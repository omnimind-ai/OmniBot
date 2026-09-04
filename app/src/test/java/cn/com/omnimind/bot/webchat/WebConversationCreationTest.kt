package cn.com.omnimind.bot.webchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebConversationCreationTest {

    @Test
    fun `conversation harness ownership prefers durable conversation binding`() {
        assertEquals(
            "deepseek-harness-acp",
            resolveConversationHarnessOwner(
                storedMode = "agent",
                sessionAgentId = "xiaowan-acp",
                conversationAgentId = "deepseek-harness-acp",
            ),
        )
    }

    @Test
    fun `legacy conversation modes recover their original harness`() {
        assertEquals(
            "xiaowan-acp",
            resolveConversationHarnessOwner(
                storedMode = "normal",
                sessionAgentId = null,
                conversationAgentId = null,
            ),
        )
        assertEquals(
            "codex-acp",
            resolveConversationHarnessOwner(
                storedMode = "codex",
                sessionAgentId = null,
                conversationAgentId = null,
            ),
        )
    }

    @Test
    fun `unbound canonical agent conversation cannot inherit selected harness`() {
        assertEquals(
            "xiaowan-acp",
            resolveConversationHarnessOwner(
                storedMode = "agent",
                sessionAgentId = null,
                conversationAgentId = null,
            ),
        )
    }

    @Test
    fun `stored conversation mode wins over a stale Agent request fallback`() {
        assertEquals(
            "agent",
            resolveWebConversationMode(
                storedMode = "codex",
                requestedMode = "normal"
            )
        )
        assertEquals(
            "chat_only",
            resolveWebConversationMode(
                storedMode = "chat_only",
                requestedMode = null
            )
        )
    }

    @Test
    fun `each stored mode selects its own runtime`() {
        assertEquals(
            WebConversationRunKind.AGENT,
            resolveWebConversationRunKind("normal")
        )
        assertEquals(
            WebConversationRunKind.AGENT,
            resolveWebConversationRunKind("codex")
        )
        assertEquals(
            "agent",
            resolveWebConversationMode("codex", "normal")
        )
        assertEquals(
            WebConversationRunKind.CHAT_ONLY,
            resolveWebConversationRunKind("chat_only")
        )
    }

    @Test
    fun `agent stream events are mapped to web updates`() {
        val assistantUpdate = parseWebAgentEvent(
            mapOf(
                "method" to "item/agentMessage/delta",
                "turnId" to "turn-1",
                "params" to mapOf(
                    "itemId" to "item-1",
                    "delta" to "hello"
                )
            )
        )
        assertEquals("hello", assistantUpdate.assistantDelta)
        assertEquals("item-1-agent-message", assistantUpdate.assistantEntryId)
        assertEquals("turn-1", assistantUpdate.parentTaskId)

        assertEquals(
            "thinking",
            parseWebAgentEvent(
                mapOf(
                    "method" to "item/reasoning/delta",
                    "turnId" to "turn-1",
                    "params" to mapOf(
                        "itemId" to "reasoning-1",
                        "delta" to "thinking"
                    )
                )
            ).reasoningDelta
        )
        assertEquals(
            "completed",
            parseWebAgentEvent(
                mapOf("method" to "turn/completed")
            ).terminalKind
        )
    }

    @Test
    fun `official ACP session updates are mapped to web updates`() {
        val message = parseWebAgentEvent(
            mapOf(
                "method" to "session/update",
                "turnId" to "turn-acp-1",
                "params" to mapOf(
                    "sessionId" to "session-1",
                    "update" to mapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "content" to mapOf("text" to "hello")
                    )
                )
            )
        )
        assertEquals("hello", message.assistantDelta)
        assertEquals("turn-acp-1", message.parentTaskId)

        val tool = parseWebAgentEvent(
            mapOf(
                "method" to "session/update",
                "turnId" to "turn-acp-1",
                "params" to mapOf(
                    "update" to mapOf(
                        "sessionUpdate" to "tool_call_update",
                        "toolCallId" to "tool-1",
                        "title" to "Read file",
                        "status" to "completed"
                    )
                )
            )
        ).tool
        assertEquals("tool-1-agent-file", tool?.entryId)
        assertEquals("success", tool?.status)
    }

    @Test
    fun `web agent runs explicitly request full access`() {
        val arguments = buildWebAgentTurnArguments(
            conversationId = 42L,
            userMessage = "检查权限",
            attachments = emptyList(),
            cwd = " /workspace ",
            agentId = " claude-code-acp ",
            model = "deepseek-v4-pro",
            effort = "high",
            conversationMode = "chat_only"
        )

        assertEquals(42L, arguments["conversationId"])
        assertEquals("检查权限", arguments["text"])
        assertEquals("never", arguments["approvalPolicy"])
        assertEquals("user", arguments["approvalsReviewer"])
        assertEquals(
            mapOf("type" to "dangerFullAccess"),
            arguments["sandboxPolicy"]
        )
        assertEquals("/workspace", arguments["cwd"])
        assertEquals("claude-code-acp", arguments["agentId"])
        assertEquals("deepseek-v4-pro", arguments["model"])
        assertEquals("high", arguments["effort"])
        assertEquals("chat_only", arguments["conversationMode"])
    }

    @Test
    fun `web agent selection prefers the stored conversation binding`() {
        assertEquals(
            "opencode-acp",
            resolveWebAgentId(
                storedAgentId = "opencode-acp",
                requestedAgentId = null
            )
        )
        assertEquals(
            "claude-code-acp",
            resolveWebAgentId(
                storedAgentId = null,
                requestedAgentId = " claude-code-acp "
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `web agent selection rejects a conflicting request`() {
        resolveWebAgentId(
            storedAgentId = "codex-acp",
            requestedAgentId = "opencode-acp"
        )
    }

    @Test
    fun `normal web conversation always resolves to Xiaowan`() {
        assertEquals(
            "xiaowan-acp",
            resolveWebAgentId(
                storedAgentId = null,
                requestedAgentId = null,
                conversationMode = "normal",
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `normal web conversation rejects another Harness`() {
        resolveWebAgentId(
            storedAgentId = null,
            requestedAgentId = "dsh",
            conversationMode = "normal",
        )
    }

    @Test
    fun `agent tool lifecycle keeps a stable card id and terminal status`() {
        val started = parseWebAgentEvent(
            mapOf(
                "method" to "item/started",
                "turnId" to "turn-2",
                "params" to mapOf(
                    "item" to mapOf(
                        "id" to "command-1",
                        "type" to "commandExecution",
                        "command" to "pwd",
                        "status" to "running"
                    )
                )
            )
        ).tool
        val completed = parseWebAgentEvent(
            mapOf(
                "method" to "item/completed",
                "turnId" to "turn-2",
                "params" to mapOf(
                    "item" to mapOf(
                        "id" to "command-1",
                        "type" to "commandExecution",
                        "command" to "pwd",
                        "status" to "completed"
                    )
                )
            )
        ).tool

        assertEquals("command-1-agent-command", started?.entryId)
        assertEquals("running", started?.status)
        assertEquals(started?.entryId, completed?.entryId)
        assertEquals("success", completed?.status)
        assertEquals("turn-2", completed?.parentTaskId)
    }

    @Test
    fun `web ACP uses pending status over nested raw output`() {
        val tool = parseWebAgentEvent(
            mapOf(
                "method" to "session/update",
                "turnId" to "turn-actionable",
                "params" to mapOf(
                    "update" to mapOf(
                        "sessionUpdate" to "tool_call_update",
                        "toolCallId" to "tool-actionable",
                        "title" to "高权限操作",
                        "status" to "pending",
                        "success" to false,
                        "rawOutput" to mapOf(
                            "success" to false,
                            "question" to "请确认执行高权限操作"
                        )
                    )
                )
            )
        ).tool

        assertEquals("pending", tool?.status)
    }

    @Test
    fun `first user message becomes the conversation title like Flutter`() {
        assertEquals("帮我分析这个项目", deriveWebConversationTitle("  帮我分析这个项目  "))
        assertEquals(
            "12345678901234567890...",
            deriveWebConversationTitle("123456789012345678901234")
        )
        assertNull(deriveWebConversationTitle("   "))
    }
}
