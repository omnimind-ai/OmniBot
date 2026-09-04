package cn.com.omnimind.baselib.util

import java.net.URI

/**
 * Transport policy for endpoints that receive user content.
 *
 * A request remains sensitive when it has no API key: prompts, attachments, workspace paths,
 * MCP arguments, and model responses must be checked at the final request boundary. HTTPS/WSS is
 * the default; a user-configured local Provider may explicitly opt in to HTTP/WS because Android
 * LAN gateways commonly do not expose TLS. Callers that do not explicitly opt in remain secure.
 */
object ContentEndpointSecurity {
    fun requireSafe(
        rawUrl: String,
        allowInsecureLoopback: Boolean = false,
        allowInsecureTransport: Boolean = false,
    ): String {
        val normalized = rawUrl.trim()
        require(normalized.isNotEmpty()) { "User content endpoint URL is required." }
        val uri = runCatching { URI(normalized) }.getOrNull()
            ?: throw IllegalArgumentException("User content endpoint URL is invalid.")
        require(uri.isAbsolute) { "User content endpoint URL must be absolute." }
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (
            !allowInsecureTransport &&
            allowInsecureLoopback &&
            (scheme == "http" || scheme == "ws") &&
            !isLiteralIpLoopback(uri.host)
        ) {
            throw IllegalArgumentException(
                "User content requires HTTPS/WSS; debug plaintext is limited to literal IP loopback.",
            )
        }
        return CredentialEndpointSecurity.requireSafe(
            rawUrl = normalized,
            hasCredential = true,
            allowInsecureLoopback = allowInsecureLoopback,
            allowInsecureTransport = allowInsecureTransport,
        )
    }

    private fun isLiteralIpLoopback(host: String?): Boolean {
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
}
