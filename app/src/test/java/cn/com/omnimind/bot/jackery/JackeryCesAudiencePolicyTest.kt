package cn.com.omnimind.bot.jackery

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class JackeryCesAudiencePolicyTest {
    private fun tool(name: String, displayName: String, type: String) = buildJsonObject {
        put("function", buildJsonObject {
            put("name", name); put("displayName", displayName); put("toolType", type)
        })
    }

    @Test fun `only approved configured MCP search survives the audience capability filter`() {
        val search = tool("mcp__staff__web_search__hash", "web_search", "mcp")
        val fakeBuiltin = tool("web_search", "web_search", "builtin")
        val otherMcp = tool("mcp__staff__delete__hash", "delete_files", "mcp")
        assertEquals(listOf(search), JackeryCesAudiencePolicy.searchTools(
            listOf(search, fakeBuiltin, otherMcp, buildJsonObject {}), setOf("web_search")))
        assertTrue(JackeryCesAudiencePolicy.searchTools(listOf(search), emptySet()).isEmpty())
    }

    @Test fun `audience cannot change provider identity or prompt while read methods remain usable`() {
        listOf("saveModelProviderProfile", "setEditingModelProviderProfile", "clearModelProviderConfig",
            "saveSceneModelBinding", "saveChatPromptSetting", "saveAgentSoulSetting").forEach {
            assertTrue(it, JackeryCesAudiencePolicy.blocksMutation(it))
        }
        listOf("fetchProviderModels", "getModelProviderConfig", "listModelProviderProfiles").forEach {
            assertFalse(it, JackeryCesAudiencePolicy.blocksMutation(it))
        }
    }

}
