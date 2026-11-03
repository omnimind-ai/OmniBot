package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.database.TokenUsageRecord
import okhttp3.OkHttpClient
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HttpControllerTokenUsageTest {
    private val source = object : EventSource {
        override fun request(): Request = Request.Builder().url("https://example.test/").build()
        override fun cancel() = Unit
    }

    private fun listener(
        records: MutableList<TokenUsageRecord>,
        protocol: String = "openai_compatible",
        requireResponsesCompleted: Boolean = false,
        delegate: EventSourceListener = object : EventSourceListener() {},
        recordUsage: ((TokenUsageRecord) -> Unit)? = null,
    ): EventSourceListener {
        val seedType = HttpController::class.java.declaredClasses.single { it.simpleName == "AiRequestLogSeed" }
        val seed = seedType.declaredConstructors.single { it.parameterCount == 8 }
            .apply { isAccessible = true }
            .newInstance("fixture", "fixture-model", protocol, "https://example.test/", "POST", true, "{}", 42L)
        val sink: (TokenUsageRecord) -> Unit = recordUsage ?: { records.add(it) }
        return HttpController::class.java.getDeclaredMethod(
            "createLoggingEventListener", String::class.java, EventSourceListener::class.java,
            seedType, Boolean::class.javaPrimitiveType, Function1::class.java,
        ).apply { isAccessible = true }
            .invoke(HttpController, "fixture", delegate, seed, requireResponsesCompleted, sink) as EventSourceListener
    }

    @Test
    fun `real SSE completion cancellation preserves usage once`() {
        val records = mutableListOf<TokenUsageRecord>()
        val closed = CountDownLatch(1)
        val delegate = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") eventSource.cancel()
            }
            override fun onClosed(eventSource: EventSource) { closed.countDown() }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) { closed.countDown() }
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: {\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":80}}\n\n" +
                    "data: [DONE]\n\n"
            ))
            val client = OkHttpClient()
            try {
                EventSources.createFactory(client).newEventSource(
                    Request.Builder().url(server.url("/chat/completions")).build(), listener(records, delegate = delegate)
                )
                assertTrue("SSE terminal callback", closed.await(5, TimeUnit.SECONDS))
                assertEquals(1, records.size)
                assertEquals(1200, records.single().promptTokens)
                assertEquals(80, records.single().completionTokens)
                assertEquals(42L, records.single().conversationId)
            } finally {
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test
    fun `reported usage survives failure and repeated terminal callbacks without double counting`() {
        val records = mutableListOf<TokenUsageRecord>()
        val listener = listener(records)
        listener.onEvent(source, null, null, """{"usage":{"prompt_tokens":50,"completion_tokens":10}}""")
        listener.onFailure(source, IOException("Canceled"), null)
        listener.onClosed(source)
        listener.onFailure(source, IOException("Canceled"), null)
        assertEquals(1, records.size)
        assertEquals(10, records.single().completionTokens)
    }

    @Test
    fun `usage is captured before a delegate can cancel synchronously`() {
        val records = mutableListOf<TokenUsageRecord>()
        lateinit var listener: EventSourceListener
        listener = listener(records, delegate = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                listener.onFailure(eventSource, IOException("Canceled"), null)
            }
        })
        listener.onEvent(source, null, null, """{"usage":{"input_tokens":25,"output_tokens":6}}""")
        assertEquals(1, records.size)
        assertEquals(25, records.single().promptTokens)
        assertEquals(6, records.single().completionTokens)
    }

    @Test
    fun `Responses usage is retained after diagnostic transcript limit`() {
        val records = mutableListOf<TokenUsageRecord>()
        val listener = listener(records, requireResponsesCompleted = true)
        // Fill almost all retained diagnostic space, then send a terminal event
        // too large to fit. Accounting must still read the event's usage.
        listener.onEvent(source, null, "response.created", " ".repeat(1024 * 1024 - 8))
        listener.onEvent(source, null, "response.completed", """{
            "type":"response.completed","response":{"status":"completed","usage":{
                "input_tokens":600,"output_tokens":70,"output_tokens_details":{"reasoning_tokens":40},
                "input_tokens_details":{"cached_tokens":200}
            }}
        }""")
        listener.onFailure(source, IOException("Canceled"), null)
        assertEquals(1, records.size)
        assertEquals(600, records.single().promptTokens)
        assertEquals(70, records.single().completionTokens)
        assertEquals(40, records.single().reasoningTokens)
        assertEquals(200, records.single().cachedTokens)
    }

    @Test
    fun `Anthropic partial usage retains input cache and final output counters`() {
        val records = mutableListOf<TokenUsageRecord>()
        val listener = listener(records, protocol = "anthropic")
        listener.onEvent(source, null, "message_start", """{"message":{"usage":{
            "input_tokens":100,"cache_read_input_tokens":800,"cache_creation_input_tokens":50,"output_tokens":1
        }}}""")
        listener.onEvent(source, null, "message_delta", """{"usage":{"output_tokens":60}}""")
        listener.onFailure(source, IOException("Canceled"), null)
        assertEquals(1, records.size)
        assertEquals(950, records.single().promptTokens)
        assertEquals(60, records.single().completionTokens)
        assertEquals(800, records.single().cachedTokens)
        assertEquals(50, records.single().cacheCreationTokens)
    }

    @Test
    fun `failed request without reported usage creates no synthetic consumption`() {
        val records = mutableListOf<TokenUsageRecord>()
        val listener = listener(records)
        listener.onEvent(source, null, null, """{"choices":[{"delta":{"content":"partial"}}]}""")
        listener.onFailure(source, IOException("connection reset"), null)
        assertTrue(records.isEmpty())
    }

    @Test
    fun `successful JSON fallback captures usage before consumer closes body`() {
        val records = mutableListOf<TokenUsageRecord>()
        val received = AtomicReference<String>()
        val closed = CountDownLatch(1)
        val body = """{"object":"response","status":"completed","output":[],"usage":{"input_tokens":200,"output_tokens":30}}"""
        val delegate = object : EventSourceListener() {
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                try { received.set(response?.body?.string()) } finally { closed.countDown() }
            }
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
            val client = OkHttpClient()
            try {
                EventSources.createFactory(client).newEventSource(
                    Request.Builder().url(server.url("/responses")).build(), listener(records, delegate = delegate)
                )
                assertTrue("JSON fallback callback", closed.await(5, TimeUnit.SECONDS))
                assertEquals(body, received.get())
                assertEquals(1, records.size)
                assertEquals(200, records.single().promptTokens)
                assertEquals(30, records.single().completionTokens)
            } finally {
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test
    fun `live SSE and successful OAuth bodies are never read for accounting`() {
        listOf("text/event-stream" to false, "application/json" to true).forEach { (contentType, oauth) ->
            var reads = 0
            var delegated = false
            val body = object : ResponseBody() {
                override fun contentType(): MediaType = contentType.toMediaType()
                override fun contentLength(): Long = -1
                override fun source(): BufferedSource {
                    reads++
                    return Buffer().writeUtf8("""{"usage":{"input_tokens":100,"output_tokens":20}}""")
                }
            }
            val records = mutableListOf<TokenUsageRecord>()
            val listener = listener(records, requireResponsesCompleted = oauth, delegate = object : EventSourceListener() {
                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) { delegated = true }
            })
            listener.onFailure(source, IOException("SSE transport failed"), Response.Builder()
                .request(source.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build())
            assertEquals("$contentType OAuth=$oauth", 0, reads)
            assertTrue(delegated)
            assertTrue(records.isEmpty())
        }
    }

    @Test
    fun `accounting exceptions do not block terminal delegate callbacks`() {
        var delegated = 0
        val listener = listener(mutableListOf(), recordUsage = { error("fixture storage failure") },
            delegate = object : EventSourceListener() {
                override fun onClosed(eventSource: EventSource) { delegated++ }
                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) { delegated++ }
            })
        listener.onEvent(source, null, null, "not-json")
        listener.onEvent(source, null, null, """{"usage":{"input_tokens":100,"output_tokens":20}}""")
        listener.onFailure(source, IOException("Canceled"), null)
        listener.onClosed(source)
        assertEquals(2, delegated)
    }

}
