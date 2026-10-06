package cn.com.omnimind.bot.agent.projection

import android.util.Log

/**
 * The single native send orchestration of a chat turn (batch 5d-0b).
 *
 * A UI hands it an admitted submission with every per-turn setting already
 * frozen ([ChatTurnRequest]). The launcher admits the run on the target
 * runtime, persists the admission snapshot, reserves the ACP session and
 * sends the prompt through [ChatPromptDispatcher], and reports what the
 * page should adopt ([ChatTurnOutcome]). It replaces the three Dart dispatch
 * paths (`_sendAgentMessage`, `_sendPureChatMessage`, `_tryAgentFlow`) and
 * the command-overlay copy, which each repeated this sequence.
 *
 * Navigation stays with the caller: [isTargetCurrent] is checked between the
 * awaits, and a run whose target moved on is released (and a session it
 * created is closed) instead of prompting. Conversation creation stays with
 * the caller too for now: the page owns the drawer refresh and target
 * persistence that follow it, so a submission arrives with its conversation.
 *
 * Fixed while moving (see docs/native-compose-migration.md, 5d-0):
 * - errors are applied to this run's own runtime, never the visible one;
 * - session pointers are reported only for a target that is still current,
 *   never written before the prompt is sent;
 * - the prompt carries the submitted text and attachments, not a re-read of
 *   the newest user message, and excluded attachments are never forwarded.
 */
class ChatTurnLauncher(
    private val coordinator: ChatConversationRuntimeCoordinator,
    private val dispatcher: ChatPromptDispatcher,
) {
    /**
     * Runs one turn. [isTargetCurrent] answers whether the UI still shows
     * the target that submitted it; it is read after every await.
     */
    suspend fun launchTurn(
        request: ChatTurnRequest,
        isTargetCurrent: () -> Boolean = { true },
    ): ChatTurnOutcome {
        val target = ChatPromptDispatcher.TurnTarget(request.taskId, request.conversationId, request.mode)
        if (request.text.isEmpty() && request.attachments.isEmpty()) return ChatTurnOutcome.rejected("empty")
        if (!isTargetCurrent()) return ChatTurnOutcome.rejected("stale")

        coordinator.beginAcpTurn(target.taskId, target.conversationId, target.mode)
        request.userMessage?.let { insertUserMessage(target, it) }

        if (!coordinator.isEphemeralRuntime(target.conversationId, target.mode)) {
            // The admission snapshot is the durable user-input boundary. After
            // it, every newer snapshot belongs to the runtime's own tail.
            val persisted = runCatching {
                coordinator.persistRuntimeConversation(target.conversationId, target.mode, persistMessages = true).await()
            }
            persisted.exceptionOrNull()?.let { error ->
                return fail(target, "persist", error, request.clearThinkingOnFailure)
            }
        }
        if (!isTargetCurrent()) {
            coordinator.unregisterTask(target.taskId, target.conversationId, target.mode)
            return ChatTurnOutcome.rejected("stale")
        }

        val prepared = dispatcher.prepareTurnSession(
            target = target,
            existingSessionId = request.existingSessionId,
            sessionArgs = newSessionArguments(
                conversationId = request.conversationId,
                model = request.model,
                effort = request.effort,
                collaborationMode = request.collaborationMode,
                conversationMode = request.conversationMode,
            ),
            clearThinkingOnFailure = request.clearThinkingOnFailure,
        )
        when (prepared["status"]) {
            // The failure is already this run's PromptResponse.
            "failed" -> return ChatTurnOutcome(status = ChatTurnOutcome.Status.Failed)
            "ready" -> Unit
            else -> {
                coordinator.unregisterTask(target.taskId, target.conversationId, target.mode)
                return ChatTurnOutcome.rejected("abandoned")
            }
        }
        val sessionId = prepared["sessionId"] as String
        if (!isTargetCurrent()) {
            dispatcher.releaseTurnSession(target, closeSessionId = sessionId.takeIf { prepared["created"] == true })
            return ChatTurnOutcome.rejected("stale")
        }

        val submitted = dispatcher.submitTurnPrompt(
            target = target,
            promptArgs = promptSessionArguments(
                text = buildUserPromptText(request.text, request.attachments),
                sessionId = sessionId,
                conversationId = request.conversationId,
                requestId = request.requestId,
                agentId = request.agentId,
                attachments = modelAttachments(request.attachments),
                permission = request.permission,
                model = request.model,
                effort = request.effort,
                collaborationMode = request.collaborationMode,
                conversationMode = request.conversationMode,
                terminalEnvironment = request.terminalEnvironment,
            ),
            fallbackSessionId = sessionId,
            conversation = request.conversation,
            clearThinkingOnFailure = request.clearThinkingOnFailure,
        )
        val completed = submitted["status"] == "completed"
        val response = copyStringMap(submitted["response"]).orEmpty()
        // Pointers describe the submitting target; a page that moved on must
        // not adopt them.
        val current = isTargetCurrent()
        return ChatTurnOutcome(
            status = if (completed) ChatTurnOutcome.Status.Completed else ChatTurnOutcome.Status.Failed,
            sessionId = if (current) firstNonBlank(response["sessionId"], response["threadId"]) ?: sessionId else null,
            threadId = if (current && completed) firstNonBlank(response["threadId"]) else null,
            turnId = if (current) firstNonBlank(response["promptId"], response["turnId"]) else null,
            responseConversationId = if (current && completed) asInt(response["conversationId"]) else null,
            targetCurrent = current,
        )
    }

    /**
     * The user row is part of admission: it joins the runtime before the
     * snapshot is persisted, keyed by id so a later echo replaces it in place.
     */
    private fun insertUserMessage(target: ChatPromptDispatcher.TurnTarget, message: ChatMessage) {
        val snapshot = coordinator.snapshotFor(target.conversationId, target.mode)
        if (snapshot?.messages?.any { it.id == message.id } == true) return
        coordinator.insertRuntimeMessage(target.conversationId, target.mode, message)
    }

    /** Applies a failure before transport to this run's own runtime. */
    private fun fail(
        target: ChatPromptDispatcher.TurnTarget,
        step: String,
        error: Throwable,
        clearThinking: Boolean,
    ): ChatTurnOutcome {
        Log.w(TAG, "$step failed for ${target.taskId}: ${error.message}")
        if (clearThinking) coordinator.clearTaskThinkingPresentation(target.taskId, target.conversationId, target.mode)
        val runtime = coordinator.snapshotFor(target.conversationId, target.mode)
        coordinator.applyAcpPromptResponse(
            taskId = target.taskId,
            conversationId = target.conversationId,
            sessionId = runtime?.activeAcpSessionId,
            turnId = runtime?.activeAcpTurnId,
            stopReason = "error",
            error = ChatPromptDispatcher.userFacingError(step, error),
            mode = target.mode,
        )
        return ChatTurnOutcome(status = ChatTurnOutcome.Status.Failed)
    }

    private fun firstNonBlank(vararg values: Any?): String? =
        values.firstNotNullOfOrNull { dartToString(it)?.trim()?.ifEmpty { null } }

    private companion object {
        const val TAG = "ChatTurnLauncher"
    }
}

/**
 * One admitted submission with its frozen settings. [taskId] is the run id
 * (`<ms>-ai`) and doubles as the ACP request id.
 */
data class ChatTurnRequest(
    val taskId: String,
    val conversationId: Int,
    /** Runtime mode key (`agent`, `normal`, `openclaw`). */
    val mode: String,
    /** The submitted text exactly as typed. */
    val text: String,
    val attachments: List<Map<String, Any?>> = emptyList(),
    /**
     * The optimistic user row. Null when the caller already inserted it
     * (a retry keeps its row).
     */
    val userMessage: ChatMessage? = null,
    val existingSessionId: String? = null,
    /** ACP Harness; null for pure chat and remote sessions. */
    val agentId: String? = null,
    val permission: AgentPermissionMode? = null,
    val model: String? = null,
    val effort: String? = null,
    val collaborationMode: String? = null,
    /** Conversation storage mode (`agent`, `chat_only`, `subagent`, ...). */
    val conversationMode: String? = null,
    val terminalEnvironment: Map<String, String>? = null,
    val conversation: Map<String, Any?>? = null,
    /** Pure chat drops its optimistic thinking card when the turn fails early. */
    val clearThinkingOnFailure: Boolean = false,
) {
    val requestId: String get() = taskId
}

/** What the submitting page adopts after [ChatTurnLauncher.launchTurn]. */
data class ChatTurnOutcome(
    val status: Status,
    /** Why a run was never sent: `empty`, `stale` or `abandoned`. */
    val rejectedReason: String? = null,
    /** Pointers are null when the target is no longer current. */
    val sessionId: String? = null,
    val threadId: String? = null,
    val turnId: String? = null,
    /** The conversation the runtime reported, when it differs from the request. */
    val responseConversationId: Int? = null,
    val targetCurrent: Boolean = false,
) {
    enum class Status { Completed, Failed, Rejected }

    fun toChannel(): Map<String, Any?> = linkedMapOf(
        "status" to status.name.lowercase(),
        "rejectedReason" to rejectedReason,
        "sessionId" to sessionId,
        "threadId" to threadId,
        "turnId" to turnId,
        "responseConversationId" to responseConversationId,
        "targetCurrent" to targetCurrent,
    )

    companion object {
        fun rejected(reason: String) = ChatTurnOutcome(Status.Rejected, rejectedReason = reason)
    }
}
