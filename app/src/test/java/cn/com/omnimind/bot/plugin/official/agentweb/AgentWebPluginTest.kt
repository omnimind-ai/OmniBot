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
        val openActions = actions.filter {
            it.id in setOf(AgentWebActions.OPEN_KIMI, AgentWebActions.OPEN_OPENCODE)
        }
        val lifecycleActions = actions - openActions.toSet()
        assertEquals(2, openActions.size)
        assertEquals(4, lifecycleActions.size)
        assertTrue(openActions.all { it.presentation["placement"].toString() == "\"agent_settings\"" })
        assertTrue(
            openActions.all {
                it.presentation["placements"].toString()
                    .contains("\"home_drawer_quick_launch\"")
            },
        )
        assertTrue(openActions.all { it.presentation["agentId"] != null })
        assertTrue(openActions.all { it.presentation["shortLabel"] != null })
        // The open actions link their lifecycle siblings so UI surfaces can
        // query status and stop without hardcoding action ids.
        assertTrue(openActions.all { it.presentation["statusAction"] != null })
        assertTrue(openActions.all { it.presentation["stopAction"] != null })
        // Lifecycle actions stay invocable but never render as standalone tiles.
        assertTrue(lifecycleActions.all { it.presentation["placement"] == null })
        assertTrue(lifecycleActions.all { it.presentation["placements"] == null })
        assertTrue(actions.all { it.ownerPluginId == null })
        assertFalse(actions.toString().contains("--no-open"))
        assertFalse(actions.toString().contains("token="))
    }
}
