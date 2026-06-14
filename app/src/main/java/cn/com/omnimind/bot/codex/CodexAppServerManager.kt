package cn.com.omnimind.bot.codex

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.setup.EnvironmentSetupLogic
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.SkillIndexEntry
import cn.com.omnimind.bot.agent.SkillIndexService
import cn.com.omnimind.bot.agent.WorkspaceMemoryService
import cn.com.omnimind.bot.util.TaskRuntimeSettings
import com.rk.libcommons.OmnibotTerminalEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class CodexAppServerManager private constructor(
    private val context: Context
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()
    private val threadStartMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bindingRepository = CodexThreadBindingRepository(appContext)
    private val remoteConfigStore = CodexRemoteBridgeConfigStore(appContext)
    private val contextInjectionConfigStore = CodexContextInjectionConfigStore(appContext)
    private val activeTurnsByThreadId = ConcurrentHashMap<String, String>()

    @Volatile
    private var pendingThreadStartConversationId: Long? = null

    @Volatile
    private var session: CodexAppServerSession? = null
    @Volatile
    private var activeRuntime: CodexRuntimeKind? = null
    @Volatile
    private var eventListener: ((Map<String, Any?>) -> Unit)? = null

    fun setEventListener(listener: ((Map<String, Any?>) -> Unit)?) {
        eventListener = listener
    }

    suspend fun status(): Map<String, Any?> {
        val runtime = resolveRuntime()
        val connected = session?.isRunning == true && activeRuntime == runtime.kind
        val probe = when (runtime.kind) {
            CodexRuntimeKind.REMOTE -> probeRemoteCodex(runtime.remoteConfig)
            CodexRuntimeKind.LOCAL -> probeCodex()
        }
        return linkedMapOf(
            "connected" to connected,
            "ready" to probe.ready,
            "version" to probe.version,
            "error" to probe.error,
            "codexHome" to CodexAppServerDefaults.CODEX_HOME,
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
        )
    }

    suspend fun connect(): Map<String, Any?> {
        sessionMutex.withLock {
            val runtime = resolveRuntime()
            val existing = session
            if (existing?.isRunning == true && activeRuntime == runtime.kind) {
                return status()
            }
            existing?.disconnect()
            session = null
            activeRuntime = null
            activeTurnsByThreadId.clear()
            val nextSession = CodexAppServerSession(
                context = appContext,
                scope = scope,
                onServerMessage = ::handleServerMessage,
                connectionFactory = when (runtime.kind) {
                    CodexRuntimeKind.REMOTE -> {
                        {
                            RemoteCodexBridgeConnection(
                                config = runtime.remoteConfig,
                                scope = scope
                            )
                        }
                    }
                    CodexRuntimeKind.LOCAL -> null
                }
            )
            session = nextSession
            activeRuntime = runtime.kind
            try {
                nextSession.start(clientVersion = BuildConfig.VERSION_NAME)
            } catch (error: Throwable) {
                if (session === nextSession) {
                    session = null
                }
                if (activeRuntime == runtime.kind) {
                    activeRuntime = null
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
            activeRuntime = null
            activeTurnsByThreadId.clear()
        }
        return status()
    }

    suspend fun invalidateLocalTerminalEnvironment() {
        sessionMutex.withLock {
            if (activeRuntime == CodexRuntimeKind.LOCAL) {
                session?.disconnect()
                session = null
                activeRuntime = null
                activeTurnsByThreadId.clear()
            }
        }
    }

    suspend fun handleMethod(method: String, args: Map<String, Any?>): Any? {
        return when (method) {
            "status" -> status()
            "connect" -> connect()
            "disconnect" -> disconnect()
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
            "collaborationMode/list" -> requestWrappedList(
                "collaborationMode/list",
                args,
                "collaborationModes"
            )
            "config/local/read" -> readLocalConfig()
            "config/local/write" -> writeLocalConfig(args)
            "config/remote/test" -> testRemoteConfig(args)
            "config/remote/fs/list" -> listRemoteDirectories(args)
            "config/remote/fs/read" -> readRemoteFile(args)
            "config/remote/fs/write" -> writeRemoteFile(args)
            "config/remote/fs/delete" -> deleteRemotePath(args)
            "config/remote/fs/move" -> moveRemotePath(args)
            "turn/start" -> startTurn(args)
            "turn/steer" -> steerTurn(args)
            "turn/interrupt" -> interruptTurn(args)
            "review/start" -> startReview(args)
            "account/read" -> request("account/read", null)
            "account/login/start" -> request(
                "account/login/start",
                args.ifEmpty { mapOf("type" to "chatgpt") }
            )
            "account/login/cancel" -> request("account/login/cancel", args)
            "account/rateLimits/read" -> request("account/rateLimits/read", null)
            "respondToServerRequest" -> respondToServerRequest(args)
            else -> request(method, args)
        }
    }

    private suspend fun startThread(args: Map<String, Any?>): Map<String, Any?> = threadStartMutex.withLock {
        val shouldBindLocally = shouldSyncLocalThreadBindings()
        val cwd = sanitizeCodexAbsolutePath(args.stringValue("cwd")) ?: resolveDefaultCwd()
        val conversationId = args.longValue("conversationId")
        val params = linkedMapOf<String, Any?>(
            "cwd" to cwd,
            "approvalPolicy" to (args.stringValue("approvalPolicy") ?: "on-request"),
            "sandboxPolicy" to (args["sandboxPolicy"] ?: buildDefaultCodexSandboxPolicy(cwd))
        )
        args.stringValue("approvalsReviewer")?.let {
            params["approvalsReviewer"] = it
        }
        addCodexOptionalRunParams(params, args)
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
            args["includeTurns"]?.let { params["includeTurns"] = it }
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
            bindingRepository.updateTitle(threadId, name, force = true)
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
        val cwd = sanitizeCodexAbsolutePath(args.stringValue("cwd")) ?: resolveDefaultCwd()
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
                "CodexAppServerManager",
                "Codex turn/start hit a missing thread; creating a fresh thread binding."
            )
            val retryResponse = startThread(args + mapOf("cwd" to cwd))
            threadId = retryResponse["threadId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw error
            params["threadId"] = threadId
            request("turn/start", params) as Map<String, Any?>
        }
        val turnId = extractTurnId(response)
        if (!turnId.isNullOrBlank()) {
            activeTurnsByThreadId[threadId] = turnId
        }
        return response.withLocalIds(
            threadId = threadId,
            conversationId = localConversationIdForThread(threadId),
            turnId = turnId
        )
    }

    private suspend fun startReview(args: Map<String, Any?>): Map<String, Any?> {
        val cwd = sanitizeCodexAbsolutePath(args.stringValue("cwd")) ?: resolveDefaultCwd()
        var threadId = ensureThreadForTurn(args, cwd)
        val params = buildReviewStartParams(
            args = args,
            cwd = cwd,
            threadId = threadId
        )
        val response = try {
            request("review/start", params) as Map<String, Any?>
        } catch (error: Throwable) {
            if (!shouldRecoverMissingThread(error)) {
                throw error
            }
            Log.w(
                "CodexAppServerManager",
                "Codex review/start hit a missing thread; creating a fresh thread binding."
            )
            val retryResponse = startThread(args + mapOf("cwd" to cwd))
            threadId = retryResponse["threadId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw error
            params["threadId"] = threadId
            request("review/start", params) as Map<String, Any?>
        }
        val turnId = extractTurnId(response)
        if (!turnId.isNullOrBlank()) {
            activeTurnsByThreadId[threadId] = turnId
        }
        return response.withLocalIds(
            threadId = threadId,
            conversationId = localConversationIdForThread(threadId),
            turnId = turnId
        )
    }

    private suspend fun steerTurn(args: Map<String, Any?>): Map<String, Any?> {
        val threadId = resolveThreadId(args)
        val expectedTurnId = args.stringValue("expectedTurnId")
            ?: args.stringValue("turnId")
            ?: activeTurnsByThreadId[threadId]
            ?: throw IllegalArgumentException("missing active Codex turn id")
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
        val turnId = args.stringValue("turnId")
            ?: activeTurnsByThreadId[threadId]
            ?: throw IllegalArgumentException("missing active Codex turn id")
        val response = request(
            "turn/interrupt",
            mapOf("threadId" to threadId, "turnId" to turnId)
        ) as Map<String, Any?>
        activeTurnsByThreadId.remove(threadId)
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

    private suspend fun readLocalConfig(): Map<String, Any?> {
        val remoteConfig = remoteConfigStore.read()
        val contextInjectionConfig = contextInjectionConfigStore.read()
        val command = """
            mkdir -p ${shellQuote(CodexAppServerDefaults.CODEX_HOME)}
            printf '__OMNI_CODEX_CONFIG_START__\n'
            if [ -f ${shellQuote("${CodexAppServerDefaults.CODEX_HOME}/config.toml")} ]; then
              cat ${shellQuote("${CodexAppServerDefaults.CODEX_HOME}/config.toml")}
            fi
            printf '\n__OMNI_CODEX_CONFIG_END__\n'
            printf '__OMNI_CODEX_AUTH_START__\n'
            if [ -f ${shellQuote("${CodexAppServerDefaults.CODEX_HOME}/auth.json")} ]; then
              cat ${shellQuote("${CodexAppServerDefaults.CODEX_HOME}/auth.json")}
            fi
            printf '\n__OMNI_CODEX_AUTH_END__\n'
        """.trimIndent()
        val localRead = runCatching {
            val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
                command = command,
                executorKey = "codex-config-read",
                timeoutMs = 30_000L
            )
            if (!result.isOk || result.exitCode != 0) {
                throw IllegalStateException(
                    result.error.ifBlank { result.rawOutputPreview.ifBlank { "Failed to read Codex config." } }
                )
            }
            result.output
        }
        if (localRead.isFailure && !remoteConfig.enabled) {
            throw localRead.exceptionOrNull()
                ?: IllegalStateException("Failed to read Codex config.")
        }
        val localOutput = localRead.getOrDefault("")
        val configToml = extractMarkedBlock(
            localOutput,
            "__OMNI_CODEX_CONFIG_START__",
            "__OMNI_CODEX_CONFIG_END__"
        )
        val authJson = extractMarkedBlock(
            localOutput,
            "__OMNI_CODEX_AUTH_START__",
            "__OMNI_CODEX_AUTH_END__"
        )
        return buildCodexLocalConfigPayload(
            model = extractTomlString(configToml, "model").orEmpty(),
            baseUrl = extractTomlString(configToml, "base_url").orEmpty(),
            apiKey = extractOpenAiApiKey(authJson).orEmpty(),
            remoteConfig = remoteConfig,
            runtime = resolveRuntime().kind.payloadValue,
            contextInjectionEnabled = contextInjectionConfig.enabled
        )
    }

    private suspend fun writeLocalConfig(args: Map<String, Any?>): Map<String, Any?> {
        val storedContextInjectionConfig = contextInjectionConfigStore.read()
        val baseUrl = args.stringValue("baseUrl").orEmpty()
        val model = args.stringValue("model").orEmpty()
        val apiKey = args.stringValue("apiKey").orEmpty()
        val contextInjectionEnabled = args.booleanValue("contextInjectionEnabled")
            ?: storedContextInjectionConfig.enabled
        val remoteConfig = CodexRemoteBridgeConfig(
            enabled = args["remoteEnabled"] == true,
            bridgeUrl = args.stringValue("remoteBridgeUrl").orEmpty(),
            authToken = args.stringValue("remoteBridgeToken").orEmpty(),
            cwd = args.stringValue("remoteCwd").orEmpty()
        )
        val localComplete = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
        if (remoteConfig.enabled && !remoteConfig.isConfigured) {
            throw IllegalArgumentException("Remote Codex bridge URL and cwd are required.")
        }

        val savedRemoteConfig = remoteConfigStore.write(remoteConfig)
        val savedContextInjectionConfig = contextInjectionConfigStore.write(
            CodexContextInjectionConfig(enabled = contextInjectionEnabled)
        )
        if (localComplete) {
            val configToml = buildCodexConfigToml(baseUrl = baseUrl, model = model)
            val authJson = JSONObject()
                .put("OPENAI_API_KEY", apiKey)
                .toString(4) + "\n"
            val configPath = "${CodexAppServerDefaults.CODEX_HOME}/config.toml"
            val authPath = "${CodexAppServerDefaults.CODEX_HOME}/auth.json"
            val command = """
                set -eu
                mkdir -p ${shellQuote(CodexAppServerDefaults.CODEX_HOME)}
                umask 077
                printf %s ${shellQuote(configToml)} > ${shellQuote(configPath)}
                printf %s ${shellQuote(authJson)} > ${shellQuote(authPath)}
                chmod 600 ${shellQuote(configPath)} ${shellQuote(authPath)}
                printf '__OMNI_CODEX_WRITE_OK__\n'
            """.trimIndent()
            val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
                command = command,
                executorKey = "codex-config-write",
                timeoutMs = 30_000L
            )
            if (!result.isOk || result.exitCode != 0) {
                throw IllegalStateException(
                    result.error.ifBlank { result.rawOutputPreview.ifBlank { "Failed to write Codex config." } }
                )
            }
        }
        sessionMutex.withLock {
            session?.disconnect()
            session = null
            activeRuntime = null
            activeTurnsByThreadId.clear()
        }
        return buildCodexLocalConfigPayload(
            model = model,
            baseUrl = baseUrl,
            apiKey = apiKey,
            remoteConfig = savedRemoteConfig,
            runtime = resolveRuntime().kind.payloadValue,
            contextInjectionEnabled = savedContextInjectionConfig.enabled
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

    private fun buildTurnStartParams(
        args: Map<String, Any?>,
        cwd: String,
        threadId: String
    ): MutableMap<String, Any?> {
        val input = resolveInput(args)
        val contextText = if (contextInjectionConfigStore.read().enabled) {
            buildCodexContextInjectionTextForTurn()
        } else {
            null
        }
        val params = linkedMapOf<String, Any?>(
            "threadId" to threadId,
            "input" to buildCodexInputWithOptionalContext(input, contextText),
            "cwd" to cwd,
            "approvalPolicy" to (args.stringValue("approvalPolicy") ?: "on-request"),
            "sandboxPolicy" to (args["sandboxPolicy"] ?: buildDefaultCodexSandboxPolicy(cwd))
        )
        args.stringValue("approvalsReviewer")?.let {
            params["approvalsReviewer"] = it
        }
        addCodexOptionalRunParams(params, args)
        return params
    }

    private fun buildCodexContextInjectionTextForTurn(): String? {
        val workspaceManager = AgentWorkspaceManager(appContext)
        val memoryContext = runCatching {
            WorkspaceMemoryService(appContext, workspaceManager).buildPromptContext()
        }.onFailure { error ->
            Log.w(
                "CodexAppServerManager",
                "Failed to load workspace memory for Codex context injection.",
                error
            )
        }.getOrNull()
        val terminalVariables = runCatching {
            OmnibotTerminalEnvironment.loadUserVariables(appContext)
        }.onFailure { error ->
            Log.w(
                "CodexAppServerManager",
                "Failed to load terminal variables for Codex context injection.",
                error
            )
        }.getOrDefault(emptyMap())
        val skillsContext = runCatching {
            val skillsRoot = workspaceManager.skillsRoot()
            buildCodexSkillsContextText(
                skillsRootShellPath = workspaceManager.shellPathForAndroid(skillsRoot)
                    ?: "${AgentWorkspaceManager.SHELL_ROOT_PATH}/.omnibot/skills",
                skillsRootAndroidPath = skillsRoot.absolutePath,
                skills = SkillIndexService(appContext, workspaceManager).listSkillsForManagement()
            )
        }.onFailure { error ->
            Log.w(
                "CodexAppServerManager",
                "Failed to load skills index for Codex context injection.",
                error
            )
        }.getOrNull()

        return buildCodexContextInjectionText(
            soul = memoryContext?.soul.orEmpty(),
            longTermMemory = memoryContext?.longTermMemory.orEmpty(),
            todayShortMemory = memoryContext?.todayShortMemory.orEmpty(),
            terminalVariables = terminalVariables,
            skillsContext = skillsContext.orEmpty()
        )
    }

    private fun buildReviewStartParams(
        args: Map<String, Any?>,
        cwd: String,
        threadId: String
    ): MutableMap<String, Any?> {
        val params = linkedMapOf<String, Any?>(
            "threadId" to threadId,
            "target" to resolveCodexReviewTarget(args["target"]),
            "delivery" to (args.stringValue("delivery") ?: "inline"),
            "cwd" to cwd,
            "approvalPolicy" to (args.stringValue("approvalPolicy") ?: "on-request"),
            "sandboxPolicy" to (args["sandboxPolicy"] ?: buildDefaultCodexSandboxPolicy(cwd))
        )
        args.stringValue("approvalsReviewer")?.let {
            params["approvalsReviewer"] = it
        }
        addCodexOptionalRunParams(params, args)
        return params
    }

    private fun shouldRecoverMissingThread(error: Throwable): Boolean {
        val message = error.message?.lowercase().orEmpty()
        return message.contains("thread not found")
    }

    private suspend fun ensureThreadForTurn(args: Map<String, Any?>, cwd: String): String {
        val explicitThreadId = args.stringValue("threadId")
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
        return activeRuntime != CodexRuntimeKind.REMOTE &&
            resolveRuntime().kind != CodexRuntimeKind.REMOTE
    }

    private suspend fun localConversationIdForThread(threadId: String): Long? {
        if (!shouldSyncLocalThreadBindings()) {
            return null
        }
        return bindingRepository.getBindingByThreadId(threadId)?.conversationId
    }

    private fun syncActiveTurnSnapshot(threadId: String, response: Map<String, Any?>) {
        val active = codexThreadActivity(response)
        val activeTurnId = extractActiveTurnId(response)
        if (active == true && !activeTurnId.isNullOrBlank()) {
            activeTurnsByThreadId[threadId] = activeTurnId
            return
        }
        if (active == false) {
            activeTurnsByThreadId.remove(threadId)
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

    private suspend fun ensureConnectedSession(): CodexAppServerSession {
        val existing = session
        if (existing?.isRunning == true) {
            return existing
        }
        connect()
        return session ?: throw IllegalStateException("Codex app-server is not connected.")
    }

    private suspend fun handleServerMessage(message: Map<String, Any?>) {
        val method = extractCodexServerMethod(message)
        val explicitParams = extractCodexServerParams(message)
        val params = if (explicitParams.isNotEmpty()) {
            explicitParams
        } else {
            syntheticCodexServerParams(message, method)
        }
        val threadId = extractThreadId(message)
        val turnId = extractTurnId(message) ?: extractActiveTurnId(message)
        // Diagnostic: log every server-side method that reaches Kotlin so the
        // user can verify via `adb logcat -s CodexAppServerManager:V` whether
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
            "CodexAppServerManager",
            "<- method=$method itemType=$diagItemType threadId=$threadId turnId=$turnId"
        )
        val protocolEventType = if (method == "codex/event") {
            codexProtocolEventType(params)
        } else {
            ""
        }
        if (!threadId.isNullOrBlank() && !turnId.isNullOrBlank() &&
            (method == "turn/started" ||
                protocolEventType == "task_started" ||
                protocolEventType == "turn_started")) {
            activeTurnsByThreadId[threadId] = turnId
            TaskRuntimeSettings.onTaskStarted(appContext)
        }
        if (!threadId.isNullOrBlank() && method == "thread/status/changed") {
            val active = codexThreadActivity(message)
            if (active == true && !turnId.isNullOrBlank()) {
                activeTurnsByThreadId[threadId] = turnId
            } else if (active == false) {
                activeTurnsByThreadId.remove(threadId)
            }
        }
        if (!threadId.isNullOrBlank() &&
            (method == "turn/completed" ||
                protocolEventType == "task_complete" ||
                protocolEventType == "turn_complete" ||
                protocolEventType == "turn_aborted")) {
            activeTurnsByThreadId.remove(threadId)
        }
        if (!threadId.isNullOrBlank() &&
            (method == "error" || method == "turn/failed") &&
            params["willRetry"] != true) {
            // codex app-server emits top-level `error` notifications when a
            // turn fails terminally (no follow-up turn/completed will come).
            // Clear the active turn so subsequent thread/read responses
            // surface active=false to the Flutter side.
            activeTurnsByThreadId.remove(threadId)
        }
        if (!threadId.isNullOrBlank() && method == "thread/closed") {
            activeTurnsByThreadId.remove(threadId)
        }

        val localConversationId = syncMessage(method, message, params, threadId)
        if (method == "turn/completed" ||
            protocolEventType == "task_complete" ||
            protocolEventType == "turn_complete") {
            TaskRuntimeSettings.onTaskFinished(appContext)
            TaskRuntimeSettings.notifyTaskFinished(
                context = appContext,
                title = "Codex task completed",
                message = "Tap to view the completed Codex turn.",
                conversationId = localConversationId,
                conversationMode = "codex"
            )
        }
        emitEvent(
            linkedMapOf(
                "method" to method,
                "workspaceId" to CodexAppServerSession.DEFAULT_WORKSPACE_ID,
                "threadId" to threadId,
                "turnId" to turnId,
                "conversationId" to localConversationId,
                "params" to params,
                "message" to message
            )
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
                        cwd = sanitizeCodexAbsolutePath(thread.stringValue("cwd"))
                            ?: sanitizeCodexAbsolutePath(params.stringValue("cwd"))
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
                cwd = sanitizeCodexAbsolutePath(entry.cwd) ?: resolveDefaultCwd(),
                title = entry.title,
                archived = entry.archived
            )
        }
    }

    private fun emitEvent(event: Map<String, Any?>) {
        val listener = eventListener ?: return
        mainHandler.post {
            listener(event)
        }
    }

    private suspend fun probeCodex(): CodexProbe {
        return runCatching {
            val terminalManager = TerminalManager.getInstance(appContext)
            val result = terminalManager.executeHiddenCommand(
                command = EnvironmentSetupLogic.buildInventoryProbeCommand(listOf("codex")),
                executorKey = "codex-probe",
                timeoutMs = 30_000L
            )
            val parsed = EnvironmentSetupLogic.parseInventoryProbeOutput(result.output)
            val codex = parsed["codex"]
            CodexProbe(
                ready = codex?.ready == true,
                version = codex?.version,
                error = if (result.exitCode == 0) null else result.error.ifBlank { result.rawOutputPreview }
            )
        }.getOrElse { error ->
            CodexProbe(
                ready = false,
                version = null,
                error = error.message ?: error.javaClass.simpleName
            )
        }
    }

    private suspend fun probeRemoteCodex(config: CodexRemoteBridgeConfig): CodexProbe {
        val probe = probeCodexRemoteBridge(config)
        return CodexProbe(
            ready = probe.ready,
            version = probe.version,
            error = probe.error,
            details = probe.details
        )
    }

    private suspend fun resolveDefaultCwd(): String {
        val runtime = resolveRuntime()
        if (runtime.kind == CodexRuntimeKind.REMOTE) {
            return runtime.remoteConfig.cwd.trim()
        }
        return runCatching {
            val workspaceRoot = AgentWorkspaceManager.rootDirectory(appContext)
            workspaceRoot.mkdirs()
            if (workspaceRoot.exists() && workspaceRoot.isDirectory) {
                CodexAppServerDefaults.DEFAULT_WORKSPACE_CWD
            } else {
                CodexAppServerDefaults.FALLBACK_CWD
            }
        }.getOrNull() ?: CodexAppServerDefaults.FALLBACK_CWD
    }

    private fun resolveRuntime(): CodexRuntime {
        val remoteConfig = remoteConfigStore.read()
        return if (remoteConfig.enabled) {
            CodexRuntime(CodexRuntimeKind.REMOTE, remoteConfig)
        } else {
            CodexRuntime(CodexRuntimeKind.LOCAL, remoteConfig)
        }
    }

    private suspend fun resolveThreadId(args: Map<String, Any?>): String {
        val explicit = args.stringValue("threadId") ?: args.stringValue("thread_id")
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

    private fun resolveInput(args: Map<String, Any?>): List<Map<String, Any?>> {
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
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "Codex turn input is empty" }
        return buildCodexTextInput(trimmed)
    }

    private data class CodexProbe(
        val ready: Boolean,
        val version: String?,
        val error: String?,
        val details: Map<String, Any?> = emptyMap()
    )

    companion object {
        @Volatile
        private var INSTANCE: CodexAppServerManager? = null

        fun getInstance(context: Context): CodexAppServerManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CodexAppServerManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}

private data class CodexRuntime(
    val kind: CodexRuntimeKind,
    val remoteConfig: CodexRemoteBridgeConfig
)

private enum class CodexRuntimeKind(val payloadValue: String) {
    LOCAL("local"),
    REMOTE("remote")
}

private data class CodexThreadListEntry(
    val threadId: String,
    val cwd: String?,
    val title: String?,
    val archived: Boolean?
)

private const val MAX_CODEX_CONTEXT_VARIABLE_NAMES = 80
private const val MAX_CODEX_CONTEXT_SKILLS = 80
private const val MAX_CODEX_CONTEXT_SKILL_DESCRIPTION_CHARS = 220

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

internal fun sanitizeCodexAbsolutePath(raw: String?): String? {
    val source = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return source
        .lineSequence()
        .map { it.trim() }
        .lastOrNull { line ->
            line.startsWith("/") && line.none { char -> char.isISOControl() }
        }
}

internal fun buildCodexTextInput(text: String): List<Map<String, Any?>> {
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

internal fun buildCodexInputWithOptionalContext(
    input: List<Map<String, Any?>>,
    contextText: String?
): List<Map<String, Any?>> {
    val trimmedContext = contextText?.trim().orEmpty()
    if (trimmedContext.isEmpty()) {
        return input
    }
    return buildCodexTextInput(trimmedContext) + input
}

internal fun buildCodexContextInjectionText(
    soul: String,
    longTermMemory: String,
    todayShortMemory: String,
    terminalVariables: Map<String, String>,
    skillsContext: String = ""
): String? {
    val sections = mutableListOf<String>()

    fun addTextSection(tag: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return
        }
        sections += buildString {
            appendLine("[$tag]")
            appendLine(trimmed)
            append("[/$tag]")
        }
    }

    addTextSection("workspace_soul", soul)
    addTextSection("long_term_memory", longTermMemory)
    addTextSection("today_short_memory", todayShortMemory)
    addTextSection("skills", skillsContext)

    val variableNames = terminalVariables.keys
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()
    if (variableNames.isNotEmpty()) {
        val visibleNames = variableNames.take(MAX_CODEX_CONTEXT_VARIABLE_NAMES)
        sections += buildString {
            appendLine("[terminal_variables]")
            appendLine("Values are hidden. These variable names are configured in Omnibot terminal sessions:")
            visibleNames.forEach { name ->
                appendLine("- $name=(configured, value hidden)")
            }
            val omitted = variableNames.size - visibleNames.size
            if (omitted > 0) {
                appendLine("- ... ($omitted more)")
            }
            append("[/terminal_variables]")
        }
    }

    if (sections.isEmpty()) {
        return null
    }
    return buildString {
        appendLine("[omnibot_context]")
        appendLine("This background context is injected by Omnibot because the user enabled Codex context injection. Use it as persistent user/workspace context, not as the current task request.")
        sections.forEachIndexed { index, section ->
            appendLine()
            append(section)
            if (index != sections.lastIndex) {
                appendLine()
            }
        }
        appendLine()
        append("[/omnibot_context]")
    }
}

internal fun buildCodexSkillsContextText(
    skillsRootShellPath: String,
    skillsRootAndroidPath: String,
    skills: List<SkillIndexEntry>
): String? {
    val installedSkills = skills
        .filter { it.installed }
        .sortedWith(
            compareBy<SkillIndexEntry> { it.source }
                .thenBy { it.name.lowercase() }
        )
    if (installedSkills.isEmpty()) {
        return buildString {
            appendLine("Omnibot skills root (shell): $skillsRootShellPath")
            appendLine("Omnibot skills root (android): $skillsRootAndroidPath")
            appendLine("To create a skill that appears in Omnibot Skill Store, create it at:")
            appendLine("- $skillsRootShellPath/<skill-id>/SKILL.md")
            appendLine("Use lowercase kebab-case for <skill-id>. The directory may include scripts/, references/, assets/, or evals/.")
            append("No installed Omnibot skills are currently indexed.")
        }
    }

    return buildString {
        appendLine("Omnibot skills root (shell): $skillsRootShellPath")
        appendLine("Omnibot skills root (android): $skillsRootAndroidPath")
        appendLine("APK built-in skills are copied into this workspace root before indexing; Codex cannot read APK assets directly.")
        appendLine("To create a skill that appears in Omnibot Skill Store, create it at:")
        appendLine("- $skillsRootShellPath/<skill-id>/SKILL.md")
        appendLine("Use lowercase kebab-case for <skill-id>. The directory may include scripts/, references/, assets/, or evals/.")
        appendLine("When the user asks to create or update a skill, read the indexed skill-creator SKILL.md first if it is available.")
        appendLine()
        appendLine("Indexed skills:")
        installedSkills.take(MAX_CODEX_CONTEXT_SKILLS).forEach { skill ->
            val capabilities = buildList {
                if (skill.hasScripts) add("scripts")
                if (skill.hasReferences) add("references")
                if (skill.hasAssets) add("assets")
                if (skill.hasEvals) add("evals")
            }.ifEmpty { listOf("none") }.joinToString(",")
            val enabledLabel = if (skill.enabled) "enabled" else "disabled"
            val description = skill.description
                .replace('\n', ' ')
                .trim()
                .take(MAX_CODEX_CONTEXT_SKILL_DESCRIPTION_CHARS)
            append("- id=${skill.id} | name=${skill.name} | source=${skill.source} | state=$enabledLabel | path=${skill.shellSkillFilePath} | capabilities=$capabilities")
            if (description.isNotBlank()) {
                append(" | description=$description")
            }
            appendLine()
        }
        val omitted = installedSkills.size - MAX_CODEX_CONTEXT_SKILLS
        if (omitted > 0) {
            append("- ... ($omitted more)")
        }
    }.trim()
}

internal fun buildDefaultCodexSandboxPolicy(cwd: String): Map<String, Any?> {
    val writableRoot = sanitizeCodexAbsolutePath(cwd) ?: CodexAppServerDefaults.FALLBACK_CWD
    return linkedMapOf(
        "type" to "workspaceWrite",
        "writableRoots" to listOf(writableRoot),
        "networkAccess" to true,
        "excludeTmpdirEnvVar" to false,
        "excludeSlashTmp" to false
    )
}

internal fun addCodexOptionalRunParams(
    params: MutableMap<String, Any?>,
    args: Map<String, Any?>
) {
    args["model"]?.let { params["model"] = it }
    args["effort"]?.let { params["effort"] = it }
    resolveCodexCollaborationMode(args)?.let { params["collaborationMode"] = it }
    args["serviceTier"]?.let { params["serviceTier"] = it }
}

internal fun resolveCodexCollaborationMode(args: Map<String, Any?>): Map<String, Any?>? {
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
    }?.normalizeCodexCollaborationModeKind() ?: return null

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

private fun String.normalizeCodexCollaborationModeKind(): String? {
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

private fun buildCodexLocalConfigPayload(
    model: String,
    baseUrl: String,
    apiKey: String,
    remoteConfig: CodexRemoteBridgeConfig,
    runtime: String,
    contextInjectionEnabled: Boolean
): Map<String, Any?> {
    return linkedMapOf(
        "codexHome" to CodexAppServerDefaults.CODEX_HOME,
        "model" to model,
        "baseUrl" to baseUrl,
        "apiKey" to apiKey,
        "remoteEnabled" to remoteConfig.enabled,
        "remoteBridgeUrl" to remoteConfig.bridgeUrl,
        "remoteBridgeToken" to remoteConfig.authToken,
        "remoteCwd" to remoteConfig.cwd,
        "remoteConfigured" to remoteConfig.isConfigured,
        "runtime" to runtime,
        "contextInjectionEnabled" to contextInjectionEnabled
    )
}

private fun buildCodexConfigToml(baseUrl: String, model: String): String {
    return """
        model_provider = "omnimind"
        model = ${tomlString(model)}
        model_reasoning_effort = "xhigh"
        disable_response_storage = true

        [model_providers.omnimind]
        name = "omnimind"
        base_url = ${tomlString(baseUrl)}
        wire_api = "responses"
        requires_openai_auth = true
    """.trimIndent() + "\n"
}

private fun extractMarkedBlock(source: String, startMarker: String, endMarker: String): String {
    val start = source.indexOf(startMarker)
    if (start < 0) return ""
    val bodyStart = start + startMarker.length
    val end = source.indexOf(endMarker, bodyStart)
    if (end < 0) return ""
    return source.substring(bodyStart, end).trim()
}

private fun extractTomlString(source: String, key: String): String? {
    if (source.isBlank()) return null
    val escapedKey = Regex.escape(key)
    val pattern = Regex(
        pattern = """(?m)^\s*$escapedKey\s*=\s*"((?:\\.|[^"\\])*)"\s*(?:#.*)?$"""
    )
    return pattern.find(source)?.groupValues?.getOrNull(1)?.let(::unescapeTomlBasicString)
}

private fun extractOpenAiApiKey(source: String): String? {
    val trimmed = source.trim()
    if (trimmed.isEmpty()) return null
    return runCatching {
        JSONObject(trimmed).optString("OPENAI_API_KEY").trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
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

private fun unescapeTomlBasicString(value: String): String {
    val result = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char != '\\' || index == value.lastIndex) {
            result.append(char)
            index += 1
            continue
        }
        val escaped = value[index + 1]
        when (escaped) {
            'b' -> result.append('\b')
            't' -> result.append('\t')
            'n' -> result.append('\n')
            'f' -> result.append('\u000C')
            'r' -> result.append('\r')
            '"' -> result.append('"')
            '\\' -> result.append('\\')
            else -> result.append(escaped)
        }
        index += 2
    }
    return result.toString()
}

private fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
}

private fun Map<String, Any?>.stringValue(key: String): String? {
    return this[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

private fun Map<String, Any?>.booleanValue(key: String): Boolean? {
    val raw = this[key] ?: return null
    return when (raw) {
        is Boolean -> raw
        is String -> when (raw.trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
        is Number -> raw.toInt() != 0
        else -> null
    }
}

private fun Map<String, Any?>.longValue(key: String): Long? {
    val raw = this[key] ?: return null
    return when (raw) {
        is Number -> raw.toLong()
        is String -> raw.trim().toLongOrNull()
        else -> null
    }
}

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

private fun extractCodexServerMethod(value: Any?, depth: Int = 0): String {
    val map = value as? Map<*, *> ?: return ""
    if (depth > 6) {
        return ""
    }
    val direct = normalizeCodexServerMethod(map["method"]?.toString()?.trim())
    if (direct.isNotBlank()) {
        return direct
    }
    for (key in CODEX_ENVELOPE_KEYS) {
        val nested = extractCodexServerMethod(map[key], depth + 1)
        if (nested.isNotBlank()) {
            return nested
        }
    }
    val rawType = map["type"]?.toString()?.trim()
    if (codexServerTypeLooksLikeMethod(rawType)) {
        return normalizeCodexServerMethod(rawType)
    }
    return ""
}

private fun codexServerTypeLooksLikeMethod(rawType: String?): Boolean {
    val type = rawType?.trim().orEmpty()
    if (type.isBlank()) {
        return false
    }
    val normalized = normalizeCodexServerMethod(type)
    return normalized.contains("/") ||
        normalized == "error" ||
        type in CODEX_THREAD_ITEM_TYPES
}

private fun extractCodexServerParams(value: Any?, depth: Int = 0): Map<String, Any?> {
    val map = value as? Map<*, *> ?: return emptyMap()
    if (depth > 6) {
        return emptyMap()
    }
    val direct = map["params"] as? Map<*, *>
    if (direct != null) {
        val nested = extractCodexServerParams(direct, depth + 1)
        if (nested.isNotEmpty()) {
            return topLevelCodexIds(map) + nested
        }
        val normalized = direct.entries.associate { (entryKey, nestedValue) ->
            entryKey.toString() to nestedValue
        }
        if (normalized.isNotEmpty()) {
            return topLevelCodexIds(map) + normalized
        }
    }
    for (key in CODEX_ENVELOPE_KEYS) {
        if (key == "params") {
            continue
        }
        val nested = extractCodexServerParams(map[key], depth + 1)
        if (nested.isNotEmpty()) {
            return topLevelCodexIds(map) + nested
        }
    }
    return emptyMap()
}

private fun topLevelCodexIds(map: Map<*, *>): Map<String, Any?> {
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

private fun normalizeCodexServerMethod(rawMethod: String?): String {
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

private fun syntheticCodexServerParams(
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

private fun codexProtocolEventType(value: Any?): String {
    val msg = codexProtocolMsg(value) ?: return ""
    return msg["type"]?.toString()?.trim()?.lowercase()
        ?.replace(Regex("[^a-z0-9]+"), "_")
        .orEmpty()
}

private fun codexProtocolMsg(value: Any?, depth: Int = 0): Map<*, *>? {
    val map = value as? Map<*, *> ?: return null
    if (depth > 6) {
        return null
    }
    val direct = map["msg"] as? Map<*, *>
    if (direct != null) {
        return direct
    }
    for (key in CODEX_ENVELOPE_KEYS) {
        val nested = codexProtocolMsg(map[key], depth + 1)
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
        val active = codexActivityFromValue(turn["status"] ?: turn["state"])
        if (active == true) {
            return turn["id"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
    return null
}

private fun codexThreadActivity(value: Any?): Boolean? {
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
        val active = codexActivityFromValue(candidate)
        if (active == true) {
            return true
        }
        if (active == false) {
            inactiveCandidate = false
        }
    }
    for (key in CODEX_ENVELOPE_KEYS) {
        val nested = root[key] as? Map<*, *> ?: continue
        val nestedActivity = codexThreadActivity(nested)
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
            val active = codexActivityFromValue(turn["status"] ?: turn["state"])
            if (active != null) {
                return active
            }
        }
    }
    return inactiveCandidate
}

private fun codexActivityFromValue(value: Any?): Boolean? {
    if (value is Boolean) {
        return value
    }
    val text = codexStatusText(value)?.lowercase()
        ?.replace(Regex("[^a-z0-9]+"), "")
        ?: return null
    return when (text) {
        "running", "active", "busy", "inprogress", "inflight", "executing" -> true
        "idle", "closed", "completed", "complete", "notloaded", "systemerror",
        "failed", "cancelled", "canceled", "interrupted" -> false
        else -> null
    }
}

private fun codexStatusText(value: Any?): String? {
    return when (value) {
        null -> null
        is String -> value.trim().takeIf { it.isNotEmpty() }
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> {
            listOf("type", "status", "state", "value", "name")
                .firstNotNullOfOrNull { key -> codexStatusText(value[key]) }
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

private fun collectThreadEntries(value: Any?): List<CodexThreadListEntry> {
    val entries = mutableListOf<CodexThreadListEntry>()
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
                    entries += CodexThreadListEntry(
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
