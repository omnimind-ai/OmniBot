package cn.com.omnimind.bot.ui.nativehome

import android.app.Activity
import android.content.Intent
import android.net.Uri
import cn.com.omnimind.bot.activity.MainActivity
import cn.com.omnimind.nativeui.LegacyDestination
import cn.com.omnimind.nativeui.LegacyDestination.Page
import java.util.UUID

/** Remove each mapping when its feature has moved to Compose. Never dispatches an Agent prompt. */
internal class LegacyHomeNavigator(private val activity: Activity) {
    fun open(destination: LegacyDestination) {
        val route = when (destination) {
            is LegacyDestination.Conversation -> Uri.Builder().path("/home/chat")
                .appendQueryParameter("conversationId", destination.id.toString())
                .appendQueryParameter("mode", destination.mode)
                .apply { destination.agentId?.let { appendQueryParameter("agentId", it) } }
                .appendQueryParameter("requestKey", UUID.randomUUID().toString()).build().toString()
            is LegacyDestination.NewConversation -> Uri.Builder().path("/home/chat")
                .appendQueryParameter("conversationId", "new")
                .appendQueryParameter("requestKey", UUID.randomUUID().toString())
                .appendQueryParameter("nativeDraft", destination.draft).build().toString()
            is LegacyDestination.TerminalPackage -> Uri.Builder().path("/home/termux_setting")
                .apply { if (destination.packageId.isNotBlank()) appendQueryParameter("focus", destination.packageId) }
                .build().toString()
            is Page -> when (destination) {
                Page.Account -> "/my/account"
                Page.ModelProviders -> "/home/model_provider_setting"
                Page.SceneModels -> "/home/scene_model_setting"
                Page.WorkspaceMemory -> "/home/workspace_memory_setting"
                Page.Agents -> "/home/agent_mode_setting"
                Page.Terminal -> "/home/termux_setting"
                Page.McpTools -> "/home/mcp_tools"
                Page.AppearanceDetails -> "/home/background_setting?section=background"
                Page.Miscellaneous -> "/home/experience_misc_setting"
                Page.Storage -> "/home/storage_usage"
                Page.RequestLogs -> "/my/about/request-logs"
                Page.RuntimeLogs -> "/my/about/runtime-logs"
                Page.UserGuide -> "/my/about/user-guide"
                Page.Memory -> "/memory/memory_center_page"
                Page.Plugins -> "/home/plugin_market"
                Page.Skills -> "/home/skill_store"
                Page.ExecutionHistory -> "/task/execution_history"
                Page.ScheduledTasks -> "/task/scheduled_tasks"
                Page.Workspace -> "/home/chat"
            }
        }
        activity.startActivity(Intent(activity, MainActivity::class.java)
            .putExtra(EXTRA_NATIVE_DESTINATION, route))
    }

    companion object {
        const val EXTRA_NATIVE_DESTINATION = "native_home_destination"
    }
}
