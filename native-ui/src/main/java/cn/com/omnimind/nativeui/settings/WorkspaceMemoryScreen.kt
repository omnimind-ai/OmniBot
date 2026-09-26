package cn.com.omnimind.nativeui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniTopBar
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class WorkspaceEditor { Soul, ChatPrompt, LongMemory }

sealed interface WorkspaceMemoryNotice {
    data class Message(val text: String) : WorkspaceMemoryNotice
    data class Resource(@StringRes val id: Int) : WorkspaceMemoryNotice
}

data class WorkspaceMemorySettingsState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val soulDraft: String = "",
    val chatPromptDraft: String = "",
    val longMemoryDraft: String = "",
    val saving: WorkspaceEditor? = null,
    val embeddingEnabled: Boolean = true,
    val embeddingConfigured: Boolean = false,
    val embeddingUsesPlatform: Boolean = false,
    val embeddingBusy: Boolean = false,
    val rollupEnabled: Boolean = false,
    val rollupBusy: Boolean = false,
    val rollupRunning: Boolean = false,
    val lastRunAtMillis: Long? = null,
    val nextRunAtMillis: Long? = null,
    val notice: WorkspaceMemoryNotice? = null,
) {
    // Prompt and memory contents must not appear in incidental state logging.
    override fun toString(): String = "WorkspaceMemorySettingsState(loaded=$loaded, loading=$loading, saving=$saving)"
}

data class WorkspaceMemorySettingsActions(
    val load: () -> Unit,
    val setDraft: (WorkspaceEditor, String) -> Unit,
    val save: (WorkspaceEditor) -> Unit,
    val setEmbeddingEnabled: (Boolean) -> Unit,
    val setRollupEnabled: (Boolean) -> Unit,
    val runRollupNow: () -> Unit,
    val dismissNotice: () -> Unit,
)

@Composable
fun WorkspaceMemoryScreen(state: WorkspaceMemorySettingsState, actions: WorkspaceMemorySettingsActions,
    onSceneModels: () -> Unit, onBack: () -> Unit) {
    val palette = LocalOmniPalette.current
    val snackbar = remember { SnackbarHostState() }
    val notice = when (val value = state.notice) {
        is WorkspaceMemoryNotice.Message -> value.text
        is WorkspaceMemoryNotice.Resource -> stringResource(value.id)
        null -> null
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice)
            actions.dismissNotice()
        }
    }
    Scaffold(containerColor = palette.page,
        topBar = { OmniTopBar(stringResource(R.string.omni_workspace_title), onBack) },
        snackbarHost = { SnackbarHost(snackbar) }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 28.dp)) {
            if (state.loading && !state.loaded) {
                item { Text(stringResource(R.string.omni_workspace_loading),
                    color = palette.secondaryText, fontSize = 13.sp) }
            } else if (state.loadFailed && !state.loaded) {
                item {
                    Text(stringResource(R.string.omni_workspace_load_failed), color = palette.secondaryText, fontSize = 13.sp)
                    TextButton(stringResource(R.string.omni_log_retry), actions.load)
                }
            } else if (state.loaded) {
                item {
                    PreferenceSectionHeader(stringResource(R.string.omni_workspace_capability))
                    WorkspaceToggle(
                        title = stringResource(R.string.omni_workspace_embedding),
                        summary = stringResource(if (state.embeddingConfigured)
                            R.string.omni_workspace_embedding_ready else R.string.omni_workspace_embedding_not_ready),
                        checked = state.embeddingEnabled,
                        enabled = !state.embeddingBusy && !state.rollupRunning,
                        onChange = actions.setEmbeddingEnabled,
                    )
                    if (!state.embeddingUsesPlatform) TextButton(
                        stringResource(R.string.omni_workspace_go_to_config), onSceneModels)
                    PreferenceDivider(withIcon = false)
                    WorkspaceToggle(
                        title = stringResource(R.string.omni_workspace_nightly_rollup),
                        summary = stringResource(R.string.omni_workspace_last_run, formatMemoryTime(state.lastRunAtMillis,
                            stringResource(R.string.omni_workspace_none))) + "\n" +
                            stringResource(R.string.omni_workspace_next_run, formatMemoryTime(state.nextRunAtMillis,
                                stringResource(R.string.omni_workspace_none))),
                        checked = state.rollupEnabled,
                        enabled = !state.rollupBusy && !state.rollupRunning,
                        onChange = actions.setRollupEnabled,
                    )
                    TextButton(stringResource(R.string.omni_workspace_rollup_now), actions.runRollupNow,
                        enabled = !state.rollupRunning && !state.rollupBusy && !state.embeddingBusy && state.saving == null)
                    Spacer(Modifier.height(18.dp))
                    PreferenceSectionHeader(stringResource(R.string.omni_workspace_settings_and_memory))
                }
                item(key = "soul") {
                    WorkspaceEditorField(stringResource(R.string.omni_workspace_soul), state.soulDraft,
                        state.saving == WorkspaceEditor.Soul, state.saving != null || state.rollupRunning,
                        { actions.setDraft(WorkspaceEditor.Soul, it) }, { actions.save(WorkspaceEditor.Soul) })
                    PreferenceDivider(withIcon = false)
                }
                item(key = "chat") {
                    WorkspaceEditorField(stringResource(R.string.omni_workspace_chat_prompt), state.chatPromptDraft,
                        state.saving == WorkspaceEditor.ChatPrompt, state.saving != null || state.rollupRunning,
                        { actions.setDraft(WorkspaceEditor.ChatPrompt, it) }, { actions.save(WorkspaceEditor.ChatPrompt) })
                    PreferenceDivider(withIcon = false)
                }
                item(key = "memory") {
                    WorkspaceEditorField(stringResource(R.string.omni_workspace_long_memory), state.longMemoryDraft,
                        state.saving == WorkspaceEditor.LongMemory, state.saving != null || state.rollupRunning,
                        { actions.setDraft(WorkspaceEditor.LongMemory, it) }, { actions.save(WorkspaceEditor.LongMemory) })
                }
            }
        }
    }
}

@Composable
private fun WorkspaceToggle(title: String, summary: String, checked: Boolean,
    enabled: Boolean, onChange: (Boolean) -> Unit) {
    val palette = LocalOmniPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), color = palette.text,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Switch(checked, onChange, enabled = enabled,
                colors = SwitchDefaults.switchColors(checkedTrackColor = palette.accent,
                    uncheckedTrackColor = palette.strongBorder,
                    checkedThumbColor = Color.White, uncheckedThumbColor = Color.White),
                modifier = Modifier.semantics { contentDescription = title })
        }
        Spacer(Modifier.height(4.dp))
        Text(summary, color = palette.secondaryText, fontSize = 12.sp, lineHeight = 17.4.sp)
    }
}

@Composable
private fun WorkspaceEditorField(title: String, draft: String, saving: Boolean, anySaving: Boolean,
    onChange: (String) -> Unit, onSave: () -> Unit) {
    val palette = LocalOmniPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(title, color = palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        TextField(draft, onChange, minLines = 8, maxLines = 12, cornerRadius = 10.dp,
            enabled = !anySaving, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Button(onSave, enabled = !anySaving) {
                Text(stringResource(if (saving) R.string.omni_workspace_saving else R.string.omni_workspace_save))
            }
        }
    }
}

private fun formatMemoryTime(millis: Long?, none: String): String =
    if (millis == null || millis <= 0) none else
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
