package cn.com.omnimind.bot.ui.nativehome

import android.app.Activity
import android.content.Intent
import android.net.Uri
import cn.com.omnimind.bot.activity.MainActivity
import cn.com.omnimind.bot.activity.NativeOnboardingActivity
import cn.com.omnimind.bot.ui.WebLinks
import cn.com.omnimind.nativeui.LegacyDestination
import cn.com.omnimind.nativeui.LegacyDestination.Page
import java.util.UUID

/** Remove each mapping when its feature has moved to Compose. Never dispatches an Agent prompt. */
internal class LegacyHomeNavigator(private val activity: Activity) {
    fun open(destination: LegacyDestination) {
        if (openNatively(destination)) return
        val route = when (destination) {
            is LegacyDestination.Conversation -> Uri.Builder().path("/home/chat")
                .appendQueryParameter("conversationId", destination.id.toString())
                .appendQueryParameter("mode", destination.mode)
                .apply { destination.agentId?.let { appendQueryParameter("agentId", it) } }
                .apply { if (destination.draft.isNotBlank()) appendQueryParameter("nativeDraft", destination.draft) }
                .appendQueryParameter("requestKey", UUID.randomUUID().toString()).build().toString()
            // NativeHomeApp resolves it and hands a Flutter-only conversation over as Conversation.
            is LegacyDestination.OpenConversation -> Uri.Builder().path("/home/chat")
                .appendQueryParameter("conversationId", destination.id.toString())
                .appendQueryParameter("mode", destination.mode)
                .appendQueryParameter("requestKey", UUID.randomUUID().toString()).build().toString()
            is LegacyDestination.NewConversation -> Uri.Builder().path("/home/chat")
                .appendQueryParameter("conversationId", "new")
                .appendQueryParameter("requestKey", UUID.randomUUID().toString())
                .appendQueryParameter("nativeDraft", destination.draft).build().toString()
            // Flutter applies the pending shared draft itself (`_applyStagedSharedDraftIfNeeded`).
            is LegacyDestination.SharedDraft -> Uri.Builder().path("/home/chat")
                .appendQueryParameter("conversationId", "new")
                .appendQueryParameter("mode", "normal")
                .appendQueryParameter("requestKey", destination.requestKey).build().toString()
            is LegacyDestination.TerminalPackage -> Uri.Builder().path("/home/termux_setting")
                .apply { if (destination.packageId.isNotBlank()) appendQueryParameter("focus", destination.packageId) }
                .build().toString()
            is LegacyDestination.PluginRoute -> destination.route.takeIf { it.startsWith("/") } ?: "/home/chat"
            is Page -> when (destination) {
                Page.Account -> "/my/account"
                Page.ModelProviders -> "/home/model_provider_setting"
                Page.SceneModels -> "/home/scene_model_setting"
                // Temporary hand-off: the native Bridge page's QR scan entry opens the
                // Flutter page, whose scanner autosaves through the same store (batch 4h-2).
                Page.RemoteBridge -> "/home/remote_codex_setting"
                Page.Chat -> "/home/chat"
                // Opened by openNatively.
                Page.QuickStart, Page.UserGuide -> return
            }
            // Native pages since 5e-8a; NativeHomeApp opens them and never hands them over.
            is LegacyDestination.Workspace, is LegacyDestination.WorkspaceFile -> return
        }
        activity.startActivity(Intent(activity, MainActivity::class.java)
            .putExtra(EXTRA_NATIVE_DESTINATION, route))
    }

    /** Pages that moved to native screens outside native Home; true when handled. */
    private fun openNatively(destination: LegacyDestination): Boolean = when (destination) {
        // Quick start replays the native onboarding (5f-1b).
        Page.QuickStart -> {
            activity.startActivity(Intent(activity, NativeOnboardingActivity::class.java)
                .putExtra(NativeOnboardingActivity.EXTRA_REPLAY, true))
            true
        }
        // The manual opens in a Custom Tab instead of the Flutter WebView (5g).
        Page.UserGuide -> {
            WebLinks.open(activity, WebLinks.userGuideUrl(activity.resources.configuration.locales[0]))
            true
        }
        else -> false
    }

    companion object {
        const val EXTRA_NATIVE_DESTINATION = "native_home_destination"
    }
}
