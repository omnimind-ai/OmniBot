package cn.com.omnimind.bot.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.service.notification.StatusBarNotification
import cn.com.omnimind.bot.agent.tool.BuiltInAgentCapabilityModule
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class NotificationAccessTest {
    private val context = mock(Context::class.java)
    private val manager = mock(NotificationManager::class.java)
    private val prefs = mock(SharedPreferences::class.java)
    private val listener = mock(OmniNotificationListener::class.java)
    init {
        `when`(context.packageName).thenReturn("cn.com.omnimind.bot")
        `when`(context.getSystemService(NotificationManager::class.java)).thenReturn(manager)
        `when`(context.getSharedPreferences("notification_access", Context.MODE_PRIVATE)).thenReturn(prefs)
        `when`(prefs.getStringSet(eq("allowed_packages"), anySet())).thenReturn(setOf("test.delivery"))
        `when`(manager.isNotificationListenerAccessGranted(any(ComponentName::class.java))).thenReturn(true)
        OmniNotificationListener.connected = listener
    }
    @Test fun `bulk consent includes system messaging apps excludes self and can clear`() {
        val pm = mock(android.content.pm.PackageManager::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(context.packageManager).thenReturn(pm)
        val sms = android.content.pm.ApplicationInfo().apply { packageName = "test.sms"; flags = android.content.pm.ApplicationInfo.FLAG_SYSTEM }
        val own = android.content.pm.ApplicationInfo().apply { packageName = context.packageName }
        `when`(pm.getInstalledApplications(0)).thenReturn(listOf(sms, own))
        `when`(prefs.edit()).thenReturn(editor)
        `when`(editor.putStringSet(eq("allowed_packages"), anySet())).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        NotificationAccess.setAllAllowed(context, true)
        verify(editor).putStringSet("allowed_packages", setOf("test.sms"))
        NotificationAccess.setAllAllowed(context, false)
        verify(editor).putStringSet("allowed_packages", emptySet())
        verify(manager, never()).isNotificationListenerAccessGranted(any(ComponentName::class.java))
    }
    @Test fun `real time wait responds to app event and cancellation detaches`() = kotlinx.coroutines.runBlocking {
        `when`(listener.activeNotifications).thenReturn(emptyArray())
        val wait = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            NotificationAccess.awaitChange(context, "test.delivery", 10)
        }
        OmniNotificationListener.changes.emit("other.app")
        kotlinx.coroutines.yield()
        assertFalse(wait.isCompleted)
        OmniNotificationListener.changes.emit("test.delivery")
        assertEquals(true, wait.await()["changed"])
        val cancelled = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            NotificationAccess.awaitChange(context, "test.delivery", 10)
        }
        cancelled.cancel()
        cancelled.join()
        assertEquals(0, OmniNotificationListener.changes.subscriptionCount.value)
    }
    @After fun cleanup() { OmniNotificationListener.connected = null }

    @Test fun `builtin catalog exposes bounded app-scoped read`() {
        val definition = BuiltInAgentCapabilityModule.definitions.single { it.name == "notifications_read" }
        assertTrue(definition.description.contains("untrusted"))
        assertTrue(definition.parameters.toString().contains("applicationId"))
    }
    @Test fun `permission denial and app exclusion block reads`() {
        `when`(manager.isNotificationListenerAccessGranted(any(ComponentName::class.java))).thenReturn(false)
        assertThrows(IllegalStateException::class.java) { NotificationAccess.read(context, "test.delivery", 0, 20) }
        `when`(manager.isNotificationListenerAccessGranted(any(ComponentName::class.java))).thenReturn(true)
        assertThrows(IllegalStateException::class.java) { NotificationAccess.read(context, "other.app", 0, 20) }
        verify(listener, never()).activeNotifications
    }
    @Test fun `disconnect and invalid query do not become an empty successful result`() {
        OmniNotificationListener.connected = null
        assertThrows(IllegalStateException::class.java) { NotificationAccess.read(context, "test.delivery", 0, 20) }
        assertThrows(IllegalArgumentException::class.java) { NotificationAccess.read(context, "test.delivery", -1, 20) }
        assertThrows(IllegalArgumentException::class.java) { NotificationAccess.read(context, "test.delivery", 0, 51) }
    }
    private fun notification(id: String, pkg: String, at: Long, body: String): StatusBarNotification {
        val extras = mock(Bundle::class.java)
        `when`(extras.getCharSequence(Notification.EXTRA_TITLE)).thenReturn("Delivery")
        `when`(extras.getCharSequence(Notification.EXTRA_TEXT)).thenReturn(body)
        val n = Notification().apply { this.extras = extras }
        return mock(StatusBarNotification::class.java).also {
            `when`(it.key).thenReturn(id); `when`(it.packageName).thenReturn(pkg)
            `when`(it.postTime).thenReturn(at); `when`(it.notification).thenReturn(n)
        }
    }
    @Test fun `query filters sorts bounds and reflects updates and removals without stale history`() {
        val first = notification("order", "test.delivery", 100, "Preparing")
        val other = notification("other", "other.app", 300, "PRIVATE")
        val newer = notification("new", "test.delivery", 200, "On the way")
        `when`(listener.activeNotifications).thenReturn(arrayOf(first, other, newer))
        val bounded = NotificationAccess.read(context, "test.delivery", 0, 1)
        assertEquals(true, bounded["hasMore"])
        assertTrue(bounded.toString().contains("On the way"))
        assertFalse(bounded.toString().contains("PRIVATE"))
        assertEquals(1, NotificationAccess.read(context, "test.delivery", 150, 20)["count"])
        val delivered = notification("order", "test.delivery", 210, "Delivered")
        `when`(listener.activeNotifications).thenReturn(arrayOf(delivered))
        val updated = NotificationAccess.read(context, "test.delivery", 0, 20).toString()
        assertTrue(updated.contains("Delivered")); assertFalse(updated.contains("Preparing"))
        `when`(listener.activeNotifications).thenReturn(emptyArray())
        assertEquals(0, NotificationAccess.read(context, "test.delivery", 0, 20)["count"])
    }
}
