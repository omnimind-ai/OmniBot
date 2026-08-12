package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.account.OmniAccount
import cn.com.omnimind.baselib.account.PlatformModelsUnavailableException
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionThinking
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.DeepSeekProvider
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.OmniOfficialProvider
import cn.com.omnimind.baselib.llm.PlatformAiProvisioner
import cn.com.omnimind.baselib.llm.ReasoningStreamUpdatePolicy
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.media.PlatformMediaProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

interface AgentLlmClient {
    suspend fun streamTurn(
        request: ChatCompletionRequest,
        onReasoningUpdate: (suspend (String) -> Unit)? = null,
        onContentUpdate: (suspend (String) -> Unit)? = null
    ): ChatCompletionTurn
}

class AgentStreamRequestException(
    val statusCode: Int?,
    val reason: String,
    val responseBody: String?
) : RuntimeException(
    "chat completion stream request failed${
        statusCode?.let { "($it)" }.orEmpty()
    }: $reason"
)

class AgentStreamReasoningLeakException(
    reason: String
) : RuntimeException(reason)

class HttpAgentLlmClient(
    private val scope: CoroutineScope,
    private val modelOverride: AgentModelOverride? = null,
    private val streamRequestOp: suspend (
        model: String,
        requestBodyJson: String,
        event: EventSourceListener,
        explicitApiBase: String?,
        explicitApiKey: String?,
        explicitCustomHeaders: Map<String, String>?,
        explicitModel: String?,
        explicitProtocolType: String?,
        explicitWireApi: String?,
        forceHttp1: Boolean
    ) -> EventSource = { model, requestBodyJson, event, explicitApiBase, explicitApiKey, explicitCustomHeaders, explicitModel, explicitProtocolType, explicitWireApi, forceHttp1 ->
        HttpController.postChatCompletionsStreamRequest(
            model = model,
            requestBodyJson = requestBodyJson,
            event = event,
            explicitApiBase = explicitApiBase,
            explicitApiKey = explicitApiKey,
            explicitCustomHeaders = explicitCustomHeaders,
            explicitModel = explicitModel,
            explicitProtocolType = explicitProtocolType,
            explicitWireApi = explicitWireApi,
            forceHttp1 = forceHttp1
        )
    },
    private val resolveRouteInfoOp: (
        modelOrScene: String,
        explicitApiBase: String?,
        explicitApiKey: String?,
        explicitCustomHeaders: Map<String, String>?,
        explicitModel: String?,
        explicitProtocolType: String?,
        explicitWireApi: String?
    ) -> HttpController.ChatCompletionRouteInfo = { modelOrScene, explicitApiBase, explicitApiKey, explicitCustomHeaders, explicitModel, explicitProtocolType, explicitWireApi ->
        HttpController.resolveChatCompletionRouteInfo(
            modelOrScene = modelOrScene,
            explicitApiBase = explicitApiBase,
            explicitApiKey = explicitApiKey,
            explicitCustomHeaders = explicitCustomHeaders,
            explicitModel = explicitModel,
            explicitProtocolType = explicitProtocolType,
            explicitWireApi = explicitWireApi
        )
    },
    private val refreshPlatformSessionOp: suspend () -> Boolean = {
        val access = OmniAccount.currentAiRequestAccess()
        if (access.usesPlatform) {
            OmniAccount.repository().refreshSession()
            true
        } else {
            false
        }
    },
    private val resolvePlatformVisionModelOp: suspend () -> String? = {
        val access = OmniAccount.currentAiRequestAccess()
        if (!access.usesPlatform) {
            null
        } else {
            PlatformAiProvisioner.ensureReadyStatus().defaultVisionModelId
                ?: throw PlatformModelsUnavailableException(
                    "官方服务当前没有可用的图片理解模型"
                )
        }
    },
    private val streamIdleWatchdogMs: Long = 0L,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }
) : AgentLlmClient {
    private val tag = "HttpAgentLlmClient"

    private companion object {
        const val REASONING_UPDATE_INTERVAL_MS =
            ReasoningStreamUpdatePolicy.DEFAULT_INTERVAL_MS
        const val DEFAULT_CLOSED_STREAM_ERROR =
            "chat completion stream closed before completion signal"
    }

    private data class StreamRequestVariant(
        val name: String,
        val request: ChatCompletionRequest
    )

    override suspend fun streamTurn(
        request: ChatCompletionRequest,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?
    ): ChatCompletionTurn {
        val platformVisionModel = if (request.hasImageInput()) {
            resolvePlatformVisionModelOp()?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val routedRequest = platformVisionModel?.let { request.copy(model = it) } ?: request
        val modelCandidates = buildModelCandidates(routedRequest.model)
        val sanitizedRequest = sanitizeRequestForTarget(routedRequest)
        var lastFailure: AgentStreamRequestException? = null

        for (modelIndex in modelCandidates.indices) {
            val candidateModel = modelCandidates[modelIndex]
            val routeInfo = resolveRouteInfoOp(
                candidateModel,
                modelOverride?.apiBase,
                modelOverride?.apiKey,
                modelOverride?.customHeaders,
                modelOverride?.modelId,
                modelOverride?.protocolType,
                modelOverride?.wireApi
            )
            val variants = buildRequestVariants(sanitizedRequest, routeInfo)
            for (variantIndex in variants.indices) {
                val variant = variants[variantIndex]
                try {
                    if (modelIndex > 0 || variantIndex > 0) {
                        OmniLog.w(
                            tag,
                            "retry stream request model=$candidateModel variant=${variant.name}"
                        )
                    }
                    // Encode lazily, one variant at a time, so we never hold multiple
                    // copies of a potentially huge request payload in memory at once.
                    val requestJson = json.encodeToString(variant.request)
                    if (routeInfo.providerProfileId == OmniOfficialProvider.PROFILE_ID) {
                        PlatformMediaProtocol.requirePlatformJsonRequestWithinLimit(requestJson)
                    }
                    return streamTurnWithPlatformAuthRetry(
                        model = candidateModel,
                        requestJson = requestJson,
                        onReasoningUpdate = onReasoningUpdate,
                        onContentUpdate = onContentUpdate
                    )
                } catch (error: AgentStreamRequestException) {
                    lastFailure = error
                    val canRetryVariant =
                        error.statusCode == 400 && variantIndex < variants.lastIndex
                    if (canRetryVariant) {
                        OmniLog.w(
                            tag,
                            "stream variant=${variant.name} failed with 400: ${error.reason}"
                        )
                        continue
                    }

                    val canFallbackModel =
                        modelIndex < modelCandidates.lastIndex && isModelNotSupported(error)
                    if (canFallbackModel) {
                        val nextModel = modelCandidates[modelIndex + 1]
                        OmniLog.w(
                            tag,
                            "model=$candidateModel not supported, fallback to model=$nextModel; reason=${error.reason}"
                        )
                        break
                    }
                    throw error
                } catch (error: AgentStreamReasoningLeakException) {
                    if (shouldRetryNextVariantAfterReasoningLeak(routeInfo, variants, variantIndex)) {
                        OmniLog.w(
                            tag,
                            "stream variant=${variant.name} leaked inline reasoning on guarded route; retrying next conservative variant"
                        )
                        continue
                    }
                    throw error
                }
            }
        }

        throw lastFailure ?: IllegalStateException("chat completion stream failed with unknown reason")
    }

    private suspend fun streamTurnWithPlatformAuthRetry(
        model: String,
        requestJson: String,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?,
    ): ChatCompletionTurn {
        return try {
            streamTurnOnce(model, requestJson, onReasoningUpdate, onContentUpdate)
        } catch (error: AgentStreamRequestException) {
            if (error.statusCode != 401 || !refreshPlatformSessionOp()) {
                throw error
            }
            OmniLog.i(tag, "platform access token refreshed after 401; retrying once")
            streamTurnOnce(model, requestJson, onReasoningUpdate, onContentUpdate)
        }
    }

    private suspend fun streamTurnOnce(
        model: String,
        requestJson: String,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?
    ): ChatCompletionTurn {
        return try {
            doStreamTurnOnce(model, requestJson, onReasoningUpdate, onContentUpdate, forceHttp1 = false)
        } catch (e: AgentStreamRequestException) {
            if (isHttp2ProtocolError(e)) {
                OmniLog.w(tag, "HTTP/2 stream PROTOCOL_ERROR, retrying with HTTP/1.1")
                doStreamTurnOnce(model, requestJson, onReasoningUpdate, onContentUpdate, forceHttp1 = true)
            } else {
                throw e
            }
        }
    }

    private fun isHttp2ProtocolError(error: AgentStreamRequestException): Boolean {
        return error.reason.contains("PROTOCOL_ERROR", ignoreCase = true)
                || error.reason.contains("stream was reset", ignoreCase = true)
    }

    private fun shouldBufferLeadingInlineThinkTag(
        routeInfo: HttpController.ChatCompletionRouteInfo
    ): Boolean {
        if (shouldGuardNvidiaKimiReasoningLeak(routeInfo)) {
            return true
        }
        val protocolType = routeInfo.protocolType.trim().ifEmpty { "openai_compatible" }
        if (!protocolType.equals("openai_compatible", ignoreCase = true)) {
            return false
        }
        return sequenceOf(routeInfo.resolvedModel, routeInfo.requestedModel)
            .map { it.trim().lowercase() }
            .any { model ->
                model.startsWith("qwen") ||
                    model.contains("/qwen") ||
                    model.contains(":qwen") ||
                    model.contains("_qwen") ||
                    model.contains("-qwen")
            }
    }

    private suspend fun doStreamTurnOnce(
        model: String,
        requestJson: String,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?,
        forceHttp1: Boolean
    ): ChatCompletionTurn {
        val streamDone = CompletableDeferred<ChatCompletionTurn>()
        val completed = AtomicBoolean(false)
        val routeInfo = resolveRouteInfoOp(
            model,
            modelOverride?.apiBase,
            modelOverride?.apiKey,
            modelOverride?.customHeaders,
            modelOverride?.modelId,
            modelOverride?.protocolType,
            modelOverride?.wireApi
        )
        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            includeReasoningInAssistantMessage = routeInfo.requiresReasoningEcho,
            bufferLeadingTextUntilInlineThinkTag = shouldBufferLeadingInlineThinkTag(routeInfo),
            guardLeadingReasoningLeak = shouldGuardNvidiaKimiReasoningLeak(routeInfo)
        )
        var lastReasoning = ""
        var lastReasoningEmitLength = 0
        var lastReasoningEmitAt = 0L
        var reasoningEmitJob: Job? = null
        val reasoningLock = Any()
        var lastContent = ""
        var eventSource: EventSource? = null
        var streamIdleWatchdog: Job? = null
        val emissionQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        val emissionLock = Any()
        val emissionJob = scope.launch {
            for (block in emissionQueue) {
                runCatching { block.invoke() }
                    .onFailure { OmniLog.w(tag, "stream emission failed: ${it.message}") }
            }
        }
        fun enqueueEmission(block: suspend () -> Unit) {
            if (emissionQueue.isClosedForSend) {
                return
            }
            emissionQueue.trySend(block)
        }

        fun cancelWatchdog() {
            streamIdleWatchdog?.cancel()
            streamIdleWatchdog = null
        }

        fun scheduleWatchdog() {
            val timeoutMs = streamIdleWatchdogMs
            if (timeoutMs <= 0L) {
                return
            }
            cancelWatchdog()
            streamIdleWatchdog = scope.launch {
                delay(timeoutMs)
                if (!completed.compareAndSet(false, true)) {
                    return@launch
                }
                streamDone.completeExceptionally(
                    IllegalStateException("chat completion stream idle timeout after ${timeoutMs}ms")
                )
                eventSource?.cancel()
            }
        }

        fun dispatchReasoningSnapshot(reasoning: String) {
            lastReasoning = reasoning
            if (onReasoningUpdate != null) {
                enqueueEmission {
                    onReasoningUpdate.invoke(reasoning)
                }
            }
        }

        fun collectReasoningSnapshotLocked(): String? {
            val length = accumulator.currentReasoningLength()
            if (length <= 0 || length == lastReasoningEmitLength) return null
            val reasoning = accumulator.currentReasoning()
            lastReasoningEmitLength = length
            if (reasoning.isBlank() || reasoning == lastReasoning) return null
            lastReasoning = reasoning
            lastReasoningEmitAt = System.currentTimeMillis()
            return reasoning
        }

        fun scheduleReasoningSnapshotLocked(delayMs: Long) {
            reasoningEmitJob = scope.launch {
                delay(delayMs)
                synchronized(emissionLock) {
                    val snapshot = synchronized(reasoningLock) {
                        reasoningEmitJob = null
                        collectReasoningSnapshotLocked()
                    }
                    if (snapshot != null) {
                        dispatchReasoningSnapshot(snapshot)
                    }
                }
            }
        }

        fun emitReasoning(force: Boolean = false) {
            var snapshot: String? = null
            synchronized(emissionLock) {
                synchronized(reasoningLock) {
                    val length = accumulator.currentReasoningLength()
                    if (length <= 0 || length == lastReasoningEmitLength) return
                    if (force) {
                        reasoningEmitJob?.cancel()
                        reasoningEmitJob = null
                        snapshot = collectReasoningSnapshotLocked()
                        return@synchronized
                    }
                    if (reasoningEmitJob?.isActive == true) return
                    val delayMs = ReasoningStreamUpdatePolicy.nextDelayMs(
                        hasEmittedBefore = lastReasoningEmitLength > 0,
                        lastEmitAtMs = lastReasoningEmitAt,
                        nowMs = System.currentTimeMillis(),
                        intervalMs = REASONING_UPDATE_INTERVAL_MS
                    )
                    if (delayMs <= 0L) {
                        snapshot = collectReasoningSnapshotLocked()
                    } else {
                        scheduleReasoningSnapshotLocked(delayMs)
                    }
                }
                if (snapshot != null) {
                    dispatchReasoningSnapshot(snapshot)
                }
            }
        }

        fun emitContent() {
            val content = accumulator.currentContent()
            if (content.isEmpty() || content == lastContent) return
            synchronized(emissionLock) {
                var reasoningSnapshot: String? = null
                synchronized(reasoningLock) {
                    if (accumulator.currentReasoningLength() > 0) {
                        reasoningEmitJob?.cancel()
                        reasoningEmitJob = null
                        reasoningSnapshot = collectReasoningSnapshotLocked()
                    }
                }
                if (reasoningSnapshot != null) {
                    dispatchReasoningSnapshot(reasoningSnapshot)
                }
                lastContent = content
                if (onContentUpdate != null) {
                    enqueueEmission {
                        onContentUpdate.invoke(content)
                    }
                }
            }
        }

        fun completeStream(eventSource: EventSource? = null) {
            if (!completed.compareAndSet(false, true)) return
            cancelWatchdog()
            runCatching {
                val turn = accumulator.buildTurn()
                enforceReasoningEchoIfRequired(turn, routeInfo)
                emitReasoning(force = true)
                emitContent()
                turn
            }.onSuccess { turn ->
                streamDone.complete(turn)
            }.onFailure { error ->
                streamDone.completeExceptionally(error)
            }
            eventSource?.cancel()
        }

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                scheduleWatchdog()
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (completed.get()) return
                runCatching {
                    scheduleWatchdog()
                    val done = accumulator.consume(data)
                    emitReasoning()
                    emitContent()
                    if (done) {
                        completeStream(eventSource)
                    }
                }.onFailure { error ->
                    if (completed.compareAndSet(false, true)) {
                        val failure = if (error is AgentStreamReasoningLeakException) {
                            error
                        } else {
                            IllegalStateException("invalid chat completion stream chunk: ${error.message}", error)
                        }
                        streamDone.completeExceptionally(failure)
                        eventSource.cancel()
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (completed.get()) {
                    cancelWatchdog()
                    return
                }
                if (accumulator.canFinalizeOnClosed()) {
                    completeStream()
                    return
                }
                cancelWatchdog()
                if (completed.compareAndSet(false, true)) {
                    streamDone.completeExceptionally(
                        IllegalStateException(DEFAULT_CLOSED_STREAM_ERROR)
                    )
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (!completed.compareAndSet(false, true)) return
                cancelWatchdog()
                val responseBody = extractResponseBody(response)
                val reason = extractErrorReason(responseBody)
                    ?: sanitizeReason(t?.message)
                    ?: "unknown stream failure"
                streamDone.completeExceptionally(
                    AgentStreamRequestException(
                        statusCode = response?.code,
                        reason = reason,
                        responseBody = responseBody
                    )
                )
            }
        }

        try {
            eventSource = streamRequestOp(
                model,
                requestJson,
                listener,
                modelOverride?.apiBase,
                modelOverride?.apiKey,
                modelOverride?.customHeaders,
                modelOverride?.modelId,
                modelOverride?.protocolType,
                modelOverride?.wireApi,
                forceHttp1
            )
            scheduleWatchdog()
            return streamDone.await()
        } finally {
            cancelWatchdog()
            reasoningEmitJob?.cancel()
            eventSource?.cancel()
            emissionQueue.close()
            runCatching { emissionJob.join() }
        }
    }

    private fun enforceReasoningEchoIfRequired(
        turn: ChatCompletionTurn,
        routeInfo: HttpController.ChatCompletionRouteInfo
    ) {
        if (!routeInfo.requiresReasoningEcho) {
            return
        }
        if (turn.reasoning.isBlank()) {
            return
        }
        if (!turn.message.reasoningContent.isNullOrBlank()) {
            return
        }
        throw IllegalStateException(
            "assistant turn is missing reasoning_content for route=${routeInfo.resolvedModel} " +
                "protocol=${routeInfo.protocolType} despite non-empty reasoning output"
        )
    }

    private fun buildRequestVariants(
        request: ChatCompletionRequest,
        routeInfo: HttpController.ChatCompletionRouteInfo
    ): List<StreamRequestVariant> {
        val variants = mutableListOf<StreamRequestVariant>()
        val seenRequests = LinkedHashSet<ChatCompletionRequest>()
        // Dedup by structural equality of the request itself instead of by its
        // serialized JSON. This is equivalent (equal data classes serialize to equal
        // JSON) but avoids eagerly materializing every variant's payload string, which
        // could be tens of MB each and previously exhausted the heap (issue #429).
        fun add(name: String, candidate: ChatCompletionRequest) {
            if (seenRequests.add(candidate)) {
                variants.add(StreamRequestVariant(name = name, request = candidate))
            }
        }

        if (shouldGuardNvidiaKimiReasoningLeak(routeInfo)) {
            val noThinkingRequest = request.copy(
                enableThinking = false,
                reasoningEffort = "none",
                thinking = ChatCompletionThinking(type = "disabled")
            )
            add("nvidia_no_thinking", noThinkingRequest)
            add(
                "nvidia_no_thinking_minimal",
                noThinkingRequest.copy(streamOptions = null)
            )
        }

        add("default", request)
        add(
            "no_stream_options",
            request.copy(streamOptions = null)
        )
        add(
            "minimal",
            request.copy(
                streamOptions = null,
                parallelToolCalls = null,
                toolChoice = null
            )
        )

        request.promptCacheKey?.let {
            add(
                "no_prompt_cache_key",
                request.copy(
                    streamOptions = null,
                    parallelToolCalls = null,
                    toolChoice = null,
                    promptCacheKey = null
                )
            )
        }

        val legacyFunctions = request.tools.map { it.function }
        if (legacyFunctions.isNotEmpty() && !routeInfo.wireApi.equals("responses", ignoreCase = true)) {
            add(
                "legacy_functions",
                request.copy(
                    streamOptions = null,
                    parallelToolCalls = null,
                    toolChoice = null,
                    tools = emptyList(),
                    functions = legacyFunctions,
                    functionCall = toLegacyFunctionCall(request.toolChoice)
                )
            )
        }
        return variants
    }

    private fun shouldRetryNextVariantAfterReasoningLeak(
        routeInfo: HttpController.ChatCompletionRouteInfo,
        variants: List<StreamRequestVariant>,
        variantIndex: Int
    ): Boolean {
        if (!shouldGuardNvidiaKimiReasoningLeak(routeInfo)) {
            return false
        }
        return variants
            .drop(variantIndex + 1)
            .any { it.name.startsWith("nvidia_no_thinking") }
    }

    private fun shouldGuardNvidiaKimiReasoningLeak(
        routeInfo: HttpController.ChatCompletionRouteInfo
    ): Boolean {
        val protocolType = routeInfo.protocolType.trim().ifEmpty { "openai_compatible" }
        if (!protocolType.equals("openai_compatible", ignoreCase = true)) {
            return false
        }
        if (!isNvidiaIntegrateApiBase(routeInfo.apiBase)) {
            return false
        }
        return sequenceOf(routeInfo.resolvedModel, routeInfo.requestedModel)
            .map(::normalizeReasoningLeakGuardModel)
            .any { it == "kimi-k2.6" }
    }

    private fun isNvidiaIntegrateApiBase(apiBase: String?): Boolean {
        val normalized = apiBase?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val host = runCatching { URI(normalized).host?.lowercase() }
            .getOrNull()
            ?.removePrefix("www.")
            ?: return false
        return host == "integrate.api.nvidia.com"
    }

    private fun normalizeReasoningLeakGuardModel(model: String): String {
        return model.trim().lowercase()
            .substringAfterLast('/')
            .substringAfterLast(':')
    }

    private fun sanitizeRequestForTarget(request: ChatCompletionRequest): ChatCompletionRequest {
        if (shouldPreserveAllAssistantReasoning()) {
            return request
        }
        val sanitizedMessages = request.messages.mapIndexed { index, message ->
            if (
                message.role != "assistant" ||
                message.reasoningContent.isNullOrBlank() ||
                shouldRetainAssistantReasoning(index, request.messages)
            ) {
                message
            } else {
                message.copy(reasoningContent = null)
            }
        }
        return if (sanitizedMessages == request.messages) {
            request
        } else {
            request.copy(messages = sanitizedMessages)
        }
    }

    private fun shouldPreserveAllAssistantReasoning(): Boolean {
        if (isOfficialDeepSeekTarget()) {
            return true
        }
        return when (resolvedProtocolType()) {
            DeepSeekProvider.PROTOCOL_TYPE, "anthropic" -> true
            else -> false
        }
    }

    private fun shouldRetainAssistantReasoning(
        assistantIndex: Int,
        messages: List<ChatCompletionMessage>
    ): Boolean {
        val message = messages.getOrNull(assistantIndex) ?: return false
        if (message.toolCalls?.isNotEmpty() == true) {
            return true
        }
        for (index in assistantIndex + 1 until messages.size) {
            when (messages[index].role) {
                "tool" -> return true
                "user" -> return false
            }
        }
        return false
    }

    private fun isOfficialDeepSeekTarget(): Boolean {
        if (modelOverride != null) {
            return DeepSeekProvider.shouldUseOfficialAdapter(
                protocolType = modelOverride.protocolType,
                apiBase = modelOverride.apiBase
            )
        }
        val profile = runCatching { ModelProviderConfigStore.getEditingProfile() }
            .getOrNull()
        return DeepSeekProvider.shouldUseOfficialAdapter(
            protocolType = profile?.protocolType,
            apiBase = profile?.baseUrl
        )
    }

    private fun resolvedProtocolType(): String {
        modelOverride?.protocolType
            ?.let(DeepSeekProvider::normalizeProtocolType)
            ?.let { return it }
        return runCatching { ModelProviderConfigStore.getEditingProfile().protocolType }
            .map(DeepSeekProvider::normalizeProtocolType)
            .getOrDefault(DeepSeekProvider.normalizeProtocolType(null))
    }

    private fun toLegacyFunctionCall(toolChoice: JsonElement?): JsonElement? {
        if (toolChoice == null) return null
        return when (toolChoice) {
            is JsonPrimitive -> {
                val raw = toolChoice.contentOrNull?.trim().orEmpty()
                when {
                    raw.isEmpty() || raw.equals("none", ignoreCase = true) -> null
                    raw.equals("required", ignoreCase = true) -> JsonPrimitive("auto")
                    else -> JsonPrimitive(raw)
                }
            }

            is JsonObject -> {
                val functionName =
                    extractJsonText((toolChoice["function"] as? JsonObject)?.get("name"))
                if (functionName.isNullOrBlank()) {
                    JsonPrimitive("auto")
                } else {
                    JsonObject(mapOf("name" to JsonPrimitive(functionName)))
                }
            }

            else -> JsonPrimitive("auto")
        }
    }

    private fun extractResponseBody(response: Response?): String? {
        val body = runCatching { response?.body?.string() }.getOrNull()?.trim().orEmpty()
        return body.takeIf { it.isNotEmpty() }?.take(4000)
    }

    private fun extractErrorReason(responseBody: String?): String? {
        val raw = responseBody?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return sanitizeReason(raw)
        val errorObj = parsed["error"] as? JsonObject
        val formalErrorCode = extractJsonText(errorObj?.get("code"))
            ?: extractJsonText(parsed["code"])
        PlatformMediaProtocol.stableUserMessageForErrorCode(formalErrorCode)?.let { return it }

        val candidates = listOf(
            extractJsonText(errorObj?.get("message")),
            extractJsonText(errorObj?.get("detail")),
            extractJsonText(parsed["message"]),
            extractJsonText(parsed["detail"]),
            extractJsonText(parsed["error_description"]),
            extractJsonText(parsed["error"])
        )
        return candidates.firstOrNull { !it.isNullOrBlank() } ?: sanitizeReason(raw)
    }

    private fun extractJsonText(element: JsonElement?): String? {
        return when (element) {
            null -> null
            is JsonPrimitive -> element.contentOrNull
            is JsonObject -> {
                extractJsonText(element["message"])
                    ?: extractJsonText(element["detail"])
                    ?: extractJsonText(element["code"])
            }

            else -> sanitizeReason(element.toString())
        }
    }

    private fun sanitizeReason(raw: String?, maxLen: Int = 240): String? {
        val normalized = raw?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        return if (normalized.length <= maxLen) normalized else "${normalized.take(maxLen)}..."
    }

    private fun buildModelCandidates(baseModel: String): List<String> {
        val normalized = baseModel.trim().ifEmpty { baseModel }
        val candidates = linkedSetOf(normalized)
        if (normalized.startsWith("scene.")) {
            candidates.add("scene.dispatch.model")
        }
        return candidates.toList()
    }

    private fun ChatCompletionRequest.hasImageInput(): Boolean =
        messages.any { message -> message.content.containsImageInput() }

    private fun JsonElement?.containsImageInput(): Boolean {
        return when (this) {
            is JsonArray -> any { element -> element.containsImageInput() }
            is JsonObject -> {
                val type = (get("type") as? JsonPrimitive)
                    ?.contentOrNull
                    ?.trim()
                    ?.lowercase()
                type == "image_url" ||
                    type == "input_image" ||
                    type == "image" ||
                    containsKey("image_url") ||
                    containsKey("imageUrl") ||
                    containsKey("input_image") ||
                    containsKey("inputImage") ||
                    values.any { element -> element.containsImageInput() }
            }
            else -> false
        }
    }

    private fun isModelNotSupported(error: AgentStreamRequestException): Boolean {
        val code = error.statusCode
        if (code != 400 && code != 404) return false
        val haystack = buildString {
            append(error.reason)
            append(' ')
            append(error.responseBody.orEmpty())
        }.lowercase()
        if (!haystack.contains("model")) return false
        return haystack.contains("not supported") ||
            haystack.contains("unsupported model") ||
            haystack.contains("model_not_supported") ||
            haystack.contains("invalid model") ||
            haystack.contains("unknown model") ||
            haystack.contains("model does not exist") ||
            haystack.contains("no such model") ||
            haystack.contains("not found")
    }
}
