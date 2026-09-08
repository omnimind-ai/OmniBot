package cn.com.omnimind.bot.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentEventAdapterTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val adapter = AgentEventAdapter(json)

    @Test
    fun `file and skill output survives the model envelope without content loss`() {
        for (toolName in listOf("file_read", "skills_read")) {
            for (raw in listOf("ok", "开头" + "正文".repeat(100_000) + "END")) {
                val result = ToolExecutionResult.ContextResult(
                    toolName = toolName,
                    summaryText = raw,
                    previewJson = raw,
                    rawResultJson = raw,
                )
                val payload = json.parseToJsonElement(adapter.toolResultContent(
                    descriptor = AgentToolRegistry.RuntimeToolDescriptor(toolName, toolName, "file"),
                    result = result,
                    extras = emptyMap(),
                )).jsonObject

                assertEquals(raw, payload["rawResultJson"]?.jsonPrimitive?.content)
                assertEquals(raw, payload["previewJson"]?.jsonPrimitive?.content)
                assertEquals(raw, payload["summary"]?.jsonPrimitive?.content)
                assertFalse(payload.containsKey("outputTruncated"))
            }
        }
    }
}
