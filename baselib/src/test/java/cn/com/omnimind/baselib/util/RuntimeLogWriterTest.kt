package cn.com.omnimind.baselib.util

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLogWriterTest {
    @Test
    fun `slow storage retains a finite queue of newest bounded messages`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val consumed = mutableListOf<RuntimeLogEntry>()
        val writer = RuntimeLogWriter(
            scope = this,
            capacity = 2,
            append = { entry ->
                consumed += entry
                if (entry.id == "0") {
                    started.complete(Unit)
                    release.await()
                }
            },
            onError = { throw AssertionError(it) },
        )
        writer.offer(RuntimeLogEntry(id = "0"))
        started.await()
        val huge = "🌊".repeat(100_000)
        for (index in 1..50) {
            assertTrue(writer.offer(RuntimeLogEntry(id = index.toString(), message = huge)))
        }
        release.complete(Unit)
        writer.closeAndJoin()

        assertEquals(listOf("0", "49", "50"), consumed.map { it.id })
        consumed.drop(1).forEach {
            assertTrue(it.message.toByteArray(Charsets.UTF_8).size <= RuntimeLogLimits.MESSAGE_BYTES)
            assertTrue(it.message.endsWith(RuntimeLogLimits.OMITTED))
        }
        assertFalse(writer.offer(RuntimeLogEntry()))
    }

    @Test
    fun `a storage exception does not stop later log writes`() = runBlocking {
        val errors = mutableListOf<Exception>()
        val saved = mutableListOf<String>()
        val writer = RuntimeLogWriter(
            scope = this,
            append = {
                if (it.id == "bad") throw IOException("disk unavailable")
                saved += it.id
            },
            onError = { errors += it },
        )
        writer.offer(RuntimeLogEntry(id = "bad"))
        writer.offer(RuntimeLogEntry(id = "good"))
        writer.closeAndJoin()
        assertEquals(listOf("good"), saved)
        assertEquals(1, errors.size)
    }

    @Test
    fun `huge throwable messages are bounded without invoking throwable toString`() {
        val error = object : Throwable("中🌊".repeat(100_000)) {
            override fun toString(): String = throw AssertionError("must not copy the complete message")
        }
        val text = boundedRuntimeLogStackTrace(error)
        assertTrue(text.endsWith(RuntimeLogLimits.OMITTED))
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= RuntimeLogLimits.STACK_BYTES)
        assertEquals(text, text.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
    }

    @Test
    fun `cyclic throwable causes cannot loop forever`() {
        val first = Throwable("first").apply { stackTrace = emptyArray() }
        val second = Throwable("second").apply { stackTrace = emptyArray() }
        first.initCause(second)
        second.initCause(first)
        val text = boundedRuntimeLogStackTrace(first)
        assertTrue(text.contains("first"))
        assertTrue(text.contains("second"))
        assertTrue(text.endsWith(RuntimeLogLimits.OMITTED))
        assertTrue(text.length < 200)
    }
}
