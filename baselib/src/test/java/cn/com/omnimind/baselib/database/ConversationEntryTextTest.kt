package cn.com.omnimind.baselib.database

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConversationEntryTextTest {
    @Test fun `chunk boundaries preserve UTF8 and use one based byte offsets`() = runBlocking {
        val original = ("x".repeat(32767) + "中文🌊").repeat(3)
        val bytes = original.toByteArray()
        val offsets = mutableListOf<Long>()
        val output = ByteArrayOutputStream()
        copyConversationEntryText(bytes.size.toLong(), "", output) { offset ->
            offsets += offset
            bytes.copyOfRange((offset - 1).toInt(), minOf(bytes.size, (offset - 1).toInt() + 32768))
        }
        assertEquals(listOf(1L, 32769L, 65537L, 98305L), offsets)
        assertEquals(original, output.toString("UTF-8"))
    }

    @Test fun `large records reach the sink incrementally without accumulating chunks`() = runBlocking {
        val chunk = ByteArray(32768)
        var written = 0L
        val size = 512L * 1024 * 1024
        val sink = object : OutputStream() {
            override fun write(b: Int) = error("must write chunks")
            override fun write(b: ByteArray, off: Int, len: Int) { written += len }
        }
        copyConversationEntryText(size, "", sink) { offset ->
            assertEquals(written + 1, offset)
            chunk
        }
        assertEquals(size, written)
    }

    @Test fun `missing or inconsistent chunks fail without accepting partial history`() = runBlocking {
        for (chunk in listOf(null, byteArrayOf(), ByteArray(32769))) {
            try {
                copyConversationEntryText(32769, "", ByteArrayOutputStream()) { chunk }
                fail("invalid chunk must fail")
            } catch (_: IllegalStateException) { }
        }
        try {
            copyConversationEntryText(32769, "", ByteArrayOutputStream()) { ByteArray(32768) }
            fail("overshoot must fail")
        } catch (_: IllegalStateException) { }
    }

    @Test fun `inline text avoids database queries and validates its byte count`() = runBlocking {
        val output = ByteArrayOutputStream()
        copyConversationEntryText(6, "中文", output) { error("unexpected chunk read") }
        assertEquals("中文", output.toString("UTF-8"))
        try {
            copyConversationEntryText(3, "中文", output) { error("unexpected chunk read") }
            fail("inconsistent inline length must fail")
        } catch (_: IllegalStateException) { }
    }

    @Test fun `cancellation stops reading instead of completing an offload`() = runBlocking {
        var reads = 0
        val job = launch {
            try {
                copyConversationEntryText(100_000, "", ByteArrayOutputStream()) {
                    reads++
                    cancel()
                    ByteArray(32768)
                }
                fail("cancelled copy must fail")
            } catch (_: CancellationException) { }
        }
        job.join()
        assertEquals(1, reads)
    }
}
