import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_summary_card.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/terminal_output_utils.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/app_background_service.dart';

void main() {
  setUp(() {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
  });

  tearDown(() {
    LegacyTextLocalizer.clearResolvedLocale();
  });

  test('TerminalOutputUtils builds readable output from result json', () {
    final output = TerminalOutputUtils.buildDisplayOutput(
      terminalOutput: '',
      rawResultJson: jsonEncode({
        'liveFallbackReason': '共享存储未就绪',
        'stdout': 'hello',
        'stderr': 'warning',
      }),
      resultPreviewJson: '',
    );

    expect(output, contains('hello'));
    expect(output, contains('[stderr]'));
    expect(output, contains('warning'));
  });

  test('AnsiTextSpanBuilder applies color and bold to sgr spans', () {
    const baseStyle = TextStyle(fontSize: 12, color: Colors.white);
    final span = AnsiTextSpanBuilder.build(
      '\u001B[31;1merror\u001B[0m',
      baseStyle,
    );

    final children = span.children!;
    final styledChild = children.first as TextSpan;
    expect(styledChild.text, 'error');
    expect(styledChild.style?.fontWeight, FontWeight.w700);
    expect(styledChild.style?.color, const Color(0xFFE06C75));
  });

  testWidgets('tool card prefers toolTitle when rendering compact chip', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AgentToolSummaryCard(
            cardData: {
              'status': 'success',
              'displayName': '终端执行',
              'toolTitle': '检查仓库状态',
              'toolType': 'terminal',
              'summary': '终端命令执行成功',
              'argsJson': jsonEncode({
                'command': 'ls -la',
                'executionMode': 'termux',
                'timeoutSeconds': 60,
              }),
            },
          ),
        ),
      ),
    );

    expect(find.text('检查仓库状态'), findsOneWidget);
    expect(find.text('终端执行'), findsNothing);
    final leadingIcon = find.byKey(
      const ValueKey('agent-tool-summary-leading-icon'),
    );
    expect(leadingIcon, findsOneWidget);
    final iconSlot = tester.widget<SizedBox>(leadingIcon);
    expect(iconSlot.width, 20);
    expect(iconSlot.height, 20);
    expect(
      find.descendant(of: leadingIcon, matching: find.byType(DecoratedBox)),
      findsNothing,
    );
    final icon = tester.widget<Icon>(
      find.descendant(of: leadingIcon, matching: find.byType(Icon)),
    );
    expect(icon.size, 18);
  });

  testWidgets('completed VLM tool renders GUI completion card', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AgentToolSummaryCard(
            cardData: {
              'status': 'success',
              'toolName': 'vlm_task',
              'displayName': 'VLM GUI',
              'toolType': 'builtin',
              'summary': '蓝牙已打开',
              'resultPreviewJson': jsonEncode({
                'run_id': 'gui-run-1',
                'success': true,
                'content': '蓝牙已打开',
                'registration_available': true,
                'registration_hint': '本次成功操作已保存为 RunLog，可注册为复用指令。',
              }),
            },
          ),
        ),
      ),
    );

    expect(find.byKey(const ValueKey('vlm-task-result-card')), findsOneWidget);
    expect(find.text('GUI 任务已完成'), findsOneWidget);
    expect(find.text('蓝牙已打开'), findsOneWidget);
    expect(find.textContaining('可注册为复用指令'), findsOneWidget);
    expect(find.text('查看 RunLog'), findsOneWidget);
    expect(find.text('注册为复用指令'), findsOneWidget);
    expect(find.byKey(kAgentToolDetailSheetKey), findsNothing);
  });

  testWidgets('registered GUI result opens its matching Function details', (
    tester,
  ) async {
    final router = GoRouter(
      initialLocation: '/',
      routes: [
        GoRoute(
          path: '/',
          builder: (context, state) => Scaffold(
            body: AgentToolSummaryCard(
              cardData: {
                'status': 'success',
                'toolName': 'vlm_task',
                'toolType': 'builtin',
                'resultPreviewJson': jsonEncode({
                  'run_id': 'gui-run-1',
                  'success': true,
                  'content': '已完成',
                  'auto_registered': true,
                  'registered_function_id': 'recorded_demo',
                }),
              },
            ),
          ),
        ),
        GoRoute(
          path: '/task/omniflow',
          builder: (context, state) => Scaffold(
            body: Text('function:${state.uri.queryParameters['functionId']}'),
          ),
        ),
      ],
    );
    addTearDown(router.dispose);

    await tester.pumpWidget(MaterialApp.router(routerConfig: router));
    await tester.tap(find.byKey(const ValueKey('vlm-task-open-functions')));
    await tester.pumpAndSettle();

    expect(find.text('function:recorded_demo'), findsOneWidget);
  });

  testWidgets('unfinished VLM tool still exposes its RunLog', (tester) async {
    final router = GoRouter(
      initialLocation: '/',
      routes: [
        GoRoute(
          path: '/',
          builder: (context, state) => Scaffold(
            body: AgentToolSummaryCard(
              cardData: {
                'status': 'interrupted',
                'toolName': 'vlm_task',
                'displayName': 'VLM GUI',
                'toolType': 'builtin',
                'summary': '视觉任务已停止',
                // A stop can arrive before the final result payload is built. The
                // progress event still carries the run id at card level.
                'run_id': 'gui-run-interrupted',
                'resultPreviewJson': jsonEncode({
                  'success': false,
                  'done_reason': 'cancelled',
                  'error': '视觉任务已停止',
                }),
              },
            ),
          ),
        ),
        GoRoute(
          path: '/task/run_log/:runId',
          builder: (context, state) =>
              Scaffold(body: Text('runlog:${state.pathParameters['runId']}')),
        ),
      ],
    );
    addTearDown(router.dispose);

    await tester.pumpWidget(MaterialApp.router(routerConfig: router));

    expect(find.text('GUI 任务未完成'), findsOneWidget);
    expect(find.byKey(const ValueKey('vlm-task-open-run-log')), findsOneWidget);
    expect(find.text('复用指令'), findsNothing);

    await tester.tap(find.byKey(const ValueKey('vlm-task-open-run-log')));
    await tester.pumpAndSettle();

    expect(find.text('runlog:gui-run-interrupted'), findsOneWidget);
  });

  testWidgets('published Vibe project opens its dashboard directly', (
    tester,
  ) async {
    final router = GoRouter(
      initialLocation: '/',
      routes: [
        GoRoute(
          path: '/',
          builder: (context, state) => Scaffold(
            body: AgentToolSummaryCard(
              cardData: {
                'status': 'success',
                'toolName': 'project_publish',
                'displayName': '发布项目',
                'toolType': 'plugin',
                'resultPreviewJson': jsonEncode({
                  'name': '真实 NBA 赛程',
                  'dashboardRoute':
                      '/home/plugin_dashboard?pluginId=local.project.nba-live',
                }),
              },
            ),
          ),
        ),
        GoRoute(
          path: '/home/plugin_dashboard',
          builder: (context, state) => Scaffold(
            body: Text('dashboard:${state.uri.queryParameters['pluginId']}'),
          ),
        ),
      ],
    );
    addTearDown(router.dispose);

    await tester.pumpWidget(MaterialApp.router(routerConfig: router));

    expect(
      find.byKey(const ValueKey('published-project-card')),
      findsOneWidget,
    );
    expect(find.text('真实 NBA 赛程'), findsOneWidget);
    expect(find.text('打开 Dashboard'), findsOneWidget);

    await tester.tap(
      find.byKey(const ValueKey('published-project-open-dashboard')),
    );
    await tester.pumpAndSettle();

    expect(find.text('dashboard:local.project.nba-live'), findsOneWidget);
  });

  testWidgets('tool card opens detail sheet when tapped', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Center(
            child: AgentToolSummaryCard(
              cardData: {
                'status': 'success',
                'displayName': '终端执行',
                'toolTitle': '检查仓库状态',
                'toolType': 'terminal',
                'summary': '终端命令执行成功',
                'argsJson': jsonEncode({
                  'command': 'git status',
                  'workingDirectory': '/workspace',
                }),
                'terminalOutput': 'On branch main',
              },
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.text('检查仓库状态'));
    await tester.pumpAndSettle();

    final sheet = find.byKey(kAgentToolDetailSheetKey);
    expect(sheet, findsOneWidget);
    expect(
      find.descendant(
        of: sheet,
        matching: find.textContaining('git status', findRichText: true),
      ),
      findsOneWidget,
    );

    await tester.tapAt(const Offset(12, 12));
    await tester.pumpAndSettle();

    expect(sheet, findsNothing);
  });

  testWidgets('codex tool card uses inline tool row style', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Center(
            child: AgentToolSummaryCard(
              cardData: {
                'type': 'agent_tool_summary',
                'status': 'success',
                'toolTitle': '读取 README.md',
                'toolType': 'workspace',
                'summary': '读取完成',
                'argsJson': jsonEncode({'path': 'README.md'}),
                'rawResultJson': jsonEncode({'type': 'mcpToolCall'}),
              },
            ),
          ),
        ),
      ),
    );

    expect(find.text('读取 README.md'), findsOneWidget);
    expect(find.byIcon(LucideIcons.folder), findsOneWidget);
    expect(find.text('工作区'), findsNothing);

    await tester.tap(find.text('读取 README.md'));
    await tester.pumpAndSettle();

    expect(find.byKey(kAgentToolDetailSheetKey), findsOneWidget);
  });

  testWidgets('running codex inline tool title uses shimmer', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Center(
            child: AgentToolSummaryCard(
              cardData: {
                'type': 'agent_tool_summary',
                'status': 'running',
                'toolTitle': 'Read README.md',
                'toolType': 'workspace',
                'summary': 'reading',
                'rawResultJson': jsonEncode({'type': 'function_call'}),
              },
            ),
          ),
        ),
      ),
    );

    expect(find.text('Read README.md'), findsOneWidget);
    expect(find.byType(ShaderMask), findsOneWidget);
  });

  testWidgets(
    'interrupted status shows stopped state without loading spinner',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: AgentToolSummaryCard(
              cardData: {
                'status': 'interrupted',
                'displayName': 'tool',
                'toolType': 'builtin',
                'summary': 'stopped',
              },
            ),
          ),
        ),
      );

      expect(find.text('\u4E2D\u65AD'), findsOneWidget);
      expect(find.byIcon(LucideIcons.stopCircle), findsOneWidget);
      expect(find.byType(CircularProgressIndicator), findsNothing);
    },
  );

  testWidgets('timeout status shows dedicated timeout badge and icon', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AgentToolSummaryCard(
            cardData: {
              'status': 'timeout',
              'displayName': '终端执行',
              'toolType': 'terminal',
              'summary': '终端命令等待超时',
            },
          ),
        ),
      ),
    );

    expect(find.text('超时'), findsOneWidget);
    expect(find.byIcon(LucideIcons.hourglass), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsNothing);
  });

  testWidgets('tool card falls back to args tool_title when field missing', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AgentToolSummaryCard(
            cardData: {
              'status': 'running',
              'displayName': '读取文件',
              'toolType': 'workspace',
              'summary': '已读取文件',
              'argsJson': jsonEncode({
                'tool_title': '查看配置',
                'path': 'README.md',
              }),
            },
          ),
        ),
      ),
    );

    expect(find.text('查看配置'), findsOneWidget);
    expect(find.text('工作区'), findsOneWidget);
  });

  testWidgets('file diff card expands diff inline instead of opening sheet', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AgentToolSummaryCard(
            cardData: {
              'status': 'success',
              'displayName': '文件修改',
              'toolTitle': '更新 main.dart',
              'toolType': 'file',
              'summary': '1 个文件 · +2 -1',
              'changedFiles': 1,
              'additions': 2,
              'deletions': 1,
              'filePath': 'lib/main.dart',
              'diffText': '''
diff --git a/lib/main.dart b/lib/main.dart
--- a/lib/main.dart
+++ b/lib/main.dart
@@ -1,3 +1,4 @@
-old line
+new line
+another line
 same line
''',
            },
          ),
        ),
      ),
    );

    expect(find.text('更新 '), findsOneWidget);
    expect(find.text('main.dart'), findsOneWidget);
    expect(find.textContaining('+2 -1', findRichText: true), findsOneWidget);
    expect(find.textContaining('-old line', findRichText: true), findsNothing);

    await tester.tap(
      find.byKey(const ValueKey('inline-file-diff-title-toggle')),
    );
    await tester.pump(const Duration(milliseconds: 320));

    expect(find.byKey(kAgentToolDetailSheetKey), findsNothing);
    expect(find.text('lib/main.dart'), findsNothing);
    expect(
      find.textContaining('-old line', findRichText: true),
      findsOneWidget,
    );
    expect(
      find.textContaining('+new line', findRichText: true),
      findsOneWidget,
    );
    expect(
      find.textContaining('same line', findRichText: true),
      findsOneWidget,
    );
  });

  testWidgets('file diff title filename tap shows full path tooltip', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AgentToolSummaryCard(
            cardData: {
              'status': 'success',
              'displayName': '文件修改',
              'toolTitle': '更新 main.dart',
              'toolType': 'file',
              'filePath': 'lib/main.dart',
              'diffText': '''
diff --git a/lib/main.dart b/lib/main.dart
--- a/lib/main.dart
+++ b/lib/main.dart
@@ -1,2 +1,2 @@
-old line
+new line
''',
            },
          ),
        ),
      ),
    );

    await tester.tap(find.text('main.dart'));
    await tester.pump(const Duration(milliseconds: 500));

    expect(find.text('lib/main.dart'), findsOneWidget);
    expect(find.textContaining('-old line', findRichText: true), findsNothing);
  });

  testWidgets('tool card title follows appearance text color', (tester) async {
    const customTextColor = Color(0xFFEEE6D7);

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AgentToolSummaryCard(
            cardData: {
              'status': 'success',
              'toolTitle': '同步索引',
              'toolType': 'workspace',
              'summary': '已完成同步',
            },
            visualProfile: const AppBackgroundVisualProfile(
              sampledImageLuminance: 0.12,
              effectiveLuminance: 0.24,
              textTone: AppBackgroundTextTone.light,
              customPrimaryTextColor: customTextColor,
            ),
          ),
        ),
      ),
    );

    final title = tester.widget<Text>(find.text('同步索引'));
    expect(title.style?.color, customTextColor);
    expect(title.style?.fontSize, 12);
  });

  testWidgets('subagent card shows status line and expands timeline', (
    tester,
  ) async {
    const thinkingLine = 'SubAgent #1 思考：检查数据来源';
    const firstStatusLine = 'SubAgent #1 调用工具：file_search';
    const resultLine = 'SubAgent #1 得到结果：完成摘要';
    const secondStatusLine = 'SubAgent #2 思考：整理最终摘要';

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AgentToolSummaryCard(
            cardData: {
              'status': 'running',
              'displayName': '分派子任务',
              'toolName': 'subagent_dispatch',
              'toolType': 'subagent',
              'subagentStatusText': firstStatusLine,
              'subagentEvents': [
                {
                  'id': 'subagent-event-1',
                  'seq': 1,
                  'createdAt': 10,
                  'kind': 'thinking',
                  'summary': thinkingLine,
                  'status': 'running',
                  'taskIndex': 0,
                },
                {
                  'id': 'subagent-event-2',
                  'seq': 2,
                  'createdAt': 20,
                  'kind': 'tool_started',
                  'summary': firstStatusLine,
                  'status': 'running',
                  'taskIndex': 0,
                  'toolName': 'file_search',
                },
                {
                  'id': 'subagent-event-3',
                  'seq': 3,
                  'createdAt': 30,
                  'kind': 'subagent_completed',
                  'summary': resultLine,
                  'status': 'completed',
                  'taskIndex': 0,
                },
                {
                  'id': 'subagent-event-4',
                  'seq': 4,
                  'createdAt': 40,
                  'kind': 'thinking',
                  'summary': secondStatusLine,
                  'status': 'running',
                  'taskIndex': 1,
                },
              ],
            },
          ),
        ),
      ),
    );

    expect(find.text('分派子任务'), findsOneWidget);
    expect(find.text(resultLine), findsOneWidget);
    expect(find.text(secondStatusLine), findsOneWidget);
    expect(find.text(thinkingLine), findsNothing);
    expect(find.text(firstStatusLine), findsNothing);

    await tester.tap(find.text(resultLine));
    await tester.pump(const Duration(milliseconds: 320));

    expect(find.text('检查数据来源'), findsOneWidget);
    expect(find.text('file_search'), findsOneWidget);
    expect(find.text('完成摘要'), findsOneWidget);
    expect(find.text(thinkingLine), findsNothing);
    expect(find.text(firstStatusLine), findsNothing);
    expect(find.text(resultLine), findsOneWidget);
    expect(find.byIcon(LucideIcons.brain), findsOneWidget);
    expect(find.byIcon(LucideIcons.wrench), findsOneWidget);
  });
}
