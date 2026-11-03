import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/pages/agent/agent_config_page.dart';
import 'package:ui/features/home/pages/agent/agent_mode_setting_page.dart';
import 'package:ui/features/home/pages/main_navigation_page.dart';
import 'package:ui/features/home/pages/settings/settings_page.dart';
import 'package:ui/features/home/widgets/home_drawer.dart';
import 'package:ui/features/task/pages/usage_statistics/usage_statistics_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/app_update_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/flclash/navigation_dock.dart';
import 'package:ui/widgets/flclash/tab_view.dart';
import 'package:ui/widgets/omni_navigation_rail.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const assistChannel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  const agentChannel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  const eventsChannel = MethodChannel('cn.com.omnimind.bot/AgentRuntimeEvents');
  const pluginChannel = MethodChannel('cn.com.omnimind.bot/PluginPlatform');
  const mcpChannel = MethodChannel('cn.com.omnimind.bot/McpServer');
  const updateChannel = MethodChannel('cn.com.omnimind.bot/app_update');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late List<String> agentCalls;
  late List<String> assistCalls;
  GoRouter? router;

  setUp(() async {
    SharedPreferences.setMockInitialValues({
      StorageKeys.welcomeCompleted: true,
    });
    await StorageService.init();
    GoRouterManager.setInitialRoute('/home/home');
    GoRouterManager.setSubEngine(false);
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    AppUpdateService.statusNotifier.value = null;
    agentCalls = [];
    assistCalls = [];
    messenger.setMockMethodCallHandler(agentChannel, (call) async {
      agentCalls.add(call.method);
      return switch (call.method) {
        'agent/list' => {
          'agents': [
            {
              'id': 'codex-acp',
              'name': 'Codex',
              'command': 'codex-acp',
              'enabled': true,
              'builtIn': true,
              'installed': true,
              'status': 'online',
              'configurationAvailable': true,
            },
          ],
        },
        'agent/config/read' => {'kind': 'codex', 'configContent': ''},
        _ => null,
      };
    });
    messenger.setMockMethodCallHandler(eventsChannel, (call) async => null);
    messenger.setMockMethodCallHandler(
      pluginChannel,
      (call) async => call.method == 'listActions' ? <Object?>[] : null,
    );
    messenger.setMockMethodCallHandler(
      mcpChannel,
      (call) async => call.method == 'state'
          ? {'enabled': false, 'running': false, 'port': 0, 'token': ''}
          : null,
    );
    messenger.setMockMethodCallHandler(
      updateChannel,
      (call) async => switch (call.method) {
        'getBetaOptIn' => false,
        'getApkDownloadSource' => 'worker',
        _ => null,
      },
    );
    messenger.setMockMethodCallHandler(assistChannel, (call) async {
      assistCalls.add(call.method);
      return switch (call.method) {
        'getConversations' || 'agentSkillList' || 'getMessages' => <Object?>[],
        'getWorkspaceLongMemory' => {'content': ''},
        'getWorkspaceMemoryEmbeddingConfig' => {
          'enabled': false,
          'configured': false,
        },
        'getWorkspaceMemoryRollupStatus' => {'enabled': false},
        'getConversationActivitySummary' => {'activityMap': <String, int>{}},
        'getTokenUsageSummary' => {'weeklyData': <Object?>[]},
        'manageConversation' => true,
        _ => null,
      };
    });
  });

  tearDown(() {
    router?.dispose();
    router = null;
    GoRouterManager.setInitialRoute(null);
    GoRouterManager.setSubEngine(false);
    LegacyTextLocalizer.clearResolvedLocale();
    AppUpdateService.statusNotifier.value = null;
    for (final channel in [
      assistChannel,
      agentChannel,
      eventsChannel,
      pluginChannel,
      mcpChannel,
      updateChannel,
    ]) {
      messenger.setMockMethodCallHandler(channel, null);
    }
  });

  Future<void> pumpShell(
    WidgetTester tester, {
    String? tab,
    Size size = const Size(390, 844),
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    tester.view.padding = FakeViewPadding(bottom: 24);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPadding);
    GoRouterManager.setInitialRoute(
      '/home/home${tab == null ? '' : '?tab=$tab'}',
    );
    await tester.pumpWidget(
      ProviderScope(
        child: Consumer(
          builder: (context, ref, child) {
            router ??= GoRouterManager.createRouter(ref);
            return MaterialApp.router(
              routerConfig: router,
              theme: AppTheme.lightTheme,
              locale: const Locale('zh'),
              localizationsDelegates: AppLocalizations.localizationsDelegates,
              supportedLocales: AppLocalizations.supportedLocales,
            );
          },
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  Finder destination(String label) => find.descendant(
    of: find.byType(FlClashNavigationDock),
    matching: find.byWidgetPredicate(
      (widget) => widget is Semantics && widget.properties.label == label,
    ),
  );

  Future<void> select(WidgetTester tester, String label) async {
    await tester.tap(destination(label));
    await tester.pumpAndSettle();
  }

  int agentReads() =>
      agentCalls.where((method) => method == 'agent/list').length;

  testWidgets(
    'dock selection slides both root pages while the dock stays put',
    (tester) async {
      await pumpShell(tester);
      final dock = find.byType(FlClashNavigationDock);
      final dockBounds = tester.getRect(dock);
      final home = find.byType(HomeDrawer, skipOffstage: false);
      final homeLeft = tester.getTopLeft(home).dx;
      final pageWidth = tester.getSize(home).width;
      final initialAgentReads = agentReads();

      await tester.tap(destination('Agents'));
      await tester.pump();
      final incoming = find.byType(AgentModeSettingPage, skipOffstage: false);
      // A cold destination gets one layout frame before the slide clock starts.
      expect(tester.getTopLeft(home).dx, homeLeft);
      expect(tester.getTopLeft(incoming).dx, homeLeft + pageWidth);
      expect(agentReads(), initialAgentReads);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 90));
      final outgoingLeft = tester.getTopLeft(home).dx;
      final incomingLeft = tester.getTopLeft(incoming).dx;
      expect(outgoingLeft, lessThan(homeLeft));
      expect(incomingLeft, greaterThan(homeLeft));
      expect(incomingLeft, lessThan(homeLeft + pageWidth));
      expect(incomingLeft - outgoingLeft, closeTo(pageWidth, 0.1));
      expect(tester.getRect(dock), dockBounds);

      await tester.pumpAndSettle();
      expect(tester.getTopLeft(incoming).dx, homeLeft);
      expect(find.byType(AgentModeSettingPage).hitTestable(), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'four destinations open lazily and root pages have no back action',
    (tester) async {
      await pumpShell(tester);
      for (final label in ['首页', 'Agents', '轨迹', '设置']) {
        expect(destination(label), findsOneWidget);
      }
      expect(find.byIcon(LucideIcons.chevronLeft).hitTestable(), findsNothing);
      final homeAgentReads = agentReads();
      await select(tester, '设置');
      expect(find.byType(SettingsPage).hitTestable(), findsOneWidget);
      expect(
        find.byType(AgentModeSettingPage, skipOffstage: false),
        findsNothing,
      );
      expect(
        find.byType(UsageStatisticsPage, skipOffstage: false),
        findsNothing,
      );
      expect(agentReads(), homeAgentReads);
      expect(assistCalls, isNot(contains('getConversationActivitySummary')));
      expect(assistCalls, isNot(contains('getTokenUsageSummary')));
      expect(find.byIcon(LucideIcons.chevronLeft).hitTestable(), findsNothing);
      await select(tester, '轨迹');
      expect(find.byType(UsageStatisticsPage).hitTestable(), findsOneWidget);
      expect(assistCalls, contains('getConversationActivitySummary'));
      expect(assistCalls, contains('getTokenUsageSummary'));
      expect(find.byIcon(LucideIcons.chevronLeft).hitTestable(), findsNothing);
      await select(tester, 'Agents');
      expect(find.byType(AgentModeSettingPage).hitTestable(), findsOneWidget);
      expect(find.byIcon(LucideIcons.chevronLeft).hitTestable(), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  for (final size in const [Size(768, 1024), Size(1069, 668)]) {
    testWidgets('rail at $size slides pages vertically in destination order', (
      tester,
    ) async {
      await pumpShell(tester, size: size);
      final rail = find.byType(OmniNavigationRail);
      final railBounds = tester.getRect(rail);
      final home = find.byType(HomeDrawer, skipOffstage: false);
      final homeOrigin = tester.getTopLeft(home);
      final pageHeight = tester.getSize(home).height;
      final initialAgentReads = agentReads();

      // Skipping two destinations still animates only the requested pair.
      await tester.tap(destination('设置'));
      await tester.pump();
      final settings = find.byType(SettingsPage, skipOffstage: false);
      expect(tester.getTopLeft(home), homeOrigin);
      expect(tester.getTopLeft(settings), homeOrigin + Offset(0, pageHeight));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 90));
      final outgoing = tester.getTopLeft(home);
      final incoming = tester.getTopLeft(settings);
      expect(outgoing.dx, homeOrigin.dx);
      expect(incoming.dx, homeOrigin.dx);
      expect(outgoing.dy, lessThan(homeOrigin.dy));
      expect(incoming.dy, greaterThan(homeOrigin.dy));
      expect(incoming.dy, lessThan(homeOrigin.dy + pageHeight));
      expect(incoming.dy - outgoing.dy, closeTo(pageHeight, 0.1));
      expect(tester.getRect(rail), railBounds);
      expect(agentReads(), initialAgentReads);
      expect(
        find.byType(AgentModeSettingPage, skipOffstage: false),
        findsNothing,
      );
      expect(
        find.byType(UsageStatisticsPage, skipOffstage: false),
        findsNothing,
      );
      await tester.pumpAndSettle();
      expect(tester.getTopLeft(settings), homeOrigin);

      await tester.tap(destination('首页'));
      await tester.pump();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 90));
      expect(tester.getTopLeft(home).dx, homeOrigin.dx);
      expect(tester.getTopLeft(settings).dx, homeOrigin.dx);
      expect(tester.getTopLeft(home).dy, lessThan(homeOrigin.dy));
      expect(tester.getTopLeft(settings).dy, greaterThan(homeOrigin.dy));
      expect(
        tester.getTopLeft(settings).dy - tester.getTopLeft(home).dy,
        closeTo(pageHeight, 0.1),
      );
      expect(tester.getRect(rail), railBounds);
      await tester.pumpAndSettle();
      expect(tester.getTopLeft(home), homeOrigin);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets(
    'Agent configuration uses the existing route and returns to the selected tab',
    (tester) async {
      await pumpShell(tester, tab: 'agents');
      await tester.tap(find.byKey(const Key('agent-config-codex-acp')));
      await tester.pumpAndSettle();
      expect(find.byType(AgentConfigPage), findsOneWidget);
      expect(agentCalls, contains('agent/config/read'));
      expect(
        GoRouterState.of(tester.element(find.byType(AgentConfigPage))).uri.path,
        '/home/agent_config/codex-acp',
      );
      expect(find.byType(FlClashNavigationDock).hitTestable(), findsNothing);
      await tester.tap(find.byIcon(LucideIcons.chevronLeft).hitTestable());
      await tester.pumpAndSettle();
      expect(find.byType(AgentModeSettingPage).hitTestable(), findsOneWidget);
      expect(
        tester
            .widget<FlClashNavigationDock>(find.byType(FlClashNavigationDock))
            .selectedIndex,
        1,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'hidden home search does not block back and survives a tab change',
    (tester) async {
      await pumpShell(tester);
      await tester.tap(find.byKey(const ValueKey('home-search-button')));
      await tester.pumpAndSettle();
      final field = find.byKey(const ValueKey('home-search-input'));
      await tester.enterText(field, '保留搜索');
      await tester.pumpAndSettle();
      final route = ModalRoute.of(
        tester.element(find.byType(MainNavigationPage)),
      )!;
      expect(route.popDisposition, RoutePopDisposition.doNotPop);
      await select(tester, '设置');
      expect(route.popDisposition, RoutePopDisposition.bubble);
      final home = tester.widget<HomeDrawer>(
        find.byType(HomeDrawer, skipOffstage: false),
      );
      expect(home.active, isFalse);
      await select(tester, '首页');
      expect(find.text('保留搜索'), findsOneWidget);
      expect(route.popDisposition, RoutePopDisposition.doNotPop);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(find.text('保留搜索'), findsNothing);
      expect(route.popDisposition, RoutePopDisposition.bubble);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'settings keep scroll offset and allow the final row above the dock',
    (tester) async {
      await pumpShell(tester, tab: 'settings');
      final scrollable = find
          .descendant(
            of: find.byType(SettingsPage),
            matching: find.byType(Scrollable),
          )
          .first;
      final position = tester.state<ScrollableState>(scrollable).position;
      position.jumpTo(position.maxScrollExtent);
      await tester.pumpAndSettle();
      final offset = position.pixels;
      expect(offset, greaterThan(0));
      final lastRow = find.text('关于小万');
      expect(lastRow, findsOneWidget);
      expect(
        tester.getBottomLeft(lastRow).dy,
        lessThan(tester.getTopLeft(find.byType(FlClashNavigationDock)).dy),
      );
      await select(tester, 'Agents');
      await select(tester, '设置');
      final restored = tester
          .state<ScrollableState>(
            find
                .descendant(
                  of: find.byType(SettingsPage),
                  matching: find.byType(Scrollable),
                )
                .first,
          )
          .position;
      expect(restored.pixels, closeTo(offset, 0.1));
      expect(tester.takeException(), isNull);
    },
  );

  for (final size in const [
    Size(600, 900),
    Size(768, 1024),
    Size(1024, 768),
    Size(1366, 1024),
  ]) {
    testWidgets('tablet $size uses a rail and an aligned home column', (
      tester,
    ) async {
      await pumpShell(tester, size: size);
      expect(find.byType(OmniNavigationRail), findsOneWidget);
      expect(
        tester
            .widget<FlClashNavigationDock>(find.byType(FlClashNavigationDock))
            .axis,
        Axis.vertical,
      );
      final pageRect = tester.getRect(find.byType(FlClashTabView));
      expect(pageRect.left, OmniNavigationRail.width);
      expect(pageRect.width, size.width - OmniNavigationRail.width);
      final homeScaffold = find.descendant(
        of: find.byKey(const ValueKey('home-content-column')),
        matching: find.byType(Scaffold),
      );
      final homeRect = tester.getRect(homeScaffold);
      expect(homeRect.width, lessThanOrEqualTo(840));
      expect(homeRect.center.dx, closeTo(pageRect.center.dx, 0.1));
      final createRect = tester.getRect(
        find.byKey(const ValueKey('home-new-conversation')),
      );
      expect(createRect.left, greaterThan(homeRect.left));
      expect(createRect.right, lessThan(homeRect.right));
      await tester.tap(find.byKey(const ValueKey('main-navigation-rail-1')));
      await tester.pumpAndSettle();
      expect(find.byType(AgentModeSettingPage).hitTestable(), findsOneWidget);
      expect(
        tester
            .widget<OmniNavigationRail>(find.byType(OmniNavigationRail))
            .selectedIndex,
        MainNavigationTab.agents.index,
      );
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('resizing across dock and rail retains home search and owners', (
    tester,
  ) async {
    await pumpShell(tester);
    await tester.tap(find.byKey(const ValueKey('home-search-button')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const ValueKey('home-search-input')),
      '保留平板搜索',
    );
    await tester.pumpAndSettle();
    final homeState = tester.state(find.byType(HomeDrawer));
    final tabState = tester.state(find.byType(FlClashTabView));
    final initialReads = agentReads();
    for (final size in const [
      Size(768, 1024),
      Size(1366, 768),
      Size(540, 900),
      Size(1024, 768),
    ]) {
      tester.view.physicalSize = size;
      await tester.pumpAndSettle();
      expect(tester.state(find.byType(HomeDrawer)), same(homeState));
      expect(tester.state(find.byType(FlClashTabView)), same(tabState));
      expect(
        tester
            .widget<FlClashTabView>(find.byType(FlClashTabView))
            .scrollDirection,
        size.width >= 600 ? Axis.vertical : Axis.horizontal,
      );
      expect(find.text('保留平板搜索'), findsOneWidget);
      expect(agentReads(), initialReads);
      expect(tester.takeException(), isNull);
    }
  });

  testWidgets('tablet keeps navigation reachable with the keyboard visible', (
    tester,
  ) async {
    await pumpShell(tester, size: const Size(768, 1024));
    tester.view.viewInsets = const FakeViewPadding(bottom: 360);
    addTearDown(tester.view.resetViewInsets);
    await tester.tap(find.byKey(const ValueKey('home-search-button')));
    await tester.pumpAndSettle();
    for (var index = 0; index < MainNavigationTab.values.length; index++) {
      final destination = find.byKey(ValueKey('main-navigation-rail-$index'));
      expect(destination.hitTestable(), findsOneWidget);
      expect(tester.getRect(destination).bottom, lessThanOrEqualTo(664));
    }
    final homeScaffold = find.descendant(
      of: find.byKey(const ValueKey('home-content-column')),
      matching: find.byType(Scaffold),
    );
    expect(
      tester.getRect(homeScaffold).bottom,
      1024,
      reason: 'The inner Scaffold owns keyboard avoidance exactly once.',
    );
    await tester.sendKeyDownEvent(LogicalKeyboardKey.controlLeft);
    await tester.sendKeyEvent(LogicalKeyboardKey.digit2);
    await tester.sendKeyUpEvent(LogicalKeyboardKey.controlLeft);
    await tester.pumpAndSettle();
    expect(find.byType(AgentModeSettingPage).hitTestable(), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
