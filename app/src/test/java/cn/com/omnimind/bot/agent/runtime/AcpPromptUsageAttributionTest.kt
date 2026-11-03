@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package cn.com.omnimind.bot.agent.runtime

import com.agentclientprotocol.model.Usage
import org.junit.Assert.*
import org.junit.Test

class AcpPromptUsageAttributionTest {
    private val attribution = AcpPromptUsageAttribution("external-acp", 42, "model-at-admission")

    @Test fun `ACP cache splits become inclusive prompt usage without double counting`() {
        val record = attribution.record(Usage(
            inputTokens = 40, outputTokens = 7, totalTokens = 82,
            cachedReadTokens = 30, cachedWriteTokens = 5,
        ), createdAt = 123)!!
        assertEquals(42L, record.conversationId)
        assertEquals("model-at-admission", record.model)
        assertEquals(75, record.promptTokens)
        assertEquals(7, record.completionTokens)
        assertEquals(30, record.cachedTokens)
        assertEquals(5, record.cacheCreationTokens)
        assertEquals(123L, record.createdAt)
    }

    @Test fun `native accounting missing usage and missing conversation do not create records`() {
        val usage = Usage(inputTokens = 10, outputTokens = 3, totalTokens = 13)
        assertNull(attribution.copy(agentId = AcpAgentProfileStore.XIAOWAN_AGENT_ID).record(usage))
        assertNull(attribution.record(null))
        assertNull(attribution.copy(conversationId = null).record(usage))
        assertNull(attribution.copy(conversationId = 0).record(usage))
        assertNull(attribution.record(Usage(inputTokens = 0, outputTokens = 0, totalTokens = 0)))
    }

    @Test fun `untrusted usage counts are clamped before adding cache components`() {
        val record = attribution.record(Usage(
            inputTokens = Long.MAX_VALUE, outputTokens = -5, totalTokens = Long.MAX_VALUE,
            cachedReadTokens = Long.MAX_VALUE, cachedWriteTokens = Long.MAX_VALUE,
        ))!!
        assertEquals(Int.MAX_VALUE, record.promptTokens)
        assertEquals(0, record.completionTokens)
        assertEquals(Int.MAX_VALUE, record.cachedTokens)
        assertEquals(Int.MAX_VALUE, record.cacheCreationTokens)
    }
}
