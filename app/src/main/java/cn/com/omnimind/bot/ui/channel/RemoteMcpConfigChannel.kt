package cn.com.omnimind.bot.ui.channel

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.App
import cn.com.omnimind.bot.mcp.RemoteMcpConfigService
import cn.com.omnimind.bot.mcp.RemoteMcpServerConfig
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RemoteMcpConfigChannel {
    private val channelName = "cn.com.omnimind.bot/RemoteMcpConfig"
    private val scope = CoroutineScope(Dispatchers.IO)
    private var channel: MethodChannel? = null
    private var appContext: Context? = null

    fun onCreate(context: Context) {
        appContext = context.applicationContext
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
        channel?.setMethodCallHandler { call, result ->
            scope.launch {
                try {
                    when (call.method) {
                        "listServers" -> {
                            respondSuccess(result, service().listServers().map { it.toMap() })
                        }
                        "upsertServer" -> {
                            val raw = call.arguments<Map<String, Any?>>() ?: emptyMap()
                            val saved = service().upsertServer(RemoteMcpServerConfig.fromMap(raw))
                            respondSuccess(result, saved.toMap())
                        }
                        "deleteServer" -> {
                            val serverId = call.argument<String>("id").orEmpty()
                            service().deleteServer(serverId)
                            respondSuccess(result, true)
                        }
                        "setServerEnabled" -> {
                            val serverId = call.argument<String>("id").orEmpty()
                            val enabled = call.argument<Boolean>("enabled") == true
                            val updated = service().setServerEnabled(serverId, enabled)
                            respondSuccess(result, updated?.toMap())
                        }
                        "refreshServerTools" -> {
                            val serverId = call.argument<String>("id").orEmpty()
                            val discovered = service().refreshServerTools(serverId)
                            respondSuccess(
                                result,
                                mapOf(
                                    "server" to discovered.config.toMap(),
                                    "tools" to discovered.tools.map { it.toPromptMap() }
                                )
                            )
                        }
                        else -> withContext(Dispatchers.Main) { result.notImplemented() }
                    }
                } catch (t: Throwable) {
                    OmniLog.e("[RemoteMcpConfigChannel]", "channel error: ${t.message}", t)
                    withContext(Dispatchers.Main) {
                        result.error("REMOTE_MCP_ERROR", t.message, null)
                    }
                }
            }
        }
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
        appContext = null
    }

    private suspend fun respondSuccess(result: MethodChannel.Result, value: Any?) {
        withContext(Dispatchers.Main) {
            result.success(value)
        }
    }

    private fun service() = RemoteMcpConfigService(appContext ?: App.instance.applicationContext)
}
