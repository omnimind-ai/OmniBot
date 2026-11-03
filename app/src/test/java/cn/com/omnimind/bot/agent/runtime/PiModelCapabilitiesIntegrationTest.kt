package cn.com.omnimind.bot.agent.runtime

import android.content.ContextWrapper
import android.content.res.AssetManager
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.ProviderModelOption
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/** The Pi wire verifier consumes these actual parser/adapter outputs, rather than hand-built models. */
class PiModelCapabilitiesIntegrationTest {
    @Test
    fun providerDeclarationsGenerateTheCatalogUsedByTheRealPiSdk() {
        val directory = File("build/reports/pi-model-capabilities").apply { mkdirs() }
        val cases = listOf(
            Case(
                "deepseek", "https://api.deepseek.com", "deepseek-flash", "openai_compatible", "chat_completions",
                """{"effort":{"supported_levels":["low","high","max"],"default_level":"high"}}""",
                listOf("off", "low", "high", "max"), "high",
                mapOf("off" to mapOf("thinking" to mapOf("type" to "disabled"))) +
                    listOf("low", "high", "max").associateWith { level ->
                        mapOf("thinking" to mapOf("type" to "enabled"), "reasoning_effort" to level)
                    },
                mapOf("off" to listOf("reasoning_effort")),
            ),
            Case(
                "deepseek-anthropic", "https://api.deepseek.com/anthropic", "deepseek-flash", "anthropic", "chat_completions",
                """{"effort":{"supported_levels":["low","high","max"],"default_level":"high"}}""",
                listOf("off", "low", "high", "max"), "high",
                mapOf("off" to mapOf("thinking" to mapOf("type" to "disabled"))) +
                    listOf("low", "high", "max").associateWith { level ->
                        mapOf("thinking" to mapOf("type" to "adaptive"), "output_config" to mapOf("effort" to level))
                    },
                mapOf("off" to listOf("output_config")),
            ),
            Case(
                "responses", "https://api.openai.com/v1", "gpt-5.4", "openai_compatible", "responses",
                """{"supported_reasoning_levels":[{"effort":"none"},{"effort":"low"},
                    {"effort":"medium"},{"effort":"high"},{"effort":"xhigh"}],"default_reasoning_level":"medium"}""",
                listOf("off", "low", "medium", "high", "xhigh"), "medium",
                listOf("off", "low", "medium", "high", "xhigh").associateWith { level ->
                    mapOf("reasoning" to mapOf("effort" to if (level == "off") "none" else level))
                },
            ),
            Case(
                "anthropic", "https://api.anthropic.com", "claude-sonnet-5", "anthropic", "chat_completions",
                """{"capabilities":{"thinking":{"supported":true,"types":{
                    "adaptive":{"supported":true},"disabled":{"supported":true}}},
                    "effort":{"supported":true,"low":{"supported":true},
                    "high":{"supported":true},"max":{"supported":true}}},"default_reasoning_level":"high"}""",
                listOf("off", "low", "high", "max"), "high",
                mapOf("off" to mapOf("thinking" to mapOf("type" to "disabled"))) +
                    listOf("low", "high", "max").associateWith { level ->
                        mapOf("thinking" to mapOf("type" to "adaptive"), "output_config" to mapOf("effort" to level))
                    },
                mapOf("off" to listOf("output_config")),
            ),
        )
        for (case in cases) {
            val rawModel = JsonParser.parseString(case.metadata).asJsonObject.apply { addProperty("id", case.modelId) }
            val body = "{\"data\":[$rawModel]}"
            val parser = HttpController::class.java.getDeclaredMethod(
                "parseProviderModelsResponse", String::class.java, String::class.java,
            ).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val parsed = parser.invoke(HttpController, body, case.baseUrl) as List<ProviderModelOption>
            assertEquals(true, parsed.single().reasoning)
            assertEquals(case.defaultLevel, parsed.single().defaultReasoningLevel)
            writeFixture(directory, case, parsed)
        }
    }

    @Test
    fun exactRouteCatalogPreservesVendorThinkingParametersForTheSdk() {
        val directory = File("build/reports/pi-model-capabilities").apply { mkdirs() }
        val assets = Mockito.mock(AssetManager::class.java)
        Mockito.`when`(assets.open("provider_reasoning_catalog.json")).thenAnswer {
            File("../baselib/src/main/assets/provider_reasoning_catalog.json").inputStream()
        }
        val context = object : ContextWrapper(null) {
            override fun getAssets(): AssetManager = assets
        }
        fun toggleCase(name: String, baseUrl: String, modelId: String, field: String) = Case(
            name, baseUrl, modelId, "openai_compatible", "chat_completions", "{}",
            listOf("off", "on"), "on",
            listOf("off", "on").associateWith { level ->
                if (field == "thinking") mapOf(field to mapOf("type" to if (level == "off") "disabled" else "enabled"))
                else mapOf(field to (level == "on"))
            },
            listOf("off", "on").associateWith { listOf("reasoning_effort") },
        )
        val cases = listOf(
            toggleCase("kimi", "https://api.moonshot.cn/v1", "kimi-k2.6", "thinking"),
            toggleCase("mimo", "https://api.xiaomimimo.com/v1", "mimo-v2.6-pro", "thinking"),
            toggleCase("qwen", "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", "qwen3.7-max", "enable_thinking"),
            Case(
                "glm", "https://open.bigmodel.cn/api/coding/paas/v4", "glm-5.3", "openai_compatible", "chat_completions", "{}",
                listOf("low", "high", "max"), "high",
                listOf("low", "high", "max").associateWith { level ->
                    mapOf("thinking" to mapOf("type" to "enabled"), "reasoning_effort" to level)
                },
            ),
            Case(
                "openai-chat", "https://api.openai.com/v1", "gpt-5.4", "openai_compatible", "chat_completions", "{}",
                listOf("off", "low", "medium", "high", "xhigh"), "medium",
                listOf("off", "low", "medium", "high", "xhigh").associateWith { level ->
                    mapOf("reasoning_effort" to if (level == "off") "none" else level)
                },
            ),
        )
        for (case in cases) {
            val profile = ModelProviderProfile(case.name, case.name, baseUrl = case.baseUrl,
                protocolType = case.protocol, wireApi = case.wire, revision = 1)
            val resolved = ModelProviderConfigStore.resolveModelCapabilities(context, profile, listOf(ProviderModelOption(case.modelId)))
            assertEquals("bundled_catalog", resolved.single().reasoningCapabilitySource)
            writeFixture(directory, case, resolved)
        }
    }

    private fun writeFixture(directory: File, case: Case, models: List<ProviderModelOption>) {
        val profile = ModelProviderProfile(case.name, case.name, baseUrl = case.baseUrl,
            protocolType = case.protocol, wireApi = case.wire, revision = 1)
        val document = JsonParser.parseString(buildPiModelsJson(
            AgentProviderCredentials(case.baseUrl, "fixture-key", protocolType = case.protocol, wireApi = case.wire),
            case.modelId, models, profile,
        ))
        val piModel = document.asJsonObject.getAsJsonObject("providers").getAsJsonObject("omnibot")
            .getAsJsonArray("models")[0].asJsonObject
        assertTrue(piModel["reasoning"].asBoolean)
        val fixture = mapOf(
            "name" to case.name, "document" to document, "modelId" to case.modelId,
            "expectedLevels" to case.levels, "expectedDefault" to case.defaultLevel,
            "expectedWire" to case.expectedWire, "expectedAbsent" to case.expectedAbsent,
        )
        File(directory, "${case.name}.json").writeText(
            GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(fixture)
        )
    }

    private data class Case(
        val name: String,
        val baseUrl: String,
        val modelId: String,
        val protocol: String,
        val wire: String,
        val metadata: String,
        val levels: List<String>,
        val defaultLevel: String,
        val expectedWire: Map<String, Map<String, Any>>,
        val expectedAbsent: Map<String, List<String>> = emptyMap(),
    )
}
