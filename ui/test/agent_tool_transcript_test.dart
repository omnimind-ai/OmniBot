import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart';

void main() {
  test(
    'buildAgentToolTranscript renders non-terminal tool as pseudo command',
    () {
      final transcript = buildAgentToolTranscript({
        'toolName': 'file_read',
        'displayName': '读取文件',
        'toolType': 'workspace',
        'argsJson': jsonEncode({
          'path': '/workspace/README.md',
          'maxChars': 4000,
          'tool_title': '查看 README',
        }),
        'resultPreviewJson': jsonEncode({
          'path': '/workspace/README.md',
          'size': 32,
          'content': 'hello world',
        }),
        'status': 'success',
        'summary': '已读取文件',
      });

      expect(
        transcript.promptLine,
        r'$ file_read --path /workspace/README.md --maxChars 4000',
      );
      expect(transcript.outputText, contains('path: /workspace/README.md'));
      expect(transcript.outputText, contains('size: 32'));
      expect(transcript.outputText, contains('content: hello world'));
    },
  );

  test(
    'buildAgentToolTranscript renders terminal tool using native command',
    () {
      final transcript = buildAgentToolTranscript({
        'toolName': 'terminal_execute',
        'displayName': '终端执行',
        'toolType': 'terminal',
        'argsJson': jsonEncode({
          'command': 'git status',
          'workingDirectory': '/workspace',
        }),
        'terminalOutput': 'On branch main',
        'status': 'success',
        'summary': '终端命令执行成功',
      });

      expect(transcript.promptLine, r"$ cd /workspace && git status");
      expect(transcript.outputText, 'On branch main');
      expect(transcript.previewText, 'On branch main');
    },
  );

  test(
    'buildAgentToolTranscript hides legacy Codex namespace for Claude tools',
    () {
      final transcript = buildAgentToolTranscript({
        'agentId': 'claude-code-acp',
        'agentName': 'Claude Code',
        'toolName': 'codex.tool',
        'toolTitle': 'Read settings.json',
        'displayName': 'Read settings.json',
        'toolType': 'workspace',
        'argsJson': jsonEncode({
          'id': 'tool-call-42',
          'path': '/root/.claude/settings.json',
        }),
        'resultPreviewJson': jsonEncode({'status': 'ok'}),
        'status': 'success',
      });

      expect(transcript.promptLine, 'Claude Code · Read settings.json');
      expect(transcript.promptLine, isNot(contains('codex.tool')));
      expect(transcript.promptLine, isNot(contains('--id')));
    },
  );

  test(
    'buildAgentToolTranscript hides generic running placeholder for terminal output area',
    () {
      final transcript = buildAgentToolTranscript({
        'toolName': 'terminal_execute',
        'displayName': '终端执行',
        'toolType': 'terminal',
        'argsJson': jsonEncode({
          'command': 'npm install',
          'workingDirectory': '/workspace',
        }),
        'status': 'running',
        'summary': '正在调用内嵌 Alpine 终端执行命令',
        'progress': '终端输出更新中',
      });

      expect(transcript.promptLine, r'$ cd /workspace && npm install');
      expect(transcript.outputText, isEmpty);
      expect(transcript.previewText, isEmpty);
    },
  );

  test('tool detail preserves every persisted structured result field', () {
    final completeRecords = List<Map<String, dynamic>>.generate(
      128,
      (index) => <String, dynamic>{'id': index, 'fact': 'fact-$index'},
    );
    final transcript = buildAgentToolTranscript({
      'toolName': 'memory_search',
      'toolType': 'memory',
      // A preview may be abbreviated for a compact card. Detail must select
      // the canonical raw result instead.
      'resultPreviewJson': jsonEncode(<String, dynamic>{
        'records': completeRecords.take(1).toList(),
      }),
      'rawResultJson': jsonEncode(<String, dynamic>{
        'records': completeRecords,
      }),
    });

    expect(transcript.outputText, contains('id: 0, fact: fact-0'));
    expect(transcript.outputText, contains('id: 127, fact: fact-127'));
    expect(transcript.outputText, isNot(contains('[truncated]')));
  });

  test('tool detail preserves a complete array result', () {
    final values = List<Map<String, dynamic>>.generate(
      128,
      (index) => <String, dynamic>{'id': index, 'fact': 'fact-$index'},
    );
    final transcript = buildAgentToolTranscript({
      'toolName': 'external_list',
      'toolType': 'mcp',
      'resultPreviewJson': jsonEncode(values.take(1).toList()),
      'rawResultJson': jsonEncode(values),
    });

    expect(transcript.outputText, contains('"fact": "fact-0"'));
    expect(transcript.outputText, contains('"fact": "fact-127"'));
    expect(transcript.outputText, isNot(contains('[truncated]')));
  });
}
