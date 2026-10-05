package cn.com.omnimind.bot.ui.chat

import cn.com.omnimind.bot.agent.projection.DartJson
import cn.com.omnimind.nativeui.chat.AgentRequestCardUi
import cn.com.omnimind.nativeui.chat.DeepThinkingCardUi

/*
 * Presentation of the request and thinking cards. Ports of:
 *
 * - `.../cards/agent_request_card.dart` (`AgentRequestNotice`,
 *   `_compactRequestPresentation`, `_cardStatus`)
 * - `.../cards/card_widget_factory.dart` (`deep_thinking` branch) and
 *   `.../cards/deep_thinking_card.dart` (`_buildText` line localization)
 */

// ---------------------------------------------------------------------------
// agent_request
// ---------------------------------------------------------------------------

internal fun presentAgentRequestCard(cardData: Map<String, Any?>): AgentRequestCardUi {
    val kind = dartTrim(dartStr(cardData["requestKind"]))
    val (title, detail) = compactRequestPresentation(cardData)
    val requestId = cardData["requestId"]
    return AgentRequestCardUi(
        kind = kind,
        title = title,
        detail = detail,
        status = requestCardStatus(cardData),
        interactionUnavailable = cardData["interactionUnavailable"] == true ||
            requestId == null || dartTrim(dartToStringValue(requestId)).isEmpty(),
        sessionEnded = cardData["interactionUnavailableReason"] == "session_ended",
    )
}

/** Dart `_cardStatus`. */
internal fun requestCardStatus(cardData: Map<String, Any?>): String {
    val normalized = dartTrim(dartStr(cardData["status"] ?: "pending")).lowercase()
    return normalized.ifEmpty { "pending" }
}

/** Dart `_compactRequestPresentation`: a schema's first field can name a generic request. */
internal fun compactRequestPresentation(card: Map<String, Any?>): Pair<String, String> {
    var title = dartTrim(dartStr(card["title"]))
    var detail = dartTrim(dartStr(card["detail"]))
    val raw = decodeJsonMapOrEmpty(dartStr(card["rawParamsJson"]))
    val schema = compactRequestSchema(raw)
    val properties = asStringMap(schema?.get("properties"))
    if (!properties.isNullOrEmpty()) {
        val first = properties.entries.first()
        val field = asStringMap(first.value) ?: emptyMap()
        val fieldTitle = firstText(listOf(field["title"], field["label"], first.key))
        val fieldDetail = firstText(listOf(field["description"], field["placeholder"]))
        if (isGenericCompactTitle(title) && fieldTitle != null) {
            title = fieldTitle
        }
        if ((isGenericCompactDetail(detail, title) || detail.isEmpty()) && fieldDetail != null) {
            detail = fieldDetail
        }
        val choices = compactSchemaChoices(field)
        if (choices.isNotEmpty() && !detail.contains("可选：")) {
            val joined = "可选：" + choices.joinToString("、")
            detail = if (detail.isEmpty()) joined else "$detail\n$joined"
        }
    }
    return title to detail
}

private fun compactRequestSchema(params: Map<String, Any?>?): Map<String, Any?>? {
    if (params == null) return null
    for (key in listOf("requestedSchema", "requested_schema", "schema", "inputSchema", "input_schema")) {
        val value = params[key]
        val map = asStringMap(value) ?: (value as? String)?.let { asStringMap(decodeJsonOrNull(it)) }
        if (map != null) return map
    }
    for (key in listOf("request", "elicitation", "params")) {
        val value = params[key]
        val nested = asStringMap(value) ?: (value as? String)?.let { asStringMap(decodeJsonOrNull(it)) }
        val schema = compactRequestSchema(nested)
        if (schema != null) return schema
    }
    return if (params["properties"] is Map<*, *>) params else null
}

private fun decodeJsonOrNull(value: String): Any? = try {
    DartJson.decode(value)
} catch (_: Exception) {
    null
}

private fun isGenericCompactTitle(value: String): Boolean {
    val normalized = dartTrim(value).lowercase()
    return normalized.isEmpty() ||
        (normalized.contains("agent") && (normalized.contains("input") || normalized.contains("question"))) ||
        (value.contains("需要") && value.contains("输入"))
}

private fun isGenericCompactDetail(detail: String, title: String): Boolean {
    val normalized = dartTrim(detail).lowercase()
    return normalized.isEmpty() ||
        normalized == dartTrim(title).lowercase() ||
        (normalized.contains("agent") && normalized.contains("input")) ||
        normalized.startsWith("{") ||
        normalized.startsWith("[") ||
        normalized.contains("requestedschema")
}

private fun compactSchemaChoices(field: Map<String, Any?>): List<String> {
    val values = field["oneOf"] ?: field["enum"]
    if (values !is List<*>) return emptyList()
    return values.mapNotNull { value ->
        val map = asStringMap(value)
        firstText(listOf(map?.get("title"), map?.get("label"), map?.get("const"), value))
    }
}

private fun asStringMap(value: Any?): Map<String, Any?>? = (value as? Map<*, *>)?.let(::stringKeyedMap)

/** Dart `_firstText`: the first value whose `toString().trim()` is non-empty. */
private fun firstText(values: Iterable<Any?>): String? {
    for (value in values) {
        if (value == null) continue
        val text = dartTrim(dartToStringValue(value))
        if (text.isNotEmpty()) return text
    }
    return null
}

// ---------------------------------------------------------------------------
// deep_thinking
// ---------------------------------------------------------------------------

/** Dart `CardWidgetFactory` `deep_thinking` branch. */
internal fun presentDeepThinkingCard(cardData: Map<String, Any?>, english: Boolean): DeepThinkingCardUi {
    val stage = asRoundedInt(cardData["stage"]) ?: 1
    val taskId = nullableText(cardData["taskID"])
    val cardId = nullableText(cardData["cardId"])
    val text = dartStr(cardData["thinkingContent"])
    return DeepThinkingCardUi(
        // Dart `_buildText`: each line goes through the legacy localizer.
        text = if (english) text.split('\n').joinToString("\n") { LegacyTextLocalizer.localize(it, true) } else text,
        stage = stage,
        isLoading = asBool(cardData["isLoading"], fallback = stage != 4 && stage != 5),
        startTimeMillis = asLongOrNull(cardData["startTime"]),
        endTimeMillis = asLongOrNull(cardData["endTime"]),
        // Dart `_shouldShowDeepThinkingAvatar`.
        showAvatar = taskId == null || cardId == null || cardId == "$taskId-thinking",
    )
}

private fun nullableText(value: Any?): String? {
    val normalized = if (value == null) "" else dartTrim(dartToStringValue(value))
    return normalized.ifEmpty { null }
}

/** Dart `CardWidgetFactory._asInt` for values that fit an Int. */
private fun asRoundedInt(value: Any?): Int? {
    val number = asLongOrNull(value) ?: return null
    return if (number in Int.MIN_VALUE..Int.MAX_VALUE) number.toInt() else null
}

/** Dart `CardWidgetFactory._asInt` (Dart ints are 64-bit: millisecond timestamps fit). */
private fun asLongOrNull(value: Any?): Long? {
    when (value) {
        is Int -> return value.toLong()
        is Long -> return value
        is Number -> {
            val number = value.toDouble()
            return if (number.isFinite()) Math.round(number) else null
        }
    }
    val text = if (value == null) "" else dartTrim(dartToStringValue(value))
    if (text.isEmpty()) return null
    text.toLongOrNull()?.let { return it }
    val parsed = text.toDoubleOrNull()
    return if (parsed != null && parsed.isFinite()) Math.round(parsed) else null
}

private fun asBool(value: Any?, fallback: Boolean): Boolean {
    if (value is Boolean) return value
    return when (if (value == null) "" else dartTrim(dartToStringValue(value)).lowercase()) {
        "true" -> true
        "false" -> false
        else -> fallback
    }
}
