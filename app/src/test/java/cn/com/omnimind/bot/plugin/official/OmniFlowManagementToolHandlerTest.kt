package cn.com.omnimind.bot.plugin.official

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import cn.com.omnimind.baselib.runlog.CanonicalRunLogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowManagementToolHandlerTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `saving a runlog preserves the official arguments`() {
        val args = json.parseToJsonElement(
            """{"run_id":"gui-123"}""",
        ).jsonObject

        val normalized = normalizeOmniFlowManagementArguments(
            OmniFlowManagementTools.SAVE_FUNCTION,
            args,
        )

        assertEquals("gui-123", normalized["run_id"])
        assertFalse(normalized.containsKey("agent_visible"))
    }

    @Test
    fun `enhancement arguments are passed to the official save pipeline`() {
        val args = json.parseToJsonElement(
            """{"run_id":"gui-123","functions":[{"function_id":"fn-1"}],"enhance":true,"instruction":"稳定化参数"}""",
        ).jsonObject

        val normalized = normalizeOmniFlowManagementArguments(
            OmniFlowManagementTools.SAVE_FUNCTION,
            args,
        )

        assertEquals("gui-123", normalized["run_id"])
        assertEquals(true, normalized["enhance"])
        assertEquals("稳定化参数", normalized["instruction"])
        assertEquals(
            "fn-1",
            ((normalized["functions"] as List<*>).single() as Map<*, *>)["function_id"],
        )
    }

    @Test
    fun `failed runlog cannot be registered as a function`() {
        assertFalse(
            isRegisterableRunLog(
                CanonicalRunLogRecord(
                    runId = "failed",
                    status = "failed",
                    success = false,
                    diagnostics = mapOf("done_reason" to "error"),
                ),
            ),
        )
    }

    @Test
    fun `succeeded runlog can be registered`() {
        assertTrue(
            isRegisterableRunLog(
                CanonicalRunLogRecord(
                    runId = "succeeded",
                    status = "succeeded",
                    success = true,
                    diagnostics = mapOf("done_reason" to "function_completed"),
                ),
            ),
        )
    }

    @Test
    fun `developer override tools are exposed with recovery confirmation`() {
        val definitions = OmniFlowManagementTools.definitions().associateBy { it.name }

        assertTrue(definitions.containsKey(OmniFlowManagementTools.GET_PYTHON_OVERRIDE))
        assertTrue(definitions.containsKey(OmniFlowManagementTools.APPLY_PYTHON_OVERRIDE))
        assertTrue(definitions.containsKey(OmniFlowManagementTools.RELOAD_PYTHON_OVERRIDE))
        val clear = requireNotNull(definitions[OmniFlowManagementTools.CLEAR_PYTHON_OVERRIDE])
        assertEquals(
            "confirm",
            clear.parameters["required"]?.toString()?.trim('[', ']', '"'),
        )
    }
}
