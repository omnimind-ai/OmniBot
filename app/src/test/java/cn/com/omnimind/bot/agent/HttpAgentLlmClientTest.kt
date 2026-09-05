package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.account.PlatformModelsUnavailableException
import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionStreamOptions
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.bot.media.PlatformMediaProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpAgentLlmClientTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `does not replay partial tool arguments after http2 reset`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        var attempts = 0
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    attempts++
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(source, null, "message",
                        """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"write_file","arguments":"{"}}]}}]}""")
                    listener.onFailure(source, java.io.IOException("stream was reset: CANCEL"), okResponse())
                    source
                },
                maxTransientStreamRetries = 2,
                transientStreamRetryDelayMs = 0,
                json = json,
            )
            val error = runCatching { client.streamTurn(simpleRequest()) }.exceptionOrNull()
            assertTrue(error is AgentStreamRequestException)
            assertEquals(1, attempts)
        } finally { scope.cancel() }
    }

    @Test
    fun `platform image input uses catalog vision model`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val requestedModels = mutableListOf<String>()
        val requestBodies = mutableListOf<String>()
        val resolvedExplicitModels = mutableListOf<String?>()
        val streamedExplicitModels = mutableListOf<String?>()
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = officialTestOverride(),
                resolveRouteInfoOp = { model, _, _, _, explicitModel, protocolType, _ ->
                    resolvedExplicitModels += explicitModel
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = explicitModel ?: model,
                        protocolType = protocolType ?: "openai_compatible",
                        requiresReasoningEcho = false,
                    )
                },
                streamRequestOp = { model, body, listener, _, _, _, explicitModel, _, _, _ ->
                    requestedModels += model
                    requestBodies += body
                    streamedExplicitModels += explicitModel
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    val content = if (requestedModels.size == 1) {
                        "a red status light next to a disabled switch"
                    } else {
                        "done"
                    }
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"$content"},"finish_reason":"stop"}]}"""
                    )
                    listener.onEvent(source, null, "message", "[DONE]")
                    source
                },
                resolvePlatformVisionModelOp = { "official-vision-model" },
                json = json,
            )
            val request = ChatCompletionRequest(
                model = "scene.dispatch.model",
                messages = listOf(
                    ChatCompletionMessage(
                        role = "system",
                        content = JsonPrimitive("large agent history that is unnecessary for this image turn"),
                    ),
                    ChatCompletionMessage(
                        role = "user",
                        content = json.parseToJsonElement(
                            """[{"type":"text","text":"historic image context"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AQ=="}}]"""
                        ),
                    ),
                    ChatCompletionMessage(
                        role = "user",
                        content = json.parseToJsonElement(
                            """[{"type":"text","text":"what is this"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AA=="}}]"""
                        )
                    )
                ),
                reasoningEffort = "medium",
                thinking = cn.com.omnimind.baselib.llm.ChatCompletionThinking(type = "enabled"),
                maxCompletionTokens = 16_384,
                tools = listOf(
                    cn.com.omnimind.baselib.llm.ChatCompletionTool(
                        function = cn.com.omnimind.baselib.llm.ChatCompletionFunction(
                            name = "unnecessary_tool",
                        ),
                    ),
                ),
                toolChoice = JsonPrimitive("auto"),
                parallelToolCalls = true,
                promptCacheKey = "full-agent-context",
                stream = true,
            )

            val turn = client.streamTurn(request)

            assertEquals("done", turn.message.contentText())
            assertEquals(listOf("official-vision-model", "scene.dispatch.model"), requestedModels)
            assertEquals(
                listOf(
                    "official-vision-model",
                    "official-vision-model",
                    "test-model",
                    "test-model",
                ),
                resolvedExplicitModels,
            )
            assertEquals(listOf("official-vision-model", "test-model"), streamedExplicitModels)

            val visionRequest = json.parseToJsonElement(requestBodies[0]).jsonObject
            assertNull(visionRequest["reasoning_effort"])
            assertNull(visionRequest["thinking"])
            assertEquals("true", visionRequest["enable_thinking"]?.jsonPrimitive?.content)
            assertEquals(
                16_384,
                visionRequest["max_completion_tokens"]?.jsonPrimitive?.content?.toInt(),
            )
            assertEquals(1, visionRequest["messages"]?.let { it as JsonArray }?.size)
            assertEquals(0, visionRequest["tools"]?.let { it as JsonArray }?.size)
            assertNull(visionRequest["tool_choice"])
            assertNull(visionRequest["parallel_tool_calls"])
            assertNull(visionRequest["prompt_cache_key"])
            assertEquals(
                "official-vision-model",
                visionRequest["model"]
                    ?.jsonPrimitive
                    ?.content
            )

            val agentRequest = json.parseToJsonElement(requestBodies[1]).jsonObject
            assertEquals("medium", agentRequest["reasoning_effort"]?.jsonPrimitive?.content)
            assertEquals(3, agentRequest["messages"]?.let { it as JsonArray }?.size)
            assertEquals(1, agentRequest["tools"]?.let { it as JsonArray }?.size)
            assertEquals("auto", agentRequest["tool_choice"]?.jsonPrimitive?.content)
            assertEquals("true", agentRequest["parallel_tool_calls"]?.jsonPrimitive?.content)
            assertEquals(
                "full-agent-context",
                agentRequest["prompt_cache_key"]?.jsonPrimitive?.content,
            )
            assertTrue(agentRequest.toString().contains("what is this"))
            assertTrue(agentRequest.toString().contains("historic image context"))
            assertTrue(agentRequest.toString().contains("a red status light"))
            assertFalse(agentRequest.toString().contains("image_url"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `platform image input fails closed when catalog has no vision model`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        var requestCount = 0
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = officialTestOverride(),
                streamRequestOp = { _, _, _, _, _, _, _, _, _, _ ->
                    requestCount += 1
                    dummyEventSource()
                },
                resolvePlatformVisionModelOp = {
                    throw PlatformModelsUnavailableException("no official vision model")
                },
                json = json,
            )
            val request = ChatCompletionRequest(
                model = "scene.dispatch.model",
                messages = listOf(
                    ChatCompletionMessage(
                        role = "user",
                        content = json.parseToJsonElement(
                            """[{"type":"input_image","image_url":"https://example.com/image.jpg"}]"""
                        )
                    )
                ),
                stream = true,
            )

            val error = runCatching { client.streamTurn(request) }.exceptionOrNull()

            assertTrue(error is PlatformModelsUnavailableException)
            assertEquals(0, requestCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `platform multi image request beyond the former host limit reaches the harness`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        var requestCount = 0
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                resolveRouteInfoOp = { model, _, _, _, _, protocolType, _ ->
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = model,
                        protocolType = protocolType ?: "openai_compatible",
                        requiresReasoningEcho = false,
                        providerProfileId = null,
                        routeTag = "platform_gateway",
                    )
                },
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    requestCount += 1
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"accepted"},"finish_reason":"stop"}]}""",
                    )
                    listener.onEvent(source, null, "message", "[DONE]")
                    source
                },
                resolvePlatformVisionModelOp = { "official-vision-model" },
                json = json,
            )
            val imageData = "A".repeat(4 * 1024 * 1024)
            val blocks = buildList {
                add(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive("分析这些图片"))))
                repeat(4) {
                    add(
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("image_url"),
                                "image_url" to JsonObject(
                                    mapOf(
                                        "url" to JsonPrimitive("data:image/jpeg;base64,$imageData")
                                    )
                                ),
                            )
                        )
                    )
                }
            }
            val request = ChatCompletionRequest(
                model = "scene.dispatch.model",
                messages = listOf(ChatCompletionMessage("user", JsonArray(blocks))),
                stream = true,
            )

            val turn = client.streamTurn(request)

            assertEquals("accepted", turn.message.contentText())
            assertEquals(1, requestCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `platform 401 refreshes account session and retries exactly once`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        var requestCount = 0
        var refreshCount = 0
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                resolveRouteInfoOp = { model, _, _, _, explicitModel, protocolType, _ ->
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = explicitModel ?: model,
                        protocolType = protocolType ?: "openai_compatible",
                        requiresReasoningEcho = false,
                        routeTag = "platform_gateway",
                    )
                },
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    requestCount += 1
                    val source = dummyEventSource()
                    if (requestCount == 1) {
                        listener.onFailure(source, null, unauthorizedResponse())
                    } else {
                        listener.onOpen(source, okResponse())
                        listener.onEvent(
                            source,
                            null,
                            "message",
                            """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
                        )
                        listener.onEvent(source, null, "message", "[DONE]")
                    }
                    source
                },
                refreshPlatformSessionOp = {
                    refreshCount += 1
                    true
                },
                json = json,
            )

            val turn = client.streamTurn(request = simpleRequest())

            assertEquals("ok", turn.message.contentText())
            assertEquals(2, requestCount)
            assertEquals(1, refreshCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `platform 401 after visible output preserves the started prompt without refresh or replay`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        var requestCount = 0
        var refreshCount = 0
        val updates = mutableListOf<String>()
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                resolveRouteInfoOp = { model, _, _, _, explicitModel, protocolType, _ ->
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = explicitModel ?: model,
                        protocolType = protocolType ?: "openai_compatible",
                        requiresReasoningEcho = false,
                        routeTag = "platform_gateway",
                    )
                },
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    requestCount += 1
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"已输出的半截回答"}}]}""",
                    )
                    listener.onFailure(source, null, unauthorizedResponse())
                    source
                },
                refreshPlatformSessionOp = {
                    refreshCount += 1
                    true
                },
                json = json,
            )

            val error = runCatching {
                client.streamTurn(
                    request = simpleRequest(),
                    onContentUpdate = { updates += it },
                )
            }.exceptionOrNull()

            assertTrue(error is AgentStreamRequestException)
            assertEquals(401, (error as AgentStreamRequestException).statusCode)
            assertEquals(1, requestCount)
            assertEquals(0, refreshCount)
            assertEquals(listOf("已输出的半截回答"), updates)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `provider failure retains its complete response body`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val responseBody = "provider-detail-".repeat(500)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    val source = dummyEventSource()
                    listener.onFailure(source, null, failedResponse(responseBody))
                    source
                },
                json = json,
            )

            val error = runCatching { client.streamTurn(simpleRequest()) }.exceptionOrNull()

            assertTrue(error is AgentStreamRequestException)
            assertEquals(responseBody, (error as AgentStreamRequestException).responseBody)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `transient stream failure retries the same model turn`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        var attempts = 0
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    attempts += 1
                    val source = dummyEventSource()
                    if (attempts == 1) {
                        listener.onFailure(
                            source,
                            IllegalStateException("Software caused connection abort"),
                            null,
                        )
                    } else {
                        listener.onOpen(source, okResponse())
                        listener.onEvent(
                            source,
                            null,
                            "message",
                            """{"choices":[{"delta":{"content":"完成"},"finish_reason":"stop"}]}""",
                        )
                        listener.onEvent(source, null, "message", "[DONE]")
                    }
                    source
                },
                maxTransientStreamRetries = 2,
                transientStreamRetryDelayMs = 0L,
                json = json,
            )

            val turn = client.streamTurn(request = simpleRequest())

            assertEquals(2, attempts)
            assertEquals("完成", turn.message.contentText())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `does not retry a stream after visible output has started`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        var attempts = 0
        val updates = mutableListOf<String>()
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    attempts += 1
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"半截输出"}}]}""",
                    )
                    listener.onFailure(
                        source,
                        IllegalStateException("Software caused connection abort"),
                        null,
                    )
                    source
                },
                maxTransientStreamRetries = 2,
                transientStreamRetryDelayMs = 0L,
                json = json,
            )

            val error = runCatching {
                client.streamTurn(
                    request = simpleRequest(),
                    onContentUpdate = { updates += it },
                )
            }.exceptionOrNull()

            assertTrue(error is AgentStreamRequestException)
            assertEquals(1, attempts)
            assertEquals(listOf("半截输出"), updates)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `incomplete streamed tool call retries the same model turn once`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        var attempts = 0
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    attempts += 1
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    if (attempts == 1) {
                        listener.onEvent(
                            source,
                            null,
                            "message",
                            """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_bad","type":"function","function":{"arguments":"{\"query\":\"test\"}"}}]},"finish_reason":"tool_calls"}]}""",
                        )
                    } else {
                        listener.onEvent(
                            source,
                            null,
                            "message",
                            """{"choices":[{"delta":{"content":"已恢复"},"finish_reason":"stop"}]}""",
                        )
                    }
                    listener.onEvent(source, null, "message", "[DONE]")
                    source
                },
                maxTransientStreamRetries = 2,
                transientStreamRetryDelayMs = 0L,
                json = json,
            )

            val turn = client.streamTurn(request = simpleRequest())

            assertEquals(2, attempts)
            assertEquals("已恢复", turn.message.contentText())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `repeated incomplete tool calls stop after one same turn retry`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        var attempts = 0
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    attempts += 1
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_bad","type":"function","function":{"arguments":"{\"query\":\"test\"}"}}]},"finish_reason":"tool_calls"}]}""",
                    )
                    listener.onEvent(source, null, "message", "[DONE]")
                    source
                },
                maxTransientStreamRetries = 3,
                transientStreamRetryDelayMs = 0L,
                json = json,
            )

            val error = runCatching { client.streamTurn(request = simpleRequest()) }
                .exceptionOrNull()

            assertTrue(error is AgentIncompleteToolCallException)
            assertEquals(2, attempts)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `non transient client error is not retried`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        var attempts = 0
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    attempts += 1
                    val source = dummyEventSource()
                    listener.onFailure(
                        source,
                        IllegalStateException("unauthorized"),
                        Response.Builder()
                            .request(Request.Builder().url("https://example.com").build())
                            .protocol(Protocol.HTTP_1_1)
                            .code(401)
                            .message("Unauthorized")
                            .body("unauthorized".toResponseBody())
                            .build(),
                    )
                    source
                },
                maxTransientStreamRetries = 2,
                transientStreamRetryDelayMs = 0L,
                json = json,
            )

            val error = runCatching { client.streamTurn(simpleRequest()) }.exceptionOrNull()

            assertEquals(1, attempts)
            assertEquals(401, (error as AgentStreamRequestException).statusCode)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `unsupported thinking parameter ends the original request without retry`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        var attempts = 0
        val requestBodies = mutableListOf<String>()
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                streamRequestOp = { _, body, listener, _, _, _, _, _, _, _ ->
                    attempts += 1
                    requestBodies += body
                    val source = dummyEventSource()
                    listener.onFailure(
                        source,
                        IllegalStateException("bad request"),
                        Response.Builder()
                            .request(Request.Builder().url("https://example.com").build())
                            .protocol(Protocol.HTTP_1_1)
                            .code(400)
                            .message("Bad Request")
                            .body(
                                "{\"error\":{\"message\":\"Validation: Unsupported parameter(s): `enable_thinking`\"}}"
                                    .toResponseBody()
                            )
                            .build(),
                    )
                    source
                },
                maxTransientStreamRetries = 0,
                json = json,
            )

            val error = runCatching { client.streamTurn(
                simpleRequest().copy(
                    enableThinking = true,
                    thinking = cn.com.omnimind.baselib.llm.ChatCompletionThinking(type = "enabled"),
                )
            ) }.exceptionOrNull()

            assertTrue(error is AgentStreamRequestException)
            assertEquals(1, attempts)
            assertTrue(requestBodies.first().contains("enable_thinking"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `unsupported image content ends the original request without stripping the image`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        var attempts = 0
        val requestBodies = mutableListOf<String>()
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                streamRequestOp = { _, body, listener, _, _, _, _, _, _, _ ->
                    attempts += 1
                    requestBodies += body
                    val source = dummyEventSource()
                    listener.onFailure(
                        source,
                        IllegalStateException("bad request"),
                        Response.Builder()
                            .request(Request.Builder().url("https://example.com").build())
                            .protocol(Protocol.HTTP_1_1)
                            .code(400)
                            .message("Bad Request")
                            .body(
                                "{\"error\":{\"message\":\"image_url is not supported\"}}"
                                    .toResponseBody()
                            )
                            .build(),
                    )
                    source
                },
                maxTransientStreamRetries = 0,
                json = json,
            )
            val imageContent = json.parseToJsonElement(
                """[{"type":"text","text":"请分析图片\n已添加到 workspace，可通过以下路径读取：\n- image.png: file:///workspace/image.png"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}]"""
            )

            val error = runCatching { client.streamTurn(
                ChatCompletionRequest(
                    model = "test-model",
                    messages = listOf(ChatCompletionMessage("user", imageContent)),
                    stream = true,
                )
            ) }.exceptionOrNull()

            assertTrue(error is AgentStreamRequestException)
            assertEquals(1, attempts)
            assertTrue(requestBodies.first().contains("image_url"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `provider stream with no completion is terminated before ACP stall watchdog`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                streamRequestOp = { _, _, _, _, _, _, _, _, _, _ -> dummyEventSource() },
                maxTransientStreamRetries = 0,
                streamIdleTimeoutMs = 25L,
                json = json,
            )

            val error = runCatching { client.streamTurn(simpleRequest()) }.exceptionOrNull()

            assertTrue(error is AgentStreamIdleTimeoutException)
            assertTrue(error?.message.orEmpty().contains("chat completion stream idle timeout"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `provider stream that stalls after first event is terminated`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"先输出"}}]}"""
                    )
                    delay(80L)
                    source
                },
                maxTransientStreamRetries = 0,
                streamIdleTimeoutMs = 25L,
                json = json,
            )

            val error = runCatching {
                withTimeout(500L) { client.streamTurn(simpleRequest()) }
            }.exceptionOrNull()

            assertTrue(error is AgentStreamIdleTimeoutException)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `actual transport preserves caller fields without request variants`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val requests = mutableListOf<String>()
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                streamRequestOp = { _, body, listener, _, _, _, _, _, _, _ ->
                    requests += body
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(source, null, "message",
                        """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""")
                    listener.onEvent(source, null, "message", "[DONE]")
                    source
                },
                json = json,
            )
            val request = simpleRequest().copy(
                messages = listOf(ChatCompletionMessage(role = "system", content = JsonPrimitive("Choose one tool"))),
                tools = listOf(ChatCompletionTool(function = ChatCompletionFunction(name = "click"))),
                functions = listOf(ChatCompletionFunction(name = "click")),
                functionCall = JsonPrimitive("auto"),
                toolChoice = JsonPrimitive("required"),
                parallelToolCalls = false,
                streamOptions = ChatCompletionStreamOptions(),
            )
            client.streamTurn(request)
            assertEquals(1, requests.size)
            assertEquals(json.parseToJsonElement(json.encodeToString(ChatCompletionRequest.serializer(), request)),
                json.parseToJsonElement(requests.single()))
        } finally { scope.cancel() }
    }

    @Test
    fun `shared request boundary preserves tool call identities`() {
        val longId = "response_" + "x".repeat(81)
        val request = simpleRequest().copy(
            messages = listOf(
                ChatCompletionMessage(
                    role = "assistant",
                    toolCalls = listOf(
                        AssistantToolCall(
                            id = longId,
                            function = AssistantToolCallFunction(
                                name = "read_file",
                                arguments = "{}",
                            ),
                        ),
                    ),
                ),
                ChatCompletionMessage(
                    role = "tool",
                    toolCallId = longId,
                    content = JsonPrimitive("ok"),
                ),
            ),
        )

        val prepared = request

        assertEquals(longId, prepared.messages[0].toolCalls!!.single().id)
        assertEquals(longId, prepared.messages[1].toolCallId)
    }

    @Test
    fun `shared request boundary preserves explicit auto tool choice`() {
        val request = simpleRequest().copy(
            tools = listOf(
                ChatCompletionTool(
                    function = ChatCompletionFunction(name = "read_file")
                )
            ),
            toolChoice = JsonPrimitive("auto"),
        )

        val prepared = request

        assertEquals("auto", prepared.toolChoice?.jsonPrimitive?.content)
        assertTrue(prepared.tools.isNotEmpty())
    }

    @Test
    fun `official deepseek request preserves explicit required tool choice`() {
        val request = simpleRequest().copy(
            tools = listOf(
                ChatCompletionTool(
                    function = ChatCompletionFunction(name = "read_file")
                )
            ),
            toolChoice = JsonPrimitive("required"),
        )

        val prepared = request

        assertEquals("required", prepared.toolChoice?.jsonPrimitive?.content)
    }

    @Test
    fun `successful non streaming responses body completes a stream turn`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                resolveRouteInfoOp = { model, _, _, _, _, protocolType, _ ->
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = "gpt-5.6-sol",
                        protocolType = protocolType ?: "openai_compatible",
                        requiresReasoningEcho = false,
                        wireApi = OpenAiWireApi.RESPONSES,
                    )
                },
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    val source = dummyEventSource()
                    listener.onFailure(
                        source,
                        IllegalStateException("Expected text/event-stream"),
                        okResponse(
                            """{"object":"response","status":"completed","output":[{"type":"function_call","call_id":"call-1","name":"click","arguments":"{\"summary\":\"打开蓝牙\",\"x\":900,\"y\":300}"}],"usage":{"prompt_tokens":120,"completion_tokens":15,"total_tokens":135}}""",
                        ),
                    )
                    source
                },
                json = json,
            )

            val turn = client.streamTurn(request = simpleRequest())

            assertEquals("gpt-5.6-sol", turn.resolvedModel)
            assertEquals("click", turn.message.toolCalls?.single()?.function?.name)
            assertEquals(120, turn.usage?.promptTokens)
            assertEquals(15, turn.usage?.completionTokens)
            assertEquals(135, turn.usage?.totalTokens)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `responses route restores namespaced ACP tool name before execution`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        var sentWireName = ""
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride().copy(wireApi = OpenAiWireApi.RESPONSES),
                resolveRouteInfoOp = { model, _, _, _, _, protocolType, _ ->
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = "gpt-5.6-sol",
                        protocolType = protocolType ?: "openai_compatible",
                        requiresReasoningEcho = false,
                        wireApi = OpenAiWireApi.RESPONSES,
                    )
                },
                streamRequestOp = { _, body, listener, _, _, _, _, _, _, _ ->
                    val root = json.parseToJsonElement(body).jsonObject
                    sentWireName = root["tools"]!!.let { it as JsonArray }[0]
                        .jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"$sentWireName","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
                    )
                    listener.onEvent(source, null, "message", "[DONE]")
                    source
                },
                json = json,
            )
            val request = simpleRequest().copy(
                tools = listOf(
                    ChatCompletionTool(
                        function = ChatCompletionFunction(name = "agent.status"),
                    ),
                ),
            )

            val turn = client.streamTurn(request)

            val streamedNames = mutableListOf<String>()
            client.streamTurn(request, onToolCallInput = { streamedNames += it.function.name })
            assertEquals(listOf("agent.status"), streamedNames)
            assertTrue(sentWireName.matches(Regex("^[a-zA-Z0-9_-]+$")))
            assertTrue(sentWireName.length <= 64)
            assertEquals("agent.status", turn.message.toolCalls?.single()?.function?.name)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `closed stream with assistant payload fails without terminal marker`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"还没输出完"}}]}"""
                    )
                    listener.onClosed(source)
                    source
                },
                json = json
            )

            val error = runCatching { client.streamTurn(request = simpleRequest()) }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertTrue(error?.message.orEmpty().contains("closed before completion"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `slow stream remains alive until provider closes it`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"先来一段"}}]}"""
                    )
                    kotlinx.coroutines.delay(75L)
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"完成"},"finish_reason":"stop"}]}"""
                    )
                    listener.onEvent(source, null, "message", "[DONE]")
                    source
                },
                json = json
            )

            val turn = client.streamTurn(request = simpleRequest())
            assertEquals("先来一段完成", turn.message.contentText())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `done signal still completes stream normally`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"最终回答"}}]}"""
                    )
                    listener.onEvent(source, null, "message", "[DONE]")
                    source
                },
                json = json
            )

            val turn = client.streamTurn(request = simpleRequest())

            assertEquals("最终回答", turn.message.contentText())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `scene request returns the resolved route model`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                resolveRouteInfoOp = { model, _, _, _, _, protocolType, _ ->
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = "configured-vlm-model",
                        protocolType = protocolType ?: "openai_compatible",
                        requiresReasoningEcho = false,
                    )
                },
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"完成"},"finish_reason":"stop"}]}""",
                    )
                    listener.onEvent(source, null, "message", "[DONE]")
                    source
                },
                json = json,
            )

            val turn = client.streamTurn(
                request = simpleRequest().copy(model = "scene.vlm.operation.primary"),
            )

            assertEquals("configured-vlm-model", turn.resolvedModel)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `resolved route requiring reasoning echo preserves reasoning content even when override is not deepseek`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                resolveRouteInfoOp = { model, _, _, _, _, protocolType, _ ->
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = "deepseek-v4-flash",
                        protocolType = protocolType ?: "deepseek",
                        requiresReasoningEcho = true
                    )
                },
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"reasoning_content":"需要先查工具","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_time","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
                    )
                    listener.onEvent(source, null, "message", "[DONE]")
                    source
                },
                json = json
            )

            val turn = client.streamTurn(request = simpleRequest())

            assertEquals("需要先查工具", turn.reasoning)
            assertEquals("需要先查工具", turn.message.reasoningContent)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `resolved route without reasoning echo keeps plain-answer reasoning off assistant message`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                resolveRouteInfoOp = { model, _, _, _, _, protocolType, _ ->
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = "qwen-plus",
                        protocolType = protocolType ?: "openai_compatible",
                        requiresReasoningEcho = false
                    )
                },
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"reasoning_content":"内部思考","content":"最终回答"},"finish_reason":"stop"}]}"""
                    )
                    listener.onEvent(source, null, "message", "[DONE]")
                    source
                },
                json = json
            )

            val turn = client.streamTurn(request = simpleRequest())

            assertEquals("内部思考", turn.reasoning)
            assertEquals("最终回答", turn.message.contentText())
            assertNull(turn.message.reasoningContent)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `qwen route emits pending reasoning before content`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        val firstContentUpdate = CompletableDeferred<String>()
        val emissions = mutableListOf<String>()
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                resolveRouteInfoOp = { model, _, _, _, _, protocolType, _ ->
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = "qwen3.6-plus",
                        protocolType = protocolType ?: "openai_compatible",
                        requiresReasoningEcho = false
                    )
                },
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"","reasoning_content":"先分析"}}]}"""
                    )
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"","reasoning_content":"更多"}}]}"""
                    )
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"最终"}}]}"""
                    )
                    withTimeout(1_000L) {
                        firstContentUpdate.await()
                    }
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"回答"},"finish_reason":"stop"}]}"""
                    )
                    listener.onEvent(source, null, "message", "[DONE]")
                    source
                },
                json = json
            )

            val turn = client.streamTurn(
                request = simpleRequest(),
                onReasoningUpdate = { reasoning ->
                    emissions += "reasoning:$reasoning"
                },
                onContentUpdate = { content ->
                    emissions += "content:$content"
                    if (!firstContentUpdate.isCompleted) {
                        firstContentUpdate.complete(content)
                    }
                }
            )

            assertEquals("最终", firstContentUpdate.await())
            assertEquals("先分析更多", turn.reasoning)
            assertEquals("最终回答", turn.message.contentText())
            val lastReasoningIndex = emissions.indexOfLast {
                it.startsWith("reasoning:")
            }
            val firstContentIndex = emissions.indexOfFirst {
                it.startsWith("content:")
            }
            assertTrue(lastReasoningIndex >= 0)
            assertTrue(firstContentIndex >= 0)
            assertTrue(lastReasoningIndex < firstContentIndex)
        } finally {
            scope.cancel()
        }
    }

    private fun simpleRequest() = cn.com.omnimind.baselib.llm.ChatCompletionRequest(
        messages = listOf(
            cn.com.omnimind.baselib.llm.ChatCompletionMessage(
                role = "user",
                content = kotlinx.serialization.json.JsonPrimitive("继续")
            )
        ),
        model = "test-model",
        stream = true
    )

    private fun testOverride() = AgentModelOverride(
        providerProfileId = "test",
        modelId = "test-model",
        apiBase = "https://example.com",
        apiKey = "test-key"
    )

    private fun officialTestOverride() = testOverride().copy(
        providerProfileId = "omnibot-official-ai",
        apiKey = "",
    )

    private fun dummyEventSource(): EventSource {
        return object : EventSource {
            override fun request(): Request =
                Request.Builder().url("https://example.com").build()

            override fun cancel() = Unit
        }
    }

    private fun okResponse(body: String? = null): Response {
        return Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body?.toResponseBody())
            .build()
    }

    private fun unauthorizedResponse(): Response {
        return Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()
    }

    private fun failedResponse(body: String): Response {
        return Response.Builder()
            .request(Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(400)
            .message("Bad Request")
            .body(body.toResponseBody())
            .build()
    }

    private fun routeInfo(
        requestedModel: String,
        resolvedModel: String,
        protocolType: String,
        requiresReasoningEcho: Boolean,
        apiBase: String = "https://example.com",
        wireApi: String = OpenAiWireApi.CHAT_COMPLETIONS,
        providerProfileId: String? = "test",
        routeTag: String? = "test",
    ) = HttpController.ChatCompletionRouteInfo(
        requestedModel = requestedModel,
        resolvedModel = resolvedModel,
        apiBase = apiBase,
        providerProfileId = providerProfileId,
        providerProfileName = "Test",
        routeTag = routeTag,
        bindingApplied = false,
        bindingProfileMissing = false,
        overrideApplied = true,
        protocolType = protocolType,
        wireApi = wireApi,
        providerCapabilities = cn.com.omnimind.baselib.llm.DeepSeekProvider
            .requestCapabilities(protocolType, apiBase, resolvedModel)
            .copy(requiresReasoningContentForToolCalls = requiresReasoningEcho)
    )
    @Test
    fun `provider model name does not reinterpret a close tag as hidden reasoning`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(
                scope = scope,
                modelOverride = testOverride(),
                resolveRouteInfoOp = { model, _, _, _, _, protocolType, _ ->
                    routeInfo(
                        requestedModel = model,
                        resolvedModel = "qwen3.6-plus",
                        protocolType = protocolType ?: "openai_compatible",
                        requiresReasoningEcho = false
                    )
                },
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    val source = dummyEventSource()
                    listener.onOpen(source, okResponse())
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"first reasoning</th"}}]}"""
                    )
                    listener.onEvent(
                        source,
                        null,
                        "message",
                        """{"choices":[{"delta":{"content":"ink>final answer"}}]}"""
                    )
                    listener.onEvent(source, null, "message", "[DONE]")
                    source
                },
                json = json
            )

            val turn = client.streamTurn(request = simpleRequest())

            assertEquals("", turn.reasoning)
            assertEquals("first reasoning</think>final answer", turn.message.contentText())
        } finally {
            scope.cancel()
        }
    }

}
