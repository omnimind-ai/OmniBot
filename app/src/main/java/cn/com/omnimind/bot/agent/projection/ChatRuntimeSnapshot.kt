package cn.com.omnimind.bot.agent.projection

import java.util.Collections

/**
 * Immutable UI snapshot of one runtime: exactly what the Dart
 * `ChatRuntimeView` exposes. Surfaces render from it and never see the
 * mutable [ChatConversationRuntimeState].
 *
 * [revision] increases on every published change of this runtime, so the
 * Flutter adapter can discard out-of-order deliveries; the message-list
 * revisions keep row-level refresh (content vs structure mutations).
 */
data class ChatRuntimeSnapshot(
    val conversationId: Int,
    val mode: String,
    val revision: Long,
    val conversation: Map<String, Any?>?,
    val messages: List<ChatMessage>,
    val structureRevision: Int,
    val lastMutationRevision: Int,
    val lastMutationAffectsPageChrome: Boolean,
    val lastMutationKind: MessageListMutationKind,
    val currentAiMessages: Map<String, String>,
    val currentThinkingMessages: Map<String, String>,
    val isAiResponding: Boolean,
    val isContextCompressing: Boolean,
    val isCheckingExecutableTask: Boolean,
    val isExecutingTask: Boolean,
    val isInputAreaVisible: Boolean,
    val isDeepThinking: Boolean,
    val deepThinkingContent: String,
    val currentThinkingStage: Int,
    val hasInFlightTask: Boolean,
    val activeRunId: String?,
    val activeAcpTurnId: String?,
    val activeAcpSessionId: String?,
    val lastAgentTurnId: String?,
    val activeAgentTurnIds: Set<String>,
    val activeToolCardId: String?,
    val activeThinkingCardId: String?,
    val activeContextCompactionMarkerId: String?,
    val pendingAgentTextTaskId: String?,
    val pendingThinkingRoundSplit: Boolean,
    val toolCardSequence: Int,
    val thinkingRound: Int,
    val chatIslandDisplayLayer: String,
    val lastAgentToolType: String?,
    val browserSessionSnapshot: Map<String, Any?>?,
    val availableAcpCommands: List<Map<String, Any?>>,
    val acpConfigOptions: List<Map<String, Any?>>,
    val currentAcpModeId: String?,
    val acpSessionInfo: Map<String, Any?>,
    val shouldSuppressLocalMessageSnapshotEcho: Boolean,
    val isEphemeral: Boolean,
    /** Local task ids currently bound to this runtime (Dart `_taskBindings`). */
    val boundTaskIds: Set<String> = emptySet(),
) {
    val currentDispatchTurnId: String? get() = activeRunId

    /**
     * Flutter `StandardMessageCodec` form consumed by the snapshot adapter.
     *
     * Messages travel as `messageIds` (full newest-first order) plus only the
     * [changedMessages] the adapter has not seen, so a streamed chunk costs
     * one message instead of the whole conversation. Text caches travel as
     * keys only: surfaces test presence, never content.
     */
    fun toChannelMap(changedMessages: List<ChatMessage>): Map<String, Any?> = linkedMapOf(
        "conversationId" to conversationId,
        "mode" to mode,
        "revision" to revision,
        "conversation" to conversation,
        "messageIds" to messages.map { it.id },
        "changedMessages" to changedMessages.map { messageToChannel(it) },
        "structureRevision" to structureRevision,
        "lastMutationRevision" to lastMutationRevision,
        "lastMutationAffectsPageChrome" to lastMutationAffectsPageChrome,
        "lastMutationKind" to lastMutationKind.name,
        "currentAiMessageKeys" to currentAiMessages.keys.toList(),
        "currentThinkingMessageKeys" to currentThinkingMessages.keys.toList(),
        "isAiResponding" to isAiResponding,
        "isContextCompressing" to isContextCompressing,
        "isCheckingExecutableTask" to isCheckingExecutableTask,
        "isExecutingTask" to isExecutingTask,
        "isInputAreaVisible" to isInputAreaVisible,
        "isDeepThinking" to isDeepThinking,
        "deepThinkingContent" to deepThinkingContent,
        "currentThinkingStage" to currentThinkingStage,
        "hasInFlightTask" to hasInFlightTask,
        "activeRunId" to activeRunId,
        "activeAcpTurnId" to activeAcpTurnId,
        "activeAcpSessionId" to activeAcpSessionId,
        "lastAgentTurnId" to lastAgentTurnId,
        "activeAgentTurnIds" to activeAgentTurnIds.toList(),
        "activeToolCardId" to activeToolCardId,
        "activeThinkingCardId" to activeThinkingCardId,
        "activeContextCompactionMarkerId" to activeContextCompactionMarkerId,
        "pendingAgentTextTaskId" to pendingAgentTextTaskId,
        "pendingThinkingRoundSplit" to pendingThinkingRoundSplit,
        "toolCardSequence" to toolCardSequence,
        "thinkingRound" to thinkingRound,
        "chatIslandDisplayLayer" to chatIslandDisplayLayer,
        "lastAgentToolType" to lastAgentToolType,
        "browserSessionSnapshot" to browserSessionSnapshot,
        "availableAcpCommands" to availableAcpCommands,
        "acpConfigOptions" to acpConfigOptions,
        "currentAcpModeId" to currentAcpModeId,
        "acpSessionInfo" to acpSessionInfo,
        "shouldSuppressLocalMessageSnapshotEcho" to shouldSuppressLocalMessageSnapshotEcho,
        "isEphemeral" to isEphemeral,
        "boundTaskIds" to boundTaskIds.toList(),
    )

    companion object {
        internal fun of(
            state: ChatConversationRuntimeState,
            revision: Long,
            isEphemeral: Boolean,
            boundTaskIds: Set<String> = emptySet(),
        ) =
            ChatRuntimeSnapshot(
                conversationId = state.conversationId,
                mode = state.mode,
                revision = revision,
                conversation = state.conversation?.let { readOnlyMap(it) },
                messages = Collections.unmodifiableList(state.messages.toList()),
                structureRevision = state.messages.structureRevision,
                lastMutationRevision = state.messages.lastMutationRevision,
                lastMutationAffectsPageChrome = state.messages.lastMutationAffectsPageChrome,
                lastMutationKind = state.messages.lastMutationKind,
                currentAiMessages = Collections.unmodifiableMap(LinkedHashMap(state.currentAiMessages)),
                currentThinkingMessages = Collections.unmodifiableMap(LinkedHashMap(state.currentThinkingMessages)),
                isAiResponding = state.isAiResponding,
                isContextCompressing = state.isContextCompressing,
                isCheckingExecutableTask = state.isCheckingExecutableTask,
                isExecutingTask = state.isExecutingTask,
                isInputAreaVisible = state.isInputAreaVisible,
                isDeepThinking = state.isDeepThinking,
                deepThinkingContent = state.deepThinkingContent,
                currentThinkingStage = state.currentThinkingStage,
                hasInFlightTask = state.hasInFlightTask,
                activeRunId = state.activeRunId,
                activeAcpTurnId = state.activeAcpTurnId,
                activeAcpSessionId = state.activeAcpSessionId,
                lastAgentTurnId = state.lastAgentTurnId,
                activeAgentTurnIds = Collections.unmodifiableSet(LinkedHashSet(state.activeAgentTurnIds)),
                activeToolCardId = state.activeToolCardId,
                activeThinkingCardId = state.activeThinkingCardId,
                activeContextCompactionMarkerId = state.activeContextCompactionMarkerId,
                pendingAgentTextTaskId = state.pendingAgentTextTaskId,
                pendingThinkingRoundSplit = state.pendingThinkingRoundSplit,
                toolCardSequence = state.toolCardSequence,
                thinkingRound = state.thinkingRound,
                chatIslandDisplayLayer = state.chatIslandDisplayLayer,
                lastAgentToolType = state.lastAgentToolType,
                browserSessionSnapshot = state.browserSessionSnapshot?.let { readOnlyMap(it) },
                availableAcpCommands = Collections.unmodifiableList(state.availableAcpCommands.map { readOnlyMap(it) }),
                acpConfigOptions = Collections.unmodifiableList(state.acpConfigOptions.map { readOnlyMap(it) }),
                currentAcpModeId = state.currentAcpModeId,
                acpSessionInfo = readOnlyMap(state.acpSessionInfo),
                shouldSuppressLocalMessageSnapshotEcho = state.shouldSuppressLocalMessageSnapshotEcho,
                isEphemeral = isEphemeral,
                boundTaskIds = Collections.unmodifiableSet(LinkedHashSet(boundTaskIds)),
            )
    }
}

/** Deep, read-only copy: the snapshot never aliases mutable runtime state. */
@Suppress("UNCHECKED_CAST")
private fun readOnlyMap(value: Map<String, Any?>): Map<String, Any?> = readOnly(value) as Map<String, Any?>

private fun readOnly(value: Any?): Any? = when (value) {
    is Map<*, *> -> {
        val copy = LinkedHashMap<String, Any?>(value.size)
        for ((key, nested) in value) copy[key.toString()] = readOnly(nested)
        Collections.unmodifiableMap(copy)
    }
    is List<*> -> Collections.unmodifiableList(value.map { readOnly(it) })
    else -> value
}

/** Message JSON for the channel; `createAt` as epoch millis (exact round trip). */
fun messageToChannel(message: ChatMessage): Map<String, Any?> =
    LinkedHashMap(message.toJson()).apply { put("createAt", message.createAtMillis) }
