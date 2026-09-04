package cn.com.omnimind.bot.agent

import cn.com.omnimind.bot.agent.runtime.AcpAgentProfileStore
import cn.com.omnimind.bot.agent.runtime.AcpAgentHealth
import cn.com.omnimind.bot.agent.runtime.DEEPSEEK_HARNESS_PREPARATION_REVISION
import cn.com.omnimind.bot.agent.runtime.ManagedAcpPreparationInProgressException
import cn.com.omnimind.bot.agent.runtime.ManagedAcpPreparationGate
import cn.com.omnimind.bot.agent.runtime.managedAgentPreparationHealth
import cn.com.omnimind.bot.agent.runtime.shouldPrepareManagedAcpAdapter
import cn.com.omnimind.bot.agent.runtime.shouldProbeManagedAcpLaunchCommand
import cn.com.omnimind.bot.agent.runtime.shouldReuseManagedAcpPreparation
import cn.com.omnimind.bot.agent.runtime.resolveAcpLaunchModelForDispatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedAcpAdapterPreparationTest {
    @Test
    fun `a healthy installed DSH adapter is reused during agent switching`() {
        assertFalse(
            shouldPrepareManagedAcpAdapter(
                agentId = AcpAgentProfileStore.DEEPSEEK_HARNESS_AGENT_ID,
                commandAvailable = true,
                allPackagesReady = true,
                adapterHealthy = true,
            )
        )
    }

    @Test
    fun `missing or unhealthy adapters are still prepared`() {
        assertTrue(
            shouldPrepareManagedAcpAdapter(
                agentId = AcpAgentProfileStore.DEEPSEEK_HARNESS_AGENT_ID,
                commandAvailable = false,
                allPackagesReady = false,
                adapterHealthy = false,
            )
        )
    }

    @Test
    fun `stale installer revision is prepared even when the old tree is healthy`() {
        assertTrue(
            shouldPrepareManagedAcpAdapter(
                agentId = AcpAgentProfileStore.DEEPSEEK_HARNESS_AGENT_ID,
                commandAvailable = true,
                allPackagesReady = true,
                adapterHealthy = true,
                preparationRevision = "deepseek-dsh-pnpm-copy-v8",
                requiredRevision = DEEPSEEK_HARNESS_PREPARATION_REVISION,
            )
        )
        assertFalse(
            shouldPrepareManagedAcpAdapter(
                agentId = AcpAgentProfileStore.DEEPSEEK_HARNESS_AGENT_ID,
                commandAvailable = true,
                allPackagesReady = true,
                adapterHealthy = true,
                preparationRevision = DEEPSEEK_HARNESS_PREPARATION_REVISION,
                requiredRevision = DEEPSEEK_HARNESS_PREPARATION_REVISION,
            )
        )
    }

    @Test
    fun `Dispatch catalog supplies the default launch model without a binding`() {
        assertEquals(
            "dispatch-model",
            resolveAcpLaunchModelForDispatch(
                providerModelIds = listOf("dispatch-model", "other-model"),
                dispatchModel = null,
            ),
        )
    }

    @Test
    fun `online installed preparation is reused until an explicit check resets it`() {
        assertTrue(
            shouldReuseManagedAcpPreparation(
                healthStatus = "online",
                installed = true,
            )
        )
        assertFalse(
            shouldReuseManagedAcpPreparation(
                healthStatus = "unchecked",
                installed = true,
            )
        )
        assertFalse(
            shouldReuseManagedAcpPreparation(
                healthStatus = "online",
                installed = false,
            )
        )
    }

    @Test
    fun `completed preparation is reusable without another foreground probe`() {
        val health = managedAgentPreparationHealth(checkedAt = 123L)

        assertEquals(AcpAgentHealth.STATUS_ONLINE, health.status)
        assertTrue(health.installed == true)
        assertTrue(shouldReuseManagedAcpPreparation(health.status, health.installed))
        assertEquals(123L, health.checkedAt)
    }

    @Test
    fun `stale preparation revision is not reused after installer fix`() {
        assertFalse(
            shouldReuseManagedAcpPreparation(
                healthStatus = "online",
                installed = true,
                preparationRevision = "deepseek-dsh-pnpm-copy-v2",
                requiredRevision = DEEPSEEK_HARNESS_PREPARATION_REVISION,
            )
        )
        assertTrue(
            shouldReuseManagedAcpPreparation(
                healthStatus = "online",
                installed = true,
                preparationRevision = DEEPSEEK_HARNESS_PREPARATION_REVISION,
                requiredRevision = DEEPSEEK_HARNESS_PREPARATION_REVISION,
            )
        )
    }

    @Test
    fun `healthy managed adapter skips the foreground launch command probe`() {
        assertFalse(
            shouldProbeManagedAcpLaunchCommand(
                managedAdapter = true,
                healthStatus = AcpAgentHealth.STATUS_ONLINE,
                installed = true,
            )
        )
    }

    @Test
    fun `custom or stale adapter health still verifies the launch command`() {
        assertTrue(
            shouldProbeManagedAcpLaunchCommand(
                managedAdapter = false,
                healthStatus = AcpAgentHealth.STATUS_ONLINE,
                installed = true,
            )
        )
        assertTrue(
            shouldProbeManagedAcpLaunchCommand(
                managedAdapter = true,
                healthStatus = AcpAgentHealth.STATUS_ONLINE,
                installed = true,
                preparationRevision = "old",
                requiredRevision = DEEPSEEK_HARNESS_PREPARATION_REVISION,
            )
        )
        assertTrue(
            shouldProbeManagedAcpLaunchCommand(
                managedAdapter = true,
                healthStatus = AcpAgentHealth.STATUS_UNCHECKED,
                installed = true,
            )
        )
    }

    @Test
    fun `foreground switch fails fast while another Harness is installing`() = runBlocking {
        val gate = ManagedAcpPreparationGate()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val installation = async {
            gate.run("deepseek-harness-acp") {
                started.complete(Unit)
                release.await()
            }
        }

        started.await()
        try {
            gate.run("codex-acp") { Unit }
            throw AssertionError("a foreground switch must not wait for installation")
        } catch (error: ManagedAcpPreparationInProgressException) {
            assertEquals("deepseek-harness-acp", error.preparingAgentId)
        } finally {
            release.complete(Unit)
            installation.await()
        }

        assertEquals("ready", gate.run("codex-acp") { "ready" })
    }
}
