import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/settings/background_setting_page.dart';
import 'package:ui/services/app_background_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/theme/app_theme_controller.dart';
import 'package:ui/theme/app_theme_mode.dart';
import 'package:ui/widgets/app_background_widgets.dart';
import 'package:ui/widgets/settings_ui.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/theme/liquid_glass_controller.dart';

class _SvgTestAssetBundle extends CachingAssetBundle {
  static final Uint8List _svgBytes = Uint8List.fromList(
    utf8.encode(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
      '<rect width="24" height="24" fill="#000000"/>'
      '</svg>',
    ),
  );

  @override
  Future<ByteData> load(String key) async {
    return ByteData.view(_svgBytes.buffer);
  }

  @override
  Future<String> loadString(String key, {bool cache = true}) async {
    return utf8.decode(_svgBytes);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    AppBackgroundService.notifier.value = AppBackgroundConfig.defaults;
  });

  tearDown(() async {
    AppBackgroundService.notifier.value = AppBackgroundConfig.defaults;
  });

  Widget buildTestApp(Widget child) {
    return ProviderScope(
      child: Consumer(
        builder: (context, ref, _) {
          return MaterialApp(
            theme: AppTheme.lightTheme,
            darkTheme: AppTheme.darkTheme,
            themeMode: ref.watch(appThemeModeProvider).materialThemeMode,
            home: DefaultAssetBundle(
              bundle: _SvgTestAssetBundle(),
              child: child,
            ),
          );
        },
      ),
    );
  }

  Future<void> scrollTo(
    WidgetTester tester,
    Finder target,
    double delta,
  ) async {
    await tester.scrollUntilVisible(
      target,
      delta,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.pumpAndSettle();
  }

  testWidgets(
    'liquid glass starts at ultra and persists quality and enable choices',
    (tester) async {
      await tester.pumpWidget(buildTestApp(const BackgroundSettingPage()));
      await tester.pumpAndSettle();
      final toggle = find.byKey(
        const ValueKey('appearance-liquid-glass-switch'),
      );
      final quality = find.byKey(
        const ValueKey('appearance-liquid-glass-quality'),
      );
      expect(tester.widget<OmniSettingsSwitch>(toggle).value, isTrue);
      expect(
        tester
            .widget<OmniSettingsDropdown<LiquidGlassQuality>>(
              find.byType(OmniSettingsDropdown<LiquidGlassQuality>),
            )
            .value,
        LiquidGlassQuality.ultra,
      );
      await tester.ensureVisible(quality);
      await tester.pumpAndSettle();
      await tester.tap(quality);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Standard').last);
      await tester.pumpAndSettle();
      expect(
        StorageService.getString(LiquidGlassController.qualityKey),
        'standard',
      );
      await tester.ensureVisible(toggle);
      await tester.pumpAndSettle();
      await tester.tap(toggle);
      await tester.pumpAndSettle();
      expect(tester.widget<OmniSettingsSwitch>(toggle).value, isFalse);
      expect(StorageService.getBool(LiquidGlassController.enabledKey), isFalse);
      expect(
        tester
            .widget<OmniSettingsDropdown<LiquidGlassQuality>>(
              find.byType(OmniSettingsDropdown<LiquidGlassQuality>),
            )
            .onChanged,
        isNull,
      );
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'large text uses reachable appearance choices on a narrow screen',
    (tester) async {
      tester.view.physicalSize = const Size(320, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await tester.pumpWidget(
        buildTestApp(
          const MediaQuery(
            data: MediaQueryData(
              size: Size(320, 800),
              textScaler: TextScaler.linear(1.5),
            ),
            child: BackgroundSettingPage(),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('appearance-theme-dropdown')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey('appearance-language-dropdown')),
        findsOneWidget,
      );
      expect(find.byKey(const ValueKey('theme-mode-slider')), findsNothing);
      final quality = find.byKey(
        const ValueKey('appearance-liquid-glass-quality'),
      );
      await tester.ensureVisible(quality);
      await tester.pumpAndSettle();
      expect(quality.hitTestable(), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets(
    'periodic pet refresh does not invalidate a slow in-flight read',
    (tester) async {
      const channel = MethodChannel('cn.com.omnimind.bot/overlay');
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      final firstState = Completer<Map<String, dynamic>>();
      var reads = 0;
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method != 'getPetOverlayState') return null;
        reads += 1;
        return firstState.future;
      });
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      await tester.pumpWidget(buildTestApp(const BackgroundSettingPage()));
      await tester.pumpAndSettle();
      expect(reads, 1);
      final card = find.byKey(const ValueKey('appearance-pet-card'));
      await scrollTo(tester, card, 500);
      final header = find
          .descendant(of: card, matching: find.byType(InkWell))
          .first;
      await tester.ensureVisible(header);
      await tester.pumpAndSettle();
      await tester.tap(header);
      await tester.pumpAndSettle();
      await tester.pump(const Duration(seconds: 5));
      expect(reads, 1);
      firstState.complete({'selectedId': 'builtin:xiaowan'});
      await tester.pumpAndSettle();
      expect(
        reads,
        1,
        reason: 'the periodic tick must not queue a replacement read',
      );
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  testWidgets('supports auto-saving appearance updates from the preview', (
    tester,
  ) async {
    AppBackgroundService.notifier.value = const AppBackgroundConfig(
      enabled: true,
      sourceType: AppBackgroundSourceType.remote,
      localImagePath: '',
      remoteImageUrl: 'https://example.com/existing-background.png',
      blurSigma: 10,
      frostOpacity: 0.2,
      brightness: 1,
      focalX: 0.1,
      focalY: -0.1,
    );

    await tester.pumpWidget(buildTestApp(const BackgroundSettingPage()));

    expect(find.text('外观设置'), findsOneWidget);
    expect(find.byKey(const ValueKey('theme-mode-slider')), findsOneWidget);
    expect(
      find.byType(AppBackgroundPreview),
      findsNothing,
      reason: 'offscreen preview must not be laid out on entry',
    );
    expect(find.byKey(const ValueKey('background-save-button')), findsNothing);
    expect(find.byKey(const ValueKey('background-reset-button')), findsNothing);
    expect(find.byKey(const ValueKey('background-source-none')), findsNothing);
    expect(find.byKey(const ValueKey('appearance-pet-card')), findsNothing);

    await tester.tap(find.byKey(const ValueKey('theme-mode-option-dark')));
    await tester.pumpAndSettle();
    expect(StorageService.getThemeMode(), AppThemeMode.dark);

    final workspacePreviewChip = find.byKey(
      const ValueKey('background-preview-kind-workspace'),
    );
    await scrollTo(tester, workspacePreviewChip, 350);
    await tester.tap(workspacePreviewChip, warnIfMissed: false);
    await tester.pumpAndSettle();

    expect(find.byType(AppBackgroundPreview), findsOneWidget);

    final remoteSourceChip = find.byKey(
      const ValueKey('background-source-remote'),
    );
    await scrollTo(tester, remoteSourceChip, -350);
    await tester.tap(remoteSourceChip);
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey('background-remote-url-field')),
      findsOneWidget,
    );

    await scrollTo(
      tester,
      find.byKey(const ValueKey('appearance-text-color-field')),
      350,
    );
    await tester.enterText(
      find.byKey(const ValueKey('appearance-text-color-field')),
      '#1D3E7B',
    );
    await tester.pump(const Duration(milliseconds: 260));
    await tester.pumpAndSettle();

    expect(
      AppBackgroundService.current.chatTextColorMode,
      AppBackgroundTextColorMode.custom,
    );
    expect(AppBackgroundService.current.chatTextHexColor, '#1D3E7B');

    final backgroundSwitch = find.byKey(
      const ValueKey('appearance-background-enable-switch'),
    );
    await scrollTo(tester, backgroundSwitch, -350);
    await tester.tap(backgroundSwitch);
    await tester.pump(const Duration(milliseconds: 260));
    await tester.pumpAndSettle();

    expect(AppBackgroundService.current.enabled, isFalse);
  });

  testWidgets('flushes pending appearance changes when leaving immediately', (
    tester,
  ) async {
    AppBackgroundService.notifier.value = const AppBackgroundConfig(
      enabled: true,
      sourceType: AppBackgroundSourceType.remote,
      localImagePath: '',
      remoteImageUrl: 'https://example.com/existing-background.png',
      blurSigma: 10,
      frostOpacity: 0.2,
      brightness: 1,
      focalX: 0.1,
      focalY: -0.1,
    );

    await tester.pumpWidget(buildTestApp(const BackgroundSettingPage()));

    await scrollTo(
      tester,
      find.byKey(const ValueKey('appearance-text-color-field')),
      350,
    );
    await tester.enterText(
      find.byKey(const ValueKey('appearance-text-color-field')),
      '#1D3E7B',
    );
    await tester.pump(const Duration(milliseconds: 100));

    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();

    expect(
      AppBackgroundService.current.chatTextColorMode,
      AppBackgroundTextColorMode.custom,
    );
    expect(AppBackgroundService.current.chatTextHexColor, '#1D3E7B');
  });
}
