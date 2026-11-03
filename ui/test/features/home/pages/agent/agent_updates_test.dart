import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/agent/agent_mode_setting_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
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
  });

  Widget page({bool english = false}) => MaterialApp(
    theme: AppTheme.lightTheme,
    locale: Locale(english ? 'en' : 'zh'),
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    home: const AgentModeSettingPage(),
  );

  Map<String, dynamic> agent(
    String id, {
    bool checked = false,
    bool updated = false,
    bool missing = false,
    String? error,
  }) => {
    'id': id,
    'name': id,
    'command': id,
    'enabled': true,
    'builtIn': true,
    'managedAdapter': true,
    'installed': !missing,
    'status': missing ? 'missing' : 'online',
    if (checked)
      'packageVersion': {
        'installedVersion': missing
            ? null
            : updated
            ? '2.0.32'
            : '1.18.29',
        'latestVersion': error == null ? '2.0.32' : null,
        'targetVersion': error == null ? '2.0.32' : null,
        'checkedAt': 1792467900000,
        'error': error,
        'updateAvailable': !updated && !missing && error == null,
      },
  };

  for (final initializationOk in [true, false]) {
    testWidgets(
      'single upgrade reports installed version with initialization success=$initializationOk',
      (tester) async {
        var checked = false;
        var updated = false;
        final calls = <String>[];
        messenger.setMockMethodCallHandler(channel, (call) async {
          calls.add(call.method);
          if (call.method == 'agent/checkUpdates') checked = true;
          if (call.method == 'agent/prepare') {
            expect(call.arguments, {'agentId': 'OpenCode', 'force': true});
            updated = true;
            return {
              'ok': initializationOk,
              if (!initializationOk)
                'error': 'Provider credentials are missing',
              'agent': agent('OpenCode', checked: true, updated: true),
            };
          }
          if (call.method == 'agent/list' ||
              call.method == 'agent/checkUpdates') {
            return {
              'agents': [agent('OpenCode', checked: checked, updated: updated)],
            };
          }
          return null;
        });
        tester.view.physicalSize = const Size(390, 950);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        await tester.pumpWidget(page());
        await tester.pumpAndSettle();
        expect(find.text('版本未检测'), findsNothing);
        await tester.scrollUntilVisible(
          find.byKey(const Key('agent-version-details')),
          160,
        );
        await tester.tap(find.byKey(const Key('agent-version-details')));
        await tester.pumpAndSettle();
        expect(find.text('版本未检测'), findsOneWidget);
        await tester.binding.handlePopRoute();
        await tester.pumpAndSettle();
        await tester.ensureVisible(
          find.byKey(const Key('agent-check-updates')),
        );
        await tester.tap(find.byKey(const Key('agent-check-updates')));
        await tester.pumpAndSettle();
        expect(find.text('1.18.29 · 有新版本 2.0.32'), findsOneWidget);
        expect(find.textContaining('上次检查'), findsOneWidget);
        expect(calls.where((call) => call == 'agent/prepare'), isEmpty);
        await tester.scrollUntilVisible(
          find.byKey(const Key('agent-check-OpenCode')),
          -160,
        );
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('agent-check-OpenCode')));
        await tester.pumpAndSettle();
        expect(
          find.text(initializationOk ? '助手升级成功' : '版本已升级，助手未能启动'),
          findsOneWidget,
        );
        if (initializationOk) {
          expect(find.textContaining('已安装 2.0.32'), findsOneWidget);
        }
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets(
    'bulk upgrade continues after page disposal and excludes missing agents',
    (tester) async {
      final gate = Completer<Map<String, dynamic>>();
      addTearDown(() {
        if (!gate.isCompleted) gate.complete({'ok': false});
      });
      final started = <String>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'agent/list') {
          return {
            'agents': [
              agent('first', checked: true),
              agent('second', checked: true),
              agent('missing', checked: true, missing: true),
            ],
          };
        }
        if (call.method == 'agent/prepare') {
          final id = (call.arguments as Map)['agentId'] as String;
          started.add(id);
          if (id == 'first') return gate.future;
          return {'ok': true, 'agent': agent(id, checked: true, updated: true)};
        }
        return null;
      });
      await tester.pumpWidget(page());
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.byKey(const Key('agent-upgrade-all')),
        160,
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('agent-upgrade-all')));
      await tester.pump();
      expect(started, ['first']);
      await tester.pumpWidget(const SizedBox.shrink());
      gate.complete({'ok': false, 'error': 'network'});
      await tester.pumpAndSettle();
      expect(started, ['first', 'second']);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('offline checks do not display up to date or offer upgrade', (
    tester,
  ) async {
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'agent/list') {
        return {
          'agents': [
            agent('OpenCode', checked: true, error: 'registry_unavailable'),
          ],
        };
      }
      return null;
    });
    await tester.pumpWidget(page(english: true));
    await tester.pumpAndSettle();
    expect(
      find.text('1.18.29 · Update check failed; try again'),
      findsOneWidget,
    );
    expect(find.textContaining('No updates'), findsNothing);
    expect(find.byKey(const Key('agent-upgrade-all')), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
