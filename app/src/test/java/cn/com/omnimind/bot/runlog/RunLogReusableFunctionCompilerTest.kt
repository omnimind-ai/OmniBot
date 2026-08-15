package cn.com.omnimind.bot.runlog

import cn.com.omnimind.baselib.runlog.CanonicalRunLogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunLogReusableFunctionCompilerTest {
    @Test
    fun compilesCanonicalDeviceRunLogWithoutLosingReplayActions() {
        val record = CanonicalRunLogRecord(
            runId = "human-test",
            goal = "添加联系人",
            status = "succeeded",
            success = true,
            steps = listOf(
                step(0, "state-before", "click", mapOf("x" to 120.0, "y" to 240.0)),
                step(1, "state-form", "input_text", mapOf("text" to "小万")),
                step(2, "state-keyboard", "press_key", mapOf("key" to "BACK")),
            ),
            diagnostics = mapOf("done_reason" to "finished"),
        )

        val function = RunLogReusableFunctionCompiler.compile(record)
        val steps = function["steps"] as List<*>

        assertEquals("omniflow.function.v2", function["schema_version"])
        assertEquals(
            setOf(
                "schema_version",
                "function_id",
                "name",
                "description",
                "input_schema",
                "bindings",
                "steps",
                "checker_rules",
                "agent_visible",
            ),
            function.keys,
        )
        assertEquals("添加联系人", function["name"])
        assertEquals(3, steps.size)
        assertEquals("state-before", (steps[0] as Map<*, *>)["source_state_id"])
        assertTrue(function["agent_visible"] == true)
    }

    @Test
    fun ignoresFailedActionsAndKeepsStableFunctionIdentity() {
        val record = CanonicalRunLogRecord(
            goal = "测试操作",
            status = "succeeded",
            success = true,
            steps = listOf(
                step(0, "state-1", "click", mapOf("x" to 1, "y" to 2)),
                step(1, "state-2", "click", mapOf("x" to 3, "y" to 4), success = false),
            ),
            diagnostics = mapOf("done_reason" to "finished"),
        )

        val first = RunLogReusableFunctionCompiler.compile(record, agentVisible = false)
        val second = RunLogReusableFunctionCompiler.compile(record, agentVisible = false)

        assertEquals(first["function_id"], second["function_id"])
        assertEquals(1, (first["steps"] as List<*>).size)
        assertFalse(first["agent_visible"] as Boolean)
    }

    private fun step(
        index: Int,
        beforeStateId: String,
        tool: String,
        args: Map<String, Any?>,
        success: Boolean = true,
    ): Map<String, Any?> = mapOf(
        "step_index" to index,
        "before_state_id" to beforeStateId,
        "action" to mapOf("tool" to tool, "args" to args),
        "result" to mapOf("success" to success),
        "after_state_id" to "${beforeStateId}-after",
    )
}
