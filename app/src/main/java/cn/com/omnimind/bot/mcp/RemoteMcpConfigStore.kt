package cn.com.omnimind.bot.mcp

import cn.com.omnimind.baselib.util.OssIdentity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV

object RemoteMcpConfigStore {
    private const val GLOBAL_KEY = "remote_mcp_servers"
    private const val MIGRATION_DONE_KEY = "remote_mcp_servers_flattened_v1"
    private const val LEGACY_KEY_PREFIX = "remote_mcp_servers_"
    private val gson = Gson()
    private val mmkv: MMKV?
        get() = MMKV.defaultMMKV()
    private val persistedListType = object : TypeToken<List<Map<String, Any?>>>() {}.type

    fun listServers(): List<RemoteMcpServerConfig> {
        ensureMigrated()
        val saved = mmkv?.decodeString(GLOBAL_KEY) ?: return emptyList()
        return decodeServersJson(saved)
    }

    fun getServer(serverId: String): RemoteMcpServerConfig? {
        return listServers().firstOrNull { it.id == serverId }
    }

    fun listEnabledServers(): List<RemoteMcpServerConfig> {
        return listServers().filter { it.enabled }
    }

    fun upsertServer(config: RemoteMcpServerConfig): RemoteMcpServerConfig {
        val normalized = normalize(config)
        val current = listServers().toMutableList()
        val index = current.indexOfFirst { it.id == normalized.id }
        val merged = if (index >= 0) {
            normalized.copy(
                lastHealth = normalized.lastHealth.takeIf { it != RemoteMcpHealth.UNKNOWN }
                    ?: current[index].lastHealth,
                lastError = normalized.lastError ?: current[index].lastError,
                toolCount = if (normalized.toolCount > 0) normalized.toolCount else current[index].toolCount,
                lastSyncedAt = normalized.lastSyncedAt ?: current[index].lastSyncedAt
            )
        } else {
            normalized
        }

        if (index >= 0) {
            current[index] = merged
        } else {
            current.add(merged)
        }
        saveServers(current)
        return merged
    }

    fun deleteServer(serverId: String) {
        val current = listServers().filterNot { it.id == serverId }
        saveServers(current)
    }

    fun setServerEnabled(serverId: String, enabled: Boolean): RemoteMcpServerConfig? {
        val current = listServers().toMutableList()
        val index = current.indexOfFirst { it.id == serverId }
        if (index < 0) return null
        val updated = current[index].copy(enabled = enabled)
        current[index] = updated
        saveServers(current)
        return updated
    }

    fun updateDiscoveryStatus(
        serverId: String,
        health: RemoteMcpHealth,
        toolCount: Int,
        lastError: String?,
        lastSyncedAt: Long = System.currentTimeMillis()
    ): RemoteMcpServerConfig? {
        val current = listServers().toMutableList()
        val index = current.indexOfFirst { it.id == serverId }
        if (index < 0) return null
        val updated = current[index].copy(
            lastHealth = health,
            toolCount = toolCount,
            lastError = lastError?.trim()?.takeIf { it.isNotEmpty() },
            lastSyncedAt = lastSyncedAt
        )
        current[index] = updated
        saveServers(current)
        return updated
    }

    private fun normalize(config: RemoteMcpServerConfig): RemoteMcpServerConfig {
        return config.copy(
            id = config.id.ifBlank { java.util.UUID.randomUUID().toString() },
            name = config.name.trim(),
            endpointUrl = config.endpointUrl.trim(),
            bearerToken = config.bearerToken.trim(),
            lastError = config.lastError?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    private fun saveServers(servers: List<RemoteMcpServerConfig>) {
        mmkv?.encode(GLOBAL_KEY, gson.toJson(servers))
    }

    /**
     * Persisted entries predate some fields on [RemoteMcpServerConfig]. Gson
     * allocates Kotlin classes without invoking their default constructor, so
     * decoding directly into the data class can leave non-null fields null at
     * runtime. Rebuild every entry through the compatibility boundary instead.
     */
    internal fun decodeServersJson(saved: String): List<RemoteMcpServerConfig> {
        return runCatching {
            val persisted = gson.fromJson<List<Map<String, Any?>>>(saved, persistedListType)
                ?: emptyList()
            persisted.map(RemoteMcpServerConfig::fromMap)
        }.getOrDefault(emptyList())
    }

    private fun ensureMigrated() {
        val kv = mmkv ?: return
        if (kv.decodeBool(MIGRATION_DONE_KEY, false)) {
            return
        }

        try {
            val hasGlobal = !kv.decodeString(GLOBAL_KEY).isNullOrBlank()
            if (!hasGlobal) {
                val userId = OssIdentity.currentUserIdOrNull() ?: "guest"
                val legacyValue = kv.decodeString(LEGACY_KEY_PREFIX + userId)
                if (!legacyValue.isNullOrBlank()) {
                    kv.encode(GLOBAL_KEY, legacyValue)
                }
            }
        } finally {
            kv.encode(MIGRATION_DONE_KEY, true)
        }
    }
}
