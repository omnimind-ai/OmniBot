import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/conversation_service.dart';
import 'package:ui/services/conversation_history_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final coordinator = ChatConversationRuntimeCoordinator.instance;
  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  ConversationModel conversation(int id, String agent) => ConversationModel(
    id: id,
    title: 'private-$id',
    status: 0,
    messageCount: 1,
    createdAt: 1,
    updatedAt: 1,
    agentId: agent,
    mode: ConversationMode.agent,
  );
  Future<void> changed([String? prefix]) async {
    final completed = Completer<void>();
    await messenger.handlePlatformMessage(
      channel.name,
      const StandardMethodCodec().encodeMethodCall(
        MethodCall('onConversationListChanged', {
          'reason': 'na_history_owner_changed',
          'naOwnerPrefix': prefix,
        }),
      ),
      (_) => completed.complete(),
    );
    await completed.future;
    await Future<void>.delayed(Duration.zero);
  }

  setUp(() {
    coordinator.resetForTest();
    AssistsMessageService.hasNaHistoryOwnerSnapshot = false;
    AssistsMessageService.visibleNaOwnerPrefix = null;
    messenger.setMockMethodCallHandler(channel, (_) async => null);
    messenger.setMockMethodCallHandler(
      const MethodChannel('cn.com.omnimind.bot/VoicePlayback'),
      (_) async => null,
    );
    coordinator.ensureInitialized();
  });
  tearDown(coordinator.resetForTest);

  test(
    'logout evicts only Na views and late ACP events cannot restore them',
    () async {
      final alice = conversation(101, 'na-cloud');
      for (final c in [
        alice,
        conversation(102, 'xiaowan-acp'),
        conversation(103, 'my-byok-agent'),
      ]) {
        final runtime = coordinator.ensureRuntime(
          conversationId: c.id,
          mode: kChatRuntimeModeAgent,
          conversation: c,
          initialMessages: [ChatMessageModel.userMessage(c.title)],
        );
        if (c == alice)
          runtime.activeAcpSessionId = 'na:aaaaaaaaaaaaaaaa:session';
      }
      await changed();
      expect(
        coordinator.runtimeFor(
          conversationId: 101,
          mode: kChatRuntimeModeAgent,
        ),
        isNull,
      );
      expect(
        coordinator
            .runtimeFor(conversationId: 102, mode: kChatRuntimeModeAgent)!
            .messages
            .single
            .text,
        'private-102',
      );
      expect(
        coordinator
            .runtimeFor(conversationId: 103, mode: kChatRuntimeModeAgent)!
            .messages
            .single
            .text,
        'private-103',
      );
      final result = coordinator.applyAgentEvent(
        conversationId: 101,
        conversation: alice,
        event: {
          'method': 'session/update',
          'params': {
            'sessionId': 'na:aaaaaaaaaaaaaaaa:session',
            'update': {
              'sessionUpdate': 'agent_message_chunk',
              'content': {'type': 'text', 'text': 'late secret'},
            },
          },
        },
      );
      expect(result.handled, isFalse);
      coordinator.replaceConversationSnapshot(
        conversationId: 101,
        mode: kChatRuntimeModeAgent,
        conversation: alice,
        messages: [ChatMessageModel.userMessage('late snapshot')],
      );
      await coordinator.persistConversationMessageSnapshot(
        conversationId: 101,
        mode: kChatRuntimeModeAgent,
        messages: [ChatMessageModel.userMessage('late preview')],
        conversation: alice,
      );
      expect(
        coordinator.runtimeFor(
          conversationId: 101,
          mode: kChatRuntimeModeAgent,
        ),
        isNull,
      );
      // A later successful native load by Alice restores the durable history.
      coordinator.restoreNaHistoryView(alice);
      coordinator.replaceConversationSnapshot(
        conversationId: 101,
        mode: kChatRuntimeModeAgent,
        conversation: alice,
        messages: [ChatMessageModel.userMessage('saved history')],
      );
      expect(
        coordinator
            .runtimeFor(conversationId: 101, mode: kChatRuntimeModeAgent)!
            .messages
            .single
            .text,
        'saved history',
      );
    },
  );

  test(
    'first late Na event for an uncached conversation is rejected after switch',
    () async {
      await changed('na:bbbbbbbbbbbbbbbb:');
      final result = coordinator.applyAgentEvent(
        conversationId: 999,
        event: {
          'method': 'session/update',
          'params': {
            'sessionId': 'na:aaaaaaaaaaaaaaaa:unseen',
            'update': {
              'sessionUpdate': 'agent_message_chunk',
              'content': {'type': 'text', 'text': 'Alice secret'},
            },
          },
        },
      );
      expect(result.handled, isFalse);
      expect(
        coordinator.runtimeFor(
          conversationId: 999,
          mode: kChatRuntimeModeAgent,
        ),
        isNull,
      );
    },
  );

  test(
    'an old account list finishing after logout cannot repopulate Na titles',
    () async {
      final reply = Completer<List<Map<String, dynamic>>>();
      messenger.setMockMethodCallHandler(channel, (_) => reply.future);
      final pending = ConversationService.getAllConversations(
        includeArchived: true,
      );
      await Future<void>.delayed(Duration.zero);
      await changed();
      reply.complete([
        conversation(1, 'na-cloud').toJson(),
        conversation(2, 'xiaowan-acp').toJson(),
      ]);
      expect((await pending).map((c) => c.id), [2]);
    },
  );

  test('late full history and transcript cannot cross a logout', () async {
    final history = Completer<List<Object>>();
    final transcript = Completer<String>();
    final calls = <String>[];
    messenger.setMockMethodCallHandler(channel, (call) {
      calls.add(call.method);
      return switch (call.method) {
        'getConversationMessages' => history.future,
        'getConversationTranscript' => transcript.future,
        _ => Future<Object?>.value(null),
      };
    });
    final read = ConversationHistoryService.readConversationHistory(101);
    final copy = ConversationHistoryService.copyConversation(101);
    final rejected = expectLater(read, throwsStateError);
    await Future<void>.delayed(Duration.zero);
    await changed();
    history.complete([ChatMessageModel.userMessage('Alice secret').toJson()]);
    transcript.complete('Alice transcript');
    await rejected;
    expect(await copy, isFalse);
    expect(calls, isNot(contains('copyToClipboard')));
  });
}
