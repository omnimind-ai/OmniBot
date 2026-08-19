@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.OmniOfficialProvider
import cn.com.omnimind.baselib.llm.PlatformAiProvisioner
import cn.com.omnimind.baselib.llm.ProviderModelOption
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
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

    override val exitSignal = CompletableDeferred<Int?>()
    override val isRunning: Boolean
        get() = ::clientTransport.isInitialized && clientTransport.started

    override fun createTransport(parentScope: CoroutineScope): Transport {
        clientTransport = LoopbackTransport()
        serverTransport = LoopbackTransport()
        clientTransport.peer = serverTransport
        serverTransport.peer = clientTransport
        serverProtocol = Protocol(parentScope, serverTransport)
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
        if (::clientTransport.isInitialized) clientTransport.close()
        if (::serverTransport.isInitialized) serverTransport.close()
    }
}

private class XiaowanAgentSupport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val scheduleToolBridge: AgentScheduleToolBridge,
    private val conversationIdProvider: suspend (String) -> Long?,
) : AgentSupport {
    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo = AgentInfo(
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

    override suspend fun createSession(
        sessionParameters: SessionCreationParameters,
    ): AgentSession {
        val models = loadXiaowanModels()
        return XiaowanAgentSession(
            context = context,
            scope = scope,
            scheduleToolBridge = scheduleToolBridge,
            conversationIdProvider = conversationIdProvider,
            availableModels = models.available,
            configuredModelId = models.configuredModelId,
            sessionId = SessionId(UUID.randomUUID().toString()),
        )
    }

    private suspend fun loadXiaowanModels(): XiaowanModels {
        val binding = SceneModelBindingStore.getBinding("scene.dispatch.model")
            ?: throw IllegalStateException(
                "The scene Provider/model binding is required for Xiaowan ACP"
            )
        val profile = binding.providerProfileId.let(ModelProviderConfigStore::getProfile)
            ?: PlatformAiProvisioner.officialProfileOrNull()
                ?.takeIf { OmniOfficialProvider.isOfficialProfile(binding.providerProfileId) }
            ?: throw IllegalStateException(
                "The configured scene Provider is unavailable: ${binding.providerProfileId}"
            )
        val models = if (OmniOfficialProvider.isOfficialProfile(profile.id)) {
            PlatformAiProvisioner.ensureReadyAndGetModels("text")
        } else {
            HttpController.fetchProviderModels(
                apiBase = profile.baseUrl,
                apiKey = profile.apiKey,
                customHeaders = profile.customHeaders,
                protocolType = profile.protocolType,
                wireApi = profile.wireApi,
            )
        }
        val available = models
            .filter { it.id.isNotBlank() }
            .distinctBy(ProviderModelOption::id)
            .map { model ->
                ModelInfo(
                    ModelId(model.id),
                    model.displayName.ifBlank { model.id },
                    model.ownedBy.orEmpty(),
                    JsonNull,
                )
            }
        val configuredModelId = binding.modelId.trim()
        require(configuredModelId.isNotEmpty()) {
            "The scene Provider/model binding has no model"
        }
        require(available.any { it.modelId.value == configuredModelId }) {
            "The configured scene model is not present in the current Provider /models response: $configuredModelId"
        }
        return XiaowanModels(available = available, configuredModelId = configuredModelId)
    }
}

private data class XiaowanModels(
    val available: List<ModelInfo>,
    val configuredModelId: String,
)

private class XiaowanAgentSession(
    private val context: Context,
    scope: CoroutineScope,
    private val scheduleToolBridge: AgentScheduleToolBridge,
    private val conversationIdProvider: suspend (String) -> Long?,
    override val availableModels: List<ModelInfo>,
    private val configuredModelId: String,
    override val sessionId: SessionId,
) : AgentSession {
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
        _meta: JsonElement?,
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
        val result = executor.processUserMessage(
            userMessage = text,
            conversationHistory = emptyList(),
            runtimeContextRepository = AgentRuntimeContextRepository(context),
            attachments = promptParts.attachments,
            conversationId = conversationId,
            conversationMode = AgentConversationModePolicy.NORMAL_MODE,
            modelOverride = selectedModelOverride(),
            reasoningEffort = null,
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
        val binding = SceneModelBindingStore.getBinding("scene.dispatch.model")
            ?: return null
        val profile = binding.providerProfileId.let(ModelProviderConfigStore::getProfile)
            ?: PlatformAiProvisioner.officialProfileOrNull()
                ?.takeIf { OmniOfficialProvider.isOfficialProfile(binding.providerProfileId) }
            ?: return null
        if (!profile.isConfigured()) return null
        return cn.com.omnimind.bot.agent.AgentModelOverride(
            providerProfileId = profile.id,
            providerProfileName = profile.name,
            modelId = modelId,
            apiBase = profile.baseUrl,
            apiKey = profile.apiKey,
            customHeaders = profile.customHeaders,
            protocolType = profile.protocolType,
            wireApi = profile.wireApi,
        )
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

    private suspend fun emitAssistantSnapshotLocked(snapshot: String) {
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
                        _meta = JsonNull,
                    )
                )
            }
        )
    }

    override suspend fun onThinkingStart() {
        callbackMutex.withLock {
            thoughtSnapshot = ""
            thoughtMessageId = MessageId(UUID.randomUUID().toString())
        }
    }

    override suspend fun onThinkingUpdate(thinking: String) {
        callbackMutex.withLock {
            emitTextSnapshot(
                snapshot = thinking,
                previous = thoughtSnapshot,
                messageId = thoughtMessageId,
                emit = { delta, id ->
                    thoughtSnapshot = thinking
                    thoughtMessageId = id
                    emitUpdate(
                        SessionUpdate.AgentThoughtChunk(
                            content = ContentBlock.Text(delta),
                            messageId = id,
                            _meta = JsonNull,
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
                rawOutput = JsonPrimitive(text),
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
            emitAssistantSnapshotLocked(message)
        }
    }

    override suspend fun onClarifyRequired(question: String, missingFields: List<String>?) {
        callbackMutex.withLock {
            emitAssistantNotice(question)
        }
    }
    override suspend fun onComplete(result: AgentResult) = Unit
    override suspend fun onError(error: String) {
        callbackMutex.withLock {
            emitAssistantNotice(error)
        }
    }
    override suspend fun onPermissionRequired(missing: List<String>) {
        callbackMutex.withLock {
            val details = missing.filter(String::isNotBlank).joinToString("、")
            emitAssistantNotice(
                if (details.isEmpty()) "需要额外权限才能继续。" else "需要以下权限才能继续：$details"
            )
        }
    }

    private suspend fun emitAssistantNotice(text: String) {
        val normalized = text.trim()
        if (normalized.isEmpty()) return
        emitUpdate(
            SessionUpdate.AgentMessageChunk(
                content = ContentBlock.Text(normalized),
                messageId = MessageId(UUID.randomUUID().toString()),
                _meta = JsonNull,
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
