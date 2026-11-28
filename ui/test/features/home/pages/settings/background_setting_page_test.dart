import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/settings/background_setting_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/app_background_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_icon_controller.dart';
import 'package:ui/theme/app_icon_variant.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/theme/app_theme_controller.dart';
import 'package:ui/theme/app_theme_mode.dart';
import 'package:ui/widgets/app_background_widgets.dart';
import 'package:ui/widgets/settings_ui.dart';
import 'package:ui/widgets/omni_slider.dart';
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
    if (!key.endsWith('.svg')) return rootBundle.load(key);
    return ByteData.view(_svgBytes.buffer);
  }

  @override
  Future<String> loadString(String key, {bool cache = true}) async {
    if (!key.endsWith('.svg')) return rootBundle.loadString(key, cache: cache);
    return utf8.decode(_svgBytes);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    AppIconController.syncFromStorage();
    AppBackgroundService.notifier.value = AppBackgroundConfig.defaults;
  });

  tearDown(() async {
    AppBackgroundService.notifier.value = AppBackgroundConfig.defaults;
  });

  Widget buildTestApp(Widget child, {Locale? locale}) {
    return ProviderScope(
      child: Consumer(
        builder: (context, ref, _) {
          return MaterialApp(
            locale: locale,
            localizationsDelegates: locale == null
                ? null
                : AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
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

  Finder iconChoice(AppIconVariant variant) =>
      find.byKey(ValueKey('appearance-app-icon-${variant.storageValue}'));

  testWidgets('shows six icons and persists an Android launcher change', (
    tester,
  ) async {
    const channel = MethodChannel('cn.com.omnimind.bot/app_state');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return 'wave';
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));

    await tester.pumpWidget(
      buildTestApp(const BackgroundSettingPage(), locale: const Locale('en')),
    );
    await tester.pumpAndSettle();

    expect(find.text('App Icon'), findsOneWidget);
    expect(find.text('Default'), findsOneWidget);
    const labels = [
      'AI Sparkle',
      'Live Dot',
      'Terminal Cursor',
      'Listening Wave',
      'Open Ring',
      'Classic Ear',
    ];
    for (final (index, variant) in AppIconVariant.values.indexed) {
      final choice = iconChoice(variant);
      expect(
        tester.widget<Semantics>(choice).properties.selected,
        variant.isDefault,
      );
      expect(
        find.descendant(of: choice, matching: find.text(labels[index])),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: choice,
          matching: find.image(AssetImage(variant.assetPath)),
        ),
        findsOneWidget,
      );
    }
    expect(StorageService.getAppIconVariant(), AppIconVariant.sparkle);

    final wave = iconChoice(AppIconVariant.wave);
    await tester.ensureVisible(wave);
    await tester.pumpAndSettle();
    await tester.tap(wave);
    await tester.pumpAndSettle();

    expect(tester.widget<Semantics>(wave).properties.selected, isTrue);
    expect(
      tester
          .widget<Semantics>(iconChoice(AppIconVariant.sparkle))
          .properties
          .selected,
      isFalse,
    );
    expect(StorageService.getString(StorageService.kAppIconVariantKey), 'wave');
    expect(calls.single.method, 'setAppIconVariant');
    expect(calls.single.arguments, {'variant': 'wave'});
    expect(tester.takeException(), isNull);
  });

  testWidgets('keeps the previous selection when Android rejects a change', (
    tester,
  ) async {
    const channel = MethodChannel('cn.com.omnimind.bot/app_state');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    var attempts = 0;
    messenger.setMockMethodCallHandler(channel, (_) async {
      attempts++;
      throw PlatformException(code: 'APP_ICON_UPDATE_FAILED');
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
    await tester.pumpWidget(buildTestApp(const BackgroundSettingPage()));
    await tester.pumpAndSettle();

    final dot = iconChoice(AppIconVariant.dot);
    await tester.ensureVisible(dot);
    await tester.pumpAndSettle();
    await tester.tap(dot);
    await tester.pumpAndSettle();

    expect(attempts, 1);
    expect(find.text('无法更换应用图标，请重试。'), findsOneWidget);
    expect(tester.widget<Semantics>(dot).properties.selected, isFalse);
    expect(StorageService.getAppIconVariant(), AppIconVariant.sparkle);
    expect(AppIconController.currentNotifier.value, AppIconVariant.sparkle);
  });

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
          locale: const Locale('pt'),
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
      expect(find.text('Ícone do app'), findsOneWidget);
      expect(find.text('Padrão'), findsOneWidget);
      expect(find.text('Onda Sonora'), findsOneWidget);
      for (final variant in AppIconVariant.values) {
        final choice = iconChoice(variant);
        await tester.ensureVisible(choice);
        await tester.pumpAndSettle();
        expect(choice.hitTestable(), findsOneWidget);
      }
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

  testWidgets('appearance value tracks retain ranges and save live updates', (
    tester,
  ) async {
    await tester.pumpWidget(buildTestApp(const BackgroundSettingPage()));
    await tester.pumpAndSettle();
    final blur = find.byKey(
      const ValueKey('appearance-background-blur-slider'),
    );
    await scrollTo(tester, blur, 350);
    final blurWidget = tester.widget<OmniSlider>(blur);
    expect((blurWidget.min, blurWidget.max), (0, 24));
    expect(
      tester
          .widget<OmniSlider>(
            find.byKey(const ValueKey('appearance-overlay-intensity-slider')),
          )
          .max,
      0.55,
    );
    final brightness = tester.widget<OmniSlider>(
      find.byKey(const ValueKey('appearance-overlay-brightness-slider')),
    );
    expect((brightness.min, brightness.max), (0.5, 1.5));
    final size = tester.widget<OmniSlider>(
      find.byKey(const ValueKey('appearance-chat-text-size-slider')),
    );
    expect((size.min, size.max), (12, 22));

    final rect = tester.getRect(blur);
    await tester.tapAt(
      Offset(rect.left + 2 + (rect.width - 4) * 0.75, rect.center.dy),
    );
    await tester.pump(const Duration(milliseconds: 260));
    await tester.pumpAndSettle();
    expect(AppBackgroundService.current.blurSigma, closeTo(18, 0.0001));
    expect(tester.widget<OmniSlider>(blur).value, closeTo(18, 0.0001));
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
