import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/agent/agent_config_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/models_dev_catalog_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/navigation/page_route.dart';

Future<void> _sendBackGesture(
  WidgetTester tester,
  String method, [
  Map<String, dynamic>? arguments,
]) async {
  final message = const StandardMethodCodec().encodeMethodCall(
    MethodCall(method, arguments),
  );
  await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
    'flutter/backgesture',
    message,
    (ByteData? _) {},
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const agentRuntimeChannel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  const assistCoreChannel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    ModelsDevCatalogService.setCatalogForTesting(
      const ModelsDevCatalog(providers: {}),
    );
  });

  tearDown(() {
    ModelsDevCatalogService.resetForTesting();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, null);
  });

  testWidgets(
    'Na uses the existing Agent config without device model settings',
    (tester) async {
      final providerCalls = <String>[];
      Map<String, dynamic>? saved;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
            if (call.method == 'agent/list') {
              return _catalog(_agent('na-cloud', 'Na'));
            }
            if (call.method == 'agent/config/read') {
              return {
                'kind': 'na-cloud',
                'baseUrl': 'http://127.0.0.1:8787',
                'connectionMode': 'development',
                'developmentAvailable': true,
              };
            }
            if (call.method == 'agent/config/write') {
              saved = Map<String, dynamic>.from(call.arguments as Map);
              return {
                'kind': 'na-cloud',
                'baseUrl': saved!['baseUrl'],
                'apiKey': saved!['apiKey'],
                'connectionMode': 'development',
                'developmentAvailable': true,
              };
            }
            return null;
          });
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
            providerCalls.add(call.method);
            return null;
          });
      await _pumpPage(tester, 'na-cloud');
      expect(find.byKey(const Key('na-service-address')), findsOneWidget);
      expect(find.byKey(const Key('na-access-token')), findsOneWidget);
      expect(
        find.byKey(const Key('agent-shared-provider-model-selector')),
        findsNothing,
      );
      expect(find.byType(DropdownButtonFormField<String>), findsNothing);
      await tester.enterText(
        find.byKey(const Key('na-service-address')),
        'https://na.example.test',
      );
      await tester.enterText(
        find.byKey(const Key('na-access-token')),
        'fixture-account-token',
      );
      await tester.ensureVisible(find.byKey(const Key('agent-config-save')));
      await tester.tap(find.byKey(const Key('agent-config-save')));
      await tester.pumpAndSettle();
      expect(saved, {
        'agentId': 'na-cloud',
        'connectionMode': 'development',
        'baseUrl': 'https://na.example.test',
        'apiKey': 'fixture-account-token',
      });
      expect(providerCalls, isEmpty);
    },
  );

  testWidgets(
    'managed Na uses account connection without exposing credentials',
    (tester) async {
      final calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
            calls.add(call);
            if (call.method == 'agent/list') {
              return _catalog(_agent('na-cloud', 'Na'));
            }
            if (call.method == 'agent/config/read' ||
                call.method == 'agent/config/write') {
              return {
                'kind': 'na-cloud',
                'connectionMode': 'managed',
                'signedIn': true,
                'developmentAvailable': false,
              };
            }
            if (call.method == 'agent/test') return {'connected': true};
            return null;
          });
      await _pumpPage(tester, 'na-cloud');
      expect(find.byKey(const Key('na-service-address')), findsNothing);
      expect(find.byKey(const Key('na-access-token')), findsNothing);
      expect(find.byKey(const Key('na-development-connection')), findsNothing);
      expect(find.byKey(const Key('na-account')), findsOneWidget);
      await tester.ensureVisible(find.byKey(const Key('agent-config-save')));
      await tester.tap(find.byKey(const Key('agent-config-save')));
      await tester.pumpAndSettle();
      expect(
        calls
            .singleWhere((call) => call.method == 'agent/config/write')
            .arguments,
        {'agentId': 'na-cloud', 'connectionMode': 'managed'},
      );
      expect(calls.where((call) => call.method == 'agent/test'), hasLength(1));
    },
  );

  testWidgets('production Na direct links cannot load or edit configuration', (
    tester,
  ) async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          calls.add(call);
          if (call.method == 'agent/list') {
            return _catalog({
              ..._agent('na-cloud', 'Na'),
              'configurationAvailable': false,
            });
          }
          throw StateError('Production Na must not access configuration');
        });
    await _pumpPage(tester, 'na-cloud');
    expect(find.text('Na 自动使用当前登录账号，无需配置。'), findsOneWidget);
    expect(find.byKey(const Key('na-account')), findsOneWidget);
    expect(find.byKey(const Key('agent-config-save')), findsNothing);
    expect(find.byKey(const Key('na-development-connection')), findsNothing);
    expect(find.byKey(const Key('na-service-address')), findsNothing);
    expect(find.byKey(const Key('na-access-token')), findsNothing);
    expect(calls.map((call) => call.method), ['agent/list']);
  });

  testWidgets('unavailable Na development support ignores stale test mode', (
    tester,
  ) async {
    final writes = <Map<String, dynamic>>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          if (call.method == 'agent/list') {
            return _catalog(_agent('na-cloud', 'Na'));
          }
          if (call.method == 'agent/config/write') {
            writes.add(Map<String, dynamic>.from(call.arguments as Map));
          }
          if (call.method == 'agent/test') return {'connected': true};
          return {
            'kind': 'na-cloud',
            'connectionMode': 'development',
            'developmentAvailable': false,
            'signedIn': true,
            'baseUrl': 'https://stale-test.example',
          };
        });
    await _pumpPage(tester, 'na-cloud');
    expect(find.byKey(const Key('na-development-connection')), findsNothing);
    expect(find.byKey(const Key('na-service-address')), findsNothing);
    expect(find.byKey(const Key('na-access-token')), findsNothing);
    await tester.tap(find.byKey(const Key('agent-config-save')));
    await tester.pumpAndSettle();
    expect(writes, [
      {'agentId': 'na-cloud', 'connectionMode': 'managed'},
    ]);
  });

  testWidgets('managed Na shows a useful entitlement error', (tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          if (call.method == 'agent/list') {
            return _catalog(_agent('na-cloud', 'Na'));
          }
          if (call.method == 'agent/test') {
            return {'connected': false, 'error': '当前账号尚未开通 Na 云端使用权限。'};
          }
          return {
            'kind': 'na-cloud',
            'connectionMode': 'managed',
            'signedIn': true,
            'developmentAvailable': false,
          };
        });
    await _pumpPage(tester, 'na-cloud');
    await tester.ensureVisible(find.byKey(const Key('agent-config-save')));
    await tester.tap(find.byKey(const Key('agent-config-save')));
    await tester.pumpAndSettle();
    expect(find.text('当前账号尚未开通 Na 云端使用权限。'), findsOneWidget);
    expect(find.textContaining('Bad state:'), findsNothing);
  });

  testWidgets('custom Agent launch edits survive saving and reopening', (
    tester,
  ) async {
    var stored = <String, dynamic>{
      'id': 'my-acp-agent',
      'name': 'My ACP Agent',
      'command': 'my-agent',
      'arguments': <String>[],
      'environment': <String, String>{'OLD_OPTION': 'remove me'},
      'enabled': true,
      'builtIn': false,
      'source': 'custom',
    };
    final runtimeCalls = <String>[];
    final providerCalls = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          runtimeCalls.add(call.method);
          if (call.method == 'agent/list') return _catalog(stored);
          if (call.method == 'agent/save') {
            final args = Map<String, dynamic>.from(call.arguments as Map);
            stored = Map<String, dynamic>.from(args['agent'] as Map);
            return <String, dynamic>{'catalog': _catalog(stored)};
          }
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          providerCalls.add(call.method);
          return null;
        });

    await _pumpPage(tester, 'my-acp-agent');
    expect(find.textContaining('保存不会中断当前对话'), findsOneWidget);
    const command = '/workspace/my agent/bin/acp';
    const arguments = '--config\n/workspace/my agent/settings.json';
    const environment =
        'OPENAI_API_KEY=user-test-key\n'
        'OPENAI_BASE_URL=https://user.example/v1\n'
        'CUSTOM_OPTION=  中文 = \'quotes\' \$literal  \n'
        'EMPTY_OPTION=';
    final fields = find.byType(TextField);
    expect(fields, findsNWidgets(3));
    await tester.enterText(fields.at(0), command);
    await tester.enterText(fields.at(1), arguments);
    await tester.enterText(fields.at(2), environment);
    await tester.ensureVisible(find.byKey(const Key('agent-config-save')));
    await tester.tap(find.byKey(const Key('agent-config-save')));
    await tester.pumpAndSettle();

    expect(stored['command'], command);
    expect(stored['arguments'], arguments.split('\n'));
    expect(stored['environment'], <String, String>{
      'OPENAI_API_KEY': 'user-test-key',
      'OPENAI_BASE_URL': 'https://user.example/v1',
      'CUSTOM_OPTION': '  中文 = \'quotes\' \$literal  ',
      'EMPTY_OPTION': '',
    });

    await tester.pumpWidget(const SizedBox.shrink());
    await _pumpPage(tester, 'my-acp-agent');
    expect(tester.widget<TextField>(fields.at(0)).controller!.text, command);
    expect(tester.widget<TextField>(fields.at(1)).controller!.text, arguments);
    expect(
      tester.widget<TextField>(fields.at(2)).controller!.text,
      environment,
    );
    expect(providerCalls, isEmpty);
    expect(runtimeCalls, <String>['agent/list', 'agent/save', 'agent/list']);
  });

  testWidgets('shared Provider selector saves the Agent scene binding', (
    tester,
  ) async {
    Map<String, dynamic>? savedBinding;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          if (call.method == 'agent/list') {
            return _catalog(_agent('codex-acp', 'Codex'));
          }
          if (call.method == 'agent/config/read') {
            return <String, dynamic>{
              'agentId': 'codex-acp',
              'kind': 'codex',
              'configPath': '~/.codex/config.toml',
              'authPath': '~/.codex/auth.json',
            };
          }
          if (call.method == 'disconnect') {
            return <String, dynamic>{'connected': false, 'ready': true};
          }
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          switch (call.method) {
            case 'listModelProviderProfiles':
              return <String, dynamic>{
                'editingProfileId': 'provider-1',
                'profiles': <Map<String, dynamic>>[
                  <String, dynamic>{
                    'id': 'provider-1',
                    'name': 'DeepSeek Provider',
                    'baseUrl': 'https://api.deepseek.com',
                    'apiKey': 'sk-test',
                    'configured': true,
                    'hasApiKey': true,
                    'revision': 1,
                  },
                ],
              };
            case 'getSceneModelBindings':
              return <dynamic>[];
            case 'saveSceneModelBinding':
              savedBinding = Map<String, dynamic>.from(call.arguments as Map);
              return <Map<String, dynamic>>[
                <String, dynamic>{
                  'sceneId': 'scene.dispatch.model',
                  'providerProfileId': 'provider-1',
                  'modelId': 'deepseek-v4-pro',
                },
              ];
          }
          return null;
        });

    await seedManualModels(
      profileId: 'provider-1',
      apiBase: 'https://api.deepseek.com',
      profileRevision: 1,
      models: const <ProviderModelOption>[
        ProviderModelOption(
          id: 'deepseek-v4-pro',
          displayName: 'deepseek-v4-pro',
        ),
      ],
    );

    await _pumpPage(tester, 'codex-acp');
    await tester.tap(
      find.byKey(const Key('agent-shared-provider-model-selector')),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('deepseek-v4-pro'));
    await tester.pumpAndSettle();

    expect(savedBinding?['sceneId'], 'scene.dispatch.model');
    expect(savedBinding?['providerProfileId'], 'provider-1');
    expect(savedBinding?['modelId'], 'deepseek-v4-pro');
  });

  testWidgets(
    'shared Agent model selection accepts ready OAuth without an API key',
    (tester) async {
      Map<String, dynamic>? savedBinding;
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(agentRuntimeChannel, (call) async {
        if (call.method == 'agent/list') {
          return _catalog(_agent('codex-acp', 'Codex'));
        }
        if (call.method == 'agent/config/read') return {'kind': 'codex'};
        return null;
      });
      messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
        switch (call.method) {
          case 'listModelProviderProfiles':
            return {
              'editingProfileId': 'chatgpt-1',
              'profiles': [
                {
                  'id': 'chatgpt-1',
                  'name': 'My ChatGPT',
                  'baseUrl': 'https://api.openai.com/v1',
                  'sourceType': 'chatgpt_oauth',
                  'wireApi': 'responses',
                  'configured': true,
                  'ready': true,
                  'apiKey': '',
                  'hasApiKey': false,
                  'revision': 12,
                },
              ],
            };
          case 'getSceneModelBindings':
            return <dynamic>[];
          case 'fetchProviderModels':
            expect((call.arguments as Map)['profileId'], 'chatgpt-1');
            return [
              {'id': 'gpt-test', 'displayName': 'gpt-test'},
            ];
          case 'saveSceneModelBinding':
            savedBinding = Map<String, dynamic>.from(call.arguments as Map);
            return [
              {
                'sceneId': 'scene.dispatch.model',
                'providerProfileId': 'chatgpt-1',
                'modelId': 'gpt-test',
              },
            ];
        }
        return null;
      });
      await _pumpPage(tester, 'codex-acp');
      await tester.tap(
        find.byKey(const Key('agent-shared-provider-model-selector')),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('gpt-test'));
      await tester.pumpAndSettle();
      expect(savedBinding?['sceneId'], 'scene.dispatch.model');
      expect(savedBinding?['providerProfileId'], 'chatgpt-1');
      expect(savedBinding?['modelId'], 'gpt-test');
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('Codex config page uses the shared Provider selector', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          if (call.method == 'agent/list') {
            return _catalog(_agent('codex-acp', 'Codex'));
          }
          if (call.method == 'agent/config/read') {
            return <String, dynamic>{
              'agentId': 'codex-acp',
              'kind': 'codex',
              'configPath': '~/.codex/config.toml',
              'authPath': '~/.codex/auth.json',
              'baseUrl': 'https://old.example/v1',
              'model': 'old-model',
              'apiKey': 'sk-old',
            };
          }
          return null;
        });

    await _pumpPage(tester, 'codex-acp');

    expect(find.textContaining('~/.codex/config.toml'), findsOneWidget);
    expect(find.textContaining('~/.codex/auth.json'), findsOneWidget);
    expect(
      find.byKey(const Key('agent-shared-provider-model-selector')),
      findsOneWidget,
    );
    expect(find.byKey(const Key('codex-agent-base-url')), findsNothing);
    expect(find.byKey(const Key('codex-agent-model')), findsNothing);
    expect(find.byKey(const Key('codex-agent-api-key')), findsNothing);
  });

  testWidgets(
    'built-in Agent config uses ordinary system back without a second transition',
    (tester) async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
            if (call.method == 'agent/list') {
              return _catalog(_agent('codex-acp', 'Codex'));
            }
            if (call.method == 'agent/config/read') {
              return <String, dynamic>{
                'agentId': 'codex-acp',
                'kind': 'codex',
                'configPath': '~/.codex/config.toml',
                'authPath': '~/.codex/auth.json',
                'baseUrl': 'https://api.example/v1',
                'model': 'gpt-5',
                'apiKey': 'sk-test',
              };
            }
            return null;
          });

      tester.view.physicalSize = const Size(1080, 2200);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('zh'),
          home: Scaffold(
            body: Builder(
              builder: (context) => TextButton(
                onPressed: () => Navigator.of(context).push(
                  OmniPageRoute<void>(
                    builder: (_) => const AgentConfigPage(agentId: 'codex-acp'),
                  ),
                ),
                child: const Text('open config'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('open config'));
      await tester.pumpAndSettle();

      final route = ModalRoute.of(
        tester.element(find.byType(AgentConfigPage)),
      )!;
      expect(route.popGestureEnabled, isTrue);

      await _sendBackGesture(tester, 'startBackGesture', <String, dynamic>{
        'touchOffset': <double>[0.0, 300.0],
        'progress': 0.0,
        'swipeEdge': 0,
      });
      await tester.pump();

      expect(route.popGestureInProgress, isFalse);
      await _sendBackGesture(
        tester,
        'updateBackGestureProgress',
        <String, dynamic>{
          'touchOffset': <double>[400.0, 300.0],
          'progress': 0.4,
          'swipeEdge': 0,
        },
      );
      await tester.pump();
      expect(route.animation!.value, 1);
      await _sendBackGesture(tester, 'cancelBackGesture');
      await tester.pumpAndSettle();
      expect(find.byType(AgentConfigPage), findsOneWidget);
      expect(route.animation!.value, 1);
      expect(route.popGestureInProgress, isFalse);
      await _sendBackGesture(tester, 'startBackGesture', <String, dynamic>{
        'touchOffset': <double>[0.0, 300.0],
        'progress': 0.0,
        'swipeEdge': 0,
      });
      await _sendBackGesture(tester, 'commitBackGesture');
      await tester.pumpAndSettle();
      expect(route.isActive, isFalse);
    },
    variant: TargetPlatformVariant.only(TargetPlatform.android),
  );

  testWidgets('Claude config page edits the complete settings.json content', (
    tester,
  ) async {
    Map<String, dynamic>? saved;
    const initial = '{\n  "env": {"ANTHROPIC_MODEL": "claude-sonnet"}\n}\n';
    const updated = '{\n  "env": {"ANTHROPIC_MODEL": "claude-opus"}\n}\n';
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          if (call.method == 'agent/list') {
            return _catalog(_agent('claude-code-acp', 'Claude Code'));
          }
          if (call.method == 'agent/config/read') {
            return <String, dynamic>{
              'agentId': 'claude-code-acp',
              'kind': 'json',
              'path': '~/.claude/settings.json',
              'content': initial,
            };
          }
          if (call.method == 'agent/config/write') {
            saved = Map<String, dynamic>.from(call.arguments as Map);
            return <String, dynamic>{
              'agentId': 'claude-code-acp',
              'kind': 'json',
              'path': '~/.claude/settings.json',
              'content': saved!['content'],
            };
          }
          return null;
        });

    await _pumpPage(tester, 'claude-code-acp');

    expect(find.textContaining('~/.claude/settings.json'), findsWidgets);
    expect(find.textContaining('claude-sonnet'), findsOneWidget);
    await tester.enterText(
      find.byKey(const Key('agent-raw-config-content')),
      updated,
    );
    await tester.tap(find.byKey(const Key('agent-config-save')));
    await tester.pumpAndSettle();

    expect(saved?['agentId'], 'claude-code-acp');
    expect(saved?['content'], updated);
    expect(find.textContaining('claude-opus'), findsOneWidget);
  });
}

Future<void> _pumpPage(WidgetTester tester, String agentId) async {
  tester.view.physicalSize = const Size(1080, 2200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

  await tester.pumpWidget(
    MaterialApp(
      theme: AppTheme.lightTheme,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('zh'),
      home: AgentConfigPage(agentId: agentId),
    ),
  );
  await tester.pumpAndSettle();
}

Map<String, dynamic> _catalog(Map<String, dynamic> agent) {
  return <String, dynamic>{
    'agents': <Map<String, dynamic>>[agent],
  };
}

Map<String, dynamic> _agent(String id, String name) {
  final command = switch (id) {
    'codex-acp' => 'codex-acp',
    _ => 'claude-agent-acp',
  };
  return <String, dynamic>{
    'id': id,
    'name': name,
    'description': '$name ACP Agent',
    'command': command,
    'enabled': true,
    'builtIn': true,
    'source': 'official',
    'status': 'online',
    'configurationAvailable': true,
  };
}

Future<void> seedManualModels({
  required String profileId,
  required String apiBase,
  int? profileRevision,
  required List<ProviderModelOption> models,
}) => ModelProviderConfigService.saveManualModelIds(
  profileId: profileId,
  ids: models.map((m) => m.id).toList(),
);
