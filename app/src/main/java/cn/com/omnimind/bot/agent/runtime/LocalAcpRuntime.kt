@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import android.util.Base64
import android.util.Log
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.agent.AgentWorkspaceAttachmentSupport
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.bot.mcp.McpServerManager
import cn.com.omnimind.bot.omniflow.OmniVlmPlugin
import cn.com.omnimind.bot.task.runtime.TaskRuntime
import cn.com.omnimind.bot.util.TaskRuntimeSettings
import com.ai.assistance.operit.terminal.TerminalManager
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.client.GlobalElicitationHandler
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.CreateElicitationRequest
import com.agentclientprotocol.model.CreateElicitationResponse
import com.agentclientprotocol.model.CreateTerminalResponse
import com.agentclientprotocol.model.ElicitationAction
import com.agentclientprotocol.model.ElicitationCapabilities
import com.agentclientprotocol.model.ElicitationContentValue
import com.agentclientprotocol.model.ElicitationFormCapabilities
import com.agentclientprotocol.model.ElicitationUrlCapabilities
import com.agentclientprotocol.model.EnvVariable
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.PlanCapabilities
import com.agentclientprotocol.model.PlanVariant
import com.agentclientprotocol.model.ReadTextFileResponse
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
import com.agentclientprotocol.model.ReleaseTerminalResponse
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionConfigOptionCategory
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionConfigSelectOptions
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionModeId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.TerminalExitStatus
import com.agentclientprotocol.model.TerminalOutputResponse
import com.agentclientprotocol.model.WaitForTerminalExitResponse
import com.agentclientprotocol.model.KillTerminalCommandResponse
import com.agentclientprotocol.model.WriteTextFileResponse
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.MethodName
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * Host-side timing for one ACP turn. This intentionally contains only
 * lifecycle stages and elapsed milliseconds; it must never contain prompt
 * text, Provider credentials, or model response content.
 */
private class AcpTurnTiming {
    private val startedAtNanos = System.nanoTime()
    @Volatile
    private var lastActivityAtNanos = startedAtNanos
    private val stages = linkedMapOf<String, Long>()

    @Synchronized
    fun mark(stage: String): Long? {
        lastActivityAtNanos = System.nanoTime()
        if (stages.containsKey(stage)) return null
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
        stages[stage] = elapsed
        return elapsed
    }

    fun touch() {
        lastActivityAtNanos = System.nanoTime()
    }

    fun idleMillis(): Long = TimeUnit.NANOSECONDS.toMillis(
        System.nanoTime() - lastActivityAtNanos
    )
}

internal fun shouldPrepareManagedAgentWithoutSwitchingRuntime(
    managedAdapter: Boolean,
    runtimeConnected: Boolean,
    activeAgentId: String?,
    requestedAgentId: String,
): Boolean = managedAdapter &&
    runtimeConnected &&
    !activeAgentId.isNullOrBlank() &&
    activeAgentId != requestedAgentId

internal class LocalAcpRuntime(
    context: Context,
    private val scope: CoroutineScope,
    private val bindingRepository: AgentSessionBindingRepository,
    private val profileStore: AcpAgentProfileStore,
    private val prepareLaunchEnvironment: suspend (AcpAgentProfile) -> Map<String, String>,
    private val resolveSessionMcpEnabled: suspend (AcpAgentProfile) -> Boolean = { true },
    private val prepareSharedProviderBinding: suspend () -> Unit = {},
    private val buildHandoffContext: suspend (Long, String?) -> String?,
    private val scheduleToolBridge: AgentScheduleToolBridge,
    private val copyConversationHistory: suspend (Long, Long) -> Int = { _, _ -> 0 },
    private val serverRequestOwners: AcpServerRequestOwnerRegistry =
        AcpServerRequestOwnerRegistry(),
    private val onMessage: suspend (Map<String, Any?>) -> Unit
) {
    private val appContext = context.applicationContext
    private val connectMutex = Mutex()
    // Availability probes share one terminal/proot environment. Serializing
    // them prevents overlapping refreshes from racing to persist a stale
    // result after a newer probe has completed.
    private val agentAvailabilityMutex = Mutex()
    @Volatile
    private var sessionMcpEnabled = true
    // A connect attempt includes adapter preparation and ACP initialization.
    // Agent switching must be able to cancel that attempt before waiting for
    // the mutex; otherwise a stalled DSH npm/proot process blocks Xiaowan.
    private val pendingConnectJobs = ConcurrentHashMap.newKeySet<Job>()
    /**
     * Host-side sequence for ACP notification delivery. ACP leaves transport
     * ordering outside SessionUpdate; the shared UI still needs an idempotent
     * key when a bridge retries delivery. It is scoped to the ACP session and
     * does not depend on the selected Harness.
     */
    private val sessionEventSequences = ConcurrentHashMap<String, AtomicLong>()
    private val sessionMutex = Mutex()
    // Session ownership is a transaction: checking a conversation binding
    // and creating/restoring its ACP session must be atomic. This gate only
    // protects allocation, never the prompt itself.
    private val sessionResolutionMutex = Mutex()
    // Keep request-id lookup, active-turn reservation and terminal idempotency
    // in one lifecycle owner. A transport retry can arrive at any point in
    // the start handshake; no other map is allowed to infer turn ownership.
    private val turnLifecycle = AcpTurnLifecycleRegistry()
    private val workspaceManager = AgentWorkspaceManager(appContext)
    private val sessions = ConcurrentHashMap<String, ClientSession>()
    private val sessionCwds = ConcurrentHashMap<String, String>()
    // PromptResponse.usage arrives after the final streamed chunk. Keep the
    // last official assistant message id per turn so its footer metadata is
    // projected onto the existing message rather than a duplicate empty row.
    private val lastAssistantMessageIds =
        ConcurrentHashMap<AcpTurnIdentity, MessageId>()
    /** Redacted, in-memory timing state for the active local ACP turns. */
    private val turnTimings = ConcurrentHashMap<AcpTurnIdentity, AcpTurnTiming>()
    // Keep the Android foreground lease keyed by the same opaque ACP turn id
    // as the lifecycle registry. Local and remote turns must have identical
    // background/sleep behavior; using a boolean here would over-release the
    // process when two local sessions finish out of order.
    private val foregroundTurnIds = ConcurrentHashMap.newKeySet<AcpTurnIdentity>()

    /**
     * Threads currently inside a `session/load` call. Per the ACP spec the
     * agent replays the entire conversation as `session/update` notifications
     * before answering, and we already restore that history from Room — so the
     * replay must not enter the live event stream.
     */
    private val replayingThreads = ConcurrentHashMap.newKeySet<String>()
    private val replaySuppressedThreads = ConcurrentHashMap.newKeySet<String>()
    private val promptJobs = ConcurrentHashMap<String, Job>()
    private val pendingPermissions =
        ConcurrentHashMap<String, PendingPermissionRequest>()
    private val pendingElicitations =
        ConcurrentHashMap<String, PendingElicitationRequest>()
    // ACP leaves implementation extensions intentionally open ended. The
    // JVM SDK has no wildcard Agent->Client handler, so extension
    // requests are intercepted at the stdio boundary and suspended here
    // until the shared `respondToServerRequest` surface resolves them.
    private val pendingExtensionRequests =
        ConcurrentHashMap<String, PendingExtensionRequest>()
    private val terminalProcesses = ConcurrentHashMap<String, AcpTerminalProcess>()
    private val sessionPermissionBehaviors =
        ConcurrentHashMap<String, AcpPermissionBehavior>()
    private val pendingHandoffConversationIds = ConcurrentHashMap<String, Long>()

    @Volatile
    private var connection: AcpRuntimeConnection? = null

    @Volatile
    private var protocol: Protocol? = null

    @Volatile
    private var client: Client? = null

    @Volatile
    private var agentInfo: AgentInfo? = null

    @Volatile
    private var activeProfile: AcpAgentProfile? = null

    /**
     * Environment inherited by ACP-created terminals. Harness tools such as
     * `dsh plugin`, Claude's shell-backed functions, and MCP helper commands
     * must run with the same managed PATH/home/provider settings as the ACP
     * process itself; request-local env values may then override it.
     */
    @Volatile
    private var activeLaunchEnvironment: Map<String, String> = emptyMap()

    @Volatile
    private var catalogSessionId: String? = null

    val isConnected: Boolean
        get() = connection?.isRunning == true && client != null && agentInfo != null

    fun setSessionMcpEnabled(enabled: Boolean) {
        sessionMcpEnabled = enabled
    }

    fun hasActiveTurns(): Boolean = turnLifecycle.hasActiveTurns()

    /**
     * Returns the host-owned turn currently running in an ACP session.
     *
     * Some ACP agents (notably OpenCode) send `session/update` notifications
     * without a turn id. The prompt collector still knows the turn, so the
     * manager must be able to resolve that missing wire field at the runtime
     * boundary instead of dropping otherwise valid stream updates.
     */
    fun activeTurnIdForSession(sessionId: String?): String? =
        sessionId?.takeIf { it.isNotBlank() }?.let(turnLifecycle::activeTurnId)

    fun activeAgentId(): String = (activeProfile ?: profileStore.selected()).id

    fun activeAgentName(): String = (activeProfile ?: profileStore.selected()).name

    /** True when this process owns the given ACP session in memory. */
    fun ownsSession(sessionId: String): Boolean = sessions.containsKey(sessionId.trim())

    fun protocolVersion(): Int? = agentInfo?.protocolVersion

    fun agentVersion(): String? = agentInfo?.implementation?.version

    suspend fun connect(
        profile: AcpAgentProfile = profileStore.selected()
    ) {
        val callerJob = coroutineContext[Job]
        callerJob?.let(pendingConnectJobs::add)
        try {
            connectMutex.withLock {
        Log.i(TAG, "Connecting ACP agent id=${profile.id} command=${profile.command}")
        require(profile.enabled) { "ACP agent ${profile.name} is disabled." }
        if (isConnected && activeProfile?.id == profile.id) {
            return@withLock
        }
        disconnectLocked()
        workspaceManager.ensureRuntimeDirectories()
        val baseEnvironment = try {
            if (profile.id == AcpAgentProfileStore.XIAOWAN_AGENT_ID) {
                sessionMcpEnabled = true
                emptyMap()
            } else {
                prepareLaunchEnvironment(profile).also {
                    requireLaunchCommand(profile)
                    sessionMcpEnabled = resolveSessionMcpEnabled(profile)
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.e(TAG, "ACP launch preparation failed for ${profile.id}: ${error.message}", error)
            val wrapped = wrapInitializationError(profile, error)
            profileStore.saveHealth(profile.id, failedAgentHealth(wrapped))
            throw wrapped
        }
        val nextConnection: AcpRuntimeConnection = if (
        profile.id == AcpAgentProfileStore.XIAOWAN_AGENT_ID
    ) {
            XiaowanAcpConnection(
                context = appContext,
                scope = scope,
                scheduleToolBridge = scheduleToolBridge,
                ensureSharedProviderBinding = prepareSharedProviderBinding,
                conversationIdProvider = { threadId ->
                    bindingRepository.getBindingByThreadId(threadId)?.conversationId
                },
                isXiaowanSession = { threadId ->
                    profileStore.agentIdForSession(threadId) ==
                        AcpAgentProfileStore.XIAOWAN_AGENT_ID ||
                        bindingRepository.getBindingByThreadId(threadId)
                            ?.conversationId
                            ?.let(profileStore::agentIdForConversation) ==
                        AcpAgentProfileStore.XIAOWAN_AGENT_ID
                },
                deleteSession = { threadId ->
                    profileStore.unbindSession(threadId)
                    bindingRepository.detachThread(threadId)
                },
            )
        } else {
            val launchEnvironment = baseEnvironment + profile.environment
            activeLaunchEnvironment = launchEnvironment
            AcpProcessConnection(
                context = appContext,
                scope = scope,
                profile = profile,
                environment = launchEnvironment + mapOf(
                    // Official ACP runtimes use atomic file writers for
                    // sessions, skills and extensions.  The interactive
                    // terminal's historical PRoot link emulation turns
                    // those writes into dangling symlinks, so opt ACP out
                    // without changing ordinary terminal behavior.
                    "OMNIBOT_DISABLE_PROOT_LINK2SYMLINK" to "1",
                    "NODE_OPTIONS" to appendNodeRequire(
                        launchEnvironment["NODE_OPTIONS"],
                        ACP_FILESYSTEM_COMPAT_PATH
                    )
                ),
                onExtensionRequest = { id, method, params ->
                    awaitAgentExtensionRequest(id, method, params)
                },
                onExtensionNotification = { method, params ->
                    publishAgentExtensionNotification(method, params)
                }
            )
        }
        val transport = nextConnection.createTransport(scope)
        val nextProtocol = Protocol(scope, transport)
        // ACP has two elicitation scopes. Session-scoped requests are routed
        // through ClientSessionOperations; request-scoped requests do not
        // belong to a session and therefore use the Client-level handler.
        // Supplying both keeps an Agent from failing merely because it asks
        // for confirmation before a session has been associated with it.
        val nextClient = Client(
            nextProtocol,
            object : GlobalElicitationHandler {
                override suspend fun createElicitation(
                    request: CreateElicitationRequest
                ): CreateElicitationResponse = awaitElicitation(request, null)
            }
        )
        try {
            Log.i(TAG, "Starting ACP process for ${profile.id}")
            nextConnection.start()
            Log.i(TAG, "ACP process started for ${profile.id}")
            nextProtocol.start()
            Log.i(TAG, "ACP protocol started for ${profile.id}; initializing")
            val initialized = initializeAgent(
                client = nextClient,
                connection = nextConnection,
                clientInfo = ClientInfo(
                    capabilities = ClientCapabilities(
                        fs = FileSystemCapability(
                            readTextFile = true,
                            writeTextFile = true
                        ),
                        terminal = true,
                        planCapabilities = PlanCapabilities(),
                        elicitation = ElicitationCapabilities(
                            form = ElicitationFormCapabilities(),
                            url = ElicitationUrlCapabilities()
                        ),
                        // DeepSeek Harness gates its Cordis/plugin plane on
                        // this negotiated metadata. These are optional ACP
                        // hints and are ignored by Harnesses that do not use
                        // them; terminal_output also enables native tool
                        // output through the standard terminal callbacks.
                        _meta = ACP_CLIENT_CAPABILITY_META
                    ),
                    implementation = Implementation(
                        name = "omnibot-app",
                        version = BuildConfig.VERSION_NAME,
                        title = "OmnibotApp"
                    )
                )
            )
            Log.i(
                TAG,
                "ACP initialized for ${profile.id}: " +
                    "implementation=${initialized.implementation?.name} " +
                    "version=${initialized.implementation?.version}"
            )
            connection = nextConnection
            protocol = nextProtocol
            client = nextClient
            agentInfo = initialized
            activeProfile = profile
            profileStore.saveHealth(
                profile.id,
                AcpAgentHealth(
                    status = AcpAgentHealth.STATUS_ONLINE,
                    installed = true,
                    checkedAt = System.currentTimeMillis(),
                    capabilities = capabilitiesPayload(initialized),
                    preparationRevision = AcpAgentProfileStore
                            .officialRuntime(profile)
                            ?.preparationRevision
                )
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            nextProtocol.close()
            val diagnostics = nextConnection.diagnosticSummary()
            Log.e(
                TAG,
                "ACP initialize failed for ${profile.id}: " +
                    "${error.message ?: error.javaClass.simpleName}" +
                    if (diagnostics.isBlank()) "" else "; $diagnostics",
                error
            )
            nextConnection.close()
            val failure = if (
                error is TimeoutCancellationException &&
                diagnostics.isNotBlank()
            ) {
                IllegalStateException(
                    "ACP initialize timed out after ${INITIALIZE_TIMEOUT_MS / 1_000}s. " +
                        diagnostics,
                    error
                )
            } else {
                error
            }
            val wrapped = wrapInitializationError(
                profile,
                failure
            )
            profileStore.saveHealth(
                profile.id,
                failedAgentHealth(wrapped)
            )
            throw wrapped
        }
            }
        } finally {
            callerJob?.let(pendingConnectJobs::remove)
        }
    }

    private suspend fun cancelPendingConnectAttempts() {
        val currentJob = coroutineContext[Job]
        val pending = pendingConnectJobs.filter { it !== currentJob }
        if (pending.isEmpty()) return
        Log.i(TAG, "Cancelling ${pending.size} pending ACP connect attempt(s) before switch")
        pending.forEach(Job::cancel)
        pending.forEach { job ->
            withTimeoutOrNull(CONNECT_CANCEL_TIMEOUT_MS) {
                job.join()
            }
        }
    }

    private suspend fun requireLaunchCommand(profile: AcpAgentProfile) {
        val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
            command = "$MANAGED_NPM_PATH_PREFIX " +
                "command -v ${shellQuoteAcp(profile.command)} >/dev/null 2>&1",
            executorKey = "acp-launch-command-${profile.id}",
            timeoutMs = COMMAND_PROBE_TIMEOUT_MS
        )
        if (!result.isOk || result.exitCode != 0) {
            throw IllegalStateException(
                "ACP launch command not found: ${profile.command}. " +
                    "Open Agent mode settings to configure the command or install its adapter."
            )
        }
    }

    private fun appendNodeRequire(existing: String?, path: String): String {
        val option = "--require $path"
        return existing.orEmpty().trim()
            .let { current -> if (current.contains(option)) current else "$current $option".trim() }
    }

    private suspend fun initializeAgent(
        client: Client,
        connection: AcpRuntimeConnection,
        clientInfo: ClientInfo
    ): AgentInfo = withTimeout(INITIALIZE_TIMEOUT_MS) {
        coroutineScope {
            val initialize = async { client.initialize(clientInfo) }
            select {
                initialize.onAwait { it }
                connection.exitSignal.onAwait { exitCode ->
                    initialize.cancel()
                    throw IllegalStateException(
                        connection.exitDescription(exitCode)
                    )
                }
            }
        }
    }

    private fun wrapInitializationError(
        profile: AcpAgentProfile,
        error: Throwable
    ): IllegalStateException {
        if (
            error is IllegalStateException &&
            error.message?.startsWith("Failed to initialize ACP agent ") == true
        ) {
            return error
        }
        return IllegalStateException(
            "Failed to initialize ACP agent ${profile.name}: " +
                (error.message ?: error.javaClass.simpleName),
            error
        )
    }

    suspend fun disconnect() {
        // A switch may arrive while the previous connect is still preparing
        // an official adapter (npm/proot/provider discovery).  That work is
        // tracked independently of the mutex, so cancel it before waiting
        // for the mutex; otherwise the switch waits behind the very connect
        // it is supposed to replace.
        cancelPendingConnectAttempts()
        connectMutex.withLock {
            disconnectLocked()
        }
    }

    private suspend fun disconnectLocked() {
        Log.i(TAG, "Disconnecting ACP runtime profile=${activeProfile?.id ?: "none"}")
        // Session MCP capability belongs to one Harness launch. A custom
        // Responses-backed Codex launch can disable it, but the next Harness
        // (including in-process Xiaowan, which has no external preparation
        // callback) must start from the default enabled capability.
        sessionMcpEnabled = true
        // Close every in-flight turn before tearing the transport down.
        // Cancelling the prompt jobs first would leave their finally blocks
        // racing a dead connection, and the UI would keep showing those turns
        // as running forever.
        turnLifecycle.activeRecords().forEach { record ->
            val threadId = record.sessionId
            val turnId = record.turnId
            finishTurn(threadId, turnId, status = "cancelled")
        }
        // Cancel all turns together and apply one total deadline. Waiting for
        // each prompt serially made a switch cost N * 2s when several turns
        // were still registered (and a vendor adapter could ignore every
        // cancellation). The process close below remains the hard stop.
        val inFlightPromptJobs = promptJobs.values.toList()
        inFlightPromptJobs.forEach(Job::cancel)
        val settled = withTimeoutOrNull(CANCEL_JOIN_TIMEOUT_MS) {
            inFlightPromptJobs.forEach { it.join() }
            true
        } == true
        if (!settled && inFlightPromptJobs.isNotEmpty()) {
            // A vendor ACP adapter may be blocked in a non-cancellable stdio
            // read. Do not hold the switch mutex forever; the process close
            // below is the hard stop and the next Harness will reconnect
            // cleanly.
            Log.w(TAG, "Timed out cancelling ACP prompts before switch")
        }
        promptJobs.clear()
        pendingPermissions.keys.forEach(serverRequestOwners::remove)
        pendingPermissions.values.forEach { it.response.complete(null) }
        pendingPermissions.clear()
        pendingElicitations.keys.forEach(serverRequestOwners::remove)
        pendingElicitations.values.forEach { pending ->
            pending.response.complete(
                CreateElicitationResponse(action = ElicitationAction.Cancel)
            )
        }
        pendingElicitations.clear()
        pendingExtensionRequests.keys.forEach(serverRequestOwners::remove)
        pendingExtensionRequests.values.forEach { pending ->
            pending.response.complete(
                RawAcpExtensionReply(
                    error = buildJsonObject {
                        put("code", -32800)
                        put("message", "ACP runtime disconnected")
                    }
                )
            )
        }
        pendingExtensionRequests.clear()
        terminalProcesses.values.forEach { terminal ->
            runCatching { terminal.process.destroyForcibly() }
            terminal.readerJob.cancel()
        }
        terminalProcesses.clear()
        sessions.clear()
        sessionCwds.clear()
        // These maps are scoped to one ACP transport. A Harness is allowed
        // to reuse opaque session/turn ids after restart, so retaining them
        // would drop valid updates or treat a new turn as already finished.
        sessionEventSequences.clear()
        lastAssistantMessageIds.clear()
        sessionPermissionBehaviors.clear()
        pendingHandoffConversationIds.clear()
        replayingThreads.clear()
        replaySuppressedThreads.clear()
        catalogSessionId = null
        turnLifecycle.clear()
        protocol?.close()
        protocol = null
        client = null
        agentInfo = null
        activeProfile = null
        activeLaunchEnvironment = emptyMap()
        val oldConnection = connection
        connection = null
        if (oldConnection != null) {
            withTimeoutOrNull(PROCESS_CLOSE_TIMEOUT_MS) {
                oldConnection.close()
            } ?: Log.w(TAG, "Timed out closing ACP process")
        }
        Log.i(TAG, "Disconnected ACP runtime")
    }

    fun statusPayload(): Map<String, Any?> {
        val selected = activeProfile ?: profileStore.selected()
        return linkedMapOf(
            // Include the live transport state when this payload is returned
            // as part of agent/select.  The Flutter shortcut can then render
            // the selected Harness immediately without issuing a second
            // status/connect round-trip after the process has already been
            // initialized here.
            "connected" to isConnected,
            "ready" to isConnected,
            "runtime" to "local",
            "protocol" to "acp",
            "protocolVersion" to agentInfo?.protocolVersion,
            "activeAgentId" to selected.id,
            "activeAgentName" to selected.name,
            "agentImplementation" to agentInfo?.implementation?.let {
                linkedMapOf(
                    "name" to it.name,
                    "title" to it.title,
                    "version" to it.version
                )
            },
            "capabilities" to capabilitiesPayload(agentInfo)
        )
    }

    private suspend fun agentsPayload(
        refreshAvailability: Boolean = true,
        includeRuntimeStatus: Boolean = false,
    ): Map<String, Any?> {
        if (refreshAvailability) {
            refreshAgentAvailability()
        }
        val selectedId = profileStore.selected().id
        return linkedMapOf<String, Any?>(
            "selectedAgentId" to selectedId,
            "agents" to profileStore.list().map {
                it.toPayload(
                    selected = it.id == selectedId,
                    health = profileStore.health(it.id)
                )
            }
        ).apply {
            if (includeRuntimeStatus) {
                putAll(statusPayload())
            }
        }
    }

    suspend fun handleMethod(method: String, args: Map<String, Any?>): Any? {
        val canonicalArgs = AcpSessionCompatibility.canonicalize(method, args)
        return when (method) {
            // Listing is a read-only, latency-sensitive UI operation. Do not
            // start the terminal/proot health probe while opening Agent mode;
            // the page loads cached health immediately and triggers the
            // explicit refresh probe in the background. The refresh endpoint
            // remains the opt-in path for a fresh availability check.
            "agent/list" -> agentsPayload(refreshAvailability = false)
            "agent/refresh" -> agentsPayload(refreshAvailability = true)
            "agent/select" -> selectAgent(args.stringValue("agentId").orEmpty())
            "agent/save" -> saveAgent(args)
            "agent/delete" -> deleteAgent(args.stringValue("agentId").orEmpty())
            "agent/test" -> testAgent(args.stringValue("agentId"))
            "agent/prepare" -> prepareAgent(args.stringValue("agentId"))
            // Public ACP surface. The app keeps the legacy conversation
            // terminology out of the client-facing transport; these names
            // are the protocol's session operations and are shared by every
            // local ACP agent.
            "session/new" -> newAcpSession(canonicalArgs)
            "session/load" -> loadAcpSession(canonicalArgs)
            "session/resume" -> resumeAcpSession(canonicalArgs)
            "session/fork" -> forkAcpSession(canonicalArgs)
            "session/list" -> listAcpSessions(canonicalArgs)
            "session/prompt" -> promptAcpSession(canonicalArgs)
            "session/cancel" -> cancelAcpSession(canonicalArgs)
            "session/set_mode" -> setSessionMode(canonicalArgs)
            "session/set_config_option" -> setSessionConfigOption(canonicalArgs)
            "session/close" -> closeAcpSession(canonicalArgs)
            "session/delete" -> deleteAcpSession(canonicalArgs)
            // The session surface is canonical. The implementation below
            // still uses the historical thread-named helpers, so normalize
            // their response back to sessionId/promptId at this boundary.
            "session/archive" -> archiveThread(canonicalArgs, true).withAcpSessionId()
            "session/unarchive" -> archiveThread(canonicalArgs, false).withAcpSessionId()
            "session/name/set" -> setThreadName(canonicalArgs).withAcpSessionId()
            "thread/archive" -> archiveThread(args, true)
            "thread/unarchive" -> archiveThread(args, false)
            "thread/name/set" -> setThreadName(args)
            "model/list" -> listModels(canonicalArgs)
            "config/read" -> readRunConfig(canonicalArgs)
            "config/set" -> setConfigOption(canonicalArgs)
            "collaborationMode/list" -> listCollaborationModes(canonicalArgs)
            "review/start" -> startReview(canonicalArgs)
            "authenticate", "auth/authenticate" -> authenticateAcp(canonicalArgs)
            "logout", "auth/logout" -> logoutAcp(canonicalArgs)
            "providers/list", "auth/providers/list" -> listAcpProviders(canonicalArgs)
            "providers/set", "auth/providers/set" -> setAcpProvider(canonicalArgs)
            "providers/disable", "auth/providers/disable" -> disableAcpProvider(canonicalArgs)
            "respondToServerRequest" -> respondToPermission(args)
            "notifyAcpExtension" -> sendRawAgentNotification(
                args.stringValue("method")
                    ?: throw IllegalArgumentException("method is required"),
                args["params"]
            )
            // ACP extension methods are arbitrary JSON-RPC method names. The
            // protocol reserves the underscore namespace for implementation
            // extensions; forward those methods unchanged so a Harness can
            // expose an optional capability without another app-private
            // transport. Unknown core-looking methods still fail loudly.
            else -> if (method.startsWith("_")) {
                sendRawAgentRequestValue(method, canonicalArgs)
            } else {
                throw UnsupportedOperationException(
                    "ACP agent does not expose the legacy method '$method'."
                )
            }
        }
    }

    private suspend fun newAcpSession(args: Map<String, Any?>): Map<String, Any?> {
        return startThread(args).withAcpSessionId()
    }

    private suspend fun loadAcpSession(args: Map<String, Any?>): Map<String, Any?> {
        val explicitSessionId = args.stringValue("sessionId")
            ?: args.stringValue("threadId")
        val requestedConversationId = args.longValue("conversationId")
        val explicitBinding = explicitSessionId?.let {
            bindingRepository.getBindingByThreadId(it)
        }
        Log.i(
            TAG,
            "ACP session/load requested session=${explicitSessionId?.let(::compactId)} " +
                "conversation=${requestedConversationId ?: "none"}"
        )
        // A stale page can send the previous session id together with the new
        // conversation id. Never load that session into the new conversation;
        // let the durable conversation binding resolve the correct session.
        val staleExplicitSession = !explicitThreadMatchesConversation(
            explicitThreadId = explicitSessionId,
            requestedConversationId = requestedConversationId,
            boundConversationId = explicitBinding?.conversationId,
        )
        if (staleExplicitSession) {
            Log.w(
                TAG,
                "ACP session/load ignored stale session=${explicitSessionId?.let(::compactId)} " +
                    "for conversation=$requestedConversationId"
            )
        }
        val normalized = if (!staleExplicitSession &&
            args.stringValue("sessionId") != null &&
            args.stringValue("threadId") == null
        ) {
            args + ("threadId" to args.stringValue("sessionId"))
        } else if (staleExplicitSession) {
            LinkedHashMap(args).apply {
                remove("sessionId")
                remove("threadId")
            }
        } else {
            args
        }
        val hasConversationBinding = requestedConversationId?.let { conversationId ->
            bindingRepository.getBindingByConversationId(conversationId) != null
        } == true
        if (shouldCreateSessionForConversationLoad(
                explicitSessionId = normalized.stringValue("sessionId"),
                explicitThreadId = normalized.stringValue("threadId"),
                conversationId = requestedConversationId,
                hasConversationBinding = hasConversationBinding
            )
        ) {
            // Pre-ACP Xiaowan conversations have durable messages but no ACP
            // binding. Loading such a conversation must materialize the first
            // ACP session instead of failing with "No ACP session is bound".
            Log.i(
                TAG,
                "ACP session/load creating missing binding for conversation=$requestedConversationId"
            )
            return startThread(
                normalized,
                allowCatalogReuse = false
            ).withAcpSessionId().plus(
                "sessionRestored" to false
            )
        }
        return resumeThread(normalized, preferResume = false).withAcpSessionId()
    }

    private suspend fun resumeAcpSession(args: Map<String, Any?>): Map<String, Any?> =
        resumeThread(args, preferResume = true).withAcpSessionId()

    private suspend fun listAcpSessions(args: Map<String, Any?>): Map<String, Any?> {
        val response = listThreads(args)
        val sessions = (response["threads"] as? List<*>)?.map { entry ->
            val map = entry as? Map<*, *> ?: return@map entry
            LinkedHashMap<String, Any?>().apply {
                map.entries.forEach { (key, value) -> put(key.toString(), value) }
                val sessionId = stringValue("sessionId") ?: stringValue("threadId")
                    ?: stringValue("id")
                if (!sessionId.isNullOrBlank()) put("sessionId", sessionId)
            }
        }
        return LinkedHashMap(response).apply {
            put("sessions", sessions.orEmpty())
        }
    }

    private suspend fun promptAcpSession(args: Map<String, Any?>): Map<String, Any?> {
        val normalized = if (args.stringValue("sessionId") != null &&
            args.stringValue("threadId") == null
        ) {
            args + ("threadId" to args.stringValue("sessionId"))
        } else {
            args
        }
        return startTurn(normalized).withAcpSessionId()
    }

    private suspend fun cancelAcpSession(args: Map<String, Any?>): Map<String, Any?> {
        val normalized = if (args.stringValue("sessionId") != null &&
            args.stringValue("threadId") == null
        ) {
            args + ("threadId" to args.stringValue("sessionId"))
        } else {
            args
        }
        cancelPendingPermissionRequests(normalized.stringValue("threadId").orEmpty())
        val runId = normalized.stringValue("runId")?.trim().orEmpty()
        if (runId.isNotEmpty() && OmniVlmPlugin.stop(runId)) {
            return mapOf(
                "ok" to true,
                "cancelled" to true,
                "runId" to runId,
                "sessionId" to normalized.stringValue("sessionId"),
                "threadId" to normalized.stringValue("threadId"),
                "turnId" to normalized.stringValue("turnId"),
            ).filterValues { it != null }
        }
        val threadId = normalized.stringValue("threadId")
            ?: throw IllegalArgumentException("sessionId is required")
        if (turnLifecycle.activeTurnId(threadId) == null) {
            // ACP cancellation is safe to repeat after a prompt response or
            // a previous cancel. Do not turn an idle stop button into an
            // "active turn id is missing" error.
            return mapOf(
                "ok" to true,
                "cancelled" to false,
                "sessionId" to threadId,
                "threadId" to threadId,
            )
        }
        return interruptTurn(normalized).withAcpSessionId()
    }

    /** Official ACP mode mutation; the old config/set entry remains below for compatibility. */
    private suspend fun setSessionMode(args: Map<String, Any?>): Map<String, Any?> {
        val session = resolveSessionForMutation(args)
        val requestedModeId = args.stringValue("modeId")
            ?: args.stringValue("mode")
            ?: throw IllegalArgumentException("modeId is required")
        val modeId = resolveAcpSessionModeId(
            session.availableModes.map { it.id.value },
            requestedModeId
        ) ?: throw IllegalArgumentException(
            "Invalid ACP session mode '$requestedModeId'."
        )
        session.setMode(SessionModeId(modeId))
        val sessionId = session.sessionId.value
        sessionPermissionBehaviors[sessionId] = if (isAcpFullAccessMode(modeId)) {
            AcpPermissionBehavior.ALLOW_WITHOUT_PROMPT
        } else {
            AcpPermissionBehavior.ASK_USER
        }
        emitAcpNotification(
            sessionId = sessionId,
            update = mapOf(
                "sessionUpdate" to "current_mode_update",
                "currentModeId" to modeId
            )
        )
        return emptyMap()
    }

    /** Official ACP config mutation; keep the response on the ACP wire shape. */
    private suspend fun setSessionConfigOption(
        args: Map<String, Any?>
    ): Map<String, Any?> {
        val result = setConfigOption(args)
        return mapOf("configOptions" to result["configOptions"])
    }

    /** Official ACP session close. Closing a session must not archive its local history. */
    private suspend fun closeAcpSession(args: Map<String, Any?>): Map<String, Any?> {
        val sessionId = args.stringValue("sessionId")
            ?: args.stringValue("threadId")
            ?: throw IllegalArgumentException("sessionId is required")
        turnLifecycle.activeTurnId(sessionId)?.let { turnId ->
            runCatching {
                interruptTurn(mapOf("threadId" to sessionId, "turnId" to turnId))
            }.onFailure { error ->
                // Closing is a lifecycle boundary even when the Agent does
                // not answer the cancellation request. Otherwise the closed
                // session keeps its host turn reservation and every later
                // prompt is rejected as already running.
                Log.w(
                    TAG,
                    "ACP session close could not interrupt turn=$turnId; " +
                        "finalizing it locally",
                    error,
                )
                if (turnLifecycle.activeTurnId(sessionId) == turnId) {
                    finishTurn(sessionId, turnId, status = "cancelled")
                }
            }
        }
        cancelPendingPermissionRequests(sessionId)
        sessions.remove(sessionId)?.close()
        sessionCwds.remove(sessionId)
        sessionPermissionBehaviors.remove(sessionId)
        return mapOf(
            "ok" to true,
            "closed" to true,
            "sessionId" to sessionId,
            "threadId" to sessionId,
            "historyPreserved" to true,
        )
    }

    /**
     * ACP v1 exposes session/delete in the wire schema even though older JVM
     * SDKs do not yet have a typed Client method. Send it through the typed
     * protocol transport and only detach the local binding after the Agent
     * confirms success. Detaching never removes the Room conversation.
     */
    private suspend fun deleteAcpSession(args: Map<String, Any?>): Map<String, Any?> {
        val sessionId = args.stringValue("sessionId")
            ?: args.stringValue("threadId")
            ?: throw IllegalArgumentException("sessionId is required")
        check(turnLifecycle.activeTurnId(sessionId) == null) {
            "ACP session $sessionId is running; cancel the turn before deleting it."
        }
        cancelPendingPermissionRequests(sessionId)
        // Xiaowan owns no external persisted session state, so its delete
        // operation is the same ACP lifecycle transition as local detachment.
        // External Harnesses receive the official wire request through the
        // typed ACP protocol transport and are detached only after success.
        val response = if (activeAgentId() == AcpAgentProfileStore.XIAOWAN_AGENT_ID) {
            emptyMap()
        } else {
            sendRawAgentRequest(
                method = "session/delete",
                params = mapOf("sessionId" to sessionId)
            )
        }
        sessions.remove(sessionId)?.close()
        sessionCwds.remove(sessionId)
        sessionPermissionBehaviors.remove(sessionId)
        pendingHandoffConversationIds.remove(sessionId)
        profileStore.unbindSession(sessionId)
        val conversationId = bindingRepository.detachThread(sessionId)
        return LinkedHashMap(response).apply {
            put("sessionId", sessionId)
            put("conversationId", conversationId)
            put("historyPreserved", true)
        }
    }

    private suspend fun authenticateAcp(args: Map<String, Any?>): Map<String, Any?> {
        val methodId = args.stringValue("methodId")
            ?: args.stringValue("method")
            ?: throw IllegalArgumentException("methodId is required")
        return sendRawAgentRequest(
            method = "authenticate",
            params = linkedMapOf<String, Any?>(
                "methodId" to methodId,
                "_meta" to args["_meta"]
            ).filterValues { it != null }
        )
    }

    private suspend fun logoutAcp(args: Map<String, Any?>): Map<String, Any?> =
        sendRawAgentRequest("logout", args.withoutLocalIds())

    private suspend fun listAcpProviders(args: Map<String, Any?>): Map<String, Any?> =
        sendRawAgentRequest("providers/list", args.withoutLocalIds())

    private suspend fun setAcpProvider(args: Map<String, Any?>): Map<String, Any?> =
        sendRawAgentRequest("providers/set", args.withoutLocalIds())

    private suspend fun disableAcpProvider(args: Map<String, Any?>): Map<String, Any?> =
        sendRawAgentRequest("providers/disable", args.withoutLocalIds())

    private suspend fun sendRawAgentRequest(
        method: String,
        params: Map<String, Any?>
    ): Map<String, Any?> {
        val response = sendRawAgentRequestValue(method, params)
        return (response as? Map<*, *>)
            ?.entries
            ?.associate { it.key.toString() to it.value }
            ?: emptyMap()
    }

    private suspend fun sendRawAgentRequestValue(
        method: String,
        params: Map<String, Any?>
    ): Any? {
        val response = requireProtocol().sendRequestRaw(
            MethodName(method),
            anyToAcpJson(params),
            null
        )
        return jsonToAny(response)
    }

    private suspend fun sendRawAgentNotification(
        method: String,
        params: Any?
    ): Map<String, Any?> {
        connection?.sendRawMessage(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", anyToAcpJson(params))
            }.toString()
        ) ?: throw IllegalStateException("ACP agent is not connected.")
        return mapOf("ok" to true, "method" to method)
    }

    private fun Map<String, Any?>.withoutLocalIds(): Map<String, Any?> =
        LinkedHashMap(this).apply {
            remove("threadId")
            remove("conversationId")
        }

    private suspend fun resolveSessionForMutation(
        args: Map<String, Any?>
    ): ClientSession {
        val requestedThreadId = args.stringValue("sessionId")
            ?: args.stringValue("threadId")
            ?: args.longValue("conversationId")?.let {
                bindingRepository.getBindingByConversationId(it)?.threadId
            }
        if (requestedThreadId.isNullOrBlank()) {
            return ensureCatalogSession(args)
        }
        return sessions[requestedThreadId] ?: run {
            resumeThread(args + mapOf("threadId" to requestedThreadId))
            sessions[requestedThreadId]
                ?: throw IllegalStateException("Failed to restore ACP session.")
        }
    }

    private fun Map<String, Any?>.withAcpSessionId(): Map<String, Any?> {
        val sessionId = stringValue("sessionId") ?: stringValue("threadId")
        val promptId = stringValue("promptId") ?: stringValue("turnId")
        val result = LinkedHashMap(this).apply {
            if (sessionId != null) {
                put("sessionId", sessionId)
            }
            if (promptId != null) {
                put("promptId", promptId)
            }
        }
        return AcpSessionCompatibility.withLegacyIds(result)
    }

    private suspend fun selectAgent(id: String): Map<String, Any?> {
        cancelPendingConnectAttempts()
        val previous = profileStore.selected()
        val selected = profileStore.select(id)
        // Selecting the already-active Harness is a UI refresh, not a
        // provider switch. Reusing the live ACP transport keeps repeated
        // taps on the top-right selector effectively free and, more
        // importantly, preserves an in-flight session instead of restarting
        // its process.
        if (activeProfile?.id == selected.id && isConnected) {
            return agentsPayload(
                refreshAvailability = false,
                includeRuntimeStatus = true,
            )
        }
        // A provider switch is a process boundary.  Do not rely on the
        // in-memory `isConnected` flag here: after an app restart or a
        // partially failed handshake the old ACP process may still be alive
        // even though the client state is incomplete.  Closing unconditionally
        // prevents the next prompt from being sent to the previous agent.
        if (activeProfile?.id != selected.id || connection != null) {
            Log.i(TAG, "Switching ACP agent to ${selected.id}; closing previous process")
            disconnect()
        }
        // Selecting a managed Agent is also the one-click install/start
        // action.  Previously the Flutter shortcut only persisted the
        // selected profile and then called `status()`.  A missing managed
        // command therefore made `status.ready` false, so connect() — the
        // only boundary that installs the official runtime — was never
        // reached.  Keep preparation here at the ACP boundary so every
        // caller (top shortcut, settings, restored mode, and future clients)
        // gets the same official installation path.
        return try {
            connect(profile = selected)
            agentsPayload(
                refreshAvailability = false,
                includeRuntimeStatus = true,
            )
        } catch (error: Throwable) {
            if (previous.id != selected.id) {
                profileStore.select(previous.id)
                Log.w(
                    TAG,
                    "ACP switch to ${selected.id} failed; restored ${previous.id}",
                    error
                )
            }
            throw error
        }
    }

    private suspend fun saveAgent(args: Map<String, Any?>): Map<String, Any?> {
        val profileMap = args.mapValue("agent").ifEmpty { args }
        val saved = profileStore.save(
            AcpAgentProfile(
                id = profileMap.stringValue("id").orEmpty(),
                name = profileMap.stringValue("name").orEmpty(),
                command = profileMap.stringValue("command").orEmpty(),
                arguments = profileMap.stringList("arguments"),
                environment = profileMap.stringMap("environment"),
                enabled = profileMap["enabled"] != false
            )
        )
        if (activeProfile?.id == saved.id) {
            disconnect()
        }
        return linkedMapOf(
            "agent" to saved.toPayload(
                selected = profileStore.selected().id == saved.id,
                health = profileStore.health(saved.id)
            ),
            "catalog" to agentsPayload(refreshAvailability = false)
        )
    }

    private suspend fun deleteAgent(id: String): Map<String, Any?> {
        if (activeProfile?.id == id) {
            disconnect()
        }
        profileStore.delete(id)
        return agentsPayload(refreshAvailability = false)
    }

    private suspend fun testAgent(id: String?): Map<String, Any?> {
        val profile = profileStore.list().firstOrNull { it.id == id }
            ?: profileStore.selected()
        val runtime = AcpAgentProfileStore.officialRuntime(profile)

        // Detection must remain read-only. In particular, a managed Harness
        // can only be installed from the explicit `agent/prepare` action;
        // clicking Check must never start npm, node-gyp, or a network download.
        if (runtime?.managedAdapterPackage != null) {
            refreshAgentAvailability()
            val health = profileStore.health(profile.id)
            val ready = health.status == AcpAgentHealth.STATUS_ONLINE
            val error = if (ready) {
                null
            } else if (health.status == AcpAgentHealth.STATUS_MISSING) {
                "${profile.name} 尚未安装。请点击“安装官方 Harness”准备运行组件。"
            } else {
                "${profile.name} 尚未完成初始化。请点击“安装官方 Harness”准备运行组件。"
            }
            return linkedMapOf(
                "ok" to ready,
                "agent" to profile.toPayload(
                    selected = profile.id == profileStore.selected().id,
                    health = health
                ),
                "status" to health.status,
                "error" to error,
                // A managed Harness check is deliberately read-only and does
                // not start an ACP process. Return its declared composition
                // capabilities nevertheless; otherwise the UI displays an
                // empty result and makes a healthy DSH installation look as
                // if it has no plugins/tools. A later initialize handshake
                // replaces these with negotiated ACP values.
                "capabilities" to profile.toPayload(
                    selected = profile.id == profileStore.selected().id,
                    health = health
                )["capabilities"],
            )
        }

        val wasSelected = profileStore.selected()
        val wasConnected = isConnected
        return runCatching {
            connect(profile = profile)
            linkedMapOf(
                "ok" to true,
                "agent" to profile.toPayload(
                    selected = profile.id == profileStore.selected().id,
                    health = profileStore.health(profile.id)
                ),
                "protocolVersion" to protocolVersion(),
                "implementation" to statusPayload()["agentImplementation"],
                "capabilities" to statusPayload()["capabilities"]
            )
        }.getOrElse { error ->
            val health = failedAgentHealth(error)
            profileStore.saveHealth(profile.id, health)
            linkedMapOf(
                "ok" to false,
                "agent" to profile.toPayload(false, health),
                "status" to health.status,
                "error" to (error.message ?: error.javaClass.simpleName)
            )
        }.also {
            if (profile.id != wasSelected.id) {
                disconnect()
                profileStore.select(wasSelected.id)
                if (wasConnected) {
                    connect(profile = wasSelected)
                }
            }
        }
    }

    private suspend fun prepareAgent(id: String?): Map<String, Any?> {
        val profile = profileStore.list().firstOrNull { it.id == id }
            ?: profileStore.selected()
        val wasSelected = profileStore.selected()
        val wasConnected = isConnected
        val managedAdapter = AcpAgentProfileStore
            .officialRuntime(profile)
            ?.managedAdapterPackage != null
        // Explicit preparation is the only path allowed to reset the managed
        // Harness health and enter connect(), which may install dependencies.
        if (managedAdapter) {
            profileStore.saveHealth(
                profile.id,
                profileStore.health(profile.id).copy(
                    status = AcpAgentHealth.STATUS_UNCHECKED,
                    error = null
                )
            )
        }
        if (shouldPrepareManagedAgentWithoutSwitchingRuntime(
                managedAdapter = managedAdapter,
                runtimeConnected = wasConnected,
                activeAgentId = activeProfile?.id,
                requestedAgentId = profile.id,
            )
        ) {
            // Installing another official Harness must not tear down the
            // connection currently serving chat turns. Prepare its managed
            // command in place; the normal agent/select path performs the ACP
            // handshake when the user actually switches to that Harness.
            return runCatching {
                prepareLaunchEnvironment(profile)
                requireLaunchCommand(profile)
                val health = managedAgentPreparationHealth(
                    preparationRevision = AcpAgentProfileStore
                        .officialRuntime(profile)
                        ?.preparationRevision,
                )
                profileStore.saveHealth(profile.id, health)
                linkedMapOf(
                    "ok" to true,
                    "agent" to profile.toPayload(
                        selected = profile.id == wasSelected.id,
                        health = health,
                    ),
                    "status" to health.status,
                    "capabilities" to profile.toPayload(
                        selected = profile.id == wasSelected.id,
                        health = health,
                    )["capabilities"],
                )
            }.getOrElse { error ->
                val health = failedAgentHealth(error)
                profileStore.saveHealth(profile.id, health)
                linkedMapOf(
                    "ok" to false,
                    "agent" to profile.toPayload(false, health),
                    "status" to health.status,
                    "error" to (error.message ?: error.javaClass.simpleName),
                )
            }
        }
        return runCatching {
            connect(profile = profile)
            linkedMapOf(
                "ok" to true,
                "agent" to profile.toPayload(
                    selected = profile.id == profileStore.selected().id,
                    health = profileStore.health(profile.id)
                ),
                "protocolVersion" to protocolVersion(),
                "implementation" to statusPayload()["agentImplementation"],
                "capabilities" to statusPayload()["capabilities"]
            )
        }.getOrElse { error ->
            val health = failedAgentHealth(error)
            profileStore.saveHealth(profile.id, health)
            linkedMapOf(
                "ok" to false,
                "agent" to profile.toPayload(false, health),
                "status" to health.status,
                "error" to (error.message ?: error.javaClass.simpleName)
            )
        }.also {
            if (profile.id != wasSelected.id) {
                disconnect()
                profileStore.select(wasSelected.id)
                if (wasConnected) {
                    connect(profile = wasSelected)
                }
            }
        }
    }

    private suspend fun refreshAgentAvailability() {
        agentAvailabilityMutex.withLock {
            refreshAgentAvailabilityLocked()
        }
    }

    private suspend fun refreshAgentAvailabilityLocked() {
        val profiles = profileStore.list()
        if (profiles.isEmpty()) return
        val externalProfiles = profiles.filterNot {
            it.id == AcpAgentProfileStore.XIAOWAN_AGENT_ID
        }
        val command = MANAGED_NPM_PATH_PREFIX + "\n" + externalProfiles.flatMap { profile ->
            val id = shellQuoteAcp(profile.id)
            val runtime = AcpAgentProfileStore.officialRuntime(profile)
            val launchExecutable = shellQuoteAcp(profile.command)
            val launchProbe =
                "if command -v $launchExecutable >/dev/null 2>&1; then " +
                    "printf '__OMNI_ACP_AGENT__\\t%s\\tlaunch\\t1\\n' $id; " +
                    "else printf '__OMNI_ACP_AGENT__\\t%s\\tlaunch\\t0\\n' $id; fi"
            val healthProbe = if (runtime?.managedAdapterPackage != null) {
                val healthCommand = runtime.managedAdapterHealthCommand
                if (healthCommand == null) {
                    "printf '__OMNI_ACP_AGENT__\\t%s\\thealth\\t1\\n' $id"
                } else {
                    "if command -v $launchExecutable >/dev/null 2>&1 && " +
                        "$healthCommand >/dev/null 2>&1; then " +
                        "printf '__OMNI_ACP_AGENT__\\t%s\\thealth\\t1\\n' $id; " +
                        "else printf '__OMNI_ACP_AGENT__\\t%s\\thealth\\t0\\n' $id; fi"
                }
            } else {
                ""
            }
            val discoveryProbe = if (runtime?.managedAdapterPackage != null) {
                ""
            } else {
                runtime?.discoveryCommand
                    ?.takeIf { it != profile.command }
                    ?.let { rawCommand ->
                        val executable = shellQuoteAcp(rawCommand)
                        "if command -v $executable >/dev/null 2>&1; then " +
                            "printf '__OMNI_ACP_AGENT__\\t%s\\tdiscovery\\t1\\n' $id; " +
                            "else printf '__OMNI_ACP_AGENT__\\t%s\\tdiscovery\\t0\\n' $id; fi"
                    }
                    .orEmpty()
            }
            listOf(launchProbe, healthProbe, discoveryProbe).filter(String::isNotBlank)
        }.joinToString("\n")
        val availabilityById = if (externalProfiles.isEmpty()) {
            emptyMap()
        } else {
            runCatching {
                TerminalManager.getInstance(appContext).executeHiddenCommand(
                    command = command,
                    executorKey = "acp-agent-catalog-probe",
                    timeoutMs = 15_000L
                ).output.lineSequence().mapNotNull { line ->
                    val parts = line.trim().split('\t')
                    if (parts.size == 4 && parts[0] == "__OMNI_ACP_AGENT__") {
                        Triple(parts[1], parts[2], parts[3] == "1")
                    } else {
                        null
                    }
                }.groupBy { it.first }
            }.getOrDefault(emptyMap())
        }
        val checkedAt = System.currentTimeMillis()
        profiles.forEach { profile ->
            val builtIn = profile.id == AcpAgentProfileStore.XIAOWAN_AGENT_ID
            val availability = availabilityById[profile.id].orEmpty()
                .associate { it.second to it.third }
            val runtime = AcpAgentProfileStore.officialRuntime(profile)
            val launchInstalled = availability["launch"] == true
            val discoveryInstalled = availability["discovery"] == true
            val managedAdapter = runtime?.managedAdapterPackage != null
            val managedHealthCheckPassed = availability["health"]
            val installed = builtIn || launchInstalled ||
                (!managedAdapter && discoveryInstalled)
            val previous = profileStore.health(profile.id)
            val next = when {
                !profile.enabled -> previous.copy(
                    status = AcpAgentHealth.STATUS_OFFLINE,
                    installed = installed,
                    error = "Agent is disabled."
                )
                builtIn -> AcpAgentHealth(
                    status = AcpAgentHealth.STATUS_ONLINE,
                    installed = true,
                    error = null,
                    checkedAt = checkedAt
                )
                managedAdapter -> managedAgentHealthFromProbe(
                    enabled = profile.enabled,
                    launchInstalled = launchInstalled,
                    healthCheckPassed = managedHealthCheckPassed,
                    previous = previous,
                ).let { health ->
                    if (health.status == AcpAgentHealth.STATUS_MISSING) {
                        health.copy(
                            error = "Agent command not found: ${profile.command}",
                        )
                    } else {
                        health
                    }
                }
                !installed -> AcpAgentHealth(
                    status = AcpAgentHealth.STATUS_MISSING,
                    installed = false,
                    error = "Agent command not found: " +
                        profile.command,
                    checkedAt = checkedAt
                )
                previous.installed != true ||
                    previous.status == AcpAgentHealth.STATUS_MISSING -> AcpAgentHealth(
                    status = AcpAgentHealth.STATUS_UNCHECKED,
                    installed = true,
                    checkedAt = checkedAt
                )
                else -> previous.copy(installed = true, checkedAt = checkedAt)
            }
            profileStore.saveHealth(profile.id, next)
        }
    }

    private fun failedAgentHealth(error: Throwable): AcpAgentHealth {
        val message = error.message ?: error.javaClass.simpleName
        val missing = isMissingAcpAgentFailure(message)
        return AcpAgentHealth(
            status = if (missing) {
                AcpAgentHealth.STATUS_MISSING
            } else {
                AcpAgentHealth.STATUS_OFFLINE
            },
            installed = !missing,
            error = message,
            checkedAt = System.currentTimeMillis()
        )
    }

    private suspend fun startThread(
        args: Map<String, Any?>,
        allowCatalogReuse: Boolean = true
    ): Map<String, Any?> =
        sessionMutex.withLock {
            val cwd = normalizeCwd(args.stringValue("cwd"))
            val catalogSession = if (allowCatalogReuse) {
                catalogSessionId
                    ?.let(sessions::get)
                    ?.takeIf { sessionCwds[it.sessionId.value] == cwd }
            } else {
                null
            }
            val session = if (catalogSession != null) {
                Log.i(
                    TAG,
                    "ACP timing agent=${activeAgentId()} stage=session_reused " +
                        "session=${compactId(catalogSession.sessionId.value)}"
                )
                catalogSession
            } else {
                val startedAtNanos = System.nanoTime()
                requireClient().newSession(
                    sessionCreationParameters(cwd, args),
                    operationsFactory()
                ).also {
                    registerSession(it, cwd)
                    Log.i(
                        TAG,
                        "ACP timing agent=${activeAgentId()} stage=session_created " +
                            "session=${compactId(it.sessionId.value)} " +
                            "elapsedMs=${elapsedMillis(startedAtNanos)}"
                    )
                }
            }
            if (catalogSession != null) {
                catalogSessionId = null
            }
            profileStore.bindSession(session.sessionId.value, activeAgentId())
            applyRunConfig(session, args)
            val conversationId = bindingRepository.ensureBinding(
                threadId = session.sessionId.value,
                conversationId = args.longValue("conversationId"),
                cwd = cwd,
                conversationMode = args.stringValue("conversationMode")
                    ?: AgentSessionBindingRepository.AGENT_MODE_STORAGE_VALUE
            )
            profileStore.bindConversation(conversationId, activeAgentId())
            if (activeAgentId() != AcpAgentProfileStore.XIAOWAN_AGENT_ID) {
                // A new external Harness session has no ACP-native history.
                // Carry the durable OmniBot conversation into its first turn;
                // subsequent turns use the same session and do not duplicate
                // the handoff. Xiaowan reads the same history natively in its
                // Provider adapter, so injecting it there would duplicate it.
                pendingHandoffConversationIds[session.sessionId.value] = conversationId
            }
            sessionPayload(session, conversationId)
        }

    /**
     * Fork through the official ACP client operation instead of replaying a
     * prompt into a second session. The fork always receives a fresh local
     * conversation binding; attaching it to the source conversation would
     * make two ACP sessions compete for one history stream.
     */
    private suspend fun forkAcpSession(args: Map<String, Any?>): Map<String, Any?> {
        val sourceThreadId = resolveThreadId(args)
        check(turnLifecycle.activeTurnId(sourceThreadId) == null) {
            "ACP session $sourceThreadId is running; fork it after the turn completes."
        }
        val source = sessions[sourceThreadId] ?: run {
            val restored = resumeThread(args + ("threadId" to sourceThreadId))
            sessions[restored.stringValue("threadId") ?: sourceThreadId]
        }
            ?: throw IllegalStateException("ACP session $sourceThreadId is not loaded.")

        return sessionMutex.withLock {
            val currentSource = sessions[sourceThreadId] ?: source
            val sourceBinding = bindingRepository.getBindingByThreadId(
                currentSource.sessionId.value
            )
            check(turnLifecycle.activeTurnId(sourceThreadId) == null) {
                "ACP session $sourceThreadId started a turn while it was being forked."
            }
            val cwd = normalizeCwd(
                args.stringValue("cwd") ?: sessionCwds[sourceThreadId]
            )
            val forked = requireClient().forkSession(
                SessionId(currentSource.sessionId.value),
                sessionCreationParameters(cwd, args),
                operationsFactory()
            )
            registerSession(forked, cwd)
            profileStore.bindSession(forked.sessionId.value, activeAgentId())
            val conversationId = bindingRepository.ensureBinding(
                threadId = forked.sessionId.value,
                cwd = cwd,
                title = "分支对话",
                conversationMode = args.stringValue("conversationMode")
                    ?: AgentSessionBindingRepository.AGENT_MODE_STORAGE_VALUE
            )
            sourceBinding?.let { binding ->
                val copied = copyConversationHistory(
                    binding.conversationId,
                    conversationId
                )
                Log.i(
                    TAG,
                    "ACP fork history source=${binding.conversationId} " +
                        "target=$conversationId entries=$copied"
                )
            }
            profileStore.bindConversation(conversationId, activeAgentId())
            LinkedHashMap(sessionPayload(forked, conversationId)).apply {
                put("forkedFromSessionId", currentSource.sessionId.value)
            }
        }
    }

    private suspend fun resumeThread(
        args: Map<String, Any?>,
        preferResume: Boolean = true
    ): Map<String, Any?> =
        sessionMutex.withLock {
            val threadId = resolveThreadId(args)
            val expectedAgentId = profileStore.agentIdForSession(threadId)
            if (expectedAgentId != null && expectedAgentId != activeAgentId()) {
                val cwd = normalizeCwd(
                    args.stringValue("cwd")
                        ?: bindingRepository.getBindingByThreadId(threadId)?.cwd
                )
                val fresh = requireClient().newSession(
                    sessionCreationParameters(cwd, args),
                    operationsFactory()
                )
                registerSession(fresh, cwd)
                profileStore.bindSession(fresh.sessionId.value, activeAgentId())
                val conversationId = bindingRepository.ensureBinding(
                    threadId = fresh.sessionId.value,
                    conversationId = args.longValue("conversationId")
                        ?: bindingRepository.getBindingByThreadId(threadId)?.conversationId,
                    cwd = cwd,
                    conversationMode = args.stringValue("conversationMode")
                        ?: AgentSessionBindingRepository.AGENT_MODE_STORAGE_VALUE
                )
                profileStore.bindConversation(conversationId, activeAgentId())
                if (activeAgentId() != AcpAgentProfileStore.XIAOWAN_AGENT_ID) {
                    pendingHandoffConversationIds[fresh.sessionId.value] = conversationId
                }
                return@withLock sessionPayload(fresh, conversationId).plus(
                    mapOf(
                        "sessionRestored" to false,
                        "previousSessionId" to threadId
                    )
                )
            }
            sessions[threadId]?.let {
                Log.i(
                    TAG,
                    "ACP session/load restored in-memory session=${compactId(threadId)}"
                )
                return@withLock sessionPayload(
                    it,
                    bindingRepository.getBindingByThreadId(threadId)?.conversationId
                )
            }
            val capabilities = requireAgentInfo().capabilities
            val cwd = normalizeCwd(
                args.stringValue("cwd")
                    ?: bindingRepository.getBindingByThreadId(threadId)?.cwd
            )
            val parameters = sessionCreationParameters(cwd, args)
            val restored = try {
                when {
                    preferResume && capabilities.sessionCapabilities.resume != null ->
                        requireClient().resumeSession(
                            SessionId(threadId),
                            parameters,
                            operationsFactory()
                        )
                    capabilities.loadSession -> {
                        replayingThreads.add(threadId)
                        if (shouldSuppressAcpReplay(threadId)) {
                            replaySuppressedThreads.add(threadId)
                        }
                        try {
                            requireClient().loadSession(
                                SessionId(threadId),
                                parameters,
                                operationsFactory()
                            )
                        } finally {
                            replayingThreads.remove(threadId)
                            replaySuppressedThreads.remove(threadId)
                        }
                    }
                    !preferResume && capabilities.sessionCapabilities.resume != null ->
                        requireClient().resumeSession(
                            SessionId(threadId),
                            parameters,
                            operationsFactory()
                        )
                    else -> null
                }
            } catch (error: Throwable) {
                if (!isRecoverableAgentThreadError(error.message.orEmpty())) {
                    throw error
                }
                null
            }
            if (restored == null) {
                Log.w(
                    TAG,
                    "ACP session/load could not restore session=${compactId(threadId)}; " +
                        "creating a fresh session with the same conversation binding"
                )
                val fresh = requireClient().newSession(
                    parameters,
                    operationsFactory()
                )
                registerSession(fresh, cwd)
                profileStore.bindSession(fresh.sessionId.value, activeAgentId())
                val conversationId = bindingRepository.ensureBinding(
                    threadId = fresh.sessionId.value,
                    conversationId = args.longValue("conversationId")
                        ?: bindingRepository.getBindingByThreadId(threadId)?.conversationId,
                    cwd = cwd,
                    conversationMode = args.stringValue("conversationMode")
                        ?: AgentSessionBindingRepository.AGENT_MODE_STORAGE_VALUE
                )
                profileStore.bindConversation(conversationId, activeAgentId())
                if (activeAgentId() != AcpAgentProfileStore.XIAOWAN_AGENT_ID) {
                    pendingHandoffConversationIds[fresh.sessionId.value] = conversationId
                }
                return@withLock sessionPayload(fresh, conversationId).plus(
                    mapOf(
                        "sessionRestored" to false,
                        "previousSessionId" to threadId
                    )
                )
            }
            registerSession(restored, cwd)
            Log.i(
                TAG,
                "ACP session/load restored persisted session=${compactId(threadId)}"
            )
            profileStore.bindSession(restored.sessionId.value, activeAgentId())
            val conversationId = bindingRepository.ensureBinding(
                threadId = threadId,
                conversationId = args.longValue("conversationId"),
                cwd = cwd,
                conversationMode = args.stringValue("conversationMode")
                    ?: AgentSessionBindingRepository.AGENT_MODE_STORAGE_VALUE
            )
            profileStore.bindConversation(conversationId, activeAgentId())
            sessionPayload(restored, conversationId)
        }

    private suspend fun readThread(args: Map<String, Any?>): Map<String, Any?> {
        val response = resumeThread(args)
        return LinkedHashMap(response).apply {
            put("active", turnLifecycle.activeTurnId(response["threadId"]?.toString().orEmpty()) != null)
            turnLifecycle.activeTurnId(response["threadId"]?.toString().orEmpty())?.let {
                put("activeTurnId", it)
                put("turnId", it)
            }
        }
    }

    private suspend fun listThreads(args: Map<String, Any?>): Map<String, Any?> {
        val limit = (args["limit"] as? Number)?.toInt()?.coerceIn(1, 200) ?: 50
        val capabilities = requireAgentInfo().capabilities
        val allEntries = if (capabilities.sessionCapabilities.list != null) {
            requireClient().listSessions(
                cwd = args.stringValue("cwd"),
                additionalDirectories = args.stringList("additionalDirectories")
            ).toList().map { session ->
                profileStore.bindSession(session.sessionId.value, activeAgentId())
                bindingRepository.ensureBinding(
                    threadId = session.sessionId.value,
                    cwd = session.cwd,
                    title = session.title
                )
                linkedMapOf(
                    "id" to session.sessionId.value,
                    "threadId" to session.sessionId.value,
                    "cwd" to session.cwd,
                    "title" to session.title,
                    "updatedAt" to session.updatedAt,
                    "agentId" to activeAgentId(),
                    "agentName" to activeAgentName()
                )
            }
        } else {
            sessions.values
                .filterNot { it.sessionId.value == catalogSessionId }
                .map { session ->
                linkedMapOf(
                    "id" to session.sessionId.value,
                    "threadId" to session.sessionId.value,
                    "cwd" to sessionCwds[session.sessionId.value],
                    "agentId" to activeAgentId(),
                    "agentName" to activeAgentName()
                )
            }
        }
        // The app bridge exposes an opaque cursor over a materialized list.
        // Keep the snapshot order deterministic so a retry with the same
        // cursor cannot reshuffle entries from ConcurrentHashMap/Agent output.
        val orderedEntries = allEntries.sortedBy { entry ->
            entry["sessionId"]?.toString()
                ?: entry["threadId"]?.toString()
                ?: entry["id"]?.toString().orEmpty()
        }
        val page = paginateAcpItems(
            items = orderedEntries,
            limit = limit,
            cursor = args.stringValue("cursor"),
        ) { entry ->
            entry["sessionId"]?.toString()
                ?: entry["threadId"]?.toString()
                ?: entry["id"]?.toString().orEmpty()
        }
        return mapOf(
            "threads" to page.items,
            "data" to page.items,
            "nextCursor" to page.nextCursor,
        )
    }

    private suspend fun archiveThread(
        args: Map<String, Any?>,
        archived: Boolean
    ): Map<String, Any?> {
        val threadId = resolveThreadId(args)
        check(turnLifecycle.activeTurnId(threadId) == null) {
            "ACP session $threadId is running; cancel the turn before archiving it."
        }
        if (archived && requireAgentInfo().capabilities.sessionCapabilities.close != null) {
            sessions.remove(threadId)?.close()
            sessionCwds.remove(threadId)
            sessionPermissionBehaviors.remove(threadId)
        }
        bindingRepository.setArchived(threadId, archived)
        return mapOf(
            "ok" to true,
            "threadId" to threadId,
            "conversationId" to bindingRepository.getBindingByThreadId(threadId)?.conversationId
        )
    }

    private suspend fun setThreadName(args: Map<String, Any?>): Map<String, Any?> {
        val threadId = resolveThreadId(args)
        val name = args.stringValue("name").orEmpty()
        bindingRepository.updateTitle(threadId, name)
        emitAcpNotification(
            sessionId = threadId,
            update = mapOf(
                "sessionUpdate" to "session_info_update",
                "title" to name
            )
        )
        return mapOf(
            "ok" to true,
            "threadId" to threadId,
            "conversationId" to bindingRepository.getBindingByThreadId(threadId)?.conversationId
        )
    }

    private suspend fun listModels(args: Map<String, Any?>): Map<String, Any?> {
        val session = ensureCatalogSession(args)
        val modelOption = sessionConfigOptions(session).firstOrNull {
            it.id.value == "model" || it.category == SessionConfigOptionCategory.MODEL
        } as? SessionConfigOption.Select
        val options = modelOption?.flatOptions().orEmpty()
        val acpModels = if (options.isEmpty() && session.modelsSupported) {
            session.availableModels.map {
                linkedMapOf(
                    "id" to it.modelId.value,
                    "model" to it.modelId.value,
                    "displayName" to it.name,
                    "description" to it.description
                )
            }
        } else {
            options.map {
                linkedMapOf(
                    "id" to it.value.value,
                    "model" to it.value.value,
                    "displayName" to it.name,
                    "description" to it.description
                )
            }
        }
        val effortOption = sessionConfigOptions(session).firstOrNull {
            it.id.value == "reasoning_effort" ||
                it.category == SessionConfigOptionCategory.THOUGHT_LEVEL
        } as? SessionConfigOption.Select
        return linkedMapOf(
            "models" to acpModels,
            "modelConfigSupported" to (modelOption != null || session.modelsSupported),
            "currentModelId" to (
                modelOption?.currentValue?.value
                    ?: if (session.modelsSupported) session.currentModel.value.value else null
                ),
            "reasoningEfforts" to effortOption?.flatOptions()?.map { it.value.value }.orEmpty(),
            "currentReasoningEffort" to effortOption?.currentValue?.value,
            "configOptions" to sessionConfigOptions(session).map(::acpConfigOptionPayload)
        )
    }

    private suspend fun readRunConfig(args: Map<String, Any?>): Map<String, Any?> {
        val session = ensureCatalogSession(args)
        val options = sessionConfigOptions(session)
        fun current(id: String, category: SessionConfigOptionCategory? = null): Any? {
            return options.firstOrNull { it.id.value == id || it.category == category }
                ?.currentValuePayload()
        }
        return linkedMapOf(
            "model" to current("model", SessionConfigOptionCategory.MODEL),
            "reasoning_effort" to current(
                "reasoning_effort",
                SessionConfigOptionCategory.THOUGHT_LEVEL
            ),
            "collaborationMode" to current("collaboration_mode"),
            "mode" to current("mode", SessionConfigOptionCategory.MODE),
            "configOptions" to options.map(::acpConfigOptionPayload)
        )
    }

    private suspend fun setConfigOption(args: Map<String, Any?>): Map<String, Any?> {
        val configId = args.stringValue("configId")
            ?: throw IllegalArgumentException("configId is required")
        val rawValue = args["value"]
            ?: throw IllegalArgumentException("value is required")
        val requestedThreadId = args.stringValue("sessionId")
            ?: args.stringValue("threadId")
            ?: args.longValue("conversationId")?.let {
                bindingRepository.getBindingByConversationId(it)?.threadId
            }
        val session = resolveSessionForMutation(args)
        val threadId = session.sessionId.value
        check(turnLifecycle.activeTurnId(threadId) == null) {
            "ACP session $threadId is running; configuration changes apply when idle."
        }
        val option = sessionConfigOptions(session).firstOrNull {
            it.id.value == configId
        } ?: when (configId) {
            "model" -> sessionConfigOptions(session).firstOrNull {
                it.category == SessionConfigOptionCategory.MODEL
            }
            "reasoning_effort" -> sessionConfigOptions(session).firstOrNull {
                it.category == SessionConfigOptionCategory.THOUGHT_LEVEL
            }
            "mode" -> sessionConfigOptions(session).firstOrNull {
                it.category == SessionConfigOptionCategory.MODE
            }
            else -> null
        }
        if (option == null && configId == "model" && session.modelsSupported) {
            val value = rawValue.toString()
            val model = session.availableModels.firstOrNull {
                it.modelId.value == value
            } ?: throw IllegalArgumentException(
                "Invalid value '$value' for ACP model selection."
            )
            if (session.currentModel.value.value != value) {
                session.setModel(model.modelId)
            }
            val options = sessionConfigOptions(session).map(::acpConfigOptionPayload)
            emitAcpNotification(
                sessionId = threadId,
                update = mapOf(
                    "sessionUpdate" to "config_option_update",
                    "configOptions" to options
                )
            )
            return linkedMapOf(
                "ok" to true,
                "threadId" to threadId,
                "configId" to configId,
                "value" to value,
                "configOptions" to options
            )
        }
        if (option == null && configId == "mode" && session.modesSupported) {
            val requestedMode = rawValue.toString()
            val modeId = resolveAcpSessionModeId(
                session.availableModes.map { it.id.value },
                requestedMode
            ) ?: throw IllegalArgumentException(
                "Invalid value '$requestedMode' for ACP mode selection."
            )
            session.setMode(SessionModeId(modeId))
            sessionPermissionBehaviors[threadId] = if (
                isAcpFullAccessMode(modeId)
            ) {
                AcpPermissionBehavior.ALLOW_WITHOUT_PROMPT
            } else {
                AcpPermissionBehavior.ASK_USER
            }
            val options = sessionConfigOptions(session).map(::acpConfigOptionPayload)
            emitAcpNotification(
                sessionId = threadId,
                update = mapOf(
                    "sessionUpdate" to "current_mode_update",
                    "currentModeId" to modeId,
                    "configOptions" to options
                )
            )
            return linkedMapOf(
                "ok" to true,
                "threadId" to threadId,
                "configId" to configId,
                "value" to modeId,
                "configOptions" to options
            )
        }
        option ?: throw IllegalArgumentException(
            "ACP session does not expose config option '$configId'."
        )

        val appliedValue: Any? = when (option) {
            is SessionConfigOption.Select -> {
                val requestedValue = rawValue.toString()
                val value = if (configId == "mode") {
                    resolveAcpSessionModeId(
                        option.flatOptions().map { it.value.value },
                        requestedValue
                    )
                } else {
                    requestedValue.takeIf {
                        option.flatOptions().any { it.value.value == requestedValue }
                    }
                } ?: throw IllegalArgumentException(
                    "Invalid value '$requestedValue' for ACP config option '$configId'."
                )
                if (option.currentValue.value != value) {
                    session.setConfigOption(
                        option.id,
                        SessionConfigOptionValue.StringValue(value)
                    )
                }
                value
            }
            is SessionConfigOption.BooleanOption -> {
                val value = when (rawValue) {
                    is Boolean -> rawValue
                    else -> rawValue.toString().toBooleanStrictOrNull()
                } ?: throw IllegalArgumentException(
                    "Invalid boolean value for ACP config option '$configId'."
                )
                if (option.currentValue != value) {
                    session.setConfigOption(
                        option.id,
                        SessionConfigOptionValue.BoolValue(value)
                    )
                }
                value
            }
        }

        if (configId == "mode" && appliedValue is String) {
            sessionPermissionBehaviors[threadId] = if (
                isAcpFullAccessMode(appliedValue)
            ) {
                AcpPermissionBehavior.ALLOW_WITHOUT_PROMPT
            } else {
                AcpPermissionBehavior.ASK_USER
            }
        }
        val options = sessionConfigOptions(session).map(::acpConfigOptionPayload)
        emitAcpNotification(
            sessionId = threadId,
            update = mapOf(
                "sessionUpdate" to "config_option_update",
                "configOptions" to options
            )
        )
        return linkedMapOf(
            "ok" to true,
            "threadId" to threadId,
            "configId" to configId,
            "value" to appliedValue,
            "configOptions" to options
        )
    }

    private suspend fun listCollaborationModes(
        args: Map<String, Any?>
    ): Map<String, Any?> {
        val session = ensureCatalogSession(args)
        val option = sessionConfigOptions(session)
            .firstOrNull { it.id.value == "collaboration_mode" }
            as? SessionConfigOption.Select
        return mapOf(
            "collaborationModes" to option?.flatOptions()?.map {
                mapOf(
                    "id" to it.value.value,
                    "name" to it.name,
                    "description" to it.description
                )
            }.orEmpty(),
            "currentMode" to option?.currentValue?.value
        )
    }

    private suspend fun startTurn(args: Map<String, Any?>): Map<String, Any?> =
        prepareTurn(args)()

    private suspend fun prepareTurn(
        args: Map<String, Any?>,
    ): suspend () -> Map<String, Any?> {
        val session = ensureSessionForTurn(args)
        val threadId = session.sessionId.value
        val requestId = args.stringValue("requestId")?.takeIf { it.isNotBlank() }
        val turnId = UUID.randomUUID().toString()
        val reservation = turnLifecycle.reserve(threadId, turnId, requestId)
        when (reservation) {
            is AcpTurnReservation.InFlight,
            is AcpTurnReservation.Completed -> {
                val known = when (reservation) {
                    is AcpTurnReservation.InFlight -> reservation.record
                    is AcpTurnReservation.Completed -> reservation.record
                    else -> error("unreachable ACP turn reservation")
                }
                return {
                    linkedMapOf<String, Any?>(
                        "threadId" to threadId,
                        "turnId" to known.turnId,
                        "conversationId" to bindingRepository
                            .getBindingByThreadId(threadId)?.conversationId,
                        "deduplicated" to true,
                        "completed" to (known.terminal != null),
                        "status" to known.terminal?.status,
                        "error" to known.terminal?.error,
                    ).filterValues { it != null }
                }
            }
            is AcpTurnReservation.Busy -> {
                throw IllegalStateException("ACP session $threadId already has an active turn.")
            }
            is AcpTurnReservation.Started -> Unit
        }
        val turnIdentity = AcpTurnIdentity(threadId, turnId)
        turnTimings[turnIdentity] = AcpTurnTiming()
        markTurnTiming(threadId, turnId, "turn_reserved")
        // startThread applies initial configuration for a new session. After
        // that, the idle session is changed through config/set; do not
        // overwrite Harness-owned state on every turn. Older ACP adapters
        // without configOptions keep the legacy per-turn compatibility path.
        try {
            if (sessionConfigOptions(session).isEmpty()) {
                applyRunConfig(session, args)
            } else if (hasAcpPermissionPolicy(args)) {
                sessionPermissionBehaviors[threadId] = resolveAcpPermissionBehavior(args)
            } else {
                // A mode selected through session/set_mode or config/set is
                // session-scoped. Do not erase it when a client sends a
                // minimal prompt without repeating policy fields.
                sessionPermissionBehaviors.putIfAbsent(
                    threadId,
                    AcpPermissionBehavior.ALLOW_WITHOUT_PROMPT
                )
            }
        } catch (error: Throwable) {
            turnLifecycle.release(threadId, turnId)
            turnTimings.remove(turnIdentity)
            throw error
        }
        val blocks = try {
            buildPromptBlocks(args, turnId, threadId)
        } catch (error: Throwable) {
            turnLifecycle.release(threadId, turnId)
            turnTimings.remove(turnIdentity)
            throw error
        }
        val completion = CompletableDeferred<Map<String, Any?>>()
        val activeConnection = connection
            ?: throw IllegalStateException("ACP agent connection is not available.").also {
                turnTimings.remove(turnIdentity)
                turnLifecycle.release(threadId, turnId)
            }
        val timedOut = AtomicBoolean(false)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var stopReason: String? = null
            var cancelled = false
            var failure: Throwable? = null
            try {
                // ACP's prompt response is the terminal signal for this
                // request. Some adapters keep the underlying notification
                // stream open for session-scoped updates after responding;
                // collecting until that transport closes leaves the UI in
                // "thinking" forever even though the turn already ended.
                turnLifecycle.markRunning(threadId, turnId)
                acquireForegroundTurn(threadId, turnId)
                markTurnTiming(threadId, turnId, "prompt_sent")
                session.prompt(blocks, promptMeta(args)).takeWhile { event ->
                    when (event) {
                        is Event.SessionUpdateEvent -> {
                            handleSessionUpdate(threadId, turnId, event.update)
                            true
                        }
                        is Event.PromptResponseEvent -> {
                            stopReason = event.response.stopReason.name.lowercase()
                            event.response.toAcpTurnUsageUpdate(
                                lastAssistantMessageIds[turnIdentity]
                            )?.let { usageUpdate ->
                                emitAcpNotification(
                                    sessionId = threadId,
                                    update = usageUpdate,
                                    timingThreadId = threadId,
                                    timingTurnId = turnId,
                                )
                            }
                            Log.i(
                                TAG,
                                "ACP prompt response for turn=$turnId stopReason=$stopReason"
                            )
                            false
                        }
                    }
                }.collect()
            } catch (error: CancellationException) {
                cancelled = true
            } catch (error: Throwable) {
                Log.e(TAG, "ACP prompt failed", error)
                failure = error
            } finally {
                coroutineContext[Job]?.let { promptJobs.remove(threadId, it) }
                val status = if (timedOut.get()) {
                    "timeout"
                } else {
                    resolveTurnTerminalStatus(stopReason, cancelled, failure)
                }
                finishTurn(
                    threadId = threadId,
                    turnId = turnId,
                    status = status,
                    error = failure?.let { it.message ?: it.javaClass.simpleName }
                )
                completion.complete(
                    linkedMapOf<String, Any?>(
                        "status" to status,
                        "stopReason" to stopReason,
                        "error" to failure?.let { it.message ?: it.javaClass.simpleName },
                        "completed" to true
                    ).filterValues { it != null }
                )
            }
        }
        promptJobs[threadId] = job
        // A few ACP adapters stream updates but never deliver the terminal
        // prompt response. Keep the UI and turn reservation recoverable by
        // timing out only after a period with no ACP activity. This is an
        // inactivity watchdog, so long-running turns that keep streaming are
        // not interrupted merely because they exceed a wall-clock duration.
        val watchdog = scope.launch {
            while (isActive && turnLifecycle.activeTurnId(threadId) == turnId) {
                delay(STALL_CHECK_INTERVAL_MS)
                val timing = turnTimings[turnIdentity] ?: break
                if (timing.idleMillis() < STALL_DEADLINE_MS) continue
                if (!timedOut.compareAndSet(false, true)) break
                val message = "ACP turn stalled for ${STALL_DEADLINE_MS / 1000}s without updates"
                Log.e(TAG, "$message session=$threadId turn=$turnId")
                finishTurn(threadId, turnId, status = "timeout", error = message)
                job.cancel(CancellationException(message))
                break
            }
        }
        job.invokeOnCompletion { watchdog.cancel() }
        // A process exit is not guaranteed to close an in-flight ACP prompt
        // flow.  StdioTransport may remain suspended on the input channel even
        // after the child has gone away, so observing the connection only while
        // initializing is insufficient.  Keep the process lifecycle and the
        // host turn lifecycle joined for every prompt.
        val exitWatcher = scope.launch(start = CoroutineStart.LAZY) {
            val exitCode = activeConnection.exitSignal.await()
            if (
                turnLifecycle.activeTurnId(threadId) != turnId
            ) {
                return@launch
            }
            val error = activeConnection.exitDescription(exitCode)
            Log.e(
                TAG,
                "ACP process exited during turn=$turnId session=$threadId: $error"
            )
            finishTurn(
                threadId = threadId,
                turnId = turnId,
                status = "error",
                error = error
            )
            runCatching {
                withTimeoutOrNull(CANCEL_JOIN_TIMEOUT_MS) {
                    job.cancelAndJoin()
                }
            }
            if (connection === activeConnection) {
                runCatching { disconnect() }
                    .onFailure { closeError ->
                        Log.w(
                            TAG,
                            "Unable to reset ACP runtime after process exit: " +
                                (closeError.message ?: closeError.javaClass.simpleName),
                            closeError
                        )
                    }
            }
        }

        job.invokeOnCompletion {
            exitWatcher.cancel()
        }
        return {
            exitWatcher.start()
            job.start()
            job.join()
            exitWatcher.cancelAndJoin()
            linkedMapOf<String, Any?>(
                "threadId" to threadId,
                "turnId" to turnId,
                "conversationId" to bindingRepository
                    .getBindingByThreadId(threadId)?.conversationId
            ).apply { putAll(completion.await()) }.filterValues { it != null }
        }
    }

    /**
     * The single exit through which a turn is ever declared over.
     *
     * ACP guarantees a `session/prompt` response carrying a stop reason, but
     * the response is a MethodChannel result and is not visible to the
     * EventChannel reducer. Emit the terminal lifecycle notification before
     * releasing the active-turn reservation so both transports observe the
     * same boundary. The reducer treats duplicate completion from the prompt
     * response as idempotent.
     */
    private suspend fun finishTurn(
        threadId: String,
        turnId: String,
        status: String,
        error: String? = null
    ) {
        if (turnLifecycle.finish(threadId, turnId, status, error) == null) return
        val terminalMethod = if (status == "error" || status == "timeout") {
            "turn/failed"
        } else {
            "turn/completed"
        }
        val terminalParams = linkedMapOf<String, Any?>(
            "sessionId" to threadId,
            "turnId" to turnId,
            "status" to status,
            "stopReason" to status,
        )
        error?.takeIf { it.isNotBlank() }?.let { terminalParams["error"] = it }
        runCatching {
            emitHostMessage(
                linkedMapOf(
                    "method" to terminalMethod,
                    "params" to terminalParams,
                    "sessionId" to threadId,
                    "threadId" to threadId,
                    "turnId" to turnId,
                )
            )
        }.onFailure { emissionError ->
            Log.w(
                TAG,
                "Unable to emit terminal lifecycle event for turn=$turnId: " +
                    emissionError.message,
                emissionError,
            )
        }
        markTurnTiming(threadId, turnId, "terminal_$status")
        val turnIdentity = AcpTurnIdentity(threadId, turnId)
        turnTimings.remove(turnIdentity)
        lastAssistantMessageIds.remove(turnIdentity)
        releaseForegroundTurn(threadId, turnId)
    }

    private fun acquireForegroundTurn(sessionId: String, turnId: String) {
        val turnIdentity = AcpTurnIdentity(sessionId, turnId)
        if (!foregroundTurnIds.add(turnIdentity)) return
        TaskRuntimeSettings.onTaskStarted(appContext)
        if (!TaskRuntime.start(appContext, agentTurnRuntimeId(sessionId, turnId))) {
            Log.w(TAG, "Unable to acquire foreground runtime for local turn=$turnId")
        }
    }

    private fun releaseForegroundTurn(sessionId: String, turnId: String) {
        val turnIdentity = AcpTurnIdentity(sessionId, turnId)
        if (!foregroundTurnIds.remove(turnIdentity)) return
        TaskRuntime.finish(appContext, agentTurnRuntimeId(sessionId, turnId))
        TaskRuntimeSettings.onTaskFinished(appContext)
    }

    private suspend fun startReview(args: Map<String, Any?>): Map<String, Any?> {
        return startTurn(args + mapOf("text" to "/review")).withAcpSessionId()
    }

    private suspend fun steerTurn(args: Map<String, Any?>): Map<String, Any?> {
        val threadId = resolveThreadId(args)
        val capabilities = capabilitiesPayload(requireAgentInfo())
        if (capabilities["steering"] != true) {
            throw UnsupportedOperationException(
                "The selected ACP agent did not advertise steering support."
            )
        }
        val text = args.stringValue("text")
            ?: throw IllegalArgumentException("text is required")
        val response = requireProtocol().sendRequestRaw(
            MethodName("session/steer"),
            buildJsonObject {
                put("sessionId", threadId)
                put("prompt", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    })
                })
            },
            SessionId(threadId)
        )
        return mapOf(
            "ok" to true,
            "threadId" to threadId,
            "turnId" to turnLifecycle.activeTurnId(threadId),
            "result" to response.toString()
        )
    }

    private suspend fun interruptTurn(args: Map<String, Any?>): Map<String, Any?> {
        val threadId = resolveThreadId(args)
        val session = sessions[threadId]
            ?: throw IllegalArgumentException("ACP session is not loaded: $threadId")
        // ACP requires the Client to settle every permission prompt after a
        // cancellation. Otherwise the Agent can remain suspended in a
        // permission await even after its prompt collector was cancelled.
        cancelPendingPermissionRequests(threadId)
        val turnId = turnLifecycle.activeTurnId(threadId)
        turnId?.let { markTurnTiming(threadId, it, "cancel_requested") }

        // There are two independent pieces to stop here:
        //
        //  1. Ask the ACP agent to stop the work it is doing.
        //  2. Stop our prompt collector, which owns the host-side turn
        //     reservation and waits for the prompt response.
        //
        // Previously we only did (1). A non-compliant or slow adapter could
        // keep the prompt flow alive forever, so the Android runtime stayed
        // busy and the UI never got a reliable lifecycle boundary.
        val protocolCancelled = withTimeoutOrNull(CANCEL_REQUEST_TIMEOUT_MS) {
            try {
                session.cancel()
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "ACP session cancellation request failed", error)
                false
            }
        } == true

        val promptJob = promptJobs[threadId]
        val collectorStopped = if (promptJob != null) {
            withTimeoutOrNull(CANCEL_JOIN_TIMEOUT_MS) {
                promptJob.cancelAndJoin()
                true
            } == true
        } else {
            true
        }

        // If the adapter ignored cancellation and the collector did not
        // terminate, close the ACP process as a last-resort kill switch. The
        // next prompt will reconnect through ensureLocalAcpConnected(). This
        // is preferable to leaving a Harness executing tools after the user
        // explicitly pressed stop.
        if (turnId != null && (!protocolCancelled || !collectorStopped)) {
            Log.w(
                TAG,
                "ACP cancellation did not settle for session=$threadId " +
                    "protocolCancelled=$protocolCancelled " +
                    "collectorStopped=$collectorStopped; closing process"
            )
            runCatching { disconnect() }
                .onFailure { error ->
                    Log.w(TAG, "Unable to close ACP process after cancellation", error)
                }
        }

        // Close the turn out explicitly. Cancelling the ACP session does not
        // reliably produce a prompt response, and the prompt job may already
        // have been removed by its finally block.
        if (turnId != null && turnLifecycle.activeTurnId(threadId) == turnId) {
            finishTurn(threadId, turnId, status = "cancelled")
        }
        return mapOf(
            "ok" to true,
            "threadId" to threadId,
            "turnId" to turnId,
            "conversationId" to bindingRepository.getBindingByThreadId(threadId)?.conversationId
        )
    }

    private fun respondToPermission(args: Map<String, Any?>): Map<String, Any?> {
        val requestId = args["requestId"]?.toString()
            ?: throw IllegalArgumentException("requestId is required")
        pendingElicitations.remove(requestId)?.let { pending ->
            serverRequestOwners.remove(requestId)
            pending.response.complete(elicitationResponse(args))
            return mapOf("ok" to true)
        }
        pendingExtensionRequests.remove(requestId)?.let { pending ->
            serverRequestOwners.remove(requestId)
            val error = args["error"]?.let(::anyToAcpJson)
            val result = if (error == null) {
                args["response"]?.let(::anyToAcpJson)
                    ?: args["result"]?.let(::anyToAcpJson)
                    ?: buildJsonObject {}
            } else {
                null
            }
            pending.response.complete(RawAcpExtensionReply(result = result, error = error))
            return mapOf("ok" to true)
        }
        val pending = pendingPermissions.remove(requestId)
            ?: throw IllegalArgumentException("Unknown ACP permission request: $requestId")
        serverRequestOwners.remove(requestId)
        val response = args.mapValue("response")
        val explicitOptionId = response.stringValue("optionId")
            ?: response.stringValue("selectedOptionId")
        if (explicitOptionId != null &&
            pending.options.none { it.optionId.value == explicitOptionId }
        ) {
            pending.response.complete(null)
            throw IllegalArgumentException(
                "Unknown ACP permission option: $explicitOptionId"
            )
        }
        val accepted = response.stringValue("decision")?.lowercase() == "accept"
        // ACP clients may return the selected option id directly. Honor it
        // before applying the compatibility decision mapping so ALLOW_ALWAYS
        // and REJECT_ALWAYS are not accidentally downgraded to *_ONCE.
        val selected = explicitOptionId?.let { optionId ->
            pending.options.firstOrNull { it.optionId.value == optionId }
        } ?: pending.options.firstOrNull { option ->
            if (accepted) {
                option.kind == PermissionOptionKind.ALLOW_ONCE
            } else {
                option.kind == PermissionOptionKind.REJECT_ONCE
            }
        } ?: pending.options.firstOrNull { option ->
            if (accepted) {
                option.kind == PermissionOptionKind.ALLOW_ALWAYS
            } else {
                option.kind == PermissionOptionKind.REJECT_ALWAYS
            }
        }
        pending.response.complete(selected)
        return mapOf("ok" to true)
    }

    private fun cancelPendingPermissionRequests(threadId: String) {
        if (threadId.isBlank()) return
        pendingPermissions.entries.toList().forEach { (requestId, pending) ->
            if (pending.sessionId == threadId && pendingPermissions.remove(requestId, pending)) {
                serverRequestOwners.remove(requestId)
                pending.response.complete(null)
            }
        }
        pendingElicitations.entries.toList().forEach { (requestId, pending) ->
            if (pending.sessionId == threadId && pendingElicitations.remove(requestId, pending)) {
                serverRequestOwners.remove(requestId)
                pending.response.complete(
                    CreateElicitationResponse(action = ElicitationAction.Cancel)
                )
            }
        }
        pendingExtensionRequests.entries.toList().forEach { (requestId, pending) ->
            if (pending.sessionId == threadId &&
                pendingExtensionRequests.remove(requestId, pending)
            ) {
                serverRequestOwners.remove(requestId)
                pending.response.complete(
                    RawAcpExtensionReply(
                        error = buildJsonObject {
                            put("code", -32800)
                            put("message", "ACP request cancelled")
                        }
                    )
                )
            }
        }
    }

    private suspend fun awaitAgentExtensionRequest(
        id: JsonElement,
        method: String,
        params: JsonElement?
    ): RawAcpExtensionReply {
        val requestId = rawAcpRequestId(id)
        val response = CompletableDeferred<RawAcpExtensionReply>()
        val sessionId = (params as? JsonObject)
            ?.get("sessionId")
            ?.jsonPrimitive
            ?.contentOrNull
        val pending = PendingExtensionRequest(sessionId = sessionId, response = response)
        pendingExtensionRequests[requestId] = pending
        serverRequestOwners.register(requestId, activeAgentId(), sessionId)
        try {
            sessionId?.let { id ->
                turnLifecycle.activeTurnId(id)?.let { turnId ->
                    turnLifecycle.markWaitingForInput(id, turnId)
                }
            }
            val activeTurnId = sessionId?.let(turnLifecycle::activeTurnId)
            emitHostMessage(
                linkedMapOf(
                    "jsonrpc" to "2.0",
                    "id" to jsonToAny(id),
                    "method" to method,
                    "params" to jsonToAny(params ?: JsonNull),
                    "turnId" to activeTurnId,
                    "acpExtensionRequest" to true
                )
            )
            return response.await()
        } finally {
            pendingExtensionRequests.remove(requestId, pending)
            serverRequestOwners.remove(requestId)
            sessionId?.let { id ->
                turnLifecycle.activeTurnId(id)?.let { turnId ->
                    turnLifecycle.markRunning(id, turnId)
                }
            }
        }
    }

    private suspend fun publishAgentExtensionNotification(
        method: String,
        params: JsonElement?
    ) {
        emitHostMessage(
            linkedMapOf(
                "jsonrpc" to "2.0",
                "method" to method,
                "params" to jsonToAny(params ?: JsonNull),
                "acpExtensionNotification" to true
            )
        )
    }

    private fun elicitationResponse(args: Map<String, Any?>): CreateElicitationResponse {
        val response = args.mapValue("response").orEmpty()
        val action = (response["action"] ?: response["decision"])?.toString()
            ?.lowercase()
        if (action == "decline" || action == "reject" || action == "cancel") {
            return CreateElicitationResponse(
                action = if (action == "cancel") {
                    ElicitationAction.Cancel
                } else {
                    ElicitationAction.Decline
                }
            )
        }
        val rawContent = response.mapValue("content")
            ?: response.mapValue("answers")
            ?: emptyMap()
        val content = rawContent.mapValues { (_, value) ->
            when (value) {
                is List<*> -> ElicitationContentValue.StringArrayValue(
                    value.map { it?.toString().orEmpty() }
                )
                is Boolean -> ElicitationContentValue.BooleanValue(value)
                is Int -> ElicitationContentValue.IntegerValue(value.toLong())
                is Long -> ElicitationContentValue.IntegerValue(value)
                is Number -> ElicitationContentValue.NumberValue(value.toDouble())
                else -> ElicitationContentValue.StringValue(value?.toString().orEmpty())
            }
        }
        return CreateElicitationResponse(ElicitationAction.Accept(content))
    }

    private suspend fun ensureSessionForTurn(args: Map<String, Any?>): ClientSession =
        sessionResolutionMutex.withLock {
        val explicitThreadId = args.stringValue("threadId")
        val conversationId = args.longValue("conversationId")
        val explicitBinding = explicitThreadId
            ?.takeIf { it.isNotBlank() }
            ?.let { bindingRepository.getBindingByThreadId(it) }
        val canReuseExplicitThread = explicitThreadMatchesConversation(
            explicitThreadId = explicitThreadId,
            requestedConversationId = conversationId,
            boundConversationId = explicitBinding?.conversationId,
        )
        if (!explicitThreadId.isNullOrBlank() && canReuseExplicitThread) {
            return sessions[explicitThreadId] ?: run {
                val response = resumeThread(args)
                val resolvedThreadId = response.stringValue("threadId")
                    ?: response.stringValue("sessionId")
                    ?: explicitThreadId
                sessions[resolvedThreadId]
                    ?: throw IllegalStateException("Failed to restore ACP session.")
            }
        }
        val binding = if (conversationId != null) {
            bindingRepository.getBindingByConversationId(conversationId)
        } else {
            null
        }
        if (binding != null) {
            val bindingAgentId = profileStore.agentIdForSession(binding.threadId)
                ?: AcpAgentProfileStore.DEFAULT_AGENT_ID
            if (bindingAgentId == activeAgentId()) {
                return sessions[binding.threadId] ?: run {
                    val response = resumeThread(args + mapOf("threadId" to binding.threadId))
                    val resolvedThreadId = response.stringValue("threadId")
                        ?: response.stringValue("sessionId")
                        ?: binding.threadId
                    sessions[resolvedThreadId]
                        ?: throw IllegalStateException("Failed to restore ACP session.")
                }
            }
        }
        val created = startThread(args)
        return sessions[created["threadId"]?.toString()]
            ?: throw IllegalStateException("Failed to create ACP session.")
        }

    private fun promptMeta(args: Map<String, Any?>): JsonElement? {
        val conversationMode = args.stringValue("conversationMode")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val reasoningEffort = args.stringValue("effort")
            ?: args.stringValue("reasoningEffort")
        val terminalEnvironment = (args["terminalEnvironment"] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (rawKey, rawValue) ->
                val key = rawKey?.toString()?.trim().orEmpty()
                if (key.isEmpty() || rawValue == null) null else key to rawValue.toString()
            }
            ?.toMap()
            .orEmpty()
        if (
            conversationMode == null &&
            reasoningEffort == null &&
            terminalEnvironment.isEmpty()
        ) return null
        // This metadata is local ACP session metadata. External ACP agents may
        // ignore it; the built-in Xiaowan adapter uses it to apply the same
        // chat_only policy and run setting without introducing a second
        // transport.
        return buildJsonObject {
            conversationMode?.let { put("conversationMode", it) }
            reasoningEffort?.let { put("reasoningEffort", it) }
            if (terminalEnvironment.isNotEmpty()) {
                put("terminalEnvironment", buildJsonObject {
                    terminalEnvironment.forEach { (key, value) -> put(key, value) }
                })
            }
        }
    }

    private suspend fun ensureCatalogSession(args: Map<String, Any?>): ClientSession {
        catalogSessionId?.let(sessions::get)?.let { return it }
        sessions.values
            .firstOrNull { bindingRepository.getBindingByThreadId(it.sessionId.value) != null }
            ?.let { return it }
        return sessionMutex.withLock {
            catalogSessionId?.let(sessions::get)?.let {
                return@withLock it
            }
            val cwd = normalizeCwd(args.stringValue("cwd"))
            requireClient().newSession(
                sessionCreationParameters(cwd),
                operationsFactory()
            ).also { session ->
                registerSession(session, cwd)
                catalogSessionId = session.sessionId.value
            }
        }
    }

    private fun sessionCreationParameters(
        cwd: String,
        args: Map<String, Any?> = emptyMap()
    ): SessionCreationParameters {
        val profile = activeProfile ?: profileStore.selected()
        val supportsHttp = requireAgentInfo().capabilities.mcpCapabilities.http
        val requestedAdditionalDirectories = (args["additionalDirectories"] as? List<*>)
            .orEmpty()
            .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
        val supportsAdditionalDirectories = requireAgentInfo()
            .capabilities.sessionCapabilities.additionalDirectories != null
        if (requestedAdditionalDirectories.isNotEmpty() && !supportsAdditionalDirectories) {
            throw IllegalArgumentException(
                "${profile.name} ACP does not support additionalDirectories; " +
                    "use a path under ${AgentWorkspaceManager.SHELL_ROOT_PATH}."
            )
        }
        val mcpState = if (sessionMcpEnabled && supportsHttp) {
            McpServerManager.ensureRunning(appContext)
        } else {
            McpServerManager.currentState()
        }
        val declaredServers = if (sessionMcpEnabled && supportsHttp) {
            buildLocalAgentAcpMcpServers(
                harnessAdapter = AcpHarnessAdapters.forProfile(profile),
                supportsHttp = supportsHttp,
                state = mcpState
            ) + buildConfiguredRemoteAcpMcpServers()
        } else {
            emptyList()
        }
        return SessionCreationParameters(
            cwd = cwd,
            // Keep both surfaces: the OmniBot MCP server supplies device
            // capabilities, while configured remote servers supply the
            // user's existing MCP tools.  A Harness's own built-in tools are
            // still owned by that Harness and arrive as its standard ACP
            // tool_call updates; they must never be replaced by this list.
            mcpServers = declaredServers,
            additionalDirectories = requestedAdditionalDirectories.map(::normalizeCwd)
        )
    }

    private suspend fun applyRunConfig(
        session: ClientSession,
        args: Map<String, Any?>
    ) {
        if (hasAcpPermissionPolicy(args)) {
            sessionPermissionBehaviors[session.sessionId.value] =
                resolveAcpPermissionBehavior(args)
        } else {
            sessionPermissionBehaviors.putIfAbsent(
                session.sessionId.value,
                AcpPermissionBehavior.ALLOW_WITHOUT_PROMPT
            )
        }
        val requested = linkedMapOf<String, Any?>(
            "model" to args.stringValue("model"),
            "reasoning_effort" to (
                args.stringValue("effort") ?: args.stringValue("reasoningEffort")
                ),
            "collaboration_mode" to args.stringValue("collaborationMode"),
            "mode" to resolveAgentMode(args)
        )
        val options = sessionConfigOptions(session)
        requested.forEach { (requestedId, value) ->
            if (value == null) return@forEach
            val option = options.firstOrNull {
                it.id.value == requestedId ||
                    (
                        requestedId == "model" &&
                            it.category == SessionConfigOptionCategory.MODEL
                        ) ||
                    (
                        requestedId == "reasoning_effort" &&
                            it.category == SessionConfigOptionCategory.THOUGHT_LEVEL
                        ) ||
                    (
                        requestedId == "mode" &&
                            it.category == SessionConfigOptionCategory.MODE
                        )
            }
            when (option) {
                is SessionConfigOption.Select -> {
                    val requestedValue = value.toString()
                    val stringValue = if (requestedId == "mode") {
                        resolveAcpSessionModeId(
                            option.flatOptions().map { it.value.value },
                            requestedValue
                        )
                    } else {
                        requestedValue.takeIf {
                            option.flatOptions().any { it.value.value == requestedValue }
                        }
                    }
                    if (stringValue != null && option.currentValue.value != stringValue) {
                        session.setConfigOption(
                            option.id,
                            SessionConfigOptionValue.StringValue(stringValue)
                        )
                    }
                }
                is SessionConfigOption.BooleanOption -> {
                    val boolValue = value as? Boolean ?: return@forEach
                    if (option.currentValue != boolValue) {
                        session.setConfigOption(
                            option.id,
                            SessionConfigOptionValue.BoolValue(boolValue)
                        )
                    }
                }
                null -> {
                    if (requestedId == "model" && session.modelsSupported) {
                        val model = session.availableModels.firstOrNull {
                            it.modelId.value == value.toString()
                        }
                        if (model != null) {
                            session.setModel(model.modelId)
                        }
                    } else if (requestedId == "mode" && session.modesSupported) {
                        val modeId = resolveAcpSessionModeId(
                            session.availableModes.map { it.id.value },
                            value.toString()
                        )
                        if (modeId != null) {
                            session.setMode(SessionModeId(modeId))
                        }
                    }
                }
            }
        }
    }

    private fun resolveAgentMode(args: Map<String, Any?>): String? {
        val approval = args.stringValue("approvalPolicy")?.lowercase()
        val sandbox = args.mapValue("sandboxPolicy")
        val sandboxType = sandbox.stringValue("type")?.lowercase()
        return when {
            approval == "never" || sandboxType == "dangerfullaccess" ->
                "agent-full-access"
            sandboxType == "readonly" -> "read-only"
            approval != null || sandboxType != null -> "agent"
            else -> null
        }
    }

    private suspend fun buildPromptBlocks(
        args: Map<String, Any?>,
        turnId: String,
        threadId: String
    ): List<ContentBlock> {
        val capabilities = requireAgentInfo().capabilities.promptCapabilities
        val blocks = mutableListOf<ContentBlock>()
        pendingHandoffConversationIds[threadId]?.let { conversationId ->
            val handoff = buildHandoffContext(conversationId, args.stringValue("text"))
            if (pendingHandoffConversationIds.remove(threadId, conversationId) && handoff != null) {
                blocks += ContentBlock.Text(handoff)
            }
        }
        val text = args.stringValue("text").orEmpty()
        if (text.isNotEmpty()) {
            blocks += ContentBlock.Text(text)
        }
        val rawAttachments = args.listOfMaps("attachments")
        val attachments = AgentWorkspaceAttachmentSupport.prepareAttachmentsForRuntime(
            context = appContext,
            taskId = turnId,
            rawAttachments = rawAttachments
        )
        attachments.forEach { attachment ->
            val name = attachment.stringValue("name")
                ?: attachment.stringValue("fileName")
                ?: "attachment"
            val mimeType = attachment.stringValue("mimeType")
                ?: "application/octet-stream"
            val shellPath = attachment.stringValue("promptPath")
                ?: attachment.stringValue("workspacePath")
                ?: attachment.stringValue("path")
                ?: return@forEach
            val androidPath = attachment.stringValue("path")?.let(::File)
            val isImage = attachment["isImage"] == true ||
                mimeType.startsWith("image/", ignoreCase = true)
            val isAudio = attachment["isAudio"] == true ||
                mimeType.startsWith("audio/", ignoreCase = true)
            if (isImage && capabilities.image && androidPath?.isFile == true) {
                val encoded = Base64.encodeToString(
                    androidPath.readBytes(),
                    Base64.NO_WRAP
                )
                blocks += ContentBlock.Image(
                    data = encoded,
                    mimeType = mimeType,
                    uri = "file://$shellPath"
                )
            } else if (isAudio && capabilities.audio && androidPath?.isFile == true) {
                // ACP has a first-class audio content block. Sending a
                // ResourceLink here makes audio support depend on whether a
                // Harness happens to resolve the host's private file URI;
                // encode it exactly like image input when the Agent advertises
                // prompt/audio support, so every Harness sees the same block.
                val encoded = Base64.encodeToString(
                    androidPath.readBytes(),
                    Base64.NO_WRAP
                )
                blocks += ContentBlock.Audio(
                    data = encoded,
                    mimeType = mimeType
                )
            } else {
                blocks += ContentBlock.ResourceLink(
                    name = name,
                    uri = "file://$shellPath",
                    mimeType = mimeType,
                    size = (attachment["size"] as? Number)?.toLong()
                )
            }
        }
        if (blocks.isEmpty()) {
            blocks += ContentBlock.Text("")
        }
        return blocks
    }

    private fun operationsFactory() =
        com.agentclientprotocol.client.ClientOperationsFactory { sessionId, _ ->
            AcpClientOperations(sessionId.value)
        }

    private inner class AcpClientOperations(
        private val threadId: String
    ) : ClientSessionOperations {
        override suspend fun requestPermissions(
            toolCall: SessionUpdate.ToolCallUpdate,
            permissions: List<PermissionOption>,
            _meta: JsonElement?
        ): RequestPermissionResponse {
            if (
                sessionPermissionBehaviors[threadId] ==
                AcpPermissionBehavior.ALLOW_WITHOUT_PROMPT
            ) {
                val selected = permissions.firstOrNull {
                    it.kind == PermissionOptionKind.ALLOW_ALWAYS
                } ?: permissions.firstOrNull {
                    it.kind == PermissionOptionKind.ALLOW_ONCE
                }
                return RequestPermissionResponse(
                    outcome = selected?.let {
                        RequestPermissionOutcome.Selected(it.optionId)
                    } ?: RequestPermissionOutcome.Cancelled
                )
            }
            val requestId = UUID.randomUUID().toString()
            val pending = PendingPermissionRequest(
                sessionId = threadId,
                options = permissions,
                response = CompletableDeferred()
            )
            pendingPermissions[requestId] = pending
            serverRequestOwners.register(requestId, activeAgentId(), threadId)
            turnLifecycle.activeTurnId(threadId)?.let { turnId ->
                turnLifecycle.markWaitingForInput(threadId, turnId)
            }
            val activeTurnId = turnLifecycle.activeTurnId(threadId)
            emitHostMessage(
                linkedMapOf(
                    "jsonrpc" to "2.0",
                    "id" to requestId,
                    "method" to "session/request_permission",
                    // Host-envelope identity; ACP params stay standard.
                    "turnId" to activeTurnId,
                    "params" to mapOf(
                        "sessionId" to threadId,
                        // RequestPermissionRequest.toolCall is the standard
                        // ToolCallUpdate shape. Keep the explanation in
                        // official content blocks instead of the old
                        // host-only `detail` field.
                        "toolCall" to standardAcpPermissionToolCallPayload(
                            toolCallId = toolCall.toolCallId.value,
                            title = toolCall.title ?: "Permission required",
                            optionNames = permissions.map { it.name },
                        ),
                        "options" to permissions.map {
                            mapOf(
                                "optionId" to it.optionId.value,
                                "name" to it.name,
                                "kind" to it.kind.name.lowercase()
                            )
                        }
                    )
                )
            )
            val selected = pending.response.await()
            turnLifecycle.activeTurnId(threadId)?.let { turnId ->
                turnLifecycle.markRunning(threadId, turnId)
            }
            return RequestPermissionResponse(
                outcome = selected?.let {
                    RequestPermissionOutcome.Selected(it.optionId)
                } ?: RequestPermissionOutcome.Cancelled
            )
        }

        override suspend fun terminalCreate(
            command: String,
            args: List<String>,
            cwd: String?,
            env: List<EnvVariable>,
            outputByteLimit: ULong?,
            _meta: JsonElement?
        ): CreateTerminalResponse {
            require(command.isNotBlank()) { "ACP terminal command is required." }
            val terminalId = UUID.randomUUID().toString()
            val shellCwd = normalizeCwd(cwd ?: sessionCwds[threadId])
            val commandLine = buildString {
                append("cd ")
                append(shellQuoteAcp(shellCwd))
                append(" && exec ")
                append(shellQuoteAcp(command))
                args.forEach {
                    append(' ')
                    append(shellQuoteAcp(it))
                }
            }
            val process = TerminalManager.getInstance(appContext).startLongLivedProcess(
                command = commandLine,
                executorKey = "acp-terminal-$threadId-$terminalId",
                extraEnvironment = activeLaunchEnvironment +
                    env.associate { it.name to it.value },
                redirectErrorStream = true
            )
            val output = StringBuilder()
            val readerJob = scope.launch(Dispatchers.IO) {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            output.append(line).append('\n')
                            if (output.length > MAX_ACP_TERMINAL_BUFFER_CHARS) {
                                output.delete(
                                    0,
                                    output.length - MAX_ACP_TERMINAL_BUFFER_CHARS
                                )
                            }
                        }
                    }
                }
            }
            terminalProcesses[terminalId] = AcpTerminalProcess(
                process = process,
                output = output,
                readerJob = readerJob,
                outputByteLimit = outputByteLimit?.toLong()?.coerceAtMost(
                    MAX_ACP_TERMINAL_BUFFER_CHARS.toLong()
                )
            )
            return CreateTerminalResponse(terminalId)
        }

        override suspend fun terminalOutput(
            terminalId: String,
            _meta: JsonElement?
        ): TerminalOutputResponse {
            val terminal = terminalProcesses[terminalId]
                ?: throw IllegalArgumentException("Unknown ACP terminal: $terminalId")
            val finished = !terminal.process.isAlive
            if (finished) terminal.readerJob.join()
            val output = synchronized(terminal.output) { terminal.output.toString() }
            val limited = tailByBytes(output, terminal.outputByteLimit)
            return TerminalOutputResponse(
                output = limited.first,
                truncated = limited.second,
                exitStatus = if (finished) {
                    TerminalExitStatus(exitCode = terminal.process.exitValue().toUInt())
                } else {
                    null
                }
            )
        }

        override suspend fun terminalRelease(
            terminalId: String,
            _meta: JsonElement?
        ): ReleaseTerminalResponse {
            val terminal = terminalProcesses.remove(terminalId)
                ?: return ReleaseTerminalResponse()
            if (terminal.process.isAlive) terminal.process.destroy()
            terminal.readerJob.cancel()
            return ReleaseTerminalResponse()
        }

        override suspend fun terminalWaitForExit(
            terminalId: String,
            _meta: JsonElement?
        ): WaitForTerminalExitResponse {
            val terminal = terminalProcesses[terminalId]
                ?: throw IllegalArgumentException("Unknown ACP terminal: $terminalId")
            withContext(Dispatchers.IO) { terminal.process.waitFor() }
            terminal.readerJob.join()
            return WaitForTerminalExitResponse(
                exitCode = terminal.process.exitValue().toUInt()
            )
        }

        override suspend fun terminalKill(
            terminalId: String,
            _meta: JsonElement?
        ): KillTerminalCommandResponse {
            val terminal = terminalProcesses[terminalId]
                ?: return KillTerminalCommandResponse()
            if (terminal.process.isAlive) terminal.process.destroyForcibly()
            return KillTerminalCommandResponse()
        }

        override suspend fun createElicitation(
            request: CreateElicitationRequest
        ): CreateElicitationResponse = awaitElicitation(request, threadId)

        override suspend fun completeElicitation(
            notification: com.agentclientprotocol.model.CompleteElicitationNotification
        ) = Unit

        override suspend fun notify(
            notification: SessionUpdate,
            _meta: JsonElement?
        ) {
            handleSessionUpdate(
                threadId = threadId,
                turnId = turnLifecycle.activeTurnId(threadId),
                update = notification
            )
        }

        override suspend fun fsReadTextFile(
            path: String,
            line: UInt?,
            limit: UInt?,
            _meta: JsonElement?
        ): ReadTextFileResponse = withContext(Dispatchers.IO) {
            val file = resolveWorkspaceFile(path)
            require(file.isFile) { "File does not exist: $path" }
            val content = if (line == null && limit == null) {
                file.readText()
            } else {
                val start = ((line ?: 1u).toLong() - 1L).coerceAtLeast(0L).toInt()
                val count = limit?.toLong()?.coerceAtMost(MAX_FILE_LINES.toLong())?.toInt()
                    ?: MAX_FILE_LINES
                file.useLines { lines ->
                    lines.drop(start).take(count).joinToString("\n")
                }
            }
            ReadTextFileResponse(content)
        }

        override suspend fun fsWriteTextFile(
            path: String,
            content: String,
            _meta: JsonElement?
        ): WriteTextFileResponse = withContext(Dispatchers.IO) {
            val file = resolveWorkspaceFile(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            WriteTextFileResponse()
        }
    }

    /**
     * Bridges both session-scoped and request-scoped ACP elicitation into the
     * same host response path. The UI answers through
     * `respondToServerRequest`, so no Harness-specific dialog protocol is
     * needed here.
     */
    private suspend fun awaitElicitation(
        request: CreateElicitationRequest,
        sessionId: String?,
    ): CreateElicitationResponse {
        val requestId = UUID.randomUUID().toString()
        val response = CompletableDeferred<CreateElicitationResponse>()
        pendingElicitations[requestId] = PendingElicitationRequest(
            sessionId = sessionId,
            response = response,
        )
        serverRequestOwners.register(requestId, activeAgentId(), sessionId)
        val params = (jsonToAny(
            Json.encodeToJsonElement(
                CreateElicitationRequest.serializer(),
                request
            )
        ) as? Map<*, *>)
            ?.entries
            ?.associate { it.key.toString() to it.value }
            .orEmpty()
            .toMutableMap()
        if (sessionId != null) params["sessionId"] = sessionId
        try {
            sessionId?.let { id ->
                turnLifecycle.activeTurnId(id)?.let { turnId ->
                    turnLifecycle.markWaitingForInput(id, turnId)
                }
            }
            val activeTurnId = sessionId?.let(turnLifecycle::activeTurnId)
            emitHostMessage(
                linkedMapOf(
                    "jsonrpc" to "2.0",
                    "id" to requestId,
                    "method" to "elicitation/create",
                    // Host-envelope identity; ACP params stay standard.
                    "turnId" to activeTurnId,
                    "params" to params
                )
            )
            return response.await()
        } finally {
            pendingElicitations.remove(
                requestId,
                PendingElicitationRequest(sessionId = sessionId, response = response)
            )
            serverRequestOwners.remove(requestId)
            sessionId?.let { id ->
                turnLifecycle.activeTurnId(id)?.let { turnId ->
                    turnLifecycle.markRunning(id, turnId)
                }
            }
        }
    }

    private suspend fun handleSessionUpdate(
        threadId: String,
        turnId: String?,
        update: SessionUpdate
    ) {
        // `session/load` replays the whole conversation as session updates. We
        // restore history from Room instead, so the replay must never reach the
        // live stream — otherwise every replayed message becomes its own
        // pseudo turn in the UI and gets persisted back over the real history.
        if (threadId in replaySuppressedThreads) return
        val isReplay = threadId in replayingThreads

        // Never let a timeline update through without a turn id. Updates that
        // arrive outside an active prompt come via the notify callback with
        // none, and downstream they would fall back to a per-item id — which
        // spawns a duplicate agent avatar and "processing" header per item.
        // The SDK closes the prompt's update channel before it emits the
        // prompt response. A notification received after that point has no
        // active turn and must not be assigned to the previous turn.
        // ACP v1 session/update notifications are session-scoped and do not
        // require a wire-level turnId. The host-side prompt reservation is
        // the only safe attribution boundary while a prompt is active. Keep
        // this mapping for every Harness, not just one provider: requiring a
        // provider-specific turn field would reject valid standard ACP
        // traffic and make Xiaowan/DSH behave differently from the protocol.
        val implicitTurnId = turnLifecycle.activeTurnId(threadId)
        val resolvedTurnId = turnId?.takeIf { it.isNotBlank() }
            ?: implicitTurnId
            ?: if (isReplay && update.isTurnScoped()) replayTurnId(threadId) else null
        if (resolvedTurnId == null && update.isTurnScoped()) {
            Log.w(TAG, "Dropping turn-scoped ACP update with no resolvable turn: $update")
            return
        }

        resolvedTurnId?.let { resolvedId ->
            val turnIdentity = AcpTurnIdentity(threadId, resolvedId)
            turnTimings[turnIdentity]?.touch()
            markTurnTiming(threadId, resolvedId, "first_update")
            when (update) {
                is SessionUpdate.AgentThoughtChunk ->
                    markTurnTiming(threadId, resolvedId, "first_reasoning")
                is SessionUpdate.AgentMessageChunk -> {
                    update.messageId?.let { lastAssistantMessageIds[turnIdentity] = it }
                    markTurnTiming(threadId, resolvedId, "first_text")
                }
                is SessionUpdate.ToolCall,
                is SessionUpdate.ToolCallUpdate ->
                    markTurnTiming(threadId, resolvedId, "first_tool")
                else -> Unit
            }
        }

        // A session title is the one update with a side effect of its own.
        if (update is SessionUpdate.SessionInfoUpdate && !update.title.isNullOrBlank()) {
            bindingRepository.updateTitle(threadId, update.title)
        }

        // Forward the official ACP notification envelope. The host UI may
        // project it into cards, but the wire shape remains ACP and never
        // becomes an app-owned item/turn/acp event vocabulary.
        val notification = update.toAcpSessionNotification(threadId) ?: return
        emitAcpNotification(
            sessionId = notification.sessionId,
            update = notification.update,
            timingThreadId = threadId,
            timingTurnId = resolvedTurnId,
            replay = isReplay,
        )
    }

    /**
     * Sends an ACP notification without adding host-only identifiers to its
     * params. ACP clients must be able to consume this as a standard
     * `session/update` message without knowing anything about this app.
     */
    private suspend fun emitAcpNotification(
        sessionId: String,
        update: Map<String, Any?>,
        timingThreadId: String? = null,
        timingTurnId: String? = null,
        replay: Boolean = false,
    ) {
        val sequence = sessionEventSequences
            .computeIfAbsent(sessionId) { AtomicLong(0L) }
            .incrementAndGet()
        val eventId = "$sessionId:$sequence"
        emitHostMessage(linkedMapOf<String, Any?>(
                "method" to "session/update",
                // Host-envelope identity. The ACP update under params stays
                // official and consumable by any ACP client; these fields make
                // delivery to the shared UI idempotent.
                "eventId" to eventId,
                "sequence" to sequence,
                // These are host-envelope fields, not ACP params. They let
                // the shared reducer group a load replay without changing
                // the official session/update payload.
                "turnId" to timingTurnId,
                "replay" to replay.takeIf { it },
                "params" to mapOf(
                    "sessionId" to sessionId,
                    "update" to update
                )
            ).filterValues { it != null })
        if (timingThreadId != null && timingTurnId != null) {
            markTurnTiming(timingThreadId, timingTurnId, "event_delivered")
        }
    }

    /**
     * The manager intentionally exposes one shared ACP event stream to the
     * Flutter and WebChat consumers. Mark local events at this internal
     * boundary so a late event from an otherwise still-connected ACP process
     * cannot be attributed to whichever profile is selected now. The marker
     * is removed by AgentRuntimeManager before the public event is delivered.
     */
    private suspend fun emitHostMessage(message: Map<String, Any?>) {
        onMessage(
            LinkedHashMap(message).apply {
                put("_sourceAgentId", activeAgentId())
            }
        )
    }

    private fun registerSession(session: ClientSession, cwd: String) {
        sessions[session.sessionId.value] = session
        sessionCwds[session.sessionId.value] = cwd
    }

    private fun markTurnTiming(
        threadId: String,
        turnId: String,
        stage: String,
    ) {
        val elapsed = turnTimings[AcpTurnIdentity(threadId, turnId)]?.mark(stage) ?: return
        Log.i(
            TAG,
            "ACP timing agent=${activeAgentId()} stage=$stage " +
                "session=${compactId(threadId)} turn=${compactId(turnId)} " +
                "elapsedMs=$elapsed"
        )
    }

    private fun compactId(value: String): String =
        value.trim().takeLast(8).ifBlank { "none" }

    private suspend fun shouldSuppressAcpReplay(threadId: String): Boolean {
        val binding = bindingRepository.getBindingByThreadId(threadId) ?: return false
        val conversation = DatabaseHelper.getConversationById(binding.conversationId)
        val modes = listOf(
            conversation?.mode,
            AgentSessionBindingRepository.AGENT_MODE_STORAGE_VALUE,
            "normal",
            "codex",
            "acp",
        ).filterNotNull().map(String::trim).filter(String::isNotEmpty).distinct()
        return modes.any { mode ->
            DatabaseHelper.countAgentConversationThreadEntries(
                conversationId = binding.conversationId,
                conversationMode = mode,
            ) > 0
        }
    }

    private fun replayTurnId(threadId: String): String = "acp-replay-$threadId"

    private fun elapsedMillis(startedAtNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

    private fun sessionPayload(
        session: ClientSession,
        conversationId: Long?
    ): Map<String, Any?> = linkedMapOf(
        "threadId" to session.sessionId.value,
        "id" to session.sessionId.value,
        "conversationId" to conversationId,
        "cwd" to sessionCwds[session.sessionId.value],
        "agentId" to activeAgentId(),
        "agentName" to activeAgentName(),
        "active" to (turnLifecycle.activeTurnId(session.sessionId.value) != null),
        "activeTurnId" to turnLifecycle.activeTurnId(session.sessionId.value),
        "additionalDirectories" to session.parameters.additionalDirectories,
        "configOptions" to sessionConfigOptions(session).map(::acpConfigOptionPayload)
    )

    private fun sessionConfigOptions(session: ClientSession): List<SessionConfigOption> {
        return if (session.configOptionsSupported) {
            session.configOptions.value
        } else {
            emptyList()
        }
    }

    private fun resolveWorkspaceFile(path: String): File {
        val shellPath = when {
            path == AgentWorkspaceManager.SHELL_ROOT_PATH ||
                path.startsWith("${AgentWorkspaceManager.SHELL_ROOT_PATH}/") -> path
            path.startsWith("file://${AgentWorkspaceManager.SHELL_ROOT_PATH}") ->
                path.removePrefix("file://")
            path.startsWith("/") -> throw IllegalArgumentException(
                "ACP filesystem access is limited to /workspace."
            )
            else -> "${AgentWorkspaceManager.SHELL_ROOT_PATH}/${path.trimStart('/')}"
        }
        val file = workspaceManager.androidPathForShell(shellPath)?.canonicalFile
            ?: throw IllegalArgumentException("Invalid workspace path: $path")
        val root = AgentWorkspaceManager.rootDirectory(appContext).canonicalFile
        require(file.path == root.path || file.path.startsWith(root.path + File.separator)) {
            "ACP filesystem access is limited to /workspace."
        }
        return file
    }

    private suspend fun resolveThreadId(args: Map<String, Any?>): String {
        // `threadId` remains accepted for old clients, but every new session
        // caller uses the ACP name `sessionId`. Keep the conversion here at
        // the implementation boundary so the main path never needs to know
        // which historical helper is underneath it.
        args.stringValue("sessionId")?.let { return it }
        args.stringValue("threadId")?.let { return it }
        val conversationId = args.longValue("conversationId")
            ?: throw IllegalArgumentException("sessionId or conversationId is required")
        return bindingRepository.getBindingByConversationId(conversationId)?.threadId
            ?: throw IllegalArgumentException(
            "No ACP session is bound to conversation $conversationId"
        )
    }

    private fun normalizeCwd(value: String?): String {
        val cwd = value?.trim().orEmpty().ifBlank {
            AgentRuntimeDefaults.DEFAULT_WORKSPACE_CWD
        }
        require(
            cwd == AgentWorkspaceManager.SHELL_ROOT_PATH ||
                cwd.startsWith("${AgentWorkspaceManager.SHELL_ROOT_PATH}/")
        ) {
            "Local ACP cwd must stay inside ${AgentWorkspaceManager.SHELL_ROOT_PATH}."
        }
        return cwd
    }

    private fun requireClient(): Client = client
        ?: throw IllegalStateException("ACP agent is not connected.")

    private fun requireProtocol(): Protocol = protocol
        ?: throw IllegalStateException("ACP agent is not connected.")

    private fun requireAgentInfo(): AgentInfo = agentInfo
        ?: throw IllegalStateException("ACP agent is not initialized.")

    private data class PendingPermissionRequest(
        val sessionId: String,
        val options: List<PermissionOption>,
        val response: CompletableDeferred<PermissionOption?>
    )

    private data class PendingElicitationRequest(
        val sessionId: String?,
        val response: CompletableDeferred<CreateElicitationResponse>
    )

    private data class PendingExtensionRequest(
        val sessionId: String?,
        val response: CompletableDeferred<RawAcpExtensionReply>
    )

    private data class AcpTerminalProcess(
        val process: Process,
        val output: StringBuilder,
        val readerJob: Job,
        val outputByteLimit: Long?
    )

    companion object {
        private const val TAG = "LocalAcpRuntime"
        private const val INITIALIZE_TIMEOUT_MS = 90_000L
        private const val PROCESS_CLOSE_TIMEOUT_MS = 1_500L
        private const val CONNECT_CANCEL_TIMEOUT_MS = 2_000L
        private const val CANCEL_REQUEST_TIMEOUT_MS = 2_000L
        private const val CANCEL_JOIN_TIMEOUT_MS = 2_000L
        private const val STALL_CHECK_INTERVAL_MS = 5_000L
        private const val STALL_DEADLINE_MS = 120_000L
        private const val COMMAND_PROBE_TIMEOUT_MS = 20_000L
        private const val MAX_FILE_LINES = 20_000
        private const val MAX_ACP_TERMINAL_BUFFER_CHARS = 256_000

    }
}

internal fun shouldCreateSessionForConversationLoad(
    explicitSessionId: String?,
    explicitThreadId: String?,
    conversationId: Long?,
    hasConversationBinding: Boolean
): Boolean {
    val hasExplicitSession = !explicitSessionId.isNullOrBlank() ||
        !explicitThreadId.isNullOrBlank()
    return !hasExplicitSession &&
        conversationId != null &&
        conversationId > 0L &&
        !hasConversationBinding
}

internal enum class AcpPermissionBehavior {
    ASK_USER,
    ALLOW_WITHOUT_PROMPT
}

internal fun resolveAcpPermissionBehavior(
    args: Map<String, Any?>
): AcpPermissionBehavior {
    val approvalPolicy = args.stringValue("approvalPolicy")
        ?.lowercase()
        ?.replace("-", "")
        ?.replace("_", "")
    val permissionMode = args.stringValue("permissionMode")
        ?.lowercase()
        ?.replace("-", "")
        ?.replace("_", "")
    val sandboxType = args.mapValue("sandboxPolicy")
        .stringValue("type")
        ?.lowercase()
        ?.replace("-", "")
        ?.replace("_", "")
    return if (
        approvalPolicy == "never" ||
        sandboxType == "dangerfullaccess" ||
        permissionMode in setOf(
            "fullaccess",
            "agentfullaccess",
            "dangerfullaccess",
            "bypass",
            "bypasspermissions",
            "unrestricted"
        ) ||
        // No policy means the app's canonical default: unrestricted local
        // execution. The approval gate is opt-in via on-request/readonly.
        (approvalPolicy == null && sandboxType == null && permissionMode == null)
    ) {
        AcpPermissionBehavior.ALLOW_WITHOUT_PROMPT
    } else {
        AcpPermissionBehavior.ASK_USER
    }
}

private fun hasAcpPermissionPolicy(args: Map<String, Any?>): Boolean {
    return args.containsKey("approvalPolicy") ||
        args.containsKey("sandboxPolicy") ||
        args.containsKey("permissionMode")
}

/**
 * Harnesses use different ACP mode identifiers for the same user-facing
 * permission policy. Resolve our canonical IDs against the identifiers a
 * session actually advertises, while still preferring an exact match.
 */
internal fun resolveAcpSessionModeId(
    availableModeIds: Collection<String>,
    requestedModeId: String
): String? {
    val requested = normalizeAcpModeId(requestedModeId)
    availableModeIds.firstOrNull { normalizeAcpModeId(it) == requested }?.let { return it }
    val aliases = when (requested) {
        "agentfullaccess", "dangerfullaccess", "fullaccess", "bypass", "bypasspermissions" ->
            setOf(
                "agentfullaccess",
                "dangerfullaccess",
                "fullaccess",
                "bypass",
                "bypasspermissions",
                "bypasspermission",
                "unrestricted"
            )
        "readonly", "plan" -> setOf("readonly", "plan", "read")
        "agent", "default", "workspacewrite", "acceptedits", "autoapprove" ->
            setOf("agent", "default", "workspacewrite", "acceptedits", "autoapprove")
        else -> emptySet()
    }
    return availableModeIds.firstOrNull { normalizeAcpModeId(it) in aliases }
}

internal fun isAcpFullAccessMode(modeId: String): Boolean {
    return normalizeAcpModeId(modeId) in setOf(
        "agentfullaccess",
        "dangerfullaccess",
        "fullaccess",
        "bypass",
        "bypasspermissions",
        "bypasspermission",
        "unrestricted"
    )
}

private fun normalizeAcpModeId(value: String): String {
    return value.trim().lowercase()
        .replace("-", "")
        .replace("_", "")
        .replace(" ", "")
}

internal interface AcpRuntimeConnection {
    val exitSignal: CompletableDeferred<Int?>
    val isRunning: Boolean
    fun createTransport(parentScope: CoroutineScope): com.agentclientprotocol.transport.Transport
    suspend fun start()
    fun diagnosticSummary(): String
    fun exitDescription(exitCode: Int?): String
    /** Sends a raw client->agent JSON-RPC notification for an ACP extension. */
    suspend fun sendRawMessage(line: String) {
        throw UnsupportedOperationException("Raw ACP notifications are unavailable")
    }
    suspend fun close()
}

/**
 * Raw JSON-RPC extension traffic that the ACP JVM SDK cannot type-dispatch.
 * Extension method names use the reserved underscore namespace; standard ACP
 * messages continue through Protocol/StdioTransport unchanged.
 */
internal data class RawAcpExtensionMessage(
    val id: JsonElement?,
    val method: String,
    val params: JsonElement?,
    val isRequest: Boolean
)

internal data class RawAcpExtensionReply(
    val result: JsonElement? = null,
    val error: JsonElement? = null
)

internal fun parseAcpExtensionLine(line: String): RawAcpExtensionMessage? {
    val payload = runCatching { Json.parseToJsonElement(line) }
        .getOrNull()
        as? JsonObject
        ?: return null
    val method = payload["method"]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.startsWith("_") && it.length > 1 }
        ?: return null
    val id = payload["id"]
    return RawAcpExtensionMessage(
        id = id,
        method = method,
        params = payload["params"],
        isRequest = id != null
    )
}

internal fun rawAcpRequestId(id: JsonElement): String =
    (id as? JsonPrimitive)?.contentOrNull ?: id.toString()

internal fun encodeAcpExtensionResponse(
    id: JsonElement,
    reply: RawAcpExtensionReply
): String = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    if (reply.error != null) {
        put("error", reply.error)
    } else {
        put("result", reply.result ?: buildJsonObject {})
    }
}.toString()

private class AcpProcessConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val profile: AcpAgentProfile,
    private val environment: Map<String, String>,
    private val onExtensionRequest: suspend (
        id: JsonElement,
        method: String,
        params: JsonElement?
    ) -> RawAcpExtensionReply,
    private val onExtensionNotification: suspend (
        method: String,
        params: JsonElement?
    ) -> Unit
) : AcpRuntimeConnection {
    private val inputChannel = Channel<String>(Channel.UNLIMITED)
    private val writeMutex = Mutex()
    private val stderrLock = Any()
    private val stderrTail = ArrayDeque<String>()
    private var process: Process? = null
    private var stderrJob: Job? = null
    private var waitJob: Job? = null
    private var readerJob: Job? = null
    private var writer: OutputStreamWriter? = null

    @Volatile
    private var closing = false

    private val input: Flow<String> = inputChannel.receiveAsFlow()
    override val exitSignal = CompletableDeferred<Int?>()
    override val isRunning: Boolean
        get() = process?.isAlive == true

    override fun createTransport(parentScope: CoroutineScope): com.agentclientprotocol.transport.Transport {
        return StdioTransport(
            parentScope = parentScope,
            ioDispatcher = Dispatchers.IO,
            input = input,
            output = ::writeLine,
            name = "omnibot-acp-${profile.id}"
        )
    }

    override suspend fun sendRawMessage(line: String) {
        writeLine(line)
    }

    override suspend fun start() {
        if (isRunning) return
        closing = false
        val command = buildString {
            // ACP session/new is created with /workspace as its cwd.  The
            // embedded terminal otherwise starts long-lived processes in
            // /root, which makes official filesystem/skill providers resolve
            // a different process.cwd() than the ACP session.  Keep every
            // managed ACP runtime on the same official workspace root.
            append("cd ")
            append(shellQuoteAcp(AgentWorkspaceManager.SHELL_ROOT_PATH))
            append(" && ")
            append(MANAGED_NPM_PATH_PREFIX)
            append(' ')
            append("exec ")
            append(shellQuoteAcp(profile.command))
            profile.arguments.forEach {
                append(' ')
                append(shellQuoteAcp(it))
            }
        }
        Log.i("LocalAcpRuntime", "Launching ACP process profile=${profile.id} command=$command")
        val started = TerminalManager.getInstance(context).startLongLivedAlpineProcess(
            command = command,
            executorKey = "acp-agent-${profile.id}",
            redirectErrorStream = false,
            extraEnvironment = environment
        )
        process = started
        val harnessAdapter = AcpHarnessAdapters.forProfile(profile)
        Log.i(
            "LocalAcpRuntime",
            "Launched ACP process profile=${profile.id} alive=${started.isAlive}"
        )
        writer = OutputStreamWriter(started.outputStream, StandardCharsets.UTF_8)
        readerJob = scope.launch {
            try {
                lineFlow(started).collect {
                    val normalized = harnessAdapter.normalizeStdioLine(it)
                    val extension = parseAcpExtensionLine(normalized)
                    if (extension == null) {
                        inputChannel.send(normalized)
                    } else if (extension.isRequest && extension.id != null) {
                        // Do not block stdout consumption while the user is
                        // deciding how to answer an extension request. Other
                        // ACP notifications must remain ordered and visible.
                        launch {
                            val reply = runCatching {
                                onExtensionRequest(
                                    extension.id,
                                    extension.method,
                                    extension.params
                                )
                            }.getOrElse { error ->
                                RawAcpExtensionReply(
                                    error = buildJsonObject {
                                        put("code", -32000)
                                        put(
                                            "message",
                                            error.message ?: "ACP extension request failed"
                                        )
                                    }
                                )
                            }
                            writeLine(
                                encodeAcpExtensionResponse(extension.id, reply)
                            )
                        }
                    } else {
                        onExtensionNotification(
                            extension.method,
                            extension.params
                        )
                    }
                }
            } catch (error: IOException) {
                handleStreamReadFailure(
                    streamName = "stdout",
                    error = error,
                    started = started,
                    terminateProcess = true
                )
            }
        }
        stderrJob = scope.launch(Dispatchers.IO) {
            try {
                started.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            appendDiagnostic(line)
                            Log.d("LocalAcpRuntime", "[${profile.name}] $line")
                        }
                    }
                }
            } catch (error: IOException) {
                handleStreamReadFailure(
                    streamName = "stderr",
                    error = error,
                    started = started,
                    terminateProcess = false
                )
            }
        }
        waitJob = scope.launch(Dispatchers.IO) {
            val exitCode = runCatching { started.waitFor() }.getOrNull()
            exitSignal.complete(exitCode)
            if (process === started) {
                process = null
                inputChannel.close(
                    IllegalStateException(
                        "ACP agent ${profile.name} exited with code $exitCode."
                    )
                )
            }
        }
    }

    private fun appendDiagnostic(message: String) {
        synchronized(stderrLock) {
            stderrTail.addLast(message)
            while (
                stderrTail.size > MAX_STDERR_LINES ||
                stderrTail.sumOf(String::length) > MAX_STDERR_CHARS
            ) {
                stderrTail.removeFirstOrNull()
            }
        }
    }

    private fun handleStreamReadFailure(
        streamName: String,
        error: IOException,
        started: Process,
        terminateProcess: Boolean
    ) {
        if (
            shouldSuppressAcpStreamReadFailure(
                closing = closing,
                currentProcess = process === started,
                processAlive = started.isAlive
            )
        ) {
            return
        }
        val detail = "$streamName reader failed: " +
            (error.message ?: error.javaClass.simpleName)
        appendDiagnostic(detail)
        Log.w("LocalAcpRuntime", "[${profile.name}] $detail", error)
        if (terminateProcess) {
            exitSignal.complete(null)
            runCatching { started.destroy() }
        }
    }

    override fun diagnosticSummary(): String {
        val stderr = synchronized(stderrLock) {
            stderrTail.joinToString("\n").trim()
        }
        return if (stderr.isBlank()) {
            ""
        } else {
            "Adapter stderr: ${stderr.takeLast(MAX_STDERR_CHARS)}"
        }
    }

    override fun exitDescription(exitCode: Int?): String {
        val summary = diagnosticSummary()
        return buildString {
            append("ACP process exited before initialize completed")
            if (exitCode != null) {
                append(" with code ")
                append(exitCode)
            }
            if (summary.isNotBlank()) {
                append(". ")
                append(summary)
            }
        }
    }

    private suspend fun writeLine(line: String) {
        writeMutex.withLock {
            val output = writer
                ?: throw IllegalStateException("ACP agent stdin is closed.")
            withContext(Dispatchers.IO) {
                output.write(line)
                output.write("\n")
                output.flush()
            }
        }
    }

    override suspend fun close() {
        closing = true
        val current = process
        val processTree = collectManagedProcessIds(profile.command)
        Log.i(
            "LocalAcpRuntime",
            "Closing ACP process profile=${profile.id} alive=${current?.isAlive == true} " +
                "descendants=${processTree.size}"
        )
        process = null
        readerJob?.cancel()
        stderrJob?.cancel()
        waitJob?.cancel()
        // Destroy the process before closing its pipes.  A proot-backed
        // Process stream can block while its child is still alive; doing the
        // pipe cleanup first used to hold the runtime mutex indefinitely and
        // prevented every subsequent ACP agent from starting.
        killProcessTree(processTree)
        runCatching { current?.destroy() }
        Log.i(
            "LocalAcpRuntime",
            "Requested ACP process shutdown profile=${profile.id}"
        )
        if (current != null) {
            val exited = withContext(Dispatchers.IO) {
                runCatching { current.waitFor(500, TimeUnit.MILLISECONDS) }
                    .getOrDefault(false)
            }
            if (!exited) {
                runCatching { current.destroyForcibly() }
                withContext(Dispatchers.IO) {
                    runCatching { current.waitFor(500, TimeUnit.MILLISECONDS) }
                }
            }
        }
        withTimeoutOrNull(250L) {
            withContext(Dispatchers.IO) {
                runCatching { writer?.close() }
                runCatching { current?.inputStream?.close() }
                runCatching { current?.errorStream?.close() }
            }
        }
        writer = null
        Log.i(
            "LocalAcpRuntime",
            "Closed ACP process profile=${profile.id} alive=${current?.isAlive == true}"
        )
        withTimeoutOrNull(500L) {
            readerJob?.cancelAndJoin()
            stderrJob?.cancelAndJoin()
            waitJob?.cancelAndJoin()
        }
        // PRoot launches the managed Node process as a same-UID child. On
        // Android, destroying the Java Process wrapper can reap only the
        // linker/proot parent and leave Node alive. Re-scan once after the
        // parent shutdown and terminate any descendants that survived it.
        killProcessTree(collectManagedProcessIds(profile.command))
        readerJob = null
        stderrJob = null
        waitJob = null
        inputChannel.close()
    }

    private fun collectManagedProcessIds(commandToken: String): List<Int> {
        if (commandToken.isBlank()) return emptyList()
        val parentByPid = mutableMapOf<Int, Int>()
        val commandByPid = mutableMapOf<Int, String>()
        File("/proc").listFiles().orEmpty().forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            val stat = runCatching {
                File(entry, "stat").readText(StandardCharsets.UTF_8)
            }.getOrNull() ?: return@forEach
            val match = PROCESS_STAT_PATTERN.find(stat) ?: return@forEach
            val parentPid = match.groupValues[1].toIntOrNull() ?: return@forEach
            val command = runCatching {
                String(File(entry, "cmdline").readBytes(), StandardCharsets.UTF_8)
                    .replace('\u0000', ' ')
            }.getOrNull().orEmpty()
            parentByPid[pid] = parentPid
            commandByPid[pid] = command
        }
        val roots = commandByPid
            .asSequence()
            .filter { (_, command) -> command.contains(commandToken) }
            .map { (pid, _) -> pid }
            .toSet()
        val managed = LinkedHashSet<Int>()
        var frontier = roots
        while (frontier.isNotEmpty()) {
            managed.addAll(frontier)
            frontier = parentByPid
                .asSequence()
                .filter { (pid, parentPid) -> parentPid in frontier && pid !in managed }
                .map { (pid, _) -> pid }
                .toSet()
        }
        return managed.toList()
    }

    private fun killProcessTree(processIds: List<Int>) {
        processIds
            .asReversed()
            .forEach { pid ->
                runCatching { android.os.Process.killProcess(pid) }
            }
    }

    private fun lineFlow(process: Process): Flow<String> = flow {
        process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) {
                    emit(line)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private val PROCESS_STAT_PATTERN = Regex("^\\d+ \\([^)]*\\) \\S+ (\\d+)")
        private const val MAX_STDERR_LINES = 60
        private const val MAX_STDERR_CHARS = 6_000
    }
}

private fun capabilitiesPayload(info: AgentInfo?): Map<String, Any?> {
    val capabilities = info?.capabilities
    val steering = info?._meta
        ?.runCatching {
            jsonObject["steering"]?.jsonObject?.get("supported")?.jsonPrimitive?.content
        }
        ?.getOrNull()
        ?.toBooleanStrictOrNull() == true
    return linkedMapOf(
        "loadSession" to (capabilities?.loadSession == true),
        "prompt" to linkedMapOf(
            "audio" to (capabilities?.promptCapabilities?.audio == true),
            "image" to (capabilities?.promptCapabilities?.image == true),
            "embeddedContext" to (
                capabilities?.promptCapabilities?.embeddedContext == true
                )
        ),
        "mcp" to linkedMapOf(
            "http" to (capabilities?.mcpCapabilities?.http == true),
            "sse" to (capabilities?.mcpCapabilities?.sse == true)
        ),
        "session" to linkedMapOf(
            "list" to (capabilities?.sessionCapabilities?.list != null),
            "fork" to (capabilities?.sessionCapabilities?.fork != null),
            "resume" to (capabilities?.sessionCapabilities?.resume != null),
            "delete" to (capabilities?.sessionCapabilities?.delete != null),
            "close" to (capabilities?.sessionCapabilities?.close != null),
            "additionalDirectories" to (
                capabilities?.sessionCapabilities?.additionalDirectories != null
                )
        ),
        "auth" to linkedMapOf(
            "methods" to info?.authMethods?.map {
                mapOf("id" to it.id.value, "name" to it.name)
            }.orEmpty(),
            "logout" to (capabilities?.auth?.logout != null),
            "providers" to (capabilities?.providers != null)
        ),
        "client" to linkedMapOf(
            "fs" to linkedMapOf("readTextFile" to true, "writeTextFile" to true),
            "terminal" to true,
            "plan" to true,
            "elicitation" to linkedMapOf("form" to true, "url" to true)
        ),
        "steering" to steering
    )
}

/** Optional ACP client metadata understood by DeepSeek Harness 0.4.x. */
internal val ACP_CLIENT_CAPABILITY_META: JsonObject = buildJsonObject {
    put("terminal_output", true)
    put("subagent-transcript", true)
    put("dsh", buildJsonObject {
        put("cordis", buildJsonObject {
            put("protocol", 0)
        })
    })
}

private const val MANAGED_NPM_PATH_PREFIX =
    "PATH=\"/root/.npm-global/bin:\$PATH\"; export PATH;"

internal fun shouldSuppressAcpStreamReadFailure(
    closing: Boolean,
    currentProcess: Boolean,
    processAlive: Boolean
): Boolean = closing || !currentProcess || !processAlive

/**
 * Collapses however a prompt ended into the single status string the UI reads.
 *
 * `stopReason` is the ACP-reported reason and wins when present. Cancellation
 * beats a failure because a cancelled coroutine usually also surfaces an
 * exception. An agent that ends its prompt flow without any response at all is
 * treated as a normal end-of-turn: the alternative is leaving the turn running
 * forever, which is strictly worse than mislabelling a rare silent failure.
 */
private fun SessionConfigOption.Select.flatOptions() = when (val value = options) {
    is SessionConfigSelectOptions.Flat -> value.options
    is SessionConfigSelectOptions.Grouped -> value.groups.flatMap { it.options }
}

private fun SessionConfigOption.currentValuePayload(): Any? = when (this) {
    is SessionConfigOption.Select -> currentValue.value
    is SessionConfigOption.BooleanOption -> currentValue
}

internal fun resolveTurnTerminalStatus(
    stopReason: String?,
    cancelled: Boolean,
    error: Throwable?
): String {
    stopReason?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.lowercase() }
    if (cancelled) return "cancelled"
    if (error != null) return "error"
    return "end_turn"
}


private fun Map<String, Any?>.mapValue(key: String): Map<String, Any?> {
    val raw = this[key] as? Map<*, *> ?: return emptyMap()
    return raw.entries.associate { (mapKey, value) -> mapKey.toString() to value }
}

private fun anyToAcpJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Map<*, *> -> buildJsonObject {
        value.entries.forEach { (key, item) ->
            key?.toString()?.let { put(it, anyToAcpJson(item)) }
        }
    }
    is Iterable<*> -> buildJsonArray { value.forEach { add(anyToAcpJson(it)) } }
    else -> JsonPrimitive(value.toString())
}

private fun jsonToAny(value: JsonElement): Any? = when (value) {
    is JsonObject -> value.entries.associate { (key, item) -> key to jsonToAny(item) }
    is JsonArray -> value.map(::jsonToAny)
    is JsonPrimitive -> when {
        value.isString -> value.content
        value.content == "true" -> true
        value.content == "false" -> false
        value.content.toLongOrNull() != null -> value.content.toLong()
        value.content.toDoubleOrNull() != null -> value.content.toDouble()
        else -> value.contentOrNull
    }
    else -> null
}

private fun tailByBytes(value: String, limit: Long?): Pair<String, Boolean> {
    if (limit == null || limit <= 0L) return value to false
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size <= limit) return value to false
    val start = bytes.size - limit.toInt().coerceAtMost(bytes.size)
    return String(bytes.copyOfRange(start, bytes.size), StandardCharsets.UTF_8) to true
}

private fun Map<String, Any?>.stringValue(key: String): String? =
    this[key]?.toString()?.trim()?.takeIf(String::isNotEmpty)

private fun Map<String, Any?>.longValue(key: String): Long? = when (val value = this[key]) {
    is Number -> value.toLong()
    else -> value?.toString()?.toLongOrNull()
}

private fun Map<String, Any?>.stringList(key: String): List<String> =
    (this[key] as? List<*>)?.mapNotNull {
        it?.toString()?.trim()?.takeIf(String::isNotEmpty)
    }.orEmpty()

private fun Map<String, Any?>.stringMap(key: String): Map<String, String> =
    (this[key] as? Map<*, *>)?.entries?.mapNotNull { (mapKey, value) ->
        val keyText = mapKey?.toString()?.trim().orEmpty()
        if (keyText.isEmpty()) null else keyText to value?.toString().orEmpty()
    }?.toMap().orEmpty()

private fun Map<String, Any?>.listOfMaps(key: String): List<Map<String, Any?>> =
    (this[key] as? List<*>)?.mapNotNull { item ->
        (item as? Map<*, *>)?.entries?.associate { (mapKey, value) ->
            mapKey.toString() to value
        }
    }.orEmpty()

internal fun managedAgentHealthFromProbe(
    enabled: Boolean,
    launchInstalled: Boolean,
    healthCheckPassed: Boolean?,
    previous: AcpAgentHealth,
): AcpAgentHealth {
    val checkedAt = System.currentTimeMillis()
    // A package probe answers “the files and launcher exist”; it does not
    // answer “the last ACP initialize succeeded”. Keep a real handshake
    // failure visible until explicit preparation or a later successful
    // initialize clears it. Otherwise a shallow probe can turn a broken
    // plugin graph back into a misleading `online` state.
    val previousInitializeFailure =
        previous.error?.let(::isAcpInitializeFailure) == true &&
            previous.status != AcpAgentHealth.STATUS_ONLINE
    return when {
        !enabled -> previous.copy(
            status = AcpAgentHealth.STATUS_OFFLINE,
            installed = launchInstalled,
            error = "Agent is disabled.",
            checkedAt = checkedAt,
        )
        !launchInstalled -> AcpAgentHealth(
            status = AcpAgentHealth.STATUS_MISSING,
            installed = false,
            error = "Agent command is not installed.",
            checkedAt = checkedAt,
        )
        healthCheckPassed == false -> if (previousInitializeFailure) {
            previous.copy(
                status = AcpAgentHealth.STATUS_OFFLINE,
                installed = true,
                checkedAt = checkedAt,
            )
        } else {
            AcpAgentHealth(
                status = AcpAgentHealth.STATUS_OFFLINE,
                installed = true,
                error = "Harness 健康检查失败，请点击“安装官方 Harness”重新初始化。",
                checkedAt = checkedAt,
            )
        }
        previousInitializeFailure -> previous.copy(
            status = AcpAgentHealth.STATUS_OFFLINE,
            installed = true,
            checkedAt = checkedAt,
        )
        else -> AcpAgentHealth(
            status = AcpAgentHealth.STATUS_ONLINE,
            installed = true,
            error = null,
            checkedAt = checkedAt,
            preparationRevision = previous.preparationRevision,
        )
    }
}

/**
 * Missing installation is a transport/command fact, not an arbitrary phrase
 * in adapter stderr. Android proot and Node dependency errors commonly
 * contain “No such file”, while the executable itself is present; classifying
 * those as missing makes the next availability probe erase the real cause.
 */
internal fun isMissingAcpAgentFailure(message: String): Boolean {
    val normalized = message.lowercase()
    return "acp launch command not found" in normalized ||
        "agent launch command not found" in normalized ||
        "command not found" in normalized ||
        "code 127" in normalized
}

internal fun isAcpInitializeFailure(message: String): Boolean =
    message.trim().startsWith("Failed to initialize ACP agent", ignoreCase = true)

/**
 * Preparation has already completed the adapter install, launch-command
 * check, and vendor health check.  Persist that result as reusable readiness;
 * the ACP process itself is still disconnected until the user switches to
 * this Harness and performs the normal initialize handshake. `online` here
 * means the adapter is launch-ready, not that a process is currently alive.
 */
internal fun managedAgentPreparationHealth(
    checkedAt: Long = System.currentTimeMillis(),
    preparationRevision: String? = null,
): AcpAgentHealth = AcpAgentHealth(
    status = AcpAgentHealth.STATUS_ONLINE,
    installed = true,
    checkedAt = checkedAt,
    preparationRevision = preparationRevision,
)

private fun shellQuoteAcp(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

internal object AgentHandoffContext {
    private const val MAX_HANDOFF_CHARS = 96_000

    fun format(
        conversationId: Long,
        messages: List<ChatCompletionMessage>,
        currentPrompt: String? = null
    ): String? {
        val handoffMessages = messages.toMutableList().apply {
            val normalizedPrompt = currentPrompt?.trim().orEmpty()
            if (normalizedPrompt.isNotEmpty()) {
                val currentMessageIndex = indexOfLast { message ->
                    message.role.equals("user", ignoreCase = true) &&
                        renderContent(message).trim() == normalizedPrompt
                }
                if (currentMessageIndex >= 0) {
                    removeAt(currentMessageIndex)
                }
            }
        }
        if (handoffMessages.isEmpty()) return null
        val rendered = buildString {
            appendLine("[OmniBot handoff]")
            appendLine("Continue the existing OmniBot conversation.")
            appendLine("Conversation ID: $conversationId")
            appendLine("The following is persisted conversation context. Treat it as prior context, not a new user request.")
            appendLine()
            handoffMessages.forEach { message ->
                append(message.role.trim().ifEmpty { "message" })
                append(": ")
                appendLine(renderContent(message))
            }
        }.trim()
        if (rendered.length <= MAX_HANDOFF_CHARS) return rendered
        return buildString {
            appendLine("[OmniBot handoff]")
            appendLine("Older context was omitted from this handoff and remains available in local history; continue from the retained tail.")
            appendLine("Conversation ID: $conversationId")
            appendLine()
            append(rendered.takeLast(MAX_HANDOFF_CHARS))
        }
    }

    private fun renderContent(message: ChatCompletionMessage): String {
        val parts = buildList {
            message.content?.let { add(renderJson(it)) }
            message.reasoningContent?.trim()?.takeIf { it.isNotEmpty() }?.let {
                add("reasoning=$it")
            }
            message.toolCalls?.takeIf { it.isNotEmpty() }?.let {
                add("tool_calls=${it.joinToString()}")
            }
        }
        return parts.joinToString(" ").ifBlank { "(no text content)" }
    }

    private fun renderJson(element: JsonElement): String {
        return if (element is JsonPrimitive) {
            element.contentOrNull ?: element.toString()
        } else {
            element.toString()
        }
    }
}
