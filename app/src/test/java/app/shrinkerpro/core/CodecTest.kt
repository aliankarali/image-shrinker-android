package app.shrinkerpro.core

import app.shrinkerpro.core.gif.GifDecoder
import app.shrinkerpro.core.gif.GifOptimizer
import app.shrinkerpro.core.png.PngWriter
import app.shrinkerpro.core.png.Quantizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Random
import javax.imageio.ImageIO

private fun fixture(name: String): ByteArray =
    CodecTest::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes()

private fun BufferedImage.argb(): IntArray = getRGB(0, 0, width, height, null, 0, width)

/** Reads a PNG back with the JDK's decoder, an implementation independent of ours. */
private fun readPng(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))!!

class CodecTest {

    // ---- PNG ----------------------------------------------------------------------

    @Test fun fewColourImageIsPalettedLosslessly() {
        val w = 64; val h = 48
        val colours = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0x800000FF.toInt(), 0x00000000)
        val pixels = IntArray(w * h) { colours[(it / 7) % colours.size] }
        val png = PngWriter.write(Quantizer.quantize(pixels, w, h))
        val back = readPng(png).argb()
        // Fully transparent pixels compare as transparent; their RGB is irrelevant.
        for (i in pixels.indices) {
            if (pixels[i] ushr 24 == 0) assertEquals(0, back[i] ushr 24) else assertEquals(pixels[i], back[i])
        }
        // Four colours fit in 2 bits per pixel.
        assertEquals(2, png[24].toInt())
    }

    @Test fun twoColourImageUsesOneBitPerPixel() {
        val pixels = IntArray(33 * 5) { if (it % 3 == 0) -1 else 0xFF000000.toInt() }
        val png = PngWriter.write(Quantizer.quantize(pixels, 33, 5))
        assertEquals(1, png[24].toInt())
        assertArrayEquals(pixels, readPng(png).argb())
    }

    @Test fun photographicImageIsReducedToAPaletteThatStaysClose() {
        val png = readPng(fixture("sample.png"))
        val src = png.argb()
        val indexed = Quantizer.quantize(src, png.width, png.height)
        assertTrue(indexed.palette.size <= 256)
        val out = PngWriter.write(indexed)
        val back = readPng(out).argb()
        var error = 0.0
        var counted = 0
        for (i in src.indices) {
            val a = src[i]; val b = back[i]
            if (a ushr 24 == 0) { assertEquals("transparent stays transparent", 0, b ushr 24); continue }
            for (shift in intArrayOf(0, 8, 16, 24)) error += kotlin.math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
            counted++
        }
        val meanPerChannel = error / (counted * 4.0)
        assertTrue("mean channel error $meanPerChannel too high", meanPerChannel < 6.0)
        assertTrue("quantised ${out.size} not smaller than original 244413", out.size < 244_413)
    }

    @Test fun manyColourGradientIsQuantised() {
        val w = 256; val h = 256
        val pixels = IntArray(w * h) { i -> val x = i % w; val y = i / w; (0xFF shl 24) or (x shl 16) or (y shl 8) or ((x + y) / 2) }
        val indexed = Quantizer.quantize(pixels, w, h)
        assertEquals(256, indexed.palette.size)
        val back = readPng(PngWriter.write(indexed)).argb()
        assertEquals(pixels.size, back.size)
    }

    // ---- LZW / GIF -------------------------------------------------------------------

    @Test fun lzwRoundTripsAcrossCodeSizesAndTableResets() {
        val random = Random(42)
        for (bits in 2..8) {
            for (length in intArrayOf(0, 1, 2, 300, 70_000)) {
                val maxValue = 1 shl bits
                // Mix runs and noise so the table both fills and resets.
                val data = ByteArray(length) { i -> (if (i % 97 < 50) (i / 13) % maxValue else random.nextInt(maxValue)).toByte() }
                val encoded = app.shrinkerpro.core.gif.LzwAccess.encode(data, maxOf(2, bits))
                val decoded = app.shrinkerpro.core.gif.LzwAccess.decode(encoded, maxOf(2, bits), length)
                assertArrayEquals("bits=$bits length=$length", data, decoded)
            }
        }
    }

    @Test fun decoderMatchesTheJdkOnTheFirstFrame() {
        for (name in listOf("sample.gif", "offsetframes.gif")) {
            val bytes = fixture(name)
            val decoded = GifDecoder.decode(bytes)
            val ours = GifDecoder.composite(decoded).first()
            // The JDK returns the first frame's own raster, not the logical
            // screen, so compare inside that frame's rectangle.
            val frame = decoded.frames.first()
            val jdk = ImageIO.read(ByteArrayInputStream(bytes))
            assertEquals(frame.width, jdk.width)
            assertEquals(frame.height, jdk.height)
            val theirs = jdk.argb()
            for (y in 0 until frame.height) for (x in 0 until frame.width) {
                val o = ours[(frame.top + y) * decoded.width + frame.left + x]
                val t = theirs[y * frame.width + x]
                if (o ushr 24 == 0) assertEquals("$name ($x,$y)", 0, t ushr 24)
                else assertEquals("$name ($x,$y)", o, t)
            }
        }
    }

    @Test fun optimisingFixturesIsPixelExact() {
        for (name in listOf("sample.gif", "offsetframes.gif")) {
            val bytes = fixture(name)
            val optimised = GifOptimizer.optimize(bytes)
            assertDisplaysTheSame(name, GifDecoder.decode(bytes), GifDecoder.decode(optimised))
            assertTrue("$name: ${optimised.size} > ${bytes.size}", optimised.size <= bytes.size)
        }
    }

    @Test fun animationThatErasesPixelsSurvivesOptimisation() {
        // Frame 1 draws a block, frame 2 clears it with "restore to background",
        // frame 3 draws elsewhere with "restore to previous".
        val gif = SyntheticGif.build(
            width = 20, height = 10,
            frames = listOf(
                SyntheticGif.Frame(0, 0, 20, 10, fill = 1, transparent = -1, disposal = 2, delay = 10),
                SyntheticGif.Frame(5, 2, 4, 4, fill = 2, transparent = -1, disposal = 3, delay = 20),
                SyntheticGif.Frame(10, 5, 3, 3, fill = 3, transparent = -1, disposal = 0, delay = 30),
                SyntheticGif.Frame(10, 5, 3, 3, fill = 3, transparent = -1, disposal = 0, delay = 40),
            ),
        )
        val optimised = GifOptimizer.optimize(gif)
        assertDisplaysTheSame("synthetic", GifDecoder.decode(gif), GifDecoder.decode(optimised))
        // The repeated last frame is folded into its predecessor's time.
        val totalBefore = GifDecoder.decode(gif).frames.sumOf { it.delay }
        val totalAfter = GifDecoder.decode(optimised).frames.sumOf { it.delay }
        assertEquals(totalBefore, totalAfter)
    }

    @Test fun croppedGifKeepsEveryFrame() {
        val bytes = fixture("offsetframes.gif")
        val source = GifDecoder.decode(bytes)
        assertTrue("fixture must be animated", source.frames.size > 1)
        val plan = SizePlanner.plan(PixelSize(source.width, source.height), null, CropTarget(120, 68, CropTarget.Mode.PIXELS))!!
        val out = GifDecoder.decode(GifOptimizer.optimize(bytes, plan))
        assertEquals(120, out.width)
        assertEquals(68, out.height)
        assertEquals(source.frames.sumOf { it.delay }, out.frames.sumOf { it.delay })
        assertTrue(out.frames.size > 1)
    }

    @Test fun resizedGifHonoursTheCap() {
        val bytes = fixture("sample.gif")
        val plan = SizePlanner.plan(PixelSize(120, 120), 50, null)!!
        val out = GifDecoder.decode(GifOptimizer.optimize(bytes, plan))
        assertEquals(50, out.width)
        assertEquals(50, out.height)
        assertEquals(GifDecoder.decode(bytes).frames.size, out.frames.size)
    }

    /** Compares what is on screen at every instant, so frame merges don't count as differences. */
    private fun assertDisplaysTheSame(name: String, a: app.shrinkerpro.core.gif.GifImage, b: app.shrinkerpro.core.gif.GifImage) {
        fun timeline(img: app.shrinkerpro.core.gif.GifImage): List<Pair<Int, IntArray>> {
            val canvases = GifDecoder.composite(img)
            val out = ArrayList<Pair<Int, IntArray>>()
            for ((i, c) in canvases.withIndex()) {
                val delay = img.frames[i].delay
                if (out.isNotEmpty() && out.last().second.contentEquals(c)) {
                    out[out.size - 1] = out.last().first + delay to c
                } else out += delay to c
            }
            return out
        }
        val ta = timeline(a); val tb = timeline(b)
        assertEquals("$name: screen states", ta.size, tb.size)
        for (i in ta.indices) {
            assertEquals("$name: delay of state $i", ta[i].first, tb[i].first)
            assertArrayEquals("$name: pixels of state $i", ta[i].second, tb[i].second)
        }
    }

    // ---- resampling ----------------------------------------------------------------------

    @Test fun areaAverageOfFourPixels() {
        val src = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        val out = Resampler.resize(src, 2, 2, 1, 1)
        val v = out[0] and 0xFF
        assertTrue("got $v", v in 127..128)
    }

    @Test fun transparentNeighboursDoNotDarkenEdges() {
        val src = intArrayOf(0xFFFF0000.toInt(), 0x0000FF00)
        val out = Resampler.resize(src, 2, 1, 1, 1)[0]
        assertEquals(0xFF, (out shr 16) and 0xFF)
        assertEquals(0, (out shr 8) and 0xFF)
        assertTrue((out ushr 24) in 127..128)
    }

    @Test fun uniformColourSurvivesUnevenScaling() {
        val src = IntArray(7 * 5) { 0xFF336699.toInt() }
        for (v in Resampler.resize(src, 7, 5, 3, 2)) assertEquals(0xFF336699.toInt(), v)
    }
}

/** Builds small GIFs with chosen disposal methods, for cases the fixtures don't cover. */
object SyntheticGif {
    class Frame(val left: Int, val top: Int, val width: Int, val height: Int, val fill: Int, val transparent: Int, val disposal: Int, val delay: Int)

    fun build(width: Int, height: Int, frames: List<Frame>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("GIF89a".toByteArray())
        fun u16(v: Int) { out.write(v and 0xFF); out.write(v shr 8) }
        u16(width); u16(height)
        out.write(0x80 or 0x01) // global palette of 4 entries
        out.write(0); out.write(0)
        for (c in intArrayOf(0x000000, 0xFF0000, 0x00FF00, 0x0000FF)) { out.write(c shr 16); out.write((c shr 8) and 0xFF); out.write(c and 0xFF) }
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B)); out.write("NETSCAPE2.0".toByteArray()); out.write(byteArrayOf(3, 1, 0, 0, 0))
        for (f in frames) {
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4))
            out.write((f.disposal shl 2) or (if (f.transparent >= 0) 1 else 0))
            u16(f.delay); out.write(maxOf(0, f.transparent)); out.write(0)
            out.write(0x2C); u16(f.left); u16(f.top); u16(f.width); u16(f.height); out.write(0)
            out.write(2)
            val data = app.shrinkerpro.core.gif.LzwAccess.encode(ByteArray(f.width * f.height) { f.fill.toByte() }, 2)
            var p = 0
            while (p < data.size) { val n = minOf(255, data.size - p); out.write(n); out.write(data, p, n); p += n }
            out.write(0)
        }
        out.write(0x3B)
        return out.toByteArray()
    }
}
