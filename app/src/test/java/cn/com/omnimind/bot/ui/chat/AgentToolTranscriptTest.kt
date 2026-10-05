package cn.com.omnimind.bot.ui.chat

import cn.com.omnimind.bot.agent.projection.DartJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of `ui/test/agent_tool_transcript_test.dart`. */
class AgentToolTranscriptTest {
    private fun json(value: Any?) = DartJson.encode(value)

    @Test
    fun `old terminal card displays stored ACP exit detail without migration`() {
        val transcript = buildAgentToolTranscript(
            mapOf(
                "toolType" to "terminal",
                "status" to "error",
                "summary" to "",
                "rawResultJson" to json(
                    linkedMapOf(
                        "type" to "commandExecution",
                        "rawOutput" to linkedMapOf("formatted_output" to "", "exit_code" to 182),
                    ),
                ),
            ),
        )
        assertEquals("Command exited with code 182", transcript.outputText)
    }

    @Test
    fun `buildAgentToolTranscript renders non-terminal tool as pseudo command`() {
        val transcript = buildAgentToolTranscript(
            mapOf(
                "toolName" to "file_read",
                "displayName" to "读取文件",
                "toolType" to "workspace",
                "argsJson" to json(
                    linkedMapOf(
                        "path" to "/workspace/README.md",
                        "maxChars" to 4000,
                        "tool_title" to "查看 README",
                    ),
                ),
                "resultPreviewJson" to json(
                    linkedMapOf(
                        "path" to "/workspace/README.md",
                        "size" to 32,
                        "content" to "hello world",
                    ),
                ),
                "status" to "success",
                "summary" to "已读取文件",
            ),
        )

        assertEquals(
            "\$ file_read --path /workspace/README.md --maxChars 4000",
            transcript.promptLine,
        )
        assertTrue(transcript.outputText.contains("path: /workspace/README.md"))
        assertTrue(transcript.outputText.contains("size: 32"))
        assertTrue(transcript.outputText.contains("content: hello world"))
    }

    @Test
    fun `buildAgentToolTranscript renders terminal tool using native command`() {
        val transcript = buildAgentToolTranscript(
            mapOf(
                "toolName" to "terminal_execute",
                "displayName" to "终端执行",
                "toolType" to "terminal",
                "argsJson" to json(
                    linkedMapOf("command" to "git status", "workingDirectory" to "/workspace"),
                ),
                "terminalOutput" to "On branch main",
                "status" to "success",
                "summary" to "终端命令执行成功",
            ),
        )

        assertEquals("\$ cd /workspace && git status", transcript.promptLine)
        assertEquals("On branch main", transcript.outputText)
        assertEquals("On branch main", transcript.previewText)
    }

    @Test
    fun `buildAgentToolTranscript hides legacy Codex namespace for Claude tools`() {
        val transcript = buildAgentToolTranscript(
            mapOf(
                "agentId" to "claude-code-acp",
                "agentName" to "Claude Code",
                "toolName" to "codex.tool",
                "toolTitle" to "Read settings.json",
                "displayName" to "Read settings.json",
                "toolType" to "workspace",
                "argsJson" to json(
                    linkedMapOf("id" to "tool-call-42", "path" to "/root/.claude/settings.json"),
                ),
                "resultPreviewJson" to json(linkedMapOf("status" to "ok")),
                "status" to "success",
            ),
        )

        assertEquals("Claude Code · Read settings.json", transcript.promptLine)
        assertFalse(transcript.promptLine.contains("codex.tool"))
        assertFalse(transcript.promptLine.contains("--id"))
    }

    @Test
    fun `buildAgentToolTranscript hides generic running placeholder for terminal output area`() {
        val transcript = buildAgentToolTranscript(
            mapOf(
                "toolName" to "terminal_execute",
                "displayName" to "终端执行",
                "toolType" to "terminal",
                "argsJson" to json(
                    linkedMapOf("command" to "npm install", "workingDirectory" to "/workspace"),
                ),
                "status" to "running",
                "summary" to "正在调用内嵌 Alpine 终端执行命令",
                "progress" to "终端输出更新中",
            ),
        )

        assertEquals("\$ cd /workspace && npm install", transcript.promptLine)
        assertTrue(transcript.outputText.isEmpty())
        assertTrue(transcript.previewText.isEmpty())
    }

    @Test
    fun `tool detail preserves every persisted structured result field`() {
        val completeRecords = List(128) { index -> linkedMapOf("id" to index, "fact" to "fact-$index") }
        val transcript = buildAgentToolTranscript(
            mapOf(
                "toolName" to "memory_search",
                "toolType" to "memory",
                "resultPreviewJson" to json(linkedMapOf("records" to completeRecords.take(1))),
                "rawResultJson" to json(linkedMapOf("records" to completeRecords)),
            ),
        )

        assertTrue(transcript.outputText.contains("id: 0, fact: fact-0"))
        assertTrue(transcript.outputText.contains("id: 127, fact: fact-127"))
        assertFalse(transcript.outputText.contains("[truncated]"))
    }

    @Test
    fun `tool detail preserves a complete array result`() {
        val values = List(128) { index -> linkedMapOf("id" to index, "fact" to "fact-$index") }
        val transcript = buildAgentToolTranscript(
            mapOf(
                "toolName" to "external_list",
                "toolType" to "mcp",
                "resultPreviewJson" to json(values.take(1)),
                "rawResultJson" to json(values),
            ),
        )

        assertTrue(transcript.outputText.contains("\"fact\": \"fact-0\""))
        assertTrue(transcript.outputText.contains("\"fact\": \"fact-127\""))
        assertFalse(transcript.outputText.contains("[truncated]"))
    }
}
