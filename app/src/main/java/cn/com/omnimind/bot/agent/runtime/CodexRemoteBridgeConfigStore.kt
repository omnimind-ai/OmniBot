package cn.com.omnimind.bot.agent.runtime

import android.content.Context

/** Host identity for the configured PC Bridge, distinct from local Codex. */
internal const val REMOTE_CODEX_AGENT_ID = "codex-remote"

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

    fun read(): CodexRemoteBridgeConfig {
        return CodexRemoteBridgeConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            bridgeUrl = prefs.getString(KEY_BRIDGE_URL, "").orEmpty(),
            authToken = prefs.getString(KEY_AUTH_TOKEN, "").orEmpty(),
            cwd = prefs.getString(KEY_CWD, "").orEmpty()
        )
    }

    fun write(config: CodexRemoteBridgeConfig): CodexRemoteBridgeConfig {
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_BRIDGE_URL, config.bridgeUrl.trim())
            .putString(KEY_AUTH_TOKEN, config.authToken.trim())
            .putString(KEY_CWD, config.cwd.trim())
            .apply()
        return read()
    }

    private companion object {
        private const val PREFS_NAME = "codex_remote_bridge_config"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BRIDGE_URL = "bridge_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_CWD = "cwd"
    }
}
