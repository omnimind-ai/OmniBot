import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/features/home/pages/chat/widgets/agent_run_header.dart';
import 'package:ui/widgets/agent_brand_icon.dart';

void main() {
  testWidgets('running header shows the elapsed processing label and ticks', (
    tester,
  ) async {
    final startedAt = DateTime.now().subtract(const Duration(seconds: 5));

    await tester.pumpWidget(
      _wrap(
        AgentRunHeader(
          taskId: 'turn-1',
          agentId: 'codex-acp',
          status: AgentRunStatus.running,
          startedAt: startedAt,
        ),
      ),
    );
    await tester.pump();

    expect(find.textContaining('正在处理 5s'), findsOneWidget);
    expect(find.byKey(const ValueKey('acp-processed-label')), findsNothing);
    // No fold affordance while running — that is what makes the run
    // auto-collapse when it finishes rather than needing an explicit trigger.
    expect(find.byType(Icon), findsNothing);

    await tester.pump(const Duration(seconds: 1));
    expect(find.textContaining('正在处理 6s'), findsOneWidget);
  });

  testWidgets(
    'finished header shows processed label, elapsed time and chevron',
    (tester) async {
      final startedAt = DateTime(2026, 7, 25, 15, 48, 0);

      await tester.pumpWidget(
        _wrap(
          AgentRunHeader(
            taskId: 'turn-1',
            agentId: 'claude-code-acp',
            status: AgentRunStatus.finished,
            startedAt: startedAt,
            finishedAt: startedAt.add(const Duration(seconds: 83)),
            onToggleExpanded: () {},
          ),
        ),
      );
      await tester.pump();

      expect(find.textContaining('已处理'), findsOneWidget);
      expect(find.textContaining('1m 23s'), findsOneWidget);
      expect(find.byKey(const ValueKey('acp-processing-label')), findsNothing);
      expect(
        find.byKey(const ValueKey('agent-run-summary-chevron-turn-1')),
        findsOneWidget,
      );
    },
  );

  testWidgets('finished header without fold history has no chevron', (
    tester,
  ) async {
    await tester.pumpWidget(
      _wrap(
        AgentRunHeader(
          taskId: 'turn-no-history',
          agentId: 'codex-acp',
          status: AgentRunStatus.finished,
          startedAt: DateTime(2026, 7, 25, 15, 48, 0),
          finishedAt: DateTime(2026, 7, 25, 15, 48, 1),
        ),
      ),
    );
    await tester.pump();

    expect(find.byKey(const ValueKey('acp-processed-label')), findsOneWidget);
    expect(
      find.byKey(const ValueKey('agent-run-summary-chevron-turn-no-history')),
      findsNothing,
    );
  });

  testWidgets('header renders the agent brand exactly once', (tester) async {
    await tester.pumpWidget(
      _wrap(
        AgentRunHeader(
          taskId: 'turn-1',
          agentId: 'claude-code-acp',
          status: AgentRunStatus.running,
          startedAt: DateTime.now(),
        ),
      ),
    );
    await tester.pump();

    final icons = find.byType(AgentBrandIcon);
    expect(icons, findsOneWidget);
    expect(tester.widget<AgentBrandIcon>(icons).agentId, 'claude-code-acp');
    expect(
      find.byKey(const ValueKey('agent-run-acp-avatar-turn-1')),
      findsOneWidget,
    );
  });

  for (final agentId in const <String>[
    'xiaowan-acp',
    'codex-acp',
    'claude-code-acp',
    'opencode-acp',
    'deepseek-harness-acp',
  ]) {
    testWidgets('$agentId brand fills the run avatar without a nested circle', (
      tester,
    ) async {
      await tester.pumpWidget(
        _wrap(
          AgentRunHeader(
            taskId: 'turn-$agentId',
            agentId: agentId,
            status: AgentRunStatus.running,
            startedAt: DateTime.now(),
          ),
        ),
      );
      await tester.pump();

      final runAvatar = find.byKey(
        ValueKey('agent-run-acp-avatar-turn-$agentId'),
      );
      final brandIcon = tester.widget<AgentBrandIcon>(
        find.descendant(of: runAvatar, matching: find.byType(AgentBrandIcon)),
      );

      expect(brandIcon.size, 30);
    });
  }

  testWidgets(
    'completion cross-fades the running label into the folded header',
    (tester) async {
      final startedAt = DateTime.now().subtract(const Duration(seconds: 5));
      var status = AgentRunStatus.running;
      late StateSetter setState;

      await tester.pumpWidget(
        _wrap(
          StatefulBuilder(
            builder: (context, stateSetter) {
              setState = stateSetter;
              return AgentRunHeader(
                taskId: 'turn-transition',
                agentId: 'deepseek-harness',
                status: status,
                startedAt: startedAt,
                finishedAt: status == AgentRunStatus.finished
                    ? startedAt.add(const Duration(seconds: 5))
                    : null,
                onToggleExpanded: status == AgentRunStatus.finished
                    ? () {}
                    : null,
              );
            },
          ),
        ),
      );
      await tester.pump();

      setState(() {
        status = AgentRunStatus.finished;
      });
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 90));

      expect(
        find.byKey(const ValueKey('acp-processing-label')),
        findsOneWidget,
      );
      expect(find.byKey(const ValueKey('acp-processed-label')), findsOneWidget);
      expect(
        find.byKey(const ValueKey('agent-run-summary-chevron-turn-transition')),
        findsOneWidget,
      );

      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('acp-processing-label')), findsNothing);
      expect(find.byKey(const ValueKey('acp-processed-label')), findsOneWidget);
    },
  );

  testWidgets('a sub-second run omits the elapsed suffix', (tester) async {
    final startedAt = DateTime(2026, 7, 25, 15, 48, 0);

    await tester.pumpWidget(
      _wrap(
        AgentRunHeader(
          taskId: 'turn-1',
          agentId: 'codex-acp',
          status: AgentRunStatus.finished,
          startedAt: startedAt,
          finishedAt: startedAt.add(const Duration(milliseconds: 120)),
          onToggleExpanded: () {},
        ),
      ),
    );
    await tester.pump();

    expect(find.text('已处理'), findsOneWidget);
  });
}

Widget _wrap(Widget child) {
  return MaterialApp(
    locale: const Locale('zh'),
    localizationsDelegates: const <LocalizationsDelegate<dynamic>>[
      GlobalMaterialLocalizations.delegate,
      GlobalWidgetsLocalizations.delegate,
      GlobalCupertinoLocalizations.delegate,
    ],
    supportedLocales: const <Locale>[Locale('zh'), Locale('en')],
    home: Scaffold(
      body: Align(alignment: Alignment.topLeft, child: child),
    ),
  );
}
