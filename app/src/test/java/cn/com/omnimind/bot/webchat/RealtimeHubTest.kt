package cn.com.omnimind.bot.webchat

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.take
import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeHubTest {

    @Test
    fun `slow WebChat subscriber receives every event beyond the former fixed buffer`() = runBlocking {
        val eventName = "realtime-hub-test-${UUID.randomUUID()}"
        val expectedCount = 320
        val received = mutableListOf<Int>()
        val subscribed = CompletableDeferred<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            RealtimeHub.stream()
                .onSubscription { subscribed.complete(Unit) }
                .filter { it.event == eventName }
                .take(expectedCount)
                .collect { event ->
                    received += (event.data.getValue("sequence") as Number).toInt()
                    // Simulate a client rendering streamed tool activity while
                    // more events keep arriving.
                    delay(1)
                }
        }

        subscribed.await()
        repeat(expectedCount) { sequence ->
            RealtimeHub.publish(eventName, mapOf("sequence" to sequence))
        }

        withTimeout(10_000L) { collector.join() }
        assertEquals((0 until expectedCount).toList(), received)
    }
}
