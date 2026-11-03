@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.database.TokenUsageRecord
import com.agentclientprotocol.model.Usage

/** Immutable accounting attribution captured by the existing ACP prompt owner. */
internal data class AcpPromptUsageAttribution(
    val agentId: String,
    val conversationId: Long?,
    val model: String,
) {
    fun record(usage: Usage?, createdAt: Long = System.currentTimeMillis()): TokenUsageRecord? {
        // Native model calls already have their own accounting owner. An ACP
        // summary for the same calls must not count them a second time.
        if (agentId == AcpAgentProfileStore.XIAOWAN_AGENT_ID || usage == null) return null
        val id = conversationId?.takeIf { it > 0 } ?: return null
        val input = usage.inputTokens.toStoredTokenCount()
        val output = usage.outputTokens.toStoredTokenCount()
        val cacheRead = (usage.cachedReadTokens ?: 0L).toStoredTokenCount()
        val cacheWrite = (usage.cachedWriteTokens ?: 0L).toStoredTokenCount()
        // ACP inputTokens is uncached input. Store the same inclusive prompt
        // count as the native HTTP path, with cache fields as a breakdown.
        val prompt = (input.toLong() + cacheRead + cacheWrite).toStoredTokenCount()
        if (prompt == 0 && output == 0) return null
        return TokenUsageRecord(
            conversationId = id,
            model = model,
            promptTokens = prompt,
            completionTokens = output,
            cachedTokens = cacheRead,
            cacheCreationTokens = cacheWrite,
            createdAt = createdAt,
        )
    }
}

private fun Long.toStoredTokenCount(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
