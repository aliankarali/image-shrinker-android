package app.shrinkerpro.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class JpegIccTest {
    private fun segment(marker: Int, body: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(0xFF); write(marker)
        val length = body.size + 2
        write(length shr 8); write(length and 0xFF)
        write(body)
    }.toByteArray()

    private fun iccChunk(sequence: Int, count: Int, data: ByteArray) =
        segment(0xE2, "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(sequence.toByte(), count.toByte()) + data)

    private fun jpeg(vararg segments: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(0xFF); write(0xD8)
        segments.forEach { write(it) }
        write(segment(0xDA, byteArrayOf(0, 0)))
    }.toByteArray()

    private val app0 = segment(0xE0, "JFIF\u0000".toByteArray() + ByteArray(9))

    @Test fun singleChunk() {
        val profile = ByteArray(300) { it.toByte() }
        assertArrayEquals(profile, JpegIcc.extract(jpeg(app0, iccChunk(1, 1, profile))))
    }

    @Test fun chunksAreJoinedInSequenceOrderWhateverOrderTheyAppear() {
        val a = ByteArray(10) { 1 }; val b = ByteArray(10) { 2 }; val c = ByteArray(5) { 3 }
        assertArrayEquals(a + b + c, JpegIcc.extract(jpeg(iccChunk(2, 3, b), app0, iccChunk(1, 3, a), iccChunk(3, 3, c))))
    }

    @Test fun missingOrInconsistentChunksGiveNothing() {
        assertNull(JpegIcc.extract(jpeg(app0)))
        assertNull(JpegIcc.extract(jpeg(iccChunk(1, 2, ByteArray(4)))))
        assertNull(JpegIcc.extract(jpeg(iccChunk(1, 2, ByteArray(4)), iccChunk(2, 3, ByteArray(4)))))
        assertNull(JpegIcc.extract(byteArrayOf(1, 2, 3)))
    }

    @Test fun otherApp2SegmentsAreIgnored() {
        val profile = ByteArray(20) { 7 }
        val mpf = segment(0xE2, "MPF\u0000".toByteArray() + ByteArray(8))
        assertArrayEquals(profile, JpegIcc.extract(jpeg(mpf, iccChunk(1, 1, profile))))
    }
}
