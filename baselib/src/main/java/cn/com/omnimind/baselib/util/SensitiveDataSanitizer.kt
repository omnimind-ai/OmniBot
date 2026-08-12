package cn.com.omnimind.baselib.util

import androidx.annotation.VisibleForTesting

/**
 * Best-effort redaction for diagnostic text. This is a final safety net; callers should still
 * avoid logging request/response bodies and credentials in the first place.
 */
object SensitiveDataSanitizer {
    private const val DEFAULT_MAX_CHARS = 8_192
    private const val REDACTED = "[REDACTED]"

    private val bearerPattern = Regex(
        pattern = "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+",
    )
    private val jwtPattern = Regex(
        pattern = "\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b",
    )
    private val prefixedKeyPattern = Regex(
        pattern = "(?i)\\b(?:sk|pk|rk)-[A-Za-z0-9_-]{8,}",
    )
    private val secretAssignmentPattern = Regex(
        pattern = "(?i)([\"']?(?:authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|session[_-]?(?:id|key|token)|device[_-]?id|install[_-]?id|token|password|passwd|secret|cookie|set-cookie)[\"']?\\s*[:=]\\s*[\"']?)([^\"'\\s,}\\]]+)",
    )
    private val secretQueryPattern = Regex(
        pattern = "(?i)([?&](?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|session[_-]?(?:id|key|token)|device[_-]?id|install[_-]?id|token|key|secret)=)([^&#\\s]+)",
    )
    private val cookieHeaderPattern = Regex(
        pattern = "(?i)(\\b(?:Cookie|Set-Cookie)\\s*:\\s*)([^\\r\\n]+)",
    )
    private val urlCredentialsPattern = Regex(
        pattern = "(?i)(https?://)([^\\s/@:]+):([^\\s/@]+)@",
    )
    private val emailPattern = Regex(
        pattern = "(?i)\\b([A-Z0-9._%+-])[A-Z0-9._%+-]*@([A-Z0-9.-]+\\.[A-Z]{2,})\\b",
    )

    @JvmStatic
    @JvmOverloads
    fun sanitize(raw: String?, maxChars: Int = DEFAULT_MAX_CHARS): String {
        if (raw.isNullOrEmpty()) return raw.orEmpty()
        var value = raw
        value = urlCredentialsPattern.replace(value) { match ->
            "${match.groupValues[1]}$REDACTED@"
        }
        value = secretQueryPattern.replace(value) { match ->
            "${match.groupValues[1]}$REDACTED"
        }
        value = cookieHeaderPattern.replace(value) { match ->
            "${match.groupValues[1]}$REDACTED"
        }
        value = bearerPattern.replace(value, "Bearer $REDACTED")
        value = secretAssignmentPattern.replace(value) { match ->
            "${match.groupValues[1]}$REDACTED"
        }
        value = jwtPattern.replace(value, REDACTED)
        value = prefixedKeyPattern.replace(value, REDACTED)
        value = emailPattern.replace(value) { match ->
            "${match.groupValues[1]}***@${match.groupValues[2]}"
        }
        val safeLimit = maxChars.coerceAtLeast(0)
        return if (value.length <= safeLimit) value else value.take(safeLimit) + "…[truncated]"
    }

    @VisibleForTesting
    internal fun redactedMarker(): String = REDACTED
}
