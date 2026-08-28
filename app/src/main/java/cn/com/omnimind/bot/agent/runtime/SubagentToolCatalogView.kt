package cn.com.omnimind.bot.agent

import kotlinx.serialization.json.JsonObject

/**
 * A filtered view over an existing [AgentToolCatalog] that only exposes
 * tools allowed by the active [SubagentProfile]. Any attempt to access a
 * tool outside the whitelist throws [IllegalStateException], preventing
 * a subagent from escalating beyond its declared scope.
 */
class SubagentToolCatalogView(
    private val parent: AgentToolCatalog,
    private val allowed: Set<String>
) : AgentToolCatalog {
    override val usesProgressiveDiscovery: Boolean = parent.usesProgressiveDiscovery

    override val toolsForModel: List<ChatCompletionTool> by lazy {
        parent.toolsForModel.filter { tool ->
            isAllowed(tool.function.name)
        }
    }

    override fun runtimeDescriptor(toolName: String): AgentToolRegistry.RuntimeToolDescriptor {
        ensureAllowed(toolName)
        return parent.runtimeDescriptor(toolName)
    }

    override fun validateArguments(toolName: String, arguments: JsonObject) {
        ensureAllowed(toolName)
        parent.validateArguments(toolName, arguments)
    }

    override fun searchTools(query: String, limit: Int): List<AgentToolSearchEntry> {
        return parent.searchTools(query, limit).filter { isAllowed(it.name) }
    }

    override fun exposeToolNames(names: Set<String>) {
        parent.exposeToolNames(names.filterTo(linkedSetOf(), ::isAllowed))
    }

    /**
     * Profiles use the stable internal names, while direct Agent catalogs may
     * expose common Harness names such as `read` or `bash`. Resolve both forms
     * and reject an alias occupied by a plugin/MCP tool.
     */
    private fun isAllowed(toolName: String): Boolean {
        if (toolName in allowed) return true
        return allowed.any { sourceName ->
            AgentToolDefinitions.modelFacingNameFor(sourceName) == toolName &&
                parent.runtimeDescriptor(toolName).toolType ==
                    parent.runtimeDescriptor(sourceName).toolType
        }
    }

    private fun ensureAllowed(toolName: String) {
        if (!isAllowed(toolName)) {
            throw IllegalStateException(
                "tool '$toolName' is not allowed for this subagent (whitelist=${allowed.size})"
            )
        }
    }
}
