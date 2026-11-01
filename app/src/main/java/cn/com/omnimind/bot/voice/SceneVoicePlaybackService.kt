package cn.com.omnimind.bot.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.activity.MainActivity
import cn.com.omnimind.bot.util.NestedBackgroundStateUtil

/** Keeps user-started speech audible after its ACP turn or Activity has ended. */
class SceneVoicePlaybackService : Service() {
    companion object {
        internal const val ACTION_START = "cn.com.omnimind.bot.voice.START"
        internal const val ACTION_STOP = "cn.com.omnimind.bot.voice.STOP"
        internal const val EXTRA_GENERATION = "generation"
        private const val CHANNEL_ID = "scene_voice_playback"
        private const val NOTIFICATION_ID = 2048111

        private var appContext: Context? = null
        internal val leases = SceneVoiceForegroundLeases(
            canStart = {
                Build.VERSION.SDK_INT < 37 ||
                    NestedBackgroundStateUtil.getInstance().hasVisibleActivity()
            },
            startService = { generation ->
                val context = checkNotNull(appContext)
                context.startForegroundService(Intent(context, SceneVoicePlaybackService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_GENERATION, generation)
                })
            },
            stopService = {
                appContext?.let { it.stopService(Intent(it, SceneVoicePlaybackService::class.java)) }
            },
        )

        internal fun acquire(
            context: Context,
            owner: Any,
            onStopped: (SceneVoiceForegroundLeases.Lease, String?) -> Unit,
        ): SceneVoiceForegroundLeases.Lease {
            appContext = context.applicationContext
            return leases.acquire(owner, onStopped)
        }
    }

    private var generation: Long? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            leases.stopAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val requestedGeneration = intent.getLongExtra(EXTRA_GENERATION, -1)
        try {
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
            if (leases.onForeground(requestedGeneration)) generation = requestedGeneration
            if (!leases.hasOwners()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        } catch (error: Exception) {
            leases.stopAll(
                error = if (AppLocaleManager.isEnglish(this)) {
                    "Unable to start reading aloud. Open the app and try again."
                } else {
                    "无法开始朗读，请打开应用后重试。"
                },
                expectedGeneration = requestedGeneration,
            )
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        generation?.let {
            leases.stopAll(
                error = if (AppLocaleManager.isEnglish(this)) "Reading aloud stopped" else "朗读已停止",
                expectedGeneration = it,
            )
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val english = AppLocaleManager.isEnglish(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            if (english) "Reading aloud" else "语音朗读",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) })
        val openApp = PendingIntent.getActivity(
            this, NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, NOTIFICATION_ID,
            Intent(this, SceneVoicePlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (english) "Reading aloud" else "正在朗读")
            .setContentText(if (english) "Preparing or playing voice messages" else "正在合成或播放语音消息")
            .setContentIntent(openApp)
            .addAction(0, if (english) "Stop" else "停止朗读", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
    }
}
