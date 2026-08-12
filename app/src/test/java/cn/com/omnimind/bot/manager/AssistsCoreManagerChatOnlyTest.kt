package cn.com.omnimind.bot.manager

import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.assists.api.bean.TaskParams
import cn.com.omnimind.bot.agent.AgentModelOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AssistsCoreManagerChatOnlyTest {

    @Test
    fun `prepareChatTaskContent prepends CHAT prompt only for chat_only mode`() {
        val content = listOf(
            linkedMapOf<String, Any>(
                "role" to "user",
                "content" to "hello"
            )
        )

        val chatOnlyResult = prepareChatTaskContent(
            content = content,
            conversationMode = CHAT_ONLY_MODE,
            chatPromptContent = "# CHAT\nbe helpful\n"
        )
        val normalResult = prepareChatTaskContent(
            content = content,
            conversationMode = "normal",
            chatPromptContent = "# CHAT\nbe helpful\n"
        )

        assertEquals(2, chatOnlyResult.size)
        assertEquals("system", chatOnlyResult.first()["role"])
        assertEquals("# CHAT\nbe helpful\n", chatOnlyResult.first()["content"])
        assertEquals(content.first(), chatOnlyResult[1])
        assertEquals(content, normalResult)
    }

    @Test
    fun `resolveChatTaskModelOverride uses configured provider profile`() {
        val result = resolveChatTaskModelOverride(
            raw = mapOf(
                "providerProfileId" to "provider-1",
                "modelId" to "gpt-5.4-mini"
            )
        ) { id ->
            if (id != "provider-1") {
                return@resolveChatTaskModelOverride null
            }
            ModelProviderProfile(
                id = "provider-1",
                name = "Provider One",
                baseUrl = "https://example.com/v1",
                apiKey = "secret",
                protocolType = "openai_compatible",
                destinationConsentValid = true
            )
        }

        assertNotNull(result)
        assertEquals("provider-1", result?.providerProfileId)
        assertEquals("gpt-5.4-mini", result?.modelId)
        assertEquals("https://example.com/v1", result?.apiBase)
        assertEquals("secret", result?.apiKey)
        assertEquals("openai_compatible", result?.protocolType)
        assertNull(result?.contextLimit)
    }

    @Test
    fun `resolveChatTaskModelOverride preserves contextLimit from payload`() {
        val result = resolveChatTaskModelOverride(
            raw = mapOf(
                "providerProfileId" to "provider-1",
                "modelId" to "claude-fable-5",
                "contextLimit" to 1000000
            )
        ) { id ->
            ModelProviderProfile(
                id = id,
                name = "Provider One",
                baseUrl = "https://api.anthropic.com",
                apiKey = "secret",
                protocolType = "anthropic",
                destinationConsentValid = true
            )
        }

        assertNotNull(result)
        assertEquals(1000000, result?.contextLimit)
    }

    @Test
    fun `resolveChatTaskModelOverride falls back when provider profile is missing or invalid`() {
        val missingProfileResult = resolveChatTaskModelOverride(
            raw = mapOf(
                "providerProfileId" to "missing-provider",
                "modelId" to "gpt-5.4-mini"
            )
        ) { _ -> null }

        val invalidProfileResult = resolveChatTaskModelOverride(
            raw = mapOf(
                "providerProfileId" to "provider-2",
                "modelId" to "gpt-5.4-mini"
            )
        ) { id ->
            ModelProviderProfile(
                id = id,
                name = "Provider Two",
                baseUrl = "",
                apiKey = "secret"
            )
        }

        assertNull(missingProfileResult)
        assertNull(invalidProfileResult)
    }

    @Test
    fun `extractChatTaskTextPayload parses OpenAI stream chunks`() {
        val chunk = """
            {"choices":[{"delta":{"content":"hello from pure chat"}}]}
        """.trimIndent()

        val result = extractChatTaskTextPayload(chunk)

        assertEquals("hello from pure chat", result)
    }

    @Test
    fun `extractChatTaskTextPayload ignores reasoning-only json chunks`() {
        val chunk = """
            {"choices":[{"delta":{"reasoning_content":"先分析一下问题。"}}]}
        """.trimIndent()

        val result = extractChatTaskTextPayload(chunk)

        assertEquals("", result)
    }

    @Test
    fun `extractChatTaskTextPayload ignores finish_reason control chunks`() {
        val chunk = """
            {"choices":[{"finish_reason":"stop"}]}
        """.trimIndent()

        val result = extractChatTaskTextPayload(chunk)

        assertEquals("", result)
    }

    @Test
    fun `extractChatTaskTextPayload preserves array chunk order`() {
        val chunk = """
            [
              {"choices":[{"delta":{"role":"assistant","content":"就是"}}]},
              {"choices":[{"delta":{"content":"这两个"}}]},
              {"choices":[{"delta":{"content":"字"}}]},
              {"choices":[{"delta":{"content":"。"}}]},
              {"choices":[{"delta":{"content":"不然"}}]},
              {"choices":[{"delta":{"content":"呢"}}]},
              {"choices":[{"delta":{"content":"，"}}]},
              {"choices":[{"delta":{"content":"你想"}}]},
              {"choices":[{"delta":{"content":"听"}}]},
              {"choices":[{"delta":{"content":"哪个"}}]},
              {"choices":[{"delta":{"content":"？"}}]},
              {"choices":[{"delta":{},"finish_reason":"stop"}]}
            ]
        """.trimIndent()

        val result = extractChatTaskTextPayload(chunk)

        assertEquals("就是这两个字。不然呢，你想听哪个？", result)
    }

    @Test
    fun `extractChatTaskTextPayload preserves whitespace and punctuation in array chunks`() {
        val chunk = """
            [
              {"choices":[{"delta":{"content":"Hello"}}]},
              {"choices":[{"delta":{"content":","}}]},
              {"choices":[{"delta":{"content":" "}}]},
              {"choices":[{"delta":{"content":"world"}}]},
              {"choices":[{"delta":{"content":"!"}}]},
              {"choices":[{"delta":{"content":"\n"}}]},
              {"choices":[{"delta":{"content":"  Next line"}}]},
              {"choices":[{"delta":{},"finish_reason":"stop"}]}
            ]
        """.trimIndent()

        val result = extractChatTaskTextPayload(chunk)

        assertEquals("Hello, world!\n  Next line", result)
    }

    @Test
    fun `extractChatTaskPromptTokens parses usage chunk`() {
        val chunk = """
            {"choices":[{"delta":{"content":"hello"}}],"usage":{"prompt_tokens":4096}}
        """.trimIndent()

        val result = extractChatTaskPromptTokens(chunk)

        assertEquals(4096, result)
    }

    @Test
    fun `chatModelOverrideToAgentModelOverride preserves chat override transport fields`() {
        val result = chatModelOverrideToAgentModelOverride(
            TaskParams.ChatModelOverride(
                providerProfileId = "provider-1",
                modelId = "gpt-5.4-mini",
                apiBase = "https://example.com/v1",
                apiKey = "secret",
                protocolType = "openai_compatible",
                contextLimit = 1000000
            )
        )

        assertNotNull(result)
        assertEquals("provider-1", result?.providerProfileId)
        assertEquals("gpt-5.4-mini", result?.modelId)
        assertEquals("https://example.com/v1", result?.apiBase)
        assertEquals("secret", result?.apiKey)
        assertEquals("openai_compatible", result?.protocolType)
        assertEquals(1000000, result?.contextLimit)
    }

    @Test
    fun `resolvePromptTokenThresholdFallback prefers stored threshold then model contextLimit`() {
        val override = TaskParams.ChatModelOverride(
            providerProfileId = "provider-1",
            modelId = "claude-fable-5",
            apiBase = "https://api.anthropic.com",
            apiKey = "secret",
            protocolType = "anthropic",
            contextLimit = 1000000
        )

        assertEquals(256000, resolvePromptTokenThresholdFallback(256000, override))
        assertEquals(1000000, resolvePromptTokenThresholdFallback(null, override))
        assertEquals(
            128000,
            resolvePromptTokenThresholdFallback(
                null,
                override.copy(contextLimit = null)
            )
        )
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
        assertEquals(
            "low",
            resolveAgentReasoningEffort("low", officialDeepSeekOverride)
        )
        assertEquals(
            "no",
            resolveAgentReasoningEffort("no", officialDeepSeekOverride)
        )
        assertNull(
            resolveAgentReasoningEffort(
                reasoningEffort = null,
                modelOverride = null,
                fallbackProfile = nonDeepSeekProfile
            )
        )
    }
}
