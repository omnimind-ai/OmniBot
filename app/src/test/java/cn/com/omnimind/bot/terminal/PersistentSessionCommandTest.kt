package cn.com.omnimind.bot.terminal

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PersistentSessionCommandTest {
    @Test fun `missing or exited terminal ends the wait without a fabricated completion`() = runBlocking {
        for (snapshot in listOf(null, EmbeddedTerminalRuntime.PersistentSessionSnapshot("partial",false,7))) {
            val output = mutableListOf<String>()
            val failure = runCatching {
                withTimeout(500) {
                    EmbeddedTerminalRuntime.awaitSessionCommandTranscript("token",0,{snapshot},output::add)
                }
            }.exceptionOrNull()
            assertTrue("Expected terminal failure, got $failure", failure is IllegalStateException && failure !is CancellationException)
            if (snapshot != null) {
                assertTrue(failure!!.message.orEmpty().contains("exit=7"))
                assertEquals(listOf("partial"),output)
            }
        }
    }
    @Test fun `formal command marker wins over subsequent shell exit`() = runBlocking {
        repeat(40) {
            val transcript="done\n__OMNIBOT_SESSION_DONE__:token:0\n"
            assertEquals(transcript, EmbeddedTerminalRuntime.awaitSessionCommandTranscript("token",0,
                { EmbeddedTerminalRuntime.PersistentSessionSnapshot(transcript,false,0) }))
        }
    }
    @Test fun `cancelling a live wait permits the next independent command`() = runBlocking {
        val seen=CompletableDeferred<Unit>()
        val task=launch {
            EmbeddedTerminalRuntime.awaitSessionCommandTranscript("old",0,{
                seen.complete(Unit)
                EmbeddedTerminalRuntime.PersistentSessionSnapshot("",true)
            })
        }
        seen.await();task.cancelAndJoin()
        assertTrue(task.isCancelled)
        val next="__OMNIBOT_SESSION_DONE__:next:0\n"
        assertEquals(next,EmbeddedTerminalRuntime.awaitSessionCommandTranscript("next",0,
            { EmbeddedTerminalRuntime.PersistentSessionSnapshot(next,true) }))
    }
}
