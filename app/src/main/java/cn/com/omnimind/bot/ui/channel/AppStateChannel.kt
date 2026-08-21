package cn.com.omnimind.bot.ui.channel

import android.content.Context
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.activity.StartupThemeResolver
import cn.com.omnimind.bot.quicklog.QuickLogWidgetUpdater
import cn.com.omnimind.bot.share.SharedOpenDraftStore
import cn.com.omnimind.bot.share.SharedOpenPreferenceStore
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * 应用状态通道 - 处理Flutter与Android应用级状态之间的通信
 */
class AppStateChannel {

    private val TAG = "AppStateChannel"
    private val CHANNEL = "cn.com.omnimind.bot/app_state"

    private var context: Context? = null
    private var methodChannel: MethodChannel? = null


    fun onCreate(context: Context) {
        this.context = context
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannel?.setMethodCallHandler { call, result ->
            handleMethodCall(call, result)
        }
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getPendingShareDraft" -> {
                val appContext = context?.applicationContext
                if (appContext == null) {
                    result.error("INVALID_CONTEXT", "Context is null", null)
                    return
                }
                result.success(SharedOpenDraftStore.getPending(appContext))
            }
            "clearPendingShareDraft" -> {
                val appContext = context?.applicationContext
                if (appContext == null) {
                    result.error("INVALID_CONTEXT", "Context is null", null)
                    return
                }
                SharedOpenDraftStore.clearPending(appContext)
                result.success(true)
            }
            "getSharedOpenMode" -> {
                val appContext = context?.applicationContext
                if (appContext == null) {
                    result.error("INVALID_CONTEXT", "Context is null", null)
                    return
                }
                result.success(SharedOpenPreferenceStore.getOpenMode(appContext))
            }
            "getSharedOpenModes" -> {
                val appContext = context?.applicationContext
                if (appContext == null) {
                    result.error("INVALID_CONTEXT", "Context is null", null)
                    return
                }
                result.success(SharedOpenPreferenceStore.getOpenModes(appContext))
            }
            "setSharedOpenMode" -> {
                val appContext = context?.applicationContext
                val mode = call.argument<String>("mode")
                val target = call.argument<String>("target")?.trim()?.lowercase()
                if (appContext == null) {
                    result.error("INVALID_CONTEXT", "Context is null", null)
                    return
                }
                val saved = when (target) {
                    "image" -> SharedOpenPreferenceStore.setImageOpenMode(appContext, mode.orEmpty())
                    "file" -> SharedOpenPreferenceStore.setFileOpenMode(appContext, mode.orEmpty())
                    else -> SharedOpenPreferenceStore.setOpenMode(appContext, mode.orEmpty())
                }
                result.success(saved)
            }
            "applyLanguagePreference" -> {
                val appContext = context?.applicationContext
                if (appContext == null) {
                    result.error("INVALID_CONTEXT", "Context is null", null)
                    return
                }
                AppLocaleManager.applyAppLocale(appContext)
                runCatching {
                    AgentWorkspaceManager(appContext).ensureRuntimeDirectories()
                }.onFailure {
                    OmniLog.w(TAG, "Failed to refresh workspace defaults after language change: ${it.message}")
                }
                runCatching {
                    QuickLogWidgetUpdater.updateAll(appContext)
                }.onFailure {
                    OmniLog.w(TAG, "Failed to refresh quick log widget language: ${it.message}")
                }
                result.success(true)
            }
            "applyThemeMode" -> {
                val appContext = context?.applicationContext
                val mode = call.argument<String>("mode")
                if (appContext == null) {
                    result.error("INVALID_CONTEXT", "Context is null", null)
                    return
                }
                if (mode == null) {
                    result.error("INVALID_ARGUMENT", "mode is required", null)
                    return
                }
                StartupThemeResolver.applyApplicationNightMode(appContext, mode)
                result.success(true)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    fun clear() {
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
    }
}
