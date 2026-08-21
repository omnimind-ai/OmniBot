import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/models/chat_message_model.dart';
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
  }) {
    return <String, dynamic>{
      if (conversationId != null) 'conversationId': conversationId,
      if (sessionId != null) 'sessionId': sessionId,
      'agentId': agentId,
      'agentName': agentName,
      'threadId': turnId,
      'turnId': turnId,
      'message': <String, dynamic>{
        'method': method,
        'params': <String, dynamic>{
          'turnId': turnId,
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
  }) {
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
      ),
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

  test(
    'primes one visible thinking placeholder before the first ACP chunk',
    () {
      const conversationId = 2003;
      const taskId = 'local-task-before-acp';

      coordinator.primeAcpThinking(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );
      coordinator.primeAcpThinking(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );

      final runtime = coordinator.runtimeFor(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      )!;
      expect(runtime.isAiResponding, isTrue);
      expect(runtime.currentDispatchTurnId, taskId);
      expect(
        runtime.messages
            .where((message) => message.cardData?['type'] == 'deep_thinking')
            .length,
        1,
      );
      expect(runtime.messages.first.cardData?['isLoading'], isTrue);
    },
  );

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
    runtime.currentDispatchTurnId = null;
    applyAcp(
      conversationId,
      'session/update',
      sessionId: 'session-next',
      turnId: '',
      params: <String, dynamic>{'delta': 'new session'},
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
    applyAcp(
      conversationId,
      'turn/completed',
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

  test('keeps ACP turns isolated by conversation and finalizes them', () {
    const firstConversation = 2101;
    const secondConversation = 2102;
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
    applyAcp(
      firstConversation,
      'turn/completed',
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

  test('keeps DSH ACP reasoning steps separate', () {
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
      thinking.map((message) => message.cardData?['thinkingContent']).toSet(),
      <String>{'第一阶段：分析工作区。', '第二阶段：根据结果判断。'},
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

  test('routes normal chat chunks through the ACP stream', () {
    const conversationId = 2301;
    const turnId = 'turn-normal';
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
    applyAcp(
      conversationId,
      'turn/completed',
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
    expect(runtime.isAiResponding, isFalse);
    expect(runtime.isDeepThinking, isFalse);
    expect(runtime.activeThinkingCardId, isNull);
    expect(runtime.activeToolCardId, isNull);
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
