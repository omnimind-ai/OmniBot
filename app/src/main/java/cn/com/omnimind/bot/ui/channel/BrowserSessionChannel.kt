package cn.com.omnimind.bot.ui.channel

import cn.com.omnimind.bot.agent.LiveAgentBrowserSessionManager
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BrowserSessionChannel {
    companion object {
        private const val CHANNEL = "cn.com.omnimind.bot/AgentBrowserSession"
    }

    private var methodChannel: MethodChannel? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun setChannel(flutterEngine: FlutterEngine) {
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannel?.setMethodCallHandler(::handleMethodCall)
    }

    private fun handleMethodCall(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        val arguments = (call.arguments as? Map<*, *>)
            ?.entries
            ?.associate { (key, value) -> key.toString() to value }
            .orEmpty()
        scope.launch {
            try {
                val payload = LiveAgentBrowserSessionManager.handleCurrentCall(
                    method = call.method,
                    arguments = arguments
                )
                result.success(payload)
            } catch (error: Exception) {
                NativeChannelErrorPrivacy.deliver(
                    result,
                    "BrowserSessionChannel",
                    "BROWSER_SESSION_CALL_FAILED",
                    error,
                )
            }
        }
    }

    fun clear() {
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
    }
}
