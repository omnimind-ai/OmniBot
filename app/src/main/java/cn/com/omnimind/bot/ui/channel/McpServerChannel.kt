package cn.com.omnimind.bot.ui.channel

import android.content.Context
import cn.com.omnimind.bot.mcp.McpServerManager
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
                            respondSuccess(result, McpServerManager.currentState().toMap())
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
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    withContext(Dispatchers.Main) {
                        NativeChannelErrorPrivacy.deliver(
                            result,
                            "[McpServerChannel]",
                            "MCP_ERROR",
                            error,
                        )
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
