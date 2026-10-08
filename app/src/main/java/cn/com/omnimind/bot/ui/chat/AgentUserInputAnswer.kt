package cn.com.omnimind.bot.ui.chat

import cn.com.omnimind.bot.agent.projection.ChatMessage
import cn.com.omnimind.bot.agent.projection.DartJson
import cn.com.omnimind.bot.agent.projection.isAgentRequestCardType

/*
 * Answering an Agent's question from the composer (batch 5e-6), ported from
 * `chat_page_user_message_actions.dart` (`_pendingAgentUserInputCard`,
 * `_respondToPendingAgentUserInput`, `_singleComposerElicitationContent`).
 * While a `user_input` request is pending, the composer's text is the
 * answer instead of a new prompt.
 */

/** The newest pending `user_input` request card, or null (Dart reads from the oldest end of a newest-first list; one is pending at a time). */
internal fun pendingUserInputCard(messages: List<ChatMessage>): Map<String, Any?>? =
    messages.asReversed().firstNotNullOfOrNull { message ->
        message.cardData?.takeIf { card ->
            isAgentRequestCardType(card["type"]?.toString()) &&
                card["requestKind"]?.toString() == "user_input" &&
                card["status"]?.toString() == "pending" &&
                card["requestId"] != null
        }
    }

/**
 * The `respondToServerRequest` arguments for [answer]: an ACP elicitation
 * gets `{action: accept, content}` with the single schema field typed; the
 * legacy request-user-input shape gets `answers[questionId]`.
 */
internal fun userInputResponseArgs(card: Map<String, Any?>, answer: String): Map<String, Any?> {
    val args = linkedMapOf<String, Any?>("requestId" to card["requestId"])
    card["agentId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { args["agentId"] = it }
    val conversationId = (card["conversationId"] as? Number)?.toInt() ?: card["conversationId"]?.toString()?.toIntOrNull()
    conversationId?.let { args["conversationId"] = it }
    card["sessionId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { args["sessionId"] = it }
    args["response"] = if (card["structuredElicitation"] == true) {
        linkedMapOf("action" to "accept", "content" to elicitationContent(card, answer))
    } else {
        val questionId = card["questionId"]?.toString()?.ifBlank { null } ?: "answer"
        linkedMapOf("answers" to linkedMapOf(questionId to linkedMapOf("answers" to listOf(answer))))
    }
    return args
}

/** Dart `_singleComposerElicitationContent`: one required (or only) field, typed by its schema. */
internal fun elicitationContent(card: Map<String, Any?>, text: String): Map<String, Any?> {
    val raw = card["rawParamsJson"]?.toString()?.trim()?.let(::decodeMap).orEmpty()
    val schema = findSchema(raw) ?: listOf("request", "elicitation", "params").firstNotNullOfOrNull { key ->
        decodeValue(raw[key])?.let { it as? Map<*, *> }?.let(::findSchema)
    }
    val properties = (schema?.get("properties") as? Map<*, *>).orEmpty()
    val required = (schema?.get("required") as? List<*>).orEmpty().map { it.toString() }
    val field = when {
        required.size == 1 -> required.first()
        properties.size == 1 -> properties.keys.first().toString()
        else -> null
    }?.takeIf { it.isNotEmpty() } ?: return mapOf("answer" to text)
    val type = ((properties[field] as? Map<*, *>)?.get("type") ?: "string").toString().lowercase()
    val value: Any = when (type) {
        "integer" -> text.trim().toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: text
        "number" -> text.trim().toDoubleOrNull() ?: text
        "boolean" -> text.trim().lowercase() == "true"
        "array" -> text.split(',').map(String::trim).filter(String::isNotEmpty)
        else -> text
    }
    return mapOf(field to value)
}

private val SCHEMA_KEYS = listOf("requestedSchema", "requested_schema", "schema", "inputSchema", "input_schema")

private fun findSchema(map: Map<*, *>): Map<*, *>? =
    SCHEMA_KEYS.firstNotNullOfOrNull { key -> decodeValue(map[key]) as? Map<*, *> }

/** Dart decodes JSON-string fields in place. */
private fun decodeValue(value: Any?): Any? = when (value) {
    is String -> runCatching { DartJson.decode(value) }.getOrNull()
    else -> value
}

private fun decodeMap(text: String): Map<*, *>? =
    if (text.isEmpty()) null else runCatching { DartJson.decode(text) }.getOrNull() as? Map<*, *>
