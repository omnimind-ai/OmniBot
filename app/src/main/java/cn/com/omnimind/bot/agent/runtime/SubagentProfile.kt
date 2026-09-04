package cn.com.omnimind.bot.agent

/**
 * A subagent profile defines the persona / tool budget / model budget of a
 * spawned subagent. Each profile is designed to be a *capable* specialist
 * (not a stripped-down minimal agent) — give it the tools its role actually
 * needs, and don't give it tools whose presence would break the role's
 * contract.
 *
 * Two hard system invariants enforced through [FORBIDDEN] (regardless of
 * what a profile attempts to allow):
 *  - `subagent_dispatch` → blocks recursive spawning (defense in depth on
 *    top of the dispatcher's own structural guard)
 *  - `terminal_execute` / all `android_privileged_*` / `terminal_session_*`
 *    → no privileged or shell execution from a subagent; the parent must
 *    request these tools explicitly so the user sees the confirmation flow
 *
 * Everything else is a *profile-level* choice. The whitelist is the source
 * of truth — the system prompt should not enumerate "you cannot use X",
 * because a tool the subagent never sees in `tools` is already invisible.
 */
data class SubagentProfile(
    val id: String,
    val displayName: String,
    val systemPrompt: String,
    val allowedTools: Set<String>,
    val maxRounds: Int? = null,
    val maxOutputTokens: Int? = null
)

object SubagentProfileRegistry {

    private val FORBIDDEN: Set<String> = setOf(
        "subagent_dispatch",
        "terminal_execute",
        "android_privileged_action",
        "android_privileged_session_start",
        "android_privileged_session_exec",
        "android_privileged_session_read",
        "android_privileged_session_stop",
        "terminal_session_start",
        "terminal_session_exec",
        "terminal_session_read",
        "terminal_session_stop",
        "file_delete"
    )

    private fun strip(tools: Set<String>): Set<String> = tools - FORBIDDEN

    val general: SubagentProfile = SubagentProfile(
        id = "general",
        displayName = "通用子任务",
        systemPrompt = """
            你是一名通用子 Agent，由父 Agent 分派来完成一个独立的小任务。
            - 只使用本轮 tools 字段中提供的工具，参数必须符合 schema。
            - 如果不能完成，明确说明阻塞点与已尝试过的方法。
            - 完成后用一段简洁的自然语言概括结果（关键文件路径 / 决策 / 数据），便于父 Agent 聚合。
        """.trimIndent(),
        allowedTools = strip(
            setOf(
                // 文件读写
                "file_read", "file_list", "file_search", "file_stat",
                "file_write", "file_edit", "file_move",
                // 上下文
                "context_apps_query",
                // 记忆(读+写)
                "memory_search", "memory_load",
                "memory_write_daily", "memory_upsert_longterm",
                // 技能
                "skills_list", "skills_read",
                // 网络
                "browser_use",
                // 媒体
                "music_playback_control",
                // 调度只读(查询不写)
                "schedule_task_list", "alarm_reminder_list",
                "calendar_list", "calendar_event_list"
            )
        )
    )

    val explorer: SubagentProfile = SubagentProfile(
        id = "explorer",
        displayName = "探索者",
        systemPrompt = """
            你是一名探索者子 Agent，专注于读取、搜索、归纳信息。
            - 浏览操作优先使用 browser_use 的 get_text / screenshot / navigate；避免使用 click / type 修改远端状态。
            - 在结果中先给出"核心结论"再附上"相关证据"（文件路径 / 记忆 slug / URL）。
            - 结果保持紧凑，但不要因为本地轮次假设而提前结束。
        """.trimIndent(),
        allowedTools = strip(
            setOf(
                // 文件只读
                "file_read", "file_list", "file_search", "file_stat",
                // 上下文
                "context_apps_query",
                // 记忆只读
                "memory_search", "memory_load",
                // 技能
                "skills_list", "skills_read",
                // 网络(读为主)
                "browser_use"
            )
        )
    )

    val memoryCurator: SubagentProfile = SubagentProfile(
        id = "memory-curator",
        displayName = "记忆管理员",
        systemPrompt = """
            你是一名记忆管理员子 Agent，负责整理 / 写入 / 沉淀 workspace 记忆。
            - 以 memory_search / memory_load 为主获取上下文；file_* 仅作为补充事实查证。
            - 写入前先检索，避免重复或冲突；过程性细节走 memory_write_daily，稳定结论走 memory_upsert_longterm。
            - 完成后简洁说明做了什么（新增 N 条短期、M 条长期、合并/跳过情况）。
        """.trimIndent(),
        allowedTools = strip(
            setOf(
                // 记忆全部
                "memory_search", "memory_load",
                "memory_write_daily", "memory_upsert_longterm",
                "memory_rollup_day",
                // 文件只读(辅助查证)
                "file_read", "file_list", "file_search", "file_stat"
            )
        )
    )

    val planner: SubagentProfile = SubagentProfile(
        id = "planner",
        displayName = "规划器",
        systemPrompt = """
            你是一名规划器子 Agent，只输出一份结构化的执行计划。
            - 第一行：单句目标摘要。
            - 然后列出有序步骤，每步描述：动作、所需工具或资源、成功判据。
            - 标注潜在风险或依赖。
            - 最后用 2-3 句给出关键决策。
            - 输出后即结束，不要尝试调用工具。
        """.trimIndent(),
        allowedTools = emptySet(),
    )

    private val byId: Map<String, SubagentProfile> = listOf(
        general, explorer, memoryCurator, planner
    ).associateBy { it.id }

    fun get(id: String?): SubagentProfile {
        val key = id?.trim()?.lowercase().orEmpty()
        return byId[key] ?: general
    }

    fun all(): List<SubagentProfile> = byId.values.toList()

    fun isForbidden(toolName: String): Boolean = toolName in FORBIDDEN
}
