import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/authorize_setting/root_access_setting.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/root_access_service.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/settings_ui.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final calls = <MethodCall>[];
  Map<String, dynamic> snapshot = {'enabled': false, 'status': 'DISABLED'};
  Future<dynamic> Function(MethodCall)? customHandler;

  setUp(() {
    calls.clear();
    snapshot = {'enabled': false, 'status': 'DISABLED'};
    customHandler = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, (call) async {
          calls.add(call);
          if (customHandler != null) return customHandler!(call);
          switch (call.method) {
            case 'setRootEnabled':
              final enabled = (call.arguments as Map)['enabled'] == true;
              snapshot = {
                'enabled': enabled,
                'status': enabled ? 'GRANTED' : 'DISABLED',
              };
            case 'requestRootAccess':
              snapshot = {'enabled': true, 'status': 'GRANTED'};
          }
          return snapshot;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, null);
  });

  Future<void> mount(WidgetTester tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        locale: const Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: const Scaffold(body: RootAccessSetting()),
      ),
    );
    await tester.pumpAndSettle();
  }

  OmniSettingsSwitch toggle(WidgetTester tester) =>
      tester.widget<OmniSettingsSwitch>(
        find.byKey(const ValueKey('root-access-switch')),
      );

  testWidgets('default settings only read status and never request root', (
    tester,
  ) async {
    await mount(tester);
    expect(toggle(tester).value, false);
    expect(calls.map((call) => call.method), ['getRootStatus']);
    expect(find.byKey(const ValueKey('root-access-retry')), findsNothing);
  });

  testWidgets('root is enabled only after the user confirms', (tester) async {
    await mount(tester);
    toggle(tester).onToggle(true);
    await tester.pumpAndSettle();
    expect(calls.where((call) => call.method == 'setRootEnabled'), isEmpty);
    await tester.tap(find.text('启用并授权'));
    await tester.pumpAndSettle();
    expect(toggle(tester).value, true);
    expect(find.textContaining('已授权 · 优先使用 Root'), findsOneWidget);
    expect(
      calls.where((call) => call.method == 'setRootEnabled').single.arguments,
      {'enabled': true},
    );
  });

  testWidgets('denial is displayed and reauthorization requires a tap', (
    tester,
  ) async {
    snapshot = {'enabled': true, 'status': 'DENIED'};
    await mount(tester);
    expect(find.textContaining('授权被拒绝'), findsOneWidget);
    expect(calls.where((call) => call.method == 'requestRootAccess'), isEmpty);
    await tester.tap(find.text('重新申请 Root 授权'));
    await tester.pumpAndSettle();
    expect(calls.where((call) => call.method == 'requestRootAccess').length, 1);
    expect(find.textContaining('已授权 · 优先使用 Root'), findsOneWidget);
  });

  testWidgets(
    'disabling while authorization is pending ignores its late result',
    (tester) async {
      final pending = Completer<Map<String, dynamic>>();
      customHandler = (call) async {
        if (call.method == 'setRootEnabled') {
          if ((call.arguments as Map)['enabled'] == true) return pending.future;
          return {'enabled': false, 'status': 'DISABLED'};
        }
        return snapshot;
      };
      await mount(tester);
      toggle(tester).onToggle(true);
      await tester.pumpAndSettle();
      await tester.tap(find.text('启用并授权'));
      await tester.pumpAndSettle();
      expect(toggle(tester).value, true);
      expect(toggle(tester).enabled, true);
      toggle(tester).onToggle(false);
      await tester.pumpAndSettle();
      pending.complete({'enabled': true, 'status': 'GRANTED'});
      await tester.pumpAndSettle();
      expect(toggle(tester).value, false);
      expect(find.textContaining('未启用'), findsOneWidget);
    },
  );

  testWidgets('restored checking status refreshes passively', (tester) async {
    snapshot = {'enabled': true, 'status': 'CHECKING'};
    await mount(tester);
    expect(find.textContaining('正在检查授权'), findsOneWidget);
    snapshot = {'enabled': true, 'status': 'GRANTED'};
    await tester.pump(const Duration(milliseconds: 600));
    await tester.pumpAndSettle();
    expect(find.textContaining('已授权 · 优先使用 Root'), findsOneWidget);
    expect(calls.every((call) => call.method == 'getRootStatus'), true);
  });

  test('status projection distinguishes disabled, denied, and timeout', () {
    expect(RootAccessSnapshot.fromMap(null).enabled, false);
    expect(
      RootAccessSnapshot.fromMap({'enabled': false, 'status': 'GRANTED'})
          .granted,
      false,
    );
    expect(
      const RootAccessSnapshot(enabled: true, status: 'TIMED_OUT').label(false),
      '授权超时',
    );
    expect(
      const RootAccessSnapshot(enabled: true, status: 'DENIED').label(true),
      'Access denied',
    );
  });
}
