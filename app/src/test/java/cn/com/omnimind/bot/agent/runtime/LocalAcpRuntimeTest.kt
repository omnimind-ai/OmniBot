package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import java.util.ArrayDeque
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAcpRuntimeTest {
    @Test
    fun `ACP terminal keeps complete output unless the caller requests a byte limit`() {
        val output = "line\n".repeat(80_000)

        assertEquals(output to false, tailByBytes(output, null))
        assertEquals("def" to true, tailByBytes("abcdef", 3uL))
    }

    @Test
    fun `ACP file reads keep every requested line unless the caller supplies a limit`() {
        val lines = (1..20_001).asSequence().map { "line-$it" }
        val complete = selectAcpTextFileLines(lines, line = 1u, limit = null)

        assertTrue(complete.startsWith("line-1\n"))
        assertTrue(complete.endsWith("line-20001"))
        assertEquals(20_001, complete.lineSequence().count())
        assertEquals(
            "line-3\nline-4",
            selectAcpTextFileLines(
                sequenceOf("line-1", "line-2", "line-3", "line-4", "line-5"),
                line = 3u,
                limit = 2u,
            ),
        )
    }

    @Test
    fun `pending event buffer preserves every update beyond the former cap`() {
        val events = ArrayDeque<Map<String, Any?>>()
        repeat(1024) { index ->
            enqueuePendingAgentEvent(
                events,
                mapOf("method" to "session/update", "sequence" to index),
            )
        }

        val finalUpdate = mapOf("method" to "session/update", "sequence" to 1024)
        enqueuePendingAgentEvent(events, finalUpdate)

        assertEquals(1025, events.size)
        assertEquals(0, events.first()["sequence"])
        assertEquals(finalUpdate, events.last())
    }

    @Test
    fun `managed Harness preparation probes its own live runtime`() {
        assertTrue(
            shouldProbePreparedManagedAgent(
                managedAdapter = true,
                runtimeConnected = true,
            )
        )
        assertFalse(
            shouldProbePreparedManagedAgent(
                managedAdapter = true,
                runtimeConnected = false,
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
    fun `completed ACP requests stay idempotent beyond the former tombstone cap`() {
        val ownership = AcpTurnOwnershipRegistry()

        repeat(300) { index ->
            val turnId = "turn-$index"
            val requestId = "request-$index"
            assertTrue(ownership.reserve("session", turnId, requestId) is AcpTurnReservation.Started)
            assertTrue(ownership.finish("session", turnId, "completed") != null)
        }

        assertTrue(
            ownership.reserve("session", "retry-old", "request-0")
                is AcpTurnReservation.Completed
        )
    }

    @Test
    fun `a cancelled turn ends only itself and the next user prompt can start`() {
        val ownership = AcpTurnOwnershipRegistry()
        assertTrue(
            ownership.reserve("session", "turn-cancelled", "request-cancelled")
                is AcpTurnReservation.Started
        )
        assertTrue(ownership.finish("session", "turn-cancelled", "cancelled") != null)

        assertTrue(
            ownership.reserve("session", "turn-next", "request-next")
                is AcpTurnReservation.Started
        )
        assertTrue(
            ownership.reserve("session", "turn-cancelled-retry", "request-cancelled")
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
    fun `transport disconnect before prompt attachment cancels the late job`() {
        val preparation = Job()
        val execution = AcpPromptExecution(preparation)

        execution.cancelForTransport(CancellationException("ACP runtime disconnected"))
        val latePrompt = Job()
        execution.attachPromptJob(latePrompt)

        assertTrue(preparation.isCancelled)
        assertTrue(latePrompt.isCancelled)
        assertFalse(execution.tryStartPrompt())
    }

    @Test
    fun `transport disconnect without a preparation job still rejects late prompt admission`() {
        val execution = AcpPromptExecution(null)
        execution.cancelForTransport(CancellationException("ACP runtime disconnected"))

        val latePrompt = Job()
        execution.attachPromptJob(latePrompt)

        assertTrue(latePrompt.isCancelled)
        assertFalse(execution.tryStartPrompt())
    }

    @Test
    fun `transport disconnect cancels an admitted prompt but not another execution`() {
        val prompt = Job()
        val execution = AcpPromptExecution(null)
        execution.attachPromptJob(prompt)
        assertTrue(execution.tryStartPrompt())
        val otherPrompt = Job()
        val otherExecution = AcpPromptExecution(null)
        otherExecution.attachPromptJob(otherPrompt)

        execution.cancelForTransport(CancellationException("ACP runtime disconnected"))
        execution.cancelForTransport(CancellationException("duplicate disconnect"))

        assertTrue(prompt.isCancelled)
        assertFalse(otherPrompt.isCancelled)
        assertTrue(otherExecution.tryStartPrompt())
        otherPrompt.cancel()
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
    fun `server request reply follows its captured owner without session metadata`() {
        for (owner in listOf("kimi-code-acp", "codex-acp", REMOTE_CODEX_AGENT_ID)) {
            val expected = if (owner == REMOTE_CODEX_AGENT_ID) AcpServerRequestRoute.Remote
                else AcpServerRequestRoute.Local(owner)
            assertEquals(expected, resolveAcpServerRequestRoute(
                requestedAgentId = null,
                sessionAgentId = null,
                conversationAgentId = null,
                pendingRequestAgentId = owner,
            ))
        }
    }

    @Test
    fun `pending server request validates every supplied identity before routing`() {
        val owner = "codex-acp"
        for (owners in listOf(
            listOf(REMOTE_CODEX_AGENT_ID, owner, owner),
            listOf(owner, REMOTE_CODEX_AGENT_ID, owner),
            listOf(owner, owner, REMOTE_CODEX_AGENT_ID),
        )) {
            val error = runCatching { resolveAcpServerRequestRoute(
                requestedAgentId = owners[0],
                sessionAgentId = owners[1],
                conversationAgentId = owners[2],
                pendingRequestAgentId = owner,
            ) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error!!.message.orEmpty().startsWith("agent_owner_conflict:"))
        }
        assertEquals(AcpServerRequestRoute.Local(owner), resolveAcpServerRequestRoute(
            requestedAgentId = " $owner ", sessionAgentId = "", conversationAgentId = null,
            pendingRequestAgentId = owner,
        ))
    }

    @Test
    fun `reply without pending owner uses explicit identity and never invents a default`() {
        for (owner in listOf("codex-acp", REMOTE_CODEX_AGENT_ID)) {
            val expected = if (owner == REMOTE_CODEX_AGENT_ID) AcpServerRequestRoute.Remote
                else AcpServerRequestRoute.Local(owner)
            for (owners in listOf(listOf(owner, null, null), listOf(null, owner, null), listOf(null, null, owner))) {
                assertEquals(expected, resolveAcpServerRequestRoute(
                    requestedAgentId = owners[0], sessionAgentId = owners[1],
                    conversationAgentId = owners[2], pendingRequestAgentId = null,
                ))
            }
        }
        val absent = runCatching { resolveAcpServerRequestRoute(null, " ", null, null) }.exceptionOrNull()
        assertTrue(absent is IllegalArgumentException)
        assertTrue(absent!!.message.orEmpty().startsWith("agent_identity_required:"))
        for (owners in listOf(
            listOf("codex-acp", REMOTE_CODEX_AGENT_ID, null),
            listOf(null, "codex-acp", REMOTE_CODEX_AGENT_ID),
            listOf("codex-acp", null, REMOTE_CODEX_AGENT_ID),
        )) {
            val conflict = runCatching { resolveAcpServerRequestRoute(
                owners[0], owners[1], owners[2], null,
            ) }.exceptionOrNull()
            assertTrue(conflict is IllegalArgumentException)
            assertTrue(conflict!!.message.orEmpty().startsWith("agent_owner_conflict:"))
        }
    }

    @Test
    fun `server request owner is released after the response lifecycle`() {
        val registry = AcpServerRequestOwnerRegistry()
        registry.register("request-1", "kimi-code-acp", "session-1")

        assertEquals(
            AcpServerRequestOwner("kimi-code-acp", "session-1"),
            registry.ownerFor("request-1"),
        )

        registry.remove("request-1")
        assertEquals(null, registry.ownerFor("request-1"))
    }

    @Test
    fun `same request id on parallel ACP transports stays independently addressable`() {
        val registry = AcpServerRequestOwnerRegistry()
        registry.register("same-id", "xiaowan-acp", "xiaowan-session")
        registry.register("same-id", "kimi-code-acp", "kimi-session")

        assertEquals(null, registry.ownerFor("same-id"))
        assertEquals(
            AcpServerRequestOwner("xiaowan-acp", "xiaowan-session"),
            registry.resolve("same-id", agentId = "xiaowan-acp"),
        )
        assertEquals(
            AcpServerRequestOwner("kimi-code-acp", "kimi-session"),
            registry.resolve("same-id", sessionId = "kimi-session"),
        )

        registry.remove("same-id", agentId = "xiaowan-acp", sessionId = "xiaowan-session")
        assertEquals(
            AcpServerRequestOwner("kimi-code-acp", "kimi-session"),
            registry.ownerFor("same-id"),
        )
    }

    @Test
    fun `ambiguous ACP request id cannot fall back to selected Agent`() {
        val registry = AcpServerRequestOwnerRegistry()
        registry.register("same-id", "xiaowan-acp", null)
        registry.register("same-id", "kimi-code-acp", null)

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
        registry.register("request-1", "kimi-code-acp", "kimi-session")

        var failed = false
        try {
            registry.resolve("request-1", agentId = "xiaowan-acp")
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
        registry.remove("request-1", agentId = "xiaowan-acp")
        assertEquals(AcpServerRequestOwner("kimi-code-acp", "kimi-session"), registry.ownerFor("request-1"))
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
