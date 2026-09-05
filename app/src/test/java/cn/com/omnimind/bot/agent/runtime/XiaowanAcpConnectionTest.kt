package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.bot.mcp.RemoteMcpCallResult
import cn.com.omnimind.bot.mcp.RemoteMcpToolDescriptor
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaowanAcpConnectionTest {

    @Test
    fun `xiaowan advertises only the ACP lifecycle and MCP transports it implements`() {
        val capabilities = xiaowanAgentCapabilities()

        assertFalse(capabilities.loadSession)
        assertTrue(capabilities.mcpCapabilities.http)
        assertTrue(capabilities.mcpCapabilities.sse)
        assertNotNull(capabilities.sessionCapabilities.list)
        assertNotNull(capabilities.sessionCapabilities.resume)
        assertNotNull(capabilities.sessionCapabilities.delete)
        assertNotNull(capabilities.sessionCapabilities.close)
        assertNull(capabilities.sessionCapabilities.fork)
    }

    @Test
    fun `session MCP tools receive safe unique model names and MCP metadata`() {
        val connection = fakeMcpConnection("session-server", "团队 MCP")
        val descriptor = RemoteMcpToolDescriptor(
            serverId = connection.connectionId,
            serverName = connection.serverName,
            toolName = "查询/天气",
            description = "查询天气",
            inputSchema = mapOf("type" to "object"),
        )
        val bindings = buildXiaowanMcpToolBindings(
            listOf(
                XiaowanMcpDiscoveredTool(connection, descriptor),
                XiaowanMcpDiscoveredTool(connection, descriptor),
            )
        )

        assertEquals(2, bindings.map { it.modelToolName }.distinct().size)
        bindings.forEach { binding ->
            assertTrue(binding.modelToolName.length <= 64)
            assertTrue(binding.modelToolName.matches(Regex("[A-Za-z0-9_-]+")))
        }
        val module = XiaowanMcpCapabilityModule(bindings)
        assertEquals(setOf("mcp"), module.toolDefinitions.map { it.toolType }.toSet())
        assertEquals(setOf("团队 MCP"), module.toolDefinitions.map { it.serverName }.toSet())
        assertEquals(bindings.map { it.modelToolName }.toSet(), module.handlers.single().toolNames)
    }

    @Test
    fun `explicit reasoning rounds use separate ACP thought messages`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onThinkingStart()
        bridge.onThinkingUpdate("先分析")
        bridge.onThinkingStart()
        bridge.onThinkingUpdate("再调用工具")

        val thoughtUpdates = updates.filterIsInstance<SessionUpdate.AgentThoughtChunk>()
        assertEquals(4, thoughtUpdates.size)
        assertEquals(2, thoughtUpdates.map { it.messageId }.distinct().size)

        val contentUpdates = thoughtUpdates.filter {
            (it.content as ContentBlock.Text).text.isNotEmpty()
        }
        assertEquals(2, contentUpdates.size)
        assertNotEquals(contentUpdates[0].content, contentUpdates[1].content)
    }

    @Test
    fun `stdio MCP result keeps complete text in summary preview and raw projection`() {
        val longText = "long MCP fact|".repeat(512)
        val result = remoteMcpCallResult(
            buildJsonObject {
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(longText))
                            }
                        )
                    }
                )
            }
        )

        assertEquals(longText, result.summaryText)
        assertTrue(result.previewJson.contains(longText))
        assertTrue(result.rawResultJson.contains(longText))
    }

    private fun fakeMcpConnection(
        id: String,
        name: String,
    ): XiaowanMcpServerConnection = object : XiaowanMcpServerConnection {
        override val connectionId: String = id
        override val serverName: String = name

        override suspend fun start() = Unit

        override suspend fun listTools(): List<RemoteMcpToolDescriptor> = emptyList()

        override suspend fun callTool(
            toolName: String,
            arguments: Map<String, Any?>,
        ): RemoteMcpCallResult = error("not used")

        override suspend fun close() = Unit
    }
}
