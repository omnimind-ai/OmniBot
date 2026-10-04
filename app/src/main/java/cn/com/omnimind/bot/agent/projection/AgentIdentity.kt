package cn.com.omnimind.bot.agent.projection

/**
 * Stable identities shared by the ACP adapter and the chat presentation.
 *
 * Data-only values. ACP owns the protocol fields; the UI owns `cardId` and
 * `runId`. No Harness-specific name belongs here.
 */
data class AgentRunIdentity(
    val runId: String,
    val conversationId: Int,
    val sessionId: String? = null,
    val turnId: String? = null,
    val rpcRequestId: String? = null,
) {
    /**
     * The stable host identity for one UI run, separate from ACP's
     * session/turn: a provider may admit the official turn after the local
     * prompt has already been rendered.
     */
    val normalizedRunId: String get() = runId.trim()
    val normalizedSessionId: String? get() = normalizedOrNull(sessionId)
    val normalizedTurnId: String? get() = normalizedOrNull(turnId)
}

/**
 * The identity carried by one projected ACP item/card. The UI only uses
 * [runId] for turn grouping and [cardId] for replacement, so a provider tool
 * id never becomes a global card key.
 */
data class AgentEventIdentity(
    val runId: String,
    val conversationId: Int? = null,
    val sessionId: String? = null,
    val turnId: String? = null,
    val itemId: String? = null,
    val toolCallId: String? = null,
    val cardId: String? = null,
) {
    val scopedToolKey: String
        get() {
            val session = normalizedOrNull(sessionId) ?: "session-unknown"
            val turn = normalizedOrNull(turnId) ?: "turn-unknown"
            val tool = normalizedOrNull(toolCallId) ?: "tool-unknown"
            return "$session:$turn:$tool"
        }
}

data class AgentToolIdentity(
    val sessionId: String? = null,
    val turnId: String? = null,
    /** The ACP identity used to match tool_call_update. */
    val toolCallId: String? = null,
    /** The provider's original call id, when the adapter normalized it. */
    val rawProviderToolCallId: String? = null,
) {
    val hasAcpIdentity: Boolean get() = nonEmpty(sessionId) && nonEmpty(toolCallId)

    val toolKey: String?
        get() = if (!hasAcpIdentity) null else "${sessionId!!.trim()}:${toolCallId!!.trim()}"

    /** Derives a card id without changing the ACP toolCallId itself. */
    fun cardId(suffix: String, fallback: String): String {
        if (toolKey == null) return fallback
        return "tool:${safeSegment(sessionId!!)}:${safeSegment(toolCallId!!)}:$suffix"
    }

    companion object {
        fun fromMaps(
            raw: Map<String, Any?>? = null,
            existing: Map<String, Any?>? = null,
            sessionId: String? = null,
            turnId: String? = null,
        ): AgentToolIdentity {
            val source = raw ?: emptyMap()
            val old = existing ?: emptyMap()
            val canonical = firstString(
                source["toolCallId"], source["tool_call_id"],
                old["toolCallId"], old["tool_call_id"],
                source["callId"], source["call_id"], source["id"],
                old["callId"], old["call_id"],
            )
            val rawProvider = firstString(
                source["rawProviderToolCallId"], source["raw_provider_tool_call_id"],
                source["providerCallId"], source["provider_call_id"],
                source["callId"], source["call_id"],
                old["rawProviderToolCallId"], old["callId"], old["call_id"],
            )
            return AgentToolIdentity(
                sessionId = firstString(
                    sessionId, source["sessionId"], source["session_id"],
                    old["sessionId"], old["session_id"],
                ),
                turnId = firstString(
                    turnId, source["turnId"], source["turn_id"],
                    old["turnId"], old["turn_id"],
                ),
                toolCallId = canonical,
                rawProviderToolCallId = rawProvider,
            )
        }
    }
}

private val envelopeKeys = listOf(
    "params", "message", "payload", "data", "event", "notification", "result",
)

/**
 * Reads the official ACP lifecycle identity from an event envelope. Routing,
 * admission and reduction share these rules so a valid update is never
 * reduced into a runtime that is not its owner.
 */
fun acpEventSessionId(event: Map<String, Any?>): String? = acpEnvelopeIdentity(
    event,
    // `threadId` is the pre-ACP compatibility name, accepted only here.
    listOf("sessionId", "session_id", "threadId", "thread_id"),
)

fun acpEventTurnId(event: Map<String, Any?>): String? = acpEnvelopeIdentity(
    event,
    // Legacy AgentStreamEvent called the ACP turn a task: read alias only.
    listOf("turnId", "turn_id", "taskId", "task_id", "runId", "run_id"),
)

fun acpEventMessageId(event: Map<String, Any?>): String? = acpEnvelopeIdentity(
    event,
    listOf("messageId", "message_id", "entryId", "entry_id", "itemId", "item_id"),
)

fun acpEventItemId(event: Map<String, Any?>): String? = acpEnvelopeIdentity(
    event,
    listOf("itemId", "item_id", "toolCallId", "tool_call_id", "callId", "call_id"),
)

/**
 * Whether the host explicitly reserved the first event for the active local
 * prompt. Delivery metadata, not an ACP field.
 */
fun acpEventAllowsImplicitTurnAdmission(event: Map<String, Any?>): Boolean =
    acpEnvelopeContainsTrue(event, "allowImplicitTurnAdmission")

/** Identifies the removed pre-ACP event shape at the compatibility boundary. */
fun acpEventIsLegacyCompatibilityShape(event: Map<String, Any?>): Boolean =
    event["legacyCompatibility"] == true ||
        event.containsKey("kind") ||
        event.containsKey("streamKind") ||
        event.containsKey("eventKind") ||
        event.containsKey("taskId") ||
        event.containsKey("task_id")

private fun acpEnvelopeIdentity(
    root: Map<String, Any?>,
    keys: List<String>,
    depth: Int = 0,
): String? {
    if (depth > 6) return null
    for (key in keys) {
        firstString(root[key])?.let { return it }
    }
    for (key in envelopeKeys) {
        val nested = copyStringMap(root[key]) ?: continue
        acpEnvelopeIdentity(nested, keys, depth + 1)?.let { return it }
    }
    return null
}

private fun acpEnvelopeContainsTrue(
    root: Map<String, Any?>,
    key: String,
    depth: Int = 0,
): Boolean {
    if (depth > 6) return false
    if (root[key] == true) return true
    for (envelopeKey in envelopeKeys) {
        val nested = copyStringMap(root[envelopeKey]) ?: continue
        if (acpEnvelopeContainsTrue(nested, key, depth + 1)) return true
    }
    return false
}

private fun nonEmpty(value: String?): Boolean = value?.trim()?.isNotEmpty() == true

internal fun normalizedOrNull(value: String?): String? = value?.trim()?.ifEmpty { null }

/**
 * Key for a protocol turn lookup. A turn id is normally unique only inside a
 * session, so the session is part of the key when available; the turn-only
 * form keeps legacy providers working.
 */
fun acpTurnKey(sessionId: String? = null, turnId: String? = null): String {
    val normalizedTurnId = normalizedOrNull(turnId) ?: return ""
    val normalizedSessionId = normalizedOrNull(sessionId)
    return if (normalizedSessionId == null) normalizedTurnId else "$normalizedSessionId:$normalizedTurnId"
}

private val unsafeSegment = Regex("[^a-zA-Z0-9._:-]")

private fun safeSegment(value: String): String = value.trim().replace(unsafeSegment, "_")
