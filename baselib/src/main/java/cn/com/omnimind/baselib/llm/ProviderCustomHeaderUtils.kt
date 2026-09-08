package cn.com.omnimind.baselib.llm

import java.util.Locale

object ProviderCustomHeaderUtils {
    private val forbiddenHeaderNames = setOf(
        "host",
        "content-length",
        "connection",
        "transfer-encoding"
    )
    private val exactSensitiveHeaderNames = setOf(
        "authorization",
        "x-api-key",
        "api-key",
        "cookie",
        "set-cookie",
        "proxy-authorization"
    )

    fun normalizeHeaderName(name: String): String {
        return name.trim().lowercase(Locale.ROOT)
    }

    fun isForbiddenHeaderName(name: String): Boolean {
        return forbiddenHeaderNames.contains(normalizeHeaderName(name))
    }

    fun sanitizeCustomHeaders(headers: Map<String, String>?): Map<String, String> {
        if (headers.isNullOrEmpty()) {
            return emptyMap()
        }
        val normalized = LinkedHashMap<String, Pair<String, String>>()
        headers.forEach { (rawKey, rawValue) ->
            val key = rawKey.trim()
            if (key.isEmpty()) {
                return@forEach
            }
            val normalizedKey = normalizeHeaderName(key)
            // Header values are also exported to newline-delimited official
            // Harness fields (for example ANTHROPIC_CUSTOM_HEADERS). Never
            // let an editable Provider value inject another header line.
            if (forbiddenHeaderNames.contains(normalizedKey) ||
                key.any { it == '\r' || it == '\n' } ||
                rawValue.any { it == '\r' || it == '\n' }
            ) {
                return@forEach
            }
            normalized.remove(normalizedKey)
            normalized[normalizedKey] = key to rawValue
        }
        return LinkedHashMap<String, String>().apply {
            normalized.values.forEach { (key, value) ->
                put(key, value)
            }
        }
    }

    fun mergeHeaders(
        builtIn: Map<String, String>,
        custom: Map<String, String>?
    ): Map<String, String> {
        val merged = LinkedHashMap<String, Pair<String, String>>()
        builtIn.forEach { (rawKey, rawValue) ->
            val key = rawKey.trim()
            if (key.isEmpty()) {
                return@forEach
            }
            val normalizedKey = normalizeHeaderName(key)
            merged.remove(normalizedKey)
            merged[normalizedKey] = key to rawValue
        }
        sanitizeCustomHeaders(custom).forEach { (key, value) ->
            val normalizedKey = normalizeHeaderName(key)
            merged.remove(normalizedKey)
            merged[normalizedKey] = key to value
        }
        return LinkedHashMap<String, String>().apply {
            merged.values.forEach { (key, value) ->
                put(key, value)
            }
        }
    }

    fun redactHeadersForLog(headers: Map<String, String>?): Map<String, String> {
        if (headers.isNullOrEmpty()) {
            return emptyMap()
        }
        return LinkedHashMap<String, String>().apply {
            headers.forEach { (key, value) ->
                put(key, if (shouldRedactHeader(key)) "***" else value)
            }
        }
    }

    fun shouldRedactHeader(name: String): Boolean {
        val normalized = normalizeHeaderName(name)
        return exactSensitiveHeaderNames.contains(normalized) ||
            normalized.contains("token") ||
            normalized.contains("secret") ||
            normalized.contains("key")
    }

    fun coerceStringMap(raw: Any?): Map<String, String> {
        val entries = raw as? Map<*, *> ?: return emptyMap()
        val normalized = LinkedHashMap<String, String>()
        entries.forEach { (key, value) ->
            val stringKey = key?.toString() ?: return@forEach
            normalized[stringKey] = value?.toString() ?: ""
        }
        return sanitizeCustomHeaders(normalized)
    }
}
