package cn.com.omnimind.bot.agent.projection

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Port of `ui/test/services/agent_event_reducer_test.dart` lines 4366-5820.
 */
class AgentEventReducerTestD {
    private lateinit var reducer: AgentEventReducer
    private lateinit var runtime: ChatConversationRuntimeState

    @Before
    fun setUp() {
        reducer = AgentEventReducer()
        runtime = ChatConversationRuntimeState(conversationId = 42, mode = CHAT_RUNTIME_MODE_AGENT)
    }

    @After
    fun tearDown() {
        runtime.dispose()
    }

    private fun assertContains(haystack: Any?, needle: String) {
        assertTrue("expected <$haystack> to contain <$needle>", haystack is String && haystack.contains(needle))
    }

    @Test
    fun `raw function outputs complete and enrich existing tool cards`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "rawResponseItem/completed",
                    "params" to jsonMapOf(
                        "threadId" to "thread-1",
                        "turnId" to "turn-1",
                        "item" to jsonMapOf(
                            "type" to "function_call",
                            "name" to "exec_command",
                            "call_id" to "call-cmd-output-1",
                            "arguments" to DartJson.encode(jsonMapOf("cmd" to "flutter test")),
                        ),
                    ),
                ),
            ),
        )
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "rawResponseItem/completed",
                    "params" to jsonMapOf(
                        "threadId" to "thread-1",
                        "turnId" to "turn-1",
                        "item" to jsonMapOf(
                            "type" to "function_call_output",
                            "call_id" to "call-cmd-output-1",
                            "output" to "00:01 +1: All tests passed!\n",
                        ),
                    ),
                ),
            ),
        )

        assertEquals(1, runtime.messages.size)
        val cardData = runtime.messages.single().cardData!!
        assertEquals("terminal", cardData["toolType"])
        assertEquals("flutter test", cardData["toolTitle"])
        assertContains(cardData["terminalOutput"], "All tests passed")
        assertEquals("success", cardData["status"])
    }

    @Test
    fun `raw output-only items still produce visible tool cards`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "rawResponseItem/completed",
                    "params" to jsonMapOf(
                        "threadId" to "thread-1",
                        "turnId" to "turn-1",
                        "item" to jsonMapOf(
                            "type" to "function_call_output",
                            "call_id" to "call-output-only-1",
                            "output" to "README.md contents",
                        ),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("agent_tool_summary", cardData["type"])
        assertEquals("tool", cardData["toolType"])
        assertContains(cardData["summary"], "README.md contents")
        assertContains(cardData["rawResultJson"], "function_call_output")
    }

    @Test
    fun `raw response items without ids use stable distinct fallback ids`() {
        for (query in listOf("first query", "second query")) {
            reducer.reduce(
                runtime,
                jsonMapOf(
                    "message" to jsonMapOf(
                        "method" to "rawResponseItem/completed",
                        "params" to jsonMapOf(
                            "threadId" to "thread-1",
                            "turnId" to "turn-1",
                            "item" to jsonMapOf(
                                "type" to "web_search_call",
                                "status" to "completed",
                                "action" to jsonMapOf("type" to "search", "query" to query),
                            ),
                        ),
                    ),
                ),
            )
        }

        assertEquals(2, runtime.messages.size)
        assertEquals(2, runtime.messages.map { it.id }.toSet().size)
        val titles = runtime.messages.map { it.cardData?.get("toolTitle") }
        assertTrue(titles.toString(), titles.containsAll(listOf("Search: first query", "Search: second query")))
    }

    @Test
    fun `projects ACP assistant text resources into the shared reply`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-assistant-text-resource",
                    "turnId" to "turn-assistant-text-resource",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "messageId" to "assistant-text-resource",
                        "content" to jsonMapOf(
                            "type" to "resource",
                            "resource" to jsonMapOf(
                                "uri" to "workspace://notes.txt",
                                "mimeType" to "text/plain",
                                "text" to "资源中的正文",
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals("资源中的正文", runtime.messages.single().text)
    }

    @Test
    fun `projects ACP assistant resource links into artifact cards`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-assistant-link",
                    "turnId" to "turn-assistant-link",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "messageId" to "assistant-link",
                        "content" to jsonMapOf(
                            "type" to "resource_link",
                            "name" to "result.json",
                            "uri" to "omnibot://workspace/result.json",
                            "mimeType" to "application/json",
                        ),
                    ),
                ),
            ),
        )

        val artifact = runtime.messages.single { it.cardData?.get("type") == "artifact_card" }
        assertEquals(
            "omnibot://workspace/result.json",
            (artifact.cardData?.get("artifact") as Map<*, *>)["uri"],
        )
    }

    @Test
    fun `maps file diffs into first-class diff tool cards`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/fileChange/outputDelta",
                    "params" to jsonMapOf(
                        "turnId" to "turn-1",
                        "itemId" to "file-1",
                        "path" to "lib/main.dart",
                        "delta" to "diff --git a/lib/main.dart b/lib/main.dart\n" +
                            "--- a/lib/main.dart\n" +
                            "+++ b/lib/main.dart\n" +
                            "@@ -1,2 +1,2 @@\n" +
                            "-old line\n" +
                            "+new line\n" +
                            " same line\n",
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("agent_tool_summary", cardData["type"])
        assertEquals("file", cardData["toolType"])
        assertEquals(true, cardData["showDiff"])
        assertEquals("lib/main.dart", cardData["filePath"])
        assertEquals(1, cardData["additions"])
        assertEquals(1, cardData["deletions"])
        assertContains(cardData["summary"], "+1 -1")
        assertContains((cardData["diffText"] ?: "").toString(), "diff --git")
    }

    @Test
    fun `maps standard ACP diff content into a shared file card without a kind`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-standard-diff",
                    "turnId" to "turn-standard-diff",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "tool_call",
                        "toolCallId" to "call-standard-diff",
                        "title" to "更新 main.dart",
                        "status" to "completed",
                        "content" to listOf(
                            jsonMapOf(
                                "type" to "diff",
                                "path" to "lib/main.dart",
                                "oldText" to "old line\n",
                                "newText" to "new line\n",
                            ),
                        ),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("agent_tool_summary", cardData["type"])
        assertEquals("file", cardData["toolType"])
        assertEquals(true, cardData["showDiff"])
        assertEquals("lib/main.dart", cardData["filePath"])
        assertEquals(1, cardData["additions"])
        assertEquals(1, cardData["deletions"])
    }

    @Test
    fun `ACP sparse completion updates keep one file card and preserve its diff`() {
        val callId = "call-file-1"
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/started",
                    "params" to jsonMapOf(
                        "turnId" to "turn-1",
                        "item" to jsonMapOf(
                            "id" to callId,
                            "type" to "fileChange",
                            "title" to "Write",
                            "status" to "pending",
                            "content" to mutableListOf<Any?>(),
                            "rawInput" to DartJson.encode(
                                jsonMapOf(
                                    "file_path" to "/workspace/edit-demo.txt",
                                    "content" to "old line\n",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val initialSequence = runtime.messages.single().streamMeta?.get("seq")
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/updated",
                    "params" to jsonMapOf(
                        "turnId" to "turn-1",
                        "item" to jsonMapOf(
                            "id" to callId,
                            "type" to "fileChange",
                            "title" to "Write edit-demo.txt",
                            "status" to null,
                            "content" to listOf(
                                jsonMapOf(
                                    "type" to "diff",
                                    "path" to "/workspace/edit-demo.txt",
                                    "oldText" to "old line\n",
                                    "newText" to "new line\n",
                                ),
                            ),
                            "rawInput" to DartJson.encode(
                                jsonMapOf(
                                    "file_path" to "/workspace/edit-demo.txt",
                                    "content" to "new line\n",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/completed",
                    "params" to jsonMapOf(
                        "turnId" to "turn-1",
                        "item" to jsonMapOf(
                            "id" to callId,
                            "type" to "tool",
                            "title" to null,
                            "status" to "completed",
                            "content" to null,
                            "rawInput" to null,
                            "rawOutput" to "\"File created successfully\"",
                        ),
                    ),
                ),
            ),
        )

        assertEquals(1, runtime.messages.size)
        assertEquals("$callId-agent-file", runtime.messages.single().id)
        val cardData = runtime.messages.single().cardData!!
        assertEquals("file", cardData["toolType"])
        assertEquals("Write edit-demo.txt", cardData["toolTitle"])
        assertEquals("success", cardData["status"])
        assertEquals(true, cardData["showDiff"])
        assertEquals("/workspace/edit-demo.txt", cardData["filePath"])
        assertEquals("1 file · +1 -1", cardData["summary"])
        assertContains(cardData["argsJson"], "/workspace/edit-demo.txt")
        assertContains(cardData["rawResultJson"], "File created successfully")
        assertEquals(initialSequence, runtime.messages.single().streamMeta?.get("seq"))
    }

    @Test
    fun `ACP command cards prefer raw input description over generic labels`() {
        val callId = "call-command-1"
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/started",
                    "params" to jsonMapOf(
                        "turnId" to "turn-1",
                        "item" to jsonMapOf(
                            "id" to callId,
                            "type" to "commandExecution",
                            "title" to "Terminal",
                            "status" to "pending",
                            "rawInput" to "{}",
                        ),
                    ),
                ),
            ),
        )
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/completed",
                    "params" to jsonMapOf(
                        "turnId" to "turn-1",
                        "item" to jsonMapOf(
                            "id" to callId,
                            "type" to "commandExecution",
                            "title" to "ls -la /workspace",
                            "status" to "completed",
                            "rawInput" to DartJson.encode(
                                jsonMapOf(
                                    "command" to "ls -la /workspace",
                                    "description" to "Inspect the workspace contents",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(1, runtime.messages.size)
        val cardData = runtime.messages.single().cardData!!
        assertEquals("Inspect the workspace contents", cardData["toolTitle"])
        assertNotEquals("Agent command", cardData["toolTitle"])
        assertEquals("success", cardData["status"])
        assertContains(cardData["argsJson"], "ls -la /workspace")
    }

    @Test
    fun `maps hunk-only changes json into first-class diff tool cards`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/fileChange/outputDelta",
                    "params" to jsonMapOf(
                        "turnId" to "turn-1",
                        "itemId" to "call-1",
                        "type" to "fileChange",
                        "id" to "call-1",
                        "changes" to DartJson.encode(
                            jsonMapOf(
                                "path" to "/repo/test/services/agent_diff_parser_test.dart",
                                "kind" to jsonMapOf("type" to "update", "move_path" to null),
                                "diff" to "@@ -1,2 +1,2 @@\n-old line\n+new line\n same line\n",
                            ),
                        ),
                        "status" to "completed",
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("agent_tool_summary", cardData["type"])
        assertEquals("file", cardData["toolType"])
        assertEquals("Edit agent_diff_parser_test.dart", cardData["toolTitle"])
        assertEquals(true, cardData["showDiff"])
        assertEquals("/repo/test/services/agent_diff_parser_test.dart", cardData["filePath"])
        assertEquals(1, cardData["changedFiles"])
        assertEquals(1, cardData["additions"])
        assertEquals(1, cardData["deletions"])
        assertEquals("1 file · +1 -1", cardData["summary"])
        assertContains((cardData["diffText"] ?: "").toString(), "diff --git")
    }

    // Remote-snapshot mapper cases live in the Flutter adapter suite
    // (ui/test/features/home/pages/chat/remote_codex_snapshot_mapper_test.dart).

    @Test
    fun `uses file paths for concise file change tool titles`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/started",
                    "params" to jsonMapOf(
                        "turnId" to "turn-1",
                        "item" to jsonMapOf(
                            "id" to "file-1",
                            "type" to "fileChange",
                            "path" to "/repo/lib/main.dart",
                        ),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("Edit main.dart", cardData["toolTitle"])
    }

    @Test
    fun `uses generic tool params for concise tool titles`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/started",
                    "params" to jsonMapOf(
                        "turnId" to "turn-1",
                        "item" to jsonMapOf(
                            "id" to "tool-1",
                            "type" to "tool",
                            "toolName" to "mcp__context7__query_docs",
                            "arguments" to "{\"query\":\"Riverpod provider override\"}",
                        ),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("query_docs: Riverpod provider override", cardData["toolTitle"])
    }

    @Test
    fun `maps mcp read file calls into workspace tool cards`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/started",
                    "params" to jsonMapOf(
                        "turnId" to "turn-1",
                        "item" to jsonMapOf(
                            "id" to "tool-1",
                            "type" to "mcpToolCall",
                            "tool" to "mcp__filesystem__read_file",
                            "arguments" to "{\"path\":\"README.md\"}",
                        ),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("agent_tool_summary", cardData["type"])
        assertEquals("workspace", cardData["toolType"])
        assertEquals("Read README.md", cardData["toolTitle"])
        assertContains(cardData["argsJson"], "README.md")
    }

    @Test
    fun `maps sdk command_execution events without method into terminal cards`() {
        val started = reducer.reduce(
            runtime,
            jsonMapOf(
                "type" to "item.started",
                "thread_id" to "thread-1",
                "turn_id" to "turn-1",
                "item" to jsonMapOf(
                    "id" to "cmd-1",
                    "type" to "command_execution",
                    "command" to "cd ui && flutter test",
                    "aggregated_output" to "",
                    "status" to "in_progress",
                ),
            ),
        )

        assertTrue(started.handled)
        var cardData = runtime.messages.single().cardData!!
        assertEquals("agent_tool_summary", cardData["type"])
        assertEquals("terminal", cardData["toolType"])
        assertEquals("cd ui && flutter test", cardData["toolTitle"])
        assertEquals("running", cardData["status"])

        reducer.reduce(
            runtime,
            jsonMapOf(
                "type" to "item.completed",
                "thread_id" to "thread-1",
                "turn_id" to "turn-1",
                "item" to jsonMapOf(
                    "id" to "cmd-1",
                    "type" to "command_execution",
                    "command" to "cd ui && flutter test",
                    "aggregated_output" to "00:01 +1: All tests passed!\n",
                    "exit_code" to 0,
                    "status" to "completed",
                ),
            ),
        )

        cardData = runtime.messages.single().cardData!!
        assertEquals("terminal", cardData["toolType"])
        assertEquals("success", cardData["status"])
        assertContains(cardData["terminalOutput"], "All tests passed")
    }

    @Test
    fun `maps sdk mcp_tool_call read events without method into workspace cards`() {
        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "type" to "item.completed",
                "thread_id" to "thread-1",
                "turn_id" to "turn-1",
                "item" to jsonMapOf(
                    "id" to "read-1",
                    "type" to "mcp_tool_call",
                    "server" to "filesystem",
                    "tool" to "read_file",
                    "arguments" to jsonMapOf("path" to "README.md"),
                    "status" to "completed",
                ),
            ),
        )

        assertTrue(result.handled)
        val cardData = runtime.messages.single().cardData!!
        assertEquals("agent_tool_summary", cardData["type"])
        assertEquals("workspace", cardData["toolType"])
        assertEquals("Read README.md", cardData["toolTitle"])
        assertContains(cardData["argsJson"], "README.md")
    }

    @Test
    fun `completed command snapshots update terminal output and status`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/started",
                    "params" to jsonMapOf(
                        "turnId" to "turn-1",
                        "item" to jsonMapOf(
                            "id" to "cmd-1",
                            "type" to "commandExecution",
                            "command" to "npm test",
                        ),
                    ),
                ),
            ),
        )

        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/completed",
                    "params" to jsonMapOf(
                        "turnId" to "turn-1",
                        "itemId" to "cmd-1",
                        "item" to jsonMapOf(
                            "id" to "cmd-1",
                            "type" to "commandExecution",
                            "command" to "npm test",
                            "aggregatedOutput" to "test failed\n",
                            "exitCode" to 1,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(1, runtime.messages.size)
        val cardData = runtime.messages.single().cardData!!
        assertEquals("terminal", cardData["toolType"])
        assertEquals("error", cardData["status"])
        assertEquals("test failed\n", cardData["terminalOutput"])
    }

    @Test
    fun `patch updated events keep file diff cards current`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/fileChange/patchUpdated",
                    "params" to jsonMapOf(
                        "turnId" to "turn-1",
                        "itemId" to "file-1",
                        "changes" to DartJson.encode(
                            jsonMapOf(
                                "path" to "/repo/lib/app.dart",
                                "kind" to jsonMapOf("type" to "update"),
                                "diff" to "@@ -1 +1 @@\n-old\n+new\n",
                            ),
                        ),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("file", cardData["toolType"])
        assertEquals(true, cardData["showDiff"])
        assertEquals("/repo/lib/app.dart", cardData["filePath"])
        assertEquals("1 file · +1 -1", cardData["summary"])
    }

    @Test
    fun `keeps agent message entries separate by codex item id`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/agentMessage/delta",
                    "params" to jsonMapOf("turnId" to "turn-1", "itemId" to "msg-1", "delta" to "first"),
                ),
            ),
        )

        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/agentMessage/delta",
                    "params" to jsonMapOf("turnId" to "turn-1", "itemId" to "msg-2", "delta" to "second"),
                ),
            ),
        )

        assertEquals(
            listOf("msg-2-agent-message", "msg-1-agent-message"),
            runtime.messages.map { it.id },
        )
        assertEquals("turn-1", runtime.messages.first().streamMeta?.get("parentTaskId"))
        assertEquals("msg-2-agent-message", runtime.messages.first().streamMeta?.get("entryId"))
        assertEquals(2, runtime.messages.first().streamMeta?.get("seq"))
        assertEquals(1, runtime.messages.last().streamMeta?.get("seq"))
    }

    @Test
    fun `thread active status cannot start a prompt`() {
        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "thread/status/changed",
                    "params" to jsonMapOf(
                        "threadId" to "thread-1",
                        "status" to jsonMapOf("type" to "active", "activeFlags" to mutableListOf<Any?>()),
                    ),
                ),
            ),
        )

        assertFalse(result.handled)
        assertFalse(runtime.isAiResponding)
    }

    @Test
    fun `marks upstream turn started notification as processing`() {
        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "turn/started",
                "params" to jsonMapOf(
                    "threadId" to "thread-1",
                    "turn" to jsonMapOf("id" to "turn-1", "status" to "inProgress"),
                ),
            ),
        )

        assertTrue(result.handled)
        assertEquals("thread-1", result.threadId)
        assertEquals("turn-1", result.turnId)
        assertTrue(runtime.isAiResponding)
        assertEquals("turn-1", runtime.currentDispatchTurnId)
    }

    @Test
    fun `ignores legacy thread status payloads`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "thread/status/changed",
                    "params" to jsonMapOf(
                        "threadId" to "thread-1",
                        "status" to jsonMapOf("type" to "active"),
                    ),
                ),
            ),
        )

        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "thread/status/changed",
                    "params" to jsonMapOf(
                        "threadId" to "thread-1",
                        "status" to jsonMapOf("type" to "idle"),
                    ),
                ),
            ),
        )

        assertFalse(result.handled)
        assertFalse(runtime.isAiResponding)
    }

    @Test
    fun `thread idle leaves reasoning active until the prompt response`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "turn/started",
                    "params" to jsonMapOf("threadId" to "thread-1", "turnId" to "turn-1"),
                ),
            ),
        )
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/reasoning/textDelta",
                    "params" to jsonMapOf(
                        "threadId" to "thread-1",
                        "turnId" to "turn-1",
                        "itemId" to "reason-1",
                        "delta" to "thinking",
                    ),
                ),
            ),
        )

        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "thread/status/changed",
                    "params" to jsonMapOf(
                        "threadId" to "thread-1",
                        "status" to jsonMapOf("type" to "idle"),
                    ),
                ),
            ),
        )

        assertTrue(runtime.isAiResponding)
        assertEquals("turn-1", runtime.currentDispatchTurnId)
        assertFalse(runtime.messages.any { it.id.endsWith("cancelled") })
        assertEquals(true, runtime.messages.single().cardData!!["isLoading"])
        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = null,
            turnId = "turn-1",
            stopReason = "end_turn",
        )
        assertFalse(runtime.isAiResponding)
        assertNull(runtime.currentDispatchTurnId)
        assertEquals(false, runtime.messages.single().cardData!!["isLoading"])
    }

    private fun hydratedHello() = ChatMessage(
        id = "msg-1-agent-message",
        type = 1,
        user = 2,
        content = jsonMapOf("text" to "Hello", "id" to "msg-1-agent-message"),
    )

    private fun agentDelta(delta: String) = jsonMapOf(
        "message" to jsonMapOf(
            "method" to "item/agentMessage/delta",
            "params" to jsonMapOf("turnId" to "turn-1", "itemId" to "msg-1", "delta" to delta),
        ),
    )

    @Test
    fun `ignores replayed assistant deltas after snapshot hydration`() {
        runtime.messages.add(hydratedHello())

        for (delta in listOf("Hel", "lo", "!")) {
            reducer.reduce(runtime, agentDelta(delta))
        }

        assertEquals("Hello!", runtime.messages.single().text)
    }

    @Test
    fun `replayed assistant deltas do not restart an idle turn`() {
        runtime.messages.add(hydratedHello())

        for (delta in listOf("Hel", "lo")) {
            reducer.reduce(runtime, agentDelta(delta))
        }

        assertEquals("Hello", runtime.messages.single().text)
        assertFalse(runtime.isAiResponding)
        assertNull(runtime.currentDispatchTurnId)
        assertTrue(runtime.currentAiMessages.isEmpty())
    }

    @Test
    fun `idle status does not clear partial replay delta offsets`() {
        runtime.messages.add(hydratedHello())

        reducer.reduce(runtime, agentDelta("Hel"))
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "thread/status/changed",
                    "params" to jsonMapOf(
                        "threadId" to "thread-1",
                        "status" to jsonMapOf("type" to "idle"),
                    ),
                ),
            ),
        )
        reducer.reduce(runtime, agentDelta("lo"))

        assertEquals("Hello", runtime.messages.single().text)
        assertFalse(runtime.isAiResponding)
        assertNull(runtime.currentDispatchTurnId)
    }

    // Ported to ChatRuntimeRoutingTest (needs the coordinator).

    @Test
    fun `finalizes assistant item without duplicating completed text`() {
        reducer.reduce(runtime, agentDelta("Hel"))

        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/completed",
                    "params" to jsonMapOf(
                        "turnId" to "turn-1",
                        "item" to jsonMapOf("id" to "msg-1", "type" to "agentMessage", "text" to "Hello"),
                    ),
                ),
            ),
        )

        assertEquals("Hello", runtime.messages.single().text)
        assertEquals(true, runtime.messages.single().streamMeta?.get("isFinal"))
        assertTrue(runtime.currentAiMessages.isEmpty())
    }

    @Test
    fun `keeps reasoning timer stable across deltas and completion`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/started",
                    "params" to jsonMapOf(
                        "turnId" to "turn-1",
                        "item" to jsonMapOf("id" to "reason-1", "type" to "reasoning"),
                    ),
                ),
            ),
        )

        // A lifecycle-only reasoning start must not create a blank card. The
        // first real delta creates the card and starts its timer.
        assertTrue(runtime.messages.isEmpty())

        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/reasoning/textDelta",
                    "params" to jsonMapOf(
                        "turnId" to "turn-1",
                        "itemId" to "reason-1",
                        "delta" to "thinking",
                    ),
                ),
            ),
        )

        assertEquals("reason-1-agent-thinking", runtime.messages.single().id)
        val startedStartTime = runtime.messages.single().cardData!!["startTime"]
        // Dart `isA<int>()`: Dart int is 64-bit, so Int or Long both qualify.
        assertTrue("startTime=$startedStartTime", startedStartTime is Int || startedStartTime is Long)
        assertEquals("thinking", runtime.messages.single().cardData!!["thinkingContent"])

        // item/completed for reasoning no longer flips the card to complete — the
        // turn may still emit more reasoning, tool calls, or an agent message.
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/completed",
                    "params" to jsonMapOf(
                        "turnId" to "turn-1",
                        "item" to jsonMapOf("id" to "reason-1", "type" to "reasoning"),
                    ),
                ),
            ),
        )
        val midTurnCard = runtime.messages.single().cardData!!
        assertEquals(startedStartTime, midTurnCard["startTime"])
        assertEquals(true, midTurnCard["isLoading"])
        assertEquals(ThinkingStage.THINKING, midTurnCard["stage"])

        // turn/completed is the terminal signal that finalizes the thinking card.
        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = null,
            turnId = "turn-1",
            stopReason = "end_turn",
        )

        val completedCard = runtime.messages
            .first { it.cardData?.get("type") == "deep_thinking" }
            .cardData!!
        assertEquals(startedStartTime, completedCard["startTime"])
        assertEquals(ThinkingStage.COMPLETE, completedCard["stage"])
        assertEquals(false, completedCard["isLoading"])
        assertNotNull(completedCard["endTime"])
    }
}
