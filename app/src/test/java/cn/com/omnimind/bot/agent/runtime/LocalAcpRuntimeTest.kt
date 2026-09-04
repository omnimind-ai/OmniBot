package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.Job
import java.util.ArrayDeque
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAcpRuntimeTest {
    @Test
    fun `pending event buffer keeps terminal boundaries when saturated`() {
        val events = ArrayDeque<Map<String, Any?>>()
        repeat(1024) { index ->
            enqueuePendingAgentEvent(
                events,
                mapOf("method" to "session/update", "sequence" to index),
            )
        }

        val terminal = mapOf(
            "method" to "turn/failed",
            "sessionId" to "session-buffer",
            "turnId" to "turn-buffer",
        )
        enqueuePendingAgentEvent(events, terminal)

        assertTrue(events.contains(terminal))
        assertEquals(1024, events.size)
    }

    @Test
    fun `pending event buffer does not evict unique terminal boundaries`() {
        val events = ArrayDeque<Map<String, Any?>>()
        repeat(1024) { index ->
            enqueuePendingAgentEvent(
                events,
                mapOf(
                    "method" to "turn/completed",
                    "sessionId" to "session-$index",
                    "turnId" to "turn-$index",
                ),
            )
        }

        val extraTerminal = mapOf(
            "method" to "turn/failed",
            "sessionId" to "session-extra",
            "turnId" to "turn-extra",
        )
        enqueuePendingAgentEvent(events, extraTerminal)

        assertTrue(events.size > 1024)
        assertTrue(events.first()["sessionId"] == "session-0")
        assertTrue(events.contains(extraTerminal))
    }

    @Test
    fun `managed Harness preparation preserves another live ACP runtime`() {
        assertTrue(
            shouldPrepareManagedAgentWithoutSwitchingRuntime(
                managedAdapter = true,
                runtimeConnected = true,
                activeAgentId = "xiaowan-acp",
                requestedAgentId = "opencode-acp",
            )
        )
        assertFalse(
            shouldPrepareManagedAgentWithoutSwitchingRuntime(
                managedAdapter = true,
                runtimeConnected = true,
                activeAgentId = "xiaowan-acp",
                requestedAgentId = "xiaowan-acp",
            )
        )
    }

    @Test
    fun `turn ownership admits independent sessions without a global serial gate`() {
        val ownership = AcpTurnOwnershipRegistry()
        assertTrue(
            ownership.reserve("session-a", "turn-a", null)
                is AcpTurnReservation.Started
        )
        assertTrue(
            ownership.reserve("session-b", "turn-b", null)
                is AcpTurnReservation.Started
        )
    }

    @Test
    fun `shared turn store isolates equal session ids by transport scope`() {
        val store = AcpTurnOwnershipStore()
        val local = AcpTurnOwnershipRegistry(store, "local:xiaowan")
        val remote = AcpTurnOwnershipRegistry(store, "remote:codex")

        assertTrue(local.reserve("same-session", "local-turn", "local-request") is AcpTurnReservation.Started)
        assertTrue(remote.reserve("same-session", "remote-turn", "remote-request") is AcpTurnReservation.Started)

        assertEquals("local-turn", local.activeTurnId("same-session"))
        assertEquals("remote-turn", remote.activeTurnId("same-session"))

        assertTrue(local.finish("same-session", "local-turn", "completed") != null)
        assertEquals(null, local.activeTurnId("same-session"))
        assertEquals("remote-turn", remote.activeTurnId("same-session"))
        assertEquals(
            "remote-turn",
            remote.requestRecord("same-session", "remote-request")?.turnId,
        )
    }

    @Test
    fun `android turn resource identity includes the ACP session`() {
        assertFalse(
            agentTurnRuntimeId("session-a", "same-turn") ==
                agentTurnRuntimeId("session-b", "same-turn")
        )
    }

    @Test
    fun `same session has one turn and request retry is idempotent`() {
        val ownership = AcpTurnOwnershipRegistry()
        val started = ownership.reserve("session", "turn-1", "request-1")
        assertTrue(started is AcpTurnReservation.Started)
        assertTrue(
            ownership.reserve("session", "turn-1-retry", "request-1")
                is AcpTurnReservation.InFlight
        )
        assertTrue(
            ownership.reserve("session", "turn-2", "request-2")
                is AcpTurnReservation.Busy
        )
        ownership.finish("session", "turn-1", "error", "failed")
        assertTrue(
            ownership.reserve("session", "turn-1-retry", "request-1")
                is AcpTurnReservation.Completed
        )
    }

    @Test
    fun `legacy start event can attach request identity to the existing turn`() {
        val ownership = AcpTurnOwnershipRegistry()
        ownership.reserve("session", "turn-1", null)

        assertTrue(ownership.attachRequestId("session", "turn-1", "request-1"))
        ownership.finish("session", "turn-1", "completed")

        assertTrue(
            ownership.reserve("session", "turn-2", "request-1")
                is AcpTurnReservation.Completed
        )
    }

    @Test
    fun `official prompt response is required for a successful end turn`() {
        assertEquals(
            "end_turn",
            resolveTurnTerminalStatus(
                stopReason = "end_turn",
                promptResponseReceived = true,
                cancelled = false,
                error = null,
            )
        )
        assertEquals(
            "error",
            resolveTurnTerminalStatus(
                stopReason = null,
                promptResponseReceived = false,
                cancelled = false,
                error = null,
            )
        )
    }

    @Test
    fun `official cancellation reason wins over collector cancellation`() {
        assertEquals(
            "cancelled",
            resolveTurnTerminalStatus(
                stopReason = "cancelled",
                promptResponseReceived = true,
                cancelled = false,
                error = null,
            )
        )
    }

    @Test
    fun `cancel before prompt admission cannot leave a prompt behind`() {
        val preparation = Job()
        val prompt = Job()
        val execution = AcpPromptExecution(preparation)
        execution.attachPromptJob(prompt)

        assertFalse(execution.requestCancellation())
        assertFalse(execution.tryStartPrompt())
        assertTrue(preparation.isCancelled)
        assertTrue(prompt.isCancelled)
    }

    @Test
    fun `cancel after prompt admission is delegated without cancelling prompt collector`() {
        val prompt = Job()
        val execution = AcpPromptExecution(prompt)
        execution.attachPromptJob(prompt)
        assertTrue(execution.tryStartPrompt())

        assertTrue(execution.requestCancellation())
        assertFalse(prompt.isCancelled)
        prompt.cancel()
    }

    @Test
    fun `transport routing follows identity instead of global runtime state`() {
        assertTrue(
            shouldRouteLocalAcpRequest(
                remoteEnabled = true,
                method = "session/prompt",
                requestedAgentId = "xiaowan-acp",
                sessionAgentId = null,
                conversationAgentId = null,
                localCodexSessionOwned = false,
            )
        )
        assertFalse(
            shouldRouteLocalAcpRequest(
                remoteEnabled = true,
                method = "session/prompt",
                requestedAgentId = null,
                sessionAgentId = null,
                conversationAgentId = null,
                localCodexSessionOwned = false,
            )
        )
        assertTrue(
            shouldRouteLocalAcpRequest(
                remoteEnabled = true,
                method = "session/prompt",
                requestedAgentId = "codex-acp",
                sessionAgentId = null,
                conversationAgentId = null,
                localCodexSessionOwned = true,
            )
        )
        assertFalse(
            shouldRouteLocalAcpRequest(
                remoteEnabled = true,
                method = "session/prompt",
                requestedAgentId = "codex-acp",
                sessionAgentId = null,
                conversationAgentId = null,
                localCodexSessionOwned = false,
            )
        )
        assertTrue(
            shouldRouteLocalAcpRequest(
                remoteEnabled = true,
                method = "respondToServerRequest",
                requestedAgentId = null,
                sessionAgentId = "xiaowan-acp",
                conversationAgentId = null,
                localCodexSessionOwned = false,
            )
        )
        assertTrue(
            shouldRouteLocalAcpRequest(
                remoteEnabled = true,
                method = "initialize",
                requestedAgentId = "xiaowan-acp",
                sessionAgentId = null,
                conversationAgentId = null,
                localCodexSessionOwned = false,
            )
        )
        assertTrue(
            shouldRouteLocalAcpRequest(
                remoteEnabled = true,
                method = "\$/cancel_request",
                requestedAgentId = "deepseek-harness-acp",
                sessionAgentId = null,
                conversationAgentId = null,
                localCodexSessionOwned = false,
            )
        )
    }

    @Test
    fun `server request reply follows request owner when session metadata is absent`() {
        assertEquals(
            AcpServerRequestRoute.Local("deepseek-harness-acp"),
            resolveAcpServerRequestRoute(
                remoteEnabled = true,
                requestedAgentId = null,
                sessionAgentId = null,
                conversationAgentId = null,
                pendingRequestAgentId = "deepseek-harness-acp",
                selectedRuntime = AcpServerRequestRuntime.REMOTE,
            ),
        )
        assertEquals(
            AcpServerRequestRoute.Local("deepseek-harness-acp"),
            resolveAcpServerRequestRoute(
                remoteEnabled = true,
                requestedAgentId = null,
                sessionAgentId = null,
                conversationAgentId = null,
                pendingRequestAgentId = "deepseek-harness-acp",
                selectedRuntime = AcpServerRequestRuntime.LOCAL,
            ),
        )
    }

    @Test
    fun `server request owner is released after the response lifecycle`() {
        val registry = AcpServerRequestOwnerRegistry()
        registry.register("request-1", "deepseek-harness-acp", "session-1")

        assertEquals(
            AcpServerRequestOwner("deepseek-harness-acp", "session-1"),
            registry.ownerFor("request-1"),
        )

        registry.remove("request-1")
        assertEquals(null, registry.ownerFor("request-1"))
    }

    @Test
    fun `same request id on parallel ACP transports stays independently addressable`() {
        val registry = AcpServerRequestOwnerRegistry()
        registry.register("same-id", "xiaowan-acp", "xiaowan-session")
        registry.register("same-id", "deepseek-harness-acp", "dsh-session")

        assertEquals(null, registry.ownerFor("same-id"))
        assertEquals(
            AcpServerRequestOwner("xiaowan-acp", "xiaowan-session"),
            registry.resolve("same-id", agentId = "xiaowan-acp"),
        )
        assertEquals(
            AcpServerRequestOwner("deepseek-harness-acp", "dsh-session"),
            registry.resolve("same-id", sessionId = "dsh-session"),
        )

        registry.remove("same-id", agentId = "xiaowan-acp", sessionId = "xiaowan-session")
        assertEquals(
            AcpServerRequestOwner("deepseek-harness-acp", "dsh-session"),
            registry.ownerFor("same-id"),
        )
    }

    @Test
    fun `ambiguous ACP request id cannot fall back to selected Agent`() {
        val registry = AcpServerRequestOwnerRegistry()
        registry.register("same-id", "xiaowan-acp", null)
        registry.register("same-id", "deepseek-harness-acp", null)

        var failed = false
        try {
            registry.resolve("same-id")
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun `request identity mismatch cannot fall back to the only pending owner`() {
        val registry = AcpServerRequestOwnerRegistry()
        registry.register("request-1", "deepseek-harness-acp", "dsh-session")

        var failed = false
        try {
            registry.resolve("request-1", agentId = "xiaowan-acp")
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun `only current owner can finish and terminal transition is single shot`() {
        val ownership = AcpTurnOwnershipRegistry()
        ownership.reserve("session", "turn-1", "request-1")
        assertTrue(ownership.finish("session", "other", "completed") == null)
        assertTrue(ownership.finish("session", "turn-1", "timeout") != null)
        assertTrue(ownership.finish("session", "turn-1", "completed") == null)
        val retry = ownership.reserve("session", "turn-2", "request-1")
        assertTrue(retry is AcpTurnReservation.Completed)
    }

    @Test
    fun `transport termination finishes every parallel session atomically`() {
        val ownership = AcpTurnOwnershipRegistry()
        ownership.reserve("session-a", "turn-a", "request-a")
        ownership.reserve("session-b", "turn-b", "request-b")

        val finished = ownership.finishAll("error", "bridge disconnected")

        assertEquals(
            setOf("session-a" to "turn-a", "session-b" to "turn-b"),
            finished.map { it.sessionId to it.turnId }.toSet(),
        )
        assertTrue(finished.all { it.terminal?.status == "error" })
        assertTrue(ownership.activeRecords().isEmpty())
        assertTrue(
            ownership.reserve("session-a", "new-turn", "request-a")
                is AcpTurnReservation.Completed
        )
    }

    @Test
    fun `stale ACP updates are rejected after a terminal transition`() {
        assertTrue(shouldProjectAcpTurnUpdate("turn-1", "turn-1", replay = false))
        assertFalse(shouldProjectAcpTurnUpdate(null, "turn-1", replay = false))
        assertFalse(shouldProjectAcpTurnUpdate("turn-2", "turn-1", replay = false))
        assertTrue(shouldProjectAcpTurnUpdate(null, "replay", replay = true))
    }

    @Test
    fun `legacy conversation without binding creates session on load`() {
        assertTrue(
            shouldCreateSessionForConversationLoad(
                explicitSessionId = null,
                explicitThreadId = null,
                conversationId = 42L,
                hasConversationBinding = false
            )
        )
    }

    @Test
    fun `bound conversation still resolves its existing session`() {
        assertFalse(
            shouldCreateSessionForConversationLoad(
                explicitSessionId = null,
                explicitThreadId = null,
                conversationId = 42L,
                hasConversationBinding = true
            )
        )
    }

    @Test
    fun `explicit session is never replaced by a new session`() {
        assertFalse(
            shouldCreateSessionForConversationLoad(
                explicitSessionId = "session-1",
                explicitThreadId = null,
                conversationId = 42L,
                hasConversationBinding = false
            )
        )
    }
}
