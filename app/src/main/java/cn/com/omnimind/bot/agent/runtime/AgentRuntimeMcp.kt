@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.bot.mcp.McpServerState
import cn.com.omnimind.bot.mcp.RemoteMcpConfigStore
import cn.com.omnimind.bot.mcp.RemoteMcpServerConfig
import cn.com.omnimind.bot.mcp.RemoteMcpTransport
import com.agentclientprotocol.model.HttpHeader
import com.agentclientprotocol.model.McpServer

private const val LOCAL_AGENT_MCP_SERVER_NAME = "omnibot"
private const val LOCAL_AGENT_MCP_HOST = "127.0.0.1"

/**
 * Builds the standard ACP session-level MCP declaration used by local agents.
 *
 * Local Harnesses receive this only through the official ACP session/new
 * declaration. Tool discovery and namespacing remain Harness-owned.
 */
internal fun buildLocalAgentAcpMcpServers(
    supportsHttp: Boolean,
    state: McpServerState,
): List<McpServer> {
    if (!supportsHttp) return emptyList()
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
 * Preserve the transport type negotiated through ACP. A legacy SSE endpoint
 * must never be mislabeled as Streamable HTTP, and neither optional transport
 * may be sent to an Agent that omitted the corresponding capability.
 */
internal fun buildConfiguredRemoteAcpMcpServers(
    configured: List<RemoteMcpServerConfig> = RemoteMcpConfigStore.listEnabledServers(),
    supportsHttp: Boolean,
    supportsSse: Boolean,
): List<McpServer> {
    val usedNames = linkedSetOf<String>()
    return configured.mapNotNull { server ->
        val endpoint = server.endpointUrl.trim()
        if (!endpoint.startsWith("http://", ignoreCase = true) &&
            !endpoint.startsWith("https://", ignoreCase = true)
        ) {
            return@mapNotNull null
        }
        val legacySse = server.transport == RemoteMcpTransport.SSE ||
            (
                server.transport == RemoteMcpTransport.AUTO &&
                    endpoint.substringBefore('?').trimEnd('/')
                        .endsWith("/sse", ignoreCase = true)
                )
        if (legacySse && !supportsSse) return@mapNotNull null
        if (!legacySse && !supportsHttp) return@mapNotNull null
        val baseName = server.name.trim().ifBlank {
            "remote-${server.id.take(8)}"
        }
        var name = baseName
        var suffix = 2
        while (!usedNames.add(name)) {
            name = "$baseName-$suffix"
            suffix += 1
        }
        val configuredHeaders = server.headers.entries
            .filter { (headerName, _) -> headerName.isNotBlank() }
            .map { (headerName, value) -> HttpHeader(name = headerName, value = value) }
            .toMutableList()
        val hasAuthorization = configuredHeaders.any {
            it.name.equals("Authorization", ignoreCase = true)
        }
        if (!hasAuthorization && server.bearerToken.isNotBlank()) {
            configuredHeaders += HttpHeader(
                name = "Authorization",
                value = "Bearer ${server.bearerToken.trim()}",
            )
        }
        val headers = configuredHeaders.toList()
        if (legacySse) {
            McpServer.Sse(
                name = name,
                url = endpoint,
                headers = headers,
            )
        } else {
            McpServer.Http(
                name = name,
                url = endpoint,
                headers = headers,
            )
        }
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
