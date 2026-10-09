package app.shrinkerpro.core.png

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Writes paletted PNGs at the smallest bit depth the palette allows. */
object PngWriter {

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    fun write(image: IndexedImage): ByteArray {
        val paletteSize = image.palette.size
        require(paletteSize in 1..256)
        val bitDepth = when {
            paletteSize <= 2 -> 1
            paletteSize <= 4 -> 2
            paletteSize <= 16 -> 4
            else -> 8
        }

        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)

        val ihdr = ByteArrayOutputStream()
        DataOutputStream(ihdr).apply {
            writeInt(image.width)
            writeInt(image.height)
            writeByte(bitDepth)
            writeByte(3) // colour type: indexed
            writeByte(0) // deflate
            writeByte(0) // adaptive filtering
            writeByte(0) // no interlace
        }
        chunk(out, "IHDR", ihdr.toByteArray())

        val plte = ByteArray(paletteSize * 3)
        for (i in 0 until paletteSize) {
            val c = image.palette[i]
            plte[i * 3] = (c shr 16).toByte()
            plte[i * 3 + 1] = (c shr 8).toByte()
            plte[i * 3 + 2] = c.toByte()
        }
        chunk(out, "PLTE", plte)

        // tRNS only needs to reach the last translucent entry; the rest default to opaque.
        val lastTranslucent = image.palette.indexOfLast { it ushr 24 != 255 }
        if (lastTranslucent >= 0) {
            chunk(out, "tRNS", ByteArray(lastTranslucent + 1) { (image.palette[it] ushr 24).toByte() })
        }

        chunk(out, "IDAT", compressedScanlines(image, bitDepth))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /**
     * Paletted data usually compresses best unfiltered, but not always — so both
     * unfiltered and per-row adaptive filtering are tried and the smaller kept.
     */
    private fun compressedScanlines(image: IndexedImage, bitDepth: Int): ByteArray {
        val rows = packRows(image, bitDepth)
        val unfiltered = deflate(filtered(rows, adaptive = false, bitDepth))
        if (bitDepth < 8) return unfiltered
        val adaptive = deflate(filtered(rows, adaptive = true, bitDepth))
        return if (adaptive.size < unfiltered.size) adaptive else unfiltered
    }

    private fun packRows(image: IndexedImage, bitDepth: Int): Array<ByteArray> {
        val perByte = 8 / bitDepth
        val rowBytes = (image.width + perByte - 1) / perByte
        return Array(image.height) { y ->
            val row = ByteArray(rowBytes)
            val base = y * image.width
            if (bitDepth == 8) {
                System.arraycopy(image.indices, base, row, 0, image.width)
            } else {
                for (x in 0 until image.width) {
                    val v = image.indices[base + x].toInt() and 0xFF
                    val shift = 8 - bitDepth * (x % perByte + 1)
                    row[x / perByte] = (row[x / perByte].toInt() or (v shl shift)).toByte()
                }
            }
            row
        }
    }

    private fun filtered(rows: Array<ByteArray>, adaptive: Boolean, bitDepth: Int): ByteArray {
        val rowBytes = rows.firstOrNull()?.size ?: 0
        val out = ByteArray(rows.size * (rowBytes + 1))
        val bpp = maxOf(1, bitDepth / 8)
        val candidate = ByteArray(rowBytes)
        val best = ByteArray(rowBytes)
        var previous = ByteArray(rowBytes)
        var offset = 0
        for (row in rows) {
            if (!adaptive) {
                out[offset] = 0
                System.arraycopy(row, 0, out, offset + 1, rowBytes)
            } else {
                // Minimum sum of absolute differences, the heuristic libpng uses.
                var bestType = 0
                var bestScore = Long.MAX_VALUE
                for (type in 0..4) {
                    applyFilter(type, row, previous, bpp, candidate)
                    var score = 0L
                    for (b in candidate) score += kotlin.math.abs(b.toInt())
                    if (score < bestScore) {
                        bestScore = score; bestType = type
                        System.arraycopy(candidate, 0, best, 0, rowBytes)
                    }
                }
                out[offset] = bestType.toByte()
                System.arraycopy(best, 0, out, offset + 1, rowBytes)
            }
            previous = row
            offset += rowBytes + 1
        }
        return out
    }

    private fun applyFilter(type: Int, row: ByteArray, prev: ByteArray, bpp: Int, out: ByteArray) {
        for (i in row.indices) {
            val x = row[i].toInt() and 0xFF
            val a = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
            val b = prev[i].toInt() and 0xFF
            val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
            val v = when (type) {
                0 -> x
                1 -> x - a
                2 -> x - b
                3 -> x - (a + b) / 2
                else -> x - paeth(a, b, c)
            }
            out[i] = v.toByte()
        }
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(maxOf(64, data.size / 4))
            val buffer = ByteArray(64 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buffer)
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        DataOutputStream(out).writeInt(data.size)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        DataOutputStream(out).writeInt(crc.value.toInt())
    }
}
