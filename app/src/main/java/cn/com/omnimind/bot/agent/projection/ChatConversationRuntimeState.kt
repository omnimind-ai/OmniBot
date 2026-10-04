package cn.com.omnimind.bot.agent.projection

import android.util.Log

const val CHAT_RUNTIME_MODE_NORMAL = "normal"
const val CHAT_RUNTIME_MODE_OPENCLAW = "openclaw"
const val CHAT_RUNTIME_MODE_AGENT = "agent"

/** Dart `ThinkingStage` values. */
object ThinkingStage {
    const val THINKING = 1
    const val TOOL_CALL = 2
    const val EXECUTING = 3
    const val COMPLETE = 4
    const val CANCELLED = 5
}

/** Dart `ChatIslandDisplayLayer` storage values. */
object ChatIslandDisplayLayer {
    const val MODE = "mode"
    const val TOOLS = "tools"
}

internal enum class StreamingTextStreamKind { pureChatReply, agentReply, pureChatThinking, agentThinking }

internal const val STREAMING_TEXT_CHUNK_FLUSH_THRESHOLD = 5

internal class StreamingTextBatchState(
    val taskId: String,
    val kind: StreamingTextStreamKind,
    var latestText: String,
    var lastFlushedText: String,
) {
    var pendingChunkCount = 0

    val hasPendingFlush: Boolean get() = latestText != lastFlushedText

    val reachedFlushThreshold: Boolean get() = pendingChunkCount >= STREAMING_TEXT_CHUNK_FLUSH_THRESHOLD

    /** New text since the last flush contains a newline: flush markdown blocks promptly. */
    val containsNewlineSinceFlush: Boolean
        get() {
            if (latestText.length <= lastFlushedText.length) return false
            return latestText.indexOf('\n', lastFlushedText.length) >= 0
        }

    fun stage(nextText: String) {
        if (nextText == latestText) return
        latestText = nextText
        pendingChunkCount += 1
    }

    fun markFlushed() {
        lastFlushedText = latestText
        pendingChunkCount = 0
    }
}

/**
 * Mutable projection state of one `(conversationId, mode)` runtime; the port
 * of Dart `ChatConversationRuntimeState`. Only the reducer and coordinator
 * mutate it. UI surfaces receive immutable snapshots.
 *
 * Identity spaces are deliberately separate: [activeRunId] is the stable
 * local render key, [activeAcpTurnId] / [activeAcpSessionId] are the
 * official ACP identities, and text caches are never lifecycle state.
 */
class ChatConversationRuntimeState(
    val conversationId: Int,
    val mode: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Dart `ConversationModel.toJson()` shape. */
    var conversation: JsonMap? = null
    val messages = ProjectedMessageList()

    /**
     * Accumulated assistant text: a TEXT CACHE keyed per producer (task id or
     * `<acpMessageId>-agent-message`). Never feeds [activeAgentTurnIds].
     */
    val currentAiMessages = LinkedHashMap<String, String>()

    /** Legacy process notifications bound to the run that first saw them. */
    val standaloneProcessRunIds = LinkedHashMap<String, String>()

    /** Accumulated reasoning text. Same contract as [currentAiMessages]. */
    val currentThinkingMessages = LinkedHashMap<String, String>()

    /** User chunks admitted only for an explicit ACP history replay. */
    val currentAcpUserMessages = LinkedHashMap<String, String>()
    internal val streamingTextBatches = LinkedHashMap<String, StreamingTextBatchState>()
    val agentEntrySequences = LinkedHashMap<String, Int>()
    val agentEntryStartTimes = LinkedHashMap<String, Long>()
    val agentReplayDeltaOffsets = LinkedHashMap<String, Int>()

    /** Performance metadata that arrived before its assistant message. */
    val pendingAcpPerformanceMetrics = LinkedHashMap<String, JsonMap>()
    val pendingAcpReasoningCardData = LinkedHashMap<String, JsonMap>()

    /** Presentation metadata that arrived before its text entry. */
    val pendingAcpAssistantPresentation = LinkedHashMap<String, JsonMap>()

    /** Host ACP notification ids already reduced (dedupe by explicit id only). */
    val processedAcpEventIds = LinkedHashSet<String>()
    val completedAgentTurnIds = LinkedHashSet<String>()

    /** Metadata-only trail of events quarantined for missing identity. */
    val acpCompatibilityDiagnostics = ArrayList<JsonMap>()
    var acpCompatibilityWarningShown = false

    /** Session ids observed by this runtime, kept after it becomes idle. */
    val knownAcpSessionIds = LinkedHashSet<String>()

    /** Sessions invalidated by cancel/reset; a new turn may reactivate one. */
    val retiredAcpSessionIds = LinkedHashSet<String>()
    var allowRetiredAcpSessionReactivation = false

    /** Official ACP turn -> stable local run, retained after completion. */
    val acpTurnToRunIds = LinkedHashMap<String, String>()
    val completedAcpTurnIds = LinkedHashSet<String>()

    /** Advances when a prompt/session lifecycle starts; fences async persistence. */
    var persistenceGeneration = 0

    var availableAcpCommands: List<JsonMap> = emptyList()
    var acpConfigOptions: List<JsonMap> = emptyList()
    var currentAcpModeId: String? = null
    var acpSessionInfo: JsonMap = linkedMapOf()

    /** Extension updates the UI does not understand yet. */
    val acpExtensionUpdates = ArrayList<JsonMap>()
    var agentNextEntrySequence = 0
    var isAiResponding = false
    var isContextCompressing = false
    var isCheckingExecutableTask = false
    var deepThinkingContent = ""
    var isDeepThinking = false

    /** Stable local ownership key; never assigned an ACP turn id. */
    var activeRunId: String? = null

    /** Compatibility name for page/history callers; same value as [activeRunId]. */
    var currentDispatchTurnId: String?
        get() = activeRunId
        set(value) {
            activeRunId = value
        }

    var activeAcpTurnId: String? = null
    var activeAcpSessionId: String? = null
    var currentThinkingStage = ThinkingStage.THINKING
    var isInputAreaVisible = true
    var isExecutingTask = false

    var lastAgentTurnId: String? = null
    var activeToolCardId: String? = null
    var activeThinkingCardId: String? = null
    var activeContextCompactionMarkerId: String? = null
    var pendingAgentTextTaskId: String? = null
    var waitingThinkingBeforeAgentTextTaskId: String? = null
    var pendingThinkingRoundSplit = false
    var toolCardSequence = 0
    var thinkingRound = 0
    var chatIslandDisplayLayer = ChatIslandDisplayLayer.MODE
    var lastAgentToolType: String? = null

    /** Page-owned browser session snapshot, carried opaquely. */
    var browserSessionSnapshot: JsonMap? = null
    private var localSnapshotEchoSuppressionUntilMillis = 0L

    val activeRunIdentity: AgentRunIdentity?
        get() {
            val runId = activeRunId?.trim().orEmpty()
            if (runId.isEmpty()) return null
            return AgentRunIdentity(
                runId = runId,
                conversationId = conversationId,
                sessionId = activeAcpSessionId,
                turnId = activeAcpTurnId,
            )
        }

    val hasInFlightTask: Boolean
        get() = isAiResponding || isCheckingExecutableTask || isExecutingTask || activeRunId != null

    val shouldSuppressLocalMessageSnapshotEcho: Boolean
        get() = clock() < localSnapshotEchoSuppressionUntilMillis

    fun expectLocalMessageSnapshotEcho() {
        localSnapshotEchoSuppressionUntilMillis = clock() + LOCAL_SNAPSHOT_ECHO_SUPPRESSION_MILLIS
    }

    /**
     * Runs currently believed to be producing output. Only stable UI run ids;
     * ACP turn ids and text cache keys are excluded.
     */
    val activeAgentTurnIds: Set<String>
        get() {
            val ids = LinkedHashSet<String>()
            // Text caches are projection buffers, not proof a prompt is alive.
            val hasLiveWork = isAiResponding || isCheckingExecutableTask || isExecutingTask || activeRunId != null
            if (hasLiveWork) {
                val currentTaskId = (activeRunId ?: currentDispatchTurnId)?.trim().orEmpty()
                if (currentTaskId.isNotEmpty()) ids.add(currentTaskId)
            }
            val lastTaskId = lastAgentTurnId?.trim().orEmpty()
            if (isAiResponding && lastTaskId.isNotEmpty()) ids.add(lastTaskId)
            val pendingTaskId = pendingAgentTextTaskId?.trim().orEmpty()
            if (pendingTaskId.isNotEmpty()) ids.add((activeRunId ?: pendingTaskId).trim())
            return ids
        }

    fun dispose() {
        streamingTextBatches.clear()
        agentEntrySequences.clear()
        agentEntryStartTimes.clear()
        agentReplayDeltaOffsets.clear()
        standaloneProcessRunIds.clear()
        pendingAcpPerformanceMetrics.clear()
        pendingAcpReasoningCardData.clear()
        pendingAcpAssistantPresentation.clear()
        processedAcpEventIds.clear()
        completedAgentTurnIds.clear()
        acpCompatibilityDiagnostics.clear()
        acpCompatibilityWarningShown = false
        knownAcpSessionIds.clear()
        retiredAcpSessionIds.clear()
        allowRetiredAcpSessionReactivation = false
        acpTurnToRunIds.clear()
        completedAcpTurnIds.clear()
        messages.clear()
    }

    fun resolveRunId(sessionId: String? = null, turnId: String? = null, fallback: String? = null): String? {
        val key = acpTurnKey(sessionId = sessionId, turnId = turnId)
        if (key.isNotEmpty()) {
            acpTurnToRunIds[key]?.let { return it }
            acpTurnToRunIds[acpTurnKey(turnId = turnId)]?.let { return it }
        }
        val active = activeRunId?.trim().orEmpty()
        if (active.isNotEmpty()) {
            if (key.isNotEmpty()) acpTurnToRunIds[key] = active
            val turnOnlyKey = acpTurnKey(turnId = turnId)
            if (turnOnlyKey.isNotEmpty()) acpTurnToRunIds[turnOnlyKey] = active
            return active
        }
        val normalizedFallback = fallback?.trim().orEmpty()
        if (normalizedFallback.isEmpty()) return null
        if (key.isNotEmpty()) acpTurnToRunIds[key] = normalizedFallback
        return normalizedFallback
    }

    /**
     * Resolves an ACP event without letting an unknown identity borrow the
     * active render id: once an official turn is active, a delayed event from
     * another identity only resolves to an already admitted run.
     */
    fun resolveAcpEventRunId(sessionId: String? = null, turnId: String? = null, fallback: String? = null): String? {
        val incomingSessionId = sessionId?.trim().orEmpty()
        val incomingTurnId = turnId?.trim().orEmpty()
        val activeSessionId = activeAcpSessionId?.trim().orEmpty()
        val activeTurnId = activeAcpTurnId?.trim().orEmpty()
        val belongsToDifferentActiveIdentity =
            (incomingSessionId.isNotEmpty() && activeSessionId.isNotEmpty() && incomingSessionId != activeSessionId) ||
                (incomingTurnId.isNotEmpty() && activeTurnId.isNotEmpty() && incomingTurnId != activeTurnId)
        if (belongsToDifferentActiveIdentity) {
            return resolveKnownRunId(sessionId = incomingSessionId, turnId = incomingTurnId)
        }
        return resolveRunId(sessionId = incomingSessionId, turnId = incomingTurnId, fallback = fallback)
    }

    /** Looks up only an already admitted ACP identity; never binds. */
    fun resolveKnownRunId(sessionId: String? = null, turnId: String? = null): String? {
        val key = acpTurnKey(sessionId = sessionId, turnId = turnId)
        if (key.isNotEmpty()) acpTurnToRunIds[key]?.let { return it }
        val turnOnlyKey = acpTurnKey(turnId = turnId)
        if (turnOnlyKey.isNotEmpty()) return acpTurnToRunIds[turnOnlyKey]
        return null
    }

    fun rememberProcessedAcpEventId(eventId: String): Boolean {
        val normalized = eventId.trim()
        if (normalized.isEmpty()) return true
        return processedAcpEventIds.add(normalized)
    }

    fun hasProcessedAcpEventId(eventId: String): Boolean {
        val normalized = eventId.trim()
        return normalized.isNotEmpty() && normalized in processedAcpEventIds
    }

    fun rememberCompletedAcpTurn(turnId: String) {
        val normalized = turnId.trim()
        if (normalized.isEmpty()) return
        completedAcpTurnIds.add(normalized)
    }

    fun rememberAcpCompatibilityDiagnostic(
        reason: String,
        method: String,
        sessionId: String? = null,
        turnId: String? = null,
        itemId: String? = null,
        messageId: String? = null,
        legacy: Boolean = false,
    ): Boolean {
        val entry: JsonMap = linkedMapOf("reason" to reason, "method" to method)
        normalizedOrNull(sessionId)?.let { entry["sessionId"] = it }
        normalizedOrNull(turnId)?.let { entry["turnId"] = it }
        normalizedOrNull(itemId)?.let { entry["itemId"] = it }
        normalizedOrNull(messageId)?.let { entry["messageId"] = it }
        if (legacy) entry["legacy"] = true
        entry["at"] = clock()
        val shouldWarnUser = reason == "turn_id_missing" && !acpCompatibilityWarningShown
        acpCompatibilityWarningShown = acpCompatibilityWarningShown || shouldWarnUser
        acpCompatibilityDiagnostics.add(entry)
        Log.d(
            TAG,
            "[ACP compatibility] quarantined $method: $reason" +
                (if (sessionId == null) "" else " session=$sessionId") +
                (if (turnId == null) "" else " turn=$turnId") +
                (if (itemId == null) "" else " item=$itemId") +
                (if (messageId == null) "" else " message=$messageId") +
                (if (legacy) " legacy=true" else ""),
        )
        return shouldWarnUser
    }

    fun standaloneProcessOwner(processId: String, fallbackRunId: String): String {
        val normalizedProcessId = processId.trim()
        val normalizedFallback = fallbackRunId.trim()
        if (normalizedProcessId.isEmpty() || normalizedFallback.isEmpty()) return normalizedFallback
        val existing = standaloneProcessRunIds[normalizedProcessId]
        if (!existing.isNullOrEmpty()) return existing
        standaloneProcessRunIds[normalizedProcessId] = normalizedFallback
        return normalizedFallback
    }

    fun acpTurnIdForRun(runId: String): String? {
        val normalized = runId.trim()
        if (normalized.isEmpty()) return null
        // A direct/legacy event can already map a protocol turn to this run.
        if (activeRunId == normalized && activeAcpTurnId != null) return activeAcpTurnId
        for ((key, value) in acpTurnToRunIds) {
            if (value != normalized) continue
            val separator = key.lastIndexOf(':')
            return if (separator == -1) key else key.substring(separator + 1)
        }
        return null
    }

    fun acceptsAcpEvent(
        sessionId: String? = null,
        turnId: String? = null,
        allowCompletedTurnMetadata: Boolean = false,
        allowSessionAdmission: Boolean = false,
    ): Boolean {
        val incomingSessionId = sessionId?.trim().orEmpty()
        val incomingTurnId = turnId?.trim().orEmpty()
        // A sessionless event is a legacy shape, but may not bypass the turn
        // tombstone below.
        if (incomingSessionId.isEmpty() && incomingTurnId.isEmpty()) return true
        if (incomingSessionId in retiredAcpSessionIds) {
            val canReactivate = allowRetiredAcpSessionReactivation && incomingTurnIdIsNewForRuntime(incomingTurnId)
            if (!canReactivate) return false
            retiredAcpSessionIds.remove(incomingSessionId)
            allowRetiredAcpSessionReactivation = false
        }
        if (incomingSessionId.isNotEmpty()) knownAcpSessionIds.add(incomingSessionId)
        // A completed turn stays fenced even after its session becomes idle.
        if (incomingTurnId.isNotEmpty() &&
            (incomingTurnId in completedAgentTurnIds || incomingTurnId in completedAcpTurnIds)
        ) {
            val activeTurnId = activeAcpTurnId?.trim() ?: currentDispatchTurnId?.trim() ?: ""
            val currentSessionId = activeAcpSessionId?.trim().orEmpty()
            if (!allowCompletedTurnMetadata || activeTurnId.isNotEmpty() ||
                (currentSessionId.isNotEmpty() && currentSessionId != incomingSessionId)
            ) {
                return false
            }
            return true
        }
        if (incomingSessionId.isEmpty()) return true

        val currentSessionId = activeAcpSessionId?.trim().orEmpty()
        if (currentSessionId.isEmpty()) {
            if (!allowSessionAdmission) return false
            activeAcpSessionId = incomingSessionId
            return true
        }
        if (currentSessionId == incomingSessionId) return true
        val currentTurnId = activeAcpTurnId?.trim() ?: currentDispatchTurnId?.trim() ?: ""
        // Another session may replace the current one only while the local
        // task waits for its first official turn.
        if (currentTurnId.isNotEmpty() && activeAcpTurnId?.trim()?.isNotEmpty() == true) return false
        // Arriving first does not grant ownership; the host reservation must.
        if (!allowSessionAdmission) return false
        activeAcpSessionId = incomingSessionId
        return true
    }

    fun incomingTurnIdIsNewForRuntime(turnId: String): Boolean =
        turnId.isNotEmpty() &&
            turnId !in completedAgentTurnIds &&
            turnId !in completedAcpTurnIds &&
            (currentDispatchTurnId?.trim()?.isNotEmpty() == true || activeRunId?.trim()?.isNotEmpty() == true)

    private companion object {
        const val TAG = "ChatRuntimeState"
        const val LOCAL_SNAPSHOT_ECHO_SUPPRESSION_MILLIS = 2_000L
    }
}
