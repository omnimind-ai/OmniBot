package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpControllerCustomHeadersTest {

    @Test
    fun `explicit model refresh accepts a slow successful first response`() = runBlocking {
        serveSingleJsonResponse(
            body = """{"data":[{"id":"slow-provider-model"}]}""",
            responseDelayMillis = 12_000,
        ) { port ->
            val models = HttpController.fetchProviderModels(
                apiBase = "http://127.0.0.1:$port",
                apiKey = "test-key",
            )
            assertEquals(listOf("slow-provider-model"), models.map { it.id })
        }
        Unit
    }

    @Test
    fun `fetchProviderModels applies custom headers to openai compatible requests`() = runBlocking {
        val requestLines = serveSingleJsonResponse(
            """
                {
                  "data": [
                    {"id": "gpt-4.1-mini", "owned_by": "openai"}
                  ]
                }
            """.trimIndent()
        ) { port ->
            val models = HttpController.fetchProviderModels(
                apiBase = "http://127.0.0.1:$port",
                apiKey = "sk-default",
                customHeaders = linkedMapOf(
                    "authorization" to "Bearer override-token",
                    "X-Trace-Id" to "trace-1",
                    "Host" to "blocked.example"
                )
            )
            assertEquals(listOf("gpt-4.1-mini"), models.map { it.id })
        }

        assertEquals("GET /v1/models HTTP/1.1", requestLines.first())
        assertEquals(
            "authorization: bearer override-token",
            requestLines.firstOrNull { it.startsWith("authorization:", ignoreCase = true) }
                ?.lowercase()
        )
        assertEquals(
            "x-trace-id: trace-1",
            requestLines.firstOrNull { it.startsWith("x-trace-id:", ignoreCase = true) }
                ?.lowercase()
        )
        assertFalse(requestLines.any { it.equals("host: blocked.example", ignoreCase = true) })
    }

    @Test
    fun `fetchProviderModels applies custom headers to anthropic requests`() = runBlocking {
        val requestLines = serveSingleJsonResponse(
            """
                {
                  "data": [
                    {"id": "claude-sonnet-4-5", "type": "model"}
                  ]
                }
            """.trimIndent()
        ) { port ->
            val models = HttpController.fetchProviderModels(
                apiBase = "http://127.0.0.1:$port",
                apiKey = "sk-ant-default",
                customHeaders = linkedMapOf(
                    "x-api-key" to "override-ant-key",
                    "X-App-Name" to "OpenOmniBot",
                    "Connection" to "close"
                ),
                protocolType = "anthropic"
            )
            assertEquals(listOf("claude-sonnet-4-5"), models.map { it.id })
        }

        assertEquals("GET /v1/models HTTP/1.1", requestLines.first())
        assertEquals(
            "x-api-key: override-ant-key",
            requestLines.firstOrNull { it.startsWith("x-api-key:", ignoreCase = true) }
                ?.lowercase()
        )
        assertEquals(
            "x-app-name: openomnibot",
            requestLines.firstOrNull { it.startsWith("x-app-name:", ignoreCase = true) }
                ?.lowercase()
        )
        assertFalse(requestLines.any { it.equals("connection: close", ignoreCase = true) })
    }

    private fun serveSingleJsonResponse(
        body: String,
        responseDelayMillis: Long = 0,
        block: suspend (port: Int) -> Unit
    ): List<String> {
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
                    Thread.sleep(responseDelayMillis)
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
            runBlocking { block(serverSocket.localPort) }
            serverThread.join()
            return requestLines
        } finally {
            serverSocket.close()
        }
    }
}
