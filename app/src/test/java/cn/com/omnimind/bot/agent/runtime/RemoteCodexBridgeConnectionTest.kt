package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteCodexBridgeConnectionTest {
    @Test
    fun `inbound bridge events stay in arrival order when callbacks suspend`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val delivered = mutableListOf<Int>()
        val queue = RemoteCodexInboundEventQueue(scope)
        try {
            queue.offer {
                delivered += 1
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            firstStarted.await()
            queue.offer { delivered += 2 }
            queue.offer { delivered += 3 }

            releaseFirst.complete(Unit)
            queue.close()

            assertEquals(listOf(1, 2, 3), delivered)
        } finally {
            scope.cancel()
        }
    }
}
