package cn.com.omnimind.nativeui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text

/** OmniBot typography/spacing over Miuix's content and interaction container. */
@Composable
internal fun PreferenceRow(
    title: String,
    summary: String,
    @DrawableRes icon: Int? = null,
    compact: Boolean = false,
    isLast: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    bottomNote: String? = null,
    summaryColor: Color? = null,
    noteColor: Color? = null,
    trailing: @Composable () -> Unit,
) {
    val palette = LocalOmniPalette.current
    val leading: (@Composable () -> Unit)? = if (icon == null) null else {
        { OmniIcon(icon, modifier = Modifier.padding(end = 2.dp)) }
    }
    val note: (@Composable () -> Unit)? = if (bottomNote == null) null else {
        {
            Text(bottomNote, fontSize = if (compact) 10.sp else 10.5.sp, lineHeight = if (compact) 14.5.sp else 15.225.sp,
                color = noteColor ?: palette.tertiaryText, modifier = Modifier.padding(top = if (compact) 6.dp else 8.dp))
        }
    }
    BasicComponent(
        insideMargin = PaddingValues(start = 4.dp, end = 2.dp, top = if (compact) 12.dp else 14.dp,
            bottom = if (compact) { if (isLast) 12.dp else 11.dp } else { if (isLast) 14.dp else 13.dp }),
        onClick = onClick,
        role = if (onClick != null) Role.Button else null,
        enabled = enabled,
        startAction = leading,
        endActions = { Box(Modifier.padding(start = 4.dp)) { trailing() } },
        bottomAction = note,
    ) {
        Text(title, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = palette.text)
        Spacer(Modifier.height(2.dp))
        Text(summary, fontSize = 11.sp, lineHeight = 17.05.sp, color = summaryColor ?: palette.secondaryText)
    }
}

@Composable
internal fun PreferenceSectionHeader(title: String, summary: String? = null, compact: Boolean = false) {
    val palette = LocalOmniPalette.current
    Column(Modifier.padding(bottom = if (compact) 6.dp else 8.dp)) {
        Row {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .6.sp, color = palette.tertiaryText)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).padding(top = 7.dp).height(1.dp).background(palette.border.copy(alpha = if (palette.dark) .56f else .8f)))
        }
        if (summary != null) {
            Spacer(Modifier.height(6.dp))
            Text(summary, fontSize = 12.sp, lineHeight = 18.sp, color = palette.secondaryText)
        }
    }
}

@Composable
internal fun PreferenceDivider(withIcon: Boolean = true) {
    val palette = LocalOmniPalette.current
    Box(Modifier.padding(start = if (withIcon) 30.dp else 12.dp).fillMaxWidth().height(1.dp)
        .background(palette.border.copy(alpha = if (palette.dark) .5f else .78f)))
}
