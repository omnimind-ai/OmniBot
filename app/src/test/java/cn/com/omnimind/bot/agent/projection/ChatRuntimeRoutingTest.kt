package cn.com.omnimind.bot.agent.projection

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Port of `ui/test/features/home/pages/chat/chat_runtime_view_and_routing_test.dart`
 * plus the coordinator-backed tests deferred from the reducer port
 * (`ui/test/services/agent_event_reducer_test.dart` lines 2926-2975 and
 * 5545-5575).
 *
 * Kotlin has no read-only live view: surfaces get immutable
 * [ChatRuntimeSnapshot]s, so the Dart "view rejects writes" assertions become
 * "snapshots are detached copies", and Dart `events.add(e)` becomes
 * [ChatConversationRuntimeCoordinator.routeAgentEvent].
 */
class ChatRuntimeRoutingTest {
    private lateinit var fixture: ChatRuntimeTestFixture
    private val coordinator get() = fixture.coordinator

    @Before
    fun setUp() {
        fixture = ChatRuntimeTestFixture()
    }

    @After
    fun tearDown() {
        coordinator.resetForTest()
    }

    private fun messageChunk(
        turnId: String,
        sessionId: String,
        text: String,
        conversationId: Int? = null,
        messageId: String = "message-1",
    ): Map<String, Any?> {
        val event = linkedMapOf<String, Any?>()
        if (conversationId != null) event["conversationId"] = conversationId
        event["sessionId"] = sessionId
        event["allowImplicitTurnAdmission"] = true
        event["agentId"] = "xiaowan-acp"
        event["turnId"] = turnId
        event["message"] = linkedMapOf(
            "method" to "session/update",
            "params" to linkedMapOf(
                "turnId" to turnId,
                "sessionId" to sessionId,
                "update" to linkedMapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "messageId" to messageId,
                    "content" to linkedMapOf("text" to text),
                ),
            ),
        )
        return event
    }

    private fun pageContext(
        activeMode: String = CHAT_RUNTIME_MODE_AGENT,
        agentConversationId: Int? = null,
        normalConversationId: Int? = null,
        remote: ChatRuntimeRemoteRoutingContext? = null,
    ) = ChatRuntimeRoutingContext.page(
        activeMode = activeMode,
        conversationIdsByMode = mapOf(
            CHAT_RUNTIME_MODE_AGENT to agentConversationId,
            CHAT_RUNTIME_MODE_NORMAL to normalConversationId,
            CHAT_RUNTIME_MODE_OPENCLAW to null,
        ),
        remote = remote,
    )

    private fun snapshot(conversationId: Int, mode: String) = coordinator.snapshotFor(conversationId, mode)

    private fun assistantRows(conversationId: Int, mode: String = CHAT_RUNTIME_MODE_AGENT) =
        snapshot(conversationId, mode)!!.messages.filter { it.user == 2 }

    // ------------------------------------------------- read-only runtime view

    @Test
    fun `snapshots are immutable copies of the runtime`() {
        val view = coordinator.ensureRuntime(
            conversationId = 7101,
            mode = CHAT_RUNTIME_MODE_AGENT,
            initialMessages = listOf(ChatMessage.userMessage("hello", id = "u1")),
        )
        // Dart: the view is cached per runtime. Kotlin: equal snapshots.
        assertEquals(view, coordinator.snapshotFor(7101, CHAT_RUNTIME_MODE_AGENT))
        assertEquals("u1", view.messages.single().id)

        // Mutating a snapshot's collections (only reachable via a cast) never
        // reaches the runtime: the snapshot is a detached copy.
        runCatching { (view.messages as MutableList<ChatMessage>).add(ChatMessage.userMessage("x")) }
        runCatching { (view.messages as MutableList<ChatMessage>).clear() }
        @Suppress("UNCHECKED_CAST")
        runCatching { (view.currentAiMessages as MutableMap<String, String>)["task"] = "text" }
        val fresh = coordinator.snapshotFor(7101, CHAT_RUNTIME_MODE_AGENT)!!
        assertEquals(listOf("u1"), fresh.messages.map { it.id })
        assertTrue(fresh.currentAiMessages.isEmpty())

        // Later commands never change an earlier snapshot.
        val before = coordinator.snapshotFor(7101, CHAT_RUNTIME_MODE_AGENT)!!
        coordinator.insertRuntimeMessage(7101, CHAT_RUNTIME_MODE_AGENT, ChatMessage.userMessage("y", id = "u2"))
        coordinator.updateRuntimePresentation(7101, CHAT_RUNTIME_MODE_AGENT, isAiResponding = true)
        assertEquals(listOf("u1"), before.messages.map { it.id })
        assertFalse(before.isAiResponding)
        val after = coordinator.snapshotFor(7101, CHAT_RUNTIME_MODE_AGENT)!!
        assertEquals(listOf("u2", "u1"), after.messages.map { it.id })
        assertTrue(after.isAiResponding)
    }

    @Test
    fun `message commands are the write path and advance the row revision`() {
        coordinator.ensureRuntime(conversationId = 7102, mode = CHAT_RUNTIME_MODE_NORMAL)
        val mode = CHAT_RUNTIME_MODE_NORMAL
        fun ids() = snapshot(7102, mode)!!.messages.map { it.id }
        fun revision() = snapshot(7102, mode)!!.lastMutationRevision
        val startRevision = revision()
        var notifications = 0
        fun <T> command(block: () -> T): T {
            val beforeRevision = revision()
            val result = block()
            if (revision() > beforeRevision) notifications += 1
            return result
        }
        fun insert(id: String, index: Int = 0) = command {
            coordinator.insertRuntimeMessage(7102, mode, ChatMessage.userMessage(id, id = id), index = index)
        }

        insert("a")
        insert("b")
        insert("c")
        assertEquals(listOf("c", "b", "a"), ids())

        // An existing id is replaced in place, as the list always did.
        command { coordinator.insertRuntimeMessage(7102, mode, ChatMessage.assistantMessage("updated", id = "b")) }
        assertEquals(listOf("c", "b", "a"), ids())
        assertEquals("updated", snapshot(7102, mode)!!.messages[1].text)

        assertTrue(
            command {
                coordinator.replaceRuntimeMessage(7102, mode, "a", ChatMessage.assistantMessage("A", id = "a"))
            },
        )
        assertEquals("A", snapshot(7102, mode)!!.messages.last().text)
        assertFalse(
            command {
                coordinator.replaceRuntimeMessage(7102, mode, "missing", ChatMessage.userMessage("x"))
            },
        )

        command { coordinator.removeLeadingRuntimeMessages(7102, mode, count = 1) }
        assertEquals(listOf("b", "a"), ids())
        command { coordinator.appendRuntimeMessages(7102, mode, listOf(ChatMessage.userMessage("z", id = "z"))) }
        assertEquals(listOf("b", "a", "z"), ids())
        command { coordinator.removeRuntimeMessages(7102, mode, listOf("a", "z")) }
        assertEquals(listOf("b"), ids())
        command { coordinator.replaceRuntimeMessages(7102, mode, emptyList()) }
        assertTrue(snapshot(7102, mode)!!.messages.isEmpty())
        assertEquals(9, notifications)
        assertEquals(9, revision() - startRevision)
    }

    @Test
    fun `presentation commands update only the addressed runtime`() {
        coordinator.ensureRuntime(conversationId = 7103, mode = CHAT_RUNTIME_MODE_NORMAL)
        coordinator.ensureRuntime(conversationId = 7103, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.updateRuntimePresentation(
            conversationId = 7103,
            mode = CHAT_RUNTIME_MODE_NORMAL,
            isAiResponding = true,
            deepThinkingContent = "thinking",
            chatIslandDisplayLayer = ChatIslandDisplayLayer.TOOLS,
        )
        coordinator.setRuntimeDispatchTurnId(7103, CHAT_RUNTIME_MODE_NORMAL, turnId = "local-1")
        val normal = snapshot(7103, CHAT_RUNTIME_MODE_NORMAL)!!
        val agent = snapshot(7103, CHAT_RUNTIME_MODE_AGENT)!!
        assertTrue(normal.isAiResponding)
        assertEquals("thinking", normal.deepThinkingContent)
        assertEquals(ChatIslandDisplayLayer.TOOLS, normal.chatIslandDisplayLayer)
        assertEquals("local-1", normal.currentDispatchTurnId)
        assertFalse(agent.isAiResponding)
        assertNull(agent.currentDispatchTurnId)
    }

    // ------------------------------------------------------ runtime event route

    @Test
    fun `projects events only while a surface is attached`() {
        coordinator.beginAcpTurn(taskId = "turn-1", conversationId = 7201, mode = CHAT_RUNTIME_MODE_AGENT)
        assertNull(
            coordinator.routeAgentEvent(
                messageChunk(conversationId = 7201, turnId = "turn-1", sessionId = "session-1", text = "ignored"),
            ),
        )
        assertNotNull(snapshot(7201, CHAT_RUNTIME_MODE_AGENT))
        assertTrue(assistantRows(7201).isEmpty())

        val outcomes = ArrayList<ChatRuntimeEventOutcome>()
        val host = coordinator.attachEventHost(
            context = { pageContext(agentConversationId = 7201) },
            onOutcome = { outcomes.add(it) },
        )
        coordinator.routeAgentEvent(
            messageChunk(conversationId = 7201, turnId = "turn-1", sessionId = "session-1", text = "projected"),
        )
        assertEquals(1, outcomes.size)
        assertEquals(7201, outcomes.single().conversationId)
        assertEquals(CHAT_RUNTIME_MODE_AGENT, outcomes.single().mode)
        assertTrue(outcomes.single().result.handled)
        assertTrue(assistantRows(7201).single().text!!.contains("projected"))

        host.detach()
        assertNull(
            coordinator.routeAgentEvent(
                messageChunk(
                    conversationId = 7201,
                    turnId = "turn-1",
                    sessionId = "session-1",
                    text = " after detach",
                    messageId = "message-2",
                ),
            ),
        )
        assertEquals(1, outcomes.size)
        assertEquals(1, assistantRows(7201).size)
    }

    @Test
    fun `applies an event once and delivers it to every surface`() {
        coordinator.beginAcpTurn(taskId = "turn-a", conversationId = 7202, mode = CHAT_RUNTIME_MODE_AGENT)
        val pageOutcomes = ArrayList<ChatRuntimeEventOutcome>()
        val sheetOutcomes = ArrayList<ChatRuntimeEventOutcome>()
        coordinator.attachEventHost(
            context = { pageContext(agentConversationId = 7202) },
            onOutcome = { pageOutcomes.add(it) },
        )
        coordinator.attachEventHost(
            context = { ChatRuntimeRoutingContext.dispatchScoped(conversationId = 9999, mode = "command_overlay") },
            onOutcome = { sheetOutcomes.add(it) },
        )
        val outcome = coordinator.routeAgentEvent(
            messageChunk(conversationId = 7202, turnId = "turn-a", sessionId = "session-a", text = "once"),
        )
        assertEquals(CHAT_RUNTIME_MODE_AGENT, outcome?.mode)
        assertEquals(1, pageOutcomes.size)
        assertEquals(1, sheetOutcomes.size)
        assertNull(snapshot(9999, "command_overlay"))
    }

    @Test
    fun `resolves an event without a host conversation id by identity`() {
        coordinator.beginAcpTurn(taskId = "turn-bg", conversationId = 7203, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.bindAcpSession(
            taskId = "turn-bg",
            conversationId = 7203,
            mode = CHAT_RUNTIME_MODE_AGENT,
            sessionId = "session-bg",
        )
        coordinator.attachEventHost(
            // The visible conversation is a different one.
            context = { pageContext(agentConversationId = 1) },
            onOutcome = {},
        )
        val outcome = coordinator.routeAgentEvent(
            messageChunk(turnId = "turn-bg", sessionId = "session-bg", text = "background"),
        )
        assertEquals(7203, outcome?.conversationId)
        assertEquals(CHAT_RUNTIME_MODE_AGENT, outcome?.mode)
    }

    @Test
    fun `drops identity-less events that no owner can claim`() {
        coordinator.attachEventHost(
            context = { pageContext(agentConversationId = 7204) },
            onOutcome = {},
        )
        val unknownSession = coordinator.routeAgentEvent(
            messageChunk(turnId = "stray", sessionId = "stray", text = "x"),
        )
        assertNull(unknownSession)
        assertNull(snapshot(7204, CHAT_RUNTIME_MODE_AGENT))
        // An identity-less error is the one shape that falls back to the
        // visible Agent conversation.
        val error = coordinator.routeAgentEvent(
            linkedMapOf(
                "method" to "error",
                "message" to linkedMapOf(
                    "method" to "error",
                    "params" to linkedMapOf("error" to "boom"),
                ),
            ),
        )
        assertEquals(7204, error?.conversationId)
    }

    @Test
    fun `a dispatch-scoped surface claims only its own conversation`() {
        coordinator.beginAcpTurn(taskId = "sheet-turn", conversationId = 7205, mode = "command_overlay")
        var inFlight = true
        coordinator.attachEventHost(
            context = {
                if (inFlight) {
                    ChatRuntimeRoutingContext.dispatchScoped(conversationId = 7205, mode = "command_overlay")
                } else {
                    null
                }
            },
            onOutcome = {},
        )
        assertNull(coordinator.routeAgentEvent(messageChunk(turnId = "sheet-turn", sessionId = "s", text = "no id")))
        assertNull(
            coordinator.routeAgentEvent(
                messageChunk(conversationId = 7206, turnId = "sheet-turn", sessionId = "s", text = "other"),
            ),
        )
        val claimed = coordinator.routeAgentEvent(
            messageChunk(conversationId = 7205, turnId = "sheet-turn", sessionId = "s", text = "mine"),
        )
        assertEquals("command_overlay", claimed?.mode)
        assertEquals(true, claimed?.result?.handled)
        inFlight = false
        assertNull(
            coordinator.routeAgentEvent(
                messageChunk(conversationId = 7205, turnId = "sheet-turn", sessionId = "s", text = "late"),
            ),
        )
    }

    @Test
    fun `promotes the active remote thread into the visible runtime`() {
        val threadId = "remote-thread-1"
        val runtimeId = remoteAgentRuntimeIdForThread(threadId)
        val pending = ChatMessage.userMessage("queued", id = "pending-u")
        val outcomes = ArrayList<ChatRuntimeEventOutcome>()
        coordinator.attachEventHost(
            context = {
                pageContext(
                    agentConversationId = null,
                    remote = ChatRuntimeRemoteRoutingContext(
                        activeThreadId = threadId,
                        agentFallbackMessages = listOf(pending),
                        agentConversation = ConversationPayload.create(
                            id = 0,
                            mode = ConversationModes.AGENT,
                            title = "Remote",
                            status = 0,
                            messageCount = 1,
                            createdAt = 1,
                            updatedAt = 1,
                        ),
                    ),
                )
            },
            onOutcome = { outcomes.add(it) },
        )
        coordinator.routeAgentEvent(
            linkedMapOf(
                "method" to "error",
                "threadId" to threadId,
                "message" to linkedMapOf(
                    "method" to "error",
                    "params" to linkedMapOf("error" to "boom"),
                ),
            ),
        )
        assertEquals(runtimeId, outcomes.single().conversationId)
        assertEquals(threadId, outcomes.single().promotedRemoteThreadId)
        val runtime = snapshot(runtimeId, CHAT_RUNTIME_MODE_AGENT)!!
        assertTrue(runtime.messages.any { it.id == "pending-u" })
        assertEquals(runtimeId, runtime.conversation?.get("id"))
        assertEquals("Remote", runtime.conversation?.get("title"))
        assertTrue(coordinator.isEphemeralRuntime(runtimeId, CHAT_RUNTIME_MODE_AGENT))
    }

    // ------------------------------- deferred from the reducer test port

    /** agent_event_reducer_test.dart: 'routes a session-only event to its background conversation'. */
    @Test
    fun `routes a session-only event to its background conversation`() {
        val first = coordinator.debugEnsureRuntimeState(conversationId = 8101, mode = CHAT_RUNTIME_MODE_AGENT)
        val second = coordinator.debugEnsureRuntimeState(conversationId = 8102, mode = CHAT_RUNTIME_MODE_AGENT)
        first.acceptsAcpEvent(sessionId = "session-background-1", allowSessionAdmission = true)
        second.acceptsAcpEvent(sessionId = "session-background-2", allowSessionAdmission = true)

        assertEquals(8102, coordinator.conversationIdForAcpEvent(sessionId = "session-background-2"))

        coordinator.discardConversationRuntime(conversationId = 8101, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.discardConversationRuntime(conversationId = 8102, mode = CHAT_RUNTIME_MODE_AGENT)
    }

    /** agent_event_reducer_test.dart: 'interrupts every running tool in a parallel tool batch'. */
    @Test
    fun `interrupts every running tool in a parallel tool batch`() {
        val parallelRuntime = coordinator.debugEnsureRuntimeState(conversationId = 8103, mode = CHAT_RUNTIME_MODE_AGENT)
            .apply { activeRunId = "run-parallel-tools" }
        parallelRuntime.messages.addAll(
            listOf(
                ChatMessage.cardMessage(
                    linkedMapOf(
                        "type" to "agent_tool_summary",
                        "taskId" to "run-parallel-tools",
                        "status" to "running",
                    ),
                    id = "parallel-tool-1",
                ),
                ChatMessage.cardMessage(
                    linkedMapOf(
                        "type" to "agent_tool_summary",
                        "taskId" to "run-parallel-tools",
                        "status" to "progress",
                    ),
                    id = "parallel-tool-2",
                ),
            ),
        )
        parallelRuntime.activeToolCardId = "parallel-tool-2"

        coordinator.interruptActiveToolCard(conversationId = 8103, mode = CHAT_RUNTIME_MODE_AGENT, summary = "已取消")

        assertEquals(
            listOf("interrupted", "interrupted"),
            parallelRuntime.messages.map { it.cardData?.get("status") },
        )
        assertNull(parallelRuntime.activeToolCardId)
        coordinator.discardConversationRuntime(conversationId = 8103, mode = CHAT_RUNTIME_MODE_AGENT)
    }

    /** agent_event_reducer_test.dart: 'keeps replay delta offsets across matching snapshot replacement'. */
    @Test
    fun `keeps replay delta offsets across matching snapshot replacement`() {
        val conversationId = 420042
        val hydratedMessage = ChatMessage(
            id = "msg-1-agent-message",
            type = 1,
            user = 2,
            content = linkedMapOf("text" to "Hello", "id" to "msg-1-agent-message"),
        )
        val coordinatorRuntime = coordinator.debugEnsureRuntimeState(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            initialMessages = listOf(hydratedMessage),
        )
        coordinatorRuntime.agentReplayDeltaOffsets["msg-1-agent-message"] = 3
        coordinatorRuntime.agentReplayDeltaOffsets["stale-entry"] = 2

        coordinator.replaceConversationSnapshot(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            messages = listOf(hydratedMessage),
        )

        val updatedRuntime = coordinator.debugRuntimeStateFor(conversationId, CHAT_RUNTIME_MODE_AGENT)!!
        assertEquals(3, updatedRuntime.agentReplayDeltaOffsets["msg-1-agent-message"])
        assertFalse(updatedRuntime.agentReplayDeltaOffsets.containsKey("stale-entry"))
    }
}
