package cn.com.omnimind.bot.codex

import android.content.Context

internal data class CodexContextInjectionConfig(
    val enabled: Boolean = false
)

internal class CodexContextInjectionConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun read(): CodexContextInjectionConfig {
        return CodexContextInjectionConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false)
        )
    }

    fun write(config: CodexContextInjectionConfig): CodexContextInjectionConfig {
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .apply()
        return read()
    }

    private companion object {
        private const val PREFS_NAME = "codex_context_injection_config"
        private const val KEY_ENABLED = "enabled"
    }
}
