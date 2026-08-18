package cn.com.omnimind.bot.agent

import android.content.Context
import cn.com.omnimind.bot.agent.tool.handlers.BrowserToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.ContextToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.FileToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.ImageGenerationToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.McpToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.MemoryLoadToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.MemoryToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.PrivilegedToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.agent.tool.handlers.SkillsToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.SubagentToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.SystemToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.TerminalToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.ToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.VlmToolHandler
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
    terminalDistribution: TerminalDistribution.Spec = TerminalDistribution.alpine,
    pluginHandlers: List<ToolHandler> = emptyList(),
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

    private val terminalHandler = TerminalToolHandler(helper, workspaceManager, scope)
    private val privilegedHandler = PrivilegedToolHandler(helper, workspaceManager, terminalHandler)

    private val orderedHandlers: List<ToolHandler> = listOfNotNull(
        ContextToolHandler(helper),
        VlmToolHandler(context).takeIf { includeVlmTool },
        privilegedHandler,
        terminalHandler,
        BrowserToolHandler(helper, workspaceManager),
        ImageGenerationToolHandler(helper, workspaceManager),
        FileToolHandler(helper, workspaceManager),
        SkillsToolHandler(helper, workspaceManager),
        SystemToolHandler(helper, scheduleToolBridge, workspaceManager),
        MemoryToolHandler(helper),
        MemoryLoadToolHandler(helper),
        SubagentToolHandler(helper, subagentDispatcher)
    )

    private val mcpFallback = McpToolHandler(helper)
    private val allHandlers = orderedHandlers + pluginHandlers
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
        val handler = handlerMap[toolName]
        return if (handler != null) {
            handler.execute(toolCall, args, runtimeDescriptor, env, callback, toolHandle)
        } else {
            mcpFallback.execute(toolCall, args, runtimeDescriptor, env, callback, toolHandle)
        }
    }

    override suspend fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        for (handler in allHandlers) {
            runCatching { handler.dispose() }
        }
    }
}
