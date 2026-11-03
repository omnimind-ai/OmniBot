import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_empty_greeting.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_history_loading_transition.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_message_viewport.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_widgets.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/deep_thinking_card.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/widgets/streaming_text.dart';

void main() {
  testWidgets('keyboard geometry preserves visible rows and sliver delegates', (
    tester,
  ) async {
    final inset = ValueNotifier<double>(0);
    final controller = ScrollController();
    final countedMessages = List.generate(6, _CountingTextMessage.new);
    final messages = ObservableChatMessageList()
      ..replaceAllMessages(countedMessages);
    addTearDown(inset.dispose);
    addTearDown(controller.dispose);
    addTearDown(messages.dispose);
    addTearDown(tester.view.resetViewInsets);

    await tester.pumpWidget(
      _viewportApp(
        inset,
        ChatHistoryLoadingTransition(
          isLoading: false,
          child: ChatMessageList(
            messages: messages,
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    final initialHeight = controller.position.viewportDimension;
    final reads = countedMessages.map((message) => message.textReads).toList();
    expect(reads.every((count) => count > 0), isTrue);
    final delegates = tester
        .widgetList<SliverList>(find.byType(SliverList))
        .map((sliver) => sliver.delegate)
        .toList();

    for (final bottomInset in [24.0, 64.0, 128.0, 0.0]) {
      tester.view.viewInsets = FakeViewPadding(bottom: bottomInset);
      inset.value = bottomInset;
      await tester.pump();
      expect(
        controller.position.viewportDimension,
        closeTo(initialHeight - bottomInset, 0.01),
      );
      expect(
        controller.offset,
        closeTo(controller.position.maxScrollExtent, 0.01),
      );
      final currentDelegates = tester
          .widgetList<SliverList>(find.byType(SliverList))
          .map((sliver) => sliver.delegate)
          .toList();
      expect(currentDelegates, orderedEquals(delegates));
      expect(
        countedMessages.map((message) => message.textReads).toList(),
        reads,
        reason: 'IME motion must only relayout existing visible messages.',
      );
    }
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'viewport updates empty and loading geometry with fixed content',
    (tester) async {
      final inset = ValueNotifier<double>(0);
      final controller = ScrollController();
      addTearDown(inset.dispose);
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        _viewportApp(
          inset,
          ChatMessageList(
            messages: const [],
            scrollController: controller,
            onBeforeTaskExecute: () async {},
          ),
        ),
      );
      await tester.pump();
      inset.value = 120;
      await tester.pump();
      expect(
        tester.widget<AnimatedAlign>(find.byType(AnimatedAlign)).alignment,
        const Alignment(-1, -1),
      );
      expect(
        find.byWidgetPredicate(
          (widget) =>
              widget is Padding &&
              widget.padding == const EdgeInsets.only(bottom: 120),
        ),
        findsOneWidget,
      );

      await tester.pumpWidget(
        _viewportApp(
          inset,
          const ChatHistoryLoadingTransition(
            isLoading: true,
            child: SizedBox(),
          ),
        ),
      );
      await tester.pump();
      inset.value = 180;
      await tester.pump();
      expect(
        find.byWidgetPredicate(
          (widget) =>
              widget is Padding &&
              widget.padding == const EdgeInsets.only(bottom: 180),
        ),
        findsOneWidget,
      );
      expect(find.byType(ChatMessageList), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('pure chat retains reasoning and live answer subscriptions', (
    tester,
  ) async {
    final controller = ScrollController();
    final messages = ObservableChatMessageList()
      ..replaceAllMessages([
        ChatMessageModel(
          id: 'answer',
          type: 1,
          user: 2,
          content: const {'text': 'first chunk'},
          streamMeta: const {'kind': 'text_snapshot', 'isFinal': false},
        ),
        _CountingToolCard(),
        ChatMessageModel.cardMessage(const {
          'type': 'agent_request',
          'requestId': 'request',
        }, id: 'request'),
        ChatMessageModel.cardMessage(const {
          'type': 'deep_thinking',
          'thinkingContent': 'reasoning stays visible',
          'isLoading': false,
          'stage': 4,
        }, id: 'thinking'),
        ChatMessageModel.userMessage('question', id: 'user'),
      ]);
    addTearDown(controller.dispose);
    addTearDown(messages.dispose);

    await tester.pumpWidget(_app(messages, controller));
    await tester.pump();
    expect(find.byType(DeepThinkingCard), findsOneWidget);
    expect(
      find.byKey(const ValueKey('chat-message-list-item-tool')),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey('chat-message-list-item-request')),
      findsNothing,
    );
    expect(
      tester.widget<StreamingText>(find.byType(StreamingText)).fullText,
      'first chunk',
    );

    final revision = messages.structureRevision;
    messages[0] = messages[0].copyWith(content: const {'text': 'next chunk'});
    expect(messages.structureRevision, revision);
    await tester.pump();
    expect(
      tester.widget<StreamingText>(find.byType(StreamingText)).fullText,
      'next chunk',
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('keyboard inset rebuilds reuse pure chat filtering', (
    tester,
  ) async {
    final controller = ScrollController();
    final tool = _CountingToolCard();
    final messages = ObservableChatMessageList()
      ..replaceAllMessages([
        tool,
        ChatMessageModel.userMessage('question', id: 'user'),
      ]);
    addTearDown(controller.dispose);
    addTearDown(messages.dispose);

    await tester.pumpWidget(_app(messages, controller));
    await tester.pumpAndSettle();
    final reads = tool.cardReads;
    for (final inset in [48.0, 120.0, 240.0, 300.0, 0.0]) {
      await tester.pumpWidget(_app(messages, controller, bottomInset: inset));
      await tester.pump();
    }
    expect(
      tool.cardReads,
      reads,
      reason: 'Keyboard motion must not rescan the filtered transcript.',
    );

    await tester.pumpWidget(_app(messages, controller, hideToolCards: false));
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey('chat-message-list-item-tool')),
      findsOneWidget,
    );

    await tester.pumpWidget(_app(messages, controller));
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey('chat-message-list-item-tool')),
      findsNothing,
    );

    messages[0] = ChatMessageModel.assistantMessage(
      'visible replacement',
      id: 'tool',
    );
    await tester.pumpAndSettle();
    expect(
      find.textContaining('visible replacement', findRichText: true),
      findsOneWidget,
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'a pure chat containing only execution cards keeps its empty state',
    (tester) async {
      final controller = ScrollController();
      final messages = ObservableChatMessageList()..add(_CountingToolCard());
      addTearDown(controller.dispose);
      addTearDown(messages.dispose);

      await tester.pumpWidget(_app(messages, controller));
      await tester.pump();
      expect(find.byType(ChatEmptyGreeting), findsOneWidget);
    },
  );
}

Widget _app(
  List<ChatMessageModel> messages,
  ScrollController controller, {
  bool hideToolCards = true,
  double bottomInset = 0,
}) => MaterialApp(
  localizationsDelegates: AppLocalizations.localizationsDelegates,
  supportedLocales: AppLocalizations.supportedLocales,
  home: Scaffold(
    body: ChatMessageList(
      messages: messages,
      scrollController: controller,
      hideToolCards: hideToolCards,
      bottomOverlayInset: bottomInset,
      onBeforeTaskExecute: () async {},
    ),
  ),
);

Widget _viewportApp(ValueNotifier<double> inset, Widget child) => MaterialApp(
  localizationsDelegates: AppLocalizations.localizationsDelegates,
  supportedLocales: AppLocalizations.supportedLocales,
  home: Scaffold(
    resizeToAvoidBottomInset: false,
    body: ValueListenableBuilder<double>(
      valueListenable: inset,
      child: child,
      builder: (context, value, child) => ChatMessageViewport(
        bottomOverlayInset: value,
        liftEmptyGreeting: value > 0,
        child: child!,
      ),
    ),
  ),
);

class _CountingTextMessage extends ChatMessageModel {
  _CountingTextMessage(int index)
    : super(
        id: 'counted-$index',
        type: 1,
        user: 1,
        content: {'text': 'visible message $index'},
      );

  int textReads = 0;

  @override
  String? get text {
    textReads++;
    return super.text;
  }
}

class _CountingToolCard extends ChatMessageModel {
  _CountingToolCard()
    : super(
        id: 'tool',
        type: 2,
        user: 3,
        content: const {
          'cardData': {
            'type': 'agent_tool_summary',
            'status': 'success',
            'toolType': 'terminal',
            'toolTitle': 'tool execution',
            'summary': 'finished',
          },
        },
      );

  int cardReads = 0;

  @override
  Map<String, dynamic>? get cardData {
    cardReads++;
    return super.cardData;
  }
}
