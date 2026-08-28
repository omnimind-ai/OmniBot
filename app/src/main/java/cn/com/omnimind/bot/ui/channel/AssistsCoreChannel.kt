package cn.com.omnimind.bot.ui.channel

import android.annotation.SuppressLint
import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.App
import cn.com.omnimind.bot.manager.AssistsCoreManager
import cn.com.omnimind.bot.omniflow.OmniFlowToolChannel
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Native task and Agent bridge.
 */
class AssistsCoreChannel {
    var TAG = "[AssistsCoreChannel]"
    private val EVENT_CHANNEL = "cn.com.omnimind.bot/AssistCoreEvent" // Flutter 事件通道
    private var channel: MethodChannel? = null

    @SuppressLint("StaticFieldLeak")
    private var assistsCoreManager: AssistsCoreManager? = null
    private var omniFlowToolChannel: OmniFlowToolChannel? = null
    fun onCreate(context: Context) {
        assistsCoreManager = AssistsCoreManager.sharedInstanceOrCreate(context)
        omniFlowToolChannel = OmniFlowToolChannel(context)
    }



    fun setChannel( flutterEngine: FlutterEngine) {
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
        assistsCoreManager?.setChannel(channel!!);
        if (flutterEngine == App.getCachedMainEngine()) {
            AssistsCoreManager.bindMainEngineChannel(channel!!)
        }
        channel!!.setMethodCallHandler { call, result ->
            OmniLog.d(TAG, "setMethodCallHandler " + call.method)
            if (omniFlowToolChannel?.handle(call, result) == true) {
                return@setMethodCallHandler
            }
            when (call.method) {
                "agentSkillList" -> {
                    assistsCoreManager!!.agentSkillList(call, result)
                }
                "agentSkillInstall" -> {
                    assistsCoreManager!!.agentSkillInstall(call, result)
                }
                "agentSkillSetEnabled" -> {
                    assistsCoreManager!!.agentSkillSetEnabled(call, result)
                }
                "agentSkillDelete" -> {
                    assistsCoreManager!!.agentSkillDelete(call, result)
                }
                "agentSkillInstallBuiltin" -> {
                    assistsCoreManager!!.agentSkillInstallBuiltin(call, result)
                }
                "agentSkillSyncOfficialRepository" -> {
                    assistsCoreManager!!.agentSkillSyncOfficialRepository(call, result)
                }
                "getModelProviderConfig" -> {
                    assistsCoreManager!!.getModelProviderConfig(call, result)
                }
                "listModelProviderProfiles" -> {
                    assistsCoreManager!!.listModelProviderProfiles(call, result)
                }
                "listRecentAiRequestLogs" -> {
                    assistsCoreManager!!.listRecentAiRequestLogs(call, result)
                }
                "listRuntimeLogs" -> {
                    assistsCoreManager!!.listRuntimeLogs(call, result)
                }
                "clearRuntimeLogs" -> {
                    assistsCoreManager!!.clearRuntimeLogs(call, result)
                }
                "saveModelProviderProfile" -> {
                    assistsCoreManager!!.saveModelProviderProfile(call, result)
                }
                "deleteModelProviderProfile" -> {
                    assistsCoreManager!!.deleteModelProviderProfile(call, result)
                }
                "setEditingModelProviderProfile" -> {
                    assistsCoreManager!!.setEditingModelProviderProfile(call, result)
                }
                "saveModelProviderConfig" -> {
                    assistsCoreManager!!.saveModelProviderConfig(call, result)
                }
                "clearModelProviderConfig" -> {
                    assistsCoreManager!!.clearModelProviderConfig(call, result)
                }
                "fetchProviderModels" -> {
                    assistsCoreManager!!.fetchProviderModels(call, result)
                }
                "getSceneModelCatalog" -> {
                    assistsCoreManager!!.getSceneModelCatalog(call, result)
                }
                "getSceneModelBindings" -> {
                    assistsCoreManager!!.getSceneModelBindings(call, result)
                }
                "saveSceneModelBinding" -> {
                    assistsCoreManager!!.saveSceneModelBinding(call, result)
                }
                "clearSceneModelBinding" -> {
                    assistsCoreManager!!.clearSceneModelBinding(call, result)
                }
                "getSceneOperationConfig" -> {
                    assistsCoreManager!!.getSceneOperationConfig(call, result)
                }
                "saveSceneOperationConfig" -> {
                    assistsCoreManager!!.saveSceneOperationConfig(call, result)
                }
                "getSceneVoiceConfig" -> {
                    assistsCoreManager!!.getSceneVoiceConfig(call, result)
                }
                "saveSceneVoiceConfig" -> {
                    assistsCoreManager!!.saveSceneVoiceConfig(call, result)
                }
                "getSceneModelOverrides" -> {
                    assistsCoreManager!!.getSceneModelOverrides(call, result)
                }
                "saveSceneModelOverride" -> {
                    assistsCoreManager!!.saveSceneModelOverride(call, result)
                }
                "clearSceneModelOverride" -> {
                    assistsCoreManager!!.clearSceneModelOverride(call, result)
                }
                "getAgentSoulSetting" -> {
                    assistsCoreManager!!.getAgentSoulSetting(call, result)
                }
                "getChatPromptSetting" -> {
                    assistsCoreManager!!.getChatPromptSetting(call, result)
                }
                "saveAgentSoulSetting" -> {
                    assistsCoreManager!!.saveAgentSoulSetting(call, result)
                }
                "saveChatPromptSetting" -> {
                    assistsCoreManager!!.saveChatPromptSetting(call, result)
                }
                "getWorkspaceLongMemory" -> {
                    assistsCoreManager!!.getWorkspaceLongMemory(call, result)
                }
                "getWorkspaceShortMemories" -> {
                    assistsCoreManager!!.getWorkspaceShortMemories(call, result)
                }
                "listQuickLogs" -> {
                    assistsCoreManager!!.listQuickLogs(call, result)
                }
                "addQuickLog" -> {
                    assistsCoreManager!!.addQuickLog(call, result)
                }
                "updateQuickLog" -> {
                    assistsCoreManager!!.updateQuickLog(call, result)
                }
                "deleteQuickLog" -> {
                    assistsCoreManager!!.deleteQuickLog(call, result)
                }
                "saveWorkspaceLongMemory" -> {
                    assistsCoreManager!!.saveWorkspaceLongMemory(call, result)
                }
                "getWorkspaceMemoryEmbeddingConfig" -> {
                    assistsCoreManager!!.getWorkspaceMemoryEmbeddingConfig(call, result)
                }
                "saveWorkspaceMemoryEmbeddingConfig" -> {
                    assistsCoreManager!!.saveWorkspaceMemoryEmbeddingConfig(call, result)
                }
                "getWorkspaceMemoryRollupStatus" -> {
                    assistsCoreManager!!.getWorkspaceMemoryRollupStatus(call, result)
                }
                "saveWorkspaceMemoryRollupEnabled" -> {
                    assistsCoreManager!!.saveWorkspaceMemoryRollupEnabled(call, result)
                }
                "runWorkspaceMemoryRollupNow" -> {
                    assistsCoreManager!!.runWorkspaceMemoryRollupNow(call, result)
                }
                "upsertWorkspaceScheduledTask" -> {
                    assistsCoreManager!!.upsertWorkspaceScheduledTask(call, result)
                }
                "deleteWorkspaceScheduledTask" -> {
                    assistsCoreManager!!.deleteWorkspaceScheduledTask(call, result)
                }
                "syncWorkspaceScheduledTasks" -> {
                    assistsCoreManager!!.syncWorkspaceScheduledTasks(call, result)
                }

                "getInstalledApplications" -> {
                    assistsCoreManager!!.getInstalledApplications( call, result)
                }
                "getInstalledApplicationsWithIconUpdate" -> {
                    assistsCoreManager!!.getInstalledApplicationsWithIconUpdate( call, result)
                }
                "listAgentExactAlarms" -> {
                    assistsCoreManager!!.listAgentExactAlarms(call, result)
                }
                "deleteAgentExactAlarm" -> {
                    assistsCoreManager!!.deleteAgentExactAlarm(call, result)
                }
                "getAlarmSettings" -> {
                    assistsCoreManager!!.getAlarmSettings(call, result)
                }
                "saveAlarmSettings" -> {
                    assistsCoreManager!!.saveAlarmSettings(call, result)
                }
                "getNanoTime"->{
                    result.success(System.nanoTime() / 1_000_000)
                }
                "copyToClipboard"->{
                    assistsCoreManager!!.copyToClipboard( call, result)
                }
                "getClipboardText"->{
                    assistsCoreManager!!.getClipboardText(call, result)
                }
                "postLLMChat"->{
                    assistsCoreManager!!.postLLMChat( call, result)
                }
                "generateMemoryGreeting" -> {
                    assistsCoreManager!!.generateMemoryGreeting(call, result)
                }
                "openAPPMarket"->{
                    assistsCoreManager!!.openAPPMarket( call, result)
                }
                "getDeskTopPackageName"->{
                    assistsCoreManager!!.getDeskTopPackageName( call, result)
                }
                "setPreventScreenSleepDuringTasksEnabled" -> {
                    assistsCoreManager!!.setPreventScreenSleepDuringTasksEnabled(call, result)
                }
                "setTaskCompletionNotificationEnabled" -> {
                    assistsCoreManager!!.setTaskCompletionNotificationEnabled(call, result)
                }
                "setVisibleChatConversation" -> {
                    assistsCoreManager!!.setVisibleChatConversation(call, result)
                }
                "showTaskCompletionNotification" -> {
                    assistsCoreManager!!.showTaskCompletionNotification(call, result)
                }
                "navigateToMainEngineRoute" -> {
                    assistsCoreManager!!.navigateToMainEngineRoute(call, result)
                }
                "reopenChatBotAfterAuth" -> {
                    assistsCoreManager!!.reopenChatBotAfterAuth(result)
                }
                "showScheduledTaskReminder" -> {
                    assistsCoreManager!!.showScheduledTaskReminder(call, result)
                }
                "hideScheduledTaskReminder" -> {
                    assistsCoreManager!!.hideScheduledTaskReminder(call, result)
                }
                "getTokenUsageRecords" -> {
                    assistsCoreManager!!.getTokenUsageRecords(call, result)
                }
                "getConversations" -> {
                    assistsCoreManager!!.getConversations(call, result)
                }
                "getConversationMessages" -> {
                    assistsCoreManager!!.getConversationMessages(call, result)
                }
                "getConversationMessagesPaged" -> {
                    assistsCoreManager!!.getConversationMessagesPaged(call, result)
                }
                "replaceConversationMessages" -> {
                    assistsCoreManager!!.replaceConversationMessages(call, result)
                }
                "upsertConversationUiCard" -> {
                    assistsCoreManager!!.upsertConversationUiCard(call, result)
                }
                "compactConversationContext" -> {
                    assistsCoreManager!!.compactConversationContext(call, result)
                }
                "clearConversationMessages" -> {
                    assistsCoreManager!!.clearConversationMessages(call, result)
                }
                "getConversationsByPage" -> {
                    assistsCoreManager!!.getConversationsByPage(call, result)
                }
                "createConversation" -> {
                    assistsCoreManager!!.createConversation(call, result)
                }
                "updateConversation" -> {
                    assistsCoreManager!!.updateConversation(call, result)
                }
                "updateConversationPromptTokenThreshold" -> {
                    assistsCoreManager!!.updateConversationPromptTokenThreshold(call, result)
                }
                "deleteConversation" -> {
                    assistsCoreManager!!.deleteConversation(call, result)
                }
                "updateConversationTitle" -> {
                    assistsCoreManager!!.updateConversationTitle(call, result)
                }
                "generateConversationSummary" -> {
                    assistsCoreManager!!.generateConversationSummary(call, result)
                }
                "completeConversation" -> {
                    assistsCoreManager!!.completeConversation(call, result)
                }
                "setCurrentConversationId" -> {
                    assistsCoreManager!!.setCurrentConversationId(call, result)
                }
                else -> result.notImplemented()
        }
    }

    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
        omniFlowToolChannel?.clear()
        omniFlowToolChannel = null
    }

}
