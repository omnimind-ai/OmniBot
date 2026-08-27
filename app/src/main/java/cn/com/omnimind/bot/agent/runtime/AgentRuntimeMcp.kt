@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.bot.mcp.McpServerState
import cn.com.omnimind.bot.mcp.RemoteMcpConfigStore
import cn.com.omnimind.bot.mcp.RemoteMcpServerConfig
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

/**
 * Projects the user's enabled remote MCP servers onto the official ACP
 * session declaration.  These servers belong to the configured Harness
 * session; they are not reimplemented by OmniBot and their tool names are
 * therefore left untouched for the Harness to namespace.
 *
 * ACP 0.26 only has a typed HTTP server in the JVM SDK.  Streamable HTTP is
 * supported by the official adapters; legacy `/sse` entries remain available
 * to OmniBot's native MCP client but are deliberately not sent as an invalid
 * ACP HTTP declaration.
 */
internal fun buildConfiguredRemoteAcpMcpServers(
    configured: List<RemoteMcpServerConfig> = RemoteMcpConfigStore.listEnabledServers(),
): List<McpServer> {
    val usedNames = linkedSetOf<String>()
    return configured.mapNotNull { server ->
        val endpoint = server.endpointUrl.trim()
        if (!endpoint.startsWith("http://", ignoreCase = true) &&
            !endpoint.startsWith("https://", ignoreCase = true)
        ) {
            return@mapNotNull null
        }
        if (endpoint.substringBefore('?').trimEnd('/').endsWith("/sse", ignoreCase = true)) {
            return@mapNotNull null
        }
        val baseName = server.name.trim().ifBlank {
            "remote-${server.id.take(8)}"
        }
        var name = baseName
        var suffix = 2
        while (!usedNames.add(name)) {
            name = "$baseName-$suffix"
            suffix += 1
        }
        val headers = server.bearerToken.trim()
            .takeIf(String::isNotEmpty)
            ?.let {
                listOf(HttpHeader(name = "Authorization", value = "Bearer $it"))
            }
            .orEmpty()
        McpServer.Http(
            name = name,
            url = endpoint,
            headers = headers,
        )
    }
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
