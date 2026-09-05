package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.account.AiRequestTransportPolicy
import cn.com.omnimind.baselib.account.OmniAccount
import cn.com.omnimind.baselib.account.PlatformModelsUnavailableException
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.ChatCompletionUsage
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

interface AgentLlmClient {
    suspend fun streamTurn(
        request: ChatCompletionRequest,
        onReasoningUpdate: (suspend (String) -> Unit)? = null,
        onContentUpdate: (suspend (String) -> Unit)? = null,
        onToolCallInput: (suspend (AssistantToolCall) -> Unit)? = null,
    ): ChatCompletionTurn
}

class AgentStreamRequestException(
    val statusCode: Int?,
    val reason: String,
    val responseBody: String?,
    val responseStarted: Boolean = false,
    cause: Throwable? = null,
) : RuntimeException(
    "chat completion stream request failed${
        statusCode?.let { "($it)" }.orEmpty()
    }: $reason", cause
)

class AgentStreamIdleTimeoutException(
    val timeoutMillis: Long,
) : RuntimeException("chat completion stream idle timeout after ${timeoutMillis}ms")

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
    // This is the single transport retry owner. A retry is safe only before
    // visible output exists; replaying a started stream duplicates reasoning,
    // text, and potentially tool intent.
    private val maxTransientStreamRetries: Int? = null,
    private val transientStreamRetryDelayMs: Long? = null,
    private val streamIdleTimeoutMs: Long? = null,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    },
) : AgentLlmClient {
    private val effectiveMaxTransientStreamRetries: Int?
        get() = maxTransientStreamRetries
    private val effectiveTransientStreamRetryDelayMs: Long?
        get() = transientStreamRetryDelayMs
    private val effectiveStreamIdleTimeoutMs: Long?
        get() = streamIdleTimeoutMs
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
    }


    override suspend fun streamTurn(
        request: ChatCompletionRequest,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?,
        onToolCallInput: (suspend (AssistantToolCall) -> Unit)?,
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
                onToolCallInput = onToolCallInput,
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
            onToolCallInput = null,
        )
        val description = visionTurn.message.contentText().trim()
        check(description.isNotEmpty()) { "官方图片理解模型未返回可用内容" }
        return streamRoutedTurn(
            request = request.withPlatformVisionDescription(description),
            effectiveExplicitModel = modelOverride?.modelId,
            onReasoningUpdate = onReasoningUpdate,
            onContentUpdate = onContentUpdate,
            onToolCallInput = onToolCallInput,
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
        onToolCallInput: (suspend (AssistantToolCall) -> Unit)?,
    ): ChatCompletionTurn {
        val routeInfo = resolveRouteInfoOp(
            request.model,
            modelOverride?.apiBase,
            modelOverride?.apiKey,
            modelOverride?.customHeaders,
            effectiveExplicitModel,
            modelOverride?.protocolType,
            modelOverride?.wireApi,
        )
        val namePlan = if (OpenAiWireApi.isResponses(routeInfo.wireApi)) {
            OpenAiResponsesFunctionNameCodec.planFor(request)
        } else null
        val wireRequest = namePlan?.encodeRequest(request) ?: request
        val turn = streamTurnWithPlatformAuthRetry(
            model = request.model,
            requestJson = json.encodeToString(wireRequest),
            explicitModel = effectiveExplicitModel,
            platformRoute = AiRequestTransportPolicy.isPlatformRoute(routeInfo.routeTag),
            onReasoningUpdate = onReasoningUpdate,
            onContentUpdate = onContentUpdate,
            onToolCallInput = { call ->
                onToolCallInput?.invoke(call.copy(function = call.function.copy(
                    name = namePlan?.restore(call.function.name) ?: call.function.name,
                )))
            },
        )
        return namePlan?.restoreTurn(turn) ?: turn
    }

    private suspend fun streamTurnWithPlatformAuthRetry(
        model: String,
        requestJson: String,
        explicitModel: String?,
        platformRoute: Boolean,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?,
        onToolCallInput: (suspend (AssistantToolCall) -> Unit)?,
    ): ChatCompletionTurn {
        var emittedOutput = false
        suspend fun forward(
            callback: (suspend (String) -> Unit)?,
            value: String,
        ) {
            if (value.isNotBlank()) emittedOutput = true
            callback?.invoke(value)
        }
        return try {
            streamTurnOnce(
                model,
                requestJson,
                explicitModel,
                onReasoningUpdate = { value -> forward(onReasoningUpdate, value) },
                onContentUpdate = { value -> forward(onContentUpdate, value) },
                onToolCallInput = { call -> emittedOutput = true; onToolCallInput?.invoke(call) },
            )
        } catch (error: AgentStreamRequestException) {
            if (
                error.statusCode != 401 ||
                emittedOutput ||
                error.responseStarted ||
                !platformRoute ||
                !refreshPlatformSessionOp()
            ) {
                throw error
            }
            OmniLog.i(tag, "platform access token refreshed after 401; retrying once")
            streamTurnOnce(
                model,
                requestJson,
                explicitModel,
                onReasoningUpdate = { value -> forward(onReasoningUpdate, value) },
                onContentUpdate = { value -> forward(onContentUpdate, value) },
                onToolCallInput = { call -> emittedOutput = true; onToolCallInput?.invoke(call) },
            )
        }
    }

    private suspend fun streamTurnOnce(
        model: String,
        requestJson: String,
        explicitModel: String?,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?,
        onToolCallInput: (suspend (AssistantToolCall) -> Unit)?,
    ): ChatCompletionTurn {
        val retryCount = effectiveMaxTransientStreamRetries?.coerceAtLeast(0) ?: 0
        var retriedIncompleteToolCall = false
        repeat(retryCount + 1) { attempt ->
            var attemptProducedOutput = false
            suspend fun forward(
                callback: (suspend (String) -> Unit)?,
                value: String,
            ) {
                if (value.isNotBlank()) attemptProducedOutput = true
                callback?.invoke(value)
            }
            try {
                return try {
                    doStreamTurnOnce(
                        model,
                        requestJson,
                        explicitModel,
                        onReasoningUpdate = { value -> forward(onReasoningUpdate, value) },
                        onContentUpdate = { value -> forward(onContentUpdate, value) },
                        onToolCallInput = { call -> attemptProducedOutput = true; onToolCallInput?.invoke(call) },
                        forceHttp1 = false,
                    )
                } catch (error: AgentStreamRequestException) {
                    if (isHttp2ProtocolError(error) && !attemptProducedOutput && !error.responseStarted) {
                        OmniLog.w(tag, "HTTP/2 stream PROTOCOL_ERROR, retrying with HTTP/1.1")
                        doStreamTurnOnce(
                            model,
                            requestJson,
                            explicitModel,
                            onReasoningUpdate = { value -> forward(onReasoningUpdate, value) },
                            onContentUpdate = { value -> forward(onContentUpdate, value) },
                            onToolCallInput = { call -> attemptProducedOutput = true; onToolCallInput?.invoke(call) },
                            forceHttp1 = true,
                        )
                    } else {
                        throw error
                    }
                }
            } catch (error: AgentStreamRequestException) {
                if (
                    attemptProducedOutput || error.responseStarted ||
                    attempt >= retryCount ||
                    !isTransientStreamFailure(error)
                ) throw error
                val delayMs = (effectiveTransientStreamRetryDelayMs ?: 0L)
                    .coerceAtLeast(0L) * (attempt + 1L)
                OmniLog.w(
                    tag,
                    "transient stream failure, retrying attempt=${attempt + 1}/$retryCount " +
                        "delayMs=$delayMs reason=${error.reason}",
                )
                if (delayMs > 0L) delay(delayMs)
            } catch (error: AgentIncompleteToolCallException) {
                if (
                    attemptProducedOutput ||
                    retriedIncompleteToolCall ||
                    attempt >= retryCount
                ) throw error
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

    private suspend fun doStreamTurnOnce(
        model: String,
        requestJson: String,
        explicitModel: String?,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?,
        onToolCallInput: (suspend (AssistantToolCall) -> Unit)?,
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
            includeReasoningInAssistantMessage =
                routeInfo.providerCapabilities.requiresReasoningContentForToolCalls,
            captureAnthropicContentBlocks =
                routeInfo.providerCapabilities.requiresAnthropicThinkingReplay,
            anthropicSourceModel = routeInfo.resolvedModel
        )
        var lastReasoning = ""
        var lastReasoningEmitLength = 0
        var lastReasoningEmitAt = 0L
        var reasoningEmitJob: Job? = null
        val reasoningLock = Any()
        var lastContent = ""
        var lastToolInputEmitAt = 0L
        val lastToolInputs = mutableMapOf<String, AssistantToolCall>()
        var eventSource: EventSource? = null
        val lastStreamActivityAtMs = AtomicLong(startedAtMs)
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

        fun emitToolInputs(force: Boolean = false) {
            val now = System.currentTimeMillis()
            // Same presentation cadence as reasoning, without limiting the input.
            if (!force && now - lastToolInputEmitAt < REASONING_UPDATE_INTERVAL_MS) return
            val calls = accumulator.currentToolCalls()
            if (calls.isEmpty()) return
            lastToolInputEmitAt = now
            emitReasoning(force = true)
            calls.forEach { call ->
                if (lastToolInputs.put(call.id, call) != call) {
                    enqueueEmission { onToolCallInput?.invoke(call) }
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
                emitToolInputs(force = true)
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

        fun failIdleStream(timeoutMs: Long) {
            if (!completed.compareAndSet(false, true)) return
            val error = AgentStreamIdleTimeoutException(timeoutMs)
            OmniLog.w(
                tag,
                "ACP provider timing stage=stream_idle_timeout " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
            streamDone.completeExceptionally(error)
            eventSource?.cancel()
        }

        val idleWatchdog = effectiveStreamIdleTimeoutMs?.let { timeoutMs ->
            scope.launch {
                val checkIntervalMs = timeoutMs.coerceIn(1L, 1_000L)
                while (!completed.get()) {
                    delay(checkIntervalMs)
                    if (
                        System.currentTimeMillis() - lastStreamActivityAtMs.get() >=
                        timeoutMs.coerceAtLeast(1L)
                    ) {
                        failIdleStream(timeoutMs)
                        break
                    }
                }
            }
        }

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                lastStreamActivityAtMs.set(System.currentTimeMillis())
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
                lastStreamActivityAtMs.set(System.currentTimeMillis())
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
                    emitToolInputs()
                    if (done) {
                        completeStream(eventSource)
                    }
                }.onFailure { error ->
                    if (completed.compareAndSet(false, true)) {
                        val failure = IllegalStateException(
                            "invalid chat completion stream chunk: ${error.message}",
                            error
                        )
                        streamDone.completeExceptionally(failure)
                        eventSource.cancel()
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (completed.get()) {
                    return
                }
                lastStreamActivityAtMs.set(System.currentTimeMillis())
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
                lastStreamActivityAtMs.set(System.currentTimeMillis())
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
                        responseBody = responseBody,
                        responseStarted = accumulator.hasAssistantPayload(),
                        cause = t,
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
            idleWatchdog?.cancel()
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
        if (!routeInfo.providerCapabilities.requiresReasoningContentForToolCalls) {
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
            maxCompletionTokens = maxCompletionTokens,
            maxTokens = maxTokens,
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
