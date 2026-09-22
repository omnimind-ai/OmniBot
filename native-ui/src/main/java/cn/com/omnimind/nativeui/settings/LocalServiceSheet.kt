package cn.com.omnimind.nativeui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.NativeHomeState
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

/** The Scaffold/library owns dismissal, back gestures and popup layering. */
@Composable
internal fun LocalServiceSheet(show: Boolean, state: NativeHomeState, onDismiss: () -> Unit, onRefreshToken: () -> Unit) {
    val palette = LocalOmniPalette.current
    val context = LocalContext.current
    val addressLabel = stringResource(R.string.omni_settings_mcp_address)
    val tokenLabel = stringResource(R.string.omni_settings_mcp_token)
    fun copy(label: String, value: String, confirmation: Int, sensitive: Boolean = false) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, value)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, context.getString(confirmation), Toast.LENGTH_SHORT).show()
    }
    OverlayBottomSheet(
        show = show,
        title = stringResource(R.string.omni_settings_mcp_local_service),
        backgroundColor = palette.page,
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(addressLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.secondaryText)
            SelectionContainer { Text(state.localService.endpoint, fontSize = 13.sp, color = palette.text) }
            Text(tokenLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.secondaryText)
            SelectionContainer {
                Text(state.localService.token.ifBlank { stringResource(R.string.omni_settings_not_generated) }, fontSize = 13.sp, color = palette.text)
            }
            val colors = ButtonDefaults.textButtonColors(color = Color.Transparent, textColor = palette.accent)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(stringResource(R.string.omni_settings_copy_address),
                    { copy(addressLabel, state.localService.endpoint, R.string.omni_settings_copied_address) },
                    enabled = !state.localServiceBusy && state.localService.endpoint.isNotBlank(), colors = colors)
                TextButton(stringResource(R.string.omni_settings_copy_token),
                    { copy(tokenLabel, state.localService.token, R.string.omni_settings_copied_token, sensitive = true) },
                    enabled = !state.localServiceBusy && state.localService.token.isNotBlank(), colors = colors)
                TextButton(stringResource(R.string.omni_settings_refresh_token), onRefreshToken,
                    enabled = !state.localServiceBusy, colors = colors)
            }
            Text(stringResource(R.string.omni_settings_mcp_security_notice), fontSize = 12.sp, color = palette.secondaryText)
        }
    }
}
