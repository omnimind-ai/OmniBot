package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolDefinitionsVlmTest {
    @Test
    fun `enabled operation module exposes built in vlm task`() {
        val definitions = AgentToolDefinitions.staticTools(
            locale = PromptLocale.EN_US,
            includeVlmTool = true,
        )
        val function = definitions
            .mapNotNull { it["function"] as? JsonObject }
            .single { it["name"]?.jsonPrimitive?.contentOrNull == "vlm_task" }

        assertEquals("builtin", function["toolType"]?.jsonPrimitive?.contentOrNull)
        assertTrue("vlm_task" in AgentToolDefinitions.reservedToolNames())
    }

    @Test
    fun `visual entry stays available for manual-enable guidance`() {
        val definitions = AgentToolDefinitions.staticTools(PromptLocale.EN_US)

        assertTrue(
            definitions.any {
                (it["function"] as? JsonObject)
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull == "vlm_task"
            }
        )
    }

    @Test
    fun `direct agent catalog uses common native tool names`() {
        val definitions = AgentToolDefinitions.modelFacingTools(
            AgentToolDefinitions.staticTools(PromptLocale.EN_US)
        )
        val names = definitions.mapNotNull {
            (it["function"] as? JsonObject)
                ?.get("name")
                ?.jsonPrimitive
                ?.contentOrNull
        }

        assertTrue(setOf("read", "write", "edit", "bash", "glob", "grep", "webfetch")
            .all(names::contains))
        assertTrue("file_read" !in names)
        assertTrue("terminal_execute" !in names)
        assertTrue("browser_use" !in names)
        assertTrue("read" in AgentToolDefinitions.reservedToolNames())

        val bashDescription = definitions
            .mapNotNull { it["function"] as? JsonObject }
            .single { it["name"]?.jsonPrimitive?.contentOrNull == "bash" }
            .getValue("description")
            .jsonPrimitive
            .content
        assertTrue("terminal_execute" !in bashDescription)
    }
}
