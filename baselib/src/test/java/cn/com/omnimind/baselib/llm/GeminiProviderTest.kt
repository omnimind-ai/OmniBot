package cn.com.omnimind.baselib.llm

import org.junit.Assert.*
import org.junit.Test

class GeminiProviderTest {
    @Test fun protocolNormalizationRetainsExistingDefaultsAndGemini() {
        assertEquals("gemini_interactions", ProviderProtocol.normalize(" Gemini_Interactions "))
        assertEquals("anthropic", ProviderProtocol.normalize("anthropic"))
        assertEquals("deepseek", ProviderProtocol.normalize("deepseek"))
        assertEquals("openai_compatible", ProviderProtocol.normalize(null))
        assertEquals("openai_compatible", ProviderProtocol.normalize("unknown"))
        assertEquals("https://example.test/v1", ModelProviderConfigStore.normalizeBaseUrl("https://example.test/v1/interactions"))
        assertEquals("https://example.test/v1/interactions#", ModelProviderConfigStore.normalizeBaseUrl("https://example.test/v1/interactions#"))
    }

    @Test fun unknownModelsDoNotInheritGuessedThinkingOrVisionCapabilities() {
        val unknown = GeminiProvider.enrich(ProviderModelOption("gemini-future"))
        assertNull(unknown.reasoning)
        assertTrue(unknown.supportedReasoningLevels.isEmpty())
        assertNull(unknown.reasoningCanDisable)
        assertTrue(unknown.inputModalities.isEmpty())
        val known = GeminiProvider.enrich(ProviderModelOption("models/gemini-3.6-flash"))
        assertTrue("minimal" in known.supportedReasoningLevels)
        assertEquals(false, known.reasoningCanDisable)
        assertTrue("image" in known.inputModalities)
        assertFalse(ProviderCustomHeaderUtils.redactHeadersForLog(mapOf("x-goog-api-key" to "secret")).values.contains("secret"))
    }
}
