package cn.com.omnimind.bot.agent.projection

import java.util.Base64

/*
 * Value helpers private to the Dart `agent_event_reducer.dart` library.
 *
 * They are prefixed because their semantics differ from the package-wide
 * helpers in ProjectionValues.kt: the reducer's `_firstString` extracts text
 * from nested maps through `_extractText`, and `_safeJson` pretty-prints.
 *
 * Dart name            -> Kotlin
 *   _string            -> acpString        (toString().trim(), null when blank)
 *   _firstString       -> acpFirstString   (via acpExtractText, trimmed)
 *   _extractText       -> acpExtractText
 *   _extractStreamingText -> acpExtractStreamingText
 *   _commandTextFromValue -> acpCommandTextFromValue
 *   _decodeBase64Output   -> acpDecodeBase64Output
 *   _decodeByteListOutput -> acpDecodeByteListOutput
 *   _safeJson          -> acpSafeJson      (2-space indented Dart JSON)
 *   _trimTerminalOutput -> acpTrimTerminalOutput
 *   _accountSummary    -> acpAccountSummary
 *   _collaborationModeFromThreadSettings -> acpCollaborationModeFromThreadSettings
 *   _acpRequestId      -> acpRequestId
 *   _asStringMap       -> copyStringMap    (ProjectionValues, always copies)
 *   _asMapList         -> asMapList        (ProjectionValues)
 *   _asInt / _asDouble -> asInt / asDouble (ProjectionValues)
 */

internal fun acpString(value: Any?): String? {
    val text = dartToString(value)?.trim().orEmpty()
    return text.ifEmpty { null }
}

internal fun acpFirstString(values: Iterable<Any?>): String? {
    for (value in values) {
        val text = acpExtractText(value)?.trim()
        if (!text.isNullOrEmpty()) return text
    }
    return null
}

internal fun acpFirstString(vararg values: Any?): String? = acpFirstString(values.asList())

internal fun acpExtractText(value: Any?): String? {
    if (value == null) return null
    if (value is String) return value
    if (value is Number || value is Boolean) return value.toString()
    val map = copyStringMap(value)
    if (map != null) {
        return acpFirstString(map["text"], map["content"], map["message"], map["value"], map["delta"], map["summary"])
    }
    if (value is List<*>) return value.mapNotNull { acpExtractText(it) }.joinToString("")
    return value.toString()
}

/**
 * Extracts streamed ACP text without normalizing whitespace: markdown may be
 * split into chunks made only of spaces or newlines.
 */
internal fun acpExtractStreamingText(value: Any?): String? {
    if (value == null) return null
    if (value is String) return value
    if (value is Number || value is Boolean) return value.toString()
    val map = copyStringMap(value)
    if (map != null) {
        for (candidate in listOf(map["text"], map["content"], map["message"], map["value"], map["delta"], map["resource"])) {
            val text = acpExtractStreamingText(candidate)
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }
    if (value is List<*>) return value.mapNotNull { acpExtractStreamingText(it) }.joinToString("")
    return value.toString()
}

internal fun acpCommandTextFromValue(value: Any?): String? {
    if (value == null) return null
    if (value is String) return value.trim().ifEmpty { null }
    if (value is List<*>) {
        val parts = value.mapNotNull { acpExtractText(it) }.map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }
    return acpExtractText(value)
}

internal fun acpDecodeBase64Output(value: Any?): String? {
    val encoded = acpString(value) ?: return null
    val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
        ?: runCatching { Base64.getUrlDecoder().decode(encoded) }.getOrNull()
        ?: return null
    return String(bytes, Charsets.UTF_8)
}

internal fun acpDecodeByteListOutput(value: Any?): String? {
    if (value !is List<*>) return null
    val bytes = ByteArray(value.size)
    for ((index, item) in value.withIndex()) {
        val byte = asInt(item)
        if (byte == null || byte < 0 || byte > 255) return null
        bytes[index] = byte.toByte()
    }
    return String(bytes, Charsets.UTF_8)
}

/** Dart `_acpRequestId`: a trimmed string or a numeric JSON-RPC id. */
internal fun acpRequestId(
    params: Map<String, Any?>,
    message: Map<String, Any?>,
    item: Map<String, Any?>? = null,
): Any? {
    for (candidate in listOf(
        message["id"], params["requestId"], params["request_id"],
        item?.get("requestId"), item?.get("request_id"),
    )) {
        if (candidate is String) {
            if (candidate.trim().isNotEmpty()) return candidate.trim()
        } else if (candidate is Number) {
            return candidate
        }
    }
    return null
}

internal fun acpCollaborationModeFromThreadSettings(params: Map<String, Any?>): String? {
    val settings = copyStringMap(params["threadSettings"])
        ?: copyStringMap(params["thread_settings"])
        ?: copyStringMap(params["settings"])
        ?: copyStringMap(params["thread"])
        ?: params
    val modeValue = settings["collaborationMode"] ?: settings["collaboration_mode"]
        ?: params["collaborationMode"] ?: params["collaboration_mode"]
    val modeMap = copyStringMap(modeValue)
    val mode = acpFirstString(modeMap?.get("mode"), modeMap?.get("kind"), modeValue)
    if (mode != null) return mode
    val nestedSettings = copyStringMap(modeMap?.get("settings")) ?: copyStringMap(settings["settings"])
    return acpFirstString(nestedSettings?.get("mode"), nestedSettings?.get("kind"))
}

/** Dart `_safeJson`: indented JSON, falling back to `toString()`. */
internal fun acpSafeJson(value: Any?): String =
    runCatching { DartJson.encodeIndented(value) }.getOrElse { dartToString(value) ?: "" }

internal fun acpAccountSummary(params: Map<String, Any?>): String {
    val account = copyStringMap(params["account"]) ?: params
    val email = acpString(account["email"])
    val plan = acpString(account["planType"]) ?: acpString(account["plan_type"])
    val type = acpString(account["type"])
    val parts = listOfNotNull(email, plan, type?.takeIf { it != "chatgpt" })
    return if (parts.isEmpty()) acpSafeJson(params) else parts.joinToString(" / ")
}

internal fun acpTrimTerminalOutput(value: String): String = value
