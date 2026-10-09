package io.github.aliankarali.imageshrinker

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aliankarali.imageshrinker.core.JpegIcc
import io.github.aliankarali.imageshrinker.engine.MozJpeg
import io.github.aliankarali.imageshrinker.engine.RasterCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class MozJpegTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets
    private val codec by lazy { RasterCodec(context) }

    private fun asset(path: String) = assets.open(path).use { it.readBytes() }

    /** A binary PPM (P6) as an opaque sRGB bitmap. */
    private fun ppm(path: String): Bitmap {
        val bytes = asset(path)
        val header = String(bytes, 0, 32, Charsets.US_ASCII).split(Regex("\\s+"))
        check(header[0] == "P6" && header[3] == "255")
        val (w, h) = header[1].toInt() to header[2].toInt()
        val start = bytes.size - w * h * 3
        val pixels = IntArray(w * h) { i ->
            val o = start + i * 3
            Color.rgb(bytes[o].toInt() and 0xFF, bytes[o + 1].toInt() and 0xFF, bytes[o + 2].toInt() and 0xFF)
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun decode(jpeg: ByteArray): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(jpeg))) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    /** (marker, luma h×v sampling) of the frame header. */
    private fun frame(jpeg: ByteArray): Pair<Int, String> {
        var i = 2
        while (i + 4 < jpeg.size) {
            val marker = jpeg[i + 1].toInt() and 0xFF
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (marker in 0xC0..0xC2) {
                val sampling = jpeg[i + 11].toInt() and 0xFF
                return marker to "${sampling shr 4}x${sampling and 15}"
            }
            i += 2 + length
        }
        error("no frame header")
    }

    @Test fun libraryLoads() {
        assertTrue(MozJpeg.isAvailable)
    }

    /** The same bytes as the desktop app's cjpeg for the same pixels, at every setting. */
    @Test fun outputIsByteIdenticalToTheDesktopEncoder() {
        val pixels = ppm("mozjpeg/odd.ppm")
        for ((quality, golden) in listOf(null to "standard", 40 to "q40", 85 to "q85", 92 to "q92")) {
            assertArrayEquals("quality $golden", asset("mozjpeg/odd-$golden.jpg"), MozJpeg.encode(pixels, quality, null))
        }
    }

    @Test fun subsamplingFollowsQualityAsOnTheDesktop() {
        val pixels = ppm("mozjpeg/odd.ppm")
        assertEquals(0xC2 to "2x2", frame(MozJpeg.encode(pixels, null, null))) // progressive 4:2:0
        assertEquals("2x1", frame(MozJpeg.encode(pixels, 85, null)).second)   // 4:2:2 from 80
        assertEquals("1x1", frame(MozJpeg.encode(pixels, 92, null)).second)   // 4:4:4 from 90
    }

    @Test fun smallerThanThePlatformEncoderAtTheSameSetting() {
        val photo = ppm("mozjpeg/odd.ppm")
        val platform = ByteArrayOutputStream().also { photo.compress(Bitmap.CompressFormat.JPEG, 75, it) }.size()
        val moz = ByteArrayOutputStream().also { codec.encodeJpeg(photo, null, it) }.size()
        assertTrue("mozjpeg $moz B vs platform $platform B", moz < platform)
    }

    @Test fun ordinaryBitmapsUseMozjpegAndTheRestThePlatform() {
        val plain = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        assertEquals(RasterCodec.JpegEncoder.MOZJPEG, codec.jpegEncoderFor(plain))
        val deep = Bitmap.createBitmap(8, 8, Bitmap.Config.RGBA_F16)
        assertEquals(RasterCodec.JpegEncoder.PLATFORM, codec.jpegEncoderFor(deep))
    }

    @Test fun ultraHdrKeepsItsGainMapThroughThePlatformEncoder() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val base = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        base.gainmap = Gainmap(Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) })
        assertEquals(RasterCodec.JpegEncoder.PLATFORM, codec.jpegEncoderFor(base))
        val jpeg = ByteArrayOutputStream().also { codec.encodeJpeg(base, null, it) }.toByteArray()
        assertTrue("gain map should survive", decode(jpeg).hasGainmap())
    }

    @Test fun wideGamutPhotosKeepTheirColourProfile() {
        val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888, false, p3).apply { eraseColor(Color.RED) }
        val jpeg = ByteArrayOutputStream().also { codec.encodeJpeg(bitmap, null, it) }.toByteArray()
        assertEquals("written by mozjpeg", 0xC2, frame(jpeg).first)
        assertNotNull("profile embedded", JpegIcc.extract(jpeg))
        assertTrue("decodes as Display P3", decode(jpeg).colorSpace!!.let { it == p3 || it.name == p3.name })
    }

    @Test fun srgbNeedsNoProfile() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val jpeg = ByteArrayOutputStream().also { codec.encodeJpeg(bitmap, null, it) }.toByteArray()
        assertNull(JpegIcc.extract(jpeg))
    }

    /** Not a pass/fail check: logs encode times for a 12-megapixel image. */
    @Test fun timing() {
        val photo = ppm("mozjpeg/odd.ppm").let { Bitmap.createScaledBitmap(it, 4000, 3000, true) }
        fun best(block: () -> Unit) = (1..3).minOf { val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1_000_000 }
        val moz = best { MozJpeg.encode(photo, null, null) }
        val platform = best { photo.compress(Bitmap.CompressFormat.JPEG, 75, ByteArrayOutputStream()) }
        Log.i("MozJpegTiming", "12 MP: mozjpeg ${moz} ms, platform ${platform} ms")
    }
}
