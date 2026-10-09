package io.github.aliankarali.imageshrinker.core.gif

import io.github.aliankarali.imageshrinker.core.ResizePlan
import io.github.aliankarali.imageshrinker.core.Resampler
import io.github.aliankarali.imageshrinker.core.png.IntIntMap
import io.github.aliankarali.imageshrinker.core.png.Quantizer
import java.io.ByteArrayOutputStream

/**
 * Optimises GIFs the way gifsicle's `-O2` does for the desktop app: without
 * changing a single displayed pixel unless a resize or crop was asked for.
 *
 * Three encodings are tried and the smallest kept:
 *  - **repack**: the original frames as they are, with palettes trimmed to the
 *    entries in use, comments and unknown extensions dropped, and LZW redone;
 *  - **diff**: each frame reduced to the rectangle that changed since the last,
 *    with unchanged pixels inside it made transparent;
 *  - **full**: every frame drawn whole, cleared after display — the fallback when
 *    an animation erases pixels, which a diff over "leave in place" can't express.
 *
 * Every frame of an animation is kept; a resize applies to each of them.
 */
object GifOptimizer {

    fun optimize(bytes: ByteArray, resize: ResizePlan? = null): ByteArray {
        val image = GifDecoder.decode(bytes)
        val encoder = Lzw.Encoder()
        if (resize != null) return resized(image, resize, encoder)

        val candidates = ArrayList<ByteArray>(3)
        repack(image, encoder)?.let(candidates::add)
        val canvases = GifDecoder.composite(image)
        val delays = image.frames.map { it.delay }
        rebuild(image.width, image.height, canvases, delays, image.loopCount, encoder)?.let(candidates::add)
        return candidates.minByOrNull { it.size } ?: bytes
    }

    // ---- repack -------------------------------------------------------------------

    private fun repack(image: GifImage, encoder: Lzw.Encoder): ByteArray? {
        if (image.frames.any { it.localPalette == null && image.globalPalette == null }) return null

        var globalUsed = -1
        for (f in image.frames) if (f.localPalette == null) globalUsed = maxOf(globalUsed, maxIndex(f))
        val global = image.globalPalette?.takeIf { globalUsed >= 0 }?.let { trim(it, globalUsed) }

        // Indices past the end of their palette are drawn differently by every
        // decoder; leave such files to the rebuild, which works from pixels.
        if (image.frames.any { maxIndex(it) >= (it.localPalette ?: image.globalPalette)!!.size }) return null

        val frames = image.frames.map { f ->
            val local = f.localPalette?.let { trim(it, maxIndex(f)) }
            EncodedFrame(f.left, f.top, f.width, f.height, local, f.indices, f.transparentIndex, f.disposal, f.delay)
        }
        return GifWriter.write(image.width, image.height, global, image.loopCount, frames, encoder)
    }

    private fun maxIndex(f: GifFrame): Int {
        var max = 0
        for (b in f.indices) max = maxOf(max, b.toInt() and 0xFF)
        return maxOf(max, f.transparentIndex)
    }

    private fun trim(palette: IntArray, maxIndex: Int): IntArray =
        palette.copyOf(minOf(palette.size, paletteSizeFor(maxIndex + 1)))

    // ---- rebuild from composited frames ----------------------------------------------

    private class Built(val frame: EncodedFrame, val colors: IntArray)

    /**
     * Re-encodes from what is actually on screen after each frame. Returns null
     * when some frame can't be paletted losslessly (more than 256 colours in it).
     */
    internal fun rebuild(
        width: Int,
        height: Int,
        canvases: List<IntArray>,
        delays: List<Int>,
        loopCount: Int?,
        encoder: Lzw.Encoder,
    ): ByteArray? {
        val diff = diffFrames(width, height, canvases, delays)
        val full = fullFrames(width, height, canvases, delays)
        val options = listOfNotNull(diff, full).mapNotNull { encodeBuilt(width, height, it, loopCount, encoder) }
        return options.minByOrNull { it.size }
    }

    /** Frames as changed rectangles drawn over the previous screen ("leave in place"). */
    private fun diffFrames(width: Int, height: Int, canvases: List<IntArray>, delays: List<Int>): List<Built>? {
        val built = ArrayList<Built>()
        var previous = IntArray(width * height)
        for ((i, canvas) in canvases.withIndex()) {
            // A pixel that goes from visible to clear can't be drawn over the last
            // frame — only disposal can clear it, which the full encoding handles.
            for (p in canvas.indices) if (canvas[p] ushr 24 == 0 && previous[p] ushr 24 != 0) return null

            var minX = width; var minY = height; var maxX = -1; var maxY = -1
            for (y in 0 until height) for (x in 0 until width) {
                val p = y * width + x
                if (canvas[p] != previous[p]) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            if (maxX < 0) {
                if (built.isNotEmpty()) {
                    // Nothing changed: fold this frame's time into the last one.
                    val last = built.removeAt(built.size - 1)
                    built += Built(last.frame.withDelay(last.frame.delay + delays[i]), last.colors)
                    continue
                }
                minX = 0; minY = 0; maxX = 0; maxY = 0
            }
            val w = maxX - minX + 1
            val h = maxY - minY + 1
            val pixels = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val p = (minY + y) * width + minX + x
                pixels[y * w + x] = if (canvas[p] == previous[p]) TRANSPARENT else canvas[p]
            }
            val frame = paletted(minX, minY, w, h, pixels, disposal = 1, delay = delays[i]) ?: return null
            built += frame
            previous = canvas
        }
        return built
    }

    /** Every frame drawn whole and cleared after display. */
    private fun fullFrames(width: Int, height: Int, canvases: List<IntArray>, delays: List<Int>): List<Built>? {
        val built = ArrayList<Built>()
        var previous: IntArray? = null
        for ((i, canvas) in canvases.withIndex()) {
            if (previous != null && canvas.contentEquals(previous) && built.isNotEmpty()) {
                val last = built.removeAt(built.size - 1)
                built += Built(last.frame.withDelay(last.frame.delay + delays[i]), last.colors)
                continue
            }
            val pixels = IntArray(canvas.size) { if (canvas[it] ushr 24 == 0) TRANSPARENT else canvas[it] }
            built += paletted(0, 0, width, height, pixels, disposal = 2, delay = delays[i]) ?: return null
            previous = canvas
        }
        return built
    }

    /** Palettes one frame exactly; [TRANSPARENT] pixels map to a transparent slot. */
    private fun paletted(left: Int, top: Int, w: Int, h: Int, pixels: IntArray, disposal: Int, delay: Int): Built? {
        val slots = IntIntMap(512)
        var hasTransparent = false
        for (c in pixels) {
            if (c == TRANSPARENT) { hasTransparent = true; continue }
            if (slots.get(c) < 0) {
                if (slots.size == 256) return null
                slots.put(c, slots.size)
            }
        }
        if (slots.size + (if (hasTransparent) 1 else 0) > 256) return null
        val colors = IntArray(slots.size)
        slots.forEach { c, i -> colors[i] = c or OPAQUE }
        val transparentIndex = if (hasTransparent) slots.size else -1
        val indices = ByteArray(pixels.size) { p ->
            val c = pixels[p]
            (if (c == TRANSPARENT) transparentIndex else slots.get(c)).toByte()
        }
        val frame = EncodedFrame(left, top, w, h, null, indices, transparentIndex, disposal, delay)
        return Built(frame, colors)
    }

    /**
     * Gives every frame a palette: one shared global palette when all frames'
     * colours fit in 256 together, otherwise a local palette per frame.
     */
    private fun encodeBuilt(width: Int, height: Int, built: List<Built>, loopCount: Int?, encoder: Lzw.Encoder): ByteArray? {
        val union = LinkedHashSet<Int>()
        for (b in built) for (c in b.colors) union += c
        val needsTransparent = built.any { it.frame.transparentIndex >= 0 }

        if (union.size + (if (needsTransparent) 1 else 0) <= 256) {
            val global = IntArray(union.size + (if (needsTransparent) 1 else 0))
            val position = HashMap<Int, Int>()
            for ((i, c) in union.withIndex()) { global[i] = c; position[c] = i }
            val transparentIndex = if (needsTransparent) union.size else -1
            if (needsTransparent) global[transparentIndex] = 0 or OPAQUE // value is irrelevant; black
            val padded = global.copyOf(paletteSizeFor(global.size))
            for (i in global.size until padded.size) padded[i] = OPAQUE
            val frames = built.map { b ->
                val remap = IntArray(256)
                for ((i, c) in b.colors.withIndex()) remap[i] = position.getValue(c)
                if (b.frame.transparentIndex >= 0) remap[b.frame.transparentIndex] = transparentIndex
                val indices = ByteArray(b.frame.indices.size) { remap[b.frame.indices[it].toInt() and 0xFF].toByte() }
                b.frame.copy(palette = null, indices = indices, transparentIndex = if (b.frame.transparentIndex >= 0) transparentIndex else -1)
            }
            return GifWriter.write(width, height, padded, loopCount, frames, encoder)
        }

        val frames = built.map { b ->
            val size = b.colors.size + (if (b.frame.transparentIndex >= 0) 1 else 0)
            val local = IntArray(paletteSizeFor(size)) { OPAQUE }
            b.colors.copyInto(local)
            b.frame.copy(palette = local)
        }
        return GifWriter.write(width, height, null, loopCount, frames, encoder)
    }

    // ---- resize / crop ------------------------------------------------------------------

    private fun resized(image: GifImage, plan: ResizePlan, encoder: Lzw.Encoder): ByteArray {
        val canvases = GifDecoder.composite(image)
        val crop = plan.cropRect
        val out = plan.outputSize
        val scaled = canvases.map { canvas ->
            val cropped = Resampler.crop(canvas, image.width, image.height, crop)
            val resized = Resampler.resize(cropped, crop.width, crop.height, out.width, out.height)
            // GIF transparency is all or nothing: threshold the alpha the
            // resampler blended at the edges.
            IntArray(resized.size) { i ->
                val c = resized[i]
                if (c ushr 24 < 128) 0 else c or OPAQUE
            }
        }

        // One palette for the whole animation keeps colours from flickering
        // between frames, and dithering is left off for the same reason.
        val all = IntArray(scaled.sumOf { it.size })
        var offset = 0
        for (s in scaled) { s.copyInto(all, offset); offset += s.size }
        val palette = Quantizer.buildPalette(all, maxColors = 255).filter { it ushr 24 != 0 }.toIntArray()
        val mapped = scaled.map { s ->
            val m = Quantizer.mapToPalette(s, palette)
            IntArray(s.size) { if (s[it] == 0) 0 else m[it] }
        }
        return rebuild(out.width, out.height, mapped, image.frames.map { it.delay }, image.loopCount, encoder)
            ?: throw GifFormatException("could not re-encode the resized GIF")
    }

    // ---- shared -------------------------------------------------------------------------

    /** Sentinel for "leave this pixel transparent"; real colours always carry alpha 0xFF here. */
    private const val TRANSPARENT = 0
    private const val OPAQUE = -0x1000000

    internal fun paletteSizeFor(entries: Int): Int {
        var size = 2
        while (size < entries) size = size shl 1
        return size
    }
}

internal data class EncodedFrame(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    /** Local palette, or null to use the global one. */
    val palette: IntArray?,
    val indices: ByteArray,
    val transparentIndex: Int,
    val disposal: Int,
    val delay: Int,
) {
    fun withDelay(delay: Int) = copy(delay = delay)
}

internal object GifWriter {

    fun write(
        width: Int,
        height: Int,
        globalPalette: IntArray?,
        loopCount: Int?,
        frames: List<EncodedFrame>,
        encoder: Lzw.Encoder,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        u16(out, width)
        u16(out, height)
        if (globalPalette != null) {
            out.write(0x80 or 0x70 or (bits(globalPalette.size) - 1))
        } else {
            out.write(0x70)
        }
        out.write(0) // background index
        out.write(0) // aspect ratio
        globalPalette?.let { palette(out, it) }

        if (loopCount != null && frames.size > 1) {
            out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
            out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            out.write(byteArrayOf(0x03, 0x01))
            u16(out, loopCount)
            out.write(0)
        }

        for (f in frames) {
            if (f.delay != 0 || f.transparentIndex >= 0 || f.disposal != 0) {
                out.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04))
                out.write(((f.disposal and 0x07) shl 2) or (if (f.transparentIndex >= 0) 1 else 0))
                u16(out, f.delay)
                out.write(if (f.transparentIndex >= 0) f.transparentIndex else 0)
                out.write(0)
            }
            out.write(0x2C)
            u16(out, f.left); u16(out, f.top); u16(out, f.width); u16(out, f.height)
            val paletteSize = (f.palette ?: globalPalette)?.size ?: 256
            if (f.palette != null) {
                out.write(0x80 or (bits(f.palette.size) - 1))
                palette(out, f.palette)
            } else {
                out.write(0)
            }
            val minCodeSize = maxOf(2, bits(paletteSize))
            out.write(minCodeSize)
            val data = encoder.encode(f.indices, minCodeSize)
            var pos = 0
            while (pos < data.size) {
                val n = minOf(255, data.size - pos)
                out.write(n)
                out.write(data, pos, n)
                pos += n
            }
            out.write(0)
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    /** log2 of a power-of-two palette size, at least 1. */
    private fun bits(size: Int): Int {
        var b = 1
        while ((1 shl b) < size) b++
        return b
    }

    private fun palette(out: ByteArrayOutputStream, colors: IntArray) {
        for (c in colors) {
            out.write((c shr 16) and 0xFF)
            out.write((c shr 8) and 0xFF)
            out.write(c and 0xFF)
        }
    }

    private fun u16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }
}
