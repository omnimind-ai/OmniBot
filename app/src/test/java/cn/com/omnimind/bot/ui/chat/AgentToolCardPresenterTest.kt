package cn.com.omnimind.bot.ui.chat

import cn.com.omnimind.bot.agent.projection.DartJson
import cn.com.omnimind.nativeui.chat.AgentDiffLineKind
import cn.com.omnimind.nativeui.chat.AgentToolCardStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of `ui/test/agent_tool_summary_card_test.dart`. Non-widget tests are
 * ported as-is; widget tests about text, labels and style decisions are
 * translated into presenter assertions (locale zh, as in the Dart setUp).
 */
class AgentToolCardPresenterTest {
    private fun json(value: Any?) = DartJson.encode(value)
    private fun present(card: Map<String, Any?>) = presentAgentToolCard(card, english = false)

    @Test
    fun `streaming HTML input is pending, not executing or awaiting approval`() {
        val card = mapOf(
            "type" to "agent_tool_summary",
            "status" to "pending",
            "toolName" to "file_write",
            "toolTitle" to "写入文件",
            "toolType" to "file",
            "argsJson" to "{\"path\":\"/workspace/index.html\",\"content\":\"<html>",
        )
        val ui = present(card)
        assertFalse(isAgentToolAwaitingConfirmation(card))
        assertEquals(resolveAgentToolStatusLabel(card, english = false), ui.badgeLabel)
        assertEquals("准备中", ui.badgeLabel)
        assertFalse(ui.title.contains("等待确认"))
        assertFalse(ui.title.contains("正在写入"))
        assertNotEquals("成功", ui.badgeLabel)
        assertFalse(ui.isActive)
    }

    @Test
    fun `TerminalOutputUtils builds readable output from result json`() {
        val output = TerminalOutputUtils.buildDisplayOutput(
            terminalOutput = "",
            rawResultJson = json(
                linkedMapOf(
                    "liveFallbackReason" to "共享存储未就绪",
                    "stdout" to "hello",
                    "stderr" to "warning",
                ),
            ),
            resultPreviewJson = "",
        )

        assertTrue(output.contains("hello"))
        assertTrue(output.contains("[stderr]"))
        assertTrue(output.contains("warning"))
    }

    @Test
    fun `tool card prefers toolTitle when rendering compact chip`() {
        val ui = present(
            mapOf(
                "status" to "success",
                "displayName" to "终端执行",
                "toolTitle" to "检查仓库状态",
                "toolType" to "terminal",
                "summary" to "终端命令执行成功",
                "argsJson" to json(
                    linkedMapOf(
                        "command" to "ls -la",
                        "executionMode" to "termux",
                        "timeoutSeconds" to 60,
                    ),
                ),
            ),
        )

        assertEquals(AgentToolCardStyle.Capsule, ui.style)
        assertEquals("检查仓库状态", ui.title)
        assertNotEquals("终端执行", ui.title)
        assertEquals("成功", ui.badgeLabel)
    }

    @Test
    fun `pending privileged confirmation is shown as waiting`() {
        val ui = present(
            mapOf(
                "type" to "agent_tool_summary",
                "uiStyle" to "agent_tool",
                "status" to "running",
                "toolName" to "android_privileged_action",
                "toolTitle" to "安卓高级动作",
                "toolType" to "clarify",
                "question" to "高权限 shell 命令尚未执行，请确认后执行一次。",
                "missingFields" to listOf("arguments.confirmed"),
            ),
        )

        assertEquals("等待确认", ui.badgeLabel)
        assertNotEquals("执行中", ui.badgeLabel)
        assertEquals("安卓高级动作", ui.title)
        assertFalse(ui.isActive)
    }

    @Test
    fun `tool card opens detail sheet when tapped`() {
        val ui = present(
            mapOf(
                "status" to "success",
                "displayName" to "终端执行",
                "toolTitle" to "检查仓库状态",
                "toolType" to "terminal",
                "summary" to "终端命令执行成功",
                "argsJson" to json(
                    linkedMapOf("command" to "git status", "workingDirectory" to "/workspace"),
                ),
                "terminalOutput" to "On branch main",
            ),
        )

        assertEquals(AgentToolCardStyle.Capsule, ui.style)
        assertTrue(ui.opensDetail)
        assertTrue(ui.detail.promptLine.contains("git status"))
        assertEquals("On branch main", ui.detail.outputText)
        assertTrue(ui.detail.isTerminal)
        assertEquals("\$ cd /workspace && git status\nOn branch main", ui.detail.copyText)
    }

    @Test
    fun `ACP tool actions remain available in the shared detail sheet`() {
        val ui = present(
            mapOf(
                "cardId" to "tool-actions-card",
                "status" to "success",
                "toolName" to "terminal_session_start",
                "toolTitle" to "终端会话已启动",
                "toolType" to "terminal",
                "workspaceId" to "conversation_42",
                "actions" to listOf(
                    mapOf(
                        "type" to "workspace",
                        "label" to "打开项目目录",
                        "target" to "omnibot://workspace/project",
                        "payload" to mapOf("workspaceId" to "conversation_42"),
                    ),
                ),
            ),
        )

        assertEquals("tool-actions-card", ui.cardId)
        assertTrue(ui.opensDetail)
        assertEquals(1, ui.detail.actions.size)
        val action = ui.detail.actions.single()
        assertEquals("打开项目目录", action.label)
        assertEquals("workspace", action.type)
        assertEquals("omnibot://workspace/project", action.target)
        assertEquals(mapOf("workspaceId" to "conversation_42"), action.payload)
    }

    @Test
    fun `schedule tools keep their legacy follow-up action`() {
        val ui = present(
            mapOf(
                "cardId" to "schedule-action-card",
                "status" to "success",
                "toolName" to "schedule_create",
                "toolTitle" to "定时任务已创建",
                "toolType" to "schedule",
            ),
        )

        assertEquals("定时任务已创建", ui.title)
        val action = ui.detail.actions.single()
        assertEquals("查看定时任务", action.label)
        assertEquals("route", action.type)
        assertEquals("/task/scheduled_tasks", action.target)
    }

    @Test
    fun `codex tool card uses inline tool row style`() {
        val ui = present(
            mapOf(
                "type" to "agent_tool_summary",
                "status" to "success",
                "toolTitle" to "读取 README.md",
                "toolType" to "workspace",
                "summary" to "读取完成",
                "argsJson" to json(linkedMapOf("path" to "README.md")),
                "rawResultJson" to json(linkedMapOf("type" to "mcpToolCall")),
            ),
        )

        assertEquals(AgentToolCardStyle.Inline, ui.style)
        assertEquals("读取 README.md", ui.title)
        assertNotEquals("工作区", ui.badgeLabel)
        assertTrue(ui.opensDetail)
        assertNull(ui.diff)
    }

    @Test
    fun `codex tool card keeps capsule style when agent presentation is off`() {
        val ui = presentAgentToolCard(
            mapOf(
                "type" to "agent_tool_summary",
                "status" to "success",
                "toolTitle" to "读取 README.md",
                "toolType" to "workspace",
                "rawResultJson" to json(linkedMapOf("type" to "mcpToolCall")),
            ),
            english = false,
            useAgentToolPresentation = false,
        )
        assertEquals(AgentToolCardStyle.Capsule, ui.style)
    }

    @Test
    fun `running codex inline tool title uses shimmer`() {
        val ui = present(
            mapOf(
                "type" to "agent_tool_summary",
                "status" to "running",
                "toolTitle" to "Read README.md",
                "toolType" to "workspace",
                "summary" to "reading",
                "rawResultJson" to json(linkedMapOf("type" to "function_call")),
            ),
        )

        assertEquals(AgentToolCardStyle.Inline, ui.style)
        assertEquals("Read README.md", ui.title)
        assertTrue(ui.isActive)
        assertEquals("工作区", ui.badgeLabel)
    }

    @Test
    fun `running file write exposes the action and target without reasoning`() {
        val label = resolveAgentToolProgressTitle(
            mapOf(
                "status" to "running",
                "toolName" to "file_write",
                "toolType" to "file",
                "argsJson" to json(linkedMapOf("path" to "notes/draft.md")),
            ),
            isEnglish = false,
        )

        assertEquals("正在写入文件：draft.md", label)
    }

    @Test
    fun `running file write card is visible without a thinking card`() {
        val ui = present(
            mapOf(
                "type" to "agent_tool_summary",
                "status" to "running",
                "toolName" to "file_write",
                "toolType" to "file",
                "filePath" to "notes/draft.md",
                "argsJson" to json(linkedMapOf("path" to "notes/draft.md")),
            ),
        )

        assertEquals(AgentToolCardStyle.Inline, ui.style)
        assertTrue(ui.title.contains("正在写入文件"))
        assertEquals("draft.md", ui.fileName)
        assertEquals("notes/draft.md", ui.filePath)
    }

    @Test
    fun `interrupted status shows stopped state without loading spinner`() {
        val ui = present(
            mapOf(
                "status" to "interrupted",
                "displayName" to "tool",
                "toolType" to "builtin",
                "summary" to "stopped",
            ),
        )

        assertEquals("中断", ui.badgeLabel)
        assertEquals("interrupted", ui.status)
        assertFalse(ui.isActive)
    }

    @Test
    fun `timeout status shows dedicated timeout badge and icon`() {
        val ui = present(
            mapOf(
                "status" to "timeout",
                "displayName" to "终端执行",
                "toolType" to "terminal",
                "summary" to "终端命令等待超时",
            ),
        )

        assertEquals("超时", ui.badgeLabel)
        assertEquals("timeout", ui.status)
        assertFalse(ui.isActive)
    }

    @Test
    fun `tool card falls back to args tool_title when field missing`() {
        val ui = present(
            mapOf(
                "status" to "running",
                "displayName" to "读取文件",
                "toolType" to "workspace",
                "summary" to "已读取文件",
                "argsJson" to json(linkedMapOf("tool_title" to "查看配置", "path" to "README.md")),
            ),
        )

        assertEquals(AgentToolCardStyle.Capsule, ui.style)
        assertEquals("查看配置", ui.title)
        assertEquals("工作区", ui.badgeLabel)
        assertTrue(ui.isActive)
    }

    private val mainDiff = "diff --git a/lib/main.dart b/lib/main.dart\n" +
        "--- a/lib/main.dart\n" +
        "+++ b/lib/main.dart\n" +
        "@@ -1,3 +1,4 @@\n" +
        "-old line\n" +
        "+new line\n" +
        "+another line\n" +
        " same line\n"

    @Test
    fun `file diff card expands diff inline instead of opening sheet`() {
        val ui = present(
            mapOf(
                "status" to "success",
                "displayName" to "文件修改",
                "toolTitle" to "更新 main.dart",
                "toolType" to "file",
                "summary" to "1 个文件 · +2 -1",
                "changedFiles" to 1,
                "additions" to 2,
                "deletions" to 1,
                "filePath" to "lib/main.dart",
                "diffText" to mainDiff,
            ),
        )

        assertEquals(AgentToolCardStyle.Inline, ui.style)
        assertEquals("更新 main.dart", ui.title)
        assertEquals("main.dart", ui.fileName)
        assertEquals("+2 -1", ui.diffStat?.label)
        assertFalse(ui.opensDetail)
        val diff = assertNotNullAndGet(ui.diff)
        assertEquals("+2 -1", diff.statLabel)
        val lines = diff.files.single().lines
        assertTrue(lines.any { it.kind == AgentDiffLineKind.Deletion && it.prefix + it.content == "-old line" })
        assertTrue(lines.any { it.kind == AgentDiffLineKind.Addition && it.prefix + it.content == "+new line" })
        assertTrue(lines.any { it.kind == AgentDiffLineKind.Context && it.content == "same line" })
        // The detail sheet shows the same diff and copies the raw diff text.
        assertNotNull(ui.detail.diff)
        assertTrue(ui.detail.copyText.endsWith(mainDiff.trimEnd()))
    }

    @Test
    fun `file diff title filename tap shows full path tooltip`() {
        val ui = present(
            mapOf(
                "status" to "success",
                "displayName" to "文件修改",
                "toolTitle" to "更新 main.dart",
                "toolType" to "file",
                "filePath" to "lib/main.dart",
                "diffText" to "diff --git a/lib/main.dart b/lib/main.dart\n" +
                    "--- a/lib/main.dart\n" +
                    "+++ b/lib/main.dart\n" +
                    "@@ -1,2 +1,2 @@\n" +
                    "-old line\n" +
                    "+new line\n",
            ),
        )

        assertEquals("main.dart", ui.fileName)
        assertEquals("lib/main.dart", ui.filePath)
        assertEquals("+1 -1", ui.diffStat?.label)
    }

    @Test
    fun `english labels are localized`() {
        val ui = presentAgentToolCard(
            mapOf("status" to "timeout", "displayName" to "终端执行", "toolType" to "terminal"),
            english = true,
        )
        assertEquals("Timeout", resolveAgentToolStatusLabel(mapOf("status" to "timeout"), english = true))
        assertEquals("Timeout", ui.badgeLabel)
    }

    private fun <T : Any> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
