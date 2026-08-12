package cn.com.omnimind.bot.media

import cn.com.omnimind.baselib.account.AiAccessMode
import cn.com.omnimind.baselib.account.AiRequestAccess
import cn.com.omnimind.baselib.account.OmniAccount
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response

internal data class PlatformGatewayCredentials(
    val gatewayBaseUrl: String,
    val bearerToken: String,
)

internal data class PlatformGatewayError(
    val code: String?,
    val message: String?,
)

internal class PlatformGatewayException(
    val statusCode: Int?,
    val errorCode: String?,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Executes an official gateway request and refreshes the account token once on 401. */
internal class PlatformMediaGatewayExecutor(
    private val executeRequest: suspend (Request) -> Response,
    private val accessProvider: () -> AiRequestAccess = OmniAccount::currentAiRequestAccess,
    private val refreshSession: suspend () -> Unit = {
        OmniAccount.repository().refreshSession()
    },
) {
    suspend fun execute(requestFactory: (PlatformGatewayCredentials) -> Request): Response {
        val first = executeRequest(requestFactory(requireCredentials(accessProvider())))
        if (first.code != 401) {
            return first
        }
        first.close()
        try {
            refreshSession()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw PlatformGatewayException(
                statusCode = 401,
                errorCode = "invalid_access_token",
                message = "登录状态已失效，请重新登录",
                cause = error,
            )
        }
        return executeRequest(requestFactory(requireCredentials(accessProvider())))
    }

    private fun requireCredentials(access: AiRequestAccess): PlatformGatewayCredentials {
        if (access.mode != AiAccessMode.PLATFORM || !access.usesPlatform) {
            throw PlatformGatewayException(
                statusCode = null,
                errorCode = "platform_unavailable",
                message = access.unavailableReason ?: "平台 AI 当前不可用",
            )
        }
        val gateway = access.platformGatewayUrl?.trim()?.trimEnd('/').orEmpty()
        val parsed = gateway.toHttpUrlOrNull()
        if (parsed == null || !parsed.isHttps) {
            throw PlatformGatewayException(
                statusCode = null,
                errorCode = "invalid_gateway_url",
                message = "官方 AI 网关地址无效",
            )
        }
        val token = access.bearerToken?.trim().orEmpty()
        if (token.isEmpty()) {
            throw PlatformGatewayException(
                statusCode = 401,
                errorCode = "invalid_access_token",
                message = "登录状态已失效，请重新登录",
            )
        }
        return PlatformGatewayCredentials(gateway, token)
    }
}

internal object PlatformMediaProtocol {
    /**
     * New API accepts JSON bodies below 16 MiB. Keep a full 1 MiB below that
     * boundary so headers, proxy framing, and small server-side wrappers cannot
     * turn an accepted client payload into a 413 at the public gateway.
     */
    internal const val MAX_PLATFORM_JSON_UTF8_BYTES: Long = 15L * 1024L * 1024L

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun endpoint(credentials: PlatformGatewayCredentials, path: String): String {
        require(path.startsWith('/')) { "gateway path must start with /" }
        return credentials.gatewayBaseUrl + path
    }

    fun parseErrorEnvelope(bytes: ByteArray): PlatformGatewayError? {
        if (bytes.isEmpty()) return null
        val text = bytes.toString(Charsets.UTF_8).trim()
        if (!text.startsWith('{')) return null
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return null
        val error = root["error"]
        if (error == null) {
            val success = (root["success"] as? JsonPrimitive)?.booleanOrNull
            if (success != false) return null
            return PlatformGatewayError(
                code = root["code"].primitiveContent(),
                message = root["message"].primitiveContent(),
            )
        }
        return when (error) {
            is JsonObject -> PlatformGatewayError(
                code = error["code"].primitiveContent(),
                message = error["message"].primitiveContent(),
            )
            is JsonPrimitive -> PlatformGatewayError(
                code = null,
                message = error.contentOrNull,
            )
            else -> PlatformGatewayError(code = null, message = null)
        }
    }

    fun requireSuccessfulResponse(statusCode: Int, bytes: ByteArray) {
        val error = parseErrorEnvelope(bytes)
        if (statusCode in 200..299 && error == null) {
            return
        }
        val code = error?.code?.trim()?.takeIf(String::isNotEmpty)
        throw PlatformGatewayException(
            statusCode = statusCode,
            errorCode = code,
            message = userMessage(statusCode, code),
        )
    }

    fun requirePlatformJsonRequestWithinLimit(jsonBody: String) {
        val utf8Bytes = jsonBody.toByteArray(Charsets.UTF_8).size.toLong()
        if (utf8Bytes > MAX_PLATFORM_JSON_UTF8_BYTES) {
            throw PlatformGatewayException(
                statusCode = null,
                errorCode = "request_too_large",
                message = "官方 AI 请求内容过大，请减少历史消息或图片后重试（发送上限 15 MiB）",
            )
        }
    }

    fun readBodyLimited(response: Response, maxBytes: Long): ByteArray {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val body = response.body ?: return ByteArray(0)
        val declared = body.contentLength()
        if (declared > maxBytes) {
            throw responseTooLarge(maxBytes)
        }
        body.byteStream().use { input ->
            val output = ByteArrayOutputStream(
                declared.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: 8192
            )
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) {
                    throw responseTooLarge(maxBytes)
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun userMessage(statusCode: Int, code: String?): String {
        stableUserMessageForErrorCode(code)?.let { return it }
        return when (code?.lowercase()) {
            "invalid_access_token" -> "登录状态已失效，请重新登录"
            "access_denied" -> "当前官方模型或接口不可用"
            else -> when (statusCode) {
                401 -> "登录状态已失效，请重新登录"
                403 -> "当前官方模型或接口不可用"
                413 -> "媒体文件过大，请缩小后重试"
                429 -> "请求过于频繁，请稍后重试"
                in 400..499 -> "官方 AI 请求参数无效"
                else -> "官方 AI 服务暂时不可用，请稍后重试"
            }
        }
    }

    fun stableUserMessageForErrorCode(code: String?): String? =
        when (code?.trim()?.lowercase()) {
            "insufficient_energy", "insufficient_quota", "insufficient_platform_quota",
            "quota_exceeded", "quota_exhausted" ->
                "平台额度不足，请充值或切换 BYOK"
            "quota_service_unavailable", "platform_quota_service_unavailable" ->
                "平台额度服务暂时不可用，请稍后重试"
            "platform_pricing_unavailable", "platform_model_pricing_unavailable" ->
                "平台计费配置暂时不可用，请稍后重试"
            "platform_model_service_unavailable" ->
                "官方模型服务暂时不可用，请稍后重试"
            "unsupported_tts_voice" ->
                "所选官方声音不可用，请刷新模型或选择其他声音"
            "platform_tts_voice_unavailable" ->
                "官方语音声音配置暂时不可用，请稍后重试"
            else -> null
        }

    private fun responseTooLarge(maxBytes: Long): PlatformGatewayException =
        PlatformGatewayException(
            statusCode = null,
            errorCode = "response_too_large",
            message = "官方 AI 返回的媒体文件过大（上限 ${maxBytes / (1024 * 1024)} MB）",
        )

    private fun kotlinx.serialization.json.JsonElement?.primitiveContent(): String? =
        runCatching { this?.jsonPrimitive?.contentOrNull }.getOrNull()
}

internal suspend fun Call.awaitResponse(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (!continuation.isCompleted) {
                    continuation.resumeWithException(error)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isCompleted) {
                    response.close()
                } else {
                    continuation.resume(response)
                }
            }
        })
    }
