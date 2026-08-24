package cn.com.omnimind.bot.manager

import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.bot.agent.AgentModelOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistsCoreManagerChatOnlyTest {

    @Test
    fun `resolveDirectAgentModelOverride preserves contextLimit from payload`() {
        val result = resolveDirectAgentModelOverride(
            raw = mapOf(
                "providerProfileId" to "provider-1",
                "modelId" to "claude-fable-5",
                "contextLimit" to "1000000"
            )
        ) { id ->
            ModelProviderProfile(
                id = id,
                name = "Provider One",
                baseUrl = "https://api.anthropic.com",
                apiKey = "secret",
                protocolType = "anthropic",
            )
        }

        org.junit.Assert.assertNotNull(result)
        assertEquals("Provider One", result?.providerProfileName)
        assertEquals(1000000, result?.contextLimit)
        assertEquals("https://api.anthropic.com", result?.apiBase)
        assertEquals("anthropic", result?.protocolType)
    }

    @Test
    fun `resolveDirectAgentModelOverride normalizes the shared provider projection`() {
        val result = resolveDirectAgentModelOverride(
            raw = mapOf(
                "providerProfileId" to " provider-1 ",
                "modelId" to " model-x ",
            )
        ) {
            ModelProviderProfile(
                id = it,
                name = " Provider One ",
                baseUrl = "https://example.com/v1/chat/completions",
                apiKey = " secret ",
                customHeaders = mapOf(" X-Request-Id " to " request-1 ", "Host" to "ignored"),
                protocolType = "OPENAI_COMPATIBLE",
                wireApi = "RESPONSES",
            )
        }

        assertEquals("provider-1", result?.providerProfileId)
        assertEquals("Provider One", result?.providerProfileName)
        assertEquals("model-x", result?.modelId)
        assertEquals("https://example.com", result?.apiBase)
        assertEquals("secret", result?.apiKey)
        assertEquals(mapOf("X-Request-Id" to "request-1"), result?.customHeaders)
        assertEquals("openai_compatible", result?.protocolType)
        assertEquals("responses", result?.wireApi)
    }

    @Test
    fun `normalizeReasoningEffort accepts supported values only`() {
        assertEquals("no", normalizeReasoningEffort("no"))
        assertEquals("no", normalizeReasoningEffort(" NO "))
        assertEquals("low", normalizeReasoningEffort(" low "))
        assertEquals("high", normalizeReasoningEffort("HIGH"))
        assertEquals("xhigh", normalizeReasoningEffort(" xhigh "))
        assertEquals("max", normalizeReasoningEffort("MAX"))
        assertNull(normalizeReasoningEffort("medium"))
        assertNull(normalizeReasoningEffort(""))
    }

    @Test
    fun `resolveAgentReasoningEffort defaults to max only for official deepseek targets`() {
        val officialDeepSeekOverride = AgentModelOverride(
            providerProfileId = "deepseek-official",
            providerProfileName = "DeepSeek",
            modelId = "deepseek-reasoner",
            apiBase = "https://api.deepseek.com/v1",
            apiKey = "secret",
            protocolType = "deepseek"
        )
        val nonDeepSeekProfile = ModelProviderProfile(
            id = "provider-1",
            name = "Provider One",
            baseUrl = "https://example.com/v1",
            apiKey = "secret",
            protocolType = "openai_compatible"
        )

        assertEquals("max", resolveAgentReasoningEffort(null, officialDeepSeekOverride))
        assertEquals(
            "max",
            resolveAgentReasoningEffort(
                reasoningEffort = null,
                modelOverride = null,
                fallbackProfile = ModelProviderProfile(
                    id = "deepseek-official",
                    name = "DeepSeek",
                    baseUrl = "https://api.deepseek.com",
                    apiKey = "secret",
                    protocolType = "deepseek"
                )
            )
        )
        assertEquals("low", resolveAgentReasoningEffort("low", officialDeepSeekOverride))
        assertEquals("no", resolveAgentReasoningEffort("no", officialDeepSeekOverride))
        assertNull(
            resolveAgentReasoningEffort(
                reasoningEffort = null,
                modelOverride = null,
                fallbackProfile = nonDeepSeekProfile
            )
        )
    }
}
