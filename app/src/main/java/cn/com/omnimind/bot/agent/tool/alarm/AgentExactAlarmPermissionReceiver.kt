package cn.com.omnimind.bot.agent

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.bot.update.PrivacyConsentStore

class AgentExactAlarmPermissionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            return
        }
        if (!WorkspaceScheduleConsentPolicy.allowsAutomaticRun(
                PrivacyConsentStore.getDecision(context)
            )) {
            return
        }
        AgentAlarmToolService(context.applicationContext)
            .reschedulePersistedExactRemindersIfPermitted()
    }
}
