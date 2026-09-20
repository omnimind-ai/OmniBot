package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.bot.notification.NotificationAccess
import com.google.gson.Gson
import java.io.File

/** Shell-only fixture. Never exports other apps' notification contents. */
class DebugNotificationProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = runCatching {
            when (intent.getStringExtra("operation")) {
                "allow_fixture" -> { NotificationAccess.setAllowed(context, "com.android.shell", true); mapOf("allowed" to true) }
                "catalog" -> mapOf("registered" to cn.com.omnimind.bot.agent.AgentToolRegistry(context)
                    .searchTools("notifications_read", 50).any { it.name == "notifications_read" })
                "clear_fixture" -> {
                    val service = checkNotNull(cn.com.omnimind.bot.notification.OmniNotificationListener.connected)
                    service.activeNotifications.orEmpty().filter {
                        it.packageName == "com.android.shell" &&
                            it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() == "OOB_NOTIFICATION_FIXTURE"
                    }.forEach { service.cancelNotification(it.key) }
                    mapOf("cleared" to true)
                }
                "deny_fixture" -> { NotificationAccess.setAllowed(context, "com.android.shell", false); mapOf("allowed" to false) }
                else -> {
                    val payload = NotificationAccess.read(context, "com.android.shell", 0, 50)
                    val rows = payload["notifications"] as List<*>
                    mapOf("notifications" to rows.filter {
                        (it as? Map<*, *>)?.get("title") == "OOB_NOTIFICATION_FIXTURE"
                    }, "granted" to NotificationAccess.granted(context))
                }
            }
        }.getOrElse { mapOf("error" to (it.message ?: it.javaClass.simpleName)) }
        File(context.filesDir, "notification-probe.json").writeText(Gson().toJson(result))
    }
}
