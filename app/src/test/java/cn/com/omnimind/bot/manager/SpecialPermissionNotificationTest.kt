package cn.com.omnimind.bot.manager

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class SpecialPermissionNotificationTest {
    @Test
    @Config(sdk = [29, 32])
    fun `pre Android 13 checks and requests reflect the app notification switch without a permission activity`() {
        val application = RuntimeEnvironment.getApplication()
        val notifications = shadowOf(application.getSystemService(NotificationManager::class.java))
        val manager = SpecialPermissionManager(application)

        for (enabled in listOf(false, true)) {
            notifications.setNotificationsEnabled(enabled)
            assertEquals(enabled, resultOf(manager::isNotificationPermissionGranted))
            assertEquals(enabled, resultOf(manager::requestNotificationPermission))
            assertNull(shadowOf(application).nextStartedActivity)
        }
    }

    @Test
    @Config(sdk = [33, 34])
    fun `Android 13 and later require both runtime permission and the app notification switch`() {
        val application = RuntimeEnvironment.getApplication()
        val notifications = shadowOf(application.getSystemService(NotificationManager::class.java))
        val manager = SpecialPermissionManager(application)
        notifications.setNotificationsEnabled(true)

        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(false, resultOf(manager::isNotificationPermissionGranted))

        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(true, resultOf(manager::isNotificationPermissionGranted))

        notifications.setNotificationsEnabled(false)
        assertEquals(false, resultOf(manager::isNotificationPermissionGranted))
        assertNull(shadowOf(application).nextStartedActivity)
    }

    private fun resultOf(call: (MethodChannel.Result) -> Unit): Any? {
        var value: Any? = null
        call(object : MethodChannel.Result {
            override fun success(result: Any?) { value = result }
            override fun error(code: String, message: String?, details: Any?) {
                fail("$code: $message")
            }
            override fun notImplemented() { fail("Notification permission method missing") }
        })
        return value
    }
}
