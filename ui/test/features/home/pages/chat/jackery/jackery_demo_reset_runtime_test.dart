import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final coordinator = ChatConversationRuntimeCoordinator.instance;
  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final calls = <MethodCall>[];

  setUp(() {
    coordinator.resetForTest();
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          return 'SUCCESS';
        });
  });
  tearDown(() {
    coordinator.resetForTest();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  void prime(int id, String mode) {
    coordinator.ensureRuntime(
      conversationId: id,
      mode: mode,
      conversation: ConversationModel(
        id: id,
        title: 'demo',
        status: 0,
        mode: ConversationMode.chatOnly,
        messageCount: 1,
        createdAt: 1,
        updatedAt: 1,
      ),
      initialMessages: [
        ChatMessageModel(
          id: 'user-$id',
          type: 1,
          user: 1,
          createAt: DateTime(2026),
          content: {'text': 'A committed question'},
        ),
      ],
    );
  }

  test(
    'reset drains admitted persistence and fences new writes until deletion',
    () async {
      prime(1, kChatRuntimeModeNormal);
      prime(2, kChatRuntimeModeAgent);
      coordinator.schedulePersistRuntimeConversation(
        conversationId: 1,
        mode: kChatRuntimeModeNormal,
        persistMessages: true,
        delay: const Duration(seconds: 30),
      );
      await coordinator.prepareForDemoReset();
      expect(coordinator.demoResetInProgress, isTrue);
      expect(
        calls.where((call) => call.method == 'updateConversation'),
        isNotEmpty,
      );
      final drained = calls.length;
      await coordinator.persistRuntimeConversation(
        conversationId: 2,
        mode: kChatRuntimeModeAgent,
        persistMessages: true,
      );
      coordinator.schedulePersistRuntimeConversation(
        conversationId: 2,
        mode: kChatRuntimeModeAgent,
        delay: Duration.zero,
      );
      await Future<void>.delayed(Duration.zero);
      expect(calls.length, drained);
      await coordinator.clearAfterDemoReset();
      expect(
        coordinator.runtimeFor(conversationId: 1, mode: kChatRuntimeModeNormal),
        isNull,
      );
      expect(
        coordinator.runtimeFor(conversationId: 2, mode: kChatRuntimeModeAgent),
        isNull,
      );
      expect(coordinator.demoResetInProgress, isFalse);
      await coordinator.persistRuntimeConversation(
        conversationId: 1,
        mode: kChatRuntimeModeNormal,
      );
      expect(
        calls.length,
        drained,
        reason: 'Deleted histories cannot be resurrected by old page disposal',
      );
    },
  );

  test('failed reset releases fence and retains committed history', () async {
    prime(3, kChatRuntimeModeNormal);
    await coordinator.prepareForDemoReset();
    await coordinator.abortDemoReset();
    expect(
      coordinator
          .runtimeFor(conversationId: 3, mode: kChatRuntimeModeNormal)!
          .messages
          .single
          .text,
      'A committed question',
    );
    await coordinator.persistRuntimeConversation(
      conversationId: 3,
      mode: kChatRuntimeModeNormal,
    );
    expect(
      calls.where((call) => call.method == 'updateConversation'),
      isNotEmpty,
    );
  });
}
