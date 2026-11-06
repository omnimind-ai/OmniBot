package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.permission.LocalNetworkPermission
import android.os.Build
import cn.com.omnimind.bot.App

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import cn.com.omnimind.bot.agent.AgentRuntimeUpgradeRequiredException
import com.ai.assistance.operit.terminal.TerminalManager
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ChatGptOAuthProvider
import cn.com.omnimind.baselib.llm.OmniOfficialProvider
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.baselib.llm.PlatformAiProvisioner
import cn.com.omnimind.baselib.llm.ProviderCustomHeaderUtils
import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.llm.SceneModelBindingEntry
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
import com.rk.terminal.runtime.EmbeddedRuntimeInstaller
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.File
import java.util.UUID
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A persisted scene binding is an optional Provider/model override for ACP.
 * When it is absent or stale, Dispatch falls back to the current editing
 * Provider and its verified model catalog; startup must not require a binding.
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

internal fun resolveAgentProviderProfile(
    boundProviderProfileId: String?,
    configuredProfile: ModelProviderProfile?,
    officialProfile: ModelProviderProfile?,
): ModelProviderProfile? {
    val normalizedId = boundProviderProfileId?.trim().orEmpty()
    return configuredProfile?.takeIf { it.id == normalizedId }
        ?: officialProfile?.takeIf {
            it.id == normalizedId && OmniOfficialProvider.isOfficialProfile(normalizedId)
        }
}

internal fun resolveAgentProviderApiKey(
    profile: ModelProviderProfile,
    officialBearerToken: String?,
): String? {
    val key = if (OmniOfficialProvider.isOfficialProfile(profile.id)) {
        officialBearerToken
    } else {
        profile.apiKey
    }
    return key?.trim()?.takeIf(String::isNotEmpty)
}

internal suspend fun fetchAgentProviderModels(
    profile: ModelProviderProfile,
    forceRefresh: Boolean = false,
): List<ProviderModelOption> {
    return if (OmniOfficialProvider.isOfficialProfile(profile.id)) {
        if (forceRefresh) PlatformAiProvisioner.refreshAndGetModels()
        else PlatformAiProvisioner.ensureReadyAndGetModels()
    } else {
        if (Build.VERSION.SDK_INT >= 37) {
            LocalNetworkPermission.requireForEndpoint(App.instance, profile.baseUrl)
        }
        HttpController.fetchProviderModels(
            apiBase = profile.baseUrl,
            apiKey = profile.apiKey,
            customHeaders = profile.customHeaders,
            protocolType = profile.protocolType,
            wireApi = profile.wireApi,
            providerProfile = profile,
        )
    }
}

/** Availability and distribution-scoped connectivity are owned by the host probe. */
internal fun mergeLocalRuntimeStatus(
    hostStatus: Map<String, Any?>,
    transportStatus: Map<String, Any?>,
): Map<String, Any?> = hostStatus + transportStatus.filterKeys {
    it != "ready" && it != "connected"
}

/**
 * Keep the shared ACP session surface separate from host-only conversation
 * fields.  The Flutter bridge may include conversationId, agentId and the
 * legacy threadId so the host can resolve ownership, but none of those are
 * part of an ACP session request.  Every non-local adapter must receive this
 * canonical wire subset.
 */
internal fun standardAcpSessionWireParams(
    method: String,
    args: Map<String, Any?>,
): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    args.stringValue("sessionId")?.let { put("sessionId", it) }
    when (method) {
        "session/fork" -> {
            args.stringValue("cwd")?.let { put("cwd", it) }
            args["additionalDirectories"]?.let { put("additionalDirectories", it) }
        }
        "session/close", "session/delete" -> Unit
        "session/set_mode" -> {
            args.stringValue("modeId")?.let { put("modeId", it) }
        }
        "session/set_config_option" -> {
            args.stringValue("configId")?.let { put("configId", it) }
            if (args.containsKey("value")) put("value", args["value"])
        }
    }
    args["_meta"]?.let { put("_meta", it) }
}


class AgentRuntimeManager private constructor(
    private val context: Context
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()
    private val threadStartMutexes = ConcurrentHashMap<Long, Mutex>()
    private val unboundThreadStartMutex = Mutex()
    // Preparing an official npm ACP adapter is an installation boundary, not
    // a normal switch operation.  Serialize it so rapid agent switches cannot
    // start multiple npm/native builds against the same terminal directory.
    private val managedAcpPreparationGate = ManagedAcpPreparationGate.shared
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bindingRepository = AgentSessionBindingRepository(appContext)
    // ACP deltas arrive much faster than durable bindings can change. Cache
    // positive ownership so every token does not issue a Room query.
    /**
     * Host-side projection of the official ACP session to the local
     * conversation. Local sessions may additionally resolve through the
     * durable binding repository; remote sessions have no local row and live
     * in this registry for the lifetime of the connected bridge.
     */
    private val sessionConversationIds = ConcurrentHashMap<String, Long>()
    private val historyRepository = AgentConversationHistoryRepository(appContext)
    private val remoteConfigStore = CodexRemoteBridgeConfigStore(appContext)
    private val acpAgentProfileStore = AcpAgentProfileStore(appContext)
    private val managedAgentUpdates = ManagedAgentUpdates(
        probe = { profiles, distributionId ->
            check(TerminalDistribution.selected().id == distributionId &&
                EmbeddedRuntimeInstaller.isDistributionReady(appContext, TerminalDistribution.selected())) {
                "Prepare the terminal environment before checking installed Agent versions"
            }
            val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
                command = managedAgentVersionProbeCommand(profiles),
                executorKey = "acp-agent-version-probe-$distributionId",
                timeoutMs = 15_000L,
                distribution = TerminalDistribution.fromId(distributionId),
            )
            check(result.isOk && result.exitCode == 0) { "Agent version probe failed" }
            parseManagedAgentVersionProbe(result.output)
        },
        read = acpAgentProfileStore::packageVersion,
        save = acpAgentProfileStore::savePackageVersion,
    )
    private val managedAgentUninstaller = ManagedAgentUninstaller(
        gate = managedAcpPreparationGate,
        profiles = acpAgentProfileStore::list,
        currentDistribution = { TerminalDistribution.selected().id },
        disconnect = { profile ->
            if (!EmbeddedRuntimeInstaller.isDistributionReady(appContext, TerminalDistribution.selected())) {
                throw ManagedAgentUninstallException(
                    "agent_uninstall_terminal_unavailable", "The terminal environment is unavailable.",
                )
            }
            localRuntimeFor(profile.id).disconnect()
            acpAgentProfileStore.clearHealth(profile.id)
            invalidateLocalProbeCache()
        },
        execute = ::executeManagedAgentUninstall,
        refresh = ::refreshManagedAgentAfterUninstall,
    )
    private val naRuntime by lazy { NaCloudRuntime(appContext, scope, bindingRepository, acpAgentProfileStore, ::emitEvent) }
    private val scheduledTaskScheduler by lazy {
        WorkspaceScheduledTaskScheduler(appContext)
    }
    private val xiaowanScheduleToolBridge = object : AgentScheduleToolBridge {
        override suspend fun createTask(arguments: Map<String, Any?>): Map<String, Any?> =
            scheduledTaskScheduler.createTask(arguments)

        override suspend fun listTasks(): List<Map<String, Any?>> =
            scheduledTaskScheduler.listTasks()

        override suspend fun updateTask(arguments: Map<String, Any?>): Map<String, Any?> =
            scheduledTaskScheduler.updateTask(arguments)

        override suspend fun deleteTask(arguments: Map<String, Any?>): Map<String, Any?> {
            val deleted = scheduledTaskScheduler.deleteTask(
                arguments["taskId"]?.toString() ?: arguments["id"]?.toString().orEmpty()
            )
            return mapOf(
                "deleted" to deleted,
                "success" to deleted,
                "summary" to if (deleted) "定时任务已删除" else "未找到要删除的定时任务"
            )
        }
    }
    // Local ACP profiles are separate executables, so each profile needs its
    // own transport and session registry. Instances are created lazily: using
    // two Agents does not start every installed Harness on app launch.
    private val pendingAcpServerRequests = AcpServerRequestOwnerRegistry()
    // One source of truth for host turn ownership. Scoped views isolate
    // opaque session ids across transports without duplicating lifecycle
    // reservation and terminal logic.
    private val turnOwnershipStore = AcpTurnOwnershipStore()
    private val localAcpRuntimes = ConcurrentHashMap<String, LocalAcpRuntime>()

    private fun createLocalAcpRuntime(agentId: String): LocalAcpRuntime = LocalAcpRuntime(
        context = appContext,
        scope = scope,
        bindingRepository = bindingRepository,
        profileStore = acpAgentProfileStore,
        ownerAgentId = agentId,
        prepareLaunchEnvironment = ::prepareLocalAcpLaunch,
        resolveSessionMcpEnabled = { profile, providerAccess ->
            AcpHarnessAdapters.forProfile(profile)
                .supportsSessionMcp(providerAccess?.credentials ?: currentAgentProviderCredentials())
        },
        prepareSharedProviderBinding = ::prepareSharedProviderBinding,
        buildHandoffContext = ::buildLocalAcpHandoffContext,
        scheduleToolBridge = xiaowanScheduleToolBridge,
        copyConversationHistory = { sourceConversationId, targetConversationId ->
            historyRepository.copyConversationHistory(
                sourceConversationId = sourceConversationId,
                targetConversationId = targetConversationId,
            )
        },
        serverRequestOwners = pendingAcpServerRequests,
        turnOwnership = AcpTurnOwnershipRegistry(turnOwnershipStore, agentId),
        onMessage = ::handleServerMessage
    )

    private fun localRuntimeFor(agentId: String): LocalAcpRuntime {
        val profile = acpAgentProfileStore.requireProfile(agentId)
        require(profile.id != NA_AGENT_ID) { "Na owns its cloud transport." }
        return localAcpRuntimes.computeIfAbsent(profile.id, ::createLocalAcpRuntime)
    }

    private fun allLocalRuntimes(): List<LocalAcpRuntime> = localAcpRuntimes.values.toList()

    // Remote ACP bridges do not expose the host turn until their start
    // response arrives. This registry is the same lifecycle owner used by
    // LocalAcpRuntime; the bridge-specific maps below are only execution
    // resources, never ownership state.
    private val remoteTurnOwnership =
        AcpTurnOwnershipRegistry(turnOwnershipStore, REMOTE_TURN_SCOPE)
    private val pendingTurnThreads = ConcurrentHashMap.newKeySet<String>()
    /** Execution resources only; ACP remains the owner of prompt lifecycle. */
    private val remotePromptExecutions = ConcurrentHashMap<String, AcpPromptExecution>()

    private suspend fun buildLocalAcpHandoffContext(
        conversationId: Long,
        currentPrompt: String?
    ): String? {
        val promptSeed = historyRepository.buildPromptSeed(
            conversationId = conversationId,
            conversationMode = "agent"
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
    private fun admitRemoteTurn(
        threadId: String,
        turnId: String,
        requestId: String? = null,
    ) {
        when (val reservation = remoteTurnOwnership.adopt(threadId, turnId, requestId)) {
            is AcpTurnReservation.Started -> Unit
            is AcpTurnReservation.InFlight -> if (reservation.record.turnId == turnId) {
                requestId?.let { remoteTurnOwnership.attachRequestId(threadId, turnId, it) }
                return
            }
            is AcpTurnReservation.Completed -> return
            is AcpTurnReservation.Busy -> {
                if (reservation.record.turnId == turnId) {
                    requestId?.let { remoteTurnOwnership.attachRequestId(threadId, turnId, it) }
                    return
                }
                throw IllegalStateException(
                    "ACP session $threadId already owns turn " +
                        reservation.record.turnId + ", cannot adopt $turnId."
                )
            }
        }
        TaskRuntimeSettings.onTaskStarted(appContext)
        if (!TaskRuntime.start(appContext, agentTurnRuntimeId(threadId, turnId))) {
            Log.w("AgentRuntimeManager", "Unable to acquire foreground runtime for turn=$turnId")
        }
    }

    private fun clearActiveTurn(
        threadId: String,
        expectedTurnId: String? = null,
        terminalStatus: String = "completed",
    ): Boolean {
        val turnId = remoteTurnOwnership.activeTurnId(threadId) ?: return false
        if (expectedTurnId != null && expectedTurnId != turnId) return false
        if (remoteTurnOwnership.finish(threadId, turnId, status = terminalStatus) == null) {
            return false
        }
        releaseTurnRuntime(threadId, turnId)
        return true
    }

    private suspend fun completeRemotePromptFromResponse(
        sessionId: String,
        turnId: String,
        status: String,
        error: String? = null,
    ): Boolean {
        // The official `session/prompt` response (or its owning request
        // error) is the terminal ACP boundary. Release host resources here;
        // the caller projects the returned result through the shared reducer.
        return clearActiveTurn(sessionId, turnId, terminalStatus = status)
    }

    private fun clearActiveTurns() {
        remoteTurnOwnership.activeRecords().forEach { record ->
            if (remoteTurnOwnership.finish(record.sessionId, record.turnId, status = "cancelled") != null) {
                releaseTurnRuntime(record.sessionId, record.turnId)
            }
        }
    }

    private fun releaseTurnRuntime(sessionId: String, turnId: String) {
        TaskRuntime.finish(appContext, agentTurnRuntimeId(sessionId, turnId))
        TaskRuntimeSettings.onTaskFinished(appContext)
    }

    @Volatile
    private var session: RemoteCodexAppServerSession? = null
    private val localProbeCache = ConcurrentHashMap<String, LocalProbeCache>()
    // The ACP filesystem preload is immutable for the lifetime of this app
    // process. Rewriting it for every Harness switch performs a terminal IPC
    // and, on a physical device, adds roughly 1–2 seconds before the target
    // ACP process can even start. Keep the write as a one-time initialization.
    @Volatile
    private var acpFilesystemCompatReady = false
    @Volatile
    private var eventListener: ((Map<String, Any?>) -> Unit)? = null
    private val eventDispatchLock = Any()
    private val pendingEvents = ArrayDeque<Map<String, Any?>>()
    private val hostEventSequence = AtomicLong(0L)
    private val supplementalEventListeners =
        ConcurrentHashMap<String, (Map<String, Any?>) -> Unit>()

    fun setEventListener(listener: ((Map<String, Any?>) -> Unit)?) {
        synchronized(eventDispatchLock) {
            eventListener = listener
        }
        if (listener != null) {
            mainHandler.post(::drainPendingEvents)
        }
    }

    internal fun setSupplementalEventListener(
        key: String,
        listener: (Map<String, Any?>) -> Unit
    ) {
        supplementalEventListeners[key] = listener
    }

    /** A status belongs to one explicitly addressed endpoint, never a global selection. */
    private suspend fun status(agentId: String): Map<String, Any?> {
        if (agentId == NA_AGENT_ID) return naRuntime.status()
        val remoteConfig = remoteConfigStore.read()
        val remote = agentId == REMOTE_CODEX_AGENT_ID
        val profile = if (remote) null else acpAgentProfileStore.requireProfile(agentId)
        val local = if (remote) null else localAcpRuntimes[agentId]
        val connected = if (remote) session?.isRunning == true else local?.let {
            it.isConnected && (it.connectionDistributionId == null ||
                it.connectionDistributionId == TerminalDistribution.selected().id)
        } == true
        val probe = when {
            remote && !remoteConfig.enabled -> AgentRuntimeProbe(false, null, "Remote ACP bridge is disabled.")
            remote -> probeRemoteCodex(remoteConfig)
            connected -> AgentRuntimeProbe(true, local?.agentVersion(), null)
            else -> probeLocalAcpAgentCached(requireNotNull(profile))
        }
        return linkedMapOf<String, Any?>(
            "connected" to connected,
            "ready" to probe.ready,
            "version" to (local?.agentVersion() ?: probe.version),
            "error" to probe.error,
            "activeAgentId" to agentId,
            "activeAgentName" to (profile?.name ?: "Codex Remote"),
            "agentHome" to if (remote) AgentRuntimeDefaults.CODEX_HOME else null,
            "cwd" to if (remote) remoteConfig.cwd else AgentRuntimeDefaults.DEFAULT_WORKSPACE_CWD,
            "runtime" to if (remote) "remote" else "local",
            "protocol" to "acp",
            "remoteEnabled" to remoteConfig.enabled,
            "remoteBridgeUrl" to remoteConfig.bridgeUrl,
            "remoteCwd" to remoteConfig.cwd,
            "remoteConfigured" to remoteConfig.isConfigured,
            "remoteTransport" to probe.details["acpTransport"],
            "remoteActiveConnections" to probe.details["activeConnections"],
            "remoteUptimeMs" to probe.details["uptimeMs"],
        ).let { payload ->
            if (local == null) payload else mergeLocalRuntimeStatus(payload, local.statusPayload())
        }
    }

    /** The configured PC Bridge has its own connection owner, independent of local Agents. */
    private suspend fun ensureConnectedSession(): RemoteCodexAppServerSession = sessionMutex.withLock {
        val config = remoteConfigStore.read()
        check(config.enabled && config.isConfigured) { "Remote ACP bridge is not configured or enabled." }
        LocalNetworkPermission.requireForEndpoint(context, config.bridgeUrl)
        session?.takeIf { it.isRunning }?.let { return@withLock it }
        session?.let { previous ->
            finishRemoteDisconnect()
            session = null
            previous.disconnect()
        }
        val next = RemoteCodexAppServerSession(
            scope = scope,
            onServerMessage = ::handleServerMessage,
            connectionFactory = { RemoteCodexBridgeConnection(config = config, scope = scope) },
        )
        session = next
        try {
            next.start(clientVersion = BuildConfig.VERSION_NAME)
        } catch (error: Throwable) {
            if (session === next) session = null
            throw error
        }
        next
    }

    /** Explicit transport maintenance; it cannot disconnect another Agent's sessions. */
    private suspend fun disconnect(agentId: String): Map<String, Any?> {
        if (agentId == REMOTE_CODEX_AGENT_ID) {
            sessionMutex.withLock {
                finishRemoteDisconnect()
                val previous = session
                session = null
                previous?.disconnect()
                remotePromptExecutions.values.toList().forEach { execution ->
                    execution.cancelForTransport(CancellationException("Remote ACP runtime disconnected"))
                }
                remotePromptExecutions.clear()
                sessionConversationIds.clear()
                clearActiveTurns()
                pendingTurnThreads.clear()
                remoteTurnOwnership.clear()
                pendingAcpServerRequests.removeForAgent(REMOTE_CODEX_AGENT_ID)
            }
        } else {
            acpAgentProfileStore.requireProfile(agentId)
            localAcpRuntimes[agentId]?.disconnect()
            localProbeCache.remove(agentId)
        }
        return status(agentId)
    }

    /**
     * Provider credentials and the scene model binding are launch inputs for
     * every shared-provider Harness. Once either changes, the old process and
     * Xiaowan's in-process session snapshot are stale. Tear down only the
     * affected local runtime; the next ACP request reconnects from the new
     * canonical configuration.
     */
    suspend fun invalidateSharedProviderRuntime(changedProviderProfileId: String? = null) {
        sessionMutex.withLock {
            val activeProfiles = acpAgentProfileStore.list().filter { profile ->
                AcpAgentProfileStore.usesSharedProvider(profile) &&
                    localAcpRuntimes[profile.id]?.isConnected == true
            }
            if (activeProfiles.isEmpty()) return@withLock
            val boundProviderId = SceneModelBindingStore
                .getBinding("scene.dispatch.model")
                ?.providerProfileId
                ?.trim()
            if (!changedProviderProfileId.isNullOrBlank() &&
                boundProviderId != changedProviderProfileId.trim()
            ) {
                return@withLock
            }
            invalidateLocalProbeCache()
            // Provider credentials and the dispatch model are launch inputs.
            // Do not let a later reconnect reuse an environment assembled
            // from the previous Provider binding.
            activeProfiles.forEach { profile ->
                localRuntimeFor(profile.id).disconnect()
            }
        }
    }

    /**
     * ACP MCP declarations are immutable session setup inputs. Once the user
     * edits the remote MCP set, no live local Agent may keep serving the old
     * definitions or connections. The next request reconnects/resumes through
     * the canonical ACP lifecycle with a fresh SessionCreationParameters list.
     */
    suspend fun invalidateMcpConfiguration() {
        sessionMutex.withLock {
            invalidateLocalProbeCache()
            val connectedProfiles = acpAgentProfileStore.list().filter { profile ->
                localAcpRuntimes[profile.id]?.isConnected == true
            }
            connectedProfiles.forEach { profile ->
                localRuntimeFor(profile.id).disconnect()
            }
        }
    }

    suspend fun handleMethod(method: String, args: Map<String, Any?>): Any? {
        val compatibilityRequest = AcpLegacyCompatibilityAdapter.adapt(method, args)
        return try {
            val response = AcpLegacyCompatibilityAdapter.adaptResponse(
                compatibilityRequest,
                handleCanonicalMethod(compatibilityRequest.method, compatibilityRequest.args),
            )
            if (compatibilityRequest.method in setOf("session/load", "session/resume") && response is Map<*, *>) {
                withContext(Dispatchers.Default) {
                    RemoteHistoryCompatibility.normalizeResponse(response.entries.associate { it.key.toString() to it.value })
                }
            } else response
        } catch (error: Throwable) {
            if (error !is kotlinx.coroutines.CancellationException) {
                Log.e("AgentRuntimeManager", "ACP request failed method=${compatibilityRequest.method} " +
                    "type=${error.javaClass.simpleName} at=${error.stackTrace.take(5).joinToString()}")
            }
            throw error
        }
    }

    /**
     * Canonical ACP application surface. Legacy names are normalized before
     * entering this method, so new business code cannot accidentally create
     * another thread/turn lifecycle.
     */
    private suspend fun handleCanonicalMethod(method: String, args: Map<String, Any?>): Any? {
        val canonicalArgs = AcpSessionCompatibility.canonicalize(method, args)
        // Registry and transport configuration have no selected Agent. They do
        // not enter an ACP process merely to inspect or maintain their data.
        when (method) {
            "agent/list", "agent/refresh", "agent/checkUpdates", "agent/save", "agent/delete",
            "agent/uninstall", "agent/test", "agent/prepare" -> return handleAgentDirectory(method, canonicalArgs)
            "agent/select", "connect" -> throw UnsupportedOperationException(
                "$method is no longer supported; address an Agent through the ACP session surface.",
            )
            "config/remote/read" -> return readRemoteBridgeConfig()
            "config/remote/write" -> return writeRemoteBridgeConfig(canonicalArgs)
            "config/remote/test" -> return testRemoteConfig(canonicalArgs)
            "respondToServerRequest" -> return respondToServerRequest(canonicalArgs)
        }
        val identity = resolveRequestIdentity(
            method,
            if (method == "initialize") canonicalArgs.filterKeys { it == "agentId" } else canonicalArgs,
        )
        val ownedArgs = canonicalArgs + ("agentId" to identity.agentId)
        if (method == "status") return status(identity.agentId)
        if (method == "disconnect") return disconnect(identity.agentId)
        if (identity.agentId == NA_AGENT_ID) return naRuntime.handle(method, ownedArgs)
        if (identity.agentId != REMOTE_CODEX_AGENT_ID) {
            when (method) {
                "agent/config/read" -> return readAgentConfig(ownedArgs)
                "agent/config/write" -> return writeAgentConfig(ownedArgs)
                "agent/config/rollback" -> return rollbackAgentConfig(ownedArgs)
                "model/list" -> if (AcpAgentProfileStore.usesSharedProvider(
                    acpAgentProfileStore.requireProfile(identity.agentId),
                )) return listAuthoritativeProviderModels()
            }
            val runtime = localRuntimeFor(identity.agentId)
            // Resolve the operation before process admission. Unknown methods
            // cannot launch an Agent as a side effect of reporting an error.
            val operation = runtime.prepareRequest(method, ownedArgs)
            ensureLocalAcpConnected(method, ownedArgs, identity)
            return operation()
        }
        // Remote is an explicitly addressed PC Bridge. Enabling its settings
        // cannot redirect a local Conversation or a local Codex session here.
        return when (method) {
            "initialize" -> ensureConnectedSession().initializePayload()
            "session/new" -> startRemoteAcpSession(ownedArgs)
            "session/load", "session/resume" -> loadRemoteAcpSession(ownedArgs)
            "session/list" -> listRemoteAcpSessions(ownedArgs)
            "session/prompt" -> promptRemoteAcpSession(ownedArgs)
            "session/cancel" -> cancelRemoteAcpSession(ownedArgs)
            "\$/cancel_request" -> cancelRemoteAcpRequest(ownedArgs)
            "session/fork", "session/close", "session/delete", "session/set_mode",
            "session/set_config_option" -> forwardRemoteAcpSessionMethod(method, ownedArgs)
            "session/archive" -> archiveThread(ownedArgs, archived = true).withAcpSessionId()
            "session/unarchive" -> archiveThread(ownedArgs, archived = false).withAcpSessionId()
            "session/name/set" -> setThreadName(ownedArgs).withAcpSessionId()
            "model/list" -> requestWrappedList("model/list", ownedArgs, "models")
            "config/read" -> readEffectiveRunConfig()
            "collaborationMode/list" -> requestWrappedList(method, ownedArgs, "collaborationModes")
            "config/remote/fs/list" -> listRemoteDirectories(ownedArgs)
            "config/remote/fs/read" -> readRemoteFile(ownedArgs)
            "config/remote/fs/write" -> writeRemoteFile(ownedArgs)
            "config/remote/fs/delete" -> deleteRemotePath(ownedArgs)
            "config/remote/fs/move" -> moveRemotePath(ownedArgs)
            "review/start" -> startReview(ownedArgs)
            "account/read", "account/login/start", "account/login/cancel", "account/rateLimits/read" ->
                request(method, AcpSessionCompatibility.wireParams(ownedArgs))
            "authenticate", "auth/authenticate" -> request("authenticate", AcpSessionCompatibility.wireParams(ownedArgs))
            "logout", "auth/logout" -> request("logout", AcpSessionCompatibility.wireParams(ownedArgs))
            "providers/list", "auth/providers/list" -> request("providers/list", AcpSessionCompatibility.wireParams(ownedArgs))
            "providers/set", "auth/providers/set" -> request("providers/set", AcpSessionCompatibility.wireParams(ownedArgs))
            "providers/disable", "auth/providers/disable" -> request("providers/disable", AcpSessionCompatibility.wireParams(ownedArgs))
            "notifyAcpExtension" -> {
                val extension = ownedArgs.stringValue("method")
                    ?: throw IllegalArgumentException("Extension method is required.")
                require(isAcpExtensionMethod(extension)) { "ACP notifications must use the extension namespace." }
                ensureConnectedSession().sendNotification(extension, AcpSessionCompatibility.extensionParams(ownedArgs))
                mapOf("ok" to true)
            }
            else -> {
                require(isAcpExtensionMethod(method)) { "Unsupported ACP method: $method" }
                request(method, AcpSessionCompatibility.extensionParams(ownedArgs))
            }
        }
    }

    private suspend fun handleAgentDirectory(method: String, args: Map<String, Any?>): Any? {
        val distribution = TerminalDistribution.selected()
        val distributionId = distribution.id
        fun catalog(): Any? = withAgentPackageVersions(acpAgentProfileStore.catalogPayload(), distributionId)
        when (method) {
            "agent/list" -> return catalog()
            "agent/refresh", "agent/checkUpdates" -> {
                if (method == "agent/checkUpdates") {
                    managedAgentUpdates.check(acpAgentProfileStore.list(), distributionId)
                }
                if (EmbeddedRuntimeInstaller.isDistributionReady(appContext, TerminalDistribution.selected())) {
                    acpAgentProfileStore.list().filter { it.id != NA_AGENT_ID }.forEach { profile ->
                        localRuntimeFor(profile.id).refreshAvailability()
                    }
                    managedAgentUpdates.check(acpAgentProfileStore.list(), distributionId, remote = false)
                }
                check(TerminalDistribution.selected().id == distributionId) {
                    "Terminal environment changed. Refresh Agent settings."
                }
                return catalog()
            }
            "agent/save" -> {
                val profile = args.mapValue("agent").ifEmpty { args }
                val saved = acpAgentProfileStore.save(AcpAgentProfile(
                    id = profile.stringValue("id").orEmpty(),
                    name = profile.stringValue("name").orEmpty(),
                    command = profile.stringValue("command").orEmpty(),
                    arguments = (profile["arguments"] as? List<*>)?.map { it.toString() }.orEmpty(),
                    environment = profile.mapValue("environment").mapValues { it.value?.toString().orEmpty() },
                    enabled = profile["enabled"] != false,
                ))
                invalidateLocalProbeCache()
                return mapOf("agent" to saved.toPayload(health = acpAgentProfileStore.health(saved.id)), "catalog" to catalog())
            }
            "agent/delete" -> {
                val id = args.stringValue("agentId") ?: throw IllegalArgumentException("agent_identity_required: agentId is required.")
                acpAgentProfileStore.requireProfile(id)
                localAcpRuntimes[id]?.disconnect()
                localAcpRuntimes.remove(id)
                acpAgentProfileStore.delete(id)
                invalidateLocalProbeCache()
                return catalog()
            }
            "agent/uninstall" -> return uninstallManagedAgent(args, distributionId)
        }
        val id = args.stringValue("agentId") ?: throw IllegalArgumentException("agent_identity_required: agentId is required.")
        val profile = acpAgentProfileStore.requireProfile(id)
        if (id == NA_AGENT_ID) return naRuntime.handle(method, args)
        if (method == "agent/prepare") {
            require(args.stringValue("distributionId")?.let { it == distributionId } != false) {
                "Terminal environment changed. Refresh Agent settings before installing."
            }
            try {
                ensureManagedAcpAdapter(profile, force = args["force"] == true, distribution = distribution)
            } finally {
                managedAgentUpdates.check(listOf(profile), distributionId, remote = false)
            }
            check(TerminalDistribution.selected().id == distributionId) { "Terminal environment changed. Refresh Agent settings." }
        }
        invalidateLocalProbeCache()
        return withAgentPackageVersions(localRuntimeFor(id).handleMethod(method, args), distributionId)
    }

    /** One host admission context, resolved before choosing any transport. */
    private data class AcpRequestIdentity(
        val agentId: String,
        val conversationId: Long?,
        val explicitSessionMatchesConversation: Boolean,
    )

    private suspend fun resolveRequestIdentity(method: String, args: Map<String, Any?>): AcpRequestIdentity {
        val conversationId = args.longValue("conversationId")
        val conversation = conversationId?.takeIf { it > 0 }?.let { DatabaseHelper.getConversationById(it) }
        val mode = conversation?.mode ?: args.stringValue("conversationMode")
        val explicitSessionId = args.stringValue("sessionId") ?: args.stringValue("threadId")
        val conversationSessionId = conversationId?.takeIf { it > 0 }
            ?.let { bindingRepository.getBindingByConversationId(it)?.threadId }
        val requestedAgentId = args.stringValue("agentId")
        val conversationAgentId = conversationId?.let(acpAgentProfileStore::agentIdForConversation)
        // Remote session ids are scoped to the bridge and may equal a local id.
        // Its explicit endpoint identity must not consult a local session index.
        val remoteOwner = (conversationAgentId ?: requestedAgentId) == REMOTE_CODEX_AGENT_ID
        val explicitBindingConversation = if (remoteOwner) null else
            explicitSessionId?.let { bindingRepository.getBindingByThreadId(it)?.conversationId }
        val matchesConversation = remoteOwner || explicitThreadMatchesConversation(
            explicitThreadId = explicitSessionId,
            requestedConversationId = conversationId,
            boundConversationId = explicitBindingConversation,
            ownsUnboundSession = method == "session/prompt" && explicitSessionId != null &&
                allLocalRuntimes().any { it.ownsSession(explicitSessionId) },
        )
        val sessionAgentId = if (remoteOwner) null else
            conversationSessionId?.let(acpAgentProfileStore::agentIdForSession)
                ?: explicitSessionId?.takeIf { matchesConversation }?.let(acpAgentProfileStore::agentIdForSession)
        acpAgentProfileStore.requireSupportedAgent(requestedAgentId, conversationAgentId, sessionAgentId)
        val resolution = AgentConversationModePolicy.resolveHarness(
            conversationMode = mode,
            requestedAgentId = requestedAgentId,
            conversationAgentId = conversationAgentId,
            sessionAgentId = sessionAgentId,
            xiaowanAgentId = AcpAgentProfileStore.XIAOWAN_AGENT_ID,
        )
        require(!resolution.hasConflict) {
            "agent_owner_conflict: Conversation/session belongs to ${resolution.conflictWithAgentId}; " +
                "create a new conversation to use ${resolution.requestedAgentId}."
        }
        if (resolution.agentId != REMOTE_CODEX_AGENT_ID) acpAgentProfileStore.requireProfile(resolution.agentId)
        return AcpRequestIdentity(resolution.agentId, conversationId, matchesConversation)
    }

    private fun withAgentPackageVersions(payload: Any?, distributionId: String): Any? {
        if (payload !is Map<*, *>) return payload
        val result = payload.entries.associate { it.key.toString() to it.value }.toMutableMap()
        if (result.containsKey("agents")) {
            result["distributionId"] = distributionId
            result["retiredAgentIds"] = acpAgentProfileStore.retiredAgentIds.toList()
        }
        fun enrich(value: Any?): Any? {
            if (value !is Map<*, *>) return value
            val id = value["id"] as? String ?: return value
            return value + mapOf("packageVersion" to acpAgentProfileStore.packageVersion(distributionId, id)?.toPayload())
        }
        (result["agents"] as? List<*>)?.let { result["agents"] = it.map(::enrich) }
        result["agent"]?.let { result["agent"] = enrich(it) }
        result["catalog"]?.let { result["catalog"] = withAgentPackageVersions(it, distributionId) }
        return result
    }

    /** JSON-RPC request cancellation is a notification, not session/cancel. */
    private suspend fun cancelRemoteAcpRequest(
        args: Map<String, Any?>
    ): Map<String, Any?> {
        val requestId = args["requestId"] ?: args["id"]
            ?: throw IllegalArgumentException("requestId is required")
        // JSON-RPC request cancellation belongs to the transport that owns
        // the request. Reconnecting would send a cancellation for an old
        // request id to an unrelated transport and can never cancel the
        // original operation.
        val activeSession = session?.takeIf { it.isRunning }
            ?: return mapOf(
                "ok" to true,
                "cancelled" to false,
                "requestId" to requestId,
            )
        activeSession.sendNotification(
            "\$/cancel_request",
            mapOf("requestId" to requestId),
        )
        return mapOf("ok" to true, "cancelled" to true, "requestId" to requestId)
    }

    private suspend fun startRemoteAcpSession(
        args: Map<String, Any?>
    ): Map<String, Any?> {
        val cwd = sanitizeAgentRuntimeAbsolutePath(args.stringValue("cwd"))
            ?: resolveRemoteCwd()
        val params = linkedMapOf<String, Any?>(
            "cwd" to cwd,
            "mcpServers" to emptyList<Any?>()
        )
        args["additionalDirectories"]?.let { params["additionalDirectories"] = it }
        args.stringValue("model")?.let { params["model"] = it }
        // ACP session/new has no universal reasoning-effort parameter. The
        // Agent must advertise a thought-level config option, which is then
        // changed through the official session/set_config_option method.
        val response = request("session/new", params)
        val payload = response as? Map<String, Any?> ?: emptyMap()
        val sessionId = extractThreadId(payload)
            ?: payload.stringValue("id")
            ?: throw IllegalStateException("ACP session/new did not return a session id.")
        args.stringValue("effort")?.let { requestedEffort ->
            val configId = resolveAdvertisedReasoningEffortConfigId(
                payload = payload,
                requestedEffort = requestedEffort,
            ) ?: throw IllegalArgumentException(
                "ACP Agent did not advertise reasoning effort '$requestedEffort'."
            )
            request(
                "session/set_config_option",
                mapOf(
                    "sessionId" to sessionId,
                    "configId" to configId,
                    "value" to requestedEffort,
                ),
            )
        }
        bindSessionConversation(sessionId, args.longValue("conversationId"))
        return payload.withAcpSessionId().withLocalIds(
            threadId = sessionId,
            conversationId = conversationIdForSession(sessionId)
        )
    }

    private suspend fun loadRemoteAcpSession(
        args: Map<String, Any?>
    ): Map<String, Any?> {
        val sessionId = args.stringValue("sessionId")
            ?: args.stringValue("threadId")
            ?: throw IllegalArgumentException("sessionId is required")
        val params = linkedMapOf<String, Any?>("sessionId" to sessionId)
        args.stringValue("cwd")?.let { params["cwd"] = it }
        args["additionalDirectories"]?.let { params["additionalDirectories"] = it }
        args["_meta"]?.let { params["_meta"] = it }
        bindSessionConversation(sessionId, args.longValue("conversationId"))
        return try {
            (request("session/load", params) as? Map<String, Any?> ?: emptyMap())
                .withAcpSessionId()
                .withLocalIds(
                    threadId = sessionId,
                    conversationId = conversationIdForSession(sessionId),
                )
        } catch (error: Throwable) {
            if (!isUnsupportedRemoteAcpMethod(error)) throw error
            requestWithResolvedThread("thread/resume", args)
                .withAcpSessionId()
                .withLocalIds(
                    threadId = sessionId,
                    conversationId = conversationIdForSession(sessionId),
                )
        }
    }

    private suspend fun listRemoteAcpSessions(
        args: Map<String, Any?>
    ): Map<String, Any?> {
        val params = linkedMapOf<String, Any?>()
        args["cursor"]?.let { params["cursor"] = it }
        args.stringValue("cwd")?.let { params["cwd"] = it }
        args["_meta"]?.let { params["_meta"] = it }
        return try {
            // Some ACP bridges return the list directly as the JSON-RPC
            // result, while others wrap it in {sessions: [...]}. Normalize
            // both forms at this boundary so clients never see an empty
            // session list merely because the bridge chose the compact form.
            val rawResponse = request("session/list", params)
            val response = when (rawResponse) {
                is Map<*, *> -> rawResponse.entries.associate { (key, value) ->
                    key.toString() to value
                }
                is List<*> -> mapOf("sessions" to rawResponse)
                else -> emptyMap()
            }
            response.withAcpSessions()
        } catch (error: Throwable) {
            if (!isUnsupportedRemoteAcpMethod(error)) throw error
            listThreads(args).withAcpSessions()
        }
    }

    private fun bindSessionConversation(sessionId: String, conversationId: Long?) {
        val normalized = sessionId.trim()
        if (normalized.isEmpty() || conversationId == null) return
        val existing = sessionConversationIds.putIfAbsent(normalized, conversationId)
        if (existing != null && existing != conversationId) {
            // A session is reusable across turns, but it is not reusable
            // across local conversations. Overwriting this binding would
            // route late tool/update events into the wrong history. Require
            // the caller to explicitly close/unbind before changing owner.
            throw IllegalStateException(
                "ACP session $normalized is already bound to conversation $existing."
            )
        }
    }

    private fun unbindSessionConversation(sessionId: String) {
        sessionConversationIds.remove(sessionId.trim())
    }

    /** Forward optional ACP session methods with only their official fields. */
    private suspend fun forwardRemoteAcpSessionMethod(
        method: String,
        args: Map<String, Any?>,
    ): Map<String, Any?> {
        val sessionId = args.stringValue("sessionId")
            ?: args.stringValue("threadId")
            ?: throw IllegalArgumentException("sessionId is required")
        val conversationId = args.longValue("conversationId")
        bindSessionConversation(sessionId, conversationId)
        val params = standardAcpSessionWireParams(
            method = method,
            args = args + ("sessionId" to sessionId),
        )
        require(params["sessionId"] == sessionId) {
            "ACP $method requires sessionId"
        }
        if (method == "session/set_mode") {
            require(params.stringValue("modeId") != null) {
                "ACP session/set_mode requires modeId"
            }
        }
        if (method == "session/set_config_option") {
            require(params.stringValue("configId") != null) {
                "ACP session/set_config_option requires configId"
            }
            require(params.containsKey("value")) {
                "ACP session/set_config_option requires value"
            }
        }
        val rawResponse = if (method == "session/close" || method == "session/delete") {
            // Closing/deleting a session is a lifecycle boundary. First let
            // the same ACP cancellation path settle an active turn; do not
            // close a live session underneath an executing prompt.
            remoteTurnOwnership.activeTurnId(sessionId)?.let { activeTurnId ->
                cancelRemoteAcpSession(
                    args + mapOf("turnId" to activeTurnId),
                )
            }
            // Unlike session/load, close/delete must never resurrect a dead
            // transport merely to send a request for the old session.
            val activeSession = session?.takeIf { it.isRunning }
                ?: throw IllegalStateException(
                    "Remote ACP transport is unavailable for $method."
                )
            val response = activeSession.sendRequest(method, params)
            val error = response["error"]
            if (error != null) throw IllegalStateException(error.toString())
            response["result"] ?: response
        } else {
            request(method, params)
        }
        val response = (rawResponse as? Map<*, *>).orEmpty().entries.associate {
            it.key.toString() to it.value
        }
        val responseSessionId = response.stringValue("sessionId")
            ?: response.stringValue("threadId")
            ?: sessionId
        val result = response.withAcpSessionId().withLocalIds(
            threadId = responseSessionId,
            conversationId = conversationIdForSession(sessionId),
        )
        if (method == "session/close" || method == "session/delete") {
            unbindSessionConversation(sessionId)
            unbindSessionConversation(responseSessionId)
        }
        return result
    }

    private suspend fun promptRemoteAcpSession(
        args: Map<String, Any?>
    ): Map<String, Any?> {
        val sessionId = args.stringValue("sessionId")
            ?: args.stringValue("threadId")
            ?: startRemoteAcpSession(args)["sessionId"]?.toString()
            ?: throw IllegalStateException("ACP session/new did not return a session id.")
        bindSessionConversation(sessionId, args.longValue("conversationId"))
        val requestId = args.stringValue("requestId")?.takeIf { it.isNotBlank() }
        requestId?.let { id ->
            remoteTurnOwnership.requestRecord(sessionId, id)?.let { known ->
                // A transport retry must not execute remote tools a second
                // time. The first prompt still owns the event stream; return
                // its identity so the caller can keep observing that turn.
                return linkedMapOf<String, Any?>(
                    "sessionId" to known.sessionId,
                    "threadId" to known.sessionId,
                    "promptId" to known.turnId,
                    "turnId" to known.turnId,
                    "deduplicated" to true,
                    "completed" to (known.terminal != null),
                    "status" to known.terminal?.status,
                    "error" to known.terminal?.error,
                    "conversationId" to conversationIdForSession(sessionId),
                ).filterValues { it != null }
            }
        }
        val turnId = UUID.randomUUID().toString()
        admitRemoteTurn(sessionId, turnId, requestId)
        val promptJob = coroutineContext[Job]
        val execution = AcpPromptExecution(promptJob)
        promptJob?.let { execution.attachPromptJob(it) }
        remotePromptExecutions[sessionId] = execution
        var terminalStatus = "completed"
        return try {
            val prompt = resolveInput(args, sessionId).map { block ->
                LinkedHashMap<String, Any?>().apply {
                    block.forEach { (key, value) ->
                        if (key != "text_elements") put(key, value)
                    }
                }
            }
            if (!execution.tryStartPrompt()) {
                throw CancellationException("ACP prompt cancelled before admission")
            }
        val response = request(
                "session/prompt",
                linkedMapOf<String, Any?>(
                    "sessionId" to sessionId,
                    "prompt" to prompt,
                ).apply {
                    args["_meta"]?.let { put("_meta", it) }
                }
            )
            val payload = response as? Map<String, Any?> ?: emptyMap()
            terminalStatus = terminalStatusFromAcpParams(payload)
            completeRemotePromptFromResponse(
                sessionId = sessionId,
                turnId = turnId,
                status = terminalStatus,
            )
            payload.withAcpSessionId().withLocalIds(
                threadId = sessionId,
                conversationId = conversationIdForSession(sessionId),
                turnId = turnId
            ).toMutableMap().apply {
                put("completed", true)
                payload.stringValue("stopReason")?.let { put("status", it) }
            }
        } catch (error: Throwable) {
            terminalStatus = when {
                error is TimeoutCancellationException -> "timeout"
                error is CancellationException -> "cancelled"
                else -> "error"
            }
            // Only the owning session/prompt request reports terminal failure.
            val detail = error.message?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: error.javaClass.simpleName
            completeRemotePromptFromResponse(
                sessionId = sessionId,
                turnId = turnId,
                status = terminalStatus,
                error = detail.takeIf { terminalStatus != "cancelled" },
            )
            throw error
        } finally {
            remotePromptExecutions.remove(sessionId, execution)
        }
    }

    private suspend fun cancelRemoteAcpSession(
        args: Map<String, Any?>
    ): Map<String, Any?> {
        val sessionId = args.stringValue("sessionId")
            ?: args.stringValue("threadId")
            ?: throw IllegalArgumentException("sessionId is required")
        bindSessionConversation(sessionId, args.longValue("conversationId"))
        val turnId = args.stringValue("turnId") ?: remoteTurnOwnership.activeTurnId(sessionId)
        if (turnId.isNullOrBlank()) {
            return mapOf(
                "ok" to true,
                "cancelled" to false,
                "sessionId" to sessionId,
                "threadId" to sessionId,
            )
        }
        // Cancellation is scoped to the session that admitted this turn. It
        // must not call ensureConnectedSession(): reconnecting here can send
        // session/cancel for an already-terminated turn on a new transport.
        val activeSession = session?.takeIf { it.isRunning }
        if (activeSession == null) {
            throw IllegalStateException(
                "Remote ACP transport is unavailable for session/cancel."
            )
        }
        val execution = remotePromptExecutions[sessionId]
        val promptStarted = execution?.requestCancellation() == true
        if (execution == null || promptStarted) {
            // ACP defines session/cancel as a notification. The host still
            // returns an acknowledgement to Flutter, but the Agent wire must
            // not wait for a JSON-RPC response that compliant Agents never
            // send. If the execution resource is unexpectedly absent, keep
            // the historical best-effort cancellation for the active session.
            // The bridge may acknowledge cancellation while the original
            // session/prompt request is still suspended. Let that request
            // observe the official ACP terminal response before releasing
            // host ownership; cancelling the waiter here would discard the
            // response and create a second, locally invented terminal path.
            activeSession.sendNotification(
                "session/cancel",
                mapOf("sessionId" to sessionId),
            )
        }
        // `session/cancel` is a notification. The active prompt remains the
        // owner until the Agent returns its official PromptResponse; this
        // method must not invent a local cancelled terminal or force-close a
        // still-valid ACP transport after an arbitrary grace period.
        return mapOf(
            "cancelled" to true,
            "sessionId" to sessionId,
            "threadId" to sessionId,
            "turnId" to turnId,
        ).withAcpSessionId()
            .withLocalIds(
                threadId = sessionId,
                conversationId = conversationIdForSession(sessionId),
                turnId = turnId,
            )
    }


    private suspend fun startThread(args: Map<String, Any?>): Map<String, Any?> {
        val conversationId = args.longValue("conversationId")
        val mutex = conversationId?.let {
            threadStartMutexes.computeIfAbsent(it) { Mutex() }
        } ?: unboundThreadStartMutex
        return mutex.withLock {
            startThreadInternal(args)
        }
    }

    private suspend fun startThreadInternal(args: Map<String, Any?>): Map<String, Any?> {
        val cwd = sanitizeAgentRuntimeAbsolutePath(args.stringValue("cwd")) ?: resolveRemoteCwd()
        val params = linkedMapOf<String, Any?>(
            "cwd" to cwd,
            "approvalPolicy" to (args.stringValue("approvalPolicy") ?: "on-request"),
            "sandbox" to resolveAgentSandboxMode(args["sandboxPolicy"] ?: buildAgentSandboxPolicy(cwd)),
        )
        args.stringValue("approvalsReviewer")?.let { params["approvalsReviewer"] = it }
        addAgentOptionalRunParams(params, args)
        val response = request("thread/start", params) as Map<String, Any?>
        val sessionId = extractThreadId(response) ?: response.stringValue("id")
        sessionId?.let { bindSessionConversation(it, args.longValue("conversationId")) }
        return response.withLocalIds(threadId = sessionId,
            conversationId = sessionId?.let { conversationIdForSession(it) })
    }

    private suspend fun listThreads(args: Map<String, Any?>): Map<String, Any?> {
        val params = linkedMapOf<String, Any?>()
        args["cursor"]?.let { params["cursor"] = it }
        args["limit"]?.let { params["limit"] = it }
        args["sortKey"]?.let { params["sortKey"] = it }
        params["sourceKinds"] = args["sourceKinds"] ?: DEFAULT_CODEX_THREAD_SOURCE_KINDS
        val response = request("thread/list", params) as Map<String, Any?>
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
        val activeTurnId = remoteTurnOwnership.activeTurnId(threadId)
        return response.withLocalIds(
            threadId = threadId,
            conversationId = conversationIdForSession(threadId),
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
        return response.withLocalIds(
            threadId = threadId,
            conversationId = conversationIdForSession(threadId)
        )
    }

    private suspend fun setThreadName(args: Map<String, Any?>): Map<String, Any?> {
        val threadId = resolveThreadId(args)
        val name = args.stringValue("name") ?: args.stringValue("threadName") ?: ""
        val response = request(
            "thread/name/set",
            mapOf("threadId" to threadId, "name" to name)
        ) as Map<String, Any?>
        return response.withLocalIds(
            threadId = threadId,
            conversationId = conversationIdForSession(threadId)
        )
    }

    private suspend fun requestWrappedList(
        method: String,
        args: Map<String, Any?>,
        listKey: String
    ): Map<String, Any?> {
        val wireArgs = AcpSessionCompatibility.wireParams(args)
        val response = request(method, wireArgs.ifEmpty { null })
        return when (response) {
            is Map<*, *> -> response.entries.associate { (key, value) -> key.toString() to value }
            is List<*> -> mapOf(listKey to response)
            else -> mapOf(listKey to emptyList<Any?>(), "raw" to response)
        }
    }

    private suspend fun startReview(args: Map<String, Any?>): Map<String, Any?> {
        val cwd = sanitizeAgentRuntimeAbsolutePath(args.stringValue("cwd")) ?: resolveRemoteCwd()
        val threadId = ensureThreadForTurn(args, cwd)
        val params = buildReviewStartParams(
            args = args,
            threadId = threadId
        )
        request(
            "thread/settings/update",
            buildAgentThreadSettingsUpdateParams(args, cwd, threadId),
        )
        // A failed request for an existing session does not authorize a new
        // session or a second review. Return the transport's original error.
        val response = request("review/start", params) as Map<String, Any?>
        val turnId = extractTurnId(response)
        if (!turnId.isNullOrBlank()) {
            admitRemoteTurn(threadId, turnId)
        }
        return response.withLocalIds(
            threadId = threadId,
                conversationId = conversationIdForSession(threadId),
            turnId = turnId
        ).withAcpSessionId()
    }

    private suspend fun respondToServerRequest(args: Map<String, Any?>): Map<String, Any?> {
        val requestId = args["requestId"] ?: args["id"]
            ?: throw IllegalArgumentException("requestId is required")
        val sessionId = args.stringValue("sessionId")
            ?: args.stringValue("threadId")
        val pendingRequestOwner = pendingAcpServerRequests.resolve(
            requestId = requestId,
            agentId = args.stringValue("agentId"),
            sessionId = sessionId,
        )
        val requestedAgentId = args.stringValue("agentId")
        val remoteReply = (pendingRequestOwner?.agentId ?: requestedAgentId) == REMOTE_CODEX_AGENT_ID
        val route = resolveAcpServerRequestRoute(
            requestedAgentId = requestedAgentId,
            sessionAgentId = if (remoteReply) null else sessionId?.let(acpAgentProfileStore::agentIdForSession),
            conversationAgentId = args.longValue("conversationId")?.let(acpAgentProfileStore::agentIdForConversation),
            pendingRequestAgentId = pendingRequestOwner?.agentId,
        )
        if (route is AcpServerRequestRoute.Local) {
            val runtime = localAcpRuntimes[route.agentId]
                ?: throw IllegalStateException("The ACP request's Agent transport is no longer connected.")
            return runtime.handleMethod("respondToServerRequest", args + ("agentId" to route.agentId))
                as Map<String, Any?>
        }
        val result = args["response"] ?: args["result"]
            ?: throw IllegalArgumentException("response is required")
        val owner = session?.takeIf { it.isRunning }
            ?: throw IllegalStateException("The remote ACP request's transport is no longer connected.")
        owner.sendResponse(requestId, result)
        pendingAcpServerRequests.remove(requestId, REMOTE_CODEX_AGENT_ID, sessionId)
        return mapOf("ok" to true)
    }

    private suspend fun readRemoteBridgeConfig(): Map<String, Any?> {
        val remoteConfig = remoteConfigStore.read()
        return buildRemoteBridgeConfigPayload(
            remoteConfig = remoteConfig,
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
        val adapterConfig = AgentConfigAdapterRegistry.readConfig(
            input = AgentProviderMappingInput(
                agentId = profile.id,
                provider = currentAgentProviderCredentials(),
                model = currentAgentBoundModel(),
                harnessAdapter = harnessAdapter,
                providerProfile = currentAgentProviderProfile(),
            ),
            access = object : AgentConfigFileAccess {
                override suspend fun read(path: String, executorKey: String): String =
                    readTerminalTextFile(path, executorKey)

                override suspend fun write(path: String, content: String, executorKey: String) =
                    writeTerminalTextFile(path, content, executorKey)
            },
        )
        return withAgentConfigMetadata(
            profile.id,
            adapterConfig ?: linkedMapOf(
                "agentId" to profile.id,
                "kind" to "profile",
            ),
        )
    }

    private suspend fun writeAgentConfig(args: Map<String, Any?>): Map<String, Any?> {
        val agentId = args.stringValue("agentId")
            ?: throw IllegalArgumentException("agentId is required.")
        val profile = acpAgentProfileStore.list().firstOrNull { it.id == agentId }
            ?: throw IllegalArgumentException("Unknown ACP agent: $agentId")
        return localRuntimeFor(agentId).withLaunchConfiguration { providerAccess ->
            validateExpectedConfigRevision(agentId, args)
            writeAgentConfigFiles(profile, args, providerAccess)
        }
    }

    private suspend fun writeAgentConfigFiles(
        profile: AcpAgentProfile,
        args: Map<String, Any?>,
        providerAccess: AgentProviderAccess?,
    ): Map<String, Any?> {
        val harnessAdapter = AcpHarnessAdapters.forProfile(profile)
        val providerProfile = currentAgentProviderProfile()
        val configuredProvider = if (ChatGptOAuthProvider.isOAuth(providerProfile?.sourceType)) {
            providerAccess?.takeIf { it.profile.id == providerProfile?.id }?.credentials
        } else currentAgentProviderCredentials()
        val writeProvider = configuredProvider ?: run {
            if (ChatGptOAuthProvider.isOAuth(providerProfile?.sourceType)) return@run null
            val baseUrl = args.stringValue("baseUrl")
                ?: ""
            val apiKey = args.stringValue("apiKey")
                ?: ""
            if (baseUrl.isBlank() || apiKey.isBlank()) null else AgentProviderCredentials(
                baseUrl = baseUrl,
                apiKey = apiKey,
                wireApi = args.stringValue("wireApi") ?: OpenAiWireApi.RESPONSES,
                customHeaders = providerProfile?.customHeaders.orEmpty(),
                protocolType = providerProfile?.protocolType ?: "openai_compatible",
            )
        }
        val providerModels = providerProfile?.let {
            ModelProviderConfigStore.cachedModels(appContext, it)
        }.orEmpty()
        val writes = AgentConfigAdapterRegistry.directConfigWrites(
            input = AgentProviderMappingInput(
                agentId = profile.id,
                provider = writeProvider,
                model = args.stringValue("model") ?: currentAgentBoundModel(),
                harnessAdapter = harnessAdapter,
                providerProfile = providerProfile,
            ),
            args = args,
            providerModels = providerModels,
        )
        if (writes.isEmpty()) {
            throw UnsupportedOperationException(
                "This ACP Harness does not expose a writable configuration surface."
            )
        }
        writes.forEach { write ->
            writeTerminalTextFile(
                path = write.path,
                content = write.content,
                executorKey = write.executorKey,
            )
        }
        recordAgentConfigRevision(
            agentId = profile.id,
            operation = "write",
            paths = writes.map(AgentConfigWrite::path),
        )
        return readAgentConfig(mapOf("agentId" to profile.id))
    }

    private suspend fun rollbackAgentConfig(args: Map<String, Any?>): Map<String, Any?> {
        val agentId = args.stringValue("agentId")
            ?: throw IllegalArgumentException("agentId is required.")
        val profile = acpAgentProfileStore.list().firstOrNull { it.id == agentId }
            ?: throw IllegalArgumentException("Unknown ACP agent: $agentId")
        return localRuntimeFor(agentId).withLaunchConfiguration { providerAccess ->
            validateExpectedConfigRevision(agentId, args)
            val targetRevision = args.longValue("targetRevision")
                ?: throw IllegalArgumentException("targetRevision is required.")
            val snapshot = acpAgentProfileStore.configSnapshot(agentId, targetRevision)
            require(snapshot.isNotEmpty()) { "Agent config revision has no files." }
            snapshot.forEach { (path, content) ->
                writeTerminalTextFile(
                    path = path,
                    content = content,
                    executorKey = "agent-config-rollback-${profile.id}",
                )
            }
            // Historical files can contain a previous process's loopback URL. Configuration
            // rollback never restores authorization; the connected process keeps its own grant.
            if (providerAccess != null) writeLocalAcpConfiguration(profile, providerAccess)
            recordAgentConfigRevision(
                agentId = profile.id,
                operation = "rollback",
                paths = snapshot.keys.toList(),
            )
            readAgentConfig(mapOf("agentId" to profile.id))
        }
    }

    private fun validateExpectedConfigRevision(
        agentId: String,
        args: Map<String, Any?>,
    ) {
        val expected = args.longValue("expectedRevision") ?: return
        val actual = acpAgentProfileStore.configRevision(agentId)
        require(expected == actual) {
            "Agent config changed concurrently (expected revision $expected, current $actual)."
        }
    }

    private suspend fun recordAgentConfigRevision(
        agentId: String,
        operation: String,
        paths: List<String>,
    ) {
        val files = paths.distinct().associateWith { path ->
            readAgentConfigFileForRevision(path, agentId)
        }
        acpAgentProfileStore.recordConfigRevision(
            agentId = agentId,
            operation = operation,
            files = files,
        )
    }

    private suspend fun readAgentConfigFileForRevision(path: String, agentId: String): String =
        readTerminalTextFile(
            path = path,
            executorKey = "agent-config-revision-read-$agentId",
        )

    private fun withAgentConfigMetadata(
        agentId: String,
        payload: Map<String, Any?>,
    ): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        putAll(payload)
        put("revision", acpAgentProfileStore.configRevision(agentId))
        put(
            "audit",
            acpAgentProfileStore.configAudit(agentId).map { entry ->
                linkedMapOf<String, Any?>(
                    "revision" to entry.revision,
                    "operation" to entry.operation,
                    "paths" to entry.paths,
                    "createdAt" to entry.createdAt,
                )
            },
        )
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
        executorKey: String,
        distribution: TerminalDistribution.Spec = TerminalDistribution.selected(),
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
        executeAgentConfigCommand(command, executorKey, distribution)
    }

    private suspend fun executeAgentConfigCommand(
        command: String,
        executorKey: String,
        distribution: TerminalDistribution.Spec = TerminalDistribution.selected(),
    ): String {
        val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
            command = command,
            executorKey = executorKey,
            distribution = distribution,
            timeoutMs = null
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
            remotePromptExecutions.values.toList().forEach { execution ->
                execution.cancelForTransport(
                    CancellationException("Remote ACP configuration changed")
                )
            }
            remotePromptExecutions.clear()
            sessionConversationIds.clear()
            session?.disconnect()
            session = null
            clearActiveTurns()
            pendingAcpServerRequests.removeForAgent(REMOTE_CODEX_AGENT_ID)
        }
        return buildRemoteBridgeConfigPayload(
            remoteConfig = savedRemoteConfig,
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
        LocalNetworkPermission.requestForEndpoint(context, remoteConfig.bridgeUrl)
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

    private suspend fun ensureThreadForTurn(args: Map<String, Any?>, cwd: String): String {
        val explicitThreadId = args.stringValue("threadId")
            ?: args.stringValue("sessionId")
        if (!explicitThreadId.isNullOrBlank()) {
            return explicitThreadId
        }
        val response = startThread(args + mapOf("cwd" to cwd))
        return response["threadId"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("thread/start did not return a threadId")
    }

    /** Only the remote connection writes this host reservation index. */
    private fun conversationIdForSession(sessionId: String): Long? =
        sessionConversationIds[sessionId.trim()]

    private suspend fun request(method: String, params: Any?): Any {
        val response = ensureConnectedSession().sendRequest(method, params)
        val error = response["error"]
        if (error != null) {
            throw IllegalStateException(error.toString())
        }
        return response["result"] ?: response
    }

    private suspend fun connectLocalAcp(
        profile: AcpAgentProfile,
        runtime: LocalAcpRuntime = localRuntimeFor(profile.id),
    ) {
        require(profile.enabled) {
            "No enabled ACP Agent is selected. Enable one in Agent mode settings."
        }
        runtime.connect(profile = profile)
    }

    private suspend fun prepareLocalAcpLaunch(
        profile: AcpAgentProfile,
        preparation: AcpLaunchPreparation,
    ): PreparedAcpLaunch {
        val provider = currentAgentProviderProfile()
        if (provider != null && AcpAgentProfileStore.usesSharedProvider(profile)) {
            LocalNetworkPermission.requireForEndpoint(context, provider.baseUrl)
        }
        if (!AcpAgentProfileStore.usesSharedProvider(profile) ||
            !ChatGptOAuthProvider.isOAuth(provider?.sourceType)) {
            return PreparedAcpLaunch(writeLocalAcpConfiguration(profile))
        }
        require(AgentConfigAdapterRegistry.supportsHostManagedResponses(AcpHarnessAdapters.forProfile(profile))) {
            "${profile.name} does not support the ChatGPT Responses Provider. " +
                "Choose a compatible Agent or an API-key Provider supported by this Agent."
        }
        val selectedProvider = checkNotNull(provider)
        // An installation probe can prepare another profile while its process is already online.
        // Borrow that process's grant; a probe must never rotate or own its authorization.
        if (preparation == AcpLaunchPreparation.PROBE) {
            return localRuntimeFor(profile.id).withLaunchConfiguration { existing ->
                if (existing != null) {
                    check(existing.profile.id == selectedProvider.id) { "Provider changed; reconnect this Agent." }
                    PreparedAcpLaunch(writeLocalAcpConfiguration(profile, existing))
                } else {
                    prepareChatGptAcpLaunch(profile, selectedProvider)
                }
            }
        }
        return prepareChatGptAcpLaunch(profile, selectedProvider)
    }

    private suspend fun prepareChatGptAcpLaunch(
        profile: AcpAgentProfile,
        selectedProvider: ModelProviderProfile,
    ): PreparedAcpLaunch {
        val access = ChatGptAgentProviderAccess.open(selectedProvider)
        try {
            return PreparedAcpLaunch(writeLocalAcpConfiguration(profile, access), access)
        } catch (error: Throwable) {
            access.close()
            throw error
        }
    }

    private suspend fun writeLocalAcpConfiguration(
        profile: AcpAgentProfile,
        providerAccess: AgentProviderAccess? = null,
        providerModelsOverride: List<ProviderModelOption>? = null,
    ): Map<String, String> {
        val officialRuntime = AcpAgentProfileStore.officialRuntime(profile)
        if (officialRuntime?.validateBeforeConfigWrite == true) {
            // A major-version config migration must not write V2-only fields
            // into an installation that still runs the old executable.
            val healthCommand = requireNotNull(officialRuntime.managedAdapterHealthCommand)
            if (!isTerminalShellCommandSuccessful(healthCommand, MANAGED_ACP_PROBE_TIMEOUT_MS)) {
                throw AgentRuntimeUpgradeRequiredException(profile.name)
            }
        }
        val usesSharedProvider = AcpAgentProfileStore
            .officialRuntime(profile)
            ?.usesSharedProvider == true
        // This path only builds launch environment for an already installed
        // Harness.  Dependency installation belongs exclusively to the
        // explicit `agent/prepare` request above.
        val sharedProviderProfile = providerAccess?.profile ?: currentAgentProviderProfile()
        val sharedProvider = providerAccess?.credentials ?: currentAgentProviderCredentials()
        val boundModel = currentAgentBoundModel()
        if (usesSharedProvider) {
            checkNotNull(sharedProviderProfile) {
                "Dispatch Model Provider is not configured. " +
                    "Configure the default Provider before starting Harness."
            }
            checkNotNull(sharedProvider) {
                "Dispatch Model Provider has no usable credentials. " +
                    "Check the default Provider configuration before starting Harness."
            }
        }
        // Provider /models is catalog metadata, not an ACP launch
        // prerequisite. A shared-provider Harness can launch from the
        // durable scene binding; when that binding is absent, fail clearly
        // and let the Provider settings/scene selector create it through an
        // explicit user action. Never turn Agent startup into a network
        // discovery request.
        val providerModels = providerModelsOverride ?: if (usesSharedProvider && sharedProviderProfile != null) {
            ModelProviderConfigStore.cachedModels(appContext, sharedProviderProfile)
        } else emptyList()
        val resolvedModel = if (usesSharedProvider) {
            val model = resolveAcpLaunchModelForDispatch(
                providerModelIds = providerModels.map(ProviderModelOption::id),
                dispatchModel = boundModel,
            )
            if (model == null) {
                throw IllegalStateException(
                    "Dispatch Model has no usable model. " +
                        "Refresh the current Provider model list and choose a model."
                )
            }
            model
        } else {
            resolveAcpLaunchModel(
                providerModelIds = providerModels.map(ProviderModelOption::id),
                boundModel = boundModel
            )
        }
        val adapterModels = if (usesSharedProvider && providerModels.isEmpty()) {
            // A persisted binding is an explicit user choice. The adapter
            // still needs a one-item config document, but that document is
            // derived locally and does not imply a Provider catalog request.
            val fallbackModel = requireNotNull(resolvedModel)
            Log.i(
                "AgentRuntimeManager",
                "Using persisted Agent model=$fallbackModel for ACP adapter config; " +
                    "Provider catalog discovery remains an explicit settings action",
            )
            listOf(ProviderModelOption(id = fallbackModel, displayName = fallbackModel))
        } else {
            providerModels
        }
        val providerModelsForAdapter = if (usesSharedProvider && sharedProviderProfile != null) {
            val selectedCatalog = (adapterModels + ProviderModelOption(id = requireNotNull(resolvedModel)))
                .distinctBy { it.id }
            ModelProviderConfigStore.resolveModelCapabilities(appContext, sharedProviderProfile, selectedCatalog)
        } else adapterModels
        val harnessAdapter = AcpHarnessAdapters.forProfile(profile)
        val mapping = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = profile.id,
                provider = sharedProvider,
                model = resolvedModel,
                harnessAdapter = harnessAdapter,
                providerProfile = sharedProviderProfile,
            )
        )
        // Re-read editable files at process launch; the running process keeps its snapshot.
        val existingAdapterConfig = mapping.launchConfigPath?.let { path ->
            readTerminalTextFile(
                path = path,
                executorKey = mapping.launchConfigExecutorKey
                    ?: "harness-launch-config-read"
            )
        }.orEmpty()
        // Official ACP persistence uses hard-link publication. Android's app
        // sandbox rejects hard links, so install one narrow Node compatibility
        // preload while keeping upstream ACP packages untouched.
        if (!acpFilesystemCompatReady) {
            writeTerminalTextFile(
                path = ACP_FILESYSTEM_COMPAT_PATH,
                content = ACP_FILESYSTEM_COMPAT_SCRIPT,
                executorKey = "acp-filesystem-compat-write"
            )
            acpFilesystemCompatReady = true
        }
        val launchConfigWrites = AgentConfigAdapterRegistry.launchConfigWrites(
            input = AgentProviderMappingInput(
                agentId = profile.id,
                provider = sharedProvider,
                model = resolvedModel,
                harnessAdapter = harnessAdapter,
                providerProfile = sharedProviderProfile,
            ),
            mapping = mapping,
            providerModels = providerModelsForAdapter,
            existingConfig = existingAdapterConfig,
        )
        launchConfigWrites.forEach { write ->
            // Adapters are asked to produce the canonical config on every
            // launch, but most Harness switches reuse the same Provider/model
            // values. Avoid another terminal IPC and filesystem write when
            // the target already contains that exact config.
            val existingContent = when (write.path) {
                mapping.launchConfigPath -> existingAdapterConfig
                else -> null
            }
            if (existingContent != null && existingContent == write.content) {
                return@forEach
            }
            writeTerminalTextFile(
                path = write.path,
                content = write.content,
                executorKey = write.executorKey,
            )
        }
        return mapping.environment
    }

    /**
     * Read the canonical Provider/model document for Agent dispatch.
     * Provider/model selection is a user-owned configuration action; Agent
     * startup must not silently migrate or select a remote model.
     */
    private suspend fun ensureSharedAgentProviderBinding(): SceneModelBindingEntry? {
        val current = SceneModelBindingStore.getBinding("scene.dispatch.model")
            ?.takeIf { it.providerProfileId.isNotBlank() && it.modelId.isNotBlank() }
            ?.takeIf { binding ->
                ModelProviderConfigStore.getProfile(binding.providerProfileId)
                    ?.let { it.baseUrl.isNotBlank() &&
                        (it.apiKey.isNotBlank() || (ChatGptOAuthProvider.isOAuth(it.sourceType) && it.ready)) } == true
            }
        if (current != null) return current

        // There is no model authority in this native layer. The model catalog
        // is persisted by the Provider configuration surface, and the scene
        // binding is written only after the user chooses a model. Do not
        // silently select the first remote model during ACP initialization.
        return null
    }

    private suspend fun prepareSharedProviderBinding() {
        ensureSharedAgentProviderBinding()
    }

    private fun currentAgentProviderProfile(): ModelProviderProfile? =
        AgentDispatchConfiguration.providerProfile()

    private fun currentAgentProviderCredentials(): AgentProviderCredentials? =
        AgentDispatchConfiguration.providerCredentials()

    private fun currentAgentBoundModel(): String? = AgentDispatchConfiguration.modelId()

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
                fetchAgentProviderModels(profile)
            } else {
                withTimeoutOrNull(timeoutMs) {
                    fetchAgentProviderModels(profile)
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

    private suspend fun listAuthoritativeProviderModels(): Map<String, Any?> {
        // Passive reads project the same latest Provider discovery used by
        // launch config. Explicit model-card refresh owns network discovery.
        val models = currentAgentProviderProfile()?.let {
            ModelProviderConfigStore.cachedModels(appContext, it)
        }.orEmpty()
        return buildAuthoritativeProviderModelPayload(
            providerModelIds = models.map { it.id },
            boundModel = currentAgentBoundModel(),
        )
    }

    private suspend fun ensureManagedAcpAdapter(
        profile: AcpAgentProfile,
        force: Boolean = false,
        distribution: TerminalDistribution.Spec = TerminalDistribution.selected(),
    ) {
        val runtime = AcpAgentProfileStore.officialRuntime(profile)
            ?: return
        if (runtime.managedAdapterPackage == null) {
            return
        }

        // A previous successful ACP connection is the fast path.  In
        // particular, do not make an already-healthy Harness wait for an
        // unrelated Harness to finish installing.
        val previousHealth = acpAgentProfileStore.health(profile.id)
        if (!force && shouldReuseManagedAcpPreparation(
                healthStatus = previousHealth.status,
                installed = previousHealth.installed,
                preparationRevision = previousHealth.preparationRevision,
                requiredRevision = runtime.preparationRevision,
            )) {
            return
        }

        // Only the explicit agent/prepare action enters this method. If another
        // installation owns the gate, report that state instead of continuing
        // into initialization as though this installation had completed.
        managedAcpPreparationGate.run(profile.id) {
            ensureManagedAcpAdapterLocked(profile, force, distribution)
        }
    }

    private suspend fun ensureManagedAcpAdapterLocked(profile: AcpAgentProfile, force: Boolean, distribution: TerminalDistribution.Spec) {
        val runtime = AcpAgentProfileStore.officialRuntime(profile) ?: return
        val packageName = runtime.managedAdapterPackage ?: return
        val previousHealth = acpAgentProfileStore.health(profile.id)
        // A successful ACP initialize is the authoritative preparation
        // result. Reusing it avoids running three terminal probes (including
        // the package/health checks) on every foreground Harness switch. An
        // explicit Agent check resets this health to `unchecked`; a missing
        // command will still be caught by LocalAcpRuntime.requireLaunchCommand
        // and invalidate the health on the next connect.
        if (!force && shouldReuseManagedAcpPreparation(
                healthStatus = previousHealth.status,
                installed = previousHealth.installed,
                preparationRevision = previousHealth.preparationRevision,
                requiredRevision = runtime.preparationRevision,
            )) {
            return
        }
        val managedPackages = runtime.managedAdapterPackages
            .ifEmpty { listOf(packageName) }
        val installTargets = managedPackages.joinToString(" ") { shellQuote(it) }
        val adapterHealthCheck = runtime.managedAdapterHealthCommand ?: ":"
        val adapterInstallCommand = runtime.managedInstallCommand
            ?: "npm install -g --prefix /root/.npm-global --no-audit --no-fund $installTargets"
        val installScript = """
            set -eu
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
                executorKey = "harness-installer-script-write-${profile.id}",
                distribution = distribution,
            )
        }
        val commandAvailable = isTerminalCommandAvailable(
            command = profile.command,
            timeoutMs = MANAGED_ACP_PROBE_TIMEOUT_MS,
            distribution = distribution,
        )
        // Some official Harnesses keep
        // the adapter inside a vendor-owned profile directory rather than
        // installing it globally. Their health command is the authoritative
        // package-graph check; probing `/root/.npm-global` would incorrectly
        // force a reinstall on every switch and bypass the vendor workflow.
        val allPackagesReady = if (runtime.managedInstallScriptPath != null) {
            commandAvailable
        } else {
            managedPackages.size == 1 ||
                (commandAvailable && areManagedNpmPackagesInstalled(
                    packageSpecs = managedPackages,
                    timeoutMs = MANAGED_ACP_PROBE_TIMEOUT_MS,
                    distribution = distribution,
                ))
        }
        val adapterHealthy = runtime.managedAdapterHealthCommand
            ?.let { isTerminalShellCommandSuccessful(it, MANAGED_ACP_PROBE_TIMEOUT_MS, distribution) }
            ?: true
        // Installation/update is an explicit preparation boundary. A normal
        // Agent switch must reuse a healthy installed adapter; otherwise every
        // switch would rerun the complete DSH npm/native installation.
        if (!force && !shouldPrepareManagedAcpAdapter(
                agentId = profile.id,
                commandAvailable = commandAvailable,
                allPackagesReady = allPackagesReady,
                adapterHealthy = adapterHealthy,
                preparationRevision = previousHealth.preparationRevision,
                requiredRevision = runtime.preparationRevision,
            )
        ) {
            return
        }
        // This is an explicit user retry. A previous failure must not prevent
        // the installer from trying again after network/environment repairs.
        if (!isTerminalCommandAvailable("npm", MANAGED_ACP_PROBE_TIMEOUT_MS, distribution)) {
            check(TerminalDistribution.selected().id == distribution.id) { "Terminal environment changed. Refresh Agent settings." }
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
        if (!isTerminalCommandAvailable("npm", MANAGED_ACP_PROBE_TIMEOUT_MS, distribution)) {
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
            distribution = distribution,
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
        // Only a successful explicit installer proves the installed revision.
        if (TerminalDistribution.selected().id == distribution.id) {
            acpAgentProfileStore.saveHealth(
                profile.id,
                acpAgentProfileStore.health(profile.id).copy(
                    installed = true,
                    preparationRevision = runtime.preparationRevision,
                ),
            )
        }
    }

    private suspend fun uninstallManagedAgent(args: Map<String, Any?>, distributionId: String): Any? {
        val agentId = args.stringValue("agentId").orEmpty().trim()
        val payload = try {
            val outcome = managedAgentUninstaller.uninstall(
                agentId = agentId,
                distributionId = args.stringValue("distributionId").orEmpty(),
            )
            linkedMapOf<String, Any?>(
                "ok" to outcome.ok,
                "agent" to outcome.profile.toPayload(
                    health = if (TerminalDistribution.selected().id == distributionId) {
                        acpAgentProfileStore.health(outcome.profile.id)
                    } else managedAgentHealthAfterUninstall(outcome.report),
                ),
                "error" to outcome.error,
                "failureKind" to outcome.failureKind,
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            linkedMapOf<String, Any?>(
                "ok" to false,
                "error" to (error.message ?: "Managed uninstall failed."),
                "failureKind" to when (error) {
                    is ManagedAgentUninstallException -> error.failureKind
                    is ManagedAcpPreparationInProgressException -> "agent_package_operation_in_progress"
                    else -> "agent_uninstall_failed"
                },
            ).apply {
                acpAgentProfileStore.list().firstOrNull { it.id == agentId }?.let { profile ->
                    put("agent", profile.toPayload(
                        health = acpAgentProfileStore.health(profile.id),
                    ))
                }
            }
        }
        payload["distributionId"] = distributionId
        return withAgentPackageVersions(payload, distributionId)
    }

    private suspend fun executeManagedAgentUninstall(
        profile: AcpAgentProfile,
        distributionId: String,
        probe: Boolean,
    ): ManagedAgentUninstallReport {
        // Do not let a hidden-command bootstrap install another environment
        // after the settings selection changes, even for the recovery probe.
        if (TerminalDistribution.selected().id != distributionId) {
            throw ManagedAgentUninstallException(
                "agent_uninstall_distribution_changed", "Terminal environment changed. Refresh Agent settings.",
            )
        }
        if (!EmbeddedRuntimeInstaller.isDistributionReady(appContext, TerminalDistribution.selected())) {
            throw ManagedAgentUninstallException(
                "agent_uninstall_terminal_unavailable", "The terminal environment is unavailable.",
            )
        }
        val strategy = requireNotNull(profile.officialRuntime?.managedUninstallStrategy)
        val source = appContext.assets.open("acp/uninstall-agent.cjs").bufferedReader().use { it.readText() }
        val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
            command = managedAgentUninstallCommand(source, strategy, probe),
            executorKey = "acp-adapter-uninstall-${profile.id}-${if (probe) "probe" else "remove"}",
            distribution = TerminalDistribution.fromId(distributionId),
            timeoutMs = if (probe) 15_000L else MANAGED_ACP_INSTALL_TIMEOUT_MS,
        )
        // The script reports disk state even on a partial failure (exit 1).
        // A zero exit without this report cannot establish that removal worked.
        return parseManagedAgentUninstallReport(result.output)
    }

    private suspend fun refreshManagedAgentAfterUninstall(
        profile: AcpAgentProfile,
        distributionId: String,
        report: ManagedAgentUninstallReport?,
    ) {
        invalidateLocalProbeCache()
        acpAgentProfileStore.savePackageVersion(
            distributionId, profile.id,
            managedAgentVersionAfterUninstall(acpAgentProfileStore.packageVersion(distributionId, profile.id), report),
        )
        if (TerminalDistribution.selected().id != distributionId) return
        acpAgentProfileStore.saveHealth(profile.id, managedAgentHealthAfterUninstall(report))
        if (report != null && !report.removed) {
            managedAgentUpdates.check(listOf(profile), distributionId, remote = false)
        }
    }

    private suspend fun areManagedNpmPackagesInstalled(
        packageSpecs: List<String>,
        timeoutMs: Long = 20_000L,
        distribution: TerminalDistribution.Spec = TerminalDistribution.selected(),
    ): Boolean {
        if (packageSpecs.isEmpty()) return true
        val checks = packageSpecs.joinToString(" && ") { spec ->
            val packageName = npmPackageName(spec)
            "test -f ${shellQuote("/root/.npm-global/lib/node_modules/$packageName/package.json")}"
        }
        val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
            command = checks,
            executorKey = "acp-managed-packages-probe-${packageSpecs.hashCode()}",
            distribution = distribution,
            timeoutMs = timeoutMs
        )
        return result.isOk && result.exitCode == 0
    }

    private suspend fun isTerminalCommandAvailable(
        command: String,
        timeoutMs: Long = 20_000L,
        distribution: TerminalDistribution.Spec = TerminalDistribution.selected(),
    ): Boolean {
        val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
            command = "$MANAGED_NPM_PATH_PREFIX " +
                "command -v ${shellQuote(command)} >/dev/null 2>&1",
            executorKey = "acp-command-probe-${command.hashCode()}",
            distribution = distribution,
            timeoutMs = timeoutMs
        )
        return result.isOk && result.exitCode == 0
    }

    private suspend fun isTerminalShellCommandSuccessful(
        command: String,
        timeoutMs: Long = 20_000L,
        distribution: TerminalDistribution.Spec = TerminalDistribution.selected(),
    ): Boolean {
        val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
            command = "$MANAGED_NPM_PATH_PREFIX $command",
            executorKey = "acp-shell-health-${command.hashCode()}",
            distribution = distribution,
            timeoutMs = timeoutMs
        )
        return result.isOk && result.exitCode == 0
    }

    private suspend fun ensureLocalAcpConnected(
        method: String,
        args: Map<String, Any?>,
        identity: AcpRequestIdentity,
    ) {
        val targetProfile = acpAgentProfileStore.requireProfile(identity.agentId)
        require(targetProfile.enabled) { "ACP agent ${targetProfile.name} is disabled." }
        require(identity.explicitSessionMatchesConversation) {
            "agent_owner_conflict: The requested ACP session belongs to another conversation."
        }
        val targetRuntime = localRuntimeFor(targetProfile.id)
        if (!targetRuntime.isConnected || (targetRuntime.connectionDistributionId != null &&
                targetRuntime.connectionDistributionId != TerminalDistribution.selected().id)) {
            connectLocalAcp(profile = targetProfile, runtime = targetRuntime)
        }
        if (method == "session/load" && args["refreshConfig"] == true &&
            AcpAgentProfileStore.usesSharedProvider(targetProfile) &&
            AcpHarnessAdapters.forProfile(targetProfile).configAdapterId != null) {
            // Explicit model-card refresh owns discovery. Startup and prompts
            // still use the saved binding and never require a /models request.
            check(!targetRuntime.hasActiveTurns()) { "本轮结束后可刷新模型列表" }
            val provider = checkNotNull(currentAgentProviderProfile()) { "请先配置模型提供商" }
            val models = fetchAgentProviderModels(provider, forceRefresh = true)
            check(currentAgentProviderProfile() == provider) { "模型配置已更改，请重新打开模型列表" }
            check(!targetRuntime.hasActiveTurns()) { "本轮结束后可刷新模型列表" }
            ModelProviderConfigStore.rememberModels(appContext, provider, models)
            targetRuntime.withLaunchConfiguration { providerAccess ->
                check(targetRuntime.isConnected && !targetRuntime.hasActiveTurns()) {
                    "Agent 状态已更改，请重新打开模型列表"
                }
                check(currentAgentProviderProfile() == provider) { "模型配置已更改，请重新打开模型列表" }
                writeLocalAcpConfiguration(targetProfile, providerAccess, providerModelsOverride = models)
            }
        }
        identity.conversationId?.let { acpAgentProfileStore.bindConversation(it, targetProfile.id) }
    }

    private suspend fun handleServerMessage(message: Map<String, Any?>) {
        // The connection callback fixes event ownership. Late events remain
        // scoped to their original Agent even while other conversations run.
        val sourceAgentId = message["_sourceAgentId"]?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (sourceAgentId == null && message["_remoteConnectionToken"] != session?.connectionToken) {
            Log.i("AgentRuntimeManager", "Ignoring an event from a superseded remote transport")
            return
        }
        val publicMessage = if (sourceAgentId == null) {
            LinkedHashMap(message).apply { remove("_remoteConnectionToken") }
        } else {
            LinkedHashMap(message).apply { remove("_sourceAgentId") }
        }
        val method = extractRemoteCodexServerMethod(publicMessage)
        if (method == "codex/disconnected" && sourceAgentId == null) {
            val eventConnectionToken = message["_remoteConnectionToken"]?.toString()
            if (eventConnectionToken != null &&
                eventConnectionToken != session?.connectionToken
            ) {
                // A reconnect can publish a stale exit callback from the
                // previous app-server instance. It must not terminate turns
                // admitted on the new transport.
                Log.i(
                    "AgentRuntimeManager",
                    "Ignoring stale remote disconnect callback"
                )
                return
            }
            if (finishRemoteDisconnect()) return
        }
        val rawExtensionParams = publicMessage["params"]
        val explicitParams = extractRemoteCodexServerParams(publicMessage)
        val params = if (method.startsWith("_") &&
            rawExtensionParams != null &&
            rawExtensionParams !is Map<*, *>
        ) {
            // JSON-RPC params may be an array or scalar for an extension. The
            // shared manager historically accepts map-shaped params, so keep
            // a lossless compatibility wrapper while the original payload
            // remains available in `message` for Flutter diagnostics.
            mapOf("_rawParams" to rawExtensionParams)
        } else if (explicitParams.isNotEmpty()) {
            explicitParams
        } else {
            syntheticRemoteCodexServerParams(publicMessage, method)
        }
        val disconnectedIdentity = if (method == "codex/disconnected" && sourceAgentId == null) {
            remoteTurnOwnership.activeRecords().firstOrNull()
                ?.let { it.sessionId to it.turnId }
        } else {
            null
        }
        val threadId = extractThreadId(publicMessage) ?: disconnectedIdentity?.first
        val localEventRuntime = sourceAgentId?.let(localAcpRuntimes::get)
        if (sourceAgentId != null && localEventRuntime == null) {
            Log.w("AgentRuntimeManager", "Dropping event from an unknown ACP transport owner")
            return
        }
        if (sourceAgentId == null && publicMessage["id"] != null && method.isNotBlank()) {
            pendingAcpServerRequests.register(publicMessage["id"], REMOTE_CODEX_AGENT_ID, threadId)
        }
        // ACP session/update is session-scoped on the wire. When an external
        // local Harness omits a turn id, the active prompt reservation for
        // this exact session is the only valid attribution boundary. This is
        // a protocol rule, not a Harness-specific capability: never infer an
        // identity from text, timing, or the selected Agent.
        val remoteActiveTurnId = if (sourceAgentId == null) {
            threadId?.let(remoteTurnOwnership::activeTurnId)
        } else {
            null
        }
        val implicitTurnId = if (sourceAgentId != null) {
            localEventRuntime?.activeTurnIdForSession(threadId)
        } else {
            null
        }
        // Standard ACP session/update notifications are session-scoped and
        // do not require a turn id. While a remote prompt is active, project
        // them onto the host turn reserved above; once it is cleared, late
        // notifications remain quarantined instead of being attached to the
        // next prompt.
        val explicitTurnId = extractTurnId(publicMessage)
        val activeEventTurnId = extractActiveTurnId(publicMessage)
        val turnId = resolveObservedTurnId(
            explicitTurnId = explicitTurnId,
            activeEventTurnId = activeEventTurnId,
            hostActiveTurnId = remoteActiveTurnId,
            disconnectedTurnId = disconnectedIdentity?.second,
            implicitTurnId = implicitTurnId,
            preferHostActiveTurn = sourceAgentId == null &&
                threadId?.let { remotePromptExecutions[it] != null } == true,
        )
        // A first non-terminal event may establish Flutter's active turn only
        // when the host itself supplied the attribution. Provider payloads
        // with an arbitrary turn id are not enough: they may be delayed data
        // from an older prompt.
        val hostAssignedTurn = publicMessage["hostTurnId"] == true ||
            (sourceAgentId == null &&
                remoteActiveTurnId != null &&
                turnId == remoteActiveTurnId) ||
            (sourceAgentId == null &&
                explicitTurnId == null &&
                activeEventTurnId == null &&
                remoteActiveTurnId != null) ||
            (sourceAgentId != null &&
                explicitTurnId == null &&
                activeEventTurnId == null &&
                implicitTurnId != null)
        // Diagnostic: log every server-side method that reaches Kotlin so the
        // user can verify via `adb logcat -s AgentRuntimeManager:V` whether
        // commandExecution / rawResponseItem events actually arrive over the
        // bridge. If item/started events for commandExecution are missing
        // here but present in `codex app-server` stdout, the bridge is
        // dropping them; if present here but missing on Flutter side, the
        // EventChannel pipe is the problem.
        val diagItemType = (publicMessage["params"] as? Map<*, *>)
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
            admitRemoteTurn(threadId, turnId)
        }

        val eventAgentId = sourceAgentId ?: REMOTE_CODEX_AGENT_ID
        val eventAgentName = if (sourceAgentId == null) "Codex Remote" else
            localEventRuntime?.activeAgentName().orEmpty()
        val projectedConversationId = runCatching {
            syncMessage(
                method = method,
                params = params,
                threadId = threadId,
                sourceAgentId = sourceAgentId,
            )
        }.onFailure { error ->
            Log.w("AgentRuntimeManager", "syncMessage failed for $method: ${error.message}")
        }.getOrNull()
        val eventConversationId = resolveAcpEventConversationId(
            remoteEvent = sourceAgentId == null,
            sessionConversationId = if (sourceAgentId == null) {
                threadId?.let { sessionId -> conversationIdForSession(sessionId) }
            } else {
                null
            },
            projectedConversationId = projectedConversationId,
        )

        // Commands are session metadata, including before the first prompt.
        // Only the host's current durable binding can admit their session;
        // a provider-supplied session id alone must never replace the owner.
        val boundCommandSession = if (sourceAgentId != null &&
            eventConversationId != null && method == "session/update" &&
            params.mapValue("update").stringValue("sessionUpdate") == "available_commands_update"
        ) bindingRepository.getBindingByConversationId(eventConversationId)?.threadId else null
        val hostAssignedCommandSession = admitsBoundAcpCommands(
            method = method,
            updateType = params.mapValue("update").stringValue("sessionUpdate"),
            sessionId = threadId,
            boundSessionId = boundCommandSession,
            turnId = turnId,
            sourceOwnsSession = sourceAgentId != null && threadId != null &&
                localEventRuntime?.ownsSession(threadId) == true,
        )

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
                "conversationId" to eventConversationId,
                "agentId" to eventAgentId,
                "agentName" to eventAgentName,
                "allowImplicitTurnAdmission" to (hostAssignedTurn || hostAssignedCommandSession),
                "replay" to message["replay"],
                "params" to params,
                "message" to publicMessage
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
                    conversationId = eventConversationId,
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
     * A bridge exit is transport-scoped: it has no reliable session id and
     * therefore must terminate every turn owned by that bridge. Keeping the
     * old first-record behavior made parallel sessions leak their spinner and
     * left stale session/conversation attribution across reconnects.
     */
    private suspend fun finishRemoteDisconnect(): Boolean {
        val activeRecords = remoteTurnOwnership.activeRecords()
        remotePromptExecutions.values.toList().forEach { execution ->
            execution.cancelForTransport(
                CancellationException("Remote ACP runtime disconnected")
            )
        }
        remotePromptExecutions.clear()
        pendingTurnThreads.clear()
        sessionConversationIds.clear()
        pendingAcpServerRequests.removeForAgent(REMOTE_CODEX_AGENT_ID)

        if (activeRecords.isEmpty()) return false

        val failureMessage = "Remote ACP bridge disconnected."
        remoteTurnOwnership.finishAll(
            status = "error",
            error = failureMessage,
        ).forEach { record ->
            releaseTurnRuntime(record.sessionId, record.turnId)
        }
        return true
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
                "plan",
                "plan_update",
                "plan_removed",
                "terminal_output_chunk",
                "terminal_update"
            )
        }
        if (
            method.startsWith("item/") ||
            method == "rawResponseItem/completed" ||
            method == "turn/plan/updated" ||
            method == "turn/plan/removed" ||
            method == "turn/diff/updated"
        ) {
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
        params: Map<String, Any?>,
        threadId: String?,
        sourceAgentId: String?,
    ): Long? {
        if (sourceAgentId == null || threadId.isNullOrBlank()) return null
        val binding = bindingRepository.getBindingByThreadId(threadId) ?: return null
        if (acpAgentProfileStore.agentIdForConversation(binding.conversationId) != sourceAgentId) return null
        when (method) {
            "thread/name/updated" -> bindingRepository.updateTitle(
                threadId, params.stringValue("threadName") ?: params.stringValue("name") ?: params.stringValue("title"),
            )
            "thread/archived" -> bindingRepository.setArchived(threadId, true)
            "thread/unarchived" -> bindingRepository.setArchived(threadId, false)
        }
        return binding.conversationId
    }

    private fun emitEvent(event: Map<String, Any?>) {
        val delivered = if (event["eventId"]?.toString()?.isNotBlank() == true) {
            event
        } else {
            val sequence = hostEventSequence.incrementAndGet()
            LinkedHashMap(event).apply {
                // This is host delivery metadata. The ACP payload under
                // `params` remains untouched, so an ACP client can consume it
                // without knowing about the Android event bridge.
                put("eventId", "host:$sequence")
                put("sequence", sequence)
            }
        }
        mainHandler.post {
            dispatchEvent(delivered)
        }
    }

    private fun dispatchEvent(event: Map<String, Any?>) {
        val listener: ((Map<String, Any?>) -> Unit)?
        val supplementalListeners: List<(Map<String, Any?>) -> Unit>
        synchronized(eventDispatchLock) {
            listener = eventListener
            supplementalListeners = supplementalEventListeners.values.toList()
            if (listener == null) {
                enqueuePendingEventLocked(event)
            }
        }
        if (listener != null) {
            val delivered = runCatching {
                listener.invoke(event)
            }.onFailure { error ->
                Log.w("AgentRuntimeManager", "primary event listener failed: ${error.message}")
            }.isSuccess
            if (!delivered) {
                // A listener exception must not consume a lifecycle terminal
                // event. Detach the broken listener so a later Flutter
                // binding can drain this event and the following events in
                // order instead of leaving the UI permanently processing.
                synchronized(eventDispatchLock) {
                    if (eventListener === listener) {
                        eventListener = null
                    }
                    enqueuePendingEventLocked(event)
                }
            }
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

    private fun drainPendingEvents() {
        while (true) {
            val event = synchronized(eventDispatchLock) {
                if (eventListener == null || pendingEvents.isEmpty()) {
                    null
                } else {
                    pendingEvents.removeFirst()
                }
            } ?: return
            val listener = eventListener ?: run {
                synchronized(eventDispatchLock) {
                    pendingEvents.addFirst(event)
                }
                return
            }
            val delivered = runCatching {
                listener.invoke(event)
            }.onFailure { error ->
                Log.w("AgentRuntimeManager", "buffered event delivery failed: ${error.message}")
            }.isSuccess
            if (!delivered) {
                synchronized(eventDispatchLock) {
                    if (eventListener === listener) {
                        eventListener = null
                    }
                    pendingEvents.addFirst(event)
                }
                return
            }
        }
    }

    private fun enqueuePendingEventLocked(event: Map<String, Any?>) {
        enqueuePendingAgentEvent(pendingEvents, event)
    }

    private suspend fun probeLocalAcpAgent(profile: AcpAgentProfile, distribution: TerminalDistribution.Spec): AgentRuntimeProbe {
        if (!profile.enabled) {
            return AgentRuntimeProbe(
                ready = false,
                version = null,
                error = "ACP agent ${profile.name} is disabled."
            )
        }
        if (profile.officialRuntime?.embedded == true) {
            return AgentRuntimeProbe(
                ready = true,
                version = BuildConfig.VERSION_NAME,
                error = null
            )
        }
        // A successful managed-adapter preparation or ACP initialize is
        // already an authoritative launch-readiness result. Re-running a
        // shell command probe after every app/process restart turns the
        // foreground Agent entry into a 15s timeout when the terminal is
        // merely waking up. The real connect path still validates the actual
        // ACP process and will invalidate this health on a genuine failure.
        val runtime = AcpAgentProfileStore.officialRuntime(profile)
        val persistedHealth = acpAgentProfileStore.health(profile.id)
        if (runtime?.managedAdapterPackage != null &&
            shouldReuseManagedAcpPreparation(
                healthStatus = persistedHealth.status,
                installed = persistedHealth.installed,
                preparationRevision = persistedHealth.preparationRevision,
                requiredRevision = runtime.preparationRevision,
            )
        ) {
            return AgentRuntimeProbe(
                ready = true,
                version = null,
                error = null,
                details = mapOf("source" to "persisted_health"),
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
                timeoutMs = 15_000L,
                distribution = distribution,
                installIfMissing = false,
            )
            val launchReady = result.isOk && result.exitCode == 0
            val healthCommand = AcpAgentProfileStore
                .officialRuntime(profile)
                ?.managedAdapterHealthCommand
            val healthResult = if (launchReady && healthCommand != null) {
                TerminalManager.getInstance(appContext).executeHiddenCommand(
                    command = "$MANAGED_NPM_PATH_PREFIX $healthCommand",
                    executorKey = "acp-agent-health-probe-${profile.id}",
                    timeoutMs = 8_000L,
                    distribution = distribution,
                    installIfMissing = false,
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

    /**
     * Cache explicit, Agent-scoped status probes briefly. Session requests
     * own connection readiness and do not depend on a directory-wide probe.
     */
    private suspend fun probeLocalAcpAgentCached(profile: AcpAgentProfile): AgentRuntimeProbe {
        val distribution = TerminalDistribution.selected()
        val fingerprint = localProbeFingerprint(profile, distribution)
        val now = System.currentTimeMillis()
        localProbeCache[profile.id]?.let { cached ->
            if (
                cached.profileFingerprint == fingerprint &&
                now - cached.checkedAtMs in 0 until LOCAL_PROBE_CACHE_TTL_MS
            ) {
                return cached.probe
            }
        }
        val probe = probeLocalAcpAgent(profile, distribution)
        localProbeCache[profile.id] = LocalProbeCache(
            profileFingerprint = fingerprint,
            checkedAtMs = System.currentTimeMillis(),
            probe = probe
        )
        return probe
    }

    private fun invalidateLocalProbeCache() {
        localProbeCache.clear()
    }

    private fun localProbeFingerprint(profile: AcpAgentProfile, distribution: TerminalDistribution.Spec): String =
        listOf(
            distribution.id,
            profile.id,
            profile.command,
            profile.enabled,
            AcpAgentProfileStore.officialRuntime(profile)?.managedAdapterPackage,
            profile.environment.hashCode()
        ).joinToString("|")

    private suspend fun probeRemoteCodex(config: CodexRemoteBridgeConfig): AgentRuntimeProbe {
        try {
            LocalNetworkPermission.requireForEndpoint(context, config.bridgeUrl)
        } catch (error: SecurityException) {
            return AgentRuntimeProbe(false, null, error.message)
        }
        val probe = probeCodexRemoteBridge(config)
        return AgentRuntimeProbe(
            ready = probe.ready,
            version = probe.version,
            error = probe.error,
            details = probe.details
        )
    }

    private fun resolveRemoteCwd(): String = remoteConfigStore.read().cwd.trim().also {
        require(it.isNotEmpty()) { "Remote ACP workspace is not configured." }
    }

    private suspend fun resolveThreadId(args: Map<String, Any?>): String {
        val explicit = args.stringValue("threadId")
            ?: args.stringValue("sessionId")
            ?: args.stringValue("thread_id")
        if (!explicit.isNullOrBlank()) {
            return explicit
        }
        throw IllegalArgumentException("sessionId is required for remote ACP sessions")
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
            preferLocalImagePaths = false
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
        val remoteConfig = remoteConfigStore.read()
        val locallyPrepared = AgentWorkspaceAttachmentSupport.prepareAttachmentsForRuntime(
            context = appContext,
            taskId = threadId
                ?: args.stringValue("conversationId")
                ?: "remote-agent-${System.currentTimeMillis()}",
            rawAttachments = rawAttachments
        )
        return locallyPrepared.map { attachment ->
            prepareRemoteCodexAttachment(remoteConfig, attachment)
        }
    }

    private suspend fun prepareRemoteCodexAttachment(
        remoteConfig: CodexRemoteBridgeConfig,
        attachment: Map<String, Any?>
    ): Map<String, Any?> {
        val existingRemotePath = attachment.stringValue("promptPath")
            ?: attachment.stringValue("workspacePath")
        val localPath = attachment.stringValue("path")
        val localFile = localPath
            ?.removePrefix("file://")
            ?.let(::File)
        if (!existingRemotePath.isNullOrBlank()) {
            if (localFile?.isFile != true) {
                return attachment
            }
        }
        val sourcePath = localPath.orEmpty()
        if (sourcePath.startsWith("http://", ignoreCase = true) ||
            sourcePath.startsWith("https://", ignoreCase = true)
        ) {
            // A provider-visible URL is already a remote attachment. Keep it
            // for buildAgentTurnInput instead of treating the URL as a local
            // File and failing before ACP prompt admission.
            return attachment
        }
        val source = localFile ?: File(sourcePath)
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

    private data class LocalProbeCache(
        val profileFingerprint: String,
        val checkedAtMs: Long,
        val probe: AgentRuntimeProbe
    )

    companion object {
        @Volatile
        private var INSTANCE: AgentRuntimeManager? = null

        private const val LOCAL_PROBE_CACHE_TTL_MS = 15_000L

        fun getInstance(context: Context): AgentRuntimeManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgentRuntimeManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        fun getIfInitialized(): AgentRuntimeManager? = INSTANCE
    }
}

/**
 * Resolve the Agent-advertised ACP config id instead of guessing a private
 * field name. Different ACP Agents may use a stable id other than
 * `reasoning_effort`, while the official category remains thought-level.
 */
internal fun resolveAdvertisedReasoningEffortConfigId(
    payload: Map<String, Any?>,
    requestedEffort: String,
): String? {
    val options = payload["configOptions"] as? List<*> ?: return null
    return options.asSequence()
        .mapNotNull { it as? Map<*, *> }
        .firstOrNull { option ->
            val id = option["id"]?.toString()?.trim().orEmpty()
            val category = option["category"]?.toString()?.trim()?.lowercase().orEmpty()
            val isReasoning = id == "reasoning_effort" ||
                category == "thought_level" ||
                category == "thought-level" ||
                id.contains("reasoning", ignoreCase = true)
            if (!isReasoning || id.isEmpty()) return@firstOrNull false
            val advertisedValues = (option["options"] as? List<*>)
                .orEmpty()
                .mapNotNull { value ->
                    when (value) {
                        is Map<*, *> -> value["value"]?.toString()
                        else -> value?.toString()
                    }
                }
            advertisedValues.contains(requestedEffort)
        }
        ?.get("id")
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

/** Queue ACP updates verbatim until Flutter binds its one shared reducer. */
internal fun enqueuePendingAgentEvent(
    pendingEvents: ArrayDeque<Map<String, Any?>>,
    event: Map<String, Any?>,
) {
    pendingEvents.addLast(event)
}

internal fun terminalStatusFromAcpParams(
    params: Map<String, Any?>,
    fallback: String = "completed",
): String {
    val raw = listOf("stopReason", "stop_reason", "status", "state")
        .asSequence()
        .mapNotNull { params[it]?.toString()?.trim()?.lowercase() }
        .firstOrNull { it.isNotEmpty() }
        ?: return fallback
    return when (raw.replace('-', '_')) {
        "cancelled", "canceled", "interrupted", "aborted" -> "cancelled"
        "failed", "failure", "error", "timeout", "timed_out" -> "error"
        else -> "completed"
    }
}

/**
 * Resolves the Provider that currently drives Dispatch Model execution.
 *
 * The scene binding is an optional override, not a prerequisite for running a
 * Harness. If it is missing or stale, use the Provider settings page's editing
 * profile as the Dispatch default.
 */
internal fun resolveDispatchAgentProviderProfile(
    boundProviderProfileId: String?,
    configuredProfile: ModelProviderProfile?,
    editingProfile: ModelProviderProfile?,
    officialProfile: ModelProviderProfile?,
): ModelProviderProfile? {
    return resolveAgentProviderProfile(
        boundProviderProfileId = boundProviderProfileId,
        configuredProfile = configuredProfile,
        officialProfile = officialProfile,
    )?.takeIf { it.isConfigured() }
        ?: editingProfile?.takeIf { it.isConfigured() }
}

// A foreground Agent switch must not leave the chat in an indefinite
// Processing state when the device is offline or the npm registry is stalled.
// The official installer remains unchanged; this only bounds one preparation
// attempt and lets the UI surface its error so the user can retry explicitly.
private const val MANAGED_ACP_INSTALL_TIMEOUT_MS = 8 * 60 * 1_000L
private const val MANAGED_ACP_PROBE_TIMEOUT_MS = 5_000L
private const val REMOTE_CANCEL_TIMEOUT_MS = 10_000L
private const val REMOTE_TURN_SCOPE = "remote:codex"
internal const val AGENT_PROVIDER_MODEL_LOOKUP_TIMEOUT_MS = 3_000L

/**
 * Raised instead of suspending a Harness switch behind another Harness's
 * long-running npm/native preparation.
 */
internal class ManagedAcpPreparationInProgressException(
    val preparingAgentId: String,
) : IllegalStateException(
    "Harness preparation is already running for $preparingAgentId. " +
        "Wait for that installation to finish before starting another " +
        "unprepared Harness."
)

/**
 * Non-blocking ownership gate for the shared npm/native installation area.
 * Keeping this seam small makes the foreground-switch guarantee testable
 * without starting a terminal or an ACP process.
 */
internal class ManagedAcpPreparationGate {
    companion object {
        /** Foreground installs and upgrade cleanup share one npm-prefix owner. */
        val shared = ManagedAcpPreparationGate()
    }

    private val mutex = Mutex()

    @Volatile
    private var ownerAgentId: String? = null

    val isBusy: Boolean
        get() = mutex.isLocked

    val currentOwnerAgentId: String?
        get() = ownerAgentId

    suspend fun <T> run(agentId: String, operation: suspend () -> T): T {
        if (!mutex.tryLock()) {
            throw ManagedAcpPreparationInProgressException(
                currentOwnerAgentId ?: "another Harness"
            )
        }
        ownerAgentId = agentId
        try {
            return operation()
        } finally {
            ownerAgentId = null
            mutex.unlock()
        }
    }
}

internal fun shouldPrepareManagedAcpAdapter(
    agentId: String,
    commandAvailable: Boolean,
    allPackagesReady: Boolean,
    adapterHealthy: Boolean,
    preparationRevision: String? = null,
    requiredRevision: String? = null,
): Boolean {
    // Keep the agent id in the decision signature because preparation is
    // agent-specific, but reuse the same readiness contract for all managed
    // adapters. Package updates are not part of every foreground switch.
    val revisionCurrent = requiredRevision == null ||
        preparationRevision == requiredRevision
    return !(commandAvailable && allPackagesReady && adapterHealthy && revisionCurrent)
}

internal fun shouldReuseManagedAcpPreparation(
    healthStatus: String,
    installed: Boolean?,
    preparationRevision: String? = null,
    requiredRevision: String? = null,
): Boolean {
    return healthStatus == AcpAgentHealth.STATUS_ONLINE &&
        installed == true &&
        (requiredRevision == null || preparationRevision == requiredRevision)
}

/**
 * A managed ACP adapter that has already passed preparation does not need a
 * second shell `command -v` probe on the normal connect path. Custom agents
 * and stale/unchecked health records still take the defensive probe path.
 */
internal fun shouldProbeManagedAcpLaunchCommand(
    managedAdapter: Boolean,
    healthStatus: String,
    installed: Boolean?,
    preparationRevision: String? = null,
    requiredRevision: String? = null,
): Boolean {
    return !managedAdapter || !shouldReuseManagedAcpPreparation(
        healthStatus = healthStatus,
        installed = installed,
        preparationRevision = preparationRevision,
        requiredRevision = requiredRevision,
    )
}

private const val MANAGED_NPM_PATH_PREFIX =
    "PATH=\"/root/.npm-global/bin:\$PATH\"; export PATH;"
internal const val OPENCODE_CONFIG_PATH = "/root/.config/opencode/opencode.json"


/** ACP extension methods live in the implementation-reserved underscore namespace. */
private fun isAcpExtensionMethod(method: String): Boolean =
    method.trim().startsWith("_")

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

/**
 * Old remote codex-acp bridges expose the pre-ACP thread aliases but return a
 * plain JSON-RPC error for newer session methods. Only that capability error
 * is eligible for fallback; authentication, transport, and agent failures
 * must still reach the caller unchanged.
 */
internal fun isUnsupportedRemoteAcpMethod(error: Throwable): Boolean {
    val message = (error.message ?: error.toString()).lowercase()
    return listOf(
        "method not found",
        "unknown method",
        "unsupported method",
        "not implemented",
        "does not support",
    ).any(message::contains)
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
): Map<String, Any?> {
    return linkedMapOf(
        "agentHome" to AgentRuntimeDefaults.CODEX_HOME,
        "remoteEnabled" to remoteConfig.enabled,
        "remoteBridgeUrl" to remoteConfig.bridgeUrl,
        "remoteBridgeToken" to remoteConfig.authToken,
        "remoteCwd" to remoteConfig.cwd,
        "remoteConfigured" to remoteConfig.isConfigured,
    )
}

/**
 * Official OpenCode V2 configuration for the shared Provider.
 * The API key remains an environment substitution; the host only publishes
 * the shared provider/model mapping into OpenCode's own config surface.
 */
internal fun buildOpenCodeConfigJson(
    model: String,
    baseUrl: String,
    existingConfigJson: String = "",
    customHeaders: Map<String, String> = emptyMap(),
    providerModels: List<ProviderModelOption> = emptyList(),
    protocolType: String = "openai_compatible",
    wireApi: String = OpenAiWireApi.CHAT_COMPLETIONS,
): String {
    val providerModel = model.removePrefix("$OPEN_CODE_PROVIDER_ID/")
    val root = if (existingConfigJson.isBlank()) com.google.gson.JsonObject()
        else JsonParser.parseString(existingConfigJson).asJsonObject
    root.addProperty("\$schema", "https://opencode.ai/config.json")
    root.addProperty("model", model)
    // This adapter maps the user's shared Provider, not OpenCode's built-in
    // catalog. Use OpenCode's own provider-selection configuration.
    if (!root.has("enabled_providers") && !root.has("policies")) {
        root.add("enabled_providers", com.google.gson.JsonArray().apply { add(OPEN_CODE_PROVIDER_ID) })
    }

    val provider = managedOpenCodeV2Provider(root)
    provider.addProperty("package", when {
        protocolType.equals("anthropic", ignoreCase = true) -> "@opencode/ai/providers/anthropic-compatible"
        OpenAiWireApi.isResponses(wireApi) -> "@opencode/ai/providers/openai-compatible/responses"
        else -> "@opencode/ai/providers/openai-compatible"
    })
    provider.addProperty("name", "OmniBot Provider")
    val settings = provider.objectOrCreate("settings")
    settings.addProperty("baseURL", baseUrl)
    settings.addProperty("apiKey", "{env:OPENAI_API_KEY}")
    val headers = provider.objectOrCreate("headers")
    // The Provider is the editable source of truth. Remove only values that
    // this adapter generated on an earlier launch; preserve unrelated user
    // headers in the official OpenCode file.
    headers.entrySet()
        .filter { (_, value) ->
            value.isJsonPrimitive &&
                value.asString.startsWith("{env:OMNIBOT_PROVIDER_HEADER_")
        }
        .map { it.key }
        .forEach(headers::remove)
    ProviderCustomHeaderUtils.sanitizeCustomHeaders(customHeaders)
        .entries
        .forEachIndexed { index, (name, _) ->
            headers.addProperty(name, "{env:OMNIBOT_PROVIDER_HEADER_$index}")
        }
    if (headers.entrySet().isEmpty()) {
        provider.remove("headers")
    }
    val models = provider.getAsJsonObject("models") ?: com.google.gson.JsonObject().also {
        provider.add("models", it)
    }
    val availableModels = (providerModels + ProviderModelOption(id = providerModel))
        .distinctBy { it.id }.filter { it.id.isNotBlank() }
    if (providerModels.isNotEmpty()) {
        // Replace only the managed Provider catalog; retain settings for models
        // still present and leave unrelated user providers untouched.
        val availableIds = availableModels.map { it.id }.toSet()
        models.keySet().toList().filterNot { it in availableIds }.forEach(models::remove)
    }
    availableModels.forEach { entry ->
            val modelConfig = models.getAsJsonObject(entry.id)
                ?: com.google.gson.JsonObject().also { models.add(entry.id, it) }
            modelConfig.addProperty("name", entry.displayName.ifBlank { entry.id })
            // Forward declared capabilities. Let OpenCode own its supported variants;
            // never infer reasoning levels or token limits from a model name.
            val capabilities = modelConfig.objectOrCreate("capabilities")
            entry.reasoning?.let { supported ->
                if (!supported) capabilities.remove("reasoning")
                capabilities.objectOrCreate("reasoning").addProperty("supported", supported)
            }
            entry.toolCall?.let { capabilities.addProperty("tools", it) }
            entry.temperature?.let { capabilities.addProperty("temperature", it) }
            if (entry.inputModalities.isNotEmpty()) capabilities.add("input", com.google.gson.JsonArray().apply {
                entry.inputModalities.forEach(::add)
            })
            if (entry.outputModalities.isNotEmpty()) capabilities.add("output", com.google.gson.JsonArray().apply {
                entry.outputModalities.forEach(::add)
            })
            if (capabilities.size() == 0) modelConfig.remove("capabilities")
            if (entry.contextLimit != null || entry.inputLimit != null || entry.outputLimit != null) {
                val limits = modelConfig.getAsJsonObject("limit") ?: com.google.gson.JsonObject()
                entry.contextLimit?.let { limits.addProperty("context", it) }
                entry.inputLimit?.let { limits.addProperty("input", it) }
                entry.outputLimit?.let { limits.addProperty("output", it) }
                modelConfig.add("limit", limits)
            }
        }

    return GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n"
}


internal fun managedAgentTerminalPackageId(profile: AcpAgentProfile): String? =
    AcpAgentProfileStore.officialRuntime(profile)?.terminalPackageId

internal fun npmPackageName(spec: String): String {
    val versionSeparator = spec.lastIndexOf('@')
    return if (versionSeparator > 0) spec.substring(0, versionSeparator) else spec
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

internal fun tomlString(value: String): String {
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

internal fun extractTomlString(source: String, key: String): String? {
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

internal fun extractOpenAiApiKey(source: String): String? {
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
internal const val CODEX_CONFIG_TOML_PATH = "/root/.codex/config.toml"
internal const val CODEX_AUTH_JSON_PATH = "/root/.codex/auth.json"
internal const val CODEX_MODEL_CATALOG_JSON_PATH = "/root/.codex/provider-model-catalog.json"
internal const val CLAUDE_SETTINGS_CONFIG_PATH = "/root/.claude/settings.json"
internal const val OPENCODE_CONFIG_JSON_PATH = "/root/.config/opencode/opencode.json"
internal const val CODEX_CONFIG_TOML_DISPLAY_PATH = "~/.codex/config.toml"
internal const val CODEX_AUTH_JSON_DISPLAY_PATH = "~/.codex/auth.json"
internal const val CLAUDE_SETTINGS_JSON_DISPLAY_PATH = "~/.claude/settings.json"
internal const val OPENCODE_CONFIG_JSON_DISPLAY_PATH = "~/.config/opencode/opencode.json"
private const val AGENT_CONFIG_START_MARKER = "__OMNI_AGENT_CONFIG_START__"
private const val AGENT_CONFIG_END_MARKER = "__OMNI_AGENT_CONFIG_END__"
internal const val DEFAULT_EMPTY_JSON_FILE = "{\n}\n"

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
        keys = setOf("sessionId", "session_id", "threadId", "thread_id"),
        nestedObjectKeys = setOf(
            "thread",
            "message",
            "payload",
            "data",
            "event",
            "notification",
            "params",
            "update",
            "result",
            "_meta",
            "msg"
        )
    )
}

internal fun extractTurnId(value: Any?): String? {
    val fromTurn = extractStringRecursive(
        value = value,
        // `taskId`/`runId` are read-only compatibility aliases used by the
        // removed AgentStreamEvent bridge. They are normalized into ACP's
        // turnId before reaching Flutter; no legacy transport is restored.
        keys = setOf("turnId", "turn_id", "taskId", "task_id", "runId", "run_id"),
        nestedObjectKeys = setOf(
            "turn",
            "message",
            "payload",
            "data",
            "event",
            "notification",
            "params",
            "update",
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

/**
 * Resolves an observed event identity without allowing the host's current
 * turn to overwrite an explicit id carried by that event. The latter is only
 * a fallback for session-scoped notifications that genuinely omit a turn.
 */
internal fun resolveObservedTurnId(
    explicitTurnId: String?,
    activeEventTurnId: String?,
    hostActiveTurnId: String?,
    disconnectedTurnId: String?,
    implicitTurnId: String?,
    preferHostActiveTurn: Boolean = false,
): String? = (if (preferHostActiveTurn) {
    sequenceOf(
        hostActiveTurnId,
        explicitTurnId,
        activeEventTurnId,
        disconnectedTurnId,
        implicitTurnId,
    )
} else {
    sequenceOf(
        explicitTurnId,
        activeEventTurnId,
        hostActiveTurnId,
        disconnectedTurnId,
        implicitTurnId,
    )
}).map { it?.trim() }
    .firstOrNull { !it.isNullOrEmpty() }

/** A command catalog may establish its host-bound session, never a prompt turn. */
internal fun admitsBoundAcpCommands(
    method: String,
    updateType: String?,
    sessionId: String?,
    boundSessionId: String?,
    turnId: String?,
    sourceOwnsSession: Boolean,
): Boolean = method == "session/update" &&
    updateType == "available_commands_update" && turnId.isNullOrBlank() &&
    sourceOwnsSession && !sessionId.isNullOrBlank() && sessionId == boundSessionId

/**
 * Conversation attribution is host-owned. A remote ACP notification does not
 * carry the local conversation id, so the session binding must be preferred
 * over any payload projection when one exists.
 */
internal fun resolveAcpEventConversationId(
    remoteEvent: Boolean,
    sessionConversationId: Long?,
    projectedConversationId: Long?,
): Long? = if (remoteEvent) {
    sessionConversationId
} else {
    projectedConversationId
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
