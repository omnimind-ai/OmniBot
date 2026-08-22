package cn.com.omnimind.bot.agent

object AgentToolVisibilitySelector {
    /**
     * Keep the complete model catalog in every request for now. Progressive
     * discovery through tools_search made the model spend a turn searching
     * instead of executing an already-installed capability, and it also made
     * nested OmniFlow authoring turns return the search tool by mistake.
     */
    fun select(
        userMessage: String,
        candidates: List<ToolCandidate>,
        routingMode: AgentToolRoutingMode = AgentToolRoutingMode.DEFAULT,
    ): Set<String> = candidates
        .map { it.name }
        .filter(String::isNotBlank)
        .toCollection(linkedSetOf())

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
