package cn.com.omnimind.bot.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import cn.com.omnimind.baselib.util.OmniLog
import com.tencent.mmkv.MMKV
import java.time.LocalDateTime
import java.time.ZoneId

class WorkspaceMemoryRollupScheduler(
    private val context: Context
) {
    companion object {
        const val ACTION_MEMORY_ROLLUP =
            "cn.com.omnimind.bot.agent.ACTION_WORKSPACE_MEMORY_ROLLUP"

        private const val KEY_ROLLUP_NEXT_RUN_AT = "workspace_memory_rollup_next_run_at_v1"
        private const val KEY_ROLLUP_REQUEST_SEED = "workspace_memory_rollup_alarm"
    }

    private val memoryService = WorkspaceMemoryService(context)
    private val mmkv: MMKV? = MMKV.defaultMMKV()

    fun ensureScheduledIfEnabled() {
        if (!memoryService.isRollupEnabled()) {
            cancel()
            return
        }
        scheduleNext()
    }

    fun setEnabled(enabled: Boolean): WorkspaceMemoryRollupStatus {
        val status = memoryService.saveRollupEnabled(enabled)
        if (enabled) {
            scheduleNext()
        } else {
            cancel()
        }
        return status
    }

    /** Shared manual action for Flutter and native settings; scheduling failures remain warnings. */
    fun runNow(): Map<String, Any?> {
        val payload = memoryService.rollupDay().toMutableMap()
        runCatching { ensureScheduledIfEnabled() }.onFailure { error ->
            OmniLog.w("WorkspaceMemoryRollupScheduler", "run now schedule failed: ${error.message}")
            payload["scheduleWarning"] = error.message
        }
        return payload
    }

    fun getNextRunAtMillis(): Long? {
        return mmkv?.decodeLong(KEY_ROLLUP_NEXT_RUN_AT, 0L)?.takeIf { it > 0 }
    }

    fun onAlarmTriggered() {
        memoryService.rollupDay()
        scheduleNext()
    }

    private fun scheduleNext() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = computeNextTriggerAtMillis()
        val pendingIntent = buildPendingIntent()
        alarmManager.cancel(pendingIntent)
        val scheduled = runCatching {
            scheduleExact(alarmManager, triggerAt, pendingIntent)
            true
        }.recoverCatching { throwable ->
            OmniLog.w(
                "WorkspaceMemoryRollupScheduler",
                "exact alarm unavailable, fallback to inexact: ${throwable.message}"
            )
            scheduleInexact(alarmManager, triggerAt, pendingIntent)
            false
        }.getOrElse { throwable ->
            throw throwable
        }
        mmkv?.encode(KEY_ROLLUP_NEXT_RUN_AT, triggerAt)
        OmniLog.i(
            "WorkspaceMemoryRollupScheduler",
            "rollup scheduled at=$triggerAt exact=$scheduled"
        )
    }

    private fun scheduleExact(
        alarmManager: AlarmManager,
        triggerAt: Long,
        pendingIntent: PendingIntent
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent
            )
        }
    }

    private fun scheduleInexact(
        alarmManager: AlarmManager,
        triggerAt: Long,
        pendingIntent: PendingIntent
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent
            )
        } else {
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent
            )
        }
    }

    private fun cancel() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(buildPendingIntent())
        mmkv?.encode(KEY_ROLLUP_NEXT_RUN_AT, 0L)
    }

    private fun buildPendingIntent(): PendingIntent {
        val intent = Intent(context, WorkspaceMemoryRollupReceiver::class.java).apply {
            action = ACTION_MEMORY_ROLLUP
        }
        return PendingIntent.getBroadcast(
            context,
            KEY_ROLLUP_REQUEST_SEED.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
    }

    private fun computeNextTriggerAtMillis(): Long {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        var target = now.withHour(22).withMinute(0).withSecond(0).withNano(0)
        if (!target.isAfter(now)) {
            target = target.plusDays(1)
        }
        return target.atZone(zone).toInstant().toEpochMilli()
    }

    private fun immutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }
}
