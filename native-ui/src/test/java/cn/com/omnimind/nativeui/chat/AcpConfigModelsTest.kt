package cn.com.omnimind.nativeui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ACP config panel's parsing and labels (5e-3), against acp_config_button.dart. */
class AcpConfigModelsTest {
    private val response = listOf(
        mapOf(
            "id" to "reasoning_effort", "name" to "Reasoning", "category" to "thought_level", "type" to "select",
            "currentValue" to "high",
            "options" to listOf(mapOf("value" to "low", "name" to "Low"), mapOf("value" to "high", "name" to "High")),
        ),
        mapOf(
            "id" to "model", "category" to "model", "type" to "select", "currentValue" to "b",
            // Grouped choices are flattened.
            "options" to listOf(mapOf("name" to "Group", "options" to listOf(mapOf("value" to "a"), mapOf("value" to "b", "name" to "Model B")))),
        ),
        mapOf("id" to "enable_thinking", "type" to "boolean", "currentValue" to true),
        mapOf("id" to "vendor_knob", "name" to "Vendor knob", "type" to "number", "currentValue" to 3),
        mapOf("name" to "no id"),
    )

    @Test
    fun `every declared option is kept, grouped choices flattened, id-less ones dropped`() {
        val options = parseAcpConfigOptions(response, english = false)
        assertEquals(listOf("reasoning_effort", "model", "enable_thinking", "vendor_knob"), options.map { it.id })
        assertEquals(listOf("a", "b"), options[1].choices.map { it.value })
        assertEquals("Model B", options[1].currentLabel)
        assertTrue(options[2].isBoolean)
        // An unknown type is shown with its raw value, never edited.
        assertEquals("3", options[3].currentLabel)
        assertTrue(!options[3].isSelect && !options[3].isBoolean)
    }

    @Test
    fun `labels are presentation only, localized like the Dart panel`() {
        val zh = parseAcpConfigOptions(response, english = false)
        assertEquals(listOf("思考强度", "模型", "启用思考", "Vendor knob"), zh.map { it.label })
        assertEquals("高", zh[0].currentLabel)
        val en = parseAcpConfigOptions(response, english = true)
        assertEquals(listOf("Reasoning", "model", "enable_thinking", "Vendor knob"), en.map { it.label })
        assertEquals("High", en[0].currentLabel)
    }
}
