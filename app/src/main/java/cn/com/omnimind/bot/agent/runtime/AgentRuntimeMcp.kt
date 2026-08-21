@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.bot.mcp.McpServerState
import com.agentclientprotocol.model.HttpHeader
import com.agentclientprotocol.model.McpServer

private const val LOCAL_AGENT_MCP_SERVER_NAME = "omnibot"
private const val LOCAL_AGENT_MCP_HOST = "127.0.0.1"

/**
 * Builds the standard ACP session-level MCP declaration used by local agents.
 *
 * A Harness that cannot consume session-level MCP declarations receives the
 * same endpoint through its adapter-owned launch environment instead.
 */
internal fun buildLocalAgentAcpMcpServers(
    harnessAdapter: AcpHarnessAdapter,
    supportsHttp: Boolean,
    state: McpServerState,
): List<McpServer> {
    if (!supportsHttp ||
        harnessAdapter.mcpTransport != AcpHarnessMcpTransport.SESSION_DECLARATION
    ) {
        return emptyList()
    }
    require(state.running) { "Omnibot MCP server is not running." }
    require(state.port in 1..65535) { "Omnibot MCP server port is invalid." }
    require(state.token.isNotBlank()) { "Omnibot MCP server token is missing." }
    return listOf(
        McpServer.Http(
            name = LOCAL_AGENT_MCP_SERVER_NAME,
            url = localAgentMcpUrl(state),
            headers = listOf(
                HttpHeader(
                    name = "Authorization",
                    value = "Bearer ${state.token}"
                )
            )
        )
    )
}

internal fun buildEnvironmentMcpBinding(
    state: McpServerState,
): Map<String, String> {
    require(state.running) { "Omnibot MCP server is not running." }
    require(state.port in 1..65535) { "Omnibot MCP server port is invalid." }
    require(state.token.isNotBlank()) { "Omnibot MCP server token is missing." }
    return mapOf(
        "OMNIBOT_MCP_URL" to localAgentMcpUrl(state),
        "OMNIBOT_MCP_TOKEN" to state.token,
    )
}

private fun localAgentMcpUrl(state: McpServerState): String =
    "http://$LOCAL_AGENT_MCP_HOST:${state.port}/mcp"
