package cn.com.omnimind.bot.ui.channel

import cn.com.omnimind.bot.preferences.UiPreferencesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import cn.com.omnimind.bot.activity.StartupThemeResolver
import cn.com.omnimind.bot.share.SharedOpenDraftStore
import cn.com.omnimind.bot.share.SharedOpenPreferenceStore
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * 应用状态通道 - 处理Flutter与Android应用级状态之间的通信
 */
class AppStateChannel {

    private val CHANNEL = "cn.com.omnimind.bot/app_state"

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var context: Context? = null
    private var methodChannel: MethodChannel? = null


    fun onCreate(context: Context) {
        this.context = context
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
            "getUiPreferences", "updateUiPreferences", "applyLanguagePreference" -> {
                val appContext = context?.applicationContext
                if (appContext == null) {
                    result.error("INVALID_CONTEXT", "Context is null", null)
                    return
                }
                scope.launch {
                    try {
                        val store = UiPreferencesStore.get(appContext)
                        if (call.method == "applyLanguagePreference") {
                            store.applyLanguagePreference()
                            result.success(true)
                        } else {
                            val snapshot = if (call.method == "getUiPreferences") {
                                withContext(Dispatchers.IO) { store.read() }
                            } else when (call.argument<String>("operation")) {
                                "theme" -> store.setTheme(requireNotNull(call.argument<String>("value")))
                                "language" -> store.setLanguage(requireNotNull(call.argument<String>("value")))
                                "greeting" -> store.setGreetingEnabled(requireNotNull(call.argument<Boolean>("enabled")))
                                "savePrompt" -> store.savePrompt(call.argument<String>("id"),
                                    requireNotNull(call.argument<String>("title")), requireNotNull(call.argument<String>("prompt")))
                                "deletePrompt" -> store.deletePrompt(requireNotNull(call.argument<String>("id")))
                                "resetPrompts" -> store.resetPrompts()
                                "togglePinned" -> store.togglePinned(requireNotNull(call.argument<String>("id")))
                                else -> throw IllegalArgumentException("Unknown preference operation")
                            }
                            result.success(snapshot.toMap())
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        result.error("PREFERENCE_FAILED", "Unable to read or save preferences", null)
                    }
                }
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
        scope.cancel()
        context = null
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
    }
}
