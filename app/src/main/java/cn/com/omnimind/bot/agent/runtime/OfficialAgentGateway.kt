package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.account.AiRequestAccess
import cn.com.omnimind.baselib.account.OmniAccount
import cn.com.omnimind.bot.media.PlatformGatewayException
import cn.com.omnimind.bot.media.PlatformMediaGatewayExecutor
import cn.com.omnimind.bot.media.awaitResponse
import com.google.gson.JsonParser
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * HTTP credential boundary for external Harness processes. A process receives a
 * loopback-only capability, never a snapshot of the short-lived account token.
 * ACP Session/Turn/Item ownership and retries remain completely unchanged.
 */
internal class OfficialAgentGateway(
    private val accessProvider: () -> AiRequestAccess = OmniAccount::currentAiRequestAccess,
    private val refreshSession: suspend () -> Unit = { OmniAccount.repository().refreshSession() },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .readTimeout(5, TimeUnit.MINUTES).build(),
    private val executeRequest: suspend (Request) -> Response = { client.newCall(it).awaitResponse() },
) {
    private val mutex = Mutex()
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var port = 0
    @Volatile private var binding: Binding? = null

    private data class Binding(val identity: List<String>, val gateway: String, val key: String)

    suspend fun credentials(provider: AgentProviderCredentials): AgentProviderCredentials = mutex.withLock {
        val access = accessProvider()
        val identity = accountIdentity(access.bearerToken) ?: throw invalidSession()
        val gateway = access.platformGatewayUrl?.trim()?.trimEnd('/')
            ?: throw invalidSession()
        check(access.usesPlatform && gateway == provider.baseUrl.trimEnd('/')) {
            "官方模型配置已更改，请重新选择模型"
        }
        val current = binding?.takeIf { it.identity == identity && it.gateway == gateway }
            ?: Binding(identity, gateway, Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteArray(32).also(SecureRandom()::nextBytes),
            )).also { binding = it }
        if (server == null) {
            val started = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                routing { route("/{path...}") { handle { forward(call) } } }
            }.start(wait = false)
            try {
                port = started.engine.resolvedConnectors().single().port
                server = started
            } catch (error: Throwable) {
                started.stop(0, 0)
                throw error
            }
        }
        provider.copy(baseUrl = "http://127.0.0.1:$port", apiKey = current.key, customHeaders = emptyMap())
    }

    suspend fun close() = mutex.withLock {
        binding = null
        server?.stop(0, 1000)
        server = null
        port = 0
    }

    private suspend fun forward(call: ApplicationCall) {
        val bound = binding
        val supplied = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            ?: call.request.headers["x-api-key"]
        if (bound == null || supplied == null || !MessageDigest.isEqual(
                supplied.toByteArray(), bound.key.toByteArray(),
            )) {
            fail(call, 401, "invalid_access_token", "登录状态已失效，请重新登录")
            return
        }
        val path = call.request.path()
        val method = call.request.httpMethod.value
        if (!((method == "GET" && path == "/v1/models") ||
                (method == "POST" && path in POST_PATHS))) {
            fail(call, 404, "unsupported_endpoint", "官方模型接口不可用")
            return
        }
        // Authenticate before accepting a potentially large body. Check again
        // inside each attempt, including after refresh, to prevent account swaps.
        val response = try {
            checkedAccess(bound)
            val channel = call.receiveChannel()
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = channel.readAvailable(buffer)
                if (count < 0) break
                if (bytes.size() + count > MAX_REQUEST_BYTES) {
                    fail(call, 413, "request_too_large", "模型请求过大，请减少附件后重试")
                    return
                }
                bytes.write(buffer, 0, count)
            }
            val body = bytes.toByteArray()
            val executor = PlatformMediaGatewayExecutor(
                executeRequest = executeRequest,
                accessProvider = { checkedAccess(bound) },
                refreshSession = {
                    checkedAccess(bound)
                    refreshSession()
                },
            )
            executor.execute { credentials ->
                Request.Builder().url(credentials.gatewayBaseUrl + path)
                    .header("Authorization", "Bearer ${credentials.bearerToken}")
                    .apply {
                        for (header in REQUEST_HEADERS) {
                            call.request.headers[header]?.let { header(header, it) }
                        }
                        method(method, if (method == "GET") null else body.toRequestBody(JSON_TYPE))
                    }.build()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: PlatformGatewayException) {
            fail(call, error.statusCode ?: 503, error.errorCode ?: "gateway_unavailable",
                error.message ?: "官方 AI 暂时不可用")
            return
        } catch (_: Exception) {
            fail(call, 502, "gateway_unavailable", "官方 AI 连接失败，请稍后重试")
            return
        }
        // Once headers/body are exposed, errors propagate without replaying the
        // HTTP request, let alone the ACP prompt or previously executed tools.
        response.use { upstream ->
            for (header in RESPONSE_HEADERS) {
                upstream.header(header)?.let { call.response.headers.append(header, it) }
            }
            call.respondOutputStream(
                contentType = upstream.header("Content-Type")?.let {
                    runCatching { ContentType.parse(it) }.getOrNull()
                } ?: ContentType.Application.Json,
                status = HttpStatusCode.fromValue(upstream.code),
            ) {
                coroutineScope {
                    // Closing the body also unblocks a socket read when the
                    // Harness cancels/disconnects while the upstream is silent.
                    val closeOnCancel = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { upstream.close() }
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            upstream.body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    write(buffer, 0, count)
                                    flush()
                                }
                            }
                        }
                    } finally {
                        closeOnCancel.cancel()
                    }
                }
            }
        }
    }

    private fun checkedAccess(bound: Binding): AiRequestAccess = accessProvider().also {
        if (!it.usesPlatform || accountIdentity(it.bearerToken) != bound.identity ||
            it.platformGatewayUrl?.trim()?.trimEnd('/') != bound.gateway || binding !== bound) {
            throw invalidSession()
        }
    }

    private suspend fun fail(call: ApplicationCall, status: Int, code: String, message: String) {
        val envelope = com.google.gson.JsonObject().apply {
            add("error", com.google.gson.JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
            })
        }
        call.respondText(envelope.toString(), ContentType.Application.Json, HttpStatusCode.fromValue(status))
    }

    companion object {
        private const val MAX_REQUEST_BYTES = 16 * 1024 * 1024
        private val JSON_TYPE = "application/json".toMediaType()
        private val POST_PATHS = setOf("/v1/chat/completions", "/v1/responses", "/v1/messages", "/v1/messages/count_tokens")
        private val REQUEST_HEADERS = listOf("Accept", "Anthropic-Version", "Anthropic-Beta", "OpenAI-Beta")
        private val RESPONSE_HEADERS = listOf("x-request-id", "request-id", "retry-after", "cache-control")

        private fun invalidSession() = PlatformGatewayException(401, "invalid_access_token", "登录状态已失效，请重新登录")

        /** Identity comparison only; the upstream gateway still validates JWT signatures. */
        internal fun accountIdentity(token: String?): List<String>? = runCatching {
            val parts = token?.split('.') ?: return null
            if (parts.size != 3) return null
            val claims = JsonParser.parseString(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)).asJsonObject
            listOf("iss", "sub", "sid").map { key ->
                claims[key]?.asString?.takeIf(String::isNotBlank) ?: return null
            }
        }.getOrNull()
    }
}
