package cn.com.omnimind.nativeui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import cn.com.omnimind.nativeui.ThemePreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Mirrors ui/lib/theme/omni_theme_palette.dart during the migration. */
@Immutable
data class OmniPalette(
    val page: Color,
    val surface: Color,
    val secondarySurface: Color,
    val elevatedSurface: Color,
    val border: Color,
    val strongBorder: Color,
    val text: Color,
    val secondaryText: Color,
    val tertiaryText: Color,
    val accent: Color,
    val segmentTrack: Color,
    val segmentThumb: Color,
    val scrim: Color,
    val dark: Boolean,
) {
    // The Flutter drawer deliberately uses a different light background.
    val drawer: Color get() = if (dark) page else Color(0xFFF5F5F5)

    companion object {
        val Light = OmniPalette(
            Color(0xFFF4F7FB), Color.White, Color(0xFFF0F5FC), Color(0xFFE9F0F9),
            Color(0xFFE2EAF4), Color(0xFFD3DEEC), Color(0xFF353E53), Color(0xFF71809B),
            Color(0xFF98A5BB), Color(0xFF2C7FEB), Color(0xFFE8EFF8), Color.White,
            Color(0x4D0B1220), false,
        )
        val Dark = OmniPalette(
            Color(0xFF151617), Color(0xFF1C1E1F), Color(0xFF242728), Color(0xFF2D3032),
            Color(0xFF373A3C), Color(0xFF484C4F), Color(0xFFF2EFE8), Color(0xFFC9C3B8),
            Color(0xFF9A9488), Color(0xFF98AD90), Color(0xFF222425), Color(0xFF303335),
            Color(0xA6121314), true,
        )
    }
}

val LocalOmniPalette = staticCompositionLocalOf { OmniPalette.Light }

@Composable
fun OmniTheme(preference: ThemePreference, content: @Composable () -> Unit) {
    val dark = when (preference) {
        ThemePreference.System -> isSystemInDarkTheme()
        ThemePreference.Light -> false
        ThemePreference.Dark -> true
    }
    val palette = if (dark) OmniPalette.Dark else OmniPalette.Light
    val controller = remember(dark) {
        val base = if (dark) darkColorScheme() else lightColorScheme()
        val colors = base.copy(
            primary = palette.accent,
            onPrimary = if (dark) palette.page else Color.White,
            background = palette.page,
            onBackground = palette.text,
            onBackgroundVariant = palette.secondaryText,
            surface = palette.page,
            surfaceVariant = palette.surface,
            onSurface = palette.text,
            onSurfaceSecondary = palette.secondaryText,
            onSurfaceVariantSummary = palette.secondaryText,
            onSurfaceVariantActions = palette.tertiaryText,
            surfaceContainer = palette.surface,
            onSurfaceContainer = palette.text,
            outline = palette.strongBorder,
            dividerLine = palette.border,
            windowDimming = palette.scrim,
            error = Color(0xFFFF6464),
        )
        ThemeController(
            colorSchemeMode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
            lightColors = colors,
            darkColors = colors,
        )
    }
    CompositionLocalProvider(LocalOmniPalette provides palette) {
        MiuixTheme(controller = controller, content = content)
    }
}
