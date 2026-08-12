package cn.com.omnimind.baselib.util

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Shared rule for endpoints that carry API keys, bearer tokens or custom auth headers. */
object CredentialEndpointSecurity {
    @Volatile
    private var debugLoopbackAllowed = false

    fun configureDebugLoopback(allowed: Boolean) {
        debugLoopbackAllowed = allowed
    }

    fun isDebugLoopbackAllowed(): Boolean = debugLoopbackAllowed

    fun requireSafe(
        rawUrl: String,
        hasCredential: Boolean,
        allowInsecureLoopback: Boolean = false,
    ): String {
        val normalized = rawUrl.trim()
        require(normalized.isNotEmpty()) { "Credential endpoint URL is required." }
        val uri = runCatching { URI(normalized) }.getOrNull()
            ?: throw IllegalArgumentException("Credential endpoint URL is invalid.")
        require(uri.isAbsolute) { "Credential endpoint URL must be absolute." }
        require(uri.userInfo == null) { "Credential endpoint URL must not embed user info." }
        require(uri.rawFragment == null) { "Credential endpoint URL must not contain a fragment." }
        require(!uri.host.isNullOrBlank()) { "Credential endpoint URL is missing a host." }
        val scheme = uri.scheme?.lowercase().orEmpty()
        require(scheme in SUPPORTED_ENDPOINT_SCHEMES) {
            "Credential endpoint URL uses an unsupported scheme."
        }
        require(!containsSensitiveQueryName(uri.rawQuery)) {
            "Credential endpoint URL must not embed secrets in query parameters."
        }
        if (!hasCredential) return normalized
        if (scheme == "https" || scheme == "wss") return normalized
        if (
            allowInsecureLoopback &&
            (scheme == "http" || scheme == "ws") &&
            isLiteralLoopback(uri.host)
        ) {
            return normalized
        }
        throw IllegalArgumentException(
            "Credentials require HTTPS/WSS; insecure transport is allowed only for explicit debug loopback.",
        )
    }

    fun isLiteralLoopback(host: String?): Boolean {
        val normalized = host?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]")
            ?: return false
        if (normalized == "::1" || normalized == "0:0:0:0:0:0:0:1") return true
        val octets = normalized.split('.')
        if (octets.size != 4) return false
        val parsed = octets.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return false
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
        }
        return parsed.first() == 127
    }

    private fun containsSensitiveQueryName(rawQuery: String?): Boolean {
        if (rawQuery.isNullOrBlank()) return false
        return rawQuery.split('&', ';').any { pair ->
            val rawName = pair.substringBefore('=')
            val decoded = runCatching {
                URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
            }.getOrDefault(rawName)
            val normalizedName = decoded.trim().lowercase().replace('-', '_')
            normalizedName in SENSITIVE_QUERY_NAMES
        }
    }

    private val SENSITIVE_QUERY_NAMES = setOf(
        "key",
        "api_key",
        "apikey",
        "access_key",
        "accesskey",
        "token",
        "access_token",
        "accesstoken",
        "auth",
        "authorization",
        "signature",
        "sig",
    )

    private val SUPPORTED_ENDPOINT_SCHEMES = setOf("http", "https", "ws", "wss")
}
