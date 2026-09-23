package cn.com.omnimind.nativeui.settings

import androidx.compose.runtime.Immutable
import cn.com.omnimind.nativeui.ThemePreference

@Immutable
data class UiPreferencesState(
    val loaded: Boolean = false,
    val theme: ThemePreference = ThemePreference.System,
    val language: LanguagePreference = LanguagePreference.System,
    val greetingEnabled: Boolean = true,
    val prompts: List<EditableQuickPrompt> = emptyList(),
    val pinnedIds: List<String> = emptyList(),
    val busy: Boolean = false,
    val failed: Boolean = false,
    val savedPromptRevision: Int = 0,
)

enum class LanguagePreference(val storageValue: String) { System("system"), Chinese("zhHans"), English("en") }

@Immutable
data class EditableQuickPrompt(
    val id: String, val title: String, val prompt: String,
    val titleEn: String?, val promptEn: String?, val iconKey: String, val builtIn: Boolean,
) {
    fun displayTitle(english: Boolean) = if (english) titleEn ?: title else title
    fun displayPrompt(english: Boolean) = if (english) promptEn ?: prompt else prompt
}

data class UiPreferencesActions(
    val setTheme: (ThemePreference) -> Unit,
    val setLanguage: (LanguagePreference) -> Unit,
    val setGreeting: (Boolean) -> Unit,
    val savePrompt: (id: String?, title: String, prompt: String) -> Unit,
    val deletePrompt: (String) -> Unit,
    val togglePinned: (String) -> Unit,
    val resetPrompts: () -> Unit,
    val clearError: () -> Unit,
    val refresh: () -> Unit,
)
