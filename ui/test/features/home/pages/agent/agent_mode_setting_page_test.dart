import 'dart:async';

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
  const pluginPlatformChannel = MethodChannel(
    'cn.com.omnimind.bot/PluginPlatform',
  );

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
                'dsh',
                'unchecked',
                managedAdapter: true,
                lastCheckError: 'Harness 未初始化，请点击“安装官方 Harness”准备运行组件。',
              ),
            ],
          };
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(pluginPlatformChannel, (call) async {
          if (call.method == 'listActions') return <dynamic>[];
          return null;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(pluginPlatformChannel, null);
  });

  testWidgets('installer exceptions remain in a readable result sheet', (
    tester,
  ) async {
    var calls = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          if (call.method == 'agent/list') {
            return {
              'selectedAgentId': 'codex-acp',
              'agents': [
                _agent(
                  'codex-acp',
                  'Codex',
                  'codex-acp',
                  'online',
                  managedAdapter: true,
                ),
              ],
            };
          }
          if (call.method == 'agent/prepare') {
            calls++;
            expect((call.arguments as Map)['force'], isTrue);
            throw PlatformException(
              code: 'INSTALL_FAILED',
              message: 'DEPTH_ZERO_SELF_SIGNED_CERT',
            );
          }
          return null;
        });
    await tester.pumpWidget(
      MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('zh'),
        home: const AgentModeSettingPage(),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('agent-check-codex-acp')));
    await tester.pumpAndSettle();
    await tester.pump(const Duration(seconds: 10));
    expect(find.byType(SettingsDetailSheet), findsOneWidget);
    expect(find.text('安装未完成'), findsOneWidget);
    expect(find.textContaining('网络是否需要登录'), findsOneWidget);
    expect(find.textContaining('SELF_SIGNED_CERT'), findsNothing);
    expect(calls, 1);
  });

  for (final entry in <String, String>{
    'Dispatch Model Provider is not configured.': '请先在模型设置中选择一个可用模型，再启动助手。',
    'Dispatch Model Provider has no usable credentials.':
        '模型连接验证失败，请在模型设置中检查接口地址和密钥。',
    'Claude Code requires an Anthropic-compatible Provider endpoint.':
        '当前模型连接不适用于这个助手，请更换模型连接或助手。',
    'Harness preparation is already running for codex-acp.':
        '已有助手正在安装，请等待完成后再试。',
  }.entries) {
    testWidgets(
      'assistant error offers an action without internal details: ${entry.key}',
      (tester) async {
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
              if (call.method != 'agent/list') return null;
              return <String, dynamic>{
                'selectedAgentId': 'codex-acp',
                'agents': [
                  _agent(
                    'codex-acp',
                    'Codex',
                    'codex-acp',
                    'offline',
                    lastCheckError: entry.key,
                  ),
                ],
              };
            });
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
        expect(find.text(entry.key), findsNothing);
        expect(find.text(entry.value), findsOneWidget);
      },
    );
  }

  testWidgets(
    'installed initialization errors are not mislabeled Dispatch waiting and retry runs again',
    (tester) async {
      var attempts = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
            if (call.method == 'agent/list')
              return <String, dynamic>{
                'selectedAgentId': 'codex-acp',
                'agents': [
                  _agent(
                    'codex-acp',
                    'Codex',
                    'codex-acp',
                    'missing',
                    managedAdapter: true,
                  ),
                ],
              };
            if (call.method == 'agent/prepare') {
              expect((call.arguments as Map)['force'], isTrue);
              attempts++;
              return <String, dynamic>{
                'ok': attempts > 1,
                'agent': {'installed': true},
                'error': 'ACP process exited unexpectedly',
                'capabilities': <String, dynamic>{},
              };
            }
            return null;
          });
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
      await tester.pumpAndSettle();
      expect(find.text('安装已完成，但助手未能启动'), findsOneWidget);
      expect(find.textContaining('等待 Dispatch'), findsNothing);
      expect(find.text('ACP process exited unexpectedly'), findsNothing);
      expect(find.text('助手未能启动，请重试；若仍失败，可重新安装。'), findsOneWidget);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('agent-check-codex-acp')));
      await tester.pumpAndSettle();
      expect(attempts, 2);
      expect(find.text('助手安装成功'), findsOneWidget);
    },
  );

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
    expect(find.textContaining('DeepSeek Harness ACP Agent'), findsNothing);
    expect(find.textContaining('ACP initialize'), findsNothing);
    expect(find.text('可用'), findsWidgets);
    expect(find.text('未安装'), findsOneWidget);
    expect(find.text('启动失败'), findsOneWidget);
    expect(find.text('全部 4'), findsOneWidget);
    expect(find.text('预置 Agent'), findsOneWidget);
    expect(find.text('官方 Agent'), findsNothing);
    expect(find.text('官方'), findsNothing);
    expect(find.textContaining('统一 API'), findsNothing);
    expect(find.byType(PopupMenuButton<String>), findsNothing);
    expect(find.text('初始化检测'), findsNothing);
    expect(find.text('重新检测'), findsNWidgets(2));
    expect(find.text('重新安装'), findsOneWidget);
    expect(find.text('配置'), findsNWidgets(4));
    expect(find.text('安装'), findsNothing);
    // All assistants retain configuration access, including failed startup.
    // Installation stays on the separate action button.
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
    expect(find.text('助手检查通过'), findsOneWidget);
    expect(find.textContaining('prompt: true'), findsNothing);
    expect(find.text('助手已准备好，可以开始对话。'), findsOneWidget);
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

  testWidgets(
    'managed Harness installation keeps unrelated settings interactive',
    (tester) async {
      final preparation = Completer<Map<String, dynamic>>();
      var preparationCalls = 0;
      addTearDown(() {
        if (!preparation.isCompleted) {
          preparation.complete(<String, dynamic>{
            'ok': false,
            'error': 'test cleanup',
          });
        }
      });
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
            switch (call.method) {
              case 'agent/list':
                return <String, dynamic>{
                  'selectedAgentId': 'codex-acp',
                  'agents': <Map<String, dynamic>>[
                    _agent('codex-acp', 'Codex', 'codex-acp', 'online'),
                    _agent(
                      'deepseek-harness-acp',
                      'DeepSeek Harness',
                      'dsh',
                      'missing',
                      managedAdapter: true,
                    ),
                  ],
                };
              case 'agent/prepare':
                preparationCalls += 1;
                expect(call.arguments, <String, Object?>{
                  'agentId': 'deepseek-harness-acp',
                  'force': true,
                });
                return preparation.future;
            }
            return null;
          });
      tester.view.physicalSize = const Size(1080, 2200);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      Widget buildPage() => MaterialApp(
        theme: AppTheme.lightTheme,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('zh'),
        home: const AgentModeSettingPage(),
      );

      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();

      await tester.tap(
        find.byKey(const Key('agent-check-deepseek-harness-acp')),
      );
      await tester.pump();

      expect(find.text('后台安装中'), findsOneWidget);
      await tester.tap(find.byTooltip('添加自定义 ACP Agent'));
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.text('添加自定义 ACP Agent'), findsOneWidget);

      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      await tester.pumpWidget(buildPage());
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.text('后台安装中'), findsOneWidget);

      await tester.tap(
        find.byKey(const Key('agent-navigation-deepseek-harness-acp')),
      );
      await tester.pump();
      expect(preparationCalls, 1);

      preparation.complete(<String, dynamic>{
        'ok': false,
        'error': 'expected test completion',
      });
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.text('后台安装中'), findsNothing);
    },
  );

  testWidgets('renders and invokes Agent Web actions from the plugin catalog', (
    tester,
  ) async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(pluginPlatformChannel, (call) async {
          calls.add(call);
          if (call.method == 'listActions') {
            return <Map<String, dynamic>>[
              <String, dynamic>{
                'id': 'open_kimi_web',
                'pluginId': 'com.omnimind.agent-web',
                'displayName': 'Kimi Code Web',
                'description': 'Open Kimi Code Web',
                'presentation': <String, dynamic>{
                  'placement': 'agent_settings',
                  'packageId': 'kimi',
                  'label': <String, String>{
                    'zh': 'Kimi Code Web',
                    'en': 'Kimi Code Web',
                  },
                  'description': <String, String>{
                    'zh': '在系统浏览器中打开本机 Web 界面',
                    'en': 'Open the local Web UI',
                  },
                },
              },
            ];
          }
          if (call.method == 'invokeAction') {
            return <String, dynamic>{
              'success': true,
              'code': 'OPENED',
              'serviceId': 'kimi',
              'packageId': 'kimi',
              'running': true,
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

    expect(find.text('本地 Web 界面'), findsOneWidget);
    expect(find.text('Kimi Code Web'), findsOneWidget);
    expect(find.text('在系统浏览器中打开本机 Web 界面'), findsOneWidget);
    await tester.tap(
      find.byKey(
        const Key('plugin-action-button-com.omnimind.agent-web/open_kimi_web'),
      ),
    );
    await tester.pump();

    final invocation = calls.lastWhere((call) => call.method == 'invokeAction');
    expect(invocation.arguments, <String, Object?>{
      'pluginId': 'com.omnimind.agent-web',
      'actionId': 'open_kimi_web',
      'arguments': <String, dynamic>{},
    });
    expect(tester.takeException(), isNull);
  });
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
