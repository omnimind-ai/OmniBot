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
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class NaCloudStreamingTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun promptUsesOneSubmissionAndSseChunksThroughTheExistingAcpBoundary() = runBlocking {
        val context = acpProfileStoreTestContext(folder.root)
        `when`(context.cacheDir).thenReturn(folder.root)
        val bindings = mock(AgentSessionBindingRepository::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val seen = mutableListOf<Map<String, Any?>>()
        val runtime = NaCloudRuntime(context, scope, bindings, AcpAgentProfileStore(context)) { seen += it }
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"runId":"run"}"""))
                server.enqueue(MockResponse().setBody("""{"eventCursor":1,"run":{"id":"run","status":"running","items":[]}}"""))
                val events = listOf(
                    """{"id":2,"type":"item.updated","runId":"run","data":{"item":{"id":"reply","type":"agent_message","text":"first "}}}""",
                    """{"id":3,"type":"item.updated","runId":"run","data":{"item":{"id":"reply","type":"agent_message","text":"first second"}}}""",
                    """{"id":4,"type":"run.completed","runId":"run","data":{"run":{"id":"run","status":"completed","items":[{"id":"reply","type":"agent_message","text":"first second"}]}}}""",
                ).joinToString("") { "event: na\ndata: $it\n\n" }
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8").setChunkedBody(events, 17))
                server.start()
                val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))
                val session = connection.prefix + "conversation"
                `when`(bindings.ensureBinding(session, NA_AGENT_ID, cwd = "na-cloud")).thenReturn(42L)
                withContext(connection) {
                    val args = mapOf("sessionId" to session, "requestId" to "same-turn", "text" to "hello")
                    val result = runtime.callPrompt(args) as Map<*, *>
                    assertEquals("end_turn", result["stopReason"])
                    assertEquals(result, runtime.callPrompt(args))
                }
                assertEquals(3, server.requestCount)
                assertEquals("POST", server.takeRequest().method)
                assertEquals("/api/runs/run", server.takeRequest().path)
                assertEquals("/api/events?after=1", server.takeRequest().path)
                assertEquals(listOf("first ", "second"), seen.map {
                    val update = (it["params"] as Map<*, *>)["update"] as Map<*, *>
                    assertEquals("agent_message_chunk", update["sessionUpdate"])
                    assertEquals("reply", update["messageId"])
                    (update["content"] as Map<*, *>)["text"]
                })
                assertTrue(seen.all { it["method"] == "session/update" && it["sessionId"] == session && it["turnId"] == "same-turn" })
            }
        } finally { scope.cancel() }
    }

    private suspend fun NaCloudRuntime.callPrompt(args: Map<String, Any?>): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            javaClass.getDeclaredMethod("handleConnected", String::class.java, Map::class.java, Continuation::class.java)
                .apply { isAccessible = true }.invoke(this, "session/prompt", args, continuation)
        }
}
