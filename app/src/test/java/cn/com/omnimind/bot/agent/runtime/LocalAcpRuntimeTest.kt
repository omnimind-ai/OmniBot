package cn.com.omnimind.bot.agent.runtime

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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
    fun `turn reservation does not serialize independent prompt execution`() = runBlocking {
        val coordinator = AcpTurnStartCoordinator()
        val executingCount = AtomicInteger(0)
        val bothExecuting = CompletableDeferred<Unit>()
        val releaseExecutions = CompletableDeferred<Unit>()

        val turns = List(2) {
            async {
                coordinator.run {
                    suspend {
                        if (executingCount.incrementAndGet() == 2) {
                            bothExecuting.complete(Unit)
                        }
                        releaseExecutions.await()
                    }
                }
            }
        }

        withTimeout(1_000) { bothExecuting.await() }
        releaseExecutions.complete(Unit)
        turns.awaitAll()
        Unit
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
