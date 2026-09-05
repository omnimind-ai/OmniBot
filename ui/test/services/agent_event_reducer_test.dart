import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/agent_event_reducer.dart';
import 'package:ui/services/agent_identity.dart';

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

  test(
    'partial HTML input stays current on the same card and survives serialization',
    () {
      const partials = ['{"content":"<html>', '{"content":"<html>正在生成正文'];
      for (var index = 0; index < partials.length; index++) {
        reducer.reduce(
          runtime: runtime,
          event: {
            'method': 'session/update',
            'turnId': 'html-turn',
            'params': {
              'sessionId': 'html-session',
              'update': {
                'sessionUpdate': index == 0 ? 'tool_call' : 'tool_call_update',
                'toolCallId': 'html-input',
                if (index == 0) ...{
                  'title': '写入文件',
                  'kind': 'edit',
                  'status': 'pending',
                },
                'rawInput': partials[index],
              },
            },
          },
        );
        final card = runtime.messages.single.cardData!;
        expect(card['status'], 'pending');
        expect(card['argsJson'], partials[index]);
        expect(card['toolCallId'], 'html-input');
        final restored = ChatMessageModel.fromJson(
          jsonDecode(jsonEncode(runtime.messages.single.toJson())),
        );
        expect(restored.cardData!['argsJson'], partials[index]);
      }
    },
  );

  for (final stopReason in ['cancelled', 'error', 'end_turn']) {
    test(
      'official $stopReason never invents success for unfinished HTML tools',
      () {
        for (final entry in {
          'input': 'pending',
          'writing': 'in_progress',
          'saved': 'completed',
          'denied': 'failed',
        }.entries) {
          reducer.reduce(
            runtime: runtime,
            event: {
              'method': 'session/update',
              'turnId': 'html-turn',
              'params': {
                'sessionId': 'html-session',
                'update': {
                  'sessionUpdate': 'tool_call',
                  'toolCallId': entry.key,
                  'title': '写入文件',
                  'kind': 'edit',
                  'status': entry.value,
                  'rawInput': entry.key == 'input'
                      ? '{"content":"<html>'
                      : {'path': '/workspace/${entry.key}.html'},
                },
              },
            },
          );
        }
        reducer.reducePromptResponse(
          runtime: runtime,
          sessionId: 'html-session',
          turnId: 'html-turn',
          stopReason: stopReason,
          error: stopReason == 'error' ? 'connection lost' : null,
        );
        final cards = runtime.messages
            .where((message) => message.cardData?['toolType'] == 'file')
            .map((message) => message.cardData!)
            .toList();
        expect(cards, hasLength(4));
        expect(
          {for (final card in cards) card['toolCallId']: card['status']},
          {
            'input': 'pending',
            'writing': 'running',
            'saved': 'success',
            'denied': 'error',
          },
        );
        expect(runtime.isAiResponding, isFalse);
        expect(
          cards.any(
            (card) => (card['argsJson'] ?? '').toString().contains('<html>'),
          ),
          isTrue,
        );
      },
    );
  }

  test('reads ACP identity through the bridge event envelope', () {
    final event = <String, dynamic>{
      'message': {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-nested',
          'turnId': 'turn-nested',
          'update': {
            'sessionUpdate': 'agent_thought_chunk',
            'content': {'text': 'nested reasoning'},
          },
        },
      },
    };

    expect(acpEventSessionId(event), 'session-nested');
    expect(acpEventTurnId(event), 'turn-nested');
  });

  test('ignores a duplicated host ACP notification by explicit event id', () {
    final event = <String, dynamic>{
      'eventId': 'session-dedupe:1',
      'method': 'session/update',
      'turnId': 'turn-dedupe',
      'params': {
        'sessionId': 'session-dedupe',
        'update': {
          'sessionUpdate': 'agent_message_chunk',
          'messageId': 'message-dedupe',
          'content': {'type': 'text', 'text': '只显示一次'},
        },
      },
    };

    reducer.reduce(runtime: runtime, event: event);
    reducer.reduce(runtime: runtime, event: Map<String, dynamic>.from(event));

    expect(runtime.messages, hasLength(1));
    expect(runtime.messages.single.text, '只显示一次');
  });

  test('imports the removed AgentStreamEvent shape through ACP identity', () {
    final first = reducer.reduce(
      runtime: runtime,
      event: {
        'kind': 'text_snapshot',
        'taskId': 'legacy-task-1',
        'entryId': 'legacy-message-1',
        'seq': 1,
        'text': '旧 Harness 的回答',
      },
    );

    expect(first.handled, isTrue);
    expect(runtime.messages.single.text, '旧 Harness 的回答');
    expect(runtime.messages.single.turnId, 'legacy-task-1');

    reducer.reduce(
      runtime: runtime,
      event: {'kind': 'completed', 'taskId': 'legacy-task-1', 'seq': 2},
    );

    expect(runtime.isAiResponding, isTrue);
    expect(runtime.messages.single.text, '旧 Harness 的回答');
    expect(runtime.acpCompatibilityDiagnostics, isEmpty);
  });

  test('imports legacy tool lifecycle into the shared ACP tool card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'kind': 'tool_started',
        'taskId': 'legacy-tool-turn',
        'entryId': 'legacy-tool-1',
        'seq': 1,
        'toolName': 'terminal_execute',
        'toolType': 'terminal',
        'status': 'running',
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'kind': 'tool_completed',
        'taskId': 'legacy-tool-turn',
        'entryId': 'legacy-tool-1',
        'seq': 2,
        'toolName': 'terminal_execute',
        'toolType': 'terminal',
        'status': 'success',
        'summary': '命令完成',
      },
    );

    final cards = runtime.messages.where(
      (message) => message.cardData?['type'] == 'agent_tool_summary',
    );
    expect(cards, hasLength(1));
    expect(cards.single.cardData?['status'], 'success');
    expect(cards.single.cardData?['taskId'], 'legacy-tool-turn');
  });

  test('quarantines a turn event without identity instead of merging it', () {
    runtime
      ..isAiResponding = true
      ..currentDispatchTurnId = 'xiaowan-turn-1'
      ..activeAcpTurnId = 'xiaowan-turn-1';

    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'item/agentMessage/delta',
        'params': {'delta': 'late output without turn id'},
      },
    );

    expect(result.handled, isTrue);
    expect(result.compatibilityWarning, contains('缺少 turnId'));
    expect(runtime.messages, isEmpty);
    expect(runtime.acpCompatibilityDiagnostics, hasLength(1));
    expect(
      runtime.acpCompatibilityDiagnostics.single['reason'],
      'turn_id_missing',
    );
    expect(runtime.currentDispatchTurnId, 'xiaowan-turn-1');

    final repeated = reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'item/agentMessage/delta',
        'params': {'delta': 'another late output without turn id'},
      },
    );
    expect(repeated.compatibilityWarning, isNull);
    expect(runtime.acpCompatibilityDiagnostics, hasLength(2));
  });

  test('missing ACP messageId still stays scoped to its official turn', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-no-message-id',
          'turnId': 'turn-no-message-id',
          'update': {
            'sessionUpdate': 'agent_message_chunk',
            'content': {'type': 'text', 'text': '第一段'},
          },
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-no-message-id',
          'turnId': 'turn-no-message-id',
          'update': {
            'sessionUpdate': 'agent_message_chunk',
            'content': {'type': 'text', 'text': '第二段'},
          },
        },
      },
    );

    expect(runtime.messages, hasLength(1));
    expect(runtime.messages.single.text, '第一段第二段');
    expect(runtime.acpCompatibilityDiagnostics, isEmpty);
  });

  test(
    'missing messageId uses item identity to keep same-turn messages separate',
    () {
      for (final itemId in <String>['item-a', 'item-b']) {
        reducer.reduce(
          runtime: runtime,
          event: {
            'method': 'session/update',
            'params': {
              'sessionId': 'session-same-turn',
              'turnId': 'turn-same-turn',
              'update': {
                'sessionUpdate': 'agent_message_chunk',
                'itemId': itemId,
                'content': {'type': 'text', 'text': itemId},
              },
            },
          },
        );
      }

      expect(runtime.messages, hasLength(2));
      expect(
        runtime.messages.map((message) => message.text),
        containsAll(<String>['item-a', 'item-b']),
      );
    },
  );

  test('retains scalar and list ACP unknown updates without a turn id', () {
    for (final rawUpdate in <dynamic>[
      'provider-progress',
      <dynamic>['phase-1', 0.5],
    ]) {
      reducer.reduce(
        runtime: runtime,
        event: {
          'method': 'session/update',
          'params': {
            'sessionId': 'session-extension-shape',
            'update': {
              'sessionUpdate': 'vendor_progress',
              'rawUpdate': rawUpdate,
            },
          },
        },
      );
    }

    expect(runtime.acpExtensionUpdates, hasLength(2));
    expect(
      runtime.acpExtensionUpdates.map((entry) => entry['rawUpdate']),
      containsAll(<dynamic>[
        'provider-progress',
        <dynamic>['phase-1', 0.5],
      ]),
    );
  });

  test('retains all ACP extension updates for a long-lived conversation', () {
    for (var index = 0; index < 700; index += 1) {
      reducer.reduce(
        runtime: runtime,
        event: {
          'method': 'session/update',
          'params': {
            'sessionId': 'session-long-extension-history',
            'update': {
              'sessionUpdate': 'vendor_progress',
              'rawUpdate': {'sequence': index},
            },
          },
        },
      );
    }

    expect(runtime.acpExtensionUpdates, hasLength(700));
    expect(runtime.acpExtensionUpdates.first['rawUpdate']['sequence'], 0);
    expect(runtime.acpExtensionUpdates.last['rawUpdate']['sequence'], 699);
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

  test('ACP assistant chunks preserve Markdown whitespace byte for byte', () {
    const chunks = <String>[
      '程序运行成功了！',
      '\n\n',
      '---\n\n## 完成情况',
      '\n\n### 程序效果\n\n',
      '```text\n第一行\n第二行\n```',
      '\n\n后续 **加粗** 和 [链接](https://example.com)',
    ];

    for (final chunk in chunks) {
      reducer.reduce(
        runtime: runtime,
        event: {
          'method': 'session/update',
          'params': {
            'sessionId': 'session-markdown',
            'turnId': 'turn-markdown',
            'update': {
              'sessionUpdate': 'agent_message_chunk',
              'messageId': 'message-markdown',
              'content': {'type': 'text', 'text': chunk},
            },
          },
        },
      );
    }

    expect(runtime.messages.single.text, chunks.join());
  });

  test('ACP assistant chunks preserve spaces at token boundaries', () {
    for (final chunk in const <String>[
      'POSIX',
      ' Shell',
      ' and',
      ' Markdown',
    ]) {
      reducer.reduce(
        runtime: runtime,
        event: {
          'method': 'session/update',
          'params': {
            'sessionId': 'session-spaces',
            'turnId': 'turn-spaces',
            'update': {
              'sessionUpdate': 'agent_message_chunk',
              'messageId': 'message-spaces',
              'content': {'text': chunk},
            },
          },
        },
      );
    }

    expect(runtime.messages.single.text, 'POSIX Shell and Markdown');
  });

  test('projects ACP assistant image content into the shared image card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-assistant-image',
          'turnId': 'turn-assistant-image',
          'update': {
            'sessionUpdate': 'agent_message_chunk',
            'messageId': 'assistant-image',
            'content': {
              'type': 'image',
              'data': 'AAAA',
              'mimeType': 'image/png',
            },
          },
        },
      },
    );

    final imageCard = runtime.messages.singleWhere(
      (message) => message.cardData?['toolType'] == 'image',
    );
    expect(imageCard.cardData?['type'], 'agent_tool_summary');
    expect(imageCard.cardData?['imageDataUrl'], 'data:image/png;base64,AAAA');
    expect(imageCard.cardData?['toolName'], 'assistant_media');
  });

  test('projects ACP assistant image resources into the shared image card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-assistant-resource',
          'turnId': 'turn-assistant-resource',
          'update': {
            'sessionUpdate': 'agent_message_chunk',
            'messageId': 'assistant-resource',
            'content': {
              'type': 'resource',
              'resource': {
                'uri': 'workspace://result.png',
                'mimeType': 'image/jpeg',
                'blob': 'BBBB',
              },
            },
          },
        },
      },
    );

    final imageCard = runtime.messages.singleWhere(
      (message) => message.cardData?['toolType'] == 'image',
    );
    expect(imageCard.cardData?['imageDataUrl'], 'data:image/jpeg;base64,BBBB');
  });

  test('projects ACP assistant audio content into the shared audio card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-assistant-audio',
          'turnId': 'turn-assistant-audio',
          'update': {
            'sessionUpdate': 'agent_message_chunk',
            'messageId': 'assistant-audio',
            'content': {
              'type': 'audio',
              'data': 'AAAA',
              'mimeType': 'audio/mpeg',
            },
          },
        },
      },
    );

    final audioCard = runtime.messages.singleWhere(
      (message) => message.cardData?['toolType'] == 'audio',
    );
    expect(audioCard.cardData?['toolType'], 'audio');
    expect(audioCard.cardData?['audioDataUrl'], 'data:audio/mpeg;base64,AAAA');
  });

  test('keeps ACP advertised commands in shared runtime state', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-commands',
          'update': {
            'sessionUpdate': 'available_commands_update',
            'availableCommands': [
              {'name': 'review', 'description': 'Review the workspace'},
              {'name': '/review', 'description': 'duplicate'},
              {'name': 'ship', 'description': 'Ship the change'},
            ],
          },
        },
      },
    );

    expect(result.handled, isTrue);
    expect(runtime.availableAcpCommands, hasLength(2));
    expect(runtime.availableAcpCommands.last['name'], 'ship');
  });

  test('keeps dynamic ACP config options in shared runtime state', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-config',
          'update': {
            'sessionUpdate': 'config_option_update',
            'configOptions': [
              {
                'id': 'model',
                'name': 'Model',
                'type': 'select',
                'currentValue': 'model-a',
                'options': [
                  {'value': 'model-a', 'name': 'Model A'},
                ],
              },
              {'name': 'invalid-without-id'},
            ],
          },
        },
      },
    );

    expect(result.handled, isTrue);
    expect(runtime.acpConfigOptions, hasLength(1));
    expect(runtime.acpConfigOptions.single['id'], 'model');
  });

  test('projects ACP user history only when explicitly replaying', () {
    final live = reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-history',
          'update': {
            'sessionUpdate': 'user_message_chunk',
            'messageId': 'user-live',
            'content': {'type': 'text', 'text': 'must not duplicate'},
          },
        },
      },
    );
    expect(live.handled, isTrue);
    expect(runtime.messages, isEmpty);

    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-history',
          'update': {
            'sessionUpdate': 'user_message_chunk',
            'messageId': 'user-replay',
            'replay': true,
            'content': {'type': 'text', 'text': 'replayed'},
          },
        },
      },
    );
    expect(runtime.messages, hasLength(1));
    expect(runtime.messages.single.user, 1);
    expect(runtime.messages.single.text, 'replayed');
  });

  test('projects a live ACP user chunk only when the host query is absent', () {
    final liveEvent = <String, dynamic>{
      'method': 'session/update',
      'turnId': 'turn-live-user',
      'params': {
        'sessionId': 'session-live-user',
        'turnId': 'turn-live-user',
        'update': {
          'sessionUpdate': 'user_message_chunk',
          'messageId': 'dsh-user-message',
          'content': {'type': 'text', 'text': 'DSH 实时用户问题'},
        },
      },
    };

    reducer.reduce(runtime: runtime, event: liveEvent);

    expect(runtime.messages, hasLength(1));
    expect(runtime.messages.single.user, 1);
    expect(runtime.messages.single.text, 'DSH 实时用户问题');

    final hostRuntime = ChatConversationRuntimeState(
      conversationId: 43,
      mode: kChatRuntimeModeAgent,
    );
    addTearDown(hostRuntime.dispose);
    hostRuntime.currentDispatchTurnId = 'turn-live-user-ai';
    hostRuntime.messages.add(
      ChatMessageModel.userMessage('DSH 实时用户问题', id: 'turn-live-user-user'),
    );
    reducer.reduce(runtime: hostRuntime, event: liveEvent);

    expect(hostRuntime.messages, hasLength(1));
    expect(hostRuntime.messages.single.id, 'turn-live-user-user');
  });

  test('maps ACP elicitation requests into the shared request card', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'jsonrpc': '2.0',
          'id': 'elicitation-1',
          'method': 'elicitation/create',
          'params': {
            'sessionId': 'session-elicitation',
            'title': '需要确认',
            'description': '请提供项目名称',
          },
        },
      },
    );

    expect(result.handled, isTrue);
    expect(runtime.messages, hasLength(1));
    expect(runtime.messages.single.cardData?['type'], 'agent_request');
    expect(runtime.messages.single.cardData?['requestKind'], 'user_input');
    expect(runtime.messages.single.cardData?['requestId'], 'elicitation-1');
  });

  test('preserves request ownership when a later ACP update omits it', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'agentId': 'xiaowan-acp',
        'agentName': '小万',
        'message': {
          'id': 'elicitation-owner-1',
          'method': 'elicitation/create',
          'params': {'sessionId': 'session-owner-1', 'title': '需要确认'},
        },
      },
    );

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'id': 'elicitation-owner-1',
          'method': 'elicitation/create',
          'params': {'title': '需要确认（更新）'},
        },
      },
    );

    final card = runtime.messages.single.cardData!;
    expect(card['agentId'], 'xiaowan-acp');
    expect(card['agentName'], '小万');
    expect(card['sessionId'], 'session-owner-1');
  });

  test('uses the ACP elicitation schema instead of a generic input title', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'id': 'elicitation-schema-1',
          'method': 'elicitation/create',
          'params': {
            'message': 'The agent needs your input.',
            'requestedSchema': {
              'type': 'object',
              'properties': {
                'question_0': {
                  'type': 'string',
                  'title': '插件名称',
                  'description': '请输入要安装的插件',
                  'oneOf': [
                    {'const': 'android', 'title': 'Android 插件'},
                  ],
                },
              },
            },
          },
        },
      },
    );

    final card = runtime.messages.single.cardData!;
    expect(card['title'], '插件名称');
    expect(card['detail'], contains('请输入要安装的插件'));
    expect(card['detail'], contains('Android 插件'));
    expect(card['detail'], isNot(contains('requestedSchema')));
  });

  test('uses the schema question for legacy ACP user-input envelopes', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'id': 'user-input-schema-1',
          'method': 'item/tool/requestUserInput',
          'params': {
            'questions': [
              {
                'id': 'details',
                'label': 'The agent needs your input.',
                'description': 'The agent needs your input.',
              },
            ],
            'requested_schema': jsonEncode({
              'type': 'object',
              'properties': {
                'details': {
                  'type': 'string',
                  'title': '插件详情',
                  'description': '请提供插件名称和用途',
                },
              },
            }),
          },
        },
      },
    );

    final card = runtime.messages.single.cardData!;
    expect(card['title'], '插件详情');
    expect(card['detail'], '请提供插件名称和用途');
  });

  test('preserves unknown ACP extensions at the shared runtime boundary', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-extension',
          'update': {
            'sessionUpdate': 'provider_progress',
            'rawUpdate': {'text': 'still working', 'progress': 0.5},
          },
        },
      },
    );

    expect(result.handled, isTrue);
    expect(
      runtime.acpExtensionUpdates.single['sessionUpdate'],
      'provider_progress',
    );
    expect(runtime.acpExtensionUpdates.single['rawUpdate']['progress'], 0.5);
  });

  test('retains ACP extension requests and their response ids', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'method': '_omnibot/presentation',
        'id': 'extension-1',
        'acpExtensionRequest': true,
        'message': {
          'jsonrpc': '2.0',
          'id': 'extension-1',
          'method': '_omnibot/presentation',
          'params': {'card': 'usage'},
        },
      },
    );

    expect(result.handled, isTrue);
    expect(result.requestId, 'extension-1');
    expect(
      runtime.acpExtensionUpdates.single['method'],
      '_omnibot/presentation',
    );
    expect(runtime.acpExtensionUpdates.single['request'], isTrue);
    expect(runtime.acpExtensionUpdates.single['params']['card'], 'usage');
  });

  test('projects session-scoped ACP titles without a turn id', () {
    runtime.conversation = ConversationModel(
      id: 42,
      title: '旧标题',
      status: 0,
      messageCount: 0,
      createdAt: 1,
      updatedAt: 1,
    );

    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-title',
          'update': {
            'sessionUpdate': 'session_info_update',
            'title': 'ACP 新标题',
            'updatedAt': 1234,
          },
        },
      },
    );

    expect(result.handled, isTrue);
    expect(result.method, 'thread/name/updated');
    expect(runtime.conversation?.title, 'ACP 新标题');
  });

  test('ACP reasoning chunks preserve Markdown whitespace', () {
    const chunks = <String>['分析步骤', '\n\n', '- 第一项', '\n- 第二项'];

    for (final chunk in chunks) {
      reducer.reduce(
        runtime: runtime,
        event: {
          'method': 'session/update',
          'params': {
            'sessionId': 'session-reasoning',
            'turnId': 'turn-reasoning',
            'update': {
              'sessionUpdate': 'agent_thought_chunk',
              'messageId': 'reasoning-message',
              'content': {'type': 'text', 'text': chunk},
            },
          },
        },
      );
    }

    final thinking = runtime.messages.singleWhere(
      (message) => message.cardData?['type'] == 'deep_thinking',
    );
    expect(thinking.cardData?['thinkingContent'], chunks.join());
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

    reducer.reducePromptResponse(
      runtime: runtime,
      sessionId: null,
      turnId: 'turn-1',
      stopReason: 'end_turn',
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

    reducer.reducePromptResponse(
      runtime: runtime,
      sessionId: null,
      turnId: 'turn-1',
      stopReason: 'end_turn',
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

    reducer.reducePromptResponse(
      runtime: runtime,
      sessionId: 'session-1',
      turnId: 'turn-1',
      stopReason: 'end_turn',
    );

    expect(runtime.isAiResponding, isFalse);
    expect(runtime.currentDispatchTurnId, isNull);
    expect(runtime.activeAcpTurnId, isNull);
  });

  test(
    'prompt response closes its primed request without an admitted ACP id',
    () {
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

      reducer.reducePromptResponse(
        runtime: runtime,
        sessionId: null,
        turnId: 'official-turn-1',
        stopReason: 'end_turn',
      );

      final card = runtime.messages.single.cardData!;
      expect(runtime.isAiResponding, isFalse);
      expect(runtime.isDeepThinking, isFalse);
      expect(runtime.currentDispatchTurnId, isNull);
      expect(card['isLoading'], isFalse);
      expect(card['stage'], ThinkingStage.complete.value);
    },
  );

  test('owning prompt response without a wire id closes its local request', () {
    runtime
      ..isAiResponding = true
      ..currentDispatchTurnId = 'local-request'
      ..lastAgentTurnId = 'local-request';

    reducer.reducePromptResponse(
      runtime: runtime,
      sessionId: null,
      turnId: null,
      stopReason: 'end_turn',
    );

    expect(runtime.isAiResponding, isFalse);
    expect(runtime.currentDispatchTurnId, isNull);
    expect(runtime.acpCompatibilityDiagnostics, isEmpty);
  });

  test('id-less terminal never closes an already admitted official turn', () {
    runtime
      ..isAiResponding = true
      ..currentDispatchTurnId = 'local-request'
      ..lastAgentTurnId = 'local-request'
      ..activeAcpTurnId = 'official-turn-1';

    reducer.reduce(
      runtime: runtime,
      event: const {'method': 'turn/completed', 'params': <String, dynamic>{}},
    );

    expect(runtime.isAiResponding, isTrue);
    expect(runtime.activeAcpTurnId, 'official-turn-1');
    expect(runtime.acpCompatibilityDiagnostics, isEmpty);
  });

  test(
    'id-less turn plan updates are quarantined instead of using the active run',
    () {
      runtime
        ..isAiResponding = true
        ..currentDispatchTurnId = 'new-turn'
        ..activeAcpTurnId = 'new-turn';

      reducer.reduce(
        runtime: runtime,
        event: const {
          'method': 'turn/plan/updated',
          'params': {'plan': '- [pending] stale plan'},
        },
      );

      expect(runtime.messages, isEmpty);
      expect(runtime.acpCompatibilityDiagnostics, hasLength(1));
      expect(
        runtime.acpCompatibilityDiagnostics.single['method'],
        'turn/plan/updated',
      );
    },
  );

  test('id-less raw response completion is quarantined', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: const {
        'method': 'rawResponseItem/completed',
        'params': {
          'item': {'type': 'function_call', 'name': 'read_file'},
        },
      },
    );

    expect(result.handled, isTrue);
    expect(runtime.messages, isEmpty);
    expect(
      runtime.acpCompatibilityDiagnostics.single['reason'],
      'turn_id_missing',
    );
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

    reducer.reducePromptResponse(
      runtime: runtime,
      sessionId: null,
      turnId: 'turn-1',
      stopReason: 'end_turn',
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

    reducer.reducePromptResponse(
      runtime: runtime,
      sessionId: null,
      turnId: 'turn-1',
      stopReason: 'end_turn',
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
    expect(runtime.currentDispatchTurnId, 'request-1-ai');

    reducer.reducePromptResponse(
      runtime: runtime,
      sessionId: null,
      turnId: 'acp-turn-1',
      stopReason: 'end_turn',
    );

    expect(runtime.activeAcpTurnId, isNull);
    expect(runtime.currentDispatchTurnId, isNull);
    expect(runtime.isAiResponding, isFalse);
  });

  test('keeps local run identity stable after ACP turn admission', () {
    runtime
      ..currentDispatchTurnId = 'local-run-1'
      ..activeRunId = 'local-run-1'
      ..isAiResponding = true;

    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'turn/started',
        'params': {'sessionId': 'session-1', 'turnId': 'official-turn-1'},
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'item/agentMessage/delta',
        'params': {
          'sessionId': 'session-1',
          'turnId': 'official-turn-1',
          'itemId': 'item-1',
          'delta': 'hello',
        },
      },
    );

    final message = runtime.messages.single;
    expect(runtime.activeRunId, 'local-run-1');
    expect(runtime.activeAcpTurnId, 'official-turn-1');
    expect(message.runId, 'local-run-1');
    expect(message.turnId, 'official-turn-1');
    expect(message.streamMeta?['runId'], 'local-run-1');
  });

  test(
    'ACP admits a turn from the first session update when no turn started exists',
    () {
      runtime
        ..currentDispatchTurnId = 'request-1-ai'
        ..lastAgentTurnId = 'request-1-ai'
        ..isAiResponding = true;

      reducer.reduce(
        runtime: runtime,
        event: {
          'method': 'session/update',
          'allowImplicitTurnAdmission': true,
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

      reducer.reducePromptResponse(
        runtime: runtime,
        sessionId: 'session-1',
        turnId: 'acp-turn-1',
        stopReason: 'end_turn',
      );

      expect(runtime.isAiResponding, isFalse);
      expect(runtime.currentDispatchTurnId, isNull);
      expect(runtime.activeAcpTurnId, isNull);
    },
  );

  test('does not admit an untrusted first event as the active turn', () {
    runtime
      ..currentDispatchTurnId = 'request-1-ai'
      ..lastAgentTurnId = 'request-1-ai'
      ..isAiResponding = true;

    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-1',
          'turnId': 'late-old-turn',
          'update': {
            'sessionUpdate': 'agent_message_chunk',
            'messageId': 'message-old',
            'content': {'text': '迟到的旧输出'},
          },
        },
      },
    );

    expect(result.handled, isTrue);
    expect(runtime.activeAcpTurnId, isNull);
    expect(runtime.messages, isEmpty);
    expect(runtime.currentDispatchTurnId, 'request-1-ai');
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

  test('turn started without an id does not invent an ACP turn identity', () {
    runtime.activeRunId = 'local-run-without-wire-id';
    runtime.currentDispatchTurnId = 'local-run-without-wire-id';
    runtime.isAiResponding = true;

    final result = reducer.reduce(
      runtime: runtime,
      event: {'method': 'turn/started', 'params': <String, dynamic>{}},
    );

    expect(result.handled, isTrue);
    expect(runtime.activeAcpTurnId, isNull);
    expect(runtime.activeRunId, 'local-run-without-wire-id');
    expect(runtime.currentDispatchTurnId, 'local-run-without-wire-id');
    expect(runtime.isAiResponding, isTrue);
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

  test(
    'uses the host prompt reservation for an ACP update without turn id',
    () {
      runtime.currentDispatchTurnId = 'local-reserved-turn';
      runtime.lastAgentTurnId = 'local-reserved-turn';
      runtime.isAiResponding = true;
      final result = reducer.reduce(
        runtime: runtime,
        event: {
          'method': 'session/update',
          'allowImplicitTurnAdmission': true,
          'params': {
            'sessionId': 'session-1',
            'update': {
              'sessionUpdate': 'agent_message_chunk',
              'messageId': 'message-1',
              'content': {'type': 'text', 'text': '通过宿主 reservation 归属'},
            },
          },
        },
      );

      expect(result.handled, isTrue);
      expect(runtime.messages.single.text, '通过宿主 reservation 归属');
      expect(runtime.currentDispatchTurnId, 'local-reserved-turn');
      expect(runtime.activeAcpTurnId, isNull);
    },
  );

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
    reducer.reducePromptResponse(
      runtime: runtime,
      sessionId: null,
      turnId: 'turn-1',
      stopReason: 'end_turn',
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

  test('projects structured ACP reasoning into the shared thinking card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'turnId': 'turn-structured-thinking',
        'params': {
          'sessionId': 'session-structured-thinking',
          'update': {
            'sessionUpdate': 'agent_thought_chunk',
            'messageId': 'thought-structured',
            'content': {'type': 'text', 'text': ''},
            '_meta': {
              'cn.com.omnimind.agent': {
                'reasoning': {
                  'taskDescription': '检查 ACP 投影是否完整',
                  'subTasks': ['保留工具结果', '渲染统一卡片'],
                  'preparation': '先确认会话归属',
                  'taskTitle': '统一展示层检查',
                  'memoryActions': ['保留旧卡片字段'],
                  'stage': 'planning',
                },
              },
            },
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['type'], 'deep_thinking');
    expect(cardData['thinkingContent'], contains('检查 ACP 投影是否完整'));
    expect(cardData['thinkingContent'], contains('统一展示层检查'));
    expect(cardData['thinkingContent'], contains('保留工具结果'));
    expect(cardData['thinkingContent'], contains('先确认会话归属'));
    expect(cardData['thinkingContent'], contains('保留旧卡片字段'));
    expect(cardData['taskTitle'], '统一展示层检查');
    expect(cardData['subTasks'], ['保留工具结果', '渲染统一卡片']);
    expect(cardData['memoryActions'], ['保留旧卡片字段']);
  });

  test('projects plain ACP reasoning metadata from another namespace', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'turnId': 'turn-generic-reasoning',
        'params': {
          'sessionId': 'session-generic-reasoning',
          'update': {
            'sessionUpdate': 'agent_thought_chunk',
            'messageId': 'thought-generic',
            'content': {'type': 'text', 'text': ''},
            '_meta': {
              'com.example.acp': {
                'thinking': {
                  'text': '来自 ACP Harness 的推理摘要',
                  'stage': 'planning',
                  'summary': '已完成计划整理',
                },
              },
            },
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['thinkingContent'], '来自 ACP Harness 的推理摘要');
    expect(cardData['stage'], 1);
    expect(cardData['reasoningSummary'], '已完成计划整理');
  });

  test('routes ACP metadata media and artifacts through shared cards', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'turnId': 'turn-presentation-resources',
        'params': {
          'sessionId': 'session-presentation-resources',
          'update': {
            'sessionUpdate': 'agent_message_chunk',
            'messageId': 'message-presentation-resources',
            'content': {'type': 'text', 'text': ''},
            '_meta': {
              'com.example.acp': {
                'media': [
                  {
                    'imageDataUrl': 'data:image/png;base64,ACP',
                    'title': 'ACP image',
                  },
                ],
                'artifacts': [
                  {
                    'uri': 'workspace://acp-result.md',
                    'title': 'ACP result',
                    'mimeType': 'text/markdown',
                  },
                ],
              },
            },
          },
        },
      },
    );

    expect(
      runtime.messages.any(
        (message) =>
            message.cardData?['toolType'] == 'image' &&
            message.cardData?['imageDataUrl'] == 'data:image/png;base64,ACP',
      ),
      isTrue,
    );
    expect(
      runtime.messages.any(
        (message) =>
            message.cardData?['type'] == 'artifact_card' &&
            message.cardData?['artifact']?['uri'] ==
                'workspace://acp-result.md',
      ),
      isTrue,
    );
  });

  test('does not render an empty ACP thought start as a blank card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'turnId': 'turn-thinking-start',
        'params': {
          'sessionId': 'session-thinking-start',
          'update': {
            'sessionUpdate': 'agent_thought_chunk',
            'messageId': 'thought-start',
            'content': {'type': 'text', 'text': ''},
            '_meta': {
              'cn.com.omnimind.agent': {
                'reasoning': {'stage': 'thinking'},
              },
            },
          },
        },
      },
    );

    expect(runtime.messages, isEmpty);
  });

  test('retains reasoning metadata from an empty ACP start', () {
    const base = <String, dynamic>{
      'method': 'session/update',
      'turnId': 'turn-reasoning-metadata',
      'params': {
        'sessionId': 'session-reasoning-metadata',
        'update': {
          'sessionUpdate': 'agent_thought_chunk',
          'messageId': 'thought-metadata',
          'content': {'type': 'text', 'text': ''},
          '_meta': {
            'cn.com.omnimind.agent': {
              'reasoning': {
                'taskTitle': '检查工作区',
                'subTasks': ['读取状态'],
              },
            },
          },
        },
      },
    };
    reducer.reduce(runtime: runtime, event: base);
    expect(runtime.messages.single.cardData?['taskTitle'], '检查工作区');
    expect(runtime.messages.single.cardData?['subTasks'], ['读取状态']);

    reducer.reduce(
      runtime: runtime,
      event: {
        ...base,
        'params': {
          ...(base['params'] as Map<String, dynamic>),
          'update': {
            ...((base['params'] as Map<String, dynamic>)['update']
                as Map<String, dynamic>),
            'content': {'type': 'text', 'text': '开始检查'},
            '_meta': null,
          },
        },
      },
    );

    final card = runtime.messages.single.cardData!;
    expect(card['thinkingContent'], contains('开始检查'));
    expect(card['taskTitle'], '检查工作区');
    expect(card['subTasks'], ['读取状态']);
  });

  test('keeps ACP turn usage that arrives before the assistant text', () {
    const base = <String, dynamic>{
      'method': 'session/update',
      'turnId': 'turn-usage-before-text',
      'params': {
        'sessionId': 'session-usage-before-text',
        'update': {
          'sessionUpdate': 'agent_message_chunk',
          'messageId': 'message-usage-before-text',
          'content': {'type': 'text', 'text': ''},
          '_meta': {
            'cn.com.omnimind.agent': {
              'usage': {
                'turnUsage': {'ctx': 128, 'in': 100, 'out': 28, 'cache': 12},
              },
            },
          },
        },
      },
    };

    reducer.reduce(runtime: runtime, event: base);
    expect(runtime.messages, isEmpty);

    reducer.reduce(
      runtime: runtime,
      event: {
        ...base,
        'params': {
          ...(base['params'] as Map<String, dynamic>),
          'update': {
            ...((base['params'] as Map<String, dynamic>)['update']
                as Map<String, dynamic>),
            'content': {'type': 'text', 'text': '正文'},
            '_meta': null,
          },
        },
      },
    );

    expect(runtime.messages.single.text, '正文');
    expect(runtime.messages.single.turnUsage, {
      'ctx': 128,
      'in': 100,
      'out': 28,
      'cache': 12,
    });
  });

  test(
    'projects standard ACP usage updates into conversation context usage',
    () {
      runtime.conversation = ConversationModel(
        id: 42,
        title: 'ACP usage',
        status: 0,
        messageCount: 0,
        createdAt: 1,
        updatedAt: 1,
      );

      final result = reducer.reduce(
        runtime: runtime,
        event: {
          'method': 'session/update',
          'params': {
            'sessionId': 'session-usage',
            'update': {
              'sessionUpdate': 'usage_update',
              'used': 12345,
              'size': 128000,
            },
          },
        },
      );

      expect(result.handled, isTrue);
      expect(runtime.conversation?.latestPromptTokens, 12345);
      expect(runtime.conversation?.promptTokenThreshold, 128000);
      expect(runtime.conversation?.latestPromptTokensUpdatedAt, greaterThan(0));
    },
  );

  test('projects ACP turn usage into the shared assistant footer', () {
    const base = <String, dynamic>{
      'method': 'session/update',
      'turnId': 'turn-usage-footer',
      'params': {
        'sessionId': 'session-usage-footer',
        'update': {
          'sessionUpdate': 'agent_message_chunk',
          'messageId': 'message-usage-footer',
        },
      },
    };

    reducer.reduce(
      runtime: runtime,
      event: {
        ...base,
        'params': {
          ...(base['params'] as Map<String, dynamic>),
          'update': {
            ...((base['params'] as Map<String, dynamic>)['update']
                as Map<String, dynamic>),
            'content': {'type': 'text', 'text': '带用量的统一正文'},
          },
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        ...base,
        'params': {
          ...(base['params'] as Map<String, dynamic>),
          'update': {
            ...((base['params'] as Map<String, dynamic>)['update']
                as Map<String, dynamic>),
            'content': {'type': 'text', 'text': ''},
            '_meta': {
              'cn.com.omnimind.agent': {
                'usage': {
                  'latestPromptTokens': 100,
                  'promptTokenThreshold': 128000,
                  'turnUsage': {'ctx': 100, 'in': 100, 'out': 20, 'cache': 10},
                },
              },
            },
          },
        },
      },
    );

    final message = runtime.messages.single;
    expect(message.text, '带用量的统一正文');
    expect(message.turnUsage, {'ctx': 100, 'in': 100, 'out': 20, 'cache': 10});
  });

  test('ACP v2 plan update and removal share the plan card route', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'turnId': 'turn-plan-v2',
        'params': {
          'sessionId': 'session-plan-v2',
          'update': {
            'sessionUpdate': 'plan_update',
            'plan': {
              'type': 'markdown',
              'id': 'plan-1',
              'content': '# Plan\n\n1. inspect',
            },
          },
        },
      },
    );

    expect(runtime.messages, hasLength(1));
    expect(runtime.messages.single.cardData?['toolType'], 'plan');
    expect(runtime.messages.single.cardData?['planId'], 'plan-1');
    expect(runtime.messages.single.cardData?['summary'], contains('inspect'));
    final planCardId = runtime.messages.single.id;

    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'turnId': 'turn-plan-v2',
        'params': {
          'sessionId': 'session-plan-v2',
          'update': {
            'sessionUpdate': 'plan_update',
            'plan': {
              'type': 'markdown',
              'id': 'plan-1',
              'content': '# Plan\n\n1. inspect\n2. implement',
            },
          },
        },
      },
    );

    expect(runtime.messages, hasLength(1));
    expect(runtime.messages.single.id, planCardId);
    expect(runtime.messages.single.cardData?['summary'], contains('implement'));

    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'turnId': 'turn-plan-v2',
        'params': {
          'sessionId': 'session-plan-v2',
          'update': {'sessionUpdate': 'plan_removed', 'id': 'plan-1'},
        },
      },
    );

    expect(runtime.messages, isEmpty);
  });

  test('standard ACP image tool content reaches the shared image card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'turnId': 'turn-image-content',
        'params': {
          'sessionId': 'session-image-content',
          'update': {
            'sessionUpdate': 'tool_call_update',
            'toolCallId': 'image-1',
            'kind': 'other',
            'title': 'Generated image',
            'status': 'completed',
            'content': [
              {
                'type': 'content',
                'content': {
                  'type': 'image',
                  'data': 'AAAA',
                  'mimeType': 'image/png',
                },
              },
            ],
            'rawOutput': {'toolType': 'context'},
          },
        },
      },
    );

    final card = runtime.messages.single.cardData!;
    expect(card['toolType'], 'image');
    expect(card['imageDataUrl'], 'data:image/png;base64,AAAA');
    expect(card['resultPreviewJson'], contains('image/png'));
  });

  test('official ACP read kind uses the shared workspace tool route', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'turnId': 'turn-read-kind',
        'params': {
          'sessionId': 'session-read-kind',
          'update': {
            'sessionUpdate': 'tool_call',
            'toolCallId': 'read-1',
            'kind': 'read',
            'title': '读取文件',
            'status': 'in_progress',
            'rawInput': {'path': '/workspace/README.md'},
          },
        },
      },
    );

    final card = runtime.messages.single.cardData!;
    expect(card['toolType'], 'workspace');
    expect(card['toolCallId'], 'read-1');
  });

  test('official ACP ToolKind values share the same card route mapping', () {
    const cases = <(String, String)>[
      ('read', 'workspace'),
      ('edit', 'file'),
      ('delete', 'file'),
      ('move', 'file'),
      ('search', 'search'),
      ('execute', 'terminal'),
      ('fetch', 'browser'),
      ('think', 'plan'),
    ];

    for (final (kind, expectedToolType) in cases) {
      final callId = 'kind-$kind';
      reducer.reduce(
        runtime: runtime,
        event: {
          'method': 'session/update',
          'turnId': 'turn-official-kinds',
          'params': {
            'sessionId': 'session-official-kinds',
            'update': {
              'sessionUpdate': 'tool_call',
              'toolCallId': callId,
              'kind': kind,
              'title': 'ACP $kind',
              'status': 'in_progress',
            },
          },
        },
      );
    }

    for (final (kind, expectedToolType) in cases) {
      final card = runtime.messages.singleWhere(
        (message) => message.cardData?['toolCallId'] == 'kind-$kind',
      );
      expect(card.cardData?['toolType'], expectedToolType);
    }
  });

  test('sparse ACP tool updates keep the initial official kind and title', () {
    const base = <String, dynamic>{
      'method': 'session/update',
      'turnId': 'turn-sparse-tool-update',
      'params': {
        'sessionId': 'session-sparse-tool-update',
        'update': {
          'sessionUpdate': 'tool_call',
          'toolCallId': 'sparse-read',
          'kind': 'read',
          'title': '读取 README',
          'status': 'in_progress',
          'rawInput': {'path': '/workspace/README.md'},
        },
      },
    };
    reducer.reduce(runtime: runtime, event: base);
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'turnId': 'turn-sparse-tool-update',
        'params': {
          'sessionId': 'session-sparse-tool-update',
          'update': {
            'sessionUpdate': 'tool_call_update',
            'toolCallId': 'sparse-read',
            'status': 'completed',
          },
        },
      },
    );

    final card = runtime.messages.single.cardData!;
    expect(card['toolType'], 'workspace');
    expect(card['toolTitle'], '读取 README');
    expect(card['status'], 'success');
  });

  test('official ACP tool media content uses the shared media card route', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'turnId': 'turn-tool-media-content',
        'params': {
          'sessionId': 'session-tool-media-content',
          'update': {
            'sessionUpdate': 'tool_call',
            'toolCallId': 'media-1',
            'kind': 'other',
            'title': '生成预览',
            'status': 'completed',
            'content': [
              {
                'type': 'content',
                'content': {
                  'type': 'resource',
                  'resource': {
                    'uri': 'workspace://preview.png',
                    'blob': 'CCCC',
                    'mimeType': 'image/png',
                  },
                },
              },
            ],
          },
        },
      },
    );

    final card = runtime.messages.single.cardData!;
    expect(card['toolType'], 'image');
    expect(card['imageDataUrl'], 'data:image/png;base64,CCCC');
  });

  test('projects ACP tool resource links into the shared artifact card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'turnId': 'turn-tool-resource',
        'params': {
          'sessionId': 'session-tool-resource',
          'update': {
            'sessionUpdate': 'tool_call_update',
            'toolCallId': 'tool-resource-1',
            'kind': 'other',
            'title': '生成文件',
            'status': 'completed',
            'content': [
              {
                'type': 'content',
                'content': {
                  'type': 'resource_link',
                  'name': 'result.md',
                  'uri': 'workspace://result.md',
                  'mimeType': 'text/markdown',
                },
              },
            ],
          },
        },
      },
    );

    final artifactCard = runtime.messages.singleWhere(
      (message) => message.cardData?['type'] == 'artifact_card',
    );
    final artifact = artifactCard.cardData?['artifact'] as Map<String, dynamic>;
    expect(artifact['uri'], 'workspace://result.md');
    expect(artifact['fileName'], 'result.md');
    expect(artifact['mimeType'], 'text/markdown');
  });

  test('standard ACP terminal content keeps the shared terminal session', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'turnId': 'turn-terminal-content',
        'params': {
          'sessionId': 'session-terminal-content',
          'update': {
            'sessionUpdate': 'tool_call_update',
            'toolCallId': 'terminal-content-1',
            'kind': 'execute',
            'title': 'Run tests',
            'status': 'completed',
            'content': [
              {'type': 'terminal', 'terminalId': 'shell-1'},
            ],
          },
        },
      },
    );

    final card = runtime.messages.single.cardData!;
    expect(card['toolType'], 'terminal');
    expect(card['terminalSessionId'], 'shell-1');
    expect(card['resultPreviewJson'], contains('terminalId'));
  });

  test('projects ACP retry state into the next shared assistant message', () {
    const base = <String, dynamic>{
      'method': 'session/update',
      'turnId': 'turn-retry',
      'params': {
        'sessionId': 'session-retry',
        'update': {
          'sessionUpdate': 'agent_message_chunk',
          'messageId': 'message-retry',
          'content': {'type': 'text', 'text': ''},
          '_meta': {
            'cn.com.omnimind.agent': {
              'retry': {
                'count': 1,
                'maxRetries': 3,
                'delayMs': 1000,
                'message': '请求失败，正在重试',
                'reason': 'timeout',
              },
            },
          },
        },
      },
    };
    reducer.reduce(runtime: runtime, event: base);

    expect(runtime.messages, isEmpty);
    expect(runtime.isAiResponding, isFalse);
    expect(runtime.currentDispatchTurnId, isNull);

    reducer.reduce(
      runtime: runtime,
      event: {
        ...base,
        'params': {
          ...(base['params'] as Map<String, dynamic>),
          'update': {
            'sessionUpdate': 'agent_message_chunk',
            'messageId': 'message-retry',
            'content': {'type': 'text', 'text': '恢复后的统一正文'},
          },
        },
      },
    );

    final message = runtime.messages.single;
    expect(message.text, '恢复后的统一正文');
    expect(message.content?['agentRetrying'], isTrue);
    expect(message.content?['agentRetryCount'], 1);
    expect(message.content?['agentMaxRetries'], 3);

    reducer.reducePromptResponse(
      runtime: runtime,
      sessionId: null,
      turnId: 'turn-retry',
      stopReason: 'end_turn',
    );
    expect(runtime.messages.single.content?['agentRetrying'], isNull);
  });

  test('projects ACP recovery state into the existing error presentation', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'turnId': 'turn-recovery',
        'params': {
          'sessionId': 'session-recovery',
          'update': {
            'sessionUpdate': 'agent_message_chunk',
            'messageId': 'message-recovery',
            'content': {'type': 'text', 'text': '网络连接中断'},
            '_meta': {
              'cn.com.omnimind.agent': {
                'recovery': {
                  'error': '网络连接中断',
                  'retryable': true,
                  'continueable': false,
                },
              },
            },
          },
        },
      },
    );

    final message = runtime.messages.single;
    expect(message.isError, isTrue);
    expect(message.content?['agentErrorText'], '网络连接中断');
    expect(message.content?['agentRetryable'], isTrue);
    expect(message.content?['agentContinueable'], isNull);
  });

  test('does not project partial ACP recovery as a continuation action', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'turnId': 'turn-partial-recovery',
        'params': {
          'sessionId': 'session-partial-recovery',
          'update': {
            'sessionUpdate': 'agent_message_chunk',
            'messageId': 'message-partial-recovery',
            'content': {'type': 'text', 'text': '半截答案'},
            '_meta': {
              'cn.com.omnimind.agent': {
                'recovery': {
                  'error': '连接中断',
                  'retryable': true,
                  'continueable': true,
                  'continueResumeMode': 'approximate',
                  'persistAsError': false,
                },
              },
            },
          },
        },
      },
    );

    final message = runtime.messages.single;
    expect(message.text, '半截答案');
    expect(message.isError, isFalse);
    expect(message.content?['agentContinueable'], isNull);
    expect(message.content?['agentContinueResumeMode'], isNull);
  });

  test(
    'buffers ACP recovery and clarification until assistant text exists',
    () {
      const metadataOnly = <String, dynamic>{
        'method': 'item/agentMessage/delta',
        'params': {
          'turnId': 'turn-pending-presentation',
          'entryId': 'message-pending-presentation',
          'delta': '',
          'acpPresentation': {
            'recovery': {
              'error': '需要重新连接',
              'retryable': true,
              'continueable': false,
            },
            'clarification': {
              'question': '是否继续？',
              'missingFields': ['arguments.confirmed'],
            },
          },
        },
      };

      reducer.reduce(runtime: runtime, event: metadataOnly);
      expect(runtime.messages, isEmpty);

      reducer.reduce(
        runtime: runtime,
        event: {
          ...metadataOnly,
          'params': {
            ...(metadataOnly['params'] as Map<String, dynamic>),
            'delta': '恢复后的正文',
            'acpPresentation': null,
          },
        },
      );

      final message = runtime.messages.single;
      expect(message.text, '恢复后的正文');
      expect(message.isError, isTrue);
      expect(message.content?['agentErrorText'], '需要重新连接');
      expect(message.content?['agentClarificationRequired'], isTrue);
      expect(message.content?['agentClarificationQuestion'], '是否继续？');
      expect(message.content?['agentClarificationMissingFields'], [
        'arguments.confirmed',
      ]);
    },
  );

  test(
    'preserves ACP clarification fields on the existing assistant reply',
    () {
      reducer.reduce(
        runtime: runtime,
        event: {
          'method': 'session/update',
          'turnId': 'turn-clarification',
          'params': {
            'sessionId': 'session-clarification',
            'update': {
              'sessionUpdate': 'agent_message_chunk',
              'messageId': 'message-clarification',
              'content': {'type': 'text', 'text': '是否继续执行？'},
              '_meta': {
                'cn.com.omnimind.agent': {
                  'clarification': {
                    'question': '是否继续执行？',
                    'missingFields': ['arguments.confirmed'],
                  },
                },
              },
            },
          },
        },
      );

      final message = runtime.messages.single;
      expect(message.text, '是否继续执行？');
      expect(message.content?['agentClarificationRequired'], isTrue);
      expect(message.content?['agentClarificationQuestion'], '是否继续执行？');
      expect(message.content?['agentClarificationMissingFields'], [
        'arguments.confirmed',
      ]);
    },
  );

  test('ignores private automatic compaction presentation metadata', () {
    const base = <String, dynamic>{
      'method': 'session/update',
      'turnId': 'turn-compaction',
      'params': {
        'sessionId': 'session-compaction',
        'update': {
          'sessionUpdate': 'agent_thought_chunk',
          'messageId': 'thought-compaction',
          'content': {'type': 'text', 'text': ''},
          '_meta': {
            'cn.com.omnimind.agent': {
              'compaction': {
                'status': 'compressing',
                'trigger': 'auto',
                'latestPromptTokens': 126000,
                'promptTokenThreshold': 128000,
              },
            },
          },
        },
      },
    };
    reducer.reduce(runtime: runtime, event: base);

    expect(runtime.messages, isEmpty);
    expect(runtime.isContextCompressing, isFalse);

    reducer.reduce(
      runtime: runtime,
      event: {
        ...base,
        'params': {
          ...(base['params'] as Map<String, dynamic>),
          'update': {
            'sessionUpdate': 'agent_thought_chunk',
            'messageId': 'thought-compaction',
            'content': {'type': 'text', 'text': ''},
            '_meta': {
              'cn.com.omnimind.agent': {
                'compaction': {'status': 'completed'},
              },
            },
          },
        },
      },
    );

    expect(runtime.messages, isEmpty);
    expect(runtime.isContextCompressing, isFalse);
  });

  test('appends multiple ACP reasoning rounds to one thinking card', () {
    const event = {
      'method': 'item/reasoning/delta',
      'params': {
        'turnId': 'turn-multi-round',
        'itemId': 'thought-for-one-prompt',
      },
    };

    reducer.reduce(
      runtime: runtime,
      event: {
        ...event,
        'params': {
          ...event['params'] as Map<String, dynamic>,
          'delta': '第一轮思考',
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        ...event,
        'params': {
          ...event['params'] as Map<String, dynamic>,
          'delta': '第二轮思考',
        },
      },
    );

    final thinkingMessages = runtime.messages
        .where((message) => message.cardData?['type'] == 'deep_thinking')
        .toList();
    expect(thinkingMessages, hasLength(1));
    expect(thinkingMessages.single.cardData?['thinkingContent'], '第一轮思考第二轮思考');
  });

  test(
    'starts a new ACP reasoning card when an explicit retry segment arrives',
    () {
      Map<String, dynamic> event({
        required String itemId,
        required String text,
        required int segmentIndex,
      }) {
        return <String, dynamic>{
          'method': 'item/reasoning/delta',
          'params': {
            'turnId': 'turn-retry-segment',
            'itemId': itemId,
            'delta': text,
            'acpPresentation': {
              'reasoning': {'segmentIndex': segmentIndex},
            },
          },
        };
      }

      reducer.reduce(
        runtime: runtime,
        event: event(itemId: 'reason-failed', text: '失败请求的思考', segmentIndex: 0),
      );
      reducer.reduce(
        runtime: runtime,
        event: event(itemId: 'reason-retry', text: '成功重试的思考', segmentIndex: 1),
      );

      final thinkingCards = runtime.messages
          .where((message) => message.cardData?['type'] == 'deep_thinking')
          .toList(growable: false);
      expect(thinkingCards, hasLength(2));
      expect(
        thinkingCards.map((message) => message.cardData?['thinkingContent']),
        containsAll(<String>['失败请求的思考', '成功重试的思考']),
      );
      expect(
        thinkingCards
            .firstWhere(
              (message) => message.cardData?['thinkingContent'] == '失败请求的思考',
            )
            .cardData?['isLoading'],
        isFalse,
      );
    },
  );

  test('keeps reasoning segments interleaved around ACP tool calls', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'item/reasoning/textDelta',
        'params': {
          'turnId': 'turn-interleaved',
          'itemId': 'reason-before-tool',
          'delta': '先检查工作区',
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'item/started',
        'params': {
          'turnId': 'turn-interleaved',
          'item': {
            'id': 'tool-1',
            'type': 'commandExecution',
            'command': 'pwd',
            'status': 'in_progress',
          },
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'item/reasoning/textDelta',
        'params': {
          'turnId': 'turn-interleaved',
          'itemId': 'reason-after-tool',
          'delta': '根据工具结果继续判断',
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'item/started',
        'params': {
          'turnId': 'turn-interleaved',
          'item': {
            'id': 'tool-2',
            'type': 'commandExecution',
            'command': 'git status',
            'status': 'in_progress',
          },
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'item/reasoning/textDelta',
        'params': {
          'turnId': 'turn-interleaved',
          'itemId': 'reason-after-second-tool',
          'delta': '确认第二个工具结果',
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'item/agentMessage/delta',
        'params': {
          'turnId': 'turn-interleaved',
          'itemId': 'answer-1',
          'delta': '最终答案',
        },
      },
    );

    final group = buildAgentRunTimelineEntries(runtime.messages).single.group!;
    expect(
      group.allMessagesOldestFirst.map(
        (message) => message.cardData?['type'] ?? 'assistant_text',
      ),
      <String>[
        'deep_thinking',
        'agent_tool_summary',
        'deep_thinking',
        'agent_tool_summary',
        'deep_thinking',
        'assistant_text',
      ],
    );
    final thinkingCards = group.allMessagesOldestFirst
        .where((message) => message.cardData?['type'] == 'deep_thinking')
        .toList(growable: false);
    expect(
      thinkingCards.map((message) => message.cardData?['thinkingContent']),
      <String>['先检查工作区', '根据工具结果继续判断', '确认第二个工具结果'],
    );
    expect(thinkingCards.map((message) => message.id), <String>[
      'reason-before-tool-agent-thinking',
      'reason-after-tool-agent-thinking',
      'reason-after-second-tool-agent-thinking',
    ]);
    for (final card in thinkingCards) {
      expect(card.cardData?['isLoading'], isFalse);
      expect(card.cardData?['startTime'], isA<int>());
      expect(card.cardData?['endTime'], isA<int>());
    }
  });

  test('segments ACP reasoning without message ids across tool calls', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'item/reasoning/textDelta',
        'params': {'turnId': 'turn-without-message-id', 'delta': '工具前'},
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'item/started',
        'params': {
          'turnId': 'turn-without-message-id',
          'item': {
            'id': 'tool-without-message-id',
            'type': 'commandExecution',
            'command': 'pwd',
            'status': 'in_progress',
          },
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'item/reasoning/textDelta',
        'params': {'turnId': 'turn-without-message-id', 'delta': '工具后'},
      },
    );

    final thinkingCards = runtime.messages.reversed
        .where((message) => message.cardData?['type'] == 'deep_thinking')
        .toList(growable: false);
    expect(
      thinkingCards.map((message) => message.cardData?['thinkingContent']),
      <String>['工具前', '工具后'],
    );
    expect(thinkingCards.map((message) => message.id), <String>[
      'turn-without-message-id-agent-thinking',
      'turn-without-message-id-agent-thinking-segment-2',
    ]);
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

  test('legacy state_change cannot start a prompt without a request', () {
    final running = reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-lifecycle',
          'update': {'sessionUpdate': 'state_change', 'state': 'running'},
        },
      },
    );

    expect(running.handled, isTrue);
    expect(running.compatibilityWarning, isNull);
    expect(runtime.isAiResponding, isFalse);

    final idle = reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-lifecycle',
          'update': {
            'sessionUpdate': 'state_change',
            'state': 'idle',
            'stop_reason': 'end_turn',
          },
        },
      },
    );

    expect(idle.handled, isTrue);
    expect(runtime.isAiResponding, isFalse);
  });

  test('ignores the pre-release ACP state_update spelling', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-lifecycle-draft',
          'update': {'sessionUpdate': 'state_update', 'state': 'running'},
        },
      },
    );

    expect(result.handled, isTrue);
    expect(result.compatibilityWarning, isNull);
    expect(runtime.isAiResponding, isFalse);
  });

  test('routes a session-only event to its background conversation', () {
    final coordinator = ChatConversationRuntimeCoordinator.instance;
    final first = coordinator.ensureRuntime(
      conversationId: 8101,
      mode: kChatRuntimeModeAgent,
    );
    final second = coordinator.ensureRuntime(
      conversationId: 8102,
      mode: kChatRuntimeModeAgent,
    );
    first.acceptsAcpEvent(
      sessionId: 'session-background-1',
      allowSessionAdmission: true,
    );
    second.acceptsAcpEvent(
      sessionId: 'session-background-2',
      allowSessionAdmission: true,
    );

    expect(
      coordinator.conversationIdForAcpEvent(sessionId: 'session-background-2'),
      8102,
    );

    coordinator.discardConversationRuntime(
      conversationId: 8101,
      mode: kChatRuntimeModeAgent,
    );
    coordinator.discardConversationRuntime(
      conversationId: 8102,
      mode: kChatRuntimeModeAgent,
    );
  });

  test('interrupts every running tool in a parallel tool batch', () {
    final coordinator = ChatConversationRuntimeCoordinator.instance;
    final parallelRuntime = coordinator.ensureRuntime(
      conversationId: 8103,
      mode: kChatRuntimeModeAgent,
    )..activeRunId = 'run-parallel-tools';
    parallelRuntime.messages.addAll([
      ChatMessageModel.cardMessage({
        'type': 'agent_tool_summary',
        'taskId': 'run-parallel-tools',
        'status': 'running',
      }, id: 'parallel-tool-1'),
      ChatMessageModel.cardMessage({
        'type': 'agent_tool_summary',
        'taskId': 'run-parallel-tools',
        'status': 'progress',
      }, id: 'parallel-tool-2'),
    ]);
    parallelRuntime.activeToolCardId = 'parallel-tool-2';

    coordinator.interruptActiveToolCard(
      conversationId: 8103,
      mode: kChatRuntimeModeAgent,
      summary: '已取消',
    );

    expect(
      parallelRuntime.messages
          .map((message) => message.cardData?['status'])
          .toList(),
      ['interrupted', 'interrupted'],
    );
    expect(parallelRuntime.activeToolCardId, isNull);
    coordinator.discardConversationRuntime(
      conversationId: 8103,
      mode: kChatRuntimeModeAgent,
    );
  });

  test('uses sessionId and toolCallId as the canonical tool identity', () {
    Map<String, dynamic> event({
      required String status,
      required String title,
    }) {
      return <String, dynamic>{
        'message': {
          'method': 'session/update',
          'turnId': 'turn-1',
          'params': {
            'sessionId': 'session-1',
            'update': {
              'sessionUpdate': 'tool_call_update',
              'toolCallId': 'tool-1',
              'kind': 'execute',
              'title': title,
              'status': status,
              'rawOutput': {'type': 'text', 'text': 'done'},
            },
          },
        },
      };
    }

    reducer.reduce(
      runtime: runtime,
      event: event(status: 'in_progress', title: 'run'),
    );
    reducer.reduce(
      runtime: runtime,
      event: event(status: 'completed', title: 'run done'),
    );

    expect(runtime.messages, hasLength(1));
    final message = runtime.messages.single;
    final cardData = message.cardData!;
    expect(cardData['sessionId'], 'session-1');
    expect(cardData['turnId'], 'turn-1');
    expect(cardData['toolCallId'], 'tool-1');
    expect(cardData['toolKey'], 'session-1:tool-1');
    expect(message.id, 'tool:session-1:tool-1:command');
  });

  test(
    'does not regress a completed ACP tool card on a stale running update',
    () {
      Map<String, dynamic> event({
        required String status,
        String? terminalOutput,
      }) {
        return <String, dynamic>{
          'message': {
            'method': 'session/update',
            'turnId': 'turn-terminal-ordering',
            'params': {
              'sessionId': 'session-terminal-ordering',
              'update': {
                'sessionUpdate': 'tool_call_update',
                'toolCallId': 'terminal-ordering-1',
                'kind': 'other',
                'title': 'bash',
                'status': status,
                'rawOutput': {
                  'toolType': 'terminal',
                  'toolName': 'bash',
                  if (terminalOutput != null) 'terminalOutput': terminalOutput,
                },
              },
            },
          },
        };
      }

      reducer.reduce(
        runtime: runtime,
        event: event(status: 'completed', terminalOutput: 'finished'),
      );
      reducer.reduce(
        runtime: runtime,
        event: event(status: 'in_progress'),
      );

      final cardData = runtime.messages.single.cardData!;
      expect(cardData['status'], 'success');
      expect(cardData['terminalOutput'], 'finished');
    },
  );

  test('treats ACP success and timeout tool statuses as terminal cards', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'session/update',
          'turnId': 'turn-terminal-statuses',
          'params': {
            'sessionId': 'session-terminal-statuses',
            'update': {
              'sessionUpdate': 'tool_call_update',
              'toolCallId': 'tool-terminal-statuses',
              'kind': 'execute',
              'title': '执行命令',
              'status': 'success',
              'rawOutput': {'terminalOutput': 'done'},
            },
          },
        },
      },
    );

    expect(runtime.messages, hasLength(1));
    expect(runtime.messages.single.cardData?['status'], 'success');
    expect(runtime.messages.single.streamMeta?['isFinal'], isTrue);
  });

  test('projects structured ACP tool output into the shared terminal card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'session/update',
          'turnId': 'turn-tool-rich',
          'params': {
            'sessionId': 'session-tool-rich',
            'update': {
              'sessionUpdate': 'tool_call_update',
              'toolCallId': 'terminal-1',
              'kind': 'other',
              'title': 'terminal',
              'status': 'completed',
              'rawOutput': {
                'toolType': 'terminal',
                'summary': 'Command completed',
                'resultPreview': {'exitCode': 0},
                'terminalOutput': 'hello from the shared ACP card',
                'terminalSessionId': 'shell-1',
                'artifacts': [
                  {
                    'id': 'artifact-1',
                    'title': 'result.txt',
                    'uri': 'file:///workspace/result.txt',
                  },
                ],
              },
            },
          },
        },
      },
    );

    final cardData = runtime.messages
        .firstWhere(
          (message) => message.cardData?['type'] == 'agent_tool_summary',
        )
        .cardData!;
    expect(cardData['toolType'], 'terminal');
    expect(cardData['summary'], 'Command completed');
    expect(cardData['terminalOutput'], 'hello from the shared ACP card');
    expect(cardData['terminalSessionId'], 'shell-1');
    expect(cardData['artifacts'], hasLength(1));
    final artifact = runtime.messages.firstWhere(
      (message) => message.cardData?['type'] == 'artifact_card',
    );
    expect(artifact.cardData?['artifact']?['title'], 'result.txt');
  });

  test('tracks the active ACP tool for the shared stop action', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'session/update',
          'turnId': 'turn-active-tool',
          'params': {
            'sessionId': 'session-active-tool',
            'update': {
              'sessionUpdate': 'tool_call_update',
              'toolCallId': 'active-tool-1',
              'kind': 'execute',
              'title': 'bash',
              'status': 'in_progress',
            },
          },
        },
      },
    );

    expect(runtime.activeToolCardId, isNotNull);
    final activeCardId = runtime.activeToolCardId!;
    expect(runtime.messages.single.id, activeCardId);

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'session/update',
          'turnId': 'turn-active-tool',
          'params': {
            'sessionId': 'session-active-tool',
            'update': {
              'sessionUpdate': 'tool_call_update',
              'toolCallId': 'active-tool-1',
              'kind': 'execute',
              'title': 'bash',
              'status': 'completed',
            },
          },
        },
      },
    );

    expect(runtime.activeToolCardId, isNull);
  });

  test('keeps another parallel ACP tool active after one completes', () {
    Map<String, dynamic> toolUpdate({
      required String toolCallId,
      required String status,
    }) {
      return {
        'message': {
          'method': 'session/update',
          'turnId': 'turn-parallel-tools',
          'params': {
            'sessionId': 'session-parallel-tools',
            'update': {
              'sessionUpdate': 'tool_call_update',
              'toolCallId': toolCallId,
              'kind': 'execute',
              'title': toolCallId,
              'status': status,
            },
          },
        },
      };
    }

    reducer.reduce(
      runtime: runtime,
      event: toolUpdate(toolCallId: 'parallel-1', status: 'in_progress'),
    );
    reducer.reduce(
      runtime: runtime,
      event: toolUpdate(toolCallId: 'parallel-2', status: 'in_progress'),
    );
    final secondCardId = runtime.activeToolCardId;
    expect(secondCardId, isNotNull);

    reducer.reduce(
      runtime: runtime,
      event: toolUpdate(toolCallId: 'parallel-2', status: 'completed'),
    );

    expect(runtime.activeToolCardId, isNotNull);
    expect(runtime.activeToolCardId, isNot(secondCardId));
    expect(
      runtime.messages
          .firstWhere((message) => message.id == runtime.activeToolCardId)
          .cardData?['status'],
      'running',
    );
  });

  test('projects legacy tool-result details from ACP rawOutput', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'session/update',
          'turnId': 'turn-legacy-tool-details',
          'params': {
            'sessionId': 'session-legacy-tool-details',
            'update': {
              'sessionUpdate': 'tool_call_update',
              'toolCallId': 'clarify-1',
              'kind': 'other',
              'title': 'clarify',
              'status': 'completed',
              'rawOutput': {
                'toolType': 'clarify',
                'result': {
                  'question': '还要继续吗？',
                  'missingFields': ['confirmed'],
                },
                'outputTruncated': true,
                'originalChars': 18000,
                'headTail': 'head ... tail',
                'fullOutputArtifact': {'id': 'full-output-1'},
                'subagentStatusText': '子任务已完成',
                'subagentEvents': [
                  {'id': 'subagent-event-1', 'kind': 'subagent_completed'},
                ],
              },
            },
          },
        },
      },
    );

    final card = runtime.messages.single.cardData!;
    expect(card['question'], '还要继续吗？');
    expect(card['missingFields'], ['confirmed']);
    expect(card['outputTruncated'], isTrue);
    expect(card['originalChars'], 18000);
    expect(card['headTail'], 'head ... tail');
    expect(card['fullOutputArtifact']?['id'], 'full-output-1');
    expect(card['subagentStatusText'], '子任务已完成');
    expect(card['subagentEvents'], hasLength(1));
  });

  test('preserves the ACP terminal status over raw tool output', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'turnId': 'turn-actionable-status',
        'params': {
          'sessionId': 'session-actionable-status',
          'update': {
            'sessionUpdate': 'tool_call_update',
            'toolCallId': 'actionable-1',
            'kind': 'execute',
            'title': '执行高权限操作',
            'status': 'pending',
            'rawOutput': {'success': false, 'question': '请确认执行高权限操作'},
          },
        },
      },
    );

    final card = runtime.messages.single.cardData!;
    expect(card['status'], 'pending');
  });

  test(
    'keeps ACP subagent progress carried in rawInput and merges children',
    () {
      Map<String, dynamic> update({
        required String status,
        required Map<String, dynamic> rawInput,
        Map<String, dynamic>? rawOutput,
      }) {
        return {
          'message': {
            'method': 'session/update',
            'turnId': 'turn-subagent-progress',
            'params': {
              'sessionId': 'session-subagent-progress',
              'update': {
                'sessionUpdate': 'tool_call_update',
                'toolCallId': 'subagent-dispatch-1',
                'kind': 'other',
                'title': 'subagent_dispatch',
                'status': status,
                'rawInput': rawInput,
                if (rawOutput != null) 'rawOutput': rawOutput,
              },
            },
          },
        };
      }

      reducer.reduce(
        runtime: runtime,
        event: update(
          status: 'in_progress',
          rawInput: {
            'subagentStatusText': '正在执行子任务 1',
            'subagentEvents': [
              {
                'id': 'subagent-event-1',
                'kind': 'subagent_started',
                'summary': '子任务 1：读取配置',
                'status': 'running',
                'taskIndex': 0,
                'subagentId': 'child-1',
                'seq': 1,
              },
            ],
          },
        ),
      );
      reducer.reduce(
        runtime: runtime,
        event: update(
          status: 'in_progress',
          rawInput: {
            'subagentStatusText': '正在执行子任务 2',
            'subagentEvents': [
              {
                'id': 'subagent-event-2',
                'kind': 'subagent_started',
                'summary': '子任务 2：检查依赖',
                'status': 'running',
                'taskIndex': 1,
                'subagentId': 'child-2',
                'seq': 2,
              },
            ],
          },
        ),
      );
      reducer.reduce(
        runtime: runtime,
        event: update(
          status: 'completed',
          rawInput: const {},
          rawOutput: {
            'toolType': 'context',
            'toolName': 'subagent_dispatch',
            'success': true,
            'result': {'results': []},
          },
        ),
      );

      final card = runtime.messages.single.cardData!;
      expect(card['toolType'], 'subagent');
      expect(card['status'], 'success');
      expect(card['subagentStatusText'], '正在执行子任务 2');
      expect(card['subagentEvents'], hasLength(2));
      expect(
        (card['subagentEvents'] as List).map((event) => event['taskIndex']),
        containsAll(<int>[0, 1]),
      );
    },
  );

  test('keeps plain ACP tool raw output in the shared card summary', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-plain-tool-output',
          'turnId': 'turn-plain-tool-output',
          'update': {
            'sessionUpdate': 'tool_call_update',
            'toolCallId': 'plain-output-1',
            'kind': 'other',
            'title': '读取结果',
            'status': 'completed',
            'rawOutput': 'plain ACP result',
          },
        },
      },
    );

    final card = runtime.messages.single.cardData!;
    expect(card['summary'], 'plain ACP result');
    expect(card['progress'], 'plain ACP result');
  });

  test('projects nested ACP tool results through the shared card fields', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-nested-tool-result',
          'turnId': 'turn-nested-tool-result',
          'update': {
            'sessionUpdate': 'tool_call_update',
            'toolCallId': 'nested-tool-result-1',
            'kind': 'other',
            'title': '运行任务',
            'status': 'completed',
            'rawOutput': {
              'toolType': 'context',
              'result': {
                'toolType': 'terminal',
                'terminalOutput': 'nested output',
                'terminalSessionId': 'shell-nested',
                'artifacts': [
                  {
                    'id': 'nested-artifact',
                    'title': 'result.txt',
                    'uri': 'workspace://result.txt',
                  },
                ],
              },
            },
          },
        },
      },
    );

    final card = runtime.messages
        .firstWhere(
          (message) => message.cardData?['type'] == 'agent_tool_summary',
        )
        .cardData!;
    expect(card['toolType'], 'terminal');
    expect(card['terminalOutput'], 'nested output');
    expect(card['terminalSessionId'], 'shell-nested');
    expect(card['artifacts'], hasLength(1));
    expect(
      runtime.messages.any(
        (message) => message.cardData?['type'] == 'artifact_card',
      ),
      isTrue,
    );
  });

  test(
    'routes generic ACP context results by their concrete tool capability',
    () {
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'session/update',
            'turnId': 'turn-context-file',
            'params': {
              'sessionId': 'session-context-file',
              'update': {
                'sessionUpdate': 'tool_call',
                'toolCallId': 'file-1',
                'kind': 'other',
                'title': 'write',
                'status': 'in_progress',
                'rawInput': {'path': '/workspace/note.md'},
              },
            },
          },
        },
      );
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'session/update',
            'turnId': 'turn-context-file',
            'params': {
              'sessionId': 'session-context-file',
              'update': {
                'sessionUpdate': 'tool_call_update',
                'toolCallId': 'file-1',
                'kind': 'other',
                'title': 'write',
                'status': 'completed',
                'rawOutput': {
                  // ContextResult is the native result envelope. It must not
                  // erase the file card route selected by the tool itself.
                  'toolType': 'context',
                  'toolName': 'write',
                  'summary': 'Wrote note.md',
                  'result': {'path': '/workspace/note.md'},
                  'imageDataUrl': 'data:image/png;base64,AA==',
                },
              },
            },
          },
        },
      );

      final cardData = runtime.messages
          .firstWhere(
            (message) => message.cardData?['type'] == 'agent_tool_summary',
          )
          .cardData!;
      expect(cardData['toolType'], 'file');
      expect(cardData['toolName'], 'write');
      expect(cardData['imageDataUrl'], 'data:image/png;base64,AA==');
    },
  );

  test(
    'keeps every context-backed ACP capability on its existing card route',
    () {
      for (final route
          in const <({String id, String toolName, String toolType})>[
            (id: 'browser-1', toolName: 'webfetch', toolType: 'browser'),
            (id: 'image-1', toolName: 'image_generate', toolType: 'image'),
            (id: 'subagent-1', toolName: 'subagent_run', toolType: 'subagent'),
          ]) {
        reducer.reduce(
          runtime: runtime,
          event: {
            'message': {
              'method': 'session/update',
              'turnId': 'turn-${route.id}',
              'params': {
                'sessionId': 'session-context-routes',
                'update': {
                  'sessionUpdate': 'tool_call_update',
                  'toolCallId': route.id,
                  'kind': 'other',
                  'title': route.toolName,
                  'status': 'completed',
                  'rawOutput': {
                    'toolType': 'context',
                    'toolName': route.toolName,
                    'summary': '${route.toolName} completed',
                    'result': const <String, dynamic>{},
                  },
                },
              },
            },
          },
        );
      }

      for (final route
          in const <({String id, String toolName, String toolType})>[
            (id: 'browser-1', toolName: 'webfetch', toolType: 'browser'),
            (id: 'image-1', toolName: 'image_generate', toolType: 'image'),
            (id: 'subagent-1', toolName: 'subagent_run', toolType: 'subagent'),
          ]) {
        final cardData = runtime.messages
            .firstWhere(
              (message) => message.cardData?['toolCallId'] == route.id,
            )
            .cardData!;
        expect(cardData['toolType'], route.toolType);
      }
    },
  );

  test('completed ACP browser tool restores the live browser snapshot', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'session/update',
          'turnId': 'turn-browser-snapshot',
          'params': {
            'sessionId': 'session-browser-snapshot',
            'update': {
              'sessionUpdate': 'tool_call_update',
              'toolCallId': 'browser-snapshot-1',
              'kind': 'other',
              'title': 'webfetch',
              'status': 'completed',
              'rawOutput': {
                'toolType': 'context',
                'toolName': 'webfetch',
                'success': true,
                'workspaceId': 'conversation_42',
                'result': {
                  'currentUrl': 'https://example.com/result',
                  'pageTitle': 'Example result',
                  'activeTabId': 9,
                },
              },
            },
          },
        },
      },
    );

    expect(runtime.browserSessionSnapshot, isNotNull);
    expect(
      runtime.browserSessionSnapshot!.currentUrl,
      'https://example.com/result',
    );
    expect(runtime.browserSessionSnapshot!.workspaceId, 'conversation_42');
  });

  test('ACP schedule and alarm tools preserve their follow-up triggers', () {
    for (final tool in const <({String id, String name, String flag})>[
      (
        id: 'schedule-trigger',
        name: 'schedule_create',
        flag: 'showScheduleAction',
      ),
      (id: 'alarm-trigger', name: 'alarm_create', flag: 'showAlarmAction'),
    ]) {
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'session/update',
            'turnId': 'turn-${tool.id}',
            'params': {
              'sessionId': 'session-follow-up-triggers',
              'update': {
                'sessionUpdate': 'tool_call_update',
                'toolCallId': tool.id,
                'kind': 'other',
                'title': tool.name,
                'status': 'completed',
                'rawOutput': {
                  'toolName': tool.name,
                  'success': true,
                  'result': const <String, dynamic>{},
                },
              },
            },
          },
        },
      );

      final cardData = runtime.messages
          .firstWhere((message) => message.cardData?['toolCallId'] == tool.id)
          .cardData!;
      expect(cardData[tool.flag], isTrue, reason: tool.name);
    }
  });

  test('uses the ACP failed status even when tool output says timeout', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'session/update',
          'turnId': 'turn-terminal-timeout',
          'params': {
            'sessionId': 'session-terminal-timeout',
            'update': {
              'sessionUpdate': 'tool_call_update',
              'toolCallId': 'terminal-timeout-1',
              'kind': 'other',
              'title': 'bash',
              'status': 'failed',
              'rawOutput': {
                'toolType': 'terminal',
                'toolName': 'bash',
                'summary': 'Command timed out',
                'success': false,
                'timedOut': true,
                'terminalOutput': 'partial output',
              },
            },
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['toolType'], 'terminal');
    expect(cardData['status'], 'error');
    expect(cardData['terminalOutput'], 'partial output');
  });

  test(
    'turns an ACP missing-accessibility result into an authorization card',
    () {
      final base = <String, dynamic>{
        'message': {
          'method': 'session/update',
          'turnId': 'turn-permission',
          'params': {
            'sessionId': 'session-permission',
            'update': {
              'sessionUpdate': 'tool_call',
              'toolCallId': 'tool-permission',
              'kind': 'other',
              'title': 'vlm_task',
              'status': 'in_progress',
              'rawInput': <String, dynamic>{},
            },
          },
        },
      };
      reducer.reduce(runtime: runtime, event: base);
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'session/update',
            'turnId': 'turn-permission',
            'params': {
              'sessionId': 'session-permission',
              'update': {
                'sessionUpdate': 'tool_call_update',
                'toolCallId': 'tool-permission',
                'kind': 'other',
                'title': 'vlm_task',
                'status': 'failed',
                'rawOutput': {
                  'type': 'permission_section',
                  'requiredPermissionIds': ['accessibility'],
                  'missing': ['无障碍权限'],
                },
              },
            },
          },
        },
      );

      expect(runtime.messages, hasLength(1));
      expect(runtime.messages.single.cardData?['type'], 'permission_section');
      expect(runtime.messages.single.cardData?['requiredPermissionIds'], [
        'accessibility',
      ]);
      expect(
        runtime.messages.single.cardData?['autoOpenAuthorization'],
        isTrue,
      );
    },
  );

  test('deduplicates ACP permission request and permission result cards', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'id': 'permission-request-1',
          'method': 'session/request_permission',
          'params': {
            'turnId': 'turn-permission-dedupe',
            'sessionId': 'session-permission-dedupe',
            'toolCallId': 'tool-permission-dedupe',
            'title': '执行命令',
            'description': '需要确认后执行',
          },
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'session/update',
          'turnId': 'turn-permission-dedupe',
          'params': {
            'sessionId': 'session-permission-dedupe',
            'update': {
              'sessionUpdate': 'tool_call_update',
              'toolCallId': 'tool-permission-dedupe',
              'kind': 'execute',
              'title': '执行命令',
              'status': 'failed',
              'rawOutput': {
                'type': 'permission_section',
                'requiredPermissionIds': ['accessibility'],
                'missing': ['无障碍权限'],
              },
            },
          },
        },
      },
    );

    expect(runtime.messages, hasLength(1));
    expect(runtime.messages.single.cardData?['type'], 'permission_section');
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
        reducer.reducePromptResponse(
          runtime: runtime,
          sessionId: null,
          turnId: turn,
          stopReason: 'end_turn',
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
    reducer.reducePromptResponse(
      runtime: runtime,
      sessionId: null,
      turnId: 'turn-1',
      stopReason: 'end_turn',
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

  test('keeps a large terminal output delta intact in its tool card', () {
    final output = List<String>.generate(
      700,
      (index) => 'line-$index ${'x' * 180}',
    ).join('\n');
    expect(output.length, greaterThan(128 * 1024));

    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'command/exec/outputDelta',
          'params': {
            'processId': 'proc-large-output',
            'stream': 'stdout',
            'deltaBase64': base64Encode(utf8.encode(output)),
          },
        },
      },
    );

    expect(runtime.messages, hasLength(1));
    expect(runtime.messages.single.cardData?['terminalOutput'], output);
  });

  test('late standalone process output stays with its original run', () {
    runtime
      ..isAiResponding = true
      ..currentDispatchTurnId = 'turn-1'
      ..activeRunId = 'turn-1';
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'process/outputDelta',
          'params': {'processHandle': 'process-1', 'delta': 'first\n'},
        },
      },
    );

    runtime
      ..currentDispatchTurnId = 'turn-2'
      ..activeRunId = 'turn-2';
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'method': 'process/outputDelta',
          'params': {'processHandle': 'process-1', 'delta': 'late\n'},
        },
      },
    );

    expect(runtime.messages, hasLength(1));
    expect(runtime.messages.single.cardData?['taskId'], 'turn-1');
    expect(
      runtime.messages.single.cardData?['terminalOutput'],
      'first\nlate\n',
    );
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

  test('projects ACP assistant text resources into the shared reply', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-assistant-text-resource',
          'turnId': 'turn-assistant-text-resource',
          'update': {
            'sessionUpdate': 'agent_message_chunk',
            'messageId': 'assistant-text-resource',
            'content': {
              'type': 'resource',
              'resource': {
                'uri': 'workspace://notes.txt',
                'mimeType': 'text/plain',
                'text': '资源中的正文',
              },
            },
          },
        },
      },
    );

    expect(runtime.messages.single.text, '资源中的正文');
  });

  test('projects ACP assistant resource links into artifact cards', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'params': {
          'sessionId': 'session-assistant-link',
          'turnId': 'turn-assistant-link',
          'update': {
            'sessionUpdate': 'agent_message_chunk',
            'messageId': 'assistant-link',
            'content': {
              'type': 'resource_link',
              'name': 'result.json',
              'uri': 'omnibot://workspace/result.json',
              'mimeType': 'application/json',
            },
          },
        },
      },
    );

    final artifact = runtime.messages.singleWhere(
      (message) => message.cardData?['type'] == 'artifact_card',
    );
    expect(
      (artifact.cardData?['artifact'] as Map)['uri'],
      'omnibot://workspace/result.json',
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
    'maps standard ACP diff content into a shared file card without a kind',
    () {
      reducer.reduce(
        runtime: runtime,
        event: {
          'method': 'session/update',
          'params': {
            'sessionId': 'session-standard-diff',
            'turnId': 'turn-standard-diff',
            'update': {
              'sessionUpdate': 'tool_call',
              'toolCallId': 'call-standard-diff',
              'title': '更新 main.dart',
              'status': 'completed',
              'content': [
                {
                  'type': 'diff',
                  'path': 'lib/main.dart',
                  'oldText': 'old line\n',
                  'newText': 'new line\n',
                },
              ],
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
    },
  );

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

  test(
    'hydrates the complete remote tool output behind its compact summary',
    () {
      final completeOutput =
          'first remote fact\n' +
          List<String>.filled(256, 'middle remote fact').join('\n') +
          '\ntail remote fact must survive';
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
                  'call_id': 'raw-cmd-long-output',
                  'arguments': '{"cmd":"inspect"}',
                },
                {
                  'type': 'function_call_output',
                  'call_id': 'raw-cmd-long-output',
                  'output': completeOutput,
                },
              ],
            },
          ],
        },
      });

      final cardData = messages.single.cardData!;
      expect(
        cardData['summary'],
        isNot(contains('tail remote fact must survive')),
      );
      expect(cardData['rawResultJson'], contains('first remote fact'));
      expect(
        cardData['rawResultJson'],
        contains('tail remote fact must survive'),
      );
    },
  );

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

  test('thread active status cannot start a prompt', () {
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

    expect(result.handled, isFalse);
    expect(runtime.isAiResponding, isFalse);
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

  test('ignores legacy thread status payloads', () {
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

    expect(result.handled, isFalse);
    expect(runtime.isAiResponding, isFalse);
  });

  test('thread idle leaves reasoning active until the prompt response', () {
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

    expect(runtime.isAiResponding, isTrue);
    expect(runtime.currentDispatchTurnId, 'turn-1');
    expect(
      runtime.messages.any((message) => message.id.endsWith('cancelled')),
      isFalse,
    );
    expect(runtime.messages.single.cardData!['isLoading'], isTrue);
    reducer.reducePromptResponse(
      runtime: runtime,
      sessionId: null,
      turnId: 'turn-1',
      stopReason: 'end_turn',
    );
    expect(runtime.isAiResponding, isFalse);
    expect(runtime.currentDispatchTurnId, isNull);
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

    // A lifecycle-only reasoning start must not create a blank card. The
    // first real delta creates the card and starts its timer.
    expect(runtime.messages, isEmpty);

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
    final startedStartTime = runtime.messages.single.cardData!['startTime'];
    expect(startedStartTime, isA<int>());
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
    reducer.reducePromptResponse(
      runtime: runtime,
      sessionId: null,
      turnId: 'turn-1',
      stopReason: 'end_turn',
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

    reducer.reducePromptResponse(
      runtime: runtime,
      sessionId: null,
      turnId: 'turn-1',
      stopReason: 'end_turn',
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

  test('reused provider tool ids cannot rewrite a previous turn card', () {
    void reduceToolTurn(String turnId) {
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'item/started',
            'params': {
              'turnId': turnId,
              'item': {
                'id': 'call-1',
                'type': 'commandExecution',
                'command': 'echo $turnId',
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
              'turnId': turnId,
              'item': {
                'id': 'call-1',
                'type': 'commandExecution',
                'command': 'echo $turnId',
                'status': 'completed',
              },
            },
          },
        },
      );
      reducer.reducePromptResponse(
        runtime: runtime,
        sessionId: null,
        turnId: turnId,
        stopReason: 'end_turn',
      );
    }

    reduceToolTurn('turn-1');
    reduceToolTurn('turn-2');

    expect(runtime.messages, hasLength(2));
    expect(runtime.messages.map((message) => message.id).toSet(), <String>{
      'call-1-agent-command',
      'turn-2-call-1-agent-command',
    });
    final first = runtime.messages.firstWhere(
      (message) => message.id == 'call-1-agent-command',
    );
    final second = runtime.messages.firstWhere(
      (message) => message.id == 'turn-2-call-1-agent-command',
    );
    expect(first.cardData?['taskId'], 'turn-1');
    expect(second.cardData?['taskId'], 'turn-2');
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

  test('renders standard ACP permission payload as human-readable content', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'message': {
          'id': 'permission-1',
          'method': 'session/request_permission',
          'params': {
            'sessionId': 'session-internal-1',
            'toolCall': {
              'toolCallId': 'tool-call-internal-1',
              'title': 'Run project tests',
              'kind': 'execute',
              'rawInput': {'command': 'pnpm test'},
            },
            'options': [
              {'optionId': 'allow_once', 'name': 'Allow once'},
              {'optionId': 'reject_once', 'name': 'Reject'},
            ],
          },
        },
      },
    );

    final cardData = runtime.messages.single.cardData!;
    expect(cardData['title'], 'Run project tests');
    expect(cardData['detail'], 'Command: pnpm test');
    expect(cardData['detail'], isNot(contains('session-internal-1')));
    expect(cardData['detail'], isNot(contains('tool-call-internal-1')));
    expect(cardData['detail'], isNot(contains('toolCall')));
  });

  test('maps request user input into codex request card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'agentId': 'deepseek-harness-acp',
        'agentName': 'DeepSeek Harness',
        'sessionId': 'session-top-level',
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
    expect(cardData['sessionId'], 'session-top-level');
    expect(cardData['agentId'], 'deepseek-harness-acp');
    expect(cardData['agentName'], 'DeepSeek Harness');
  });

  test('preserves request id when ACP places it inside params', () {
    final result = reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'item/tool/requestUserInput',
        'params': {
          'requestId': 'params-request-1',
          'questions': [
            {'id': 'choice', 'question': 'Choose one'},
          ],
        },
      },
    );

    expect(result.requestId, 'params-request-1');
    expect(runtime.messages.single.cardData?['requestId'], 'params-request-1');
  });

  test('preserves request id when ACP places it inside the request item', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'item/started',
        'params': {
          'item': {
            'id': 'approval-item-1',
            'type': 'requestApproval',
            'requestId': 'item-request-1',
            'reason': 'Need confirmation',
          },
        },
      },
    );

    expect(runtime.messages.single.cardData?['requestId'], 'item-request-1');
  });

  test('marks an ACP request without request id as non-interactive', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'item/tool/requestUserInput',
        'params': {
          'questions': [
            {'id': 'choice', 'question': 'Choose one'},
          ],
        },
      },
    );

    expect(runtime.messages.single.cardData?['requestId'], isNull);
    expect(runtime.messages.single.cardData?['interactionUnavailable'], isTrue);
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

  test('new reasoning item ids stay in one loading thinking card', () {
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

    final thinkingMessages = runtime.messages
        .where((message) => message.cardData?['type'] == 'deep_thinking')
        .toList();
    expect(thinkingMessages, hasLength(1));
    expect(thinkingMessages.single.cardData!['isLoading'], isTrue);
    expect(
      thinkingMessages.single.cardData!['thinkingContent'],
      'first thoughtsecond thought',
    );

    reducer.reducePromptResponse(
      runtime: runtime,
      sessionId: null,
      turnId: 'turn-1',
      stopReason: 'end_turn',
    );

    final completedThinkingMessages = runtime.messages
        .where((message) => message.cardData?['type'] == 'deep_thinking')
        .toList();
    expect(completedThinkingMessages, hasLength(1));
    expect(
      completedThinkingMessages.single.cardData?['stage'],
      ThinkingStage.complete.value,
    );
  });

  test(
    'ACP reasoning segment metadata splits reused message ids around tools',
    () {
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'session/update',
            'params': {
              'sessionId': 'session-1',
              'turnId': 'turn-1',
              'update': {
                'sessionUpdate': 'agent_thought_chunk',
                'messageId': 'shared-thought',
                'content': {'type': 'text', 'text': 'before tool'},
                '_meta': {
                  'cn.com.omnimind.agent': {
                    'reasoning': {'segmentIndex': 0},
                  },
                },
              },
            },
          },
        },
      );
      reducer.reduce(
        runtime: runtime,
        event: {
          'message': {
            'method': 'session/update',
            'params': {
              'sessionId': 'session-1',
              'turnId': 'turn-1',
              'update': {
                'sessionUpdate': 'tool_call',
                'toolCallId': 'tool-1',
                'title': 'read_file',
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
            'method': 'session/update',
            'params': {
              'sessionId': 'session-1',
              'turnId': 'turn-1',
              'update': {
                'sessionUpdate': 'agent_thought_chunk',
                'messageId': 'shared-thought',
                'content': {'type': 'text', 'text': 'after tool'},
                '_meta': {
                  'cn.com.omnimind.agent': {
                    'reasoning': {'segmentIndex': 1},
                  },
                },
              },
            },
          },
        },
      );

      final cards = runtime.messages
          .where((message) => message.cardData?['type'] == 'deep_thinking')
          .toList()
          .reversed
          .toList();
      expect(cards, hasLength(2));
      expect(cards[0].cardData?['thinkingContent'], 'before tool');
      expect(cards[1].cardData?['thinkingContent'], 'after tool');
    },
  );

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
    reducer.reducePromptResponse(
      runtime: runtime,
      sessionId: null,
      turnId: 'turn-1',
      stopReason: 'end_turn',
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

  test('owning prompt transport failure finalizes the active request', () {
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

    reducer.reducePromptResponse(
      runtime: runtime,
      sessionId: null,
      turnId: 'turn-1',
      stopReason: 'error',
      error: 'connection lost',
    );

    expect(runtime.isAiResponding, isFalse);
    expect(runtime.currentDispatchTurnId, isNull);
    final thinking = runtime.messages
        .firstWhere((message) => message.cardData?['type'] == 'deep_thinking')
        .cardData!;
    expect(thinking['isLoading'], isFalse);
    expect(thinking['stage'], ThinkingStage.complete.value);
  });

  test(
    'prompt failure finalizes a local run when the official turn id differs',
    () {
      runtime
        ..isAiResponding = true
        ..activeRunId = 'local-run-1'
        ..currentDispatchTurnId = 'local-run-1'
        ..lastAgentTurnId = 'local-run-1'
        ..activeAcpTurnId = 'official-turn-1';

      reducer.reducePromptResponse(
        runtime: runtime,
        sessionId: null,
        turnId: 'official-turn-1',
        stopReason: 'error',
        error: 'provider failed',
      );

      expect(runtime.isAiResponding, isFalse);
      expect(runtime.currentDispatchTurnId, isNull);
      expect(runtime.activeAcpTurnId, isNull);
    },
  );

  test(
    'terminal error finalizes a local run when the official turn id differs',
    () {
      runtime
        ..isAiResponding = true
        ..activeRunId = 'local-run-2'
        ..currentDispatchTurnId = 'local-run-2'
        ..lastAgentTurnId = 'local-run-2'
        ..activeAcpTurnId = 'official-turn-2';

      reducer.reducePromptResponse(
        runtime: runtime,
        sessionId: null,
        turnId: 'official-turn-2',
        stopReason: 'error',
        error: 'connection lost',
      );

      expect(runtime.isAiResponding, isFalse);
      expect(runtime.currentDispatchTurnId, isNull);
      expect(runtime.activeAcpTurnId, isNull);
    },
  );

  test('turn completed with a cancelled stop reason stays cancelled', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'turn/started',
        'turnId': 'cancelled-turn',
        'params': {'turnId': 'cancelled-turn'},
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'turnId': 'cancelled-turn',
        'params': {
          'update': {
            'sessionUpdate': 'agent_thought_chunk',
            'messageId': 'cancelled-thought',
            'content': {'text': '处理中'},
          },
        },
      },
    );

    reducer.reducePromptResponse(
      runtime: runtime,
      sessionId: null,
      turnId: 'cancelled-turn',
      stopReason: 'cancelled',
    );

    expect(runtime.isAiResponding, isFalse);
    final thinking = runtime.messages.firstWhere(
      (message) => message.cardData?['type'] == 'deep_thinking',
    );
    expect(thinking.cardData?['stage'], ThinkingStage.cancelled.value);
  });

  test('owning prompt Provider error is rendered as a concise message', () {
    reducer.reducePromptResponse(
      runtime: runtime,
      sessionId: null,
      turnId: 'turn-1',
      stopReason: 'error',
      error: 'Invalid JSON data: tools[8].type is unsupported',
    );

    final statusCard = runtime.messages.firstWhere(
      (message) => message.cardData?['toolType'] == 'status',
    );
    expect(
      statusCard.cardData?['summary'],
      'Invalid JSON data: tools[8].type is unsupported',
    );
    expect(statusCard.cardData?['summary'], isNot(contains('{"error"')));
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

  test('standard ACP tool content chunks update the existing shared card', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'turnId': 'turn-tool-content-chunk',
        'params': {
          'sessionId': 'session-tool-content-chunk',
          'update': {
            'sessionUpdate': 'tool_call',
            'toolCallId': 'tool-content-1',
            'kind': 'other',
            'title': '读取结果',
            'status': 'in_progress',
          },
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'turnId': 'turn-tool-content-chunk',
        'params': {
          'sessionId': 'session-tool-content-chunk',
          'update': {
            'sessionUpdate': 'tool_call_content_chunk',
            'toolCallId': 'tool-content-1',
            'content': {
              'type': 'content',
              'content': {'type': 'text', 'text': '第一段结果'},
            },
          },
        },
      },
    );
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'turnId': 'turn-tool-content-chunk',
        'params': {
          'sessionId': 'session-tool-content-chunk',
          'update': {
            'sessionUpdate': 'vendor_passthrough',
            'rawUpdate': {
              'type': 'tool_call_content_chunk',
              'toolCallId': 'tool-content-1',
              'content': {
                'type': 'content',
                'content': {'type': 'text', 'text': '第二段结果'},
              },
            },
          },
        },
      },
    );

    final card = runtime.messages
        .firstWhere(
          (message) => message.cardData?['type'] == 'agent_tool_summary',
        )
        .cardData!;
    final contentItems = card['contentItems'] as List;
    expect(contentItems, hasLength(2));
    expect(card['rawResultJson'], contains('第二段结果'));
  });

  test('ACP terminal output chunks append by tool or terminal identity', () {
    reducer.reduce(
      runtime: runtime,
      event: {
        'method': 'session/update',
        'turnId': 'turn-terminal-chunk',
        'params': {
          'sessionId': 'session-terminal-chunk',
          'update': {
            'sessionUpdate': 'tool_call',
            'toolCallId': 'tool-terminal-1',
            'kind': 'execute',
            'title': '运行命令',
            'status': 'in_progress',
            'content': [
              {'type': 'terminal', 'terminalId': 'terminal-1'},
            ],
          },
        },
      },
    );
    for (final update in <Map<String, dynamic>>[
      {
        'sessionUpdate': 'terminal_output_chunk',
        'toolCallId': 'tool-terminal-1',
        'terminalId': 'terminal-1',
        'data': 'one\\n',
      },
      {
        'sessionUpdate': 'vendor_passthrough',
        'rawUpdate': {
          'type': 'terminal_output_chunk',
          'terminalId': 'terminal-1',
          'data': 'two\\n',
        },
      },
    ]) {
      reducer.reduce(
        runtime: runtime,
        event: {
          'method': 'session/update',
          'turnId': 'turn-terminal-chunk',
          'params': {'sessionId': 'session-terminal-chunk', 'update': update},
        },
      );
    }

    final card = runtime.messages.single.cardData!;
    expect(card['terminalOutput'], 'one\\ntwo\\n');
    expect(card['terminalSessionId'], 'terminal-1');
  });
}
