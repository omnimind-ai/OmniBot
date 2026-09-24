package cn.com.omnimind.nativeui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.ThemePreference
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniTopBar
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
fun AppearanceScreen(state: UiPreferencesState, actions: UiPreferencesActions, onBackground: () -> Unit, onBack: () -> Unit) {
    val palette = LocalOmniPalette.current
    Scaffold(containerColor = palette.page, topBar = { OmniTopBar(stringResource(R.string.omni_settings_appearance_title), onBack) }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Column {
                    PreferenceSectionHeader(stringResource(R.string.omni_pref_theme), stringResource(R.string.omni_pref_theme_summary))
                    PreferenceChoices(listOf(stringResource(R.string.omni_pref_system), stringResource(R.string.omni_pref_light), stringResource(R.string.omni_pref_dark)),
                        ThemePreference.entries.indexOf(state.theme), state.loaded && !state.busy) { actions.setTheme(ThemePreference.entries[it]) }
                }
            }
            item {
                Column {
                    PreferenceSectionHeader(stringResource(R.string.omni_pref_language), stringResource(R.string.omni_pref_language_summary))
                    PreferenceChoices(listOf(stringResource(R.string.omni_pref_system), "简体中文", "English"),
                        LanguagePreference.entries.indexOf(state.language), state.loaded && !state.busy) { actions.setLanguage(LanguagePreference.entries[it]) }
                }
            }
            item {
                PreferenceRow(stringResource(R.string.omni_pref_background), stringResource(R.string.omni_pref_background_summary),
                    icon = R.drawable.omni_image, isLast = true, onClick = onBackground) { OmniIcon(R.drawable.omni_chevron_right) }
            }
            if (state.failed) item { PreferenceFailure(actions) }
        }
    }
}

@Composable
private fun PreferenceChoices(labels: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    val palette = LocalOmniPalette.current
    // Miuix owns the indicator, click semantics and horizontal layout.
    TabRowWithContour(labels, selected, { if (enabled) onSelect(it) },
        colors = TabRowDefaults.tabRowColors(backgroundColor = palette.segmentTrack, contentColor = palette.secondaryText,
            selectedBackgroundColor = palette.segmentThumb, selectedContentColor = palette.accent))
}

@Composable
internal fun PreferenceFailure(actions: UiPreferencesActions) {
    Column {
        Text(stringResource(R.string.omni_pref_failed), fontSize = 12.sp, color = LocalOmniPalette.current.secondaryText)
        TextButton(stringResource(R.string.omni_retry), actions.refresh)
    }
}
