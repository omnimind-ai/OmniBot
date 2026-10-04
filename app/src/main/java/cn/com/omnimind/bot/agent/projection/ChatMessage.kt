package cn.com.omnimind.bot.agent.projection

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One projected chat message, the Kotlin port of Dart `ChatMessageModel`.
 *
 * type: 1 = text, 2 = card. user: 1 = user, 2 = assistant, 3 = system card.
 * Instances are immutable; [content], [streamMeta] and [turnUsage] are never
 * mutated after construction (callers copy before editing).
 */
data class ChatMessage(
    val id: String,
    val type: Int,
    val user: Int,
    val content: Map<String, Any?>? = null,
    val isLoading: Boolean = false,
    val isFirst: Boolean = false,
    val isError: Boolean = false,
    val isSummarizing: Boolean = false,
    val streamMeta: Map<String, Any?>? = null,
    val turnUsage: Map<String, Any?>? = null,
    val reasoningContent: String? = null,
    val createAtMillis: Long = System.currentTimeMillis(),
) {
    val text: String? get() = content?.get("text")?.let { dartToString(it) }

    @Suppress("UNCHECKED_CAST")
    val cardData: Map<String, Any?>?
        get() {
            val value = content?.get("cardData") ?: return null
            if (value !is Map<*, *>) return null
            return if (value.keys.all { it is String }) value as Map<String, Any?> else copyStringMap(value)
        }

    val contentId: String? get() = content?.get("id")?.let { dartToString(it) }

    /** The ACP Agent that produced this message; null for older messages. */
    val agentId: String?
        get() = stringOrNull(content?.get("agentId") ?: cardData?.get("agentId") ?: streamMeta?.get("agentId"))

    val agentName: String?
        get() = stringOrNull(content?.get("agentName") ?: cardData?.get("agentName") ?: streamMeta?.get("agentName"))

    /** Canonical projected ACP run; legacy names read as fallbacks. */
    val runId: String?
        get() = stringOrNull(
            streamMeta?.get("runId") ?: streamMeta?.get("parentTaskId")
                ?: cardData?.get("runId") ?: cardData?.get("taskID") ?: cardData?.get("taskId"),
        )

    val sessionId: String? get() = stringOrNull(streamMeta?.get("sessionId") ?: cardData?.get("sessionId"))
    val turnId: String? get() = stringOrNull(streamMeta?.get("turnId") ?: cardData?.get("turnId"))
    val itemId: String? get() = stringOrNull(streamMeta?.get("itemId") ?: cardData?.get("itemId"))
    val toolCallId: String? get() = stringOrNull(streamMeta?.get("toolCallId") ?: cardData?.get("toolCallId"))
    val cardId: String?
        get() = stringOrNull(streamMeta?.get("cardId") ?: cardData?.get("cardId") ?: contentId)

    val dbId: Int? get() = asNullableInt(content?.get("dbId"))

    /**
     * Dart `copyWith`: a null argument KEEPS the current value. Use this for
     * every port of Dart `copyWith`; Kotlin `copy(x = null)` would clear it.
     */
    fun copyWith(
        id: String? = null,
        type: Int? = null,
        user: Int? = null,
        content: Map<String, Any?>? = null,
        isLoading: Boolean? = null,
        isFirst: Boolean? = null,
        isError: Boolean? = null,
        isSummarizing: Boolean? = null,
        streamMeta: Map<String, Any?>? = null,
        turnUsage: Map<String, Any?>? = null,
        reasoningContent: String? = null,
        createAtMillis: Long? = null,
    ): ChatMessage = ChatMessage(
        id = id ?: this.id,
        type = type ?: this.type,
        user = user ?: this.user,
        content = content ?: this.content,
        isLoading = isLoading ?: this.isLoading,
        isFirst = isFirst ?: this.isFirst,
        isError = isError ?: this.isError,
        isSummarizing = isSummarizing ?: this.isSummarizing,
        streamMeta = streamMeta ?: this.streamMeta,
        turnUsage = turnUsage ?: this.turnUsage,
        reasoningContent = reasoningContent ?: this.reasoningContent,
        createAtMillis = createAtMillis ?: this.createAtMillis,
    )

    /**
     * Dart `toJson`: `createAt` is an ISO-8601 local timestamp, as persisted
     * by the existing history service.
     */
    fun toJson(): JsonMap = linkedMapOf<String, Any?>(
        "id" to id,
        "type" to type,
        "user" to user,
        "content" to content,
        "isLoading" to isLoading,
        "isFirst" to isFirst,
        "isError" to isError,
        "isSummarizing" to isSummarizing,
    ).apply {
        if (streamMeta != null) put("streamMeta", streamMeta)
        if (turnUsage != null) put("turnUsage", turnUsage)
        if (reasoningContent != null) put("reasoning_content", reasoningContent)
        put("createAt", formatCreateAt(createAtMillis))
    }

    companion object {
        fun userMessage(text: String, id: String? = null): ChatMessage {
            val messageId = id ?: System.currentTimeMillis().toString()
            return ChatMessage(messageId, 1, 1, linkedMapOf("text" to text, "id" to messageId))
        }

        fun assistantMessage(
            text: String,
            id: String? = null,
            isLoading: Boolean = false,
            reasoningContent: String? = null,
        ): ChatMessage {
            val messageId = id ?: System.currentTimeMillis().toString()
            return ChatMessage(
                id = messageId,
                type = 1,
                user = 2,
                content = linkedMapOf("text" to text, "id" to messageId),
                isLoading = isLoading,
                reasoningContent = stringOrNull(reasoningContent),
            )
        }

        fun cardMessage(
            cardData: Map<String, Any?>,
            id: String? = null,
            streamMeta: Map<String, Any?>? = null,
        ): ChatMessage {
            val messageId = id ?: System.currentTimeMillis().toString()
            return ChatMessage(
                id = messageId,
                type = 2,
                user = 3,
                content = linkedMapOf("cardData" to cardData, "id" to messageId),
                streamMeta = streamMeta,
            )
        }

        @Suppress("UNCHECKED_CAST")
        fun fromJson(json: Map<String, Any?>): ChatMessage {
            val normalizedContent = normalizeDynamic(json["content"])
            val normalizedType = asNullableInt(json["type"]) ?: 1
            val normalizedUser = asNullableInt(json["user"]) ?: 1
            val contentMap = (normalizedContent as? Map<String, Any?>)?.let {
                normalizeAssistantTextContent(it, normalizedType, normalizedUser)
            }
            return ChatMessage(
                id = dartToString(json["id"]) ?: "",
                type = normalizedType,
                user = normalizedUser,
                content = contentMap,
                isLoading = json["isLoading"] as? Boolean ?: false,
                isFirst = json["isFirst"] as? Boolean ?: false,
                isError = json["isError"] as? Boolean ?: false,
                isSummarizing = json["isSummarizing"] as? Boolean ?: false,
                streamMeta = normalizeDynamic(json["streamMeta"]) as? Map<String, Any?>,
                turnUsage = normalizeDynamic(json["turnUsage"]) as? Map<String, Any?>,
                reasoningContent = stringOrNull(json["reasoning_content"] ?: json["reasoningContent"]),
                createAtMillis = parseCreateAt(json["createAt"]),
            )
        }

        internal fun asNullableInt(raw: Any?): Int? = when (raw) {
            is Int -> raw
            is Long -> raw.toInt()
            is Number -> {
                val d = raw.toDouble()
                if (d.isFinite() && d == Math.floor(d)) d.toInt() else null
            }
            is String -> {
                val trimmed = raw.trim()
                trimmed.toIntOrNull() ?: trimmed.toDoubleOrNull()?.let {
                    if (it.isFinite() && it == Math.floor(it)) it.toInt() else null
                }
            }
            else -> null
        }

        private val localIsoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")

        internal fun formatCreateAt(millis: Long): String =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
                .format(localIsoFormatter)

        internal fun parseCreateAt(raw: Any?): Long {
            when (raw) {
                is Number -> return raw.toLong()
                is String -> {
                    val trimmed = raw.trim()
                    if (trimmed.isEmpty()) return System.currentTimeMillis()
                    parseIsoMillis(trimmed)?.let { return it }
                    trimmed.toLongOrNull()?.let { return it }
                }
            }
            return System.currentTimeMillis()
        }

        private fun parseIsoMillis(text: String): Long? {
            runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            runCatching { return Instant.parse(text).toEpochMilli() }
            return runCatching {
                LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
        }

        private fun normalizeAssistantTextContent(
            content: Map<String, Any?>,
            type: Int,
            user: Int,
        ): Map<String, Any?> {
            if (type != 1 || user != 2) return content
            val rawText = dartToString(content["text"]) ?: ""
            val trimmed = rawText.trimStart()
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) return content
            val sanitized = sanitizePersistedAssistantText(rawText)
            if (sanitized == rawText) return content
            return LinkedHashMap(content).apply { put("text", sanitized) }
        }

        private fun sanitizePersistedAssistantText(raw: String): String {
            val firstContentIndex = raw.indexOfFirst { !it.isWhitespace() }
            if (firstContentIndex < 0 || raw[firstContentIndex] != '{') return raw
            val extracted = StringBuilder()
            var cursor = firstContentIndex
            var strippedTransportFrames = false
            while (cursor < raw.length) {
                val next = skipWhitespace(raw, cursor)
                if (next >= raw.length || raw[next] != '{') {
                    cursor = next
                    break
                }
                val jsonEnd = findBalancedJsonObjectEnd(raw, next)
                if (jsonEnd == null) {
                    cursor = next
                    break
                }
                val text = tryExtractTransportAssistantText(raw.substring(next, jsonEnd + 1))
                if (text == null) {
                    cursor = next
                    break
                }
                strippedTransportFrames = true
                extracted.append(text)
                cursor = jsonEnd + 1
            }
            if (!strippedTransportFrames) return raw
            return (raw.substring(0, firstContentIndex) + extracted + raw.substring(cursor)).trim()
        }

        private fun skipWhitespace(raw: String, start: Int): Int {
            var index = start
            while (index < raw.length && raw[index].isWhitespace()) index += 1
            return index
        }

        private fun findBalancedJsonObjectEnd(raw: String, start: Int): Int? {
            var depth = 0
            var inString = false
            var escaped = false
            for (index in start until raw.length) {
                val char = raw[index]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (inString && char == '\\') {
                    escaped = true
                    continue
                }
                if (char == '"') {
                    inString = !inString
                    continue
                }
                if (inString) continue
                if (char == '{') {
                    depth += 1
                } else if (char == '}') {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
            return null
        }

        private fun tryExtractTransportAssistantText(raw: String): String? {
            val normalized = raw.trim()
            if (normalized.isEmpty() || !normalized.startsWith("{")) return null
            val decoded = runCatching { DartJson.decode(normalized) }.getOrNull()
            val map = decoded as? Map<*, *> ?: return null
            tryExtractChoicesTransportText(map["choices"])?.let { return it }
            tryExtractOutputTransportText(map["output"])?.let { return it }
            return null
        }

        private fun tryExtractChoicesTransportText(rawChoices: Any?): String? {
            if (rawChoices !is List<*>) return null
            if (rawChoices.isEmpty()) return ""
            val first = rawChoices.first() as? Map<*, *> ?: return null
            val delta = first["delta"]
            if (delta is Map<*, *>) return extractTextPayload(delta["content"])
            val message = first["message"]
            if (message is Map<*, *>) return extractTextPayload(message["content"])
            val choiceText = extractTextPayload(first["text"] ?: first["content"])
            if (choiceText.isNotEmpty()) return choiceText
            if (first.containsKey("finish_reason") || first.containsKey("delta") || first.containsKey("message")) {
                return ""
            }
            return null
        }

        private fun tryExtractOutputTransportText(rawOutput: Any?): String? {
            if (rawOutput !is List<*>) return null
            val hasTransportShape = rawOutput.any { item ->
                if (item !is Map<*, *>) return@any false
                val type = dartToString(item["type"])?.trim()?.lowercase()
                item.containsKey("content") || item.containsKey("text") ||
                    type == "message" || type == "output_text" ||
                    type == "reasoning" || type == "reasoning_text"
            }
            if (!hasTransportShape) return null
            return rawOutput.joinToString("") { extractTextPayload(it) }
        }

        private fun extractTextPayload(raw: Any?): String = when (raw) {
            null -> ""
            is String -> raw
            is List<*> -> raw.joinToString("") { extractTextPayload(it) }
            is Map<*, *> -> {
                val type = dartToString(raw["type"])?.trim()?.lowercase()
                when {
                    type == "text" || type == "output_text" -> extractTextPayload(raw["text"])
                    raw.containsKey("text") -> extractTextPayload(raw["text"])
                    raw.containsKey("content") -> extractTextPayload(raw["content"])
                    else -> ""
                }
            }
            else -> ""
        }

        /** Dart `_normalizeDynamic`: string keys, integral doubles to Int. */
        internal fun normalizeDynamic(value: Any?): Any? = when (value) {
            is Map<*, *> -> {
                val result = LinkedHashMap<String, Any?>(value.size)
                for ((key, nested) in value) result[key.toString()] = normalizeDynamic(nested)
                result
            }
            is List<*> -> value.map { normalizeDynamic(it) }
            is Double -> if (value.isFinite() && value == Math.floor(value) &&
                value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()
            ) {
                val asLong = value.toLong()
                if (asLong in Int.MIN_VALUE..Int.MAX_VALUE) asLong.toInt() else asLong
            } else {
                value
            }
            else -> value
        }
    }
}
