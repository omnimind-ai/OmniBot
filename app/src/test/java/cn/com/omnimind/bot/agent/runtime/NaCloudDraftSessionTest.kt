package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class NaCloudDraftSessionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun openingReloadingAndListingAnUnsentCloudSessionNeverCreatesLocalHistory() = runBlocking {
        val context = acpProfileStoreTestContext(folder.root)
        `when`(context.cacheDir).thenReturn(folder.root)
        val repository = mock(AgentSessionBindingRepository::class.java)
        val store = AcpAgentProfileStore(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        fun runtime() = NaCloudRuntime(context, scope, repository, AcpAgentProfileStore(context), emit = {})
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"conversation":{"id":"draft","title":"New"}}"""))
                server.enqueue(MockResponse().setBody("""{"conversation":{"id":"draft","title":"New"}}"""))
                server.enqueue(MockResponse().setBody("""{"conversations":[{"id":"draft","title":"New"}]}"""))
                server.enqueue(MockResponse().setBody("""{"runId":"first-run"}"""))
                server.enqueue(MockResponse().setBody("""{"run":{"status":"completed","items":[]}}"""))
                server.start()
                val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))
                withContext(connection) {
                    val opened = runtime().callConnected("session/new", emptyMap()) as Map<*, *>
                    val id = opened["sessionId"].toString()
                    assertEquals(connection.prefix + "draft", id)
                    assertNull(opened["conversationId"])
                    val reopened = runtime()
                    val loaded = reopened.callConnected("session/load", mapOf("sessionId" to id)) as Map<*, *>
                    assertNull(loaded["conversationId"])
                    val listed = reopened.callConnected("session/list", emptyMap()) as Map<*, *>
                    assertEquals(emptyList<Any>(), listed["sessions"])
                    assertFalse(mockingDetails(repository).invocations.any { it.method.name == "ensureBinding" })

                    `when`(repository.ensureBinding(id, NA_AGENT_ID, cwd = "na-cloud")).thenReturn(42L)
                    val sent = reopened.callConnected("session/prompt", mapOf("sessionId" to id, "text" to "hello")) as Map<*, *>
                    assertEquals(id, sent["sessionId"])
                    assertEquals(42L, sent["conversationId"])
                    verify(repository).ensureBinding(id, NA_AGENT_ID, cwd = "na-cloud")
                    assertEquals(connection.prefix, store.naOwnerForConversation(42L))
                }
                assertEquals("POST", server.takeRequest().method)
                assertEquals("/api/conversations/draft", server.takeRequest().path)
                assertEquals("/api/conversations", server.takeRequest().path)
                assertEquals("/api/conversations/draft/messages", server.takeRequest().path)
            }
        } finally { scope.cancel() }
    }

    private suspend fun NaCloudRuntime.callConnected(method: String, args: Map<String, Any?>): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            javaClass.getDeclaredMethod("handleConnected", String::class.java, Map::class.java, Continuation::class.java)
                .apply { isAccessible = true }.invoke(this, method, args, continuation).let {
                    if (it === COROUTINE_SUSPENDED) it else it
                }
        }
}
