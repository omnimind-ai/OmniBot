package cn.com.omnimind.bot.agent.projection

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * Port of ui/test/services/agent_event_reducer_test.dart, lines 5821-7184
 * (end of file).
 */
class AgentEventReducerTestE {
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

    @Test
    fun `normal turn completion never creates a cancellation body`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "turn/started",
                    "params" to jsonMapOf("turnId" to "turn-1"),
                ),
            ),
        )
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

        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = null,
            turnId = "turn-1",
            stopReason = "end_turn",
        )

        assertFalse(runtime.messages.any { it.id == "turn-1-cancelled" })

        val thinkingCard = runtime.messages
            .first { it.cardData?.get("type") == "deep_thinking" }
            .cardData!!
        assertEquals(false, thinkingCard["isLoading"])
        assertEquals(ThinkingStage.COMPLETE, thinkingCard["stage"])
        assertNotNull(thinkingCard["endTime"])
    }

    @Test
    fun `updates tool cards in place with stable codex stream metadata`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/commandExecution/outputDelta",
                    "params" to jsonMapOf(
                        "turnId" to "turn-1",
                        "itemId" to "cmd-1",
                        "command" to "ls",
                        "delta" to "a\n",
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
                        "itemId" to "cmd-1",
                        "command" to "ls",
                        "delta" to "b\n",
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("a\nb\n", cardData["terminalOutput"])
        assertEquals("cmd-1-agent-command", runtime.messages.single().streamMeta?.get("entryId"))
        assertEquals(1, runtime.messages.single().streamMeta?.get("seq"))
    }

    @Test
    fun `reused provider tool ids cannot rewrite a previous turn card`() {
        fun reduceToolTurn(turnId: String) {
            reducer.reduce(
                runtime,
                jsonMapOf(
                    "message" to jsonMapOf(
                        "method" to "item/started",
                        "params" to jsonMapOf(
                            "turnId" to turnId,
                            "item" to jsonMapOf(
                                "id" to "call-1",
                                "type" to "commandExecution",
                                "command" to "echo $turnId",
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
                            "turnId" to turnId,
                            "item" to jsonMapOf(
                                "id" to "call-1",
                                "type" to "commandExecution",
                                "command" to "echo $turnId",
                                "status" to "completed",
                            ),
                        ),
                    ),
                ),
            )
            reducer.reducePromptResponse(
                runtime = runtime,
                sessionId = null,
                turnId = turnId,
                stopReason = "end_turn",
            )
        }

        reduceToolTurn("turn-1")
        reduceToolTurn("turn-2")

        assertEquals(2, runtime.messages.size)
        assertEquals(
            setOf("call-1-agent-command", "turn-2-call-1-agent-command"),
            runtime.messages.map { it.id }.toSet(),
        )
        val first = runtime.messages.first { it.id == "call-1-agent-command" }
        val second = runtime.messages.first { it.id == "turn-2-call-1-agent-command" }
        assertEquals("turn-1", first.cardData?.get("taskId"))
        assertEquals("turn-2", second.cardData?.get("taskId"))
    }

    @Test
    fun `maps approval requests into codex request card`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "id" to 7,
                    "method" to "item/commandExecution/requestApproval",
                    "params" to jsonMapOf("command" to "rm tmp.txt", "reason" to "cleanup"),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("agent_request", cardData["type"])
        assertEquals("approval", cardData["requestKind"])
        assertEquals(7, cardData["requestId"])
    }

    @Test
    fun `renders standard ACP permission payload as human-readable content`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "id" to "permission-1",
                    "method" to "session/request_permission",
                    "params" to jsonMapOf(
                        "sessionId" to "session-internal-1",
                        "toolCall" to jsonMapOf(
                            "toolCallId" to "tool-call-internal-1",
                            "title" to "Run project tests",
                            "kind" to "execute",
                            "rawInput" to jsonMapOf("command" to "pnpm test"),
                        ),
                        "options" to mutableListOf(
                            jsonMapOf("optionId" to "allow_once", "name" to "Allow once"),
                            jsonMapOf("optionId" to "reject_once", "name" to "Reject"),
                        ),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("Run project tests", cardData["title"])
        assertEquals("Command: pnpm test", cardData["detail"])
        val detail = cardData["detail"] as String
        assertFalse(detail.contains("session-internal-1"))
        assertFalse(detail.contains("tool-call-internal-1"))
        assertFalse(detail.contains("toolCall"))
    }

    @Test
    fun `maps request user input into codex request card`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "agentId" to "deepseek-harness-acp",
                "agentName" to "DeepSeek Harness",
                "sessionId" to "session-top-level",
                "message" to jsonMapOf(
                    "id" to "request-1",
                    "method" to "item/tool/requestUserInput",
                    "params" to jsonMapOf(
                        "questions" to mutableListOf(
                            jsonMapOf("id" to "choice", "question" to "Choose one"),
                        ),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("agent_request", cardData["type"])
        assertEquals("user_input", cardData["requestKind"])
        assertEquals("choice", cardData["questionId"])
        assertTrue((cardData["rawParamsJson"] as String).contains("Choose one"))
        assertEquals("pending", cardData["status"])
        assertEquals(42, cardData["conversationId"])
        assertEquals("session-top-level", cardData["sessionId"])
        assertEquals("deepseek-harness-acp", cardData["agentId"])
        assertEquals("DeepSeek Harness", cardData["agentName"])
    }

    @Test
    fun `preserves request id when ACP places it inside params`() {
        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "item/tool/requestUserInput",
                "params" to jsonMapOf(
                    "requestId" to "params-request-1",
                    "questions" to mutableListOf(
                        jsonMapOf("id" to "choice", "question" to "Choose one"),
                    ),
                ),
            ),
        )

        assertEquals("params-request-1", result.requestId)
        assertEquals("params-request-1", runtime.messages.single().cardData?.get("requestId"))
    }

    @Test
    fun `preserves request id when ACP places it inside the request item`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "item/started",
                "params" to jsonMapOf(
                    "item" to jsonMapOf(
                        "id" to "approval-item-1",
                        "type" to "requestApproval",
                        "requestId" to "item-request-1",
                        "reason" to "Need confirmation",
                    ),
                ),
            ),
        )

        assertEquals("item-request-1", runtime.messages.single().cardData?.get("requestId"))
    }

    @Test
    fun `marks an ACP request without request id as non-interactive`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "item/tool/requestUserInput",
                "params" to jsonMapOf(
                    "questions" to mutableListOf(
                        jsonMapOf("id" to "choice", "question" to "Choose one"),
                    ),
                ),
            ),
        )

        assertNull(runtime.messages.single().cardData?.get("requestId"))
        assertEquals(true, runtime.messages.single().cardData?.get("interactionUnavailable"))
    }

    @Test
    fun `reads collaboration mode from thread settings update`() {
        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "thread/settings/updated",
                "params" to jsonMapOf(
                    "threadId" to "thread-1",
                    "threadSettings" to jsonMapOf(
                        "collaborationMode" to jsonMapOf("mode" to "default"),
                    ),
                ),
            ),
        )

        assertTrue(result.handled)
        assertEquals("thread/settings/updated", result.method)
        assertEquals("thread-1", result.threadId)
        assertEquals("default", result.collaborationMode)
    }

    @Test
    fun `maps app-server request_user_input request before turn completes`() {
        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "item/tool/requestUserInput",
                "threadId" to "thread-1",
                "turnId" to "turn-1",
                "message" to jsonMapOf(
                    "jsonrpc" to "2.0",
                    "id" to 0,
                    "method" to "item/tool/requestUserInput",
                    "params" to jsonMapOf(
                        "threadId" to "thread-1",
                        "turnId" to "turn-1",
                        "itemId" to "call1",
                        "questions" to mutableListOf(
                            jsonMapOf(
                                "id" to "confirm_path",
                                "header" to "Confirm",
                                "question" to "Proceed with the plan?",
                                "options" to mutableListOf(
                                    jsonMapOf(
                                        "label" to "Yes (Recommended)",
                                        "description" to "Continue the current plan.",
                                    ),
                                    jsonMapOf(
                                        "label" to "No",
                                        "description" to "Stop and revisit the approach.",
                                    ),
                                ),
                            ),
                        ),
                        "autoResolutionMs" to 60000,
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertTrue(result.handled)
        assertEquals(0, result.requestId)
        assertEquals("agent_request", cardData["type"])
        assertEquals("user_input", cardData["requestKind"])
        assertEquals(0, cardData["requestId"])
        assertEquals("turn-1", cardData["taskId"])
        assertEquals("confirm_path", cardData["questionId"])
        assertTrue((cardData["rawParamsJson"] as String).contains("Yes (Recommended)"))
        assertTrue(runtime.isAiResponding)
    }

    private fun keepsSettledRequestUserInputStatus(settledStatus: String) {
        val requestEvent = jsonMapOf(
            "message" to jsonMapOf(
                "id" to "request-1",
                "method" to "item/tool/requestUserInput",
                "params" to jsonMapOf(
                    "questions" to mutableListOf(
                        jsonMapOf(
                            "id" to "choice",
                            "question" to "Choose one",
                            "options" to mutableListOf(
                                jsonMapOf("label" to "Option A"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        reducer.reduce(runtime, requestEvent)
        val existing = runtime.messages.single()
        val submittedCardData = LinkedHashMap<String, Any?>(existing.cardData!!)
            .apply { put("status", settledStatus) }
        runtime.messages[0] = existing.copyWith(
            content = jsonMapOf("cardData" to submittedCardData, "id" to existing.id),
        )

        reducer.reduce(runtime, requestEvent)

        assertEquals(settledStatus, runtime.messages.single().cardData!!["status"])
    }

    @Test
    fun `keeps submitted request user input status during event replay`() =
        keepsSettledRequestUserInputStatus("submitted")

    @Test
    fun `keeps cancelled request user input status during event replay`() =
        keepsSettledRequestUserInputStatus("cancelled")

    @Test
    fun `keeps interrupted request user input status during event replay`() =
        keepsSettledRequestUserInputStatus("interrupted")

    @Test
    fun `keeps failed request user input status during event replay`() =
        keepsSettledRequestUserInputStatus("failed")

    @Ignore("remoteCodexMessagesFromThreadResponseForTesting (remote_codex_snapshot_mapper.dart) has no Kotlin port; ported with snapshot mapper tests")
    @Test
    fun `hydrates historical request user input as submitted request card`() {
        /*
        final messages = remoteCodexMessagesFromThreadResponseForTesting({
          'thread': {
            'id': 'thread-1',
            'turns': [
              {
                'id': 'turn-1',
                'items': [
                  {
                    'id': 'request-1',
                    'type': 'requestUserInput',
                    'status': 'completed',
                    'questions': [
                      {
                        'id': 'choice',
                        'question': 'Choose one',
                        'options': [
                          {'label': 'Option A'},
                        ],
                      },
                    ],
                    'answers': {
                      'choice': {
                        'answers': ['Option A'],
                      },
                    },
                  },
                ],
              },
            ],
          },
        });

        final cardData = messages.single.cardData!;
        expect(cardData['type'], 'agent_request');
        expect(cardData['requestKind'], 'user_input');
        expect(cardData['questionId'], 'choice');
        expect(cardData['status'], 'submitted');
        expect(cardData['rawParamsJson'], contains('Option A'));
         */
    }

    @Test
    fun `ignores unknown events without throwing`() {
        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "future/event",
                    "params" to jsonMapOf("raw" to true),
                ),
            ),
        )

        assertFalse(result.handled)
        assertTrue(runtime.messages.isEmpty())
    }

    @Test
    fun `ignores codex stderr logs without creating tool cards`() {
        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "codex/stderr",
                    "params" to jsonMapOf("message" to "startup log"),
                ),
            ),
        )

        assertFalse(result.handled)
        assertTrue(runtime.messages.isEmpty())
    }

    @Test
    fun `removes stale codex stderr status cards`() {
        runtime.messages.add(
            ChatMessage.cardMessage(
                jsonMapOf(
                    "type" to "agent_tool_summary",
                    "toolName" to "codex.status",
                    "toolTitle" to "codex/stderr",
                    "displayName" to "codex/stderr",
                    "status" to "running",
                ),
                id = "stderr-status",
            ),
        )

        val result = reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "codex/stderr",
                    "params" to jsonMapOf("message" to "startup log"),
                ),
            ),
        )

        assertTrue(result.handled)
        assertTrue(runtime.messages.isEmpty())
    }

    @Test
    fun `reasoning item completed during active turn keeps thinking card loading`() {
        // Dart name: 'reasoning item/completed during active turn keeps thinking card loading'
        // ('/' is not allowed in a JVM method name).
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
                        "delta" to "analysing the request",
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
                        "threadId" to "thread-1",
                        "turnId" to "turn-1",
                        "itemId" to "reason-1",
                        "item" to jsonMapOf(
                            "id" to "reason-1",
                            "type" to "reasoning",
                            "summary" to "analysing the request",
                        ),
                    ),
                ),
            ),
        )

        val card = runtime.messages.first { it.cardData?.get("type") == "deep_thinking" }
        val cardData = card.cardData!!
        assertEquals(true, cardData["isLoading"])
        assertEquals(false, cardData["isCollapsible"])
        assertEquals(ThinkingStage.THINKING, cardData["stage"])
        assertTrue(runtime.isAiResponding)
    }

    @Test
    fun `new reasoning item ids stay in one loading thinking card`() {
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
                        "delta" to "first thought",
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
                        "threadId" to "thread-1",
                        "turnId" to "turn-1",
                        "itemId" to "reason-1",
                        "item" to jsonMapOf("id" to "reason-1", "type" to "reasoning"),
                    ),
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
                        "itemId" to "reason-2",
                        "delta" to "second thought",
                    ),
                ),
            ),
        )

        val thinkingMessages = runtime.messages.filter { it.cardData?.get("type") == "deep_thinking" }
        assertEquals(1, thinkingMessages.size)
        assertEquals(true, thinkingMessages.single().cardData!!["isLoading"])
        assertEquals(
            "first thoughtsecond thought",
            thinkingMessages.single().cardData!!["thinkingContent"],
        )

        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = null,
            turnId = "turn-1",
            stopReason = "end_turn",
        )

        val completedThinkingMessages =
            runtime.messages.filter { it.cardData?.get("type") == "deep_thinking" }
        assertEquals(1, completedThinkingMessages.size)
        assertEquals(ThinkingStage.COMPLETE, completedThinkingMessages.single().cardData?.get("stage"))
    }

    @Test
    fun `ACP reasoning segment metadata splits reused message ids around tools`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "session/update",
                    "params" to jsonMapOf(
                        "sessionId" to "session-1",
                        "turnId" to "turn-1",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "agent_thought_chunk",
                            "messageId" to "shared-thought",
                            "content" to jsonMapOf("type" to "text", "text" to "before tool"),
                            "_meta" to jsonMapOf(
                                "cn.com.omnimind.agent" to jsonMapOf(
                                    "reasoning" to jsonMapOf("segmentIndex" to 0),
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
                    "method" to "session/update",
                    "params" to jsonMapOf(
                        "sessionId" to "session-1",
                        "turnId" to "turn-1",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "tool_call",
                            "toolCallId" to "tool-1",
                            "title" to "read_file",
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
                    "method" to "session/update",
                    "params" to jsonMapOf(
                        "sessionId" to "session-1",
                        "turnId" to "turn-1",
                        "update" to jsonMapOf(
                            "sessionUpdate" to "agent_thought_chunk",
                            "messageId" to "shared-thought",
                            "content" to jsonMapOf("type" to "text", "text" to "after tool"),
                            "_meta" to jsonMapOf(
                                "cn.com.omnimind.agent" to jsonMapOf(
                                    "reasoning" to jsonMapOf("segmentIndex" to 1),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val cards = runtime.messages
            .filter { it.cardData?.get("type") == "deep_thinking" }
            .reversed()
        assertEquals(2, cards.size)
        assertEquals("before tool", cards[0].cardData?.get("thinkingContent"))
        assertEquals("after tool", cards[1].cardData?.get("thinkingContent"))
    }

    @Test
    fun `turn completed finalizes the thinking card after reasoning ends`() {
        // Dart name: 'turn/completed finalizes the thinking card after reasoning ends'
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
                        "delta" to "finished thinking",
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
                        "threadId" to "thread-1",
                        "turnId" to "turn-1",
                        "itemId" to "reason-1",
                        "item" to jsonMapOf(
                            "id" to "reason-1",
                            "type" to "reasoning",
                            "summary" to "finished thinking",
                        ),
                    ),
                ),
            ),
        )
        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = null,
            turnId = "turn-1",
            stopReason = "end_turn",
        )

        val card = runtime.messages.first { it.cardData?.get("type") == "deep_thinking" }
        val cardData = card.cardData!!
        assertEquals(false, cardData["isLoading"])
        assertEquals(true, cardData["isCollapsible"])
        assertEquals(ThinkingStage.COMPLETE, cardData["stage"])
        assertFalse(runtime.isAiResponding)
    }

    @Test
    fun `owning prompt transport failure finalizes the active request`() {
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

        assertTrue(runtime.isAiResponding)

        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = null,
            turnId = "turn-1",
            stopReason = "error",
            error = "connection lost",
        )

        assertFalse(runtime.isAiResponding)
        assertNull(runtime.currentDispatchTurnId)
        val thinking = runtime.messages
            .first { it.cardData?.get("type") == "deep_thinking" }
            .cardData!!
        assertEquals(false, thinking["isLoading"])
        assertEquals(ThinkingStage.COMPLETE, thinking["stage"])
    }

    @Test
    fun `prompt failure finalizes a local run when the official turn id differs`() {
        runtime.isAiResponding = true
        runtime.activeRunId = "local-run-1"
        runtime.currentDispatchTurnId = "local-run-1"
        runtime.lastAgentTurnId = "local-run-1"
        runtime.activeAcpTurnId = "official-turn-1"

        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = null,
            turnId = "official-turn-1",
            stopReason = "error",
            error = "provider failed",
        )

        assertFalse(runtime.isAiResponding)
        assertNull(runtime.currentDispatchTurnId)
        assertNull(runtime.activeAcpTurnId)
    }

    @Test
    fun `terminal error finalizes a local run when the official turn id differs`() {
        runtime.isAiResponding = true
        runtime.activeRunId = "local-run-2"
        runtime.currentDispatchTurnId = "local-run-2"
        runtime.lastAgentTurnId = "local-run-2"
        runtime.activeAcpTurnId = "official-turn-2"

        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = null,
            turnId = "official-turn-2",
            stopReason = "error",
            error = "connection lost",
        )

        assertFalse(runtime.isAiResponding)
        assertNull(runtime.currentDispatchTurnId)
        assertNull(runtime.activeAcpTurnId)
    }

    @Test
    fun `turn completed with a cancelled stop reason stays cancelled`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "turn/started",
                "turnId" to "cancelled-turn",
                "params" to jsonMapOf("turnId" to "cancelled-turn"),
            ),
        )
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "turnId" to "cancelled-turn",
                "params" to jsonMapOf(
                    "update" to jsonMapOf(
                        "sessionUpdate" to "agent_thought_chunk",
                        "messageId" to "cancelled-thought",
                        "content" to jsonMapOf("text" to "处理中"),
                    ),
                ),
            ),
        )

        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = null,
            turnId = "cancelled-turn",
            stopReason = "cancelled",
        )

        assertFalse(runtime.isAiResponding)
        val thinking = runtime.messages.first { it.cardData?.get("type") == "deep_thinking" }
        assertEquals(ThinkingStage.CANCELLED, thinking.cardData?.get("stage"))
    }

    @Test
    fun `owning prompt Provider error is rendered as a concise message`() {
        reducer.reducePromptResponse(
            runtime = runtime,
            sessionId = null,
            turnId = "turn-1",
            stopReason = "error",
            error = "Invalid JSON data: tools[8].type is unsupported",
        )

        val statusCard = runtime.messages.first { it.cardData?.get("toolType") == "status" }
        assertEquals("助手暂时无法完成操作，请重试。", statusCard.cardData?.get("summary"))
        assertFalse((statusCard.cardData?.get("summary") as String).contains("{\"error\""))
    }

    @Test
    fun `top-level error with willRetry=true keeps the turn active`() {
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
                    "method" to "error",
                    "params" to jsonMapOf(
                        "threadId" to "thread-1",
                        "turnId" to "turn-1",
                        "willRetry" to true,
                        "message" to "rate limited",
                    ),
                ),
            ),
        )

        assertTrue(runtime.isAiResponding)
        assertNotNull(runtime.currentDispatchTurnId)
    }

    @Ignore("remoteCodexMessagesFromThreadResponseForTesting (remote_codex_snapshot_mapper.dart) has no Kotlin port; ported with snapshot mapper tests")
    @Test
    fun `snapshot renders reasoning as loading even when item_status is completed while turn is active`() {
        /*
        Dart name: 'snapshot renders reasoning as loading even when item.status is completed while turn is active'
        final messages = remoteCodexMessagesFromThreadResponseForTesting(
          {
            'thread': {
              'id': 'thread-1',
              'status': {'type': 'active'},
              'turns': [
                {
                  'id': 'turn-1',
                  'status': 'inProgress',
                  'items': [
                    {
                      'id': 'reason-1',
                      'type': 'reasoning',
                      'status': 'completed',
                      'summary': ['done reasoning'],
                    },
                  ],
                },
              ],
            },
          },
          active: true,
          activeTurnId: 'turn-1',
        );

        final cardData = messages.first.cardData!;
        expect(cardData['type'], 'deep_thinking');
        expect(cardData['isLoading'], isTrue);
        expect(cardData['isCollapsible'], isFalse);
        expect(cardData['stage'], ThinkingStage.thinking.value);
         */
    }

    @Ignore("remoteCodexMessagesFromThreadResponseForTesting (remote_codex_snapshot_mapper.dart) has no Kotlin port; ported with snapshot mapper tests")
    @Test
    fun `snapshot keeps only the latest reasoning card loading for active turn`() {
        /*
        final messages = remoteCodexMessagesFromThreadResponseForTesting(
          {
            'thread': {
              'id': 'thread-1',
              'status': {'type': 'active'},
              'turns': [
                {
                  'id': 'turn-1',
                  'status': 'inProgress',
                  'items': [
                    {
                      'id': 'reason-1',
                      'type': 'reasoning',
                      'status': 'completed',
                      'summary': ['older reasoning'],
                    },
                    {
                      'id': 'reason-2',
                      'type': 'reasoning',
                      'status': 'completed',
                      'summary': ['latest reasoning'],
                    },
                  ],
                },
              ],
            },
          },
          active: true,
          activeTurnId: 'turn-1',
        );

        final first = messages.firstWhere(
          (message) => message.id == 'reason-1-agent-thinking',
        );
        final second = messages.firstWhere(
          (message) => message.id == 'reason-2-agent-thinking',
        );
        expect(first.cardData!['isLoading'], isFalse);
        expect(first.cardData!['stage'], ThinkingStage.complete.value);
        expect(second.cardData!['isLoading'], isTrue);
        expect(second.cardData!['stage'], ThinkingStage.thinking.value);
         */
    }

    @Test
    fun `item_started commandExecution with commandActions read uses workspace card and keeps deltas`() {
        // Dart name: 'item/started commandExecution with commandActions read uses workspace card and keeps deltas'
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/started",
                    "params" to jsonMapOf(
                        "threadId" to "thread-1",
                        "turnId" to "turn-1",
                        "item" to jsonMapOf(
                            "id" to "read-2",
                            "type" to "commandExecution",
                            "command" to "sed -n 1,200p AGENTS.md",
                            "cwd" to "/repo",
                            "status" to "in_progress",
                            "commandActions" to mutableListOf(
                                jsonMapOf(
                                    "type" to "read",
                                    "command" to "sed -n 1,200p AGENTS.md",
                                    "name" to "AGENTS.md",
                                    "path" to "/repo/AGENTS.md",
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
                    "method" to "item/commandExecution/outputDelta",
                    "params" to jsonMapOf(
                        "threadId" to "thread-1",
                        "turnId" to "turn-1",
                        "itemId" to "read-2",
                        "delta" to "# Project AGENTS.md\n",
                    ),
                ),
            ),
        )

        assertEquals(1, runtime.messages.size)
        val cardData = runtime.messages.single().cardData!!
        assertEquals("workspace", cardData["toolType"])
        assertEquals("Read AGENTS.md", cardData["toolTitle"])
        assertTrue((cardData["terminalOutput"] as String).contains("Project AGENTS.md"))
        assertEquals("running", cardData["status"])
    }

    @Test
    fun `parsed_cmd list_files at item_started becomes workspace List card`() {
        // Dart name: 'parsed_cmd list_files at item/started becomes workspace List card'
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/started",
                    "params" to jsonMapOf(
                        "threadId" to "thread-1",
                        "turnId" to "turn-1",
                        "item" to jsonMapOf(
                            "id" to "list-1",
                            "type" to "commandExecution",
                            "command" to "ls /repo/ui",
                            "cwd" to "/repo",
                            "status" to "in_progress",
                            "commandActions" to mutableListOf(
                                jsonMapOf(
                                    "type" to "listFiles",
                                    "command" to "ls /repo/ui",
                                    "path" to "/repo/ui",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("workspace", cardData["toolType"])
        assertEquals("List ui", cardData["toolTitle"])
    }

    @Test
    fun `rawResponseItem function_call js with arguments_title shows title`() {
        // Dart name: 'rawResponseItem function_call js with arguments.title shows title'
        // Mirrors the OpenAI Responses path: codex app-server forwards
        // EVERY function_call ResponseItem as rawResponseItem/completed. For
        // node_repl/js the arguments JSON carries a human-readable title
        // alongside the code blob.
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
                            "name" to "js",
                            "call_id" to "call_agQUhiEvZgvXKxX7ursGybbn",
                            "arguments" to DartJson.encode(
                                jsonMapOf(
                                    "title" to "Refine flavor parsing",
                                    "code" to "const fs2 = await import('node:fs/promises');",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("agent_tool_summary", cardData["type"])
        assertEquals("Refine flavor parsing", cardData["toolTitle"])
    }

    @Test
    fun `rawResponseItem function_call exec_command shows the cmd as title`() {
        // exec_command is the dominant tool in the user-reported session
        // (22 occurrences). Arguments JSON has {cmd, workdir, max_output_tokens}.
        // The card should be a terminal-type card titled with the cmd.
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
                            "call_id" to "call_5qvsAWrt1UCjXkfPlakIsqXD",
                            "arguments" to DartJson.encode(
                                jsonMapOf(
                                    "cmd" to "sed -n '1,260p' app/build.gradle.kts",
                                    "workdir" to "/Users/ocean/code/OmnibotApp",
                                    "max_output_tokens" to 16000,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("agent_tool_summary", cardData["type"])
        assertEquals("terminal", cardData["toolType"])
        assertTrue((cardData["toolTitle"] as String).contains("sed -n '1,260p' app/build.gradle.kts"))
    }

    @Test
    fun `function_call_output for exec_command merges output into the same card`() {
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
                            "call_id" to "call_merge_1",
                            "arguments" to DartJson.encode(
                                jsonMapOf(
                                    "cmd" to "pwd",
                                    "workdir" to "/repo",
                                    "max_output_tokens" to 2000,
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
                    "method" to "rawResponseItem/completed",
                    "params" to jsonMapOf(
                        "threadId" to "thread-1",
                        "turnId" to "turn-1",
                        "item" to jsonMapOf(
                            "type" to "function_call_output",
                            "call_id" to "call_merge_1",
                            "output" to "/repo\n",
                        ),
                    ),
                ),
            ),
        )

        assertEquals(1, runtime.messages.size)
        val cardData = runtime.messages.single().cardData!!
        assertEquals("terminal", cardData["toolType"])
        assertEquals("pwd", cardData["toolTitle"])
        assertTrue((cardData["terminalOutput"] as String).contains("/repo"))
        assertEquals("success", cardData["status"])
    }

    @Test
    fun `item_started mcpToolCall without title falls back to tool short name`() {
        // Dart name: 'item/started mcpToolCall without title falls back to tool short name'
        reducer.reduce(
            runtime,
            jsonMapOf(
                "message" to jsonMapOf(
                    "method" to "item/started",
                    "params" to jsonMapOf(
                        "threadId" to "thread-1",
                        "turnId" to "turn-1",
                        "item" to jsonMapOf(
                            "id" to "mcp-1",
                            "type" to "mcpToolCall",
                            "tool" to "plain_tool",
                            "arguments" to "{}",
                        ),
                    ),
                ),
            ),
        )

        val cardData = runtime.messages.single().cardData!!
        assertEquals("plain_tool", cardData["toolTitle"])
    }

    @Test
    fun `standard ACP tool content chunks update the existing shared card`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "turnId" to "turn-tool-content-chunk",
                "params" to jsonMapOf(
                    "sessionId" to "session-tool-content-chunk",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "tool_call",
                        "toolCallId" to "tool-content-1",
                        "kind" to "other",
                        "title" to "读取结果",
                        "status" to "in_progress",
                    ),
                ),
            ),
        )
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "turnId" to "turn-tool-content-chunk",
                "params" to jsonMapOf(
                    "sessionId" to "session-tool-content-chunk",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "tool_call_content_chunk",
                        "toolCallId" to "tool-content-1",
                        "content" to jsonMapOf(
                            "type" to "content",
                            "content" to jsonMapOf("type" to "text", "text" to "第一段结果"),
                        ),
                    ),
                ),
            ),
        )
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "turnId" to "turn-tool-content-chunk",
                "params" to jsonMapOf(
                    "sessionId" to "session-tool-content-chunk",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "vendor_passthrough",
                        "rawUpdate" to jsonMapOf(
                            "type" to "tool_call_content_chunk",
                            "toolCallId" to "tool-content-1",
                            "content" to jsonMapOf(
                                "type" to "content",
                                "content" to jsonMapOf("type" to "text", "text" to "第二段结果"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val card = runtime.messages
            .first { it.cardData?.get("type") == "agent_tool_summary" }
            .cardData!!
        val contentItems = card["contentItems"] as List<*>
        assertEquals(2, contentItems.size)
        assertTrue((card["rawResultJson"] as String).contains("第二段结果"))
    }

    @Test
    fun `ACP terminal output chunks append by tool or terminal identity`() {
        reducer.reduce(
            runtime,
            jsonMapOf(
                "method" to "session/update",
                "turnId" to "turn-terminal-chunk",
                "params" to jsonMapOf(
                    "sessionId" to "session-terminal-chunk",
                    "update" to jsonMapOf(
                        "sessionUpdate" to "tool_call",
                        "toolCallId" to "tool-terminal-1",
                        "kind" to "execute",
                        "title" to "运行命令",
                        "status" to "in_progress",
                        "content" to mutableListOf(
                            jsonMapOf("type" to "terminal", "terminalId" to "terminal-1"),
                        ),
                    ),
                ),
            ),
        )
        // Dart source uses '\\n' (literal backslash + n), kept as-is.
        for (update in listOf(
            jsonMapOf(
                "sessionUpdate" to "terminal_output_chunk",
                "toolCallId" to "tool-terminal-1",
                "terminalId" to "terminal-1",
                "data" to "one\\n",
            ),
            jsonMapOf(
                "sessionUpdate" to "vendor_passthrough",
                "rawUpdate" to jsonMapOf(
                    "type" to "terminal_output_chunk",
                    "terminalId" to "terminal-1",
                    "data" to "two\\n",
                ),
            ),
        )) {
            reducer.reduce(
                runtime,
                jsonMapOf(
                    "method" to "session/update",
                    "turnId" to "turn-terminal-chunk",
                    "params" to jsonMapOf("sessionId" to "session-terminal-chunk", "update" to update),
                ),
            )
        }

        val card = runtime.messages.single().cardData!!
        assertEquals("one\\ntwo\\n", card["terminalOutput"])
        assertEquals("terminal-1", card["terminalSessionId"])
    }
}
