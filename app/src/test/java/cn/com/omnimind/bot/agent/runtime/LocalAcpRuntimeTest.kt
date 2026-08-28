package cn.com.omnimind.bot.agent.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAcpRuntimeTest {
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
    fun `turn lifecycle admits independent sessions without a global serial gate`() {
        val lifecycle = AcpTurnLifecycleRegistry()
        assertTrue(
            lifecycle.reserve("session-a", "turn-a", null)
                is AcpTurnReservation.Started
        )
        assertTrue(
            lifecycle.reserve("session-b", "turn-b", null)
                is AcpTurnReservation.Started
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
        val lifecycle = AcpTurnLifecycleRegistry()
        val started = lifecycle.reserve("session", "turn-1", "request-1")
        assertTrue(started is AcpTurnReservation.Started)
        assertTrue(
            lifecycle.reserve("session", "turn-1-retry", "request-1")
                is AcpTurnReservation.InFlight
        )
        assertTrue(
            lifecycle.reserve("session", "turn-2", "request-2")
                is AcpTurnReservation.Busy
        )
        lifecycle.finish("session", "turn-1", "error", "failed")
        assertTrue(
            lifecycle.reserve("session", "turn-1-retry", "request-1")
                is AcpTurnReservation.Completed
        )
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
        val lifecycle = AcpTurnLifecycleRegistry()
        lifecycle.reserve("session", "turn-1", "request-1")
        assertTrue(lifecycle.finish("session", "other", "completed") == null)
        assertTrue(lifecycle.finish("session", "turn-1", "timeout") != null)
        assertTrue(lifecycle.finish("session", "turn-1", "completed") == null)
        val retry = lifecycle.reserve("session", "turn-2", "request-1")
        assertTrue(retry is AcpTurnReservation.Completed)
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
