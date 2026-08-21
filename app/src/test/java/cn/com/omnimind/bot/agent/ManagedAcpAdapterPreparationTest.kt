package cn.com.omnimind.bot.agent

import cn.com.omnimind.bot.agent.runtime.AcpAgentProfileStore
import cn.com.omnimind.bot.agent.runtime.shouldPrepareManagedAcpAdapter
import cn.com.omnimind.bot.agent.runtime.shouldReuseManagedAcpPreparation
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
}
