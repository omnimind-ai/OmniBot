package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.AgentFinalResponse
import cn.com.omnimind.bot.agent.AgentResult
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class XiaowanAcpPresentationBridgeTest {

    @Test
    fun `thinking start emits an empty ACP thought chunk for the existing card`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onThinkingStart()

        val thought = updates.filterIsInstance<SessionUpdate.AgentThoughtChunk>().single()
        assertEquals("", (thought.content as ContentBlock.Text).text)
        val namespace = (thought._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
        val reasoning = namespace["reasoning"] as JsonObject
        assertEquals("thinking", reasoning["stage"]?.jsonPrimitive?.content)
    }

    @Test
    fun `retry state is carried by the ACP assistant update`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onRetrying(
            retryCount = 1,
            maxRetries = 3,
            retryDelayMs = 1000,
            message = "请求失败，正在重试",
            retryReason = "timeout",
        )

        val message = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>().single()
        val namespace = (message._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
        val retry = namespace["retry"] as JsonObject
        assertEquals("1", retry["count"]?.jsonPrimitive?.content)
        assertEquals("3", retry["maxRetries"]?.jsonPrimitive?.content)
        assertEquals("1000", retry["delayMs"]?.jsonPrimitive?.content)
        assertEquals("timeout", retry["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `error recovery state is carried by the ACP assistant update`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onError("网络连接中断", retryable = true)

        val message = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>().single()
        assertEquals("网络连接中断", (message.content as ContentBlock.Text).text)
        val namespace = (message._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
        val recovery = namespace["recovery"] as JsonObject
        assertEquals("true", recovery["retryable"]?.jsonPrimitive?.content)
        assertEquals("false", recovery["continueable"]?.jsonPrimitive?.content)
    }

    @Test
    fun `clarification keeps its missing fields in ACP metadata`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onClarifyRequired("是否继续执行？", listOf("arguments.confirmed"))

        val message = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>().single()
        assertEquals("是否继续执行？", (message.content as ContentBlock.Text).text)
        val namespace = (message._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
        val clarification = namespace["clarification"] as JsonObject
        assertEquals("是否继续执行？", clarification["question"]?.jsonPrimitive?.content)
        assertEquals(
            "arguments.confirmed",
            (clarification["missingFields"] as JsonArray).single().jsonPrimitive.content,
        )
    }

    @Test
    fun `context compaction is carried by an ACP thought update`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onContextCompactionStateChanged(
            isCompacting = true,
            latestPromptTokens = 126000,
            promptTokenThreshold = 128000,
        )

        val thought = updates.filterIsInstance<SessionUpdate.AgentThoughtChunk>().single()
        val namespace = (thought._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
        val compaction = namespace["compaction"] as JsonObject
        assertEquals("compressing", compaction["status"]?.jsonPrimitive?.content)
        assertEquals("126000", compaction["latestPromptTokens"]?.jsonPrimitive?.content)
        assertEquals("128000", compaction["promptTokenThreshold"]?.jsonPrimitive?.content)
    }

    @Test
    fun `completion preserves the old turn usage footer data in ACP metadata`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onChatMessage("已完成", isFinal = false)
        bridge.onComplete(
            AgentResult.Success(
                response = AgentFinalResponse(content = "已完成"),
                executedTools = emptyList(),
                latestPromptTokens = 100,
                promptTokenThreshold = 128000,
                completionTokens = 20,
                cachedTokens = 10,
                cacheCreationTokens = 3,
                totalTokens = 120,
            )
        )

        val messages = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
        assertEquals(2, messages.size)
        val namespace = (messages.last()._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
        val usage = namespace["usage"] as JsonObject
        val turnUsage = usage["turnUsage"] as JsonObject
        assertEquals(100, turnUsage["ctx"]?.jsonPrimitive?.content?.toInt())
        assertEquals(100, turnUsage["in"]?.jsonPrimitive?.content?.toInt())
        assertEquals(20, turnUsage["out"]?.jsonPrimitive?.content?.toInt())
        assertEquals(10, turnUsage["cache"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `final performance metadata survives deduplication of an identical text snapshot`() =
        runBlocking {
            val updates = mutableListOf<SessionUpdate>()
            val bridge = XiaowanAcpEventBridge { updates += it }

            bridge.onChatMessage("最终回答", isFinal = false)
            bridge.onChatMessage(
                "最终回答",
                isFinal = true,
                prefillTokensPerSecond = 36.6,
                decodeTokensPerSecond = 12.4,
            )

            val messages = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
            assertEquals(2, messages.size)
            assertEquals(messages.first().messageId, messages.last().messageId)
            assertEquals("", (messages.last().content as ContentBlock.Text).text)
            val namespace = (messages.last()._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
            val usage = namespace["usage"] as JsonObject
            assertEquals(36.6, usage["prefillTokensPerSecond"]?.jsonPrimitive?.double ?: 0.0, 0.0)
            assertEquals(12.4, usage["decodeTokensPerSecond"]?.jsonPrimitive?.double ?: 0.0, 0.0)
        }

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

    @Test
    fun `permission tool result keeps the existing permission card payload`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onToolCallStart("call-permission", "vlm_task", JsonObject(emptyMap()))
        bridge.onToolCallComplete(
            "call-permission",
            "vlm_task",
            ToolExecutionResult.PermissionRequired(listOf("无障碍权限")),
        )

        val completion = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().last()
        val rawOutput = completion.rawOutput as JsonObject
        assertEquals("permission_section", rawOutput["type"]?.jsonPrimitive?.content)
        assertEquals(
            "无障碍权限",
            (rawOutput["missing"] as JsonArray).single().jsonPrimitive.content,
        )
    }
}
