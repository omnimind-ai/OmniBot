package cn.com.omnimind.bot.notifications

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.com.omnimind.baselib.account.OmniAccount
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.activity.MainActivity
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import cn.com.omnimind.bot.agent.runtime.NA_AGENT_ID
import cn.com.omnimind.bot.agent.runtime.NaNotificationDelivery
import cn.com.omnimind.bot.agent.runtime.NaNotificationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** An Android lease for receiving server-owned messages. This service never
 * starts an agent, creates a turn, approves an action, or replays a prompt. */
class NaMessageService : Service() {
    companion object {
        private const val CHANNEL = "na_message_connection"
        private const val ID = 2048121
        private const val WORK = "na_notification_recovery"
        private const val START = "cn.com.omnimind.bot.na.messages.START"
        @Volatile var running = false
            private set
        @Volatile private var initialized = false
        private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun initialize(application: Application) {
            if (initialized) return
            initialized = true
            restoreRecovery(application)
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    val state = NaNotificationStore(application).read()
                    if (state.enabled && !NaNotificationDelivery.ownerMatches(application, state)) {
                        NaNotificationDelivery.disable(application)
                    } else if (state.enabled && !running && NaNotificationDelivery.canPost(application)) {
                        runCatching { startReception(application) }
                    }
                }
                override fun onActivityCreated(activity: Activity, state: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
            if (OmniAccount.isConfigured()) processScope.launch {
                val account = OmniAccount.repository()
                var previous = account.sessionGeneration.value
                account.sessionGeneration.collect { generation ->
                    if (generation != previous) {
                        val state = NaNotificationStore(application).read()
                        if (state.enabled && !state.development) NaNotificationDelivery.disable(application)
                    }
                    previous = generation
                }
            }
        }

        private fun restoreRecovery(context: Context) {
            val state = NaNotificationStore(context).read()
            if (!state.enabled) return
            if (!NaNotificationDelivery.ownerMatches(context, state)) {
                NaNotificationDelivery.disable(context)
                return
            }
            val request = PeriodicWorkRequestBuilder<NaNotificationRecoveryWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun startReception(context: Context) {
            restoreRecovery(context)
            val state = NaNotificationStore(context).read()
            if (!state.enabled || !NaNotificationDelivery.ownerMatches(context, state)) return
            context.startForegroundService(Intent(context, NaMessageService::class.java).setAction(START))
        }

        fun stopReception(context: Context) {
            context.stopService(Intent(context, NaMessageService::class.java))
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }

        internal suspend fun synchronize(context: Context, waitMs: Int): Map<*, *> {
            val state = NaNotificationStore(context).read()
            if (!state.enabled || !NaNotificationDelivery.ownerMatches(context, state)) return emptyMap<String, Any?>()
            return AgentRuntimeManager.getInstance(context).handleMethod("config/na/notifications", mapOf(
                "agentId" to NA_AGENT_ID, "operation" to "sync", "lease" to state.lease,
                "expectedOwner" to state.owner, "waitMs" to waitMs,
            )) as? Map<*, *> ?: emptyMap<String, Any?>()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reception: Job? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Na 后台连接", NotificationManager.IMPORTANCE_LOW).apply {
            description = "保持与 Na 的消息连接，接收主动提醒"
            setShowBadge(false)
        })
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("route", "/home/na/personal")
            putExtra("needClear", false)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Na 主动消息已开启").setContentText("正在接收你的个人助理提醒")
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(PendingIntent.getActivity(this, ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
        ServiceCompat.startForeground(this, ID, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = NaNotificationStore(this).read()
        if (!state.enabled || !NaNotificationDelivery.ownerMatches(this, state) ||
            (intent != null && intent.action != START)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        running = true
        if (reception?.isActive != true) reception = scope.launch {
            while (isActive && NaNotificationStore(this@NaMessageService).read().enabled) {
                try {
                    synchronize(this@NaMessageService, 25000)
                    delay(if (NaNotificationDelivery.canPost(this@NaMessageService)) 500 else 30000)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { delay(15000) }
            }
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class NaNotificationRecoveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val state = NaNotificationStore(applicationContext).read()
        if (!state.enabled) return Result.success()
        if (!NaNotificationDelivery.ownerMatches(applicationContext, state)) {
            NaNotificationDelivery.disable(applicationContext)
            return Result.success()
        }
        if (NaMessageService.running || !NaNotificationDelivery.canPost(applicationContext)) return Result.success()
        return try { NaMessageService.synchronize(applicationContext, 0); Result.success() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
}
