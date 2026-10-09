package io.github.aliankarali.imageshrinker.core.gif

import java.io.IOException

/** A parsed GIF: palettes are opaque ARGB ints (0xFFRRGGBB). */
class GifImage(
    val width: Int,
    val height: Int,
    val globalPalette: IntArray?,
    val backgroundIndex: Int,
    /** `null` when the file has no NETSCAPE2.0 block (plays once); 0 loops forever. */
    val loopCount: Int?,
    val frames: List<GifFrame>,
)

class GifFrame(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val localPalette: IntArray?,
    /** Palette index per pixel, row-major, already de-interlaced. */
    val indices: ByteArray,
    /** -1 for none. */
    val transparentIndex: Int,
    /** 0/1 leave in place, 2 restore to background (transparent), 3 restore previous. */
    val disposal: Int,
    /** Hundredths of a second. */
    val delay: Int,
)

class GifFormatException(message: String) : IOException(message)

object GifDecoder {

    fun decode(bytes: ByteArray): GifImage {
        val r = Reader(bytes)
        val signature = String(bytes, 0, minOf(6, bytes.size), Charsets.US_ASCII)
        if (signature != "GIF87a" && signature != "GIF89a") throw GifFormatException("not a GIF")
        r.skip(6)

        val width = r.u16()
        val height = r.u16()
        val packed = r.u8()
        val backgroundIndex = r.u8()
        r.u8() // pixel aspect ratio
        val globalPalette = if (packed and 0x80 != 0) r.palette(1 shl ((packed and 0x07) + 1)) else null

        var loopCount: Int? = null
        val frames = ArrayList<GifFrame>()
        var transparentIndex = -1
        var disposal = 0
        var delay = 0

        loop@ while (r.hasMore()) {
            when (r.u8()) {
                0x21 -> {
                    val label = r.u8()
                    when (label) {
                        0xF9 -> {
                            val size = r.u8()
                            val start = r.position
                            val flags = r.u8()
                            delay = r.u16()
                            val t = r.u8()
                            disposal = (flags shr 2) and 0x07
                            transparentIndex = if (flags and 0x01 != 0) t else -1
                            r.position = start + size
                            r.skipSubBlocks()
                        }
                        0xFF -> {
                            val blocks = r.subBlocks()
                            val id = blocks.firstOrNull()
                            if (id != null && id.size >= 11 &&
                                String(id, 0, 11, Charsets.US_ASCII).let { it == "NETSCAPE2.0" || it == "ANIMEXTS1.0" }
                            ) {
                                val data = blocks.getOrNull(1)
                                if (data != null && data.size >= 3 && data[0].toInt() == 1) {
                                    loopCount = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
                                }
                            }
                        }
                        else -> r.skipSubBlocks() // comments, plain text, unknown
                    }
                }
                0x2C -> {
                    val left = r.u16()
                    val top = r.u16()
                    val w = r.u16()
                    val h = r.u16()
                    val flags = r.u8()
                    val local = if (flags and 0x80 != 0) r.palette(1 shl ((flags and 0x07) + 1)) else null
                    val interlaced = flags and 0x40 != 0
                    val minCodeSize = r.u8()
                    val data = r.subBlocksConcatenated()
                    val fill = if (transparentIndex >= 0) transparentIndex else 0
                    var indices = Lzw.decode(data, minCodeSize, w * h, fill)
                    if (interlaced) indices = deinterlace(indices, w, h)
                    frames += GifFrame(left, top, w, h, local, indices, transparentIndex, disposal, delay)
                    transparentIndex = -1
                    disposal = 0
                    delay = 0
                }
                0x3B -> break@loop
                0x00 -> continue@loop // stray padding some encoders leave
                else -> break@loop // garbage: keep what decoded so far, as browsers do
            }
        }
        if (frames.isEmpty()) throw GifFormatException("GIF has no frames")
        return GifImage(width, height, globalPalette, backgroundIndex, loopCount, frames)
    }

    private fun deinterlace(src: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(src.size)
        var row = 0
        for ((start, step) in arrayOf(0 to 8, 4 to 8, 2 to 4, 1 to 2)) {
            var y = start
            while (y < h) {
                System.arraycopy(src, row * w, out, y * w, w)
                row++
                y += step
            }
        }
        return out
    }

    /**
     * Renders every frame onto the logical screen and returns what is on
     * screen after each one, as non-premultiplied ARGB. The background is
     * transparent, as every browser draws it.
     */
    fun composite(image: GifImage): List<IntArray> {
        val w = image.width
        val h = image.height
        var canvas = IntArray(w * h)
        val result = ArrayList<IntArray>(image.frames.size)
        for (frame in image.frames) {
            val palette = frame.localPalette ?: image.globalPalette ?: GRAYSCALE
            val saved = if (frame.disposal == 3) canvas.copyOf() else null
            for (y in 0 until frame.height) {
                val cy = frame.top + y
                if (cy !in 0 until h) continue
                for (x in 0 until frame.width) {
                    val cx = frame.left + x
                    if (cx !in 0 until w) continue
                    val index = frame.indices[y * frame.width + x].toInt() and 0xFF
                    if (index == frame.transparentIndex) continue
                    canvas[cy * w + cx] = if (index < palette.size) palette[index] else OPAQUE_BLACK
                }
            }
            result += canvas.copyOf()
            when (frame.disposal) {
                2 -> for (y in maxOf(0, frame.top) until minOf(h, frame.top + frame.height)) {
                    for (x in maxOf(0, frame.left) until minOf(w, frame.left + frame.width)) canvas[y * w + x] = 0
                }
                3 -> canvas = saved!!
            }
        }
        return result
    }

    private const val OPAQUE_BLACK = -0x1000000
    private val GRAYSCALE = IntArray(256) { (0xFF shl 24) or (it shl 16) or (it shl 8) or it }

    private class Reader(private val bytes: ByteArray) {
        var position = 0
        fun hasMore() = position < bytes.size
        fun skip(n: Int) { position += n }
        fun u8(): Int {
            if (position >= bytes.size) throw GifFormatException("truncated GIF")
            return bytes[position++].toInt() and 0xFF
        }
        fun u16(): Int = u8() or (u8() shl 8)
        fun palette(entries: Int): IntArray = IntArray(entries) {
            (0xFF shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        }
        fun skipSubBlocks() {
            while (hasMore()) {
                val size = u8()
                if (size == 0) return
                position += size
            }
        }
        fun subBlocks(): List<ByteArray> {
            val blocks = ArrayList<ByteArray>()
            while (hasMore()) {
                val size = u8()
                if (size == 0) break
                val end = minOf(bytes.size, position + size)
                blocks += bytes.copyOfRange(position, end)
                position = end
            }
            return blocks
        }
        fun subBlocksConcatenated(): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            while (hasMore()) {
                val size = u8()
                if (size == 0) break
                val end = minOf(bytes.size, position + size)
                out.write(bytes, position, end - position)
                position = end
            }
            return out.toByteArray()
        }
    }
}

internal object Lzw {

    /** Decodes up to [pixelCount] indices; a short or damaged stream is padded with [fill]. */
    fun decode(data: ByteArray, minCodeSize: Int, pixelCount: Int, fill: Int): ByteArray {
        val out = ByteArray(pixelCount)
        if (minCodeSize !in 1..11) {
            java.util.Arrays.fill(out, fill.toByte())
            return out
        }
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val length = IntArray(4096)
        for (i in 0 until clear) { suffix[i] = i.toByte(); length[i] = 1; prefix[i] = -1 }

        var codeSize = minCodeSize + 1
        var next = eoi + 1
        var previous = -1
        var outPos = 0
        var bitBuffer = 0
        var bitCount = 0
        var dataPos = 0
        val stack = ByteArray(4097)

        decode@ while (outPos < pixelCount) {
            while (bitCount < codeSize) {
                if (dataPos >= data.size) break@decode
                bitBuffer = bitBuffer or ((data[dataPos++].toInt() and 0xFF) shl bitCount)
                bitCount += 8
            }
            val code = bitBuffer and ((1 shl codeSize) - 1)
            bitBuffer = bitBuffer ushr codeSize
            bitCount -= codeSize

            if (code == clear) {
                codeSize = minCodeSize + 1
                next = eoi + 1
                previous = -1
                continue
            }
            if (code == eoi) break
            if (previous == -1) {
                if (code >= clear) break // first code after a clear must be a root
                out[outPos++] = code.toByte()
                previous = code
                continue
            }

            val entry: Int
            val firstOfCode: Byte
            if (code < next) {
                entry = code
                firstOfCode = first(code, prefix, suffix)
            } else if (code == next) {
                // The KwKwK case: the code being defined right now.
                entry = -1
                firstOfCode = first(previous, prefix, suffix)
            } else {
                break // corrupt
            }

            if (next < 4096) {
                prefix[next] = previous
                suffix[next] = firstOfCode
                length[next] = length[previous] + 1
                next++
                if (next == (1 shl codeSize) && codeSize < 12) codeSize++
            }

            val emit = if (entry == -1) next - 1 else entry
            // Unwind the chain into the stack, then copy out in order.
            var n = 0
            var c = emit
            while (c >= 0 && n < stack.size) {
                stack[n++] = suffix[c]
                c = prefix[c]
            }
            while (n > 0 && outPos < pixelCount) out[outPos++] = stack[--n]
            previous = emit
        }
        if (outPos < pixelCount) java.util.Arrays.fill(out, outPos, pixelCount, fill.toByte())
        return out
    }

    private fun first(code: Int, prefix: IntArray, suffix: ByteArray): Byte {
        var c = code
        while (prefix[c] >= 0) c = prefix[c]
        return suffix[c]
    }

    /** Encoder state reused across frames: a 4096×256 code table with generation stamps. */
    class Encoder {
        private val table = IntArray(4096 * 256)
        private val stamp = IntArray(4096 * 256)
        private var generation = 0

        fun encode(indices: ByteArray, minCodeSize: Int): ByteArray {
            require(minCodeSize in 2..8)
            val out = BitWriter()
            val clear = 1 shl minCodeSize
            val eoi = clear + 1
            var codeSize = minCodeSize + 1
            var next = eoi + 1
            generation++

            out.write(clear, codeSize)
            if (indices.isEmpty()) {
                out.write(eoi, codeSize)
                return out.finish()
            }

            var prefix = indices[0].toInt() and 0xFF
            for (i in 1 until indices.size) {
                val k = indices[i].toInt() and 0xFF
                val slot = (prefix shl 8) or k
                if (stamp[slot] == generation) {
                    prefix = table[slot]
                    continue
                }
                out.write(prefix, codeSize)
                if (next >= (1 shl codeSize) && codeSize < 12) codeSize++
                if (next < 4096) {
                    stamp[slot] = generation
                    table[slot] = next++
                } else {
                    out.write(clear, codeSize)
                    generation++
                    codeSize = minCodeSize + 1
                    next = eoi + 1
                }
                prefix = k
            }
            out.write(prefix, codeSize)
            if (next >= (1 shl codeSize) && codeSize < 12) codeSize++
            out.write(eoi, codeSize)
            return out.finish()
        }
    }

    private class BitWriter {
        private val out = java.io.ByteArrayOutputStream()
        private var buffer = 0
        private var count = 0

        fun write(code: Int, size: Int) {
            buffer = buffer or (code shl count)
            count += size
            while (count >= 8) {
                out.write(buffer and 0xFF)
                buffer = buffer ushr 8
                count -= 8
            }
        }

        fun finish(): ByteArray {
            if (count > 0) out.write(buffer and 0xFF)
            buffer = 0
            count = 0
            return out.toByteArray()
        }
    }
}
