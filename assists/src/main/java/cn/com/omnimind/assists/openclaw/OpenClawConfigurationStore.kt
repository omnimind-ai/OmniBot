package cn.com.omnimind.assists.openclaw

import cn.com.omnimind.assists.api.bean.TaskParams
import android.content.Context
import cn.com.omnimind.baselib.util.AppSecretStoreBackend
import cn.com.omnimind.baselib.util.ContentEndpointSecurity
import cn.com.omnimind.baselib.util.CredentialEndpointSecurity
import cn.com.omnimind.baselib.util.SecureSecretStore
import com.google.gson.Gson
import java.net.URI
import java.security.SecureRandom

/** Public, secret-free view of the authoritative native OpenClaw configuration. */
data class OpenClawConfigurationSnapshot(
    val configured: Boolean,
    val enabled: Boolean,
    val baseUrl: String,
    val userId: String,
    val generation: Long,
    val allowedOrigin: String,
    val consentVersion: Int,
    val hasGatewayToken: Boolean,
)

data class OpenClawDestinationPlan(
    val requestId: String,
    val baseUrl: String,
    val canonicalOrigin: String,
    val expectedGeneration: Long,
)

enum class OpenClawCredentialMutation {
    KEEP,
    REPLACE,
    CLEAR,
}

enum class OpenClawConfigurationStatus {
    SUCCESS,
    INVALID_ENDPOINT,
    INVALID_ARGUMENT,
    STALE_GENERATION,
    STORAGE_UNAVAILABLE,
    ROLLBACK_FAILED,
    DENY_TOMBSTONE_PERSIST_FAILED,
    DENY_TOMBSTONE_CLEAR_FAILED,
}

data class OpenClawConfigurationMutationResult(
    val success: Boolean,
    val status: OpenClawConfigurationStatus,
    val snapshot: OpenClawConfigurationSnapshot? = null,
)

internal data class OpenClawConfigurationRecord(
    val schemaVersion: Int = OpenClawConfigurationRepository.SCHEMA_VERSION,
    val baseUrl: String = "",
    val gatewayToken: String? = null,
    val userId: String = "",
    val generation: Long = 0L,
    val enabled: Boolean = false,
    val allowedOrigin: String = "",
    val consentVersion: Int = 0,
)

internal data class CanonicalOpenClawEndpoint(
    val endpoint: String,
    val origin: String,
)

/**
 * One encrypted JSON value contains the endpoint, credential, identity selector and consent state.
 * A verified single-key write prevents secret/metadata splits. Failed writes are rolled back and
 * also trip a process-local deny latch, so a partially failed operation can never authorize I/O.
 */
internal class OpenClawConfigurationRepository(
    private val secureStore: SecureSecretStore,
    private val denyTombstoneStore: OpenClawDenyTombstoneStore,
    private val storageKey: String = CONFIGURATION_KEY,
    private val allowInsecureLoopback: () -> Boolean = { false },
    private val gson: Gson = Gson(),
) {
    @Volatile
    private var forcedDisabled = false
    private val pendingPlans = LinkedHashMap<String, OpenClawDestinationPlan>()

    @Synchronized
    fun snapshot(): OpenClawConfigurationSnapshot {
        val tombstone = readTombstoneFailClosed()
        val record = readRecordOrNull() ?: return disabledSnapshot()
        return record.toSnapshot(
            forceDisabled = forcedDisabled || tombstone.denied,
            generationOverride = maxOf(record.generation, tombstone.generation),
        )
    }

    @Synchronized
    fun prepareDestination(rawBaseUrl: String): OpenClawDestinationPlan? {
        val current = readRecordOrNull() ?: return null
        val endpoint = canonicalize(rawBaseUrl) ?: return null
        val plan = OpenClawDestinationPlan(
            requestId = newRequestId(),
            baseUrl = endpoint.endpoint,
            canonicalOrigin = endpoint.origin,
            expectedGeneration = current.generation,
        )
        pendingPlans[plan.requestId] = plan
        while (pendingPlans.size > MAX_PENDING_PLANS) {
            pendingPlans.remove(pendingPlans.keys.first())
        }
        return plan
    }

    @Synchronized
    fun saveConfirmed(
        requestId: String,
        expectedGeneration: Long,
        confirmedOrigin: String,
        rawBaseUrl: String,
        userId: String,
        credentialMutation: OpenClawCredentialMutation,
        replacementToken: String?,
        enable: Boolean,
    ): OpenClawConfigurationMutationResult {
        val plan = pendingPlans.remove(requestId)
        if (
            plan == null ||
            requestId.isBlank() ||
            requestId != plan.requestId ||
            expectedGeneration != plan.expectedGeneration
        ) {
            return failure(OpenClawConfigurationStatus.STALE_GENERATION)
        }
        val current = readRecordOrNull()
            ?: return failure(OpenClawConfigurationStatus.STORAGE_UNAVAILABLE)
        if (current.generation != expectedGeneration) {
            return failure(OpenClawConfigurationStatus.STALE_GENERATION)
        }
        val endpoint = canonicalize(rawBaseUrl)
            ?: return failure(OpenClawConfigurationStatus.INVALID_ENDPOINT)
        if (confirmedOrigin != endpoint.origin) {
            return failure(OpenClawConfigurationStatus.INVALID_ENDPOINT)
        }
        if (endpoint.endpoint != plan.baseUrl || endpoint.origin != plan.canonicalOrigin) {
            return failure(OpenClawConfigurationStatus.INVALID_ENDPOINT)
        }
        val normalizedUserId = userId.trim()
        if (normalizedUserId.length > MAX_USER_ID_LENGTH) {
            return failure(OpenClawConfigurationStatus.INVALID_ARGUMENT)
        }
        val nextToken = when (credentialMutation) {
            OpenClawCredentialMutation.KEEP -> current.gatewayToken
            OpenClawCredentialMutation.CLEAR -> null
            OpenClawCredentialMutation.REPLACE -> replacementToken
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= MAX_CREDENTIAL_LENGTH }
                ?: return failure(OpenClawConfigurationStatus.INVALID_ARGUMENT)
        }
        val next = current.copy(
            baseUrl = endpoint.endpoint,
            gatewayToken = nextToken,
            userId = normalizedUserId,
            generation = nextGeneration(current.generation),
            enabled = enable,
            allowedOrigin = endpoint.origin,
            consentVersion = CURRENT_CONSENT_VERSION,
        )
        // Close the second gate before touching the encrypted record. A crash after this point is
        // denied on the next process start even if the old encrypted record was still enabled.
        forcedDisabled = true
        if (!denyTombstoneStore.markDenied(next.generation)) {
            val disabledFallback = next.copy(
                enabled = false,
                allowedOrigin = "",
                consentVersion = 0,
            )
            persist(current, disabledFallback)
            forcedDisabled = true
            return failure(OpenClawConfigurationStatus.DENY_TOMBSTONE_PERSIST_FAILED)
        }
        val persisted = persist(current, next)
        if (!persisted.success) {
            forcedDisabled = true
            return persisted
        }
        if (!denyTombstoneStore.clearDenied(next.generation)) {
            forcedDisabled = true
            return failure(OpenClawConfigurationStatus.DENY_TOMBSTONE_CLEAR_FAILED)
        }
        forcedDisabled = false
        return OpenClawConfigurationMutationResult(
            success = true,
            status = OpenClawConfigurationStatus.SUCCESS,
            snapshot = snapshot(),
        )
    }

    /** Legacy values are retained only as inactive configuration and always require fresh consent. */
    @Synchronized
    fun migrateLegacyInactive(
        rawBaseUrl: String,
        legacyGatewayToken: String?,
        userId: String,
    ): OpenClawConfigurationMutationResult {
        // Missing tombstone state represents an unconfirmed upgrade. Persist deny before reading
        // or importing any legacy configuration.
        forcedDisabled = true
        val initialDenyPersisted = denyTombstoneStore.markDenied(0L)
        val current = readRecordOrNull()
            ?: return failure(OpenClawConfigurationStatus.STORAGE_UNAVAILABLE)
        val endpoint = rawBaseUrl.trim().takeIf(String::isNotEmpty)?.let(::canonicalize)
        val token = legacyGatewayToken
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_CREDENTIAL_LENGTH }
            ?: current.gatewayToken
        val normalizedUserId = userId.trim().take(MAX_USER_ID_LENGTH)
        val next = current.copy(
            baseUrl = endpoint?.endpoint ?: current.baseUrl,
            gatewayToken = token,
            userId = normalizedUserId.ifEmpty { current.userId },
            generation = nextGeneration(current.generation),
            enabled = false,
            allowedOrigin = "",
            consentVersion = 0,
        )
        val generationDenyPersisted = denyTombstoneStore.markDenied(next.generation)
        val denyPersisted = initialDenyPersisted && generationDenyPersisted
        val persisted = persist(current, next)
        if (!denyPersisted) {
            forcedDisabled = true
            return failure(OpenClawConfigurationStatus.DENY_TOMBSTONE_PERSIST_FAILED)
        }
        return persisted
    }

    /** First reset/disable step: persist a new generation with no authorized destination. */
    @Synchronized
    fun disableAndAdvance(): OpenClawConfigurationMutationResult {
        // This is deliberately the first persistent operation. It does not depend on Keystore
        // being readable, so an encrypted-store outage still leaves a restart-safe deny record.
        forcedDisabled = true
        val priorTombstone = readTombstoneFailClosed()
        val provisionalGeneration = nextGeneration(priorTombstone.generation)
        val provisionalDenyPersisted = denyTombstoneStore.markDenied(provisionalGeneration)
        val current = readRecordOrNull() ?: return failure(
            if (provisionalDenyPersisted) {
                OpenClawConfigurationStatus.STORAGE_UNAVAILABLE
            } else {
                OpenClawConfigurationStatus.DENY_TOMBSTONE_PERSIST_FAILED
            },
        )
        val targetGeneration = maxOf(
            nextGeneration(current.generation),
            provisionalGeneration,
        )
        val next = current.copy(
            generation = targetGeneration,
            enabled = false,
            allowedOrigin = "",
            consentVersion = 0,
        )
        val finalDenyPersisted = if (targetGeneration == provisionalGeneration) {
            provisionalDenyPersisted
        } else {
            denyTombstoneStore.markDenied(targetGeneration)
        }
        val denyPersisted = provisionalDenyPersisted && finalDenyPersisted
        val persisted = persist(current, next)
        if (!denyPersisted) {
            // The encrypted disabled record is still attempted above as an independent fallback.
            forcedDisabled = true
            return failure(OpenClawConfigurationStatus.DENY_TOMBSTONE_PERSIST_FAILED)
        }
        if (!persisted.success) forcedDisabled = true
        return persisted
    }

    @Synchronized
    fun isAuthorized(config: TaskParams.OpenClawConfig): Boolean {
        // This check intentionally precedes every encrypted read and every runtime send/open gate.
        if (forcedDisabled || readTombstoneFailClosed().denied) return false
        val current = readRecordOrNull() ?: return false
        if (
            !current.enabled ||
            current.consentVersion != CURRENT_CONSENT_VERSION ||
            current.allowedOrigin.isBlank() ||
            config.generation != current.generation ||
            config.canonicalOrigin != current.allowedOrigin ||
            config.userId.orEmpty().trim() != current.userId
        ) {
            return false
        }
        val supplied = canonicalize(config.baseUrl) ?: return false
        return supplied.endpoint == current.baseUrl && supplied.origin == current.allowedOrigin
    }

    /** Runs the final send/open operation while holding the same lock used by disable/reset. */
    @Synchronized
    fun <T> withAuthorization(config: TaskParams.OpenClawConfig, block: () -> T): T? {
        if (!isAuthorized(config)) return null
        return block()
    }

    @Synchronized
    fun resolveTaskConfig(
        expectedGeneration: Long,
        expectedOrigin: String,
        suppliedBaseUrl: String,
        suppliedUserId: String?,
        sessionKey: String?,
    ): TaskParams.OpenClawConfig? {
        if (forcedDisabled || readTombstoneFailClosed().denied) return null
        val current = readRecordOrNull() ?: return null
        val candidate = TaskParams.OpenClawConfig(
            baseUrl = suppliedBaseUrl,
            userId = suppliedUserId,
            sessionKey = sessionKey,
            generation = expectedGeneration,
            canonicalOrigin = expectedOrigin,
        )
        if (!isAuthorized(candidate)) return null
        return candidate.copy(baseUrl = current.baseUrl, userId = current.userId)
    }

    @Synchronized
    fun getGatewayToken(): String {
        if (forcedDisabled || readTombstoneFailClosed().denied) return ""
        return readRecordOrNull()?.gatewayToken.orEmpty()
    }

    @Synchronized
    fun hasGatewayToken(): Boolean = readRecordOrNull()?.gatewayToken.orEmpty().isNotBlank()

    @Synchronized
    fun clearForTesting(): Boolean {
        forcedDisabled = true
        denyTombstoneStore.markDenied(Long.MAX_VALUE)
        return try {
            secureStore.delete(storageKey)
        } catch (_: Exception) {
            false
        }
    }

    private fun persist(
        previous: OpenClawConfigurationRecord,
        next: OpenClawConfigurationRecord,
    ): OpenClawConfigurationMutationResult {
        val previousRaw = encode(previous)
        val nextRaw = encode(next)
        val written = try {
            secureStore.isAvailable() &&
                secureStore.write(storageKey, nextRaw) &&
                secureStore.read(storageKey) == nextRaw
        } catch (_: Exception) {
            false
        }
        if (written) {
            forcedDisabled = false
            return OpenClawConfigurationMutationResult(
                success = true,
                status = OpenClawConfigurationStatus.SUCCESS,
                snapshot = next.toSnapshot(forceDisabled = false),
            )
        }

        forcedDisabled = true
        val rolledBack = try {
            secureStore.isAvailable() &&
                secureStore.write(storageKey, previousRaw) &&
                secureStore.read(storageKey) == previousRaw
        } catch (_: Exception) {
            false
        }
        if (!rolledBack) {
            try {
                secureStore.delete(storageKey)
            } catch (_: Exception) {
                // The in-process deny latch remains authoritative when storage is unavailable.
            }
        }
        return failure(
            if (rolledBack) {
                OpenClawConfigurationStatus.STORAGE_UNAVAILABLE
            } else {
                OpenClawConfigurationStatus.ROLLBACK_FAILED
            },
        )
    }

    private fun readRecordOrNull(): OpenClawConfigurationRecord? {
        if (!secureStore.isAvailable()) {
            forcedDisabled = true
            return null
        }
        val raw = try {
            secureStore.read(storageKey)
        } catch (_: Exception) {
            forcedDisabled = true
            return null
        }
        if (raw == null) return OpenClawConfigurationRecord()
        val decoded = try {
            gson.fromJson(raw, OpenClawConfigurationRecord::class.java)
        } catch (_: Exception) {
            null
        }
        if (decoded == null || !isValid(decoded)) {
            forcedDisabled = true
            return null
        }
        return decoded
    }

    private fun readTombstoneFailClosed(): OpenClawDenyTombstone = try {
        denyTombstoneStore.read()
            ?: OpenClawDenyTombstone(denied = true, generation = 0L)
    } catch (_: Exception) {
        OpenClawDenyTombstone(denied = true, generation = 0L)
    }

    private fun isValid(record: OpenClawConfigurationRecord): Boolean {
        if (
            record.schemaVersion != SCHEMA_VERSION ||
            record.generation < 0L ||
            record.baseUrl.length > MAX_ENDPOINT_LENGTH ||
            record.userId.length > MAX_USER_ID_LENGTH ||
            record.allowedOrigin.length > MAX_ORIGIN_LENGTH ||
            record.gatewayToken.orEmpty().length > MAX_CREDENTIAL_LENGTH
        ) {
            return false
        }
        if (!record.enabled) return true
        if (record.consentVersion != CURRENT_CONSENT_VERSION) return false
        val endpoint = canonicalize(record.baseUrl) ?: return false
        return endpoint.endpoint == record.baseUrl && endpoint.origin == record.allowedOrigin
    }

    private fun encode(record: OpenClawConfigurationRecord): String = gson.toJson(record)

    private fun canonicalize(raw: String): CanonicalOpenClawEndpoint? =
        canonicalizeOpenClawEndpoint(raw, allowInsecureLoopback())

    private fun OpenClawConfigurationRecord.toSnapshot(
        forceDisabled: Boolean,
        generationOverride: Long = generation,
    ): OpenClawConfigurationSnapshot = OpenClawConfigurationSnapshot(
        configured = baseUrl.isNotBlank(),
        enabled = enabled && !forceDisabled,
        baseUrl = baseUrl,
        userId = userId,
        generation = generationOverride,
        allowedOrigin = if (forceDisabled) "" else allowedOrigin,
        consentVersion = if (forceDisabled) 0 else consentVersion,
        hasGatewayToken = !gatewayToken.isNullOrBlank(),
    )

    private fun disabledSnapshot(): OpenClawConfigurationSnapshot {
        val tombstoneGeneration = readTombstoneFailClosed().generation
        return OpenClawConfigurationSnapshot(
            configured = false,
            enabled = false,
            baseUrl = "",
            userId = "",
            generation = tombstoneGeneration,
            allowedOrigin = "",
            consentVersion = 0,
            hasGatewayToken = false,
        )
    }

    private fun failure(
        status: OpenClawConfigurationStatus,
    ): OpenClawConfigurationMutationResult = OpenClawConfigurationMutationResult(
        success = false,
        status = status,
        snapshot = snapshot(),
    )

    private fun nextGeneration(current: Long): Long =
        if (current == Long.MAX_VALUE) 1L else current + 1L

    private fun newRequestId(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val CURRENT_CONSENT_VERSION = 1
        const val CONFIGURATION_KEY = "openclaw.configuration.v1"
        private const val MAX_ENDPOINT_LENGTH = 4096
        private const val MAX_ORIGIN_LENGTH = 1024
        private const val MAX_USER_ID_LENGTH = 1024
        private const val MAX_CREDENTIAL_LENGTH = 64 * 1024
        private const val MAX_PENDING_PLANS = 16
    }
}

object OpenClawConfigurationStore {
    private const val LEGACY_GATEWAY_TOKEN_KEY = "openclaw.gateway_token"

    private val repository by lazy {
        OpenClawConfigurationRepository(
            secureStore = AppSecretStoreBackend,
            denyTombstoneStore = AndroidOpenClawDenyTombstoneStore,
            allowInsecureLoopback = CredentialEndpointSecurity::isDebugLoopbackAllowed,
        )
    }

    fun initialize(context: Context): Boolean =
        AndroidOpenClawDenyTombstoneStore.initialize(context)

    fun snapshot(): OpenClawConfigurationSnapshot = repository.snapshot()

    fun prepareDestination(rawBaseUrl: String): OpenClawDestinationPlan? =
        repository.prepareDestination(rawBaseUrl)

    fun saveConfirmed(
        requestId: String,
        expectedGeneration: Long,
        confirmedOrigin: String,
        rawBaseUrl: String,
        userId: String,
        credentialMutation: OpenClawCredentialMutation,
        replacementToken: String?,
        enable: Boolean,
    ): OpenClawConfigurationMutationResult = repository.saveConfirmed(
        requestId = requestId,
        expectedGeneration = expectedGeneration,
        confirmedOrigin = confirmedOrigin,
        rawBaseUrl = rawBaseUrl,
        userId = userId,
        credentialMutation = credentialMutation,
        replacementToken = replacementToken,
        enable = enable,
    )

    fun migrateLegacyInactive(
        rawBaseUrl: String,
        legacyGatewayToken: String?,
        userId: String,
    ): OpenClawConfigurationMutationResult = repository.migrateLegacyInactive(
        rawBaseUrl = rawBaseUrl,
        legacyGatewayToken = legacyGatewayToken,
        userId = userId,
    )

    fun migrateStandaloneGatewayToken(): Boolean {
        val legacyToken = try {
            AppSecretStoreBackend.read(LEGACY_GATEWAY_TOKEN_KEY)
        } catch (_: Exception) {
            return false
        }
        if (legacyToken.isNullOrBlank()) return true
        val migrated = repository.migrateLegacyInactive("", legacyToken, "")
        if (!migrated.success) return false
        return try {
            AppSecretStoreBackend.delete(LEGACY_GATEWAY_TOKEN_KEY)
        } catch (_: Exception) {
            false
        }
    }

    fun disableAndAdvance(): OpenClawConfigurationMutationResult =
        repository.disableAndAdvance()

    fun resolveTaskConfig(
        expectedGeneration: Long,
        expectedOrigin: String,
        suppliedBaseUrl: String,
        suppliedUserId: String?,
        sessionKey: String?,
    ): TaskParams.OpenClawConfig? = repository.resolveTaskConfig(
        expectedGeneration = expectedGeneration,
        expectedOrigin = expectedOrigin,
        suppliedBaseUrl = suppliedBaseUrl,
        suppliedUserId = suppliedUserId,
        sessionKey = sessionKey,
    )

    fun isAuthorized(config: TaskParams.OpenClawConfig): Boolean =
        repository.isAuthorized(config)

    fun <T> withAuthorization(config: TaskParams.OpenClawConfig, block: () -> T): T? =
        repository.withAuthorization(config, block)

    fun getGatewayToken(): String = repository.getGatewayToken()

    fun hasGatewayToken(): Boolean = repository.hasGatewayToken()
}

internal fun canonicalizeOpenClawEndpoint(
    rawBaseUrl: String,
    allowInsecureLoopback: Boolean,
): CanonicalOpenClawEndpoint? {
    val safe = try {
        ContentEndpointSecurity.requireSafe(
            rawUrl = rawBaseUrl,
            allowInsecureLoopback = allowInsecureLoopback,
        )
    } catch (_: Exception) {
        return null
    }
    val parsed = try {
        URI(safe).normalize()
    } catch (_: Exception) {
        return null
    }
    if (parsed.rawQuery != null || parsed.rawFragment != null || parsed.userInfo != null) return null
    val scheme = parsed.scheme?.lowercase() ?: return null
    val host = parsed.host?.lowercase()?.takeIf(String::isNotBlank) ?: return null
    val defaultPort = if (scheme == "https" || scheme == "wss") 443 else 80
    val effectivePort = if (parsed.port == -1) defaultPort else parsed.port
    if (effectivePort !in 1..65535) return null
    val normalizedPath = parsed.rawPath.orEmpty().trimEnd('/')
    val endpoint = try {
        URI(
            scheme,
            null,
            host,
            if (effectivePort == defaultPort) -1 else effectivePort,
            normalizedPath,
            null,
            null,
        ).toASCIIString()
    } catch (_: Exception) {
        return null
    }
    val hostPart = if (host.contains(':')) "[$host]" else host
    return CanonicalOpenClawEndpoint(
        endpoint = endpoint,
        origin = "$scheme://$hostPart:$effectivePort",
    )
}
