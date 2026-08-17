package cn.com.omnimind.bot.task.runtime

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
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.manager.AssistsCoreManager
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.activity.MainActivity
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground host for user-visible long-running tasks.
 *
 * The service owns the durable launch envelope and re-attaches it to the
 * existing Agent runner. The runner itself stays in AssistsCoreManager so this
 * migration does not duplicate the business loop.
 */
class TaskRuntimeService : Service() {
    companion object {
        private const val TAG = "TaskRuntimeService"
        const val ACTION_START = "cn.com.omnimind.bot.task.runtime.START"

        private const val CHANNEL_ID = "task_runtime_execution"
        private const val CHANNEL_NAME = "任务执行"
        private const val NOTIFICATION_ID = 2048110
    }

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private val dispatchedTaskIds = ConcurrentHashMap.newKeySet<String>()

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        OmniLog.d(TAG, "Task runtime foreground service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                updateNotification()
                dispatchPendingTasks()
            }

            else -> {
                OmniLog.w(TAG, "Ignoring unknown task runtime action=${intent?.action}")
                dispatchPendingTasks()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        OmniLog.d(TAG, "Task runtime foreground service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dispatchPendingTasks() {
        val manager = AssistsCoreManager.sharedInstanceOrCreate(applicationContext)
        TaskRuntimeStore.listPending(applicationContext).forEach { record ->
            if (!dispatchedTaskIds.add(record.taskId)) return@forEach
            if (manager.activeAgentTaskIds().contains(record.taskId)) return@forEach
            TaskRuntimeStore.markRunning(applicationContext, record.taskId)
            val arguments = record.payload.toMutableMap().apply {
                put("__taskRuntimeOwned", true)
            }
            manager.createAgentTask(
                MethodCall("createAgentTask", arguments),
                NoOpResult,
            )
        }
    }

    private object NoOpResult : MethodChannel.Result {
        override fun success(result: Any?) = Unit
        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) = Unit
        override fun notImplemented() = Unit
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon.takeIf { it != 0 } ?: R.mipmap.ic_launcher)
            .setContentTitle("小万任务执行中")
            .setContentText("正在后台执行用户发起的任务")
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "小万在后台执行用户发起的任务"
                setShowBadge(false)
            },
        )
    }

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
}
