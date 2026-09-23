package cn.com.omnimind.bot.preferences

import android.content.Context
import android.content.SharedPreferences
import android.icu.text.BreakIterator
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.quicklog.QuickLogWidgetUpdater
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** The shared writer for these three existing preference keys. No new storage namespace. */
class UiPreferencesStore private constructor(context: Context) {
    private val application = context.applicationContext
    private val preferences = application.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val defaultPromptsJson by lazy {
        application.resources.openRawResource(R.raw.native_home_default_prompts).bufferedReader().use { it.readText() }
    }

    val snapshots = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in KEYS) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { read() }.flowOn(Dispatchers.IO)

    fun read(): UiPreferencesSnapshot {
        val home = readHomeObject()
        val prompts = promptObjects(home).map { item ->
            HomePromptPreference(
                id = item.optString("id"), title = item.optString("title"), prompt = item.optString("prompt"),
                titleEn = (item.opt("titleEn") as? String)?.takeIf(String::isNotBlank),
                promptEn = (item.opt("promptEn") as? String)?.takeIf(String::isNotBlank),
                iconKey = item.optString("iconKey", "spark"), builtIn = item.optBoolean("builtIn", false),
            )
        }.filter { it.id.isNotBlank() }
        val ids = prompts.map { it.id }.toSet()
        return UiPreferencesSnapshot(
            theme = preferences.getString(THEME, "system")?.trim().takeIf { it in THEMES } ?: "system",
            language = preferences.getString(LANGUAGE, "system")?.trim().takeIf { it in LANGUAGES } ?: "system",
            systemLocaleTag = AppLocaleManager.systemLocale().toLanguageTag(),
            home = HomePreference(
                greetingEnabled = home.optBoolean("greetingEnabled", true),
                prompts = prompts,
                pinnedIds = pinnedIds(home).filter { it in ids }.distinct().take(2),
            ),
        )
    }

    suspend fun setTheme(value: String): UiPreferencesSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(value in THEMES) { "Unsupported theme mode" }
            check(preferences.edit().putString(THEME, value).commit()) { "Unable to save theme" }
            // The visible host applies its theme. Do not recreate a Flutter Activity here.
            read()
        }
    }

    suspend fun setLanguage(value: String): UiPreferencesSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(value in LANGUAGES) { "Unsupported language mode" }
            check(preferences.edit().putString(LANGUAGE, value).commit()) { "Unable to save language" }
            // Once committed, finish existing effects even if the submitting host closes.
            withContext(NonCancellable) { applyLanguagePreference() }
            read()
        }
    }

    /** Preserve the existing language side effects, shared with the legacy app-state command. */
    suspend fun applyLanguagePreference() = withContext(Dispatchers.Main) {
        AppLocaleManager.applyAppLocale(application)
        runCatching { AgentWorkspaceManager(application).ensureRuntimeDirectories() }
            .onFailure { OmniLog.w("UiPreferences", "Unable to refresh localized workspace defaults") }
        runCatching { QuickLogWidgetUpdater.updateAll(application) }
            .onFailure { OmniLog.w("UiPreferences", "Unable to refresh localized widgets") }
        Unit
    }

    suspend fun setGreetingEnabled(enabled: Boolean) = editHome { it.put("greetingEnabled", enabled) }

    suspend fun savePrompt(id: String?, title: String, prompt: String): UiPreferencesSnapshot = editHome { home ->
        val normalizedTitle = title.trim()
        val normalizedPrompt = prompt.trim()
        require(normalizedTitle.isNotEmpty() && normalizedPrompt.isNotEmpty()) { "Prompt title and text are required" }
        require(characterCount(normalizedTitle) <= 12 && characterCount(normalizedPrompt) <= 160) { "Prompt length exceeds the limit" }
        val prompts = promptObjects(home).toMutableList()
        if (id == null) {
            prompts.add(0, JSONObject().put("id", "custom_${UUID.randomUUID()}")
                .put("title", normalizedTitle).put("prompt", normalizedPrompt).put("iconKey", "spark").put("builtIn", false))
        } else {
            val item = prompts.firstOrNull { it.optString("id") == id } ?: error("Prompt no longer exists")
            require(!item.optBoolean("builtIn", false)) { "Built-in prompts cannot be edited" }
            // Keep translations and unknown metadata when editing the visible title/text.
            item.put("title", normalizedTitle).put("prompt", normalizedPrompt)
        }
        home.put("quickPrompts", JSONArray(prompts))
    }

    suspend fun deletePrompt(id: String) = editHome { home ->
        home.put("quickPrompts", JSONArray(promptObjects(home).filterNot { it.optString("id") == id }))
        home.put("pinnedQuickPromptIds", JSONArray(pinnedIds(home).filterNot { it == id }))
    }

    suspend fun resetPrompts() = editHome { home ->
        home.put("quickPrompts", JSONArray(defaultPromptsJson))
        home.put("pinnedQuickPromptIds", JSONArray())
    }

    suspend fun togglePinned(id: String) = editHome { home ->
        val ids = promptObjects(home).map { it.optString("id") }.toSet()
        require(id in ids) { "Prompt no longer exists" }
        val pinned = pinnedIds(home).filter { it in ids }.distinct().take(2).toMutableList()
        if (!pinned.remove(id)) {
            require(pinned.size < 2) { "At most two prompts may be pinned" }
            pinned.add(id)
        }
        home.put("pinnedQuickPromptIds", JSONArray(pinned))
    }

    private suspend fun editHome(edit: (JSONObject) -> Unit): UiPreferencesSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val home = readHomeObject()
            edit(home)
            check(preferences.edit().putString(HOME, home.toString()).commit()) { "Unable to save home preferences" }
            read()
        }
    }

    private fun readHomeObject(): JSONObject {
        val result = runCatching { JSONObject(preferences.getString(HOME, "{}").orEmpty()) }.getOrDefault(JSONObject())
        if (result.optJSONArray("quickPrompts") == null) result.put("quickPrompts", JSONArray(defaultPromptsJson))
        return result
    }

    private fun promptObjects(home: JSONObject): List<JSONObject> {
        val array = home.getJSONArray("quickPrompts")
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun pinnedIds(home: JSONObject): List<String> {
        val array = home.optJSONArray("pinnedQuickPromptIds") ?: return emptyList()
        return List(array.length()) { array.optString(it).trim() }.filter(String::isNotEmpty)
    }

    private fun characterCount(value: String): Int {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(value) }
        var count = 0
        iterator.first()
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }

    companion object {
        const val THEME = "flutter.theme_option"
        const val LANGUAGE = "flutter.language_option"
        const val HOME = "flutter.home_greeting_settings"
        private val KEYS = setOf(THEME, LANGUAGE, HOME)
        private val THEMES = setOf("system", "light", "dark")
        private val LANGUAGES = setOf("system", "zhHans", "en")
        @Volatile private var instance: UiPreferencesStore? = null
        fun get(context: Context): UiPreferencesStore = instance ?: synchronized(this) {
            instance ?: UiPreferencesStore(context).also { instance = it }
        }
    }
}

data class HomePromptPreference(
    val id: String, val title: String, val prompt: String,
    val titleEn: String?, val promptEn: String?, val iconKey: String, val builtIn: Boolean,
) {
    fun toMap(): Map<String, Any?> = mapOf("id" to id, "title" to title, "prompt" to prompt,
        "titleEn" to titleEn, "promptEn" to promptEn, "iconKey" to iconKey, "builtIn" to builtIn)
}

data class HomePreference(val greetingEnabled: Boolean, val prompts: List<HomePromptPreference>, val pinnedIds: List<String>) {
    fun toMap(): Map<String, Any?> = mapOf("greetingEnabled" to greetingEnabled,
        "quickPrompts" to prompts.map { it.toMap() }, "pinnedQuickPromptIds" to pinnedIds)
}

data class UiPreferencesSnapshot(val theme: String, val language: String, val home: HomePreference, val systemLocaleTag: String) {
    fun toMap(): Map<String, Any?> = mapOf("theme" to theme, "language" to language, "systemLocaleTag" to systemLocaleTag, "home" to home.toMap())
}
