package cn.com.omnimind.assists.task.recording

import cn.com.omnimind.androidgui.AndroidGuiActionResult
import cn.com.omnimind.assists.ManualInputTarget
import cn.com.omnimind.baselib.runlog.State
import cn.com.omnimind.baselib.runlog.actionOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualRecordingEngineTest {
    @Test
    fun `manual back is a target independent canonical key`() {
        assertEquals(
            mapOf("key" to "back"),
            manualPressKeyActionArgs("back", null),
        )
    }

    @Test
    fun `manual enter keeps the selected input target`() {
        val target = ManualInputTarget(
            description = "搜索框",
            x = 408f,
            y = 112f,
            nodeResourceId = "com.example:id/search",
        )

        assertEquals(
            mapOf(
                "key" to "enter",
                "target_description" to "搜索框",
                "x" to 408f,
                "y" to 112f,
                "node_resource_id" to "com.example:id/search",
            ),
            manualPressKeyActionArgs("enter", target),
        )
    }

    @Test
    fun serializesActionsAndCommitsInReceiveOrder() = runBlocking {
        val events = mutableListOf<String>()
        val journal = ManualRecordingJournal()
        val engine = ManualRecordingEngine(
            journal = journal,
            observe = { stage, command ->
                events += "$stage:${command.action.tool}"
                observation("<$stage/>")
            },
            execute = { command ->
                events += "execute:${command.action.tool}"
                if (command.action.tool == "click") delay(20)
                AndroidGuiActionResult(true, "ok")
            },
            nowMs = { 200L },
        )

        val first = async { engine.perform(action("click", 100L)) }
        delay(5)
        val second = async { engine.perform(action("swipe", 110L)) }

        assertTrue(first.await().recorded)
        assertTrue(second.await().recorded)
        assertEquals(listOf("click", "swipe"), journal.snapshot().map { it.action.tool })
        assertEquals(
            listOf(
                "1_before:click", "execute:click", "1_after:click",
                "2_before:swipe", "execute:swipe", "2_after:swipe",
            ),
            events,
        )
        assertEquals(ManualRecordingEngineStats(2, 2, 0, 0, null), engine.stats())
    }

    @Test
    fun observationFailureDoesNotDropExecutedAction() = runBlocking {
        val journal = ManualRecordingJournal()
        val engine = ManualRecordingEngine(
            journal = journal,
            observe = { _, _ -> error("xml unavailable") },
            execute = { AndroidGuiActionResult(true, "ok") },
            nowMs = { 200L },
        )

        val outcome = engine.perform(action("click", 100L))

        assertTrue(outcome.executed)
        assertTrue(outcome.recorded)
        assertEquals(1, journal.size())
        assertEquals(null, journal.lastOrNull()?.beforeXml)
    }

    @Test
    fun failedDispatchIsCountedButNotPersistedAsReplayStep() = runBlocking {
        val journal = ManualRecordingJournal()
        val engine = ManualRecordingEngine(
            journal = journal,
            observe = { _, _ -> ManualRecordingObservation() },
            execute = { AndroidGuiActionResult(false, "dispatch failed") },
        )

        val outcome = engine.perform(action("click", 100L))

        assertFalse(outcome.executed)
        assertFalse(outcome.recorded)
        assertEquals(0, journal.size())
        assertEquals(ManualRecordingEngineStats(1, 0, 1, 0, null), engine.stats())
    }

    @Test
    fun failedManualTextDispatchIsPersistedWithFailureEvidence() = runBlocking {
        val journal = ManualRecordingJournal()
        val engine = ManualRecordingEngine(
            journal = journal,
            observe = { _, _ -> observation("<page/>") },
            execute = { AndroidGuiActionResult(false, "input_target_not_found") },
        )

        val outcome = engine.perform(
            action("input_text", 100L).copy(persistOnFailure = true),
        )

        assertFalse(outcome.executed)
        assertTrue(outcome.recorded)
        assertEquals(false, journal.lastOrNull()?.operationSuccess)
        assertEquals("input_target_not_found", journal.lastOrNull()?.operationError)
        assertEquals(ManualRecordingEngineStats(1, 0, 1, 0, null), engine.stats())
    }

    @Test
    fun capturesAfterStateOnceWithoutBlockingForPageChanges() = runBlocking {
        val journal = ManualRecordingJournal()
        var afterAttempts = 0
        val engine = ManualRecordingEngine(
            journal = journal,
            observe = { stage, _ ->
                if (stage.endsWith("_before")) {
                    observation("<page name=\"before\"/>")
                } else {
                    afterAttempts += 1
                    observation("<page name=\"before\"/>")
                }
            },
            execute = { AndroidGuiActionResult(true, "ok") },
        )

        val outcome = engine.perform(action("click", 100L))

        assertTrue(outcome.recorded)
        assertEquals(1, afterAttempts)
        assertEquals("<page name=\"before\"/>", journal.lastOrNull()?.afterXml)
    }

    @Test
    fun waitDoesNotRequireAfterStateToChange() = runBlocking {
        val journal = ManualRecordingJournal()
        var observationCount = 0
        val engine = ManualRecordingEngine(
            journal = journal,
            observe = { _, _ ->
                observationCount += 1
                observation("<page/>")
            },
            execute = { AndroidGuiActionResult(true, "ok") },
        )

        val outcome = engine.perform(action("wait", 100L))

        assertTrue(outcome.recorded)
        assertEquals(2, observationCount)
        assertEquals("<page/>", journal.lastOrNull()?.afterXml)
    }

    @Test
    fun unchangedClickStateIsRecordedWithoutRetries() = runBlocking {
        val journal = ManualRecordingJournal()
        var observationCount = 0
        val engine = ManualRecordingEngine(
            journal = journal,
            observe = { _, _ ->
                observationCount += 1
                observation("<page/>")
            },
            execute = { AndroidGuiActionResult(true, "ok") },
        )

        val outcome = engine.perform(action("click", 100L))

        assertTrue(outcome.recorded)
        assertEquals(2, observationCount)
        assertEquals("<page/>", journal.lastOrNull()?.afterXml)
    }

    @Test
    fun cancellationClosesPendingActionWithoutSwallowingIt() = runBlocking {
        val engine = ManualRecordingEngine(
            journal = ManualRecordingJournal(),
            observe = { _, _ -> ManualRecordingObservation() },
            execute = { throw CancellationException("cancelled") },
        )

        var cancelled = false
        try {
            engine.perform(action("click", 100L))
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(ManualRecordingEngineStats(1, 0, 1, 0, null), engine.stats())
    }

    private fun action(tool: String, startedAtMs: Long) = ManualRecordingCommand(
        action = actionOf(tool),
        title = tool,
        summary = tool,
        source = "overlay_touch",
        startedAtMs = startedAtMs,
    )

    private fun observation(xml: String): ManualRecordingObservation = ManualRecordingObservation(
        state = State.create(
            packageName = "test.package",
            activityName = "TestActivity",
            displayWidth = 1080,
            displayHeight = 1920,
            xml = xml,
        ),
    )
}
