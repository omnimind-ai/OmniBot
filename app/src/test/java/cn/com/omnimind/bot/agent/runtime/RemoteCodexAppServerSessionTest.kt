package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteCodexAppServerSessionTest {
    @Test
    fun `transport terminal bypasses a blocked inbound event`() = runBlocking {
        val eventStarted = CompletableDeferred<Unit>()
        val terminalDelivered = CompletableDeferred<Unit>()
        val queue = RemoteCodexInboundEventQueue(this)

        assertTrue(
            queue.offer {
                eventStarted.complete(Unit)
                CompletableDeferred<Unit>().await()
            },
        )
        withTimeout(1_000) { eventStarted.await() }

        assertTrue(queue.offerTerminal { terminalDelivered.complete(Unit) })
        withTimeout(1_000) { terminalDelivered.await() }
        queue.close()
    }

    @Test
    fun `request timeout sends official json rpc cancellation`() = runBlocking {
        val connection = RecordingConnection()
        val session = RemoteCodexAppServerSession(
            scope = this,
            onServerMessage = {},
            connectionFactory = { connection },
        )

        session.start("test")

        try {
            session.sendRequest("slow", timeoutMs = 20)
        } catch (_: TimeoutCancellationException) {
            // Expected: the assertion below verifies the timeout cleanup.
        }

        assertTrue(
            connection.writes.any {
                it.contains("\"method\":\"\$/cancel_request\"") &&
                    it.contains("\"requestId\":2")
            },
        )
    }

    @Test
    fun `prompt response status is the remote terminal boundary`() {
        assertEquals(
            "completed",
            terminalStatusFromAcpParams(mapOf("stopReason" to "end_turn")),
        )
        assertEquals(
            "cancelled",
            terminalStatusFromAcpParams(mapOf("stopReason" to "cancelled")),
        )
    }

    private class RecordingConnection : RemoteCodexAppServerConnection {
        override val isRunning: Boolean = true
        val writes = mutableListOf<String>()
        private lateinit var onStdoutLine: suspend (String) -> Unit

        override suspend fun start(
            onStdoutLine: suspend (String) -> Unit,
            onStderrLine: suspend (String) -> Unit,
            onExit: suspend (Int?) -> Unit,
        ) {
            this.onStdoutLine = onStdoutLine
        }

        override suspend fun writeLine(line: String) {
            writes += line
            if (line.contains("\"method\":\"initialize\"")) {
                onStdoutLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}")
            }
        }

        override suspend fun close() = Unit
    }
}
