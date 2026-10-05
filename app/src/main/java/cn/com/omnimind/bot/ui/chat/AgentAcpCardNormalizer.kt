package cn.com.omnimind.bot.ui.chat

import cn.com.omnimind.bot.agent.projection.DartJson
import cn.com.omnimind.bot.agent.projection.normalizeAgentToolStatus

/**
 * Kotlin port of `ui/lib/services/agent_acp_card_normalizer.dart`.
 *
 * Maps ACP / legacy card payloads onto the shared card types before they are
 * presented. Keep it mechanically comparable with the Dart source: same
 * method names, branch order and `??=` fallbacks (a `??=` assigns when the key
 * is absent or holds null).
 */
internal object AgentAcpCardNormalizer {
    fun normalize(source: Map<String, Any?>): Map<String, Any?> {
        val card = LinkedHashMap(source)
        val type = string(card["type"] ?: card["sessionUpdate"] ?: card["kind"])

        if (isRequestType(type)) {
            card["type"] = "agent_request"
            card.putIfNull("requestKind") { requestKind(type) }
            return card
        }

        if (isThinkingType(type)) {
            return normalizeThinking(card)
        }

        if (isPlanType(type) ||
            (type == "agent_tool_summary" &&
                (dartTrim(string(card["toolType"])).lowercase() == "plan" ||
                    dartTrim(string(card["toolName"])).lowercase() == "plan"))
        ) {
            return normalizePlan(card)
        }

        if (isToolType(type)) {
            return normalizeTool(card, type = type)
        }

        return card
    }

    private fun normalizeThinking(source: Map<String, Any?>): Map<String, Any?> {
        val card = LinkedHashMap(source)
        val legacy = legacyThinkingValues(card)
        val currentText = string(
            card["thinkingContent"]
                ?: card["text"]
                ?: card["delta"]
                ?: card["content"]
                ?: card["summary"],
        )
        val legacyText = formatLegacyThinking(legacy)
        card["type"] = "deep_thinking"
        card["thinkingContent"] = if (currentText.isNotEmpty()) currentText else legacyText
        card.putIfNull("taskID") { card["taskId"] ?: card["runId"] }
        card.putIfNull("runId") { card["taskID"] }
        card.putIfNull("cardId") { card["entryId"] ?: card["itemId"] }
        card.putIfNull("stage") { thinkingStage(card) }
        card.putIfNull("isLoading") { !dartNumEquals(card["stage"], 4) && !dartNumEquals(card["stage"], 5) }
        card.putIfNull("isCollapsible") { card["isLoading"] == false }
        if (legacy.taskTitle.isNotEmpty()) card["taskTitle"] = legacy.taskTitle
        if (legacy.subTasks.isNotEmpty()) card["subTasks"] = legacy.subTasks
        if (legacy.preparation.isNotEmpty()) card["preparation"] = legacy.preparation
        if (legacy.memoryActions.isNotEmpty()) {
            card["memoryActions"] = legacy.memoryActions
        }
        return card
    }

    private fun normalizePlan(source: Map<String, Any?>): Map<String, Any?> {
        val card = LinkedHashMap(source)
        val text = dartTrim(
            string(card["summary"] ?: card["progress"] ?: card["text"]),
        )
        val structuredEntries = planEntries(card["planEntries"] ?: card["entries"])
        val plan = card["plan"]
        val nestedPlanEntries = if (plan is Map<*, *>) {
            planEntries(plan["entries"])
        } else {
            emptyList()
        }
        val planValue = if (structuredEntries.isNotEmpty()) {
            card["entries"]
        } else if (nestedPlanEntries.isNotEmpty()) {
            (plan as Map<*, *>)["entries"]
        } else {
            card["plan"]
        }
        var entries = if (structuredEntries.isNotEmpty()) structuredEntries else nestedPlanEntries
        val planText = if (text.isNotEmpty()) text else planText(planValue)
        // ACP v2 permits a markdown plan payload instead of a structured
        // entries array. Parse only explicit task-list rows.
        if (entries.isEmpty() && planText.isNotEmpty()) {
            entries = parsePlanMarkdown(planText)
        }
        val effectivePlanText = if (planText.isNotEmpty()) planText else formatPlan(entries)
        val terminal = entries.isNotEmpty() &&
            entries.all { entry -> isTerminalPlanStatus(entry["status"]) }
        card["type"] = "agent_tool_summary"
        card.putIfNull("uiStyle") { "agent_tool" }
        card["toolType"] = "plan"
        card.putIfNull("toolName") { "plan" }
        card.putIfNull("toolTitle") { "任务计划" }
        card.putIfNull("displayName") { "任务计划" }
        card["summary"] = effectivePlanText
        card["progress"] = effectivePlanText
        card.putIfNull("status") { if (terminal) "success" else "running" }
        card["planEntries"] = entries
        card.putIfNull("rawInput") { linkedMapOf<String, Any?>("entries" to entries) }
        return card
    }

    private fun normalizeTool(source: Map<String, Any?>, type: String): Map<String, Any?> {
        val card = LinkedHashMap(source)
        card["type"] = "agent_tool_summary"
        card.putIfNull("uiStyle") { "agent_tool" }
        card.putIfNull("toolCallId") { card["tool_call_id"] ?: card["callId"] }
        card.putIfNull("toolName") { card["name"] ?: card["title"] ?: type }
        card.putIfNull("toolTitle") { card["title"] ?: card["name"] ?: "工具调用" }
        card.putIfNull("displayName") { card["toolTitle"] }
        card.putIfNull("toolType") { toolType(type) }
        card["status"] = normalizeAgentToolStatus(
            card,
            fallbackStatus = if (dartTrim(string(card["status"])).isEmpty()) {
                "running"
            } else {
                string(card["status"])
            },
        )
        card.putIfNull("summary") { contentText(card["content"] ?: card["rawOutput"]) }
        card.putIfNull("progress") { card["summary"] }
        return card
    }

    private class LegacyThinkingValues(
        val taskDescription: String,
        val subTasks: List<String>,
        val preparation: String,
        val taskTitle: String,
        val memoryActions: List<String>,
    )

    private fun legacyThinkingValues(source: Map<String, Any?>): LegacyThinkingValues {
        val nested = decodeMap(source["deep_thinking"] ?: source["deepThinking"])
        val raw: Map<String, Any?> = if (nested == null) {
            source
        } else {
            LinkedHashMap(source).apply { putAll(nested) }
        }
        val contentMap = decodeMap(raw["thinkingContent"] ?: raw["content"])
        val values: Map<String, Any?> = if (contentMap == null) {
            raw
        } else {
            LinkedHashMap(raw).apply { putAll(contentMap) }
        }
        return LegacyThinkingValues(
            taskDescription = string(values["task_description"] ?: values["taskDescription"]),
            subTasks = stringList(values["sub_tasks"] ?: values["subTasks"]),
            preparation = string(values["preparation"]),
            taskTitle = string(values["task_title"] ?: values["taskTitle"]),
            memoryActions = stringList(values["memory_actions"] ?: values["memoryActions"]),
        )
    }

    private fun formatLegacyThinking(values: LegacyThinkingValues): String {
        val parts = ArrayList<String>()
        if (values.taskDescription.isNotEmpty()) parts.add(values.taskDescription)
        if (values.subTasks.isNotEmpty()) {
            parts.add(
                values.subTasks
                    .mapIndexed { index, value -> "任务${index + 1}: $value" }
                    .joinToString("\n"),
            )
        }
        if (values.preparation.isNotEmpty()) parts.add(values.preparation)
        if (values.memoryActions.isNotEmpty()) {
            parts.add("记忆：${values.memoryActions.joinToString("、")}")
        }
        return parts.joinToString("\n\n")
    }

    private fun formatPlan(entries: List<Map<String, Any?>>): String {
        return entries
            .map { entry ->
                val status = string(entry["status"]).lowercase()
                val marker = if (isTerminalPlanStatus(status)) "[x]" else "[ ]"
                "$marker ${string(entry["content"] ?: entry["title"])}"
            }
            .filter { line -> dartTrim(line).length > 4 }
            .joinToString("\n")
    }

    private fun planEntries(value: Any?): List<Map<String, Any?>> {
        if (value is List<*>) {
            return value
                .filterIsInstance<Map<*, *>>()
                .map { entry ->
                    val copy = LinkedHashMap<String, Any?>()
                    for ((key, nested) in entry) copy[key as String] = nested
                    copy
                }
        }
        return emptyList()
    }

    private val planRowRegex =
        Regex("^[-*+]$JS_WS+\\[([^\\]]*)\\]$JS_WS+($JS_DOT+)\\z")
    private val planNumberedRegex =
        Regex("^\\d+[.)]$JS_WS+(?:\\[([^\\]]*)\\]$JS_WS+)?($JS_DOT+)\\z")

    private fun parsePlanMarkdown(text: String): List<Map<String, Any?>> {
        val entries = ArrayList<Map<String, Any?>>()
        for (rawLine in text.split("\n")) {
            val line = dartTrim(rawLine)
            if (line.isEmpty() || line.startsWith("#")) continue
            val match = planRowRegex.find(line) ?: planNumberedRegex.find(line) ?: continue
            val marker = match.groups[1]?.value?.let { dartTrim(it).lowercase() } ?: ""
            val content = dartTrim(match.groups[2]?.value ?: "")
            if (content.isEmpty()) continue
            val status = when (marker) {
                "x", "✓", "done", "completed", "complete" -> "completed"
                "~", "-", "in_progress", "in-progress", "running" -> "in_progress"
                else -> "pending"
            }
            entries.add(linkedMapOf("content" to content, "status" to status))
        }
        return entries
    }

    private fun planText(value: Any?): String {
        if (value is String) return dartTrim(value)
        if (value is Map<*, *>) {
            return string(value["content"] ?: value["text"] ?: value["markdown"])
        }
        return ""
    }

    private fun isTerminalPlanStatus(value: Any?): Boolean {
        val status = string(value).lowercase()
        return status == "completed" || status == "complete" || status == "done"
    }

    private fun thinkingStage(card: Map<String, Any?>): Int {
        val phase = string(card["phase"] ?: card["stageName"]).lowercase()
        if (phase == "complete" || phase == "completed") return 4
        if (phase == "cancelled" || phase == "canceled") return 5
        if (phase == "planning" || phase == "plan") return 2
        if (phase == "preparing" || phase == "preparation") return 3
        return 1
    }

    private fun isThinkingType(type: String?): Boolean {
        return type == "deep_thinking" ||
            type == "thinking" ||
            type == "reasoning" ||
            type == "agent_thought_chunk" ||
            type == "agentThoughtChunk"
    }

    private fun isPlanType(type: String?): Boolean {
        return type == "plan" || type == "todo_list" || type == "turn_plan"
    }

    private fun isRequestType(type: String?): Boolean {
        return type == "codex_request" ||
            type == "requestApproval" ||
            type == "requestUserInput" ||
            type == "request_approval" ||
            type == "request_user_input"
    }

    private fun isToolType(type: String?): Boolean {
        if (type == null) return false
        return type == "tool_call" ||
            type == "tool_call_update" ||
            type == "commandExecution" ||
            type == "command_execution" ||
            type == "mcpToolCall" ||
            type == "mcp_tool_call" ||
            type == "webSearch" ||
            type == "web_search" ||
            type == "fileChange" ||
            type == "file_change"
    }

    private fun requestKind(type: String?): String {
        return if (type == "requestUserInput" || type == "request_user_input") {
            "user_input"
        } else {
            "approval"
        }
    }

    private fun toolType(type: String): String {
        if (type.contains("mcp")) return "mcp"
        if (type.contains("web")) return "web"
        if (type.contains("file")) return "file"
        if (type.contains("command")) return "terminal"
        return "builtin"
    }

    private fun contentText(value: Any?): String {
        if (value is String) return dartTrim(value)
        if (value is Map<*, *>) {
            return dartTrim(string(value["text"] ?: value["summary"] ?: value["content"]))
        }
        if (value is List<*>) {
            return value
                .map { contentText(it) }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
        }
        return ""
    }

    private fun decodeMap(value: Any?): Map<String, Any?>? {
        if (value is Map<*, *>) return stringKeyed(value)
        if (value !is String) return null
        val text = dartTrim(value)
        if (!text.startsWith("{") || !text.endsWith("}")) return null
        return try {
            val decoded = DartJson.decode(text)
            if (decoded is Map<*, *>) stringKeyed(decoded) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun stringKeyed(value: Map<*, *>): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for ((key, nested) in value) result[key as String] = nested
        return result
    }

    private fun stringList(value: Any?): List<String> {
        if (value is List<*>) {
            return value.map { string(it) }.filter { it.isNotEmpty() }
        }
        val text = string(value)
        return if (text.isEmpty()) emptyList() else listOf(text)
    }

    /** Dart `_string`: `value?.toString().trim() ?? ''`. */
    private fun string(value: Any?): String =
        if (value == null) "" else dartTrim(dartToStringValue(value))

    private inline fun MutableMap<String, Any?>.putIfNull(key: String, value: () -> Any?) {
        if (this[key] == null) this[key] = value()
    }

    /** Dart `==` between a JSON number and an int literal. */
    private fun dartNumEquals(value: Any?, expected: Int): Boolean = when (value) {
        is Int, is Long, is Short, is Byte -> (value as Number).toLong() == expected.toLong()
        is Double -> value == expected.toDouble()
        is Float -> value.toDouble() == expected.toDouble()
        else -> false
    }
}
