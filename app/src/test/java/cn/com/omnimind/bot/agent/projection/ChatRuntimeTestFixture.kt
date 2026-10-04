package cn.com.omnimind.bot.agent.projection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** One recorded native channel call, in Dart `MethodCall` shape. */
data class RecordedCall(val method: String, val arguments: Map<String, Any?>)

/**
 * Stand-in for the Dart test's mocked `AssistCoreEvent` method channel: it
 * records the native calls the history store makes and can hold a method to
 * reproduce I/O races (Dart `Completer`-gated mock handlers).
 */
class FakeHistoryStore : ChatRuntimeHistoryStore() {
    val calls = ArrayList<RecordedCall>()
    val conversations = LinkedHashMap<Int, Map<String, Any?>>()
    var failReplace = false

    /** When set, the next call of that method suspends until released. */
    private val holds = LinkedHashMap<String, Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>>()

    /** Returns (entered, release): entered completes when the call starts. */
    fun hold(method: String): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> {
        val pair = CompletableDeferred<Unit>() to CompletableDeferred<Unit>()
        holds[method] = pair
        return pair
    }

    fun callsTo(method: String) = calls.filter { it.method == method }

    private suspend fun gate(method: String) {
        val pair = holds.remove(method) ?: return
        pair.first.complete(Unit)
        pair.second.await()
    }

    override suspend fun replaceNativeConversationMessages(
        conversationId: Int,
        conversationMode: String,
        rows: List<Map<String, Any?>>,
        allowHistoryRemoval: Boolean,
    ): Boolean {
        calls.add(
            RecordedCall(
                "replaceConversationMessages",
                linkedMapOf(
                    "conversationId" to conversationId,
                    "mode" to conversationMode,
                    "messages" to rows,
                    "allowHistoryRemoval" to allowHistoryRemoval,
                ),
            ),
        )
        gate("replaceConversationMessages")
        return !failReplace
    }

    override suspend fun clearLegacyConversationMessages(conversationId: Int, conversationMode: String) = Unit

    override suspend fun readConversation(conversationId: Int): Map<String, Any?>? = conversations[conversationId]

    override suspend fun writeConversation(conversation: Map<String, Any?>): Boolean {
        calls.add(RecordedCall("updateConversation", linkedMapOf("conversation" to conversation)))
        gate("updateConversation")
        return true
    }

    override suspend fun completeConversation(conversationId: Int, conversationMode: String): Boolean {
        calls.add(
            RecordedCall("completeConversation", linkedMapOf("conversationId" to conversationId, "mode" to conversationMode)),
        )
        return true
    }

    override suspend fun generateConversationSummary(conversationHistory: String): String? {
        calls.add(RecordedCall("generateConversationSummary", linkedMapOf("conversationHistory" to conversationHistory)))
        return null
    }

    override suspend fun upsertConversationUiCard(
        conversationId: Int,
        conversationMode: String,
        entryId: String,
        cardData: Map<String, Any?>,
        createdAtMillis: Long,
    ) {
        calls.add(
            RecordedCall(
                "upsertConversationUiCard",
                linkedMapOf(
                    "conversationId" to conversationId,
                    "mode" to conversationMode,
                    "entryId" to entryId,
                    "cardData" to cardData,
                    "createdAt" to createdAtMillis,
                ),
            ),
        )
    }
}

/** Records voice side effects (Dart test's mocked VoicePlayback channel). */
class FakeVoice : ChatRuntimeVoice {
    data class Call(val messageId: String, val text: String, val isFinal: Boolean)

    val calls = ArrayList<Call>()

    override fun onAssistantMessageUpdated(messageId: String, text: String, isFinal: Boolean) {
        calls.add(Call(messageId, text, isFinal))
    }
}

/**
 * Manual clock + timers (Dart real `Timer`s). [advanceBy] fires due timers in
 * order; [runAll] fires everything pending.
 */
class ManualScheduler : ChatRuntimeScheduler {
    private class Entry(val dueAt: Long, val order: Long, val block: () -> Unit) {
        var cancelled = false
    }

    var now = 1_700_000_000_000L
        private set
    private var sequence = 0L
    private val entries = ArrayList<Entry>()

    val pendingCount: Int get() = entries.count { !it.cancelled }

    override fun schedule(delayMillis: Long, block: () -> Unit): ChatRuntimeTimer {
        val entry = Entry(now + delayMillis, sequence++, block)
        entries.add(entry)
        return ChatRuntimeTimer { entry.cancelled = true }
    }

    fun advanceBy(millis: Long) {
        val target = now + millis
        while (true) {
            val next = entries.filter { !it.cancelled && it.dueAt <= target }
                .minWithOrNull(compareBy<Entry> { it.dueAt }.thenBy { it.order }) ?: break
            entries.remove(next)
            now = maxOf(now, next.dueAt)
            next.block()
        }
        entries.removeAll { it.cancelled }
        now = target
    }

    fun runAll() = advanceBy(Long.MAX_VALUE / 4 - now)
}

/** Builds a coordinator wired to fakes; everything runs on the test thread. */
class ChatRuntimeTestFixture {
    val history = FakeHistoryStore()
    val voice = FakeVoice()
    val scheduler = ManualScheduler()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val coordinator = ChatConversationRuntimeCoordinator(
        persistence = history,
        voice = voice,
        scope = scope,
        scheduler = scheduler,
        clock = { scheduler.now },
    )

    /**
     * Dart test helper `acpEvent`: one ACP envelope as emitted by the host,
     * with a host reservation for the first event when requested.
     */
    fun acpEvent(
        method: String,
        turnId: String,
        sessionId: String? = null,
        params: Map<String, Any?> = emptyMap(),
        agentId: String = "xiaowan-acp",
        agentName: String = "小万",
        conversationId: Int? = null,
        hostAssignedTurn: Boolean = false,
    ): JsonMap {
        val event: JsonMap = linkedMapOf()
        if (conversationId != null) event["conversationId"] = conversationId
        if (sessionId != null) event["sessionId"] = sessionId
        if (hostAssignedTurn || method == "turn/started" || sessionId == null) {
            event["allowImplicitTurnAdmission"] = true
        }
        event["agentId"] = agentId
        event["agentName"] = agentName
        event["threadId"] = turnId
        event["turnId"] = turnId
        val messageParams: JsonMap = linkedMapOf()
        if (!hostAssignedTurn) messageParams["turnId"] = turnId
        if (sessionId != null) messageParams["sessionId"] = sessionId
        messageParams.putAll(deepCopyMap(params))
        event["message"] = linkedMapOf("method" to method, "params" to messageParams)
        return event
    }

    /** Dart test helper `applyAcp`. */
    fun applyAcp(
        conversationId: Int,
        method: String,
        turnId: String,
        sessionId: String? = null,
        params: Map<String, Any?> = emptyMap(),
        mode: String = CHAT_RUNTIME_MODE_AGENT,
        agentId: String = "xiaowan-acp",
        agentName: String = "小万",
        hostAssignedTurn: Boolean = false,
    ): AgentReduceResult {
        if (method == "turn/started" &&
            coordinator.debugRuntimeStateFor(conversationId, mode)?.currentDispatchTurnId == null
        ) {
            coordinator.beginAcpTurn(taskId = turnId, conversationId = conversationId, mode = mode)
        }
        return coordinator.applyAgentEvent(
            conversationId = conversationId,
            mode = mode,
            event = acpEvent(
                method,
                turnId = turnId,
                sessionId = sessionId,
                params = params,
                agentId = agentId,
                agentName = agentName,
                conversationId = conversationId,
                hostAssignedTurn = hostAssignedTurn,
            ),
        )
    }

    /** Dart test helper `completePrompt`. */
    fun completePrompt(
        conversationId: Int,
        turnId: String,
        sessionId: String? = null,
        mode: String = CHAT_RUNTIME_MODE_AGENT,
        params: Map<String, Any?> = emptyMap(),
    ): AgentReduceResult {
        val runtime = coordinator.debugRuntimeStateFor(conversationId, mode)!!
        return coordinator.applyAcpPromptResponse(
            taskId = runtime.activeRunId ?: runtime.currentDispatchTurnId ?: turnId,
            conversationId = conversationId,
            mode = mode,
            sessionId = sessionId ?: runtime.activeAcpSessionId,
            turnId = turnId,
            stopReason = params["stopReason"] as? String ?: "end_turn",
        )
    }
}
