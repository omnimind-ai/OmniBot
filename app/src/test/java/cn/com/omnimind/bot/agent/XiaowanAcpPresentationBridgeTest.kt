package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.bot.agent.ToolExecutionResult
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class XiaowanAcpPresentationBridgeTest {

    @Test
    fun `structured thinking emits a display delta instead of raw JSON`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onThinkingUpdate(
            """{"task_description":"检查统一卡片","sub_tasks":["保留工具结果"],"preparation":"确认 ACP 流"}"""
        )

        val thought = updates.filterIsInstance<SessionUpdate.AgentThoughtChunk>().single()
        val text = (thought.content as ContentBlock.Text).text
        assertEquals("检查统一卡片\n\n- 保留工具结果\n\n确认 ACP 流", text)
    }

    @Test
    fun `tool completion keeps structured terminal result in ACP raw output`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onToolCallStart("call-1", "terminal", JsonObject(emptyMap()))
        bridge.onToolCallComplete(
            "call-1",
            "terminal",
            ToolExecutionResult.TerminalResult(
                toolName = "terminal",
                summaryText = "Command completed",
                previewJson = "{\"exitCode\":0}",
                rawResultJson = "{\"stdout\":\"hello\"}",
                terminalOutput = "hello",
                terminalSessionId = "shell-1",
            ),
        )

        val completion = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().last()
        val rawOutput = completion.rawOutput as JsonObject
        assertEquals("terminal", rawOutput["toolType"]?.jsonPrimitive?.content)
        assertEquals("Command completed", rawOutput["summary"]?.jsonPrimitive?.content)
        assertEquals("hello", rawOutput["terminalOutput"]?.jsonPrimitive?.content)
        assertEquals("shell-1", rawOutput["terminalSessionId"]?.jsonPrimitive?.content)
        assertEquals("0", (rawOutput["result"] as JsonObject)["exitCode"]?.jsonPrimitive?.content)
    }
}
