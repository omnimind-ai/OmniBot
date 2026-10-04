package cn.com.omnimind.bot.agent.projection

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Port of `ui/test/features/home/pages/chat/chat_conversation_runtime_coordinator_test.dart`,
 * lines 1479-2858. Same inputs, same assertions, same test names as the Dart
 * acceptance tests.
 */
class ChatConversationRuntimeCoordinatorTest2 {
    private lateinit var fixture: ChatRuntimeTestFixture
    private val coordinator get() = fixture.coordinator
    private val recordedMethodCalls get() = fixture.history.calls

    @Before
    fun setUp() {
        fixture = ChatRuntimeTestFixture()
    }

    private fun applyAcp(
        conversationId: Int,
        method: String,
        turnId: String,
        sessionId: String? = null,
        params: Map<String, Any?> = emptyMap(),
        mode: String = CHAT_RUNTIME_MODE_AGENT,
        agentId: String = "xiaowan-acp",
        agentName: String = "小万",
        hostAssignedTurn: Boolean = false,
    ) = fixture.applyAcp(conversationId, method, turnId, sessionId, params, mode, agentId, agentName, hostAssignedTurn)

    private fun completePrompt(
        conversationId: Int,
        turnId: String,
        sessionId: String? = null,
        mode: String = CHAT_RUNTIME_MODE_AGENT,
        params: Map<String, Any?> = emptyMap(),
    ) = fixture.completePrompt(conversationId, turnId, sessionId, mode, params)

    private fun assertIsInt(value: Any?, message: String = "expected int") {
        assertTrue("$message but was $value", value is Int || value is Long)
    }

    private fun num(value: Any?): Long = (value as Number).toLong()

    @Suppress("UNCHECKED_CAST")
    private fun rows(call: RecordedCall): List<Map<String, Any?>> =
        (call.arguments["messages"] as List<*>).map { it as Map<String, Any?> }

    // --- for (legacyMethod) for (stopReason): legacy status cannot terminate the owning prompt

    private val legacyMethods = listOf(
        "state_change",
        "state_update",
        "thread/status/changed",
        "turn/completed",
        "turn/failed",
        "thread/closed",
        "error",
        "legacy:completed",
        "legacy:error",
    )

    private fun legacyStatusCannotTerminateOwningPrompt(legacyMethod: String, stopReason: String) {
        val conversationId = 2110
        val taskId = "status-request"
        val sessionId = "status-session"
        coordinator.beginAcpTurn(taskId = taskId, conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.bindAcpSession(
            taskId = taskId,
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            sessionId = sessionId,
        )
        val runtime = coordinator.debugRuntimeStateFor(conversationId, CHAT_RUNTIME_MODE_AGENT)!!
        for (status in listOf("running", "idle", "failed", "cancelled")) {
            val event: JsonMap = linkedMapOf()
            if (legacyMethod.startsWith("legacy:")) {
                event["kind"] = legacyMethod.split(":").last()
                event["taskId"] = taskId
                event["error"] = "legacy stream failure"
            } else {
                event["method"] = if (legacyMethod.startsWith("state_")) "session/update" else legacyMethod
            }
            val params: JsonMap = linkedMapOf("sessionId" to sessionId)
            if (!legacyMethod.startsWith("state_")) {
                params["status"] = status
                params["willRetry"] = status == "running"
                params["error"] = "legacy status failure"
            } else {
                params["update"] = linkedMapOf(
                    "sessionUpdate" to legacyMethod,
                    "state" to status,
                    "stopReason" to "error",
                    "error" to "legacy status failure",
                )
            }
            event["params"] = params
            coordinator.applyAgentEvent(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT, event = event)
            assertTrue(status, runtime.isAiResponding)
            assertTrue(status, runtime.messages.isEmpty())
            assertTrue(
                status,
                coordinator.isTaskActive(taskId = taskId, conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT),
            )
        }
        val result = coordinator.applyAcpPromptResponse(
            taskId = taskId,
            conversationId = conversationId,
            sessionId = sessionId,
            stopReason = stopReason,
            error = if (stopReason == "error") "real provider failure" else null,
        )
        assertTrue(result.handled)
        assertFalse(runtime.isAiResponding)
        val failures = runtime.messages.filter { it.cardData?.get("title") == "本轮执行失败" }
        assertEquals(if (stopReason == "error") 1 else 0, failures.size)
        val messageCount = runtime.messages.size
        assertFalse(
            coordinator.applyAcpPromptResponse(
                taskId = taskId,
                conversationId = conversationId,
                sessionId = sessionId,
                stopReason = "error",
                error = "late duplicate failure",
            ).handled,
        )
        assertEquals(messageCount, runtime.messages.size)
    }

    /**
     * The Dart file generates one test per (legacyMethod, stopReason) pair:
     * `legacy status cannot terminate the owning prompt: $legacyMethod $stopReason`.
     * Here each pair runs on a fresh fixture and failures are reported by name.
     */
    @Test
    fun `legacy status cannot terminate the owning prompt - all methods and stop reasons`() {
        val failures = ArrayList<String>()
        for (legacyMethod in legacyMethods) {
            for (stopReason in listOf("end_turn", "cancelled", "error")) {
                fixture = ChatRuntimeTestFixture()
                try {
                    legacyStatusCannotTerminateOwningPrompt(legacyMethod, stopReason)
                } catch (error: AssertionError) {
                    failures.add("legacy status cannot terminate the owning prompt: $legacyMethod $stopReason -> ${error.message}")
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `official cancellation after partial output survives reload and duplicate completion`() {
        val id = 2990
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = id, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.beginAcpTurn(taskId = "cancel-request", conversationId = id, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.bindAcpSession(
            taskId = "cancel-request",
            conversationId = id,
            mode = CHAT_RUNTIME_MODE_AGENT,
            sessionId = "cancel-session",
        )
        applyAcp(
            id,
            "session/update",
            turnId = "cancel-turn",
            sessionId = "cancel-session",
            hostAssignedTurn = true,
            params = mapOf(
                "update" to mapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "messageId" to "partial",
                    "content" to mapOf("type" to "text", "text" to "KEEP_PARTIAL"),
                ),
            ),
        )
        coordinator.applyAcpPromptResponse(
            taskId = "cancel-request",
            conversationId = id,
            sessionId = "cancel-session",
            turnId = "cancel-turn",
            stopReason = "cancelled",
        )
        assertFalse(runtime.isAiResponding)
        val restored = runtime.messages.map { ChatMessage.fromJson(it.toJson()) }
        // `buildAgentRunTimelineEntries` is a Flutter UI helper (not ported).
        // Equivalent assertions on its inputs: the restored turn forms exactly
        // one run group (single agentRunId among non-user messages), the group
        // is not failed (no terminal failure card) and is cancelled (an item
        // carries stopReason cancelled/canceled), and KEEP_PARTIAL is a visible
        // (non-process) message of that group.
        val groupIds = restored.mapNotNull { agentRunId(it) }.distinct()
        assertEquals(1, groupIds.size)
        val groupMessages = restored.filter { agentRunId(it) == groupIds.single() }
        assertFalse(
            groupMessages.any {
                val card = it.cardData
                card?.get("type") == "agent_tool_summary" && card["toolType"] == "status" &&
                    card["status"] == "error" && agentRunKind(it) == "error"
            },
        )
        assertTrue(groupMessages.any { it.streamMeta?.get("stopReason") in setOf("cancelled", "canceled") })
        assertTrue(
            groupMessages.any {
                it.text == "KEEP_PARTIAL" && it.cardData?.get("type") != "deep_thinking" &&
                    it.cardData?.get("type") != "agent_tool_summary"
            },
        )
        assertFalse(
            coordinator.applyAcpPromptResponse(
                taskId = "cancel-request",
                conversationId = id,
                sessionId = "cancel-session",
                turnId = "cancel-turn",
                stopReason = "end_turn",
            ).handled,
        )
        assertEquals(
            "cancelled",
            runtime.messages.single { it.text == "KEEP_PARTIAL" }.streamMeta?.get("stopReason"),
        )
    }

    // --- for (stopReason in ['end_turn', 'cancelled', 'error'])

    private fun officialPromptWithoutStreamedOutput(stopReason: String) {
        val conversationId = 2111
        val runtime = coordinator.debugEnsureRuntimeState(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            initialMessages = listOf(ChatMessage.userMessage("执行这个请求", id = "user-no-stream")),
        )
        coordinator.beginAcpTurn(
            taskId = "request-no-stream",
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
        )
        coordinator.bindAcpSession(
            taskId = "request-no-stream",
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            sessionId = "session-no-stream",
        )
        val result = coordinator.applyAcpPromptResponse(
            taskId = "request-no-stream",
            conversationId = conversationId,
            sessionId = "session-no-stream",
            stopReason = stopReason,
        )
        assertTrue(result.handled)
        assertFalse(runtime.isAiResponding)
        assertEquals(1, runtime.messages.count { it.id == "user-no-stream" })
        assertFalse(
            coordinator.isTaskActive(
                taskId = "request-no-stream",
                conversationId = conversationId,
                mode = CHAT_RUNTIME_MODE_AGENT,
            ),
        )
    }

    @Test
    fun `official prompt without streamed output ends its own request - end_turn`() =
        officialPromptWithoutStreamedOutput("end_turn")

    @Test
    fun `official prompt without streamed output ends its own request - cancelled`() =
        officialPromptWithoutStreamedOutput("cancelled")

    @Test
    fun `official prompt without streamed output ends its own request - error`() =
        officialPromptWithoutStreamedOutput("error")

    @Test
    fun `a stale prompt result cannot recreate a discarded conversation runtime`() {
        coordinator.beginAcpTurn(taskId = "discarded-request", conversationId = 2112, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.resetForTest()
        val result = coordinator.applyAcpPromptResponse(
            taskId = "discarded-request",
            conversationId = 2112,
            sessionId = "discarded-session",
            stopReason = "cancelled",
        )
        assertFalse(result.handled)
        assertNull(coordinator.debugRuntimeStateFor(2112, CHAT_RUNTIME_MODE_AGENT))
    }

    @Test
    fun `a background conversation still accepts its own prompt response`() {
        for (id in listOf(2113, 2114)) {
            coordinator.beginAcpTurn(taskId = "request-$id", conversationId = id, mode = CHAT_RUNTIME_MODE_AGENT)
            coordinator.bindAcpSession(
                taskId = "request-$id",
                conversationId = id,
                mode = CHAT_RUNTIME_MODE_AGENT,
                sessionId = "session-$id",
            )
        }
        val result = coordinator.applyAcpPromptResponse(
            taskId = "request-2113",
            conversationId = 2113,
            sessionId = "session-2113",
            stopReason = "end_turn",
        )
        assertTrue(result.handled)
        assertFalse(coordinator.debugRuntimeStateFor(2113, CHAT_RUNTIME_MODE_AGENT)!!.isAiResponding)
        assertTrue(coordinator.isTaskActive(taskId = "request-2114", conversationId = 2114, mode = CHAT_RUNTIME_MODE_AGENT))
        assertTrue(coordinator.debugRuntimeStateFor(2114, CHAT_RUNTIME_MODE_AGENT)!!.isAiResponding)
    }

    @Test
    fun `keeps DSH ACP reasoning interleaved around tool activity`() {
        val conversationId = 2103
        val turnId = "dsh-turn"
        applyAcp(
            conversationId,
            "item/reasoning/delta",
            turnId = turnId,
            agentId = "deepseek-harness-acp",
            agentName = "DeepSeek Harness",
            params = mapOf("itemId" to "thought-1", "delta" to "第一阶段：分析工作区。"),
        )
        applyAcp(
            conversationId,
            "item/started",
            turnId = turnId,
            agentId = "deepseek-harness-acp",
            agentName = "DeepSeek Harness",
            params = mapOf(
                "item" to mapOf(
                    "id" to "tool-1",
                    "type" to "commandExecution",
                    "command" to "pwd",
                    "status" to "running",
                ),
            ),
        )
        applyAcp(
            conversationId,
            "item/reasoning/delta",
            turnId = turnId,
            agentId = "deepseek-harness-acp",
            agentName = "DeepSeek Harness",
            params = mapOf("itemId" to "thought-2", "delta" to "第二阶段：根据结果判断。"),
        )

        val runtime = coordinator.debugRuntimeStateFor(conversationId, CHAT_RUNTIME_MODE_AGENT)!!
        val thinking = runtime.messages.filter { it.cardData?.get("type") == "deep_thinking" }
        assertEquals(2, thinking.size)
        assertEquals(
            listOf("deep_thinking", "agent_tool_summary", "deep_thinking"),
            runtime.messages.reversed().map { it.cardData?.get("type") ?: "assistant_text" },
        )
        assertEquals(
            listOf("第一阶段：分析工作区。", "第二阶段：根据结果判断。"),
            thinking.reversed().map { it.cardData?.get("thinkingContent") },
        )
    }

    @Test
    fun `continuous updates cannot postpone durable history indefinitely`() = runBlocking<Unit> {
        val conversationId = 99112
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        runtime.messages.add(ChatMessage.userMessage("long running synthetic task"))
        for (index in 0 until 5) {
            coordinator.schedulePersistRuntimeConversation(
                conversationId = conversationId,
                mode = CHAT_RUNTIME_MODE_AGENT,
                persistMessages = index == 2,
            )
            fixture.scheduler.advanceBy(100)
        }
        // Commit during continuous output; preserve flags merged into the batch.
        assertTrue(recordedMethodCalls.any { it.method == "replaceConversationMessages" })
        coordinator.flushPendingPersistence(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
    }

    @Test
    fun `an old history snapshot cannot downgrade an officially completed item`() {
        val conversationId = 99202
        val turn = "completed-history-turn"
        coordinator.beginAcpTurn(taskId = turn, conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        applyAcp(
            conversationId,
            "session/update",
            turnId = turn,
            params = mapOf(
                "update" to mapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "messageId" to "reply",
                    "content" to mapOf("text" to "partial "),
                ),
            ),
        )
        val runtime = coordinator.debugRuntimeStateFor(conversationId, CHAT_RUNTIME_MODE_AGENT)!!
        val old = runtime.messages.first { it.user == 2 }
        applyAcp(
            conversationId,
            "session/update",
            turnId = turn,
            params = mapOf(
                "update" to mapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "messageId" to "reply",
                    "content" to mapOf("text" to "complete"),
                ),
            ),
        )
        completePrompt(conversationId, turnId = turn)
        val committed = runtime.messages.first { it.user == 2 }
        for (snapshot in listOf(listOf(old), listOf(old.copyWith(streamMeta = emptyMap())))) {
            coordinator.replaceConversationSnapshot(
                conversationId = conversationId,
                mode = CHAT_RUNTIME_MODE_AGENT,
                messages = snapshot,
            )
            assertEquals("partial complete", runtime.messages.single().text)
            assertEquals("end_turn", runtime.messages.single().streamMeta?.get("stopReason"))
            assertEquals(true, runtime.messages.single().streamMeta?.get("isFinal"))
            assertEquals(false, runtime.hasInFlightTask)
        }
        // A durable completed projection may still supply additional metadata.
        val restored = committed.copyWith(turnUsage = mapOf("inputTokens" to 123))
        coordinator.replaceConversationSnapshot(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            messages = listOf(restored),
        )
        assertEquals(123, runtime.messages.single().turnUsage?.get("inputTokens"))
    }

    @Test
    fun `persistence uses the completed projection after awaiting metadata I-O`() = runBlocking<Unit> {
        val conversationId = 99201
        val turn = "metadata-race-turn"
        // Dart: the mock handler holds the first `updateConversation` call.
        val (entered, release) = fixture.history.hold("updateConversation")
        coordinator.beginAcpTurn(taskId = turn, conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        applyAcp(
            conversationId,
            "session/update",
            turnId = turn,
            params = mapOf(
                "update" to mapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "messageId" to "reply",
                    "content" to mapOf("text" to "partial "),
                ),
            ),
        )
        val saving = coordinator.persistRuntimeConversation(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            persistMessages = true,
        )
        entered.await()
        applyAcp(
            conversationId,
            "session/update",
            turnId = turn,
            params = mapOf(
                "update" to mapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "messageId" to "reply",
                    "content" to mapOf("text" to "complete"),
                ),
            ),
        )
        completePrompt(conversationId, turnId = turn, params = mapOf("stopReason" to "end_turn"))
        val current = coordinator.debugRuntimeStateFor(conversationId, CHAT_RUNTIME_MODE_AGENT)!!
        assertEquals("end_turn", current.messages.first { it.user == 2 }.streamMeta?.get("stopReason"))
        release.complete(Unit)
        saving.await()
        coordinator.flushAllPendingPersistence()
        val write = recordedMethodCalls.first { it.method == "replaceConversationMessages" }
        val answer = rows(write).single { it["user"] == 2 }
        assertEquals("partial complete", (answer["content"] as Map<*, *>)["text"])
        assertEquals(true, (answer["streamMeta"] as Map<*, *>)["isFinal"])
        assertEquals("end_turn", (answer["streamMeta"] as Map<*, *>)["stopReason"])
    }

    @Test
    fun `persists ACP runtime messages back to native history`() = runBlocking<Unit> {
        val conversationId = 2201
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        runtime.messages.add(0, ChatMessage.userMessage("用户输入"))
        applyAcp(
            conversationId,
            "session/update",
            turnId = "turn-persist",
            params = mapOf(
                "update" to mapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "messageId" to "message-persist",
                    "content" to mapOf("text" to "ACP 回复"),
                ),
            ),
        )
        coordinator.flushPendingPersistence(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)

        val replaceCalls = fixture.history.callsTo("replaceConversationMessages")
        assertTrue(replaceCalls.isNotEmpty())
        val args = replaceCalls.last().arguments
        assertEquals(conversationId, args["conversationId"])
        assertEquals(CHAT_RUNTIME_MODE_AGENT, args["mode"])
        assertTrue(rows(replaceCalls.last()).any { (it["content"] as? Map<*, *>)?.get("text") == "ACP 回复" })
    }

    @Test
    fun `partial idle page updates preserve committed messages`() = runBlocking<Unit> {
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = 99111, mode = CHAT_RUNTIME_MODE_AGENT)
        val old = ChatMessage.userMessage("keep original task")
        runtime.messages.add(old)
        coordinator.persistConversationMessageSnapshot(
            conversationId = 99111,
            mode = CHAT_RUNTIME_MODE_AGENT,
            messages = emptyList(),
        ).await()
        assertTrue(runtime.messages.map { it.id }.contains(old.id))
        val calls = fixture.history.callsTo("replaceConversationMessages")
        assertEquals(false, calls.last().arguments["allowHistoryRemoval"])
        assertTrue((calls.last().arguments["messages"] as List<*>).isNotEmpty())
    }

    @Test
    fun `ordinary completion persistence cannot clear an empty runtime history`() = runBlocking<Unit> {
        coordinator.debugEnsureRuntimeState(conversationId = 99110, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.persistRuntimeConversation(
            conversationId = 99110,
            mode = CHAT_RUNTIME_MODE_AGENT,
            persistMessages = true,
        ).await()
        assertTrue(fixture.history.callsTo("replaceConversationMessages").isEmpty())
    }

    @Test
    fun `persists an empty snapshot when the caller owns message replacement`() = runBlocking<Unit> {
        val conversationId = 2200
        coordinator.debugEnsureRuntimeState(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)

        coordinator.persistRuntimeConversation(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            persistMessages = true,
            allowHistoryRemoval = true,
        ).await()

        val replaceCalls = fixture.history.callsTo("replaceConversationMessages")
        assertTrue(replaceCalls.isNotEmpty())
        assertEquals(conversationId, replaceCalls.last().arguments["conversationId"])
        assertTrue((replaceCalls.last().arguments["messages"] as List<*>).isEmpty())
    }

    @Test
    fun `page snapshot preserves admitted prompt timing through history`() = runBlocking<Unit> {
        val conversationId = 2211
        val turnId = "snapshot-timed-request"
        coordinator.beginAcpTurn(taskId = turnId, conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        val runtime = coordinator.debugRuntimeStateFor(conversationId, CHAT_RUNTIME_MODE_AGENT)!!
        val startedAt = runtime.agentEntryStartTimes["prompt:$turnId"]
        assertIsInt(startedAt)
        coordinator.replaceConversationSnapshot(
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            messages = ArrayList(runtime.messages),
            isAiResponding = runtime.isAiResponding,
            currentDispatchTurnId = runtime.currentDispatchTurnId,
            lastAgentTurnId = runtime.lastAgentTurnId,
        )
        applyAcp(
            conversationId,
            "session/update",
            turnId = turnId,
            params = mapOf(
                "update" to mapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "content" to mapOf("type" to "text", "text" to "完成"),
                ),
            ),
        )
        completePrompt(conversationId, turnId = turnId)
        val reply = runtime.messages.single { it.type == 1 && it.user == 2 }
        assertNotNull("endedAt", reply.turnUsage?.get("endedAt"))
        assertTrue(num(reply.turnUsage?.get("endedAt")) >= startedAt!!)
        assertNotNull("durationMs", reply.turnUsage?.get("durationMs"))
        assertTrue(num(reply.turnUsage?.get("durationMs")) >= 0)
        coordinator.flushPendingPersistence(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        val saved = recordedMethodCalls.last { it.method == "replaceConversationMessages" }
        val json = rows(saved).single { it["id"] == reply.id }
        assertEquals(reply.turnUsage, ChatMessage.fromJson(LinkedHashMap(json)).turnUsage)
    }

    @Test
    fun `prompt timing belongs to the final visible reply in a multi-message turn`() {
        val conversationId = 2210
        val turnId = "multi-reply"
        applyAcp(conversationId, "turn/started", turnId = turnId)
        val runtime = coordinator.debugRuntimeStateFor(conversationId, CHAT_RUNTIME_MODE_AGENT)!!
        // Dart DateTime.now(); the coordinator's clock is the manual scheduler.
        runtime.agentEntryStartTimes["prompt:$turnId"] = fixture.scheduler.now - 65000
        for ((messageId, text) in listOf("progress" to "我先检查一下", "answer" to "最终结果")) {
            applyAcp(
                conversationId,
                "session/update",
                turnId = turnId,
                params = mapOf(
                    "update" to mapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "messageId" to messageId,
                        "content" to mapOf("type" to "text", "text" to text),
                    ),
                ),
            )
        }
        coordinator.applyAcpPromptResponse(
            taskId = turnId,
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            stopReason = "end_turn",
            sessionId = null,
        )
        val replies = runtime.messages.filter { it.type == 1 && it.user == 2 }
        assertEquals(2, replies.size)
        val answer = replies.single { it.text == "最终结果" }
        val progress = replies.single { it.text == "我先检查一下" }
        assertNotNull("durationMs", answer.turnUsage?.get("durationMs"))
        assertTrue(num(answer.turnUsage?.get("durationMs")) >= 65000)
        assertIsInt(answer.turnUsage?.get("endedAt"))
        assertNull(progress.turnUsage?.get("endedAt"))
    }

    // --- for (reason in ['end_turn', 'cancelled', 'error'])

    private fun promptTimingFinalizedOnceAndIsolated(reason: String) {
        val conversationId = 2209
        val turnId = "timed-request"
        applyAcp(conversationId, "turn/started", turnId = turnId)
        val runtime = coordinator.debugRuntimeStateFor(conversationId, CHAT_RUNTIME_MODE_AGENT)!!
        runtime.agentEntryStartTimes["prompt:$turnId"] = fixture.scheduler.now - 65000
        applyAcp(
            conversationId,
            "session/update",
            turnId = turnId,
            params = mapOf(
                "update" to mapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "content" to mapOf("type" to "text", "text" to "已有结果"),
                ),
            ),
        )
        assertNull(runtime.messages.last { it.user == 2 }.turnUsage)
        coordinator.applyAcpPromptResponse(
            taskId = turnId,
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            stopReason = reason,
            sessionId = null,
        )
        val reply = runtime.messages.last { it.type == 1 && it.user == 2 }
        assertNotNull("durationMs", reply.turnUsage?.get("durationMs"))
        assertTrue(num(reply.turnUsage?.get("durationMs")) >= 65000)
        assertIsInt(reply.turnUsage?.get("endedAt"))
        val restored = ChatMessage.fromJson(reply.toJson())
        assertEquals(reply.turnUsage, restored.turnUsage)
        applyAcp(conversationId, "turn/started", turnId = "next-request")
        coordinator.applyAcpPromptResponse(
            taskId = turnId,
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
            stopReason = reason,
            sessionId = null,
        )
        assertEquals(restored.turnUsage, runtime.messages.single { it.id == reply.id }.turnUsage)
        assertEquals("next-request", runtime.currentDispatchTurnId)
    }

    @Test
    fun `prompt timing is finalized once and isolated - end_turn`() = promptTimingFinalizedOnceAndIsolated("end_turn")

    @Test
    fun `prompt timing is finalized once and isolated - cancelled`() = promptTimingFinalizedOnceAndIsolated("cancelled")

    @Test
    fun `prompt timing is finalized once and isolated - error`() = promptTimingFinalizedOnceAndIsolated("error")

    @Test
    fun `accepts final ACP turn usage after the turn completion fence`() = runBlocking<Unit> {
        val conversationId = 2202
        val turnId = "turn-late-usage"
        val sessionId = "session-late-usage"
        val messageId = "message-late-usage"

        applyAcp(conversationId, "turn/started", turnId = turnId, sessionId = sessionId)
        applyAcp(
            conversationId,
            "session/update",
            turnId = turnId,
            sessionId = sessionId,
            params = mapOf(
                "update" to mapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "messageId" to messageId,
                    "content" to mapOf("type" to "text", "text" to "最终回复"),
                ),
            ),
        )
        completePrompt(conversationId, turnId = turnId, sessionId = sessionId)
        applyAcp(
            conversationId,
            "session/update",
            turnId = turnId,
            sessionId = sessionId,
            params = mapOf(
                "update" to mapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "messageId" to messageId,
                    "content" to mapOf("type" to "text", "text" to ""),
                    "_meta" to mapOf(
                        "cn.com.omnimind.agent" to mapOf(
                            "usage" to mapOf(
                                "latestPromptTokens" to 16076,
                                "promptTokenThreshold" to 128000,
                                "turnUsage" to mapOf(
                                    "ctx" to 16076,
                                    "in" to 16076,
                                    "out" to 1470,
                                    "cache" to 10770,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val runtime = coordinator.debugRuntimeStateFor(conversationId, CHAT_RUNTIME_MODE_AGENT)!!
        val answer = runtime.messages.single { it.id == "$turnId-$messageId-agent-message" }
        val usage = answer.turnUsage
        assertNotNull(usage)
        assertEquals(setOf("endedAt", "durationMs", "ctx", "in", "out", "cache"), usage!!.keys)
        assertIsInt(usage["endedAt"])
        assertTrue(num(usage["durationMs"]) >= 0)
        assertEquals(16076, usage["ctx"])
        assertEquals(16076, usage["in"])
        assertEquals(1470, usage["out"])
        assertEquals(10770, usage["cache"])
        assertFalse(runtime.isAiResponding)

        coordinator.flushPendingPersistence(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        val replaceCalls = fixture.history.callsTo("replaceConversationMessages")
        assertTrue(replaceCalls.isNotEmpty())
        val persisted = rows(replaceCalls.last()).single { it["id"] == answer.id }
        assertEquals(answer.turnUsage, persisted["turnUsage"])
    }

    @Test
    fun `ignores automatic compaction metadata instead of persisting a private card`() {
        val conversationId = 2203
        val turnId = "turn-compaction-persist"
        val sessionId = "session-compaction-persist"

        applyAcp(conversationId, "turn/started", turnId = turnId, sessionId = sessionId)
        applyAcp(
            conversationId,
            "session/update",
            turnId = turnId,
            sessionId = sessionId,
            params = mapOf(
                "update" to mapOf(
                    "sessionUpdate" to "agent_thought_chunk",
                    "messageId" to "thought-compaction-persist",
                    "content" to mapOf("type" to "text", "text" to ""),
                    "_meta" to mapOf(
                        "cn.com.omnimind.agent" to mapOf(
                            "compaction" to mapOf(
                                "status" to "completed",
                                "trigger" to "auto",
                                "latestPromptTokens" to 126000,
                                "promptTokenThreshold" to 128000,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val runtime = coordinator.debugRuntimeStateFor(conversationId, CHAT_RUNTIME_MODE_AGENT)!!
        assertTrue(runtime.messages.isEmpty())
        assertFalse(runtime.isContextCompressing)
        assertTrue(fixture.history.callsTo("upsertConversationUiCard").isEmpty())
    }

    @Test
    fun `manual compaction marker does not manufacture an automatic or user turn`() {
        val conversationId = 2204
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)

        coordinator.beginContextCompaction(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)

        val marker = runtime.messages.single()
        assertEquals(3, marker.user)
        assertEquals("context_compaction_marker", marker.cardData?.get("type"))
        assertEquals("manual", marker.cardData?.get("trigger"))
        assertTrue(runtime.messages.none { it.user == 1 })
    }

    @Test
    fun `routes normal chat chunks through the ACP stream`() {
        val conversationId = 2301
        val turnId = "turn-normal"
        coordinator.beginAcpTurn(taskId = turnId, conversationId = conversationId, mode = CHAT_RUNTIME_MODE_NORMAL)
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_NORMAL)
        applyAcp(
            conversationId,
            "session/update",
            turnId = turnId,
            mode = CHAT_RUNTIME_MODE_NORMAL,
            params = mapOf(
                "update" to mapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "messageId" to "message-normal",
                    "content" to mapOf("text" to "普通聊天回复"),
                ),
            ),
        )
        completePrompt(
            conversationId,
            turnId = turnId,
            mode = CHAT_RUNTIME_MODE_NORMAL,
            params = mapOf("status" to "completed"),
        )

        assertEquals("普通聊天回复", runtime.messages.single().text)
        assertFalse(runtime.isAiResponding)
    }

    @Test
    fun `clears transient runtime state when an ACP session ends`() {
        val conversationId = 2401
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        runtime.currentDispatchTurnId = "turn-clear"
        runtime.lastAgentTurnId = "turn-clear"
        runtime.activeRunId = "run-clear"
        runtime.activeAcpTurnId = "acp-turn-clear"
        runtime.activeAcpSessionId = "session-clear"
        runtime.currentAiMessages["message-clear"] = "stale text"
        runtime.agentReplayDeltaOffsets["message-clear"] = 4
        runtime.pendingAcpAssistantPresentation["pending-clear"] = linkedMapOf(
            "recovery" to linkedMapOf("error" to "stale"),
        )
        runtime.isAiResponding = true
        runtime.isDeepThinking = true
        runtime.activeThinkingCardId = "thought"
        runtime.activeToolCardId = "tool"

        coordinator.clearConversationRuntimeSession(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)

        assertNull(runtime.currentDispatchTurnId)
        assertNull(runtime.lastAgentTurnId)
        assertNull(runtime.activeRunId)
        assertTrue(runtime.currentAiMessages.isEmpty())
        assertTrue(runtime.agentReplayDeltaOffsets.isEmpty())
        assertTrue(runtime.pendingAcpAssistantPresentation.isEmpty())
        assertFalse(runtime.isAiResponding)
        assertFalse(runtime.isDeepThinking)
        assertNull(runtime.activeThinkingCardId)
        assertNull(runtime.activeToolCardId)
        assertTrue(runtime.completedAgentTurnIds.contains("run-clear"))
        assertTrue(runtime.completedAgentTurnIds.contains("acp-turn-clear"))
        assertTrue(runtime.completedAcpTurnIds.contains("acp-turn-clear"))
    }

    @Test
    fun `unregistering a local task also clears its distinct official ACP turn`() {
        val conversationId = 2404
        val taskId = "local-run-2404"
        val officialTurnId = "acp-turn-2404"
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.beginAcpTurn(taskId = taskId, conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        applyAcp(conversationId, "turn/started", turnId = officialTurnId)

        assertEquals(officialTurnId, runtime.activeAcpTurnId)
        assertTrue(runtime.isAiResponding)

        coordinator.unregisterTask(taskId)

        assertNull(runtime.activeAcpTurnId)
        assertNull(runtime.activeAcpSessionId)
        assertFalse(runtime.isAiResponding)
        assertFalse(runtime.isContextCompressing)
        assertTrue(runtime.isInputAreaVisible)
        assertTrue(runtime.completedAcpTurnIds.contains(officialTurnId))
    }

    @Test
    fun `late thinking cleanup for an old task cannot clear the new task`() {
        val conversationId = 2405
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.beginAcpTurn(taskId = "local-old-2405", conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        applyAcp(conversationId, "turn/started", turnId = "acp-old-2405")
        coordinator.registerTask(taskId = "local-new-2405", conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.beginAcpTurn(taskId = "local-new-2405", conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        runtime.isDeepThinking = true
        runtime.deepThinkingContent = "new turn reasoning"
        runtime.activeThinkingCardId = "local-new-2405-thinking"

        coordinator.clearTaskThinkingPresentation(
            taskId = "local-old-2405",
            conversationId = conversationId,
            mode = CHAT_RUNTIME_MODE_AGENT,
        )

        assertTrue(runtime.isDeepThinking)
        assertEquals("new turn reasoning", runtime.deepThinkingContent)
        assertEquals("local-new-2405-thinking", runtime.activeThinkingCardId)
    }

    @Test
    fun `beginAcpTurn admits a pure chat runtime when it is created lazily`() {
        val conversationId = 2406
        val taskId = "pure-chat-lazy-runtime"
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_NORMAL)

        coordinator.registerTask(taskId = taskId, conversationId = conversationId, mode = CHAT_RUNTIME_MODE_NORMAL)
        coordinator.beginAcpTurn(taskId = taskId, conversationId = conversationId, mode = CHAT_RUNTIME_MODE_NORMAL)

        assertTrue(runtime.isAiResponding)
        assertEquals(taskId, runtime.currentDispatchTurnId)
        assertEquals(taskId, runtime.activeRunId)
        assertTrue(coordinator.isTaskActive(taskId = taskId, conversationId = conversationId, mode = CHAT_RUNTIME_MODE_NORMAL))
    }

    @Test
    fun `bindAcpSession reserves the official identity before prompt events`() {
        val conversationId = 24061
        val taskId = "session-reservation-task"
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.beginAcpTurn(taskId = taskId, conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)

        assertTrue(
            coordinator.bindAcpSession(
                taskId = taskId,
                conversationId = conversationId,
                mode = CHAT_RUNTIME_MODE_AGENT,
                sessionId = "session-reserved",
            ),
        )
        assertEquals("session-reserved", runtime.activeAcpSessionId)
        assertTrue(runtime.knownAcpSessionIds.contains("session-reserved"))

        coordinator.unregisterTask(taskId, conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
    }

    @Test
    fun `official terminal event retires only the matching task binding`() {
        val conversationId = 24062
        val taskId = "terminal-binding-task"
        val sessionId = "terminal-binding-session"
        val turnId = "terminal-binding-turn"

        coordinator.beginAcpTurn(taskId = taskId, conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        assertTrue(
            coordinator.bindAcpSession(
                taskId = taskId,
                conversationId = conversationId,
                mode = CHAT_RUNTIME_MODE_AGENT,
                sessionId = sessionId,
            ),
        )

        completePrompt(conversationId, turnId = turnId, sessionId = sessionId)

        val runtime = coordinator.debugRuntimeStateFor(conversationId, CHAT_RUNTIME_MODE_AGENT)!!
        assertFalse(runtime.isAiResponding)
        assertFalse(coordinator.isTaskActive(taskId = taskId, conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT))
    }

    @Test
    fun `rebinds a task without leaving the old runtime active`() {
        val oldConversationId = 2407
        val newConversationId = 2408
        val taskId = "handoff-task"
        val oldRuntime = coordinator.debugEnsureRuntimeState(conversationId = oldConversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        val newRuntime = coordinator.debugEnsureRuntimeState(conversationId = newConversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.beginAcpTurn(taskId = taskId, conversationId = oldConversationId, mode = CHAT_RUNTIME_MODE_AGENT)

        coordinator.registerTask(taskId = taskId, conversationId = newConversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.beginAcpTurn(taskId = taskId, conversationId = newConversationId, mode = CHAT_RUNTIME_MODE_AGENT)

        assertFalse(oldRuntime.hasInFlightTask)
        assertTrue(newRuntime.isAiResponding)
        assertTrue(coordinator.isTaskActive(taskId = taskId, conversationId = newConversationId, mode = CHAT_RUNTIME_MODE_AGENT))
    }

    @Test
    fun `scoped late cleanup cannot clear a task after it changes runtime`() {
        val oldConversationId = 2409
        val newConversationId = 2410
        val taskId = "reused-task-id"
        val oldRuntime = coordinator.debugEnsureRuntimeState(conversationId = oldConversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        val newRuntime = coordinator.debugEnsureRuntimeState(conversationId = newConversationId, mode = CHAT_RUNTIME_MODE_AGENT)

        coordinator.beginAcpTurn(taskId = taskId, conversationId = oldConversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.registerTask(taskId = taskId, conversationId = newConversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.beginAcpTurn(taskId = taskId, conversationId = newConversationId, mode = CHAT_RUNTIME_MODE_AGENT)

        // This is the old runtime's delayed callback. Its identity must be
        // checked before the shared task binding is used for cleanup.
        coordinator.unregisterTask(taskId, conversationId = oldConversationId, mode = CHAT_RUNTIME_MODE_AGENT)

        assertFalse(oldRuntime.hasInFlightTask)
        assertTrue(newRuntime.isAiResponding)
        assertEquals(taskId, newRuntime.activeRunId)
    }

    @Test
    fun `beginAcpTurn rebinds through the same task admission path`() {
        val oldConversationId = 2411
        val newConversationId = 2412
        val taskId = "direct-begin-rebind"
        val oldRuntime = coordinator.debugEnsureRuntimeState(conversationId = oldConversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        val newRuntime = coordinator.debugEnsureRuntimeState(conversationId = newConversationId, mode = CHAT_RUNTIME_MODE_AGENT)

        coordinator.beginAcpTurn(taskId = taskId, conversationId = oldConversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.beginAcpTurn(taskId = taskId, conversationId = newConversationId, mode = CHAT_RUNTIME_MODE_AGENT)

        assertFalse(oldRuntime.hasInFlightTask)
        assertTrue(newRuntime.isAiResponding)
        assertFalse(coordinator.isTaskActive(taskId = taskId, conversationId = oldConversationId, mode = CHAT_RUNTIME_MODE_AGENT))
        assertTrue(coordinator.isTaskActive(taskId = taskId, conversationId = newConversationId, mode = CHAT_RUNTIME_MODE_AGENT))
    }

    @Test
    fun `fences a sessionless late turn event after runtime reset`() {
        val conversationId = 2403
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        coordinator.beginAcpTurn(taskId = "run-reset", conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        runtime.activeAcpSessionId = "session-reset"
        runtime.activeAcpTurnId = "acp-turn-reset"

        coordinator.clearConversationRuntimeSession(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)

        assertFalse(runtime.acceptsAcpEvent(turnId = "acp-turn-reset"))
        // A new sessionless turn remains compatible with the legacy wire shape.
        assertTrue(runtime.acceptsAcpEvent(turnId = "acp-turn-new"))
    }

    @Test
    fun `fences late events from a reset session but allows a new turn to reuse it`() {
        val conversationId = 2402
        val runtime = coordinator.debugEnsureRuntimeState(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)

        assertTrue(
            runtime.acceptsAcpEvent(sessionId = "session-retired", turnId = "turn-old", allowSessionAdmission = true),
        )
        coordinator.clearConversationRuntimeSession(conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)

        assertFalse(runtime.acceptsAcpEvent(sessionId = "session-retired", turnId = "turn-old"))

        coordinator.beginAcpTurn(taskId = "run-new", conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)
        assertTrue(
            runtime.acceptsAcpEvent(sessionId = "session-retired", turnId = "turn-new", allowSessionAdmission = true),
        )
    }

    @Test
    fun `projects active Xiaowan conversations for the drawer`() {
        val conversationId = 2010
        val taskId = "drawer-running-task"

        coordinator.beginAcpTurn(taskId = taskId, conversationId = conversationId, mode = CHAT_RUNTIME_MODE_AGENT)

        assertTrue(coordinator.activeAgentConversationIds.contains(conversationId))
        assertTrue(coordinator.isAgentConversationActive(conversationId))

        coordinator.unregisterTask(taskId)
        assertFalse(coordinator.activeAgentConversationIds.contains(conversationId))
        assertFalse(coordinator.isAgentConversationActive(conversationId))
    }

    @Test
    fun `maps ACP tool updates to the tools island`() {
        val conversationId = 2501
        applyAcp(
            conversationId,
            "item/started",
            turnId = "turn-tool",
            params = mapOf(
                "item" to mapOf(
                    "id" to "tool-1",
                    "type" to "commandExecution",
                    "command" to "pwd",
                    "status" to "running",
                ),
            ),
        )
        val runtime = coordinator.debugRuntimeStateFor(conversationId, CHAT_RUNTIME_MODE_AGENT)!!
        assertEquals(ChatIslandDisplayLayer.TOOLS, runtime.chatIslandDisplayLayer)
        assertEquals("terminal", runtime.lastAgentToolType)
    }
}
