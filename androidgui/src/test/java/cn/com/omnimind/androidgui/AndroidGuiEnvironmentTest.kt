package cn.com.omnimind.androidgui

import android.content.Intent
import cn.com.omnimind.baselib.runlog.Action
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGuiEnvironmentTest {
    @Test
    fun `input popup movement keeps click detection strict and input execution recoverable`() {
        assertEquals(false, InputNodeLookup.CLICK_TARGET.allowFallbackAfterCoordinateMiss)
        assertEquals(true, InputNodeLookup.INPUT_ACTION.allowFallbackAfterCoordinateMiss)
    }

    @Test
    fun `accessibility reconnect window tolerates slow OEM rebinding`() {
        assertEquals(15_000L, ACCESSIBILITY_READY_TIMEOUT_MS)
    }

    @Test
    fun `open app clears stale activity stack and gets an extended stabilization window`() {
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            OPEN_APP_INTENT_FLAGS,
        )
        assertTrue(
            stateStabilizationTimeoutMs("open_app") >
                stateStabilizationTimeoutMs("click"),
        )
    }

    @Test
    fun `screen capture waits for transient accessibility reconnect`() = runBlocking {
        val platform = ReconnectingPlatform()
        val environment = AndroidGuiEnvironment(appContext = null, platform = platform)
        launch {
            delay(100L)
            platform.ready = true
        }

        val snapshot = environment.captureScreenSnapshot()

        assertEquals("com.android.settings", snapshot.packageName)
        assertEquals(1, platform.observeCalls)
    }

    @Test
    fun `action waits for transient accessibility reconnect instead of restarting task`() = runBlocking {
        val platform = ReconnectingPlatform()
        val environment = AndroidGuiEnvironment(appContext = null, platform = platform)
        launch {
            delay(100L)
            platform.ready = true
        }

        val result = environment.act(Action(tool = "wait", args = mapOf("duration_ms" to 0)))

        assertTrue(result.success)
        assertEquals(1, platform.dispatchCalls)
        assertEquals(0, platform.observeCalls)
        assertEquals("host_completed", result.diagnostics["state_stabilization"])
    }

    @Test
    fun `action reports host stabilization after two matching accessibility states`() = runBlocking {
        val platform = ReconnectingPlatform().apply {
            ready = true
            observedStates += state(xml = "<hierarchy value=\"loading\" />")
            observedStates += state(xml = "<hierarchy value=\"ready\" />")
            observedStates += state(xml = "<hierarchy value=\"ready\" />")
        }
        val environment = AndroidGuiEnvironment(appContext = null, platform = platform)

        val result = environment.act(Action(tool = "click", args = mapOf("x" to 10, "y" to 20)))

        assertTrue(result.success)
        assertEquals(3, platform.observeCalls)
        assertEquals("host_completed", result.diagnostics["state_stabilization"])
        assertEquals("stable", result.diagnostics["state_stabilization_result"])
    }

    @Test
    fun `manual action can dispatch without waiting for page stabilization`() = runBlocking {
        val platform = ReconnectingPlatform().apply { ready = true }
        val environment = AndroidGuiEnvironment(appContext = null, platform = platform)

        val result = environment.act(
            Action(tool = "click", args = mapOf("x" to 10, "y" to 20)),
            awaitStabilization = false,
        )

        assertTrue(result.success)
        assertEquals(1, platform.dispatchCalls)
        assertEquals(0, platform.observeCalls)
        assertEquals("runtime_delegated", result.diagnostics["state_stabilization_result"])
    }

    private class ReconnectingPlatform : AndroidGuiPlatform {
        @Volatile
        var ready: Boolean = false
        var observeCalls: Int = 0
        var dispatchCalls: Int = 0
        val observedStates = ArrayDeque<AndroidGuiPlatformState>()

        override fun isAccessibilityEnabled(): Boolean = true

        override fun isReady(): Boolean = ready

        override fun displaySize(): Pair<Int, Int> = 1080 to 2400

        override fun screenshotExcludesOverlays(): Boolean = true

        override suspend fun observe(captureScreenshot: Boolean): AndroidGuiPlatformState {
            check(ready) { "android_gui_accessibility_not_ready" }
            observeCalls += 1
            return observedStates.removeFirstOrNull() ?: state()
        }

        override suspend fun dispatch(action: Action): AndroidGuiActionResult {
            check(ready) { "android_gui_accessibility_not_ready" }
            dispatchCalls += 1
            return AndroidGuiActionResult(success = true, message = "ok")
        }

        override suspend fun inputTarget(
            x: Float?,
            y: Float?,
        ): AndroidGuiInputTarget? = null

        override suspend fun installedApplications(): Map<String, String> = emptyMap()

        override fun inputMethodTop(): Int? = null
    }

    private companion object {
        fun state(xml: String = "<hierarchy />") = AndroidGuiPlatformState(
            packageName = "com.android.settings",
            activityName = "Settings",
            displayWidth = 1080,
            displayHeight = 2400,
            xml = xml,
        )
    }
}
