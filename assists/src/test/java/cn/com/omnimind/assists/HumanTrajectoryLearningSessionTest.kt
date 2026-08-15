package cn.com.omnimind.assists

import cn.com.omnimind.assists.task.recording.ManualRecordedAction
import cn.com.omnimind.baselib.runlog.State
import cn.com.omnimind.baselib.runlog.RunLogStepRecord
import cn.com.omnimind.baselib.runlog.RunLogWriter
import cn.com.omnimind.baselib.runlog.actionOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanTrajectoryLearningSessionTest {
    @Test
    fun manualRecordingKeepsEveryCoordinateActionWhenMiddleXmlIsUnavailable() = runBlocking {
        val actions = listOf(
            coordinateAction("打开安装页", 500, 400, state("before-0", "demo.app", "<hierarchy/>")),
            coordinateAction("点击安装", 500, 750, null),
            coordinateAction("点击完成", 500, 900, state("after-2", "installer", "<hierarchy/>")),
        )
        val records = mutableListOf<RunLogStepRecord>()
        val writer = RunLogWriter { records += it }

        actions.forEachIndexed { index, action ->
            writer.write(
                fact = HumanTrajectoryLearningSession.buildRunLogFact("manual-run", index, action),
                states = manualRunLogStates(action),
            )
        }

        assertEquals(3, records.size)
        assertEquals(listOf(0, 1, 2), records.map { it.step["step_index"] })
        assertEquals(
            listOf("打开安装页", "点击安装", "点击完成"),
            records.map { (it.step["metadata"] as Map<*, *>)["summary"] },
        )
        assertTrue(records[1].states.all { state ->
            state["xml"]?.toString()?.contains("capture=\"unavailable\"") == true
        })
    }

    @Test
    fun manualCoordinateActionPersistsWithoutXmlStates() = runBlocking {
        val action = ManualRecordedAction(
            action = actionOf("click", mapOf("x" to 500, "y" to 750)),
            title = "点击安装",
            beforeState = null,
            afterState = null,
            startedAtMs = 100L,
            finishedAtMs = 200L,
            summary = "点击安装",
            displayWidth = 1080,
            displayHeight = 2400,
            evidenceComplete = false,
            evidenceError = "xml_unavailable",
        )

        var record: RunLogStepRecord? = null
        val writer = RunLogWriter { record = it }
        writer.write(
            fact = HumanTrajectoryLearningSession.buildRunLogFact("manual-run", 0, action),
            states = manualRunLogStates(action),
        )

        val saved = requireNotNull(record)
        assertEquals("click", (saved.step["action"] as Map<*, *>)["tool"])
        assertEquals(2, saved.states.size)
        assertEquals(saved.states[0]["state_id"], saved.step["before_state_id"])
        assertEquals(saved.states[1]["state_id"], saved.step["after_state_id"])
        assertEquals(
            "<hierarchy capture=\"unavailable\" stage=\"before\" />",
            saved.states[0]["xml"],
        )
        assertEquals(
            false,
            (saved.step["metadata"] as Map<*, *>)["evidence_complete"],
        )
    }

    @Test
    fun cancelledEdgeBackSwipePersistsWithReplayCoordinates() = runBlocking {
        val action = ManualRecordedAction(
            action = actionOf(
                "swipe",
                linkedMapOf(
                    "target_description" to "屏幕区域",
                    "x1" to 1f,
                    "y1" to 1_200f,
                    "x2" to 320f,
                    "y2" to 1_204f,
                    "duration_ms" to 240L,
                    "direction" to "right",
                ),
            ),
            title = "右滑",
            beforeState = null,
            afterState = null,
            startedAtMs = 100L,
            finishedAtMs = 340L,
            summary = "从 (1, 1200) 滑动到 (320, 1204)",
            displayWidth = 1080,
            displayHeight = 2400,
            evidenceComplete = false,
            evidenceError = "xml_unavailable",
        )

        val fact = HumanTrajectoryLearningSession.buildRunLogFact("manual-run", 0, action)
        val savedAction = fact.getValue("action") as Map<*, *>
        val args = requireNotNull(savedAction["args"]) as Map<*, *>

        assertEquals("swipe", savedAction["tool"])
        assertEquals(1f, args["x1"])
        assertEquals(1_200f, args["y1"])
        assertEquals(320f, args["x2"])
        assertEquals(1_204f, args["y2"])
        assertEquals("right", args["direction"])
    }

    @Test
    fun failedManualInputIsRetainedAsCanonicalFailedStep() = runBlocking {
        val action = ManualRecordedAction(
            action = actionOf("input_text", mapOf("text" to "拿铁咖啡", "x" to 500, "y" to 300)),
            title = "输入文字",
            beforeState = state("input-before", "demo.app", "<hierarchy/>"),
            afterState = state("input-after", "demo.app", "<hierarchy/>"),
            startedAtMs = 100L,
            finishedAtMs = 200L,
            summary = "输入文字：拿铁咖啡",
            operationSuccess = false,
            operationError = "input_target_not_found",
        )

        val fact = HumanTrajectoryLearningSession.buildRunLogFact("manual-run", 0, action)
        val result = fact.getValue("result") as Map<*, *>
        val metadata = fact.getValue("metadata") as Map<*, *>

        assertEquals(false, result["success"])
        assertEquals("input_target_not_found", result["error"])
        assertEquals("failed", metadata["status"])
        assertEquals("拿铁咖啡", (fact.getValue("action") as Map<*, *>).let {
            (it["args"] as Map<*, *>)["text"]
        })
        assertFalse(manualOperationFailuresResolved(listOf(action)))
        assertEquals(
            true,
            manualOperationFailuresResolved(
                listOf(action, action.copy(operationSuccess = true, operationError = null)),
            ),
        )
    }

    @Test
    fun newManualRunLogUsesCanonicalExecutionFacts() = runBlocking {
        val action = ManualRecordedAction(
            action = actionOf(
                "click",
                mapOf(
                    "x" to 500,
                    "y" to 516.098,
                ),
            ),
            title = "点击搜索",
            beforeState = state(
                stateId = "manual-run-human-0-before",
                packageName = "demo.before",
                xml = "<hierarchy><node text=\"before\" bounds=\"[0,0][1440,3168]\" /></hierarchy>",
            ),
            afterState = state(
                stateId = "manual-run-human-0-after",
                packageName = "demo.after",
                xml = "<hierarchy><node text=\"after\" bounds=\"[0,0][1440,3168]\" /></hierarchy>",
            ),
            startedAtMs = 100L,
            finishedAtMs = 200L,
            summary = "点击搜索",
            displayWidth = 1440,
            displayHeight = 3168,
        )

        var record: RunLogStepRecord? = null
        val writer = RunLogWriter { record = it }
        writer.write(
            fact = HumanTrajectoryLearningSession.buildRunLogFact(
                runId = "manual-run",
                index = 0,
                action = action,
            ),
            states = manualRunLogStates(action),
        )
        val saved = requireNotNull(record)

        assertEquals(2, saved.states.size)
        assertEquals(
            setOf(
                "step_index",
                "before_state_id",
                "action",
                "result",
                "after_state_id",
                "metadata",
            ),
            saved.step.keys,
        )
        assertEquals(0, saved.step["step_index"])
        assertEquals("manual-run-human-0-before", saved.step["before_state_id"])
        assertEquals("manual-run-human-0-after", saved.step["after_state_id"])
        assertFalse(saved.step.containsKey("coordinate_space"))
        assertStateInput(
            state = saved.states[0],
            expectedXml = action.beforeXml,
            expectedPackageName = "demo.before",
        )
        assertStateInput(
            state = saved.states[1],
            expectedXml = action.afterXml,
            expectedPackageName = "demo.after",
        )
        val recordedAction = saved.step.getValue("action") as Map<*, *>
        assertEquals("click", recordedAction["tool"])
        assertEquals(mapOf("x" to 500, "y" to 516.098), recordedAction["args"])
        assertFalse(saved.step.containsKey("before_state"))
        assertFalse(saved.step.containsKey("after_state"))
        assertFalse(saved.step.containsKey("state"))
        assertFalse(saved.step.containsKey("tool_call"))
        assertFalse(saved.step.containsKey("params"))
        assertFalse(saved.step.containsKey("source_context"))
    }

    private fun state(stateId: String, packageName: String, xml: String): State = State(
        stateId = stateId,
        packageName = packageName,
        activityName = "",
        displayWidth = 1440,
        displayHeight = 3168,
        xml = xml,
    )

    private fun coordinateAction(
        title: String,
        x: Int,
        y: Int,
        observedState: State?,
    ): ManualRecordedAction = ManualRecordedAction(
        action = actionOf("click", mapOf("x" to x, "y" to y)),
        title = title,
        beforeState = observedState,
        afterState = observedState,
        startedAtMs = 100L,
        finishedAtMs = 200L,
        summary = title,
        displayWidth = 1080,
        displayHeight = 2400,
        evidenceComplete = observedState != null,
        evidenceError = if (observedState == null) "xml_unavailable" else null,
    )

    private fun assertStateInput(
        state: Map<*, *>,
        expectedXml: String?,
        expectedPackageName: String,
    ) {
        assertEquals(expectedXml, state["xml"])
        assertEquals(expectedPackageName, state["package_name"])
        assertEquals(mapOf("width" to 1440, "height" to 3168), state["display"])
        assertFalse(state.containsKey("xml_path"))
        assertFalse(state.containsKey("xml_sha256"))
        assertFalse(state.containsKey("xml_chars"))
        assertFalse(state.containsKey("xml_bytes"))
        assertFalse(state.containsKey("display_width"))
        assertFalse(state.containsKey("display_height"))
    }
}
