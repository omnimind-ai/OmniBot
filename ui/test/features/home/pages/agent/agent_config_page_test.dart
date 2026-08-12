import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/agent/agent_config_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const agentRuntimeChannel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, null);
  });

  testWidgets('Codex only shows key status and blank keeps the old key', (
    tester,
  ) async {
    const oldSecret = 'SECRET_OLD_CODEX_KEY_MUST_NOT_RENDER';
    const replacementSecret = 'EXPLICIT_NEW_CODEX_KEY';
    final saves = <Map<String, dynamic>>[];
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
              'hasApiKey': true,
              'apiKey': oldSecret,
            };
          }
          if (call.method == 'agent/config/write') {
            final saved = Map<String, dynamic>.from(call.arguments as Map);
            saves.add(saved);
            return <String, dynamic>{
              'agentId': 'codex-acp',
              'kind': 'codex',
              'configPath': '~/.codex/config.toml',
              'authPath': '~/.codex/auth.json',
              'baseUrl': saved['baseUrl'],
              'model': saved['model'],
              'hasApiKey': true,
              'apiKey': oldSecret,
            };
          }
          return null;
        });

    await _pumpPage(tester, 'codex-acp');

    final apiKeyField = tester.widget<TextField>(
      find.byKey(const Key('codex-agent-api-key')),
    );
    expect(apiKeyField.controller?.text, isEmpty);
    expect(find.textContaining(oldSecret), findsNothing);
    expect(find.textContaining('已有 API Key 永不显示'), findsOneWidget);

    await tester.enterText(
      find.byKey(const Key('codex-agent-base-url')),
      'https://api.example/v1',
    );
    await tester.enterText(
      find.byKey(const Key('codex-agent-model')),
      'deepseek-chat',
    );
    await tester.tap(find.byKey(const Key('agent-config-save')));
    await tester.pumpAndSettle();

    expect(saves.single['agentId'], 'codex-acp');
    expect(saves.single['baseUrl'], 'https://api.example/v1');
    expect(saves.single['model'], 'deepseek-chat');
    expect(saves.single.containsKey('apiKey'), isFalse);
    expect(find.textContaining(oldSecret), findsNothing);

    await tester.enterText(
      find.byKey(const Key('codex-agent-api-key')),
      replacementSecret,
    );
    await tester.tap(find.byKey(const Key('agent-config-save')));
    await tester.pumpAndSettle();

    expect(saves, hasLength(2));
    expect(saves.last['apiKey'], replacementSecret);
    expect(
      tester
          .widget<TextField>(find.byKey(const Key('codex-agent-api-key')))
          .controller
          ?.text,
      isEmpty,
    );
    expect(find.textContaining(replacementSecret), findsNothing);
  });

  testWidgets(
    'Claude status never fills old content and empty cannot overwrite',
    (tester) async {
      const oldSecret = 'SECRET_ANTHROPIC_TOKEN_MUST_NOT_RENDER';
      const replacement = '{\n  "env": {"ANTHROPIC_MODEL": "claude-opus"}\n}\n';
      Map<String, dynamic>? saved;
      var writeCalls = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
            if (call.method == 'agent/list') {
              return _catalog(_agent('claude-code-acp', 'Claude Code'));
            }
            if (call.method == 'agent/config/read') {
              return <String, dynamic>{
                'agentId': 'claude-code-acp',
                'kind': 'replace-only',
                'format': 'json',
                'displayPath': '~/.claude/settings.json',
                'hasConfig': true,
                'byteCount': 123,
                'content': '{"token":"$oldSecret"}',
              };
            }
            if (call.method == 'agent/config/write') {
              writeCalls += 1;
              saved = Map<String, dynamic>.from(call.arguments as Map);
              return <String, dynamic>{
                'agentId': 'claude-code-acp',
                'kind': 'replace-only',
                'format': 'json',
                'displayPath': '~/.claude/settings.json',
                'hasConfig': true,
                'byteCount': replacement.length,
                'content': oldSecret,
              };
            }
            return null;
          });

      await _pumpPage(tester, 'claude-code-acp');

      final contentField = tester.widget<TextField>(
        find.byKey(const Key('agent-raw-config-content')),
      );
      expect(contentField.controller?.text, isEmpty);
      expect(find.textContaining(oldSecret), findsNothing);
      expect(find.textContaining('现有内容和秘密不会显示'), findsOneWidget);
      expect(find.textContaining('已配置 · 123 字节'), findsOneWidget);

      await tester.tap(find.byKey(const Key('agent-config-save')));
      await tester.pumpAndSettle();
      expect(writeCalls, 0);

      await tester.enterText(
        find.byKey(const Key('agent-raw-config-content')),
        replacement,
      );
      await tester.tap(find.byKey(const Key('agent-config-save')));
      await tester.pumpAndSettle();

      expect(writeCalls, 1);
      expect(saved?['agentId'], 'claude-code-acp');
      expect(saved?['content'], replacement);
      expect(
        tester
            .widget<TextField>(
              find.byKey(const Key('agent-raw-config-content')),
            )
            .controller
            ?.text,
        isEmpty,
      );
      expect(find.textContaining(oldSecret), findsNothing);
    },
  );

  testWidgets('built-in clear requires two confirmations and explains scope', (
    tester,
  ) async {
    var clearCalls = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          if (call.method == 'agent/list') {
            return _catalog(_agent('opencode-acp', 'OpenCode'));
          }
          if (call.method == 'agent/config/read') {
            return _replaceOnlyStatus(
              agentId: 'opencode-acp',
              format: 'jsonc',
              path: '~/.config/opencode/opencode.json',
              hasConfig: true,
              byteCount: 88,
            );
          }
          if (call.method == 'agent/config/clear') {
            clearCalls += 1;
            return _replaceOnlyStatus(
              agentId: 'opencode-acp',
              format: 'jsonc',
              path: '~/.config/opencode/opencode.json',
              hasConfig: false,
              byteCount: 0,
            );
          }
          return null;
        });

    await _pumpPage(tester, 'opencode-acp');

    await tester.tap(find.byKey(const Key('agent-config-clear')));
    await tester.pumpAndSettle();
    expect(clearCalls, 0);
    expect(find.textContaining('服务端账号和云端数据不会受影响'), findsOneWidget);

    await tester.tap(find.byKey(const Key('agent-config-clear-continue')));
    await tester.pumpAndSettle();
    expect(clearCalls, 0);
    expect(find.text('最后确认'), findsOneWidget);

    await tester.tap(find.byKey(const Key('agent-config-clear-confirm')));
    await tester.pumpAndSettle();
    expect(clearCalls, 1);
    expect(find.text('尚未配置'), findsOneWidget);
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
  return <String, dynamic>{
    'id': id,
    'name': name,
    'description': '$name ACP Agent',
    'command': id == 'codex-acp' ? 'codex-acp' : 'claude-agent-acp',
    'enabled': true,
    'builtIn': true,
    'source': 'official',
    'status': 'online',
  };
}

Map<String, dynamic> _replaceOnlyStatus({
  required String agentId,
  required String format,
  required String path,
  required bool hasConfig,
  required int byteCount,
}) {
  return <String, dynamic>{
    'agentId': agentId,
    'kind': 'replace-only',
    'format': format,
    'displayPath': path,
    'hasConfig': hasConfig,
    'byteCount': byteCount,
  };
}
