package cn.com.omnimind.bot.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechInputControllerTest {
    @Test
    fun `another recognition provider receives frames and only the owner can commit`() = runBlocking {
        val sawFrames = CompletableDeferred<Unit>()
        val recognizer = object : SpeechRecognitionProvider {
            override suspend fun transcribe(frames: Flow<ByteArray>) =
                frames.toList().sumOf { it.size }.toString()
        }
        val input = SpeechInputController(this, { recognizer }) { finish ->
            flow {
                emit(byteArrayOf(1, 2))
                sawFrames.complete(Unit)
                finish.await()
            }
        }
        val transcript = input.start("first")
        sawFrames.await()
        assertFalse(input.finish("stale"))
        input.cancel("stale")
        assertFalse(transcript.isCompleted)
        assertTrue(input.finish("first"))
        assertEquals("2", transcript.await())
    }

    @Test
    fun `cancelling releases the recorder before a subsequent request is accepted`() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val recognizer = object : SpeechRecognitionProvider {
            override suspend fun transcribe(frames: Flow<ByteArray>): String {
                frames.collect {}
                error("Cancelled recording must not commit")
            }
        }
        val input = SpeechInputController(this, { recognizer }) { finish ->
            flow {
                try {
                    ready.complete(Unit)
                    finish.await()
                } finally {
                    released.complete(Unit)
                }
            }
        }
        val started = input.start("first")
        ready.await()
        input.cancel("first")
        started.join()
        assertTrue(released.isCompleted)
        assertTrue(started.isCancelled)
        assertFalse(input.finish("first"))
        val next = input.start("next")
        input.cancel("next")
        next.join()
        assertTrue(next.isCancelled)
    }
}
