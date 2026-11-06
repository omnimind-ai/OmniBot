package cn.com.omnimind.bot.terminal

import android.content.Context
import com.rk.terminal.runtime.TerminalDistribution
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import cn.com.omnimind.bot.termux.TermuxLiveEnvironmentResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class EmbeddedTerminalInitCoordinatorTest {
    private val coordinator = EmbeddedTerminalInitCoordinator
    private val stateField = coordinator.javaClass.getDeclaredField("embeddedTerminalInitState")
        .apply { isAccessible = true }
    private var previousState: Any? = null
    private val runField = coordinator.javaClass.getDeclaredField("activeRun").apply { isAccessible = true }
    private val jobField = coordinator.javaClass.getDeclaredField("activeJob").apply { isAccessible = true }
    private var previousRun: Any? = null
    private var previousJob: Any? = null

    @Before
    fun reserveStartupPreparation() {
        previousState = stateField.get(coordinator)
        previousRun = runField.get(coordinator)
        previousJob = jobField.get(coordinator)
        coordinator.javaClass.getDeclaredMethod(
            "resetEmbeddedTerminalInitStateLocked",
            EmbeddedTerminalInitCoordinator.PreparationRequest::class.java
        )
            .apply { isAccessible = true }
            .invoke(coordinator, EmbeddedTerminalInitCoordinator.PreparationRequest.Switch(TerminalDistribution.ubuntu))
    }

    @After
    fun restoreState() {
        stateField.set(coordinator, previousState)
        runField.set(coordinator, previousRun)
        jobField.set(coordinator, previousJob)
    }

    @Test
    fun repeatedDisplayCallbacksDoNotRestartRunningOrFailedPreparation() {
        val context = mock(Context::class.java)

        assertFalse(coordinator.startInBackground(context))
        val startedAt = coordinator.buildSnapshot()["startedAt"]
        complete(success = false, message = "Network unavailable")

        assertFalse(coordinator.startInBackground(context))
        val snapshot = coordinator.buildSnapshot()
        assertEquals(startedAt, snapshot["startedAt"])
        assertEquals(false, snapshot["running"])
        assertEquals(true, snapshot["completed"])
        assertEquals(false, snapshot["success"])
        assertEquals("error", snapshot["phase"])
        assertEquals("Network unavailable", snapshot["error"])
    }

    @Test
    fun successfulRetryClearsThePreviousFailureAndPreventsAutomaticReinstall() {
        complete(success = false, message = "Download failed")
        complete(success = true, message = "Ready")

        val snapshot = coordinator.buildSnapshot()
        assertEquals(true, snapshot["success"])
        assertEquals("ready", snapshot["phase"])
        assertEquals(null, snapshot["error"])
        assertEquals(1.0, snapshot["progress"])
        assertFalse(coordinator.startInBackground(mock(Context::class.java)))
    }

    @Test
    fun cancellingBeforeWorkerStartCompletesTheRequestAndKeepsItsTarget() {
        val request = EmbeddedTerminalInitCoordinator.PreparationRequest.Switch(TerminalDistribution.ubuntu)
        @Suppress("UNCHECKED_CAST")
        val run = coordinator.javaClass.getDeclaredMethod(
            "reservePreparationLocked", Context::class.java,
            EmbeddedTerminalInitCoordinator.PreparationRequest::class.java
        ).apply { isAccessible = true }.invoke(coordinator, mock(Context::class.java), request)
            as Pair<CompletableDeferred<TermuxLiveEnvironmentResult>, Job>

        assertEquals("ubuntu", coordinator.buildSnapshot()["distribution"])
        assertTrue(coordinator.cancelCurrent())
        assertTrue(run.first.isCompleted)
        assertFalse(run.first.isCancelled)
        assertFalse(run.second.start())
        assertEquals(false, coordinator.buildSnapshot()["running"])
        assertEquals(true, coordinator.buildSnapshot()["completed"])
        assertEquals(false, coordinator.buildSnapshot()["success"])
        assertEquals("cancelled", coordinator.buildSnapshot()["phase"])
        assertEquals("switch", coordinator.buildSnapshot()["requestKind"])
        assertEquals("ubuntu", coordinator.buildSnapshot()["distribution"])
        assertFalse(coordinator.cancelCurrent())
    }

    @Test
    fun retryDoesNotJoinAnActiveRequestForAnotherTargetOrOperation() = runBlocking {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val active = CompletableDeferred<TermuxLiveEnvironmentResult>()
        runField.set(coordinator, active)
        val snapshot = coordinator.buildSnapshot()

        val differentRequests = listOf(
            EmbeddedTerminalInitCoordinator.PreparationRequest.Switch(TerminalDistribution.alpine),
            EmbeddedTerminalInitCoordinator.PreparationRequest.Prepare(listOf("nodejs"))
        )
        for (request in differentRequests) {
            val result = withTimeout(1_000L) { coordinator.retry(context, request) }
            assertFalse(result.success)
            assertEquals("另一个终端环境准备任务正在运行，请稍后再试。", result.message)
            assertEquals(snapshot, coordinator.buildSnapshot())
            assertFalse(active.isCompleted)
        }
    }

    private fun complete(success: Boolean, message: String) {
        coordinator.javaClass.getDeclaredMethod(
            "markEmbeddedTerminalInitCompleted",
            Boolean::class.javaPrimitiveType,
            String::class.java
        ).apply { isAccessible = true }.invoke(coordinator, success, message)
    }
}
