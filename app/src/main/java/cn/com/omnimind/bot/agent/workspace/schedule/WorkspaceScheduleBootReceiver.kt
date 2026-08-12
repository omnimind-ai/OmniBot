package cn.com.omnimind.bot.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.update.PrivacyConsentStore

class WorkspaceScheduleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_TIMEZONE_CHANGED &&
            action != Intent.ACTION_TIME_CHANGED
        ) {
            return
        }
        runCatching {
            if (WorkspaceScheduleConsentPolicy.allowsAutomaticRun(
                    PrivacyConsentStore.getDecision(context)
                )) {
                WorkspaceMemoryRollupScheduler(context).ensureScheduledIfEnabled()
                WorkspaceScheduledTaskScheduler(context).rescheduleAllEnabled()
                AgentAlarmToolService(context).reschedulePersistedExactRemindersIfPermitted()
            }
        }.onFailure {
            OmniLog.e("WorkspaceScheduleBootReceiver", "reschedule failed: ${it.message}")
        }
    }
}
