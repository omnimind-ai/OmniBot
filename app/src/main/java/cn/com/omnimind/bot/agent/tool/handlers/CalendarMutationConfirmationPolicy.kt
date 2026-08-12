package cn.com.omnimind.bot.agent.tool.handlers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Every device-calendar read or mutation requires a trusted, local user action. */
internal object CalendarMutationConfirmationPolicy {
    private val calendarTools = setOf(
        "calendar_list",
        "calendar_event_create",
        "calendar_event_list",
        "calendar_event_update",
        "calendar_event_delete",
    )

    fun requiresExplicitUserConsent(toolName: String): Boolean = toolName in calendarTools

    fun title(toolName: String, english: Boolean): String = when {
        english && toolName == "calendar_list" -> "Share calendar accounts?"
        english && toolName == "calendar_event_list" -> "Share calendar events?"
        english -> "Change device calendar?"
        toolName == "calendar_list" -> "允许读取日历账户？"
        toolName == "calendar_event_list" -> "允许读取日程？"
        else -> "允许更改设备日历？"
    }

    fun question(toolName: String, arguments: JsonObject, english: Boolean): String {
        val action = actionDescription(toolName, arguments, english)
        val disclosure = if (english) {
            "If you approve, the requested calendar data and tool result may be sent to the active AI provider as part of this conversation. This approval applies only to this exact request."
        } else {
            "批准后，本次请求涉及的日历数据和工具结果可能作为当前对话的一部分发送给正在使用的 AI Provider。本次批准仅适用于这一项完全相同的请求。"
        }
        return "$action\n\n$disclosure"
    }

    fun deniedMessage(english: Boolean): String = if (english) {
        "Calendar access was not approved by a local user. No calendar data was read or changed."
    } else {
        "本地用户未批准日历访问；未读取或更改任何日历数据。"
    }

    fun unavailableMessage(english: Boolean): String = if (english) {
        "Calendar access requires approval in the foreground app. No calendar data was read or changed."
    } else {
        "日历访问必须在前台应用中由用户批准；未读取或更改任何日历数据。"
    }

    private fun actionDescription(
        toolName: String,
        arguments: JsonObject,
        english: Boolean,
    ): String {
        val title = safeValue(arguments, "title")
        val startAt = safeValue(arguments, "startAt")
        val endAt = safeValue(arguments, "endAt")
        val eventId = safeValue(arguments, "eventId")
        val query = safeValue(arguments, "query")
        return if (english) {
            when (toolName) {
                "calendar_list" -> "Allow this request to read your visible calendar account names and identifiers?"
                "calendar_event_list" -> listOfNotNull(
                    "Allow this request to read calendar event titles, times, locations, and identifiers?",
                    query?.let { "Search: $it" },
                    startAt?.let { "From: $it" },
                    endAt?.let { "To: $it" },
                ).joinToString("\n")
                "calendar_event_create" -> listOfNotNull(
                    "Create this calendar event?",
                    title?.let { "Title: $it" },
                    startAt?.let { "Starts: $it" },
                    endAt?.let { "Ends: $it" },
                ).joinToString("\n")
                "calendar_event_update" -> listOfNotNull(
                    "Update this calendar event?",
                    eventId?.let { "Event: $it" },
                    title?.let { "New title: $it" },
                    startAt?.let { "New start: $it" },
                    endAt?.let { "New end: $it" },
                ).joinToString("\n")
                "calendar_event_delete" -> listOfNotNull(
                    "Delete this calendar event?",
                    eventId?.let { "Event: $it" },
                ).joinToString("\n")
                else -> "Allow this calendar request?"
            }
        } else {
            when (toolName) {
                "calendar_list" -> "允许本次请求读取可见日历的账户名称和标识符吗？"
                "calendar_event_list" -> listOfNotNull(
                    "允许本次请求读取日程标题、时间、地点和标识符吗？",
                    query?.let { "搜索：$it" },
                    startAt?.let { "开始：$it" },
                    endAt?.let { "结束：$it" },
                ).joinToString("\n")
                "calendar_event_create" -> listOfNotNull(
                    "创建这个日程吗？",
                    title?.let { "标题：$it" },
                    startAt?.let { "开始：$it" },
                    endAt?.let { "结束：$it" },
                ).joinToString("\n")
                "calendar_event_update" -> listOfNotNull(
                    "修改这个日程吗？",
                    eventId?.let { "事件：$it" },
                    title?.let { "新标题：$it" },
                    startAt?.let { "新开始时间：$it" },
                    endAt?.let { "新结束时间：$it" },
                ).joinToString("\n")
                "calendar_event_delete" -> listOfNotNull(
                    "删除这个日程吗？",
                    eventId?.let { "事件：$it" },
                ).joinToString("\n")
                else -> "允许本次日历请求吗？"
            }
        }
    }

    private fun safeValue(arguments: JsonObject, key: String): String? =
        arguments[key]?.jsonPrimitive?.contentOrNull
            ?.replace(Regex("[\\r\\n\\t]+"), " ")
            ?.trim()
            ?.take(MAX_DISPLAY_CHARS)
            ?.takeIf(String::isNotEmpty)

    private const val MAX_DISPLAY_CHARS = 160
}
