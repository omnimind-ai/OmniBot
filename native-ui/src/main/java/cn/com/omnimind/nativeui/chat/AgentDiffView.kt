package cn.com.omnimind.nativeui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Text

/**
 * Unified diff of a file tool (Flutter `AgentDiffViewer`). Lines wrap instead
 * of scrolling horizontally so the list stays lazy on large patches.
 */
@Composable
fun AgentDiffView(
    diff: AgentDiffUi,
    modifier: Modifier = Modifier,
    showOverview: Boolean = true,
    showFileHeaders: Boolean = true,
) {
    val colors = agentDiffColors()
    LazyColumn(modifier) {
        agentDiffItems(diff, colors, showOverview, showFileHeaders)
    }
}

internal fun LazyListScope.agentDiffItems(
    diff: AgentDiffUi,
    colors: AgentDiffColors,
    showOverview: Boolean,
    showFileHeaders: Boolean,
) {
    if (diff.files.isEmpty()) {
        item(key = "diff-empty") {
            Text(stringResource(R.string.omni_diff_empty), color = colors.textSecondary, fontSize = 12.sp)
        }
        return
    }
    val maxLineCount = diff.files.maxOf { it.lines.size }
    val gutterWidth = maxOf(28f, maxOf(1, maxLineCount.toString().length) * 7.5f + 6f).dp
    if (showOverview) {
        item(key = "diff-overview") { DiffOverview(diff, colors) }
    }
    diff.files.forEachIndexed { fileIndex, file ->
        if (showFileHeaders) {
            item(key = "diff-file-$fileIndex") { DiffFileHeader(file, colors, top = fileIndex > 0 || showOverview) }
        }
        itemsIndexed(file.lines, key = { lineIndex, _ -> "diff-$fileIndex-$lineIndex" }) { _, line ->
            DiffLineRow(line, gutterWidth, colors)
        }
    }
}

@Composable
private fun DiffOverview(diff: AgentDiffUi, colors: AgentDiffColors) {
    val single = diff.files.singleOrNull()
    val title = if (single != null) single.displayPath.ifBlank { "Diff" }
    else stringResource(R.string.omni_diff_files, diff.files.size)
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.headerSurface, RoundedCornerShape(10.dp))
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OmniIcon(R.drawable.omni_git_compare_arrows, size = 18.dp, tint = colors.icon)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.omni_diff_files, diff.files.size) + " · " + diff.statLabel,
                color = colors.textSecondary, fontSize = 12.sp, maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        DiffStatPill(diff.statLabel, diff.additions, diff.deletions, colors)
    }
}

@Composable
private fun DiffFileHeader(file: AgentDiffFileUi, colors: AgentDiffColors, top: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = if (top) 14.dp else 0.dp)
            .background(colors.headerSurface, RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            .padding(start = 12.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OmniIcon(
            when {
                file.isNewFile -> R.drawable.omni_circle_plus
                file.isDeletedFile -> R.drawable.omni_circle_minus
                else -> R.drawable.omni_file_text
            },
            size = 17.dp, tint = colors.icon,
        )
        Spacer(Modifier.width(8.dp))
        Text(file.displayPath, Modifier.weight(1f), color = colors.textPrimary, fontSize = 14.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(10.dp))
        DiffChip(
            stringResource(
                when {
                    file.isNewFile -> R.string.omni_diff_state_new
                    file.isDeletedFile -> R.string.omni_diff_state_deleted
                    else -> R.string.omni_diff_state_modified
                },
            ),
            colors.chipText, colors,
        )
        Spacer(Modifier.width(8.dp))
        DiffStatPill(file.statLabel, file.additions, file.deletions, colors)
    }
}

@Composable
private fun DiffLineRow(line: AgentDiffLineUi, gutterWidth: Dp, colors: AgentDiffColors) {
    val (background, textColor, gutterColor) = when (line.kind) {
        AgentDiffLineKind.Addition -> Triple(colors.addBackground, colors.lineText, colors.addAccent)
        AgentDiffLineKind.Deletion -> Triple(colors.removeBackground, colors.lineText, colors.removeAccent)
        AgentDiffLineKind.Header -> Triple(colors.hunkBackground, colors.hunkText, colors.gutterText)
        AgentDiffLineKind.Meta -> Triple(colors.metaBackground, colors.textSecondary, colors.gutterText)
        AgentDiffLineKind.Context -> Triple(colors.contextBackground, colors.contextText, colors.gutterText)
    }
    val content = if (line.kind == AgentDiffLineKind.Header || line.kind == AgentDiffLineKind.Meta) line.content
    else line.prefix + line.content
    Row(Modifier.fillMaxWidth().background(background).padding(vertical = 3.dp)) {
        LineNumber(line.oldLineNumber, gutterWidth, gutterColor)
        Spacer(Modifier.width(6.dp))
        LineNumber(line.newLineNumber, gutterWidth, gutterColor)
        Spacer(Modifier.width(10.dp))
        Text(content, Modifier.weight(1f).padding(end = 10.dp), color = textColor, fontSize = 12.sp,
            lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun LineNumber(value: Int?, width: Dp, color: Color) {
    Text(value?.toString() ?: "", Modifier.width(width), color = color, fontSize = 11.sp, lineHeight = 17.sp,
        textAlign = TextAlign.End, fontFamily = FontFamily.Monospace)
}

@Composable
internal fun DiffStatPill(label: String, additions: Int, deletions: Int, colors: AgentDiffColors = agentDiffColors()) {
    DiffChip(label, if (additions >= deletions) colors.addAccent else colors.removeAccent, colors)
}

@Composable
private fun DiffChip(label: String, textColor: Color, colors: AgentDiffColors) {
    Text(
        label,
        Modifier
            .background(colors.chipBackground, RoundedCornerShape(50))
            .border(1.dp, colors.chipBorder, RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        color = textColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, lineHeight = 10.sp,
    )
}

@Immutable
internal data class AgentDiffColors(
    val headerSurface: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val lineText: Color,
    val contextText: Color,
    val gutterText: Color,
    val icon: Color,
    val chipBackground: Color,
    val chipBorder: Color,
    val chipText: Color,
    val addBackground: Color,
    val addAccent: Color,
    val removeBackground: Color,
    val removeAccent: Color,
    val hunkBackground: Color,
    val hunkText: Color,
    val metaBackground: Color,
    val contextBackground: Color,
)

/** GitHub-like light / dark diff palette, matching the Flutter viewer. */
@Composable
internal fun agentDiffColors(): AgentDiffColors = if (LocalOmniPalette.current.dark) DarkDiffColors else LightDiffColors

private val DarkDiffColors = AgentDiffColors(
    headerSurface = Color(0xFF111B2B), border = Color(0xFF223047),
    textPrimary = Color(0xFFF1F5FB), textSecondary = Color(0xFF8FA4C2),
    lineText = Color(0xFFE6EDF3), contextText = Color(0xFFD7E0EC), gutterText = Color(0xFF6F809A),
    icon = Color(0xFF9FB1C8), chipBackground = Color(0xFF162033), chipBorder = Color(0xFF2A3A53),
    chipText = Color(0xFF9FB1C8), addBackground = Color(0xFF12311F), addAccent = Color(0xFF7EE787),
    removeBackground = Color(0xFF351D24), removeAccent = Color(0xFFFF7B72),
    hunkBackground = Color(0xFF162033), hunkText = Color(0xFFBFD0E8),
    metaBackground = Color(0xFF111B2B), contextBackground = Color(0xFF0F1724),
)

private val LightDiffColors = AgentDiffColors(
    headerSurface = Color(0xFFF6F8FA), border = Color(0xFFE3E7ED),
    textPrimary = Color(0xFF24292F), textSecondary = Color(0xFF6E7781),
    lineText = Color(0xFF24292F), contextText = Color(0xFF57606A), gutterText = Color(0xFF8C959F),
    icon = Color(0xFF6E7781), chipBackground = Color(0xFFFFFFFF), chipBorder = Color(0xFFD8DEE6),
    chipText = Color(0xFF6E7781), addBackground = Color(0xFFEFFAEF), addAccent = Color(0xFF1A7F37),
    removeBackground = Color(0xFFFFEBE9), removeAccent = Color(0xFFCF222E),
    hunkBackground = Color(0xFFF1F6FD), hunkText = Color(0xFF57606A),
    metaBackground = Color(0xFFF6F8FA), contextBackground = Color(0xFFFFFFFF),
)
