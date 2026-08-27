package cn.com.omnimind.bot.media

import cn.com.omnimind.baselib.account.AiAccessMode
import cn.com.omnimind.baselib.account.AiRequestAccess
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformMediaGatewayTest {
    @Test
    fun refreshesJwtOnceAfterUnauthorizedAndRebuildsRequest() = runBlocking {
        var token = "expired-token"
        var refreshCount = 0
        val requests = mutableListOf<Request>()
        val codes = ArrayDeque(listOf(401, 200))
        val executor = PlatformMediaGatewayExecutor(
            executeRequest = { request ->
                requests += request
                response(request, codes.removeFirst(), "{}")
            },
            accessProvider = {
                AiRequestAccess(
                    mode = AiAccessMode.PLATFORM,
                    platformGatewayUrl = "https://gateway.example.com",
                    bearerToken = token,
                )
            },
            refreshSession = {
                refreshCount += 1
                token = "fresh-token"
            },
        )

        executor.execute { credentials ->
            Request.Builder()
                .url(PlatformMediaProtocol.endpoint(credentials, "/v1/images/generations"))
                .header("Authorization", "Bearer ${credentials.bearerToken}")
                .build()
        }.close()

        assertEquals(1, refreshCount)
        assertEquals(2, requests.size)
        assertEquals("Bearer expired-token", requests[0].header("Authorization"))
        assertEquals("Bearer fresh-token", requests[1].header("Authorization"))
    }

    @Test
    fun neverRetriesASecondUnauthorizedResponse() = runBlocking {
        var requests = 0
        val executor = PlatformMediaGatewayExecutor(
            executeRequest = { request ->
                requests += 1
                response(request, 401, "{}")
            },
            accessProvider = {
                AiRequestAccess(
                    mode = AiAccessMode.PLATFORM,
                    platformGatewayUrl = "https://gateway.example.com",
                    bearerToken = "token-$requests",
                )
            },
            refreshSession = {},
        )

        val response = executor.execute { credentials ->
            Request.Builder()
                .url(PlatformMediaProtocol.endpoint(credentials, "/v1/images/generations"))
                .build()
        }

        assertEquals(401, response.code)
        assertEquals(2, requests)
        response.close()
    }

    @Test
    fun mapsRefreshFailureToSafeAuthenticationError() = runBlocking {
        val request = Request.Builder().url("https://gateway.example.com/v1/images/generations").build()
        val executor = PlatformMediaGatewayExecutor(
            executeRequest = { response(request, 401, "{}") },
            accessProvider = {
                AiRequestAccess(
                    mode = AiAccessMode.PLATFORM,
                    platformGatewayUrl = "https://gateway.example.com",
                    bearerToken = "expired-token",
                )
            },
            refreshSession = { error("internal refresh detail") },
        )

        val error = runCatching { executor.execute { request } }.exceptionOrNull()

        assertTrue(error is PlatformGatewayException)
        error as PlatformGatewayException
        assertEquals(401, error.statusCode)
        assertEquals("invalid_access_token", error.errorCode)
        assertFalse(error.message.orEmpty().contains("internal refresh detail"))
    }

    @Test
    fun recognizesInsufficientQuotaInsideHttp200Envelope() {
        val bytes = """
            {"error":{"code":"insufficient_energy","message":"quota exhausted"}}
        """.trimIndent().toByteArray()

        val error = runCatching {
            PlatformMediaProtocol.requireSuccessfulResponse(200, bytes)
        }.exceptionOrNull()

        assertTrue(error is PlatformGatewayException)
        error as PlatformGatewayException
        assertEquals("insufficient_energy", error.errorCode)
        assertTrue(error.message.orEmpty().contains("额度不足"))
    }

    @Test
    fun mapsFormalPlatformErrorCodesToStableChineseMessages() {
        val expectedFragments = linkedMapOf(
            "insufficient_platform_quota" to "额度不足",
            "platform_quota_service_unavailable" to "额度服务暂时不可用",
            "platform_pricing_unavailable" to "计费配置暂时不可用",
            "platform_model_pricing_unavailable" to "计费配置暂时不可用",
            "platform_model_service_unavailable" to "官方模型服务暂时不可用",
        )

        expectedFragments.forEach { (code, expectedFragment) ->
            val bytes = """{"error":{"code":"$code","message":"private detail"}}"""
                .toByteArray()
            val error = runCatching {
                PlatformMediaProtocol.requireSuccessfulResponse(400, bytes)
            }.exceptionOrNull()

            assertTrue("code=$code", error is PlatformGatewayException)
            error as PlatformGatewayException
            assertEquals(code, error.errorCode)
            assertTrue("code=$code", error.message.orEmpty().contains(expectedFragment))
            assertFalse("code=$code", error.message.orEmpty().contains("private detail"))
        }
    }

    @Test
    fun rejectsFinalUtf8JsonAboveSafePlatformLimit() {
        val body = "你".repeat(
            (PlatformMediaProtocol.MAX_PLATFORM_JSON_UTF8_BYTES / 3L).toInt() + 1
        )

        val error = runCatching {
            PlatformMediaProtocol.requirePlatformJsonRequestWithinLimit(body)
        }.exceptionOrNull()

        assertTrue(error is PlatformGatewayException)
        assertEquals("request_too_large", (error as PlatformGatewayException).errorCode)
        assertTrue(error.message.orEmpty().contains("15 MiB"))
    }

    @Test
    fun recognizesLegacyFalseSuccessQuotaEnvelopeWithoutLeakingUpstreamMessage() {
        val quotaBytes = """
            {"success":false,"code":"quota_exceeded","message":"internal upstream detail"}
        """.trimIndent().toByteArray()
        val quotaError = runCatching {
            PlatformMediaProtocol.requireSuccessfulResponse(200, quotaBytes)
        }.exceptionOrNull()

        assertTrue(quotaError is PlatformGatewayException)
        assertTrue(quotaError?.message.orEmpty().contains("额度不足"))

        val badRequestBytes = """
            {"error":{"code":"bad_request","message":"https://internal.invalid/path"}}
        """.trimIndent().toByteArray()
        val badRequest = runCatching {
            PlatformMediaProtocol.requireSuccessfulResponse(400, badRequestBytes)
        }.exceptionOrNull()

        assertTrue(badRequest is PlatformGatewayException)
        assertFalse(badRequest?.message.orEmpty().contains("internal.invalid"))
    }

    @Test
    fun rejectsBodiesLargerThanDeclaredLimit() {
        val request = Request.Builder().url("https://gateway.example.com/v1/images/generations").build()
        val response = response(request, 200, "12345")

        val error = runCatching {
            PlatformMediaProtocol.readBodyLimited(response, 4)
        }.exceptionOrNull()

        assertTrue(error is PlatformGatewayException)
        assertEquals("response_too_large", (error as PlatformGatewayException).errorCode)
        response.close()
    }

    private fun response(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("stub")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
}
