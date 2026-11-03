package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.Assert.*
import org.junit.Test

class HttpAgentLlmClientRetryTest {
    private val prompt = ChatCompletionRequest(model = "fixture", stream = true,
        messages = listOf(ChatCompletionMessage("user", JsonPrimitive("one logical send"))))

    @Test fun `production defaults recover from two 503s with identical requests and exponential delay`() = scenario {
        val transport = Transport(listOf(failure(503), failure(503), success))
        val delays = mutableListOf<Long>()
        val output = mutableListOf<String>()
        val result = transport.client(it, pause = { ms -> delays += ms })
            .streamTurn(prompt, onContentUpdate = { content -> output += content })
        assertEquals("recovered", result.message.contentText())
        assertEquals(listOf(1000L, 2000L), delays)
        assertEquals(3, transport.bodies.size)
        assertEquals(1, transport.bodies.distinct().size)
        assertEquals(listOf("recovered"), output)
    }

    @Test fun `persistent 503 exhausts exactly three total attempts`() = scenario {
        val transport = Transport(listOf(failure(503)))
        val error = runCatching { transport.client(it).streamTurn(prompt) }.exceptionOrNull()
        assertEquals(503, (error as AgentStreamRequestException).statusCode)
        assertEquals(3, transport.bodies.size)
    }

    @Test fun `pre-output transport reset and in-band 503 recover through the same retry owner`() = scenario { scope ->
        val reset: (EventSourceListener, EventSource) -> Unit = { listener, source ->
            listener.onOpen(source, response(200))
            listener.onFailure(source, java.io.IOException("connection reset"), response(200))
        }
        val inBand: (EventSourceListener, EventSource) -> Unit = { listener, source ->
            listener.onOpen(source, response(200))
            listener.onEvent(source, null, "message",
                """{"error":{"code":"server_error","message":"unavailable"},"status_code":503}""")
        }
        for (failure in listOf(reset, inBand)) {
            val transport = Transport(listOf(failure, success))
            assertEquals("recovered", transport.client(scope).streamTurn(prompt).message.contentText())
            assertEquals(2, transport.bodies.size)
        }
    }

    @Test fun `429 honors Retry-After but quota and invalid requests fail immediately`() = scenario { scope ->
        val transport = Transport(listOf(failure(429, retryAfter = "4"), success))
        val delays = mutableListOf<Long>()
        transport.client(scope, pause = { delays += it }).streamTurn(prompt)
        assertEquals(listOf(4000L), delays)
        for ((status, body) in listOf(
            400 to "{}", 401 to "{}", 403 to "{}", 422 to "{}", 501 to "{}",
            429 to """{"error":{"code":"insufficient_quota"}}""",
            429 to """{"error":{"type":"insufficient_quota"}}""",
            429 to """{"error":{"code":"billing_hard_limit_reached"}}""",
        )) {
            val nonTransient = Transport(listOf(failure(status, body)))
            assertTrue(runCatching { nonTransient.client(scope).streamTurn(prompt) }.isFailure)
            assertEquals("status=$status body=$body", 1, nonTransient.bodies.size)
        }
    }

    @Test fun `long server waiting period is surfaced without an early retry`() = scenario {
        val transport = Transport(listOf(failure(429, retryAfter = "120"), success))
        val error = runCatching { transport.client(it).streamTurn(prompt) }.exceptionOrNull()
        assertEquals(429, (error as AgentStreamRequestException).statusCode)
        assertEquals(1, transport.bodies.size)
    }

    @Test fun `retry after supports seconds HTTP dates and invalid values`() {
        assertEquals(2000L, parseAgentRetryAfterMillis("2", 0))
        assertEquals(5000L, parseAgentRetryAfterMillis("Thu, 01 Jan 1970 00:00:05 GMT", 0))
        assertEquals(0L, parseAgentRetryAfterMillis("Thu, 01 Jan 1970 00:00:05 GMT", 10_000))
        assertNull(parseAgentRetryAfterMillis("-3", 0))
        assertNull(parseAgentRetryAfterMillis("not a date", 0))
        assertEquals(Long.MAX_VALUE, parseAgentRetryAfterMillis("9".repeat(50), 0))
    }

    @Test fun `cancelling backoff prevents another request`() = scenario { scope ->
        val transport = Transport(listOf(failure(503), success))
        val waiting = CompletableDeferred<Unit>()
        val client = transport.client(scope, pause = { waiting.complete(Unit); awaitCancellation() })
        val job = scope.async { client.streamTurn(prompt) }
        withTimeout(2000) { waiting.await() }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, transport.bodies.size)
    }

    @Test fun `partial reasoning text or tool input is never replayed`() = scenario { scope ->
        for (delta in listOf(
            """{"content":"partial answer"}""",
            """{"reasoning_content":"partial reasoning"}""",
            """{"tool_calls":[{"index":0,"id":"call_one","function":{"name":"file_write","arguments":"{"}}]}""",
        )) {
            val transport = Transport(listOf({ listener, source ->
                listener.onOpen(source, response(200))
                listener.onEvent(source, null, "message", """{"choices":[{"delta":$delta}]}""")
                listener.onFailure(source, java.io.IOException("connection reset"), response(200))
            }, success))
            val error = runCatching { transport.client(scope).streamTurn(prompt) }.exceptionOrNull()
            assertTrue(error is AgentStreamRequestException)
            assertTrue((error as AgentStreamRequestException).responseStarted)
            assertEquals(1, transport.bodies.size)
        }
    }

    @Test fun `transport EOF before payload retries but prior logical output disables all retries`() = scenario { scope ->
        val eof = Transport(listOf({ listener, source -> listener.onClosed(source) }, success))
        assertEquals("recovered", eof.client(scope).streamTurn(prompt).message.contentText())
        assertEquals(2, eof.bodies.size)
        val disabled = Transport(listOf(failure(503), success))
        assertTrue(runCatching { disabled.client(scope).streamTurn(prompt, allowTransportRetry = false) }.isFailure)
        assertEquals(1, disabled.bodies.size)
    }

    @Test fun `auth refresh HTTP2 fallback and transient error share the total attempt budget`() = scenario { scope ->
        val transport = Transport(listOf(failure(401), { listener, source ->
            listener.onFailure(source, java.io.IOException("PROTOCOL_ERROR"), null)
        }, failure(503), success))
        var refreshes = 0
        val error = runCatching {
            transport.client(scope, platform = true, refresh = { refreshes++; true }).streamTurn(prompt)
        }.exceptionOrNull()
        assertEquals(503, (error as AgentStreamRequestException).statusCode)
        assertEquals(3, transport.bodies.size)
        assertEquals(listOf(false, false, true), transport.http1)
        assertEquals(1, refreshes)
    }

    @Test fun `a new call on the same client gets a fresh retry budget`() = scenario { scope ->
        val transport = Transport(listOf(success, failure(503), success))
        val client = transport.client(scope)
        assertEquals("recovered", client.streamTurn(prompt).message.contentText())
        assertEquals("recovered", client.streamTurn(prompt).message.contentText())
        assertEquals(3, transport.bodies.size)
    }

    private fun scenario(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { block(scope) } finally { scope.cancel() }
    }

    private val success: (EventSourceListener, EventSource) -> Unit = { listener, source ->
        listener.onOpen(source, response(200))
        listener.onEvent(source, null, "message",
            """{"choices":[{"delta":{"content":"recovered"},"finish_reason":"stop"}]}""")
        listener.onEvent(source, null, "message", "[DONE]")
    }

    private fun failure(status: Int, body: String = "{}", retryAfter: String? = null): (EventSourceListener, EventSource) -> Unit =
        { listener, source -> listener.onFailure(source, null, response(status, body, retryAfter)) }

    private fun response(code: Int, body: String = "", retryAfter: String? = null): Response =
        Response.Builder().request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody())
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }.build()

    private class Transport(private val steps: List<(EventSourceListener, EventSource) -> Unit>) {
        val bodies = mutableListOf<String>()
        val http1 = mutableListOf<Boolean>()
        fun client(scope: CoroutineScope, pause: suspend (Long) -> Unit = {}, platform: Boolean = false,
                   refresh: suspend () -> Boolean = { false }) = HttpAgentLlmClient(
            scope = scope,
            modelOverride = AgentModelOverride(providerProfileId = "fixture", modelId = "fixture", apiBase = "https://example.com", apiKey = "dummy"),
            resolveRouteInfoOp = { _, _, _, _, _, _, _ ->
                HttpController.ChatCompletionRouteInfo(requestedModel = "fixture", resolvedModel = "fixture",
                    apiBase = "https://example.com", providerProfileId = "fixture", providerProfileName = "fixture",
                    routeTag = if (platform) "platform_gateway" else "fixture", bindingApplied = false,
                    bindingProfileMissing = false, overrideApplied = true)
            },
            streamRequestOp = { _, body, listener, _, _, _, _, _, _, forceHttp1 ->
                val step = steps[(bodies.size).coerceAtMost(steps.lastIndex)]
                bodies += body
                http1 += forceHttp1
                val source = object : EventSource {
                    override fun request() = Request.Builder().url("https://example.com").build()
                    override fun cancel() = Unit
                }
                step(listener, source)
                source
            },
            refreshPlatformSessionOp = refresh,
            retryDelayOp = pause,
        )
    }
}
