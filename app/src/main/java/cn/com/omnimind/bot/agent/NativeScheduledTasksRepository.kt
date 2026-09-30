package cn.com.omnimind.bot.agent

import android.content.Context

/**
 * Native adapter for the scheduled-tasks page. `WorkspaceScheduledTaskScheduler`
 * stays the single scheduling/storage owner (its upsert/delete already mirror
 * into the Flutter preference store), and `AgentAlarmToolService` keeps the
 * exact-alarm records. This class only adapts payloads; it never schedules or
 * cancels alarms itself.
 */
internal class NativeScheduledTasksRepository(context: Context) {
    private val scheduler = WorkspaceScheduledTaskScheduler(context.applicationContext)
    private val alarms = AgentAlarmToolService(context.applicationContext)

    fun listTasks(): List<NativeScheduledTask> =
        scheduler.listTasks().mapNotNull(::parseTask)

    /** Full-field upsert, same payload shape as the Flutter page's `task.toJson()`. */
    fun saveTask(payload: Map<String, Any?>) {
        scheduler.upsertTask(payload)
    }

    fun deleteTask(taskId: String) {
        scheduler.deleteTask(taskId)
    }

    fun listAlarms(): List<NativeExactAlarm> =
        alarms.listExactReminders().mapNotNull { raw ->
            val triggerAt = (raw["triggerAtMillis"] as? Number)?.toLong() ?: 0L
            if (triggerAt <= 0L) return@mapNotNull null
            NativeExactAlarm(
                alarmId = raw["alarmId"]?.toString().orEmpty(),
                title = raw["title"]?.toString().orEmpty(),
                message = raw["message"]?.toString().orEmpty(),
                triggerAtMillis = triggerAt,
                timezone = raw["timezone"]?.toString().orEmpty(),
            )
        }

    fun deleteAlarm(alarmId: String) {
        alarms.deleteExactReminder(alarmId)
    }

    private fun parseTask(raw: Map<String, Any?>): NativeScheduledTask? {
        val taskId = raw["taskId"]?.toString()?.trim().orEmpty()
        if (taskId.isEmpty()) return null
        return NativeScheduledTask(
            taskId = taskId,
            title = raw["title"]?.toString().orEmpty(),
            targetKind = raw["targetKind"]?.toString().orEmpty(),
            scheduleType = raw["scheduleType"]?.toString().orEmpty(),
            fixedTime = raw["fixedTime"]?.toString()?.takeIf(String::isNotBlank),
            countdownMinutes = (raw["countdownMinutes"] as? Number)?.toInt(),
            repeatDaily = raw["repeatDaily"] == true,
            enabled = raw["enabled"] != false,
            notificationEnabled = raw["notificationEnabled"] != false,
            nextExecutionTime = (raw["nextExecutionTime"] as? Number)?.toLong(),
            createdAt = (raw["createdAt"] as? Number)?.toLong() ?: 0L,
            subagentConversationId = raw["subagentConversationId"]?.toString(),
            parentConversationId = raw["parentConversationId"]?.toString(),
            parentConversationMode = raw["parentConversationMode"]?.toString(),
            subagentPrompt = raw["subagentPrompt"]?.toString(),
        )
    }
}

internal data class NativeScheduledTask(
    val taskId: String,
    val title: String,
    val targetKind: String,
    val scheduleType: String,
    val fixedTime: String?,
    val countdownMinutes: Int?,
    val repeatDaily: Boolean,
    val enabled: Boolean,
    val notificationEnabled: Boolean,
    val nextExecutionTime: Long?,
    val createdAt: Long,
    val subagentConversationId: String?,
    val parentConversationId: String?,
    val parentConversationMode: String?,
    val subagentPrompt: String?,
) {
    /** Same wire shape as the Flutter page's `ScheduledTask.toJson()`. */
    fun toUpsertPayload(
        scheduleType: String,
        fixedTime: String?,
        countdownMinutes: Int?,
        repeatDaily: Boolean,
        nextExecutionTime: Long,
    ): Map<String, Any?> = mapOf(
        "id" to taskId,
        "title" to title,
        "targetKind" to targetKind,
        "subagentConversationId" to subagentConversationId,
        "parentConversationId" to parentConversationId,
        "parentConversationMode" to parentConversationMode,
        "subagentPrompt" to subagentPrompt,
        "notificationEnabled" to notificationEnabled,
        "type" to if (scheduleType == "countdown") "countdown" else "fixedTime",
        "fixedTime" to fixedTime,
        "countdownMinutes" to countdownMinutes,
        "repeatDaily" to repeatDaily,
        "isEnabled" to true,
        "createdAt" to createdAt,
        "nextExecutionTime" to nextExecutionTime,
    )
}

internal data class NativeExactAlarm(
    val alarmId: String,
    val title: String,
    val message: String,
    val triggerAtMillis: Long,
    val timezone: String,
)
