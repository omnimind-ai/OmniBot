package cn.com.omnimind.bot.agent.tool.handlers

import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FileSearchTraversalTest {
    @Test fun `limit stops before even enumerating the next file`() = runBlocking {
        val files = sequence {
            yield(File("a.png"))
            error("Traversal continued after the requested result limit")
        }
        assertEquals(listOf("a.png"), collectFileSearchMatches(files, 1) { it.name })
    }

    @Test fun `nonmatches do not consume limit and unicode paths survive`() = runBlocking {
        val files = sequenceOf(File("not-matched"), File("图片 with spaces.png"), File("last.png"))
        assertEquals(listOf("图片 with spaces.png"), collectFileSearchMatches(files, 1) {
            it.name.takeIf { name -> name.endsWith(".png") }
        })
        assertEquals(emptyList<String>(), collectFileSearchMatches(files, null) { null as String? })
    }

    @Test fun `cancelled turn cannot return a successful partial result`() = runBlocking {
        val task = async {
            collectFileSearchMatches(sequenceOf(File("a"), File("b")), 1) {
                currentCoroutineContext().cancel()
                it.name
            }
        }
        try {
            task.await()
            fail("Cancelled search returned success")
        } catch (_: CancellationException) { }
        // The next user operation belongs to an independent job and remains usable.
        assertEquals(listOf("next"), collectFileSearchMatches(sequenceOf(File("next")), 1) { it.name })
    }

    @Test fun `cancellation from file reader propagates unchanged`() = runBlocking {
        val cancellation = CancellationException("user stopped file read")
        try {
            collectFileSearchMatches<String>(sequenceOf(File("a")), null) { throw cancellation }
            fail("Cancellation was swallowed")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
    }

    @Test fun `VM errors are not treated as absent files`() = runBlocking {
        val failure = OutOfMemoryError("synthetic reader failure")
        try {
            collectFileSearchMatches<String>(sequenceOf(File("a")), null) { throw failure }
            fail("Fatal reader error was swallowed")
        } catch (actual: OutOfMemoryError) { assertSame(failure, actual) }
    }

    @Test fun `empty directory and repeated searches complete without stale results`() = runBlocking {
        repeat(40) {
            assertTrue(collectFileSearchMatches(emptySequence(), 10) { file -> file.name }.isEmpty())
            assertEquals(listOf("result"), collectFileSearchMatches(sequenceOf(File("result")), null) { it.name })
        }
    }
}
