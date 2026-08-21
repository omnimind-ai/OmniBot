import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_event_reducer.dart';

void main() {
  late AgentEventReducer reducer;
  late ChatConversationRuntimeState runtime;

  setUp(() {
    reducer = const AgentEventReducer();
    runtime = ChatConversationRuntimeState(
      conversationId: 42,
      mode: kChatRuntimeModeAgent,
    );
  });

  tearDown(() {
    runtime.dispose();
  });

  test('maps agent message deltas into assistant text', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/agentMessage/delta',
          'params': {'turnId': 'turn-1', 'delta': 'hello'},
        },
      },
    );

    expect(result.handled, isTrue);
    expect(runtime.messages.single.text, 'hello');
    expect(runtime.messages.single.user, 2);
  });

  test('one turn completes reasoning and assistant text together', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-1',
          'turnId': 'turn-1',
          'update': {
            'sessionUpdate': 'agent_thought_chunk',
            'messageId': 'reason-1',
            'content': {'text': '先思考'},
          },
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-1',
          'turnId': 'turn-1',
          'update': {
            'sessionUpdate': 'agent_message_chunk',
            'messageId': 'message-1',
            'content': {'text': '最终答案'},
          },
        },
      },
    );

    final thinking = runtime.messages.firstWhere(
      (message) => message.cardData?['type'] == 'deep_thinking',
    );
    // Assistant text marks the thinking phase complete; the turn remains
    // active until the ACP terminal notification arrives.
    expect(thinking.cardData?['isLoading'], isFalse);
    expect(runtime.isAiResponding, isTrue);

    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'turn/completed',
        'params': {'turnId': 'turn-1'},
      },
    );

    expect(thinking.cardData?['isLoading'], isFalse);
    expect(runtime.isAiResponding, isFalse);
    expect(runtime.activeAgentTurnIds, isEmpty);
  });

  test(
    'projects native ACP session updates when turn id is on the envelope',
    () {
      // AgentRuntimeManager keeps session/update protocol params untouched and
      // attaches the host-owned turn id to the outer event envelope. This is
      // the shape emitted by LocalAcpRuntime for official DSH; the reducer must
      // not require a non-ACP turnId field inside params.update.
      reducer.reduce(
        runtime: runtime,
        event: {
          'method': 'session/update',
          'turnId': 'native-turn-1',
          'threadId': 'native-session-1',
          'params': {
            'sessionId': 'native-session-1',
            'update': {
              'sessionUpdate': 'agent_thought_chunk',
              'messageId': 'thought-1',
              'content': {'type': 'text', 'text': '来自 DSH 的思考'},
            },
          },
        },
      );

      final thinking = runtime.messages.firstWhere(
        (message) => message.cardData?['type'] == 'deep_thinking',
      );
      expect(thinking.cardData?['thinkingContent'], '来自 DSH 的思考');
      expect(thinking.cardData?['taskID'], 'native-turn-1');
      expect(runtime.isAiResponding, isTrue);
    },
  );

  test('a text-only turn completes without creating a thinking card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-1',
          'turnId': 'turn-1',
          'update': {
            'sessionUpdate': 'agent_message_chunk',
            'messageId': 'message-1',
            'content': {'text': '直接回答'},
          },
        },
      },
    );
    expect(
      runtime.messages.where(
        (message) => message.cardData?['type'] == 'deep_thinking',
      ),
      isEmpty,
    );

    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'turn/completed',
        'params': {'turnId': 'turn-1'},
      },
    );

    expect(runtime.isAiResponding, isFalse);
    expect(runtime.messages.single.text, '直接回答');
  });

  test('official turn completion clears a local dispatch placeholder', () {
    runtime
      ..isAiResponding = true
      ..currentDispatchTurnId = 'local-request'
      ..lastAgentTurnId = 'local-request'
      ..activeAcpTurnId = 'turn-1';

    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'turn/completed',
        'params': {
          'sessionId': 'session-1',
          'turnId': 'turn-1',
        },
      },
    );

    expect(runtime.isAiResponding, isFalse);
    expect(runtime.currentDispatchTurnId, isNull);
    expect(runtime.activeAcpTurnId, isNull);
  });

  test('terminal event closes a primed turn that never admitted its ACP id', () {
    runtime
      ..isAiResponding = true
      ..currentDispatchTurnId = 'local-request'
      ..lastAgentTurnId = 'local-request'
      ..isDeepThinking = true
      ..activeThinkingCardId = 'local-request-thinking';
    runtime.messages.add(
      ChatMessageModel(
        id: 'local-request-thinking',
        type: 2,
        user: 3,
        content: {
          'cardData': {
            'type': 'deep_thinking',
            'taskID': 'local-request',
            'cardId': 'local-request-thinking',
            'isLoading': true,
            'stage': ThinkingStage.thinking.value,
            'thinkingContent': '',
          },
          'id': 'local-request-thinking',
        },
      ),
    );

    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'turn/completed',
        'params': {'turnId': 'official-turn-1'},
      },
    );

    final card = runtime.messages.single.cardData!;
    expect(runtime.isAiResponding, isFalse);
    expect(runtime.isDeepThinking, isFalse);
    expect(runtime.currentDispatchTurnId, isNull);
    expect(card['isLoading'], isFalse);
    expect(card['stage'], ThinkingStage.complete.value);
  });

  test('many message ids in one turn stay a single active turn', () {
    // ACP mints a new `agent_message_chunk.messageId` for each assistant
    // message inside a turn, and the reducer caches text per message id. That
    // cache used to feed `activeAgentTurnIds`, so a five-message turn reported
    // five in-flight "tasks" and the chat list drew five agent avatars with
    // five "正在处理" rows. Message identity is not turn identity.
    for (final messageId in <String>['msg-a', 'msg-b', 'msg-c', 'msg-d']) {
      reducer.reduce(
        runtime: runtime,
        event: {
          'turnId': 'turn-1',
          'message': {
            'method': 'item/agentMessage/delta',
            'params': {
              'turnId': 'turn-1',
              'itemId': messageId,
              'delta': 'chunk for $messageId',
            },
          },
        },
      );
    }

    expect(runtime.messages, hasLength(4));
    expect(runtime.currentAiMessages, hasLength(4));
    expect(runtime.activeAgentTurnIds, <String>{'turn-1'});
  });

  test('turn completion clears the active turn and its text cache', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'turnId': 'turn-1',
        'message': {
          'method': 'item/agentMessage/delta',
          'params': {'turnId': 'turn-1', 'itemId': 'msg-a', 'delta': '答案'},
        },
      },
    );
    expect(runtime.activeAgentTurnIds, <String>{'turn-1'});

    reducer.reduce(
      runtime: runtime,
      event: {
        'turnId': 'turn-1',
        'message': {
          'method': 'turn/completed',
          'params': {
            'threadId': 'thread-1',
            'turn': {'id': 'turn-1', 'status': 'end_turn'},
          },
        },
      },
    );

    expect(runtime.isAiResponding, isFalse);
    expect(runtime.activeAgentTurnIds, isEmpty);
    expect(runtime.currentAiMessages, isEmpty);
  });

  test('turn completion clears the official turn owner', () {
    runtime.currentDispatchTurnId = 'turn-1';
    runtime.activeAcpTurnId = 'turn-1';
    runtime.lastAgentTurnId = 'turn-1';
    runtime.isAiResponding = true;

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/completed',
          'params': {'turnId': 'turn-1'},
        },
      },
    );

    expect(runtime.activeAgentTurnIds, isEmpty);
    expect(runtime.lastAgentTurnId, isNull);
  });

  test('ACP admits a turn after the local request placeholder', () {
    runtime.currentDispatchTurnId = 'request-1-ai';
    runtime.lastAgentTurnId = 'request-1-ai';
    runtime.isAiResponding = true;

    final started = reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'turn/started',
        'params': {'turnId': 'acp-turn-1'},
      },
    );

    expect(started.handled, isTrue);
    expect(runtime.activeAcpTurnId, 'acp-turn-1');
    expect(runtime.currentDispatchTurnId, 'acp-turn-1');

    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'turn/completed',
        'params': {'turnId': 'acp-turn-1'},
      },
    );

    expect(runtime.activeAcpTurnId, isNull);
    expect(runtime.currentDispatchTurnId, isNull);
    expect(runtime.isAiResponding, isFalse);
  });

  test('ACP admits a turn from the first session update when no turn started exists', () {
    runtime
      ..currentDispatchTurnId = 'request-1-ai'
      ..lastAgentTurnId = 'request-1-ai'
      ..isAiResponding = true;

    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-1',
          'turnId': 'acp-turn-1',
          'update': {
            'sessionUpdate': 'agent_message_chunk',
            'messageId': 'message-1',
            'content': {'text': 'OpenCode response'},
          },
        },
      },
    );

    expect(runtime.activeAcpTurnId, 'acp-turn-1');
    expect(runtime.messages.single.text, 'OpenCode response');

    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'turn/completed',
        'params': {'sessionId': 'session-1', 'turnId': 'acp-turn-1'},
      },
    );

    expect(runtime.isAiResponding, isFalse);
    expect(runtime.currentDispatchTurnId, isNull);
    expect(runtime.activeAcpTurnId, isNull);
  });

  test('late output from an older turn cannot reclaim the active turn', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/started',
          'params': {'turnId': 'turn-old'},
        },
      },
    );
    // Model the valid post-terminal hand-off without allowing a second
    // turn/started event to overwrite an active turn.
    runtime.currentDispatchTurnId = 'turn-new';
    runtime.lastAgentTurnId = 'turn-new';
    runtime.activeAcpTurnId = 'turn-new';
    runtime.isAiResponding = true;

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/agentMessage/delta',
          'params': {
            'turnId': 'turn-old',
            'itemId': 'old-message',
            'delta': '迟到的旧输出',
          },
        },
      },
    );

    expect(runtime.currentDispatchTurnId, 'turn-new');
    expect(runtime.messages, isEmpty);
  });

  test('turn-scoped ACP update without a turn id is ignored', () {
    runtime.currentDispatchTurnId = 'turn-active';
    runtime.lastAgentTurnId = 'turn-active';
    runtime.isAiResponding = true;
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-1',
          'update': {
            'sessionUpdate': 'agent_message_chunk',
            'messageId': 'message-1',
            'content': {'type': 'text', 'text': '无法归属'},
          },
        },
      },
    );

    expect(result.handled, isTrue);
    expect(runtime.messages, isEmpty);
    expect(runtime.currentDispatchTurnId, 'turn-active');
  });

  test('late completion from an older turn does not clear the newer turn', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/started',
          'params': {'turnId': 'turn-old'},
        },
      },
    );
    runtime.currentDispatchTurnId = 'turn-new';
    runtime.lastAgentTurnId = 'turn-new';
    runtime.activeAcpTurnId = 'turn-new';
    runtime.isAiResponding = true;
    expect(runtime.currentDispatchTurnId, 'turn-new');
    expect(runtime.isAiResponding, isTrue);

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/completed',
          'params': {'turnId': 'turn-old'},
        },
      },
    );

    expect(runtime.currentDispatchTurnId, 'turn-new');
    expect(runtime.lastAgentTurnId, 'turn-new');
    expect(runtime.isAiResponding, isTrue);
  });

  test('ignores ACP updates that arrive after their turn completed', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'session/update',
          'turnId': 'turn-1',
          'params': {
            'sessionId': 'session-1',
            'turnId': 'turn-1',
            'update': {
              'sessionUpdate': 'agent_message_chunk',
              'messageId': 'message-1',
              'content': {'type': 'text', 'text': '首段'},
            },
          },
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/completed',
          'params': {
            'turnId': 'turn-1',
            'turn': {'id': 'turn-1', 'status': 'end_turn'},
          },
        },
      },
    );

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'session/update',
          'turnId': 'turn-1',
          'params': {
            'sessionId': 'session-1',
            'turnId': 'turn-1',
            'update': {
              'sessionUpdate': 'agent_message_chunk',
              'messageId': 'message-1',
              'content': {'type': 'text', 'text': '迟到尾帧'},
            },
          },
        },
      },
    );

    expect(runtime.isAiResponding, isFalse);
    expect(runtime.messages.single.text, '首段');
  });

  test('maps reasoning deltas into deep thinking card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/reasoning/textDelta',
          'params': {'turnId': 'turn-1', 'delta': 'thinking'},
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['type'], 'deep_thinking');
    expect(cardData['thinkingContent'], 'thinking');
    expect(cardData['isLoading'], isTrue);
  });

  test('maps ACP agent thought chunks into deep thinking card', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'turnId': 'turn-claude',
        'message': {
          'method': 'item/reasoning/delta',
          'params': {
            'turnId': 'turn-claude',
            'itemId': 'claude-message-1',
            'delta': '先确认用户消息与当前轮次',
          },
        },
      },
    );

    expect(result.handled, isTrue);
    final message = runtime.messages.single;
    expect(message.id, 'claude-message-1-agent-thinking');
    expect(message.cardData?['type'], 'deep_thinking');
    expect(message.cardData?['taskID'], 'turn-claude');
    expect(message.cardData?['thinkingContent'], '先确认用户消息与当前轮次');
  });

  test('renders the official ACP session/update envelope', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'session/update',
          'turnId': 'turn-1',
          'params': {
            'sessionId': 'session-1',
            'update': {
              'sessionUpdate': 'agent_message_chunk',
              'messageId': 'message-1',
              'content': {'type': 'text', 'text': '来自标准 ACP'},
            },
          },
        },
      },
    );

    expect(result.handled, isTrue);
    expect(runtime.messages.single.text, '来自标准 ACP');
    expect(runtime.messages.single.user, 2);
  });

  test('deduplicates repeated committed ACP assistant blocks', () {
    final event = <String, dynamic>{
      'message': {
        'method': 'session/update',
        'turnId': 'turn-1',
        'params': {
          'sessionId': 'session-1',
          'update': {
            'sessionUpdate': 'agent_message_chunk',
            'messageId': 'message-1',
            'content': {'type': 'text', 'text': '来自 DSH 的完整消息'},
          },
        },
      },
    };

    reducer.reduce(runtime: runtime, event: event);
    reducer.reduce(runtime: runtime, event: event);

    expect(runtime.messages, hasLength(1));
    expect(runtime.messages.single.text, '来自 DSH 的完整消息');
  });

  test(
    'accepts cumulative committed ACP assistant blocks without repetition',
    () {
      Map<String, dynamic> event(String text) => <String, dynamic>{
        'message': {
          'method': 'session/update',
          'turnId': 'turn-1',
          'params': {
            'sessionId': 'session-1',
            'update': {
              'sessionUpdate': 'agent_message_chunk',
              'messageId': 'message-1',
              'content': {'type': 'text', 'text': text},
            },
          },
        },
      };

      reducer.reduce(runtime: runtime, event: event('第一段'));
      reducer.reduce(runtime: runtime, event: event('第一段第二段'));

      expect(runtime.messages.single.text, '第一段第二段');
    },
  );

  test('isolates ACP chunks without messageId by host turn', () {
    for (final turn in <String>['turn-1', 'turn-2']) {
      final result = reducer.reduce(
        runtime: runtime,
        event: {
          'turnId': turn,
          'message': {
            'method': 'session/update',
            'params': {
              'sessionId': 'session-1',
              'update': {
                'sessionUpdate': 'agent_message_chunk',
                'content': {'type': 'text', 'text': turn},
              },
            },
          },
        },
      );
      expect(result.handled, isTrue);
      if (turn == 'turn-1') {
        reducer.reduce(
          runtime: runtime,
          event: {
            'message': {
              'method': 'turn/completed',
              'params': {'turnId': turn},
            },
          },
        );
      }
    }

    expect(runtime.messages, hasLength(2));
    expect(runtime.messages.map((message) => message.text).toSet(), {
      'turn-1',
      'turn-2',
    });
  });

  test('isolates reused ACP message ids by host turn', () {
    Map<String, dynamic> messageEvent({
      required String turnId,
      required String text,
    }) {
      return <String, dynamic>{
        'message': {
          'method': 'session/update',
          'turnId': turnId,
          'params': {
            'sessionId': 'session-1',
            'update': {
              'sessionUpdate': 'agent_message_chunk',
              // DeepSeek Harness reuses this ACP messageId in later turns.
              'messageId': '1:1',
              'content': {'type': 'text', 'text': text},
            },
          },
        },
      };
    }

    reducer.reduce(
      runtime: runtime,
      event: messageEvent(turnId: 'turn-1', text: '第一轮'),
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/completed',
          'params': {'turnId': 'turn-1'},
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: messageEvent(turnId: 'turn-2', text: '第二轮'),
    );

    expect(runtime.messages, hasLength(2));
    expect(runtime.messages.map((message) => message.text).toSet(), {
      '第一轮',
      '第二轮',
    });
    expect(runtime.messages.map((message) => message.id).toSet(), {
      'turn-1-1:1-agent-message',
      'turn-2-1:1-agent-message',
    });
  });

  test('does not turn ACP config updates into a private event', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'session/update',
          'params': {
            'sessionId': 'session-1',
            'update': {
              'sessionUpdate': 'config_option_update',
              'configOptions': const <Map<String, dynamic>>[],
            },
          },
        },
      },
    );

    expect(result.handled, isTrue);
    expect(runtime.messages, isEmpty);
  });

  test('maps command output deltas into terminal tool card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/commandExecution/outputDelta',
          'params': {
            'turnId': 'turn-1',
            'itemId': 'cmd-1',
            'command': 'ls',
            'delta': 'file.txt\n',
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['type'], 'agent_tool_summary');
    expect(cardData['toolType'], 'terminal');
    expect(cardData['terminalOutput'], 'file.txt\n');
  });

  test('maps standalone command output deltas into terminal tool card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'command/exec/outputDelta',
          'params': {
            'processId': 'proc-1',
            'stream': 'stdout',
            'deltaBase64': base64Encode(utf8.encode('hello\n')),
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['type'], 'agent_tool_summary');
    expect(cardData['toolType'], 'terminal');
    expect(cardData['toolName'], 'agent.commandExec');
    expect(cardData['terminalOutput'], 'hello\n');
    expect(cardData['status'], 'running');
  });

  test('maps process exit snapshots into completed terminal card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'process/outputDelta',
          'params': {
            'processHandle': 'proc-2',
            'stream': 'stderr',
            'deltaBase64': base64Encode(utf8.encode('warning\n')),
          },
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'process/exited',
          'params': {
            'processHandle': 'proc-2',
            'exitCode': 1,
            'stdout': '',
            'stderr': 'failed\n',
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['toolType'], 'terminal');
    expect(cardData['status'], 'error');
    expect(cardData['terminalOutput'], contains('[stderr]'));
    expect(cardData['terminalOutput'], contains('warning'));
    expect(cardData['terminalOutput'], contains('failed'));
  });

  test('maps raw response read file calls into workspace tool card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'rawResponseItem/completed',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'item': {
              'type': 'function_call',
              'name': 'read_file',
              'call_id': 'call-read-1',
              'arguments': jsonEncode({'path': 'README.md'}),
            },
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['type'], 'agent_tool_summary');
    expect(cardData['toolType'], 'workspace');
    expect(cardData['toolTitle'], 'Read README.md');
    expect(cardData['status'], 'success');
    expect(cardData['argsJson'], contains('README.md'));
  });

  test('maps raw response local shell calls into terminal tool card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'rawResponseItem/completed',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'item': {
              'type': 'local_shell_call',
              'call_id': 'call-shell-1',
              'status': 'completed',
              'action': {
                'type': 'exec',
                'command': ['ls', '-la'],
                'working_directory': '/workspace',
              },
            },
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['toolType'], 'terminal');
    expect(cardData['toolTitle'], 'ls -la');
    expect(cardData['status'], 'success');
    expect(cardData['argsJson'], contains('ls -la'));
  });

  test('maps raw exec_command calls into terminal tool cards', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'rawResponseItem/completed',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'item': {
              'type': 'function_call',
              'name': 'exec_command',
              'call_id': 'call-cmd-1',
              'arguments': jsonEncode({
                'cmd':
                    'cd ui && flutter test test/services/agent_event_reducer_test.dart',
              }),
            },
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['toolType'], 'terminal');
    expect(cardData['toolTitle'], contains('flutter test'));
    expect(cardData['argsJson'], contains('flutter test'));
  });

  test('classifies raw rg exec_command calls as search tool cards', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'rawResponseItem/completed',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'item': {
              'type': 'function_call',
              'name': 'exec_command',
              'call_id': 'call-search-1',
              'arguments': jsonEncode({
                'cmd': 'rg -n "rawResponseItem" ui/lib ui/test',
              }),
            },
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['toolType'], 'search');
    expect(cardData['toolTitle'], 'rg -n "rawResponseItem" ui/lib ui/test');
    expect(cardData['status'], 'success');
  });

  test('keeps command output deltas on existing search command card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/started',
          'params': {
            'turnId': 'turn-1',
            'item': {
              'id': 'search-cmd-1',
              'type': 'commandExecution',
              'command': 'rg -n "session/update" ui/lib',
              'status': 'in_progress',
            },
          },
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/commandExecution/outputDelta',
          'params': {
            'turnId': 'turn-1',
            'itemId': 'search-cmd-1',
            'delta': 'ui/lib/services/agent_event_reducer.dart:1\n',
          },
        },
      },
    );

    expect(runtime.messages, hasLength(1));
    final cardData = runtime.messages.single.cardData!;
    expect(cardData['toolType'], 'search');
    expect(cardData['terminalOutput'], contains('agent_event_reducer.dart'));
    expect(cardData['status'], 'running');
  });

  test('raw function outputs complete and enrich existing tool cards', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'rawResponseItem/completed',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'item': {
              'type': 'function_call',
              'name': 'exec_command',
              'call_id': 'call-cmd-output-1',
              'arguments': jsonEncode({'cmd': 'flutter test'}),
            },
          },
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'rawResponseItem/completed',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'item': {
              'type': 'function_call_output',
              'call_id': 'call-cmd-output-1',
              'output': '00:01 +1: All tests passed!\n',
            },
          },
        },
      },
    );

    expect(runtime.messages, hasLength(1));
    final cardData = runtime.messages.single.cardData!;
    expect(cardData['toolType'], 'terminal');
    expect(cardData['toolTitle'], 'flutter test');
    expect(cardData['terminalOutput'], contains('All tests passed'));
    expect(cardData['status'], 'success');
  });

  test('raw output-only items still produce visible tool cards', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'rawResponseItem/completed',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'item': {
              'type': 'function_call_output',
              'call_id': 'call-output-only-1',
              'output': 'README.md contents',
            },
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['type'], 'agent_tool_summary');
    expect(cardData['toolType'], 'tool');
    expect(cardData['summary'], contains('README.md contents'));
    expect(cardData['rawResultJson'], contains('function_call_output'));
  });

  test('raw response items without ids use stable distinct fallback ids', () {
    for (final query in const ['first query', 'second query']) {
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'rawResponseItem/completed',
            'params': {
              'threadId': 'thread-1',
              'turnId': 'turn-1',
              'item': {
                'type': 'web_search_call',
                'status': 'completed',
                'action': {'type': 'search', 'query': query},
              },
            },
          },
        },
      );
    }

    expect(runtime.messages, hasLength(2));
    expect(runtime.messages.map((message) => message.id).toSet(), hasLength(2));
    expect(
      runtime.messages.map((message) => message.cardData?['toolTitle']),
      containsAll(<String>['Search: first query', 'Search: second query']),
    );
  });

  test('maps file diffs into first-class diff tool cards', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/fileChange/outputDelta',
          'params': {
            'turnId': 'turn-1',
            'itemId': 'file-1',
            'path': 'lib/main.dart',
            'delta': '''
diff --git a/lib/main.dart b/lib/main.dart
--- a/lib/main.dart
+++ b/lib/main.dart
@@ -1,2 +1,2 @@
-old line
+new line
 same line
''',
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['type'], 'agent_tool_summary');
    expect(cardData['toolType'], 'file');
    expect(cardData['showDiff'], isTrue);
    expect(cardData['filePath'], 'lib/main.dart');
    expect(cardData['additions'], 1);
    expect(cardData['deletions'], 1);
    expect(cardData['summary'], contains('+1 -1'));
    expect((cardData['diffText'] ?? '').toString(), contains('diff --git'));
  });

  test(
    'ACP sparse completion updates keep one file card and preserve its diff',
    () {
      const callId = 'call-file-1';
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'item/started',
            'params': {
              'turnId': 'turn-1',
              'item': {
                'id': callId,
                'type': 'fileChange',
                'title': 'Write',
                'status': 'pending',
                'content': <dynamic>[],
                'rawInput': jsonEncode({
                  'file_path': '/workspace/edit-demo.txt',
                  'content': 'old line\n',
                }),
              },
            },
          },
        },
      );
      final initialSequence = runtime.messages.single.streamMeta?['seq'];
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'item/updated',
            'params': {
              'turnId': 'turn-1',
              'item': {
                'id': callId,
                'type': 'fileChange',
                'title': 'Write edit-demo.txt',
                'status': null,
                'content': [
                  {
                    'type': 'diff',
                    'path': '/workspace/edit-demo.txt',
                    'oldText': 'old line\n',
                    'newText': 'new line\n',
                  },
                ],
                'rawInput': jsonEncode({
                  'file_path': '/workspace/edit-demo.txt',
                  'content': 'new line\n',
                }),
              },
            },
          },
        },
      );
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'item/completed',
            'params': {
              'turnId': 'turn-1',
              'item': {
                'id': callId,
                'type': 'tool',
                'title': null,
                'status': 'completed',
                'content': null,
                'rawInput': null,
                'rawOutput': '"File created successfully"',
              },
            },
          },
        },
      );

      expect(runtime.messages, hasLength(1));
      expect(runtime.messages.single.id, '$callId-agent-file');
      final cardData = runtime.messages.single.cardData!;
      expect(cardData['toolType'], 'file');
      expect(cardData['toolTitle'], 'Write edit-demo.txt');
      expect(cardData['status'], 'success');
      expect(cardData['showDiff'], isTrue);
      expect(cardData['filePath'], '/workspace/edit-demo.txt');
      expect(cardData['summary'], '1 file · +1 -1');
      expect(cardData['argsJson'], contains('/workspace/edit-demo.txt'));
      expect(cardData['rawResultJson'], contains('File created successfully'));
      expect(runtime.messages.single.streamMeta?['seq'], initialSequence);
    },
  );

  test(
    'ACP command cards prefer raw input description over generic labels',
    () {
      const callId = 'call-command-1';
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'item/started',
            'params': {
              'turnId': 'turn-1',
              'item': {
                'id': callId,
                'type': 'commandExecution',
                'title': 'Terminal',
                'status': 'pending',
                'rawInput': '{}',
              },
            },
          },
        },
      );
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'item/completed',
            'params': {
              'turnId': 'turn-1',
              'item': {
                'id': callId,
                'type': 'commandExecution',
                'title': 'ls -la /workspace',
                'status': 'completed',
                'rawInput': jsonEncode({
                  'command': 'ls -la /workspace',
                  'description': 'Inspect the workspace contents',
                }),
              },
            },
          },
        },
      );

      expect(runtime.messages, hasLength(1));
      final cardData = runtime.messages.single.cardData!;
      expect(cardData['toolTitle'], 'Inspect the workspace contents');
      expect(cardData['toolTitle'], isNot('Agent command'));
      expect(cardData['status'], 'success');
      expect(cardData['argsJson'], contains('ls -la /workspace'));
    },
  );

  test('maps hunk-only changes json into first-class diff tool cards', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/fileChange/outputDelta',
          'params': {
            'turnId': 'turn-1',
            'itemId': 'call-1',
            'type': 'fileChange',
            'id': 'call-1',
            'changes': jsonEncode({
              'path': '/repo/test/services/agent_diff_parser_test.dart',
              'kind': {'type': 'update', 'move_path': null},
              'diff': '''
@@ -1,2 +1,2 @@
-old line
+new line
 same line
''',
            }),
            'status': 'completed',
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['type'], 'agent_tool_summary');
    expect(cardData['toolType'], 'file');
    expect(cardData['toolTitle'], 'Edit agent_diff_parser_test.dart');
    expect(cardData['showDiff'], isTrue);
    expect(
      cardData['filePath'],
      '/repo/test/services/agent_diff_parser_test.dart',
    );
    expect(cardData['changedFiles'], 1);
    expect(cardData['additions'], 1);
    expect(cardData['deletions'], 1);
    expect(cardData['summary'], '1 file · +1 -1');
    expect((cardData['diffText'] ?? '').toString(), contains('diff --git'));
  });

  test('hydrates historical hunk-only file changes as diff cards', () {
    final messages = remoteCodexMessagesFromThreadResponseForTesting({
      'thread': {
        'id': 'thread-1',
        'turns': [
          {
            'id': 'turn-1',
            'items': [
              {
                'id': 'call-1',
                'type': 'fileChange',
                'status': 'completed',
                'changes': jsonEncode({
                  'path': '/repo/lib/main.dart',
                  'kind': {'type': 'update'},
                  'diff': '''
@@ -1,2 +1,2 @@
-old line
+new line
 same line
''',
                }),
              },
            ],
          },
        ],
      },
    });

    final cardData = messages.single.cardData!;
    expect(cardData['toolType'], 'file');
    expect(cardData['showDiff'], isTrue);
    expect(cardData['filePath'], '/repo/lib/main.dart');
    expect(cardData['additions'], 1);
    expect(cardData['deletions'], 1);
  });

  test('hydrates historical codex tool item variants as tool cards', () {
    final messages = remoteCodexMessagesFromThreadResponseForTesting({
      'thread': {
        'id': 'thread-1',
        'turns': [
          {
            'id': 'turn-1',
            'items': [
              {
                'id': 'search-1',
                'type': 'webSearch',
                'query': 'Codex app server protocol',
                'status': 'completed',
              },
              {
                'id': 'image-1',
                'type': 'imageView',
                'path': '/tmp/screenshot.png',
                'status': 'completed',
              },
              {
                'id': 'tool-1',
                'type': 'mcpToolCall',
                'tool': 'mcp__filesystem__read_file',
                'arguments': '{"path":"README.md"}',
                'status': 'completed',
              },
              {
                'id': 'sdk-read-1',
                'type': 'mcp_tool_call',
                'server': 'filesystem',
                'tool': 'read_file',
                'arguments': {'path': 'AGENTS.md'},
                'status': 'completed',
              },
              {
                'id': 'sdk-cmd-1',
                'type': 'command_execution',
                'command': 'flutter test',
                'aggregated_output': '00:01 +1: All tests passed!',
                'exit_code': 0,
                'status': 'completed',
              },
              {
                'type': 'function_call',
                'name': 'read_file',
                'call_id': 'raw-read-1',
                'arguments': '{"path":"lib/main.dart"}',
              },
              {
                'type': 'local_shell_call',
                'call_id': 'raw-shell-1',
                'status': 'completed',
                'action': {
                  'type': 'exec',
                  'command': ['git', 'status'],
                },
              },
            ],
          },
        ],
      },
    });

    final cards = messages.map((message) => message.cardData!).toList();
    expect(
      cards.map((card) => card['toolType']),
      containsAll(<String>['search', 'image', 'workspace', 'terminal']),
    );
    expect(
      cards.map((card) => card['toolTitle']),
      containsAll(<String>[
        'Search: Codex app server protocol',
        'View screenshot.png',
        'Read README.md',
        'Read AGENTS.md',
        'flutter test',
        'Read main.dart',
        'git status',
      ]),
    );
  });

  test('hydrates historical raw function outputs onto matching tool card', () {
    final messages = remoteCodexMessagesFromThreadResponseForTesting({
      'thread': {
        'id': 'thread-1',
        'turns': [
          {
            'id': 'turn-1',
            'items': [
              {
                'type': 'function_call',
                'name': 'exec_command',
                'call_id': 'raw-cmd-1',
                'arguments': '{"cmd":"flutter test"}',
              },
              {
                'type': 'function_call_output',
                'call_id': 'raw-cmd-1',
                'output': '00:01 +1: All tests passed!',
              },
            ],
          },
        ],
      },
    });

    expect(messages, hasLength(1));
    final cardData = messages.single.cardData!;
    expect(cardData['toolType'], 'terminal');
    expect(cardData['toolTitle'], 'flutter test');
    expect(cardData['terminalOutput'], contains('All tests passed'));
    expect(cardData['summary'], contains('All tests passed'));
  });

  test('hydrates codex user image blocks as message attachments', () {
    final messages = remoteCodexMessagesFromThreadResponseForTesting({
      'thread': {
        'id': 'thread-1',
        'turns': [
          {
            'id': 'turn-1',
            'items': [
              {
                'id': 'user-1',
                'type': 'userMessage',
                'content': [
                  {'type': 'text', 'text': '看这张图'},
                  {
                    'type': 'image',
                    'detail': null,
                    'url': 'data:image/png;base64,AAAA',
                  },
                ],
              },
            ],
          },
        ],
      },
    });

    final message = messages.single;
    expect(message.user, 1);
    expect(message.text, '看这张图');
    expect(message.text, isNot(contains('data:image')));
    expect(message.text, isNot(contains('{type: image')));

    final attachments = message.content?['attachments'] as List;
    expect(attachments, hasLength(1));
    final attachment = attachments.single as Map<String, dynamic>;
    expect(attachment['dataUrl'], 'data:image/png;base64,AAAA');
    expect(attachment['mimeType'], 'image/png');
    expect(attachment['isImage'], isTrue);
  });

  test('uses file paths for concise file change tool titles', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/started',
          'params': {
            'turnId': 'turn-1',
            'item': {
              'id': 'file-1',
              'type': 'fileChange',
              'path': '/repo/lib/main.dart',
            },
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['toolTitle'], 'Edit main.dart');
  });

  test('uses generic tool params for concise tool titles', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/started',
          'params': {
            'turnId': 'turn-1',
            'item': {
              'id': 'tool-1',
              'type': 'tool',
              'toolName': 'mcp__context7__query_docs',
              'arguments': '{"query":"Riverpod provider override"}',
            },
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['toolTitle'], 'query_docs: Riverpod provider override');
  });

  test('maps mcp read file calls into workspace tool cards', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/started',
          'params': {
            'turnId': 'turn-1',
            'item': {
              'id': 'tool-1',
              'type': 'mcpToolCall',
              'tool': 'mcp__filesystem__read_file',
              'arguments': '{"path":"README.md"}',
            },
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['type'], 'agent_tool_summary');
    expect(cardData['toolType'], 'workspace');
    expect(cardData['toolTitle'], 'Read README.md');
    expect(cardData['argsJson'], contains('README.md'));
  });

  test(
    'maps sdk command_execution events without method into terminal cards',
    () {
      final started = reducer.reduce(
        runtime: runtime,
        event: {
          'type': 'item.started',
          'thread_id': 'thread-1',
          'turn_id': 'turn-1',
          'item': {
            'id': 'cmd-1',
            'type': 'command_execution',
            'command': 'cd ui && flutter test',
            'aggregated_output': '',
            'status': 'in_progress',
          },
        },
      );

      expect(started.handled, isTrue);
      var cardData = runtime.messages.single.cardData!;
      expect(cardData['type'], 'agent_tool_summary');
      expect(cardData['toolType'], 'terminal');
      expect(cardData['toolTitle'], 'cd ui && flutter test');
      expect(cardData['status'], 'running');

      reducer.reduce(
        runtime: runtime,
        event: {
          'type': 'item.completed',
          'thread_id': 'thread-1',
          'turn_id': 'turn-1',
          'item': {
            'id': 'cmd-1',
            'type': 'command_execution',
            'command': 'cd ui && flutter test',
            'aggregated_output': '00:01 +1: All tests passed!\n',
            'exit_code': 0,
            'status': 'completed',
          },
        },
      );

      cardData = runtime.messages.single.cardData!;
      expect(cardData['toolType'], 'terminal');
      expect(cardData['status'], 'success');
      expect(cardData['terminalOutput'], contains('All tests passed'));
    },
  );

  test(
    'maps sdk mcp_tool_call read events without method into workspace cards',
    () {
      final result = reducer.reduce(
        runtime: runtime,
        event: {
          'type': 'item.completed',
          'thread_id': 'thread-1',
          'turn_id': 'turn-1',
          'item': {
            'id': 'read-1',
            'type': 'mcp_tool_call',
            'server': 'filesystem',
            'tool': 'read_file',
            'arguments': {'path': 'README.md'},
            'status': 'completed',
          },
        },
      );

      expect(result.handled, isTrue);
      final cardData = runtime.messages.single.cardData!;
      expect(cardData['type'], 'agent_tool_summary');
      expect(cardData['toolType'], 'workspace');
      expect(cardData['toolTitle'], 'Read README.md');
      expect(cardData['argsJson'], contains('README.md'));
    },
  );

  test('completed command snapshots update terminal output and status', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/started',
          'params': {
            'turnId': 'turn-1',
            'item': {
              'id': 'cmd-1',
              'type': 'commandExecution',
              'command': 'npm test',
            },
          },
        },
      },
    );

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/completed',
          'params': {
            'turnId': 'turn-1',
            'itemId': 'cmd-1',
            'item': {
              'id': 'cmd-1',
              'type': 'commandExecution',
              'command': 'npm test',
              'aggregatedOutput': 'test failed\n',
              'exitCode': 1,
            },
          },
        },
      },
    );

    expect(runtime.messages, hasLength(1));
    final cardData = runtime.messages.single.cardData!;
    expect(cardData['toolType'], 'terminal');
    expect(cardData['status'], 'error');
    expect(cardData['terminalOutput'], 'test failed\n');
  });

  test('patch updated events keep file diff cards current', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/fileChange/patchUpdated',
          'params': {
            'turnId': 'turn-1',
            'itemId': 'file-1',
            'changes': jsonEncode({
              'path': '/repo/lib/app.dart',
              'kind': {'type': 'update'},
              'diff': '''
@@ -1 +1 @@
-old
+new
''',
            }),
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['toolType'], 'file');
    expect(cardData['showDiff'], isTrue);
    expect(cardData['filePath'], '/repo/lib/app.dart');
    expect(cardData['summary'], '1 file · +1 -1');
  });

  test('keeps agent message entries separate by codex item id', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/agentMessage/delta',
          'params': {'turnId': 'turn-1', 'itemId': 'msg-1', 'delta': 'first'},
        },
      },
    );

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/agentMessage/delta',
          'params': {'turnId': 'turn-1', 'itemId': 'msg-2', 'delta': 'second'},
        },
      },
    );

    expect(runtime.messages.map((message) => message.id).toList(), <String>[
      'msg-2-agent-message',
      'msg-1-agent-message',
    ]);
    expect(runtime.messages.first.streamMeta?['parentTaskId'], 'turn-1');
    expect(
      runtime.messages.first.streamMeta?['entryId'],
      'msg-2-agent-message',
    );
    expect(runtime.messages.first.streamMeta?['seq'], 2);
    expect(runtime.messages.last.streamMeta?['seq'], 1);
  });

  test('marks thread active from object status payload', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'thread/status/changed',
          'params': {
            'threadId': 'thread-1',
            'status': {'type': 'active', 'activeFlags': <dynamic>[]},
          },
        },
      },
    );

    expect(result.handled, isTrue);
    expect(runtime.isAiResponding, isTrue);
  });

  test('marks upstream turn started notification as processing', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'turn/started',
        'params': {
          'threadId': 'thread-1',
          'turn': {'id': 'turn-1', 'status': 'inProgress'},
        },
      },
    );

    expect(result.handled, isTrue);
    expect(result.threadId, 'thread-1');
    expect(result.turnId, 'turn-1');
    expect(runtime.isAiResponding, isTrue);
    expect(runtime.currentDispatchTurnId, 'turn-1');
  });

  test(
    'renders latest snapshot reasoning as active without explicit turn id',
    () {
      final messages = remoteCodexMessagesFromThreadResponseForTesting({
        'thread': {
          'id': 'thread-1',
          'status': {'type': 'active', 'activeFlags': <dynamic>[]},
          'turns': [
            {
              'id': 'turn-1',
              'status': 'inProgress',
              'items': [
                {
                  'id': 'user-1',
                  'type': 'userMessage',
                  'content': [
                    {'text': 'hi'},
                  ],
                },
                {
                  'id': 'reasoning-1',
                  'type': 'reasoning',
                  'summary': ['thinking'],
                  'content': <dynamic>[],
                },
              ],
            },
          ],
        },
      }, active: true);

      final cardData = messages.first.cardData!;
      expect(cardData['type'], 'deep_thinking');
      expect(cardData['isLoading'], isTrue);
      expect(cardData['stage'], ThinkingStage.thinking.value);
      expect(cardData['isCollapsible'], isFalse);
      expect(messages.first.streamMeta?['isFinal'], isFalse);
    },
  );

  test('detects stale-normalized remote active turn shape', () {
    final looksActive = remoteCodexLatestTurnLooksExternallyActiveForTesting({
      'thread': {
        'id': 'thread-1',
        'status': {'type': 'idle'},
        'turns': [
          {
            'id': 'turn-1',
            'status': 'interrupted',
            'completedAt': null,
            'items': [
              {
                'id': 'reasoning-1',
                'type': 'reasoning',
                'summary': ['still writing'],
              },
            ],
          },
        ],
      },
    });

    expect(looksActive, isTrue);
  });

  test('marks thread idle from object status payload', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'thread/status/changed',
          'params': {
            'threadId': 'thread-1',
            'status': {'type': 'active'},
          },
        },
      },
    );

    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'thread/status/changed',
          'params': {
            'threadId': 'thread-1',
            'status': {'type': 'idle'},
          },
        },
      },
    );

    expect(result.handled, isTrue);
    expect(runtime.isAiResponding, isFalse);
  });

  test('thread idle finalizes active turn without cancellation body', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/started',
          'params': {'threadId': 'thread-1', 'turnId': 'turn-1'},
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/reasoning/textDelta',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'itemId': 'reason-1',
            'delta': 'thinking',
          },
        },
      },
    );

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'thread/status/changed',
          'params': {
            'threadId': 'thread-1',
            'status': {'type': 'idle'},
          },
        },
      },
    );

    expect(runtime.isAiResponding, isFalse);
    expect(runtime.currentDispatchTurnId, isNull);
    expect(
      runtime.messages.any((message) => message.id.endsWith('cancelled')),
      isFalse,
    );
    expect(runtime.messages.single.cardData!['isLoading'], isFalse);
  });

  test('ignores replayed assistant deltas after snapshot hydration', () {
    runtime.messages.add(
      ChatMessageModel(
        id: 'msg-1-agent-message',
        type: 1,
        user: 2,
        content: {'text': 'Hello', 'id': 'msg-1-agent-message'},
      ),
    );

    for (final delta in const ['Hel', 'lo', '!']) {
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'item/agentMessage/delta',
            'params': {'turnId': 'turn-1', 'itemId': 'msg-1', 'delta': delta},
          },
        },
      );
    }

    expect(runtime.messages.single.text, 'Hello!');
  });

  test('replayed assistant deltas do not restart an idle turn', () {
    runtime.messages.add(
      ChatMessageModel(
        id: 'msg-1-agent-message',
        type: 1,
        user: 2,
        content: {'text': 'Hello', 'id': 'msg-1-agent-message'},
      ),
    );

    for (final delta in const ['Hel', 'lo']) {
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'item/agentMessage/delta',
            'params': {'turnId': 'turn-1', 'itemId': 'msg-1', 'delta': delta},
          },
        },
      );
    }

    expect(runtime.messages.single.text, 'Hello');
    expect(runtime.isAiResponding, isFalse);
    expect(runtime.currentDispatchTurnId, isNull);
    expect(runtime.currentAiMessages, isEmpty);
  });

  test('idle status does not clear partial replay delta offsets', () {
    runtime.messages.add(
      ChatMessageModel(
        id: 'msg-1-agent-message',
        type: 1,
        user: 2,
        content: {'text': 'Hello', 'id': 'msg-1-agent-message'},
      ),
    );

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/agentMessage/delta',
          'params': {'turnId': 'turn-1', 'itemId': 'msg-1', 'delta': 'Hel'},
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'thread/status/changed',
          'params': {
            'threadId': 'thread-1',
            'status': {'type': 'idle'},
          },
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/agentMessage/delta',
          'params': {'turnId': 'turn-1', 'itemId': 'msg-1', 'delta': 'lo'},
        },
      },
    );

    expect(runtime.messages.single.text, 'Hello');
    expect(runtime.isAiResponding, isFalse);
    expect(runtime.currentDispatchTurnId, isNull);
  });

  test('keeps replay delta offsets across matching snapshot replacement', () {
    final coordinator = ChatConversationRuntimeCoordinator.instance;
    const conversationId = 420042;
    final hydratedMessage = ChatMessageModel(
      id: 'msg-1-agent-message',
      type: 1,
      user: 2,
      content: {'text': 'Hello', 'id': 'msg-1-agent-message'},
    );
    final coordinatorRuntime = coordinator.ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
      initialMessages: [hydratedMessage],
    );
    coordinatorRuntime.agentReplayDeltaOffsets['msg-1-agent-message'] = 3;
    coordinatorRuntime.agentReplayDeltaOffsets['stale-entry'] = 2;

    coordinator.replaceConversationSnapshot(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
      messages: [hydratedMessage],
    );

    final updatedRuntime = coordinator.runtimeFor(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    )!;
    expect(updatedRuntime.agentReplayDeltaOffsets['msg-1-agent-message'], 3);
    expect(
      updatedRuntime.agentReplayDeltaOffsets.containsKey('stale-entry'),
      isFalse,
    );
  });

  test(
    'preserves extra local duplicate user messages missing from snapshot',
    () {
      final now = DateTime.fromMillisecondsSinceEpoch(1700000000000);
      final merged = mergeRemoteCodexSnapshotMessagesForTesting(
        snapshotMessages: [
          ChatMessageModel(
            id: 'remote-user-1',
            type: 1,
            user: 1,
            content: {'text': 'again', 'id': 'remote-user-1'},
            createAt: now,
          ),
        ],
        existingMessages: [
          ChatMessageModel(
            id: 'local-user-2',
            type: 1,
            user: 1,
            content: {'text': 'again', 'id': 'local-user-2'},
            createAt: now.add(const Duration(seconds: 2)),
          ),
          ChatMessageModel(
            id: 'local-user-1',
            type: 1,
            user: 1,
            content: {'text': 'again', 'id': 'local-user-1'},
            createAt: now.add(const Duration(seconds: 1)),
          ),
        ],
        activeTaskId: null,
        isAiResponding: false,
      );

      expect(merged.map((message) => message.id), contains('remote-user-1'));
      expect(merged.map((message) => message.id), contains('local-user-2'));
      expect(
        merged.map((message) => message.id),
        isNot(contains('local-user-1')),
      );
    },
  );

  test(
    'merge finalizes stale local thinking cards for the active codex turn',
    () {
      final now = DateTime.fromMillisecondsSinceEpoch(1700000000000);
      final merged = mergeRemoteCodexSnapshotMessagesForTesting(
        snapshotMessages: [
          ChatMessageModel.cardMessage({
            'type': 'deep_thinking',
            'taskID': 'turn-1',
            'cardId': 'reason-2-agent-thinking',
            'isLoading': true,
            'isCollapsible': false,
            'stage': ThinkingStage.thinking.value,
            'thinkingContent': 'latest',
            'startTime': now
                .add(const Duration(seconds: 2))
                .millisecondsSinceEpoch,
          }, id: 'reason-2-agent-thinking').copyWith(
            createAt: now.add(const Duration(seconds: 2)),
          ),
        ],
        existingMessages: [
          ChatMessageModel.cardMessage({
            'type': 'deep_thinking',
            'taskID': 'turn-1',
            'cardId': 'reason-1-agent-thinking',
            'isLoading': true,
            'isCollapsible': false,
            'stage': ThinkingStage.thinking.value,
            'thinkingContent': 'older',
            'startTime': now.millisecondsSinceEpoch,
          }, id: 'reason-1-agent-thinking').copyWith(createAt: now),
        ],
        activeTaskId: 'turn-1',
        isAiResponding: true,
      );

      final thinkingCards = merged
          .where((message) => message.cardData?['type'] == 'deep_thinking')
          .toList();
      expect(thinkingCards, hasLength(2));
      final latest = thinkingCards.firstWhere(
        (message) => message.id == 'reason-2-agent-thinking',
      );
      final older = thinkingCards.firstWhere(
        (message) => message.id == 'reason-1-agent-thinking',
      );
      expect(latest.cardData!['isLoading'], isTrue);
      expect(older.cardData!['isLoading'], isFalse);
      expect(older.cardData!['stage'], ThinkingStage.complete.value);
    },
  );

  test('preserves live pending user input request missing from snapshot', () {
    final now = DateTime.fromMillisecondsSinceEpoch(1700000000000);
    final pendingRequest = ChatMessageModel.cardMessage(
      {
        'type': 'codex_request',
        'taskId': 'turn-1',
        'cardId': 'request-1-agent-user-input',
        'requestId': 'request-1',
        'requestKind': 'user_input',
        'questionId': 'confirm_path',
        'status': 'pending',
      },
      id: 'request-1-agent-user-input',
      streamMeta: {
        'parentTaskId': 'turn-1',
        'entryId': 'request-1-agent-user-input',
        'kind': 'clarify_required',
        'isFinal': false,
      },
    ).copyWith(createAt: now.add(const Duration(seconds: 1)));

    final merged = mergeRemoteCodexSnapshotMessagesForTesting(
      snapshotMessages: [
        ChatMessageModel(
          id: 'remote-user-1',
          type: 1,
          user: 1,
          content: {'text': 'ask something', 'id': 'remote-user-1'},
          createAt: now,
        ),
      ],
      existingMessages: [pendingRequest],
      activeTaskId: 'turn-1',
      isAiResponding: true,
    );

    final request = merged.singleWhere(
      (message) => message.id == 'request-1-agent-user-input',
    );
    expect(request.cardData!['type'], 'agent_request');
    expect(request.cardData!['requestKind'], 'user_input');
    expect(request.cardData!['status'], 'pending');
  });

  test('finalizes assistant item without duplicating completed text', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/agentMessage/delta',
          'params': {'turnId': 'turn-1', 'itemId': 'msg-1', 'delta': 'Hel'},
        },
      },
    );

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/completed',
          'params': {
            'turnId': 'turn-1',
            'item': {'id': 'msg-1', 'type': 'agentMessage', 'text': 'Hello'},
          },
        },
      },
    );

    expect(runtime.messages.single.text, 'Hello');
    expect(runtime.messages.single.streamMeta?['isFinal'], isTrue);
    expect(runtime.currentAiMessages, isEmpty);
  });

  test('keeps reasoning timer stable across deltas and completion', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/started',
          'params': {
            'turnId': 'turn-1',
            'item': {'id': 'reason-1', 'type': 'reasoning'},
          },
        },
      },
    );

    final startedCard = runtime.messages.single;
    final startedStartTime = startedCard.cardData!['startTime'];

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/reasoning/textDelta',
          'params': {
            'turnId': 'turn-1',
            'itemId': 'reason-1',
            'delta': 'thinking',
          },
        },
      },
    );

    expect(runtime.messages.single.id, 'reason-1-agent-thinking');
    expect(runtime.messages.single.cardData!['startTime'], startedStartTime);
    expect(runtime.messages.single.cardData!['thinkingContent'], 'thinking');

    // item/completed for reasoning no longer flips the card to complete — the
    // turn may still emit more reasoning, tool calls, or an agent message.
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/completed',
          'params': {
            'turnId': 'turn-1',
            'item': {'id': 'reason-1', 'type': 'reasoning'},
          },
        },
      },
    );
    final midTurnCard = runtime.messages.single.cardData!;
    expect(midTurnCard['startTime'], startedStartTime);
    expect(midTurnCard['isLoading'], isTrue);
    expect(midTurnCard['stage'], ThinkingStage.thinking.value);

    // turn/completed is the terminal signal that finalizes the thinking card.
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/completed',
          'params': {'turnId': 'turn-1'},
        },
      },
    );

    final completedCard = runtime.messages
        .firstWhere((message) => message.cardData?['type'] == 'deep_thinking')
        .cardData!;
    expect(completedCard['startTime'], startedStartTime);
    expect(completedCard['stage'], ThinkingStage.complete.value);
    expect(completedCard['isLoading'], isFalse);
    expect(completedCard['endTime'], isNotNull);
  });

  test('normal turn completion never creates a cancellation body', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/started',
          'params': {'turnId': 'turn-1'},
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/reasoning/textDelta',
          'params': {
            'turnId': 'turn-1',
            'itemId': 'reason-1',
            'delta': 'thinking',
          },
        },
      },
    );

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/completed',
          'params': {'turnId': 'turn-1'},
        },
      },
    );

    expect(
      runtime.messages.any((message) => message.id == 'turn-1-cancelled'),
      isFalse,
    );

    final thinkingCard = runtime.messages
        .firstWhere((message) => message.cardData?['type'] == 'deep_thinking')
        .cardData!;
    expect(thinkingCard['isLoading'], isFalse);
    expect(thinkingCard['stage'], ThinkingStage.complete.value);
    expect(thinkingCard['endTime'], isNotNull);
  });

  test('updates tool cards in place with stable codex stream metadata', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/commandExecution/outputDelta',
          'params': {
            'turnId': 'turn-1',
            'itemId': 'cmd-1',
            'command': 'ls',
            'delta': 'a\n',
          },
        },
      },
    );

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/commandExecution/outputDelta',
          'params': {
            'turnId': 'turn-1',
            'itemId': 'cmd-1',
            'command': 'ls',
            'delta': 'b\n',
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['terminalOutput'], 'a\nb\n');
    expect(
      runtime.messages.single.streamMeta?['entryId'],
      'cmd-1-agent-command',
    );
    expect(runtime.messages.single.streamMeta?['seq'], 1);
  });

  test('maps approval requests into codex request card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'id': 7,
          'method': 'item/commandExecution/requestApproval',
          'params': {'command': 'rm tmp.txt', 'reason': 'cleanup'},
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['type'], 'agent_request');
    expect(cardData['requestKind'], 'approval');
    expect(cardData['requestId'], 7);
  });

  test('maps request user input into codex request card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'id': 'request-1',
          'method': 'item/tool/requestUserInput',
          'params': {
            'questions': [
              {'id': 'choice', 'question': 'Choose one'},
            ],
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['type'], 'agent_request');
    expect(cardData['requestKind'], 'user_input');
    expect(cardData['questionId'], 'choice');
    expect(cardData['rawParamsJson'], contains('Choose one'));
    expect(cardData['status'], 'pending');
    expect(cardData['conversationId'], 42);
  });

  test('reads collaboration mode from thread settings update', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'thread/settings/updated',
        'params': {
          'threadId': 'thread-1',
          'threadSettings': {
            'collaborationMode': {'mode': 'default'},
          },
        },
      },
    );

    expect(result.handled, isTrue);
    expect(result.method, 'thread/settings/updated');
    expect(result.threadId, 'thread-1');
    expect(result.collaborationMode, 'default');
  });

  test('maps app-server request_user_input request before turn completes', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'item/tool/requestUserInput',
        'threadId': 'thread-1',
        'turnId': 'turn-1',
        'message': {
          'jsonrpc': '2.0',
          'id': 0,
          'method': 'item/tool/requestUserInput',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'itemId': 'call1',
            'questions': [
              {
                'id': 'confirm_path',
                'header': 'Confirm',
                'question': 'Proceed with the plan?',
                'options': [
                  {
                    'label': 'Yes (Recommended)',
                    'description': 'Continue the current plan.',
                  },
                  {
                    'label': 'No',
                    'description': 'Stop and revisit the approach.',
                  },
                ],
              },
            ],
            'autoResolutionMs': 60000,
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(result.handled, isTrue);
    expect(result.requestId, 0);
    expect(cardData['type'], 'agent_request');
    expect(cardData['requestKind'], 'user_input');
    expect(cardData['requestId'], 0);
    expect(cardData['taskId'], 'turn-1');
    expect(cardData['questionId'], 'confirm_path');
    expect(cardData['rawParamsJson'], contains('Yes (Recommended)'));
    expect(runtime.isAiResponding, isTrue);
  });

  test('keeps submitted request user input status during event replay', () {
    final requestEvent = {
      'message': {
        'id': 'request-1',
        'method': 'item/tool/requestUserInput',
        'params': {
          'questions': [
            {
              'id': 'choice',
              'question': 'Choose one',
              'options': [
                {'label': 'Option A'},
              ],
            },
          ],
        },
      },
    };

    reducer.reduce(runtime: runtime, event: requestEvent);
    final existing = runtime.messages.single;
    final submittedCardData = Map<String, dynamic>.from(existing.cardData!)
      ..['status'] = 'submitted';
    runtime.messages[0] = existing.copyWith(
      content: {'cardData': submittedCardData, 'id': existing.id},
    );

    reducer.reduce(runtime: runtime, event: requestEvent);

    expect(runtime.messages.single.cardData!['status'], 'submitted');
  });

  test('hydrates historical request user input as submitted request card', () {
    final messages = remoteCodexMessagesFromThreadResponseForTesting({
      'thread': {
        'id': 'thread-1',
        'turns': [
          {
            'id': 'turn-1',
            'items': [
              {
                'id': 'request-1',
                'type': 'requestUserInput',
                'status': 'completed',
                'questions': [
                  {
                    'id': 'choice',
                    'question': 'Choose one',
                    'options': [
                      {'label': 'Option A'},
                    ],
                  },
                ],
                'answers': {
                  'choice': {
                    'answers': ['Option A'],
                  },
                },
              },
            ],
          },
        ],
      },
    });

    final cardData = messages.single.cardData!;
    expect(cardData['type'], 'agent_request');
    expect(cardData['requestKind'], 'user_input');
    expect(cardData['questionId'], 'choice');
    expect(cardData['status'], 'submitted');
    expect(cardData['rawParamsJson'], contains('Option A'));
  });

  test('ignores unknown events without throwing', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'future/event',
          'params': {'raw': true},
        },
      },
    );

    expect(result.handled, isFalse);
    expect(runtime.messages, isEmpty);
  });

  test('ignores codex stderr logs without creating tool cards', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'codex/stderr',
          'params': {'message': 'startup log'},
        },
      },
    );

    expect(result.handled, isFalse);
    expect(runtime.messages, isEmpty);
  });

  test('removes stale codex stderr status cards', () {
    runtime.messages.add(
      ChatMessageModel.cardMessage({
        'type': 'agent_tool_summary',
        'toolName': 'codex.status',
        'toolTitle': 'codex/stderr',
        'displayName': 'codex/stderr',
        'status': 'running',
      }, id: 'stderr-status'),
    );

    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'codex/stderr',
          'params': {'message': 'startup log'},
        },
      },
    );

    expect(result.handled, isTrue);
    expect(runtime.messages, isEmpty);
  });

  test(
    'reasoning item/completed during active turn keeps thinking card loading',
    () {
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'turn/started',
            'params': {'threadId': 'thread-1', 'turnId': 'turn-1'},
          },
        },
      );
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'item/reasoning/textDelta',
            'params': {
              'threadId': 'thread-1',
              'turnId': 'turn-1',
              'itemId': 'reason-1',
              'delta': 'analysing the request',
            },
          },
        },
      );
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'item/completed',
            'params': {
              'threadId': 'thread-1',
              'turnId': 'turn-1',
              'itemId': 'reason-1',
              'item': {
                'id': 'reason-1',
                'type': 'reasoning',
                'summary': 'analysing the request',
              },
            },
          },
        },
      );

      final card = runtime.messages.firstWhere(
        (message) => message.cardData?['type'] == 'deep_thinking',
      );
      final cardData = card.cardData!;
      expect(cardData['isLoading'], isTrue);
      expect(cardData['isCollapsible'], isFalse);
      expect(cardData['stage'], ThinkingStage.thinking.value);
      expect(runtime.isAiResponding, isTrue);
    },
  );

  test('new reasoning item finalizes previous loading thinking card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/started',
          'params': {'threadId': 'thread-1', 'turnId': 'turn-1'},
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/reasoning/textDelta',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'itemId': 'reason-1',
            'delta': 'first thought',
          },
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/completed',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'itemId': 'reason-1',
            'item': {'id': 'reason-1', 'type': 'reasoning'},
          },
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/reasoning/textDelta',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'itemId': 'reason-2',
            'delta': 'second thought',
          },
        },
      },
    );

    final first = runtime.messages.firstWhere(
      (message) => message.id == 'reason-1-agent-thinking',
    );
    final second = runtime.messages.firstWhere(
      (message) => message.id == 'reason-2-agent-thinking',
    );
    expect(first.cardData!['isLoading'], isFalse);
    expect(first.cardData!['stage'], ThinkingStage.complete.value);
    expect(second.cardData!['isLoading'], isTrue);
    expect(second.cardData!['thinkingContent'], 'second thought');
  });

  test('turn/completed finalizes the thinking card after reasoning ends', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/started',
          'params': {'threadId': 'thread-1', 'turnId': 'turn-1'},
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/reasoning/textDelta',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'itemId': 'reason-1',
            'delta': 'finished thinking',
          },
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/completed',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'itemId': 'reason-1',
            'item': {
              'id': 'reason-1',
              'type': 'reasoning',
              'summary': 'finished thinking',
            },
          },
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/completed',
          'params': {'threadId': 'thread-1', 'turnId': 'turn-1'},
        },
      },
    );

    final card = runtime.messages.firstWhere(
      (message) => message.cardData?['type'] == 'deep_thinking',
    );
    final cardData = card.cardData!;
    expect(cardData['isLoading'], isFalse);
    expect(cardData['isCollapsible'], isTrue);
    expect(cardData['stage'], ThinkingStage.complete.value);
    expect(runtime.isAiResponding, isFalse);
  });

  test('top-level error with willRetry=false finalizes the active turn', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/started',
          'params': {'threadId': 'thread-1', 'turnId': 'turn-1'},
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/reasoning/textDelta',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'itemId': 'reason-1',
            'delta': 'thinking',
          },
        },
      },
    );

    expect(runtime.isAiResponding, isTrue);

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'error',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'willRetry': false,
            'message': 'connection lost',
          },
        },
      },
    );

    expect(runtime.isAiResponding, isFalse);
    expect(runtime.currentDispatchTurnId, isNull);
    final thinking = runtime.messages
        .firstWhere((message) => message.cardData?['type'] == 'deep_thinking')
        .cardData!;
    expect(thinking['isLoading'], isFalse);
    expect(thinking['stage'], ThinkingStage.complete.value);
  });

  test('top-level error with willRetry=true keeps the turn active', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'turn/started',
          'params': {'threadId': 'thread-1', 'turnId': 'turn-1'},
        },
      },
    );

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'error',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'willRetry': true,
            'message': 'rate limited',
          },
        },
      },
    );

    expect(runtime.isAiResponding, isTrue);
    expect(runtime.currentDispatchTurnId, isNotNull);
  });

  test(
    'snapshot renders reasoning as loading even when item.status is completed '
    'while turn is active',
    () {
      final messages = remoteCodexMessagesFromThreadResponseForTesting(
        {
          'thread': {
            'id': 'thread-1',
            'status': {'type': 'active'},
            'turns': [
              {
                'id': 'turn-1',
                'status': 'inProgress',
                'items': [
                  {
                    'id': 'reason-1',
                    'type': 'reasoning',
                    'status': 'completed',
                    'summary': ['done reasoning'],
                  },
                ],
              },
            ],
          },
        },
        active: true,
        activeTurnId: 'turn-1',
      );

      final cardData = messages.first.cardData!;
      expect(cardData['type'], 'deep_thinking');
      expect(cardData['isLoading'], isTrue);
      expect(cardData['isCollapsible'], isFalse);
      expect(cardData['stage'], ThinkingStage.thinking.value);
    },
  );

  test(
    'snapshot keeps only the latest reasoning card loading for active turn',
    () {
      final messages = remoteCodexMessagesFromThreadResponseForTesting(
        {
          'thread': {
            'id': 'thread-1',
            'status': {'type': 'active'},
            'turns': [
              {
                'id': 'turn-1',
                'status': 'inProgress',
                'items': [
                  {
                    'id': 'reason-1',
                    'type': 'reasoning',
                    'status': 'completed',
                    'summary': ['older reasoning'],
                  },
                  {
                    'id': 'reason-2',
                    'type': 'reasoning',
                    'status': 'completed',
                    'summary': ['latest reasoning'],
                  },
                ],
              },
            ],
          },
        },
        active: true,
        activeTurnId: 'turn-1',
      );

      final first = messages.firstWhere(
        (message) => message.id == 'reason-1-agent-thinking',
      );
      final second = messages.firstWhere(
        (message) => message.id == 'reason-2-agent-thinking',
      );
      expect(first.cardData!['isLoading'], isFalse);
      expect(first.cardData!['stage'], ThinkingStage.complete.value);
      expect(second.cardData!['isLoading'], isTrue);
      expect(second.cardData!['stage'], ThinkingStage.thinking.value);
    },
  );

  test(
    'item/started commandExecution with commandActions read uses workspace card and keeps deltas',
    () {
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'item/started',
            'params': {
              'threadId': 'thread-1',
              'turnId': 'turn-1',
              'item': {
                'id': 'read-2',
                'type': 'commandExecution',
                'command': 'sed -n 1,200p AGENTS.md',
                'cwd': '/repo',
                'status': 'in_progress',
                'commandActions': <Map<String, dynamic>>[
                  {
                    'type': 'read',
                    'command': 'sed -n 1,200p AGENTS.md',
                    'name': 'AGENTS.md',
                    'path': '/repo/AGENTS.md',
                  },
                ],
              },
            },
          },
        },
      );
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'item/commandExecution/outputDelta',
            'params': {
              'threadId': 'thread-1',
              'turnId': 'turn-1',
              'itemId': 'read-2',
              'delta': '# Project AGENTS.md\n',
            },
          },
        },
      );

      expect(runtime.messages, hasLength(1));
      final cardData = runtime.messages.single.cardData!;
      expect(cardData['toolType'], 'workspace');
      expect(cardData['toolTitle'], 'Read AGENTS.md');
      expect(cardData['terminalOutput'], contains('Project AGENTS.md'));
      expect(cardData['status'], 'running');
    },
  );

  test('parsed_cmd list_files at item/started becomes workspace List card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'item/started',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'item': {
              'id': 'list-1',
              'type': 'commandExecution',
              'command': 'ls /repo/ui',
              'cwd': '/repo',
              'status': 'in_progress',
              'commandActions': <Map<String, dynamic>>[
                {
                  'type': 'listFiles',
                  'command': 'ls /repo/ui',
                  'path': '/repo/ui',
                },
              ],
            },
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['toolType'], 'workspace');
    expect(cardData['toolTitle'], 'List ui');
  });

  test('rawResponseItem function_call js with arguments.title shows title', () {
    // Mirrors the OpenAI Responses path: codex app-server forwards
    // EVERY function_call ResponseItem as rawResponseItem/completed. For
    // node_repl/js the arguments JSON carries a human-readable title
    // alongside the code blob.
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'rawResponseItem/completed',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'item': {
              'type': 'function_call',
              'name': 'js',
              'call_id': 'call_agQUhiEvZgvXKxX7ursGybbn',
              'arguments': jsonEncode({
                'title': 'Refine flavor parsing',
                'code': "const fs2 = await import('node:fs/promises');",
              }),
            },
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['type'], 'agent_tool_summary');
    expect(cardData['toolTitle'], 'Refine flavor parsing');
  });

  test('rawResponseItem function_call exec_command shows the cmd as title', () {
    // exec_command is the dominant tool in the user-reported session
    // (22 occurrences). Arguments JSON has {cmd, workdir, max_output_tokens}.
    // The card should be a terminal-type card titled with the cmd.
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'rawResponseItem/completed',
          'params': {
            'threadId': 'thread-1',
            'turnId': 'turn-1',
            'item': {
              'type': 'function_call',
              'name': 'exec_command',
              'call_id': 'call_5qvsAWrt1UCjXkfPlakIsqXD',
              'arguments': jsonEncode({
                'cmd': "sed -n '1,260p' app/build.gradle.kts",
                'workdir': '/Users/ocean/code/OmnibotApp',
                'max_output_tokens': 16000,
              }),
            },
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['type'], 'agent_tool_summary');
    expect(cardData['toolType'], 'terminal');
    expect(
      cardData['toolTitle'],
      contains("sed -n '1,260p' app/build.gradle.kts"),
    );
  });

  test(
    'function_call_output for exec_command merges output into the same card',
    () {
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'rawResponseItem/completed',
            'params': {
              'threadId': 'thread-1',
              'turnId': 'turn-1',
              'item': {
                'type': 'function_call',
                'name': 'exec_command',
                'call_id': 'call_merge_1',
                'arguments': jsonEncode({
                  'cmd': 'pwd',
                  'workdir': '/repo',
                  'max_output_tokens': 2000,
                }),
              },
            },
          },
        },
      );
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'rawResponseItem/completed',
            'params': {
              'threadId': 'thread-1',
              'turnId': 'turn-1',
              'item': {
                'type': 'function_call_output',
                'call_id': 'call_merge_1',
                'output': '/repo\n',
              },
            },
          },
        },
      );

      expect(runtime.messages, hasLength(1));
      final cardData = runtime.messages.single.cardData!;
      expect(cardData['toolType'], 'terminal');
      expect(cardData['toolTitle'], 'pwd');
      expect(cardData['terminalOutput'], contains('/repo'));
      expect(cardData['status'], 'success');
    },
  );

  test(
    'item/started mcpToolCall without title falls back to tool short name',
    () {
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'item/started',
            'params': {
              'threadId': 'thread-1',
              'turnId': 'turn-1',
              'item': {
                'id': 'mcp-1',
                'type': 'mcpToolCall',
                'tool': 'plain_tool',
                'arguments': '{}',
              },
            },
          },
        },
      );

      final cardData = runtime.messages.single.cardData!;
      expect(cardData['toolTitle'], 'plain_tool');
    },
  );
}
