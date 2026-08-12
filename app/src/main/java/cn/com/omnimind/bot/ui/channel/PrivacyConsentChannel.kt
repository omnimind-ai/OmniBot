package cn.com.omnimind.bot.ui.channel

import android.content.Context
import cn.com.omnimind.bot.App
import cn.com.omnimind.bot.update.PrivacyConsentDecision
import cn.com.omnimind.bot.update.PrivacyConsentStore
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class PrivacyConsentChannel {
    private val channelName = "cn.com.omnimind.bot/privacy_consent"
    private var context: Context? = null
    private var channel: MethodChannel? = null

    fun onCreate(context: Context) {
        this.context = context.applicationContext
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).apply {
            setMethodCallHandler { call, result ->
                val safeContext = context
                if (safeContext == null) {
                    result.error("CONTEXT_ERROR", "Context not initialized", null)
                    return@setMethodCallHandler
                }
                when (call.method) {
                    "getDecision" -> result.success(
                        PrivacyConsentStore.getDecision(safeContext).storedValue
                    )
                    "setDecision" -> {
                        val decision = when (call.argument<String>("decision")?.trim()?.lowercase()) {
                            PrivacyConsentDecision.GRANTED.storedValue -> PrivacyConsentDecision.GRANTED
                            PrivacyConsentDecision.DECLINED.storedValue -> PrivacyConsentDecision.DECLINED
                            else -> {
                                result.error("INVALID_DECISION", "An explicit privacy decision is required", null)
                                return@setMethodCallHandler
                            }
                        }
                        PrivacyConsentStore.recordDecision(safeContext, decision)
                        (safeContext as? App)?.initSDKsAfterPrivacyConsent()
                        result.success(decision.storedValue)
                    }
                    else -> result.notImplemented()
                }
            }
        }
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
    }
}
