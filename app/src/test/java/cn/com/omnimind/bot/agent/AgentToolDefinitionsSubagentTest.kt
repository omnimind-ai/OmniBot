package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolDefinitionsSubagentTest {

    @Test
    fun `subagent schema makes delegation explicit and profile guidance optional`() {
        val function = subagentFunction(PromptLocale.ZH_CN)
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject
        val tasks = properties["tasks"] as JsonObject
        val item = tasks["items"] as JsonObject
        val itemProperties = item["properties"] as JsonObject
        val profile = itemProperties["profileId"] as JsonObject
        val defaultProfile = properties["defaultProfileId"] as JsonObject
        val profileIds = (profile["enum"] as JsonArray).map {
            it.jsonPrimitive.contentOrNull
        }

        assertEquals("object", item["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(listOf("instruction"), (item["required"] as JsonArray).map {
            it.jsonPrimitive.contentOrNull
        })
        assertEquals(
            listOf("general", "explorer", "memory-curator", "planner"),
            profileIds
        )
        assertEquals(profile["enum"], defaultProfile["enum"])
        assertTrue(
            function["description"]?.jsonPrimitive?.contentOrNull
                ?.contains("隔离上下文") == true
        )
        assertTrue(
            function["description"]?.jsonPrimitive?.contentOrNull
                ?.contains("用户明确要求分派或并行") == true
        )
        assertTrue(
            profile["description"]?.jsonPrimitive?.contentOrNull
                ?.contains("继承当前 harness 已提供的能力") == true
        )
    }

    @Test
    fun `english subagent schema explains delegation boundaries`() {
        val function = subagentFunction(PromptLocale.EN_US)
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject
        val tasks = properties["tasks"] as JsonObject
        val item = tasks["items"] as JsonObject
        val itemProperties = item["properties"] as JsonObject
        val instruction = itemProperties["instruction"] as JsonObject
        val profile = itemProperties["profileId"] as JsonObject

        assertTrue(
            function["description"]?.jsonPrimitive?.contentOrNull
                ?.contains("isolated contexts") == true
        )
        assertTrue(
            instruction["description"]?.jsonPrimitive?.contentOrNull
                ?.contains("self-contained") == true
        )
        assertTrue(
            profile["description"]?.jsonPrimitive?.contentOrNull
                ?.contains("inherits the capabilities provided by the current harness") == true
        )
    }

    private fun subagentFunction(locale: PromptLocale): JsonObject {
        val definition = AgentToolDefinitions.subagentTools(locale).single()
        return definition["function"] as JsonObject
    }
}
