package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import com.rk.terminal.runtime.TerminalDistribution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

class LocalAcpRuntimeOwnershipTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `runtime identity cannot change through catalog edits or requests for another Agent`() = runBlocking {
        val context = acpProfileStoreTestContext(directory.root)
        val store = AcpAgentProfileStore(context)
        val owner = store.requireProfile(AcpAgentProfileStore.XIAOWAN_AGENT_ID)
        val other = store.requireProfile(AcpAgentProfileStore.CODEX_AGENT_ID)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var preparations = 0
        val runtime = LocalAcpRuntime(
            context, scope, mock(AgentSessionBindingRepository::class.java), store,
            ownerAgentId = owner.id,
            currentDistribution = { TerminalDistribution.alpine },
            prepareLaunchEnvironment = { _, _ ->
                preparations++
                error("A different owner must fail before preparing a connection")
            },
            buildHandoffContext = { _, _ -> null },
            scheduleToolBridge = mock(AgentScheduleToolBridge::class.java),
            onMessage = { error("Rejected ownership must not emit an ACP lifecycle event") },
        )
        try {
            store.save(other.copy(environment = mapOf("CUSTOM" to "value")))
            assertEquals(owner.id, runtime.activeAgentId())
            assertEquals(owner.id, runtime.statusPayload()["activeAgentId"])
            assertEquals(false, runtime.statusPayload()["connected"])
            assertTrue(runCatching { runtime.connect(other) }.exceptionOrNull() is IllegalArgumentException)
            for (method in listOf("agent/test", "agent/prepare", "session/new")) {
                assertTrue(runCatching {
                    runtime.handleMethod(method, mapOf("agentId" to other.id))
                }.exceptionOrNull() is IllegalArgumentException)
            }
            assertTrue(runCatching {
                runtime.handleMethod("agent/test", emptyMap())
            }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, preparations)
            assertFalse(runtime.isConnected)
            assertEquals(null, runtime.connectionDistributionId)
        } finally {
            scope.cancel()
        }
    }
}
