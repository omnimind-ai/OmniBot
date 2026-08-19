package cn.com.omnimind.bot.agent

object AgentToolVisibilitySelector {
    @Suppress("UNUSED_PARAMETER")
    fun select(
        userMessage: String,
        candidates: List<ToolCandidate>,
        routingMode: AgentToolRoutingMode = AgentToolRoutingMode.DEFAULT,
    ): Set<String> {
        // Tool selection is intentionally delegated to the Agent through the
        // lightweight discovery tool. Client-side keyword guesses are brittle
        // for multilingual prompts and cannot reliably select remote MCP tools.
        // Keep the initial model-visible catalog tiny; the runtime exposes the
        // concrete schemas after tools_search returns.
        // Keep only the protocol-neutral discovery entry plus the common
        // Harness-native workspace tools. OmniBot-specific device/context
        // capabilities, memory, scheduling, plugins, and remote MCP schemas
        // remain discoverable on demand.
        val bootstrapNames = setOf(
            TOOL_SEARCH_NAME,
            "read",
            "write",
            "edit",
            "bash",
            "glob",
            "grep",
            "webfetch",
        )
        return candidates
            .map { it.name }
            .filterTo(linkedSetOf()) { it in bootstrapNames }
    }

    const val TOOL_SEARCH_NAME = "tools_search"

    data class ToolCandidate(
        val name: String,
        val displayName: String,
        val description: String,
        val owner: String? = null,
        val dynamic: Boolean = false,
    )
}

enum class AgentToolRoutingMode {
    DEFAULT,
    WORKSPACE_DIRECT;

    companion object {
        private const val FRONTMATTER_KEY = "tool-routing"
        private const val WORKSPACE_DIRECT_VALUE = "workspace-direct"

        fun fromSkillFrontmatter(
            frontmatter: Iterable<Map<String, String>>,
        ): AgentToolRoutingMode = if (frontmatter.any { values ->
            values[FRONTMATTER_KEY]?.trim()?.equals(
                WORKSPACE_DIRECT_VALUE,
                ignoreCase = true,
            ) == true
        }) {
            WORKSPACE_DIRECT
        } else {
            DEFAULT
        }
    }
}
