@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentConversationModePolicy
import cn.com.omnimind.bot.agent.AgentResult
import cn.com.omnimind.bot.agent.AgentRuntimeContextRepository
import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import cn.com.omnimind.bot.agent.NoOpAgentRunControl
import cn.com.omnimind.bot.agent.OmniAgentExecutor
import cn.com.omnimind.bot.agent.ToolExecutionResult
import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.Implementation
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.rpc.JsonRpcMessage
import com.agentclientprotocol.transport.BaseTransport
import com.agentclientprotocol.transport.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/**
 * Xiaowan is a built-in ACP Agent. The loopback transport is only the official
 * ACP SDK transport boundary; no app-private request or event protocol exists.
 */
internal class XiaowanAcpConnection(
    private val context: Context,
    private val scope: CoroutineScope,
) : AcpRuntimeConnection {
    private lateinit var clientTransport: LoopbackTransport
    private lateinit var serverTransport: LoopbackTransport
    private lateinit var serverProtocol: Protocol

    override val exitSignal = CompletableDeferred<Int?>()
    override val isRunning: Boolean
        get() = ::clientTransport.isInitialized && clientTransport.started

    override fun createTransport(parentScope: CoroutineScope): Transport {
        clientTransport = LoopbackTransport()
        serverTransport = LoopbackTransport()
        clientTransport.peer = serverTransport
        serverTransport.peer = clientTransport
        serverProtocol = Protocol(parentScope, serverTransport)
        Agent(serverProtocol, XiaowanAgentSupport(context, scope))
        return clientTransport
    }

    override suspend fun start() {
        serverProtocol.start()
        serverTransport.start()
    }

    override fun diagnosticSummary(): String = ""

    override fun exitDescription(exitCode: Int?): String =
        "Built-in Xiaowan ACP Agent closed before initialize completed"

    override suspend fun close() {
        if (::serverProtocol.isInitialized) serverProtocol.close()
        if (::clientTransport.isInitialized) clientTransport.close()
        if (::serverTransport.isInitialized) serverTransport.close()
    }
}

private class XiaowanAgentSupport(
    private val context: Context,
    private val scope: CoroutineScope,
) : AgentSupport {
    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo = AgentInfo(
        protocolVersion = 1,
        capabilities = AgentCapabilities(),
        authMethods = emptyList(),
        implementation = Implementation(
            name = "xiaowan",
            version = BuildConfig.VERSION_NAME,
            title = "小万",
        ),
        _meta = JsonNull,
    )

    override suspend fun createSession(
        parameters: SessionCreationParameters,
    ): AgentSession = XiaowanAgentSession(
        context = context,
        scope = scope,
        sessionId = SessionId(UUID.randomUUID().toString()),
    )
}

private class XiaowanAgentSession(
    private val context: Context,
    scope: CoroutineScope,
    override val sessionId: SessionId,
) : AgentSession {
    private val messages = mutableListOf<ChatCompletionMessage>()
    private val executor = OmniAgentExecutor(
        context = context,
        scope = scope,
        scheduleToolBridge = NoOpScheduleToolBridge,
    )

    override suspend fun prompt(
        content: List<ContentBlock>,
        _meta: JsonElement?,
    ): Flow<Event> = flow {
        val text = content.joinToString("") { block ->
            when (block) {
                is ContentBlock.Text -> block.text
                is ContentBlock.ResourceLink -> block.title ?: block.name
                else -> ""
            }
        }.trim()
        require(text.isNotEmpty()) { "Xiaowan ACP prompt is empty" }
        val streamBridge = XiaowanAcpEventBridge { update ->
            emit(Event.SessionUpdateEvent(update))
        }
        val result = executor.processUserMessage(
            userMessage = text,
            conversationHistory = emptyList(),
            runtimeContextRepository = AgentRuntimeContextRepository(context),
            attachments = emptyList(),
            conversationId = null,
            conversationMode = AgentConversationModePolicy.NORMAL_MODE,
            modelOverride = null,
            reasoningEffort = null,
            terminalEnvironment = emptyMap(),
            callback = streamBridge,
            runControl = NoOpAgentRunControl,
            historyMessagesOverride = messages.toList(),
        )
        val answer = when (result) {
            is AgentResult.Success -> result.response.content
            is AgentResult.Error -> throw result.exception
                ?: IllegalStateException(result.message)
        }
        messages += ChatCompletionMessage(
            role = "user",
            content = JsonPrimitive(text),
        )
        messages += ChatCompletionMessage(
            role = "assistant",
            content = JsonPrimitive(answer),
        )
        // The executor reports cumulative snapshots through AgentCallback. The
        // bridge already converted those snapshots to ACP chunks; this final
        // call only fills a gap when a provider returned content without any
        // callback update, and is de-duplicated by the same bridge.
        streamBridge.emitAssistantSnapshot(answer)
        emit(
            Event.PromptResponseEvent(
                PromptResponse(
                    stopReason = StopReason.END_TURN,
                    _meta = JsonNull,
                )
            )
        )
    }
}

/** Convert the executor's cumulative snapshots into append-only ACP chunks. */
internal fun acpSnapshotDelta(previous: String, next: String): String? {
    if (next.isEmpty() || next == previous) return null
    if (previous.isEmpty()) return next
    if (next.startsWith(previous)) return next.removePrefix(previous).ifEmpty { null }
    // A provider retry can reset the snapshot. Emit the new snapshot as a new
    // chunk rather than concatenating unrelated generations.
    if (previous.startsWith(next)) return null
    return next
}

private class XiaowanAcpEventBridge(
    private val emitUpdate: suspend (SessionUpdate) -> Unit,
) : AgentCallback {
    private var assistantSnapshot = ""
    private var thoughtSnapshot = ""
    private var assistantMessageId = MessageId(UUID.randomUUID().toString())
    private var thoughtMessageId = MessageId(UUID.randomUUID().toString())
    private val toolIdsByName = mutableMapOf<String, String>()

    suspend fun emitAssistantSnapshot(snapshot: String) {
        emitTextSnapshot(
            snapshot = snapshot,
            previous = assistantSnapshot,
            messageId = assistantMessageId,
            emit = { delta, id ->
                assistantSnapshot = snapshot
                assistantMessageId = id
                emitUpdate(
                    SessionUpdate.AgentMessageChunk(
                        content = ContentBlock.Text(delta),
                        messageId = id,
                        _meta = JsonNull,
                    )
                )
            }
        )
    }

    override suspend fun onThinkingStart() {
        thoughtSnapshot = ""
        thoughtMessageId = MessageId(UUID.randomUUID().toString())
    }

    override suspend fun onThinkingUpdate(thinking: String) {
        emitTextSnapshot(
            snapshot = thinking,
            previous = thoughtSnapshot,
            messageId = thoughtMessageId,
            emit = { delta, id ->
                thoughtSnapshot = thinking
                thoughtMessageId = id
                emitUpdate(
                    SessionUpdate.AgentThoughtChunk(
                        content = ContentBlock.Text(delta),
                        messageId = id,
                        _meta = JsonNull,
                    )
                )
            }
        )
    }

    override suspend fun onToolCallStart(
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject,
    ) {
        emitToolStart(UUID.randomUUID().toString(), toolName, arguments)
    }

    override suspend fun onToolCallStart(
        toolCallId: String,
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject,
    ) {
        emitToolStart(toolCallId.ifBlank { UUID.randomUUID().toString() }, toolName, arguments)
    }

    private suspend fun emitToolStart(
        toolCallId: String,
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject,
    ) {
        toolIdsByName[toolName] = toolCallId
        emitUpdate(
            SessionUpdate.ToolCall(
                toolCallId = ToolCallId(toolCallId),
                title = toolName,
                kind = ToolKind.OTHER,
                status = ToolCallStatus.IN_PROGRESS,
                content = listOf(
                    ToolCallContent.Content(ContentBlock.Text(arguments.toString()))
                ),
                locations = emptyList(),
                rawInput = arguments,
                rawOutput = JsonNull,
                _meta = JsonNull,
            )
        )
    }

    override suspend fun onToolCallProgress(
        toolName: String,
        progress: String,
        extras: Map<String, Any?>,
    ) {
        val toolCallId = toolIdsByName[toolName] ?: UUID.randomUUID().toString().also {
            toolIdsByName[toolName] = it
        }
        emitUpdate(
            SessionUpdate.ToolCallUpdate(
                toolCallId = ToolCallId(toolCallId),
                title = toolName,
                kind = ToolKind.OTHER,
                status = ToolCallStatus.IN_PROGRESS,
                content = listOf(
                    ToolCallContent.Content(ContentBlock.Text(progress))
                ),
                locations = emptyList(),
                rawInput = JsonNull,
                rawOutput = JsonNull,
                _meta = JsonNull,
            )
        )
    }

    override suspend fun onToolCallComplete(
        toolName: String,
        result: ToolExecutionResult,
    ) {
        emitToolComplete(toolIdsByName[toolName], toolName, result)
    }

    override suspend fun onToolCallComplete(
        toolCallId: String,
        toolName: String,
        result: ToolExecutionResult,
    ) {
        emitToolComplete(
            toolCallId.ifBlank { toolIdsByName[toolName] },
            toolName,
            result,
        )
    }

    private suspend fun emitToolComplete(
        toolCallId: String?,
        toolName: String,
        result: ToolExecutionResult,
    ) {
        val resolvedToolCallId = toolCallId ?: UUID.randomUUID().toString()
        toolIdsByName[toolName] = resolvedToolCallId
        val text = toolResultText(result)
        emitUpdate(
            SessionUpdate.ToolCallUpdate(
                toolCallId = ToolCallId(resolvedToolCallId),
                title = toolName,
                kind = ToolKind.OTHER,
                status = if (toolResultSucceeded(result)) {
                    ToolCallStatus.COMPLETED
                } else {
                    ToolCallStatus.FAILED
                },
                content = text.takeIf(String::isNotEmpty)?.let {
                    listOf(ToolCallContent.Content(ContentBlock.Text(it)))
                }.orEmpty(),
                locations = emptyList(),
                rawInput = JsonNull,
                rawOutput = JsonPrimitive(text),
                _meta = JsonNull,
            )
        )
    }

    override suspend fun onChatMessage(message: String) {
        emitAssistantSnapshot(message)
    }

    override suspend fun onChatMessage(message: String, isFinal: Boolean) {
        emitAssistantSnapshot(message)
    }

    override suspend fun onChatMessage(
        message: String,
        isFinal: Boolean,
        prefillTokensPerSecond: Double?,
        decodeTokensPerSecond: Double?,
    ) {
        emitAssistantSnapshot(message)
    }

    override suspend fun onClarifyRequired(question: String, missingFields: List<String>?) = Unit
    override suspend fun onComplete(result: AgentResult) = Unit
    override suspend fun onError(error: String) = Unit
    override suspend fun onPermissionRequired(missing: List<String>) = Unit

    private suspend fun emitTextSnapshot(
        snapshot: String,
        previous: String,
        messageId: MessageId,
        emit: suspend (String, MessageId) -> Unit,
    ) {
        val delta = acpSnapshotDelta(previous, snapshot) ?: return
        val id = if (previous.isNotEmpty() && !snapshot.startsWith(previous)) {
            MessageId(UUID.randomUUID().toString())
        } else {
            messageId
        }
        emit(delta, id)
    }
}

private fun toolResultSucceeded(result: ToolExecutionResult): Boolean = when (result) {
    is ToolExecutionResult.Error,
    is ToolExecutionResult.Interrupted,
    is ToolExecutionResult.PermissionRequired -> false
    is ToolExecutionResult.TerminalResult -> result.success
    is ToolExecutionResult.ScheduleResult -> result.success
    is ToolExecutionResult.McpResult -> result.success
    is ToolExecutionResult.MemoryResult -> result.success
    is ToolExecutionResult.ContextResult -> result.success
    is ToolExecutionResult.ChatMessage,
    is ToolExecutionResult.Clarify -> true
}

private fun toolResultText(result: ToolExecutionResult): String = when (result) {
    is ToolExecutionResult.ChatMessage -> result.message
    is ToolExecutionResult.Clarify -> result.question
    is ToolExecutionResult.Error -> result.message
    is ToolExecutionResult.PermissionRequired -> result.missing.joinToString(", ")
    is ToolExecutionResult.ScheduleResult -> result.summaryText
    is ToolExecutionResult.McpResult -> result.summaryText.ifBlank { result.rawResultJson }
    is ToolExecutionResult.MemoryResult -> result.summaryText.ifBlank { result.rawResultJson }
    is ToolExecutionResult.TerminalResult -> result.summaryText.ifBlank { result.terminalOutput }
    is ToolExecutionResult.Interrupted -> result.summaryText
    is ToolExecutionResult.ContextResult -> result.summaryText.ifBlank { result.rawResultJson }
}

private object NoOpScheduleToolBridge : AgentScheduleToolBridge {
    override suspend fun createTask(arguments: Map<String, Any?>): Map<String, Any?> =
        mapOf("success" to false, "error" to "Scheduling is not available through ACP session metadata.")

    override suspend fun listTasks(): List<Map<String, Any?>> = emptyList()

    override suspend fun updateTask(arguments: Map<String, Any?>): Map<String, Any?> =
        mapOf("success" to false, "error" to "Scheduling is not available through ACP session metadata.")

    override suspend fun deleteTask(arguments: Map<String, Any?>): Map<String, Any?> =
        mapOf("success" to false, "error" to "Scheduling is not available through ACP session metadata.")
}

private class LoopbackTransport : BaseTransport() {
    var peer: LoopbackTransport? = null
    var started: Boolean = false

    override fun start() {
        started = true
    }

    override fun send(message: JsonRpcMessage) {
        peer?.deliver(message)
    }

    private fun deliver(message: JsonRpcMessage) {
        fireMessage(message)
    }

    override fun close() {
        started = false
        fireClose()
    }
}
