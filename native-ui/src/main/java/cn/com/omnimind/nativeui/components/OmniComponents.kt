package cn.com.omnimind.nativeui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun OmniIcon(
    @DrawableRes icon: Int,
    description: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    tint: Color = LocalOmniPalette.current.text,
) = Icon(painterResource(icon), description, modifier.size(size), tint = tint)

@Composable
internal fun OmniIconButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    Box(
        modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { OmniIcon(icon, description, size = size) }
}

/** Flutter CommonAppBar: 44dp toolbar, 56dp leading slot, centered 17sp title. */
@Composable
internal fun OmniTopBar(title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().statusBarsPadding().height(44.dp)) {
        Box(Modifier.width(56.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            OmniIconButton(R.drawable.omni_chevron_left, stringResource(R.string.omni_back), onBack)
        }
        Text(
            title, modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            color = LocalOmniPalette.current.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier = modifier, fontSize = 11.sp, letterSpacing = .6.sp,
        fontWeight = FontWeight.SemiBold, color = LocalOmniPalette.current.tertiaryText)
}
