import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/voice_playback_coordinator.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const methodChannel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  const voiceChannel = MethodChannel('cn.com.omnimind.bot/VoicePlayback');
  final coordinator = ChatConversationRuntimeCoordinator.instance;
  late StreamController<Map<String, dynamic>> events;

  Map<String, dynamic> messageChunk({
    required String turnId,
    required String sessionId,
    required String text,
    int? conversationId,
    String messageId = 'message-1',
  }) {
    return <String, dynamic>{
      'conversationId': ?conversationId,
      'sessionId': sessionId,
      'allowImplicitTurnAdmission': true,
      'agentId': 'xiaowan-acp',
      'turnId': turnId,
      'message': <String, dynamic>{
        'method': 'session/update',
        'params': <String, dynamic>{
          'turnId': turnId,
          'sessionId': sessionId,
          'update': <String, dynamic>{
            'sessionUpdate': 'agent_message_chunk',
            'messageId': messageId,
            'content': <String, dynamic>{'text': text},
          },
        },
      },
    };
  }

  ChatRuntimeRoutingContext pageContext({
    String activeMode = kChatRuntimeModeAgent,
    int? agentConversationId,
    int? normalConversationId,
    ChatRuntimeRemoteRoutingContext? remote,
  }) {
    return ChatRuntimeRoutingContext.page(
      activeMode: activeMode,
      conversationIdsByMode: <String, int?>{
        kChatRuntimeModeAgent: agentConversationId,
        kChatRuntimeModeNormal: normalConversationId,
        kChatRuntimeModeOpenClaw: null,
      },
      remote: remote,
    );
  }

  setUp(() async {
    coordinator.resetForTest();
    await VoicePlaybackCoordinator.instance.debugResetForTest();
    events = StreamController<Map<String, dynamic>>.broadcast(sync: true);
    coordinator.agentEventSource = () => events.stream;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          switch (call.method) {
            case 'getConversations':
            case 'getSceneModelBindings':
              return <Map<String, dynamic>>[];
            case 'getSceneVoiceConfig':
              return <String, dynamic>{'autoPlay': false};
            default:
              return 'SUCCESS';
          }
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(voiceChannel, (call) async => true);
    coordinator.ensureInitialized();
  });

  tearDown(() async {
    coordinator.resetForTest();
    await events.close();
    await VoicePlaybackCoordinator.instance.debugResetForTest();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(voiceChannel, null);
  });

  group('read-only runtime view', () {
    test('is cached per runtime and rejects writes', () {
      final view = coordinator.ensureRuntime(
        conversationId: 7101,
        mode: kChatRuntimeModeAgent,
        initialMessages: <ChatMessageModel>[
          ChatMessageModel.userMessage('hello', id: 'u1'),
        ],
      );
      expect(
        identical(
          view,
          coordinator.runtimeFor(
            conversationId: 7101,
            mode: kChatRuntimeModeAgent,
          ),
        ),
        isTrue,
      );
      expect(identical(view.messages, view.messages), isTrue);
      expect(view.messages.single.id, 'u1');
      expect(
        () => view.messages.add(ChatMessageModel.userMessage('x')),
        throwsUnsupportedError,
      );
      expect(() => view.messages.clear(), throwsUnsupportedError);
      expect(
        () => view.messages[0] = ChatMessageModel.userMessage('x'),
        throwsUnsupportedError,
      );
      expect(
        () => view.currentAiMessages['task'] = 'text',
        throwsUnsupportedError,
      );
      expect(view.messages.single.id, 'u1');
    });

    test('message commands are the write path and notify row listeners', () {
      final view = coordinator.ensureRuntime(
        conversationId: 7102,
        mode: kChatRuntimeModeNormal,
      );
      var notifications = 0;
      view.messages.addListener(() => notifications += 1);
      void insert(String id, {int index = 0}) =>
          coordinator.insertRuntimeMessage(
            conversationId: 7102,
            mode: kChatRuntimeModeNormal,
            message: ChatMessageModel.userMessage(id, id: id),
            index: index,
          );

      insert('a');
      insert('b');
      insert('c');
      expect(view.messages.map((m) => m.id), <String>['c', 'b', 'a']);

      // An existing id is replaced in place, as the list always did.
      coordinator.insertRuntimeMessage(
        conversationId: 7102,
        mode: kChatRuntimeModeNormal,
        message: ChatMessageModel.assistantMessage('updated', id: 'b'),
      );
      expect(view.messages.map((m) => m.id), <String>['c', 'b', 'a']);
      expect(view.messages[1].text, 'updated');

      expect(
        coordinator.replaceRuntimeMessage(
          conversationId: 7102,
          mode: kChatRuntimeModeNormal,
          messageId: 'a',
          message: ChatMessageModel.assistantMessage('A', id: 'a'),
        ),
        isTrue,
      );
      expect(view.messages.last.text, 'A');
      expect(
        coordinator.replaceRuntimeMessage(
          conversationId: 7102,
          mode: kChatRuntimeModeNormal,
          messageId: 'missing',
          message: ChatMessageModel.userMessage('x'),
        ),
        isFalse,
      );

      coordinator.removeLeadingRuntimeMessages(
        conversationId: 7102,
        mode: kChatRuntimeModeNormal,
        count: 1,
      );
      expect(view.messages.map((m) => m.id), <String>['b', 'a']);
      coordinator.appendRuntimeMessages(
        conversationId: 7102,
        mode: kChatRuntimeModeNormal,
        messages: <ChatMessageModel>[ChatMessageModel.userMessage('z', id: 'z')],
      );
      expect(view.messages.map((m) => m.id), <String>['b', 'a', 'z']);
      coordinator.removeRuntimeMessages(
        conversationId: 7102,
        mode: kChatRuntimeModeNormal,
        messageIds: <String>['a', 'z'],
      );
      expect(view.messages.map((m) => m.id), <String>['b']);
      coordinator.replaceRuntimeMessages(
        conversationId: 7102,
        mode: kChatRuntimeModeNormal,
        messages: const <ChatMessageModel>[],
      );
      expect(view.messages, isEmpty);
      expect(notifications, 9);
    });

    test('presentation commands update only the addressed runtime', () {
      final normal = coordinator.ensureRuntime(
        conversationId: 7103,
        mode: kChatRuntimeModeNormal,
      );
      final agent = coordinator.ensureRuntime(
        conversationId: 7103,
        mode: kChatRuntimeModeAgent,
      );
      coordinator.updateRuntimePresentation(
        conversationId: 7103,
        mode: kChatRuntimeModeNormal,
        isAiResponding: true,
        deepThinkingContent: 'thinking',
        chatIslandDisplayLayer: ChatIslandDisplayLayer.tools,
      );
      coordinator.setRuntimeDispatchTurnId(
        conversationId: 7103,
        mode: kChatRuntimeModeNormal,
        turnId: 'local-1',
      );
      expect(normal.isAiResponding, isTrue);
      expect(normal.deepThinkingContent, 'thinking');
      expect(normal.chatIslandDisplayLayer, ChatIslandDisplayLayer.tools);
      expect(normal.currentDispatchTurnId, 'local-1');
      expect(agent.isAiResponding, isFalse);
      expect(agent.currentDispatchTurnId, isNull);
    });
  });

  group('runtime event route', () {
    test('projects events only while a surface is attached', () {
      coordinator.beginAcpTurn(
        taskId: 'turn-1',
        conversationId: 7201,
        mode: kChatRuntimeModeAgent,
      );
      events.add(
        messageChunk(
          conversationId: 7201,
          turnId: 'turn-1',
          sessionId: 'session-1',
          text: 'ignored',
        ),
      );
      final view = coordinator.runtimeFor(
        conversationId: 7201,
        mode: kChatRuntimeModeAgent,
      )!;
      expect(view.messages.where((m) => m.user == 2), isEmpty);

      final outcomes = <ChatRuntimeEventOutcome>[];
      final host = coordinator.attachEventHost(
        context: () => pageContext(agentConversationId: 7201),
        onOutcome: outcomes.add,
      );
      events.add(
        messageChunk(
          conversationId: 7201,
          turnId: 'turn-1',
          sessionId: 'session-1',
          text: 'projected',
        ),
      );
      expect(outcomes, hasLength(1));
      expect(outcomes.single.conversationId, 7201);
      expect(outcomes.single.mode, kChatRuntimeModeAgent);
      expect(outcomes.single.result.handled, isTrue);
      expect(
        view.messages.where((m) => m.user == 2).single.text,
        contains('projected'),
      );

      host.detach();
      events.add(
        messageChunk(
          conversationId: 7201,
          turnId: 'turn-1',
          sessionId: 'session-1',
          text: ' after detach',
          messageId: 'message-2',
        ),
      );
      expect(outcomes, hasLength(1));
      expect(view.messages.where((m) => m.user == 2), hasLength(1));
    });

    test('applies an event once and delivers it to every surface', () {
      coordinator.beginAcpTurn(
        taskId: 'turn-a',
        conversationId: 7202,
        mode: kChatRuntimeModeAgent,
      );
      final pageOutcomes = <ChatRuntimeEventOutcome>[];
      final sheetOutcomes = <ChatRuntimeEventOutcome>[];
      coordinator.attachEventHost(
        context: () => pageContext(agentConversationId: 7202),
        onOutcome: pageOutcomes.add,
      );
      coordinator.attachEventHost(
        context: () => const ChatRuntimeRoutingContext.dispatchScoped(
          conversationId: 9999,
          mode: 'command_overlay',
        ),
        onOutcome: sheetOutcomes.add,
      );
      final outcome = coordinator.routeAgentEvent(
        messageChunk(
          conversationId: 7202,
          turnId: 'turn-a',
          sessionId: 'session-a',
          text: 'once',
        ),
      );
      expect(outcome?.mode, kChatRuntimeModeAgent);
      expect(pageOutcomes, hasLength(1));
      expect(sheetOutcomes, hasLength(1));
      expect(
        coordinator.runtimeFor(conversationId: 9999, mode: 'command_overlay'),
        isNull,
      );
    });

    test('resolves an event without a host conversation id by identity', () {
      coordinator.beginAcpTurn(
        taskId: 'turn-bg',
        conversationId: 7203,
        mode: kChatRuntimeModeAgent,
      );
      coordinator.bindAcpSession(
        taskId: 'turn-bg',
        conversationId: 7203,
        mode: kChatRuntimeModeAgent,
        sessionId: 'session-bg',
      );
      coordinator.attachEventHost(
        // The visible conversation is a different one.
        context: () => pageContext(agentConversationId: 1),
        onOutcome: (_) {},
      );
      final outcome = coordinator.routeAgentEvent(
        messageChunk(
          turnId: 'turn-bg',
          sessionId: 'session-bg',
          text: 'background',
        ),
      );
      expect(outcome?.conversationId, 7203);
      expect(outcome?.mode, kChatRuntimeModeAgent);
    });

    test('drops identity-less events that no owner can claim', () {
      coordinator.attachEventHost(
        context: () => pageContext(agentConversationId: 7204),
        onOutcome: (_) {},
      );
      final unknownSession = coordinator.routeAgentEvent(
        messageChunk(turnId: 'stray', sessionId: 'stray', text: 'x'),
      );
      expect(unknownSession, isNull);
      expect(
        coordinator.runtimeFor(
          conversationId: 7204,
          mode: kChatRuntimeModeAgent,
        ),
        isNull,
      );
      // An identity-less error is the one shape that falls back to the
      // visible Agent conversation.
      final error = coordinator.routeAgentEvent(<String, dynamic>{
        'method': 'error',
        'message': <String, dynamic>{
          'method': 'error',
          'params': <String, dynamic>{'error': 'boom'},
        },
      });
      expect(error?.conversationId, 7204);
    });

    test('a dispatch-scoped surface claims only its own conversation', () {
      coordinator.beginAcpTurn(
        taskId: 'sheet-turn',
        conversationId: 7205,
        mode: 'command_overlay',
      );
      var inFlight = true;
      coordinator.attachEventHost(
        context: () => inFlight
            ? const ChatRuntimeRoutingContext.dispatchScoped(
                conversationId: 7205,
                mode: 'command_overlay',
              )
            : null,
        onOutcome: (_) {},
      );
      expect(
        coordinator.routeAgentEvent(
          messageChunk(turnId: 'sheet-turn', sessionId: 's', text: 'no id'),
        ),
        isNull,
      );
      expect(
        coordinator.routeAgentEvent(
          messageChunk(
            conversationId: 7206,
            turnId: 'sheet-turn',
            sessionId: 's',
            text: 'other',
          ),
        ),
        isNull,
      );
      final claimed = coordinator.routeAgentEvent(
        messageChunk(
          conversationId: 7205,
          turnId: 'sheet-turn',
          sessionId: 's',
          text: 'mine',
        ),
      );
      expect(claimed?.mode, 'command_overlay');
      expect(claimed?.result.handled, isTrue);
      inFlight = false;
      expect(
        coordinator.routeAgentEvent(
          messageChunk(
            conversationId: 7205,
            turnId: 'sheet-turn',
            sessionId: 's',
            text: 'late',
          ),
        ),
        isNull,
      );
    });

    test('promotes the active remote thread into the visible runtime', () {
      const threadId = 'remote-thread-1';
      final runtimeId = remoteAgentRuntimeIdForThread(threadId);
      final pending = ChatMessageModel.userMessage('queued', id: 'pending-u');
      final outcomes = <ChatRuntimeEventOutcome>[];
      coordinator.attachEventHost(
        context: () => pageContext(
          agentConversationId: null,
          remote: ChatRuntimeRemoteRoutingContext(
            activeThreadId: threadId,
            agentFallbackMessages: <ChatMessageModel>[pending],
            agentConversation: ConversationModel(
              id: 0,
              title: 'Remote',
              status: 0,
              messageCount: 1,
              createdAt: 1,
              updatedAt: 1,
            ),
          ),
        ),
        onOutcome: outcomes.add,
      );
      coordinator.routeAgentEvent(<String, dynamic>{
        'method': 'error',
        'threadId': threadId,
        'message': <String, dynamic>{
          'method': 'error',
          'params': <String, dynamic>{'error': 'boom'},
        },
      });
      expect(outcomes.single.conversationId, runtimeId);
      expect(outcomes.single.promotedRemoteThreadId, threadId);
      final runtime = coordinator.runtimeFor(
        conversationId: runtimeId,
        mode: kChatRuntimeModeAgent,
      )!;
      expect(runtime.messages.any((m) => m.id == 'pending-u'), isTrue);
      expect(runtime.conversation?.id, runtimeId);
      expect(runtime.conversation?.title, 'Remote');
      expect(
        coordinator.isEphemeralRuntime(
          conversationId: runtimeId,
          mode: kChatRuntimeModeAgent,
        ),
        isTrue,
      );
    });
  });
}
