package cn.com.omnimind.bot.agent.runtime

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AgentRuntimeManagerConfigTest {
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
}
