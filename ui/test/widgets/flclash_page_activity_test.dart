import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/agent/agent_mode_setting_page.dart';
import 'package:ui/features/home/pages/settings/settings_page.dart';
import 'package:ui/features/task/pages/usage_statistics/usage_statistics_page.dart';
import 'package:ui/features/task/pages/usage_statistics/widgets/activity_dashboard_card.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/flclash/page_activity.dart';
import 'package:ui/widgets/flclash/tab_view.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const agent = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  const plugin = MethodChannel('cn.com.omnimind.bot/PluginPlatform');
  const assist = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  const mcp = MethodChannel('cn.com.omnimind.bot/McpServer');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late List<String> reads;
  Completer<Map<String, dynamic>>? heldCatalog;

  setUp(() async {
    reads = [];
    heldCatalog = null;
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    messenger.setMockMethodCallHandler(agent, (call) async {
      reads.add(call.method);
      if (call.method == 'agent/list') {
        return heldCatalog != null &&
                reads.where((read) => read == 'agent/list').length == 1
            ? heldCatalog!.future
            : Future.value(
                _catalog(heldCatalog == null ? 'Codex' : 'Fresh Codex'),
              );
      }
      return null;
    });
    messenger.setMockMethodCallHandler(plugin, (call) async {
      reads.add(call.method);
      return call.method == 'listActions' ? [] : null;
    });
    messenger.setMockMethodCallHandler(mcp, (call) async {
      reads.add('mcp/${call.method}');
      return {'enabled': false, 'running': false, 'port': 0, 'token': ''};
    });
    messenger.setMockMethodCallHandler(assist, (call) async {
      reads.add(call.method);
      return switch (call.method) {
        'getSceneModelCatalog' => [],
        'getWorkspaceMemoryEmbeddingConfig' => {
          'enabled': false,
          'configured': false,
        },
        'getWorkspaceMemoryRollupStatus' => {'enabled': false},
        'getConversationActivitySummary' => {
          'activityMap': <String, int>{},
          'totalConversations': 13,
          'currentStreak': 0,
        },
        'getTokenUsageSummary' => {
          'weeklyData': [
            for (final start in (call.arguments as Map)['weekStartsMs'] as List)
              {'weekStartMs': start, 'modelTokens': <String, int>{}},
          ],
        },
        _ => null,
      };
    });
  });
  tearDown(() {
    LegacyTextLocalizer.clearResolvedLocale();
    for (final channel in [agent, plugin, assist, mcp]) {
      messenger.setMockMethodCallHandler(channel, null);
    }
  });

  testWidgets(
    'real cold tabs issue no native reads during preparation or slide',
    (tester) async {
      final selected = ValueNotifier(0);
      addTearDown(selected.dispose);
      await tester.pumpWidget(_pages(selected));
      expect(reads, isEmpty);
      for (final index in [1, 2, 3]) {
        final before = List<String>.of(reads);
        selected.value = index;
        await tester.pump();
        await tester.pump();
        expect(reads, before);
        await tester.pump(const Duration(milliseconds: 150));
        expect(reads, before);
        if (index == 3) {
          // Static rows travel with the page, rather than appearing on landing.
          expect(
            find.descendant(
              of: find.byType(SettingsPage),
              matching: find.byType(ListView),
            ),
            findsOneWidget,
          );
        }
        await tester.pumpAndSettle();
        expect(reads.length, greaterThan(before.length));
      }
      expect(reads.where((read) => read == 'agent/list').length, 1);
      expect(
        reads.where((read) => read == 'getConversationActivitySummary').length,
        1,
      );
      expect(reads.where((read) => read == 'getTokenUsageSummary').length, 1);
      expect(reads.where((read) => read == 'mcp/state').length, 1);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'abandoned cold page does not load and cached settings stays built',
    (tester) async {
      final selected = ValueNotifier(0);
      addTearDown(selected.dispose);
      await tester.pumpWidget(_pages(selected));
      selected.value = 1;
      await tester.pump();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 60));
      selected.value = 0;
      await tester.pumpAndSettle();
      expect(reads, isEmpty);
      selected.value = 3;
      await tester.pumpAndSettle();
      final settingsList = find.descendant(
        of: find.byType(SettingsPage),
        matching: find.byType(ListView),
      );
      final original = tester.element(settingsList);
      selected.value = 0;
      await tester.pumpAndSettle();
      final before = List<String>.of(reads);
      selected.value = 3;
      await tester.pump();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      expect(reads, before);
      expect(tester.element(settingsList), same(original));
      await tester.pumpAndSettle();
      expect(reads.where((read) => read == 'agent/list'), isEmpty);
      expect(reads.where((read) => read == 'mcp/state').length, 2);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('late catalog stays unpublished and reentry revalidates once', (
    tester,
  ) async {
    heldCatalog = Completer<Map<String, dynamic>>();
    final selected = ValueNotifier(0);
    addTearDown(selected.dispose);
    await tester.pumpWidget(_pages(selected));
    selected.value = 1;
    await tester.pump();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump();
    await tester.pump();
    expect(reads.where((read) => read == 'agent/list').length, 1);
    selected.value = 3;
    await tester.pump();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 80));
    heldCatalog!.complete(_catalog('Late Codex'));
    await tester.pump();
    await tester.pump();
    expect(find.text('Late Codex', skipOffstage: false), findsNothing);
    await tester.pumpAndSettle();
    expect(find.text('Late Codex', skipOffstage: false), findsNothing);
    selected.value = 1;
    await tester.pump();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(find.text('Late Codex', skipOffstage: false), findsNothing);
    await tester.pumpAndSettle();
    expect(find.text('Fresh Codex').hitTestable(), findsOneWidget);
    expect(find.text('Late Codex', skipOffstage: false), findsNothing);
    expect(reads.where((read) => read == 'agent/list').length, 2);
    expect(tester.takeException(), isNull);
  });

  testWidgets('visited activity keeps its chart throughout the next slide', (
    tester,
  ) async {
    final selected = ValueNotifier(0);
    addTearDown(selected.dispose);
    await tester.pumpWidget(_pages(selected));
    selected.value = 2;
    await tester.pumpAndSettle();
    final card = tester.state(find.byType(ActivityDashboardCard));
    expect(find.text('13').hitTestable(), findsOneWidget);
    selected.value = 0;
    await tester.pumpAndSettle();
    final before = List<String>.of(reads);
    selected.value = 2;
    await tester.pump();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(reads, before);
    expect(tester.state(find.byType(ActivityDashboardCard)), same(card));
    final content = tester.widget<Padding>(
      find.byKey(const ValueKey('usage-statistics-content')),
    );
    expect(content.child, isA<FadeTransition>());
    await tester.pumpAndSettle();
    expect(
      reads.where((read) => read == 'getConversationActivitySummary').length,
      2,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'same-owner reads coalesce and queued unselected pages never load',
    (tester) async {
      final selected = ValueNotifier(0);
      final built = <int>[];
      final calls = <int>[];
      addTearDown(selected.dispose);
      await tester.pumpWidget(
        MaterialApp(
          home: ValueListenableBuilder<int>(
            valueListenable: selected,
            builder: (_, index, _) => FlClashTabView(
              selectedIndex: index,
              itemCount: 4,
              builder: (_, page, active) => page == 0
                  ? const SizedBox.expand()
                  : _Probe(
                      index: page,
                      active: active,
                      calls: calls,
                      built: built,
                    ),
            ),
          ),
        ),
      );
      for (final index in [3, 1, 2, 0, 3]) {
        selected.value = index;
        await tester.pump();
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 25));
        expect(calls, isEmpty);
      }
      await tester.pumpAndSettle();
      expect(calls, [3]);
      expect(built.toSet(), {3});
      await tester.pumpWidget(const SizedBox());
      await tester.pump();
      await tester.pump();
      expect(tester.takeException(), isNull);
    },
  );
}

Map<String, dynamic> _catalog(String name) => {
  'agents': [
    {
      'id': 'codex-acp',
      'name': name,
      'command': 'codex-acp',
      'enabled': true,
      'installed': true,
      'status': 'online',
      'builtIn': true,
    },
  ],
};
Widget _pages(ValueNotifier<int> selected) => MaterialApp(
  theme: AppTheme.lightTheme,
  locale: const Locale('zh'),
  localizationsDelegates: AppLocalizations.localizationsDelegates,
  supportedLocales: AppLocalizations.supportedLocales,
  home: ValueListenableBuilder<int>(
    valueListenable: selected,
    builder: (_, index, _) => FlClashTabView(
      selectedIndex: index,
      itemCount: 4,
      builder: (_, page, active) => switch (page) {
        1 => AgentModeSettingPage(embedded: true, active: active),
        2 => UsageStatisticsPage(embedded: true, active: active),
        3 => SettingsPage(embedded: true, active: active),
        _ => const Material(child: Center(child: Text('Home'))),
      },
    ),
  ),
);

class _Probe extends StatefulWidget {
  const _Probe({
    required this.index,
    required this.active,
    required this.calls,
    required this.built,
  });
  final int index;
  final bool active;
  final List<int> calls;
  final List<int> built;
  @override
  State<_Probe> createState() => _ProbeState();
}

class _ProbeState extends State<_Probe> with FlClashPageLoadMixin {
  void load() {
    unawaited(
      runPageLoad(#catalog, () async {
        widget.calls.add(widget.index);
      }),
    );
  }

  @override
  void initState() {
    super.initState();
    widget.built.add(widget.index);
    load();
    load();
  }

  @override
  void didUpdateWidget(_Probe oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.active && !oldWidget.active) load();
  }

  @override
  Widget build(BuildContext context) => const SizedBox.expand();
}
