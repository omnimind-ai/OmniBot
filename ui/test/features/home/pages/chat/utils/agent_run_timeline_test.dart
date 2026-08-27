import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/models/chat_message_model.dart';

void main() {
  test('groups by canonical runId before legacy parentTaskId', () {
    final message = ChatMessageModel(
      id: 'run-1-text',
      type: 1,
      user: 2,
      content: const {'text': '答案', 'id': 'run-1-text'},
      streamMeta: const {
        'runId': 'run-1',
        'parentTaskId': 'official-turn-1',
        'entrySeq': 1,
        'seq': 1,
        'isFinal': true,
      },
    );

    final entries = buildAgentRunTimelineEntries([message]);

    expect(entries.single.group?.runId, 'run-1');
    expect(entries.single.group?.taskId, 'run-1');
  });

  test('restores review header for legacy Xiaowan timestamp-ai reply', () {
    const timestamp = '1787481000000';
    final messages = <ChatMessageModel>[
      ChatMessageModel(
        id: '$timestamp-ai',
        type: 1,
        user: 2,
        content: const <String, dynamic>{
          'id': '$timestamp-ai',
          'text': '旧小万最终回复',
        },
      ),
      _thinkingCard(
        id: '$timestamp-ai-thinking',
        taskId: '$timestamp-ai',
        seq: 1,
      ),
      ChatMessageModel.userMessage('旧问题', id: '$timestamp-user'),
    ];

    final entries = buildAgentRunTimelineEntries(messages);

    expect(entries, hasLength(2));
    expect(entries.first.group?.taskId, '$timestamp-ai');
    expect(
      entries.first.group?.visibleMessagesNewestFirst.single.text,
      '旧小万最终回复',
    );
    expect(entries.first.group?.thinkingCount, 1);
    expect(entries.first.group?.hasProcessMessages, isTrue);
    expect(entries.last.message?.id, '$timestamp-user');
  });

  test('groups completed agent run by parent task id', () {
    final entries = buildAgentRunTimelineEntries(_buildCompletedRunMessages());

    expect(entries, hasLength(2));
    expect(entries.first.group?.taskId, 'task-1');
    expect(entries.first.group?.thinkingCount, 1);
    expect(entries.first.group?.toolCount, 1);
    expect(entries.first.group?.visibleMessagesNewestFirst.single.text, '最终回答');
  });

  test('keeps the ACP plan as a visible mutable card outside the fold', () {
    final plan = _cardMessage(
      id: 'task-plan-agent-plan',
      taskId: 'task-plan',
      kind: 'tool_progress',
      seq: 2,
      cardData: <String, dynamic>{
        'type': 'agent_tool_summary',
        'toolType': 'plan',
        'toolName': 'plan',
        'toolTitle': 'Agent plan',
        'status': 'running',
        'planEntries': <Map<String, dynamic>>[
          {'content': 'Inspect workspace', 'status': 'in_progress'},
          {'content': 'Suggest improvement', 'status': 'pending'},
        ],
      },
    );
    final answer = _assistantMessage(
      id: 'task-plan-answer',
      text: '完成',
      taskId: 'task-plan',
      kind: 'text_snapshot',
      seq: 3,
      isFinal: true,
    );

    final group = buildAgentRunTimelineEntries([
      answer,
      plan,
      ChatMessageModel.userMessage('开始', id: 'task-plan-user'),
    ]).first.group!;

    expect(group.visibleMessagesOldestFirst.map((message) => message.id), [
      'task-plan-agent-plan',
      'task-plan-answer',
    ]);
    expect(group.processMessagesOldestFirst, isEmpty);
  });

  test('hides standalone artifact card between prompt and Agent response', () {
    final messages = <ChatMessageModel>[
      ChatMessageModel.cardMessage(
        const <String, dynamic>{
          'type': 'artifact_card',
          'artifact': <String, dynamic>{'title': 'result.md'},
          'taskId': 'task-1',
          'runId': 'task-1',
          'cardId': 'task-1-artifact-result',
        },
        id: 'task-1-artifact-result',
        streamMeta: const <String, dynamic>{
          'runId': 'task-1',
          'parentTaskId': 'task-1',
          'kind': 'artifact',
        },
      ),
      ..._buildCompletedRunMessages(),
    ];

    final entries = buildAgentRunTimelineEntries(messages);

    expect(entries, hasLength(2));
    expect(entries.any((entry) => entry.key.contains('artifact')), isFalse);
    expect(entries.first.group?.taskId, 'task-1');
    expect(entries.last.message?.id, 'user-1');
  });

  test('projects ACP user-input request to one Agent question bubble', () {
    final request = ChatMessageModel.cardMessage(
      <String, dynamic>{
        'type': 'agent_request',
        'requestKind': 'user_input',
        'requestId': 'request-1',
        'title': '需要你的确认',
        'detail': '请告诉我下一步怎么做',
        'taskId': 'run-1',
        'runId': 'run-1',
      },
      id: 'request-1-card',
      streamMeta: const <String, dynamic>{
        'runId': 'run-1',
        'kind': 'clarify_required',
      },
    );

    final entries = buildAgentRunTimelineEntries(<ChatMessageModel>[
      request,
      ChatMessageModel.userMessage('开始', id: 'user-1'),
    ]);

    final group = entries.first.group;
    expect(group?.taskId, 'run-1');
    expect(group?.visibleMessagesOldestFirst, hasLength(1));
    expect(group?.visibleMessagesOldestFirst.single.type, 2);
    expect(
      group?.visibleMessagesOldestFirst.single.cardData?['simplePresentation'],
      true,
    );
    expect(
      group?.visibleMessagesOldestFirst.single.cardData?['title'],
      '需要你的确认',
    );
    expect(
      group?.visibleMessagesOldestFirst.single.cardData?['detail'],
      '请告诉我下一步怎么做',
    );
  });

  test('projects ACP permission request without rendering a request card', () {
    final request = ChatMessageModel.cardMessage(
      <String, dynamic>{
        'type': 'agent_request',
        'requestKind': 'approval',
        'requestId': 'permission-1',
        'title': '允许执行命令',
        'detail': '需要访问工作区',
        'taskId': 'run-approval',
        'runId': 'run-approval',
      },
      id: 'permission-1-card',
      streamMeta: const <String, dynamic>{
        'runId': 'run-approval',
        'kind': 'permission_required',
      },
    );

    final entries = buildAgentRunTimelineEntries(
      <ChatMessageModel>[request],
      activeTaskIds: const <String>{'run-approval'},
    );

    final rendered = entries.single.group!.visibleMessagesOldestFirst.single;
    expect(rendered.type, 2);
    expect(rendered.user, 3);
    expect(rendered.cardData?['simplePresentation'], true);
    expect(rendered.cardData?['title'], '允许执行命令');
    expect(rendered.cardData?['detail'], '需要访问工作区');
  });

  test('hides structured ACP schema from the rendered question', () {
    final request = ChatMessageModel.cardMessage(
      <String, dynamic>{
        'type': 'agent_request',
        'requestKind': 'user_input',
        'requestId': 'schema-request',
        'title': 'The agent needs your input.',
        'detail': '{"type":"object","properties":{}}',
        'rawParamsJson':
            '{"requestedSchema":{"type":"object","properties":{"question":{"type":"string","title":"插件名称","description":"请输入要安装的插件"}}}}',
        'runId': 'run-schema',
      },
      id: 'schema-request-card',
      streamMeta: const <String, dynamic>{'runId': 'run-schema'},
    );

    final entries = buildAgentRunTimelineEntries(<ChatMessageModel>[request]);
    final rendered = entries.single.group!.visibleMessagesOldestFirst.single;
    expect(rendered.type, 2);
    expect(rendered.cardData?['title'], '插件名称');
    expect(rendered.cardData?['detail'], '请输入要安装的插件');
    expect(rendered.cardData?['detail'], isNot(contains('requestedSchema')));
  });

  test('uses turn-owned content anchors over a stale tool timestamp', () {
    final startedAt = DateTime(2026, 8, 22, 14, 29, 24, 493);
    final messages = _buildCompletedRunMessages()
        .map((message) {
          if (message.id == 'task-1-tool') {
            return message.copyWith(
              createAt: startedAt.subtract(const Duration(minutes: 2)),
            );
          }
          if (message.id == 'task-1-thinking') {
            return message.copyWith(createAt: startedAt);
          }
          if (message.id == 'task-1-text') {
            return message.copyWith(
              createAt: startedAt.add(const Duration(seconds: 4)),
            );
          }
          return message;
        })
        .toList(growable: false);

    final group = buildAgentRunTimelineEntries(messages).first.group!;

    expect(group.startedAt, startedAt);
    expect(group.finishedAt, startedAt.add(const Duration(seconds: 4)));
  });

  test('keeps every prose message visible when history lacks isFinal', () {
    final messages = <ChatMessageModel>[
      _assistantMessage(
        id: 'task-2-text-2',
        text: '第二版回答',
        taskId: 'task-2',
        kind: 'text_snapshot',
        seq: 22,
        isFinal: null,
      ),
      _assistantMessage(
        id: 'task-2-text-1',
        text: '第一版回答',
        taskId: 'task-2',
        kind: 'text_snapshot',
        seq: 21,
        isFinal: null,
      ),
      _thinkingCard(id: 'task-2-thinking', taskId: 'task-2', seq: 12),
    ];

    final entries = buildAgentRunTimelineEntries(messages);

    expect(entries, hasLength(1));
    expect(
      entries.single.group?.visibleMessagesOldestFirst.map(
        (message) => message.id,
      ),
      <String>['task-2-text-1', 'task-2-text-2'],
    );
  });

  test('groups an in-flight run and marks it running', () {
    final entries = buildAgentRunTimelineEntries(
      _buildCompletedRunMessages(isFinal: false),
      activeTaskIds: const <String>{'task-1'},
    );

    // An active run is one group, not a pile of loose bubbles. That is what
    // guarantees a single agent avatar + single "processing" header per turn
    // however many message entries the agent streams.
    expect(entries, hasLength(2));
    expect(entries.first.group?.taskId, 'task-1');
    expect(entries.first.group?.status, AgentRunStatus.running);
    expect(entries.last.message?.id, 'user-1');
  });

  test('run status follows the active task set, not a persisted flag', () {
    final messages = _buildCompletedRunMessages(isFinal: true);
    final activeEntries = buildAgentRunTimelineEntries(
      messages,
      activeTaskIds: const <String>{'task-1'},
    );
    final completedEntries = buildAgentRunTimelineEntries(messages);

    expect(activeEntries.first.group?.status, AgentRunStatus.running);
    expect(completedEntries, hasLength(2));
    expect(completedEntries.first.group?.taskId, 'task-1');
    expect(completedEntries.first.group?.status, AgentRunStatus.finished);
    expect(
      completedEntries.first.group?.visibleMessagesNewestFirst.single.id,
      'task-1-text',
    );
    expect(
      completedEntries.first.group?.processMessagesNewestFirst.map(
        (message) => message.id,
      ),
      containsAll(<String>['task-1-tool', 'task-1-thinking']),
    );
  });

  test('groups a finished run whose snapshots all say isFinal:false', () {
    // Regression for on-device conversation 58: codex-acp turns persisted with
    // isFinal:false on every assistant message because no terminal event was
    // ever emitted. Gating the group on that bit removed the agent avatar, the
    // "已处理" label, and the fold all at once. Grouping must depend only on the
    // run no longer being active.
    final messages = <ChatMessageModel>[
      _assistantMessage(
        id: 'msg-e-agent-message',
        text: '最终答案',
        taskId: 'dc8c5328',
        kind: 'text_snapshot',
        seq: 11,
        isFinal: false,
      ),
      _assistantMessage(
        id: 'msg-d-agent-message',
        text: '中间叙述 2',
        taskId: 'dc8c5328',
        kind: 'text_snapshot',
        seq: 7,
        isFinal: false,
      ),
      _assistantMessage(
        id: 'msg-c-agent-message',
        text: '中间叙述 1',
        taskId: 'dc8c5328',
        kind: 'text_snapshot',
        seq: 6,
        isFinal: false,
      ),
      _cardMessage(
        id: 'exec-1-agent-command',
        taskId: 'dc8c5328',
        kind: 'tool_completed',
        seq: 5,
        cardData: <String, dynamic>{
          'type': 'agent_tool_summary',
          'status': 'success',
          'toolType': 'terminal',
          'toolTitle': 'ls',
        },
      ),
      ChatMessageModel.userMessage('mimo code 有 acp 协议吗？', id: 'user-58'),
    ];

    final entries = buildAgentRunTimelineEntries(messages);

    expect(entries, hasLength(2));
    final group = entries.first.group;
    expect(group?.taskId, 'dc8c5328');
    expect(group?.status, AgentRunStatus.finished);
    // Keep every prose message in the group so expanding the completed run
    // can restore the full trace; the widget's collapsed projection shows
    // only the final prose message.
    expect(group?.visibleMessagesOldestFirst.map((m) => m.id), <String>[
      'msg-c-agent-message',
      'msg-d-agent-message',
      'msg-e-agent-message',
    ]);
    expect(group?.processMessagesOldestFirst.map((m) => m.id), <String>[
      'exec-1-agent-command',
    ]);
  });

  test('groups a text-only turn so it still gets a header', () {
    // No tool cards, no thinking cards, a single assistant message. The old
    // "needs at least two messages" gate dropped this to a loose bubble, which
    // meant a plain question and answer never showed an agent avatar.
    final messages = <ChatMessageModel>[
      _assistantMessage(
        id: 'task-9-text',
        text: '简短回答',
        taskId: 'task-9',
        kind: 'text_snapshot',
        seq: 2,
        isFinal: false,
      ),
      ChatMessageModel.userMessage('简短问题', id: 'user-9'),
    ];

    final entries = buildAgentRunTimelineEntries(messages);

    expect(entries, hasLength(2));
    expect(entries.first.group?.taskId, 'task-9');
    expect(entries.first.group?.processMessagesNewestFirst, isEmpty);
    expect(
      entries.first.group?.visibleMessagesNewestFirst.single.id,
      'task-9-text',
    );
  });

  test('many message-less active ids collapse to a single running header', () {
    // Regression for the duplicated avatars: every streamed assistant message
    // used to leak its entry id into the active-task set, and each leaked id
    // rendered its own avatar + "正在处理" row. "An agent is working and has
    // produced nothing yet" is one condition, so it gets at most one header
    // however many ids are in flight.
    final entries = buildAgentRunTimelineEntries(
      _buildCompletedRunMessages(),
      activeTaskIds: const <String>{
        'msg-a-agent-message',
        'msg-b-agent-message',
        'msg-c-agent-message',
        'task-1-ai',
      },
    );

    final runningGroups = entries
        .where((entry) => entry.group?.isRunning ?? false)
        .toList(growable: false);
    expect(runningGroups, hasLength(1));
    expect(entries.where((entry) => entry.group != null), hasLength(2));
  });

  test('no pending header once a real run is already streaming', () {
    final entries = buildAgentRunTimelineEntries(
      _buildCompletedRunMessages(isFinal: false),
      activeTaskIds: const <String>{
        'task-1',
        'task-1-ai',
        'msg-a-agent-message',
      },
    );

    expect(
      entries.where((entry) => entry.group?.isRunning ?? false),
      hasLength(1),
    );
    expect(entries.first.group?.taskId, 'task-1');
  });

  test('resolves the run agent id per message, then per conversation', () {
    final withMessageIdentity = buildAgentRunTimelineEntries(<ChatMessageModel>[
      _assistantMessage(
        id: 'task-a-text',
        text: '答案',
        taskId: 'task-a',
        kind: 'text_snapshot',
        seq: 2,
        agentId: 'claude-code-acp',
      ),
      ChatMessageModel.userMessage('问题', id: 'user-a'),
    ], conversationAgentId: 'codex-acp');
    expect(withMessageIdentity.first.group?.agentId, 'claude-code-acp');

    final withoutMessageIdentity =
        buildAgentRunTimelineEntries(<ChatMessageModel>[
          _assistantMessage(
            id: 'task-b-text',
            text: '答案',
            taskId: 'task-b',
            kind: 'text_snapshot',
            seq: 2,
          ),
          ChatMessageModel.userMessage('问题', id: 'user-b'),
        ], conversationAgentId: 'opencode-acp');
    expect(withoutMessageIdentity.first.group?.agentId, 'opencode-acp');

    final withNeither = buildAgentRunTimelineEntries(<ChatMessageModel>[
      _assistantMessage(
        id: 'task-c-text',
        text: '答案',
        taskId: 'task-c',
        kind: 'text_snapshot',
        seq: 2,
      ),
      ChatMessageModel.userMessage('问题', id: 'user-c'),
    ]);
    expect(withNeither.first.group?.agentId, kGenericAgentId);
  });

  test('keeps permission card visible alongside final permission text', () {
    final messages = <ChatMessageModel>[
      _cardMessage(
        id: 'task-3-permission-card',
        taskId: 'task-3',
        kind: 'permission_required',
        seq: 31,
        cardData: <String, dynamic>{
          'type': 'permission_section',
          'requiredPermissionIds': const <String>['overlay'],
        },
      ),
      _assistantMessage(
        id: 'task-3-permission-text',
        text: '请先授权',
        taskId: 'task-3',
        kind: 'permission_required',
        seq: 30,
        isFinal: true,
      ),
      _thinkingCard(id: 'task-3-thinking', taskId: 'task-3', seq: 10),
    ];

    final entries = buildAgentRunTimelineEntries(messages);

    expect(entries, hasLength(1));
    expect(entries.single.group?.visibleMessagesNewestFirst, hasLength(2));
    expect(
      entries.single.group?.visibleMessagesNewestFirst.map(
        (message) => message.id,
      ),
      containsAll(<String>['task-3-permission-card', 'task-3-permission-text']),
    );
  });

  test('groups active codex request as the visible run message', () {
    final messages = <ChatMessageModel>[
      _codexRequestCard(id: 'turn-7-request', taskId: 'turn-7', seq: 12),
      ChatMessageModel.userMessage('需要选择方案', id: 'user-7'),
    ];

    final entries = buildAgentRunTimelineEntries(
      messages,
      activeTaskIds: const <String>{'turn-7'},
    );

    expect(entries, hasLength(2));
    expect(entries.first.group?.taskId, 'turn-7');
    expect(entries.first.group?.processMessagesNewestFirst, isEmpty);
    expect(
      entries.first.group?.visibleMessagesNewestFirst.single.id,
      'turn-7-request',
    );
    expect(entries.last.message?.id, 'user-7');
  });

  test('keeps codex request visible after thinking process cards', () {
    final messages = <ChatMessageModel>[
      _codexRequestCard(id: 'turn-8-request', taskId: 'turn-8', seq: 22),
      _thinkingCard(id: 'turn-8-thinking', taskId: 'turn-8', seq: 10),
      ChatMessageModel.userMessage('继续计划吗', id: 'user-8'),
    ];

    final entries = buildAgentRunTimelineEntries(
      messages,
      activeTaskIds: const <String>{'turn-8'},
    );

    final group = entries.first.group;
    expect(group?.taskId, 'turn-8');
    expect(group?.visibleMessagesNewestFirst.single.id, 'turn-8-request');
    expect(group?.processMessagesNewestFirst.single.id, 'turn-8-thinking');
  });

  test(
    'uses cancelled text as the visible body for a manually stopped run',
    () {
      final messages = <ChatMessageModel>[
        _assistantMessage(
          id: 'task-5-cancelled',
          text: '任务已取消',
          taskId: 'task-5',
          kind: 'text_snapshot',
          seq: 1000000000,
          isFinal: true,
        ),
        _thinkingCard(id: 'task-5-thinking', taskId: 'task-5', seq: 12),
      ];

      final entries = buildAgentRunTimelineEntries(messages);

      expect(entries, hasLength(1));
      expect(
        entries.single.group?.visibleMessagesNewestFirst.single.text,
        '任务已取消',
      );
      expect(
        entries.single.group?.processMessagesNewestFirst.single.id,
        'task-5-thinking',
      );
    },
  );

  test('orders a turn by arrival, not by stream sequence', () {
    final messages = <ChatMessageModel>[
      _assistantMessage(
        id: 'task-6-text-2',
        text: '任务已被手动停止。需要换一种方式发送吗？',
        taskId: 'task-6',
        kind: 'text_snapshot',
        seq: 105,
        entrySeq: 5,
        isFinal: true,
      ),
      _thinkingCard(
        id: 'task-6-thinking-2',
        taskId: 'task-6',
        seq: 104,
        entrySeq: 4,
      ),
      _cardMessage(
        id: 'task-6-tool-1',
        taskId: 'task-6',
        kind: 'tool_completed',
        seq: 69,
        entrySeq: 3,
        cardData: <String, dynamic>{
          'type': 'agent_tool_summary',
          'status': 'failed',
          'toolType': 'terminal_execute',
          'toolTitle': '执行命令',
          'summary': '命令执行失败',
        },
      ),
      ChatMessageModel(
        id: 'task-6-text',
        type: 1,
        user: 2,
        content: <String, dynamic>{'id': 'task-6-text', 'text': '好的，我来执行这个命令。'},
      ),
      _thinkingCard(
        id: 'task-6-thinking',
        taskId: 'task-6',
        seq: 70,
        entrySeq: 1,
      ),
      ChatMessageModel.userMessage('用户问题', id: 'user-6'),
    ];

    final entries = buildAgentRunTimelineEntries(messages);

    expect(entries, hasLength(2));
    final group = entries.first.group;
    expect(group?.taskId, 'task-6');
    // `task-6-text` carries no streamMeta at all, so it can only be placed by
    // where it sits in the list.
    expect(
      group?.visibleMessagesOldestFirst.map((message) => message.id),
      <String>['task-6-text', 'task-6-text-2'],
    );
    expect(
      group?.processMessagesOldestFirst.map((message) => message.id),
      <String>['task-6-thinking', 'task-6-tool-1', 'task-6-thinking-2'],
    );
    expect(entries.last.message?.id, 'user-6');
  });

  test(
    'restores Xiaowan prompt before its completed run from an oldest-first snapshot',
    () {
      const timestamp = '1786765190269';
      const taskId = '$timestamp-ai';
      final messages = <ChatMessageModel>[
        ChatMessageModel(
          id: '$timestamp-user',
          type: 1,
          user: 1,
          content: const <String, dynamic>{
            'id': '$timestamp-user',
            'text': '怎么登录 GitHub？',
          },
        ),
        _thinkingCard(
          id: '$taskId-thinking',
          taskId: taskId,
          seq: 44,
          entrySeq: 1,
        ),
        _cardMessage(
          id: '$taskId-tool-1',
          taskId: taskId,
          kind: 'tool_completed',
          seq: 47,
          entrySeq: 2,
          cardData: _toolCard('context_apps_query'),
        ),
        _thinkingCard(
          id: '$taskId-thinking-2',
          taskId: taskId,
          seq: 52,
          entrySeq: 3,
        ),
        _assistantMessage(
          id: '$taskId-text',
          text: '你手机上已经装了 GitHub 官方 App。',
          taskId: taskId,
          kind: 'text_snapshot',
          seq: 252,
          entrySeq: 4,
          isFinal: true,
        ),
      ];

      final entries = buildAgentRunTimelineEntries(messages);

      expect(entries, hasLength(2));
      expect(entries.first.group?.taskId, taskId);
      expect(entries.last.message?.id, '$timestamp-user');
      expect(
        entries.first.group?.allMessagesOldestFirst.map(
          (message) => message.id,
        ),
        <String>[
          '$taskId-thinking',
          '$taskId-tool-1',
          '$taskId-thinking-2',
          '$taskId-text',
        ],
      );
    },
  );

  test(
    'orders persisted ACP terminal frames by seq when entrySeq is absent',
    () {
      final taskId = 'persisted-acp-turn';
      final messages = <ChatMessageModel>[
        _cardMessage(
          id: '$taskId-tool',
          taskId: taskId,
          kind: 'tool_completed',
          seq: 2,
          isFinal: true,
          cardData: _toolCard('读取工具结果'),
        ),
        _thinkingCard(
          id: '$taskId-thinking',
          taskId: taskId,
          seq: 1,
          isFinal: true,
        ),
        _assistantMessage(
          id: '$taskId-text',
          taskId: taskId,
          kind: 'text_snapshot',
          seq: 3,
          text: '最终回答',
          isFinal: true,
        ),
      ];

      final group = buildAgentRunTimelineEntries(messages).single.group!;

      expect(
        group.allMessagesOldestFirst.map((message) => message.id),
        <String>['$taskId-thinking', '$taskId-tool', '$taskId-text'],
      );
    },
  );

  test(
    'restores partially sequenced Xiaowan prose inside its chronological tool rounds',
    () {
      const timestamp = '1786765957366';
      const taskId = '$timestamp-ai';
      final base = DateTime.fromMillisecondsSinceEpoch(1786765957000);
      DateTime at(int milliseconds) =>
          base.add(Duration(milliseconds: milliseconds));

      ChatMessageModel missingMetaText(String id, String text, int createdAt) {
        return ChatMessageModel(
          id: id,
          type: 1,
          user: 2,
          content: <String, dynamic>{'id': id, 'text': text},
          streamMeta: <String, dynamic>{'entryId': id, 'isFinal': false},
          createAt: at(createdAt),
        );
      }

      // This is the bad display shape returned by the installed debug build:
      // the three entries whose stable metadata was stripped are placed ahead
      // of every sequenced entry, even though their creation times belong in
      // the middle of the run.
      final messages = <ChatMessageModel>[
        missingMetaText('$taskId-text-7', '诊断完成', 700),
        missingMetaText('$taskId-text-6', '安装 ssh 客户端', 500),
        missingMetaText('$taskId-text-5', '验证 SSH 握手', 300),
        _assistantMessage(
          id: '$taskId-text-8',
          text: '最终环境诊断结果',
          taskId: taskId,
          kind: 'text_snapshot',
          seq: 839,
          entrySeq: 11,
          isFinal: true,
        ).copyWith(createAt: at(900)),
        _thinkingCard(
          id: '$taskId-thinking-9',
          taskId: taskId,
          seq: 444,
          entrySeq: 10,
        ).copyWith(createAt: at(800)),
        _cardMessage(
          id: '$taskId-tool-8',
          taskId: taskId,
          kind: 'tool_completed',
          seq: 441,
          entrySeq: 9,
          cardData: _toolCard('memory_write_daily'),
        ).copyWith(createAt: at(750)),
        _thinkingCard(
          id: '$taskId-thinking-8',
          taskId: taskId,
          seq: 422,
          entrySeq: 8,
        ).copyWith(createAt: at(600)),
        _cardMessage(
          id: '$taskId-tool-6',
          taskId: taskId,
          kind: 'tool_completed',
          seq: 383,
          entrySeq: 7,
          cardData: _toolCard('terminal_execute'),
        ).copyWith(createAt: at(550)),
        _thinkingCard(
          id: '$taskId-thinking-6',
          taskId: taskId,
          seq: 357,
          entrySeq: 6,
        ).copyWith(createAt: at(400)),
        _cardMessage(
          id: '$taskId-tool-5',
          taskId: taskId,
          kind: 'tool_completed',
          seq: 352,
          entrySeq: 5,
          cardData: _toolCard('terminal_execute'),
        ).copyWith(createAt: at(350)),
        _thinkingCard(
          id: '$taskId-thinking-5',
          taskId: taskId,
          seq: 307,
          entrySeq: 4,
        ).copyWith(createAt: at(200)),
      ];

      final group = buildAgentRunTimelineEntries(messages).single.group!;

      expect(
        group.allMessagesOldestFirst.map((message) => message.id),
        <String>[
          '$taskId-thinking-5',
          '$taskId-text-5',
          '$taskId-tool-5',
          '$taskId-thinking-6',
          '$taskId-text-6',
          '$taskId-tool-6',
          '$taskId-thinking-8',
          '$taskId-text-7',
          '$taskId-tool-8',
          '$taskId-thinking-9',
          '$taskId-text-8',
        ],
      );
    },
  );

  test('restores multiple legacy Xiaowan turns newest-first', () {
    const firstTimestamp = '1786765116611';
    const secondTimestamp = '1786765190269';
    final messages = <ChatMessageModel>[
      ChatMessageModel.userMessage('第一问', id: '$firstTimestamp-user'),
      _assistantMessage(
        id: '$firstTimestamp-ai-text',
        text: '第一答',
        taskId: '$firstTimestamp-ai',
        kind: 'text_snapshot',
        seq: 10,
        entrySeq: 1,
        isFinal: true,
      ),
      ChatMessageModel.userMessage('第二问', id: '$secondTimestamp-user'),
      _assistantMessage(
        id: '$secondTimestamp-ai-text',
        text: '第二答',
        taskId: '$secondTimestamp-ai',
        kind: 'text_snapshot',
        seq: 20,
        entrySeq: 1,
        isFinal: true,
      ),
    ];

    final entries = buildAgentRunTimelineEntries(messages);

    expect(entries.map((entry) => entry.key), <String>[
      'agent-run-$secondTimestamp-ai',
      '$secondTimestamp-user',
      'agent-run-$firstTimestamp-ai',
      '$firstTimestamp-user',
    ]);
  });

  test('interleaved tool batches stay separate around agent prose', () {
    // Regression for on-device conversation 60. codex-acp narrates, runs a
    // batch of tools, narrates again, runs more. Hoisting the newest prose
    // message to the bottom of the group showed the first paragraph *below*
    // the tools it preceded and left the two tool batches adjacent so they
    // merged into one card. The complete trace must keep its arrival order
    // when the user expands the finished run.
    final messages = <ChatMessageModel>[
      _cardMessage(
        id: 'exec-4-agent-command',
        taskId: 'turn-60',
        kind: 'tool_completed',
        seq: 5,
        cardData: _toolCard('sed'),
      ),
      _cardMessage(
        id: 'exec-3-agent-command',
        taskId: 'turn-60',
        kind: 'tool_completed',
        seq: 4,
        cardData: _toolCard('grep'),
      ),
      _assistantMessage(
        id: 'msg-2-agent-message',
        text: '第二段正文',
        taskId: 'turn-60',
        kind: 'text_snapshot',
        seq: 3,
        isFinal: false,
      ),
      _cardMessage(
        id: 'exec-2-agent-command',
        taskId: 'turn-60',
        kind: 'tool_completed',
        seq: 2,
        cardData: _toolCard('ls'),
      ),
      _assistantMessage(
        id: 'msg-1-agent-message',
        text: '第一段正文',
        taskId: 'turn-60',
        kind: 'text_snapshot',
        seq: 1,
        isFinal: false,
      ),
      ChatMessageModel.userMessage('问题', id: 'user-60'),
    ];

    final group = buildAgentRunTimelineEntries(messages).first.group;

    expect(
      group?.segmentsOldestFirst.map(
        (segment) => segment.messages.map((message) => message.id).toList(),
      ),
      <List<String>>[
        <String>['msg-1-agent-message'],
        <String>['exec-2-agent-command'],
        <String>['msg-2-agent-message'],
        <String>['exec-3-agent-command', 'exec-4-agent-command'],
      ],
    );
    // Two folds, not one: prose between the batches keeps them apart.
    expect(
      group?.segmentsOldestFirst.where((segment) => segment.isProcess),
      hasLength(2),
    );
  });
}

List<ChatMessageModel> _buildCompletedRunMessages({bool isFinal = true}) {
  return <ChatMessageModel>[
    _assistantMessage(
      id: 'task-1-text',
      text: '最终回答',
      taskId: 'task-1',
      kind: 'text_snapshot',
      seq: 30,
      isFinal: isFinal,
    ),
    _cardMessage(
      id: 'task-1-tool',
      taskId: 'task-1',
      kind: 'tool_completed',
      seq: 20,
      cardData: <String, dynamic>{
        'type': 'agent_tool_summary',
        'status': 'success',
        'toolType': 'workspace',
        'toolTitle': '读取配置文件',
        'summary': '配置读取完成',
      },
    ),
    _thinkingCard(id: 'task-1-thinking', taskId: 'task-1', seq: 10),
    ChatMessageModel.userMessage('用户问题', id: 'user-1'),
  ];
}

ChatMessageModel _assistantMessage({
  required String id,
  required String text,
  required String taskId,
  required String kind,
  required int seq,
  int? entrySeq,
  bool? isFinal = false,
  String? agentId,
}) {
  return ChatMessageModel(
    id: id,
    type: 1,
    user: 2,
    content: <String, dynamic>{
      'text': text,
      'id': id,
      if (agentId != null) 'agentId': agentId,
    },
    streamMeta: <String, dynamic>{
      'parentTaskId': taskId,
      'kind': kind,
      'seq': seq,
      if (entrySeq != null) 'entrySeq': entrySeq,
      'entryId': id,
      if (isFinal != null) 'isFinal': isFinal,
    },
  );
}

Map<String, dynamic> _toolCard(String title) {
  return <String, dynamic>{
    'type': 'agent_tool_summary',
    'status': 'success',
    'toolType': 'terminal',
    'toolTitle': title,
  };
}

ChatMessageModel _thinkingCard({
  required String id,
  required String taskId,
  required int seq,
  int? entrySeq,
  bool? isFinal = false,
}) {
  return _cardMessage(
    id: id,
    taskId: taskId,
    kind: 'thinking_snapshot',
    seq: seq,
    entrySeq: entrySeq,
    isFinal: isFinal,
    cardData: <String, dynamic>{
      'type': 'deep_thinking',
      'thinkingContent': '思考过程',
      'stage': 4,
      'isLoading': false,
      'taskID': taskId,
      'cardId': id,
    },
  );
}

ChatMessageModel _codexRequestCard({
  required String id,
  required String taskId,
  required int seq,
}) {
  return _cardMessage(
    id: id,
    taskId: taskId,
    kind: 'clarify_required',
    seq: seq,
    cardData: <String, dynamic>{
      'type': 'codex_request',
      'taskId': taskId,
      'cardId': id,
      'requestId': id,
      'requestKind': 'user_input',
      'status': 'pending',
    },
  );
}

ChatMessageModel _cardMessage({
  required String id,
  required String taskId,
  required String kind,
  required int seq,
  int? entrySeq,
  bool? isFinal = false,
  required Map<String, dynamic> cardData,
}) {
  return ChatMessageModel.cardMessage(
    cardData,
    id: id,
    streamMeta: <String, dynamic>{
      'parentTaskId': taskId,
      'kind': kind,
      'seq': seq,
      if (entrySeq != null) 'entrySeq': entrySeq,
      'entryId': id,
      if (isFinal != null) 'isFinal': isFinal,
    },
  );
}
