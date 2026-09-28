package cn.com.omnimind.bot.mcp

import android.content.Context
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared mutation boundary for the native page and the Flutter compatibility channel. */
class RemoteMcpConfigService(context: Context) {
    private val appContext = context.applicationContext

    fun listServers(): List<RemoteMcpServerConfig> = RemoteMcpConfigStore.listServers()

    suspend fun upsertServer(
        config: RemoteMcpServerConfig,
        expected: RemoteMcpServerConfig? = null,
    ): RemoteMcpServerConfig = mutationLock.withLock {
        checkUnchanged(config.id, expected)
        val saved = RemoteMcpConfigStore.upsertServer(config)
        RemoteMcpDiscoveryRegistry.invalidate(saved.id)
        invalidateAcpMcpSessions()
        saved
    }

    suspend fun deleteServer(serverId: String, expected: RemoteMcpServerConfig? = null) {
        mutationLock.withLock {
            checkUnchanged(serverId, expected)
            RemoteMcpConfigStore.deleteServer(serverId)
            RemoteMcpDiscoveryRegistry.invalidate(serverId)
            invalidateAcpMcpSessions()
        }
    }

    suspend fun setServerEnabled(
        serverId: String,
        enabled: Boolean,
        expected: RemoteMcpServerConfig? = null,
    ): RemoteMcpServerConfig? = mutationLock.withLock {
        checkUnchanged(serverId, expected)
        val updated = RemoteMcpConfigStore.setServerEnabled(serverId, enabled)
        RemoteMcpDiscoveryRegistry.invalidate(serverId)
        invalidateAcpMcpSessions()
        updated
    }

    suspend fun refreshServerTools(serverId: String): RemoteMcpDiscoveredServer {
        val config = RemoteMcpConfigStore.getServer(serverId)
            ?: throw IllegalArgumentException("Server not found")
        return RemoteMcpDiscoveryRegistry.discoverServer(config, forceRefresh = true)
    }

    private fun checkUnchanged(serverId: String, expected: RemoteMcpServerConfig?) {
        if (expected == null) return
        val current = RemoteMcpConfigStore.getServer(serverId)
        if (current == null || current.configurationFields() != expected.configurationFields()) {
            throw IllegalStateException("MCP service changed elsewhere")
        }
    }

    private suspend fun invalidateAcpMcpSessions() {
        AgentRuntimeManager.getInstance(appContext).invalidateMcpConfiguration()
    }

    private fun RemoteMcpServerConfig.configurationFields() =
        listOf(name, endpointUrl, bearerToken, headers, transport, enabled)

    private companion object {
        val mutationLock = Mutex()
    }
}
