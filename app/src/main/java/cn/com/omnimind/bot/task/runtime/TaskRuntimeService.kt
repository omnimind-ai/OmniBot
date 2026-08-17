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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground host for user-visible long-running tasks.
 *
 * The service owns the durable launch envelope and dispatches it through a
 * TaskRunner. The runner still delegates to the existing Agent loop so this
 * boundary does not duplicate or destabilize loop semantics.
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
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var manager: AssistsCoreManager
    private lateinit var agentTaskRunner: AgentTaskRunner

    override fun onCreate() {
        super.onCreate()
        manager = AssistsCoreManager.sharedInstanceOrCreate(applicationContext)
        agentTaskRunner = AgentTaskRunner(manager)
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        OmniLog.d(TAG, "Task runtime foreground service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && TaskRuntimeStore.listPending(applicationContext).isEmpty()) {
            // A foreground lease for an external Harness is intentionally not
            // a second persisted Agent state machine. If Android recreates the
            // process after that Harness was lost, there is nothing safe for
            // this service to resume, so do not leave a zombie notification.
            OmniLog.i(TAG, "Stopping empty task runtime after process recreation")
            stopSelf(startId)
            return START_NOT_STICKY
        }
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
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dispatchPendingTasks() {
        TaskRuntimeStore.listPending(applicationContext).forEach { record ->
            if (!dispatchedTaskIds.add(record.taskId)) return@forEach
            if (record.kind != agentTaskRunner.kind) return@forEach
            if (manager.activeAgentTaskIds().contains(record.taskId)) return@forEach
            TaskRuntimeStore.markRunning(applicationContext, record.taskId)
            val started = runCatching {
                agentTaskRunner.start(record, serviceScope)
            }.getOrElse { error ->
                OmniLog.e(
                    TAG,
                    "Unable to start task runner taskId=${record.taskId}: ${error.message}",
                    error,
                )
                false
            }
            if (!started) {
                dispatchedTaskIds.remove(record.taskId)
                TaskRuntime.finish(applicationContext, record.taskId)
            }
        }
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
