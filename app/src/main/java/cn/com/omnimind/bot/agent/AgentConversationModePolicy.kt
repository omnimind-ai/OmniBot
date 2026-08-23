package cn.com.omnimind.bot.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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

    private val subagentRestrictedToolNames = setOf(
        "schedule_task_create",
        "schedule_task_list",
        "schedule_task_update",
        "schedule_task_delete",
        "alarm_reminder_create",
        "alarm_reminder_list",
        "alarm_reminder_delete",
        "calendar_list",
        "calendar_event_create",
        "calendar_event_list",
        "calendar_event_update",
        "calendar_event_delete"
        // `subagent_dispatch` 的防递归改由 SubagentProfileRegistry.FORBIDDEN
        // (SubagentProfile.kt) 在每个子 Agent 的工具白名单里硬禁用。这样
        // 即便父 Agent 处于 subagent 模式,也能 spawn 真子 Agent;子 Agent
        // 自身看不到 subagent_dispatch,无法再递归。
    )

    fun isSubagentMode(conversationMode: String?): Boolean {
        return conversationMode?.trim()?.equals(SUBAGENT_MODE, ignoreCase = true) == true
    }

    fun isChatOnlyMode(conversationMode: String?): Boolean {
        return conversationMode?.trim()?.equals(CHAT_ONLY_MODE, ignoreCase = true) == true
    }

    fun isToolRestrictedInConversationMode(
        toolName: String,
        conversationMode: String?
    ): Boolean {
        if (isChatOnlyMode(conversationMode)) {
            return true
        }
        if (!isSubagentMode(conversationMode)) {
            return false
        }
        return subagentRestrictedToolNames.contains(toolName.trim())
    }

    fun restrictedToolNamesForConversationMode(conversationMode: String?): Set<String> {
        return if (isChatOnlyMode(conversationMode)) {
            // chat_only is handled as a deny-all policy by the definition
            // filter below; this set remains useful to callers that ask
            // whether an individual known tool is restricted.
            emptySet()
        } else if (isSubagentMode(conversationMode)) {
            subagentRestrictedToolNames
        } else {
            emptySet()
        }
    }

    fun filterToolDefinitionsForConversationMode(
        definitions: List<JsonObject>,
        conversationMode: String?
    ): List<JsonObject> {
        if (isChatOnlyMode(conversationMode)) {
            return emptyList()
        }
        val restricted = restrictedToolNamesForConversationMode(conversationMode)
        if (restricted.isEmpty()) {
            return definitions
        }
        return definitions.filterNot { definition ->
            val toolName = (definition["function"] as? JsonObject)
                ?.get("name")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                .orEmpty()
            restricted.contains(toolName)
        }
    }
}

private fun String?.normalizedAgentId(): String? {
    return this?.trim()?.takeIf { it.isNotEmpty() }
}
