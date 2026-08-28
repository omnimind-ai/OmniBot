import 'dart:convert';
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/tool_activity_utils.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_tool_activity_strip.dart';
import 'package:ui/features/home/pages/command_overlay/services/tool_card_detail_gesture_gate.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/chat_message_model.dart';

void main() {
  setUp(() {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
  });

  tearDown(() {
    LegacyTextLocalizer.clearResolvedLocale();
  });

  testWidgets('command strip renders all commands without expand toggle', (
    tester,
  ) async {
    Map<String, dynamic>? selectedCommand;
    final commands = [
      {
        'cardId': 'slash-command-compact',
        'toolTitle': '/compact',
        'toolType': 'command',
        'toolTypeLabel': '上下文',
        'status': 'running',
        'statusLabel': '命令',
        'summary': '手动压缩',
      },
      {
        'cardId': 'slash-command-effort',
        'toolTitle': '/effort',
        'toolType': 'command',
        'toolTypeLabel': '思考',
        'status': 'success',
        'statusLabel': 'low',
        'controlType': 'effortSlider',
        'effortOptions': const ['no', 'low', 'high', 'xhigh', 'max'],
        'selectedEffort': 'low',
      },
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ChatCommandActivityStrip(
            commands: commands,
            onSelectCommand: (command) => selectedCommand = command,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('compact'), findsOneWidget);
    expect(find.text('effort'), findsOneWidget);
    expect(
      find.byKey(
        const ValueKey<String>(
          '$kChatCommandEffortSliderKeyPrefix-slash-command-effort',
        ),
      ),
      findsOneWidget,
    );
    expect(find.text('xhigh'), findsOneWidget);
    expect(find.text('max'), findsOneWidget);
    expect(find.text('low'), findsOneWidget);
    expect(find.text('设置思考强度'), findsNothing);
    expect(find.byKey(kChatToolActivityToggleKey), findsNothing);

    expect(
      tester.getCenter(find.text('xhigh')).dx,
      lessThan(tester.getCenter(find.text('max')).dx),
    );

    await tester.tap(find.text('xhigh'));
    await tester.pumpAndSettle();

    expect(selectedCommand?['toolTitle'], 'xhigh');
  });

  testWidgets('command strip renders plan command as clickable toggle', (
    tester,
  ) async {
    Map<String, dynamic>? selectedCommand;
    final commands = [
      {
        'cardId': 'slash-command-agent-model',
        'toolTitle': '/model',
        'toolType': 'command',
        'toolTypeLabel': '模型',
        'status': 'running',
        'statusLabel': '命令',
        'summary': '选择 Codex 模型',
      },
      {
        'cardId': 'slash-command-agent-plan',
        'toolTitle': '/plan',
        'toolType': 'command',
        'toolTypeLabel': '计划',
        'status': 'success',
        'statusLabel': '已选',
        'summary': '当前已启用 Plan 模式',
        'isToggle': true,
        'toggleValue': true,
      },
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ChatCommandActivityStrip(
            commands: commands,
            onSelectCommand: (command) => selectedCommand = command,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final planToggleKey = ValueKey<String>(
      '$kChatCommandToggleKeyPrefix-slash-command-agent-plan',
    );
    expect(find.byKey(planToggleKey), findsOneWidget);
    expect(find.text('/chat'), findsNothing);

    await tester.tap(find.byKey(planToggleKey));
    await tester.pumpAndSettle();

    expect(selectedCommand?['toolTitle'], '/plan');
  });

  test(
    'filters tool messages by active task ids while keeping completed cards',
    () {
      final messages = [
        ChatMessageModel.cardMessage(
          {
            'type': 'agent_tool_summary',
            'taskId': 'task-active',
            'status': 'success',
            'toolTitle': '已完成工具',
          },
          id: 'tool-complete',
          streamMeta: const {'parentTaskId': 'task-active'},
        ),
        ChatMessageModel.cardMessage(
          {
            'type': 'agent_tool_summary',
            'taskId': 'task-active',
            'status': 'running',
            'toolTitle': '运行中工具',
          },
          id: 'tool-running',
          streamMeta: const {'parentTaskId': 'task-active'},
        ),
        ChatMessageModel.cardMessage(
          {
            'type': 'agent_tool_summary',
            'taskId': 'task-old',
            'status': 'success',
            'toolTitle': '旧任务工具',
          },
          id: 'tool-old',
          streamMeta: const {'parentTaskId': 'task-old'},
        ),
      ];

      final filtered = filterAgentToolMessagesByTaskIds(messages, const {
        'task-active',
      });

      expect(filtered.map((message) => message.id), [
        'tool-complete',
        'tool-running',
      ]);
    },
  );

  test('completed run keeps latest tool history pinned after folding', () {
    final messages = [
      ChatMessageModel(
        id: 'task-latest-text',
        type: 1,
        user: 2,
        content: const {'text': '最终回答', 'id': 'task-latest-text'},
        streamMeta: const {
          'parentTaskId': 'task-latest',
          'kind': 'completed',
          'isFinal': true,
          'seq': 4,
        },
      ),
      ChatMessageModel.cardMessage(
        {
          'type': 'agent_tool_summary',
          'taskId': 'task-latest',
          'status': 'success',
          'toolTitle': '最新工具',
        },
        id: 'task-latest-tool',
        streamMeta: const {
          'parentTaskId': 'task-latest',
          'kind': 'tool_completed',
          'seq': 3,
        },
      ),
      ChatMessageModel.cardMessage(
        {
          'type': 'deep_thinking',
          'taskID': 'task-latest',
          'thinkingContent': '思考中',
        },
        id: 'task-latest-thinking',
        streamMeta: const {
          'parentTaskId': 'task-latest',
          'kind': 'thinking_snapshot',
          'seq': 2,
        },
      ),
      ChatMessageModel.userMessage('上一条用户消息', id: 'user-1'),
    ];

    final snapshot = resolveAgentToolActivitySnapshot(messages);

    expect(snapshot.isActiveRun, isFalse);
    expect(snapshot.taskId, 'task-latest');
    expect(snapshot.messages.map((message) => message.id), [
      'task-latest-tool',
    ]);
  });

  test(
    'completed tool history stays hidden until the matching run group expands',
    () {
      final messages = [
        ChatMessageModel(
          id: 'task-latest-text',
          type: 1,
          user: 2,
          content: const {'text': '最终回答', 'id': 'task-latest-text'},
          streamMeta: const {
            'parentTaskId': 'task-latest',
            'kind': 'completed',
            'isFinal': true,
            'seq': 4,
          },
        ),
        ChatMessageModel.cardMessage(
          {
            'type': 'agent_tool_summary',
            'taskId': 'task-latest',
            'status': 'success',
            'toolTitle': '最新工具',
          },
          id: 'task-latest-tool',
          streamMeta: const {
            'parentTaskId': 'task-latest',
            'kind': 'tool_completed',
            'seq': 3,
          },
        ),
      ];

      final snapshot = resolveAgentToolActivitySnapshot(messages);

      expect(shouldShowAgentToolActivitySnapshot(snapshot), isFalse);
      expect(
        shouldShowAgentToolActivitySnapshot(
          snapshot,
          expandedTaskIds: const {'task-latest'},
        ),
        isTrue,
      );
      expect(
        shouldShowAgentToolActivitySnapshot(
          snapshot,
          expandedTaskIds: const {'task-other'},
        ),
        isFalse,
      );
    },
  );

  test(
    'preferred expanded run shows its own tool history instead of latest run',
    () {
      final messages = [
        ChatMessageModel.userMessage('更新的用户问题', id: 'user-latest'),
        ChatMessageModel(
          id: 'task-latest-text',
          type: 1,
          user: 2,
          content: const {'text': '最新回答', 'id': 'task-latest-text'},
          streamMeta: const {
            'parentTaskId': 'task-latest',
            'kind': 'completed',
            'isFinal': true,
            'seq': 8,
          },
        ),
        ChatMessageModel.cardMessage(
          {
            'type': 'agent_tool_summary',
            'taskId': 'task-latest',
            'status': 'success',
            'toolTitle': '最新工具',
          },
          id: 'task-latest-tool',
          streamMeta: const {
            'parentTaskId': 'task-latest',
            'kind': 'tool_completed',
            'seq': 7,
          },
        ),
        ChatMessageModel(
          id: 'task-older-text',
          type: 1,
          user: 2,
          content: const {'text': '更早回答', 'id': 'task-older-text'},
          streamMeta: const {
            'parentTaskId': 'task-older',
            'kind': 'completed',
            'isFinal': true,
            'seq': 4,
          },
        ),
        ChatMessageModel.cardMessage(
          {
            'type': 'agent_tool_summary',
            'taskId': 'task-older',
            'status': 'success',
            'toolTitle': '更早工具',
          },
          id: 'task-older-tool',
          streamMeta: const {
            'parentTaskId': 'task-older',
            'kind': 'tool_completed',
            'seq': 3,
          },
        ),
      ];

      final snapshot = resolveAgentToolActivitySnapshot(
        messages,
        preferredCompletedTaskId: 'task-older',
      );

      expect(snapshot.isActiveRun, isFalse);
      expect(snapshot.taskId, 'task-older');
      expect(snapshot.messages.map((message) => message.id), [
        'task-older-tool',
      ]);
      expect(
        shouldShowAgentToolActivitySnapshot(
          snapshot,
          expandedTaskIds: const {'task-older'},
        ),
        isTrue,
      );
    },
  );

  test('newer user turn clears pinned tool history from prior agent run', () {
    final messages = [
      ChatMessageModel.userMessage('新的用户问题', id: 'user-latest'),
      ChatMessageModel(
        id: 'task-latest-text',
        type: 1,
        user: 2,
        content: const {'text': '最终回答', 'id': 'task-latest-text'},
        streamMeta: const {
          'parentTaskId': 'task-latest',
          'kind': 'completed',
          'isFinal': true,
          'seq': 4,
        },
      ),
      ChatMessageModel.cardMessage(
        {
          'type': 'agent_tool_summary',
          'taskId': 'task-latest',
          'status': 'success',
          'toolTitle': '最新工具',
        },
        id: 'task-latest-tool',
        streamMeta: const {
          'parentTaskId': 'task-latest',
          'kind': 'tool_completed',
          'seq': 3,
        },
      ),
    ];

    final snapshot = resolveAgentToolActivitySnapshot(messages);

    expect(snapshot.isActiveRun, isFalse);
    expect(snapshot.messages, isEmpty);
  });

  test(
    'active run without tool cards does not reuse previous tool history',
    () {
      final messages = [
        ChatMessageModel(
          id: 'task-active-text',
          type: 1,
          user: 2,
          content: const {'text': '新的回复', 'id': 'task-active-text'},
          streamMeta: const {
            'parentTaskId': 'task-active',
            'kind': 'text_snapshot',
            'seq': 10,
            'isFinal': false,
          },
        ),
        ChatMessageModel.cardMessage(
          {
            'type': 'deep_thinking',
            'taskID': 'task-active',
            'thinkingContent': '思考中',
          },
          id: 'task-active-thinking',
          streamMeta: const {
            'parentTaskId': 'task-active',
            'kind': 'thinking_snapshot',
            'seq': 9,
          },
        ),
        ChatMessageModel.cardMessage(
          {
            'type': 'agent_tool_summary',
            'taskId': 'task-old',
            'status': 'success',
            'toolTitle': '旧工具',
          },
          id: 'task-old-tool',
          streamMeta: const {
            'parentTaskId': 'task-old',
            'kind': 'tool_completed',
            'seq': 3,
          },
        ),
        ChatMessageModel(
          id: 'task-old-text',
          type: 1,
          user: 2,
          content: const {'text': '旧回答', 'id': 'task-old-text'},
          streamMeta: const {
            'parentTaskId': 'task-old',
            'kind': 'completed',
            'isFinal': true,
            'seq': 4,
          },
        ),
      ];

      final snapshot = resolveAgentToolActivitySnapshot(
        messages,
        activeTaskIds: const {'task-active'},
      );

      expect(snapshot.isActiveRun, isTrue);
      expect(snapshot.messages, isEmpty);
      expect(shouldShowAgentToolActivitySnapshot(snapshot), isFalse);
    },
  );

  test('active tool history remains visible without requiring expansion', () {
    final messages = [
      ChatMessageModel.cardMessage(
        {
          'type': 'agent_tool_summary',
          'taskId': 'task-active',
          'status': 'running',
          'toolTitle': '运行中工具',
        },
        id: 'task-active-tool',
        streamMeta: const {
          'parentTaskId': 'task-active',
          'kind': 'tool_running',
          'seq': 2,
        },
      ),
    ];

    final snapshot = resolveAgentToolActivitySnapshot(
      messages,
      activeTaskIds: const {'task-active'},
    );

    expect(snapshot.isActiveRun, isTrue);
    expect(snapshot.taskId, 'task-active');
    expect(shouldShowAgentToolActivitySnapshot(snapshot), isTrue);
  });

  testWidgets(
    'renders current tool title and expands history without duplicating current item',
    (tester) async {
      final messages = [
        ChatMessageModel.cardMessage({
          'type': 'agent_tool_summary',
          'status': 'running',
          'toolType': 'terminal',
          'toolTitle': '检查 git 状态',
          'summary': '终端正在运行',
          'terminalOutput': 'git status\nOn branch main',
        }),
        ChatMessageModel.cardMessage({
          'type': 'agent_tool_summary',
          'status': 'success',
          'toolType': 'workspace',
          'toolTitle': '读取配置文件',
          'summary': '已读取 app.yaml',
        }),
      ];

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(body: ChatToolActivityStrip(messages: messages)),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(kChatToolActivityBarKey), findsOneWidget);
      expect(find.text('检查 git 状态'), findsOneWidget);

      await tester.tap(find.byKey(kChatToolActivityToggleKey));
      await tester.pumpAndSettle();

      expect(find.byKey(kChatToolActivityPanelKey), findsOneWidget);
      expect(find.byKey(kChatToolActivityPreviewKey), findsNothing);
      expect(find.byType(ToolActivityRow), findsNWidgets(2));
      expect(find.text('检查 git 状态'), findsOneWidget);
      expect(find.text('读取配置文件'), findsOneWidget);

      final rowHeight = tester
          .getSize(find.byType(ToolActivityRow).first)
          .height;
      final historyRowHeight = tester
          .getSize(find.byType(ToolActivityRow).last)
          .height;
      final terminalTypeRight = tester.getTopRight(find.text('终端')).dx;
      final workspaceTypeRight = tester.getTopRight(find.text('工作区')).dx;
      final runningTagRight = tester.getTopRight(find.text('运行中')).dx;
      final successTagRight = tester.getTopRight(find.text('成功')).dx;

      expect(rowHeight, closeTo(32, 0.1));
      expect(historyRowHeight, closeTo(rowHeight, 0.1));
      expect(terminalTypeRight, closeTo(workspaceTypeRight, 1));
      expect(runningTagRight, closeTo(successTagRight, 1));
    },
  );

  testWidgets('running-only strip hides completed tool cards entirely', (
    tester,
  ) async {
    final messages = [
      ChatMessageModel.cardMessage({
        'type': 'agent_tool_summary',
        'status': 'success',
        'toolType': 'terminal',
        'toolTitle': '已完成的工具',
        'summary': '终端执行完成',
      }),
      ChatMessageModel.cardMessage({
        'type': 'agent_tool_summary',
        'status': 'timeout',
        'toolType': 'browser',
        'toolTitle': '已结束的浏览器工具',
        'summary': '页面等待超时',
      }),
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ChatToolActivityStrip(messages: messages, runningOnly: true),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(kChatToolActivityBarKey), findsNothing);
    expect(find.byKey(kChatToolActivityPreviewKey), findsNothing);
    expect(find.byKey(kChatToolActivityPanelKey), findsNothing);
  });

  testWidgets(
    'expanded history stacks previous calls by recency from bottom to top',
    (tester) async {
      final messages = [
        ChatMessageModel.cardMessage({
          'type': 'agent_tool_summary',
          'status': 'running',
          'toolType': 'terminal',
          'toolTitle': '当前调用',
          'summary': '正在执行当前调用',
        }),
        ChatMessageModel.cardMessage({
          'type': 'agent_tool_summary',
          'status': 'success',
          'toolType': 'workspace',
          'toolTitle': '最近一条',
          'summary': '最近一条结果',
        }),
        ChatMessageModel.cardMessage({
          'type': 'agent_tool_summary',
          'status': 'success',
          'toolType': 'browser',
          'toolTitle': '最近第二条',
          'summary': '最近第二条结果',
        }),
        ChatMessageModel.cardMessage({
          'type': 'agent_tool_summary',
          'status': 'success',
          'toolType': 'workspace',
          'toolTitle': '最早一条',
          'summary': '最早一条结果',
        }),
      ];

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(body: ChatToolActivityStrip(messages: messages)),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kChatToolActivityToggleKey));
      await tester.pumpAndSettle();

      final currentY = tester.getCenter(find.text('当前调用')).dy;
      final recentY = tester.getCenter(find.text('最近一条')).dy;
      final secondRecentY = tester.getCenter(find.text('最近第二条')).dy;
      final earliestY = tester.getCenter(find.text('最早一条')).dy;

      expect(currentY, greaterThan(recentY));
      expect(recentY, greaterThan(secondRecentY));
      expect(secondRecentY, greaterThan(earliestY));
    },
  );

  testWidgets(
    'expanded scrollable history opens anchored to the most recent items',
    (tester) async {
      final messages = [
        ChatMessageModel.cardMessage({
          'type': 'agent_tool_summary',
          'status': 'running',
          'toolType': 'terminal',
          'toolTitle': '当前调用',
          'summary': '当前调用结果',
        }),
        for (final title in const [
          '最近一条',
          '最近第二条',
          '最近第三条',
          '最近第四条',
          '最近第五条',
          '最早第二条',
          '最早一条',
        ])
          ChatMessageModel.cardMessage({
            'type': 'agent_tool_summary',
            'status': 'success',
            'toolType': 'workspace',
            'toolTitle': title,
            'summary': '$title 结果',
          }),
      ];

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(body: ChatToolActivityStrip(messages: messages)),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(kChatToolActivityToggleKey));
      await tester.pumpAndSettle();

      expect(find.text('最近一条').hitTestable(), findsOneWidget);
      expect(find.text('最近第二条').hitTestable(), findsOneWidget);
      expect(find.text('最早一条').hitTestable(), findsNothing);
      expect(find.text('最早第二条').hitTestable(), findsNothing);
    },
  );

  testWidgets('thumbnail opens current tool detail only', (tester) async {
    final messages = [
      ChatMessageModel.cardMessage({
        'type': 'agent_tool_summary',
        'status': 'success',
        'toolType': 'terminal',
        'toolName': 'terminal_execute',
        'toolTitle': '查看日志',
        'summary': '终端执行完成',
        'argsJson': jsonEncode({
          'command': 'tail -n 2 app.log',
          'workingDirectory': '/workspace',
        }),
        'terminalOutput': 'line 1\nline 2',
      }),
      ChatMessageModel.cardMessage({
        'type': 'agent_tool_summary',
        'status': 'success',
        'toolType': 'browser',
        'toolName': 'browser_use',
        'toolTitle': '打开官网',
        'summary': '页面已加载',
        'argsJson': jsonEncode({'url': 'https://omnimind.ai'}),
      }),
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: ChatToolActivityStrip(messages: messages)),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.descendant(
        of: find.byKey(kChatToolActivityPreviewKey),
        matching: find.text(r'$ cd /workspace && tail -n 2 app.log'),
      ),
      findsOneWidget,
    );

    await tester.tap(find.byKey(kChatToolActivityPreviewKey));
    await tester.pumpAndSettle();

    final sheet = find.byKey(kAgentToolDetailSheetKey);

    expect(
      find.descendant(of: sheet, matching: find.text('查看日志')),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: sheet,
        matching: find.textContaining('tail -n 2 app.log', findRichText: true),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: sheet,
        matching: find.textContaining('line 2', findRichText: true),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(of: sheet, matching: find.text('打开官网')),
      findsNothing,
    );
    expect(
      find.descendant(of: sheet, matching: find.text('页面已加载')),
      findsNothing,
    );
    expect(find.byIcon(Icons.close_rounded), findsNothing);
    expect(
      find.descendant(of: sheet, matching: find.text('终端')),
      findsOneWidget,
    );
    expect(
      find.descendant(of: sheet, matching: find.text('成功')),
      findsOneWidget,
    );
    expect(
      find.descendant(of: sheet, matching: find.textContaining('终端 · 成功')),
      findsNothing,
    );
  });

  testWidgets('Claude tool detail hides legacy Codex command namespace', (
    tester,
  ) async {
    final messages = [
      ChatMessageModel.cardMessage({
        'type': 'agent_tool_summary',
        'uiStyle': 'codex_tool',
        'agentId': 'claude-code-acp',
        'agentName': 'Claude Code',
        'status': 'success',
        'toolType': 'workspace',
        'toolName': 'codex.tool',
        'toolTitle': 'Read settings.json',
        'summary': '读取完成',
        'argsJson': jsonEncode({
          'id': 'tool-call-42',
          'path': '/root/.claude/settings.json',
        }),
        'resultPreviewJson': jsonEncode({'status': 'ok'}),
      }),
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ChatToolActivityStrip(
            messages: messages,
            showPreviewThumbnail: false,
            openActiveCardOnTap: true,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('Read settings.json'));
    await tester.pumpAndSettle();

    final sheet = find.byKey(kAgentToolDetailSheetKey);
    expect(sheet, findsOneWidget);
    expect(
      find.descendant(
        of: sheet,
        matching: find.textContaining(
          'Claude Code · Read settings.json',
          findRichText: true,
        ),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: sheet,
        matching: find.textContaining('codex.tool', findRichText: true),
      ),
      findsNothing,
    );
    expect(
      find.descendant(
        of: sheet,
        matching: find.textContaining('--id', findRichText: true),
      ),
      findsNothing,
    );
  });

  testWidgets(
    'completed tool strip hides thumbnail and opens detail from row',
    (tester) async {
      final messages = [
        ChatMessageModel.cardMessage({
          'type': 'agent_tool_summary',
          'status': 'success',
          'toolType': 'terminal',
          'toolName': 'terminal_execute',
          'toolTitle': '查看日志',
          'summary': '终端执行完成',
          'argsJson': jsonEncode({
            'command': 'tail -n 2 app.log',
            'workingDirectory': '/workspace',
          }),
          'terminalOutput': 'line 1\nline 2',
        }),
        ChatMessageModel.cardMessage({
          'type': 'agent_tool_summary',
          'status': 'success',
          'toolType': 'browser',
          'toolName': 'browser_use',
          'toolTitle': '打开官网',
          'summary': '页面已加载',
          'argsJson': jsonEncode({'url': 'https://omnimind.ai'}),
        }),
      ];

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ChatToolActivityStrip(
              messages: messages,
              showPreviewThumbnail: false,
              openActiveCardOnTap: true,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.byKey(kChatToolActivityPreviewKey), findsNothing);

      await tester.tap(find.text('查看日志'));
      await tester.pumpAndSettle();

      final sheet = find.byKey(kAgentToolDetailSheetKey);

      expect(
        find.descendant(of: sheet, matching: find.text('查看日志')),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: sheet,
          matching: find.textContaining(
            'tail -n 2 app.log',
            findRichText: true,
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: sheet,
          matching: find.textContaining('line 2', findRichText: true),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets('running active tool shows stop button and taps stop only', (
    tester,
  ) async {
    var stopCalls = 0;
    var lastTaskId = '';
    var lastCardId = '';
    var expanded = false;
    final messages = [
      ChatMessageModel.cardMessage({
        'type': 'agent_tool_summary',
        'taskId': 'task-stop-1',
        'cardId': 'task-stop-1-tool-1',
        'status': 'running',
        'toolType': 'terminal',
        'toolTitle': '停止中的工具',
        'summary': '终端正在运行',
      }, id: 'task-stop-1-tool-1'),
      ChatMessageModel.cardMessage({
        'type': 'agent_tool_summary',
        'taskId': 'task-stop-1',
        'cardId': 'task-stop-1-tool-0',
        'status': 'success',
        'toolType': 'workspace',
        'toolTitle': '历史工具',
        'summary': '历史结果',
      }, id: 'task-stop-1-tool-0'),
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: StatefulBuilder(
          builder: (context, setState) {
            return Scaffold(
              body: ChatToolActivityStrip(
                messages: messages,
                expanded: expanded,
                onExpandedChanged: (value) => setState(() => expanded = value),
                onStopToolCall: (taskId, cardId) async {
                  stopCalls += 1;
                  lastTaskId = taskId;
                  lastCardId = cardId;
                  return true;
                },
              ),
            );
          },
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(kChatToolActivityStopKey), findsOneWidget);

    await tester.tap(find.byKey(kChatToolActivityStopKey));
    await tester.pump();

    expect(stopCalls, 1);
    expect(lastTaskId, 'task-stop-1');
    expect(lastCardId, 'task-stop-1-tool-1');
    expect(expanded, isFalse);
    expect(
      find.byKey(kChatToolActivityPreviewKey).hitTestable(),
      findsOneWidget,
    );
  });

  testWidgets('stop button stays disabled while stop request is pending', (
    tester,
  ) async {
    final completer = Completer<bool>();
    var stopCalls = 0;
    final messages = [
      ChatMessageModel.cardMessage({
        'type': 'agent_tool_summary',
        'taskId': 'task-stop-pending',
        'cardId': 'task-stop-pending-tool-1',
        'status': 'running',
        'toolType': 'terminal',
        'toolTitle': '等待停止',
        'summary': '终端正在运行',
      }, id: 'task-stop-pending-tool-1'),
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ChatToolActivityStrip(
            messages: messages,
            onStopToolCall: (_, __) {
              stopCalls += 1;
              return completer.future;
            },
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(kChatToolActivityStopKey));
    await tester.pump();
    await tester.tap(find.byKey(kChatToolActivityStopKey));
    await tester.pump();

    expect(stopCalls, 1);

    completer.complete(true);
    await tester.pumpAndSettle();
  });

  testWidgets('expanded history row opens its own tool detail', (tester) async {
    final messages = [
      ChatMessageModel.cardMessage({
        'type': 'agent_tool_summary',
        'status': 'success',
        'toolType': 'terminal',
        'toolName': 'terminal_execute',
        'toolTitle': '查看日志',
        'summary': '终端执行完成',
        'argsJson': jsonEncode({
          'command': 'tail -n 2 app.log',
          'workingDirectory': '/workspace',
        }),
        'terminalOutput': 'line 1\nline 2',
      }),
      ChatMessageModel.cardMessage({
        'type': 'agent_tool_summary',
        'status': 'success',
        'toolType': 'browser',
        'toolName': 'browser_use',
        'toolTitle': '打开官网',
        'summary': '页面已加载',
        'argsJson': jsonEncode({
          'url': 'https://omnimind.ai/docs',
          'query': 'docs',
        }),
        'resultPreviewJson': jsonEncode({
          'currentUrl': 'https://omnimind.ai/docs',
          'title': 'Omnimind Docs',
        }),
      }),
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: ChatToolActivityStrip(messages: messages)),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(kChatToolActivityToggleKey));
    await tester.pumpAndSettle();
    await tester.tap(find.text('打开官网'));
    await tester.pumpAndSettle();

    final sheet = find.byKey(kAgentToolDetailSheetKey);

    expect(
      find.descendant(of: sheet, matching: find.text('打开官网')),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: sheet,
        matching: find.textContaining(
          'browser_use --url https://omnimind.ai/docs --query docs',
          findRichText: true,
        ),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: sheet,
        matching: find.textContaining(
          'currentUrl: https://omnimind.ai/docs',
          findRichText: true,
        ),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: sheet,
        matching: find.textContaining(
          'title: Omnimind Docs',
          findRichText: true,
        ),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: sheet,
        matching: find.textContaining('line 2', findRichText: true),
      ),
      findsNothing,
    );
    expect(
      find.descendant(of: sheet, matching: find.text('浏览器')),
      findsOneWidget,
    );
    expect(
      find.descendant(of: sheet, matching: find.text('成功')),
      findsOneWidget,
    );
  });

  testWidgets('timeout thumbnail and detail sheet render dedicated status', (
    tester,
  ) async {
    final messages = [
      ChatMessageModel.cardMessage({
        'type': 'agent_tool_summary',
        'status': 'timeout',
        'toolType': 'terminal',
        'toolName': 'terminal_execute',
        'toolTitle': '等待超时',
        'summary': '终端命令等待超时，可能仍在后台继续运行。',
        'argsJson': jsonEncode({
          'command': 'sleep 10',
          'workingDirectory': '/workspace',
        }),
        'terminalOutput': 'still running',
      }),
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: ChatToolActivityStrip(messages: messages)),
      ),
    );
    await tester.pumpAndSettle();

    expect(
      find.descendant(
        of: find.byKey(kChatToolActivityPreviewKey),
        matching: find.text(r'$ cd /workspace && sleep 10'),
      ),
      findsOneWidget,
    );

    await tester.tap(find.byKey(kChatToolActivityPreviewKey));
    await tester.pumpAndSettle();

    final sheet = find.byKey(kAgentToolDetailSheetKey);

    expect(
      find.descendant(of: sheet, matching: find.text('超时')),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: sheet,
        matching: find.textContaining('sleep 10', findRichText: true),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: sheet,
        matching: find.textContaining('still running', findRichText: true),
      ),
      findsOneWidget,
    );
  });

  testWidgets('occupied height stays stable when expanding history', (
    tester,
  ) async {
    final messages = [
      ChatMessageModel.cardMessage({
        'type': 'agent_tool_summary',
        'status': 'running',
        'toolType': 'terminal',
        'toolTitle': '检查 git 状态',
        'summary': '终端正在运行',
        'terminalOutput': 'git status\nOn branch main',
      }),
      ChatMessageModel.cardMessage({
        'type': 'agent_tool_summary',
        'status': 'success',
        'toolType': 'workspace',
        'toolTitle': '读取配置文件',
        'summary': '已读取 app.yaml',
      }),
    ];
    final reportedHeights = <double>[];

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ChatToolActivityStrip(
            messages: messages,
            onOccupiedHeightChanged: reportedHeights.add,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final initialHeight = reportedHeights.single;

    await tester.tap(find.byKey(kChatToolActivityToggleKey));
    await tester.pumpAndSettle();

    expect(reportedHeights.last, closeTo(initialHeight, 0.1));
    expect(reportedHeights.length, 1);
  });

  testWidgets(
    'thumbnail overlays the bar on the left and the bar is slightly narrower',
    (tester) async {
      final messages = [
        ChatMessageModel.cardMessage({
          'type': 'agent_tool_summary',
          'status': 'running',
          'toolType': 'terminal',
          'toolTitle': '检查 git 状态',
          'summary': '终端正在运行',
          'terminalOutput': 'git status\nOn branch main',
        }),
        ChatMessageModel.cardMessage({
          'type': 'agent_tool_summary',
          'status': 'success',
          'toolType': 'workspace',
          'toolTitle': '读取配置文件',
          'summary': '已读取 app.yaml',
        }),
      ];

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Stack(
              children: [
                Positioned(
                  left: 52,
                  bottom: 24,
                  width: 280,
                  child: ChatToolActivityStrip(
                    messages: messages,
                    anchorRect: const Rect.fromLTWH(52, 0, 280, 0),
                  ),
                ),
              ],
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      final previewTopLeft = tester.getTopLeft(
        find.byKey(kChatToolActivityPreviewKey),
      );
      final previewTopRight = tester.getTopRight(
        find.byKey(kChatToolActivityPreviewKey),
      );
      final barTopLeft = tester.getTopLeft(find.byKey(kChatToolActivityBarKey));
      final titleTopLeft = tester.getTopLeft(find.text('检查 git 状态'));
      final barSize = tester.getSize(find.byKey(kChatToolActivityBarKey));
      final glassSurface = find.descendant(
        of: find.byKey(kChatToolActivityBarKey),
        matching: find.byWidgetPredicate((widget) {
          if (widget is! DecoratedBox || widget.decoration is! BoxDecoration) {
            return false;
          }
          final decoration = widget.decoration as BoxDecoration;
          return decoration.border != null &&
              decoration.gradient is LinearGradient;
        }),
      );

      expect(previewTopLeft.dx, 52);
      expect(previewTopLeft.dy, lessThan(barTopLeft.dy));
      expect(barTopLeft.dx, closeTo(72, 0.1));
      expect(barSize.width, closeTo(240, 0.1));
      expect(titleTopLeft.dx, greaterThan(previewTopRight.dx - 12));
      expect(glassSurface, findsOneWidget);
      expect(
        find.descendant(
          of: find.byKey(kChatToolActivityBarKey),
          matching: find.byType(BackdropFilter),
        ),
        findsOneWidget,
      );
      expect(find.text('运行中'), findsOneWidget);
      expect(find.text('1/2'), findsNothing);
    },
  );

  testWidgets('expanded strip can be dismissed by tapping outside', (
    tester,
  ) async {
    var expanded = true;
    final messages = [
      ChatMessageModel.cardMessage({
        'type': 'agent_tool_summary',
        'status': 'running',
        'toolType': 'terminal',
        'toolTitle': '检查 git 状态',
        'summary': '终端正在运行',
      }),
      ChatMessageModel.cardMessage({
        'type': 'agent_tool_summary',
        'status': 'success',
        'toolType': 'workspace',
        'toolTitle': '读取配置文件',
        'summary': '已读取 app.yaml',
      }),
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: StatefulBuilder(
          builder: (context, setState) {
            return Scaffold(
              body: Stack(
                children: [
                  if (expanded)
                    Positioned.fill(
                      child: GestureDetector(
                        behavior: HitTestBehavior.translucent,
                        onTap: () => setState(() => expanded = false),
                      ),
                    ),
                  Positioned(
                    left: 52,
                    bottom: 24,
                    width: 280,
                    child: ChatToolActivityStrip(
                      messages: messages,
                      anchorRect: const Rect.fromLTWH(52, 0, 280, 0),
                      expanded: expanded,
                      onExpandedChanged: (value) =>
                          setState(() => expanded = value),
                    ),
                  ),
                ],
              ),
            );
          },
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(kChatToolActivityPanelKey), findsOneWidget);

    await tester.tapAt(const Offset(12, 12));
    await tester.pumpAndSettle();

    expect(find.byKey(kChatToolActivityPreviewKey), findsOneWidget);
  });

  testWidgets('expanded history drawer holds gesture gate while dragging', (
    tester,
  ) async {
    final messages = List<ChatMessageModel>.generate(6, (index) {
      return ChatMessageModel.cardMessage({
        'type': 'agent_tool_summary',
        'status': index == 0 ? 'running' : 'success',
        'toolType': 'workspace',
        'toolTitle': '工具调用 ${index + 1}',
        'summary': '结果 ${index + 1}',
      });
    });

    addTearDown(() {
      if (ToolCardDetailGestureGate.hasActivePointers) {
        fail('gesture gate should be released after the drag completes');
      }
    });

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Stack(
            children: [
              Positioned(
                left: 52,
                bottom: 24,
                width: 280,
                child: ChatToolActivityStrip(
                  messages: messages,
                  anchorRect: const Rect.fromLTWH(52, 0, 280, 0),
                  expanded: true,
                  onExpandedChanged: (_) {},
                ),
              ),
            ],
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final panelCenter = tester.getCenter(find.byKey(kChatToolActivityPanelKey));
    final gesture = await tester.startGesture(panelCenter);
    await tester.pump();

    expect(ToolCardDetailGestureGate.hasActivePointers, isTrue);

    await gesture.moveBy(const Offset(0, -48));
    await tester.pump();
    await gesture.up();
    await tester.pumpAndSettle();

    expect(ToolCardDetailGestureGate.hasActivePointers, isFalse);
  });
}
