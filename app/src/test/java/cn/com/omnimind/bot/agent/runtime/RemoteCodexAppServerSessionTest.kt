package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteCodexAppServerSessionTest {
    @Test
    fun `v2 config selection adds official discriminator without changing v1 or custom types`() {
        val params = mapOf("sessionId" to "session", "configId" to "reasoning_effort", "value" to "high")
        assertEquals(params + ("type" to "id"), remoteAcpRequestParams("session/set_config_option", params, 2))
        assertEquals(params, remoteAcpRequestParams("session/set_config_option", params, 1))
        assertEquals(params, remoteAcpRequestParams("session/new", params, 2))
        val boolean = params + ("value" to true)
        assertEquals(boolean + ("type" to "boolean"), remoteAcpRequestParams("session/set_config_option", boolean, 2))
        val custom = params + ("type" to "custom")
        assertEquals(custom, remoteAcpRequestParams("session/set_config_option", custom, 2))
    }

    @Test
    fun `v2 notification preserves backend identity only at the versioned boundary`() {
        val event = mapOf<String, Any?>("method" to "session/update", "params" to mapOf(
            "sessionId" to "shared-session", "update" to mapOf(
                "sessionUpdate" to "state_update", "state" to "running",
                "_meta" to mapOf("codex" to mapOf("turnId" to "backend-turn")),
            ),
        ))
        assertEquals(event, normalizeRemoteAcpNotification(event, 1))
        val normalized = normalizeRemoteAcpNotification(event, 2)
        assertEquals("backend-turn", extractTurnId(normalized))
        assertTrue(isAcpV2State(normalized, "running"))
        assertFalse(isAcpV2State(event, "running"))
        assertFalse(isAcpV2State(normalized, "idle"))
    }

    @Test
    fun `explicit remote harness keeps prompt on remote even with local conversation binding`() {
        assertFalse(shouldRouteLocalAcpRequest(
            remoteEnabled = true, method = "session/prompt",
            requestedAgentId = "codex-remote", sessionAgentId = null,
            conversationAgentId = "xiaowan", localCodexSessionOwned = false,
        ))
        assertTrue(shouldRouteLocalAcpRequest(
            remoteEnabled = true, method = "session/prompt",
            requestedAgentId = "xiaowan", sessionAgentId = null,
            conversationAgentId = "xiaowan", localCodexSessionOwned = false,
        ))
    }

    @Test
    fun `ACP initialization and notifications carry required json rpc version`() = runBlocking {
        val connection = RecordingConnection()
        val session = RemoteCodexAppServerSession(this, {}, { connection })
        session.start("strict-acp-phone-regression")
        assertTrue(connection.writes.size >= 2)
        connection.writes.forEach { line ->
            assertEquals("2.0", com.google.gson.JsonParser.parseString(line)
                .asJsonObject.get("jsonrpc")?.asString)
        }
        session.disconnect()
    }

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

    @Test
    fun `reconnect is not ready until ACP initialize completes`() = runBlocking {
        val initializeSeen = CompletableDeferred<Unit>()
        val releaseInitialize = CompletableDeferred<Unit>()
        val connection = object : RemoteCodexAppServerConnection {
            override var isRunning = false
            lateinit var stdout: suspend (String) -> Unit
            override suspend fun start(onStdoutLine: suspend (String) -> Unit,
                onStderrLine: suspend (String) -> Unit, onExit: suspend (Int?) -> Unit) {
                stdout = onStdoutLine
                isRunning = true
            }
            override suspend fun writeLine(line: String) {
                if (line.contains("\"method\":\"initialize\"")) {
                    initializeSeen.complete(Unit)
                    releaseInitialize.await()
                    stdout("{\"id\":1,\"result\":{}}")
                }
            }
            override suspend fun close() { isRunning = false }
        }
        val session = RemoteCodexAppServerSession(this, {}, { connection })
        val starting = async { session.start("test") }
        try {
            withTimeout(1000) { initializeSeen.await() }
            assertFalse("An open socket must not bypass ACP initialization", session.isRunning)
        } finally {
            releaseInitialize.complete(Unit)
            starting.await()
        }
        assertTrue(session.isRunning)
        session.disconnect()
        assertFalse(session.isRunning)
    }

    @Test
    fun `failed transport start is closed before another connection attempt`() = runBlocking {
        var closed = false
        val failed = object : RemoteCodexAppServerConnection {
            override val isRunning get() = !closed
            override suspend fun start(onStdoutLine: suspend (String) -> Unit,
                onStderrLine: suspend (String) -> Unit, onExit: suspend (Int?) -> Unit) {
                throw IllegalStateException("network lost during bridge hello")
            }
            override suspend fun writeLine(line: String) = Unit
            override suspend fun close() { closed = true }
        }
        val recovered = RecordingConnection()
        var attempts = 0
        val session = RemoteCodexAppServerSession(this, {}, {
            if (attempts++ == 0) failed else recovered
        })
        val failure = runCatching { session.start("test") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue("Failed transport must be released", closed)
        assertFalse(session.isRunning)
        session.start("test")
        assertTrue(session.isRunning)
        assertEquals(2, attempts)
        assertEquals(1, recovered.writes.count { it.contains("\"method\":\"initialize\"") })
        session.disconnect()
    }

    @Test
    fun `disconnect fails old prompt and reconnect does not replay it or admit late events`() = runBlocking {
        val connections = mutableListOf<RecordingConnection>()
        val messages = mutableListOf<Map<String, Any?>>()
        val session = RemoteCodexAppServerSession(this, { messages += it }, {
            RecordingConnection().also { connections += it }
        })
        session.start("test")
        val old = connections.single()
        val pending = async {
            runCatching { session.sendRequest("session/prompt", mapOf("sessionId" to "existing")) }
        }
        old.promptWritten.await()
        old.exit()
        assertTrue(pending.await().isFailure)
        assertFalse(session.isRunning)
        assertTrue(session.initializePayload().isEmpty())
        session.start("test")
        val current = connections.last()
        assertTrue(session.isRunning)
        assertFalse(current.writes.any { it.contains("session/prompt") })
        val count = messages.size
        old.emit("{\"method\":\"session/update\",\"params\":{\"sessionId\":\"existing\"}}")
        old.exit()
        assertEquals(count, messages.size)
        assertTrue(session.isRunning)
        session.disconnect()
    }

    @Test
    fun `v2 reconnect restores all observed sessions without replaying a lost prompt acknowledgement`() = runBlocking {
        val connections = mutableListOf<RecordingConnection>()
        val messages = mutableListOf<Map<String, Any?>>()
        val session = RemoteCodexAppServerSession(this, { messages += it }, {
            RecordingConnection(2).also { connections += it }
        }, restoreSessions = { restored ->
            for (id in listOf("one", "two")) restored.sendRequest("session/resume",
                mapOf("sessionId" to id, "replayFrom" to mapOf("type" to "start")))
        }, reconnectDelays = listOf(1L))
        session.start("test")
        val old = connections.single()
        val prompt = async { runCatching { session.sendRequest("session/prompt",
            mapOf("sessionId" to "one")) } }
        old.promptWritten.await()
        old.exit()
        assertTrue(prompt.await().isFailure)
        withTimeout(2_000) { session.awaitRecovery() }
        assertTrue(session.isRunning)
        assertEquals(2, connections.size)
        val methods = connections.last().writes.map {
            com.google.gson.JsonParser.parseString(it).asJsonObject.get("method").asString }
        assertEquals(listOf("initialize", "initialized", "session/resume", "session/resume"), methods)
        val count = messages.size
        old.emit("{\"method\":\"session/update\",\"params\":{\"sessionId\":\"one\"}}")
        old.exit()
        assertEquals(count, messages.size)
        session.disconnect()
    }

    @Test
    fun `explicit disconnect cancels automatic transport recovery`() = runBlocking {
        val connections = mutableListOf<RecordingConnection>()
        val session = RemoteCodexAppServerSession(this, {}, {
            RecordingConnection(2).also { connections += it }
        }, reconnectDelays = listOf(20L))
        session.start("test")
        connections.single().exit()
        session.disconnect()
        kotlinx.coroutines.delay(40)
        assertEquals(1, connections.size)
        assertFalse(session.isRunning)
    }

    @Test
    fun `queued prompt is never written after its connection is disconnected`() = runBlocking {
        val underlying = RecordingConnection()
        val blocked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val connection = object : RemoteCodexAppServerConnection by underlying {
            override suspend fun writeLine(line: String) {
                if (line.contains("hold-write")) { blocked.complete(Unit); release.await() }
                underlying.writeLine(line)
            }
        }
        val session = RemoteCodexAppServerSession(this, {}, { connection })
        session.start("test")
        val holding = async { runCatching { session.sendNotification("hold-write") } }
        blocked.await()
        val prompt = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            runCatching { session.sendRequest("session/prompt") }
        }
        session.disconnect()
        release.complete(Unit)
        holding.await()
        assertTrue(prompt.await().isFailure)
        assertFalse(underlying.writes.any { it.contains("session/prompt") })
    }

    @Test
    fun `request timeout includes waiting for write lock and unsent request is not cancelled on wire`() = runBlocking {
        val underlying = RecordingConnection()
        val blocked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val connection = object : RemoteCodexAppServerConnection by underlying {
            override suspend fun writeLine(line: String) {
                if (line.contains("hold-write")) { blocked.complete(Unit); release.await() }
                underlying.writeLine(line)
            }
        }
        val session = RemoteCodexAppServerSession(this, {}, { connection })
        session.start("test")
        val holding = async { session.sendNotification("hold-write") }
        blocked.await()
        val request = async { runCatching { session.sendRequest("session/prompt", timeoutMs = 20) } }
        try {
            val error = withTimeout(500) { request.await().exceptionOrNull() }
            assertTrue(error is TimeoutCancellationException)
            assertFalse(underlying.writes.any { it.contains("session/prompt") || it.contains("cancel_request") })
        } finally {
            release.complete(Unit)
            holding.await()
            session.disconnect()
            request.await()
        }
    }

    private class RecordingConnection(private val protocolVersion: Int = 1) : RemoteCodexAppServerConnection {
        override var isRunning: Boolean = true
        val writes = mutableListOf<String>()
        val promptWritten = CompletableDeferred<Unit>()
        private lateinit var exitCallback: suspend (Int?) -> Unit
        suspend fun exit() { isRunning = false; exitCallback(null) }
        suspend fun emit(line: String) { onStdoutLine(line) }
        private lateinit var onStdoutLine: suspend (String) -> Unit

        override suspend fun start(
            onStdoutLine: suspend (String) -> Unit,
            onStderrLine: suspend (String) -> Unit,
            onExit: suspend (Int?) -> Unit,
        ) {
            this.onStdoutLine = onStdoutLine
            this.exitCallback = onExit
        }

        override suspend fun writeLine(line: String) {
            writes += line
            if (line.contains("session/prompt")) promptWritten.complete(Unit)
            if (line.contains("\"method\":\"initialize\"")) {
                val id = com.google.gson.JsonParser.parseString(line).asJsonObject.get("id").asLong
                onStdoutLine("{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"protocolVersion\":$protocolVersion}}")
            } else if (line.contains("\"method\":\"session/resume\"")) {
                val id = com.google.gson.JsonParser.parseString(line).asJsonObject.get("id").asLong
                onStdoutLine("{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{}}")
            }
        }

        override suspend fun close() { isRunning = false }
    }
}
