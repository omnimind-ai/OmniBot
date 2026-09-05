package cn.com.omnimind.bot.agent

import cn.com.omnimind.bot.agent.workspace.memory.LongTermMemoryIndex
import kotlinx.serialization.json.JsonObject

/**
 * Separate switches for the temporary clean Agent baseline.
 *
 * Keep local plugins available for first-party Function management while the
 * optional inbound MCP listener remains isolated. The in-app Agent never
 * routes through MCP; it uses the native capability catalog directly.
 */
object AgentRuntimeFeatureFlags {
    const val ENABLE_PLUGIN_RUNTIME: Boolean = true
    const val ENABLE_LOCAL_MCP_SERVER: Boolean = false
}

interface AgentExecutionEnvironment {
    val agentRunId: String
    val userMessage: String
    val runtimeContextRepository: AgentRuntimeContextRepository
    val workspaceDescriptor: AgentWorkspaceDescriptor
    val resolvedSkills: List<ResolvedSkillContext>
    val workspaceManager: AgentWorkspaceManager
    val workspaceMemoryService: WorkspaceMemoryService
    val conversationMode: String
    val reasoningEffort: String?
    val modelProviderProfileId: String? get() = null
    val terminalEnvironment: Map<String, String>
    val runControl: AgentRunControl

    /**
     * The ACP client-side permission boundary for tools that need approval.
     * Null is retained for non-ACP callers; those callers must not invent a
     * second wire protocol and should apply their own host policy.
     */
    val permissionRequester: AgentPermissionRequester? get() = null

    /** Long-term memory slug index. Null when unavailable; tools handle gracefully. */
    val longTermMemoryIndex: LongTermMemoryIndex? get() = null

}

data class DefaultAgentExecutionEnvironment(
    override val agentRunId: String,
    override val userMessage: String,
    override val runtimeContextRepository: AgentRuntimeContextRepository,
    override val workspaceDescriptor: AgentWorkspaceDescriptor,
    override val resolvedSkills: List<ResolvedSkillContext>,
    override val workspaceManager: AgentWorkspaceManager,
    override val workspaceMemoryService: WorkspaceMemoryService,
    override val conversationMode: String,
    override val reasoningEffort: String? = null,
    override val modelProviderProfileId: String? = null,
    override val terminalEnvironment: Map<String, String> = emptyMap(),
    override val runControl: AgentRunControl = NoOpAgentRunControl,
    override val permissionRequester: AgentPermissionRequester? = null,
    override val longTermMemoryIndex: LongTermMemoryIndex? = null,
) : AgentExecutionEnvironment

fun interface AgentPermissionRequester {
    /** Returns true only when the user selected an allow option. */
    suspend fun requestPermission(
        toolCallId: String,
        title: String,
        detail: String,
    ): Boolean
}

data class AgentToolSearchEntry(
    val name: String,
    val displayName: String,
    val description: String,
    val toolType: String,
    val serverName: String? = null,
)

interface AgentToolCatalog {
    val toolsForModel: List<ChatCompletionTool>

    fun runtimeDescriptor(toolName: String): AgentToolRegistry.RuntimeToolDescriptor

    fun validateArguments(toolName: String, arguments: JsonObject)

    /** Search the current catalog. */
    fun searchTools(query: String, limit: Int? = null): List<AgentToolSearchEntry> = emptyList()
}

interface AgentToolExecutor {
    suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult

    suspend fun dispose() = Unit
}
