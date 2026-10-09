package app.shrinkerpro.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExifTextPatchTest {
    private val fixture = javaClass.getResourceAsStream("/fixtures/rotated.jpg")!!.readBytes()
    private val copyright = "© 2026 Shrinker Pro Test".toByteArray(Charsets.UTF_8) + 0

    /** What ExifInterface does to text: every non-ASCII byte becomes '?'. */
    private fun damage(file: ByteArray, value: ByteArray): ByteArray {
        val at = indexOf(file, value)
        check(at >= 0) { "fixture should contain the copyright text" }
        return file.copyOf().also { copy ->
            for (i in value.indices) if (value[i] < 0) copy[at + i] = '?'.code.toByte()
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    @Test fun restoresTheOriginalBytes() {
        val damaged = damage(fixture, copyright)
        assertFalse(damaged.contentEquals(fixture))
        assertTrue(ExifTextPatch.patch(damaged, mapOf(0x8298 to copyright)))
        assertArrayEquals(fixture, damaged)
    }

    @Test fun acceptsSourcesWithoutATerminator() {
        val damaged = damage(fixture, copyright)
        assertTrue(ExifTextPatch.patch(damaged, mapOf(0x8298 to copyright.copyOf(copyright.size - 1))))
        assertArrayEquals(fixture, damaged)
    }

    @Test fun leavesTextThatIsNotTheDamagedOriginalAlone() {
        val before = fixture.copyOf()
        val other = "(c) 2026 Somebody Else Here".toByteArray() + 0
        assertFalse(ExifTextPatch.patch(before, mapOf(0x8298 to other)))
        assertArrayEquals(fixture, before)
    }

    @Test fun ignoresFilesWithoutExif() {
        val png = javaClass.getResourceAsStream("/fixtures/sample.png")!!.readBytes()
        assertFalse(ExifTextPatch.patch(png.copyOf(), mapOf(0x8298 to copyright)))
    }
}
