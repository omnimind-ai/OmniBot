package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.account.AiAccessMode
import cn.com.omnimind.baselib.account.AiRequestAccess
import cn.com.omnimind.baselib.account.AccountNotAuthenticatedException
import cn.com.omnimind.baselib.account.AccountApiClient
import cn.com.omnimind.baselib.account.AccountRepository
import cn.com.omnimind.baselib.account.AccountTokenStore
import cn.com.omnimind.baselib.account.AccountTokens
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.Pipe
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class OfficialAgentGatewayTest {
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private val provider = AgentProviderCredentials("https://gateway.example", "unused")

    private fun token(session: String = "session-a", version: Int = 1): String = "header." +
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"iss":"account","sub":"user","sid":"$session","version":$version}""".toByteArray()
        ) + ".signature"

    private fun access(token: String = token()) = AiRequestAccess(
        mode = AiAccessMode.PLATFORM, platformGatewayUrl = provider.baseUrl, bearerToken = token,
    )

    private fun response(request: Request, status: Int, body: String = "data: hello\n\ndata: [DONE]\n\n") =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
            .header("Content-Type", "text/event-stream")
            .body(body.toResponseBody("text/event-stream".toMediaType())).build()

    private fun call(credentials: AgentProviderCredentials, path: String = "/v1/chat/completions") =
        http.newCall(Request.Builder().url(credentials.baseUrl + path)
            .header("Authorization", "Bearer ${credentials.apiKey}")
            .post("""{"model":"test","stream":true}""".toRequestBody("application/json".toMediaType()))
            .build()).execute()

    @Test fun realHttpRefreshUpdatesRepositoryAndKeepsTheSameHarnessCapability() = runBlocking {
        val modelServer = MockWebServer()
        val accountServer = MockWebServer()
        modelServer.start()
        accountServer.start()
        val oldToken = token()
        val newToken = token(version = 2)
        val store = object : AccountTokenStore {
            @Volatile var tokens: AccountTokens? = AccountTokens(
                oldToken, "2026-09-22T00:00:00Z", "test-refresh", "2026-10-22T00:00:00Z",
            )
            override fun read() = tokens
            override fun write(tokens: AccountTokens): Boolean { this.tokens = tokens; return true }
            override fun clear(): Boolean { tokens = null; return true }
        }
        val repository = AccountRepository(
            AccountApiClient(accountServer.url("/").toString(), allowInsecureLoopback = true), store,
        )
        accountServer.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""
            {"accessToken":"$newToken","accessExpiresAt":"2026-09-22T00:15:00Z",
             "refreshToken":"test-refresh-rotated","refreshExpiresAt":"2026-10-22T00:00:00Z",
             "user":{"id":"user","email":"test@example.invalid","role":"user","status":"active",
                     "emailVerifiedAt":"2026-01-01T00:00:00Z","createdAt":"2026-01-01T00:00:00Z"}}
        """.trimIndent()))
        modelServer.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
        repeat(2) {
            modelServer.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: refreshed\n\ndata: [DONE]\n\n"))
        }
        // Only the test DNS/destination is substituted. Request execution,
        // refresh HTTP, JSON decoding, repository writes and SSE use real code.
        val transport = OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                    .url(modelServer.url(chain.request().url.encodedPath)).build())
            }.build()
        val gateway = OfficialAgentGateway(
            accessProvider = { access(repository.accessTokenForPlatformGateway()) },
            refreshSession = { repository.refreshSession() }, client = transport,
        )
        try {
            val local = gateway.credentials(provider)
            repeat(2) {
                call(local).use { response ->
                    assertEquals(200, response.code)
                    assertTrue(response.body.string().contains("refreshed"))
                }
            }
            assertEquals(newToken, store.read()!!.accessToken)
            assertEquals("test-refresh-rotated", store.read()!!.refreshToken)
            assertEquals(local, gateway.credentials(provider))
            assertEquals(1, accountServer.requestCount)
            val refresh = accountServer.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/v1/auth/refresh", refresh.path)
            assertEquals("""{"refreshToken":"test-refresh"}""", refresh.body.readUtf8())
            assertEquals(3, modelServer.requestCount)
            assertEquals(listOf(oldToken, newToken, newToken), (1..3).map {
                modelServer.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization")!!.removePrefix("Bearer ")
            })
        } finally {
            gateway.close()
            modelServer.shutdown()
            accountServer.shutdown()
        }
    }

    @Test fun expiredTokenRefreshesOneHttpRequestWithoutChangingProcessCredentials() = runBlocking {
        var access = access()
        var refreshes = 0
        val requests = mutableListOf<Request>()
        val gateway = OfficialAgentGateway(
            accessProvider = { access },
            refreshSession = { refreshes++; access = access(token(version = 2)) },
            executeRequest = { request ->
                requests += request
                response(request, if (requests.size == 1) 401 else 200)
            },
        )
        try {
            val local = gateway.credentials(provider)
            assertTrue(local.baseUrl.startsWith("http://127.0.0.1:"))
            assertNotEquals(access.bearerToken, local.apiKey)
            call(local).use {
                assertEquals(200, it.code)
                assertEquals("data: hello\n\ndata: [DONE]\n\n", it.body!!.string())
            }
            assertEquals(1, refreshes)
            assertEquals(listOf("Bearer ${token()}", "Bearer ${token(version = 2)}"),
                requests.map { it.header("Authorization") })
            assertEquals(1, requests.map { request ->
                Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            }.distinct().size)
            assertEquals(local, gateway.credentials(provider))
            access = access(token(version = 3))
            call(local).close()
            assertEquals("Bearer ${token(version = 3)}", requests.last().header("Authorization"))
            assertEquals(1, refreshes)
        } finally { gateway.close() }
    }

    @Test fun repeatedUnauthorizedStopsAfterOneRefresh() = runBlocking {
        var calls = 0
        var refreshes = 0
        val gateway = OfficialAgentGateway(accessProvider = { access() },
            refreshSession = { refreshes++ }, executeRequest = { calls++; response(it, 401) })
        try {
            call(gateway.credentials(provider)).use { assertEquals(401, it.code); it.body!!.string() }
            assertEquals(2, calls)
            assertEquals(1, refreshes)
        } finally { gateway.close() }
    }

    @Test fun accountChangeDuringRefreshCannotUseNewAccountsCredentials() = runBlocking {
        var access = access()
        var calls = 0
        val gateway = OfficialAgentGateway(accessProvider = { access },
            refreshSession = { access = access(token(session = "session-b")) },
            executeRequest = { calls++; response(it, 401) })
        try {
            call(gateway.credentials(provider)).use { assertEquals(401, it.code) }
            assertEquals(1, calls)
        } finally { gateway.close() }
    }

    @Test fun revokedRefreshTokenReturnsLoginErrorWithoutAnotherModelRequest() = runBlocking {
        var calls = 0
        val gateway = OfficialAgentGateway(accessProvider = { access() },
            refreshSession = { throw AccountNotAuthenticatedException() },
            executeRequest = { calls++; response(it, 401) })
        try {
            call(gateway.credentials(provider)).use {
                assertEquals(401, it.code)
                assertTrue(it.body.string().contains("invalid_access_token"))
            }
            assertEquals(1, calls)
        } finally { gateway.close() }
    }

    @Test fun logoutAndReplacementLoginInvalidateOldProcessCapability() = runBlocking {
        var access = access()
        var calls = 0
        val gateway = OfficialAgentGateway(accessProvider = { access },
            executeRequest = { calls++; response(it, 200) })
        try {
            val old = gateway.credentials(provider)
            access = AiRequestAccess(mode = AiAccessMode.BYOK)
            call(old).use { assertEquals(401, it.code) }
            access = access(token(session = "session-b"))
            val fresh = gateway.credentials(provider)
            assertNotEquals(old.apiKey, fresh.apiKey)
            call(old).use { assertEquals(401, it.code) }
            call(fresh).use { assertEquals(200, it.code) }
            assertEquals(1, calls)
        } finally { gateway.close() }
    }

    @Test fun wrongCapabilityAndNonModelRoutesNeverReachUpstream() = runBlocking {
        var calls = 0
        val gateway = OfficialAgentGateway(accessProvider = { access() },
            executeRequest = { calls++; response(it, 200) })
        try {
            val local = gateway.credentials(provider)
            call(local.copy(apiKey = "wrong")).use { assertEquals(401, it.code) }
            call(local, "/account/delete").use { assertEquals(404, it.code) }
            assertEquals(0, calls)
        } finally { gateway.close() }
    }

    @Test fun quotaAndServerErrorsAreReturnedWithoutRefreshOrRetry() = runBlocking {
        for (status in listOf(403, 429, 500)) {
            var calls = 0
            val gateway = OfficialAgentGateway(accessProvider = { access() },
                refreshSession = { fail("must not refresh for $status") },
                executeRequest = { calls++; response(it, status, "error") })
            try {
                call(gateway.credentials(provider)).use {
                    assertEquals(status, it.code)
                    assertEquals("error", it.body!!.string())
                }
                assertEquals(1, calls)
            } finally { gateway.close() }
        }
    }

    @Test fun streamsFirstEventBeforeUpstreamCompletes() = runBlocking {
        val pipe = Pipe(8192)
        val sink = pipe.sink.buffer()
        sink.writeUtf8("data: first\n\n").flush()
        var calls = 0
        val gateway = OfficialAgentGateway(accessProvider = { access() }, executeRequest = {
            calls++
            response(it, 200).newBuilder().body(object : okhttp3.ResponseBody() {
                private val input = pipe.source.buffer()
                override fun contentType() = "text/event-stream".toMediaType()
                override fun contentLength() = -1L
                override fun source() = input
            }).build()
        })
        try {
            call(gateway.credentials(provider)).use {
                assertEquals(200, it.code)
                assertEquals("data: first", it.body.source().readUtf8LineStrict())
                // This is only written after observing the first event. A
                // buffering relay deadlocks/times out before reaching here.
                sink.writeUtf8("data: [DONE]\n\n").close()
                assertTrue(it.body.string().contains("[DONE]"))
            }
            assertEquals(1, calls)
        } finally {
            pipe.cancel()
            gateway.close()
        }
    }

    @Test fun failureAfterStreamStartsDoesNotReplayRequest() = runBlocking {
        val pipe = Pipe(8192)
        val sink = pipe.sink.buffer()
        sink.writeUtf8("data: first\n\n").flush()
        var calls = 0
        var refreshes = 0
        val gateway = OfficialAgentGateway(accessProvider = { access() }, refreshSession = { refreshes++ },
            executeRequest = {
                calls++
                response(it, 200).newBuilder().body(object : okhttp3.ResponseBody() {
                    private val input = pipe.source.buffer()
                    override fun contentType() = "text/event-stream".toMediaType()
                    override fun contentLength() = -1L
                    override fun source() = input
                }).build()
            })
        try {
            call(gateway.credentials(provider)).use {
                assertEquals("data: first", it.body.source().readUtf8LineStrict())
                pipe.cancel()
                // Some engines close a broken stream with an I/O error, some
                // with EOF. Neither may produce another upstream request.
                try { it.body.string() } catch (_: IOException) { }
            }
            assertEquals(1, calls)
            assertEquals(0, refreshes)
        } finally {
            pipe.cancel()
            gateway.close()
        }
    }
}
