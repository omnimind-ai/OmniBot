package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.account.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*

class NaCloudConnectionTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun staleWorkspaceWindowCannotActOnAnotherAccountOrEndpoint() {
        requireNaWorkspaceOwner("na:alice:cloud-a:", "na:alice:cloud-a:", true)
        requireNaWorkspaceOwner("na:alice:cloud-a:", null, false)
        for (owner in listOf(null, "", "na:bob:cloud-a:", "na:alice:cloud-b:")) {
            assertThrows(IllegalArgumentException::class.java) {
                requireNaWorkspaceOwner("na:alice:cloud-a:", owner, true)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireNaWorkspaceOwner("na:bob:cloud-a:", "na:alice:cloud-a:", false)
        }
    }
    private val user = AccountUser("alice", "alice@example.test", "user", "active", "", "")
    private val tokens = AccountTokens("access-one", "2099-01-01T00:00:00Z", "refresh-one", "2099-01-01T00:00:00Z")
    private class Store(var tokens: AccountTokens?) : AccountTokenStore {
        override fun read() = tokens
        override fun write(tokens: AccountTokens): Boolean { this.tokens = tokens; return true }
        override fun clear(): Boolean { tokens = null; return true }
    }
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    @Test fun officialEndpointRejectsInsecureOrDecoratedUrls() {
        for (url in listOf("http://example.test", "https://name:pass@example.test", "https://example.test/api", "https://example.test?token=x", "https://example.test#x")) {
            assertTrue(url, runCatching { NaCloudConnection.officialBaseUrl(url, false) }.isFailure)
        }
        assertEquals("http://127.0.0.1:18090", NaCloudConnection.officialBaseUrl("http://127.0.0.1:18090", true))
        assertTrue(runCatching { NaCloudConnection.officialBaseUrl("http://127.0.0.1:18090", false) }.isFailure)
    }

    @Test fun managedConnectionClaimsOnlyOwnWorkspaceAndKeepsStableOwnerAcrossRefresh() = runBlocking {
        val remote = mock(AccountRemoteDataSource::class.java)
        `when`(remote.getCurrentUser("access-one")).thenReturn(user)
        `when`(remote.refresh("refresh-one")).thenReturn(AccountSession(user, tokens.copy(accessToken = "access-two", refreshToken = "refresh-two")))
        val account = AccountRepository(remote, Store(tokens))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"workspace\":null,\"executionAvailable\":false}"))
            server.enqueue(MockResponse().setResponseCode(201).setBody("{\"workspace\":{\"id\":\"server-space\"},\"executionAvailable\":true}"))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("ok"))
            server.start()
            val connection = NaCloudConnection.managed(server.url("/").newBuilder().host("127.0.0.1").build().toString(), true, account)
            connection.prepareWorkspace(client)
            val result = connection.execute(client, Request.Builder().url(connection.apiBaseUrl + "/api/status").build()) { it.body.string() }
            assertEquals("ok", result)
            assertEquals("/v1/cloud/workspace", server.takeRequest().path)
            val claim = server.takeRequest()
            assertEquals("POST", claim.method)
            assertEquals("/v1/cloud/workspace/claim", claim.path)
            assertEquals("{}", claim.body.readUtf8())
            assertEquals("Bearer access-one", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer access-two", server.takeRequest().getHeader("Authorization"))
            assertEquals(connection.prefix, NaCloudConnection.managed(connection.baseUrl, true, account).prefix)
            assertNotEquals(connection.prefix, NaCloudConnection(connection.baseUrl, account, "bob").prefix)
        }
    }

    @Test fun managedPostDoesNotReplayAndLogoutFencesOldConnection() = runBlocking {
        val remote = mock(AccountRemoteDataSource::class.java)
        `when`(remote.getCurrentUser("access-one")).thenReturn(user)
        val account = AccountRepository(remote, Store(tokens))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.start()
            val connection = NaCloudConnection.managed(server.url("/").newBuilder().host("127.0.0.1").build().toString(), true, account)
            val request = Request.Builder().url(connection.apiBaseUrl + "/api/conversations/c/messages").post("{}".toRequestBody()).build()
            assertTrue(runCatching { connection.execute(client, request) { it.code } }.exceptionOrNull() is AccountApiException)
            assertEquals(1, server.requestCount)
            account.logout()
            assertTrue(runCatching { connection.execute(client, request) { it.code } }.exceptionOrNull() is AccountNotAuthenticatedException)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun unavailableWorkspaceCannotProceedToAnExecutionRequest() = runBlocking {
        val remote = mock(AccountRemoteDataSource::class.java)
        `when`(remote.getCurrentUser("access-one")).thenReturn(user)
        val account = AccountRepository(remote, Store(tokens))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"workspace\":{\"id\":\"space\"},\"executionAvailable\":false}"))
            server.start()
            val connection = NaCloudConnection.managed(server.url("/").newBuilder().host("127.0.0.1").build().toString(), true, account)
            assertTrue(runCatching { connection.prepareWorkspace(client) }.isFailure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun historyObservationNeverClaimsAMissingWorkspace() = runBlocking {
        val remote = mock(AccountRemoteDataSource::class.java)
        `when`(remote.getCurrentUser("access-one")).thenReturn(user)
        val account = AccountRepository(remote, Store(tokens))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"workspace\":null,\"executionAvailable\":false}"))
            server.start()
            val connection = NaCloudConnection.managed(server.url("/").newBuilder().host("127.0.0.1").build().toString(), true, account)
            assertTrue(runCatching { connection.prepareWorkspace(client, allowClaim = false) }.isFailure)
            assertEquals(1, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
        }
    }

    @Test fun foregroundObserverCannotClaimWorkspaceAndNotificationSetupCanSkipIt() = runBlocking {
        val remote = mock(AccountRemoteDataSource::class.java)
        `when`(remote.getCurrentUser("access-one")).thenReturn(user)
        val account = AccountRepository(remote, Store(tokens))
        // Inject only the local account/HTTP boundaries. Exercise the ordinary
        // runtime routes with no sockets, Android services or real credentials.
        val accountField = OmniAccount.javaClass.getDeclaredField("configuredRepository").apply { isAccessible = true }
        val previous = accountField.get(OmniAccount)
        accountField.set(OmniAccount, account)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val context = acpProfileStoreTestContext(folder.root)
        val paths = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val workspaceRead = CompletableDeferred<Unit>()
        val runtime = NaCloudRuntime(context, scope, mock(AgentSessionBindingRepository::class.java), AcpAgentProfileStore(context), {})
        val fakeHttp = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            paths += request.method + " " + request.url.encodedPath
            val body = if (request.url.encodedPath == "/v1/cloud/workspace") {
                workspaceRead.complete(Unit)
                """{"workspace":null,"executionAvailable":false}"""
            } else """{"capabilities":[]}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        NaCloudRuntime::class.java.getDeclaredField("client").apply { isAccessible = true }.set(runtime, fakeHttp)
        try {
            runtime.handle("config/na/historySync", mapOf("enabled" to true))
            withTimeout(2000) { workspaceRead.await() }
            val settings = runtime.handle("config/na/notifications", mapOf("operation" to "read")) as Map<*, *>
            val unsupported = runCatching {
                runtime.handle("config/na/notifications", mapOf("operation" to "write", "enabled" to true, "owner" to settings["owner"]))
            }.exceptionOrNull()
            assertTrue(unsupported?.message.orEmpty().contains("尚未提供后台消息接收"))
            runtime.handle("config/na/historySync", mapOf("enabled" to false))
            assertEquals(listOf("GET /v1/cloud/workspace", "GET /v1/cloud/na/api/status"), paths.toList())
            assertFalse(paths.any { it.contains("claim") || it.startsWith("POST ") })
        } finally {
            scope.cancel()
            accountField.set(OmniAccount, previous)
        }
    }
}
