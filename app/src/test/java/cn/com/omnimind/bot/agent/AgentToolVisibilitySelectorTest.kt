package cn.com.omnimind.bot.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolVisibilitySelectorTest {
    @Test
    fun `keeps every native tool visible before discovery`() {
        val candidates = listOf(
            tool("read"),
            tool("write"),
            tool("edit"),
            tool("bash"),
            tool("glob"),
            tool("grep"),
            tool("webfetch"),
            tool("vlm_task"),
            tool("context_time_now"),
            tool("project_check"),
            tool("project_publish"),
            tool("fitness_record", dynamic = true, owner = "local.project.fitness"),
            tool("nba_live_scores", dynamic = true, owner = "local.project.nba"),
        )

        val selected = AgentToolVisibilitySelector.select(
            userMessage = "帮我总结今天的训练记录",
            candidates = candidates,
        )

        assertEquals(candidates.map { it.name }.toSet(), selected)
    }

    @Test
    fun `exposes dynamic tools in the complete catalog`() {
        val candidates = listOf(tool("read")) +
            (1..80).map { index ->
                tool(
                    name = "dynamic_tool_$index",
                    dynamic = true,
                    owner = "local.project.$index",
                )
            }

        val selected = AgentToolVisibilitySelector.select(
            userMessage = "创建一个项目",
            candidates = candidates,
        )

        assertEquals(candidates.map { it.name }.toSet(), selected)
        assertTrue("dynamic_tool_1" in selected)
    }

    @Test
    fun `native project tools are eagerly exposed`() {
        val requiredTools = listOf(
            "file_write",
            "terminal_execute",
            "project_contract",
            "project_check",
            "project_publish",
        )
        val selected = AgentToolVisibilitySelector.select(
            userMessage = "帮我做一个小游戏 NBA 经理模拟器",
            candidates = requiredTools.map(::tool),
        )

        requiredTools.forEach { toolName -> assertTrue(toolName in selected) }
    }

    @Test
    fun `vibe project skill still parses workspace direct routing`() {
        val routingMode = AgentToolRoutingMode.fromSkillFrontmatter(
            listOf(mapOf("tool-routing" to "workspace-direct")),
        )

        assertTrue(routingMode == AgentToolRoutingMode.WORKSPACE_DIRECT)
    }

    private fun tool(
        name: String,
        dynamic: Boolean = false,
        owner: String? = null,
    ) = AgentToolVisibilitySelector.ToolCandidate(
        name = name,
        displayName = name,
        description = name,
        owner = owner,
        dynamic = dynamic,
    )
}
