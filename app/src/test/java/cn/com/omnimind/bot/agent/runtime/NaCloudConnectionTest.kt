package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.account.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class NaCloudConnectionTest {
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
}
