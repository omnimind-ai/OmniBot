package cn.com.omnimind.bot.agent

import cn.com.omnimind.bot.agent.runtime.toolResultAcpPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class XiaowanToolResultPayloadTest {
    private val json = Json

    @Test
    fun `identical result bodies are transmitted once regardless of tool name or result category`() {
        val marker = "UNIQUE_BODY_" + "内容😀".repeat(16_000)
        val body = JsonObject(mapOf("content" to JsonPrimitive(marker))).toString()
        val results = listOf(
            ToolExecutionResult.ContextResult("file_read", "Read", body, body),
            ToolExecutionResult.ContextResult("custom_fetch_document", "Read", body, body),
            ToolExecutionResult.McpResult("plugin_blob", "test-server", "Read", body, body),
            ToolExecutionResult.MemoryResult("memory_read", "Read", body, body),
            ToolExecutionResult.TerminalResult("terminal", "Read", body, body),
            ToolExecutionResult.Interrupted("terminal", "Stopped", body, body),
        )
        for (result in results) {
            val ui = toolResultAcpPayload(result)
            assertEquals(marker, ui["result"]!!.jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals(1, ui.toString().split("UNIQUE_BODY_").size - 1)
            val model = AgentEventAdapter(json).toolResultContent(
                AgentToolRegistry.RuntimeToolDescriptor("registered_tool", "Read", "context"), result,
            )
            assertEquals(body, json.parseToJsonElement(model).jsonObject["rawResultJson"]!!.jsonPrimitive.content)
            assertEquals(1, model.split("UNIQUE_BODY_").size - 1)
        }
    }

    @Test
    fun `different preview and raw output remain complete including non JSON text`() {
        for (raw in listOf("plain output\nEND", "{\"content\":\"正文 END\"}")) {
            val result = ToolExecutionResult.ContextResult("custom_read", "Read", "{\"count\":2}", raw)
            val ui = toolResultAcpPayload(result)
            assertEquals("custom_read", ui["toolName"]!!.jsonPrimitive.content)
            assertEquals("2", ui["result"]!!.jsonObject["count"]!!.jsonPrimitive.content)
            assertEquals(raw, ui["rawResultJson"]!!.jsonPrimitive.content)
            assertFalse(ui.containsKey("previewJson"))
            assertFalse(ui.containsKey("rawResult"))
            val model = json.parseToJsonElement(AgentEventAdapter(json).toolResultContent(
                AgentToolRegistry.RuntimeToolDescriptor("custom_read", "Read", "context"), result,
            )).jsonObject
            assertEquals(raw, model["rawResultJson"]!!.jsonPrimitive.content)
            assertEquals(result.previewJson, model["previewJson"]!!.jsonPrimitive.content)
        }
    }
}
