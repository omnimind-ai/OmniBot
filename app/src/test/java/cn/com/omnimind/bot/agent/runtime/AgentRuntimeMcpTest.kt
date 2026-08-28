package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.llm.SceneModelBindingEntry
import cn.com.omnimind.bot.mcp.McpServerState
import cn.com.omnimind.bot.mcp.RemoteMcpServerConfig
import com.agentclientprotocol.model.McpServer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun deepSeekHarnessUsesOfficialSessionMcpDeclaration() {
        val servers = buildLocalAgentAcpMcpServers(
            harnessAdapter = AcpHarnessAdapters.deepSeekHarness,
            supportsHttp = true,
            state = runningState,
        )
        assertEquals(1, servers.size)
        assertEquals("omnibot", (servers.single() as McpServer.Http).name)
        assertTrue(AcpHarnessAdapters.deepSeekHarness.mcpEnvironment(runningState).isEmpty())
    }

    @Test
    fun configuredRemoteMcpServersAreForwardedWithoutReplacingOmnibotSurface() {
        val servers = buildConfiguredRemoteAcpMcpServers(
            configured = listOf(
                RemoteMcpServerConfig(
                    id = "filesystem",
                    name = "Filesystem",
                    endpointUrl = "https://mcp.example.test/mcp",
                    bearerToken = "remote-token",
                ),
                RemoteMcpServerConfig(
                    id = "legacy-sse",
                    name = "Legacy SSE",
                    endpointUrl = "https://mcp.example.test/sse",
                ),
            ),
        )

        assertEquals(1, servers.size)
        val first = servers.first() as McpServer.Http
        assertEquals("Filesystem", first.name)
        assertEquals("https://mcp.example.test/mcp", first.url)
        assertEquals("Bearer remote-token", first.headers.single().value)
    }

    @Test
    fun deepSeekCordisAndNativeTerminalCapabilitiesAreNegotiated() {
        assertEquals(
            "0",
            ACP_CLIENT_CAPABILITY_META["dsh"]!!
                .jsonObject["cordis"]!!
                .jsonObject["protocol"]!!
                .jsonPrimitive.content,
        )
        assertEquals("true", ACP_CLIENT_CAPABILITY_META["terminal_output"]!!.toString())
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

    @Test
    fun codexSkipsMcpDeclarationForCustomResponsesProvider() {
        val provider = AgentProviderCredentials(
            baseUrl = "https://gateway.example/v1",
            apiKey = "test-key",
            wireApi = OpenAiWireApi.RESPONSES,
            supportsNamespaceTools = false,
        )

        assertTrue(!AcpHarnessAdapters.codex.supportsSessionMcp(provider))
        assertTrue(
            AcpHarnessAdapters.codex.supportsSessionMcp(
                provider.copy(supportsNamespaceTools = true),
            ),
        )
    }

    @Test
    fun missingSharedBindingCanBeMigratedFromEditingProviderModel() {
        val migrated = resolveSharedAgentProviderBinding(
            currentBinding = null,
            editingProfile = ModelProviderProfile(
                id = "deepseek-v4",
                name = "DeepSeek V4",
                baseUrl = "https://gateway.example/v1",
                apiKey = "test-key",
            ),
            availableModels = listOf(
                ProviderModelOption(id = "deepseek-v4"),
            ),
        )

        assertEquals(
            SceneModelBindingEntry(
                sceneId = "scene.dispatch.model",
                providerProfileId = "deepseek-v4",
                modelId = "deepseek-v4",
            ),
            migrated,
        )
    }

    @Test
    fun conversationHarnessOwnerWinsOverAConflictingRequestedHarness() {
        assertEquals(
            "codex-acp",
            resolveConversationHarnessId(
                chatOnly = false,
                requestedAgentId = "deepseek-harness",
                conversationAgentId = "codex-acp",
                conversationBindingAgentId = null,
                sessionAgentId = null,
                selectedAgentId = "xiaowan",
            ),
        )
        assertEquals(
            "deepseek-harness",
            resolveConversationHarnessId(
                chatOnly = false,
                requestedAgentId = "deepseek-harness",
                conversationAgentId = null,
                conversationBindingAgentId = null,
                // A new conversation may briefly carry the previous page's
                // session id. The explicit new Harness must win over it.
                sessionAgentId = "codex-acp",
                selectedAgentId = "xiaowan",
            ),
        )
        assertEquals(
            AcpAgentProfileStore.XIAOWAN_AGENT_ID,
            resolveConversationHarnessId(
                chatOnly = true,
                requestedAgentId = "deepseek-harness",
                conversationAgentId = "codex-acp",
                conversationBindingAgentId = null,
                sessionAgentId = null,
                selectedAgentId = "deepseek-harness",
            ),
        )
    }

}
