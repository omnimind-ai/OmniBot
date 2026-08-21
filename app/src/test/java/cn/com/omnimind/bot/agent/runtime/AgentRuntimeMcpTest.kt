package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.bot.mcp.McpServerState
import com.agentclientprotocol.model.McpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeMcpTest {
    private val runningState = McpServerState(
        enabled = true,
        running = true,
        host = "127.0.0.1",
        port = 18765,
        token = "test-token",
    )

    @Test
    fun standardAcpAgentReceivesOfficialHttpMcpDeclaration() {
        val servers = buildLocalAgentAcpMcpServers(
            harnessAdapter = AcpHarnessAdapters.standard,
            supportsHttp = true,
            state = runningState,
        )

        assertEquals(1, servers.size)
        val server = servers.single()
        assertTrue(server is McpServer.Http)
        server as McpServer.Http
        assertEquals("omnibot", server.name)
        assertEquals("http://127.0.0.1:18765/mcp", server.url)
        assertEquals("Authorization", server.headers.single().name)
        assertEquals("Bearer test-token", server.headers.single().value)
    }

    @Test
    fun environmentBoundHarnessUsesAdapterEnvironmentInsteadOfSessionDeclaration() {
        assertTrue(
            buildLocalAgentAcpMcpServers(
                harnessAdapter = AcpHarnessAdapters.deepSeekHarness,
                supportsHttp = true,
                state = runningState,
            ).isEmpty(),
        )

        assertEquals(
            mapOf(
                "OMNIBOT_MCP_URL" to "http://127.0.0.1:18765/mcp",
                "OMNIBOT_MCP_TOKEN" to "test-token",
            ),
            AcpHarnessAdapters.deepSeekHarness.mcpEnvironment(runningState),
        )
    }

    @Test
    fun mcpDeclarationsRejectAnUnavailableServer() {
        val stopped = runningState.copy(running = false)

        try {
            buildLocalAgentAcpMcpServers(
                harnessAdapter = AcpHarnessAdapters.standard,
                supportsHttp = true,
                state = stopped,
            )
            throw AssertionError("expected unavailable MCP server to be rejected")
        } catch (error: IllegalArgumentException) {
            assertEquals("Omnibot MCP server is not running.", error.message)
        }
    }

}
