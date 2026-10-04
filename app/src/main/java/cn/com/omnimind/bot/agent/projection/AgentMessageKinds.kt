package cn.com.omnimind.bot.agent.projection

internal const val AGENT_TOOL_UI_STYLE = "agent_tool"
internal const val AGENT_TOOL_SUMMARY_CARD_TYPE = "agent_tool_summary"
internal const val AGENT_REQUEST_CARD_TYPE = "agent_request"

// Read-only compatibility for snapshots created before Agent mode used the
// shared ACP tool card types.
private const val LEGACY_AGENT_TOOL_UI_STYLE = "codex_tool"
private const val LEGACY_AGENT_REQUEST_CARD_TYPE = "codex_request"

internal fun isAgentToolUiStyle(value: Any?): Boolean {
    val normalized = dartToString(value)?.trim().orEmpty()
    return normalized == AGENT_TOOL_UI_STYLE || normalized == LEGACY_AGENT_TOOL_UI_STYLE
}

internal fun canonicalAgentToolUiStyle(value: Any?): String =
    if (isAgentToolUiStyle(value)) AGENT_TOOL_UI_STYLE else dartToString(value)?.trim().orEmpty()

internal fun isAgentRequestCardType(value: Any?): Boolean {
    val normalized = dartToString(value)?.trim().orEmpty()
    return normalized == AGENT_REQUEST_CARD_TYPE || normalized == LEGACY_AGENT_REQUEST_CARD_TYPE
}

internal fun canonicalAgentRequestCardType(value: Any?): String =
    if (isAgentRequestCardType(value)) AGENT_REQUEST_CARD_TYPE else dartToString(value)?.trim().orEmpty()

private val toolSummaryTypes = setOf(
    AGENT_TOOL_SUMMARY_CARD_TYPE, "tool_call", "tool_call_update",
    "commandExecution", "command_execution", "mcpToolCall", "mcp_tool_call",
    "webSearch", "web_search", "fileChange", "file_change", "plan", "todo_list",
)

private fun cardTypeOf(message: ChatMessage): String =
    dartToString(message.cardData?.get("type") ?: "")!!.trim()

/** Whether the message is a tool-summary card, from any agent. */
internal fun isAgentToolSummaryMessage(message: ChatMessage): Boolean =
    cardTypeOf(message) in toolSummaryTypes

/** Whether a tool-summary message is an ACP plan snapshot. */
internal fun isAgentPlanMessage(message: ChatMessage): Boolean {
    val card = message.cardData ?: return false
    val type = dartToString(card["type"] ?: "")!!.trim().lowercase()
    if (type == "plan" || type == "todo_list") return true
    if (type != AGENT_TOOL_SUMMARY_CARD_TYPE) return false
    return dartToString(card["toolType"] ?: "")!!.trim().lowercase() == "plan" ||
        dartToString(card["toolName"] ?: "")!!.trim().lowercase() == "plan"
}

internal fun isAcpAgentToolSummaryMessage(message: ChatMessage): Boolean =
    isAgentToolSummaryMessage(message) && isAgentToolUiStyle(message.cardData?.get("uiStyle"))

internal fun isAgentRequestMessage(message: ChatMessage): Boolean =
    isAgentRequestCardType(message.cardData?.get("type"))

/** ACP user-input requests are answered through the single composer. */
internal fun isAgentUserInputRequestMessage(message: ChatMessage): Boolean =
    isAgentRequestMessage(message) &&
        dartToString(message.cardData?.get("requestKind") ?: "")!!.trim() == "user_input"

internal fun isAgentApprovalRequestMessage(message: ChatMessage): Boolean =
    isAgentRequestMessage(message) &&
        dartToString(message.cardData?.get("requestKind") ?: "")!!.trim() == "approval"

/** Whether the text reads as the "task cancelled" marker. */
internal fun isCancelledTaskText(message: ChatMessage): Boolean {
    val text = (message.text ?: "").trim().lowercase()
    return text == "任务已取消" || text == "task canceled" || text == "task cancelled"
}

internal fun canonicalizeAgentHistoryMessage(message: ChatMessage): ChatMessage {
    val sourceCardData = message.cardData ?: return message
    val cardData: JsonMap = LinkedHashMap(sourceCardData)
    var changed = false
    if (isAgentRequestCardType(cardData["type"])) {
        val type = canonicalAgentRequestCardType(cardData["type"])
        if (type != cardData["type"]) {
            cardData["type"] = type
            changed = true
        }
    }
    if (isAgentToolUiStyle(cardData["uiStyle"])) {
        val uiStyle = canonicalAgentToolUiStyle(cardData["uiStyle"])
        if (uiStyle != cardData["uiStyle"]) {
            cardData["uiStyle"] = uiStyle
            changed = true
        }
    }
    val rawToolName = dartToString(cardData["toolName"])
    if (rawToolName != null) {
        val toolName = canonicalAgentToolName(rawToolName)
        if (toolName != rawToolName) {
            cardData["toolName"] = toolName
            changed = true
        }
    }
    if (!changed) return message
    return message.copy(
        content = LinkedHashMap(message.content ?: emptyMap()).apply {
            put("cardData", cardData)
            put("id", message.contentId ?: message.id)
        },
    )
}

// ---- Run identity helpers (Dart `agent_run_timeline.dart`) ----

private val legacyAgentTaskId = Regex("^\\d{13}-ai$")
private val entryIdSuffixes = listOf("-assistant", "-clarify", "-permission", "-error", "-thinking", "-text")
private val entryIdMarkers = listOf("-thinking-", "-text-", "-tool-", "-permission-")

internal fun agentRunId(message: ChatMessage): String? {
    val normalized = message.runId?.trim().orEmpty()
    if (normalized.isNotEmpty()) return normalized
    if (message.user == 1) return null
    return agentTaskIdFromEntryId(message.id) ?: agentTaskIdFromEntryId(message.contentId)
}

internal fun agentRunParentTaskId(message: ChatMessage): String? = agentRunId(message)

internal fun agentRunKind(message: ChatMessage): String =
    dartToString(message.streamMeta?.get("kind") ?: "")!!.trim().lowercase()

private fun agentTaskIdFromEntryId(raw: String?): String? {
    val id = raw?.trim().orEmpty()
    if (id.isEmpty()) return null
    // Legacy Xiaowan final replies used the run id itself as the message id.
    if (legacyAgentTaskId.matches(id)) return id
    for (suffix in entryIdSuffixes) {
        if (id.endsWith(suffix)) return id.substring(0, id.length - suffix.length)
    }
    for (marker in entryIdMarkers) {
        val index = id.indexOf(marker)
        if (index > 0) return id.substring(0, index)
    }
    return null
}

/**
 * Keeps the first slot (stable chronology) and the newest value (latest
 * stream snapshot) when a snapshot or replay repeats an entry id.
 */
internal fun canonicalizeChatMessagesById(messages: Iterable<ChatMessage>): List<ChatMessage> {
    val canonical = ArrayList<ChatMessage>()
    val indexById = HashMap<String, Int>()
    for (message in messages) {
        val id = message.id.trim()
        if (id.isEmpty()) {
            canonical.add(message)
            continue
        }
        val existingIndex = indexById[id]
        if (existingIndex == null) {
            indexById[id] = canonical.size
            canonical.add(message)
        } else {
            canonical[existingIndex] = message
        }
    }
    return canonical
}
