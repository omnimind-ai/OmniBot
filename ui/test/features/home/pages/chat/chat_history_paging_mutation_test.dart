import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/models/chat_message_model.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final coordinator = ChatConversationRuntimeCoordinator.instance;

  setUp(coordinator.resetForTest);
  tearDown(() {
    coordinator.resetForTest();
    messenger.setMockMethodCallHandler(channel, null);
  });

  ChatConversationRuntimeState runtime() => coordinator.ensureRuntime(
    conversationId: 71001,
    mode: kChatRuntimeModeAgent,
    initialMessages: [
      ChatMessageModel.assistantMessage('remove', id: 'remove'),
      ChatMessageModel.userMessage('retain', id: 'retain'),
    ],
  )..historyPage = (hasMore: false, nextOffset: 50);

  Future<void> removeOne(ChatConversationRuntimeState state) =>
      coordinator.persistConversationMessageSnapshot(
        conversationId: state.conversationId,
        mode: state.mode,
        messages: state.messages
            .where((message) => message.id != 'remove')
            .toList(),
        allowHistoryRemoval: true,
        removedMessageIds: ['remove'],
      );

  test(
    'history cursor resets and notifies only after native removal commits',
    () async {
      final commit = Completer<Object?>();
      final enteredWrite = Completer<void>();
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'replaceConversationMessages') {
          enteredWrite.complete();
          return commit.future;
        }
        return 'SUCCESS';
      });
      final state = runtime();
      final observed = <({bool hasMore, int nextOffset})?>[];
      void listener() => observed.add(state.historyPage);
      coordinator.addListener(listener);
      try {
        final removing = removeOne(state);
        await enteredWrite.future;
        expect(state.historyPage, (hasMore: false, nextOffset: 50));
        expect(state.historyPageRevision, 0);
        commit.complete('SUCCESS');
        await removing;
        expect(state.historyPage, (hasMore: true, nextOffset: 0));
        expect(state.historyPageRevision, 1);
        expect(observed.last, (hasMore: true, nextOffset: 0));
      } finally {
        coordinator.removeListener(listener);
      }
    },
  );

  test(
    'native removal failure propagates without resetting the cursor',
    () async {
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'replaceConversationMessages') {
          throw PlatformException(code: 'DATABASE_WRITE_FAILED');
        }
        return 'SUCCESS';
      });
      final state = runtime();
      await expectLater(removeOne(state), throwsStateError);
      expect(state.historyPage, (hasMore: false, nextOffset: 50));
      expect(state.historyPageRevision, 0);
    },
  );
}
