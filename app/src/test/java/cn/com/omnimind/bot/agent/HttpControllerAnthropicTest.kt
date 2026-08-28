package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.AnthropicMessageProtocolState
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionProtocolState
import cn.com.omnimind.baselib.llm.contentText
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpControllerAnthropicTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `automatic anthropic cache control is added for regular payloads`() {
        val method = HttpController::class.java.getDeclaredMethod(
            "applyAnthropicAutomaticCacheControl",
            String::class.java
        )
        method.isAccessible = true
        val payload = method.invoke(
            HttpController,
            """
                {
                  "model": "claude-sonnet",
                  "messages": [
                    {
                      "role": "user",
                      "content": [{"type": "text", "text": "hello"}]
                    }
                  ]
                }
            """.trimIndent()
        ) as String

        val root = json.parseToJsonElement(payload).jsonObject
        assertFalse(root.containsKey("cache_control"))
        val messageContent = root["messages"]
            ?.let { it as kotlinx.serialization.json.JsonArray }
            ?.single()
            ?.jsonObject
            ?.get("content") as kotlinx.serialization.json.JsonArray
        assertEquals(
            "ephemeral",
            messageContent.single().jsonObject["cache_control"]
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.content
        )
    }

    @Test
    fun `automatic anthropic cache control is skipped when breakpoint slots are full`() {
        val method = HttpController::class.java.getDeclaredMethod(
            "applyAnthropicAutomaticCacheControl",
            String::class.java
        )
        method.isAccessible = true
        val payload = method.invoke(
            HttpController,
            """
                {
                  "model": "claude-sonnet",
                  "system": [
                    {"type": "text", "text": "system-a", "cache_control": {"type": "ephemeral"}},
                    {"type": "text", "text": "system-b", "cache_control": {"type": "ephemeral"}}
                  ],
                  "messages": [
                    {
                      "role": "user",
                      "content": [{"type": "text", "text": "user-a", "cache_control": {"type": "ephemeral"}}]
                    },
                    {
                      "role": "assistant",
                      "content": [{"type": "text", "text": "assistant-a", "cache_control": {"type": "ephemeral"}}]
                    }
                  ]
                }
            """.trimIndent()
        ) as String

        val root = json.parseToJsonElement(payload).jsonObject
        assertFalse(root.containsKey("cache_control"))
    }

    @Test
    fun `automatic anthropic cache control uses stable system tools and transcript breakpoints`() {
        val method = HttpController::class.java.getDeclaredMethod(
            "applyAnthropicAutomaticCacheControl",
            String::class.java
        )
        method.isAccessible = true
        val payload = method.invoke(
            HttpController,
            """
                {
                  "model": "claude-sonnet",
                  "system": [
                    {"type": "text", "text": "stable", "cache_control": {"type": "ephemeral"}},
                    {"type": "text", "text": "coarse-time"}
                  ],
                  "tools": [
                    {"name": "a", "input_schema": {"type": "object"}},
                    {"name": "z", "input_schema": {"type": "object"}}
                  ],
                  "messages": [
                    {"role": "user", "content": [{"type": "text", "text": "hello"}]}
                  ]
                }
            """.trimIndent()
        ) as String

        val root = json.parseToJsonElement(payload).jsonObject
        val system = root["system"] as kotlinx.serialization.json.JsonArray
        val tools = root["tools"] as kotlinx.serialization.json.JsonArray
        val messages = root["messages"] as kotlinx.serialization.json.JsonArray

        assertTrue(system.all { it.jsonObject.containsKey("cache_control") })
        assertFalse(tools.first().jsonObject.containsKey("cache_control"))
        assertEquals(
            "ephemeral",
            tools.last().jsonObject["cache_control"]?.jsonObject
                ?.get("type")?.jsonPrimitive?.content
        )
        assertEquals(
            "ephemeral",
            messages.last().jsonObject["content"]
                ?.let { it as kotlinx.serialization.json.JsonArray }
                ?.last()
                ?.jsonObject
                ?.get("cache_control")
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.content
        )
    }

    @Test
    fun `anthropic stream adapter preserves cache hit usage`() {
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapAnthropicListener(
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
            "message_start",
            """
                {
                  "type": "message_start",
                  "message": {
                    "usage": {
                      "input_tokens": 12,
                      "cache_creation_input_tokens": 0,
                      "cache_read_input_tokens": 4096,
                      "output_tokens": 1
                    }
                  }
                }
            """.trimIndent()
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_delta",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "message_delta",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}"""
        )
        wrapped.onEvent(source, null, "message_stop", """{"type":"message_stop"}""")

        val accumulator = AgentLlmStreamAccumulator(json)
        chunks.forEach(accumulator::consume)
        val turn = accumulator.buildTurn()

        assertEquals("Hello", turn.message.contentText())
        assertEquals("end_turn", turn.finishReason)
        assertEquals(4108, turn.usage?.promptTokens)
        assertEquals(5, turn.usage?.completionTokens)
        assertEquals(4113, turn.usage?.totalTokens)
        assertEquals(
            "4096",
            turn.usage?.promptTokensDetails?.jsonObject
                ?.get("cached_tokens")?.jsonPrimitive?.content
        )
    }

    @Test
    fun `anthropic non stream usage counts cache writes as processed input`() {
        val method = HttpController::class.java.getDeclaredMethod(
            "normalizeAnthropicUsageResponse",
            String::class.java
        )
        method.isAccessible = true
        val usage = method.invoke(
            HttpController,
            """
                {
                  "usage": {
                    "input_tokens": 7,
                    "cache_creation_input_tokens": 1024,
                    "cache_read_input_tokens": 0,
                    "output_tokens": 2
                  }
                }
            """.trimIndent()
        ) as String
        val root = json.parseToJsonElement(usage).jsonObject

        assertEquals("1031", root["prompt_tokens"]?.jsonPrimitive?.content)
        assertEquals("2", root["completion_tokens"]?.jsonPrimitive?.content)
        assertEquals("1033", root["total_tokens"]?.jsonPrimitive?.content)
        assertEquals(
            "1024",
            root["prompt_tokens_details"]?.jsonObject
                ?.get("cache_creation_tokens")?.jsonPrimitive?.content
        )
        assertEquals(
            "0",
            root["prompt_tokens_details"]?.jsonObject
                ?.get("cached_tokens")?.jsonPrimitive?.content
        )
    }

    @Test
    fun `anthropic logged stream usage merges initial input with final output`() {
        val method = HttpController::class.java.getDeclaredMethod(
            "normalizeAnthropicUsageResponse",
            String::class.java
        )
        method.isAccessible = true
        val usage = method.invoke(
            HttpController,
            """
                [
                  {
                    "type": "message_start",
                    "message": {
                      "usage": {
                        "input_tokens": 9,
                        "cache_creation_input_tokens": 0,
                        "cache_read_input_tokens": 2048,
                        "output_tokens": 1
                      }
                    }
                  },
                  {
                    "type": "message_delta",
                    "usage": {"output_tokens": 6}
                  }
                ]
            """.trimIndent()
        ) as String
        val root = json.parseToJsonElement(usage).jsonObject

        assertEquals("2057", root["prompt_tokens"]?.jsonPrimitive?.content)
        assertEquals("6", root["completion_tokens"]?.jsonPrimitive?.content)
        assertEquals("2063", root["total_tokens"]?.jsonPrimitive?.content)
        assertEquals(
            "2048",
            root["prompt_tokens_details"]?.jsonObject
                ?.get("cached_tokens")?.jsonPrimitive?.content
        )
    }

    @Test
    fun `anthropic stream adapter still converts tool calls`() {
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapAnthropicListener(
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
            "message_start",
            """{"type":"message_start","message":{"usage":{"input_tokens":20,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":1}}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_start",
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool_7","name":"get_weather","input":{}}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_delta",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"city\":\"Shanghai\"}"}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "message_delta",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":4}}"""
        )
        wrapped.onEvent(source, null, "message_stop", """{"type":"message_stop"}""")

        val accumulator = AgentLlmStreamAccumulator(json)
        chunks.forEach(accumulator::consume)
        val turn = accumulator.buildTurn()

        assertEquals("tool_calls", turn.finishReason)
        assertEquals("tool_7", turn.message.toolCalls?.single()?.id)
        assertEquals("get_weather", turn.message.toolCalls?.single()?.function?.name)
        assertEquals(
            """{"city":"Shanghai"}""",
            turn.message.toolCalls?.single()?.function?.arguments
        )
        assertEquals(20, turn.usage?.promptTokens)
        assertEquals(4, turn.usage?.completionTokens)
    }

    @Test
    fun `anthropic tool loop replays signed thinking blocks unchanged`() {
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapAnthropicListener(
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
            "content_block_start",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_delta",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Need inspect."}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_delta",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"opaque-signature"}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_stop",
            """{"type":"content_block_stop","index":0}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_start",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":"Running command."}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_stop",
            """{"type":"content_block_stop","index":1}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_start",
            """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"tool_9","name":"terminal_execute","input":{}}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_delta",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"command\":\"pwd\"}"}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_stop",
            """{"type":"content_block_stop","index":2}"""
        )
        wrapped.onEvent(
            source,
            null,
            "message_delta",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}"""
        )
        wrapped.onEvent(source, null, "message_stop", """{"type":"message_stop"}""")

        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            captureAnthropicContentBlocks = true,
            anthropicSourceModel = "deepseek-v4-flash-vision-exp"
        )
        chunks.forEach(accumulator::consume)
        val assistant = accumulator.buildTurn().message
        val replayBlocks = assistant.protocolState?.anthropic?.contentBlocks
        assertNotNull(replayBlocks)
        assertEquals("thinking", replayBlocks!![0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(
            "opaque-signature",
            replayBlocks[0].jsonObject["signature"]?.jsonPrimitive?.content
        )
        assertEquals("Running command.", assistant.contentText())

        val toolResult = ChatCompletionMessage(
            role = "tool",
            toolCallId = "tool_9",
            content = JsonPrimitive("permission denied"),
            protocolState = ChatCompletionProtocolState(
                anthropic = AnthropicMessageProtocolState(toolResultIsError = true)
            )
        )
        val replayMethod = HttpController::class.java.getDeclaredMethod(
            "resolveAnthropicReplayContentBlocks",
            ChatCompletionMessage::class.java,
            String::class.java
        )
        replayMethod.isAccessible = true
        val replayedAssistantBlocks = replayMethod.invoke(
            HttpController,
            assistant,
            "deepseek-v4-flash-vision-exp"
        )
        assertEquals(replayBlocks, replayedAssistantBlocks)
        assertEquals(
            null,
            replayMethod.invoke(HttpController, assistant, "claude-sonnet-other")
        )

        val errorMethod = HttpController::class.java.getDeclaredMethod(
            "anthropicToolResultIsError",
            ChatCompletionMessage::class.java
        )
        errorMethod.isAccessible = true
        assertEquals(true, errorMethod.invoke(HttpController, toolResult))
    }

    @Test
    fun `fetchProviderModels supports anthropic models endpoint`() = runBlocking {
        val requestLines = mutableListOf<String>()
        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread {
            serverSocket.use { socketServer ->
                val socket = socketServer.accept()
                socket.use { client ->
                    val reader = BufferedReader(
                        InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)
                    )
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) {
                            break
                        }
                        requestLines += line
                    }

                    val body = """
                        {
                          "data": [
                            {
                              "id": "claude-sonnet-4-5",
                              "display_name": "Claude Sonnet 4.5",
                              "type": "model",
                              "context_limit": 1000000,
                              "output_limit": 64000,
                              "capabilities": {
                                "reasoning": true,
                                "tool_call": true,
                                "vision": true
                              }
                            },
                            {"id": "claude-haiku-4-5", "display_name": "Claude Haiku 4.5", "type": "model"}
                          ]
                        }
                    """.trimIndent()
                    val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
                    val writer = BufferedWriter(
                        OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8)
                    )
                    writer.write("HTTP/1.1 200 OK\r\n")
                    writer.write("Content-Type: application/json\r\n")
                    writer.write("Content-Length: ${bodyBytes.size}\r\n")
                    writer.write("Connection: close\r\n")
                    writer.write("\r\n")
                    writer.write(body)
                    writer.flush()
                }
            }
        }

        try {
            val models = HttpController.fetchProviderModels(
                apiBase = "http://127.0.0.1:${serverSocket.localPort}",
                apiKey = "sk-ant-test",
                protocolType = "anthropic"
            )
            serverThread.join()

            assertEquals(listOf("claude-haiku-4-5", "claude-sonnet-4-5"), models.map { it.id })
            assertEquals(
                listOf("Claude Haiku 4.5", "Claude Sonnet 4.5"),
                models.map { it.displayName }
            )
            assertEquals(1000000, models.last().contextLimit)
            assertEquals(64000, models.last().outputLimit)
            assertTrue(models.last().reasoning == true)
            assertTrue(models.last().toolCall == true)
            assertTrue(models.last().attachment == true)
            assertEquals("GET /v1/models HTTP/1.1", requestLines.first())
            assertEquals(
                "x-api-key: sk-ant-test",
                requestLines.firstOrNull { it.startsWith("x-api-key:", ignoreCase = true) }
            )
            assertEquals(
                "anthropic-version: 2023-06-01",
                requestLines.firstOrNull { it.startsWith("anthropic-version:", ignoreCase = true) }
            )
            assertNotNull(models.first().ownedBy)
        } finally {
            serverSocket.close()
        }
    }

    private fun dummyEventSource(): EventSource {
        return object : EventSource {
            override fun request(): Request =
                Request.Builder().url("https://example.com").build()

            override fun cancel() = Unit
        }
    }

}
