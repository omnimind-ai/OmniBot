package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.util.CredentialEndpointSecurity
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class HttpControllerAnthropicTest {
    @Before
    fun enableDebugLiteralLoopback() {
        CredentialEndpointSecurity.configureDebugLoopback(true)
    }

    @After
    fun resetDebugLiteralLoopback() {
        CredentialEndpointSecurity.configureDebugLoopback(false)
    }
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
        assertEquals(
            "ephemeral",
            root["cache_control"]?.jsonObject?.get("type")?.jsonPrimitive?.content
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

}
