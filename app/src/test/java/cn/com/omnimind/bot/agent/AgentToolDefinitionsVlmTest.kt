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
    fun `disabled operation module does not load vlm task`() {
        val definitions = AgentToolDefinitions.staticTools(
            locale = PromptLocale.EN_US,
            includeVlmTool = false,
        )

        assertTrue(
            definitions.none {
                (it["function"] as? JsonObject)
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull == "vlm_task"
            }
        )
    }
}
