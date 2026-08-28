package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.llm.SceneModelBindingEntry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.AgentFinalResponse
import cn.com.omnimind.bot.agent.AgentResult
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolKind
import com.agentclientprotocol.model.ToolCallStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class XiaowanAcpPresentationBridgeTest {
    @Test
    fun `permission outcome requires an explicit allow option`() {
        val json = Json { ignoreUnknownKeys = true }
        assertTrue(
            isAllowedAcpPermissionOutcome(
                json.parseToJsonElement(
                    """{"outcome":{"outcome":"selected","optionId":"allow_once"}}"""
                )
            )
        )
        assertFalse(
            isAllowedAcpPermissionOutcome(
                json.parseToJsonElement(
                    """{"outcome":{"outcome":"selected","optionId":"reject_once"}}"""
                )
            )
        )
        assertFalse(
            isAllowedAcpPermissionOutcome(
                json.parseToJsonElement(
                    """{"outcome":{"outcome":"selected"}}"""
                )
            )
        )
    }


    @Test
    fun `provider finish reasons map to ACP stop reasons`() {
        assertEquals(StopReason.END_TURN, acpStopReasonForFinishReason("stop"))
        assertEquals(StopReason.MAX_TOKENS, acpStopReasonForFinishReason("length"))
        assertEquals(StopReason.CANCELLED, acpStopReasonForFinishReason("cancelled"))
        assertEquals(StopReason.REFUSAL, acpStopReasonForFinishReason("content_filter"))
        assertEquals(StopReason.MAX_TURN_REQUESTS, acpStopReasonForFinishReason("turn_limit"))
    }

    @Test
    fun `ordinary clarification is emitted as text without clarification metadata`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onClarifyRequired("需要确认目标文件", listOf("path"))

        val message = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>().single()
        assertEquals("需要确认目标文件", (message.content as ContentBlock.Text).text)
        assertTrue(message._meta is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `declared runtime tool type is used for ACP kind`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onToolCallStart(
            "call-custom",
            "vendor_action_with_no_standard_name",
            JsonObject(emptyMap()),
            "terminal",
        )

        val tool = updates.filterIsInstance<SessionUpdate.ToolCall>().single()
        assertEquals(ToolKind.EXECUTE, tool.kind)
    }

    @Test
    fun `legacy tool start without an ACP id is ignored`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onToolCallStart("android_privileged_action", JsonObject(emptyMap()))

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `replayed tool start with the same ACP id is idempotent`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onToolCallStart("call-1", "android_privileged_action", JsonObject(emptyMap()))
        bridge.onToolCallStart("call-1", "android_privileged_action", JsonObject(emptyMap()))
        bridge.onToolCallComplete(
            "call-1",
            "android_privileged_action",
            ToolExecutionResult.Error("android_privileged_action", "done"),
        )
        bridge.onToolCallStart("call-1", "android_privileged_action", JsonObject(emptyMap()))

        assertEquals(1, updates.filterIsInstance<SessionUpdate.ToolCall>().size)
    }

    @Test
    fun `orphan tool completion does not invent an ACP call id`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onToolCallComplete("android_privileged_action", ToolExecutionResult.Error("android_privileged_action", "missing"))

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `orphan tool progress does not invent an ACP call id`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onToolCallProgress("android_privileged_action", "执行中")

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `permission waiting updates the existing ACP tool call`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onToolCallStart(
            "call-privileged",
            "android_privileged_action",
            JsonObject(emptyMap()),
        )
        bridge.emitToolPending(
            toolCallId = "call-privileged",
            title = "需要确认高权限操作",
            detail = "命令尚未执行，请确认。",
        )

        val toolUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
        assertEquals(1, toolUpdates.size)
        assertEquals("call-privileged", toolUpdates.single().toolCallId.value)
        assertEquals(ToolCallStatus.PENDING, toolUpdates.single().status)
    }

    @Test
    fun `xiaowan exposes the bound model plus provider catalog`() {
        val models = requireNotNull(
            buildXiaowanModelsFromBinding(
                SceneModelBindingEntry("scene", "provider", "selected"),
                listOf(
                    ProviderModelOption(id = "selected", displayName = "Selected"),
                    ProviderModelOption(id = "other", displayName = "Other"),
                ),
            )
        )

        assertEquals("selected,other", models.available.joinToString(",") { it.modelId.value })
        assertEquals("selected", models.configuredModelId)
    }

    @Test
    fun `ACP prompt metadata restores terminal environment for Xiaowan tools`() {
        val environment = xiaowanTerminalEnvironmentFromMeta(
            JsonObject(
                mapOf(
                    "terminalEnvironment" to JsonObject(
                        mapOf(
                            "API_ENDPOINT" to JsonPrimitive("https://example.test"),
                            "EMPTY_VALUE" to JsonPrimitive(""),
                        )
                    )
                )
            )
        )

        assertEquals(
            mapOf(
                "API_ENDPOINT" to "https://example.test",
                "EMPTY_VALUE" to "",
            ),
            environment,
        )
    }

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
    fun `reasoning after a tool starts a new ACP thought segment`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onThinkingStart()
        bridge.onThinkingUpdate("先分析")
        bridge.onToolCallStart("call-1", "terminal", JsonObject(emptyMap()))
        bridge.onThinkingUpdate("工具结果返回后继续分析")

        val thoughts = updates
            .filterIsInstance<SessionUpdate.AgentThoughtChunk>()
            .filter { (it.content as ContentBlock.Text).text.isNotEmpty() }
        assertEquals(2, thoughts.size)
        assertEquals(2, thoughts.map { it.messageId }.distinct().size)
        assertEquals("先分析", (thoughts[0].content as ContentBlock.Text).text)
        assertEquals(
            "工具结果返回后继续分析",
            (thoughts[1].content as ContentBlock.Text).text,
        )
        val segments = thoughts.map { thought ->
            val namespace = (thought._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
            val reasoning = namespace["reasoning"] as JsonObject
            reasoning["segmentIndex"]?.jsonPrimitive?.content?.toInt()
        }
        assertEquals(listOf(0, 1), segments)
    }

    @Test
    fun `tool boundary does not create an empty thought card before later reasoning`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onThinkingStart()
        bridge.onThinkingUpdate("工具前的思考")
        bridge.onToolCallStart("call-1", "terminal", JsonObject(emptyMap()))
        // AgentOrchestrator starts another model round after the tool. The
        // next round may produce another tool without producing reasoning.
        bridge.onThinkingStart()
        bridge.onThinkingUpdate("工具后的思考")

        val thoughts = updates.filterIsInstance<SessionUpdate.AgentThoughtChunk>()
        assertEquals(1, thoughts.count { (it.content as ContentBlock.Text).text.isEmpty() })
        assertEquals(
            listOf("工具前的思考", "工具后的思考"),
            thoughts
                .map { (it.content as ContentBlock.Text).text }
                .filter(String::isNotEmpty),
        )
        val nonEmptyIds = thoughts
            .filter { (it.content as ContentBlock.Text).text.isNotEmpty() }
            .map { it.messageId }
        assertEquals(2, nonEmptyIds.distinct().size)
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
    fun `retry starts a new reasoning segment instead of reusing the failed one`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onThinkingStart()
        bridge.onThinkingUpdate("失败请求的思考")
        bridge.onRetrying(
            retryCount = 1,
            maxRetries = 2,
            retryDelayMs = 0,
            message = "正在重试",
            retryReason = "timeout",
        )
        bridge.onThinkingUpdate("成功重试的思考")

        val thoughts = updates
            .filterIsInstance<SessionUpdate.AgentThoughtChunk>()
            .filter { (it.content as ContentBlock.Text).text.isNotEmpty() }
        assertEquals(2, thoughts.size)
        assertEquals(2, thoughts.map { it.messageId }.distinct().size)
        val segments = thoughts.map { thought ->
            val namespace = (thought._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
            (namespace["reasoning"] as JsonObject)["segmentIndex"]?.jsonPrimitive?.content?.toInt()
        }
        assertEquals(listOf(0, 1), segments)
    }

    @Test
    fun `retry assigns a new generation id to the next reasoning segment`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onThinkingStart()
        bridge.onThinkingUpdate("第一代思考")
        bridge.onRetrying(
            retryCount = 1,
            maxRetries = 2,
            retryDelayMs = 0,
            message = "正在重试",
            retryReason = "timeout",
        )
        bridge.onThinkingUpdate("第二代思考")

        val thoughts = updates.filterIsInstance<SessionUpdate.AgentThoughtChunk>()
            .filter { (it.content as ContentBlock.Text).text.isNotEmpty() }
        val generationIds = thoughts.map { thought ->
            val namespace = (thought._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
            ((namespace["reasoning"] as JsonObject)["generationId"] ?: error("missing generation"))
                .jsonPrimitive.content
        }
        assertEquals(2, generationIds.distinct().size)
    }

    @Test
    fun `provider snapshot reset starts a new reasoning segment`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onThinkingStart()
        bridge.onThinkingUpdate("第一次请求的思考")
        // This is what an internal HTTP retry looks like to the callback: the
        // retry is transparent, so there is no separate onRetrying event.
        bridge.onThinkingUpdate("重试请求的思考")

        val thoughts = updates
            .filterIsInstance<SessionUpdate.AgentThoughtChunk>()
            .filter { (it.content as ContentBlock.Text).text.isNotEmpty() }
        assertEquals(2, thoughts.size)
        assertEquals(2, thoughts.map { it.messageId }.distinct().size)
        val segments = thoughts.map { thought ->
            val namespace = (thought._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
            (namespace["reasoning"] as JsonObject)["segmentIndex"]?.jsonPrimitive?.content
        }
        assertEquals(listOf("0", "1"), segments)
    }

    @Test
    fun `provider snapshot reset does not fabricate a connection retry card`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onChatMessage("第一代答案", isFinal = false)
        bridge.onChatMessage("新一代答案", isFinal = true)

        val messages = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
        assertEquals(2, messages.size)
        assertTrue(
            messages.none { message ->
                val namespace = (message._meta as? JsonObject)
                    ?.get("cn.com.omnimind.agent") as? JsonObject
                namespace?.containsKey("retry") == true
            }
        )
    }

    @Test
    fun `retry separates partial assistant output from the next generation`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onChatMessage("失败请求的半截答案", isFinal = false)
        bridge.onRetrying(
            retryCount = 1,
            maxRetries = 2,
            retryDelayMs = 0,
            message = "正在重试",
            retryReason = "timeout",
        )
        bridge.onChatMessage("重试后的完整答案", isFinal = true)

        val messages = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
        assertEquals(3, messages.size)
        assertEquals(2, messages.map { it.messageId }.distinct().size)
        assertEquals(
            "失败请求的半截答案",
            (messages[0].content as ContentBlock.Text).text,
        )
        assertEquals(
            "重试后的完整答案",
            (messages[2].content as ContentBlock.Text).text,
        )
        val retryNamespace = (messages[1]._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
        assertEquals("正在重试", (retryNamespace["retry"] as JsonObject)["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `legacy progress without tool id cannot cross-wire parallel same-name tools`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onToolCallStart("call-1", "file_read", JsonObject(emptyMap()))
        bridge.onToolCallStart("call-2", "file_read", JsonObject(emptyMap()))
        bridge.onToolCallProgress("file_read", "读取中", emptyMap())

        val progressIds = updates
            .filterIsInstance<SessionUpdate.ToolCallUpdate>()
            .filter { it.status == com.agentclientprotocol.model.ToolCallStatus.IN_PROGRESS }
            .map { it.toolCallId.value }
        assertEquals(listOf("call-1", "call-2"), progressIds)
    }

    @Test
    fun `legacy completion without tool id does not complete an ambiguous call`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onToolCallStart("call-1", "file_read", JsonObject(emptyMap()))
        bridge.onToolCallStart("call-2", "file_read", JsonObject(emptyMap()))
        bridge.onToolCallComplete(
            "file_read",
            ToolExecutionResult.ChatMessage("不应猜测归属"),
        )

        val completed = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
            .filter { it.status == com.agentclientprotocol.model.ToolCallStatus.COMPLETED }
        assertEquals(0, completed.size)
    }

    @Test
    fun `Xiaowan tool names are projected to official ACP kinds`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onToolCallStart("read-1", "file_read", JsonObject(emptyMap()))
        bridge.onToolCallStart("edit-1", "apply_patch", JsonObject(emptyMap()))
        bridge.onToolCallStart("exec-1", "terminal_exec", JsonObject(emptyMap()))
        bridge.onToolCallStart("search-1", "workspace_search", JsonObject(emptyMap()))

        val kinds = updates.filterIsInstance<SessionUpdate.ToolCall>().associate {
            it.toolCallId.value to it.kind
        }
        assertEquals(ToolKind.READ, kinds["read-1"])
        assertEquals(ToolKind.EDIT, kinds["edit-1"])
        assertEquals(ToolKind.EXECUTE, kinds["exec-1"])
        assertEquals(ToolKind.SEARCH, kinds["search-1"])
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
    fun `partial error keeps recovery on the existing ACP assistant message`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(canContinue = true) { updates += it }

        bridge.onChatMessage("半截答案", isFinal = false)
        bridge.onError("连接中断", retryable = true)

        val messages = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
        assertEquals(2, messages.size)
        assertEquals(messages[0].messageId, messages[1].messageId)
        assertEquals("", (messages[1].content as ContentBlock.Text).text)
        val namespace = (messages[1]._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
        val recovery = namespace["recovery"] as JsonObject
        assertEquals("true", recovery["retryable"]?.jsonPrimitive?.content)
        assertEquals("true", recovery["continueable"]?.jsonPrimitive?.content)
        assertEquals("false", recovery["persistAsError"]?.jsonPrimitive?.content)
        assertEquals(
            "approximate",
            recovery["continueResumeMode"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `ordinary clarification stays plain assistant text`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onClarifyRequired("是否继续执行？", listOf("arguments.confirmed"))

        val message = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>().single()
        assertEquals("是否继续执行？", (message.content as ContentBlock.Text).text)
        assertEquals(kotlinx.serialization.json.JsonNull, message._meta)
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
    fun `ACP prompt response does not count cached input twice`() {
        val success = AgentResult.Success(
            response = AgentFinalResponse(content = "已完成"),
            executedTools = emptyList(),
            latestPromptTokens = 2_057,
            completionTokens = 5,
            cachedTokens = 2_048,
            cacheCreationTokens = 3,
        )

        val usage = requireNotNull(success.toAcpUsage())
        assertEquals(6L, usage.inputTokens)
        assertEquals(2_048L, usage.cachedReadTokens)
        assertEquals(3L, usage.cachedWriteTokens)

        val turnUsage = PromptResponse(
            stopReason = StopReason.END_TURN,
            usage = usage,
        ).toAcpTurnUsageUpdate(messageId = MessageId("msg_usage"))
            ?.get("_meta")
            ?.let { it as Map<*, *> }
            ?.get("cn.com.omnimind.agent")
            ?.let { it as Map<*, *> }
            ?.get("usage")
            ?.let { it as Map<*, *> }
            ?.get("turnUsage")
            ?.let { it as Map<*, *> }

        assertEquals(2_057L, turnUsage?.get("ctx"))
        assertEquals(2_057L, turnUsage?.get("in"))
        assertEquals(2_048L, turnUsage?.get("cache"))
        assertEquals(6L, turnUsage?.get("uncachedInputTokens"))
    }

    @Test
    fun `completion keeps output-only usage when prompt tokens are unavailable`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onComplete(
            AgentResult.Success(
                response = AgentFinalResponse(content = "已完成"),
                executedTools = emptyList(),
                completionTokens = 20,
            )
        )

        val message = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>().single()
        val namespace = (message._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
        val usage = namespace["usage"] as JsonObject
        val turnUsage = usage["turnUsage"] as JsonObject
        assertEquals("0", turnUsage["ctx"]?.jsonPrimitive?.content)
        assertEquals("20", turnUsage["out"]?.jsonPrimitive?.content)
    }

    @Test
    fun `prompt token usage is emitted as the standard ACP usage update`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onPromptTokenUsageChanged(100, 128_000)

        val usage = updates.filterIsInstance<SessionUpdate.UsageUpdate>().single()
        assertEquals(100L, usage.used)
        assertEquals(128_000L, usage.size)
        assertEquals(kotlinx.serialization.json.JsonNull, usage._meta)
    }

    @Test
    fun `completion projects legacy output state and restores empty output fallback`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onComplete(
            AgentResult.Success(
                response = AgentFinalResponse(content = ""),
                executedTools = emptyList(),
                outputKind = "none",
                hasUserVisibleOutput = false,
            )
        )

        val message = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>().single()
        assertEquals("暂时无法生成回复，请重试。", (message.content as ContentBlock.Text).text)
        val namespace = (message._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
        val completion = namespace["completion"] as JsonObject
        assertEquals("none", completion["outputKind"]?.jsonPrimitive?.content)
        assertEquals("false", completion["hasUserVisibleOutput"]?.jsonPrimitive?.content)
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
    fun `legacy thinking fields stay inside the official ACP thought chunk`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onThinkingUpdate(
            """{"task_description":"恢复旧能力","summary":"已完成分析","stage":"planning","phase":"prepare"}"""
        )

        val thought = updates.filterIsInstance<SessionUpdate.AgentThoughtChunk>().single()
        val namespace = (thought._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
        val reasoning = namespace["reasoning"] as JsonObject
        assertEquals("已完成分析", reasoning["summary"]?.jsonPrimitive?.content)
        assertEquals("planning", reasoning["stage"]?.jsonPrimitive?.content)
        assertEquals("prepare", reasoning["phase"]?.jsonPrimitive?.content)
    }

    @Test
    fun `partial structured thinking streams readable deltas without raw json`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onThinkingUpdate("""{"task_description":"检查统一""")
        bridge.onThinkingUpdate(
            """{"task_description":"检查统一卡片","sub_tasks":["保留工具"""
        )
        bridge.onThinkingUpdate(
            """{"task_description":"检查统一卡片","sub_tasks":["保留工具结果"],"preparation":"确认 ACP 流"}"""
        )

        val thoughts = updates.filterIsInstance<SessionUpdate.AgentThoughtChunk>()
        val combined = thoughts.joinToString("") { (it.content as ContentBlock.Text).text }
        assertEquals("检查统一卡片\n\n- 保留工具结果\n\n确认 ACP 流", combined)
        assertEquals(false, combined.contains('{'))
        assertEquals(1, thoughts.map { it.messageId }.distinct().size)
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
        assertEquals("{\"exitCode\":0}", rawOutput["previewJson"]?.jsonPrimitive?.content)
        assertEquals("{\"stdout\":\"hello\"}", rawOutput["rawResultJson"]?.jsonPrimitive?.content)
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

    @Test
    fun `clarification tool result uses ACP pending status`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onToolCallStart("call-confirm", "android_privileged_action", JsonObject(emptyMap()))
        bridge.onToolCallComplete(
            "call-confirm",
            "android_privileged_action",
            ToolExecutionResult.Clarify(
                question = "确认执行高权限 shell 命令？",
                missingFields = listOf("arguments.confirmed"),
            ),
        )

        val completion = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().last()
        assertEquals(ToolCallStatus.PENDING, completion.status)
        val rawOutput = completion.rawOutput as JsonObject
        assertEquals("确认执行高权限 shell 命令？", rawOutput["question"]?.jsonPrimitive?.content)
    }
}
