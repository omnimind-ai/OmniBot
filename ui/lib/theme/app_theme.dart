import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/widgets/omni_menu.dart';

class AppTheme {
  AppTheme._();

  static ThemeData get lightTheme => _lightTheme;
  static ThemeData get darkTheme => _darkTheme;

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
  }) {
    final isDark = brightness == Brightness.dark;
    final onAccentColor = _foregroundForAccent(palette.accentPrimary);
    final globalInputDecorationTheme = _buildInputDecorationTheme(
      palette: palette,
    );
    final colorScheme = ColorScheme.fromSeed(
      seedColor: palette.accentPrimary,
      brightness: brightness,
      primary: palette.accentPrimary,
      onPrimary: onAccentColor,
      primaryContainer: palette.surfaceElevated,
      onPrimaryContainer: palette.textPrimary,
      secondary: palette.textSecondary,
      onSecondary: palette.surfacePrimary,
      secondaryContainer: palette.surfaceSecondary,
      onSecondaryContainer: palette.textPrimary,
      tertiary: palette.textSecondary,
      onTertiary: palette.surfacePrimary,
      tertiaryContainer: palette.surfaceElevated,
      onTertiaryContainer: palette.textPrimary,
      error: AppColors.alertRed,
      onError: Colors.white,
      surface: palette.surfacePrimary,
      onSurface: palette.textPrimary,
      onSurfaceVariant: palette.textSecondary,
      surfaceBright: palette.surfacePrimary,
      surfaceDim: palette.surfaceElevated,
      surfaceContainerLowest: palette.surfacePrimary,
      surfaceContainerLow: palette.pageBackground,
      surfaceContainer: palette.surfaceSecondary,
      surfaceContainerHigh: palette.surfaceElevated,
      surfaceContainerHighest: palette.surfaceElevated,
      inverseSurface: isDark
          ? OmniThemePalette.light.surfacePrimary
          : OmniThemePalette.dark.surfacePrimary,
      onInverseSurface: isDark
          ? OmniThemePalette.light.textPrimary
          : OmniThemePalette.dark.textPrimary,
      inversePrimary: isDark
          ? OmniThemePalette.light.accentPrimary
          : OmniThemePalette.dark.accentPrimary,
      outline: palette.borderStrong,
      outlineVariant: palette.borderSubtle,
      surfaceTint: Colors.transparent,
    );

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
      chipTheme: ChipThemeData(
        backgroundColor: palette.surfaceElevated,
        selectedColor: palette.accentPrimary,
        disabledColor: palette.surfaceSecondary,
        labelStyle: TextStyle(
          color: palette.textSecondary,
          fontSize: 13,
          fontWeight: FontWeight.w600,
        ),
        secondaryLabelStyle: TextStyle(
          color: onAccentColor,
          fontSize: 13,
          fontWeight: FontWeight.w600,
        ),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        shape: const StadiumBorder(),
        side: BorderSide.none,
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
            return onAccentColor;
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
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          disabledBackgroundColor: palette.surfaceElevated,
          disabledForegroundColor: palette.textTertiary,
          minimumSize: const Size(0, 44),
          textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
        ),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          disabledBackgroundColor: palette.surfaceElevated,
          disabledForegroundColor: palette.textTertiary,
          surfaceTintColor: Colors.transparent,
          elevation: 0,
          minimumSize: const Size(0, 44),
          textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: palette.textPrimary,
          disabledForegroundColor: palette.textTertiary,
          side: BorderSide(color: palette.borderStrong),
          minimumSize: const Size(0, 44),
          textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
        ),
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: palette.surfacePrimary,
        surfaceTintColor: Colors.transparent,
        modalBarrierColor: palette.overlayScrim,
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
        ),
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: palette.surfacePrimary,
        surfaceTintColor: Colors.transparent,
        shadowColor: palette.shadowColor,
        elevation: 6,
        titleTextStyle: TextStyle(
          color: palette.textPrimary,
          fontSize: 17,
          fontWeight: FontWeight.w600,
        ),
        contentTextStyle: TextStyle(color: palette.textSecondary, fontSize: 14),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      ),
      popupMenuTheme: PopupMenuThemeData(
        color: palette.surfacePrimary,
        surfaceTintColor: Colors.transparent,
        shadowColor: palette.shadowColor,
        elevation: 6,
        shape: const RoundedRectangleBorder(
          borderRadius: OmniMenuStyle.panelRadius,
        ),
        menuPadding: OmniMenuStyle.panelPadding,
        textStyle: OmniMenuStyle.labelStyle.copyWith(
          color: palette.textPrimary,
        ),
        labelTextStyle: WidgetStateProperty.resolveWith(
          (states) => OmniMenuStyle.labelStyle.copyWith(
            color: states.contains(WidgetState.disabled)
                ? palette.textTertiary
                : palette.textPrimary,
          ),
        ),
      ),
    );

    return baseTheme.copyWith(
      textTheme: baseTheme.textTheme
          .copyWith(
            bodyLarge: baseTheme.textTheme.bodyLarge?.copyWith(fontSize: 15),
            bodyMedium: baseTheme.textTheme.bodyMedium?.copyWith(fontSize: 14),
            bodySmall: baseTheme.textTheme.bodySmall?.copyWith(fontSize: 12),
            titleLarge: baseTheme.textTheme.titleLarge?.copyWith(fontSize: 18),
            titleMedium: baseTheme.textTheme.titleMedium?.copyWith(
              fontSize: 15,
            ),
            titleSmall: baseTheme.textTheme.titleSmall?.copyWith(fontSize: 14),
          )
          .apply(
            bodyColor: palette.textPrimary,
            displayColor: palette.textPrimary,
          ),
    );
  }

  static InputDecorationTheme _buildInputDecorationTheme({
    required OmniThemePalette palette,
  }) {
    final fillColor = palette.surfaceSecondary;
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
        borderSide: BorderSide.none,
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
        ? const Color(0xFF161616)
        : Colors.white;
  }
}
