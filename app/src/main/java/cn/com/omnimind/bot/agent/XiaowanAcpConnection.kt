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
            callback = NoOpAgentCallback,
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
        if (answer.isNotEmpty()) {
            emit(
                Event.SessionUpdateEvent(
                    SessionUpdate.AgentMessageChunk(
                        content = ContentBlock.Text(answer),
                        messageId = MessageId(UUID.randomUUID().toString()),
                        _meta = JsonNull,
                    )
                )
            )
        }
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

private object NoOpScheduleToolBridge : AgentScheduleToolBridge {
    override suspend fun createTask(arguments: Map<String, Any?>): Map<String, Any?> =
        mapOf("success" to false, "error" to "Scheduling is not available through ACP session metadata.")

    override suspend fun listTasks(): List<Map<String, Any?>> = emptyList()

    override suspend fun updateTask(arguments: Map<String, Any?>): Map<String, Any?> =
        mapOf("success" to false, "error" to "Scheduling is not available through ACP session metadata.")

    override suspend fun deleteTask(arguments: Map<String, Any?>): Map<String, Any?> =
        mapOf("success" to false, "error" to "Scheduling is not available through ACP session metadata.")
}

private object NoOpAgentCallback : AgentCallback {
    override suspend fun onThinkingStart() = Unit
    override suspend fun onThinkingUpdate(thinking: String) = Unit
    override suspend fun onToolCallStart(toolName: String, arguments: kotlinx.serialization.json.JsonObject) = Unit
    override suspend fun onToolCallProgress(
        toolName: String,
        progress: String,
        extras: Map<String, Any?>,
    ) = Unit
    override suspend fun onToolCallComplete(
        toolName: String,
        result: cn.com.omnimind.bot.agent.ToolExecutionResult,
    ) = Unit
    override suspend fun onChatMessage(message: String) = Unit
    override suspend fun onClarifyRequired(question: String, missingFields: List<String>?) = Unit
    override suspend fun onComplete(result: AgentResult) = Unit
    override suspend fun onError(error: String) = Unit
    override suspend fun onPermissionRequired(missing: List<String>) = Unit
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
