package cn.com.omnimind.nativeui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.com.omnimind.nativeui.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** Cancel + primary confirm, the Miuix dialog button convention. */
@Composable
internal fun OmniDialogActions(
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    dismissText: String = stringResource(R.string.omni_cancel),
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(dismissText, onDismiss, Modifier.weight(1f))
        TextButton(confirmText, onConfirm, Modifier.weight(1f), enabled = confirmEnabled,
            colors = ButtonDefaults.textButtonColorsPrimary())
    }
}

/** Title/summary confirmation with [OmniDialogActions]; dismissal cancels. */
@Composable
internal fun OmniConfirmDialog(
    show: Boolean,
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    summary: String? = null,
    confirmEnabled: Boolean = true,
) {
    OverlayDialog(show = show, title = title, summary = summary, onDismissRequest = onDismiss) {
        OmniDialogActions(onDismiss, confirmText, onConfirm, confirmEnabled)
    }
}

/** Acknowledge-only message with one primary button. */
@Composable
internal fun OmniNoticeDialog(
    show: Boolean,
    title: String,
    confirmText: String,
    onDismiss: () -> Unit,
    summary: String? = null,
) {
    OverlayDialog(show = show, title = title, summary = summary, onDismissRequest = onDismiss) {
        TextButton(confirmText, onDismiss, Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary())
    }
}
