package cn.com.omnimind.bot.agent.runtime

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AgentRuntimeManagerConfigTest {
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
