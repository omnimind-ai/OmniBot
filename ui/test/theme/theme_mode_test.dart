import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/theme/app_theme_controller.dart';
import 'package:ui/theme/app_theme_mode.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/common_app_bar.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
  });

  test('theme mode parsing keeps compatibility and falls back to system', () {
    expect(appThemeModeFromString('light'), AppThemeMode.light);
    expect(appThemeModeFromString('dark'), AppThemeMode.dark);
    expect(appThemeModeFromString('system'), AppThemeMode.system);
    expect(appThemeModeFromString('legacy-value'), AppThemeMode.system);
    expect(appThemeModeFromString(null), AppThemeMode.system);
  });

  test('theme controller persists selected mode', () async {
    final container = ProviderContainer();
    addTearDown(container.dispose);

    expect(container.read(appThemeModeProvider), AppThemeMode.system);

    await container
        .read(appThemeModeProvider.notifier)
        .setThemeMode(AppThemeMode.dark);

    expect(container.read(appThemeModeProvider), AppThemeMode.dark);
    expect(StorageService.getThemeMode(), AppThemeMode.dark);
  });

  test('primary controls preserve readable contrast in both themes', () {
    for (final theme in [AppTheme.lightTheme, AppTheme.darkTheme]) {
      final foreground = theme.colorScheme.onPrimary.computeLuminance();
      final background = theme.colorScheme.primary.computeLuminance();
      final lighter = foreground > background ? foreground : background;
      final darker = foreground > background ? background : foreground;
      expect((lighter + 0.05) / (darker + 0.05), greaterThanOrEqualTo(7));
      expect(
        theme.switchTheme.thumbColor!.resolve({WidgetState.selected}),
        theme.colorScheme.onPrimary,
      );
    }
  });

  test('direct color overrides preserve every light and dark scheme role', () {
    for (final (theme, palette, onAccent) in [
      (AppTheme.lightTheme, OmniThemePalette.light, Colors.white),
      (AppTheme.darkTheme, OmniThemePalette.dark, const Color(0xFF161616)),
    ]) {
      final isDark = theme.brightness == Brightness.dark;
      // The previous construction generated all roles before overriding them.
      // ColorScheme equality also checks the generated fixed/error containers,
      // shadow/scrim and deprecated roles that are intentionally left intact.
      final previous =
          ColorScheme.fromSeed(
            seedColor: palette.accentPrimary,
            brightness: theme.brightness,
          ).copyWith(
            primary: palette.accentPrimary,
            onPrimary: onAccent,
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
      expect(theme.colorScheme, previous, reason: '${theme.brightness}');
    }
  });

  testWidgets('round back control keeps the provided navigation callback', (
    tester,
  ) async {
    var backCount = 0;
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        home: Scaffold(
          appBar: CommonAppBar(
            title: 'Settings',
            primary: true,
            onBackPressed: () => backCount++,
          ),
        ),
      ),
    );
    await tester.tap(find.byTooltip('Back'));
    await tester.pump();
    expect(backCount, 1);
    expect(find.text('Settings'), findsOneWidget);
  });

  testWidgets('system mode follows platform brightness for core surfaces', (
    tester,
  ) async {
    final binding = tester.binding;
    binding.platformDispatcher.platformBrightnessTestValue = Brightness.dark;
    addTearDown(binding.platformDispatcher.clearPlatformBrightnessTestValue);

    Widget buildHarness() {
      return ProviderScope(
        child: Consumer(
          builder: (context, ref, _) {
            return MaterialApp(
              theme: AppTheme.lightTheme,
              darkTheme: AppTheme.darkTheme,
              themeMode: ref.watch(appThemeModeProvider).materialThemeMode,
              home: Builder(
                builder: (context) => Scaffold(
                  backgroundColor: context.omniPalette.pageBackground,
                  appBar: const CommonAppBar(title: '主题', primary: true),
                  body: Container(
                    key: const ValueKey('surface'),
                    color: context.omniPalette.surfacePrimary,
                  ),
                ),
              ),
            );
          },
        ),
      );
    }

    await tester.pumpWidget(buildHarness());
    await tester.pumpAndSettle();

    expect(
      tester.widget<Scaffold>(find.byType(Scaffold)).backgroundColor,
      OmniThemePalette.dark.pageBackground,
    );
    expect(
      tester.widget<AppBar>(find.byType(AppBar)).backgroundColor,
      OmniThemePalette.dark.pageBackground,
    );
    expect(
      tester.widget<Container>(find.byKey(const ValueKey('surface'))).color,
      OmniThemePalette.dark.surfacePrimary,
    );

    binding.platformDispatcher.platformBrightnessTestValue = Brightness.light;
    await tester.pumpWidget(buildHarness());
    await tester.pumpAndSettle();

    expect(
      tester.widget<Scaffold>(find.byType(Scaffold)).backgroundColor,
      OmniThemePalette.light.pageBackground,
    );
    expect(
      tester.widget<AppBar>(find.byType(AppBar)).backgroundColor,
      OmniThemePalette.light.pageBackground,
    );
    expect(
      tester.widget<Container>(find.byKey(const ValueKey('surface'))).color,
      OmniThemePalette.light.surfacePrimary,
    );
  });
}
