package cn.com.omnimind.bot.agent.web

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.ProtocolException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class WebRequestException(
    val code: String,
    message: String,
    val status: Int? = null,
) : IOException(message)

internal data class WebHttpResponse(
    val requestedUrl: String,
    val finalUrl: String,
    val status: Int,
    val contentType: String?,
    val bytes: ByteArray,
    val truncated: Boolean,
)

/**
 * 独立匿名 HTTP 客户端。
 * 不复用浏览器 Cookie、缓存或认证器，关闭时取消当前运行持有的全部在途请求。
 */
internal class WebHttpTransport(
    baseClient: OkHttpClient = defaultPublicClient(),
    private val allowPrivateAddresses: Boolean = false,
) : Closeable {
    private val client = baseClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .authenticator(Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .let { builder ->
            if (allowPrivateAddresses) builder else builder.dns(PublicOnlyDns)
        }
        .build()

    private val closed = AtomicBoolean(false)
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()

    fun ensureActive() {
        if (closed.get() || Thread.currentThread().isInterrupted) {
            throw WebRequestException("WEB_CANCELLED", "网页请求已取消")
        }
    }

    fun cancelActiveCalls() {
        activeCalls.forEach(Call::cancel)
    }

    fun validateDestination(rawUrl: String): HttpUrl =
        validateUrl(rawUrl, allowPrivateAddresses)

    suspend fun get(
        rawUrl: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = MAX_RESPONSE_BYTES,
        bindCancel: ((() -> Unit) -> Unit)? = null,
    ): WebHttpResponse = request(
        rawUrl = rawUrl,
        method = "GET",
        headers = headers,
        maxBytes = maxBytes,
        bindCancel = bindCancel,
    )

    suspend fun request(
        rawUrl: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
        maxBytes: Int = MAX_RESPONSE_BYTES,
        bindCancel: ((() -> Unit) -> Unit)? = null,
    ): WebHttpResponse = withContext(Dispatchers.IO) {
        require(maxBytes in 1..MAX_RESPONSE_BYTES)
        val normalizedMethod = method.trim().uppercase(Locale.ROOT)
        if (normalizedMethod != "GET" && normalizedMethod != "POST") {
            throw WebRequestException("INVALID_ARGUMENT", "仅支持 GET 或 POST 请求方式")
        }
        val initial = validateDestination(rawUrl)
        var url = initial
        var currentMethod = normalizedMethod
        var currentBody = if (normalizedMethod == "POST") (body ?: byteArrayOf()) else null
        val visited = hashSetOf<String>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        val parentJob = currentCoroutineContext()[Job]

        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            ensureActive()
            currentCoroutineContext().ensureActive()
            if (!visited.add("$currentMethod:$url")) {
                throw WebRequestException("WEB_REDIRECT_LOOP", "网页重定向形成循环")
            }
            val remainingNs = deadline - System.nanoTime()
            if (remainingNs <= 0L) {
                throw WebRequestException("WEB_TIMEOUT", "网页请求超时")
            }

            val sameOrigin = url.scheme == initial.scheme &&
                url.host == initial.host &&
                url.port == initial.port
            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", DEFAULT_ACCEPT)
                .header("Accept-Language", DEFAULT_ACCEPT_LANGUAGE)

            headers.forEach { (rawName, rawValue) ->
                val name = rawName.trim()
                val value = rawValue.trim()
                if (name.isEmpty()) return@forEach
                validateHeader(name, value)
                if (!sameOrigin && isSensitiveHeader(name)) return@forEach
                requestBuilder.header(name, value)
            }

            if (currentMethod == "POST") {
                val mediaType = (contentType ?: "application/json; charset=utf-8").toMediaTypeOrNull()
                requestBuilder.post((currentBody ?: byteArrayOf()).toRequestBody(mediaType))
            } else {
                requestBuilder.get()
            }

            val call = client.newCall(requestBuilder.build())
            call.timeout().timeout(remainingNs, TimeUnit.NANOSECONDS)
            activeCalls += call
            val cancelReg = parentJob?.invokeOnCompletion { call.cancel() }
            bindCancel?.invoke { call.cancel() }
            if (closed.get() || parentJob?.isCancelled == true) call.cancel()

            try {
                call.execute().use { response ->
                    ensureActive()
                    currentCoroutineContext().ensureActive()
                    if (response.code in REDIRECT_STATUS) {
                        if (redirectCount == MAX_REDIRECTS) {
                            throw WebRequestException("WEB_REDIRECT_LIMIT", "网页重定向次数超过限制")
                        }
                        val location = response.header("Location")
                            ?: throw WebRequestException("WEB_REDIRECT_INVALID", "网页重定向缺少目标地址")
                        val resolved = url.resolve(location)
                            ?: throw WebRequestException("WEB_REDIRECT_INVALID", "网页重定向地址无效")
                        url = validateUrl(resolved.toString(), allowPrivateAddresses)
                        if (response.code in setOf(301, 302, 303) && currentMethod == "POST") {
                            currentMethod = "GET"
                            currentBody = null
                        }
                    } else {
                        val status = response.code
                        if (!response.isSuccessful) {
                            // 非 2xx 不读取正文，避免错误页内的回显或敏感信息进入模型上下文
                            return@withContext WebHttpResponse(
                                requestedUrl = initial.toString(),
                                finalUrl = url.toString(),
                                status = status,
                                contentType = response.header("Content-Type"),
                                bytes = byteArrayOf(),
                                truncated = false,
                            )
                        }
                        val responseBody = response.body
                        val output = ByteArrayOutputStream(minOf(maxBytes, 16_384))
                        var truncated = false
                        responseBody.byteStream().use { input ->
                            val buffer = ByteArray(8_192)
                            while (true) {
                                ensureActive()
                                currentCoroutineContext().ensureActive()
                                val capacity = maxBytes - output.size()
                                val count = input.read(buffer, 0, minOf(buffer.size, capacity + 1))
                                if (count < 0) break
                                if (count > capacity) {
                                    output.write(buffer, 0, capacity)
                                    truncated = true
                                    break
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                        ensureActive()
                        currentCoroutineContext().ensureActive()
                        return@withContext WebHttpResponse(
                            requestedUrl = initial.toString(),
                            finalUrl = url.toString(),
                            status = status,
                            contentType = response.header("Content-Type"),
                            bytes = output.toByteArray(),
                            truncated = truncated,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: WebRequestException) {
                throw failure
            } catch (_: InterruptedIOException) {
                currentCoroutineContext().ensureActive()
                ensureActive()
                throw WebRequestException("WEB_TIMEOUT", "网页连接或读取超时")
            } catch (_: ProtocolException) {
                currentCoroutineContext().ensureActive()
                ensureActive()
                throw WebRequestException("WEB_PROTOCOL_ERROR", "服务器返回了无法处理的 HTTP 响应")
            } catch (_: UnknownHostException) {
                currentCoroutineContext().ensureActive()
                ensureActive()
                throw WebRequestException("WEB_HOST_UNAVAILABLE", "无法解析目标主机或目标地址不允许访问")
            } catch (_: IOException) {
                currentCoroutineContext().ensureActive()
                ensureActive()
                throw WebRequestException("WEB_NETWORK_ERROR", "无法完成网页请求，请检查网络连接或目标地址")
            } finally {
                cancelReg?.dispose()
                activeCalls -= call
            }
        }
        throw WebRequestException("WEB_REDIRECT_LIMIT", "网页重定向次数超过限制")
    }

    override fun close() {
        closed.set(true)
        cancelActiveCalls()
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_URL_CHARS = 8_192
        private const val MAX_REDIRECTS = 5
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
        private const val DEFAULT_ACCEPT =
            "text/html,application/xhtml+xml,text/plain,text/markdown,application/json;q=0.9,*/*;q=0.1"
        private const val DEFAULT_ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8"
        private val REDIRECT_STATUS = setOf(301, 302, 303, 307, 308)

        private val PublicOnlyDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = Dns.SYSTEM.lookup(hostname)
                if (addresses.isEmpty() || addresses.any(::isBlockedAddress)) {
                    throw UnknownHostException("Target host must resolve to a public address")
                }
                return addresses
            }
        }

        private fun defaultPublicClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        fun validateUrl(raw: String, allowPrivateAddresses: Boolean = false): HttpUrl {
            if (raw.isBlank() || raw.length > MAX_URL_CHARS || raw.any { it.isISOControl() }) {
                throw WebRequestException("INVALID_URL", "网址为空、过长或包含无效字符")
            }
            val parsed = raw.trim().toHttpUrlOrNull()
                ?: throw WebRequestException("INVALID_URL", "只支持完整的 http:// 或 https:// 网址")
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
                throw WebRequestException("URL_CREDENTIALS_UNSUPPORTED", "此工具不接受包含用户名或密码的网址")
            }
            if (!allowPrivateAddresses) {
                val host = parsed.host.lowercase(Locale.ROOT)
                if (host == "localhost" || host.endsWith(".localhost") || host == "ip6-localhost") {
                    throw WebRequestException("DISALLOWED_HOST", "不支持访问本机或内网地址")
                }
            }
            return parsed.newBuilder().fragment(null).build().also { normalized ->
                if (normalized.toString().length > MAX_URL_CHARS) {
                    throw WebRequestException("INVALID_URL", "编码后的网址超过长度限制")
                }
            }
        }

        private fun isBlockedAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress ||
                address.isMulticastAddress
            ) {
                return true
            }
            val raw = address.address
            // IPv4 carrier-grade NAT (100.64.0.0/10)
            if (raw.size == 4) {
                val first = raw[0].toInt() and 0xff
                val second = raw[1].toInt() and 0xff
                if (first == 100 && second in 64..127) return true
                if (first == 0) return true
            }
            // IPv6 Unique Local Address (fc00::/7)
            if (raw.size == 16 && (raw[0].toInt() and 0xfe) == 0xfc) {
                return true
            }
            return false
        }

        private fun validateHeader(name: String, value: String) {
            if (name.length > 128 || value.length > 4096 ||
                name.any { it.isISOControl() || it == ':' || it.isWhitespace() } ||
                value.any { it == '\r' || it == '\n' || it == '\u0000' }
            ) {
                throw WebRequestException("INVALID_ARGUMENT", "请求头名称或值包含无效字符")
            }
        }

        private fun isSensitiveHeader(name: String): Boolean {
            val lower = name.lowercase(Locale.ROOT)
            return lower == "authorization" || lower == "proxy-authorization" ||
                lower == "cookie" || lower == "x-api-key" || lower == "api-key" ||
                "token" in lower || "secret" in lower || "key" in lower
        }
    }
}
