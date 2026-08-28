package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.database.AgentConversationEntry
import cn.com.omnimind.baselib.database.AgentConversationEntryRecord
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class AgentConversationHistorySupportTest {
    private val gson = Gson()
    private val canonicalJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Test
    fun `paged history keeps legacy normal entries after agent migration`() {
        val legacyEntry = AgentConversationEntry(
            id = 1,
            conversationId = 7,
            conversationMode = "normal",
            entryId = "legacy-user",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_USER_MESSAGE,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = "旧对话内容",
            payloadJson = "{}",
            createdAt = 1,
            updatedAt = 1
        )
        val canonicalEntry = legacyEntry.copy(
            id = 2,
            conversationMode = "agent",
            entryId = "new-user",
            summary = "新对话内容",
            createdAt = 2,
            updatedAt = 2
        )

        val (page, hasMore) = AgentConversationHistoryRepository.pageConversationEntries(
            entries = listOf(canonicalEntry, legacyEntry),
            limit = 20,
            offset = 0
        )

        assertEquals(listOf("new-user", "legacy-user"), page.map { it.entryId })
        assertFalse(hasMore)
    }

    @Test
    fun `paged history reports more entries outside the bounded window`() {
        val entries = (1..3).map { index ->
            AgentConversationEntry(
                id = index.toLong(),
                conversationId = 7,
                conversationMode = "agent",
                entryId = "entry-$index",
                entryType = AgentConversationHistoryRepository.ENTRY_TYPE_USER_MESSAGE,
                status = AgentConversationHistoryRepository.STATUS_SUCCESS,
                summary = "消息 $index",
                payloadJson = "{}",
                createdAt = index.toLong(),
                updatedAt = index.toLong()
            )
        }

        val (page, hasMore) = AgentConversationHistoryRepository.pageConversationEntries(
            entries = entries,
            limit = 1,
            offset = 0
        )

        assertEquals(listOf("entry-3"), page.map { it.entryId })
        assertTrue(hasMore)
    }

    @Test
    fun `stale ui snapshot cannot delete a pending external user message`() {
        val externalUser = mapOf<String, Any?>(
            "id" to "web-run-user",
            "type" to 1,
            "user" to 1,
            "content" to mapOf("id" to "web-run-user", "text" to "来自 WebUI"),
            "streamMeta" to AgentConversationHistorySupport.externalUserMessageStreamMeta(),
            "createAt" to "2026-07-23T05:00:00Z"
        )
        val assistant = mapOf<String, Any?>(
            "id" to "reply-1",
            "type" to 1,
            "user" to 2,
            "content" to mapOf("id" to "reply-1", "text" to "已收到"),
            "createAt" to "2026-07-23T05:00:01Z"
        )

        val merged = AgentConversationHistorySupport.mergePendingExternalUserMessages(
            existingMessages = listOf(externalUser),
            incomingMessages = listOf(assistant)
        )

        assertEquals(listOf("web-run-user", "reply-1"), merged.map { it["id"] })
    }

    @Test
    fun `ui snapshot acknowledges an external user message before later removal`() {
        val externalUser = mapOf<String, Any?>(
            "id" to "web-run-user",
            "type" to 1,
            "user" to 1,
            "content" to mapOf("id" to "web-run-user", "text" to "来自 WebUI"),
            "streamMeta" to AgentConversationHistorySupport.externalUserMessageStreamMeta()
        )

        val acknowledged = AgentConversationHistorySupport
            .mergePendingExternalUserMessages(
                existingMessages = listOf(externalUser),
                incomingMessages = listOf(externalUser)
            )
            .single()
        assertNull(acknowledged["streamMeta"])

        val laterSnapshot = AgentConversationHistorySupport
            .mergePendingExternalUserMessages(
                existingMessages = listOf(acknowledged),
                incomingMessages = emptyList()
            )
        assertTrue(laterSnapshot.isEmpty())
    }

    @Test
    fun `mergeToolPayload keeps args and final status across tool lifecycle`() {
        val startPayload = mapOf(
            "toolName" to "browser_use",
            "displayName" to "浏览器自动化",
            "toolType" to "builtin",
            "argsJson" to """{"url":"https://example.com","steps":2}""",
            "summary" to "打开页面"
        )
        val progressPayload = mapOf(
            "progress" to "正在分析页面",
            "summary" to "正在分析页面"
        )
        val completePayload = mapOf(
            "status" to AgentConversationHistoryRepository.STATUS_SUCCESS,
            "summary" to "已完成页面分析",
            "resultPreviewJson" to """{"message":"done"}""",
            "rawResultJson" to """{"message":"done","details":"very long raw"}""",
            "success" to true
        )

        val mergedProgress = AgentConversationHistorySupport.mergeToolPayload(
            existing = startPayload,
            incoming = progressPayload,
            fallbackStatus = AgentConversationHistoryRepository.STATUS_RUNNING,
            fallbackSummary = "正在调用工具"
        )
        val mergedComplete = AgentConversationHistorySupport.mergeToolPayload(
            existing = mergedProgress,
            incoming = completePayload,
            fallbackStatus = AgentConversationHistoryRepository.STATUS_SUCCESS,
            fallbackSummary = "已完成页面分析"
        )

        assertEquals(
            """{"url":"https://example.com","steps":2}""",
            mergedComplete["argsJson"]
        )
        assertEquals(
            AgentConversationHistoryRepository.STATUS_SUCCESS,
            mergedComplete["status"]
        )
        assertEquals("已完成页面分析", mergedComplete["summary"])
        assertEquals("""{"message":"done"}""", mergedComplete["resultPreviewJson"])
    }

    @Test
    fun `mergeToolPayload preserves timeout metadata`() {
        val runningPayload = mapOf(
            "toolName" to "terminal_execute",
            "displayName" to "终端执行",
            "toolType" to "terminal",
            "status" to AgentConversationHistoryRepository.STATUS_RUNNING,
            "terminalOutput" to "hello\n"
        )
        val timeoutPayload = mapOf(
            "status" to AgentConversationHistoryRepository.STATUS_TIMEOUT,
            "summary" to "终端命令等待超时，可能仍在后台继续运行。",
            "timedOut" to true,
            "terminalOutputDelta" to "world\n"
        )

        val merged = AgentConversationHistorySupport.mergeToolPayload(
            existing = runningPayload,
            incoming = timeoutPayload,
            fallbackStatus = AgentConversationHistoryRepository.STATUS_TIMEOUT,
            fallbackSummary = "终端命令等待超时，可能仍在后台继续运行。"
        )

        assertEquals(
            AgentConversationHistoryRepository.STATUS_TIMEOUT,
            merged["status"]
        )
        assertEquals(true, merged["timedOut"])
        assertEquals("hello\nworld\n", merged["terminalOutput"])
    }

    @Test
    fun `buildPromptSeedFromEntries replays compact tool history in chronological order`() {
        val userEntry = AgentConversationEntry(
            id = 1,
            conversationId = 7,
            conversationMode = "normal",
            entryId = "u1",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_USER_MESSAGE,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = "查看 example",
            payloadJson = """
                {"id":"u1","type":1,"user":1,"content":{"text":"查看 example","id":"u1"},"createAt":"2026-03-27T00:00:00Z"}
            """.trimIndent(),
            createdAt = 1,
            updatedAt = 1
        )
        val assistantEntry = AgentConversationEntry(
            id = 2,
            conversationId = 7,
            conversationMode = "normal",
            entryId = "a1",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_ASSISTANT_MESSAGE,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = "assistant should start being replayed",
            payloadJson = """
                {"id":"a1","type":1,"user":2,"content":{"text":"assistant should start being replayed","id":"a1"},"createAt":"2026-03-27T00:00:01Z"}
            """.trimIndent(),
            createdAt = 2,
            updatedAt = 2
        )
        val toolEntry = AgentConversationEntry(
            id = 3,
            conversationId = 7,
            conversationMode = "normal",
            entryId = "t1",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = "抓取成功",
            payloadJson = """
                {
                  "toolName":"browser_use",
                  "displayName":"浏览器自动化",
                  "toolType":"builtin",
                  "argsJson":"{\"url\":\"https://example.com\",\"query\":\"latest\"}",
                  "summary":"抓取成功",
                  "resultPreviewJson":"{\"title\":\"Example\"}",
                  "rawResultJson":"{\"title\":\"Example\",\"html\":\"<html>super long raw payload</html>\"}",
                  "success":true
                }
            """.trimIndent(),
            createdAt = 3,
            updatedAt = 3
        )

        val secondToolEntry = AgentConversationEntry(
            id = 4,
            conversationId = 7,
            conversationMode = "normal",
            entryId = "t2",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT,
            status = AgentConversationHistoryRepository.STATUS_ERROR,
            summary = "执行命令失败",
            payloadJson = """
                {
                  "toolName":"terminal_execute",
                  "displayName":"执行命令",
                  "toolType":"terminal",
                  "argsJson":"{\"command\":\"pwd\"}",
                  "summary":"执行命令失败",
                  "resultPreviewJson":"{\"message\":\"permission denied\"}",
                  "rawResultJson":"{\"message\":\"permission denied\",\"trace\":\"super long raw payload terminal\"}",
                  "terminalOutput":"permission denied",
                  "success":false
                }
            """.trimIndent(),
            createdAt = 4,
            updatedAt = 4
        )

        val seed = AgentConversationHistorySupport.buildPromptSeedFromEntries(
            listOf(userEntry, assistantEntry, toolEntry, secondToolEntry)
        )

        assertEquals(6, seed.historyMessages.size)
        assertEquals(
            listOf("user", "assistant", "assistant", "tool", "assistant", "tool"),
            seed.historyMessages.map { it.role }
        )
        assertTrue(seed.historyMessages[0].content.toString().contains("查看 example"))
        assertEquals(1, seed.historyMessages[2].toolCalls?.size)
        assertEquals("browser_use", seed.historyMessages[2].toolCalls?.single()?.function?.name)
        assertTrue(
            seed.historyMessages[2].toolCalls
                ?.single()
                ?.function
                ?.arguments
                .orEmpty()
                .contains("\"url\":\"https://example.com\"")
        )
        assertEquals("terminal_execute", seed.historyMessages[4].toolCalls?.single()?.function?.name)

        val firstToolSummary = seed.historyMessages[3].content!!.jsonPrimitive.content
        assertTrue(firstToolSummary.contains("浏览器自动化"))
        assertTrue(firstToolSummary.contains("抓取成功"))
        assertTrue(firstToolSummary.contains("previewJson"))
        assertFalse(firstToolSummary.contains("rawResultJson"))

        val secondToolSummary = seed.historyMessages[5].content!!.jsonPrimitive.content
        assertTrue(secondToolSummary.contains("执行命令"))
        assertTrue(secondToolSummary.contains("执行命令失败"))
        assertTrue(secondToolSummary.contains("terminalOutput"))
        assertFalse(secondToolSummary.contains("rawResultJson"))

        val allReplayText = seed.historyMessages.joinToString("\n") {
            it.content?.toString().orEmpty()
        }
        assertTrue(allReplayText.contains("assistant should start being replayed"))
        assertFalse(allReplayText.contains("super long raw payload"))
        assertFalse(allReplayText.contains("super long raw payload terminal"))
    }

    @Test
    fun `canonical tool replay preserves original ids results and final assistant ordering`() {
        val firstCall = AssistantToolCall(
            id = "call_1",
            function = AssistantToolCallFunction(
                name = "memory_search",
                arguments = "{\"query\":\"cache\"}"
            )
        )
        val secondCall = AssistantToolCall(
            id = "call_2",
            function = AssistantToolCallFunction(
                name = "skills_read",
                arguments = "{\"skillId\":\"debugging\"}"
            )
        )
        val canonicalAssistant = ChatCompletionMessage(
            role = "assistant",
            toolCalls = listOf(firstCall, secondCall),
            reasoningContent = "先读取稳定上下文"
        )
        val firstResult = ChatCompletionMessage(
            role = "tool",
            toolCallId = firstCall.id,
            content = JsonPrimitive("{\"result\":\"memory exact\"}")
        )
        val secondResult = ChatCompletionMessage(
            role = "tool",
            toolCallId = secondCall.id,
            content = JsonPrimitive("{\"result\":\"skill exact\"}")
        )
        val assistantJson = canonicalJson.encodeToString(canonicalAssistant)

        fun toolEntry(
            id: Long,
            entryId: String,
            callId: String,
            result: ChatCompletionMessage
        ) = AgentConversationEntry(
            id = id,
            conversationId = 7,
            conversationMode = "normal",
            entryId = entryId,
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = "done",
            payloadJson = gson.toJson(
                mapOf(
                    "toolName" to result.toolCallId,
                    "modelToolCallId" to callId,
                    "modelAssistantMessageJson" to assistantJson,
                    "modelToolResultMessageJson" to canonicalJson.encodeToString(result)
                )
            ),
            createdAt = id,
            updatedAt = id
        )

        val entries = listOf(
            AgentConversationEntry(
                id = 1,
                conversationId = 7,
                conversationMode = "normal",
                entryId = "task-user",
                entryType = AgentConversationHistoryRepository.ENTRY_TYPE_USER_MESSAGE,
                status = AgentConversationHistoryRepository.STATUS_SUCCESS,
                summary = "continue",
                payloadJson = gson.toJson(
                    AgentConversationHistorySupport.buildTextMessagePayload(
                        messageId = "task-user",
                        user = 1,
                        text = "continue",
                        isError = false,
                        streamMeta = null,
                        createdAt = 1L
                    )
                ),
                createdAt = 1,
                updatedAt = 1
            ),
            AgentConversationEntry(
                id = 2,
                conversationId = 7,
                conversationMode = "normal",
                entryId = "task-assistant",
                entryType = AgentConversationHistoryRepository.ENTRY_TYPE_ASSISTANT_MESSAGE,
                status = AgentConversationHistoryRepository.STATUS_SUCCESS,
                summary = "final answer",
                payloadJson = gson.toJson(
                    AgentConversationHistorySupport.buildTextMessagePayload(
                        messageId = "task-assistant",
                        user = 2,
                        text = "final answer",
                        isError = false,
                        streamMeta = null,
                        createdAt = 2L
                    )
                ),
                createdAt = 2,
                updatedAt = 2
            ),
            toolEntry(3, "task-tool-1", firstCall.id, firstResult),
            toolEntry(4, "task-tool-2", secondCall.id, secondResult)
        )

        val replay = AgentConversationHistorySupport.buildPromptRelevantMessages(entries)

        assertEquals(
            listOf("user", "assistant", "tool", "tool", "assistant"),
            replay.map { it.role }
        )
        assertEquals(listOf("call_1", "call_2"), replay[1].toolCalls?.map { it.id })
        assertEquals("call_1", replay[2].toolCallId)
        assertEquals("call_2", replay[3].toolCallId)
        assertEquals("{\"result\":\"memory exact\"}", replay[2].content?.jsonPrimitive?.content)
        assertEquals("{\"result\":\"skill exact\"}", replay[3].content?.jsonPrimitive?.content)
        assertEquals("final answer", replay[4].content?.jsonPrimitive?.content)
    }

    @Test
    fun `buildPromptRelevantMessages preserves assistant reasoning content when present in payload`() {
        val payload = AgentConversationHistorySupport.buildTextMessagePayload(
            messageId = "a-reasoning",
            user = 2,
            text = "先调用工具",
            reasoningContent = "需要先定位文件",
            isError = false,
            streamMeta = null,
            createdAt = 1L
        )
        val entry = AgentConversationEntry(
            id = 10,
            conversationId = 7,
            conversationMode = "normal",
            entryId = "a-reasoning",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_ASSISTANT_MESSAGE,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = "先调用工具",
            payloadJson = gson.toJson(payload),
            createdAt = 1,
            updatedAt = 1
        )

        val messages = AgentConversationHistorySupport.buildPromptRelevantMessages(listOf(entry))

        assertEquals(1, messages.size)
        assertEquals("assistant", messages.single().role)
        assertEquals("需要先定位文件", messages.single().reasoningContent)
    }

    @Test
    fun `prepareEntryForStorage keeps assistant reasoning content`() {
        val payload = AgentConversationHistorySupport.buildTextMessagePayload(
            messageId = "a-storage",
            user = 2,
            text = "完成首轮",
            reasoningContent = "上一轮思考内容",
            isError = false,
            streamMeta = null,
            createdAt = 1L
        )
        val entry = AgentConversationEntry(
            id = 11,
            conversationId = 7,
            conversationMode = "normal",
            entryId = "a-storage",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_ASSISTANT_MESSAGE,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = "完成首轮",
            payloadJson = gson.toJson(payload),
            createdAt = 1,
            updatedAt = 1
        )

        val stored = AgentConversationHistorySupport.prepareEntryForStorage(entry)
        val storedPayload = AgentConversationHistorySupport.readMap(stored.payloadJson)

        assertEquals("上一轮思考内容", storedPayload["reasoning_content"])
    }

    @Test
    fun `prepareEntryForStorage keeps assistant turn usage`() {
        val payload = AgentConversationHistorySupport.buildTextMessagePayload(
            messageId = "a-usage",
            user = 2,
            text = "完成首轮",
            isError = false,
            streamMeta = null,
            turnUsage = mapOf(
                "ctx" to 18_797,
                "in" to 18_797,
                "out" to 296,
                "cache" to 13_157
            ),
            createdAt = 1L
        )
        val entry = AgentConversationEntry(
            id = 12,
            conversationId = 7,
            conversationMode = "normal",
            entryId = "a-usage",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_ASSISTANT_MESSAGE,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = "完成首轮",
            payloadJson = gson.toJson(payload),
            createdAt = 1,
            updatedAt = 1
        )

        val stored = AgentConversationHistorySupport.prepareEntryForStorage(entry)
        val storedPayload = AgentConversationHistorySupport.readMap(stored.payloadJson)
        val storedUsage = storedPayload["turnUsage"] as Map<*, *>

        assertEquals(18_797L, (storedUsage["ctx"] as Number).toLong())
        assertEquals(18_797L, (storedUsage["in"] as Number).toLong())
        assertEquals(296L, (storedUsage["out"] as Number).toLong())
        assertEquals(13_157L, (storedUsage["cache"] as Number).toLong())
    }

    @Test
    fun `buildPromptRelevantMessages replays tool turn reasoning content from tool payload`() {
        val entry = AgentConversationEntry(
            id = 12,
            conversationId = 7,
            conversationMode = "normal",
            entryId = "task-1-tool-1",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = "抓取成功",
            payloadJson = gson.toJson(
                mapOf(
                    "toolName" to "browser_use",
                    "displayName" to "浏览器自动化",
                    "toolType" to "builtin",
                    "argsJson" to """{"url":"https://example.com"}""",
                    "reasoning_content" to "需要先打开页面确认结构",
                    "summary" to "抓取成功",
                    "success" to true
                )
            ),
            createdAt = 1,
            updatedAt = 1
        )

        val messages = AgentConversationHistorySupport.buildPromptRelevantMessages(listOf(entry))

        assertEquals(2, messages.size)
        assertEquals("assistant", messages[0].role)
        assertEquals("需要先打开页面确认结构", messages[0].reasoningContent)
        assertEquals("browser_use", messages[0].toolCalls?.single()?.function?.name)
    }

    @Test
    fun `restoreToolPayloadFromUiMessage keeps agent tool cards restorable as tool events`() {
        val message = mapOf<String, Any?>(
            "id" to "task-1-tool-1",
            "type" to 2,
            "user" to 3,
            "content" to mapOf(
                "id" to "task-1-tool-1",
                "agentId" to "claude-code-acp",
                "agentName" to "Claude Code",
                "cardData" to mapOf(
                    "type" to "agent_tool_summary",
                    "uiStyle" to "agent_tool",
                    "agentId" to "claude-code-acp",
                    "agentName" to "Claude Code",
                    "taskId" to "task-1",
                    "cardId" to "task-1-tool-1",
                    "toolName" to "codex.browser_use",
                    "displayName" to "浏览器自动化",
                    "toolType" to "builtin",
                    "status" to "success",
                    "summary" to "抓取成功",
                    "argsJson" to """{"url":"https://example.com"}""",
                    "resultPreviewJson" to """{"title":"Example"}""",
                    "rawResultJson" to """{"title":"Example","html":"<html>raw</html>"}""",
                    "success" to true
                )
            )
        )

        val restored = AgentConversationHistorySupport.restoreToolPayloadFromUiMessage(message)

        assertEquals("agent.browser_use", restored?.get("toolName"))
        assertEquals("claude-code-acp", restored?.get("agentId"))
        assertEquals("Claude Code", restored?.get("agentName"))
        assertEquals("agent_tool", restored?.get("uiStyle"))
        assertEquals("success", restored?.get("status"))
        assertEquals("抓取成功", restored?.get("summary"))
        assertEquals(
            """{"url":"https://example.com"}""",
            restored?.get("argsJson")
        )
        assertEquals(
            """{"title":"Example","html":"<html>raw</html>"}""",
            restored?.get("rawResultJson")
        )
    }

    @Test
    fun `buildDisplaySafeToolCardData compacts oversized historical tool payloads`() {
        val longScript = "print('hello')\n".repeat(900)
        val longRaw = "raw-result".repeat(900)
        val longTerminal = (1..2000).joinToString("\n") { "line-$it" }
        val entry = AgentConversationEntry(
            id = 7,
            conversationId = 1,
            conversationMode = "normal",
            entryId = "task-1-tool-1",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = "脚本执行完成",
            payloadJson = "",
            createdAt = 1,
            updatedAt = 1
        )
        val payload = mapOf<String, Any?>(
            "taskId" to "task-1",
            "agentId" to "claude-code-acp",
            "agentName" to "Claude Code",
            "cardId" to "task-1-tool-1",
            "toolName" to "codex.terminal_execute",
            "displayName" to "执行命令",
            "toolType" to "terminal",
            "argsJson" to gson.toJson(mapOf("command" to longScript)),
            "resultPreviewJson" to gson.toJson(mapOf("message" to "done")),
            "rawResultJson" to gson.toJson(mapOf("stdout" to longRaw)),
            "terminalOutput" to longTerminal,
            "terminalOutputDelta" to "latest delta",
            "artifacts" to (1..20).map { index ->
                mapOf("path" to "/tmp/file-$index.txt", "content" to "x".repeat(2000))
            },
            "success" to true
        )

        val cardData = AgentConversationHistorySupport.buildDisplaySafeToolCardData(
            entry = entry,
            payload = payload
        )

        assertEquals("agent_tool_summary", cardData["type"])
        assertEquals("agent_tool", cardData["uiStyle"])
        assertEquals("claude-code-acp", cardData["agentId"])
        assertEquals("Claude Code", cardData["agentName"])
        assertEquals("agent.terminal_execute", cardData["toolName"])
        assertEquals(true, cardData["isHistorical"])
        assertEquals("compact", cardData["historyRenderMode"])
        assertEquals("", cardData["terminalOutputDelta"])
        assertEquals(true, cardData["payloadCompacted"])
        assertTrue((cardData["argsJson"] as String).length < longScript.length)
        assertTrue((cardData["rawResultJson"] as String).length < longRaw.length)
        assertTrue((cardData["terminalOutput"] as String).startsWith("[Earlier content omitted]"))
        assertTrue((cardData["terminalOutput"] as String).contains("line-2000"))
        assertEquals(8, (cardData["artifacts"] as List<*>).size)
    }

    @Test
    fun `buildDisplaySafeUiCardMessage compacts historical deep thinking cards`() {
        val longThinking = "思考过程 ".repeat(6000)
        val payload = mapOf<String, Any?>(
            "id" to "task-1-thinking",
            "type" to 2,
            "user" to 3,
            "content" to mapOf(
                "id" to "task-1-thinking",
                "cardData" to mapOf(
                    "type" to "deep_thinking",
                    "taskID" to "task-1",
                    "cardId" to "task-1-thinking",
                    "thinkingContent" to longThinking,
                    "startTime" to 1000,
                    "stage" to 1,
                    "isLoading" to true,
                    "streamMeta" to mapOf("nested" to "metadata".repeat(2000))
                )
            ),
            "streamMeta" to mapOf(
                "seq" to 1,
                "roundIndex" to 1,
                "kind" to "thinking",
                "parentTaskId" to "task-1",
                "entryId" to "task-1-thinking",
                "raw" to mapOf("large" to "metadata".repeat(2000))
            )
        )
        val entry = AgentConversationEntry(
            id = 9,
            conversationId = 1,
            conversationMode = "normal",
            entryId = "task-1-thinking",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_UI_CARD,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = "",
            payloadJson = gson.toJson(payload),
            createdAt = 1000,
            updatedAt = 2000
        )

        val message = AgentConversationHistorySupport.buildDisplaySafeUiCardMessage(
            entry = entry,
            payload = payload
        )
        val content = message["content"] as Map<*, *>
        val cardData = content["cardData"] as Map<*, *>
        val streamMeta = message["streamMeta"] as Map<*, *>

        assertEquals("deep_thinking", cardData["type"])
        assertEquals(false, cardData["isLoading"])
        assertEquals(4, cardData["stage"])
        assertEquals(true, cardData["thinkingContentTruncated"])
        assertTrue((cardData["thinkingContent"] as String).length <= 8 * 1024)
        assertEquals("task-1", streamMeta["parentTaskId"])
        assertFalse(streamMeta.containsKey("raw"))
        assertFalse(cardData.containsKey("streamMeta"))
    }

    @Test
    fun `preserveDeepThinkingContent keeps prior text when final snapshot is empty`() {
        val existing = AgentConversationHistorySupport.buildCardMessagePayload(
            messageId = "task-thinking",
            cardData = mapOf(
                "type" to "deep_thinking",
                "thinkingContent" to "已经收到的思考内容",
                "thinkingOriginalLength" to 10,
                "thinkingContentTruncated" to false,
                "thinkingTruncateMode" to "none",
                "stage" to 1,
                "isLoading" to true
            ),
            isError = false,
            streamMeta = null,
            createdAt = 1000
        )
        val incoming = AgentConversationHistorySupport.buildCardMessagePayload(
            messageId = "task-thinking",
            cardData = mapOf(
                "type" to "deep_thinking",
                "thinkingContent" to "",
                "thinkingOriginalLength" to 0,
                "thinkingContentTruncated" to false,
                "thinkingTruncateMode" to "none",
                "stage" to 4,
                "isLoading" to false,
                "endTime" to 2000
            ),
            isError = false,
            streamMeta = null,
            createdAt = 1000
        )

        val merged = AgentConversationHistorySupport.preserveDeepThinkingContent(
            existingPayload = existing,
            incomingPayload = incoming
        )
        val content = merged["content"] as Map<*, *>
        val cardData = content["cardData"] as Map<*, *>

        assertEquals("已经收到的思考内容", cardData["thinkingContent"])
        assertEquals(10, cardData["thinkingOriginalLength"])
        assertEquals(4, cardData["stage"])
        assertEquals(false, cardData["isLoading"])
        assertEquals(2000, cardData["endTime"])
    }

    @Test
    fun `normalizeInterruptedEntries converts running tools to interrupted`() {
        val runningEntry = AgentConversationEntry(
            id = 1,
            conversationId = 9,
            conversationMode = "subagent",
            entryId = "tool-running",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT,
            status = AgentConversationHistoryRepository.STATUS_RUNNING,
            summary = "",
            payloadJson = """
                {"toolName":"terminal_run","displayName":"执行命令","toolType":"terminal","status":"running","summary":"","terminalOutput":"hello"}
            """.trimIndent(),
            createdAt = 1,
            updatedAt = 1
        )

        val normalized = AgentConversationHistorySupport.normalizeInterruptedEntries(
            listOf(runningEntry)
        )

        assertEquals(1, normalized.size)
        assertEquals(
            AgentConversationHistoryRepository.STATUS_INTERRUPTED,
            normalized.single().status
        )
        assertTrue(normalized.single().summary.isNotBlank())
        assertTrue(normalized.single().payloadJson.contains("\"status\":\"interrupted\""))
    }

    @Test
    fun `normalizeInterruptedEntries finalizes lone thinking card during restore`() {
        val thinkingEntry = AgentConversationEntry(
            id = 1,
            conversationId = 9,
            conversationMode = "normal",
            entryId = "task-1-thinking",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_UI_CARD,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = "",
            payloadJson = """
                {
                  "id":"task-1-thinking",
                  "type":2,
                  "user":3,
                  "content":{
                    "id":"task-1-thinking",
                    "cardData":{
                      "type":"deep_thinking",
                      "taskID":"task-1",
                      "thinkingContent":"正在分析",
                      "startTime":1000,
                      "endTime":null,
                      "stage":1,
                      "isLoading":true
                    }
                  },
                  "isLoading":false,
                  "isFirst":false,
                  "isError":false,
                  "isSummarizing":false,
                  "createAt":"2026-03-27T00:00:01Z"
                }
            """.trimIndent(),
            createdAt = 1000,
            updatedAt = 1500
        )

        val normalized = AgentConversationHistorySupport.normalizeInterruptedEntries(
            entries = listOf(thinkingEntry),
            finalizeLatestThinkingEntries = true
        )

        assertEquals(1, normalized.size)
        assertTrue(normalized.single().payloadJson.contains("\"stage\":4"))
        assertTrue(normalized.single().payloadJson.contains("\"isLoading\":false"))
        assertTrue(normalized.single().payloadJson.contains("\"endTime\":1500"))
    }

    @Test
    fun `buildPromptSeedFromEntries prepends context summary and skips entries before cutoff`() {
        val entries = listOf(
            buildUserEntry(id = 1, entryId = "u1", text = "旧问题"),
            buildAssistantEntry(id = 2, entryId = "a1", text = "旧回答"),
            buildUserEntry(id = 3, entryId = "u2", text = "新问题"),
            buildAssistantEntry(id = 4, entryId = "a2", text = "新回答")
        )

        val seed = AgentConversationHistorySupport.buildPromptSeedFromEntries(
            entries = entries,
            contextSummary = """
                【用户目标与约束】
                - 保留旧需求
            """.trimIndent(),
            cutoffEntryDbId = 2
        )

        assertEquals(3, seed.historyMessages.size)
        assertEquals("user", seed.historyMessages.first().role)
        assertTrue(
            seed.historyMessages.first().content!!.jsonPrimitive.content.startsWith(
                "<context-summary> The following is a summary of the earlier conversation that was compacted to save context space."
            )
        )
        assertTrue(seed.historyMessages.first().content!!.jsonPrimitive.content.contains("保留旧需求"))
        assertEquals("user", seed.historyMessages[1].role)
        assertEquals("新问题", seed.historyMessages[1].content!!.jsonPrimitive.content)
        assertEquals("assistant", seed.historyMessages[2].role)
        assertEquals("新回答", seed.historyMessages[2].content!!.jsonPrimitive.content)
    }

    @Test
    fun `buildPromptSeedFromEntries skips interruptedTurn assistant entries`() {
        // 失败那一轮被 onError 标了 interruptedTurn=true。续跑时 LLM 应当看不见
        // 这一段(无论里面是错误文案还是断流前的半截输出),只看见之前的工具结果。
        val entries = listOf(
            buildUserEntry(id = 1, entryId = "u1", text = "做点事"),
            buildToolEntry(
                id = 2,
                entryId = "t1",
                toolName = "browser_use",
                summary = "页面抓取成功"
            ),
            buildInterruptedAssistantEntry(
                id = 3,
                entryId = "a-failed",
                text = "Failed to connect to api.example.com"
            )
        )

        val seed = AgentConversationHistorySupport.buildPromptSeedFromEntries(entries)

        // 期望: user + tool 的 assistant 包装 + tool result, 共 3 条; 失败的 assistant 被跳过。
        assertEquals(3, seed.historyMessages.size)
        assertEquals(
            listOf("user", "assistant", "tool"),
            seed.historyMessages.map { it.role }
        )
        // 跳过的 assistant 内容不应该出现在任何一条消息里。
        assertTrue(
            seed.historyMessages.none { message ->
                (message.content?.toString().orEmpty()).contains("Failed to connect")
            }
        )
        // 工具调用仍然完整保留。
        assertEquals(1, seed.historyMessages[1].toolCalls?.size)
        assertEquals(
            "browser_use",
            seed.historyMessages[1].toolCalls?.single()?.function?.name
        )
    }

    @Test
    fun `buildPromptSeedFromEntries replays the latest normal assistant after an interrupted one`() {
        // 用户点 Continue 成功跑完后,失败 entry 被同 entryId 覆盖回 interruptedTurn=false;
        // 但万一 DB 里历史地遗留了一条"interruptedTurn=true 后又跟着一条正常 assistant",
        // 过滤后只回放正常那条。
        val entries = listOf(
            buildUserEntry(id = 1, entryId = "u1", text = "ask"),
            buildInterruptedAssistantEntry(id = 2, entryId = "a-bad", text = "Network error"),
            buildAssistantEntry(id = 3, entryId = "a-good", text = "Here is the answer")
        )

        val seed = AgentConversationHistorySupport.buildPromptSeedFromEntries(entries)

        assertEquals(2, seed.historyMessages.size)
        assertEquals(listOf("user", "assistant"), seed.historyMessages.map { it.role })
        assertEquals(
            "Here is the answer",
            seed.historyMessages[1].content!!.jsonPrimitive.content
        )
    }

    @Test
    fun `buildPromptSeedFromEntries keeps all entries after cutoff without takeLast truncation`() {
        val entries = (1L..25L).map { index ->
            buildUserEntry(
                id = index,
                entryId = "u$index",
                text = "message-$index"
            )
        }

        val seed = AgentConversationHistorySupport.buildPromptSeedFromEntries(entries)

        assertEquals(25, seed.historyMessages.size)
        assertEquals("message-1", seed.historyMessages.first().content!!.jsonPrimitive.content)
        assertEquals("message-25", seed.historyMessages.last().content!!.jsonPrimitive.content)
    }

    @Test
    fun `buildPromptSeedFromEntries rebuilds image blocks from local path when available`() {
        AgentImageAttachmentSupport.backend = object : AgentImageAttachmentSupport.Backend {
            override fun readFileAsDataUrl(file: File, mimeTypeHint: String?): String {
                return "data:image/png;base64,LOCAL_FILE"
            }

            override fun compressDataUrl(
                dataUrl: String,
                scale: Float,
                quality: Int
            ): AgentImageAttachmentSupport.ResolvedImageData {
                return AgentImageAttachmentSupport.ResolvedImageData(
                    dataUrl = "data:image/jpeg;base64,MODEL_FROM_PATH",
                    mimeType = "image/jpeg",
                    originalWidth = 1200,
                    originalHeight = 800,
                    compressedWidth = 900,
                    compressedHeight = 600
                )
            }
        }
        try {
            val entry = buildUserEntry(
                id = 1,
                entryId = "u-image",
                text = "看一下这张图",
                attachments = listOf(
                    mapOf(
                        "path" to "/tmp/photo.png",
                        "dataUrl" to "data:image/jpeg;base64,STORED_PREVIEW",
                        "mimeType" to "image/png",
                        "isImage" to true
                    )
                )
            )

            val seed = AgentConversationHistorySupport.buildPromptSeedFromEntries(listOf(entry))
            val content = seed.historyMessages.single().content as JsonArray
            val imageBlock = content[1].jsonObject

            assertEquals("image_url", imageBlock["type"]?.jsonPrimitive?.content)
            assertEquals(
                "data:image/jpeg;base64,MODEL_FROM_PATH",
                imageBlock["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content
            )
        } finally {
            AgentImageAttachmentSupport.resetBackendForTests()
        }
    }

    @Test
    fun `buildPromptSeedFromEntries keeps non-image attachments as workspace path hints`() {
        val entry = buildUserEntry(
            id = 1,
            entryId = "u-doc",
            text = "你看看这个",
            attachments = listOf(
                mapOf(
                    "path" to "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/shared/doc.md",
                    "name" to "doc.md",
                    "mimeType" to "text/markdown",
                    "isImage" to false,
                    "promptPath" to "/workspace/shared/doc.md",
                    "sendToModel" to false
                )
            )
        )

        val seed = AgentConversationHistorySupport.buildPromptSeedFromEntries(listOf(entry))
        val content = seed.historyMessages.single().content!!.jsonPrimitive.content

        assertTrue(content.contains("doc.md"))
        assertTrue(content.contains("/workspace/shared/doc.md"))
    }

    @Test
    fun `selectEntriesToCompact includes historical tool context before latest user`() {
        val entries = listOf(
            buildUserEntry(id = 1, entryId = "u1", text = "第一轮问题"),
            buildAssistantEntry(id = 2, entryId = "a1", text = "第一轮回答"),
            buildToolEntry(id = 3, entryId = "t1", toolName = "browser_use", summary = "第一轮工具"),
            buildUserEntry(id = 4, entryId = "u2", text = "第二轮问题")
        )

        val selection = AgentConversationHistorySupport.selectEntriesToCompact(entries)

        assertEquals(listOf(1L, 2L, 3L), selection?.entriesToCompact?.map { it.id })
        assertEquals(3L, selection?.cutoffEntryDbId)
    }

    @Test
    fun `selectEntriesToCompact respects existing cutoff and skips when no complete previous round`() {
        val entries = listOf(
            buildUserEntry(id = 1, entryId = "u1", text = "第一轮问题"),
            buildAssistantEntry(id = 2, entryId = "a1", text = "第一轮回答"),
            buildUserEntry(id = 3, entryId = "u2", text = "第二轮问题")
        )

        val selectionAfterCutoff = AgentConversationHistorySupport.selectEntriesToCompact(
            entries = entries,
            cutoffEntryDbId = 2
        )
        val selectionWithoutOlderRound = AgentConversationHistorySupport.selectEntriesToCompact(
            entries = listOf(
                buildUserEntry(id = 11, entryId = "u11", text = "只有当前轮")
            )
        )

        assertNull(selectionAfterCutoff)
        assertNull(selectionWithoutOlderRound)
    }

    @Test
    fun `buildPromptRelevantMessages replays tool history before same-task assistant content`() {
        val entries = listOf(
            buildUserEntry(id = 1, entryId = "task-1-user", text = "请检查页面"),
            buildAssistantEntry(
                id = 2,
                entryId = "task-1-assistant",
                text = "页面标题是 Example"
            ),
            buildToolEntry(
                id = 3,
                entryId = "task-1-tool-1",
                toolName = "browser_use",
                summary = "抓取成功"
            ),
            buildUserEntry(id = 4, entryId = "task-2-user", text = "继续下一步")
        )

        val messages = AgentConversationHistorySupport.buildPromptRelevantMessages(entries)

        assertEquals(
            listOf("user", "assistant", "tool", "assistant", "user"),
            messages.map { it.role }
        )
        assertEquals("browser_use", messages[1].toolCalls?.single()?.function?.name)
        assertTrue(messages[2].content!!.jsonPrimitive.content.contains("\"summary\":\"抓取成功\""))
        assertFalse(messages[2].content!!.jsonPrimitive.content.contains("rawResultJson"))
        assertEquals("页面标题是 Example", messages[3].content!!.jsonPrimitive.content)
        assertEquals("继续下一步", messages[4].content!!.jsonPrimitive.content)
    }

    @Test
    fun `buildPromptRelevantMessages truncates oversized tool replay fields`() {
        val longSummary = "s".repeat(400)
        val longTerminal = "t".repeat(1500)
        val entry = AgentConversationEntry(
            id = 1,
            conversationId = 7,
            conversationMode = "normal",
            entryId = "task-1-tool-1",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = longSummary,
            payloadJson = """
                {
                  "toolName":"terminal_execute",
                  "displayName":"执行命令",
                  "toolType":"terminal",
                  "summary":"$longSummary",
                  "terminalOutput":"$longTerminal",
                  "resultPreviewJson":"{\"message\":\"ok\"}",
                  "rawResultJson":"{\"message\":\"raw\"}",
                  "success":true
                }
            """.trimIndent(),
            createdAt = 1,
            updatedAt = 1
        )

        val messages = AgentConversationHistorySupport.buildPromptRelevantMessages(listOf(entry))
        val toolSummary = messages[1].content!!.jsonPrimitive.content

        assertTrue(toolSummary.contains("\"summary\":\"${"s".repeat(240)}...\""))
        assertTrue(toolSummary.contains("\"terminalOutput\":\"${"t".repeat(1200)}...\""))
        assertFalse(toolSummary.contains("rawResultJson"))
    }

    @Test
    fun `prepareEntryForStorage compacts oversized tool payload before persistence`() {
        val longRaw = "raw".repeat(12_000)
        val longTerminal = (1..5000).joinToString("\n") { "line-$it" }
        val entry = AgentConversationEntry(
            id = 1,
            conversationId = 8,
            conversationMode = "normal",
            entryId = "task-8-tool-1",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = "终端执行完成",
            payloadJson = gson.toJson(
                mapOf(
                    "agentId" to "claude-code-acp",
                    "agentName" to "Claude Code",
                    "toolName" to "codex.terminal_execute",
                    "displayName" to "执行命令",
                    "toolType" to "terminal",
                    "summary" to "终端执行完成",
                    "argsJson" to gson.toJson(mapOf("command" to "pwd")),
                    "rawResultJson" to gson.toJson(mapOf("stdout" to longRaw)),
                    "terminalOutput" to longTerminal,
                    "success" to true
                )
            ),
            createdAt = 1,
            updatedAt = 1
        )

        val stored = AgentConversationHistorySupport.prepareEntryForStorage(entry)
        val payload = AgentConversationHistorySupport.readMap(stored.payloadJson)

        assertTrue(stored.payloadJson.length <= AgentConversationHistorySupport.MAX_STORAGE_ENTRY_PAYLOAD_CHARS)
        assertEquals(true, payload["payloadCompacted"])
        assertEquals("agent_tool", payload["uiStyle"])
        assertEquals("claude-code-acp", payload["agentId"])
        assertEquals("Claude Code", payload["agentName"])
        assertEquals("agent.terminal_execute", payload["toolName"])
        assertTrue(payload["rawResultJson"].toString().length < longRaw.length)
        assertTrue(payload["terminalOutput"].toString().contains("line-5000"))
    }

    @Test
    fun `materializeRecord repairs truncated oversized tool rows into compact non replay entry`() {
        val record = AgentConversationEntryRecord(
            id = 12,
            conversationId = 9,
            conversationMode = "normal",
            entryId = "task-9-tool-1",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT,
            status = AgentConversationHistoryRepository.STATUS_ERROR,
            summary = "工具执行失败，历史负载过大",
            payloadJson = "",
            createdAt = 10,
            updatedAt = 11,
            payloadOriginalLength = 9_900_000,
            payloadTruncated = true,
            summaryOriginalLength = 12,
            summaryTruncated = false
        )

        val materialized = AgentConversationHistorySupport.materializeRecord(record)
        val payload = AgentConversationHistorySupport.readMap(materialized.entry.payloadJson)
        val replayMessages = AgentConversationHistorySupport.buildPromptRelevantMessages(
            listOf(materialized.entry)
        )

        assertTrue(materialized.needsRepair)
        assertEquals(true, payload["historyOmitted"])
        assertEquals("工具调用历史", payload["displayName"])
        assertEquals(
            "工具执行失败，历史负载过大",
            payload["summary"]
        )
        assertTrue(replayMessages.isEmpty())
    }

    @Test
    fun `materializeRecord repairs truncated oversized ui card rows into omitted card payload`() {
        val record = AgentConversationEntryRecord(
            id = 13,
            conversationId = 9,
            conversationMode = "normal",
            entryId = "task-9-thinking",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_UI_CARD,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = "深度思考过程已折叠",
            payloadJson = "",
            createdAt = 20,
            updatedAt = 21,
            payloadOriginalLength = 1_500_000,
            payloadTruncated = true,
            summaryOriginalLength = 9,
            summaryTruncated = false
        )

        val materialized = AgentConversationHistorySupport.materializeRecord(record)
        val payload = AgentConversationHistorySupport.readMap(materialized.entry.payloadJson)
        val content = (payload["content"] as Map<*, *>)
        val cardData = (content["cardData"] as Map<*, *>)

        assertTrue(materialized.needsRepair)
        assertEquals("history_omitted_card", cardData["type"])
        assertEquals("深度思考过程已折叠", cardData["summary"])
        assertEquals(1_500_000, (cardData["originalPayloadLength"] as Number).toInt())
    }

    @Test
    fun `prepareEntryForStorage keeps deep thinking runtime state while compacting oversized card`() {
        val longThinking = "分析中 ".repeat(10000)
        val payload = mapOf(
            "id" to "task-11-thinking",
            "type" to 2,
            "user" to 3,
            "content" to mapOf(
                "id" to "task-11-thinking",
                "cardData" to mapOf(
                    "type" to "deep_thinking",
                    "taskID" to "task-11",
                    "cardId" to "task-11-thinking",
                    "thinkingContent" to longThinking,
                    "thinkingContentTruncated" to false,
                    "stage" to 2,
                    "isLoading" to true,
                    "startTime" to 1000
                )
            ),
            "isLoading" to false,
            "isFirst" to false,
            "isError" to false,
            "isSummarizing" to false
        )
        val entry = AgentConversationEntry(
            id = 14,
            conversationId = 10,
            conversationMode = "normal",
            entryId = "task-11-thinking",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_UI_CARD,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = "思考中",
            payloadJson = gson.toJson(payload),
            createdAt = 1000,
            updatedAt = 1200
        )

        val stored = AgentConversationHistorySupport.prepareEntryForStorage(entry)
        val storedPayload = AgentConversationHistorySupport.readMap(stored.payloadJson)
        val content = storedPayload["content"] as Map<*, *>
        val cardData = content["cardData"] as Map<*, *>

        assertTrue(stored.payloadJson.length <= AgentConversationHistorySupport.MAX_STORAGE_ENTRY_PAYLOAD_CHARS)
        assertEquals("deep_thinking", cardData["type"])
        assertEquals(2, (cardData["stage"] as Number).toInt())
        assertEquals(true, cardData["isLoading"])
        assertEquals(true, cardData["thinkingContentTruncated"])
        assertTrue((cardData["thinkingContent"] as String).contains("分析中"))
    }

    @Test
    fun `buildRuntimeCompactionWindow uses current summary and compacts all historical context before latest user`() {
        val messages = listOf(
            ChatCompletionMessage(
                role = "system",
                content = JsonPrimitive("main system")
            ),
            AgentConversationHistorySupport.buildContextSummaryUserMessage(
                "【用户目标与约束】\n旧总结"
            ),
            ChatCompletionMessage(
                role = "user",
                content = JsonPrimitive("旧问题")
            ),
            ChatCompletionMessage(
                role = "assistant",
                content = JsonPrimitive("旧回答")
            ),
            ChatCompletionMessage(
                role = "user",
                content = JsonPrimitive("当前问题")
            ),
            ChatCompletionMessage(
                role = "assistant",
                content = JsonPrimitive("当前轮中间输出")
            )
        )

        val window = AgentConversationHistorySupport.buildRuntimeCompactionWindow(messages)

        assertEquals("【用户目标与约束】\n旧总结", window?.existingSummary)
        assertEquals(listOf("user", "assistant"), window?.messagesToCompact?.map { it.role })
        assertEquals("旧问题", window?.messagesToCompact?.first()?.content?.jsonPrimitive?.content)
        assertEquals("旧回答", window?.messagesToCompact?.get(1)?.content?.jsonPrimitive?.content)
    }

    @Test
    fun `buildRuntimeCompactionWindow keeps historical tool replay inside compaction window`() {
        val historicalToolCalls = listOf(
            AssistantToolCall(
                id = "tool-call-old",
                function = AssistantToolCallFunction(
                    name = "browser_use",
                    arguments = """{"url":"https://example.com/old"}"""
                )
            )
        )
        val messages = listOf(
            ChatCompletionMessage(
                role = "system",
                content = JsonPrimitive("main system")
            ),
            AgentConversationHistorySupport.buildContextSummaryUserMessage(
                "【用户目标与约束】\n旧总结"
            ),
            ChatCompletionMessage(
                role = "user",
                content = JsonPrimitive("更早的问题")
            ),
            ChatCompletionMessage(
                role = "assistant",
                content = JsonPrimitive("更早的回答")
            ),
            ChatCompletionMessage(
                role = "assistant",
                toolCalls = historicalToolCalls
            ),
            ChatCompletionMessage(
                role = "tool",
                toolCallId = "tool-call-old",
                content = JsonPrimitive("""{"summary":"旧工具结果"}""")
            ),
            ChatCompletionMessage(
                role = "user",
                content = JsonPrimitive("当前问题")
            )
        )

        val window = AgentConversationHistorySupport.buildRuntimeCompactionWindow(messages)

        assertEquals(
            listOf("user", "assistant", "assistant", "tool"),
            window?.messagesToCompact?.map { it.role }
        )
        assertEquals("更早的问题", window?.messagesToCompact?.first()?.content?.jsonPrimitive?.content)
        assertEquals("更早的回答", window?.messagesToCompact?.get(1)?.content?.jsonPrimitive?.content)
        assertEquals("browser_use", window?.messagesToCompact?.get(2)?.toolCalls?.single()?.function?.name)
        assertEquals("""{"summary":"旧工具结果"}""", window?.messagesToCompact?.get(3)?.content?.jsonPrimitive?.content)
    }

    @Test
    fun `rebuildMessagesWithCompactedSummary keeps summary plus current turn context`() {
        val historicalToolCalls = listOf(
            AssistantToolCall(
                id = "tool-call-old",
                function = AssistantToolCallFunction(
                    name = "browser_use",
                    arguments = """{"url":"https://example.com/old"}"""
                )
            )
        )
        val pendingToolCalls = listOf(
            AssistantToolCall(
                id = "tool-call-1",
                function = AssistantToolCallFunction(
                    name = "browser_use",
                    arguments = """{"url":"https://example.com"}"""
                )
            )
        )
        val messages = listOf(
            ChatCompletionMessage(
                role = "system",
                content = JsonPrimitive("main system")
            ),
            AgentConversationHistorySupport.buildContextSummaryUserMessage(
                "【用户目标与约束】\n旧总结"
            ),
            ChatCompletionMessage(
                role = "user",
                content = JsonPrimitive("旧问题")
            ),
            ChatCompletionMessage(
                role = "assistant",
                content = JsonPrimitive("旧回答")
            ),
            ChatCompletionMessage(
                role = "assistant",
                toolCalls = historicalToolCalls
            ),
            ChatCompletionMessage(
                role = "tool",
                toolCallId = "tool-call-old",
                content = JsonPrimitive("""{"summary":"旧工具结果"}""")
            ),
            ChatCompletionMessage(
                role = "assistant",
                content = JsonPrimitive("旧工具后的解释")
            ),
            ChatCompletionMessage(
                role = "user",
                content = JsonPrimitive("当前问题")
            ),
            ChatCompletionMessage(
                role = "assistant",
                content = JsonPrimitive("不应保留的中间文本"),
                toolCalls = pendingToolCalls
            )
        )

        val rebuilt = AgentConversationHistorySupport.rebuildMessagesWithCompactedSummary(
            messages = messages,
            summary = "【用户目标与约束】\n新总结"
        )

        assertEquals(
            listOf("system", "user", "user", "assistant"),
            rebuilt.map { it.role }
        )
        assertEquals("main system", rebuilt[0].content!!.jsonPrimitive.content)
        assertTrue(rebuilt[1].content!!.jsonPrimitive.content.contains("新总结"))
        assertTrue(
            rebuilt[1].content!!.jsonPrimitive.content.startsWith(
                "<context-summary> The following is a summary of the earlier conversation that was compacted to save context space."
            )
        )
        assertEquals("当前问题", rebuilt[2].content!!.jsonPrimitive.content)
        assertEquals("不应保留的中间文本", rebuilt[3].content!!.jsonPrimitive.content)
        assertEquals("browser_use", rebuilt[3].toolCalls?.single()?.function?.name)
    }

    private fun buildUserEntry(
        id: Long,
        entryId: String,
        text: String,
        attachments: List<Map<String, Any?>> = emptyList()
    ): AgentConversationEntry {
        val attachmentsJson = if (attachments.isEmpty()) {
            ""
        } else {
            ""","attachments":${gson.toJson(attachments)}"""
        }
        return AgentConversationEntry(
            id = id,
            conversationId = 1,
            conversationMode = "normal",
            entryId = entryId,
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_USER_MESSAGE,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = text,
            payloadJson = """
                {"id":"$entryId","type":1,"user":1,"content":{"text":"$text","id":"$entryId"$attachmentsJson},"createAt":"2026-03-27T00:00:00Z"}
            """.trimIndent(),
            createdAt = id,
            updatedAt = id
        )
    }

    private fun buildAssistantEntry(id: Long, entryId: String, text: String): AgentConversationEntry {
        return AgentConversationEntry(
            id = id,
            conversationId = 1,
            conversationMode = "normal",
            entryId = entryId,
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_ASSISTANT_MESSAGE,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = text,
            payloadJson = """
                {"id":"$entryId","type":1,"user":2,"content":{"text":"$text","id":"$entryId"},"createAt":"2026-03-27T00:00:01Z"}
            """.trimIndent(),
            createdAt = id,
            updatedAt = id
        )
    }

    private fun buildInterruptedAssistantEntry(
        id: Long,
        entryId: String,
        text: String
    ): AgentConversationEntry {
        return AgentConversationEntry(
            id = id,
            conversationId = 1,
            conversationMode = "normal",
            entryId = entryId,
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_ASSISTANT_MESSAGE,
            status = AgentConversationHistoryRepository.STATUS_ERROR,
            summary = text,
            payloadJson = """
                {"id":"$entryId","type":1,"user":2,"content":{"text":"$text","id":"$entryId"},"isError":true,"interruptedTurn":true,"createAt":"2026-03-27T00:00:01Z"}
            """.trimIndent(),
            createdAt = id,
            updatedAt = id
        )
    }

    private fun buildToolEntry(
        id: Long,
        entryId: String,
        toolName: String,
        summary: String
    ): AgentConversationEntry {
        return AgentConversationEntry(
            id = id,
            conversationId = 1,
            conversationMode = "normal",
            entryId = entryId,
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = summary,
            payloadJson = """
                {
                  "toolName":"$toolName",
                  "displayName":"$toolName",
                  "toolType":"builtin",
                  "argsJson":"{}",
                  "summary":"$summary",
                  "success":true
                }
            """.trimIndent(),
            createdAt = id,
            updatedAt = id
        )
    }
}
