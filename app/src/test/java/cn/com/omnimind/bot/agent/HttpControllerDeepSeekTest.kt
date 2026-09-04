package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.DeepSeekProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpControllerDeepSeekTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `official deepseek body preserves low effort in thinking mode`() {
        val payload = applyOfficialDeepSeekThinkingMode(
            """
                {
                  "model": "deepseek-v4-pro",
                  "messages": [{"role": "user", "content": "hello"}],
                  "stream": true,
                  "max_completion_tokens": 1024,
                  "prompt_cache_key": "unsupported-by-official-deepseek",
                  "reasoning_effort": "low",
                  "temperature": 0.7,
                  "top_p": 0.8
                }
            """.trimIndent()
        )

        val root = json.parseToJsonElement(payload).jsonObject
        assertEquals("enabled", root["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("low", root["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("1024", root["max_tokens"]?.jsonPrimitive?.content)
        assertFalse(root.containsKey("max_completion_tokens"))
        assertFalse(root.containsKey("enable_thinking"))
        assertFalse(root.containsKey("prompt_cache_key"))
        assertFalse(root.containsKey("temperature"))
        assertFalse(root.containsKey("top_p"))
    }

    @Test
    fun `official deepseek body normalizes medium and xhigh to high`() {
        listOf("medium", "high", "xhigh").forEach { effort ->
            val payload = applyOfficialDeepSeekThinkingMode(
                """
                    {
                      "model": "deepseek-v4-pro",
                      "messages": [{"role": "user", "content": "hello"}],
                      "reasoning_effort": "$effort"
                    }
                """.trimIndent()
            )
            val root = json.parseToJsonElement(payload).jsonObject
            assertEquals("high", root["reasoning_effort"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `official deepseek body disables thinking from enable thinking flag`() {
        val payload = applyOfficialDeepSeekThinkingMode(
            """
                {
                  "model": "deepseek-chat",
                  "messages": [{"role": "user", "content": "hello"}],
                  "enable_thinking": false,
                  "reasoning_effort": "high"
                }
            """.trimIndent()
        )

        val root = json.parseToJsonElement(payload).jsonObject
        assertEquals("disabled", root["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse(root.containsKey("enable_thinking"))
        assertFalse(root.containsKey("reasoning_effort"))
    }

    @Test
    fun `official deepseek body disables thinking from canonical none effort`() {
        val payload = applyOfficialDeepSeekThinkingMode(
            """
                {
                  "model": "deepseek-v4-flash",
                  "messages": [{"role": "user", "content": "hello"}],
                  "reasoning_effort": "none"
                }
            """.trimIndent()
        )

        val root = json.parseToJsonElement(payload).jsonObject
        assertEquals("disabled", root["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse(root.containsKey("reasoning_effort"))
    }

    @Test
    fun `official deepseek body maps xhigh effort to high`() {
        val payload = applyOfficialDeepSeekThinkingMode(
            """
                {
                  "model": "deepseek-reasoner",
                  "messages": [{"role": "user", "content": "hello"}],
                  "reasoning_effort": "xhigh"
                }
            """.trimIndent()
        )

        val root = json.parseToJsonElement(payload).jsonObject
        assertEquals("enabled", root["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("high", root["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `deepseek official adapter is scoped to api host`() {
        assertTrue(DeepSeekProvider.isOfficialBaseUrl("https://api.deepseek.com"))
        assertTrue(DeepSeekProvider.isOfficialBaseUrl("https://api.deepseek.com/v1"))
        assertFalse(DeepSeekProvider.isOfficialBaseUrl("https://proxy.example.com/deepseek"))
        assertFalse(DeepSeekProvider.isOfficialBaseUrl("https://api.deepseek.com/custom"))
    }

    @Test
    fun `deepseek route exposes one shared set of wire capabilities`() {
        val v4 = DeepSeekProvider.requestCapabilities(
            protocolType = "deepseek",
            apiBase = "https://api.deepseek.com/v1",
            model = "deepseek-v4-flash",
        )
        assertFalse(v4.supportsExplicitAutoToolChoice)
        assertTrue(v4.requiresReasoningContentForToolCalls)
        assertFalse(v4.requiresAnthropicThinkingReplay)
        assertFalse(v4.supportsResponsesPromptCacheKey)
        assertFalse(v4.supportsResponsesParallelToolCalls)
        assertFalse(v4.supportsVisionInput == true)

        val vision = DeepSeekProvider.requestCapabilities(
            protocolType = "deepseek",
            apiBase = "https://api.deepseek.com",
            model = "deepseek-v4-flash-vision-exp",
        )
        assertTrue(vision.supportsVisionInput == true)

        val proxied = DeepSeekProvider.requestCapabilities(
            protocolType = "deepseek",
            apiBase = "https://proxy.example.com/v1",
            model = "deepseek-v4-flash",
        )
        assertEquals(null, proxied.supportsVisionInput)

        val anthropic = DeepSeekProvider.requestCapabilities(
            protocolType = "anthropic",
            apiBase = "https://api.anthropic.com",
            model = "claude-sonnet",
        )
        assertTrue(anthropic.supportsExplicitAutoToolChoice)
        assertFalse(anthropic.requiresReasoningContentForToolCalls)
        assertTrue(anthropic.requiresAnthropicThinkingReplay)
    }

    private fun applyOfficialDeepSeekThinkingMode(requestBodyJson: String): String {
        val method = HttpController::class.java.getDeclaredMethod(
            "applyOfficialDeepSeekThinkingMode",
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(HttpController, requestBodyJson) as String
    }
}
