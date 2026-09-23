package cn.com.omnimind.nativeui.settings

import android.icu.text.BreakIterator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniTopBar
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import java.util.Locale

@Composable
fun HomePreferencesScreen(state: UiPreferencesState, actions: UiPreferencesActions, onBack: () -> Unit) {
    val palette = LocalOmniPalette.current
    val english = LocalConfiguration.current.locales[0].language == "en"
    val enabled = state.loaded && !state.busy
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var resetOpen by rememberSaveable { mutableStateOf(false) }
    var pinLimitOpen by rememberSaveable { mutableStateOf(false) }
    // A durable save closes the editor; a failed save preserves the user's draft.
    var observedSave by rememberSaveable { mutableIntStateOf(state.savedPromptRevision) }
    LaunchedEffect(state.savedPromptRevision) {
        if (observedSave != state.savedPromptRevision) {
            observedSave = state.savedPromptRevision
            editorOpen = false
        }
    }
    val openEditor: (EditableQuickPrompt?) -> Unit = {
        editingId = it?.id
        title = it?.title.orEmpty()
        prompt = it?.prompt.orEmpty()
        actions.clearError()
        editorOpen = true
    }
    Scaffold(containerColor = palette.page, topBar = { OmniTopBar(stringResource(R.string.omni_pref_home), onBack) }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 28.dp)) {
            item(key = "greeting") {
                val label = stringResource(R.string.omni_pref_greeting)
                PreferenceRow(label, stringResource(R.string.omni_pref_greeting_summary), isLast = true,
                    enabled = enabled, onClick = { actions.setGreeting(!state.greetingEnabled) }) {
                    Switch(state.greetingEnabled, actions.setGreeting, enabled = enabled,
                        colors = SwitchDefaults.switchColors(checkedTrackColor = palette.accent, uncheckedTrackColor = palette.strongBorder,
                            checkedThumbColor = Color.White, uncheckedThumbColor = Color.White),
                        modifier = Modifier.semantics { contentDescription = label })
                }
                Spacer(Modifier.height(24.dp))
            }
            item(key = "prompt-heading") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { PreferenceSectionHeader(stringResource(R.string.omni_pref_prompts)) }
                    TextButton(stringResource(R.string.omni_pref_reset), { resetOpen = true }, enabled = enabled)
                }
            }
            if (state.loaded && state.prompts.isEmpty()) item(key = "empty") {
                Text(stringResource(R.string.omni_pref_empty), color = palette.secondaryText, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 20.dp))
            }
            items(state.prompts, key = { "prompt:${it.id}" }) { item ->
                PromptPreferenceRow(item, english, state.pinnedIds.indexOf(item.id), enabled,
                    onPin = {
                        if (item.id !in state.pinnedIds && state.pinnedIds.size >= 2) pinLimitOpen = true
                        else actions.togglePinned(item.id)
                    }, onEdit = { openEditor(item) }, onDelete = { deleteId = item.id })
                PreferenceDivider(withIcon = false)
            }
            item(key = "add") {
                TextButton(stringResource(R.string.omni_pref_add), { openEditor(null) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
            }
            if (state.failed) item(key = "failure") { PreferenceFailure(actions) }
        }
        OverlayBottomSheet(show = editorOpen,
            title = stringResource(if (editingId == null) R.string.omni_pref_add else R.string.omni_pref_edit_prompt),
            backgroundColor = palette.page, onDismissRequest = { editorOpen = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(title, { title = it }, label = stringResource(R.string.omni_pref_prompt_name),
                    singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                LengthHint(title, 12)
                TextField(prompt, { prompt = it }, label = stringResource(R.string.omni_pref_prompt_text),
                    minLines = 3, maxLines = 5, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                LengthHint(prompt, 160)
                if (state.failed) Text(stringResource(R.string.omni_pref_failed), fontSize = 12.sp, color = palette.secondaryText)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(stringResource(R.string.omni_cancel), { editorOpen = false }, modifier = Modifier.weight(1f))
                    TextButton(stringResource(R.string.omni_pref_save), { actions.savePrompt(editingId, title, prompt) },
                        enabled = enabled && title.isNotBlank() && prompt.isNotBlank() && characterCount(title.trim()) <= 12 && characterCount(prompt.trim()) <= 160,
                        modifier = Modifier.weight(1f))
                }
            }
        }
        OverlayDialog(show = deleteId != null, title = stringResource(R.string.omni_pref_delete_prompt),
            summary = stringResource(R.string.omni_pref_delete_summary), backgroundColor = palette.page,
            onDismissRequest = { deleteId = null }) {
            ConfirmationButtons(enabled, { deleteId = null }) {
                deleteId?.let(actions.deletePrompt)
                deleteId = null
            }
        }
        OverlayDialog(show = resetOpen, title = stringResource(R.string.omni_pref_reset),
            summary = stringResource(R.string.omni_pref_reset_summary), backgroundColor = palette.page,
            onDismissRequest = { resetOpen = false }) {
            ConfirmationButtons(enabled, { resetOpen = false }) { actions.resetPrompts(); resetOpen = false }
        }
        OverlayDialog(show = pinLimitOpen, title = stringResource(R.string.omni_pref_pin_limit),
            backgroundColor = palette.page, onDismissRequest = { pinLimitOpen = false }) {
            TextButton(stringResource(R.string.omni_pref_ok), { pinLimitOpen = false }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PromptPreferenceRow(item: EditableQuickPrompt, english: Boolean, pinnedIndex: Int, enabled: Boolean,
    onPin: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val palette = LocalOmniPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).background(palette.accent.copy(alpha = .1f), CircleShape), contentAlignment = Alignment.Center) {
            OmniIcon(when (item.iconKey) {
                "summarize" -> R.drawable.omni_notebook_pen
                "search" -> R.drawable.omni_search
                "execute" -> R.drawable.omni_play
                "explore" -> R.drawable.omni_globe
                "install" -> R.drawable.omni_puzzle
                else -> R.drawable.omni_sparkles
            }, size = 17.dp, tint = palette.accent)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.displayTitle(english), modifier = Modifier.weight(1f, fill = false),
                    fontSize = 14.sp, lineHeight = 18.9.sp, fontWeight = FontWeight.Medium,
                    color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(if (item.builtIn) R.string.omni_pref_builtin else R.string.omni_pref_custom),
                    fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.Medium, color = palette.tertiaryText,
                    modifier = Modifier.background(palette.secondarySurface.copy(alpha = if (palette.dark) .8f else 1f), CircleShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp))
                if (pinnedIndex >= 0) Text(stringResource(R.string.omni_pref_pinned, pinnedIndex + 1),
                    fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.SemiBold, color = palette.accent,
                    modifier = Modifier.background(palette.accent.copy(alpha = if (palette.dark) .18f else .1f), CircleShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Spacer(Modifier.height(3.dp))
            Text(item.displayPrompt(english), fontSize = 11.sp, lineHeight = 15.95.sp, color = palette.secondaryText,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onPin, enabled = enabled, minWidth = 40.dp, minHeight = 48.dp) {
            OmniIcon(if (pinnedIndex >= 0) R.drawable.omni_pin_off else R.drawable.omni_pin,
                stringResource(if (pinnedIndex >= 0) R.string.omni_pref_unpin else R.string.omni_pref_pin),
                tint = if (pinnedIndex >= 0) palette.accent else palette.tertiaryText)
        }
        if (!item.builtIn) IconButton(onEdit, enabled = enabled, minWidth = 40.dp, minHeight = 48.dp) {
            OmniIcon(R.drawable.omni_pencil, stringResource(R.string.omni_pref_edit), tint = palette.tertiaryText)
        }
        IconButton(onDelete, enabled = enabled, minWidth = 40.dp, minHeight = 48.dp) {
            OmniIcon(R.drawable.omni_trash_2, stringResource(R.string.omni_pref_delete), tint = palette.tertiaryText)
        }
    }
}

@Composable
private fun ConfirmationButtons(enabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(stringResource(R.string.omni_cancel), onCancel, modifier = Modifier.weight(1f))
        TextButton(stringResource(R.string.omni_pref_confirm), onConfirm, enabled = enabled, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LengthHint(value: String, limit: Int) {
    val count = characterCount(value.trim())
    Text("$count / $limit", fontSize = 11.sp, color = if (count > limit) Color(0xFFFF6464) else LocalOmniPalette.current.tertiaryText,
        modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.End))
}

/** Grapheme count matches the store's validation, including composed emoji. */
private fun characterCount(value: String): Int {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(value) }
    var count = 0
    iterator.first()
    while (iterator.next() != BreakIterator.DONE) count++
    return count
}
