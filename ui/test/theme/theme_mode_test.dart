import '../support/ui_preferences_channel.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/theme/app_theme_controller.dart';
import 'package:ui/theme/app_theme_mode.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/common_app_bar.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(clearUiPreferencesChannelFixture);

  setUp(() async {
    installUiPreferencesChannelFixture();
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
