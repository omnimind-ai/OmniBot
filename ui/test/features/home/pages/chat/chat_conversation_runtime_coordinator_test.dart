import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/voice_playback_coordinator.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channelName = 'cn.com.omnimind.bot/AssistCoreEvent';
  const codec = StandardMethodCodec();
  const methodChannel = MethodChannel(channelName);
  const voiceChannel = MethodChannel('cn.com.omnimind.bot/VoicePlayback');
  final coordinator = ChatConversationRuntimeCoordinator.instance;
  final recordedMethodCalls = <MethodCall>[];

  Map<String, dynamic> acpEvent(
    String method, {
    required String turnId,
    String? sessionId,
    Map<String, dynamic> params = const <String, dynamic>{},
    String agentId = 'xiaowan-acp',
    String agentName = '小万',
    int? conversationId,
    bool hostAssignedTurn = false,
  }) {
    return <String, dynamic>{
      if (conversationId != null) 'conversationId': conversationId,
      if (sessionId != null) 'sessionId': sessionId,
      // A host reservation can admit the first event when the Agent omits
      // turn/started. Once an explicit session is present, later events must
      // still prove that they belong to the admitted session.
      if (hostAssignedTurn || method == 'turn/started' || sessionId == null)
        'allowImplicitTurnAdmission': true,
      'agentId': agentId,
      'agentName': agentName,
      'threadId': turnId,
      'turnId': turnId,
      'message': <String, dynamic>{
        'method': method,
        'params': <String, dynamic>{
          if (!hostAssignedTurn) 'turnId': turnId,
          if (sessionId != null) 'sessionId': sessionId,
          ...params,
        },
      },
    };
  }

  void applyAcp(
    int conversationId,
    String method, {
    required String turnId,
    String? sessionId,
    Map<String, dynamic> params = const <String, dynamic>{},
    String mode = kChatRuntimeModeAgent,
    String agentId = 'xiaowan-acp',
    String agentName = '小万',
    bool hostAssignedTurn = false,
  }) {
    if (method == 'turn/started' &&
        coordinator
                .runtimeFor(conversationId: conversationId, mode: mode)
                ?.currentDispatchTurnId ==
            null) {
      coordinator.beginAcpTurn(
        taskId: turnId,
        conversationId: conversationId,
        mode: mode,
      );
    }
    coordinator.applyAgentEvent(
      conversationId: conversationId,
      mode: mode,
      event: acpEvent(
        method,
        turnId: turnId,
        sessionId: sessionId,
        params: params,
        agentId: agentId,
        agentName: agentName,
        conversationId: conversationId,
        hostAssignedTurn: hostAssignedTurn,
      ),
    );
  }

  void completePrompt(
    int conversationId, {
    required String turnId,
    String? sessionId,
    String mode = kChatRuntimeModeAgent,
    Map<String, dynamic> params = const {},
  }) {
    final runtime = coordinator.runtimeFor(
      conversationId: conversationId,
      mode: mode,
    )!;
    coordinator.applyAcpPromptResponse(
      taskId: runtime.activeRunId ?? runtime.currentDispatchTurnId ?? turnId,
      conversationId: conversationId,
      mode: mode,
      sessionId: sessionId ?? runtime.activeAcpSessionId,
      turnId: turnId,
      stopReason: params['stopReason'] as String? ?? 'end_turn',
    );
  }

  Future<void> emitPlatformEvent(String method, [dynamic arguments]) async {
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .handlePlatformMessage(
          channelName,
          codec.encodeMethodCall(MethodCall(method, arguments)),
          (ByteData? _) {},
        );
    await Future<void>.delayed(Duration.zero);
  }

  setUp(() async {
    coordinator.resetForTest();
    await VoicePlaybackCoordinator.instance.debugResetForTest();
    recordedMethodCalls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          recordedMethodCalls.add(call);
          switch (call.method) {
            case 'getConversations':
              return <Map<String, dynamic>>[];
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
    await VoicePlaybackCoordinator.instance.debugResetForTest();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(voiceChannel, null);
  });

  test('renders ACP assistant, reasoning, and tool updates in one turn', () {
    const conversationId = 2002;
    const turnId = 'turn-xiaowan';
    applyAcp(conversationId, 'turn/started', turnId: turnId);
    applyAcp(
      conversationId,
      'session/update',
      turnId: turnId,
      params: <String, dynamic>{
        'sessionId': turnId,
        'update': <String, dynamic>{
          'sessionUpdate': 'agent_thought_chunk',
          'messageId': 'thought-1',
          'content': <String, dynamic>{'text': '先分析任务。'},
        },
      },
    );
    applyAcp(
      conversationId,
      'session/update',
      turnId: turnId,
      params: <String, dynamic>{
        'sessionId': turnId,
        'update': <String, dynamic>{
          'sessionUpdate': 'agent_message_chunk',
          'messageId': 'message-1',
          'content': <String, dynamic>{'text': '已经开始处理。'},
        },
      },
    );
    applyAcp(
      conversationId,
      'session/update',
      turnId: turnId,
      params: <String, dynamic>{
        'sessionId': turnId,
        'update': <String, dynamic>{
          'sessionUpdate': 'tool_call',
          'toolCallId': 'tool-1',
          'kind': 'execute',
          'title': '检查工作区',
          'status': 'running',
        },
      },
    );

    final runtime = coordinator.runtimeFor(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    )!;
    expect(runtime.messages.any((message) => message.user == 2), isTrue);
    expect(
      runtime.messages.any(
        (message) => message.cardData?['type'] == 'deep_thinking',
      ),
      isTrue,
    );
    expect(
      runtime.messages.any(
        (message) => message.cardData?['type'] == 'agent_tool_summary',
      ),
      isTrue,
    );
    expect(runtime.isAiResponding, isTrue);
  });

  test('projects the official session prompt response without turn events', () {
    const conversationId = 2048;
    coordinator.beginAcpTurn(
      taskId: 'local-prompt',
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );
    coordinator.bindAcpSession(
      taskId: 'local-prompt',
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
      sessionId: 'session-official',
    );
    coordinator.applyAgentEvent(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
      event: acpEvent(
        'turn/started',
        turnId: 'turn-official',
        sessionId: 'session-official',
      ),
    );
    coordinator.applyAgentEvent(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
      event: acpEvent(
        'session/update',
        turnId: 'turn-official',
        sessionId: 'session-official',
        params: <String, dynamic>{
          'update': <String, dynamic>{
            'sessionUpdate': 'agent_message_chunk',
            'messageId': 'message-official',
            'content': <String, dynamic>{'text': 'ACP 输出'},
          },
        },
      ),
    );
    final result = coordinator.applyAcpPromptResponse(
      taskId: 'local-prompt',
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
      sessionId: 'session-official',
      turnId: 'turn-official',
      stopReason: 'end_turn',
    );
    final runtime = coordinator.runtimeFor(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    )!;

    expect(result.method, 'session/prompt');
    expect(runtime.isAiResponding, isFalse);
    expect(runtime.activeAcpTurnId, isNull);
    expect(runtime.messages.any((message) => message.text == 'ACP 输出'), isTrue);
  });

  test(
    'persists legacy normal ACP events into canonical agent history',
    () async {
      const conversationId = 2005;
      const turnId = 'turn-xiaowan-normal-history';

      applyAcp(
        conversationId,
        'turn/started',
        turnId: turnId,
        mode: kChatRuntimeModeNormal,
      );
      applyAcp(
        conversationId,
        'session/update',
        turnId: turnId,
        mode: kChatRuntimeModeNormal,
        params: <String, dynamic>{
          'sessionId': 'session-xiaowan-normal-history',
          'update': <String, dynamic>{
            'sessionUpdate': 'agent_message_chunk',
            'messageId': 'message-normal-history',
            'content': <String, dynamic>{'text': '第一轮回复'},
          },
        },
      );

      await Future<void>.delayed(const Duration(milliseconds: 500));

      final replaceCalls = recordedMethodCalls
          .where((call) => call.method == 'replaceConversationMessages')
          .toList();
      expect(replaceCalls, isNotEmpty);
      expect(replaceCalls.last.arguments['conversationId'], conversationId);
      expect(
        replaceCalls.last.arguments['mode'],
        ConversationMode.agent.storageValue,
      );
    },
  );

  test(
    'begins a turn without a visible thinking placeholder before ACP output',
    () {
      const conversationId = 2003;
      const taskId = 'local-task-before-acp';

      coordinator.beginAcpTurn(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );
      final generationAfterFirstBegin = coordinator
          .runtimeFor(
            conversationId: conversationId,
            mode: kChatRuntimeModeAgent,
          )!
          .persistenceGeneration;
      coordinator.beginAcpTurn(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );

      final runtime = coordinator.runtimeFor(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      )!;
      expect(runtime.persistenceGeneration, generationAfterFirstBegin);
      expect(runtime.isAiResponding, isTrue);
      expect(runtime.currentDispatchTurnId, taskId);
      expect(
        runtime.messages
            .where((message) => message.cardData?['type'] == 'deep_thinking')
            .length,
        0,
      );
    },
  );

  test(
    'admits an official session update without wire turn id via host reservation',
    () {
      const conversationId = 2004;
      const localRunId = 'local-reserved-turn';
      coordinator.beginAcpTurn(
        taskId: localRunId,
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );

      final result = coordinator.applyAgentEvent(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
        event: <String, dynamic>{
          'method': 'session/update',
          'allowImplicitTurnAdmission': true,
          'params': <String, dynamic>{
            'sessionId': 'session-no-wire-turn',
            'update': <String, dynamic>{
              'sessionUpdate': 'agent_message_chunk',
              'messageId': 'message-no-wire-turn',
              'content': <String, dynamic>{
                'type': 'text',
                'text': '标准 ACP session/update',
              },
            },
          },
        },
      );

      final runtime = coordinator.runtimeFor(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      )!;
      expect(result.handled, isTrue);
      expect(runtime.messages.single.text, '标准 ACP session/update');
      expect(runtime.activeAcpSessionId, 'session-no-wire-turn');
      expect(runtime.activeAcpTurnId, isNull);
      expect(runtime.activeRunId, localRunId);
    },
  );

  test('keeps the local run identity separate from the official ACP turn', () {
    final runtime = coordinator.ensureRuntime(
      conversationId: 2008,
      mode: kChatRuntimeModeAgent,
    );

    runtime.currentDispatchTurnId = 'local-run-1';
    runtime.activeAcpTurnId = 'acp-turn-1';

    expect(runtime.activeRunId, 'local-run-1');
    expect(runtime.currentDispatchTurnId, 'local-run-1');
    expect(runtime.activeAcpTurnId, 'acp-turn-1');

    runtime.currentDispatchTurnId = null;
    expect(runtime.activeRunId, isNull);
    expect(runtime.activeAcpTurnId, 'acp-turn-1');
  });

  test('projection buffers do not keep a completed runtime in flight', () {
    final runtime = coordinator.ensureRuntime(
      conversationId: 2009,
      mode: kChatRuntimeModeAgent,
    );

    // A stream can terminate between writing a chunk and clearing its cache.
    // Those buffers must not become a second lifecycle fact source.
    runtime.currentAiMessages['message-1'] = 'partial answer';
    runtime.currentThinkingMessages['message-1'] = 'partial reasoning';

    expect(runtime.hasInFlightTask, isFalse);
    expect(runtime.activeAgentTurnIds, isEmpty);
  });

  test('routes ACP lifecycle by admitted turn identity', () {
    final runtime = coordinator.ensureRuntime(
      conversationId: 42,
      mode: kChatRuntimeModeNormal,
    );
    runtime.activeAcpTurnId = 'turn-normal-1';
    runtime.currentDispatchTurnId = 'turn-normal-1';

    expect(
      coordinator.modeForAcpEvent(conversationId: 42, turnId: 'turn-normal-1'),
      kChatRuntimeModeNormal,
    );
    expect(
      coordinator.modeForAcpEvent(conversationId: 42, turnId: 'turn-agent-1'),
      isNull,
    );
  });

  test('retains ACP dedupe and turn ownership across a long conversation', () {
    final runtime = coordinator.ensureRuntime(
      conversationId: 4201,
      mode: kChatRuntimeModeAgent,
    );
    for (var index = 0; index < 700; index += 1) {
      runtime.rememberProcessedAcpEventId('event-$index');
      runtime.rememberCompletedAcpTurn('turn-$index');
      runtime.resolveRunId(
        sessionId: 'session-$index',
        turnId: 'turn-$index',
        fallback: 'run-$index',
      );
    }

    expect(runtime.processedAcpEventIds, hasLength(700));
    expect(runtime.completedAcpTurnIds, hasLength(700));
    expect(runtime.acpTurnToRunIds, hasLength(700));
    expect(runtime.processedAcpEventIds, contains('event-0'));
    expect(runtime.processedAcpEventIds, contains('event-699'));
    expect(
      runtime.resolveKnownRunId(sessionId: 'session-0', turnId: 'turn-0'),
      'run-0',
    );
    expect(
      runtime.resolveKnownRunId(sessionId: 'session-699', turnId: 'turn-699'),
      'run-699',
    );
  });

  test('routes a known legacy process to its owning conversation', () {
    final runtime = coordinator.ensureRuntime(
      conversationId: 4202,
      mode: kChatRuntimeModeAgent,
    );
    runtime.standaloneProcessOwner('process-known', 'turn-1');

    expect(
      coordinator.conversationIdForStandaloneProcess('process-known'),
      4202,
    );
    expect(
      coordinator.conversationIdForStandaloneProcess('process-unknown'),
      isNull,
    );
  });

  test('does not restore a completed run as an active timeline group', () {
    const conversationId = 2004;
    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );
    runtime.isAiResponding = true;
    runtime.isExecutingTask = true;
    runtime.currentDispatchTurnId = 'completed-run';
    runtime.activeRunId = 'completed-run';
    runtime.lastAgentTurnId = 'completed-run';
    runtime.activeAcpSessionId = 'old-session';

    coordinator.replaceConversationSnapshot(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
      messages: <ChatMessageModel>[ChatMessageModel.userMessage('已经完成的请求')],
      isAiResponding: false,
      isExecutingTask: false,
      currentDispatchTurnId: null,
      lastAgentTurnId: null,
    );

    expect(runtime.activeAgentTurnIds, isEmpty);
    expect(runtime.activeRunId, isNull);
    expect(runtime.currentDispatchTurnId, isNull);
    expect(runtime.activeAcpSessionId, isNull);
  });

  test('an idle snapshot cannot demote an admitted ACP turn', () {
    const conversationId = 2005;
    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );
    coordinator.registerTask(
      taskId: 'live-run',
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );
    coordinator.beginAcpTurn(
      taskId: 'live-run',
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );
    runtime.activeAcpSessionId = 'live-session';
    runtime.messages.add(
      ChatMessageModel.userMessage('正在执行的请求', id: 'live-user'),
    );
    expect(
      coordinator.isTaskActive(
        taskId: 'live-run',
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      ),
      isTrue,
    );

    coordinator.replaceConversationSnapshot(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
      messages: <ChatMessageModel>[
        ChatMessageModel.userMessage('旧的历史快照', id: 'history-user'),
      ],
      isAiResponding: false,
      isExecutingTask: false,
    );

    expect(runtime.isAiResponding, isTrue);
    expect(runtime.currentDispatchTurnId, 'live-run');
    expect(runtime.activeRunId, 'live-run');
    expect(runtime.activeAcpSessionId, 'live-session');
    expect(
      runtime.messages.map((message) => message.text),
      containsAll(<String>['正在执行的请求', '旧的历史快照']),
    );
  });

  test('an authoritative idle snapshot can finish only its matching turn', () {
    const conversationId = 2008;
    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );
    coordinator.registerTask(
      taskId: 'remote-run',
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );
    coordinator.beginAcpTurn(
      taskId: 'remote-run',
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );
    runtime.activeAcpSessionId = 'remote-thread';
    runtime.activeAcpTurnId = 'remote-turn';

    expect(
      coordinator.finishTaskFromAuthoritativeSnapshot(
        taskId: 'remote-run',
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
        sessionId: 'other-thread',
        turnId: 'remote-turn',
      ),
      isFalse,
    );
    expect(runtime.isAiResponding, isTrue);

    expect(
      coordinator.finishTaskFromAuthoritativeSnapshot(
        taskId: 'remote-run',
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
        sessionId: 'remote-thread',
        turnId: 'remote-turn',
      ),
      isTrue,
    );
    expect(runtime.isAiResponding, isFalse);
    expect(runtime.activeRunId, isNull);
    expect(runtime.activeAcpTurnId, isNull);
  });

  test(
    'expires persisted ACP request cards when restoring an idle session',
    () {
      const conversationId = 2006;
      final runtime = coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );

      coordinator.replaceConversationSnapshot(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
        messages: <ChatMessageModel>[
          ChatMessageModel(
            id: 'request-card-1',
            type: 2,
            user: 3,
            content: <String, dynamic>{
              'cardData': <String, dynamic>{
                'type': 'agent_request',
                'requestId': 'request-1',
                'status': 'pending',
                'requestKind': 'user_input',
              },
            },
          ),
        ],
        isAiResponding: false,
        isExecutingTask: false,
      );

      final card = runtime.messages.single.cardData!;
      expect(card['status'], 'expired');
      expect(card['interactionUnavailable'], isTrue);
      expect(card['interactionUnavailableReason'], 'session_ended');
    },
  );

  test('keeps a live ACP request card pending during an active snapshot', () {
    const conversationId = 2007;
    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );

    coordinator.replaceConversationSnapshot(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
      messages: <ChatMessageModel>[
        ChatMessageModel(
          id: 'request-card-live',
          type: 2,
          user: 3,
          content: <String, dynamic>{
            'cardData': <String, dynamic>{
              'type': 'agent_request',
              'requestId': 'request-live',
              'status': 'pending',
              'requestKind': 'user_input',
            },
          },
        ),
      ],
      isAiResponding: true,
      isExecutingTask: true,
    );

    expect(runtime.messages.single.cardData?['status'], 'pending');
    expect(runtime.messages.single.cardData?['interactionUnavailable'], isNull);
  });

  test('binds ACP events to one session as well as one turn', () {
    const conversationId = 43;
    applyAcp(
      conversationId,
      'turn/started',
      turnId: 'turn-current',
      sessionId: 'session-current',
    );

    final runtime = coordinator.runtimeFor(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    )!;
    expect(runtime.activeAcpSessionId, 'session-current');
    expect(
      coordinator.modeForAcpEvent(
        conversationId: conversationId,
        sessionId: 'session-current',
      ),
      kChatRuntimeModeAgent,
    );

    applyAcp(
      conversationId,
      'session/update',
      turnId: 'turn-stale',
      sessionId: 'session-old',
      params: <String, dynamic>{
        'update': <String, dynamic>{
          'sessionUpdate': 'agent_message_chunk',
          'messageId': 'stale-message',
          'content': <String, dynamic>{'text': 'stale'},
        },
      },
    );

    expect(runtime.messages, isEmpty);

    runtime.activeAcpTurnId = null;
    runtime.currentDispatchTurnId = 'local-new-turn';
    runtime.isAiResponding = true;
    applyAcp(
      conversationId,
      'session/update',
      sessionId: 'session-next',
      turnId: 'late-old-turn',
      params: <String, dynamic>{'delta': 'new session'},
    );
    expect(runtime.activeAcpSessionId, 'session-current');

    applyAcp(
      conversationId,
      'turn/started',
      turnId: 'turn-next',
      sessionId: 'session-next',
    );
    expect(runtime.activeAcpSessionId, 'session-next');
  });

  test('does not let a completed old session reclaim a new Xiaowan turn', () {
    const conversationId = 44;
    applyAcp(
      conversationId,
      'turn/started',
      turnId: 'turn-xiaowan-old',
      sessionId: 'session-xiaowan-old',
    );
    completePrompt(
      conversationId,
      turnId: 'turn-xiaowan-old',
      sessionId: 'session-xiaowan-old',
    );

    coordinator.primeAcpThinking(
      taskId: 'local-xiaowan-new',
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );
    applyAcp(
      conversationId,
      'session/update',
      turnId: 'turn-xiaowan-old',
      sessionId: 'session-xiaowan-old',
      params: <String, dynamic>{
        'update': <String, dynamic>{
          'sessionUpdate': 'agent_message_chunk',
          'messageId': 'late-old-message',
          'content': <String, dynamic>{'text': '旧会话延迟输出'},
        },
      },
    );

    final runtime = coordinator.runtimeFor(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    )!;
    expect(runtime.activeAcpSessionId, 'session-xiaowan-old');
    expect(
      runtime.messages.where((message) => message.text == '旧会话延迟输出'),
      isEmpty,
    );

    applyAcp(
      conversationId,
      'turn/started',
      turnId: 'turn-xiaowan-new',
      sessionId: 'session-xiaowan-new',
    );
    expect(runtime.activeAcpSessionId, 'session-xiaowan-new');
    expect(runtime.activeAcpTurnId, 'turn-xiaowan-new');
  });

  test(
    'ignores a stale private terminal event without claiming the current turn',
    () {
      const conversationId = 45;
      applyAcp(
        conversationId,
        'turn/started',
        turnId: 'turn-current',
        sessionId: 'session-current',
      );

      final result = coordinator.applyAgentEvent(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
        event: acpEvent(
          'turn/completed',
          turnId: 'turn-old',
          sessionId: 'session-current',
        ),
      );
      final runtime = coordinator.runtimeFor(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      )!;

      expect(result.handled, isFalse);
      expect(result.affectsActiveTurn, isFalse);
      expect(runtime.activeAcpTurnId, 'turn-current');
      expect(runtime.isAiResponding, isTrue);
      // A stale event must not learn the current render id merely because the
      // current turn is active. Otherwise a later stale update can be
      // projected into the new turn's message/card scope.
      expect(
        runtime.acpTurnToRunIds.keys,
        isNot(contains('session-current:turn-old')),
      );
    },
  );

  test('marks an event from a rejected session as not current-turn-owned', () {
    const conversationId = 46;
    applyAcp(
      conversationId,
      'turn/started',
      turnId: 'turn-current',
      sessionId: 'session-current',
    );

    final result = coordinator.applyAgentEvent(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
      event: acpEvent(
        'session/update',
        turnId: 'turn-old',
        sessionId: 'session-old',
        params: <String, dynamic>{
          'update': <String, dynamic>{
            'sessionUpdate': 'agent_message_chunk',
            'content': <String, dynamic>{'type': 'text', 'text': '旧输出'},
          },
        },
      ),
    );

    expect(result.handled, isFalse);
    expect(result.affectsActiveTurn, isFalse);
  });

  test('rejects a new unscoped ACP turn without host admission', () {
    const conversationId = 47;
    coordinator.beginAcpTurn(
      taskId: 'local-reservation',
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );

    final result = coordinator.applyAgentEvent(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
      event: <String, dynamic>{
        'method': 'turn/started',
        'turnId': 'unscoped-new-turn',
        'params': <String, dynamic>{'turnId': 'unscoped-new-turn'},
      },
    );

    expect(result.handled, isFalse);
    expect(result.affectsActiveTurn, isFalse);
    expect(
      coordinator
          .runtimeFor(
            conversationId: conversationId,
            mode: kChatRuntimeModeAgent,
          )!
          .activeAcpTurnId,
      isNull,
    );
  });

  test('keeps ACP turns isolated by conversation and finalizes them', () {
    const firstConversation = 2101;
    const secondConversation = 2102;
    coordinator.beginAcpTurn(
      taskId: 'turn-first',
      conversationId: firstConversation,
      mode: kChatRuntimeModeAgent,
    );
    coordinator.beginAcpTurn(
      taskId: 'turn-second',
      conversationId: secondConversation,
      mode: kChatRuntimeModeAgent,
    );
    applyAcp(
      firstConversation,
      'session/update',
      turnId: 'turn-first',
      params: <String, dynamic>{
        'update': <String, dynamic>{
          'sessionUpdate': 'agent_message_chunk',
          'messageId': 'message-first',
          'content': <String, dynamic>{'text': '第一条回复'},
        },
      },
    );
    applyAcp(
      secondConversation,
      'session/update',
      turnId: 'turn-second',
      params: <String, dynamic>{
        'update': <String, dynamic>{
          'sessionUpdate': 'agent_message_chunk',
          'messageId': 'message-second',
          'content': <String, dynamic>{'text': '第二条回复'},
        },
      },
    );
    completePrompt(
      firstConversation,
      turnId: 'turn-first',
      params: <String, dynamic>{'status': 'completed'},
    );

    final first = coordinator.runtimeFor(
      conversationId: firstConversation,
      mode: kChatRuntimeModeAgent,
    )!;
    final second = coordinator.runtimeFor(
      conversationId: secondConversation,
      mode: kChatRuntimeModeAgent,
    )!;
    expect(first.messages.single.text, '第一条回复');
    expect(first.isAiResponding, isFalse);
    expect(second.messages.single.text, '第二条回复');
    expect(second.isAiResponding, isTrue);
  });

  test(
    'routes a session-only background reply to its original conversation after chat switching',
    () {
      const firstConversation = 2104;
      const secondConversation = 2105;
      const firstSession = 'session-first-background';
      const secondSession = 'session-current-visible';

      final first = coordinator.ensureRuntime(
        conversationId: firstConversation,
        mode: kChatRuntimeModeAgent,
        initialMessages: <ChatMessageModel>[
          ChatMessageModel.userMessage('请先整理第一份资料', id: 'first-user'),
        ],
      );
      final second = coordinator.ensureRuntime(
        conversationId: secondConversation,
        mode: kChatRuntimeModeAgent,
        initialMessages: <ChatMessageModel>[
          ChatMessageModel.userMessage('我现在查看第二份资料', id: 'second-user'),
        ],
      );
      applyAcp(
        firstConversation,
        'turn/started',
        turnId: 'turn-first-background',
        sessionId: firstSession,
      );
      applyAcp(
        secondConversation,
        'turn/started',
        turnId: 'turn-second-visible',
        sessionId: secondSession,
      );

      // The user is now viewing the second conversation.  A background ACP
      // update may omit the host conversation id, so the established ACP
      // session identity—not the visible page—must select its owner.
      final owningConversation = coordinator.conversationIdForAcpEvent(
        sessionId: firstSession,
      );
      expect(owningConversation, firstConversation);
      coordinator.applyAgentEvent(
        conversationId: owningConversation!,
        mode: kChatRuntimeModeAgent,
        event: acpEvent(
          'session/update',
          turnId: 'turn-first-background',
          sessionId: firstSession,
          params: const <String, dynamic>{
            'update': <String, dynamic>{
              'sessionUpdate': 'agent_message_chunk',
              'messageId': 'first-background-answer',
              'content': <String, dynamic>{
                'type': 'text',
                'text': '第一份资料已整理完成',
              },
            },
          },
        ),
      );

      expect(
        first.messages.map((message) => message.text),
        containsAll(<String>['请先整理第一份资料', '第一份资料已整理完成']),
      );
      expect(second.messages.map((message) => message.text), <String>[
        '我现在查看第二份资料',
      ]);
      expect(first.activeAcpSessionId, firstSession);
      expect(second.activeAcpSessionId, secondSession);
    },
  );

  test(
    'a cancelled prompt leaves the next user prompt intact when an old terminal event arrives late',
    () {
      const conversationId = 2106;
      const firstTask = 'local-first-task';
      const firstTurn = 'turn-first-cancelled';
      const firstSession = 'session-first-cancelled';
      const secondTask = 'local-second-task';
      const secondTurn = 'turn-second-active';
      const secondSession = 'session-second-active';
      final runtime = coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
        initialMessages: <ChatMessageModel>[
          ChatMessageModel.userMessage('先分析第一件事', id: 'first-user'),
        ],
      );

      coordinator.beginAcpTurn(
        taskId: firstTask,
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );
      applyAcp(
        conversationId,
        'turn/started',
        turnId: firstTurn,
        sessionId: firstSession,
      );
      applyAcp(
        conversationId,
        'session/update',
        turnId: firstTurn,
        sessionId: firstSession,
        params: const <String, dynamic>{
          'update': <String, dynamic>{
            'sessionUpdate': 'agent_message_chunk',
            'messageId': 'first-answer',
            'content': <String, dynamic>{'type': 'text', 'text': '第一件事的部分结果'},
          },
        },
      );
      completePrompt(
        conversationId,
        turnId: firstTurn,
        sessionId: firstSession,
        params: const <String, dynamic>{
          'status': 'completed',
          'stopReason': 'cancelled',
        },
      );
      expect(runtime.isAiResponding, isFalse);

      // The second user message is a new real prompt, never a replay of the
      // cancelled first request.
      runtime.messages.insert(
        0,
        ChatMessageModel.userMessage('改为处理第二件事', id: 'second-user'),
      );
      coordinator.beginAcpTurn(
        taskId: secondTask,
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );
      applyAcp(
        conversationId,
        'turn/started',
        turnId: secondTurn,
        sessionId: secondSession,
      );

      // A duplicated/late terminal event for the first ACP prompt must not
      // retire or attribute the second one.
      applyAcp(
        conversationId,
        'turn/completed',
        turnId: firstTurn,
        sessionId: firstSession,
        params: const <String, dynamic>{
          'status': 'completed',
          'stopReason': 'cancelled',
        },
      );
      expect(runtime.isAiResponding, isTrue);
      expect(runtime.activeAcpTurnId, secondTurn);
      expect(runtime.activeAcpSessionId, secondSession);

      applyAcp(
        conversationId,
        'session/update',
        turnId: secondTurn,
        sessionId: secondSession,
        params: const <String, dynamic>{
          'update': <String, dynamic>{
            'sessionUpdate': 'agent_message_chunk',
            'messageId': 'second-answer',
            'content': <String, dynamic>{'type': 'text', 'text': '第二件事已完成'},
          },
        },
      );
      completePrompt(
        conversationId,
        turnId: secondTurn,
        sessionId: secondSession,
      );

      expect(
        runtime.messages.reversed.map((message) => message.text),
        containsAllInOrder(<String>[
          '先分析第一件事',
          '第一件事的部分结果',
          '改为处理第二件事',
          '第二件事已完成',
        ]),
      );
      expect(runtime.isAiResponding, isFalse);
      expect(runtime.activeAcpTurnId, isNull);
    },
  );

  for (final nextSession in <String>['first-session', 'second-session']) {
    for (final lateStopReason in <String>['cancelled', 'error', 'end_turn']) {
      for (final lateHasTurnId in <bool>[true, false]) {
        test(
          'official prompt cancellation preserves history: next=$nextSession late=$lateStopReason turnId=$lateHasTurnId',
          () async {
            const conversationId = 2110;
            final runtime = coordinator.ensureRuntime(
              conversationId: conversationId,
              mode: kChatRuntimeModeAgent,
              initialMessages: <ChatMessageModel>[
                ChatMessageModel.userMessage('请整理第一份资料', id: 'first-user'),
              ],
            );
            coordinator.beginAcpTurn(
              taskId: 'first-request',
              conversationId: conversationId,
              mode: kChatRuntimeModeAgent,
            );
            expect(
              coordinator.bindAcpSession(
                taskId: 'first-request',
                conversationId: conversationId,
                mode: kChatRuntimeModeAgent,
                sessionId: 'first-session',
              ),
              isTrue,
            );
            applyAcp(
              conversationId,
              'session/update',
              turnId: 'first-turn',
              sessionId: 'first-session',
              hostAssignedTurn: true,
              params: const <String, dynamic>{
                'update': <String, dynamic>{
                  'sessionUpdate': 'agent_message_chunk',
                  'messageId': 'first-answer',
                  'content': <String, dynamic>{
                    'type': 'text',
                    'text': '第一份资料的部分结果',
                  },
                },
              },
            );
            expect(runtime.activeAcpTurnId, 'first-turn');
            coordinator.applyAcpPromptResponse(
              taskId: 'first-request',
              conversationId: conversationId,
              sessionId: 'first-session',
              turnId: 'first-turn',
              stopReason: 'cancelled',
            );
            expect(runtime.isAiResponding, isFalse);

            runtime.messages.insert(
              0,
              ChatMessageModel.userMessage('改为处理第二份资料', id: 'second-user'),
            );
            coordinator.beginAcpTurn(
              taskId: 'second-request',
              conversationId: conversationId,
              mode: kChatRuntimeModeAgent,
            );
            expect(
              coordinator.bindAcpSession(
                taskId: 'second-request',
                conversationId: conversationId,
                mode: kChatRuntimeModeAgent,
                sessionId: nextSession,
              ),
              isTrue,
            );
            // No synthetic turn/started or turn/completed events: a delayed result
            // must be attributed to its original official prompt request, even
            // before the next prompt has produced its first session/update.
            final lateResult = coordinator.applyAcpPromptResponse(
              taskId: 'first-request',
              conversationId: conversationId,
              sessionId: 'first-session',
              turnId: lateHasTurnId ? 'first-turn' : null,
              stopReason: lateStopReason,
              error: lateStopReason == 'error'
                  ? 'Old ACP transport disconnected'
                  : null,
            );
            expect(lateResult.handled, isFalse);
            expect(runtime.isAiResponding, isTrue);
            expect(runtime.activeAcpSessionId, nextSession);
            expect(
              coordinator.isTaskActive(
                taskId: 'second-request',
                conversationId: conversationId,
                mode: kChatRuntimeModeAgent,
              ),
              isTrue,
            );

            applyAcp(
              conversationId,
              'session/update',
              turnId: 'second-turn',
              sessionId: nextSession,
              hostAssignedTurn: true,
              params: const <String, dynamic>{
                'update': <String, dynamic>{
                  'sessionUpdate': 'agent_message_chunk',
                  'messageId': 'second-answer',
                  'content': <String, dynamic>{
                    'type': 'text',
                    'text': '第二份资料已完成',
                  },
                },
              },
            );
            final lateAfterOutput = coordinator.applyAcpPromptResponse(
              taskId: 'first-request',
              conversationId: conversationId,
              sessionId: 'first-session',
              turnId: lateHasTurnId ? 'first-turn' : null,
              stopReason: lateStopReason,
              error: lateStopReason == 'error'
                  ? 'Old ACP transport disconnected'
                  : null,
            );
            expect(lateAfterOutput.handled, isFalse);
            expect(runtime.isAiResponding, isTrue);
            expect(runtime.activeAcpTurnId, 'second-turn');
            coordinator.applyAcpPromptResponse(
              taskId: 'second-request',
              conversationId: conversationId,
              sessionId: nextSession,
              turnId: 'second-turn',
              stopReason: 'end_turn',
            );
            expect(runtime.isAiResponding, isFalse);
            final texts = runtime.messages.reversed.map(
              (message) => message.text,
            );
            expect(
              texts,
              containsAllInOrder(<String>[
                '请整理第一份资料',
                '第一份资料的部分结果',
                '改为处理第二份资料',
                '第二份资料已完成',
              ]),
            );
            expect(
              runtime.messages.where((message) => message.id == 'first-user'),
              hasLength(1),
            );
            expect(
              runtime.messages.where((message) => message.id == 'second-user'),
              hasLength(1),
            );
            await coordinator.flushPendingPersistence(
              conversationId: conversationId,
              mode: kChatRuntimeModeAgent,
            );
            final saved = Map<String, dynamic>.from(
              recordedMethodCalls
                      .lastWhere(
                        (call) => call.method == 'replaceConversationMessages',
                      )
                      .arguments
                  as Map,
            );
            expect(saved['conversationId'], conversationId);
            final savedTexts = (saved['messages'] as List).map(
              (message) => (message as Map)['content']?['text'],
            );
            expect(
              savedTexts,
              containsAll(texts.where((text) => text?.isNotEmpty == true)),
            );
          },
        );
      }
    }
  }

  for (final legacyMethod in [
    'state_change',
    'state_update',
    'thread/status/changed',
    'turn/completed',
    'turn/failed',
    'thread/closed',
    'error',
    'legacy:completed',
    'legacy:error',
  ]) {
    for (final stopReason in ['end_turn', 'cancelled', 'error']) {
      test(
        'legacy status cannot terminate the owning prompt: $legacyMethod $stopReason',
        () {
          const conversationId = 2110;
          const taskId = 'status-request';
          const sessionId = 'status-session';
          coordinator.beginAcpTurn(
            taskId: taskId,
            conversationId: conversationId,
            mode: kChatRuntimeModeAgent,
          );
          coordinator.bindAcpSession(
            taskId: taskId,
            conversationId: conversationId,
            mode: kChatRuntimeModeAgent,
            sessionId: sessionId,
          );
          final runtime = coordinator.runtimeFor(
            conversationId: conversationId,
            mode: kChatRuntimeModeAgent,
          )!;
          for (final status in ['running', 'idle', 'failed', 'cancelled']) {
            coordinator.applyAgentEvent(
              conversationId: conversationId,
              mode: kChatRuntimeModeAgent,
              event: {
                if (legacyMethod.startsWith('legacy:')) ...{
                  'kind': legacyMethod.split(':').last,
                  'taskId': taskId,
                  'error': 'legacy stream failure',
                } else
                  'method': legacyMethod.startsWith('state_')
                      ? 'session/update'
                      : legacyMethod,
                'params': {
                  'sessionId': sessionId,
                  if (!legacyMethod.startsWith('state_')) ...{
                    'status': status,
                    'willRetry': status == 'running',
                    'error': 'legacy status failure',
                  } else
                    'update': {
                      'sessionUpdate': legacyMethod,
                      'state': status,
                      'stopReason': 'error',
                      'error': 'legacy status failure',
                    },
                },
              },
            );
            expect(runtime.isAiResponding, isTrue, reason: status);
            expect(runtime.messages, isEmpty, reason: status);
            expect(
              coordinator.isTaskActive(
                taskId: taskId,
                conversationId: conversationId,
                mode: kChatRuntimeModeAgent,
              ),
              isTrue,
              reason: status,
            );
          }
          final result = coordinator.applyAcpPromptResponse(
            taskId: taskId,
            conversationId: conversationId,
            sessionId: sessionId,
            stopReason: stopReason,
            error: stopReason == 'error' ? 'real provider failure' : null,
          );
          expect(result.handled, isTrue);
          expect(runtime.isAiResponding, isFalse);
          final failures = runtime.messages.where(
            (message) => message.cardData?['title'] == '本轮执行失败',
          );
          expect(failures, hasLength(stopReason == 'error' ? 1 : 0));
          final messageCount = runtime.messages.length;
          expect(
            coordinator
                .applyAcpPromptResponse(
                  taskId: taskId,
                  conversationId: conversationId,
                  sessionId: sessionId,
                  stopReason: 'error',
                  error: 'late duplicate failure',
                )
                .handled,
            isFalse,
          );
          expect(runtime.messages, hasLength(messageCount));
        },
      );
    }
  }

  for (final stopReason in <String>['end_turn', 'cancelled', 'error']) {
    test(
      'official prompt without streamed output ends its own request: $stopReason',
      () {
        const conversationId = 2111;
        final runtime = coordinator.ensureRuntime(
          conversationId: conversationId,
          mode: kChatRuntimeModeAgent,
          initialMessages: <ChatMessageModel>[
            ChatMessageModel.userMessage('执行这个请求', id: 'user-no-stream'),
          ],
        );
        coordinator.beginAcpTurn(
          taskId: 'request-no-stream',
          conversationId: conversationId,
          mode: kChatRuntimeModeAgent,
        );
        coordinator.bindAcpSession(
          taskId: 'request-no-stream',
          conversationId: conversationId,
          mode: kChatRuntimeModeAgent,
          sessionId: 'session-no-stream',
        );
        final result = coordinator.applyAcpPromptResponse(
          taskId: 'request-no-stream',
          conversationId: conversationId,
          sessionId: 'session-no-stream',
          stopReason: stopReason,
        );
        expect(result.handled, isTrue);
        expect(runtime.isAiResponding, isFalse);
        expect(
          runtime.messages.where((message) => message.id == 'user-no-stream'),
          hasLength(1),
        );
        expect(
          coordinator.isTaskActive(
            taskId: 'request-no-stream',
            conversationId: conversationId,
            mode: kChatRuntimeModeAgent,
          ),
          isFalse,
        );
      },
    );
  }

  test(
    'a stale prompt result cannot recreate a discarded conversation runtime',
    () {
      coordinator.beginAcpTurn(
        taskId: 'discarded-request',
        conversationId: 2112,
        mode: kChatRuntimeModeAgent,
      );
      coordinator.resetForTest();
      final result = coordinator.applyAcpPromptResponse(
        taskId: 'discarded-request',
        conversationId: 2112,
        sessionId: 'discarded-session',
        stopReason: 'cancelled',
      );
      expect(result.handled, isFalse);
      expect(
        coordinator.runtimeFor(
          conversationId: 2112,
          mode: kChatRuntimeModeAgent,
        ),
        isNull,
      );
    },
  );

  test('a background conversation still accepts its own prompt response', () {
    for (final id in <int>[2113, 2114]) {
      coordinator.beginAcpTurn(
        taskId: 'request-$id',
        conversationId: id,
        mode: kChatRuntimeModeAgent,
      );
      coordinator.bindAcpSession(
        taskId: 'request-$id',
        conversationId: id,
        mode: kChatRuntimeModeAgent,
        sessionId: 'session-$id',
      );
    }
    final result = coordinator.applyAcpPromptResponse(
      taskId: 'request-2113',
      conversationId: 2113,
      sessionId: 'session-2113',
      stopReason: 'end_turn',
    );
    expect(result.handled, isTrue);
    expect(
      coordinator
          .runtimeFor(conversationId: 2113, mode: kChatRuntimeModeAgent)!
          .isAiResponding,
      isFalse,
    );
    expect(
      coordinator.isTaskActive(
        taskId: 'request-2114',
        conversationId: 2114,
        mode: kChatRuntimeModeAgent,
      ),
      isTrue,
    );
    expect(
      coordinator
          .runtimeFor(conversationId: 2114, mode: kChatRuntimeModeAgent)!
          .isAiResponding,
      isTrue,
    );
  });

  test('keeps DSH ACP reasoning interleaved around tool activity', () {
    const conversationId = 2103;
    const turnId = 'dsh-turn';
    applyAcp(
      conversationId,
      'item/reasoning/delta',
      turnId: turnId,
      agentId: 'deepseek-harness-acp',
      agentName: 'DeepSeek Harness',
      params: <String, dynamic>{'itemId': 'thought-1', 'delta': '第一阶段：分析工作区。'},
    );
    applyAcp(
      conversationId,
      'item/started',
      turnId: turnId,
      agentId: 'deepseek-harness-acp',
      agentName: 'DeepSeek Harness',
      params: <String, dynamic>{
        'item': <String, dynamic>{
          'id': 'tool-1',
          'type': 'commandExecution',
          'command': 'pwd',
          'status': 'running',
        },
      },
    );
    applyAcp(
      conversationId,
      'item/reasoning/delta',
      turnId: turnId,
      agentId: 'deepseek-harness-acp',
      agentName: 'DeepSeek Harness',
      params: <String, dynamic>{'itemId': 'thought-2', 'delta': '第二阶段：根据结果判断。'},
    );

    final runtime = coordinator.runtimeFor(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    )!;
    final thinking = runtime.messages
        .where((message) => message.cardData?['type'] == 'deep_thinking')
        .toList();
    expect(thinking, hasLength(2));
    expect(
      runtime.messages.reversed.map(
        (message) => message.cardData?['type'] ?? 'assistant_text',
      ),
      <String>['deep_thinking', 'agent_tool_summary', 'deep_thinking'],
    );
    expect(
      thinking.reversed.map((message) => message.cardData?['thinkingContent']),
      <String>['第一阶段：分析工作区。', '第二阶段：根据结果判断。'],
    );
  });

  test('persists ACP runtime messages back to native history', () async {
    const conversationId = 2201;
    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );
    runtime.messages.insert(0, ChatMessageModel.userMessage('用户输入'));
    applyAcp(
      conversationId,
      'session/update',
      turnId: 'turn-persist',
      params: <String, dynamic>{
        'update': <String, dynamic>{
          'sessionUpdate': 'agent_message_chunk',
          'messageId': 'message-persist',
          'content': <String, dynamic>{'text': 'ACP 回复'},
        },
      },
    );
    await coordinator.flushPendingPersistence(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );

    final replaceCalls = recordedMethodCalls
        .where((call) => call.method == 'replaceConversationMessages')
        .toList();
    expect(replaceCalls, isNotEmpty);
    final args = Map<String, dynamic>.from(
      (replaceCalls.last.arguments as Map).cast<String, dynamic>(),
    );
    expect(args['conversationId'], conversationId);
    expect(args['mode'], kChatRuntimeModeAgent);
    expect(
      (args['messages'] as List).any(
        (message) => (message as Map)['content']?['text'] == 'ACP 回复',
      ),
      isTrue,
    );
  });

  test(
    'persists an empty snapshot when the caller owns message replacement',
    () async {
      const conversationId = 2200;
      coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );

      await coordinator.persistRuntimeConversation(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
        persistMessages: true,
      );

      final replaceCalls = recordedMethodCalls
          .where((call) => call.method == 'replaceConversationMessages')
          .toList();
      expect(replaceCalls, isNotEmpty);
      expect(replaceCalls.last.arguments['conversationId'], conversationId);
      expect(replaceCalls.last.arguments['messages'], isEmpty);
    },
  );

  test(
    'prompt timing belongs to the final visible reply in a multi-message turn',
    () {
      const conversationId = 2210;
      const turnId = 'multi-reply';
      applyAcp(conversationId, 'turn/started', turnId: turnId);
      final runtime = coordinator.runtimeFor(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      )!;
      runtime.agentEntryStartTimes['prompt:$turnId'] =
          DateTime.now().millisecondsSinceEpoch - 65000;
      for (final entry in [('progress', '我先检查一下'), ('answer', '最终结果')]) {
        applyAcp(
          conversationId,
          'session/update',
          turnId: turnId,
          params: {
            'update': {
              'sessionUpdate': 'agent_message_chunk',
              'messageId': entry.$1,
              'content': {'type': 'text', 'text': entry.$2},
            },
          },
        );
      }
      coordinator.applyAcpPromptResponse(
        taskId: turnId,
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
        stopReason: 'end_turn',
        sessionId: null,
      );
      final replies = runtime.messages
          .where((message) => message.type == 1 && message.user == 2)
          .toList();
      expect(replies, hasLength(2));
      final answer = replies.singleWhere((message) => message.text == '最终结果');
      final progress = replies.singleWhere(
        (message) => message.text == '我先检查一下',
      );
      expect(answer.turnUsage?['durationMs'], greaterThanOrEqualTo(65000));
      expect(answer.turnUsage?['endedAt'], isA<int>());
      expect(progress.turnUsage?['endedAt'], isNull);
    },
  );

  for (final reason in ['end_turn', 'cancelled', 'error']) {
    test('prompt timing is finalized once and isolated: $reason', () {
      const conversationId = 2209;
      const turnId = 'timed-request';
      applyAcp(conversationId, 'turn/started', turnId: turnId);
      final runtime = coordinator.runtimeFor(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      )!;
      runtime.agentEntryStartTimes['prompt:$turnId'] =
          DateTime.now().millisecondsSinceEpoch - 65000;
      applyAcp(
        conversationId,
        'session/update',
        turnId: turnId,
        params: {
          'update': {
            'sessionUpdate': 'agent_message_chunk',
            'content': {'type': 'text', 'text': '已有结果'},
          },
        },
      );
      expect(runtime.messages.where((m) => m.user == 2).last.turnUsage, isNull);
      coordinator.applyAcpPromptResponse(
        taskId: turnId,
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
        stopReason: reason,
        sessionId: null,
      );
      final reply = runtime.messages
          .where((m) => m.type == 1 && m.user == 2)
          .last;
      expect(reply.turnUsage?['durationMs'], greaterThanOrEqualTo(65000));
      expect(reply.turnUsage?['endedAt'], isA<int>());
      final restored = ChatMessageModel.fromJson(reply.toJson());
      expect(restored.turnUsage, reply.turnUsage);
      applyAcp(conversationId, 'turn/started', turnId: 'next-request');
      coordinator.applyAcpPromptResponse(
        taskId: turnId,
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
        stopReason: reason,
        sessionId: null,
      );
      expect(
        runtime.messages.singleWhere((m) => m.id == reply.id).turnUsage,
        restored.turnUsage,
      );
      expect(runtime.currentDispatchTurnId, 'next-request');
    });
  }

  test(
    'accepts final ACP turn usage after the turn completion fence',
    () async {
      const conversationId = 2202;
      const turnId = 'turn-late-usage';
      const sessionId = 'session-late-usage';
      const messageId = 'message-late-usage';

      applyAcp(
        conversationId,
        'turn/started',
        turnId: turnId,
        sessionId: sessionId,
      );
      applyAcp(
        conversationId,
        'session/update',
        turnId: turnId,
        sessionId: sessionId,
        params: const <String, dynamic>{
          'update': <String, dynamic>{
            'sessionUpdate': 'agent_message_chunk',
            'messageId': messageId,
            'content': <String, dynamic>{'type': 'text', 'text': '最终回复'},
          },
        },
      );
      completePrompt(conversationId, turnId: turnId, sessionId: sessionId);
      applyAcp(
        conversationId,
        'session/update',
        turnId: turnId,
        sessionId: sessionId,
        params: const <String, dynamic>{
          'update': <String, dynamic>{
            'sessionUpdate': 'agent_message_chunk',
            'messageId': messageId,
            'content': <String, dynamic>{'type': 'text', 'text': ''},
            '_meta': <String, dynamic>{
              'cn.com.omnimind.agent': <String, dynamic>{
                'usage': <String, dynamic>{
                  'latestPromptTokens': 16076,
                  'promptTokenThreshold': 128000,
                  'turnUsage': <String, dynamic>{
                    'ctx': 16076,
                    'in': 16076,
                    'out': 1470,
                    'cache': 10770,
                  },
                },
              },
            },
          },
        },
      );

      final runtime = coordinator.runtimeFor(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      )!;
      final answer = runtime.messages.singleWhere(
        (message) => message.id == '$turnId-$messageId-agent-message',
      );
      expect(answer.turnUsage, <String, dynamic>{
        'endedAt': isA<int>(),
        'durationMs': isNonNegative,
        'ctx': 16076,
        'in': 16076,
        'out': 1470,
        'cache': 10770,
      });
      expect(runtime.isAiResponding, isFalse);

      await coordinator.flushPendingPersistence(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );
      final replaceCalls = recordedMethodCalls
          .where((call) => call.method == 'replaceConversationMessages')
          .toList();
      expect(replaceCalls, isNotEmpty);
      final persisted = Map<String, dynamic>.from(
        ((replaceCalls.last.arguments as Map)['messages'] as List)
            .cast<Map>()
            .singleWhere((message) => message['id'] == answer.id)
            .cast<String, dynamic>(),
      );
      expect(persisted['turnUsage'], answer.turnUsage);
    },
  );

  test(
    'ignores automatic compaction metadata instead of persisting a private card',
    () async {
      const conversationId = 2203;
      const turnId = 'turn-compaction-persist';
      const sessionId = 'session-compaction-persist';

      applyAcp(
        conversationId,
        'turn/started',
        turnId: turnId,
        sessionId: sessionId,
      );
      applyAcp(
        conversationId,
        'session/update',
        turnId: turnId,
        sessionId: sessionId,
        params: const <String, dynamic>{
          'update': <String, dynamic>{
            'sessionUpdate': 'agent_thought_chunk',
            'messageId': 'thought-compaction-persist',
            'content': <String, dynamic>{'type': 'text', 'text': ''},
            '_meta': <String, dynamic>{
              'cn.com.omnimind.agent': <String, dynamic>{
                'compaction': <String, dynamic>{
                  'status': 'completed',
                  'trigger': 'auto',
                  'latestPromptTokens': 126000,
                  'promptTokenThreshold': 128000,
                },
              },
            },
          },
        },
      );
      await Future<void>.delayed(Duration.zero);

      final runtime = coordinator.runtimeFor(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      )!;
      expect(runtime.messages, isEmpty);
      expect(runtime.isContextCompressing, isFalse);
      expect(
        recordedMethodCalls.where(
          (call) => call.method == 'upsertConversationUiCard',
        ),
        isEmpty,
      );
    },
  );

  test(
    'manual compaction marker does not manufacture an automatic or user turn',
    () {
      const conversationId = 2204;
      final runtime = coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );

      coordinator.beginContextCompaction(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );

      final marker = runtime.messages.single;
      expect(marker.user, 3);
      expect(marker.cardData?['type'], 'context_compaction_marker');
      expect(marker.cardData?['trigger'], 'manual');
      expect(runtime.messages.where((message) => message.user == 1), isEmpty);
    },
  );

  test('routes normal chat chunks through the ACP stream', () {
    const conversationId = 2301;
    const turnId = 'turn-normal';
    coordinator.beginAcpTurn(
      taskId: turnId,
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );
    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );
    applyAcp(
      conversationId,
      'session/update',
      turnId: turnId,
      mode: kChatRuntimeModeNormal,
      params: <String, dynamic>{
        'update': <String, dynamic>{
          'sessionUpdate': 'agent_message_chunk',
          'messageId': 'message-normal',
          'content': <String, dynamic>{'text': '普通聊天回复'},
        },
      },
    );
    completePrompt(
      conversationId,
      turnId: turnId,
      mode: kChatRuntimeModeNormal,
      params: <String, dynamic>{'status': 'completed'},
    );

    expect(runtime.messages.single.text, '普通聊天回复');
    expect(runtime.isAiResponding, isFalse);
  });

  test('clears transient runtime state when an ACP session ends', () {
    const conversationId = 2401;
    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );
    runtime.currentDispatchTurnId = 'turn-clear';
    runtime.lastAgentTurnId = 'turn-clear';
    runtime.activeRunId = 'run-clear';
    runtime.activeAcpTurnId = 'acp-turn-clear';
    runtime.activeAcpSessionId = 'session-clear';
    runtime.currentAiMessages['message-clear'] = 'stale text';
    runtime.agentReplayDeltaOffsets['message-clear'] = 4;
    runtime.pendingAcpAssistantPresentation['pending-clear'] = {
      'recovery': {'error': 'stale'},
    };
    runtime.isAiResponding = true;
    runtime.isDeepThinking = true;
    runtime.activeThinkingCardId = 'thought';
    runtime.activeToolCardId = 'tool';

    coordinator.clearConversationRuntimeSession(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );

    expect(runtime.currentDispatchTurnId, isNull);
    expect(runtime.lastAgentTurnId, isNull);
    expect(runtime.activeRunId, isNull);
    expect(runtime.currentAiMessages, isEmpty);
    expect(runtime.agentReplayDeltaOffsets, isEmpty);
    expect(runtime.pendingAcpAssistantPresentation, isEmpty);
    expect(runtime.isAiResponding, isFalse);
    expect(runtime.isDeepThinking, isFalse);
    expect(runtime.activeThinkingCardId, isNull);
    expect(runtime.activeToolCardId, isNull);
    expect(runtime.completedAgentTurnIds, contains('run-clear'));
    expect(runtime.completedAgentTurnIds, contains('acp-turn-clear'));
    expect(runtime.completedAcpTurnIds, contains('acp-turn-clear'));
  });

  test(
    'unregistering a local task also clears its distinct official ACP turn',
    () {
      const conversationId = 2404;
      const taskId = 'local-run-2404';
      const officialTurnId = 'acp-turn-2404';
      final runtime = coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );
      coordinator.beginAcpTurn(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );
      applyAcp(conversationId, 'turn/started', turnId: officialTurnId);

      expect(runtime.activeAcpTurnId, officialTurnId);
      expect(runtime.isAiResponding, isTrue);

      coordinator.unregisterTask(taskId);

      expect(runtime.activeAcpTurnId, isNull);
      expect(runtime.activeAcpSessionId, isNull);
      expect(runtime.isAiResponding, isFalse);
      expect(runtime.isContextCompressing, isFalse);
      expect(runtime.isInputAreaVisible, isTrue);
      expect(runtime.completedAcpTurnIds, contains(officialTurnId));
    },
  );

  test('late thinking cleanup for an old task cannot clear the new task', () {
    const conversationId = 2405;
    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );
    coordinator.beginAcpTurn(
      taskId: 'local-old-2405',
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );
    applyAcp(conversationId, 'turn/started', turnId: 'acp-old-2405');
    coordinator.registerTask(
      taskId: 'local-new-2405',
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );
    coordinator.beginAcpTurn(
      taskId: 'local-new-2405',
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );
    runtime.isDeepThinking = true;
    runtime.deepThinkingContent = 'new turn reasoning';
    runtime.activeThinkingCardId = 'local-new-2405-thinking';

    coordinator.clearTaskThinkingPresentation(
      taskId: 'local-old-2405',
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );

    expect(runtime.isDeepThinking, isTrue);
    expect(runtime.deepThinkingContent, 'new turn reasoning');
    expect(runtime.activeThinkingCardId, 'local-new-2405-thinking');
  });

  test('beginAcpTurn admits a pure chat runtime when it is created lazily', () {
    const conversationId = 2406;
    const taskId = 'pure-chat-lazy-runtime';
    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );

    coordinator.registerTask(
      taskId: taskId,
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );
    coordinator.beginAcpTurn(
      taskId: taskId,
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );

    expect(runtime.isAiResponding, isTrue);
    expect(runtime.currentDispatchTurnId, taskId);
    expect(runtime.activeRunId, taskId);
    expect(
      coordinator.isTaskActive(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      ),
      isTrue,
    );
  });

  test(
    'bindAcpSession reserves the official identity before prompt events',
    () {
      const conversationId = 24061;
      const taskId = 'session-reservation-task';
      final runtime = coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );
      coordinator.beginAcpTurn(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );

      expect(
        coordinator.bindAcpSession(
          taskId: taskId,
          conversationId: conversationId,
          mode: kChatRuntimeModeAgent,
          sessionId: 'session-reserved',
        ),
        isTrue,
      );
      expect(runtime.activeAcpSessionId, 'session-reserved');
      expect(runtime.knownAcpSessionIds, contains('session-reserved'));

      coordinator.unregisterTask(
        taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );
    },
  );

  test('official terminal event retires only the matching task binding', () {
    const conversationId = 24062;
    const taskId = 'terminal-binding-task';
    const sessionId = 'terminal-binding-session';
    const turnId = 'terminal-binding-turn';

    coordinator.beginAcpTurn(
      taskId: taskId,
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );
    expect(
      coordinator.bindAcpSession(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
        sessionId: sessionId,
      ),
      isTrue,
    );

    completePrompt(conversationId, turnId: turnId, sessionId: sessionId);

    final runtime = coordinator.runtimeFor(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    )!;
    expect(runtime.isAiResponding, isFalse);
    expect(
      coordinator.isTaskActive(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      ),
      isFalse,
    );
  });

  test('rebinds a task without leaving the old runtime active', () {
    const oldConversationId = 2407;
    const newConversationId = 2408;
    const taskId = 'handoff-task';
    final oldRuntime = coordinator.ensureRuntime(
      conversationId: oldConversationId,
      mode: kChatRuntimeModeAgent,
    );
    final newRuntime = coordinator.ensureRuntime(
      conversationId: newConversationId,
      mode: kChatRuntimeModeAgent,
    );
    coordinator.beginAcpTurn(
      taskId: taskId,
      conversationId: oldConversationId,
      mode: kChatRuntimeModeAgent,
    );

    coordinator.registerTask(
      taskId: taskId,
      conversationId: newConversationId,
      mode: kChatRuntimeModeAgent,
    );
    coordinator.beginAcpTurn(
      taskId: taskId,
      conversationId: newConversationId,
      mode: kChatRuntimeModeAgent,
    );

    expect(oldRuntime.hasInFlightTask, isFalse);
    expect(newRuntime.isAiResponding, isTrue);
    expect(
      coordinator.isTaskActive(
        taskId: taskId,
        conversationId: newConversationId,
        mode: kChatRuntimeModeAgent,
      ),
      isTrue,
    );
  });

  test('scoped late cleanup cannot clear a task after it changes runtime', () {
    const oldConversationId = 2409;
    const newConversationId = 2410;
    const taskId = 'reused-task-id';
    final oldRuntime = coordinator.ensureRuntime(
      conversationId: oldConversationId,
      mode: kChatRuntimeModeAgent,
    );
    final newRuntime = coordinator.ensureRuntime(
      conversationId: newConversationId,
      mode: kChatRuntimeModeAgent,
    );

    coordinator.beginAcpTurn(
      taskId: taskId,
      conversationId: oldConversationId,
      mode: kChatRuntimeModeAgent,
    );
    coordinator.registerTask(
      taskId: taskId,
      conversationId: newConversationId,
      mode: kChatRuntimeModeAgent,
    );
    coordinator.beginAcpTurn(
      taskId: taskId,
      conversationId: newConversationId,
      mode: kChatRuntimeModeAgent,
    );

    // This is the old runtime's delayed callback. Its identity must be
    // checked before the shared task binding is used for cleanup.
    coordinator.unregisterTask(
      taskId,
      conversationId: oldConversationId,
      mode: kChatRuntimeModeAgent,
    );

    expect(oldRuntime.hasInFlightTask, isFalse);
    expect(newRuntime.isAiResponding, isTrue);
    expect(newRuntime.activeRunId, taskId);
  });

  test('beginAcpTurn rebinds through the same task admission path', () {
    const oldConversationId = 2411;
    const newConversationId = 2412;
    const taskId = 'direct-begin-rebind';
    final oldRuntime = coordinator.ensureRuntime(
      conversationId: oldConversationId,
      mode: kChatRuntimeModeAgent,
    );
    final newRuntime = coordinator.ensureRuntime(
      conversationId: newConversationId,
      mode: kChatRuntimeModeAgent,
    );

    coordinator.beginAcpTurn(
      taskId: taskId,
      conversationId: oldConversationId,
      mode: kChatRuntimeModeAgent,
    );
    coordinator.beginAcpTurn(
      taskId: taskId,
      conversationId: newConversationId,
      mode: kChatRuntimeModeAgent,
    );

    expect(oldRuntime.hasInFlightTask, isFalse);
    expect(newRuntime.isAiResponding, isTrue);
    expect(
      coordinator.isTaskActive(
        taskId: taskId,
        conversationId: oldConversationId,
        mode: kChatRuntimeModeAgent,
      ),
      isFalse,
    );
    expect(
      coordinator.isTaskActive(
        taskId: taskId,
        conversationId: newConversationId,
        mode: kChatRuntimeModeAgent,
      ),
      isTrue,
    );
  });

  test('fences a sessionless late turn event after runtime reset', () {
    const conversationId = 2403;
    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );
    coordinator.beginAcpTurn(
      taskId: 'run-reset',
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );
    runtime.activeAcpSessionId = 'session-reset';
    runtime.activeAcpTurnId = 'acp-turn-reset';

    coordinator.clearConversationRuntimeSession(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );

    expect(runtime.acceptsAcpEvent(turnId: 'acp-turn-reset'), isFalse);
    // A new sessionless turn remains compatible with the legacy wire shape.
    expect(runtime.acceptsAcpEvent(turnId: 'acp-turn-new'), isTrue);
  });

  test(
    'fences late events from a reset session but allows a new turn to reuse it',
    () {
      const conversationId = 2402;
      final runtime = coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );

      expect(
        runtime.acceptsAcpEvent(
          sessionId: 'session-retired',
          turnId: 'turn-old',
          allowSessionAdmission: true,
        ),
        isTrue,
      );
      coordinator.clearConversationRuntimeSession(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );

      expect(
        runtime.acceptsAcpEvent(
          sessionId: 'session-retired',
          turnId: 'turn-old',
        ),
        isFalse,
      );

      coordinator.beginAcpTurn(
        taskId: 'run-new',
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );
      expect(
        runtime.acceptsAcpEvent(
          sessionId: 'session-retired',
          turnId: 'turn-new',
          allowSessionAdmission: true,
        ),
        isTrue,
      );
    },
  );

  test('projects active Xiaowan conversations for the drawer', () {
    const conversationId = 2010;
    const taskId = 'drawer-running-task';

    coordinator.beginAcpTurn(
      taskId: taskId,
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );

    expect(coordinator.activeAgentConversationIds, contains(conversationId));
    expect(coordinator.isAgentConversationActive(conversationId), isTrue);

    coordinator.unregisterTask(taskId);
    expect(
      coordinator.activeAgentConversationIds,
      isNot(contains(conversationId)),
    );
    expect(coordinator.isAgentConversationActive(conversationId), isFalse);
  });

  test('maps ACP tool updates to the tools island', () {
    const conversationId = 2501;
    applyAcp(
      conversationId,
      'item/started',
      turnId: 'turn-tool',
      params: <String, dynamic>{
        'item': <String, dynamic>{
          'id': 'tool-1',
          'type': 'commandExecution',
          'command': 'pwd',
          'status': 'running',
        },
      },
    );
    final runtime = coordinator.runtimeFor(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    )!;
    expect(runtime.chatIslandDisplayLayer, ChatIslandDisplayLayer.tools);
    expect(runtime.lastAgentToolType, 'terminal');
  });
}
