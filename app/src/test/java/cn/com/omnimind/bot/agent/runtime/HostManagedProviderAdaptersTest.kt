package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostManagedProviderAdaptersTest {
    private val provider = AgentProviderCredentials(
        baseUrl = "http://127.0.0.1:36123/v1",
        apiKey = "local-process-grant",
        wireApi = OpenAiWireApi.RESPONSES,
        supportsNamespaceTools = true,
        authentication = AgentProviderAuthentication.HOST_MANAGED,
    )
    private val models = listOf(ProviderModelOption(id = "gpt-6.1-sol", contextLimit = 128000))

    @Test
    fun `host Responses support follows registered adapter capability rather than agent name`() {
        val supported = listOf(AcpHarnessAdapters.codex, AcpHarnessAdapters.openCode,
            AcpHarnessAdapters.kimiCode, AcpHarnessAdapters.pi)
        supported.forEach { adapter ->
            assertTrue(AgentConfigAdapterRegistry.supportsHostManagedResponses(adapter))
            AgentConfigAdapterRegistry.map(input(adapter).copy(agentId = "renamed-user-profile"))
        }
        listOf(AcpHarnessAdapters.standard, AcpHarnessAdapters.claudeCode).forEach { adapter ->
            assertFalse(AgentConfigAdapterRegistry.supportsHostManagedResponses(adapter))
            val error = runCatching {
                AgentConfigAdapterRegistry.map(input(adapter).copy(agentId = "codex-acp"))
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertFalse(error!!.message!!.contains(provider.apiKey))
        }
    }

    @Test
    fun `host credential marker rejects remote endpoints headers and incompatible wire formats`() {
        listOf(
            provider.copy(baseUrl = "https://api.openai.com/v1"),
            provider.copy(baseUrl = "http://localhost:36123/v1"),
            provider.copy(baseUrl = "http://127.0.0.1:36123/v1?token=secret"),
            provider.copy(wireApi = "chat_completions"),
            provider.copy(protocolType = "anthropic"),
            provider.copy(customHeaders = mapOf("Authorization" to "another-token")),
        ).forEach { invalid ->
            assertTrue(runCatching { invalid.normalized() }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun `Codex uses an environment grant and HTTP Responses without touching auth json`() {
        val input = input(AcpHarnessAdapters.codex)
        val mapping = AgentConfigAdapterRegistry.map(input)
        val writes = AgentConfigAdapterRegistry.launchConfigWrites(input, mapping, models, "")
        val config = writes.single { it.path == CODEX_CONFIG_TOML_PATH }.content
        assertEquals(provider.apiKey, mapping.environment["OPENAI_API_KEY"])
        assertTrue(config.contains("env_key = \"OPENAI_API_KEY\""))
        assertTrue(config.contains("requires_openai_auth = false"))
        assertTrue(config.contains("supports_websockets = false"))
        assertTrue(config.contains("wire_api = \"responses\""))
        // Codex treats this custom provider as local-compaction-capable only;
        // naming it OpenAI would opt into unsupported backend compaction routes.
        assertTrue(config.contains("name = \"omnimind\""))
        assertFalse(writes.any { it.path == CODEX_AUTH_JSON_PATH })
        assertTrue(writes.none { it.content.contains(provider.apiKey) })
        assertTrue(AcpHarnessAdapters.codex.supportsSessionMcp(provider))
        val catalog = JsonParser.parseString(writes.single {
            it.path == CODEX_MODEL_CATALOG_JSON_PATH
        }.content).asJsonObject["models"].asJsonArray
        assertFalse(catalog.single().asJsonObject["supports_search_tool"].asBoolean)

        val replacement = input.copy(provider = provider.copy(apiKey = "next-local-grant"))
        val updated = AgentConfigAdapterRegistry.map(replacement)
        assertEquals("next-local-grant", updated.environment["OPENAI_API_KEY"])
        assertEquals(writes, AgentConfigAdapterRegistry.launchConfigWrites(replacement, updated, models, ""))
    }

    @Test
    fun `Codex API key configuration retains existing auth file behavior`() {
        val input = input(AcpHarnessAdapters.codex).copy(provider = provider.copy(
            baseUrl = "https://gateway.example/v1", apiKey = "configured-api-key",
            authentication = AgentProviderAuthentication.API_KEY,
        ))
        val mapping = AgentConfigAdapterRegistry.map(input)
        val writes = AgentConfigAdapterRegistry.launchConfigWrites(input, mapping, models, "")
        val config = writes.single { it.path == CODEX_CONFIG_TOML_PATH }.content
        assertTrue(config.contains("requires_openai_auth = true"))
        assertFalse(config.contains("env_key ="))
        assertEquals("configured-api-key", JsonParser.parseString(
            writes.single { it.path == CODEX_AUTH_JSON_PATH }.content,
        ).asJsonObject["OPENAI_API_KEY"].asString)
    }

    @Test
    fun `Codex host managed settings do not read an earlier account credential`() = runBlocking {
        val reads = mutableListOf<String>()
        val access = object : AgentConfigFileAccess {
            override suspend fun read(path: String, executorKey: String): String {
                reads += path
                return ""
            }
            override suspend fun write(path: String, content: String, executorKey: String) = Unit
        }
        val config = AgentConfigAdapterRegistry.readConfig(input(AcpHarnessAdapters.codex), access)!!
        assertFalse(reads.contains(CODEX_AUTH_JSON_PATH))
        assertEquals("", config["apiKey"])
        val writes = AgentConfigAdapterRegistry.directConfigWrites(input(AcpHarnessAdapters.codex),
            mapOf("baseUrl" to "https://other.example", "apiKey" to "overridden", "model" to models.single().id), models)
        assertTrue(writes.none { it.content.contains("other.example") || it.content.contains("overridden") })
        assertFalse(writes.any { it.path == CODEX_AUTH_JSON_PATH })
    }

    @Test
    fun `Kimi references process grant through its official api key env setting`() {
        val input = input(AcpHarnessAdapters.kimiCode)
        val mapping = AgentConfigAdapterRegistry.map(input)
        val config = AgentConfigAdapterRegistry.launchConfigWrites(input, mapping, models, "").single().content
        assertEquals(provider.apiKey, mapping.environment["OPENAI_API_KEY"])
        assertTrue(config.contains("type = \"openai_responses\""))
        assertTrue(config.contains("api_key_env = \"OPENAI_API_KEY\""))
        assertFalse(config.contains("api_key ="))
        assertFalse(config.contains(provider.apiKey))
        assertTrue(config.contains(provider.baseUrl))
    }

    @Test
    fun `OpenCode removes stored continuation options only from the managed route`() {
        val input = input(AcpHarnessAdapters.openCode)
        val mapping = AgentConfigAdapterRegistry.map(input)
        val existing = """{
            "mcp":{"files":{"type":"local","command":["files-server"]}},
            "provider":{
                "other":{"options":{"store":true,"previousResponseId":"keep-other"}},
                "omnibot":{"options":{"store":true,"previousResponseId":"old-provider"},
                    "models":{"gpt-6.1-sol":{"options":{"store":true,"previous_response_id":"old-model"},
                        "variants":{"high":{"store":true,"previousResponseId":"old-variant","reasoningEffort":"high"}}}}}
            }}"""
        val config = JsonParser.parseString(AgentConfigAdapterRegistry.launchConfigWrites(
            input, mapping, models, existing,
        ).single().content).asJsonObject
        val managed = config["providers"].asJsonObject["omnibot"].asJsonObject
        val model = managed["models"].asJsonObject[models.single().id].asJsonObject
        val variant = model["variants"].asJsonArray.single().asJsonObject
        listOf(managed["body"].asJsonObject, model["body"].asJsonObject, variant["body"].asJsonObject).forEach {
            assertFalse(it["store"].asBoolean)
            assertFalse(it.has("previousResponseId"))
            assertFalse(it.has("previous_response_id"))
        }
        assertEquals("@opencode/ai/providers/openai-compatible/responses", managed["package"].asString)
        assertEquals("{env:OPENAI_API_KEY}", managed["settings"].asJsonObject["apiKey"].asString)
        assertEquals(provider.apiKey, mapping.environment["OPENAI_API_KEY"])
        assertFalse(config.toString().contains(provider.apiKey))
        assertEquals("keep-other", config["provider"].asJsonObject["other"].asJsonObject["options"].asJsonObject["previousResponseId"].asString)
        assertTrue(config["mcp"].asJsonObject.has("files"))
    }

    @Test
    fun `offline Codex settings need only provider metadata and never write an inference route`() = runBlocking {
        val selected = ModelProviderProfile(id = "chatgpt", name = "ChatGPT",
            baseUrl = "https://api.openai.com/v1", sourceType = "chatgpt_oauth", wireApi = "responses")
        val input = input(AcpHarnessAdapters.codex).copy(provider = null, providerProfile = selected)
        val reads = mutableListOf<String>()
        val metadata = AgentConfigAdapterRegistry.readConfig(input, object : AgentConfigFileAccess {
            override suspend fun read(path: String, executorKey: String): String {
                reads += path
                return "base_url = \"https://old-provider.example/v1\""
            }
            override suspend fun write(path: String, content: String, executorKey: String) = Unit
        })!!
        assertEquals(selected.baseUrl, metadata["baseUrl"])
        assertEquals("", metadata["apiKey"])
        assertFalse(reads.contains(CODEX_AUTH_JSON_PATH))
        val writes = AgentConfigAdapterRegistry.directConfigWrites(input, emptyMap(), models)
        assertEquals(listOf(CODEX_MODEL_CATALOG_JSON_PATH), writes.map { it.path })
        assertFalse(writes.single().content.contains("base_url"))
        assertEquals(writes, AgentConfigAdapterRegistry.directConfigWrites(input.copy(model = null), emptyMap(), models))
        assertTrue(runCatching { AgentConfigAdapterRegistry.map(input) }.exceptionOrNull() is IllegalArgumentException)

        val online = input.copy(provider = provider)
        val onlineWrites = AgentConfigAdapterRegistry.directConfigWrites(online, emptyMap(), models)
        assertTrue(onlineWrites.single { it.path == CODEX_CONFIG_TOML_PATH }.content.contains(provider.baseUrl))
        assertTrue(onlineWrites.none { it.content.contains(provider.apiKey) })
    }

    private fun input(adapter: AcpHarnessAdapter) = AgentProviderMappingInput(
        agentId = "fixture-agent", provider = provider, model = models.single().id, harnessAdapter = adapter,
    )
}
