package cn.com.omnimind.bot.agent.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolCallParserTest {

    @Test
    fun normalizeAgentToolStatus_mapsAcpStatusesAndFallbacks() {
        assertEquals("pending", normalizeAgentToolStatus(jsonMapOf("status" to "pending")))
        assertEquals("running", normalizeAgentToolStatus(jsonMapOf("status" to "in_progress")))
        assertEquals("success", normalizeAgentToolStatus(jsonMapOf("status" to " COMPLETED ")))
        assertEquals("error", normalizeAgentToolStatus(jsonMapOf("state" to "rejected")))
        assertEquals("interrupted", normalizeAgentToolStatus(jsonMapOf("status" to "canceled")))
        assertEquals("timeout", normalizeAgentToolStatus(jsonMapOf("status" to "TimedOut")))
        assertEquals("running", normalizeAgentToolStatus(jsonMapOf()))
        assertEquals("pending", normalizeAgentToolStatus(jsonMapOf(), fallbackStatus = "pending"))
        assertEquals("error", normalizeAgentToolStatus(jsonMapOf("exitCode" to 2)))
        assertEquals("error", normalizeAgentToolStatus(jsonMapOf("exit_code" to " 3 ")))
        assertEquals("running", normalizeAgentToolStatus(jsonMapOf("exitCode" to 0)))
        assertEquals("success", normalizeAgentToolStatus(jsonMapOf("exit_code" to "0", "success" to true)))
        assertEquals("error", normalizeAgentToolStatus(jsonMapOf("success" to false)))
        assertEquals("error", normalizeAgentToolStatus(jsonMapOf("error" to "boom")))
        // Unknown explicit status falls through to boundary fields.
        assertEquals("running", normalizeAgentToolStatus(jsonMapOf("status" to "weird", "exitCode" to 0)))
        // Opaque rawOutput never decides the lifecycle.
        assertEquals(
            "running",
            normalizeAgentToolStatus(jsonMapOf("rawOutput" to jsonMapOf("status" to "failed"))),
        )

        assertFalse(agentToolStatusIsExplicit(jsonMapOf()))
        assertFalse(agentToolStatusIsExplicit(jsonMapOf("status" to "  ")))
        assertTrue(agentToolStatusIsExplicit(jsonMapOf("success" to null)))
        assertTrue(agentToolStatusIsExplicit(jsonMapOf("state" to "done")))
    }

    @Test
    fun canonicalItemTypeAndToolName() {
        assertEquals("commandExecution", canonicalAgentItemType("command_execution"))
        assertEquals("plan", canonicalAgentItemType(" todo_list "))
        assertEquals("", canonicalAgentItemType(null))
        assertEquals("", canonicalAgentItemType("   "))
        assertEquals("custom", canonicalAgentItemType("custom"))

        assertEquals("agent.exec", canonicalAgentToolName("codex.exec"))
        assertEquals("agent/apply_patch", canonicalAgentToolName(" codex/apply_patch "))
        assertEquals("mcp.tool", canonicalAgentToolName(" mcp.tool "))
        assertNull(canonicalAgentToolName("  "))
        assertNull(canonicalAgentToolName(null))

        assertTrue(isAgentToolItemType("file_change"))
        assertTrue(isAgentToolItemType("todo_list"))
        assertFalse(isAgentToolItemType("agent_message"))
        assertTrue(isAgentToolOutputItemType("function_call_output"))
        assertFalse(isAgentToolOutputItemType("function_call"))
    }

    @Test
    fun agentToolCardSuffix_routesByItemTypeThenToolType() {
        assertEquals("command", agentToolCardSuffix("terminal"))
        assertEquals("file", agentToolCardSuffix("terminal", itemType = "file_change"))
        assertEquals("plan", agentToolCardSuffix("tool", itemType = "todo_list"))
        assertEquals("search", agentToolCardSuffix("search", itemType = "command_execution"))
        assertEquals("workspace", agentToolCardSuffix("workspace"))
        assertEquals("browser", agentToolCardSuffix("browser"))
        assertEquals("image", agentToolCardSuffix("image"))
        assertEquals("command", agentToolCardSuffix("tool", itemType = "local_shell_call"))
        assertEquals("command", agentToolCardSuffix("tool", itemType = "command_execution"))
        assertEquals("tool", agentToolCardSuffix("mcp"))
    }

    @Test
    fun normalizeAgentToolCall_terminalCommand() {
        val raw = jsonMapOf(
            "type" to "command_execution",
            "command" to listOf("bash", "-lc", "ls -la"),
            "cwd" to "/tmp",
            "status" to "completed",
            "exitCode" to 0,
            "aggregatedOutput" to "total 0\n",
        )
        val info = normalizeAgentToolCall(raw)
        assertEquals("commandExecution", info.itemType)
        assertEquals("terminal", info.toolType)
        assertEquals("agent.terminal", info.toolName)
        assertEquals("bash -lc ls -la", info.toolTitle)
        assertEquals("bash -lc ls -la", info.displayName)
        assertEquals("success", info.status)
        assertEquals(
            linkedMapOf<String, Any?>(
                "command" to "bash -lc ls -la",
                "cwd" to "/tmp",
                "cmd" to "bash -lc ls -la",
            ),
            info.arguments,
        )
        assertEquals(
            "{\n  \"command\": \"bash -lc ls -la\",\n  \"cwd\": \"/tmp\",\n  \"cmd\": \"bash -lc ls -la\"\n}",
            info.argsJson,
        )
        assertEquals("", info.resultPreviewJson)
        assertEquals(
            "{\n" +
                "  \"type\": \"command_execution\",\n" +
                "  \"command\": [\n" +
                "    \"bash\",\n" +
                "    \"-lc\",\n" +
                "    \"ls -la\"\n" +
                "  ],\n" +
                "  \"cwd\": \"/tmp\",\n" +
                "  \"status\": \"completed\",\n" +
                "  \"exitCode\": 0,\n" +
                "  \"aggregatedOutput\": \"total 0\\n\"\n" +
                "}",
            info.rawResultJson,
        )
        assertEquals("total 0\n", info.terminalOutput)
        assertEquals("Command exited with code 0", info.summary)
        assertEquals("", info.progress)
        assertNull(info.serverName)
        assertEquals("command", agentToolCardSuffix(info.toolType, itemType = info.itemType))
    }

    @Test
    fun normalizeAgentToolCall_terminalExitCodeFromStoredRawOutput() {
        val raw = jsonMapOf(
            "type" to "command_execution",
            "command" to "false",
            "rawOutput" to "{\"exit_code\": 1, \"formatted_output\": \"oops\"}",
        )
        val info = normalizeAgentToolCall(raw, fallbackStatus = "error")
        assertEquals("terminal", info.toolType)
        assertEquals("error", info.status)
        assertEquals("oops", info.terminalOutput)
        assertEquals("Command exited with code 1", info.summary)
    }

    @Test
    fun normalizeAgentToolCall_longCommandTitleIsCompacted() {
        val command = "echo " + "a".repeat(60)
        val info = normalizeAgentToolCall(jsonMapOf("type" to "command_execution", "command" to command))
        assertEquals(command.substring(0, 48) + "...", info.toolTitle)
    }

    @Test
    fun normalizeAgentToolCall_parsedReadActionRoutesToWorkspace() {
        val raw = jsonMapOf(
            "type" to "commandExecution",
            "command" to "cat README.md",
            "commandActions" to listOf(
                jsonMapOf("type" to "read", "name" to "README.md", "path" to "/r/README.md"),
            ),
        )
        val info = normalizeAgentToolCall(raw)
        assertEquals("workspace", info.toolType)
        assertEquals("Read README.md", info.toolTitle)
        assertEquals("agent.workspace", info.toolName)
        assertEquals(
            listOf(mapOf("type" to "read", "name" to "README.md", "path" to "/r/README.md")),
            info.arguments["parsedCommands"],
        )
        assertEquals("workspace", agentToolCardSuffix(info.toolType, itemType = info.itemType))
    }

    @Test
    fun normalizeAgentToolCall_fileChange() {
        val raw = jsonMapOf(
            "type" to "file_change",
            "status" to "applied",
            "changes" to listOf(jsonMapOf("path" to "/repo/src/main.kt", "kind" to "update")),
        )
        val info = normalizeAgentToolCall(raw)
        assertEquals("fileChange", info.itemType)
        assertEquals("file", info.toolType)
        assertEquals("agent.file", info.toolName)
        assertEquals("Edit main.kt", info.toolTitle)
        assertEquals("success", info.status)
        assertEquals("applied", info.summary)
        assertEquals(
            "{\n" +
                "  \"changes\": [\n" +
                "    {\n" +
                "      \"path\": \"/repo/src/main.kt\",\n" +
                "      \"kind\": \"update\"\n" +
                "    }\n" +
                "  ]\n" +
                "}",
            info.argsJson,
        )
        assertEquals("", info.terminalOutput)
        assertEquals("file", agentToolCardSuffix(info.toolType, itemType = info.itemType))
    }

    @Test
    fun normalizeAgentToolCall_searchCommand() {
        val raw = jsonMapOf(
            "type" to "command_execution",
            "command" to "rg -n foo src",
            "status" to "in_progress",
        )
        val info = normalizeAgentToolCall(raw)
        assertEquals("search", info.toolType)
        assertEquals("agent.search", info.toolName)
        assertEquals("rg -n foo src", info.toolTitle)
        assertEquals("running", info.status)
        assertEquals("", info.summary)
        assertEquals("search", agentToolCardSuffix(info.toolType, itemType = info.itemType))
    }

    @Test
    fun normalizeAgentToolCall_webSearch() {
        val raw = jsonMapOf(
            "type" to "web_search",
            "query" to "kotlin regex",
            "status" to "completed",
        )
        val info = normalizeAgentToolCall(raw)
        assertEquals("webSearch", info.itemType)
        assertEquals("search", info.toolType)
        assertEquals("agent.webSearch", info.toolName)
        assertEquals("Search: kotlin regex", info.toolTitle)
        assertEquals("completed", info.summary)
        assertEquals("{\n  \"query\": \"kotlin regex\"\n}", info.argsJson)
    }

    @Test
    fun normalizeAgentToolCall_mcpCall() {
        val raw = jsonMapOf(
            "type" to "mcp_tool_call",
            "server" to "github",
            "tool" to "create_issue",
            "arguments" to jsonMapOf("title" to "Fix crash", "body" to "Steps"),
            "status" to "completed",
            "result" to jsonMapOf(
                "content" to listOf(jsonMapOf("type" to "text", "text" to "ok")),
            ),
        )
        val info = normalizeAgentToolCall(raw)
        assertEquals("mcpToolCall", info.itemType)
        assertEquals("mcp", info.toolType)
        assertEquals("create_issue", info.toolName)
        assertEquals("Fix crash", info.toolTitle)
        assertEquals("Fix crash", info.displayName)
        assertEquals("github", info.serverName)
        assertEquals("success", info.status)
        assertEquals("completed", info.summary)
        assertEquals(
            linkedMapOf<String, Any?>(
                "title" to "Fix crash",
                "body" to "Steps",
                "tool" to "create_issue",
                "server" to "github",
            ),
            info.arguments,
        )
        assertEquals(
            "{\n" +
                "  \"content\": [\n" +
                "    {\n" +
                "      \"type\": \"text\",\n" +
                "      \"text\": \"ok\"\n" +
                "    }\n" +
                "  ]\n" +
                "}",
            info.resultPreviewJson,
        )
        assertEquals("", info.terminalOutput)
        assertEquals("tool", agentToolCardSuffix(info.toolType, itemType = info.itemType))
    }

    @Test
    fun normalizeAgentToolCall_codexFunctionCallWithJsonStringArguments() {
        val raw = jsonMapOf(
            "type" to "function_call",
            "name" to "codex.shell",
            "arguments" to "{\"command\":[\"ls\",\" -a \"]}",
        )
        val info = normalizeAgentToolCall(raw)
        assertEquals("agent.shell", info.toolName)
        assertEquals("terminal", info.toolType)
        assertEquals("ls -a", info.toolTitle)
        assertEquals(linkedMapOf<String, Any?>("command" to "ls -a"), info.arguments)
        assertEquals("running", info.status)
        assertEquals("", info.summary)
    }

    @Test
    fun normalizeAgentToolCall_streamingRawInputStringIsKeptLosslessly() {
        val partial = "{\"path\": \"/a/b.t"
        val info = normalizeAgentToolCall(
            jsonMapOf("name" to "read_file", "rawInput" to partial, "status" to "pending"),
        )
        assertEquals("", info.itemType)
        assertEquals("workspace", info.toolType)
        assertEquals("read_file", info.toolName)
        assertEquals("read_file", info.toolTitle)
        assertEquals("pending", info.status)
        assertEquals("pending", info.summary)
        assertTrue(info.arguments.isEmpty())
        assertEquals(partial, info.argsJson)

        val complete = "{\"path\":\"/a/b.txt\"}"
        val done = normalizeAgentToolCall(
            jsonMapOf("name" to "read_file", "rawInput" to complete, "status" to "completed"),
        )
        assertEquals(linkedMapOf<String, Any?>("path" to "/a/b.txt"), done.arguments)
        assertEquals("Read b.txt", done.toolTitle)
        assertEquals(complete, done.argsJson)
        assertEquals("success", done.status)
    }
}
