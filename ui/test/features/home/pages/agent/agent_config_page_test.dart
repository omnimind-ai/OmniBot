import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/agent/agent_config_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/predictive_back_gesture_wrapper.dart';

class _PredictiveAgentConfigRoute extends PageRouteBuilder<void> {
  _PredictiveAgentConfigRoute({required String agentId})
    : super(
        transitionDuration: const Duration(milliseconds: 300),
        reverseTransitionDuration: const Duration(milliseconds: 300),
        pageBuilder: (context, animation, secondaryAnimation) =>
            AgentConfigPage(agentId: agentId),
        transitionsBuilder: (context, animation, secondaryAnimation, child) {
          return PredictiveBackGestureWrapper(
            animation: animation,
            secondaryAnimation: secondaryAnimation,
            transitionBuilder:
                (context, animation, secondaryAnimation, child) =>
                    CupertinoPageTransition(
                      primaryRouteAnimation: animation,
                      secondaryRouteAnimation: secondaryAnimation,
                      linearTransition: false,
                      child: child,
                    ),
            child: child,
          );
        },
      );
}

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
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, null);
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

    await ModelProviderConfigService.saveCachedFetchedModels(
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
    'built-in Agent config allows predictive back to drive its route',
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
      await StorageService.setPredictiveBackEnabled(true);

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('zh'),
          home: Scaffold(
            body: Builder(
              builder: (context) => TextButton(
                onPressed: () => Navigator.of(
                  context,
                ).push(_PredictiveAgentConfigRoute(agentId: 'codex-acp')),
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

      expect(route.popGestureInProgress, isTrue);
      expect(
        tester
            .widget<PredictiveBackPageTransition>(
              find.ancestor(
                of: find.byType(AgentConfigPage),
                matching: find.byType(PredictiveBackPageTransition),
              ),
            )
            .isGestureDriven(),
        isTrue,
      );

      await _sendBackGesture(tester, 'cancelBackGesture');
      await tester.pumpAndSettle();
      expect(find.byType(AgentConfigPage), findsOneWidget);
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

  testWidgets('DeepSeek Harness config keeps file permission in composer', (
    tester,
  ) async {
    Map<String, dynamic>? saved;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          if (call.method == 'agent/list') {
            return _catalog(_agent('deepseek-harness-acp', 'DeepSeek Harness'));
          }
          if (call.method == 'agent/config/read') {
            return <String, dynamic>{
              'agentId': 'deepseek-harness-acp',
              'kind': 'deepseek-harness',
              'configPath': '~/.dsh/omnibot-acp/config.json',
              'baseUrl': 'https://api.deepseek.com',
              'model': 'deepseek-v4-pro',
              'apiKey': 'sk-old',
              'reasoningEffort': 'high',
              'permissionMode': 'read-only',
            };
          }
          if (call.method == 'agent/config/write') {
            saved = Map<String, dynamic>.from(call.arguments as Map);
            return <String, dynamic>{
              'agentId': 'deepseek-harness-acp',
              'kind': 'deepseek-harness',
              'configPath': '~/.dsh/omnibot-acp/config.json',
              ...saved!,
            };
          }
          return null;
        });

    await _pumpPage(tester, 'deepseek-harness-acp');

    expect(
      find.byKey(const ValueKey('deepseek-harness-permission-read-only')),
      findsOneWidget,
    );
    expect(
      find.textContaining('~/.dsh/omnibot-acp/config.json'),
      findsOneWidget,
    );
    await tester.tap(find.byKey(const Key('agent-config-save')));
    await tester.pumpAndSettle();

    expect(saved?['agentId'], 'deepseek-harness-acp');
    expect(saved?['reasoningEffort'], 'high');
    expect(saved?['permissionMode'], 'read-only');
    expect(saved?.containsKey('baseUrl'), isFalse);
    expect(saved?.containsKey('model'), isFalse);
    expect(saved?.containsKey('apiKey'), isFalse);
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
    'selectedAgentId': agent['id'],
    'agents': <Map<String, dynamic>>[agent],
  };
}

Map<String, dynamic> _agent(String id, String name) {
  final command = switch (id) {
    'codex-acp' => 'codex-acp',
    'deepseek-harness-acp' => 'dsh',
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
  };
}
