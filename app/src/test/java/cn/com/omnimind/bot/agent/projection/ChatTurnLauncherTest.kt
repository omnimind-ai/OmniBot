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

/**
 * Behavior of the native turn launcher (batch 5d-0b). The Dart send paths it
 * replaces were only covered by source-text assertions; these run the real
 * coordinator and dispatcher against a recording transport.
 */
class ChatTurnLauncherTest {
    private lateinit var fixture: ChatRuntimeTestFixture
    private lateinit var launcher: ChatTurnLauncher
    private val calls = ArrayList<Pair<String, Map<String, Any?>>>()
    private val responders = HashMap<String, suspend (Map<String, Any?>) -> Any?>()
    private val coordinator get() = fixture.coordinator

    @Before
    fun setUp() {
        fixture = ChatRuntimeTestFixture()
        val dispatcher = ChatPromptDispatcher(coordinator) { method, args ->
            calls += method to args
            responders[method]?.invoke(args)
        }
        launcher = ChatTurnLauncher(coordinator, dispatcher)
        responders["session/new"] = { linkedMapOf("sessionId" to "session-new") }
        responders["session/close"] = { linkedMapOf("closed" to true) }
        responders["session/prompt"] = {
            linkedMapOf("sessionId" to "session-new", "promptId" to "turn-1", "stopReason" to "end_turn")
        }
        coordinator.ensureRuntime(CONVERSATION, CHAT_RUNTIME_MODE_AGENT, initialMessages = emptyList(), conversation = null, initialChatIslandDisplayLayer = null)
    }

    private fun methods() = calls.map { it.first }
    private fun argsOf(method: String) = calls.last { it.first == method }.second
    private fun runtime() = coordinator.debugRuntimeStateFor(CONVERSATION, CHAT_RUNTIME_MODE_AGENT)!!

    private fun request(
        text: String = "你好",
        attachments: List<Map<String, Any?>> = emptyList(),
        existingSessionId: String? = null,
        userMessage: ChatMessage? = userRow(text),
    ) = ChatTurnRequest(
        taskId = TASK,
        conversationId = CONVERSATION,
        mode = CHAT_RUNTIME_MODE_AGENT,
        text = text,
        attachments = attachments,
        userMessage = userMessage,
        existingSessionId = existingSessionId,
        agentId = "codex-acp",
        permission = AgentPermissionMode.ReadOnly,
        model = "gpt-5-codex",
        effort = "high",
        conversationMode = "agent",
        terminalEnvironment = mapOf("API" to "x"),
    )

    private fun userRow(text: String) = ChatMessage(
        id = "1-user", type = 1, user = 1, content = linkedMapOf("text" to text, "id" to "1-user"),
    )

    @Test
    fun `a submission inserts its user row, reserves a session and prompts it`() = runBlocking {
        val outcome = launcher.launchTurn(request())
        assertEquals(ChatTurnOutcome.Status.Completed, outcome.status)
        assertEquals(listOf("session/new", "session/prompt"), methods())
        assertEquals(
            mapOf("conversationId" to CONVERSATION, "model" to "gpt-5-codex", "effort" to "high", "conversationMode" to "agent"),
            argsOf("session/new"),
        )
        val prompt = argsOf("session/prompt")
        assertEquals("session-new", prompt["sessionId"])
        assertEquals(TASK, prompt["requestId"])
        assertEquals("codex-acp", prompt["agentId"])
        assertEquals("on-request", prompt["approvalPolicy"])
        assertEquals(mapOf("type" to "readOnly"), prompt["sandboxPolicy"])
        assertEquals("你好", prompt["text"])
        assertEquals(mapOf("API" to "x"), prompt["terminalEnvironment"])
        assertEquals("1-user", runtime().messages.single { it.user == 1 }.id)
        assertEquals("session-new", outcome.sessionId)
        assertEquals("turn-1", outcome.turnId)
        assertTrue(outcome.targetCurrent)
        assertFalse(runtime().isAiResponding)
        // The admission snapshot was persisted before transport.
        assertTrue(fixture.history.callsTo("replaceConversationMessages").isNotEmpty())
    }

    @Test
    fun `an existing session is reused and the user row is not duplicated`() = runBlocking {
        coordinator.insertRuntimeMessage(CONVERSATION, CHAT_RUNTIME_MODE_AGENT, userRow("你好"))
        launcher.launchTurn(request(existingSessionId = "kept"))
        assertEquals(listOf("session/prompt"), methods())
        assertEquals("kept", argsOf("session/prompt")["sessionId"])
        assertEquals(1, runtime().messages.count { it.id == "1-user" })
    }

    @Test
    fun `the prompt carries the submission, not a re-read of the newest user message`() = runBlocking {
        // A newer user row arrives while this turn is admitted (5d-0 fix 4).
        coordinator.insertRuntimeMessage(CONVERSATION, CHAT_RUNTIME_MODE_AGENT, userRow("别的消息").copy(id = "2-user"))
        launcher.launchTurn(request(text = "原始提交", userMessage = null))
        assertEquals("原始提交", argsOf("session/prompt")["text"])
    }

    @Test
    fun `excluded attachments are described by path and never forwarded`() = runBlocking {
        val image = mapOf("name" to "screen.png", "path" to "/tmp/screen.png", "isImage" to true)
        val excluded = mapOf("name" to "big.zip", "promptPath" to "/workspace/big.zip", "sendToModel" to false)
        launcher.launchTurn(request(text = "看看", attachments = listOf(image, excluded)))
        val prompt = argsOf("session/prompt")
        assertEquals(listOf(image), prompt["attachments"])
        assertEquals("看看\n已添加到 workspace，可通过以下路径读取：\n- big.zip: /workspace/big.zip", prompt["text"])
    }

    @Test
    fun `an empty submission is rejected without admission`() = runBlocking {
        val outcome = launcher.launchTurn(request(text = ""))
        assertEquals(ChatTurnOutcome.Status.Rejected, outcome.status)
        assertTrue(calls.isEmpty())
        assertFalse(runtime().isAiResponding)
    }

    @Test
    fun `a target that moved on before the session is reserved never prompts`() = runBlocking {
        var current = true
        val (entered, release) = fixture.history.hold("replaceConversationMessages")
        val pending = async(start = CoroutineStart.UNDISPATCHED) { launcher.launchTurn(request()) { current } }
        entered.await()
        current = false
        release.complete(Unit)
        val outcome = pending.await()
        assertEquals("stale", outcome.rejectedReason)
        assertTrue(calls.isEmpty())
        assertFalse(coordinator.isTaskActive(TASK, CONVERSATION, CHAT_RUNTIME_MODE_AGENT))
    }

    @Test
    fun `a target that moved on during session-new closes the created session`() = runBlocking {
        var current = true
        val gate = CompletableDeferred<Unit>()
        responders["session/new"] = {
            gate.await()
            linkedMapOf("sessionId" to "late-session")
        }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { launcher.launchTurn(request()) { current } }
        current = false
        gate.complete(Unit)
        val outcome = pending.await()
        assertEquals("stale", outcome.rejectedReason)
        assertEquals(listOf("session/new", "session/close"), methods())
        assertEquals("late-session", argsOf("session/close")["sessionId"])
        assertFalse(coordinator.isTaskActive(TASK, CONVERSATION, CHAT_RUNTIME_MODE_AGENT))
    }

    @Test
    fun `pointers are not reported to a page that moved on during the prompt`() = runBlocking {
        // 5d-0 fix 3: the Dart page wrote the session pointer before submit.
        var current = true
        val gate = CompletableDeferred<Unit>()
        responders["session/prompt"] = {
            gate.await()
            linkedMapOf("sessionId" to "session-new", "promptId" to "turn-1", "stopReason" to "end_turn")
        }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { launcher.launchTurn(request()) { current } }
        current = false
        gate.complete(Unit)
        val outcome = pending.await()
        assertEquals(ChatTurnOutcome.Status.Completed, outcome.status)
        assertFalse(outcome.targetCurrent)
        assertNull(outcome.sessionId)
        assertNull(outcome.turnId)
        // The turn itself still finished on its own runtime.
        assertFalse(runtime().isAiResponding)
    }

    @Test
    fun `a persistence failure ends this run on its own runtime`() = runBlocking {
        // 5d-0 fix 2: the error lands on the dispatch target, not the visible runtime.
        coordinator.ensureRuntime(OTHER, CHAT_RUNTIME_MODE_AGENT, initialMessages = emptyList(), conversation = null, initialChatIslandDisplayLayer = null)
        fixture.history.failReplace = true
        val outcome = launcher.launchTurn(request())
        assertEquals(ChatTurnOutcome.Status.Failed, outcome.status)
        assertTrue(calls.isEmpty())
        assertFalse(coordinator.isTaskActive(TASK, CONVERSATION, CHAT_RUNTIME_MODE_AGENT))
        assertFalse(runtime().isAiResponding)
        assertTrue(coordinator.debugRuntimeStateFor(OTHER, CHAT_RUNTIME_MODE_AGENT)!!.messages.isEmpty())
    }

    @Test
    fun `a session-new failure is reported as failed and already projected`() = runBlocking {
        responders["session/new"] = { throw IllegalStateException("Provider request timeout") }
        val outcome = launcher.launchTurn(request())
        assertEquals(ChatTurnOutcome.Status.Failed, outcome.status)
        assertEquals(listOf("session/new"), methods())
        assertFalse(runtime().isAiResponding)
    }

    @Test
    fun `a prompt transport failure keeps the reserved session pointer`() = runBlocking {
        responders["session/prompt"] = { throw IllegalStateException("stream disconnected") }
        val outcome = launcher.launchTurn(request())
        assertEquals(ChatTurnOutcome.Status.Failed, outcome.status)
        assertEquals("session-new", outcome.sessionId)
        assertNull(outcome.threadId)
        assertFalse(runtime().isAiResponding)
    }

    @Test
    fun `an advertised review command is an ordinary prompt that ends its run`() = runBlocking {
        // 5d-0c: the page called review/start, dropped the result, and only a
        // PromptResponse ends a run, so the page stayed responding.
        val outcome = launcher.launchTurn(request(text = "/review", existingSessionId = "kept"))
        assertEquals(ChatTurnOutcome.Status.Completed, outcome.status)
        assertEquals(listOf("session/prompt"), methods())
        assertEquals("/review", argsOf("session/prompt")["text"])
        assertFalse(runtime().isAiResponding)
        assertFalse(coordinator.isTaskActive(TASK, CONVERSATION, CHAT_RUNTIME_MODE_AGENT))
    }

    @Test
    fun `a retry keeps its user row and starts a new run`() = runBlocking {
        coordinator.insertRuntimeMessage(CONVERSATION, CHAT_RUNTIME_MODE_AGENT, userRow("你好"))
        val retry = ChatTurnIds.forRetry("1-user", 2L)
        val outcome = launcher.launchTurn(request(userMessage = null).copy(taskId = retry.taskId))
        assertEquals(ChatTurnOutcome.Status.Completed, outcome.status)
        assertEquals("2-ai", argsOf("session/prompt")["requestId"])
        assertEquals(listOf("1-user"), runtime().messages.filter { it.user == 1 }.map { it.id })
        assertFalse(runtime().isAiResponding)
    }

    @Test
    fun `a native composer submission keeps the seeded history it was admitted on`() = runBlocking {
        // 5d-1b: the composer seeds the runtime with the stored history before
        // launching; admission must append to it, never replace it.
        val older = ChatMessage(id = "0-user", type = 1, user = 1, content = linkedMapOf("text" to "旧消息", "id" to "0-user"))
        coordinator.insertRuntimeMessage(CONVERSATION, CHAT_RUNTIME_MODE_AGENT, older)
        launcher.launchTurn(request())
        val ids = runtime().messages.filter { it.user == 1 }.map { it.id }
        assertEquals(listOf("1-user", "0-user"), ids)
        val persisted = fixture.history.callsTo("replaceConversationMessages").first()
        assertTrue(persisted.toString().contains("0-user"))
    }

    private companion object {
        const val CONVERSATION = 501
        const val OTHER = 502
        const val TASK = "1-ai"
    }
}
