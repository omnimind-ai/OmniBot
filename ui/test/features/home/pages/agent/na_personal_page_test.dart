import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/agent/na_personal_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/omni_circle_button.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final calls = <Map<String, dynamic>>[];
  bool completed = false;
  bool connectionChanged = false;
  bool inventoryFailed = false;
  bool settingsOwnerChanged = false;
  bool retiredAction = false;
  int settingsRevision = 3;
  setUp(() {
    calls.clear();
    completed = false;
    connectionChanged = false;
    inventoryFailed = false;
    settingsOwnerChanged = false;
    retiredAction = false;
    settingsRevision = 3;
    messenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'agent/list') {
        return {
          'agents': [
            {
              'id': 'na-cloud',
              'name': 'Na',
              'command': 'na-cloud',
              'configurationAvailable': false,
            },
          ],
        };
      }
      if (call.method == 'config/na/local-time') {
        final args = Map<String, dynamic>.from(call.arguments as Map);
        expect(args['timezone'], 'Asia/Shanghai');
        return {'localTime': '2026-11-04T09:00', 'timezone': 'Asia/Shanghai'};
      }
      if (call.method == 'config/na/notifications') {
        return {
          'owner': 'device-owner',
          'data': {'enabled': false, 'permissionGranted': true},
        };
      }
      expect(call.method, 'config/na/personal');
      final args = Map<String, dynamic>.from(call.arguments as Map);
      calls.add(args);
      if (args['operation'] != 'status') expect(args['owner'], 'owner-1');
      final operation = args['operation'];
      if (operation == 'composio.read' && inventoryFailed) {
        throw PlatformException(
          code: 'AGENT_RUNTIME_CALL_FAILED',
          message: 'Composio provider unavailable',
        );
      }
      if (operation == 'composio.settings.update') {
        if (settingsOwnerChanged) {
          throw PlatformException(
            code: 'AGENT_RUNTIME_CALL_FAILED',
            message: 'Na 连接已变化',
          );
        }
        expect((args['payload'] as Map)['expectedRevision'], settingsRevision);
        settingsRevision++;
      }

      if (operation == 'status' && args['owner'] != null && connectionChanged) {
        throw PlatformException(
          code: 'AGENT_RUNTIME_CALL_FAILED',
          message: 'Na connection changed',
        );
      }
      final data = switch (operation) {
        'status' => {
          'capabilities': [
            'profile',
            'google',
            'composio',
            'connectors',
            'memory_search',
          ],
        },
        'notifications.list' => {'notifications': [], 'unreadCount': 0},
        'actions.list' => {
          'actions': [
            {
              'id': 'review-1',
              'operation': 'composio.GITHUB_CREATE_ISSUE',
              'summary': '创建待审核 Issue',
              'status': completed ? 'completed' : 'pending',
              if (retiredAction)
                'unavailableReason': '旧版直连操作已停用；请通过 Composio 重新准备并审核。',
              'account': {'id': 'ca_exact', 'label': '工作账号'},
              'connector': {'version': '20261029_00'},
              'args': {
                'title': 'Exact reviewed title',
                'body': '<script>literal data</script>',
              },
              if (completed)
                'result': {
                  'data': {'number': 42},
                  'truncated': false,
                },
            },
          ],
        },
        'actions.approve' => {
          'action': {'id': 'review-1', 'status': 'completed'},
        },
        'memories.list' => {
          'memories': [
            {'id': 'm1', 'key': '研究习惯', 'value': '先核对原始来源'},
          ],
        },
        'memories.search' => {
          'memories': [
            {'id': 'm1', 'key': '研究习惯', 'value': '论文先核对来源', 'truncated': true},
          ],
          'truncated': true,
          'omitted': 2,
        },
        'profile.read' => {
          'profile': {
            'user': {'timezone': 'Asia/Shanghai'},
          },
        },
        'scenarios.list' => {'scenarios': []},
        'routines.list' => {
          'routines': [
            {
              'id': 'civil-routine',
              'title': '晨间检查',
              'prompt': '检查日程，有变化才提醒',
              'enabled': true,
              'intervalMinutes': 1440.0,
              'nextRunAt': '2026-11-04T01:00:00Z',
              'schedule': {'time': '09:00', 'timezone': 'Asia/Shanghai'},
            },
          ],
        },
        'routines.update' => {
          'routine': {'id': 'civil-routine'},
        },
        'composio.settings.read' || 'composio.settings.update' => {
          'settings': {
            'enabled': true,
            'apiKeyConfigured': true,
            'configured': true,
            'revision': settingsRevision,
            'toolkits': ['github'],
            'authConfigs': {},
          },
        },
        'composio.read' => {
          'configured': true,
          'toolkits': ['github'],
          'accounts': [
            {
              'id': 'ca_exact',
              'label': '工作账号',
              'toolkit': 'github',
              'status': 'EXPIRED',
            },
          ],
        },
        'composio.catalog' => {
          'cards': [
            {
              'slug': 'slack',
              'label': 'Slack',
              'description': '消息与频道',
              'categories': ['Communication'],
              'noAuth': false,
            },
          ],
          'total': 1500,
          'nextCursor': null,
          'source': 'api',
          'complete': true,
        },
        'composio.disconnect' => {
          'disconnected': true,
          'warning': '服务商未确认撤销授权，请检查。',
        },
        _ => <String, dynamic>{},
      };
      if (operation == 'actions.approve') completed = true;
      return {'owner': 'owner-1', 'data': data};
    });
  });
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));
  Future<void> pump(WidgetTester tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        locale: const Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: const NaPersonalPage(),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets(
    'approval reviews exact account and arguments before one owner-bound execution',
    (tester) async {
      await pump(tester);
      expect(find.text('工作账号'), findsNothing);
      expect(find.text('ca_exact'), findsOneWidget);
      await tester.tap(find.text('审核并执行'));
      await tester.pumpAndSettle();
      expect(find.textContaining('Exact reviewed title'), findsOneWidget);
      expect(find.text('工具版本：20261029_00'), findsOneWidget);
      expect(
        calls.where((call) => call['operation'] == 'actions.approve'),
        isEmpty,
      );
      await tester.tap(find.text('确认执行'));
      await tester.pumpAndSettle();
      final approvals = calls.where(
        (call) => call['operation'] == 'actions.approve',
      );
      expect(approvals, hasLength(1));
      expect(approvals.single['id'], 'review-1');
      expect(approvals.single['owner'], 'owner-1');
      expect(find.text('已完成'), findsOneWidget);
      await tester.ensureVisible(find.text('执行结果'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('执行结果'));
      await tester.pumpAndSettle();
      expect(find.textContaining('"number": 42'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'an owner change while review is open cannot approve old content',
    (tester) async {
      await pump(tester);
      await tester.tap(find.text('审核并执行'));
      await tester.pumpAndSettle();
      connectionChanged = true;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      await tester.tap(find.text('确认执行'));
      await tester.pumpAndSettle();
      expect(
        calls.where((call) => call['operation'] == 'actions.approve'),
        isEmpty,
      );
      expect(find.text('Na 连接已更改，请重新载入后审核。'), findsOneWidget);
      expect(find.text('审核并执行'), findsNothing);
    },
  );

  testWidgets(
    'Chinese memory recall is bounded and truncated search cannot overwrite full memory',
    (tester) async {
      await pump(tester);
      await tester.tap(find.byKey(const Key('na-personal-tab-2')));
      await tester.pumpAndSettle();
      expect(find.text('先核对原始来源'), findsOneWidget);
      await tester.enterText(find.byKey(const Key('na-memory-search')), '论文');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();
      final search = calls
          .where((call) => call['operation'] == 'memories.search')
          .last;
      expect(search['payload'], {'query': '论文', 'limit': 20});
      expect(find.text('搜索结果已截断；清空关键词后查看完整记忆。'), findsOneWidget);
      final edit = tester.widget<TextButton>(
        find.widgetWithText(TextButton, '编辑'),
      );
      expect(edit.onPressed, isNull);
      expect(
        calls.where((call) => call['operation'] == 'memories.update'),
        isEmpty,
      );
    },
  );

  testWidgets(
    'expired app account stays visible and exact disconnection needs confirmation',
    (tester) async {
      await pump(tester);
      await tester.tap(find.byKey(const Key('na-personal-tab-1')));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.text('断开此账号'),
        250,
        scrollable: find
            .descendant(
              of: find.byKey(const ValueKey(1)),
              matching: find.byType(Scrollable),
            )
            .first,
      );
      expect(find.textContaining('授权已过期'), findsOneWidget);
      await tester.ensureVisible(find.text('断开此账号'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('断开此账号'));
      await tester.pumpAndSettle();
      expect(
        calls.where((call) => call['operation'] == 'composio.disconnect'),
        isEmpty,
      );
      await tester.tap(find.text('确认'));
      await tester.pumpAndSettle();
      final disconnections = calls.where(
        (call) => call['operation'] == 'composio.disconnect',
      );
      expect(disconnections, hasLength(1));
      expect(disconnections.single['payload'], {
        'toolkit': 'github',
        'accountId': 'ca_exact',
      });
      expect(find.text('服务商未确认撤销授权，请检查。'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'native double interval survives an unchanged routine edit without replacing its civil schedule',
    (tester) async {
      await pump(tester);
      await tester.tap(find.byKey(const Key('na-personal-tab-3')));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('编辑'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('编辑'));
      await tester.pumpAndSettle();
      expect(find.text('每天'), findsOneWidget);
      await tester.tap(find.text('保存职责'));
      await tester.pumpAndSettle();
      final update = calls
          .where((call) => call['operation'] == 'routines.update')
          .single;
      expect(update['id'], 'civil-routine');
      expect(
        (update['payload'] as Map).containsKey('intervalMinutes'),
        isFalse,
      );
      expect((update['payload'] as Map).containsKey('nextRunAt'), isFalse);
      expect((update['payload'] as Map).containsKey('schedule'), isFalse);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets('page app bar respects the Android status-bar inset', (
    tester,
  ) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(430, 900);
    tester.view.padding = const FakeViewPadding(top: 44, bottom: 24);
    addTearDown(() {
      tester.view.resetPadding();
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });
    await pump(tester);
    expect(tester.getTopLeft(find.text('Na')).dy, greaterThanOrEqualTo(44));
    expect(
      tester.getTopLeft(find.byKey(const Key('na-personal-tab-0'))).dy,
      greaterThanOrEqualTo(108),
    );
    expect(tester.takeException(), isNull);
  });
  testWidgets(
    'refresh reuses the shared glass control and selected tabs have no leading checkmark gap',
    (tester) async {
      await pump(tester);
      expect(find.byKey(const Key('na-personal-refresh')), findsOneWidget);
      expect(
        tester.widget(find.byKey(const Key('na-personal-refresh'))),
        isA<OmniCircleButton>(),
      );
      final chip = find.byKey(const Key('na-personal-tab-0'));
      expect(tester.widget<ChoiceChip>(chip).showCheckmark, isFalse);
      final label = tester.getRect(
        find.descendant(of: chip, matching: find.text('收件箱')),
      );
      final bounds = tester.getRect(chip);
      expect((label.center.dx - bounds.center.dx).abs(), lessThan(2));
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'managed phone can replace its project key without development configuration access',
    (tester) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await pump(tester);
      expect(find.byKey(const Key('na-connection-title')), findsNothing);
      await tester.tap(find.byKey(const Key('na-personal-tab-1')));
      await tester.pumpAndSettle();
      final key = find.byKey(const Key('na-composio-api-key'));
      final button = find.byKey(const Key('na-composio-settings-save'));
      expect(find.text('Composio 项目 Key'), findsOneWidget);
      expect(tester.widget<TextField>(key).enabled, isTrue);
      expect(key.hitTestable(), findsOneWidget);
      expect(button.hitTestable(), findsOneWidget);
      await tester.enterText(key, 'fixture-managed-project-key');
      await tester.tap(button);
      await tester.pumpAndSettle();
      final updates = calls
          .where((call) => call['operation'] == 'composio.settings.update')
          .toList();
      expect(updates, hasLength(1));
      expect(updates.single['owner'], 'owner-1');
      expect(updates.single['payload'], {
        'expectedRevision': 3,
        'enabled': true,
        'toolkits': ['github'],
        'authConfigs': {},
        'apiKey': 'fixture-managed-project-key',
      });
      expect(find.text('连接服务设置已保存到 Na 服务端。'), findsOneWidget);
      expect(tester.widget<TextField>(key).controller!.text, isEmpty);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'failed provider inventory still permits server-owned connection settings repair',
    (tester) async {
      inventoryFailed = true;
      await pump(tester);
      await tester.tap(find.byKey(const Key('na-personal-tab-1')));
      await tester.pumpAndSettle();
      expect(find.textContaining('Composio 账号列表暂不可用'), findsOneWidget);
      expect(find.text('授权 Google'), findsNothing);
      final key = find.byKey(const Key('na-composio-api-key'));
      expect(tester.widget<TextField>(key).obscureText, isTrue);
      await tester.enterText(key, 'write-only-new-fixture-key');
      final save = find.byKey(const Key('na-composio-settings-save'));
      await tester.ensureVisible(save);
      await tester.pumpAndSettle();
      await tester.tap(save);
      await tester.pumpAndSettle();
      final updates = calls
          .where((call) => call['operation'] == 'composio.settings.update')
          .toList();
      expect(updates, hasLength(1));
      expect(updates.single['owner'], 'owner-1');
      expect(updates.single['payload'], {
        'expectedRevision': 3,
        'enabled': true,
        'toolkits': ['github'],
        'authConfigs': {},
        'apiKey': 'write-only-new-fixture-key',
      });
      expect(find.text('连接服务设置已保存到 Na 服务端。'), findsOneWidget);
      expect(find.textContaining('Composio 账号列表暂不可用'), findsOneWidget);
      final field = tester.widget<TextField>(
        find.byKey(const Key('na-composio-api-key')),
      );
      expect(field.controller!.text, isEmpty);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'retired direct-provider action explains unavailability and cannot be approved',
    (tester) async {
      retiredAction = true;
      await pump(tester);
      expect(find.text('旧版直连操作已停用；请通过 Composio 重新准备并审核。'), findsOneWidget);
      expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, '审核并执行'))
            .onPressed,
        isNull,
      );
      expect(
        tester
            .widget<TextButton>(find.widgetWithText(TextButton, '拒绝'))
            .onPressed,
        isNotNull,
      );
      expect(
        calls.where((call) => call['operation'] == 'actions.approve'),
        isEmpty,
      );
    },
  );
  testWidgets('stale settings owner cannot save to a replacement connection', (
    tester,
  ) async {
    await pump(tester);
    await tester.tap(find.byKey(const Key('na-personal-tab-1')));
    await tester.pumpAndSettle();
    settingsOwnerChanged = true;
    final save = find.byKey(const Key('na-composio-settings-save'));
    await tester.ensureVisible(save);
    await tester.pumpAndSettle();
    await tester.tap(save);
    await tester.pumpAndSettle();
    final updates = calls
        .where((call) => call['operation'] == 'composio.settings.update')
        .toList();
    expect(updates, hasLength(1));
    expect(updates.single['owner'], 'owner-1');
    expect(find.text('Na 连接已变化'), findsOneWidget);
    expect(find.text('连接服务设置已保存到 Na 服务端。'), findsNothing);
  });
  testWidgets(
    'managed Na opens the full application catalog without development configuration access',
    (tester) async {
      await pump(tester);
      expect(find.byKey(const Key('na-connection-title')), findsNothing);
      await tester.tap(find.byKey(const Key('na-personal-tab-1')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('na-composio-browse')));
      await tester.pumpAndSettle();
      expect(find.text('所有应用'), findsOneWidget);
      expect(find.text('Slack'), findsOneWidget);
      expect(
        calls
            .where((call) => call['operation'] == 'composio.catalog')
            .single['owner'],
        'owner-1',
      );
      expect(tester.takeException(), isNull);
    },
  );
}
