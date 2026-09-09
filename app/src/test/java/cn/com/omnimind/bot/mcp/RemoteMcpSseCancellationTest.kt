package cn.com.omnimind.bot.mcp

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.BufferedSink
import org.junit.Assert.*
import org.junit.Test

class RemoteMcpSseCancellationTest {
    @Test fun `cancel repeated SSE tool calls on their original endpoint then run another call`() = runBlocking {
        val server = MockWebServer()
        val streams = ConcurrentHashMap<String, Stream>()
        val pending = LinkedBlockingQueue<Pair<String, String>>()
        val cancelled = LinkedBlockingQueue<Pair<String, String>>()
        val sequence = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") {
                    val key = sequence.incrementAndGet().toString()
                    val stream = Stream()
                    streams[key] = stream
                    stream.events.put("event: endpoint\ndata: /messages?connection=$key\n\n")
                    return MockResponse.Builder().setHeader("Content-Type", "text/event-stream").body(stream).build()
                }
                val key = request.url.queryParameter("connection")!!
                val message = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
                val stream = streams.getValue(key)
                val method = message["method"]!!.jsonPrimitive.content
                when (method) {
                    "initialize" -> stream.result(message["id"]!!, """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1"}}""")
                    "tools/call" -> {
                        val id = message["id"]!!.jsonPrimitive.content
                        if (message["params"]!!.jsonObject["arguments"]!!.jsonObject["wait"]?.jsonPrimitive?.boolean == true) {
                            pending.put(key to id)
                        } else {
                            stream.result(message["id"]!!, """{"content":[{"type":"text","text":"next"}]}""")
                            stream.events.put("")
                        }
                    }
                    "notifications/cancelled" -> {
                        assertNull("notification has no request id", message["id"])
                        cancelled.put(key to message["params"]!!.jsonObject["requestId"]!!.jsonPrimitive.content)
                        stream.events.put("")
                    }
                }
                return MockResponse.Builder().code(202)
                    .headersDelay(if (method == "tools/call" && key == "3") 5000 else 0, TimeUnit.MILLISECONDS)
                    .build()
            }
        }
        server.start()
        val config = RemoteMcpServerConfig(
            id = "sse-cancel-${System.nanoTime()}", name = "sse-cancel",
            endpointUrl = server.url("/sse").toString(), transport = RemoteMcpTransport.SSE,
        )
        try {
            repeat(3) { attempt ->
                val call = launch(Dispatchers.Default) { RemoteMcpClient.callTool(config, "echo", mapOf("wait" to true)) }
                val admitted = pending.poll(3, TimeUnit.SECONDS)
                assertNotNull("tool was actually admitted", admitted)
                if (attempt == 0) delay(150) // First waits on SSE; second cancels a delayed POST; third cancels immediately.
                withTimeout(2500) { call.cancelAndJoin() }
                assertEquals("cancel uses original endpoint and id", admitted, cancelled.poll(2, TimeUnit.SECONDS))
                assertEquals("next", RemoteMcpClient.callTool(config, "echo", emptyMap()).summaryText)
            }
            assertEquals("one stream per original call, no cancellation reconnect", 6, sequence.get())
            assertNull(cancelled.poll(200, TimeUnit.MILLISECONDS))
        } finally {
            streams.values.forEach { it.events.offer("") }
            server.close()
            RemoteMcpClient.invalidateSession(config.id)
        }
    }

    private class Stream : MockResponseBody {
        val events = LinkedBlockingQueue<String>()
        // Keep the body open while the fixture waits for a tool or cancellation.
        override val contentLength = Long.MAX_VALUE
        override fun writeTo(sink: BufferedSink) {
            while (true) {
                val event = events.poll(15, TimeUnit.SECONDS) ?: return
                if (event.isEmpty()) return
                sink.writeUtf8(event)
                sink.flush()
            }
        }
        fun result(id: JsonElement, result: String) = events.put("data: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$result}\n\n")
    }
}
