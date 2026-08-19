package cn.com.omnimind.bot.ui.channel

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.mcp.McpServerManager
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class McpServerChannel {
    private val EVENT_CHANNEL = "cn.com.omnimind.bot/McpServer"
    private val scope = CoroutineScope(Dispatchers.IO)
    private var channel: MethodChannel? = null
    private var appContext: Context? = null

    fun onCreate(context: Context) {
        appContext = context.applicationContext
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
        channel?.setMethodCallHandler { call, result ->
            val context = appContext
            if (context == null) {
                result.error("MCP_INIT_ERROR", "Context not initialized", null)
                return@setMethodCallHandler
            }
            scope.launch {
                try {
                    when (call.method) {
                        "state" -> {
                            val state = if (McpServerManager.isPersistedEnabled()) {
                                McpServerManager.ensureRunning(context)
                            } else {
                                McpServerManager.currentState()
                            }
                            respondSuccess(result, state.toMap())
                        }
                        "setEnabled" -> {
                            val enable = call.argument<Boolean>("enable") ?: false
                            val port = call.argument<Int>("port")
                            val state = McpServerManager.setEnabled(context, enable, port)
                            respondSuccess(result, state.toMap())
                        }
                        "refreshToken" -> {
                            val state = McpServerManager.refreshToken(context)
                            respondSuccess(result, state.toMap())
                        }
                        else -> withContext(Dispatchers.Main) { result.notImplemented() }
                    }
                } catch (t: Throwable) {
                    OmniLog.e("[McpServerChannel]", "channel error: ${t.message}")
                    withContext(Dispatchers.Main) {
                        result.error("MCP_ERROR", t.message ?: t.javaClass.simpleName, null)
                    }
                }
            }
        }
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    private suspend fun respondSuccess(result: MethodChannel.Result, value: Any?) {
        withContext(Dispatchers.Main) {
            result.success(value)
        }
    }
}
