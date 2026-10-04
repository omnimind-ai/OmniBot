package cn.com.omnimind.nativeui.chat

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1:1 port of ui/test/features/home/pages/chat/utils/agent_run_timeline_test.dart.
 *
 * Dart's ChatMessageModel defaults `createAt` to `DateTime.now()`; the helpers
 * below do the same so timestamp-dependent fallbacks see the same input.
 */
class AgentRunTimelineTest {

    @Test
    fun `cancelled tool-only turn remains visible after reload`() {
        val tool = cardMessage(
            mapOf("type" to "agent_tool_summary", "toolType" to "workspace", "status" to "success"),
            id = "tool",
            streamMeta = mapOf("parentTaskId" to "cancel-only", "seq" to 1, "stopReason" to "cancelled"),
        )
        val restored = roundTrip(tool)
        assertEquals(
            AgentRunStatus.cancelled,
            buildAgentRunTimelineEntries(listOf(restored)).single().group!!.status,
        )
    }

    @Test
    fun `terminal failure is visible and survives serialization without treating tool errors as failed turns`() {
        val failure = cardMessage(
            mapOf("type" to "agent_tool_summary", "toolType" to "status", "status" to "error"),
            id = "failure",
            streamMeta = mapOf("parentTaskId" to "run", "kind" to "error", "seq" to 2),
        )
        val partial = chatMessage(
            id = "partial", type = 1, user = 2,
            content = mapOf("text" to "partial"),
            streamMeta = mapOf("parentTaskId" to "run", "seq" to 1),
        )
        for (message in listOf(failure, roundTrip(failure))) {
            val group = buildAgentRunTimelineEntries(listOf(message, partial)).single().group!!
            assertEquals(AgentRunStatus.failed, group.status)
            assertEquals(listOf("partial", "failure"), group.visibleMessagesOldestFirst.map { it.id })
        }
        val toolError = cardMessage(
            mapOf("type" to "agent_tool_summary", "toolType" to "workspace", "status" to "error"),
            id = "tool",
            streamMeta = mapOf("parentTaskId" to "run", "kind" to "tool_completed", "seq" to 2),
        )
        assertEquals(
            AgentRunStatus.finished,
            buildAgentRunTimelineEntries(listOf(toolError, partial)).single().group!!.status,
        )
    }

    @Test
    fun `groups by canonical runId before legacy parentTaskId`() {
        val message = chatMessage(
            id = "run-1-text",
            type = 1,
            user = 2,
            content = mapOf("text" to "答案", "id" to "run-1-text"),
            streamMeta = mapOf(
                "runId" to "run-1",
                "parentTaskId" to "official-turn-1",
                "entrySeq" to 1,
                "seq" to 1,
                "isFinal" to true,
            ),
        )

        val entries = buildAgentRunTimelineEntries(listOf(message))

        assertEquals("run-1", entries.single().group?.runId)
        assertEquals("run-1", entries.single().group?.taskId)
    }

    @Test
    fun `restores review header for legacy Xiaowan timestamp-ai reply`() {
        val timestamp = "1787481000000"
        val messages = listOf(
            chatMessage(
                id = "$timestamp-ai",
                type = 1,
                user = 2,
                content = mapOf(
                    "id" to "$timestamp-ai",
                    "text" to "旧小万最终回复",
                ),
            ),
            thinkingCard(
                id = "$timestamp-ai-thinking",
                taskId = "$timestamp-ai",
                seq = 1,
            ),
            userMessage("旧问题", id = "$timestamp-user"),
        )

        val entries = buildAgentRunTimelineEntries(messages)

        assertEquals(2, entries.size)
        assertEquals("$timestamp-ai", entries.first().group?.taskId)
        assertEquals(
            "旧小万最终回复",
            entries.first().group?.visibleMessagesNewestFirst?.single()?.text,
        )
        assertEquals(1, entries.first().group?.thinkingCount)
        assertEquals(true, entries.first().group?.hasProcessMessages)
        assertEquals("$timestamp-user", entries.last().message?.id)
    }

    @Test
    fun `groups completed agent run by parent task id`() {
        val entries = buildAgentRunTimelineEntries(buildCompletedRunMessages())

        assertEquals(2, entries.size)
        assertEquals("task-1", entries.first().group?.taskId)
        assertEquals(1, entries.first().group?.thinkingCount)
        assertEquals(1, entries.first().group?.toolCount)
        assertEquals("最终回答", entries.first().group?.visibleMessagesNewestFirst?.single()?.text)
    }

    @Test
    fun `keeps the ACP plan as a visible mutable card outside the fold`() {
        val plan = cardMessageWithMeta(
            id = "task-plan-agent-plan",
            taskId = "task-plan",
            kind = "tool_progress",
            seq = 2,
            cardData = mapOf(
                "type" to "agent_tool_summary",
                "toolType" to "plan",
                "toolName" to "plan",
                "toolTitle" to "Agent plan",
                "status" to "running",
                "planEntries" to listOf(
                    mapOf("content" to "Inspect workspace", "status" to "in_progress"),
                    mapOf("content" to "Suggest improvement", "status" to "pending"),
                ),
            ),
        )
        val answer = assistantMessage(
            id = "task-plan-answer",
            text = "完成",
            taskId = "task-plan",
            kind = "text_snapshot",
            seq = 3,
            isFinal = true,
        )

        val group = buildAgentRunTimelineEntries(
            listOf(
                answer,
                plan,
                userMessage("开始", id = "task-plan-user"),
            ),
        ).first().group!!

        assertEquals(
            listOf("task-plan-agent-plan", "task-plan-answer"),
            group.visibleMessagesOldestFirst.map { it.id },
        )
        assertTrue(group.processMessagesOldestFirst.isEmpty())
    }

    @Test
    fun `hides standalone artifact card between prompt and Agent response`() {
        val messages = listOf(
            cardMessage(
                mapOf(
                    "type" to "artifact_card",
                    "artifact" to mapOf("title" to "result.md"),
                    "taskId" to "task-1",
                    "runId" to "task-1",
                    "cardId" to "task-1-artifact-result",
                ),
                id = "task-1-artifact-result",
                streamMeta = mapOf(
                    "runId" to "task-1",
                    "parentTaskId" to "task-1",
                    "kind" to "artifact",
                ),
            ),
        ) + buildCompletedRunMessages()

        val entries = buildAgentRunTimelineEntries(messages)

        assertEquals(2, entries.size)
        assertFalse(entries.any { entry -> entry.key.contains("artifact") })
        assertEquals("task-1", entries.first().group?.taskId)
        assertEquals("user-1", entries.last().message?.id)
    }

    @Test
    fun `projects ACP user-input request to one Agent question bubble`() {
        val request = cardMessage(
            mapOf(
                "type" to "agent_request",
                "requestKind" to "user_input",
                "requestId" to "request-1",
                "title" to "需要你的确认",
                "detail" to "请告诉我下一步怎么做",
                "taskId" to "run-1",
                "runId" to "run-1",
            ),
            id = "request-1-card",
            streamMeta = mapOf(
                "runId" to "run-1",
                "kind" to "clarify_required",
            ),
        )

        val entries = buildAgentRunTimelineEntries(
            listOf(
                request,
                userMessage("开始", id = "user-1"),
            ),
        )

        val group = entries.first().group
        assertEquals("run-1", group?.taskId)
        assertEquals(1, group?.visibleMessagesOldestFirst?.size)
        assertEquals(2, group?.visibleMessagesOldestFirst?.single()?.type)
        assertEquals(
            true,
            group?.visibleMessagesOldestFirst?.single()?.cardData?.get("simplePresentation"),
        )
        assertEquals(
            "需要你的确认",
            group?.visibleMessagesOldestFirst?.single()?.cardData?.get("title"),
        )
        assertEquals(
            "请告诉我下一步怎么做",
            group?.visibleMessagesOldestFirst?.single()?.cardData?.get("detail"),
        )
    }

    @Test
    fun `projects ACP permission request without rendering a request card`() {
        val request = cardMessage(
            mapOf(
                "type" to "agent_request",
                "requestKind" to "approval",
                "requestId" to "permission-1",
                "title" to "允许执行命令",
                "detail" to "需要访问工作区",
                "taskId" to "run-approval",
                "runId" to "run-approval",
            ),
            id = "permission-1-card",
            streamMeta = mapOf(
                "runId" to "run-approval",
                "kind" to "permission_required",
            ),
        )

        val entries = buildAgentRunTimelineEntries(
            listOf(request),
            activeTaskIds = setOf("run-approval"),
        )

        val rendered = entries.single().group!!.visibleMessagesOldestFirst.single()
        assertEquals(2, rendered.type)
        assertEquals(3, rendered.user)
        assertEquals(true, rendered.cardData?.get("simplePresentation"))
        assertEquals("允许执行命令", rendered.cardData?.get("title"))
        assertEquals("需要访问工作区", rendered.cardData?.get("detail"))
    }

    @Test
    fun `hides structured ACP schema from the rendered question`() {
        val request = cardMessage(
            mapOf(
                "type" to "agent_request",
                "requestKind" to "user_input",
                "requestId" to "schema-request",
                "title" to "The agent needs your input.",
                "detail" to "{\"type\":\"object\",\"properties\":{}}",
                "rawParamsJson" to
                    "{\"requestedSchema\":{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\",\"title\":\"插件名称\",\"description\":\"请输入要安装的插件\"}}}}",
                "runId" to "run-schema",
            ),
            id = "schema-request-card",
            streamMeta = mapOf("runId" to "run-schema"),
        )

        val entries = buildAgentRunTimelineEntries(listOf(request))
        val rendered = entries.single().group!!.visibleMessagesOldestFirst.single()
        assertEquals(2, rendered.type)
        assertEquals("插件名称", rendered.cardData?.get("title"))
        assertEquals("请输入要安装的插件", rendered.cardData?.get("detail"))
        val detail = rendered.cardData?.get("detail") as String
        assertFalse(detail.contains("requestedSchema"))
    }

    @Test
    fun `uses turn-owned content anchors over a stale tool timestamp`() {
        // Dart `DateTime(2026, 8, 22, 14, 29, 24, 493)` is local time.
        val startedAt = LocalDateTime.of(2026, 8, 22, 14, 29, 24, 493_000_000)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val messages = buildCompletedRunMessages().map { message ->
            if (message.id == "task-1-tool") {
                return@map message.copy(createAtMillis = startedAt - 2 * 60 * 1000L)
            }
            if (message.id == "task-1-thinking") {
                return@map message.copy(createAtMillis = startedAt)
            }
            if (message.id == "task-1-text") {
                return@map message.copy(createAtMillis = startedAt + 4 * 1000L)
            }
            message
        }

        val group = buildAgentRunTimelineEntries(messages).first().group!!

        assertEquals(startedAt, group.startedAtMillis)
        assertEquals(startedAt + 4 * 1000L, group.finishedAtMillis)
    }

    @Test
    fun `keeps every prose message visible when history lacks isFinal`() {
        val messages = listOf(
            assistantMessage(
                id = "task-2-text-2",
                text = "第二版回答",
                taskId = "task-2",
                kind = "text_snapshot",
                seq = 22,
                isFinal = null,
            ),
            assistantMessage(
                id = "task-2-text-1",
                text = "第一版回答",
                taskId = "task-2",
                kind = "text_snapshot",
                seq = 21,
                isFinal = null,
            ),
            thinkingCard(id = "task-2-thinking", taskId = "task-2", seq = 12),
        )

        val entries = buildAgentRunTimelineEntries(messages)

        assertEquals(1, entries.size)
        assertEquals(
            listOf("task-2-text-1", "task-2-text-2"),
            entries.single().group?.visibleMessagesOldestFirst?.map { it.id },
        )
    }

    @Test
    fun `groups an in-flight run and marks it running`() {
        val entries = buildAgentRunTimelineEntries(
            buildCompletedRunMessages(isFinal = false),
            activeTaskIds = setOf("task-1"),
        )

        // An active run is one group, not a pile of loose bubbles.
        assertEquals(2, entries.size)
        assertEquals("task-1", entries.first().group?.taskId)
        assertEquals(AgentRunStatus.running, entries.first().group?.status)
        assertEquals("user-1", entries.last().message?.id)
    }

    @Test
    fun `run status follows the active task set, not a persisted flag`() {
        val messages = buildCompletedRunMessages(isFinal = true)
        val activeEntries = buildAgentRunTimelineEntries(
            messages,
            activeTaskIds = setOf("task-1"),
        )
        val completedEntries = buildAgentRunTimelineEntries(messages)

        assertEquals(AgentRunStatus.running, activeEntries.first().group?.status)
        assertEquals(2, completedEntries.size)
        assertEquals("task-1", completedEntries.first().group?.taskId)
        assertEquals(AgentRunStatus.finished, completedEntries.first().group?.status)
        assertEquals(
            "task-1-text",
            completedEntries.first().group?.visibleMessagesNewestFirst?.single()?.id,
        )
        val processIds = completedEntries.first().group?.processMessagesNewestFirst?.map { it.id }
        assertNotNull(processIds)
        assertTrue(processIds!!.containsAll(listOf("task-1-tool", "task-1-thinking")))
    }

    @Test
    fun `groups a finished run whose snapshots all say isFinal false`() {
        // Regression for on-device conversation 58: grouping must depend only on
        // the run no longer being active.
        val messages = listOf(
            assistantMessage(
                id = "msg-e-agent-message",
                text = "最终答案",
                taskId = "dc8c5328",
                kind = "text_snapshot",
                seq = 11,
                isFinal = false,
            ),
            assistantMessage(
                id = "msg-d-agent-message",
                text = "中间叙述 2",
                taskId = "dc8c5328",
                kind = "text_snapshot",
                seq = 7,
                isFinal = false,
            ),
            assistantMessage(
                id = "msg-c-agent-message",
                text = "中间叙述 1",
                taskId = "dc8c5328",
                kind = "text_snapshot",
                seq = 6,
                isFinal = false,
            ),
            cardMessageWithMeta(
                id = "exec-1-agent-command",
                taskId = "dc8c5328",
                kind = "tool_completed",
                seq = 5,
                cardData = mapOf(
                    "type" to "agent_tool_summary",
                    "status" to "success",
                    "toolType" to "terminal",
                    "toolTitle" to "ls",
                ),
            ),
            userMessage("mimo code 有 acp 协议吗？", id = "user-58"),
        )

        val entries = buildAgentRunTimelineEntries(messages)

        assertEquals(2, entries.size)
        val group = entries.first().group
        assertEquals("dc8c5328", group?.taskId)
        assertEquals(AgentRunStatus.finished, group?.status)
        assertEquals(
            listOf(
                "msg-c-agent-message",
                "msg-d-agent-message",
                "msg-e-agent-message",
            ),
            group?.visibleMessagesOldestFirst?.map { it.id },
        )
        assertEquals(
            listOf("exec-1-agent-command"),
            group?.processMessagesOldestFirst?.map { it.id },
        )
    }

    @Test
    fun `groups a text-only turn so it still gets a header`() {
        val messages = listOf(
            assistantMessage(
                id = "task-9-text",
                text = "简短回答",
                taskId = "task-9",
                kind = "text_snapshot",
                seq = 2,
                isFinal = false,
            ),
            userMessage("简短问题", id = "user-9"),
        )

        val entries = buildAgentRunTimelineEntries(messages)

        assertEquals(2, entries.size)
        assertEquals("task-9", entries.first().group?.taskId)
        assertEquals(true, entries.first().group?.processMessagesNewestFirst?.isEmpty())
        assertEquals(
            "task-9-text",
            entries.first().group?.visibleMessagesNewestFirst?.single()?.id,
        )
    }

    @Test
    fun `many message-less active ids collapse to a single running header`() {
        val entries = buildAgentRunTimelineEntries(
            buildCompletedRunMessages(),
            activeTaskIds = setOf(
                "msg-a-agent-message",
                "msg-b-agent-message",
                "msg-c-agent-message",
                "task-1-ai",
            ),
        )

        val runningGroups = entries.filter { entry -> entry.group?.isRunning ?: false }
        assertEquals(1, runningGroups.size)
        assertEquals(2, entries.filter { entry -> entry.group != null }.size)
    }

    @Test
    fun `no pending header once a real run is already streaming`() {
        val entries = buildAgentRunTimelineEntries(
            buildCompletedRunMessages(isFinal = false),
            activeTaskIds = setOf(
                "task-1",
                "task-1-ai",
                "msg-a-agent-message",
            ),
        )

        assertEquals(1, entries.filter { entry -> entry.group?.isRunning ?: false }.size)
        assertEquals("task-1", entries.first().group?.taskId)
    }

    @Test
    fun `resolves the run agent id per message, then per conversation`() {
        val withMessageIdentity = buildAgentRunTimelineEntries(
            listOf(
                assistantMessage(
                    id = "task-a-text",
                    text = "答案",
                    taskId = "task-a",
                    kind = "text_snapshot",
                    seq = 2,
                    agentId = "claude-code-acp",
                ),
                userMessage("问题", id = "user-a"),
            ),
            conversationAgentId = "codex-acp",
        )
        assertEquals("claude-code-acp", withMessageIdentity.first().group?.agentId)

        val withoutMessageIdentity = buildAgentRunTimelineEntries(
            listOf(
                assistantMessage(
                    id = "task-b-text",
                    text = "答案",
                    taskId = "task-b",
                    kind = "text_snapshot",
                    seq = 2,
                ),
                userMessage("问题", id = "user-b"),
            ),
            conversationAgentId = "opencode-acp",
        )
        assertEquals("opencode-acp", withoutMessageIdentity.first().group?.agentId)

        val withNeither = buildAgentRunTimelineEntries(
            listOf(
                assistantMessage(
                    id = "task-c-text",
                    text = "答案",
                    taskId = "task-c",
                    kind = "text_snapshot",
                    seq = 2,
                ),
                userMessage("问题", id = "user-c"),
            ),
        )
        assertEquals(kGenericAgentId, withNeither.first().group?.agentId)
    }

    @Test
    fun `keeps permission card visible alongside final permission text`() {
        val messages = listOf(
            cardMessageWithMeta(
                id = "task-3-permission-card",
                taskId = "task-3",
                kind = "permission_required",
                seq = 31,
                cardData = mapOf(
                    "type" to "permission_section",
                    "requiredPermissionIds" to listOf("overlay"),
                ),
            ),
            assistantMessage(
                id = "task-3-permission-text",
                text = "请先授权",
                taskId = "task-3",
                kind = "permission_required",
                seq = 30,
                isFinal = true,
            ),
            thinkingCard(id = "task-3-thinking", taskId = "task-3", seq = 10),
        )

        val entries = buildAgentRunTimelineEntries(messages)

        assertEquals(1, entries.size)
        assertEquals(2, entries.single().group?.visibleMessagesNewestFirst?.size)
        val visibleIds = entries.single().group?.visibleMessagesNewestFirst?.map { it.id }
        assertNotNull(visibleIds)
        assertTrue(visibleIds!!.containsAll(listOf("task-3-permission-card", "task-3-permission-text")))
    }

    @Test
    fun `groups active codex request as the visible run message`() {
        val messages = listOf(
            codexRequestCard(id = "turn-7-request", taskId = "turn-7", seq = 12),
            userMessage("需要选择方案", id = "user-7"),
        )

        val entries = buildAgentRunTimelineEntries(
            messages,
            activeTaskIds = setOf("turn-7"),
        )

        assertEquals(2, entries.size)
        assertEquals("turn-7", entries.first().group?.taskId)
        assertEquals(true, entries.first().group?.processMessagesNewestFirst?.isEmpty())
        assertEquals(
            "turn-7-request",
            entries.first().group?.visibleMessagesNewestFirst?.single()?.id,
        )
        assertEquals("user-7", entries.last().message?.id)
    }

    @Test
    fun `keeps codex request visible after thinking process cards`() {
        val messages = listOf(
            codexRequestCard(id = "turn-8-request", taskId = "turn-8", seq = 22),
            thinkingCard(id = "turn-8-thinking", taskId = "turn-8", seq = 10),
            userMessage("继续计划吗", id = "user-8"),
        )

        val entries = buildAgentRunTimelineEntries(
            messages,
            activeTaskIds = setOf("turn-8"),
        )

        val group = entries.first().group
        assertEquals("turn-8", group?.taskId)
        assertEquals("turn-8-request", group?.visibleMessagesNewestFirst?.single()?.id)
        assertEquals("turn-8-thinking", group?.processMessagesNewestFirst?.single()?.id)
    }

    @Test
    fun `uses cancelled text as the visible body for a manually stopped run`() {
        val messages = listOf(
            assistantMessage(
                id = "task-5-cancelled",
                text = "任务已取消",
                taskId = "task-5",
                kind = "text_snapshot",
                seq = 1000000000,
                isFinal = true,
            ),
            thinkingCard(id = "task-5-thinking", taskId = "task-5", seq = 12),
        )

        val entries = buildAgentRunTimelineEntries(messages)

        assertEquals(1, entries.size)
        assertEquals(
            "任务已取消",
            entries.single().group?.visibleMessagesNewestFirst?.single()?.text,
        )
        assertEquals(
            "task-5-thinking",
            entries.single().group?.processMessagesNewestFirst?.single()?.id,
        )
    }

    @Test
    fun `orders a turn by arrival, not by stream sequence`() {
        val messages = listOf(
            assistantMessage(
                id = "task-6-text-2",
                text = "任务已被手动停止。需要换一种方式发送吗？",
                taskId = "task-6",
                kind = "text_snapshot",
                seq = 105,
                entrySeq = 5,
                isFinal = true,
            ),
            thinkingCard(
                id = "task-6-thinking-2",
                taskId = "task-6",
                seq = 104,
                entrySeq = 4,
            ),
            cardMessageWithMeta(
                id = "task-6-tool-1",
                taskId = "task-6",
                kind = "tool_completed",
                seq = 69,
                entrySeq = 3,
                cardData = mapOf(
                    "type" to "agent_tool_summary",
                    "status" to "failed",
                    "toolType" to "terminal_execute",
                    "toolTitle" to "执行命令",
                    "summary" to "命令执行失败",
                ),
            ),
            chatMessage(
                id = "task-6-text",
                type = 1,
                user = 2,
                content = mapOf("id" to "task-6-text", "text" to "好的，我来执行这个命令。"),
            ),
            thinkingCard(
                id = "task-6-thinking",
                taskId = "task-6",
                seq = 70,
                entrySeq = 1,
            ),
            userMessage("用户问题", id = "user-6"),
        )

        val entries = buildAgentRunTimelineEntries(messages)

        assertEquals(2, entries.size)
        val group = entries.first().group
        assertEquals("task-6", group?.taskId)
        // `task-6-text` carries no streamMeta at all, so it can only be placed by
        // where it sits in the list.
        assertEquals(
            listOf("task-6-text", "task-6-text-2"),
            group?.visibleMessagesOldestFirst?.map { it.id },
        )
        assertEquals(
            listOf("task-6-thinking", "task-6-tool-1", "task-6-thinking-2"),
            group?.processMessagesOldestFirst?.map { it.id },
        )
        assertEquals("user-6", entries.last().message?.id)
    }

    @Test
    fun `restores Xiaowan prompt before its completed run from an oldest-first snapshot`() {
        val timestamp = "1786765190269"
        val taskId = "$timestamp-ai"
        val messages = listOf(
            chatMessage(
                id = "$timestamp-user",
                type = 1,
                user = 1,
                content = mapOf(
                    "id" to "$timestamp-user",
                    "text" to "怎么登录 GitHub？",
                ),
            ),
            thinkingCard(
                id = "$taskId-thinking",
                taskId = taskId,
                seq = 44,
                entrySeq = 1,
            ),
            cardMessageWithMeta(
                id = "$taskId-tool-1",
                taskId = taskId,
                kind = "tool_completed",
                seq = 47,
                entrySeq = 2,
                cardData = toolCard("context_apps_query"),
            ),
            thinkingCard(
                id = "$taskId-thinking-2",
                taskId = taskId,
                seq = 52,
                entrySeq = 3,
            ),
            assistantMessage(
                id = "$taskId-text",
                text = "你手机上已经装了 GitHub 官方 App。",
                taskId = taskId,
                kind = "text_snapshot",
                seq = 252,
                entrySeq = 4,
                isFinal = true,
            ),
        )

        val entries = buildAgentRunTimelineEntries(messages)

        assertEquals(2, entries.size)
        assertEquals(taskId, entries.first().group?.taskId)
        assertEquals("$timestamp-user", entries.last().message?.id)
        assertEquals(
            listOf(
                "$taskId-thinking",
                "$taskId-tool-1",
                "$taskId-thinking-2",
                "$taskId-text",
            ),
            entries.first().group?.allMessagesOldestFirst?.map { it.id },
        )
    }

    @Test
    fun `orders persisted ACP terminal frames by seq when entrySeq is absent`() {
        val taskId = "persisted-acp-turn"
        val messages = listOf(
            cardMessageWithMeta(
                id = "$taskId-tool",
                taskId = taskId,
                kind = "tool_completed",
                seq = 2,
                isFinal = true,
                cardData = toolCard("读取工具结果"),
            ),
            thinkingCard(
                id = "$taskId-thinking",
                taskId = taskId,
                seq = 1,
                isFinal = true,
            ),
            assistantMessage(
                id = "$taskId-text",
                taskId = taskId,
                kind = "text_snapshot",
                seq = 3,
                text = "最终回答",
                isFinal = true,
            ),
        )

        val group = buildAgentRunTimelineEntries(messages).single().group!!

        assertEquals(
            listOf("$taskId-thinking", "$taskId-tool", "$taskId-text"),
            group.allMessagesOldestFirst.map { it.id },
        )
    }

    @Test
    fun `restores partially sequenced Xiaowan prose inside its chronological tool rounds`() {
        val timestamp = "1786765957366"
        val taskId = "$timestamp-ai"
        val base = 1786765957000L
        fun at(milliseconds: Int): Long = base + milliseconds

        fun missingMetaText(id: String, text: String, createdAt: Int): ChatMessageUi {
            return ChatMessageUi(
                id = id,
                type = 1,
                user = 2,
                content = mapOf("id" to id, "text" to text),
                streamMeta = mapOf("entryId" to id, "isFinal" to false),
                createAtMillis = at(createdAt),
            )
        }

        // The three entries whose stable metadata was stripped are placed ahead
        // of every sequenced entry, even though their creation times belong in
        // the middle of the run.
        val messages = listOf(
            missingMetaText("$taskId-text-7", "诊断完成", 700),
            missingMetaText("$taskId-text-6", "安装 ssh 客户端", 500),
            missingMetaText("$taskId-text-5", "验证 SSH 握手", 300),
            assistantMessage(
                id = "$taskId-text-8",
                text = "最终环境诊断结果",
                taskId = taskId,
                kind = "text_snapshot",
                seq = 839,
                entrySeq = 11,
                isFinal = true,
            ).copy(createAtMillis = at(900)),
            thinkingCard(
                id = "$taskId-thinking-9",
                taskId = taskId,
                seq = 444,
                entrySeq = 10,
            ).copy(createAtMillis = at(800)),
            cardMessageWithMeta(
                id = "$taskId-tool-8",
                taskId = taskId,
                kind = "tool_completed",
                seq = 441,
                entrySeq = 9,
                cardData = toolCard("memory_write_daily"),
            ).copy(createAtMillis = at(750)),
            thinkingCard(
                id = "$taskId-thinking-8",
                taskId = taskId,
                seq = 422,
                entrySeq = 8,
            ).copy(createAtMillis = at(600)),
            cardMessageWithMeta(
                id = "$taskId-tool-6",
                taskId = taskId,
                kind = "tool_completed",
                seq = 383,
                entrySeq = 7,
                cardData = toolCard("terminal_execute"),
            ).copy(createAtMillis = at(550)),
            thinkingCard(
                id = "$taskId-thinking-6",
                taskId = taskId,
                seq = 357,
                entrySeq = 6,
            ).copy(createAtMillis = at(400)),
            cardMessageWithMeta(
                id = "$taskId-tool-5",
                taskId = taskId,
                kind = "tool_completed",
                seq = 352,
                entrySeq = 5,
                cardData = toolCard("terminal_execute"),
            ).copy(createAtMillis = at(350)),
            thinkingCard(
                id = "$taskId-thinking-5",
                taskId = taskId,
                seq = 307,
                entrySeq = 4,
            ).copy(createAtMillis = at(200)),
        )

        val group = buildAgentRunTimelineEntries(messages).single().group!!

        assertEquals(
            listOf(
                "$taskId-thinking-5",
                "$taskId-text-5",
                "$taskId-tool-5",
                "$taskId-thinking-6",
                "$taskId-text-6",
                "$taskId-tool-6",
                "$taskId-thinking-8",
                "$taskId-text-7",
                "$taskId-tool-8",
                "$taskId-thinking-9",
                "$taskId-text-8",
            ),
            group.allMessagesOldestFirst.map { it.id },
        )
    }

    @Test
    fun `restores multiple legacy Xiaowan turns newest-first`() {
        val firstTimestamp = "1786765116611"
        val secondTimestamp = "1786765190269"
        val messages = listOf(
            userMessage("第一问", id = "$firstTimestamp-user"),
            assistantMessage(
                id = "$firstTimestamp-ai-text",
                text = "第一答",
                taskId = "$firstTimestamp-ai",
                kind = "text_snapshot",
                seq = 10,
                entrySeq = 1,
                isFinal = true,
            ),
            userMessage("第二问", id = "$secondTimestamp-user"),
            assistantMessage(
                id = "$secondTimestamp-ai-text",
                text = "第二答",
                taskId = "$secondTimestamp-ai",
                kind = "text_snapshot",
                seq = 20,
                entrySeq = 1,
                isFinal = true,
            ),
        )

        val entries = buildAgentRunTimelineEntries(messages)

        assertEquals(
            listOf(
                "agent-run-$secondTimestamp-ai",
                "$secondTimestamp-user",
                "agent-run-$firstTimestamp-ai",
                "$firstTimestamp-user",
            ),
            entries.map { entry -> entry.key },
        )
    }

    @Test
    fun `interleaved tool batches stay separate around agent prose`() {
        // Regression for on-device conversation 60: the complete trace must keep
        // its arrival order when the user expands the finished run.
        val messages = listOf(
            cardMessageWithMeta(
                id = "exec-4-agent-command",
                taskId = "turn-60",
                kind = "tool_completed",
                seq = 5,
                cardData = toolCard("sed"),
            ),
            cardMessageWithMeta(
                id = "exec-3-agent-command",
                taskId = "turn-60",
                kind = "tool_completed",
                seq = 4,
                cardData = toolCard("grep"),
            ),
            assistantMessage(
                id = "msg-2-agent-message",
                text = "第二段正文",
                taskId = "turn-60",
                kind = "text_snapshot",
                seq = 3,
                isFinal = false,
            ),
            cardMessageWithMeta(
                id = "exec-2-agent-command",
                taskId = "turn-60",
                kind = "tool_completed",
                seq = 2,
                cardData = toolCard("ls"),
            ),
            assistantMessage(
                id = "msg-1-agent-message",
                text = "第一段正文",
                taskId = "turn-60",
                kind = "text_snapshot",
                seq = 1,
                isFinal = false,
            ),
            userMessage("问题", id = "user-60"),
        )

        val group = buildAgentRunTimelineEntries(messages).first().group

        assertEquals(
            listOf(
                listOf("msg-1-agent-message"),
                listOf("exec-2-agent-command"),
                listOf("msg-2-agent-message"),
                listOf("exec-3-agent-command", "exec-4-agent-command"),
            ),
            group?.segmentsOldestFirst?.map { segment -> segment.messages.map { it.id } },
        )
        // Two folds, not one: prose between the batches keeps them apart.
        assertEquals(2, group?.segmentsOldestFirst?.filter { it.isProcess }?.size)
    }

    // -------------------------------------------------------------------------
    // Fixtures mirroring the Dart test helpers and ChatMessageModel factories.

    /** Dart `ChatMessageModel(...)`: `createAt` defaults to `DateTime.now()`. */
    private fun chatMessage(
        id: String,
        type: Int,
        user: Int,
        content: Map<String, Any?>? = null,
        streamMeta: Map<String, Any?>? = null,
    ): ChatMessageUi = ChatMessageUi(
        id = id,
        type = type,
        user = user,
        content = content,
        streamMeta = streamMeta,
        createAtMillis = System.currentTimeMillis(),
    )

    /** Dart `ChatMessageModel.userMessage(text, id:)`. */
    private fun userMessage(text: String, id: String): ChatMessageUi =
        chatMessage(id = id, type = 1, user = 1, content = mapOf("text" to text, "id" to id))

    /** Dart `ChatMessageModel.cardMessage(cardData, id:, streamMeta:)`. */
    private fun cardMessage(
        cardData: Map<String, Any?>,
        id: String,
        streamMeta: Map<String, Any?>? = null,
    ): ChatMessageUi = chatMessage(
        id = id,
        type = 2,
        user = 3,
        content = mapOf("cardData" to cardData, "id" to id),
        streamMeta = streamMeta,
    )

    /**
     * Dart `ChatMessageModel.fromJson(message.toJson())`. ChatMessageUi has no
     * JSON codec; for the card messages these tests round-trip, the Dart codec
     * preserves every field the timeline reads, so this is a field-for-field copy.
     */
    private fun roundTrip(message: ChatMessageUi): ChatMessageUi = message.copy()

    private fun buildCompletedRunMessages(isFinal: Boolean = true): List<ChatMessageUi> = listOf(
        assistantMessage(
            id = "task-1-text",
            text = "最终回答",
            taskId = "task-1",
            kind = "text_snapshot",
            seq = 30,
            isFinal = isFinal,
        ),
        cardMessageWithMeta(
            id = "task-1-tool",
            taskId = "task-1",
            kind = "tool_completed",
            seq = 20,
            cardData = mapOf(
                "type" to "agent_tool_summary",
                "status" to "success",
                "toolType" to "workspace",
                "toolTitle" to "读取配置文件",
                "summary" to "配置读取完成",
            ),
        ),
        thinkingCard(id = "task-1-thinking", taskId = "task-1", seq = 10),
        userMessage("用户问题", id = "user-1"),
    )

    private fun assistantMessage(
        id: String,
        text: String,
        taskId: String,
        kind: String,
        seq: Int,
        entrySeq: Int? = null,
        isFinal: Boolean? = false,
        agentId: String? = null,
    ): ChatMessageUi = chatMessage(
        id = id,
        type = 1,
        user = 2,
        content = buildMap {
            put("text", text)
            put("id", id)
            if (agentId != null) put("agentId", agentId)
        },
        streamMeta = streamMeta(taskId, kind, seq, entrySeq, id, isFinal),
    )

    private fun toolCard(title: String): Map<String, Any?> = mapOf(
        "type" to "agent_tool_summary",
        "status" to "success",
        "toolType" to "terminal",
        "toolTitle" to title,
    )

    private fun thinkingCard(
        id: String,
        taskId: String,
        seq: Int,
        entrySeq: Int? = null,
        isFinal: Boolean? = false,
    ): ChatMessageUi = cardMessageWithMeta(
        id = id,
        taskId = taskId,
        kind = "thinking_snapshot",
        seq = seq,
        entrySeq = entrySeq,
        isFinal = isFinal,
        cardData = mapOf(
            "type" to "deep_thinking",
            "thinkingContent" to "思考过程",
            "stage" to 4,
            "isLoading" to false,
            "taskID" to taskId,
            "cardId" to id,
        ),
    )

    private fun codexRequestCard(id: String, taskId: String, seq: Int): ChatMessageUi = cardMessageWithMeta(
        id = id,
        taskId = taskId,
        kind = "clarify_required",
        seq = seq,
        cardData = mapOf(
            "type" to "codex_request",
            "taskId" to taskId,
            "cardId" to id,
            "requestId" to id,
            "requestKind" to "user_input",
            "status" to "pending",
        ),
    )

    /** Dart test helper `_cardMessage`. */
    private fun cardMessageWithMeta(
        id: String,
        taskId: String,
        kind: String,
        seq: Int,
        entrySeq: Int? = null,
        isFinal: Boolean? = false,
        cardData: Map<String, Any?>,
    ): ChatMessageUi = cardMessage(
        cardData,
        id = id,
        streamMeta = streamMeta(taskId, kind, seq, entrySeq, id, isFinal),
    )

    private fun streamMeta(
        taskId: String,
        kind: String,
        seq: Int,
        entrySeq: Int?,
        entryId: String,
        isFinal: Boolean?,
    ): Map<String, Any?> = buildMap {
        put("parentTaskId", taskId)
        put("kind", kind)
        put("seq", seq)
        if (entrySeq != null) put("entrySeq", entrySeq)
        put("entryId", entryId)
        if (isFinal != null) put("isFinal", isFinal)
    }
}
