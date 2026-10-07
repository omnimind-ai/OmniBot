package cn.com.omnimind.nativeui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Text

/**
 * The slash command panel above the composer (batch 5d-1c): one tappable row
 * per [ChatSlashEntry]. A row fills the draft or submits through the same
 * path as typing it; nothing here talks to the runtime.
 */
@Composable
internal fun ChatSlashPanel(entries: List<ChatSlashEntry>, onPick: (ChatSlashEntry) -> Unit) {
    val palette = LocalOmniPalette.current
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .heightIn(max = 280.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.surface)
            .border(1.dp, palette.border, RoundedCornerShape(16.dp)),
    ) {
        items(entries, key = { it.id }) { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = entry.kind != ChatSlashEntry.Kind.ModelsEmpty, role = Role.Button) { onPick(entry) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OmniIcon(entryIcon(entry.kind), tint = palette.secondaryText, size = 16.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.title,
                        color = palette.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val summary = entrySummary(entry)
                    if (summary.isNotEmpty()) {
                        Text(summary, color = palette.tertiaryText, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (entry.selected) {
                    Spacer(Modifier.width(8.dp))
                    OmniIcon(R.drawable.omni_check, tint = palette.accent, size = 16.dp)
                }
            }
        }
    }
}

@Composable
private fun entrySummary(entry: ChatSlashEntry): String = when (entry.kind) {
    ChatSlashEntry.Kind.Model -> entry.detail.ifEmpty { stringResource(R.string.omni_slash_model) }
        .let { if (entry.detail.isEmpty()) it else stringResource(R.string.omni_slash_model_current, it) }
    ChatSlashEntry.Kind.Review -> stringResource(R.string.omni_slash_review)
    ChatSlashEntry.Kind.Init -> stringResource(R.string.omni_slash_init)
    ChatSlashEntry.Kind.Plan -> stringResource(if (entry.selected) R.string.omni_slash_plan_on else R.string.omni_slash_plan_off)
    ChatSlashEntry.Kind.AcpCommand -> entry.detail.ifEmpty { stringResource(R.string.omni_slash_acp_command) }
    ChatSlashEntry.Kind.ModelOption -> ""
    ChatSlashEntry.Kind.ModelsEmpty -> stringResource(R.string.omni_slash_models_empty)
    ChatSlashEntry.Kind.Effort -> entry.detail.ifEmpty { stringResource(R.string.omni_slash_effort_default) }
        .let { stringResource(R.string.omni_slash_effort, it) }
    ChatSlashEntry.Kind.EffortOption -> ""
}

private fun entryIcon(kind: ChatSlashEntry.Kind): Int = when (kind) {
    ChatSlashEntry.Kind.Model, ChatSlashEntry.Kind.ModelOption, ChatSlashEntry.Kind.ModelsEmpty -> R.drawable.omni_sparkles
    ChatSlashEntry.Kind.Review -> R.drawable.omni_git_compare_arrows
    ChatSlashEntry.Kind.Init -> R.drawable.omni_file_pen_line
    ChatSlashEntry.Kind.Plan -> R.drawable.omni_route
    ChatSlashEntry.Kind.AcpCommand -> R.drawable.omni_square_terminal
    ChatSlashEntry.Kind.Effort, ChatSlashEntry.Kind.EffortOption -> R.drawable.omni_brain
}
