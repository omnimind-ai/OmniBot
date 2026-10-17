package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import cn.com.omnimind.baselib.account.OmniAccount
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.bot.BuildConfig

/** Local Conversation access uses the same owner prefix as its ACP session.
 * A pre-session reservation survives session detach; it cannot reassign an owner.
 * Unknown legacy ownership stays on disk and is not claimed by the next login. */
internal class NaConversationAccess(
    private val profiles: AcpAgentProfileStore,
    private val sessionFor: suspend (Long) -> String?,
    private val currentOwner: () -> String?,
) {
    constructor(context: Context) : this(
        AcpAgentProfileStore(context),
        { DatabaseHelper.getAgentSessionBindingByConversationId(it)?.threadId },
        { currentOwnerPrefix(context) },
    )

    companion object {
        private val sessionOwnerPattern = Regex("^na:[0-9a-f]{16}:")
        fun sessionOwner(sessionId: String?): String? =
            sessionId?.let { sessionOwnerPattern.find(it)?.value }

        fun currentOwnerPrefix(context: Context): String? = runCatching {
            val preferences = context.getSharedPreferences("na_cloud_connection", Context.MODE_PRIVATE)
            if (BuildConfig.DEBUG && preferences.getBoolean("development", false)) {
                NaCloudConnection(preferences.getString("baseUrl", "http://127.0.0.1:8787")!!).prefix
            } else {
                if (!OmniAccount.isConfigured()) return@runCatching null
                val userId = OmniAccount.repository().cachedCloudUserId() ?: return@runCatching null
                NaCloudConnection(
                    NaCloudConnection.officialBaseUrl(BuildConfig.CLOUD_URL, BuildConfig.DEBUG),
                    userId = userId,
                ).prefix
            }
        }.getOrNull()
    }

    fun captureOwner(): String? = currentOwner()

    suspend fun canRead(conversationId: Long, owner: String? = currentOwner()): Boolean =
        canReadStored(conversationId, sessionFor(conversationId), owner)

    fun canReadStored(conversationId: Long, sessionId: String?, owner: String? = currentOwner()): Boolean {
        val sessionOwner = sessionOwner(sessionId)
        val reservedOwner = profiles.naOwnerForConversation(conversationId)
        if (sessionOwner == null && reservedOwner == null &&
            profiles.agentIdForConversation(conversationId) != NA_AGENT_ID) return true
        return owner != null && owner == currentOwner() && owner == (sessionOwner ?: reservedOwner)
    }

    suspend fun <T> read(conversationId: Long, operation: suspend () -> T): T {
        val owner = captureOwner()
        check(canRead(conversationId, owner)) { "请登录此云端会话所属的账号。" }
        val result = operation()
        check(canRead(conversationId, owner)) { "账号已切换，请重新打开会话。" }
        return result
    }
}
