package cn.com.omnimind.bot.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConfigAdaptersTest {
    private val provider = AgentProviderCredentials(
        baseUrl = "https://llmapi.paratera.com/v1",
        apiKey = "secret",
    )

    @Test
    fun sharedProviderMapsToOfficialRuntimeSurfaces() {
        val model = "GLM-5.1"

        val dsh = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = AcpAgentProfileStore.DEEPSEEK_HARNESS_AGENT_ID,
                provider = provider,
                model = model,
            ),
        )
        assertEquals("https://llmapi.paratera.com/v1", dsh.environment["DEEPSEEK_BASE_URL"])
        assertEquals("secret", dsh.environment["DEEPSEEK_API_KEY"])
        assertEquals(model, dsh.environment["DSH_MODEL"])

        val codex = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = AcpAgentProfileStore.DEFAULT_CODEX_AGENT_ID,
                provider = provider,
                model = model,
            ),
        )
        assertEquals(provider.apiKey, codex.environment["OPENAI_API_KEY"])
        assertEquals(provider.baseUrl, codex.environment["OPENAI_BASE_URL"])
        assertEquals(model, codex.codexModel)
        assertEquals("https://llmapi.paratera.com/v1", codex.codexBaseUrl)

        val claude = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = CLAUDE_CODE_AGENT_ID,
                provider = provider,
                model = model,
            ),
        )
        assertEquals(provider.apiKey, claude.environment["ANTHROPIC_API_KEY"])
        assertEquals(provider.apiKey, claude.environment["ANTHROPIC_AUTH_TOKEN"])
        assertEquals(model, claude.environment["ANTHROPIC_MODEL"])
        assertEquals(model, claude.environment["ANTHROPIC_SMALL_FAST_MODEL"])

        val openCode = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = OPENCODE_AGENT_ID,
                provider = provider,
                model = model,
            ),
        )
        assertEquals(provider.apiKey, openCode.environment["OPENAI_API_KEY"])
        assertEquals(provider.baseUrl, openCode.environment["OPENAI_BASE_URL"])
        assertEquals("omnibot/GLM-5.1", openCode.openCodeModel)
        assertEquals("https://llmapi.paratera.com/v1", openCode.openCodeBaseUrl)
    }

    @Test
    fun missingProviderDoesNotInventCredentials() {
        val mapping = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = AcpAgentProfileStore.DEFAULT_CODEX_AGENT_ID,
                provider = null,
                model = "GLM-5.1",
            ),
        )

        assertTrue(mapping.environment.keys.none { it.endsWith("API_KEY") })
        assertEquals("GLM-5.1", mapping.codexModel)
        assertEquals("/root/.codex", mapping.environment["CODEX_HOME"])
    }

    @Test
    fun codexBaseUrlNormalizesOnlyTheOfficialV1Suffix() {
        assertEquals("https://example.com/v1", normalizeCodexBaseUrl("https://example.com"))
        assertEquals("https://example.com/v1", normalizeCodexBaseUrl("https://example.com/v1/"))
        assertEquals(
            "https://example.com/compatible-mode/v1",
            normalizeCodexBaseUrl("https://example.com/compatible-mode/v1"),
        )
    }

    @Test
    fun openCodeConfigUsesOfficialCustomProviderShape() {
        val config = buildOpenCodeConfigJson(
            model = "omnibot/GLM-5.1",
            baseUrl = "https://llmapi.paratera.com/v1"
        )
        assertTrue(config.contains("https://opencode.ai/config.json"))
        assertTrue(config.contains("@ai-sdk/openai-compatible"))
        assertTrue(config.contains("omnibot/GLM-5.1"))
        assertTrue(config.contains("{env:OPENAI_API_KEY}"))
    }

    @Test
    fun openCodeBaseUrlAcceptsLegacyChatEndpoint() {
        assertEquals(
            "https://example.com/v1",
            normalizeOpenCodeBaseUrl("https://example.com/v1/chat/completions")
        )
        assertEquals(
            "https://example.com/compatible-mode/v1",
            normalizeOpenCodeBaseUrl("https://example.com/compatible-mode/v1")
        )
    }
}
