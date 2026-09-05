@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.bot.agent.AgentWorkspaceAttachmentSupport
import android.util.Log
import android.content.Context
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.PlatformAiProvisioner
import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.llm.SceneModelBindingEntry
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentConversationModePolicy
import cn.com.omnimind.bot.agent.AgentModelOverride
import cn.com.omnimind.bot.agent.AgentResult
import cn.com.omnimind.bot.agent.AgentRuntimeContextRepository
import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import cn.com.omnimind.bot.agent.NoOpAgentRunControl
import cn.com.omnimind.bot.agent.OmniAgentExecutor
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.resolveAgentPermissionIds
import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.CloseSessionResponse
import com.agentclientprotocol.model.DeleteSessionResponse
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.McpCapabilities
import com.agentclientprotocol.model.ModelId
import com.agentclientprotocol.model.ModelInfo
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.PromptCapabilities
import com.agentclientprotocol.model.SessionCapabilities
import com.agentclientprotocol.model.SessionCloseCapabilities
import com.agentclientprotocol.model.SessionDeleteCapabilities
import com.agentclientprotocol.model.SessionInfo
import com.agentclientprotocol.model.SessionListCapabilities
import com.agentclientprotocol.model.SessionResumeCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.SetSessionModelResponse
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.Usage
import cn.com.omnimind.bot.agent.AgentOutputKind
import cn.com.omnimind.bot.agent.AgentPermissionRequester
import com.agentclientprotocol.model.ToolKind
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.JsonRpcNotification
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
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
import java.time.Instant
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * Xiaowan is a built-in ACP Agent. The loopback transport is only the official
 * ACP SDK transport boundary; no app-private request or event protocol exists.
 */
internal class XiaowanAcpConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val scheduleToolBridge: AgentScheduleToolBridge,
    private val ensureSharedProviderBinding: suspend () -> Unit = {},
    private val conversationIdProvider: suspend (String) -> Long? = { null },
    private val isXiaowanSession: suspend (String) -> Boolean = { true },
    private val deleteSession: suspend (String) -> Unit = {},
) : AcpRuntimeConnection {
    override val materializesPromptAttachments: Boolean = true

    private lateinit var clientTransport: LoopbackTransport
    private lateinit var serverTransport: LoopbackTransport
    private lateinit var serverProtocol: Protocol
    private lateinit var serverProtocolScope: CoroutineScope
    private lateinit var agentSupport: XiaowanAgentSupport

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
        agentSupport = XiaowanAgentSupport(
                context = context,
                scope = scope,
                scheduleToolBridge = scheduleToolBridge,
                ensureSharedProviderBinding = ensureSharedProviderBinding,
                conversationIdProvider = conversationIdProvider,
                isXiaowanSession = isXiaowanSession,
                deleteSessionCallback = deleteSession,
                requestPermission = { sessionId, toolCallId, title, detail ->
                    requestClientPermission(
                        protocol = serverProtocol,
                        sessionId = sessionId,
                        toolCallId = toolCallId,
                        title = title,
                        detail = detail,
                    )
                },
            )
        Agent(serverProtocol, agentSupport)
        return clientTransport
    }

    override suspend fun start() {
        serverProtocol.start()
        serverTransport.start()
    }

    override fun diagnosticSummary(): String = ""

    override suspend fun sendRawMessage(line: String) {
        check(::clientTransport.isInitialized && clientTransport.started) {
            "ACP loopback transport is not connected."
        }
        val message = Json.decodeFromString(
            JsonRpcMessage.serializer(),
            line.trim()
        )
        check(message is JsonRpcNotification) {
            "Xiaowan raw ACP message must be a notification."
        }
        clientTransport.send(message)
    }

    override fun exitDescription(exitCode: Int?): String =
        "Built-in Xiaowan ACP Agent closed before initialize completed"

    override suspend fun close() {
        if (::agentSupport.isInitialized) agentSupport.closeAllSessions()
        if (::serverProtocol.isInitialized) serverProtocol.close()
        if (::serverProtocolScope.isInitialized) serverProtocolScope.cancel()
        if (::clientTransport.isInitialized) clientTransport.close()
        if (::serverTransport.isInitialized) serverTransport.close()
    }

    private suspend fun requestClientPermission(
        protocol: Protocol,
        sessionId: String,
        toolCallId: String,
        title: String,
        detail: String,
    ): Boolean {
        val response = protocol.sendRequestRaw(
            MethodName("session/request_permission"),
            jsonObjectFromMap(
                mapOf(
                    "sessionId" to sessionId,
                    "toolCall" to mapOf(
                        "toolCallId" to toolCallId,
                        // ACP has no separate human-readable permission
                        // message field. The tool-call title is the standard
                        // place for the client to show the exact operation.
                        "title" to title,
                        "kind" to "execute",
                        "status" to "pending",
                        "rawInput" to mapOf(
                            "detail" to detail.ifBlank { title },
                        ),
                    ),
                    "options" to listOf(
                        mapOf(
                            "optionId" to "allow_once",
                            "name" to "允许一次",
                            "kind" to "allow_once",
                        ),
                        mapOf(
                            "optionId" to "reject_once",
                            "name" to "拒绝",
                            "kind" to "reject_once",
                        ),
                    ),
                )
            ),
            SessionId(sessionId),
        )
        return isAllowedAcpPermissionOutcome(response)
    }

    private companion object {
        private const val TAG = "XiaowanAcpConnection"
    }
}

/**
 * ACP reports both a terminal outcome and the selected option. `selected`
 * alone is not approval: a client selecting `reject_once` also returns a
 * selected outcome. Fail closed unless the selected option is one of the
 * explicit allow choices we advertised above.
 */
internal fun isAllowedAcpPermissionOutcome(response: JsonElement?): Boolean {
    val outcome = (response as? JsonObject)?.get("outcome") as? JsonObject
        ?: return false
    val state = outcome["outcome"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.lowercase()
    if (state != "selected") return false
    val optionId = outcome["optionId"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.lowercase()
        ?: return false
    return optionId == "allow_once" || optionId == "allow_always"
}

private class XiaowanAgentSupport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val scheduleToolBridge: AgentScheduleToolBridge,
    private val ensureSharedProviderBinding: suspend () -> Unit,
    private val conversationIdProvider: suspend (String) -> Long?,
    private val isXiaowanSession: suspend (String) -> Boolean,
    private val deleteSessionCallback: suspend (String) -> Unit,
    private val requestPermission: suspend (String, String, String, String) -> Boolean,
) : AgentSupport {
    private companion object {
        private const val TAG = "XiaowanAcpConnection"
    }

    private val activeSessions = ConcurrentHashMap<String, XiaowanAgentSession>()
    private val profileStore = AcpAgentProfileStore(context)

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo {
        // Provider/model resolution is owned by Dispatch Model. A persisted
        // scene binding is only an optional override; the editing Provider
        // and its verified catalog are enough to initialize ACP.
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
                capabilities = xiaowanAgentCapabilities(),
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
        validateXiaowanSessionParameters(sessionParameters)
        return createXiaowanSession(
            sessionId = SessionId(UUID.randomUUID().toString()),
            sessionParameters = sessionParameters,
        )
    }

    private suspend fun createXiaowanSession(
        sessionId: SessionId,
        sessionParameters: SessionCreationParameters,
        requirePersistedSession: Boolean = false,
    ): AgentSession {
        if (requirePersistedSession) {
            val conversationId = conversationIdProvider(sessionId.value)
            require(conversationId != null) {
                "Xiaowan ACP session does not exist: ${sessionId.value}"
            }
        }
        val models = loadXiaowanModels()
        val saved = profileStore.sessionConfiguration(sessionId.value)
        val selectedModel = saved["model"]
            ?.takeIf { saved["providerProfileId"] == models.providerProfileId }
            ?: models.configuredModelId
        val mcpSession = XiaowanMcpSession.open(
            context = context,
            scope = scope,
            sessionId = sessionId.value,
            cwd = sessionParameters.cwd,
            servers = sessionParameters.mcpServers,
        )
        lateinit var session: XiaowanAgentSession
        session = XiaowanAgentSession(
            context = context,
            scope = scope,
            scheduleToolBridge = scheduleToolBridge,
            conversationIdProvider = conversationIdProvider,
            availableModels = (models.available + ModelInfo(ModelId(selectedModel), selectedModel))
                .distinctBy { it.modelId },
            configuredModelId = selectedModel,
            providerProfile = models.providerProfile,
            sessionId = sessionId,
            requestPermission = requestPermission,
            mcpSession = mcpSession,
            onClosed = { closedSessionId ->
                activeSessions.remove(closedSessionId, session)
            },
        )
        saved["reasoning_effort"]?.let { effort ->
            runCatching { session.setConfigOption(com.agentclientprotocol.model.SessionConfigId("reasoning_effort"), com.agentclientprotocol.model.SessionConfigOptionValue.StringValue(effort), JsonNull) }
        }
        activeSessions.remove(sessionId.value)?.close(JsonNull)
        activeSessions[sessionId.value] = session
        return session
    }

    override suspend fun listSessions(
        cwd: String?,
        additionalDirectories: List<String>?,
        _meta: JsonElement?
    ): Sequence<SessionInfo> {
        val requestedCwd = normalizeXiaowanCwd(cwd)
        val result = buildList {
            cn.com.omnimind.baselib.database.DatabaseHelper
                .getAllAgentSessionBindings()
                .filter { requestedCwd == null || normalizeXiaowanCwd(it.cwd) == requestedCwd }
                .forEach { binding ->
                if (!isXiaowanSession(binding.threadId)) return@forEach
                val conversation = cn.com.omnimind.baselib.database.DatabaseHelper
                    .getConversationById(binding.conversationId) ?: return@forEach
                add(SessionInfo(
                    sessionId = SessionId(binding.threadId),
                    cwd = binding.cwd,
                    title = conversation.title,
                    // SessionInfo.updatedAt is an RFC 3339 timestamp on the
                    // ACP wire, not the Room millisecond value used by the
                    // local conversation table.
                    updatedAt = Instant.ofEpochMilli(conversation.updatedAt).toString(),
                    // Xiaowan currently has one managed workspace and does
                    // not persist ACP additional-directory grants. Returning
                    // the caller's request here used to fabricate state that
                    // the session never applied.
                    additionalDirectories = emptyList(),
                ))
            }
        }
        return result.asSequence()
    }

    override suspend fun deleteSession(
        sessionId: SessionId,
        _meta: JsonElement?
    ): DeleteSessionResponse {
        activeSessions.remove(sessionId.value)?.close(JsonNull)
        if (isXiaowanSession(sessionId.value)) {
            deleteSessionCallback(sessionId.value)
        }
        // ACP deletion is intentionally idempotent: deleting an unknown or
        // already-deleted session succeeds without fabricating history.
        return DeleteSessionResponse(JsonNull)
    }

    override suspend fun loadSession(
        sessionId: SessionId,
        sessionParameters: SessionCreationParameters
    ): AgentSession {
        validateXiaowanSessionParameters(sessionParameters)
        activeSessions[sessionId.value]?.let { existing ->
            existing.refreshModels()
            return existing
        }
        return createXiaowanSession(
            sessionId = sessionId,
            sessionParameters = sessionParameters,
            requirePersistedSession = true,
        )
    }

    override suspend fun resumeSession(
        sessionId: SessionId,
        sessionParameters: SessionCreationParameters
    ): AgentSession {
        validateXiaowanSessionParameters(sessionParameters)
        return createXiaowanSession(
            sessionId = sessionId,
            sessionParameters = sessionParameters,
            requirePersistedSession = true,
        )
    }

    override suspend fun forkSession(
        sessionId: SessionId,
        sessionParameters: SessionCreationParameters
    ): AgentSession {
        validateXiaowanSessionParameters(sessionParameters)
        return createXiaowanSession(
            sessionId = SessionId(UUID.randomUUID().toString()),
            sessionParameters = sessionParameters,
        )
    }

    suspend fun closeAllSessions() {
        val sessions = activeSessions.values.toList()
        activeSessions.clear()
        sessions.forEach { session ->
            runCatching { session.close(JsonNull) }
        }
    }

    private fun validateXiaowanSessionParameters(parameters: SessionCreationParameters) {
        val requestedCwd = normalizeXiaowanCwd(parameters.cwd).orEmpty()
        require(requestedCwd.isEmpty() || requestedCwd == "/workspace") {
            "Xiaowan ACP only supports cwd=/workspace; requested cwd=$requestedCwd"
        }
        require(parameters.additionalDirectories.orEmpty().isEmpty()) {
            "Xiaowan ACP does not support additionalDirectories yet"
        }
    }

    /** ACP clients commonly add a trailing slash when serializing cwd. */
    private fun normalizeXiaowanCwd(cwd: String?): String? {
        val normalized = cwd?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        if (normalized == "/") return normalized
        return normalized.trimEnd('/').ifEmpty { "/" }
    }

    private suspend fun loadXiaowanModels(): XiaowanModels {
        var existingBinding = SceneModelBindingStore.getBinding("scene.dispatch.model")
        if (!hasUsableSharedProviderBinding(existingBinding)) {
            // The in-process Xiaowan adapter does not run the external
            // Harness preparation callback. Re-read the persisted binding at
            // the model boundary, but never discover a model here.
            ensureSharedProviderBinding()
            existingBinding = SceneModelBindingStore.getBinding("scene.dispatch.model")
        }
        val usableBinding = existingBinding?.takeIf(::hasUsableSharedProviderBinding)
            ?: throw IllegalStateException(
                "Dispatch Model has no selected Provider/model. " +
                    "Open Provider settings and choose a model before starting ACP."
            )
        val profile = resolveDispatchAgentProviderProfile(
            boundProviderProfileId = usableBinding.providerProfileId,
            configuredProfile = usableBinding.providerProfileId
                .let(ModelProviderConfigStore::getProfile),
            editingProfile = ModelProviderConfigStore.getEditingProfile(),
            officialProfile = PlatformAiProvisioner.officialProfileOrNull(),
        ) ?: throw IllegalStateException(
            "Dispatch Model Provider is not configured. Configure the default Provider and retry."
        )
        val startedAtNanos = System.nanoTime()
        val catalog = ModelProviderConfigStore.cachedModels(context, profile)
        val boundModels = buildXiaowanModelsFromBinding(usableBinding, catalog)
        boundModels?.let {
            val resolved = it.copy(providerProfile = profile.toSessionSnapshot())
            Log.i(
                TAG,
                "ACP timing agent=xiaowan stage=model_ready source=binding " +
                    "elapsedMs=${elapsedMillis(startedAtNanos)}"
            )
            return resolved
        }
        throw IllegalStateException(
            "Dispatch Model has no usable model. Choose a model in Provider settings first."
        )
    }
}

private fun hasUsableSharedProviderBinding(binding: SceneModelBindingEntry?): Boolean {
    val profile = binding
        ?.providerProfileId
        ?.let(ModelProviderConfigStore::getProfile)
    return hasUsableSharedProviderBinding(binding, profile)
}

internal fun hasUsableSharedProviderBinding(
    binding: SceneModelBindingEntry?,
    profile: ModelProviderProfile?,
): Boolean {
    if (binding == null || binding.providerProfileId.isBlank() || binding.modelId.isBlank()) {
        return false
    }
    return profile != null && profile.baseUrl.isNotBlank()
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

/**
 * Xiaowan sessions own an immutable Provider snapshot. Reuse is safe only
 * while both the scene binding and the complete Provider profile still match;
 * comparing only Provider/model ids kept old API keys and base URLs alive
 * after a profile was edited in Settings.
 */
internal fun canReuseXiaowanModels(
    binding: SceneModelBindingEntry?,
    profile: ModelProviderProfile,
    cached: XiaowanModels,
): Boolean {
    return binding?.providerProfileId?.trim() == cached.providerProfileId &&
        binding.modelId.trim() == cached.configuredModelId &&
        profile.toSessionSnapshot() == cached.providerProfile
}

internal fun buildXiaowanModelsFromBinding(
    binding: SceneModelBindingEntry?,
    catalog: List<ProviderModelOption> = emptyList(),
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
    val available = buildList {
        add(ModelInfo(ModelId(modelId), modelId, "", JsonNull))
        catalog.asSequence()
            .filter { it.id.trim().isNotEmpty() && it.id.trim() != modelId }
            .forEach { option ->
                add(
                    ModelInfo(
                        ModelId(option.id.trim()),
                        option.displayName.ifBlank { option.id.trim() },
                        option.ownedBy.orEmpty(),
                        JsonNull,
                    )
                )
            }
    }
    return XiaowanModels(
        available = available,
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
    override var availableModels: List<ModelInfo>,
    private val configuredModelId: String,
    private val providerProfile: ModelProviderProfile,
    override val sessionId: SessionId,
    private val requestPermission: suspend (String, String, String, String) -> Boolean,
    private val mcpSession: XiaowanMcpSession,
    private val onClosed: (String) -> Unit,
) : AgentSession {
    private companion object {
        private const val TAG = "XiaowanAcpConnection"
    }

    private val messages = mutableListOf<ChatCompletionMessage>()
    private val promptMutex = Mutex()
    @Volatile
    private var activePromptJob: Job? = null
    private val closed = AtomicBoolean(false)
    private val sessionConfig = XiaowanSessionConfig(availableModels, configuredModelId)
    private val selectedModelId: String get() = sessionConfig.model
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
                    "parentConversationMode" to AgentConversationModePolicy.AGENT_MODE
                )
            }
        },
        sessionCapabilityModules = listOfNotNull(mcpSession.capabilityModule),
    )

    override suspend fun prompt(
        content: List<ContentBlock>,
        _meta: JsonElement?,
    ): Flow<Event> = channelFlow {
        check(!closed.get()) { "Xiaowan ACP session is closed: ${sessionId.value}" }
        var promptJob: Job? = null
        try {
            promptMutex.withLock {
        // Keep the flow producer alive while the prompt worker is cancelled.
        // The ACP session/cancel callback must be able to receive the official
        // PromptResponse(CANCELLED) instead of cancelling this channelFlow
        // producer and leaking JobCancellationException into the dispatcher.
        val worker = launch {
            try {
        val rawPromptParts = buildXiaowanPromptParts(content)
        // ACP owns the prompt boundary. Materialize image content here so the
        // Agent always has a stable workspace path, while the provider policy
        // can still receive the inline image when the route supports it. The
        // shared HTTP client removes an unsupported image only on a definitive
        // pre-output 400, preserving the same turn for workspace fallback.
        val promptParts = rawPromptParts.copy(
            attachments = AgentWorkspaceAttachmentSupport.prepareAttachmentsForRuntime(
                context = context,
                taskId = "acp-${sessionId.value}-${UUID.randomUUID()}",
                rawAttachments = rawPromptParts.attachments,
            )
        )
        val text = promptParts.text
        require(text.isNotEmpty() || promptParts.attachments.isNotEmpty()) {
            "Xiaowan ACP prompt is empty"
        }
        val conversationId = conversationIdProvider(sessionId.value)
        val streamBridge = XiaowanAcpEventBridge(
        ) { update ->
            // AgentCallback can arrive from provider/tool worker coroutines.
            // `flow { emit(...) }` is not thread-safe and drops the whole turn
            // with Flow invariant violations when two stream callbacks race.
            // channelFlow serializes the hand-off while preserving every ACP
            // session/update event.
            send(Event.SessionUpdateEvent(update))
        }
        Log.i(
            TAG,
            "prompt session=${sessionId.value} conversationId=${conversationId ?: "unbound"} " +
                "inMemoryMessages=${messages.size} historySource=" +
                if (conversationId != null) "conversation" else "session_memory"
        )
        val requestedConversationMode = (_meta as? JsonObject)
            ?.get("conversationMode")
            ?.jsonPrimitive
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        // The durable Conversation is authoritative when an older caller
        // omits local ACP metadata. Otherwise a session/prompt silently
        // falls back to `normal` and reads an empty bucket from an Agent
        // conversation, even though the database contains the full history.
        val persistedConversationMode = conversationId
            ?.let { id ->
                runCatching {
                    cn.com.omnimind.baselib.database.DatabaseHelper
                        .getConversationById(id)
                        ?.mode
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }.getOrNull()
            }
        val conversationMode = persistedConversationMode
            ?: requestedConversationMode
            ?: AgentConversationModePolicy.AGENT_MODE
        Log.i(
            TAG,
            "prompt context conversation=${conversationId ?: "unbound"} " +
                "conversationMode=$conversationMode " +
                "modeSource=${if (persistedConversationMode != null) "conversation" else "acp_meta_or_agent_default"}"
        )
        // No Provider-declared reasoning enum is available. Do not invent one
        // or override the model's own thinking default.
        val reasoningEffort = sessionConfig.requestEffort
        val terminalEnvironment = xiaowanTerminalEnvironmentFromMeta(_meta)
        val result = executor.processUserMessage(
            userMessage = text,
            conversationHistory = emptyList(),
            runtimeContextRepository = AgentRuntimeContextRepository(context),
            attachments = promptParts.attachments,
            conversationId = conversationId,
            conversationMode = conversationMode,
            modelOverride = selectedModelOverride(),
            reasoningEffort = reasoningEffort,
            terminalEnvironment = terminalEnvironment,
            callback = streamBridge,
            runControl = NoOpAgentRunControl,
            permissionRequester = AgentPermissionRequester { toolCallId, title, detail ->
                streamBridge.emitToolPending(toolCallId, title, detail)
                requestPermission(sessionId.value, toolCallId, title, detail)
            },
            historyMessagesOverride = messages.toList().takeIf { conversationId == null },
        )
        if (result is AgentResult.Error && result.exception is CancellationException) {
            send(
                Event.PromptResponseEvent(
                    PromptResponse(stopReason = StopReason.CANCELLED)
                )
            )
            return@launch
        }
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
        val successfulResult = result
        // The executor reports cumulative snapshots through AgentCallback. The
        // bridge already converted those snapshots to ACP chunks; this final
        // call only fills a gap when a provider returned content without any
        // callback update, and is de-duplicated by the same bridge.
        streamBridge.emitAssistantSnapshot(answer)
        send(
            Event.PromptResponseEvent(
                PromptResponse(
                    stopReason = acpStopReasonForFinishReason(
                        successfulResult.response.finishReason
                    ),
                    usage = successfulResult.toAcpUsage(),
                    _meta = JsonNull,
                )
            )
        )
            } catch (_: CancellationException) {
                // Cancellation is the official ACP prompt terminal. Keep the
                // flow alive long enough to deliver it to the ClientSession.
                runCatching {
                    send(
                        Event.PromptResponseEvent(
                            PromptResponse(stopReason = StopReason.CANCELLED)
                        )
                    )
                }
            }
        }
        // The cancellation handle belongs to the prompt that owns the
        // session mutex. It points at the worker, not the channelFlow producer,
        // so cancelling a turn cannot abort the ACP protocol dispatcher.
        promptJob = worker
        activePromptJob = worker
        worker.join()
            }
        } finally {
            if (activePromptJob === promptJob) {
                activePromptJob = null
            }
        }
    }

    override suspend fun cancel() {
        // session/cancel is handled by the ACP protocol dispatcher. Cancel the
        // prompt job without injecting a custom exception into that dispatcher:
        // the prompt collector observes normal coroutine cancellation and
        // projects the official cancel result through the existing lifecycle.
        // Propagating a CancellationException from this callback can abort the
        // Android process while the JSON-RPC request is being handled.
        activePromptJob?.cancelAndJoin()
    }

    override suspend fun close(_meta: JsonElement?): CloseSessionResponse {
        if (closed.compareAndSet(false, true)) {
            cancel()
            mcpSession.close()
            onClosed(sessionId.value)
        }
        return CloseSessionResponse(JsonNull)
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
        setConfigOption(com.agentclientprotocol.model.SessionConfigId("model"),
            com.agentclientprotocol.model.SessionConfigOptionValue.StringValue(modelId.value), _meta)
        return SetSessionModelResponse(JsonNull)
    }

    suspend fun refreshModels() {
        val catalog = if (cn.com.omnimind.baselib.llm.OmniOfficialProvider.isOfficialProfile(providerProfile.id)) {
            PlatformAiProvisioner.refreshAndGetModels(null)
        } else {
            cn.com.omnimind.assists.controller.http.HttpController.fetchProviderModels(
                apiBase = providerProfile.baseUrl, apiKey = providerProfile.apiKey,
                customHeaders = providerProfile.customHeaders, protocolType = providerProfile.protocolType,
                wireApi = providerProfile.wireApi,
            )
        }
        availableModels = (catalog.map { ModelInfo(ModelId(it.id), it.displayName.ifBlank { it.id }) } +
            ModelInfo(ModelId(selectedModelId), selectedModelId)).distinctBy { it.modelId }
        sessionConfig.replaceModels(availableModels)
        Log.i(TAG, "ACP model catalog refreshed count=${availableModels.size}")
    }

    override val configOptions: List<com.agentclientprotocol.model.SessionConfigOption>
        get() = sessionConfig.options

    override suspend fun setConfigOption(
        configId: com.agentclientprotocol.model.SessionConfigId,
        value: com.agentclientprotocol.model.SessionConfigOptionValue,
        _meta: JsonElement?,
    ): com.agentclientprotocol.model.SetSessionConfigOptionResponse {
        check(activePromptJob?.isActive != true) { "Session configuration can only change while idle" }
        val updated = XiaowanSessionConfig(availableModels, selectedModelId)
        updated.set("reasoning_effort", com.agentclientprotocol.model.SessionConfigOptionValue.StringValue(sessionConfig.effort))
        updated.set(configId.value, value)
        AcpAgentProfileStore(context).saveSessionConfiguration(sessionId.value, mapOf(
            "providerProfileId" to providerProfile.id,
            "model" to updated.model,
            "reasoning_effort" to updated.effort,
        ))
        sessionConfig.set(configId.value, value)
        return com.agentclientprotocol.model.SetSessionConfigOptionResponse(configOptions)
    }

    private fun selectedModelOverride(): cn.com.omnimind.bot.agent.AgentModelOverride? {
        val modelId = selectedModelId.trim()
        if (modelId.isEmpty()) return null
        if (!providerProfile.isConfigured()) return null
        return AgentModelOverride.fromProviderProfile(
            profile = providerProfile,
            modelId = modelId,
        )
    }
}

internal fun AgentResult.Success.toAcpUsage(): Usage? {
    // AgentOrchestrator reports prompt_tokens as the complete input total,
    // while ACP Usage.inputTokens is the uncached portion. Keep the cache
    // counters separate so AcpSessionUpdateMapper can add them exactly once.
    val prompt = (latestPromptTokens ?: response.latestPromptTokens)
        ?.coerceAtLeast(0)
        ?.toLong()
    val output = (completionTokens ?: response.completionTokens)
        ?.coerceAtLeast(0)
        ?.toLong()
    val cacheRead = (cachedTokens ?: response.cachedTokens)
        ?.coerceAtLeast(0)
        ?.toLong()
    val cacheWrite = (cacheCreationTokens ?: response.cacheCreationTokens)
        ?.coerceAtLeast(0)
        ?.toLong()
    val total = (totalTokens ?: response.totalTokens)
        ?.coerceAtLeast(0)
        ?.toLong()
        ?: prompt?.plus(output ?: 0)
    if (prompt == null && output == null && cacheRead == null && cacheWrite == null && total == null) {
        return null
    }
    val uncachedInput = (prompt ?: 0L) - (cacheRead ?: 0L) - (cacheWrite ?: 0L)
    return Usage(
        inputTokens = uncachedInput.coerceAtLeast(0L),
        outputTokens = output ?: 0,
        totalTokens = total ?: 0,
        thoughtTokens = null,
        cachedReadTokens = cacheRead,
        cachedWriteTokens = cacheWrite,
        _meta = JsonNull,
    )
}

internal fun xiaowanAgentCapabilities(): AgentCapabilities = AgentCapabilities(
    // ACP v1 session/load requires history replay before its response settles.
    // Xiaowan restores durable context through resume but does not yet provide
    // that replay boundary, so load must not be advertised.
    loadSession = false,
    promptCapabilities = PromptCapabilities(
        // The shared executor accepts image and workspace resources, but it
        // has no ACP audio route.
        audio = false,
        image = true,
        embeddedContext = true,
    ),
    mcpCapabilities = McpCapabilities(
        http = true,
        sse = true,
    ),
    sessionCapabilities = SessionCapabilities(
        list = SessionListCapabilities(),
        // The local app can copy its Conversation after a fork, but the Agent
        // itself does not clone context for arbitrary ACP clients yet.
        fork = null,
        resume = SessionResumeCapabilities(),
        delete = SessionDeleteCapabilities(),
        close = SessionCloseCapabilities(),
    ),
)


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
                    // All image forms are materialized by the ACP prompt
                    // boundary before the provider request. This includes
                    // data-only images: AgentWorkspaceAttachmentSupport can
                    // persist their dataUrl and expose a stable read path.
                    // Keep the inline visual input enabled because the ACP
                    // capability advertised by Xiaowan is image=true.
                    put("sendToModel", true)
                    if (data.isNotEmpty()) put("dataUrl", dataUrl)
                    if (uri.isNotEmpty()) {
                        put("url", uri)
                        // Let AgentWorkspaceAttachmentSupport resolve both
                        // file:// and content:// URIs into the app workspace.
                        put("path", uri)
                    }
                }
            }
            is ContentBlock.Audio -> {
                // Xiaowan advertises promptCapabilities.audio=false. Do not
                // turn an unsupported ACP block into an attachment that the
                // shared executor may ignore; that would make the user think
                // the model received audio when it did not. The caller gets a
                // typed turn failure and can choose a provider with audio
                // input instead.
                throw UnsupportedOperationException(
                    "Xiaowan ACP does not support audio prompt content"
                )
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
                    if (isImage) {
                        // Resource links follow the same provider-independent
                        // image path as ContentBlock.Image. Do not pre-fill
                        // promptPath/workspacePath, otherwise the runtime
                        // preparation step would return before copying a
                        // content:// or file:// source into workspace.
                        put("sendToModel", true)
                        put("path", if (uri.startsWith("file://")) localPath else uri)
                        if (!uri.startsWith("file://")) put("url", uri)
                    } else {
                        // Keep the official resource reference as the source
                        // of truth. Do not mark it as an already prepared
                        // prompt/workspace path: those fields bypass the
                        // single Android resource materialization boundary
                        // and leave content:// attachments unreadable by the
                        // terminal.
                        put("sendToModel", false)
                        put("path", if (uri.startsWith("file://")) localPath else uri)
                        if (!uri.startsWith("file://")) put("url", uri)
                    }
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
                            if (uri.isNotBlank()) put("path", uri)
                        }
                    } else {
                        // ACP embeddedContext is not image-only. Preserve
                        // textual blobs as prompt text and forward other
                        // blobs as an explicit attachment marker instead of
                        // silently dropping the resource.
                        val decoded = runCatching {
                            Base64.getDecoder().decode(resource.blob)
                        }.getOrNull()
                        val textual = decoded
                            ?.toString(StandardCharsets.UTF_8)
                            ?.takeIf { bytes ->
                                bytes.isNotEmpty() &&
                                    bytes.indexOf('\u0000') < 0 &&
                                    (mimeType.startsWith("text/") ||
                                        mimeType.contains("json") ||
                                        mimeType.contains("xml") ||
                                        mimeType.contains("csv") ||
                                        mimeType.contains("markdown"))
                            }
                        if (textual != null) {
                            textParts += "[embedded resource: $mimeType ${uri.ifBlank { "inline" }}]\n$textual"
                        } else {
                            attachments += buildMap<String, Any?> {
                                put("name", uri.ifBlank { "embedded-resource" })
                                put("fileName", uri.ifBlank { "embedded-resource" })
                                put("mimeType", mimeType)
                                put("isImage", false)
                                put("sendToModel", false)
                                put("dataUrl", "data:$mimeType;base64,${resource.blob}")
                                // Do not expose a synthetic path. The ACP
                                // attachment preparation boundary must
                                // materialize this blob into workspace first;
                                // `embedded:<mime>` is not readable by a
                                // provider or a tool.
                            }
                        }
                    }
                }
            }
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

internal fun xiaowanTerminalEnvironmentFromMeta(
    meta: JsonElement?,
): Map<String, String> {
    return ((meta as? JsonObject)?.get("terminalEnvironment") as? JsonObject)
        ?.mapNotNull { (key, value) ->
            value.jsonPrimitive.contentOrNull?.let { key to it }
        }
        ?.toMap()
        .orEmpty()
}

internal class XiaowanAcpEventBridge(
    private val emitUpdate: suspend (SessionUpdate) -> Unit,
) : AgentCallback {
    private val callbackMutex = Mutex()
    private var assistantSnapshot = ""
    private var thoughtSnapshot = ""
    private var assistantMessageId = MessageId(UUID.randomUUID().toString())
    private var thoughtMessageId = MessageId(UUID.randomUUID().toString())
    private var reasoningSegmentIndex = 0
    private var reasoningSegmentPending = false
    private var generationId = UUID.randomUUID().toString()
    private val toolIdsByName = mutableMapOf<String, ArrayDeque<String>>()
    private val toolTypesById = mutableMapOf<String, String?>()
    /**
     * ACP tool-call ids are the identity of one tool invocation.  A provider
     * retry or callback replay may repeat the start notification; emitting a
     * second `tool_call` for the same id creates duplicate cards and leaves
     * completion status split across two local items.
     */
    private val seenToolCallIds = mutableSetOf<String>()
    private val startedToolCallIds = mutableSetOf<String>()

    suspend fun emitAssistantSnapshot(snapshot: String) {
        callbackMutex.withLock {
            emitAssistantSnapshotLocked(snapshot)
        }
    }

    private suspend fun emitAssistantSnapshotLocked(
        snapshot: String,
        meta: JsonElement = JsonNull,
    ) {
        val snapshotReset = assistantSnapshot.isNotEmpty() &&
            !snapshot.startsWith(assistantSnapshot) &&
            !assistantSnapshot.startsWith(snapshot)
        if (snapshotReset) {
            // A provider may start a fresh cumulative snapshot after a tool
            // boundary or reconnect. Give it a new message identity so the
            // reducer cannot glue unrelated generations together. A retry
            // status is emitted only by the explicit ACP retry callback; this
            // inferred reset is not proof that a retry happened and must not
            // surface a false "connection interrupted" card.
            assistantSnapshot = ""
            assistantMessageId = MessageId(UUID.randomUUID().toString())
        }
        val emittedText = emitTextSnapshot(
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
        // The provider reports throughput only after the final cumulative
        // snapshot. Its text is usually identical to the last streamed
        // snapshot, but the metadata still has to update that same message.
        if (!emittedText && meta !is JsonNull) {
            emitAssistantStatus(meta)
        }
    }

    override suspend fun onThinkingStart() {
        callbackMutex.withLock {
            val startsAfterTool = reasoningSegmentPending
            if (thoughtSnapshot.isNotEmpty() && !startsAfterTool) {
                thoughtMessageId = MessageId(UUID.randomUUID().toString())
                reasoningSegmentIndex += 1
            }
            thoughtSnapshot = ""
            // A tool may be followed by a tool-only model round. Keep the
            // segment boundary armed, but wait for the first real reasoning
            // chunk before allocating a new message id; otherwise the shared
            // reducer creates an empty thinking card between two tools.
            reasoningSegmentPending = startsAfterTool
            if (!startsAfterTool) {
                emitUpdate(
                    SessionUpdate.AgentThoughtChunk(
                        content = ContentBlock.Text(""),
                        messageId = thoughtMessageId,
                        _meta = reasoningAcpMeta("", reasoningSegmentIndex, generationId),
                    )
                )
            }
        }
    }

    override suspend fun onThinkingUpdate(thinking: String) {
        callbackMutex.withLock {
            val displayText = reasoningDisplayText(thinking)
            if (reasoningSegmentPending) {
                thoughtMessageId = MessageId(UUID.randomUUID().toString())
                thoughtSnapshot = ""
                reasoningSegmentPending = false
                reasoningSegmentIndex += 1
            } else if (
                thoughtSnapshot.isNotEmpty() &&
                !displayText.startsWith(thoughtSnapshot) &&
                !thoughtSnapshot.startsWith(displayText)
            ) {
                // HttpAgentLlmClient may retry a transient stream internally
                // before AgentOrchestrator sees the failure. The new stream
                // can therefore reset the cumulative snapshot without an
                // onRetrying callback. Treat that reset as a new visible ACP
                // reasoning segment instead of appending another generation
                // to the old card.
                thoughtMessageId = MessageId(UUID.randomUUID().toString())
                thoughtSnapshot = ""
                reasoningSegmentIndex += 1
            }
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
                            _meta = reasoningAcpMeta(thinking, reasoningSegmentIndex, generationId),
                        )
                    )
                }
            )
        }
    }

    override suspend fun onToolCallInput(
        toolCall: cn.com.omnimind.bot.agent.AssistantToolCall,
        toolType: String?,
    ) {
        callbackMutex.withLock {
            val id = toolCall.id
            val name = toolCall.function.name
            if (id.isBlank() || name.isBlank() || startedToolCallIds.contains(id)) return@withLock
            val first = seenToolCallIds.add(id)
            if (!first && !toolTypesById.containsKey(id)) return@withLock // terminal replay
            val input = JsonPrimitive(toolCall.function.arguments)
            if (first) {
                reasoningSegmentPending = true
                toolIdsByName.getOrPut(name) { ArrayDeque() }.addLast(id)
                toolTypesById[id] = toolType
                emitUpdate(SessionUpdate.ToolCall(
                    toolCallId = ToolCallId(id),
                    title = xiaowanToolTitle(name),
                    kind = xiaowanToolKind(name, toolType),
                    status = ToolCallStatus.PENDING,
                    rawInput = input,
                ))
            } else {
                emitUpdate(SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId(id),
                    title = xiaowanToolTitle(name),
                    kind = xiaowanToolKind(name, toolType),
                    rawInput = input,
                ))
            }
        }
    }

    override suspend fun onToolCallStart(
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject,
    ) {
        // ACP requires a toolCallId for every tool lifecycle update. The
        // legacy callback has no identity, so do not mint a local UUID here;
        // an invented id cannot be matched to its completion and produces an
        // orphan card. AgentOrchestrator uses the id-aware overload below.
    }

    override suspend fun onToolCallStart(
        toolCallId: String,
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject,
    ) {
        callbackMutex.withLock {
            if (toolCallId.isNotBlank()) {
                emitToolStart(toolCallId, toolName, arguments)
            }
        }
    }

    override suspend fun onToolCallStart(
        toolCallId: String,
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject,
        toolType: String?,
    ) {
        callbackMutex.withLock {
            if (toolCallId.isNotBlank()) {
                emitToolStart(toolCallId, toolName, arguments, toolType)
            }
        }
    }

    private suspend fun emitToolStart(
        toolCallId: String,
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject,
        toolType: String? = null,
    ) {
        // Keep a per-turn tombstone as well as the active set. The active set
        // is removed after a terminal update, but a replayed start after
        // completion must remain a no-op too.
        val first = seenToolCallIds.add(toolCallId)
        if (!first && !toolTypesById.containsKey(toolCallId)) return
        if (!startedToolCallIds.add(toolCallId)) return
        // ACP keeps the event order, so the next reasoning update belongs to
        // a new visible segment after this tool call. Delay allocating the
        // new message id until that reasoning actually arrives; otherwise a
        // tool-only round would leave an empty thought card in the timeline.
        reasoningSegmentPending = true
        if (first) toolIdsByName.getOrPut(toolName) { ArrayDeque() }.addLast(toolCallId)
        toolTypesById[toolCallId] = toolType
        if (!first) {
            emitUpdate(SessionUpdate.ToolCallUpdate(
                toolCallId = ToolCallId(toolCallId),
                title = xiaowanToolTitle(toolName),
                kind = xiaowanToolKind(toolName, toolType),
                status = ToolCallStatus.IN_PROGRESS,
                rawInput = arguments,
            ))
            return
        }
        emitUpdate(
            SessionUpdate.ToolCall(
                toolCallId = ToolCallId(toolCallId),
                title = xiaowanToolTitle(toolName),
                kind = xiaowanToolKind(toolName, toolType),
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
            val ids = toolIdsByName[toolName].orEmpty().toList()
            if (ids.isEmpty()) {
                // A progress callback without a preceding tool start has no
                // ACP identity. Never manufacture one: doing so creates an
                // orphan card and makes retries appear as duplicate tools.
                return@withLock
            } else {
                // The legacy callback does not carry toolCallId. When the
                // same tool runs in parallel, selecting the last id can put
                // one call's progress on another call's card. Broadcast the
                // ambiguous progress to every matching ACP call instead of
                // corrupting one specific card. New callers must use the
                // overload that includes toolCallId.
                ids.forEach { id ->
                    emitToolProgress(id, toolName, progress, extras)
                }
            }
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
            }
            // A tool update without a preceding ACP tool_call has no valid
            // identity. Do not invent one: that creates an orphan card and
            // can make one provider retry look like a second tool execution.
            resolvedId?.takeIf(startedToolCallIds::contains)
                ?.let { emitToolProgress(it, toolName, progress, extras) }
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
                title = xiaowanToolTitle(toolName),
                kind = xiaowanToolKind(
                    toolName,
                    toolTypesById[toolCallId],
                ),
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

    suspend fun emitToolPending(
        toolCallId: String,
        title: String,
        detail: String,
    ) {
        callbackMutex.withLock {
            if (!startedToolCallIds.contains(toolCallId)) return@withLock
            emitUpdate(
                SessionUpdate.ToolCallUpdate(
                    toolCallId = ToolCallId(toolCallId),
                    title = title,
                    kind = ToolKind.EXECUTE,
                    status = ToolCallStatus.PENDING,
                    content = listOf(
                        ToolCallContent.Content(
                            ContentBlock.Text(detail.ifBlank { title })
                        )
                    ),
                    locations = emptyList(),
                    rawInput = JsonNull,
                    rawOutput = JsonNull,
                    _meta = JsonNull,
                )
            )
        }
    }

    override suspend fun onToolCallComplete(
        toolName: String,
        result: ToolExecutionResult,
    ) {
        callbackMutex.withLock {
            if (toolIdsByName[toolName].orEmpty().size > 1) {
                // A legacy callback without toolCallId is ambiguous when
                // identical tools run in parallel. Completing the last card
                // would silently corrupt the ACP timeline; the ACP-aware
                // overload must be used for this case.
                return@withLock
            }
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
        val resolvedToolCallId = toolCallId
            ?.takeIf(startedToolCallIds::contains)
            ?: return
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
                title = xiaowanToolTitle(toolName),
                kind = xiaowanToolKind(toolName, toolTypesById[resolvedToolCallId]),
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
        // Keep the identity alive until the terminal ACP update has actually
        // been emitted. Removing it before the update makes the completion
        // look orphaned to the guard above.
        finalizeToolCallId(resolvedToolCallId)
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

    private fun finalizeToolCallId(toolCallId: String) {
        toolTypesById.remove(toolCallId)
        startedToolCallIds.remove(toolCallId)
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

    override suspend fun onPromptTokenUsageChanged(
        latestPromptTokens: Int,
        promptTokenThreshold: Int?,
    ) {
        callbackMutex.withLock {
            emitAcpUsageUpdate(latestPromptTokens, promptTokenThreshold)
        }
    }

    override suspend fun onContextCompactionStateChanged(
        isCompacting: Boolean,
        latestPromptTokens: Int?,
        promptTokenThreshold: Int?,
    ) {
        // Automatic compaction is not an ACP session or turn state. Manual
        // `/compact` owns its own UI marker, so do not synthesize a private
        // presentation update for this internal callback.
    }

    override suspend fun onClarifyRequired(question: String, missingFields: List<String>?) {
        callbackMutex.withLock {
            // A normal clarification is conversational text. Only an ACP
            // elicitation request should create a structured input card.
            emitAssistantNotice(text = question)
        }
    }
    override suspend fun onComplete(result: AgentResult) {
        val success = result as? AgentResult.Success ?: return
        callbackMutex.withLock {
            // These fields were part of the old completed stream event. Keep
            // them inside the shared ACP metadata so every Harness can expose
            // the same completion semantics without reviving that event.
            val outputKind = success.outputKind
            val hasUserVisibleOutput = success.hasUserVisibleOutput
            // The stream bridge preserves exact Markdown whitespace. Do not
            // trim the final snapshot: indentation, blank lines, and code
            // fence boundaries are part of the rendered document.
            val responseText = success.response.content
            val fallbackText = if (
                outputKind == AgentOutputKind.NONE.value &&
                !hasUserVisibleOutput &&
                responseText.isEmpty() &&
                assistantSnapshot.isEmpty()
            ) {
                "暂时无法生成回复，请重试。"
            } else {
                responseText
            }
            val meta = acpPresentationMeta(
                "completion" to mapOf(
                    "success" to true,
                    "outputKind" to outputKind,
                    "hasUserVisibleOutput" to hasUserVisibleOutput,
                ),
                "usage" to mapOf(
                    "latestPromptTokens" to (
                        success.latestPromptTokens
                            ?: success.response.latestPromptTokens
                    ),
                    "promptTokenThreshold" to (
                        success.promptTokenThreshold
                            ?: success.response.promptTokenThreshold
                    ),
                    "turnUsage" to agentTurnUsageAcpPayload(success),
                )
            )
            if (fallbackText.isNotEmpty()) {
                emitAssistantSnapshotLocked(fallbackText, meta)
            } else {
                emitAssistantStatus(meta)
            }
            emitAcpUsageUpdate(
                used = success.latestPromptTokens ?: success.response.latestPromptTokens,
                size = success.promptTokenThreshold ?: success.response.promptTokenThreshold,
            )
        }
    }
    override suspend fun onError(error: String) {
        onError(error, retryable = false)
    }
    override suspend fun onError(error: String, retryable: Boolean) {
        callbackMutex.withLock {
            // Preserve partial output as failed output. ACP has no
            // client-side approximate-resume operation: a new prompt is a new
            // turn, so partial text must not create a Continue action.
            val recoveryMeta = acpPresentationMeta(
                "recovery" to mapOf(
                    "error" to error,
                    "retryable" to retryable,
                    "willRetry" to false,
                    "persistAsError" to true,
                    "errorText" to error,
                )
            )
            if (assistantSnapshot.isNotBlank()) {
                emitAssistantStatus(recoveryMeta)
            } else {
                emitAssistantNotice(error, recoveryMeta)
            }
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

    /** Emit the ACP session-level context usage update when both dimensions exist. */
    private suspend fun emitAcpUsageUpdate(used: Int?, size: Int?) {
        val normalizedUsed = used?.coerceAtLeast(0)?.toLong() ?: return
        val normalizedSize = size?.coerceAtLeast(1)?.toLong() ?: return
        emitUpdate(
            SessionUpdate.UsageUpdate(
                used = normalizedUsed,
                size = normalizedSize,
                cost = null,
                _meta = JsonNull,
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
    ): Boolean {
        val delta = acpSnapshotDelta(previous, snapshot) ?: return false
        val id = if (previous.isNotEmpty() && !snapshot.startsWith(previous)) {
            MessageId(UUID.randomUUID().toString())
        } else {
            messageId
        }
        emit(delta, id)
        return true
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
    is ToolExecutionResult.ChatMessage -> true
    is ToolExecutionResult.Clarify -> false
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

private fun reasoningAcpMeta(
    thinking: String,
    segmentIndex: Int = 0,
    generationId: String? = null,
): JsonObject {
    val reasoning = linkedMapOf<String, Any?>(
        "stage" to "thinking",
        "content" to thinking,
        "segmentIndex" to segmentIndex,
    ).apply {
        generationId?.let { put("generationId", it) }
    }
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
        // These fields were emitted by older Xiaowan providers after the
        // structured thinking body. Keep them in the ACP reasoning metadata
        // so the shared reducer can preserve the old summary/stage behavior
        // and other ACP clients can consume the same information.
        copy("summary")
        copy("stage")
        copy("phase")
    }
    return acpPresentationMeta("reasoning" to reasoning)
}

private fun agentTurnUsageAcpPayload(result: AgentResult.Success): Map<String, Any?>? {
    val promptTokens = result.latestPromptTokens ?: result.response.latestPromptTokens
    val completionTokens = result.completionTokens ?: result.response.completionTokens
    val cachedTokens = result.cachedTokens ?: result.response.cachedTokens
    val cacheWriteTokens = result.cacheCreationTokens ?: result.response.cacheCreationTokens
    val totalTokens = result.totalTokens ?: result.response.totalTokens
    val promptTokenThreshold = result.promptTokenThreshold
        ?: result.response.promptTokenThreshold
    if (
        promptTokens == null &&
        completionTokens == null &&
        cachedTokens == null &&
        cacheWriteTokens == null &&
        totalTokens == null &&
        promptTokenThreshold == null
    ) {
        return null
    }
    val prompt = promptTokens ?: 0
    val completion = completionTokens ?: 0
    val cacheRead = (cachedTokens ?: 0).coerceIn(0, prompt)
    val cacheWrite = (cacheWriteTokens ?: 0).coerceAtLeast(0)
    val total = totalTokens ?: (prompt + completion)
    return linkedMapOf(
        "ctx" to prompt,
        "in" to prompt,
        "out" to completion,
        "cache" to cacheRead,
        "totalInputTokens" to prompt,
        "uncachedInputTokens" to (prompt - cacheRead).coerceAtLeast(0),
        "cacheReadTokens" to cacheRead,
        "cacheWriteTokens" to cacheWrite,
        "promptTokens" to prompt,
        "completionTokens" to completion,
        "totalTokens" to total,
        "promptTokenThreshold" to promptTokenThreshold,
    )
}

private fun structuredReasoning(thinking: String): JsonObject? = runCatching {
    Json.parseToJsonElement(thinking) as? JsonObject
}.getOrNull()

private fun reasoningDisplayText(thinking: String): String {
    val structured = structuredReasoning(thinking)
        ?: return partialStructuredReasoningDisplayText(thinking) ?: thinking
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

/**
 * Keeps Xiaowan's cumulative structured-reasoning snapshots readable before
 * the provider has closed the JSON object. The old Flutter stream parser did
 * this incrementally; doing it at the ACP adapter boundary preserves that
 * experience without teaching the shared reducer a Xiaowan-only payload.
 */
private fun partialStructuredReasoningDisplayText(thinking: String): String? {
    val clean = thinking
        .replaceFirst(Regex("^\\s*```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*```\\s*$"), "")
        .trimStart()
    if (!clean.startsWith("{")) return null

    val lines = mutableListOf<String>()
    firstPartialJsonStringField(clean, "task_description", "taskDescription")
        ?.takeIf(String::isNotEmpty)
        ?.let(lines::add)
    partialJsonStringArray(clean, "sub_tasks", "subTasks")
        .takeIf(List<String>::isNotEmpty)
        ?.joinToString("\n") { "- $it" }
        ?.let(lines::add)
    firstPartialJsonStringField(clean, "preparation")
        ?.takeIf(String::isNotEmpty)
        ?.let(lines::add)
    return lines.joinToString("\n\n")
}

private fun firstPartialJsonStringField(source: String, vararg fieldNames: String): String? {
    for (fieldName in fieldNames) {
        val fieldIndex = source.indexOf("\"$fieldName\"")
        if (fieldIndex < 0) continue
        val colonIndex = source.indexOf(':', fieldIndex + fieldName.length + 2)
        if (colonIndex < 0) continue
        var valueStart = colonIndex + 1
        while (valueStart < source.length && source[valueStart].isWhitespace()) valueStart++
        if (valueStart >= source.length || source[valueStart] != '"') continue
        return readPartialJsonString(source, valueStart).first
    }
    return null
}

private fun partialJsonStringArray(source: String, vararg fieldNames: String): List<String> {
    val fieldIndex = fieldNames
        .asSequence()
        .map { source.indexOf("\"$it\"") }
        .firstOrNull { it >= 0 }
        ?: return emptyList()
    val arrayStart = source.indexOf('[', fieldIndex)
    if (arrayStart < 0) return emptyList()
    val items = mutableListOf<String>()
    var cursor = arrayStart + 1
    while (cursor < source.length) {
        while (cursor < source.length &&
            (source[cursor].isWhitespace() || source[cursor] == ',')
        ) cursor++
        if (cursor >= source.length || source[cursor] == ']') break
        if (source[cursor] != '"') {
            cursor++
            continue
        }
        val (value, nextCursor, closed) = readPartialJsonString(source, cursor)
        if (value.isNotEmpty()) items += value
        cursor = nextCursor
        if (!closed) break
    }
    return items
}

/** Returns decoded text, next cursor, and whether the closing quote arrived. */
private fun readPartialJsonString(source: String, openingQuote: Int): Triple<String, Int, Boolean> {
    val value = StringBuilder()
    var cursor = openingQuote + 1
    while (cursor < source.length) {
        val char = source[cursor]
        if (char == '"') {
            return Triple(value.toString(), cursor + 1, true)
        }
        if (char != '\\') {
            value.append(char)
            cursor++
            continue
        }
        if (cursor + 1 >= source.length) {
            return Triple(value.toString(), source.length, false)
        }
        val escaped = source[cursor + 1]
        value.append(
            when (escaped) {
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                else -> escaped
            }
        )
        cursor += 2
    }
    return Triple(value.toString(), source.length, false)
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
 * Projects Xiaowan's legacy tool names into the official ACP ToolKind.
 *
 * The frontend only needs the standard capability kind; it should not have to
 * recognize Xiaowan-specific names to choose a card route. Harnesses that
 * already emit ACP kinds use the same projection on the Flutter side.
 */
private fun xiaowanToolKind(toolName: String, declaredToolType: String? = null): ToolKind {
    val normalized = toolName.trim().lowercase()
    when (declaredToolType?.trim()?.lowercase()) {
        "terminal", "privileged" -> return ToolKind.EXECUTE
        "browser" -> return ToolKind.FETCH
    }
    return when {
        normalized.containsAny("delete", "remove", "unlink") -> ToolKind.DELETE
        normalized.containsAny("move", "rename") -> ToolKind.MOVE
        normalized.containsAny("edit", "write", "patch", "update_file", "apply_patch") -> ToolKind.EDIT
        normalized.containsAny("search", "grep", "rg", "find", "query") -> ToolKind.SEARCH
        normalized.containsAny("read", "view", "open_file", "list_files", "glob", "cat") -> ToolKind.READ
        normalized.containsAny("browser", "web", "fetch", "url") -> ToolKind.FETCH
        normalized.containsAny("plan", "todo", "think") -> ToolKind.THINK
        normalized.containsAny("terminal", "shell", "exec", "command", "bash", "zsh") -> ToolKind.EXECUTE
        else -> ToolKind.OTHER
    }
}

/** ACP title is user-facing; the function name remains in toolName/rawInput. */
private fun xiaowanToolTitle(toolName: String): String = when (toolName.trim()) {
    "android_privileged_action" -> "安卓高级动作"
    "android_privileged_session_start" -> "启动高权限会话"
    "android_privileged_session_exec" -> "执行高权限命令"
    "android_privileged_session_read" -> "读取高权限输出"
    "android_privileged_session_stop" -> "结束高权限会话"
    "terminal_execute", "bash" -> "执行终端命令"
    "file_read", "read" -> "读取文件"
    "file_write", "write" -> "写入文件"
    "file_edit", "edit" -> "编辑文件"
    "file_list", "glob" -> "列出文件"
    "file_search", "grep" -> "搜索文件"
    "browser_use", "webfetch" -> "浏览器操作"
    "vlm_task" -> "执行界面操作"
    else -> toolName
}

/** Maps provider finish_reason values to the finite ACP stop-reason enum. */
internal fun acpStopReasonForFinishReason(finishReason: String?): StopReason {
    return when (finishReason?.trim()?.lowercase()) {
        "length", "max_tokens", "max_output_tokens", "token_limit",
        "context_length" -> StopReason.MAX_TOKENS
        "cancel", "cancelled", "canceled", "user_cancelled" -> StopReason.CANCELLED
        "refusal", "content_filter", "safety" -> StopReason.REFUSAL
        "max_turn_requests", "turn_limit" -> StopReason.MAX_TURN_REQUESTS
        else -> StopReason.END_TURN
    }
}

private fun String.containsAny(vararg values: String): Boolean = values.any { value ->
    if (value.length <= 3) {
        Regex("(^|[^a-z0-9])${Regex.escape(value)}([^a-z0-9]|$)").containsMatchIn(this)
    } else {
        contains(value)
    }
}

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
            payload["message"] = result.message
            payload["result"] = result.message
        }
        is ToolExecutionResult.Clarify -> {
            payload["toolType"] = "clarify"
            payload["question"] = result.question
            payload["missingFields"] = result.missingFields ?: emptyList<String>()
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
            payload["previewJson"] = result.previewJson
            payload["result"] = jsonElementFromJsonText(result.previewJson)
            payload["taskId"] = result.taskId
        }
        is ToolExecutionResult.McpResult -> {
            payload["toolType"] = "mcp"
            payload["toolName"] = result.toolName
            payload["serverName"] = result.serverName
            payload["previewJson"] = result.previewJson
            payload["rawResultJson"] = result.rawResultJson
            payload["result"] = jsonElementFromJsonText(result.previewJson)
            payload["rawResult"] = jsonElementFromJsonText(result.rawResultJson)
        }
        is ToolExecutionResult.MemoryResult -> {
            payload["toolType"] = "memory"
            payload["toolName"] = result.toolName
            payload["previewJson"] = result.previewJson
            payload["rawResultJson"] = result.rawResultJson
            payload["result"] = jsonElementFromJsonText(result.previewJson)
            payload["rawResult"] = jsonElementFromJsonText(result.rawResultJson)
        }
        is ToolExecutionResult.TerminalResult -> {
            payload["toolType"] = "terminal"
            payload["toolName"] = result.toolName
            payload["previewJson"] = result.previewJson
            payload["rawResultJson"] = result.rawResultJson
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
            payload["previewJson"] = result.previewJson
            payload["rawResultJson"] = result.rawResultJson
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
            payload["previewJson"] = result.previewJson
            payload["rawResultJson"] = result.rawResultJson
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
