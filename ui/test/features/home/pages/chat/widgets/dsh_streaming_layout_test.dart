import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_widgets.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/agent_event_reducer.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/widgets/streaming_text.dart';

void main() {
  testWidgets(
    'DSH long reasoning collapse leaves no blank viewport after answer and reload',
    (tester) async {
      final runtime = ChatConversationRuntimeState(
        conversationId: 918,
        mode: 'agent',
      );
      const reducer = AgentEventReducer();
      final controller = ScrollController();
      addTearDown(runtime.dispose);
      addTearDown(controller.dispose);
      void update(String kind, String text) => reducer.reduce(
        runtime: runtime,
        event: {
          'method': 'session/update',
          'agentId': 'deepseek-harness-acp',
          'turnId': 'long-turn',
          'params': {
            'sessionId': 'long-session',
            'update': {
              'sessionUpdate': kind,
              'messageId': '1:1',
              'content': {'type': 'text', 'text': text},
            },
          },
        },
      );
      Widget page() => MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: SizedBox(
            width: 360,
            height: 520,
            child: ChatMessageList(
              messages: runtime.messages,
              scrollController: controller,
              useAcpPresentation: true,
              activeAcpAgentId: 'deepseek-harness-acp',
              activeAgentTurnIds: runtime.isAiResponding ? {'long-turn'} : {},
              onBeforeTaskExecute: () async {},
            ),
          ),
        ),
      );
      update(
        'agent_thought_chunk',
        List.filled(80, '分析四季的变化，整理段落。').join('\n'),
      );
      await tester.pumpWidget(page());
      for (final chunk in [
        '## 四季\n\n',
        '春天回暖。\n\n',
        '夏天多雨。\n\n',
        '秋天凉爽。\n\n',
        '冬天安静。',
      ]) {
        update('agent_message_chunk', chunk);
        await tester.pumpWidget(page());
        await tester.pump(const Duration(milliseconds: 180));
        expect(
          tester.getSize(find.byType(StreamingText)).height,
          lessThan(320),
        );
        expect(
          controller.offset,
          inInclusiveRange(0, controller.position.maxScrollExtent),
        );
        expect(tester.takeException(), isNull);
      }
      reducer.reducePromptResponse(
        runtime: runtime,
        sessionId: 'long-session',
        turnId: 'long-turn',
        stopReason: 'end_turn',
      );
      await tester.pumpWidget(page());
      await tester.pumpAndSettle();
      final size = tester.getSize(find.byType(StreamingText));
      final bottom = tester.getBottomLeft(find.byType(StreamingText)).dy;
      expect(size.height, lessThan(320));
      expect(bottom, inInclusiveRange(0, 520));
      await tester.pumpWidget(const SizedBox());
      runtime.messages.replaceAllMessages(
        runtime.messages
            .map((m) => ChatMessageModel.fromJson(m.toJson()))
            .toList(),
      );
      await tester.pumpWidget(page());
      await tester.pumpAndSettle();
      expect(tester.getSize(find.byType(StreamingText)), size);
      expect(
        tester.getBottomLeft(find.byType(StreamingText)).dy,
        inInclusiveRange(0, 520),
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('DSH committed answer is fully visible at official completion', (
    tester,
  ) async {
    final runtime = ChatConversationRuntimeState(
      conversationId: 917,
      mode: 'agent',
    );
    const reducer = AgentEventReducer();
    final controller = ScrollController();
    addTearDown(runtime.dispose);
    addTearDown(controller.dispose);
    const answer = '春天的风带来青草的气息，夏天的雨落在屋檐上。秋天的落叶铺满小路，冬天的雪安静地覆盖田野。';
    void update(String kind, String text) {
      reducer.reduce(
        runtime: runtime,
        event: {
          'method': 'session/update',
          'agentId': 'deepseek-harness-acp',
          'turnId': 'dsh-turn',
          'params': {
            'sessionId': 'dsh-session',
            'update': {
              'sessionUpdate': kind,
              'messageId': '1:1',
              'content': {'type': 'text', 'text': text},
            },
          },
        },
      );
    }

    Widget page() => MaterialApp(
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: ChatMessageList(
          messages: runtime.messages,
          scrollController: controller,
          useAcpPresentation: true,
          activeAcpAgentId: 'deepseek-harness-acp',
          activeAgentTurnIds: runtime.isAiResponding ? {'dsh-turn'} : {},
          onBeforeTaskExecute: () async {},
        ),
      ),
    );
    // Installed official dsh-acp assistantUpdates sends committed blocks in
    // order, then the host receives the official PromptResponse.
    update('agent_thought_chunk', '先整理四季的特征。');
    await tester.pumpWidget(page());
    update('agent_message_chunk', answer);
    await tester.pumpWidget(page());
    reducer.reducePromptResponse(
      runtime: runtime,
      sessionId: 'dsh-session',
      turnId: 'dsh-turn',
      stopReason: 'end_turn',
    );
    await tester.pumpWidget(page());
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.textContaining(answer, findRichText: true), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'DSH spaced chunks resume visible text without an increasing blank delay',
    (tester) async {
      String text = '';
      Widget page() => MaterialApp(
        home: Scaffold(
          body: StreamingText(
            fullText: text,
            isFinal: false,
            style: const TextStyle(fontSize: 16),
          ),
        ),
      );
      await tester.pumpWidget(page());
      for (var chunk = 0; chunk < 5; chunk++) {
        text += '春夏秋冬';
        await tester.pumpWidget(page());
        for (var frame = 0; frame < 12; frame++) {
          await tester.pump(const Duration(milliseconds: 20));
        }
        expect(
          find.text(text, findRichText: true),
          findsOneWidget,
          reason: 'chunk $chunk must be visible within 240 ms',
        );
        await tester.pump(const Duration(seconds: 1));
      }
    },
  );
}
