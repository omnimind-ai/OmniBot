package cn.com.omnimind.bot.agent.projection

/**
 * Loosely typed JSON value helpers for the ACP projection.
 *
 * The projection keeps ACP payloads and projected cards as plain
 * `Map<String, Any?>` / `List<Any?>` trees, exactly like the Dart owner it
 * replaces, so the port stays mechanically comparable and the values cross
 * the Flutter `StandardMessageCodec` unchanged. Each helper here mirrors one
 * Dart idiom; keep their semantics aligned rather than "improving" them.
 */
internal typealias JsonMap = MutableMap<String, Any?>

/**
 * Dart `value?.toString()`. Kotlin prints numbers, booleans and strings the
 * same way for the values ACP carries (Dart int arrives as Int/Long).
 */
internal fun dartToString(value: Any?): String? = value?.toString()

/** Dart `value?.toString().trim()` returning null for blank values. */
internal fun stringOrNull(value: Any?): String? {
    val text = dartToString(value)?.trim().orEmpty()
    return text.ifEmpty { null }
}

/** Dart `_firstString`: first non-blank trimmed string. */
internal fun firstString(vararg values: Any?): String? {
    for (value in values) {
        val text = stringOrNull(value)
        if (text != null) return text
    }
    return null
}

internal fun firstString(values: Iterable<Any?>): String? {
    for (value in values) {
        val text = stringOrNull(value)
        if (text != null) return text
    }
    return null
}

/**
 * Dart `_asStringMap` in the reducer: always a fresh map with string keys.
 */
internal fun copyStringMap(value: Any?): JsonMap? {
    if (value !is Map<*, *>) return null
    val result = LinkedHashMap<String, Any?>(value.size)
    for ((key, nested) in value) {
        result[key.toString()] = nested
    }
    return result
}

/**
 * Dart `_asStringMap` variants that return the same instance when it already
 * is a string-keyed map. Writes through the result alias the source, as in
 * Dart.
 */
@Suppress("UNCHECKED_CAST")
internal fun sameOrStringMap(value: Any?): JsonMap? {
    if (value !is Map<*, *>) return null
    if (value is MutableMap<*, *> && value.keys.all { it is String }) {
        return value as JsonMap
    }
    return copyStringMap(value)
}

/** Dart `_asMapList`: maps of a list, each copied with string keys. */
internal fun asMapList(value: Any?): List<JsonMap> {
    if (value !is List<*>) return emptyList()
    return value.filterIsInstance<Map<*, *>>().map { copyStringMap(it)!! }
}

/** Dart `_asInt` (int, num truncation, string parse). */
internal fun asInt(value: Any?): Int? = when (value) {
    is Int -> value
    is Long -> value.toInt()
    is Number -> value.toInt()
    is String -> value.trim().toIntOrNull()
    else -> null
}

/** Millisecond / large integer values. */
internal fun asLong(value: Any?): Long? = when (value) {
    is Long -> value
    is Int -> value.toLong()
    is Number -> value.toLong()
    is String -> value.trim().toLongOrNull()
    else -> null
}

internal fun asDouble(value: Any?): Double? = when (value) {
    is Number -> value.toDouble()
    is String -> value.trim().toDoubleOrNull()
    else -> null
}

/** Deep copy of a JSON tree into fresh mutable containers. */
internal fun deepCopyJson(value: Any?): Any? = when (value) {
    is Map<*, *> -> {
        val result = LinkedHashMap<String, Any?>(value.size)
        for ((key, nested) in value) result[key.toString()] = deepCopyJson(nested)
        result
    }
    is List<*> -> value.mapTo(ArrayList(value.size)) { deepCopyJson(it) }
    else -> value
}

@Suppress("UNCHECKED_CAST")
internal fun deepCopyMap(value: Map<String, Any?>): JsonMap = deepCopyJson(value) as JsonMap

internal fun jsonMapOf(vararg pairs: Pair<String, Any?>): JsonMap = linkedMapOf(*pairs)
