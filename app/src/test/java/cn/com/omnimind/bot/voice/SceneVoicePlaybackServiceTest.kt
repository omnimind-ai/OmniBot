package cn.com.omnimind.bot.voice

import android.app.Application
import android.content.Intent
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class SceneVoicePlaybackServiceTest {
    @After
    fun tearDown() {
        SceneVoicePlaybackService.leases.stopAll()
    }

    @Test
    fun `manager cannot play until service promotes and notification stop releases every manager`() {
        val application = RuntimeEnvironment.getApplication()
        var stopped = 0
        val first = SceneVoicePlaybackService.acquire(application, Any()) { _, _ -> stopped++ }
        val second = SceneVoicePlaybackService.acquire(application, Any()) { _, _ -> stopped++ }
        val start = shadowOf(application).nextStartedService
        val controller = Robolectric.buildService(SceneVoicePlaybackService::class.java).create()
        val service = controller.get()
        try {
            assertFalse(first.isReady)
            service.onStartCommand(start, 0, 1)
            assertTrue(shadowOf(service).isLastForegroundNotificationAttached)
            assertTrue(first.isReady)
            assertTrue(second.isReady)

            service.onStartCommand(
                Intent(service, SceneVoicePlaybackService::class.java)
                    .setAction(SceneVoicePlaybackService.ACTION_STOP),
                0, 2,
            )
            assertEquals(2, stopped)
            assertFalse(first.active)
            assertFalse(second.active)
            assertTrue(shadowOf(service).isForegroundStopped)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `failed foreground promotion reports error without allowing audio`() {
        val application = RuntimeEnvironment.getApplication()
        var error: String? = null
        val lease = SceneVoicePlaybackService.acquire(application, Any()) { _, reason -> error = reason }
        val start = shadowOf(application).nextStartedService
        val controller = Robolectric.buildService(SceneVoicePlaybackService::class.java).create()
        val service = controller.get()
        shadowOf(service).setThrowInStartForeground(SecurityException("Denied"))
        try {
            service.onStartCommand(start, 0, 1)
            assertNotNull(error)
            assertFalse(lease.isReady)
            assertFalse(lease.active)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }
}
