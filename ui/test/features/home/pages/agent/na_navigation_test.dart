import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/router_config.dart';
import 'package:ui/features/home/pages/agent/agent_config_page.dart';
import 'package:ui/features/home/pages/agent/na_personal_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const agent = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  const plugins = MethodChannel('cn.com.omnimind.bot/PluginPlatform');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final calls = <MethodCall>[];
  var development = true, offline = false;
  var owner = 'before-owner';
  Completer<void>? firstStatusGate;
  GoRouter? router;
  setUp(() async {
    calls.clear();
    development = true;
    offline = false;
    owner = 'before-owner';
    firstStatusGate = null;
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    messenger.setMockMethodCallHandler(
      plugins,
      (call) async => call.method == 'listActions' ? [] : null,
    );
    messenger.setMockMethodCallHandler(agent, (call) async {
      calls.add(call);
      final arguments = (call.arguments as Map?) ?? {};
      final catalog = {
        'agents': [
          {
            'id': 'na-cloud',
            'name': 'Na',
            'command': 'na-cloud',
            'builtIn': true,
            'source': 'builtin',
            'status': 'offline',
            'enabled': true,
            'configurationAvailable': development,
          },
        ],
      };
      switch (call.method) {
        case 'agent/list':
          return catalog;
        case 'agent/config/read':
          if (!development) throw PlatformException(code: 'FORBIDDEN');
          return {
            'kind': 'na-cloud',
            'developmentAvailable': true,
            'connectionMode': 'development',
            'baseUrl': 'http://127.0.0.1:8787',
            'apiKey': '',
          };
        case 'agent/config/write':
          owner = 'after-owner';
          offline = false;
          return {
            'kind': 'na-cloud',
            'developmentAvailable': true,
            'connectionMode': 'development',
            'baseUrl': 'http://127.0.0.1:8787',
            'apiKey': '',
          };
        case 'config/remote/read':
          return {};
        case 'config/na/personal':
          final operation = arguments['operation'];
          if (operation == 'status' && firstStatusGate != null) {
            final gate = firstStatusGate!;
            firstStatusGate = null;
            await gate.future;
          }
          if (offline)
            throw PlatformException(
              code: 'AGENT_RUNTIME_CALL_FAILED',
              message: 'Na service is offline',
            );
          if (operation != 'status' || arguments['owner'] != null)
            expect(arguments['owner'], owner);
          return {
            'owner': owner,
            'data': switch (operation) {
              'status' => {
                'capabilities': ['profile', 'notifications', 'actions'],
              },
              'notifications.list' => {'notifications': [], 'unreadCount': 0},
              'actions.list' => {'actions': []},
              _ => <String, dynamic>{},
            },
          };
      }
      return null;
    });
  });
  tearDown(() {
    router?.dispose();
    router = null;
    messenger.setMockMethodCallHandler(agent, null);
    messenger.setMockMethodCallHandler(plugins, null);
  });
  Future<void> pump(
    WidgetTester tester, {
    String initial = '/home/agent_mode_setting',
    bool settle = true,
  }) async {
    final routes = homeRoutes
        .where(
          (route) => [
            '/home/agent_mode_setting',
            '/home/na/personal',
            '/home/na/connection',
            '/home/agent_config/:agentId',
          ].contains(route.path),
        )
        .toList();
    router = GoRouter(
      navigatorKey: GoRouterManager.rootNavigatorKey,
      initialLocation: initial,
      routes: routes,
    );
    await tester.pumpWidget(
      MaterialApp.router(
        routerConfig: router,
        theme: AppTheme.lightTheme,
        locale: const Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    );
    if (settle) {
      await tester.pumpAndSettle();
    } else {
      await tester.pump();
    }
  }

  for (final available in [false, true]) {
    testWidgets(
      'Agent Na configuration opens personal resources when development availability is $available',
      (tester) async {
        development = available;
        await pump(tester);
        final configure = find.byKey(const Key('agent-navigation-na-cloud'));
        expect(configure, findsOneWidget);
        await tester.ensureVisible(configure);
        await tester.pumpAndSettle();
        await tester.tap(configure);
        await tester.pumpAndSettle();
        expect(find.byType(NaPersonalPage), findsOneWidget);
        expect(find.byType(AgentConfigPage), findsNothing);
        expect(find.text('Na'), findsOneWidget);
        expect(
          find.byKey(const Key('na-connection-title')),
          available ? findsOneWidget : findsNothing,
        );
        expect(find.byKey(const Key('na-service-address')), findsNothing);
        expect(find.byKey(const Key('na-access-token')), findsNothing);
        expect(
          calls.where((call) => call.method == 'agent/config/read'),
          isEmpty,
        );
      },
    );
  }

  testWidgets(
    'development Na title opens connection while offline and return reacquires a fresh owner',
    (tester) async {
      offline = true;
      await pump(tester, initial: '/home/na/personal');
      expect(find.text('Na service is offline'), findsOneWidget);
      final title = find.byKey(const Key('na-connection-title'));
      expect(tester.widget<TextButton>(title).onPressed, isNotNull);
      expect(
        calls.where((call) => call.method == 'agent/config/read'),
        isEmpty,
      );
      await tester.tap(title);
      await tester.pumpAndSettle();
      expect(find.byType(AgentConfigPage), findsOneWidget);
      expect(find.byKey(const Key('na-service-address')), findsOneWidget);
      expect(find.byKey(const Key('na-access-token')), findsOneWidget);
      expect(find.byKey(const Key('na-personal-settings')), findsNothing);
      await tester.ensureVisible(find.byKey(const Key('agent-config-save')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('agent-config-save')));
      await tester.pumpAndSettle();
      final statusCount = calls
          .where(
            (call) =>
                call.method == 'config/na/personal' &&
                (call.arguments as Map)['operation'] == 'status',
          )
          .length;
      router!.pop();
      await tester.pumpAndSettle();
      expect(find.byType(NaPersonalPage), findsOneWidget);
      final statuses = calls
          .where(
            (call) =>
                call.method == 'config/na/personal' &&
                (call.arguments as Map)['operation'] == 'status',
          )
          .toList();
      expect(statuses.length, greaterThan(statusCount));
      expect((statuses.last.arguments as Map).containsKey('owner'), isFalse);
      final lastRead = calls.lastWhere(
        (call) =>
            call.method == 'config/na/personal' &&
            (call.arguments as Map)['operation'] == 'actions.list',
      );
      expect((lastRead.arguments as Map)['owner'], 'after-owner');
      expect(find.text('Na service is offline'), findsNothing);
      // The real editor's saved toast owns a two-second dismissal timer.
      await tester.pump(const Duration(seconds: 3));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
  );

  for (final available in [false, true]) {
    testWidgets(
      'legacy Na config link resolves personal resources for development availability $available',
      (tester) async {
        development = available;
        await pump(tester, initial: '/home/agent_config/na-cloud');
        expect(find.byType(NaPersonalPage), findsOneWidget);
        expect(find.byType(AgentConfigPage), findsNothing);
        expect(
          router!.routeInformationProvider.value.uri.path,
          '/home/na/personal',
        );
        expect(
          calls.where((call) => call.method == 'agent/config/read'),
          isEmpty,
        );
      },
    );
  }
  testWidgets(
    'delayed old status cannot overwrite ownership after title navigation and return',
    (tester) async {
      final oldStatus = Completer<void>();
      final freshStatus = Completer<void>();
      firstStatusGate = oldStatus;
      addTearDown(() {
        if (!oldStatus.isCompleted) oldStatus.complete();
        if (!freshStatus.isCompleted) freshStatus.complete();
      });
      await pump(tester, initial: '/home/na/personal', settle: false);
      await tester.pump();
      final title = find.byKey(const Key('na-connection-title'));
      expect(title, findsOneWidget);
      await tester.tap(title);
      await tester.pumpAndSettle();
      expect(find.byType(AgentConfigPage), findsOneWidget);
      // Keep the return's new ownerless status pending too. Releasing the old
      // reply in this exact window used to set _owner before the new reply.
      firstStatusGate = freshStatus;
      router!.pop();
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pump();
      final statuses = calls
          .where(
            (call) =>
                call.method == 'config/na/personal' &&
                (call.arguments as Map)['operation'] == 'status',
          )
          .toList();
      expect(statuses, hasLength(2));
      expect((statuses.last.arguments as Map).containsKey('owner'), isFalse);
      expect(freshStatus.isCompleted, isFalse);
      expect(
        calls.where(
          (call) =>
              call.method == 'config/na/personal' &&
              (call.arguments as Map)['operation'] == 'actions.list',
        ),
        isEmpty,
      );
      oldStatus.complete();
      await tester.pump();
      await tester.pump();
      freshStatus.complete();
      await tester.pumpAndSettle();
      expect(find.byType(NaPersonalPage), findsOneWidget);
      final resources = calls
          .where(
            (call) =>
                call.method == 'config/na/personal' &&
                (call.arguments as Map)['operation'] == 'actions.list',
          )
          .toList();
      expect(resources, hasLength(1));
      expect((resources.single.arguments as Map)['owner'], 'before-owner');
      expect(find.text('Na 页面连接已更改。'), findsNothing);
      expect(find.text('Na 页面请求已过期。'), findsNothing);
      expect(find.text('重新载入连接'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
  testWidgets(
    'production direct connection route exposes no address or token controls',
    (tester) async {
      development = false;
      await pump(tester, initial: '/home/na/connection');
      expect(find.byKey(const Key('na-service-address')), findsNothing);
      expect(find.byKey(const Key('na-access-token')), findsNothing);
      expect(
        calls.where((call) => call.method == 'agent/config/read'),
        isEmpty,
      );
    },
  );
}
