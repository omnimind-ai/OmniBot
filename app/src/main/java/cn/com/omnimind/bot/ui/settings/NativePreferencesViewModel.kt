package cn.com.omnimind.bot.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.bot.preferences.UiPreferencesSnapshot
import cn.com.omnimind.bot.preferences.UiPreferencesStore
import cn.com.omnimind.nativeui.ThemePreference
import cn.com.omnimind.nativeui.settings.EditableQuickPrompt
import cn.com.omnimind.nativeui.settings.LanguagePreference
import cn.com.omnimind.nativeui.settings.UiPreferencesActions
import cn.com.omnimind.nativeui.settings.UiPreferencesState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Activity-scoped projection. The shared store owns persistence and language side effects. */
internal class NativePreferencesViewModel(private val store: UiPreferencesStore) : ViewModel() {
    private val mutableState = MutableStateFlow(UiPreferencesState())
    val state = mutableState.asStateFlow()

    private var observation: Job? = null

    init { observe() }

    private fun observe() {
        if (observation?.isActive == true) return
        observation = viewModelScope.launch {
            try { store.snapshots.collect(::applySnapshot) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(failed = true) } }
        }
    }

    val actions = UiPreferencesActions(
        setTheme = { value -> mutate { store.setTheme(value.name.lowercase()) } },
        setLanguage = { value -> mutate { store.setLanguage(value.storageValue) } },
        setGreeting = { enabled -> mutate { store.setGreetingEnabled(enabled) } },
        savePrompt = { id, title, prompt -> mutate(promptSaved = true) { store.savePrompt(id, title, prompt) } },
        deletePrompt = { id -> mutate { store.deletePrompt(id) } },
        togglePinned = { id -> mutate { store.togglePinned(id) } },
        resetPrompts = { mutate { store.resetPrompts() } },
        clearError = { mutableState.update { it.copy(failed = false) } },
        refresh = ::refresh,
    )

    fun refresh() {
        observe()
        mutate { withContext(Dispatchers.IO) { store.read() } }
    }

    private fun mutate(promptSaved: Boolean = false, operation: suspend () -> UiPreferencesSnapshot) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, failed = false) }
        viewModelScope.launch {
            try {
                applySnapshot(operation())
                if (promptSaved) mutableState.update { it.copy(savedPromptRevision = it.savedPromptRevision + 1) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(failed = true) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun applySnapshot(saved: UiPreferencesSnapshot) {
        mutableState.update { current -> current.copy(
            loaded = true,
            theme = when (saved.theme) { "dark" -> ThemePreference.Dark; "light" -> ThemePreference.Light; else -> ThemePreference.System },
            language = LanguagePreference.entries.firstOrNull { it.storageValue == saved.language } ?: LanguagePreference.System,
            greetingEnabled = saved.home.greetingEnabled,
            prompts = saved.home.prompts.map { EditableQuickPrompt(it.id, it.title, it.prompt, it.titleEn, it.promptEn, it.iconKey, it.builtIn) },
            pinnedIds = saved.home.pinnedIds,
        ) }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val store = UiPreferencesStore.get(context)
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NativePreferencesViewModel::class.java))
            return NativePreferencesViewModel(store) as T
        }
    }
}
