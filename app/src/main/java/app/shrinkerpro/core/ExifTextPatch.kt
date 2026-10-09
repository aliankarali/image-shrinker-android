package app.shrinkerpro.core

import java.util.zip.CRC32

/**
 * Restores the exact bytes of EXIF text tags after ExifInterface has written them.
 *
 * ExifInterface reads every non-ASCII byte of a text tag as '?' and writes text
 * as ASCII, so "© 2026" round-trips as "?? 2026". The damage is byte-for-byte
 * (one '?' per original byte), which means the original bytes fit exactly where
 * the '?'s were written. This finds those tags in the written file and puts the
 * originals back. Anything it can't match exactly is left alone.
 */
object ExifTextPatch {

    /** Text tags in IFD0 and the EXIF sub-IFD that commonly carry non-ASCII text. */
    val TEXT_TAGS: Map<String, Int> = mapOf(
        "ImageDescription" to 0x010E,
        "Make" to 0x010F,
        "Model" to 0x0110,
        "Software" to 0x0131,
        "Artist" to 0x013B,
        "Copyright" to 0x8298,
        "ImageUniqueID" to 0xA420,
        "CameraOwnerName" to 0xA430,
        "BodySerialNumber" to 0xA431,
        "LensMake" to 0xA433,
        "LensModel" to 0xA434,
        "LensSerialNumber" to 0xA435,
    )

    private const val EXIF_IFD_POINTER = 0x8769
    private const val TYPE_ASCII = 2

    /**
     * Patches [file] (a JPEG, PNG or WebP) in place. [originals] maps tag id to
     * the source's raw value bytes. Returns whether anything changed.
     */
    fun patch(file: ByteArray, originals: Map<Int, ByteArray>): Boolean {
        if (originals.isEmpty()) return false
        val block = locate(file) ?: return false
        var changed = false
        val tiff = Tiff(file, block.offset, block.length)
        val ifd0 = tiff.firstIfd() ?: return false
        val ifds = buildList {
            add(ifd0)
            tiff.entries(ifd0).firstOrNull { it.tag == EXIF_IFD_POINTER }?.let { add(it.valueOrOffset) }
        }
        for (ifd in ifds) {
            for (entry in tiff.entries(ifd)) {
                if (entry.type != TYPE_ASCII) continue
                val original = originals[entry.tag] ?: continue
                val bytes = when (entry.count) {
                    original.size -> original
                    original.size + 1 -> original + 0 // source wasn't NUL-terminated
                    else -> continue
                }
                val at = if (entry.count <= 4) entry.entryValueOffset else block.offset + entry.valueOrOffset
                if (at < block.offset || at + bytes.size > block.offset + block.length) continue
                // Only touch bytes that are exactly the damaged form of the original.
                if (!looksDamagedFrom(file, at, bytes)) continue
                bytes.copyInto(file, at)
                changed = true
            }
        }
        if (changed && block.pngChunkStart >= 0) fixPngCrc(file, block.pngChunkStart, block.length)
        return changed
    }

    private fun looksDamagedFrom(file: ByteArray, at: Int, original: ByteArray): Boolean {
        for (i in original.indices) {
            val written = file[at + i]
            val source = original[i]
            if (written == source) continue
            if (source < 0 && written == '?'.code.toByte()) continue
            return false
        }
        return true
    }

    // ---- locating the TIFF block --------------------------------------------------------

    internal class Block(val offset: Int, val length: Int, val pngChunkStart: Int = -1)

    internal fun locate(file: ByteArray): Block? = when {
        file.size > 4 && file[0] == 0xFF.toByte() && file[1] == 0xD8.toByte() -> jpeg(file)
        file.size > 8 && file[0] == 0x89.toByte() && file[1] == 'P'.code.toByte() -> png(file)
        file.size > 12 && String(file, 0, 4, Charsets.US_ASCII) == "RIFF" && String(file, 8, 4, Charsets.US_ASCII) == "WEBP" -> webp(file)
        else -> null
    }

    private fun jpeg(file: ByteArray): Block? {
        var i = 2
        while (i + 4 <= file.size && file[i] == 0xFF.toByte()) {
            val marker = file[i + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) return null
            val length = u16be(file, i + 2)
            if (marker == 0xE1 && length >= 8 && i + 2 + length <= file.size &&
                String(file, i + 4, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000"
            ) return Block(i + 10, length - 8)
            i += 2 + length
        }
        return null
    }

    private fun png(file: ByteArray): Block? {
        var i = 8
        while (i + 12 <= file.size) {
            val length = u32be(file, i)
            val type = String(file, i + 4, 4, Charsets.US_ASCII)
            if (length < 0 || i + 12 + length > file.size) return null
            if (type == "eXIf") return Block(i + 8, length, pngChunkStart = i + 4)
            if (type == "IEND") return null
            i += 12 + length
        }
        return null
    }

    private fun webp(file: ByteArray): Block? {
        var i = 12
        while (i + 8 <= file.size) {
            val type = String(file, i, 4, Charsets.US_ASCII)
            val length = u32le(file, i + 4)
            if (length < 0 || i + 8 + length > file.size) return null
            if (type == "EXIF") {
                // Some writers keep the JPEG-style "Exif\0\0" prefix inside the chunk.
                val prefixed = length >= 6 && String(file, i + 8, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000"
                return if (prefixed) Block(i + 14, length - 6) else Block(i + 8, length)
            }
            i += 8 + length + (length and 1)
        }
        return null
    }

    private fun fixPngCrc(file: ByteArray, chunkTypeStart: Int, dataLength: Int) {
        val crc = CRC32()
        crc.update(file, chunkTypeStart, 4 + dataLength)
        val value = crc.value
        val at = chunkTypeStart + 4 + dataLength
        file[at] = (value ushr 24).toByte(); file[at + 1] = (value ushr 16).toByte()
        file[at + 2] = (value ushr 8).toByte(); file[at + 3] = value.toByte()
    }

    // ---- minimal TIFF reader ---------------------------------------------------------------

    internal class Entry(val tag: Int, val type: Int, val count: Int, val valueOrOffset: Int, val entryValueOffset: Int)

    private class Tiff(val b: ByteArray, val base: Int, val length: Int) {
        val little = b[base] == 'I'.code.toByte()

        fun u16(at: Int) = if (little) (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) else u16be(b, at)
        fun u32(at: Int) = if (little) u32le(b, at) else u32be(b, at)

        fun firstIfd(): Int? {
            if (length < 8) return null
            val magic = u16(base + 2)
            return if (magic == 42) u32(base + 4) else null
        }

        fun entries(ifd: Int): List<Entry> {
            val start = base + ifd
            if (ifd < 8 || start + 2 > base + length) return emptyList()
            val count = u16(start)
            return (0 until count).mapNotNull { n ->
                val e = start + 2 + n * 12
                if (e + 12 > base + length) return@mapNotNull null
                Entry(u16(e), u16(e + 2), u32(e + 4), u32(e + 8), e + 8)
            }
        }
    }

    private fun u16be(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    private fun u32be(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
    private fun u32le(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
}
