package cn.com.omnimind.bot.webchat

import android.content.Context
import cn.com.omnimind.bot.agent.AgentConversationHistoryRepository
import cn.com.omnimind.bot.agent.AgentTextSanitizer
import cn.com.omnimind.bot.agent.resolveAgentToolPayloadStatus
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal data class WebAgentToolUpdate(
    val entryId: String?,
    val parentTaskId: String?,
    val itemType: String,
    val status: String,
    val raw: Map<String, Any?>
)

internal data class WebAgentEventUpdate(
    val assistantEntryId: String? = null,
    val assistantDelta: String? = null,
    val assistantSnapshot: String? = null,
    val assistantFinal: Boolean = false,
    val reasoningEntryId: String? = null,
    val reasoningDelta: String? = null,
    val reasoningSnapshot: String? = null,
    val reasoningFinal: Boolean = false,
    val parentTaskId: String? = null,
    val tool: WebAgentToolUpdate? = null,
    val terminalKind: String? = null,
    val errorMessage: String? = null
)

private const val WEB_AGENT_MODE_STORAGE_VALUE = "agent"

private data class WebAgentTextEntryState(
    val entryId: String,
    val parentTaskId: String,
    val createdAt: Long,
    val sequence: Long,
    var text: String = "",
    var isFinal: Boolean = false
)

private data class WebAgentToolEntryState(
    val entryId: String,
    val parentTaskId: String,
    val createdAt: Long,
    val sequence: Long,
    var update: WebAgentToolUpdate
)

private data class WebAgentRunState(
    val taskId: String,
    val conversationId: Long,
    val conversationMode: String,
    val createdAt: Long,
    val finished: AtomicBoolean = AtomicBoolean(false),
    var threadId: String? = null,
    var turnId: String? = null,
    var agentId: String? = null,
    var agentName: String? = null,
    var sequence: Long = 0,
    val assistantEntries: LinkedHashMap<String, WebAgentTextEntryState> = linkedMapOf(),
    val reasoningEntries: LinkedHashMap<String, WebAgentTextEntryState> = linkedMapOf(),
    val toolEntries: LinkedHashMap<String, WebAgentToolEntryState> = linkedMapOf()
)

internal class WebAgentRunBridge(
    context: Context,
    private val manager: AgentRuntimeManager
) {
    private val appContext = context.applicationContext
    private val historyRepository = AgentConversationHistoryRepository(appContext)
    private val conversationService = ConversationDomainService(appContext)
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<Map<String, Any?>>(Channel.UNLIMITED)
    // Admission is a conversation-level invariant. A concurrent HTTP retry
    // must not replace a still-running state after observing a finished one.
    // This lock covers only map admission, never ACP execution.
    private val runAdmissionMutex = Mutex()
    private val runsByTaskId = ConcurrentHashMap<String, WebAgentRunState>()
    private val runsByConversationId = ConcurrentHashMap<Long, WebAgentRunState>()
    private val runsByThreadId = ConcurrentHashMap<String, WebAgentRunState>()
    private val runsByTurnId = ConcurrentHashMap<String, WebAgentRunState>()

    init {
        manager.setSupplementalEventListener(
            key = "webchat"
        ) { event -> events.trySend(event) }
        scope.launch {
            for (event in events) {
                handleEvent(event)
            }
        }
    }

    fun hasActiveRun(conversationId: Long): Boolean {
        return runsByConversationId[conversationId]?.finished?.get() == false
    }

    suspend fun startRun(
        taskId: String,
        conversationId: Long,
        conversationMode: String = WEB_AGENT_MODE_STORAGE_VALUE,
        userMessage: String,
        attachments: List<Map<String, Any?>>,
        cwd: String?,
        agentId: String? = null,
        model: String? = null,
        effort: String? = null,
        userMessageCreatedAt: Long? = null
    ): Map<String, Any?> {
        val state = WebAgentRunState(
            taskId = taskId,
            conversationId = conversationId,
            conversationMode = resolveWebConversationMode(
                storedMode = null,
                requestedMode = conversationMode
            ),
            createdAt = userMessageCreatedAt?.takeIf { it > 0L }
                ?: System.currentTimeMillis(),
            agentId = agentId?.trim()?.takeIf { it.isNotEmpty() }
        )
        runAdmissionMutex.withLock {
            val existing = runsByConversationId[conversationId]
            check(existing == null || existing.finished.get()) {
                "该 Agent 会话已有运行中的任务"
            }
            if (existing != null) {
                removeState(existing)
            }
            runsByConversationId[conversationId] = state
            runsByTaskId[taskId] = state
        }

        return try {
            // Reuse the external-message path so Flutter and WebChat receive
            // the same stable user entry before any Agent stream event can
            // overtake it. Keep this inside the same failure boundary as ACP
            // admission, otherwise a database error leaves a phantom run.
            conversationService.appendUserMessage(
                conversationId = conversationId,
                conversationMode = state.conversationMode,
                entryId = "$taskId-user",
                text = userMessage,
                attachments = attachments,
                createdAt = state.createdAt
            )
            // `agent/select` changes the global default used by the Agent
            // picker.  It is not a session binding and must not be issued as
            // part of starting a conversation: two WebChat conversations can
            // start concurrently with different Agents.  `session/prompt`
            // carries the requested agentId and resolves the durable
            // conversation owner at the ACP boundary.
            val arguments = buildWebAgentTurnArguments(
                conversationId = conversationId,
                userMessage = userMessage,
                attachments = attachments,
                cwd = cwd,
                agentId = agentId,
                model = model,
                effort = effort,
                conversationMode = state.conversationMode
            )
            val response = normalizeMap(
                manager.handleMethod("session/prompt", arguments)
            )
            bindServerIds(
                state = state,
                threadId = response["threadId"]?.toString(),
                turnId = response["turnId"]?.toString()
            )
            if (response["completed"] == true) {
                if (response["error"] == null) {
                    finishSuccessfully(state)
                } else {
                    finishWithError(state, response["error"].toString())
                }
            }
            response
        } catch (error: Throwable) {
            finishWithError(
                state,
                error.message?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: "Agent 启动失败"
            )
            throw error
        }
    }

    suspend fun cancelRun(taskId: String): Boolean {
        val state = runsByTaskId[taskId] ?: return false
        val arguments = linkedMapOf<String, Any?>(
            "conversationId" to state.conversationId
        )
        state.threadId?.let { arguments["threadId"] = it }
        state.turnId?.let { arguments["turnId"] = it }
        manager.handleMethod("session/cancel", arguments)
        return true
    }

    private suspend fun handleEvent(event: Map<String, Any?>) {
        val conversationId = event.readLong("conversationId")
        val threadId = event.readString("threadId")
        val turnId = event.readString("turnId")
        val state = conversationId?.let(runsByConversationId::get)
            ?: threadId?.let(runsByThreadId::get)
            ?: turnId?.let(runsByTurnId::get)
            ?: return
        if (state.finished.get()) return
        if (turnId != null && state.turnId != null && turnId != state.turnId) {
            return
        }
        bindServerIds(state, threadId, turnId)
        state.agentId = event.readString("agentId") ?: state.agentId
        state.agentName = event.readString("agentName") ?: state.agentName

        val update = parseWebAgentEvent(event)
        var changed = false
        val parentTaskId = update.parentTaskId
            ?.takeIf(String::isNotBlank)
            ?: state.turnId
            ?: state.taskId

        if (
            update.assistantSnapshot != null ||
            !update.assistantDelta.isNullOrEmpty()
        ) {
            val entryId = update.assistantEntryId
                ?.takeIf(String::isNotBlank)
                ?: "$parentTaskId-agent-message"
            val entry = state.assistantEntries.getOrPut(entryId) {
                newTextEntry(state, entryId, parentTaskId)
            }
            entry.text = if (update.assistantSnapshot != null) {
                mergeSnapshot(entry.text, update.assistantSnapshot)
            } else {
                entry.text + update.assistantDelta.orEmpty()
            }
            entry.isFinal = entry.isFinal || update.assistantFinal
            persistAssistantEntry(state, entry, isError = false)
            changed = true
        }

        if (
            update.reasoningSnapshot != null ||
            !update.reasoningDelta.isNullOrEmpty()
        ) {
            val entryId = update.reasoningEntryId
                ?.takeIf(String::isNotBlank)
                ?: "$parentTaskId-agent-thinking"
            val entry = state.reasoningEntries.getOrPut(entryId) {
                newTextEntry(state, entryId, parentTaskId)
            }
            entry.text = if (update.reasoningSnapshot != null) {
                mergeSnapshot(entry.text, update.reasoningSnapshot)
            } else {
                entry.text + update.reasoningDelta.orEmpty()
            }
            entry.isFinal = entry.isFinal || update.reasoningFinal
            persistReasoningEntry(state, entry)
            changed = true
        }

        update.tool?.let { toolUpdate ->
            val entryId = toolUpdate.entryId
                ?.takeIf(String::isNotBlank)
                ?: "${toolUpdate.parentTaskId ?: parentTaskId}-agent-tool"
            val toolParentTaskId = toolUpdate.parentTaskId
                ?.takeIf(String::isNotBlank)
                ?: parentTaskId
            val entry = state.toolEntries.getOrPut(entryId) {
                state.sequence += 1
                WebAgentToolEntryState(
                    entryId = entryId,
                    parentTaskId = toolParentTaskId,
                    createdAt = state.createdAt + state.sequence,
                    sequence = state.sequence,
                    update = toolUpdate
                )
            }
            entry.update = mergeToolUpdate(entry.update, toolUpdate)
            persistToolEntry(state, entry)
            changed = true
        }

        if (changed) {
            publishMessages(state, finalizeInterruptedEntries = false)
        }

        when (update.terminalKind) {
            "completed" -> finishSuccessfully(state)
            "error" -> finishWithError(
                state,
                update.errorMessage ?: "Agent 任务执行失败"
            )
        }
    }

    private fun newTextEntry(
        state: WebAgentRunState,
        entryId: String,
        parentTaskId: String
    ): WebAgentTextEntryState {
        state.sequence += 1
        return WebAgentTextEntryState(
            entryId = entryId,
            parentTaskId = parentTaskId,
            createdAt = state.createdAt + state.sequence,
            sequence = state.sequence
        )
    }

    private fun bindServerIds(
        state: WebAgentRunState,
        threadId: String?,
        turnId: String?
    ) {
        threadId?.trim()?.takeIf { it.isNotEmpty() }?.let { normalized ->
            state.threadId = normalized
            if (!state.finished.get()) {
                runsByThreadId[normalized] = state
            }
        }
        turnId?.trim()?.takeIf { it.isNotEmpty() }?.let { normalized ->
            state.turnId = normalized
            if (!state.finished.get()) {
                runsByTurnId[normalized] = state
            }
        }
    }

    private suspend fun finishSuccessfully(state: WebAgentRunState) {
        if (!state.finished.compareAndSet(false, true)) return
        state.assistantEntries.values.forEach { entry ->
            entry.isFinal = true
            persistAssistantEntry(state, entry, isError = false)
        }
        state.reasoningEntries.values.forEach { entry ->
            entry.isFinal = true
            persistReasoningEntry(state, entry)
        }
        state.toolEntries.values.forEach { entry ->
            if (entry.update.status == "running") {
                entry.update = entry.update.copy(status = "success")
                persistToolEntry(state, entry)
            }
        }
        publishMessages(state, finalizeInterruptedEntries = true)
        publishTaskEvent(state, "completed")
        removeState(state)
    }

    private suspend fun finishWithError(
        state: WebAgentRunState,
        message: String
    ) {
        if (!state.finished.compareAndSet(false, true)) return
        val entry = state.assistantEntries.values.lastOrNull() ?: run {
            val parentTaskId = state.turnId ?: state.taskId
            val entryId = "$parentTaskId-agent-message"
            newTextEntry(state, entryId, parentTaskId).also {
                state.assistantEntries[entryId] = it
            }
        }
        if (entry.text.isBlank()) {
            entry.text = AgentTextSanitizer.sanitizeUtf16(message)
        }
        entry.isFinal = true
        persistAssistantEntry(state, entry, isError = true)
        state.reasoningEntries.values.forEach { reasoning ->
            reasoning.isFinal = true
            persistReasoningEntry(state, reasoning)
        }
        state.toolEntries.values.forEach { tool ->
            if (tool.update.status == "running") {
                tool.update = tool.update.copy(status = "error")
                persistToolEntry(state, tool)
            }
        }
        publishMessages(state, finalizeInterruptedEntries = true)
        publishTaskEvent(state, "error", message)
        removeState(state)
    }

    private suspend fun persistAssistantEntry(
        state: WebAgentRunState,
        entry: WebAgentTextEntryState,
        isError: Boolean
    ) {
        historyRepository.upsertAssistantMessage(
            conversationId = state.conversationId,
            conversationMode = state.conversationMode,
            entryId = entry.entryId,
            text = AgentTextSanitizer.sanitizeUtf16(entry.text),
            isError = isError,
            streamMeta = streamMeta(
                state = state,
                entryId = entry.entryId,
                parentTaskId = entry.parentTaskId,
                sequence = entry.sequence,
                kind = "text_snapshot",
                isFinal = entry.isFinal
            ),
            createdAt = entry.createdAt
        )
    }

    private suspend fun persistReasoningEntry(
        state: WebAgentRunState,
        entry: WebAgentTextEntryState
    ) {
        historyRepository.upsertUiCard(
            conversationId = state.conversationId,
            conversationMode = state.conversationMode,
            entryId = entry.entryId,
            cardData = linkedMapOf(
                "type" to "deep_thinking",
                "agentId" to state.agentId,
                "agentName" to state.agentName,
                "taskID" to entry.parentTaskId,
                "cardId" to entry.entryId,
                "thinkingContent" to AgentTextSanitizer.sanitizeUtf16(entry.text),
                "stage" to if (entry.isFinal) 4 else 1,
                "isLoading" to !entry.isFinal,
                "startTime" to entry.createdAt,
                "endTime" to if (entry.isFinal) System.currentTimeMillis() else null,
                "isCollapsible" to true
            ).filterValues { it != null },
            streamMeta = streamMeta(
                state = state,
                entryId = entry.entryId,
                parentTaskId = entry.parentTaskId,
                sequence = entry.sequence,
                kind = "thinking_snapshot",
                isFinal = entry.isFinal
            ),
            createdAt = entry.createdAt
        )
    }

    private suspend fun persistToolEntry(
        state: WebAgentRunState,
        entry: WebAgentToolEntryState
    ) {
        val update = entry.update
        val raw = update.raw
        val toolName = firstNonBlank(
            raw["toolName"],
            raw["tool_name"],
            raw["name"],
            raw["command"],
            update.itemType
        ) ?: "agent.tool"
        val toolType = inferToolType(update.itemType, toolName)
        val title = firstNonBlank(
            raw["toolTitle"],
            raw["title"],
            raw["displayName"],
            raw["name"],
            raw["command"]
        ) ?: defaultToolTitle(toolType)
        val summary = firstNonBlank(
            raw["summary"],
            raw["message"],
            raw["description"],
            raw["progress"]
        ).orEmpty()
        val terminalOutput = firstNonBlank(
            raw["terminalOutput"],
            raw["aggregatedOutput"],
            raw["aggregated_output"],
            raw["output"],
            raw["stdout"]
        ).orEmpty()
        val arguments = normalizeMap(raw["arguments"]).ifEmpty {
            normalizeMap(raw["args"])
        }
        historyRepository.upsertToolEvent(
            conversationId = state.conversationId,
            conversationMode = state.conversationMode,
            entryId = entry.entryId,
            payload = linkedMapOf<String, Any?>(
                "taskId" to entry.parentTaskId,
                "agentId" to state.agentId,
                "agentName" to state.agentName,
                "uiStyle" to "agent_tool",
                "cardId" to entry.entryId,
                "toolName" to toolName,
                "displayName" to title,
                "toolTitle" to title,
                "toolType" to toolType,
                "serverName" to firstNonBlank(raw["serverName"], raw["server"]),
                "status" to update.status,
                "summary" to summary,
                "progress" to firstNonBlank(raw["progress"], raw["message"]).orEmpty(),
                "argsJson" to arguments.takeIf { it.isNotEmpty() }?.let(gson::toJson),
                "resultPreviewJson" to raw["result"]?.let(gson::toJson),
                "rawResultJson" to gson.toJson(raw),
                "terminalOutput" to terminalOutput,
                "streamMeta" to streamMeta(
                    state = state,
                    entryId = entry.entryId,
                    parentTaskId = entry.parentTaskId,
                    sequence = entry.sequence,
                    kind = if (update.status == "running") "tool_progress" else "tool_completed",
                    isFinal = update.status != "running"
                )
            ).filterValues { it != null },
            fallbackStatus = update.status,
            fallbackSummary = summary.ifBlank { title }
        )
    }

    private fun streamMeta(
        state: WebAgentRunState,
        entryId: String,
        parentTaskId: String,
        sequence: Long,
        kind: String,
        isFinal: Boolean
    ): Map<String, Any?> {
        return linkedMapOf(
            "seq" to sequence,
            "entrySeq" to sequence,
            "roundIndex" to sequence,
            "kind" to kind,
            "parentTaskId" to parentTaskId,
            "entryId" to entryId,
            "isFinal" to isFinal,
            "agentId" to state.agentId,
            "agentName" to state.agentName
        ).filterValues { it != null }
    }

    private suspend fun publishMessages(
        state: WebAgentRunState,
        finalizeInterruptedEntries: Boolean
    ) {
        val messages = historyRepository.listConversationMessages(
            conversationId = state.conversationId,
            conversationMode = state.conversationMode,
            finalizeInterruptedEntries = finalizeInterruptedEntries
        )
        RealtimeHub.publish(
            "messages_replaced",
            mapOf(
                "conversationId" to state.conversationId,
                "mode" to state.conversationMode,
                "messages" to messages
            )
        )
    }

    private fun publishTaskEvent(
        state: WebAgentRunState,
        kind: String,
        error: String? = null
    ) {
        RealtimeHub.publish(
            "chat_task_event",
            linkedMapOf<String, Any?>(
                "kind" to kind,
                "taskId" to state.taskId,
                "conversationId" to state.conversationId,
                "conversationMode" to state.conversationMode,
                "threadId" to state.threadId,
                "turnId" to state.turnId,
                "error" to error
            ).filterValues { it != null }
        )
    }

    private fun removeState(state: WebAgentRunState) {
        runsByTaskId.remove(state.taskId, state)
        runsByConversationId.remove(state.conversationId, state)
        state.threadId?.let { runsByThreadId.remove(it, state) }
        state.turnId?.let { runsByTurnId.remove(it, state) }
    }

    private fun mergeSnapshot(existing: String, incoming: String): String {
        val safeIncoming = AgentTextSanitizer.sanitizeUtf16(incoming)
        if (safeIncoming.isEmpty()) return existing
        if (safeIncoming.startsWith(existing)) return safeIncoming
        if (existing.startsWith(safeIncoming)) return existing
        return safeIncoming
    }

}

internal fun buildWebAgentTurnArguments(
    conversationId: Long,
    userMessage: String,
    attachments: List<Map<String, Any?>>,
    cwd: String?,
    agentId: String? = null,
    model: String? = null,
    effort: String? = null,
    conversationMode: String = WEB_AGENT_MODE_STORAGE_VALUE
): Map<String, Any?> {
    return linkedMapOf<String, Any?>(
        "conversationId" to conversationId,
        "text" to userMessage,
        "attachments" to attachments,
        "approvalPolicy" to "never",
        "approvalsReviewer" to "user",
        "sandboxPolicy" to mapOf("type" to "dangerFullAccess"),
        "conversationMode" to conversationMode
    ).apply {
        agentId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            this["agentId"] = it
        }
        cwd?.trim()?.takeIf { it.isNotEmpty() }?.let {
            this["cwd"] = it
        }
        model?.trim()?.takeIf { it.isNotEmpty() }?.let {
            this["model"] = it
        }
        effort?.trim()?.takeIf { it.isNotEmpty() }?.let {
            this["effort"] = it
        }
    }
}

internal fun parseWebAgentEvent(event: Map<String, Any?>): WebAgentEventUpdate {
    val method = event.readString("method").orEmpty()
    val normalizedMethod = normalizeAgentEventToken(method)
    val params = normalizeMap(event["params"])
    val item = normalizeMap(params["item"])
    val turnId = firstNonBlank(
        event["turnId"],
        params["turnId"],
        params["turn_id"]
    )
    val threadId = firstNonBlank(
        event["threadId"],
        params["threadId"],
        params["thread_id"]
    )
    val directItemId = resolveAgentItemId(params, item)
    val parentTaskId = turnId ?: directItemId ?: threadId

    if (normalizedMethod == "session_update") {
        val update = normalizeMap(params["update"])
        val updateKind = normalizeAgentEventToken(
            update["sessionUpdate"]?.toString().orEmpty()
        )
        val updateId = resolveAgentItemId(update, update)
        val updateParent = firstNonBlank(
            turnId,
            update["turnId"],
            update["turn_id"],
            updateId,
            threadId
        )
        val messageEntryId = agentEntryId(updateId ?: updateParent, "message")
        val reasoningEntryId = agentEntryId(updateId ?: updateParent, "thinking")
        return when (updateKind) {
            "agent_message_chunk" -> WebAgentEventUpdate(
                assistantEntryId = messageEntryId,
                assistantDelta = extractAgentText(update["content"]),
                parentTaskId = updateParent
            )
            "agent_thought_chunk" -> WebAgentEventUpdate(
                reasoningEntryId = reasoningEntryId,
                reasoningDelta = extractAgentText(update["content"]),
                parentTaskId = updateParent
            )
            "tool_call", "tool_call_update" -> WebAgentEventUpdate(
                parentTaskId = updateParent,
                tool = buildToolUpdate(
                    raw = update,
                    itemType = update["kind"]?.toString()
                        ?: update["title"]?.toString()
                        ?: "tool",
                    itemId = updateId ?: updateParent,
                    parentTaskId = updateParent,
                    fallbackStatus = if (updateKind == "tool_call") {
                        "running"
                    } else {
                        resolveAgentToolPayloadStatus(update, "running")
                    }
                )
            )
            else -> WebAgentEventUpdate(parentTaskId = updateParent)
        }
    }

    if (normalizedMethod == "turn_completed" || normalizedMethod == "thread_closed") {
        return WebAgentEventUpdate(
            parentTaskId = parentTaskId,
            terminalKind = "completed"
        )
    }
    if (
        normalizedMethod == "turn_failed" ||
        (normalizedMethod == "error" && params["willRetry"] != true)
    ) {
        return WebAgentEventUpdate(
            parentTaskId = parentTaskId,
            terminalKind = "error",
            errorMessage = extractAgentText(params["error"])
                .ifEmpty { extractAgentText(params["message"]) }
                .ifEmpty { "Agent 任务执行失败" }
        )
    }

    if (normalizedMethod == "codex_event") {
        val protocol = findRemoteCodexProtocolMessage(params)
        val protocolType = normalizeAgentEventToken(
            protocol["type"]?.toString().orEmpty()
        )
        val protocolItemId = resolveAgentItemId(protocol, protocol)
        val protocolParent = firstNonBlank(
            protocol["turnId"],
            protocol["turn_id"],
            turnId,
            protocolItemId,
            threadId
        )
        val assistantEntryId = agentEntryId(
            protocolItemId ?: protocolParent,
            "message"
        )
        val reasoningEntryId = agentEntryId(
            protocolItemId ?: protocolParent,
            "thinking"
        )
        return when {
            protocolType in setOf(
                "agent_message_delta",
                "assistant_message_delta",
                "output_text_delta"
            ) -> WebAgentEventUpdate(
                assistantEntryId = assistantEntryId,
                assistantDelta = extractAgentDelta(protocol),
                parentTaskId = protocolParent
            )
            protocolType in setOf(
                "agent_message",
                "assistant_message",
                "output_text"
            ) -> WebAgentEventUpdate(
                assistantEntryId = assistantEntryId,
                assistantSnapshot = extractAgentText(protocol).takeIf(String::isNotEmpty),
                parentTaskId = protocolParent
            )
            protocolType in setOf(
                "reasoning_delta",
                "reasoning_content_delta",
                "reasoning_text_delta",
                "agent_reasoning_delta"
            ) -> WebAgentEventUpdate(
                reasoningEntryId = reasoningEntryId,
                reasoningDelta = extractAgentDelta(protocol),
                parentTaskId = protocolParent
            )
            protocolType in setOf(
                "reasoning",
                "reasoning_content",
                "reasoning_summary"
            ) -> WebAgentEventUpdate(
                reasoningEntryId = reasoningEntryId,
                reasoningSnapshot = extractAgentText(protocol).takeIf(String::isNotEmpty),
                parentTaskId = protocolParent
            )
            protocolType in setOf(
                "task_complete",
                "turn_complete",
                "turn_completed"
            ) -> WebAgentEventUpdate(
                parentTaskId = protocolParent,
                terminalKind = "completed"
            )
            protocolType in setOf(
                "turn_aborted",
                "task_failed",
                "turn_failed",
                "error"
            ) -> WebAgentEventUpdate(
                parentTaskId = protocolParent,
                terminalKind = "error",
                errorMessage = extractAgentText(protocol).ifEmpty {
                    "Agent 任务执行失败"
                }
            )
            isAgentToolEventType(protocolType) -> WebAgentEventUpdate(
                parentTaskId = protocolParent,
                tool = buildToolUpdate(
                    raw = protocol,
                    itemType = protocolType,
                    itemId = protocolItemId ?: protocolParent,
                    parentTaskId = protocolParent,
                    fallbackStatus = agentProtocolToolStatus(protocolType)
                )
            )
            else -> WebAgentEventUpdate(parentTaskId = protocolParent)
        }
    }

    val assistantEntryId = agentEntryId(directItemId ?: parentTaskId, "message")
    val reasoningEntryId = agentEntryId(directItemId ?: parentTaskId, "thinking")
    if (
        normalizedMethod == "item_agentmessage_delta" ||
        normalizedMethod == "item_agent_message_delta"
    ) {
        return WebAgentEventUpdate(
            assistantEntryId = assistantEntryId,
            assistantDelta = extractAgentDelta(params),
            parentTaskId = parentTaskId
        )
    }
    if (
        normalizedMethod.contains("reasoning") &&
        normalizedMethod.contains("delta")
    ) {
        return WebAgentEventUpdate(
            reasoningEntryId = reasoningEntryId,
            reasoningDelta = extractAgentDelta(params),
            parentTaskId = parentTaskId
        )
    }
    if (
        normalizedMethod == "item_completed" ||
        normalizedMethod == "item_updated" ||
        normalizedMethod == "item_started" ||
        normalizedMethod == "rawresponseitem_completed" ||
        normalizedMethod == "raw_response_item_completed"
    ) {
        val canonicalItemType = canonicalAgentItemType(item["type"]?.toString())
        val text = extractAgentText(item)
        val completed = normalizedMethod.contains("completed")
        return when {
            canonicalItemType == "agentMessage" ||
                (
                    canonicalItemType == "message" &&
                        item["role"]?.toString() == "assistant"
                    ) -> WebAgentEventUpdate(
                assistantEntryId = assistantEntryId,
                assistantSnapshot = text.takeIf(String::isNotEmpty),
                assistantFinal = completed,
                parentTaskId = parentTaskId
            )
            canonicalItemType == "reasoning" ||
                canonicalItemType.startsWith("reasoning") -> WebAgentEventUpdate(
                reasoningEntryId = reasoningEntryId,
                reasoningSnapshot = text.takeIf(String::isNotEmpty),
                reasoningFinal = completed,
                parentTaskId = parentTaskId
            )
            isAgentToolItemType(canonicalItemType) -> WebAgentEventUpdate(
                parentTaskId = parentTaskId,
                tool = buildToolUpdate(
                    raw = item,
                    itemType = canonicalItemType,
                    itemId = directItemId ?: parentTaskId,
                    parentTaskId = parentTaskId,
                    fallbackStatus = if (completed) "success" else "running"
                )
            )
            else -> WebAgentEventUpdate(parentTaskId = parentTaskId)
        }
    }
    return WebAgentEventUpdate(parentTaskId = parentTaskId)
}

private fun buildToolUpdate(
    raw: Map<String, Any?>,
    itemType: String,
    itemId: String?,
    parentTaskId: String?,
    fallbackStatus: String
): WebAgentToolUpdate {
    val canonicalType = canonicalAgentItemType(itemType)
    val toolName = firstNonBlank(
        raw["toolName"],
        raw["tool_name"],
        raw["name"],
        raw["command"],
        canonicalType
    ).orEmpty()
    val suffix = agentToolCardSuffix(canonicalType, inferToolType(canonicalType, toolName))
    return WebAgentToolUpdate(
        entryId = agentEntryId(itemId, suffix),
        parentTaskId = parentTaskId,
        itemType = canonicalType,
        status = resolveAgentToolPayloadStatus(raw, fallbackStatus),
        raw = raw
    )
}

private fun mergeToolUpdate(
    existing: WebAgentToolUpdate,
    incoming: WebAgentToolUpdate
): WebAgentToolUpdate {
    val status = if (agentToolStatusRank(incoming.status) >=
        agentToolStatusRank(existing.status)
    ) {
        incoming.status
    } else {
        existing.status
    }
    return incoming.copy(
        entryId = incoming.entryId ?: existing.entryId,
        parentTaskId = incoming.parentTaskId ?: existing.parentTaskId,
        status = status,
        raw = existing.raw + incoming.raw
    )
}

private fun resolveAgentItemId(
    container: Map<String, Any?>,
    item: Map<String, Any?>
): String? {
    return firstNonBlank(
        container["itemId"],
        container["item_id"],
        container["callId"],
        container["call_id"],
        container["toolCallId"],
        container["tool_call_id"],
        item["id"],
        item["callId"],
        item["call_id"],
        item["toolCallId"],
        item["tool_call_id"],
        container["processId"],
        container["processHandle"],
        container["id"]
    )
}

private fun agentEntryId(base: String?, suffix: String): String? {
    return base?.trim()?.takeIf(String::isNotEmpty)?.let { "$it-agent-$suffix" }
}

private fun canonicalAgentItemType(raw: String?): String {
    val normalized = raw?.trim().orEmpty()
    return when (normalized) {
        "agent_message" -> "agentMessage"
        "user_message" -> "userMessage"
        "command_execution" -> "commandExecution"
        "file_change" -> "fileChange"
        "mcp_tool_call" -> "mcpToolCall"
        "dynamic_tool_call" -> "dynamicToolCall"
        "web_search" -> "webSearch"
        "image_view" -> "imageView"
        "image_generation" -> "imageGeneration"
        "collab_agent_tool_call" -> "collabAgentToolCall"
        "collab_tool_call" -> "collabToolCall"
        "todo_list" -> "plan"
        else -> normalized
    }
}

private fun isAgentToolItemType(itemType: String): Boolean {
    return canonicalAgentItemType(itemType) in setOf(
        "commandExecution",
        "local_shell_call",
        "commandExec",
        "processExecution",
        "fileChange",
        "tool",
        "mcpToolCall",
        "dynamicToolCall",
        "function_call",
        "function_call_output",
        "custom_tool_call",
        "custom_tool_call_output",
        "tool_search_call",
        "tool_search_output",
        "webSearch",
        "web_search_call",
        "imageView",
        "imageGeneration",
        "image_generation_call",
        "collabAgentToolCall",
        "collabToolCall",
        "plan"
    )
}

private fun isAgentToolEventType(type: String): Boolean {
    return isAgentToolItemType(type) ||
        type.contains("tool") ||
        type.contains("command") ||
        type.contains("exec") ||
        type.contains("search") ||
        type.contains("file_change")
}

private fun agentProtocolToolStatus(type: String): String {
    return when {
        type.contains("fail") || type.contains("error") -> "error"
        type.contains("abort") || type.contains("cancel") -> "interrupted"
        type.contains("complete") || type.contains("end") || type.contains("output") ->
            "success"
        else -> "running"
    }
}

private fun agentToolStatusRank(status: String): Int {
    return when (status) {
        // ACP permits a pending update while the tool is waiting for the
        // client to answer session/request_permission. Treat pending and
        // running as the same non-terminal phase so that transition is not
        // discarded by the monotonic merge.
        "pending", "running" -> 0
        "interrupted" -> 1
        "timeout" -> 2
        "error" -> 3
        "success" -> 4
        else -> 0
    }
}

private fun inferToolType(itemType: String, toolName: String): String {
    val raw = "$itemType $toolName".lowercase()
    return when {
        raw.contains("command") || raw.contains("shell") || raw.contains("exec") ||
            raw.contains("process") -> "terminal"
        raw.contains("file") || raw.contains("read") || raw.contains("write") ||
            raw.contains("edit") -> "file"
        raw.contains("search") -> "search"
        raw.contains("browser") || raw.contains("web") || raw.contains("navigate") ->
            "browser"
        raw.contains("image") -> "image"
        raw.contains("mcp") -> "mcp"
        raw.contains("collab") || raw.contains("agent") -> "subagent"
        raw.contains("plan") -> "plan"
        else -> "builtin"
    }
}

private fun agentToolCardSuffix(itemType: String, toolType: String): String {
    return when {
        itemType == "fileChange" || toolType == "file" -> "file"
        itemType == "plan" || toolType == "plan" -> "plan"
        toolType == "search" -> "search"
        toolType == "browser" -> "browser"
        toolType == "image" -> "image"
        toolType == "terminal" -> "command"
        else -> "tool"
    }
}

private fun defaultToolTitle(toolType: String): String {
    return when (toolType) {
        "terminal" -> "运行命令"
        "file" -> "处理文件"
        "search" -> "搜索"
        "browser" -> "浏览网页"
        "image" -> "处理图片"
        "subagent" -> "运行子任务"
        else -> "工具调用"
    }
}

private fun extractAgentDelta(value: Any?): String {
    val map = normalizeMap(value)
    return sequenceOf("delta", "text", "outputText", "output_text", "content")
        .map { key -> extractAgentText(map[key]) }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
}

private fun extractAgentText(value: Any?, depth: Int = 0): String {
    if (depth > 8 || value == null) return ""
    return when (value) {
        is String -> value
        is Number, is Boolean -> ""
        is List<*> -> value.joinToString("") { item ->
            extractAgentText(item, depth + 1)
        }
        is Map<*, *> -> {
            val map = normalizeMap(value)
            sequenceOf(
                "text",
                "delta",
                "output_text",
                "outputText",
                "content",
                "message",
                "summary"
            ).map { key -> extractAgentText(map[key], depth + 1) }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
        }
        else -> ""
    }
}

private fun findRemoteCodexProtocolMessage(
    value: Any?,
    depth: Int = 0
): Map<String, Any?> {
    if (depth > 8) return emptyMap()
    val map = normalizeMap(value)
    val direct = normalizeMap(map["msg"])
    if (direct.isNotEmpty()) return direct
    val type = map["type"]?.toString()?.trim().orEmpty()
    if (type.isNotEmpty()) return map
    for (key in listOf("event", "message", "data", "payload", "params")) {
        val nested = findRemoteCodexProtocolMessage(map[key], depth + 1)
        if (nested.isNotEmpty()) return nested
    }
    return emptyMap()
}

private fun normalizeAgentEventToken(value: String): String {
    return value.trim()
        .lowercase()
        .replace('/', '_')
        .replace('.', '_')
        .replace('-', '_')
}

private fun normalizeMap(value: Any?): Map<String, Any?> {
    return (value as? Map<*, *>)?.entries?.associate { (key, rawValue) ->
        key.toString() to rawValue
    }.orEmpty()
}

private fun firstNonBlank(vararg values: Any?): String? {
    return values.asSequence()
        .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull()
}

private fun Map<String, Any?>.readString(key: String): String? {
    return this[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

private fun Map<String, Any?>.readLong(key: String): Long? {
    return when (val raw = this[key]) {
        is Number -> raw.toLong()
        is String -> raw.trim().toLongOrNull()
        else -> null
    }
}
