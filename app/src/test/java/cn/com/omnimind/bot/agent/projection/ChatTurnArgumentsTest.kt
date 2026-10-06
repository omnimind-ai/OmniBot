package cn.com.omnimind.bot.agent.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ports the argument, model-source and attachment cases of
 * ui/test/services/agent_runtime_service_test.dart, plus the 5d-0 attachment fix.
 */
class ChatTurnArgumentsTest {
    @Test
    fun `builds canonical session-new and session-prompt arguments`() {
        assertEquals(
            mapOf("conversationId" to 42, "model" to "model-1", "conversationMode" to "agent"),
            newSessionArguments(conversationId = 42, model = " model-1 ", effort = "", conversationMode = "agent"),
        )
        assertEquals(
            mapOf("sessionId" to "s1", "conversationId" to 42, "requestId" to "req", "text" to "hi"),
            promptSessionArguments(sessionId = "s1", conversationId = 42, requestId = " req ", text = "hi"),
        )
    }

    @Test
    fun `prompt arguments carry the permission payload and keep key order`() {
        val attachment = mapOf("id" to "image-1", "name" to "screen.png", "path" to "/tmp/screen.png", "mimeType" to "image/png", "isImage" to true)
        val args = promptSessionArguments(
            text = "hello",
            sessionId = "thread-1",
            conversationId = 42,
            requestId = "1-ai",
            agentId = " codex-acp ",
            attachments = listOf(attachment),
            permission = AgentPermissionMode.FullAccess,
            model = "gpt-5-codex",
            effort = "high",
            collaborationMode = "plan",
            conversationMode = "agent",
            terminalEnvironment = mapOf("API_ENDPOINT" to "https://example.test"),
        )
        assertEquals(
            listOf(
                "sessionId", "conversationId", "requestId", "agentId", "approvalPolicy", "approvalsReviewer",
                "sandboxPolicy", "model", "effort", "collaborationMode", "conversationMode", "terminalEnvironment",
                "text", "attachments",
            ),
            args.keys.toList(),
        )
        assertEquals("codex-acp", args["agentId"])
        assertEquals("never", args["approvalPolicy"])
        assertEquals("user", args["approvalsReviewer"])
        assertEquals(mapOf("type" to "dangerFullAccess"), args["sandboxPolicy"])
        assertEquals(listOf(attachment), args["attachments"])
    }

    @Test
    fun `text is always sent, even when empty, and empty extras are omitted`() {
        val args = promptSessionArguments(text = "", terminalEnvironment = emptyMap(), attachments = emptyList(), agentId = "  ")
        assertEquals(mapOf("text" to ""), args)
    }

    @Test
    fun `permission modes map to the ACP policy triple`() {
        fun triple(mode: AgentPermissionMode) = Triple(mode.approvalPolicy, mode.approvalsReviewer, mode.sandboxPolicy)
        assertEquals(Triple("never", "user", mapOf("type" to "dangerFullAccess")), triple(AgentPermissionMode.FullAccess))
        assertEquals(Triple("on-request", "user", mapOf("type" to "readOnly")), triple(AgentPermissionMode.ReadOnly))
        assertEquals(Triple("on-request", "user", null), triple(AgentPermissionMode.Default))
        assertEquals(Triple("on-request", "auto_review", null), triple(AgentPermissionMode.AutoReview))
        // Default and auto-review leave the Harness sandbox in place.
        assertFalse(promptSessionArguments(text = "x", permission = AgentPermissionMode.Default).containsKey("sandboxPolicy"))
    }

    @Test
    fun `stored permission preferences round-trip, including legacy spellings`() {
        for (mode in AgentPermissionMode.entries) {
            assertEquals(mode, AgentPermissionMode.fromPreference(mode.preferenceValue))
        }
        assertEquals(AgentPermissionMode.ReadOnly, AgentPermissionMode.fromPreference(" READ_ONLY "))
        assertEquals(AgentPermissionMode.Default, AgentPermissionMode.fromPreference("agent"))
        assertEquals(AgentPermissionMode.FullAccess, AgentPermissionMode.fromPreference("agent-full-access"))
        assertNull(AgentPermissionMode.fromPreference("plan"))
        assertNull(AgentPermissionMode.fromPreference(null))
    }

    @Test
    fun `keeps model sources separate`() {
        assertEquals("remote", agentModelSourceKey(runtime = "remote", remoteEnabled = false, activeAgentId = null))
        assertEquals("remote", agentModelSourceKey(runtime = "local", remoteEnabled = true, activeAgentId = "codex-acp"))
        assertEquals("local-codex-acp", agentModelSourceKey(runtime = "local", remoteEnabled = false, activeAgentId = "codex-acp"))
        assertEquals("local-agent", agentModelSourceKey(runtime = null, remoteEnabled = false, activeAgentId = null))
    }

    @Test
    fun `request model prefers the override, else the active model of the current source`() {
        assertEquals("input-selected", selectAgentRequestModel(null, "input-selected", activeModelSourceMatches = true))
        assertEquals("DeepSeek-V4-Pro", selectAgentRequestModel(null, "DeepSeek-V4-Pro", activeModelSourceMatches = true))
        assertNull(selectAgentRequestModel(null, null, activeModelSourceMatches = true))
        // A model loaded for another source is never sent.
        assertNull(selectAgentRequestModel(null, "remote-model", activeModelSourceMatches = false))
        assertEquals("override", selectAgentRequestModel(" override ", "active", activeModelSourceMatches = false))
        assertNull(selectAgentRequestModel("  ", "active", activeModelSourceMatches = true))
    }

    @Test
    fun `turn ids keep the user row for retries and use the run id as request id`() {
        val fresh = ChatTurnIds.forSubmission(1_700_000_000_000L)
        assertEquals("1700000000000-user", fresh.userMessageId)
        assertEquals("1700000000000-ai", fresh.taskId)
        assertEquals(fresh.taskId, fresh.requestId)
        val retry = ChatTurnIds.forRetry("1700000000000-user", 1_700_000_005_000L)
        assertEquals("1700000000000-user", retry.userMessageId)
        assertEquals("1700000005000-ai", retry.taskId)
    }

    @Test
    fun `prompt text describes only the files the model reads by itself`() {
        // Forwarded attachments are described by the adapter, never here too.
        assertEquals("看看", buildUserPromptText("看看", listOf(mapOf("name" to "notes.md", "path" to "/sdcard/notes.md"))))
        assertEquals(
            "看看\n已添加到 workspace，可通过以下路径读取：\n- big.zip: /workspace/big.zip",
            buildUserPromptText(
                "看看",
                listOf(
                    mapOf("name" to "screen.png", "path" to "/tmp/screen.png", "isImage" to true),
                    mapOf("name" to "big.zip", "promptPath" to "/workspace/big.zip", "sendToModel" to false),
                ),
            ),
        )
        assertEquals(
            "已添加到 workspace，可通过以下路径读取：\n- x.bin: /sdcard/x.bin",
            buildUserPromptText("", listOf(mapOf("path" to "/sdcard/x.bin", "sendToModel" to false))),
        )
    }

    @Test
    fun `excluded attachments are referenced by path and never forwarded as content`() {
        val keep = mapOf("name" to "screen.png", "path" to "/tmp/screen.png", "isImage" to true)
        val excluded = mapOf("name" to "big.zip", "path" to "/sdcard/big.zip", "sendToModel" to false)
        val excludedAsString = mapOf("name" to "x.bin", "path" to "/sdcard/x.bin", "sendToModel" to "FALSE")
        assertEquals(listOf(keep), modelAttachments(listOf(keep, excluded, excludedAsString)))
    }
}
