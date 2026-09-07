package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.sse.EventSources
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Real OkHttp SSE callbacks, rather than a synthetic EventSource callback sequence. */
class HttpAgentSseLifecycleTest {
    private fun transport(): OkHttpClient = HttpController::class.java
        .getDeclaredMethod("openAIStreamClient", Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(HttpController, false) as OkHttpClient

    private fun client(scope: CoroutineScope, server: MockWebServer) = HttpAgentLlmClient(
        scope = scope,
        modelOverride = AgentModelOverride(providerProfileId = "fixture", modelId = "test-model",
            apiBase = server.url("/").toString(), apiKey = "fixture-key"),
        resolveRouteInfoOp = { model, _, _, _, _, _, _ ->
            HttpController.ChatCompletionRouteInfo(
                requestedModel = model, resolvedModel = "test-model", apiBase = server.url("/").toString(),
                providerProfileId = "fixture", providerProfileName = "Fixture", routeTag = "fixture",
                bindingApplied = false, bindingProfileMissing = false, overrideApplied = true,
                protocolType = "openai_compatible",
            )
        },
        streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
            EventSources.createFactory(transport()).newEventSource(
                Request.Builder().url(server.url("/v1/chat/completions")).build(), listener)
        },
    )

    private fun request() = ChatCompletionRequest(model = "test-model", stream = true,
        messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("hello"))))

    private fun reply() = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")

    @Test
    fun `two completed streams release transport and deliver one result each`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            MockWebServer().use { server ->
                repeat(2) { server.enqueue(reply()) }
                val client = client(scope, server)
                withTimeout(5_000) {
                    repeat(2) { assertEquals("OK", client.streamTurn(request()).message.contentText()) }
                    while (transport().dispatcher.runningCallsCount() != 0) delay(10)
                }
                assertEquals(2, server.requestCount)
            }
        } finally { scope.cancel() }
    }

    @Test
    fun `cancelling a waiting stream releases the call and permits the next request`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            MockWebServer().use { server ->
                server.enqueue(reply().setHeadersDelay(1, TimeUnit.SECONDS))
                server.enqueue(reply())
                val client = client(scope, server)
                withTimeout(5_000) {
                    val pending = async { client.streamTurn(request()) }
                    withContext(Dispatchers.IO) { checkNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
                    pending.cancelAndJoin()
                    assertEquals("OK", client.streamTurn(request()).message.contentText())
                    while (transport().dispatcher.runningCallsCount() != 0) delay(10)
                }
                assertEquals(2, server.requestCount)
            }
        } finally { scope.cancel() }
    }
}
