package cn.com.omnimind.bot.agent.projection

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.util.Base64

/**
 * Port of `ui/test/services/agent_event_reducer_test.dart`, lines 2906-4365
 * (slice C). The shared Dart setUp/tearDown (lines 13-28) is mirrored by
 * [setUp] / [tearDown].
 */
class AgentEventReducerTestC {
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

    private fun base64Utf8(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    @Test
    @Ignore("ported with coordinator tests")
    fun `routes a session-only event to its background conversation`() {
        /*
        final coordinator = ChatConversationRuntimeCoordinator.instance;
        final first = coordinator.debugEnsureRuntimeState(
          conversationId: 8101,
          mode: kChatRuntimeModeAgent,
        );
        final second = coordinator.debugEnsureRuntimeState(
          conversationId: 8102,
          mode: kChatRuntimeModeAgent,
        );
        first.acceptsAcpEvent(
          sessionId: 'session-background-1',
          allowSessionAdmission: true,
        );
        second.acceptsAcpEvent(
          sessionId: 'session-background-2',
          allowSessionAdmission: true,
        );

        expect(
          coordinator.conversationIdForAcpEvent(sessionId: 'session-background-2'),
          8102,
        );

        coordinator.discardConversationRuntime(
          conversationId: 8101,
          mode: kChatRuntimeModeAgent,
        );
        coordinator.discardConversationRuntime(
          conversationId: 8102,
          mode: kChatRuntimeModeAgent,
        );
        */
    }

    @Test
    @Ignore("ported with coordinator tests")
    fun `interrupts every running tool in a parallel tool batch`() {
        /*
        final coordinator = ChatConversationRuntimeCoordinator.instance;
        final parallelRuntime = coordinator.debugEnsureRuntimeState(
          conversationId: 8103,
          mode: kChatRuntimeModeAgent,
        )..activeRunId = 'run-parallel-tools';
        parallelRuntime.messages.addAll([
          ChatMessageModel.cardMessage({
            'type': 'agent_tool_summary',
            'taskId': 'run-parallel-tools',
            'status': 'running',
          }, id: 'parallel-tool-1'),
          ChatMessageModel.cardMessage({
            'type': 'agent_tool_summary',
            'taskId': 'run-parallel-tools',
            'status': 'progress',
          }, id: 'parallel-tool-2'),
        ]);
        parallelRuntime.activeToolCardId = 'parallel-tool-2';

        coordinator.interruptActiveToolCard(
          conversationId: 8103,
          mode: kChatRuntimeModeAgent,
          summary: '已取消',
        );

        expect(
          parallelRuntime.messages
              .map((message) => message.cardData?['status'])
              .toList(),
          ['interrupted', 'interrupted'],
        );
        expect(parallelRuntime.activeToolCardId, isNull);
        coordinator.discardConversationRuntime(
          conversationId: 8103,
          mode: kChatRuntimeModeAgent,
        );
        */
    }

    @Test
    fun `uses sessionId and toolCallId as the canonical tool identity`() {
        fun event(status: String, title: String): MutableMap<String, Any?> = jsonMapOf(
            "message" to jsonMapOf(
                "method" to "session/update",
                "turnId" to "turn-1",
                "params" to jsonMapOf(
                    "sessionId" to "session-1",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "tool_call_update",
                        "toolCallId" to "tool-1",
                        "kind" to "execute",
                        "title" to title,
                        "status" to status,
                        "rawOutput" to jsonMapOf("type" to "text", "text" to "done"),
                    ),
                ),
            ),
        )

        reducer.reduce(runtime, event(status = "in_progress", title = "run"))
        reducer.reduce(runtime, event(status = "completed", title = "run done"))

        assertEquals(1, runtime.messages.size)
        val message = runtime.messages.single()
        val cardData = message.cardData!!
        assertEquals("session-1", cardData["sessionId"])
        assertEquals("turn-1", cardData["turnId"])
        assertEquals("tool-1", cardData["toolCallId"])
        assertEquals("session-1:tool-1", cardData["toolKey"])
        assertEquals("tool:session-1:tool-1:command", message.id)
    }

    @Test
    fun `does not regress a completed ACP tool card on a stale running update`() {
        fun event(status: String, terminalOutput: String? = null): MutableMap<String, Any?> = jsonMapOf(
            "message" to jsonMapOf(
                "method" to "session/update",
                "turnId" to "turn-terminal-ordering",
                "params" to jsonMapOf(
                    "sessionId" to "session-terminal-ordering",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "tool_call_update",
                        "toolCallId" to "terminal-ordering-1",
                        "kind" to "other",
                        "title" to "bash",
                        "status" to status,
                        "rawOutput" to jsonMapOf(
                            "toolType" to "terminal",
                            "toolName" to "bash",
                        ).apply {
                            if (terminalOutput != null) put("terminalOutput", terminalOutput)
                        },
                    ),
                ),
            ),
        )

        reducer.reduce(runtime, event(status = "completed", terminalOutput = "finished"))
        reducer.reduce(runtime, event(status = "in_progress"))

        val cardData = runtime.messages.single().cardData!!
        assertEquals("success", cardData["status"])
        assertEquals("finished", cardData["terminalOutput"])
    }

    @Test
    fun `treats ACP success and timeout tool statuses as terminal cards`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "session/update",
                    "turnId" to "turn-terminal-statuses",
                    "params" to jsonMapOf(
                        "sessionId" to "session-terminal-statuses",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "tool_call_update",
                            "toolCallId" to "tool-terminal-statuses",
                            "kind" to "execute",
                            "title" to "执行命令",
                            "status" to "success",
                            "rawOutput" to jsonMapOf("terminalOutput" to "done"),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(1, runtime.messages.size)
        assertEquals("success", runtime.messages.single().cardData?.get("status"))
        assertEquals(true, runtime.messages.single().streamMeta?.get("isFinal"))
    }

    @Test
    fun `projects structured ACP tool output into the shared terminal card`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "session/update",
                    "turnId" to "turn-tool-rich",
                    "params" to jsonMapOf(
                        "sessionId" to "session-tool-rich",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "tool_call_update",
                            "toolCallId" to "terminal-1",
                            "kind" to "other",
                            "title" to "terminal",
                            "status" to "completed",
                            "rawOutput" to jsonMapOf(
                                "toolType" to "terminal",
                                "summary" to "Command completed",
                                "resultPreview" to jsonMapOf("exitCode" to 0),
                                "terminalOutput" to "hello from the shared ACP card",
                                "terminalSessionId" to "shell-1",
                                "artifacts" to mutableListOf<Any?>(
                                    jsonMapOf(
                                        "id" to "artifact-1",
                                        "title" to "result.txt",
                                        "uri" to "file:///workspace/result.txt",
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages
            .first { it.cardData?.get("type") == "agent_tool_summary" }
            .cardData!!
        assertEquals("terminal", cardData["toolType"])
        assertEquals("Command completed", cardData["summary"])
        assertEquals("hello from the shared ACP card", cardData["terminalOutput"])
        assertEquals("shell-1", cardData["terminalSessionId"])
        assertEquals(1, (cardData["artifacts"] as List<*>).size)
        val artifact = runtime.messages.first { it.cardData?.get("type") == "artifact_card" }
        assertEquals("result.txt", (artifact.cardData?.get("artifact") as? Map<*, *>)?.get("title"))
    }

    @Test
    fun `tracks the active ACP tool for the shared stop action`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "session/update",
                    "turnId" to "turn-active-tool",
                    "params" to jsonMapOf(
                        "sessionId" to "session-active-tool",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "tool_call_update",
                            "toolCallId" to "active-tool-1",
                            "kind" to "execute",
                            "title" to "bash",
                            "status" to "in_progress",
                        ),
                    ),
                ),
            ),
        )

        assertNotNull(runtime.activeToolCardId)
        val activeCardId = runtime.activeToolCardId!!
        assertEquals(activeCardId, runtime.messages.single().id)

        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "session/update",
                    "turnId" to "turn-active-tool",
                    "params" to jsonMapOf(
                        "sessionId" to "session-active-tool",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "tool_call_update",
                            "toolCallId" to "active-tool-1",
                            "kind" to "execute",
                            "title" to "bash",
                            "status" to "completed",
                        ),
                    ),
                ),
            ),
        )

        assertNull(runtime.activeToolCardId)
    }

    @Test
    fun `keeps another parallel ACP tool active after one completes`() {
        fun toolUpdate(toolCallId: String, status: String): MutableMap<String, Any?> = jsonMapOf(
            "message" to jsonMapOf(
                "method" to "session/update",
                "turnId" to "turn-parallel-tools",
                "params" to jsonMapOf(
                    "sessionId" to "session-parallel-tools",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "tool_call_update",
                        "toolCallId" to toolCallId,
                        "kind" to "execute",
                        "title" to toolCallId,
                        "status" to status,
                    ),
                ),
            ),
        )

        reducer.reduce(runtime, toolUpdate(toolCallId = "parallel-1", status = "in_progress"))
        reducer.reduce(runtime, toolUpdate(toolCallId = "parallel-2", status = "in_progress"))
        val secondCardId = runtime.activeToolCardId
        assertNotNull(secondCardId)

        reducer.reduce(runtime, toolUpdate(toolCallId = "parallel-2", status = "completed"))

        assertNotNull(runtime.activeToolCardId)
        assertNotEquals(secondCardId, runtime.activeToolCardId)
        assertEquals(
            "running",
            runtime.messages
                .first { it.id == runtime.activeToolCardId }
                .cardData?.get("status"),
        )
    }

    private fun runExitDetail(output: String) {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "turnId" to "turn-exit-detail",
                "params" to jsonMapOf(
                    "sessionId" to "session-exit-detail",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "tool_call_update",
                        "toolCallId" to "exit-detail",
                        "kind" to "execute",
                        "title" to "printf test; exit 182",
                        "status" to "failed",
                        "rawOutput" to jsonMapOf("formatted_output" to output, "exit_code" to 182),
                    ),
                ),
            ),
        )
        val card = runtime.messages.single().cardData!!
        assertEquals("error", card["status"])
        assertEquals("Command exited with code 182", card["summary"])
        assertEquals(output, card["terminalOutput"] ?: "")
        assertEquals(1, runtime.messages.size)
    }

    @Test
    fun `preserves ACP command exit detail with output false`() = runExitDetail("")

    @Test
    fun `preserves ACP command exit detail with output true`() = runExitDetail("command stderr\n")

    private fun runExitFallback(
        status: String,
        rawOutput: MutableMap<String, Any?>,
        expectedStatus: String,
        expectedSummary: String,
    ) {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "turnId" to "turn-exit-state",
                "params" to jsonMapOf(
                    "sessionId" to "session-exit-state",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "tool_call_update",
                        "toolCallId" to "exit-state",
                        "kind" to "execute",
                        "title" to "command",
                        "status" to status,
                        "rawOutput" to rawOutput,
                    ),
                ),
            ),
        )
        val card = runtime.messages.single().cardData!!
        assertEquals(expectedStatus, card["status"])
        assertEquals(expectedSummary, card["summary"])
    }

    // Dart: `for (final sample in [...]) test('... $sample')` with 4 samples.
    @Test
    fun `command exit fallback preserves ACP state and explicit detail (in_progress, exit_code 182, running, empty)`() =
        runExitFallback("in_progress", jsonMapOf("exit_code" to 182), "running", "")

    @Test
    fun `command exit fallback preserves ACP state and explicit detail (completed, exit_code 0, success, exited 0)`() =
        runExitFallback("completed", jsonMapOf("exit_code" to 0), "success", "Command exited with code 0")

    @Test
    fun `command exit fallback preserves ACP state and explicit detail (failed, exit_code 182 + summary, error, Backend unavailable)`() =
        runExitFallback(
            "failed",
            jsonMapOf("exit_code" to 182, "summary" to "Backend unavailable"),
            "error",
            "Backend unavailable",
        )

    @Test
    fun `command exit fallback preserves ACP state and explicit detail (failed, empty, error, empty)`() =
        runExitFallback("failed", jsonMapOf(), "error", "")

    @Test
    fun `projects legacy tool-result details from ACP rawOutput`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "session/update",
                    "turnId" to "turn-legacy-tool-details",
                    "params" to jsonMapOf(
                        "sessionId" to "session-legacy-tool-details",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "tool_call_update",
                            "toolCallId" to "clarify-1",
                            "kind" to "other",
                            "title" to "clarify",
                            "status" to "completed",
                            "rawOutput" to jsonMapOf(
                                "toolType" to "clarify",
                                "result" to jsonMapOf(
                                    "question" to "还要继续吗？",
                                    "missingFields" to mutableListOf<Any?>("confirmed"),
                                ),
                                "outputTruncated" to true,
                                "originalChars" to 18000,
                                "headTail" to "head ... tail",
                                "fullOutputArtifact" to jsonMapOf("id" to "full-output-1"),
                                "subagentStatusText" to "子任务已完成",
                                "subagentEvents" to mutableListOf<Any?>(
                                    jsonMapOf("id" to "subagent-event-1", "kind" to "subagent_completed"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val card = runtime.messages.single().cardData!!
        assertEquals("还要继续吗？", card["question"])
        assertEquals(listOf("confirmed"), card["missingFields"])
        assertEquals(true, card["outputTruncated"])
        assertEquals(18000, card["originalChars"])
        assertEquals("head ... tail", card["headTail"])
        assertEquals("full-output-1", (card["fullOutputArtifact"] as? Map<*, *>)?.get("id"))
        assertEquals("子任务已完成", card["subagentStatusText"])
        assertEquals(1, (card["subagentEvents"] as List<*>).size)
    }

    @Test
    fun `preserves the ACP terminal status over raw tool output`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "turnId" to "turn-actionable-status",
                "params" to jsonMapOf(
                    "sessionId" to "session-actionable-status",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "tool_call_update",
                        "toolCallId" to "actionable-1",
                        "kind" to "execute",
                        "title" to "执行高权限操作",
                        "status" to "pending",
                        "rawOutput" to jsonMapOf("success" to false, "question" to "请确认执行高权限操作"),
                    ),
                ),
            ),
        )

        val card = runtime.messages.single().cardData!!
        assertEquals("pending", card["status"])
    }

    @Test
    fun `ACP progress output preserves original tool arguments in history`() {
        val original = mapOf("command" to "id", "timeoutSeconds" to 60)
        fun apply(update: MutableMap<String, Any?>) {
            reducer.reduce(
                runtime,
                jsonMapOf(
                    "message" to jsonMapOf(
                        "method" to "session/update",
                        "turnId" to "turn-progress-input",
                        "params" to jsonMapOf("sessionId" to "session-progress-input", "update" to update),
                    ),
                ),
            )
        }
        apply(
            jsonMapOf(
                "sessionUpdate" to "tool_call",
                "toolCallId" to "call-progress-input",
                "title" to "terminal_execute",
                "kind" to "execute",
                "status" to "in_progress",
                "rawInput" to jsonMapOf("command" to "id", "timeoutSeconds" to 60),
            ),
        )
        for (index in 0 until 3) {
            apply(
                jsonMapOf(
                    "sessionUpdate" to "tool_call_update",
                    "toolCallId" to "call-progress-input",
                    "status" to "in_progress",
                    "rawInput" to null,
                    "rawOutput" to jsonMapOf("terminalOutput" to "line-$index"),
                ),
            )
            val card = runtime.messages.single().cardData!!
            assertEquals("index $index", original, DartJson.decode(card["argsJson"] as String))
            assertEquals("line-$index", card["terminalOutput"])
        }
        apply(
            jsonMapOf(
                "sessionUpdate" to "tool_call_update",
                "toolCallId" to "call-progress-input",
                "status" to "completed",
                "rawOutput" to jsonMapOf("success" to true, "terminalOutput" to "finished"),
            ),
        )
        val restored = ChatMessage.fromJson(runtime.messages.single().toJson())
        assertEquals(original, DartJson.decode(restored.cardData!!["argsJson"] as String))
        assertEquals("success", restored.cardData!!["status"])
    }

    @Test
    fun `keeps ACP subagent progress carried in rawInput and merges children`() {
        fun update(
            status: String,
            rawInput: MutableMap<String, Any?>,
            rawOutput: MutableMap<String, Any?>? = null,
        ): MutableMap<String, Any?> = jsonMapOf(
            "message" to jsonMapOf(
                "method" to "session/update",
                "turnId" to "turn-subagent-progress",
                "params" to jsonMapOf(
                    "sessionId" to "session-subagent-progress",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "tool_call_update",
                        "toolCallId" to "subagent-dispatch-1",
                        "kind" to "other",
                        "title" to "subagent_dispatch",
                        "status" to status,
                        "rawInput" to rawInput,
                    ).apply {
                        if (rawOutput != null) put("rawOutput", rawOutput)
                    },
                ),
            ),
        )

        reducer.reduce(
            runtime,
            update(
                status = "in_progress",
                rawInput = jsonMapOf(
                    "subagentStatusText" to "正在执行子任务 1",
                    "subagentEvents" to mutableListOf<Any?>(
                        jsonMapOf(
                            "id" to "subagent-event-1",
                            "kind" to "subagent_started",
                            "summary" to "子任务 1：读取配置",
                            "status" to "running",
                            "taskIndex" to 0,
                            "subagentId" to "child-1",
                            "seq" to 1,
                        ),
                    ),
                ),
            ),
        )
        reducer.reduce(
            runtime,
            update(
                status = "in_progress",
                rawInput = jsonMapOf(
                    "subagentStatusText" to "正在执行子任务 2",
                    "subagentEvents" to mutableListOf<Any?>(
                        jsonMapOf(
                            "id" to "subagent-event-2",
                            "kind" to "subagent_started",
                            "summary" to "子任务 2：检查依赖",
                            "status" to "running",
                            "taskIndex" to 1,
                            "subagentId" to "child-2",
                            "seq" to 2,
                        ),
                    ),
                ),
            ),
        )
        reducer.reduce(
            runtime,
            update(
                status = "completed",
                rawInput = jsonMapOf(),
                rawOutput = jsonMapOf(
                    "toolType" to "context",
                    "toolName" to "subagent_dispatch",
                    "success" to true,
                    "result" to jsonMapOf("results" to mutableListOf<Any?>()),
                ),
            ),
        )

        val card = runtime.messages.single().cardData!!
        assertEquals("subagent", card["toolType"])
        assertEquals("success", card["status"])
        assertEquals("正在执行子任务 2", card["subagentStatusText"])
        val events = card["subagentEvents"] as List<*>
        assertEquals(2, events.size)
        // containsAll(<int>[0, 1]); compared as Long to tolerate Int/Long boxing.
        val taskIndexes = events.map { ((it as Map<*, *>)["taskIndex"] as? Number)?.toLong() }
        assertTrue("taskIndexes=$taskIndexes", taskIndexes.containsAll(listOf(0L, 1L)))
    }

    @Test
    fun `keeps plain ACP tool raw output in the shared card summary`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-plain-tool-output",
                    "turnId" to "turn-plain-tool-output",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "tool_call_update",
                        "toolCallId" to "plain-output-1",
                        "kind" to "other",
                        "title" to "读取结果",
                        "status" to "completed",
                        "rawOutput" to "plain ACP result",
                    ),
                ),
            ),
        )

        val card = runtime.messages.single().cardData!!
        assertEquals("plain ACP result", card["summary"])
        assertEquals("plain ACP result", card["progress"])
    }

    @Test
    fun `projects nested ACP tool results through the shared card fields`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "params" to jsonMapOf(
                    "sessionId" to "session-nested-tool-result",
                    "turnId" to "turn-nested-tool-result",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "tool_call_update",
                        "toolCallId" to "nested-tool-result-1",
                        "kind" to "other",
                        "title" to "运行任务",
                        "status" to "completed",
                        "rawOutput" to jsonMapOf(
                            "toolType" to "context",
                            "result" to jsonMapOf(
                                "toolType" to "terminal",
                                "terminalOutput" to "nested output",
                                "terminalSessionId" to "shell-nested",
                                "artifacts" to mutableListOf<Any?>(
                                    jsonMapOf(
                                        "id" to "nested-artifact",
                                        "title" to "result.txt",
                                        "uri" to "workspace://result.txt",
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val card = runtime.messages
            .first { it.cardData?.get("type") == "agent_tool_summary" }
            .cardData!!
        assertEquals("terminal", card["toolType"])
        assertEquals("nested output", card["terminalOutput"])
        assertEquals("shell-nested", card["terminalSessionId"])
        assertEquals(1, (card["artifacts"] as List<*>).size)
        assertTrue(runtime.messages.any { it.cardData?.get("type") == "artifact_card" })
    }

    @Test
    fun `routes generic ACP context results by their concrete tool capability`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "session/update",
                    "turnId" to "turn-context-file",
                    "params" to jsonMapOf(
                        "sessionId" to "session-context-file",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "tool_call",
                            "toolCallId" to "file-1",
                            "kind" to "other",
                            "title" to "write",
                            "status" to "in_progress",
                            "rawInput" to jsonMapOf("path" to "/workspace/note.md"),
                        ),
                    ),
                ),
            ),
        )
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "session/update",
                    "turnId" to "turn-context-file",
                    "params" to jsonMapOf(
                        "sessionId" to "session-context-file",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "tool_call_update",
                            "toolCallId" to "file-1",
                            "kind" to "other",
                            "title" to "write",
                            "status" to "completed",
                            "rawOutput" to jsonMapOf(
                                // ContextResult is the native result envelope. It must not
                                // erase the file card route selected by the tool itself.
                                "toolType" to "context",
                                "toolName" to "write",
                                "summary" to "Wrote note.md",
                                "result" to jsonMapOf("path" to "/workspace/note.md"),
                                "imageDataUrl" to "data:image/png;base64,AA==",
                            ),
                        ),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages
            .first { it.cardData?.get("type") == "agent_tool_summary" }
            .cardData!!
        assertEquals("file", cardData["toolType"])
        assertEquals("write", cardData["toolName"])
        assertEquals("data:image/png;base64,AA==", cardData["imageDataUrl"])
    }

    @Test
    fun `keeps every context-backed ACP capability on its existing card route`() {
        data class Route(val id: String, val toolName: String, val toolType: String)
        val routes = listOf(
            Route(id = "browser-1", toolName = "webfetch", toolType = "browser"),
            Route(id = "image-1", toolName = "image_generate", toolType = "image"),
            Route(id = "subagent-1", toolName = "subagent_run", toolType = "subagent"),
        )
        for (route in routes) {
            reducer.reduce(
                runtime,
                jsonMapOf(
                    "message" to jsonMapOf(
                        "method" to "session/update",
                        "turnId" to "turn-${route.id}",
                        "params" to jsonMapOf(
                            "sessionId" to "session-context-routes",
                            "update" to jsonMapOf(
                                "sessionUpdate" to "tool_call_update",
                                "toolCallId" to route.id,
                                "kind" to "other",
                                "title" to route.toolName,
                                "status" to "completed",
                                "rawOutput" to jsonMapOf(
                                    "toolType" to "context",
                                    "toolName" to route.toolName,
                                    "summary" to "${route.toolName} completed",
                                    "result" to jsonMapOf(),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }

        for (route in routes) {
            val cardData = runtime.messages
                .first { it.cardData?.get("toolCallId") == route.id }
                .cardData!!
            assertEquals(route.id, route.toolType, cardData["toolType"])
        }
    }

    @Test
    fun `completed ACP browser tool restores the live browser snapshot`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "session/update",
                    "turnId" to "turn-browser-snapshot",
                    "params" to jsonMapOf(
                        "sessionId" to "session-browser-snapshot",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "tool_call_update",
                            "toolCallId" to "browser-snapshot-1",
                            "kind" to "other",
                            "title" to "webfetch",
                            "status" to "completed",
                            "rawOutput" to jsonMapOf(
                                "toolType" to "context",
                                "toolName" to "webfetch",
                                "success" to true,
                                "workspaceId" to "conversation_42",
                                "result" to jsonMapOf(
                                    "currentUrl" to "https://example.com/result",
                                    "pageTitle" to "Example result",
                                    "activeTabId" to 9,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        // Dart BrowserSessionSnapshot is carried as its JSON map in Kotlin.
        assertNotNull(runtime.browserSessionSnapshot)
        assertEquals("https://example.com/result", runtime.browserSessionSnapshot!!["currentUrl"])
        assertEquals("conversation_42", runtime.browserSessionSnapshot!!["workspaceId"])
    }

    @Test
    fun `ACP schedule and alarm tools preserve their follow-up triggers`() {
        data class Tool(val id: String, val name: String, val flag: String)
        for (tool in listOf(
            Tool(id = "schedule-trigger", name = "schedule_create", flag = "showScheduleAction"),
            Tool(id = "alarm-trigger", name = "alarm_create", flag = "showAlarmAction"),
        )) {
            reducer.reduce(
                runtime,
                jsonMapOf(
                    "message" to jsonMapOf(
                        "method" to "session/update",
                        "turnId" to "turn-${tool.id}",
                        "params" to jsonMapOf(
                            "sessionId" to "session-follow-up-triggers",
                            "update" to jsonMapOf(
                                "sessionUpdate" to "tool_call_update",
                                "toolCallId" to tool.id,
                                "kind" to "other",
                                "title" to tool.name,
                                "status" to "completed",
                                "rawOutput" to jsonMapOf(
                                    "toolName" to tool.name,
                                    "success" to true,
                                    "result" to jsonMapOf(),
                                ),
                            ),
                        ),
                    ),
                ),
            )

            val cardData = runtime.messages
                .first { it.cardData?.get("toolCallId") == tool.id }
                .cardData!!
            assertEquals(tool.name, true, cardData[tool.flag])
        }
    }

    @Test
    fun `uses the ACP failed status even when tool output says timeout`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "session/update",
                    "turnId" to "turn-terminal-timeout",
                    "params" to jsonMapOf(
                        "sessionId" to "session-terminal-timeout",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "tool_call_update",
                            "toolCallId" to "terminal-timeout-1",
                            "kind" to "other",
                            "title" to "bash",
                            "status" to "failed",
                            "rawOutput" to jsonMapOf(
                                "toolType" to "terminal",
                                "toolName" to "bash",
                                "summary" to "Command timed out",
                                "success" to false,
                                "timedOut" to true,
                                "terminalOutput" to "partial output",
                            ),
                        ),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("terminal", cardData["toolType"])
        assertEquals("error", cardData["status"])
        assertEquals("partial output", cardData["terminalOutput"])
    }

    @Test
    fun `turns an ACP missing-accessibility result into an authorization card`() {
        val base = jsonMapOf(
            "message" to jsonMapOf(
                "method" to "session/update",
                "turnId" to "turn-permission",
                "params" to jsonMapOf(
                    "sessionId" to "session-permission",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "tool_call",
                        "toolCallId" to "tool-permission",
                        "kind" to "other",
                        "title" to "vlm_task",
                        "status" to "in_progress",
                        "rawInput" to jsonMapOf(),
                    ),
                ),
            ),
        )
        reducer.reduce(runtime, base)
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "session/update",
                    "turnId" to "turn-permission",
                    "params" to jsonMapOf(
                        "sessionId" to "session-permission",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "tool_call_update",
                            "toolCallId" to "tool-permission",
                            "kind" to "other",
                            "title" to "vlm_task",
                            "status" to "failed",
                            "rawOutput" to jsonMapOf(
                                "type" to "permission_section",
                                "requiredPermissionIds" to mutableListOf<Any?>("accessibility"),
                                "missing" to mutableListOf<Any?>("无障碍权限"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(1, runtime.messages.size)
        assertEquals("permission_section", runtime.messages.single().cardData?.get("type"))
        assertEquals(listOf("accessibility"), runtime.messages.single().cardData?.get("requiredPermissionIds"))
        assertEquals(true, runtime.messages.single().cardData?.get("autoOpenAuthorization"))
    }

    @Test
    fun `deduplicates ACP permission request and permission result cards`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "id" to "permission-request-1",
                    "method" to "session/request_permission",
                    "params" to jsonMapOf(
                        "turnId" to "turn-permission-dedupe",
                        "sessionId" to "session-permission-dedupe",
                        "toolCallId" to "tool-permission-dedupe",
                        "title" to "执行命令",
                        "description" to "需要确认后执行",
                    ),
                ),
            ),
        )
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "session/update",
                    "turnId" to "turn-permission-dedupe",
                    "params" to jsonMapOf(
                        "sessionId" to "session-permission-dedupe",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "tool_call_update",
                            "toolCallId" to "tool-permission-dedupe",
                            "kind" to "execute",
                            "title" to "执行命令",
                            "status" to "failed",
                            "rawOutput" to jsonMapOf(
                                "type" to "permission_section",
                                "requiredPermissionIds" to mutableListOf<Any?>("accessibility"),
                                "missing" to mutableListOf<Any?>("无障碍权限"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(1, runtime.messages.size)
        assertEquals("permission_section", runtime.messages.single().cardData?.get("type"))
    }

    @Test
    fun `deduplicates repeated committed ACP assistant blocks`() {
        fun event(): MutableMap<String, Any?> = jsonMapOf(
            "message" to jsonMapOf(
                "method" to "session/update",
                "turnId" to "turn-1",
                "params" to jsonMapOf(
                    "sessionId" to "session-1",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "messageId" to "message-1",
                        "content" to jsonMapOf("type" to "text", "text" to "来自 DSH 的完整消息"),
                    ),
                ),
            ),
        )
        // Dart reuses the same map instance twice.
        val event = event()

        reducer.reduce(runtime, event)
        reducer.reduce(runtime, event)

        assertEquals(1, runtime.messages.size)
        assertEquals("来自 DSH 的完整消息", runtime.messages.single().text)
    }

    @Test
    fun `accepts cumulative committed ACP assistant blocks without repetition`() {
        fun event(text: String): MutableMap<String, Any?> = jsonMapOf(
            "message" to jsonMapOf(
                "method" to "session/update",
                "turnId" to "turn-1",
                "params" to jsonMapOf(
                    "sessionId" to "session-1",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "messageId" to "message-1",
                        "content" to jsonMapOf("type" to "text", "text" to text),
                    ),
                ),
            ),
        )

        reducer.reduce(runtime, event("第一段"))
        reducer.reduce(runtime, event("第一段第二段"))

        assertEquals("第一段第二段", runtime.messages.single().text)
    }

    @Test
    fun `isolates ACP chunks without messageId by host turn`() {
        for (turn in listOf("turn-1", "turn-2")) {
            val result = reducer.reduce(
                runtime,
                jsonMapOf(
                    "turnId" to turn,
                    "message" to jsonMapOf(
                        "method" to "session/update",
                        "params" to jsonMapOf(
                            "sessionId" to "session-1",
                            "update" to jsonMapOf(
                                "sessionUpdate" to "agent_message_chunk",
                                "content" to jsonMapOf("type" to "text", "text" to turn),
                            ),
                        ),
                    ),
                ),
            )
            assertTrue(turn, result.handled)
            if (turn == "turn-1") {
                reducer.reducePromptResponse(
                    runtime = runtime,
                    sessionId = null,
                    turnId = turn,
                    stopReason = "end_turn",
                )
            }
        }

        assertEquals(2, runtime.messages.size)
        assertEquals(setOf("turn-1", "turn-2"), runtime.messages.map { it.text }.toSet())
    }

    @Test
    fun `isolates reused ACP message ids by host turn`() {
        fun messageEvent(turnId: String, text: String): MutableMap<String, Any?> = jsonMapOf(
            "message" to jsonMapOf(
                "method" to "session/update",
                "turnId" to turnId,
                "params" to jsonMapOf(
                    "sessionId" to "session-1",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        // DeepSeek Harness reuses this ACP messageId in later turns.
                        "messageId" to "1:1",
                        "content" to jsonMapOf("type" to "text", "text" to text),
                    ),
                ),
            ),
        )

        reducer.reduce(runtime, messageEvent(turnId = "turn-1", text = "第一轮"))
        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = null,
            turnId = "turn-1",
            stopReason = "end_turn",
        )
        reducer.reduce(runtime, messageEvent(turnId = "turn-2", text = "第二轮"))

        assertEquals(2, runtime.messages.size)
        assertEquals(setOf("第一轮", "第二轮"), runtime.messages.map { it.text }.toSet())
        assertEquals(
            setOf("turn-1-1:1-agent-message", "turn-2-1:1-agent-message"),
            runtime.messages.map { it.id }.toSet(),
        )
    }

    @Test
    fun `does not turn ACP config updates into a private event`() {
        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "session/update",
                    "params" to jsonMapOf(
                        "sessionId" to "session-1",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "config_option_update",
                            "configOptions" to mutableListOf<Any?>(),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(result.handled)
        assertTrue(runtime.messages.isEmpty())
    }

    @Test
    fun `maps command output deltas into terminal tool card`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/commandExecution/outputDelta",
                    "params" to jsonMapOf(
                        "turnId" to "turn-1",
                        "itemId" to "cmd-1",
                        "command" to "ls",
                        "delta" to "file.txt\n",
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("agent_tool_summary", cardData["type"])
        assertEquals("terminal", cardData["toolType"])
        assertEquals("file.txt\n", cardData["terminalOutput"])
    }

    @Test
    fun `maps standalone command output deltas into terminal tool card`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "command/exec/outputDelta",
                    "params" to jsonMapOf(
                        "processId" to "proc-1",
                        "stream" to "stdout",
                        "deltaBase64" to base64Utf8("hello\n"),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("agent_tool_summary", cardData["type"])
        assertEquals("terminal", cardData["toolType"])
        assertEquals("agent.commandExec", cardData["toolName"])
        assertEquals("hello\n", cardData["terminalOutput"])
        assertEquals("running", cardData["status"])
    }

    @Test
    fun `keeps a large terminal output delta intact in its tool card`() {
        val output = List(700) { index -> "line-$index ${"x".repeat(180)}" }.joinToString("\n")
        assertTrue(output.length > 128 * 1024)

        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "command/exec/outputDelta",
                    "params" to jsonMapOf(
                        "processId" to "proc-large-output",
                        "stream" to "stdout",
                        "deltaBase64" to base64Utf8(output),
                    ),
                ),
            ),
        )

        assertEquals(1, runtime.messages.size)
        assertEquals(output, runtime.messages.single().cardData?.get("terminalOutput"))
    }

    @Test
    fun `late standalone process output stays with its original run`() {
        runtime.isAiResponding = true
        runtime.currentDispatchTurnId = "turn-1"
        runtime.activeRunId = "turn-1"
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "process/outputDelta",
                    "params" to jsonMapOf("processHandle" to "process-1", "delta" to "first\n"),
                ),
            ),
        )

        runtime.currentDispatchTurnId = "turn-2"
        runtime.activeRunId = "turn-2"
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "process/outputDelta",
                    "params" to jsonMapOf("processHandle" to "process-1", "delta" to "late\n"),
                ),
            ),
        )

        assertEquals(1, runtime.messages.size)
        assertEquals("turn-1", runtime.messages.single().cardData?.get("taskId"))
        assertEquals("first\nlate\n", runtime.messages.single().cardData?.get("terminalOutput"))
    }

    @Test
    fun `maps process exit snapshots into completed terminal card`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "process/outputDelta",
                    "params" to jsonMapOf(
                        "processHandle" to "proc-2",
                        "stream" to "stderr",
                        "deltaBase64" to base64Utf8("warning\n"),
                    ),
                ),
            ),
        )
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "process/exited",
                    "params" to jsonMapOf(
                        "processHandle" to "proc-2",
                        "exitCode" to 1,
                        "stdout" to "",
                        "stderr" to "failed\n",
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("terminal", cardData["toolType"])
        assertEquals("error", cardData["status"])
        val terminalOutput = cardData["terminalOutput"] as String
        assertTrue(terminalOutput, terminalOutput.contains("[stderr]"))
        assertTrue(terminalOutput, terminalOutput.contains("warning"))
        assertTrue(terminalOutput, terminalOutput.contains("failed"))
    }

    @Test
    fun `maps raw response read file calls into workspace tool card`() {
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
                            "name" to "read_file",
                            "call_id" to "call-read-1",
                            "arguments" to DartJson.encode(jsonMapOf("path" to "README.md")),
                        ),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("agent_tool_summary", cardData["type"])
        assertEquals("workspace", cardData["toolType"])
        assertEquals("Read README.md", cardData["toolTitle"])
        assertEquals("success", cardData["status"])
        assertTrue((cardData["argsJson"] as String).contains("README.md"))
    }

    @Test
    fun `maps raw response local shell calls into terminal tool card`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "rawResponseItem/completed",
                    "params" to jsonMapOf(
                        "threadId" to "thread-1",
                        "turnId" to "turn-1",
                        "item" to jsonMapOf(
                            "type" to "local_shell_call",
                            "call_id" to "call-shell-1",
                            "status" to "completed",
                            "action" to jsonMapOf(
                                "type" to "exec",
                                "command" to mutableListOf<Any?>("ls", "-la"),
                                "working_directory" to "/workspace",
                            ),
                        ),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("terminal", cardData["toolType"])
        assertEquals("ls -la", cardData["toolTitle"])
        assertEquals("success", cardData["status"])
        assertTrue(cardData["argsJson"] as String, (cardData["argsJson"] as String).contains("ls -la"))
    }

    @Test
    fun `maps raw exec_command calls into terminal tool cards`() {
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
                            "call_id" to "call-cmd-1",
                            "arguments" to DartJson.encode(
                                jsonMapOf(
                                    "cmd" to "cd ui && flutter test test/services/agent_event_reducer_test.dart",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("terminal", cardData["toolType"])
        assertTrue(cardData["toolTitle"] as String, (cardData["toolTitle"] as String).contains("flutter test"))
        assertTrue(cardData["argsJson"] as String, (cardData["argsJson"] as String).contains("flutter test"))
    }

    @Test
    fun `classifies raw rg exec_command calls as search tool cards`() {
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
                            "call_id" to "call-search-1",
                            "arguments" to DartJson.encode(
                                jsonMapOf("cmd" to "rg -n \"rawResponseItem\" ui/lib ui/test"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("search", cardData["toolType"])
        assertEquals("rg -n \"rawResponseItem\" ui/lib ui/test", cardData["toolTitle"])
        assertEquals("success", cardData["status"])
    }

    @Test
    fun `keeps command output deltas on existing search command card`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/started",
                    "params" to jsonMapOf(
                        "turnId" to "turn-1",
                        "item" to jsonMapOf(
                            "id" to "search-cmd-1",
                            "type" to "commandExecution",
                            "command" to "rg -n \"session/update\" ui/lib",
                            "status" to "in_progress",
                        ),
                    ),
                ),
            ),
        )
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/commandExecution/outputDelta",
                    "params" to jsonMapOf(
                        "turnId" to "turn-1",
                        "itemId" to "search-cmd-1",
                        "delta" to "ui/lib/services/agent_event_reducer.dart:1\n",
                    ),
                ),
            ),
        )

        assertEquals(1, runtime.messages.size)
        val cardData = runtime.messages.single().cardData!!
        assertEquals("search", cardData["toolType"])
        assertTrue(
            cardData["terminalOutput"] as String,
            (cardData["terminalOutput"] as String).contains("agent_event_reducer.dart"),
        )
        assertEquals("running", cardData["status"])
    }
}
