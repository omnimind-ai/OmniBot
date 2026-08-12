package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.bot.agent.tool.handlers.CalendarMutationConfirmationPolicy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarMutationConfirmationPolicyTest {
    private val calendarTools = setOf(
        "calendar_list",
        "calendar_event_create",
        "calendar_event_list",
        "calendar_event_update",
        "calendar_event_delete",
    )

    @Test
    fun `calendar reads and writes require a trusted user action`() {
        calendarTools.forEach { toolName ->
            assertTrue(CalendarMutationConfirmationPolicy.requiresExplicitUserConsent(toolName))
        }
        assertFalse(CalendarMutationConfirmationPolicy.requiresExplicitUserConsent("alarm_reminder_list"))
    }

    @Test
    fun `calendar definitions do not expose model-controlled confirmation fields`() {
        val definitions = AgentToolDefinitions.staticTools(PromptLocale.EN_US)
        val toolsByName = definitions.associateBy { tool ->
            val function = tool["function"] as JsonObject
            function["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }

        calendarTools.forEach { toolName ->
            val function = toolsByName.getValue(toolName)["function"] as JsonObject
            val parameters = function["parameters"] as JsonObject
            val properties = parameters["properties"] as JsonObject
            assertFalse(properties.containsKey("confirmed"))
            assertFalse(properties.containsKey("confirmationToken"))
        }
    }

    @Test
    fun `calendar read consent discloses provider sharing before access`() {
        val question = CalendarMutationConfirmationPolicy.question(
            toolName = "calendar_event_list",
            arguments = buildJsonObject { put("query", "planning") },
            english = true,
        )

        assertTrue(question.contains("read calendar event titles"))
        assertTrue(question.contains("sent to the active AI provider"))
        assertTrue(question.contains("only to this exact request"))
    }
}
