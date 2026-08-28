package cn.com.omnimind.bot.agent.tool

import android.content.Context
import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.SubagentDispatcher
import cn.com.omnimind.bot.agent.tool.handlers.BrowserToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.ContextToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.FileToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.ImageGenerationToolHandler
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
import kotlinx.coroutines.CoroutineScope

interface AgentCapabilityModule {
    val handlers: List<ToolHandler>
}

class AgentToolHandlerModule(
    override val handlers: List<ToolHandler>,
) : AgentCapabilityModule

class BuiltInAgentCapabilityModule(
    context: Context,
    scope: CoroutineScope,
    scheduleToolBridge: AgentScheduleToolBridge,
    workspaceManager: AgentWorkspaceManager,
    subagentDispatcher: SubagentDispatcher,
    helper: SharedHelper,
    includeVlmTool: Boolean,
) : AgentCapabilityModule {
    private val terminalHandler = TerminalToolHandler(helper, workspaceManager, scope)
    private val privilegedHandler = PrivilegedToolHandler(
        helper,
        workspaceManager,
        terminalHandler,
    )

    override val handlers: List<ToolHandler> = listOfNotNull(
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
        SubagentToolHandler(helper, subagentDispatcher),
    )
}
