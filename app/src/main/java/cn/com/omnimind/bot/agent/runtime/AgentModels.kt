@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package cn.com.omnimind.bot.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Agent 相关数据模型
 */

/**
 * Agent 最终响应
 */
@Serializable
data class AgentFinalResponse(
    val content: String = "",
    val finishReason: String? = null,
    val latestPromptTokens: Int? = null,
    val promptTokenThreshold: Int? = null,
    val completionTokens: Int? = null,
    val cachedTokens: Int? = null,
    val cacheCreationTokens: Int? = null,
    val totalTokens: Int? = null
)

/**
 * Agent 执行结果
 */
sealed class AgentResult {
    data class Success(
        val response: AgentFinalResponse,
        val executedTools: List<ToolExecutionResult>,
        val outputKind: String = AgentOutputKind.NONE.value,
        val hasUserVisibleOutput: Boolean = false,
        val latestPromptTokens: Int? = null,
        val promptTokenThreshold: Int? = null,
        val completionTokens: Int? = null,
        val cachedTokens: Int? = null,
        val cacheCreationTokens: Int? = null,
        val totalTokens: Int? = null
    ) : AgentResult()
    
    data class Error(
        val message: String,
        val exception: Exception? = null
    ) : AgentResult()
}

enum class AgentOutputKind(val value: String) {
    CHAT_MESSAGE("chat_message"),
    CLARIFY("clarify"),
    TASK_STARTED("task_started"),
    PERMISSION_REQUIRED("permission_required"),
    TOOL_RESULT("tool_result"),
    NONE("none")
}

/**
 * 工具执行结果
 */
sealed class ToolExecutionResult {
    open val artifacts: List<ArtifactRef> = emptyList()
    open val workspaceId: String? = null
    open val actions: List<ArtifactAction> = emptyList()

    data class ChatMessage(
        val message: String
    ) : ToolExecutionResult()
    
    data class Clarify(
        val question: String,
        val missingFields: List<String>?
    ) : ToolExecutionResult()
    
    data class Error(
        val toolName: String,
        val message: String
    ) : ToolExecutionResult()

    data class PermissionRequired(
        val missing: List<String>
    ) : ToolExecutionResult()

    data class ScheduleResult(
        val toolName: String,
        val summaryText: String,
        val previewJson: String,
        val success: Boolean = true,
        val taskId: String? = null,
        override val artifacts: List<ArtifactRef> = emptyList(),
        override val workspaceId: String? = null,
        override val actions: List<ArtifactAction> = emptyList()
    ) : ToolExecutionResult()

    data class McpResult(
        val toolName: String,
        val serverName: String,
        val summaryText: String,
        val previewJson: String,
        val rawResultJson: String,
        val success: Boolean = true,
        override val artifacts: List<ArtifactRef> = emptyList(),
        override val workspaceId: String? = null,
        override val actions: List<ArtifactAction> = emptyList()
    ) : ToolExecutionResult()

    data class MemoryResult(
        val toolName: String,
        val summaryText: String,
        val previewJson: String,
        val rawResultJson: String,
        val success: Boolean = true,
        override val artifacts: List<ArtifactRef> = emptyList(),
        override val workspaceId: String? = null,
        override val actions: List<ArtifactAction> = emptyList()
    ) : ToolExecutionResult()

    data class TerminalResult(
        val toolName: String,
        val summaryText: String,
        val previewJson: String,
        val rawResultJson: String,
        val success: Boolean = true,
        val timedOut: Boolean = false,
        val terminalOutput: String = "",
        val terminalSessionId: String? = null,
        val terminalStreamState: String = "completed",
        override val artifacts: List<ArtifactRef> = emptyList(),
        override val workspaceId: String? = null,
        override val actions: List<ArtifactAction> = emptyList()
    ) : ToolExecutionResult()

    data class Interrupted(
        val toolName: String,
        val summaryText: String = "工具调用已被用户手动停止",
        val previewJson: String = "{}",
        val rawResultJson: String = "{}",
        val interruptedBy: String = "user",
        val interruptionReason: String = "manual_stop",
        val terminalOutput: String = "",
        val terminalSessionId: String? = null,
        val terminalStreamState: String = "interrupted",
        override val artifacts: List<ArtifactRef> = emptyList(),
        override val workspaceId: String? = null,
        override val actions: List<ArtifactAction> = emptyList()
    ) : ToolExecutionResult()

    data class ContextResult(
        val toolName: String,
        val summaryText: String,
        val previewJson: String,
        val rawResultJson: String,
        val success: Boolean = true,
        val imageDataUrl: String? = null,
        override val artifacts: List<ArtifactRef> = emptyList(),
        override val workspaceId: String? = null,
        override val actions: List<ArtifactAction> = emptyList()
    ) : ToolExecutionResult()
}

/**
 * Agent 状态
 */
enum class AgentStatus {
    IDLE,
    THINKING,
    EXECUTING_TOOL,
    WAITING_INPUT,
    COMPLETED,
    ERROR
}

/**
 * Agent 回调接口
 */
interface AgentCallback {
    /**
     * Agent 开始思考
     */
    suspend fun onThinkingStart()
    
    /**
     * Agent 思考内容更新
     */
    suspend fun onThinkingUpdate(thinking: String)

    /** Provider input snapshot, projected as an official ACP pending tool call; not execution. */
    suspend fun onToolCallInput(toolCall: AssistantToolCall, toolType: String?) = Unit
    
    /**
     * 工具调用开始
     */
    suspend fun onToolCallStart(toolName: String, arguments: JsonObject)

    suspend fun onToolCallStart(
        toolCallId: String,
        toolName: String,
        arguments: JsonObject
    ) {
        onToolCallStart(toolName, arguments)
    }

    /**
     * ACP-aware tool metadata supplied by the runtime tool registry.  The
     * default keeps older callbacks source-compatible while adapters that
     * expose ACP can avoid guessing a tool kind from its display name.
     */
    suspend fun onToolCallStart(
        toolCallId: String,
        toolName: String,
        arguments: JsonObject,
        toolType: String?,
    ) {
        onToolCallStart(toolCallId, toolName, arguments)
    }
    
    /**
     * 工具调用进度更新
     */
    suspend fun onToolCallProgress(
        toolName: String,
        progress: String,
        extras: Map<String, Any?> = emptyMap()
    )

    suspend fun onToolCallProgress(
        toolCallId: String,
        toolName: String,
        progress: String,
        extras: Map<String, Any?> = emptyMap()
    ) {
        onToolCallProgress(toolName, progress, extras)
    }
    
    /**
     * 工具调用完成
     */
    suspend fun onToolCallComplete(toolName: String, result: ToolExecutionResult)

    suspend fun onToolCallComplete(
        toolCallId: String,
        toolName: String,
        result: ToolExecutionResult
    ) {
        onToolCallComplete(toolName, result)
    }

    /** Persist the exact bounded messages that were sent back to the model. */
    suspend fun onToolReplayReady(
        toolCallId: String,
        assistantMessage: cn.com.omnimind.baselib.llm.ChatCompletionMessage,
        toolResultMessage: cn.com.omnimind.baselib.llm.ChatCompletionMessage
    ) = Unit
    
    /**
     * 聊天消息
     */
    suspend fun onChatMessage(message: String)

    /**
     * 聊天消息（支持流式增量）
     */
    suspend fun onChatMessage(message: String, isFinal: Boolean) {
        onChatMessage(message)
    }

    /**
     * 聊天消息（支持流式增量与吞吐统计）
     */
    suspend fun onChatMessage(
        message: String,
        isFinal: Boolean,
        prefillTokensPerSecond: Double?,
        decodeTokensPerSecond: Double?
    ) {
        onChatMessage(message, isFinal)
    }

    /**
     * 主模型一轮调用结束后的 prompt token 统计更新
     */
    suspend fun onPromptTokenUsageChanged(
        latestPromptTokens: Int,
        promptTokenThreshold: Int?
    ) = Unit

    /**
     * 对话上下文压缩状态变化
     */
    suspend fun onContextCompactionStateChanged(
        isCompacting: Boolean,
        latestPromptTokens: Int?,
        promptTokenThreshold: Int?
    ) = Unit
    
    /**
     * 需要用户输入（追问）
     */
    suspend fun onClarifyRequired(question: String, missingFields: List<String>?)
    
    /**
     * Agent 执行完成
     */
    suspend fun onComplete(result: AgentResult)
    
    /**
     * Agent 执行错误
     */
    suspend fun onError(error: String)

    /**
     * Agent 执行错误，retryable=true 表示当前轮次可手动重试。
     */
    suspend fun onError(error: String, retryable: Boolean) {
        onError(error)
    }

    /**
     * 执行任务前缺少权限。
     */
    suspend fun onPermissionRequired(missing: List<String>)
}
