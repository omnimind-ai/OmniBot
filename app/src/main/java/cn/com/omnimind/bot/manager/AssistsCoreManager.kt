package cn.com.omnimind.bot.manager

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.database.Conversation
import cn.com.omnimind.baselib.database.TokenUsageRecord
import cn.com.omnimind.baselib.http.Http429Exception
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import cn.com.omnimind.baselib.llm.AiRequestLogStore
import cn.com.omnimind.baselib.llm.DeepSeekProvider
import cn.com.omnimind.baselib.llm.ModelProviderConfig
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.llm.ProviderCustomHeaderUtils
import cn.com.omnimind.baselib.llm.OmniOfficialProvider
import cn.com.omnimind.baselib.llm.PlatformAiProvisioner
import cn.com.omnimind.baselib.llm.SceneModelCatalogResolver
import cn.com.omnimind.baselib.llm.SceneCatalogItem
import cn.com.omnimind.baselib.llm.SceneModelBindingEntry
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.llm.SceneModelOverrideEntry
import cn.com.omnimind.baselib.llm.SceneModelOverrideStore
import cn.com.omnimind.baselib.llm.SceneOperationConfig
import cn.com.omnimind.baselib.llm.SceneOperationConfigStore
import cn.com.omnimind.baselib.llm.SceneVoiceConfig
import cn.com.omnimind.baselib.llm.SceneVoiceConfigStore
import cn.com.omnimind.baselib.util.APPPackageUtil
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.RuntimeLogStore
import cn.com.omnimind.baselib.util.exception.PermissionException
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.activity.MainActivity
import cn.com.omnimind.bot.ui.scheduled.ScheduledTaskReminderLoader
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.util.SchemeUtil
import cn.com.omnimind.bot.util.TaskRuntimeSettings
import cn.com.omnimind.bot.agent.AgentAlarmToolService
import cn.com.omnimind.bot.agent.AgentImageAttachmentSupport
import cn.com.omnimind.bot.agent.AgentWorkspaceAttachmentSupport
import cn.com.omnimind.bot.agent.AgentTextSanitizer
import cn.com.omnimind.bot.agent.AgentModelOverride
import cn.com.omnimind.bot.agent.AgentRuntimeErrorSupport
import cn.com.omnimind.bot.agent.resolveAgentPermissionIds
import cn.com.omnimind.bot.agent.AgentConversationHistoryRepository
import cn.com.omnimind.bot.agent.AgentConversationHistorySupport
import cn.com.omnimind.bot.agent.AgentRuntimeContextRepository
import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import cn.com.omnimind.bot.agent.LiveAgentBrowserSessionManager
import cn.com.omnimind.bot.agent.SkillIndexEntry
import cn.com.omnimind.bot.agent.SkillIndexService
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.WorkspaceMemoryRollupScheduler
import cn.com.omnimind.bot.agent.WorkspaceMemoryService
import cn.com.omnimind.bot.agent.WorkspaceScheduledTaskScheduler
import cn.com.omnimind.bot.agent.resolveToolExecutionStatus
import cn.com.omnimind.bot.mcp.RemoteMcpConfigStore
import cn.com.omnimind.bot.quicklog.QuickLogService
import cn.com.omnimind.bot.util.TaskCompletionNavigator
import cn.com.omnimind.bot.webchat.ConversationDomainService
import cn.com.omnimind.bot.webchat.FlutterChatSyncBridge
import cn.com.omnimind.bot.workspace.PublicStorageAccess
import cn.com.omnimind.bot.workspace.WorkspaceStorageAccess
import cn.com.omnimind.uikit.UIKit
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import kotlin.collections.mapOf
import kotlin.coroutines.resume

private const val MAX_PERSISTED_THINKING_CHARS = 16 * 1024
private const val THINKING_TRUNCATION_NOTICE = "[Earlier reasoning omitted]\n"

internal fun resolveDirectAgentModelOverride(
    raw: Map<String, Any?>?,
    profileLookup: (String) -> ModelProviderProfile?
): AgentModelOverride? {
    if (raw.isNullOrEmpty()) {
        return null
    }
    val providerProfileId = raw["providerProfileId"]?.toString()?.trim().orEmpty()
    val modelId = raw["modelId"]?.toString()?.trim().orEmpty()
    if (providerProfileId.isEmpty() || modelId.isEmpty()) {
        return null
    }
    val providerProfile = profileLookup(providerProfileId)
    if (providerProfile == null || !providerProfile.isConfigured()) {
        return null
    }
    val contextLimit = when (val rawContextLimit = raw["contextLimit"]) {
        is Number -> rawContextLimit.toInt()
        else -> rawContextLimit?.toString()?.trim()?.toIntOrNull()
    }?.takeIf { it > 0 }
    return AgentModelOverride.fromProviderProfile(
        profile = providerProfile,
        modelId = modelId,
        contextLimit = contextLimit,
    )
}

internal fun normalizeReasoningEffort(raw: String?): String? {
    val normalized = raw?.trim()?.lowercase().orEmpty()
    return when (normalized) {
        "no", "low", "high", "xhigh", "max" -> normalized
        else -> null
    }
}

internal fun resolveAgentReasoningEffort(
    reasoningEffort: String?,
    modelOverride: AgentModelOverride?,
    fallbackProfile: ModelProviderProfile? = runCatching {
        ModelProviderConfigStore.getEditingProfile()
    }.getOrNull()
): String? {
    if (!reasoningEffort.isNullOrBlank()) {
        return reasoningEffort
    }
    val useOfficialDeepSeekDefault = if (modelOverride != null) {
        DeepSeekProvider.shouldUseOfficialAdapter(
            protocolType = modelOverride.protocolType,
            apiBase = modelOverride.apiBase
        )
    } else {
        DeepSeekProvider.shouldUseOfficialAdapter(
            protocolType = fallbackProfile?.protocolType,
            apiBase = fallbackProfile?.baseUrl
        )
    }
    return if (useOfficialDeepSeekDefault) "max" else null
}

internal data class AgentFinalErrorResolution(
    val text: String,
    val persistAsError: Boolean
)

internal fun resolveAgentFinalErrorResolution(
    streamed: String,
    error: String,
    localizedFallback: String
): AgentFinalErrorResolution {
    val normalizedStreamed = AgentTextSanitizer.sanitizeUtf16(streamed).trim()
    if (normalizedStreamed.isNotEmpty()) {
        return AgentFinalErrorResolution(
            text = normalizedStreamed,
            persistAsError = false
        )
    }

    val normalizedError = AgentTextSanitizer.sanitizeUtf16(error).trim()
    val finalText = normalizedError.ifEmpty {
        AgentTextSanitizer.sanitizeUtf16(localizedFallback).trim()
    }
    return AgentFinalErrorResolution(
        text = finalText,
        persistAsError = finalText.isNotEmpty()
    )
}

private fun sanitizeInteropValue(value: Any?): Any? {
    return when (value) {
        null -> null
        is String -> AgentTextSanitizer.sanitizeUtf16(value)
        is Map<*, *> -> linkedMapOf<String, Any?>().apply {
            value.forEach { (key, item) ->
                if (key != null) {
                    put(key.toString(), sanitizeInteropValue(item))
                }
            }
        }
        is List<*> -> value.map(::sanitizeInteropValue)
        else -> value
    }
}

private fun sanitizeInteropMap(payload: Map<String, Any?>): Map<String, Any?> {
    return linkedMapOf<String, Any?>().apply {
        payload.forEach { (key, value) ->
            put(key, sanitizeInteropValue(value))
        }
    }
}

private fun extractTextPayload(raw: JsonElement?): String {
    return when (raw) {
        null, JsonNull -> ""
        is JsonPrimitive -> raw.contentOrNull.orEmpty()
        is JsonArray -> raw.joinToString(separator = "") { item ->
            extractTextPayload(item)
        }
        is JsonObject -> when {
            raw["type"]?.jsonPrimitive?.contentOrNull.equals("text", ignoreCase = true) ||
                raw["type"]?.jsonPrimitive?.contentOrNull.equals("output_text", ignoreCase = true) -> {
                extractTextPayload(raw["text"])
            }
            raw.containsKey("text") -> extractTextPayload(raw["text"])
            raw.containsKey("content") -> extractTextPayload(raw["content"])
            else -> ""
        }
        else -> ""
    }
}

class AssistsCoreManager(private val context: Context) {
    private val TAG = "[AssistsCoreManager]"

    private fun lookupRuntimeProviderProfile(profileId: String): ModelProviderProfile? =
        ModelProviderConfigStore.getProfile(profileId)
            ?: PlatformAiProvisioner.officialProfileOrNull()
                ?.takeIf { OmniOfficialProvider.isOfficialProfile(profileId) }

    companion object {
        private const val SUMMARY_TASK_PREFIX_TASK = "task-summary-"
        private const val MEMORY_GREETING_TOOL = "submit_memory_greeting"
        private const val DEFAULT_MEMORY_GREETING = "愿你今天也有温暖收获"
        private const val SUBAGENT_MODE = "subagent"
        private const val SCHEDULED_SUBAGENT_NOTIFICATION_CHANNEL =
            "scheduled_subagent_tasks_v1"

        @Volatile
        private var mainEngineChannel: MethodChannel? = null

        @Volatile
        private var sharedInstance: AssistsCoreManager? = null

        fun bindMainEngineChannel(channel: MethodChannel) {
            mainEngineChannel = channel
            FlutterChatSyncBridge.bindMainChannel(channel)
        }

        private fun registerSharedInstance(instance: AssistsCoreManager) {
            sharedInstance = instance
        }

        fun sharedInstanceOrCreate(context: Context): AssistsCoreManager {
            val existing = sharedInstance
            if (existing != null) {
                return existing
            }
            return synchronized(this) {
                sharedInstance ?: AssistsCoreManager(context.applicationContext).also {
                    sharedInstance = it
                }
            }
        }

        private fun isSummaryTask(taskId: String): Boolean {
            return taskId.startsWith(SUMMARY_TASK_PREFIX_TASK)
        }
    }

    init {
        registerSharedInstance(this)
    }

    private fun currentLocale(): PromptLocale = AppLocaleManager.resolvePromptLocale(context)

    private fun t(zh: String, en: String): String {
        return when (currentLocale()) {
            PromptLocale.ZH_CN -> zh
            PromptLocale.EN_US -> en
        }
    }

    private fun defaultMemoryGreeting(): String =
        t("愿你今天也有温暖收获", "Hope today brings you something warm and worthwhile.")

    private fun localizedPermissionName(name: String): String {
        val trimmed = name.trim()
        return when (trimmed) {
            "悬浮窗权限", "Overlay", "Overlay Permission" ->
                t("悬浮窗权限", "Overlay")
            "应用列表读取权限", "Installed Apps Access", "Installed Apps Permission" ->
                t("应用列表读取权限", "Installed Apps Access")
            "Shizuku 权限", "Shizuku Permission" ->
                t("Shizuku 权限", "Shizuku Permission")
            "公共文件访问", "Public Storage Access" ->
                t("公共文件访问", "Public Storage Access")
            else -> trimmed
        }
    }

    private data class ScheduledSubagentRunMeta(
        val scheduleTaskId: String,
        val scheduleTaskTitle: String,
        val notificationEnabled: Boolean,
        val conversationId: Long
    )

    // 用于存储需要等待用户操作的回调结果
    private lateinit var channel: MethodChannel
    private var mainJob: CoroutineScope = CoroutineScope(Dispatchers.Main)
    private var workJob: CoroutineScope = CoroutineScope(Dispatchers.Default)
    private val conversationDomainService by lazy { ConversationDomainService(context) }

    // 当前活跃的对话ID
    private var currentConversationId: Long? = null
    private var currentConversationMode: String = "agent"

    private fun ModelProviderConfig.toMap(): Map<String, Any?> {
        val official = OmniOfficialProvider.isOfficialProfile(id)
        return mapOf(
            "id" to id,
            "name" to name,
            "baseUrl" to if (official) "" else baseUrl,
            "apiKey" to if (official) "" else apiKey,
            "customHeaders" to emptyMap<String, String>(),
            "hasApiKey" to (!official && apiKey.isNotBlank()),
            "hasCustomHeaders" to (!official && customHeaders.isNotEmpty()),
            "source" to source,
            "providerType" to providerType,
            "readOnly" to readOnly,
            "ready" to ready,
            "statusText" to statusText,
            "configured" to isConfigured(),
            "wireApi" to wireApi,
        )
    }

    private fun ModelProviderProfile.toMap(): Map<String, Any?> {
        val official = OmniOfficialProvider.isOfficialProfile(id)
        return mapOf(
            "id" to id,
            "name" to name,
            "baseUrl" to if (official) "" else baseUrl,
            "apiKey" to if (official) "" else apiKey,
            "customHeaders" to emptyMap<String, String>(),
            "hasApiKey" to (!official && apiKey.isNotBlank()),
            "hasCustomHeaders" to (!official && customHeaders.isNotEmpty()),
            "sourceType" to sourceType,
            "readOnly" to readOnly,
            "ready" to ready,
            "statusText" to statusText,
            "configured" to isConfigured(),
            "protocolType" to protocolType,
            "wireApi" to wireApi,
            "revision" to revision,
        )
    }

    private fun ProviderModelOption.toMap(): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "displayName" to displayName,
            "ownedBy" to ownedBy,
            "contextLimit" to contextLimit,
            "inputLimit" to inputLimit,
            "outputLimit" to outputLimit,
            "inputModalities" to inputModalities,
            "outputModalities" to outputModalities,
            "modelsDevProviderId" to modelsDevProviderId,
            "modelsDevProviderName" to modelsDevProviderName,
            "providerLogoUrl" to providerLogoUrl,
            "family" to family,
            "group" to group,
            "attachment" to attachment,
            "reasoning" to reasoning,
            "toolCall" to toolCall,
            "structuredOutput" to structuredOutput,
            "temperature" to temperature
        )
    }

    private fun SceneCatalogItem.toMap(): Map<String, Any?> {
        return mapOf(
            "sceneId" to sceneId,
            "description" to description,
            "defaultModel" to defaultModel,
            "effectiveModel" to effectiveModel,
            "effectiveProviderProfileId" to effectiveProviderProfileId,
            "effectiveProviderProfileName" to effectiveProviderProfileName,
            "boundProviderProfileId" to boundProviderProfileId,
            "boundProviderProfileName" to boundProviderProfileName,
            "transport" to transport,
            "configSource" to configSource,
            "overrideApplied" to overrideApplied,
            "overrideModel" to overrideModel,
            "providerConfigured" to providerConfigured,
            "bindingExists" to bindingExists,
            "bindingProfileMissing" to bindingProfileMissing
        )
    }

    private fun SceneModelOverrideEntry.toMap(): Map<String, Any?> {
        return mapOf(
            "sceneId" to sceneId,
            "model" to model
        )
    }

    private fun SceneModelBindingEntry.toMap(): Map<String, Any?> {
        return mapOf(
            "sceneId" to sceneId,
            "providerProfileId" to providerProfileId,
            "modelId" to modelId
        )
    }

    private fun SceneOperationConfig.toMap(): Map<String, Any?> {
        return mapOf("useOfficialService" to useOfficialService)
    }

    private fun SceneVoiceConfig.toMap(): Map<String, Any?> {
        return mapOf(
            "autoPlay" to autoPlay,
            "voiceId" to voiceId,
            "stylePreset" to stylePreset,
            "customStyle" to customStyle,
            "ttsMode" to ttsMode,
            // Never expose the command itself over the Flutter bridge.
            "hasCustomCurlCommand" to customCurlCommand.isNotBlank(),
        )
    }

    fun setChannel(_channel: MethodChannel) {
        OmniLog.d(TAG, "setChannel")
        this.channel = _channel
        FlutterChatSyncBridge.bindCurrentChannel(_channel)
    }

    private fun toStringAnyMap(value: Any?): Map<String, Any?> {
        return (value as? Map<*, *>)?.entries?.associate { (key, rawValue) ->
            key.toString() to normalizeChannelValue(rawValue)
        } ?: emptyMap()
    }

    private fun toListOfStringAnyMap(value: Any?): List<Map<String, Any?>> {
        return (value as? List<*>)?.map { toStringAnyMap(it) } ?: emptyList()
    }

    private fun normalizeChannelValue(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> toStringAnyMap(value)
            is List<*> -> value.map { normalizeChannelValue(it) }
            else -> value
        }
    }

    private data class AgentToolMeta(
        val toolType: String,
        val displayName: String,
        val serverName: String? = null
    )

    private fun resolveAgentToolMeta(toolName: String): AgentToolMeta {
        return when (toolName) {
            "context_apps_query" -> AgentToolMeta("builtin", t("查询已安装应用", "Query Installed Apps"))
            "context_time_now" -> AgentToolMeta("builtin", t("查询当前时间", "Query Current Time"))
            "browser_use" -> AgentToolMeta("browser", t("浏览器操作", "Browser Action"))
            "android_privileged_action" -> AgentToolMeta("privileged", t("安卓高级动作", "Android Privileged Action"))
            "android_privileged_session_start" -> AgentToolMeta("privileged", t("启动高权限会话", "Start Privileged Session"))
            "android_privileged_session_exec" -> AgentToolMeta("privileged", t("执行高权限命令", "Run Privileged Command"))
            "android_privileged_session_read" -> AgentToolMeta("privileged", t("读取高权限输出", "Read Privileged Output"))
            "android_privileged_session_stop" -> AgentToolMeta("privileged", t("结束高权限会话", "Stop Privileged Session"))
            "terminal_execute" -> AgentToolMeta("terminal", t("终端执行", "Run Terminal Command"))
            "terminal_session_start" -> AgentToolMeta("terminal", t("启动终端会话", "Start Terminal Session"))
            "terminal_session_exec" -> AgentToolMeta("terminal", t("执行会话命令", "Run Session Command"))
            "terminal_session_read" -> AgentToolMeta("terminal", t("读取会话输出", "Read Session Output"))
            "terminal_session_stop" -> AgentToolMeta("terminal", t("结束终端会话", "Stop Terminal Session"))
            "file_read" -> AgentToolMeta("workspace", t("读取文件", "Read File"))
            "file_write" -> AgentToolMeta("workspace", t("写入文件", "Write File"))
            "file_edit" -> AgentToolMeta("workspace", t("编辑文件", "Edit File"))
            "file_list" -> AgentToolMeta("workspace", t("列出文件", "List Files"))
            "file_search" -> AgentToolMeta("workspace", t("搜索文件", "Search Files"))
            "file_stat" -> AgentToolMeta("workspace", t("查看文件信息", "Inspect File"))
            "file_move" -> AgentToolMeta("workspace", t("移动文件", "Move File"))
            "schedule_task_create" -> AgentToolMeta("schedule", t("创建定时任务", "Create Scheduled Task"))
            "schedule_task_list" -> AgentToolMeta("schedule", t("查看定时任务", "List Scheduled Tasks"))
            "schedule_task_update" -> AgentToolMeta("schedule", t("修改定时任务", "Update Scheduled Task"))
            "schedule_task_delete" -> AgentToolMeta("schedule", t("删除定时任务", "Delete Scheduled Task"))
            "alarm_reminder_create" -> AgentToolMeta("alarm", t("创建提醒闹钟", "Create Reminder Alarm"))
            "alarm_reminder_list" -> AgentToolMeta("alarm", t("查看提醒闹钟", "List Reminder Alarms"))
            "alarm_reminder_delete" -> AgentToolMeta("alarm", t("删除提醒闹钟", "Delete Reminder Alarm"))
            "calendar_list" -> AgentToolMeta("calendar", t("查看日历列表", "List Calendars"))
            "calendar_event_create" -> AgentToolMeta("calendar", t("创建日程", "Create Calendar Event"))
            "calendar_event_list" -> AgentToolMeta("calendar", t("查询日程", "List Calendar Events"))
            "calendar_event_update" -> AgentToolMeta("calendar", t("修改日程", "Update Calendar Event"))
            "calendar_event_delete" -> AgentToolMeta("calendar", t("删除日程", "Delete Calendar Event"))
            "memory_search" -> AgentToolMeta("memory", t("检索记忆", "Search Memory"))
            "memory_write_daily" -> AgentToolMeta("memory", t("写入当日记忆", "Write Daily Memory"))
            "memory_upsert_longterm" -> AgentToolMeta("memory", t("沉淀长期记忆", "Upsert Long-Term Memory"))
            "memory_rollup_day" -> AgentToolMeta("memory", t("整理当日记忆", "Roll Up Daily Memory"))
            "subagent_dispatch" -> AgentToolMeta("subagent", t("分派子任务", "Dispatch Subtasks"))
            else -> {
                val match = Regex("^mcp__(.+?)__(.+)$").find(toolName)
                if (match != null) {
                    val serverId = match.groupValues[1]
                    val rawToolName = match.groupValues[2]
                    val serverName = RemoteMcpConfigStore.getServer(serverId)?.name
                    AgentToolMeta("mcp", rawToolName, serverName)
                } else {
                    AgentToolMeta("builtin", toolName)
                }
            }
        }
    }

    private fun buildToolStartPayload(toolName: String, argsJson: String): Map<String, Any?> {
        val meta = resolveAgentToolMeta(toolName)
        return linkedMapOf<String, Any?>(
            "toolName" to toolName,
            "displayName" to meta.displayName,
            "toolType" to meta.toolType,
            "serverName" to meta.serverName,
            "args" to argsJson,
            "argsJson" to argsJson
        ).apply {
            extractToolTitle(argsJson)?.let { toolTitle ->
                put("toolTitle", toolTitle)
                put("summary", toolTitle)
            }
        }
    }

    private fun extractToolTitle(argsJson: String): String? {
        if (argsJson.isBlank()) return null
        return runCatching {
            JSONObject(argsJson).optString("tool_title").trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun buildToolProgressPayload(
        toolName: String,
        progress: String,
        argsJson: String = "",
        extras: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> {
        val meta = resolveAgentToolMeta(toolName)
        val payload = linkedMapOf<String, Any?>(
            "toolName" to toolName,
            "displayName" to meta.displayName,
            "toolType" to meta.toolType,
            "serverName" to meta.serverName,
            "progress" to progress,
            "args" to argsJson,
            "argsJson" to argsJson
        )
        extractToolTitle(argsJson)?.let { toolTitle ->
            payload["toolTitle"] = toolTitle
            if ((payload["summary"]?.toString() ?: "").isBlank()) {
                payload["summary"] = toolTitle
            }
        }
        payload.putAll(extras)
        return payload
    }

    private fun buildToolCompletePayload(
        toolName: String,
        result: ToolExecutionResult,
        argsJson: String = ""
    ): Map<String, Any?> {
        val meta = resolveAgentToolMeta(toolName)
        val summary: String
        val previewJson: String
        val rawResultJson: String
        val success: Boolean
        val status: String
        var interruptedBy: String? = null
        var interruptionReason: String? = null
        when (result) {
            is ToolExecutionResult.ChatMessage -> {
                summary = result.message
                previewJson = JSONObject(mapOf("message" to result.message)).toString()
                rawResultJson = previewJson
                success = true
                status = "success"
            }
            is ToolExecutionResult.Clarify -> {
                summary = result.question
                previewJson = JSONObject(
                    mapOf(
                        "question" to result.question,
                        "missingFields" to (result.missingFields ?: emptyList<String>())
                    )
                ).toString()
                rawResultJson = previewJson
                success = true
                status = AgentConversationHistoryRepository.STATUS_RUNNING
            }
            is ToolExecutionResult.PermissionRequired -> {
                val names = result.missing.map(::localizedPermissionName)
                summary = t(
                    "缺少权限：${names.joinToString("、")}",
                    "Missing permissions: ${names.joinToString(", ")}"
                )
                previewJson = JSONObject(mapOf("missing" to names)).toString()
                rawResultJson = previewJson
                success = false
                status = "interrupted"
            }
            is ToolExecutionResult.ScheduleResult -> {
                summary = result.summaryText
                previewJson = result.previewJson
                rawResultJson = result.previewJson
                success = result.success
                status = if (result.success) "success" else "error"
            }
            is ToolExecutionResult.McpResult -> {
                summary = result.summaryText
                previewJson = result.previewJson
                rawResultJson = result.rawResultJson
                success = result.success
                status = if (result.success) "success" else "error"
            }
            is ToolExecutionResult.MemoryResult -> {
                summary = result.summaryText
                previewJson = result.previewJson
                rawResultJson = result.rawResultJson
                success = result.success
                status = if (result.success) "success" else "error"
            }
            is ToolExecutionResult.TerminalResult -> {
                summary = result.summaryText
                previewJson = result.previewJson
                rawResultJson = result.rawResultJson
                success = result.success
                status = resolveToolExecutionStatus(result)
            }
            is ToolExecutionResult.Interrupted -> {
                summary = result.summaryText
                previewJson = result.previewJson
                rawResultJson = result.rawResultJson
                success = false
                status = "interrupted"
                interruptedBy = result.interruptedBy
                interruptionReason = result.interruptionReason
            }
            is ToolExecutionResult.ContextResult -> {
                summary = result.summaryText
                previewJson = result.previewJson
                rawResultJson = result.rawResultJson
                success = result.success
                status = if (result.success) "success" else "error"
            }
            is ToolExecutionResult.Error -> {
                summary = result.message
                previewJson = JSONObject(
                    mapOf("toolName" to result.toolName, "message" to result.message)
                ).toString()
                rawResultJson = previewJson
                success = false
                status = "error"
            }
        }

        val payload = linkedMapOf<String, Any?>(
            "toolName" to toolName,
            "displayName" to meta.displayName,
            "toolType" to meta.toolType,
            "serverName" to meta.serverName,
            "status" to status,
            "summary" to summary,
            "args" to argsJson,
            "argsJson" to argsJson,
            "resultPreviewJson" to previewJson,
            "rawResultJson" to rawResultJson,
            "success" to success
        )
        extractToolTitle(argsJson)?.let { payload["toolTitle"] = it }
        if (result is ToolExecutionResult.TerminalResult) {
            payload["timedOut"] = result.timedOut
            payload["terminalOutput"] = result.terminalOutput
            payload["terminalSessionId"] = result.terminalSessionId
            payload["terminalStreamState"] = result.terminalStreamState
        }
        if (result is ToolExecutionResult.Interrupted) {
            payload["interruptedBy"] = interruptedBy
            payload["interruptionReason"] = interruptionReason
            payload["terminalOutput"] = result.terminalOutput
            payload["terminalSessionId"] = result.terminalSessionId
            payload["terminalStreamState"] = result.terminalStreamState
        }
        if (result.artifacts.isNotEmpty()) {
            payload["artifacts"] = result.artifacts.map { it.toPayload() }
        }
        result.workspaceId?.let { payload["workspaceId"] = it }
        if (result.actions.isNotEmpty()) {
            payload["actions"] = result.actions.map { it.toPayload() }
        }
        return payload
    }

    private fun conversationHistoryRepository(): AgentConversationHistoryRepository {
        return AgentConversationHistoryRepository(context)
    }

    private fun normalizeConversationMode(mode: String?): String {
        return when (mode?.trim()?.lowercase()) {
            null, "", "normal", "codex", "acp", "coding" -> "agent"
            "chat", "chatonly", "chat-only" -> "chat_only"
            else -> mode.trim().lowercase()
        }
    }

    private fun resolveRequiredPermissionIds(missing: List<String>): List<String> {
        return resolveAgentPermissionIds(missing)
    }

    private fun buildPermissionCardData(requiredPermissionIds: List<String>): Map<String, Any?> {
        return linkedMapOf(
            "type" to "permission_section",
            "requiredPermissionIds" to requiredPermissionIds
        )
    }

    /**
     * 获取已安装应用（包名与应用名）
     */
    fun getInstalledApplications(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val pm = context.packageManager
                val applications = pm.getInstalledApplications(0)
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .sortedBy { pm.getApplicationLabel(it).toString() }

                val list = applications.map { appInfo ->
                    mapOf(
                        "package_name" to appInfo.packageName,
                        "app_name" to pm.getApplicationLabel(appInfo).toString()
                    )
                }
                OmniLog.v(TAG, "getInstalledApplications size=${list.size}")

                withContext(Dispatchers.Main) {
                    result.success(list)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "获取已安装应用失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_INSTALLED_APPS_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 获取已安装应用（包名与应用名，附带图标更新）
     */
    fun getInstalledApplicationsWithIconUpdate(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val pm = context.packageManager
                val applications = pm.getInstalledApplications(0)
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .sortedBy { pm.getApplicationLabel(it).toString() }

                val list = applications.map { appInfo ->
                    val packageName = appInfo.packageName
                    val appName = pm.getApplicationLabel(appInfo).toString()
                    var iconPath = ""

                    // 查询数据库中是否已有该应用的图标
                    var appIcon = DatabaseHelper.getAppIconByPackageName(packageName)

                    // 如果数据库中没有图标，则获取并保存
                    if (appIcon == null && appName.isNotEmpty()) {
                        val iconBase64 = APPPackageUtil.getAppIconBase64(context, packageName)
                        iconPath = APPPackageUtil.getAppIconFilePath(context, packageName)

                        if (iconBase64.isNotEmpty()) {
                            DatabaseHelper.insertAppIcon(
                                appName = appName,
                                packageName = packageName,
                                iconBase64 = iconBase64,
                                iconPath = iconPath
                            )
                        }
                    }

                    mapOf(
                        "package_name" to packageName,
                        "app_name" to appName,
                        "app_icon" to iconPath
                    )
                }
                OmniLog.v(TAG, "getInstalledApplications size=${list.size}")

                withContext(Dispatchers.Main) {
                    result.success(list)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "获取已安装应用失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_INSTALLED_APPS_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 查询统一 Agent 创建的 exact alarm 提醒列表
     */
    fun listAgentExactAlarms(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val alarms = AgentAlarmToolService(context).listExactReminders()
                withContext(Dispatchers.Main) {
                    result.success(alarms)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "listAgentExactAlarms error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("LIST_AGENT_EXACT_ALARMS_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 删除统一 Agent 创建的 exact alarm 提醒
     */
    fun deleteAgentExactAlarm(call: MethodCall, result: MethodChannel.Result) {
        val alarmId = call.argument<String>("alarmId")?.trim().orEmpty()
        if (alarmId.isEmpty()) {
            result.error("INVALID_ARGUMENTS", "alarmId is empty", null)
            return
        }
        workJob.launch {
            try {
                val payload = AgentAlarmToolService(context).deleteExactReminder(alarmId)
                withContext(Dispatchers.Main) {
                    result.success(payload)
                }
            } catch (e: IllegalArgumentException) {
                OmniLog.e(TAG, "deleteAgentExactAlarm not found: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("AGENT_EXACT_ALARM_NOT_FOUND", e.message, null)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "deleteAgentExactAlarm error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("DELETE_AGENT_EXACT_ALARM_ERROR", e.message, null)
                }
            }
        }
    }

    fun getAlarmSettings(call: MethodCall, result: MethodChannel.Result) {
        try {
            val payload = AgentAlarmToolService(context).getAlarmSettings()
            result.success(payload)
        } catch (e: Exception) {
            OmniLog.e(TAG, "getAlarmSettings error: ${e.message}")
            result.error("GET_ALARM_SETTINGS_ERROR", e.message, null)
        }
    }

    fun saveAlarmSettings(call: MethodCall, result: MethodChannel.Result) {
        try {
            val source = call.argument<String>("source")?.trim().orEmpty()
            if (source.isEmpty()) {
                result.error("INVALID_ARGUMENTS", "source is empty", null)
                return
            }
            val localPath = call.argument<String>("localPath")
            val remoteUrl = call.argument<String>("remoteUrl")
            val payload = AgentAlarmToolService(context).saveAlarmSettings(
                source = source,
                localPath = localPath,
                remoteUrl = remoteUrl
            )
            result.success(payload)
        } catch (e: IllegalArgumentException) {
            OmniLog.e(TAG, "saveAlarmSettings invalid: ${e.message}")
            result.error("INVALID_ARGUMENTS", e.message, null)
        } catch (e: Exception) {
            OmniLog.e(TAG, "saveAlarmSettings error: ${e.message}")
            result.error("SAVE_ALARM_SETTINGS_ERROR", e.message, null)
        }
    }

    /**
     * 显示定时任务执行前提醒（支持取消/立即执行）
     */
    fun showScheduledTaskReminder(call: MethodCall, result: MethodChannel.Result) {
        val taskId = call.argument<String>("taskId")?.trim().orEmpty()
        val taskName = call.argument<String>("taskName")?.trim().orEmpty()
        val countdownSeconds = call.argument<Int>("countdownSeconds") ?: 5

        if (taskId.isEmpty()) {
            result.error("INVALID_ARGUMENTS", "taskId is empty", null)
            return
        }
        if (taskName.isEmpty()) {
            result.error("INVALID_ARGUMENTS", "taskName is empty", null)
            return
        }

        mainJob.launch(Dispatchers.Main) {
            try {
                val success = ScheduledTaskReminderLoader.show(
                    taskId = taskId,
                    taskName = taskName,
                    countdownSeconds = countdownSeconds,
                    onCancel = { id ->
                        notifyScheduledTaskEvent("onScheduledTaskCancelled", id)
                    },
                    onExecuteNow = { id ->
                        notifyScheduledTaskEvent("onScheduledTaskExecuteNow", id)
                    }
                )
                if (success) {
                    result.success("SUCCESS")
                } else {
                    result.error("OVERLAY_NOT_READY", "Scheduled task overlay is not ready", null)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "showScheduledTaskReminder failed: ${e.message}")
                result.error("SHOW_SCHEDULED_TASK_REMINDER_ERROR", e.message, null)
            }
        }
    }

    /**
     * 隐藏定时任务提醒
     */
    fun hideScheduledTaskReminder(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch(Dispatchers.Main) {
            try {
                ScheduledTaskReminderLoader.hide()
                result.success("SUCCESS")
            } catch (e: Exception) {
                OmniLog.e(TAG, "hideScheduledTaskReminder failed: ${e.message}")
                result.error("HIDE_SCHEDULED_TASK_REMINDER_ERROR", e.message, null)
            }
        }
    }

    private fun notifyScheduledTaskEvent(method: String, taskId: String) {
        mainJob.launch(Dispatchers.Main) {
            val payload = mapOf("taskId" to taskId)
            try {
                channel.invokeMethod(method, payload)
            } catch (e: Exception) {
                OmniLog.e(TAG, "notifyScheduledTaskEvent via current channel failed: ${e.message}")
                try {
                    val mainChannel = mainEngineChannel
                    if (mainChannel != null && mainChannel != channel) {
                        mainChannel.invokeMethod(method, payload)
                    }
                } catch (fallbackError: Exception) {
                    OmniLog.e(TAG, "notifyScheduledTaskEvent fallback failed: ${fallbackError.message}")
                }
            }
        }
    }

    fun copyToClipboard(call: MethodCall, result: MethodChannel.Result) {
        try {
            val text = call.argument<String>("text") ?: ""
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("label", text)
            clipboard.setPrimaryClip(clip)
            mainJob.launch(Dispatchers.Main) {
                result.success("SUCCESS")
            }
        } catch (e: Exception) {
            mainJob.launch(Dispatchers.Main) {
                result.error("COPY_TO_CLIPBOARD_ERROR", e.message, null)
            }
        }
    }

    fun getClipboardText(call: MethodCall, result: MethodChannel.Result) {
        try {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            val text = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(context)?.toString() ?: ""
            } else {
                ""
            }
            mainJob.launch(Dispatchers.Main) {
                result.success(text)
            }
        } catch (e: Exception) {
            mainJob.launch(Dispatchers.Main) {
                result.error("GET_CLIPBOARD_ERROR", e.message, null)
            }
        }
    }

    /**
     * 调用LLM chat接口（非流式）
     * 用于修复JSON格式等场景
     */
    fun postLLMChat(call: MethodCall, result: MethodChannel.Result) {
        val text = call.argument<String>("text") ?: ""
        val model = call.argument<String>("model") ?: "scene.dispatch.model"

        workJob.launch {
            try {
                val response = HttpController.postLLMRequest(model, text)

                withContext(Dispatchers.Main) {
                    result.success(response.message)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "postLLMChat error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("POST_LLM_CHAT_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 生成记忆中心问候语（优先走标准 tool_calls，失败时回退纯文本）
     */
    fun generateMemoryGreeting(call: MethodCall, result: MethodChannel.Result) {
        val model = call.argument<String>("model")?.trim().orEmpty()
            .ifEmpty { "scene.compactor.context.chat" }
        val records = (call.argument<List<Map<String, Any?>>>("records") ?: emptyList())
            .map { entry ->
                entry.mapKeys { it.key.toString() }
            }

        workJob.launch {
            try {
                val greeting = inferMemoryGreeting(model = model, records = records)
                withContext(Dispatchers.Main) {
                    result.success(greeting)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "generateMemoryGreeting error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GENERATE_MEMORY_GREETING_ERROR", e.message, null)
                }
            }
        }
    }

    fun getModelProviderConfig(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val config = PlatformAiProvisioner.officialProfileOrNull()?.let { profile ->
                    ModelProviderConfig(
                        id = profile.id,
                        name = profile.name,
                        baseUrl = profile.baseUrl,
                        source = "platform",
                        providerType = profile.sourceType,
                        readOnly = profile.readOnly,
                        ready = profile.ready,
                        statusText = profile.statusText,
                        wireApi = profile.wireApi,
                    )
                } ?: ModelProviderConfigStore.getConfig()
                withContext(Dispatchers.Main) {
                    result.success(config.toMap())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "getModelProviderConfig error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_MODEL_PROVIDER_CONFIG_ERROR", e.message, null)
                }
            }
        }
    }

    private suspend fun inferMemoryGreeting(
        model: String,
        records: List<Map<String, Any?>>
    ): String {
        val recordBlock = buildMemoryGreetingRecordsBlock(records)
        val request = buildMemoryGreetingToolRequest(model, recordBlock)
        val toolResponse = runCatching { HttpController.postSceneChatCompletion(request) }
            .onFailure { OmniLog.w(TAG, "memory greeting tool-call failed: ${it.message}") }
            .getOrNull()

        if (toolResponse != null && toolResponse.success) {
            parseMemoryGreetingFromToolCalls(toolResponse.toolCalls)?.let { parsed ->
                val normalized = sanitizeMemoryGreeting(parsed)
                if (normalized.isNotEmpty()) {
                    return normalized
                }
            }
            val contentCandidate = sanitizeMemoryGreeting(toolResponse.content)
            if (contentCandidate.isNotEmpty()) {
                return contentCandidate
            }
        }

        val fallbackPrompt = buildMemoryGreetingLegacyPrompt(recordBlock)
        val legacyResponse = runCatching {
            HttpController.postLLMRequest(model, fallbackPrompt).message
        }.onFailure {
            OmniLog.w(TAG, "memory greeting legacy request failed: ${it.message}")
        }.getOrNull().orEmpty()

        return sanitizeMemoryGreeting(legacyResponse).ifEmpty { defaultMemoryGreeting() }
    }

    private fun buildMemoryGreetingRecordsBlock(records: List<Map<String, Any?>>): String {
        if (records.isEmpty()) {
            return t("（暂无可用记忆）", "(No memory available yet)")
        }
        return records.joinToString(separator = "\n") { record ->
            val title = record["title"]?.toString()?.trim().orEmpty().ifEmpty { t("无标题", "Untitled") }
            val description = record["description"]?.toString()?.trim().orEmpty().ifEmpty { t("无描述", "No description") }
            val appName = record["appName"]?.toString()?.trim().orEmpty().ifEmpty { t("未知来源", "Unknown source") }
            t(
                "标题: $title, 描述: $description, 来源应用: $appName",
                "Title: $title, Description: $description, Source App: $appName"
            )
        }
    }

    private fun buildMemoryGreetingToolRequest(
        model: String,
        recordBlock: String
    ): ChatCompletionRequest {
        val parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "greeting",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    t(
                                        "给用户的一句简短温暖问候语，不超过30字。",
                                        "A short, warm greeting for the user, within 30 words."
                                    )
                                )
                            )
                        }
                    )
                }
            )
            put(
                "required",
                buildJsonArray {
                    add(JsonPrimitive("greeting"))
                }
            )
        }
        return ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatCompletionMessage(
                    role = "system",
                    content = JsonPrimitive(
                        when (currentLocale()) {
                            PromptLocale.ZH_CN -> """
                                你是小万，一个温暖的AI助手。
                                请根据用户记忆生成一句简短、温馨、个性化的问候语。
                                要求：
                                1. 问候语不超过30个字。
                                2. 语气温暖友好。
                                3. 禁止使用“你好呀”开头。
                                4. 必须通过工具 $MEMORY_GREETING_TOOL 返回结果，不要输出普通文本。
                            """.trimIndent()
                            PromptLocale.EN_US -> """
                                You are Omnibot, a warm AI assistant.
                                Generate one short, warm, personalized greeting based on the user's memory.
                                Requirements:
                                1. Keep the greeting within 30 words.
                                2. Use a warm and friendly tone.
                                3. Do not begin with "Hi there".
                                4. You must return the result through the $MEMORY_GREETING_TOOL tool instead of plain text.
                            """.trimIndent()
                        }
                    )
                ),
                ChatCompletionMessage(
                    role = "user",
                    content = JsonPrimitive(
                        t(
                            """
                            用户的记忆内容：
                            $recordBlock
                            """.trimIndent(),
                            """
                            User memory:
                            $recordBlock
                            """.trimIndent()
                        )
                    )
                )
            ),
            maxCompletionTokens = 128,
            temperature = 0.7,
            tools = listOf(
                ChatCompletionTool(
                    function = ChatCompletionFunction(
                        name = MEMORY_GREETING_TOOL,
                        description = t("提交记忆中心问候语。", "Submit the memory-center greeting."),
                        parameters = parameters
                    )
                )
            ),
            parallelToolCalls = false
        )
    }

    private fun buildMemoryGreetingLegacyPrompt(recordBlock: String): String {
        return when (currentLocale()) {
            PromptLocale.ZH_CN -> """
                你是小万，一个温暖的AI助手。根据用户的记忆内容（包含本地记忆和长期记忆），生成一句简短、温馨的问候语。

                要求：
                1. 问候语要简短（不超过30个字）
                2. 结合用户记忆内容特点，体现个性化
                3. 语气温暖友好
                4. 不要使用"你好呀"开头
                5. 只输出问候语本身，不要加引号或其他说明

                用户的记忆内容：
                $recordBlock
            """.trimIndent()
            PromptLocale.EN_US -> """
                You are Omnibot, a warm AI assistant. Based on the user's memory content, including local memory and long-term memory, generate one short and warm greeting.

                Requirements:
                1. Keep the greeting short, within 30 words.
                2. Personalize it based on the user's memory.
                3. Keep the tone warm and friendly.
                4. Do not begin with "Hi there".
                5. Output only the greeting itself, without quotes or extra explanation.

                User memory:
                $recordBlock
            """.trimIndent()
        }
    }

    private fun parseMemoryGreetingFromToolCalls(toolCalls: List<AssistantToolCall>): String? {
        if (toolCalls.isEmpty()) {
            return null
        }
        val selected = toolCalls.firstOrNull {
            it.function.name.trim().equals(MEMORY_GREETING_TOOL, ignoreCase = true)
        } ?: toolCalls.first()
        val argsRaw = selected.function.arguments.trim()
        if (argsRaw.isEmpty()) {
            return null
        }
        val jsonText = extractFirstJsonObject(argsRaw) ?: argsRaw
        val payload = runCatching { JSONObject(jsonText) }
            .onFailure { OmniLog.w(TAG, "parse memory greeting tool args failed: ${it.message}") }
            .getOrNull() ?: return null
        return payload.optString("greeting").trim().ifEmpty {
            payload.optString("message").trim()
        }.ifEmpty {
            payload.optString("content").trim()
        }.takeIf { it.isNotEmpty() }
    }

    private fun sanitizeMemoryGreeting(raw: String): String {
        var value = raw.trim()
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '"', '\'', '“', '”', '‘', '’')
        if (value.startsWith("你好呀")) {
            value = value.removePrefix("你好呀").trimStart('，', ',', '。', '！', '!', '～', '~', ' ')
        }
        if (value.startsWith("Hi there", ignoreCase = true)) {
            value = value.removePrefix("Hi there").trimStart(',', '.', '!', '~', ' ')
        }
        if (value.length > 30) {
            value = value.take(30)
        }
        return value.trim()
    }

    private fun extractFirstJsonObject(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        if (!fence.isNullOrBlank()) {
            return extractFirstJsonObject(fence)
        }
        val start = trimmed.indexOf('{')
        if (start < 0) {
            return null
        }
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until trimmed.length) {
            val ch = trimmed[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return trimmed.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    fun listModelProviderProfiles(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val allProfiles = ModelProviderConfigStore.listProfiles()
                val official = PlatformAiProvisioner.officialProfileOrNull()
                val profiles = allProfiles
                    .filterNot { OmniOfficialProvider.isOfficialProfile(it.id) }
                    .toMutableList()
                    .apply { if (official != null) add(official) }
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "profiles" to profiles.map { it.toMap() },
                            "editingProfileId" to ModelProviderConfigStore.getEditingProfileId()
                        )
                    )
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "listModelProviderProfiles error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("LIST_MODEL_PROVIDER_PROFILES_ERROR", e.message, null)
                }
            }
        }
    }

    fun listRecentAiRequestLogs(call: MethodCall, result: MethodChannel.Result) {
        val limit = call.argument<Int>("limit") ?: 10
        workJob.launch {
            try {
                val logs = AiRequestLogStore.listRecent(limit)
                withContext(Dispatchers.Main) {
                    result.success(logs.map { it.toMap() })
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "listRecentAiRequestLogs error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("LIST_RECENT_AI_REQUEST_LOGS_ERROR", e.message, null)
                }
            }
        }
    }

    fun listRuntimeLogs(call: MethodCall, result: MethodChannel.Result) {
        val limit = call.argument<Int>("limit") ?: 100
        workJob.launch {
            try {
                val logs = RuntimeLogStore.listRecent(limit)
                withContext(Dispatchers.Main) {
                    result.success(logs.map { it.toMap() })
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "listRuntimeLogs error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("LIST_RUNTIME_LOGS_ERROR", e.message, null)
                }
            }
        }
    }

    fun clearRuntimeLogs(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                RuntimeLogStore.clear()
                withContext(Dispatchers.Main) {
                    result.success(true)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "clearRuntimeLogs error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("CLEAR_RUNTIME_LOGS_ERROR", e.message, null)
                }
            }
        }
    }

    fun saveModelProviderProfile(call: MethodCall, result: MethodChannel.Result) {
        val profileId = call.argument<String>("id")?.trim()
        val name = call.argument<String>("name")?.trim().orEmpty()
        val baseUrl = call.argument<String>("baseUrl")?.trim().orEmpty()
        val apiKeyReplacement = call.argument<String>("apiKey")?.trim().orEmpty()
        val customHeadersReplacement = ProviderCustomHeaderUtils.coerceStringMap(
            call.argument<Map<*, *>>("customHeaders")
        )
        val replaceApiKey = call.argument<Boolean>("replaceApiKey") == true
        val clearApiKey = call.argument<Boolean>("clearApiKey") == true
        val replaceCustomHeaders = call.argument<Boolean>("replaceCustomHeaders") == true
        val clearCustomHeaders = call.argument<Boolean>("clearCustomHeaders") == true
        val sourceType = call.argument<String>("sourceType")?.trim()
        val protocolType = call.argument<String>("protocolType")?.trim() ?: "openai_compatible"
        val wireApi = call.argument<String>("wireApi")?.trim().orEmpty()

        workJob.launch {
            try {
                val existing = profileId?.let(ModelProviderConfigStore::getProfile)
                val apiKey = when {
                    clearApiKey -> ""
                    replaceApiKey -> apiKeyReplacement
                    existing != null -> existing.apiKey
                    else -> ""
                }
                val customHeaders = when {
                    clearCustomHeaders -> emptyMap()
                    replaceCustomHeaders -> customHeadersReplacement
                    existing != null -> existing.customHeaders
                    else -> emptyMap()
                }
                val saved = ModelProviderConfigStore.saveProfile(
                    id = profileId,
                    name = name,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    customHeaders = customHeaders,
                    sourceType = sourceType,
                    protocolType = protocolType,
                    wireApi = wireApi,
                )
                AgentRuntimeManager.getIfInitialized()
                    ?.invalidateSharedProviderRuntime(saved.id)
                withContext(Dispatchers.Main) {
                    result.success(saved.toMap())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "saveModelProviderProfile error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("SAVE_MODEL_PROVIDER_PROFILE_ERROR", e.message, null)
                }
            }
        }
    }

    fun deleteModelProviderProfile(call: MethodCall, result: MethodChannel.Result) {
        val profileId = call.argument<String>("profileId")?.trim().orEmpty()

        workJob.launch {
            try {
                val profiles = ModelProviderConfigStore.deleteProfile(profileId)
                AgentRuntimeManager.getIfInitialized()
                    ?.invalidateSharedProviderRuntime(profileId)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "profiles" to profiles.map { it.toMap() },
                            "editingProfileId" to ModelProviderConfigStore.getEditingProfileId()
                        )
                    )
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "deleteModelProviderProfile error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("DELETE_MODEL_PROVIDER_PROFILE_ERROR", e.message, null)
                }
            }
        }
    }

    fun setEditingModelProviderProfile(call: MethodCall, result: MethodChannel.Result) {
        val profileId = call.argument<String>("profileId")?.trim().orEmpty()

        workJob.launch {
            try {
                val selected = ModelProviderConfigStore.setEditingProfile(profileId)
                withContext(Dispatchers.Main) {
                    result.success(selected.toMap())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "setEditingModelProviderProfile error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("SET_EDITING_MODEL_PROVIDER_PROFILE_ERROR", e.message, null)
                }
            }
        }
    }

    fun saveModelProviderConfig(call: MethodCall, result: MethodChannel.Result) {
        val baseUrl = call.argument<String>("baseUrl")?.trim() ?: ""
        val apiKeyReplacement = call.argument<String>("apiKey")?.trim().orEmpty()
        val customHeadersReplacement = ProviderCustomHeaderUtils.coerceStringMap(
            call.argument<Map<*, *>>("customHeaders")
        )
        val replaceApiKey = call.argument<Boolean>("replaceApiKey") == true
        val clearApiKey = call.argument<Boolean>("clearApiKey") == true
        val replaceCustomHeaders = call.argument<Boolean>("replaceCustomHeaders") == true
        val clearCustomHeaders = call.argument<Boolean>("clearCustomHeaders") == true

        workJob.launch {
            try {
                val current = ModelProviderConfigStore.getEditingProfile()
                val apiKey = when {
                    clearApiKey -> ""
                    replaceApiKey -> apiKeyReplacement
                    else -> current.apiKey
                }
                val customHeaders = when {
                    clearCustomHeaders -> emptyMap()
                    replaceCustomHeaders -> customHeadersReplacement
                    else -> current.customHeaders
                }
                ModelProviderConfigStore.saveConfig(
                    baseUrl,
                    apiKey,
                    customHeaders,
                )
                val saved = ModelProviderConfigStore.getConfig()
                AgentRuntimeManager.getIfInitialized()
                    ?.invalidateSharedProviderRuntime(current.id)
                withContext(Dispatchers.Main) {
                    result.success(saved.toMap())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "saveModelProviderConfig error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("SAVE_MODEL_PROVIDER_CONFIG_ERROR", e.message, null)
                }
            }
        }
    }

    fun clearModelProviderConfig(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val profileId = ModelProviderConfigStore.getEditingProfileId()
                ModelProviderConfigStore.clearConfig()
                AgentRuntimeManager.getIfInitialized()
                    ?.invalidateSharedProviderRuntime(profileId)
                withContext(Dispatchers.Main) {
                    result.success(ModelProviderConfigStore.getConfig().toMap())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "clearModelProviderConfig error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("CLEAR_MODEL_PROVIDER_CONFIG_ERROR", e.message, null)
                }
            }
        }
    }

    fun fetchProviderModels(call: MethodCall, result: MethodChannel.Result) {
        val baseUrlArg = call.argument<String>("apiBase")?.trim().orEmpty()
        val apiKeyArg = call.argument<String>("apiKey")?.trim().orEmpty()
        val customHeadersArg = ProviderCustomHeaderUtils.coerceStringMap(
            call.argument<Map<*, *>>("customHeaders")
        )
        val useProvidedApiKey = call.argument<Boolean>("useProvidedApiKey") == true
        val useProvidedCustomHeaders = call.argument<Boolean>("useProvidedCustomHeaders") == true
        val profileId = call.argument<String>("profileId")?.trim()
        val capability = call.argument<String>("capability")?.trim()
        val forceRefresh = call.argument<Boolean>("forceRefresh") == true
        val expectedProfileRevision = call.argument<Number>("expectedProfileRevision")?.toLong()
        val expectedProfileBaseUrl = call.argument<String>("expectedProfileBaseUrl")?.trim().orEmpty()

        workJob.launch {
            try {
                if (OmniOfficialProvider.isOfficialProfile(profileId)) {
                    val models = if (forceRefresh) {
                        PlatformAiProvisioner.refreshAndGetModels(capability)
                    } else {
                        PlatformAiProvisioner.ensureReadyAndGetModels(capability)
                    }
                    withContext(Dispatchers.Main) {
                        result.success(models.map { it.toMap() })
                    }
                    return@launch
                }
                val profile = profileId?.let(ModelProviderConfigStore::getProfile)
                    ?: ModelProviderConfigStore.getEditingProfile()
                require(expectedProfileRevision != null && expectedProfileRevision >= 0L) {
                    "provider profile revision is required"
                }
                require(expectedProfileBaseUrl.isNotEmpty()) {
                    "provider profile endpoint is required"
                }
                require(
                    profile.revision == expectedProfileRevision &&
                        ModelProviderConfigStore.sameCanonicalEndpoint(
                            profile.baseUrl,
                            expectedProfileBaseUrl
                        )
                ) { "provider profile changed" }
                val apiBase = if (baseUrlArg.isNotEmpty()) baseUrlArg else profile.baseUrl
                val apiKey = if (useProvidedApiKey) apiKeyArg else profile.apiKey
                val customHeaders = if (useProvidedCustomHeaders) customHeadersArg else profile.customHeaders
                val models = HttpController.fetchProviderModels(
                    apiBase = apiBase,
                    apiKey = apiKey,
                    customHeaders = customHeaders,
                    protocolType = profile.protocolType,
                    wireApi = profile.wireApi
                )
                val currentProfile = profileId?.let(ModelProviderConfigStore::getProfile)
                require(
                    currentProfile != null &&
                        currentProfile.revision == expectedProfileRevision &&
                        ModelProviderConfigStore.sameCanonicalEndpoint(
                            currentProfile.baseUrl,
                            expectedProfileBaseUrl
                        )
                ) { "provider profile changed" }
                withContext(Dispatchers.Main) {
                    result.success(models.map { it.toMap() })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the user-facing error generic, but retain the actual
                // status/transport reason in logcat so an empty model list is
                // diagnosable on a real device without exposing credentials.
                OmniLog.e(
                    TAG,
                    "fetchProviderModels failed: " +
                        (e.message?.take(300) ?: e.javaClass.simpleName),
                    e
                )
                withContext(Dispatchers.Main) {
                    result.error(
                        "FETCH_PROVIDER_MODELS_ERROR",
                        AgentRuntimeErrorSupport.userFacingMessage(e)
                            ?: "Provider model fetch failed.",
                        AgentRuntimeErrorSupport.failureKind(e)?.let {
                            mapOf("failureKind" to it)
                        }
                    )
                }
            }
        }
    }

    fun checkProviderModelAvailability(call: MethodCall, result: MethodChannel.Result) {
        val model = call.argument<String>("model")?.trim() ?: ""
        val baseUrlArg = call.argument<String>("apiBase")?.trim().orEmpty()
        val apiKeyArg = call.argument<String>("apiKey")?.trim().orEmpty()
        val customHeadersArg = ProviderCustomHeaderUtils.coerceStringMap(
            call.argument<Map<*, *>>("customHeaders")
        )
        val useProvidedApiKey = call.argument<Boolean>("useProvidedApiKey") == true
        val useProvidedCustomHeaders = call.argument<Boolean>("useProvidedCustomHeaders") == true
        val profileId = call.argument<String>("profileId")?.trim()
        val capability = call.argument<String>("capability")?.trim()

        workJob.launch {
            try {
                if (OmniOfficialProvider.isOfficialProfile(profileId)) {
                    val available = PlatformAiProvisioner.ensureReadyAndGetModels(capability)
                        .any { it.id == model }
                    withContext(Dispatchers.Main) {
                        result.success(
                            mapOf(
                                "available" to available,
                                "code" to if (available) 200 else 404,
                                "message" to if (available) "OK" else "该模型不在当前官方模型列表中"
                            )
                        )
                    }
                    return@launch
                }
                val profile = profileId?.let(ModelProviderConfigStore::getProfile)
                    ?: ModelProviderConfigStore.getEditingProfile()
                val apiBase = if (baseUrlArg.isNotEmpty()) baseUrlArg else profile.baseUrl
                val apiKey = if (useProvidedApiKey) apiKeyArg else profile.apiKey
                val customHeaders = if (useProvidedCustomHeaders) {
                    customHeadersArg
                } else {
                    profile.customHeaders
                }
                val checkResult = HttpController.checkProviderModelAvailability(
                    model = model,
                    apiBase = apiBase,
                    apiKey = apiKey,
                    customHeaders = customHeaders,
                    protocolType = profile.protocolType,
                    wireApi = profile.wireApi
                )

                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "available" to checkResult.available,
                            "code" to checkResult.code,
                            "message" to checkResult.message
                        )
                    )
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "checkProviderModelAvailability error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "available" to false,
                            "code" to null,
                            "message" to (e.message ?: "检测失败")
                        )
                    )
                }
            }
        }
    }

    fun getSceneModelCatalog(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val catalog = SceneModelCatalogResolver.listCatalogItems()
                withContext(Dispatchers.Main) {
                    result.success(catalog.map { it.toMap() })
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "getSceneModelCatalog error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_SCENE_MODEL_CATALOG_ERROR", e.message, null)
                }
            }
        }
    }

    fun getSceneModelBindings(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                withContext(Dispatchers.Main) {
                    result.success(SceneModelBindingStore.getBindingEntries().map { it.toMap() })
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "getSceneModelBindings error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_SCENE_MODEL_BINDINGS_ERROR", e.message, null)
                }
            }
        }
    }

    fun saveSceneModelBinding(call: MethodCall, result: MethodChannel.Result) {
        val sceneId = call.argument<String>("sceneId")?.trim().orEmpty()
        val providerProfileId = call.argument<String>("providerProfileId")?.trim().orEmpty()
        val modelId = call.argument<String>("modelId")?.trim().orEmpty()

        workJob.launch {
            try {
                SceneModelBindingStore.saveBinding(sceneId, providerProfileId, modelId)
                if (sceneId == SceneOperationConfigStore.SCENE_ID) {
                    SceneOperationConfigStore.saveConfig(
                        SceneOperationConfig(useOfficialService = false)
                    )
                }
                if (sceneId == "scene.dispatch.model") {
                    AgentRuntimeManager.getIfInitialized()
                        ?.invalidateSharedProviderRuntime()
                }
                withContext(Dispatchers.Main) {
                    result.success(SceneModelBindingStore.getBindingEntries().map { it.toMap() })
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "saveSceneModelBinding error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("SAVE_SCENE_MODEL_BINDING_ERROR", e.message, null)
                }
            }
        }
    }

    fun clearSceneModelBinding(call: MethodCall, result: MethodChannel.Result) {
        val sceneId = call.argument<String>("sceneId")?.trim().orEmpty()

        workJob.launch {
            try {
                SceneModelBindingStore.clearBinding(sceneId)
                if (sceneId == "scene.dispatch.model") {
                    AgentRuntimeManager.getIfInitialized()
                        ?.invalidateSharedProviderRuntime()
                }
                withContext(Dispatchers.Main) {
                    result.success(SceneModelBindingStore.getBindingEntries().map { it.toMap() })
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "clearSceneModelBinding error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("CLEAR_SCENE_MODEL_BINDING_ERROR", e.message, null)
                }
            }
        }
    }

    fun getSceneOperationConfig(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                withContext(Dispatchers.Main) {
                    result.success(SceneOperationConfigStore.getConfig().toMap())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "getSceneOperationConfig error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_SCENE_OPERATION_CONFIG_ERROR", e.message, null)
                }
            }
        }
    }

    fun saveSceneOperationConfig(call: MethodCall, result: MethodChannel.Result) {
        val useOfficialService = call.argument<Boolean>("useOfficialService") == true
        workJob.launch {
            try {
                if (useOfficialService) {
                    SceneModelBindingStore.clearBinding(SceneOperationConfigStore.SCENE_ID)
                }
                val saved = SceneOperationConfigStore.saveConfig(
                    SceneOperationConfig(useOfficialService = useOfficialService)
                )
                withContext(Dispatchers.Main) {
                    result.success(saved.toMap())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "saveSceneOperationConfig error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("SAVE_SCENE_OPERATION_CONFIG_ERROR", e.message, null)
                }
            }
        }
    }

    fun getSceneVoiceConfig(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                withContext(Dispatchers.Main) {
                    result.success(SceneVoiceConfigStore.getConfig().toMap())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "getSceneVoiceConfig error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_SCENE_VOICE_CONFIG_ERROR", e.message, null)
                }
            }
        }
    }

    fun saveSceneVoiceConfig(call: MethodCall, result: MethodChannel.Result) {
        val replaceCommand = call.argument<Boolean>("replaceCustomCurlCommand") == true
        val clearCommand = call.argument<Boolean>("clearCustomCurlCommand") == true
        workJob.launch {
            try {
                val current = SceneVoiceConfigStore.getConfig()
                val config = SceneVoiceConfig(
                    autoPlay = call.argument<Boolean>("autoPlay") ?: current.autoPlay,
                    voiceId = call.argument<String>("voiceId") ?: current.voiceId,
                    stylePreset = call.argument<String>("stylePreset") ?: current.stylePreset,
                    customStyle = call.argument<String>("customStyle") ?: current.customStyle,
                    ttsMode = call.argument<String>("ttsMode") ?: current.ttsMode,
                    customCurlCommand = call.argument<String>("customCurlCommand") ?: current.customCurlCommand,
                )
                val saved = SceneVoiceConfigStore.saveConfig(
                    config = config,
                    replaceCustomCurlCommand = replaceCommand,
                    clearCustomCurlCommand = clearCommand,
                )
                withContext(Dispatchers.Main) { result.success(saved.toMap()) }
            } catch (e: Exception) {
                OmniLog.e(TAG, "saveSceneVoiceConfig error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("SAVE_SCENE_VOICE_CONFIG_ERROR", e.message, null)
                }
            }
        }
    }

    fun getSceneModelOverrides(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                withContext(Dispatchers.Main) {
                    result.success(SceneModelOverrideStore.getOverrideEntries().map { it.toMap() })
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "getSceneModelOverrides error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_SCENE_MODEL_OVERRIDES_ERROR", e.message, null)
                }
            }
        }
    }

    fun saveSceneModelOverride(call: MethodCall, result: MethodChannel.Result) {
        val sceneId = call.argument<String>("sceneId")?.trim() ?: ""
        val model = call.argument<String>("model")?.trim() ?: ""

        workJob.launch {
            try {
                SceneModelOverrideStore.saveOverride(sceneId, model)
                withContext(Dispatchers.Main) {
                    result.success(SceneModelOverrideStore.getOverrideEntries().map { it.toMap() })
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "saveSceneModelOverride error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("SAVE_SCENE_MODEL_OVERRIDE_ERROR", e.message, null)
                }
            }
        }
    }

    fun clearSceneModelOverride(call: MethodCall, result: MethodChannel.Result) {
        val sceneId = call.argument<String>("sceneId")?.trim() ?: ""

        workJob.launch {
            try {
                SceneModelOverrideStore.clearOverride(sceneId)
                withContext(Dispatchers.Main) {
                    result.success(SceneModelOverrideStore.getOverrideEntries().map { it.toMap() })
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "clearSceneModelOverride error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("CLEAR_SCENE_MODEL_OVERRIDE_ERROR", e.message, null)
                }
            }
        }
    }

    fun getAgentSoulSetting(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val service = WorkspaceMemoryService(context)
                val content = service.readSoul()
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "content" to content
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("GET_AGENT_SOUL_SETTING_ERROR", e.message, null)
                }
            }
        }
    }

    fun getChatPromptSetting(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val service = WorkspaceMemoryService(context)
                val content = service.readChatPrompt()
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "content" to content
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("GET_CHAT_PROMPT_SETTING_ERROR", e.message, null)
                }
            }
        }
    }

    fun saveAgentSoulSetting(call: MethodCall, result: MethodChannel.Result) {
        val content = call.argument<String>("content") ?: ""
        workJob.launch {
            try {
                val service = WorkspaceMemoryService(context)
                service.writeSoul(content)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "content" to service.readSoul()
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("SAVE_AGENT_SOUL_SETTING_ERROR", e.message, null)
                }
            }
        }
    }

    fun saveChatPromptSetting(call: MethodCall, result: MethodChannel.Result) {
        val content = call.argument<String>("content") ?: ""
        workJob.launch {
            try {
                val service = WorkspaceMemoryService(context)
                service.writeChatPrompt(content)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "content" to service.readChatPrompt()
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("SAVE_CHAT_PROMPT_SETTING_ERROR", e.message, null)
                }
            }
        }
    }

    fun getWorkspaceLongMemory(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val service = WorkspaceMemoryService(context)
                val content = service.readLongTermMemory()
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "content" to content
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("GET_WORKSPACE_MEMORY_ERROR", e.message, null)
                }
            }
        }
    }

    fun getWorkspaceShortMemories(call: MethodCall, result: MethodChannel.Result) {
        val days = (call.argument<Int>("days") ?: 14).coerceIn(1, 90)
        val limit = (call.argument<Int>("limit") ?: 240).coerceIn(1, 1000)
        workJob.launch {
            try {
                val service = WorkspaceMemoryService(context)
                val payload = service.listShortMemoryEntries(days = days, limit = limit)
                    .map { entry ->
                        mapOf(
                            "id" to entry.id,
                            "date" to entry.date,
                            "time" to entry.time,
                            "content" to entry.content,
                            "timestampMillis" to entry.timestampMillis,
                            "quickLogId" to entry.quickLogId
                        )
                    }
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "items" to payload
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("GET_WORKSPACE_SHORT_MEMORY_ERROR", e.message, null)
                }
            }
        }
    }

    fun listQuickLogs(call: MethodCall, result: MethodChannel.Result) {
        val limit = (call.argument<Int>("limit") ?: 200).coerceIn(1, 500)
        workJob.launch {
            try {
                val service = QuickLogService(context)
                val items = service.listLogs(limit).map { it.toMap() }
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "items" to items,
                            "totalCount" to service.countLogs()
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("LIST_QUICK_LOGS_ERROR", e.message, null)
                }
            }
        }
    }

    fun addQuickLog(call: MethodCall, result: MethodChannel.Result) {
        val content = call.argument<String>("content") ?: ""
        val source = call.argument<String>("source") ?: QuickLogService.SOURCE_APP
        workJob.launch {
            try {
                val item = QuickLogService(context).addLog(
                    content = content,
                    source = source
                )
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "item" to item.toMap()
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("ADD_QUICK_LOG_ERROR", e.message, null)
                }
            }
        }
    }

    fun updateQuickLog(call: MethodCall, result: MethodChannel.Result) {
        val id = call.argument<String>("id") ?: ""
        val content = call.argument<String>("content") ?: ""
        workJob.launch {
            try {
                val item = QuickLogService(context).updateLog(id, content)
                withContext(Dispatchers.Main) {
                    if (item == null) {
                        result.error("UPDATE_QUICK_LOG_NOT_FOUND", "quick log not found", null)
                    } else {
                        result.success(
                            mapOf(
                                "item" to item.toMap()
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("UPDATE_QUICK_LOG_ERROR", e.message, null)
                }
            }
        }
    }

    fun deleteQuickLog(call: MethodCall, result: MethodChannel.Result) {
        val id = call.argument<String>("id") ?: ""
        workJob.launch {
            try {
                val deleted = QuickLogService(context).deleteLog(id)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "deleted" to deleted
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("DELETE_QUICK_LOG_ERROR", e.message, null)
                }
            }
        }
    }

    private fun isWorkspaceRollupMetadataLine(item: String): Boolean {
        val lower = item.lowercase()
        return lower.startsWith("source:") ||
            lower.startsWith("inputlines:") ||
            (item.startsWith("已整理") && item.contains("条短期记忆")) ||
            (item.contains("沉淀") && item.contains("长期记忆"))
    }

    fun saveWorkspaceLongMemory(call: MethodCall, result: MethodChannel.Result) {
        val content = call.argument<String>("content") ?: ""
        workJob.launch {
            try {
                val service = WorkspaceMemoryService(context)
                service.writeLongTermMemory(content)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "content" to service.readLongTermMemory()
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("SAVE_WORKSPACE_MEMORY_ERROR", e.message, null)
                }
            }
        }
    }

    fun getWorkspaceMemoryEmbeddingConfig(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val config = WorkspaceMemoryService(context).getEmbeddingConfigForUi()
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "enabled" to config.enabled,
                            "configured" to config.configured,
                            "sceneId" to config.sceneId,
                            "providerProfileId" to config.providerProfileId,
                            "providerProfileName" to config.providerProfileName,
                            "modelId" to config.modelId,
                            "apiBase" to config.apiBase,
                            "hasApiKey" to config.hasApiKey,
                            "usesPlatform" to config.usesPlatform
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("GET_MEMORY_EMBEDDING_CONFIG_ERROR", e.message, null)
                }
            }
        }
    }

    fun saveWorkspaceMemoryEmbeddingConfig(call: MethodCall, result: MethodChannel.Result) {
        val enabled = call.argument<Boolean>("enabled") ?: true
        val providerProfileId = call.argument<String>("providerProfileId")
        val modelId = call.argument<String>("modelId")
        workJob.launch {
            try {
                val config = WorkspaceMemoryService(context).saveEmbeddingConfigForUi(
                    enabled = enabled,
                    providerProfileId = providerProfileId,
                    modelId = modelId
                )
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "enabled" to config.enabled,
                            "configured" to config.configured,
                            "sceneId" to config.sceneId,
                            "providerProfileId" to config.providerProfileId,
                            "providerProfileName" to config.providerProfileName,
                            "modelId" to config.modelId,
                            "apiBase" to config.apiBase,
                            "hasApiKey" to config.hasApiKey,
                            "usesPlatform" to config.usesPlatform
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("SAVE_MEMORY_EMBEDDING_CONFIG_ERROR", e.message, null)
                }
            }
        }
    }

    fun getWorkspaceMemoryRollupStatus(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val service = WorkspaceMemoryService(context)
                val status = service.getRollupStatusForUi()
                val scheduler = WorkspaceMemoryRollupScheduler(context)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "enabled" to status.enabled,
                            "lastRunAtMillis" to status.lastRunAtMillis,
                            "lastRunSummary" to status.lastRunSummary,
                            "nextRunAtMillis" to scheduler.getNextRunAtMillis()
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("GET_MEMORY_ROLLUP_STATUS_ERROR", e.message, null)
                }
            }
        }
    }

    fun saveWorkspaceMemoryRollupEnabled(call: MethodCall, result: MethodChannel.Result) {
        val enabled = call.argument<Boolean>("enabled") ?: true
        workJob.launch {
            try {
                val scheduler = WorkspaceMemoryRollupScheduler(context)
                val status = scheduler.setEnabled(enabled)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "enabled" to status.enabled,
                            "lastRunAtMillis" to status.lastRunAtMillis,
                            "lastRunSummary" to status.lastRunSummary,
                            "nextRunAtMillis" to scheduler.getNextRunAtMillis()
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("SAVE_MEMORY_ROLLUP_STATUS_ERROR", e.message, null)
                }
            }
        }
    }

    fun runWorkspaceMemoryRollupNow(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val payload = WorkspaceMemoryService(context).rollupDay().toMutableMap()
                runCatching {
                    WorkspaceMemoryRollupScheduler(context).ensureScheduledIfEnabled()
                }.onFailure { throwable ->
                    OmniLog.w(
                        TAG,
                        "runWorkspaceMemoryRollupNow schedule failed: ${throwable.message}"
                    )
                    payload["scheduleWarning"] = throwable.message
                }
                withContext(Dispatchers.Main) {
                    result.success(payload)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("RUN_MEMORY_ROLLUP_ERROR", e.message, null)
                }
            }
        }
    }

    fun upsertWorkspaceScheduledTask(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val rawTask = toStringAnyMap(call.argument<Any?>("task"))
                val payload = WorkspaceScheduledTaskScheduler(context).upsertTask(rawTask)
                withContext(Dispatchers.Main) {
                    result.success(payload)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("UPSERT_WORKSPACE_SCHEDULED_TASK_ERROR", e.message, null)
                }
            }
        }
    }

    fun deleteWorkspaceScheduledTask(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val taskId = call.argument<String>("taskId")?.trim().orEmpty()
                val deleted = WorkspaceScheduledTaskScheduler(context).deleteTask(taskId)
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "taskId" to taskId,
                            "deleted" to deleted
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("DELETE_WORKSPACE_SCHEDULED_TASK_ERROR", e.message, null)
                }
            }
        }
    }

    fun syncWorkspaceScheduledTasks(call: MethodCall, result: MethodChannel.Result) {
        workJob.launch {
            try {
                val rawTasks = toListOfStringAnyMap(call.argument<Any?>("tasks"))
                val payload = WorkspaceScheduledTaskScheduler(context).syncTasks(rawTasks)
                withContext(Dispatchers.Main) {
                    result.success(payload)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("SYNC_WORKSPACE_SCHEDULED_TASKS_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 打开APP市场
     */
    fun openAPPMarket(call: MethodCall, result: MethodChannel.Result) {
        val packageName = call.argument<String>("packageName") ?: ""
        try {
            if (packageName.isNotEmpty()) {
                SchemeUtil.jumpToMarket(context, packageName)
                result.success("SUCCESS")
            } else {
                result.error("OPEN_APP_MARKET_ERROR", "packageName is empty", null)
            }

        } catch (e: Exception) {
            result.error("OPEN_APP_MARKET_ERROR", e.message, null)
        }
    }

    /**
     * 获取桌面包名
     */
    fun getDeskTopPackageName(call: MethodCall, result: MethodChannel.Result){
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val packages = context.packageManager
                .queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo.packageName }
                .distinct()
            result.success(packages)
        } catch (e: Exception) {
            result.error("GET_DESK_TOP_PACKAGE_NAME_ERROR", e.message, null)
        }
    }

    /**
     * 跳转到主引擎路由
     */
    fun navigateToMainEngineRoute(call: MethodCall, result: MethodChannel.Result) {
        val route = call.argument<String>("route") ?: ""
        if (route.isNotEmpty()) {
            try {
                TaskCompletionNavigator.navigateToMainRoute(context, route, needClear = false)
                mainJob.launch(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "navigateToMainEngineRoute failed: ${e.message}")
                mainJob.launch(Dispatchers.Main) {
                    result.error("NAVIGATE_ERROR", e.message, null)
                }
            }
        } else {
            result.error("NAVIGATE_ERROR", "Route is empty", null)
        }
    }

    private fun parseScheduledSubagentRunMeta(
        conversationMode: String,
        conversationId: Long?,
        call: MethodCall
    ): ScheduledSubagentRunMeta? {
        if (!conversationMode.equals(SUBAGENT_MODE, ignoreCase = true)) {
            return null
        }
        val normalizedConversationId = conversationId?.takeIf { it > 0 } ?: return null
        val scheduleTaskId = call.argument<String>("scheduledTaskId")?.trim().orEmpty()
        if (scheduleTaskId.isEmpty()) {
            return null
        }
        val title = call.argument<String>("scheduledTaskTitle")?.trim().orEmpty()
        val notificationEnabled = call.argument<Boolean>("scheduleNotificationEnabled") != false
        return ScheduledSubagentRunMeta(
            scheduleTaskId = scheduleTaskId,
            scheduleTaskTitle = title.ifBlank { t("SubAgent 定时任务", "SubAgent Scheduled Task") },
            notificationEnabled = notificationEnabled,
            conversationId = normalizedConversationId
        )
    }

    private fun enrichScheduledSubagentParent(
        arguments: Map<String, Any?>,
        parentConversationId: Long?,
        parentConversationMode: String
    ): Map<String, Any?> {
        val targetKind = arguments["targetKind"]?.toString()?.trim().orEmpty()
        if (!targetKind.equals(SUBAGENT_MODE, ignoreCase = true) || parentConversationId == null) {
            return arguments
        }
        if (
            arguments["parentConversationId"] != null ||
            arguments["subagentParentConversationId"] != null
        ) {
            return arguments
        }
        return LinkedHashMap(arguments).apply {
            put("parentConversationId", parentConversationId)
            put("parentConversationMode", parentConversationMode)
        }
    }

    private fun normalizeNotificationBody(text: String): String {
        val normalized = AgentTextSanitizer.sanitizeUtf16(text)
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) {
            return t("任务已完成，点击查看详情。", "Task completed. Tap to view details.")
        }
        return if (normalized.length <= 120) {
            normalized
        } else {
            normalized.take(117) + "..."
        }
    }

    private fun notifyScheduledSubagentCompletion(
        meta: ScheduledSubagentRunMeta,
        message: String
    ) {
        if (!meta.notificationEnabled) return
        val notificationManagerCompat = NotificationManagerCompat.from(context)
        if (!notificationManagerCompat.areNotificationsEnabled()) {
            OmniLog.w(TAG, "skip scheduled subagent notification: app notifications disabled")
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            OmniLog.w(TAG, "skip scheduled subagent notification: permission denied")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    SCHEDULED_SUBAGENT_NOTIFICATION_CHANNEL,
                    t("SubAgent 定时任务", "SubAgent Scheduled Task"),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = t("SubAgent 定时任务执行完成通知", "Notifications for completed scheduled SubAgent runs")
                }
            )
        }
        val route = TaskCompletionNavigator.buildChatRoute(meta.conversationId, SUBAGENT_MODE)
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            putExtra("route", route)
            putExtra("needClear", false)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            ("scheduled_subagent_" + meta.scheduleTaskId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
        val iconRes = context.applicationInfo.icon.takeIf { it != 0 } ?: R.mipmap.ic_launcher
        val notification = NotificationCompat.Builder(
            context,
            SCHEDULED_SUBAGENT_NOTIFICATION_CHANNEL
        )
            .setSmallIcon(iconRes)
            .setContentTitle(meta.scheduleTaskTitle.ifBlank { t("SubAgent 定时任务", "SubAgent Scheduled Task") })
            .setContentText(normalizeNotificationBody(message))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(normalizeNotificationBody(message))
            )
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val notificationId =
            "${meta.scheduleTaskId}_${System.currentTimeMillis()}".hashCode()
        notificationManagerCompat.notify(notificationId, notification)
    }

    private fun immutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    /**
     * 创建 Agent 任务
     */
    private fun resolveAgentModelOverride(raw: Map<String, Any?>?): AgentModelOverride? {
        return resolveDirectAgentModelOverride(raw, ::lookupRuntimeProviderProfile)
    }


    fun agentSkillList(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch {
            try {
                if (!WorkspaceStorageAccess.isGranted(context)) {
                    withContext(Dispatchers.Main) {
                        result.error(
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED",
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME,
                            null
                        )
                    }
                    return@launch
                }
                val workspaceManager = AgentWorkspaceManager(context)
                val skillIndexService = SkillIndexService(context, workspaceManager)
                val payload = skillIndexService.listSkillsForManagement().map(::skillEntryPayload)
                withContext(Dispatchers.Main) {
                    result.success(payload)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val isWorkspacePermissionError =
                        WorkspaceStorageAccess.looksLikePermissionError(e)
                    result.error(
                        if (isWorkspacePermissionError) {
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED"
                        } else {
                            "AGENT_SKILL_LIST_ERROR"
                        },
                        if (isWorkspacePermissionError) {
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME
                        } else {
                            e.message
                        },
                        null
                    )
                }
            }
        }
    }

    private fun skillEntryPayload(entry: SkillIndexEntry): Map<String, Any?> {
        return mapOf(
            "id" to entry.id,
            "name" to entry.name,
            "description" to entry.description,
            "compatibility" to entry.compatibility,
            "metadata" to entry.metadata,
            "rootPath" to entry.rootPath,
            "shellRootPath" to entry.shellRootPath,
            "skillFilePath" to entry.skillFilePath,
            "shellSkillFilePath" to entry.shellSkillFilePath,
            "hasScripts" to entry.hasScripts,
            "hasReferences" to entry.hasReferences,
            "hasAssets" to entry.hasAssets,
            "hasEvals" to entry.hasEvals,
            "enabled" to entry.enabled,
            "source" to entry.source,
            "installed" to entry.installed
        )
    }

    fun agentSkillInstall(call: MethodCall, result: MethodChannel.Result) {
        val sourcePath = call.argument<String>("sourcePath")?.trim().orEmpty()
        if (sourcePath.isBlank()) {
            result.error("INVALID_ARGS", "sourcePath is required", null)
            return
        }
        mainJob.launch {
            try {
                if (!WorkspaceStorageAccess.isGranted(context)) {
                    withContext(Dispatchers.Main) {
                        result.error(
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED",
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME,
                            null
                        )
                    }
                    return@launch
                }
                val workspaceManager = AgentWorkspaceManager(context)
                val skillIndexService = SkillIndexService(context, workspaceManager)
                val entry = skillIndexService.installSkillFromDirectory(sourcePath)
                withContext(Dispatchers.Main) {
                    result.success(skillEntryPayload(entry))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val isWorkspacePermissionError =
                        WorkspaceStorageAccess.looksLikePermissionError(e)
                    result.error(
                        if (isWorkspacePermissionError) {
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED"
                        } else {
                            "AGENT_SKILL_INSTALL_ERROR"
                        },
                        if (isWorkspacePermissionError) {
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME
                        } else {
                            e.message
                        },
                        null
                    )
                }
            }
        }
    }

    fun agentSkillSetEnabled(call: MethodCall, result: MethodChannel.Result) {
        val skillId = call.argument<String>("skillId")?.trim().orEmpty()
        val enabled = call.argument<Boolean>("enabled") ?: true
        if (skillId.isBlank()) {
            result.error("INVALID_ARGS", "skillId is required", null)
            return
        }
        mainJob.launch {
            try {
                if (!WorkspaceStorageAccess.isGranted(context)) {
                    withContext(Dispatchers.Main) {
                        result.error(
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED",
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME,
                            null
                        )
                    }
                    return@launch
                }
                val workspaceManager = AgentWorkspaceManager(context)
                val skillIndexService = SkillIndexService(context, workspaceManager)
                val entry = skillIndexService.setSkillEnabled(skillId, enabled)
                withContext(Dispatchers.Main) {
                    result.success(skillEntryPayload(entry))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val isWorkspacePermissionError =
                        WorkspaceStorageAccess.looksLikePermissionError(e)
                    result.error(
                        if (isWorkspacePermissionError) {
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED"
                        } else {
                            "AGENT_SKILL_SET_ENABLED_ERROR"
                        },
                        if (isWorkspacePermissionError) {
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME
                        } else {
                            e.message
                        },
                        null
                    )
                }
            }
        }
    }

    fun agentSkillDelete(call: MethodCall, result: MethodChannel.Result) {
        val skillId = call.argument<String>("skillId")?.trim().orEmpty()
        if (skillId.isBlank()) {
            result.error("INVALID_ARGS", "skillId is required", null)
            return
        }
        mainJob.launch {
            try {
                if (!WorkspaceStorageAccess.isGranted(context)) {
                    withContext(Dispatchers.Main) {
                        result.error(
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED",
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME,
                            null
                        )
                    }
                    return@launch
                }
                val workspaceManager = AgentWorkspaceManager(context)
                val skillIndexService = SkillIndexService(context, workspaceManager)
                val deleted = skillIndexService.deleteSkill(skillId)
                withContext(Dispatchers.Main) {
                    result.success(mapOf("deleted" to deleted, "id" to skillId))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val isWorkspacePermissionError =
                        WorkspaceStorageAccess.looksLikePermissionError(e)
                    result.error(
                        if (isWorkspacePermissionError) {
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED"
                        } else {
                            "AGENT_SKILL_DELETE_ERROR"
                        },
                        if (isWorkspacePermissionError) {
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME
                        } else {
                            e.message
                        },
                        null
                    )
                }
            }
        }
    }

    fun agentSkillInstallBuiltin(call: MethodCall, result: MethodChannel.Result) {
        val skillId = call.argument<String>("skillId")?.trim().orEmpty()
        if (skillId.isBlank()) {
            result.error("INVALID_ARGS", "skillId is required", null)
            return
        }
        mainJob.launch {
            try {
                if (!WorkspaceStorageAccess.isGranted(context)) {
                    withContext(Dispatchers.Main) {
                        result.error(
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED",
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME,
                            null
                        )
                    }
                    return@launch
                }
                val workspaceManager = AgentWorkspaceManager(context)
                val skillIndexService = SkillIndexService(context, workspaceManager)
                val entry = skillIndexService.installBuiltinSkill(skillId)
                withContext(Dispatchers.Main) {
                    result.success(skillEntryPayload(entry))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val isWorkspacePermissionError =
                        WorkspaceStorageAccess.looksLikePermissionError(e)
                    result.error(
                        if (isWorkspacePermissionError) {
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED"
                        } else {
                            "AGENT_SKILL_INSTALL_BUILTIN_ERROR"
                        },
                        if (isWorkspacePermissionError) {
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME
                        } else {
                            e.message
                        },
                        null
                    )
                }
            }
        }
    }

    fun agentSkillSyncOfficialRepository(call: MethodCall, result: MethodChannel.Result) {
        mainJob.launch {
            try {
                if (!WorkspaceStorageAccess.isGranted(context)) {
                    withContext(Dispatchers.Main) {
                        result.error(
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED",
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME,
                            null
                        )
                    }
                    return@launch
                }
                val workspaceManager = AgentWorkspaceManager(context)
                val skillIndexService = SkillIndexService(context, workspaceManager)
                val syncResult = skillIndexService.syncOfficialSkillsRepository()
                withContext(Dispatchers.Main) {
                    result.success(
                        mapOf(
                            "action" to syncResult.action,
                            "repositoryUrl" to syncResult.repositoryUrl,
                            "rootPath" to syncResult.rootPath,
                            "shellRootPath" to syncResult.shellRootPath,
                            "skillCount" to syncResult.skillCount,
                            "skills" to syncResult.skills.map(::skillEntryPayload),
                            "output" to syncResult.output
                        )
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val isWorkspacePermissionError =
                        WorkspaceStorageAccess.looksLikePermissionError(e)
                    result.error(
                        if (isWorkspacePermissionError) {
                            "WORKSPACE_STORAGE_PERMISSION_REQUIRED"
                        } else {
                            "AGENT_SKILL_SYNC_OFFICIAL_ERROR"
                        },
                        if (isWorkspacePermissionError) {
                            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME
                        } else {
                            e.message
                        },
                        null
                    )
                }
            }
        }
    }

    fun getTokenUsageRecords(call: MethodCall, result: MethodChannel.Result) {
        val sinceMs = call.argument<Number>("since")?.toLong() ?: 0L
        workJob.launch {
            try {
                val records = DatabaseHelper.getTokenUsageRecordsSince(sinceMs)
                val jsonList = records.map { record ->
                    mapOf(
                        "id" to record.id,
                        "conversationId" to record.conversationId,
                        "model" to record.model,
                        "promptTokens" to record.promptTokens,
                        "completionTokens" to record.completionTokens,
                        "reasoningTokens" to record.reasoningTokens,
                        "textTokens" to record.textTokens,
                        "cachedTokens" to record.cachedTokens,
                        "cacheCreationTokens" to record.cacheCreationTokens,
                        "createdAt" to record.createdAt
                    )
                }
                withContext(Dispatchers.Main) {
                    result.success(jsonList)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "Failed to get token usage records: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_TOKEN_USAGE_RECORDS_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 获取所有对话列表
     */
    fun getConversations(call: MethodCall, result: MethodChannel.Result) {
        OmniLog.d(TAG, "[getConversations] 开始获取对话列表...")
        val includeArchived = call.argument<Boolean>("includeArchived") ?: true
        val archivedOnly = call.argument<Boolean>("archivedOnly") ?: false
        val archiveBefore = call.argument<Number>("archiveBefore")?.toLong()
        workJob.launch {
            try {
                if (archiveBefore != null && archiveBefore > 0L) {
                    conversationDomainService.archiveConversationsUpdatedBefore(
                        archiveBefore
                    )
                }
                val jsonList = conversationDomainService.listConversationPayloads(
                    includeArchived = includeArchived,
                    archivedOnly = archivedOnly
                )
                OmniLog.d(TAG, "[getConversations] 从数据库获取到 ${jsonList.size} 条对话记录")
                withContext(Dispatchers.Main) {
                    OmniLog.d(TAG, "[getConversations] 返回 Flutter: $jsonList")
                    result.success(jsonList)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "[getConversations] 获取对话列表失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_CONVERSATIONS_ERROR", e.message, null)
                }
            }
        }
    }

    fun getConversationMessages(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = call.argument<Number>("conversationId")?.toLong() ?: 0L
        val mode = normalizeConversationMode(
            call.argument<String>("mode") ?: call.argument<String>("conversationMode")
        )
        if (conversationId <= 0L) {
            result.error("INVALID_ARGUMENTS", "conversationId is invalid", null)
            return
        }
        workJob.launch {
            try {
                val messages = conversationDomainService.listConversationMessages(
                    conversationId = conversationId,
                    conversationMode = mode
                )
                withContext(Dispatchers.Main) {
                    result.success(messages)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "获取对话消息失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_CONVERSATION_MESSAGES_ERROR", e.message, null)
                }
            }
        }
    }

    fun getConversationMessagesPaged(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = call.argument<Number>("conversationId")?.toLong() ?: 0L
        val mode = normalizeConversationMode(
            call.argument<String>("mode") ?: call.argument<String>("conversationMode")
        )
        val limit = call.argument<Number>("limit")?.toInt() ?: 20
        val offset = call.argument<Number>("offset")?.toInt() ?: 0
        if (conversationId <= 0L) {
            result.error("INVALID_ARGUMENTS", "conversationId is invalid", null)
            return
        }
        workJob.launch {
            try {
                val pagedResult = conversationDomainService.listConversationMessagesPaged(
                    conversationId = conversationId,
                    conversationMode = mode,
                    limit = limit,
                    offset = offset
                )
                withContext(Dispatchers.Main) {
                    result.success(pagedResult)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "分页获取对话消息失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_CONVERSATION_MESSAGES_PAGED_ERROR", e.message, null)
                }
            }
        }
    }

    fun replaceConversationMessages(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = call.argument<Number>("conversationId")?.toLong() ?: 0L
        val mode = normalizeConversationMode(
            call.argument<String>("mode") ?: call.argument<String>("conversationMode")
        )
        val messages = call.argument<List<Map<String, Any?>>>("messages") ?: emptyList()
        if (conversationId <= 0L) {
            result.error("INVALID_ARGUMENTS", "conversationId is invalid", null)
            return
        }
        workJob.launch {
            try {
                conversationDomainService.replaceConversationMessages(
                    conversationId = conversationId,
                    conversationMode = mode,
                    messages = messages
                )
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "替换对话消息失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("REPLACE_CONVERSATION_MESSAGES_ERROR", e.message, null)
                }
            }
        }
    }

    fun upsertConversationUiCard(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = call.argument<Number>("conversationId")?.toLong() ?: 0L
        val mode = normalizeConversationMode(
            call.argument<String>("mode") ?: call.argument<String>("conversationMode")
        )
        val entryId = call.argument<String>("entryId")?.trim().orEmpty()
        val cardData = call.argument<Map<String, Any?>>("cardData") ?: emptyMap()
        val createdAt = call.argument<Number>("createdAt")?.toLong()
        if (conversationId <= 0L) {
            result.error("INVALID_ARGUMENTS", "conversationId is invalid", null)
            return
        }
        if (entryId.isEmpty()) {
            result.error("INVALID_ARGUMENTS", "entryId is invalid", null)
            return
        }
        workJob.launch {
            try {
                conversationDomainService.upsertConversationUiCard(
                    conversationId = conversationId,
                    conversationMode = mode,
                    entryId = entryId,
                    cardData = cardData,
                    createdAt = createdAt ?: System.currentTimeMillis()
                )
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "保存 UI 卡片失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("UPSERT_CONVERSATION_UI_CARD_ERROR", e.message, null)
                }
            }
        }
    }

    fun compactConversationContext(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = call.argument<Number>("conversationId")?.toLong() ?: 0L
        val mode = normalizeConversationMode(
            call.argument<String>("mode") ?: call.argument<String>("conversationMode")
        )
        if (conversationId <= 0L) {
            result.error("INVALID_ARGUMENTS", "conversationId is invalid", null)
            return
        }
        val modelOverride = resolveAgentModelOverride(
            call.argument<Map<String, Any?>>("modelOverride")
        )
        val reasoningEffort = resolveAgentReasoningEffort(
            normalizeReasoningEffort(
                call.argument<String>("reasoningEffort")
            ),
            modelOverride
        )
        workJob.launch {
            try {
                val payload = conversationDomainService.compactConversationContext(
                    conversationId = conversationId,
                    conversationMode = mode,
                    modelOverride = modelOverride,
                    reasoningEffort = reasoningEffort
                )
                withContext(Dispatchers.Main) {
                    result.success(payload)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "手动压缩上下文失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("COMPACT_CONVERSATION_CONTEXT_ERROR", e.message, null)
                }
            }
        }
    }

    fun clearConversationMessages(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = call.argument<Number>("conversationId")?.toLong() ?: 0L
        val mode = normalizeConversationMode(
            call.argument<String>("mode") ?: call.argument<String>("conversationMode")
        )
        if (conversationId <= 0L) {
            result.error("INVALID_ARGUMENTS", "conversationId is invalid", null)
            return
        }
        workJob.launch {
            try {
                conversationDomainService.clearConversationMessages(
                    conversationId = conversationId,
                    conversationMode = mode
                )
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "清理对话消息失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("CLEAR_CONVERSATION_MESSAGES_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 分页获取对话列表
     */
    fun getConversationsByPage(call: MethodCall, result: MethodChannel.Result) {
        val offset = call.argument<Int>("offset") ?: 0
        val limit = call.argument<Int>("limit") ?: 20

        workJob.launch {
            try {
                val all = conversationDomainService.listConversationPayloads(
                    includeArchived = true
                )
                val jsonList = if (offset >= all.size) {
                    emptyList()
                } else {
                    all.subList(offset.coerceAtLeast(0), (offset + limit).coerceAtMost(all.size))
                }
                withContext(Dispatchers.Main) {
                    result.success(jsonList)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "分页获取对话列表失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GET_CONVERSATIONS_BY_PAGE_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 创建新对话
     */
    fun createConversation(call: MethodCall, result: MethodChannel.Result) {
        val title = call.argument<String>("title") ?: "新对话"
        val mode = normalizeConversationMode(call.argument<String>("mode"))
        val summary = call.argument<String>("summary")
        val parentConversationId = call.argument<Number>("parentConversationId")
            ?.toLong()
            ?.takeIf { it > 0L }
        val parentConversationMode = call.argument<String>("parentConversationMode")
        val scheduledTaskId = call.argument<String>("scheduledTaskId")
        val agentId = call.argument<String>("agentId")

        workJob.launch {
            try {
                val conversation = conversationDomainService.createConversation(
                    title = title,
                    mode = mode,
                    summary = summary,
                    parentConversationId = parentConversationId,
                    parentConversationMode = parentConversationMode,
                    scheduledTaskId = scheduledTaskId,
                    agentId = agentId
                )
                withContext(Dispatchers.Main) {
                    result.success((conversation["id"] as? Number)?.toLong())
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "创建对话失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("CREATE_CONVERSATION_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 更新对话
     */
    fun updateConversation(call: MethodCall, result: MethodChannel.Result) {
        val conversationMap = call.argument<Map<String, Any>>("conversation")

        workJob.launch {
            try {
                if (conversationMap != null) {
                    conversationDomainService.updateConversationFromPayload(
                        conversationMap.mapValues { it.value }
                    )
                    withContext(Dispatchers.Main) {
                        result.success("SUCCESS")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        result.error("INVALID_ARGUMENTS", "conversation is null", null)
                    }
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "更新对话失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("UPDATE_CONVERSATION_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 删除对话
     */
    fun deleteConversation(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = (call.argument<Int>("conversationId") ?: 0).toLong()

        workJob.launch {
            try {
                conversationDomainService.deleteConversation(conversationId)
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "删除对话失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("DELETE_CONVERSATION_ERROR", e.message, null)
                }
            }
        }
    }

    fun updateConversationPromptTokenThreshold(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = (call.argument<Number>("conversationId"))?.toLong()
        val promptTokenThreshold = (call.argument<Number>("promptTokenThreshold"))?.toInt()

        workJob.launch {
            try {
                if (conversationId == null || conversationId <= 0L || promptTokenThreshold == null) {
                    withContext(Dispatchers.Main) {
                        result.error(
                            "INVALID_ARGUMENTS",
                            "conversationId or promptTokenThreshold is invalid",
                            null
                        )
                    }
                    return@launch
                }
                conversationDomainService.updateConversationPromptTokenThreshold(
                    conversationId = conversationId,
                    promptTokenThreshold = promptTokenThreshold
                )
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "更新对话压缩阈值失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("UPDATE_CONVERSATION_THRESHOLD_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 更新对话标题
     */
    fun updateConversationTitle(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = (call.argument<Int>("conversationId") ?: 0).toLong()
        val newTitle = call.argument<String>("newTitle") ?: ""

        workJob.launch {
            try {
                conversationDomainService.updateConversationTitle(
                    conversationId = conversationId,
                    newTitle = newTitle
                )
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "更新对话标题失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("UPDATE_CONVERSATION_TITLE_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 生成对话摘要
     * 使用云端 qwen-flash 模型生成 10 字左右的摘要
     */
    fun generateConversationSummary(call: MethodCall, result: MethodChannel.Result) {
        val conversationHistory = call.argument<String>("conversationHistory") ?: ""

        workJob.launch {
            try {
                // 构建提示词，要求生成10字左右的摘要
                val prompt = """
                    你是一个聊天总结助手，请根据以下用户发送的对话内容，生成一个简洁的摘要标题，要求：
                    1. 摘要标题长度控制在10个字左右
                    2. 摘要标题应该体现对话的主要内容
                    3. 不要包含特殊字符和表情符号
                    4. 不要包含任何的人称用词

                    对话内容：
                    $conversationHistory

                    请直接返回摘要标题，不要包含其他内容。
                """.trimIndent()

                // 调用 LLM 生成摘要
                val llmResult = HttpController.postLLMRequest("scene.compactor.context.chat", prompt)
                val summary = llmResult.message
                    .trim()
                    .take(10)
                    .takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Conversation summary is empty")

                withContext(Dispatchers.Main) {
                    result.success(summary)
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "生成对话摘要失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("GENERATE_SUMMARY_ERROR", e.message, null)
                }
            }
        }
    }

    private fun conversationToMap(conversation: Conversation): Map<String, Any?> {
        return conversationDomainService.conversationToPayload(conversation)
    }

    private fun Map<String, Any>.readLong(key: String): Long? {
        return (this[key] as? Number)?.toLong()
    }

    private fun Map<String, Any>.readInt(key: String): Int? {
        return (this[key] as? Number)?.toInt()
    }

    fun setPreventScreenSleepDuringTasksEnabled(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        val enabled = call.argument<Boolean>("enabled") ?: true
        try {
            val success = TaskRuntimeSettings.setPreventSleepEnabled(context, enabled)
            if (success) {
                result.success("SUCCESS")
            } else {
                result.error("SAVE_PREVENT_SLEEP_SETTING_FAILED", "Failed to save prevent sleep setting", null)
            }
        } catch (e: Exception) {
            OmniLog.e(TAG, "save prevent sleep setting failed: ${e.message}")
            result.error("SAVE_PREVENT_SLEEP_SETTING_FAILED", e.message, null)
        }
    }

    fun setTaskCompletionNotificationEnabled(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        val enabled = call.argument<Boolean>("enabled") ?: true
        try {
            val success = TaskRuntimeSettings.setTaskCompletionNotificationEnabled(context, enabled)
            if (success) {
                result.success("SUCCESS")
            } else {
                result.error("SAVE_TASK_NOTIFICATION_SETTING_FAILED", "Failed to save task notification setting", null)
            }
        } catch (e: Exception) {
            OmniLog.e(TAG, "save task notification setting failed: ${e.message}")
            result.error("SAVE_TASK_NOTIFICATION_SETTING_FAILED", e.message, null)
        }
    }

    fun showTaskCompletionNotification(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        try {
            val title = call.argument<String>("title") ?: "Task completed"
            val message = call.argument<String>("message") ?: "Tap to view details."
            val conversationId = when (val raw = call.argument<Any>("conversationId")) {
                is Number -> raw.toLong()
                is String -> raw.toLongOrNull()
                else -> null
            }
            val conversationMode = call.argument<String>("conversationMode")
            TaskRuntimeSettings.notifyTaskFinished(
                context = context,
                title = title,
                message = message,
                conversationId = conversationId,
                conversationMode = conversationMode
            )
            result.success("SUCCESS")
        } catch (e: Exception) {
            OmniLog.e(TAG, "show task completion notification failed: ${e.message}")
            result.error("SHOW_TASK_NOTIFICATION_FAILED", e.message, null)
        }
    }

    fun setVisibleChatConversation(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        val visible = call.argument<Boolean>("visible") ?: true
        val conversationId = when (val raw = call.argument<Any>("conversationId")) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }?.takeIf { it > 0 }
        val mode = (call.argument<String>("mode") ?: "agent").trim().ifEmpty { "agent" }
        TaskRuntimeSettings.setVisibleConversation(context, conversationId, mode, visible)
        mainJob.launch(Dispatchers.Main) {
            result.success("SUCCESS")
        }
    }

    /**
     * 完成对话
     */
    fun completeConversation(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = (call.argument<Int>("conversationId") ?: 0).toLong()

        workJob.launch {
            try {
                conversationDomainService.completeConversation(conversationId)
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "完成对话失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("COMPLETE_CONVERSATION_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 设置当前活跃的对话ID
     */
    fun setCurrentConversationId(call: MethodCall, result: MethodChannel.Result) {
        val conversationId = (call.argument<Int>("conversationId") ?: 0).toLong()
        val mode = (call.argument<String>("mode") ?: "agent").trim().ifEmpty { "agent" }
        currentConversationId = if (conversationId > 0) conversationId else null
        currentConversationMode = mode
        mainJob.launch(Dispatchers.Main) {
            result.success("SUCCESS")
        }
    }

    /**
     * 授权完成后重新打开ChatBot半屏
     */
    fun reopenChatBotAfterAuth(result: MethodChannel.Result) {
        mainJob.launch(Dispatchers.Main) {
            try {
                UIKit.uiChatEvent?.showChatBotHalfScreen("resume_after_auth")
                result.success("SUCCESS")
            } catch (e: Exception) {
                OmniLog.e(TAG, "reopenChatBotAfterAuth failed: ${e.message}")
                result.error("REOPEN_ERROR", e.message, null)
            }
        }
    }
}
