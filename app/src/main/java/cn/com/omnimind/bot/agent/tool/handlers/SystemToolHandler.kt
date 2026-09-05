package cn.com.omnimind.bot.agent.tool.handlers

import android.net.Uri
import cn.com.omnimind.bot.agent.*
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class SystemToolHandler(
    private val helper: SharedHelper,
    private val scheduleToolBridge: AgentScheduleToolBridge,
    private val workspaceManager: AgentWorkspaceManager
) : ToolHandler {
    override val toolNames: Set<String> = setOf(
        "schedule_task_create", "schedule_task_list", "schedule_task_update", "schedule_task_delete",
        "alarm_reminder_create", "alarm_reminder_list", "alarm_reminder_delete",
        "calendar_list", "calendar_event_create", "calendar_event_list", "calendar_event_update", "calendar_event_delete",
        "music_playback_control"
    )

    private val alarmToolService = AgentAlarmToolService(helper.context)
    private val calendarToolService = AgentCalendarToolService(helper.context)
    private val musicToolService = AgentMusicToolService(helper.context, workspaceManager)

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val toolName = toolCall.function.name
        return when (toolName) {
            in setOf("schedule_task_create", "schedule_task_list", "schedule_task_update", "schedule_task_delete") ->
                executeScheduleTool(toolName, args, env.runtimeContextRepository, callback)
            in setOf("alarm_reminder_create", "alarm_reminder_list", "alarm_reminder_delete") ->
                executeAlarmTool(toolName, args, callback)
            in setOf("calendar_list", "calendar_event_create", "calendar_event_list", "calendar_event_update", "calendar_event_delete") ->
                executeCalendarTool(toolName, args, callback)
            "music_playback_control" -> executeMusicTool(args, env.workspaceDescriptor, callback)
            else -> ToolExecutionResult.Error(toolName, "Unknown system tool")
        }
    }

    private suspend fun executeScheduleTool(
        toolName: String,
        args: JsonObject,
        runtimeContextRepository: AgentRuntimeContextRepository,
        callback: AgentCallback
    ): ToolExecutionResult {
        return try {
            when (toolName) {
                "schedule_task_create" -> {
                    helper.reportToolProgress(callback, toolName, "正在创建定时任务")
                    val payload = helper.jsonObjectToMap(args).toMutableMap()
                    payload["targetKind"] = "subagent"
                    val prompt = payload["subagentPrompt"]?.toString()?.trim().orEmpty()
                    if (prompt.isEmpty()) { throw IllegalArgumentException("subagent 定时任务缺少 subagentPrompt") }
                    if (!payload.containsKey("notificationEnabled")) { payload["notificationEnabled"] = true }
                    if (!payload.containsKey("enabled")) { payload["enabled"] = true }
                    val result = scheduleToolBridge.createTask(payload)
                    ToolExecutionResult.ScheduleResult(
                        toolName = toolName,
                        summaryText = helper.localized(result["summary"]?.toString() ?: "定时任务已创建"),
                        previewJson = helper.encodeLocalizedPayload(result),
                        success = result["success"] != false,
                        taskId = result["taskId"]?.toString()
                    )
                }
                "schedule_task_list" -> {
                    helper.reportToolProgress(callback, toolName, "正在读取定时任务列表")
                    val result = scheduleToolBridge.listTasks()
                    val preview = helper.encodeLocalizedPayload(result)
                    val summary = if (result.isEmpty()) "当前没有定时任务。" else "当前共有 ${result.size} 个定时任务。"
                    ToolExecutionResult.ScheduleResult(toolName = toolName, summaryText = helper.localized(summary), previewJson = preview, success = true)
                }
                "schedule_task_update" -> {
                    helper.reportToolProgress(callback, toolName, "正在更新定时任务")
                    val payload = helper.jsonObjectToMap(args).toMutableMap()
                    payload["targetKind"] = "subagent"
                    val result = scheduleToolBridge.updateTask(payload)
                    ToolExecutionResult.ScheduleResult(
                        toolName = toolName,
                        summaryText = helper.localized(result["summary"]?.toString() ?: "定时任务已更新"),
                        previewJson = helper.encodeLocalizedPayload(result),
                        success = result["success"] != false,
                        taskId = result["taskId"]?.toString()
                    )
                }
                "schedule_task_delete" -> {
                    helper.reportToolProgress(callback, toolName, "正在删除定时任务")
                    val result = scheduleToolBridge.deleteTask(helper.jsonObjectToMap(args))
                    ToolExecutionResult.ScheduleResult(
                        toolName = toolName,
                        summaryText = helper.localized(result["summary"]?.toString() ?: "定时任务已删除"),
                        previewJson = helper.encodeLocalizedPayload(result),
                        success = result["success"] != false,
                        taskId = result["taskId"]?.toString()
                    )
                }
                else -> ToolExecutionResult.Error(toolName, "Unknown schedule tool")
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ToolExecutionResult.Error(toolName, helper.localized(e.message ?: "Schedule bridge failed")) }
    }

    private suspend fun executeAlarmTool(toolName: String, args: JsonObject, callback: AgentCallback): ToolExecutionResult {
        return try {
            when (toolName) {
                "alarm_reminder_create" -> {
                    helper.reportToolProgress(callback, toolName, "正在创建提醒闹钟")
                    val mode = args["mode"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val triggerAt = args["triggerAt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val message = args["message"]?.jsonPrimitive?.contentOrNull?.trim()
                    val timezone = args["timezone"]?.jsonPrimitive?.contentOrNull?.trim()
                    val allowWhileIdle = args["allowWhileIdle"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                    val skipUi = args["skipUi"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                    if (title.isBlank()) { throw IllegalArgumentException("title 不能为空") }
                    if (triggerAt.isBlank()) { throw IllegalArgumentException("triggerAt 不能为空") }
                    if (mode == "exact_alarm" && !alarmToolService.hasExactAlarmPermission()) {
                        alarmToolService.openExactAlarmPermissionSettings()
                        return helper.permissionRequiredResult(callback, listOf("精确闹钟权限(SCHEDULE_EXACT_ALARM)"))
                    }
                    if (mode == "exact_alarm" && !alarmToolService.hasNotificationPermission()) {
                        val granted = alarmToolService.requestNotificationPermission()
                        if (!granted) { return helper.permissionRequiredResult(callback, listOf("通知权限(POST_NOTIFICATIONS)")) }
                    }
                    val payload = alarmToolService.createReminder(AgentAlarmCreateRequest(mode = mode, title = title, triggerAt = triggerAt, message = message, timezone = timezone, allowWhileIdle = allowWhileIdle, skipUi = skipUi))
                    val payloadJson = helper.encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = helper.localized(payload["summary"]?.toString().orEmpty().ifBlank { "提醒闹钟已创建" }),
                        previewJson = payloadJson, rawResultJson = payloadJson, success = payload["success"] != false
                    )
                }
                "alarm_reminder_list" -> {
                    helper.reportToolProgress(callback, toolName, "正在读取提醒闹钟列表")
                    val items = alarmToolService.listExactReminders()
                    val payload = mapOf("count" to items.size, "items" to items)
                    val payloadJson = helper.encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = helper.localized(if (items.isEmpty()) "当前没有提醒闹钟。" else "当前共有 ${items.size} 个提醒闹钟。"),
                        previewJson = payloadJson, rawResultJson = payloadJson, success = true
                    )
                }
                "alarm_reminder_delete" -> {
                    helper.reportToolProgress(callback, toolName, "正在删除提醒闹钟")
                    val alarmId = args["alarmId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (alarmId.isBlank()) { throw IllegalArgumentException("alarmId 不能为空") }
                    val payload = alarmToolService.deleteExactReminder(alarmId)
                    val payloadJson = helper.encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = helper.localized(payload["summary"]?.toString().orEmpty().ifBlank { "提醒闹钟已删除" }),
                        previewJson = payloadJson, rawResultJson = payloadJson, success = payload["success"] != false
                    )
                }
                else -> ToolExecutionResult.Error(toolName, "Unknown alarm tool")
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ToolExecutionResult.Error(toolName, helper.localized(e.message ?: "Alarm tool failed")) }
    }

    private suspend fun executeCalendarTool(toolName: String, args: JsonObject, callback: AgentCallback): ToolExecutionResult {
        return try {
            if (!calendarToolService.hasCalendarPermissions()) {
                helper.reportToolProgress(callback, toolName, "正在请求日历权限")
                val granted = calendarToolService.requestCalendarPermissions()
                if (!granted) { return helper.permissionRequiredResult(callback, listOf("日历权限(READ/WRITE_CALENDAR)")) }
            }
            when (toolName) {
                "calendar_list" -> {
                    helper.reportToolProgress(callback, toolName, "正在读取日历列表")
                    val writableOnly = args["writableOnly"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                    val visibleOnly = args["visibleOnly"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                    val items = calendarToolService.listCalendars(writableOnly = writableOnly, visibleOnly = visibleOnly)
                    val payload = mapOf("count" to items.size, "items" to items)
                    val payloadJson = helper.encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = helper.localized(if (items.isEmpty()) "未找到符合条件的日历。" else "找到 ${items.size} 个日历。"),
                        previewJson = payloadJson, rawResultJson = payloadJson, success = true
                    )
                }
                "calendar_event_create" -> {
                    helper.reportToolProgress(callback, toolName, "正在创建日程")
                    val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val startAt = args["startAt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val endAt = args["endAt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (title.isBlank()) throw IllegalArgumentException("title 不能为空")
                    if (startAt.isBlank()) throw IllegalArgumentException("startAt 不能为空")
                    if (endAt.isBlank()) throw IllegalArgumentException("endAt 不能为空")
                    val payload = calendarToolService.createEvent(
                        CalendarEventCreateRequest(
                            title = title, startAt = startAt, endAt = endAt,
                            calendarId = args["calendarId"]?.jsonPrimitive?.contentOrNull?.trim(),
                            description = args["description"]?.jsonPrimitive?.contentOrNull?.trim(),
                            location = args["location"]?.jsonPrimitive?.contentOrNull?.trim(),
                            timezone = args["timezone"]?.jsonPrimitive?.contentOrNull?.trim(),
                            allDay = args["allDay"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                            reminderMinutes = helper.parseIntegerArray(args["reminderMinutes"] as? JsonArray)
                        )
                    )
                    val payloadJson = helper.encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = helper.localized(payload["summary"]?.toString().orEmpty().ifBlank { "日程已创建" }),
                        previewJson = payloadJson, rawResultJson = payloadJson, success = payload["success"] != false
                    )
                }
                "calendar_event_list" -> {
                    helper.reportToolProgress(callback, toolName, "正在查询日程")
                    val payload = calendarToolService.listEvents(
                        CalendarEventListRequest(
                            calendarId = args["calendarId"]?.jsonPrimitive?.contentOrNull?.trim(),
                            startAt = args["startAt"]?.jsonPrimitive?.contentOrNull?.trim(),
                            endAt = args["endAt"]?.jsonPrimitive?.contentOrNull?.trim(),
                            query = args["query"]?.jsonPrimitive?.contentOrNull?.trim(),
                            limit = resolveCalendarListLimit(args["limit"]?.jsonPrimitive?.intOrNull)
                        )
                    )
                    val payloadJson = helper.encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = helper.localized("找到 ${payload["count"] ?: 0} 条日程。"),
                        previewJson = payloadJson, rawResultJson = payloadJson, success = payload["success"] != false
                    )
                }
                "calendar_event_update" -> {
                    helper.reportToolProgress(callback, toolName, "正在修改日程")
                    val eventId = args["eventId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (eventId.isBlank()) throw IllegalArgumentException("eventId 不能为空")
                    val payload = calendarToolService.updateEvent(
                        CalendarEventUpdateRequest(
                            eventId = eventId,
                            title = args["title"]?.jsonPrimitive?.contentOrNull?.trim(),
                            startAt = args["startAt"]?.jsonPrimitive?.contentOrNull?.trim(),
                            endAt = args["endAt"]?.jsonPrimitive?.contentOrNull?.trim(),
                            description = args["description"]?.jsonPrimitive?.contentOrNull?.trim(),
                            location = args["location"]?.jsonPrimitive?.contentOrNull?.trim(),
                            timezone = args["timezone"]?.jsonPrimitive?.contentOrNull?.trim(),
                            allDay = args["allDay"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
                            reminderMinutes = if (args.containsKey("reminderMinutes")) helper.parseIntegerArray(args["reminderMinutes"] as? JsonArray) else null
                        )
                    )
                    val payloadJson = helper.encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = helper.localized(payload["summary"]?.toString().orEmpty().ifBlank { "日程已更新" }),
                        previewJson = payloadJson, rawResultJson = payloadJson, success = payload["success"] != false
                    )
                }
                "calendar_event_delete" -> {
                    helper.reportToolProgress(callback, toolName, "正在删除日程")
                    val eventId = args["eventId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (eventId.isBlank()) throw IllegalArgumentException("eventId 不能为空")
                    val payload = calendarToolService.deleteEvent(eventId)
                    val payloadJson = helper.encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = helper.localized(payload["summary"]?.toString().orEmpty().ifBlank { "日程已删除" }),
                        previewJson = payloadJson, rawResultJson = payloadJson, success = payload["success"] != false
                    )
                }
                else -> ToolExecutionResult.Error(toolName, "Unknown calendar tool")
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ToolExecutionResult.Error(toolName, helper.localized(e.message ?: "Calendar tool failed")) }
    }

    private suspend fun executeMusicTool(args: JsonObject, workspace: AgentWorkspaceDescriptor, callback: AgentCallback): ToolExecutionResult {
        val toolName = "music_playback_control"
        return try {
            val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
            val source = args["source"]?.jsonPrimitive?.contentOrNull?.trim()
            val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim()
            val loop = args["loop"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val positionSeconds = args["positionSeconds"]?.jsonPrimitive?.intOrNull
            if (action.isBlank()) { throw IllegalArgumentException("action 不能为空") }
            if (!source.isNullOrBlank()) {
                val needsWorkspaceResolution = source.startsWith("omnibot://", ignoreCase = true) ||
                    source.startsWith(AgentWorkspaceManager.SHELL_ROOT_PATH) ||
                    source.startsWith("/") || !source.contains("://")
                if (needsWorkspaceResolution) { helper.requireWorkspaceStorageAccess(callback)?.let { return it } }
                val publicCandidates = buildList {
                    add(source)
                    if (source.startsWith("file://", ignoreCase = true)) { Uri.parse(source).path?.let { add(it) } }
                }
                helper.requirePublicStorageAccessIfNeeded(callback, *publicCandidates.toTypedArray())?.let { return it }
            }
            helper.reportToolProgress(callback, toolName, when (action) {
                "play" -> if (source.isNullOrBlank()) "正在发送系统播放命令" else "正在准备播放音频"
                "pause" -> "正在暂停播放"
                "resume" -> "正在恢复播放"
                "stop" -> "正在停止播放"
                "seek" -> "正在调整播放进度"
                "status" -> "正在读取播放状态"
                "next" -> "正在切换到下一首"
                "previous" -> "正在切换到上一首"
                else -> "正在执行音乐播放控制"
            })
            val payload = when (action) {
                "play" -> musicToolService.play(AgentMusicPlayRequest(source = source, title = title, loop = loop), workspace)
                "pause" -> musicToolService.pause()
                "resume" -> musicToolService.resume()
                "stop" -> musicToolService.stop()
                "seek" -> { if (positionSeconds == null) throw IllegalArgumentException("seek 动作需要提供 positionSeconds"); musicToolService.seek(positionSeconds) }
                "status" -> musicToolService.status()
                "next" -> musicToolService.next()
                "previous" -> musicToolService.previous()
                else -> throw IllegalArgumentException("不支持的 action：$action")
            }
            val payloadJson = helper.encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized(payload["summary"]?.toString().orEmpty().ifBlank { "音乐播放控制已执行" }),
                previewJson = payloadJson, rawResultJson = payloadJson, success = payload["success"] != false
            )
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ToolExecutionResult.Error(toolName, helper.localized(e.message ?: "Music tool failed")) }
    }
}
