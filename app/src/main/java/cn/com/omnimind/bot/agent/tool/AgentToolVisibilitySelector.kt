package cn.com.omnimind.bot.agent

object AgentToolVisibilitySelector {
    @Suppress("UNUSED_PARAMETER")
    fun select(
        userMessage: String,
        candidates: List<ToolCandidate>,
        routingMode: AgentToolRoutingMode = AgentToolRoutingMode.DEFAULT,
    ): Set<String> {
        // Native schemas are stable, local, and small enough to expose in the
        // first request. Hiding them behind tools_search caused a common
        // failure loop: a phone task could not see vlm_task, searched for it,
        // then searched again when the provider did not retain the injected
        // schema on the following round. Dynamic plugin/MCP schemas remain
        // progressive because their catalog can be large or change at runtime.
        val nativeNames = candidates
            .asSequence()
            .filter { !it.dynamic }
            .map { it.name }
            .toCollection(linkedSetOf())
        return candidates
            .map { it.name }
            .filterTo(linkedSetOf()) { it in nativeNames }
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
