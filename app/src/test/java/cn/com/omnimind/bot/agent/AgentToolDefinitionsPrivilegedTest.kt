package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.shizuku.ShizukuBackend
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolDefinitionsPrivilegedTest {

    @Test
    fun `android privileged action documents that shell remains locally gated`() {
        val tool = AgentToolDefinitions.androidPrivilegedActionTool(
            visibleActions = listOf("diagnostics.getprop", "shell.exec"),
            backend = ShizukuBackend.ADB,
            locale = PromptLocale.EN_US
        )
        val function = tool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject
        val action = properties["action"] as JsonObject
        val enumValues = action["enum"] as JsonArray

        assertTrue(enumValues.any { it.jsonPrimitive.contentOrNull == "shell.exec" })
        assertTrue(
            function["description"]?.jsonPrimitive?.contentOrNull?.contains("remain disabled") ==
                true
        )
        assertFalse(properties.containsKey("confirmed"))
    }

    @Test
    fun `privileged session exec requires session id and command`() {
        val tool = AgentToolDefinitions.androidPrivilegedSessionExecTool(
            backend = ShizukuBackend.ROOT,
            locale = PromptLocale.ZH_CN
        )
        val function = tool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val required = parameters["required"] as JsonArray

        assertTrue(required.any { it.jsonPrimitive.contentOrNull == "sessionId" })
        assertTrue(required.any { it.jsonPrimitive.contentOrNull == "command" })
    }

    @Test
    fun `privileged session start does not expose model-controlled confirmation`() {
        val tool = AgentToolDefinitions.androidPrivilegedSessionStartTool(
            backend = ShizukuBackend.ADB,
            locale = PromptLocale.EN_US
        )
        val function = tool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject
        assertFalse(properties.containsKey("confirmed"))
        assertFalse(properties.containsKey("confirmationToken"))
        assertFalse(properties.containsKey("locallyApproved"))
    }
}
