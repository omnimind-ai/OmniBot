package cn.com.omnimind.bot.plugin.official.agentweb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWebPluginTest {
    @Test
    fun `tool catalog exposes open status and stop operations`() {
        val definitions = AgentWebTools.definitions()

        assertEquals(AgentWebTools.names, definitions.mapTo(linkedSetOf()) { it.name })
        assertEquals(6, definitions.size)
        assertTrue(
            definitions
                .first { it.name == AgentWebTools.OPEN_KIMI }
                .description
                .contains("explicitly asks"),
        )
    }

    @Test
    fun `settings actions are declarative and contain no runtime command`() {
        val actions = AgentWebActions.definitions()

        assertEquals(AgentWebActions.ids, actions.mapTo(linkedSetOf()) { it.id })
        assertTrue(actions.all { it.presentation["placement"].toString() == "\"agent_settings\"" })
        assertTrue(
            actions.all {
                it.presentation["placements"].toString()
                    .contains("\"home_drawer_quick_launch\"")
            },
        )
        assertTrue(actions.all { it.presentation["agentId"] != null })
        assertTrue(actions.all { it.presentation["shortLabel"] != null })
        assertTrue(actions.all { it.ownerPluginId == null })
        assertFalse(actions.toString().contains("--no-open"))
        assertFalse(actions.toString().contains("token="))
    }
}
