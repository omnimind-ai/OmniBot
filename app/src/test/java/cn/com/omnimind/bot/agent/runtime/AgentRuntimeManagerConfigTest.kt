package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.OmniOfficialProvider
import cn.com.omnimind.baselib.llm.ProviderModelOption
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AgentRuntimeManagerConfigTest {
    @Test
    fun `official provider binding resolves when it is absent from persisted profiles`() {
        val officialProfile = ModelProviderProfile(
            id = OmniOfficialProvider.PROFILE_ID,
            name = OmniOfficialProvider.PROFILE_NAME,
            baseUrl = "https://gateway.example",
        )

        assertEquals(
            officialProfile,
            resolveAgentProviderProfile(
                boundProviderProfileId = OmniOfficialProvider.PROFILE_ID,
                configuredProfile = null,
                officialProfile = officialProfile,
            ),
        )
    }

    @Test
    fun `Dispatch Provider falls back to the editing profile without a scene binding`() {
        val editingProfile = ModelProviderProfile(
            id = "editing-provider",
            name = "Editing Provider",
            baseUrl = "https://gateway.example/v1",
        )

        assertEquals(
            editingProfile,
            resolveDispatchAgentProviderProfile(
                boundProviderProfileId = null,
                configuredProfile = null,
                editingProfile = editingProfile,
                officialProfile = null,
            ),
        )
    }

    @Test
    fun `Dispatch binding fallback can create an ephemeral model selection`() {
        val editingProfile = ModelProviderProfile(
            id = "editing-provider",
            name = "Editing Provider",
            baseUrl = "https://gateway.example/v1",
        )

        assertEquals(
            "dispatch-model",
            resolveSharedAgentProviderBinding(
                currentBinding = null,
                editingProfile = editingProfile,
                availableModels = listOf(ProviderModelOption(id = "dispatch-model")),
            )?.modelId,
        )
    }

    @Test
    fun `official provider uses the account bearer token as harness credential`() {
        val officialProfile = ModelProviderProfile(
            id = OmniOfficialProvider.PROFILE_ID,
            name = OmniOfficialProvider.PROFILE_NAME,
            baseUrl = "https://gateway.example",
        )

        assertEquals(
            "account-token",
            resolveAgentProviderApiKey(officialProfile, " account-token "),
        )
    }

    @Test
    fun `custom provider keeps its configured api key`() {
        val customProfile = ModelProviderProfile(
            id = "custom-provider",
            name = "Custom",
            baseUrl = "https://provider.example",
            apiKey = "configured-key",
        )

        assertEquals(
            "configured-key",
            resolveAgentProviderApiKey(customProfile, "account-token"),
        )
    }

    @Test
    fun `persisted model remains usable when provider catalog is offline`() {
        assertEquals(
            "glm-5.1",
            resolveAcpLaunchModelWithBindingFallback(
                providerModelIds = emptyList(),
                boundModel = "glm-5.1",
            )
        )
    }

    @Test
    fun `OpenCode provider sync preserves user MCP configuration`() {
        val config = buildOpenCodeConfigJson(
            model = "omnibot/gpt-5",
            baseUrl = "https://provider.example/v1",
            existingConfigJson = """
                {
                  "mcp": {
                    "filesystem": {
                      "type": "local",
                      "command": ["filesystem-server"]
                    }
                  },
                  "agent": { "custom": { "description": "keep me" } }
                }
            """.trimIndent(),
        )

        val root = JsonParser.parseString(config).asJsonObject
        assertNotNull(root.getAsJsonObject("mcp").getAsJsonObject("filesystem"))
        assertEquals(
            "keep me",
            root.getAsJsonObject("agent").getAsJsonObject("custom")
                .get("description").asString,
        )
        assertEquals("omnibot/gpt-5", root.get("model").asString)
        assertEquals(
            "https://provider.example/v1",
            root.getAsJsonObject("provider").getAsJsonObject("omnibot")
                .getAsJsonObject("options").get("baseURL").asString,
        )
    }

    @Test
    fun `stale explicit ACP session is not reused after conversation switch`() {
        assertEquals(
            false,
            explicitThreadMatchesConversation(
                explicitThreadId = "old-session",
                requestedConversationId = 41L,
                boundConversationId = 40L,
            )
        )
    }

    @Test
    fun `current conversation keeps its ACP session and session-only calls stay compatible`() {
        assertEquals(
            true,
            explicitThreadMatchesConversation(
                explicitThreadId = "current-session",
                requestedConversationId = 41L,
                boundConversationId = 41L,
            )
        )
        assertEquals(
            true,
            explicitThreadMatchesConversation(
                explicitThreadId = "session-only",
                requestedConversationId = null,
                boundConversationId = null,
            )
        )
    }
}
