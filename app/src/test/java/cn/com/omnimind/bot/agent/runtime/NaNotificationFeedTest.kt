package cn.com.omnimind.bot.agent.runtime

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

class NaNotificationFeedTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun receiptAndCursorSurviveRecreationAndRevokedLeaseCannotAdvance() {
        val context = acpProfileStoreTestContext(folder.root)
        val state = NaNotificationState(enabled = true, owner = "na:0123456789abcdef:",
            lease = "a9fb43b7-bd46-45e2-98be-c0a9529e9314", cursor = 42, postedIds = listOf("n_one"))
        NaNotificationStore(context).write(state)
        val reopened = NaNotificationStore(acpProfileStoreTestContext(folder.root))
        assertEquals(state, reopened.read())
        reopened.requireCurrent(state)
        reopened.write(state.copy(lease = "d5931d42-434d-4dce-bcaf-91399c1369b7"))
        assertTrue(runCatching { reopened.requireCurrent(state) }.isFailure)
        reopened.write(NaNotificationState())
        assertFalse(NaNotificationStore(context).read().enabled)
    }

    @Test fun localSettingsCanBeReadAndDisabledOfflineButRejectStaleEndpointOwner() = runBlocking {
        val context = acpProfileStoreTestContext(folder.root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = NaCloudRuntime(context, scope, mock(AgentSessionBindingRepository::class.java), AcpAgentProfileStore(context), {})
        try {
            val initial = runtime.handle("config/na/notifications", mapOf("operation" to "read")) as Map<*, *>
            assertNotNull(initial["owner"])
            runtime.handle("config/na/notifications", mapOf("operation" to "write", "enabled" to false, "owner" to initial["owner"]))
            if (NaCloudEnvironment.developmentAvailable) {
                runtime.configure(mapOf("connectionMode" to "development", "baseUrl" to "http://127.0.0.1:8787", "apiKey" to "fixture-instance-token"))
                assertTrue(runCatching { runtime.handle("config/na/notifications", mapOf("operation" to "write", "enabled" to false, "owner" to initial["owner"])) }.isFailure)
                val current = runtime.handle("config/na/notifications", mapOf("operation" to "read")) as Map<*, *>
                runtime.handle("config/na/notifications", mapOf("operation" to "write", "enabled" to false, "owner" to current["owner"]))
            }
        } finally { scope.cancel() }
    }

    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).readTimeout(2, TimeUnit.SECONDS).build()
    private val empty = """{"notifications":[],"readIds":[],"nextCursor":42,"hasMore":false}"""

    @Test fun onlyFixedReadOnlyFeedUsesCapturedConnectionAndExactCursor() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(empty))
            server.start()
            val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'), developmentToken = "fixture-private-instance-token")
            val batch = naNotificationFeedRequest(connection, client, 41, 25000) {}
            assertEquals(42L, batch.nextCursor)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/notifications/feed?after=41&limit=100&waitMs=25000", request.path)
            assertEquals("Bearer fixture-private-instance-token", request.getHeader("Authorization"))
            assertEquals(0, request.bodySize)
            assertFalse(batch.toString().contains("fixture-private-instance-token"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun staleOwnerBeforeDispatchNeverContactsService() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            assertTrue(runCatching {
                naNotificationFeedRequest(NaCloudConnection(server.url("/").toString().trimEnd('/')), client, 0, 0) {
                    error("owner changed")
                }
            }.isFailure)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun redirectAndProviderErrorNeverLeakCredentialsOrBecomeSuccessfulFeed() = runBlocking {
        for (response in listOf(MockResponse().setResponseCode(302).setHeader("Location", "https://example.test/other"),
            MockResponse().setResponseCode(401).setBody("private-instance-token and server internals"))) {
            MockWebServer().use { server ->
                server.enqueue(response)
                server.start()
                val failure = runCatching {
                    naNotificationFeedRequest(NaCloudConnection(server.url("/").toString().trimEnd('/'),
                        developmentToken = "private-instance-token"), client, 0, 0) {}
                }.exceptionOrNull()
                assertNotNull(failure)
                assertFalse(failure!!.message.orEmpty().contains("private-instance-token"))
                assertEquals(1, server.requestCount)
            }
        }
    }
}
