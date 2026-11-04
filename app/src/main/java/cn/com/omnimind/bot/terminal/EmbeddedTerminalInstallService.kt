package cn.com.omnimind.bot.terminal

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.activity.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Android foreground lease for the shared installer; the coordinator owns every run. */
class EmbeddedTerminalInstallService : Service() {
    companion object {
        private const val TAG = "EmbeddedTerminalInstall"
        internal const val CHANNEL_ID = "embedded_system_installation"
        internal const val NOTIFICATION_ID = 2048122
        internal const val ACTION_OBSERVE = "cn.com.omnimind.bot.terminal.OBSERVE_INSTALLATION"
        internal const val ACTION_RETRY = "cn.com.omnimind.bot.terminal.RETRY_INSTALLATION"

        fun observe(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, EmbeddedTerminalInstallService::class.java).setAction(ACTION_OBSERVE)
                )
            }.onFailure { error ->
                // Notification restrictions must never block installation or foreground UI.
                OmniLog.w(TAG, "Installation notification unavailable", error)
            }
        }

        /** Called by the existing notification permission owner after a user grants access. */
        fun refresh(context: Context) {
            val snapshot = EmbeddedTerminalInitCoordinator.buildSnapshot()
            when {
                snapshot["running"] == true -> observe(context)
                snapshot["completed"] == true && snapshot["success"] != true -> {
                    createChannel(context)
                    post(context, buildNotification(context, snapshot))
                }
                snapshot["success"] == true -> NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            }
        }

        private fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.system_install_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.system_install_notification_channel_description)
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }

        private fun post(context: Context, notification: Notification) {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
                (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED)) return
            try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            } catch (error: SecurityException) {
                OmniLog.w(TAG, "Installation notification permission unavailable", error)
            }
        }

        internal fun downloadPercent(snapshot: Map<String, Any?>): Int? {
            if (snapshot["running"] != true || snapshot["phase"] != "downloading") return null
            val total = (snapshot["totalBytes"] as? Number)?.toLong() ?: 0
            val downloaded = (snapshot["downloadedBytes"] as? Number)?.toLong() ?: 0
            return if (total > 0) {
                (downloaded.toDouble() / total * 100).toInt().coerceIn(0, 100)
            } else null
        }

        internal fun buildNotification(context: Context, snapshot: Map<String, Any?>): Notification {
            val running = snapshot["running"] == true
            val failed = snapshot["completed"] == true && snapshot["success"] != true
            val stage = (snapshot["stage"] as? String).orEmpty()
                .lineSequence().firstOrNull().orEmpty().trim().take(240)
                .ifBlank { context.getString(R.string.system_install_notification_preparing) }
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(
                    if (failed) R.string.system_install_notification_failed
                    else R.string.system_install_notification_running
                ))
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setOngoing(running)
                .setAutoCancel(failed)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            if (failed) {
                val retry = PendingIntent.getForegroundService(
                    context,
                    NOTIFICATION_ID,
                    Intent(context, EmbeddedTerminalInstallService::class.java).setAction(ACTION_RETRY),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val retryHint = context.getString(R.string.system_install_notification_retry_hint)
                builder.setContentText(retryHint)
                    .setContentIntent(retry)
                    .addAction(0, context.getString(R.string.system_install_notification_retry), retry)
            } else {
                val percent = downloadPercent(snapshot)
                val text = if (percent == null) stage else "$stage · $percent%"
                val openApp = PendingIntent.getActivity(
                    context,
                    NOTIFICATION_ID,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(openApp)
                    .setProgress(if (percent == null) 0 else 100, percent ?: 0, running && percent == null)
            }
            return builder.build()
        }
    }

    // This coroutine only waits for an explicit prepare() call. Its cancellation
    // does not own or cancel the coordinator's independently scoped worker.
    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val progressListener: (Map<String, Any?>) -> Unit = { refreshFromCoordinator() }
    private var keepFailureNotification = false
    private var foreground = false
    private var promotionFailed = false
    private var disposed = false
    private var lastContent: List<Any?>? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        val snapshot = EmbeddedTerminalInitCoordinator.buildSnapshot()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(this, snapshot),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
            foreground = true
            EmbeddedTerminalInitCoordinator.addListener(progressListener)
        } catch (error: Exception) {
            promotionFailed = true
            OmniLog.w(TAG, "Could not show installation foreground notification", error)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OBSERVE -> {
                lastContent = null
                refreshFromCoordinator()
            }
            ACTION_RETRY -> {
                // A notification click is the same explicit preparation request
                // as the in-app retry; the coordinator coalesces concurrent calls.
                requestScope.launch {
                    EmbeddedTerminalInitCoordinator.prepare(applicationContext)
                }
                refreshFromCoordinator()
            }
            else -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun refreshFromCoordinator() {
        if (promotionFailed || disposed) return
        val snapshot = EmbeddedTerminalInitCoordinator.buildSnapshot()
        if (snapshot["running"] == true) {
            keepFailureNotification = false
            val content = listOf(snapshot["stage"], downloadPercent(snapshot))
            if (content != lastContent) {
                lastContent = content
                if (foreground) {
                    post(this, buildNotification(this, snapshot))
                } else {
                    try {
                        ServiceCompat.startForeground(
                            this, NOTIFICATION_ID, buildNotification(this, snapshot),
                            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
                        )
                        foreground = true
                    } catch (error: Exception) {
                        promotionFailed = true
                        OmniLog.w(TAG, "Could not restore installation foreground notification", error)
                        stopSelf()
                    }
                }
            }
            return
        }
        keepFailureNotification = snapshot["completed"] == true && snapshot["success"] != true
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        if (keepFailureNotification) post(this, buildNotification(this, snapshot))
        stopSelf()
    }

    override fun onDestroy() {
        disposed = true
        EmbeddedTerminalInitCoordinator.removeListener(progressListener)
        requestScope.cancel()
        if (!keepFailureNotification) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android revokes only the notification lease here. Installer state
        // continues to belong to the coordinator and is never reconstructed.
        disposed = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
