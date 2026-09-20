package cn.com.omnimind.bot.plugin.official

import cn.com.omnimind.bot.omniflow.RuntimeTool
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OmniFlowManagementToolsTest {
    @Test fun `new package tool keeps its schema without a Kotlin registration branch`() {
        val projected = runtimeToolDefinition(RuntimeTool(
            name = "package_added_tool", description = "Provided by package",
            inputSchema = mapOf("type" to "object", "required" to listOf("query"),
                "properties" to mapOf("query" to mapOf("type" to "string"))),
        ))
        assertEquals("package_added_tool", projected.name)
        assertEquals("Provided by package", projected.description)
        assertEquals("query", projected.parameters["required"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("string", projected.parameters["properties"]!!.jsonObject["query"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }
}
