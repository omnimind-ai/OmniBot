package cn.com.omnimind.bot.ui.channel

import cn.com.omnimind.baselib.permission.LocalNetworkPermission

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.App
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import cn.com.omnimind.bot.mcp.RemoteMcpConfigStore
import cn.com.omnimind.bot.mcp.RemoteMcpDiscoveryRegistry
import cn.com.omnimind.bot.mcp.RemoteMcpServerConfig
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
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
                        "listToolCatalog" -> {
                            val rows = coroutineScope {
                                RemoteMcpConfigStore.listServers().map { config -> async {
                                    val discovered = if (!config.enabled) null else try {
                                        withTimeoutOrNull(10_000) {
                                            RemoteMcpDiscoveryRegistry.discoverServer(config)
                                        }
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (_: Exception) { null }
                                    // Only return presentation metadata, never credentials or headers.
                                    mapOf(
                                        "source" to config.name,
                                        "status" to when {
                                            !config.enabled -> "disabled"
                                            discovered == null -> "unavailable"
                                            else -> "discovered"
                                        },
                                        "tools" to discovered?.tools.orEmpty().map { tool ->
                                            mapOf(
                                                "name" to tool.encodedToolName,
                                                "displayName" to tool.toolName,
                                                "description" to tool.description,
                                                "group" to "mcp",
                                                "source" to config.name,
                                                "status" to "discovered",
                                            )
                                        },
                                    )
                                } }.awaitAll()
                            }
                            respondSuccess(result, rows)
                        }
                        "listServers" -> {
                            respondSuccess(result, RemoteMcpConfigStore.listServers().map { it.toMap() })
                        }
                        "upsertServer" -> {
                            val raw = call.arguments<Map<String, Any?>>() ?: emptyMap()
                            val config = RemoteMcpServerConfig.fromMap(raw)
                            if (config.enabled) {
                                LocalNetworkPermission.requestForEndpoint(
                                    appContext ?: App.instance, config.endpointUrl,
                                )
                            }
                            val saved = RemoteMcpConfigStore.upsertServer(config)
                            RemoteMcpDiscoveryRegistry.invalidate(saved.id)
                            invalidateAcpMcpSessions()
                            respondSuccess(result, saved.toMap())
                        }
                        "deleteServer" -> {
                            val serverId = call.argument<String>("id").orEmpty()
                            RemoteMcpConfigStore.deleteServer(serverId)
                            RemoteMcpDiscoveryRegistry.invalidate(serverId)
                            invalidateAcpMcpSessions()
                            respondSuccess(result, true)
                        }
                        "setServerEnabled" -> {
                            val serverId = call.argument<String>("id").orEmpty()
                            val enabled = call.argument<Boolean>("enabled") == true
                            if (enabled) {
                                RemoteMcpConfigStore.getServer(serverId)?.let { config ->
                                    LocalNetworkPermission.requestForEndpoint(
                                        appContext ?: App.instance, config.endpointUrl,
                                    )
                                }
                            }
                            val updated = RemoteMcpConfigStore.setServerEnabled(serverId, enabled)
                            RemoteMcpDiscoveryRegistry.invalidate(serverId)
                            invalidateAcpMcpSessions()
                            respondSuccess(result, updated?.toMap())
                        }
                        "refreshServerTools" -> {
                            val serverId = call.argument<String>("id").orEmpty()
                            val config = RemoteMcpConfigStore.getServer(serverId)
                                ?: throw IllegalArgumentException("Server not found")
                            LocalNetworkPermission.requestForEndpoint(
                                appContext ?: App.instance, config.endpointUrl,
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

    private suspend fun invalidateAcpMcpSessions() {
        val context = appContext ?: App.instance.applicationContext
        AgentRuntimeManager.getInstance(context).invalidateMcpConfiguration()
    }
}
