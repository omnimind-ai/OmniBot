package cn.com.omnimind.bot.mcp

import java.util.UUID

enum class RemoteMcpHealth(val value: String) {
    UNKNOWN("unknown"),
    HEALTHY("healthy"),
    ERROR("error");

    companion object {
        fun fromValue(value: String?): RemoteMcpHealth {
            return entries.firstOrNull {
                it.value.equals(value, ignoreCase = true) ||
                    it.name.equals(value, ignoreCase = true)
            } ?: UNKNOWN
        }
    }
}

enum class RemoteMcpTransport(val value: String) {
    AUTO("auto"),
    HTTP("http"),
    SSE("sse");

    companion object {
        fun fromValue(value: String?): RemoteMcpTransport {
            return entries.firstOrNull {
                it.value.equals(value, ignoreCase = true) ||
                    it.name.equals(value, ignoreCase = true)
            } ?: AUTO
        }
    }
}

data class RemoteMcpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val endpointUrl: String,
    val bearerToken: String = "",
    /** ACP session declarations may carry arbitrary HTTP headers. */
    val headers: Map<String, String> = emptyMap(),
    /** Explicit for ACP `sse`; AUTO preserves URL-based legacy discovery. */
    val transport: RemoteMcpTransport = RemoteMcpTransport.AUTO,
    val enabled: Boolean = true,
    val lastHealth: RemoteMcpHealth = RemoteMcpHealth.UNKNOWN,
    val lastError: String? = null,
    val toolCount: Int = 0,
    val lastSyncedAt: Long? = null
) {
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "name" to name,
            "endpointUrl" to endpointUrl,
            "bearerToken" to bearerToken,
            "headers" to headers,
            "transport" to transport.value,
            "enabled" to enabled,
            "lastHealth" to lastHealth.value,
            "lastError" to lastError,
            "toolCount" to toolCount,
            "lastSyncedAt" to lastSyncedAt
        )
    }

    companion object {
        fun fromMap(raw: Map<String, Any?>): RemoteMcpServerConfig {
            val toolCount = when (val value = raw["toolCount"]) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull() ?: 0
                else -> 0
            }
            val lastSyncedAt = when (val value = raw["lastSyncedAt"]) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            }
            return RemoteMcpServerConfig(
                id = raw["id"]?.toString()?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                name = raw["name"]?.toString()?.trim().orEmpty(),
                endpointUrl = raw["endpointUrl"]?.toString()?.trim().orEmpty(),
                bearerToken = raw["bearerToken"]?.toString() ?: "",
                headers = (raw["headers"] as? Map<*, *>)
                    ?.entries
                    ?.mapNotNull { (key, value) ->
                        val name = key?.toString()?.trim().orEmpty()
                        val headerValue = value?.toString() ?: return@mapNotNull null
                        name.takeIf(String::isNotEmpty)?.let { it to headerValue }
                    }
                    ?.toMap()
                    .orEmpty(),
                transport = RemoteMcpTransport.fromValue(raw["transport"]?.toString()),
                enabled = raw["enabled"] != false,
                lastHealth = RemoteMcpHealth.fromValue(raw["lastHealth"]?.toString()),
                lastError = raw["lastError"]?.toString()?.takeIf { it.isNotBlank() },
                toolCount = toolCount,
                lastSyncedAt = lastSyncedAt
            )
        }
    }
}

data class RemoteMcpToolDescriptor(
    val serverId: String,
    val serverName: String,
    val toolName: String,
    val description: String,
    val inputSchema: Map<String, Any?> = emptyMap()
) {
    val encodedToolName: String
        get() = "mcp__${serverId}__${toolName}"

    fun toPromptMap(): Map<String, Any?> {
        return mapOf(
            "name" to encodedToolName,
            "displayName" to toolName,
            "toolType" to "mcp",
            "serverName" to serverName,
            "description" to description,
            "parameters" to inputSchema
        )
    }
}

data class RemoteMcpDiscoveredServer(
    val config: RemoteMcpServerConfig,
    val tools: List<RemoteMcpToolDescriptor>
)

data class RemoteMcpCallResult(
    val summaryText: String,
    val previewJson: String,
    val rawResultJson: String,
    val success: Boolean
)
