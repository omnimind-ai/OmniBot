package cn.com.omnimind.bot.plugin.official

import org.junit.Assert.*
import org.junit.Test

class OmniFlowManagementResultTest {
    @Test fun `compiler rejection retains structured result and failure status`() {
        val encoded = """{"success":false,"diagnostic_path":"/workspace/report.json"}"""
        val result = managementToolResult("save_function", mapOf(
            "success" to false,
            "error" to mapOf("code" to "FUNCTION_AUTHORING_REJECTED", "message" to "missing binding"),
        ), encoded)
        assertFalse(result.success)
        assertEquals("missing binding", result.summaryText)
        assertEquals(encoded, result.rawResultJson)
        assertEquals(encoded, result.previewJson)
    }

    @Test fun `success keeps original result`() {
        val result = managementToolResult("save_function", mapOf("success" to true, "summary" to "saved"), "{}")
        assertTrue(result.success)
        assertEquals("saved", result.summaryText)
    }
}
