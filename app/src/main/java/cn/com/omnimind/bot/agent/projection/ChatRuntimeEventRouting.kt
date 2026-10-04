package cn.com.omnimind.bot.agent.projection

import android.util.Log

/** Remote Agent (Codex bridge) routing facts published by the chat page. */
data class ChatRuntimeRemoteRoutingContext(
    val activeThreadId: String? = null,
    val activeRemoteRuntimeId: Int? = null,
    /** Agent-mode page-local messages promoted with a remote thread. */
    val agentFallbackMessages: List<ChatMessage> = emptyList(),
    val agentConversation: Map<String, Any?>? = null,
)

/**
 * Attribution facts a mounted surface publishes. A page surface resolves
 * events by identity and falls back to its visible conversation; a
 * dispatch-scoped surface (the command overlay sheet) claims only events for
 * its explicit conversation while a prompt is in flight.
 */
data class ChatRuntimeRoutingContext(
    val dispatchScoped: Boolean,
    /** Runtime mode key of the surface's visible mode. */
    val activeMode: String,
    val conversationIdsByMode: Map<String, Int?> = emptyMap(),
    val conversationsByMode: Map<String, Map<String, Any?>?> = emptyMap(),
    /** Non-null only while the remote Agent runtime is configured. */
    val remote: ChatRuntimeRemoteRoutingContext? = null,
    val scopedConversationId: Int? = null,
    val scopedConversation: Map<String, Any?>? = null,
) {
    companion object {
        fun page(
            activeMode: String,
            conversationIdsByMode: Map<String, Int?> = emptyMap(),
            conversationsByMode: Map<String, Map<String, Any?>?> = emptyMap(),
            remote: ChatRuntimeRemoteRoutingContext? = null,
        ) = ChatRuntimeRoutingContext(
            dispatchScoped = false,
            activeMode = activeMode,
            conversationIdsByMode = conversationIdsByMode,
            conversationsByMode = conversationsByMode,
            remote = remote,
        )

        fun dispatchScoped(conversationId: Int, mode: String, conversation: Map<String, Any?>? = null) =
            ChatRuntimeRoutingContext(
                dispatchScoped = true,
                activeMode = mode,
                scopedConversationId = conversationId,
                scopedConversation = conversation,
            )
    }
}

/** The single projection result of one event, delivered to every surface. */
data class ChatRuntimeEventOutcome(
    val event: Map<String, Any?>,
    val conversationId: Int,
    val mode: String,
    val result: AgentReduceResult,
    /** Set when the event promoted a remote thread into the visible runtime. */
    val promotedRemoteThreadId: String? = null,
)

/** Registration of one mounted surface; [detach] when it disposes. */
class ChatRuntimeEventHost internal constructor(
    private val coordinator: ChatConversationRuntimeCoordinator,
    internal val context: () -> ChatRuntimeRoutingContext?,
    internal val onOutcome: (ChatRuntimeEventOutcome) -> Unit,
) {
    fun detach() = coordinator.detachEventHost(this)
}

private val remoteAgentEnvelopeKeys = listOf("message", "payload", "data", "event", "notification", "params", "result")

fun remoteAgentEventThreadId(event: Map<String, Any?>): String? = remoteAgentThreadIdFromEnvelope(event, 0)

private fun remoteAgentThreadIdFromEnvelope(value: Any?, depth: Int): String? {
    if (depth > 6) return null
    val map = copyStringMap(value) ?: return null
    stringOrNull(map["threadId"] ?: map["thread_id"])?.let { return it }
    stringOrNull(copyStringMap(map["thread"])?.get("id"))?.let { return it }
    for (key in remoteAgentEnvelopeKeys) {
        val nested = map[key] ?: continue
        remoteAgentThreadIdFromEnvelope(nested, depth + 1)?.let { return it }
    }
    return null
}

/** Stable local runtime id of a remote Agent thread (Dart `_remoteCodexRuntimeId`). */
fun remoteAgentRuntimeIdForThread(seed: String): Int {
    var hash = 0x45d9f3bL
    for (codeUnit in seed) {
        hash = 0x1fffffffL and (hash * 31 + codeUnit.code)
    }
    return -((hash and 0x3fffffffL) + 1).toInt()
}

/**
 * Port of the Dart `_ChatRuntimeEventRouting` extension: resolves one event
 * to exactly one runtime and applies it.
 *
 * Ownership order: explicit host conversation id, promoted/known remote
 * thread, admitted session/turn identity, first owner of a legacy process,
 * and finally the visible Agent conversation for identity-less errors and
 * process output.
 */
internal object ChatRuntimeEventRouter {
    private const val TAG = "ChatRuntimeEventRouter"

    fun route(
        coordinator: ChatConversationRuntimeCoordinator,
        event: Map<String, Any?>,
        contexts: List<ChatRuntimeRoutingContext>,
    ): ChatRuntimeEventOutcome? {
        if (contexts.isEmpty()) return null
        val page = contexts.firstOrNull { !it.dispatchScoped }
        val scoped = contexts.filter { it.dispatchScoped }
        val method = stringOrNull(event["method"]) ?: stringOrNull(copyStringMap(event["message"])?.get("method"))
        val explicitConversationId = routingInt(event["conversationId"])
        val scopedClaim = scoped.firstOrNull {
            explicitConversationId != null && it.scopedConversationId == explicitConversationId
        }
        if (page == null) {
            // Without a page only a dispatch-scoped surface listens, and it
            // claims only its own explicit conversation.
            if (scopedClaim == null) return null
            return apply(
                coordinator,
                event = event,
                conversationId = explicitConversationId!!,
                mode = scopedClaim.activeMode,
                conversation = scopedClaim.scopedConversation,
            )
        }
        val eventSessionId = acpEventSessionId(event)
        val eventTurnId = acpEventTurnId(event)
        val remote = page.remote
        val rawThreadId = remoteAgentEventThreadId(event)
        val eventThreadId = if (remote == null) null else rawThreadId
        val standaloneProcessId = standaloneProcessIdOf(event)
        val standaloneProcessOwner = standaloneProcessId?.let { coordinator.conversationIdForStandaloneProcess(it) }
        val hasProtocolIdentity = eventSessionId != null || eventTurnId != null || rawThreadId != null
        val canUseVisibleFallback = method == "error" || standaloneProcessId != null
        val identityConversationId = if (explicitConversationId == null) {
            coordinator.conversationIdForAcpEvent(sessionId = eventSessionId, turnId = eventTurnId)
        } else {
            null
        }
        val mappedRemoteConversationId = eventThreadId?.let { remoteAgentRuntimeIdForThread(it) }
        val shouldPromoteRemoteEvent = eventThreadId != null &&
            shouldPromoteRemoteThread(coordinator, page, eventThreadId, mappedRemoteConversationId!!)
        var promotedRemoteThreadId: String? = null
        val conversationId = explicitConversationId
            ?: (
                if (shouldPromoteRemoteEvent) {
                    promotedRemoteThreadId = eventThreadId
                    coordinator.activateRemoteThreadRuntime(
                        eventThreadId!!,
                        fallbackMessages = remote!!.agentFallbackMessages,
                        conversation = remote.agentConversation,
                    )
                } else {
                    mappedRemoteConversationId
                }
                )
            ?: identityConversationId
            ?: standaloneProcessOwner
            ?: (
                if (!hasProtocolIdentity && canUseVisibleFallback) {
                    page.conversationIdsByMode[CHAT_RUNTIME_MODE_AGENT]
                } else {
                    null
                }
                )
        if (conversationId == null) {
            Log.d(
                TAG,
                "[Agent] dropping $method — no safe ACP owner (remoteCodex=${remote != null}, " +
                    "eventSessionId=$eventSessionId, eventTurnId=$eventTurnId, eventThreadId=$eventThreadId)",
            )
            return null
        }
        if (eventThreadId != null && !shouldPromoteRemoteEvent) {
            coordinator.ensureRemoteThreadRuntime(eventThreadId)
        }
        // A promotion makes the remote runtime the visible Agent conversation.
        val conversationIds = LinkedHashMap(page.conversationIdsByMode)
        if (promotedRemoteThreadId != null) conversationIds[CHAT_RUNTIME_MODE_AGENT] = conversationId
        val ownerMode = coordinator.modeForAcpEvent(conversationId, sessionId = eventSessionId, turnId = eventTurnId)
        val mode = when {
            ownerMode != null -> ownerMode
            scopedClaim != null -> scopedClaim.activeMode
            remote != null ||
                conversationId == conversationIds[CHAT_RUNTIME_MODE_AGENT] ||
                event["conversationMode"] == ConversationModes.AGENT -> CHAT_RUNTIME_MODE_AGENT
            conversationId == conversationIds[CHAT_RUNTIME_MODE_NORMAL] -> CHAT_RUNTIME_MODE_NORMAL
            else -> page.activeMode
        }
        val visibleConversation = when {
            scopedClaim != null && scopedClaim.activeMode == mode -> scopedClaim.scopedConversation
            page.activeMode == mode && conversationIds[mode] == conversationId ->
                if (promotedRemoteThreadId != null) {
                    coordinator.snapshotFor(conversationId, CHAT_RUNTIME_MODE_AGENT)?.conversation
                } else {
                    page.conversationsByMode[mode]
                }
            else -> null
        }
        return apply(coordinator, event, conversationId, mode, visibleConversation, promotedRemoteThreadId)
    }

    private fun apply(
        coordinator: ChatConversationRuntimeCoordinator,
        event: Map<String, Any?>,
        conversationId: Int,
        mode: String,
        conversation: Map<String, Any?>?,
        promotedRemoteThreadId: String? = null,
    ): ChatRuntimeEventOutcome {
        val result = coordinator.applyAgentEvent(conversationId, event, mode = mode, conversation = conversation)
        if (!result.handled && result.method != "codex/stderr" && result.method != "codex/parseError") {
            Log.d(TAG, "[Agent] unhandled ACP event: ${runCatching { DartJson.encode(event) }.getOrNull()}")
        }
        return ChatRuntimeEventOutcome(event, conversationId, mode, result, promotedRemoteThreadId)
    }

    private fun shouldPromoteRemoteThread(
        coordinator: ChatConversationRuntimeCoordinator,
        page: ChatRuntimeRoutingContext,
        threadId: String,
        runtimeId: Int,
    ): Boolean {
        val remote = page.remote!!
        val activeThreadId = remote.activeThreadId?.trim()
        if (activeThreadId == threadId) return true
        val currentConversationId = page.conversationIdsByMode[CHAT_RUNTIME_MODE_AGENT]
        if (currentConversationId == runtimeId) return true
        if (!activeThreadId.isNullOrEmpty()) return false
        if (currentConversationId == null || currentConversationId != remote.activeRemoteRuntimeId) return false
        val runtime = coordinator.snapshotFor(currentConversationId, CHAT_RUNTIME_MODE_AGENT)
        return remote.agentFallbackMessages.isNotEmpty() || (runtime?.hasInFlightTask ?: false)
    }

    private fun standaloneProcessIdOf(event: Map<String, Any?>): String? {
        val params = copyStringMap(event["params"])
        for (value in listOf(
            event["processId"], event["process_id"], event["processHandle"], event["process_handle"],
            params?.get("processId"), params?.get("process_id"), params?.get("processHandle"),
            params?.get("process_handle"),
        )) {
            val normalized = dartToString(value)?.trim().orEmpty()
            if (normalized.isNotEmpty()) return normalized
        }
        return null
    }

    private fun routingInt(value: Any?): Int? = when (value) {
        is Int -> value
        is Number -> value.toInt()
        else -> dartToString(value)?.toIntOrNull()
    }
}
