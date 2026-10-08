package cn.com.omnimind.bot.agent.projection

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.llm.SceneVoiceConfigStore
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide owner of the chat runtime projection.
 *
 * It owns the single [ChatConversationRuntimeCoordinator], takes the ACP
 * event stream from [AgentRuntimeManager], and publishes immutable runtime
 * snapshots plus event outcomes to the attached UI (the Flutter adapter, via
 * `ChatRuntimeChannel`). UI commands arrive through [handleCommand]. All
 * methods run on the main thread.
 */
object ChatRuntimeHost {
    private const val TAG = "ChatRuntimeHost"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var appContext: Context? = null
    private var coordinatorInstance: ChatConversationRuntimeCoordinator? = null
    private var eventSourceInstalled = false

    /** Receives batches (`type` = `sync`) and event outcomes (`type` = `outcome`). */
    private var sink: ((Map<String, Any?>) -> Unit)? = null

    /** Last message instance per id the UI holds, per runtime key. */
    private val sentMessages = HashMap<String, Map<String, ChatMessage>>()

    /** Routing contexts published by the UI, keyed by its surface id. */
    private val routingHosts = LinkedHashMap<Int, Pair<ChatRuntimeRoutingContext, ChatRuntimeEventHost>>()
    private var lastForwardedOutcome: ChatRuntimeEventOutcome? = null
    private var capture: MutableList<Map<String, Any?>>? = null

    /** Native voice playback (`SceneVoicePlaybackManager.speakText`), bound by its channel. */
    @Volatile
    var voiceSpeaker: ((messageId: String, text: String, enqueue: Boolean) -> Boolean)? = null

    /**
     * Autoplay for native pages when no Flutter engine bound [voiceSpeaker]
     * (native Home running alone, 5e-7). Without it, assistant replies on the
     * native page were never spoken: the speaker existed only once the
     * Flutter chat had attached its channel. Created on first use.
     */
    private var nativeVoice: cn.com.omnimind.bot.voice.SceneVoicePlaybackManager? = null

    private fun speak(messageId: String, text: String, enqueue: Boolean): Boolean {
        voiceSpeaker?.let { return it(messageId, text, enqueue) }
        val context = appContext ?: return false
        val voice = nativeVoice ?: cn.com.omnimind.bot.voice.SceneVoicePlaybackManager(context).also { nativeVoice = it }
        return runCatching { voice.speakText(messageId, text, enqueue, preferStreaming = true) }
            .onFailure { Log.w(TAG, "原生语音播报失败: ${it.message}") }
            .getOrDefault(false)
    }

    private var voiceAvailabilityCheckedAt = 0L
    private var voiceAutoplayAvailable = false

    fun initialize(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    val coordinator: ChatConversationRuntimeCoordinator
        get() = coordinatorInstance ?: createCoordinator().also { coordinatorInstance = it }

    /** The single native prompt admission entry (batch 5b). */
    val dispatcher: ChatPromptDispatcher
        get() = dispatcherInstance ?: ChatPromptDispatcher(coordinator) { method, args ->
            AgentRuntimeManager.getInstance(checkNotNull(appContext)).handleMethod(method, args)
        }.also { dispatcherInstance = it }

    private var dispatcherInstance: ChatPromptDispatcher? = null

    /** The single native send orchestration (batch 5d-0b). */
    val launcher: ChatTurnLauncher
        get() = launcherInstance ?: ChatTurnLauncher(coordinator, dispatcher).also { launcherInstance = it }

    private var launcherInstance: ChatTurnLauncher? = null

    /**
     * The navigation generation each UI surface currently shows. A surface
     * bumps it whenever it moves to another conversation or Harness, so a
     * turn admitted for an older generation stops between its awaits. Read
     * natively: the launcher never calls back into the UI mid-turn.
     */
    private val surfaceGenerations = HashMap<String, Long>()

    fun setSurfaceGeneration(surfaceId: String, generation: Long) {
        surfaceGenerations[surfaceId] = generation
    }

    private fun isSurfaceGenerationCurrent(surfaceId: String, generation: Long): Boolean =
        surfaceGenerations[surfaceId] == generation

    /**
     * Launches one turn for a UI submission. The reply carries the
     * [ChatTurnOutcome] and the target runtime's current snapshot.
     */
    fun handleLaunchTurn(args: Map<String, Any?>, reply: (Result<Map<String, Any?>>) -> Unit) {
        scope.launch {
            val outcome = runCatching {
                val request = turnRequestFromChannel(args)
                val surfaceId = dartToString(args["surfaceId"])?.trim().orEmpty()
                val generation = asLong(args["generation"])
                val fenced = surfaceId.isNotEmpty() && generation != null
                // A surface that has not reported a move yet shows this target.
                if (fenced) surfaceGenerations.putIfAbsent(surfaceId, generation!!)
                val isTargetCurrent: () -> Boolean = {
                    !fenced || isSurfaceGenerationCurrent(surfaceId, generation!!)
                }
                val result = launcher.launchTurn(request, isTargetCurrent)
                coordinator.publishDirtySnapshots()
                val snapshot = coordinator.snapshotFor(request.conversationId, request.mode)
                linkedMapOf(
                    "result" to result.toChannel(),
                    "sync" to listOf(syncBatch(listOfNotNull(snapshot), emptyMap())),
                )
            }
            reply(outcome)
        }
    }

    /**
     * Launches a turn for a native surface (batch 5d-1b). The turn runs in
     * the host scope, not the caller's: closing the page must not cancel a
     * turn between admission and its PromptResponse, which would strand the
     * runtime as responding. [isTargetCurrent] still fences navigation.
     */
    fun launchTurnDetached(
        request: ChatTurnRequest,
        isTargetCurrent: () -> Boolean,
        onOutcome: (ChatTurnOutcome) -> Unit = {},
    ) {
        scope.launch {
            val outcome = launcher.launchTurn(request, isTargetCurrent)
            coordinator.publishDirtySnapshots()
            onOutcome(outcome)
        }
    }

    private fun turnRequestFromChannel(args: Map<String, Any?>): ChatTurnRequest {
        fun str(key: String): String? = dartToString(args[key])?.trim()?.ifEmpty { null }
        return ChatTurnRequest(
            taskId = str("taskId") ?: error("taskId is required"),
            conversationId = asInt(args["conversationId"]) ?: error("conversationId is required"),
            mode = str("mode") ?: error("mode is required"),
            text = dartToString(args["text"]).orEmpty(),
            attachments = (args["attachments"] as? List<*>).orEmpty().mapNotNull { copyStringMap(it) },
            userMessage = copyStringMap(args["userMessage"])?.let(ChatMessage::fromJson),
            existingSessionId = str("existingSessionId"),
            agentId = str("agentId"),
            permission = AgentPermissionMode.fromPreference(str("permissionMode")),
            model = str("model"),
            effort = str("effort"),
            collaborationMode = str("collaborationMode"),
            conversationMode = str("conversationMode"),
            terminalEnvironment = copyStringMap(args["terminalEnvironment"])
                ?.mapNotNull { (key, value) -> dartToString(value)?.let { key to it } }
                ?.toMap(LinkedHashMap()),
            conversation = copyStringMap(args["conversation"]),
            clearThinkingOnFailure = args["clearThinkingOnFailure"] == true,
        )
    }

    private fun createCoordinator(): ChatConversationRuntimeCoordinator {
        val context = checkNotNull(appContext) { "ChatRuntimeHost.initialize was not called" }
        val voice = ChatRuntimeVoiceAutoplay(autoplayEnabled = ::isVoiceAutoplayEnabled) { id, text, enqueue ->
            speak(id, text, enqueue)
        }
        return ChatConversationRuntimeCoordinator(
            persistence = NativeChatRuntimeHistoryStore(context),
            voice = voice,
            scope = scope,
            scheduler = { delayMillis, block ->
                val runnable = Runnable(block)
                mainHandler.postDelayed(runnable, delayMillis)
                ChatRuntimeTimer { mainHandler.removeCallbacks(runnable) }
            },
            isEnglish = { runCatching { AppLocaleManager.isEnglish() }.getOrDefault(false) },
        ).also { it.addListener(::onRuntimesChanged) }
    }

    /**
     * Voice scene bound (provider + model) or custom curl ready, with
     * autoplay on. Cached briefly: the check runs per streamed chunk.
     */
    private fun isVoiceAutoplayEnabled(): Boolean {
        val now = System.currentTimeMillis()
        if (now - voiceAvailabilityCheckedAt < 2_000L) return voiceAutoplayAvailable
        voiceAvailabilityCheckedAt = now
        voiceAutoplayAvailable = runCatching {
            val config = SceneVoiceConfigStore.getConfig()
            val binding = SceneModelBindingStore.getBinding(SceneVoiceConfigStore.SCENE_ID)
            val bound = binding != null && binding.providerProfileId.isNotBlank() && binding.modelId.isNotBlank()
            val customCurlReady = config.ttsMode == SceneVoiceConfigStore.TTS_MODE_CUSTOM_CURL &&
                config.customCurlCommand.isNotBlank()
            config.autoPlay && (bound || customCurlReady)
        }.getOrDefault(false)
        return voiceAutoplayAvailable
    }

    /** Forces the next autoplay check to re-read the voice settings. */
    fun invalidateVoiceAvailability() {
        voiceAvailabilityCheckedAt = 0L
    }

    // ------------------------------------------------------------- UI link

    /**
     * Binds the UI sink. The UI starts from an empty mirror, so every
     * runtime is resent in full.
     */
    fun attachSink(newSink: ((Map<String, Any?>) -> Unit)?) {
        sink = newSink
        sentMessages.clear()
        if (newSink != null) newSink(syncBatch(coordinator.allSnapshots(), emptyMap()))
    }

    /** Native external user messages (IM entry points) join the runtime directly. */
    fun onExternalUserMessageAppended(data: Map<String, Any?>) {
        if (coordinatorInstance == null && appContext == null) return
        coordinator.handleExternalUserMessageAppended(data)
    }

    private fun onRuntimesChanged(snapshots: List<ChatRuntimeSnapshot>, removed: Map<String, Long>) {
        val batch = syncBatch(snapshots, removed)
        capture?.add(batch)
        sink?.invoke(batch)
    }

    private fun syncBatch(snapshots: List<ChatRuntimeSnapshot>, removed: Map<String, Long>): Map<String, Any?> {
        val encoded = snapshots.map { snapshot ->
            val key = ChatConversationRuntimeCoordinator.runtimeKey(snapshot.conversationId, snapshot.mode)
            val previous = sentMessages[key] ?: emptyMap()
            val changed = snapshot.messages.filter { previous[it.id] !== it }
            sentMessages[key] = snapshot.messages.associateBy { it.id }
            snapshot.toChannelMap(changed)
        }
        for (key in removed.keys) sentMessages.remove(key)
        return linkedMapOf("type" to "sync", "snapshots" to encoded, "removed" to removed)
    }

    /** Resends one runtime in full when the UI lost track of its messages. */
    private fun resync(conversationId: Int, mode: String): Map<String, Any?> {
        sentMessages.remove(ChatConversationRuntimeCoordinator.runtimeKey(conversationId, mode))
        val snapshot = coordinator.snapshotFor(conversationId, mode) ?: return syncBatch(emptyList(), emptyMap())
        return syncBatch(listOf(snapshot), emptyMap())
    }

    // -------------------------------------------------------------- events

    /**
     * Takes the runtime event stream once the UI first attaches a surface.
     * Until then [AgentRuntimeManager] keeps buffering events, as it did
     * before the Flutter reducer subscribed.
     */
    private fun ensureEventSource() {
        if (eventSourceInstalled) return
        val context = appContext ?: return
        eventSourceInstalled = true
        AgentRuntimeManager.getInstance(context).setEventListener { event -> routeAgentEvent(event) }
    }

    private fun routeAgentEvent(event: Map<String, Any?>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { routeAgentEvent(event) }
            return
        }
        val outcome = coordinator.routeAgentEvent(event) ?: return
        if (outcome !== lastForwardedOutcome) forwardOutcome(outcome)
    }

    private fun forwardOutcome(outcome: ChatRuntimeEventOutcome) {
        lastForwardedOutcome = outcome
        val result = outcome.result
        sink?.invoke(
            linkedMapOf(
                "type" to "outcome",
                "event" to outcome.event,
                "conversationId" to outcome.conversationId,
                "mode" to outcome.mode,
                "promotedRemoteThreadId" to outcome.promotedRemoteThreadId,
                "result" to reduceResultToChannel(result),
            ),
        )
    }

    private fun setRoutingContexts(contexts: Map<Int, ChatRuntimeRoutingContext>) {
        for ((id, entry) in routingHosts.toList()) {
            if (id !in contexts) {
                entry.second.detach()
                routingHosts.remove(id)
            }
        }
        for ((id, context) in contexts) {
            val existing = routingHosts[id]
            if (existing != null) {
                routingHosts[id] = context to existing.second
                continue
            }
            val host = coordinator.attachEventHost(
                context = { routingHosts[id]?.first },
                onOutcome = { outcome -> if (outcome !== lastForwardedOutcome) forwardOutcome(outcome) },
            )
            routingHosts[id] = context to host
        }
        if (routingHosts.isNotEmpty()) ensureEventSource()
    }

    // ------------------------------------------------------------ commands

    /**
     * Runs one UI command. Returns `{result, sync}`: `sync` repeats the
     * batches the command published, so an awaiting caller reads a mirror
     * that already includes its own change.
     */
    fun handleCommand(method: String, args: Map<String, Any?>): Map<String, Any?> {
        val captured = ArrayList<Map<String, Any?>>()
        val previous = capture
        capture = captured
        val result = try {
            executeCommand(method, args)
        } finally {
            capture = previous
        }
        // Commands that only mark runtimes dirty publish here, like the
        // direct field writes they replace becoming visible on rebuild.
        capture = captured
        try {
            coordinator.publishDirtySnapshots()
        } finally {
            capture = previous
        }
        return linkedMapOf("result" to result, "sync" to captured)
    }

    @Suppress("UNCHECKED_CAST")
    private fun executeCommand(method: String, args: Map<String, Any?>): Any? {
        val c = coordinator
        fun int(key: String): Int = asInt(args[key]) ?: throw IllegalArgumentException("$key is required")
        fun str(key: String): String = dartToString(args[key]) ?: throw IllegalArgumentException("$key is required")
        fun optStr(key: String): String? = dartToString(args[key])
        fun optInt(key: String): Int? = asInt(args[key])
        fun optBool(key: String): Boolean? = args[key] as? Boolean
        fun bool(key: String, default: Boolean = false): Boolean = optBool(key) ?: default
        fun map(key: String): Map<String, Any?>? = copyStringMap(args[key])
        fun messages(key: String): List<ChatMessage> =
            (args[key] as? List<*>).orEmpty().mapNotNull { copyStringMap(it)?.let(ChatMessage::fromJson) }
        fun message(key: String): ChatMessage = ChatMessage.fromJson(map(key) ?: emptyMap())
        val conversationId by lazy { int("conversationId") }
        val mode by lazy { str("mode") }

        return when (method) {
            "ensureRuntime", "ensureEphemeralRuntime" -> {
                val initialMessages = if (args.containsKey("initialMessages")) messages("initialMessages") else null
                if (method == "ensureRuntime") {
                    c.ensureRuntime(conversationId, mode, initialMessages, map("conversation"), optStr("initialChatIslandDisplayLayer"))
                } else {
                    c.ensureEphemeralRuntime(conversationId, mode, initialMessages, map("conversation"), optStr("initialChatIslandDisplayLayer"))
                }
                null
            }
            "registerTask" -> c.registerTask(str("taskId"), conversationId, mode).let { null }
            "beginAcpTurn" -> c.beginAcpTurn(str("taskId"), conversationId, mode).let { null }
            "bindAcpSession" -> c.bindAcpSession(str("taskId"), conversationId, mode, str("sessionId"))
            "isTaskActive" -> c.isTaskActive(str("taskId"), conversationId, mode)
            "finishTaskFromAuthoritativeSnapshot" -> c.finishTaskFromAuthoritativeSnapshot(
                str("taskId"), conversationId, mode, optStr("sessionId"), optStr("turnId"),
            )
            "unregisterTask" -> c.unregisterTask(str("taskId"), optInt("conversationId"), optStr("mode")).let { null }
            "applyAgentEvent" -> reduceResultToChannel(
                c.applyAgentEvent(conversationId, map("event") ?: emptyMap(), mode, map("conversation")),
            )
            "applyAcpPromptResponse" -> reduceResultToChannel(
                c.applyAcpPromptResponse(
                    taskId = str("taskId"),
                    conversationId = conversationId,
                    sessionId = optStr("sessionId"),
                    turnId = optStr("turnId"),
                    stopReason = optStr("stopReason"),
                    error = optStr("error"),
                    mode = mode,
                    conversation = map("conversation"),
                ),
            )
            "clearTaskThinkingPresentation" -> c.clearTaskThinkingPresentation(
                str("taskId"), conversationId, mode, bool("removeCard", true),
            ).let { null }
            "clearConversationRuntimeSession" -> c.clearConversationRuntimeSession(conversationId, mode).let { null }
            "discardConversationRuntime" -> c.discardConversationRuntime(conversationId, mode).let { null }
            "interruptActiveToolCard" -> c.interruptActiveToolCard(conversationId, mode, optStr("summary")).let { null }
            "beginContextCompaction" -> c.beginContextCompaction(
                conversationId, mode, optStr("taskId"), optStr("trigger") ?: "manual",
                optInt("latestPromptTokens"), optInt("promptTokenThreshold"),
            ).let { null }
            "finishContextCompaction" -> c.finishContextCompaction(
                conversationId, mode, optStr("status") ?: "completed",
                optInt("latestPromptTokens"), optInt("promptTokenThreshold"),
            ).let { null }
            "updateChatIslandDisplayLayer" -> c.updateChatIslandDisplayLayer(conversationId, mode, str("layer")).let { null }
            "replaceConversationSnapshot" -> {
                c.replaceConversationSnapshot(
                    conversationId = conversationId,
                    mode = mode,
                    messages = messages("messages"),
                    conversation = map("conversation"),
                    isAiResponding = bool("isAiResponding"),
                    isContextCompressing = bool("isContextCompressing"),
                    isCheckingExecutableTask = bool("isCheckingExecutableTask"),
                    deepThinkingContent = optStr("deepThinkingContent") ?: "",
                    isDeepThinking = bool("isDeepThinking"),
                    currentDispatchTurnId = optStr("currentDispatchTurnId"),
                    currentThinkingStage = optInt("currentThinkingStage") ?: ThinkingStage.THINKING,
                    isInputAreaVisible = bool("isInputAreaVisible", true),
                    isExecutingTask = bool("isExecutingTask"),
                    lastAgentTurnId = optStr("lastAgentTurnId"),
                    activeToolCardId = optStr("activeToolCardId"),
                    activeThinkingCardId = optStr("activeThinkingCardId"),
                    activeContextCompactionMarkerId = optStr("activeContextCompactionMarkerId"),
                    pendingAgentTextTaskId = optStr("pendingAgentTextTaskId"),
                    pendingThinkingRoundSplit = bool("pendingThinkingRoundSplit"),
                    toolCardSequence = optInt("toolCardSequence") ?: 0,
                    thinkingRound = optInt("thinkingRound") ?: 0,
                    chatIslandDisplayLayer = optStr("chatIslandDisplayLayer") ?: ChatIslandDisplayLayer.MODE,
                    lastAgentToolType = optStr("lastAgentToolType"),
                    browserSessionSnapshot = map("browserSessionSnapshot"),
                    preserveLiveStreamingState = bool("preserveLiveStreamingState"),
                    basedOnRevision = asLong(args["basedOnRevision"]),
                    keepTextCaches = true,
                )
                null
            }
            "persistConversationMessageSnapshot" -> {
                awaitInBackground(
                    c.persistConversationMessageSnapshot(
                        conversationId, mode, messages("messages"), map("conversation"), bool("allowHistoryRemoval"),
                    ),
                )
                null
            }
            "persistRuntimeConversation" -> {
                awaitInBackground(
                    c.persistRuntimeConversation(
                        conversationId = conversationId,
                        mode = mode,
                        generateSummary = bool("generateSummary"),
                        markComplete = bool("markComplete"),
                        persistMessages = bool("persistMessages"),
                        allowEphemeralPersistence = bool("allowEphemeralPersistence"),
                        allowHistoryRemoval = bool("allowHistoryRemoval"),
                    ),
                )
                null
            }
            "schedulePersistRuntimeConversation" -> c.schedulePersistRuntimeConversation(
                conversationId = conversationId,
                mode = mode,
                generateSummary = bool("generateSummary"),
                markComplete = bool("markComplete"),
                persistMessages = bool("persistMessages"),
                delayMillis = asLong(args["delayMillis"]) ?: 350L,
            ).let { null }
            "updateRuntimePresentation" -> c.updateRuntimePresentation(
                conversationId, mode,
                isAiResponding = optBool("isAiResponding"),
                isContextCompressing = optBool("isContextCompressing"),
                isCheckingExecutableTask = optBool("isCheckingExecutableTask"),
                isExecutingTask = optBool("isExecutingTask"),
                isInputAreaVisible = optBool("isInputAreaVisible"),
                isDeepThinking = optBool("isDeepThinking"),
                deepThinkingContent = optStr("deepThinkingContent"),
                currentThinkingStage = optInt("currentThinkingStage"),
                chatIslandDisplayLayer = optStr("chatIslandDisplayLayer"),
            ).let { null }
            "setRuntimeDispatchTurnId" -> c.setRuntimeDispatchTurnId(conversationId, mode, optStr("turnId")).let { null }
            "setRuntimeLastAgentToolType" -> c.setRuntimeLastAgentToolType(conversationId, mode, optStr("toolType")).let { null }
            "setRuntimeBrowserSessionSnapshot" -> c.setRuntimeBrowserSessionSnapshot(conversationId, mode, map("snapshot")).let { null }
            "setRuntimeConversation" -> c.setRuntimeConversation(conversationId, mode, map("conversation")).let { null }
            "insertRuntimeMessage" -> c.insertRuntimeMessage(conversationId, mode, message("message"), optInt("index") ?: 0).let { null }
            "appendRuntimeMessages" -> c.appendRuntimeMessages(conversationId, mode, messages("messages")).let { null }
            "replaceRuntimeMessage" -> c.replaceRuntimeMessage(conversationId, mode, str("messageId"), message("message"))
            "removeRuntimeMessages" -> c.removeRuntimeMessages(
                conversationId, mode, (args["messageIds"] as? List<*>).orEmpty().mapNotNull { dartToString(it) },
            ).let { null }
            "removeLeadingRuntimeMessages" -> c.removeLeadingRuntimeMessages(conversationId, mode, int("count")).let { null }
            "replaceRuntimeMessages" -> c.replaceRuntimeMessages(conversationId, mode, messages("messages")).let { null }
            "ensureRemoteThreadRuntime" -> c.ensureRemoteThreadRuntime(str("threadId"))
            "activateRemoteThreadRuntime" -> c.activateRemoteThreadRuntime(
                str("threadId"), messages("fallbackMessages"), map("conversation"),
            )
            "setRoutingContexts" -> {
                val contexts = LinkedHashMap<Int, ChatRuntimeRoutingContext>()
                for (entry in (args["contexts"] as? List<*>).orEmpty()) {
                    val raw = copyStringMap(entry) ?: continue
                    val id = asInt(raw["hostId"]) ?: continue
                    contexts[id] = routingContextFromChannel(raw)
                }
                setRoutingContexts(contexts)
                null
            }
            "resync" -> resync(conversationId, mode).also { capture?.add(it) }.let { null }
            "invalidateVoiceAvailability" -> invalidateVoiceAvailability().let { null }
            "setSurfaceGeneration" -> setSurfaceGeneration(str("surfaceId"), asLong(args["generation"]) ?: 0L).let { null }
            else -> throw UnsupportedOperationException("Unknown chat runtime command: $method")
        }
    }

    /**
     * Async persistence commands answer the UI only after the durable write,
     * like the Dart futures they replace. The channel reply is delivered by
     * [ChatRuntimeHost.handleAsyncCommand]; the synchronous path returns
     * immediately for fire-and-forget callers.
     */
    private var pendingAsync: kotlinx.coroutines.Deferred<Unit>? = null

    private fun awaitInBackground(deferred: kotlinx.coroutines.Deferred<Unit>) {
        pendingAsync = deferred
    }

    /**
     * Channel entry: runs the command and replies when any durable write it
     * started has finished (or failed, which is reported as an error).
     */
    fun handleAsyncCommand(
        method: String,
        args: Map<String, Any?>,
        reply: (Result<Map<String, Any?>>) -> Unit,
    ) {
        pendingAsync = null
        val response = runCatching { handleCommand(method, args) }
        val deferred = pendingAsync
        pendingAsync = null
        if (response.isFailure || deferred == null) {
            reply(response)
            return
        }
        scope.launch {
            val written = runCatching { deferred.await() }
            reply(written.map { response.getOrThrow() })
        }
    }

    /** `flushAllPendingPersistence` has no synchronous part. */
    fun flushAllPendingPersistence(reply: (Result<Unit>) -> Unit) {
        scope.launch { reply(runCatching { coordinator.flushAllPendingPersistence() }) }
    }

    fun flushPendingPersistence(conversationId: Int, mode: String, reply: (Result<Unit>) -> Unit) {
        scope.launch { reply(runCatching { coordinator.flushPendingPersistence(conversationId, mode) }) }
    }

    private fun routingContextFromChannel(raw: Map<String, Any?>): ChatRuntimeRoutingContext {
        val idsByMode = copyStringMap(raw["conversationIdsByMode"]).orEmpty().mapValues { asInt(it.value) }
        val conversationsByMode = copyStringMap(raw["conversationsByMode"]).orEmpty().mapValues { copyStringMap(it.value) }
        val remote = copyStringMap(raw["remote"])?.let { r ->
            ChatRuntimeRemoteRoutingContext(
                activeThreadId = dartToString(r["activeThreadId"]),
                activeRemoteRuntimeId = asInt(r["activeRemoteRuntimeId"]),
                agentFallbackMessages = (r["agentFallbackMessages"] as? List<*>).orEmpty()
                    .mapNotNull { copyStringMap(it)?.let(ChatMessage::fromJson) },
                agentConversation = copyStringMap(r["agentConversation"]),
            )
        }
        return ChatRuntimeRoutingContext(
            dispatchScoped = raw["dispatchScoped"] == true,
            activeMode = dartToString(raw["activeMode"]) ?: CHAT_RUNTIME_MODE_AGENT,
            conversationIdsByMode = idsByMode,
            conversationsByMode = conversationsByMode,
            remote = remote,
            scopedConversationId = asInt(raw["scopedConversationId"]),
            scopedConversation = copyStringMap(raw["scopedConversation"]),
        )
    }

    private fun reduceResultToChannel(result: AgentReduceResult): Map<String, Any?> = linkedMapOf(
        "handled" to result.handled,
        "method" to result.method,
        "threadId" to result.threadId,
        "turnId" to result.turnId,
        "requestId" to result.requestId,
        "collaborationMode" to result.collaborationMode,
        "compatibilityWarning" to result.compatibilityWarning,
        "affectsActiveTurn" to result.affectsActiveTurn,
    )

    init {
        Log.d(TAG, "chat runtime host created")
    }
}
