package cn.com.omnimind.bot.plugin.official.agentweb

import com.google.gson.JsonParser
import java.net.URI
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class OpenCodeWebEndpoint(val url: String, val password: String) {
    override fun toString(): String = "OpenCodeWebEndpoint(url=$url, password=<redacted>)"
}

/** Official server.pair only; conversation/session requests remain owned by ACP/the vendor UI. */
internal class OpenCodeWebPairingClient {
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun createLink(endpoint: OpenCodeWebEndpoint): String = withContext(Dispatchers.IO) {
        require(isOpenCodeLoopbackServerUrl(endpoint.url)) { "Invalid OpenCode loopback endpoint" }
        require(endpoint.password.isNotEmpty() && endpoint.password.none(Char::isISOControl)) {
            "Invalid OpenCode server credentials"
        }
        val base = endpoint.url.trimEnd('/')
        val request = Request.Builder()
            .url("$base/api/pair")
            .header("Authorization", Credentials.basic("opencode", endpoint.password))
            .header("Accept", "application/json")
            .post(ByteArray(0).toRequestBody())
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "OpenCode pairing failed (HTTP ${response.code})" }
            val source = requireNotNull(response.body).source()
            check(!source.request(16_385)) { "OpenCode pairing response is too large" }
            val body = JsonParser.parseString(source.readUtf8()).asJsonObject
            val code = body.get("code")?.asString.orEmpty()
            check(code.matches(Regex("[A-Za-z0-9_-]{16,128}")) &&
                (body.get("expires_in")?.asLong ?: 0) > 0) {
                "OpenCode returned an invalid pairing code"
            }
            // Derive the address from the verified local process, never a URL
            // returned by a response. Each open gets a fresh single-use code.
            "$base/auth/connect/$code"
        }
    }
}

internal fun isOpenCodeLoopbackServerUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return uri.scheme == "http" && uri.rawUserInfo == null && uri.port in 1..65535 &&
        uri.host?.trim('[', ']')?.lowercase() in setOf("127.0.0.1", "localhost", "::1") &&
        uri.rawPath in setOf("", "/") && uri.rawQuery == null && uri.rawFragment == null
}
