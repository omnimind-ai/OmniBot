package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import cn.com.omnimind.baselib.util.AppSecretStore
import cn.com.omnimind.baselib.util.ContentEndpointSecurity
import cn.com.omnimind.baselib.util.CredentialEndpointSecurity
import cn.com.omnimind.bot.BuildConfig
import java.net.URI

internal data class CodexRemoteBridgeConfig(
    val enabled: Boolean = false,
    val bridgeUrl: String = "",
    val authToken: String = "",
    val cwd: String = ""
) {
    val isConfigured: Boolean
        get() = bridgeUrl.trim().isNotEmpty() && cwd.trim().isNotEmpty()
}

internal class CodexRemoteBridgeConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    init {
        migrateLegacyToken()
    }

    fun read(): CodexRemoteBridgeConfig {
        val bridgeUrl = prefs.getString(KEY_BRIDGE_URL, "").orEmpty()
        val revision = prefs.getLong(KEY_REVISION, 0L)
        val consentValid = try {
            prefs.getInt(KEY_CONSENT_VERSION, 0) == CURRENT_CONSENT_VERSION &&
                prefs.getLong(KEY_CONSENT_REVISION, -1L) == revision &&
                prefs.getString(KEY_CONSENT_ORIGIN, "").orEmpty() == canonicalOrigin(bridgeUrl)
        } catch (_: Exception) {
            false
        }
        val transportSafe = try {
            ContentEndpointSecurity.requireSafe(
                rawUrl = bridgeUrl,
                allowInsecureLoopback = BuildConfig.DEBUG,
            )
            true
        } catch (_: Exception) {
            false
        }
        return CodexRemoteBridgeConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false) && consentValid && transportSafe &&
                (!prefs.getBoolean(KEY_HAS_AUTH_TOKEN, false) || AppSecretStore.read(SECRET_AUTH_TOKEN) != null),
            bridgeUrl = bridgeUrl,
            authToken = AppSecretStore.read(SECRET_AUTH_TOKEN).orEmpty(),
            cwd = prefs.getString(KEY_CWD, "").orEmpty()
        )
    }

    fun write(config: CodexRemoteBridgeConfig): CodexRemoteBridgeConfig {
        val bridgeUrl = config.bridgeUrl.trim()
        val replacement = config.authToken.trim()
        val previousEnabled = prefs.getBoolean(KEY_ENABLED, false)
        val previousHasToken = prefs.getBoolean(KEY_HAS_AUTH_TOKEN, false)
        val previousBridgeUrl = prefs.getString(KEY_BRIDGE_URL, "").orEmpty()
        val previousCwd = prefs.getString(KEY_CWD, "").orEmpty()
        val previousRevision = prefs.getLong(KEY_REVISION, 0L)
        val previousConsentVersion = prefs.getInt(KEY_CONSENT_VERSION, 0)
        val previousConsentOrigin = prefs.getString(KEY_CONSENT_ORIGIN, "").orEmpty()
        val previousConsentRevision = prefs.getLong(KEY_CONSENT_REVISION, 0L)
        val previousToken = AppSecretStore.read(SECRET_AUTH_TOKEN)
        val token = replacement.ifEmpty { previousToken.orEmpty() }
        ContentEndpointSecurity.requireSafe(
            rawUrl = bridgeUrl,
            allowInsecureLoopback = BuildConfig.DEBUG,
        )
        try {
            val credentialReady = when {
                replacement.isNotEmpty() -> AppSecretStore.write(SECRET_AUTH_TOKEN, replacement) &&
                    AppSecretStore.read(SECRET_AUTH_TOKEN) == replacement
                else -> true
            }
            check(replacement.isEmpty() || credentialReady) {
                "Remote Codex credential storage is unavailable"
            }
            val cwd = config.cwd.trim()
            val revision = previousRevision + 1L
            check(
                prefs.edit()
                    .putBoolean(KEY_ENABLED, config.enabled && credentialReady)
                    .putBoolean(KEY_HAS_AUTH_TOKEN, token.isNotEmpty() && credentialReady)
                    .putString(KEY_BRIDGE_URL, bridgeUrl)
                    .putString(KEY_CWD, cwd)
                    .putLong(KEY_REVISION, revision)
                    .putInt(KEY_CONSENT_VERSION, CURRENT_CONSENT_VERSION)
                    .putString(KEY_CONSENT_ORIGIN, canonicalOrigin(bridgeUrl))
                    .putLong(KEY_CONSENT_REVISION, revision)
                    .remove(KEY_AUTH_TOKEN)
                    .commit()
            ) { "Failed to store Remote Codex metadata" }
            val stored = read()
            check(
                stored.bridgeUrl == bridgeUrl &&
                    stored.cwd == cwd &&
                    stored.authToken == token &&
                    stored.enabled == (config.enabled && credentialReady)
            ) { "Failed to verify Remote Codex configuration" }
            return stored
        } catch (failure: Exception) {
            val (secretRestored, metadataRestored) = try {
                val restoredSecret = if (previousToken.isNullOrEmpty()) {
                    AppSecretStore.delete(SECRET_AUTH_TOKEN)
                    AppSecretStore.read(SECRET_AUTH_TOKEN) == null
                } else {
                    AppSecretStore.write(SECRET_AUTH_TOKEN, previousToken) &&
                        AppSecretStore.read(SECRET_AUTH_TOKEN) == previousToken
                }
                val restoredMetadata = prefs.edit()
                    .putBoolean(KEY_ENABLED, previousEnabled && restoredSecret)
                    .putBoolean(KEY_HAS_AUTH_TOKEN, previousHasToken && restoredSecret)
                    .putString(KEY_BRIDGE_URL, previousBridgeUrl)
                    .putString(KEY_CWD, previousCwd)
                    .putLong(KEY_REVISION, previousRevision)
                    .putInt(KEY_CONSENT_VERSION, previousConsentVersion)
                    .putString(KEY_CONSENT_ORIGIN, previousConsentOrigin)
                    .putLong(KEY_CONSENT_REVISION, previousConsentRevision)
                    .remove(KEY_AUTH_TOKEN)
                    .commit()
                restoredSecret to restoredMetadata
            } catch (_: Exception) {
                false to false
            }
            if (!secretRestored || !metadataRestored) {
                AppSecretStore.delete(SECRET_AUTH_TOKEN)
                prefs.edit()
                    .putBoolean(KEY_ENABLED, false)
                    .putBoolean(KEY_HAS_AUTH_TOKEN, false)
                    .putString(KEY_BRIDGE_URL, "")
                    .putString(KEY_CWD, "")
                    .putInt(KEY_CONSENT_VERSION, 0)
                    .putString(KEY_CONSENT_ORIGIN, "")
                    .putLong(KEY_CONSENT_REVISION, 0L)
                    .remove(KEY_AUTH_TOKEN)
                    .commit()
            }
            throw failure
        }
    }

    private fun migrateLegacyToken() {
        val legacy = prefs.getString(KEY_AUTH_TOKEN, null).orEmpty()
        if (legacy.isBlank()) {
            prefs.edit().remove(KEY_AUTH_TOKEN).commit()
            return
        }
        val bridgeUrl = prefs.getString(KEY_BRIDGE_URL, "").orEmpty()
        val safeTransport = try {
            ContentEndpointSecurity.requireSafe(
                bridgeUrl,
                allowInsecureLoopback = BuildConfig.DEBUG,
            )
            true
        } catch (_: Exception) {
            false
        }
        val migrated = safeTransport && AppSecretStore.write(SECRET_AUTH_TOKEN, legacy) &&
            AppSecretStore.read(SECRET_AUTH_TOKEN) == legacy
        val scrubbed = prefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .putBoolean(KEY_HAS_AUTH_TOKEN, migrated)
            .putBoolean(KEY_ENABLED, prefs.getBoolean(KEY_ENABLED, false) && migrated)
            .commit()
        if (!migrated || !scrubbed) {
            AppSecretStore.delete(SECRET_AUTH_TOKEN)
            prefs.edit()
                .remove(KEY_AUTH_TOKEN)
                .putBoolean(KEY_HAS_AUTH_TOKEN, false)
                .putBoolean(KEY_ENABLED, false)
                .commit()
        }
    }

    private fun canonicalOrigin(rawUrl: String): String {
        val safe = ContentEndpointSecurity.requireSafe(
            rawUrl = rawUrl,
            allowInsecureLoopback = BuildConfig.DEBUG,
        )
        val uri = URI(safe)
        val scheme = uri.scheme.lowercase()
        val host = uri.host.lowercase()
        val port = if (uri.port >= 0) uri.port else if (scheme == "https" || scheme == "wss") 443 else 80
        return buildString {
            append(scheme).append("://")
            if (host.contains(':')) append('[').append(host).append(']') else append(host)
            append(':').append(port)
        }
    }

    private companion object {
        private const val PREFS_NAME = "codex_remote_bridge_config"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BRIDGE_URL = "bridge_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_HAS_AUTH_TOKEN = "has_auth_token"
        private const val KEY_CWD = "cwd"
        private const val KEY_REVISION = "revision"
        private const val KEY_CONSENT_VERSION = "consent_version"
        private const val KEY_CONSENT_ORIGIN = "consent_origin"
        private const val KEY_CONSENT_REVISION = "consent_revision"
        private const val CURRENT_CONSENT_VERSION = 1
        private const val SECRET_AUTH_TOKEN = "codex.remote_bridge.auth_token"
    }
}
