import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/config/jackery_ces_config.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/omni_theme_palette.dart';

class AppTheme {
  AppTheme._();

  static ThemeData get lightTheme =>
      JackeryCesConfig.enabled ? jackeryCesTheme : _lightTheme;
  static ThemeData get darkTheme =>
      JackeryCesConfig.enabled ? jackeryCesTheme : _darkTheme;

  static final ThemeData jackeryCesTheme = _buildTheme(
    brightness: Brightness.light,
    palette: OmniThemePalette.jackeryCes,
    exhibition: true,
  );

  static final ThemeData _lightTheme = _buildTheme(
    brightness: Brightness.light,
    palette: OmniThemePalette.light,
  );

  static final ThemeData _darkTheme = _buildTheme(
    brightness: Brightness.dark,
    palette: OmniThemePalette.dark,
  );

  static ThemeData _buildTheme({
    required Brightness brightness,
    required OmniThemePalette palette,
    bool exhibition = false,
  }) {
    final isDark = brightness == Brightness.dark;
    final onAccentColor = _foregroundForAccent(palette.accentPrimary);
    final globalInputDecorationTheme = _buildInputDecorationTheme(
      palette: palette,
      isDark: isDark,
      exhibition: exhibition,
    );
    var colorScheme =
        ColorScheme.fromSeed(
          seedColor: palette.accentPrimary,
          brightness: brightness,
        ).copyWith(
          primary: palette.accentPrimary,
          onPrimary: onAccentColor,
          secondary: exhibition
              ? palette.accentPrimary
              : isDark
              ? _secondaryAccentForDark(palette)
              : AppColors.gradientAux,
          onSecondary: isDark ? onAccentColor : palette.textPrimary,
          error: AppColors.alertRed,
          onError: Colors.white,
          surface: palette.surfacePrimary,
          onSurface: palette.textPrimary,
          outline: palette.borderStrong,
          outlineVariant: palette.borderSubtle,
          surfaceTint: Colors.transparent,
        );

    if (exhibition) {
      colorScheme = colorScheme.copyWith(
        primaryContainer: palette.pageBackground,
        onPrimaryContainer: palette.textPrimary,
        secondaryContainer: palette.surfacePrimary,
        onSecondaryContainer: palette.textPrimary,
        tertiary: palette.accentPrimary,
        onTertiary: onAccentColor,
        tertiaryContainer: palette.surfacePrimary,
        onTertiaryContainer: palette.textPrimary,
        surfaceDim: palette.pageBackground,
        surfaceBright: palette.surfacePrimary,
        surfaceContainerLowest: palette.surfacePrimary,
        surfaceContainerLow: palette.pageBackground,
        surfaceContainer: palette.pageBackground,
        surfaceContainerHigh: palette.surfacePrimary,
        surfaceContainerHighest: palette.surfacePrimary,
        onSurfaceVariant: palette.textPrimary,
        inverseSurface: palette.textPrimary,
        onInverseSurface: palette.surfacePrimary,
        inversePrimary: palette.accentPrimary,
      );
    }

    final baseTheme = ThemeData(
      useMaterial3: true,
      brightness: brightness,
      colorScheme: colorScheme,
      scaffoldBackgroundColor: palette.pageBackground,
      dividerColor: palette.borderSubtle,
      shadowColor: palette.shadowColor,
      splashColor: palette.accentPrimary.withValues(alpha: isDark ? 0.1 : 0.08),
      highlightColor: Colors.transparent,
      extensions: <ThemeExtension<dynamic>>[palette],
      appBarTheme: AppBarTheme(
        backgroundColor: palette.pageBackground,
        foregroundColor: palette.textPrimary,
        elevation: 0,
        scrolledUnderElevation: 0,
        shadowColor: Colors.transparent,
        surfaceTintColor: Colors.transparent,
        centerTitle: true,
        systemOverlayStyle: overlayStyleForBrightness(brightness),
        titleTextStyle: TextStyle(
          fontSize: 17,
          fontWeight: FontWeight.w600,
          color: palette.textPrimary,
          fontFamily: 'SF Pro',
        ),
        iconTheme: IconThemeData(color: palette.textPrimary, size: 24),
      ),
      drawerTheme: DrawerThemeData(
        backgroundColor: palette.pageBackground,
        surfaceTintColor: Colors.transparent,
      ),
      dividerTheme: DividerThemeData(
        color: palette.borderSubtle,
        thickness: 1,
        space: 1,
      ),
      inputDecorationTheme: globalInputDecorationTheme,
      cardTheme: exhibition
          ? CardThemeData(
              color: palette.surfacePrimary,
              surfaceTintColor: Colors.transparent,
              elevation: 0,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(16),
                side: BorderSide(color: palette.borderSubtle),
              ),
            )
          : null,
      popupMenuTheme: exhibition
          ? PopupMenuThemeData(
              color: palette.pageBackground,
              surfaceTintColor: Colors.transparent,
            )
          : null,
      snackBarTheme: exhibition
          ? SnackBarThemeData(
              backgroundColor: palette.pageBackground,
              contentTextStyle: TextStyle(color: palette.textPrimary),
              actionTextColor: palette.accentPrimary,
              closeIconColor: palette.textPrimary,
              elevation: 0,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
                side: BorderSide(color: palette.borderSubtle),
              ),
            )
          : null,
      tooltipTheme: exhibition
          ? TooltipThemeData(
              decoration: BoxDecoration(
                color: palette.pageBackground,
                border: Border.all(color: palette.borderSubtle),
                borderRadius: BorderRadius.circular(8),
              ),
              textStyle: TextStyle(color: palette.textPrimary, fontSize: 12),
            )
          : null,
      textSelectionTheme: exhibition
          ? TextSelectionThemeData(
              cursorColor: palette.accentPrimary,
              selectionColor: palette.accentPrimary.withValues(alpha: 0.16),
              selectionHandleColor: palette.accentPrimary,
            )
          : null,
      chipTheme: ChipThemeData(
        backgroundColor: palette.surfaceElevated,
        selectedColor: palette.segmentThumb,
        disabledColor: palette.surfaceSecondary,
        labelStyle: TextStyle(
          color: palette.textSecondary,
          fontSize: 13,
          fontWeight: FontWeight.w600,
        ),
        secondaryLabelStyle: TextStyle(
          color: palette.textPrimary,
          fontSize: 13,
          fontWeight: FontWeight.w600,
        ),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        shape: StadiumBorder(side: BorderSide(color: palette.borderSubtle)),
        side: BorderSide(color: palette.borderSubtle),
      ),
      sliderTheme: SliderThemeData(
        activeTrackColor: palette.accentPrimary,
        inactiveTrackColor: palette.borderSubtle,
        thumbColor: palette.accentPrimary,
        overlayColor: palette.accentPrimary.withValues(alpha: 0.14),
        trackHeight: 3,
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return Colors.white;
          }
          return palette.surfacePrimary;
        }),
        trackColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return palette.accentPrimary;
          }
          return palette.borderStrong;
        }),
        trackOutlineColor: WidgetStateProperty.all(Colors.transparent),
      ),
      listTileTheme: ListTileThemeData(
        textColor: palette.textPrimary,
        iconColor: palette.textSecondary,
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: exhibition
            ? palette.pageBackground
            : palette.surfacePrimary,
        surfaceTintColor: Colors.transparent,
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
        ),
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: exhibition
            ? palette.pageBackground
            : palette.surfacePrimary,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      ),
    );

    return baseTheme.copyWith(
      textTheme: baseTheme.textTheme.apply(
        bodyColor: palette.textPrimary,
        displayColor: palette.textPrimary,
      ),
    );
  }

  static InputDecorationTheme _buildInputDecorationTheme({
    required OmniThemePalette palette,
    required bool isDark,
    bool exhibition = false,
  }) {
    final fillColor = exhibition
        ? palette.surfacePrimary
        : palette.surfaceSecondary.withValues(alpha: isDark ? 0.72 : 0.64);
    final radius = BorderRadius.circular(10);
    return InputDecorationTheme(
      filled: true,
      fillColor: fillColor,
      isDense: true,
      contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
      suffixIconConstraints: const BoxConstraints(minWidth: 44, minHeight: 44),
      hintStyle: TextStyle(color: palette.textTertiary, fontSize: 12),
      labelStyle: TextStyle(color: palette.textTertiary, fontSize: 12),
      border: OutlineInputBorder(
        borderRadius: radius,
        borderSide: BorderSide.none,
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: radius,
        borderSide: exhibition
            ? BorderSide(color: palette.borderSubtle)
            : BorderSide.none,
      ),
      disabledBorder: OutlineInputBorder(
        borderRadius: radius,
        borderSide: BorderSide.none,
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: radius,
        borderSide: BorderSide(
          color: palette.accentPrimary.withValues(alpha: 0.64),
        ),
      ),
      errorBorder: OutlineInputBorder(
        borderRadius: radius,
        borderSide: const BorderSide(color: AppColors.alertRed),
      ),
      focusedErrorBorder: OutlineInputBorder(
        borderRadius: radius,
        borderSide: const BorderSide(color: AppColors.alertRed, width: 1.5),
      ),
    );
  }

  static SystemUiOverlayStyle overlayStyleForBrightness(Brightness brightness) {
    final lightIcons = brightness == Brightness.dark;
    return SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: lightIcons ? Brightness.light : Brightness.dark,
      statusBarBrightness: lightIcons ? Brightness.dark : Brightness.light,
      systemNavigationBarColor: Colors.transparent,
      systemNavigationBarDividerColor: Colors.transparent,
      systemNavigationBarContrastEnforced: false,
      systemNavigationBarIconBrightness: lightIcons
          ? Brightness.light
          : Brightness.dark,
    );
  }

  static Color _foregroundForAccent(Color accentColor) {
    return accentColor.computeLuminance() > 0.35
        ? const Color(0xFF141716)
        : Colors.white;
  }

  static Color _secondaryAccentForDark(OmniThemePalette palette) {
    final hsl = HSLColor.fromColor(palette.accentPrimary);
    return hsl
        .withSaturation((hsl.saturation * 0.72).clamp(0.0, 1.0))
        .withLightness((hsl.lightness + 0.08).clamp(0.0, 1.0))
        .toColor();
  }
}
