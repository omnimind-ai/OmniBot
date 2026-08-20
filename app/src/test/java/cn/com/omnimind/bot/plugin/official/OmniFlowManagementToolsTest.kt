package cn.com.omnimind.bot.plugin.official

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowManagementToolsTest {
    @Test
    fun `plugin exposes the complete Function and RunLog management surface`() {
        val definitions = OmniFlowManagementTools.definitions()

        assertEquals(OmniFlowManagementTools.TOOL_NAMES, definitions.mapTo(linkedSetOf()) { it.name })
        assertEquals(OmniFlowManagementTools.TOOL_NAMES.size, definitions.size)
        definitions.forEach { definition ->
            assertFalse(definition.description.isBlank())
            assertEquals("object", definition.parameters["type"]?.toString()?.trim('"'))
        }
    }

    @Test
    fun `enhancement uses the official save function contract`() {
        val names = OmniFlowManagementTools.definitions().map { it.name }
        assertFalse(names.contains("create_function"))
        assertFalse(names.contains("update_function"))

        val save = OmniFlowManagementTools.definitions()
            .first { it.name == OmniFlowManagementTools.SAVE_FUNCTION }
        val properties = save.parameters["properties"].toString()
        assertTrue(properties.contains("functions"))
        assertTrue(properties.contains("enhance"))
        assertTrue(properties.contains("instruction"))
        assertFalse(properties.contains("agent_visible"))
    }
}
