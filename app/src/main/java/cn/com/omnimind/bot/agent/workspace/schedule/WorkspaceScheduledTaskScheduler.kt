package cn.com.omnimind.bot.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
import cn.com.omnimind.baselib.database.Conversation
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import cn.com.omnimind.bot.webchat.WebAgentRunBridge
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream
import java.time.LocalDateTime
import java.time.ZoneId

class WorkspaceScheduledTaskScheduler(
    private val context: Context
) {
    companion object {
        const val ACTION_SCHEDULED_TASK_TRIGGER =
            "cn.com.omnimind.bot.agent.ACTION_WORKSPACE_SCHEDULED_TASK_TRIGGER"
        const val EXTRA_TASK_ID = "taskId"

        private const val TAG = "WorkspaceTaskScheduler"
        private const val PREFS_NAME = "workspace_scheduled_tasks_native"
        private const val KEY_TASKS_JSON = "workspace_scheduled_tasks_json_v1"
        private const val FLUTTER_PREFS_NAME = "FlutterSharedPreferences"
        private const val FLUTTER_PREF_PREFIX = "flutter."
        private const val FLUTTER_SCHEDULED_TASKS_KEY = "${FLUTTER_PREF_PREFIX}scheduled_tasks"
        private const val LIST_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"
        private const val JSON_LIST_PREFIX = "$LIST_PREFIX!"
        private const val SUBAGENT_MODE = "subagent"
    }

    private data class StoredTask(
        val taskId: String,
        val title: String,
        val targetKind: String = SUBAGENT_MODE,
        val scheduleType: String = "fixed_time",
        val fixedTime: String? = null,
        val countdownMinutes: Int? = null,
        val repeatDaily: Boolean = false,
        val enabled: Boolean = true,
        val nextExecutionTime: Long? = null,
        val subagentConversationId: String? = null,
        val parentConversationId: String? = null,
        val parentConversationMode: String? = null,
        val subagentPrompt: String? = null,
        val notificationEnabled: Boolean = true
    )

    private val appContext = context.applicationContext
    private val gson = Gson()
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val agentRunBridge by lazy {
        WebAgentRunBridge(
            context = appContext,
            manager = AgentRuntimeManager.getInstance(appContext)
        )
    }

    fun upsertTask(rawTask: Map<String, Any?>): Map<String, Any?> {
        val taskId = rawTask["taskId"]?.toString()?.trim()
            ?.ifEmpty { null }
            ?: rawTask["id"]?.toString()?.trim().orEmpty()
        require(taskId.isNotEmpty()) { "taskId is empty" }
        val existingMap = loadTaskMapMutable()
        val existing = existingMap[taskId]
        var task = parseTask(rawTask, existing)
        if (task.enabled) {
            task = task.copy(
                nextExecutionTime = resolveNextExecutionAt(
                    task = task,
                    nowMillis = System.currentTimeMillis(),
                    preferExistingFuture = true
                )
            )
            scheduleAlarm(task)
        } else {
            cancelAlarm(task.taskId)
            task = task.copy(nextExecutionTime = null)
        }
        existingMap[task.taskId] = task
        persistTaskMap(existingMap)
        upsertTaskInFlutterStorage(task)
        return mapOf(
            "taskId" to task.taskId,
            "enabled" to task.enabled,
            "targetKind" to task.targetKind,
            "nextExecutionTime" to task.nextExecutionTime
        )
    }

    fun deleteTask(taskId: String): Boolean {
        val normalizedId = taskId.trim()
        if (normalizedId.isEmpty()) return false
        val existingMap = loadTaskMapMutable()
        val removed = existingMap.remove(normalizedId) != null
        cancelAlarm(normalizedId)
        persistTaskMap(existingMap)
        removeTaskFromFlutterStorage(normalizedId)
        return removed
    }

    fun listTasks(): List<Map<String, Any?>> {
        return loadTaskMapMutable().values.map(::taskPayload)
    }

    fun updateTask(rawTask: Map<String, Any?>): Map<String, Any?> {
        return upsertTask(rawTask)
    }

    fun syncTasks(rawTasks: List<Map<String, Any?>>): Map<String, Any?> {
        val existingMap = loadTaskMapMutable()
        val nextMap = mutableMapOf<String, StoredTask>()
        rawTasks.forEach { raw ->
            val taskId = raw["taskId"]?.toString()?.trim()
                ?.ifEmpty { null }
                ?: raw["id"]?.toString()?.trim().orEmpty()
            if (taskId.isEmpty()) return@forEach
            var task = parseTask(raw, existingMap[taskId])
            if (task.enabled) {
                task = task.copy(
                    nextExecutionTime = resolveNextExecutionAt(
                        task = task,
                        nowMillis = System.currentTimeMillis(),
                        preferExistingFuture = true
                    )
                )
                scheduleAlarm(task)
            } else {
                task = task.copy(nextExecutionTime = null)
                cancelAlarm(taskId)
            }
            nextMap[taskId] = task
        }

        existingMap.keys
            .filter { !nextMap.containsKey(it) }
            .forEach { removedId -> cancelAlarm(removedId) }

        persistTaskMap(nextMap)
        return mapOf(
            "count" to nextMap.size,
            "enabledCount" to nextMap.values.count { it.enabled }
        )
    }

    fun rescheduleAllEnabled() {
        val source = loadTaskMapMutable()
        if (source.isEmpty()) return
        val now = System.currentTimeMillis()
        val nextMap = mutableMapOf<String, StoredTask>()
        source.values.forEach { task ->
            if (!task.enabled) {
                cancelAlarm(task.taskId)
                nextMap[task.taskId] = task.copy(nextExecutionTime = null)
                return@forEach
            }
            val nextExecution = resolveNextExecutionAt(
                task = task,
                nowMillis = now,
                preferExistingFuture = true
            )
            if (nextExecution == null) {
                cancelAlarm(task.taskId)
                return@forEach
            }
            val normalized = task.copy(nextExecutionTime = nextExecution)
            nextMap[task.taskId] = normalized
            scheduleAlarm(normalized)
        }
        persistTaskMap(nextMap)
    }

    fun onAlarmTriggered(taskId: String) {
        val normalizedId = taskId.trim()
        if (normalizedId.isEmpty()) return
        val taskMap = loadTaskMapMutable()
        val current = taskMap[normalizedId] ?: return
        if (!current.enabled) {
            cancelAlarm(normalizedId)
            return
        }

        var executedTask = current
        runCatching {
            executedTask = executeTask(current)
        }.onFailure {
            OmniLog.e(TAG, "execute scheduled task failed: ${it.message}")
        }

        if (executedTask.repeatDaily && executedTask.enabled) {
            val nextExecution = resolveNextExecutionAt(
                task = executedTask,
                nowMillis = System.currentTimeMillis(),
                preferExistingFuture = false
            )
            val updated = executedTask.copy(nextExecutionTime = nextExecution)
            taskMap[normalizedId] = updated
            persistTaskMap(taskMap)
            upsertTaskInFlutterStorage(updated)
            if (nextExecution != null) {
                scheduleAlarm(updated)
            }
        } else {
            taskMap.remove(normalizedId)
            persistTaskMap(taskMap)
            cancelAlarm(normalizedId)
            removeTaskFromFlutterStorage(normalizedId)
        }
    }

    private fun executeTask(task: StoredTask): StoredTask {
        return executeSubagentTask(task)
    }

    private fun taskPayload(task: StoredTask): Map<String, Any?> = linkedMapOf(
        "taskId" to task.taskId,
        "title" to task.title,
        "targetKind" to task.targetKind,
        "scheduleType" to task.scheduleType,
        "fixedTime" to task.fixedTime,
        "countdownMinutes" to task.countdownMinutes,
        "repeatDaily" to task.repeatDaily,
        "enabled" to task.enabled,
        "nextExecutionTime" to task.nextExecutionTime,
        "subagentConversationId" to task.subagentConversationId,
        "parentConversationId" to task.parentConversationId,
        "parentConversationMode" to task.parentConversationMode,
        "subagentPrompt" to task.subagentPrompt,
        "notificationEnabled" to task.notificationEnabled,
    )

    private fun executeSubagentTask(task: StoredTask): StoredTask {
        val prompt = task.subagentPrompt?.trim().orEmpty()
        require(prompt.isNotEmpty()) { "subagentPrompt is empty" }
        val conversationId = createSubagentRunConversation(task)
        val args = mutableMapOf<String, Any?>(
            "taskId" to "subagent_schedule_${System.currentTimeMillis()}_${task.taskId}",
            "userMessage" to prompt,
            "conversationId" to conversationId,
            "conversationMode" to SUBAGENT_MODE,
            "scheduledTaskId" to task.taskId,
            "scheduledTaskTitle" to task.title,
            "scheduleNotificationEnabled" to task.notificationEnabled
        )
        runBlocking {
            agentRunBridge.startRun(
                taskId = args["taskId"].toString(),
                conversationId = conversationId,
                conversationMode = SUBAGENT_MODE,
                userMessage = prompt,
                attachments = emptyList(),
                cwd = null
            )
        }
        return task
    }

    private fun createSubagentRunConversation(task: StoredTask): Long {
        val now = System.currentTimeMillis()
        val parentConversationId = task.parentConversationId?.trim()?.toLongOrNull()
            ?: task.subagentConversationId?.trim()?.toLongOrNull()
        val parentConversationMode = task.parentConversationMode
            ?.trim()
            ?.ifEmpty { null }
        return runBlocking {
            DatabaseHelper.insertConversation(
                Conversation(
                    id = 0,
                    title = task.title.ifBlank { "SubAgent 定时任务" },
                    mode = SUBAGENT_MODE,
                    parentConversationId = parentConversationId?.takeIf { it > 0L },
                    parentConversationMode = parentConversationMode,
                    scheduledTaskId = task.taskId,
                    summary = null,
                    status = 0,
                    lastMessage = null,
                    messageCount = 0,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    private fun parseTask(rawTask: Map<String, Any?>, existing: StoredTask?): StoredTask {
        val taskId = rawTask["taskId"]?.toString()?.trim()
            ?.ifEmpty { null }
            ?: rawTask["id"]?.toString()?.trim().orEmpty()
        require(taskId.isNotEmpty()) { "taskId is empty" }

        val requestedTargetKind = rawTask["targetKind"]?.toString()?.trim().orEmpty()
            .ifEmpty { existing?.targetKind ?: SUBAGENT_MODE }
            .lowercase()
        require(requestedTargetKind == SUBAGENT_MODE) { "targetKind only supports subagent" }
        val scheduleType = normalizeScheduleType(
            rawTask["scheduleType"]?.toString()
                ?: rawTask["type"]?.toString()
                ?: existing?.scheduleType
        )
        val fixedTime = rawTask["fixedTime"]?.toString()?.trim()?.ifEmpty { null }
            ?: existing?.fixedTime
        val countdownMinutes = toInt(rawTask["countdownMinutes"]) ?: existing?.countdownMinutes
        val repeatDaily = (rawTask["repeatDaily"] as? Boolean) ?: existing?.repeatDaily ?: false
        val enabled = when (val value = rawTask["enabled"] ?: rawTask["isEnabled"]) {
            is Boolean -> value
            else -> existing?.enabled ?: true
        }
        val nextExecutionTime = toLong(rawTask["nextExecutionTime"]) ?: existing?.nextExecutionTime

        val title = rawTask["title"]?.toString()?.trim().orEmpty()
            .ifEmpty { existing?.title ?: "定时任务" }
        val subagentConversationId =
            rawTask["subagentConversationId"]?.toString()?.trim()?.ifEmpty { null }
                ?: existing?.subagentConversationId
        val parentConversationId =
            rawTask["parentConversationId"]?.toString()?.trim()?.ifEmpty { null }
                ?: rawTask["subagentParentConversationId"]?.toString()?.trim()?.ifEmpty { null }
                ?: existing?.parentConversationId
        val parentConversationMode =
            rawTask["parentConversationMode"]?.toString()?.trim()?.ifEmpty { null }
                ?: rawTask["subagentParentConversationMode"]?.toString()?.trim()?.ifEmpty { null }
                ?: existing?.parentConversationMode
        val subagentPrompt = rawTask["subagentPrompt"]?.toString()?.trim()?.ifEmpty { null }
            ?: existing?.subagentPrompt
        val notificationEnabled = (rawTask["notificationEnabled"] as? Boolean)
            ?: existing?.notificationEnabled
            ?: true

        return StoredTask(
            taskId = taskId,
            title = title,
            targetKind = SUBAGENT_MODE,
            scheduleType = scheduleType,
            fixedTime = fixedTime,
            countdownMinutes = countdownMinutes,
            repeatDaily = repeatDaily,
            enabled = enabled,
            nextExecutionTime = nextExecutionTime,
            subagentConversationId = subagentConversationId,
            parentConversationId = parentConversationId,
            parentConversationMode = parentConversationMode,
            subagentPrompt = subagentPrompt,
            notificationEnabled = notificationEnabled
        )
    }

    private fun loadFlutterScheduledTaskMaps(): MutableList<MutableMap<String, Any?>> {
        val flutterPrefs =
            appContext.getSharedPreferences(FLUTTER_PREFS_NAME, Context.MODE_PRIVATE)
        val raw = flutterPrefs.getString(FLUTTER_SCHEDULED_TASKS_KEY, null).orEmpty().trim()
        if (raw.isEmpty()) {
            val legacySet = flutterPrefs.getStringSet(FLUTTER_SCHEDULED_TASKS_KEY, null)
            if (legacySet.isNullOrEmpty()) {
                return mutableListOf()
            }
            return legacySet.mapNotNull { item ->
                runCatching { JSONObject(item) }.getOrNull()?.let { json ->
                    val map = mutableMapOf<String, Any?>()
                    json.keys().forEach { key ->
                        val value = json.opt(key)
                        map[key] = if (value == JSONObject.NULL) null else value
                    }
                    map
                }
            }.toMutableList()
        }

        val encodedList = when {
            raw.startsWith(JSON_LIST_PREFIX) -> {
                val jsonPayload = raw.removePrefix(JSON_LIST_PREFIX)
                val jsonArray = runCatching { JSONArray(jsonPayload) }.getOrElse { JSONArray() }
                buildList {
                    for (index in 0 until jsonArray.length()) {
                        add(jsonArray.optString(index))
                    }
                }
            }

            raw.startsWith(LIST_PREFIX) -> decodeLegacyStringList(raw.removePrefix(LIST_PREFIX))
            else -> emptyList()
        }
        return encodedList.mapNotNull { item ->
            runCatching { JSONObject(item) }.getOrNull()?.let { json ->
                val map = mutableMapOf<String, Any?>()
                json.keys().forEach { key ->
                    val value = json.opt(key)
                    map[key] = if (value == JSONObject.NULL) null else value
                }
                map
            }
        }.toMutableList()
    }

    private fun decodeLegacyStringList(encoded: String): List<String> {
        return runCatching {
            ObjectInputStream(ByteArrayInputStream(Base64.decode(encoded, Base64.DEFAULT))).use {
                @Suppress("UNCHECKED_CAST")
                (it.readObject() as? List<Any?>).orEmpty().mapNotNull { item -> item?.toString() }
            }
        }.getOrElse {
            OmniLog.w(TAG, "decode legacy flutter list failed: ${it.message}")
            emptyList()
        }
    }

    private fun writeFlutterScheduledTaskMaps(tasks: List<Map<String, Any?>>) {
        val flutterPrefs =
            appContext.getSharedPreferences(FLUTTER_PREFS_NAME, Context.MODE_PRIVATE)
        val rawList = tasks.map { task -> JSONObject(task).toString() }
        val payload = JSON_LIST_PREFIX + JSONArray(rawList).toString()
        flutterPrefs.edit().putString(FLUTTER_SCHEDULED_TASKS_KEY, payload).apply()
    }

    private fun upsertTaskInFlutterStorage(task: StoredTask) {
        val list = loadFlutterScheduledTaskMaps()
        val index = list.indexOfFirst { item ->
            val id = item["id"]?.toString()?.trim().orEmpty()
            id == task.taskId
        }
        val payload = if (index >= 0) {
            list[index].toMutableMap()
        } else {
            mutableMapOf()
        }
        payload["id"] = task.taskId
        payload["title"] = task.title
        payload["targetKind"] = task.targetKind
        payload["type"] = if (task.scheduleType == "countdown") "countdown" else "fixedTime"
        payload["fixedTime"] = task.fixedTime
        payload["countdownMinutes"] = task.countdownMinutes
        payload["repeatDaily"] = task.repeatDaily
        payload["isEnabled"] = task.enabled
        payload["nextExecutionTime"] = task.nextExecutionTime
        payload["subagentConversationId"] = task.subagentConversationId
        payload["parentConversationId"] = task.parentConversationId
        payload["parentConversationMode"] = task.parentConversationMode
        payload["subagentPrompt"] = task.subagentPrompt
        payload["notificationEnabled"] = task.notificationEnabled
        if (index >= 0) {
            list[index] = payload
        } else {
            list.add(payload)
        }
        writeFlutterScheduledTaskMaps(list)
    }

    private fun removeTaskFromFlutterStorage(taskId: String) {
        val list = loadFlutterScheduledTaskMaps()
        val next = list.filterNot { item ->
            item["id"]?.toString()?.trim().orEmpty() == taskId
        }
        writeFlutterScheduledTaskMaps(next)
    }

    private fun normalizeScheduleType(raw: String?): String {
        val normalized = raw?.trim().orEmpty()
        return when (normalized.lowercase()) {
            "countdown" -> "countdown"
            "fixed_time", "fixedtime", "fixed" -> "fixed_time"
            "scheduledtasktype.fixedtime" -> "fixed_time"
            "scheduledtasktype.countdown" -> "countdown"
            else -> "fixed_time"
        }
    }

    private fun resolveNextExecutionAt(
        task: StoredTask,
        nowMillis: Long,
        preferExistingFuture: Boolean
    ): Long? {
        if (!task.enabled) return null
        if (task.scheduleType == "countdown") {
            val existing = task.nextExecutionTime
            if (preferExistingFuture && existing != null && existing > nowMillis) {
                return existing
            }
            val minutes = task.countdownMinutes?.coerceAtLeast(1) ?: 1
            return nowMillis + minutes * 60_000L
        }

        val fixedTime = task.fixedTime?.trim().orEmpty()
        val parts = fixedTime.split(":")
        if (parts.size != 2) return nowMillis + 60_000L
        val hour = parts[0].toIntOrNull()?.coerceIn(0, 23) ?: return nowMillis + 60_000L
        val minute = parts[1].toIntOrNull()?.coerceIn(0, 59) ?: return nowMillis + 60_000L
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) {
            target = target.plusDays(1)
        }
        return target.atZone(zone).toInstant().toEpochMilli()
    }

    private fun scheduleAlarm(task: StoredTask) {
        val triggerAt = task.nextExecutionTime ?: return
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildPendingIntent(task.taskId)
        alarmManager.cancel(pendingIntent)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
        }.onFailure {
            OmniLog.w(TAG, "setExact failed, fallback set(): ${it.message}")
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun cancelAlarm(taskId: String) {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(buildPendingIntent(taskId))
    }

    private fun buildPendingIntent(taskId: String): PendingIntent {
        val intent = Intent(appContext, WorkspaceScheduledTaskReceiver::class.java).apply {
            action = ACTION_SCHEDULED_TASK_TRIGGER
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            appContext,
            ("workspace_scheduled_task_" + taskId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
    }

    private fun immutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    private fun loadTaskMapMutable(): MutableMap<String, StoredTask> {
        val raw = prefs.getString(KEY_TASKS_JSON, null).orEmpty().trim()
        if (raw.isEmpty()) return mutableMapOf()
        val list = runCatching {
            val type = object : TypeToken<List<StoredTask>>() {}.type
            gson.fromJson<List<StoredTask>>(raw, type)
        }.getOrElse {
            OmniLog.w(TAG, "parse scheduled tasks failed: ${it.message}")
            emptyList()
        }
        return list
            .filter {
                it.targetKind.equals(SUBAGENT_MODE, ignoreCase = true) &&
                    !it.subagentPrompt.isNullOrBlank()
            }
            .associateBy { it.taskId }
            .toMutableMap()
    }

    private fun persistTaskMap(tasks: Map<String, StoredTask>) {
        val payload = tasks.values.sortedBy { it.taskId }
        prefs.edit().putString(KEY_TASKS_JSON, gson.toJson(payload)).apply()
    }

    private fun toInt(value: Any?): Int? {
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Double -> value.toInt()
            is Float -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private fun toLong(value: Any?): Long? {
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Double -> value.toLong()
            is Float -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }
}
