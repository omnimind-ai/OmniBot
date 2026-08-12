package cn.com.omnimind.assists.openclaw

import cn.com.omnimind.baselib.util.FailClosedSecretRepository
import cn.com.omnimind.baselib.util.SecureSecretStore

enum class OpenClawIdentityResetStatus {
    SUCCESS,
    BUSY,
    SESSION_STOP_FAILED,
    IDENTITY_DELETE_FAILED,
    DEVICE_AUTH_DELETE_FAILED,
    CONFIGURATION_DISABLE_FAILED,
    CORE_UNAVAILABLE,
}

data class OpenClawIdentityResetResult(
    val success: Boolean,
    val status: OpenClawIdentityResetStatus,
)

internal interface OpenClawResetMetadataStore {
    fun clear(): Boolean
    fun isClear(): Boolean
}

/** Coordinates a verified identity deletion without ever creating a replacement identity. */
internal class OpenClawVerifiedIdentityReset(
    private val repository: FailClosedSecretRepository,
    private val metadataStore: OpenClawResetMetadataStore,
    private val clearCaches: () -> Unit,
) {
    @Synchronized
    fun hasExistingIdentity(): Boolean = try {
        repository.loadExisting() != null
    } catch (_: Exception) {
        false
    }

    @Synchronized
    fun reset(): Boolean {
        clearCaches()
        val secretDeleted = try { repository.clear() } catch (_: Exception) { false }
        val metadataDeleted = try { metadataStore.clear() } catch (_: Exception) { false }
        val secretVerifiedAbsent = try {
            repository.loadExisting() == null
        } catch (_: Exception) { false }
        val metadataVerifiedAbsent = try {
            metadataStore.isClear()
        } catch (_: Exception) { false }
        clearCaches()
        return secretDeleted && metadataDeleted && secretVerifiedAbsent && metadataVerifiedAbsent
    }
}

/** Clears device-scoped pairing data while proving that the user gateway token was preserved. */
internal class OpenClawVerifiedPairingReset(
    private val secureStore: SecureSecretStore,
    private val deviceTokenKey: String,
    private val gatewayTokenKey: String,
    private val metadataStore: OpenClawResetMetadataStore,
) {
    @Synchronized
    fun reset(): Boolean {
        if (!secureStore.isAvailable()) return false
        val gatewayBefore = try { secureStore.read(gatewayTokenKey) } catch (_: Exception) { return false }
        val deviceDeleted = try { secureStore.delete(deviceTokenKey) } catch (_: Exception) { false }
        val metadataDeleted = try { metadataStore.clear() } catch (_: Exception) { false }
        val deviceAfter = try { secureStore.read(deviceTokenKey) } catch (_: Exception) { return false }
        val gatewayAfter = try { secureStore.read(gatewayTokenKey) } catch (_: Exception) { return false }
        val metadataVerifiedAbsent = try { metadataStore.isClear() } catch (_: Exception) { false }
        return deviceDeleted && metadataDeleted && deviceAfter == null &&
            gatewayAfter == gatewayBefore && metadataVerifiedAbsent
    }
}
