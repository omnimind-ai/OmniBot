package cn.com.omnimind.bot.activity

import android.content.Context
import android.content.Intent
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.nativeui.LegacyDestination
import java.net.URLDecoder

/**
 * Routes that reach the app from outside (task-completion and scheduled
 * Sub Agent notifications, `TaskCompletionNavigator`) and that native Home
 * can open itself (batch 5e-7f). Producers keep building their Flutter
 * route strings; this maps the chat ones to a native destination.
 */
internal object NativeEntryRoutes {
    const val EXTRA_DESTINATION_CONVERSATION_ID = "native_destination_conversation_id"
    const val EXTRA_DESTINATION_CONVERSATION_MODE = "native_destination_conversation_mode"

    /**
     * `/home/chat?conversationId=12&mode=agent` → [LegacyDestination.OpenConversation].
     * Anything else (an untargeted `/home/chat`, other pages) stays with Flutter.
     */
    fun destinationFor(route: String?): LegacyDestination.OpenConversation? {
        val trimmed = route?.trim().orEmpty()
        val path = trimmed.substringBefore('?')
        if (path != "/home/chat") return null
        val query = trimmed.substringAfter('?', "").split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=').takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            key to decode(pair.substringAfter('=', ""))
        }.toMap()
        val id = query["conversationId"]?.toLongOrNull()
        if (id == null || id <= 0) return null
        return LegacyDestination.OpenConversation(id, query["mode"].orEmpty().ifBlank { "agent" })
    }

    private fun decode(value: String) = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    /**
     * The intent that brings native Home forward on [destination], or null
     * when native Home is off. CLEAR_TOP removes the `MainActivity` that
     * received the notification; native Home gets it in `onNewIntent`.
     */
    fun nativeHomeIntent(context: Context, destination: LegacyDestination.OpenConversation): Intent? {
        if (!BuildConfig.NATIVE_HOME_ENABLED) return null
        return Intent(context, NativeHomeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_DESTINATION_CONVERSATION_ID, destination.id)
            putExtra(EXTRA_DESTINATION_CONVERSATION_MODE, destination.mode)
        }
    }

    /** Reads what [nativeHomeIntent] wrote. */
    fun destinationFrom(intent: Intent): LegacyDestination.OpenConversation? {
        val id = intent.getLongExtra(EXTRA_DESTINATION_CONVERSATION_ID, -1L)
        if (id <= 0) return null
        return LegacyDestination.OpenConversation(id, intent.getStringExtra(EXTRA_DESTINATION_CONVERSATION_MODE).orEmpty())
    }
}
