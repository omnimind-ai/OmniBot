import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/agent/na_notification_settings.dart';
import 'package:ui/services/agent_runtime_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const runtime = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  const permission = MethodChannel(
    'cn.com.omnimind.bot/SpecialPermissionEvent',
  );
  final calls = <MethodCall>[];
  var enabled = false, permissionGranted = true;
  var owner = 'owner-a';
  setUp(() {
    calls.clear();
    enabled = false;
    permissionGranted = true;
    owner = 'owner-a';
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(runtime, (call) async {
          calls.add(call);
          expect(call.method, 'config/na/notifications');
          final args = Map<String, dynamic>.from(call.arguments as Map);
          expect(args['agentId'], 'na-cloud');
          if (args['operation'] == 'write') {
            expect(args['owner'], owner);
            enabled = args['enabled'] == true;
          }
          return {
            'owner': owner,
            'data': {
              'enabled': enabled,
              'permissionGranted': permissionGranted,
              'connectionActive': enabled,
              'developmentMode': true,
            },
          };
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          permission,
          (call) async => permissionGranted,
        );
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(runtime, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(permission, null);
  });
  Future<void> pump(WidgetTester tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(child: NaNotificationSettings()),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets(
    'opt in requests device delivery only and preserves captured owner',
    (tester) async {
      await pump(tester);
      expect(find.textContaining('系统密钥加密'), findsOneWidget);
      await tester.tap(find.byKey(const Key('na-notifications-enabled')));
      await tester.pumpAndSettle();
      expect(enabled, isTrue);
      final write = Map<String, dynamic>.from(calls.last.arguments as Map);
      expect(write, {
        'agentId': 'na-cloud',
        'operation': 'write',
        'owner': 'owner-a',
        'enabled': true,
      });
      expect(calls.any((call) => call.method.contains('session/')), isFalse);
      expect(find.textContaining('后台连接已开启'), findsOneWidget);
    },
  );
  testWidgets(
    'denied notification permission never enables native background access',
    (tester) async {
      permissionGranted = false;
      await pump(tester);
      await tester.tap(find.byKey(const Key('na-notifications-enabled')));
      await tester.pumpAndSettle();
      expect(enabled, isFalse);
      expect(calls.length, 1);
      expect(find.textContaining('尚未允许发送通知'), findsOneWidget);
    },
  );
  testWidgets(
    'disabling remains available when the system permission was revoked',
    (tester) async {
      enabled = true;
      permissionGranted = false;
      await pump(tester);
      await tester.tap(find.byKey(const Key('na-notifications-enabled')));
      await tester.pumpAndSettle();
      expect(enabled, isFalse);
      expect((calls.last.arguments as Map)['enabled'], isFalse);
    },
  );
  testWidgets('native owner changes cannot be adopted as a successful write', (
    tester,
  ) async {
    await pump(tester);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          runtime,
          (call) async => {
            'owner': 'owner-b',
            'data': {'enabled': true},
          },
        );
    await tester.tap(find.byKey(const Key('na-notifications-enabled')));
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<SwitchListTile>(
            find.byKey(const Key('na-notifications-enabled')),
          )
          .value,
      isFalse,
    );
    expect(find.textContaining('未能更改提醒设置'), findsOneWidget);
  });
  test(
    'notification writes require an owner before any native invocation',
    () async {
      await expectLater(
        AgentRuntimeService.requestNaNotifications(enabled: true),
        throwsArgumentError,
      );
      expect(calls, isEmpty);
    },
  );
  testWidgets(
    'late settings reply after disposal does not touch another page',
    (tester) async {
      final pending = Completer<dynamic>();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(runtime, (_) => pending.future);
      await tester.pumpWidget(
        const MaterialApp(home: Scaffold(body: NaNotificationSettings())),
      );
      await tester.pumpWidget(const MaterialApp(home: Text('Other page')));
      pending.complete({
        'owner': 'old',
        'data': {'enabled': true},
      });
      await tester.pumpAndSettle();
      expect(find.text('Other page'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
}
