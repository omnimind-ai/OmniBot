@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package cn.com.omnimind.bot.agent.runtime

import android.util.Log
import android.content.Context
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.OmniOfficialProvider
import cn.com.omnimind.baselib.llm.PlatformAiProvisioner
import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.llm.SceneModelBindingEntry
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentConversationModePolicy
import cn.com.omnimind.bot.agent.AgentResult
import cn.com.omnimind.bot.agent.AgentRuntimeContextRepository
import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import cn.com.omnimind.bot.agent.NoOpAgentRunControl
import cn.com.omnimind.bot.agent.OmniAgentExecutor
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.resolveAgentPermissionIds
import cn.com.omnimind.bot.plugin.sandbox.XiaowanChatCompletionRequestFactory
import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.ModelInfo
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.PromptCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.SetSessionModelResponse
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Xiaowan is a built-in ACP Agent. The loopback transport is only the official
 * ACP SDK transport boundary; no app-private request or event protocol exists.
 */
internal class XiaowanAcpConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val scheduleToolBridge: AgentScheduleToolBridge,
    private val conversationIdProvider: suspend (String) -> Long? = { null },
) : AcpRuntimeConnection {
    private lateinit var clientTransport: LoopbackTransport
    private lateinit var serverTransport: LoopbackTransport
    private lateinit var serverProtocol: Protocol
    private lateinit var serverProtocolScope: CoroutineScope

    override val exitSignal = CompletableDeferred<Int?>()
    override val isRunning: Boolean
        get() = ::clientTransport.isInitialized && clientTransport.started

    override fun createTransport(parentScope: CoroutineScope): Transport {
        clientTransport = LoopbackTransport()
        serverTransport = LoopbackTransport()
        clientTransport.peer = serverTransport
        serverTransport.peer = clientTransport
        // The loopback Agent is still the official ACP server/client pair,
        // but its protocol reader must not share the caller's uncaught
        // exception path. A cancelled ACP request is normal (route changes,
        // stop, or a client timeout); an exception in the SDK's cancellation
        // handler must fail this loopback connection, not abort the Android
        // process and make Enhance look like a dead button.
        val parentJob = parentScope.coroutineContext[Job]
        serverProtocolScope = CoroutineScope(
            parentScope.coroutineContext +
                SupervisorJob(parentJob) +
                CoroutineExceptionHandler { _, error ->
                    Log.e(TAG, "Loopback ACP server failed", error)
                }
        )
        serverProtocol = Protocol(serverProtocolScope, serverTransport)
        Agent(
            serverProtocol,
            XiaowanAgentSupport(
                context = context,
                scope = scope,
                scheduleToolBridge = scheduleToolBridge,
                conversationIdProvider = conversationIdProvider,
            )
        )
        return clientTransport
    }

    override suspend fun start() {
        serverProtocol.start()
        serverTransport.start()
    }

    override fun diagnosticSummary(): String = ""

    override fun exitDescription(exitCode: Int?): String =
        "Built-in Xiaowan ACP Agent closed before initialize completed"

    override suspend fun close() {
        if (::serverProtocol.isInitialized) serverProtocol.close()
        if (::serverProtocolScope.isInitialized) serverProtocolScope.cancel()
        if (::clientTransport.isInitialized) clientTransport.close()
        if (::serverTransport.isInitialized) serverTransport.close()
    }

    private companion object {
        private const val TAG = "XiaowanAcpConnection"
    }
}

private class XiaowanAgentSupport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val scheduleToolBridge: AgentScheduleToolBridge,
    private val conversationIdProvider: suspend (String) -> Long?,
) : AgentSupport {
    private companion object {
        private const val TAG = "XiaowanAcpConnection"
    }

    @Volatile
    private var cachedModels: XiaowanModels? = null

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
        // Provider/model resolution is owned by the shared binding surface.
        // Validate it during ACP initialization so a Harness switch cannot
        // appear connected and only fail on the first session/new call.
        try {
            loadXiaowanModels()
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "ACP timing agent=xiaowan stage=initialize_model_failed " +
                    "reason=${error.javaClass.simpleName}"
            )
            throw error
        }
        return AgentInfo(
            protocolVersion = 1,
            capabilities = AgentCapabilities(
                promptCapabilities = PromptCapabilities(
                    image = true,
                    embeddedContext = true,
                )
            ),
            authMethods = emptyList(),
            implementation = Implementation(
                name = "xiaowan",
                version = BuildConfig.VERSION_NAME,
                title = "小万",
            ),
            _meta = JsonNull,
        )
    }

    override suspend fun createSession(
        sessionParameters: SessionCreationParameters,
    ): AgentSession {
        val startedAtNanos = System.nanoTime()
        val models = try {
            loadXiaowanModels()
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "ACP timing agent=xiaowan stage=session_model_failed " +
                    "elapsedMs=${elapsedMillis(startedAtNanos)} " +
                    "reason=${error.javaClass.simpleName}"
            )
            throw error
        }
        return XiaowanAgentSession(
            context = context,
            scope = scope,
            scheduleToolBridge = scheduleToolBridge,
            conversationIdProvider = conversationIdProvider,
            availableModels = models.available,
            configuredModelId = models.configuredModelId,
            providerProfile = models.providerProfile,
            sessionId = SessionId(UUID.randomUUID().toString()),
        )
    }

    private suspend fun loadXiaowanModels(): XiaowanModels {
        val existingBinding = SceneModelBindingStore.getBinding("scene.dispatch.model")
        cachedModels?.let { cached ->
            val bindingStillMatches = existingBinding?.providerProfileId
                ?.trim()
                ?.equals(cached.providerProfileId, ignoreCase = false) == true &&
                existingBinding.modelId.trim() == cached.configuredModelId
            if (bindingStillMatches) {
                Log.i(TAG, "ACP timing agent=xiaowan stage=model_ready source=connection_cache")
                return cached
            }
            cachedModels = null
        }
        val startedAtNanos = System.nanoTime()
        val profileId = existingBinding?.providerProfileId
            ?: ModelProviderConfigStore.getEditingProfileId()
        val profile = profileId.let(ModelProviderConfigStore::getProfile)
            ?: PlatformAiProvisioner.officialProfileOrNull()
                ?.takeIf { OmniOfficialProvider.isOfficialProfile(profileId) }
            ?: throw IllegalStateException(
                "The configured scene Provider is unavailable: $profileId"
            )
        val boundModels = buildXiaowanModelsFromBinding(existingBinding)
        // A valid shared binding is already the user's selected Provider and
        // model. Re-querying /models for every ACP session makes an ordinary
        // Xiaowan turn wait on network discovery before it can stream its
        // first chunk. The shared model/list surface still refreshes the
        // authoritative catalog when the user opens model settings; session
        // creation only needs the bound model.
        boundModels?.let {
            val resolved = it.copy(providerProfile = profile.toSessionSnapshot())
            cachedModels = resolved
            Log.i(
                TAG,
                "ACP timing agent=xiaowan stage=model_ready source=binding " +
                    "elapsedMs=${elapsedMillis(startedAtNanos)}"
            )
            return resolved
        }
        // Model discovery belongs to the explicit Provider/model settings
        // surface. Never put a network /models request on ACP session/new:
        // one slow or unreachable Provider used to make a normal Xiaowan
        // prompt look frozen before the Provider request even started.
        throw IllegalStateException(
            "No verified Provider/model binding for Xiaowan ACP. " +
                "Select a model in scene.dispatch.model and retry."
        )
    }
}

private fun elapsedMillis(startedAtNanos: Long): Long =
    (System.nanoTime() - startedAtNanos) / 1_000_000L

internal fun resolveSharedAgentProviderBinding(
    currentBinding: SceneModelBindingEntry?,
    editingProfile: ModelProviderProfile?,
    availableModels: List<ProviderModelOption>,
): SceneModelBindingEntry? {
    currentBinding
        ?.takeIf { it.providerProfileId.trim().isNotEmpty() && it.modelId.trim().isNotEmpty() }
        ?.let { return it }

    val profile = editingProfile?.takeIf(ModelProviderProfile::isConfigured) ?: return null
    val model = availableModels
        .firstOrNull { it.id.trim().isNotEmpty() }
        ?.id
        ?.trim()
        ?: return null
    return SceneModelBindingEntry(
        sceneId = "scene.dispatch.model",
        providerProfileId = profile.id,
        modelId = model,
    )
}

internal data class XiaowanModels(
    val available: List<ModelInfo>,
    val configuredModelId: String,
    val providerProfileId: String,
    val providerProfile: ModelProviderProfile,
)

internal fun buildXiaowanModelsFromBinding(
    binding: SceneModelBindingEntry?,
): XiaowanModels? {
    val modelId = binding
        ?.modelId
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: return null
    val providerProfileId = binding.providerProfileId
        .trim()
        .takeIf(String::isNotEmpty)
        ?: return null
    return XiaowanModels(
        available = listOf(
            ModelInfo(
                ModelId(modelId),
                modelId,
                "",
                JsonNull,
            )
        ),
        configuredModelId = modelId,
        providerProfileId = providerProfileId,
        providerProfile = ModelProviderProfile(id = providerProfileId, name = ""),
    )
}

private fun ModelProviderProfile.toSessionSnapshot(): ModelProviderProfile =
    copy(customHeaders = customHeaders.toMap())

private class XiaowanAgentSession(
    private val context: Context,
    scope: CoroutineScope,
    private val scheduleToolBridge: AgentScheduleToolBridge,
    private val conversationIdProvider: suspend (String) -> Long?,
    override val availableModels: List<ModelInfo>,
    private val configuredModelId: String,
    private val providerProfile: ModelProviderProfile,
    override val sessionId: SessionId,
) : AgentSession {
    private companion object {
        private const val TAG = "XiaowanAcpConnection"
    }

    private val messages = mutableListOf<ChatCompletionMessage>()
    private val promptMutex = Mutex()
    private var selectedModelId: String = configuredModelId
    private val executor = OmniAgentExecutor(
        context = context,
        scope = scope,
        scheduleToolBridge = object : AgentScheduleToolBridge {
            override suspend fun createTask(arguments: Map<String, Any?>) =
                scheduleToolBridge.createTask(withConversationParent(arguments))

            override suspend fun listTasks(): List<Map<String, Any?>> =
                scheduleToolBridge.listTasks()

            override suspend fun updateTask(arguments: Map<String, Any?>) =
                scheduleToolBridge.updateTask(withConversationParent(arguments))

            override suspend fun deleteTask(arguments: Map<String, Any?>) =
                scheduleToolBridge.deleteTask(arguments)

            private suspend fun withConversationParent(
                arguments: Map<String, Any?>
            ): Map<String, Any?> {
                val conversationId = conversationIdProvider(sessionId.value)
                    ?.takeIf { it > 0L }
                    ?.toString()
                    ?: return arguments
                return arguments + mapOf(
                    "parentConversationId" to conversationId,
                    "parentConversationMode" to AgentConversationModePolicy.NORMAL_MODE
                )
            }
        },
    )

    override suspend fun prompt(
        content: List<ContentBlock>,
        meta: JsonElement?,
    ): Flow<Event> = channelFlow {
        promptMutex.withLock {
        val promptParts = buildXiaowanPromptParts(content)
        val text = promptParts.text
        require(text.isNotEmpty() || promptParts.attachments.isNotEmpty()) {
            "Xiaowan ACP prompt is empty"
        }
        val streamBridge = XiaowanAcpEventBridge { update ->
            // AgentCallback can arrive from provider/tool worker coroutines.
            // `flow { emit(...) }` is not thread-safe and drops the whole turn
            // with Flow invariant violations when two stream callbacks race.
            // channelFlow serializes the hand-off while preserving every ACP
            // session/update event.
            send(Event.SessionUpdateEvent(update))
        }
        val conversationId = conversationIdProvider(sessionId.value)
        Log.i(
            TAG,
            "prompt session=${sessionId.value} conversationId=${conversationId ?: "unbound"} " +
                "inMemoryMessages=${messages.size} historySource=" +
                if (conversationId != null) "conversation" else "session_memory"
        )
        val conversationMode = (meta as? JsonObject)
            ?.get("conversationMode")
            ?.jsonPrimitive
            ?.content
            ?: AgentConversationModePolicy.NORMAL_MODE
        val reasoningEffort = normalizeXiaowanReasoningEffort(
            (meta as? JsonObject)
                ?.get("reasoningEffort")
                ?.jsonPrimitive
                ?.content
        )
        val result = executor.processUserMessage(
            userMessage = text,
            conversationHistory = emptyList(),
            runtimeContextRepository = AgentRuntimeContextRepository(context),
            attachments = promptParts.attachments,
            conversationId = conversationId,
            conversationMode = conversationMode,
            modelOverride = selectedModelOverride(),
            reasoningEffort = reasoningEffort,
            terminalEnvironment = emptyMap(),
            callback = streamBridge,
            runControl = NoOpAgentRunControl,
            historyMessagesOverride = messages.toList().takeIf { conversationId == null },
        )
        val answer = when (result) {
            is AgentResult.Success -> {
                val response = result.response.content
                messages += ChatCompletionMessage(
                    role = "user",
                    content = JsonPrimitive(text),
                )
                messages += ChatCompletionMessage(
                    role = "assistant",
                    content = JsonPrimitive(response),
                )
                response
            }
            is AgentResult.Error -> throw result.exception
                ?: IllegalStateException(result.message)
        }
        // The executor reports cumulative snapshots through AgentCallback. The
        // bridge already converted those snapshots to ACP chunks; this final
        // call only fills a gap when a provider returned content without any
        // callback update, and is de-duplicated by the same bridge.
        streamBridge.emitAssistantSnapshot(answer)
        send(
            Event.PromptResponseEvent(
                PromptResponse(
                    stopReason = StopReason.END_TURN,
                    _meta = JsonNull,
                )
            )
        )
        }
    }

    /**
     * The shared ACP UI exposes a superset of effort names used by external
     * Agents. Xiaowan's OpenAI-compatible request factory accepts the common
     * four-level vocabulary, so map aliases at this adapter boundary and keep
     * the shared ACP contract provider-agnostic.
     */
    override val defaultModel: ModelId
        get() = ModelId(configuredModelId)

    override suspend fun setModel(
        modelId: ModelId,
        _meta: JsonElement?,
    ): SetSessionModelResponse {
        require(availableModels.any { it.modelId == modelId }) {
            "Model is not available from the configured Provider: ${modelId.value}"
        }
        selectedModelId = modelId.value
        return SetSessionModelResponse(JsonNull)
    }

    private fun selectedModelOverride(): cn.com.omnimind.bot.agent.AgentModelOverride? {
        val modelId = selectedModelId.trim()
        if (modelId.isEmpty()) return null
        if (!providerProfile.isConfigured()) return null
        return cn.com.omnimind.bot.agent.AgentModelOverride(
            providerProfileId = providerProfile.id,
            providerProfileName = providerProfile.name,
            modelId = modelId,
            apiBase = providerProfile.baseUrl,
            apiKey = providerProfile.apiKey,
            customHeaders = providerProfile.customHeaders,
            protocolType = providerProfile.protocolType,
            wireApi = providerProfile.wireApi,
        )
    }
}

/**
 * Maps the shared ACP vocabulary at the Xiaowan adapter boundary. Keeping
 * this pure makes the provider-facing contract testable without constructing
 * an Android Agent session.
 */
internal fun normalizeXiaowanReasoningEffort(value: String?): String? {
    return when (value?.trim()?.lowercase()) {
        // The Provider's default may enable deep thinking even for a
        // one-word greeting. ACP effort is optional, so Xiaowan follows
        // its request factory and keeps thinking opt-in rather than
        // turning every ordinary Agent turn into a long deliberation.
        null, "" -> XiaowanChatCompletionRequestFactory.DEFAULT_REASONING_EFFORT
        "no", "none", "off" -> "none"
        "min", "minimal", "minimum", "low" -> "low"
        "med", "medium" -> "medium"
        "high", "extra_high", "extra-high", "very_high", "very-high",
        "x-high", "x high", "xhigh", "max" -> "high"
        else -> null
    }
}

internal data class XiaowanPromptParts(
    val text: String,
    val attachments: List<Map<String, Any?>>
)

internal fun buildXiaowanPromptParts(content: List<ContentBlock>): XiaowanPromptParts {
    val textParts = mutableListOf<String>()
    val attachments = mutableListOf<Map<String, Any?>>()
    content.forEach { block ->
        when (block) {
            is ContentBlock.Text -> block.text.takeIf(String::isNotBlank)?.let(textParts::add)
            is ContentBlock.Image -> {
                val mimeType = block.mimeType.trim().ifEmpty { "image/*" }
                val uri = block.uri?.trim().orEmpty()
                val data = block.data.trim()
                if (data.isEmpty() && uri.isEmpty()) return@forEach
                val dataUrl = if (data.startsWith("data:", ignoreCase = true)) {
                    data
                } else {
                    "data:$mimeType;base64,$data"
                }
                attachments += buildMap<String, Any?> {
                    put("name", "image")
                    put("fileName", "image")
                    put("mimeType", mimeType)
                    put("isImage", true)
                    put("sendToModel", true)
                    if (data.isNotEmpty()) put("dataUrl", dataUrl)
                    if (uri.isNotEmpty()) put("url", uri)
                }
            }
            is ContentBlock.ResourceLink -> {
                val uri = block.uri.trim()
                if (uri.isEmpty()) return@forEach
                val isImage = block.mimeType?.startsWith("image/", ignoreCase = true) == true
                val localPath = uri.removePrefix("file://")
                attachments += buildMap<String, Any?> {
                    put("name", block.name)
                    put("fileName", block.name)
                    put("mimeType", block.mimeType ?: "application/octet-stream")
                    put("isImage", isImage)
                    put("sendToModel", isImage)
                    put("path", if (uri.startsWith("file://")) localPath else uri)
                    put("promptPath", uri)
                    put("workspacePath", uri)
                    if (!uri.startsWith("file://")) put("url", uri)
                    block.size?.let { put("size", it) }
                }
            }
            is ContentBlock.Resource -> when (val resource = block.resource) {
                is EmbeddedResourceResource.TextResourceContents -> {
                    resource.text.takeIf(String::isNotBlank)?.let(textParts::add)
                }
                is EmbeddedResourceResource.BlobResourceContents -> {
                    val mimeType = resource.mimeType?.trim()
                        ?.ifEmpty { "application/octet-stream" }
                        ?: "application/octet-stream"
                    val uri = resource.uri.orEmpty()
                    if (mimeType.startsWith("image/")) {
                        attachments += buildMap<String, Any?> {
                            put("name", uri)
                            put("fileName", uri)
                            put("mimeType", mimeType)
                            put("isImage", true)
                            put("sendToModel", true)
                            put("dataUrl", "data:$mimeType;base64,${resource.blob}")
                            put("promptPath", uri)
                        }
                    }
                }
            }
            else -> Unit
        }
    }
    return XiaowanPromptParts(
        text = textParts.joinToString("\n").trim(),
        attachments = attachments
    )
}

/** Convert the executor's cumulative snapshots into append-only ACP chunks. */
internal fun acpSnapshotDelta(previous: String, next: String): String? {
    if (next.isEmpty() || next == previous) return null
    if (previous.isEmpty()) return next
    if (next.startsWith(previous)) return next.removePrefix(previous).ifEmpty { null }
    // A provider retry can reset the snapshot. Emit the new snapshot as a new
    // chunk rather than concatenating unrelated generations.
    if (previous.startsWith(next)) return null
    return next
}

internal class XiaowanAcpEventBridge(
    private val emitUpdate: suspend (SessionUpdate) -> Unit,
) : AgentCallback {
    private val callbackMutex = Mutex()
    private var assistantSnapshot = ""
    private var thoughtSnapshot = ""
    private var assistantMessageId = MessageId(UUID.randomUUID().toString())
    private var thoughtMessageId = MessageId(UUID.randomUUID().toString())
    private val toolIdsByName = mutableMapOf<String, ArrayDeque<String>>()

    suspend fun emitAssistantSnapshot(snapshot: String) {
        callbackMutex.withLock {
            emitAssistantSnapshotLocked(snapshot)
        }
    }

    private suspend fun emitAssistantSnapshotLocked(
        snapshot: String,
        meta: JsonElement = JsonNull,
    ) {
        emitTextSnapshot(
            snapshot = snapshot,
            previous = assistantSnapshot,
            messageId = assistantMessageId,
            emit = { delta, id ->
                assistantSnapshot = snapshot
                assistantMessageId = id
                emitUpdate(
                    SessionUpdate.AgentMessageChunk(
                        content = ContentBlock.Text(delta),
                        messageId = id,
                        _meta = meta,
                    )
                )
            }
        )
    }

    override suspend fun onThinkingStart() {
        callbackMutex.withLock {
            thoughtSnapshot = ""
            // The bridge is scoped to one ACP prompt. A prompt may contain
            // several provider/tool rounds, but they are one user-visible
            // reasoning card. Keep the message id stable across those rounds
            // so the shared ACP reducer appends to the same card instead of
            // rendering another "思考完成" section for every round.
        }
    }

    override suspend fun onThinkingUpdate(thinking: String) {
        callbackMutex.withLock {
            val displayText = reasoningDisplayText(thinking)
            emitTextSnapshot(
                snapshot = displayText,
                previous = thoughtSnapshot,
                messageId = thoughtMessageId,
                emit = { delta, id ->
                    thoughtSnapshot = displayText
                    thoughtMessageId = id
                    emitUpdate(
                        SessionUpdate.AgentThoughtChunk(
                            content = ContentBlock.Text(delta),
                            messageId = id,
                            _meta = reasoningAcpMeta(thinking),
                        )
                    )
                }
            )
        }
    }

    override suspend fun onToolCallStart(
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject,
    ) {
        callbackMutex.withLock {
            emitToolStart(UUID.randomUUID().toString(), toolName, arguments)
        }
    }

    override suspend fun onToolCallStart(
        toolCallId: String,
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject,
    ) {
        callbackMutex.withLock {
            emitToolStart(toolCallId.ifBlank { UUID.randomUUID().toString() }, toolName, arguments)
        }
    }

    private suspend fun emitToolStart(
        toolCallId: String,
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject,
    ) {
        toolIdsByName.getOrPut(toolName) { ArrayDeque() }.addLast(toolCallId)
        emitUpdate(
            SessionUpdate.ToolCall(
                toolCallId = ToolCallId(toolCallId),
                title = toolName,
                kind = ToolKind.OTHER,
                status = ToolCallStatus.IN_PROGRESS,
                content = listOf(
                    ToolCallContent.Content(ContentBlock.Text(arguments.toString()))
                ),
                locations = emptyList(),
                rawInput = arguments,
                rawOutput = JsonNull,
                _meta = JsonNull,
            )
        )
    }

    override suspend fun onToolCallProgress(
        toolName: String,
        progress: String,
        extras: Map<String, Any?>,
    ) {
        callbackMutex.withLock {
            emitToolProgress(
                toolIdsByName[toolName]?.lastOrNull()
                    ?: UUID.randomUUID().toString().also {
                        toolIdsByName.getOrPut(toolName) { ArrayDeque() }.addLast(it)
                },
                toolName,
                progress,
                extras,
            )
        }
    }

    override suspend fun onToolCallProgress(
        toolCallId: String,
        toolName: String,
        progress: String,
        extras: Map<String, Any?>,
    ) {
        callbackMutex.withLock {
            val resolvedId = toolCallId.ifBlank {
                toolIdsByName[toolName]?.lastOrNull()
                    ?: UUID.randomUUID().toString().also {
                        toolIdsByName.getOrPut(toolName) { ArrayDeque() }.addLast(it)
                    }
            }
            emitToolProgress(resolvedId, toolName, progress, extras)
        }
    }

    private suspend fun emitToolProgress(
        toolCallId: String,
        toolName: String,
        progress: String,
        extras: Map<String, Any?>,
    ) {
        emitUpdate(
            SessionUpdate.ToolCallUpdate(
                toolCallId = ToolCallId(toolCallId),
                title = toolName,
                kind = ToolKind.OTHER,
                status = ToolCallStatus.IN_PROGRESS,
                content = listOf(
                    ToolCallContent.Content(ContentBlock.Text(progress))
                ),
                locations = emptyList(),
                rawInput = jsonObjectFromMap(extras),
                rawOutput = JsonNull,
                _meta = JsonNull,
            )
        )
    }

    override suspend fun onToolCallComplete(
        toolName: String,
        result: ToolExecutionResult,
    ) {
        callbackMutex.withLock {
            emitToolComplete(removeToolCallId(toolName, null), toolName, result)
        }
    }

    override suspend fun onToolCallComplete(
        toolCallId: String,
        toolName: String,
        result: ToolExecutionResult,
    ) {
        callbackMutex.withLock {
            emitToolComplete(
                removeToolCallId(toolName, toolCallId.ifBlank { null }),
                toolName,
                result,
            )
        }
    }

    private suspend fun emitToolComplete(
        toolCallId: String?,
        toolName: String,
        result: ToolExecutionResult,
    ) {
        val resolvedToolCallId = toolCallId ?: UUID.randomUUID().toString()
        val text = toolResultText(result)
        val permissionPayload = (result as? ToolExecutionResult.PermissionRequired)
            ?.let { permissionResult ->
                jsonObjectFromMap(
                    mapOf(
                        "type" to "permission_section",
                        "requiredPermissionIds" to resolveAgentPermissionIds(
                            permissionResult.missing
                        ),
                        "missing" to permissionResult.missing,
                        "message" to text,
                    )
                )
            }
        val rawOutput = permissionPayload ?: toolResultAcpPayload(result)
        emitUpdate(
            SessionUpdate.ToolCallUpdate(
                toolCallId = ToolCallId(resolvedToolCallId),
                title = toolName,
                kind = ToolKind.OTHER,
                status = if (toolResultSucceeded(result)) {
                    ToolCallStatus.COMPLETED
                } else {
                    ToolCallStatus.FAILED
                },
                content = text.takeIf(String::isNotEmpty)?.let {
                    listOf(ToolCallContent.Content(ContentBlock.Text(it)))
                }.orEmpty(),
                locations = emptyList(),
                rawInput = JsonNull,
                rawOutput = rawOutput,
                _meta = JsonNull,
            )
        )
    }

    private fun removeToolCallId(toolName: String, requestedId: String?): String? {
        val ids = toolIdsByName[toolName] ?: return requestedId
        val resolved = requestedId ?: ids.removeLastOrNull()
        if (requestedId != null) {
            ids.remove(requestedId)
        }
        if (ids.isEmpty()) {
            toolIdsByName.remove(toolName)
        }
        return resolved
    }

    override suspend fun onChatMessage(message: String) {
        callbackMutex.withLock {
            emitAssistantSnapshotLocked(message)
        }
    }

    override suspend fun onChatMessage(message: String, isFinal: Boolean) {
        callbackMutex.withLock {
            emitAssistantSnapshotLocked(message)
        }
    }

    override suspend fun onChatMessage(
        message: String,
        isFinal: Boolean,
        prefillTokensPerSecond: Double?,
        decodeTokensPerSecond: Double?,
    ) {
        callbackMutex.withLock {
            emitAssistantSnapshotLocked(
                snapshot = message,
                meta = acpPresentationMeta(
                    "usage" to mapOf(
                        "prefillTokensPerSecond" to prefillTokensPerSecond,
                        "decodeTokensPerSecond" to decodeTokensPerSecond,
                    )
                ),
            )
        }
    }

    override suspend fun onRetrying(
        retryCount: Int,
        maxRetries: Int,
        retryDelayMs: Long,
        message: String,
        retryReason: String?,
    ) {
        callbackMutex.withLock {
            emitAssistantStatus(
                acpPresentationMeta(
                    "retry" to mapOf(
                        "count" to retryCount,
                        "maxRetries" to maxRetries,
                        "delayMs" to retryDelayMs,
                        "message" to message,
                        "reason" to retryReason,
                    )
                )
            )
        }
    }

    override suspend fun onPromptTokenUsageChanged(
        latestPromptTokens: Int,
        promptTokenThreshold: Int?,
    ) {
        callbackMutex.withLock {
            emitAssistantStatus(
                acpPresentationMeta(
                    "usage" to mapOf(
                        "latestPromptTokens" to latestPromptTokens,
                        "promptTokenThreshold" to promptTokenThreshold,
                    )
                )
            )
        }
    }

    override suspend fun onContextCompactionStateChanged(
        isCompacting: Boolean,
        latestPromptTokens: Int?,
        promptTokenThreshold: Int?,
    ) {
        callbackMutex.withLock {
            emitUpdate(
                SessionUpdate.AgentThoughtChunk(
                    content = ContentBlock.Text(""),
                    messageId = thoughtMessageId,
                    _meta = acpPresentationMeta(
                        "compaction" to mapOf(
                            "status" to if (isCompacting) "compressing" else "completed",
                            "trigger" to "auto",
                            "latestPromptTokens" to latestPromptTokens,
                            "promptTokenThreshold" to promptTokenThreshold,
                        )
                    ),
                )
            )
        }
    }

    override suspend fun onClarifyRequired(question: String, missingFields: List<String>?) {
        callbackMutex.withLock {
            emitAssistantNotice(question)
        }
    }
    override suspend fun onComplete(result: AgentResult) = Unit
    override suspend fun onError(error: String) {
        onError(error, retryable = false)
    }
    override suspend fun onError(error: String, retryable: Boolean) {
        callbackMutex.withLock {
            emitAssistantNotice(
                text = error,
                meta = acpPresentationMeta(
                    "recovery" to mapOf(
                        "error" to error,
                        "retryable" to retryable,
                        "continueable" to false,
                    )
                ),
            )
        }
    }
    override suspend fun onPermissionRequired(missing: List<String>) {
        // The structured permission card is emitted with the corresponding
        // ACP tool_call_update. Do not also emit an assistant sentence here:
        // that used to leave the user with text only.
    }

    private suspend fun emitAssistantStatus(meta: JsonElement) {
        emitUpdate(
            SessionUpdate.AgentMessageChunk(
                content = ContentBlock.Text(""),
                messageId = assistantMessageId,
                _meta = meta,
            )
        )
    }

    private suspend fun emitAssistantNotice(
        text: String,
        meta: JsonElement = JsonNull,
    ) {
        val normalized = text.trim()
        if (normalized.isEmpty()) return
        emitUpdate(
            SessionUpdate.AgentMessageChunk(
                content = ContentBlock.Text(normalized),
                messageId = MessageId(UUID.randomUUID().toString()),
                _meta = meta,
            )
        )
    }

    private suspend fun emitTextSnapshot(
        snapshot: String,
        previous: String,
        messageId: MessageId,
        emit: suspend (String, MessageId) -> Unit,
    ) {
        val delta = acpSnapshotDelta(previous, snapshot) ?: return
        val id = if (previous.isNotEmpty() && !snapshot.startsWith(previous)) {
            MessageId(UUID.randomUUID().toString())
        } else {
            messageId
        }
        emit(delta, id)
    }
}

private fun jsonObjectFromMap(values: Map<String, Any?>): kotlinx.serialization.json.JsonObject =
    kotlinx.serialization.json.JsonObject(values.mapValues { (_, value) -> jsonElementFromAny(value) })

private fun jsonElementFromAny(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Map<*, *> -> jsonObjectFromMap(
        value.entries.associate { (key, entry) -> key.toString() to entry }
    )
    is Iterable<*> -> kotlinx.serialization.json.JsonArray(value.map(::jsonElementFromAny))
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value.toString())
    else -> JsonPrimitive(value.toString())
}

private fun toolResultSucceeded(result: ToolExecutionResult): Boolean = when (result) {
    is ToolExecutionResult.Error,
    is ToolExecutionResult.Interrupted,
    is ToolExecutionResult.PermissionRequired -> false
    is ToolExecutionResult.TerminalResult -> result.success
    is ToolExecutionResult.ScheduleResult -> result.success
    is ToolExecutionResult.McpResult -> result.success
    is ToolExecutionResult.MemoryResult -> result.success
    is ToolExecutionResult.ContextResult -> result.success
    is ToolExecutionResult.ChatMessage,
    is ToolExecutionResult.Clarify -> true
}

private fun toolResultText(result: ToolExecutionResult): String = when (result) {
    is ToolExecutionResult.ChatMessage -> result.message
    is ToolExecutionResult.Clarify -> result.question
    is ToolExecutionResult.Error -> result.message
    is ToolExecutionResult.PermissionRequired -> result.missing.joinToString(", ")
    is ToolExecutionResult.ScheduleResult -> result.summaryText
    is ToolExecutionResult.McpResult -> result.summaryText.ifBlank { result.rawResultJson }
    is ToolExecutionResult.MemoryResult -> result.summaryText.ifBlank { result.rawResultJson }
    is ToolExecutionResult.TerminalResult -> result.summaryText.ifBlank { result.terminalOutput }
    is ToolExecutionResult.Interrupted -> result.summaryText
    is ToolExecutionResult.ContextResult -> result.summaryText.ifBlank { result.rawResultJson }
}

private fun reasoningAcpMeta(thinking: String): JsonObject {
    val reasoning = linkedMapOf<String, Any?>(
        "stage" to "thinking",
        "content" to thinking,
    )
    val structured = structuredReasoning(thinking)
    if (structured != null) {
        fun copy(source: String, target: String = source) {
            structured[source]?.let { reasoning[target] = it }
        }
        copy("task_description", "taskDescription")
        copy("taskDescription")
        copy("sub_tasks", "subTasks")
        copy("subTasks")
        copy("preparation")
        copy("task_title", "taskTitle")
        copy("taskTitle")
        copy("memory_actions", "memoryActions")
        copy("memoryActions")
    }
    return acpPresentationMeta("reasoning" to reasoning)
}

private fun structuredReasoning(thinking: String): JsonObject? = runCatching {
    Json.parseToJsonElement(thinking) as? JsonObject
}.getOrNull()

private fun reasoningDisplayText(thinking: String): String {
    val structured = structuredReasoning(thinking) ?: return thinking
    val lines = mutableListOf<String>()
    val taskDescription = (
        structured["task_description"]?.presentationText()
            ?: structured["taskDescription"]?.presentationText()
        ).orEmpty().trim()
    if (taskDescription.isNotEmpty()) {
        lines += taskDescription
    }
    val subTasks = (structured["sub_tasks"] ?: structured["subTasks"]) as? JsonArray
    val subTaskLines = subTasks
        ?.mapNotNull { it.presentationText()?.trim()?.takeIf(String::isNotEmpty) }
        .orEmpty()
    if (subTaskLines.isNotEmpty()) {
        lines += subTaskLines.joinToString("\n") { "- $it" }
    }
    structured["preparation"]?.presentationText()
        ?.takeIf { it.isNotBlank() }
        ?.let(lines::add)
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n\n") ?: thinking
}

private fun JsonElement.presentationText(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonObject -> listOf("content", "text", "title", "description")
        .asSequence()
        .mapNotNull { key -> this[key]?.presentationText() }
        .firstOrNull()
    else -> toString()
}

private fun acpPresentationMeta(vararg values: Pair<String, Any?>): JsonObject =
    jsonObjectFromMap(
        mapOf(
            "cn.com.omnimind.agent" to values.toMap()
        )
    )

/**
 * Preserves the common tool-result vocabulary inside ACP [rawOutput].
 *
 * ACP deliberately leaves tool output unconstrained. Keeping this shape here
 * means every Harness can feed the same frontend card projection without a
 * Xiaowan-only event or widget path.
 */
private fun toolResultAcpPayload(result: ToolExecutionResult): JsonObject {
    val payload = linkedMapOf<String, Any?>(
        "summary" to toolResultText(result),
        "success" to toolResultSucceeded(result),
        "artifacts" to result.artifacts.map { it.toPayload() },
        "workspaceId" to result.workspaceId,
        "actions" to result.actions.map { it.toPayload() },
    )
    when (result) {
        is ToolExecutionResult.ChatMessage -> {
            payload["toolType"] = "message"
            payload["result"] = result.message
        }
        is ToolExecutionResult.Clarify -> {
            payload["toolType"] = "clarify"
            payload["result"] = mapOf(
                "question" to result.question,
                "missingFields" to (result.missingFields ?: emptyList<String>()),
            )
        }
        is ToolExecutionResult.Error -> {
            payload["toolType"] = "tool"
            payload["toolName"] = result.toolName
            payload["error"] = result.message
        }
        is ToolExecutionResult.PermissionRequired -> {
            // Permission results use the dedicated ACP permission payload in
            // emitToolComplete, but keeping this branch total makes this
            // serializer safe for future callers.
            payload["toolType"] = "permission"
            payload["missing"] = result.missing
        }
        is ToolExecutionResult.ScheduleResult -> {
            payload["toolType"] = "schedule"
            payload["toolName"] = result.toolName
            payload["result"] = jsonElementFromJsonText(result.previewJson)
            payload["taskId"] = result.taskId
        }
        is ToolExecutionResult.McpResult -> {
            payload["toolType"] = "mcp"
            payload["toolName"] = result.toolName
            payload["serverName"] = result.serverName
            payload["result"] = jsonElementFromJsonText(result.previewJson)
            payload["rawResult"] = jsonElementFromJsonText(result.rawResultJson)
        }
        is ToolExecutionResult.MemoryResult -> {
            payload["toolType"] = "memory"
            payload["toolName"] = result.toolName
            payload["result"] = jsonElementFromJsonText(result.previewJson)
            payload["rawResult"] = jsonElementFromJsonText(result.rawResultJson)
        }
        is ToolExecutionResult.TerminalResult -> {
            payload["toolType"] = "terminal"
            payload["toolName"] = result.toolName
            payload["result"] = jsonElementFromJsonText(result.previewJson)
            payload["rawResult"] = jsonElementFromJsonText(result.rawResultJson)
            payload["timedOut"] = result.timedOut
            payload["terminalOutput"] = result.terminalOutput
            payload["terminalSessionId"] = result.terminalSessionId
            payload["terminalStreamState"] = result.terminalStreamState
        }
        is ToolExecutionResult.Interrupted -> {
            payload["toolType"] = "terminal"
            payload["toolName"] = result.toolName
            payload["result"] = jsonElementFromJsonText(result.previewJson)
            payload["rawResult"] = jsonElementFromJsonText(result.rawResultJson)
            payload["terminalOutput"] = result.terminalOutput
            payload["terminalSessionId"] = result.terminalSessionId
            payload["terminalStreamState"] = result.terminalStreamState
            payload["interruptedBy"] = result.interruptedBy
            payload["interruptionReason"] = result.interruptionReason
        }
        is ToolExecutionResult.ContextResult -> {
            payload["toolType"] = "context"
            payload["toolName"] = result.toolName
            payload["result"] = jsonElementFromJsonText(result.previewJson)
            payload["rawResult"] = jsonElementFromJsonText(result.rawResultJson)
            payload["imageDataUrl"] = result.imageDataUrl
        }
    }
    return jsonObjectFromMap(payload)
}

private fun jsonElementFromJsonText(text: String): JsonElement = runCatching {
    Json.parseToJsonElement(text)
}.getOrElse {
    JsonPrimitive(text)
}

private class LoopbackTransport : BaseTransport() {
    var peer: LoopbackTransport? = null
    var started: Boolean = false

    override fun start() {
        started = true
    }

    override fun send(message: JsonRpcMessage) {
        peer?.deliver(message)
    }

    private fun deliver(message: JsonRpcMessage) {
        fireMessage(message)
    }

    override fun close() {
        started = false
        fireClose()
    }
}
