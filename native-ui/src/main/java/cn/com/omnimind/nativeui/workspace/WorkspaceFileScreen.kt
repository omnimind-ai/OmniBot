package cn.com.omnimind.nativeui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.chat.ChatMarkdownText
import cn.com.omnimind.nativeui.components.OmniConfirmDialog
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniIconButton
import cn.com.omnimind.nativeui.components.OmniPage
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.BasicComponent

/**
 * The native file preview (batch 5e-8a). Text, Markdown and code render
 * natively and edit in place; images render natively; PDF, HTML, Office and
 * media files show what they are and hand off to the system (the Flutter
 * page embedded WebView/PDF/media players, which stay a follow-up).
 * Leaving with unsaved edits asks first.
 */
@Composable
fun WorkspaceFileScreen(
    state: WorkspaceFileState,
    actions: WorkspaceFileActions,
    onLink: (String) -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    val leave = { if (state.dirty) confirmLeave = true else onBack() }
    // Also owns back while its dialogs are open: a back press there closes the dialog and
    // must not fall through to the page stack (found on the emulator, 5e-9).
    BackHandler(enabled = state.dirty || confirmLeave || confirmCancel) {
        when {
            confirmLeave -> confirmLeave = false
            confirmCancel -> confirmCancel = false
            else -> confirmLeave = true
        }
    }

    OmniPage(
        title = state.name,
        onBack = leave,
        notice = state.notice,
        onNoticeShown = actions.dismissNotice,
        actions = {
            if (state.exists && !state.editing) {
                if (state.canEdit) OmniIconButton(R.drawable.omni_pencil, stringResource(R.string.omni_ws_edit), actions.edit)
                if (state.kind == WorkspaceFileKind.Html) {
                    OmniIconButton(R.drawable.omni_globe, stringResource(R.string.omni_ws_open_browser), actions.openInBrowser)
                }
                OmniIconButton(R.drawable.omni_send, stringResource(R.string.omni_ws_share), actions.share)
                OmniIconButton(R.drawable.omni_hard_drive_download, stringResource(R.string.omni_ws_save_to_device), actions.saveToDevice)
            }
        },
        bottomBar = {
            if (state.editing) Row(
                Modifier.fillMaxWidth().background(palette.page).navigationBarsPadding().imePadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(stringResource(R.string.omni_cancel), { if (state.dirty) confirmCancel = true else actions.cancelEdit() },
                    Modifier.weight(1f), enabled = !state.saving)
                TextButton(stringResource(if (state.saving) R.string.omni_ws_saving else R.string.omni_save), actions.save,
                    Modifier.weight(1f), enabled = !state.saving, colors = ButtonDefaults.textButtonColorsPrimary())
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            when {
                !state.exists -> Centered(stringResource(R.string.omni_ws_file_missing))
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.failed -> Centered(stringResource(R.string.omni_ws_read_failed))
                state.editing -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    Text(stringResource(if (state.dirty) R.string.omni_ws_editing_dirty else R.string.omni_ws_editing),
                        fontSize = 12.sp, color = palette.secondaryText, modifier = Modifier.padding(vertical = 8.dp))
                    TextField(
                        state.draft, actions.updateDraft, Modifier.fillMaxWidth().weight(1f),
                        enabled = !state.saving, cornerRadius = 12.dp,
                        label = stringResource(R.string.omni_ws_editor_hint), useLabelAsPlaceholder = true,
                        textStyle = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.main.copy(
                            fontSize = 14.sp, lineHeight = 21.sp,
                            fontFamily = if (workspacePrefersMonospace(state.path)) FontFamily.Monospace else FontFamily.Default,
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                state.text != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    if (state.truncated || state.longLines) Text(
                        stringResource(if (state.truncated) R.string.omni_ws_truncated else R.string.omni_ws_long_lines),
                        fontSize = 12.sp, color = palette.secondaryText, modifier = Modifier.padding(bottom = 12.dp))
                    if (state.markdown && !state.truncated && !state.longLines) {
                        ChatMarkdownText(state.text, palette.text, palette.accent, palette.secondarySurface, onOpenLink = onLink)
                    } else SelectionContainer {
                        Text(state.text, fontSize = 14.sp, lineHeight = 21.sp, color = palette.text,
                            fontFamily = if (workspacePrefersMonospace(state.path)) FontFamily.Monospace else FontFamily.Default)
                    }
                }
                state.image != null -> Image(state.image, state.name, Modifier.fillMaxSize().padding(12.dp),
                    contentScale = ContentScale.Fit)
                else -> HandOff(state, actions)
            }
        }
        // Inside the page Scaffold: Miuix overlays render in a Scaffold's popup host.
        OmniConfirmDialog(
            show = confirmLeave,
            title = stringResource(R.string.omni_ws_leave_title),
            summary = stringResource(R.string.omni_ws_unsaved_summary),
            confirmText = stringResource(R.string.omni_ws_discard),
            onConfirm = { confirmLeave = false; actions.cancelEdit(); onBack() },
            onDismiss = { confirmLeave = false },
        )
        OmniConfirmDialog(
            show = confirmCancel,
            title = stringResource(R.string.omni_ws_discard_title),
            summary = stringResource(R.string.omni_ws_unsaved_summary),
            confirmText = stringResource(R.string.omni_ws_discard),
            onConfirm = { confirmCancel = false; actions.cancelEdit() },
            onDismiss = { confirmCancel = false },
        )
    }

}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = LocalOmniPalette.current.secondaryText)
    }
}

@Composable
private fun HandOff(state: WorkspaceFileState, actions: WorkspaceFileActions) {
    val palette = LocalOmniPalette.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        OmniIcon(R.drawable.omni_file, null, size = 56.dp, tint = palette.secondaryText)
        Spacer(Modifier.height(12.dp))
        Text(state.name, color = palette.text, fontSize = 16.sp)
        Text(state.shellPath, color = palette.tertiaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Text(state.mimeType, color = palette.secondaryText, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(24.dp))
        Button(actions.openWithSystem, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
            Text(stringResource(R.string.omni_ws_open_with), color = androidx.compose.ui.graphics.Color.White)
        }
        Spacer(Modifier.height(4.dp))
        if (state.kind == WorkspaceFileKind.Html) BasicComponent(
            title = stringResource(R.string.omni_ws_open_browser), onClick = actions.openInBrowser,
        )
        BasicComponent(title = stringResource(R.string.omni_ws_share), onClick = actions.share)
    }
}
