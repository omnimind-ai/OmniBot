import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';

void main() {
  test('every Harness switch creates an Agent conversation target', () {
    for (final agentId in <String>[
      'xiaowan-acp',
      'codex-acp',
      'claude-acp',
      'dsh-acp',
    ]) {
      final target = buildHarnessSwitchTarget(
        agentId: agentId,
        agentRuntime: 'local',
        requestKey: 'request-$agentId',
      );

      expect(target.mode, ConversationMode.agent, reason: agentId);
      expect(target.agentId, agentId);
      expect(target.isNewConversation, isTrue);
    }
  });

  test(
    'Harness switch send barrier releases queued submits once idle',
    () async {
      final barrier = HarnessSwitchSendBarrier();
      final generation = barrier.begin();
      var resumed = 0;

      final first = barrier.waitUntilIdle().then((_) => resumed += 1);
      final second = barrier.waitUntilIdle().then((_) => resumed += 1);
      await Future<void>.delayed(Duration.zero);
      expect(resumed, 0);
      expect(barrier.isActive, isTrue);

      barrier.finish(generation);
      await Future.wait<void>(<Future<void>>[first, second]);
      expect(resumed, 2);
      expect(barrier.isActive, isFalse);
    },
  );

  test(
    'stale Harness switch completion cannot release queued submits',
    () async {
      final barrier = HarnessSwitchSendBarrier();
      final firstGeneration = barrier.begin();
      final secondGeneration = barrier.begin();
      var resumed = false;

      final waiting = barrier.waitUntilIdle().then((_) => resumed = true);
      barrier.finish(firstGeneration);
      await Future<void>.delayed(Duration.zero);

      expect(resumed, isFalse);
      expect(barrier.isActive, isTrue);
      expect(barrier.isCurrent(secondGeneration), isTrue);

      barrier.finish(secondGeneration);
      await waiting;
      expect(resumed, isTrue);
      expect(barrier.isActive, isFalse);
    },
  );

  test(
    'Harness switch queue reconciles a non-cancellable selection to the latest tap',
    () async {
      final barrier = HarnessSwitchSendBarrier();
      final firstGeneration = barrier.begin();
      final firstStarted = Completer<void>();
      final releaseFirst = Completer<void>();
      final applied = <String>[];

      final first = barrier.runIfCurrent(firstGeneration, () async {
        firstStarted.complete();
        await releaseFirst.future;
        applied.add('first');
      });
      await firstStarted.future;

      final skippedGeneration = barrier.begin();
      final skipped = barrier.runIfCurrent(skippedGeneration, () async {
        applied.add('skipped');
      });
      final latestGeneration = barrier.begin();
      final latest = barrier.runIfCurrent(latestGeneration, () async {
        applied.add('latest');
      });

      releaseFirst.complete();
      await Future.wait([first, skipped, latest]);

      expect(applied, ['first', 'latest']);
      barrier.finish(latestGeneration);
      expect(barrier.isActive, isFalse);
    },
  );

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
          reason: 'messages_replaced',
          hasInFlightTask: true,
        ),
        isFalse,
      );
      expect(
        shouldReloadConversationMessagesChanged(
          reason: 'external_user_message',
          hasInFlightTask: true,
        ),
        isTrue,
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
      final original = ChatMessageModel.assistantMessage('hi', id: 'm-1');
      list.insert(0, original);
      list[0] = original.copyWith(
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
  });
}
