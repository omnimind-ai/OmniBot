package com.rk.terminal.ui.screens.terminal

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalWindowInfo
import com.rk.resources.strings
import com.rk.settings.Settings
import com.rk.terminal.ui.components.OmniModalSheet

/**
 * Dialog that captures a keyboard shortcut combination from the user.
 * Shows real-time feedback of which keys are being pressed.
 * Detects conflicts with other configured shortcuts.
 */
@Composable
fun ShortcutCaptureDialog(
    action: ShortcutAction,
    onDismiss: () -> Unit,
    onConfirm: (ShortcutBinding) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var captured by remember { mutableStateOf<ShortcutBinding?>(null) }
    var modifierHint by remember { mutableStateOf("") }
    var conflictMessage by remember { mutableStateOf<String?>(null) }

    OmniModalSheet(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                    if (event.nativeKeyEvent.repeatCount > 0) return@onPreviewKeyEvent true

                    val native = event.nativeKeyEvent
                    val keyCode = native.keyCode

                    if (ShortcutBinding.isModifierKey(keyCode)) {
                        // Show modifier being held
                        val parts = mutableListOf<String>()
                        if (native.isCtrlPressed) parts.add("Ctrl")
                        if (native.isShiftPressed) parts.add("Shift")
                        if (native.isAltPressed) parts.add("Alt")
                        modifierHint = if (parts.isNotEmpty()) parts.joinToString(" + ") + " + ..." else ""
                        return@onPreviewKeyEvent true
                    }

                    if (ShortcutBinding.isReservedKey(keyCode)) {
                        conflictMessage = "This key is reserved by the system"
                        return@onPreviewKeyEvent true
                    }

                    // Non-modifier key pressed — finalize capture
                    val binding = ShortcutBinding.fromKeyEvent(native)

                    // Check for conflicts with other actions
                    val conflict = ShortcutAction.entries
                        .filter { it != action }
                        .firstOrNull { Settings.getShortcutBinding(it) == binding }

                    if (conflict != null) {
                        conflictMessage = "Conflicts with: ${conflict.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }}"
                        captured = binding
                    } else {
                        conflictMessage = null
                        captured = binding
                    }
                    modifierHint = ""
                    true
                }
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(strings.shortcut_capture_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(Modifier.height(16.dp))

                // Display area
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                ) {
                    val displayText = when {
                        captured != null -> captured!!.toDisplayString()
                        modifierHint.isNotEmpty() -> modifierHint
                        else -> stringResource(strings.shortcut_capture_hint)
                    }
                    Text(
                        text = displayText,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 18.sp,
                        fontWeight = if (captured != null) FontWeight.Bold else FontWeight.Normal,
                        color = if (captured != null)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Conflict warning
                if (conflictMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = conflictMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.height(20.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = {
                        onConfirm(ShortcutBinding()) // Clear binding
                    }) {
                        Text(stringResource(strings.shortcut_clear))
                    }

                    Row {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(strings.cancel))
                        }
                        TextButton(
                            onClick = { captured?.let { onConfirm(it) } },
                            enabled = captured != null && conflictMessage == null,
                        ) {
                            Text(stringResource(strings.apply))
                        }
                    }
                }
            }
        }
        // Wait for the sheet window to actually gain focus before requesting
        // Composable focus. LaunchedEffect(Unit) races with window activation,
        // causing the first keypress to be missed on hardware keyboards.
        val windowInfo = LocalWindowInfo.current
        LaunchedEffect(windowInfo.isWindowFocused) {
            if (windowInfo.isWindowFocused) {
                focusRequester.requestFocus()
            }
        }
    }
}
