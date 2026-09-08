package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.ProviderModelOption
import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Exercise the actual shipped catalog, so a new profile cannot bypass mapping coverage. */
class AgentAdapterCatalogTest {
    @Test
    fun everyShippedHarnessHasAnExplicitConfigurationOwner() {
        val profiles = AcpAgentCatalog.parse(File("src/main/assets/acp/agents.json").readText()) {
            File("src/main/assets/$it").readText()
        }.agents
        assertEquals(6, profiles.size)
        for (profile in profiles) {
            val runtime = requireNotNull(AcpAgentProfileStore.officialRuntime(profile))
            val adapter = runtime.harnessAdapter
            if (profile.id == AcpAgentProfileStore.XIAOWAN_AGENT_ID) {
                assertNull(adapter.configAdapterId) // In-process Provider execution, no deployment file.
                continue
            }
            assertNotNull(profile.id, adapter.configAdapterId)
            for ((protocol, wire) in listOf(
                "openai_compatible" to "chat_completions",
                "openai_compatible" to "responses",
                "anthropic" to "chat_completions",
            )) {
                val input = AgentProviderMappingInput(
                    agentId = profile.id, harnessAdapter = adapter,
                    provider = AgentProviderCredentials("https://fixture.invalid/v1", "fixture-key",
                        protocolType = protocol, wireApi = wire), model = "org/test-model",
                )
                // Claude selects the Anthropic wire itself; a shared gateway
                // may expose it even when its saved default is OpenAI-compatible.
                val incompatible = adapter.configAdapterId == "codex" && protocol == "anthropic"
                if (incompatible) {
                    val failure = runCatching { AgentConfigAdapterRegistry.map(input) }.exceptionOrNull()
                    assertTrue("${profile.id}/$protocol must reject incompatible configuration", failure is IllegalArgumentException)
                    continue
                }
                val mapping = AgentConfigAdapterRegistry.map(input)
                val catalog = listOf(ProviderModelOption(id = "org/test-model"), ProviderModelOption(id = "org/second-model"))
                val files = AgentConfigAdapterRegistry.launchConfigWrites(input, mapping, catalog, "{}")
                val refreshFiles = AgentConfigAdapterRegistry.launchConfigWrites(input, mapping,
                    catalog + ProviderModelOption(id = "org/new-model"), "{}")
                val evidence = File("build/reports/harness-adapters/${adapter.configAdapterId}-$protocol-$wire.json")
                evidence.parentFile.mkdirs()
                evidence.writeText(com.google.gson.Gson().toJson(mapOf(
                    "harness" to adapter.configAdapterId, "protocol" to protocol, "wire" to wire,
                    "environment" to mapping.environment, "files" to files, "refreshFiles" to refreshFiles,
                    "clientCapabilityMeta" to com.google.gson.Gson().fromJson(
                        input.harnessAdapter.clientCapabilityMeta(kotlinx.serialization.json.buildJsonObject {}).toString(),
                        Map::class.java,
                    ),
                )))
                assertTrue("${profile.id} dropped selected model",
                    mapping.environment.values.any { it == "org/test-model" } ||
                        files.any { it.content.contains("org/test-model") })
                when (adapter.configAdapterId) {
                    "open-code" -> {
                        val sdk = JsonParser.parseString(files.single().content).asJsonObject
                            .getAsJsonObject("provider").getAsJsonObject("omnibot")["npm"].asString
                        assertEquals(when {
                            protocol == "anthropic" -> "@ai-sdk/anthropic"
                            wire == "responses" -> "@ai-sdk/openai"
                            else -> "@ai-sdk/openai-compatible"
                        }, sdk)
                    }
                    "kimi-code" -> {
                        val type = when {
                            protocol == "anthropic" -> "anthropic"
                            wire == "responses" -> "openai_responses"
                            else -> "openai"
                        }
                        assertTrue(files.single().content.contains("type = \"$type\""))
                        assertFalse(mapping.environment.containsKey("KIMI_MODEL_NAME"))
                    }
                    "claude-code" -> assertEquals("fixture-key", mapping.environment["ANTHROPIC_API_KEY"])
                    "codex" -> assertEquals("responses", mapping.codexWireApi)
                    "deepseek-harness" -> assertTrue(files.single().content.contains(when {
                        protocol == "anthropic" -> "anthropic-messages"
                        wire == "responses" -> "openai-responses"
                        else -> "openai-completions"
                    }))
                    else -> fail("Add assertions for new adapter ${adapter.configAdapterId}")
                }
            }
        }
    }

    @Test
    fun unknownAdapterFailsClearlyInsteadOfLaunchingWithEmptyConfiguration() {
        val adapter = object : AcpHarnessAdapter { override val configAdapterId = "missing-adapter" }
        val failure = runCatching {
            AgentConfigAdapterRegistry.map(AgentProviderMappingInput("custom", null, null, adapter))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("missing-adapter"))
        assertEquals(emptyMap<String, String>(), AgentConfigAdapterRegistry.map(
            AgentProviderMappingInput("custom", null, null, AcpHarnessAdapters.standard)).environment)
    }
}
