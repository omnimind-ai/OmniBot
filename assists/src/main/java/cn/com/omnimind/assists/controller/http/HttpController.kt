package cn.com.omnimind.assists.controller.http

import cn.com.omnimind.assists.api.bean.ResultBean
import cn.com.omnimind.baselib.account.AiRequestTransportPolicy
import cn.com.omnimind.baselib.account.AiTransportRoute
import cn.com.omnimind.baselib.account.OmniAccount
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.AiRequestLogEntry
import cn.com.omnimind.baselib.llm.AiRequestLogStore
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionStreamOptions
import cn.com.omnimind.baselib.llm.DeepSeekProvider
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.database.TokenUsageRecord
import cn.com.omnimind.baselib.llm.ModelProviderConfig
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.baselib.llm.OpenAIResponsesRequest
import cn.com.omnimind.baselib.llm.OmniOfficialProvider
import cn.com.omnimind.baselib.llm.PlatformAiProvisioner
import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.llm.ProviderCustomHeaderUtils
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.llm.SceneOperationConfigStore
import cn.com.omnimind.baselib.llm.contentText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonArray as KxJsonArray
import kotlinx.serialization.json.JsonObject as KxJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import org.json.JSONArray

/**
 * AI HTTP 控制器，用于处理模型网络请求
 */
object HttpController {
    private const val TAG = "HttpController"
    private const val RESPONSE_LOG_CHUNK_SIZE = 3500
    private const val PROVIDER_MODELS_TIMEOUT_SECONDS = 4L
    private const val ROUTE_CUSTOM_OPENAI_COMPAT = "custom_openai_compat"
    private const val ANTHROPIC_EPHEMERAL_CACHE_TYPE = "ephemeral"
    private const val ANTHROPIC_MAX_CACHE_BREAKPOINTS = 4

    data class ChatCompletionRouteInfo(
        val requestedModel: String,
        val resolvedModel: String,
        val apiBase: String?,
        val providerProfileId: String?,
        val providerProfileName: String?,
        val routeTag: String?,
        val bindingApplied: Boolean,
        val bindingProfileMissing: Boolean,
        val overrideApplied: Boolean,
        val protocolType: String = "openai_compatible",
        val wireApi: String = OpenAiWireApi.CHAT_COMPLETIONS,
        val requiresReasoningEcho: Boolean = false,
        val requiresAnthropicThinkingReplay: Boolean = false
    )

    private data class ResolvedSceneRequest(
        val requestedModel: String,
        val resolvedModel: String,
        val sceneProfile: ModelSceneRegistry.SceneRuntimeProfile?,
        val effectiveTransport: ModelSceneRegistry.SceneTransport,
        val responseParser: ModelSceneRegistry.ResponseParser,
        val apiBase: String?,
        val apiKey: String?,
        val customHeaders: Map<String, String>,
        val providerProfileId: String?,
        val providerProfileName: String?,
        val routeTag: String?,
        val customApiBaseApplied: Boolean,
        val bindingApplied: Boolean,
        val bindingProfileMissing: Boolean,
        val overrideApplied: Boolean,
        val overrideModel: String?,
        val protocolType: String = "openai_compatible",
        val wireApi: String = OpenAiWireApi.CHAT_COMPLETIONS
    )

    private data class AiRequestLogSeed(
        val label: String,
        val model: String,
        val protocolType: String,
        val url: String,
        val method: String = "POST",
        val stream: Boolean,
        val requestJson: String,
        val conversationId: Long = 0L
    )

    /**
     * Anthropic reports uncached input, cache writes, and cache reads as separate
     * counters. Keep the latest value of every counter because streaming
     * message_delta events commonly contain only the cumulative output count.
     */
    private class AnthropicUsageAccumulator {
        private var sawUsage = false
        private var inputTokens = 0
        private var cacheCreationInputTokens = 0
        private var cacheReadInputTokens = 0
        private var outputTokens = 0

        fun merge(usage: KxJsonObject?) {
            if (usage == null) return
            updateIfPresent(usage, "input_tokens") { inputTokens = it }
            updateIfPresent(usage, "cache_creation_input_tokens") {
                cacheCreationInputTokens = it
            }
            updateIfPresent(usage, "cache_read_input_tokens") {
                cacheReadInputTokens = it
            }
            updateIfPresent(usage, "output_tokens") { outputTokens = it }
        }

        fun toOpenAIUsage(): KxJsonObject? {
            if (!sawUsage) return null

            // Normalize to OpenAI/DeepSeek semantics: prompt_tokens is total
            // input and cached_tokens is a subset of it. This keeps cache-hit
            // ratios and context-size accounting provider independent.
            val promptTokens = safeTokenSum(
                inputTokens,
                cacheCreationInputTokens,
                cacheReadInputTokens
            )
            val totalTokens = safeTokenSum(promptTokens, outputTokens)
            return buildJsonObject {
                put("prompt_tokens", JsonPrimitive(promptTokens))
                put("completion_tokens", JsonPrimitive(outputTokens))
                put("total_tokens", JsonPrimitive(totalTokens))
                put(
                    "prompt_tokens_details",
                    buildJsonObject {
                        put("cached_tokens", JsonPrimitive(cacheReadInputTokens))
                        put("cache_creation_tokens", JsonPrimitive(cacheCreationInputTokens))
                    }
                )
            }
        }

        private fun updateIfPresent(
            usage: KxJsonObject,
            key: String,
            update: (Int) -> Unit
        ) {
            val value = (usage[key] as? JsonPrimitive)
                ?.contentOrNull
                ?.toIntOrNull()
                ?: return
            update(value.coerceAtLeast(0))
            sawUsage = true
        }

        private fun safeTokenSum(vararg values: Int): Int {
            return values.fold(0L) { total, value -> total + value }
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }
    }

            private data class ResponseToolCallState(
                var index: Int = -1,
                var id: String = "",
                var name: String = "",
                val arguments: StringBuilder = StringBuilder()
            )

    data class ModelAvailabilityCheckResult(
        val available: Boolean,
        val code: Int? = null,
        val message: String
    )

    fun resolveChatCompletionRouteInfo(
        modelOrScene: String,
        explicitApiBase: String? = null,
        explicitApiKey: String? = null,
        explicitCustomHeaders: Map<String, String>? = null,
        explicitModel: String? = null,
        explicitProtocolType: String? = null,
        explicitWireApi: String? = null
    ): ChatCompletionRouteInfo {
        return resolveSceneRequest(
            modelOrScene = modelOrScene,
            explicitApiBase = explicitApiBase,
            explicitApiKey = explicitApiKey,
            explicitCustomHeaders = explicitCustomHeaders,
            explicitModel = explicitModel,
            explicitProtocolType = explicitProtocolType,
            explicitWireApi = explicitWireApi
        ).toRouteInfo()
    }

    /**
     * The GUI/VLM tool is invoked by an Agent, so it must use the same model
     * route as that Agent unless the user explicitly configured a dedicated
     * VLM binding.  Falling back to the built-in VLM model here used to make
     * ACP calls silently jumped to a built-in VLM model even when the active
     * Agent had a completely different, configured Provider/model.
     */
    internal fun resolveSceneDefaultModel(
        sceneId: String,
        sceneDefaultModel: String?,
        sharedAgentModel: String?
    ): String? {
        if (sceneId != SceneOperationConfigStore.SCENE_ID) {
            return sceneDefaultModel?.trim()?.takeIf { it.isNotEmpty() }
        }
        return sharedAgentModel?.trim()?.takeIf { it.isNotEmpty() }
    }

    private val sceneCompletionClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private val completionJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun createLoggingEventListener(
        label: String,
        delegate: EventSourceListener,
        requestLogSeed: AiRequestLogSeed? = null
    ): EventSourceListener {
        val fullContent = StringBuilder()
        val rawEvents = mutableListOf<String>()
        var responseCode: Int? = null
        return object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: okhttp3.Response) {
                responseCode = response.code
                delegate.onOpen(eventSource, response)
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                delegate.onEvent(eventSource, id, type, data)
                runCatching {
                    appendStreamLogChunk(fullContent, data)
                    data.trim()
                        .takeIf { it.isNotEmpty() && it != "[DONE]" }
                        ?.let(rawEvents::add)
                }.onFailure {
                    OmniLog.w(
                        TAG,
                        "ignore stream log chunk for $label: ${it.message}"
                    )
                }
            }

            override fun onClosed(eventSource: EventSource) {
                delegate.onClosed(eventSource)
                runCatching {
                    if (fullContent.isNotEmpty()) {
                        logResponseBody(label, fullContent.toString())
                    }
                    requestLogSeed?.let { seed ->
                        persistAiRequestLog(
                            seed = seed,
                            success = true,
                            statusCode = responseCode,
                            responseJson = AiRequestLogStore.buildStreamResponseJson(rawEvents)
                        )
                    }
                }.onFailure {
                    OmniLog.w(
                        TAG,
                        "ignore stream close logging for $label: ${it.message}"
                    )
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: okhttp3.Response?
            ) {
                delegate.onFailure(eventSource, t, response)
                runCatching {
                    if (fullContent.isNotEmpty()) {
                        logResponseBody("$label (partial)", fullContent.toString())
                    }
                    requestLogSeed?.let { seed ->
                        val fallbackBody = runCatching {
                            response?.peekBody(1024L * 1024L)?.string()
                        }.getOrNull()
                        persistAiRequestLog(
                            seed = seed,
                            success = false,
                            statusCode = response?.code ?: responseCode,
                            responseJson = AiRequestLogStore.buildStreamResponseJson(rawEvents)
                                .ifBlank { AiRequestLogStore.prettyJsonOrRaw(fallbackBody) },
                            errorMessage = t?.message
                        )
                    }
                }.onFailure {
                    OmniLog.w(
                        TAG,
                        "ignore stream failure logging for $label: ${it.message}"
                    )
                }
            }
        }
    }

    private fun appendStreamLogChunk(buffer: StringBuilder, data: String) {
        val chunk = extractStreamLogChunk(data)
        if (chunk.isBlank()) return
        buffer.append(chunk)
    }

    private fun extractStreamLogChunk(data: String): String {
        val trimmed = data.trim()
        if (trimmed.isEmpty() || trimmed == "[DONE]") {
            return ""
        }

        return runCatching {
            val json = JSONObject(trimmed)
            when {
                json.optString("type") == "response.output_text.delta" -> json.optString("delta")
                json.optString("type") == "response.reasoning_summary_text.delta" -> json.optString("delta")
                json.optString("type") == "response.reasoning.delta" -> json.optString("delta")
                json.has("output_text") -> json.optString("output_text")
                json.has("output") -> extractResponsesOutputText(json).ifBlank {
                    extractResponsesReasoningText(json)
                }
                json.has("text") -> json.optString("text")
                json.has("message") && json.opt("message") is String -> json.optString("message")
                json.has("choices") -> {
                    val firstChoice = json.optJSONArray("choices")?.optJSONObject(0)
                    val delta = firstChoice?.optJSONObject("delta")
                    val message = firstChoice?.optJSONObject("message")

                    when {
                        delta != null -> {
                            extractTextPayload(delta.opt("content")).ifBlank {
                                listOf(
                                    delta.opt("reasoning_content"),
                                    delta.opt("reasoning"),
                                    delta.opt("thinking")
                                )
                                    .asSequence()
                                    .map { extractTextPayload(it) }
                                    .firstOrNull { it.isNotBlank() }
                                    .orEmpty()
                            }
                        }
                        message != null -> {
                            extractTextPayload(message.opt("content")).ifBlank {
                                listOf(
                                    message.opt("reasoning_content"),
                                    message.opt("reasoning"),
                                    message.opt("thinking")
                                )
                                    .asSequence()
                                    .map { extractTextPayload(it) }
                                    .firstOrNull { it.isNotBlank() }
                                    .orEmpty()
                            }
                        }
                        else -> trimmed
                    }
                }
                else -> trimmed
            }
        }.getOrElse { trimmed }
    }

    private fun logResponseBody(label: String, body: String?) {
        runCatching {
            val normalized = body?.trim()?.takeIf { it.isNotEmpty() } ?: "<empty>"
            val chunks = normalized.chunked(RESPONSE_LOG_CHUNK_SIZE)
            chunks.forEachIndexed { index, chunk ->
                val suffix = if (chunks.size == 1) "" else " (${index + 1}/${chunks.size})"
                OmniLog.i(TAG, "$label Response Body$suffix: $chunk")
            }
        }.onFailure {
            OmniLog.w(TAG, "ignore response body log failure: ${it.message}")
        }
    }

    private fun persistAiRequestLog(
        seed: AiRequestLogSeed,
        success: Boolean,
        statusCode: Int? = null,
        responseJson: String = "",
        errorMessage: String? = null
    ) {
        runCatching {
            AiRequestLogStore.append(
                AiRequestLogEntry(
                    label = seed.label,
                    model = seed.model,
                    protocolType = seed.protocolType,
                    url = seed.url,
                    method = seed.method,
                    stream = seed.stream,
                    statusCode = statusCode,
                    success = success,
                    requestJson = AiRequestLogStore.prettyJsonOrRaw(seed.requestJson),
                    responseJson = responseJson,
                    errorMessage = errorMessage?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
        }.onFailure {
            OmniLog.w(
                TAG,
                "ignore AI request log persistence failure for ${seed.label}: ${it.message}"
            )
        }
        // Record token usage from every successful LLM response
        if (success && responseJson.isNotEmpty()) {
            runCatching { recordTokenUsageFromResponse(seed, responseJson) }
                .onFailure {
                    OmniLog.w(TAG, "ignore token usage recording failure: ${it.message}")
                }
        }
    }

    /**
     * 从 LLM 响应中提取 usage 并写入 token_usage_records 表。
     * 兼容流式（JSONArray of chunks）和非流式（单个 JSONObject）响应。
     */
    private fun recordTokenUsageFromResponse(seed: AiRequestLogSeed, responseJson: String) {
        val normalized = responseJson.trim()
        if (normalized.isEmpty()) return
        OmniLog.d(TAG, "[TokenUsage] parsing response for model=${seed.model}, stream=${seed.stream}, responseLen=${normalized.length}")

        // Find the usage object — streaming responses are a JSONArray, non-streaming is a JSONObject
        val usageObj: JSONObject? = if (seed.protocolType.equals("anthropic", ignoreCase = true)) {
            extractAnthropicUsageObject(normalized)
        } else {
            null
        } ?: when {
            normalized.startsWith("[") -> {
                // Streaming: scan chunks from end to find the one with usage
                val arr = JSONArray(normalized)
                var found: JSONObject? = null
                for (i in arr.length() - 1 downTo 0) {
                    val chunk = arr.optJSONObject(i) ?: continue
                    val u = extractUsageObject(chunk)
                    if (u != null && (readUsageInt(u, "completion_tokens", "output_tokens") >= 0
                                || u.optInt("total_tokens", -1) >= 0)) {
                        found = u
                        break
                    }
                }
                found
            }
            normalized.startsWith("{") -> {
                extractUsageObject(JSONObject(normalized))
            }
            else -> null
        }

        if (usageObj == null) {
            OmniLog.d(TAG, "[TokenUsage] no usage object found in response for model=${seed.model}")
            return
        }

        val promptTokens = readUsageInt(usageObj, "prompt_tokens", "input_tokens").coerceAtLeast(0)
        val completionTokens = readUsageInt(usageObj, "completion_tokens", "output_tokens").coerceAtLeast(0)
        val promptDetails = usageObj.optJSONObject("prompt_tokens_details")
            ?: usageObj.optJSONObject("input_tokens_details")
        val cachedTokens = promptDetails?.optInt("cached_tokens", 0) ?: 0
        val cacheCreationTokens = promptDetails?.optInt("cache_creation_tokens", 0) ?: 0
        if (promptTokens == 0 && completionTokens == 0 && cachedTokens == 0) {
            OmniLog.d(
                TAG,
                "[TokenUsage] usage is empty (prompt=0, completion=0, cached=0) for model=${seed.model}"
            )
            return
        }

        // Extract detailed breakdown if available
        val details = usageObj.optJSONObject("completion_tokens_details")
        val reasoningTokens = details?.optInt("reasoning_tokens", 0) ?: 0
        val textTokens = details?.optInt("text_tokens", 0) ?: 0

        OmniLog.i(
            TAG,
            "[TokenUsage] recording: model=${seed.model}, " +
                "prompt=$promptTokens, completion=$completionTokens, " +
                "reasoning=$reasoningTokens, text=$textTokens, cached=$cachedTokens, " +
                "cacheCreation=$cacheCreationTokens, " +
                "stream=${seed.stream}, url=${seed.url}"
        )

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                DatabaseHelper.insertTokenUsageRecord(
                    TokenUsageRecord(
                        conversationId = seed.conversationId,
                        model = seed.model,
                        promptTokens = promptTokens,
                        completionTokens = completionTokens,
                        reasoningTokens = reasoningTokens,
                        textTokens = textTokens,
                        cachedTokens = cachedTokens,
                        cacheCreationTokens = cacheCreationTokens,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }.onFailure {
                OmniLog.w(TAG, "Failed to insert token usage record: ${it.message}")
            }
        }
    }

    private fun extractUsageObject(payload: JSONObject): JSONObject? {
        return payload.optJSONObject("usage")
            ?: payload.optJSONObject("response")?.optJSONObject("usage")
    }

    private fun extractAnthropicUsageObject(responseJson: String): JSONObject? {
        return normalizeAnthropicUsageResponse(responseJson)?.let(::JSONObject)
    }

    private fun normalizeAnthropicUsageResponse(responseJson: String): String? {
        val accumulator = AnthropicUsageAccumulator()

        fun mergePayload(payload: KxJsonObject) {
            accumulator.merge(payload["usage"] as? KxJsonObject)
            accumulator.merge(
                (payload["message"] as? KxJsonObject)?.get("usage") as? KxJsonObject
            )
            accumulator.merge(
                (payload["response"] as? KxJsonObject)?.get("usage") as? KxJsonObject
            )
        }

        when (val payload = runCatching {
            completionJson.parseToJsonElement(responseJson)
        }.getOrNull()) {
            is KxJsonArray -> {
                payload.forEach { event ->
                    (event as? KxJsonObject)?.let(::mergePayload)
                }
            }
            is KxJsonObject -> mergePayload(payload)
            else -> Unit
        }
        return accumulator.toOpenAIUsage()?.toString()
    }

    private fun readUsageInt(usage: JSONObject, primaryKey: String, fallbackKey: String): Int {
        return when {
            usage.has(primaryKey) -> usage.optInt(primaryKey, -1)
            usage.has(fallbackKey) -> usage.optInt(fallbackKey, -1)
            else -> -1
        }
    }

    private fun conversationIdFromPromptCacheKey(promptCacheKey: String?): Long {
        return Regex("conversation:(\\d+)$")
            .find(promptCacheKey.orEmpty().trim())
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: 0L
    }

    private fun conversationIdFromRequestJson(requestJson: String): Long {
        val key = runCatching {
            JSONObject(requestJson).optString("prompt_cache_key")
        }.getOrNull()
        return conversationIdFromPromptCacheKey(key)
    }

    private fun logSceneProfile(resolved: ResolvedSceneRequest) {
        val profile = resolved.sceneProfile ?: return
        OmniLog.i(
            TAG,
            "scene_profile scene=${profile.sceneId} model=${resolved.resolvedModel} transport=${resolved.effectiveTransport.wireValue} parser=${resolved.responseParser.wireValue} source=${profile.modelSource.wireValue} config_source=${profile.configSource.wireValue} override_group=${profile.overrideGroup.orEmpty()} custom_api_base=${resolved.customApiBaseApplied} binding_applied=${resolved.bindingApplied} binding_profile=${resolved.providerProfileId.orEmpty()} binding_profile_missing=${resolved.bindingProfileMissing} override_applied=${resolved.overrideApplied} override_model=${resolved.overrideModel.orEmpty()}"
        )
    }

    private fun ResolvedSceneRequest.toRouteInfo(): ChatCompletionRouteInfo {
        return ChatCompletionRouteInfo(
            requestedModel = requestedModel,
            resolvedModel = resolvedModel,
            apiBase = apiBase,
            providerProfileId = providerProfileId,
            providerProfileName = providerProfileName,
            routeTag = routeTag,
            bindingApplied = bindingApplied,
            bindingProfileMissing = bindingProfileMissing,
            overrideApplied = overrideApplied,
            protocolType = protocolType,
            wireApi = wireApi,
            requiresReasoningEcho = DeepSeekProvider.shouldUseOfficialAdapter(
                protocolType = protocolType,
                apiBase = apiBase
            ),
            requiresAnthropicThinkingReplay = DeepSeekProvider.normalizeProtocolType(protocolType) == "anthropic"
        )
    }

    private fun KxJsonObject.findProviderModelValue(vararg keys: String): JsonElement? {
        for (key in keys) {
            this[key]?.let { return it }
        }
        return null
    }

    private fun parseProviderModelInt(
        itemObj: KxJsonObject,
        directKeys: List<String>,
        nestedObjectKeys: List<String> = listOf("limits", "limit"),
        nestedValueKeys: List<String> = directKeys
    ): Int? {
        (itemObj.findProviderModelValue(*directKeys.toTypedArray()) as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.let { return it }
        for (nestedKey in nestedObjectKeys) {
            val nested = itemObj[nestedKey] as? KxJsonObject ?: continue
            (nested.findProviderModelValue(*nestedValueKeys.toTypedArray()) as? JsonPrimitive)
                ?.contentOrNull
                ?.trim()
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?.let { return it }
        }
        return null
    }

    private fun parseProviderModelBoolean(
        itemObj: KxJsonObject,
        directKeys: List<String>,
        nestedObjectKeys: List<String> = listOf("capabilities", "capability", "features", "feature"),
        nestedValueKeys: List<String> = directKeys
    ): Boolean? {
        fun parseValue(element: JsonElement?): Boolean? {
            val primitive = element as? JsonPrimitive ?: return null
            primitive.booleanOrNull?.let { return it }
            return when (primitive.contentOrNull?.trim()?.lowercase()) {
                "true", "1", "yes", "supported", "enabled" -> true
                "false", "0", "no", "unsupported", "disabled" -> false
                else -> null
            }
        }

        parseValue(itemObj.findProviderModelValue(*directKeys.toTypedArray()))?.let { return it }
        for (nestedKey in nestedObjectKeys) {
            val nested = itemObj[nestedKey] as? KxJsonObject ?: continue
            parseValue(nested.findProviderModelValue(*nestedValueKeys.toTypedArray()))?.let { return it }
        }
        return null
    }

    private fun parseProviderModelStringList(
        itemObj: KxJsonObject,
        directKeys: List<String>,
        nestedObjectKeys: List<String> = listOf("modalities", "modality"),
        nestedValueKeys: List<String> = directKeys
    ): List<String> {
        fun parseValue(element: JsonElement?): List<String> {
            return when (element) {
                is KxJsonArray -> element.mapNotNull {
                    (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
                }
                is JsonPrimitive -> element.contentOrNull
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let(::listOf)
                    ?: emptyList()
                else -> emptyList()
            }
        }

        parseValue(itemObj.findProviderModelValue(*directKeys.toTypedArray()))
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        for (nestedKey in nestedObjectKeys) {
            val nested = itemObj[nestedKey] as? KxJsonObject ?: continue
            parseValue(nested.findProviderModelValue(*nestedValueKeys.toTypedArray()))
                .takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        return emptyList()
    }

    private fun appendAnthropicContentBlocks(
        target: JSONArray,
        content: JsonElement?
    ) {
        when (content) {
            null -> Unit
            is kotlinx.serialization.json.JsonPrimitive -> {
                val text = content.contentOrNull ?: content.toString()
                if (text.isNotEmpty()) {
                    val block = JSONObject()
                    block.put("type", "text")
                    block.put("text", text)
                    target.put(
                        block
                    )
                }
            }
            is kotlinx.serialization.json.JsonArray -> {
                content.forEach { block ->
                    when (block) {
                        is kotlinx.serialization.json.JsonObject -> target.put(JSONObject(block.toString()))
                        is kotlinx.serialization.json.JsonPrimitive -> {
                            val text = block.contentOrNull ?: block.toString()
                            if (text.isNotEmpty()) {
                                val textBlock = JSONObject()
                                textBlock.put("type", "text")
                                textBlock.put("text", text)
                                target.put(
                                    textBlock
                                )
                            }
                        }
                        else -> Unit
                    }
                }
            }
            else -> {
                val block = JSONObject()
                block.put("type", "text")
                block.put("text", content.toString())
                target.put(
                    block
                )
            }
        }
    }

    private fun buildAnthropicAssistantContent(message: ChatCompletionMessage): Any? {
        val content = JSONArray()
        message.reasoningContent?.trim()?.takeIf { it.isNotEmpty() }?.let { reasoning ->
            val reasoningBlock = JSONObject()
            reasoningBlock.put("type", "text")
            reasoningBlock.put("text", reasoning)
            content.put(
                reasoningBlock
            )
        }
        appendAnthropicContentBlocks(content, message.content)
        message.toolCalls.orEmpty().forEach { toolCall ->
            val inputJson = runCatching { JSONObject(toolCall.function.arguments) }.getOrElse { JSONObject() }
            val toolUseBlock = JSONObject()
            toolUseBlock.put("type", "tool_use")
            toolUseBlock.put("id", toolCall.id)
            toolUseBlock.put("name", toolCall.function.name)
            toolUseBlock.put("input", inputJson)
            content.put(
                toolUseBlock
            )
        }
        if (content.length() == 0) {
            return null
        }
        if (
            content.length() == 1 &&
            message.reasoningContent.isNullOrBlank() &&
            message.toolCalls.isNullOrEmpty()
        ) {
            val single = content.optJSONObject(0)
            if (single?.optString("type") == "text") {
                return single.optString("text", "")
            }
        }
        return content
    }

    private fun buildAnthropicTextBlock(text: String): JSONObject {
        val block = JSONObject()
        block.put("type", "text")
        block.put("text", text)
        return block
    }

    private fun buildAnthropicMessage(
        role: String,
        content: Any
    ): JSONObject {
        val message = JSONObject()
        message.put("role", role)
        message.put("content", content)
        return message
    }

    private fun resolveSceneRequest(
        modelOrScene: String,
        explicitApiBase: String? = null,
        explicitApiKey: String? = null,
        explicitCustomHeaders: Map<String, String>? = null,
        explicitModel: String? = null,
        explicitProtocolType: String? = null,
        explicitWireApi: String? = null,
        @Suppress("UNUSED_PARAMETER") defaultTransport: ModelSceneRegistry.SceneTransport = ModelSceneRegistry.SceneTransport.OPENAI_COMPATIBLE
    ): ResolvedSceneRequest {
        val requestedModel = modelOrScene.trim()
        val sceneProfile = if (ModelSceneRegistry.isSceneId(requestedModel)) {
            ModelSceneRegistry.getRuntimeProfile(requestedModel)
        } else {
            null
        }
        val explicitBase = explicitApiBase?.let(::normalizeApiBase)
        val explicitKey = explicitApiKey?.trim()?.takeIf { it.isNotEmpty() }
        val explicitHeaders = ProviderCustomHeaderUtils.sanitizeCustomHeaders(explicitCustomHeaders)
        val explicitResolvedModel = explicitModel?.trim()?.takeIf { it.isNotEmpty() }
        val explicitProtocol = explicitProtocolType
            ?.let(DeepSeekProvider::normalizeProtocolType)
        val explicitWire = explicitWireApi?.let(OpenAiWireApi::normalize)
        val providerConfig = if (explicitBase == null) {
            ModelProviderConfigStore.getConfig()
        } else {
            ModelProviderConfig(
                baseUrl = explicitBase,
                apiKey = explicitKey.orEmpty(),
                customHeaders = explicitHeaders,
                source = "explicit"
            )
        }
        val directSceneBinding = sceneProfile?.sceneId?.let(SceneModelBindingStore::getBinding)
        val sharedAgentBinding = if (
            sceneProfile?.sceneId == SceneOperationConfigStore.SCENE_ID
        ) {
            SceneModelBindingStore.getBinding("scene.dispatch.model")
        } else {
            null
        }
        fun resolveBindingProfile(binding: cn.com.omnimind.baselib.llm.SceneModelBindingEntry?) =
            binding?.providerProfileId?.let { profileId ->
            ModelProviderConfigStore.getProfile(profileId)
                ?: PlatformAiProvisioner.officialProfileOrNull()
                    ?.takeIf { OmniOfficialProvider.isOfficialProfile(profileId) }
            }
        val directBoundProfile = resolveBindingProfile(directSceneBinding)
        val sharedAgentBoundProfile = resolveBindingProfile(sharedAgentBinding)
        val sharedAgentModel = sharedAgentBinding
            ?.takeIf { sharedAgentBoundProfile?.isConfigured() == true }
            ?.modelId
            ?: ModelSceneRegistry.getRuntimeProfile("scene.dispatch.model")?.model
        val defaultResolvedModel = when {
            sceneProfile != null -> resolveSceneDefaultModel(
                sceneId = sceneProfile.sceneId,
                sceneDefaultModel = sceneProfile.model,
                sharedAgentModel = if (
                    sceneProfile.sceneId == SceneOperationConfigStore.SCENE_ID
                ) {
                    sharedAgentModel
                } else {
                    null
                }
            )
            requestedModel.startsWith("scene.") -> ModelSceneRegistry.resolveModel(requestedModel)
            else -> requestedModel
        }
        // A dedicated VLM binding wins. If it is absent or stale, inherit the
        // active Agent binding so every Agent capability uses one Provider.
        val sceneBinding = when {
            directSceneBinding != null && directBoundProfile?.isConfigured() == true ->
                directSceneBinding
            sceneProfile?.sceneId == SceneOperationConfigStore.SCENE_ID &&
                sharedAgentBinding != null && sharedAgentBoundProfile?.isConfigured() == true ->
                sharedAgentBinding
            else -> directSceneBinding
        }
        val boundProfile = resolveBindingProfile(sceneBinding)
        // VLM is an Agent capability, not a second model route. Do not use the
        // legacy official VLM service as an implicit fallback: when the Agent
        // Provider/model is missing, the caller must receive a clear config
        // error instead of silently selecting a second VLM model.
        val bindingApplied =
            explicitBase == null &&
                explicitResolvedModel == null &&
                sceneBinding != null &&
                boundProfile?.isConfigured() == true
        val bindingProfileMissing =
            explicitBase == null &&
                explicitResolvedModel == null &&
                sceneBinding != null &&
                boundProfile == null
        val overrideModel = when {
            explicitResolvedModel != null -> explicitResolvedModel
            bindingApplied -> sceneBinding?.modelId
            else -> null
        }
        val overrideApplied =
            explicitBase != null ||
                explicitResolvedModel != null ||
                bindingApplied

        val providerBase = when {
            explicitBase != null -> explicitBase
            bindingApplied -> boundProfile?.baseUrl
            providerConfig.isConfigured() -> providerConfig.baseUrl
            else -> null
        }
        val providerKey = when {
            explicitBase != null -> explicitKey
            bindingApplied -> boundProfile?.apiKey?.takeIf { it.isNotBlank() }
            providerBase != null -> providerConfig.apiKey.takeIf { it.isNotBlank() }
            else -> null
        }
        val providerHeaders = when {
            explicitBase != null -> explicitHeaders
            bindingApplied -> ProviderCustomHeaderUtils.sanitizeCustomHeaders(boundProfile?.customHeaders)
            providerBase != null -> ProviderCustomHeaderUtils.sanitizeCustomHeaders(
                providerConfig.customHeaders
            )
            else -> emptyMap()
        }
        val protocolType = when {
            explicitProtocol != null -> explicitProtocol
            explicitBase != null -> DeepSeekProvider.normalizeProtocolType(null)
            bindingApplied -> boundProfile?.protocolType?.ifEmpty { "openai_compatible" } ?: "openai_compatible"
            else -> ModelProviderConfigStore.getEditingProfile().protocolType.ifEmpty { "openai_compatible" }
        }
        val wireApi = when {
            explicitWire != null -> explicitWire
            bindingApplied -> boundProfile?.wireApi ?: OpenAiWireApi.CHAT_COMPLETIONS
            providerBase != null -> providerConfig.wireApi
            else -> ModelProviderConfigStore.getEditingProfile().wireApi
        }
        val effectiveTransport = sceneProfile?.transport ?: defaultTransport
        val responseParser = sceneProfile?.responseParser ?: when (effectiveTransport) {
            ModelSceneRegistry.SceneTransport.OPENAI_COMPATIBLE,
            ModelSceneRegistry.SceneTransport.CONVERSATION_CHAT -> ModelSceneRegistry.ResponseParser.TEXT_CONTENT
        }
        val aiAccess = OmniAccount.currentAiRequestAccess()
        val explicitOfficialProvider =
            explicitBase != null &&
                explicitKey == null &&
                explicitHeaders.isEmpty() &&
                aiAccess.platformGatewayUrl?.let(::normalizeApiBase) == explicitBase
        val officialProviderSelected =
            (bindingApplied && OmniOfficialProvider.isOfficialProfile(boundProfile?.id)) ||
                explicitOfficialProvider
        val routeTag = when {
            officialProviderSelected -> AiRequestTransportPolicy.PLATFORM_ROUTE_TAG
            overrideApplied -> ROUTE_CUSTOM_OPENAI_COMPAT
            effectiveTransport == ModelSceneRegistry.SceneTransport.OPENAI_COMPATIBLE -> "openai_compatible"
            effectiveTransport == ModelSceneRegistry.SceneTransport.CONVERSATION_CHAT -> "conversation_chat"
            else -> null
        }

        if (officialProviderSelected) {
            aiAccess.unavailableReason?.let { throw IllegalStateException(it) }
        }
        val transportRoute = AiRequestTransportPolicy.apply(
            access = aiAccess,
            byokRoute = AiTransportRoute(
                apiBase = providerBase,
                apiKey = providerKey,
                customHeaders = providerHeaders,
                protocolType = protocolType,
                wireApi = wireApi,
                routeTag = routeTag,
            ),
        )

        return ResolvedSceneRequest(
            requestedModel = requestedModel,
            resolvedModel = when {
                explicitResolvedModel != null -> explicitResolvedModel
                bindingApplied -> sceneBinding?.modelId.orEmpty()
                else -> defaultResolvedModel.orEmpty()
            },
            sceneProfile = sceneProfile,
            effectiveTransport = effectiveTransport,
            responseParser = responseParser,
            apiBase = transportRoute.apiBase,
            apiKey = transportRoute.apiKey,
            customHeaders = transportRoute.customHeaders,
            providerProfileId = when {
                bindingApplied -> boundProfile?.id
                else -> null
            },
            providerProfileName = when {
                bindingApplied -> boundProfile?.name
                else -> null
            },
            routeTag = transportRoute.routeTag,
            customApiBaseApplied = !transportRoute.apiBase.isNullOrBlank(),
            bindingApplied = bindingApplied,
            bindingProfileMissing = bindingProfileMissing,
            overrideApplied = overrideApplied,
            overrideModel = overrideModel,
            protocolType = transportRoute.protocolType,
            wireApi = transportRoute.wireApi
        )
    }

    private fun normalizeApiBase(input: String): String? {
        return ModelProviderConfigStore.normalizeBaseUrl(input)
    }

    private fun buildOpenAIChatCompletionsUrl(apiBase: String): String {
        val base = ModelProviderConfigStore.stripDirectRequestUrlMarker(apiBase)
        if (ModelProviderConfigStore.hasDirectRequestUrlMarker(apiBase)) {
            return base
        }
        return if (ModelProviderConfigStore.hasVersionedBasePath(base)) {
            "$base/chat/completions"
        } else {
            "$base/v1/chat/completions"
        }
    }

    private fun buildOpenAIResponsesUrl(apiBase: String): String {
        val base = ModelProviderConfigStore.stripDirectRequestUrlMarker(apiBase)
        if (ModelProviderConfigStore.hasDirectRequestUrlMarker(apiBase)) {
            return base
        }
        return if (ModelProviderConfigStore.hasVersionedBasePath(base)) {
            "$base/responses"
        } else {
            "$base/v1/responses"
        }
    }

    private fun buildOpenAIInferenceUrl(apiBase: String, wireApi: String): String {
        return if (OpenAiWireApi.isResponses(wireApi)) {
            buildOpenAIResponsesUrl(apiBase)
        } else {
            buildOpenAIChatCompletionsUrl(apiBase)
        }
    }

    private fun buildOpenAIModelsUrl(apiBase: String): String {
        val base = ModelProviderConfigStore.stripDirectRequestUrlMarker(apiBase)
        if (ModelProviderConfigStore.hasDirectRequestUrlMarker(apiBase)) {
            return base
        }
        return if (ModelProviderConfigStore.hasVersionedBasePath(base)) {
            "$base/models"
        } else {
            "$base/v1/models"
        }
    }

    private fun buildAnthropicModelsUrl(apiBase: String): String {
        val base = ModelProviderConfigStore.stripDirectRequestUrlMarker(apiBase)
        if (ModelProviderConfigStore.hasDirectRequestUrlMarker(apiBase)) {
            return base
        }
        return if (ModelProviderConfigStore.hasVersionedBasePath(base)) {
            "$base/models"
        } else {
            "$base/v1/models"
        }
    }

    private fun buildOpenAIRequestBuilder(
        url: String,
        requestBody: okhttp3.RequestBody? = null,
        apiKey: String? = null,
        customHeaders: Map<String, String> = emptyMap()
    ): Request.Builder {
        val headers = linkedMapOf<String, String>(
            "Content-Type" to "application/json"
        ).apply {
            if (!apiKey.isNullOrBlank()) {
                put("Authorization", "Bearer ${apiKey.trim()}")
            }
        }
        return buildJsonRequestBuilder(
            url = url,
            headers = ProviderCustomHeaderUtils.mergeHeaders(headers, customHeaders),
            requestBody = requestBody
        )
    }

    private fun buildJsonRequestBuilder(
        url: String,
        headers: Map<String, String>,
        requestBody: okhttp3.RequestBody? = null
    ): Request.Builder {
        val builder = Request.Builder()
            .url(url)
        headers.forEach { (key, value) ->
            builder.header(key, value)
        }
        if (requestBody != null) {
            builder.post(requestBody)
        }
        return builder
    }

    // ---- Anthropic protocol helpers ----

    private fun buildAnthropicMessagesUrl(apiBase: String): String {
        val base = ModelProviderConfigStore.stripDirectRequestUrlMarker(apiBase)
        if (ModelProviderConfigStore.hasDirectRequestUrlMarker(apiBase)) {
            return base
        }
        return if (base.endsWith("/v1", ignoreCase = true)) {
            "$base/messages"
        } else {
            "$base/v1/messages"
        }
    }

    private fun hasCacheControl(requestJson: String): Boolean {
        return requestJson.contains("cache_control")
    }

    private fun buildAnthropicRequestBuilder(
        url: String,
        requestBody: okhttp3.RequestBody? = null,
        apiKey: String?,
        hasCacheControl: Boolean = false,
        customHeaders: Map<String, String> = emptyMap()
    ): Request.Builder {
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "anthropic-version" to "2023-06-01"
        ).apply {
            if (!apiKey.isNullOrBlank()) {
                put("x-api-key", apiKey.trim())
            }
        }
        if (hasCacheControl) {
            headers["anthropic-beta"] = "prompt-caching-2024-07-31"
        }
        return buildJsonRequestBuilder(
            url = url,
            headers = ProviderCustomHeaderUtils.mergeHeaders(headers, customHeaders),
            requestBody = requestBody
        )
    }

    private fun logRequestHeaders(label: String, headers: Map<String, String>) {
        OmniLog.d(
            TAG,
            "$label headers=${ProviderCustomHeaderUtils.redactHeadersForLog(headers)}"
        )
    }

    /**
     * 把内部 OpenAI 风格的 ChatCompletionRequest 转换为 Anthropic Messages API JSON。
     *
     * 转换规则：
     * - system 消息合并提取到顶层 system 字段
     * - assistant 的 tool_calls → content[].type = "tool_use"
     * - tool role → user 消息，content[].type = "tool_result"
     * - tools[].function.parameters → tools[].input_schema
     * - cache_control 字段原样保留，并默认开启 Anthropic 自动缓存
     */
    fun convertToAnthropicRequestJson(request: ChatCompletionRequest): String {
        val obj = JSONObject()
        obj.put("model", request.model)
        obj.put("max_tokens", request.maxTokens ?: request.maxCompletionTokens ?: 4096)
        request.temperature?.let { obj.put("temperature", it) }

        // Extract system messages → top-level system
        val systemMessages = request.messages.filter { it.role == "system" }
        val nonSystemMessages = request.messages.filter { it.role != "system" }

        if (systemMessages.isNotEmpty()) {
            val systemContent = systemMessages.map { msg ->
                val contentRaw = msg.content
                when {
                    contentRaw == null -> null
                    contentRaw is kotlinx.serialization.json.JsonPrimitive -> {
                        val text = contentRaw.content
                        buildAnthropicTextBlock(text)
                    }
                    contentRaw is kotlinx.serialization.json.JsonArray -> {
                        // preserve cache_control from array blocks
                        val arr = JSONArray()
                        contentRaw.forEach { block ->
                            if (block is kotlinx.serialization.json.JsonObject) {
                                arr.put(JSONObject(block.toString()))
                            }
                        }
                        if (arr.length() == 1) arr.optJSONObject(0) else arr
                    }
                    else -> buildAnthropicTextBlock(contentRaw.toString())
                }
            }.filterNotNull()

            if (systemContent.size == 1 && systemContent[0] is JSONObject) {
                val single = systemContent[0] as JSONObject
                if (!single.has("cache_control")) {
                    obj.put("system", single.optString("text", ""))
                } else {
                    obj.put("system", JSONArray().put(single))
                }
            } else {
                val arr = JSONArray()
                systemContent.forEach { c ->
                    when (c) {
                        is JSONObject -> arr.put(c)
                        is JSONArray -> for (i in 0 until c.length()) arr.put(c.opt(i))
                        else -> arr.put(buildAnthropicTextBlock(c.toString()))
                    }
                }
                obj.put("system", arr)
            }
        }

        // Convert messages
        val messages = JSONArray()
        for (msg in nonSystemMessages) {
            when (msg.role) {
                "assistant" -> {
                    val content = buildAnthropicAssistantContent(msg)
                    if (content != null) {
                        messages.put(buildAnthropicMessage("assistant", content))
                    }
                }
                "tool" -> {
                    // merge consecutive tool results into a single user message
                    val toolResultBlock = JSONObject()
                    toolResultBlock.put("type", "tool_result")
                    toolResultBlock.put("tool_use_id", msg.toolCallId ?: "")
                    toolResultBlock.put(
                        "content",
                        msg.content?.let {
                            if (it is kotlinx.serialization.json.JsonPrimitive) it.content else it.toString()
                        } ?: ""
                    )
                    // Try to merge with previous user message if it's a tool_result batch
                    val lastMsg = if (messages.length() > 0) messages.optJSONObject(messages.length() - 1) else null
                    if (lastMsg != null && lastMsg.optString("role") == "user" &&
                        lastMsg.opt("content") is JSONArray
                    ) {
                        val prevContent = lastMsg.getJSONArray("content")
                        if (prevContent.length() > 0 &&
                            prevContent.optJSONObject(0)?.optString("type") == "tool_result"
                        ) {
                            prevContent.put(toolResultBlock)
                        } else {
                            val toolResultBatch = JSONArray()
                            toolResultBatch.put(toolResultBlock)
                            messages.put(
                                buildAnthropicMessage("user", toolResultBatch)
                            )
                        }
                    } else {
                        val toolResultBatch = JSONArray()
                        toolResultBatch.put(toolResultBlock)
                        messages.put(
                            buildAnthropicMessage("user", toolResultBatch)
                        )
                    }
                }
                else -> {
                    val content = convertContentToAnthropicFormat(msg.content)
                    if (content != null) {
                        messages.put(buildAnthropicMessage(msg.role, content))
                    }
                }
            }
        }
        obj.put("messages", messages)

        // Convert tools
        if (request.tools.isNotEmpty()) {
            val tools = JSONArray()
            for (tool in request.tools) {
                val f = tool.function
                val toolObj = JSONObject()
                toolObj.put("name", f.name)
                if (!f.description.isNullOrBlank()) toolObj.put("description", f.description)
                toolObj.put("input_schema", JSONObject(f.parameters.toString()))
                tools.put(toolObj)
            }
            obj.put("tools", tools)
        }

        if (request.stream) {
            obj.put("stream", true)
        }

        val requestJson = obj.toString() ?: "{}"
        return applyAnthropicAutomaticCacheControl(requestJson)
    }

    private fun applyAnthropicAutomaticCacheControl(requestJson: String): String {
        var payload = runCatching {
            completionJson.parseToJsonElement(requestJson) as? KxJsonObject
        }.getOrNull() ?: return requestJson
        // Older builds emitted a request-level marker. Use Pi-style explicit
        // breakpoints instead: end of system, end of tools, and the latest
        // conversation message. This makes the reusable hierarchy unambiguous.
        payload = KxJsonObject(payload.filterKeys { it != "cache_control" })
        var remaining = (
            ANTHROPIC_MAX_CACHE_BREAKPOINTS - countAnthropicExplicitCacheBreakpoints(payload)
        ).coerceAtLeast(0)
        if (remaining == 0) return payload.toString()

        fun cacheControl(): KxJsonObject = buildJsonObject {
            put("type", JsonPrimitive(ANTHROPIC_EPHEMERAL_CACHE_TYPE))
        }

        fun markLastObject(array: KxJsonArray): KxJsonArray? {
            val index = array.indexOfLast { item ->
                item is KxJsonObject && !item.containsKey("cache_control")
            }
            if (index < 0) return null
            return KxJsonArray(array.mapIndexed { itemIndex, item ->
                if (itemIndex == index) {
                    KxJsonObject((item as KxJsonObject) + ("cache_control" to cacheControl()))
                } else {
                    item
                }
            })
        }

        (payload["system"] as? KxJsonArray)?.let { system ->
            if (remaining > 0) {
                markLastObject(system)?.let { marked ->
                    payload = KxJsonObject(payload + ("system" to marked))
                    remaining -= 1
                }
            }
        }

        (payload["tools"] as? KxJsonArray)?.let { tools ->
            if (remaining > 0) {
                markLastObject(tools)?.let { marked ->
                    payload = KxJsonObject(payload + ("tools" to marked))
                    remaining -= 1
                }
            }
        }

        val messages = payload["messages"] as? KxJsonArray
        if (messages != null && remaining > 0) {
            val messageIndex = messages.indexOfLast { it is KxJsonObject }
            if (messageIndex >= 0) {
                val markedMessages = KxJsonArray(messages.mapIndexed { index, item ->
                    if (index != messageIndex) return@mapIndexed item
                    val message = item as KxJsonObject
                    val content = message["content"]
                    val markedContent = when (content) {
                        is KxJsonArray -> markLastObject(content)
                        is JsonPrimitive -> buildJsonArray {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", content)
                                put("cache_control", cacheControl())
                            })
                        }
                        else -> null
                    }
                    if (markedContent == null) message else KxJsonObject(
                        message + ("content" to markedContent)
                    )
                })
                payload = KxJsonObject(payload + ("messages" to markedMessages))
            }
        }
        return payload.toString()
    }

    private fun countAnthropicExplicitCacheBreakpoints(requestJson: KxJsonObject): Int {
        var count = 0

        val tools = requestJson["tools"] as? KxJsonArray
        if (tools != null) {
            count += tools.count { item ->
                (item as? KxJsonObject)?.containsKey("cache_control") == true
            }
        }

        count += countAnthropicCacheControlBlocks(requestJson["system"])

        val messages = requestJson["messages"] as? KxJsonArray
        if (messages != null) {
            for (message in messages) {
                val messageObj = message as? KxJsonObject ?: continue
                count += countAnthropicCacheControlBlocks(messageObj["content"])
            }
        }

        return count
    }

    private fun countAnthropicCacheControlBlocks(raw: JsonElement?): Int {
        return when (raw) {
            is KxJsonObject -> if (raw.containsKey("cache_control")) 1 else 0
            is KxJsonArray -> raw.sumOf(::countAnthropicCacheControlBlocks)
            else -> 0
        }
    }

    private fun parseProviderModelsResponse(responseBody: String?): List<ProviderModelOption> {
        val payload = runCatching {
            completionJson.parseToJsonElement(responseBody ?: "{}") as? KxJsonObject
        }.getOrNull() ?: return emptyList()
        val data = (payload["data"] as? KxJsonArray)
            ?: (payload["models"] as? KxJsonArray)
            ?: return emptyList()

        return buildList {
            for (item in data) {
                val itemObj = item as? KxJsonObject ?: continue
                val id = itemObj["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (id.isEmpty()) continue
                val displayName = itemObj["display_name"]?.jsonPrimitive?.contentOrNull?.trim()
                    .orEmpty()
                    .ifEmpty { itemObj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty() }
                    .ifEmpty { id }
                val ownedBy = itemObj["owned_by"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: itemObj["type"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                val contextLimit = parseProviderModelInt(
                    itemObj = itemObj,
                    directKeys = listOf("contextLimit", "context_limit", "contextWindow", "context_window", "max_context_tokens"),
                    nestedValueKeys = listOf("context", "contextLimit", "context_limit", "contextWindow", "context_window")
                )
                val inputLimit = parseProviderModelInt(
                    itemObj = itemObj,
                    directKeys = listOf("inputLimit", "input_limit", "max_input_tokens"),
                    nestedValueKeys = listOf("input", "inputLimit", "input_limit", "max_input_tokens")
                )
                val outputLimit = parseProviderModelInt(
                    itemObj = itemObj,
                    directKeys = listOf("outputLimit", "output_limit", "max_output_tokens", "max_tokens"),
                    nestedValueKeys = listOf("output", "outputLimit", "output_limit", "max_output_tokens", "max_tokens")
                )
                val inputModalities = parseProviderModelStringList(
                    itemObj = itemObj,
                    directKeys = listOf("inputModalities", "input_modalities"),
                    nestedValueKeys = listOf("input", "inputs")
                )
                val outputModalities = parseProviderModelStringList(
                    itemObj = itemObj,
                    directKeys = listOf("outputModalities", "output_modalities"),
                    nestedValueKeys = listOf("output", "outputs")
                )
                add(
                    ProviderModelOption(
                        id = id,
                        displayName = displayName,
                        ownedBy = ownedBy,
                        contextLimit = contextLimit,
                        inputLimit = inputLimit,
                        outputLimit = outputLimit,
                        inputModalities = inputModalities,
                        outputModalities = outputModalities,
                        attachment = parseProviderModelBoolean(
                            itemObj = itemObj,
                            directKeys = listOf("attachment", "attachments", "vision"),
                            nestedValueKeys = listOf("attachment", "attachments", "vision", "image")
                        ),
                        reasoning = parseProviderModelBoolean(
                            itemObj = itemObj,
                            directKeys = listOf("reasoning", "thinking"),
                            nestedValueKeys = listOf("reasoning", "thinking")
                        ),
                        toolCall = parseProviderModelBoolean(
                            itemObj = itemObj,
                            directKeys = listOf("toolCall", "tool_call", "tools"),
                            nestedValueKeys = listOf("toolCall", "tool_call", "tools", "function_calling")
                        ),
                        structuredOutput = parseProviderModelBoolean(
                            itemObj = itemObj,
                            directKeys = listOf("structuredOutput", "structured_output"),
                            nestedValueKeys = listOf("structuredOutput", "structured_output", "json_schema")
                        ),
                        temperature = parseProviderModelBoolean(
                            itemObj = itemObj,
                            directKeys = listOf("temperature"),
                            nestedValueKeys = listOf("temperature")
                        )
                    )
                )
            }
        }.sortedBy { it.id.lowercase() }
    }

    private fun convertContentToAnthropicFormat(content: JsonElement?): Any? {
        return when {
            content == null -> null
            content is kotlinx.serialization.json.JsonPrimitive -> content.content
            content is kotlinx.serialization.json.JsonArray -> {
                val arr = JSONArray()
                content.forEach { block ->
                    if (block is kotlinx.serialization.json.JsonObject) {
                        arr.put(JSONObject(block.toString()))
                    }
                }
                arr
            }
            else -> content.toString()
        }
    }

    /**
     * 解析 Anthropic /v1/messages 非流式响应，转换为内部 SceneChatCompletionResponse。
     */
    fun parseAnthropicResponse(
        body: String?,
        parser: ModelSceneRegistry.ResponseParser,
        routeTag: String?
    ): SceneChatCompletionResponse {
        return try {
            val json = JSONObject(body ?: "{}")
            if (json.has("error")) {
                val errMsg = json.optJSONObject("error")?.optString("message", "Anthropic error") ?: "Anthropic error"
                return buildFailureSceneResponse(
                    code = "400",
                    message = errMsg,
                    parser = parser,
                    routeTag = routeTag,
                    rawResponseBody = body
                )
            }
            val contentArray = json.optJSONArray("content") ?: JSONArray()
            val textBuilder = StringBuilder()
            val toolCalls = mutableListOf<AssistantToolCall>()
            for (i in 0 until contentArray.length()) {
                val block = contentArray.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "text" -> textBuilder.append(block.optString("text", ""))
                    "tool_use" -> {
                        val inputObj = block.optJSONObject("input") ?: JSONObject()
                        toolCalls.add(
                            AssistantToolCall(
                                id = block.optString("id", "tool_${i}"),
                                type = "function",
                                function = AssistantToolCallFunction(
                                    name = block.optString("name", ""),
                                    arguments = inputObj.toString()
                                )
                            )
                        )
                    }
                }
            }
            val stopReason = json.optString("stop_reason", "").takeIf { it.isNotEmpty() }
            val contentText = textBuilder.toString()

            OmniLog.i(
                TAG,
                "[non-stream anthropic parse] content_len=${contentText.length}, " +
                    "tool_calls=${toolCalls.size}, stop_reason=$stopReason, " +
                    "content_preview=${contentText.take(200)}"
            )

            SceneChatCompletionResponse(
                success = true,
                code = "200",
                message = "success",
                parser = parser,
                route = routeTag,
                content = contentText,
                finishReason = stopReason,
                toolCalls = toolCalls,
                rawResponseBody = body
            )
        } catch (e: Exception) {
            buildFailureSceneResponse(
                code = "500",
                message = "Anthropic parse error: ${e.message}",
                parser = parser,
                routeTag = routeTag,
                rawResponseBody = body
            )
        }
    }

    /**
     * 包装一个 EventSourceListener，将 Anthropic SSE 事件实时翻译为 OpenAI-style chunks
     * 后转发给 outer，使上层 AgentLlmStreamAccumulator 无需修改。
     */
    fun wrapResponsesListener(outer: EventSourceListener): EventSourceListener {
        return object : EventSourceListener() {
            private val toolCalls = linkedMapOf<String, ResponseToolCallState>()
            private val toolCallAliases = linkedMapOf<String, String>()
            private val assistantText = StringBuilder()
            private var nextToolIndex = 0
            private var sawToolCall = false

            override fun onOpen(eventSource: EventSource, response: okhttp3.Response) {
                outer.onOpen(eventSource, response)
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    outer.onEvent(eventSource, id, type, "[DONE]")
                    return
                }
                val json = runCatching {
                    completionJson.parseToJsonElement(data) as? KxJsonObject
                }.getOrNull() ?: return
                val eventType = type?.trim()?.takeIf { it.isNotEmpty() }
                    ?: json.string("type").trim().takeIf { it.isNotEmpty() }
                    ?: return
                when (eventType) {
                    "response.output_text.delta" -> {
                        emitAssistantText(
                            eventSource = eventSource,
                            id = id,
                            type = type,
                            incoming = json.string("delta"),
                            isSnapshot = false
                        )
                    }
                    "response.output_text.done" -> {
                        emitAssistantText(
                            eventSource = eventSource,
                            id = id,
                            type = type,
                            incoming = json.string("text"),
                            isSnapshot = true
                        )
                    }
                    "response.content_part.added",
                    "response.content_part.done" -> {
                        val part = json.obj("part") ?: return
                        if (part.string("type") != "output_text") return
                        emitAssistantText(
                            eventSource = eventSource,
                            id = id,
                            type = type,
                            incoming = part.string("text"),
                            isSnapshot = true
                        )
                    }
                    "response.reasoning_summary_text.delta",
                    "response.reasoning.delta" -> {
                        val delta = json.string("delta")
                        if (delta.isNotEmpty()) {
                            outer.onEvent(
                                eventSource,
                                id,
                                type,
                                buildOpenAIChunk(buildSingleFieldDelta("reasoning_content", delta), null)
                            )
                        }
                    }
                    "response.output_item.added",
                    "response.output_item.done" -> {
                        val item = json.obj("item") ?: return
                        when (item.string("type")) {
                            "function_call" -> {
                                sawToolCall = true
                                val state = getOrCreateResponsesToolCallState(item)
                                val arguments = item.string("arguments")
                                val emittedArguments = updateResponsesArguments(
                                    state = state,
                                    incoming = arguments,
                                    isSnapshot = true,
                                )
                                outer.onEvent(
                                    eventSource,
                                    id,
                                    type,
                                    buildOpenAIToolCallChunk(
                                        state = state,
                                        finishReason = if (eventType.endsWith(".done")) "tool_calls" else null,
                                        argumentsDelta = emittedArguments
                                    )
                                )
                            }
                            "message" -> {
                                val content = item.array("content") ?: return
                                content.forEach { blockElement ->
                                    val block = blockElement as? KxJsonObject ?: return@forEach
                                    emitAssistantText(
                                        eventSource = eventSource,
                                        id = id,
                                        type = type,
                                        incoming = block.string("text"),
                                        isSnapshot = true
                                    )
                                }
                            }
                        }
                    }
                    "response.function_call_arguments.delta",
                    "response.function_call_arguments.done" -> {
                        sawToolCall = true
                        val state = getOrCreateResponsesToolCallState(json)
                        val isSnapshot = eventType.endsWith(".done")
                        val delta = if (isSnapshot) {
                            json.string("arguments")
                        } else {
                            json.string("delta")
                        }
                        val emittedArguments = updateResponsesArguments(
                            state = state,
                            incoming = delta,
                            isSnapshot = isSnapshot,
                        )
                        outer.onEvent(
                            eventSource,
                            id,
                            type,
                            buildOpenAIToolCallChunk(
                                state = state,
                                finishReason = if (eventType.endsWith(".done")) "tool_calls" else null,
                                argumentsDelta = emittedArguments
                            )
                        )
                    }
                    "response.completed" -> {
                        val responseObj = json.obj("response") ?: json
                        val usage = responseObj.obj("usage")?.let(::normalizeResponsesUsage)
                        outer.onEvent(
                            eventSource,
                            id,
                            type,
                            buildOpenAIChunk(
                                deltaJson = "{}",
                                finishReason = if (sawToolCall) "tool_calls" else "stop",
                                usage = usage
                            )
                        )
                        outer.onEvent(eventSource, id, type, "[DONE]")
                    }
                    else -> {
                        if (json.containsKey("error")) {
                            outer.onEvent(eventSource, id, type, data)
                        }
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                outer.onClosed(eventSource)
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: okhttp3.Response?
            ) {
                outer.onFailure(eventSource, t, response)
            }

            private fun getOrCreateResponsesToolCallState(raw: KxJsonObject): ResponseToolCallState {
                val canonicalKey = resolveResponsesToolCallKey(raw)
                val state = toolCalls.getOrPut(canonicalKey) {
                    ResponseToolCallState(
                        index = nextToolIndex++,
                        id = raw.string("call_id").ifBlank { canonicalKey },
                        name = raw.string("name")
                    )
                }
                registerResponsesToolCallAliases(raw, canonicalKey)
                raw.string("call_id").takeIf(String::isNotBlank)?.let { state.id = it }
                raw.string("name").takeIf(String::isNotBlank)?.let { state.name = it }
                return state
            }

            private fun resolveResponsesToolCallKey(raw: KxJsonObject): String {
                val candidates = responseToolCallIdentifiers(raw)
                for (candidate in candidates) {
                    if (candidate.isBlank()) continue
                    val alias = toolCallAliases[candidate]
                    if (!alias.isNullOrBlank()) {
                        return alias
                    }
                    if (toolCalls.containsKey(candidate)) {
                        return candidate
                    }
                }
                return raw.string("call_id").takeIf(String::isNotBlank)
                    ?: candidates.firstOrNull { it.isNotBlank() }
                    ?: "tool_${nextToolIndex}"
            }

            private fun registerResponsesToolCallAliases(
                raw: KxJsonObject,
                canonicalKey: String
            ) {
                responseToolCallIdentifiers(raw).forEach { candidate ->
                    if (candidate.isNotBlank()) {
                        toolCallAliases[candidate] = canonicalKey
                    }
                }
            }

            private fun responseToolCallIdentifiers(raw: KxJsonObject): List<String> {
                return listOf(
                    raw.string("item_id"),
                    raw.string("id"),
                    raw.string("call_id"),
                ).distinct()
            }

            private fun KxJsonObject.string(name: String): String {
                return (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
            }

            private fun KxJsonObject.obj(name: String): KxJsonObject? {
                return this[name] as? KxJsonObject
            }

            private fun KxJsonObject.array(name: String): KxJsonArray? {
                return this[name] as? KxJsonArray
            }

            private fun buildOpenAIToolCallChunk(
                state: ResponseToolCallState,
                finishReason: String?,
                argumentsDelta: String? = null
            ): String {
                val emittedArguments = argumentsDelta.orEmpty()
                return buildOpenAIChunk(
                    deltaJson = buildString {
                        append("{\"tool_calls\":[{")
                        append("\"index\":")
                        append(state.index)
                        append(",\"id\":")
                        append(completionJson.encodeToString(state.id))
                        append(",\"type\":\"function\"")
                        append(",\"function\":{")
                        append("\"name\":")
                        append(completionJson.encodeToString(state.name))
                        append(",\"arguments\":")
                        append(completionJson.encodeToString(emittedArguments))
                        append("}}]}")
                    },
                    finishReason = finishReason
                )
            }

            private fun updateResponsesArguments(
                state: ResponseToolCallState,
                incoming: String,
                isSnapshot: Boolean,
            ): String? {
                if (incoming.isEmpty()) {
                    return null
                }
                val current = state.arguments.toString()
                val emitted = if (isSnapshot) {
                    when {
                        current.isEmpty() -> incoming
                        incoming == current -> ""
                        incoming.startsWith(current) -> incoming.substring(current.length)
                        current.startsWith(incoming) -> ""
                        else -> ""
                    }
                } else {
                    when {
                        current.isEmpty() -> incoming
                        incoming.startsWith(current) -> incoming.substring(current.length)
                        else -> incoming
                    }
                }
                if (isSnapshot && incoming.startsWith(current)) {
                    state.arguments.setLength(0)
                    state.arguments.append(incoming)
                } else if (!isSnapshot && emitted.isNotEmpty()) {
                    state.arguments.append(emitted)
                }
                return emitted
            }

            private fun emitAssistantText(
                eventSource: EventSource,
                id: String?,
                type: String?,
                incoming: String,
                isSnapshot: Boolean
            ) {
                val emitted = updateAssistantText(incoming, isSnapshot) ?: return
                outer.onEvent(
                    eventSource,
                    id,
                    type,
                    buildOpenAIChunk(buildSingleFieldDelta("content", emitted), null)
                )
            }

            private fun updateAssistantText(
                incoming: String,
                isSnapshot: Boolean
            ): String? {
                if (incoming.isEmpty()) {
                    return null
                }
                if (!isSnapshot) {
                    assistantText.append(incoming)
                    return incoming
                }
                val current = assistantText.toString()
                val emitted = when {
                    current.isEmpty() -> incoming
                    incoming == current -> ""
                    incoming.startsWith(current) -> incoming.substring(current.length)
                    current.startsWith(incoming) -> ""
                    else -> incoming
                }
                if (incoming != current) {
                    assistantText.setLength(0)
                    assistantText.append(incoming)
                }
                return emitted.ifEmpty { null }
            }

            private fun buildSingleFieldDelta(field: String, value: String): String {
                return "{\"$field\":${completionJson.encodeToString(value)}}"
            }

            private fun buildOpenAIChunk(
                deltaJson: String,
                finishReason: String?,
                usage: KxJsonObject? = null
            ): String {
                val finishReasonPart = finishReason?.let {
                    ",\"finish_reason\":${completionJson.encodeToString(it)}"
                }.orEmpty()
                val usagePart = usage?.let {
                    ",\"usage\":${it.toString()}"
                }.orEmpty()
                return buildString {
                    append("{\"choices\":[{\"delta\":")
                    append(deltaJson.toString())
                    append(finishReasonPart)
                    append("}]")
                    append(usagePart)
                    append("}")
                }
            }

            private fun normalizeResponsesUsage(usage: KxJsonObject): KxJsonObject {
                val normalized = usage.toMutableMap()
                if (!normalized.containsKey("prompt_tokens")) {
                    usage["input_tokens"]?.let { normalized["prompt_tokens"] = it }
                }
                if (!normalized.containsKey("completion_tokens")) {
                    usage["output_tokens"]?.let { normalized["completion_tokens"] = it }
                }
                if (!normalized.containsKey("prompt_tokens_details")) {
                    usage["input_tokens_details"]?.let {
                        normalized["prompt_tokens_details"] = it
                    }
                }
                if (!normalized.containsKey("completion_tokens_details")) {
                    usage["output_tokens_details"]?.let {
                        normalized["completion_tokens_details"] = it
                    }
                }
                return KxJsonObject(normalized)
            }
        }
    }

    fun wrapAnthropicListener(outer: EventSourceListener): EventSourceListener {
        return object : EventSourceListener() {
            // per-stream state
            private val toolUseBlocks = mutableMapOf<Int, KxJsonObject>() // index → {id, name}
            private val toolArgBuffers = mutableMapOf<Int, StringBuilder>() // index → partial json
            private val usage = AnthropicUsageAccumulator()

            override fun onOpen(eventSource: EventSource, response: okhttp3.Response) {
                outer.onOpen(eventSource, response)
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    outer.onEvent(eventSource, id, type, "[DONE]")
                    return
                }
                val json = runCatching {
                    completionJson.parseToJsonElement(data) as? KxJsonObject
                }.getOrNull() ?: return
                val eventType = type?.trim()?.takeIf { it.isNotEmpty() }
                    ?: stringField(json, "type").takeIf { it.isNotEmpty() }
                if (eventType == null) {
                    when {
                        json.containsKey("choices") -> {
                            // Some providers may return OpenAI-style chunks on Anthropic-compatible route.
                            outer.onEvent(eventSource, id, type, data)
                        }
                        json.containsKey("text") -> {
                            val text = stringField(json, "text")
                            if (text.isNotEmpty()) {
                                outer.onEvent(
                                    eventSource,
                                    id,
                                    type,
                                    buildOpenAIChunk(
                                        deltaJson = buildJsonObject {
                                            put("content", JsonPrimitive(text))
                                        },
                                        finishReason = null
                                    )
                                )
                            }
                        }
                    }
                    return
                }
                when (eventType) {
                    "message_start" -> {
                        usage.merge(
                            objectField(json, "message")?.let { objectField(it, "usage") }
                        )
                        usage.toOpenAIUsage()?.let { normalizedUsage ->
                            outer.onEvent(
                                eventSource,
                                id,
                                type,
                                buildOpenAIChunk(
                                    deltaJson = KxJsonObject(emptyMap()),
                                    finishReason = null,
                                    usageJson = normalizedUsage
                                )
                            )
                        }
                    }
                    "content_block_start" -> {
                        val index = intField(json, "index") ?: 0
                        val block = objectField(json, "content_block") ?: return
                        when (stringField(block, "type")) {
                            "tool_use" -> {
                                val toolId = stringField(block, "id").ifEmpty { "tool_$index" }
                                val toolName = stringField(block, "name")
                                toolUseBlocks[index] = buildJsonObject {
                                    put("id", JsonPrimitive(toolId))
                                    put("name", JsonPrimitive(toolName))
                                }
                                toolArgBuffers[index] = StringBuilder()
                                // emit tool_call header chunk
                                val chunk = buildOpenAIChunk(
                                    deltaJson = buildJsonObject {
                                        put(
                                            "tool_calls",
                                            buildJsonArray {
                                                add(
                                                    buildJsonObject {
                                                        put("index", JsonPrimitive(index))
                                                        put("id", JsonPrimitive(toolId))
                                                        put("type", JsonPrimitive("function"))
                                                        put(
                                                            "function",
                                                            buildJsonObject {
                                                                put("name", JsonPrimitive(toolName))
                                                                put("arguments", JsonPrimitive(""))
                                                            }
                                                        )
                                                    }
                                                )
                                            }
                                        )
                                    },
                                    finishReason = null
                                )
                                outer.onEvent(eventSource, id, type, chunk)
                            }
                            "text" -> {
                                val text = stringField(block, "text")
                                if (text.isNotEmpty()) {
                                    val chunk = buildOpenAIChunk(
                                        deltaJson = buildJsonObject {
                                            put("content", JsonPrimitive(text))
                                        },
                                        finishReason = null
                                    )
                                    outer.onEvent(eventSource, id, type, chunk)
                                }
                            }
                        }
                    }
                    "content_block_delta" -> {
                        val index = intField(json, "index") ?: 0
                        val delta = objectField(json, "delta") ?: return
                        when (stringField(delta, "type")) {
                            "text_delta" -> {
                                val text = stringField(delta, "text")
                                val chunk = buildOpenAIChunk(
                                    deltaJson = buildJsonObject {
                                        put("content", JsonPrimitive(text))
                                    },
                                    finishReason = null
                                )
                                outer.onEvent(eventSource, id, type, chunk)
                            }
                            "input_json_delta" -> {
                                val partialJson = stringField(delta, "partial_json")
                                toolArgBuffers[index]?.append(partialJson)
                                val chunk = buildOpenAIChunk(
                                    deltaJson = buildJsonObject {
                                        put(
                                            "tool_calls",
                                            buildJsonArray {
                                                add(
                                                    buildJsonObject {
                                                        put("index", JsonPrimitive(index))
                                                        put(
                                                            "function",
                                                            buildJsonObject {
                                                                put("arguments", JsonPrimitive(partialJson))
                                                            }
                                                        )
                                                    }
                                                )
                                            }
                                        )
                                    },
                                    finishReason = null
                                )
                                outer.onEvent(eventSource, id, type, chunk)
                            }
                            "thinking_delta" -> {
                                val thinking = stringField(delta, "thinking")
                                if (thinking.isNotEmpty()) {
                                    val chunk = buildOpenAIChunk(
                                        deltaJson = buildJsonObject {
                                            put("reasoning_content", JsonPrimitive(thinking))
                                        },
                                        finishReason = null
                                    )
                                    outer.onEvent(eventSource, id, type, chunk)
                                }
                            }
                        }
                    }
                    "message_delta" -> {
                        usage.merge(objectField(json, "usage"))
                        val delta = objectField(json, "delta")
                        val stopReason = delta?.let { stringField(it, "stop_reason") }
                            ?.takeIf { it.isNotEmpty() }
                        val normalizedUsage = usage.toOpenAIUsage()
                        if (stopReason != null || normalizedUsage != null) {
                            val finishReason = if (stopReason == "tool_use") "tool_calls" else stopReason
                            val chunk = buildOpenAIChunk(
                                deltaJson = KxJsonObject(emptyMap()),
                                finishReason = finishReason,
                                usageJson = normalizedUsage
                            )
                            outer.onEvent(eventSource, id, type, chunk)
                        }
                    }
                    "message_stop" -> {
                        outer.onEvent(eventSource, id, type, "[DONE]")
                    }
                    "error" -> {
                        val errMsg = objectField(json, "error")
                            ?.let { stringField(it, "message") }
                            ?.takeIf { it.isNotEmpty() }
                            ?: "stream error"
                        outer.onFailure(eventSource, RuntimeException("Anthropic stream error: $errMsg"), null)
                    }
                    "completion" -> {
                        val completion = stringField(json, "completion")
                        if (completion.isNotEmpty()) {
                            val chunk = buildOpenAIChunk(
                                deltaJson = buildJsonObject {
                                    put("content", JsonPrimitive(completion))
                                },
                                finishReason = null
                            )
                            outer.onEvent(eventSource, id, type, chunk)
                        }
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                outer.onClosed(eventSource)
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: okhttp3.Response?
            ) {
                outer.onFailure(eventSource, t, response)
            }

            private fun buildOpenAIChunk(
                deltaJson: KxJsonObject,
                finishReason: String?,
                usageJson: KxJsonObject? = null
            ): String {
                return buildJsonObject {
                    put(
                        "choices",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("delta", deltaJson)
                                    finishReason?.let {
                                        put("finish_reason", JsonPrimitive(it))
                                    }
                                }
                            )
                        }
                    )
                    usageJson?.let { put("usage", it) }
                }.toString()
            }

            private fun objectField(source: KxJsonObject, name: String): KxJsonObject? {
                return source[name] as? KxJsonObject
            }

            private fun stringField(source: KxJsonObject, name: String): String {
                return (source[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
            }

            private fun intField(source: KxJsonObject, name: String): Int? {
                return (source[name] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.toIntOrNull()
            }
        }
    }

    private suspend fun postAnthropicStreamRequest(
        resolved: ResolvedSceneRequest,
        requestJson: String,
        event: EventSourceListener,
        forceHttp1: Boolean = false,
        conversationId: Long = 0L
    ): EventSource = withContext(Dispatchers.IO) {
        val base = normalizeApiBase(resolved.apiBase ?: "")
            ?: throw IllegalArgumentException("Invalid apiBase for Anthropic")
        val url = buildAnthropicMessagesUrl(base)
        val requestBody = requestJson.toRequestBody("application/json".toMediaType())
        val request = buildAnthropicRequestBuilder(
            url = url,
            requestBody = requestBody,
            apiKey = resolved.apiKey,
            hasCacheControl = hasCacheControl(requestJson),
            customHeaders = resolved.customHeaders
        )
            .addHeader("Accept", "text/event-stream")
            .build()
        logRequestHeaders("[anthropic stream model=${resolved.resolvedModel}]", request.headers.toMultimap().mapValues {
            it.value.joinToString(",")
        })
        EventSources.createFactory(openAIStreamClient(forceHttp1)).newEventSource(
            request,
            createLoggingEventListener(
                "[anthropic stream model=${resolved.resolvedModel}]",
                wrapAnthropicListener(event),
                requestLogSeed = AiRequestLogSeed(
                    label = "anthropic/messages",
                    model = resolved.resolvedModel,
                    protocolType = "anthropic",
                    url = url,
                    stream = true,
                    requestJson = requestJson,
                    conversationId = conversationId
                )
            )
        )
    }

    // ---- end Anthropic protocol helpers ----

    private fun openAIStreamClient(forceHttp1: Boolean = false): OkHttpClient {
        return OkHttpClient.Builder()
            .apply {
                if (forceHttp1) protocols(listOf(Protocol.HTTP_1_1))
            }
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun createChatRequestFromText(
        resolved: ResolvedSceneRequest,
        text: String,
        reasoningEffort: String? = null
    ): ChatCompletionRequest {
        val disableThinking = reasoningEffort == "no"
        return ChatCompletionRequest(
            model = resolved.resolvedModel,
            messages = listOf(
                ChatCompletionMessage(
                    role = "user",
                    content = JsonPrimitive(text)
                )
            ),
            enableThinking = if (disableThinking) false else null,
            reasoningEffort = if (disableThinking) null else reasoningEffort
        )
    }

    private fun createChatRequestFromMessages(
        resolved: ResolvedSceneRequest,
        messages: List<Map<String, Any>>,
        enableThinking: Boolean? = null,
        reasoningEffort: String? = null,
        promptCacheKey: String? = null
    ): ChatCompletionRequest {
        val disableThinking = reasoningEffort == "no"
        val chatMessages = messages.map { message ->
            ChatCompletionMessage(
                role = message["role"]?.toString().orEmpty().ifBlank { "user" },
                content = parseChatMessageContent(message["content"]),
                toolCalls = parseAssistantToolCalls(message["tool_calls"] ?: message["toolCalls"]),
                reasoningContent = parseOptionalText(
                    message["reasoning_content"] ?: message["reasoningContent"]
                ),
                toolCallId = parseOptionalText(message["tool_call_id"] ?: message["toolCallId"]),
                name = parseOptionalText(message["name"])
            )
        }
        return ChatCompletionRequest(
            model = resolved.resolvedModel,
            messages = chatMessages,
            enableThinking = if (disableThinking) false else enableThinking,
            reasoningEffort = if (disableThinking) null else reasoningEffort,
            promptCacheKey = promptCacheKey?.trim()?.takeIf { it.isNotEmpty() },
            streamOptions = ChatCompletionStreamOptions(includeUsage = true),
        )
    }

    private fun parseChatMessageContent(raw: Any?): JsonElement? {
        return when (raw) {
            null -> null
            is String -> JsonPrimitive(raw)
            is List<*> -> {
                val blocks = parseContentBlocks(raw)
                if (blocks.isNotEmpty()) {
                    blocks
                } else {
                    JsonPrimitive(raw.joinToString("\n") { item ->
                        when (item) {
                            is Map<*, *> -> {
                                val text = item["text"]?.toString().orEmpty()
                                if (text.isNotBlank()) text else item.toString()
                            }
                            else -> item?.toString().orEmpty()
                        }
                    }.trim())
                }
            }
            else -> JsonPrimitive(raw.toString())
        }
    }

    private fun parseAssistantToolCalls(raw: Any?): List<AssistantToolCall>? {
        if (raw !is List<*>) return null
        var fallbackIndex = 0
        val parsed = raw.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val function = map["function"] as? Map<*, *> ?: return@mapNotNull null
            val name = parseOptionalText(function["name"]) ?: return@mapNotNull null
            val arguments = when (val rawArguments = function["arguments"]) {
                null -> "{}"
                is String -> rawArguments
                else -> JSONObject.wrap(rawArguments)?.toString() ?: rawArguments.toString()
            }
            AssistantToolCall(
                id = parseOptionalText(map["id"]).orEmpty().ifBlank {
                    "tool_call_${fallbackIndex++}"
                },
                type = parseOptionalText(map["type"]).orEmpty().ifBlank { "function" },
                function = AssistantToolCallFunction(
                    name = name,
                    arguments = arguments
                )
            )
        }
        return parsed.ifEmpty { null }
    }

    private fun parseOptionalText(raw: Any?): String? {
        val normalized = raw?.toString()?.trim().orEmpty()
        return normalized.takeIf { it.isNotEmpty() }
    }

    private fun parseContentBlocks(raw: List<*>): KxJsonArray {
        val blocks = mutableListOf<KxJsonObject>()
        raw.forEach { item ->
            when (item) {
                is Map<*, *> -> {
                    val typeRaw = item["type"]?.toString()?.trim()?.lowercase().orEmpty()
                    val type = when {
                        typeRaw.isNotEmpty() -> typeRaw
                        item.containsKey("image_url") || item.containsKey("url") -> "image_url"
                        item.containsKey("text") -> "text"
                        else -> ""
                    }
                    when (type) {
                        "text", "input_text" -> {
                            val text = item["text"]?.toString().orEmpty()
                            if (text.isNotBlank()) {
                                blocks.add(
                                    buildJsonObject {
                                        put("type", JsonPrimitive("text"))
                                        put("text", JsonPrimitive(text))
                                        parseAnyToJsonElement(item["cache_control"])?.let {
                                            put("cache_control", it)
                                        }
                                    }
                                )
                            }
                        }
                        "image_url", "input_image", "image" -> {
                            val imageUrl = parseImageUrlFromAny(
                                item["image_url"] ?: item["url"] ?: item["imageUrl"]
                            )
                            if (imageUrl.isNotBlank()) {
                                blocks.add(buildImageContent(imageUrl))
                            }
                        }
                    }
                }
            }
        }
        return KxJsonArray(blocks)
    }

    private fun parseAnyToJsonElement(raw: Any?): JsonElement? {
        return when (raw) {
            null -> null
            is JsonElement -> raw
            is String -> JsonPrimitive(raw)
            is Number -> JsonPrimitive(raw)
            is Boolean -> JsonPrimitive(raw)
            is Map<*, *> -> buildJsonObject {
                raw.forEach { (key, value) ->
                    val normalizedKey = key?.toString()?.trim().orEmpty()
                    if (normalizedKey.isNotEmpty()) {
                        parseAnyToJsonElement(value)?.let { put(normalizedKey, it) }
                    }
                }
            }
            is List<*> -> buildJsonArray {
                raw.forEach { item ->
                    parseAnyToJsonElement(item)?.let { add(it) }
                }
            }
            else -> JsonPrimitive(raw.toString())
        }
    }

    private fun parseImageUrlFromAny(raw: Any?): String {
        return when (raw) {
            is String -> raw.trim()
            is Map<*, *> -> {
                val url = raw["url"]?.toString()?.trim().orEmpty()
                if (url.isNotBlank()) {
                    url
                } else {
                    raw["data"]?.toString()?.trim().orEmpty()
                }
            }
            else -> ""
        }
    }

    private fun buildImageContent(rawImage: String): KxJsonObject {
        val imageUrl = if (
            rawImage.startsWith("http://", ignoreCase = true) ||
            rawImage.startsWith("https://", ignoreCase = true) ||
            rawImage.startsWith("data:", ignoreCase = true)
        ) {
            rawImage
        } else {
            "data:image/png;base64,$rawImage"
        }
        return buildJsonObject {
            put("type", JsonPrimitive("image_url"))
            put(
                "image_url",
                buildJsonObject {
                    put("url", JsonPrimitive(imageUrl))
                }
            )
        }
    }

    private fun buildRequestBodyWithResolvedModel(
        requestBodyJson: String,
        resolvedModel: String,
        includeLegacyMirrors: Boolean,
        mirrorLegacyTokenFields: Boolean = true
    ): String {
        return JSONObject(requestBodyJson).apply {
            if (resolvedModel.isNotEmpty()) {
                put("model", resolvedModel)
            }
            val toolsArray = optJSONArray("tools")
            if (toolsArray != null && toolsArray.length() == 0) {
                remove("tools")
            }
            val hasMaxCompletionTokens = has("max_completion_tokens")
            val hasMaxTokens = has("max_tokens")
            if (mirrorLegacyTokenFields && hasMaxCompletionTokens && !hasMaxTokens) {
                put("max_tokens", opt("max_completion_tokens"))
            } else if (!hasMaxCompletionTokens && hasMaxTokens) {
                put("max_completion_tokens", opt("max_tokens"))
            } else if (!mirrorLegacyTokenFields && hasMaxTokens) {
                remove("max_tokens")
            }
            if (includeLegacyMirrors && has("tools") && !has("functions")) {
                val currentToolsArray = optJSONArray("tools")
                if (currentToolsArray != null && currentToolsArray.length() > 0) {
                    val functionsArray = JSONArray()
                    for (i in 0 until currentToolsArray.length()) {
                        val toolObj = currentToolsArray.optJSONObject(i) ?: continue
                        val functionObj = toolObj.optJSONObject("function") ?: continue
                        val legacyFunction = JSONObject()
                        if (functionObj.has("name")) {
                            legacyFunction.put("name", functionObj.opt("name"))
                        }
                        if (functionObj.has("description")) {
                            legacyFunction.put("description", functionObj.opt("description"))
                        }
                        if (functionObj.has("parameters")) {
                            legacyFunction.put("parameters", functionObj.opt("parameters"))
                        }
                        if (legacyFunction.length() > 0) {
                            functionsArray.put(legacyFunction)
                        }
                    }
                    if (functionsArray.length() > 0) {
                        put("functions", functionsArray)
                        if (!has("function_call")) {
                            when (val toolChoice = opt("tool_choice")) {
                                is String -> {
                                    put(
                                        "function_call",
                                        if (toolChoice.equals("required", ignoreCase = true)) {
                                            "auto"
                                        } else {
                                            toolChoice
                                        }
                                    )
                                }
                                is JSONObject -> {
                                    val functionName = toolChoice.optJSONObject("function")
                                        ?.optString("name")
                                        ?.takeIf { it.isNotBlank() }
                                    if (functionName != null) {
                                        put("function_call", JSONObject().put("name", functionName))
                                    } else {
                                        put("function_call", "auto")
                                    }
                                }
                                else -> put("function_call", "auto")
                            }
                        }
                    }
                }
            }
        }.toString()
    }

    private fun buildOpenAICompatibleRequestBody(
        requestBodyJson: String,
        resolvedModel: String,
        includeLegacyMirrors: Boolean,
        mirrorLegacyTokenFields: Boolean = true,
        protocolType: String,
        apiBase: String?
    ): String {
        val baseBody = buildRequestBodyWithResolvedModel(
            requestBodyJson = requestBodyJson,
            resolvedModel = resolvedModel,
            includeLegacyMirrors = includeLegacyMirrors,
            mirrorLegacyTokenFields = mirrorLegacyTokenFields
        )
        val protocolReadyBody = if (DeepSeekProvider.shouldUseOfficialAdapter(protocolType, apiBase)) {
            applyOfficialDeepSeekThinkingMode(baseBody)
        } else {
            baseBody
        }
        return stripAnthropicOnlyFieldsForOpenAiCompatible(protocolReadyBody)
    }

    private fun buildOpenAIResponsesRequestBody(
        requestBodyJson: String,
        resolvedModel: String
    ): String {
        val parsedRequest = completionJson.decodeFromString<ChatCompletionRequest>(requestBodyJson)
            .copy(model = resolvedModel)
        val systemInstructions = parsedRequest.messages
            .filter { it.role == "system" }
            .mapNotNull { it.contentText().trim().takeIf { text -> text.isNotEmpty() } }
            .joinToString(separator = "\n\n")
            .trim()
            .ifEmpty { null }
        val payload = OpenAIResponsesRequest(
            model = parsedRequest.model,
            input = buildResponsesInputItems(parsedRequest.messages.filter { it.role != "system" }),
            instructions = systemInstructions,
            maxOutputTokens = parsedRequest.maxCompletionTokens ?: parsedRequest.maxTokens,
            stream = parsedRequest.stream,
            tools = buildResponsesTools(parsedRequest),
            toolChoice = buildResponsesToolChoice(parsedRequest.toolChoice),
            parallelToolCalls = parsedRequest.parallelToolCalls,
            reasoning = buildResponsesReasoning(parsedRequest),
            promptCacheKey = parsedRequest.promptCacheKey
        )
        return stripAnthropicOnlyFieldsForOpenAiCompatible(
            completionJson.encodeToString(payload)
        )
    }

    private fun buildResponsesInputItems(messages: List<ChatCompletionMessage>): List<JsonElement> {
        val items = mutableListOf<JsonElement>()
        messages.forEach { message ->
            when (message.role) {
                "tool" -> {
                    items += buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", message.toolCallId.orEmpty())
                        put("output", message.contentText())
                    }
                }
                "assistant" -> {
                    val visibleText = buildList {
                        message.reasoningContent?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
                        message.contentText().trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                    }.joinToString(separator = "\n\n").trim()
                    if (visibleText.isNotEmpty()) {
                        items += buildResponsesMessageItem("assistant", visibleText)
                    }
                    message.toolCalls.orEmpty().forEach { toolCall ->
                        items += buildJsonObject {
                            put("type", "function_call")
                            put("call_id", toolCall.id)
                            put("name", toolCall.function.name)
                            put("arguments", toolCall.function.arguments)
                        }
                    }
                }
                else -> {
                    val text = message.contentText()
                    if (text.isNotBlank()) {
                        items += buildResponsesMessageItem(message.role.ifBlank { "user" }, text)
                    }
                }
            }
        }
        return items
    }

    private fun buildResponsesMessageItem(role: String, text: String): JsonElement {
        val contentType = if (role == "assistant") "output_text" else "input_text"
        return buildJsonObject {
            put("role", role)
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", contentType)
                            put("text", text)
                        }
                    )
                }
            )
        }
    }

    private fun buildResponsesTools(request: ChatCompletionRequest): List<JsonElement> {
        return request.tools.map { tool ->
            buildJsonObject {
                put("type", "function")
                put("name", tool.function.name)
                if (tool.function.description.isNotBlank()) {
                    put("description", tool.function.description)
                }
                put("parameters", tool.function.parameters)
            }
        }
    }

    private fun buildResponsesToolChoice(toolChoice: JsonElement?): JsonElement? {
        return when (toolChoice) {
            null -> null
            is JsonPrimitive -> {
                val normalized = toolChoice.contentOrNull?.trim().orEmpty()
                normalized.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive)
            }
            is KxJsonObject -> {
                val functionName = toolChoice["function"]?.let { raw ->
                    (raw as? KxJsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                }?.trim().orEmpty()
                if (functionName.isEmpty()) {
                    JsonPrimitive("auto")
                } else {
                    buildJsonObject {
                        put("type", "function")
                        put("name", functionName)
                    }
                }
            }
            else -> null
        }
    }

    private fun buildResponsesReasoning(request: ChatCompletionRequest): JsonElement? {
        if (request.enableThinking == false || request.thinking?.type == "disabled") {
            return buildJsonObject { put("effort", "none") }
        }
        val normalizedEffort = request.reasoningEffort?.trim()?.lowercase()
        val effort = when (normalizedEffort) {
            "none", "low", "medium", "high" -> normalizedEffort
            "xhigh", "max" -> "high"
            else -> null
        } ?: return null
        return buildJsonObject { put("effort", effort) }
    }

    private fun applyOfficialDeepSeekThinkingMode(requestBodyJson: String): String {
        val payload = runCatching {
            completionJson.parseToJsonElement(requestBodyJson) as? KxJsonObject
        }.getOrNull() ?: return requestBodyJson
        val explicitThinkingType = (payload["thinking"] as? KxJsonObject)
            ?.get("type")
            .let { it as? JsonPrimitive }
            ?.contentOrNull
            ?.trim()
            ?.lowercase()
            ?.takeIf { it == "enabled" || it == "disabled" }
        val enableThinking = when (val raw = payload["enable_thinking"]) {
            is JsonPrimitive -> raw.booleanOrNull
                ?: raw.contentOrNull?.trim()?.toBooleanStrictOrNull()
            else -> null
        }
        val rawReasoningEffort = payload["reasoning_effort"]
            .let { it as? JsonPrimitive }
            ?.contentOrNull
            .orEmpty()
            .trim()
            .lowercase()
            .takeIf { it.isNotEmpty() }
        val thinkingType = explicitThinkingType
            ?: when {
                enableThinking == false || rawReasoningEffort == "no" -> "disabled"
                else -> "enabled"
            }

        val updated = payload.toMutableMap()
        updated.remove("enable_thinking")
        payload["max_completion_tokens"]?.let { maxCompletionTokens ->
            if (!updated.containsKey("max_tokens")) {
                updated["max_tokens"] = maxCompletionTokens
            }
            updated.remove("max_completion_tokens")
        }
        updated["thinking"] = buildJsonObject {
            put("type", JsonPrimitive(thinkingType))
        }

        if (thinkingType == "enabled") {
            DeepSeekProvider.mapReasoningEffortForOfficialApi(rawReasoningEffort)?.let {
                updated["reasoning_effort"] = JsonPrimitive(it)
            } ?: updated.remove("reasoning_effort")
            updated.remove("temperature")
            updated.remove("top_p")
        } else {
            updated.remove("reasoning_effort")
        }
        // prompt_cache_key is an OpenAI extension and DeepSeek's official API
        // rejects unsupported top-level request fields.
        updated.remove("prompt_cache_key")
        return KxJsonObject(updated).toString()
    }

    private fun stripAnthropicOnlyFieldsForOpenAiCompatible(requestBodyJson: String): String {
        val payload = runCatching {
            completionJson.parseToJsonElement(requestBodyJson)
        }.getOrNull() ?: return requestBodyJson
        return stripAnthropicOnlyFields(payload).toString()
    }

    private fun stripAnthropicOnlyFields(payload: JsonElement): JsonElement {
        return when (payload) {
            is KxJsonObject -> KxJsonObject(
                payload
                    .filterKeys { it != "cache_control" }
                    .mapValues { (_, value) -> stripAnthropicOnlyFields(value) }
            )
            is KxJsonArray -> KxJsonArray(payload.map(::stripAnthropicOnlyFields))
            else -> payload
        }
    }

    private suspend fun postOpenAIStreamRequestAsFlow(
        chatRequest: ChatCompletionRequest,
        apiBase: String?,
        apiKey: String?,
        customHeaders: Map<String, String> = emptyMap(),
        event: EventSourceListener,
        routeTag: String? = null,
        protocolType: String = "openai_compatible",
        wireApi: String = OpenAiWireApi.CHAT_COMPLETIONS
    ): EventSource = withContext(Dispatchers.IO) {
        if (protocolType == "anthropic") {
            val resolved = ResolvedSceneRequest(
                requestedModel = chatRequest.model,
                resolvedModel = chatRequest.model,
                sceneProfile = null,
                effectiveTransport = ModelSceneRegistry.SceneTransport.OPENAI_COMPATIBLE,
                responseParser = ModelSceneRegistry.ResponseParser.TEXT_CONTENT,
                apiBase = apiBase,
                apiKey = apiKey,
                customHeaders = ProviderCustomHeaderUtils.sanitizeCustomHeaders(customHeaders),
                providerProfileId = null,
                providerProfileName = null,
                routeTag = routeTag,
                customApiBaseApplied = !apiBase.isNullOrBlank(),
                bindingApplied = false,
                bindingProfileMissing = false,
                overrideApplied = false,
                overrideModel = null,
                protocolType = "anthropic"
            )
            val anthropicJson = convertToAnthropicRequestJson(chatRequest.copy(stream = true))
            return@withContext postAnthropicStreamRequest(
                resolved,
                anthropicJson,
                event,
                conversationId = conversationIdFromPromptCacheKey(chatRequest.promptCacheKey)
            )
        }
        val base = normalizeApiBase(apiBase ?: "")
            ?: throw IllegalArgumentException("Invalid apiBase")
        val normalizedWireApi = OpenAiWireApi.normalize(wireApi)
        val requestJson = if (OpenAiWireApi.isResponses(normalizedWireApi)) {
            buildOpenAIResponsesRequestBody(
                requestBodyJson = completionJson.encodeToString(chatRequest.copy(stream = true)),
                resolvedModel = chatRequest.model
            )
        } else {
            buildOpenAICompatibleRequestBody(
                requestBodyJson = completionJson.encodeToString(chatRequest.copy(stream = true)),
                resolvedModel = chatRequest.model,
                includeLegacyMirrors = false,
                protocolType = protocolType,
                apiBase = base
            )
        }
        val requestBody = requestJson.toRequestBody("application/json".toMediaType())
        val url = buildOpenAIInferenceUrl(base, normalizedWireApi)
        val request = buildOpenAIRequestBuilder(
            url = url,
            requestBody = requestBody,
            apiKey = apiKey,
            customHeaders = customHeaders
        )
            .addHeader("Accept", "text/event-stream")
            .build()
        logRequestHeaders("[openai stream model=${chatRequest.model}]", request.headers.toMultimap().mapValues {
            it.value.joinToString(",")
        })
        val delegate = if (OpenAiWireApi.isResponses(normalizedWireApi)) {
            wrapResponsesListener(event)
        } else {
            event
        }
        EventSources.createFactory(openAIStreamClient()).newEventSource(
            request,
            createLoggingEventListener(
                "[openai_compatible stream model=${chatRequest.model} route=${routeTag.orEmpty()}]",
                delegate,
                requestLogSeed = AiRequestLogSeed(
                    label = if (OpenAiWireApi.isResponses(normalizedWireApi)) {
                        "openai/responses.stream"
                    } else {
                        "openai/chat.completions.stream"
                    },
                    model = chatRequest.model,
                    protocolType = protocolType,
                    url = url,
                    stream = true,
                    requestJson = requestJson,
                    conversationId = conversationIdFromPromptCacheKey(chatRequest.promptCacheKey)
                )
            )
        )
    }

    private suspend fun postOpenAIChatCompletionsStreamRequest(
        resolved: ResolvedSceneRequest,
        requestBodyJson: String,
        event: EventSourceListener,
        forceHttp1: Boolean = false
    ): EventSource = withContext(Dispatchers.IO) {
        if (resolved.protocolType == "anthropic") {
            // Parse the incoming OpenAI JSON back into a request and convert to Anthropic format
            val parsedRequest = runCatching {
                val json = completionJson.decodeFromString<ChatCompletionRequest>(requestBodyJson)
                json.copy(model = resolved.resolvedModel, stream = true)
            }.getOrElse {
                return@withContext buildDummyFailureEventSource(event, "Failed to parse request for Anthropic conversion")
            }
            val anthropicJson = convertToAnthropicRequestJson(parsedRequest)
            return@withContext postAnthropicStreamRequest(
                resolved,
                anthropicJson,
                event,
                forceHttp1,
                conversationId = conversationIdFromRequestJson(requestBodyJson)
            )
        }
        val base = normalizeApiBase(resolved.apiBase ?: "")
            ?: throw IllegalArgumentException("Invalid apiBase")
        val normalizedWireApi = OpenAiWireApi.normalize(resolved.wireApi)
        val preparedRequestJson = if (OpenAiWireApi.isResponses(normalizedWireApi)) {
            buildOpenAIResponsesRequestBody(
                requestBodyJson = requestBodyJson,
                resolvedModel = resolved.resolvedModel
            )
        } else {
            buildOpenAICompatibleRequestBody(
                requestBodyJson = requestBodyJson,
                resolvedModel = resolved.resolvedModel,
                includeLegacyMirrors = false,
                mirrorLegacyTokenFields = false,
                protocolType = resolved.protocolType,
                apiBase = base
            )
        }
        val requestBody = preparedRequestJson.toRequestBody("application/json".toMediaType())
        val url = buildOpenAIInferenceUrl(base, normalizedWireApi)
        val request = buildOpenAIRequestBuilder(
            url = url,
            requestBody = requestBody,
            apiKey = resolved.apiKey,
            customHeaders = resolved.customHeaders
        )
            .addHeader("Accept", "text/event-stream")
            .build()
        val wireApiLabel = if (OpenAiWireApi.isResponses(normalizedWireApi)) {
            "responses"
        } else {
            "chat-completions"
        }
        logRequestHeaders("[openai $wireApiLabel model=${resolved.resolvedModel}]", request.headers.toMultimap().mapValues {
            it.value.joinToString(",")
        })
        val delegate = if (OpenAiWireApi.isResponses(normalizedWireApi)) {
            wrapResponsesListener(event)
        } else {
            event
        }
        EventSources.createFactory(openAIStreamClient(forceHttp1)).newEventSource(
            request,
            createLoggingEventListener(
                "[openai_compatible $wireApiLabel model=${resolved.resolvedModel}]",
                delegate,
                requestLogSeed = AiRequestLogSeed(
                    label = if (OpenAiWireApi.isResponses(normalizedWireApi)) {
                        "openai/responses.stream"
                    } else {
                        "openai/chat.completions.stream"
                    },
                    model = resolved.resolvedModel,
                    protocolType = resolved.protocolType,
                    url = url,
                    stream = true,
                    requestJson = preparedRequestJson,
                    conversationId = conversationIdFromRequestJson(requestBodyJson)
                )
            )
        )
    }

    private fun buildDummyFailureEventSource(event: EventSourceListener, message: String): EventSource {
        val dummySource = object : EventSource {
            override fun request(): Request = Request.Builder().url("https://localhost").build()
            override fun cancel() {}
        }
        event.onFailure(dummySource, RuntimeException(message), null)
        return dummySource
    }

    private fun sanitizeShortMessage(raw: String?, maxLen: Int = 200): String {
        val normalized = raw?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return "请求失败"
        }
        return if (normalized.length <= maxLen) normalized else "${normalized.take(maxLen)}..."
    }

    private fun extractAvailabilityMessage(responseBody: String?): String {
        if (responseBody.isNullOrBlank()) return "请求失败"
        return try {
            val json = JSONObject(responseBody)
            val errorObj = json.optJSONObject("error")
            val errorMsg = errorObj?.optString("message", "")?.takeIf { it.isNotBlank() }
            val topMsg = json.optString("message", "").takeIf { it.isNotBlank() }
            sanitizeShortMessage(errorMsg ?: topMsg ?: responseBody)
        } catch (_: Exception) {
            sanitizeShortMessage(responseBody)
        }
    }



    /**
     * 发送 LLM 请求并处理流式响应 (SSE格式)
     *
     * @param text 请求文本
     * @param onStreamData 接收流式数据的回调函数，参数为解析后的文本内容
     */
    /**
     * 发送 LLM 请求并处理流式响应 (SSE格式)，返回Flow
     *
     * @param text 请求文本
     * @return Flow 流，发射解析后的文本内容
     */
    suspend fun postLLMStreamRequestAsFlow(
        model: String, text: String, event: EventSourceListener
    ): EventSource {
        val resolved = resolveSceneRequest(model)
        logSceneProfile(resolved)
        return postOpenAIStreamRequestAsFlow(
            chatRequest = createChatRequestFromText(resolved, text),
            apiBase = resolved.apiBase,
            apiKey = resolved.apiKey,
            customHeaders = resolved.customHeaders,
            event = event,
            routeTag = resolved.routeTag,
            protocolType = resolved.protocolType,
            wireApi = resolved.wireApi
        )
    }

    /**
     * 发送 LLM 请求并处理流式响应 (SSE格式)，返回Flow，并且支持对话上下文功能
     *
     * @param model 模型名称
     * @param messages 对话消息列表
     * @param event 事件监听器
     * @return EventSource 事件源
     */
    suspend fun postLLMStreamRequestWithContextAsFlow(
        model: String,
        messages: List<Map<String, Any>>,
        event: EventSourceListener,
        enableThinking: Boolean? = null,
        explicitApiBase: String? = null,
        explicitApiKey: String? = null,
        explicitCustomHeaders: Map<String, String>? = null,
        explicitModel: String? = null,
        explicitProtocolType: String? = null,
        explicitWireApi: String? = null,
        reasoningEffort: String? = null,
        promptCacheKey: String? = null
    ): EventSource {
        val resolved = resolveSceneRequest(
            modelOrScene = model,
            explicitApiBase = explicitApiBase,
            explicitApiKey = explicitApiKey,
            explicitCustomHeaders = explicitCustomHeaders,
            explicitModel = explicitModel,
            explicitProtocolType = explicitProtocolType,
            explicitWireApi = explicitWireApi
        )
        logSceneProfile(resolved)
        return postOpenAIStreamRequestAsFlow(
            chatRequest = createChatRequestFromMessages(
                resolved = resolved,
                messages = messages,
                enableThinking = enableThinking,
                reasoningEffort = reasoningEffort,
                promptCacheKey = promptCacheKey
            ),
            apiBase = resolved.apiBase,
            apiKey = resolved.apiKey,
            customHeaders = resolved.customHeaders,
            event = event,
            routeTag = resolved.routeTag,
            protocolType = resolved.protocolType,
            wireApi = resolved.wireApi
        )
    }

    /**
     * 发送标准 Chat Completions Tool Calling 请求（SSE）
     *
     * 请求体由调用方按标准字段构造，例如：
     * messages / model / max_completion_tokens / stream / stream_options / tools
     */
    suspend fun postChatCompletionsStreamRequest(
        model: String,
        requestBodyJson: String,
        event: EventSourceListener,
        explicitApiBase: String? = null,
        explicitApiKey: String? = null,
        explicitCustomHeaders: Map<String, String>? = null,
        explicitModel: String? = null,
        explicitProtocolType: String? = null,
        explicitWireApi: String? = null,
        forceHttp1: Boolean = false
    ): EventSource {
        val resolved = resolveSceneRequest(
            modelOrScene = model,
            explicitApiBase = explicitApiBase,
            explicitApiKey = explicitApiKey,
            explicitCustomHeaders = explicitCustomHeaders,
            explicitModel = explicitModel,
            explicitProtocolType = explicitProtocolType,
            explicitWireApi = explicitWireApi
        )
        logSceneProfile(resolved)
        return postOpenAIChatCompletionsStreamRequest(
            resolved = resolved,
            requestBodyJson = requestBodyJson,
            event = event,
            forceHttp1 = forceHttp1
        )
    }

    /**
     * 发送 LLM 请求并获取响应（普通返回）
     *
     * @param url 请求地址
     * @param jsonBody JSON 请求体
     * @param headers 请求头
     * @return 服务器响应内容
     */
    suspend fun postLLMRequest(
        model: String, text: String
    ): ResultBean = withContext(Dispatchers.IO) {
        val resolved = resolveSceneRequest(model)
        logSceneProfile(resolved)
        val response = postSceneChatCompletionInternal(
            resolved = resolved,
            request = createChatRequestFromText(resolved, text),
            retryOnBadRequest = false
        )
        if (!response.success) {
            throw IllegalStateException(response.message.ifBlank { "LLM request failed" })
        }
        return@withContext ResultBean(response.content.ifBlank { response.message })
    }

    suspend fun postSceneChatCompletion(
        chatRequest: ChatCompletionRequest
    ): SceneChatCompletionResponse {
        val resolved = resolveSceneRequest(
            modelOrScene = chatRequest.model
        )
        logSceneProfile(resolved)
        OmniLog.i(
            TAG,
            "postSceneChatCompletion scene=${chatRequest.model} resolvedModel=${resolved.resolvedModel} parser=${resolved.responseParser.wireValue} tools=${chatRequest.tools.size} messages=${chatRequest.messages.size}"
        )
        return postSceneChatCompletionInternal(
            resolved = resolved,
            request = chatRequest.copy(model = resolved.resolvedModel, stream = false),
            retryOnBadRequest = chatRequest.tools.isNotEmpty()
        )
    }

    suspend fun postSceneChatCompletionStream(
        chatRequest: ChatCompletionRequest,
        event: EventSourceListener
    ): SceneChatCompletionStreamHandle {
        val resolved = resolveSceneRequest(
            modelOrScene = chatRequest.model
        )
        logSceneProfile(resolved)
        OmniLog.i(
            TAG,
            "postSceneChatCompletionStream scene=${chatRequest.model} resolvedModel=${resolved.resolvedModel} parser=${resolved.responseParser.wireValue} tools=${chatRequest.tools.size} messages=${chatRequest.messages.size}"
        )
        val eventSource = postOpenAIChatCompletionsStreamRequest(
            resolved = resolved,
            requestBodyJson = encodeChatCompletionRequest(
                chatRequest.copy(
                    model = resolved.resolvedModel,
                    stream = true
                )
            ),
            event = event
        )
        return SceneChatCompletionStreamHandle(
            eventSource = eventSource,
            parser = resolved.responseParser,
            route = resolved.routeTag,
            resolvedModel = resolved.resolvedModel
        )
    }

    private data class CompletionRequestVariant(
        val name: String,
        val request: ChatCompletionRequest
    )

    private suspend fun postSceneChatCompletionInternal(
        resolved: ResolvedSceneRequest,
        request: ChatCompletionRequest,
        retryOnBadRequest: Boolean
    ): SceneChatCompletionResponse = withContext(Dispatchers.IO) {
        val base = normalizeApiBase(resolved.apiBase ?: "")
        if (base == null) {
            return@withContext buildFailureSceneResponse(
                code = "500",
                message = "Invalid apiBase",
                parser = resolved.responseParser,
                routeTag = resolved.routeTag
            )
        }

        if (resolved.protocolType == "anthropic") {
            val anthropicJson = convertToAnthropicRequestJson(
                request.copy(model = resolved.resolvedModel, stream = false)
            )
            val anthropicUrl = buildAnthropicMessagesUrl(base)
            OmniLog.d(TAG, "=== Anthropic Request Debug ===")
            OmniLog.d(TAG, "URL: $anthropicUrl")
            OmniLog.d(TAG, "Model: ${resolved.resolvedModel}, hasApiKey=${!resolved.apiKey.isNullOrBlank()}")
            OmniLog.d(TAG, "Request Body: ${anthropicJson.take(2000)}")
            OmniLog.d(TAG, "==============================")
            val requestBody = anthropicJson.toRequestBody("application/json".toMediaType())
            val requestCall = buildAnthropicRequestBuilder(
                url = anthropicUrl,
                requestBody = requestBody,
                apiKey = resolved.apiKey,
                hasCacheControl = hasCacheControl(anthropicJson),
                customHeaders = resolved.customHeaders
            ).build()
            logRequestHeaders("[anthropic model=${resolved.resolvedModel}]", requestCall.headers.toMultimap().mapValues {
                it.value.joinToString(",")
            })
            val response = sceneCompletionClient.newCall(requestCall).execute()
            val responseBody = response.body?.string()
            OmniLog.d(TAG, "Anthropic Response Status: ${response.code}")
            logResponseBody("[anthropic model=${resolved.resolvedModel}]", responseBody)
            persistAiRequestLog(
                seed = AiRequestLogSeed(
                    label = "anthropic/messages",
                    model = resolved.resolvedModel,
                    protocolType = "anthropic",
                    url = anthropicUrl,
                    stream = false,
                    requestJson = anthropicJson,
                    conversationId = conversationIdFromPromptCacheKey(request.promptCacheKey)
                ),
                success = response.isSuccessful,
                statusCode = response.code,
                responseJson = AiRequestLogStore.prettyJsonOrRaw(responseBody),
                errorMessage = if (response.isSuccessful) null else extractAvailabilityMessage(responseBody)
            )
            if (!response.isSuccessful) {
                return@withContext buildFailureSceneResponse(
                    code = response.code.toString(),
                    message = extractAvailabilityMessage(responseBody),
                    parser = resolved.responseParser,
                    routeTag = resolved.routeTag,
                    rawResponseBody = responseBody
                )
            }
            return@withContext parseAnthropicResponse(
                body = responseBody,
                parser = resolved.responseParser,
                routeTag = resolved.routeTag
            )
        }

        val normalizedWireApi = OpenAiWireApi.normalize(resolved.wireApi)
        val url = buildOpenAIInferenceUrl(base, normalizedWireApi)
        val variants = if (retryOnBadRequest) {
            buildSceneRequestVariants(request.copy(model = resolved.resolvedModel, stream = false))
        } else {
            listOf(CompletionRequestVariant("default", request.copy(model = resolved.resolvedModel, stream = false)))
        }

        var lastFailure: SceneChatCompletionResponse? = null
        for ((index, variant) in variants.withIndex()) {
            if (index > 0) {
                OmniLog.w(
                    TAG,
                    "retry scene completion variant=${variant.name} model=${resolved.resolvedModel} parser=${resolved.responseParser.wireValue}"
                )
            }

            val requestJson = if (OpenAiWireApi.isResponses(normalizedWireApi)) {
                buildOpenAIResponsesRequestBody(
                    requestBodyJson = completionJson.encodeToString(variant.request),
                    resolvedModel = variant.request.model
                )
            } else {
                buildOpenAICompatibleRequestBody(
                    requestBodyJson = completionJson.encodeToString(variant.request),
                    resolvedModel = variant.request.model,
                    includeLegacyMirrors = false,
                    protocolType = resolved.protocolType,
                    apiBase = base
                )
            }
            OmniLog.d(TAG, "=== OpenAI Request Debug ===")
            OmniLog.d(TAG, "URL: $url")
            OmniLog.d(TAG, "Model: ${variant.request.model}, hasApiKey=${!resolved.apiKey.isNullOrBlank()}, variant=${variant.name}")
            OmniLog.d(TAG, "Request Body: ${requestJson.take(2000)}")
            OmniLog.d(TAG, "============================")

            val requestBody = requestJson.toRequestBody("application/json".toMediaType())
            val requestCall = buildOpenAIRequestBuilder(
                url = url,
                requestBody = requestBody,
                apiKey = resolved.apiKey,
                customHeaders = resolved.customHeaders
            ).build()
            logRequestHeaders("[openai model=${variant.request.model}]", requestCall.headers.toMultimap().mapValues {
                it.value.joinToString(",")
            })

            val response = sceneCompletionClient.newCall(requestCall).execute()
            val responseBody = response.body?.string()
            OmniLog.d(TAG, "Response Status: ${response.code}")
            logResponseBody("[openai_compatible model=${variant.request.model}]", responseBody)
            persistAiRequestLog(
                seed = AiRequestLogSeed(
                    label = if (OpenAiWireApi.isResponses(normalizedWireApi)) {
                        "openai/responses"
                    } else {
                        "openai/chat.completions"
                    },
                    model = variant.request.model,
                    protocolType = resolved.protocolType,
                    url = url,
                    stream = false,
                    requestJson = requestJson,
                    conversationId = conversationIdFromPromptCacheKey(request.promptCacheKey)
                ),
                success = response.isSuccessful,
                statusCode = response.code,
                responseJson = AiRequestLogStore.prettyJsonOrRaw(responseBody),
                errorMessage = if (response.isSuccessful) null else extractAvailabilityMessage(responseBody)
            )

            if (!response.isSuccessful) {
                val failure = buildFailureSceneResponse(
                    code = response.code.toString(),
                    message = extractAvailabilityMessage(responseBody),
                    parser = resolved.responseParser,
                    routeTag = resolved.routeTag,
                    rawResponseBody = responseBody
                )
                lastFailure = failure
                if (retryOnBadRequest && response.code == 400 && index < variants.lastIndex) {
                    OmniLog.w(TAG, "scene completion 400 on variant=${variant.name}: ${failure.message}")
                    continue
                }
                return@withContext failure
            }

            return@withContext parseOpenAiStructuredSceneResponse(
                response = responseBody,
                parser = resolved.responseParser,
                routeTag = resolved.routeTag,
                wireApi = normalizedWireApi
            )
        }

        return@withContext lastFailure ?: buildFailureSceneResponse(
            code = "500",
            message = "Request failed",
            parser = resolved.responseParser,
            routeTag = resolved.routeTag
        )
    }

    /**
     * 检测自定义 OpenAI-compatible 模型可用性
     */
    suspend fun checkOpenAiModelAvailability(
        model: String,
        apiBase: String,
        apiKey: String?,
        customHeaders: Map<String, String> = emptyMap(),
        wireApi: String = OpenAiWireApi.CHAT_COMPLETIONS
    ): ModelAvailabilityCheckResult = withContext(Dispatchers.IO) {
        val normalizedModel = model.trim()
        if (normalizedModel.isEmpty()) {
            return@withContext ModelAvailabilityCheckResult(
                available = false,
                code = null,
                message = "模型名不能为空"
            )
        }

        val normalizedApiBase = normalizeApiBase(apiBase)
            ?: return@withContext ModelAvailabilityCheckResult(
                available = false,
                code = null,
                message = "URL 非法，请输入 http(s) 地址"
            )
        val normalizedWireApi = OpenAiWireApi.normalize(wireApi)
        val url = buildOpenAIInferenceUrl(normalizedApiBase, normalizedWireApi)

        return@withContext try {
            val isOfficialDeepSeek = DeepSeekProvider.isOfficialBaseUrl(normalizedApiBase)
            val baseRequestJson = JSONObject().apply {
                put("model", normalizedModel)
                put("stream", false)
                put("max_tokens", 1)
                if (isOfficialDeepSeek) {
                    put("enable_thinking", false)
                } else {
                    put("temperature", 0)
                }
                put(
                    "messages",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", "ping")
                        }
                    )
                )
            }
            val requestJson = if (OpenAiWireApi.isResponses(normalizedWireApi)) {
                buildOpenAIResponsesRequestBody(
                    requestBodyJson = baseRequestJson.toString(),
                    resolvedModel = normalizedModel
                )
            } else {
                buildOpenAICompatibleRequestBody(
                    requestBodyJson = baseRequestJson.toString(),
                    resolvedModel = normalizedModel,
                    includeLegacyMirrors = false,
                    protocolType = DeepSeekProvider.normalizeProtocolType(null),
                    apiBase = normalizedApiBase
                )
            }

            val requestBody = requestJson.toRequestBody("application/json".toMediaType())
            val request = buildOpenAIRequestBuilder(
                url = url,
                requestBody = requestBody,
                apiKey = apiKey,
                customHeaders = customHeaders
            ).build()
            logRequestHeaders(
                "[provider availability openai model=$normalizedModel]",
                request.headers.toMultimap().mapValues { it.value.joinToString(",") }
            )
            val response = OkHttpClient().newCall(request).execute()
            val responseBody = response.body?.string()
            if (!response.isSuccessful) {
                return@withContext ModelAvailabilityCheckResult(
                    available = false,
                    code = response.code,
                    message = extractAvailabilityMessage(responseBody)
                )
            }

            val hasExpectedShape = try {
                val json = JSONObject(responseBody ?: "{}")
                if (OpenAiWireApi.isResponses(normalizedWireApi)) {
                    json.has("output") || json.has("output_text")
                } else {
                    val choices = json.optJSONArray("choices")
                    choices != null && choices.length() > 0
                }
            } catch (_: Exception) {
                false
            }

            if (hasExpectedShape) {
                ModelAvailabilityCheckResult(
                    available = true,
                    code = response.code,
                    message = "OK"
                )
            } else {
                ModelAvailabilityCheckResult(
                    available = false,
                    code = response.code,
                    message = if (OpenAiWireApi.isResponses(normalizedWireApi)) {
                        "响应不符合 Responses 结构（缺少 output）"
                    } else {
                        "响应不符合 OpenAI 结构（缺少 choices）"
                    }
                )
            }
        } catch (e: Exception) {
            ModelAvailabilityCheckResult(
                available = false,
                code = null,
                message = sanitizeShortMessage(e.message ?: "请求异常")
            )
        }
    }

    suspend fun checkProviderModelAvailability(
        model: String,
        apiBase: String,
        apiKey: String?,
        customHeaders: Map<String, String> = emptyMap(),
        protocolType: String = "openai_compatible",
        wireApi: String = OpenAiWireApi.CHAT_COMPLETIONS
    ): ModelAvailabilityCheckResult {
        return if (DeepSeekProvider.normalizeProtocolType(protocolType) == "anthropic") {
            checkAnthropicModelAvailability(
                model = model,
                apiBase = apiBase,
                apiKey = apiKey,
                customHeaders = customHeaders
            )
        } else {
            checkOpenAiModelAvailability(
                model = model,
                apiBase = apiBase,
                apiKey = apiKey,
                customHeaders = customHeaders,
                wireApi = wireApi
            )
        }
    }

    suspend fun fetchProviderModels(
        apiBase: String,
        apiKey: String?,
        customHeaders: Map<String, String> = emptyMap(),
        protocolType: String = "openai_compatible",
        @Suppress("UNUSED_PARAMETER") wireApi: String = OpenAiWireApi.CHAT_COMPLETIONS
    ): List<ProviderModelOption> = withContext(Dispatchers.IO) {
        val normalizedApiBase = normalizeApiBase(apiBase)
            ?: return@withContext emptyList()
        val request = if (protocolType == "anthropic") {
            buildAnthropicRequestBuilder(
                url = buildAnthropicModelsUrl(normalizedApiBase),
                apiKey = apiKey,
                customHeaders = customHeaders
            ).get().build()
        } else {
            buildOpenAIRequestBuilder(
                url = buildOpenAIModelsUrl(normalizedApiBase),
                apiKey = apiKey,
                customHeaders = customHeaders
            ).get().build()
        }
        logRequestHeaders(
            "[provider models protocol=$protocolType]",
            request.headers.toMultimap().mapValues { it.value.joinToString(",") }
        )
        // This endpoint is used while creating a local ACP session when the
        // shared scene model binding has not been created yet. Keep the
        // blocking OkHttp call itself bounded; a coroutine timeout alone
        // cannot interrupt execute() while it is waiting on the socket.
        val response = OkHttpClient.Builder()
            .callTimeout(
                PROVIDER_MODELS_TIMEOUT_SECONDS,
                java.util.concurrent.TimeUnit.SECONDS,
            )
            .connectTimeout(
                PROVIDER_MODELS_TIMEOUT_SECONDS,
                java.util.concurrent.TimeUnit.SECONDS,
            )
            .readTimeout(
                PROVIDER_MODELS_TIMEOUT_SECONDS,
                java.util.concurrent.TimeUnit.SECONDS,
            )
            .build()
            .newCall(request)
            .execute()
        val responseBody = response.body?.string()
        if (!response.isSuccessful) {
            throw IllegalStateException(
                "获取模型列表失败 (${response.code})：${extractAvailabilityMessage(responseBody)}"
            )
        }

        parseProviderModelsResponse(responseBody)
    }

    private suspend fun checkAnthropicModelAvailability(
        model: String,
        apiBase: String,
        apiKey: String?,
        customHeaders: Map<String, String> = emptyMap()
    ): ModelAvailabilityCheckResult = withContext(Dispatchers.IO) {
        val normalizedModel = model.trim()
        if (normalizedModel.isEmpty()) {
            return@withContext ModelAvailabilityCheckResult(
                available = false,
                code = null,
                message = "妯″瀷鍚嶄笉鑳戒负绌?"
            )
        }

        val normalizedApiBase = normalizeApiBase(apiBase)
            ?: return@withContext ModelAvailabilityCheckResult(
                available = false,
                code = null,
                message = "URL 闈炴硶锛岃杈撳叆 http(s) 鍦板潃"
            )

        val requestJson = JSONObject().apply {
            put("model", normalizedModel)
            put("max_tokens", 1)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", "ping")
                    }
                )
            )
        }.toString()
        val request = buildAnthropicRequestBuilder(
            url = buildAnthropicMessagesUrl(normalizedApiBase),
            requestBody = requestJson.toRequestBody("application/json".toMediaType()),
            apiKey = apiKey,
            customHeaders = customHeaders
        ).build()
        logRequestHeaders(
            "[provider availability anthropic model=$normalizedModel]",
            request.headers.toMultimap().mapValues { it.value.joinToString(",") }
        )

        return@withContext try {
            val response = OkHttpClient().newCall(request).execute()
            val responseBody = response.body?.string()
            if (!response.isSuccessful) {
                return@withContext ModelAvailabilityCheckResult(
                    available = false,
                    code = response.code,
                    message = extractAvailabilityMessage(responseBody)
                )
            }

            val hasExpectedShape = try {
                val json = JSONObject(responseBody ?: "{}")
                val content = json.optJSONArray("content")
                content != null && content.length() > 0
            } catch (_: Exception) {
                false
            }

            ModelAvailabilityCheckResult(
                available = hasExpectedShape,
                code = response.code,
                message = if (hasExpectedShape) "OK" else "鍝嶅簲涓嶇鍚?Anthropic 缁撴瀯"
            )
        } catch (e: Exception) {
            ModelAvailabilityCheckResult(
                available = false,
                code = null,
                message = sanitizeShortMessage(e.message ?: "璇锋眰寮傚父")
            )
        }
    }

    private fun encodeChatCompletionRequest(request: ChatCompletionRequest): String {
        val requestJson = completionJson.encodeToString(request)
        return buildRequestBodyWithResolvedModel(
            requestBodyJson = requestJson,
            resolvedModel = request.model,
            includeLegacyMirrors = false
        )
    }

    private fun buildSceneRequestVariants(request: ChatCompletionRequest): List<CompletionRequestVariant> {
        val variants = mutableListOf<CompletionRequestVariant>()
        val seenPayloads = LinkedHashSet<String>()

        fun add(name: String, candidate: ChatCompletionRequest) {
            val encoded = encodeChatCompletionRequest(candidate)
            if (seenPayloads.add(encoded)) {
                variants.add(CompletionRequestVariant(name = name, request = candidate))
            }
        }

        add("default", request)

        if (request.tools.isEmpty()) {
            return variants
        }

        add("no_parallel_tool_calls", request.copy(parallelToolCalls = null))
        add(
            "no_tool_choice",
            request.copy(
                parallelToolCalls = null,
                toolChoice = null
            )
        )

        val normalizedMaxTokens = request.maxTokens ?: request.maxCompletionTokens
        add(
            "minimal_tools",
            request.copy(
                parallelToolCalls = null,
                toolChoice = null,
                temperature = null,
                topP = null,
                maxCompletionTokens = null,
                maxTokens = normalizedMaxTokens
            )
        )
        return variants
    }

    private fun buildFailureSceneResponse(
        code: String,
        message: String,
        parser: ModelSceneRegistry.ResponseParser,
        routeTag: String?,
        rawResponseBody: String? = null
    ): SceneChatCompletionResponse {
        return SceneChatCompletionResponse(
            success = false,
            code = code,
            message = message,
            parser = parser,
            route = routeTag,
            rawResponseBody = rawResponseBody
        )
    }

    private fun parseOpenAiStructuredSceneResponse(
        response: String?,
        parser: ModelSceneRegistry.ResponseParser,
        routeTag: String?,
        wireApi: String
    ): SceneChatCompletionResponse {
        if (OpenAiWireApi.isResponses(wireApi)) {
            return parseResponsesSceneResponse(response, parser, routeTag)
        }
        return try {
            val jsonObject = JSONObject(response ?: "{}")
            val choices = jsonObject.optJSONArray("choices")
                ?: return buildFailureSceneResponse(
                    code = "500",
                    message = "响应不符合 OpenAI 结构（缺少 choices）",
                    parser = parser,
                    routeTag = routeTag,
                    rawResponseBody = response
                )

            val firstChoice = choices.optJSONObject(0)
                ?: return buildFailureSceneResponse(
                    code = "500",
                    message = "响应不符合 OpenAI 结构（choices[0] 无效）",
                    parser = parser,
                    routeTag = routeTag,
                    rawResponseBody = response
                )

            val message = firstChoice.optJSONObject("message")
            val content = extractTextPayload(message?.opt("content") ?: firstChoice.opt("text"))
            val reasoning = listOf(
                message?.opt("reasoning_content"),
                message?.opt("reasoning"),
                message?.opt("thinking"),
                firstChoice.opt("reasoning_content"),
                firstChoice.opt("reasoning"),
                firstChoice.opt("thinking")
            ).asSequence()
                .map { extractTextPayload(it) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            val finishReason = firstChoice.optString("finish_reason").trim().takeIf { it.isNotEmpty() }
            val toolCalls = parseToolCalls(firstChoice, message)

            OmniLog.i(
                TAG,
                "[non-stream openai parse] content_len=${content.length}, " +
                    "reasoning_len=${reasoning.length}, tool_calls=${toolCalls.size}, " +
                    "finish=$finishReason, content_preview=${content.take(200)}"
            )

            SceneChatCompletionResponse(
                success = true,
                code = "200",
                message = "success",
                parser = parser,
                route = routeTag,
                content = content,
                reasoning = reasoning,
                finishReason = finishReason,
                toolCalls = toolCalls,
                rawResponseBody = response
            )
        } catch (e: Exception) {
            safeLogError("Failed to parse structured OpenAI response: ${e.message}")
            buildFailureSceneResponse(
                code = "500",
                message = "Parse error: ${e.message}",
                parser = parser,
                routeTag = routeTag,
                rawResponseBody = response
            )
        }
    }

    private fun parseResponsesSceneResponse(
        response: String?,
        parser: ModelSceneRegistry.ResponseParser,
        routeTag: String?
    ): SceneChatCompletionResponse {
        return try {
            val jsonObject = completionJson.parseToJsonElement(response ?: "{}") as? KxJsonObject
                ?: error("responses_root_not_object")
            val content = extractResponsesOutputText(jsonObject)
            val reasoning = extractResponsesReasoningText(jsonObject)
            val toolCalls = extractResponsesToolCalls(jsonObject)
            val finishReason = jsonObject.text("status").trim().takeIf { it.isNotEmpty() }
            if (content.isBlank() && toolCalls.isEmpty()) {
                return buildFailureSceneResponse(
                    code = "500",
                    message = "响应不符合 Responses 结构（缺少 output）",
                    parser = parser,
                    routeTag = routeTag,
                    rawResponseBody = response
                )
            }
            SceneChatCompletionResponse(
                success = true,
                code = "200",
                message = "success",
                parser = parser,
                route = routeTag,
                content = content,
                reasoning = reasoning,
                finishReason = finishReason,
                toolCalls = toolCalls,
                rawResponseBody = response
            )
        } catch (e: Exception) {
            safeLogError("Failed to parse responses output: ${e.message}")
            buildFailureSceneResponse(
                code = "500",
                message = "Responses parse error: ${e.message}",
                parser = parser,
                routeTag = routeTag,
                rawResponseBody = response
            )
        }
    }

    fun parseOpenAiResponsesBody(response: String?): SceneChatCompletionResponse =
        parseResponsesSceneResponse(
            response = response,
            parser = ModelSceneRegistry.ResponseParser.TEXT_CONTENT,
            routeTag = null,
        )

    private fun extractResponsesOutputText(root: KxJsonObject): String {
        val direct = root.text("output_text").trim()
        if (direct.isNotEmpty()) {
            return direct
        }
        val output = root["output"] as? KxJsonArray ?: return ""
        val buffer = StringBuilder()
        output.forEach { rawItem ->
            val item = rawItem as? KxJsonObject ?: return@forEach
            when (item.text("type")) {
                "message" -> {
                    val content = item["content"] as? KxJsonArray ?: return@forEach
                    content.forEach { rawBlock ->
                        val block = rawBlock as? KxJsonObject ?: return@forEach
                        when (block.text("type")) {
                            "output_text", "text", "input_text" -> {
                                buffer.append(
                                    block.text("text").ifEmpty {
                                        block.text("content")
                                    }
                                )
                            }
                        }
                    }
                }
                "output_text", "text" -> {
                    buffer.append(
                        item.text("text").ifEmpty {
                            item.text("content")
                        }
                    )
                }
            }
        }
        return buffer.toString()
    }

    private fun extractResponsesOutputText(root: JSONObject): String =
        (completionJson.parseToJsonElement(root.toString()) as? KxJsonObject)
            ?.let(::extractResponsesOutputText)
            .orEmpty()

    private fun extractResponsesReasoningText(root: KxJsonObject): String {
        val output = root["output"] as? KxJsonArray ?: return ""
        val buffer = StringBuilder()
        output.forEach { rawItem ->
            val item = rawItem as? KxJsonObject ?: return@forEach
            if (item.text("type") != "reasoning") return@forEach
            val summary = item["summary"] as? KxJsonArray
            if (summary != null) {
                summary.forEach { rawBlock ->
                    val block = rawBlock as? KxJsonObject ?: return@forEach
                    buffer.append(block.text("text"))
                }
            } else {
                buffer.append(item.text("text"))
            }
        }
        return buffer.toString()
    }

    private fun extractResponsesReasoningText(root: JSONObject): String =
        (completionJson.parseToJsonElement(root.toString()) as? KxJsonObject)
            ?.let(::extractResponsesReasoningText)
            .orEmpty()

    private fun extractResponsesToolCalls(root: KxJsonObject): List<AssistantToolCall> {
        val output = root["output"] as? KxJsonArray ?: return emptyList()
        val parsed = mutableListOf<AssistantToolCall>()
        output.forEachIndexed { index, rawItem ->
            val item = rawItem as? KxJsonObject ?: return@forEachIndexed
            if (item.text("type") != "function_call") return@forEachIndexed
            val name = item.text("name").trim()
            if (name.isEmpty()) return@forEachIndexed
            val rawArguments = item["arguments"]
            val arguments = (rawArguments as? JsonPrimitive)?.contentOrNull
                ?: rawArguments?.toString()
                ?: "{}"
            parsed += AssistantToolCall(
                id = item.text("call_id").ifBlank { item.text("id").ifBlank { "tool_$index" } },
                function = AssistantToolCallFunction(
                    name = name,
                    arguments = arguments,
                )
            )
        }
        return parsed
    }

    private fun KxJsonObject.text(name: String): String =
        (get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun parseToolCalls(
        choice: JSONObject,
        message: JSONObject?
    ): List<AssistantToolCall> {
        val toolCallsArray = message?.optJSONArray("tool_calls") ?: choice.optJSONArray("tool_calls")
        if (toolCallsArray == null || toolCallsArray.length() == 0) {
            return emptyList()
        }

        val parsed = mutableListOf<AssistantToolCall>()
        for (i in 0 until toolCallsArray.length()) {
            val toolCall = toolCallsArray.optJSONObject(i) ?: continue
            val functionObj = toolCall.optJSONObject("function") ?: continue
            val name = functionObj.optString("name").trim()
            if (name.isEmpty()) continue
            val argumentsRaw = functionObj.opt("arguments")
            val arguments = when (argumentsRaw) {
                null -> "{}"
                is String -> argumentsRaw
                is JSONObject, is JSONArray -> argumentsRaw.toString()
                else -> argumentsRaw.toString()
            }
            parsed.add(
                AssistantToolCall(
                    id = toolCall.optString("id").ifBlank { "tool_call_$i" },
                    type = toolCall.optString("type").ifBlank { "function" },
                    function = AssistantToolCallFunction(
                        name = name,
                        arguments = arguments
                    )
                )
            )
        }
        return parsed
    }

    private fun extractTextPayload(contentRaw: Any?): String {
        return when (contentRaw) {
            is String -> contentRaw
            is JSONArray -> {
                val buffer = StringBuilder()
                for (i in 0 until contentRaw.length()) {
                    val item = contentRaw.opt(i)
                    when (item) {
                        is String -> buffer.append(item)
                        is JSONObject -> {
                            val type = item.optString("type", "")
                            if (type.equals("text", ignoreCase = true)) {
                                buffer.append(item.optString("text"))
                            } else if (item.has("text")) {
                                buffer.append(item.optString("text"))
                            }
                        }
                    }
                }
                buffer.toString()
            }
            is JSONObject -> when {
                contentRaw.has("text") -> contentRaw.optString("text")
                contentRaw.has("content") -> contentRaw.optString("content")
                else -> ""
            }
            else -> ""
        }.trim()
    }

    private fun safeLogError(message: String) {
        runCatching { OmniLog.e(TAG, message) }
    }


}
