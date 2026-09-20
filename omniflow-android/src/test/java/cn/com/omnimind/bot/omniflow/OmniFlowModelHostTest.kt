package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.ChatCompletionUsage
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OmniFlowModelHostTest {
    @Test
    fun `python model turn always requests the stream consumed by the host`() = runBlocking {
        val host = OmniFlowModelHost(
            modelClient = object : OmniFlowModelClient {
                override suspend fun streamTurn(
                    request: ChatCompletionRequest,
                    onReasoningUpdate: (suspend (String) -> Unit)?,
                ): ChatCompletionTurn {
                    assertEquals(true, request.stream)
                    assertEquals("configured-model", request.model)
                    return ChatCompletionTurn(message = ChatCompletionMessage(role = "assistant"))
                }
            },
        )
        for (stream in listOf(null, false, true)) {
            val request = mutableMapOf<String, Any>(
                "model" to "configured-model",
                "messages" to listOf(mapOf("role" to "user", "content" to "author function")),
            )
            if (stream != null) request["stream"] = stream
            host.modelTurn(mapOf("model" to "configured-model", "request" to request))
        }
    }

    @Test
    fun `json completion accepts object content when provider omits tool call`() = runBlocking {
        val host = OmniFlowModelHost(
            modelClient = object : OmniFlowModelClient {
                override suspend fun streamTurn(
                    request: ChatCompletionRequest,
                    onReasoningUpdate: (suspend (String) -> Unit)?,
                ): ChatCompletionTurn = ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive("```json\n{\"parameters\":[]}\n```")
                    ),
                    finishReason = "stop",
                )
            },
        )

        assertEquals(
            "{\"parameters\":[]}",
            host.completeJson(mapOf("prompt" to "Enhance this Function"))["content"],
        )
    }

    @Test
    fun `json completion requests standard json mode without forced tools`() = runBlocking {
        var receivedRequest: ChatCompletionRequest? = null
        val host = OmniFlowModelHost(
            modelClient = object : OmniFlowModelClient {
                override suspend fun streamTurn(
                    request: ChatCompletionRequest,
                    onReasoningUpdate: (suspend (String) -> Unit)?,
                ): ChatCompletionTurn {
                    receivedRequest = request
                    return ChatCompletionTurn(
                        message = ChatCompletionMessage(
                            role = "assistant",
                            toolCalls = listOf(
                                AssistantToolCall(
                                    id = "call-submit-json",
                                    function = AssistantToolCallFunction(
                                        name = "submit_json",
                                        arguments = """{"parameters":[]}""",
                                    ),
                                ),
                            ),
                        ),
                        finishReason = "tool_calls",
                    )
                }
            },
        )

        val result = host.completeJson(
            payload = mapOf(
                "prompt" to "Enhance this Function",
                "max_tokens" to 321,
                "temperature" to 0.0,
            ),
            modelOverride = OmniVlmPlugin.MODEL_SCENE,
        )

        assertEquals("""{"parameters":[]}""", result["content"])
        assertEquals(OmniVlmPlugin.MODEL_SCENE, receivedRequest?.model)
        assertEquals(321, receivedRequest?.maxCompletionTokens)
        assertEquals(0.0, receivedRequest?.temperature)
        assertEquals(null, receivedRequest?.toolChoice)
        assertEquals(emptyList<Any>(), receivedRequest?.tools)
        assertEquals("json_object", receivedRequest?.responseFormat?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `json completion can use the bound VLM scene through the platform parser`() = runBlocking {
        var receivedRequest: ChatCompletionRequest? = null
        OmniFlow.configure(
            object : OmniFlowPlatform {
                override suspend fun startProcess(
                    context: Context,
                    command: String,
                    environment: Map<String, String>,
                ): Process = error("process_not_expected")

                override suspend fun prepareEnvironment(context: Context, command: String) = Unit

                override suspend fun resolveRuntimeSkill(
                    context: Context,
                    refresh: Boolean,
                ): OmniFlowSkillLocation = OmniFlowSkillLocation(File("."), "/workspace", "test")

                override suspend fun bootstrapRuntimeSkill(
                    context: Context,
                    location: OmniFlowSkillLocation,
                ): OmniFlowSkillLocation = location

                override suspend fun reclaimRuntimeSkill(context: Context) = Unit

                override suspend fun completeJson(request: ChatCompletionRequest): String {
                    receivedRequest = request
                    return """{"parameters":[]}"""
                }
            },
        )

        val result = OmniFlowModelHost.completeJson(
            payload = mapOf(
                "model" to "scene.dispatch.model",
                "prompt" to "Enhance this Function",
                "max_tokens" to 321,
                "temperature" to 0.0,
            ),
            modelOverride = OmniVlmPlugin.MODEL_SCENE,
        )

        assertEquals("""{"parameters":[]}""", result["content"])
        assertEquals(OmniVlmPlugin.MODEL_SCENE, receivedRequest?.model)
        assertEquals(321, receivedRequest?.maxCompletionTokens)
        assertEquals(0.0, receivedRequest?.temperature)
        assertEquals(null, receivedRequest?.toolChoice)
        assertEquals(emptyList<Any>(), receivedRequest?.tools)
        assertEquals("json_object", receivedRequest?.responseFormat?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `run metrics include terminal model turn tokens and latency`() {
        val metrics = ModelRunLogMetrics()
        metrics.recordSuccess(
            result = mapOf(
                "requested_model" to OmniVlmPlugin.MODEL_SCENE,
                "resolved_model" to "gpt-5.6-sol",
                "usage" to mapOf(
                    "prompt_tokens" to 100,
                    "completion_tokens" to 20,
                    "total_tokens" to 120,
                ),
            ),
            durationMs = 900,
        )
        metrics.recordSuccess(
            result = mapOf(
                "requested_model" to OmniVlmPlugin.MODEL_SCENE,
                "resolved_model" to "gpt-5.6-sol",
                "usage" to mapOf(
                    "prompt_tokens" to 80,
                    "completion_tokens" to 10,
                    "total_tokens" to 90,
                ),
            ),
            durationMs = 600,
        )

        val diagnostics = metrics.diagnostics()
        val usage = diagnostics["token_usage"] as Map<*, *>
        assertEquals(2, usage["call_count"])
        assertEquals(180L, usage["prompt_tokens"])
        assertEquals(30L, usage["completion_tokens"])
        assertEquals(210L, usage["total_tokens"])
        assertEquals(1500L, diagnostics["model_duration_ms"])
        assertEquals(2, (diagnostics["token_usage_by_call"] as List<*>).size)
    }

    @Test
    fun `model turn compresses data uri screenshots before provider call`() = runBlocking {
        var compressedInput = ""
        val client = object : OmniFlowModelClient {
            override suspend fun streamTurn(
                request: ChatCompletionRequest,
                onReasoningUpdate: (suspend (String) -> Unit)?,
            ): ChatCompletionTurn {
                val image = request.messages.single().content
                    ?.jsonArray
                    ?.last()
                    ?.jsonObject
                    ?.get("image_url")
                    ?.jsonObject
                    ?.get("url")
                    ?.jsonPrimitive
                    ?.content
                assertEquals("data:image/jpeg;base64,COMPRESSED", image)
                return ChatCompletionTurn(message = ChatCompletionMessage(role = "assistant"))
            }
        }
        val host = OmniFlowModelHost(
            modelClient = client,
            imageCompressor = { value ->
                compressedInput = value
                "data:image/jpeg;base64,COMPRESSED"
            },
        )

        host.modelTurn(
            mapOf(
                "model" to "scene.vlm.operation.primary",
                "request" to mapOf(
                    "model" to "scene.vlm.operation.primary",
                    "messages" to listOf(
                        mapOf(
                            "role" to "user",
                            "content" to listOf(
                                mapOf("type" to "text", "text" to "inspect"),
                                mapOf(
                                    "type" to "image_url",
                                    "image_url" to mapOf(
                                        "url" to "data:image/png;base64,ORIGINAL",
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals("data:image/png;base64,ORIGINAL", compressedInput)
    }

    @Test
    fun `model turn preserves raw pixel schema and native tool call`() = runBlocking {
        val reasoningUpdates = mutableListOf<String>()
        val client = object : OmniFlowModelClient {
            override suspend fun streamTurn(
                request: ChatCompletionRequest,
                onReasoningUpdate: (suspend (String) -> Unit)?,
            ): ChatCompletionTurn {
                assertEquals(OmniVlmPlugin.MODEL_SCENE, request.model)
                assertEquals("required", (request.toolChoice as JsonPrimitive).content)
                assertFalse(requireNotNull(request.parallelToolCalls))
                val click = request.tools.single().function
                assertEquals("click", click.name)
                assertEquals(
                    listOf("summary", "x", "y"),
                    click.parameters["required"]?.jsonArray?.map { it.jsonPrimitive.content },
                )
                val properties = click.parameters["properties"]?.jsonObject.orEmpty()
                assertEquals(0.0, properties["x"]?.jsonObject?.get("minimum")?.jsonPrimitive?.double)
                assertEquals(1080.0, properties["x"]?.jsonObject?.get("maximum")?.jsonPrimitive?.double)
                assertEquals(2400.0, properties["y"]?.jsonObject?.get("maximum")?.jsonPrimitive?.double)
                onReasoningUpdate?.invoke("search is visible")
                return ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        toolCalls = listOf(
                            AssistantToolCall(
                                id = "call-1",
                                function = AssistantToolCallFunction(
                                    name = "click",
                                    arguments = """{"summary":"点击搜索","x":540,"y":1200}""",
                                ),
                            ),
                        ),
                    ),
                    reasoning = "search is visible",
                    finishReason = "tool_calls",
                    resolvedModel = "Qwen3-VL-235B-A22B-Instruct",
                    usage = ChatCompletionUsage(
                        promptTokens = 20,
                        completionTokens = 5,
                        totalTokens = 25,
                        promptTokensDetails = buildJsonObject {
                            put("cached_tokens", JsonPrimitive(7))
                            put("image_tokens", JsonPrimitive(8))
                        },
                        completionTokensDetails = buildJsonObject {
                            put("reasoning_tokens", JsonPrimitive(3))
                            put("text_tokens", JsonPrimitive(2))
                        },
                    ),
                )
            }
        }
        val host = OmniFlowModelHost(
            modelClient = client,
            onReasoningUpdate = reasoningUpdates::add,
        )

        val result = host.modelTurn(
            mapOf(
                "model" to OmniVlmPlugin.MODEL_SCENE,
                "request" to mapOf(
                    "model" to OmniVlmPlugin.MODEL_SCENE,
                    "messages" to listOf(
                        mapOf("role" to "user", "content" to "tap search"),
                    ),
                    "tools" to listOf(
                        mapOf(
                            "type" to "function",
                            "function" to mapOf(
                                "name" to "click",
                                "description" to "Tap one point",
                                "strict" to true,
                                "parameters" to mapOf(
                                    "type" to "object",
                                    "additionalProperties" to false,
                                    "required" to listOf("summary", "x", "y"),
                                    "properties" to mapOf(
                                        "summary" to mapOf("type" to "string"),
                                        "x" to mapOf(
                                            "type" to "number",
                                            "minimum" to 0,
                                            "maximum" to 1080,
                                        ),
                                        "y" to mapOf(
                                            "type" to "number",
                                            "minimum" to 0,
                                            "maximum" to 2400,
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    "tool_choice" to "required",
                    "parallel_tool_calls" to false,
                    "stream" to true,
                ),
            ),
        )

        assertEquals(listOf("search is visible"), reasoningUpdates)
        assertEquals(OmniVlmPlugin.MODEL_SCENE, result["requested_model"])
        assertEquals("Qwen3-VL-235B-A22B-Instruct", result["resolved_model"])
        val toolCall = (result["tool_calls"] as List<*>).single() as Map<*, *>
        val function = toolCall["function"] as Map<*, *>
        assertEquals("click", function["name"])
        assertEquals("""{"summary":"点击搜索","x":540,"y":1200}""", function["arguments"])
        val usage = result["usage"] as Map<*, *>
        assertEquals(25, usage["total_tokens"])
        assertEquals(3, usage["reasoning_tokens"])
        assertEquals(2, usage["text_tokens"])
        assertEquals(8, usage["image_tokens"])
        assertEquals(7, usage["cached_tokens"])
    }

    @Test
    fun `model turn explicitly rejects a repeated stalled action and requests reflection`() =
        runBlocking {
            val requests = mutableListOf<ChatCompletionRequest>()
            var callIndex = 0
            val client = object : OmniFlowModelClient {
                override suspend fun streamTurn(
                    request: ChatCompletionRequest,
                    onReasoningUpdate: (suspend (String) -> Unit)?,
                ): ChatCompletionTurn {
                    requests += request
                    callIndex += 1
                    val arguments = if (callIndex == 1) {
                        """{"summary":"再次点保存","x":540,"y":2070}"""
                    } else {
                        """{"summary":"先选无需餐具","x":720,"y":1570}"""
                    }
                    return ChatCompletionTurn(
                        message = ChatCompletionMessage(
                            role = "assistant",
                            toolCalls = listOf(
                                AssistantToolCall(
                                    id = "call-$callIndex",
                                    function = AssistantToolCallFunction(
                                        name = "click",
                                        arguments = arguments,
                                    ),
                                ),
                            ),
                        ),
                        finishReason = "tool_calls",
                    )
                }
            }
            val host = OmniFlowModelHost(modelClient = client)

            val result = host.modelTurn(
                mapOf(
                    "model" to OmniVlmPlugin.MODEL_SCENE,
                    "state" to mapOf(
                        "display" to mapOf("width" to 1080, "height" to 2376),
                        "extra" to mapOf(
                            "previous_action_error" to
                                "action_completed_without_state_change",
                            "previous_action" to mapOf(
                                "tool" to "click",
                                "args" to mapOf(
                                    "x" to 500.0,
                                    "y" to 871.6329966329967,
                                ),
                            ),
                        ),
                    ),
                    "request" to mapOf(
                        "model" to OmniVlmPlugin.MODEL_SCENE,
                        "messages" to listOf(
                            mapOf("role" to "user", "content" to "inspect dialog"),
                        ),
                        "tools" to emptyList<Map<String, Any?>>(),
                        "tool_choice" to "required",
                        "parallel_tool_calls" to false,
                        "stream" to true,
                    ),
                ),
            )

            assertEquals(2, requests.size)
            val reflection = requests[1].messages.last().content?.jsonPrimitive?.content.orEmpty()
            assertEquals(true, reflection.contains("REFLECTION REQUIRED"))
            assertEquals(true, reflection.contains("explicitly rejected"))
            assertEquals(true, reflection.contains("Do not return this same control"))
            assertEquals(1, result["rejected_stalled_actions"])
            val toolCall = (result["tool_calls"] as List<*>).single() as Map<*, *>
            val function = toolCall["function"] as Map<*, *>
            assertEquals("""{"summary":"先选无需餐具","x":720,"y":1570}""", function["arguments"])
            assertEquals(2, (result["usage"] as Map<*, *>)["model_calls"])
        }

    @Test
    fun `json completion crosses only the configured platform boundary`() = runBlocking {
        var receivedRequest: ChatCompletionRequest? = null
        OmniFlow.configure(
            object : OmniFlowPlatform {
                override suspend fun startProcess(
                    context: Context,
                    command: String,
                    environment: Map<String, String>,
                ): Process = error("process_not_expected")

                override suspend fun prepareEnvironment(context: Context, command: String) = Unit

                override suspend fun resolveRuntimeSkill(
                    context: Context,
                    refresh: Boolean,
                ): OmniFlowSkillLocation = OmniFlowSkillLocation(File("."), "/workspace", "test")

                override suspend fun bootstrapRuntimeSkill(
                    context: Context,
                    location: OmniFlowSkillLocation,
                ): OmniFlowSkillLocation = location

                override suspend fun reclaimRuntimeSkill(context: Context) = Unit

                override suspend fun completeJson(request: ChatCompletionRequest): String {
                    receivedRequest = request
                    return """{"function_id":"none"}"""
                }
            },
        )

        val result = OmniFlowModelHost.completeJson(
            mapOf(
                "model" to "scene.dispatch.model",
                "prompt" to "Select a Function",
                "max_tokens" to 321,
                "temperature" to 0.0,
            ),
        )

        assertEquals("""{"function_id":"none"}""", result["content"])
        assertEquals("scene.dispatch.model", receivedRequest?.model)
        assertEquals(321, receivedRequest?.maxCompletionTokens)
        assertEquals(0.0, receivedRequest?.temperature)
        assertEquals(null, receivedRequest?.toolChoice)
        assertEquals(emptyList<Any>(), receivedRequest?.tools)
        assertEquals("json_object", receivedRequest?.responseFormat?.get("type")?.jsonPrimitive?.content)
    }
}
