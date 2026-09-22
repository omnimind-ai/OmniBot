package cn.com.omnimind.nativeui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun UpdateConfirmation(state: AboutState, actions: AboutActions) {
    val palette = LocalOmniPalette.current
    OverlayDialog(show = state.showUpdate && state.hasUpdate, title = stringResource(R.string.omni_update_available),
        backgroundColor = palette.page, onDismissRequest = actions.dismissUpdate) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UpdateInfoRow(stringResource(R.string.omni_update_current_version), state.currentVersion.versionLabel())
            UpdateInfoRow(stringResource(R.string.omni_update_latest_version), state.latestVersion.versionLabel())
            if (state.publishedAt > 0) UpdateInfoRow(stringResource(R.string.omni_update_published_at),
                Instant.ofEpochMilli(state.publishedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE))
            if (state.releaseNotes.isNotEmpty()) {
                Text(stringResource(R.string.omni_update_release_notes), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                Box(Modifier.fillMaxWidth().heightIn(max = 140.dp)
                    .background(if (palette.dark) palette.secondarySurface.copy(alpha = .82f) else Color(0xFFF6F8FA), RoundedCornerShape(12.dp))
                    .border(1.dp, if (palette.dark) palette.border.copy(alpha = .72f) else Color(0xFFE6EDF5), RoundedCornerShape(12.dp))) {
                    Text(state.releaseNotes, fontSize = 12.sp, lineHeight = 19.2.sp, color = palette.secondaryText,
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(stringResource(R.string.omni_update_later), actions.dismissUpdate, modifier = Modifier.weight(1f))
                TextButton(stringResource(if (state.canInstall) R.string.omni_update_now else R.string.omni_update_release_page),
                    actions.confirmUpdate, enabled = state.operation == null, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun UpdateInfoRow(label: String, value: String) {
    val palette = LocalOmniPalette.current
    Row {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = palette.tertiaryText, modifier = Modifier.width(68.dp))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.text, modifier = Modifier.weight(1f))
    }
}

private fun String.versionLabel(): String = if (isBlank()) "-" else "v$this"
