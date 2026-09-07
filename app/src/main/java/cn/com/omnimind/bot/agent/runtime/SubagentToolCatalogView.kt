package cn.com.omnimind.bot.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Enforces specialist permissions at the existing pre-execution validation boundary. */
internal class SubagentToolCatalogView(
    private val parent: AgentToolCatalog,
    private val profileId: String,
) : AgentToolCatalog {
    private val readable = mapOf(
        "file_read" to "workspace", "file_list" to "workspace",
        "file_search" to "workspace", "file_stat" to "workspace",
        "memory_search" to "memory", "memory_load" to "memory",
        "skills_list" to "skill", "skills_read" to "skill",
        "context_apps_query" to "builtin", "context_time_now" to "builtin",
        "browser_use" to "browser",
    )
    private val memoryTools = mapOf(
        "memory_write_daily" to "memory", "memory_upsert_longterm" to "memory",
        "memory_rollup_day" to "memory",
    )
    private fun allowed(name: String): Boolean {
        val expectedType = when (profileId) {
            "explorer" -> readable[name]
            "memory-curator" -> (readable.filterValues { it == "workspace" || it == "memory" } + memoryTools)[name]
            else -> null
        } ?: return false
        val descriptor = parent.runtimeDescriptor(name)
        return descriptor.toolType == expectedType && descriptor.serverName == null &&
            parent.toolsForModel.any { it.function.name == name }
    }

    override val toolsForModel: List<ChatCompletionTool>
        get() = parent.toolsForModel.filter { allowed(it.function.name) }

    // Metadata is needed to project rejected calls and pair their error results.
    override fun runtimeDescriptor(toolName: String) = parent.runtimeDescriptor(toolName)

    override fun validateArguments(toolName: String, arguments: JsonObject) {
        require(allowed(toolName)) { "Tool '$toolName' is outside the $profileId role permissions" }
        if (profileId == "explorer" && toolName == "browser_use") {
            val action = arguments["action"]?.jsonPrimitive?.contentOrNull
            require(action in setOf("navigate", "screenshot", "get_text", "get_page_info",
                "find_elements", "get_readable", "get_backbone", "list_tabs", "scroll",
                "go_back", "go_forward", "wait_for_selector")) {
                "Browser action '$action' is outside explorer observation permissions"
            }
        }
        parent.validateArguments(toolName, arguments)
    }

    override fun searchTools(query: String, limit: Int?): List<AgentToolSearchEntry> =
        parent.searchTools(query, null).filter { allowed(it.name) }.let { matches ->
            if (limit == null) matches else matches.take(limit.coerceAtLeast(0))
        }
}
