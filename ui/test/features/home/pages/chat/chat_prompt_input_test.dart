import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:shared_preferences_platform_interface/shared_preferences_platform_interface.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/services/conversation_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final coordinator = ChatConversationRuntimeCoordinator.instance;
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const agentChannel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  final calls = <MethodCall>[];
  final replies = <Completer<Map<String, dynamic>>>[];

  ChatConversationRuntimeState start(int id) {
    final turn = 'original-$id-ai';
    final runtime = coordinator.ensureEphemeralRuntime(
      conversationId: id,
      mode: kChatRuntimeModeNormal,
    );
    coordinator.beginAcpTurn(
      taskId: turn,
      conversationId: id,
      mode: kChatRuntimeModeNormal,
    );
    coordinator.bindAcpSession(
      taskId: turn,
      conversationId: id,
      mode: kChatRuntimeModeNormal,
      sessionId: 'session-$id',
    );
    runtime.activeAcpTurnId = turn;
    return runtime;
  }

  Future<void> enqueue(int id, String name) => coordinator.enqueuePrompt(
    conversationId: id,
    mode: kChatRuntimeModeNormal,
    prompt: ChatQueuedPrompt(
      id: name,
      sessionId: 'session-$id',
      agentId: 'xiaowan-acp',
      text: name,
      conversationMode: 'normal',
    ),
  );

  void finish(int id, String reason) => coordinator.applyAcpPromptResponse(
    taskId: 'original-$id-ai',
    conversationId: id,
    mode: kChatRuntimeModeNormal,
    sessionId: 'session-$id',
    turnId: 'original-$id-ai',
    stopReason: reason,
  );

  Future<void> flush() async {
    for (var i = 0; i < 12; i++) {
      await Future<void>.delayed(Duration.zero);
    }
  }

  void echoSteering(int id, String requestId, String text) {
    coordinator.applyAgentEvent(
      conversationId: id,
      mode: kChatRuntimeModeNormal,
      event: {
        'conversationId': id,
        'sessionId': 'session-$id',
        'turnId': 'original-$id-ai',
        'method': 'session/update',
        'params': {
          'sessionId': 'session-$id',
          'update': {
            'sessionUpdate': 'user_message_chunk',
            'messageId': '$requestId-user',
            'content': {'type': 'text', 'text': text},
            '_meta': {'steering': true, 'requestId': requestId},
          },
        },
      },
    );
  }

  setUp(() async {
    coordinator.resetForTest();
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    calls.clear();
    replies.clear();
    for (final name in [
      'cn.com.omnimind.bot/AgentRuntimeEvents',
      'cn.com.omnimind.bot/AssistCoreEvent',
      'cn.com.omnimind.bot/VoicePlayback',
    ]) {
      messenger.setMockMethodCallHandler(
        MethodChannel(name),
        (_) async => null,
      );
    }
    messenger.setMockMethodCallHandler(agentChannel, (call) async {
      if (call.method != 'session/prompt' && call.method != '_session/steering')
        return <String, dynamic>{};
      calls.add(call);
      final reply = Completer<Map<String, dynamic>>();
      replies.add(reply);
      return reply.future;
    });
  });

  tearDown(() async {
    for (final reply in replies) {
      if (!reply.isCompleted) reply.complete({'stopReason': 'cancelled'});
    }
    await flush();
    coordinator.resetForTest();
    messenger.setMockMethodCallHandler(agentChannel, null);
  });

  test(
    'queued prompts wait for official response and stay with their session',
    () async {
      final first = start(6101);
      final other = start(6102);
      await enqueue(6101, 'follow-up-a');
      await enqueue(6101, 'follow-up-b');
      await enqueue(6102, 'other-conversation');
      expect(calls, isEmpty);
      expect(first.messages, isEmpty);
      expect(first.activeAcpTurnId, 'original-6101-ai');
      finish(6101, 'end_turn');
      await flush();
      expect(calls, hasLength(1));
      expect(calls.single.method, 'session/prompt');
      expect((calls.single.arguments as Map)['sessionId'], 'session-6101');
      expect((calls.single.arguments as Map)['text'], 'follow-up-a');
      expect(first.pendingPrompts.single.text, 'follow-up-b');
      expect(other.pendingPrompts.single.text, 'other-conversation');
      replies.first.complete({
        'sessionId': 'session-6101',
        'stopReason': 'end_turn',
      });
      await flush();
      expect(calls, hasLength(2));
      expect((calls[1].arguments as Map)['text'], 'follow-up-b');
      expect(
        first.messages
            .where((message) => message.user == 1)
            .map((message) => message.text),
        ['follow-up-b', 'follow-up-a'],
      );
      expect(other.activeAcpTurnId, 'original-6102-ai');
    },
  );

  for (final reason in ['cancelled', 'error', 'max_tokens', 'refusal']) {
    test('$reason keeps queued drafts paused until explicit resume', () async {
      final runtime = start(6103);
      await enqueue(6103, 'keep this task');
      finish(6103, reason);
      await flush();
      expect(calls, isEmpty);
      expect(runtime.promptQueuePaused, isTrue);
      expect(runtime.pendingPrompts.single.text, 'keep this task');
      coordinator.resumePromptQueue(
        conversationId: 6103,
        mode: kChatRuntimeModeNormal,
      );
      await flush();
      expect(calls, hasLength(1));
    });
  }

  test(
    'restored queue is paused, removable and never automatically replayed',
    () async {
      start(6104);
      await enqueue(6104, 'durable draft');
      coordinator.resetForTest();
      final runtime = coordinator.ensureEphemeralRuntime(
        conversationId: 6104,
        mode: kChatRuntimeModeNormal,
      );
      await flush();
      expect(runtime.pendingPrompts.single.text, 'durable draft');
      expect(runtime.promptQueuePaused, isTrue);
      expect(calls, isEmpty);
      coordinator.removeQueuedPrompt(
        conversationId: 6104,
        mode: kChatRuntimeModeNormal,
        promptId: 'durable draft',
      );
      await flush();
      coordinator.resetForTest();
      final reloaded = coordinator.ensureEphemeralRuntime(
        conversationId: 6104,
        mode: kChatRuntimeModeNormal,
      );
      expect(reloaded.pendingPrompts, isEmpty);
    },
  );

  test(
    'steering preserves the active turn and distinct user item exactly once',
    () async {
      final runtime = start(6105);
      runtime.messages.add(
        ChatMessageModel(
          id: 'original-6105-user',
          type: 1,
          user: 1,
          content: {'text': 'Original task'},
        ),
      );
      final steering = coordinator.steerActivePrompt(
        conversationId: 6105,
        mode: kChatRuntimeModeNormal,
        agentId: 'xiaowan-acp',
        text: 'New constraint',
        requestId: 'steer-one',
      );
      await flush();
      expect(calls.single.method, '_session/steering');
      expect((calls.single.arguments as Map)['_meta'], {
        'requestId': 'steer-one',
        'expectedTurnId': 'original-6105-ai',
        'steering': {'idleBehavior': 'promptRequired'},
      });
      final echo = <String, dynamic>{
        'conversationId': 6105,
        'sessionId': 'session-6105',
        'turnId': 'original-6105-ai',
        'method': 'session/update',
        'params': {
          'sessionId': 'session-6105',
          'update': {
            'sessionUpdate': 'user_message_chunk',
            'messageId': 'steer-one-user',
            'content': {'type': 'text', 'text': 'New constraint'},
            '_meta': {'steering': true, 'requestId': 'steer-one'},
          },
        },
      };
      coordinator.applyAgentEvent(
        conversationId: 6105,
        mode: kChatRuntimeModeNormal,
        event: echo,
      );
      coordinator.applyAgentEvent(
        conversationId: 6105,
        mode: kChatRuntimeModeNormal,
        event: echo,
      );
      replies.single.complete({'outcome': 'injected'});
      expect(await steering, isTrue);
      expect(
        runtime.messages
            .where((message) => message.user == 1)
            .map((message) => message.text),
        ['New constraint', 'Original task'],
      );
      expect(runtime.activeAcpTurnId, 'original-6105-ai');
      expect(runtime.hasInFlightTask, isTrue);
      expect(runtime.pendingPrompts, isEmpty);
    },
  );

  test('uncertain steering retries keep request identity without queuing a second turn', () async {
    final runtime = start(6106);
    final first = coordinator.steerActivePrompt(
      conversationId: 6106,
      mode: kChatRuntimeModeNormal,
      agentId: 'xiaowan-acp',
      text: 'Same requirement',
      requestId: 'stable-input',
    );
    final firstFailure = expectLater(first, throwsA(isA<PlatformException>()));
    await flush();
    replies[0].completeError(PlatformException(code: 'TRANSPORT_TIMEOUT'));
    await firstFailure;
    expect(runtime.pendingPrompts, isEmpty);
    expect(runtime.activeAcpTurnId, 'original-6106-ai');
    final retry = coordinator.steerActivePrompt(
      conversationId: 6106,
      mode: kChatRuntimeModeNormal,
      agentId: 'xiaowan-acp',
      text: 'Same requirement',
      requestId: 'new-ui-attempt',
    );
    await flush();
    expect(
      ((calls[1].arguments as Map)['_meta'] as Map)['requestId'],
      'stable-input',
    );
    expect(calls.map((call) => call.method), [
      '_session/steering',
      '_session/steering',
    ]);
    echoSteering(6106, 'stable-input', 'Same requirement');
    replies[1].complete({'outcome': 'injected'});
    expect(await retry, isTrue);
    expect(runtime.hasInFlightTask, isTrue);
  });

  test(
    'a steering error after its official user echo is already accepted',
    () async {
      final runtime = start(6107);
      final request = coordinator.steerActivePrompt(
        conversationId: 6107,
        mode: kChatRuntimeModeNormal,
        agentId: 'xiaowan-acp',
        text: 'New requirement',
        requestId: 'echo-receipt',
      );
      await flush();
      runtime.messages.add(
        ChatMessageModel(
          id: 'echo-item',
          type: 1,
          user: 1,
          content: {'text': 'New requirement'},
          streamMeta: {'steeringRequestId': 'echo-receipt'},
        ),
      );
      replies.single.completeError(
        PlatformException(code: 'TRANSPORT_TIMEOUT'),
      );
      expect(await request, isTrue);
      expect(runtime.pendingPrompts, isEmpty);
      expect(calls, hasLength(1));
      expect(runtime.activeAcpTurnId, 'original-6107-ai');
    },
  );

  test('steering persists receipt before RPC and waits for official echo before acknowledgement', () async {
    final runtime = start(6108);
    var accepted = false;
    final request = coordinator
        .steerActivePrompt(
          conversationId: 6108,
          mode: kChatRuntimeModeNormal,
          agentId: 'xiaowan-acp',
          text: 'Keep this exact input',
          requestId: 'durable-input',
        )
        .then((value) {
          accepted = value;
          return value;
        });
    await flush();
    expect(
      StorageService.getJson<Map<String, dynamic>>(
        'chat.pending_prompts.v1.6108.normal.steering',
      )?['id'],
      'durable-input',
    );
    replies.single.complete({'outcome': 'injected'});
    await flush();
    expect(accepted, isFalse);
    expect(runtime.isSteeringInputPending, isTrue);
    echoSteering(6108, 'durable-input', 'Keep this exact input');
    expect(await request, isTrue);
    expect(
      StorageService.getString('chat.pending_prompts.v1.6108.normal.steering'),
      isNull,
    );
  });

  test('unconfirmed steering survives restart without becoming an executable prompt', () async {
    start(6109);
    final request = coordinator.steerActivePrompt(
      conversationId: 6109,
      mode: kChatRuntimeModeNormal,
      agentId: 'xiaowan-acp',
      text: 'Unconfirmed requirement',
      requestId: 'uncertain-input',
    );
    final failure = expectLater(request, throwsA(isA<PlatformException>()));
    await flush();
    replies.single.completeError(PlatformException(code: 'TRANSPORT_TIMEOUT'));
    await failure;
    coordinator.resetForTest();
    final restored = coordinator.ensureEphemeralRuntime(
      conversationId: 6109,
      mode: kChatRuntimeModeNormal,
    );
    expect(restored.unconfirmedSteeringText, 'Unconfirmed requirement');
    expect(restored.promptQueuePaused, isTrue);
    expect(restored.pendingPrompts, isEmpty);
    await flush();
    expect(calls, hasLength(1));
  });

  test(
    'stop while steering is pending never restarts a late promptRequired input',
    () async {
      final runtime = start(6110);
      final request = coordinator.steerActivePrompt(
        conversationId: 6110,
        mode: kChatRuntimeModeNormal,
        agentId: 'xiaowan-acp',
        text: 'Late input',
        requestId: 'late-input',
      );
      await flush();
      coordinator.pausePromptQueue(
        conversationId: 6110,
        mode: kChatRuntimeModeNormal,
      );
      finish(6110, 'cancelled');
      replies.single.complete({
        'outcome': 'promptRequired',
        'reason': 'noRunningTurn',
      });
      expect(await request, isFalse);
      await enqueue(6110, 'Late input');
      await flush();
      expect(runtime.pendingPrompts.single.text, 'Late input');
      expect(runtime.promptQueuePaused, isTrue);
      expect(calls.map((call) => call.method), ['_session/steering']);
    },
  );

  test(
    'deleting a conversation removes queued drafts and uncertain receipts',
    () async {
      final runtime = start(6111);
      await enqueue(6111, 'Delete this draft');
      await StorageService.setJson(
        'chat.pending_prompts.v1.6111.normal.steering',
        {
          'id': 'receipt',
          'text': 'Old input',
          'sessionId': 'session-6111',
          'turnId': 'turn',
        },
      );
      messenger.setMockMethodCallHandler(
        const MethodChannel('cn.com.omnimind.bot/AssistCoreEvent'),
        (call) async => call.method == 'manageConversation',
      );
      expect(await ConversationService.deleteConversation(6111), isTrue);
      expect(runtime.pendingPrompts, isEmpty);
      expect(
        StorageService.getString('chat.pending_prompts.v1.6111.normal'),
        isNull,
      );
      expect(
        StorageService.getString(
          'chat.pending_prompts.v1.6111.normal.steering',
        ),
        isNull,
      );
      coordinator.resetForTest();
      final restored = coordinator.ensureEphemeralRuntime(
        conversationId: 6111,
        mode: kChatRuntimeModeNormal,
      );
      expect(restored.pendingPrompts, isEmpty);
      expect(restored.unconfirmedSteeringText, isNull);
    },
  );

  test('steering acknowledgement and receipt removal wait for native history commit', () async {
    const id = 6112;
    final runtime = coordinator.ensureRuntime(
      conversationId: id,
      mode: kChatRuntimeModeNormal,
    );
    coordinator.beginAcpTurn(
      taskId: 'original-$id-ai',
      conversationId: id,
      mode: kChatRuntimeModeNormal,
    );
    coordinator.bindAcpSession(
      taskId: 'original-$id-ai',
      conversationId: id,
      mode: kChatRuntimeModeNormal,
      sessionId: 'session-$id',
    );
    runtime.activeAcpTurnId = 'original-$id-ai';
    final writing = Completer<void>();
    final release = Completer<void>();
    messenger.setMockMethodCallHandler(
      const MethodChannel('cn.com.omnimind.bot/AssistCoreEvent'),
      (call) async {
        if (call.method == 'replaceConversationMessages') {
          if (!writing.isCompleted) writing.complete();
          await release.future;
        }
        return 'SUCCESS';
      },
    );
    var accepted = false;
    final request = coordinator
        .steerActivePrompt(
          conversationId: id,
          mode: kChatRuntimeModeNormal,
          agentId: 'xiaowan-acp',
          text: 'Persist before clearing',
          requestId: 'await-commit',
        )
        .then((value) {
          accepted = value;
          return value;
        });
    await flush();
    replies.single.complete({'outcome': 'injected'});
    echoSteering(id, 'await-commit', 'Persist before clearing');
    await writing.future;
    expect(accepted, isFalse);
    expect(
      StorageService.getString('chat.pending_prompts.v1.$id.normal.steering'),
      isNotNull,
    );
    release.complete();
    expect(await request, isTrue);
    expect(
      StorageService.getString('chat.pending_prompts.v1.$id.normal.steering'),
      isNull,
    );
  });

  test('failed queue admission cannot execute when the active prompt completes during storage', () async {
    final runtime = start(6113);
    final store = _BlockingFirstQueueWrite(
      'chat.pending_prompts.v1.6113.normal',
    );
    SharedPreferencesStorePlatform.instance = store;
    final admission = enqueue(6113, 'must not execute');
    final rejected = expectLater(admission, throwsStateError);
    await store.entered.future;
    finish(6113, 'end_turn');
    await flush();
    expect(calls, isEmpty);
    expect(runtime.hasInFlightTask, isFalse);
    store.release.complete(false);
    await rejected;
    await flush();
    expect(calls, isEmpty);
    expect(runtime.pendingPrompts, isEmpty);
    expect(
      StorageService.getJson<List<dynamic>>(
        'chat.pending_prompts.v1.6113.normal',
      ),
      isEmpty,
    );
  });

  test('stop during queued preparation releases the unsent reservation and preserves one draft', () async {
    const id = 6114;
    final runtime = coordinator.ensureRuntime(
      conversationId: id,
      mode: kChatRuntimeModeNormal,
    );
    coordinator.beginAcpTurn(
      taskId: 'original-$id-ai',
      conversationId: id,
      mode: kChatRuntimeModeNormal,
    );
    coordinator.bindAcpSession(
      taskId: 'original-$id-ai',
      conversationId: id,
      mode: kChatRuntimeModeNormal,
      sessionId: 'session-$id',
    );
    runtime.activeAcpTurnId = 'original-$id-ai';
    await enqueue(id, 'prepared draft');
    final writing = Completer<void>();
    final release = Completer<void>();
    messenger.setMockMethodCallHandler(
      const MethodChannel('cn.com.omnimind.bot/AssistCoreEvent'),
      (call) async {
        if (call.method == 'replaceConversationMessages') {
          if (!writing.isCompleted) writing.complete();
          await release.future;
        }
        return 'SUCCESS';
      },
    );
    finish(id, 'end_turn');
    await writing.future;
    coordinator.pausePromptQueue(
      conversationId: id,
      mode: kChatRuntimeModeNormal,
    );
    release.complete();
    await flush();
    expect(calls, isEmpty);
    expect(runtime.hasInFlightTask, isFalse);
    expect(runtime.promptQueuePaused, isTrue);
    expect(runtime.pendingPrompts.single.text, 'prepared draft');
    expect(
      runtime.messages.where((message) => message.user == 1),
      hasLength(1),
    );
    coordinator.resumePromptQueue(
      conversationId: id,
      mode: kChatRuntimeModeNormal,
    );
    await flush();
    expect(calls, hasLength(1));
    expect((calls.single.arguments as Map)['text'], 'prepared draft');
    expect(
      runtime.messages.where((message) => message.user == 1),
      hasLength(1),
    );
  });

  test(
    'a different steering draft cannot replace an unconfirmed receipt',
    () async {
      final runtime = start(6115);
      final original = coordinator.steerActivePrompt(
        conversationId: 6115,
        mode: kChatRuntimeModeNormal,
        agentId: 'xiaowan-acp',
        text: 'Original uncertain input',
        requestId: 'original-receipt',
      );
      final rejected = expectLater(original, throwsA(isA<PlatformException>()));
      await flush();
      replies.single.completeError(
        PlatformException(code: 'TRANSPORT_TIMEOUT'),
      );
      await rejected;
      await expectLater(
        coordinator.steerActivePrompt(
          conversationId: 6115,
          mode: kChatRuntimeModeNormal,
          agentId: 'xiaowan-acp',
          text: 'Different input',
          requestId: 'replacement',
        ),
        throwsStateError,
      );
      expect(calls, hasLength(1));
      expect(runtime.unconfirmedSteeringText, 'Original uncertain input');
      expect(
        StorageService.getJson<Map<String, dynamic>>(
          'chat.pending_prompts.v1.6115.normal.steering',
        )?['text'],
        'Original uncertain input',
      );
    },
  );
}

class _BlockingFirstQueueWrite extends InMemorySharedPreferencesStore {
  _BlockingFirstQueueWrite(this.queueKey) : super.empty();
  final String queueKey;
  final entered = Completer<void>();
  final release = Completer<bool>();
  bool blocked = false;

  @override
  Future<bool> setValue(String valueType, String key, Object value) async {
    if (!blocked && key.endsWith(queueKey)) {
      blocked = true;
      entered.complete();
      if (!await release.future) return false;
    }
    return super.setValue(valueType, key, value);
  }
}
