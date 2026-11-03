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
        xiaowanAgentId: String,
    ): HarnessResolution {
        val normalizedRequested = requestedAgentId.normalizedAgentId()
        val normalizedConversation = conversationAgentId.normalizedAgentId()
        val normalizedSession = sessionAgentId.normalizedAgentId()
        val normalizedXiaowan = xiaowanAgentId.normalizedAgentId()
            ?: xiaowanAgentId

        require(normalizedConversation == null || normalizedSession == null ||
            normalizedConversation == normalizedSession) {
            "agent_owner_conflict: Conversation agent $normalizedConversation does not match session agent $normalizedSession."
        }
        val persistedOwner = normalizedConversation ?: normalizedSession
        if (isNormalMode(conversationMode) || isChatOnlyMode(conversationMode)) {
            require(persistedOwner == null || persistedOwner == normalizedXiaowan) {
                "agent_owner_conflict: Xiaowan product mode cannot replace persisted agent $persistedOwner."
            }
            return HarnessResolution(
                agentId = normalizedXiaowan,
                requestedAgentId = normalizedRequested,
                conflictWithAgentId = normalizedRequested
                    ?.takeUnless { it == normalizedXiaowan }
                    ?.let { normalizedXiaowan },
            )
        }

        val resolvedAgent = requireNotNull(persistedOwner ?: normalizedRequested) {
            "agent_identity_required: Agent requests without a Conversation or Session owner must specify agentId."
        }
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
