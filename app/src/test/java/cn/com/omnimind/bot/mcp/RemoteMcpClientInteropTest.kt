package cn.com.omnimind.bot.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class RemoteMcpClientInteropTest {
    private lateinit var server: MockWebServer
    private lateinit var config: RemoteMcpServerConfig
    private val responsePlans = ConcurrentLinkedQueue<ResponsePlan>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val plan = responsePlans.poll()
                    ?: return MockResponse().setResponseCode(500).setBody("No response planned")
                val body = if (plan.echoRequestId && plan.body.isNotBlank()) {
                    val requestId = runCatching {
                        Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject["id"]
                    }.getOrNull() ?: JsonNull
                    val response = Json.parseToJsonElement(plan.body).jsonObject
                    JsonObject(response + ("id" to requestId)).toString()
                } else {
                    plan.body
                }
                val response = MockResponse()
                    .setResponseCode(plan.code)
                    .setBody(body)
                plan.headers.forEach { (name, value) -> response.setHeader(name, value) }
                return response
            }
        }
        server.start()
        config = RemoteMcpServerConfig(
            id = "interop-${System.nanoTime()}",
            name = "interop",
            endpointUrl = server.url("/mcp").toString(),
            headers = mapOf("X-Test-Header" to "forwarded"),
            transport = RemoteMcpTransport.HTTP,
        )
    }

    @After
    fun tearDown() {
        RemoteMcpClient.invalidateSession(config.id)
        server.shutdown()
    }

    @Test
    fun `modern server is discovered and receives per-request metadata`() = runBlocking {
        enqueueJson(
            """{
                "jsonrpc":"2.0","id":"discover","result":{
                  "resultType":"complete","ttlMs":0,"cacheScope":"private",
                  "supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}
                }
            }""".trimIndent()
        )
        enqueueJson(
            """{
                "jsonrpc":"2.0","id":"list","result":{
                  "resultType":"complete","ttlMs":0,"cacheScope":"private",
                  "tools":[{"name":"天气 查询","description":"weather","inputSchema":{
                    "type":"object","properties":{
                      "region":{"type":"string","x-mcp-header":"Region"}
                    }
                  }}]
                }
            }""".trimIndent()
        )
        enqueueJson(
            """{
              "jsonrpc":"2.0","id":"call","result":{
                "resultType":"complete","content":[{"type":"text","text":"晴"}]
              }
            }""".trimIndent()
        )

        val tools = RemoteMcpClient.listTools(config)
        val callResult = RemoteMcpClient.callTool(
            config,
            "天气 查询",
            mapOf("region" to "华东"),
        )

        assertEquals(listOf("天气 查询"), tools.map { it.toolName })
        assertEquals("晴", callResult.summaryText)
        val discover = server.takeRequest()
        assertEquals("server/discover", discover.getHeader("Mcp-Method"))
        assertEquals("2026-07-28", discover.getHeader("MCP-Protocol-Version"))
        assertEquals("forwarded", discover.getHeader("X-Test-Header"))
        val body = Json.parseToJsonElement(discover.body.readUtf8()).jsonObject
        assertNull(body["_meta"])
        val meta = body["params"]!!.jsonObject["_meta"]!!.jsonObject
        assertEquals(
            "2026-07-28",
            meta["io.modelcontextprotocol/protocolVersion"]!!.jsonPrimitive.content,
        )
        assertTrue(meta["io.modelcontextprotocol/clientCapabilities"] != null)

        val list = server.takeRequest()
        assertEquals("tools/list", list.getHeader("Mcp-Method"))
        assertFalse(list.headers.names().contains("Mcp-Session-Id"))
        val call = server.takeRequest()
        assertEquals("tools/call", call.getHeader("Mcp-Method"))
        assertEquals(
            "天气 查询",
            ModernMcpProtocol.decodeMcpHeaderValue(call.getHeader("Mcp-Name")!!),
        )
        assertEquals(
            "华东",
            ModernMcpProtocol.decodeMcpHeaderValue(call.getHeader("Mcp-Param-Region")!!),
        )
    }

    @Test
    fun `modern header mismatch refreshes the tool schema and retries once`() = runBlocking {
        enqueueModernDiscover()
        enqueueModernToolList(headerName = "Region")
        enqueueJson(
            """{
              "jsonrpc":"2.0","id":"call",
              "error":{"code":-32020,"message":"Header mismatch"}
            }""".trimIndent(),
            code = 400,
        )
        enqueueModernToolList(headerName = "Zone")
        enqueueJson(
            """{
              "jsonrpc":"2.0","id":"call","result":{
                "resultType":"complete","content":[{"type":"text","text":"updated"}]
              }
            }""".trimIndent()
        )

        RemoteMcpClient.listTools(config)
        val result = RemoteMcpClient.callTool(
            config,
            "weather",
            mapOf("region" to "east"),
        )

        assertEquals("updated", result.summaryText)
        val requests = (0 until 5).map { server.takeRequest() }
        assertEquals("east", requests[2].getHeader("Mcp-Param-Region"))
        assertNull(requests[2].getHeader("Mcp-Param-Zone"))
        assertNull(requests[4].getHeader("Mcp-Param-Region"))
        assertEquals("east", requests[4].getHeader("Mcp-Param-Zone"))
    }

    @Test
    fun `legacy streamable HTTP initializes once and reuses its session`() = runBlocking {
        enqueueJson(
            """{"jsonrpc":"2.0","id":"probe","error":{"code":-32601,"message":"Method not found"}}"""
        )
        enqueueJson(
            """{
              "jsonrpc":"2.0","id":"init","result":{
                "protocolVersion":"2025-11-25","capabilities":{"tools":{}},
                "serverInfo":{"name":"legacy","version":"1"}
              }
            }""".trimIndent(),
            headers = mapOf("Mcp-Session-Id" to "legacy-session-1"),
        )
        enqueueStatus(202)
        enqueueJson(
            """{
              "jsonrpc":"2.0","id":"list","result":{
                "tools":[{"name":"echo","description":"echo","inputSchema":{"type":"object"}}]
              }
            }""".trimIndent()
        )
        enqueueJson(
            """{
              "jsonrpc":"2.0","id":"call","result":{
                "content":[{"type":"text","text":"ok"}],"isError":false
              }
            }""".trimIndent()
        )

        assertEquals("echo", RemoteMcpClient.listTools(config).single().toolName)
        assertEquals(
            "ok",
            RemoteMcpClient.callTool(config, "echo", mapOf("value" to "hello")).summaryText,
        )

        val methods = (0 until 5).map {
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            Json.parseToJsonElement(body).jsonObject["method"]!!.jsonPrimitive.content to request
        }
        assertEquals(
            listOf(
                "server/discover",
                "initialize",
                "notifications/initialized",
                "tools/list",
                "tools/call",
            ),
            methods.map { it.first },
        )
        assertNull(methods[1].second.getHeader("Mcp-Session-Id"))
        methods.drop(2).forEach { (_, request) ->
            assertEquals("legacy-session-1", request.getHeader("Mcp-Session-Id"))
        }
    }

    @Test
    fun `remote MCP preserves a large result in the user-visible summary and preview`() = runBlocking {
        val completeText = "remote-result:" + "x".repeat(2_400) + ":tail-must-survive"
        enqueueLegacyProbeFailure()
        enqueueLegacyInitialize("legacy-session-large-result")
        enqueueJson(
            """{
              "jsonrpc":"2.0","id":"list","result":{
                "tools":[{"name":"echo","inputSchema":{"type":"object"}}]
              }
            }""".trimIndent()
        )
        enqueueJson(
            """{
              "jsonrpc":"2.0","id":"call","result":{
                "content":[{"type":"text","text":"$completeText"}],"isError":false
              }
            }""".trimIndent()
        )

        RemoteMcpClient.listTools(config)
        val result = RemoteMcpClient.callTool(config, "echo", emptyMap())

        assertEquals(completeText, result.summaryText)
        assertTrue(result.previewJson.contains(completeText))
        assertTrue(result.rawResultJson.contains(completeText))
    }

    @Test
    fun `legacy server version rejection falls back from modern discovery`() = runBlocking {
        listOf(-32600, -32602).forEach { rejectionCode ->
            val attemptConfig = config.copy(id = "${config.id}-$rejectionCode")
            enqueueJson(
                """{
                  "jsonrpc":"2.0","id":"probe",
                  "error":{"code":$rejectionCode,"message":"Invalid MCP-Protocol-Version"}
                }""".trimIndent(),
                code = 400,
            )
            enqueueJson(
                """{
                  "jsonrpc":"2.0","id":"init","result":{
                    "protocolVersion":"2025-06-18","capabilities":{"tools":{}},
                    "serverInfo":{"name":"legacy","version":"1"}
                  }
                }""".trimIndent(),
                headers = mapOf("Mcp-Session-Id" to "legacy-$rejectionCode"),
            )
            enqueueStatus(202)
            enqueueJson(
                """{
                  "jsonrpc":"2.0","id":"list","result":{
                    "tools":[{"name":"echo","inputSchema":{"type":"object"}}]
                  }
                }""".trimIndent(),
            )

            assertEquals("echo", RemoteMcpClient.listTools(attemptConfig).single().toolName)

            val requests = (0 until 4).map { server.takeRequest() }
            val methods = requests.map { request ->
                Json.parseToJsonElement(request.body.readUtf8())
                    .jsonObject["method"]!!.jsonPrimitive.content
            }
            assertEquals(
                listOf("server/discover", "initialize", "notifications/initialized", "tools/list"),
                methods,
            )
            assertEquals("2026-07-28", requests[0].getHeader("MCP-Protocol-Version"))
            assertNull(requests[1].getHeader("MCP-Protocol-Version"))
            assertEquals("2025-06-18", requests[3].getHeader("MCP-Protocol-Version"))
            RemoteMcpClient.invalidateSession(attemptConfig.id)
        }
    }

    @Test
    fun `stale legacy HTTP session is reinitialized once without forwarding old id`() = runBlocking {
        enqueueLegacyProbeFailure()
        enqueueLegacyInitialize("legacy-session-old")
        enqueueJson(
            """{
              "jsonrpc":"2.0","id":"list","result":{
                "tools":[{"name":"echo","inputSchema":{"type":"object"}}]
              }
            }""".trimIndent()
        )
        enqueueStatus(404)
        enqueueLegacyProbeFailure()
        enqueueLegacyInitialize("legacy-session-new")
        enqueueJson(
            """{
              "jsonrpc":"2.0","id":"call","result":{
                "content":[{"type":"text","text":"recovered"}],"isError":false
              }
            }""".trimIndent()
        )

        RemoteMcpClient.listTools(config)
        val result = RemoteMcpClient.callTool(config, "echo", emptyMap())

        assertEquals("recovered", result.summaryText)
        val requests = (0 until 9).map { server.takeRequest() }
        val methods = requests.map { request ->
            Json.parseToJsonElement(request.body.clone().readUtf8())
                .jsonObject["method"]!!
                .jsonPrimitive
                .content
        }
        assertEquals(
            listOf(
                "server/discover",
                "initialize",
                "notifications/initialized",
                "tools/list",
                "tools/call",
                "server/discover",
                "initialize",
                "notifications/initialized",
                "tools/call",
            ),
            methods,
        )
        assertEquals("legacy-session-old", requests[4].getHeader("Mcp-Session-Id"))
        assertNull(requests[5].getHeader("Mcp-Session-Id"))
        assertNull(requests[6].getHeader("Mcp-Session-Id"))
        assertEquals("legacy-session-new", requests[8].getHeader("Mcp-Session-Id"))
    }

    @Test
    fun `closing a stateful legacy HTTP connection terminates its MCP session`() = runBlocking {
        enqueueLegacyProbeFailure()
        enqueueLegacyInitialize("legacy-session-close")
        enqueueJson(
            """{
              "jsonrpc":"2.0","id":"list","result":{"tools":[]}
            }""".trimIndent()
        )
        enqueueStatus(200)

        RemoteMcpClient.listTools(config)
        RemoteMcpClient.close(config)

        val requests = (0 until 5).map { server.takeRequest() }
        assertEquals("DELETE", requests.last().method)
        assertEquals(
            "legacy-session-close",
            requests.last().getHeader("Mcp-Session-Id"),
        )
        assertEquals(
            "2025-11-25",
            requests.last().getHeader("MCP-Protocol-Version"),
        )
    }

    @Test
    fun `explicit HTTP transport never falls through to deprecated SSE`() = runBlocking {
        enqueueLegacyProbeFailure(code = 404)
        enqueueStatus(404)

        val failure = runCatching { RemoteMcpClient.listTools(config) }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(2, server.requestCount)
        assertEquals("POST", server.takeRequest().method)
        assertEquals("POST", server.takeRequest().method)
    }

    @Test
    fun `modern MCP name header uses the required base64 sentinel`() {
        val encoded = RemoteMcpClient.encodeMcpHeaderValue("天气 查询")

        assertTrue(encoded.startsWith("=?base64?"))
        assertTrue(encoded.endsWith("?="))
        assertEquals("plain_tool", RemoteMcpClient.encodeMcpHeaderValue("plain_tool"))
    }

    private fun enqueueJson(
        body: String,
        headers: Map<String, String> = emptyMap(),
        code: Int = 200,
    ) {
        responsePlans += ResponsePlan(
            code = code,
            body = body,
            headers = mapOf("Content-Type" to "application/json") + headers,
            echoRequestId = true,
        )
    }

    private fun enqueueStatus(code: Int) {
        responsePlans += ResponsePlan(code = code)
    }

    private fun enqueueLegacyProbeFailure(code: Int = 200) {
        enqueueJson(
            """{
              "jsonrpc":"2.0","id":"probe",
              "error":{"code":-32601,"message":"Method not found"}
            }""".trimIndent(),
            code = code,
        )
    }

    private fun enqueueLegacyInitialize(sessionId: String) {
        enqueueJson(
            """{
              "jsonrpc":"2.0","id":"init","result":{
                "protocolVersion":"2025-11-25","capabilities":{"tools":{}},
                "serverInfo":{"name":"legacy","version":"1"}
              }
            }""".trimIndent(),
            headers = mapOf("Mcp-Session-Id" to sessionId),
        )
        enqueueStatus(202)
    }

    private fun enqueueModernDiscover() {
        enqueueJson(
            """{
              "jsonrpc":"2.0","id":"discover","result":{
                "resultType":"complete","ttlMs":0,"cacheScope":"private",
                "supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}
              }
            }""".trimIndent()
        )
    }

    private fun enqueueModernToolList(headerName: String) {
        enqueueJson(
            """{
              "jsonrpc":"2.0","id":"list","result":{
                "resultType":"complete","ttlMs":0,"cacheScope":"private",
                "tools":[{"name":"weather","inputSchema":{
                  "type":"object","properties":{
                    "region":{"type":"string","x-mcp-header":"$headerName"}
                  }
                }}]
              }
            }""".trimIndent()
        )
    }

    private data class ResponsePlan(
        val code: Int,
        val body: String = "",
        val headers: Map<String, String> = emptyMap(),
        val echoRequestId: Boolean = false,
    )
}
