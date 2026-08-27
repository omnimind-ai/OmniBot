import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/models/chat_message_model.dart';

void main() {
  test('model selector ignores repeated opens during a slow refresh', () async {
    final guard = ConversationModelSelectorOpeningGuard();
    final release = Completer<void>();

    Future<bool> open() async {
      if (!guard.tryBegin()) return false;
      try {
        await release.future;
        return true;
      } finally {
        guard.finish();
      }
    }

    final first = open();
    expect(guard.isOpening, isTrue);
    expect(await open(), isFalse);
    release.complete();
    expect(await first, isTrue);
    expect(guard.isOpening, isFalse);
    expect(guard.tryBegin(), isTrue);
    guard.finish();
  });

  group('ChatConversationRuntimeCoordinator.replaceConversationSnapshot '
      'preserveLiveStreamingState', () {
    final coordinator = ChatConversationRuntimeCoordinator.instance;

    setUp(() {
      coordinator.resetForTest();
    });

    tearDown(() {
      coordinator.resetForTest();
    });

    test(
      'when preserveLiveStreamingState=true the snapshot keeps reducer '
      'push state intact (regression: codex output mid-turn auto-collapse)',
      () {
        const conversationId = 0xC0DE;
        const mode = kChatRuntimeModeAgent;
        coordinator.ensureEphemeralRuntime(
          conversationId: conversationId,
          mode: mode,
        );
        final runtime = coordinator.runtimeFor(
          conversationId: conversationId,
          mode: mode,
        )!;
        // Simulate reducer push-driven streaming state populated by
        // _touchActiveTurn + _appendAssistantText + _appendThinking.
        runtime.isAiResponding = true;
        runtime.currentDispatchTurnId = 'turn-1';
        runtime.lastAgentTurnId = 'turn-1';
        runtime.currentAiMessages['msg-1-codex-agent'] = 'streaming text';
        runtime.currentThinkingMessages['turn-1'] = 'thinking text';
        runtime.currentThinkingStage = ThinkingStage.thinking.value;
        runtime.isDeepThinking = true;

        // Simulate the 2s polling tick deciding the thread looks idle.
        coordinator.replaceConversationSnapshot(
          conversationId: conversationId,
          mode: mode,
          messages: const <ChatMessageModel>[],
          isAiResponding: false,
          currentDispatchTurnId: null,
          currentThinkingStage: ThinkingStage.complete.value,
          preserveLiveStreamingState: true,
        );

        // None of the push-driven fields may have been clobbered: the
        // chat list reads runtime.activeAgentTurnIds and must still see
        // the active turn so the agent run group remains EXPANDED.
        expect(runtime.isAiResponding, isTrue);
        expect(runtime.currentDispatchTurnId, 'turn-1');
        expect(runtime.lastAgentTurnId, 'turn-1');
        expect(
          runtime.currentAiMessages['msg-1-codex-agent'],
          'streaming text',
        );
        expect(runtime.currentThinkingMessages['turn-1'], 'thinking text');
        expect(runtime.currentThinkingStage, ThinkingStage.thinking.value);
        expect(runtime.isDeepThinking, isTrue);
        expect(runtime.activeAgentTurnIds, contains('turn-1'));
      },
    );

    test('when preserveLiveStreamingState=false (default) the snapshot fully '
        'overwrites runtime state (initial session load behaviour)', () {
      const conversationId = 0xBEEF;
      const mode = kChatRuntimeModeAgent;
      coordinator.ensureEphemeralRuntime(
        conversationId: conversationId,
        mode: mode,
      );
      final runtime = coordinator.runtimeFor(
        conversationId: conversationId,
        mode: mode,
      )!;
      runtime.isAiResponding = true;
      runtime.currentDispatchTurnId = 'stale-turn';
      runtime.currentAiMessages['old'] = 'old text';

      coordinator.replaceConversationSnapshot(
        conversationId: conversationId,
        mode: mode,
        messages: const <ChatMessageModel>[],
        isAiResponding: false,
        currentDispatchTurnId: null,
      );

      expect(runtime.isAiResponding, isFalse);
      expect(runtime.currentDispatchTurnId, isNull);
      expect(runtime.currentAiMessages, isEmpty);
      expect(runtime.activeAgentTurnIds, isEmpty);
    });

    test(
      'keeps row notifiers when a refresh reuses runtime message objects',
      () {
        const conversationId = 0xD55;
        const mode = kChatRuntimeModeAgent;
        final runtime = coordinator.ensureRuntime(
          conversationId: conversationId,
          mode: mode,
          initialMessages: <ChatMessageModel>[
            ChatMessageModel.assistantMessage('final', id: 'turn-1-text'),
          ],
        );
        final originalNotifier = runtime.messages.listenableAt(0);
        var structuralNotifications = 0;
        runtime.messages.addListener(() => structuralNotifications += 1);

        coordinator.replaceConversationSnapshot(
          conversationId: conversationId,
          mode: mode,
          messages: List<ChatMessageModel>.from(runtime.messages),
        );

        expect(runtime.messages.listenableAt(0), same(originalNotifier));
        expect(structuralNotifications, 0);
      },
    );

    test('snapshot keeps one latest row for each message id', () {
      const conversationId = 0xD56;
      const mode = kChatRuntimeModeAgent;
      coordinator.replaceConversationSnapshot(
        conversationId: conversationId,
        mode: mode,
        messages: <ChatMessageModel>[
          ChatMessageModel.assistantMessage('old', id: 'same-id'),
          ChatMessageModel.assistantMessage('other', id: 'other-id'),
          ChatMessageModel.assistantMessage('latest', id: 'same-id'),
        ],
      );

      final messages = coordinator
          .runtimeFor(conversationId: conversationId, mode: mode)!
          .messages;
      expect(messages, hasLength(2));
      expect(messages.map((message) => message.id), <String>[
        'same-id',
        'other-id',
      ]);
      expect(messages.first.text, 'latest');
    });
  });

  group('shouldReloadConversationMessagesChanged', () {
    test('ignores native stream snapshots while runtime is in flight', () {
      expect(
        shouldReloadConversationMessagesChanged(
          reason: 'agent_stream_snapshot',
          hasInFlightTask: true,
        ),
        isFalse,
      );
      expect(
        shouldReloadConversationMessagesChanged(
          reason: 'chat_task_stream_snapshot',
          hasInFlightTask: true,
        ),
        isFalse,
      );
    });

    test('still reloads external and non-stream changes', () {
      expect(
        shouldReloadConversationMessagesChanged(
          reason: 'external_user_message',
          hasInFlightTask: true,
        ),
        isTrue,
      );
      expect(
        shouldReloadConversationMessagesChanged(
          reason: 'messages_replaced',
          hasInFlightTask: true,
        ),
        isFalse,
      );
      expect(
        shouldReloadConversationMessagesChanged(
          reason: 'agent_stream_snapshot',
          hasInFlightTask: false,
        ),
        isTrue,
      );
    });

    test('keeps the completed in-memory timeline during native echoes', () {
      expect(
        shouldReloadConversationMessagesChanged(
          reason: 'agent_stream_snapshot',
          hasInFlightTask: false,
          hasRuntimeMessages: true,
        ),
        isFalse,
      );
      expect(
        shouldReloadConversationMessagesChanged(
          reason: 'messages_replaced',
          hasInFlightTask: false,
          hasRuntimeMessages: true,
          suppressLocalSnapshotEcho: true,
        ),
        isFalse,
      );
      expect(
        shouldReloadConversationMessagesChanged(
          reason: 'messages_replaced',
          hasInFlightTask: false,
          hasRuntimeMessages: true,
        ),
        isTrue,
      );
    });
  });

  group('conversation list refresh source', () {
    test('keeps a populated runtime even after its task completes', () {
      expect(
        shouldPreferInMemoryForConversationListChanged(
          hasInFlightTask: false,
          hasRuntimeMessages: true,
        ),
        isTrue,
      );
      expect(
        shouldPreferInMemoryForConversationListChanged(
          hasInFlightTask: false,
          hasRuntimeMessages: false,
        ),
        isFalse,
      );
    });
  });

  group('resolveVisibleChatMessages', () {
    final localUserMessage = ChatMessageModel.userMessage(
      '刚刚发送的消息',
      id: 'local-user',
    );
    final runtimeReply = ChatMessageModel.assistantMessage(
      'runtime reply',
      id: 'runtime-reply',
    );

    test('keeps the populated local list during an empty runtime hand-off', () {
      final fallback = <ChatMessageModel>[localUserMessage];

      expect(
        resolveVisibleChatMessages(
          runtimeMessages: <ChatMessageModel>[],
          fallbackMessages: fallback,
          preserveFallbackDuringHandoff: true,
        ),
        same(fallback),
      );
    });

    test('uses runtime messages as soon as the runtime is populated', () {
      final runtime = <ChatMessageModel>[runtimeReply];

      expect(
        resolveVisibleChatMessages(
          runtimeMessages: runtime,
          fallbackMessages: <ChatMessageModel>[localUserMessage],
        ),
        same(runtime),
      );
    });

    test(
      'preserves a deliberately empty runtime when both sources are empty',
      () {
        final runtime = <ChatMessageModel>[];

        expect(
          resolveVisibleChatMessages(
            runtimeMessages: runtime,
            fallbackMessages: <ChatMessageModel>[],
          ),
          same(runtime),
        );
      },
    );

    test('does not expose stale fallback messages outside a hand-off', () {
      final runtime = <ChatMessageModel>[];

      expect(
        resolveVisibleChatMessages(
          runtimeMessages: runtime,
          fallbackMessages: <ChatMessageModel>[localUserMessage],
        ),
        same(runtime),
      );
    });
  });

  group('retriedMessageRoundRemovalCount', () {
    final messages = <ChatMessageModel>[
      ChatMessageModel.assistantMessage('旧回复', id: 'assistant'),
      ChatMessageModel.cardMessage(const <String, dynamic>{
        'type': 'deep_thinking',
      }, id: 'thinking'),
      ChatMessageModel.userMessage('保留显示的用户消息', id: 'user'),
      ChatMessageModel.assistantMessage('更早回复', id: 'older-assistant'),
    ];

    test('plain retry clears old response but preserves the user entry', () {
      final removeCount = retriedMessageRoundRemovalCount(
        messages,
        userMessageId: 'user',
        preserveUserMessage: true,
      );

      expect(removeCount, 2);
      expect(messages.skip(removeCount).first.id, 'user');
    });

    test('edited resend also removes the original user entry', () {
      expect(
        retriedMessageRoundRemovalCount(
          messages,
          userMessageId: 'user',
          preserveUserMessage: false,
        ),
        3,
      );
    });
  });

  group('ObservableChatMessageList', () {
    late ObservableChatMessageList list;
    late int notifyCount;

    setUp(() {
      list = ObservableChatMessageList();
      notifyCount = 0;
      list.addListener(() {
        notifyCount += 1;
      });
    });

    tearDown(() {
      list.dispose();
    });

    test('insert triggers list-level notifyListeners', () {
      list.insert(0, ChatMessageModel.assistantMessage('hi', id: 'm-1'));
      expect(notifyCount, 1);
    });

    test('operator []= triggers list-level notifyListeners', () {
      list.insert(0, ChatMessageModel.assistantMessage('hi', id: 'm-1'));
      expect(notifyCount, 1);
      notifyCount = 0;

      list[0] = ChatMessageModel.assistantMessage('hi there', id: 'm-1');
      expect(
        notifyCount,
        1,
        reason:
            'in-place content updates must notify list listeners so that '
            'observers (chat_widgets._handleObservableMessagesChanged) can rebuild',
      );
      expect(list[0].text, 'hi there');
    });

    test('operator []= records content-kind mutation', () {
      list.insert(0, ChatMessageModel.assistantMessage('hi', id: 'm-1'));
      list[0] = list[0].copyWith(
        content: <String, dynamic>{'text': 'hi there', 'id': 'm-1'},
      );
      expect(list.lastMutationKind, ChatMessageListMutationKind.content);
    });

    test('per-item notifier still fires on operator []=', () {
      list.insert(0, ChatMessageModel.assistantMessage('hi', id: 'm-1'));
      var perItemNotifyCount = 0;
      ChatMessageModel? lastObserved;
      list.listenableAt(0).addListener(() {
        perItemNotifyCount += 1;
        lastObserved = list[0];
      });

      list[0] = ChatMessageModel.assistantMessage('hi there', id: 'm-1');
      expect(perItemNotifyCount, 1);
      expect(lastObserved?.text, 'hi there');
    });

    test('inserting an existing id updates its row without adding a slot', () {
      list.insert(0, ChatMessageModel.assistantMessage('old', id: 'm-1'));
      final notifier = list.listenableAt(0);

      list.insert(1, ChatMessageModel.assistantMessage('latest', id: 'm-1'));

      expect(list, hasLength(1));
      expect(list.single.text, 'latest');
      expect(list.listenableAt(0), same(notifier));
    });
  });
}
