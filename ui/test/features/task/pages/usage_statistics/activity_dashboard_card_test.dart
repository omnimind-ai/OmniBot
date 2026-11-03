import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/task/pages/usage_statistics/usage_statistics_page.dart';
import 'package:ui/features/task/pages/usage_statistics/widgets/activity_dashboard_card.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/widgets/omni_root_toolbar.dart';

void main() {
  const assistCoreChannel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
  });

  tearDown(() {
    LegacyTextLocalizer.clearResolvedLocale();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, null);
  });

  testWidgets(
    'shows user message counts, per-model token, and cache statistics',
    (tester) async {
      final now = DateTime.now();
      final today = DateTime(now.year, now.month, now.day);
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
            switch (call.method) {
              case 'getConversationActivitySummary':
                return <String, Object?>{
                  'activityMap': {
                    _dateKey(today): 1,
                    _dateKey(today.subtract(const Duration(days: 1))): 1,
                  },
                  'totalConversations': 2,
                  'currentStreak': 2,
                };
              case 'getTokenUsageSummary':
                final arguments = call.arguments as Map;
                return <String, Object?>{
                  'weeklyData': [
                    for (final start in arguments['weekStartsMs'] as List)
                      {
                        'weekStartMs': start,
                        'modelTokens':
                            start == (arguments['weekStartsMs'] as List).last
                            ? {'gpt-4o': 300, 'qwen3': 600}
                            : <String, int>{},
                        'cachedTokens':
                            start == (arguments['weekStartsMs'] as List).last
                            ? 400
                            : 0,
                      },
                  ],
                  'totalCached': 400,
                  'modelTotals': {'gpt-4o': 300, 'qwen3': 600},
                  'modelOrder': ['qwen3', 'gpt-4o'],
                };
              default:
                return null;
            }
          });

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: SizedBox(
              width: 375,
              child: ActivityDashboardCard(weeksToShow: 4),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('900'), findsOneWidget);
      expect(find.text('tokens'), findsOneWidget);
      expect(find.text('400'), findsOneWidget);
      expect(find.text('缓存'), findsOneWidget);
      final content = tester.widget<Padding>(
        find.byKey(const ValueKey('usage-statistics-content')),
      );
      expect(content.padding, const EdgeInsets.symmetric(horizontal: 22));
      expect(content.child, isA<FadeTransition>());

      await tester.tap(find.text('Token'));
      await tester.pumpAndSettle();

      expect(find.text('qwen3'), findsOneWidget);
      expect(find.text('gpt-4o'), findsOneWidget);
      expect(find.text('67%'), findsOneWidget);
      expect(find.text('33%'), findsOneWidget);
      expect(find.text('openai/gpt-4o'), findsNothing);
    },
  );

  testWidgets(
    'token read failure shows retry and keeps conversation statistics',
    (tester) async {
      var tokenReads = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
            if (call.method == 'getConversationActivitySummary') {
              return _conversationSummary();
            }
            if (call.method == 'getTokenUsageSummary') {
              if (++tokenReads == 1) {
                throw PlatformException(code: 'READ_FAILED');
              }
              return _tokenSummary(call, 725);
            }
            return null;
          });

      await tester.pumpWidget(_dashboard());
      await tester.pumpAndSettle();
      expect(find.text('13'), findsOneWidget);
      expect(find.text('—'), findsOneWidget);
      expect(find.text('0'), findsNothing);
      expect(find.text('暂无 Token 消耗数据'), findsNothing);

      await tester.tap(find.text('Token'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Token 统计加载失败，点击重试'));
      await tester.pumpAndSettle();

      expect(tokenReads, 2);
      expect(find.text('Token 统计加载失败，点击重试'), findsNothing);
      expect(find.text('725'), findsOneWidget);
      expect(find.text('model'), findsOneWidget);
      expect(find.text('13'), findsOneWidget);
    },
  );

  testWidgets(
    'conversation read failure retries without hiding token statistics',
    (tester) async {
      var conversationReads = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
            if (call.method == 'getConversationActivitySummary') {
              if (++conversationReads == 1) {
                throw PlatformException(code: 'READ_FAILED');
              }
              return _conversationSummary();
            }
            if (call.method == 'getTokenUsageSummary') {
              return _tokenSummary(call, 725);
            }
            return null;
          });

      await tester.pumpWidget(_dashboard());
      await tester.pumpAndSettle();
      expect(find.text('725'), findsOneWidget);
      expect(find.text('—'), findsNWidgets(2));
      expect(find.text('0'), findsNothing);

      await tester.tap(find.text('对话统计加载失败，点击重试'));
      await tester.pumpAndSettle();

      expect(conversationReads, 2);
      expect(find.text('对话统计加载失败，点击重试'), findsNothing);
      expect(find.text('13'), findsOneWidget);
      expect(find.text('725'), findsOneWidget);
    },
  );

  testWidgets('returning to the foreground refreshes both statistics', (
    tester,
  ) async {
    var conversationReads = 0;
    var tokenReads = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          if (call.method == 'getConversationActivitySummary') {
            conversationReads++;
            return _conversationSummary(
              count: conversationReads == 1 ? 13 : 14,
            );
          }
          if (call.method == 'getTokenUsageSummary') {
            tokenReads++;
            return _tokenSummary(call, tokenReads == 1 ? 725 : 840);
          }
          return null;
        });

    await tester.pumpWidget(_dashboard());
    await tester.pumpAndSettle();
    expect(find.text('13'), findsOneWidget);
    expect(find.text('725'), findsOneWidget);

    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();

    expect(conversationReads, 2);
    expect(tokenReads, 2);
    expect(find.text('14'), findsOneWidget);
    expect(find.text('840'), findsOneWidget);
    expect(find.text('725'), findsNothing);
  });

  testWidgets(
    'Pad month labels and token columns align with the full heatmap',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = const Size(1366, 768);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      var weeks = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
            if (call.method == 'getConversationActivitySummary') {
              return _conversationSummary();
            }
            if (call.method == 'getTokenUsageSummary') {
              weeks = ((call.arguments as Map)['weekStartsMs'] as List).length;
              return _tokenSummary(call, 725);
            }
            return null;
          });
      await tester.pumpWidget(
        const MaterialApp(home: UsageStatisticsPage(embedded: true)),
      );
      await tester.pumpAndSettle();

      final content = tester.getRect(
        find.byKey(const ValueKey('usage-statistics-content')),
      );
      expect(content.width, 840);
      expect(
        tester.getRect(find.byKey(OmniRootToolbar.titleKey)).left,
        closeTo(content.left + 22, .01),
      );
      final plot = tester.getRect(
        find.byKey(const ValueKey('activity-heatmap-plot')),
      );
      final cells = [
        for (var week = 0; week < weeks; week++)
          tester.getRect(find.byKey(ValueKey('activity-cell-$week-0'))),
      ];
      expect(cells.first.left, closeTo(plot.left, .01));
      expect(cells.last.right, closeTo(plot.right, .01));
      expect(cells.first.height, greaterThan(14));
      for (var week = 0; week < weeks; week++) {
        final month = find.byKey(ValueKey('activity-month-$week'));
        if (month.evaluate().isNotEmpty) {
          expect(tester.getRect(month).left, closeTo(cells[week].left, .01));
        }
      }

      await tester.tap(find.byKey(const ValueKey('activity-view-1')));
      await tester.pumpAndSettle();
      final tokenPlot = tester.getRect(
        find.byKey(const ValueKey('activity-token-plot')),
      );
      expect(tokenPlot.left, closeTo(plot.left, .01));
      expect(tokenPlot.width, closeTo(plot.width, .01));
      expect(tokenPlot.height, closeTo(plot.height, .01));
      for (var week = 0; week < weeks; week++) {
        final bar = tester.getRect(
          find.byKey(ValueKey('activity-token-week-$week')),
        );
        expect(bar.left, closeTo(cells[week].left, .01));
        expect(bar.width, closeTo(cells[week].width, .01));
      }
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'a tiny model keeps its true share without overflowing the tallest week',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = const Size(1024, 768);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      var weeks = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
            if (call.method == 'getConversationActivitySummary') {
              return _conversationSummary();
            }
            if (call.method == 'getTokenUsageSummary') {
              final starts = (call.arguments as Map)['weekStartsMs'] as List;
              weeks = starts.length;
              return {
                'weeklyData': [
                  for (final start in starts)
                    {
                      'weekStartMs': start,
                      'modelTokens': start == starts.last
                          ? {'major': 999999, 'tiny': 1}
                          : <String, int>{},
                      'cachedTokens': 0,
                    },
                ],
                'totalCached': 0,
                'modelTotals': {'major': 999999, 'tiny': 1},
                'modelOrder': ['major', 'tiny'],
              };
            }
            return null;
          });
      await tester.pumpWidget(
        const MaterialApp(home: UsageStatisticsPage(embedded: true)),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('activity-view-1')));
      await tester.pumpAndSettle();
      expect(find.text('1.0M'), findsOneWidget);
      final last = weeks - 1;
      final bar = find.byKey(ValueKey('activity-token-week-$last'));
      final totalHeight = tester.getSize(bar).height;
      final major = tester
          .getSize(find.byKey(ValueKey('activity-token-segment-$last-0')))
          .height;
      final tiny = tester
          .getSize(find.byKey(ValueKey('activity-token-segment-$last-1')))
          .height;
      expect(major + tiny, closeTo(totalHeight, .000001));
      expect(tiny, closeTo(totalHeight / 1000000, .000001));
      final tinyTooltipText = find.byWidgetPredicate(
        (widget) =>
            widget is Text &&
            (widget.data ?? widget.textSpan?.toPlainText())?.contains('tiny 1') ==
                true,
      );
      expect(tinyTooltipText, findsNothing);
      await tester.tap(bar);
      await tester.pumpAndSettle();
      expect(tinyTooltipText, findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'large text and short split windows retain charts and wrap legends',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = const Size(1024, 768);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      var reads = 0;
      var weeks = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
            if (call.method == 'getConversationActivitySummary') {
              return _conversationSummary(count: 123456);
            }
            if (call.method == 'getTokenUsageSummary') {
              reads++;
              final summary = _tokenSummary(call, 725);
              weeks = ((call.arguments as Map)['weekStartsMs'] as List).length;
              summary['modelTotals'] = {
                'provider/model-with-a-long-readable-name': 725,
                'secondary-model': 300,
                'third-model': 200,
              };
              summary['modelOrder'] = [
                'provider/model-with-a-long-readable-name',
                'secondary-model',
                'third-model',
              ];
              return summary;
            }
            return null;
          });
      await tester.pumpWidget(
        const MaterialApp(home: UsageStatisticsPage(embedded: true)),
      );
      await tester.pumpAndSettle();
      final originalState = tester.state(find.byType(ActivityDashboardCard));

      tester.platformDispatcher.textScaleFactorTestValue = 2;
      tester.view.physicalSize = const Size(600, 360);
      await tester.pumpAndSettle();
      expect(
        tester.state(find.byType(ActivityDashboardCard)),
        same(originalState),
      );
      expect(find.text('123456'), findsOneWidget);
      expect(reads, 1);
      await tester.ensureVisible(find.byKey(const ValueKey('activity-view-1')));
      await tester.tap(find.byKey(const ValueKey('activity-view-1')));
      await tester.pumpAndSettle();
      final legend = tester.getRect(
        find.byKey(const ValueKey('activity-model-legend')),
      );
      expect(legend.height, greaterThan(28));
      expect(legend.width, lessThanOrEqualTo(600 - 44));
      final lastBar = find.byKey(ValueKey('activity-token-week-${weeks - 1}'));
      await tester.ensureVisible(lastBar);
      await tester.pumpAndSettle();
      expect(lastBar.hitTestable(), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
}

Widget _dashboard() => const MaterialApp(
  home: Scaffold(
    body: SizedBox(width: 375, child: ActivityDashboardCard(weeksToShow: 4)),
  ),
);

Map<String, Object?> _conversationSummary({int count = 13}) => {
  'activityMap': {_dateKey(DateTime.now()): count},
  'totalConversations': count,
  'currentStreak': 1,
};

Map<String, Object?> _tokenSummary(MethodCall call, int tokens) {
  final starts = (call.arguments as Map)['weekStartsMs'] as List;
  return {
    'weeklyData': [
      for (final start in starts)
        {
          'weekStartMs': start,
          'modelTokens': start == starts.last
              ? {'model': tokens}
              : <String, int>{},
          'cachedTokens': 0,
        },
    ],
    'totalCached': 0,
    'modelTotals': {'model': tokens},
    'modelOrder': ['model'],
  };
}

String _dateKey(DateTime date) =>
    '${date.year}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}';
