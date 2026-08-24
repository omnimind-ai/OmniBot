package cn.com.omnimind.baselib.account

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class AccountApiClientTest {
    @Test
    fun platformModelCatalogUsesJwtAndReturnsOnlySafeMetadata() = runBlocking {
        val calls = RecordingCallFactory(
            StubResponse(
                200,
                """
                {
                  "success":true,
                  "data":[{
                    "id":"Qwen3.5-Plus",
                    "owned_by":"custom",
                    "supported_endpoint_types":["openai"],
                    "base_url":"https://internal.invalid",
                    "key":"must-not-be-mapped"
                  },{
                    "id":"opus-6",
                    "owned_by":"custom",
                    "supported_endpoint_types":["openai"]
                  },{
                    "id":"image-model",
                    "supported_endpoint_types":["image-generation"]
                  },{
                    "id":"embedding-model",
                    "supported_endpoint_types":["embeddings"]
                  }],
                  "official_catalog":{
                    "version":"2",
                    "display_names":{
                      "opus-6":"opus 6☺️",
                      "hidden-model":"Must not leak"
                    },
                    "defaults":{
                      "text":"Qwen3.5-Plus",
                      "image":"image-model",
                      "embedding":"embedding-model",
                      "vision":"Qwen3.5-Plus"
                    },
                    "capabilities":{
                      "text":["Qwen3.5-Plus","opus-6"],
                      "image":["image-model"],
                      "embedding":["embedding-model"],
                      "vision":["Qwen3.5-Plus"]
                    }
                  }
                }
                """.trimIndent(),
            )
        )
        val client = PlatformModelApiClient(
            gatewayBaseUrl = "https://model.example.com/",
            callFactory = calls,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val catalog = client.getCatalog("account-jwt")
        val models = catalog.models

        assertEquals(
            listOf("Qwen3.5-Plus", "opus-6", "image-model", "embedding-model"),
            models.map(PlatformModel::id),
        )
        assertEquals(listOf("openai"), models.first().supportedEndpointTypes)
        assertTrue(catalog.hasOfficialCatalog)
        assertEquals("2", catalog.version)
        assertEquals(mapOf("opus-6" to "opus 6☺️"), catalog.displayNames)
        assertEquals("image-model", catalog.defaults.image)
        assertEquals("embedding-model", catalog.defaults.embedding)
        assertEquals(listOf("Qwen3.5-Plus"), catalog.capabilities.vision)
        assertEquals(listOf("embedding-model"), catalog.capabilities.embedding)
        val request = calls.requests.single()
        assertEquals("https://model.example.com/v1/models", request.url.toString())
        assertEquals("Bearer account-jwt", request.header("Authorization"))
    }

    @Test
    fun platformModelCatalogReportsUnauthorizedForRepositoryRefresh() = runBlocking {
        val client = PlatformModelApiClient(
            gatewayBaseUrl = "https://model.example.com",
            callFactory = RecordingCallFactory(StubResponse(401, "{}")),
            ioDispatcher = Dispatchers.Unconfined,
        )

        val error = runCatching { client.listModels("expired") }.exceptionOrNull()

        assertTrue(error is AccountApiException)
        error as AccountApiException
        assertEquals(401, error.statusCode)
        assertEquals("invalid_access_token", error.errorCode)
    }

    @Test
    fun platformModelCatalogRejectsOversizedResponse() = runBlocking {
        val oversized = "x".repeat(
            PlatformModelApiClient.MAX_CATALOG_BODY_BYTES.toInt() + 1
        )
        val client = PlatformModelApiClient(
            gatewayBaseUrl = "https://model.example.com",
            callFactory = RecordingCallFactory(StubResponse(200, oversized)),
            ioDispatcher = Dispatchers.Unconfined,
        )

        val error = runCatching { client.getCatalog("account-jwt") }.exceptionOrNull()

        assertTrue(error is AccountProtocolException)
        assertTrue(error?.message.orEmpty().contains("too large"))
    }

    @Test
    fun loginSendsCredentialsAndReadsTokenPair() = runBlocking {
        val calls = RecordingCallFactory(
            StubResponse(200, tokenPairJson("access-one", "refresh-one"))
        )
        val client = AccountApiClient(
            baseUrl = "https://account.example.com/",
            callFactory = calls,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val session = client.login(" learner@example.com ", "a long password")

        assertEquals("access-one", session.tokens.accessToken)
        assertEquals("refresh-one", session.tokens.refreshToken)
        assertEquals("user-1", session.user.id)
        val request = calls.requests.single()
        assertEquals("https://account.example.com/v1/auth/login", request.url.toString())
        assertEquals("POST", request.method)
        val body = JsonParser.parseString(request.bodyUtf8()).asJsonObject
        assertEquals("learner@example.com", body["email"].asString)
        assertEquals("a long password", body["password"].asString)
    }

    @Test
    fun updateAiSettingsSendsOnlyModeAndBearerToken() = runBlocking {
        val calls = RecordingCallFactory(
            StubResponse(200, aiSettingsJson("byok"))
        )
        val client = AccountApiClient(
            baseUrl = "https://account.example.com",
            callFactory = calls,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val settings = client.updateAiSettings("account-access-token", AiAccessMode.BYOK)

        assertEquals(AiAccessMode.BYOK, settings.mode)
        assertTrue(settings.platformAvailable)
        assertEquals("device", settings.keyStorage)
        val request = calls.requests.single()
        assertEquals("Bearer account-access-token", request.header("Authorization"))
        val body = JsonParser.parseString(request.bodyUtf8()).asJsonObject
        assertEquals(setOf("mode"), body.keySet())
        assertEquals("byok", body["mode"].asString)
        assertFalse(request.bodyUtf8().contains("apiKey", ignoreCase = true))
    }

    @Test
    fun serverErrorIsConvertedToSafeTypedException() = runBlocking {
        val calls = RecordingCallFactory(
            StubResponse(
                401,
                """{"error":{"code":"invalid_access_token","message":"请重新登录"}}""",
            )
        )
        val client = AccountApiClient(
            baseUrl = "https://account.example.com",
            callFactory = calls,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val error = runCatching { client.getAiSettings("expired") }.exceptionOrNull()

        assertTrue(error is AccountApiException)
        error as AccountApiException
        assertEquals(401, error.statusCode)
        assertEquals("invalid_access_token", error.errorCode)
        assertEquals("请重新登录", error.message)
    }

    @Test
    fun oversizedAccountResponseIsRejectedBeforeParsing() = runBlocking {
        val client = AccountApiClient(
            baseUrl = "https://account.example.com",
            callFactory = RecordingCallFactory(
                StubResponse(
                    200,
                    "x".repeat(AccountApiClient.MAX_RESPONSE_BODY_BYTES.toInt() + 1),
                    unknownLength = true,
                )
            ),
            ioDispatcher = Dispatchers.Unconfined,
        )

        val error = runCatching { client.getCurrentUser("access-token") }.exceptionOrNull()

        assertTrue(error is AccountProtocolException)
        assertTrue(error?.message.orEmpty().contains("too large"))
    }

    @Test
    fun passwordResetUsesDedicatedCodePurposeAndExactContract() = runBlocking {
        val calls = RecordingCallFactory(
            StubResponse(200, """{"requestId":"reset-1","expiresInSeconds":600}"""),
            StubResponse(204, ""),
        )
        val client = AccountApiClient(
            baseUrl = "https://account.example.com",
            callFactory = calls,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val code = client.requestPasswordResetCode(" learner@example.com ")
        client.resetPassword(
            email = " learner@example.com ",
            newPassword = "a new password with enough length",
            verificationRequestId = code.requestId,
            verificationCode = "123456",
        )

        val codeBody = JsonParser.parseString(calls.requests[0].bodyUtf8()).asJsonObject
        assertEquals("reset_password", codeBody["purpose"].asString)
        val reset = calls.requests[1]
        assertEquals("https://account.example.com/v1/auth/password-reset", reset.url.toString())
        val resetBody = JsonParser.parseString(reset.bodyUtf8()).asJsonObject
        assertEquals(
            setOf("email", "newPassword", "verificationRequestId", "verificationCode"),
            resetBody.keySet(),
        )
        assertEquals("learner@example.com", resetBody["email"].asString)
    }

    @Test
    fun lifecycleAndUsageRequestsUseBearerAndParseSafeFields() = runBlocking {
        val calls = RecordingCallFactory(
            StubResponse(
                200,
                """{"items":[{"id":"session/other","expiresAt":"2026-09-01T00:00:00Z","createdAt":"2026-08-01T00:00:00Z","lastUsedAt":"2026-08-12T00:00:00Z","current":false}]}""",
            ),
            StubResponse(204, ""),
            StubResponse(
                200,
                """{"items":[{"model":"official-text","promptTokens":8,"completionTokens":3,"totalTokens":11,"quotaUsed":22,"createdAt":"2026-08-12T00:00:00Z"}]}""",
            ),
        )
        val client = AccountApiClient(
            baseUrl = "https://account.example.com",
            callFactory = calls,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val sessions = client.listSessions("access-token")
        client.revokeSession("access-token", sessions.single().id)
        val usage = client.listPlatformUsage("access-token", 20)

        assertEquals("session/other", sessions.single().id)
        assertEquals(22L, usage.single().quotaUsed)
        calls.requests.forEach {
            assertEquals("Bearer access-token", it.header("Authorization"))
        }
        assertEquals("/v1/me/sessions/session%2Fother", calls.requests[1].url.encodedPath)
        assertEquals("20", calls.requests[2].url.queryParameter("limit"))
    }

    @Test
    fun lifecycleMutationRequestsMatchServerContractExactly() = runBlocking {
        val calls = RecordingCallFactory(
            StubResponse(204, ""),
            StubResponse(200, """{"revoked":2}"""),
            StubResponse(204, ""),
        )
        val client = AccountApiClient(
            baseUrl = "https://account.example.com",
            callFactory = calls,
            ioDispatcher = Dispatchers.Unconfined,
        )

        client.changePassword(
            accessToken = "access-token",
            currentPassword = "current password value",
            newPassword = "new secure password value",
        )
        val revoked = client.revokeOtherSessions("access-token")
        client.deleteAccount("access-token", "current password value")

        assertEquals(2, revoked)
        assertEquals(
            listOf("PUT", "DELETE", "DELETE"),
            calls.requests.map { it.method },
        )
        assertEquals(
            listOf("/v1/me/password", "/v1/me/sessions", "/v1/me"),
            calls.requests.map { it.url.encodedPath },
        )
        calls.requests.forEach {
            assertEquals("Bearer access-token", it.header("Authorization"))
        }
        val changeBody = JsonParser.parseString(calls.requests[0].bodyUtf8()).asJsonObject
        assertEquals(setOf("currentPassword", "newPassword"), changeBody.keySet())
        assertEquals("current password value", changeBody["currentPassword"].asString)
        assertEquals("new secure password value", changeBody["newPassword"].asString)
        val deleteBody = JsonParser.parseString(calls.requests[2].bodyUtf8()).asJsonObject
        assertEquals(setOf("currentPassword"), deleteBody.keySet())
        assertEquals("current password value", deleteBody["currentPassword"].asString)
    }

    @Test
    fun malformedLifecycleCollectionsAndCountsAreRejected() = runBlocking {
        val missingSessionsClient = AccountApiClient(
            baseUrl = "https://account.example.com",
            callFactory = RecordingCallFactory(StubResponse(200, "{}")),
            ioDispatcher = Dispatchers.Unconfined,
        )
        val negativeCountClient = AccountApiClient(
            baseUrl = "https://account.example.com",
            callFactory = RecordingCallFactory(StubResponse(200, """{"revoked":-1}""")),
            ioDispatcher = Dispatchers.Unconfined,
        )
        val missingUsageFieldClient = AccountApiClient(
            baseUrl = "https://account.example.com",
            callFactory = RecordingCallFactory(
                StubResponse(
                    200,
                    """{"items":[{"model":"official-text","createdAt":"2026-08-12T00:00:00Z"}]}""",
                )
            ),
            ioDispatcher = Dispatchers.Unconfined,
        )

        val sessionsError = runCatching {
            missingSessionsClient.listSessions("access-token")
        }.exceptionOrNull()
        val countError = runCatching {
            negativeCountClient.revokeOtherSessions("access-token")
        }.exceptionOrNull()
        val usageError = runCatching {
            missingUsageFieldClient.listPlatformUsage("access-token", 20)
        }.exceptionOrNull()

        assertTrue(sessionsError is AccountProtocolException)
        assertTrue(countError is AccountProtocolException)
        assertTrue(usageError is AccountProtocolException)
    }

    private fun Request.bodyUtf8(): String {
        val buffer = Buffer()
        requireNotNull(body).writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun tokenPairJson(accessToken: String, refreshToken: String): String =
        """
        {
          "tokenType":"Bearer",
          "accessToken":"$accessToken",
          "accessExpiresAt":"2026-08-04T01:00:00Z",
          "refreshToken":"$refreshToken",
          "refreshExpiresAt":"2026-09-03T01:00:00Z",
          "user":{
            "id":"user-1",
            "email":"learner@example.com",
            "role":"user",
            "status":"active",
            "emailVerifiedAt":"2026-08-04T00:00:00Z",
            "createdAt":"2026-08-04T00:00:00Z"
          }
        }
        """.trimIndent()

    private fun aiSettingsJson(mode: String): String =
        """
        {
          "mode":"$mode",
          "keyStorage":"device",
          "platformAvailable":true,
          "platform":{"platformEnabled":true,"balanceQuota":500,"unit":"new_api_quota"},
          "updatedAt":"2026-08-04T00:00:00Z"
        }
        """.trimIndent()
}

private data class StubResponse(
    val code: Int,
    val body: String,
    val unknownLength: Boolean = false,
)

private class RecordingCallFactory(vararg responses: StubResponse) : Call.Factory {
    private val queuedResponses = ArrayDeque(responses.toList())
    val requests = mutableListOf<Request>()

    override fun newCall(request: Request): Call {
        requests += request
        val response = checkNotNull(queuedResponses.pollFirst()) { "No response queued" }
        return StubCall(request, response)
    }
}

private class StubCall(
    private val originalRequest: Request,
    private val stub: StubResponse,
) : Call {
    private var executed = false
    private var canceled = false

    override fun request(): Request = originalRequest

    override fun execute(): Response {
        check(!executed) { "Already executed" }
        executed = true
        return response()
    }

    override fun enqueue(responseCallback: Callback) {
        check(!executed) { "Already executed" }
        executed = true
        responseCallback.onResponse(this, response())
    }

    override fun cancel() {
        canceled = true
    }

    override fun isExecuted(): Boolean = executed

    override fun isCanceled(): Boolean = canceled

    override fun clone(): Call = StubCall(originalRequest, stub)

    override fun timeout(): Timeout = Timeout.NONE

    private fun response(): Response = Response.Builder()
        .request(originalRequest)
        .protocol(Protocol.HTTP_1_1)
        .code(stub.code)
        .message(if (stub.code in 200..299) "OK" else "Error")
        .body(
            if (stub.unknownLength) {
                object : ResponseBody() {
                    private val buffer = Buffer().writeUtf8(stub.body)

                    override fun contentType() = "application/json".toMediaType()

                    override fun contentLength(): Long = -1

                    override fun source(): BufferedSource = buffer
                }
            } else {
                stub.body.toResponseBody("application/json".toMediaType())
            }
        )
        .build()
}
