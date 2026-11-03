package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import cn.com.omnimind.bot.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.Assume.assumeTrue
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import java.util.concurrent.TimeUnit

class NaPersonalApiTest {
    @get:Rule val directory = TemporaryFolder()
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).readTimeout(2, TimeUnit.SECONDS).build()

    @Test fun operationsResolveOnlyFixedRoutesWithExactMutationBodies() {
        val approve = naPersonalRoute("actions.approve", "action_123", emptyMap())
        assertEquals("POST", approve.method)
        assertEquals("/api/actions/action_123/approve", approve.path)
        val body = mapOf("toolkit" to "github", "accountId" to "ca_one")
        val disconnect = naPersonalRoute("composio.disconnect", null, body)
        assertEquals("DELETE", disconnect.method)
        assertEquals("/api/integrations/composio", disconnect.path)
        assertEquals(body, disconnect.payload)
        assertEquals("/api/connectors/actions/action_123", naPersonalRoute("connectors.result", "action_123", emptyMap()).path)
        assertEquals("/api/memories/search", naPersonalRoute("memories.search", null, mapOf("query" to "论文")).path)
        assertEquals("/api/profile", naPersonalRoute("profile.read", null, emptyMap()).path)
        assertEquals("PUT", naPersonalRoute("memories.update", "memory_123", mapOf("key" to "Writing", "value" to "Concise")).method)
    }

    @Test fun callersCannotSelectPrivateUrlsOrSmuggleIdsAndApprovalParameters() {
        for (operation in listOf("https://evil.test", "../settings", "settings.update", "tokens.read", "google.execute", "google.read", "google.authorize", "google.disconnect")) {
            assertThrows(IllegalArgumentException::class.java) { naPersonalRoute(operation, null, emptyMap()) }
        }
        for (id in listOf("..", "a/b", "a?token=x", "a%2fapprove", "a\\b", "")) {
            assertThrows(IllegalArgumentException::class.java) { naPersonalRoute("actions.approve", id, emptyMap()) }
        }
        assertThrows(IllegalArgumentException::class.java) { naPersonalRoute("actions.approve", "one", mapOf("approved" to true)) }
        assertThrows(IllegalArgumentException::class.java) { naPersonalRoute("profile.read", null, mapOf("url" to "https://evil.test")) }
        assertThrows(IllegalArgumentException::class.java) { naPersonalRoute("status", "unexpected", emptyMap()) }
    }

    @Test fun everyResourceAccessRetainsAccountEndpointAndConfigurationOwner() {
        val original = naPersonalOwner("na:alice:endpoint-a:", 1, 2)
        requireNaPersonalOwner(original, null, true)
        requireNaPersonalOwner(original, original, false)
        for (other in listOf(null, "", naPersonalOwner("na:bob:endpoint-a:", 1, 2),
            naPersonalOwner("na:alice:endpoint-b:", 1, 2), naPersonalOwner("na:alice:endpoint-a:", 2, 2),
            naPersonalOwner("na:alice:endpoint-a:", 1, 3))) {
            assertThrows(IllegalArgumentException::class.java) { requireNaPersonalOwner(original, other, false) }
        }
        assertFalse(original.contains("alice"))
    }

    @Test fun changingDevelopmentCredentialsOnSameEndpointFencesAlreadyRenderedActions() = runBlocking {
        assumeTrue(BuildConfig.FLAVOR_version == "develop")
        val context = acpProfileStoreTestContext(directory.root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = NaCloudRuntime(context, scope, mock(AgentSessionBindingRepository::class.java), AcpAgentProfileStore(context), emit = {})
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("{\"capabilities\":[\"actions\"]}"))
                server.enqueue(MockResponse().setBody("{\"capabilities\":[\"actions\"]}"))
                server.start()
                val address = server.url("/").toString().trimEnd('/')
                runtime.configure(mapOf("connectionMode" to "development", "baseUrl" to address, "apiKey" to "original-token"))
                val first = runtime.handle("config/na/personal", mapOf("operation" to "status")) as Map<*, *>
                runtime.configure(mapOf("connectionMode" to "development", "baseUrl" to address, "apiKey" to "replacement-token"))
                assertTrue(runCatching { runtime.handle("config/na/personal", mapOf("operation" to "actions.approve", "id" to "one", "owner" to first["owner"])) }.isFailure)
                assertEquals(1, server.requestCount)
                val second = runtime.handle("config/na/personal", mapOf("operation" to "status")) as Map<*, *>
                assertNotEquals(first["owner"], second["owner"])
                assertEquals("Bearer original-token", server.takeRequest().getHeader("Authorization"))
                assertEquals("Bearer replacement-token", server.takeRequest().getHeader("Authorization"))
            }
        } finally { scope.cancel() }
    }

    @Test fun authorizationLinksHaveExactOfficialHostsAndNoEmbeddedCredentials() {
        assertThrows(IllegalArgumentException::class.java) { validateNaPersonalAuthorizationUrl("google.authorize", "https://accounts.google.com/o/oauth2/v2/auth?state=fixture") }
        validateNaPersonalAuthorizationUrl("composio.authorize", "https://connect.composio.dev/link/ln_fixture")
        for (url in listOf("https://connect.composio.dev.evil.test/link/ln_one", "http://connect.composio.dev/link/ln_one",
            "https://user:password@connect.composio.dev/link/ln_one", "https://connect.composio.dev:444/link/ln_one",
            "https://connect.composio.dev/redirect", "https://connect.composio.dev/link/ln_one?next=https://evil.test")) {
            assertThrows(IllegalArgumentException::class.java) { validateNaPersonalAuthorizationUrl("composio.authorize", url) }
        }
    }

    @Test fun nativeTransportCapturesExactAccountConnectionAndDoesNotExposeCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(202).setBody("{\"action\":{\"id\":\"one\",\"status\":\"pending\"}}"))
            server.start()
            val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'), developmentToken = "private-dev-token")
            val result = naPersonalHttpRequest(connection, client, naPersonalRoute("connectors.execute", null,
                mapOf("provider" to "composio", "tool" to "GITHUB_CREATE_ISSUE", "accountId" to "ca_one", "args" to mapOf("title" to "Review", "body" to null))))
            assertEquals("pending", (result["action"] as Map<*, *>)["status"])
            assertFalse(result.toString().contains("private-dev-token"))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/connectors/execute", request.path)
            assertEquals("Bearer private-dev-token", request.getHeader("Authorization"))
            val submitted = request.body.readUtf8()
            assertTrue(submitted.contains("\"accountId\":\"ca_one\""))
            assertTrue(submitted.contains("\"body\":null"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun staleOwnerBeforeDispatchCannotPerformAnApproval() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))
            assertTrue(runCatching {
                naPersonalHttpRequest(connection, client, naPersonalRoute("actions.approve", "one", emptyMap())) {
                    throw IllegalStateException("Connection changed")
                }
            }.isFailure)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun changedOwnerDuringResponseCannotUpdateAnotherAccountsResourceWindow() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"memories\":[{\"value\":\"old-account-fact\"}]}"))
            server.start()
            var checks = 0
            val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))
            assertTrue(runCatching {
                naPersonalHttpRequest(connection, client, naPersonalRoute("memories.list", null, emptyMap())) {
                    if (++checks > 1) throw IllegalStateException("Connection changed")
                }
            }.isFailure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun oversizedResponsesAndUntrustedAuthorizationLinksFailBeforeBeingReturned() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(2 * 1024 * 1024 + 1)))
            server.enqueue(MockResponse().setBody("{\"url\":\"https://evil.test/link/fixture\"}"))
            server.start()
            val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))
            assertTrue(runCatching { naPersonalHttpRequest(connection, client, naPersonalRoute("status", null, emptyMap())) }.isFailure)
            assertTrue(runCatching { naPersonalHttpRequest(connection, client, naPersonalRoute("composio.authorize", null, mapOf("toolkit" to "github"))) }.isFailure)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun standalonePublicValidationErrorsRemainUsefulAndHideAccessMaterial() {
        assertEquals("请先连接所需能力。", naPersonalPublicError(403, "请先连接所需能力。", false))
        val message = naPersonalPublicError(400, "参数无效 https://example.test/callback?token=private Bearer private-token api_key=private-key sk_examplelongsecret", false)
        assertTrue(message.contains("参数无效"))
        for (secret in listOf("https://", "private-token", "private-key", "sk_examplelongsecret")) assertFalse(message.contains(secret))
        assertEquals("登录已失效，请重新登录。", naPersonalPublicError(401, "private detail", true))
        assertTrue(naPersonalPublicError(400, "x".repeat(2000), false).length <= 500)
    }

    @Test fun aLostApprovalResponseIsNeverAutomaticallyReplayed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.start()
            val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))
            assertTrue(runCatching { naPersonalHttpRequest(connection, client, naPersonalRoute("actions.approve", "one", emptyMap())) }.isFailure)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun composioSettingsUseFixedOwnerBoundRoutesAndKeepProjectKeyWriteOnly() = runBlocking {
        val body = mapOf("expectedRevision" to 7, "enabled" to true, "toolkits" to listOf("gmail"), "authConfigs" to emptyMap<String, String>(), "apiKey" to "write-only-fixture-key")
        assertEquals("GET", naPersonalRoute("composio.settings.read", null, emptyMap()).method)
        assertEquals("/api/integrations/composio/settings", naPersonalRoute("composio.settings.read", null, emptyMap()).path)
        val update = naPersonalRoute("composio.settings.update", null, body)
        assertEquals("PUT", update.method)
        assertEquals(body, update.payload)
        assertThrows(IllegalArgumentException::class.java) { requireNaPersonalOwner("current-owner", "old-owner", false) }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"settings":{"enabled":true,"apiKeyConfigured":true,"configured":true,"toolkits":["gmail"],"authConfigs":{},"revision":8,"apiKey":"accidental-server-key","secret":"private"},"apiKey":"outer-key"}"""))
            server.start()
            val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'), developmentToken = "instance-token")
            val result = naPersonalHttpRequest(connection, client, update)
            val settings = result["settings"] as Map<*, *>
            assertEquals(true, settings["apiKeyConfigured"])
            assertEquals(8.0, settings["revision"])
            for (secret in listOf("write-only-fixture-key", "accidental-server-key", "outer-key", "private", "instance-token")) assertFalse(result.toString().contains(secret))
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/api/integrations/composio/settings", request.path)
            val submitted = request.body.readUtf8()
            assertTrue(submitted.contains("write-only-fixture-key"))
            assertTrue(submitted.contains("expectedRevision"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun settingsWriteFailureCannotEchoSubmittedKeyAndIsNotRetried() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"stale config; bare-fixture-private-value"}"""))
            server.start()
            val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))
            val failure = runCatching { naPersonalHttpRequest(connection, client,
                naPersonalRoute("composio.settings.update", null, mapOf("expectedRevision" to 1, "apiKey" to "bare-fixture-private-value"))) }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(failure!!.message.orEmpty().contains("bare-fixture-private-value"))
            assertEquals(1, server.requestCount)
        }
    }

}
