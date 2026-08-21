package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.setup.buildAlpinePackageInstallCommand
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.OmniOfficialProvider
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.baselib.llm.PlatformAiProvisioner
import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.agent.AgentConversationModePolicy
import cn.com.omnimind.bot.agent.AgentConversationHistoryRepository
import cn.com.omnimind.bot.agent.AgentAttachmentPromptSupport
import cn.com.omnimind.bot.agent.AgentImageAttachmentSupport
import cn.com.omnimind.bot.agent.AgentWorkspaceAttachmentSupport
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import cn.com.omnimind.bot.agent.WorkspaceScheduledTaskScheduler
import cn.com.omnimind.bot.mcp.McpServerManager
import cn.com.omnimind.bot.terminal.EmbeddedTerminalSetupManager
import cn.com.omnimind.bot.task.runtime.TaskRuntime
import cn.com.omnimind.bot.util.TaskRuntimeSettings
import com.rk.terminal.runtime.TerminalDistribution
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A scene binding is only valid for the Provider it names. Older builds could
 * The Agent scene binding is the shared Provider/model source for every ACP
 * adapter. The Provider settings page can have a different editing profile;
 * that profile must not cause ACP to discard the model selected for Agent.
 * Never borrow the scene's built-in default because it may not exist at the
 * configured Provider.
 */
internal fun resolveSharedAgentModel(
    boundProviderProfileId: String?,
    boundModel: String?
): String? {
    val normalizedBoundProviderProfileId = boundProviderProfileId?.trim().orEmpty()
    val normalizedBoundModel = boundModel?.trim().orEmpty()
    return if (
        normalizedBoundProviderProfileId.isNotEmpty() &&
        normalizedBoundModel.isNotEmpty()
    ) {
        normalizedBoundModel
    } else {
        null
    }
}

class AgentRuntimeManager private constructor(
    private val context: Context
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()
    private val threadStartMutex = Mutex()
    // Preparing an official npm ACP adapter is an installation boundary, not
    // a normal switch operation.  Serialize it so rapid agent switches cannot
    // start multiple npm/native builds against the same terminal directory.
    private val managedAcpPreparationMutex = Mutex()
    // Serializes the short prompt-start handshake. The actual turn runs in
    // the harness, but two callers must never race before the first turn id
    // has been observed and registered locally.
    private val turnStartMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bindingRepository = AgentSessionBindingRepository(appContext)
    private val historyRepository = AgentConversationHistoryRepository(appContext)
    private val remoteConfigStore = CodexRemoteBridgeConfigStore(appContext)
    private val acpAgentProfileStore = AcpAgentProfileStore(appContext)
    private val scheduledTaskScheduler by lazy {
        WorkspaceScheduledTaskScheduler(appContext)
    }
    private val xiaowanScheduleToolBridge = object : AgentScheduleToolBridge {
        override suspend fun createTask(arguments: Map<String, Any?>): Map<String, Any?> =
            scheduledTaskScheduler.upsertTask(arguments)

        override suspend fun listTasks(): List<Map<String, Any?>> =
            scheduledTaskScheduler.listTasks()

        override suspend fun updateTask(arguments: Map<String, Any?>): Map<String, Any?> =
            scheduledTaskScheduler.updateTask(arguments)

        override suspend fun deleteTask(arguments: Map<String, Any?>): Map<String, Any?> =
            mapOf(
                "deleted" to scheduledTaskScheduler.deleteTask(
                    arguments["taskId"]?.toString()
                        ?: arguments["id"]?.toString().orEmpty()
                )
            )
    }
    private val localAcpRuntime = LocalAcpRuntime(
        context = appContext,
        scope = scope,
        bindingRepository = bindingRepository,
        profileStore = acpAgentProfileStore,
        prepareLaunchEnvironment = ::prepareLocalAcpLaunch,
        buildHandoffContext = ::buildLocalAcpHandoffContext,
        scheduleToolBridge = xiaowanScheduleToolBridge,
        onMessage = ::handleServerMessage
    )
    private val activeTurnsByThreadId = ConcurrentHashMap<String, String>()
    private val pendingTurnThreads = ConcurrentHashMap.newKeySet<String>()
    private val promptRequestTurns = ConcurrentHashMap<String, Pair<String, String>>()

    private suspend fun buildLocalAcpHandoffContext(
        conversationId: Long,
        currentPrompt: String?
    ): String? {
        val promptSeed = historyRepository.buildPromptSeed(
            conversationId = conversationId,
            conversationMode = "codex"
        )
        return AgentHandoffContext.format(
            conversationId = conversationId,
            messages = promptSeed.historyMessages,
            currentPrompt = currentPrompt
        )
    }

    /**
     * One lifecycle boundary for every Agent backend. ACP, a remote Codex
     * bridge, and future Harness adapters all enter here once a turn id is
     * known, so Android background survival is not coupled to any Agent loop.
     */
    private fun trackActiveTurn(threadId: String, turnId: String) {
        val previousTurnId = activeTurnsByThreadId.put(threadId, turnId)
        if (previousTurnId == turnId) return
        previousTurnId?.let(::releaseTurnRuntime)
        TaskRuntimeSettings.onTaskStarted(appContext)
        if (!TaskRuntime.start(appContext, agentTurnRuntimeId(turnId))) {
            Log.w("AgentRuntimeManager", "Unable to acquire foreground runtime for turn=$turnId")
        }
    }

    private fun clearActiveTurn(threadId: String, expectedTurnId: String? = null) {
        val removedTurnId = if (expectedTurnId == null) {
            activeTurnsByThreadId.remove(threadId)
        } else if (activeTurnsByThreadId.remove(threadId, expectedTurnId)) {
            expectedTurnId
        } else {
            null
        }
        removedTurnId?.let(::releaseTurnRuntime)
    }

    private fun clearActiveTurns() {
        activeTurnsByThreadId.entries.toList().forEach { (threadId, turnId) ->
            if (activeTurnsByThreadId.remove(threadId, turnId)) {
                releaseTurnRuntime(turnId)
            }
        }
    }

    private fun releaseTurnRuntime(turnId: String) {
        TaskRuntime.finish(appContext, agentTurnRuntimeId(turnId))
        TaskRuntimeSettings.onTaskFinished(appContext)
    }

    @Volatile
    private var pendingThreadStartConversationId: Long? = null

    @Volatile
    private var session: RemoteCodexAppServerSession? = null
    @Volatile
    private var activeRuntime: AgentRuntimeKind? = null
    @Volatile
    private var activeLocalDistributionId: String? = null
    @Volatile
    private var eventListener: ((Map<String, Any?>) -> Unit)? = null
    private val supplementalEventListeners =
        ConcurrentHashMap<String, (Map<String, Any?>) -> Unit>()

    fun setEventListener(listener: ((Map<String, Any?>) -> Unit)?) {
        eventListener = listener
    }

    internal fun setSupplementalEventListener(
        key: String,
        listener: (Map<String, Any?>) -> Unit
    ) {
        supplementalEventListeners[key] = listener
    }

    suspend fun status(): Map<String, Any?> {
        val runtime = resolveRuntime()
        val localDistributionId = if (runtime.kind == AgentRuntimeKind.LOCAL) {
            TerminalDistribution.selected().id
        } else {
            null
        }
        val connected = if (runtime.kind == AgentRuntimeKind.LOCAL) {
            localAcpRuntime.isConnected
        } else {
            isActiveSessionFor(runtime.kind, localDistributionId)
        }
        val probe = when (runtime.kind) {
            AgentRuntimeKind.REMOTE -> probeRemoteCodex(runtime.remoteConfig)
            AgentRuntimeKind.LOCAL -> probeLocalAcpAgent()
        }
        return linkedMapOf<String, Any?>(
            "connected" to connected,
            "ready" to probe.ready,
            "version" to (
                if (runtime.kind == AgentRuntimeKind.LOCAL) {
                    localAcpRuntime.agentVersion() ?: probe.version
                } else {
                    probe.version
                }
                ),
            "error" to probe.error,
            "agentHome" to if (runtime.kind == AgentRuntimeKind.REMOTE) {
                AgentRuntimeDefaults.CODEX_HOME
            } else {
                null
            },
            "cwd" to resolveDefaultCwd(),
            "runtime" to runtime.kind.payloadValue,
            "remoteEnabled" to runtime.remoteConfig.enabled,
            "remoteBridgeUrl" to runtime.remoteConfig.bridgeUrl,
            "remoteCwd" to runtime.remoteConfig.cwd,
            "remoteConfigured" to runtime.remoteConfig.isConfigured,
            "remoteTransport" to probe.details["acpTransport"],
            "remoteActiveConnections" to probe.details["activeConnections"],
            "remoteUptimeMs" to probe.details["uptimeMs"]
        ).apply {
            if (runtime.kind == AgentRuntimeKind.LOCAL) {
                putAll(localAcpRuntime.statusPayload())
            } else {
                put("protocol", "acp")
            }
        }
    }

    suspend fun connect(): Map<String, Any?> {
        sessionMutex.withLock {
            val runtime = resolveRuntime()
            val localDistributionId = if (runtime.kind == AgentRuntimeKind.LOCAL) {
                TerminalDistribution.selected().id
            } else {
                null
            }
            if (isActiveSessionFor(runtime.kind, localDistributionId)) {
                return status()
            }
            if (runtime.kind == AgentRuntimeKind.LOCAL && localAcpRuntime.isConnected) {
                return status()
            }
            val existing = session
            existing?.disconnect()
            session = null
            activeRuntime = null
            activeLocalDistributionId = null
            clearActiveTurns()
            if (runtime.kind == AgentRuntimeKind.LOCAL) {
                val profile = acpAgentProfileStore.selected()
                Log.i(
                    "AgentRuntimeManager",
                    "Connecting selected local ACP agent id=${profile.id} command=${profile.command}"
                )
                try {
                    connectLocalAcp()
                    Log.i(
                        "AgentRuntimeManager",
                        "Connected selected local ACP agent id=${profile.id}"
                    )
                } catch (error: Throwable) {
                    Log.e(
                        "AgentRuntimeManager",
                        "Failed to connect selected local ACP agent id=${profile.id}: " +
                            (error.message ?: error.javaClass.simpleName),
                        error
                    )
                    throw error
                }
                activeRuntime = AgentRuntimeKind.LOCAL
                activeLocalDistributionId = localDistributionId
                return status()
            }
            val nextSession = RemoteCodexAppServerSession(
                scope = scope,
                onServerMessage = ::handleServerMessage,
                connectionFactory = {
                    RemoteCodexBridgeConnection(
                        config = runtime.remoteConfig,
                        scope = scope
                    )
                }
            )
            session = nextSession
            activeRuntime = runtime.kind
            activeLocalDistributionId = localDistributionId
            try {
                nextSession.start(clientVersion = BuildConfig.VERSION_NAME)
            } catch (error: Throwable) {
                if (session === nextSession) {
                    session = null
                }
                if (activeRuntime == runtime.kind) {
                    activeRuntime = null
                    activeLocalDistributionId = null
                }
                throw error
            }
        }
        return status()
    }

    suspend fun disconnect(): Map<String, Any?> {
        sessionMutex.withLock {
            session?.disconnect()
            session = null
            localAcpRuntime.disconnect()
            activeRuntime = null
            activeLocalDistributionId = null
            clearActiveTurns()
            pendingTurnThreads.clear()
            promptRequestTurns.clear()
        }
        return status()
    }

    suspend fun handleMethod(method: String, args: Map<String, Any?>): Any? {
        val canonicalArgs = AcpSessionCompatibility.canonicalize(method, args)
        if (method == "agent/config/read") {
            return readAgentConfig(canonicalArgs)
        }
        if (method == "agent/config/write") {
            return writeAgentConfig(canonicalArgs)
        }
        if (method.startsWith("agent/")) {
            val response = localAcpRuntime.handleMethod(method, canonicalArgs)
            if (method == "agent/select" && localAcpRuntime.isConnected) {
                activeRuntime = AgentRuntimeKind.LOCAL
                activeLocalDistributionId = TerminalDistribution.selected().id
            }
            return response
        }
        if (
            method == "model/list" &&
            resolveRuntime().kind == AgentRuntimeKind.LOCAL &&
            acpAgentProfileStore.list()
                .firstOrNull { it.id == localAcpRuntime.activeAgentId() }
                ?.let(AcpAgentProfileStore::usesSharedProvider) == true
        ) {
            return listAuthoritativeProviderModels()
        }
        if (resolveRuntime().kind == AgentRuntimeKind.LOCAL && method in LOCAL_ACP_METHODS) {
            val localArgs = ensureLocalAcpConnected(method, canonicalArgs)
            val response = localAcpRuntime.handleMethod(method, localArgs)
            if (
                method == "session/prompt" ||
                method == "session/cancel" ||
                method == "session/close"
            ) {
                val payload = response as? Map<*, *>
                val threadId = payload?.get("threadId")?.toString()
                    ?: payload?.get("sessionId")?.toString()
                    ?: localArgs.stringValue("threadId")
                    ?: localArgs.stringValue("sessionId")
                val turnId = payload?.get("turnId")?.toString()
                    ?: localArgs.stringValue("turnId")
                    ?: localArgs.stringValue("promptId")
                if (!threadId.isNullOrBlank()) {
                    clearActiveTurn(threadId, turnId)
                }
            }
            return response
        }
        if (resolveRuntime().kind == AgentRuntimeKind.REMOTE) {
            when (method) {
                "session/new" -> return startRemoteAcpSession(canonicalArgs)
                "session/load" -> return startRemoteAcpSession(canonicalArgs)
                "session/list" -> return mapOf("sessions" to emptyList<Any?>())
                "session/prompt" -> return promptRemoteAcpSession(canonicalArgs)
                "session/cancel" -> return cancelRemoteAcpSession(canonicalArgs)
            }
        }
        return when (method) {
            "status" -> status()
            "connect" -> connect()
            "disconnect" -> disconnect()
            // Both local and remote runtimes speak the same ACP session
            // surface. Harness-specific operations do not cross this boundary.
            "session/new" -> startThread(canonicalArgs).withAcpSessionId()
            "session/load" -> requestWithResolvedThread("thread/resume", canonicalArgs)
                .withAcpSessionId()
            "session/list" -> listThreads(canonicalArgs).withAcpSessions()
            "session/prompt" -> startTurn(canonicalArgs).withAcpSessionId()
            "session/cancel" -> interruptTurn(canonicalArgs).withAcpSessionId()
            "session/archive" -> archiveThread(canonicalArgs, archived = true)
                .withAcpSessionId()
            "session/unarchive" -> archiveThread(canonicalArgs, archived = false)
                .withAcpSessionId()
            "session/name/set" -> setThreadName(canonicalArgs).withAcpSessionId()
            "thread/start" -> startThread(args)
            "thread/resume" -> requestWithResolvedThread("thread/resume", args)
            "thread/read" -> requestWithResolvedThread("thread/read", args)
            "thread/list" -> listThreads(args)
            "thread/loaded/list" -> requestWrappedList("thread/loaded/list", args, "threads")
            "thread/archive" -> archiveThread(args, archived = true)
            "thread/unarchive" -> archiveThread(args, archived = false)
            "thread/name/set" -> setThreadName(args)
            "model/list" -> requestWrappedList(
                "model/list",
                args.ifEmpty { mapOf("limit" to 100) },
                "models"
            )
            "config/read" -> readEffectiveRunConfig()
            "collaborationMode/list" -> requestWrappedList(
                "collaborationMode/list",
                args,
                "collaborationModes"
            )
            "config/remote/read" -> readRemoteBridgeConfig()
            "config/remote/write" -> writeRemoteBridgeConfig(args)
            "config/remote/test" -> testRemoteConfig(args)
            "config/remote/fs/list" -> listRemoteDirectories(args)
            "config/remote/fs/read" -> readRemoteFile(args)
            "config/remote/fs/write" -> writeRemoteFile(args)
            "config/remote/fs/delete" -> deleteRemotePath(args)
            "config/remote/fs/move" -> moveRemotePath(args)
            "turn/start" -> startTurn(args)
            "turn/steer" -> steerTurn(args)
            "turn/interrupt" -> interruptTurn(args)
            "review/start" -> startReview(canonicalArgs)
            "account/read" -> requestAccountMethod("account/read", null)
            "account/login/start" -> requestAccountMethod(
                "account/login/start",
                args.ifEmpty { mapOf("type" to "chatgpt") }
            )
            "account/login/cancel" -> requestAccountMethod("account/login/cancel", args)
            "account/rateLimits/read" -> requestAccountMethod("account/rateLimits/read", null)
            "respondToServerRequest" -> respondToServerRequest(args)
            else -> request(method, args)
        }
    }

    private suspend fun startRemoteAcpSession(
        args: Map<String, Any?>
    ): Map<String, Any?> {
        val cwd = sanitizeAgentRuntimeAbsolutePath(args.stringValue("cwd"))
            ?: resolveDefaultCwd()
        val params = linkedMapOf<String, Any?>(
            "cwd" to cwd,
            "mcpServers" to emptyList<Any?>()
        )
        args.stringValue("model")?.let { params["model"] = it }
        args.stringValue("effort")?.let { params["reasoningEffort"] = it }
        val response = request("session/new", params)
        val payload = response as? Map<String, Any?> ?: emptyMap()
        val sessionId = extractThreadId(payload)
            ?: payload.stringValue("id")
            ?: throw IllegalStateException("ACP session/new did not return a session id.")
        return payload.withAcpSessionId().withLocalIds(
            threadId = sessionId,
            conversationId = null
        )
    }

    private suspend fun promptRemoteAcpSession(
        args: Map<String, Any?>
    ): Map<String, Any?> {
        val sessionId = args.stringValue("sessionId")
            ?: args.stringValue("threadId")
            ?: startRemoteAcpSession(args)["sessionId"]?.toString()
            ?: throw IllegalStateException("ACP session/new did not return a session id.")
        val turnId = UUID.randomUUID().toString()
        check(activeTurnsByThreadId.putIfAbsent(sessionId, turnId) == null) {
            "ACP session $sessionId already has an active turn."
        }
        val requestId = args.stringValue("requestId")
        requestId?.let { promptRequestTurns["$sessionId|$it"] = sessionId to turnId }
        return try {
            val prompt = resolveInput(args, sessionId).map { block ->
                LinkedHashMap<String, Any?>().apply {
                    block.forEach { (key, value) ->
                        if (key != "text_elements") put(key, value)
                    }
                }
            }
            val response = request(
                "session/prompt",
                mapOf("sessionId" to sessionId, "prompt" to prompt)
            )
            val payload = response as? Map<String, Any?> ?: emptyMap()
            payload.withAcpSessionId().withLocalIds(
                threadId = sessionId,
                conversationId = null,
                turnId = turnId
            ).toMutableMap().apply {
                put("completed", true)
                payload.stringValue("stopReason")?.let { put("status", it) }
            }
        } finally {
            clearActiveTurn(sessionId, turnId)
        }
    }

    private suspend fun cancelRemoteAcpSession(
        args: Map<String, Any?>
    ): Map<String, Any?> {
        val sessionId = args.stringValue("sessionId")
            ?: args.stringValue("threadId")
            ?: throw IllegalArgumentException("sessionId is required")
        val response = request("session/cancel", mapOf("sessionId" to sessionId))
        val turnId = args.stringValue("turnId") ?: activeTurnsByThreadId[sessionId]
        clearActiveTurn(sessionId, turnId)
        return (response as? Map<String, Any?> ?: emptyMap()).withAcpSessionId()
            .withLocalIds(threadId = sessionId, conversationId = null, turnId = turnId)
    }

    private suspend fun startThread(args: Map<String, Any?>): Map<String, Any?> = threadStartMutex.withLock {
        val shouldBindLocally = shouldSyncLocalThreadBindings()
        val cwd = sanitizeAgentRuntimeAbsolutePath(args.stringValue("cwd")) ?: resolveDefaultCwd()
        val conversationId = args.longValue("conversationId")
        val params = linkedMapOf<String, Any?>(
            "cwd" to cwd,
            "approvalPolicy" to (args.stringValue("approvalPolicy") ?: "on-request"),
            "sandbox" to resolveAgentSandboxMode(
                args["sandboxPolicy"] ?: buildAgentSandboxPolicy(cwd)
            )
        )
        args.stringValue("approvalsReviewer")?.let {
            params["approvalsReviewer"] = it
        }
        addAgentOptionalRunParams(params, args)
        if (shouldBindLocally && conversationId != null) {
            pendingThreadStartConversationId = conversationId
        }
        try {
            val response = request("thread/start", params) as Map<String, Any?>
            val threadId = extractThreadId(response) ?: response.stringValue("id")
            var localConversationId: Long? = null
            if (shouldBindLocally && !threadId.isNullOrBlank()) {
                localConversationId = bindingRepository.ensureBinding(
                    threadId = threadId,
                    conversationId = conversationId,
                    cwd = cwd,
                    title = extractThreadTitle(response)
                )
            }
            response.withLocalIds(threadId = threadId, conversationId = localConversationId)
        } finally {
            if (pendingThreadStartConversationId == conversationId) {
                pendingThreadStartConversationId = null
            }
        }
    }

    private suspend fun listThreads(args: Map<String, Any?>): Map<String, Any?> {
        val params = linkedMapOf<String, Any?>()
        args["cursor"]?.let { params["cursor"] = it }
        args["limit"]?.let { params["limit"] = it }
        args["sortKey"]?.let { params["sortKey"] = it }
        params["sourceKinds"] = args["sourceKinds"] ?: DEFAULT_CODEX_THREAD_SOURCE_KINDS
        val response = request("thread/list", params) as Map<String, Any?>
        if (shouldSyncLocalThreadBindings()) {
            syncThreadListResponse(response)
        }
        return response
    }

    private suspend fun requestWithResolvedThread(
        method: String,
        args: Map<String, Any?>
    ): Map<String, Any?> {
        val threadId = resolveThreadId(args)
        val params = linkedMapOf<String, Any?>("threadId" to threadId)
        if (method == "thread/read") {
            (args["includeHistory"] ?: args["includeTurns"])?.let {
                params["includeTurns"] = it
            }
        }
        val response = request(method, params) as Map<String, Any?>
        if (shouldSyncLocalThreadBindings() && (method == "thread/read" || method == "thread/resume")) {
            syncThreadListResponse(response)
        }
        if (method == "thread/read" || method == "thread/resume") {
            syncActiveTurnSnapshot(threadId, response)
        }
        val activeTurnId = activeTurnsByThreadId[threadId]
        return response.withLocalIds(
            threadId = threadId,
            conversationId = localConversationIdForThread(threadId),
            turnId = activeTurnId,
            active = if (method == "thread/read" || method == "thread/resume") {
                activeTurnId != null
            } else {
                null
            }
        )
    }

    private suspend fun archiveThread(
        args: Map<String, Any?>,
        archived: Boolean
    ): Map<String, Any?> {
        val threadId = resolveThreadId(args)
        val method = if (archived) "thread/archive" else "thread/unarchive"
        val response = request(method, mapOf("threadId" to threadId)) as Map<String, Any?>
        if (shouldSyncLocalThreadBindings()) {
            bindingRepository.setArchived(threadId, archived)
        }
        return response.withLocalIds(
            threadId = threadId,
            conversationId = localConversationIdForThread(threadId)
        )
    }

    private suspend fun setThreadName(args: Map<String, Any?>): Map<String, Any?> {
        val threadId = resolveThreadId(args)
        val name = args.stringValue("name") ?: args.stringValue("threadName") ?: ""
        val response = request(
            "thread/name/set",
            mapOf("threadId" to threadId, "name" to name)
        ) as Map<String, Any?>
        if (shouldSyncLocalThreadBindings()) {
            bindingRepository.updateTitle(threadId, name)
        }
        return response.withLocalIds(
            threadId = threadId,
            conversationId = localConversationIdForThread(threadId)
        )
    }

    private suspend fun requestWrappedList(
        method: String,
        args: Map<String, Any?>,
        listKey: String
    ): Map<String, Any?> {
        val response = request(method, if (args.isEmpty()) null else args)
        return when (response) {
            is Map<*, *> -> response.entries.associate { (key, value) -> key.toString() to value }
            is List<*> -> mapOf(listKey to response)
            else -> mapOf(listKey to emptyList<Any?>(), "raw" to response)
        }
    }

    private suspend fun startTurn(args: Map<String, Any?>): Map<String, Any?> {
        return turnStartMutex.withLock {
            val cwd = sanitizeAgentRuntimeAbsolutePath(args.stringValue("cwd"))
                ?: resolveDefaultCwd()
            var threadId = ensureThreadForTurn(args, cwd)
            val requestId = args.stringValue("requestId")
                ?.takeIf { it.isNotBlank() }
            var requestKey = requestId?.let { "$threadId|$it" }
            requestKey?.let { key ->
                promptRequestTurns[key]?.let { (knownThreadId, knownTurnId) ->
                    return@withLock mapOf(
                        "threadId" to knownThreadId,
                        "turnId" to knownTurnId
                    ).withLocalIds(
                        threadId = knownThreadId,
                        conversationId = localConversationIdForThread(knownThreadId),
                        turnId = knownTurnId
                    )
                }
            }
            check(activeTurnsByThreadId[threadId] == null) {
                "ACP session $threadId already has an active turn."
            }
            check(pendingTurnThreads.add(threadId)) {
                "ACP session $threadId already has a turn starting."
            }
            var reservedThreadId = threadId
            val params = buildTurnStartParams(
                args = args,
                cwd = cwd,
                threadId = threadId
            )
            try {
                val response = try {
                    request("turn/start", params) as Map<String, Any?>
                } catch (error: Throwable) {
                    if (!shouldRecoverMissingThread(error)) {
                        throw error
                    }
                    Log.w(
                        "AgentRuntimeManager",
                        "Agent turn/start hit a missing thread; creating a fresh thread binding."
                    )
                    val retryResponse = startThread(args + mapOf("cwd" to cwd))
                    pendingTurnThreads.remove(reservedThreadId)
                    threadId = retryResponse["threadId"]?.toString()?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: throw error
                    check(pendingTurnThreads.add(threadId)) {
                        "ACP session $threadId already has a turn starting."
                    }
                    reservedThreadId = threadId
                    params["threadId"] = threadId
                    requestKey = requestId?.let { "$threadId|$it" }
                    request("turn/start", params) as Map<String, Any?>
                }
                val turnId = extractTurnId(response)
                check(!turnId.isNullOrBlank()) {
                    "Agent turn/start did not return a turn id."
                }
                trackActiveTurn(threadId, turnId)
                requestKey?.let { key ->
                    promptRequestTurns[key] = threadId to turnId
                    if (promptRequestTurns.size > 256) {
                        promptRequestTurns.keys.firstOrNull()?.let(promptRequestTurns::remove)
                    }
                }
                response.withLocalIds(
                    threadId = threadId,
                    conversationId = localConversationIdForThread(threadId),
                    turnId = turnId
                )
            } finally {
                pendingTurnThreads.remove(reservedThreadId)
            }
        }
    }

    private suspend fun startReview(args: Map<String, Any?>): Map<String, Any?> {
        val cwd = sanitizeAgentRuntimeAbsolutePath(args.stringValue("cwd")) ?: resolveDefaultCwd()
        var threadId = ensureThreadForTurn(args, cwd)
        val params = buildReviewStartParams(
            args = args,
            threadId = threadId
        )
        val response = try {
            request(
                "thread/settings/update",
                buildAgentThreadSettingsUpdateParams(args, cwd, threadId)
            )
            request("review/start", params) as Map<String, Any?>
        } catch (error: Throwable) {
            if (!shouldRecoverMissingThread(error)) {
                throw error
            }
            Log.w(
                "AgentRuntimeManager",
                "Agent review/start hit a missing thread; creating a fresh thread binding."
            )
            val retryResponse = startThread(args + mapOf("cwd" to cwd))
            threadId = retryResponse["threadId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw error
            params["threadId"] = threadId
            request(
                "thread/settings/update",
                buildAgentThreadSettingsUpdateParams(args, cwd, threadId)
            )
            request("review/start", params) as Map<String, Any?>
        }
        val turnId = extractTurnId(response)
        if (!turnId.isNullOrBlank()) {
            trackActiveTurn(threadId, turnId)
        }
        return response.withLocalIds(
            threadId = threadId,
            conversationId = localConversationIdForThread(threadId),
            turnId = turnId
        ).withAcpSessionId()
    }

    private suspend fun steerTurn(args: Map<String, Any?>): Map<String, Any?> {
        val threadId = resolveThreadId(args)
        val expectedTurnId = args.stringValue("expectedPromptId")
            ?: args.stringValue("expectedTurnId")
            ?: args.stringValue("turnId")
            ?: activeTurnsByThreadId[threadId]
            ?: throw IllegalArgumentException("missing active Agent turn id")
        val response = request(
            "turn/steer",
            mapOf(
                "threadId" to threadId,
                "expectedTurnId" to expectedTurnId,
                "input" to resolveInput(args)
            )
        ) as Map<String, Any?>
        return response.withLocalIds(
            threadId = threadId,
            conversationId = localConversationIdForThread(threadId),
            turnId = expectedTurnId
        )
    }

    private suspend fun interruptTurn(args: Map<String, Any?>): Map<String, Any?> {
        val threadId = resolveThreadId(args)
        val turnId = args.stringValue("promptId")
            ?: args.stringValue("turnId")
            ?: activeTurnsByThreadId[threadId]
            ?: throw IllegalArgumentException("missing active Agent turn id")
        val response = request(
            "turn/interrupt",
            mapOf("threadId" to threadId, "turnId" to turnId)
        ) as Map<String, Any?>
        clearActiveTurn(threadId, turnId)
        return response.withLocalIds(
            threadId = threadId,
            conversationId = localConversationIdForThread(threadId),
            turnId = turnId
        )
    }

    private suspend fun respondToServerRequest(args: Map<String, Any?>): Map<String, Any?> {
        if (resolveRuntime().kind == AgentRuntimeKind.LOCAL) {
            return localAcpRuntime.handleMethod("respondToServerRequest", args)
                as Map<String, Any?>
        }
        val requestId = args["requestId"] ?: args["id"]
            ?: throw IllegalArgumentException("requestId is required")
        val result = args["response"] ?: args["result"]
            ?: throw IllegalArgumentException("response is required")
        ensureConnectedSession().sendResponse(requestId, result)
        return mapOf("ok" to true)
    }

    private suspend fun readRemoteBridgeConfig(): Map<String, Any?> {
        val remoteConfig = remoteConfigStore.read()
        return buildRemoteBridgeConfigPayload(
            remoteConfig = remoteConfig,
            runtime = resolveRuntime().kind.payloadValue
        )
    }

    private suspend fun readEffectiveRunConfig(): Map<String, Any?> {
        val response = request("config/read", emptyMap<String, Any?>())
        return when (response) {
            is Map<*, *> -> response.entries.associate { (key, value) ->
                key.toString() to value
            }
            else -> emptyMap()
        }
    }

    private suspend fun readAgentConfig(args: Map<String, Any?>): Map<String, Any?> {
        val agentId = args.stringValue("agentId")
            ?: throw IllegalArgumentException("agentId is required.")
        val profile = acpAgentProfileStore.list().firstOrNull { it.id == agentId }
            ?: throw IllegalArgumentException("Unknown ACP agent: $agentId")
        val harnessAdapter = AcpHarnessAdapters.forProfile(profile)
        val harnessConfigPath = harnessAdapter.launchConfigPath
        if (harnessConfigPath != null) {
            val payload = harnessAdapter.readConfigPayload(
                profileId = profile.id,
                rawConfig = readTerminalTextFile(
                    path = harnessConfigPath,
                    executorKey = harnessAdapter.launchConfigExecutorKey
                        ?: "harness-config-read-${profile.id}"
                ),
                provider = currentAgentProviderCredentials(),
                model = currentAgentBoundModel(),
            ) ?: throw UnsupportedOperationException(
                "Harness does not expose a readable configuration surface."
            )
            return payload
        }
        return when (profile.id) {
            AcpAgentProfileStore.CODEX_AGENT_ID -> {
                val configToml = readTerminalTextFile(
                    path = CODEX_CONFIG_TOML_PATH,
                    executorKey = "codex-agent-config-read"
                )
                val authJson = readTerminalTextFile(
                    path = CODEX_AUTH_JSON_PATH,
                    executorKey = "codex-agent-auth-read"
                )
                val sharedProvider = currentAgentProviderProfile()
                linkedMapOf(
                    "agentId" to profile.id,
                    "kind" to "codex",
                    "configPath" to CODEX_CONFIG_TOML_DISPLAY_PATH,
                    "authPath" to CODEX_AUTH_JSON_DISPLAY_PATH,
                    "baseUrl" to (sharedProvider?.baseUrl
                        ?: extractTomlString(configToml, "base_url").orEmpty()),
                    "model" to currentAgentBoundModel().orEmpty(),
                    "apiKey" to extractOpenAiApiKey(authJson).orEmpty()
                )
            }
            CLAUDE_CODE_AGENT_ID -> readRawAgentConfig(
                profile = profile,
                kind = "json",
                path = CLAUDE_SETTINGS_JSON_PATH,
                displayPath = CLAUDE_SETTINGS_JSON_DISPLAY_PATH
            )
            OPENCODE_AGENT_ID -> readRawAgentConfig(
                profile = profile,
                kind = "jsonc",
                path = OPENCODE_CONFIG_JSON_PATH,
                displayPath = OPENCODE_CONFIG_JSON_DISPLAY_PATH
            )
            else -> linkedMapOf(
                "agentId" to profile.id,
                "kind" to "profile"
            )
        }
    }

    private suspend fun readRawAgentConfig(
        profile: AcpAgentProfile,
        kind: String,
        path: String,
        displayPath: String
    ): Map<String, Any?> {
        val stored = readTerminalTextFile(
            path = path,
            executorKey = "agent-config-read-${profile.id}"
        )
        return linkedMapOf(
            "agentId" to profile.id,
            "kind" to kind,
            "path" to displayPath,
            "content" to stored.ifBlank { DEFAULT_EMPTY_JSON_FILE }
        )
    }

    private suspend fun writeAgentConfig(args: Map<String, Any?>): Map<String, Any?> {
        val agentId = args.stringValue("agentId")
            ?: throw IllegalArgumentException("agentId is required.")
        val profile = acpAgentProfileStore.list().firstOrNull { it.id == agentId }
            ?: throw IllegalArgumentException("Unknown ACP agent: $agentId")
        val harnessAdapter = AcpHarnessAdapters.forProfile(profile)
        val harnessConfigPath = harnessAdapter.launchConfigPath
        if (harnessConfigPath != null) {
            val content = harnessAdapter.writeConfigPayload(
                args = args,
                rawConfig = readTerminalTextFile(
                    path = harnessConfigPath,
                    executorKey = harnessAdapter.launchConfigExecutorKey
                        ?: "harness-config-read-${profile.id}"
                ),
                provider = currentAgentProviderCredentials(),
                model = currentAgentBoundModel(),
            ) ?: throw UnsupportedOperationException(
                "Harness does not expose a writable configuration surface."
            )
            requireAgentConfigSize(content)
            writeTerminalTextFile(
                path = harnessConfigPath,
                content = content,
                executorKey = "harness-config-write-${profile.id}"
            )
            localAcpRuntime.disconnect()
            clearActiveTurns()
            return readAgentConfig(mapOf("agentId" to profile.id))
        }
        when (profile.id) {
            AcpAgentProfileStore.CODEX_AGENT_ID -> {
                val baseUrl = args.stringValue("baseUrl")
                    ?: throw IllegalArgumentException("Base URL is required.")
                val model = args.stringValue("model")
                    ?: throw IllegalArgumentException("Model ID is required.")
                val apiKey = args.stringValue("apiKey")
                    ?: throw IllegalArgumentException("API Key is required.")
                val providerModelResolution = resolveCurrentProviderModelIds(
                    currentAgentProviderProfile()
                )
                val providerModels = providerModelResolution
                    ?.takeIf { it.authoritative }
                    ?.models
                    .orEmpty()
                val resolvedModel = resolveAcpLaunchModel(
                    providerModelIds = providerModels.map(ProviderModelOption::id),
                    boundModel = model
                ) ?: throw IllegalArgumentException(
                    "Model must be selected from the current Provider /models response."
                )
                writeCodexConfigFiles(
                    configToml = buildCodexConfigToml(
                        baseUrl = baseUrl,
                        model = resolvedModel,
                        wireApi = args.stringValue("wireApi") ?: OpenAiWireApi.RESPONSES,
                        modelCatalogPath = CODEX_MODEL_CATALOG_JSON_PATH
                    ),
                    authJson = buildCodexAuthJson(apiKey),
                    modelCatalogJson = buildCodexModelCatalogJson(providerModels)
                )
            }
            CLAUDE_CODE_AGENT_ID -> {
                val content = args.stringValuePreservingWhitespace("content")
                    ?.ifBlank { DEFAULT_EMPTY_JSON_FILE }
                    ?: throw IllegalArgumentException("settings.json content is required.")
                requireAgentConfigSize(content)
                runCatching {
                    require(JsonParser.parseString(content).isJsonObject)
                }.getOrElse {
                    throw IllegalArgumentException(
                        "Claude Code settings.json must contain a valid JSON object.",
                        it
                    )
                }
                writeTerminalTextFile(
                    path = CLAUDE_SETTINGS_JSON_PATH,
                    content = content,
                    executorKey = "agent-config-write-${profile.id}"
                )
            }
            OPENCODE_AGENT_ID -> {
                val content = args.stringValuePreservingWhitespace("content")
                    ?.ifBlank { DEFAULT_EMPTY_JSON_FILE }
                    ?: throw IllegalArgumentException("opencode.json content is required.")
                requireAgentConfigSize(content)
                writeTerminalTextFile(
                    path = OPENCODE_CONFIG_JSON_PATH,
                    content = content,
                    executorKey = "agent-config-write-${profile.id}"
                )
            }
            else -> throw UnsupportedOperationException(
                "Custom ACP Agent settings are stored in its launch profile."
            )
        }
        localAcpRuntime.disconnect()
        clearActiveTurns()
        return readAgentConfig(mapOf("agentId" to profile.id))
    }

    private suspend fun writeCodexConfigFiles(
        configToml: String,
        authJson: String,
        modelCatalogJson: String
    ) {
        val command = """
            set -eu
            mkdir -p ${shellQuote(AgentRuntimeDefaults.CODEX_HOME)}
            umask 077
            printf %s ${shellQuote(configToml)} > ${shellQuote(CODEX_CONFIG_TOML_PATH)}
            printf %s ${shellQuote(authJson)} > ${shellQuote(CODEX_AUTH_JSON_PATH)}
            printf %s ${shellQuote(modelCatalogJson)} > ${shellQuote(CODEX_MODEL_CATALOG_JSON_PATH)}
            chmod 600 ${shellQuote(CODEX_CONFIG_TOML_PATH)} ${shellQuote(CODEX_AUTH_JSON_PATH)} ${shellQuote(CODEX_MODEL_CATALOG_JSON_PATH)}
        """.trimIndent()
        executeAgentConfigCommand(command, "codex-agent-config-write")
    }

    private suspend fun readTerminalTextFile(
        path: String,
        executorKey: String
    ): String {
        val command = """
            set -eu
            printf '${AGENT_CONFIG_START_MARKER}\n'
            if [ -f ${shellQuote(path)} ]; then
              cat ${shellQuote(path)}
            fi
            printf '\n${AGENT_CONFIG_END_MARKER}\n'
        """.trimIndent()
        val output = executeAgentConfigCommand(command, executorKey)
        return extractMarkedBlock(
            output,
            AGENT_CONFIG_START_MARKER,
            AGENT_CONFIG_END_MARKER
        )
    }

    private suspend fun writeTerminalTextFile(
        path: String,
        content: String,
        executorKey: String
    ) {
        val parent = File(path).parent
            ?: throw IllegalArgumentException("Invalid Agent config path.")
        val command = """
            set -eu
            mkdir -p ${shellQuote(parent)}
            umask 077
            printf %s ${shellQuote(content)} > ${shellQuote(path)}
            chmod 600 ${shellQuote(path)}
        """.trimIndent()
        executeAgentConfigCommand(command, executorKey)
    }

    private suspend fun executeAgentConfigCommand(
        command: String,
        executorKey: String
    ): String {
        val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
            command = command,
            executorKey = executorKey,
            timeoutMs = 30_000L
        )
        if (!result.isOk || result.exitCode != 0) {
            throw IllegalStateException(
                result.error.ifBlank {
                    result.rawOutputPreview.ifBlank {
                        "Failed to access the Agent configuration."
                    }
                }
            )
        }
        return result.output
    }

    private suspend fun writeRemoteBridgeConfig(args: Map<String, Any?>): Map<String, Any?> {
        val remoteConfig = CodexRemoteBridgeConfig(
            enabled = args["remoteEnabled"] == true,
            bridgeUrl = args.stringValue("remoteBridgeUrl").orEmpty(),
            authToken = args.stringValue("remoteBridgeToken").orEmpty(),
            cwd = args.stringValue("remoteCwd").orEmpty()
        )
        if (remoteConfig.enabled && !remoteConfig.isConfigured) {
            throw IllegalArgumentException("Remote Codex bridge URL and cwd are required.")
        }

        val savedRemoteConfig = remoteConfigStore.write(remoteConfig)
        sessionMutex.withLock {
            session?.disconnect()
            session = null
            localAcpRuntime.disconnect()
            activeRuntime = null
            activeLocalDistributionId = null
            clearActiveTurns()
        }
        return buildRemoteBridgeConfigPayload(
            remoteConfig = savedRemoteConfig,
            runtime = resolveRuntime().kind.payloadValue
        )
    }

    private suspend fun testRemoteConfig(args: Map<String, Any?>): Map<String, Any?> {
        val remoteConfig = CodexRemoteBridgeConfig(
            enabled = true,
            bridgeUrl = args.stringValue("remoteBridgeUrl").orEmpty(),
            authToken = args.stringValue("remoteBridgeToken").orEmpty(),
            cwd = args.stringValue("remoteCwd").orEmpty()
        )
        if (!remoteConfig.isConfigured) {
            return linkedMapOf(
                "ok" to false,
                "ready" to false,
                "error" to "Remote Codex bridge URL and cwd are required.",
                "cwd" to remoteConfig.cwd
            )
        }
        val probe = probeCodexRemoteBridge(remoteConfig)
        return linkedMapOf(
            "ok" to probe.ready,
            "ready" to probe.ready,
            "version" to probe.version,
            "error" to probe.error,
            "cwd" to (probe.cwd ?: remoteConfig.cwd)
        )
    }

    private suspend fun listRemoteDirectories(args: Map<String, Any?>): Map<String, Any?> {
        val remoteConfig = remoteConfigFromArgs(args)
        val path = args.stringValue("path") ?: remoteConfig.cwd.takeIf { it.isNotBlank() }
        return listCodexRemoteBridgeDirectory(remoteConfig, path)
    }

    private suspend fun readRemoteFile(args: Map<String, Any?>): Map<String, Any?> {
        return readCodexRemoteBridgeFile(
            config = remoteConfigFromArgs(args),
            path = args.stringValue("path")
        )
    }

    private suspend fun writeRemoteFile(args: Map<String, Any?>): Map<String, Any?> {
        return writeCodexRemoteBridgeFile(
            config = remoteConfigFromArgs(args),
            path = args.stringValue("path"),
            content = args["content"]?.toString().orEmpty()
        )
    }

    private suspend fun deleteRemotePath(args: Map<String, Any?>): Map<String, Any?> {
        return deleteCodexRemoteBridgePath(
            config = remoteConfigFromArgs(args),
            path = args.stringValue("path"),
            recursive = args["recursive"] == true
        )
    }

    private suspend fun moveRemotePath(args: Map<String, Any?>): Map<String, Any?> {
        return moveCodexRemoteBridgePath(
            config = remoteConfigFromArgs(args),
            path = args.stringValue("path"),
            destinationPath = args.stringValue("destinationPath")
        )
    }

    private suspend fun remoteConfigFromArgs(args: Map<String, Any?>): CodexRemoteBridgeConfig {
        val storedConfig = remoteConfigStore.read()
        return CodexRemoteBridgeConfig(
            enabled = true,
            bridgeUrl = args.stringValue("remoteBridgeUrl") ?: storedConfig.bridgeUrl,
            authToken = args.stringValue("remoteBridgeToken") ?: storedConfig.authToken,
            cwd = args.stringValue("remoteCwd") ?: storedConfig.cwd
        )
    }

    private suspend fun buildTurnStartParams(
        args: Map<String, Any?>,
        cwd: String,
        threadId: String
    ): MutableMap<String, Any?> {
        val params = linkedMapOf<String, Any?>(
            "threadId" to threadId,
            "input" to resolveInput(args, threadId),
            "cwd" to cwd,
            "approvalPolicy" to (args.stringValue("approvalPolicy") ?: "on-request"),
            "sandboxPolicy" to (args["sandboxPolicy"] ?: buildAgentSandboxPolicy(cwd))
        )
        args.stringValue("approvalsReviewer")?.let {
            params["approvalsReviewer"] = it
        }
        addAgentOptionalRunParams(params, args)
        return params
    }

    private fun buildReviewStartParams(
        args: Map<String, Any?>,
        threadId: String
    ): MutableMap<String, Any?> {
        return linkedMapOf(
            "threadId" to threadId,
            "target" to resolveCodexReviewTarget(args["target"]),
            "delivery" to (args.stringValue("delivery") ?: "inline")
        )
    }

    private fun shouldRecoverMissingThread(error: Throwable): Boolean {
        return isRecoverableAgentThreadError(error.message.orEmpty())
    }

    private suspend fun ensureThreadForTurn(args: Map<String, Any?>, cwd: String): String {
        val explicitThreadId = args.stringValue("threadId")
            ?: args.stringValue("sessionId")
        if (!explicitThreadId.isNullOrBlank()) {
            return explicitThreadId
        }
        if (shouldSyncLocalThreadBindings()) {
            val conversationId = args.longValue("conversationId")
            if (conversationId != null) {
                val binding = bindingRepository.getBindingByConversationId(conversationId)
                if (binding != null) {
                    return binding.threadId
                }
            }
        }
        val response = startThread(args + mapOf("cwd" to cwd))
        return response["threadId"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("thread/start did not return a threadId")
    }

    private fun shouldSyncLocalThreadBindings(): Boolean {
        return activeRuntime != AgentRuntimeKind.REMOTE &&
            resolveRuntime().kind != AgentRuntimeKind.REMOTE
    }

    private suspend fun localConversationIdForThread(threadId: String): Long? {
        if (!shouldSyncLocalThreadBindings()) {
            return null
        }
        return bindingRepository.getBindingByThreadId(threadId)?.conversationId
    }

    private fun syncActiveTurnSnapshot(threadId: String, response: Map<String, Any?>) {
        val active = remoteCodexThreadActivity(response)
        val activeTurnId = extractActiveTurnId(response)
        if (active == true && !activeTurnId.isNullOrBlank()) {
            trackActiveTurn(threadId, activeTurnId)
            return
        }
        if (active == false) {
            clearActiveTurn(threadId)
        }
    }

    private suspend fun request(method: String, params: Any?): Any {
        val response = ensureConnectedSession().sendRequest(method, params)
        val error = response["error"]
        if (error != null) {
            throw IllegalStateException(error.toString())
        }
        return response["result"] ?: response
    }

    private suspend fun connectLocalAcp(
        profile: AcpAgentProfile = acpAgentProfileStore.selected()
    ) {
        require(profile.enabled) {
            "No enabled ACP Agent is selected. Enable one in Agent mode settings."
        }
        localAcpRuntime.connect(profile = profile)
    }

    private suspend fun prepareLocalAcpLaunch(
        profile: AcpAgentProfile
    ): Map<String, String> {
        val usesSharedProvider = AcpAgentProfileStore
            .officialRuntime(profile)
            ?.usesSharedProvider == true
        val sceneBinding = SceneModelBindingStore.getBinding("scene.dispatch.model")
        val sharedProviderProfile = currentAgentProviderProfile()
        val sharedProvider = currentAgentProviderCredentials()
        val boundModel = currentAgentBoundModel()
        if (usesSharedProvider) {
            val binding = sceneBinding
                ?: throw IllegalStateException(
                    "Agent Provider is not bound to scene.dispatch.model. " +
                        "Select an Agent Provider and model before switching Harness."
                )
            checkNotNull(sharedProviderProfile) {
                "The bound Agent Provider is unavailable or not configured: " +
                    binding.providerProfileId
            }
            checkNotNull(sharedProvider) {
                "The bound Agent Provider has no usable credentials: " +
                    binding.providerProfileId
            }
            checkNotNull(boundModel) {
                "The Agent Provider binding has no model: " +
                    binding.providerProfileId
            }
        }
        val providerModelResolution = if (usesSharedProvider) {
            // A persisted Agent binding is enough to launch ACP. Do not make
            // an Agent switch wait on a slow/offline /models endpoint.
            resolveCurrentProviderModelIds(
                profile = sharedProviderProfile,
                timeoutMs = AGENT_PROVIDER_MODEL_LOOKUP_TIMEOUT_MS,
            )
        } else {
            null
        }
        val providerModels = providerModelResolution
            ?.takeIf { it.authoritative }
            ?.models
            .orEmpty()
        val resolvedModel = if (usesSharedProvider) {
            val model = resolveAcpLaunchModelWithBindingFallback(
                providerModelIds = providerModels.map(ProviderModelOption::id),
                boundModel = boundModel
            )
            if (model == null) {
                throw IllegalStateException(
                    "The bound Agent model '$boundModel' is not available from " +
                        "the current Provider /models response. Refresh the Provider model list."
                )
            }
            model
        } else {
            resolveAcpLaunchModel(
                providerModelIds = providerModels.map(ProviderModelOption::id),
                boundModel = boundModel
            )
        }
        val providerModelsForAdapter = if (usesSharedProvider && providerModels.isEmpty()) {
            // A persisted binding is an explicit user choice. If /models is
            // temporarily unavailable (or unsupported), keep the ACP launch
            // usable and give adapters the minimum catalog entry they need.
            val fallbackModel = requireNotNull(resolvedModel)
            Log.w(
                "AgentRuntimeManager",
                "Provider /models unavailable for profile=${sharedProviderProfile?.id}; " +
                    "using the persisted Agent model=$fallbackModel",
            )
            listOf(ProviderModelOption(id = fallbackModel, displayName = fallbackModel))
        } else {
            providerModels
        }
        val harnessAdapter = AcpHarnessAdapters.forProfile(profile)
        val existingHarnessConfig = harnessAdapter.launchConfigPath?.let { path ->
            readTerminalTextFile(
                path = path,
                executorKey = harnessAdapter.launchConfigExecutorKey
                    ?: "harness-launch-config-read"
            )
        }.orEmpty()
        val existingOpenCodeConfig = if (profile.id == OPENCODE_AGENT_ID) {
            readTerminalTextFile(
                path = OPENCODE_CONFIG_PATH,
                executorKey = "opencode-agent-config-read"
            )
        } else {
            ""
        }
        val mapping = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = profile.id,
                provider = sharedProvider,
                model = resolvedModel,
                harnessAdapter = harnessAdapter,
            )
        )
        val mcpState = if (
            harnessAdapter.mcpTransport == AcpHarnessMcpTransport.ENVIRONMENT
        ) {
            McpServerManager.ensureRunning(appContext)
        } else {
            McpServerManager.currentState()
        }
        val harnessEnvironment = harnessAdapter.launchEnvironment(
            provider = sharedProvider,
            model = resolvedModel,
            rawConfig = existingHarnessConfig,
            mcpState = mcpState,
        ) ?: mapping.environment
        ensureManagedAcpAdapter(profile)
        // Official ACP persistence uses hard-link publication. Android's app
        // sandbox rejects hard links, so install one narrow Node compatibility
        // preload while keeping upstream ACP packages untouched.
        writeTerminalTextFile(
            path = ACP_FILESYSTEM_COMPAT_PATH,
            content = ACP_FILESYSTEM_COMPAT_SCRIPT,
            executorKey = "acp-filesystem-compat-write"
        )
        return when (profile.id) {
            AcpAgentProfileStore.CODEX_AGENT_ID -> {
                if (sharedProvider != null) {
                    writeCodexConfigFiles(
                        configToml = buildCodexConfigToml(
                            baseUrl = mapping.codexBaseUrl ?: sharedProvider.baseUrl,
                            model = mapping.codexModel
                                ?: throw IllegalStateException(
                                    "Codex 没有可用模型，拒绝写入无匹配配置。"
                                ),
                            wireApi = mapping.codexWireApi
                                ?: OpenAiWireApi.normalize(sharedProvider.wireApi),
                            modelCatalogPath = CODEX_MODEL_CATALOG_JSON_PATH
                        ),
                        authJson = buildCodexAuthJson(sharedProvider.apiKey),
                        modelCatalogJson = buildCodexModelCatalogJson(
                            providerModelsForAdapter
                        )
                    )
                }
                mapping.environment
            }
            OPENCODE_AGENT_ID -> {
                if (sharedProvider != null && mapping.openCodeModel != null) {
                    writeTerminalTextFile(
                        path = OPENCODE_CONFIG_PATH,
                        content = buildOpenCodeConfigJson(
                            model = mapping.openCodeModel,
                            baseUrl = mapping.openCodeBaseUrl ?: sharedProvider.baseUrl,
                            existingConfigJson = existingOpenCodeConfig,
                        ),
                        executorKey = "opencode-agent-config-write"
                    )
                }
                mapping.environment
            }
            else -> harnessEnvironment
        }
    }

    private fun currentAgentProviderProfile(): ModelProviderProfile? = runCatching {
        val binding = SceneModelBindingStore.getBinding("scene.dispatch.model")
            ?: return@runCatching null
        ModelProviderConfigStore.getProfile(binding.providerProfileId)
            ?.takeIf { it.baseUrl.isNotBlank() && it.apiKey.isNotBlank() }
    }.getOrNull()

    private fun currentAgentProviderCredentials(): AgentProviderCredentials? =
        currentAgentProviderProfile()
            ?.takeIf { it.apiKey.isNotBlank() }
            ?.let {
                AgentProviderCredentials(
                    baseUrl = it.baseUrl,
                    apiKey = it.apiKey,
                    wireApi = it.wireApi,
                    customHeaders = it.customHeaders,
                    protocolType = it.protocolType
                )
            }

    private fun currentAgentBoundModel(): String? = runCatching {
        val binding = SceneModelBindingStore.getBinding("scene.dispatch.model")
        val boundProfile = binding?.providerProfileId
            ?.let(ModelProviderConfigStore::getProfile)
            ?.takeIf { it.baseUrl.isNotBlank() && it.apiKey.isNotBlank() }
            ?: return@runCatching null
        resolveSharedAgentModel(
            boundProviderProfileId = binding?.providerProfileId,
            boundModel = binding?.modelId
        )
    }.getOrNull()

    private data class ProviderModelResolution(
        val models: List<ProviderModelOption>,
        val authoritative: Boolean
    ) {
        val modelIds: List<String>
            get() = models.map { it.id.trim() }.filter(String::isNotEmpty)
    }

    private suspend fun resolveCurrentProviderModelIds(
        profile: ModelProviderProfile?,
        timeoutMs: Long? = null,
    ): ProviderModelResolution? {
        profile ?: return null
        val fetched = runCatching {
            val models = if (timeoutMs == null) {
                fetchProviderModels(profile)
            } else {
                withTimeoutOrNull(timeoutMs) {
                    fetchProviderModels(profile)
                } ?: throw IllegalStateException(
                    "Provider /models lookup timed out after ${timeoutMs}ms"
                )
            }
            models.filter { it.id.trim().isNotEmpty() }
        }.onFailure { error ->
            Log.w(
                "AgentRuntimeManager",
                "Provider /models failed for profile=${profile.id}: " +
                    (error.message ?: error.javaClass.simpleName),
            )
        }.getOrNull()
        return fetched?.let { models ->
            ProviderModelResolution(
                models = models,
                authoritative = true
            )
        }
    }

    private suspend fun fetchProviderModels(
        profile: ModelProviderProfile,
    ): List<ProviderModelOption> {
        return if (OmniOfficialProvider.isOfficialProfile(profile.id)) {
            PlatformAiProvisioner.ensureReadyAndGetModels()
        } else {
            HttpController.fetchProviderModels(
                apiBase = profile.baseUrl,
                apiKey = profile.apiKey,
                customHeaders = profile.customHeaders,
                protocolType = profile.protocolType,
                wireApi = profile.wireApi
            )
        }
    }

    private suspend fun listAuthoritativeProviderModels(): Map<String, Any?> {
        val provider = currentAgentProviderProfile()
        // The ACP model/list surface is also queried during app/bootstrap
        // refreshes. Keep it bounded like launch preparation so an offline or
        // partial-connectivity device never leaves the chat waiting on /models.
        val modelResolution = resolveCurrentProviderModelIds(
            profile = provider,
            timeoutMs = AGENT_PROVIDER_MODEL_LOOKUP_TIMEOUT_MS,
        )
        return buildAuthoritativeProviderModelPayload(
            providerModelIds = modelResolution
                ?.takeIf { it.authoritative }
                ?.modelIds,
            boundModel = currentAgentBoundModel(),
        )
    }

    private suspend fun ensureManagedAcpAdapter(profile: AcpAgentProfile) =
        managedAcpPreparationMutex.withLock {
            ensureManagedAcpAdapterLocked(profile)
        }

    private suspend fun ensureManagedAcpAdapterLocked(profile: AcpAgentProfile) {
        val runtime = AcpAgentProfileStore.officialRuntime(profile) ?: return
        val packageName = runtime.managedAdapterPackage ?: return
        val previousHealth = acpAgentProfileStore.health(profile.id)
        // A successful ACP initialize is the authoritative preparation
        // result. Reusing it avoids running three terminal probes (including
        // the package/health checks) on every foreground Harness switch. An
        // explicit Agent check resets this health to `unchecked`; a missing
        // command will still be caught by LocalAcpRuntime.requireLaunchCommand
        // and invalidate the health on the next connect.
        if (shouldReuseManagedAcpPreparation(previousHealth.status, previousHealth.installed)) {
            return
        }
        val managedPackages = runtime.managedAdapterPackages
            .ifEmpty { listOf(packageName) }
        val installTargets = managedPackages.joinToString(" ") { shellQuote(it) }
        val nativeBuildPrerequisites = if (runtime.requiresNativeBuildTools) {
            MANAGED_NATIVE_BUILD_PREREQUISITES_COMMAND
        } else {
            ":"
        }
        val adapterHealthCheck = runtime.managedAdapterHealthCommand ?: ":"
        val adapterInstallCommand = runtime.managedInstallCommand
            ?: "npm install -g --prefix /root/.npm-global --no-audit --no-fund $installTargets"
        val installScript = """
            set -eu
            $nativeBuildPrerequisites
            mkdir -p /root/.npm-global/bin
            export PATH="/root/.npm-global/bin:${'$'}PATH"
            $adapterInstallCommand
            command -v ${shellQuote(profile.command)} >/dev/null 2>&1
            $adapterHealthCheck
        """.trimIndent()
        // The APK contains only this installer logic. A managed Harness may
        // publish it to its adapter-owned path; the runtime itself remains in
        // the terminal environment and is installed on explicit preparation.
        val installScriptPath = runtime.managedInstallScriptPath
        installScriptPath?.let {
            writeTerminalTextFile(
                path = it,
                content = "#!/bin/sh\n$installScript\n",
                executorKey = "harness-installer-script-write-${profile.id}"
            )
        }
        val commandAvailable = isTerminalCommandAvailable(
            command = profile.command,
            timeoutMs = MANAGED_ACP_PROBE_TIMEOUT_MS,
        )
        val allPackagesReady = managedPackages.size == 1 ||
            (commandAvailable && areManagedNpmPackagesInstalled(
                packageSpecs = managedPackages,
                timeoutMs = MANAGED_ACP_PROBE_TIMEOUT_MS,
            ))
        val adapterHealthy = runtime.managedAdapterHealthCommand
            ?.let { isTerminalShellCommandSuccessful(it, MANAGED_ACP_PROBE_TIMEOUT_MS) }
            ?: true
        // Installation/update is an explicit preparation boundary. A normal
        // Agent switch must reuse a healthy installed adapter; otherwise every
        // switch would rerun the complete DSH npm/native installation.
        if (!shouldPrepareManagedAcpAdapter(
                agentId = profile.id,
                commandAvailable = commandAvailable,
                allPackagesReady = allPackagesReady,
                adapterHealthy = adapterHealthy,
            )
        ) {
            return
        }
        val previousPreparationFailure = previousHealth.error
            ?.trim()
            ?.startsWith("Failed to prepare", ignoreCase = true) == true
        if (previousPreparationFailure) {
            throw IllegalStateException(
                "${previousHealth.error}. Open Agent settings and retry the official " +
                    "${profile.name} installation."
            )
        }
        if (!isTerminalCommandAvailable("npm", MANAGED_ACP_PROBE_TIMEOUT_MS)) {
            val terminalPackageId = managedAgentTerminalPackageId(profile)
            if (terminalPackageId == null) {
                throw IllegalStateException(
                    "npm is required to prepare the ${profile.name} ACP adapter."
                )
            }
            val bootstrap = EmbeddedTerminalSetupManager(appContext).installPackages(
                selectedPackageIds = listOf(terminalPackageId)
            )
            if (!bootstrap.success) {
                val details = bootstrap.message.ifBlank { bootstrap.output.trim() }
                throw IllegalStateException(
                    details.ifBlank {
                        "Unable to install the ${profile.name} ACP adapter prerequisites."
                    }
                )
            }
        }
        if (!isTerminalCommandAvailable("npm", MANAGED_ACP_PROBE_TIMEOUT_MS)) {
            throw IllegalStateException(
                "npm is required to prepare the ${profile.name} ACP adapter."
            )
        }
        val command = if (installScriptPath != null) {
            "sh ${shellQuote(installScriptPath)}"
        } else {
            installScript
        }
        val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
            command = command,
            executorKey = "acp-adapter-install-${profile.id}",
            timeoutMs = MANAGED_ACP_INSTALL_TIMEOUT_MS
        )
        if (!result.isOk || result.exitCode != 0) {
            val details = result.output.trim()
                .ifBlank { result.rawOutputPreview.trim() }
                .ifBlank { result.error.trim() }
                .takeLast(2_000)
            throw IllegalStateException(
                buildString {
                    append("Failed to prepare the ${profile.name} ACP adapter")
                    if (details.isNotBlank()) {
                        append(": ")
                        append(details)
                    }
                }
            )
        }
    }

    private suspend fun areManagedNpmPackagesInstalled(
        packageSpecs: List<String>,
        timeoutMs: Long = 20_000L,
    ): Boolean {
        if (packageSpecs.isEmpty()) return true
        val checks = packageSpecs.joinToString(" && ") { spec ->
            val packageName = npmPackageName(spec)
            "test -f ${shellQuote("/root/.npm-global/lib/node_modules/$packageName/package.json")}"
        }
        val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
            command = checks,
            executorKey = "acp-managed-packages-probe-${packageSpecs.hashCode()}",
            timeoutMs = timeoutMs
        )
        return result.isOk && result.exitCode == 0
    }

    private suspend fun isTerminalCommandAvailable(
        command: String,
        timeoutMs: Long = 20_000L,
    ): Boolean {
        val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
            command = "$MANAGED_NPM_PATH_PREFIX " +
                "command -v ${shellQuote(command)} >/dev/null 2>&1",
            executorKey = "acp-command-probe-${command.hashCode()}",
            timeoutMs = timeoutMs
        )
        return result.isOk && result.exitCode == 0
    }

    private suspend fun isTerminalShellCommandSuccessful(
        command: String,
        timeoutMs: Long = 20_000L,
    ): Boolean {
        val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
            command = "$MANAGED_NPM_PATH_PREFIX $command",
            executorKey = "acp-shell-health-${command.hashCode()}",
            timeoutMs = timeoutMs
        )
        return result.isOk && result.exitCode == 0
    }

    private suspend fun ensureLocalAcpConnected(
        method: String,
        args: Map<String, Any?>
    ): Map<String, Any?> {
        val chatOnly = args.stringValue("conversationMode")
            ?.equals(AgentConversationModePolicy.CHAT_ONLY_MODE, ignoreCase = true) == true
        val requestedAgentId = args.stringValue("agentId")
        val explicitThreadId = args.stringValue("threadId")
        val conversationId = args.longValue("conversationId")
        val conversationBinding = conversationId
            ?.let { bindingRepository.getBindingByConversationId(it) }
        val requestedThreadId = explicitThreadId ?: conversationBinding?.threadId
        val boundAgentId = requestedThreadId?.let {
            acpAgentProfileStore.agentIdForSession(it)
        }
        val conversationAgentId = conversationId?.let {
            acpAgentProfileStore.agentIdForConversation(it)
        }
        val selectedAgentId = acpAgentProfileStore.selected().id
        val targetAgentId = if (chatOnly) {
            // Pure chat is still ACP, but it is not an Agent/Harness session.
            // Route it through the built-in ACP provider adapter so the
            // selected Harness can never leak into the chat-only turn.
            AcpAgentProfileStore.XIAOWAN_AGENT_ID
        } else {
            requestedAgentId
                ?: boundAgentId
                ?: conversationAgentId
                ?: selectedAgentId
        }
        val targetProfile = targetAgentId?.let { agentId ->
            acpAgentProfileStore.list().firstOrNull { it.id == agentId }
                ?: throw IllegalArgumentException("Unknown ACP agent: $agentId")
        }
        if (targetProfile != null) {
            require(targetProfile.enabled) {
                "ACP agent ${targetProfile.name} is disabled."
            }
        }
        // An explicit session with an explicit Agent id but no persisted owner
        // binding is legacy/untrusted state. For a prompt, do not resume it on
        // the newly selected ACP process: create a fresh session and let the
        // normal conversation handoff carry the durable context forward.
        // Session/load remains strict about its explicit id so callers get a
        // real load error instead of silently receiving a different session.
        val unownedExplicitPrompt = method == "session/prompt" &&
            explicitThreadId != null &&
            requestedAgentId != null &&
            boundAgentId == null
        val boundThreadBelongsToAnotherAgent = explicitThreadId != null &&
            boundAgentId != null &&
            targetProfile != null &&
            boundAgentId != targetProfile.id
        val threadBelongsToAnotherAgent =
            boundThreadBelongsToAnotherAgent || unownedExplicitPrompt ||
                (chatOnly && boundAgentId != null &&
                    targetProfile != null && boundAgentId != targetProfile.id)
        if (targetProfile != null &&
            (!localAcpRuntime.isConnected ||
                localAcpRuntime.activeAgentId() != targetProfile.id)
        ) {
            check(!localAcpRuntime.hasActiveTurns()) {
                "设备当前已有其他 ACP Agent 任务，暂时不能切换 Agent。"
            }
            if (localAcpRuntime.isConnected) {
                localAcpRuntime.disconnect()
            }
            // A conversation/session may explicitly belong to a different
            // Agent than the persisted default. Connect that profile for this
            // request without changing the user's default selection. Only the
            // explicit agent/select API is allowed to mutate that preference.
            connectLocalAcp(profile = targetProfile)
        }
        if (conversationId != null && targetProfile != null) {
            acpAgentProfileStore.bindConversation(conversationId, targetProfile.id)
        }
        if (localAcpRuntime.isConnected) {
            activeRuntime = AgentRuntimeKind.LOCAL
            activeLocalDistributionId = TerminalDistribution.selected().id
            return if (threadBelongsToAnotherAgent) {
                LinkedHashMap(args).apply {
                    remove("threadId")
                    remove("sessionId")
                }
            } else {
                args
            }
        }
        connectLocalAcp()
        activeRuntime = AgentRuntimeKind.LOCAL
        activeLocalDistributionId = TerminalDistribution.selected().id
        return if (threadBelongsToAnotherAgent) {
            LinkedHashMap(args).apply {
                remove("threadId")
                remove("sessionId")
            }
        } else {
            args
        }
    }

    private suspend fun requestAccountMethod(method: String, params: Any?): Any {
        if (resolveRuntime().kind == AgentRuntimeKind.REMOTE) {
            return request(method, params)
        }
        throw UnsupportedOperationException(
            "Local authentication is managed by the selected ACP Agent. " +
                "Open its Agent configuration page to update credentials."
        )
    }

    private suspend fun ensureConnectedSession(): RemoteCodexAppServerSession {
        val runtime = resolveRuntime()
        val localDistributionId = if (runtime.kind == AgentRuntimeKind.LOCAL) {
            TerminalDistribution.selected().id
        } else {
            null
        }
        val existing = session
        if (isActiveSessionFor(runtime.kind, localDistributionId)) {
            return existing
                ?: throw IllegalStateException("Remote ACP session is unavailable.")
        }
        connect()
        return session ?: throw IllegalStateException("Remote ACP agent is not connected.")
    }

    private fun isActiveSessionFor(
        runtimeKind: AgentRuntimeKind,
        localDistributionId: String?
    ): Boolean {
        return session?.isRunning == true &&
            activeRuntime == runtimeKind &&
            (
                runtimeKind != AgentRuntimeKind.LOCAL ||
                    activeLocalDistributionId == localDistributionId
                )
    }

    private suspend fun handleServerMessage(message: Map<String, Any?>) {
        val method = extractRemoteCodexServerMethod(message)
        val explicitParams = extractRemoteCodexServerParams(message)
        val params = if (explicitParams.isNotEmpty()) {
            explicitParams
        } else {
            syntheticRemoteCodexServerParams(message, method)
        }
        val threadId = extractThreadId(message)
        // ACP session/update is session-scoped on the wire. OpenCode emits
        // valid turn-scoped updates without a turnId, while LocalAcpRuntime
        // has already reserved the active turn for the prompt collector.
        // Resolve that omission at this boundary so the shared Flutter
        // reducer receives a stable turn id and does not lose the stream.
        val turnId = extractTurnId(message)
            ?: extractActiveTurnId(message)
            ?: localAcpRuntime.activeTurnIdForSession(threadId)
        // Diagnostic: log every server-side method that reaches Kotlin so the
        // user can verify via `adb logcat -s AgentRuntimeManager:V` whether
        // commandExecution / rawResponseItem events actually arrive over the
        // bridge. If item/started events for commandExecution are missing
        // here but present in `codex app-server` stdout, the bridge is
        // dropping them; if present here but missing on Flutter side, the
        // EventChannel pipe is the problem.
        val diagItemType = (message["params"] as? Map<*, *>)
            ?.get("item")?.let { it as? Map<*, *> }
            ?.get("type")?.toString()
            ?: (params["item"] as? Map<*, *>)?.get("type")?.toString()
        Log.d(
            "AgentRuntimeManager",
            "<- method=$method itemType=$diagItemType threadId=$threadId turnId=$turnId"
        )
        val protocolEventType = if (method == "codex/event") {
            remoteCodexProtocolEventType(params)
        } else {
            ""
        }
        // A remote adapter can deliver notifications after the active-turn
        // map has already been cleared. Never let such a turn-scoped event
        // reach Flutter without an explicit turn id: the renderer would have
        // to guess and could attach old tool output to the next turn.
        if (isTurnScopedRemoteEvent(method, protocolEventType, params) &&
            turnId.isNullOrBlank()
        ) {
            Log.w(
                "AgentRuntimeManager",
                "Dropping turn-scoped event without a turn id: method=$method " +
                    "protocolEventType=$protocolEventType threadId=$threadId"
            )
            return
        }
        if (!threadId.isNullOrBlank() && !turnId.isNullOrBlank() &&
            (method == "turn/started" ||
                protocolEventType == "task_started" ||
                protocolEventType == "turn_started")) {
            trackActiveTurn(threadId, turnId)
        }
        if (!threadId.isNullOrBlank() && method == "thread/status/changed") {
            val active = remoteCodexThreadActivity(message)
            if (active == true && !turnId.isNullOrBlank()) {
                trackActiveTurn(threadId, turnId)
            } else if (active == false) {
                clearActiveTurn(threadId)
            }
        }
        if (!threadId.isNullOrBlank() &&
            (method == "turn/completed" ||
                protocolEventType == "task_complete" ||
                protocolEventType == "turn_complete" ||
                protocolEventType == "turn_aborted")) {
            clearActiveTurn(threadId, turnId)
        }
        if (!threadId.isNullOrBlank() &&
            (method == "error" || method == "turn/failed") &&
            params["willRetry"] != true) {
            // codex app-server emits top-level `error` notifications when a
            // turn fails terminally (no follow-up turn/completed will come).
            // Clear the active turn so subsequent thread/read responses
            // surface active=false to the Flutter side.
            clearActiveTurn(threadId, turnId)
        }
        if (!threadId.isNullOrBlank() && method == "thread/closed") {
            clearActiveTurn(threadId)
        }

        val eventAgentId = if (activeRuntime == AgentRuntimeKind.REMOTE) {
            AcpAgentProfileStore.CODEX_AGENT_ID
        } else {
            threadId?.let(acpAgentProfileStore::agentIdForSession)
                ?: localAcpRuntime.activeAgentId()
        }
        val eventAgentName = if (activeRuntime == AgentRuntimeKind.REMOTE) {
            "Codex"
        } else {
            acpAgentProfileStore.list()
                .firstOrNull { it.id == eventAgentId }
                ?.name
                ?: localAcpRuntime.activeAgentName()
        }
        val localConversationId = runCatching {
            syncMessage(method, message, params, threadId)
        }.onFailure { error ->
            Log.w("AgentRuntimeManager", "syncMessage failed for $method: ${error.message}")
        }.getOrNull()

        // Deliver to Flutter FIRST. The completion side effects below only run
        // for the terminal event, so anything that throws in them used to drop
        // exactly that one event while every other event sailed through —
        // leaving the turn permanently "running" in the UI.
        emitEvent(
            linkedMapOf(
                "method" to method,
                "id" to message["id"],
                "workspaceId" to RemoteCodexAppServerSession.DEFAULT_WORKSPACE_ID,
                "threadId" to threadId,
                "turnId" to turnId,
                "conversationId" to localConversationId,
                "agentId" to eventAgentId,
                "agentName" to eventAgentName,
                "params" to params,
                "message" to message
            )
        )

        if (method == "turn/completed" ||
            method == "turn/failed" ||
            protocolEventType == "task_complete" ||
            protocolEventType == "turn_complete") {
            runCatching {
                TaskRuntimeSettings.notifyTaskFinished(
                    context = appContext,
                    title = "$eventAgentName task completed",
                    message = "Tap to view the completed Agent turn.",
                    conversationId = localConversationId,
                    conversationMode = "codex"
                )
            }.onFailure { error ->
                Log.w(
                    "AgentRuntimeManager",
                    "task completion notification failed: ${error.message}"
                )
            }
        }
    }

    private fun isTurnScopedRemoteEvent(
        method: String,
        protocolEventType: String,
        params: Map<String, Any?>
    ): Boolean {
        if (method == "session/update") {
            val sessionUpdate = params.mapValue("update").stringValue("sessionUpdate")
            return sessionUpdate in setOf(
                "agent_message_chunk",
                "agent_thought_chunk",
                "tool_call",
                "tool_call_update",
                "plan"
            )
        }
        if (method.startsWith("item/") || method == "rawResponseItem/completed") {
            return true
        }
        return protocolEventType in setOf(
            "task_started",
            "turn_started",
            "agent_message_delta",
            "agent_thought_delta",
            "tool_started",
            "tool_updated",
            "tool_completed",
            "task_progress",
            "turn_progress"
        )
    }

    private suspend fun syncMessage(
        method: String,
        message: Map<String, Any?>,
        params: Map<String, Any?>,
        threadId: String?
    ): Long? {
        if (!shouldSyncLocalThreadBindings()) {
            return null
        }
        return when (method) {
            "thread/started" -> {
                val thread = params.mapValue("thread")
                val resolvedThreadId = thread.stringValue("id") ?: threadId
                if (resolvedThreadId.isNullOrBlank()) {
                    null
                } else {
                    bindingRepository.ensureBinding(
                        threadId = resolvedThreadId,
                        conversationId = pendingThreadStartConversationId,
                        cwd = sanitizeAgentRuntimeAbsolutePath(thread.stringValue("cwd"))
                            ?: sanitizeAgentRuntimeAbsolutePath(params.stringValue("cwd"))
                            ?: resolveDefaultCwd(),
                        title = extractThreadTitle(message)
                    )
                }
            }
            "thread/name/updated" -> {
                val resolvedThreadId = threadId ?: params.stringValue("threadId") ?: params.stringValue("thread_id")
                if (!resolvedThreadId.isNullOrBlank()) {
                    bindingRepository.updateTitle(
                        resolvedThreadId,
                        params.stringValue("threadName")
                            ?: params.stringValue("thread_name")
                            ?: params.stringValue("name")
                            ?: params.stringValue("title")
                    )
                    bindingRepository.getBindingByThreadId(resolvedThreadId)?.conversationId
                } else {
                    null
                }
            }
            "thread/archived" -> {
                threadId?.let {
                    bindingRepository.setArchived(it, true)
                    bindingRepository.getBindingByThreadId(it)?.conversationId
                }
            }
            "thread/unarchived" -> {
                threadId?.let {
                    bindingRepository.setArchived(it, false)
                    bindingRepository.getBindingByThreadId(it)?.conversationId
                }
            }
            else -> {
                if (!threadId.isNullOrBlank()) {
                    bindingRepository.getBindingByThreadId(threadId)?.conversationId
                } else {
                    null
                }
            }
        }
    }

    private suspend fun syncThreadListResponse(response: Map<String, Any?>) {
        collectThreadEntries(response).forEach { entry ->
            bindingRepository.ensureBinding(
                threadId = entry.threadId,
                cwd = sanitizeAgentRuntimeAbsolutePath(entry.cwd) ?: resolveDefaultCwd(),
                title = entry.title,
                archived = entry.archived
            )
        }
    }

    private fun emitEvent(event: Map<String, Any?>) {
        val listener = eventListener
        val supplementalListeners = supplementalEventListeners.values.toList()
        if (listener == null && supplementalListeners.isEmpty()) return
        mainHandler.post {
            runCatching {
                listener?.invoke(event)
            }.onFailure { error ->
                Log.w("AgentRuntimeManager", "primary event listener failed: ${error.message}")
            }
            supplementalListeners.forEach { supplemental ->
                runCatching {
                    supplemental(event)
                }.onFailure { error ->
                    Log.w(
                        "AgentRuntimeManager",
                        "supplemental event listener failed: ${error.message}"
                    )
                }
            }
        }
    }

    private suspend fun probeLocalAcpAgent(): AgentRuntimeProbe {
        val profile = acpAgentProfileStore.selected()
        if (!profile.enabled) {
            return AgentRuntimeProbe(
                ready = false,
                version = null,
                error = "No enabled ACP Agent is selected."
            )
        }
        if (profile.id == AcpAgentProfileStore.XIAOWAN_AGENT_ID) {
            return AgentRuntimeProbe(
                ready = true,
                version = BuildConfig.VERSION_NAME,
                error = null
            )
        }
        return runCatching {
            val environmentPrefix = profile.environment.entries.joinToString(" ") {
                "${it.key}=${shellQuote(it.value)}"
            }.let { if (it.isBlank()) "" else "export $it; " }
            val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
                command = "$MANAGED_NPM_PATH_PREFIX $environmentPrefix" +
                    "command -v ${shellQuote(profile.command)}",
                executorKey = "acp-agent-probe-${profile.id}",
                timeoutMs = 15_000L
            )
            val launchReady = result.isOk && result.exitCode == 0
            val healthCommand = AcpAgentProfileStore
                .officialRuntime(profile)
                ?.managedAdapterHealthCommand
            val healthResult = if (launchReady && healthCommand != null) {
                TerminalManager.getInstance(appContext).executeHiddenCommand(
                    command = "$MANAGED_NPM_PATH_PREFIX $healthCommand",
                    executorKey = "acp-agent-health-probe-${profile.id}",
                    timeoutMs = 8_000L
                )
            } else {
                null
            }
            val healthy = healthResult == null ||
                (healthResult.isOk && healthResult.exitCode == 0)
            AgentRuntimeProbe(
                ready = launchReady && healthy,
                version = null,
                error = when {
                    !launchReady -> result.error.ifBlank {
                        "ACP agent command not found: ${profile.command}"
                    }
                    !healthy -> healthResult?.error?.ifBlank {
                        "ACP adapter health check failed: ${profile.name}"
                    } ?: "ACP adapter health check failed: ${profile.name}"
                    else -> null
                }
            )
        }.getOrElse { error ->
            AgentRuntimeProbe(
                ready = false,
                version = null,
                error = error.message ?: error.javaClass.simpleName
            )
        }
    }

    private suspend fun probeRemoteCodex(config: CodexRemoteBridgeConfig): AgentRuntimeProbe {
        val probe = probeCodexRemoteBridge(config)
        return AgentRuntimeProbe(
            ready = probe.ready,
            version = probe.version,
            error = probe.error,
            details = probe.details
        )
    }

    private suspend fun resolveDefaultCwd(): String {
        val runtime = resolveRuntime()
        if (runtime.kind == AgentRuntimeKind.REMOTE) {
            return runtime.remoteConfig.cwd.trim()
        }
        return runCatching {
            val workspaceRoot = AgentWorkspaceManager.rootDirectory(appContext)
            workspaceRoot.mkdirs()
            if (workspaceRoot.exists() && workspaceRoot.isDirectory) {
                AgentRuntimeDefaults.DEFAULT_WORKSPACE_CWD
            } else {
                AgentRuntimeDefaults.FALLBACK_CWD
            }
        }.getOrNull() ?: AgentRuntimeDefaults.FALLBACK_CWD
    }

    private fun resolveRuntime(): AgentRuntime {
        val remoteConfig = remoteConfigStore.read()
        return if (remoteConfig.enabled) {
            AgentRuntime(AgentRuntimeKind.REMOTE, remoteConfig)
        } else {
            AgentRuntime(AgentRuntimeKind.LOCAL, remoteConfig)
        }
    }

    private suspend fun resolveThreadId(args: Map<String, Any?>): String {
        val explicit = args.stringValue("threadId")
            ?: args.stringValue("sessionId")
            ?: args.stringValue("thread_id")
        if (!explicit.isNullOrBlank()) {
            return explicit
        }
        if (!shouldSyncLocalThreadBindings()) {
            throw IllegalArgumentException("threadId is required for remote Codex sessions")
        }
        val conversationId = args.longValue("conversationId")
            ?: throw IllegalArgumentException("threadId or conversationId is required")
        val binding = bindingRepository.getBindingByConversationId(conversationId)
            ?: throw IllegalArgumentException("Codex thread binding not found for conversation $conversationId")
        return binding.threadId
    }

    private suspend fun resolveInput(
        args: Map<String, Any?>,
        threadId: String? = null
    ): List<Map<String, Any?>> {
        val rawInput = args["input"]
        if (rawInput is List<*>) {
            return rawInput
                .mapNotNull { it as? Map<*, *> }
                .map { entry ->
                    LinkedHashMap<String, Any?>().apply {
                        entry.entries.forEach { (key, value) ->
                            put(key.toString(), value)
                        }
                        if (this["type"]?.toString() == "text" && !containsKey("text_elements")) {
                            put("text_elements", emptyList<Map<String, Any?>>())
                        }
                    }
                }
                .filter { it.isNotEmpty() }
        }
        val text = args.stringValue("text") ?: args.stringValue("message") ?: ""
        val attachments = prepareAgentAttachments(
            args = args,
            threadId = threadId
        )
        return buildAgentTurnInput(
            text = text,
            attachments = attachments,
            preferLocalImagePaths = resolveRuntime().kind == AgentRuntimeKind.LOCAL
        )
    }

    private suspend fun prepareAgentAttachments(
        args: Map<String, Any?>,
        threadId: String?
    ): List<Map<String, Any?>> {
        val rawAttachments = (args["attachments"] as? List<*>)
            ?.mapNotNull { item ->
                (item as? Map<*, *>)?.entries?.associate { (key, value) ->
                    key.toString() to value
                }
            }
            .orEmpty()
        if (rawAttachments.isEmpty()) {
            return emptyList()
        }
        val runtime = resolveRuntime()
        if (runtime.kind == AgentRuntimeKind.LOCAL) {
            val taskId = threadId
                ?: args.stringValue("conversationId")
                ?: "agent-${System.currentTimeMillis()}"
            return AgentWorkspaceAttachmentSupport.prepareAttachmentsForRuntime(
                context = appContext,
                taskId = taskId,
                rawAttachments = rawAttachments
            )
        }
        return rawAttachments.map { attachment ->
            prepareRemoteCodexAttachment(runtime.remoteConfig, attachment)
        }
    }

    private suspend fun prepareRemoteCodexAttachment(
        remoteConfig: CodexRemoteBridgeConfig,
        attachment: Map<String, Any?>
    ): Map<String, Any?> {
        if (AgentImageAttachmentSupport.isImageAttachment(attachment)) {
            return attachment
        }
        val existingRemotePath = attachment.stringValue("promptPath")
            ?: attachment.stringValue("workspacePath")
        if (!existingRemotePath.isNullOrBlank()) {
            return attachment
        }
        val sourcePath = attachment.stringValue("path").orEmpty()
        val source = File(sourcePath)
        require(source.exists() && source.isFile) {
            "Codex attachment is not readable: $sourcePath"
        }
        val response = uploadCodexRemoteBridgeAttachment(
            config = remoteConfig,
            source = source,
            name = attachment.stringValue("name")
                ?: attachment.stringValue("fileName")
                ?: source.name
        )
        require(response["ok"] == true) {
            val error = response["error"]?.toString().orEmpty()
            if (error.contains("HTTP 404", ignoreCase = true)) {
                "Remote file attachments require codex-bridge 0.1.5 or newer."
            } else {
                error.ifBlank {
                    "Remote Codex attachment upload failed. Update codex-bridge and retry."
                }
            }
        }
        val remotePath = response.stringValue("path")
            ?: throw IllegalStateException("Remote Codex attachment upload returned no path.")
        return LinkedHashMap(attachment).apply {
            put("promptPath", remotePath)
            put("workspacePath", remotePath)
        }
    }

    private data class AgentRuntimeProbe(
        val ready: Boolean,
        val version: String?,
        val error: String?,
        val details: Map<String, Any?> = emptyMap()
    )

    companion object {
        @Volatile
        private var INSTANCE: AgentRuntimeManager? = null

        fun getInstance(context: Context): AgentRuntimeManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgentRuntimeManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}

private data class AgentRuntime(
    val kind: AgentRuntimeKind,
    val remoteConfig: CodexRemoteBridgeConfig
)

private enum class AgentRuntimeKind(val payloadValue: String) {
    LOCAL("local"),
    REMOTE("remote")
}

// A foreground Agent switch must not leave the chat in an indefinite
// Processing state when the device is offline or the npm registry is stalled.
// The official installer remains unchanged; this only bounds one preparation
// attempt and lets the UI surface its error so the user can retry explicitly.
private const val MANAGED_ACP_INSTALL_TIMEOUT_MS = 8 * 60 * 1_000L
private const val MANAGED_ACP_PROBE_TIMEOUT_MS = 5_000L
private const val AGENT_PROVIDER_MODEL_LOOKUP_TIMEOUT_MS = 3_000L

internal fun shouldPrepareManagedAcpAdapter(
    agentId: String,
    commandAvailable: Boolean,
    allPackagesReady: Boolean,
    adapterHealthy: Boolean,
): Boolean {
    // Keep the agent id in the decision signature because preparation is
    // agent-specific, but reuse the same readiness contract for all managed
    // adapters. Updating DSH is not part of every foreground switch.
    return !(commandAvailable && allPackagesReady && adapterHealthy)
}

internal fun shouldReuseManagedAcpPreparation(
    healthStatus: String,
    installed: Boolean?,
): Boolean {
    return healthStatus == AcpAgentHealth.STATUS_ONLINE && installed == true
}

private const val MANAGED_NPM_PATH_PREFIX =
    "PATH=\"/root/.npm-global/bin:\$PATH\"; export PATH;"
internal val MANAGED_NATIVE_BUILD_PREREQUISITES_COMMAND = """
    ensure_native_build_tools() {
      if command -v make >/dev/null 2>&1 &&
         command -v c++ >/dev/null 2>&1 &&
         command -v python3 >/dev/null 2>&1; then
        return 0
      fi
      if command -v apk >/dev/null 2>&1; then
        ${buildAlpinePackageInstallCommand(listOf("build-base", "python3"))}
      elif command -v apt-get >/dev/null 2>&1; then
        apt-get update
        DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends build-essential python3
      else
        echo "This Harness requires make, a C++ compiler, and Python 3 for its native dependencies." >&2
        return 1
      fi
    }
    ensure_native_build_tools
""".trimIndent()
private const val OPENCODE_CONFIG_PATH = "/root/.config/opencode/opencode.json"
private val LOCAL_ACP_METHODS = setOf(
    "session/new",
    "session/load",
    "session/list",
    "session/prompt",
    "session/cancel",
    "session/set_mode",
    "session/set_config_option",
    "session/close",
    "session/archive",
    "session/unarchive",
    "session/name/set",
    "thread/archive",
    "thread/unarchive",
    "thread/name/set",
    "model/list",
    "config/read",
    "config/set",
    "collaborationMode/list",
    "review/start",
    "respondToServerRequest"
)

private data class RemoteCodexThreadListEntry(
    val threadId: String,
    val cwd: String?,
    val title: String?,
    val archived: Boolean?
)

internal fun Map<String, Any?>.withLocalIds(
    threadId: String?,
    conversationId: Long?,
    turnId: String? = null,
    active: Boolean? = null
): Map<String, Any?> {
    val result = LinkedHashMap(this)
    if (!threadId.isNullOrBlank()) {
        result["threadId"] = threadId
    }
    if (conversationId != null) {
        result["conversationId"] = conversationId
    }
    if (!turnId.isNullOrBlank()) {
        result["turnId"] = turnId
        if (active == true) {
            result["activeTurnId"] = turnId
        }
    }
    if (active != null) {
        result["active"] = active
    }
    return result
}

internal fun Map<String, Any?>.withAcpSessionId(): Map<String, Any?> {
    val sessionId = stringValue("sessionId") ?: stringValue("threadId")
    val promptId = stringValue("promptId") ?: stringValue("turnId")
    val result = LinkedHashMap(this).apply {
        if (!sessionId.isNullOrBlank()) {
            put("sessionId", sessionId)
        }
        if (!promptId.isNullOrBlank()) {
            put("promptId", promptId)
        }
    }
    return AcpSessionCompatibility.withLegacyIds(result)
}

internal fun Map<String, Any?>.withAcpSessions(): Map<String, Any?> {
    val sessions = this["sessions"] ?: this["threads"]
    val normalized = (sessions as? List<*>)?.map { entry ->
        val map = (entry as? Map<*, *>)?.entries?.associate { (key, value) ->
            key.toString() to value
        } ?: return@map entry
        AcpSessionCompatibility.withLegacyIds(
            LinkedHashMap(map).apply {
                val sessionId = stringValue("sessionId") ?: stringValue("threadId")
                if (!sessionId.isNullOrBlank()) put("sessionId", sessionId)
            }
        )
    }
    return LinkedHashMap(this).apply {
        if (normalized != null) put("sessions", normalized)
    }
}

internal fun sanitizeAgentRuntimeAbsolutePath(raw: String?): String? {
    val source = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return source
        .lineSequence()
        .map { it.trim() }
        .lastOrNull { line ->
            line.startsWith("/") && line.none { char -> char.isISOControl() }
        }
}

internal fun buildAgentTextInput(text: String): List<Map<String, Any?>> {
    val trimmed = text.trim()
    require(trimmed.isNotEmpty()) { "Codex turn input is empty" }
    return listOf(
        linkedMapOf(
            "type" to "text",
            "text" to trimmed,
            "text_elements" to emptyList<Map<String, Any?>>()
        )
    )
}

internal fun buildAgentTurnInput(
    text: String,
    attachments: List<Map<String, Any?>>,
    preferLocalImagePaths: Boolean
): List<Map<String, Any?>> {
    val input = mutableListOf<Map<String, Any?>>()
    val nonImageAttachments = mutableListOf<Map<String, Any?>>()
    attachments.forEach { attachment ->
        if (!AgentImageAttachmentSupport.isImageAttachment(attachment)) {
            nonImageAttachments += attachment
            return@forEach
        }
        if (!AgentAttachmentPromptSupport.shouldSendAttachmentToModel(attachment)) {
            return@forEach
        }
        val path = agentAttachmentRuntimePath(attachment)
        if (preferLocalImagePaths && path != null) {
            input += linkedMapOf(
                "type" to "localImage",
                "path" to path
            )
            return@forEach
        }
        val imageUrl = AgentImageAttachmentSupport.resolveImageAttachmentUrl(attachment)
        if (imageUrl.startsWith("data:", ignoreCase = true)) {
            input += linkedMapOf(
                "type" to "image",
                "url" to imageUrl
            )
            return@forEach
        }
        if (!preferLocalImagePaths && path != null) {
            input += linkedMapOf(
                "type" to "localImage",
                "path" to path
            )
            return@forEach
        }
        val name = attachment.stringValue("name")
            ?: attachment.stringValue("fileName")
            ?: "image"
        throw IllegalArgumentException("Codex image attachment is not readable: $name")
    }
    val textWithAttachmentPaths = AgentAttachmentPromptSupport.buildUserMessageText(
        text = text,
        attachments = nonImageAttachments
    ).trim()
    if (textWithAttachmentPaths.isNotEmpty()) {
        input += buildAgentTextInput(textWithAttachmentPaths)
    }
    require(input.isNotEmpty()) { "Codex turn input is empty" }
    return input
}

private fun agentAttachmentRuntimePath(attachment: Map<String, Any?>): String? {
    return sequenceOf(
        attachment.stringValue("promptPath"),
        attachment.stringValue("workspacePath"),
        attachment.stringValue("path")
    )
        .filterNotNull()
        .map(String::trim)
        .firstOrNull(::isAgentAbsoluteAttachmentPath)
}

private fun isAgentAbsoluteAttachmentPath(path: String): Boolean {
    return path.startsWith("/") ||
        path.startsWith("\\\\") ||
        Regex("^[A-Za-z]:[\\\\/].+").matches(path)
}

internal fun buildAgentSandboxPolicy(cwd: String): Map<String, Any?> {
    val writableRoot = sanitizeAgentRuntimeAbsolutePath(cwd) ?: AgentRuntimeDefaults.FALLBACK_CWD
    return linkedMapOf(
        "type" to "workspaceWrite",
        "writableRoots" to listOf(writableRoot),
        "networkAccess" to true,
        "excludeTmpdirEnvVar" to false,
        "excludeSlashTmp" to false
    )
}

internal fun resolveAgentSandboxMode(sandboxPolicy: Any?): String {
    val type = sandboxPolicy.asStringMap()
        ?.stringValue("type")
        ?: sandboxPolicy?.toString()
    return when (type?.trim()?.lowercase()?.replace("-", "")?.replace("_", "")) {
        "dangerfullaccess" -> "danger-full-access"
        "readonly" -> "read-only"
        else -> "workspace-write"
    }
}

internal fun buildAgentThreadSettingsUpdateParams(
    args: Map<String, Any?>,
    cwd: String,
    threadId: String
): Map<String, Any?> {
    return linkedMapOf<String, Any?>(
        "threadId" to threadId,
        "cwd" to cwd,
        "approvalPolicy" to (args.stringValue("approvalPolicy") ?: "on-request"),
        "sandboxPolicy" to (args["sandboxPolicy"] ?: buildAgentSandboxPolicy(cwd))
    ).apply {
        args.stringValue("approvalsReviewer")?.let {
            this["approvalsReviewer"] = it
        }
        addAgentOptionalRunParams(this, args)
    }
}

internal fun addAgentOptionalRunParams(
    params: MutableMap<String, Any?>,
    args: Map<String, Any?>
) {
    args["model"]?.let { params["model"] = it }
    args["effort"]?.let { params["effort"] = it }
    resolveAgentCollaborationMode(args)?.let { params["collaborationMode"] = it }
    args["serviceTier"]?.let { params["serviceTier"] = it }
}

internal fun resolveAgentCollaborationMode(args: Map<String, Any?>): Map<String, Any?>? {
    val rawMode = args["collaborationMode"] ?: return null
    val source = rawMode.asStringMap()
    val mode = when {
        source != null -> {
            source.stringValue("mode")
                ?: source.stringValue("value")
                ?: source.stringValue("name")
        }
        rawMode is String -> rawMode.trim()
        else -> rawMode.toString().trim()
    }?.normalizeAgentCollaborationModeKind() ?: return null

    val sourceSettings = source?.mapValue("settings").orEmpty()
    val model = sourceSettings.stringValue("model")
        ?: source?.stringValue("model")
        ?: args.stringValue("model")
        ?: return null
    val reasoningEffort = sourceSettings.stringValue("reasoning_effort")
        ?: sourceSettings.stringValue("reasoningEffort")
        ?: source?.stringValue("reasoning_effort")
        ?: source?.stringValue("reasoningEffort")
        ?: args.stringValue("effort")
    val developerInstructions = sourceSettings.stringValue("developer_instructions")
        ?: sourceSettings.stringValue("developerInstructions")
        ?: source?.stringValue("developer_instructions")
        ?: source?.stringValue("developerInstructions")

    val settings = linkedMapOf<String, Any?>("model" to model)
    reasoningEffort?.let { settings["reasoning_effort"] = it }
    developerInstructions?.let { settings["developer_instructions"] = it }
    return linkedMapOf(
        "mode" to mode,
        "settings" to settings
    )
}

private fun Any?.asStringMap(): Map<String, Any?>? {
    val raw = this as? Map<*, *> ?: return null
    return raw.entries.associate { (key, value) -> key.toString() to value }
}

private fun String.normalizeAgentCollaborationModeKind(): String? {
    val normalized = trim().lowercase()
    if (normalized.isEmpty()) {
        return null
    }
    return when {
        normalized == "plan" || normalized.contains("plan") -> "plan"
        normalized == "default" -> "default"
        else -> normalized
    }
}

private fun buildRemoteBridgeConfigPayload(
    remoteConfig: CodexRemoteBridgeConfig,
    runtime: String
): Map<String, Any?> {
    return linkedMapOf(
        "agentHome" to AgentRuntimeDefaults.CODEX_HOME,
        "remoteEnabled" to remoteConfig.enabled,
        "remoteBridgeUrl" to remoteConfig.bridgeUrl,
        "remoteBridgeToken" to remoteConfig.authToken,
        "remoteCwd" to remoteConfig.cwd,
        "remoteConfigured" to remoteConfig.isConfigured,
        "runtime" to runtime
    )
}

/**
 * Official OpenCode v1 configuration for a custom OpenAI-compatible provider.
 * The API key remains an environment substitution; the host only publishes
 * the shared provider/model mapping into OpenCode's own config surface.
 */
internal fun buildOpenCodeConfigJson(
    model: String,
    baseUrl: String,
    existingConfigJson: String = "",
): String {
    val providerModel = model.substringAfter("/", model)
    val root = runCatching {
        JsonParser.parseString(existingConfigJson).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull() ?: com.google.gson.JsonObject()
    root.addProperty("\$schema", "https://opencode.ai/config.json")
    root.addProperty("model", model)

    val providers = root.getAsJsonObject("provider") ?: com.google.gson.JsonObject().also {
        root.add("provider", it)
    }
    val provider = providers.getAsJsonObject(OPEN_CODE_PROVIDER_ID)
        ?: com.google.gson.JsonObject().also {
            providers.add(OPEN_CODE_PROVIDER_ID, it)
        }
    provider.addProperty("npm", "@ai-sdk/openai-compatible")
    provider.addProperty("name", "OmniBot Provider")
    val options = provider.getAsJsonObject("options") ?: com.google.gson.JsonObject().also {
        provider.add("options", it)
    }
    options.addProperty("baseURL", baseUrl)
    options.addProperty("apiKey", "{env:OPENAI_API_KEY}")
    val models = provider.getAsJsonObject("models") ?: com.google.gson.JsonObject().also {
        provider.add("models", it)
    }
    val modelConfig = models.getAsJsonObject(providerModel)
        ?: com.google.gson.JsonObject().also {
            models.add(providerModel, it)
        }
    modelConfig.addProperty("name", providerModel)
    val limits = modelConfig.getAsJsonObject("limit") ?: com.google.gson.JsonObject().also {
        modelConfig.add("limit", it)
    }
    limits.addProperty("context", 128000)
    limits.addProperty("output", 8192)

    return GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n"
}


internal fun managedAgentTerminalPackageId(profile: AcpAgentProfile): String? =
    AcpAgentProfileStore.officialRuntime(profile)?.terminalPackageId

internal fun npmPackageName(spec: String): String {
    val versionSeparator = spec.lastIndexOf('@')
    return if (versionSeparator > 0) spec.substring(0, versionSeparator) else spec
}

internal fun isRecoverableAgentThreadError(message: String): Boolean {
    val normalized = message.lowercase()
    return normalized.contains("thread not found") ||
        normalized.contains("unknown session") ||
        normalized.contains("did not advertise session resume or loadsession") ||
        normalized.contains("session not found") ||
        normalized.contains("session does not exist") ||
        normalized.contains("session file") && (
            normalized.contains("not found") ||
                normalized.contains("missing") ||
                normalized.contains("does not exist")
            ) ||
        normalized.contains("metadata") && (
            normalized.contains("not found") ||
                normalized.contains("missing") ||
                normalized.contains("does not exist")
            )
}

internal fun buildCodexConfigToml(
    baseUrl: String,
    model: String,
    wireApi: String = OpenAiWireApi.RESPONSES,
    modelCatalogPath: String? = null
): String {
    val codexWireApi = if (OpenAiWireApi.isResponses(wireApi)) {
        OpenAiWireApi.RESPONSES
    } else {
        "chat"
    }
    val lines = mutableListOf(
        "model_provider = \"omnimind\"",
        "model = ${tomlString(model.trim())}",
        "disable_response_storage = true",
        modelCatalogPath?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "model_catalog_json = ${tomlString(it)}" }
            .orEmpty(),
        "",
        "[model_providers.omnimind]",
        "name = \"omnimind\"",
        "base_url = ${tomlString(baseUrl.trim())}",
        "wire_api = \"$codexWireApi\"",
        "requires_openai_auth = true"
    )
    return lines.joinToString(separator = "\n", postfix = "\n")
}

internal fun buildCodexAuthJson(apiKey: String): String {
    return GsonBuilder()
        .setPrettyPrinting()
        .create()
        .toJson(mapOf("OPENAI_API_KEY" to apiKey.trim())) + "\n"
}

private fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
}

private fun tomlString(value: String): String {
    return buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000C' -> append("\\f")
                '\r' -> append("\\r")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
        append('"')
    }
}

private fun extractMarkedBlock(
    source: String,
    startMarker: String,
    endMarker: String
): String {
    val start = source.indexOf(startMarker)
    if (start < 0) return ""
    val bodyStart = start + startMarker.length
    val end = source.indexOf(endMarker, bodyStart)
    if (end < 0) return ""
    return source.substring(bodyStart, end)
        .removePrefix("\n")
        .removeSuffix("\n")
}

private fun extractTomlString(source: String, key: String): String? {
    if (source.isBlank()) return null
    val escapedKey = Regex.escape(key)
    return Regex(
        pattern = """(?m)^\s*$escapedKey\s*=\s*"((?:\\.|[^"\\])*)"\s*(?:#.*)?$"""
    ).find(source)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::unescapeTomlBasicString)
}

private fun unescapeTomlBasicString(value: String): String {
    return buildString {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            if (current != '\\' || index + 1 >= value.length) {
                append(current)
                index += 1
                continue
            }
            when (val escaped = value[index + 1]) {
                'b' -> append('\b')
                't' -> append('\t')
                'n' -> append('\n')
                'f' -> append('\u000C')
                'r' -> append('\r')
                '"' -> append('"')
                '\\' -> append('\\')
                else -> append(escaped)
            }
            index += 2
        }
    }
}

private fun extractJsonString(source: String, key: String): String? {
    if (source.isBlank()) return null
    val parsed = runCatching {
        JsonParser.parseString(source)
            .takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.get(key)
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
    }.getOrNull()
    if (!parsed.isNullOrBlank()) return parsed.trim()
    return Regex(
        pattern = """(?m)[\"']${Regex.escape(key)}[\"']\s*:\s*[\"']([^\"']+)[\"']"""
    ).find(source)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
}

private fun extractClaudeCodeModel(source: String): String? {
    val parsed = runCatching {
        JsonParser.parseString(source)
            .takeIf { it.isJsonObject }
            ?.asJsonObject
    }.getOrNull()
    val directModel = parsed?.get("model")
        ?.takeIf { it.isJsonPrimitive }
        ?.asString
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    if (directModel != null) return directModel
    val environment = parsed?.getAsJsonObject("env")
    val environmentModel = listOf("ANTHROPIC_MODEL", "ANTHROPIC_SMALL_FAST_MODEL")
        .asSequence()
        .mapNotNull { key ->
            environment?.get(key)
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }
        .firstOrNull()
    return environmentModel
        ?: extractJsonString(source, "ANTHROPIC_MODEL")
        ?: extractJsonString(source, "ANTHROPIC_SMALL_FAST_MODEL")
}

private fun extractOpenCodeModel(source: String): String? {
    return extractJsonString(source, "model")
        ?.removePrefix("$OPEN_CODE_PROVIDER_ID/")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
}

private fun extractOpenAiApiKey(source: String): String? {
    val trimmed = source.trim()
    if (trimmed.isEmpty()) return null
    return runCatching {
        JsonParser.parseString(trimmed)
            .takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.get("OPENAI_API_KEY")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }.getOrNull()
}

private fun requireAgentConfigSize(content: String) {
    require(content.length <= MAX_AGENT_CONFIG_FILE_CHARS) {
        "Agent configuration is too large."
    }
}

private fun Map<String, Any?>.stringValue(key: String): String? {
    return this[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

private fun Map<String, Any?>.stringValuePreservingWhitespace(key: String): String? {
    return this[key]?.toString()
}

private fun Map<String, Any?>.longValue(key: String): Long? {
    val raw = this[key] ?: return null
    return when (raw) {
        is Number -> raw.toLong()
        is String -> raw.trim().toLongOrNull()
        else -> null
    }
}

internal const val CLAUDE_CODE_AGENT_ID = "claude-code-acp"
internal const val OPENCODE_AGENT_ID = "opencode-acp"
private const val CODEX_CONFIG_TOML_PATH = "/root/.codex/config.toml"
private const val CODEX_AUTH_JSON_PATH = "/root/.codex/auth.json"
private const val CODEX_MODEL_CATALOG_JSON_PATH = "/root/.codex/provider-model-catalog.json"
private const val CLAUDE_SETTINGS_JSON_PATH = "/root/.claude/settings.json"
private const val OPENCODE_CONFIG_JSON_PATH = "/root/.config/opencode/opencode.json"
private const val CODEX_CONFIG_TOML_DISPLAY_PATH = "~/.codex/config.toml"
private const val CODEX_AUTH_JSON_DISPLAY_PATH = "~/.codex/auth.json"
private const val CLAUDE_SETTINGS_JSON_DISPLAY_PATH = "~/.claude/settings.json"
private const val OPENCODE_CONFIG_JSON_DISPLAY_PATH = "~/.config/opencode/opencode.json"
private const val AGENT_CONFIG_START_MARKER = "__OMNI_AGENT_CONFIG_START__"
private const val AGENT_CONFIG_END_MARKER = "__OMNI_AGENT_CONFIG_END__"
private const val MAX_AGENT_CONFIG_FILE_CHARS = 1_048_576
private const val DEFAULT_EMPTY_JSON_FILE = "{\n}\n"

internal fun agentTurnRuntimeId(turnId: String): String = "agent-turn:${turnId.trim()}"

private fun Map<String, Any?>.mapValue(key: String): Map<String, Any?> {
    val raw = this[key] as? Map<*, *> ?: return emptyMap()
    return raw.entries.associate { (entryKey, value) -> entryKey.toString() to value }
}

private val CODEX_ENVELOPE_KEYS = listOf(
    "message",
    "payload",
    "data",
    "event",
    "notification",
    "params",
    "result",
    "_meta",
    "msg"
)

private fun extractRemoteCodexServerMethod(value: Any?, depth: Int = 0): String {
    val map = value as? Map<*, *> ?: return ""
    if (depth > 6) {
        return ""
    }
    val direct = normalizeRemoteCodexServerMethod(map["method"]?.toString()?.trim())
    if (direct.isNotBlank()) {
        return direct
    }
    for (key in CODEX_ENVELOPE_KEYS) {
        val nested = extractRemoteCodexServerMethod(map[key], depth + 1)
        if (nested.isNotBlank()) {
            return nested
        }
    }
    val rawType = map["type"]?.toString()?.trim()
    if (remoteCodexServerTypeLooksLikeMethod(rawType)) {
        return normalizeRemoteCodexServerMethod(rawType)
    }
    return ""
}

private fun remoteCodexServerTypeLooksLikeMethod(rawType: String?): Boolean {
    val type = rawType?.trim().orEmpty()
    if (type.isBlank()) {
        return false
    }
    val normalized = normalizeRemoteCodexServerMethod(type)
    return normalized.contains("/") ||
        normalized == "error" ||
        type in CODEX_THREAD_ITEM_TYPES
}

private fun extractRemoteCodexServerParams(value: Any?, depth: Int = 0): Map<String, Any?> {
    val map = value as? Map<*, *> ?: return emptyMap()
    if (depth > 6) {
        return emptyMap()
    }
    val direct = map["params"] as? Map<*, *>
    if (direct != null) {
        val nested = extractRemoteCodexServerParams(direct, depth + 1)
        if (nested.isNotEmpty()) {
            return topLevelRemoteCodexIds(map) + nested
        }
        val normalized = direct.entries.associate { (entryKey, nestedValue) ->
            entryKey.toString() to nestedValue
        }
        if (normalized.isNotEmpty()) {
            return topLevelRemoteCodexIds(map) + normalized
        }
    }
    for (key in CODEX_ENVELOPE_KEYS) {
        if (key == "params") {
            continue
        }
        val nested = extractRemoteCodexServerParams(map[key], depth + 1)
        if (nested.isNotEmpty()) {
            return topLevelRemoteCodexIds(map) + nested
        }
    }
    return emptyMap()
}

private fun topLevelRemoteCodexIds(map: Map<*, *>): Map<String, Any?> {
    val ids = linkedMapOf<String, Any?>()
    val meta = map["_meta"] as? Map<*, *>
    if (meta != null) {
        for (key in listOf("threadId", "thread_id")) {
            if (meta.containsKey(key)) {
                ids[key] = meta[key]
            }
        }
    }
    for (key in listOf("threadId", "thread_id", "turnId", "turn_id", "itemId", "item_id")) {
        if (map.containsKey(key)) {
            ids[key] = map[key]
        }
    }
    return ids
}

private fun normalizeRemoteCodexServerMethod(rawMethod: String?): String {
    val method = rawMethod?.trim().orEmpty()
    if (method.isEmpty()) {
        return ""
    }
    return when (method) {
        "thread.started" -> "thread/started"
        "turn.started" -> "turn/started"
        "turn.completed" -> "turn/completed"
        "turn.failed" -> "turn/failed"
        "item.started" -> "item/started"
        "item.updated" -> "item/updated"
        "item.completed" -> "item/completed"
        else -> method
            .replace("/agent_message/", "/agentMessage/")
            .replace("/command_execution/", "/commandExecution/")
            .replace("/file_change/", "/fileChange/")
            .replace("/mcp_tool_call/", "/mcpToolCall/")
    }
}

private fun syntheticRemoteCodexServerParams(
    message: Map<String, Any?>,
    method: String
): Map<String, Any?> {
    if (method.isBlank()) {
        return emptyMap()
    }
    val payload = linkedMapOf<String, Any?>()
    message.forEach { (key, value) ->
        if (key != "method" && key != "type" && key != "params") {
            payload[key] = value
        }
    }
    return payload
}

private fun remoteCodexProtocolEventType(value: Any?): String {
    val msg = remoteCodexProtocolMsg(value) ?: return ""
    return msg["type"]?.toString()?.trim()?.lowercase()
        ?.replace(Regex("[^a-z0-9]+"), "_")
        .orEmpty()
}

private fun remoteCodexProtocolMsg(value: Any?, depth: Int = 0): Map<*, *>? {
    val map = value as? Map<*, *> ?: return null
    if (depth > 6) {
        return null
    }
    val direct = map["msg"] as? Map<*, *>
    if (direct != null) {
        return direct
    }
    for (key in CODEX_ENVELOPE_KEYS) {
        val nested = remoteCodexProtocolMsg(map[key], depth + 1)
        if (nested != null) {
            return nested
        }
    }
    return null
}

internal fun extractThreadId(value: Any?): String? {
    return extractStringRecursive(
        value = value,
        // Official ACP notifications use `sessionId`; `threadId` remains a
        // compatibility alias for the app-server boundary. Reading the
        // canonical field here keeps session/update events attached to the
        // local conversation instead of dropping them before persistence.
        keys = setOf("sessionId", "threadId", "thread_id"),
        nestedObjectKeys = setOf(
            "thread",
            "message",
            "payload",
            "data",
            "event",
            "notification",
            "params",
            "result",
            "_meta",
            "msg"
        )
    )
}

private fun extractTurnId(value: Any?): String? {
    val fromTurn = extractStringRecursive(
        value = value,
        keys = setOf("turnId", "turn_id"),
        nestedObjectKeys = setOf(
            "turn",
            "message",
            "payload",
            "data",
            "event",
            "notification",
            "params",
            "result",
            "_meta",
            "msg"
        )
    )
    if (!fromTurn.isNullOrBlank()) {
        return fromTurn
    }
    val map = value as? Map<*, *> ?: return null
    val turn = map["turn"] as? Map<*, *> ?: return null
    return turn["id"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

private fun extractActiveTurnId(value: Any?): String? {
    val direct = extractStringRecursive(
        value = value,
        keys = setOf(
            "turnId",
            "turn_id",
            "activeTurnId",
            "active_turn_id",
            "currentTurnId",
            "current_turn_id"
        ),
        nestedObjectKeys = setOf(
            "thread",
            "turn",
            "status",
            "message",
            "payload",
            "data",
            "event",
            "notification",
            "params",
            "result",
            "_meta",
            "msg"
        )
    )
    if (!direct.isNullOrBlank()) {
        return direct
    }
    val root = value as? Map<*, *> ?: return null
    val thread = root["thread"] as? Map<*, *>
    val turns = (thread?.get("turns") as? List<*>) ?: (root["turns"] as? List<*>) ?: return null
    for (index in turns.indices.reversed()) {
        val turn = turns[index] as? Map<*, *> ?: continue
        val active = remoteCodexActivityFromValue(turn["status"] ?: turn["state"])
        if (active == true) {
            return turn["id"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
    return null
}

private fun remoteCodexThreadActivity(value: Any?): Boolean? {
    val root = value as? Map<*, *> ?: return null
    val thread = root["thread"] as? Map<*, *>
    var inactiveCandidate: Boolean? = null
    val candidates = listOf(
        root["active"],
        root["isActive"],
        root["is_active"],
        root["status"],
        root["state"],
        root["turnStatus"],
        root["turn_status"],
        thread?.get("active"),
        thread?.get("isActive"),
        thread?.get("is_active"),
        thread?.get("status"),
        thread?.get("state"),
        thread?.get("turnStatus"),
        thread?.get("turn_status")
    )
    for (candidate in candidates) {
        val active = remoteCodexActivityFromValue(candidate)
        if (active == true) {
            return true
        }
        if (active == false) {
            inactiveCandidate = false
        }
    }
    for (key in CODEX_ENVELOPE_KEYS) {
        val nested = root[key] as? Map<*, *> ?: continue
        val nestedActivity = remoteCodexThreadActivity(nested)
        if (nestedActivity == true) {
            return true
        }
        if (nestedActivity == false) {
            inactiveCandidate = false
        }
    }
    val turns = (thread?.get("turns") as? List<*>) ?: (root["turns"] as? List<*>)
    if (turns != null) {
        for (index in turns.indices.reversed()) {
            val turn = turns[index] as? Map<*, *> ?: continue
            val active = remoteCodexActivityFromValue(turn["status"] ?: turn["state"])
            if (active != null) {
                return active
            }
        }
    }
    return inactiveCandidate
}

private fun remoteCodexActivityFromValue(value: Any?): Boolean? {
    if (value is Boolean) {
        return value
    }
    val text = remoteCodexStatusText(value)?.lowercase()
        ?.replace(Regex("[^a-z0-9]+"), "")
        ?: return null
    return when (text) {
        "running", "active", "busy", "inprogress", "inflight", "executing" -> true
        "idle", "closed", "completed", "complete", "notloaded", "systemerror",
        "failed", "cancelled", "canceled", "interrupted" -> false
        else -> null
    }
}

private fun remoteCodexStatusText(value: Any?): String? {
    return when (value) {
        null -> null
        is String -> value.trim().takeIf { it.isNotEmpty() }
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> {
            listOf("type", "status", "state", "value", "name")
                .firstNotNullOfOrNull { key -> remoteCodexStatusText(value[key]) }
        }
        else -> null
    }
}

private fun extractThreadTitle(value: Any?): String? {
    val map = value as? Map<*, *> ?: return null
    val params = map["params"] as? Map<*, *>
    val result = map["result"] as? Map<*, *>
    val thread = map["thread"] as? Map<*, *>
    return listOfNotNull(
        map["threadName"],
        map["thread_name"],
        map["name"],
        map["title"],
        map["preview"],
        params?.get("threadName"),
        params?.get("thread_name"),
        params?.get("name"),
        params?.get("title"),
        params?.get("preview"),
        result?.get("threadName"),
        result?.get("thread_name"),
        result?.get("name"),
        result?.get("title"),
        result?.get("preview"),
        thread?.get("name"),
        thread?.get("title"),
        thread?.get("preview"),
        (params?.get("thread") as? Map<*, *>)?.get("name"),
        (result?.get("thread") as? Map<*, *>)?.get("name"),
        (params?.get("thread") as? Map<*, *>)?.get("title"),
        (result?.get("thread") as? Map<*, *>)?.get("title"),
        (params?.get("thread") as? Map<*, *>)?.get("preview"),
        (result?.get("thread") as? Map<*, *>)?.get("preview")
    ).firstNotNullOfOrNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
}

private fun extractStringRecursive(
    value: Any?,
    keys: Set<String>,
    nestedObjectKeys: Set<String>
): String? {
    val map = value as? Map<*, *> ?: return null
    for (key in keys) {
        val direct = map[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        if (direct != null) {
            return direct
        }
    }
    for (nestedKey in nestedObjectKeys) {
        val nested = map[nestedKey] as? Map<*, *>
        if (nestedKey == "thread" || nestedKey == "turn") {
            val id = nested?.get("id")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            if (id != null) {
                return id
            }
        }
        val recursive = extractStringRecursive(nested, keys, nestedObjectKeys)
        if (recursive != null) {
            return recursive
        }
    }
    val params = map["params"] as? Map<*, *>
    val fromParams = extractStringRecursive(params, keys, nestedObjectKeys)
    if (fromParams != null) {
        return fromParams
    }
    val result = map["result"] as? Map<*, *>
    return extractStringRecursive(result, keys, nestedObjectKeys)
}

private fun collectThreadEntries(value: Any?): List<RemoteCodexThreadListEntry> {
    val entries = mutableListOf<RemoteCodexThreadListEntry>()
    fun visit(current: Any?, parentKey: String? = null) {
        when (current) {
            is List<*> -> current.forEach { visit(it, parentKey) }
            is Map<*, *> -> {
                val threadMap = current["thread"] as? Map<*, *>
                val threadId = threadEntryId(current, threadMap, parentKey)
                if (threadId != null) {
                    val cwd = listOfNotNull(current["cwd"], threadMap?.get("cwd"))
                        .firstNotNullOfOrNull {
                            it?.toString()?.trim()?.takeIf(String::isNotEmpty)
                        }
                    val title = listOfNotNull(
                        current["name"],
                        current["title"],
                        current["preview"],
                        current["threadName"],
                        current["thread_name"],
                        threadMap?.get("name"),
                        threadMap?.get("title"),
                        threadMap?.get("preview")
                    ).firstNotNullOfOrNull {
                        it?.toString()?.trim()?.takeIf(String::isNotEmpty)
                    }
                    val archived = listOfNotNull(
                        current["archived"],
                        current["isArchived"],
                        current["is_archived"],
                        threadMap?.get("archived"),
                        threadMap?.get("isArchived"),
                        threadMap?.get("is_archived")
                    ).firstNotNullOfOrNull(::asBooleanOrNull)
                    entries += RemoteCodexThreadListEntry(
                        threadId = threadId,
                        cwd = cwd,
                        title = title,
                        archived = archived
                    )
                }
                current.entries.forEach { (key, nestedValue) ->
                    val nestedKey = key?.toString()
                    if (nestedKey !in THREAD_ITEM_COLLECTION_KEYS) {
                        visit(nestedValue, nestedKey)
                    }
                }
            }
        }
    }
    visit(value)
    return entries.distinctBy { it.threadId }
}

private fun threadEntryId(
    current: Map<*, *>,
    threadMap: Map<*, *>?,
    parentKey: String?
): String? {
    return listOfNotNull(
        current["threadId"],
        current["thread_id"],
        threadMap?.get("id"),
        if (current.looksLikeThreadEntry(threadMap, parentKey)) current["id"] else null
    ).firstNotNullOfOrNull {
        it?.toString()?.trim()?.takeIf(String::isNotEmpty)
    }
}

private fun Map<*, *>.looksLikeThreadEntry(threadMap: Map<*, *>?, parentKey: String?): Boolean {
    if (threadMap != null || containsKey("threadId") || containsKey("thread_id")) {
        return true
    }
    if (!containsKey("id")) {
        return false
    }
    val normalizedParentKey = parentKey?.lowercase().orEmpty()
    if (normalizedParentKey == "thread" || normalizedParentKey == "threads") {
        return true
    }
    val type = this["type"]?.toString()?.trim().orEmpty()
    if (type in CODEX_THREAD_ITEM_TYPES) {
        return false
    }
    return keys.any { key ->
        key?.toString() in THREAD_SUMMARY_KEYS
    }
}

private fun asBooleanOrNull(value: Any?): Boolean? {
    return when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.trim().lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
        else -> null
    }
}

internal fun resolveCodexReviewTarget(value: Any?): Map<String, Any?> {
    val target = value as? Map<*, *>
    if (target.isNullOrEmpty()) {
        return mapOf("type" to "uncommittedChanges")
    }
    return target.entries.mapNotNull { (key, nestedValue) ->
        val normalizedKey = key?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null
        normalizedKey to nestedValue
    }.toMap().ifEmpty { mapOf("type" to "uncommittedChanges") }
}

private val THREAD_ITEM_COLLECTION_KEYS = setOf(
    "items",
    "inputItems",
    "input_items",
    "outputItems",
    "output_items",
    "responseItems",
    "response_items",
    "rawItems",
    "raw_items",
    "events",
    "messages",
    "turns"
)

private val THREAD_SUMMARY_KEYS = setOf(
    "cwd",
    "name",
    "title",
    "preview",
    "threadName",
    "thread_name",
    "archived",
    "isArchived",
    "is_archived",
    "sourceKind",
    "source_kind",
    "createdAt",
    "created_at",
    "updatedAt",
    "updated_at",
    "lastActivityAt",
    "last_activity_at"
)

private val CODEX_THREAD_ITEM_TYPES = setOf(
    "agentMessage",
    "agent_message",
    "reasoning",
    "commandExecution",
    "command_execution",
    "local_shell_call",
    "commandExec",
    "processExecution",
    "fileChange",
    "file_change",
    "tool",
    "mcpToolCall",
    "mcp_tool_call",
    "dynamicToolCall",
    "dynamic_tool_call",
    "function_call",
    "function_call_output",
    "custom_tool_call",
    "custom_tool_call_output",
    "tool_search_call",
    "tool_search_output",
    "webSearch",
    "web_search",
    "web_search_call",
    "imageView",
    "image_view",
    "imageGeneration",
    "image_generation",
    "image_generation_call",
    "collabAgentToolCall",
    "collab_agent_tool_call",
    "collabToolCall",
    "collab_tool_call",
    "userMessage",
    "user_message",
    "todo_list",
    "plan",
    "serverRequest"
)

internal val DEFAULT_CODEX_THREAD_SOURCE_KINDS = listOf(
    "cli",
    "vscode",
    "exec",
    "appServer",
    "subAgent",
    "subAgentReview",
    "subAgentCompact",
    "subAgentThreadSpawn",
    "subAgentOther"
)
