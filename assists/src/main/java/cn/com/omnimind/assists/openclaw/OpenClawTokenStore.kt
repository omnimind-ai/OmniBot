package cn.com.omnimind.assists.openclaw

import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.AppSecretStore
import cn.com.omnimind.baselib.util.AppSecretStoreBackend
import com.tencent.mmkv.MMKV

/**
 * OpenClaw DeviceToken 持久化存储
 *
 * Gateway 在 hello-ok 响应中可能返回 deviceToken，
 * 客户端必须持久化保存并在后续连接中使用此 token 进行认证。
 */
object OpenClawTokenStore {
    private const val TAG = "OpenClawTokenStore"
    private const val KEY_DEVICE_TOKEN = "openclaw_device_token"
    private const val SECRET_DEVICE_TOKEN = "openclaw.device_token"
    private const val SECRET_GATEWAY_TOKEN = "openclaw.gateway_token"
    private const val KEY_DEVICE_ROLE = "openclaw_device_role"
    private const val KEY_DEVICE_SCOPES = "openclaw_device_scopes"

    private val mmkv: MMKV by lazy { MMKV.defaultMMKV() }

    private val verifiedPairingReset by lazy {
        OpenClawVerifiedPairingReset(
            secureStore = AppSecretStoreBackend,
            deviceTokenKey = SECRET_DEVICE_TOKEN,
            gatewayTokenKey = SECRET_GATEWAY_TOKEN,
            metadataStore = object : OpenClawResetMetadataStore {
                override fun clear(): Boolean {
                    mmkv.removeValueForKey(KEY_DEVICE_ROLE)
                    mmkv.removeValueForKey(KEY_DEVICE_SCOPES)
                    return isClear()
                }

                override fun isClear(): Boolean =
                    !mmkv.containsKey(KEY_DEVICE_ROLE) && !mmkv.containsKey(KEY_DEVICE_SCOPES)
            },
        )
    }

    /** One-time removal of the legacy plaintext device token. */
    @Synchronized
    fun initialize() {
        val legacy = mmkv.decodeString(KEY_DEVICE_TOKEN).orEmpty()
        if (legacy.isNotBlank()) {
            val migrated = AppSecretStore.write(SECRET_DEVICE_TOKEN, legacy) &&
                AppSecretStore.read(SECRET_DEVICE_TOKEN) == legacy
            // Never keep the plaintext copy, even when Keystore is unavailable.
            mmkv.removeValueForKey(KEY_DEVICE_TOKEN)
            if (!migrated) {
                AppSecretStore.delete(SECRET_DEVICE_TOKEN)
                OmniLog.w(TAG, "legacy device credential migration failed closed")
            }
        }
        if (!OpenClawConfigurationStore.migrateStandaloneGatewayToken()) {
            OmniLog.w(TAG, "legacy gateway credential migration failed closed")
        }
    }

    /**
     * 保存 Gateway 颁发的 deviceToken（来自 hello-ok 响应）
     */
    fun saveDeviceToken(token: String) {
        if (token.isBlank()) return
        if (!AppSecretStore.write(SECRET_DEVICE_TOKEN, token.trim())) {
            OmniLog.w(TAG, "device credential was not persisted because secure storage is unavailable")
        }
    }

    /**
     * 获取已保存的 deviceToken，如果没有则返回 null
     */
    fun getDeviceToken(): String? {
        val token = AppSecretStore.read(SECRET_DEVICE_TOKEN)
        return if (token.isNullOrBlank()) null else token
    }

    fun hasGatewayToken(): Boolean = OpenClawConfigurationStore.hasGatewayToken()

    fun hasAnyAuthToken(): Boolean =
        !getDeviceToken().isNullOrBlank() || hasGatewayToken()

    fun saveGatewayToken(token: String): Boolean {
        // Standalone credential replacement is intentionally disabled. Configuration and consent
        // must be committed together through OpenClawConfigurationStore.saveConfirmed().
        return false
    }

    fun migrateLegacyGatewayToken(token: String): Boolean {
        val normalized = token.trim()
        if (normalized.isEmpty()) return hasGatewayToken()
        return OpenClawConfigurationStore
            .migrateLegacyInactive("", normalized, "")
            .success
    }

    fun clearGatewayToken(): Boolean = false

    fun getGatewayToken(): String =
        OpenClawConfigurationStore.getGatewayToken()

    /**
     * 保存 Gateway 返回的角色和 scope 信息
     */
    fun saveAuthInfo(role: String?, scopes: List<String>?) {
        if (!role.isNullOrBlank()) {
            mmkv.encode(KEY_DEVICE_ROLE, role)
        }
        if (!scopes.isNullOrEmpty()) {
            mmkv.encode(KEY_DEVICE_SCOPES, scopes.joinToString(","))
        }
    }

    /**
     * 获取已保存的角色
     */
    fun getRole(): String? {
        return mmkv.decodeString(KEY_DEVICE_ROLE)
    }

    /**
     * 获取已保存的 scopes
     */
    fun getScopes(): List<String> {
        val raw = mmkv.decodeString(KEY_DEVICE_SCOPES)
        return if (raw.isNullOrBlank()) emptyList()
        else raw.split(",").filter { it.isNotBlank() }
    }

    /**
     * 清除所有存储的 token 信息（用于重置设备配对）
     */
    @Synchronized
    fun resetDevicePairingVerified(): Boolean {
        val cleared = verifiedPairingReset.reset()
        if (cleared) {
            OmniLog.i(TAG, "cleared and verified stored device authentication")
        } else {
            OmniLog.w(TAG, "device authentication reset could not be verified")
        }
        return cleared
    }

    /** Backward-compatible reset entry point. Gateway credentials are intentionally preserved. */
    fun clear(): Boolean = resetDevicePairingVerified()

    /**
     * 获取用于认证的 token：
     * 优先使用 deviceToken（后续连接），退回到 gateway token（首次连接）
     */
    fun getAuthToken(): String {
        val deviceToken = getDeviceToken()
        if (!deviceToken.isNullOrBlank()) {
            OmniLog.i(TAG, "using stored deviceToken for auth")
            return deviceToken
        }
        OmniLog.i(TAG, "using gateway credential for auth (first connect or no device credential)")
        return OpenClawConfigurationStore.getGatewayToken()
    }
}
