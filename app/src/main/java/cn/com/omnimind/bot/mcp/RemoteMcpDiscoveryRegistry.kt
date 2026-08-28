package cn.com.omnimind.bot.mcp

import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

object RemoteMcpDiscoveryRegistry {
    private const val TAG = "[RemoteMcpDiscoveryRegistry]"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    private data class CacheEntry(
        val discoveredAt: Long,
        val server: RemoteMcpDiscoveredServer
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val serverLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Return descriptors already known by this process without touching the
     * network. This is intentionally used on the ordinary prompt path.
     * Stale entries are still useful for model routing; an explicit discovery
     * refresh will reconcile them before the next model request.
     */
    fun cachedEnabledServers(): List<RemoteMcpDiscoveredServer> {
        return RemoteMcpConfigStore.listEnabledServers().mapNotNull { config ->
            cache[config.id]?.server
        }
    }

    suspend fun discoverEnabledServers(forceRefresh: Boolean = false): List<RemoteMcpDiscoveredServer> {
        return coroutineScope {
            RemoteMcpConfigStore.listEnabledServers()
                .map { config ->
                    async {
                        runCatching {
                            discoverServer(config, forceRefresh)
                        }.onFailure {
                            OmniLog.w(TAG, "discover ${config.name} failed: ${it.message}")
                        }.getOrNull()
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
    }

    suspend fun discoverServer(
        config: RemoteMcpServerConfig,
        forceRefresh: Boolean = false
    ): RemoteMcpDiscoveredServer {
        val lock = serverLocks.getOrPut(config.id) { Mutex() }
        return lock.withLock {
            val now = System.currentTimeMillis()
            val cached = cache[config.id]
            if (!forceRefresh && cached != null && now - cached.discoveredAt < CACHE_TTL_MS) {
                return@withLock cached.server
            }
            try {
                val tools = RemoteMcpClient.listTools(config)
                val updatedConfig = RemoteMcpConfigStore.updateDiscoveryStatus(
                    serverId = config.id,
                    health = RemoteMcpHealth.HEALTHY,
                    toolCount = tools.size,
                    lastError = null,
                    lastSyncedAt = now
                ) ?: config.copy(
                    lastHealth = RemoteMcpHealth.HEALTHY,
                    toolCount = tools.size,
                    lastError = null,
                    lastSyncedAt = now
                )
                val discovered = RemoteMcpDiscoveredServer(updatedConfig, tools)
                cache[config.id] = CacheEntry(now, discovered)
                discovered
            } catch (t: Throwable) {
                RemoteMcpConfigStore.updateDiscoveryStatus(
                    serverId = config.id,
                    health = RemoteMcpHealth.ERROR,
                    toolCount = 0,
                    lastError = t.message ?: "Unknown error",
                    lastSyncedAt = now
                )
                cache.remove(config.id)
                throw t
            }
        }
    }

    fun invalidate(serverId: String? = null) {
        if (serverId == null) {
            cache.clear()
            RemoteMcpClient.invalidateSession()
            return
        }
        cache.remove(serverId)
        RemoteMcpClient.invalidateSession(serverId)
    }
}
