package cn.com.omnimind.assists.controller.http

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GeminiInteractionsHttpTest {
    @Test fun `native discovery requests every page with Google auth and custom headers`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"models":[{"name":"models/first"}],"nextPageToken":"p2"}"""))
            server.enqueue(MockResponse().setBody("""{"models":[{"name":"models/second"}]}"""))
            val models = HttpController.fetchProviderModels(server.url("/v1").toString(), "test-key",
                mapOf("X-Route" to "test"), "gemini_interactions")
            assertEquals(listOf("first", "second"), models.map { it.id })
            val first = server.takeRequest(2, TimeUnit.SECONDS)!!
            val second = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("test-key", first.getHeader("x-goog-api-key"))
            assertEquals("test", first.getHeader("X-Route"))
            assertNull(first.getHeader("Authorization"))
            assertTrue(first.path!!.startsWith("/v1/models?"))
            assertTrue(second.path!!.contains("pageToken=p2"))
        }
    }

    @Test fun `repeating catalog tokens fail instead of looping`() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("""{"models":[],"nextPageToken":"same"}""")) }
            assertTrue(runCatching {
                HttpController.fetchProviderModels(server.url("/v1").toString(), "test", protocolType = "gemini_interactions")
            }.isFailure)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `request auth overrides are merged without adding bearer auth`() {
        val method = HttpController::class.java.getDeclaredMethod("buildGeminiRequest", String::class.java,
            String::class.java, Map::class.java, JsonObject::class.java)
        method.isAccessible = true
        val request = (method.invoke(HttpController, "https://example.test/v1", "test-key",
            mapOf("x-goog-api-key" to "override"), null) as Request.Builder).build()
        assertEquals("override", request.header("x-goog-api-key"))
        assertNull(request.header("Authorization"))
        assertEquals("/v1/interactions", request.url.encodedPath)
    }

    @Test fun `SSE EOF without interaction completion fails after partial text`() {
        MockWebServer().use { server ->
            val body = """
                event: step.start
                data: {"event_type":"step.start","index":0,"step":{"type":"model_output"}}

                event: step.delta
                data: {"event_type":"step.delta","index":0,"delta":{"type":"text","text":"partial"}}

            """.trimIndent() + "\n\n"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
            val done = CountDownLatch(1)
            val chunks = mutableListOf<String>()
            var failure: Throwable? = null
            val listener = object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) { chunks += data }
                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) { failure = t; done.countDown() }
                override fun onClosed(eventSource: EventSource) { done.countDown() }
            }
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
            val source = EventSources.createFactory(client).newEventSource(Request.Builder().url(server.url("/v1/interactions")).build(), GeminiInteractionsStream.listener("provider", listener))
            try {
                assertTrue(done.await(5, TimeUnit.SECONDS))
                assertNotNull(failure)
                assertTrue(chunks.any { it.contains("partial") })
                assertFalse(chunks.contains("[DONE]"))
                assertEquals(1, server.requestCount)
            } finally { source.cancel(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
}
