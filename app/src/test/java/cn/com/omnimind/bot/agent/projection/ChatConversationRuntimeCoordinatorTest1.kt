package cn.com.omnimind.bot.agent.projection

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Port of `ui/test/features/home/pages/chat/chat_conversation_runtime_coordinator_test.dart`,
 * lines 1-1478. Same inputs, same assertions, same test names.
 */
class ChatConversationRuntimeCoordinatorTest1 {
    private lateinit var fixture: ChatRuntimeTestFixture
    private val coordinator get() = fixture.coordinator

    @Before
    fun setUp() {
        fixture = ChatRuntimeTestFixture()
        coordinator.resetForTest()
    }

    @After
    fun tearDown() {
        coordinator.resetForTest()
    }

    private fun acpEvent(
        method: String,
        turnId: String,
        sessionId: String? = null,
        params: Map<String, Any?> = emptyMap(),
    ) = fixture.acpEvent(method, turnId = turnId, sessionId = sessionId, params = params)

    private fun applyAcp(
        conversationId: Int,
        method: String,
        turnId: String,
        sessionId: String? = null,
        params: Map<String, Any?> = emptyMap(),
        mode: String = CHAT_RUNTIME_MODE_AGENT,
        hostAssignedTurn: Boolean = false,
    ) = fixture.applyAcp(
        conversationId,
        method,
        turnId = turnId,
        sessionId = sessionId,
        params = params,
        mode = mode,
        hostAssignedTurn = hostAssignedTurn,
    )

    private fun completePrompt(
        conversationId: Int,
        turnId: String,
        sessionId: String? = null,
        params: Map<String, Any?> = emptyMap(),
    ) = fixture.completePrompt(conversationId, turnId = turnId, sessionId = sessionId, params = params)

    private fun runtimeFor(conversationId: Int, mode: String = CHAT_RUNTIME_MODE_AGENT) =
        coordinator.debugRuntimeStateFor(conversationId, mode)!!

    private fun messageChunk(messageId: String?, text: String, typed: Boolean = false): Map<String, Any?> {
        val content: MutableMap<String, Any?> = linkedMapOf()
        if (typed) content["type"] = "text"
        content["text"] = text
        val update: MutableMap<String, Any?> = linkedMapOf("sessionUpdate" to "agent_message_chunk")
        if (messageId != null) update["messageId"] = messageId
        update["content"] = content
        return linkedMapOf("update" to update)
    }

    /** Dart `containsAllInOrder`: each expected element appears, in order. */
    private fun assertContainsAllInOrder(actual: List<Any?>, expected: List<Any?>) {
        var index = 0
        for (item in actual) {
            if (index < expected.size && item == expected[index]) index += 1
        }
        assertTrue("expected $actual to contain in order $expected", index == expected.size)
    }

    private fun assertContainsAll(actual: Collection<Any?>, expected: Collection<Any?>) {
        assertTrue("expected $actual to contain all of $expected", actual.containsAll(expected))
    }

    @Test
    fun `compaction observations cannot overwrite the saved user threshold`() {
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = 99109, mode = CHAT_RUNTIME_MODE_AGENT)
        runtime.conversation = ConversationPayload.create(
            id = 99109,
            mode = ConversationModes.AGENT,
            title = "budget",
            status = 0,
            messageCount = 0,
            createdAt = 1,
            updatedAt = 1,
        ).apply { put("promptTokenThreshold", 64000) }
        coordinator.beginContextCompaction(
            conversationId = 99109,
            mode = CHAT_RUNTIME_MODE_AGENT,
            latestPromptTokens = 31000,
            promptTokenThreshold = 32000,
        )
        assertEquals(31000, ConversationPayload(runtime.conversation!!).latestPromptTokens)
        assertEquals(64000, ConversationPayload(runtime.conversation!!).promptTokenThreshold)
        coordinator.finishContextCompaction(
            conversationId = 99109,
            mode = CHAT_RUNTIME_MODE_AGENT,
            latestPromptTokens = 8000,
            promptTokenThreshold = 32000,
        )
        assertEquals(64000, ConversationPayload(runtime.conversation!!).promptTokenThreshold)
    }

    @Test
    fun `host-bound commands arrive before a prompt without creating a turn`() {
        fun commands(session: String, admitted: Boolean): Map<String, Any?> = linkedMapOf(
            "method" to "session/update",
            "threadId" to session,
            "allowImplicitTurnAdmission" to admitted,
            "params" to linkedMapOf(
                "sessionId" to session,
                "update" to linkedMapOf(
                    "sessionUpdate" to "available_commands_update",
                    "availableCommands" to listOf(
                        linkedMapOf("name" to "compact", "description" to "Compact context"),
                    ),
                ),
            ),
        )
        val rejected = coordinator.applyAgentEvent(conversationId = 2002, event = commands("unknown", false))
        assertFalse(rejected.handled)
        val accepted = coordinator.applyAgentEvent(conversationId = 2002, event = commands("bound", true))
        assertTrue(accepted.handled)
        val runtime = runtimeFor(2002)
        assertEquals("compact", runtime.availableAcpCommands.single()["name"])
        assertEquals("bound", runtime.activeAcpSessionId)
        assertNull(runtime.activeAcpTurnId)
        assertFalse(runtime.isAiResponding)
        runtime.retiredAcpSessionIds.add("old")
        assertFalse(coordinator.applyAgentEvent(conversationId = 2002, event = commands("old", true)).handled)
        assertEquals("bound", runtime.activeAcpSessionId)
    }

    @Test
    fun `renders ACP assistant, reasoning, and tool updates in one turn`() {
        val conversationId = 2002
        val turnId = "turn-xiaowan"
        applyAcp(conversationId, "turn/started", turnId = turnId)
        applyAcp(
            conversationId,
            "session/update",
            turnId = turnId,
            params = linkedMapOf(
                "sessionId" to turnId,
                "update" to linkedMapOf(
                    "sessionUpdate" to "agent_thought_chunk",
                    "messageId" to "thought-1",
                    "content" to linkedMapOf("text" to "先分析任务。"),
                ),
            ),
        )
        applyAcp(
            conversationId,
            "session/update",
            turnId = turnId,
            params = linkedMapOf(
                "sessionId" to turnId,
                "update" to linkedMapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "messageId" to "message-1",
                    "content" to linkedMapOf("text" to "已经开始处理。"),
                ),
            ),
        )
        applyAcp(
            conversationId,
            "session/update",
            turnId = turnId,
            params = linkedMapOf(
                "sessionId" to turnId,
                "update" to linkedMapOf(
                    "sessionUpdate" to "tool_call",
                    "toolCallId" to "tool-1",
                    "kind" to "execute",
                    "title" to "检查工作区",
                    "status" to "running",
                ),
            ),
        )

        val runtime = runtimeFor(conversationId)
        assertTrue(runtime.messages.any { it.user == 2 })
        assertTrue(runtime.messages.any { it.cardData?.get("type") == "deep_thinking" })
        assertTrue(runtime.messages.any { it.cardData?.get("type") == "agent_tool_summary" })
        assertTrue(runtime.isAiResponding)
    }

    @Test
    fun `projects the official session prompt response without turn events`() {
        val conversationId = 2048
        coordinator.beginAcpTurn(taskId = "local-prompt", conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.bindAcpSession(
            taskId = "local-prompt",
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            sessionId = "session-official",
        )
        coordinator.applyAgentEvent(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            event = acpEvent("turn/started", turnId = "turn-official", sessionId = "session-official"),
        )
        coordinator.applyAgentEvent(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            event = acpEvent(
                "session/update",
                turnId = "turn-official",
                sessionId = "session-official",
                params = messageChunk("message-official", "ACP 输出"),
            ),
        )
        val result = coordinator.applyAcpPromptResponse(
            taskId = "local-prompt",
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            sessionId = "session-official",
            turnId = "turn-official",
            stopReason = "end_turn",
        )
        val runtime = runtimeFor(conversationId)

        assertEquals("session/prompt", result.method)
        assertFalse(runtime.isAiResponding)
        assertNull(runtime.activeAcpTurnId)
        assertTrue(runtime.messages.any { it.text == "ACP 输出" })
    }

    @Test
    fun `persists legacy normal ACP events into canonical agent history`() {
        val conversationId = 2005
        val turnId = "turn-xiaowan-normal-history"

        applyAcp(conversationId, "turn/started", turnId = turnId, mode = CHAT_RUNTIME_MODE_NORMAL)
        applyAcp(
            conversationId,
            "session/update",
            turnId = turnId,
            mode = CHAT_RUNTIME_MODE_NORMAL,
            params = linkedMapOf(
                "sessionId" to "session-xiaowan-normal-history",
                "update" to linkedMapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "messageId" to "message-normal-history",
                    "content" to linkedMapOf("text" to "第一轮回复"),
                ),
            ),
        )

        // Dart: await Future<void>.delayed(const Duration(milliseconds: 500));
        fixture.scheduler.advanceBy(500)

        val replaceCalls = fixture.history.callsTo("replaceConversationMessages")
        assertTrue(replaceCalls.isNotEmpty())
        assertEquals(conversationId, replaceCalls.last().arguments["conversationId"])
        assertEquals(ConversationModes.AGENT, replaceCalls.last().arguments["mode"])
    }

    @Test
    fun `begins a turn without a visible thinking placeholder before ACP output`() {
        val conversationId = 2003
        val taskId = "local-task-before-acp"

        coordinator.beginAcpTurn(taskId = taskId, conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        val generationAfterFirstBegin = runtimeFor(conversationId).persistenceGeneration
        coordinator.beginAcpTurn(taskId = taskId, conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)

        val runtime = runtimeFor(conversationId)
        assertEquals(generationAfterFirstBegin, runtime.persistenceGeneration)
        assertTrue(runtime.isAiResponding)
        assertEquals(taskId, runtime.currentDispatchTurnId)
        assertEquals(0, runtime.messages.count { it.cardData?.get("type") == "deep_thinking" })
    }

    @Test
    fun `admits an official session update without wire turn id via host reservation`() {
        val conversationId = 2004
        val localRunId = "local-reserved-turn"
        coordinator.beginAcpTurn(taskId = localRunId, conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)

        val result = coordinator.applyAgentEvent(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            event = linkedMapOf(
                "method" to "session/update",
                "allowImplicitTurnAdmission" to true,
                "params" to linkedMapOf(
                    "sessionId" to "session-no-wire-turn",
                    "update" to linkedMapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "messageId" to "message-no-wire-turn",
                        "content" to linkedMapOf("type" to "text", "text" to "标准 ACP session/update"),
                    ),
                ),
            ),
        )

        val runtime = runtimeFor(conversationId)
        assertTrue(result.handled)
        assertEquals("标准 ACP session/update", runtime.messages.single().text)
        assertEquals("session-no-wire-turn", runtime.activeAcpSessionId)
        assertNull(runtime.activeAcpTurnId)
        assertEquals(localRunId, runtime.activeRunId)
    }

    @Test
    fun `keeps the local run identity separate from the official ACP turn`() {
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = 2008, mode = CHAT_RUNTIME_MODE_AGENT)

        runtime.currentDispatchTurnId = "local-run-1"
        runtime.activeAcpTurnId = "acp-turn-1"

        assertEquals("local-run-1", runtime.activeRunId)
        assertEquals("local-run-1", runtime.currentDispatchTurnId)
        assertEquals("acp-turn-1", runtime.activeAcpTurnId)

        runtime.currentDispatchTurnId = null
        assertNull(runtime.activeRunId)
        assertEquals("acp-turn-1", runtime.activeAcpTurnId)
    }

    @Test
    fun `projection buffers do not keep a completed runtime in flight`() {
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = 2009, mode = CHAT_RUNTIME_MODE_AGENT)

        runtime.currentAiMessages["message-1"] = "partial answer"
        runtime.currentThinkingMessages["message-1"] = "partial reasoning"

        assertFalse(runtime.hasInFlightTask)
        assertTrue(runtime.activeAgentTurnIds.isEmpty())
    }

    @Test
    fun `routes ACP lifecycle by admitted turn identity`() {
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = 42, mode = CHAT_RUNTIME_MODE_NORMAL)
        runtime.activeAcpTurnId = "turn-normal-1"
        runtime.currentDispatchTurnId = "turn-normal-1"

        assertEquals(CHAT_RUNTIME_MODE_NORMAL, coordinator.modeForAcpEvent(conversationId = 42, turnId = "turn-normal-1"))
        assertNull(coordinator.modeForAcpEvent(conversationId = 42, turnId = "turn-agent-1"))
    }

    @Test
    fun `retains ACP dedupe and turn ownership across a long conversation`() {
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = 4201, mode = CHAT_RUNTIME_MODE_AGENT)
        for (index in 0 until 700) {
            runtime.rememberProcessedAcpEventId("event-$index")
            runtime.rememberCompletedAcpTurn("turn-$index")
            runtime.resolveRunId(sessionId = "session-$index", turnId = "turn-$index", fallback = "run-$index")
        }

        assertEquals(700, runtime.processedAcpEventIds.size)
        assertEquals(700, runtime.completedAcpTurnIds.size)
        assertEquals(700, runtime.acpTurnToRunIds.size)
        assertTrue(runtime.processedAcpEventIds.contains("event-0"))
        assertTrue(runtime.processedAcpEventIds.contains("event-699"))
        assertEquals("run-0", runtime.resolveKnownRunId(sessionId = "session-0", turnId = "turn-0"))
        assertEquals("run-699", runtime.resolveKnownRunId(sessionId = "session-699", turnId = "turn-699"))
    }

    @Test
    fun `routes a known legacy process to its owning conversation`() {
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = 4202, mode = CHAT_RUNTIME_MODE_AGENT)
        runtime.standaloneProcessOwner("process-known", "turn-1")

        assertEquals(4202, coordinator.conversationIdForStandaloneProcess("process-known"))
        assertNull(coordinator.conversationIdForStandaloneProcess("process-unknown"))
    }

    @Test
    fun `does not restore a completed run as an active timeline group`() {
        val conversationId = 2004
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        runtime.isAiResponding = true
        runtime.isExecutingTask = true
        runtime.currentDispatchTurnId = "completed-run"
        runtime.activeRunId = "completed-run"
        runtime.lastAgentTurnId = "completed-run"
        runtime.activeAcpSessionId = "old-session"

        coordinator.replaceConversationSnapshot(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            messages = listOf(ChatMessage.userMessage("已经完成的请求")),
            isAiResponding = false,
            isExecutingTask = false,
            currentDispatchTurnId = null,
            lastAgentTurnId = null,
        )

        assertTrue(runtime.activeAgentTurnIds.isEmpty())
        assertNull(runtime.activeRunId)
        assertNull(runtime.currentDispatchTurnId)
        assertNull(runtime.activeAcpSessionId)
    }

    @Test
    fun `an idle snapshot cannot demote an admitted ACP turn`() {
        val conversationId = 2005
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.registerTask(taskId = "live-run", conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.beginAcpTurn(taskId = "live-run", conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        runtime.activeAcpSessionId = "live-session"
        runtime.activeAcpTurnId = "official-live-turn"
        runtime.messages.add(ChatMessage.userMessage("正在执行的请求", id = "live-user"))
        assertTrue(
            coordinator.isTaskActive(taskId = "live-run", conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT),
        )

        coordinator.replaceConversationSnapshot(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            messages = listOf(ChatMessage.userMessage("旧的历史快照", id = "history-user")),
            isAiResponding = false,
            isExecutingTask = false,
        )

        assertTrue(runtime.isAiResponding)
        assertEquals("live-run", runtime.currentDispatchTurnId)
        assertEquals("live-run", runtime.activeRunId)
        assertEquals("live-session", runtime.activeAcpSessionId)
        assertEquals("official-live-turn", runtime.activeAcpTurnId)
        assertContainsAll(runtime.messages.map { it.text }, listOf("正在执行的请求", "旧的历史快照"))
    }

    @Test
    fun `a snapshot with running flags cannot clear the admitted ACP identity`() {
        val conversationId = 20051
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.registerTask(taskId = "host-run", conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.beginAcpTurn(taskId = "host-run", conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        runtime.activeAcpSessionId = "official-session"
        runtime.activeAcpTurnId = "official-turn"
        val latest = ChatMessage.assistantMessage("latest streamed output", id = "item")
        runtime.messages.add(latest)
        coordinator.replaceConversationSnapshot(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            messages = listOf(ChatMessage.assistantMessage("stale output", id = "item")),
            isAiResponding = true,
            currentDispatchTurnId = "host-run",
        )
        assertEquals("official-session", runtime.activeAcpSessionId)
        assertEquals("official-turn", runtime.activeAcpTurnId)
        assertSame(latest, runtime.messages.single())
        assertTrue(runtime.hasInFlightTask)
    }

    @Test
    fun `an authoritative idle snapshot can finish only its matching turn`() {
        val conversationId = 2008
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.registerTask(taskId = "remote-run", conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.beginAcpTurn(taskId = "remote-run", conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        runtime.activeAcpSessionId = "remote-thread"
        runtime.activeAcpTurnId = "remote-turn"

        assertFalse(
            coordinator.finishTaskFromAuthoritativeSnapshot(
                taskId = "remote-run",
                conversationId = conversationId,
                mode = CHAT_RUNTIME_MODE_AGENT,
                sessionId = "other-thread",
                turnId = "remote-turn",
            ),
        )
        assertTrue(runtime.isAiResponding)

        assertTrue(
            coordinator.finishTaskFromAuthoritativeSnapshot(
                taskId = "remote-run",
                conversationId = conversationId,
                mode = CHAT_RUNTIME_MODE_AGENT,
                sessionId = "remote-thread",
                turnId = "remote-turn",
            ),
        )
        assertFalse(runtime.isAiResponding)
        assertNull(runtime.activeRunId)
        assertNull(runtime.activeAcpTurnId)
    }

    @Test
    fun `expires persisted ACP request cards when restoring an idle session`() {
        val conversationId = 2006
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)

        coordinator.replaceConversationSnapshot(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            messages = listOf(
                ChatMessage(
                    id = "request-card-1",
                    type = 2,
                    user = 3,
                    content = linkedMapOf(
                        "cardData" to linkedMapOf(
                            "type" to "agent_request",
                            "requestId" to "request-1",
                            "status" to "pending",
                            "requestKind" to "user_input",
                        ),
                    ),
                ),
            ),
            isAiResponding = false,
            isExecutingTask = false,
        )

        val card = runtime.messages.single().cardData!!
        assertEquals("expired", card["status"])
        assertEquals(true, card["interactionUnavailable"])
        assertEquals("session_ended", card["interactionUnavailableReason"])
    }

    // --- for (final preserveLive in [false, true]) ---------------------------

    private fun terminalHistoricalRequestsStayClosed(preserveLive: Boolean) {
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = 2018, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.replaceConversationSnapshot(
            conversationId = 2018,
            mode = CHAT_RUNTIME_MODE_AGENT,
            isAiResponding = true,
            preserveLiveStreamingState = preserveLive,
            messages = listOf("old", "answered", "current").map { id ->
                val streamMeta: MutableMap<String, Any?> =
                    linkedMapOf("parentTaskId" to if (id == "current") "new-turn" else "old-turn")
                if (id != "current") streamMeta["stopReason"] = "cancelled"
                ChatMessage(
                    id = id,
                    type = 2,
                    user = 3,
                    content = linkedMapOf(
                        "extra" to "preserve",
                        "cardData" to linkedMapOf(
                            "type" to "agent_request",
                            "requestId" to id,
                            "status" to if (id == "answered") "accepted" else "pending",
                        ),
                    ),
                    streamMeta = streamMeta,
                )
            },
        )
        run {
            val index = runtime.messages.indexOfFirst { it.id == "old" }
            val old = runtime.messages[index]
            val content: MutableMap<String, Any?> = LinkedHashMap(old.content ?: emptyMap())
            content["cardData"] = linkedMapOf("type" to "agent_request", "requestId" to "old", "status" to "pending")
            runtime.messages[index] = old.copyWith(content = content)
            coordinator.replaceConversationSnapshot(
                conversationId = 2018,
                mode = CHAT_RUNTIME_MODE_AGENT,
                messages = if (preserveLive) {
                    emptyList()
                } else {
                    runtime.messages.map {
                        if (it.id == "old") it.copyWith(streamMeta = linkedMapOf("parentTaskId" to "old-turn")) else it
                    }
                },
                isAiResponding = true,
                preserveLiveStreamingState = preserveLive,
            )
        }
        val byId = runtime.messages.associateBy { it.id }
        assertEquals("cancelled", byId["old"]!!.cardData!!["status"])
        assertEquals(true, byId["old"]!!.cardData!!["interactionUnavailable"])
        assertEquals("preserve", byId["old"]!!.content!!["extra"])
        assertEquals("accepted", byId["answered"]!!.cardData!!["status"])
        assertEquals("pending", byId["current"]!!.cardData!!["status"])
        assertNull(byId["current"]!!.cardData!!["interactionUnavailable"])
    }

    @Test
    fun `terminal historical requests stay closed during active restore false`() =
        terminalHistoricalRequestsStayClosed(false)

    @Test
    fun `terminal historical requests stay closed during active restore true`() =
        terminalHistoricalRequestsStayClosed(true)

    @Test
    fun `keeps a live ACP request card pending during an active snapshot`() {
        val conversationId = 2007
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)

        coordinator.replaceConversationSnapshot(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            messages = listOf(
                ChatMessage(
                    id = "request-card-live",
                    type = 2,
                    user = 3,
                    content = linkedMapOf(
                        "cardData" to linkedMapOf(
                            "type" to "agent_request",
                            "requestId" to "request-live",
                            "status" to "pending",
                            "requestKind" to "user_input",
                        ),
                    ),
                ),
            ),
            isAiResponding = true,
            isExecutingTask = true,
        )

        assertEquals("pending", runtime.messages.single().cardData?.get("status"))
        assertNull(runtime.messages.single().cardData?.get("interactionUnavailable"))
    }

    @Test
    fun `binds ACP events to one session as well as one turn`() {
        val conversationId = 43
        applyAcp(conversationId, "turn/started", turnId = "turn-current", sessionId = "session-current")

        val runtime = runtimeFor(conversationId)
        assertEquals("session-current", runtime.activeAcpSessionId)
        assertEquals(
            CHAT_RUNTIME_MODE_AGENT,
            coordinator.modeForAcpEvent(conversationId = conversationId, sessionId = "session-current"),
        )

        applyAcp(
            conversationId,
            "session/update",
            turnId = "turn-stale",
            sessionId = "session-old",
            params = messageChunk("stale-message", "stale"),
        )

        assertTrue(runtime.messages.isEmpty())

        runtime.activeAcpTurnId = null
        runtime.currentDispatchTurnId = "local-new-turn"
        runtime.isAiResponding = true
        applyAcp(
            conversationId,
            "session/update",
            sessionId = "session-next",
            turnId = "late-old-turn",
            params = linkedMapOf("delta" to "new session"),
        )
        assertEquals("session-current", runtime.activeAcpSessionId)

        applyAcp(conversationId, "turn/started", turnId = "turn-next", sessionId = "session-next")
        assertEquals("session-next", runtime.activeAcpSessionId)
    }

    @Test
    fun `does not let a completed old session reclaim a new Xiaowan turn`() {
        val conversationId = 44
        applyAcp(conversationId, "turn/started", turnId = "turn-xiaowan-old", sessionId = "session-xiaowan-old")
        completePrompt(conversationId, turnId = "turn-xiaowan-old", sessionId = "session-xiaowan-old")

        coordinator.primeAcpThinking(
            taskId = "local-xiaowan-new",
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
        )
        applyAcp(
            conversationId,
            "session/update",
            turnId = "turn-xiaowan-old",
            sessionId = "session-xiaowan-old",
            params = messageChunk("late-old-message", "旧会话延迟输出"),
        )

        val runtime = runtimeFor(conversationId)
        assertEquals("session-xiaowan-old", runtime.activeAcpSessionId)
        assertTrue(runtime.messages.none { it.text == "旧会话延迟输出" })

        applyAcp(conversationId, "turn/started", turnId = "turn-xiaowan-new", sessionId = "session-xiaowan-new")
        assertEquals("session-xiaowan-new", runtime.activeAcpSessionId)
        assertEquals("turn-xiaowan-new", runtime.activeAcpTurnId)
    }

    @Test
    fun `ignores a stale private terminal event without claiming the current turn`() {
        val conversationId = 45
        applyAcp(conversationId, "turn/started", turnId = "turn-current", sessionId = "session-current")

        val result = coordinator.applyAgentEvent(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            event = acpEvent("turn/completed", turnId = "turn-old", sessionId = "session-current"),
        )
        val runtime = runtimeFor(conversationId)

        assertFalse(result.handled)
        assertFalse(result.affectsActiveTurn)
        assertEquals("turn-current", runtime.activeAcpTurnId)
        assertTrue(runtime.isAiResponding)
        assertFalse(runtime.acpTurnToRunIds.keys.contains("session-current:turn-old"))
    }

    @Test
    fun `marks an event from a rejected session as not current-turn-owned`() {
        val conversationId = 46
        applyAcp(conversationId, "turn/started", turnId = "turn-current", sessionId = "session-current")

        val result = coordinator.applyAgentEvent(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            event = acpEvent(
                "session/update",
                turnId = "turn-old",
                sessionId = "session-old",
                params = messageChunk(null, "旧输出", typed = true),
            ),
        )

        assertFalse(result.handled)
        assertFalse(result.affectsActiveTurn)
    }

    @Test
    fun `rejects a new unscoped ACP turn without host admission`() {
        val conversationId = 47
        coordinator.beginAcpTurn(
            taskId = "local-reservation",
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
        )

        val result = coordinator.applyAgentEvent(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            event = linkedMapOf(
                "method" to "turn/started",
                "turnId" to "unscoped-new-turn",
                "params" to linkedMapOf("turnId" to "unscoped-new-turn"),
            ),
        )

        assertFalse(result.handled)
        assertFalse(result.affectsActiveTurn)
        assertNull(runtimeFor(conversationId).activeAcpTurnId)
    }

    @Test
    fun `keeps ACP turns isolated by conversation and finalizes them`() {
        val firstConversation = 2101
        val secondConversation = 2102
        coordinator.beginAcpTurn(taskId = "turn-first", conversationId = firstConversation, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.beginAcpTurn(taskId = "turn-second", conversationId = secondConversation, mode = CHAT_RUNTIME_MODE_AGENT)
        applyAcp(
            firstConversation,
            "session/update",
            turnId = "turn-first",
            params = messageChunk("message-first", "第一条回复"),
        )
        applyAcp(
            secondConversation,
            "session/update",
            turnId = "turn-second",
            params = messageChunk("message-second", "第二条回复"),
        )
        completePrompt(firstConversation, turnId = "turn-first", params = linkedMapOf("status" to "completed"))

        val first = runtimeFor(firstConversation)
        val second = runtimeFor(secondConversation)
        assertEquals("第一条回复", first.messages.single().text)
        assertFalse(first.isAiResponding)
        assertEquals("第二条回复", second.messages.single().text)
        assertTrue(second.isAiResponding)
    }

    @Test
    fun `routes a session-only background reply to its original conversation after chat switching`() {
        val firstConversation = 2104
        val secondConversation = 2105
        val firstSession = "session-first-background"
        val secondSession = "session-current-visible"

        val first = coordinator.debugEnsureRuntimeState(
            conversationId = firstConversation,
            mode = CHAT_RUNTIME_MODE_AGENT,
            initialMessages = listOf(ChatMessage.userMessage("请先整理第一份资料", id = "first-user")),
        )
        val second = coordinator.debugEnsureRuntimeState(
            conversationId = secondConversation,
            mode = CHAT_RUNTIME_MODE_AGENT,
            initialMessages = listOf(ChatMessage.userMessage("我现在查看第二份资料", id = "second-user")),
        )
        applyAcp(firstConversation, "turn/started", turnId = "turn-first-background", sessionId = firstSession)
        applyAcp(secondConversation, "turn/started", turnId = "turn-second-visible", sessionId = secondSession)

        val owningConversation = coordinator.conversationIdForAcpEvent(sessionId = firstSession)
        assertEquals(firstConversation, owningConversation)
        coordinator.applyAgentEvent(
            conversationId = owningConversation!!,
            mode = CHAT_RUNTIME_MODE_AGENT,
            event = acpEvent(
                "session/update",
                turnId = "turn-first-background",
                sessionId = firstSession,
                params = messageChunk("first-background-answer", "第一份资料已整理完成", typed = true),
            ),
        )

        assertContainsAll(first.messages.map { it.text }, listOf("请先整理第一份资料", "第一份资料已整理完成"))
        assertEquals(listOf("我现在查看第二份资料"), second.messages.map { it.text })
        assertEquals(firstSession, first.activeAcpSessionId)
        assertEquals(secondSession, second.activeAcpSessionId)
    }

    @Test
    fun `a cancelled prompt leaves the next user prompt intact when an old terminal event arrives late`() {
        val conversationId = 2106
        val firstTask = "local-first-task"
        val firstTurn = "turn-first-cancelled"
        val firstSession = "session-first-cancelled"
        val secondTask = "local-second-task"
        val secondTurn = "turn-second-active"
        val secondSession = "session-second-active"
        val runtime = coordinator.debugEnsureRuntimeState(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            initialMessages = listOf(ChatMessage.userMessage("先分析第一件事", id = "first-user")),
        )

        coordinator.beginAcpTurn(taskId = firstTask, conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        applyAcp(conversationId, "turn/started", turnId = firstTurn, sessionId = firstSession)
        applyAcp(
            conversationId,
            "session/update",
            turnId = firstTurn,
            sessionId = firstSession,
            params = messageChunk("first-answer", "第一件事的部分结果", typed = true),
        )
        completePrompt(
            conversationId,
            turnId = firstTurn,
            sessionId = firstSession,
            params = linkedMapOf("status" to "completed", "stopReason" to "cancelled"),
        )
        assertFalse(runtime.isAiResponding)

        runtime.messages.add(0, ChatMessage.userMessage("改为处理第二件事", id = "second-user"))
        coordinator.beginAcpTurn(taskId = secondTask, conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        applyAcp(conversationId, "turn/started", turnId = secondTurn, sessionId = secondSession)

        applyAcp(
            conversationId,
            "turn/completed",
            turnId = firstTurn,
            sessionId = firstSession,
            params = linkedMapOf("status" to "completed", "stopReason" to "cancelled"),
        )
        assertTrue(runtime.isAiResponding)
        assertEquals(secondTurn, runtime.activeAcpTurnId)
        assertEquals(secondSession, runtime.activeAcpSessionId)

        applyAcp(
            conversationId,
            "session/update",
            turnId = secondTurn,
            sessionId = secondSession,
            params = messageChunk("second-answer", "第二件事已完成", typed = true),
        )
        completePrompt(conversationId, turnId = secondTurn, sessionId = secondSession)

        assertContainsAllInOrder(
            runtime.messages.reversed().map { it.text },
            listOf("先分析第一件事", "第一件事的部分结果", "改为处理第二件事", "第二件事已完成"),
        )
        assertFalse(runtime.isAiResponding)
        assertNull(runtime.activeAcpTurnId)
    }

    // --- for nextSession x lateStopReason x lateHasTurnId --------------------

    private fun officialPromptCancellationPreservesHistory(
        nextSession: String,
        lateStopReason: String,
        lateHasTurnId: Boolean,
    ) = runBlocking {
        val conversationId = 2110
        val runtime = coordinator.debugEnsureRuntimeState(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            initialMessages = listOf(ChatMessage.userMessage("请整理第一份资料", id = "first-user")),
        )
        coordinator.beginAcpTurn(taskId = "first-request", conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        assertTrue(
            coordinator.bindAcpSession(
                taskId = "first-request",
                conversationId = conversationId,
                mode = CHAT_RUNTIME_MODE_AGENT,
                sessionId = "first-session",
            ),
        )
        applyAcp(
            conversationId,
            "session/update",
            turnId = "first-turn",
            sessionId = "first-session",
            hostAssignedTurn = true,
            params = messageChunk("first-answer", "第一份资料的部分结果", typed = true),
        )
        assertEquals("first-turn", runtime.activeAcpTurnId)
        coordinator.applyAcpPromptResponse(
            taskId = "first-request",
            conversationId = conversationId,
            sessionId = "first-session",
            turnId = "first-turn",
            stopReason = "cancelled",
        )
        assertFalse(runtime.isAiResponding)

        runtime.messages.add(0, ChatMessage.userMessage("改为处理第二份资料", id = "second-user"))
        coordinator.beginAcpTurn(taskId = "second-request", conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        assertTrue(
            coordinator.bindAcpSession(
                taskId = "second-request",
                conversationId = conversationId,
                mode = CHAT_RUNTIME_MODE_AGENT,
                sessionId = nextSession,
            ),
        )
        val lateResult = coordinator.applyAcpPromptResponse(
            taskId = "first-request",
            conversationId = conversationId,
            sessionId = "first-session",
            turnId = if (lateHasTurnId) "first-turn" else null,
            stopReason = lateStopReason,
            error = if (lateStopReason == "error") "Old ACP transport disconnected" else null,
        )
        assertFalse(lateResult.handled)
        assertTrue(runtime.isAiResponding)
        assertEquals(nextSession, runtime.activeAcpSessionId)
        assertTrue(
            coordinator.isTaskActive(taskId = "second-request", conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT),
        )

        applyAcp(
            conversationId,
            "session/update",
            turnId = "second-turn",
            sessionId = nextSession,
            hostAssignedTurn = true,
            params = messageChunk("second-answer", "第二份资料已完成", typed = true),
        )
        val lateAfterOutput = coordinator.applyAcpPromptResponse(
            taskId = "first-request",
            conversationId = conversationId,
            sessionId = "first-session",
            turnId = if (lateHasTurnId) "first-turn" else null,
            stopReason = lateStopReason,
            error = if (lateStopReason == "error") "Old ACP transport disconnected" else null,
        )
        assertFalse(lateAfterOutput.handled)
        assertTrue(runtime.isAiResponding)
        assertEquals("second-turn", runtime.activeAcpTurnId)
        coordinator.applyAcpPromptResponse(
            taskId = "second-request",
            conversationId = conversationId,
            sessionId = nextSession,
            turnId = "second-turn",
            stopReason = "end_turn",
        )
        assertFalse(runtime.isAiResponding)
        val texts = runtime.messages.reversed().map { it.text }
        assertContainsAllInOrder(
            texts,
            listOf("请整理第一份资料", "第一份资料的部分结果", "改为处理第二份资料", "第二份资料已完成"),
        )
        assertEquals(1, runtime.messages.count { it.id == "first-user" })
        assertEquals(1, runtime.messages.count { it.id == "second-user" })
        coordinator.flushPendingPersistence(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        val saved = fixture.history.callsTo("replaceConversationMessages").lastOrNull()
        assertNotNull(saved)
        assertEquals(conversationId, saved!!.arguments["conversationId"])
        val savedTexts = (saved.arguments["messages"] as List<*>).map { message ->
            ((message as Map<*, *>)["content"] as? Map<*, *>)?.get("text")
        }
        assertContainsAll(savedTexts, texts.filter { it?.isNotEmpty() == true })
    }

    @Test
    fun `official prompt cancellation preserves history- next=first-session late=cancelled turnId=true`() =
        officialPromptCancellationPreservesHistory("first-session", "cancelled", true)

    @Test
    fun `official prompt cancellation preserves history- next=first-session late=cancelled turnId=false`() =
        officialPromptCancellationPreservesHistory("first-session", "cancelled", false)

    @Test
    fun `official prompt cancellation preserves history- next=first-session late=error turnId=true`() =
        officialPromptCancellationPreservesHistory("first-session", "error", true)

    @Test
    fun `official prompt cancellation preserves history- next=first-session late=error turnId=false`() =
        officialPromptCancellationPreservesHistory("first-session", "error", false)

    @Test
    fun `official prompt cancellation preserves history- next=first-session late=end_turn turnId=true`() =
        officialPromptCancellationPreservesHistory("first-session", "end_turn", true)

    @Test
    fun `official prompt cancellation preserves history- next=first-session late=end_turn turnId=false`() =
        officialPromptCancellationPreservesHistory("first-session", "end_turn", false)

    @Test
    fun `official prompt cancellation preserves history- next=second-session late=cancelled turnId=true`() =
        officialPromptCancellationPreservesHistory("second-session", "cancelled", true)

    @Test
    fun `official prompt cancellation preserves history- next=second-session late=cancelled turnId=false`() =
        officialPromptCancellationPreservesHistory("second-session", "cancelled", false)

    @Test
    fun `official prompt cancellation preserves history- next=second-session late=error turnId=true`() =
        officialPromptCancellationPreservesHistory("second-session", "error", true)

    @Test
    fun `official prompt cancellation preserves history- next=second-session late=error turnId=false`() =
        officialPromptCancellationPreservesHistory("second-session", "error", false)

    @Test
    fun `official prompt cancellation preserves history- next=second-session late=end_turn turnId=true`() =
        officialPromptCancellationPreservesHistory("second-session", "end_turn", true)

    @Test
    fun `official prompt cancellation preserves history- next=second-session late=end_turn turnId=false`() =
        officialPromptCancellationPreservesHistory("second-session", "end_turn", false)
}
