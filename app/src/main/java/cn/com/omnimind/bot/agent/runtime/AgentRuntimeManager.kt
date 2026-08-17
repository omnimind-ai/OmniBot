package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.setup.buildAlpinePackageInstallCommand
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.agent.AgentAttachmentPromptSupport
import cn.com.omnimind.bot.agent.AgentImageAttachmentSupport
import cn.com.omnimind.bot.agent.AgentWorkspaceAttachmentSupport
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.mcp.McpServerManager
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
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AgentRuntimeManager private constructor(
    private val context: Context
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()
    private val threadStartMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bindingRepository = AgentSessionBindingRepository(appContext)
    private val remoteConfigStore = CodexRemoteBridgeConfigStore(appContext)
    private val acpAgentProfileStore = AcpAgentProfileStore(appContext)
    private val localAcpRuntime = LocalAcpRuntime(
        context = appContext,
        scope = scope,
        bindingRepository = bindingRepository,
        profileStore = acpAgentProfileStore,
        prepareLaunchEnvironment = ::prepareLocalAcpLaunch,
        onMessage = ::handleServerMessage
    )
    private val activeTurnsByThreadId = ConcurrentHashMap<String, String>()

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
            "remoteTransport" to probe.details["appServerTransport"],
            "remoteDesktopAvailable" to probe.details["desktopAppServerAvailable"],
            "remoteActiveConnections" to probe.details["activeConnections"],
            "remoteUptimeMs" to probe.details["uptimeMs"]
        ).apply {
            if (runtime.kind == AgentRuntimeKind.LOCAL) {
                putAll(localAcpRuntime.statusPayload())
            } else {
                put("protocol", "app-server")
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
                connectLocalAcp()
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
            return localAcpRuntime.handleMethod(method, canonicalArgs)
        }
        if (resolveRuntime().kind == AgentRuntimeKind.LOCAL && method in LOCAL_ACP_METHODS) {
            ensureLocalAcpConnected(canonicalArgs)
            return localAcpRuntime.handleMethod(method, canonicalArgs)
        }
        return when (method) {
            "status" -> status()
            "connect" -> connect()
            "disconnect" -> disconnect()
            // The local runtime speaks ACP directly. The remote Codex
            // runtime remains an official Codex app-server transport, so
            // these ACP-shaped calls are translated only at that boundary.
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
        val cwd = sanitizeAgentRuntimeAbsolutePath(args.stringValue("cwd")) ?: resolveDefaultCwd()
        var threadId = ensureThreadForTurn(args, cwd)
        val params = buildTurnStartParams(
            args = args,
            cwd = cwd,
            threadId = threadId
        )
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
            threadId = retryResponse["threadId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw error
            params["threadId"] = threadId
            request("turn/start", params) as Map<String, Any?>
        }
        val turnId = extractTurnId(response)
        if (!turnId.isNullOrBlank()) {
            trackActiveTurn(threadId, turnId)
        }
        return response.withLocalIds(
            threadId = threadId,
            conversationId = localConversationIdForThread(threadId),
            turnId = turnId
        )
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
        return when (profile.id) {
            AcpAgentProfileStore.DEFAULT_CODEX_AGENT_ID -> {
                val configToml = readTerminalTextFile(
                    path = CODEX_CONFIG_TOML_PATH,
                    executorKey = "codex-agent-config-read"
                )
                val authJson = readTerminalTextFile(
                    path = CODEX_AUTH_JSON_PATH,
                    executorKey = "codex-agent-auth-read"
                )
                linkedMapOf(
                    "agentId" to profile.id,
                    "kind" to "codex",
                    "configPath" to CODEX_CONFIG_TOML_DISPLAY_PATH,
                    "authPath" to CODEX_AUTH_JSON_DISPLAY_PATH,
                    "baseUrl" to extractTomlString(configToml, "base_url").orEmpty(),
                    "model" to extractTomlString(configToml, "model").orEmpty(),
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
            AcpAgentProfileStore.DEEPSEEK_HARNESS_AGENT_ID -> {
                val config = parseDeepSeekHarnessConfig(
                    readTerminalTextFile(
                        path = DEEPSEEK_HARNESS_CONFIG_PATH,
                        executorKey = "deepseek-harness-config-read"
                    )
                )
                linkedMapOf(
                    "agentId" to profile.id,
                    "kind" to "deepseek-harness",
                    "configPath" to DEEPSEEK_HARNESS_CONFIG_DISPLAY_PATH,
                    "baseUrl" to config.baseUrl,
                    "model" to config.model,
                    "apiKey" to config.apiKey,
                    "reasoningEffort" to config.reasoningEffort,
                    "permissionMode" to config.permissionMode
                )
            }
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
        when (profile.id) {
            AcpAgentProfileStore.DEFAULT_CODEX_AGENT_ID -> {
                val baseUrl = args.stringValue("baseUrl")
                    ?: throw IllegalArgumentException("Base URL is required.")
                val model = args.stringValue("model")
                    ?: throw IllegalArgumentException("Model ID is required.")
                val apiKey = args.stringValue("apiKey")
                    ?: throw IllegalArgumentException("API Key is required.")
                writeCodexConfigFiles(
                    configToml = buildCodexConfigToml(
                        baseUrl = baseUrl,
                        model = model
                    ),
                    authJson = buildCodexAuthJson(apiKey)
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
            AcpAgentProfileStore.DEEPSEEK_HARNESS_AGENT_ID -> {
                val current = parseDeepSeekHarnessConfig(
                    readTerminalTextFile(
                        path = DEEPSEEK_HARNESS_CONFIG_PATH,
                        executorKey = "deepseek-harness-config-before-write"
                    )
                )
                val config = deepSeekHarnessConfigFromArgs(args, current)
                writeTerminalTextFile(
                    path = DEEPSEEK_HARNESS_CONFIG_PATH,
                    content = buildDeepSeekHarnessConfigJson(config),
                    executorKey = "deepseek-harness-config-write"
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
        authJson: String
    ) {
        val command = """
            set -eu
            mkdir -p ${shellQuote(AgentRuntimeDefaults.CODEX_HOME)}
            umask 077
            printf %s ${shellQuote(configToml)} > ${shellQuote(CODEX_CONFIG_TOML_PATH)}
            printf %s ${shellQuote(authJson)} > ${shellQuote(CODEX_AUTH_JSON_PATH)}
            chmod 600 ${shellQuote(CODEX_CONFIG_TOML_PATH)} ${shellQuote(CODEX_AUTH_JSON_PATH)}
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

    private suspend fun connectLocalAcp() {
        val profile = acpAgentProfileStore.selected()
        require(profile.enabled) {
            "No enabled ACP Agent is selected. Enable one in Agent mode settings."
        }
        localAcpRuntime.connect(profile = profile)
    }

    private suspend fun prepareLocalAcpLaunch(
        profile: AcpAgentProfile
    ): Map<String, String> {
        val deepSeekEnvironment = if (
            profile.id == AcpAgentProfileStore.DEEPSEEK_HARNESS_AGENT_ID &&
            AcpAgentProfileStore.officialRuntime(profile) != null
        ) {
            val mcpState = McpServerManager.ensureRunning(appContext)
            val config = parseDeepSeekHarnessConfig(
                readTerminalTextFile(
                    path = DEEPSEEK_HARNESS_CONFIG_PATH,
                    executorKey = "deepseek-harness-launch-config-read"
                )
            )
            require(config.apiKey.isNotBlank()) {
                "Configure the DeepSeek API key in Agent mode settings before starting DeepSeek Harness."
            }
            config.toEnvironment() + buildDeepSeekHarnessMcpEnvironment(mcpState)
        } else {
            emptyMap()
        }
        ensureManagedAcpAdapter(profile)
        return when (profile.id) {
            AcpAgentProfileStore.DEFAULT_CODEX_AGENT_ID ->
                mapOf("CODEX_HOME" to AgentRuntimeDefaults.CODEX_HOME)
            AcpAgentProfileStore.DEEPSEEK_HARNESS_AGENT_ID -> {
                if (AcpAgentProfileStore.officialRuntime(profile) != null) {
                    writeTerminalTextFile(
                        path = DEEPSEEK_HARNESS_CORDIS_PATH,
                        content = buildDeepSeekHarnessCordisConfig(),
                        executorKey = "deepseek-harness-cordis-write"
                    )
                }
                deepSeekEnvironment
            }
            else -> emptyMap()
        }
    }

    private suspend fun ensureManagedAcpAdapter(profile: AcpAgentProfile) {
        val runtime = AcpAgentProfileStore.officialRuntime(profile) ?: return
        val packageName = runtime.managedAdapterPackage ?: return
        val managedPackages = runtime.managedAdapterPackages
            .ifEmpty { listOf(packageName) }
        val commandAvailable = isTerminalCommandAvailable(profile.command)
        val allPackagesReady = managedPackages.size == 1 ||
            (commandAvailable && areManagedNpmPackagesInstalled(managedPackages))
        val adapterHealthy = runtime.managedAdapterHealthCommand
            ?.let { isTerminalShellCommandSuccessful(it) }
            ?: true
        if (commandAvailable && allPackagesReady && adapterHealthy) {
            return
        }
        if (!isTerminalCommandAvailable(runtime.discoveryCommand)) {
            throw IllegalStateException(
                "${profile.name} CLI was not found: ${runtime.discoveryCommand}. " +
                    "Install it in Terminal Environment first."
            )
        }
        if (!isTerminalCommandAvailable("npm")) {
            throw IllegalStateException(
                "npm is required to prepare the ${profile.name} ACP adapter."
            )
        }
        val installTargets = managedPackages.joinToString(" ") { shellQuote(it) }
        val nativeBuildPrerequisites = if (runtime.requiresNativeBuildTools) {
            MANAGED_NATIVE_BUILD_PREREQUISITES_COMMAND
        } else {
            ":"
        }
        val adapterHealthCheck = runtime.managedAdapterHealthCommand ?: ":"
        val adapterInstallCommand = if (runtime.requiresNativeBuildTools) {
            DEEPSEEK_HARNESS_NPM_INSTALL_COMMAND
        } else {
            "npm install -g --prefix /root/.npm-global --no-audit --no-fund $installTargets"
        }
        val command = """
            set -eu
            $nativeBuildPrerequisites
            mkdir -p /root/.npm-global/bin
            export PATH="/root/.npm-global/bin:${'$'}PATH"
            $adapterInstallCommand
            command -v ${shellQuote(profile.command)} >/dev/null 2>&1
            $adapterHealthCheck
        """.trimIndent()
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
        packageSpecs: List<String>
    ): Boolean {
        if (packageSpecs.isEmpty()) return true
        val checks = packageSpecs.joinToString(" && ") { spec ->
            val packageName = npmPackageName(spec)
            "test -f ${shellQuote("/root/.npm-global/lib/node_modules/$packageName/package.json")}"
        }
        val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
            command = checks,
            executorKey = "acp-managed-packages-probe-${packageSpecs.hashCode()}",
            timeoutMs = 20_000L
        )
        return result.isOk && result.exitCode == 0
    }

    private suspend fun isTerminalCommandAvailable(command: String): Boolean {
        val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
            command = "$MANAGED_NPM_PATH_PREFIX " +
                "command -v ${shellQuote(command)} >/dev/null 2>&1",
            executorKey = "acp-command-probe-${command.hashCode()}",
            timeoutMs = 20_000L
        )
        return result.isOk && result.exitCode == 0
    }

    private suspend fun isTerminalShellCommandSuccessful(command: String): Boolean {
        val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
            command = "$MANAGED_NPM_PATH_PREFIX $command",
            executorKey = "acp-shell-health-${command.hashCode()}",
            timeoutMs = 20_000L
        )
        return result.isOk && result.exitCode == 0
    }

    private suspend fun ensureLocalAcpConnected(args: Map<String, Any?>) {
        val requestedAgentId = args.stringValue("agentId")
        val explicitThreadId = args.stringValue("threadId")
        val requestedThreadId = explicitThreadId
            ?: args.longValue("conversationId")
                ?.let { bindingRepository.getBindingByConversationId(it)?.threadId }
        val boundAgentId = requestedThreadId?.let {
            acpAgentProfileStore.agentIdForSession(it)
                ?: AcpAgentProfileStore.DEFAULT_CODEX_AGENT_ID
        }
        require(
            requestedAgentId == null ||
                boundAgentId == null ||
                requestedAgentId == boundAgentId
        ) {
            "ACP session $requestedThreadId belongs to agent $boundAgentId, not $requestedAgentId."
        }
        val targetAgentId = boundAgentId ?: requestedAgentId
        val targetProfile = targetAgentId?.let { agentId ->
            acpAgentProfileStore.list().firstOrNull { it.id == agentId }
                ?: throw IllegalArgumentException("Unknown ACP agent: $agentId")
        }
        if (targetProfile != null) {
            require(targetProfile.enabled) {
                "ACP agent ${targetProfile.name} is disabled."
            }
        }
        if (targetProfile != null &&
            targetProfile.id != acpAgentProfileStore.selected().id
        ) {
            check(!localAcpRuntime.hasActiveTurns()) {
                "设备当前已有其他 ACP Agent 任务，暂时不能切换 Agent。"
            }
            localAcpRuntime.disconnect()
            acpAgentProfileStore.select(targetProfile.id)
        }
        if (localAcpRuntime.isConnected) {
            return
        }
        connectLocalAcp()
        activeRuntime = AgentRuntimeKind.LOCAL
        activeLocalDistributionId = TerminalDistribution.selected().id
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
                ?: throw IllegalStateException("Codex app-server session is unavailable.")
        }
        connect()
        return session ?: throw IllegalStateException("Codex app-server is not connected.")
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
        val presentation = message.mapValue("presentation")
        if (presentation.isNotEmpty()) {
            handleLocalPresentationMessage(presentation)
            return
        }
        val method = extractRemoteCodexServerMethod(message)
        val explicitParams = extractRemoteCodexServerParams(message)
        val params = if (explicitParams.isNotEmpty()) {
            explicitParams
        } else {
            syntheticRemoteCodexServerParams(message, method)
        }
        val threadId = extractThreadId(message)
        val turnId = extractTurnId(message) ?: extractActiveTurnId(message)
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
            AcpAgentProfileStore.DEFAULT_CODEX_AGENT_ID
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

    /**
     * Local ACP has no turn lifecycle notification: the lifecycle is the
     * result of the `session/prompt` request itself. Keep that host concern
     * separate from ACP by sending a presentation state, never a fabricated
     * thread, turn, or item protocol notification.
     */
    private suspend fun handleLocalPresentationMessage(
        presentation: Map<String, Any?>
    ) {
        val kind = presentation.stringValue("kind") ?: return
        val threadId = presentation.stringValue("threadId")
        val turnId = presentation.stringValue("turnId")
        val conversationId = threadId?.let { localConversationIdForThread(it) }
        val agentId = threadId?.let(acpAgentProfileStore::agentIdForSession)
            ?: localAcpRuntime.activeAgentId()
        val agentName = acpAgentProfileStore.list()
            .firstOrNull { it.id == agentId }
            ?.name
            ?: localAcpRuntime.activeAgentName()
        emitEvent(
            linkedMapOf(
                "presentation" to presentation,
                "threadId" to threadId,
                "turnId" to turnId,
                "conversationId" to conversationId,
                "agentId" to agentId,
                "agentName" to agentName
            )
        )
        if (kind == "turn_completed" || kind == "turn_failed") {
            runCatching {
                TaskRuntimeSettings.notifyTaskFinished(
                    context = appContext,
                    title = "$agentName task completed",
                    message = "Tap to view the completed Agent turn.",
                    conversationId = conversationId,
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
            AgentRuntimeProbe(
                ready = result.isOk && result.exitCode == 0,
                version = null,
                error = if (result.isOk && result.exitCode == 0) {
                    null
                } else {
                    result.error.ifBlank {
                        "ACP agent command not found: ${profile.command}"
                    }
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

private const val MANAGED_ACP_INSTALL_TIMEOUT_MS = 10 * 60 * 1_000L
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
        echo "DeepSeek Harness requires make, a C++ compiler, and Python 3 to build node-pty." >&2
        return 1
      fi
    }
    ensure_native_build_tools
""".trimIndent()
private const val DEEPSEEK_HARNESS_HOME = "/root/.dsh/omnibot-acp"
private const val DEEPSEEK_HARNESS_CONFIG_PATH =
    "$DEEPSEEK_HARNESS_HOME/config.json"
private const val DEEPSEEK_HARNESS_CONFIG_DISPLAY_PATH =
    "~/.dsh/omnibot-acp/config.json"
private const val DEEPSEEK_HARNESS_CORDIS_PATH =
    "$DEEPSEEK_HARNESS_HOME/cordis.yml"
private const val DEEPSEEK_PUBLIC_BASE_URL = "https://api.deepseek.com"
private const val DEEPSEEK_HARNESS_DEFAULT_MODEL = "deepseek-v4-pro"
private const val DEEPSEEK_HARNESS_DEFAULT_REASONING_EFFORT = "max"
private const val DEEPSEEK_HARNESS_DEFAULT_PERMISSION_MODE = "workspace-write"
private val DEEPSEEK_HARNESS_REASONING_EFFORTS = setOf("off", "high", "max")
private val DEEPSEEK_HARNESS_PERMISSION_MODES = setOf(
    "read-only",
    "workspace-write",
    "danger-full-access"
)

private val LOCAL_ACP_METHODS = setOf(
    "session/new",
    "session/load",
    "session/list",
    "session/prompt",
    "session/cancel",
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

internal data class DeepSeekHarnessConfig(
    val baseUrl: String = DEEPSEEK_PUBLIC_BASE_URL,
    val model: String = DEEPSEEK_HARNESS_DEFAULT_MODEL,
    val apiKey: String = "",
    val reasoningEffort: String = DEEPSEEK_HARNESS_DEFAULT_REASONING_EFFORT,
    val permissionMode: String = DEEPSEEK_HARNESS_DEFAULT_PERMISSION_MODE
) {
    fun toEnvironment(): Map<String, String> = linkedMapOf(
        "DEEPSEEK_BASE_URL" to baseUrl,
        "DEEPSEEK_API_KEY" to apiKey,
        "DSH_MODEL" to model,
        "DSH_REASONING_EFFORT" to reasoningEffort,
        "DSH_PERMISSION_MODE" to permissionMode,
        "DSH_ACP_HOME" to DEEPSEEK_HARNESS_HOME,
        "NODE_NO_WARNINGS" to "1"
    )
}

internal fun parseDeepSeekHarnessConfig(source: String): DeepSeekHarnessConfig {
    val json = runCatching {
        JsonParser.parseString(source)
            .takeIf { it.isJsonObject }
            ?.asJsonObject
    }.getOrNull() ?: return DeepSeekHarnessConfig()
    fun stringValue(key: String): String? = json.get(key)
        ?.takeIf { it.isJsonPrimitive }
        ?.asString
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    val reasoningEffort = stringValue("reasoningEffort")
        ?.takeIf { it in DEEPSEEK_HARNESS_REASONING_EFFORTS }
        ?: DEEPSEEK_HARNESS_DEFAULT_REASONING_EFFORT
    val permissionMode = stringValue("permissionMode")
        ?.takeIf { it in DEEPSEEK_HARNESS_PERMISSION_MODES }
        ?: DEEPSEEK_HARNESS_DEFAULT_PERMISSION_MODE
    return DeepSeekHarnessConfig(
        baseUrl = stringValue("baseUrl") ?: DEEPSEEK_PUBLIC_BASE_URL,
        model = stringValue("model") ?: DEEPSEEK_HARNESS_DEFAULT_MODEL,
        apiKey = stringValue("apiKey").orEmpty(),
        reasoningEffort = reasoningEffort,
        permissionMode = permissionMode
    )
}

internal fun deepSeekHarnessConfigFromArgs(
    args: Map<String, Any?>,
    current: DeepSeekHarnessConfig = DeepSeekHarnessConfig()
): DeepSeekHarnessConfig {
    val baseUrl = args.stringValue("baseUrl")
        ?: throw IllegalArgumentException("DeepSeek Base URL is required.")
    val model = args.stringValue("model")
        ?: throw IllegalArgumentException("DeepSeek model ID is required.")
    val apiKey = args.stringValue("apiKey")
        ?: throw IllegalArgumentException("DeepSeek API key is required.")
    val reasoningEffort = args.stringValue("reasoningEffort")
        ?: DEEPSEEK_HARNESS_DEFAULT_REASONING_EFFORT
    require(reasoningEffort in DEEPSEEK_HARNESS_REASONING_EFFORTS) {
        "DeepSeek Harness reasoning effort must be off, high, or max."
    }
    val permissionMode = args.stringValue("permissionMode")
        ?: current.permissionMode
    require(permissionMode in DEEPSEEK_HARNESS_PERMISSION_MODES) {
        "DeepSeek Harness permission mode must be read-only, workspace-write, or danger-full-access."
    }
    return DeepSeekHarnessConfig(
        baseUrl = baseUrl,
        model = model,
        apiKey = apiKey,
        reasoningEffort = reasoningEffort,
        permissionMode = permissionMode
    )
}

internal fun buildDeepSeekHarnessConfigJson(
    config: DeepSeekHarnessConfig
): String {
    return GsonBuilder()
        .setPrettyPrinting()
        .create()
        .toJson(
            linkedMapOf(
                "baseUrl" to config.baseUrl,
                "model" to config.model,
                "apiKey" to config.apiKey,
                "reasoningEffort" to config.reasoningEffort,
                "permissionMode" to config.permissionMode
            )
        ) + "\n"
}

/**
 * Phone-safe copy of DeepSeek Harness's official ACP example composition.
 *
 * The host owns only deployment values (model, permission, persistence, and
 * the MCP endpoint). The capability plugins and their defaults stay aligned
 * with the upstream composition, so the app does not accidentally turn DSH
 * into a smaller private agent implementation.
 */
internal fun buildDeepSeekHarnessCordisConfig(): String = """
    - id: llm-deepseek
      name: '@deepseek-ai/dsh-llm-deepseek'
      config:
        thinking: enabled
        reasoningEffort: !!js "process.env.DSH_REASONING_EFFORT ?? 'max'"
        models:
          - id: deepseek-v4-flash
          - id: deepseek-v4-pro

    - id: sandbox
      name: '@deepseek-ai/dsh-sandbox-local'

    - id: sandbox-policy
      name: '@deepseek-ai/dsh-sandbox-policy'
      config:
        mode: !!js "process.env.DSH_PERMISSION_MODE ?? 'workspace-write'"
        workspaceRoot: !!js process.cwd()

    - id: subprocess
      name: '@deepseek-ai/dsh-subprocess-local'

    - id: bash
      name: '@deepseek-ai/dsh-bash-sandbox'
      config:
        timeoutMs: 60000

    - id: approval
      name: '@deepseek-ai/dsh-user-approval'
      config:
        policy: !!js "(process.env.DSH_PERMISSION_MODE ?? 'workspace-write') === 'danger-full-access' ? 'never' : 'ask'"

    - id: acp-agent
      name: '@deepseek-ai/dsh-acp-demo'
      config:
        provider: deepseek-official
        model: !!js "process.env.DSH_MODEL ?? 'deepseek-v4-pro'"
        persistenceRoot: !!js "(process.env.DSH_ACP_HOME ?? '/root/.dsh/omnibot-acp') + '/sessions'"
        persistenceCompression: !!js "process.env.DSH_PERSISTENCE_COMPRESSION ?? 'zstd'"
        # Keep the official Harness defaults for workspace context, skills,
        # jobs, goals, and tool transport. The host does not disable them.
        workspaceContext:
          maxBytes: 65536
        persona: |
          You are a coding assistant powered by the {{model}} model. Your working directory is {{cwd}}. Your bash tool runs under a file sandbox — a `[sandbox: file access denied …]` result is policy, not a command bug.

          Verify your work by running the code or tests. Keep answers brief and factual.

    - id: token-meter
      name: '@deepseek-ai/dsh-token-meter'

    - id: compaction-basic
      name: '@deepseek-ai/dsh-compaction-basic'
      config:
        thresholdRatio: 0.8
        retainRatio: 0.08
        maxTokens: 8192
        compactionRetries: 1

    - id: session-projection
      name: '@deepseek-ai/dsh-session-projection'

    - id: subagent
      name: '@deepseek-ai/dsh-subagent'

    - id: subagent-spawn-in-process
      name: '@deepseek-ai/dsh-subagent-spawn-in-process'
      config:
        providerName: spawn

    - id: subagent-fork-in-process
      name: '@deepseek-ai/dsh-subagent-fork-in-process'
      config:
        providerName: fork

    - id: tool-subagent-control
      name: '@deepseek-ai/dsh-tool-subagent-control'

    - id: tool-subagent-list-agents
      name: '@deepseek-ai/dsh-tool-subagent-control/list-agents'

    - id: tool-subagent-report
      name: '@deepseek-ai/dsh-tool-subagent-report'

    - id: tool-subagent
      name: '@deepseek-ai/dsh-tool-subagent'
      config:
        provider: spawn
        toolName: subagent
        backgroundMode: continuable
        maxDepth: 1

    - id: tool-subagent-fork
      name: '@deepseek-ai/dsh-tool-subagent'
      config:
        provider: fork
        toolName: subagent_fork
        backgroundMode: one-shot
        enableRunInBackground: false
        maxDepth: 1

    - id: workflow-worker-thread
      name: '@deepseek-ai/dsh-workflow-worker-thread'
      config:
        provider: spawn

    - id: tool-workflow
      name: '@deepseek-ai/dsh-tool-workflow'

    - id: tool-ralph
      name: '@deepseek-ai/dsh-tool-ralph'

    - id: tool-todo
      name: '@deepseek-ai/dsh-tool-todo'
      config:
        allowParallelInProgress: true

    - id: repeat-tool-reminder
      name: '@deepseek-ai/dsh-repeat-tool-reminder'

    - id: fs-sandbox
      name: '@deepseek-ai/dsh-fs-sandbox'
      config:
        cwd: !!js process.cwd()

    - id: fs-observation-policy
      name: '@deepseek-ai/dsh-fs-observation-policy'

    - id: tool-fs
      name: '@deepseek-ai/dsh-tool-fs'

    - id: hooks-claude-code
      name: '@deepseek-ai/dsh-hooks-claude-code'
      config:
        configPath: ./hooks.json

    - id: hooks-codex
      name: '@deepseek-ai/dsh-hooks-codex'
      config:
        configPath: ./codex-hooks.json

    - id: mcp-omnibot
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: omnibot
        transport: streamable-http
        url: !!js process.env.OMNIBOT_MCP_URL
        headers:
          Authorization: !!js "'Bearer ' + process.env.OMNIBOT_MCP_TOKEN"
        failOnStartupError: true
""".trimIndent() + "\n"

internal fun npmPackageName(spec: String): String {
    val versionSeparator = spec.lastIndexOf('@')
    return if (versionSeparator > 0) spec.substring(0, versionSeparator) else spec
}

internal fun isRecoverableAgentThreadError(message: String): Boolean {
    val normalized = message.lowercase()
    return normalized.contains("thread not found") ||
        normalized.contains("unknown session") ||
        normalized.contains("did not advertise session resume or loadsession")
}

internal fun buildCodexConfigToml(
    baseUrl: String,
    model: String
): String {
    val lines = mutableListOf(
        "model_provider = \"omnimind\"",
        "model = ${tomlString(model.trim())}",
        "model_reasoning_effort = \"xhigh\"",
        "disable_response_storage = true",
        "",
        "[model_providers.omnimind]",
        "name = \"omnimind\"",
        "base_url = ${tomlString(baseUrl.trim())}",
        "wire_api = \"responses\"",
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

private const val CLAUDE_CODE_AGENT_ID = "claude-code-acp"
private const val OPENCODE_AGENT_ID = "opencode-acp"
private const val CODEX_CONFIG_TOML_PATH = "/root/.codex/config.toml"
private const val CODEX_AUTH_JSON_PATH = "/root/.codex/auth.json"
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

private fun extractThreadId(value: Any?): String? {
    return extractStringRecursive(
        value = value,
        keys = setOf("threadId", "thread_id"),
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
