package cn.com.omnimind.bot.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedStreamCopyTest {
    @Test
    fun copiesContentWithinLimit() {
        val source = ByteArray(32) { it.toByte() }
        val output = ByteArrayOutputStream()

        assertEquals(32L, BoundedStreamCopy.copy(ByteArrayInputStream(source), output, 32L))
        assertArrayEquals(source, output.toByteArray())
    }

    @Test
    fun rejectsContentThatExceedsLimit() {
        assertThrows(ContentSizeLimitExceededException::class.java) {
            BoundedStreamCopy.copy(
                ByteArrayInputStream(ByteArray(33)),
                ByteArrayOutputStream(),
                32L,
            )
        }
    }
}
