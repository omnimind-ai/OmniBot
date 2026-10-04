package cn.com.omnimind.bot.agent.projection

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Cancellable one-shot timer (Dart `Timer`). */
fun interface ChatRuntimeTimer {
    fun cancel()
}

/** Schedules [block] after [delayMillis] on the coordinator's thread. */
fun interface ChatRuntimeScheduler {
    fun schedule(delayMillis: Long, block: () -> Unit): ChatRuntimeTimer
}

/**
 * The single owner of chat runtime projection: the Kotlin port of Dart
 * `ChatConversationRuntimeCoordinator` (+ its part files).
 *
 * Runtimes are keyed by `(conversationId, mode)`. ACP events enter through
 * [applyAgentEvent] / [routeAgentEvent] and `session/prompt` results through
 * [applyAcpPromptResponse]; both use the one [AgentEventReducer]. The
 * coordinator is confined to one thread (the caller's): every public method
 * must be called from it, and [scope] / [scheduler] must resume on it.
 *
 * Changes are published as immutable [ChatRuntimeSnapshot]s to [listeners]
 * whenever the Dart owner called `notifyListeners`; page write commands mark
 * their runtime dirty without notifying, exactly like the direct field
 * writes they replace, and are published with the next notification or
 * [publishDirtySnapshots].
 */
class ChatConversationRuntimeCoordinator(
    private val persistence: ChatRuntimePersistence,
    private val voice: ChatRuntimeVoice,
    private val scope: CoroutineScope,
    private val scheduler: ChatRuntimeScheduler,
    private val isEnglish: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class TaskBinding(val conversationId: Int, val mode: String)

    private class PendingPersistenceRequest(
        val conversationId: Int,
        val mode: String,
        val timer: ChatRuntimeTimer,
        val generateSummary: Boolean = false,
        val markComplete: Boolean = false,
        val persistMessages: Boolean = false,
    )

    /** Receives the snapshots of runtimes changed by one notification. */
    fun interface Listener {
        fun onRuntimesChanged(snapshots: List<ChatRuntimeSnapshot>, removedKeys: List<String>)
    }

    private val reducer = AgentEventReducer()
    private val runtimes = LinkedHashMap<String, ChatConversationRuntimeState>()
    private val taskBindings = LinkedHashMap<String, TaskBinding>()
    private val pendingPersistence = LinkedHashMap<String, PendingPersistenceRequest>()

    // One ordered tail per runtime so an older snapshot can never finish
    // after a newer one and move durable history backwards.
    private val persistenceTails = LinkedHashMap<String, Deferred<Unit>>()
    private val ephemeralRuntimeKeys = LinkedHashSet<String>()
    private val eventHosts = ArrayList<ChatRuntimeEventHost>()
    private val listeners = ArrayList<Listener>()
    private val dirtyKeys = LinkedHashSet<String>()
    private val removedKeys = LinkedHashSet<String>()
    private val revisions = HashMap<String, Long>()

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    // ---------------------------------------------------------------- reads

    fun snapshotFor(conversationId: Int, mode: String): ChatRuntimeSnapshot? {
        val key = runtimeKey(conversationId, mode)
        val state = runtimes[key] ?: return null
        return ChatRuntimeSnapshot.of(state, revisions[key] ?: 0L, key in ephemeralRuntimeKeys)
    }

    fun allSnapshots(): List<ChatRuntimeSnapshot> = runtimes.keys.mapNotNull { key ->
        runtimes[key]?.let { ChatRuntimeSnapshot.of(it, revisions[key] ?: 0L, key in ephemeralRuntimeKeys) }
    }

    /** Conversation ids with live work in the shared ACP projection. */
    val activeAgentConversationIds: Set<Int>
        get() = runtimes.values
            .filter { it.mode == CHAT_RUNTIME_MODE_AGENT && it.hasInFlightTask }
            .mapTo(LinkedHashSet()) { it.conversationId }

    fun isAgentConversationActive(conversationId: Int): Boolean =
        stateFor(conversationId, CHAT_RUNTIME_MODE_AGENT)?.hasInFlightTask ?: false

    /**
     * Resolves an ACP event to the runtime that admitted its turn. The
     * `(conversationId, turnId)` binding is authoritative; mode is UI data.
     */
    fun modeForAcpEvent(conversationId: Int, sessionId: String? = null, turnId: String? = null): String? {
        val normalizedSessionId = sessionId?.trim().orEmpty()
        val normalizedTurnId = turnId?.trim().orEmpty()
        if (normalizedTurnId.isEmpty() && normalizedSessionId.isEmpty()) return null
        for (mode in listOf(CHAT_RUNTIME_MODE_NORMAL, CHAT_RUNTIME_MODE_AGENT, CHAT_RUNTIME_MODE_OPENCLAW)) {
            val runtime = stateFor(conversationId, mode) ?: continue
            if ((normalizedSessionId.isNotEmpty() && runtime.activeAcpSessionId == normalizedSessionId) ||
                runtime.activeAcpTurnId == normalizedTurnId ||
                runtime.currentDispatchTurnId == normalizedTurnId ||
                runtime.lastAgentTurnId == normalizedTurnId
            ) {
                return mode
            }
        }
        return null
    }

    /** The conversation that first claimed a legacy process identity. */
    fun conversationIdForStandaloneProcess(processId: String): Int? {
        val normalized = processId.trim()
        if (normalized.isEmpty()) return null
        for (runtime in runtimes.values) {
            if (runtime.standaloneProcessRunIds.containsKey(normalized)) return runtime.conversationId
        }
        return null
    }

    /**
     * The conversation owning a session/turn when an event lacks the host
     * conversation id, so the visible page never becomes the implicit owner
     * of a background run.
     */
    fun conversationIdForAcpEvent(sessionId: String? = null, turnId: String? = null): Int? {
        val normalizedSessionId = sessionId?.trim().orEmpty()
        val normalizedTurnId = turnId?.trim().orEmpty()
        if (normalizedSessionId.isEmpty() && normalizedTurnId.isEmpty()) return null
        for (runtime in runtimes.values) {
            if (normalizedSessionId.isNotEmpty() &&
                (runtime.activeAcpSessionId == normalizedSessionId || normalizedSessionId in runtime.knownAcpSessionIds)
            ) {
                return runtime.conversationId
            }
            if (normalizedTurnId.isEmpty()) continue
            if (runtime.activeAcpTurnId == normalizedTurnId ||
                runtime.currentDispatchTurnId == normalizedTurnId ||
                runtime.lastAgentTurnId == normalizedTurnId ||
                normalizedTurnId in runtime.completedAgentTurnIds ||
                normalizedTurnId in runtime.completedAcpTurnIds ||
                runtime.acpTurnToRunIds.keys.any { it == normalizedTurnId || it.endsWith(":$normalizedTurnId") }
            ) {
                return runtime.conversationId
            }
        }
        return null
    }

    fun isEphemeralRuntime(conversationId: Int, mode: String): Boolean =
        runtimeKey(conversationId, mode) in ephemeralRuntimeKeys

    /** Whether [taskId] still owns the live local turn of this runtime. */
    fun isTaskActive(taskId: String, conversationId: Int, mode: String): Boolean {
        val runtime = stateFor(conversationId, mode)
        val binding = taskBindings[taskId]
        if (runtime == null || binding == null || binding.conversationId != conversationId || binding.mode != mode) {
            return false
        }
        return runtime.activeRunId == taskId || runtime.currentDispatchTurnId == taskId ||
            runtime.lastAgentTurnId == taskId
    }

    // ------------------------------------------------------------ lifecycle

    fun ensureRuntime(
        conversationId: Int,
        mode: String,
        initialMessages: List<ChatMessage>? = null,
        conversation: Map<String, Any?>? = null,
        initialChatIslandDisplayLayer: String? = null,
    ): ChatRuntimeSnapshot {
        val state = ensureRuntimeState(conversationId, mode, initialMessages, conversation, initialChatIslandDisplayLayer)
        markDirty(state)
        return snapshotFor(conversationId, mode)!!
    }

    fun ensureEphemeralRuntime(
        conversationId: Int,
        mode: String,
        initialMessages: List<ChatMessage>? = null,
        conversation: Map<String, Any?>? = null,
        initialChatIslandDisplayLayer: String? = null,
    ): ChatRuntimeSnapshot {
        ensureEphemeralRuntimeState(conversationId, mode, initialMessages, conversation, initialChatIslandDisplayLayer)
        return snapshotFor(conversationId, mode)!!
    }

    fun registerTask(taskId: String, conversationId: Int, mode: String) {
        val existingBinding = taskBindings[taskId]
        if (existingBinding != null &&
            (existingBinding.conversationId != conversationId || existingBinding.mode != mode)
        ) {
            // A remote/local handoff can move the same submission to another
            // runtime; do not strand the old runtime as an invisible turn.
            unregisterTask(taskId, conversationId = existingBinding.conversationId, mode = existingBinding.mode)
        }
        val runtime = ensureRuntimeState(conversationId, mode)
        // A new prompt starts with a local render key; never let a previous
        // turn's official id claim the new prompt's terminal event.
        if (runtime.currentDispatchTurnId != taskId) {
            runtime.activeAcpTurnId = null
            runtime.activeRunId = taskId
        }
        if (runtime.activeRunId == null) runtime.activeRunId = taskId
        taskBindings[taskId] = TaskBinding(conversationId, mode)
        markDirty(runtime)
    }

    fun beginAcpTurn(taskId: String, conversationId: Int, mode: String) {
        val existingBinding = taskBindings[taskId]
        val existingRuntime = stateFor(conversationId, mode)
        val alreadyStarted = existingBinding?.conversationId == conversationId &&
            existingBinding.mode == mode &&
            existingRuntime?.isAiResponding == true &&
            existingRuntime.currentDispatchTurnId == taskId &&
            existingRuntime.lastAgentTurnId == taskId
        registerTask(taskId, conversationId, mode)
        if (alreadyStarted) return
        val runtime = ensureRuntimeState(conversationId, mode)
        runtime.persistenceGeneration += 1
        runtime.isAiResponding = true
        runtime.currentDispatchTurnId = taskId
        runtime.agentEntryStartTimes["prompt:$taskId"] = clock()
        runtime.activeRunId = taskId
        runtime.lastAgentTurnId = taskId
        runtime.currentThinkingStage = ThinkingStage.THINKING
        runtime.allowRetiredAcpSessionReactivation = true
        notifyListeners(runtime)
    }

    /**
     * Records the official ACP session after `session/new` and before
     * `session/prompt`: an identity reservation, not a second lifecycle.
     */
    fun bindAcpSession(taskId: String, conversationId: Int, mode: String, sessionId: String): Boolean {
        val normalizedSessionId = sessionId.trim()
        if (normalizedSessionId.isEmpty() || !isTaskActive(taskId, conversationId, mode)) return false
        val runtime = stateFor(conversationId, mode) ?: return false
        val currentSessionId = runtime.activeAcpSessionId?.trim().orEmpty()
        val currentTurnId = runtime.activeAcpTurnId?.trim().orEmpty()
        if (currentSessionId.isNotEmpty() && currentSessionId != normalizedSessionId && currentTurnId.isNotEmpty()) {
            return false
        }
        runtime.activeAcpSessionId = normalizedSessionId
        runtime.knownAcpSessionIds.add(normalizedSessionId)
        runtime.retiredAcpSessionIds.remove(normalizedSessionId)
        notifyListeners(runtime)
        return true
    }

    /**
     * Commits a terminal transition proven by an authoritative remote
     * session snapshot (remote ACP read path only).
     */
    fun finishTaskFromAuthoritativeSnapshot(
        taskId: String,
        conversationId: Int,
        mode: String,
        sessionId: String? = null,
        turnId: String? = null,
    ): Boolean {
        val runtime = stateFor(conversationId, mode)
        if (runtime == null || !isTaskActive(taskId, conversationId, mode)) return false
        val incomingSessionId = sessionId?.trim().orEmpty()
        val activeSessionId = runtime.activeAcpSessionId?.trim().orEmpty()
        if (incomingSessionId.isNotEmpty() && activeSessionId.isNotEmpty() && incomingSessionId != activeSessionId) {
            return false
        }
        val incomingTurnId = turnId?.trim().orEmpty()
        val activeTurnId = runtime.activeAcpTurnId?.trim().orEmpty()
        if (incomingTurnId.isNotEmpty() && activeTurnId.isNotEmpty() && incomingTurnId != activeTurnId) return false
        // Establish the protocol-to-local mapping from validated identities
        // before delegating to the one terminal cleanup path.
        if (activeTurnId.isNotEmpty()) {
            runtime.resolveRunId(
                sessionId = if (activeSessionId.isEmpty()) incomingSessionId else activeSessionId,
                turnId = activeTurnId,
                fallback = taskId,
            )
        }
        unregisterTask(taskId, conversationId = conversationId, mode = mode)
        return true
    }

    /** Compatibility names: starting a turn stays presentation-free. */
    fun primeAcpThinking(taskId: String, conversationId: Int, mode: String) =
        beginAcpTurn(taskId, conversationId, mode)

    fun primePureChatThinking(taskId: String, conversationId: Int, mode: String) =
        beginAcpTurn(taskId, conversationId, mode)

    fun unregisterTask(taskId: String, conversationId: Int? = null, mode: String? = null) {
        val binding = taskBindings[taskId]
        // A delayed callback for an older binding is a no-op; resolving by
        // the bare task id would clean the newer turn.
        if (conversationId != null && (binding == null || binding.conversationId != conversationId)) return
        if (mode != null && (binding == null || binding.mode != mode.trim())) return
        val runtime = runtimeForTask(taskId)
        if (runtime != null) {
            // Fence both identity spaces: a late update for either id must
            // not become the first event of the next prompt.
            val officialTurnId = runtime.activeAcpTurnId?.trim()
            rememberCompletedTurn(runtime, taskId)
            if (!officialTurnId.isNullOrEmpty()) {
                rememberCompletedTurn(runtime, officialTurnId)
                runtime.rememberCompletedAcpTurn(officialTurnId)
            }
            runtime.currentAiMessages.remove(taskId)
            runtime.currentThinkingMessages.remove(taskId)
            if (runtime.currentDispatchTurnId == taskId) runtime.currentDispatchTurnId = null
            if (runtime.activeRunId == taskId) runtime.activeRunId = null
            if (runtime.activeAcpTurnId == taskId || acpTurnBelongsToTask(runtime, runtime.activeAcpTurnId, taskId)) {
                runtime.activeAcpTurnId = null
            }
            if (runtime.lastAgentTurnId == taskId) runtime.lastAgentTurnId = null
            // A late cleanup from an older turn must not tear down a newer one.
            val hasAnotherTurn = runtime.currentDispatchTurnId != null ||
                runtime.lastAgentTurnId != null || runtime.activeAcpTurnId != null
            if (!hasAnotherTurn) {
                runtime.activeAcpSessionId = null
                runtime.isAiResponding = false
                runtime.isExecutingTask = false
                runtime.isCheckingExecutableTask = false
                runtime.isContextCompressing = false
                runtime.deepThinkingContent = ""
                runtime.isDeepThinking = false
                runtime.isInputAreaVisible = true
                runtime.currentThinkingStage = ThinkingStage.THINKING
                runtime.activeToolCardId = null
                runtime.activeThinkingCardId = null
                runtime.pendingAgentTextTaskId = null
                runtime.waitingThinkingBeforeAgentTextTaskId = null
                runtime.pendingThinkingRoundSplit = false
            }
            notifyListeners(runtime)
        }
        taskBindings.remove(taskId)
    }

    private fun acpTurnBelongsToTask(runtime: ChatConversationRuntimeState, turnId: String?, taskId: String): Boolean {
        val normalizedTurnId = turnId?.trim().orEmpty()
        if (normalizedTurnId.isEmpty()) return false
        if (runtime.acpTurnToRunIds[acpTurnKey(turnId = normalizedTurnId)] == taskId) return true
        return runtime.acpTurnToRunIds.entries.any {
            it.value == taskId && (it.key == normalizedTurnId || it.key.endsWith(":$normalizedTurnId"))
        }
    }

    private fun rememberCompletedTurn(runtime: ChatConversationRuntimeState, turnId: String) {
        val normalized = turnId.trim()
        if (normalized.isEmpty()) return
        runtime.completedAgentTurnIds.add(normalized)
    }

    // ------------------------------------------------------------ projection

    fun applyAgentEvent(
        conversationId: Int,
        event: Map<String, Any?>,
        mode: String = CHAT_RUNTIME_MODE_AGENT,
        conversation: Map<String, Any?>? = null,
    ): AgentReduceResult {
        val runtime = ensureRuntimeState(
            conversationId = conversationId,
            mode = mode,
            conversation = conversation,
            initialChatIslandDisplayLayer = ChatIslandDisplayLayer.MODE,
        )
        val eventSessionId = acpEventSessionId(event)
        val eventTurnId = acpEventTurnId(event)
        val presentation = acpEventPresentation(event)
        val carriesFinalTurnUsage = acpEventCarriesFinalTurnUsage(event)
        val allowsHostSessionAdmission = acpEventAllowsImplicitTurnAdmission(event)
        val activeAcpTurn = runtime.activeAcpTurnId?.trim().orEmpty()
        val currentDispatchTurn = runtime.currentDispatchTurnId?.trim().orEmpty()
        val incomingTurn = eventTurnId?.trim().orEmpty()
        val isKnownCurrentTurn = incomingTurn.isNotEmpty() &&
            (incomingTurn == activeAcpTurn || incomingTurn == currentDispatchTurn ||
                incomingTurn == runtime.lastAgentTurnId?.trim().orEmpty())
        // New ACP traffic must carry its session identity, except an already
        // known turn or an explicit host reservation; the legacy shape stays
        // a named compatibility exception.
        if (eventSessionId == null && incomingTurn.isNotEmpty() && !isKnownCurrentTurn &&
            !allowsHostSessionAdmission && !acpEventIsLegacyCompatibilityShape(event)
        ) {
            return AgentReduceResult(handled = false, affectsActiveTurn = false)
        }
        if (!runtime.acceptsAcpEvent(
                sessionId = eventSessionId,
                turnId = eventTurnId,
                allowCompletedTurnMetadata = carriesFinalTurnUsage,
                allowSessionAdmission = allowsHostSessionAdmission,
            )
        ) {
            return AgentReduceResult(handled = false, affectsActiveTurn = false)
        }
        // Stale events are consumed by the reducer but do not own the visible
        // turn; capture ownership before a terminal event clears it.
        val activeAcpTurnBefore = runtime.activeAcpTurnId?.trim().orEmpty()
        val dispatchTurnBefore = runtime.currentDispatchTurnId?.trim().orEmpty()
        val affectsActiveTurn = when {
            eventTurnId == null -> runtime.isAiResponding && dispatchTurnBefore.isNotEmpty() && activeAcpTurnBefore.isEmpty()
            activeAcpTurnBefore.isEmpty() -> runtime.isAiResponding && dispatchTurnBefore.isNotEmpty()
            else -> activeAcpTurnBefore == eventTurnId
        }
        val result = reducer.reduce(runtime, event).copyWith(affectsActiveTurn = affectsActiveTurn)
        if (result.handled) {
            annotateAgentMessages(runtime, event, result)
            notifyAcpVoicePlayback(runtime, event, result)
            if (presentation?.get("compaction") is Map<*, *>) {
                val marker = runtime.messages.firstOrNull {
                    it.type == 2 && it.cardData?.get("type") == "context_compaction_marker"
                }
                if (marker != null) persistContextCompactionMarkerIfNeeded(conversationId, mode, marker)
            }
            notifyListeners(runtime)
            if (!isEphemeralRuntime(conversationId, mode)) {
                // Persist into the runtime that admitted the event: ACP can be
                // hosted by the normal chat runtime as well as the Agent page.
                schedulePersistRuntimeConversation(
                    conversationId = conversationId,
                    mode = mode,
                    persistMessages = true,
                    // Exact usage can trail turn/completed; persist it now.
                    delayMillis = if (carriesFinalTurnUsage) 0L else 350L,
                )
            }
        }
        return result
    }

    /**
     * Applies the official `session/prompt` result through the same reducer
     * and persistence path as streamed updates: the canonical terminal
     * boundary, never a synthesized `turn` event.
     */
    fun applyAcpPromptResponse(
        taskId: String,
        conversationId: Int,
        sessionId: String?,
        turnId: String? = null,
        stopReason: String? = null,
        error: String? = null,
        mode: String = CHAT_RUNTIME_MODE_AGENT,
        conversation: Map<String, Any?>? = null,
    ): AgentReduceResult {
        // The awaited result belongs to the request that sent it; never lend
        // a later request's identity to an old response.
        if (!isTaskActive(taskId, conversationId, mode)) {
            return AgentReduceResult(handled = false, affectsActiveTurn = false)
        }
        val runtime = ensureRuntimeState(
            conversationId = conversationId,
            mode = mode,
            conversation = conversation,
            initialChatIslandDisplayLayer = ChatIslandDisplayLayer.MODE,
        )
        val result = reducer.reducePromptResponse(
            runtime,
            sessionId = sessionId,
            turnId = turnId,
            stopReason = stopReason,
            error = error,
        )
        taskBindings.remove(taskId)
        if (result.handled) {
            notifyListeners(runtime)
            if (!isEphemeralRuntime(conversationId, mode)) {
                schedulePersistRuntimeConversation(
                    conversationId = conversationId,
                    mode = mode,
                    persistMessages = true,
                    delayMillis = 0L,
                )
            }
        }
        return result
    }

    /**
     * Keeps ACP assistant text on the shared voice path. Voice is a
     * presentation side effect, so it lives at the coordinator boundary.
     */
    private fun notifyAcpVoicePlayback(
        runtime: ChatConversationRuntimeState,
        event: Map<String, Any?>,
        result: AgentReduceResult,
    ) {
        val method = result.method
        if (method != "item/agentMessage/delta" && method != "turn/completed" &&
            method != "thread/closed" && method != "turn/failed"
        ) {
            return
        }
        val message = copyStringMap(event["message"]) ?: event
        val params = copyStringMap(event["params"]) ?: copyStringMap(message["params"]) ?: emptyMap()
        val update = copyStringMap(params["update"])
        val taskId = runtime.resolveAcpEventRunId(
            sessionId = firstString(
                event["sessionId"], event["session_id"], params["sessionId"], params["session_id"],
                update?.get("sessionId"),
            ),
            turnId = result.turnId ?: firstString(
                event["turnId"], event["turn_id"], params["turnId"], params["turn_id"], update?.get("turnId"),
            ),
            fallback = runtime.lastAgentTurnId ?: runtime.currentDispatchTurnId,
        )

        if (method == "item/agentMessage/delta") {
            val itemId = firstString(
                params["entryId"], params["itemId"], params["item_id"], update?.get("entryId"), update?.get("messageId"),
            )
            val candidates = runtime.messages.filter {
                it.type == 1 && it.user == 2 &&
                    (taskId == null || dartToString(it.streamMeta?.get("parentTaskId")) == taskId)
            }
            var assistantMessage: ChatMessage? = null
            if (itemId != null) {
                assistantMessage = candidates.firstOrNull {
                    it.id == itemId || it.id.contains(itemId) || dartToString(it.streamMeta?.get("entryId")) == itemId
                }
            }
            if (assistantMessage == null) assistantMessage = candidates.firstOrNull()
            val assistantText = assistantMessage?.text?.trim().orEmpty()
            if (assistantMessage == null || assistantText.isEmpty()) return
            voice.onAssistantMessageUpdated(assistantMessage.id, assistantText, isFinal = false)
            return
        }

        val officialTurnId = result.turnId?.trim().orEmpty()
        val assistantMessage = runtime.messages.firstOrNull {
            if (it.type != 1 || it.user != 2) return@firstOrNull false
            val streamTurnId = dartToString(it.streamMeta?.get("turnId"))?.trim()
            val parentTaskId = dartToString(it.streamMeta?.get("parentTaskId"))?.trim()
            (officialTurnId.isNotEmpty() && streamTurnId == officialTurnId) ||
                (taskId != null && parentTaskId == taskId)
        }
        val assistantText = assistantMessage?.text?.trim().orEmpty()
        if (assistantMessage == null || assistantText.isEmpty()) return
        voice.onAssistantMessageCompleted(assistantMessage.id, assistantText)
    }

    private fun annotateAgentMessages(
        runtime: ChatConversationRuntimeState,
        event: Map<String, Any?>,
        result: AgentReduceResult,
    ) {
        val envelope = copyStringMap(event["message"])
        val params = copyStringMap(event["params"]) ?: copyStringMap(envelope?.get("params"))
        val agentId = stringOrNull(event["agentId"]) ?: stringOrNull(params?.get("agentId"))
            ?: stringOrNull(envelope?.get("agentId")) ?: return
        val agentName = stringOrNull(event["agentName"]) ?: stringOrNull(params?.get("agentName"))
            ?: stringOrNull(envelope?.get("agentName"))
        val protocolTurnId = result.turnId ?: stringOrNull(event["turnId"]) ?: stringOrNull(params?.get("turnId"))
        val protocolSessionId = stringOrNull(event["sessionId"]) ?: stringOrNull(params?.get("sessionId"))
            ?: stringOrNull(envelope?.get("sessionId"))
        val taskId = runtime.resolveAcpEventRunId(
            sessionId = protocolSessionId,
            turnId = protocolTurnId,
            fallback = runtime.activeRunId ?: runtime.currentDispatchTurnId,
        )
        for (index in runtime.messages.indices) {
            val message = runtime.messages[index]
            if (message.agentId != null) continue
            val cardData = message.cardData
            val isAcpMessage = message.id.contains("-agent-") || message.id.contains("-codex-") ||
                isAgentToolUiStyle(cardData?.get("uiStyle")) || isAgentRequestCardType(cardData?.get("type"))
            if (!isAcpMessage) continue
            val parentTaskId = stringOrNull(
                message.streamMeta?.get("parentTaskId") ?: message.streamMeta?.get("runId")
                    ?: cardData?.get("taskId") ?: cardData?.get("runId") ?: cardData?.get("taskID"),
            )
            if (taskId != null && parentTaskId != null && parentTaskId != taskId) continue
            val content: JsonMap = LinkedHashMap(message.content ?: emptyMap())
            content["agentId"] = agentId
            if (agentName != null) content["agentName"] = agentName
            if (cardData != null) {
                content["cardData"] = LinkedHashMap(cardData).apply {
                    put("agentId", agentId)
                    if (agentName != null) put("agentName", agentName)
                    if (protocolSessionId != null) put("sessionId", protocolSessionId)
                }
            }
            runtime.messages[index] = message.copyWith(content = content)
        }
    }

    fun clearPureChatThinking(taskId: String, conversationId: Int, mode: String, removeCard: Boolean = true) =
        clearTaskThinkingPresentation(taskId, conversationId, mode, removeCard)

    /**
     * Removes the optimistic thinking surface when a turn fails before its
     * first official ACP update.
     */
    fun clearTaskThinkingPresentation(taskId: String, conversationId: Int, mode: String, removeCard: Boolean = true) {
        val runtime = stateFor(conversationId, mode)
        val binding = taskBindings[taskId]
        if (runtime == null || binding == null || binding.conversationId != conversationId || binding.mode != mode) {
            return
        }
        val ownsActiveThinkingState = runtime.activeRunId == taskId ||
            runtime.currentDispatchTurnId == taskId || runtime.lastAgentTurnId == taskId
        runtime.currentThinkingMessages.remove(taskId)
        if (ownsActiveThinkingState) {
            runtime.deepThinkingContent = ""
            runtime.isDeepThinking = false
        }
        if (runtime.lastAgentTurnId == taskId) runtime.lastAgentTurnId = null
        val activeThinkingCardId = runtime.activeThinkingCardId
        if (ownsActiveThinkingState && activeThinkingCardId != null &&
            (activeThinkingCardId == taskId || activeThinkingCardId.startsWith("$taskId-thinking"))
        ) {
            runtime.activeThinkingCardId = null
        }
        if (ownsActiveThinkingState) {
            runtime.pendingThinkingRoundSplit = false
            runtime.thinkingRound = 0
        }
        if (removeCard) {
            runtime.messages.removeWhere {
                it.type == 2 && it.cardData?.get("type") == "deep_thinking" &&
                    dartToString(it.cardData?.get("taskID") ?: "") == taskId
            }
        }
        notifyListeners(runtime)
    }

    fun clearConversationRuntimeSession(conversationId: Int, mode: String) {
        val runtime = stateFor(conversationId, mode) ?: return
        runtime.persistenceGeneration += 1
        // A lifecycle boundary: fence local and official identities and drop
        // the binding so late callbacks cannot reach a reused conversation.
        val localRunId = runtime.activeRunId?.trim() ?: runtime.currentDispatchTurnId?.trim()
            ?: runtime.lastAgentTurnId?.trim() ?: ""
        val officialTurnId = runtime.activeAcpTurnId?.trim().orEmpty()
        if (localRunId.isNotEmpty()) rememberCompletedTurn(runtime, localRunId)
        if (officialTurnId.isNotEmpty()) {
            rememberCompletedTurn(runtime, officialTurnId)
            runtime.rememberCompletedAcpTurn(officialTurnId)
        }
        taskBindings.entries.removeIf { it.value.conversationId == conversationId && it.value.mode == mode }
        val sessionsToRetire = LinkedHashSet(runtime.knownAcpSessionIds)
        runtime.activeAcpSessionId?.trim()?.takeIf { it.isNotEmpty() }?.let { sessionsToRetire.add(it) }
        runtime.retiredAcpSessionIds.addAll(sessionsToRetire)
        runtime.allowRetiredAcpSessionReactivation = false
        runtime.currentDispatchTurnId = null
        runtime.activeRunId = null
        runtime.activeAcpTurnId = null
        runtime.activeAcpSessionId = null
        runtime.isAiResponding = false
        runtime.isExecutingTask = false
        runtime.isCheckingExecutableTask = false
        runtime.isContextCompressing = false
        runtime.deepThinkingContent = ""
        runtime.isDeepThinking = false
        runtime.currentThinkingMessages.clear()
        runtime.currentAcpUserMessages.clear()
        runtime.currentAiMessages.clear()
        runtime.standaloneProcessRunIds.clear()
        runtime.agentReplayDeltaOffsets.clear()
        runtime.pendingAcpPerformanceMetrics.clear()
        runtime.pendingAcpReasoningCardData.clear()
        runtime.pendingAcpAssistantPresentation.clear()
        runtime.processedAcpEventIds.clear()
        runtime.acpCompatibilityWarningShown = false
        runtime.availableAcpCommands = emptyList()
        runtime.acpConfigOptions = emptyList()
        runtime.currentAcpModeId = null
        runtime.acpSessionInfo = linkedMapOf()
        runtime.acpExtensionUpdates.clear()
        runtime.currentThinkingStage = ThinkingStage.THINKING
        runtime.lastAgentTurnId = null
        runtime.pendingAgentTextTaskId = null
        runtime.waitingThinkingBeforeAgentTextTaskId = null
        runtime.activeToolCardId = null
        runtime.activeThinkingCardId = null
        runtime.activeContextCompactionMarkerId = null
        runtime.pendingThinkingRoundSplit = false
        runtime.toolCardSequence = 0
        runtime.thinkingRound = 0
        runtime.streamingTextBatches.clear()
        runtime.agentEntrySequences.clear()
        runtime.agentEntryStartTimes.clear()
        runtime.agentNextEntrySequence = 0
        notifyListeners(runtime)
    }

    fun discardConversationRuntime(conversationId: Int, mode: String) {
        cancelPendingPersistence(conversationId, mode)
        val key = runtimeKey(conversationId, mode)
        ephemeralRuntimeKeys.remove(key)
        taskBindings.entries.removeIf { it.value.conversationId == conversationId && it.value.mode == mode }
        val removed = runtimes.remove(key)
        if (removed != null) {
            removed.dispose()
            dirtyKeys.remove(key)
            revisions.remove(key)
            removedKeys.add(key)
            publish()
        }
    }

    fun interruptActiveToolCard(conversationId: Int, mode: String, summary: String? = null) {
        val runtime = stateFor(conversationId, mode) ?: return
        val activeCard = runtime.activeToolCardId?.let { id -> runtime.messages.firstOrNull { it.id == id } }
        val taskId = dartToString(
            runtime.activeRunId ?: runtime.currentDispatchTurnId
                ?: activeCard?.cardData?.get("taskId") ?: activeCard?.cardData?.get("taskID"),
        )?.trim().orEmpty()
        if (taskId.isEmpty()) return
        var changed = false
        for (index in runtime.messages.indices) {
            val message = runtime.messages[index]
            val cardData = message.cardData ?: continue
            if (cardData["type"] != AGENT_TOOL_SUMMARY_CARD_TYPE && cardData["type"] != AGENT_REQUEST_CARD_TYPE) continue
            val cardTaskId = dartToString(cardData["taskId"] ?: cardData["taskID"] ?: "")!!.trim()
            if (cardTaskId != taskId) continue
            val currentStatus = dartToString(cardData["status"] ?: "")!!.lowercase()
            val isActive = if (cardData["type"] == AGENT_TOOL_SUMMARY_CARD_TYPE) {
                currentStatus in setOf("running", "pending", "progress", "in_progress")
            } else {
                currentStatus in setOf("pending", "running", "waiting")
            }
            if (!isActive) continue
            val nextCardData: JsonMap = LinkedHashMap(cardData)
            nextCardData["status"] = "interrupted"
            nextCardData["success"] = false
            if (summary != null && summary.trim().isNotEmpty()) nextCardData["summary"] = summary.trim()
            runtime.messages[index] = message.copyWith(content = linkedMapOf("cardData" to nextCardData, "id" to message.id))
            changed = true
        }
        runtime.activeToolCardId = null
        if (changed) notifyListeners(runtime) else markDirty(runtime)
    }

    fun beginContextCompaction(
        conversationId: Int,
        mode: String,
        taskId: String? = null,
        trigger: String = "manual",
        latestPromptTokens: Int? = null,
        promptTokenThreshold: Int? = null,
    ) {
        val runtime = stateFor(conversationId, mode) ?: return
        applyPromptTokenUsageUpdate(runtime, latestPromptTokens, promptTokenThreshold)
        runtime.isContextCompressing = true
        val activeMarkerId = runtime.activeContextCompactionMarkerId
        val markerId = if (activeMarkerId != null && runtime.messages.any { it.id == activeMarkerId }) {
            activeMarkerId
        } else {
            buildContextCompactionMarkerId(conversationId, taskId, trigger)
        }
        runtime.activeContextCompactionMarkerId = markerId
        upsertContextCompactionMarker(
            runtime,
            markerId = markerId,
            status = "compressing",
            trigger = trigger,
            latestPromptTokens = latestPromptTokens,
            promptTokenThreshold = promptTokenThreshold,
        )
        notifyListeners(runtime)
        schedulePersistRuntimeConversation(conversationId = conversationId, mode = mode)
    }

    fun finishContextCompaction(
        conversationId: Int,
        mode: String,
        status: String = "completed",
        latestPromptTokens: Int? = null,
        promptTokenThreshold: Int? = null,
    ) {
        val runtime = stateFor(conversationId, mode) ?: return
        applyPromptTokenUsageUpdate(runtime, latestPromptTokens, promptTokenThreshold)
        runtime.isContextCompressing = false
        runtime.activeContextCompactionMarkerId?.let { markerId ->
            upsertContextCompactionMarker(
                runtime,
                markerId = markerId,
                status = status,
                latestPromptTokens = latestPromptTokens,
                promptTokenThreshold = promptTokenThreshold,
            )
        }
        runtime.activeContextCompactionMarkerId = null
        notifyListeners(runtime)
        schedulePersistRuntimeConversation(conversationId = conversationId, mode = mode)
    }

    fun updateChatIslandDisplayLayer(conversationId: Int, mode: String, layer: String) {
        val runtime = stateFor(conversationId, mode)
        if (runtime == null || runtime.chatIslandDisplayLayer == layer) return
        runtime.chatIslandDisplayLayer = layer
        notifyListeners(runtime)
    }

    /** Native/IM user messages are shared runtime ingress, not an Agent stream. */
    fun handleExternalUserMessageAppended(data: Map<String, Any?>) {
        val conversationId = asPositiveInt(data["conversationId"]) ?: return
        val runtimeMode = runtimeModeFromConversationMode(dartToString(data["mode"] ?: data["conversationMode"] ?: "")!!)
        val runtime = runtimes[runtimeKey(conversationId, runtimeMode)] ?: return
        val entryId = dartToString(data["entryId"] ?: "")!!.trim()
        if (entryId.isEmpty()) return
        val text = dartToString(data["text"] ?: "")!!
        val createdAt = asPositiveInt(data["createdAt"])?.toLong() ?: asLong(data["createdAt"])?.takeIf { it > 0 }
            ?: clock()
        if (hasEquivalentAgentUserMessage(runtime.messages, entryId, text, createdAt)) return
        val attachments = (data["attachments"] as? List<*>)?.filterIsInstance<Map<*, *>>()
            ?.map { copyStringMap(it)!! } ?: emptyList()
        val content: JsonMap = linkedMapOf("id" to entryId, "text" to text)
        if (attachments.isNotEmpty()) content["attachments"] = attachments
        val message = ChatMessage(id = entryId, type = 1, user = 1, content = content, createAtMillis = createdAt)
        runtime.messages.add(findInsertIndexByCreatedAt(runtime.messages, createdAt), message)
        notifyListeners(runtime)
    }

    // ------------------------------------------------------------- snapshot

    fun replaceConversationSnapshot(
        conversationId: Int,
        mode: String,
        messages: List<ChatMessage>,
        conversation: Map<String, Any?>? = null,
        isAiResponding: Boolean = false,
        isContextCompressing: Boolean = false,
        isCheckingExecutableTask: Boolean = false,
        currentAiMessages: Map<String, String>? = null,
        currentThinkingMessages: Map<String, String>? = null,
        deepThinkingContent: String = "",
        isDeepThinking: Boolean = false,
        currentDispatchTurnId: String? = null,
        currentThinkingStage: Int = ThinkingStage.THINKING,
        isInputAreaVisible: Boolean = true,
        isExecutingTask: Boolean = false,
        lastAgentTurnId: String? = null,
        activeToolCardId: String? = null,
        activeThinkingCardId: String? = null,
        activeContextCompactionMarkerId: String? = null,
        pendingAgentTextTaskId: String? = null,
        pendingThinkingRoundSplit: Boolean = false,
        toolCardSequence: Int = 0,
        thinkingRound: Int = 0,
        chatIslandDisplayLayer: String = ChatIslandDisplayLayer.MODE,
        lastAgentToolType: String? = null,
        browserSessionSnapshot: Map<String, Any?>? = null,
        preserveLiveStreamingState: Boolean = false,
    ) {
        var normalizedMessages = normalizeIdleAgentRequestCards(
            normalizeIdleThinkingCards(
                dedupeEquivalentAgentUserMessages(messages),
                isAiResponding = isAiResponding,
                preserveLiveStreamingState = preserveLiveStreamingState,
            ),
            isAiResponding = isAiResponding,
            preserveLiveStreamingState = preserveLiveStreamingState,
        )
        val runtime = ensureRuntimeState(conversationId, mode, conversation = conversation)
        val hasBoundLiveTask = taskBindings.entries.any { (taskId, binding) ->
            binding.conversationId == conversationId && binding.mode == mode &&
                (runtime.activeRunId == taskId || runtime.currentDispatchTurnId == taskId ||
                    runtime.lastAgentTurnId == taskId)
        }
        // While reducer push events stream, a polling snapshot refreshes only
        // the visible list and metadata; reducer-owned state stays as is.
        if (preserveLiveStreamingState || hasBoundLiveTask) {
            // A snapshot cannot re-admit a live turn or clear its identity.
            // Keep reducer-owned items and add genuinely new ones by id.
            val knownIds = runtime.messages.mapTo(HashSet()) { it.id }
            val mergedMessages = ArrayList<ChatMessage>()
            mergedMessages.addAll(
                normalizeIdleAgentRequestCards(runtime.messages, isAiResponding = true, preserveLiveStreamingState = true),
            )
            normalizedMessages.filterTo(mergedMessages) { knownIds.add(it.id) }
            replaceRuntimeMessagesIfChanged(runtime, mergedMessages)
            runtime.conversation = conversation?.let { LinkedHashMap(it) } ?: runtime.conversation
            pruneAgentReplayDeltaOffsets(runtime, mergedMessages)
            notifyListeners(runtime)
            return
        }
        // History reads can finish after PromptResponse: keep a committed
        // item when an older snapshot's copy lacks its terminal result.
        val committedItems = LinkedHashMap<String, ChatMessage>()
        for (message in normalizeIdleAgentRequestCards(runtime.messages, isAiResponding = true, preserveLiveStreamingState = true)) {
            if (dartToString(message.streamMeta?.get("stopReason"))?.trim()?.isNotEmpty() == true) {
                committedItems[message.id] = message
            }
        }
        normalizedMessages = normalizedMessages.map { message ->
            if (dartToString(message.streamMeta?.get("stopReason"))?.trim()?.isNotEmpty() != true) {
                committedItems[message.id] ?: message
            } else {
                message
            }
        }
        val hadInFlightTask = runtime.hasInFlightTask
        val snapshotHasLiveWork = isAiResponding || isCheckingExecutableTask || isExecutingTask
        // Only the matching live request may keep its prompt clock.
        val preservedPromptTimingKey = if (hasBoundLiveTask && currentDispatchTurnId != null &&
            currentDispatchTurnId == runtime.currentDispatchTurnId
        ) {
            "prompt:$currentDispatchTurnId"
        } else {
            null
        }
        // Text maps are projection buffers, not lifecycle evidence.
        if (!snapshotHasLiveWork) {
            val previousRunId = runtime.activeRunId?.trim() ?: runtime.currentDispatchTurnId?.trim()
                ?: runtime.lastAgentTurnId?.trim() ?: ""
            val previousTurnId = runtime.activeAcpTurnId?.trim().orEmpty()
            if (previousRunId.isNotEmpty()) rememberCompletedTurn(runtime, previousRunId)
            if (previousTurnId.isNotEmpty()) {
                rememberCompletedTurn(runtime, previousTurnId)
                runtime.rememberCompletedAcpTurn(previousTurnId)
            }
        }
        replaceRuntimeMessagesIfChanged(runtime, normalizedMessages)
        runtime.conversation = conversation?.let { LinkedHashMap(it) } ?: runtime.conversation
        runtime.isAiResponding = isAiResponding
        runtime.isContextCompressing = isContextCompressing
        runtime.isCheckingExecutableTask = isCheckingExecutableTask
        runtime.currentAiMessages.clear()
        runtime.currentAiMessages.putAll(currentAiMessages ?: emptyMap())
        runtime.currentThinkingMessages.clear()
        runtime.currentThinkingMessages.putAll(currentThinkingMessages ?: emptyMap())
        runtime.deepThinkingContent = deepThinkingContent
        runtime.isDeepThinking = isDeepThinking
        runtime.currentDispatchTurnId = currentDispatchTurnId
        val snapshotRunId = normalizedMessages.mapNotNull { it.runId }.map { it.trim() }
            .firstOrNull { it.isNotEmpty() }.orEmpty()
        runtime.activeRunId = if (snapshotHasLiveWork) {
            if (currentDispatchTurnId?.trim()?.isNotEmpty() == true) {
                currentDispatchTurnId
            } else {
                snapshotRunId.ifEmpty { null }
            }
        } else {
            null
        }
        // A snapshot carries only a render hint; the official turn must be
        // admitted by ACP, never guessed from a local placeholder id.
        runtime.activeAcpTurnId = null
        // An idle persisted snapshot is authoritative during restore.
        runtime.activeAcpSessionId = if (snapshotHasLiveWork && hadInFlightTask) runtime.activeAcpSessionId else null
        runtime.currentThinkingStage = currentThinkingStage
        runtime.isInputAreaVisible = isInputAreaVisible
        runtime.isExecutingTask = isExecutingTask
        runtime.lastAgentTurnId = lastAgentTurnId
        runtime.activeToolCardId = activeToolCardId
        runtime.activeThinkingCardId = activeThinkingCardId
        runtime.activeContextCompactionMarkerId = activeContextCompactionMarkerId
        runtime.pendingAgentTextTaskId = pendingAgentTextTaskId
        runtime.waitingThinkingBeforeAgentTextTaskId = null
        runtime.pendingThinkingRoundSplit = pendingThinkingRoundSplit
        runtime.toolCardSequence = toolCardSequence
        runtime.thinkingRound = thinkingRound
        runtime.chatIslandDisplayLayer = chatIslandDisplayLayer
        runtime.lastAgentToolType = lastAgentToolType
        runtime.browserSessionSnapshot = browserSessionSnapshot?.let { LinkedHashMap(it) }
        runtime.streamingTextBatches.clear()
        runtime.agentEntrySequences.clear()
        runtime.agentEntryStartTimes.keys.removeIf { it != preservedPromptTimingKey }
        pruneAgentReplayDeltaOffsets(runtime, normalizedMessages)
        runtime.agentNextEntrySequence = 0
        notifyListeners(runtime)
    }

    /**
     * Updates the projection and persists the same snapshot. Page-level edit,
     * retry and external-message paths use this seam; with a live turn the
     * page snapshot merges by id so it cannot erase streamed items.
     */
    fun persistConversationMessageSnapshot(
        conversationId: Int,
        mode: String,
        messages: List<ChatMessage>,
        conversation: Map<String, Any?>? = null,
        allowHistoryRemoval: Boolean = false,
    ): Deferred<Unit> {
        val runtime = ensureRuntimeState(conversationId, mode, conversation = conversation)
        val incoming = ArrayList(messages)
        if (runtime.hasInFlightTask || !allowHistoryRemoval) {
            val incomingById = LinkedHashMap<String, ChatMessage>()
            for (message in incoming) incomingById[message.id] = message
            val merged = runtime.messages.map { incomingById.remove(it.id) ?: it }.toMutableList()
            if (incomingById.isNotEmpty()) merged.addAll(incomingById.values)
            replaceRuntimeMessagesIfChanged(runtime, merged)
        } else {
            replaceRuntimeMessagesIfChanged(runtime, incoming)
        }
        runtime.conversation = conversation?.let { LinkedHashMap(it) } ?: runtime.conversation
        notifyListeners(runtime)
        return persistRuntimeConversation(
            conversationId = conversationId,
            mode = mode,
            persistMessages = true,
            allowEphemeralPersistence = true,
            allowHistoryRemoval = allowHistoryRemoval,
        )
    }

    /** Never resurrect a pre-created thinking spinner in an idle snapshot. */
    private fun normalizeIdleThinkingCards(
        messages: List<ChatMessage>,
        isAiResponding: Boolean,
        preserveLiveStreamingState: Boolean,
    ): List<ChatMessage> {
        if (isAiResponding || preserveLiveStreamingState) return messages
        val now = clock()
        return messages.map { message ->
            val existingCardData = message.cardData
            if (message.type != 2 || existingCardData?.get("type") != "deep_thinking" ||
                existingCardData["isLoading"] != true
            ) {
                return@map message
            }
            val cardData: JsonMap = LinkedHashMap(existingCardData)
            cardData["isLoading"] = false
            cardData["stage"] = ThinkingStage.COMPLETE
            if (cardData["endTime"] == null) cardData["endTime"] = now
            cardData["isCollapsible"] = true
            message.copyWith(content = linkedMapOf("cardData" to cardData, "id" to message.id))
        }
    }

    /**
     * A server request is a live JSON-RPC request, not a resumable item:
     * after the session ends nothing can answer it, so an idle restore makes
     * it terminal while keeping it for auditability.
     */
    private fun normalizeIdleAgentRequestCards(
        messages: List<ChatMessage>,
        isAiResponding: Boolean,
        preserveLiveStreamingState: Boolean,
    ): List<ChatMessage> = messages.map { message ->
        val existingCardData = message.cardData
        if (message.type != 2 || existingCardData?.get("type") != AGENT_REQUEST_CARD_TYPE ||
            dartToString(existingCardData["status"])?.trim()?.lowercase() != "pending" ||
            existingCardData["requestId"] == null || existingCardData["interactionUnavailable"] == true
        ) {
            return@map message
        }
        val hasPromptOutcome = dartToString(message.streamMeta?.get("stopReason"))?.trim()?.isNotEmpty() == true
        if (!hasPromptOutcome && (isAiResponding || preserveLiveStreamingState)) return@map message
        val cardData: JsonMap = LinkedHashMap(existingCardData)
        // A persisted PromptResponse owns this old request even while another
        // prompt is active.
        cardData["status"] = if (hasPromptOutcome) "cancelled" else "expired"
        cardData["interactionUnavailable"] = true
        cardData["interactionUnavailableReason"] = "session_ended"
        message.copyWith(
            content = LinkedHashMap(message.content ?: emptyMap()).apply {
                put("cardData", cardData)
                put("id", message.id)
            },
        )
    }

    private fun replaceRuntimeMessagesIfChanged(runtime: ChatConversationRuntimeState, messages: List<ChatMessage>) {
        val current = runtime.messages
        if (current.size == messages.size && current.indices.all { current[it] === messages[it] }) return
        current.replaceAllMessages(messages)
    }

    private fun pruneAgentReplayDeltaOffsets(runtime: ChatConversationRuntimeState, messages: List<ChatMessage>) {
        if (runtime.agentReplayDeltaOffsets.isEmpty()) return
        val liveEntryIds = HashSet<String>()
        for (message in messages) {
            liveEntryIds.add(message.id)
            dartToString(message.streamMeta?.get("entryId"))?.trim()?.takeIf { it.isNotEmpty() }?.let { liveEntryIds.add(it) }
            dartToString(message.cardData?.get("cardId"))?.trim()?.takeIf { it.isNotEmpty() }?.let { liveEntryIds.add(it) }
        }
        runtime.agentReplayDeltaOffsets.keys.removeIf { it !in liveEntryIds }
    }

    // ---------------------------------------------------------- persistence

    fun persistRuntimeConversation(
        conversationId: Int,
        mode: String,
        generateSummary: Boolean = false,
        markComplete: Boolean = false,
        persistMessages: Boolean = false,
        allowEphemeralPersistence: Boolean = false,
        allowHistoryRemoval: Boolean = false,
    ): Deferred<Unit> {
        cancelPendingPersistence(conversationId, mode)
        if (isEphemeralRuntime(conversationId, mode) && !allowEphemeralPersistence) return completedUnit()
        val key = runtimeKey(conversationId, mode)
        val expectedRuntime = stateFor(conversationId, mode) ?: return completedUnit()
        val previous = persistenceTails[key]
        val operation = scope.async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { previous?.await() }
            persistRuntimeConversationNow(
                conversationId = conversationId,
                mode = mode,
                expectedRuntime = expectedRuntime,
                generateSummary = generateSummary,
                markComplete = markComplete,
                persistMessages = persistMessages,
                allowHistoryRemoval = allowHistoryRemoval,
            )
        }
        persistenceTails[key] = operation
        operation.invokeOnCompletion {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                if (persistenceTails[key] === operation) persistenceTails.remove(key)
            }
        }
        return operation
    }

    private suspend fun persistRuntimeConversationNow(
        conversationId: Int,
        mode: String,
        expectedRuntime: ChatConversationRuntimeState,
        generateSummary: Boolean,
        markComplete: Boolean,
        persistMessages: Boolean,
        allowHistoryRemoval: Boolean,
    ) {
        val runtime = expectedRuntime
        if (stateFor(conversationId, mode) !== runtime) return
        val persistenceGeneration = runtime.persistenceGeneration
        // An empty projection at completion/disposal is not an instruction
        // to erase committed history; only explicit replacement owns clear.
        if (runtime.messages.isEmpty() && !(persistMessages && allowHistoryRemoval)) return

        var snapshotMessages = runtime.messages.toList()
        val snapshotConversation = runtime.conversation?.let { ConversationPayload(it) }
        val conversationMode = conversationModeFromRuntimeMode(mode, snapshotConversation)
        val now = clock()
        val lastMessage = if (snapshotMessages.isNotEmpty()) snapshotMessages[0].text ?: "" else ""
        val messageCount = snapshotMessages.size
        val firstUserMessage = snapshotMessages.firstOrNull { it.user == 1 } ?: ChatMessage.userMessage("default")
        val userText = firstUserMessage.text ?: "conversation"
        val title = if (userText.length > 20) "${userText.substring(0, 20)}..." else userText

        var summary = snapshotConversation?.summary
        if (generateSummary) {
            val history = buildConversationHistoryText(snapshotMessages)
            summary = if (history.isEmpty()) null else persistence.generateConversationSummary(history)
        }
        val baseConversation: JsonMap = when {
            snapshotConversation == null -> ConversationPayload.create(
                id = conversationId,
                mode = conversationMode,
                title = title,
                summary = summary,
                lastMessage = lastMessage,
                messageCount = messageCount,
                createdAt = now,
                updatedAt = now,
            )
            snapshotConversation.mode == conversationMode -> LinkedHashMap(snapshotConversation.map)
            else -> snapshotConversation.copyWith("mode" to conversationMode)
        }
        val base = ConversationPayload(baseConversation)
        val updatedConversation = base.copyWith(
            "title" to (if (base.title.isEmpty()) title else base.title),
            "summary" to (summary ?: base.summary),
            "lastMessage" to lastMessage,
            "messageCount" to messageCount,
            "updatedAt" to now,
        )
        persistence.updateConversation(updatedConversation)
        if (persistMessages) {
            // Metadata I/O can overlap streamed chunks: capture message
            // content at the write boundary unless the runtime was replaced.
            if (stateFor(conversationId, mode) === runtime && runtime.persistenceGeneration == persistenceGeneration) {
                snapshotMessages = runtime.messages.toList()
            }
            // The write is echoed back as messages_replaced; the runtime
            // already owns this snapshot, so suppress the reload flash.
            runtime.expectLocalMessageSnapshotEcho()
            markDirty(runtime)
            persistence.saveConversationMessages(conversationId, conversationMode, snapshotMessages, allowHistoryRemoval)
        }
        // Do not mutate a runtime replaced while the write was in flight.
        val isCurrentRuntime = stateFor(conversationId, mode) === runtime &&
            runtime.persistenceGeneration == persistenceGeneration
        if (isCurrentRuntime) {
            runtime.conversation = updatedConversation
            markDirty(runtime)
        }
        if (markComplete && isCurrentRuntime) {
            persistence.completeConversation(conversationId, conversationMode)
        }
    }

    fun schedulePersistRuntimeConversation(
        conversationId: Int,
        mode: String,
        generateSummary: Boolean = false,
        markComplete: Boolean = false,
        persistMessages: Boolean = false,
        delayMillis: Long = 350L,
    ) {
        val key = runtimeKey(conversationId, mode)
        if (key in ephemeralRuntimeKeys) return
        val previous = pendingPersistence[key]
        // Continuous chunks must not keep moving the write into the future:
        // merge into the original timer; urgent writes may advance it.
        if (delayMillis == 0L) previous?.timer?.cancel()
        val nextGenerateSummary = generateSummary || (previous?.generateSummary ?: false)
        val nextMarkComplete = markComplete || (previous?.markComplete ?: false)
        val nextPersistMessages = persistMessages || (previous?.persistMessages ?: false)
        val timer = (if (delayMillis != 0L) previous?.timer else null) ?: scheduler.schedule(delayMillis) {
            val request = pendingPersistence.remove(key) ?: return@schedule
            persistRuntimeConversation(
                conversationId = conversationId,
                mode = mode,
                generateSummary = request.generateSummary,
                markComplete = request.markComplete,
                persistMessages = request.persistMessages,
            )
        }
        pendingPersistence[key] = PendingPersistenceRequest(
            conversationId = conversationId,
            mode = mode,
            timer = timer,
            generateSummary = nextGenerateSummary,
            markComplete = nextMarkComplete,
            persistMessages = nextPersistMessages,
        )
    }

    suspend fun flushPendingPersistence(conversationId: Int, mode: String) {
        val key = runtimeKey(conversationId, mode)
        val request = pendingPersistence.remove(key) ?: return
        request.timer.cancel()
        if (key in ephemeralRuntimeKeys) return
        persistRuntimeConversation(
            conversationId = request.conversationId,
            mode = request.mode,
            generateSummary = request.generateSummary,
            markComplete = request.markComplete,
            persistMessages = request.persistMessages,
        ).await()
    }

    suspend fun flushAllPendingPersistence() {
        val requests = pendingPersistence.values.toList()
        pendingPersistence.clear()
        for (request in requests) {
            request.timer.cancel()
            persistRuntimeConversation(
                conversationId = request.conversationId,
                mode = request.mode,
                generateSummary = request.generateSummary,
                markComplete = request.markComplete,
                persistMessages = request.persistMessages,
            ).await()
        }
        // Lifecycle callbacks may already have queued a write; disposal and
        // background flush wait for that tail as well.
        for (inFlight in persistenceTails.values.toList()) inFlight.await()
    }

    private fun cancelPendingPersistence(conversationId: Int, mode: String) {
        pendingPersistence.remove(runtimeKey(conversationId, mode))?.timer?.cancel()
    }

    // ------------------------------------------------------- page commands

    // The only writes besides the lifecycle commands above. Like the direct
    // field writes they replace, they mark the runtime dirty without a
    // coordinator notification; message-list writes keep the list's row
    // revisions.

    fun updateRuntimePresentation(
        conversationId: Int,
        mode: String,
        isAiResponding: Boolean? = null,
        isContextCompressing: Boolean? = null,
        isCheckingExecutableTask: Boolean? = null,
        isExecutingTask: Boolean? = null,
        isInputAreaVisible: Boolean? = null,
        isDeepThinking: Boolean? = null,
        deepThinkingContent: String? = null,
        currentThinkingStage: Int? = null,
        chatIslandDisplayLayer: String? = null,
    ) {
        val runtime = stateFor(conversationId, mode) ?: return
        isAiResponding?.let { runtime.isAiResponding = it }
        isContextCompressing?.let { runtime.isContextCompressing = it }
        isCheckingExecutableTask?.let { runtime.isCheckingExecutableTask = it }
        isExecutingTask?.let { runtime.isExecutingTask = it }
        isInputAreaVisible?.let { runtime.isInputAreaVisible = it }
        isDeepThinking?.let { runtime.isDeepThinking = it }
        deepThinkingContent?.let { runtime.deepThinkingContent = it }
        currentThinkingStage?.let { runtime.currentThinkingStage = it }
        chatIslandDisplayLayer?.let { runtime.chatIslandDisplayLayer = it }
        markDirty(runtime)
    }

    fun setRuntimeDispatchTurnId(conversationId: Int, mode: String, turnId: String?) {
        stateFor(conversationId, mode)?.let {
            it.currentDispatchTurnId = turnId
            markDirty(it)
        }
    }

    fun setRuntimeLastAgentToolType(conversationId: Int, mode: String, toolType: String?) {
        stateFor(conversationId, mode)?.let {
            it.lastAgentToolType = toolType
            markDirty(it)
        }
    }

    fun setRuntimeBrowserSessionSnapshot(conversationId: Int, mode: String, snapshot: Map<String, Any?>?) {
        stateFor(conversationId, mode)?.let {
            it.browserSessionSnapshot = snapshot?.let { value -> LinkedHashMap(value) }
            markDirty(it)
        }
    }

    fun setRuntimeConversation(conversationId: Int, mode: String, conversation: Map<String, Any?>?) {
        stateFor(conversationId, mode)?.let {
            it.conversation = conversation?.let { value -> LinkedHashMap(value) }
            markDirty(it)
        }
    }

    /** Inserts newest-first at [index]; an existing id is replaced in place. */
    fun insertRuntimeMessage(conversationId: Int, mode: String, message: ChatMessage, index: Int = 0) {
        stateFor(conversationId, mode)?.let {
            it.messages.add(index.coerceIn(0, it.messages.size), message)
            markDirty(it)
        }
    }

    fun appendRuntimeMessages(conversationId: Int, mode: String, messages: List<ChatMessage>) {
        stateFor(conversationId, mode)?.let {
            it.messages.addAll(messages)
            markDirty(it)
        }
    }

    fun replaceRuntimeMessage(conversationId: Int, mode: String, messageId: String, message: ChatMessage): Boolean {
        val runtime = stateFor(conversationId, mode) ?: return false
        val index = runtime.messages.indexOfFirst { it.id == messageId }
        if (index < 0) return false
        runtime.messages[index] = message
        markDirty(runtime)
        return true
    }

    fun removeRuntimeMessages(conversationId: Int, mode: String, messageIds: Collection<String>) {
        val ids = messageIds.toSet()
        if (ids.isEmpty()) return
        stateFor(conversationId, mode)?.let {
            it.messages.removeWhere { message -> message.id in ids }
            markDirty(it)
        }
    }

    fun removeLeadingRuntimeMessages(conversationId: Int, mode: String, count: Int) {
        val runtime = stateFor(conversationId, mode) ?: return
        if (count <= 0) return
        runtime.messages.removeRange(0, count.coerceIn(0, runtime.messages.size))
        markDirty(runtime)
    }

    fun replaceRuntimeMessages(conversationId: Int, mode: String, messages: List<ChatMessage>) {
        val runtime = stateFor(conversationId, mode) ?: return
        runtime.messages.clear()
        runtime.messages.addAll(messages)
        markDirty(runtime)
    }

    /** Publishes runtimes changed by page commands since the last notification. */
    fun publishDirtySnapshots() = publish()

    // -------------------------------------------------------- event routing

    /** Attaches a mounted surface to the single runtime event route. */
    fun attachEventHost(
        context: () -> ChatRuntimeRoutingContext?,
        onOutcome: (ChatRuntimeEventOutcome) -> Unit,
    ): ChatRuntimeEventHost {
        val host = ChatRuntimeEventHost(this, context, onOutcome)
        eventHosts.add(host)
        return host
    }

    internal fun detachEventHost(host: ChatRuntimeEventHost) {
        eventHosts.remove(host)
    }

    val hasEventHosts: Boolean get() = eventHosts.isNotEmpty()

    /** Applies one runtime event to the one runtime that owns it. */
    fun routeAgentEvent(event: Map<String, Any?>): ChatRuntimeEventOutcome? {
        val contexts = eventHosts.reversed().mapNotNull { it.context() }
        val outcome = ChatRuntimeEventRouter.route(this, event, contexts) ?: return null
        for (host in ArrayList(eventHosts)) {
            if (host in eventHosts) host.onOutcome(outcome)
        }
        return outcome
    }

    /** Ensures the ephemeral runtime that mirrors a remote Agent thread. */
    fun ensureRemoteThreadRuntime(threadId: String): Int {
        val normalizedThreadId = threadId.trim()
        val runtimeId = remoteAgentRuntimeIdForThread(normalizedThreadId)
        val now = clock()
        val suffix = if (normalizedThreadId.length > 6) {
            normalizedThreadId.substring(normalizedThreadId.length - 6)
        } else {
            normalizedThreadId
        }
        ensureEphemeralRuntimeState(
            conversationId = runtimeId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            conversation = stateFor(runtimeId, CHAT_RUNTIME_MODE_AGENT)?.conversation
                ?: ConversationPayload.create(
                    id = runtimeId,
                    mode = ConversationModes.AGENT,
                    title = "Agent $suffix",
                    messageCount = 0,
                    createdAt = now,
                    updatedAt = now,
                ),
            initialChatIslandDisplayLayer = ChatIslandDisplayLayer.MODE,
        )
        return runtimeId
    }

    /** Makes a remote thread's runtime visible, carrying page-local items over. */
    fun activateRemoteThreadRuntime(
        threadId: String,
        fallbackMessages: List<ChatMessage> = emptyList(),
        conversation: Map<String, Any?>? = null,
    ): Int {
        val runtimeId = ensureRemoteThreadRuntime(threadId)
        val runtime = stateFor(runtimeId, CHAT_RUNTIME_MODE_AGENT)
        if (runtime != null) {
            if (fallbackMessages.isNotEmpty()) {
                val existingIds = runtime.messages.mapTo(HashSet()) { it.id }
                for (message in fallbackMessages.asReversed()) {
                    if (existingIds.add(message.id)) runtime.messages.add(message)
                }
            }
            if (conversation != null) {
                runtime.conversation = ConversationPayload(conversation).copyWith("id" to runtimeId)
            }
            markDirty(runtime)
        }
        return runtimeId
    }

    // ---------------------------------------------------------------- tests

    internal fun debugRuntimeStateFor(conversationId: Int, mode: String) = stateFor(conversationId, mode)

    internal fun debugEnsureRuntimeState(
        conversationId: Int,
        mode: String,
        initialMessages: List<ChatMessage>? = null,
        conversation: Map<String, Any?>? = null,
    ) = ensureRuntimeState(conversationId, mode, initialMessages, conversation)

    fun resetForTest() {
        for (request in pendingPersistence.values) request.timer.cancel()
        pendingPersistence.clear()
        for (runtime in runtimes.values) runtime.dispose()
        runtimes.clear()
        taskBindings.clear()
        ephemeralRuntimeKeys.clear()
        eventHosts.clear()
        dirtyKeys.clear()
        removedKeys.clear()
        revisions.clear()
    }

    // ------------------------------------------------------------- internal

    private fun stateFor(conversationId: Int, mode: String) = runtimes[runtimeKey(conversationId, mode)]

    private fun ensureRuntimeState(
        conversationId: Int,
        mode: String,
        initialMessages: List<ChatMessage>? = null,
        conversation: Map<String, Any?>? = null,
        initialChatIslandDisplayLayer: String? = null,
    ): ChatConversationRuntimeState {
        val key = runtimeKey(conversationId, mode)
        val existing = runtimes[key]
        val runtime = existing ?: ChatConversationRuntimeState(conversationId, mode, clock)
        if (existing == null) {
            if (initialChatIslandDisplayLayer != null) runtime.chatIslandDisplayLayer = initialChatIslandDisplayLayer
            runtimes[key] = runtime
            removedKeys.remove(key)
        }
        if (runtime.messages.isEmpty() && initialMessages != null) {
            runtime.messages.addAll(dedupeEquivalentAgentUserMessages(initialMessages))
        }
        if (conversation != null) runtime.conversation = LinkedHashMap(conversation)
        return runtime
    }

    private fun ensureEphemeralRuntimeState(
        conversationId: Int,
        mode: String,
        initialMessages: List<ChatMessage>? = null,
        conversation: Map<String, Any?>? = null,
        initialChatIslandDisplayLayer: String? = null,
    ): ChatConversationRuntimeState {
        val runtime = ensureRuntimeState(conversationId, mode, initialMessages, conversation, initialChatIslandDisplayLayer)
        ephemeralRuntimeKeys.add(runtimeKey(conversationId, mode))
        markDirty(runtime)
        return runtime
    }

    private fun runtimeForTask(taskId: String): ChatConversationRuntimeState? {
        val binding = taskBindings[taskId] ?: return null
        return ensureRuntimeState(binding.conversationId, binding.mode)
    }

    private fun applyPromptTokenUsageUpdate(
        runtime: ChatConversationRuntimeState,
        latestPromptTokens: Int?,
        promptTokenThreshold: Int?,
    ) {
        val conversation = runtime.conversation ?: return
        if (latestPromptTokens == null && promptTokenThreshold == null) return
        val payload = ConversationPayload(conversation)
        // Usage observations never write the user-owned threshold setting.
        runtime.conversation = payload.copyWith(
            "latestPromptTokens" to (latestPromptTokens ?: payload.latestPromptTokens),
            "latestPromptTokensUpdatedAt" to
                (if (latestPromptTokens != null) clock() else payload.latestPromptTokensUpdatedAt),
        )
    }

    private fun buildContextCompactionMarkerId(conversationId: Int, taskId: String?, trigger: String): String {
        val suffix = clock()
        val normalizedTaskId = taskId?.trim()
        if (!normalizedTaskId.isNullOrEmpty()) return "$normalizedTaskId-context-compaction-$suffix"
        return "conversation-$conversationId-$trigger-context-compaction-$suffix"
    }

    private fun upsertContextCompactionMarker(
        runtime: ChatConversationRuntimeState,
        markerId: String,
        status: String,
        trigger: String = "manual",
        latestPromptTokens: Int? = null,
        promptTokenThreshold: Int? = null,
    ) {
        val index = runtime.messages.indexOfFirst { it.id == markerId }
        val existing = if (index == -1) null else runtime.messages[index]
        val existingCardData = existing?.cardData ?: emptyMap()
        val startTime = (existingCardData["startTime"] as? Number)?.toLong() ?: clock()
        val endTime = if (status == "compressing") null else clock()
        val resolvedTriggerRaw = dartToString(existingCardData["trigger"] ?: trigger)!!.trim()
        val resolvedTrigger = resolvedTriggerRaw.ifEmpty { trigger }
        val conversation = runtime.conversation?.let { ConversationPayload(it) }
        val cardData: JsonMap = linkedMapOf(
            "type" to "context_compaction_marker",
            "status" to status,
            "label" to contextCompactionLabel(status),
            "trigger" to resolvedTrigger,
            "startTime" to startTime,
            "endTime" to endTime,
            "latestPromptTokens" to (latestPromptTokens ?: conversation?.latestPromptTokens),
            "promptTokenThreshold" to (promptTokenThreshold ?: conversation?.promptTokenThreshold),
        )
        val message = ChatMessage(
            id = markerId,
            type = 2,
            user = 3,
            content = linkedMapOf("cardData" to cardData, "id" to markerId),
            createAtMillis = startTime,
        )
        if (index == -1) {
            runtime.messages.add(0, message)
        } else {
            runtime.messages[index] = existing!!.copyWith(content = linkedMapOf("cardData" to cardData, "id" to markerId))
        }
        persistContextCompactionMarkerIfNeeded(
            runtime.conversationId,
            runtime.mode,
            if (index == -1) message else runtime.messages[index],
        )
    }

    private fun contextCompactionLabel(status: String): String {
        val english = isEnglish()
        return when (status) {
            "compressing" -> if (english) "Compressing" else "正在压缩"
            "noop" -> if (english) "No compaction needed" else "无需压缩"
            "failed" -> if (english) "Compaction failed" else "压缩失败"
            else -> if (english) "Compacted" else "已压缩"
        }
    }

    private fun persistContextCompactionMarkerIfNeeded(conversationId: Int, mode: String, message: ChatMessage) {
        if (isEphemeralRuntime(conversationId, mode)) return
        val cardData = message.cardData
        if (message.type != 2 || cardData?.get("type") != "context_compaction_marker") return
        val conversationMode = conversationModeFromRuntimeMode(
            mode,
            stateFor(conversationId, mode)?.conversation?.let { ConversationPayload(it) },
        )
        val entryId = message.id
        val card = LinkedHashMap(cardData)
        val createdAt = message.createAtMillis
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching { persistence.upsertConversationUiCard(conversationId, conversationMode, entryId, card, createdAt) }
                .onFailure { Log.w(TAG, "保存 UI 卡片异常: ${it.message}") }
        }
    }

    private fun buildConversationHistoryText(messages: List<ChatMessage>): String {
        val english = isEnglish()
        val buffer = StringBuilder()
        for (message in messages) {
            if (message.user != 1) continue
            val text = message.content?.get("text") as? String ?: ""
            if (text.isEmpty()) continue
            buffer.append(if (english) "User: $text\n" else "用户: $text\n")
        }
        return buffer.toString().trim()
    }

    private fun conversationModeFromRuntimeMode(mode: String, conversation: ConversationPayload?): String = when (mode) {
        CHAT_RUNTIME_MODE_OPENCLAW -> ConversationModes.OPENCLAW
        CHAT_RUNTIME_MODE_AGENT -> ConversationModes.AGENT
        // `normal` is the legacy Xiaowan label; durable conversations use
        // the canonical ACP mode.
        else -> when (conversation?.mode) {
            ConversationModes.CHAT_ONLY -> ConversationModes.CHAT_ONLY
            ConversationModes.SUBAGENT -> ConversationModes.SUBAGENT
            else -> ConversationModes.AGENT
        }
    }

    private fun dedupeEquivalentAgentUserMessages(messages: List<ChatMessage>): List<ChatMessage> {
        val source = ArrayList(messages)
        val preferredByCanonicalId = LinkedHashMap<String, ChatMessage>()
        for (message in source) {
            if (message.user != 1) continue
            val canonicalId = canonicalAgentUserMessageId(message.id) ?: continue
            val existing = preferredByCanonicalId[canonicalId]
            if (existing == null || preferAgentUserMessage(message, existing)) {
                preferredByCanonicalId[canonicalId] = message
            }
        }
        if (preferredByCanonicalId.isEmpty()) return source
        return source.filter { message ->
            if (message.user != 1) return@filter true
            val canonicalId = canonicalAgentUserMessageId(message.id) ?: return@filter true
            preferredByCanonicalId[canonicalId] === message
        }
    }

    private fun hasEquivalentAgentUserMessage(
        messages: List<ChatMessage>,
        entryId: String,
        text: String?,
        createdAt: Long?,
    ): Boolean {
        val canonicalId = canonicalAgentUserMessageId(entryId) ?: return messages.any { it.id == entryId }
        val normalizedText = text?.trim()
        for (message in messages) {
            if (message.user != 1) continue
            if (message.id == entryId) return true
            if (canonicalAgentUserMessageId(message.id) != canonicalId) continue
            if (!normalizedText.isNullOrEmpty()) {
                val existingText = (message.text ?: "").trim()
                if (existingText.isNotEmpty() && existingText != normalizedText) continue
            }
            if (createdAt != null && kotlin.math.abs(message.createAtMillis - createdAt) > 1000) continue
            return true
        }
        return false
    }

    private fun preferAgentUserMessage(candidate: ChatMessage, existing: ChatMessage): Boolean {
        val candidateIsLocal = !candidate.id.endsWith("-ai-user")
        val existingIsLocal = !existing.id.endsWith("-ai-user")
        if (candidateIsLocal != existingIsLocal) return candidateIsLocal
        return candidate.createAtMillis > existing.createAtMillis
    }

    private fun canonicalAgentUserMessageId(rawId: String): String? {
        val id = rawId.trim()
        if (id.isEmpty()) return null
        if (id.endsWith("-ai-user")) return "${id.substring(0, id.length - "-ai-user".length)}-user"
        if (id.endsWith("-user")) return id
        return null
    }

    private fun findInsertIndexByCreatedAt(messages: List<ChatMessage>, createdAt: Long): Int {
        for (index in messages.indices) {
            if (messages[index].createAtMillis <= createdAt) return index
        }
        return messages.size
    }

    private fun asPositiveInt(raw: Any?): Int? {
        val value = when (raw) {
            is Int -> raw
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
        return if (value != null && value > 0) value else null
    }

    private fun runtimeModeFromConversationMode(rawMode: String): String =
        when (conversationModeFromStorageValue(rawMode)) {
            ConversationModes.OPENCLAW -> CHAT_RUNTIME_MODE_OPENCLAW
            ConversationModes.AGENT -> CHAT_RUNTIME_MODE_AGENT
            else -> CHAT_RUNTIME_MODE_NORMAL
        }

    private fun markDirty(runtime: ChatConversationRuntimeState) {
        val key = runtimeKey(runtime.conversationId, runtime.mode)
        if (runtimes[key] === runtime) dirtyKeys.add(key)
    }

    /** Dart `notifyListeners()`: publish every dirty runtime, this one included. */
    private fun notifyListeners(runtime: ChatConversationRuntimeState) {
        markDirty(runtime)
        publish()
    }

    private fun publish() {
        if (dirtyKeys.isEmpty() && removedKeys.isEmpty()) return
        val snapshots = dirtyKeys.mapNotNull { key ->
            val state = runtimes[key] ?: return@mapNotNull null
            val revision = (revisions[key] ?: 0L) + 1
            revisions[key] = revision
            ChatRuntimeSnapshot.of(state, revision, key in ephemeralRuntimeKeys)
        }
        val removed = removedKeys.toList()
        dirtyKeys.clear()
        removedKeys.clear()
        for (listener in ArrayList(listeners)) listener.onRuntimesChanged(snapshots, removed)
    }

    private fun completedUnit(): Deferred<Unit> = CompletableDeferred(Unit)

    companion object {
        private const val TAG = "ChatRuntimeCoordinator"

        fun runtimeKey(conversationId: Int, mode: String): String = "$mode:$conversationId"
    }
}
