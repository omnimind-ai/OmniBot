import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/agent/agent_mode_setting_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/settings_detail_sheet.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const agentRuntimeChannel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          if (call.method != 'agent/list') return null;
          return <String, dynamic>{
            'selectedAgentId': 'codex-acp',
            'agents': <Map<String, dynamic>>[
              _agent('codex-acp', 'Codex', 'codex-acp', 'online'),
              _agent(
                'claude-code-acp',
                'Claude Code',
                'claude-agent-acp',
                'missing',
              ),
              _agent(
                'opencode-acp',
                'OpenCode',
                'opencode',
                'offline',
                arguments: const ['acp'],
              ),
              _agent(
                'deepseek-harness-acp',
                'DeepSeek Harness',
                'dsh-acp-demo',
                'unchecked',
                managedAdapter: true,
                lastCheckError:
                    'ACP adapter will be prepared during Initialize.',
              ),
            ],
          };
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, null);
  });

  testWidgets('shows the managed ACP Agent catalog without Gemini', (
    tester,
  ) async {
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
        home: const AgentModeSettingPage(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Codex'), findsOneWidget);
    expect(find.text('Claude Code'), findsOneWidget);
    expect(find.text('Gemini CLI'), findsNothing);
    expect(find.text('OpenCode'), findsOneWidget);
    expect(find.text('DeepSeek Harness'), findsOneWidget);
    expect(find.text('可用'), findsWidgets);
    expect(find.text('未安装'), findsOneWidget);
    expect(find.text('初始化失败'), findsOneWidget);
    expect(find.text('全部 4'), findsOneWidget);
    expect(find.text('预置 Agent'), findsOneWidget);
    expect(find.text('官方 Agent'), findsNothing);
    expect(find.text('官方'), findsNothing);
    expect(find.textContaining('统一 API'), findsNothing);
    expect(find.byType(PopupMenuButton<String>), findsNothing);
    expect(find.text('初始化检测'), findsNothing);
    expect(find.text('重新检测'), findsNWidgets(2));
    expect(find.text('准备并初始化'), findsOneWidget);
    expect(find.text('配置'), findsNWidgets(4));
    // 4 Agent 配置入口 + 1 远程 PC Bridge 入口。
    expect(find.byIcon(LucideIcons.chevronRight), findsNWidgets(5));
    expect(
      tester
          .getTopLeft(find.byKey(const Key('agent-check-deepseek-harness-acp')))
          .dy,
      lessThan(
        tester
            .getTopLeft(
              find.byKey(const Key('agent-navigation-deepseek-harness-acp')),
            )
            .dy,
      ),
    );
    expect(find.text('远程 PC Bridge'), findsOneWidget);
    expect(find.text('远程运行'), findsOneWidget);
    expect(find.text('使用'), findsNothing);
    expect(find.text('当前使用'), findsNothing);
    expect(find.text('Use Agent'), findsNothing);
    expect(find.text('Selected'), findsNothing);
  });

  testWidgets('shows Agent check results in the shared settings detail card', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          switch (call.method) {
            case 'agent/list':
              return <String, dynamic>{
                'selectedAgentId': 'codex-acp',
                'agents': <Map<String, dynamic>>[
                  _agent('codex-acp', 'Codex', 'codex-acp', 'online'),
                ],
              };
            case 'agent/test':
              expect(call.arguments, <String, Object?>{'agentId': 'codex-acp'});
              return <String, dynamic>{
                'ok': true,
                'capabilities': <String, dynamic>{
                  'prompt': true,
                  'tools': <String>['read', 'edit'],
                },
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
        home: const AgentModeSettingPage(),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('agent-check-codex-acp')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    final resultSheet = find.byKey(
      const ValueKey('agent-check-result-codex-acp'),
    );
    expect(resultSheet, findsOneWidget);
    expect(find.byType(SettingsDetailSheet), findsOneWidget);
    expect(find.byType(AlertDialog), findsNothing);
    expect(find.text('Agent 检测成功'), findsOneWidget);
    expect(find.textContaining('prompt: true'), findsOneWidget);
    expect(find.textContaining('read'), findsOneWidget);
    expect(find.text('完成'), findsNothing);
    expect(tester.getSize(resultSheet).width, 640);

    await tester.tapAt(const Offset(20, 20));
    await tester.pumpAndSettle();
    expect(resultSheet, findsNothing);
  });

  testWidgets(
    'focused custom Agent fields can be cancelled or dismissed without errors',
    (tester) async {
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
          home: const AgentModeSettingPage(),
        ),
      );
      await tester.pumpAndSettle();

      final addButton = find.byTooltip('添加自定义 ACP Agent');
      await tester.tap(addButton);
      await tester.pumpAndSettle();
      final dialog = find.byType(AlertDialog);
      final dialogFields = find.descendant(
        of: dialog,
        matching: find.byType(TextField),
      );
      await tester.tap(dialogFields.first);
      await tester.enterText(dialogFields.first, 'Custom Agent');
      await tester.tap(find.widgetWithText(TextButton, '取消'));
      await tester.pumpAndSettle();

      expect(find.text('添加自定义 ACP Agent'), findsNothing);
      expect(tester.takeException(), isNull);

      await tester.tap(addButton);
      await tester.pumpAndSettle();
      final reopenedDialogFields = find.descendant(
        of: find.byType(AlertDialog),
        matching: find.byType(TextField),
      );
      await tester.tap(reopenedDialogFields.at(1));
      await tester.enterText(reopenedDialogFields.at(1), '/bin/agent');
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();

      expect(find.text('添加自定义 ACP Agent'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
}

Map<String, dynamic> _agent(
  String id,
  String name,
  String command,
  String status, {
  List<String> arguments = const [],
  bool managedAdapter = false,
  String? lastCheckError,
}) {
  return <String, dynamic>{
    'id': id,
    'name': name,
    'description': '$name ACP Agent',
    'command': command,
    'arguments': arguments,
    'enabled': true,
    'builtIn': true,
    'source': 'official',
    'selected': id == 'codex-acp',
    'installed': status != 'missing',
    'status': status,
    'managedAdapter': managedAdapter,
    if (lastCheckError != null) 'lastCheckError': lastCheckError,
  };
}
