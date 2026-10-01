package cn.com.omnimind.nativeui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun OmniIcon(
    @DrawableRes icon: Int,
    description: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    tint: Color = LocalOmniPalette.current.text,
) = Icon(painterResource(icon), description, modifier.size(size), tint = tint)

/** Miuix [IconButton] (press feedback, squircle) with a 48dp touch target and Lucide icon. */
@Composable
internal fun OmniIconButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    enabled: Boolean = true,
    tint: Color = LocalOmniPalette.current.text,
) {
    IconButton(onClick, modifier, enabled = enabled, minWidth = 48.dp, minHeight = 48.dp) {
        OmniIcon(icon, description, size = size, tint = if (enabled) tint else tint.copy(alpha = .4f))
    }
}

/** Miuix [SmallTopAppBar] on the page color with the shared back button. */
@Composable
internal fun OmniTopBar(title: String, onBack: () -> Unit) = OmniTopBar(title, onBack) {}

/** Same toolbar with trailing actions; kept as an overload so a trailing lambda stays `onBack`. */
@Composable
internal fun OmniTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val palette = LocalOmniPalette.current
    SmallTopAppBar(
        title = title,
        color = palette.page,
        titleColor = palette.text,
        navigationIcon = {
            OmniIconButton(R.drawable.omni_chevron_left, stringResource(R.string.omni_back), onBack)
        },
        actions = actions,
    )
}

@Composable
internal fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier = modifier, fontSize = 11.sp, letterSpacing = .6.sp,
        fontWeight = FontWeight.SemiBold, color = LocalOmniPalette.current.tertiaryText)
}
