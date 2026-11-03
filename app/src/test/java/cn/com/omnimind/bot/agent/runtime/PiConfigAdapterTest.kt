package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.llm.DeepSeekProvider
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.ProviderModelCapabilities
import com.google.gson.JsonParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class PiConfigAdapterTest {
    @Test
    fun sharedProviderSelectsTheOfficialWireAndKeepsSecretsOutOfFiles() {
        for ((protocol, wire, api) in listOf(
            Triple("openai_compatible", "chat_completions", "openai-completions"),
            Triple("openai_compatible", "responses", "openai-responses"),
            Triple("anthropic", "chat_completions", "anthropic-messages"),
        )) {
            val provider = AgentProviderCredentials(
                "https://fixture.invalid/v1", "private-key", wireApi = wire, protocolType = protocol,
                customHeaders = mapOf("X-Tenant" to "private-header"),
            )
            val input = AgentProviderMappingInput("pi-acp", provider, "org/model", AcpHarnessAdapters.pi)
            val mapping = AgentConfigAdapterRegistry.map(input)
            val files = AgentConfigAdapterRegistry.launchConfigWrites(input, mapping, listOf(
                ProviderModelOption(id = "org/model", contextLimit = 32000, outputLimit = 4000,
                    inputModalities = listOf("text", "image"), reasoning = true),
                ProviderModelOption(id = "other"),
            ), "{}")
            assertEquals(PI_MODELS_PATH, files.single().path)
            assertEquals("private-key", mapping.environment["OMNIBOT_PI_API_KEY"])
            assertEquals("org/model", mapping.environment["OMNIBOT_PI_MODEL"])
            assertTrue(mapping.environment.getValue("OMNIBOT_PI_HEADERS").contains("private-header"))
            assertFalse(files.single().content.contains("private-key"))
            assertFalse(files.single().content.contains("private-header"))
            val config = JsonParser.parseString(files.single().content).asJsonObject
                .getAsJsonObject("providers").getAsJsonObject("omnibot")
            assertEquals(api, config["api"].asString)
            assertEquals(if (protocol == "anthropic") "https://fixture.invalid" else "https://fixture.invalid/v1", config["baseUrl"].asString)
            val models = config.getAsJsonArray("models")
            assertEquals(2, models.size())
            assertEquals(32000, models[0].asJsonObject["contextWindow"].asInt)
            assertEquals(4000, models[0].asJsonObject["maxTokens"].asInt)
            assertTrue(models[0].asJsonObject["reasoning"].asBoolean)
            assertTrue(models[0].asJsonObject["input"].asJsonArray.any { it.asString == "image" })
        }
    }

    @Test
    fun declaredReasoningLevelsAndSmallModelLimitsArePreserved() {
        val config = JsonParser.parseString(buildPiModelsJson(
            AgentProviderCredentials("https://fixture.invalid/v1", "key"), "small",
            listOf(ProviderModelOption(id = "small", contextLimit = 2048, reasoning = true,
                supportedReasoningLevels = listOf("off", "high", "max"))),
        )).asJsonObject.getAsJsonObject("providers").getAsJsonObject("omnibot")
            .getAsJsonArray("models")[0].asJsonObject
        assertEquals(2048, config["maxTokens"].asInt)
        val thinking = config.getAsJsonObject("thinkingLevelMap")
        assertEquals("off", thinking["off"].asString)
        assertEquals("high", thinking["high"].asString)
        assertEquals("max", thinking["max"].asString)
        assertTrue(thinking["medium"].isJsonNull)
    }

    @Test
    fun deepSeekDeclaredEffortsReachPiWithoutDisablingTheIndependentOffSwitch() {
        val model = ProviderModelCapabilities.applyDeclared(
            ProviderModelOption("deepseek-flash"),
            Json.parseToJsonElement("""{
                "effort": {"supported_levels":["low","high","max"],"default_level":"high"}
            }""").jsonObject,
            DeepSeekProvider.OFFICIAL_BASE_URL,
        )
        val profile = DeepSeekProvider.officialProfile().copy(revision = 7)
        val document = JsonParser.parseString(buildPiModelsJson(
            AgentProviderCredentials(profile.baseUrl, "private-key"), model.id, listOf(model), profile,
        )).asJsonObject
        val piModel = document.getAsJsonObject("providers").getAsJsonObject("omnibot")
            .getAsJsonArray("models")[0].asJsonObject
        assertTrue(piModel["reasoning"].asBoolean)
        val thinking = piModel.getAsJsonObject("thinkingLevelMap")
        assertEquals("none", thinking["off"].asString)
        for (level in listOf("low", "high", "max")) assertEquals(level, thinking[level].asString)
        for (level in listOf("minimal", "medium", "xhigh")) assertTrue(thinking[level].isJsonNull)
        val identity = document.getAsJsonObject("_meta").getAsJsonObject("omnibot")
        assertEquals(profile.id, identity["providerProfileId"].asString)
        assertEquals(7, identity["providerRevision"].asInt)
        assertEquals("high", identity.getAsJsonObject("modelThinkingDefaults")[model.id].asString)
        assertFalse(document.toString().contains("private-key"))
    }

    @Test
    fun reasoningEffortDeclarationsWorkWithoutARedundantBooleanAndPreserveNoneOnTheWire() {
        val document = JsonParser.parseString(buildPiModelsJson(
            AgentProviderCredentials("https://fixture.invalid/v1", "key"), "model",
            listOf(ProviderModelOption("model", supportedReasoningLevels = listOf("none", "low", "high"),
                defaultReasoningLevel = "none")),
        )).asJsonObject
        val model = document.getAsJsonObject("providers").getAsJsonObject("omnibot")
            .getAsJsonArray("models")[0].asJsonObject
        assertTrue(model["reasoning"].asBoolean)
        assertEquals("none", model.getAsJsonObject("thinkingLevelMap")["off"].asString)
        assertEquals("off", document.getAsJsonObject("_meta").getAsJsonObject("omnibot")
            .getAsJsonObject("modelThinkingDefaults")["model"].asString)
    }

    @Test
    fun launchIdentityAndAdaptiveThinkingUseTheSameCanonicalProviderSnapshot() {
        val profile = ModelProviderProfile("anthropic-profile", "Claude", baseUrl = "https://api.anthropic.com/v1",
            protocolType = "anthropic", revision = 3)
        val input = AgentProviderMappingInput(
            "pi-acp", AgentProviderCredentials(profile.baseUrl, "key", protocolType = profile.protocolType),
            "adaptive-model", AcpHarnessAdapters.pi, providerProfile = profile,
        )
        val mapping = AgentConfigAdapterRegistry.map(input)
        assertEquals(profile.id, mapping.environment["OMNIBOT_PI_PROVIDER_ID"])
        assertEquals("3", mapping.environment["OMNIBOT_PI_PROVIDER_REVISION"])
        assertEquals("anthropic-messages", mapping.environment["OMNIBOT_PI_PROVIDER_API"])
        assertEquals("https://api.anthropic.com", mapping.environment["OMNIBOT_PI_PROVIDER_BASE_URL"])
        val file = AgentConfigAdapterRegistry.launchConfigWrites(input, mapping, listOf(
            ProviderModelOption("adaptive-model", reasoning = true, reasoningSupportsAdaptive = true,
                supportedReasoningLevels = listOf("low", "high", "max"), reasoningCanDisable = false),
        ), "{}").single()
        val model = JsonParser.parseString(file.content).asJsonObject.getAsJsonObject("providers")
            .getAsJsonObject("omnibot").getAsJsonArray("models")[0].asJsonObject
        assertTrue(model.getAsJsonObject("compat")["forceAdaptiveThinking"].asBoolean)
        assertTrue(model.getAsJsonObject("thinkingLevelMap")["off"].isJsonNull)
    }

    @Test
    fun mandatoryReasoningDoesNotOfferOffAndUnknownModelsDoNotGainInventedCapabilities() {
        val config = JsonParser.parseString(buildPiModelsJson(
            AgentProviderCredentials("https://fixture.invalid", "key", protocolType = "anthropic"), "required",
            listOf(
                ProviderModelOption("required", reasoning = true, reasoningCanDisable = false,
                    supportedReasoningLevels = listOf("low", "high", "max")),
                ProviderModelOption("unknown"),
                ProviderModelOption("disabled", reasoning = false, supportedReasoningLevels = listOf("high")),
            ),
        )).asJsonObject.getAsJsonObject("providers").getAsJsonObject("omnibot").getAsJsonArray("models")
        assertTrue(config[0].asJsonObject.getAsJsonObject("thinkingLevelMap")["off"].isJsonNull)
        assertFalse(config[1].asJsonObject.has("reasoning"))
        assertFalse(config[1].asJsonObject.has("thinkingLevelMap"))
        assertFalse(config[2].asJsonObject["reasoning"].asBoolean)
        assertFalse(config[2].asJsonObject.has("thinkingLevelMap"))
    }

    @Test
    fun aHostManagedResponsesGrantOnlyLivesInTheLaunchEnvironment() {
        val input = AgentProviderMappingInput("pi-acp", AgentProviderCredentials(
            "http://127.0.0.1:32123/v1", "process-grant", wireApi = "responses",
            authentication = AgentProviderAuthentication.HOST_MANAGED,
        ), "selected-model", AcpHarnessAdapters.pi)
        val mapping = AgentConfigAdapterRegistry.map(input)
        val content = AgentConfigAdapterRegistry.launchConfigWrites(input, mapping, emptyList(), "{}").single().content
        assertEquals("process-grant", mapping.environment["OMNIBOT_PI_API_KEY"])
        assertFalse(content.contains("process-grant"))
        assertTrue(content.contains("selected-model"))
        assertTrue(content.contains("openai-responses"))
    }

    @Test
    fun staleProfileOverridesCannotReplaceTheSharedPiProvider() {
        val profile = AcpAgentProfile(
            "pi-acp", "Pi", command = "omnibot-pi-acp", builtIn = true,
            environment = mapOf("OMNIBOT_PI_API_KEY" to "old", "OMNIBOT_PI_MODEL" to "old", "CUSTOM" to "keep"),
            officialRuntime = AcpOfficialRuntime("omnibot-pi-acp", harnessAdapter = AcpHarnessAdapters.pi, usesSharedProvider = true),
        )
        assertEquals(mapOf("CUSTOM" to "keep", "OMNIBOT_PI_MODEL" to "selected"),
            mergeAcpLaunchEnvironment(profile, mapOf("OMNIBOT_PI_MODEL" to "selected")))
    }
}
