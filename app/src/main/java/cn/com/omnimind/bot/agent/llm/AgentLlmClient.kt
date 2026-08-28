package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.account.AiRequestTransportPolicy
import cn.com.omnimind.baselib.account.OmniAccount
import cn.com.omnimind.baselib.account.PlatformModelsUnavailableException
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionThinking
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.ChatCompletionUsage
import cn.com.omnimind.baselib.llm.DeepSeekProvider
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.baselib.llm.OpenAiResponsesFunctionNameCodec
import cn.com.omnimind.baselib.llm.OmniOfficialProvider
import cn.com.omnimind.baselib.llm.PlatformAiProvisioner
import cn.com.omnimind.baselib.llm.ReasoningStreamUpdatePolicy
import cn.com.omnimind.baselib.llm.contentText
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    modelOverride: AgentModelOverride? = null,
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
    private val maxTransientStreamRetries: Int = 2,
    private val transientStreamRetryDelayMs: Long = 750L,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }
) : AgentLlmClient {
    private val modelOverride: AgentModelOverride? = modelOverride?.normalized()
    private val tag = "HttpAgentLlmClient"

    private companion object {
        const val REASONING_UPDATE_INTERVAL_MS =
            ReasoningStreamUpdatePolicy.DEFAULT_INTERVAL_MS
        const val DEFAULT_CLOSED_STREAM_ERROR =
            "chat completion stream closed before completion signal"
        val TRANSIENT_STREAM_FAILURE_MARKERS = listOf(
            "software caused connection abort",
            "unable to resolve host",
            "connection reset",
            "connection refused",
            "failed to connect",
            "network is unreachable",
            "unexpected end of stream",
            "socket closed",
            "timeout",
            "timed out",
        )
        // The platform gateway reserves quota from the whole prompt plus the
        // requested output ceiling. Reusing full agent history, every tool schema,
        // and the 16K ceiling can reserve several times a user's weekly allowance
        // before the vision model is called. A vision turn only needs the current
        // image question; subsequent text turns still use the normal agent context.
        const val PLATFORM_VISION_MAX_COMPLETION_TOKENS = 1_024
    }

    internal data class StreamRequestVariant(
        val name: String,
        val request: ChatCompletionRequest
    )

    override suspend fun streamTurn(
        request: ChatCompletionRequest,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?
    ): ChatCompletionTurn {
        val usesOfficialProvider =
            OmniOfficialProvider.isOfficialProfile(modelOverride?.providerProfileId) ||
                (request.hasImageInput() && requestUsesOfficialProvider(request))
        val platformVisionModel = if (request.hasImageInput() && usesOfficialProvider) {
            resolvePlatformVisionModelOp()?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        if (platformVisionModel == null) {
            return streamRoutedTurn(
                request = request,
                effectiveExplicitModel = modelOverride?.modelId,
                onReasoningUpdate = onReasoningUpdate,
                onContentUpdate = onContentUpdate,
            )
        }

        // Platform vision is a bounded preprocessing turn. Feed its description
        // back into the normal Agent turn so system/history, tools and the stable
        // prompt cache key remain available for the actual user request.
        val visionTurn = streamRoutedTurn(
            request = request.forPlatformVision(platformVisionModel),
            effectiveExplicitModel = platformVisionModel,
            onReasoningUpdate = null,
            onContentUpdate = null,
        )
        val description = visionTurn.message.contentText().trim()
        check(description.isNotEmpty()) { "官方图片理解模型未返回可用内容" }
        return streamRoutedTurn(
            request = request.withPlatformVisionDescription(description),
            effectiveExplicitModel = modelOverride?.modelId,
            onReasoningUpdate = onReasoningUpdate,
            onContentUpdate = onContentUpdate,
        )
    }

    private fun requestUsesOfficialProvider(request: ChatCompletionRequest): Boolean {
        if (OmniOfficialProvider.isOfficialProfile(modelOverride?.providerProfileId)) {
            return true
        }
        if (modelOverride != null) {
            return false
        }
        val routeInfo = resolveRouteInfoOp(
            request.model,
            null,
            null,
            null,
            null,
            null,
            null,
        )
        return AiRequestTransportPolicy.isPlatformRoute(routeInfo.routeTag)
    }

    private suspend fun streamRoutedTurn(
        request: ChatCompletionRequest,
        effectiveExplicitModel: String?,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?,
    ): ChatCompletionTurn {
        val modelCandidates = buildModelCandidates(request.model)
        val sanitizedRequest = sanitizeRequestForTarget(request)
        var lastFailure: AgentStreamRequestException? = null

        for (modelIndex in modelCandidates.indices) {
            val candidateModel = modelCandidates[modelIndex]
            val routeInfo = resolveRouteInfoOp(
                candidateModel,
                modelOverride?.apiBase,
                modelOverride?.apiKey,
                modelOverride?.customHeaders,
                effectiveExplicitModel,
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
                    val responsesNamePlan = if (OpenAiWireApi.isResponses(routeInfo.wireApi)) {
                        OpenAiResponsesFunctionNameCodec.planFor(variant.request)
                    } else {
                        null
                    }
                    val wireRequest = responsesNamePlan?.encodeRequest(variant.request)
                        ?: variant.request
                    val requestJson = json.encodeToString(wireRequest)
                    if (AiRequestTransportPolicy.isPlatformRoute(routeInfo.routeTag)) {
                        PlatformMediaProtocol.requirePlatformJsonRequestWithinLimit(requestJson)
                    }
                    val turn = streamTurnWithPlatformAuthRetry(
                        model = candidateModel,
                        requestJson = requestJson,
                        explicitModel = effectiveExplicitModel,
                        platformRoute = AiRequestTransportPolicy.isPlatformRoute(routeInfo.routeTag),
                        onReasoningUpdate = onReasoningUpdate,
                        onContentUpdate = onContentUpdate
                    )
                    return responsesNamePlan?.restoreTurn(turn) ?: turn
                } catch (error: AgentStreamRequestException) {
                    lastFailure = error
                    if (isBadRequest(error) && isThinkingDisableRejected(error)) {
                        val thinkingCompatible = withThinkingEnabled(variant.request)
                        if (thinkingCompatible != variant.request) {
                            OmniLog.w(tag, "provider rejected disabled thinking; retrying with low effort")
                            val fallbackPlan = if (OpenAiWireApi.isResponses(routeInfo.wireApi)) {
                                OpenAiResponsesFunctionNameCodec.planFor(thinkingCompatible)
                            } else {
                                null
                            }
                            val fallbackWireRequest =
                                fallbackPlan?.encodeRequest(thinkingCompatible) ?: thinkingCompatible
                            val turn = streamTurnWithPlatformAuthRetry(
                                model = candidateModel,
                                requestJson = json.encodeToString(fallbackWireRequest),
                                explicitModel = effectiveExplicitModel,
                                platformRoute = AiRequestTransportPolicy.isPlatformRoute(routeInfo.routeTag),
                                onReasoningUpdate = onReasoningUpdate,
                                onContentUpdate = onContentUpdate,
                            )
                            return fallbackPlan?.restoreTurn(turn) ?: turn
                        }
                    }
                    if (isBadRequest(error) && isTextOnlyContentRejected(error)) {
                        val textOnly = withoutUnsupportedImageBlocks(variant.request)
                        if (textOnly != variant.request) {
                            OmniLog.w(tag, "provider rejected image content; retrying text-only request")
                            val fallbackPlan = if (OpenAiWireApi.isResponses(routeInfo.wireApi)) {
                                OpenAiResponsesFunctionNameCodec.planFor(textOnly)
                            } else {
                                null
                            }
                            val fallbackWireRequest = fallbackPlan?.encodeRequest(textOnly) ?: textOnly
                            try {
                                val turn = streamTurnWithPlatformAuthRetry(
                                    model = candidateModel,
                                    requestJson = json.encodeToString(fallbackWireRequest),
                                    explicitModel = effectiveExplicitModel,
                                    platformRoute = AiRequestTransportPolicy.isPlatformRoute(routeInfo.routeTag),
                                    onReasoningUpdate = onReasoningUpdate,
                                    onContentUpdate = onContentUpdate,
                                )
                                return fallbackPlan?.restoreTurn(turn) ?: turn
                            } catch (fallbackError: AgentStreamRequestException) {
                                if (!isBadRequest(fallbackError) || !isThinkingDisableRejected(fallbackError)) {
                                    throw fallbackError
                                }
                                val thinkingCompatible = withThinkingEnabled(textOnly)
                                val thinkingPlan = if (OpenAiWireApi.isResponses(routeInfo.wireApi)) {
                                    OpenAiResponsesFunctionNameCodec.planFor(thinkingCompatible)
                                } else null
                                val thinkingWireRequest = thinkingPlan?.encodeRequest(thinkingCompatible) ?: thinkingCompatible
                                OmniLog.w(tag, "provider rejected disabled thinking after media fallback; retrying with low effort")
                                val turn = streamTurnWithPlatformAuthRetry(
                                    model = candidateModel,
                                    requestJson = json.encodeToString(thinkingWireRequest),
                                    explicitModel = effectiveExplicitModel,
                                    platformRoute = AiRequestTransportPolicy.isPlatformRoute(routeInfo.routeTag),
                                    onReasoningUpdate = onReasoningUpdate,
                                    onContentUpdate = onContentUpdate,
                                )
                                return thinkingPlan?.restoreTurn(turn) ?: turn
                            }
                        }
                    }
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
        explicitModel: String?,
        platformRoute: Boolean,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?,
    ): ChatCompletionTurn {
        return try {
            streamTurnOnce(model, requestJson, explicitModel, onReasoningUpdate, onContentUpdate)
        } catch (error: AgentStreamRequestException) {
            if (error.statusCode != 401 || !platformRoute || !refreshPlatformSessionOp()) {
                throw error
            }
            OmniLog.i(tag, "platform access token refreshed after 401; retrying once")
            streamTurnOnce(model, requestJson, explicitModel, onReasoningUpdate, onContentUpdate)
        }
    }

    private suspend fun streamTurnOnce(
        model: String,
        requestJson: String,
        explicitModel: String?,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?
    ): ChatCompletionTurn {
        val retryCount = maxTransientStreamRetries.coerceAtLeast(0)
        var retriedIncompleteToolCall = false
        repeat(retryCount + 1) { attempt ->
            try {
                return try {
                    doStreamTurnOnce(
                        model,
                        requestJson,
                        explicitModel,
                        onReasoningUpdate,
                        onContentUpdate,
                        forceHttp1 = false,
                    )
                } catch (error: AgentStreamRequestException) {
                    if (isHttp2ProtocolError(error)) {
                        OmniLog.w(tag, "HTTP/2 stream PROTOCOL_ERROR, retrying with HTTP/1.1")
                        doStreamTurnOnce(
                            model,
                            requestJson,
                            explicitModel,
                            onReasoningUpdate,
                            onContentUpdate,
                            forceHttp1 = true,
                        )
                    } else {
                        throw error
                    }
                }
            } catch (error: AgentStreamRequestException) {
                if (attempt >= retryCount || !isTransientStreamFailure(error)) throw error
                val delayMs = transientStreamRetryDelayMs.coerceAtLeast(0L) * (attempt + 1L)
                OmniLog.w(
                    tag,
                    "transient stream failure, retrying attempt=${attempt + 1}/$retryCount " +
                        "delayMs=$delayMs reason=${error.reason}",
                )
                if (delayMs > 0L) delay(delayMs)
            } catch (error: AgentIncompleteToolCallException) {
                if (retriedIncompleteToolCall || attempt >= retryCount) throw error
                retriedIncompleteToolCall = true
                OmniLog.w(
                    tag,
                    "incomplete streamed tool call index=${error.toolCallIndex}; " +
                        "retrying the same model turn once",
                )
            }
        }
        error("unreachable transient stream retry state")
    }

    private fun isHttp2ProtocolError(error: AgentStreamRequestException): Boolean {
        return error.reason.contains("PROTOCOL_ERROR", ignoreCase = true)
                || error.reason.contains("stream was reset", ignoreCase = true)
    }

    private fun isTransientStreamFailure(error: AgentStreamRequestException): Boolean {
        val status = error.statusCode
        if (status == 408 || status == 425 || status == 429 || status != null && status >= 500) {
            return true
        }
        if (status != null) return false
        val reason = error.reason.lowercase()
        return TRANSIENT_STREAM_FAILURE_MARKERS.any(reason::contains)
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
        explicitModel: String?,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?,
        forceHttp1: Boolean
    ): ChatCompletionTurn {
        val streamDone = CompletableDeferred<ChatCompletionTurn>()
        val completed = AtomicBoolean(false)
        val startedAtMs = System.currentTimeMillis()
        var firstEventLogged = false
        var firstReasoningLogged = false
        var firstContentLogged = false
        val routeInfo = resolveRouteInfoOp(
            model,
            modelOverride?.apiBase,
            modelOverride?.apiKey,
            modelOverride?.customHeaders,
            explicitModel,
            modelOverride?.protocolType,
            modelOverride?.wireApi
        )
        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            includeReasoningInAssistantMessage = routeInfo.requiresReasoningEcho,
            bufferLeadingTextUntilInlineThinkTag = shouldBufferLeadingInlineThinkTag(routeInfo),
            guardLeadingReasoningLeak = shouldGuardNvidiaKimiReasoningLeak(routeInfo),
            captureAnthropicContentBlocks = routeInfo.requiresAnthropicThinkingReplay,
            anthropicSourceModel = routeInfo.resolvedModel
        )
        var lastReasoning = ""
        var lastReasoningEmitLength = 0
        var lastReasoningEmitAt = 0L
        var reasoningEmitJob: Job? = null
        val reasoningLock = Any()
        var lastContent = ""
        var eventSource: EventSource? = null
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
            runCatching {
                val turn = accumulator.buildTurn().copy(
                    resolvedModel = routeInfo.resolvedModel,
                )
                enforceReasoningEchoIfRequired(turn, routeInfo)
                emitReasoning(force = true)
                emitContent()
                turn
            }.onSuccess { turn ->
                OmniLog.i(
                    tag,
                    "ACP provider timing stage=stream_completed " +
                        "elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                        "protocol=${routeInfo.protocolType}"
                )
                streamDone.complete(turn)
            }.onFailure { error ->
                streamDone.completeExceptionally(error)
            }
            eventSource?.cancel()
        }

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                OmniLog.i(
                    tag,
                    "ACP provider timing stage=stream_open " +
                        "elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                        "protocol=${routeInfo.protocolType}"
                )
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (completed.get()) return
                runCatching {
                    if (!firstEventLogged) {
                        firstEventLogged = true
                        OmniLog.i(
                            tag,
                            "ACP provider timing stage=first_event " +
                                "elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                                "protocol=${routeInfo.protocolType}"
                        )
                    }
                    val done = accumulator.consume(data)
                    if (!firstReasoningLogged && accumulator.currentReasoningLength() > 0) {
                        firstReasoningLogged = true
                        OmniLog.i(
                            tag,
                            "ACP provider timing stage=first_reasoning " +
                                "elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                                "protocol=${routeInfo.protocolType}"
                        )
                    }
                    if (!firstContentLogged && accumulator.currentContent().isNotEmpty()) {
                        firstContentLogged = true
                        OmniLog.i(
                            tag,
                            "ACP provider timing stage=first_content " +
                                "elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                                "protocol=${routeInfo.protocolType}"
                        )
                    }
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
                    return
                }
                if (accumulator.canFinalizeOnClosed()) {
                    completeStream()
                    return
                }
                if (completed.compareAndSet(false, true)) {
                    streamDone.completeExceptionally(
                        IllegalStateException(DEFAULT_CLOSED_STREAM_ERROR)
                    )
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (!completed.compareAndSet(false, true)) return
                OmniLog.w(
                    tag,
                    "ACP provider timing stage=stream_failed " +
                        "elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                        "status=${response?.code ?: "none"} " +
                        "protocol=${routeInfo.protocolType}"
                )
                val responseBody = extractRawResponseBody(response)
                parseSuccessfulNonStreamingResponsesTurn(
                    statusCode = response?.code,
                    responseBody = responseBody,
                    routeInfo = routeInfo,
                )?.let { turn ->
                    streamDone.complete(turn)
                    return
                }
                val reason = extractErrorReason(responseBody)
                    ?: sanitizeReason(t?.message)
                    ?: "unknown stream failure"
                streamDone.completeExceptionally(
                    AgentStreamRequestException(
                        statusCode = response?.code,
                        reason = reason,
                        responseBody = responseBody?.take(4000)
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
                explicitModel,
                modelOverride?.protocolType,
                modelOverride?.wireApi,
                forceHttp1
            )
            OmniLog.i(
                tag,
                "ACP provider timing stage=request_dispatched " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                    "protocol=${routeInfo.protocolType}"
            )
            return streamDone.await()
        } finally {
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

    internal fun buildRequestVariants(
        request: ChatCompletionRequest,
        routeInfo: HttpController.ChatCompletionRouteInfo
    ): List<StreamRequestVariant> {
        val normalizedRequest = request
        val requiresNativeToolCalls = normalizedRequest.tools.isNotEmpty() &&
            normalizedRequest.parallelToolCalls == false &&
            (normalizedRequest.toolChoice as? JsonPrimitive)
                ?.contentOrNull
                ?.equals("required", ignoreCase = true) == true
        val compatibleRequest = normalizedRequest
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
            val noThinkingRequest = compatibleRequest.copy(
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

        add("default", compatibleRequest)
        add(
            "no_stream_options",
            compatibleRequest.copy(streamOptions = null)
        )
        if (requiresNativeToolCalls) {
            return variants
        }

        val legacyFunctions = compatibleRequest.tools.map { it.function }
        val legacyRequest = legacyFunctions
            .takeIf {
                it.isNotEmpty() &&
                    !routeInfo.wireApi.equals("responses", ignoreCase = true)
            }
            ?.let {
                compatibleRequest.copy(
                    streamOptions = null,
                    parallelToolCalls = null,
                    toolChoice = null,
                    tools = emptyList(),
                    functions = it,
                    functionCall = toLegacyFunctionCall(request.toolChoice)
                )
            }
        add(
            "minimal",
            compatibleRequest.copy(
                streamOptions = null,
                parallelToolCalls = null,
                toolChoice = null
            )
        )

        if (legacyRequest != null) {
            add("legacy_functions", legacyRequest)
        }
        request.promptCacheKey?.let {
            add(
                "no_prompt_cache_key",
                compatibleRequest.copy(
                    streamOptions = null,
                    parallelToolCalls = null,
                    toolChoice = null,
                    promptCacheKey = null
                )
            )
        }
        return variants
    }

    private fun isTextOnlyContentRejected(error: AgentStreamRequestException): Boolean {
        val text = (error.reason + " " + error.responseBody.orEmpty()).lowercase()
        return text.contains("type") && text.contains("text") &&
            (text.contains("only") || text.contains("范围") || text.contains("['text']"))
    }

    private fun isBadRequest(error: AgentStreamRequestException): Boolean {
        if (error.statusCode == 400) return true
        val text = (error.reason + " " + error.responseBody.orEmpty()).lowercase()
        return text.contains("status_code=400") || text.contains("status code: 400") ||
            text.contains("bad request")
    }

    private fun isThinkingDisableRejected(error: AgentStreamRequestException): Boolean {
        val text = (error.reason + " " + error.responseBody.orEmpty()).lowercase()
        return (text.contains("思考") || text.contains("thinking") || text.contains("reasoning")) &&
            (text.contains("不支持关闭") || text.contains("不支持禁用") ||
                text.contains("always thinks") || text.contains("cannot disable") ||
                text.contains("must use low") || text.contains("请使用 low"))
    }

    private fun withThinkingEnabled(request: ChatCompletionRequest): ChatCompletionRequest {
        if (request.enableThinking != false && request.thinking == null &&
            !request.reasoningEffort.equals("none", ignoreCase = true)) {
            return request
        }
        return request.copy(
            enableThinking = null,
            thinking = null,
            reasoningEffort = "low",
        )
    }

    private fun withoutUnsupportedImageBlocks(request: ChatCompletionRequest): ChatCompletionRequest {
        var changed = false
        val messages = request.messages.map { message ->
            val blocks = message.content as? JsonArray ?: return@map message
            val kept = blocks.filterNot { item ->
                val block = item as? JsonObject ?: return@filterNot false
                val type = block["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
                val image = type in setOf("image_url", "input_image", "image") ||
                    block.containsKey("image_url") || block.containsKey("input_image")
                if (image) changed = true
                image
            }
            if (kept.isEmpty()) {
                message.copy(content = JsonArray(listOf(JsonObject(mapOf(
                    "type" to JsonPrimitive("text"),
                    "text" to JsonPrimitive("请依据当前界面继续。"),
                )))))
            } else if (kept.size != blocks.size) {
                message.copy(content = JsonArray(kept))
            } else message
        }
        return if (changed) request.copy(messages = messages) else request
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

    private fun extractRawResponseBody(response: Response?): String? {
        val body = runCatching { response?.body?.string() }.getOrNull()?.trim().orEmpty()
        return body.takeIf { it.isNotEmpty() }
    }

    private fun parseSuccessfulNonStreamingResponsesTurn(
        statusCode: Int?,
        responseBody: String?,
        routeInfo: HttpController.ChatCompletionRouteInfo,
    ): ChatCompletionTurn? {
        if (
            statusCode == null ||
            statusCode !in 200..299 ||
            !OpenAiWireApi.isResponses(routeInfo.wireApi)
        ) return null
        val parsed = HttpController.parseOpenAiResponsesBody(responseBody)
        if (!parsed.success) return null
        val content = parsed.content.takeIf(String::isNotBlank)?.let(::JsonPrimitive)
        val turn = ChatCompletionTurn(
            message = ChatCompletionMessage(
                role = "assistant",
                content = content,
                toolCalls = parsed.toolCalls.takeIf { it.isNotEmpty() },
            ),
            reasoning = parsed.reasoning,
            finishReason = parsed.finishReason,
            usage = parseResponsesUsage(responseBody),
            resolvedModel = routeInfo.resolvedModel,
        )
        enforceReasoningEchoIfRequired(turn, routeInfo)
        return turn
    }

    private fun parseResponsesUsage(responseBody: String?): ChatCompletionUsage? {
        val usage = runCatching {
            json.parseToJsonElement(responseBody.orEmpty()).jsonObject["usage"]?.jsonObject
        }.getOrNull() ?: return null
        return ChatCompletionUsage(
            promptTokens = usage.tokenCount("input_tokens", "prompt_tokens"),
            completionTokens = usage.tokenCount("output_tokens", "completion_tokens"),
            totalTokens = usage["total_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        )
    }

    private fun JsonObject.tokenCount(vararg names: String): Int? = names.firstNotNullOfOrNull {
        get(it)?.jsonPrimitive?.contentOrNull?.toIntOrNull()
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

    private fun ChatCompletionRequest.forPlatformVision(model: String): ChatCompletionRequest {
        val normalizedReasoning = reasoningEffort?.trim()?.lowercase()
        val compatibleEnableThinking = when {
            enableThinking != null -> enableThinking
            normalizedReasoning == null -> null
            normalizedReasoning in setOf("no", "none", "disabled") -> false
            else -> true
        }
        val currentImageMessage = messages.lastOrNull { message ->
            message.content.containsImageInput()
        }
        return copy(
            messages = currentImageMessage?.let(::listOf) ?: messages,
            model = model,
            maxCompletionTokens = maxCompletionTokens?.coerceAtMost(
                PLATFORM_VISION_MAX_COMPLETION_TOKENS
            ),
            maxTokens = maxTokens?.coerceAtMost(PLATFORM_VISION_MAX_COMPLETION_TOKENS),
            tools = emptyList(),
            toolChoice = null,
            parallelToolCalls = null,
            functions = null,
            functionCall = null,
            promptCacheKey = null,
            reasoningEffort = null,
            thinking = null,
            enableThinking = compatibleEnableThinking,
        )
    }

    private fun ChatCompletionRequest.withPlatformVisionDescription(
        description: String,
    ): ChatCompletionRequest {
        val imageMessageIndex = messages.indexOfLast { message ->
            message.content.containsImageInput()
        }
        if (imageMessageIndex < 0) return this
        val nextMessages = messages.mapIndexed { index, message ->
            if (!message.content.containsImageInput()) {
                return@mapIndexed message
            }
            val originalText = message.content.textInputForVisionFollowUp().trim()
            val replacement = buildString {
                if (originalText.isNotEmpty()) {
                    append(originalText)
                    append("\n\n")
                }
                if (index == imageMessageIndex) {
                    if (originalText.isEmpty()) {
                        append("请根据以下图片识别结果继续完成用户请求。\n\n")
                    }
                    append("[图片识别结果]\n")
                    append(description)
                } else {
                    append("[历史图片内容已省略；请结合后续对话中的已有分析。]")
                }
            }
            message.copy(content = JsonPrimitive(replacement))
        }
        return copy(messages = nextMessages)
    }

    private fun JsonElement?.textInputForVisionFollowUp(): String {
        return when (this) {
            is JsonPrimitive -> contentOrNull.orEmpty()
            is JsonArray -> mapNotNull { element ->
                when (element) {
                    is JsonPrimitive -> element.contentOrNull
                    is JsonObject -> {
                        val type = (element["type"] as? JsonPrimitive)
                            ?.contentOrNull
                            ?.trim()
                            ?.lowercase()
                        if (type == "text" || type == "input_text") {
                            (element["text"] as? JsonPrimitive)?.contentOrNull
                        } else {
                            null
                        }
                    }
                    else -> null
                }
            }.joinToString("\n")
            is JsonObject -> (get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
            else -> ""
        }
    }

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
