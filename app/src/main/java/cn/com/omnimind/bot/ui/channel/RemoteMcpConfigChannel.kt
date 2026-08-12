package cn.com.omnimind.bot.ui.channel

import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.mcp.RemoteMcpConfigStore
import cn.com.omnimind.bot.mcp.RemoteMcpDiscoveryRegistry
import cn.com.omnimind.bot.mcp.RemoteMcpServerConfig
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RemoteMcpConfigChannel {
    private val channelName = "cn.com.omnimind.bot/RemoteMcpConfig"
    private val scope = CoroutineScope(Dispatchers.IO)
    private var channel: MethodChannel? = null

    fun onCreate() = Unit

    fun setChannel(flutterEngine: FlutterEngine) {
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
        channel?.setMethodCallHandler { call, result ->
            scope.launch {
                try {
                    when (call.method) {
                        "listServers" -> {
                            respondSuccess(result, RemoteMcpConfigStore.listServers().map { it.toMap() })
                        }
                        "upsertServer" -> {
                            val raw = call.arguments<Map<String, Any?>>() ?: emptyMap()
                            val saved = RemoteMcpConfigStore.upsertServer(
                                RemoteMcpServerConfig.fromMap(raw),
                                destinationConfirmed = raw["destinationConfirmed"] == true,
                            )
                            RemoteMcpDiscoveryRegistry.invalidate(saved.id)
                            respondSuccess(result, saved.toMap())
                        }
                        "deleteServer" -> {
                            val serverId = call.argument<String>("id").orEmpty()
                            RemoteMcpConfigStore.deleteServer(serverId)
                            RemoteMcpDiscoveryRegistry.invalidate(serverId)
                            respondSuccess(result, true)
                        }
                        "setServerEnabled" -> {
                            val serverId = call.argument<String>("id").orEmpty()
                            val enabled = call.argument<Boolean>("enabled") == true
                            val expectedEndpointUrl = call.argument<String>("expectedEndpointUrl").orEmpty()
                            val expectedGeneration = call.argument<Number>("expectedGeneration")?.toLong() ?: -1L
                            val updated = RemoteMcpConfigStore.setServerEnabled(
                                serverId,
                                enabled,
                                expectedEndpointUrl,
                                expectedGeneration,
                                destinationConfirmed = enabled && call.argument<Boolean>("destinationConfirmed") == true,
                            )
                            if (!enabled) {
                                RemoteMcpDiscoveryRegistry.invalidate(serverId)
                            }
                            respondSuccess(result, updated?.toMap())
                        }
                        "refreshServerTools" -> {
                            val serverId = call.argument<String>("id").orEmpty()
                            val expectedEndpointUrl = call.argument<String>("expectedEndpointUrl").orEmpty()
                            val expectedGeneration = call.argument<Number>("expectedGeneration")?.toLong() ?: -1L
                            check(call.argument<Boolean>("destinationConfirmed") == true) {
                                "Remote MCP destination confirmation is required"
                            }
                            val config = RemoteMcpConfigStore.confirmServerDestination(
                                serverId,
                                expectedEndpointUrl,
                                expectedGeneration,
                            )
                            val discovered = RemoteMcpDiscoveryRegistry.discoverServer(config, forceRefresh = true)
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
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    OmniLog.e(
                        "[RemoteMcpConfigChannel]",
                        "Remote MCP configuration operation failed type=${failure.javaClass.simpleName}",
                    )
                    withContext(Dispatchers.Main) {
                        result.error(
                            "REMOTE_MCP_ERROR",
                            "Remote MCP configuration is unavailable. Please try again.",
                            null,
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
