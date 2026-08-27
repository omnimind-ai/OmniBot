import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/ai_chat_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channelName = 'cn.com.omnimind.bot/AssistCoreEvent';
  const codec = StandardMethodCodec();
  const methodChannel = MethodChannel(channelName);
  final coordinator = ChatConversationRuntimeCoordinator.instance;
  final recordedMethodCalls = <MethodCall>[];
  var agentStreamSeq = 0;
  final agentThinkingRounds = <String, int>{};
  final agentTextRounds = <String, int>{};
  final agentPendingNextTextRound = <String, bool>{};
  final agentActiveThinkingEntryIds = <String, String>{};
  final agentActiveTextEntryIds = <String, String>{};
  final agentActiveToolEntryIds = <String, String>{};
  final agentToolSequences = <String, int>{};
  final agentEntryOrderSeqs = <String, int>{};
  var agentEntrySeq = 0;

  String agentThinkingEntryId(String taskId, int roundIndex) {
    return roundIndex <= 1
        ? '$taskId-thinking'
        : '$taskId-thinking-$roundIndex';
  }

  String agentTextEntryId(String taskId, int roundIndex) {
    return roundIndex <= 1 ? '$taskId-text' : '$taskId-text-$roundIndex';
  }

  int agentToolRoundIndex(String taskId) {
    return <int>[
      agentThinkingRounds[taskId] ?? 0,
      agentTextRounds[taskId] ?? 0,
      1,
    ].reduce((left, right) => left > right ? left : right);
  }

  Map<String, dynamic> streamMetaForEntry(
    String entryId,
    int roundIndex,
    String kind,
    String taskId,
  ) {
    final stableSeq = agentEntryOrderSeqs.putIfAbsent(entryId, () {
      agentEntrySeq += 1;
      return agentEntrySeq;
    });
    return <String, dynamic>{
      'seq': stableSeq,
      'roundIndex': roundIndex,
      'kind': kind,
      'parentTaskId': taskId,
    };
  }

  List<MethodCall> mapLegacyAgentEvent(String method, dynamic arguments) {
    final raw = Map<String, dynamic>.from(
      (arguments as Map?) ?? const <String, dynamic>{},
    );
    final taskId = (raw['taskId'] ?? '').toString();
    final now = DateTime.now().millisecondsSinceEpoch;

    MethodCall streamCall(Map<String, dynamic> payload) {
      agentStreamSeq += 1;
      return MethodCall('onAgentStreamEvent', <String, dynamic>{
        'taskId': taskId,
        'seq': agentStreamSeq,
        'createdAt': now,
        ...payload,
      });
    }

    switch (method) {
      case 'onAgentThinkingStart':
        final roundIndex = (agentThinkingRounds[taskId] ?? 0) + 1;
        agentThinkingRounds[taskId] = roundIndex;
        final entryId = agentThinkingEntryId(taskId, roundIndex);
        agentActiveThinkingEntryIds[taskId] = entryId;
        if (agentActiveTextEntryIds.containsKey(taskId)) {
          agentPendingNextTextRound[taskId] = true;
        }
        return <MethodCall>[
          streamCall(<String, dynamic>{
            'kind': 'thinking_started',
            'entryId': entryId,
            'roundIndex': roundIndex,
            'thinking': '',
            'stage': 1,
            'streamMeta': streamMetaForEntry(
              entryId,
              roundIndex,
              'thinking_started',
              taskId,
            ),
          }),
        ];
      case 'onAgentThinkingUpdate':
        final roundIndex = agentThinkingRounds[taskId] ?? 1;
        final entryId =
            agentActiveThinkingEntryIds[taskId] ??
            agentThinkingEntryId(taskId, roundIndex);
        agentActiveThinkingEntryIds[taskId] = entryId;
        return <MethodCall>[
          streamCall(<String, dynamic>{
            'kind': 'thinking_snapshot',
            'entryId': entryId,
            'roundIndex': roundIndex,
            'thinking': (raw['thinking'] ?? '').toString(),
            'stage': 1,
            'streamMeta': streamMetaForEntry(
              entryId,
              roundIndex,
              'thinking_snapshot',
              taskId,
            ),
          }),
        ];
      case 'onAgentChatMessage':
        var roundIndex = agentTextRounds[taskId] ?? 0;
        if (roundIndex == 0 || agentPendingNextTextRound[taskId] == true) {
          roundIndex += 1;
          agentTextRounds[taskId] = roundIndex;
          agentPendingNextTextRound.remove(taskId);
          agentActiveTextEntryIds[taskId] = agentTextEntryId(
            taskId,
            roundIndex,
          );
        }
        final entryId =
            agentActiveTextEntryIds[taskId] ??
            agentTextEntryId(taskId, roundIndex == 0 ? 1 : roundIndex);
        return <MethodCall>[
          streamCall(<String, dynamic>{
            'kind': 'text_snapshot',
            'entryId': entryId,
            'roundIndex': agentTextRounds[taskId] ?? 1,
            'text': (raw['message'] ?? '').toString(),
            'isFinal': raw['isFinal'] == true,
            'streamMeta': streamMetaForEntry(
              entryId,
              agentTextRounds[taskId] ?? 1,
              'text_snapshot',
              taskId,
            ),
            if (raw['prefillTokensPerSecond'] != null)
              'prefillTokensPerSecond': raw['prefillTokensPerSecond'],
            if (raw['decodeTokensPerSecond'] != null)
              'decodeTokensPerSecond': raw['decodeTokensPerSecond'],
          }),
        ];
      case 'onAgentToolCallStart':
        final nextSequence = (agentToolSequences[taskId] ?? 0) + 1;
        agentToolSequences[taskId] = nextSequence;
        final entryId = (raw['cardId'] ?? '').toString().trim().isNotEmpty
            ? (raw['cardId'] ?? '').toString().trim()
            : '$taskId-tool-$nextSequence';
        agentActiveToolEntryIds[taskId] = entryId;
        agentPendingNextTextRound[taskId] = true;
        return <MethodCall>[
          streamCall(<String, dynamic>{
            'kind': 'tool_started',
            'entryId': entryId,
            'roundIndex': agentToolRoundIndex(taskId),
            'streamMeta': streamMetaForEntry(
              entryId,
              agentToolRoundIndex(taskId),
              'tool_started',
              taskId,
            ),
            ...raw,
            'cardId': entryId,
          }),
        ];
      case 'onAgentToolCallProgress':
        final entryId = (raw['cardId'] ?? '').toString().trim().isNotEmpty
            ? (raw['cardId'] ?? '').toString().trim()
            : agentActiveToolEntryIds[taskId];
        return <MethodCall>[
          streamCall(<String, dynamic>{
            'kind': 'tool_progress',
            'entryId': entryId,
            'roundIndex': agentToolRoundIndex(taskId),
            if (entryId != null)
              'streamMeta': streamMetaForEntry(
                entryId,
                agentToolRoundIndex(taskId),
                'tool_progress',
                taskId,
              ),
            ...raw,
            if (entryId != null) 'cardId': entryId,
          }),
        ];
      case 'onAgentToolCallComplete':
        final entryId = (raw['cardId'] ?? '').toString().trim().isNotEmpty
            ? (raw['cardId'] ?? '').toString().trim()
            : agentActiveToolEntryIds.remove(taskId);
        agentPendingNextTextRound[taskId] = true;
        return <MethodCall>[
          streamCall(<String, dynamic>{
            'kind': 'tool_completed',
            'entryId': entryId,
            'roundIndex': agentToolRoundIndex(taskId),
            if (entryId != null)
              'streamMeta': streamMetaForEntry(
                entryId,
                agentToolRoundIndex(taskId),
                'tool_completed',
                taskId,
              ),
            ...raw,
            if (entryId != null) 'cardId': entryId,
          }),
        ];
      case 'onAgentError':
        final existingEntryId = agentActiveTextEntryIds[taskId];
        final errorText = (raw['error'] ?? '').toString();
        if (existingEntryId == null) {
          final roundIndex = (agentTextRounds[taskId] ?? 0) + 1;
          agentTextRounds[taskId] = roundIndex;
          final entryId = agentTextEntryId(taskId, roundIndex);
          agentActiveTextEntryIds[taskId] = entryId;
          return <MethodCall>[
            streamCall(<String, dynamic>{
              'kind': 'text_snapshot',
              'entryId': entryId,
              'roundIndex': roundIndex,
              'text': errorText,
              'isFinal': true,
              'streamMeta': streamMetaForEntry(
                entryId,
                roundIndex,
                'text_snapshot',
                taskId,
              ),
            }),
            streamCall(<String, dynamic>{
              'kind': 'error',
              'entryId': entryId,
              'roundIndex': roundIndex,
              'error': errorText,
              'persistAsError': true,
              'streamMeta': streamMetaForEntry(
                entryId,
                roundIndex,
                'error',
                taskId,
              ),
            }),
          ];
        }
        return <MethodCall>[
          streamCall(<String, dynamic>{
            'kind': 'error',
            'entryId': existingEntryId,
            'roundIndex': agentTextRounds[taskId] ?? 1,
            'error': errorText,
            'persistAsError': false,
            'streamMeta': streamMetaForEntry(
              existingEntryId,
              agentTextRounds[taskId] ?? 1,
              'error',
              taskId,
            ),
          }),
        ];
      default:
        return <MethodCall>[MethodCall(method, arguments)];
    }
  }

  Future<void> emitPlatformEvent(String method, [dynamic arguments]) async {
    final calls =
        method.startsWith('onAgent') &&
            method != 'onAgentPromptTokenUsageChanged' &&
            method != 'onAgentContextCompactionStateChanged' &&
            method != 'onAgentStreamEvent'
        ? mapLegacyAgentEvent(method, arguments)
        : <MethodCall>[MethodCall(method, arguments)];
    for (final call in calls) {
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .handlePlatformMessage(
            channelName,
            codec.encodeMethodCall(call),
            (ByteData? _) {},
          );
      await Future<void>.delayed(Duration.zero);
    }
  }

  List<String> visibleMessageIds(ChatConversationRuntimeState runtime) {
    return runtime.messages
        .map((message) => message.id)
        .toList()
        .reversed
        .toList();
  }

  setUp(() async {
    coordinator.resetForTest();
    recordedMethodCalls.clear();
    agentStreamSeq = 0;
    agentThinkingRounds.clear();
    agentTextRounds.clear();
    agentPendingNextTextRound.clear();
    agentActiveThinkingEntryIds.clear();
    agentActiveTextEntryIds.clear();
    agentActiveToolEntryIds.clear();
    agentToolSequences.clear();
    agentEntryOrderSeqs.clear();
    agentEntrySeq = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, (call) async {
          recordedMethodCalls.add(call);
          return 'SUCCESS';
        });
    coordinator.ensureInitialized();
  });

  tearDown(() async {
    coordinator.resetForTest();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodChannel, null);
  });

  test('routes agent chat updates to the bound conversation only', () async {
    const conversationA = 1001;
    const conversationB = 1002;
    const taskId = 'agent-task-a';

    coordinator.ensureRuntime(
      conversationId: conversationA,
      mode: kChatRuntimeModeNormal,
    );
    coordinator.ensureRuntime(
      conversationId: conversationB,
      mode: kChatRuntimeModeNormal,
    );
    coordinator.registerTask(
      taskId: taskId,
      conversationId: conversationA,
      mode: kChatRuntimeModeNormal,
    );

    final runtimeA = coordinator.runtimeFor(
      conversationId: conversationA,
      mode: kChatRuntimeModeNormal,
    )!;
    runtimeA.currentDispatchTaskId = taskId;

    await emitPlatformEvent('onAgentChatMessage', <String, dynamic>{
      'taskId': taskId,
      'message': 'hello from agent',
      'isFinal': true,
      'prefillTokensPerSecond': 123.4,
      'decodeTokensPerSecond': 56.7,
    });

    final runtimeB = coordinator.runtimeFor(
      conversationId: conversationB,
      mode: kChatRuntimeModeNormal,
    )!;

    expect(runtimeA.messages, hasLength(1));
    expect(runtimeA.messages.first.id, '$taskId-text');
    expect(runtimeA.messages.first.text, 'hello from agent');
    expect(runtimeA.messages.first.content?['prefillTokensPerSecond'], 123.4);
    expect(runtimeA.messages.first.content?['decodeTokensPerSecond'], 56.7);
    expect(runtimeB.messages, isEmpty);
  });

  test('dedupes native agent user entry matching local user bubble', () {
    const conversationId = 1003;
    const createdAtMs = 1234567890000;

    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
      initialMessages: <ChatMessageModel>[
        ChatMessageModel(
          id: '$createdAtMs-ai-user',
          type: 1,
          user: 1,
          content: const <String, dynamic>{
            'id': '$createdAtMs-ai-user',
            'text': '确认',
          },
          createAt: DateTime.fromMillisecondsSinceEpoch(createdAtMs + 120),
        ),
        ChatMessageModel(
          id: '$createdAtMs-user',
          type: 1,
          user: 1,
          content: const <String, dynamic>{
            'id': '$createdAtMs-user',
            'text': '确认',
          },
          createAt: DateTime.fromMillisecondsSinceEpoch(createdAtMs),
        ),
      ],
    );

    expect(runtime.messages, hasLength(1));
    expect(runtime.messages.single.id, '$createdAtMs-user');
    expect(runtime.messages.single.text, '确认');
  });

  test(
    'adopts scheduled subagent stream events for an opened conversation runtime',
    () async {
      const conversationId = 1010;
      const taskId = 'subagent_schedule_123_schedule-news';

      final runtime = coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
        conversation: ConversationModel(
          id: conversationId,
          mode: ConversationMode.subagent,
          title: '新闻整理',
          status: 0,
          messageCount: 0,
          createdAt: 1000,
          updatedAt: 1000,
        ),
      );

      await emitPlatformEvent('onAgentStreamEvent', <String, dynamic>{
        'taskId': taskId,
        'conversationId': conversationId,
        'conversationMode': ConversationMode.subagent.storageValue,
        'seq': 1,
        'kind': 'text_snapshot',
        'entryId': '$taskId-text',
        'roundIndex': 1,
        'text': '定时任务正在整理新闻。',
      });

      expect(runtime.messages, hasLength(1));
      expect(runtime.messages.single.id, '$taskId-text');
      expect(runtime.messages.single.text, '定时任务正在整理新闻。');
      expect(runtime.isAiResponding, isTrue);

      await emitPlatformEvent('onAgentStreamEvent', <String, dynamic>{
        'taskId': taskId,
        'conversationId': conversationId,
        'conversationMode': ConversationMode.subagent.storageValue,
        'seq': 2,
        'kind': 'completed',
        'success': true,
      });

      expect(runtime.isAiResponding, isFalse);
    },
  );

  test(
    'finalizes latest streamed agent text after thinking and tool boundaries',
    () async {
      const conversationId = 1011;
      const taskId = 'agent-task-interleaved-tool-final';
      final runtime = coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );
      coordinator.registerTask(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );

      Future<void> emitAgentStream(Map<String, dynamic> payload) {
        return emitPlatformEvent('onAgentStreamEvent', <String, dynamic>{
          'taskId': taskId,
          'conversationId': conversationId,
          'conversationMode': ConversationMode.normal.storageValue,
          ...payload,
        });
      }

      await emitAgentStream(<String, dynamic>{
        'seq': 1,
        'kind': 'thinking_started',
        'entryId': '$taskId-thinking',
        'roundIndex': 1,
        'thinking': '',
        'stage': 1,
        'streamMeta': streamMetaForEntry(
          '$taskId-thinking',
          1,
          'thinking_started',
          taskId,
        ),
      });
      await emitAgentStream(<String, dynamic>{
        'seq': 2,
        'kind': 'thinking_snapshot',
        'entryId': '$taskId-thinking',
        'roundIndex': 1,
        'thinking': '第一段思考',
        'stage': 1,
        'streamMeta': streamMetaForEntry(
          '$taskId-thinking',
          1,
          'thinking_snapshot',
          taskId,
        ),
      });
      await emitAgentStream(<String, dynamic>{
        'seq': 3,
        'kind': 'text_snapshot',
        'entryId': '$taskId-text',
        'roundIndex': 1,
        'text': '第一段正文',
        'isFinal': false,
        'streamMeta': streamMetaForEntry(
          '$taskId-text',
          1,
          'text_snapshot',
          taskId,
        ),
      });
      await emitAgentStream(<String, dynamic>{
        'seq': 4,
        'kind': 'thinking_started',
        'entryId': '$taskId-thinking-2',
        'roundIndex': 2,
        'thinking': '',
        'stage': 1,
        'streamMeta': streamMetaForEntry(
          '$taskId-thinking-2',
          2,
          'thinking_started',
          taskId,
        ),
      });
      await emitAgentStream(<String, dynamic>{
        'seq': 5,
        'kind': 'tool_started',
        'entryId': '$taskId-tool-1',
        'roundIndex': 2,
        'cardId': '$taskId-tool-1',
        'toolName': 'search',
        'toolType': 'builtin',
        'summary': '正在搜索',
        'streamMeta': streamMetaForEntry(
          '$taskId-tool-1',
          2,
          'tool_started',
          taskId,
        ),
      });
      await emitAgentStream(<String, dynamic>{
        'seq': 6,
        'kind': 'tool_completed',
        'entryId': '$taskId-tool-1',
        'roundIndex': 2,
        'cardId': '$taskId-tool-1',
        'toolName': 'search',
        'toolType': 'builtin',
        'summary': '搜索完成',
        'success': true,
        'streamMeta': streamMetaForEntry(
          '$taskId-tool-1',
          2,
          'tool_completed',
          taskId,
        ),
      });
      await emitAgentStream(<String, dynamic>{
        'seq': 7,
        'kind': 'text_snapshot',
        'entryId': '$taskId-text-2',
        'roundIndex': 2,
        'text': '第二段正文',
        'isFinal': false,
        'streamMeta': streamMetaForEntry(
          '$taskId-text-2',
          2,
          'text_snapshot',
          taskId,
        ),
      });
      await emitAgentStream(<String, dynamic>{
        'seq': 8,
        'kind': 'completed',
        'entryId': '$taskId-text-2',
        'roundIndex': 2,
        'success': true,
      });

      final firstText = runtime.messages.singleWhere(
        (message) => message.id == '$taskId-text',
      );
      final finalText = runtime.messages.singleWhere(
        (message) => message.id == '$taskId-text-2',
      );
      expect(firstText.streamMeta?['isFinal'], isFalse);
      expect(finalText.streamMeta?['isFinal'], isTrue);

      final group = buildAgentRunTimelineEntries(
        runtime.messages,
      ).singleWhere((entry) => entry.group?.taskId == taskId).group!;
      // Both prose messages stay in the conversation, in the order the agent
      // wrote them; only the thinking and tool cards fold.
      expect(
        group.visibleMessagesOldestFirst.map((message) => message.id),
        <String>['$taskId-text', '$taskId-text-2'],
      );
      expect(
        group.processMessagesOldestFirst.map((message) => message.id),
        containsAll(<String>[
          '$taskId-thinking',
          '$taskId-thinking-2',
          '$taskId-tool-1',
        ]),
      );
    },
  );

  test('persists codex runtime messages back to native history', () async {
    const conversationId = 2001;
    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );
    runtime.messages.insert(0, ChatMessageModel.userMessage('第一句标题应该保留'));

    await coordinator.applyAgentEvent(
      conversationId: conversationId,
      event: {
        'message': {
          'method': 'item/agentMessage/delta',
          'params': {'turnId': 'turn-1', 'delta': 'Codex reply'},
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
    expect(args['mode'], 'codex');

    final messages = (args['messages'] as List)
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item.cast<String, dynamic>()))
        .toList();
    expect(
      messages.any(
        (message) =>
            message['user'] == 1 &&
            ((message['content'] as Map?)?['text'] == '第一句标题应该保留'),
      ),
      isTrue,
    );
    expect(
      messages.any(
        (message) =>
            message['user'] == 2 &&
            ((message['content'] as Map?)?['text'] == 'Codex reply'),
      ),
      isTrue,
    );
  });

  test('records the active ACP Agent on text and tool messages', () {
    const conversationId = 2002;
    coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );

    coordinator.applyAgentEvent(
      conversationId: conversationId,
      event: {
        'agentId': 'claude-code-acp',
        'agentName': 'Claude Code',
        'message': {
          'method': 'item/agentMessage/delta',
          'params': {'turnId': 'turn-claude', 'delta': 'Claude reply'},
        },
      },
    );
    coordinator.applyAgentEvent(
      conversationId: conversationId,
      event: {
        'agentId': 'claude-code-acp',
        'agentName': 'Claude Code',
        'message': {
          'method': 'item/started',
          'params': {
            'turnId': 'turn-claude',
            'item': {
              'id': 'tool-1',
              'type': 'commandExecution',
              'command': 'pwd',
              'status': 'running',
            },
          },
        },
      },
    );

    final runtime = coordinator.runtimeFor(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    )!;
    final assistant = runtime.messages.singleWhere(
      (message) => message.user == 2,
    );
    final tool = runtime.messages.singleWhere(
      (message) => message.cardData?['type'] == 'agent_tool_summary',
    );
    expect(assistant.agentId, 'claude-code-acp');
    expect(assistant.agentName, 'Claude Code');
    expect(tool.agentId, 'claude-code-acp');
    expect(tool.agentName, 'Claude Code');
  });

  test(
    'keeps DSH reasoning steps separate and finalizes each at the next output boundary',
    () {
      const conversationId = 2003;
      const turnId = 'dsh-turn-multi-thinking';

      void apply(String method, Map<String, dynamic> params) {
        coordinator.applyAgentEvent(
          conversationId: conversationId,
          event: <String, dynamic>{
            'agentId': 'deepseek-harness',
            'agentName': 'DeepSeek Harness',
            'message': <String, dynamic>{
              'method': method,
              'params': <String, dynamic>{'turnId': turnId, ...params},
            },
          },
        );
      }

      apply('item/reasoning/delta', <String, dynamic>{
        'itemId': 'dsh-step-1-thought',
        'delta': '第一阶段：先分析工作区。',
      });
      apply('item/started', <String, dynamic>{
        'item': <String, dynamic>{
          'id': 'dsh-tool-1',
          'type': 'commandExecution',
          'command': 'pwd',
          'status': 'running',
        },
      });
      apply('item/reasoning/delta', <String, dynamic>{
        'itemId': 'dsh-step-2-thought',
        'delta': '第二阶段：根据工具结果继续判断。',
      });
      apply('item/agentMessage/delta', <String, dynamic>{
        'itemId': 'dsh-step-2-message',
        'delta': '这是最终回答。',
      });

      final runtime = coordinator.runtimeFor(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      )!;
      final thinkingCards = runtime.messages
          .where((message) => message.cardData?['type'] == 'deep_thinking')
          .toList(growable: false);

      expect(thinkingCards, hasLength(2));
      expect(
        thinkingCards
            .map((message) => message.cardData?['thinkingContent'])
            .toSet(),
        <String>{'第一阶段：先分析工作区。', '第二阶段：根据工具结果继续判断。'},
      );
      expect(
        thinkingCards.every(
          (message) =>
              message.cardData?['isLoading'] == false &&
              message.cardData?['stage'] == 4,
        ),
        isTrue,
      );
      expect(runtime.activeThinkingCardId, isNull);
    },
  );

  test('replaces divergent agent snapshots instead of concatenating', () async {
    const conversationId = 1003;
    const taskId = 'agent-task-divergent-snapshot';

    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );
    runtime.currentDispatchTaskId = taskId;
    coordinator.registerTask(
      taskId: taskId,
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );

    await emitPlatformEvent('onAgentChatMessage', <String, dynamic>{
      'taskId': taskId,
      'message': '第一版：正在分析问题。',
      'isFinal': false,
    });
    await emitPlatformEvent('onAgentChatMessage', <String, dynamic>{
      'taskId': taskId,
      'message': '最终版：已经定位到根因并准备修复。',
      'isFinal': false,
    });

    expect(runtime.messages, hasLength(1));
    expect(runtime.messages.single.text, '最终版：已经定位到根因并准备修复。');
  });

  test('keeps visible agent text when a later agent error arrives', () async {
    const conversationId = 1004;
    const taskId = 'agent-task-error-after-content';

    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );
    runtime.currentDispatchTaskId = taskId;
    coordinator.registerTask(
      taskId: taskId,
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );

    await emitPlatformEvent('onAgentChatMessage', <String, dynamic>{
      'taskId': taskId,
      'message': '这是一段已经成功生成的正文。',
      'isFinal': false,
    });
    await emitPlatformEvent('onAgentError', <String, dynamic>{
      'taskId': taskId,
      'error':
          'Agent execution failed: length=140; regionStart=0; bytePairLength=138',
    });

    expect(runtime.messages, hasLength(1));
    expect(runtime.messages.single.text, '这是一段已经成功生成的正文。');
    expect(runtime.messages.single.isError, isFalse);
  });

  test(
    'binds turn usage and continue metadata to the failed assistant turn',
    () async {
      const conversationId = 10041;
      const taskId = 'agent-task-continueable-error';
      const entryId = '$taskId-text';

      final runtime = coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );
      coordinator.registerTask(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );

      await emitPlatformEvent('onAgentStreamEvent', <String, dynamic>{
        'taskId': taskId,
        'conversationId': conversationId,
        'conversationMode': ConversationMode.normal.storageValue,
        'seq': 1,
        'kind': 'text_snapshot',
        'entryId': entryId,
        'roundIndex': 1,
        'isFinal': true,
        'text': 'partial reply',
        'turnUsage': {'ctx': 20000, 'in': 10000, 'out': 87, 'cache': 10000},
      });

      await emitPlatformEvent('onAgentStreamEvent', <String, dynamic>{
        'taskId': taskId,
        'conversationId': conversationId,
        'conversationMode': ConversationMode.normal.storageValue,
        'seq': 2,
        'kind': 'error',
        'entryId': entryId,
        'roundIndex': 1,
        'error': 'network interrupted',
        'errorText': 'network interrupted',
        'continueable': true,
        'continueResumeMode': 'approximate',
        'retryable': true,
        'persistAsError': false,
        'turnUsage': {'ctx': 20000, 'in': 10000, 'out': 87, 'cache': 10000},
      });

      expect(runtime.messages, hasLength(1));
      final message = runtime.messages.single;
      expect(message.id, entryId);
      expect(message.turnUsage?['ctx'], 20000);
      expect(message.turnUsage?['in'], 10000);
      expect(message.content?['agentContinueable'], isTrue);
      expect(message.content?['agentContinueResumeMode'], 'approximate');
      expect(message.content?['agentRetryable'], isTrue);
    },
  );

  test(
    'shows an error bubble when agent fails before any visible text',
    () async {
      const conversationId = 1005;
      const taskId = 'agent-task-empty-error';

      final runtime = coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );
      runtime.currentDispatchTaskId = taskId;
      coordinator.registerTask(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );

      await emitPlatformEvent('onAgentError', <String, dynamic>{
        'taskId': taskId,
        'error':
            'Agent execution failed: length=140; regionStart=0; bytePairLength=138',
      });

      expect(runtime.messages, hasLength(1));
      expect(
        runtime.messages.single.text,
        contains('length=140; regionStart=0; bytePairLength=138'),
      );
      expect(runtime.messages.single.isError, isTrue);
    },
  );

  test('routes chat task chunks to the bound conversation only', () async {
    const conversationA = 2001;
    const conversationB = 2002;
    const taskId = 'chat-task-a';

    coordinator.ensureRuntime(
      conversationId: conversationA,
      mode: kChatRuntimeModeOpenClaw,
    );
    coordinator.ensureRuntime(
      conversationId: conversationB,
      mode: kChatRuntimeModeOpenClaw,
    );
    coordinator.registerTask(
      taskId: taskId,
      conversationId: conversationA,
      mode: kChatRuntimeModeOpenClaw,
    );

    await emitPlatformEvent('onChatMessage', <String, dynamic>{
      'taskID': taskId,
      'content': '{"text":"hello from openclaw"}',
      'type': null,
    });

    final runtimeA = coordinator.runtimeFor(
      conversationId: conversationA,
      mode: kChatRuntimeModeOpenClaw,
    )!;
    final runtimeB = coordinator.runtimeFor(
      conversationId: conversationB,
      mode: kChatRuntimeModeOpenClaw,
    )!;

    expect(runtimeA.messages, hasLength(1));
    expect(runtimeA.messages.first.id, taskId);
    expect(runtimeA.messages.first.text, 'hello from openclaw');
    expect(runtimeB.messages, isEmpty);
  });

  test('parses raw OpenAI chat chunks into visible assistant text', () async {
    const conversationId = 2201;
    const taskId = 'chat-task-openai';

    coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );
    coordinator.registerTask(
      taskId: taskId,
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );

    await emitPlatformEvent('onChatMessage', <String, dynamic>{
      'taskID': taskId,
      'content': '{"choices":[{"delta":{"content":"hello from pure chat"}}]}',
      'type': null,
    });

    final runtime = coordinator.runtimeFor(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    )!;

    expect(runtime.messages, hasLength(1));
    expect(runtime.messages.first.id, taskId);
    expect(runtime.messages.first.text, 'hello from pure chat');
  });

  test('parses usage performance metrics from pure-chat usage chunks', () async {
    const conversationId = 2211;
    const taskId = 'chat-task-usage-performance';

    coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );
    coordinator.registerTask(
      taskId: taskId,
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );

    await emitPlatformEvent('onChatMessage', <String, dynamic>{
      'taskID': taskId,
      'content': '{"choices":[{"delta":{"content":"hello from pure chat"}}]}',
      'type': null,
    });
    await emitPlatformEvent('onChatMessage', <String, dynamic>{
      'taskID': taskId,
      'content':
          '{"choices":[],"usage":{"prompt_tokens":15,"completion_tokens":100,"total_tokens":115,"performance":{"prefill_tokens_per_second":36.6,"decode_tokens_per_second":12.4}}}',
      'type': null,
    });

    final runtime = coordinator.runtimeFor(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    )!;

    expect(runtime.messages, hasLength(1));
    expect(runtime.messages.first.text, 'hello from pure chat');
    expect(runtime.messages.first.content?['prefillTokensPerSecond'], 36.6);
    expect(runtime.messages.first.content?['decodeTokensPerSecond'], 12.4);
  });

  test('primes pure-chat thinking card immediately before streaming', () {
    const conversationId = 2204;
    const taskId = 'chat-task-thinking-prime';

    coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );
    coordinator.registerTask(
      taskId: taskId,
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );

    coordinator.primePureChatThinking(
      taskId: taskId,
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );

    final runtime = coordinator.runtimeFor(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    )!;

    expect(runtime.messages, hasLength(1));
    expect(runtime.messages.first.cardData?['type'], 'deep_thinking');
    expect(runtime.messages.first.cardData?['isLoading'], isTrue);
    expect(runtime.messages.first.cardData?['thinkingContent'], '');
  });

  test(
    'removes primed thinking card when no reasoning chunk arrives',
    () async {
      const conversationId = 2206;
      const taskId = 'chat-task-thinking-empty';

      coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );
      coordinator.registerTask(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );

      coordinator.primePureChatThinking(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );

      await emitPlatformEvent('onChatMessage', <String, dynamic>{
        'taskID': taskId,
        'content': '{"choices":[{"delta":{"content":"没有思考流也要正常收尾。"}}]}',
        'type': null,
      });
      await emitPlatformEvent('onChatMessageEnd', <String, dynamic>{
        'taskID': taskId,
      });

      final runtime = coordinator.runtimeFor(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      )!;

      expect(runtime.messages, hasLength(1));
      expect(runtime.messages.single.id, taskId);
      expect(runtime.messages.single.text, '没有思考流也要正常收尾。');
    },
  );

  test('renders pure-chat reasoning as a deep thinking card', () async {
    const conversationId = 2203;
    const taskId = 'chat-task-thinking';

    coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );
    coordinator.registerTask(
      taskId: taskId,
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );

    await emitPlatformEvent('onChatMessage', <String, dynamic>{
      'taskID': taskId,
      'content': '{"choices":[{"delta":{"reasoning_content":"先分析一下问题。"}}]}',
      'type': null,
    });
    await emitPlatformEvent('onChatMessage', <String, dynamic>{
      'taskID': taskId,
      'content': '{"choices":[{"delta":{"content":"这是最终回答。"}}]}',
      'type': null,
    });
    await emitPlatformEvent('onChatMessageEnd', <String, dynamic>{
      'taskID': taskId,
    });

    final runtime = coordinator.runtimeFor(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    )!;

    expect(runtime.messages, hasLength(2));
    expect(runtime.messages.first.id, taskId);
    expect(runtime.messages.first.text, '这是最终回答。');

    final thinkingCard = runtime.messages.last;
    expect(thinkingCard.cardData?['type'], 'deep_thinking');
    expect(thinkingCard.cardData?['thinkingContent'], '先分析一下问题。');
    expect(thinkingCard.cardData?['isLoading'], isFalse);
    expect(thinkingCard.cardData?['stage'], 4);
  });

  test(
    'keeps the full pure-chat reasoning prefix across delta chunks',
    () async {
      const conversationId = 2205;
      const taskId = 'chat-task-thinking-delta';

      coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );
      coordinator.registerTask(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );

      coordinator.primePureChatThinking(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );

      await emitPlatformEvent('onChatMessage', <String, dynamic>{
        'taskID': taskId,
        'content': '{"choices":[{"delta":{"reasoning_content":"先"}}]}',
        'type': null,
      });
      await emitPlatformEvent('onChatMessage', <String, dynamic>{
        'taskID': taskId,
        'content': '{"choices":[{"delta":{"reasoning_content":"分析"}}]}',
        'type': null,
      });
      await emitPlatformEvent('onChatMessage', <String, dynamic>{
        'taskID': taskId,
        'content': '{"choices":[{"delta":{"reasoning_content":"一下问题。"}}]}',
        'type': null,
      });

      final runtime = coordinator.runtimeFor(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      )!;
      final thinkingCard = runtime.messages.single;

      expect(thinkingCard.cardData?['type'], 'deep_thinking');
      expect(thinkingCard.cardData?['thinkingContent'], '先分析一下问题。');
    },
  );

  test(
    'preserves whitespace and punctuation in pure-chat reasoning delta chunks',
    () async {
      const conversationId = 2207;
      const taskId = 'chat-task-thinking-whitespace';

      coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );
      coordinator.registerTask(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );

      coordinator.primePureChatThinking(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );

      await emitPlatformEvent('onChatMessage', <String, dynamic>{
        'taskID': taskId,
        'content': '{"choices":[{"delta":{"reasoning_content":"先想"}}]}',
        'type': null,
      });
      await emitPlatformEvent('onChatMessage', <String, dynamic>{
        'taskID': taskId,
        'content': '{"choices":[{"delta":{"reasoning_content":"："}}]}',
        'type': null,
      });
      await emitPlatformEvent('onChatMessage', <String, dynamic>{
        'taskID': taskId,
        'content': '{"choices":[{"delta":{"reasoning_content":"\\n"}}]}',
        'type': null,
      });
      await emitPlatformEvent('onChatMessage', <String, dynamic>{
        'taskID': taskId,
        'content': '{"choices":[{"delta":{"reasoning_content":"  再做"}}]}',
        'type': null,
      });
      await emitPlatformEvent('onChatMessage', <String, dynamic>{
        'taskID': taskId,
        'content': '{"choices":[{"delta":{"reasoning_content":"。"}}]}',
        'type': null,
      });

      final runtime = coordinator.runtimeFor(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      )!;
      final thinkingCard = runtime.messages.single;

      expect(thinkingCard.cardData?['type'], 'deep_thinking');
      expect(thinkingCard.cardData?['thinkingContent'], '先想：\n  再做。');
    },
  );

  test(
    'preserves whitespace and punctuation in pure-chat content delta chunks',
    () async {
      const conversationId = 2208;
      const taskId = 'chat-task-content-whitespace';

      coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );
      coordinator.registerTask(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );

      await emitPlatformEvent('onChatMessage', <String, dynamic>{
        'taskID': taskId,
        'content': '{"choices":[{"delta":{"content":"Hello"}}]}',
        'type': null,
      });
      await emitPlatformEvent('onChatMessage', <String, dynamic>{
        'taskID': taskId,
        'content': '{"choices":[{"delta":{"content":","}}]}',
        'type': null,
      });
      await emitPlatformEvent('onChatMessage', <String, dynamic>{
        'taskID': taskId,
        'content': '{"choices":[{"delta":{"content":" "}}]}',
        'type': null,
      });
      await emitPlatformEvent('onChatMessage', <String, dynamic>{
        'taskID': taskId,
        'content': '{"choices":[{"delta":{"content":"world"}}]}',
        'type': null,
      });
      await emitPlatformEvent('onChatMessage', <String, dynamic>{
        'taskID': taskId,
        'content': '{"choices":[{"delta":{"content":"!"}}]}',
        'type': null,
      });

      final runtime = coordinator.runtimeFor(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      )!;

      expect(runtime.messages, hasLength(1));
      expect(runtime.messages.single.text, 'Hello, world!');
    },
  );

  test('preserves repeated punctuation in pure-chat content chunks', () async {
    const conversationId = 2209;
    const taskId = 'chat-task-content-repeated-punctuation';

    coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );
    coordinator.registerTask(
      taskId: taskId,
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );

    await emitPlatformEvent('onChatMessage', <String, dynamic>{
      'taskID': taskId,
      'content': '{"choices":[{"delta":{"content":"你好"}}]}',
      'type': null,
    });
    await emitPlatformEvent('onChatMessage', <String, dynamic>{
      'taskID': taskId,
      'content': '{"choices":[{"delta":{"content":"，"}}]}',
      'type': null,
    });
    await emitPlatformEvent('onChatMessage', <String, dynamic>{
      'taskID': taskId,
      'content': '{"choices":[{"delta":{"content":"世界"}}]}',
      'type': null,
    });
    await emitPlatformEvent('onChatMessage', <String, dynamic>{
      'taskID': taskId,
      'content': '{"choices":[{"delta":{"content":"。"}}]}',
      'type': null,
    });
    await emitPlatformEvent('onChatMessage', <String, dynamic>{
      'taskID': taskId,
      'content': '{"choices":[{"delta":{"content":"再见"}}]}',
      'type': null,
    });
    await emitPlatformEvent('onChatMessage', <String, dynamic>{
      'taskID': taskId,
      'content': '{"choices":[{"delta":{"content":"。"}}]}',
      'type': null,
    });

    final runtime = coordinator.runtimeFor(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    )!;

    expect(runtime.messages, hasLength(1));
    expect(runtime.messages.single.text, '你好，世界。再见。');
  });

  test(
    'accepts cumulative pure-chat content snapshots without duplication',
    () async {
      const conversationId = 2210;
      const taskId = 'chat-task-content-cumulative';

      coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );
      coordinator.registerTask(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );

      await emitPlatformEvent('onChatMessage', <String, dynamic>{
        'taskID': taskId,
        'content': '{"choices":[{"delta":{"content":"Hello"}}]}',
        'type': null,
      });
      await emitPlatformEvent('onChatMessage', <String, dynamic>{
        'taskID': taskId,
        'content': '{"choices":[{"delta":{"content":"Hello,"}}]}',
        'type': null,
      });
      await emitPlatformEvent('onChatMessage', <String, dynamic>{
        'taskID': taskId,
        'content': '{"choices":[{"delta":{"content":"Hello, world!"}}]}',
        'type': null,
      });

      final runtime = coordinator.runtimeFor(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      )!;

      expect(runtime.messages, hasLength(1));
      expect(runtime.messages.single.text, 'Hello, world!');
    },
  );

  test(
    'keeps chat page streaming active when overlay chat also listens',
    () async {
      const conversationId = 2202;
      const taskId = 'chat-task-overlay';
      final overlayService = AiChatService();
      String? overlayMessage;
      overlayService.setOnMessageCallback((taskId, content, type) {
        overlayMessage = content;
      });

      coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );
      coordinator.registerTask(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );

      await emitPlatformEvent('onChatMessage', <String, dynamic>{
        'taskID': taskId,
        'content': '{"choices":[{"delta":{"content":"shared pure chat"}}]}',
        'type': null,
      });

      final runtime = coordinator.runtimeFor(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      )!;

      expect(runtime.messages, hasLength(1));
      expect(runtime.messages.first.text, 'shared pure chat');
      expect(
        overlayMessage,
        '{"choices":[{"delta":{"content":"shared pure chat"}}]}',
      );

      overlayService.dispose();
    },
  );

  test('clears transient agent thinking state when a session ends', () {
    const conversationId = 4001;

    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );
    runtime.currentDispatchTaskId = 'agent-task';
    runtime.deepThinkingContent = 'old thinking';
    runtime.isDeepThinking = true;
    runtime.currentThinkingStage = 4;
    runtime.lastAgentTaskId = 'agent-task';
    runtime.activeToolCardId = 'agent-task-tool-1';
    runtime.activeThinkingCardId = 'agent-task-thinking';
    runtime.pendingAgentTextTaskId = 'agent-task';
    runtime.waitingThinkingBeforeAgentTextTaskId = 'agent-task';
    runtime.pendingThinkingRoundSplit = true;
    runtime.toolCardSequence = 3;
    runtime.thinkingRound = 2;

    coordinator.clearConversationRuntimeSession(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );

    expect(runtime.currentDispatchTaskId, isNull);
    expect(runtime.deepThinkingContent, isEmpty);
    expect(runtime.isDeepThinking, isFalse);
    expect(runtime.currentThinkingStage, 1);
    expect(runtime.lastAgentTaskId, isNull);
    expect(runtime.activeToolCardId, isNull);
    expect(runtime.activeThinkingCardId, isNull);
    expect(runtime.pendingAgentTextTaskId, isNull);
    expect(runtime.waitingThinkingBeforeAgentTextTaskId, isNull);
    expect(runtime.pendingThinkingRoundSplit, isFalse);
    expect(runtime.toolCardSequence, 0);
    expect(runtime.thinkingRound, 0);
  });

  test(
    'shows thinking before assistant content when reasoning arrives later',
    () async {
      const conversationId = 4451;
      const taskId = 'agent-thinking-before-content';

      final runtime = coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );
      runtime.currentDispatchTaskId = taskId;
      coordinator.registerTask(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );

      await emitPlatformEvent('onAgentThinkingStart', <String, dynamic>{
        'taskId': taskId,
      });
      await emitPlatformEvent('onAgentChatMessage', <String, dynamic>{
        'taskId': taskId,
        'message': '先给出结论。',
        'isFinal': false,
      });

      expect(
        visibleMessageIds(runtime),
        equals(<String>['$taskId-thinking', '$taskId-text']),
      );

      await emitPlatformEvent('onAgentThinkingUpdate', <String, dynamic>{
        'taskId': taskId,
        'thinking': '我先检查一下上下文。',
      });

      expect(
        visibleMessageIds(runtime),
        equals(<String>['$taskId-thinking', '$taskId-text']),
      );
      expect(
        runtime.messages
            .firstWhere((message) => message.id == '$taskId-text')
            .text,
        '先给出结论。',
      );
    },
  );

  test(
    'keeps assistant content visible when tool calls start afterwards',
    () async {
      const conversationId = 4501;
      const taskId = 'agent-task-with-content';

      final runtime = coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );
      runtime.currentDispatchTaskId = taskId;
      coordinator.registerTask(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );

      await emitPlatformEvent('onAgentChatMessage', <String, dynamic>{
        'taskId': taskId,
        'message': '看起来克隆还没完全完成，只有 `.git` 目录。让我再等待一下，然后重新检查。',
        'isFinal': false,
      });

      await emitPlatformEvent('onAgentToolCallStart', <String, dynamic>{
        'taskId': taskId,
        'toolName': 'terminal_execute',
        'displayName': 'terminal_execute',
        'toolType': 'terminal',
        'summary': '检查 git 状态',
      });

      final textMessage = runtime.messages.firstWhere(
        (msg) => msg.id == '$taskId-text',
      );
      final toolMessage = runtime.messages.firstWhere(
        (msg) => msg.cardData?['type'] == 'agent_tool_summary',
      );

      expect(textMessage.text, contains('克隆还没完全完成'));
      expect(toolMessage.cardData?['toolType'], 'terminal');
      expect(runtime.pendingAgentTextTaskId, isNull);
    },
  );

  test('keeps visible order as thinking then content then tool card', () async {
    const conversationId = 4520;
    const taskId = 'agent-thinking-content-tool';

    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );
    runtime.currentDispatchTaskId = taskId;
    coordinator.registerTask(
      taskId: taskId,
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );

    await emitPlatformEvent('onAgentThinkingStart', <String, dynamic>{
      'taskId': taskId,
    });
    await emitPlatformEvent('onAgentChatMessage', <String, dynamic>{
      'taskId': taskId,
      'message': '让我先检查仓库状态。',
      'isFinal': false,
    });
    await emitPlatformEvent('onAgentToolCallStart', <String, dynamic>{
      'taskId': taskId,
      'toolName': 'terminal_execute',
      'displayName': 'terminal_execute',
      'toolType': 'terminal',
      'summary': '检查 git 状态',
    });

    final visibleIds = visibleMessageIds(runtime);
    expect(visibleIds, hasLength(3));
    expect(visibleIds[0], '$taskId-thinking');
    expect(visibleIds[1], '$taskId-text');
    expect(
      runtime.messages
          .firstWhere(
            (message) => message.cardData?['type'] == 'agent_tool_summary',
          )
          .id,
      visibleIds[2],
    );
  });

  test('stores toolTitle from agent tool events on tool cards', () async {
    const conversationId = 4555;
    const taskId = 'agent-task-title';

    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );
    runtime.currentDispatchTaskId = taskId;
    coordinator.registerTask(
      taskId: taskId,
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );

    await emitPlatformEvent('onAgentToolCallStart', <String, dynamic>{
      'taskId': taskId,
      'toolName': 'file_read',
      'displayName': '读取文件',
      'toolType': 'workspace',
      'toolTitle': '查看配置',
      'summary': '查看配置',
      'argsJson': jsonEncode({'tool_title': '查看配置', 'path': 'README.md'}),
    });

    final toolMessage = runtime.messages.firstWhere(
      (msg) => msg.cardData?['type'] == 'agent_tool_summary',
    );

    expect(toolMessage.cardData?['toolTitle'], '查看配置');
  });

  test('releases buffered final content after a short timeout', () async {
    const conversationId = 4606;
    const taskId = 'agent-timeout-release';

    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );
    runtime.currentDispatchTaskId = taskId;
    coordinator.registerTask(
      taskId: taskId,
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );

    await emitPlatformEvent('onAgentThinkingStart', <String, dynamic>{
      'taskId': taskId,
    });
    await emitPlatformEvent('onAgentChatMessage', <String, dynamic>{
      'taskId': taskId,
      'message': '即使没等到思考文本，也要尽快显示正文。',
      'isFinal': true,
      'prefillTokensPerSecond': 12.3,
      'decodeTokensPerSecond': 45.6,
    });

    expect(
      visibleMessageIds(runtime),
      equals(<String>['$taskId-thinking', '$taskId-text']),
    );
    final textMessage = runtime.messages.firstWhere(
      (message) => message.id == '$taskId-text',
    );
    expect(textMessage.text, '即使没等到思考文本，也要尽快显示正文。');
    expect(textMessage.content?['prefillTokensPerSecond'], 12.3);
    expect(textMessage.content?['decodeTokensPerSecond'], 45.6);
  });

  test(
    'stores stream meta on deep thinking cards for history restoration',
    () async {
      const conversationId = 4666;
      const taskId = 'agent-task-thinking-persist';

      final runtime = coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );
      runtime.currentDispatchTaskId = taskId;
      coordinator.registerTask(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );

      await emitPlatformEvent('onAgentThinkingStart', <String, dynamic>{
        'taskId': taskId,
      });
      await emitPlatformEvent('onAgentThinkingUpdate', <String, dynamic>{
        'taskId': taskId,
        'thinking': '恢复后也要能看到这段思考',
      });
      await Future<void>.delayed(Duration.zero);
      final thinkingMessage = runtime.messages.firstWhere(
        (message) => message.id == '$taskId-thinking',
      );
      expect(thinkingMessage.cardData?['type'], 'deep_thinking');
      expect(thinkingMessage.cardData?['thinkingContent'], '恢复后也要能看到这段思考');
      expect(thinkingMessage.streamMeta?['seq'], 1);
      expect(thinkingMessage.streamMeta?['roundIndex'], 1);
      expect(thinkingMessage.streamMeta?['kind'], 'thinking_snapshot');
      expect(thinkingMessage.streamMeta?['parentTaskId'], taskId);
    },
  );

  test(
    'renders later content plus tool-call rounds as new assistant messages instead of overwriting earlier ones',
    () async {
      const conversationId = 4601;
      const taskId = 'agent-task-multi-round';

      final runtime = coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );
      runtime.currentDispatchTaskId = taskId;
      coordinator.registerTask(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );

      await emitPlatformEvent('onAgentChatMessage', <String, dynamic>{
        'taskId': taskId,
        'message': '第一轮：先检查仓库状态。',
        'isFinal': false,
      });

      await emitPlatformEvent('onAgentToolCallStart', <String, dynamic>{
        'taskId': taskId,
        'toolName': 'terminal_execute',
        'displayName': 'terminal_execute',
        'toolType': 'terminal',
        'summary': '检查 git 状态',
      });

      await emitPlatformEvent('onAgentToolCallComplete', <String, dynamic>{
        'taskId': taskId,
        'toolName': 'terminal_execute',
        'displayName': 'terminal_execute',
        'toolType': 'terminal',
        'summary': 'git 状态已返回',
        'success': true,
      });

      await emitPlatformEvent('onAgentChatMessage', <String, dynamic>{
        'taskId': taskId,
        'message': '第二轮：继续等待克隆完成。',
        'isFinal': false,
      });

      await emitPlatformEvent('onAgentChatMessage', <String, dynamic>{
        'taskId': taskId,
        'message': '第二轮：继续等待克隆完成，然后再次检查。',
        'isFinal': false,
      });

      final firstRoundMessage = runtime.messages.firstWhere(
        (msg) => msg.id == '$taskId-text',
      );
      final secondRoundMessage = runtime.messages.firstWhere(
        (msg) => msg.id == '$taskId-text-2',
      );

      expect(firstRoundMessage.text, '第一轮：先检查仓库状态。');
      expect(secondRoundMessage.text, '第二轮：继续等待克隆完成，然后再次检查。');
      for (final message in <ChatMessageModel>[
        firstRoundMessage,
        secondRoundMessage,
      ]) {
        expect(message.streamMeta?['parentTaskId'], taskId);
        expect(message.streamMeta?['kind'], 'text_snapshot');
        expect(message.streamMeta?['seq'], isNotNull);
      }
      expect(runtime.pendingAgentTextTaskId, taskId);
    },
  );

  test(
    'finalizes each agent thinking card when the stream moves on to tool or text output',
    () async {
      const conversationId = 4602;
      const taskId = 'agent-task-thinking-collapse';

      final runtime = coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );
      runtime.currentDispatchTaskId = taskId;
      coordinator.registerTask(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );

      await emitPlatformEvent('onAgentThinkingStart', <String, dynamic>{
        'taskId': taskId,
      });
      await emitPlatformEvent('onAgentThinkingUpdate', <String, dynamic>{
        'taskId': taskId,
        'thinking': '第一轮先分析仓库状态。',
      });
      await emitPlatformEvent('onAgentToolCallStart', <String, dynamic>{
        'taskId': taskId,
        'toolName': 'terminal_execute',
        'displayName': 'terminal_execute',
        'toolType': 'terminal',
        'summary': '检查 git 状态',
      });

      final firstThinkingCard = runtime.messages.firstWhere(
        (message) => message.id == '$taskId-thinking',
      );
      expect(firstThinkingCard.cardData?['isLoading'], isFalse);
      expect(firstThinkingCard.cardData?['stage'], 4);
      expect(firstThinkingCard.cardData?['endTime'], isNotNull);

      await emitPlatformEvent('onAgentThinkingStart', <String, dynamic>{
        'taskId': taskId,
      });
      await emitPlatformEvent('onAgentThinkingUpdate', <String, dynamic>{
        'taskId': taskId,
        'thinking': '第二轮根据工具结果继续分析。',
      });

      final secondThinkingCard = runtime.messages.firstWhere(
        (message) => message.id == '$taskId-thinking-2',
      );
      expect(secondThinkingCard.cardData?['isLoading'], isTrue);
      expect(secondThinkingCard.cardData?['stage'], 1);

      await emitPlatformEvent('onAgentChatMessage', <String, dynamic>{
        'taskId': taskId,
        'message': '第二轮给出最终结论。',
        'isFinal': false,
      });

      final finalizedSecondThinkingCard = runtime.messages.firstWhere(
        (message) => message.id == '$taskId-thinking-2',
      );
      expect(finalizedSecondThinkingCard.cardData?['isLoading'], isFalse);
      expect(finalizedSecondThinkingCard.cardData?['stage'], 4);
      expect(runtime.activeThinkingCardId, isNull);
      expect(runtime.isDeepThinking, isFalse);
    },
  );

  test('forces tools layer when browser or terminal tools start', () async {
    const conversationId = 5001;
    const taskId = 'agent-tool-task';

    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );
    runtime.currentDispatchTaskId = taskId;
    coordinator.registerTask(
      taskId: taskId,
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );

    await emitPlatformEvent('onAgentToolCallStart', <String, dynamic>{
      'taskId': taskId,
      'toolName': 'browser_use',
      'displayName': 'browser_use',
      'toolType': 'browser',
      'summary': 'open browser',
    });

    expect(runtime.chatIslandDisplayLayer, ChatIslandDisplayLayer.tools);
    expect(runtime.lastAgentToolType, 'browser');

    await emitPlatformEvent('onAgentToolCallStart', <String, dynamic>{
      'taskId': taskId,
      'toolName': 'terminal_execute',
      'displayName': 'terminal_execute',
      'toolType': 'terminal',
      'summary': 'run terminal',
    });

    expect(runtime.chatIslandDisplayLayer, ChatIslandDisplayLayer.tools);
    expect(runtime.lastAgentToolType, 'terminal');
  });

  test('stores browser session snapshot when browser tool completes', () async {
    const conversationId = 6001;
    const taskId = 'agent-browser-task';
    const workspaceId = 'conversation_6001';

    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );
    runtime.currentDispatchTaskId = taskId;
    coordinator.registerTask(
      taskId: taskId,
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );

    await emitPlatformEvent('onAgentToolCallStart', <String, dynamic>{
      'taskId': taskId,
      'toolName': 'browser_use',
      'displayName': 'browser_use',
      'toolType': 'browser',
      'summary': 'browser start',
    });

    await emitPlatformEvent('onAgentToolCallComplete', <String, dynamic>{
      'taskId': taskId,
      'toolName': 'browser_use',
      'displayName': 'browser_use',
      'toolType': 'browser',
      'summary': 'browser ready',
      'workspaceId': workspaceId,
      'success': true,
      'rawResultJson': jsonEncode(<String, dynamic>{
        'activeTabId': 7,
        'currentUrl': 'https://example.com/login',
        'pageTitle': 'Sign In',
        'userAgentProfile': 'desktop_safari',
      }),
    });

    final snapshot = runtime.browserSessionSnapshot;
    expect(runtime.chatIslandDisplayLayer, ChatIslandDisplayLayer.tools);
    expect(runtime.lastAgentToolType, 'browser');
    expect(snapshot, isNotNull);
    expect(snapshot?.workspaceId, workspaceId);
    expect(snapshot?.activeTabId, 7);
    expect(snapshot?.currentUrl, 'https://example.com/login');
    expect(snapshot?.title, 'Sign In');
    expect(snapshot?.userAgentProfile, 'desktop_safari');
  });

  test(
    'uses cardId from tool events when completing interrupted tools',
    () async {
      const conversationId = 6501;
      const taskId = 'agent-interrupted-tool-task';
      const cardId = 'agent-interrupted-tool-task-tool-9';

      final runtime = coordinator.ensureRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );
      runtime.currentDispatchTaskId = taskId;
      coordinator.registerTask(
        taskId: taskId,
        conversationId: conversationId,
        mode: kChatRuntimeModeNormal,
      );

      await emitPlatformEvent('onAgentToolCallStart', <String, dynamic>{
        'taskId': taskId,
        'cardId': cardId,
        'toolName': 'terminal_execute',
        'displayName': 'terminal_execute',
        'toolType': 'terminal',
        'summary': '执行长命令',
        'argsJson': jsonEncode(<String, dynamic>{'command': 'sleep 30'}),
      });

      await emitPlatformEvent('onAgentToolCallComplete', <String, dynamic>{
        'taskId': taskId,
        'cardId': cardId,
        'toolName': 'terminal_execute',
        'displayName': 'terminal_execute',
        'toolType': 'terminal',
        'status': 'interrupted',
        'summary': '工具调用已被用户手动停止',
        'success': false,
        'interruptedBy': 'user',
        'interruptionReason': 'manual_stop',
      });

      final toolMessage = runtime.messages.firstWhere(
        (message) => message.id == cardId,
      );

      expect(toolMessage.cardData?['status'], 'interrupted');
      expect(toolMessage.cardData?['interruptedBy'], 'user');
      expect(toolMessage.cardData?['interruptionReason'], 'manual_stop');
      expect(runtime.activeToolCardId, isNull);
    },
  );

  test('continues assistant output after interrupted tool completes', () async {
    const conversationId = 6502;
    const taskId = 'agent-interrupted-continue-task';
    const cardId = 'agent-interrupted-continue-task-tool-2';

    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );
    runtime.currentDispatchTaskId = taskId;
    coordinator.registerTask(
      taskId: taskId,
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
    );

    await emitPlatformEvent('onAgentToolCallStart', <String, dynamic>{
      'taskId': taskId,
      'cardId': cardId,
      'toolName': 'browser_use',
      'displayName': 'browser_use',
      'toolType': 'browser',
      'summary': '打开页面',
    });

    await emitPlatformEvent('onAgentToolCallComplete', <String, dynamic>{
      'taskId': taskId,
      'cardId': cardId,
      'toolName': 'browser_use',
      'displayName': 'browser_use',
      'toolType': 'browser',
      'status': 'interrupted',
      'summary': '工具调用已被用户手动停止',
      'success': false,
      'interruptedBy': 'user',
      'interruptionReason': 'manual_stop',
    });

    await emitPlatformEvent('onAgentChatMessage', <String, dynamic>{
      'taskId': taskId,
      'message': '浏览器工具已停止，我先直接告诉你页面当前不可达。',
      'isFinal': false,
    });

    final textMessage = runtime.messages.firstWhere(
      (message) => message.id == '$taskId-text',
    );
    expect(textMessage.text, contains('浏览器工具已停止'));
  });

  test('applies initial island layer when a runtime is created late', () {
    const conversationId = 7001;

    final runtime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
      initialChatIslandDisplayLayer: ChatIslandDisplayLayer.mode,
    );

    expect(runtime.chatIslandDisplayLayer, ChatIslandDisplayLayer.mode);

    final reused = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeNormal,
      initialChatIslandDisplayLayer: ChatIslandDisplayLayer.tools,
    );

    expect(identical(runtime, reused), isTrue);
    expect(reused.chatIslandDisplayLayer, ChatIslandDisplayLayer.mode);
  });

  test('maps legacy model island layer wire value to tools', () {
    expect(
      ChatIslandDisplayLayer.fromWireName('model'),
      ChatIslandDisplayLayer.tools,
    );
  });
}
