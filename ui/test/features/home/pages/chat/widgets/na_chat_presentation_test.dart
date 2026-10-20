import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/chat_page.dart';
import 'package:ui/features/home/pages/chat/utils/na_chat_presentation.dart';
import 'package:ui/features/home/pages/chat/widgets/agent_run_group_message.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_widgets.dart';
import 'package:ui/features/home/pages/chat/widgets/na_message_entrance.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/models/chat_message_model.dart';

final _time = DateTime(2026, 10, 10, 15, 0);
ChatMessageModel _body(
  String id,
  String text, {
  int user = 2,
  int minute = 0,
  bool finalText = true,
}) => ChatMessageModel(
  id: id,
  type: 1,
  user: user,
  createAt: _time.add(Duration(minutes: minute)),
  content: {'text': text, 'decodeTokensPerSecond': 42.0},
  streamMeta: {
    'parentTaskId': 'turn',
    'turnId': 'turn',
    'kind': 'text_snapshot',
    'isFinal': finalText,
  },
  turnUsage: {
    'durationMs': 65400,
    'endedAt': _time.millisecondsSinceEpoch,
    'out': 90,
  },
);
ChatMessageModel _card(
  String id,
  String type, {
  String kind = 'tool_call',
  int minute = 0,
}) => ChatMessageModel(
  id: id,
  type: 2,
  user: 3,
  createAt: _time.add(Duration(minutes: minute)),
  content: {
    'cardData': {
      'type': type,
      'uiStyle': 'agent_tool',
      'toolType': kind == 'error' ? 'status' : 'terminal',
      'status': kind == 'error' ? 'error' : 'success',
      'toolTitle': 'PRIVATE_TOOL_DETAILS',
      'summary': 'CLEAR_FAILURE_NOTICE',
    },
  },
  streamMeta: {'parentTaskId': 'turn', 'kind': kind, 'isFinal': true},
);
Widget _app(
  List<ChatMessageModel> messages,
  ScrollController controller, {
  String agent = 'na-cloud',
  Set<String> active = const {},
}) => MaterialApp(
  localizationsDelegates: AppLocalizations.localizationsDelegates,
  supportedLocales: AppLocalizations.supportedLocales,
  locale: const Locale('zh'),
  home: Scaffold(
    body: ChatMessageList(
      messages: messages,
      scrollController: controller,
      activeAcpAgentId: agent,
      activeAgentTurnIds: active,
      useAcpPresentation: true,
      onBeforeTaskExecute: () async {},
    ),
  ),
);

void main() {
  test('time separators use visible-message gaps and calendar boundaries', () {
    expect(showNaConversationTime(_time, null), isTrue);
    expect(
      showNaConversationTime(
        _time.add(const Duration(minutes: 4, seconds: 59)),
        _time,
      ),
      isFalse,
    );
    expect(
      showNaConversationTime(_time.add(const Duration(minutes: 5)), _time),
      isTrue,
    );
    expect(
      showNaConversationTime(
        DateTime(2026, 10, 11),
        DateTime(2026, 10, 10, 23, 59),
      ),
      isTrue,
    );
    expect(
      naConversationTimeLabel(_time, english: false, now: _time),
      '今天 15:00',
    );
    expect(
      naConversationTimeLabel(
        _time,
        english: true,
        now: _time.add(const Duration(days: 1)),
      ),
      'Yesterday 15:00',
    );
    expect(
      naConversationTimeLabel(
        DateTime(2025, 1, 1, 9, 5),
        english: false,
        now: _time,
      ),
      '2025年1月1日 09:05',
    );
  });

  testWidgets(
    'Na flattens assistant items, hides tools/reasoning/metrics, and restores identical history',
    (tester) async {
      final source = [
        _body('answer-2', 'SECOND_BODY'),
        _card('tool', 'agent_tool_summary'),
        _card('thinking', 'deep_thinking', kind: 'thinking'),
        _body('answer-1', 'FIRST_BODY'),
        _body('user', 'QUESTION', user: 1),
      ];
      for (final messages in [
        source,
        source.map((m) => ChatMessageModel.fromJson(m.toJson())).toList(),
      ]) {
        final controller = ScrollController();
        await tester.pumpWidget(_app(messages, controller));
        await tester.pumpAndSettle();
        expect(
          find.byKey(const ValueKey('na-agent-bubble-answer-1')),
          findsOneWidget,
        );
        expect(
          find.byKey(const ValueKey('na-agent-bubble-answer-2')),
          findsOneWidget,
        );
        expect(
          find.textContaining('FIRST_BODY', findRichText: true),
          findsOneWidget,
        );
        expect(
          find.textContaining('SECOND_BODY', findRichText: true),
          findsOneWidget,
        );
        expect(find.byType(AgentRunGroupMessage), findsNothing);
        expect(find.textContaining('PRIVATE_TOOL_DETAILS'), findsNothing);
        expect(find.textContaining('CLEAR_FAILURE_NOTICE'), findsNothing);
        expect(find.textContaining('1m 5s'), findsNothing);
        expect(find.textContaining('15:00:00'), findsNothing);
        expect(find.textContaining('tok/s'), findsNothing);
        expect(
          tester
              .widgetList<NaMessageEntrance>(find.byType(NaMessageEntrance))
              .every((w) => !w.animate),
          isTrue,
        );
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox());
        controller.dispose();
      }
    },
  );

  testWidgets(
    'Na keeps failure and cancellation readable without a process card',
    (tester) async {
      final controller = ScrollController();
      await tester.pumpWidget(
        _app([
          _card('failure', 'agent_tool_summary', kind: 'error'),
          _body('cancelled', '任务已取消'),
          _body('partial', 'PARTIAL_BODY'),
        ], controller),
      );
      await tester.pumpAndSettle();
      expect(
        find.textContaining('CLEAR_FAILURE_NOTICE', findRichText: true),
        findsOneWidget,
      );
      expect(find.textContaining('任务已取消', findRichText: true), findsOneWidget);
      expect(
        find.textContaining('PARTIAL_BODY', findRichText: true),
        findsOneWidget,
      );
      expect(find.byType(AgentRunGroupMessage), findsNothing);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      controller.dispose();
    },
  );

  testWidgets(
    'hidden tool timestamps cannot suppress a five-minute separator',
    (tester) async {
      final controller = ScrollController();
      await tester.pumpWidget(
        _app([
          _body('later', 'LATER', minute: 6),
          _card('tool', 'agent_tool_summary', minute: 4),
          _body('earlier', 'EARLIER'),
        ], controller),
      );
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('na-conversation-time-earlier')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey('na-conversation-time-later')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey('na-conversation-time-tool')),
        findsNothing,
      );
      await tester.pumpWidget(const SizedBox());
      controller.dispose();
    },
  );

  testWidgets(
    'Na send animates once while streaming updates retain the message bubble',
    (tester) async {
      final messages = ObservableChatMessageList();
      final controller = ScrollController();
      await tester.pumpWidget(_app(messages, controller, active: {'turn'}));
      await tester.pumpAndSettle();
      messages.insert(
        0,
        _body(
          'new-user',
          'SMOOTH_SEND',
          user: 1,
        ).copyWith(createAt: DateTime.now()),
      );
      await tester.pump();
      final entrance = find.byKey(
        const ValueKey('na-message-entrance-new-user'),
      );
      expect(tester.widget<NaMessageEntrance>(entrance).animate, isTrue);
      final opacity = find
          .descendant(of: entrance, matching: find.byType(Opacity))
          .first;
      expect(tester.widget<Opacity>(opacity).opacity, lessThan(1));
      await tester.pump(const Duration(milliseconds: 160));
      final midway = tester.widget<Opacity>(opacity).opacity;
      expect(midway, greaterThan(0.55));
      expect(midway, lessThan(1));
      await tester.pumpAndSettle();
      expect(tester.widget<Opacity>(opacity).opacity, 1);
      messages.insert(0, _body('stream', 'FIRST ', finalText: false));
      await tester.pumpAndSettle();
      final bubble = tester.element(
        find.byKey(const ValueKey('na-agent-bubble-stream')),
      );
      messages[0] = _body('stream', 'FIRST SECOND', finalText: false);
      await tester.pumpAndSettle();
      expect(
        tester.element(find.byKey(const ValueKey('na-agent-bubble-stream'))),
        same(bubble),
      );
      expect(
        find.textContaining('FIRST SECOND', findRichText: true),
        findsOneWidget,
      );
      expect(find.byType(AgentRunGroupMessage), findsNothing);
      expect(tester.widget<Opacity>(opacity).opacity, 1);
      // Paginated older messages never play the send animation.
      messages.add(_body('history', 'OLDER', minute: -10));
      await tester.pumpAndSettle();
      controller.jumpTo(controller.position.minScrollExtent);
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<NaMessageEntrance>(
              find.byKey(const ValueKey('na-message-entrance-history')),
            )
            .animate,
        isFalse,
      );
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      messages.dispose();
      controller.dispose();
    },
  );

  testWidgets('asynchronously loaded history stays still', (tester) async {
    final messages = ObservableChatMessageList();
    final controller = ScrollController();
    await tester.pumpWidget(_app(messages, controller));
    await tester.pumpAndSettle();
    messages.replaceAllMessages([
      _body(
        'restored',
        'RESTORED_BODY',
      ).copyWith(createAt: DateTime.now().subtract(const Duration(minutes: 1))),
    ]);
    await tester.pump();
    final entrance = find.byKey(const ValueKey('na-message-entrance-restored'));
    expect(tester.widget<NaMessageEntrance>(entrance).animate, isFalse);
    expect(
      tester
          .widget<Opacity>(
            find.descendant(of: entrance, matching: find.byType(Opacity)).first,
          )
          .opacity,
      1,
    );
    await tester.pumpAndSettle();
    await tester.pumpWidget(const SizedBox());
    messages.dispose();
    controller.dispose();
  });

  testWidgets(
    'cloud cancelled history matches the live cancellation identity',
    (tester) async {
      final restored = remoteCodexMessagesFromThreadResponseForTesting({
        'turns': [
          {
            'id': 'turn',
            'status': 'cancelled',
            'items': [
              {
                'id': 'turn-cancelled',
                'hostMessageId': 'turn-cancelled',
                'type': 'agentMessage',
                'text': '任务已取消',
              },
            ],
          },
        ],
      });
      final live = _body('turn-cancelled', '任务已取消');
      final merged = mergeRemoteCodexSnapshotMessagesForTesting(
        snapshotMessages: restored,
        existingMessages: [live],
        activeTaskId: null,
        isAiResponding: false,
      );
      expect(merged.where((m) => m.id == live.id), hasLength(1));
      final controller = ScrollController();
      await tester.pumpWidget(_app(merged, controller));
      await tester.pumpAndSettle();
      expect(find.textContaining('任务已取消', findRichText: true), findsOneWidget);
      expect(find.byType(AgentRunGroupMessage), findsNothing);
      await tester.pumpWidget(const SizedBox());
      controller.dispose();
    },
  );

  testWidgets('reduced motion respects the system setting', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: MediaQuery(
          data: MediaQueryData(disableAnimations: true),
          child: NaMessageEntrance(
            animate: true,
            isUser: true,
            child: Text('SEND'),
          ),
        ),
      ),
    );
    expect(tester.widget<Opacity>(find.byType(Opacity).first).opacity, 1);
    expect(tester.takeException(), isNull);
  });

  testWidgets('other agents retain their existing run presentation', (
    tester,
  ) async {
    final controller = ScrollController();
    await tester.pumpWidget(
      _app([_body('answer', 'OTHER_AGENT')], controller, agent: 'codex'),
    );
    await tester.pumpAndSettle();
    expect(find.byType(AgentRunGroupMessage), findsOneWidget);
    expect(find.byType(NaMessageEntrance), findsNothing);
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });
}
