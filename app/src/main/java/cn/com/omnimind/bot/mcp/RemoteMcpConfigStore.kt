package cn.com.omnimind.bot.mcp

import android.content.Context
import androidx.annotation.VisibleForTesting
import cn.com.omnimind.baselib.util.OssIdentity
import cn.com.omnimind.baselib.util.SensitiveDataSanitizer
import cn.com.omnimind.baselib.util.ContentEndpointSecurity
import cn.com.omnimind.baselib.util.CredentialEndpointSecurity
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import java.net.URI
import java.util.UUID

internal data class RemoteMcpMetadataRecord(
    val id: String = "",
    val name: String = "",
    val endpointUrl: String = "",
    val hasBearerToken: Boolean = false,
    val enabled: Boolean = true,
    val lastHealth: String = RemoteMcpHealth.UNKNOWN.value,
    val lastError: String? = null,
    val toolCount: Int = 0,
    val lastSyncedAt: Long? = null,
    val generation: Long = 0L,
    val consentVersion: Int = 0,
    val consentOrigin: String = "",
    val consentRevision: Long = 0L,
) {
    fun toConfig(token: String = ""): RemoteMcpServerConfig {
        return RemoteMcpServerConfig(
            id = id,
            name = name,
            endpointUrl = endpointUrl,
            bearerToken = token,
            enabled = enabled,
            lastHealth = RemoteMcpHealth.fromValue(lastHealth),
            lastError = lastError,
            toolCount = toolCount,
            lastSyncedAt = lastSyncedAt,
            generation = generation,
            consentVersion = consentVersion,
            consentOrigin = consentOrigin,
            consentRevision = consentRevision,
        )
    }

    companion object {
        fun fromConfig(config: RemoteMcpServerConfig, hasBearerToken: Boolean): RemoteMcpMetadataRecord {
            return RemoteMcpMetadataRecord(
                id = config.id,
                name = config.name,
                endpointUrl = config.endpointUrl,
                hasBearerToken = hasBearerToken,
                enabled = config.enabled,
                lastHealth = config.lastHealth.value,
                lastError = config.lastError,
                toolCount = config.toolCount,
                lastSyncedAt = config.lastSyncedAt,
                generation = config.generation,
                consentVersion = config.consentVersion,
                consentOrigin = config.consentOrigin,
                consentRevision = config.consentRevision,
            )
        }
    }
}

internal data class RemoteMcpMigrationOutcome(
    val succeeded: Boolean,
    val metadataJson: String,
    val credentialIds: Set<String>,
)

@VisibleForTesting
internal object RemoteMcpConfigMigration {
    private const val CREDENTIAL_UNAVAILABLE_MESSAGE =
        "Secure credential storage is unavailable; re-enter the Bearer token to enable this server."
    private const val UNSAFE_ENDPOINT_MESSAGE =
        "The remote MCP endpoint is unsafe and must be reconfigured."
    private val gson = Gson()
    private val legacyListType = object : TypeToken<List<LegacyRemoteMcpRecord>>() {}.type
    private val metadataListType = object : TypeToken<List<RemoteMcpMetadataRecord>>() {}.type

    fun migrate(
        rawCandidates: List<String>,
        persistToken: (serverId: String, token: String) -> Boolean,
    ): RemoteMcpMigrationOutcome {
        val items = decodeFirstUsable(rawCandidates)
        val credentialIds = items.filter { it.hasCredential }.mapTo(LinkedHashSet()) { it.config.id }
        return try {
            items.forEach { item ->
                val token = item.config.bearerToken.trim()
                if (token.isNotEmpty()) {
                    check(persistToken(item.config.id, token)) {
                        "encrypted credential verification failed"
                    }
                }
            }
            RemoteMcpMigrationOutcome(
                succeeded = true,
                metadataJson = encodeMetadata(
                    items.map { item ->
                        RemoteMcpMetadataRecord.fromConfig(
                            item.config.copy(bearerToken = ""),
                            hasBearerToken = item.hasCredential,
                        )
                    }
                ),
                credentialIds = credentialIds,
            )
        } catch (_: Exception) {
            failClosedItems(items)
        }
    }

    fun failClosed(rawCandidates: List<String>): RemoteMcpMigrationOutcome {
        return failClosedItems(decodeFirstUsable(rawCandidates))
    }

    fun decodeMetadata(raw: String?): List<RemoteMcpMetadataRecord> {
        val normalized = raw?.trim()?.takeIf(String::isNotEmpty) ?: return emptyList()
        return try {
            val decoded: List<RemoteMcpMetadataRecord> =
                gson.fromJson(normalized, metadataListType) ?: emptyList()
            decoded.mapNotNull(::normalizeRecord)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encodeMetadata(records: List<RemoteMcpMetadataRecord>): String {
        return gson.toJson(records.mapNotNull(::normalizeRecord))
    }

    fun containsPlaintextCredentialField(raw: String?): Boolean {
        val normalized = raw?.trim()?.takeIf(String::isNotEmpty) ?: return false
        return try {
            val root = JsonParser.parseString(normalized)
            root.isJsonArray && root.asJsonArray.any { element ->
                element.isJsonObject && element.asJsonObject.has("bearerToken")
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun failClosedItems(items: List<MigrationItem>): RemoteMcpMigrationOutcome {
        val credentialIds = items.filter { it.hasCredential }.mapTo(LinkedHashSet()) { it.config.id }
        val records = items.map { item ->
            val config = if (item.hasCredential) {
                item.config.copy(
                    bearerToken = "",
                    enabled = false,
                    lastHealth = RemoteMcpHealth.ERROR,
                    lastError = CREDENTIAL_UNAVAILABLE_MESSAGE,
                )
            } else {
                item.config.copy(bearerToken = "")
            }
            RemoteMcpMetadataRecord.fromConfig(config, hasBearerToken = false)
        }
        return RemoteMcpMigrationOutcome(
            succeeded = false,
            metadataJson = encodeMetadata(records),
            credentialIds = credentialIds,
        )
    }

    private fun decodeFirstUsable(rawCandidates: List<String>): List<MigrationItem> {
        rawCandidates.asSequence().map(String::trim).filter(String::isNotEmpty).forEach { raw ->
            if (!containsPlaintextCredentialField(raw) && raw.contains("\"hasBearerToken\"")) {
                val metadata = decodeMetadata(raw)
                if (metadata.isNotEmpty() || raw == "[]") {
                    return metadata.map { record ->
                        MigrationItem(record.toConfig(), record.hasBearerToken)
                    }
                }
            }
            val legacy = try {
                val decoded: List<LegacyRemoteMcpRecord> =
                    gson.fromJson(raw, legacyListType) ?: emptyList()
                decoded.map { record ->
                    sanitizeLegacyConfig(RemoteMcpServerConfig(
                        id = record.id?.trim().orEmpty().ifEmpty { UUID.randomUUID().toString() },
                        name = record.name?.trim().orEmpty(),
                        endpointUrl = record.endpointUrl?.trim().orEmpty(),
                        bearerToken = record.bearerToken?.trim().orEmpty(),
                        enabled = record.enabled ?: true,
                        lastHealth = RemoteMcpHealth.entries.firstOrNull { health ->
                            health.value.equals(record.lastHealth, ignoreCase = true) ||
                                health.name.equals(record.lastHealth, ignoreCase = true)
                        } ?: RemoteMcpHealth.UNKNOWN,
                        lastError = record.lastError?.trim()?.takeIf(String::isNotEmpty),
                        toolCount = (record.toolCount ?: 0).coerceAtLeast(0),
                        lastSyncedAt = record.lastSyncedAt,
                    ))
                }
            } catch (_: Exception) {
                null
            }
            if (legacy != null) {
                return legacy.map { config ->
                    MigrationItem(config, config.bearerToken.isNotBlank())
                }
            }
        }
        return emptyList()
    }

    private fun normalizeRecord(record: RemoteMcpMetadataRecord): RemoteMcpMetadataRecord? {
        val id = record.id.trim().takeIf(String::isNotEmpty) ?: return null
        val normalized = record.copy(
            id = id,
            name = record.name.trim(),
            endpointUrl = record.endpointUrl.trim(),
            lastHealth = RemoteMcpHealth.fromValue(record.lastHealth).value,
            lastError = record.lastError?.trim()?.takeIf(String::isNotEmpty),
            toolCount = record.toolCount.coerceAtLeast(0),
        )
        val safe = try {
            ContentEndpointSecurity.requireSafe(
                rawUrl = normalized.endpointUrl,
                allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
            )
            true
        } catch (_: Exception) {
            false
        }
        return if (safe) normalized else normalized.copy(
            endpointUrl = "",
            hasBearerToken = false,
            enabled = false,
            lastHealth = RemoteMcpHealth.ERROR.value,
            lastError = UNSAFE_ENDPOINT_MESSAGE,
        )
    }

    private fun normalizeConfig(config: RemoteMcpServerConfig): RemoteMcpServerConfig {
        return config.copy(
            id = config.id.trim().ifEmpty { UUID.randomUUID().toString() },
            name = config.name.trim(),
            endpointUrl = config.endpointUrl.trim(),
            bearerToken = config.bearerToken.trim(),
            lastError = config.lastError?.trim()?.takeIf(String::isNotEmpty),
            toolCount = config.toolCount.coerceAtLeast(0),
        )
    }

    private fun sanitizeLegacyConfig(config: RemoteMcpServerConfig): RemoteMcpServerConfig {
        val normalized = normalizeConfig(config)
        val safe = try {
            ContentEndpointSecurity.requireSafe(
                rawUrl = normalized.endpointUrl,
                allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
            )
            true
        } catch (_: Exception) {
            false
        }
        return if (safe) normalized else normalized.copy(
            endpointUrl = "",
            bearerToken = "",
            enabled = false,
            lastHealth = RemoteMcpHealth.ERROR,
            lastError = UNSAFE_ENDPOINT_MESSAGE,
        )
    }

    private data class MigrationItem(
        val config: RemoteMcpServerConfig,
        val hasCredential: Boolean,
    )

    private data class LegacyRemoteMcpRecord(
        val id: String? = null,
        val name: String? = null,
        val endpointUrl: String? = null,
        val bearerToken: String? = null,
        val enabled: Boolean? = null,
        val lastHealth: String? = null,
        val lastError: String? = null,
        val toolCount: Int? = null,
        val lastSyncedAt: Long? = null,
    )
}

object RemoteMcpConfigStore {
    private const val CURRENT_CONSENT_VERSION = 1
    private const val GLOBAL_KEY = "remote_mcp_servers"
    private const val FLATTEN_MIGRATION_DONE_KEY = "remote_mcp_servers_flattened_v1"
    private const val ENCRYPTED_MIGRATION_DONE_KEY = "remote_mcp_credentials_encrypted_v1"
    private const val ENCRYPTED_MIGRATION_FAILED_KEY = "remote_mcp_credentials_failed_v1"
    private const val LEGACY_KEY_PREFIX = "remote_mcp_servers_"
    private const val CREDENTIAL_UNAVAILABLE_MESSAGE =
        "Secure credential storage is unavailable; re-enter the Bearer token to enable this server."

    private val gson = Gson()

    @Volatile
    private var initialized = false

    @Volatile
    private var credentialStore: RemoteMcpCredentialStore? = null

    private val mmkv: MMKV?
        get() = MMKV.defaultMMKV()

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val kv = mmkv
        if (kv == null) {
            initialized = true
            return
        }

        val rawCandidates = plaintextCandidates(kv)
        val encryptedStore = try {
            EncryptedRemoteMcpCredentialStore(context).also(RemoteMcpCredentialStore::verifyAvailable)
        } catch (_: Exception) {
            null
        }

        if (encryptedStore == null) {
            persistFailClosedMigration(kv, RemoteMcpConfigMigration.failClosed(rawCandidates))
            initialized = true
            return
        }

        val alreadyMigrated = kv.decodeBool(ENCRYPTED_MIGRATION_DONE_KEY, false) &&
            !RemoteMcpConfigMigration.containsPlaintextCredentialField(kv.decodeString(GLOBAL_KEY)) &&
            legacyKeys(kv).isEmpty()
        if (alreadyMigrated) {
            val existingRaw = kv.decodeString(GLOBAL_KEY)
            val sanitizedMetadata = RemoteMcpConfigMigration.encodeMetadata(
                RemoteMcpConfigMigration.decodeMetadata(existingRaw),
            )
            if (existingRaw != sanitizedMetadata) {
                persistMetadata(kv, sanitizedMetadata)
            }
            credentialStore = encryptedStore
            initialized = true
            return
        }

        val outcome = RemoteMcpConfigMigration.migrate(rawCandidates) { serverId, token ->
            encryptedStore.writeToken(serverId, token)
            encryptedStore.readToken(serverId) == token
        }
        if (outcome.succeeded && persistMetadata(kv, outcome.metadataJson)) {
            removeLegacyPlaintext(kv)
            kv.encode(ENCRYPTED_MIGRATION_DONE_KEY, true)
            kv.removeValueForKey(ENCRYPTED_MIGRATION_FAILED_KEY)
            encryptedStore.deleteTokensExcept(outcome.credentialIds)
            credentialStore = encryptedStore
        } else {
            outcome.credentialIds.forEach { id ->
                try {
                    encryptedStore.deleteToken(id)
                } catch (_: Exception) {
                    // Continue scrubbing every migrated credential.
                }
            }
            persistFailClosedMigration(
                kv,
                RemoteMcpConfigMigration.failClosed(rawCandidates),
            )
        }
        initialized = true
    }

    @Synchronized
    fun listServers(): List<RemoteMcpServerConfig> {
        if (!initialized) return emptyList()
        val records = RemoteMcpConfigMigration.decodeMetadata(mmkv?.decodeString(GLOBAL_KEY))
        return records.map(::hydrateRecord)
    }

    fun getServer(serverId: String): RemoteMcpServerConfig? {
        return listServers().firstOrNull { it.id == serverId }
    }

    fun listEnabledServers(): List<RemoteMcpServerConfig> {
        return listServers().filter { it.enabled && hasCurrentConsent(it) }
    }

    @Synchronized
    fun upsertServer(
        config: RemoteMcpServerConfig,
        destinationConfirmed: Boolean = false,
    ): RemoteMcpServerConfig {
        requireSecureStorage()
        val normalized = normalize(config)
        val current = listServers().toMutableList()
        val index = current.indexOfFirst { it.id == normalized.id }
        var merged = if (index >= 0) {
            val retainedToken = resolveBearerTokenForUpdate(
                existing = current[index].bearerToken,
                replacement = normalized.bearerToken,
                clear = normalized.clearBearerToken,
            )
            normalized.copy(
                bearerToken = retainedToken,
                clearBearerToken = false,
                generation = current[index].generation + 1L,
                lastHealth = normalized.lastHealth.takeIf { it != RemoteMcpHealth.UNKNOWN }
                    ?: current[index].lastHealth,
                lastError = normalized.lastError ?: current[index].lastError,
                toolCount = if (normalized.toolCount > 0) normalized.toolCount else current[index].toolCount,
                lastSyncedAt = normalized.lastSyncedAt ?: current[index].lastSyncedAt,
            )
        } else {
            normalized.copy(
                bearerToken = normalized.bearerToken.takeUnless {
                    normalized.clearBearerToken
                }.orEmpty(),
                clearBearerToken = false,
                generation = 1L,
            )
        }
        merged = if (destinationConfirmed) {
            merged.copy(
                consentVersion = CURRENT_CONSENT_VERSION,
                consentOrigin = canonicalOrigin(merged.endpointUrl),
                consentRevision = merged.generation,
            )
        } else {
            merged.copy(
                enabled = false,
                consentVersion = 0,
                consentOrigin = "",
                consentRevision = 0L,
            )
        }
        if (index >= 0) current[index] = merged else current.add(merged)
        saveServers(current)
        return merged
    }

    @VisibleForTesting
    internal fun resolveBearerTokenForUpdate(
        existing: String,
        replacement: String,
        clear: Boolean,
    ): String = when {
        replacement.isNotBlank() -> replacement.trim()
        clear -> ""
        else -> existing
    }

    @Synchronized
    fun deleteServer(serverId: String) {
        val store = requireSecureStorage()
        val current = listServers().filterNot { it.id == serverId }
        saveServers(current)
        store.deleteToken(serverId)
    }

    @Synchronized
    fun setServerEnabled(
        serverId: String,
        enabled: Boolean,
        expectedEndpointUrl: String,
        expectedGeneration: Long,
        destinationConfirmed: Boolean = false,
    ): RemoteMcpServerConfig? {
        requireSecureStorage()
        val current = listServers().toMutableList()
        val index = current.indexOfFirst { it.id == serverId }
        if (index < 0) return null
        checkMatchesExpected(current[index], expectedEndpointUrl, expectedGeneration)
        if (enabled && current[index].lastError == CREDENTIAL_UNAVAILABLE_MESSAGE) {
            return current[index]
        }
        val nextGeneration = current[index].generation + 1L
        val updated = current[index].copy(
            enabled = enabled,
            generation = nextGeneration,
            consentVersion = if (enabled && destinationConfirmed) CURRENT_CONSENT_VERSION else current[index].consentVersion,
            consentOrigin = if (enabled && destinationConfirmed) canonicalOrigin(current[index].endpointUrl) else current[index].consentOrigin,
            consentRevision = if (enabled && destinationConfirmed) nextGeneration else current[index].consentRevision,
        )
        current[index] = updated
        saveServers(current)
        return updated
    }

    @Synchronized
    fun confirmServerDestination(
        serverId: String,
        expectedEndpointUrl: String,
        expectedGeneration: Long,
    ): RemoteMcpServerConfig {
        requireSecureStorage()
        val current = listServers().toMutableList()
        val index = current.indexOfFirst { it.id == serverId }
        require(index >= 0) { "Remote MCP server not found" }
        checkMatchesExpected(current[index], expectedEndpointUrl, expectedGeneration)
        val nextGeneration = current[index].generation + 1L
        val updated = current[index].copy(
            generation = nextGeneration,
            consentVersion = CURRENT_CONSENT_VERSION,
            consentOrigin = canonicalOrigin(current[index].endpointUrl),
            consentRevision = nextGeneration,
        )
        current[index] = updated
        saveServers(current)
        return updated
    }

    @Synchronized
    fun updateDiscoveryStatus(
        serverId: String,
        expectedEndpointUrl: String,
        expectedGeneration: Long,
        health: RemoteMcpHealth,
        toolCount: Int,
        lastError: String?,
        lastSyncedAt: Long = System.currentTimeMillis(),
    ): RemoteMcpServerConfig? {
        requireSecureStorage()
        val current = listServers().toMutableList()
        val index = current.indexOfFirst { it.id == serverId }
        if (index < 0) return null
        checkMatchesExpected(current[index], expectedEndpointUrl, expectedGeneration)
        val updated = current[index].copy(
            lastHealth = health,
            toolCount = toolCount.coerceAtLeast(0),
            lastError = sanitizeStoredError(lastError),
            lastSyncedAt = lastSyncedAt,
        )
        current[index] = updated
        saveServers(current)
        return updated
    }

    @VisibleForTesting
    internal fun canonicalEndpointForCas(rawUrl: String): String {
        val safe = ContentEndpointSecurity.requireSafe(
            rawUrl = rawUrl,
            allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
        )
        val uri = URI(safe).normalize()
        val scheme = uri.scheme.lowercase()
        val host = uri.host.lowercase()
        val port = if (uri.port >= 0) uri.port else if (scheme == "https" || scheme == "wss") 443 else 80
        val path = uri.rawPath?.takeIf(String::isNotEmpty) ?: "/"
        return buildString {
            append(scheme).append("://")
            if (host.contains(':')) append('[').append(host).append(']') else append(host)
            append(':').append(port).append(path)
            uri.rawQuery?.let { append('?').append(it) }
        }
    }

    @VisibleForTesting
    internal fun canonicalOrigin(rawUrl: String): String {
        val uri = URI(canonicalEndpointForCas(rawUrl))
        val host = uri.host.lowercase()
        return buildString {
            append(uri.scheme.lowercase()).append("://")
            if (host.contains(':')) append('[').append(host).append(']') else append(host)
            append(':').append(uri.port)
        }
    }

    @VisibleForTesting
    internal fun hasCurrentConsent(config: RemoteMcpServerConfig): Boolean = try {
            config.consentVersion == CURRENT_CONSENT_VERSION &&
                config.consentRevision == config.generation &&
                config.consentOrigin == canonicalOrigin(config.endpointUrl)
        } catch (_: Exception) {
            false
        }

    private fun checkMatchesExpected(
        current: RemoteMcpServerConfig,
        expectedEndpointUrl: String,
        expectedGeneration: Long,
    ) {
        check(matchesExpected(current, expectedEndpointUrl, expectedGeneration)) {
            "Remote MCP configuration or endpoint changed after confirmation"
        }
    }

    @VisibleForTesting
    internal fun matchesExpected(
        current: RemoteMcpServerConfig,
        expectedEndpointUrl: String,
        expectedGeneration: Long,
    ): Boolean = try {
        expectedGeneration == current.generation &&
            canonicalEndpointForCas(expectedEndpointUrl) ==
            canonicalEndpointForCas(current.endpointUrl)
    } catch (_: Exception) {
        false
    }

    private fun hydrateRecord(record: RemoteMcpMetadataRecord): RemoteMcpServerConfig {
        val metadataConfig = record.toConfig()
        val consentSafeConfig = if (hasCurrentConsent(metadataConfig)) {
            metadataConfig
        } else {
            metadataConfig.copy(enabled = false)
        }
        if (!record.hasBearerToken) return consentSafeConfig
        val token = try {
            credentialStore?.readToken(record.id)
        } catch (_: Exception) {
            null
        }
            ?.takeIf(String::isNotBlank)
        if (token == null) {
            return consentSafeConfig.copy(
                enabled = false,
                lastHealth = RemoteMcpHealth.ERROR,
                lastError = CREDENTIAL_UNAVAILABLE_MESSAGE,
            )
        }
        return consentSafeConfig.copy(bearerToken = token)
    }

    private fun normalize(config: RemoteMcpServerConfig): RemoteMcpServerConfig {
        val normalized = config.copy(
            id = config.id.trim().ifEmpty { UUID.randomUUID().toString() },
            name = config.name.trim(),
            endpointUrl = config.endpointUrl.trim(),
            bearerToken = config.bearerToken.trim(),
            lastError = sanitizeStoredError(config.lastError),
            toolCount = config.toolCount.coerceAtLeast(0),
        )
        ContentEndpointSecurity.requireSafe(
            rawUrl = normalized.endpointUrl,
            allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
        )
        return normalized
    }

    private fun saveServers(servers: List<RemoteMcpServerConfig>) {
        val kv = mmkv ?: error("Remote MCP metadata storage is unavailable")
        val store = requireSecureStorage()
        val normalized = servers.map(::normalize)
        val previousMetadata = kv.decodeString(GLOBAL_KEY)
        val previousIds = RemoteMcpConfigMigration.decodeMetadata(previousMetadata)
            .mapTo(LinkedHashSet()) { it.id }
        val touchedIds = previousIds + normalized.map { it.id }
        val previousTokens = touchedIds.associateWith(store::readToken)
        try {
            normalized.forEach { config ->
                if (config.bearerToken.isBlank()) {
                    store.deleteToken(config.id)
                } else {
                    store.writeToken(config.id, config.bearerToken)
                    check(store.readToken(config.id) == config.bearerToken) {
                        "failed to verify encrypted remote MCP credential"
                    }
                }
            }
            val records = normalized.map { config ->
                RemoteMcpMetadataRecord.fromConfig(
                    config.copy(bearerToken = ""),
                    hasBearerToken = config.bearerToken.isNotBlank(),
                )
            }
            check(persistMetadata(kv, RemoteMcpConfigMigration.encodeMetadata(records))) {
                "failed to store remote MCP metadata"
            }
            store.deleteTokensExcept(
                normalized.filter { it.bearerToken.isNotBlank() }.mapTo(HashSet()) { it.id }
            )
        } catch (failure: Exception) {
            val rolledBack = try {
                touchedIds.forEach { id ->
                    val previous = previousTokens[id]
                    if (previous.isNullOrBlank()) {
                        store.deleteToken(id)
                    } else {
                        store.writeToken(id, previous)
                    }
                }
                touchedIds.all { id -> store.readToken(id) == previousTokens[id] }
            } catch (_: Exception) {
                false
            }
            if (rolledBack) {
                if (previousMetadata == null) {
                    kv.removeValueForKey(GLOBAL_KEY)
                } else if (!persistMetadata(kv, previousMetadata)) {
                    kv.removeValueForKey(GLOBAL_KEY)
                }
            } else {
                // Never leave a newly written secret bound to old metadata.
                kv.removeValueForKey(GLOBAL_KEY)
            }
            throw failure
        }
    }

    private fun requireSecureStorage(): RemoteMcpCredentialStore {
        check(initialized && credentialStore != null) {
            "Secure remote MCP credential storage is unavailable"
        }
        return credentialStore!!
    }

    private fun plaintextCandidates(kv: MMKV): List<String> {
        val values = mutableListOf<String>()
        kv.decodeString(GLOBAL_KEY)?.takeIf(String::isNotBlank)?.let(values::add)
        val currentUserKey = LEGACY_KEY_PREFIX + (OssIdentity.currentUserIdOrNull() ?: "guest")
        kv.decodeString(currentUserKey)?.takeIf(String::isNotBlank)?.let(values::add)
        legacyKeys(kv).filterNot { it == currentUserKey }.forEach { key ->
            kv.decodeString(key)?.takeIf(String::isNotBlank)?.let(values::add)
        }
        return values
    }

    private fun legacyKeys(kv: MMKV): List<String> {
        return kv.allKeys()?.filter { key ->
            key.startsWith(LEGACY_KEY_PREFIX) &&
                key != FLATTEN_MIGRATION_DONE_KEY &&
                key != ENCRYPTED_MIGRATION_DONE_KEY &&
                key != ENCRYPTED_MIGRATION_FAILED_KEY
        }.orEmpty()
    }

    private fun removeLegacyPlaintext(kv: MMKV) {
        legacyKeys(kv).forEach(kv::removeValueForKey)
        kv.encode(FLATTEN_MIGRATION_DONE_KEY, true)
    }

    private fun persistFailClosedMigration(kv: MMKV, outcome: RemoteMcpMigrationOutcome) {
        if (!persistMetadata(kv, outcome.metadataJson)) {
            kv.removeValueForKey(GLOBAL_KEY)
        }
        removeLegacyPlaintext(kv)
        kv.removeValueForKey(ENCRYPTED_MIGRATION_DONE_KEY)
        kv.encode(ENCRYPTED_MIGRATION_FAILED_KEY, true)
        credentialStore = null
    }

    private fun persistMetadata(kv: MMKV, metadataJson: String): Boolean {
        if (RemoteMcpConfigMigration.containsPlaintextCredentialField(metadataJson)) return false
        if (!kv.encode(GLOBAL_KEY, metadataJson)) return false
        val stored = kv.decodeString(GLOBAL_KEY)
        return stored == metadataJson &&
            !RemoteMcpConfigMigration.containsPlaintextCredentialField(stored)
    }

    private fun sanitizeStoredError(raw: String?): String? {
        return raw?.let { SensitiveDataSanitizer.sanitize(it, maxChars = 512) }
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }
}
