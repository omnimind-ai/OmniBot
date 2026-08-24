package cn.com.omnimind.bot.manager

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import cn.com.omnimind.assists.api.bean.TaskParams
import cn.com.omnimind.assists.api.interfaces.OnMessagePushListener
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
import cn.com.omnimind.baselib.util.APPPackageUtil
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.RuntimeLogStore
import cn.com.omnimind.baselib.util.exception.PermissionException
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.activity.MainActivity
import cn.com.omnimind.bot.ui.scheduled.ScheduledTaskReminderLoader
import cn.com.omnimind.bot.util.AssistsUtil
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.util.SchemeUtil
import cn.com.omnimind.bot.util.TaskRuntimeSettings
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentAlarmToolService
import cn.com.omnimind.bot.agent.AgentConversationContextCompactor
import cn.com.omnimind.bot.agent.AgentImageAttachmentSupport
import cn.com.omnimind.bot.agent.AgentWorkspaceAttachmentSupport
import cn.com.omnimind.bot.agent.AgentStreamEvent
import cn.com.omnimind.bot.agent.AgentTextSanitizer
import cn.com.omnimind.bot.agent.AgentModelOverride
import cn.com.omnimind.bot.agent.AgentResult
import cn.com.omnimind.bot.agent.AgentConversationHistoryRepository
import cn.com.omnimind.bot.agent.AgentConversationHistorySupport
import cn.com.omnimind.bot.agent.AgentRuntimeContextRepository
import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import cn.com.omnimind.bot.agent.AgentRunControl
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolProgressSnapshot
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.LiveAgentBrowserSessionManager
import cn.com.omnimind.bot.agent.ManualToolStopCancellationException
import cn.com.omnimind.bot.agent.OmniAgentExecutor
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
import cn.com.omnimind.bot.webchat.RealtimeHub
import cn.com.omnimind.bot.workspace.PublicStorageAccess
import cn.com.omnimind.bot.workspace.WorkspaceStorageAccess
import cn.com.omnimind.uikit.UIKit
import com.google.gson.Gson
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.encodeToString

internal const val CHAT_ONLY_MODE = "chat_only"
private const val MAX_PERSISTED_THINKING_CHARS = 16 * 1024
private const val THINKING_TRUNCATION_NOTICE = "[Earlier reasoning omitted]\n"

private val chatTaskPayloadJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal fun prepareChatTaskContent(
    content: List<Map<String, Any>>,
    conversationMode: String,
    chatPromptContent: String?
): List<Map<String, Any>> {
    val prompt = chatPromptContent?.takeIf { it.trim().isNotEmpty() } ?: return content
    if (!conversationMode.equals(CHAT_ONLY_MODE, ignoreCase = true)) {
        return content
    }
    return buildList {
        add(
            linkedMapOf<String, Any>(
                "role" to "system",
                "content" to prompt
            )
        )
        addAll(content)
    }
}

internal fun resolveChatTaskModelOverride(
    raw: Map<String, Any?>?,
    profileLookup: (String) -> ModelProviderProfile?
): TaskParams.ChatModelOverride? {
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
    return TaskParams.ChatModelOverride(
        providerProfileId = providerProfile.id,
        modelId = modelId,
        apiBase = providerProfile.baseUrl,
        apiKey = providerProfile.apiKey,
        customHeaders = providerProfile.customHeaders,
        protocolType = providerProfile.protocolType.ifEmpty { "openai_compatible" },
        wireApi = providerProfile.wireApi,
        contextLimit = contextLimit
    )
}

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
    return AgentModelOverride(
        providerProfileId = providerProfile.id,
        providerProfileName = providerProfile.name,
        modelId = modelId,
        apiBase = providerProfile.baseUrl,
        apiKey = providerProfile.apiKey,
        customHeaders = providerProfile.customHeaders,
        protocolType = providerProfile.protocolType.ifEmpty { "openai_compatible" },
        wireApi = providerProfile.wireApi,
        contextLimit = contextLimit
    )
}

internal fun resolvePromptTokenThresholdFallback(
    storedThreshold: Int?,
    modelOverride: TaskParams.ChatModelOverride?
): Int {
    return AgentConversationContextCompactor.resolveEffectiveContextCapacity(
        storedThreshold = storedThreshold,
        modelContextLimit = modelOverride?.contextLimit
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

internal data class AgentTurnUsageSnapshot(
    val ctxTokens: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheTokens: Int,
    val totalInputTokens: Int,
    val uncachedInputTokens: Int,
    val cacheReadTokens: Int,
    val cacheWriteTokens: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val promptTokenThreshold: Int?
) {
    fun toPayload(): Map<String, Any?> {
        return linkedMapOf(
            "ctx" to ctxTokens,
            "in" to inputTokens,
            "out" to outputTokens,
            "cache" to cacheTokens,
            "totalInputTokens" to totalInputTokens,
            "uncachedInputTokens" to uncachedInputTokens,
            "cacheReadTokens" to cacheReadTokens,
            "cacheWriteTokens" to cacheWriteTokens,
            "promptTokens" to promptTokens,
            "completionTokens" to completionTokens,
            "totalTokens" to totalTokens,
            "promptTokenThreshold" to promptTokenThreshold
        )
    }
}

internal fun buildTurnUsageSnapshot(
    latestPromptTokens: Int?,
    promptTokenThreshold: Int?,
    result: AgentResult.Success?
): AgentTurnUsageSnapshot? {
    val promptTokens = latestPromptTokens ?: result?.latestPromptTokens ?: return null
    val completionTokens = result?.completionTokens ?: 0
    val cacheTokens = (result?.cachedTokens ?: 0).coerceIn(0, promptTokens)
    val cacheWriteTokens = (result?.cacheCreationTokens ?: 0).coerceAtLeast(0)
    val totalTokens = result?.totalTokens ?: (promptTokens + completionTokens)
    val ctxTokens = promptTokens
    return AgentTurnUsageSnapshot(
        ctxTokens = ctxTokens,
        inputTokens = promptTokens,
        outputTokens = completionTokens,
        cacheTokens = cacheTokens,
        totalInputTokens = promptTokens,
        uncachedInputTokens = (promptTokens - cacheTokens).coerceAtLeast(0),
        cacheReadTokens = cacheTokens,
        cacheWriteTokens = cacheWriteTokens,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        promptTokenThreshold = promptTokenThreshold ?: result?.promptTokenThreshold
    )
}

internal fun buildTurnUsageSnapshot(
    latestPromptTokens: Int?,
    promptTokenThreshold: Int?,
    completionTokens: Int,
    cachedTokens: Int
): AgentTurnUsageSnapshot? {
    val promptTokens = latestPromptTokens ?: return null
    val totalTokens = promptTokens + completionTokens
    val cacheTokens = cachedTokens.coerceIn(0, promptTokens)
    val ctxTokens = promptTokens
    return AgentTurnUsageSnapshot(
        ctxTokens = ctxTokens,
        inputTokens = promptTokens,
        outputTokens = completionTokens,
        cacheTokens = cacheTokens,
        totalInputTokens = promptTokens,
        uncachedInputTokens = (promptTokens - cacheTokens).coerceAtLeast(0),
        cacheReadTokens = cacheTokens,
        cacheWriteTokens = 0,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        promptTokenThreshold = promptTokenThreshold
    )
}

internal const val AGENT_MANUAL_CANCELLATION_SEQUENCE = 1_000_000_000L
internal const val AGENT_MANUAL_CANCELLATION_ROUND = 1_000_000_000

internal fun buildAgentManualCancellationStreamMeta(
    taskId: String,
    entryId: String
): Map<String, Any?> {
    return linkedMapOf(
        "seq" to AGENT_MANUAL_CANCELLATION_SEQUENCE,
        "roundIndex" to AGENT_MANUAL_CANCELLATION_ROUND,
        "kind" to "text_snapshot",
        "parentTaskId" to taskId,
        "entryId" to entryId,
        "isFinal" to true
    )
}

internal fun extractChatTaskTextPayload(content: String): String {
    val normalized = content.trim()
    if (normalized.isEmpty() || normalized == "[DONE]") {
        return ""
    }
    if (!normalized.startsWith("{") && !normalized.startsWith("[")) {
        return content
    }
    val parsed = runCatching {
        extractChatTaskTextValue(chatTaskPayloadJson.parseToJsonElement(normalized))
    }.getOrElse { "" }
    if (parsed.isNotEmpty()) {
        return parsed
    }

    val contentMatch = Regex(""""(?:content|text)"\s*:\s*"((?:\\.|[^"\\])*)"""")
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { raw ->
            runCatching {
                chatTaskPayloadJson.parseToJsonElement(""""$raw"""")
                    .jsonPrimitive
                    .content
            }.getOrDefault(raw)
        }
        .orEmpty()
    return contentMatch
}

private fun extractChatTaskTextValue(raw: JsonElement?): String {
    return when (raw) {
        null, JsonNull -> ""
        is JsonPrimitive -> raw.contentOrNull.orEmpty()
        is JsonArray -> raw.joinToString(separator = "") { item ->
            extractChatTaskTextValue(item)
        }
        is JsonObject -> {
            val directText = extractTextPayload(raw["text"])
            if (directText.isNotEmpty()) {
                return directText
            }

            val outputText = extractTextPayload(raw["output_text"])
            if (outputText.isNotEmpty()) {
                return outputText
            }

            val contentText = extractTextPayload(raw["content"])
            if (contentText.isNotEmpty()) {
                return contentText
            }

            val messageText = extractChatTaskTextValue(raw["message"])
            if (messageText.isNotEmpty()) {
                return messageText
            }

            val choices = raw["choices"] as? JsonArray
            if (choices != null && choices.isNotEmpty()) {
                val firstChoice = choices.firstOrNull() as? JsonObject
                if (firstChoice != null) {
                    val deltaText = extractChatTaskTextValue(firstChoice["delta"])
                    if (deltaText.isNotEmpty()) {
                        return deltaText
                    }

                    val choiceMessageText = extractChatTaskTextValue(firstChoice["message"])
                    if (choiceMessageText.isNotEmpty()) {
                        return choiceMessageText
                    }

                    val choiceText = extractTextPayload(
                        firstChoice["text"] ?: firstChoice["content"]
                    )
                    if (choiceText.isNotEmpty()) {
                        return choiceText
                    }
                }
            }

            val output = raw["output"] as? JsonArray
            if (output != null && output.isNotEmpty()) {
                val outputTextFromList = output.joinToString(separator = "") { item ->
                    extractChatTaskTextValue(item)
                }
                if (outputTextFromList.isNotEmpty()) {
                    return outputTextFromList
                }
            }

            ""
        }
        else -> ""
    }
}

internal fun extractChatTaskPromptTokens(content: String): Int? {
    val normalized = content.trim()
    if (normalized.isEmpty() || normalized == "[DONE]") {
        return null
    }
    return Regex("\"prompt_tokens\"\\s*:\\s*(\\d+)")
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}

internal fun extractChatTaskCompletionTokens(content: String): Int? {
    val normalized = content.trim()
    if (normalized.isEmpty() || normalized == "[DONE]") return null
    return Regex("\"completion_tokens\"\\s*:\\s*(\\d+)")
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}

internal fun extractChatTaskCachedTokens(content: String): Int? {
    val normalized = content.trim()
    if (normalized.isEmpty() || normalized == "[DONE]") return null
    return Regex("\"cached_tokens\"\\s*:\\s*(\\d+)")
        .find(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}

internal fun chatModelOverrideToAgentModelOverride(
    modelOverride: TaskParams.ChatModelOverride?
): AgentModelOverride? {
    if (modelOverride == null) {
        return null
    }
    val providerProfileName = runCatching {
        ModelProviderConfigStore.getProfile(modelOverride.providerProfileId)?.name
            ?: PlatformAiProvisioner.officialProfileOrNull()
                ?.takeIf {
                    OmniOfficialProvider.isOfficialProfile(modelOverride.providerProfileId)
                }
                ?.name
    }.getOrNull()
    return AgentModelOverride(
        providerProfileId = modelOverride.providerProfileId,
        providerProfileName = providerProfileName,
        modelId = modelOverride.modelId,
        apiBase = modelOverride.apiBase,
        apiKey = modelOverride.apiKey,
        customHeaders = modelOverride.customHeaders,
        protocolType = modelOverride.protocolType.ifEmpty { "openai_compatible" },
        wireApi = modelOverride.wireApi,
        contextLimit = modelOverride.contextLimit
    )
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

class AssistsCoreManager(private val context: Context) : OnMessagePushListener {
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
        private val TERMINAL_ENV_KEY_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
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

    private data class ChatTaskPersistenceState(
        val conversationId: Long,
        val conversationMode: String,
        val userEntryId: String,
        val assistantEntryId: String,
        val modelOverride: TaskParams.ChatModelOverride? = null,
        val reasoningEffort: String? = null,
        val assistantBuffer: StringBuilder = StringBuilder(),
        var isError: Boolean = false,
        var latestPromptTokens: Int? = null,
        var promptTokenThreshold: Int? = null,
        var completionTokens: Int? = null,
        var cachedTokens: Int? = null
    )

    private data class FailedAgentRetryContext(
        val arguments: Map<String, Any?>
    )

    private data class FailedAgentContinueContext(
        val arguments: Map<String, Any?>
    )

    private class ActiveAgentRunContext(
        val taskId: String,
        val job: Job,
        val conversationId: Long?,
        val conversationMode: String
    ) : AgentRunControl {
        private val lock = Any()
        private var generationCounter = 0L
        private var activeTool: ManagedToolExecutionHandle? = null

        override fun beginToolExecution(
            toolName: String,
            toolCallId: String
        ): AgentToolExecutionHandle {
            return synchronized(lock) {
                ManagedToolExecutionHandle(
                    owner = this,
                    generation = ++generationCounter,
                    toolName = toolName,
                    toolCallId = toolCallId
                ).also { handle ->
                    activeTool = handle
                }
            }
        }

        fun bindActiveToolCardId(cardId: String) {
            synchronized(lock) {
                activeTool?.bindCardId(cardId)
            }
        }

        suspend fun requestManualToolStop(cardId: String): Boolean {
            val handle = synchronized(lock) {
                activeTool?.takeIf { it.matchesCardId(cardId) }
            } ?: return false
            return handle.requestManualStop()
        }

        fun clearTool(handle: ManagedToolExecutionHandle) {
            synchronized(lock) {
                if (activeTool === handle) {
                    activeTool = null
                }
            }
        }
    }

    private class ManagedToolExecutionHandle(
        private val owner: ActiveAgentRunContext,
        override val generation: Long,
        override val toolName: String,
        override val toolCallId: String
    ) : AgentToolExecutionHandle {
        private val lock = Any()
        private var cardId: String? = null
        private var job: Job? = null
        private var stopAction: (suspend () -> Unit)? = null
        private var latestSnapshot = AgentToolProgressSnapshot()
        private var completed = false
        private var manualStopRequested = false

        override fun bindCardId(cardId: String) {
            synchronized(lock) {
                this.cardId = cardId.trim().ifEmpty { null }
            }
        }

        fun matchesCardId(expectedCardId: String): Boolean {
            val normalized = expectedCardId.trim()
            if (normalized.isEmpty()) {
                return false
            }
            return synchronized(lock) {
                cardId == normalized && !completed
            }
        }

        override fun currentCardId(): String? {
            return synchronized(lock) { cardId }
        }

        override fun bindExecutionJob(job: Job) {
            synchronized(lock) {
                this.job = job
            }
        }

        override fun bindStopAction(action: (suspend () -> Unit)?) {
            synchronized(lock) {
                stopAction = action
            }
        }

        override fun recordProgress(summary: String, extras: Map<String, Any?>) {
            synchronized(lock) {
                if (!manualStopRequested) {
                    latestSnapshot = AgentToolProgressSnapshot(
                        summary = summary,
                        extras = LinkedHashMap(extras)
                    )
                }
            }
        }

        override fun latestProgressSnapshot(): AgentToolProgressSnapshot {
            return synchronized(lock) { latestSnapshot }
        }

        override fun isManualStopRequested(): Boolean {
            return synchronized(lock) { manualStopRequested }
        }

        override fun throwIfStopRequested() {
            if (isManualStopRequested()) {
                throw ManualToolStopCancellationException()
            }
        }

        suspend fun requestManualStop(): Boolean {
            val currentStopAction: (suspend () -> Unit)?
            val currentJob: Job?
            synchronized(lock) {
                if (completed) {
                    return false
                }
                if (manualStopRequested) {
                    return true
                }
                manualStopRequested = true
                currentStopAction = stopAction
                currentJob = job
            }
            runCatching {
                currentStopAction?.invoke()
            }.onFailure {
                OmniLog.w("[AssistsCoreManager]", "manual tool stop action failed: ${it.message}")
            }
            currentJob?.cancel(ManualToolStopCancellationException())
            return true
        }

        override fun complete() {
            synchronized(lock) {
                completed = true
                stopAction = null
                job = null
            }
            owner.clearTool(this)
        }
    }

    // 用于存储需要等待用户操作的回调结果
    private lateinit var channel: MethodChannel
    private var mainJob: CoroutineScope = CoroutineScope(Dispatchers.Main)
    private var workJob: CoroutineScope = CoroutineScope(Dispatchers.Default)
    private val activeAgentLock = Any()

    private val activeAgentRuns: MutableMap<String, ActiveAgentRunContext> = mutableMapOf()
    private val failedAgentRetryContexts: MutableMap<String, FailedAgentRetryContext> = mutableMapOf()
    private val failedAgentContinueContexts: MutableMap<String, FailedAgentContinueContext> = mutableMapOf()
    private val chatTaskPersistenceStates: MutableMap<String, ChatTaskPersistenceState> =
        mutableMapOf()
    private val conversationDomainService by lazy { ConversationDomainService(context) }

    // 当前活跃的对话ID
    private var currentConversationId: Long? = null
    private var currentConversationMode: String = "normal"

    private fun registerActiveAgentRun(taskId: String, context: ActiveAgentRunContext) {
        synchronized(activeAgentLock) {
            activeAgentRuns[taskId] = context
        }
    }

    private fun registerChatTaskPersistenceState(taskId: String, state: ChatTaskPersistenceState) {
        synchronized(activeAgentLock) {
            chatTaskPersistenceStates[taskId] = state
        }
    }

    private fun registerFailedAgentRetryContext(taskId: String, context: FailedAgentRetryContext) {
        synchronized(activeAgentLock) {
            failedAgentRetryContexts[taskId] = context
        }
    }

    private fun registerFailedAgentContinueContext(taskId: String, context: FailedAgentContinueContext) {
        synchronized(activeAgentLock) {
            failedAgentContinueContexts[taskId] = context
        }
    }

    private fun getFailedAgentRetryContext(taskId: String): FailedAgentRetryContext? {
        return synchronized(activeAgentLock) {
            failedAgentRetryContexts[taskId]
        }
    }

    private fun getFailedAgentContinueContext(taskId: String): FailedAgentContinueContext? {
        return synchronized(activeAgentLock) {
            failedAgentContinueContexts[taskId]
        }
    }

    private fun removeFailedAgentRetryContext(taskId: String): FailedAgentRetryContext? {
        return synchronized(activeAgentLock) {
            failedAgentRetryContexts.remove(taskId)
        }
    }

    private fun removeFailedAgentContinueContext(taskId: String): FailedAgentContinueContext? {
        return synchronized(activeAgentLock) {
            failedAgentContinueContexts.remove(taskId)
        }
    }

    private fun getChatTaskPersistenceState(taskId: String): ChatTaskPersistenceState? {
        return synchronized(activeAgentLock) {
            chatTaskPersistenceStates[taskId]
        }
    }

    private fun removeChatTaskPersistenceState(taskId: String): ChatTaskPersistenceState? {
        return synchronized(activeAgentLock) {
            chatTaskPersistenceStates.remove(taskId)
        }
    }

    private fun clearActiveAgentJob(taskId: String, job: Job) {
        synchronized(activeAgentLock) {
            if (activeAgentRuns[taskId]?.job == job) {
                activeAgentRuns.remove(taskId)
            }
        }
    }

    private fun cancelActiveAgentRun(taskId: String?, reason: String) {
        val runsToCancel = synchronized(activeAgentLock) {
            if (taskId.isNullOrBlank()) {
                val snapshot = activeAgentRuns.values.toList()
                activeAgentRuns.clear()
                snapshot
            } else {
                val current = activeAgentRuns.remove(taskId)
                if (current == null) emptyList() else listOf(current)
            }
        }
        if (runsToCancel.isNotEmpty()) {
            OmniLog.i(TAG, "Cancelling active agent run(s): $reason taskId=$taskId")
            runsToCancel.forEach { run ->
                publishManualAgentCancellation(run)
                run.job.cancel(CancellationException(reason))
            }
        }
    }

    private fun publishManualAgentCancellation(run: ActiveAgentRunContext) {
        val conversationId = run.conversationId ?: return
        val cancelledText = when (AppLocaleManager.resolvePromptLocale(context)) {
            PromptLocale.EN_US -> "Task canceled"
            PromptLocale.ZH_CN -> "任务已取消"
        }
        val entryId = "${run.taskId}-cancelled"
        val now = System.currentTimeMillis()
        val streamMeta = buildAgentManualCancellationStreamMeta(run.taskId, entryId)
        workJob.launch {
            runCatching {
                val repository = conversationHistoryRepository()
                repository.upsertAssistantMessage(
                    conversationId = conversationId,
                    conversationMode = run.conversationMode,
                    entryId = entryId,
                    text = cancelledText,
                    isError = false,
                    streamMeta = streamMeta,
                    createdAt = now
                )
                withContext(Dispatchers.Main) {
                    invokeFlutterEventSafely(
                        "onAgentStreamEvent",
                        sanitizeInteropMap(
                            mapOf(
                                "taskId" to run.taskId,
                                "seq" to AGENT_MANUAL_CANCELLATION_SEQUENCE,
                                "kind" to "text_snapshot",
                                "entryId" to entryId,
                                "roundIndex" to AGENT_MANUAL_CANCELLATION_ROUND,
                                "isFinal" to true,
                                "text" to cancelledText,
                                "createdAt" to now,
                                "streamMeta" to streamMeta
                            )
                        )
                    )
                }
            }.onFailure {
                OmniLog.w(TAG, "publish manual agent cancellation failed: ${it.message}", it)
            }
        }
    }

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

    fun setChannel(_channel: MethodChannel) {
        OmniLog.d(TAG, "setChannel")
        this.channel = _channel
        FlutterChatSyncBridge.bindCurrentChannel(_channel)
    }

    private fun currentChannelOrNull(): MethodChannel? {
        return if (this::channel.isInitialized) channel else null
    }

    /**
     * 统一的 Flutter 事件派发：
     * 1) 始终在主线程调用；
     * 2) 同时投递到当前通道和主引擎通道；
     * 3) 避免事件派发异常导致进程崩溃。
     */
    private fun invokeFlutterEventSafely(method: String, arguments: Any? = null) {
        val current = currentChannelOrNull()
        val main = mainEngineChannel
        val channels = listOfNotNull(current, main).distinct()
        if (channels.isEmpty()) {
            OmniLog.w(TAG, "skip invoke $method: flutter channel unavailable")
            return
        }

        var lastError: Exception? = null
        var delivered = false
        for (target in channels) {
            try {
                target.invokeMethod(method, arguments)
                delivered = true
            } catch (e: Exception) {
                lastError = e
                OmniLog.e(TAG, "invoke $method failed on one channel: ${e.message}")
            }
        }
        if (!delivered) {
            OmniLog.e(TAG, "invoke $method failed on all channels: ${lastError?.message}")
        }
    }

    fun hasActiveAgentRuns(): Boolean {
        return synchronized(activeAgentLock) {
            activeAgentRuns.isNotEmpty()
        }
    }

    fun activeAgentTaskIds(): List<String> {
        return synchronized(activeAgentLock) {
            activeAgentRuns.keys.toList()
        }
    }

    fun hasActiveAgentRun(
        conversationId: Long,
        conversationMode: String
    ): Boolean {
        val normalizedMode = conversationMode.trim().lowercase().ifEmpty { "normal" }
        return synchronized(activeAgentLock) {
            activeAgentRuns.values.any { run ->
                run.conversationId == conversationId &&
                    run.conversationMode.trim().lowercase() == normalizedMode
            }
        }
    }

    fun hasActiveChatTasks(): Boolean {
        return synchronized(activeAgentLock) {
            chatTaskPersistenceStates.isNotEmpty()
        }
    }

    fun hasActiveChatRun(
        conversationId: Long,
        conversationMode: String
    ): Boolean {
        val normalizedMode = normalizeConversationMode(conversationMode)
        return synchronized(activeAgentLock) {
            chatTaskPersistenceStates.values.any { state ->
                state.conversationId == conversationId &&
                    state.conversationMode == normalizedMode
            }
        }
    }

    suspend fun invokeFlutterMethodForAgent(method: String, arguments: Map<String, Any?>): Any? {
        val targetChannel = mainEngineChannel ?: if (this::channel.isInitialized) channel else null
        if (targetChannel == null) {
            throw IllegalStateException("Flutter channel unavailable for $method")
        }
        return suspendCancellableCoroutine { continuation ->
            mainJob.launch(Dispatchers.Main) {
                try {
                    targetChannel.invokeMethod(method, arguments, object : MethodChannel.Result {
                        override fun success(result: Any?) {
                            if (!continuation.isCompleted) {
                                continuation.resume(result)
                            }
                        }

                        override fun error(
                            errorCode: String,
                            errorMessage: String?,
                            errorDetails: Any?
                        ) {
                            if (!continuation.isCompleted) {
                                continuation.resumeWithException(
                                    IllegalStateException(
                                        "$errorCode: ${errorMessage ?: "Flutter bridge error"}"
                                    )
                                )
                            }
                        }

                        override fun notImplemented() {
                            if (!continuation.isCompleted) {
                                continuation.resumeWithException(
                                    NotImplementedError("Flutter method not implemented: $method")
                                )
                            }
                        }
                    })
                } catch (e: Exception) {
                    if (!continuation.isCompleted) {
                        continuation.resumeWithException(e)
                    }
                }
            }
        }
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
                status = "success"
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
        return mode?.trim()?.ifEmpty { null } ?: "normal"
    }

    private fun resolveRequiredPermissionIds(missing: List<String>): List<String> {
        val nameToId = linkedMapOf(
            "悬浮窗权限" to "overlay",
            "Overlay" to "overlay",
            "应用列表读取权限" to "installed_apps",
            "Installed Apps Access" to "installed_apps",
            "Shizuku 权限" to "shizuku",
            "Shizuku Permission" to "shizuku",
            WorkspaceStorageAccess.REQUIRED_PERMISSION_NAME to "workspace_storage",
            PublicStorageAccess.REQUIRED_PERMISSION_NAME to "public_storage",
            "Public Storage Access" to "public_storage"
        )
        return missing.mapNotNull { raw ->
            nameToId[raw.trim()]
        }.distinct()
    }

    private fun buildPermissionCardData(requiredPermissionIds: List<String>): Map<String, Any?> {
        return linkedMapOf(
            "type" to "permission_section",
            "requiredPermissionIds" to requiredPermissionIds
        )
    }

    private fun extractChatTaskText(content: String): String = extractChatTaskTextPayload(content)

    private suspend fun publishChatTaskMessagesSync(
        state: ChatTaskPersistenceState,
        finalizeInterruptedEntries: Boolean
    ) {
        val messages = conversationHistoryRepository().listConversationMessages(
            conversationId = state.conversationId,
            conversationMode = state.conversationMode,
            finalizeInterruptedEntries = finalizeInterruptedEntries
        )
        RealtimeHub.publish(
            "messages_replaced",
            mapOf(
                "conversationId" to state.conversationId,
                "mode" to state.conversationMode,
                "messages" to messages
            )
        )
        FlutterChatSyncBridge.dispatchConversationMessagesChanged(
            conversationId = state.conversationId,
            mode = state.conversationMode,
            reason = "chat_task_stream_snapshot"
        )
    }

    private fun publishChatTaskTerminalEvent(
        taskId: String,
        state: ChatTaskPersistenceState,
        kind: String
    ) {
        RealtimeHub.publish(
            "agent_stream_event",
            mapOf(
                "taskId" to taskId,
                "conversationId" to state.conversationId,
                "conversationMode" to state.conversationMode,
                "kind" to kind
            )
        )
    }


    /**
     * 取消正在运行的聊天或 Agent 任务。
     */
    fun cancelRunningTask(
        call: MethodCall, result: MethodChannel.Result,
    ) {
        mainJob.launch {
            try {
                val taskId = call.argument<String>("taskId")
                cancelActiveAgentRun(taskId, "cancelRunningTask")
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "cancelRunningTask error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("CANCEL_RUNNING_TASK_ERROR", e.message, null)
                }
            }
        }
    }

    fun stopAgentToolCall(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        mainJob.launch {
            try {
                val taskId = call.argument<String>("taskId")?.trim().orEmpty()
                val cardId = call.argument<String>("cardId")?.trim().orEmpty()
                if (taskId.isBlank() || cardId.isBlank()) {
                    withContext(Dispatchers.Main) {
                        result.error(
                            "INVALID_ARGUMENTS",
                            "taskId and cardId are required",
                            null
                        )
                    }
                    return@launch
                }
                val runContext = synchronized(activeAgentLock) {
                    activeAgentRuns[taskId]
                }
                val stopped = runContext?.requestManualToolStop(cardId) == true
                withContext(Dispatchers.Main) {
                    if (stopped) {
                        result.success("SUCCESS")
                    } else {
                        result.error(
                            "NO_MATCHING_ACTIVE_TOOL",
                            "No running tool matches cardId=$cardId",
                            null
                        )
                    }
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "stopAgentToolCall error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("STOP_AGENT_TOOL_CALL_ERROR", e.message, null)
                }
            }
        }
    }

    fun retryAgentTask(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        mainJob.launch {
            try {
                val taskId = call.argument<String>("taskId")?.trim().orEmpty()
                if (taskId.isBlank()) {
                    withContext(Dispatchers.Main) {
                        result.error("INVALID_ARGUMENTS", "taskId is required", null)
                    }
                    return@launch
                }
                val retryContext = getFailedAgentRetryContext(taskId)
                if (retryContext == null) {
                    withContext(Dispatchers.Main) {
                        result.error(
                            "NO_RETRY_CONTEXT",
                            "No retryable agent context found for taskId=$taskId",
                            null
                        )
                    }
                    return@launch
                }
                createAgentTask(
                    MethodCall("createAgentTask", retryContext.arguments),
                    result
                )
            } catch (e: Exception) {
                OmniLog.e(TAG, "retryAgentTask error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("RETRY_AGENT_TASK_ERROR", e.message, null)
                }
            }
        }
    }

    fun continueAgentTask(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        mainJob.launch {
            try {
                val taskId = call.argument<String>("taskId")?.trim().orEmpty()
                if (taskId.isBlank()) {
                    withContext(Dispatchers.Main) {
                        result.error("INVALID_ARGUMENTS", "taskId is required", null)
                    }
                    return@launch
                }
                val continueContext = getFailedAgentContinueContext(taskId)
                if (continueContext != null) {
                    handleCreateOrContinueAgentTask(
                        MethodCall("createAgentTask", continueContext.arguments),
                        result,
                        isContinue = true
                    )
                    return@launch
                }
                val retryContext = getFailedAgentRetryContext(taskId)
                if (retryContext != null) {
                    val continueArgs = retryContext.arguments.toMutableMap()
                    continueArgs["continueMode"] = true
                    continueArgs["continueResumeMode"] = "approximate"
                    handleCreateOrContinueAgentTask(
                        MethodCall("createAgentTask", continueArgs),
                        result,
                        isContinue = true
                    )
                    return@launch
                }
                val currentRun = synchronized(activeAgentLock) { activeAgentRuns[taskId] }
                val conversationId = currentRun?.conversationId
                    ?: call.argument<Number>("conversationId")?.toLong()
                    ?: 0L
                val conversationMode = normalizeConversationMode(
                    call.argument<String>("conversationMode") ?: currentRun?.conversationMode
                )
                if (conversationId <= 0L) {
                    withContext(Dispatchers.Main) {
                        result.error("NO_CONTINUE_CONTEXT", "No conversation context found", null)
                    }
                    return@launch
                }
                val repository = conversationHistoryRepository()
                val messages = repository.listConversationMessages(
                    conversationId = conversationId,
                    conversationMode = conversationMode
                )
                val assistantEntries = messages.filter { entry ->
                    (entry["user"] as? Number)?.toInt() == 2 &&
                        (entry["type"] as? Number)?.toInt() != 2
                }
                val lastAssistant = assistantEntries.lastOrNull() ?: run {
                    withContext(Dispatchers.Main) {
                        result.error(
                            "NO_CONTINUE_CONTEXT",
                            "No resumable assistant turn found for taskId=$taskId",
                            null
                        )
                    }
                    return@launch
                }
                val lastUser = messages.lastOrNull { entry ->
                    (entry["user"] as? Number)?.toInt() == 1
                } ?: run {
                    withContext(Dispatchers.Main) {
                        result.error(
                            "NO_CONTINUE_CONTEXT",
                            "No user message found for continuation",
                            null
                        )
                    }
                    return@launch
                }
                val userMessage = (lastUser["content"] as? Map<*, *>)
                    ?.get("text")
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                // 进程重启或 context 被清等场景下走到这里:in-memory 的
                // FailedAgentContinueContext 丢了,只能从 DB 反查"已经续跑过几代"。
                // 看 thinking entry id 后缀里的 -c$N 取最大值;新一代 = max + 1。
                // 没找到任何带后缀的 → max=0 → 新一代=1。
                val continueSuffixRegex = Regex("-c(\\d+)$")
                val maxExistingGeneration = messages.asSequence()
                    .mapNotNull { it["id"]?.toString() }
                    .filter { it.startsWith("$taskId-thinking") || it.startsWith("$taskId-tool") }
                    .mapNotNull { continueSuffixRegex.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                    .maxOrNull()
                    ?: 0
                val continueArgs = linkedMapOf<String, Any?>(
                    "taskId" to taskId,
                    "userMessage" to userMessage,
                    "conversationId" to conversationId,
                    "conversationMode" to conversationMode,
                    "continueMode" to true,
                    "continueResumeMode" to "approximate",
                    "continueFromAssistantEntryId" to lastAssistant["id"]?.toString(),
                    "continueFromAssistantText" to (lastAssistant["content"] as? Map<*, *>)?.get("text")
                        ?.toString()
                        .orEmpty(),
                    "continueTurnUsage" to (lastAssistant["turnUsage"] as? Map<*, *>)?.let(::sanitizeInteropValue),
                    // 写入 parent generation;handleCreateOrContinueAgentTask 会 +1 得到新一代。
                    "continueGeneration" to maxExistingGeneration
                )
                registerFailedAgentContinueContext(
                    taskId,
                    FailedAgentContinueContext(arguments = sanitizeInteropMap(continueArgs))
                )
                handleCreateOrContinueAgentTask(
                    MethodCall("createAgentTask", continueArgs),
                    result,
                    isContinue = true
                )
            } catch (e: Exception) {
                OmniLog.e(TAG, "continueAgentTask error: ${e.message}")
                withContext(Dispatchers.Main) {
                    result.error("CONTINUE_AGENT_TASK_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 取消聊天任务
     */
    fun cancelChatTask(
        call: MethodCall, result: MethodChannel.Result,
    ) {
        mainJob.launch {
            try {
                val taskId = call.argument<String>("taskId")
                cancelActiveAgentRun(taskId, "cancelChatTask")
                AssistsUtil.Core.cancelChatTask(taskId)
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("CANCEL_MESSAGE_ERROR", e.message, null)
                }
            }
        }
    }

    /**
     * 创建聊天任务
     */
    fun createChatTask(
        call: MethodCall, result: MethodChannel.Result,
    ) {
        val taskID = call.argument<String>("taskID") ?: ""
        val content = call.argument<List<Map<String, Any>>>("content") ?: emptyList()
        val provider = call.argument<String>("provider")
        val conversationId = call.argument<Number>("conversationId")?.toLong()
        val conversationMode = normalizeConversationMode(call.argument<String>("conversationMode"))
        val userMessage = call.argument<String>("userMessage")?.trim().orEmpty()
        val requestedUserMessageCreatedAt =
            call.argument<Number>("userMessageCreatedAt")?.toLong()?.takeIf { it > 0L }
        val externalUserMessage = call.argument<Boolean>("externalUserMessage") == true
        val rawUserAttachments = (call.argument<List<Map<String, Any?>>>("userAttachments") ?: emptyList())
            .map(::sanitizeInteropMap)
        val userAttachments = AgentImageAttachmentSupport.prepareAttachments(
            AgentWorkspaceAttachmentSupport.prepareAttachmentsForRuntime(
                context = context,
                taskId = taskID,
                rawAttachments = rawUserAttachments
            )
        ).historyAttachments
        val modelOverride = resolveChatTaskModelOverride(
            call.argument<Map<String, Any?>>("modelOverride"),
            ::lookupRuntimeProviderProfile
        )
        val reasoningEffort = normalizeReasoningEffort(
            call.argument<String>("reasoningEffort")
        )
        val openClawConfigMap = call.argument<Map<String, Any>>("openClawConfig")
        val openClawConfig = openClawConfigMap?.let { map ->
            val baseUrl = map["baseUrl"] as? String ?: ""
            if (baseUrl.isBlank()) {
                null
            } else {
                cn.com.omnimind.assists.api.bean.TaskParams.OpenClawConfig(
                    baseUrl = baseUrl,
                    token = map["token"] as? String,
                    userId = map["userId"] as? String,
                    sessionKey = map["sessionKey"] as? String
                )
            }
        }

        mainJob.launch {
            try {
                TaskRuntimeSettings.onTaskStarted(context)
                val workspaceMemoryService = WorkspaceMemoryService(context)
                val preparedContent = prepareChatTaskContent(
                    content = content,
                    conversationMode = conversationMode,
                    chatPromptContent = workspaceMemoryService.readChatPrompt()
                )
                val normalizedConversationId = conversationId?.takeIf { it > 0L }
                if (normalizedConversationId != null) {
                    val repository = conversationHistoryRepository()
                    if (userMessage.isNotBlank() || userAttachments.isNotEmpty()) {
                        val userMessageCreatedAt =
                            requestedUserMessageCreatedAt ?: System.currentTimeMillis()
                        repository.upsertUserMessage(
                            conversationId = normalizedConversationId,
                            conversationMode = conversationMode,
                            entryId = "$taskID-user",
                            text = userMessage,
                            attachments = userAttachments,
                            streamMeta = if (externalUserMessage) {
                                AgentConversationHistorySupport.externalUserMessageStreamMeta()
                            } else {
                                null
                            },
                            createdAt = userMessageCreatedAt
                        )
                        if (externalUserMessage) {
                            FlutterChatSyncBridge.dispatchExternalUserMessageAppended(
                                conversationId = normalizedConversationId,
                                mode = conversationMode,
                                entryId = "$taskID-user",
                                text = userMessage,
                                attachments = userAttachments,
                                createdAt = userMessageCreatedAt
                            )
                        }
                    }
                    val persistenceState = ChatTaskPersistenceState(
                        conversationId = normalizedConversationId,
                        conversationMode = conversationMode,
                        userEntryId = "$taskID-user",
                        assistantEntryId = "$taskID-assistant",
                        modelOverride = modelOverride,
                        reasoningEffort = reasoningEffort,
                        promptTokenThreshold = resolvePromptTokenThresholdFallback(
                            storedThreshold = repository
                                .getConversation(normalizedConversationId)
                                ?.promptTokenThreshold,
                            modelOverride = modelOverride
                        )
                    )
                    registerChatTaskPersistenceState(taskID, persistenceState)
                    publishChatTaskMessagesSync(
                        persistenceState,
                        finalizeInterruptedEntries = false
                    )
                }
                AssistsUtil.Core.createChatTask(
                    taskID,
                    preparedContent,
                    this@AssistsCoreManager,
                    provider,
                    openClawConfig,
                    modelOverride,
                    reasoningEffort,
                    cn.com.omnimind.baselib.llm.PromptCacheKeyStore.forConversation(
                        context,
                        normalizedConversationId
                    )
                )
                withContext(Dispatchers.Main) {
                    result.success("SUCCESS")
                }
            } catch (e: PermissionException) {
                removeChatTaskPersistenceState(taskID)
                TaskRuntimeSettings.onTaskFinished(context)
                withContext(Dispatchers.Main) {
                    result.error("PERMISSION_ERROR", e.message, null)
                }
            } catch (e: Exception) {
                removeChatTaskPersistenceState(taskID)
                TaskRuntimeSettings.onTaskFinished(context)
                withContext(Dispatchers.Main) {
                    result.error("DO_TASK_ERROR", e.message, null)
                }
            }
        }

    }


    override suspend fun onChatMessage(taskID: String, content: String, type: String?) {
        getChatTaskPersistenceState(taskID)?.let { state ->
            val repository = conversationHistoryRepository()
            val normalizedType = type?.trim()?.lowercase().orEmpty()
            if (
                state.conversationMode.equals(CHAT_ONLY_MODE, ignoreCase = true) &&
                normalizedType != "error" &&
                normalizedType != "rate_limited"
            ) {
                extractChatTaskPromptTokens(content)?.let { promptTokens ->
                    val promptTokenThreshold = resolvePromptTokenThresholdFallback(
                        storedThreshold = state.promptTokenThreshold,
                        modelOverride = state.modelOverride
                    )
                    state.latestPromptTokens = promptTokens
                    state.promptTokenThreshold = promptTokenThreshold
                    extractChatTaskCompletionTokens(content)?.let { state.completionTokens = it }
                    extractChatTaskCachedTokens(content)?.let { state.cachedTokens = it }
                    repository.updatePromptTokenUsage(
                        conversationId = state.conversationId,
                        promptTokens = promptTokens,
                        threshold = promptTokenThreshold
                    )
                    withContext(Dispatchers.Main) {
                        invokeFlutterEventSafely(
                            "onAgentPromptTokenUsageChanged",
                            mapOf(
                                "taskId" to taskID,
                                "conversationId" to state.conversationId,
                                "conversationMode" to state.conversationMode,
                                "latestPromptTokens" to promptTokens,
                                "promptTokenThreshold" to promptTokenThreshold
                            )
                        )
                    }
                }
            }
            when (normalizedType) {
                "openclaw_attachment" -> Unit
                "error",
                "rate_limited" -> {
                    val message = extractChatTaskText(content).ifBlank {
                        content.trim().ifBlank {
                            if (normalizedType == "rate_limited") {
                                "请求过于频繁，请稍后重试。"
                            } else {
                                "网络异常，请稍后重试。"
                            }
                        }
                    }
                    state.assistantBuffer.setLength(0)
                    state.assistantBuffer.append(message)
                    state.isError = true
                }
                else -> {
                    val message = extractChatTaskText(content)
                    if (message.isNotEmpty()) {
                        state.assistantBuffer.append(message)
                    }
                    state.isError = false
                }
            }
            val snapshot = state.assistantBuffer.toString().trim()
            if (snapshot.isNotEmpty()) {
                repository.upsertAssistantMessage(
                    conversationId = state.conversationId,
                    conversationMode = state.conversationMode,
                    entryId = state.assistantEntryId,
                    text = snapshot,
                    isError = state.isError
                )
            }
            publishChatTaskMessagesSync(
                state,
                finalizeInterruptedEntries = false
            )
        }
        withContext(Dispatchers.Main) {
            try {
                val isSummary = isSummaryTask(taskID)
                val mainChannel = mainEngineChannel

                if (isSummary && mainChannel != null && mainChannel != channel) {
                    mainChannel.invokeMethod(
                        "onChatMessage", mapOf(
                            "taskID" to taskID, "content" to content, "type" to type
                        )
                    )
                    // 如果当前不是主引擎通道，避免在半屏重复展示
                    return@withContext
                }

                channel.invokeMethod(
                    "onChatMessage", mapOf(
                        "taskID" to taskID, "content" to content, "type" to type
                    )
                )
            } catch (e: Exception) {
                OmniLog.e(TAG, "onChatMessage error: ${e.message}")
            }

        }
    }

    override suspend fun onChatMessageEnd(taskID: String) {
        val persistenceState = removeChatTaskPersistenceState(taskID)
        persistenceState?.let { state ->
            val snapshot = state.assistantBuffer.toString().trim()
            if (snapshot.isNotEmpty()) {
                conversationHistoryRepository().upsertAssistantMessage(
                    conversationId = state.conversationId,
                    conversationMode = state.conversationMode,
                    entryId = state.assistantEntryId,
                    text = snapshot,
                    isError = state.isError
                )
            }
            publishChatTaskMessagesSync(
                state,
                finalizeInterruptedEntries = true
            )
            publishChatTaskTerminalEvent(
                taskId = taskID,
                state = state,
                kind = if (state.isError) "error" else "completed"
            )
        }
        val compactedConversationPayload = maybeAutoCompactChatOnlyConversation(
            taskID,
            persistenceState
        )
        val turnUsagePayload = persistenceState?.let { state ->
            buildTurnUsageSnapshot(
                latestPromptTokens = state.latestPromptTokens,
                promptTokenThreshold = state.promptTokenThreshold,
                completionTokens = state.completionTokens ?: 0,
                cachedTokens = state.cachedTokens ?: 0
            )?.toPayload()
        }
        withContext(Dispatchers.Main) {
            try {
                val isSummary = isSummaryTask(taskID)
                val mainChannel = mainEngineChannel

                if (isSummary && mainChannel != null && mainChannel != channel) {
                    mainChannel.invokeMethod(
                        "onChatMessageEnd", mapOf(
                            "taskID" to taskID,
                            "turnUsage" to turnUsagePayload
                        )
                    )
                    // 如果当前不是主引擎通道，避免在半屏重复展示
                    return@withContext
                }

                channel.invokeMethod(
                    "onChatMessageEnd", mapOf(
                        "taskID" to taskID,
                        "turnUsage" to turnUsagePayload
                    )
                )
            } catch (e: Exception) {
                OmniLog.e(TAG, "onChatMessageEnd error: ${e.message}")
            }

        }
        compactedConversationPayload?.let { payload ->
            FlutterChatSyncBridge.dispatchConversationListChanged(
                reason = "conversation_updated",
                conversation = payload
            )
        }
        TaskRuntimeSettings.onTaskFinished(context)
        if (persistenceState?.isError != true) {
            TaskRuntimeSettings.notifyTaskFinished(
                context = context,
                title = "小万回复已完成",
                message = "纯聊天回复已完成，点击查看详情",
                conversationId = persistenceState?.conversationId,
                conversationMode = persistenceState?.conversationMode
            )
        }
    }

    private suspend fun maybeAutoCompactChatOnlyConversation(
        taskId: String,
        state: ChatTaskPersistenceState?
    ): Map<String, Any?>? {
        if (state == null || state.isError) {
            return null
        }
        if (!state.conversationMode.equals(CHAT_ONLY_MODE, ignoreCase = true)) {
            return null
        }
        val latestPromptTokens = state.latestPromptTokens ?: return null
        val promptTokenThreshold = resolvePromptTokenThresholdFallback(
            storedThreshold = state.promptTokenThreshold,
            modelOverride = state.modelOverride
        )
        if (latestPromptTokens <= promptTokenThreshold) {
            return null
        }

        val repository = conversationHistoryRepository()
        val candidate = repository.getContextCompactionCandidate(
            conversationId = state.conversationId,
            conversationMode = state.conversationMode
        ) ?: return null
        if (candidate.entriesToCompact.isEmpty()) {
            return null
        }

        withContext(Dispatchers.Main) {
            invokeFlutterEventSafely(
                "onAgentContextCompactionStateChanged",
                mapOf(
                    "taskId" to taskId,
                    "conversationId" to state.conversationId,
                    "conversationMode" to state.conversationMode,
                    "isCompacting" to true,
                    "latestPromptTokens" to latestPromptTokens,
                    "promptTokenThreshold" to promptTokenThreshold
                )
            )
        }

        var conversationPayload: Map<String, Any?>? = null
        try {
            val payload = ConversationDomainService(context).compactConversationContext(
                conversationId = state.conversationId,
                conversationMode = state.conversationMode,
                modelOverride = chatModelOverrideToAgentModelOverride(state.modelOverride),
                reasoningEffort = state.reasoningEffort
            )
            @Suppress("UNCHECKED_CAST")
            conversationPayload = payload["conversation"] as? Map<String, Any?>
        } catch (e: Exception) {
            OmniLog.w(TAG, "纯聊天自动压缩失败: ${e.message}")
        } finally {
            withContext(Dispatchers.Main) {
                invokeFlutterEventSafely(
                    "onAgentContextCompactionStateChanged",
                    mapOf(
                        "taskId" to taskId,
                        "conversationId" to state.conversationId,
                        "conversationMode" to state.conversationMode,
                        "isCompacting" to false,
                        "latestPromptTokens" to latestPromptTokens,
                        "promptTokenThreshold" to promptTokenThreshold
                    )
                )
            }
        }
        return conversationPayload
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
                ModelProviderConfigStore.clearConfig()
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
                OmniLog.e(TAG, "fetchProviderModels failed")
                withContext(Dispatchers.Main) {
                    result.error(
                        "FETCH_PROVIDER_MODELS_ERROR",
                        "Provider model fetch failed.",
                        null
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
    private fun parseTerminalEnvironmentMap(raw: Map<String, Any?>?): Map<String, String> {
        if (raw.isNullOrEmpty()) {
            return emptyMap()
        }
        val normalized = linkedMapOf<String, String>()
        raw.forEach { (rawKey, rawValue) ->
            val key = rawKey.trim()
            if (key.isEmpty() || !TERMINAL_ENV_KEY_PATTERN.matches(key)) {
                return@forEach
            }
            normalized.remove(key)
            normalized[key] = rawValue?.toString() ?: ""
        }
        return normalized
    }

    private fun resolveAgentModelOverride(raw: Map<String, Any?>?): AgentModelOverride? {
        return resolveDirectAgentModelOverride(raw, ::lookupRuntimeProviderProfile)
    }

    fun createAgentTask(call: MethodCall, result: MethodChannel.Result) {
        handleCreateOrContinueAgentTask(call, result, isContinue = false)
    }

    private fun handleCreateOrContinueAgentTask(
        call: MethodCall,
        result: MethodChannel.Result,
        isContinue: Boolean
    ) {
        val rawCallArguments = (call.arguments as? Map<*, *>)
            ?.entries
            ?.filter { it.key != null }
            ?.associate { it.key.toString() to it.value }
            ?: emptyMap()
        val taskId = (call.argument<String>("taskId") ?: "").trim()
        val userMessage = AgentTextSanitizer.sanitizeUtf16(
            (call.argument<String>("userMessage") ?: "").toString()
        )
        val legacyConversationHistory =
            call.argument<List<Map<String, Any?>>>("conversationHistory") ?: emptyList()
        val rawAttachments = (call.argument<List<Map<String, Any?>>>("attachments") ?: emptyList())
            .map(::sanitizeInteropMap)
        val normalizedAttachments = AgentWorkspaceAttachmentSupport.prepareAttachmentsForRuntime(
            context = context,
            taskId = taskId,
            rawAttachments = rawAttachments
        )
        val preparedAttachments = AgentImageAttachmentSupport.prepareAttachments(normalizedAttachments)
        val runtimeAttachments = preparedAttachments.runtimeAttachments
        val historyAttachments = preparedAttachments.historyAttachments
        val userMessageCreatedAt = call.argument<Number>("userMessageCreatedAt")?.toLong()
        val externalUserMessage = call.argument<Boolean>("externalUserMessage") == true
        val userEntryId = call.argument<String>("userEntryId")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: userMessageCreatedAt
                ?.takeIf { it > 0L }
                ?.let { "$it-user" }
            ?: "$taskId-user"
        val conversationId = call.argument<Number>("conversationId")?.toLong()?.takeIf { it > 0L }
        val requestedConversationMode =
            call.argument<String>("conversationMode")?.trim()?.ifEmpty { null }
        val resolvedConversationMode = normalizeConversationMode(
            requestedConversationMode ?: currentConversationMode
        )
        val scheduledSubagentMeta = parseScheduledSubagentRunMeta(
            conversationMode = resolvedConversationMode,
            conversationId = conversationId,
            call = call
        )
        val modelOverride = resolveAgentModelOverride(
            call.argument<Map<String, Any?>>("modelOverride")
        )
        val reasoningEffort = resolveAgentReasoningEffort(
            normalizeReasoningEffort(
                call.argument<String>("reasoningEffort")
            ),
            modelOverride
        )
        val terminalEnvironment = parseTerminalEnvironmentMap(
            call.argument<Map<String, Any?>>("terminalEnvironment")
        )
        val continueMode = isContinue || call.argument<Boolean>("continueMode") == true
        val continueResumeMode = call.argument<String>("continueResumeMode")
            ?.trim()
            ?.ifEmpty { null }
            ?: (if (continueMode) "approximate" else null)
        val continueFromAssistantEntryId = call.argument<String>("continueFromAssistantEntryId")
            ?.trim()
            ?.ifEmpty { null }
        val continueFromAssistantText = call.argument<String>("continueFromAssistantText")
            ?.let(AgentTextSanitizer::sanitizeUtf16)
            ?.trim()
            ?.ifEmpty { null }
        val continueTurnUsage = call.argument<Map<String, Any?>>("continueTurnUsage")
            ?.let(::sanitizeInteropMap)
        // 续跑代数:每次点 Continue 触发的新 run 都 +1,用来给 thinking 和 tool entry id
        // 加 -c$gen 后缀,避免与上一次 run 的卡片碰撞导致前端"被替代"。
        // 0 = 首次任务(非续跑),无后缀,保持向后兼容。
        val parentContinueGeneration =
            call.argument<Number>("continueGeneration")?.toInt() ?: 0
        val continueGeneration = if (isContinue || continueMode) {
            (parentContinueGeneration + 1).coerceAtLeast(1)
        } else {
            0
        }
        if (taskId.isBlank()) {
            result.error("INVALID_ARGUMENTS", "taskId is empty", null)
            return
        }
        removeFailedAgentRetryContext(taskId)
        removeFailedAgentContinueContext(taskId)
        if (legacyConversationHistory.isNotEmpty()) {
            OmniLog.d(
                TAG,
                "Ignoring legacy conversationHistory for createAgentTask taskId=$taskId size=${legacyConversationHistory.size}"
            )
        }
        val retryArguments = sanitizeInteropMap(
            rawCallArguments + mapOf(
                "taskId" to taskId,
                "userMessage" to userMessage,
                "attachments" to rawAttachments,
                "userMessageCreatedAt" to userMessageCreatedAt,
                "conversationId" to conversationId,
                "conversationMode" to resolvedConversationMode,
                "reasoningEffort" to reasoningEffort,
                "terminalEnvironment" to terminalEnvironment,
                "continueMode" to continueMode,
                "continueResumeMode" to continueResumeMode,
                "continueFromAssistantEntryId" to continueFromAssistantEntryId,
                "continueFromAssistantText" to continueFromAssistantText,
                "continueTurnUsage" to continueTurnUsage,
                "continueGeneration" to continueGeneration
            )
        )
        val agentRunJob = SupervisorJob()
        val agentRunScope = CoroutineScope(agentRunJob + Dispatchers.Default)
        val agentRunContext = ActiveAgentRunContext(
            taskId = taskId,
            job = agentRunJob,
            conversationId = conversationId,
            conversationMode = resolvedConversationMode
        )
        registerActiveAgentRun(taskId, agentRunContext)
        TaskRuntimeSettings.onTaskStarted(context)

        agentRunScope.launch {
            var historyRepository: AgentConversationHistoryRepository? = null
            try {
                val runtimeContextRepository = AgentRuntimeContextRepository(context)
                historyRepository = conversationHistoryRepository()
                val repository = historyRepository ?: return@launch
                if (continueMode) {
                    registerFailedAgentContinueContext(
                        taskId,
                        FailedAgentContinueContext(arguments = retryArguments)
                    )
                }

                val scheduleBridge = object : AgentScheduleToolBridge {
                    override suspend fun createTask(arguments: Map<String, Any?>): Map<String, Any?> {
                        val enrichedArguments = enrichScheduledSubagentParent(
                            arguments = arguments,
                            parentConversationId = conversationId,
                            parentConversationMode = resolvedConversationMode
                        )
                        return toStringAnyMap(
                            invokeFlutterMethodForAgent("agentScheduleCreate", enrichedArguments)
                        )
                    }

                    override suspend fun listTasks(): List<Map<String, Any?>> {
                        return toListOfStringAnyMap(
                            invokeFlutterMethodForAgent("agentScheduleList", emptyMap())
                        )
                    }

                    override suspend fun updateTask(arguments: Map<String, Any?>): Map<String, Any?> {
                        val enrichedArguments = enrichScheduledSubagentParent(
                            arguments = arguments,
                            parentConversationId = conversationId,
                            parentConversationMode = resolvedConversationMode
                        )
                        return toStringAnyMap(
                            invokeFlutterMethodForAgent("agentScheduleUpdate", enrichedArguments)
                        )
                    }

                    override suspend fun deleteTask(arguments: Map<String, Any?>): Map<String, Any?> {
                        return toStringAnyMap(
                            invokeFlutterMethodForAgent("agentScheduleDelete", arguments)
                        )
                    }
                }

                // 2. 初始化 Executor
                val executor = OmniAgentExecutor(context, agentRunScope, scheduleBridge)
                fun asLong(value: Any?): Long? {
                    return when (value) {
                        is Number -> value.toLong()
                        is String -> value.trim().toLongOrNull()
                        else -> value?.toString()?.trim()?.toLongOrNull()
                    }
                }

                val initialEntryOrderSeqs = mutableMapOf<String, Long>()
                var initialStreamSequence = 0L
                var initialEntrySequence = 0L
                if (conversationId != null) {
                    runCatching {
                        repository.listConversationMessages(
                            conversationId = conversationId,
                            conversationMode = resolvedConversationMode
                        )
                    }.onSuccess { existingMessages ->
                        existingMessages.forEach { message ->
                            val streamMeta = toStringAnyMap(message["streamMeta"])
                            if (streamMeta["parentTaskId"]?.toString()?.trim() != taskId) {
                                return@forEach
                            }
                            asLong(streamMeta["seq"])?.let { seq ->
                                initialStreamSequence = maxOf(initialStreamSequence, seq)
                            }
                            val entrySeq = asLong(streamMeta["entrySeq"])
                            if (entrySeq != null) {
                                initialEntrySequence = maxOf(initialEntrySequence, entrySeq)
                                val entryId = streamMeta["entryId"]?.toString()?.trim()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?: message["id"]?.toString()?.trim()
                                        ?.takeIf { it.isNotEmpty() }
                                if (entryId != null) {
                                    initialEntryOrderSeqs[entryId] = entrySeq
                                }
                            }
                        }
                    }.onFailure { error ->
                        OmniLog.w(
                            TAG,
                            "seed agent stream sequence failed for taskId=$taskId: ${error.message}",
                            error
                        )
                    }
                }
                val activeToolArgs = mutableMapOf<String, ArrayDeque<String>>()
                val activeToolEntryIds = mutableMapOf<String, ArrayDeque<String>>()
                val toolEntryIdsByCallId = mutableMapOf<String, String>()
                val toolArgsByCallId = mutableMapOf<String, String>()
                val thinkingCardStartTimes = mutableMapOf<String, Long>()
                val entryCreatedAtTimes = mutableMapOf<String, Long>()
                val entryOrderSeqs = initialEntryOrderSeqs
                val scheduledAssistantBuffer = StringBuilder()
                var toolSequence = 0
                var eventSequence = initialStreamSequence
                var persistedStreamSequence = initialStreamSequence
                var entrySequence = initialEntrySequence
                var activeThinkingEntryId: String? = null
                var activeAssistantEntryId: String? = null
                var thinkingRound = 0
                var assistantRound = 0
                var latestThinkingContent = ""
                var latestAssistantVisibleText = ""
                var shouldStartNewAssistantRound = false
                val assistantReasoningByEntryId = mutableMapOf<String, String>()
                var lastAssistantEntryId: String? = null
                var lastAssistantRoundIndex = 0

                fun parseRoundFromAssistantEntryId(entryId: String): Int? {
                    val baseId = "$taskId-text"
                    return when {
                        entryId == baseId -> 1
                        entryId.startsWith("$baseId-") ->
                            entryId.removePrefix("$baseId-").toIntOrNull()
                        else -> null
                    }
                }

                // 续跑:只复用失败那一轮的 assistant entry id / round,让第一帧新内容直接落
                // 进旧 bubble。pending 标志在 ensureAssistantEntry 首次命中后清零,
                // 避免后续轮次被误锁。
                //
                // 注意几个故意不 preset 的状态:
                // - thinkingRound 留 0:新 run 自己起算,resolveThinkingEntryId 再叠上
                //   continueGeneration 的 -c 后缀,确保不和旧 thinking 卡碰撞。
                // - latestAssistantVisibleText 留空:onComplete 的 finalText fallback 不
                //   应该回退到旧错误文案("Failed to connect..."),否则续跑万一啥都没
                //   流出来时,DB 还会被旧错误文案重新覆盖。
                var continueEntryPending = false
                if (continueMode && continueFromAssistantEntryId != null) {
                    val parsedRound = parseRoundFromAssistantEntryId(continueFromAssistantEntryId)
                    if (parsedRound != null && parsedRound >= 1) {
                        activeAssistantEntryId = continueFromAssistantEntryId
                        assistantRound = parsedRound
                        lastAssistantEntryId = continueFromAssistantEntryId
                        lastAssistantRoundIndex = parsedRound
                        continueEntryPending = true
                    }
                }

                fun pushToolValue(
                    store: MutableMap<String, ArrayDeque<String>>,
                    toolName: String,
                    value: String
                ) {
                    store.getOrPut(toolName) { ArrayDeque() }.addLast(value)
                }

                fun peekToolValue(
                    store: MutableMap<String, ArrayDeque<String>>,
                    toolName: String
                ): String {
                    return store[toolName]?.lastOrNull().orEmpty()
                }

                fun popToolValue(
                    store: MutableMap<String, ArrayDeque<String>>,
                    toolName: String
                ): String {
                    val queue = store[toolName] ?: return ""
                    val value = if (queue.isEmpty()) "" else queue.removeLast()
                    if (queue.isEmpty()) {
                        store.remove(toolName)
                    }
                    return value
                }

                fun removeToolValue(
                    store: MutableMap<String, ArrayDeque<String>>,
                    toolName: String,
                    value: String
                ) {
                    val queue = store[toolName] ?: return
                    queue.removeLastOccurrence(value)
                    if (queue.isEmpty()) {
                        store.remove(toolName)
                    }
                }

                suspend fun publishConversationMessagesSync() {
                    val normalizedConversationId = conversationId ?: return
                    val repository = historyRepository ?: return
                    try {
                        // 流式快照仍属于活跃任务，不能在读取时按“中断恢复”收尾思考/工具条目。
                        val messages = repository.listConversationMessages(
                            conversationId = normalizedConversationId,
                            conversationMode = resolvedConversationMode,
                            finalizeInterruptedEntries = false
                        )
                        RealtimeHub.publish(
                            "messages_replaced",
                            mapOf(
                                "conversationId" to normalizedConversationId,
                                "mode" to resolvedConversationMode,
                                "messages" to messages
                            )
                        )
                        FlutterChatSyncBridge.dispatchConversationMessagesChanged(
                            conversationId = normalizedConversationId,
                            mode = resolvedConversationMode,
                            reason = "agent_stream_snapshot"
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        OmniLog.w(
                            TAG,
                            "publish conversation messages failed: ${error.message}",
                            error
                        )
                    }
                }

                suspend fun persistConversationMutation(
                    description: String,
                    publish: Boolean = true,
                    block: suspend () -> Unit
                ): Boolean {
                    val persisted = try {
                        block()
                        true
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        OmniLog.w(TAG, "$description failed: ${error.message}", error)
                        false
                    }
                    if (persisted && publish) {
                        publishConversationMessagesSync()
                    }
                    return persisted
                }

                // 续跑代数后缀,用于隔离 thinking / tool entry id,避免新 run 的卡片
                // 用 entryId 命中失败 run 已有的卡片导致前端"被替代"。
                // assistant 文本 entry id 故意不加这个后缀:那条 bubble 我们要主动复用旧 id,
                // 让新内容原地落入失败那条 bubble。
                val continueGenerationSuffix =
                    if (continueGeneration > 0) "-c$continueGeneration" else ""

                fun resolveThinkingEntryId(round: Int): String {
                    val base = if (round <= 1) {
                        "$taskId-thinking"
                    } else {
                        "$taskId-thinking-$round"
                    }
                    return base + continueGenerationSuffix
                }

                fun resolveAssistantEntryId(round: Int): String {
                    return if (round <= 1) {
                        "$taskId-text"
                    } else {
                        "$taskId-text-$round"
                    }
                }

                fun nextEventSeq(): Long {
                    eventSequence += 1
                    return eventSequence
                }

                fun resolveEntryOrderSeq(entryId: String): Long {
                    return entryOrderSeqs.getOrPut(entryId) {
                        entrySequence += 1
                        entrySequence
                    }
                }

                fun nextPersistedStreamSeq(): Long {
                    persistedStreamSequence += 1
                    return persistedStreamSequence
                }

                fun buildStreamMeta(
                    entryId: String,
                    roundIndex: Int,
                    kind: String,
                    seq: Long,
                    isFinal: Boolean = false
                ): Map<String, Any?> {
                    val entrySeq = resolveEntryOrderSeq(entryId)
                    val meta = linkedMapOf<String, Any?>(
                        "seq" to seq,
                        "entrySeq" to entrySeq,
                        "roundIndex" to roundIndex,
                        "kind" to kind,
                        "parentTaskId" to taskId,
                        "entryId" to entryId
                    )
                    if (isFinal) {
                        meta["isFinal"] = true
                    }
                    return meta
                }

                fun persistedStreamMeta(
                    entryId: String,
                    roundIndex: Int,
                    kind: String,
                    isFinal: Boolean = false
                ): Map<String, Any?> {
                    return buildStreamMeta(
                        entryId = entryId,
                        roundIndex = roundIndex,
                        kind = kind,
                        seq = nextPersistedStreamSeq(),
                        isFinal = isFinal
                    )
                }

                fun eventStreamMeta(
                    entryId: String,
                    roundIndex: Int,
                    kind: String,
                    eventSeq: Long
                ): Map<String, Any?> {
                    return buildStreamMeta(
                        entryId = entryId,
                        roundIndex = roundIndex,
                        kind = kind,
                        seq = eventSeq
                    )
                }

                fun rememberAssistantEntry(entryId: String, roundIndex: Int) {
                    if (entryId.isBlank()) return
                    lastAssistantEntryId = entryId
                    lastAssistantRoundIndex = roundIndex.coerceAtLeast(1)
                }

                fun captureAssistantReasoningForEntry(entryId: String): String? {
                    if (entryId.isBlank()) return null
                    assistantReasoningByEntryId[entryId]
                        ?.takeIf { it.isNotBlank() }
                        ?.let { return it }
                    val normalizedReasoning = AgentTextSanitizer.sanitizeUtf16(
                        latestThinkingContent
                    ).trim()
                    if (normalizedReasoning.isBlank()) {
                        return null
                    }
                    assistantReasoningByEntryId[entryId] = normalizedReasoning
                    return normalizedReasoning
                }

                fun markAssistantRoundBoundary() {
                    if (activeAssistantEntryId != null || assistantRound > 0) {
                        shouldStartNewAssistantRound = true
                        activeAssistantEntryId = null
                        scheduledAssistantBuffer.setLength(0)
                    }
                }

                fun ensureAssistantEntry(forceNewRound: Boolean = false): Pair<Int, String> {
                    // 续跑首次复用旧 entryId,忽略 forceNewRound (调用方常按 assistantRound>0 触发新轮,
                    // 但续跑时 assistantRound>0 是从失败那一轮还原下来的,不是真的"已经走完一轮"。)
                    if (continueEntryPending && activeAssistantEntryId != null) {
                        continueEntryPending = false
                        shouldStartNewAssistantRound = false
                        val entryId = activeAssistantEntryId!!
                        entryCreatedAtTimes.putIfAbsent(entryId, System.currentTimeMillis())
                        rememberAssistantEntry(entryId, assistantRound)
                        return assistantRound to entryId
                    }
                    if (activeAssistantEntryId == null || shouldStartNewAssistantRound || forceNewRound) {
                        assistantRound = (assistantRound + 1).coerceAtLeast(1)
                        activeAssistantEntryId = resolveAssistantEntryId(assistantRound)
                        shouldStartNewAssistantRound = false
                        scheduledAssistantBuffer.setLength(0)
                    }
                    val entryId = activeAssistantEntryId!!
                    entryCreatedAtTimes.putIfAbsent(entryId, System.currentTimeMillis())
                    rememberAssistantEntry(entryId, assistantRound)
                    return assistantRound to entryId
                }

                fun currentToolRoundIndex(): Int {
                    return maxOf(thinkingRound, assistantRound, 1)
                }

                fun buildDeepThinkingCardData(
                    thinkingContent: String,
                    isLoading: Boolean,
                    stage: Int,
                    startTime: Long,
                    endTime: Long?
                ): Map<String, Any?> {
                    val sanitizedThinking = AgentTextSanitizer.sanitizeUtf16(thinkingContent)
                    val originalLength = sanitizedThinking.length
                    val persistedThinking = if (originalLength <= MAX_PERSISTED_THINKING_CHARS) {
                        sanitizedThinking
                    } else {
                        val bodyLimit = (MAX_PERSISTED_THINKING_CHARS - THINKING_TRUNCATION_NOTICE.length)
                            .coerceAtLeast(0)
                        AgentTextSanitizer.sanitizeUtf16(
                            THINKING_TRUNCATION_NOTICE + sanitizedThinking.takeLast(bodyLimit)
                        )
                    }
                    val truncated = persistedThinking.length < originalLength
                    return linkedMapOf(
                        "type" to "deep_thinking",
                        "isLoading" to isLoading,
                        "thinkingContent" to persistedThinking,
                        "thinkingContentTruncated" to truncated,
                        "thinkingOriginalLength" to originalLength,
                        "thinkingTruncateMode" to if (truncated) "head_omitted" else "none",
                        "stage" to stage,
                        "taskID" to taskId,
                        "startTime" to startTime,
                        "endTime" to endTime,
                        "isCollapsible" to true
                    )
                }

                suspend fun upsertThinkingCard(
                    entryId: String,
                    roundIndex: Int,
                    thinkingContent: String,
                    isLoading: Boolean,
                    stage: Int,
                    createdAt: Long = thinkingCardStartTimes[entryId] ?: System.currentTimeMillis(),
                    streamKind: String = "thinking_snapshot",
                    endTime: Long? = null,
                    publish: Boolean = true
                ) {
                    val normalizedConversationId = conversationId ?: return
                    if (entryId.isBlank()) return
                    val startTime = thinkingCardStartTimes.getOrPut(entryId) { createdAt }
                    persistConversationMutation(
                        description = "upsert thinking card",
                        publish = publish
                    ) {
                        repository.upsertUiCard(
                            conversationId = normalizedConversationId,
                            conversationMode = resolvedConversationMode,
                            entryId = entryId,
                            cardData = buildDeepThinkingCardData(
                                thinkingContent = thinkingContent,
                                isLoading = isLoading,
                                stage = stage,
                                startTime = startTime,
                                endTime = endTime
                            ),
                            streamMeta = persistedStreamMeta(
                                entryId = entryId,
                                roundIndex = roundIndex,
                                kind = streamKind
                            ),
                            createdAt = startTime
                        )
                    }
                }

                suspend fun finalizeThinkingCardIfNeeded(publish: Boolean = true) {
                    val entryId = activeThinkingEntryId ?: return
                    upsertThinkingCard(
                        entryId = entryId,
                        roundIndex = thinkingRound.coerceAtLeast(1),
                        thinkingContent = latestThinkingContent,
                        isLoading = false,
                        stage = 4,
                        streamKind = "thinking_snapshot",
                        endTime = System.currentTimeMillis(),
                        publish = publish
                    )
                }

                suspend fun upsertAssistantSnapshot(
                    entryId: String,
                    roundIndex: Int,
                    text: String,
                    isError: Boolean,
                    interruptedTurn: Boolean = false,
                    streamKind: String = "text_snapshot",
                    isFinal: Boolean = false,
                    usageSnapshot: AgentTurnUsageSnapshot? = null,
                    reasoningContent: String? = null
                ) {
                    val normalizedConversationId = conversationId ?: return
                    val normalizedText = AgentTextSanitizer.sanitizeUtf16(text).trim()
                    if (normalizedText.isEmpty()) return
                    val createdAt = entryCreatedAtTimes.getOrPut(entryId) {
                        System.currentTimeMillis()
                    }
                    persistConversationMutation("upsert assistant snapshot") {
                        repository.upsertAssistantMessage(
                            conversationId = normalizedConversationId,
                            conversationMode = resolvedConversationMode,
                            entryId = entryId,
                            text = normalizedText,
                            isError = isError,
                            interruptedTurn = interruptedTurn,
                            reasoningContent = reasoningContent
                                ?.takeIf { it.isNotBlank() }
                                ?.let(AgentTextSanitizer::sanitizeUtf16),
                            streamMeta = persistedStreamMeta(
                                entryId = entryId,
                                roundIndex = roundIndex,
                                kind = streamKind,
                                isFinal = isFinal
                            ),
                            turnUsage = usageSnapshot?.toPayload(),
                            createdAt = createdAt
                        )
                    }
                }

                suspend fun upsertClarifyMessage(
                    entryId: String,
                    roundIndex: Int,
                    question: String
                ) {
                    val normalizedConversationId = conversationId ?: return
                    val normalizedQuestion = AgentTextSanitizer.sanitizeUtf16(question).trim()
                    if (normalizedQuestion.isEmpty()) return
                    val createdAt = entryCreatedAtTimes.getOrPut(entryId) {
                        System.currentTimeMillis()
                    }
                    persistConversationMutation("upsert clarify message") {
                        repository.upsertAssistantMessage(
                            conversationId = normalizedConversationId,
                            conversationMode = resolvedConversationMode,
                            entryId = entryId,
                            text = normalizedQuestion,
                            isError = false,
                            streamMeta = persistedStreamMeta(
                                entryId = entryId,
                                roundIndex = roundIndex,
                                kind = "clarify_required"
                            ),
                            createdAt = createdAt
                        )
                    }
                }

                fun buildPermissionRequiredMessage(missing: List<String>): String {
                    val names = missing.map(::localizedPermissionName).filter { it.isNotEmpty() }
                    return if (names.isEmpty()) {
                        t(
                            "执行任务前需要先开启权限",
                            "Enable the required permissions before running the task."
                        )
                    } else {
                        t(
                            "执行任务前，请先开启：${names.joinToString("、")}",
                            "Enable these permissions before running the task: ${names.joinToString(", ")}"
                        )
                    }
                }

                suspend fun upsertPermissionState(
                    textEntryId: String,
                    roundIndex: Int,
                    missing: List<String>
                ) {
                    val normalizedConversationId = conversationId ?: return
                    val names = missing.map(::localizedPermissionName).filter { it.isNotEmpty() }
                    val message = buildPermissionRequiredMessage(missing)
                    persistConversationMutation("upsert permission state") {
                        repository.upsertAssistantMessage(
                            conversationId = normalizedConversationId,
                            conversationMode = resolvedConversationMode,
                            entryId = textEntryId,
                            text = AgentTextSanitizer.sanitizeUtf16(message),
                            isError = false,
                            streamMeta = persistedStreamMeta(
                                entryId = textEntryId,
                                roundIndex = roundIndex,
                                kind = "permission_required"
                            ),
                            createdAt = entryCreatedAtTimes.getOrPut(textEntryId) {
                                System.currentTimeMillis()
                            }
                        )
                        val permissionIds = resolveRequiredPermissionIds(names)
                        if (permissionIds.isNotEmpty()) {
                            repository.upsertUiCard(
                                conversationId = normalizedConversationId,
                                conversationMode = resolvedConversationMode,
                                entryId = "$taskId-permission",
                                cardData = buildPermissionCardData(permissionIds),
                                streamMeta = persistedStreamMeta(
                                    entryId = "$taskId-permission",
                                    roundIndex = roundIndex,
                                    kind = "permission_required"
                                ),
                                createdAt = entryCreatedAtTimes.getOrPut("$taskId-permission") {
                                    System.currentTimeMillis()
                                }
                            )
                        }
                    }
                }

                suspend fun upsertToolEvent(
                    entryId: String,
                    roundIndex: Int,
                    payload: Map<String, Any?>,
                    streamKind: String,
                    fallbackStatus: String,
                    fallbackSummary: String
                ) {
                    val normalizedConversationId = conversationId ?: return
                    if (entryId.isBlank()) return
                    persistConversationMutation("upsert tool event") {
                        val sanitizedPayload = sanitizeInteropMap(
                            linkedMapOf<String, Any?>("taskId" to taskId).apply {
                                putAll(payload)
                                put(
                                    "streamMeta",
                                    persistedStreamMeta(
                                        entryId = entryId,
                                        roundIndex = roundIndex,
                                        kind = streamKind
                                    )
                                )
                            }
                        )
                        repository.upsertToolEvent(
                            conversationId = normalizedConversationId,
                            conversationMode = resolvedConversationMode,
                            entryId = entryId,
                            payload = sanitizedPayload,
                            fallbackStatus = fallbackStatus,
                            fallbackSummary = AgentTextSanitizer.sanitizeUtf16(fallbackSummary)
                        )
                    }
                }

                suspend fun sendStreamEvent(
                    kind: String,
                    entryId: String? = null,
                    roundIndex: Int = 0,
                    isFinal: Boolean = false,
                    text: String? = null,
                    thinking: String? = null,
                    stage: Int? = null,
                    prefillTokensPerSecond: Double? = null,
                    decodeTokensPerSecond: Double? = null,
                    success: Boolean? = null,
                    outputKind: String? = null,
                    hasUserVisibleOutput: Boolean? = null,
                    latestPromptTokens: Int? = null,
                    promptTokenThreshold: Int? = null,
                    error: String? = null,
                    question: String? = null,
                    missingFields: List<String>? = null,
                    missing: List<String>? = null,
                    turnUsage: Map<String, Any?>? = null,
                    extras: Map<String, Any?> = emptyMap(),
                    attachLatestThinkingToText: Boolean = true
                ) {
                    val effectiveThinking = thinking
                        ?.takeIf { it.isNotBlank() }
                        ?: latestThinkingContent.takeIf {
                            kind == "text_snapshot" &&
                                attachLatestThinkingToText &&
                                it.isNotBlank()
                        }
                    val eventSeq = nextEventSeq()
                    val basePayload = AgentStreamEvent(
                        taskId = taskId,
                        seq = eventSeq,
                        kind = kind,
                        createdAt = System.currentTimeMillis(),
                        entryId = entryId,
                        roundIndex = roundIndex,
                        isFinal = isFinal,
                        text = text,
                        thinking = effectiveThinking,
                        stage = stage,
                        prefillTokensPerSecond = prefillTokensPerSecond,
                        decodeTokensPerSecond = decodeTokensPerSecond,
                        success = success,
                        outputKind = outputKind,
                        hasUserVisibleOutput = hasUserVisibleOutput,
                        latestPromptTokens = latestPromptTokens,
                        promptTokenThreshold = promptTokenThreshold,
                        error = error,
                        question = question,
                        missingFields = missingFields,
                        missing = missing,
                        turnUsage = turnUsage,
                        extras = extras
                    ).toPayload(
                        conversationId = conversationId,
                        conversationMode = resolvedConversationMode
                    )
                    val payload = sanitizeInteropMap(
                        entryId?.takeIf { it.isNotBlank() }?.let { resolvedEntryId ->
                            basePayload + mapOf(
                                "streamMeta" to eventStreamMeta(
                                    entryId = resolvedEntryId,
                                    roundIndex = roundIndex,
                                    kind = kind,
                                    eventSeq = eventSeq
                                )
                            )
                        } ?: basePayload
                    )
                    RealtimeHub.publish("agent_stream_event", payload)
                    withContext(Dispatchers.Main) {
                        invokeFlutterEventSafely("onAgentStreamEvent", payload)
                    }
                }

                conversationId?.let { normalizedConversationId ->
                    if (!continueMode && (userMessage.isNotBlank() || historyAttachments.isNotEmpty())) {
                        val createdAt = userMessageCreatedAt ?: System.currentTimeMillis()
                        val persisted = persistConversationMutation("upsert user message") {
                            repository.upsertUserMessage(
                                conversationId = normalizedConversationId,
                                conversationMode = resolvedConversationMode,
                                entryId = userEntryId,
                                text = userMessage,
                                attachments = historyAttachments,
                                streamMeta = if (externalUserMessage) {
                                    AgentConversationHistorySupport
                                        .externalUserMessageStreamMeta()
                                } else {
                                    null
                                },
                                createdAt = createdAt
                            )
                        }
                        if (persisted && externalUserMessage) {
                            FlutterChatSyncBridge.dispatchExternalUserMessageAppended(
                                conversationId = normalizedConversationId,
                                mode = resolvedConversationMode,
                                entryId = userEntryId,
                                text = userMessage,
                                attachments = historyAttachments,
                                createdAt = createdAt
                            )
                        }
                    }
                }

                // 3. 创建回调
                val callback = object : AgentCallback {
                    override suspend fun onThinkingStart() {
                        finalizeThinkingCardIfNeeded(publish = false)
                        thinkingRound += 1
                        val entryId = resolveThinkingEntryId(thinkingRound)
                        val startTime = System.currentTimeMillis()
                        activeThinkingEntryId = entryId
                        latestThinkingContent = ""
                        thinkingCardStartTimes.putIfAbsent(entryId, startTime)
                        markAssistantRoundBoundary()
                        upsertThinkingCard(
                            entryId = entryId,
                            roundIndex = thinkingRound,
                            thinkingContent = "",
                            isLoading = true,
                            stage = 1,
                            createdAt = startTime,
                            streamKind = "thinking_started"
                        )
                        sendStreamEvent(
                            kind = "thinking_started",
                            entryId = entryId,
                            roundIndex = thinkingRound,
                            thinking = "",
                            stage = 1
                        )
                    }

                    override suspend fun onThinkingUpdate(thinking: String) {
                        val normalizedThinking = AgentTextSanitizer.sanitizeUtf16(thinking).trim()
                        if (shouldIgnoreRegressiveSnapshot(latestThinkingContent, normalizedThinking)) {
                            OmniLog.d(
                                TAG,
                                "ignore stale thinking snapshot: incoming=${normalizedThinking.length}, current=${latestThinkingContent.length}"
                            )
                            return
                        }
                        if (activeThinkingEntryId == null) {
                            if (thinkingRound <= 0) {
                                thinkingRound = 1
                            }
                            val generated = resolveThinkingEntryId(thinkingRound)
                            activeThinkingEntryId = generated
                            thinkingCardStartTimes.putIfAbsent(
                                generated,
                                System.currentTimeMillis()
                            )
                        }
                        val entryId = activeThinkingEntryId ?: return
                        latestThinkingContent = normalizedThinking
                        upsertThinkingCard(
                            entryId = entryId,
                            roundIndex = thinkingRound.coerceAtLeast(1),
                            thinkingContent = normalizedThinking,
                            isLoading = true,
                            stage = 1,
                            streamKind = "thinking_snapshot"
                        )
                        sendStreamEvent(
                            kind = "thinking_snapshot",
                            entryId = entryId,
                            roundIndex = thinkingRound.coerceAtLeast(1),
                            thinking = normalizedThinking,
                            stage = 1
                        )
                    }

                    private suspend fun handleToolCallStart(
                        toolCallId: String?,
                        toolName: String,
                        arguments: JsonObject
                    ) {
                        val argsJson = arguments.toString()
                        pushToolValue(activeToolArgs, toolName, argsJson)
                        val entryId = "$taskId-tool$continueGenerationSuffix-${++toolSequence}"
                        val roundIndex = currentToolRoundIndex()
                        pushToolValue(activeToolEntryIds, toolName, entryId)
                        toolCallId?.let { callId ->
                            toolEntryIdsByCallId[callId] = entryId
                            toolArgsByCallId[callId] = argsJson
                        }
                        agentRunContext.bindActiveToolCardId(entryId)
                        activeThinkingEntryId?.let { thinkingEntryId ->
                            upsertThinkingCard(
                                entryId = thinkingEntryId,
                                roundIndex = thinkingRound.coerceAtLeast(roundIndex),
                                thinkingContent = latestThinkingContent,
                                isLoading = true,
                                stage = 2,
                                streamKind = "thinking_snapshot",
                                publish = false
                            )
                        }
                        markAssistantRoundBoundary()
                        val payload = buildToolStartPayload(toolName, argsJson).toMutableMap().apply {
                            put("cardId", entryId)
                        }
                        latestThinkingContent.takeIf { it.isNotBlank() }?.let { reasoning ->
                            payload["reasoning_content"] = reasoning
                        }
                        upsertToolEvent(
                            entryId = entryId,
                            roundIndex = roundIndex,
                            payload = payload,
                            streamKind = "tool_started",
                            fallbackStatus = AgentConversationHistoryRepository.STATUS_RUNNING,
                            fallbackSummary = payload["summary"]?.toString()?.ifBlank {
                                t("正在调用工具", "Calling tool")
                            } ?: t("正在调用工具", "Calling tool")
                        )
                        sendStreamEvent(
                            kind = "tool_started",
                            entryId = entryId,
                            roundIndex = roundIndex,
                            extras = payload
                        )
                    }

                    override suspend fun onToolCallStart(
                        toolName: String,
                        arguments: JsonObject
                    ) {
                        handleToolCallStart(null, toolName, arguments)
                    }

                    override suspend fun onToolCallStart(
                        toolCallId: String,
                        toolName: String,
                        arguments: JsonObject
                    ) {
                        handleToolCallStart(toolCallId, toolName, arguments)
                    }

                    override suspend fun onToolCallProgress(
                        toolName: String,
                        progress: String,
                        extras: Map<String, Any?>
                    ) {
                        val entryId = peekToolValue(activeToolEntryIds, toolName)
                        val roundIndex = currentToolRoundIndex()
                        val payload = buildToolProgressPayload(
                            toolName,
                            progress,
                            peekToolValue(activeToolArgs, toolName),
                            extras
                        ).toMutableMap().apply {
                            if (entryId.isNotBlank()) {
                                put("cardId", entryId)
                            }
                        }
                        upsertToolEvent(
                            entryId = entryId,
                            roundIndex = roundIndex,
                            payload = payload,
                            streamKind = "tool_progress",
                            fallbackStatus = AgentConversationHistoryRepository.STATUS_RUNNING,
                            fallbackSummary = payload["summary"]?.toString()?.ifBlank {
                                t("正在调用工具", "Calling tool")
                            } ?: t("正在调用工具", "Calling tool")
                        )
                        sendStreamEvent(
                            kind = "tool_progress",
                            entryId = entryId.takeIf { it.isNotBlank() },
                            roundIndex = roundIndex,
                            extras = payload
                        )
                    }

                    private suspend fun handleToolCallComplete(
                        toolCallId: String?,
                        toolName: String,
                        result: ToolExecutionResult
                    ) {
                        val mappedArgs = toolCallId?.let { callId ->
                            toolArgsByCallId.remove(callId)
                        }
                        val argsJson = mappedArgs ?: popToolValue(activeToolArgs, toolName)
                        if (mappedArgs != null) {
                            removeToolValue(activeToolArgs, toolName, mappedArgs)
                        }
                        val mappedEntryId = toolCallId?.let { callId ->
                            toolEntryIdsByCallId[callId]
                        }
                        val entryId = mappedEntryId ?: popToolValue(activeToolEntryIds, toolName).ifBlank {
                            "$taskId-tool$continueGenerationSuffix-${++toolSequence}"
                        }
                        if (mappedEntryId != null) {
                            removeToolValue(activeToolEntryIds, toolName, mappedEntryId)
                        }
                        val roundIndex = currentToolRoundIndex()
                        activeThinkingEntryId?.let { thinkingEntryId ->
                            upsertThinkingCard(
                                entryId = thinkingEntryId,
                                roundIndex = thinkingRound.coerceAtLeast(roundIndex),
                                thinkingContent = latestThinkingContent,
                                isLoading = true,
                                stage = 2,
                                streamKind = "thinking_snapshot",
                                publish = false
                            )
                        }
                        markAssistantRoundBoundary()
                        val payload = buildToolCompletePayload(toolName, result, argsJson)
                            .toMutableMap().apply {
                                put("cardId", entryId)
                            }
                        latestThinkingContent.takeIf { it.isNotBlank() }?.let { reasoning ->
                            payload["reasoning_content"] = reasoning
                        }
                        val success = payload["success"] != false
                        upsertToolEvent(
                            entryId = entryId,
                            roundIndex = roundIndex,
                            payload = payload,
                            streamKind = "tool_completed",
                            fallbackStatus = if (success) {
                                AgentConversationHistoryRepository.STATUS_SUCCESS
                            } else {
                                AgentConversationHistoryRepository.STATUS_ERROR
                            },
                            fallbackSummary = payload["summary"]?.toString().orEmpty()
                        )
                        sendStreamEvent(
                            kind = "tool_completed",
                            entryId = entryId,
                            roundIndex = roundIndex,
                            extras = payload
                        )
                        if (payload["toolType"]?.toString() == "browser") {
                            val snapshot = LiveAgentBrowserSessionManager.currentSnapshot()
                            RealtimeHub.publish(
                                "browser_snapshot_updated",
                                mapOf("snapshot" to snapshot)
                            )
                            FlutterChatSyncBridge.dispatchBrowserSnapshotUpdated(snapshot)
                        }
                    }

                    override suspend fun onToolCallComplete(
                        toolName: String,
                        result: ToolExecutionResult
                    ) {
                        handleToolCallComplete(null, toolName, result)
                    }

                    override suspend fun onToolCallComplete(
                        toolCallId: String,
                        toolName: String,
                        result: ToolExecutionResult
                    ) {
                        handleToolCallComplete(toolCallId, toolName, result)
                    }

                    override suspend fun onToolReplayReady(
                        toolCallId: String,
                        assistantMessage: ChatCompletionMessage,
                        toolResultMessage: ChatCompletionMessage
                    ) {
                        val normalizedConversationId = conversationId ?: return
                        val entryId = toolEntryIdsByCallId.remove(toolCallId) ?: return
                        val assistantJson = chatTaskPayloadJson.encodeToString(assistantMessage)
                        val toolResultJson = chatTaskPayloadJson.encodeToString(toolResultMessage)
                        persistConversationMutation(
                            description = "persist canonical tool replay",
                            publish = false
                        ) {
                            repository.upsertToolEvent(
                                conversationId = normalizedConversationId,
                                conversationMode = resolvedConversationMode,
                                entryId = entryId,
                                payload = mapOf(
                                    "modelToolCallId" to toolCallId,
                                    "modelAssistantMessageJson" to assistantJson,
                                    "modelToolResultMessageJson" to toolResultJson
                                ),
                                fallbackStatus = AgentConversationHistoryRepository.STATUS_SUCCESS,
                                fallbackSummary = ""
                            )
                        }
                    }

                    override suspend fun onChatMessage(message: String) {
                        dispatchAgentChatMessage(message, isFinal = true)
                    }

                    override suspend fun onChatMessage(message: String, isFinal: Boolean) {
                        dispatchAgentChatMessage(message, isFinal)
                    }

                    override suspend fun onChatMessage(
                        message: String,
                        isFinal: Boolean,
                        prefillTokensPerSecond: Double?,
                        decodeTokensPerSecond: Double?
                    ) {
                        dispatchAgentChatMessage(
                            message = message,
                            isFinal = isFinal,
                            prefillTokensPerSecond = prefillTokensPerSecond,
                            decodeTokensPerSecond = decodeTokensPerSecond
                        )
                    }

                    override suspend fun onPromptTokenUsageChanged(
                        latestPromptTokens: Int,
                        promptTokenThreshold: Int?
                    ) {
                        sendFlutterEvent(
                            "onAgentPromptTokenUsageChanged",
                            mapOf(
                                "latestPromptTokens" to latestPromptTokens,
                                "promptTokenThreshold" to promptTokenThreshold
                            )
                        )
                    }

                    override suspend fun onContextCompactionStateChanged(
                        isCompacting: Boolean,
                        latestPromptTokens: Int?,
                        promptTokenThreshold: Int?
                    ) {
                        sendFlutterEvent(
                            "onAgentContextCompactionStateChanged",
                            mapOf(
                                "isCompacting" to isCompacting,
                                "latestPromptTokens" to latestPromptTokens,
                                "promptTokenThreshold" to promptTokenThreshold
                            )
                        )
                    }

                    override suspend fun onClarifyRequired(
                        question: String,
                        missingFields: List<String>?
                    ) {
                        finalizeThinkingCardIfNeeded()
                        val normalizedQuestion = AgentTextSanitizer.sanitizeUtf16(question).trim()
                        val (roundIndex, entryId) = ensureAssistantEntry(
                            forceNewRound = latestAssistantVisibleText.isNotEmpty() || assistantRound > 0
                        )
                        latestAssistantVisibleText = normalizedQuestion
                        if (normalizedQuestion.isNotEmpty()) {
                            upsertClarifyMessage(
                                entryId = entryId,
                                roundIndex = roundIndex,
                                question = normalizedQuestion
                            )
                        }
                        sendStreamEvent(
                            kind = "clarify_required",
                            entryId = entryId,
                            roundIndex = roundIndex,
                            text = normalizedQuestion,
                            question = normalizedQuestion,
                            missingFields = missingFields
                        )
                    }

                    override suspend fun onComplete(result: AgentResult) {
                        removeFailedAgentRetryContext(taskId)
                        removeFailedAgentContinueContext(taskId)
                        val isSuccess = result is AgentResult.Success
                        val outputKind = (result as? AgentResult.Success)?.outputKind ?: "none"
                        val hasUserVisibleOutput =
                            (result as? AgentResult.Success)?.hasUserVisibleOutput == true
                        val latestPromptTokens = (result as? AgentResult.Success)?.latestPromptTokens
                        val promptTokenThreshold =
                            (result as? AgentResult.Success)?.promptTokenThreshold
                        val turnUsageSnapshot = buildTurnUsageSnapshot(
                            latestPromptTokens = latestPromptTokens,
                            promptTokenThreshold = promptTokenThreshold,
                            result = result as? AgentResult.Success
                        )
                        val streamed = scheduledAssistantBuffer.toString().trim()
                        val fallback = (result as? AgentResult.Success)
                            ?.response
                            ?.content
                            ?.trim()
                            .orEmpty()
                        val finalText = resolveAssistantFinalText(
                            streamed = streamed.ifEmpty { latestAssistantVisibleText },
                            fallback = fallback
                        ).ifEmpty {
                            if (isSuccess && outputKind == "none" && !hasUserVisibleOutput) {
                                t(
                                    "暂时无法生成回复，请重试。",
                                    "I can't generate a reply right now. Please try again."
                                )
                            } else {
                                ""
                            }
                        }
                        finalizeThinkingCardIfNeeded(publish = finalText.isBlank())
                        var completedEntryId: String? = activeAssistantEntryId ?: lastAssistantEntryId
                        var completedRoundIndex = if (activeAssistantEntryId != null) {
                            assistantRound.coerceAtLeast(1)
                        } else {
                            lastAssistantRoundIndex.coerceAtLeast(1)
                        }
                        if (finalText.isNotBlank()) {
                            val existingEntryId = completedEntryId
                                ?.takeIf { it.isNotBlank() }
                                ?.takeIf {
                                    shouldReuseAssistantEntryForFinalText(
                                        finalText = finalText,
                                        fallback = fallback
                                    )
                                }
                            val (roundIndex, entryId) = if (existingEntryId != null) {
                                completedRoundIndex.coerceAtLeast(1) to existingEntryId
                            } else {
                                ensureAssistantEntry(forceNewRound = assistantRound > 0)
                            }
                            val reasoningForEntry = assistantReasoningByEntryId[entryId]
                                ?: if (entryId == activeAssistantEntryId || existingEntryId == null) {
                                    captureAssistantReasoningForEntry(entryId)
                                } else {
                                    null
                                }
                            completedEntryId = entryId
                            completedRoundIndex = roundIndex
                            rememberAssistantEntry(entryId, roundIndex)
                            latestAssistantVisibleText = finalText
                            scheduledAssistantBuffer.setLength(0)
                            scheduledAssistantBuffer.append(finalText)
                            upsertAssistantSnapshot(
                                entryId = entryId,
                                roundIndex = roundIndex,
                                text = finalText,
                                isError = !isSuccess,
                                streamKind = "text_snapshot",
                                isFinal = true,
                                usageSnapshot = turnUsageSnapshot,
                                reasoningContent = reasoningForEntry
                            )
                            sendStreamEvent(
                                kind = "text_snapshot",
                                entryId = entryId,
                                roundIndex = roundIndex,
                                isFinal = true,
                                text = finalText,
                                turnUsage = turnUsageSnapshot?.toPayload(),
                                attachLatestThinkingToText = false
                            )
                        }
                        scheduledSubagentMeta?.let { meta ->
                            val notificationText = finalText.ifEmpty {
                                if (isSuccess) {
                                    t("任务已完成，点击查看详情。", "Task completed. Tap to view details.")
                                } else {
                                    t("任务已结束，请点击查看详情。", "Task ended. Tap to view details.")
                                }
                            }
                            runCatching {
                                notifyScheduledSubagentCompletion(meta, notificationText)
                            }.onFailure {
                                OmniLog.w(
                                    TAG,
                                    "notify scheduled subagent completion failed: ${it.message}",
                                    it
                                )
                            }
                        }
                        if (scheduledSubagentMeta == null) {
                            TaskRuntimeSettings.notifyTaskFinished(
                                context = context,
                                title = if (isSuccess) "Agent 任务已完成" else "Agent 任务已结束",
                                message = finalText.ifBlank {
                                    if (isSuccess) "任务已完成，点击查看详情" else "任务已结束，点击查看详情"
                                },
                                conversationId = conversationId ?: currentConversationId,
                                conversationMode = resolvedConversationMode
                            )
                        }
                        sendStreamEvent(
                            kind = "completed",
                            entryId = completedEntryId,
                            roundIndex = completedRoundIndex,
                            success = isSuccess,
                            outputKind = outputKind,
                            hasUserVisibleOutput = hasUserVisibleOutput,
                            latestPromptTokens = latestPromptTokens,
                            promptTokenThreshold = promptTokenThreshold,
                            turnUsage = turnUsageSnapshot?.toPayload()
                        )
                    }

                    override suspend fun onRetrying(
                        retryCount: Int,
                        maxRetries: Int,
                        retryDelayMs: Long,
                        message: String,
                        retryReason: String?
                    ) {
                        val retryEntryId = activeAssistantEntryId ?: activeThinkingEntryId
                        val retryRoundIndex = if (activeAssistantEntryId != null) {
                            assistantRound.coerceAtLeast(1)
                        } else {
                            thinkingRound.coerceAtLeast(1)
                        }
                        sendStreamEvent(
                            kind = "retrying",
                            entryId = retryEntryId,
                            roundIndex = retryRoundIndex,
                            text = AgentTextSanitizer.sanitizeUtf16(message).trim(),
                            stage = 1,
                            extras = mapOf(
                                "willRetry" to true,
                                "retryable" to true,
                                "retryCount" to retryCount,
                                "maxRetries" to maxRetries,
                                "retryDelayMs" to retryDelayMs,
                                "retryReason" to retryReason
                            )
                        )
                    }

                    override suspend fun onError(error: String) {
                        onError(error, retryable = false)
                    }

                    override suspend fun onError(error: String, retryable: Boolean) {
                        if (retryable) {
                            registerFailedAgentRetryContext(
                                taskId,
                                FailedAgentRetryContext(arguments = retryArguments)
                            )
                        } else {
                            removeFailedAgentRetryContext(taskId)
                        }
                        val resolution = resolveAgentFinalErrorResolution(
                            streamed = scheduledAssistantBuffer.toString().ifBlank {
                                latestAssistantVisibleText
                            },
                            error = error,
                            localizedFallback = t(
                                "暂时无法生成回复，请重试。",
                                "I can't generate a reply right now. Please try again."
                            )
                        )
                        val errorText = AgentTextSanitizer.sanitizeUtf16(error).trim().ifEmpty {
                            t(
                                "暂时无法生成回复，请重试。",
                                "I can't generate a reply right now. Please try again."
                            )
                        }
                        val finalText = resolution.text
                        val errorTurnUsageSnapshot = buildTurnUsageSnapshot(
                            latestPromptTokens = (result as? AgentResult.Success)?.latestPromptTokens,
                            promptTokenThreshold = (result as? AgentResult.Success)?.promptTokenThreshold,
                            result = result as? AgentResult.Success
                        )
                        finalizeThinkingCardIfNeeded(publish = finalText.isBlank())
                        var errorEntryId: String? = activeAssistantEntryId ?: lastAssistantEntryId
                        var errorRoundIndex = if (activeAssistantEntryId != null) {
                            assistantRound.coerceAtLeast(1)
                        } else {
                            lastAssistantRoundIndex.coerceAtLeast(1)
                        }
                        if (finalText.isNotBlank()) {
                            val existingEntryId = errorEntryId
                                ?.takeIf { it.isNotBlank() }
                                ?.takeIf {
                                    shouldReuseAssistantEntryForFinalText(
                                        finalText = finalText,
                                        fallback = errorText
                                    )
                                }
                            val (roundIndex, entryId) = if (existingEntryId != null) {
                                errorRoundIndex.coerceAtLeast(1) to existingEntryId
                            } else {
                                ensureAssistantEntry(forceNewRound = assistantRound > 0)
                            }
                            val reasoningForEntry = assistantReasoningByEntryId[entryId]
                                ?: if (entryId == activeAssistantEntryId || existingEntryId == null) {
                                    captureAssistantReasoningForEntry(entryId)
                                } else {
                                    null
                                }
                            errorEntryId = entryId
                            errorRoundIndex = roundIndex
                            rememberAssistantEntry(entryId, roundIndex)
                            latestAssistantVisibleText = finalText
                            scheduledAssistantBuffer.setLength(0)
                            scheduledAssistantBuffer.append(finalText)
                            upsertAssistantSnapshot(
                                entryId = entryId,
                                roundIndex = roundIndex,
                                text = finalText,
                                isError = resolution.persistAsError,
                                interruptedTurn = true,
                                streamKind = "text_snapshot",
                                isFinal = true,
                                usageSnapshot = errorTurnUsageSnapshot,
                                reasoningContent = reasoningForEntry
                            )
                            sendStreamEvent(
                                kind = "text_snapshot",
                                entryId = entryId,
                                roundIndex = roundIndex,
                                isFinal = true,
                                text = finalText,
                                turnUsage = errorTurnUsageSnapshot?.toPayload(),
                                attachLatestThinkingToText = false
                            )
                        }
                        val continueResumeModeValue = continueResumeMode ?: "approximate"
                        val continuePayload = errorTurnUsageSnapshot?.toPayload() ?: continueTurnUsage
                        val continueable = conversationId != null &&
                            errorEntryId?.isNotBlank() == true &&
                            finalText.isNotBlank()
                        if (continueable) {
                            registerFailedAgentContinueContext(
                                taskId,
                                FailedAgentContinueContext(
                                    arguments = sanitizeInteropMap(
                                        retryArguments + mapOf(
                                            "continueMode" to true,
                                            "continueResumeMode" to continueResumeModeValue,
                                            "continueFromAssistantEntryId" to errorEntryId,
                                            "continueFromAssistantText" to finalText,
                                            "continueTurnUsage" to continuePayload
                                        )
                                    )
                                )
                            )
                        } else if (!continueMode) {
                            removeFailedAgentContinueContext(taskId)
                        }
                        scheduledSubagentMeta?.let { meta ->
                            runCatching {
                                notifyScheduledSubagentCompletion(meta, finalText)
                            }.onFailure {
                                OmniLog.w(
                                    TAG,
                                    "notify scheduled subagent error failed: ${it.message}",
                                    it
                                )
                            }
                        }
                        sendStreamEvent(
                            kind = "error",
                            entryId = errorEntryId,
                            roundIndex = errorRoundIndex,
                            error = error,
                            turnUsage = continuePayload,
                            extras = mapOf(
                                "persistAsError" to resolution.persistAsError,
                                "willRetry" to false,
                                "retryable" to retryable,
                                "continueable" to continueable,
                                "continueResumeMode" to if (continueable) continueResumeModeValue else null,
                                "retryCount" to if (retryable) 3 else 0,
                                "maxRetries" to 3,
                                "errorText" to errorText
                            )
                        )
                    }

                    override suspend fun onPermissionRequired(missing: List<String>) {
                        finalizeThinkingCardIfNeeded()
                        val (roundIndex, entryId) = ensureAssistantEntry(
                            forceNewRound = latestAssistantVisibleText.isNotEmpty() || assistantRound > 0
                        )
                        val permissionMessage = buildPermissionRequiredMessage(missing)
                        latestAssistantVisibleText = AgentTextSanitizer.sanitizeUtf16(permissionMessage).trim()
                        upsertPermissionState(
                            textEntryId = entryId,
                            roundIndex = roundIndex,
                            missing = missing
                        )
                        sendStreamEvent(
                            kind = "permission_required",
                            entryId = entryId,
                            roundIndex = roundIndex,
                            text = permissionMessage,
                            missing = missing,
                            extras = mapOf("permissionCardId" to "$taskId-permission")
                        )
                    }

                    private suspend fun dispatchAgentChatMessage(
                        message: String,
                        isFinal: Boolean,
                        prefillTokensPerSecond: Double? = null,
                        decodeTokensPerSecond: Double? = null
                    ) {
                        val normalizedMessage = AgentTextSanitizer.sanitizeUtf16(message).trim()
                        var entryId: String? = activeAssistantEntryId
                        var roundIndex = assistantRound
                        if (normalizedMessage.isNotEmpty()) {
                            val resolvedEntry = ensureAssistantEntry()
                            roundIndex = resolvedEntry.first
                            entryId = resolvedEntry.second
                            val resolvedEntryId = resolvedEntry.second
                            val currentSnapshot =
                                AgentTextSanitizer.sanitizeUtf16(
                                    scheduledAssistantBuffer.toString()
                                ).trim()
                            if (shouldIgnoreRegressiveSnapshot(currentSnapshot, normalizedMessage)) {
                                OmniLog.d(
                                    TAG,
                                    "ignore stale agent snapshot: incoming=${normalizedMessage.length}, current=${currentSnapshot.length}, final=$isFinal"
                                )
                                return
                            }
                            scheduledAssistantBuffer.setLength(0)
                            scheduledAssistantBuffer.append(normalizedMessage)
                            latestAssistantVisibleText = normalizedMessage
                            rememberAssistantEntry(resolvedEntryId, roundIndex)
                            val reasoningForEntry = captureAssistantReasoningForEntry(
                                resolvedEntryId
                            )
                            upsertAssistantSnapshot(
                                entryId = resolvedEntryId,
                                roundIndex = roundIndex,
                                text = normalizedMessage,
                                isError = false,
                                streamKind = "text_snapshot",
                                isFinal = isFinal,
                                reasoningContent = reasoningForEntry
                            )
                        }
                        val snapshotText = entryId?.let {
                            AgentTextSanitizer.sanitizeUtf16(
                                scheduledAssistantBuffer.toString()
                            ).trim()
                        }.orEmpty()
                        if (entryId != null && snapshotText.isNotEmpty()) {
                            sendStreamEvent(
                                kind = "text_snapshot",
                                entryId = entryId,
                                roundIndex = roundIndex.coerceAtLeast(1),
                                isFinal = isFinal,
                                text = snapshotText.ifEmpty { normalizedMessage },
                                prefillTokensPerSecond = prefillTokensPerSecond,
                                decodeTokensPerSecond = decodeTokensPerSecond,
                                attachLatestThinkingToText = false
                            )
                        }
                    }

                    private fun shouldIgnoreRegressiveSnapshot(
                        current: String,
                        incoming: String
                    ): Boolean {
                        if (current.isEmpty() || incoming.isEmpty()) {
                            return false
                        }
                        return incoming.length < current.length && current.startsWith(incoming)
                    }

                    private fun resolveAssistantFinalText(
                        streamed: String,
                        fallback: String
                    ): String {
                        val normalizedStreamed = AgentTextSanitizer.sanitizeUtf16(streamed).trim()
                        val normalizedFallback = AgentTextSanitizer.sanitizeUtf16(fallback).trim()
                        if (normalizedFallback.isEmpty()) {
                            return normalizedStreamed
                        }
                        if (normalizedStreamed.isEmpty()) {
                            return normalizedFallback
                        }
                        return when {
                            normalizedFallback.length >= normalizedStreamed.length &&
                                normalizedFallback.startsWith(normalizedStreamed) -> normalizedFallback
                            normalizedStreamed.length > normalizedFallback.length &&
                                normalizedStreamed.startsWith(normalizedFallback) -> normalizedStreamed
                            else -> normalizedFallback
                        }
                    }

                    private fun shouldReuseAssistantEntryForFinalText(
                        finalText: String,
                        fallback: String
                    ): Boolean {
                        if (activeAssistantEntryId != null) {
                            return true
                        }
                        if (!shouldStartNewAssistantRound || fallback.isBlank()) {
                            return true
                        }
                        val latestText = AgentTextSanitizer.sanitizeUtf16(
                            latestAssistantVisibleText
                        ).trim()
                        val normalizedFinal = AgentTextSanitizer.sanitizeUtf16(finalText).trim()
                        if (latestText.isBlank() || normalizedFinal == latestText) {
                            return true
                        }
                        return normalizedFinal.startsWith(latestText)
                    }

                    private suspend fun sendFlutterEvent(
                        method: String,
                        args: Map<String, Any?>
                    ) {
                        val payload = sanitizeInteropMap(
                            mapOf(
                                "taskId" to taskId,
                                "conversationId" to conversationId,
                                "conversationMode" to resolvedConversationMode
                            ) + args
                        )
                        withContext(Dispatchers.Main) {
                            invokeFlutterEventSafely(method, payload)
                        }
                    }
                }

                // 4. 执行任务
                executor.processUserMessage(
                    userMessage,
                    legacyConversationHistory,
                    runtimeContextRepository,
                    runtimeAttachments,
                    conversationId,
                    resolvedConversationMode,
                    modelOverride,
                    reasoningEffort,
                    terminalEnvironment,
                    callback,
                    runControl = agentRunContext,
                    continueMode = continueMode
                )
            } catch (e: CancellationException) {
                OmniLog.i(TAG, "createAgentTask cancelled: ${e.message}")
            } catch (e: Exception) {
                removeFailedAgentRetryContext(taskId)
                OmniLog.e(TAG, "createAgentTask error: ${e.message}")
                val errorMessage = e.message?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    "Agent execution failed: $it"
                } ?: "Agent execution failed"
                var failureTextSeq = 1L
                var failureErrorSeq = 2L
                var failureTextStreamMeta: Map<String, Any?>? = null
                runCatching {
                    val normalizedConversationId = conversationId ?: return@runCatching
                    val failureRepository =
                        historyRepository ?: conversationHistoryRepository().also {
                            historyRepository = it
                        }
                    val roundIndex = 1
                    val entryId = "$taskId-text"
                    fun streamSeq(value: Any?): Long? {
                        return when (value) {
                            is Number -> value.toLong()
                            is String -> value.trim().toLongOrNull()
                            else -> value?.toString()?.trim()?.toLongOrNull()
                        }
                    }
                    val maxExistingSeq = failureRepository.listConversationMessages(
                        conversationId = normalizedConversationId,
                        conversationMode = resolvedConversationMode
                    ).mapNotNull { message ->
                        val meta = toStringAnyMap(message["streamMeta"])
                        if (meta["parentTaskId"]?.toString()?.trim() != taskId) {
                            null
                        } else {
                            streamSeq(meta["seq"])
                        }
                    }.maxOrNull() ?: 0L
                    failureTextSeq = maxExistingSeq + 1
                    failureErrorSeq = failureTextSeq + 1
                    failureTextStreamMeta = linkedMapOf(
                        "seq" to failureTextSeq,
                        "entrySeq" to failureTextSeq,
                        "roundIndex" to roundIndex,
                        "kind" to "error",
                        "parentTaskId" to taskId,
                        "entryId" to entryId,
                        "isFinal" to true
                    )
                    failureRepository.upsertAssistantMessage(
                        conversationId = normalizedConversationId,
                        conversationMode = resolvedConversationMode,
                        entryId = entryId,
                        text = errorMessage,
                        isError = true,
                        streamMeta = failureTextStreamMeta,
                        createdAt = System.currentTimeMillis()
                    )
                    val messages = failureRepository.listConversationMessages(
                        conversationId = normalizedConversationId,
                        conversationMode = resolvedConversationMode
                    )
                    RealtimeHub.publish(
                        "messages_replaced",
                        mapOf(
                            "conversationId" to normalizedConversationId,
                            "mode" to resolvedConversationMode,
                            "messages" to messages
                        )
                    )
                    FlutterChatSyncBridge.dispatchConversationMessagesChanged(
                        conversationId = normalizedConversationId,
                        mode = resolvedConversationMode,
                        reason = "messages_replaced"
                    )
                }.onFailure {
                    OmniLog.w(TAG, "persist agent startup failure failed: ${it.message}")
                }
                scheduledSubagentMeta?.let { meta ->
                    runCatching {
                        notifyScheduledSubagentCompletion(meta, errorMessage)
                    }.onFailure {
                        OmniLog.w(TAG, "notify scheduled subagent failure failed: ${it.message}")
                    }
                }
                runCatching {
                    val failureEntryId = "$taskId-text"
                    val failureRoundIndex = 1
                    val textPayload = sanitizeInteropMap(
                        AgentStreamEvent(
                            taskId = taskId,
                            seq = failureTextSeq,
                            kind = "text_snapshot",
                            createdAt = System.currentTimeMillis(),
                            entryId = failureEntryId,
                            roundIndex = failureRoundIndex,
                            isFinal = true,
                            text = errorMessage
                        ).toPayload(
                            conversationId = conversationId,
                            conversationMode = resolvedConversationMode
                        ) + mapOf("streamMeta" to failureTextStreamMeta)
                    )
                    val errorPayload = sanitizeInteropMap(
                        AgentStreamEvent(
                            taskId = taskId,
                            seq = failureErrorSeq,
                            kind = "error",
                            createdAt = System.currentTimeMillis(),
                            entryId = failureEntryId,
                            roundIndex = failureRoundIndex,
                            error = errorMessage,
                            extras = mapOf("persistAsError" to true)
                        ).toPayload(
                            conversationId = conversationId,
                            conversationMode = resolvedConversationMode
                        ) + mapOf(
                            "streamMeta" to linkedMapOf(
                                "seq" to failureErrorSeq,
                                "entrySeq" to failureErrorSeq,
                                "roundIndex" to failureRoundIndex,
                                "kind" to "error",
                                "parentTaskId" to taskId,
                                "entryId" to failureEntryId,
                                "isFinal" to true
                            )
                        )
                    )
                    RealtimeHub.publish("agent_stream_event", textPayload)
                    RealtimeHub.publish("agent_stream_event", errorPayload)
                    withContext(Dispatchers.Main) {
                        invokeFlutterEventSafely("onAgentStreamEvent", textPayload)
                        invokeFlutterEventSafely("onAgentStreamEvent", errorPayload)
                    }
                }.onFailure {
                    OmniLog.w(TAG, "dispatch agent startup failure failed: ${it.message}")
                }
            } finally {
                TaskRuntimeSettings.onTaskFinished(context)
                clearActiveAgentJob(taskId, agentRunJob)
            }
        }
        result.success("SUCCESS")
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

        workJob.launch {
            try {
                val conversation = conversationDomainService.createConversation(
                    title = title,
                    mode = mode,
                    summary = summary,
                    parentConversationId = parentConversationId,
                    parentConversationMode = parentConversationMode,
                    scheduledTaskId = scheduledTaskId
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
        val mode = (call.argument<String>("mode") ?: "normal").trim().ifEmpty { "normal" }
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
        val mode = (call.argument<String>("mode") ?: "normal").trim().ifEmpty { "normal" }
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
