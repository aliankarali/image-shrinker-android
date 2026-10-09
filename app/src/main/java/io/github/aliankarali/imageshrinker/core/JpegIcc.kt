package io.github.aliankarali.imageshrinker.core

import java.io.ByteArrayOutputStream

/** Reads the ICC profile out of a JPEG's APP2 "ICC_PROFILE" segments. */
object JpegIcc {
    private val SIGNATURE = "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1)

    /**
     * The embedded profile, or null if there is none or its chunks are
     * inconsistent. Profiles over ~64 KB span several segments, each tagged
     * with its sequence number and the total count; they're joined in order.
     */
    fun extract(jpeg: ByteArray): ByteArray? {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
        val chunks = sortedMapOf<Int, ByteArray>()
        var expected = -1
        var i = 2
        while (i + 4 <= jpeg.size && jpeg[i] == 0xFF.toByte()) {
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break // image data starts; no more metadata
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (length < 2 || i + 2 + length > jpeg.size) return null
            val body = i + 4
            val headerLength = SIGNATURE.size + 2
            if (marker == 0xE2 && length - 2 >= headerLength && matches(jpeg, body)) {
                val sequence = jpeg[body + SIGNATURE.size].toInt() and 0xFF
                val count = jpeg[body + SIGNATURE.size + 1].toInt() and 0xFF
                if (expected != -1 && expected != count) return null
                expected = count
                chunks[sequence] = jpeg.copyOfRange(body + headerLength, i + 2 + length)
            }
            i += 2 + length
        }
        if (chunks.isEmpty() || chunks.size != expected || chunks.keys != (1..expected).toSet()) return null
        return ByteArrayOutputStream().apply { chunks.values.forEach { write(it) } }.toByteArray()
    }

    private fun matches(bytes: ByteArray, at: Int): Boolean =
        SIGNATURE.indices.all { bytes[at + it] == SIGNATURE[it] }
}
