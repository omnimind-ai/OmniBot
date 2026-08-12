package cn.com.omnimind.bot.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.update.PrivacyConsentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WorkspaceMemoryRollupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (action != WorkspaceMemoryRollupScheduler.ACTION_MEMORY_ROLLUP) {
            return
        }
        if (!WorkspaceScheduleConsentPolicy.allowsAutomaticRun(
                PrivacyConsentStore.getDecision(context)
            )) {
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WorkspaceMemoryRollupScheduler(context).onAlarmTriggered()
            } catch (t: Throwable) {
                OmniLog.e(
                    "WorkspaceMemoryRollupReceiver",
                    "nightly rollup failed: ${t.message}"
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
