package cn.com.omnimind.bot.agent

import kotlinx.serialization.json.JsonObject

object AgentConversationModePolicy {
    /** Canonical durable mode for the shared Agent/ACP conversation surface. */
    const val AGENT_MODE = "agent"
    const val NORMAL_MODE = "normal"
    const val SUBAGENT_MODE = "subagent"
    const val CHAT_ONLY_MODE = "chat_only"

    internal data class HarnessResolution(
        val agentId: String,
        val requestedAgentId: String?,
        val conflictWithAgentId: String? = null,
    ) {
        val hasConflict: Boolean
            get() = conflictWithAgentId != null
    }

    internal fun resolveHarness(
        conversationMode: String?,
        requestedAgentId: String?,
        conversationAgentId: String?,
        sessionAgentId: String?,
        selectedAgentId: String?,
        xiaowanAgentId: String,
    ): HarnessResolution {
        val normalizedRequested = requestedAgentId.normalizedAgentId()
        val normalizedConversation = conversationAgentId.normalizedAgentId()
        val normalizedSession = sessionAgentId.normalizedAgentId()
        val normalizedSelected = selectedAgentId.normalizedAgentId()
        val normalizedXiaowan = xiaowanAgentId.normalizedAgentId()
            ?: xiaowanAgentId

        if (isNormalMode(conversationMode) || isChatOnlyMode(conversationMode)) {
            return HarnessResolution(
                agentId = normalizedXiaowan,
                requestedAgentId = normalizedRequested,
                conflictWithAgentId = normalizedRequested
                    ?.takeUnless { it == normalizedXiaowan },
            )
        }

        val persistedOwner = normalizedConversation ?: normalizedSession
        val resolvedAgent = persistedOwner ?: normalizedRequested
            ?: normalizedSelected ?: normalizedXiaowan
        return HarnessResolution(
            agentId = resolvedAgent,
            requestedAgentId = normalizedRequested,
            conflictWithAgentId = if (
                persistedOwner != null &&
                normalizedRequested != null &&
                persistedOwner != normalizedRequested
            ) {
                persistedOwner
            } else {
                null
            },
        )
    }

    fun isNormalMode(conversationMode: String?): Boolean {
        return conversationMode?.trim()?.equals(NORMAL_MODE, ignoreCase = true) == true
    }

    fun isSubagentMode(conversationMode: String?): Boolean {
        return conversationMode?.trim()?.equals(SUBAGENT_MODE, ignoreCase = true) == true
    }

    fun isChatOnlyMode(conversationMode: String?): Boolean {
        return conversationMode?.trim()?.equals(CHAT_ONLY_MODE, ignoreCase = true) == true
    }

    fun filterToolDefinitionsForConversationMode(
        definitions: List<JsonObject>,
        conversationMode: String?
    ): List<JsonObject> {
        // `chat_only` is an explicit user-selected no-tool surface. Every
        // Agent-capable conversation otherwise receives the capabilities that
        // its active harness exposes; a mode name must not maintain a second,
        // handwritten tool policy.
        if (isChatOnlyMode(conversationMode)) {
            return emptyList()
        }
        return definitions
    }
}

private fun String?.normalizedAgentId(): String? {
    return this?.trim()?.takeIf { it.isNotEmpty() }
}
