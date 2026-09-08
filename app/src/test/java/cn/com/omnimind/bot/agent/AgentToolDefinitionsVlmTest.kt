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
    fun `direct agent catalog keeps harness tool names unchanged`() {
        val definitions = AgentToolDefinitions.staticTools(PromptLocale.EN_US)
        val names = definitions.mapNotNull {
            (it["function"] as? JsonObject)
                ?.get("name")
                ?.jsonPrimitive
                ?.contentOrNull
        }

        assertTrue(setOf(
            "file_read", "file_write", "file_edit", "terminal_execute",
            "file_list", "file_search", "browser_use"
        )
            .all(names::contains))
        assertTrue("file_read" in AgentToolDefinitions.reservedToolNames())

        val terminalDescription = definitions
            .mapNotNull { it["function"] as? JsonObject }
            .single { it["name"]?.jsonPrimitive?.contentOrNull == "terminal_execute" }
            .getValue("description")
            .jsonPrimitive
            .content
        assertTrue(terminalDescription.isNotBlank())
    }

    @Test
    fun `runtime tool catalogs omit private tool scheduling rules`() {
        val runtimeDefinitions = AgentToolDefinitions.staticTools(PromptLocale.EN_US) +
            AgentToolDefinitions.memoryTools(PromptLocale.EN_US) +
            AgentToolDefinitions.subagentTools(PromptLocale.EN_US)
        fun hasPrivateSchedulingRule(definition: JsonObject): Boolean =
            (definition["function"] as? JsonObject)?.get("postToolRule") != null

        assertTrue(
            "runtime catalogs must rely on ACP prompt turns, not per-tool scheduling",
            runtimeDefinitions.none(::hasPrivateSchedulingRule)
        )
        val scheduleDescription = runtimeDefinitions
            .mapNotNull { it["function"] as? JsonObject }
            .single { it["name"]?.jsonPrimitive?.contentOrNull == "schedule_task_create" }
            .getValue("description")
            .jsonPrimitive
            .content
        assertTrue("Wait for the tool result" !in scheduleDescription)
    }
}
