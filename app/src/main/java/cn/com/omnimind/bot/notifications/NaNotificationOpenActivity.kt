package cn.com.omnimind.bot.notifications

import android.app.Activity
import android.os.Bundle
import cn.com.omnimind.bot.agent.runtime.NaConversationAccess
import cn.com.omnimind.bot.agent.runtime.NaNotificationDelivery
import cn.com.omnimind.bot.agent.runtime.NaNotificationStore
import cn.com.omnimind.bot.agent.runtime.naNotificationIdentifier
import cn.com.omnimind.bot.util.TaskCompletionNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Notification clicks enter the existing local Conversation/ACP history route.
 * A stale notification can never select a different account's conversation. */
class NaNotificationOpenActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope.launch {
            try {
                val state = NaNotificationStore(this@NaNotificationOpenActivity).read()
                val owner = intent.getStringExtra("naOwner")
                val lease = intent.getStringExtra("naLease")
                val id = naNotificationIdentifier(intent.getStringExtra("naNotificationId"))
                check(state.enabled && state.owner == owner && state.lease == lease &&
                    NaNotificationDelivery.ownerMatches(this@NaNotificationOpenActivity, state))
                val localId = intent.getLongExtra("conversationId", 0).takeIf { it > 0 }
                if (localId != null) withContext(Dispatchers.IO) {
                    check(cn.com.omnimind.bot.agent.runtime.AcpAgentProfileStore(this@NaNotificationOpenActivity)
                        .agentIdForConversation(localId) == cn.com.omnimind.bot.agent.runtime.NA_AGENT_ID)
                    check(NaConversationAccess(this@NaNotificationOpenActivity).canRead(localId, owner))
                }
                NaNotificationStore(this@NaNotificationOpenActivity).requireCurrent(state)
                check(NaNotificationDelivery.ownerMatches(this@NaNotificationOpenActivity, state))
                NaNotificationDelivery.cancel(this@NaNotificationOpenActivity, state.owner, id)
                TaskCompletionNavigator.navigateToMainRoute(this@NaNotificationOpenActivity,
                    if (localId == null) "/home/na/personal" else TaskCompletionNavigator.buildChatRoute(localId, "agent"), false)
            } catch (_: Exception) {
                // A revoked/changed connection must not open another user's data.
            } finally { finish() }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
