package cn.com.omnimind.nativeui.chat

import androidx.compose.runtime.Immutable

/**
 * One projected chat message as the Compose message surfaces see it.
 *
 * Mirrors the native runtime's `ChatMessage` (app module) field for field:
 * type 1 = text, 2 = card; user 1 = user, 2 = assistant, 3 = system card.
 * [content], [streamMeta] and [turnUsage] are read-only JSON trees exactly as
 * the runtime snapshot publishes them. The app module maps runtime messages
 * into this type; native-ui never sees the runtime itself.
 */
@Immutable
data class ChatMessageUi(
    val id: String,
    val type: Int,
    val user: Int,
    val content: Map<String, Any?>? = null,
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    val isSummarizing: Boolean = false,
    val streamMeta: Map<String, Any?>? = null,
    val turnUsage: Map<String, Any?>? = null,
    val reasoningContent: String? = null,
    val createAtMillis: Long = 0L,
    /** Presentation of an `agent_tool_summary` card, derived by the app module. */
    val toolCard: AgentToolCardUi? = null,
    /** Presentation of an `agent_request` card, derived by the app module. */
    val requestCard: AgentRequestCardUi? = null,
    /** Presentation of a `deep_thinking` card, derived by the app module. */
    val thinkingCard: DeepThinkingCardUi? = null,
) {
    val text: String? get() = content?.get("text")?.toString()

    @Suppress("UNCHECKED_CAST")
    val cardData: Map<String, Any?>?
        get() = (content?.get("cardData") as? Map<*, *>)?.let { map ->
            if (map.keys.all { it is String }) map as Map<String, Any?> else map.mapKeys { it.key.toString() }
        }

    val contentId: String? get() = content?.get("id")?.toString()

    val agentId: String?
        get() = normalized(content?.get("agentId") ?: cardData?.get("agentId") ?: streamMeta?.get("agentId"))

    val agentName: String?
        get() = normalized(content?.get("agentName") ?: cardData?.get("agentName") ?: streamMeta?.get("agentName"))

    /** Canonical projected run identity; legacy card/stream names as fallbacks. */
    val runId: String?
        get() = normalized(
            streamMeta?.get("runId") ?: streamMeta?.get("parentTaskId")
                ?: cardData?.get("runId") ?: cardData?.get("taskID") ?: cardData?.get("taskId"),
        )

    val sessionId: String? get() = normalized(streamMeta?.get("sessionId") ?: cardData?.get("sessionId"))
    val turnId: String? get() = normalized(streamMeta?.get("turnId") ?: cardData?.get("turnId"))
    val toolCallId: String? get() = normalized(streamMeta?.get("toolCallId") ?: cardData?.get("toolCallId"))

    private fun normalized(value: Any?): String? = value?.toString()?.trim()?.ifEmpty { null }
}
