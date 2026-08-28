package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.contentText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpControllerResponsesTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `chat completions request serializes prompt cache key`() {
        val payload = json.encodeToString(
            ChatCompletionRequest(
                model = "gpt-4.1",
                messages = listOf(
                    ChatCompletionMessage(
                        role = "user",
                        content = kotlinx.serialization.json.JsonPrimitive("hello")
                    )
                ),
                promptCacheKey = "omnibot:v1:0123456789abcdef0123:conversation:42"
            )
        )
        val root = json.parseToJsonElement(payload).jsonObject

        assertEquals(
            "omnibot:v1:0123456789abcdef0123:conversation:42",
            root["prompt_cache_key"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `chat completions wire body excludes provider private state`() {
        val method = HttpController::class.java.getDeclaredMethod(
            "stripAnthropicOnlyFieldsForOpenAiCompatible",
            String::class.java
        )
        method.isAccessible = true
        val payload = method.invoke(
            HttpController,
            """
                {
                  "model": "gpt-4.1",
                  "messages": [
                    {"role":"user","content":"inspect"},
                    {
                      "role":"assistant",
                      "content":"running",
                      "tool_calls":[{"id":"call_1","type":"function","function":{"name":"shell","arguments":"{}"}}],
                      "_omnibot_protocol_state":{"anthropic":{"source_model":"claude","content_blocks":[{"type":"thinking","thinking":"private","signature":"opaque"}]}}
                    },
                    {
                      "role":"tool",
                      "tool_call_id":"call_1",
                      "content":"done",
                      "_omnibot_protocol_state":{"anthropic":{"tool_result_is_error":false}}
                    }
                  ]
                }
            """.trimIndent()
        ) as String

        assertFalse(payload.contains("_omnibot_protocol_state"))
        val messages = json.parseToJsonElement(payload).jsonObject["messages"]!!.jsonArray
        assertEquals("call_1", messages[2].jsonObject["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals("running", messages[1].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `responses request body maps chat history to instructions input and function call output`() {
        val method = HttpController::class.java.getDeclaredMethod(
            "buildOpenAIResponsesRequestBody",
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        val payload = method.invoke(
            HttpController,
            """
                {
                  "model": "gpt-4.1",
                  "stream": true,
                  "prompt_cache_key": "omnibot:v1:test:conversation:42",
                  "max_completion_tokens": 256,
                  "tool_choice": "required",
                  "tools": [
                    {
                      "type": "function",
                      "function": {
                        "name": "get_weather",
                        "description": "Get weather",
                        "parameters": {"type":"object","properties":{"city":{"type":"string"}}}
                      }
                    }
                  ],
                  "messages": [
                    {"role": "system", "content": "You are helpful."},
                    {"role": "user", "content": "Weather in Shanghai?"},
                    {
                      "role": "assistant",
                      "content": "It is sunny.",
                      "_omnibot_protocol_state": {
                        "anthropic": {
                          "source_model": "claude",
                          "content_blocks": [
                            {"type":"thinking","thinking":"private","signature":"opaque"}
                          ]
                        }
                      },
                      "tool_calls": [
                        {
                          "id": "call_1",
                          "type": "function",
                          "function": {"name": "get_weather", "arguments": "{\"city\":\"Shanghai\"}"}
                        }
                      ]
                    },
                    {"role": "tool", "tool_call_id": "call_1", "content": "{\"temp\":28}"}
                  ]
                }
            """.trimIndent(),
            "gpt-4.1-mini"
        ) as String

        val root = json.parseToJsonElement(payload).jsonObject
        assertFalse(payload.contains("_omnibot_protocol_state"))
        assertEquals("gpt-4.1-mini", root["model"]?.jsonPrimitive?.content)
        assertEquals(
            "omnibot:v1:test:conversation:42",
            root["prompt_cache_key"]?.jsonPrimitive?.content
        )
        assertEquals("You are helpful.", root["instructions"]?.jsonPrimitive?.content)
        assertEquals("required", root["tool_choice"]?.jsonPrimitive?.content)
        assertEquals("256", root["max_output_tokens"]?.jsonPrimitive?.content)

        val input = root["input"]!!.jsonArray
        assertEquals("user", input[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals(
            "Weather in Shanghai?",
            input[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content
        )
        assertEquals(
            "input_text",
            input[0].jsonObject["content"]!!.jsonArray[0].jsonObject["type"]?.jsonPrimitive?.content
        )
        assertEquals("assistant", input[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals(
            "output_text",
            input[1].jsonObject["content"]!!.jsonArray[0].jsonObject["type"]?.jsonPrimitive?.content
        )
        assertEquals(
            "It is sunny.",
            input[1].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content
        )
        assertEquals("function_call", input[2].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("call_1", input[2].jsonObject["call_id"]?.jsonPrimitive?.content)
        assertEquals("function_call_output", input[3].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("{\"temp\":28}", input[3].jsonObject["output"]?.jsonPrimitive?.content)

        val tools = root["tools"]!!.jsonArray
        assertEquals("function", tools[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("get_weather", tools[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `responses request backfills missing function call output before sending history`() {
        val method = HttpController::class.java.getDeclaredMethod(
            "buildOpenAIResponsesRequestBody",
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        val payload = method.invoke(
            HttpController,
            """
                {
                  "model": "gpt-4.1",
                  "messages": [
                    {"role": "user", "content": "检查项目"},
                    {
                      "role": "assistant",
                      "tool_calls": [
                        {
                          "id": "call_ptpAmLkngkIT9h4H4fb1D2mj",
                          "type": "function",
                          "function": {"name": "file_list", "arguments": "{}"}
                        }
                      ]
                    },
                    {"role": "user", "content": "继续"}
                  ]
                }
            """.trimIndent(),
            "gpt-4.1",
        ) as String

        val input = json.parseToJsonElement(payload).jsonObject["input"]!!.jsonArray
        val functionCallIndex = input.indexOfFirst {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        val functionOutputIndex = input.indexOfFirst {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }

        assertTrue(functionCallIndex >= 0)
        assertTrue(functionOutputIndex > functionCallIndex)
        assertEquals(
            "call_ptpAmLkngkIT9h4H4fb1D2mj",
            input[functionOutputIndex].jsonObject["call_id"]?.jsonPrimitive?.content,
        )
        assertTrue(
            input[functionOutputIndex].jsonObject["output"]?.jsonPrimitive?.content
                ?.contains("missing", ignoreCase = true) == true
        )
    }

    @Test
    fun `responses request normalizes ACP tool names consistently across history catalog and choice`() {
        val method = HttpController::class.java.getDeclaredMethod(
            "buildOpenAIResponsesRequestBody",
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        val payload = method.invoke(
            HttpController,
            """
                {
                  "model": "gpt-5.6-sol",
                  "tool_choice": {
                    "type": "function",
                    "function": {"name": "agent.status"}
                  },
                  "tools": [
                    {
                      "type": "function",
                      "function": {
                        "name": "agent.status",
                        "description": "Read agent status",
                        "parameters": {"type":"object","properties":{}}
                      }
                    }
                  ],
                  "messages": [
                    {"role": "user", "content": "first"},
                    {"role": "assistant", "content": "second"},
                    {"role": "user", "content": "third"},
                    {
                      "role": "assistant",
                      "content": "fourth",
                      "tool_calls": [
                        {
                          "id": "call_legacy",
                          "type": "function",
                          "function": {"name": "agent.status", "arguments": "{}"}
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent(),
            "gpt-5.6-sol",
        ) as String

        val root = json.parseToJsonElement(payload).jsonObject
        val historyName = root["input"]!!.jsonArray[4]
            .jsonObject["name"]!!.jsonPrimitive.content
        val catalogName = root["tools"]!!.jsonArray[0]
            .jsonObject["name"]!!.jsonPrimitive.content
        val choiceName = root["tool_choice"]!!.jsonObject["name"]!!.jsonPrimitive.content

        assertTrue(historyName.matches(Regex("^[a-zA-Z0-9_-]+$")))
        assertEquals(historyName, catalogName)
        assertEquals(historyName, choiceName)
    }

    @Test
    fun `responses request uses output text for assistant history`() {
        val method = HttpController::class.java.getDeclaredMethod(
            "buildOpenAIResponsesRequestBody",
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        val payload = method.invoke(
            HttpController,
            """
                {
                  "model": "gpt-5.6-sol",
                  "messages": [
                    {"role": "user", "content": "帮我点一杯咖啡"},
                    {"role": "assistant", "content": "我先搜索外卖应用。"},
                    {"role": "user", "content": "继续"}
                  ]
                }
            """.trimIndent(),
            "gpt-5.6-sol",
        ) as String

        val input = json.parseToJsonElement(payload).jsonObject["input"]!!.jsonArray
        assertEquals(
            "input_text",
            input[0].jsonObject["content"]!!.jsonArray[0]
                .jsonObject["type"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "output_text",
            input[1].jsonObject["content"]!!.jsonArray[0]
                .jsonObject["type"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "input_text",
            input[2].jsonObject["content"]!!.jsonArray[0]
                .jsonObject["type"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `responses request preserves online VLM latency controls`() {
        val method = HttpController::class.java.getDeclaredMethod(
            "buildOpenAIResponsesRequestBody",
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        val payload = method.invoke(
            HttpController,
            """
                {
                  "model": "scene.vlm.operation.primary",
                  "stream": true,
                  "max_completion_tokens": 512,
                  "reasoning_effort": "none",
                  "enable_thinking": false,
                  "parallel_tool_calls": false,
                  "messages": [{"role": "user", "content": "Open Bluetooth"}]
                }
            """.trimIndent(),
            "gpt-5.6-sol"
        ) as String

        val root = json.parseToJsonElement(payload).jsonObject
        assertTrue(root["stream"]?.jsonPrimitive?.content?.toBoolean() == true)
        assertEquals("512", root["max_output_tokens"]?.jsonPrimitive?.content)
        assertEquals(
            "none",
            root["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content,
        )
        assertEquals(false, root["parallel_tool_calls"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `responses stream adapter converts output text events into chat chunks`() {
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapResponsesListener(
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    chunks += data
                }
            }
        )

        val source = dummyEventSource()
        wrapped.onEvent(
            source,
            null,
            "response.output_text.delta",
            """{"type":"response.output_text.delta","delta":"Hello"}"""
        )
        wrapped.onEvent(
            source,
            null,
            "response.completed",
            """{"type":"response.completed","response":{"usage":{"input_tokens":4,"output_tokens":3,"total_tokens":7,"input_tokens_details":{"cached_tokens":2},"output_tokens_details":{"reasoning_tokens":2,"text_tokens":1}}}}"""
        )

        val accumulator = AgentLlmStreamAccumulator(json)
        chunks.forEach(accumulator::consume)
        val turn = accumulator.buildTurn()

        assertEquals("Hello", turn.message.contentText())
        assertEquals(4, turn.usage?.promptTokens)
        assertEquals(3, turn.usage?.completionTokens)
        assertEquals(
            "2",
            turn.usage?.promptTokensDetails?.jsonObject
                ?.get("cached_tokens")?.jsonPrimitive?.content,
        )
        assertEquals(
            "2",
            turn.usage?.completionTokensDetails?.jsonObject
                ?.get("reasoning_tokens")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `responses stream adapter converts function call events into tool calls`() {
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapResponsesListener(
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    chunks += data
                }
            }
        )

        val source = dummyEventSource()
        wrapped.onEvent(
            source,
            null,
            "response.output_item.added",
            """{"type":"response.output_item.added","item":{"type":"function_call","call_id":"call_7","name":"get_weather","arguments":"{\"city\":\"Shanghai\"}"}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "response.completed",
            """{"type":"response.completed","response":{"usage":{"prompt_tokens":4,"completion_tokens":1,"total_tokens":5}}}"""
        )

        val accumulator = AgentLlmStreamAccumulator(json)
        chunks.forEach(accumulator::consume)
        val turn = accumulator.buildTurn()

        assertEquals(1, turn.message.toolCalls?.size)
        assertEquals("get_weather", turn.message.toolCalls?.first()?.function?.name)
        assertTrue(turn.message.toolCalls?.first()?.function?.arguments?.contains("Shanghai") == true)
        assertEquals("tool_calls", turn.finishReason)
    }

    @Test
    fun `responses stream adapter keeps item_id argument deltas on original tool call`() {
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapResponsesListener(
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    chunks += data
                }
            }
        )

        val source = dummyEventSource()
        wrapped.onEvent(
            source,
            null,
            "response.output_item.added",
            """{"type":"response.output_item.added","item":{"type":"function_call","id":"msg_tool_1","call_id":"call_7","name":"get_weather","arguments":"","status":"in_progress"}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "response.function_call_arguments.delta",
            """{"type":"response.function_call_arguments.delta","item_id":"msg_tool_1","delta":"{\"city\":\"Shanghai\"}","output_index":1}"""
        )
        wrapped.onEvent(
            source,
            null,
            "response.function_call_arguments.done",
            """{"type":"response.function_call_arguments.done","item_id":"msg_tool_1","name":"get_weather","arguments":"{\"city\":\"Shanghai\"}","output_index":1}"""
        )
        wrapped.onEvent(
            source,
            null,
            "response.completed",
            """{"type":"response.completed","response":{"usage":{"prompt_tokens":4,"completion_tokens":1,"total_tokens":5}}}"""
        )

        val accumulator = AgentLlmStreamAccumulator(json)
        chunks.forEach(accumulator::consume)
        val turn = accumulator.buildTurn()

        assertEquals(1, turn.message.toolCalls?.size)
        assertEquals("call_7", turn.message.toolCalls?.first()?.id)
        assertEquals("get_weather", turn.message.toolCalls?.first()?.function?.name)
        assertEquals("""{"city":"Shanghai"}""", turn.message.toolCalls?.first()?.function?.arguments)
        assertEquals("tool_calls", turn.finishReason)
    }

    @Test
    fun `responses stream adapter merges argument events that arrive before call metadata`() {
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapResponsesListener(
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    chunks += data
                }
            },
        )

        val source = dummyEventSource()
        wrapped.onEvent(
            source,
            null,
            "response.function_call_arguments.delta",
            """{"type":"response.function_call_arguments.delta","item_id":"msg_tool_1","delta":"{\"city\":\"Shanghai\"}","output_index":1}""",
        )
        wrapped.onEvent(
            source,
            null,
            "response.output_item.added",
            """{"type":"response.output_item.added","item":{"type":"function_call","id":"msg_tool_1","call_id":"call_7","name":"get_weather","arguments":"","status":"in_progress"}}""",
        )
        wrapped.onEvent(
            source,
            null,
            "response.function_call_arguments.done",
            """{"type":"response.function_call_arguments.done","item_id":"msg_tool_1","name":"get_weather","arguments":"{\"city\":\"Shanghai\"}","output_index":1}""",
        )
        wrapped.onEvent(
            source,
            null,
            "response.completed",
            """{"type":"response.completed","response":{"usage":{"input_tokens":4,"output_tokens":1,"total_tokens":5}}}""",
        )

        val accumulator = AgentLlmStreamAccumulator(json)
        chunks.forEach(accumulator::consume)
        val turn = accumulator.buildTurn()

        assertEquals(1, turn.message.toolCalls?.size)
        assertEquals("call_7", turn.message.toolCalls?.single()?.id)
        assertEquals("get_weather", turn.message.toolCalls?.single()?.function?.name)
        assertEquals(
            """{"city":"Shanghai"}""",
            turn.message.toolCalls?.single()?.function?.arguments,
        )
    }

    @Test
    fun `responses stream adapter does not append completed arguments after fragmented deltas`() {
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapResponsesListener(
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    chunks += data
                }
            },
        )

        val source = dummyEventSource()
        wrapped.onEvent(
            source,
            null,
            "response.output_item.added",
            """{"type":"response.output_item.added","item":{"type":"function_call","id":"msg_submit","call_id":"call_submit","name":"submit_json","arguments":""}}""",
        )
        wrapped.onEvent(
            source,
            null,
            "response.function_call_arguments.delta",
            """{"type":"response.function_call_arguments.delta","item_id":"msg_submit","delta":"{\"parameters\":"}""",
        )
        wrapped.onEvent(
            source,
            null,
            "response.function_call_arguments.delta",
            """{"type":"response.function_call_arguments.delta","item_id":"msg_submit","delta":"[]}"}""",
        )
        wrapped.onEvent(
            source,
            null,
            "response.function_call_arguments.done",
            """{"type":"response.function_call_arguments.done","item_id":"msg_submit","name":"submit_json","arguments":"{\"parameters\":[]}"}""",
        )
        wrapped.onEvent(
            source,
            null,
            "response.completed",
            """{"type":"response.completed","response":{"usage":{"input_tokens":10,"output_tokens":3,"total_tokens":13}}}""",
        )

        val accumulator = AgentLlmStreamAccumulator(json)
        chunks.forEach(accumulator::consume)
        val turn = accumulator.buildTurn()

        assertEquals(
            """{"parameters":[]}""",
            turn.message.toolCalls?.single()?.function?.arguments,
        )
    }

    @Test
    fun `responses stream adapter deduplicates final assistant text snapshots`() {
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapResponsesListener(
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    chunks += data
                }
            }
        )

        val source = dummyEventSource()
        wrapped.onEvent(
            source,
            null,
            "response.output_text.delta",
            """{"type":"response.output_text.delta","delta":"Hello "}"""
        )
        wrapped.onEvent(
            source,
            null,
            "response.output_text.delta",
            """{"type":"response.output_text.delta","delta":"world"}"""
        )
        wrapped.onEvent(
            source,
            null,
            "response.content_part.done",
            """{"type":"response.content_part.done","part":{"type":"output_text","text":"Hello world"}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "response.output_item.done",
            """{"type":"response.output_item.done","item":{"type":"message","content":[{"type":"output_text","text":"Hello world"}]}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "response.completed",
            """{"type":"response.completed","response":{"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}}"""
        )

        val accumulator = AgentLlmStreamAccumulator(json)
        chunks.forEach(accumulator::consume)
        val turn = accumulator.buildTurn()

        assertEquals("Hello world", turn.message.contentText())
        assertEquals("stop", turn.finishReason)
        assertEquals(2, turn.usage?.completionTokens)
    }

    private fun dummyEventSource(): EventSource {
        return object : EventSource {
            override fun request(): Request =
                Request.Builder().url("https://example.com").build()

            override fun cancel() = Unit
        }
    }
}
