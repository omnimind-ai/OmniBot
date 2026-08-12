package cn.com.omnimind.baselib.account

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object OfficialEndpointSecurity {
    fun normalizeBaseUrl(
        raw: String,
        label: String,
        allowInsecureLoopback: Boolean = false,
    ): String {
        val normalized = raw.trim().trimEnd('/')
        require(normalized.isNotEmpty()) { "$label is empty" }
        val url = normalized.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("$label is not a valid HTTP URL")
        require(url.username.isEmpty() && url.password.isEmpty()) {
            "$label must not contain embedded credentials"
        }
        require(url.querySize == 0 && url.fragment == null) {
            "$label must not contain query parameters or a fragment"
        }
        require(isAllowed(url, allowInsecureLoopback)) {
            "$label must use HTTPS"
        }
        return url.toString().trimEnd('/')
    }

    fun isAllowed(raw: String?, allowInsecureLoopback: Boolean = false): Boolean {
        val url = raw?.trim()?.toHttpUrlOrNull() ?: return false
        return url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.querySize == 0 &&
            url.fragment == null &&
            isAllowed(url, allowInsecureLoopback)
    }

    private fun isAllowed(url: HttpUrl, allowInsecureLoopback: Boolean): Boolean {
        if (url.scheme == "https") return true
        return allowInsecureLoopback && url.scheme == "http" && isLoopback(url.host)
    }

    private fun isLoopback(host: String): Boolean {
        val normalized = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        return normalized == "localhost" ||
            normalized == "127.0.0.1" ||
            normalized == "::1"
    }
}
