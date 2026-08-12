package cn.com.omnimind.bot.mcp

import java.util.UUID

enum class RemoteMcpHealth(val value: String) {
    UNKNOWN("unknown"),
    HEALTHY("healthy"),
    ERROR("error");

    companion object {
        fun fromValue(value: String?): RemoteMcpHealth {
            return entries.firstOrNull { it.value == value } ?: UNKNOWN
        }
    }
}

data class RemoteMcpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val endpointUrl: String,
    val bearerToken: String = "",
    val clearBearerToken: Boolean = false,
    val enabled: Boolean = true,
    val lastHealth: RemoteMcpHealth = RemoteMcpHealth.UNKNOWN,
    val lastError: String? = null,
    val toolCount: Int = 0,
    val lastSyncedAt: Long? = null,
    val generation: Long = 0L,
    val consentVersion: Int = 0,
    val consentOrigin: String = "",
    val consentRevision: Long = 0L,
) {
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "name" to name,
            "endpointUrl" to endpointUrl,
            // Credentials are write-only from Flutter. Native runtime code
            // hydrates the encrypted value only while performing a request.
            "bearerToken" to "",
            "hasBearerToken" to bearerToken.isNotBlank(),
            "enabled" to enabled,
            "lastHealth" to lastHealth.value,
            "lastError" to lastError,
            "toolCount" to toolCount,
            "lastSyncedAt" to lastSyncedAt,
            "generation" to generation,
            "consentVersion" to consentVersion,
            "consentRevision" to consentRevision,
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
            val generation = when (val value = raw["generation"]) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull() ?: 0L
                else -> 0L
            }
            val consentVersion = when (val value = raw["consentVersion"]) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull() ?: 0
                else -> 0
            }
            val consentRevision = when (val value = raw["consentRevision"]) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull() ?: 0L
                else -> 0L
            }
            return RemoteMcpServerConfig(
                id = raw["id"]?.toString()?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                name = raw["name"]?.toString()?.trim().orEmpty(),
                endpointUrl = raw["endpointUrl"]?.toString()?.trim().orEmpty(),
                bearerToken = raw["bearerToken"]?.toString() ?: "",
                clearBearerToken = raw["clearBearerToken"] == true,
                enabled = raw["enabled"] != false,
                lastHealth = RemoteMcpHealth.fromValue(raw["lastHealth"]?.toString()),
                lastError = raw["lastError"]?.toString()?.takeIf { it.isNotBlank() },
                toolCount = toolCount,
                lastSyncedAt = lastSyncedAt,
                generation = generation.coerceAtLeast(0L),
                consentVersion = consentVersion.coerceAtLeast(0),
                consentOrigin = raw["consentOrigin"]?.toString().orEmpty(),
                consentRevision = consentRevision.coerceAtLeast(0L),
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
