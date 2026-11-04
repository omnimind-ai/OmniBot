package cn.com.omnimind.bot.terminal

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class EmbeddedTerminalInitCoordinatorTest {
    private val coordinator = EmbeddedTerminalInitCoordinator
    private val stateField = coordinator.javaClass.getDeclaredField("embeddedTerminalInitState")
        .apply { isAccessible = true }
    private var previousState: Any? = null

    @Before
    fun reserveStartupPreparation() {
        previousState = stateField.get(coordinator)
        coordinator.javaClass.getDeclaredMethod("resetEmbeddedTerminalInitStateLocked")
            .apply { isAccessible = true }
            .invoke(coordinator)
    }

    @After
    fun restoreState() {
        stateField.set(coordinator, previousState)
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

    private fun complete(success: Boolean, message: String) {
        coordinator.javaClass.getDeclaredMethod(
            "markEmbeddedTerminalInitCompleted",
            Boolean::class.javaPrimitiveType,
            String::class.java
        ).apply { isAccessible = true }.invoke(coordinator, success, message)
    }
}
