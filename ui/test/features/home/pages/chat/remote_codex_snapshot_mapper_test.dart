import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/models/chat_message_model.dart';

/// Remote Agent (Codex bridge) history mapping. The mapper is a page adapter
/// that stays in Flutter; these cases were part of the former Dart reducer
/// suite before the projection moved to the native owner.
void main() {
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
          ChatMessageModel.cardMessage(
            {
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
            },
            id: 'reason-2-agent-thinking',
          ).copyWith(createAt: now.add(const Duration(seconds: 2))),
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
}
