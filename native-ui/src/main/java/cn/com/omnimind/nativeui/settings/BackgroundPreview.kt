package cn.com.omnimind.nativeui.settings

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Text

/** The same white/dark mask and text-tone rules used by Flutter's AppBackgroundLayer. */
internal data class BackgroundVisualColors(
    val primary: Color, val secondary: Color, val userBubble: Color, val usesLightText: Boolean,
    val custom: Boolean,
)

internal fun backgroundVisualColors(config: BackgroundConfig, sampledLuminance: Float): BackgroundVisualColors {
    val whiteBoost = ((config.brightness - 1f).coerceIn(0f, .5f) / .5f) * .18f
    val white = (.14f + config.frostOpacity + whiteBoost).coerceIn(.12f, .78f)
    val dark = ((1f - config.brightness).coerceIn(0f, .5f) / .5f * .24f).coerceIn(0f, .24f)
    val effective = (sampledLuminance + (.97f - sampledLuminance) * white) * (1f - dark)
    val lightText = config.isActive && effective < .56f
    val customColor = if (config.isActive && config.chatTextColorMode == BackgroundTextColorMode.Custom) {
        runCatching { Color(AndroidColor.parseColor(config.chatTextHexColor)) }.getOrNull()
    } else null
    return BackgroundVisualColors(
        primary = customColor ?: if (lightText) Color(0xFFF4F8FE) else Color(0xFF353E53),
        secondary = customColor?.copy(alpha = .82f) ?: if (lightText) Color(0xFFD5E3F2) else Color(0xFF617390),
        userBubble = if (lightText) Color(0x3322344B) else Color(0xE6F1F8FF),
        usesLightText = lightText, custom = customColor != null,
    )
}

@Composable
internal fun BackgroundImageLayer(
    state: BackgroundSettingsState,
    modifier: Modifier = Modifier,
    fallback: Color = if (LocalOmniPalette.current.dark) Color(0xFF1A1B1C) else Color(0xFFF6FAFF),
) {
    val config = state.config
    val tail = if (LocalOmniPalette.current.dark) lerp(fallback, Color(0xFF1B2840), .5f)
        else lerp(fallback, Color.White, .35f)
    Box(modifier.background(Brush.linearGradient(listOf(fallback, tail)))) {
        if (config.isActive) {
            state.previewImage?.let { bitmap ->
                Image(bitmap, null,
                    modifier = Modifier.fillMaxSize().blur(config.blurSigma.dp).graphicsLayer {
                        scaleX = config.imageScale
                        scaleY = config.imageScale
                        transformOrigin = TransformOrigin((config.focalX + 1f) / 2f, (config.focalY + 1f) / 2f)
                    },
                    contentScale = ContentScale.Crop,
                    alignment = BiasAlignment(config.focalX, config.focalY))
            }
            val whiteBoost = ((config.brightness - 1f).coerceIn(0f, .5f) / .5f) * .18f
            val white = (.14f + config.frostOpacity + whiteBoost).coerceIn(.12f, .78f)
            val dark = ((1f - config.brightness).coerceIn(0f, .5f) / .5f * .24f).coerceIn(0f, .24f)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                Color.White.copy(alpha = (white + .04f).coerceAtMost(.85f)),
                Color(0xFFF6FAFF).copy(alpha = white),
                Color(0xFFEDF4FF).copy(alpha = (white * .9f).coerceAtMost(.8f)),
            ))))
            if (dark > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dark)))
        }
    }
}

/** Preview uses Compose's transformable gesture instead of maintaining a pointer engine. */
@Composable
internal fun BackgroundPreview(
    state: BackgroundSettingsState,
    kind: BackgroundPreviewKind,
    onViewportChanged: (Float, Float, Float) -> Unit,
) {
    val palette = LocalOmniPalette.current
    val config = state.config
    val currentConfig by rememberUpdatedState(config)
    val currentOnViewportChanged by rememberUpdatedState(onViewportChanged)
    val visual = backgroundVisualColors(config, state.sampledLuminance)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        val current = currentConfig
        if (current.hasResolvedImage && size.width > 0 && size.height > 0) {
            val scale = (current.imageScale * zoom).coerceIn(1f, 3f)
            val x = (current.focalX - pan.x / size.width * 2f / scale).coerceIn(-1f, 1f)
            val y = (current.focalY - pan.y / size.height * 2f / scale).coerceIn(-1f, 1f)
            currentOnViewportChanged(x, y, scale)
        }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(.68f).clip(RoundedCornerShape(24.dp))
        .onSizeChanged { size = it }
        .then(if (config.hasResolvedImage) Modifier.transformable(transform) else Modifier)) {
        BackgroundImageLayer(state, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            if (kind == BackgroundPreviewKind.Chat) ChatPreviewChrome(config, visual)
            else WorkspacePreviewChrome(visual)
        }
        if (state.imageLoading || state.imageLoadFailed) {
            val label = stringResource(if (state.imageLoading) R.string.omni_background_image_loading
                else R.string.omni_background_image_load_failed)
            Text(label, modifier = Modifier.align(Alignment.Center)
                .background(Color.Black.copy(alpha = .58f), RoundedCornerShape(99.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
                color = Color.White, fontSize = 12.sp)
        }
        if (config.hasResolvedImage) Text(stringResource(R.string.omni_background_drag_hint),
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                .background(Color.Black.copy(alpha = .54f), RoundedCornerShape(99.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChatPreviewChrome(config: BackgroundConfig, visual: BackgroundVisualColors) {
    val palette = LocalOmniPalette.current
    Box(Modifier.fillMaxWidth().height(54.dp).background(palette.surface.copy(alpha = .72f), RoundedCornerShape(99.dp)))
    Spacer(Modifier.height(18.dp))
    val tone = stringResource(if (visual.custom) R.string.omni_background_preview_tone_custom
        else if (visual.usesLightText) R.string.omni_background_preview_tone_light
        else R.string.omni_background_preview_tone_dark)
    Text("${stringResource(R.string.omni_background_preview_chat)} · $tone",
        modifier = Modifier.background(palette.surface.copy(alpha = .70f), RoundedCornerShape(99.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = visual.secondary, fontSize = (11f * config.chatTextSize / 14f).sp,
        fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))
    PreviewMessage(stringResource(R.string.omni_background_preview_chat),
        stringResource(R.string.omni_background_text_color_subtitle), .66f, visual, config, false)
    Spacer(Modifier.height(14.dp))
    PreviewMessage(stringResource(R.string.omni_background_text_color_title), tone,
        .52f, visual, config, true)
    Spacer(Modifier.weight(1f))
    Box(Modifier.fillMaxWidth().height(64.dp).background(palette.surface.copy(alpha = .76f), RoundedCornerShape(22.dp)))
}

@Composable
private fun PreviewMessage(title: String, subtitle: String, width: Float, visual: BackgroundVisualColors,
    config: BackgroundConfig, user: Boolean) {
    val palette = LocalOmniPalette.current
    val scale = (config.chatTextSize / 14f).coerceIn(.86f, 1.58f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Column(Modifier.align(if (user) Alignment.CenterEnd else Alignment.CenterStart)
            .width(maxWidth * width).height(70.dp)
            .background(if (user) visual.userBubble else palette.surface.copy(alpha = .54f), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(title, color = visual.primary, fontSize = (11f * scale).sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = visual.secondary, fontSize = (10f * scale).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun WorkspacePreviewChrome(visual: BackgroundVisualColors) {
    val palette = LocalOmniPalette.current
    Box(Modifier.fillMaxWidth().height(48.dp).background(palette.surface.copy(alpha = .74f), RoundedCornerShape(16.dp)))
    Spacer(Modifier.height(18.dp))
    repeat(5) { index ->
        Row(Modifier.fillMaxWidth().height(46.dp)
            .background(palette.surface.copy(alpha = .70f + index * .02f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp).background(visual.secondary.copy(alpha = .18f), RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.fillMaxWidth().height(10.dp)
                .background(visual.secondary.copy(alpha = .32f), RoundedCornerShape(99.dp)))
        }
        if (index < 4) Spacer(Modifier.height(10.dp))
    }
}
