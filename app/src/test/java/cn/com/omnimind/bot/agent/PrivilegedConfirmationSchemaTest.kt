package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.shizuku.ShizukuBackend
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedConfirmationSchemaTest {
    @Test
    fun `privileged schemas expose no model controlled confirmation field`() {
        val definitions = listOf(
            AgentToolDefinitions.androidPrivilegedActionTool(
                visibleActions = listOf("safe_test_action"),
                backend = ShizukuBackend.ADB,
                locale = PromptLocale.EN_US,
            ),
            AgentToolDefinitions.androidPrivilegedSessionStartTool(ShizukuBackend.ADB, PromptLocale.EN_US),
            AgentToolDefinitions.androidPrivilegedSessionExecTool(ShizukuBackend.ADB, PromptLocale.EN_US),
        )

        definitions.forEach { tool ->
            val function = tool["function"] as JsonObject
            val parameters = function["parameters"] as JsonObject
            val properties = parameters["properties"] as JsonObject
            assertFalse(properties.containsKey("confirmed"))
            assertFalse(properties.containsKey("confirmationToken"))
        }

        val actionDescription = ((definitions.first()["function"] as JsonObject)["description"])
            ?.jsonPrimitive
            ?.contentOrNull
            .orEmpty()
        assertTrue(actionDescription.contains("model arguments cannot authorize"))
    }
}
