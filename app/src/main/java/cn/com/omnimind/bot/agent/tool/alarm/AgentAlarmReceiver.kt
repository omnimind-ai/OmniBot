package cn.com.omnimind.bot.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.activity.MainActivity
import cn.com.omnimind.bot.update.PrivacyConsentDecision
import cn.com.omnimind.bot.update.PrivacyConsentStore

class AgentAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_AGENT_ALARM_PRE_ALERT_TRIGGER =
            "cn.com.omnimind.bot.agent.ACTION_AGENT_ALARM_PRE_ALERT_TRIGGER"
        const val ACTION_AGENT_ALARM_RING_TRIGGER =
            "cn.com.omnimind.bot.agent.ACTION_AGENT_ALARM_RING_TRIGGER"
        const val ACTION_AGENT_ALARM_CLOSE =
            "cn.com.omnimind.bot.agent.ACTION_AGENT_ALARM_CLOSE"
        const val ACTION_AGENT_ALARM_SNOOZE =
            "cn.com.omnimind.bot.agent.ACTION_AGENT_ALARM_SNOOZE"

        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_ALARM_TITLE = "extra_alarm_title"
        const val EXTRA_ALARM_MESSAGE = "extra_alarm_message"

        private const val PRE_ALERT_CHANNEL_ID = "agent_alarm_pre_alert_channel"
        private const val PRE_ALERT_CHANNEL_NAME = "闹钟提醒"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (!AgentAlarmReceiverConsentPolicy.shouldHandle(
                action,
                PrivacyConsentStore.getDecision(context),
            )
        ) {
            return
        }
        val alarmId = intent?.getStringExtra(EXTRA_ALARM_ID).orEmpty()
        if (alarmId.isBlank()) return

        val toolService = AgentAlarmToolService(context)
        when (action) {
            ACTION_AGENT_ALARM_PRE_ALERT_TRIGGER -> {
                handlePreAlertTrigger(context, toolService, alarmId)
            }

            ACTION_AGENT_ALARM_RING_TRIGGER -> {
                handleRingTrigger(context, toolService, alarmId)
            }

            ACTION_AGENT_ALARM_CLOSE -> {
                AgentAlarmRingingService.stop(context)
                val closed = toolService.closeExactReminder(alarmId)
                showToast(
                    context,
                    if (closed) "闹钟已关闭" else "关闭闹钟失败"
                )
            }

            ACTION_AGENT_ALARM_SNOOZE -> {
                AgentAlarmRingingService.stop(context)
                runCatching {
                    val payload = toolService.snoozeExactReminder(alarmId)
                    val summary = payload["summary"]?.toString().orEmpty()
                    showToast(
                        context,
                        summary.ifBlank { "已延后 5 分钟提醒" }
                    )
                }.onFailure {
                    showToast(context, "稍后提醒失败")
                }
            }
        }
    }

    private fun handlePreAlertTrigger(
        context: Context,
        toolService: AgentAlarmToolService,
        alarmId: String
    ) {
        val record = toolService.markCountdownState(alarmId) ?: return
        val triggerAtMillis = (record["triggerAtMillis"] as? Number)?.toLong() ?: 0L
        if (triggerAtMillis > 0L && triggerAtMillis <= System.currentTimeMillis()) {
            handleRingTrigger(context, toolService, alarmId)
            return
        }
        showPreAlertNotification(context, record)
    }

    private fun handleRingTrigger(
        context: Context,
        toolService: AgentAlarmToolService,
        alarmId: String
    ) {
        val record = toolService.markRingingState(alarmId) ?: return
        val title = record["title"]?.toString().orEmpty().ifBlank { "提醒" }
        val message = record["message"]?.toString().orEmpty().ifBlank { "闹钟响了" }
        AgentAlarmRingingService.start(
            context = context,
            alarmId = alarmId,
            title = title,
            message = message
        )
    }

    private fun showPreAlertNotification(
        context: Context,
        record: Map<String, Any?>
    ) {
        val alarmId = record["alarmId"]?.toString().orEmpty()
        if (alarmId.isBlank()) return

        val title = record["title"]?.toString().orEmpty().ifBlank { "提醒" }
        val message = record["message"]?.toString().orEmpty().ifBlank { "闹钟即将响铃" }
        val triggerAtMillis = (record["triggerAtMillis"] as? Number)?.toLong() ?: 0L

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensurePreAlertChannel(manager)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            AgentAlarmToolService.stableRequestCode("open:$alarmId"),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        val closePendingIntent = actionPendingIntent(
            context = context,
            action = ACTION_AGENT_ALARM_CLOSE,
            alarmId = alarmId,
            requestSeed = "close:$alarmId"
        )
        val snoozePendingIntent = actionPendingIntent(
            context = context,
            action = ACTION_AGENT_ALARM_SNOOZE,
            alarmId = alarmId,
            requestSeed = "snooze:$alarmId"
        )

        val builder = NotificationCompat.Builder(context, PRE_ALERT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setSubText("闹钟即将开始")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(0, "关闭", closePendingIntent)
            .addAction(0, "5分钟后再提醒", snoozePendingIntent)

        if (triggerAtMillis > System.currentTimeMillis()) {
            val remainingMillis = triggerAtMillis - System.currentTimeMillis()
            builder.setWhen(triggerAtMillis)
            builder.setUsesChronometer(true)
            builder.setTimeoutAfter((remainingMillis + 1500L).coerceAtLeast(1000L))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setChronometerCountDown(true)
            }
        }

        manager.notify(AgentAlarmToolService.stableNotificationId(alarmId), builder.build())
    }

    private fun ensurePreAlertChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            PRE_ALERT_CHANNEL_ID,
            PRE_ALERT_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "闹钟提前提醒与倒计时"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun actionPendingIntent(
        context: Context,
        action: String,
        alarmId: String,
        requestSeed: String
    ): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            AgentAlarmToolService.stableRequestCode(requestSeed),
            Intent(context, AgentAlarmReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_ALARM_ID, alarmId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
    }

    private fun immutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}

/** Existing trigger intents can survive a consent change, so check again at delivery time. */
internal object AgentAlarmReceiverConsentPolicy {
    fun shouldHandle(action: String, decision: PrivacyConsentDecision): Boolean {
        val isAutomaticTrigger = action == AgentAlarmReceiver.ACTION_AGENT_ALARM_PRE_ALERT_TRIGGER ||
            action == AgentAlarmReceiver.ACTION_AGENT_ALARM_RING_TRIGGER
        return !isAutomaticTrigger || WorkspaceScheduleConsentPolicy.allowsAutomaticRun(decision)
    }
}
