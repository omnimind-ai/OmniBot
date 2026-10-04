package cn.com.omnimind.nativeui.chat

/*
 * Port of the subset of ui/lib/services/agent_message_kinds.dart that the
 * agent run timeline needs. Names match the Dart originals.
 */

internal const val kAgentToolSummaryCardType = "agent_tool_summary"
internal const val kAgentRequestCardType = "agent_request"

// Read-only compatibility for conversation snapshots created before Agent mode
// used the shared ACP tool card types.
private const val legacyAgentRequestCardType = "codex_request"

internal fun isAgentRequestCardType(value: Any?): Boolean {
    val normalized = value?.toString()?.kindsDartTrim() ?: ""
    return normalized == kAgentRequestCardType ||
        normalized == legacyAgentRequestCardType
}

/**
 * Whether a tool-summary message is an ACP plan snapshot.
 *
 * Plans are stateful protocol data rather than transient tool activity. Keep
 * this predicate shared so the timeline can leave the plan card visible while
 * its `planEntries` are replaced by subsequent ACP updates.
 */
internal fun isAgentPlanMessage(message: ChatMessageUi): Boolean {
    val card = message.cardData ?: return false
    val type = (card["type"] ?: "").toString().kindsDartTrim().lowercase()
    if (type == "plan" || type == "todo_list") return true
    if (type != kAgentToolSummaryCardType) return false
    return (card["toolType"] ?: "").toString().kindsDartTrim().lowercase() == "plan" ||
        (card["toolName"] ?: "").toString().kindsDartTrim().lowercase() == "plan"
}

/** Whether the message is a request card (approval / user input). */
internal fun isAgentRequestMessage(message: ChatMessageUi): Boolean {
    return isAgentRequestCardType(message.cardData?.get("type"))
}

/** Dart `String.trim()` whitespace set (differs from Kotlin's `trim()`). */
private fun String.kindsDartTrim(): String = trim { c ->
    when (c) {
        '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', ' ', '\u0085', ' ',
        ' ', ' ', ' ', ' ', ' ', '　', '﻿' -> true
        in ' '..' ' -> true
        else -> false
    }
}
