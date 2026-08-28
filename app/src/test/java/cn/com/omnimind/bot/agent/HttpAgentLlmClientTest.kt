package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.account.PlatformModelsUnavailableException
import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionStreamOptions
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.bot.media.PlatformMediaProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
                1_024,
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
    fun `platform multi image request over safe JSON limit is rejected before send`() = runBlocking {
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
                streamRequestOp = { _, _, _, _, _, _, _, _, _, _ ->
                    requestCount += 1
                    dummyEventSource()
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

            val error = runCatching { client.streamTurn(request) }.exceptionOrNull()

            assertTrue(error?.message.orEmpty().contains("请求内容过大"))
            assertEquals(0, requestCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `platform long unicode context is measured as final UTF8 and rejected before send`() = runBlocking {
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
                streamRequestOp = { _, _, _, _, _, _, _, _, _, _ ->
                    requestCount += 1
                    dummyEventSource()
                },
                json = json,
            )
            val longUnicodeContext = "你".repeat(
                (PlatformMediaProtocol.MAX_PLATFORM_JSON_UTF8_BYTES / 3L).toInt() + 1
            )
            val request = ChatCompletionRequest(
                model = "official-text-model",
                messages = listOf(
                    ChatCompletionMessage("user", JsonPrimitive(longUnicodeContext))
                ),
                stream = true,
            )

            val error = runCatching { client.streamTurn(request) }.exceptionOrNull()

            assertTrue(error?.message.orEmpty().contains("15 MiB"))
            assertEquals(0, requestCount)
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
    fun `request variants preserve provider content and keep native tools`() {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(scope = scope, modelOverride = testOverride())
            val request = simpleRequest().copy(
                messages = listOf(
                    cn.com.omnimind.baselib.llm.ChatCompletionMessage(
                        role = "system",
                        content = JsonPrimitive("Choose one tool"),
                    ),
                    cn.com.omnimind.baselib.llm.ChatCompletionMessage(
                        role = "user",
                        content = JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("text"),
                                        "text" to JsonPrimitive("Current screen"),
                                    )
                                )
                            )
                        ),
                    ),
                ),
                tools = listOf(
                    ChatCompletionTool(
                        function = ChatCompletionFunction(name = "click"),
                    ),
                ),
                toolChoice = JsonPrimitive("required"),
                parallelToolCalls = false,
                streamOptions = ChatCompletionStreamOptions(),
            )

            val variants = client.buildRequestVariants(
                request = request,
                routeInfo = routeInfo(
                    requestedModel = "scene.vlm.operation.primary",
                    resolvedModel = "GLM-5.1",
                    protocolType = "openai_compatible",
                    requiresReasoningEcho = false,
                    apiBase = "https://llmapi.paratera.com/v1/chat/completions",
                ),
            )

            assertEquals(listOf("default", "no_stream_options"), variants.map { it.name })
            assertNotNull(variants.first().request.streamOptions)
            assertEquals("click", variants.first().request.tools.single().function.name)
            assertNull(variants.first().request.functions)
            val systemText = variants.first().request.messages.first().content
                ?.jsonPrimitive
                ?.content
            assertEquals("Choose one tool", systemText)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `Paratera GLM Agent route requests exact streaming usage before compatibility fallback`() {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val client = HttpAgentLlmClient(scope = scope, modelOverride = testOverride())
            val request = simpleRequest().copy(
                streamOptions = ChatCompletionStreamOptions(),
            )

            val variants = client.buildRequestVariants(
                request = request,
                routeInfo = routeInfo(
                    requestedModel = "scene.dispatch.model",
                    resolvedModel = "GLM-5.1",
                    protocolType = "openai_compatible",
                    requiresReasoningEcho = false,
                    apiBase = "https://llmapi.paratera.com/v1/chat/completions",
                ),
            )

            assertEquals(
                listOf("default", "no_stream_options"),
                variants.take(2).map { it.name },
            )
            assertEquals(true, variants.first().request.streamOptions?.includeUsage)
            assertNull(variants[1].request.streamOptions)
        } finally {
            scope.cancel()
        }
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

            assertTrue(sentWireName.matches(Regex("^[a-zA-Z0-9_-]+$")))
            assertTrue(sentWireName.length <= 64)
            assertEquals("agent.status", turn.message.toolCalls?.single()?.function?.name)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `closed stream with assistant payload completes without terminal marker`() = runBlocking {
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

            val turn = client.streamTurn(request = simpleRequest())

            assertEquals("还没输出完", turn.message.contentText())
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
        requiresReasoningEcho = requiresReasoningEcho
    )
    @Test
    fun `qwen openai compatible route reclassifies leading content before closing think tag`() = runBlocking {
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

            assertEquals("first reasoning", turn.reasoning)
            assertEquals("final answer", turn.message.contentText())
        } finally {
            scope.cancel()
        }
    }

}
