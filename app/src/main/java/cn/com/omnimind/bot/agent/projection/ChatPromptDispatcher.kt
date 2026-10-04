package cn.com.omnimind.bot.agent.projection

import android.util.Log
import cn.com.omnimind.bot.agent.AgentRuntimeErrorSupport

/**
 * The single native entry for chat prompt admission (batch 5b).
 *
 * Every UI prompt runs through here: the session reservation
 * (`session/new` + coordinator binding), `session/prompt`, and the official
 * PromptResponse or transport error, which is applied through the one
 * coordinator/reducer. Cancellation (`session/cancel`), JSON-RPC
 * `$/cancel_request` and server-request answers (`respondToServerRequest`)
 * pass through the same entry. The UI only expresses intents and reads the
 * resulting runtime snapshot; it never calls the ACP transport for these.
 *
 * Page navigation state (which conversation is visible, Harness switching)
 * stays with the page: the page checks its own target between [prepareTurnSession]
 * and [submitTurnPrompt] and releases the reservation with
 * [releaseTurnSession] when it moved on.
 */
class ChatPromptDispatcher(
    private val coordinator: ChatConversationRuntimeCoordinator,
    private val transport: suspend (method: String, args: Map<String, Any?>) -> Any?,
) {
    data class TurnTarget(val taskId: String, val conversationId: Int, val mode: String)

    /**
     * Reserves the official ACP session for an admitted local run. Returns
     * `ready` with the session id, `abandoned` when the run lost ownership
     * (a session created here is closed again), or `failed` after the error
     * was applied to the runtime as this run's PromptResponse.
     */
    suspend fun prepareTurnSession(
        target: TurnTarget,
        existingSessionId: String?,
        sessionArgs: Map<String, Any?>,
        clearThinkingOnFailure: Boolean = false,
    ): Map<String, Any?> {
        if (!ownsTurn(target)) return status("abandoned")
        val existing = existingSessionId?.trim().orEmpty()
        val sessionId: String
        val created: Boolean
        try {
            if (existing.isNotEmpty()) {
                sessionId = existing
                created = false
            } else {
                val response = copyStringMap(transport("session/new", sessionArgs)).orEmpty()
                sessionId = dartToString(response["sessionId"] ?: response["threadId"])?.trim().orEmpty()
                check(sessionId.isNotEmpty()) { "ACP session/new did not return a session id" }
                created = true
            }
        } catch (error: Throwable) {
            failTurn(target, "session/new", error, existingSessionId, clearThinkingOnFailure)
            return status("failed")
        }
        if (!ownsTurn(target) ||
            !coordinator.bindAcpSession(target.taskId, target.conversationId, target.mode, sessionId)
        ) {
            if (created) closeSession(sessionId, target.conversationId)
            return status("abandoned")
        }
        return linkedMapOf("status" to "ready", "sessionId" to sessionId, "created" to created)
    }

    /** Releases a reservation whose page target moved on before the prompt. */
    suspend fun releaseTurnSession(target: TurnTarget, closeSessionId: String?) {
        closeSessionId?.trim()?.takeIf { it.isNotEmpty() }?.let { closeSession(it, target.conversationId) }
        coordinator.unregisterTask(target.taskId, conversationId = target.conversationId, mode = target.mode)
    }

    /**
     * Sends `session/prompt` and applies its official result (or the
     * transport error) as this run's PromptResponse. Returns the raw response
     * for page follow-ups (thread/session pointers).
     */
    suspend fun submitTurnPrompt(
        target: TurnTarget,
        promptArgs: Map<String, Any?>,
        fallbackSessionId: String?,
        conversation: Map<String, Any?>? = null,
        clearThinkingOnFailure: Boolean = false,
    ): Map<String, Any?> {
        val response = try {
            normalizePromptResponse(copyStringMap(transport("session/prompt", promptArgs)).orEmpty())
        } catch (error: Throwable) {
            val result = failTurn(target, "session/prompt", error, fallbackSessionId, clearThinkingOnFailure)
            return linkedMapOf("status" to "failed", "result" to result)
        }
        val result = coordinator.applyAcpPromptResponse(
            taskId = target.taskId,
            conversationId = target.conversationId,
            sessionId = firstString(response["sessionId"], response["threadId"]) ?: fallbackSessionId,
            turnId = firstString(response["promptId"], response["turnId"]),
            stopReason = firstString(response["stopReason"], response["status"]),
            error = firstString(response["error"]),
            mode = target.mode,
            conversation = conversation,
        )
        return linkedMapOf("status" to "completed", "response" to response, "result" to result)
    }

    /**
     * A prompt with no chat runtime (scheduled Sub Agent runs): its
     * `session/update` stream is projected by identity like any other.
     */
    suspend fun submitDetachedPrompt(args: Map<String, Any?>): Map<String, Any?> =
        normalizePromptResponse(copyStringMap(transport("session/prompt", args)).orEmpty())

    /** `session/cancel`: requests cancellation; PromptResponse still ends the turn. */
    suspend fun cancelTurn(args: Map<String, Any?>): Any? = transport("session/cancel", args)

    /** JSON-RPC request cancellation, not a second Agent lifecycle. */
    suspend fun cancelRequest(args: Map<String, Any?>): Any? = transport("\$/cancel_request", args)

    /** Answers an Agent server request (approval, user input, elicitation). */
    suspend fun respondToServerRequest(args: Map<String, Any?>): Any? = transport("respondToServerRequest", args)

    private fun ownsTurn(target: TurnTarget) =
        coordinator.isTaskActive(target.taskId, target.conversationId, target.mode)

    private fun failTurn(
        target: TurnTarget,
        method: String,
        error: Throwable,
        fallbackSessionId: String?,
        clearThinkingOnFailure: Boolean,
    ): AgentReduceResult {
        Log.w(TAG, "$method failed for ${target.taskId}: ${error.message}")
        if (clearThinkingOnFailure) {
            coordinator.clearTaskThinkingPresentation(target.taskId, target.conversationId, target.mode)
        }
        val runtime = coordinator.snapshotFor(target.conversationId, target.mode)
        return coordinator.applyAcpPromptResponse(
            taskId = target.taskId,
            conversationId = target.conversationId,
            sessionId = runtime?.activeAcpSessionId ?: fallbackSessionId,
            turnId = runtime?.activeAcpTurnId,
            stopReason = "error",
            error = userFacingError(method, error),
            mode = target.mode,
        )
    }

    private suspend fun closeSession(sessionId: String, conversationId: Int) {
        runCatching {
            transport("session/close", linkedMapOf("sessionId" to sessionId, "conversationId" to conversationId))
        }.onFailure { Log.w(TAG, "ACP abandoned session close failed: ${it.message}") }
    }

    private fun status(value: String): Map<String, Any?> = linkedMapOf("status" to value)

    companion object {
        private const val TAG = "ChatPromptDispatcher"

        /**
         * The error text the UI showed before 5b: the channel reported
         * `userFacingMessage ?: "method: detail"` with the native
         * `failureKind`, and Dart formatted it for display.
         */
        fun userFacingError(method: String, error: Throwable): String {
            val detail = AgentRuntimeErrorSupport.safeDiagnosticMessage(error)
            val raw = AgentRuntimeErrorSupport.userFacingMessage(error)
                ?: "$method: ${detail.ifBlank { error.javaClass.simpleName }}"
            return AgentUserErrorText.format(raw, AgentRuntimeErrorSupport.failureKind(error))
        }

        /** A response error carrying a native `failureKind` is shown formatted. */
        fun normalizePromptResponse(response: Map<String, Any?>): Map<String, Any?> {
            val failureKind = response["failureKind"] as? String
            val error = response["error"] as? String
            if (failureKind == null || error == null) return response
            return LinkedHashMap(response).apply { put("error", AgentUserErrorText.format(error, failureKind)) }
        }
    }
}
