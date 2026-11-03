import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/agent/agent_mode_setting_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  const plugins = MethodChannel('cn.com.omnimind.bot/PluginPlatform');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    messenger.setMockMethodCallHandler(plugins, (_) async => <dynamic>[]);
  });
  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
    messenger.setMockMethodCallHandler(plugins, null);
    expect(AgentRuntimeService.agentPackageOperations, isEmpty);
  });

  Widget page({bool english = false, double textScale = 1}) => MaterialApp(
    theme: AppTheme.lightTheme,
    locale: Locale(english ? 'en' : 'zh'),
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    builder: (context, child) => MediaQuery(
      data: MediaQuery.of(context)
          .copyWith(textScaler: TextScaler.linear(textScale)),
      child: child!,
    ),
    home: const AgentModeSettingPage(),
  );

  Map<String, dynamic> agent(
    String id, {
    bool? installed = true,
    bool uninstallable = true,
    bool managed = true,
    bool builtIn = true,
    bool enabled = true,
    bool? versionKnown,
  }) => {
    'id': id,
    'name': id,
    'command': id,
    'enabled': enabled,
    'builtIn': builtIn,
    'managedAdapter': managed,
    'uninstallable': uninstallable,
    'installed': installed,
    'status': installed == false ? 'missing' : 'online',
    'packageVersion': {
      'installedVersion': (versionKnown ?? installed == true) ? '1.0.0' : null,
      'latestVersion': '2.0.0',
      'targetVersion': '2.0.0',
      'checkedAt': 1792467900000,
      'updateAvailable': installed == true,
    },
  };

  Map<String, dynamic> catalog(List<Map<String, dynamic>> agents) => {
    'distributionId': 'ubuntu',
    'agents': agents,
  };

  void size(WidgetTester tester, {double width = 390, double height = 950}) {
    tester.view.physicalSize = Size(width, height);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
  }

  Future<void> confirmUninstall(WidgetTester tester, String id) async {
    final action = find.byKey(Key('agent-uninstall-$id'));
    // The page admits its catalog read after the landing frame. An unrelated
    // install keeps animating, so wait for the row instead of pumpAndSettle.
    for (var frame = 0; frame < 10 && action.evaluate().isEmpty; frame++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    await tester.tap(action);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    await tester.tap(find.byKey(Key('agent-uninstall-confirm-action-$id')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
  }

  testWidgets(
    'only installed managed agents with native capability show uninstall',
    (tester) async {
      const supported = [
        'pi-acp',
        'codex-acp',
        'claude-code-acp',
        'opencode-acp',
        'kimi-code-acp',
      ];
      final agents = [
        for (final id in supported) agent(id),
        agent('disabled', enabled: false),
        agent('version-only', installed: null, versionKnown: true),
        agent('missing', installed: false),
        agent('xiaowan-acp', managed: false, uninstallable: false),
        agent('na-cloud', managed: false, uninstallable: false),
        agent('custom', managed: false, builtIn: false, uninstallable: false),
        agent('old-catalog')..remove('uninstallable'),
      ];
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'agent/list') return catalog(agents);
        return null;
      });
      size(tester, height: 3000);
      await tester.pumpWidget(page());
      await tester.pumpAndSettle();
      for (final id in [...supported, 'disabled', 'version-only']) {
        expect(find.byKey(Key('agent-uninstall-$id')), findsOneWidget);
      }
      for (final id in [
        'missing',
        'xiaowan-acp',
        'na-cloud',
        'custom',
        'old-catalog',
      ]) {
        expect(find.byKey(Key('agent-config-$id')), findsOneWidget);
        expect(find.byKey(Key('agent-uninstall-$id')), findsNothing);
      }
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'confirmation can cancel and successful uninstall restores install',
    (tester) async {
      var installed = true;
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        if (call.method == 'agent/list') {
          return catalog([agent('pi-acp', installed: installed)]);
        }
        if (call.method == 'agent/uninstall') {
          expect(call.arguments, {
            'agentId': 'pi-acp',
            'distributionId': 'ubuntu',
          });
          installed = false;
          return {'ok': true, 'agent': agent('pi-acp', installed: false)};
        }
        return null;
      });
      size(tester);
      await tester.pumpWidget(page());
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('agent-uninstall-pi-acp')));
      await tester.pumpAndSettle();
      expect(find.text('卸载当前终端环境中的程序，保留配置、登录信息和历史记录。'), findsOneWidget);
      expect(calls.where((call) => call.method == 'agent/uninstall'), isEmpty);
      await tester.tap(find.byKey(const Key('agent-uninstall-cancel-pi-acp')));
      await tester.pumpAndSettle();
      expect(calls.where((call) => call.method == 'agent/uninstall'), isEmpty);
      await confirmUninstall(tester, 'pi-acp');
      await tester.pumpAndSettle();
      expect(find.text('助手已卸载'), findsOneWidget);
      expect(find.textContaining('配置、登录信息和历史记录已保留'), findsOneWidget);
      await tester.tap(
        find.byKey(const Key('agent-uninstall-result-close-pi-acp')),
      );
      await tester.pumpAndSettle();
      expect(find.text('未安装'), findsOneWidget);
      expect(find.text('安装'), findsOneWidget);
      expect(find.textContaining('1.0.0'), findsNothing);
      expect(find.byKey(const Key('agent-upgrade-all')), findsNothing);
      expect(find.byKey(const Key('agent-uninstall-pi-acp')), findsNothing);
      expect(calls.where((call) => call.method == 'agent/delete'), isEmpty);
      expect(calls.where((call) => call.method == 'agent/select'), isEmpty);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'uninstall queues behind installation and survives leaving and returning',
    (tester) async {
      final installing = Completer<Map<String, dynamic>>();
      final uninstalling = Completer<Map<String, dynamic>>();
      var installed = true;
      var uninstallCalls = 0;
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'agent/list') {
          return catalog([
            agent('other'),
            agent('pi-acp', installed: installed),
          ]);
        }
        if (call.method == 'agent/prepare') return installing.future;
        if (call.method == 'agent/uninstall') {
          uninstallCalls++;
          return uninstalling.future;
        }
        return null;
      });
      unawaited(
        AgentRuntimeService.prepareAgentInBackground(
          'other',
          distributionId: 'ubuntu',
        ),
      );
      addTearDown(() {
        if (!installing.isCompleted) installing.complete({'ok': false});
        if (!uninstalling.isCompleted) uninstalling.complete({'ok': false});
      });
      size(tester, height: 1100);
      await tester.pumpWidget(page());
      await tester.pump();
      await confirmUninstall(tester, 'pi-acp');
      expect(find.text('等待卸载'), findsOneWidget);
      expect(uninstallCalls, 0);
      expect(find.byKey(const Key('agent-uninstall-pi-acp')), findsNothing);
      expect(find.byKey(const Key('agent-check-pi-acp')), findsNothing);
      final navigation = tester.widget<IconButton>(
        find.byKey(const Key('agent-navigation-pi-acp')),
      );
      expect(navigation.onPressed, isNull);
      final checkUpdates = tester.widget<InkWell>(
        find.byKey(const Key('agent-check-updates')),
      );
      expect(checkUpdates.onTap, isNull);

      installing.complete({'ok': true});
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.text('卸载中'), findsOneWidget);
      expect(uninstallCalls, 1);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pumpWidget(page());
      for (var frame = 0; frame < 5; frame++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      expect(find.text('卸载中'), findsOneWidget);
      expect(uninstallCalls, 1);
      installed = false;
      uninstalling.complete({
        'ok': true,
        'agent': agent('pi-acp', installed: false),
      });
      await tester.pumpAndSettle();
      expect(find.text('未安装'), findsOneWidget);
      expect(find.byKey(const Key('agent-check-pi-acp')), findsOneWidget);
      expect(find.byKey(const Key('agent-uninstall-pi-acp')), findsNothing);
      expect(
        find.byKey(const Key('agent-uninstall-result-pi-acp')),
        findsNothing,
      );
      expect(tester.takeException(), isNull);
    },
  );

  for (final throws in [false, true]) {
    testWidgets(
      'failed uninstall remains retryable after partial removal, throws=$throws',
      (tester) async {
        var installed = true;
        var attempts = 0;
        messenger.setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'agent/list') {
            return catalog([agent('pi-acp', installed: installed)]);
          }
          if (call.method == 'agent/uninstall') {
            expect(call.arguments, {
              'agentId': 'pi-acp',
              'distributionId': 'ubuntu',
            });
            attempts++;
            installed = false;
            if (attempts == 1) {
              if (throws) {
                throw PlatformException(
                  code: 'uninstall_failed',
                  message: 'Permission denied',
                );
              }
              return {
                'ok': false,
                'error': 'Permission denied',
                'agent': agent('pi-acp', installed: false),
              };
            }
            return {'ok': true, 'agent': agent('pi-acp', installed: false)};
          }
          return null;
        });
        size(tester);
        await tester.pumpWidget(page());
        await tester.pumpAndSettle();
        await confirmUninstall(tester, 'pi-acp');
        await tester.pumpAndSettle();
        expect(find.text('卸载未完成'), findsOneWidget);
        expect(find.text('卸载未完成，请重试。'), findsOneWidget);
        expect(find.textContaining('Permission denied'), findsNothing);
        expect(find.byKey(const Key('agent-uninstall-pi-acp')), findsNothing);
        await tester.tap(find.byKey(const Key('agent-uninstall-retry-pi-acp')));
        await tester.pumpAndSettle();
        expect(attempts, 2);
        expect(find.text('助手已卸载'), findsOneWidget);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets('unknown distribution cannot start an uninstall', (tester) async {
    final calls = <String>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call.method);
      if (call.method == 'agent/list') {
        return {
          'agents': [agent('pi-acp')],
        };
      }
      return null;
    });
    size(tester);
    await tester.pumpWidget(page());
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('agent-uninstall-pi-acp')));
    await tester.pumpAndSettle();
    expect(find.text('请刷新 Agent 列表后重试。'), findsOneWidget);
    expect(calls, isNot(contains('agent/uninstall')));
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'external installation is explained without claiming uninstall success',
    (tester) async {
      var attempts = 0;
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'agent/list') {
          return catalog([agent('pi-acp')]);
        }
        if (call.method == 'agent/uninstall') {
          attempts++;
          return {
            'ok': false,
            'failureKind': 'agent_uninstall_external_installation',
            'error': 'Another installation remains at /opt/external/pi (probe timeout)',
            'agent': agent('pi-acp'),
          };
        }
        return null;
      });
      size(tester);
      await tester.pumpWidget(page());
      await tester.pumpAndSettle();
      await confirmUninstall(tester, 'pi-acp');
      await tester.pumpAndSettle();
      expect(attempts, 1);
      expect(find.text('卸载未完成'), findsOneWidget);
      expect(find.text('检测到其他安装或无法安全移除的文件，请在终端中检查该助手的安装位置。'), findsOneWidget);
      expect(find.text('助手已卸载'), findsNothing);
      expect(find.textContaining('等待回复超时'), findsNothing);
      expect(find.textContaining('/opt/external/pi'), findsNothing);
      expect(
        find.byKey(const Key('agent-uninstall-retry-pi-acp')),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('inline actions fit a narrow English layout with enlarged text', (
    tester,
  ) async {
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'agent/list') {
        return catalog([agent('Custom Long Agent Name')]);
      }
      return null;
    });
    size(tester, width: 320, height: 1100);
    await tester.pumpWidget(page(english: true, textScale: 1.3));
    await tester.pumpAndSettle();
    expect(find.text('Uninstall'), findsOneWidget);
    expect(find.text('Update'), findsOneWidget);
    await tester.tap(
      find.byKey(const Key('agent-uninstall-Custom Long Agent Name')),
    );
    await tester.pumpAndSettle();
    expect(
      find.textContaining(
        'Keep configuration, sign-in information, and history.',
      ),
      findsOneWidget,
    );
    expect(tester.takeException(), isNull);
  });
}
