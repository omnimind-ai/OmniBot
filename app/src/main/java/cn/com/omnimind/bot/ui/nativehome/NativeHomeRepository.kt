package cn.com.omnimind.bot.ui.nativehome

import android.content.Context
import android.content.SharedPreferences
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.bot.mcp.McpServerManager
import cn.com.omnimind.nativeui.ConversationSummary
import cn.com.omnimind.nativeui.NativeHomeState
import cn.com.omnimind.nativeui.QuickPrompt
import cn.com.omnimind.nativeui.ThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Temporary read adapter for existing storage. It creates no database or preference namespace. */
internal class NativeHomeRepository(context: Context) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

    val snapshots = combine(
        DatabaseHelper.getDatabase().conversationDao().observeUnarchived(),
        callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(readPreferences()) }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            trySend(readPreferences())
            awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        },
    ) { conversations, settings ->
        settings.copy(
            conversations = conversations.map {
                ConversationSummary(it.id, it.title, it.lastMessage.orEmpty(), it.mode, it.updatedAt, it.isPinned, it.parentConversationId)
            },
            loading = false,
        )
    }

    fun readPreferences(): NativeHomeState {
        val greeting = runCatching {
            JSONObject(preferences.getString("flutter.home_greeting_settings", "{}").orEmpty())
        }.getOrDefault(JSONObject())
        val english = resolveNativeHomeLocale(
            preferences.getString("flutter.language_option", "system"),
            context.resources.configuration.locales[0],
        ).language == "en"
        val prompts = greeting.optJSONArray("quickPrompts") ?: org.json.JSONArray(
            context.resources.openRawResource(cn.com.omnimind.bot.R.raw.native_home_default_prompts)
                .bufferedReader().use { it.readText() },
        )
        val pinned = greeting.optJSONArray("pinnedQuickPromptIds")
        val pinnedIds = (0 until (pinned?.length() ?: 0)).map { pinned!!.optString(it) }.take(2)
        val quickPrompts = (0 until prompts.length()).mapNotNull { index ->
            val prompt = prompts.optJSONObject(index) ?: return@mapNotNull null
            val id = prompt.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            fun localized(key: String): String =
                if (english) prompt.optString("${key}En").ifBlank { prompt.optString(key) }
                else prompt.optString(key)
            QuickPrompt(id, localized("title"), localized("prompt"))
        }.sortedBy { pinnedIds.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
        return NativeHomeState(
            theme = when (preferences.getString("flutter.theme_option", "system")) {
                "light" -> ThemePreference.Light
                "dark" -> ThemePreference.Dark
                else -> ThemePreference.System
            },
            predictiveBack = preferences.getBoolean("flutter.predictive_back_enabled", true),
            workspaceMemoryConfigured = preferences.getBoolean("flutter.workspace_memory_configured", false),
            greetingEnabled = greeting.optBoolean("greetingEnabled", true),
            quickPrompts = quickPrompts,
        )
    }

    suspend fun localServiceEnabled(): Boolean = withContext(Dispatchers.IO) {
        val state = if (McpServerManager.isPersistedEnabled()) McpServerManager.ensureRunning(context)
            else McpServerManager.currentState()
        state.enabled
    }

    suspend fun setLocalServiceEnabled(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        // McpServerManager remains the only owner of the service and token lifecycle.
        McpServerManager.setEnabled(context, enabled).enabled
    }
}
