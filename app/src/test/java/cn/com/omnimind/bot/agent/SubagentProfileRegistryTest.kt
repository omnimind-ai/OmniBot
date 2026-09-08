package cn.com.omnimind.bot.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentProfileRegistryTest {

    @Test
    fun `four built-in profiles registered`() {
        val ids = SubagentProfileRegistry.all().map { it.id }
        assertTrue(ids.containsAll(listOf("general", "explorer", "memory-curator", "planner")))
        assertEquals(4, ids.size)
    }

    @Test
    fun `unknown profileId falls back to general`() {
        assertEquals("general", SubagentProfileRegistry.get(null).id)
        assertEquals("general", SubagentProfileRegistry.get("").id)
        assertEquals("general", SubagentProfileRegistry.get("does-not-exist").id)
    }

    @Test
    fun `profiles provide task guidance without a tool policy`() {
        for (profile in SubagentProfileRegistry.all()) {
            assertTrue(profile.displayName.isNotBlank())
            assertTrue(profile.systemPrompt.isNotBlank())
            assertTrue(!profile.systemPrompt.contains("terminal_execute"))
        }
    }

    @Test
    fun `each profile has distinct system prompt`() {
        val prompts = SubagentProfileRegistry.all().map { it.systemPrompt }
        assertEquals(prompts.size, prompts.toSet().size)
        for ((i, a) in prompts.withIndex()) {
            for (b in prompts.drop(i + 1)) {
                assertNotEquals(a, b)
            }
        }
    }
}
