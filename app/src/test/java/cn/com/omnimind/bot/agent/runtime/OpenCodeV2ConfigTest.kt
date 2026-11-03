package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.ProviderModelOption
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class OpenCodeV2ConfigTest {
    @Test
    fun `managed V1 conversion preserves user data and is idempotent`() {
        val legacy = """{
            "mcp":{"files":{"type":"local","command":["files"]}},
            "permission":{"bash":"ask"},
            "provider":{
                "custom":{"npm":"@ai-sdk/openai","options":{"apiKey":"user-value"}},
                "omnibot":{"options":{"headers":{"X-User":"keep"},"timeout":1234,"setCacheKey":true},
                    "models":{"test":{"id":"upstream-test","options":{"temperature":0.3},
                    "provider":{"npm":"@ai-sdk/openai-compatible","api":"https://model.example/v1"},
                    "interleaved":{"field":"reasoning_content"},
                    "variants":{"high":{"reasoningEffort":"high"}},"tool_call":true}}}
            }}"""
        fun convert(source: String) = buildOpenCodeConfigJson("omnibot/test", "https://example.test/v1", source)
        val migrated = convert(legacy)
        val config = JsonParser.parseString(migrated).asJsonObject
        val provider = config["providers"].asJsonObject["omnibot"].asJsonObject
        val model = provider["models"].asJsonObject["test"].asJsonObject
        assertEquals("@opencode/ai/providers/openai-compatible", provider["package"].asString)
        assertEquals("keep", provider["headers"].asJsonObject["X-User"].asString)
        assertEquals(1234, provider["settings"].asJsonObject["timeout"].asInt)
        assertTrue(provider["compatibility"].asJsonObject["supportsPromptCacheKey"].asBoolean)
        assertEquals("upstream-test", model["modelID"].asString)
        assertEquals("aisdk:@ai-sdk/openai-compatible", model["package"].asString)
        assertEquals("https://model.example/v1", model["settings"].asJsonObject["baseURL"].asString)
        assertEquals("reasoning_content", model["compatibility"].asJsonObject["reasoningField"].asString)
        assertEquals(0.3, model["body"].asJsonObject["temperature"].asDouble, 0.0)
        assertEquals("high", model["variants"].asJsonArray.single().asJsonObject["id"].asString)
        assertTrue(model["capabilities"].asJsonObject["tools"].asBoolean)
        assertEquals("user-value", config["provider"].asJsonObject["custom"].asJsonObject["options"].asJsonObject["apiKey"].asString)
        assertTrue(config["mcp"].asJsonObject.has("files"))
        assertEquals("ask", config["permission"].asJsonObject["bash"].asString)
        assertEquals(migrated, convert(migrated))
    }

    @Test
    fun `native provider wins over stale legacy config and declared modalities are retained`() {
        val config = JsonParser.parseString(buildOpenCodeConfigJson(
            "omnibot/test", "https://example.test/v1",
            existingConfigJson = """{"policies":[{"action":"provider.use","resource":"custom","effect":"allow"}],
                "provider":{"omnibot":{"options":{"timeout":1}}},
                "providers":{"omnibot":{"settings":{"timeout":2},"headers":{"X-User":"keep"}}}}""",
            providerModels = listOf(ProviderModelOption("test", inputModalities = listOf("text", "image"),
                outputModalities = listOf("text"), inputLimit = 1000)),
        )).asJsonObject
        val provider = config["providers"].asJsonObject["omnibot"].asJsonObject
        assertEquals(2, provider["settings"].asJsonObject["timeout"].asInt)
        assertFalse(config.has("provider"))
        assertFalse(config.has("enabled_providers"))
        val model = provider["models"].asJsonObject["test"].asJsonObject
        assertEquals(listOf("text", "image"), model["capabilities"].asJsonObject["input"].asJsonArray.map { it.asString })
        assertEquals(1000, model["limit"].asJsonObject["input"].asInt)
    }

    @Test(expected = IllegalStateException::class)
    fun `invalid config is not silently replaced with a new empty file`() {
        buildOpenCodeConfigJson("omnibot/test", "https://example.test/v1", "[]")
    }
}
