package cn.com.omnimind.bot.agent.projection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatPromptDispatcherTest {
    private lateinit var fixture: ChatRuntimeTestFixture
    private lateinit var dispatcher: ChatPromptDispatcher
    private val calls = ArrayList<Pair<String, Map<String, Any?>>>()
    private val responders = HashMap<String, suspend (Map<String, Any?>) -> Any?>()
    private val coordinator get() = fixture.coordinator
    private val target = ChatPromptDispatcher.TurnTarget("task-1", 501, CHAT_RUNTIME_MODE_AGENT)

    @Before
    fun setUp() {
        fixture = ChatRuntimeTestFixture()
        dispatcher = ChatPromptDispatcher(coordinator) { method, args ->
            calls += method to args
            responders[method]?.invoke(args)
        }
        responders["session/new"] = { linkedMapOf("sessionId" to "session-new") }
        responders["session/close"] = { linkedMapOf("closed" to true) }
    }

    private fun methods() = calls.map { it.first }

    private fun runtime() = coordinator.debugRuntimeStateFor(target.conversationId, target.mode)!!

    @Test
    fun `prepare creates and binds a session for an admitted run`() = runBlocking {
        coordinator.beginAcpTurn(target.taskId, target.conversationId, target.mode)
        val prepared = dispatcher.prepareTurnSession(target, null, mapOf("conversationId" to 501))
        assertEquals("ready", prepared["status"])
        assertEquals("session-new", prepared["sessionId"])
        assertEquals(true, prepared["created"])
        assertEquals("session-new", runtime().activeAcpSessionId)
        assertEquals(listOf("session/new"), methods())
        assertEquals(mapOf("conversationId" to 501), calls.single().second)
    }

    @Test
    fun `prepare reuses an existing session without session-new`() = runBlocking {
        coordinator.beginAcpTurn(target.taskId, target.conversationId, target.mode)
        val prepared = dispatcher.prepareTurnSession(target, " kept ", emptyMap())
        assertEquals("kept", prepared["sessionId"])
        assertEquals(false, prepared["created"])
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a run that lost ownership during session-new closes the new session`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        responders["session/new"] = {
            gate.await()
            linkedMapOf("sessionId" to "late-session")
        }
        coordinator.beginAcpTurn(target.taskId, target.conversationId, target.mode)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            dispatcher.prepareTurnSession(target, null, emptyMap())
        }
        coordinator.unregisterTask(target.taskId, conversationId = 501, mode = target.mode)
        gate.complete(Unit)
        val prepared = pending.await()
        assertEquals("abandoned", prepared["status"])
        assertEquals(listOf("session/new", "session/close"), methods())
        assertEquals("late-session", calls.last().second["sessionId"])
        assertNull(runtime().activeAcpSessionId)
    }

    @Test
    fun `an inactive run is never prepared`() = runBlocking {
        val prepared = dispatcher.prepareTurnSession(target, null, emptyMap())
        assertEquals("abandoned", prepared["status"])
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a session-new failure ends the run as its prompt response`() = runBlocking {
        responders["session/new"] = { throw IllegalStateException("Provider request timeout") }
        coordinator.beginAcpTurn(target.taskId, target.conversationId, target.mode)
        val prepared = dispatcher.prepareTurnSession(target, null, emptyMap(), clearThinkingOnFailure = true)
        assertEquals("failed", prepared["status"])
        assertFalse(runtime().isAiResponding)
        assertFalse(coordinator.isTaskActive(target.taskId, target.conversationId, target.mode))
        val failureText = runtime().messages.joinToString { DartJson.encode(it.content) }
        assertTrue(failureText, failureText.contains("等待回复超时"))
    }

    @Test
    fun `releasing a reservation closes the created session and the binding`() = runBlocking {
        coordinator.beginAcpTurn(target.taskId, target.conversationId, target.mode)
        dispatcher.prepareTurnSession(target, null, emptyMap())
        dispatcher.releaseTurnSession(target, "session-new")
        assertEquals(listOf("session/new", "session/close"), methods())
        assertFalse(coordinator.isTaskActive(target.taskId, target.conversationId, target.mode))
    }

    @Test
    fun `submit applies the official prompt response`() = runBlocking {
        responders["session/prompt"] = {
            linkedMapOf("sessionId" to "s1", "turnId" to "turn-9", "stopReason" to "end_turn")
        }
        coordinator.beginAcpTurn(target.taskId, target.conversationId, target.mode)
        val outcome = dispatcher.submitTurnPrompt(target, mapOf("text" to "hi"), fallbackSessionId = "s1")
        assertEquals("completed", outcome["status"])
        assertEquals("turn-9", (outcome["response"] as Map<*, *>)["turnId"])
        assertTrue((outcome["result"] as AgentReduceResult).handled)
        assertFalse(runtime().isAiResponding)
        assertEquals(mapOf("text" to "hi"), calls.single().second)
    }

    @Test
    fun `a classified response error is formatted for the user`() = runBlocking {
        responders["session/prompt"] = {
            linkedMapOf("stopReason" to "error", "error" to "429 raw", "failureKind" to "provider_quota_exceeded")
        }
        coordinator.beginAcpTurn(target.taskId, target.conversationId, target.mode)
        val outcome = dispatcher.submitTurnPrompt(target, emptyMap(), fallbackSessionId = null)
        assertEquals(
            "模型服务商额度不足，请检查账户余额或配额后再试。",
            (outcome["response"] as Map<*, *>)["error"],
        )
    }

    @Test
    fun `a transport failure ends the run once and reports failed`() = runBlocking {
        responders["session/prompt"] = { throw IllegalStateException("stream disconnected") }
        coordinator.beginAcpTurn(target.taskId, target.conversationId, target.mode)
        val outcome = dispatcher.submitTurnPrompt(target, emptyMap(), fallbackSessionId = "s1")
        assertEquals("failed", outcome["status"])
        assertTrue((outcome["result"] as AgentReduceResult).handled)
        assertFalse(runtime().isAiResponding)
        val text = runtime().messages.joinToString { DartJson.encode(it.content) }
        assertTrue(text, text.contains("回复连接已中断"))
    }

    @Test
    fun `prompt failures retain structured classification`() {
        val response = ChatPromptDispatcher.normalizePromptResponse(
            linkedMapOf(
                "status" to "error", "stopReason" to "error", "completed" to true,
                "error" to "测试失败", "failureKind" to "provider_authentication_failed",
                "sessionId" to "session-1", "turnId" to "turn-1",
            ),
        )
        assertEquals("模型连接验证失败，请在模型设置中检查接口地址和密钥。", response["error"])
        assertEquals("error", response["status"])
        assertEquals("turn-1", response["turnId"])
        assertEquals(true, response["completed"])
    }

    @Test
    fun `cancel and server answers pass through the single entry`() = runBlocking {
        dispatcher.cancelTurn(mapOf("sessionId" to "s"))
        dispatcher.cancelRequest(mapOf("requestId" to 7))
        dispatcher.respondToServerRequest(mapOf("requestId" to "r"))
        assertEquals(listOf("session/cancel", "\$/cancel_request", "respondToServerRequest"), methods())
    }

    @Test
    fun `formatter maps failure kinds and http envelopes`() {
        assertEquals(
            "模型服务商暂时不可用，请稍后再试或更换模型连接。",
            AgentUserErrorText.format("x", "provider_service_unavailable"),
        )
        assertEquals(
            "模型连接验证失败，请在模型设置中检查接口地址和密钥。",
            AgentUserErrorText.format("responses request failed(401): nope"),
        )
        assertEquals(
            "The reply timed out. Check your connection and try again, or choose another model.",
            AgentUserErrorText.format("x", "provider_request_timeout", english = true),
        )
        assertEquals("助手暂时无法完成操作，请重试。", AgentUserErrorText.format("{\"raw\":1}"))
    }
}
