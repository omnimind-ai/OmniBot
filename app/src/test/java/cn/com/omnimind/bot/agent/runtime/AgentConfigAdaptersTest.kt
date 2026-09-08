package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.DeepSeekProvider
import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConfigAdaptersTest {
    @Test
    fun harnessModelSelectionUsesAdvertisedValuesWithoutLosingModelNamespace() {
        assertEquals("omnibot/org/model", AcpHarnessAdapters.openCode.resolveModelValue(
            "org/model", listOf("omnibot/org/model")))
        assertEquals(null, AcpHarnessAdapters.openCode.resolveModelValue("model", listOf("other/model")))
        assertEquals("[\"provider\",\"org/model\"]", AcpHarnessAdapters.deepSeekHarness.resolveModelValue(
            "org/model", listOf("[\"provider\",\"org/model\"]")))
        assertEquals(null, AcpHarnessAdapters.deepSeekHarness.resolveModelValue(
            "model", listOf("[\"a\",\"model\"]", "[\"b\",\"model\"]")))
        assertEquals(null, AcpHarnessAdapters.claudeCode.resolveModelValue("model", listOf("other")))
        assertEquals("model", AcpHarnessAdapters.codex.resolveModelValue("model", listOf("model")))
    }
    @Test
    fun codexNegotiatesOfficialFailureMetadataWithoutChangingOtherHarnesses() {
        val base = kotlinx.serialization.json.buildJsonObject {
            put("terminal_output", kotlinx.serialization.json.JsonPrimitive(true))
        }
        val meta = AcpHarnessAdapters.codex.clientCapabilityMeta(base)
        assertEquals(base["terminal_output"], meta["terminal_output"])
        assertTrue(meta.toString().contains("sessionFailure"))
        assertEquals(base, AcpHarnessAdapters.kimiCode.clientCapabilityMeta(base))
    }

    @Test
    fun claudeUsesOfficialTextOutputWithoutAdvertisingUnrenderedTerminalExtension() {
        val base = ACP_CLIENT_CAPABILITY_META
        val meta = AcpHarnessAdapters.claudeCode.clientCapabilityMeta(base)
        assertFalse(meta.containsKey("terminal_output"))
        assertEquals(kotlinx.serialization.json.JsonObject(base - "terminal_output"), meta)
        assertEquals("true", base["terminal_output"].toString())
        assertEquals(meta, AcpHarnessAdapters.claudeCode.clientCapabilityMeta(meta))
    }

    @Test
    fun codexUsesOnlyOwningResponseFailureNotWarningsOrChatText() {
        fun meta(severity: String, title: String) =
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"jetbrains":{"air":{"version":1,"sessionFailure":{"severity":"$severity","title":"$title"}}}}"""
            ) as kotlinx.serialization.json.JsonObject
        val failure = meta("error", "Request timed out")
        assertEquals("Request timed out", AcpHarnessAdapters.codex.promptFailure(failure))
        assertEquals(null, AcpHarnessAdapters.standard.promptFailure(failure))
        assertEquals(null, AcpHarnessAdapters.codex.promptFailure(meta("warning", "Retrying")))
        assertEquals(null, AcpHarnessAdapters.codex.promptFailure(null))
        assertEquals("Assistant request failed.", AcpHarnessAdapters.codex.promptFailure(meta("error", "")))
    }

    @Test
    fun openCodeReceivesDeclaredCapabilitiesWithoutInventingReasoningLevels() {
        val root = JsonParser.parseString(buildOpenCodeConfigJson(
            model = "omnibot/model-a", baseUrl = "https://fixture.invalid/v1",
            providerModels = listOf(ProviderModelOption(id = "model-a", reasoning = true,
                toolCall = true, temperature = false, contextLimit = 32000, outputLimit = 4000)),
        )).asJsonObject
        val model = root.getAsJsonObject("provider").getAsJsonObject("omnibot")
            .getAsJsonObject("models").getAsJsonObject("model-a")
        assertTrue(model["reasoning"].asBoolean)
        assertTrue(model["tool_call"].asBoolean)
        assertFalse(model["temperature"].asBoolean)
        assertEquals(32000, model.getAsJsonObject("limit")["context"].asInt)
        assertEquals(4000, model.getAsJsonObject("limit")["output"].asInt)
        assertFalse(model.has("variants"))
    }

    @Test
    fun claudeSdkReceivesRootWithoutDuplicatingV1Messages() {
        for (suffix in listOf("", "/v1", "/v1/", "/v1/messages", "/messages", "/v1/chat/completions")) {
            assertEquals("https://gateway.example/anthropic",
                normalizeClaudeCodeBaseUrl("https://gateway.example/anthropic$suffix"))
        }
    }

    @Test
    fun openCodeCatalogRefreshRemovesStaleManagedModelsAndKeepsUserSettings() {
        val root = JsonParser.parseString(buildOpenCodeConfigJson(
            model = "omnibot/current", baseUrl = "https://gateway.example/v1",
            providerModels = listOf(ProviderModelOption(id = "current")),
            existingConfigJson = """{"provider":{"omnibot":{"models":{"stale":{},"current":{"options":{"temperature":0.3}}}},"custom":{"models":{"mine":{}}}}}""",
        )).asJsonObject
        val providers = root["provider"].asJsonObject
        val models = providers["omnibot"].asJsonObject["models"].asJsonObject
        assertEquals(setOf("current"), models.keySet())
        assertEquals(0.3, models["current"].asJsonObject["options"].asJsonObject["temperature"].asDouble, 0.0)
        assertTrue(providers["custom"].asJsonObject["models"].asJsonObject.has("mine"))
    }

    @Test
    fun kimiAnthropicProtocolWinsOverStaleOpenAiWireSetting() {
        val input = AgentProviderMappingInput(
            agentId = "kimi-code-acp", harnessAdapter = AcpHarnessAdapters.kimiCode,
            provider = AgentProviderCredentials("https://gateway.example/anthropic", "test-key",
                protocolType = "anthropic", wireApi = "responses"), model = "org/model-a",
        )
        val mapping = AgentConfigAdapterRegistry.map(input)
        assertFalse(mapping.environment.containsKey("KIMI_MODEL_NAME"))
        val content = AgentConfigAdapterRegistry.launchConfigWrites(input, mapping, emptyList(), "").single().content
        assertTrue(content.contains("type = \"anthropic\""))
        assertTrue(content.contains("model = \"org/model-a\""))
    }

    @Test
    fun kimiCatalogPreservesProviderIdsMetadataAndCurrentBinding() {
        val config = buildKimiCodeManagedFiles(
            AgentProviderCredentials("https://gateway.example/v1", "test-key", wireApi = "chat_completions"),
            "current", providerModels = listOf(
                ProviderModelOption(id = "org/model.a", displayName = "Model A", contextLimit = 64000),
                ProviderModelOption(id = "org/model.a"),
                ProviderModelOption(id = "model-b", reasoning = false, inputModalities = listOf("text")),
            ),
        ).getValue(KIMI_CODE_CONFIG_PATH)
        assertTrue(config.contains("[models.\"org/model.a\"]"))
        assertEquals(1, Regex("display_name = \"Model A\"").findAll(config).count())
        assertTrue(config.contains("max_context_size = 64000"))
        assertTrue(config.contains("capabilities = []"))
        assertTrue(config.contains("default_model = \"current\""))
        assertTrue(config.contains("[models.\"current\"]"))
    }

    @Test
    fun codexRejectsAnthropicConfigurationInsteadOfInventingResponsesSupport() {
        val failure = runCatching {
            AgentConfigAdapterRegistry.map(AgentProviderMappingInput(
                agentId = "codex-acp", harnessAdapter = AcpHarnessAdapters.codex,
                provider = AgentProviderCredentials("https://gateway.example/anthropic", "test-key",
                    protocolType = "anthropic"), model = "model-a",
            ))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("Responses"))
        assertFalse(failure.message!!.contains("test-key"))
    }

    @Test
    fun openCodeUsesTheConfiguredWireAcrossProtocolSwitches() {
        var saved = "{}"
        val cases = listOf(
            Triple("openai_compatible", "chat_completions", "@ai-sdk/openai-compatible"),
            Triple("openai_compatible", "responses", "@ai-sdk/openai"),
            Triple("anthropic", "chat_completions", "@ai-sdk/anthropic"),
            Triple("openai_compatible", "chat_completions", "@ai-sdk/openai-compatible"),
        )
        for ((protocol, wire, npm) in cases) {
            val input = AgentProviderMappingInput(
                agentId = "opencode-acp",
                provider = AgentProviderCredentials("https://gateway.example/v1", "test-secret",
                    protocolType = protocol, wireApi = wire),
                model = "org/model-a", harnessAdapter = AcpHarnessAdapters.openCode,
            )
            saved = AgentConfigAdapterRegistry.launchConfigWrites(
                input, AgentConfigAdapterRegistry.map(input),
                listOf(ProviderModelOption(id = "org/model-a")), saved,
            ).single().content
            val root = JsonParser.parseString(saved).asJsonObject
            val provider = root["provider"].asJsonObject["omnibot"].asJsonObject
            assertEquals(npm, provider["npm"].asString)
            assertEquals("omnibot/org/model-a", root["model"].asString)
            assertTrue(provider["models"].asJsonObject.has("org/model-a"))
            assertFalse(saved.contains("test-secret"))
        }
    }

    @Test
    fun openCodePreservesRawNamespacedModelIds() {
        val root = JsonParser.parseString(buildOpenCodeConfigJson(
            model = "org/model-a", baseUrl = "https://gateway.example/v1",
        )).asJsonObject
        val models = root["provider"].asJsonObject["omnibot"].asJsonObject["models"].asJsonObject
        assertTrue(models.has("org/model-a"))
        assertFalse(models.has("model-a"))
    }

    @Test
    fun openCodeAnthropicEndpointIsNormalizedOnce() {
        for (suffix in listOf("", "/v1", "/v1/messages", "/messages")) {
            assertEquals("https://gateway.example/anthropic/v1",
                normalizeOpenCodeBaseUrl("https://gateway.example/anthropic$suffix"))
        }
    }

    @Test
    fun kimiProtocolSwitchReplacesOfficialCatalogWithoutEnvironmentOverride() {
        val input = AgentProviderMappingInput(
            agentId = "kimi-code",
            provider = AgentProviderCredentials("https://gateway.example/v1/responses", "key\"quoted",
                wireApi = "responses", customHeaders = mapOf("X-Route" to "mobile")),
            model = "dispatch-model", harnessAdapter = AcpHarnessAdapters.kimiCode,
        )
        val mapping = AgentConfigAdapterRegistry.map(input)
        assertFalse(mapping.environment.containsKey("KIMI_MODEL_NAME"))
        val write = AgentConfigAdapterRegistry.launchConfigWrites(input, mapping, emptyList(), "").single()
        assertEquals(KIMI_CODE_CONFIG_PATH, write.path)
        assertTrue(write.content.contains("type = \"openai_responses\""))
        assertTrue(write.content.contains("base_url = \"https://gateway.example/v1\""))
        assertTrue(write.content.contains("model = \"dispatch-model\""))
        assertTrue(write.content.contains("api_key = \"key\\\"quoted\""))
        assertTrue(write.content.contains("\"X-Route\" = \"mobile\""))
        val chat = input.copy(provider = input.provider!!.copy(wireApi = "chat_completions"), model = "other-model")
        val chatMapping = AgentConfigAdapterRegistry.map(chat)
        assertFalse(chatMapping.environment.containsKey("KIMI_MODEL_NAME"))
        val chatConfig = AgentConfigAdapterRegistry.launchConfigWrites(chat, chatMapping, emptyList(), write.content).single().content
        assertTrue(chatConfig.contains("type = \"openai\""))
        assertTrue(chatConfig.contains("model = \"other-model\""))
        assertFalse(chatConfig.contains("dispatch-model"))
    }

    @Test
    fun deepSeekAcpUsesOfficialProviderPatchWithoutPersistingCredentials() {
        val input = AgentProviderMappingInput(
            agentId = "deepseek-harness-acp",
            provider = AgentProviderCredentials("https://gateway.example/v1", "secret-key",
                customHeaders = mapOf("X-Route" to "mobile")),
            model = "model-b", harnessAdapter = AcpHarnessAdapters.deepSeekHarness,
        )
        val mapping = AgentConfigAdapterRegistry.map(input)
        val patch = AgentConfigAdapterRegistry.launchConfigWrites(
            input, mapping, emptyList(), "",
        ).single().content
        assertTrue(patch.contains("- id: llm-pi-ai"))
        assertTrue(patch.contains("- id: acp"))
        assertTrue(patch.contains("\"model\":\"model-b\""))
        assertTrue(patch.contains("\"X-Route\":\"mobile\""))
        assertFalse(patch.contains("secret-key"))
        assertEquals("secret-key", mapping.environment["OMNIBOT_DSH_API_KEY"])
    }
    @Test
    fun openCodeMappingPreservesExplicitProviderSelection() {
        val config = JsonParser.parseString(buildOpenCodeConfigJson(
            model = "omnibot/model-a", baseUrl = "https://example.test/v1",
            existingConfigJson = """{"enabled_providers":["omnibot","custom"],"permission":{"bash":"ask"}}""",
        )).asJsonObject
        assertEquals(listOf("omnibot", "custom"), config.getAsJsonArray("enabled_providers").map { it.asString })
        assertEquals("ask", config.getAsJsonObject("permission")["bash"].asString)
    }
    @Test
    fun openCodeLaunchIncludesTheSharedProviderCatalog() {
        val input = AgentProviderMappingInput(
            agentId = "opencode-acp", provider = AgentProviderCredentials("https://example.test/v1", "test-key"),
            model = "model-a", harnessAdapter = AcpHarnessAdapters.openCode,
        )
        val write = AgentConfigAdapterRegistry.launchConfigWrites(
            input = input, mapping = AgentConfigAdapterRegistry.map(input),
            providerModels = listOf(ProviderModelOption(id = "model-a"), ProviderModelOption(id = "model-b")),
            existingConfig = "{}",
        ).single()
        val config = JsonParser.parseString(write.content).asJsonObject
        assertTrue(config.getAsJsonObject("provider").getAsJsonObject("omnibot")
            .getAsJsonObject("models").has("model-b"))
    }
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
    fun knownLegacyIdentityMigratesButCustomDisplayNameDoesNot() {
        assertTrue(
            AcpAgentProfileStore.isLegacyXiaowanAlias(
                AcpAgentProfile(
                    id = "legacy-xiaowan-bot",
                    name = "旧版小万",
                    command = "legacy-xiaowan",
                ),
            ),
        )
        assertFalse(
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
                harnessAdapter = AcpHarnessAdapters.deepSeekHarness,
            ),
        )
        assertEquals("https://llmapi.paratera.com/v1", dsh.environment["DEEPSEEK_BASE_URL"])
        assertEquals("secret", dsh.environment["DEEPSEEK_API_KEY"])
        assertEquals(model, dsh.environment["DSH_MODEL"])

        val codex = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = AcpAgentProfileStore.CODEX_AGENT_ID,
                provider = provider,
                model = model,
                harnessAdapter = AcpHarnessAdapters.codex,
            ),
        )
        assertEquals(provider.apiKey, codex.environment["OPENAI_API_KEY"])
        assertEquals(provider.baseUrl, codex.environment["OPENAI_BASE_URL"])
        assertEquals(model, codex.codexModel)
        assertEquals("https://llmapi.paratera.com/v1", codex.codexBaseUrl)
        assertEquals(OpenAiWireApi.RESPONSES, codex.codexWireApi)

        val kimi = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = AcpAgentProfileStore.KIMI_CODE_AGENT_ID,
                provider = provider,
                model = model,
                harnessAdapter = AcpHarnessAdapters.kimiCode,
            ),
        )
        assertFalse(kimi.environment.containsKey("KIMI_MODEL_NAME"))
        assertFalse(kimi.environment.containsKey("KIMI_MODEL_API_KEY"))
        assertEquals(KIMI_CODE_HOME, kimi.environment["KIMI_CODE_HOME"])

        val claude = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = CLAUDE_CODE_AGENT_ID,
                provider = AgentProviderCredentials(
                    baseUrl = "https://llmapi.paratera.com/anthropic",
                    apiKey = provider.apiKey,
                    protocolType = "anthropic",
                ),
                model = model,
                harnessAdapter = AcpHarnessAdapters.claudeCode,
            ),
        )
        assertEquals(provider.apiKey, claude.environment["ANTHROPIC_API_KEY"])
        assertEquals(provider.apiKey, claude.environment["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("https://llmapi.paratera.com/anthropic", claude.environment["ANTHROPIC_BASE_URL"])
        assertEquals(model, claude.environment["ANTHROPIC_MODEL"])
        assertEquals(model, claude.environment["ANTHROPIC_SMALL_FAST_MODEL"])

        val openCode = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = "opencode-acp",
                provider = provider,
                model = model,
                harnessAdapter = AcpHarnessAdapters.openCode,
            ),
        )
        assertEquals(provider.apiKey, openCode.environment["OPENAI_API_KEY"])
        assertEquals(provider.baseUrl, openCode.environment["OPENAI_BASE_URL"])
        assertEquals("omnibot/GLM-5.1", openCode.openCodeModel)
        assertEquals("https://llmapi.paratera.com/v1", openCode.openCodeBaseUrl)
    }

    @Test
    fun providerMappingUsesHarnessCapabilityInsteadOfAdapterObjectIdentity() {
        val decoratedCodex = object : AcpHarnessAdapter by AcpHarnessAdapters.codex {}
        val mapping = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = AcpAgentProfileStore.CODEX_AGENT_ID,
                provider = AgentProviderCredentials(
                    baseUrl = " https://example.com/v1/chat/completions ",
                    apiKey = " secret ",
                    wireApi = "RESPONSES",
                ),
                model = " model-x ",
                harnessAdapter = decoratedCodex,
            ),
        )

        assertEquals("https://example.com/v1", mapping.environment["OPENAI_BASE_URL"])
        assertEquals("secret", mapping.environment["OPENAI_API_KEY"])
        assertEquals("model-x", mapping.codexModel)
        assertEquals(OpenAiWireApi.RESPONSES, mapping.codexWireApi)
    }

    @Test
    fun providerCredentialsNormalizeTransportFieldsAtTheAdapterBoundary() {
        val normalized = AgentProviderCredentials(
            baseUrl = " https://example.com/v1 ",
            apiKey = " secret ",
            wireApi = "chat-completions",
            customHeaders = mapOf(" X-Trace " to " request-id ", " " to "discarded"),
            protocolType = " OpenAI_COMPATIBLE ",
        ).normalized()

        assertEquals("https://example.com/v1", normalized.baseUrl)
        assertEquals("secret", normalized.apiKey)
        assertEquals(OpenAiWireApi.CHAT_COMPLETIONS, normalized.wireApi)
        assertEquals(mapOf("X-Trace" to "request-id"), normalized.customHeaders)
        assertEquals("openai_compatible", normalized.protocolType)
    }

    @Test
    fun providerCredentialsRejectWhitespaceInsideBaseUrl() {
        val failure = runCatching {
            AgentProviderCredentials(
                baseUrl = "https://example.com/model gateway",
                apiKey = "secret",
            ).normalized()
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun editableProviderHeadersReachEachOfficialAdapterSurface() {
        val configuredProvider = AgentProviderCredentials(
            baseUrl = "https://example.com/v1",
            apiKey = "secret",
            customHeaders = linkedMapOf(
                " X-Trace-Id " to " trace-1 ",
                "X-Region" to "cn",
                "Host" to "must-be-dropped",
            ),
        )

        val codex = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = AcpAgentProfileStore.CODEX_AGENT_ID,
                provider = configuredProvider,
                model = "model-a",
                harnessAdapter = AcpHarnessAdapters.codex,
            ),
        )
        assertEquals(
            mapOf(
                "X-Trace-Id" to "OMNIBOT_PROVIDER_HEADER_0",
                "X-Region" to "OMNIBOT_PROVIDER_HEADER_1",
            ),
            codex.codexEnvHttpHeaders,
        )
        assertEquals("trace-1", codex.environment["OMNIBOT_PROVIDER_HEADER_0"])
        assertEquals("cn", codex.environment["OMNIBOT_PROVIDER_HEADER_1"])
        val codexConfig = AgentConfigAdapterRegistry.launchConfigWrites(
            input = AgentProviderMappingInput(
                agentId = AcpAgentProfileStore.CODEX_AGENT_ID,
                provider = configuredProvider,
                model = "model-a",
                harnessAdapter = AcpHarnessAdapters.codex,
            ),
            mapping = codex,
            providerModels = listOf(ProviderModelOption(id = "model-a")),
            existingConfig = "",
        ).first().content
        assertTrue(codexConfig.contains("\"X-Trace-Id\" = \"OMNIBOT_PROVIDER_HEADER_0\""))

        val claude = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = CLAUDE_CODE_AGENT_ID,
                provider = configuredProvider.copy(
                    baseUrl = "https://example.com/anthropic",
                    protocolType = "anthropic",
                ),
                model = "model-a",
                harnessAdapter = AcpHarnessAdapters.claudeCode,
            ),
        )
        assertEquals(
            "X-Trace-Id: trace-1\nX-Region: cn",
            claude.environment["ANTHROPIC_CUSTOM_HEADERS"],
        )

        val kimi = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = AcpAgentProfileStore.KIMI_CODE_AGENT_ID,
                provider = configuredProvider,
                model = "model-a",
                harnessAdapter = AcpHarnessAdapters.kimiCode,
            ),
        )
        val kimiConfig = AgentConfigAdapterRegistry.launchConfigWrites(
            AgentProviderMappingInput(
                agentId = AcpAgentProfileStore.KIMI_CODE_AGENT_ID,
                provider = configuredProvider, model = "model-a", harnessAdapter = AcpHarnessAdapters.kimiCode,
            ), kimi, emptyList(), "",
        ).single().content
        assertTrue(kimiConfig.contains("\"X-Trace-Id\" = \"trace-1\""))
        assertTrue(kimiConfig.contains("\"X-Region\" = \"cn\""))
        assertFalse(kimiConfig.contains("must-be-dropped"))

        val openCode = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = "opencode-acp",
                provider = configuredProvider,
                model = "model-a",
                harnessAdapter = AcpHarnessAdapters.openCode,
            ),
        )
        val openCodeConfig = AgentConfigAdapterRegistry.launchConfigWrites(
            input = AgentProviderMappingInput(
                agentId = "opencode-acp",
                provider = configuredProvider,
                model = "model-a",
                harnessAdapter = AcpHarnessAdapters.openCode,
            ),
            mapping = openCode,
            providerModels = listOf(ProviderModelOption(id = "model-a")),
            existingConfig = "",
        ).single().content
        val openCodeHeaders = JsonParser.parseString(openCodeConfig)
            .asJsonObject["provider"].asJsonObject["omnibot"]
            .asJsonObject["options"].asJsonObject["headers"].asJsonObject
        assertEquals(
            "{env:OMNIBOT_PROVIDER_HEADER_0}",
            openCodeHeaders["X-Trace-Id"].asString,
        )
        assertEquals(
            "{env:OMNIBOT_PROVIDER_HEADER_1}",
            openCodeHeaders["X-Region"].asString,
        )
        assertTrue("Host" !in openCodeHeaders.entrySet().map { it.key })
    }

    @Test
    fun editableProviderChangesRecomputeMappingWithoutStaleValues() {
        val first = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = "opencode-acp",
                provider = AgentProviderCredentials(
                    baseUrl = "https://old.example.com/v1",
                    apiKey = "old-key",
                    customHeaders = mapOf("X-Old" to "old"),
                ),
                model = "old-model",
                harnessAdapter = AcpHarnessAdapters.openCode,
            ),
        )
        val second = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = "opencode-acp",
                provider = AgentProviderCredentials(
                    baseUrl = "https://new.example.com/v1",
                    apiKey = "new-key",
                    customHeaders = mapOf("X-New" to "new"),
                ),
                model = "new-model",
                harnessAdapter = AcpHarnessAdapters.openCode,
            ),
        )

        assertEquals("https://old.example.com/v1", first.openCodeBaseUrl)
        assertEquals("old-model", first.openCodeModel?.substringAfter('/'))
        assertEquals("https://new.example.com/v1", second.openCodeBaseUrl)
        assertEquals("new-model", second.openCodeModel?.substringAfter('/'))
        assertEquals("new-key", second.environment["OPENAI_API_KEY"])
        assertTrue("OMNIBOT_PROVIDER_HEADER_0" in second.environment)
        assertEquals("new", second.environment["OMNIBOT_PROVIDER_HEADER_0"])
    }

    @Test
    fun missingProviderDoesNotInventCredentials() {
        val mapping = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = AcpAgentProfileStore.CODEX_AGENT_ID,
                provider = null,
                model = "GLM-5.1",
                harnessAdapter = AcpHarnessAdapters.codex,
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
    fun acpLaunchKeepsExplicitBindingWhenProviderCatalogIsUnavailable() {
        assertEquals(
            "bound-model",
            resolveAcpLaunchModelWithBindingFallback(
                providerModelIds = null,
                boundModel = "bound-model",
            ),
        )
        assertEquals(
            "bound-model",
            resolveAcpLaunchModelWithBindingFallback(
                providerModelIds = emptyList(),
                boundModel = "bound-model",
            ),
        )
        assertEquals(
            null,
            resolveAcpLaunchModelWithBindingFallback(
                providerModelIds = listOf("new-model"),
                boundModel = "removed-model",
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
            AcpAgentProfileStore.CODEX_AGENT_ID,
            CLAUDE_CODE_AGENT_ID,
            "opencode-acp",
        ).forEach { agentId ->
            val mapping = AgentConfigAdapterRegistry.map(
                AgentProviderMappingInput(
                    agentId = agentId,
                    provider = provider,
                    model = null,
                    harnessAdapter = if (
                        agentId == AcpAgentProfileStore.DEEPSEEK_HARNESS_AGENT_ID
                    ) AcpHarnessAdapters.deepSeekHarness else AcpHarnessAdapters.standard,
                ),
            )
            when (agentId) {
                AcpAgentProfileStore.DEEPSEEK_HARNESS_AGENT_ID ->
                    assertEquals("", mapping.deepSeekConfig?.model)
                AcpAgentProfileStore.CODEX_AGENT_ID ->
                    assertEquals(null, mapping.codexModel)
                CLAUDE_CODE_AGENT_ID ->
                    assertEquals(null, mapping.environment["ANTHROPIC_MODEL"])
                "opencode-acp" ->
                    assertEquals(null, mapping.openCodeModel)
            }
        }

        val noPreviousModel = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = AcpAgentProfileStore.CODEX_AGENT_ID,
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
            "https://example.com/v1",
            normalizeCodexBaseUrl("https://example.com/v1/chat/completions"),
        )
        assertEquals(
            "https://example.com/v1",
            normalizeCodexBaseUrl("https://example.com/v1/responses"),
        )
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
        assertEquals("tokens", model["truncation_policy"].asJsonObject["mode"].asString)
        assertEquals(128000, model["truncation_policy"].asJsonObject["limit"].asInt)
        assertTrue(model["base_instructions"].asString.isNotBlank())
        assertEquals("list", model["visibility"].asString)
        assertEquals(false, model["supports_parallel_tool_calls"].asBoolean)
        assertEquals(
            listOf("text", "image"),
            model["input_modalities"].asJsonArray.map { it.asString },
        )
        assertTrue(model["default_reasoning_level"]?.isJsonNull != false)
        assertEquals(
            emptyList<String>(),
            model["supported_reasoning_levels"].asJsonArray.map {
                it.asJsonObject["effort"].asString
            },
        )
    }

    @Test
    fun codexCatalogUsesResolvedProviderVisionCapabilityWhenMetadataIsMissing() {
        val catalog = JsonParser.parseString(
            buildCodexModelCatalogJson(
                providerModels = listOf(
                    ProviderModelOption(id = "deepseek-v4-pro"),
                    ProviderModelOption(id = "deepseek-v4-flash-vision-exp"),
                ),
                provider = AgentProviderCredentials(
                    baseUrl = DeepSeekProvider.OFFICIAL_BASE_URL,
                    apiKey = "secret",
                    protocolType = DeepSeekProvider.PROTOCOL_TYPE,
                ),
            ),
        ).asJsonObject
        val models = catalog.getAsJsonArray("models")
            .associateBy { it.asJsonObject["slug"].asString }

        assertEquals(
            listOf("text"),
            models.getValue("deepseek-v4-pro")
                .asJsonObject["input_modalities"].asJsonArray.map { it.asString },
        )
        assertEquals(
            listOf("text", "image"),
            models.getValue("deepseek-v4-flash-vision-exp")
                .asJsonObject["input_modalities"].asJsonArray.map { it.asString },
        )
    }

    @Test
    fun codexCatalogKeepsExplicitModalitiesAheadOfRouteFallback() {
        val catalog = JsonParser.parseString(
            buildCodexModelCatalogJson(
                providerModels = listOf(
                    ProviderModelOption(
                        id = "deepseek-v4-flash-vision-exp",
                        inputModalities = listOf("text"),
                    ),
                ),
                provider = AgentProviderCredentials(
                    baseUrl = DeepSeekProvider.OFFICIAL_BASE_URL,
                    apiKey = "secret",
                    protocolType = DeepSeekProvider.PROTOCOL_TYPE,
                ),
            ),
        ).asJsonObject
        val model = catalog.getAsJsonArray("models").single().asJsonObject

        assertEquals(
            listOf("text"),
            model["input_modalities"].asJsonArray.map { it.asString },
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

        assertTrue(model["default_reasoning_level"]?.isJsonNull != false)
        assertEquals(
            emptyList<String>(),
            model["supported_reasoning_levels"].asJsonArray.map {
                it.asJsonObject["effort"].asString
            },
        )
    }

    @Test
    fun codexCatalogPreservesDeclaredEffortsAndDefaultWithoutAddingLevels() {
        val catalog = JsonParser.parseString(buildCodexModelCatalogJson(listOf(
            ProviderModelOption(id = "custom", supportedReasoningLevels = listOf("low", "high"),
                defaultReasoningLevel = "high"),
        ))).asJsonObject
        val model = catalog.getAsJsonArray("models").single().asJsonObject
        assertEquals("high", model["default_reasoning_level"].asString)
        assertEquals(listOf("low", "high"), model["supported_reasoning_levels"].asJsonArray
            .map { it.asJsonObject["effort"].asString })
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
        assertFalse(config.contains("\"context\""))
        assertFalse(config.contains("\"output\""))
    }

    @Test
    fun openCodeEditableHeadersReplaceOnlyPreviousAdapterValues() {
        val existing = """
            {
              "provider": {
                "omnibot": {
                  "options": {
                    "headers": {
                      "X-Old": "{env:OMNIBOT_PROVIDER_HEADER_0}",
                      "X-Keep": "keep-me"
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val config = JsonParser.parseString(
            buildOpenCodeConfigJson(
                model = "omnibot/model-a",
                baseUrl = "https://example.com/v1",
                existingConfigJson = existing,
                customHeaders = mapOf("X-New" to "new-value"),
            ),
        ).asJsonObject
        val headers = config["provider"].asJsonObject["omnibot"]
            .asJsonObject["options"].asJsonObject["headers"].asJsonObject

        assertTrue("X-Old" !in headers.entrySet().map { it.key })
        assertEquals("keep-me", headers["X-Keep"].asString)
        assertEquals(
            "{env:OMNIBOT_PROVIDER_HEADER_0}",
            headers["X-New"].asString,
        )
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
    fun claudeCodeUsesDeepSeekOfficialAnthropicEndpoint() {
        assertEquals(
            "https://api.deepseek.com/anthropic",
            normalizeClaudeCodeBaseUrl("https://api.deepseek.com/v1"),
        )
        val mapping = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = CLAUDE_CODE_AGENT_ID,
                provider = AgentProviderCredentials(
                    baseUrl = "https://api.deepseek.com/v1",
                    apiKey = "deepseek-key",
                ),
                model = "deepseek-v4-flash",
                harnessAdapter = AcpHarnessAdapters.claudeCode,
            ),
        )
        assertEquals(
            "https://api.deepseek.com/anthropic",
            mapping.environment["ANTHROPIC_BASE_URL"],
        )
    }

    @Test
    fun claudeCodeNormalizesSdkSuffixWithoutGuessingAnAnthropicEndpoint() {
        assertEquals(
            "https://llmapi.paratera.com",
            normalizeClaudeCodeBaseUrl("https://llmapi.paratera.com/v1")
        )
    }

    @Test
    fun claudeCodeMapsSharedProviderWithoutRejectingItsOpenAiLabel() {
        for (baseUrl in listOf("https://llmapi.paratera.com", "https://llmapi.paratera.com/v1")) {
            val selected = provider.copy(baseUrl = baseUrl,
                customHeaders = mapOf("X-Test-Tenant" to "selected-tenant"))
            val mapping = AgentConfigAdapterRegistry.map(
                AgentProviderMappingInput(
                    agentId = CLAUDE_CODE_AGENT_ID,
                    provider = selected,
                    model = "GLM-5.1",
                    harnessAdapter = AcpHarnessAdapters.claudeCode,
                )
            )
            assertEquals("https://llmapi.paratera.com", mapping.environment["ANTHROPIC_BASE_URL"])
            assertEquals(selected.apiKey, mapping.environment["ANTHROPIC_API_KEY"])
            assertEquals(selected.apiKey, mapping.environment["ANTHROPIC_AUTH_TOKEN"])
            assertEquals("GLM-5.1", mapping.environment["ANTHROPIC_MODEL"])
            assertEquals("GLM-5.1", mapping.environment["ANTHROPIC_SMALL_FAST_MODEL"])
            assertEquals("X-Test-Tenant: selected-tenant", mapping.environment["ANTHROPIC_CUSTOM_HEADERS"])
            assertEquals(baseUrl, selected.baseUrl)
            assertEquals(provider.protocolType, selected.protocolType)
        }
    }

    @Test
    fun claudeCodeProviderSwitchDoesNotReusePreviousCredentialsOrHeaders() {
        fun map(selected: AgentProviderCredentials, model: String) =
            AgentConfigAdapterRegistry.map(AgentProviderMappingInput(
                agentId = CLAUDE_CODE_AGENT_ID, provider = selected, model = model,
                harnessAdapter = AcpHarnessAdapters.claudeCode,
            )).environment
        val first = map(provider.copy(customHeaders = mapOf("X-Tenant" to "first")), "first-model")
        val second = map(provider.copy(baseUrl = "https://gateway.example/team/v1",
            apiKey = "second-test-key", customHeaders = emptyMap()), "org/second-model")
        assertEquals("https://gateway.example/team", second["ANTHROPIC_BASE_URL"])
        assertEquals("second-test-key", second["ANTHROPIC_API_KEY"])
        assertEquals("org/second-model", second["ANTHROPIC_MODEL"])
        assertFalse(second.containsKey("ANTHROPIC_CUSTOM_HEADERS"))
        assertEquals("first-model", first["ANTHROPIC_MODEL"])
        assertEquals("X-Tenant: first", first["ANTHROPIC_CUSTOM_HEADERS"])
    }

    @Test
    fun customAgentKeepsItsOwnEnvironmentWithoutASharedProvider() {
        val profile = AcpAgentProfile(
            id = "user-agent",
            name = "My ACP Agent",
            command = "/workspace/my agent/bin/acp",
            arguments = listOf("--config", "/workspace/my agent/config.json"),
            environment = mapOf(
                "OPENAI_API_KEY" to "user-test-key",
                "OPENAI_BASE_URL" to "https://user.example/v1",
                "ANTHROPIC_MODEL" to "user-model",
                "CUSTOM_OPTION" to "spaces 中文 = 'quotes' \$literal",
                "EMPTY_OPTION" to "",
            ),
        )
        // Exercise the persisted profile representation, not just a fresh map.
        val restored = Gson().fromJson(Gson().toJson(profile), AcpAgentProfile::class.java)
        val mapping = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = restored.id,
                provider = null,
                model = null,
                harnessAdapter = AcpHarnessAdapters.forProfile(restored),
            ),
        )

        assertEquals(profile.command, restored.command)
        assertEquals(profile.arguments, restored.arguments)
        assertEquals(
            profile.environment,
            mergeAcpLaunchEnvironment(
                profile = restored,
                providerEnvironment = mapping.environment,
            ),
        )
    }

    @Test
    fun customAgentLaunchesDoNotInheritTheSharedProviderOrPreviousEdits() {
        val first = AcpAgentProfile(
            id = "user-agent",
            name = "My ACP Agent",
            command = "my-agent-acp",
            environment = mapOf(
                "OPENAI_API_KEY" to "first-test-key",
                "CUSTOM_OPTION" to "first",
            ),
        )
        val edited = first.copy(environment = mapOf("OPENAI_API_KEY" to "edited-test-key"))
        val cleared = first.copy(environment = emptyMap())
        for (sharedProvider in listOf(null, provider, provider.copy(apiKey = "changed-key"))) {
            for (profile in listOf(first, edited, cleared, first)) {
                val mapping = AgentConfigAdapterRegistry.map(
                    AgentProviderMappingInput(
                        agentId = profile.id,
                        provider = sharedProvider,
                        model = "shared-model",
                        harnessAdapter = AcpHarnessAdapters.forProfile(profile),
                    ),
                )
                assertEquals(
                    profile.environment,
                    mergeAcpLaunchEnvironment(profile, mapping.environment),
                )
            }
        }
        assertEquals("first-test-key", first.environment["OPENAI_API_KEY"])
    }

    @Test
    fun providerMappingCannotBeShadowedByStaleProfileEnvironment() {
        val merged = mergeAcpLaunchEnvironment(
            profile = AcpAgentProfile(
                id = "shared-provider-agent",
                name = "Shared Provider Agent",
                command = "agent-acp",
                officialRuntime = AcpOfficialRuntime(
                    discoveryCommand = "agent-acp",
                    usesSharedProvider = true,
                ),
                environment = mapOf(
                    "ANTHROPIC_BASE_URL" to "https://old.example.com/anthropic",
                    "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1",
                ),
            ),
            providerEnvironment = mapOf(
                "ANTHROPIC_BASE_URL" to "https://api.minimaxi.com/anthropic",
                "ANTHROPIC_MODEL" to "MiniMax-M3",
            ),
        )

        assertEquals("https://api.minimaxi.com/anthropic", merged["ANTHROPIC_BASE_URL"])
        assertEquals("MiniMax-M3", merged["ANTHROPIC_MODEL"])
        assertEquals("1", merged["CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC"])
    }

    @Test
    fun staleProviderEnvironmentIsRemovedWhenCurrentProviderIsAbsent() {
        val merged = mergeAcpLaunchEnvironment(
            profile = AcpAgentProfile(
                id = "shared-provider-agent",
                name = "Shared Provider Agent",
                command = "agent-acp",
                officialRuntime = AcpOfficialRuntime(
                    discoveryCommand = "agent-acp",
                    usesSharedProvider = true,
                ),
                environment = mapOf(
                    "ANTHROPIC_BASE_URL" to "https://old.example.com/anthropic",
                    "ANTHROPIC_API_KEY" to "old-key",
                    "OPENAI_BASE_URL" to "https://old.example.com/v1",
                    "OMNIBOT_PROVIDER_HEADER_0" to "old-header",
                    "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1",
                ),
            ),
            providerEnvironment = emptyMap(),
        )

        assertTrue("ANTHROPIC_BASE_URL" !in merged)
        assertTrue("ANTHROPIC_API_KEY" !in merged)
        assertTrue("OPENAI_BASE_URL" !in merged)
        assertTrue("OMNIBOT_PROVIDER_HEADER_0" !in merged)
        assertEquals("1", merged["CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC"])
    }

    @Test
    fun claudeCodeMapsMiniMaxOpenAiBaseToAnthropicBase() {
        assertEquals(
            "https://api.minimaxi.com/anthropic",
            normalizeClaudeCodeBaseUrl("https://api.minimaxi.com/v1")
        )
        assertEquals(
            "https://api.minimax.io/anthropic",
            normalizeClaudeCodeBaseUrl("https://api.minimax.io/v1/chat/completions")
        )
    }

    @Test
    fun claudeCodePreservesMiniMaxAnthropicBase() {
        assertEquals(
            "https://api.minimaxi.com/anthropic",
            normalizeClaudeCodeBaseUrl("https://api.minimaxi.com/anthropic/v1")
        )
    }

    @Test
    fun claudeCodeLaunchEnvironmentUsesMappedMiniMaxAnthropicEndpoint() {
        val mapping = AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput(
                agentId = "claude-code-acp",
                provider = AgentProviderCredentials(
                    baseUrl = "https://api.minimaxi.com/v1",
                    apiKey = "test-key",
                ),
                model = "MiniMax-M3",
                harnessAdapter = AcpHarnessAdapters.claudeCode,
            )
        )

        assertEquals(
            "https://api.minimaxi.com/anthropic",
            mapping.environment["ANTHROPIC_BASE_URL"]
        )
        assertEquals("MiniMax-M3", mapping.environment["ANTHROPIC_MODEL"])
    }
}
