package cn.com.omnimind.bot.terminal

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import cn.com.omnimind.bot.termux.TermuxLiveEnvironmentResult
import com.rk.terminal.runtime.TerminalDistribution
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class EmbeddedTerminalInstallServiceTest {
    private val coordinator = EmbeddedTerminalInitCoordinator
    private val stateField = coordinator.javaClass.getDeclaredField("embeddedTerminalInitState")
        .apply { isAccessible = true }
    private var previousState: Any? = null
    private val runField = coordinator.javaClass.getDeclaredField("activeRun")
        .apply { isAccessible = true }
    private var previousRun: Any? = null
    private val application get() = RuntimeEnvironment.getApplication()
    private val notifications get() = shadowOf(application.getSystemService(NotificationManager::class.java))

    @Before
    fun reservePreparation() {
        previousState = stateField.get(coordinator)
        previousRun = runField.get(coordinator)
        coordinator.javaClass.getDeclaredMethod(
            "resetEmbeddedTerminalInitStateLocked",
            EmbeddedTerminalInitCoordinator.PreparationRequest::class.java
        )
            .apply { isAccessible = true }.invoke(coordinator, EmbeddedTerminalInitCoordinator.PreparationRequest.Switch(TerminalDistribution.ubuntu))
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun restoreState() {
        ShadowLooper.idleMainLooper()
        stateField.set(coordinator, previousState)
        runField.set(coordinator, previousRun)
    }

    @Test
    fun `package installation is ongoing and indeterminate even if old download bytes remain`() {
        val snapshot = mapOf(
            "running" to true, "stage" to "Installing base tools", "phase" to "installing",
            "progress" to 0.99, "downloadedBytes" to 500L, "totalBytes" to 500L
        )
        val notification = EmbeddedTerminalInstallService.buildNotification(application, snapshot)

        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertEquals("Installing base tools", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNull(notification.fullScreenIntent)
        assertNull(EmbeddedTerminalInstallService.downloadPercent(snapshot))
        assertNull(EmbeddedTerminalInstallService.downloadPercent(snapshot + ("phase" to "verifying")))
    }

    @Test
    fun `only measured download bytes produce a percentage`() {
        val snapshot = mapOf(
            "running" to true, "stage" to "Downloading", "phase" to "downloading",
            "progress" to 0.93, "downloadedBytes" to 120L, "totalBytes" to 400L
        )
        val notification = EmbeddedTerminalInstallService.buildNotification(application, snapshot)

        assertEquals(30, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertEquals("Downloading · 30%", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNull(EmbeddedTerminalInstallService.downloadPercent(snapshot + ("totalBytes" to 0L)))
    }

    @Test
    fun `failure retry is an immutable explicit service request and opens no activity`() {
        val notification = EmbeddedTerminalInstallService.buildNotification(application, mapOf(
            "completed" to true, "success" to false, "stage" to "Network unavailable"
        ))
        val retry = shadowOf(notification.contentIntent)

        assertFalse(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertTrue(retry.isImmutable)
        assertTrue(retry.isForegroundServiceIntent)
        assertEquals(EmbeddedTerminalInstallService.ACTION_RETRY, retry.savedIntent.action)
        assertEquals(EmbeddedTerminalInstallService::class.java.name, retry.savedIntent.component?.className)
        assertEquals(notification.contentIntent, notification.actions.single().actionIntent)
        assertNull(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `failed Ubuntu switch notification restores its request without coordinator memory`() {
        val notification = EmbeddedTerminalInstallService.buildNotification(application, mapOf(
            "completed" to true, "success" to false,
            "requestKind" to "switch", "distribution" to "ubuntu"
        ))
        val retryIntent = shadowOf(notification.contentIntent).savedIntent
        // Simulate the in-memory coordinator being absent after process recreation.
        stateField.set(coordinator, previousState)
        assertEquals(
            EmbeddedTerminalInitCoordinator.PreparationRequest.Switch(TerminalDistribution.ubuntu),
            EmbeddedTerminalInstallService.retryRequest(retryIntent)
        )
    }

    @Test
    fun `package retry notification preserves explicit package selection`() {
        val notification = EmbeddedTerminalInstallService.buildNotification(application, mapOf(
            "completed" to true, "success" to false,
            "requestKind" to "prepare", "distribution" to "alpine",
            "packageIds" to listOf("nodejs", "git")
        ))
        assertEquals(
            EmbeddedTerminalInitCoordinator.PreparationRequest.Prepare(listOf("nodejs", "git")),
            EmbeddedTerminalInstallService.retryRequest(shadowOf(notification.contentIntent).savedIntent)
        )
    }

    @Test
    fun `successful completion removes the foreground lease and its notification`() {
        val controller = Robolectric.buildService(EmbeddedTerminalInstallService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(observeIntent(), 0, 1)
            assertTrue(shadowOf(service).isLastForegroundNotificationAttached)

            complete(success = true)
            ShadowLooper.idleMainLooper()

            assertTrue(shadowOf(service).isForegroundStopped)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertNull(notifications.getNotification(EmbeddedTerminalInstallService.NOTIFICATION_ID))
        } finally { controller.destroy() }
    }

    @Test
    fun `failed completion releases the foreground lease and keeps one dismissible retry notification`() {
        val controller = Robolectric.buildService(EmbeddedTerminalInstallService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(observeIntent(), 0, 1)
            complete(success = false)
            ShadowLooper.idleMainLooper()

            assertTrue(shadowOf(service).isForegroundStopped)
            assertTrue(shadowOf(service).isStoppedBySelf)
            val notification = notifications.getNotification(EmbeddedTerminalInstallService.NOTIFICATION_ID)
            assertNotNull(notification)
            assertFalse(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertTrue(shadowOf(notification.contentIntent).isForegroundServiceIntent)
        } finally { controller.destroy() }
        assertNotNull(notifications.getNotification(EmbeddedTerminalInstallService.NOTIFICATION_ID))
    }

    @Test
    fun `permission denial never starts a permission activity and granting later refreshes the same run`() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val startedAt = coordinator.buildSnapshot()["startedAt"]
        EmbeddedTerminalInstallService.observe(application)
        assertEquals(EmbeddedTerminalInstallService.ACTION_OBSERVE, shadowOf(application).nextStartedService.action)
        assertNull(shadowOf(application).nextStartedActivity)

        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        EmbeddedTerminalInstallService.refresh(application)
        assertEquals(EmbeddedTerminalInstallService.ACTION_OBSERVE, shadowOf(application).nextStartedService.action)
        assertEquals(startedAt, coordinator.buildSnapshot()["startedAt"])
    }

    @Test
    fun `Android foreground timeout stops only the lease and does not change installer state`() {
        val controller = Robolectric.buildService(EmbeddedTerminalInstallService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(observeIntent(), 0, 7)
            val snapshot = coordinator.buildSnapshot()
            service.onTimeout(7, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            assertTrue(shadowOf(service).isForegroundStopped)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertEquals(snapshot, coordinator.buildSnapshot())
        } finally { controller.destroy() }
    }

    @Test
    fun `retry joins the existing preparation and destroying the lease does not cancel its worker`() {
        val run = CompletableDeferred<TermuxLiveEnvironmentResult>()
        runField.set(coordinator, run)
        val controller = Robolectric.buildService(EmbeddedTerminalInstallService::class.java).create()
        val snapshot = coordinator.buildSnapshot()
        controller.get().onStartCommand(
            Intent(application, EmbeddedTerminalInstallService::class.java)
                .setAction(EmbeddedTerminalInstallService.ACTION_RETRY), 0, 1
        )
        ShadowLooper.idleMainLooper()
        assertEquals(snapshot, coordinator.buildSnapshot())
        assertSame(run, runField.get(coordinator))
        controller.destroy()
        ShadowLooper.idleMainLooper()
        assertTrue(run.isActive)
    }

    @Test
    fun `failed foreground promotion cannot throw again while handling the start intent`() {
        val controller = Robolectric.buildService(EmbeddedTerminalInstallService::class.java)
        val service = controller.get()
        shadowOf(service).setThrowInStartForeground(SecurityException("Not allowed"))
        val snapshot = coordinator.buildSnapshot()
        try {
            controller.create()
            service.onStartCommand(observeIntent(), 0, 1)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertEquals(snapshot, coordinator.buildSnapshot())
        } finally { controller.destroy() }
    }

    private fun observeIntent() = Intent(application, EmbeddedTerminalInstallService::class.java)
        .setAction(EmbeddedTerminalInstallService.ACTION_OBSERVE)

    private fun complete(success: Boolean) {
        coordinator.javaClass.getDeclaredMethod(
            "markEmbeddedTerminalInitCompleted", Boolean::class.javaPrimitiveType, String::class.java
        ).apply { isAccessible = true }.invoke(coordinator, success, if (success) "Ready" else "Network unavailable")
    }
}
