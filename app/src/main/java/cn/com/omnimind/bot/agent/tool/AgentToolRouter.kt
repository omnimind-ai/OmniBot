package cn.com.omnimind.bot.agent

import android.content.Context
import cn.com.omnimind.bot.agent.tool.AgentCapabilityModule
import cn.com.omnimind.bot.agent.tool.BuiltInAgentCapabilityModule
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.agent.tool.handlers.ToolHandler
import com.rk.terminal.runtime.TerminalDistribution
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

class AgentToolRouter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val scheduleToolBridge: AgentScheduleToolBridge,
    private val workspaceManager: AgentWorkspaceManager,
    private val subagentDispatcher: SubagentDispatcher,
    private val toolCatalog: AgentToolCatalog? = null,
    terminalDistribution: TerminalDistribution.Spec = TerminalDistribution.alpine,
    capabilityModules: List<AgentCapabilityModule> = emptyList(),
    // The handler is a gated entry point: it returns manual-enable guidance
    // while the operation module is disabled and executes only when enabled.
    includeVlmTool: Boolean = true,
) : AgentToolExecutor {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val helper = SharedHelper(context, json, terminalDistribution)

    private val builtInCapabilities = BuiltInAgentCapabilityModule(
        context = context,
        scope = scope,
        scheduleToolBridge = scheduleToolBridge,
        workspaceManager = workspaceManager,
        subagentDispatcher = subagentDispatcher,
        helper = helper,
        includeVlmTool = includeVlmTool,
    )

    private val allHandlers: List<ToolHandler> =
        builtInCapabilities.handlers + capabilityModules.flatMap { it.handlers }
    private val disposed = AtomicBoolean(false)

    private val handlerMap: Map<String, ToolHandler> = buildMap {
        for (handler in allHandlers) {
            for (name in handler.toolNames) {
                require(name !in this) { "Duplicate tool handler: $name" }
                put(name, handler)
            }
        }
    }

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        helper.ensureRunActive()
        val toolName = toolCall.function.name
        if (AgentConversationModePolicy.isChatOnlyMode(env.conversationMode)) {
            return ToolExecutionResult.Error(
                toolName,
                "Tool execution is disabled in chat-only ACP sessions."
            )
        }
        val toolCallback = callback.scopedToToolCall(toolCall.id, toolName)
        val handler = handlerMap[toolName]
        return if (handler != null) {
            handler.execute(toolCall, args, runtimeDescriptor, env, toolCallback, toolHandle)
        } else {
            ToolExecutionResult.Error(
                toolName,
                "Unknown capability: $toolName. All installed capabilities are already available in this turn."
            )
        }
    }

    override suspend fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        for (handler in allHandlers) {
            runCatching { handler.dispose() }
        }
    }
}

private fun AgentCallback.scopedToToolCall(
    toolCallId: String,
    scopeToolName: String,
): AgentCallback = object : AgentCallback by this {
    override suspend fun onToolCallProgress(
        toolName: String,
        progress: String,
        extras: Map<String, Any?>,
    ) {
        this@scopedToToolCall.onToolCallProgress(
            toolCallId,
            scopeToolName.ifBlank { toolName },
            progress,
            extras,
        )
    }
}
