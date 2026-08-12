import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_switch/flutter_switch.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/pages/agent/remote_codex_setting_page.dart';
import 'package:ui/features/home/pages/scene_model_setting/scene_model_setting_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/models_dev_catalog_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/services/voice_playback_coordinator.dart';
import 'package:ui/theme/app_theme.dart';

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

const _modelsDevCatalogJson = '''
{
  "custom": {
    "id": "custom",
    "name": "Custom",
    "models": {
      "scene-model": {
        "id": "scene-model",
        "name": "Scene Model",
        "limit": {"context": 128000}
      }
    }
  }
}
''';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  const agentRuntimeChannel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');

  Widget buildTestApp(Widget child, {Locale locale = const Locale('zh')}) {
    return MaterialApp(
      navigatorKey: GoRouterManager.rootNavigatorKey,
      theme: AppTheme.lightTheme,
      darkTheme: AppTheme.darkTheme,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: locale,
      home: DefaultAssetBundle(bundle: _SvgTestAssetBundle(), child: child),
    );
  }

  Future<void> confirmDestination(WidgetTester tester) async {
    expect(
      find.byKey(const Key('data-destination-confirmation-dialog')),
      findsOneWidget,
    );
    await tester.tap(find.byKey(const Key('data-destination-acknowledgement')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('data-destination-confirm')));
    await tester.pumpAndSettle();
  }

  Future<void> confirmDestinationWithoutSettling(WidgetTester tester) async {
    expect(
      find.byKey(const Key('data-destination-confirmation-dialog')),
      findsOneWidget,
    );
    await tester.tap(find.byKey(const Key('data-destination-acknowledgement')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('data-destination-confirm')));
    await tester.pump();
  }

  Future<void> pumpSceneSettings(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(buildTestApp(const SceneModelSettingPage()));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
  }

  late Map<String, dynamic> savedVoiceConfig;
  late Map<String, dynamic> codexReadConfig;
  late Map<String, dynamic>? savedCodexConfig;
  late int codexWriteCount;
  late bool providerConfigured;
  late String providerBaseUrl;
  late int providerRevision;
  late String providerSourceType;
  late bool providerReadOnly;
  late bool providerReady;
  late int providerFetchCount;
  late List<Map<String, dynamic>> providerFetchResponse;
  late Completer<List<Map<String, dynamic>>>? providerFetchCompleter;
  late Object? providerFetchError;
  late Map<dynamic, dynamic>? lastProviderFetchArguments;

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    await VoicePlaybackCoordinator.instance.debugResetForTest();
    ModelsDevCatalogService.setCatalogForTesting(
      ModelsDevCatalogService.parseCatalog(_modelsDevCatalogJson),
    );
    codexWriteCount = 0;
    providerConfigured = true;
    providerBaseUrl = 'https://example.com/v1';
    providerRevision = 1;
    providerSourceType = 'custom';
    providerReadOnly = false;
    providerReady = true;
    providerFetchCount = 0;
    providerFetchResponse = <Map<String, dynamic>>[];
    providerFetchCompleter = null;
    providerFetchError = null;
    lastProviderFetchArguments = null;
    savedCodexConfig = null;
    codexReadConfig = <String, dynamic>{
      'remoteEnabled': true,
      'remoteBridgeUrl': 'ws://192.168.1.2:17321/codex',
      'hasRemoteBridgeToken': true,
      'remoteCwd': '/Users/name/code/project',
    };
    savedVoiceConfig = <String, dynamic>{
      'autoPlay': false,
      'voiceId': 'default_zh',
      'stylePreset': '默认',
      'customStyle': '',
    };

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          switch (call.method) {
            case 'getSceneModelCatalog':
              return <Map<String, dynamic>>[
                <String, dynamic>{
                  'sceneId': 'scene.voice',
                  'description': '负责 AI 回复文本的语音合成与播放',
                  'defaultModel': '',
                  'effectiveModel': '',
                  'effectiveProviderProfileId': '',
                  'effectiveProviderProfileName': '',
                  'boundProviderProfileId': '',
                  'boundProviderProfileName': '',
                  'transport': 'openai_compatible',
                  'configSource': 'builtin',
                  'overrideApplied': false,
                  'overrideModel': '',
                  'providerConfigured': false,
                  'bindingExists': false,
                  'bindingProfileMissing': false,
                },
                <String, dynamic>{
                  'sceneId': 'scene.compactor.context.chat',
                  'description': '负责聊天历史压缩总结',
                  'defaultModel': 'chat-compactor-model',
                  'effectiveModel': 'chat-compactor-model',
                  'effectiveProviderProfileId': '',
                  'effectiveProviderProfileName': '',
                  'boundProviderProfileId': '',
                  'boundProviderProfileName': '',
                  'transport': 'openai_compatible',
                  'configSource': 'builtin',
                  'overrideApplied': false,
                  'overrideModel': '',
                  'providerConfigured': false,
                  'bindingExists': false,
                  'bindingProfileMissing': false,
                },
              ];
            case 'getSceneModelBindings':
              return <Map<String, dynamic>>[];
            case 'listModelProviderProfiles':
              return <String, dynamic>{
                'profiles': <Map<String, dynamic>>[
                  <String, dynamic>{
                    'id': 'provider-1',
                    'name': 'Provider One',
                    'baseUrl': providerBaseUrl,
                    'apiKey': 'secret',
                    'hasApiKey': true,
                    'configured': providerConfigured,
                    'destinationConsentValid': providerConfigured,
                    'sourceType': providerSourceType,
                    'readOnly': providerReadOnly,
                    'ready': providerReady,
                    'revision': providerRevision,
                    'protocolType': 'openai_compatible',
                  },
                ],
                'editingProfileId': 'provider-1',
              };
            case 'fetchProviderModels':
              providerFetchCount += 1;
              lastProviderFetchArguments = call.arguments as Map?;
              final error = providerFetchError;
              if (error != null) {
                throw PlatformException(
                  code: 'FETCH_FAILED',
                  message: error.toString(),
                );
              }
              final pending = providerFetchCompleter;
              if (pending != null) return pending.future;
              return providerFetchResponse;
            case 'getSceneVoiceConfig':
              return savedVoiceConfig;
            case 'saveSceneVoiceConfig':
              savedVoiceConfig = Map<String, dynamic>.from(
                (call.arguments as Map).cast<String, dynamic>(),
              );
              return savedVoiceConfig;
            default:
              return null;
          }
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          switch (call.method) {
            case 'config/remote/read':
              return codexReadConfig;
            case 'config/remote/write':
              savedCodexConfig = Map<String, dynamic>.from(
                (call.arguments as Map).cast<String, dynamic>(),
              );
              codexWriteCount += 1;
              return <String, dynamic>{
                'remoteEnabled': savedCodexConfig!['remoteEnabled'],
                'remoteBridgeUrl': savedCodexConfig!['remoteBridgeUrl'],
                'hasRemoteBridgeToken':
                    (savedCodexConfig!['remoteBridgeToken'] as String)
                        .isNotEmpty ||
                    codexReadConfig['hasRemoteBridgeToken'] == true,
                'remoteCwd': savedCodexConfig!['remoteCwd'],
              };
            default:
              return null;
          }
        });
  });

  tearDown(() async {
    final pending = providerFetchCompleter;
    if (pending != null && !pending.isCompleted) {
      pending.complete(<Map<String, dynamic>>[]);
    }
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, null);
    ModelsDevCatalogService.resetForTesting();
    await VoicePlaybackCoordinator.instance.debugResetForTest();
  });

  testWidgets('scene page does not wait for metadata refresh', (tester) async {
    tester.view.physicalSize = const Size(1080, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    providerConfigured = false;
    await ModelProviderConfigService.saveCachedFetchedModels(
      profileId: 'provider-1',
      apiBase: 'https://example.com/v1',
      profileRevision: providerRevision,
      models: const [
        ProviderModelOption(id: 'scene-model', displayName: 'scene-model'),
      ],
    );
    final loader = Completer<ModelsDevCatalog>();
    addTearDown(() {
      if (!loader.isCompleted) {
        loader.complete(const ModelsDevCatalog(providers: {}));
      }
    });
    var loadCount = 0;
    ModelsDevCatalogService.setCatalogLoaderForTesting(() {
      loadCount += 1;
      return loader.future;
    });

    await tester.pumpWidget(buildTestApp(const SceneModelSettingPage()));
    for (var index = 0; index < 6; index++) {
      await tester.pump(const Duration(milliseconds: 1));
    }

    expect(find.byType(ListView), findsWidgets);
    expect(find.byType(CircularProgressIndicator), findsNothing);
    expect(find.text('Voice'), findsOneWidget);
    expect(loadCount, 1);

    loader.complete(
      ModelsDevCatalogService.parseCatalog(_modelsDevCatalogJson),
    );
    for (var index = 0; index < 4; index++) {
      await tester.pump(const Duration(milliseconds: 1));
    }
    expect(tester.takeException(), isNull);
  });

  testWidgets('scene entry shows BYOK cache without fetching provider models', (
    tester,
  ) async {
    await ModelProviderConfigService.saveCachedFetchedModels(
      profileId: 'provider-1',
      apiBase: providerBaseUrl,
      profileRevision: providerRevision,
      models: const <ProviderModelOption>[
        ProviderModelOption(id: 'cached-model', displayName: 'Cached model'),
      ],
    );

    await pumpSceneSettings(tester);

    expect(providerFetchCount, 0);
    expect(
      find.byKey(const Key('scene-model-refresh-provider-models-button')),
      findsOneWidget,
    );
    await tester.tap(
      find.byKey(
        const Key('scene-model-selector-scene.compactor.context.chat'),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('cached-model'), findsOneWidget);
  });

  testWidgets('manual BYOK refresh confirms once before fetch', (tester) async {
    providerFetchResponse = <Map<String, dynamic>>[
      <String, dynamic>{'id': 'fresh-model', 'displayName': 'Fresh model'},
    ];
    await pumpSceneSettings(tester);

    await tester.tap(
      find.byKey(const Key('scene-model-refresh-provider-models-button')),
    );
    await tester.pump();

    expect(providerFetchCount, 0);
    expect(
      find.byKey(const Key('data-destination-confirmation-dialog')),
      findsOneWidget,
    );
    expect(find.text('https://example.com:443'), findsOneWidget);
    expect(find.textContaining('/v1'), findsNothing);

    await confirmDestination(tester);

    expect(providerFetchCount, 1);
    expect(lastProviderFetchArguments?['apiBase'], providerBaseUrl);
    expect(lastProviderFetchArguments?['profileId'], 'provider-1');
    expect(lastProviderFetchArguments?['destinationConfirmed'], isTrue);
    expect(
      find.byKey(const Key('data-destination-confirmation-dialog')),
      findsNothing,
    );
    expect(
      tester
          .widget<OutlinedButton>(
            find.byKey(const Key('scene-model-refresh-provider-models-button')),
          )
          .onPressed,
      isNotNull,
    );
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('rejecting BYOK refresh performs zero fetch', (tester) async {
    await pumpSceneSettings(tester);

    await tester.tap(
      find.byKey(const Key('scene-model-refresh-provider-models-button')),
    );
    await tester.pump();
    await tester.tap(find.byKey(const Key('data-destination-cancel')));
    await tester.pumpAndSettle();

    expect(providerFetchCount, 0);
    expect(
      find.byKey(const Key('scene-model-refresh-provider-models-progress')),
      findsNothing,
    );
    expect(
      tester
          .widget<OutlinedButton>(
            find.byKey(const Key('scene-model-refresh-provider-models-button')),
          )
          .onPressed,
      isNotNull,
    );
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('changed provider revision cannot apply an old fetch result', (
    tester,
  ) async {
    final pending = Completer<List<Map<String, dynamic>>>();
    providerFetchCompleter = pending;
    await pumpSceneSettings(tester);

    await tester.tap(
      find.byKey(const Key('scene-model-refresh-provider-models-button')),
    );
    await tester.pump();
    await confirmDestinationWithoutSettling(tester);
    for (var attempt = 0; attempt < 4 && providerFetchCount == 0; attempt++) {
      await tester.pump();
    }
    expect(providerFetchCount, 1);

    providerBaseUrl = 'https://replacement.example.com/v1';
    providerRevision = 2;
    pending.complete(<Map<String, dynamic>>[
      <String, dynamic>{'id': 'stale-model', 'displayName': 'Stale model'},
    ]);
    for (var attempt = 0; attempt < 10; attempt++) {
      await tester.pump();
      final button = tester.widget<OutlinedButton>(
        find.byKey(const Key('scene-model-refresh-provider-models-button')),
      );
      if (button.onPressed != null) break;
    }
    expect(find.textContaining('部分模型列表未刷新'), findsOneWidget);

    await tester.tap(
      find.byKey(
        const Key('scene-model-selector-scene.compactor.context.chat'),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('stale-model'), findsNothing);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets(
    'manual refresh is single flight and disposal ignores completion',
    (tester) async {
      final pending = Completer<List<Map<String, dynamic>>>();
      providerFetchCompleter = pending;
      await pumpSceneSettings(tester);
      final refreshButton = tester.widget<OutlinedButton>(
        find.byKey(const Key('scene-model-refresh-provider-models-button')),
      );

      refreshButton.onPressed!();
      await tester.pump();
      await confirmDestinationWithoutSettling(tester);
      for (var attempt = 0; attempt < 4 && providerFetchCount == 0; attempt++) {
        await tester.pump();
      }
      expect(providerFetchCount, 1);
      refreshButton.onPressed!();
      await tester.pump();
      expect(providerFetchCount, 1);
      expect(
        find.byKey(const Key('data-destination-confirmation-dialog')),
        findsNothing,
      );
      expect(
        find.byKey(const Key('scene-model-refresh-provider-models-progress')),
        findsOneWidget,
      );

      await tester.pumpWidget(const MaterialApp(home: SizedBox.shrink()));
      pending.complete(<Map<String, dynamic>>[
        <String, dynamic>{'id': 'late-model', 'displayName': 'Late model'},
      ]);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 20));
      expect(providerFetchCount, 1);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('official platform catalog is not blocked by BYOK confirmation', (
    tester,
  ) async {
    providerBaseUrl = '';
    providerSourceType = 'omnibot_official';
    providerReadOnly = true;
    providerFetchResponse = <Map<String, dynamic>>[
      <String, dynamic>{'id': 'official-model'},
    ];

    await pumpSceneSettings(tester);
    expect(providerFetchCount, 1);
    expect(
      find.byKey(const Key('data-destination-confirmation-dialog')),
      findsNothing,
    );
    await tester.pump(const Duration(seconds: 3));

    await tester.tap(
      find.byKey(const Key('scene-model-refresh-provider-models-button')),
    );
    await tester.pumpAndSettle();
    expect(providerFetchCount, 2);
    expect(lastProviderFetchArguments?['destinationConfirmed'], isNot(true));
    expect(
      find.byKey(const Key('data-destination-confirmation-dialog')),
      findsNothing,
    );
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('refresh errors never display endpoint token or exception text', (
    tester,
  ) async {
    providerFetchError =
        'socket failed at https://user:token@example.com/private?key=secret';
    await pumpSceneSettings(tester);

    await tester.tap(
      find.byKey(const Key('scene-model-refresh-provider-models-button')),
    );
    await tester.pump();
    await confirmDestinationWithoutSettling(tester);
    for (var attempt = 0; attempt < 10; attempt++) {
      await tester.pump();
      final button = tester.widget<OutlinedButton>(
        find.byKey(const Key('scene-model-refresh-provider-models-button')),
      );
      if (button.onPressed != null) break;
    }

    expect(find.textContaining('user:token'), findsNothing);
    expect(find.textContaining('/private'), findsNothing);
    expect(find.textContaining('key=secret'), findsNothing);
    expect(find.textContaining('部分模型列表未刷新'), findsOneWidget);
    await tester.pump(const Duration(seconds: 3));
  });

  testWidgets('voice scene expands and saves voice settings', (tester) async {
    tester.view.physicalSize = const Size(1080, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(buildTestApp(const SceneModelSettingPage()));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(find.text('Voice'), findsOneWidget);
    expect(find.text('Compactor'), findsNothing);
    expect(find.text('Chat Compactor'), findsOneWidget);
    expect(find.text('未绑定'), findsOneWidget);
    expect(find.text('AI 响应完成后自动播放'), findsNothing);
    expect(find.byKey(const Key('voice-scene-expand-button')), findsOneWidget);

    await tester.tap(find.byKey(const Key('voice-scene-expand-button')));
    await tester.pumpAndSettle();

    expect(find.text('AI 响应完成后自动播放'), findsOneWidget);
    expect(find.byType(FlutterSwitch), findsOneWidget);
    expect(find.byType(Switch), findsNothing);
    expect(find.byKey(const Key('voice-scene-voice-id-field')), findsOneWidget);
    expect(
      find.byKey(const Key('voice-scene-custom-style-field')),
      findsOneWidget,
    );
    expect(find.text('保存语音设置'), findsNothing);
    expect(find.textContaining('建议绑定 MiMo'), findsNothing);

    await tester.enterText(
      find.byKey(const Key('voice-scene-voice-id-field')),
      'mimo_default',
    );
    await tester.pump(const Duration(milliseconds: 500));

    await tester.tap(find.byKey(const Key('voice-style-option-温柔陪伴')));
    await tester.pumpAndSettle();

    await tester.enterText(
      find.byKey(const Key('voice-scene-custom-style-field')),
      '更温柔一点',
    );
    await tester.pump(const Duration(milliseconds: 500));

    expect(savedVoiceConfig['voiceId'], 'mimo_default');
    expect(savedVoiceConfig['stylePreset'], '温柔陪伴');
    expect(savedVoiceConfig['customStyle'], '更温柔一点');

    expect(codexWriteCount, 0);
  });

  testWidgets('remote bridge save confirms destination before native write', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1080, 2200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(buildTestApp(const RemoteCodexSettingPage()));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(find.text('远程 PC Bridge'), findsWidgets);
    expect(find.textContaining('本地终端环境 Codex'), findsNothing);
    expect(find.textContaining('自定义 API'), findsNothing);
    final settingsCard = tester.widget<Container>(
      find.byKey(const Key('remote-pc-bridge-settings-card')),
    );
    expect((settingsCard.decoration! as BoxDecoration).border, isNull);

    final urlField = find.byKey(
      const Key('codex-config-remote-bridge-url-field'),
    );
    final cwdField = find.byKey(const Key('codex-config-remote-cwd-field'));
    await tester.enterText(urlField, 'wss://bridge.example.com/codex');
    await tester.enterText(cwdField, '/Users/new/project');

    expect(codexWriteCount, 0);
    await tester.tap(find.byKey(const Key('codex-config-save-button')));
    await tester.pump();
    expect(codexWriteCount, 0);
    await confirmDestination(tester);
    expect(codexWriteCount, 1);
    expect(savedCodexConfig, <String, dynamic>{
      'remoteEnabled': true,
      'remoteBridgeUrl': 'wss://bridge.example.com/codex',
      'remoteBridgeToken': '',
      'remoteCwd': '/Users/new/project',
    });
    expect(find.text('已保存。'), findsOneWidget);

    final tokenField = find.byKey(const Key('codex-config-remote-token-field'));
    await tester.enterText(tokenField, 'replacement-token');
    await tester.pump();
    final saveButton = tester.widget<FilledButton>(
      find.byKey(const Key('codex-config-save-button')),
    );
    expect(saveButton.onPressed, isNotNull);
    saveButton.onPressed!();
    await tester.pump();
    expect(codexWriteCount, 1);
    await confirmDestination(tester);
    expect(codexWriteCount, 2);
    expect(savedCodexConfig?['remoteBridgeToken'], 'replacement-token');
    expect(
      tester.widget<TextField>(tokenField).controller?.text,
      isEmpty,
      reason:
          'Native must return only credential status, never the token value.',
    );
  });
}
