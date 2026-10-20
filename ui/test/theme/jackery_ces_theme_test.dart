import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/config/jackery_ces_config.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/theme/app_theme_controller.dart';
import 'package:ui/theme/app_theme_mode.dart';
import 'package:ui/theme/omni_theme_palette.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('exhibition theme keeps backgrounds neutral and controls orange', () {
    final theme = AppTheme.jackeryCesTheme;
    const palette = OmniThemePalette.jackeryCes;

    expect(theme.brightness, Brightness.light);
    expect(theme.scaffoldBackgroundColor, const Color(0xFFF8F7F5));
    expect(theme.drawerTheme.backgroundColor, palette.pageBackground);
    expect(theme.appBarTheme.backgroundColor, palette.pageBackground);
    expect(theme.dialogTheme.backgroundColor, palette.pageBackground);
    expect(theme.bottomSheetTheme.backgroundColor, palette.pageBackground);
    expect(theme.popupMenuTheme.color, palette.pageBackground);
    expect(theme.cardTheme.color, Colors.white);
    expect(theme.inputDecorationTheme.fillColor, Colors.white);
    expect(theme.dividerColor, const Color(0xFFE8E5E1));
    expect(theme.colorScheme.primary, const Color(0xFFFF6727));
    expect(theme.colorScheme.secondary, palette.accentPrimary);
    expect(theme.colorScheme.onSurface, const Color(0xFF303030));
    expect(
      theme.switchTheme.trackColor?.resolve({WidgetState.selected}),
      palette.accentPrimary,
    );
    expect(theme.textSelectionTheme.cursorColor, palette.accentPrimary);
    expect(theme.extension<OmniThemePalette>(), palette);
  });

  test('edition selects tokens without changing standard colors', () {
    if (JackeryCesConfig.enabled) {
      expect(AppColors.primaryBlue, const Color(0xFFFF6727));
      expect(AppColors.buttonPrimary, const Color(0xFFFF6727));
      expect(AppColors.linkBlue, const Color(0xFFFF6727));
      expect(AppColors.background, const Color(0xFFF8F7F5));
      expect(AppColors.text, const Color(0xFF303030));
      expect(AppColors.borderStandard, const Color(0xFFE8E5E1));
      expect(AppColors.primaryGradient.colors.toSet(), {Colors.white});
      expect(AppColors.buttonGradient.colors.toSet(), {AppColors.primaryBlue});
      expect(AppTheme.lightTheme, same(AppTheme.jackeryCesTheme));
      expect(AppTheme.darkTheme, same(AppTheme.jackeryCesTheme));
      expect(AppThemeMode.dark.materialThemeMode, ThemeMode.light);
      expect(AppThemeMode.system.materialThemeMode, ThemeMode.light);
    } else {
      expect(AppColors.primaryBlue, const Color(0xFF00AEFF));
      expect(AppColors.buttonPrimary, const Color(0xFF2C7FEB));
      expect(AppColors.background, const Color(0xFFF5F5F5));
      expect(AppTheme.darkTheme.brightness, Brightness.dark);
      expect(
        AppTheme.lightTheme.extension<OmniThemePalette>(),
        OmniThemePalette.light,
      );
      expect(AppThemeMode.dark.materialThemeMode, ThemeMode.dark);
    }
  });

  test(
    'exhibition ignores a persisted dark theme and rejects mode edits',
    () async {
      SharedPreferences.setMockInitialValues({
        StorageService.kThemeOptionKey: 'dark',
      });
      await StorageService.init();
      final container = ProviderContainer();
      addTearDown(container.dispose);
      await container
          .read(appThemeModeProvider.notifier)
          .setThemeMode(AppThemeMode.dark);
      expect(
        container.read(appThemeModeProvider),
        JackeryCesConfig.enabled ? AppThemeMode.light : AppThemeMode.dark,
      );
    },
  );
}
