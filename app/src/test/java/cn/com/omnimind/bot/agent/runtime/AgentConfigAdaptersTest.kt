package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConfigAdaptersTest {
    private val provider = AgentProviderCredentials(
        baseUrl = "https://llmapi.paratera.com/v1",
        apiKey = "secret",
    )

    @Test
    fun sharedAgentModelRequiresAnExplicitProviderBinding() {
        assertEquals(
            "bound-model",
            resolveSharedAgentModel(
                boundProviderProfileId = "provider-1",
                boundModel = "bound-model",
            ),
        )
        assertEquals(
            null,
            resolveSharedAgentModel(
                boundProviderProfileId = null,
                boundModel = "bound-model",
            ),
        )
        assertEquals(
            null,
            resolveSharedAgentModel(
                boundProviderProfileId = "provider-1",
                boundModel = null,
            ),
        )
    }

    @Test
    fun legacyXiaowanAliasesAreRecognizedForMigration() {
        assertTrue(
            AcpAgentProfileStore.isLegacyXiaowanAlias(
                AcpAgentProfile(
                    id = "legacy-xiaowan-bot",
                    name = "旧版小万",
                    command = "legacy-xiaowan",
                ),
            ),
        )
        assertTrue(
            AcpAgentProfileStore.isLegacyXiaowanAlias(
                AcpAgentProfile(
                    id = "custom-xiaowan",
                    name = "小万",
                    command = "custom-agent",
                ),
            ),
        )
    }

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
        assertEquals(OpenAiWireApi.RESPONSES, codex.codexWireApi)

        val claude = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = CLAUDE_CODE_AGENT_ID,
                provider = provider,
                model = model,
            ),
        )
        assertEquals(provider.apiKey, claude.environment["ANTHROPIC_API_KEY"])
        assertEquals(provider.apiKey, claude.environment["ANTHROPIC_AUTH_TOKEN"])
        assertEquals(provider.baseUrl, claude.environment["ANTHROPIC_BASE_URL"])
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
    fun modelResolutionPrefersMatchingProviderChoices() {
        assertEquals(
            "bound-model",
            resolveAdapterModel(
                providerModelIds = listOf("first-model", "bound-model", "old-model"),
                boundModel = "bound-model",
            ),
        )
        assertEquals(
            null,
            resolveAdapterModel(
                providerModelIds = listOf("first-model", "old-model"),
                boundModel = "removed-model",
            ),
        )
        assertEquals(
            null,
            resolveAdapterModel(
                providerModelIds = listOf("first-model", "second-model"),
                boundModel = "removed-model",
            ),
        )
    }

    @Test
    fun modelResolutionRequiresProviderVerification() {
        assertEquals(
            null,
            resolveAdapterModel(
                providerModelIds = null,
                boundModel = "bound-model",
            ),
        )
        assertEquals(
            null,
            resolveAdapterModel(
                providerModelIds = emptyList(),
                boundModel = null,
            ),
        )
        assertEquals(
            null,
            resolveAdapterModel(
                providerModelIds = null,
                boundModel = null,
            ),
        )
        assertEquals(
            "qwen3.5-plus",
            resolveAdapterModel(
                providerModelIds = listOf("qwen3.5-plus"),
                boundModel = "qwen3.5-plus",
            ),
        )
    }

    @Test
    fun authoritativeProviderModelPayloadNeverUsesAcpDefaults() {
        val payload = buildAuthoritativeProviderModelPayload(
            providerModelIds = listOf("first-model", "deepseek-v4-pro"),
            boundModel = "deepseek-v4-pro",
        )

        assertEquals(
            listOf("first-model", "deepseek-v4-pro"),
            (payload["models"] as List<*>).map { (it as Map<*, *>) ["id"] },
        )
        assertEquals("deepseek-v4-pro", payload["currentModelId"])
        assertEquals(true, payload["modelConfigSupported"])
        assertTrue(
            (payload["models"] as List<*>).none {
                (it as Map<*, *>) ["id"] == "gpt-5.6-sol"
            },
        )
    }

    @Test
    fun acpLaunchPrefersTheSharedBindingOverStaleAdapterOverrides() {
        assertEquals(
            "shared-model",
            resolveAcpLaunchModel(
                providerModelIds = listOf("shared-model"),
                boundModel = "shared-model"
            )
        )
        assertEquals(
            "shared-model",
            resolveAcpLaunchModel(
                providerModelIds = listOf("shared-model"),
                boundModel = "shared-model"
            )
        )
        assertEquals(
            null,
            resolveAcpLaunchModel(
                providerModelIds = null,
                boundModel = null,
            )
        )
    }

    @Test
    fun adaptersDoNotUseOldModelOverrides() {
        listOf(
            AcpAgentProfileStore.DEEPSEEK_HARNESS_AGENT_ID,
            AcpAgentProfileStore.DEFAULT_CODEX_AGENT_ID,
            CLAUDE_CODE_AGENT_ID,
            OPENCODE_AGENT_ID,
        ).forEach { agentId ->
            val mapping = AgentConfigAdapterRegistry.map(
                AgentProviderMappingInput(
                    agentId = agentId,
                    provider = provider,
                    model = null,
                ),
            )
            when (agentId) {
                AcpAgentProfileStore.DEEPSEEK_HARNESS_AGENT_ID ->
                    assertEquals("", mapping.deepSeekConfig?.model)
                AcpAgentProfileStore.DEFAULT_CODEX_AGENT_ID ->
                    assertEquals(null, mapping.codexModel)
                CLAUDE_CODE_AGENT_ID ->
                    assertEquals(null, mapping.environment["ANTHROPIC_MODEL"])
                OPENCODE_AGENT_ID ->
                    assertEquals(null, mapping.openCodeModel)
            }
        }

        val noPreviousModel = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = AcpAgentProfileStore.DEFAULT_CODEX_AGENT_ID,
                provider = provider,
                model = null,
            ),
        )
        assertEquals(null, noPreviousModel.codexModel)
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
    fun codexConfigUsesTheProviderWireApiAndSelectedModel() {
        val chatConfig = buildCodexConfigToml(
            baseUrl = "https://example.com/v1",
            model = "deepseek-v4-pro",
            wireApi = "chat_completions",
            modelCatalogPath = "/root/.codex/provider-model-catalog.json",
        )
        assertTrue(chatConfig.contains("model = \"deepseek-v4-pro\""))
        assertTrue(chatConfig.contains("wire_api = \"chat\""))
        assertTrue(chatConfig.contains("model_catalog_json = \"/root/.codex/provider-model-catalog.json\""))
        assertTrue(!chatConfig.contains("wire_api = \"responses\""))

        val responsesConfig = buildCodexConfigToml(
            baseUrl = "https://example.com/v1",
            model = "gpt-5.6-sol",
            wireApi = "responses",
        )
        assertTrue(responsesConfig.contains("model = \"gpt-5.6-sol\""))
        assertTrue(responsesConfig.contains("wire_api = \"responses\""))
    }

    @Test
    fun codexCatalogContainsProviderModelsWithoutExternalMetadata() {
        val catalog = JsonParser.parseString(
            buildCodexModelCatalogJson(
                listOf(
                    ProviderModelOption(
                        id = "deepseek-v4-pro",
                        displayName = "deepseek-v4-pro",
                        contextLimit = 128000,
                        outputLimit = 8192,
                    ),
                ),
            ),
        ).asJsonObject
        val model = catalog.getAsJsonArray("models").single().asJsonObject

        assertEquals("deepseek-v4-pro", model["slug"].asString)
        assertEquals(128000, model["context_window"].asInt)
        assertEquals(128000, model["max_context_window"].asInt)
        assertTrue(model["base_instructions"].asString.isNotBlank())
        assertEquals("list", model["visibility"].asString)
        assertEquals(false, model["supports_parallel_tool_calls"].asBoolean)
        assertEquals("medium", model["default_reasoning_level"].asString)
        assertEquals(
            listOf("medium"),
            model["supported_reasoning_levels"].asJsonArray.map {
                it.asJsonObject["effort"].asString
            },
        )
    }

    @Test
    fun codexCatalogUsesCodexDefaultEffortWhenProviderOmitsEffortList() {
        val catalog = JsonParser.parseString(
            buildCodexModelCatalogJson(
                listOf(
                    ProviderModelOption(
                        id = "deepseek-v4-pro",
                    ),
                ),
            ),
        ).asJsonObject
        val model = catalog.getAsJsonArray("models").single().asJsonObject

        assertEquals("medium", model["default_reasoning_level"].asString)
        assertEquals(
            listOf("medium"),
            model["supported_reasoning_levels"].asJsonArray.map {
                it.asJsonObject["effort"].asString
            },
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

    @Test
    fun claudeCodeUsesAlibabaOfficialAnthropicEndpoint() {
        assertEquals(
            "https://dashscope.aliyuncs.com/apps/anthropic",
            normalizeClaudeCodeBaseUrl(
                "https://dashscope.aliyuncs.com/compatible-mode/v1"
            )
        )
        assertEquals(
            "https://coding.dashscope.aliyuncs.com/apps/anthropic",
            normalizeClaudeCodeBaseUrl("https://coding.dashscope.aliyuncs.com/v1")
        )
    }

    @Test
    fun claudeCodeLeavesUnknownProviderEndpointUntouched() {
        assertEquals(
            "https://llmapi.paratera.com/v1",
            normalizeClaudeCodeBaseUrl("https://llmapi.paratera.com/v1")
        )
    }
}
