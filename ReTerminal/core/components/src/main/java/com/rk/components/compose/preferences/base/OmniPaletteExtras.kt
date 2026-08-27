package com.rk.components.compose.preferences.base

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Extra design tokens from the OmnibotApp palette that do not map onto a
 * Material3 [androidx.compose.material3.ColorScheme] role.
 *
 * Values mirror ui/lib/theme/omni_theme_palette.dart and are provided by
 * the app theme (KarbonTheme). Defaults are the light palette.
 */
@Immutable
data class OmniPaletteExtras(
    /** Tertiary text color (section headers, chevrons, hints). */
    val textTertiary: Color = Color(0xFF98A5BB),
    /** Hairline between setting rows (borderSubtle at 78%). */
    val rowDivider: Color = Color(0xC7E2EAF4),
)

val LocalOmniPaletteExtras = compositionLocalOf { OmniPaletteExtras() }
