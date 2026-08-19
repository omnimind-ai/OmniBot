@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import android.util.Base64
import android.util.Log
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.agent.AgentWorkspaceAttachmentSupport
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.bot.mcp.McpServerManager
import com.ai.assistance.operit.terminal.TerminalManager
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ClientCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.FileSystemCapability
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.PermissionOption
import com.agentclientprotocol.model.PermissionOptionKind
import com.agentclientprotocol.model.PlanCapabilities
import com.agentclientprotocol.model.PlanVariant
import com.agentclientprotocol.model.ReadTextFileResponse
import com.agentclientprotocol.model.RequestPermissionOutcome
import com.agentclientprotocol.model.RequestPermissionResponse
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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
import kotlin.coroutines.coroutineContext

internal class LocalAcpRuntime(
    context: Context,
    private val scope: CoroutineScope,
    private val bindingRepository: AgentSessionBindingRepository,
    private val profileStore: AcpAgentProfileStore,
    private val prepareLaunchEnvironment: suspend (AcpAgentProfile) -> Map<String, String>,
    private val buildHandoffContext: suspend (Long, String?) -> String?,
    private val scheduleToolBridge: AgentScheduleToolBridge,
    private val onMessage: suspend (Map<String, Any?>) -> Unit
) {
    private val appContext = context.applicationContext
    private val connectMutex = Mutex()
    private val sessionMutex = Mutex()
    // Keep request-id lookup and active-turn reservation in one critical
    // section. A transport retry can arrive before the first start call has
    // populated promptRequestTurns; without this, the retry is misclassified
    // as a second active turn.
    private val turnStartMutex = Mutex()
    private val workspaceManager = AgentWorkspaceManager(appContext)
    private val sessions = ConcurrentHashMap<String, ClientSession>()
    private val sessionCwds = ConcurrentHashMap<String, String>()
    private val activeTurnIds = ConcurrentHashMap<String, String>()
    // A prompt request may be delivered twice by a client after a transport
    // timeout. Keep the request-to-turn mapping so the retry returns the
    // original turn instead of executing the user's tools again.
    private val promptRequestTurns = ConcurrentHashMap<String, String>()

    /**
     * Turn ids that have already emitted their terminal event. [finishTurn] is
     * reachable from the prompt job, from an explicit interrupt, and from
     * teardown, so it has to be idempotent.
     */
    private val finishedTurns = ConcurrentHashMap.newKeySet<String>()

    /**
     * Wall-clock time of the last session update seen for an in-flight turn.
     *
     * ACP closes a turn with the `session/prompt` *response* (carrying the stop
     * reason), and the SDK's prompt flow only completes when that response
     * arrives. At least one adapter (codex-acp / MiMo Code) streams its full
     * answer as session updates and then never sends the response, leaving the
     * flow — and therefore the prompt job's `finally` — suspended forever. The
     * turn stays "running" in the UI indefinitely.
     *
     * The stall watchdog uses this timestamp to detect that situation: a turn
     * that has produced nothing for [STALL_DEADLINE_MS] is finalized
     * synthetically. Adapters that send the response complete normally first,
     * so this only ever fires for a genuinely stalled flow.
     */
    private val lastTurnActivityAt = ConcurrentHashMap<String, Long>()

    /**
     * Threads currently inside a `session/load` call. Per the ACP spec the
     * agent replays the entire conversation as `session/update` notifications
     * before answering, and we already restore that history from Room — so the
     * replay must not enter the live event stream.
     */
    private val replayingThreads = ConcurrentHashMap.newKeySet<String>()
    private val promptJobs = ConcurrentHashMap<String, Job>()
    private val pendingPermissions =
        ConcurrentHashMap<String, PendingPermissionRequest>()
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

    @Volatile
    private var catalogSessionId: String? = null

    val isConnected: Boolean
        get() = connection?.isRunning == true && client != null && agentInfo != null

    fun hasActiveTurns(): Boolean = activeTurnIds.isNotEmpty()

    /**
     * Returns the host-owned turn currently running in an ACP session.
     *
     * Some ACP agents (notably OpenCode) send `session/update` notifications
     * without a turn id. The prompt collector still knows the turn, so the
     * manager must be able to resolve that missing wire field at the runtime
     * boundary instead of dropping otherwise valid stream updates.
     */
    fun activeTurnIdForSession(sessionId: String?): String? =
        sessionId?.takeIf { it.isNotBlank() }?.let(activeTurnIds::get)

    fun activeAgentId(): String = (activeProfile ?: profileStore.selected()).id

    fun activeAgentName(): String = (activeProfile ?: profileStore.selected()).name

    fun protocolVersion(): Int? = agentInfo?.protocolVersion

    fun agentVersion(): String? = agentInfo?.implementation?.version

    suspend fun connect(
        profile: AcpAgentProfile = profileStore.selected()
    ) = connectMutex.withLock {
        Log.i(TAG, "Connecting ACP agent id=${profile.id} command=${profile.command}")
        require(profile.enabled) { "ACP agent ${profile.name} is disabled." }
        if (isConnected && activeProfile?.id == profile.id) {
            return@withLock
        }
        disconnectLocked()
        workspaceManager.ensureRuntimeDirectories()
        val baseEnvironment = try {
            if (profile.id == AcpAgentProfileStore.XIAOWAN_AGENT_ID) {
                emptyMap()
            } else {
                prepareLaunchEnvironment(profile).also {
                    requireLaunchCommand(profile)
                }
            }
        } catch (error: Throwable) {
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
                conversationIdProvider = { threadId ->
                    bindingRepository.getBindingByThreadId(threadId)?.conversationId
                }
            )
        } else {
            val launchEnvironment = baseEnvironment + profile.environment
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
                )
            )
        }
        val transport = nextConnection.createTransport(scope)
        val nextProtocol = Protocol(scope, transport)
        val nextClient = Client(nextProtocol)
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
                        terminal = false,
                        planCapabilities = PlanCapabilities()
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
            profileStore.select(profile.id)
            profileStore.saveHealth(
                profile.id,
                AcpAgentHealth(
                    status = AcpAgentHealth.STATUS_ONLINE,
                    installed = true,
                    checkedAt = System.currentTimeMillis(),
                    capabilities = capabilitiesPayload(initialized)
                )
            )
        } catch (error: Throwable) {
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

    suspend fun disconnect() = connectMutex.withLock {
        disconnectLocked()
    }

    private suspend fun disconnectLocked() {
        Log.i(TAG, "Disconnecting ACP runtime profile=${activeProfile?.id ?: "none"}")
        // Close every in-flight turn before tearing the transport down.
        // Cancelling the prompt jobs first would leave their finally blocks
        // racing a dead connection, and the UI would keep showing those turns
        // as running forever.
        activeTurnIds.entries.toList().forEach { (threadId, turnId) ->
            finishTurn(threadId, turnId, status = "cancelled")
        }
        promptJobs.values.toList().forEach { it.cancelAndJoin() }
        promptJobs.clear()
        pendingPermissions.values.forEach { it.response.complete(null) }
        pendingPermissions.clear()
        sessions.clear()
        sessionCwds.clear()
        sessionPermissionBehaviors.clear()
        pendingHandoffConversationIds.clear()
        replayingThreads.clear()
        catalogSessionId = null
        activeTurnIds.clear()
        promptRequestTurns.clear()
        protocol?.close()
        protocol = null
        client = null
        agentInfo = null
        activeProfile = null
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

    private suspend fun agentsPayload(refreshAvailability: Boolean = true): Map<String, Any?> {
        if (refreshAvailability) {
            refreshAgentAvailability()
        }
        val selectedId = profileStore.selected().id
        return linkedMapOf(
            "selectedAgentId" to selectedId,
            "agents" to profileStore.list().map {
                it.toPayload(
                    selected = it.id == selectedId,
                    health = profileStore.health(it.id)
                )
            }
        )
    }

    suspend fun handleMethod(method: String, args: Map<String, Any?>): Any? {
        val canonicalArgs = AcpSessionCompatibility.canonicalize(method, args)
        return when (method) {
            "agent/list" -> agentsPayload()
            "agent/refresh" -> agentsPayload(refreshAvailability = true)
            "agent/select" -> selectAgent(args.stringValue("agentId").orEmpty())
            "agent/save" -> saveAgent(args)
            "agent/delete" -> deleteAgent(args.stringValue("agentId").orEmpty())
            "agent/test" -> testAgent(args.stringValue("agentId"))
            // Public ACP surface. The app keeps the legacy conversation
            // terminology out of the client-facing transport; these names
            // are the protocol's session operations and are shared by every
            // local ACP agent.
            "session/new" -> newAcpSession(canonicalArgs)
            "session/load" -> loadAcpSession(canonicalArgs)
            "session/list" -> listAcpSessions(canonicalArgs)
            "session/prompt" -> promptAcpSession(canonicalArgs)
            "session/cancel" -> cancelAcpSession(canonicalArgs)
            "session/archive" -> archiveThread(canonicalArgs, true)
            "session/unarchive" -> archiveThread(canonicalArgs, false)
            "session/name/set" -> setThreadName(canonicalArgs)
            "thread/archive" -> archiveThread(args, true)
            "thread/unarchive" -> archiveThread(args, false)
            "thread/name/set" -> setThreadName(args)
            "model/list" -> listModels(canonicalArgs)
            "config/read" -> readRunConfig(canonicalArgs)
            "config/set" -> setConfigOption(canonicalArgs)
            "collaborationMode/list" -> listCollaborationModes(canonicalArgs)
            "review/start" -> startReview(canonicalArgs)
            "respondToServerRequest" -> respondToPermission(args)
            else -> throw UnsupportedOperationException(
                "ACP agent does not expose the legacy method '$method'."
            )
        }
    }

    private suspend fun newAcpSession(args: Map<String, Any?>): Map<String, Any?> {
        return startThread(args).withAcpSessionId()
    }

    private suspend fun loadAcpSession(args: Map<String, Any?>): Map<String, Any?> {
        val normalized = if (args.stringValue("sessionId") != null &&
            args.stringValue("threadId") == null
        ) {
            args + ("threadId" to args.stringValue("sessionId"))
        } else {
            args
        }
        return resumeThread(normalized).withAcpSessionId()
    }

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
        return interruptTurn(normalized).withAcpSessionId()
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
        val selected = profileStore.select(id)
        // A provider switch is a process boundary.  Do not rely on the
        // in-memory `isConnected` flag here: after an app restart or a
        // partially failed handshake the old ACP process may still be alive
        // even though the client state is incomplete.  Closing unconditionally
        // prevents the next prompt from being sent to the previous agent.
        if (activeProfile?.id != selected.id || connection != null) {
            Log.i(TAG, "Switching ACP agent to ${selected.id}; closing previous process")
            disconnect()
        }
        return agentsPayload(refreshAvailability = false)
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

    private suspend fun refreshAgentAvailability() {
        val profiles = profileStore.list()
        if (profiles.isEmpty()) return
        val externalProfiles = profiles.filterNot {
            it.id == AcpAgentProfileStore.XIAOWAN_AGENT_ID
        }
        val command = MANAGED_NPM_PATH_PREFIX + "\n" + externalProfiles.flatMap { profile ->
            val id = shellQuoteAcp(profile.id)
            val runtime = AcpAgentProfileStore.officialRuntime(profile)
            buildList {
                add("launch" to profile.command)
                // A managed adapter is the official ACP entry point.  Its
                // vendor CLI (for example `codex`) is not required to be
                // installed separately and must not make an ACP profile look
                // missing after the adapter itself was installed.
                if (runtime?.managedAdapterPackage == null) {
                    runtime?.discoveryCommand
                        ?.takeIf { it != profile.command }
                        ?.let { add("discovery" to it) }
                }
            }.map { (kind, rawCommand) ->
                val executable = shellQuoteAcp(rawCommand)
                "if command -v $executable >/dev/null 2>&1; then " +
                    "printf '__OMNI_ACP_AGENT__\\t%s\\t%s\\t1\\n' $id '$kind'; else " +
                    "printf '__OMNI_ACP_AGENT__\\t%s\\t%s\\t0\\n' $id '$kind'; fi"
            }
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
                !installed -> AcpAgentHealth(
                    status = AcpAgentHealth.STATUS_MISSING,
                    installed = false,
                    error = "Agent command not found: " +
                        profile.command,
                    checkedAt = checkedAt
                )
                !launchInstalled && runtime?.managedAdapterPackage != null ->
                    AcpAgentHealth(
                        status = AcpAgentHealth.STATUS_UNCHECKED,
                        installed = true,
                        error = "ACP adapter will be prepared during Initialize.",
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
        val normalized = message.lowercase()
        val missing = "not found" in normalized ||
            "code 127" in normalized ||
            "no such file" in normalized
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

    private suspend fun startThread(args: Map<String, Any?>): Map<String, Any?> =
        sessionMutex.withLock {
            val cwd = normalizeCwd(args.stringValue("cwd"))
            val previousBinding = args.longValue("conversationId")?.let {
                bindingRepository.getBindingByConversationId(it)
            }
            val catalogSession = catalogSessionId
                ?.let(sessions::get)
                ?.takeIf { sessionCwds[it.sessionId.value] == cwd }
            val session = catalogSession ?: requireClient().newSession(
                sessionCreationParameters(cwd),
                operationsFactory()
            ).also { registerSession(it, cwd) }
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
            if (previousBinding != null && previousBinding.threadId != session.sessionId.value) {
                pendingHandoffConversationIds[session.sessionId.value] = conversationId
            }
            sessionPayload(session, conversationId)
        }

    private suspend fun resumeThread(args: Map<String, Any?>): Map<String, Any?> =
        sessionMutex.withLock {
            val threadId = resolveThreadId(args)
            val expectedAgentId = profileStore.agentIdForSession(threadId)
            if (expectedAgentId != null && expectedAgentId != activeAgentId()) {
                val cwd = normalizeCwd(
                    args.stringValue("cwd")
                        ?: bindingRepository.getBindingByThreadId(threadId)?.cwd
                )
                val fresh = requireClient().newSession(
                    sessionCreationParameters(cwd),
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
                pendingHandoffConversationIds[fresh.sessionId.value] = conversationId
                return@withLock sessionPayload(fresh, conversationId).plus(
                    mapOf(
                        "sessionRestored" to false,
                        "previousSessionId" to threadId
                    )
                )
            }
            sessions[threadId]?.let {
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
            val parameters = sessionCreationParameters(cwd)
            val restored = try {
                when {
                    capabilities.sessionCapabilities.resume != null ->
                        requireClient().resumeSession(
                            SessionId(threadId),
                            parameters,
                            operationsFactory()
                        )
                    capabilities.loadSession -> {
                        replayingThreads.add(threadId)
                        try {
                            requireClient().loadSession(
                                SessionId(threadId),
                                parameters,
                                operationsFactory()
                            )
                        } finally {
                            replayingThreads.remove(threadId)
                        }
                    }
                    else -> null
                }
            } catch (error: Throwable) {
                if (!isRecoverableAgentThreadError(error.message.orEmpty())) {
                    throw error
                }
                null
            }
            if (restored == null) {
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
                pendingHandoffConversationIds[fresh.sessionId.value] = conversationId
                return@withLock sessionPayload(fresh, conversationId).plus(
                    mapOf(
                        "sessionRestored" to false,
                        "previousSessionId" to threadId
                    )
                )
            }
            registerSession(restored, cwd)
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
            put("active", activeTurnIds.containsKey(response["threadId"]?.toString()))
            activeTurnIds[response["threadId"]?.toString()]?.let {
                put("activeTurnId", it)
                put("turnId", it)
            }
        }
    }

    private suspend fun listThreads(args: Map<String, Any?>): Map<String, Any?> {
        val limit = (args["limit"] as? Number)?.toInt()?.coerceIn(1, 200) ?: 50
        val capabilities = requireAgentInfo().capabilities
        val entries = if (capabilities.sessionCapabilities.list != null) {
            requireClient().listSessions(
                cwd = args.stringValue("cwd")
            ).take(limit).toList().map { session ->
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
                .take(limit)
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
        return mapOf("threads" to entries, "data" to entries, "nextCursor" to null)
    }

    private suspend fun archiveThread(
        args: Map<String, Any?>,
        archived: Boolean
    ): Map<String, Any?> {
        val threadId = resolveThreadId(args)
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
        val session = if (requestedThreadId.isNullOrBlank()) {
            ensureCatalogSession(args)
        } else {
            sessions[requestedThreadId] ?: run {
                resumeThread(args + mapOf("threadId" to requestedThreadId))
                sessions[requestedThreadId]
                    ?: throw IllegalStateException("Failed to restore ACP session.")
            }
        }
        val threadId = session.sessionId.value
        check(!activeTurnIds.containsKey(threadId)) {
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
        option ?: throw IllegalArgumentException(
            "ACP session does not expose config option '$configId'."
        )

        val appliedValue: Any? = when (option) {
            is SessionConfigOption.Select -> {
                val value = rawValue.toString()
                require(option.flatOptions().any { it.value.value == value }) {
                    "Invalid value '$value' for ACP config option '$configId'."
                }
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
                appliedValue == "agent-full-access"
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
        turnStartMutex.withLock {
            startTurnLocked(args)
        }

    private suspend fun startTurnLocked(args: Map<String, Any?>): Map<String, Any?> {
        val session = ensureSessionForTurn(args)
        val threadId = session.sessionId.value
        val requestId = args.stringValue("requestId")?.takeIf { it.isNotBlank() }
        val requestKey = requestId?.let { "$threadId|$it" }
        requestKey?.let { key ->
            promptRequestTurns[key]?.let { existingTurnId ->
                return linkedMapOf(
                    "threadId" to threadId,
                    "turnId" to existingTurnId,
                    "conversationId" to bindingRepository
                        .getBindingByThreadId(threadId)?.conversationId
                )
            }
        }
        val turnId = UUID.randomUUID().toString()
        val existingTurnId = activeTurnIds.putIfAbsent(threadId, turnId)
        if (existingTurnId != null) {
            val sameRequestTurn = requestKey?.let(promptRequestTurns::get)
            if (sameRequestTurn != null) {
                return linkedMapOf(
                    "threadId" to threadId,
                    "turnId" to sameRequestTurn,
                    "conversationId" to bindingRepository
                        .getBindingByThreadId(threadId)?.conversationId
                )
            }
            throw IllegalStateException("ACP session $threadId already has an active turn.")
        }
        // startThread applies initial configuration for a new session. After
        // that, the idle session is changed through config/set; do not
        // overwrite Harness-owned state on every turn. Older ACP adapters
        // without configOptions keep the legacy per-turn compatibility path.
        try {
            if (sessionConfigOptions(session).isEmpty()) {
                applyRunConfig(session, args)
            } else {
                sessionPermissionBehaviors[threadId] = resolveAcpPermissionBehavior(args)
            }
        } catch (error: Throwable) {
            activeTurnIds.remove(threadId, turnId)
            throw error
        }
        requestKey?.let { key ->
            promptRequestTurns[key] = turnId
            if (promptRequestTurns.size > 256) {
                promptRequestTurns.keys.firstOrNull()?.let(promptRequestTurns::remove)
            }
        }
        val blocks = try {
            buildPromptBlocks(args, turnId, threadId)
        } catch (error: Throwable) {
            activeTurnIds.remove(threadId, turnId)
            requestKey?.let(promptRequestTurns::remove)
            throw error
        }
        val completion = CompletableDeferred<Map<String, Any?>>()
        val activeConnection = connection
            ?: throw IllegalStateException("ACP agent connection is not available.")
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
                session.prompt(blocks).takeWhile { event ->
                    lastTurnActivityAt[threadId] = System.currentTimeMillis()
                    when (event) {
                        is Event.SessionUpdateEvent -> {
                            handleSessionUpdate(threadId, turnId, event.update)
                            true
                        }
                        is Event.PromptResponseEvent -> {
                            stopReason = event.response.stopReason.name.lowercase()
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
                val status = resolveTurnTerminalStatus(stopReason, cancelled, failure)
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
        lastTurnActivityAt[threadId] = System.currentTimeMillis()
        var watchdog: Job? = null
        var exitWatcher: Job? = null

        // A process exit is not guaranteed to close an in-flight ACP prompt
        // flow.  StdioTransport may remain suspended on the input channel even
        // after the child has gone away, so observing the connection only while
        // initializing is insufficient.  Keep the process lifecycle and the
        // host turn lifecycle joined for every prompt.
        exitWatcher = scope.launch(start = CoroutineStart.LAZY) {
            val exitCode = activeConnection.exitSignal.await()
            if (
                activeTurnIds[threadId] != turnId ||
                finishedTurns.contains(turnId)
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

        // Guards against an adapter that streams its answer and then never
        // sends the `session/prompt` response (see [lastTurnActivityAt]). The
        // watchdog cancels itself as soon as the prompt job ends for any other
        // reason, so it only ever finalizes a genuinely stalled turn.  A
        // timed-out process is also closed; otherwise the next prompt can be
        // sent to the same broken ACP session and appear to require a new chat.
        watchdog = scope.launch(start = CoroutineStart.LAZY) {
            val startedAt = System.currentTimeMillis()
            while (true) {
                delay(STALL_CHECK_INTERVAL_MS)
                val last = lastTurnActivityAt[threadId] ?: return@launch
                val now = System.currentTimeMillis()
                if (now - startedAt >= STALL_DEADLINE_MS ||
                    now - last >= STALL_DEADLINE_MS
                ) {
                    Log.w(
                        TAG,
                        "ACP turn=$turnId on session=$threadId produced no updates for " +
                            "$STALL_DEADLINE_MS ms; finalizing because the adapter did not " +
                            "send a session/prompt response."
                    )
                    finishTurn(
                        threadId = threadId,
                        turnId = turnId,
                        status = "timeout",
                        error = "ACP agent did not finish this turn within " +
                            "${STALL_DEADLINE_MS / 1000}s."
                    )
                    if (connection === activeConnection) {
                        runCatching { disconnect() }
                            .onFailure { closeError ->
                                Log.w(
                                    TAG,
                                    "Unable to reset stalled ACP runtime: " +
                                        (closeError.message ?: closeError.javaClass.simpleName),
                                    closeError
                                )
                            }
                    } else {
                        runCatching { job.cancelAndJoin() }
                    }
                    return@launch
                }
            }
        }

        job.invokeOnCompletion {
            watchdog?.cancel()
            exitWatcher?.cancel()
        }
        watchdog?.start()
        exitWatcher?.start()
        job.start()
        job.join()
        watchdog?.cancelAndJoin()
        exitWatcher?.cancelAndJoin()
        return linkedMapOf<String, Any?>(
            "threadId" to threadId,
            "turnId" to turnId,
            "conversationId" to bindingRepository.getBindingByThreadId(threadId)?.conversationId
        ).apply { putAll(completion.await()) }.filterValues { it != null }
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
        if (!finishedTurns.add(turnId)) return
        if (finishedTurns.size > 256) {
            finishedTurns.firstOrNull()?.let(finishedTurns::remove)
        }
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
            onMessage(
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
        activeTurnIds.remove(threadId, turnId)
        lastTurnActivityAt.remove(threadId)
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
            "turnId" to activeTurnIds[threadId],
            "result" to response.toString()
        )
    }

    private suspend fun interruptTurn(args: Map<String, Any?>): Map<String, Any?> {
        val threadId = resolveThreadId(args)
        val session = sessions[threadId]
            ?: throw IllegalArgumentException("ACP session is not loaded: $threadId")
        val turnId = activeTurnIds[threadId]

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
        if (turnId != null && activeTurnIds[threadId] == turnId) {
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
        val pending = pendingPermissions.remove(requestId)
            ?: throw IllegalArgumentException("Unknown ACP permission request: $requestId")
        val response = args.mapValue("response")
        val accepted = response.stringValue("decision")?.lowercase() == "accept"
        val selected = pending.options.firstOrNull { option ->
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

    private suspend fun ensureSessionForTurn(args: Map<String, Any?>): ClientSession {
        val explicitThreadId = args.stringValue("threadId")
        if (!explicitThreadId.isNullOrBlank()) {
            return sessions[explicitThreadId] ?: run {
                val response = resumeThread(args)
                val resolvedThreadId = response.stringValue("threadId")
                    ?: response.stringValue("sessionId")
                    ?: explicitThreadId
                sessions[resolvedThreadId]
                    ?: throw IllegalStateException("Failed to restore ACP session.")
            }
        }
        val conversationId = args.longValue("conversationId")
        val binding = if (conversationId != null) {
            bindingRepository.getBindingByConversationId(conversationId)
        } else {
            null
        }
        if (binding != null) {
            val bindingAgentId = profileStore.agentIdForSession(binding.threadId)
                ?: AcpAgentProfileStore.DEFAULT_CODEX_AGENT_ID
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

    private fun sessionCreationParameters(cwd: String): SessionCreationParameters {
        val profile = activeProfile ?: profileStore.selected()
        val supportsHttp = requireAgentInfo().capabilities.mcpCapabilities.http
        val mcpState = if (supportsHttp) {
            McpServerManager.ensureRunning(appContext)
        } else {
            McpServerManager.currentState()
        }
        return SessionCreationParameters(
            cwd = cwd,
            mcpServers = buildLocalAgentAcpMcpServers(
                agentId = profile.id,
                supportsHttp = supportsHttp,
                state = mcpState
            )
        )
    }

    private suspend fun applyRunConfig(
        session: ClientSession,
        args: Map<String, Any?>
    ) {
        sessionPermissionBehaviors[session.sessionId.value] =
            resolveAcpPermissionBehavior(args)
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
                    val stringValue = value.toString()
                    if (option.flatOptions().any { it.value.value == stringValue } &&
                        option.currentValue.value != stringValue
                    ) {
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
                        val mode = session.availableModes.firstOrNull {
                            it.id.value == value.toString()
                        }
                        if (mode != null) {
                            session.setMode(SessionModeId(mode.id.value))
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
            if (attachment["sendToModel"] == false) {
                return@forEach
            }
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
                options = permissions,
                response = CompletableDeferred()
            )
            pendingPermissions[requestId] = pending
            onMessage(
                linkedMapOf(
                    "jsonrpc" to "2.0",
                    "id" to requestId,
                    "method" to "session/request_permission",
                    "params" to mapOf(
                        "sessionId" to threadId,
                        "toolCall" to mapOf(
                            "toolCallId" to toolCall.toolCallId.value,
                            "title" to (toolCall.title ?: "Permission required"),
                            "detail" to permissions.joinToString("\n") { it.name }
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
            return RequestPermissionResponse(
                outcome = selected?.let {
                    RequestPermissionOutcome.Selected(it.optionId)
                } ?: RequestPermissionOutcome.Cancelled
            )
        }

        override suspend fun notify(
            notification: SessionUpdate,
            _meta: JsonElement?
        ) {
            handleSessionUpdate(
                threadId = threadId,
                turnId = activeTurnIds[threadId],
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

    private suspend fun handleSessionUpdate(
        threadId: String,
        turnId: String?,
        update: SessionUpdate
    ) {
        // `session/load` replays the whole conversation as session updates. We
        // restore history from Room instead, so the replay must never reach the
        // live stream — otherwise every replayed message becomes its own
        // pseudo turn in the UI and gets persisted back over the real history.
        if (threadId in replayingThreads) return

        // Never let a timeline update through without a turn id. Updates that
        // arrive outside an active prompt come via the notify callback with
        // none, and downstream they would fall back to a per-item id — which
        // spawns a duplicate agent avatar and "processing" header per item.
        // The SDK closes the prompt's update channel before it emits the
        // prompt response. A notification received after that point has no
        // active turn and must not be assigned to the previous turn.
        val resolvedTurnId = turnId?.takeIf { it.isNotBlank() }
            ?: activeTurnIds[threadId]
        if (resolvedTurnId == null && update.isTurnScoped()) {
            Log.w(TAG, "Dropping turn-scoped ACP update with no resolvable turn: $update")
            return
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
            update = notification.update
        )
    }

    /**
     * Sends an ACP notification without adding host-only identifiers to its
     * params. ACP clients must be able to consume this as a standard
     * `session/update` message without knowing anything about this app.
     */
    private suspend fun emitAcpNotification(
        sessionId: String,
        update: Map<String, Any?>
    ) {
        onMessage(linkedMapOf(
                "method" to "session/update",
                "params" to mapOf(
                    "sessionId" to sessionId,
                    "update" to update
                )
            ))
    }

    private fun registerSession(session: ClientSession, cwd: String) {
        sessions[session.sessionId.value] = session
        sessionCwds[session.sessionId.value] = cwd
    }

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
        "active" to activeTurnIds.containsKey(session.sessionId.value),
        "activeTurnId" to activeTurnIds[session.sessionId.value],
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
        args.stringValue("threadId")?.let { return it }
        val conversationId = args.longValue("conversationId")
            ?: throw IllegalArgumentException("threadId or conversationId is required")
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
        val options: List<PermissionOption>,
        val response: CompletableDeferred<PermissionOption?>
    )

    companion object {
        private const val TAG = "LocalAcpRuntime"
        private const val INITIALIZE_TIMEOUT_MS = 90_000L
        private const val PROCESS_CLOSE_TIMEOUT_MS = 1_500L
        private const val CANCEL_REQUEST_TIMEOUT_MS = 2_000L
        private const val CANCEL_JOIN_TIMEOUT_MS = 2_000L
        private const val COMMAND_PROBE_TIMEOUT_MS = 20_000L
        private const val MAX_FILE_LINES = 20_000

        // How long a turn may stay silent before the stall watchdog finalizes
        // it. Picked well above the longest gap a healthy turn produces between
        // updates — a tool that runs for a while still emits output deltas, so
        // a gap this wide means the adapter stopped without sending the
        // `session/prompt` response. Lower recovers stuck turns faster but
        // risks cutting off a slow one; 120 s is the conservative default.
        private const val STALL_DEADLINE_MS = 120_000L
        private const val STALL_CHECK_INTERVAL_MS = 15_000L
    }
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
    val sandboxType = args.mapValue("sandboxPolicy")
        .stringValue("type")
        ?.lowercase()
        ?.replace("-", "")
        ?.replace("_", "")
    return if (approvalPolicy == "never" || sandboxType == "dangerfullaccess") {
        AcpPermissionBehavior.ALLOW_WITHOUT_PROMPT
    } else {
        AcpPermissionBehavior.ASK_USER
    }
}

internal interface AcpRuntimeConnection {
    val exitSignal: CompletableDeferred<Int?>
    val isRunning: Boolean
    fun createTransport(parentScope: CoroutineScope): com.agentclientprotocol.transport.Transport
    suspend fun start()
    fun diagnosticSummary(): String
    fun exitDescription(exitCode: Int?): String
    suspend fun close()
}

private class AcpProcessConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val profile: AcpAgentProfile,
    private val environment: Map<String, String>
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
        Log.i(
            "LocalAcpRuntime",
            "Launched ACP process profile=${profile.id} alive=${started.isAlive}"
        )
        writer = OutputStreamWriter(started.outputStream, StandardCharsets.UTF_8)
        readerJob = scope.launch {
            try {
                lineFlow(started).collect { inputChannel.send(it) }
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
        Log.i(
            "LocalAcpRuntime",
            "Closing ACP process profile=${profile.id} alive=${current?.isAlive == true}"
        )
        process = null
        readerJob?.cancel()
        stderrJob?.cancel()
        waitJob?.cancel()
        // Destroy the process before closing its pipes.  A proot-backed
        // Process stream can block while its child is still alive; doing the
        // pipe cleanup first used to hold the runtime mutex indefinitely and
        // prevented every subsequent ACP agent from starting.
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
        readerJob = null
        stderrJob = null
        waitJob = null
        inputChannel.close()
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
        "steering" to steering
    )
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
