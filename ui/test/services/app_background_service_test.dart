import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/app_background_service.dart';
import 'package:ui/services/storage_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/app_state');
  late Map<String, dynamic> storedConfig;
  var failSave = false;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    AppBackgroundService.notifier.value = AppBackgroundConfig.defaults;
    storedConfig = AppBackgroundConfig.defaults.toJson();
    failSave = false;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          final prefs = await SharedPreferences.getInstance();
          switch (call.method) {
            case 'getBackgroundConfig':
              return storedConfig;
            case 'saveBackgroundConfig':
              if (failSave) throw PlatformException(code: 'save_failed');
              final arguments = call.arguments as Map<dynamic, dynamic>;
              storedConfig = AppBackgroundConfig.fromJson(
                Map<String, dynamic>.from(arguments['config'] as Map),
              ).toJson();
              await prefs.setString(
                'flutter.app_background_config_v1',
                jsonEncode(storedConfig),
              );
              return storedConfig;
            case 'resetBackgroundConfig':
              storedConfig = AppBackgroundConfig.defaults.toJson();
              await prefs.remove('flutter.app_background_config_v1');
              return storedConfig;
          }
          throw MissingPluginException(call.method);
        });
  });

  tearDown(() async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    LegacyTextLocalizer.clearResolvedLocale();
    AppBackgroundService.notifier.value = AppBackgroundConfig.defaults;
  });

  test('fromJson clamps background values into supported range', () {
    final config = AppBackgroundConfig.fromJson(const <String, dynamic>{
      'enabled': true,
      'sourceType': 'remote',
      'remoteImageUrl': 'https://example.com/bg.jpg',
      'blurSigma': 100,
      'frostOpacity': -1,
      'brightness': 3,
      'focalX': -3,
      'focalY': 4,
      'imageScale': 8,
      'chatTextSize': 30,
      'chatTextColorMode': 'custom',
      'chatTextHexColor': '1d3e7b',
    });

    expect(config.enabled, isTrue);
    expect(config.sourceType, AppBackgroundSourceType.remote);
    expect(config.blurSigma, 24);
    expect(config.frostOpacity, 0);
    expect(config.brightness, 1.5);
    expect(config.focalX, -1);
    expect(config.focalY, 1);
    expect(config.imageScale, 3);
    expect(config.chatTextSize, 22);
    expect(config.chatTextColorMode, AppBackgroundTextColorMode.custom);
    expect(config.chatTextHexColor, '#1D3E7B');
  });

  test('save load and reset round-trip shared background config', () async {
    const config = AppBackgroundConfig(
      enabled: true,
      sourceType: AppBackgroundSourceType.remote,
      localImagePath: '',
      remoteImageUrl: 'https://example.com/background.jpg',
      blurSigma: 12,
      frostOpacity: 0.22,
      brightness: 1.1,
      focalX: 0.3,
      focalY: -0.25,
      imageScale: 1.8,
      chatTextSize: 17.5,
      chatTextColorMode: AppBackgroundTextColorMode.custom,
      chatTextHexColor: '#1D3E7B',
    );

    await AppBackgroundService.save(config);

    expect(AppBackgroundService.current.isActive, isTrue);
    expect(
      AppBackgroundService.current.remoteImageUrl,
      'https://example.com/background.jpg',
    );

    AppBackgroundService.notifier.value = AppBackgroundConfig.defaults;
    await AppBackgroundService.load();

    expect(AppBackgroundService.current.enabled, isTrue);
    expect(
      AppBackgroundService.current.sourceType,
      AppBackgroundSourceType.remote,
    );
    expect(
      AppBackgroundService.current.remoteImageUrl,
      'https://example.com/background.jpg',
    );
    expect(AppBackgroundService.current.blurSigma, 12);
    expect(AppBackgroundService.current.frostOpacity, 0.22);
    expect(AppBackgroundService.current.brightness, 1.1);
    expect(AppBackgroundService.current.focalX, 0.3);
    expect(AppBackgroundService.current.focalY, -0.25);
    expect(AppBackgroundService.current.imageScale, 1.8);
    expect(AppBackgroundService.current.chatTextSize, 17.5);
    expect(
      AppBackgroundService.current.chatTextColorMode,
      AppBackgroundTextColorMode.custom,
    );
    expect(AppBackgroundService.current.chatTextHexColor, '#1D3E7B');

    await AppBackgroundService.reset();

    expect(AppBackgroundService.current.enabled, isFalse);
    expect(
      AppBackgroundService.current.sourceType,
      AppBackgroundSourceType.none,
    );
    expect(AppBackgroundService.current.remoteImageUrl, isEmpty);
  });

  test(
    'failed native save leaves the visible config and storage unchanged',
    () async {
      failSave = true;
      final next = AppBackgroundConfig.defaults.copyWith(
        sourceType: AppBackgroundSourceType.remote,
        remoteImageUrl: 'https://example.com/background.jpg',
        enabled: true,
      );

      await expectLater(
        AppBackgroundService.save(next),
        throwsA(isA<PlatformException>()),
      );

      expect(AppBackgroundService.current, AppBackgroundConfig.defaults);
      expect(StorageService.getString('app_background_config_v1'), isNull);
    },
  );

  test(
    'load refreshes a native edit while Flutter preferences are cached',
    () async {
      storedConfig = AppBackgroundConfig.defaults
          .copyWith(
            sourceType: AppBackgroundSourceType.remote,
            remoteImageUrl: 'https://example.com/new.jpg',
            enabled: true,
          )
          .toJson();

      await AppBackgroundService.load();

      expect(
        AppBackgroundService.current.remoteImageUrl,
        'https://example.com/new.jpg',
      );
      expect(AppBackgroundService.current.isActive, isTrue);
    },
  );

  test(
    'startup reads the shared key while the native channel is unavailable',
    () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
      final cached = AppBackgroundConfig.defaults.copyWith(
        sourceType: AppBackgroundSourceType.remote,
        remoteImageUrl: 'https://example.com/cached.jpg',
        enabled: true,
      );
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(
        'flutter.app_background_config_v1',
        jsonEncode(cached.toJson()),
      );

      await AppBackgroundService.load();

      expect(
        AppBackgroundService.current.remoteImageUrl,
        'https://example.com/cached.jpg',
      );
    },
  );

  test('derive chooses readable text tone from whole-background luminance', () {
    const config = AppBackgroundConfig(
      enabled: true,
      sourceType: AppBackgroundSourceType.remote,
      localImagePath: '',
      remoteImageUrl: 'https://example.com/background.jpg',
      blurSigma: 10,
      frostOpacity: 0.2,
      brightness: 1,
      focalX: 0,
      focalY: 0,
    );

    final darkProfile = AppBackgroundVisualProfile.derive(
      config: config,
      sampledImageLuminance: 0.16,
    );
    final lightProfile = AppBackgroundVisualProfile.derive(
      config: config,
      sampledImageLuminance: 0.9,
    );
    final customProfile = AppBackgroundVisualProfile.derive(
      config: config.copyWith(
        chatTextColorMode: AppBackgroundTextColorMode.custom,
        chatTextHexColor: '#1D3E7B',
      ),
      sampledImageLuminance: 0.2,
    );

    expect(darkProfile.usesLightText, isTrue);
    expect(darkProfile.previewToneLabel, '浅色文本');
    expect(lightProfile.usesLightText, isFalse);
    expect(lightProfile.previewToneLabel, '深色文本');
    expect(customProfile.previewToneLabel, '自定义颜色');
    expect(customProfile.primaryTextColor, const Color(0xFF1D3E7B));
  });
}
