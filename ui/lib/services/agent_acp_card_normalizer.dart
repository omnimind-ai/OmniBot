import 'dart:convert';

import 'agent_tool_call_parser.dart';

class AgentAcpCardNormalizer {
  const AgentAcpCardNormalizer._();

  static Map<String, dynamic> normalize(Map<String, dynamic> source) {
    final card = Map<String, dynamic>.from(source);
    final type = _string(card['type'] ?? card['sessionUpdate'] ?? card['kind']);

    if (_isRequestType(type)) {
      card['type'] = 'agent_request';
      card['requestKind'] ??= _requestKind(type);
      return card;
    }

    if (_isThinkingType(type)) {
      return _normalizeThinking(card);
    }

    if (_isPlanType(type) ||
        (type == 'agent_tool_summary' &&
            (_string(card['toolType']).trim().toLowerCase() == 'plan' ||
                _string(card['toolName']).trim().toLowerCase() == 'plan'))) {
      return _normalizePlan(card);
    }

    if (_isToolType(type)) {
      return _normalizeTool(card, type: type!);
    }

    return card;
  }

  static Map<String, dynamic> _normalizeThinking(Map<String, dynamic> source) {
    final card = Map<String, dynamic>.from(source);
    final legacy = _legacyThinkingValues(card);
    final currentText = _string(
      card['thinkingContent'] ??
          card['text'] ??
          card['delta'] ??
          card['content'] ??
          card['summary'],
    );
    final legacyText = _formatLegacyThinking(legacy);
    card['type'] = 'deep_thinking';
    card['thinkingContent'] = currentText.isNotEmpty ? currentText : legacyText;
    card['taskID'] ??= card['taskId'] ?? card['runId'];
    card['runId'] ??= card['taskID'];
    card['cardId'] ??= card['entryId'] ?? card['itemId'];
    card['stage'] ??= _thinkingStage(card);
    card['isLoading'] ??= card['stage'] != 4 && card['stage'] != 5;
    card['isCollapsible'] ??= card['isLoading'] == false;
    if (legacy.taskTitle.isNotEmpty) card['taskTitle'] = legacy.taskTitle;
    if (legacy.subTasks.isNotEmpty) card['subTasks'] = legacy.subTasks;
    if (legacy.preparation.isNotEmpty) card['preparation'] = legacy.preparation;
    if (legacy.memoryActions.isNotEmpty) {
      card['memoryActions'] = legacy.memoryActions;
    }
    return card;
  }

  static Map<String, dynamic> _normalizePlan(Map<String, dynamic> source) {
    final card = Map<String, dynamic>.from(source);
    final text = _string(
      card['summary'] ?? card['progress'] ?? card['text'],
    ).trim();
    final structuredEntries = _planEntries(
      card['planEntries'] ?? card['entries'],
    );
    final nestedPlanEntries = card['plan'] is Map
        ? _planEntries((card['plan'] as Map)['entries'])
        : const <Map<String, dynamic>>[];
    final planValue = structuredEntries.isNotEmpty
        ? card['entries']
        : nestedPlanEntries.isNotEmpty
        ? (card['plan'] as Map)['entries']
        : card['plan'];
    var entries = structuredEntries.isNotEmpty
        ? structuredEntries
        : nestedPlanEntries;
    final planText = text.isNotEmpty ? text : _planText(planValue);
    // ACP v2 permits a markdown plan payload instead of a structured entries
    // array. Parse only explicit task-list rows so prose headings are not
    // mistaken for tasks, while still giving the mutable card concrete rows
    // to update/render.
    if (entries.isEmpty && planText.isNotEmpty) {
      entries = _parsePlanMarkdown(planText);
    }
    final effectivePlanText = planText.isNotEmpty
        ? planText
        : _formatPlan(entries);
    final terminal =
        entries.isNotEmpty &&
        entries.every((entry) => _isTerminalPlanStatus(entry['status']));
    card['type'] = 'agent_tool_summary';
    card['uiStyle'] ??= 'agent_tool';
    card['toolType'] = 'plan';
    card['toolName'] ??= 'plan';
    card['toolTitle'] ??= '任务计划';
    card['displayName'] ??= '任务计划';
    card['summary'] = effectivePlanText;
    card['progress'] = effectivePlanText;
    card['status'] ??= terminal ? 'success' : 'running';
    card['planEntries'] = entries;
    card['rawInput'] ??= <String, dynamic>{'entries': entries};
    return card;
  }

  static Map<String, dynamic> _normalizeTool(
    Map<String, dynamic> source, {
    required String type,
  }) {
    final card = Map<String, dynamic>.from(source);
    card['type'] = 'agent_tool_summary';
    card['uiStyle'] ??= 'agent_tool';
    card['toolCallId'] ??= card['tool_call_id'] ?? card['callId'];
    card['toolName'] ??= card['name'] ?? card['title'] ?? type;
    card['toolTitle'] ??= card['title'] ?? card['name'] ?? '工具调用';
    card['displayName'] ??= card['toolTitle'];
    card['toolType'] ??= _toolType(type);
    card['status'] = normalizeAgentToolStatus(
      card,
      fallbackStatus: _string(card['status']).trim().isEmpty
          ? 'running'
          : _string(card['status']),
    );
    card['summary'] ??= _contentText(card['content'] ?? card['rawOutput']);
    card['progress'] ??= card['summary'];
    return card;
  }

  static _LegacyThinkingValues _legacyThinkingValues(
    Map<String, dynamic> source,
  ) {
    final nested = _decodeMap(
      source['deep_thinking'] ?? source['deepThinking'],
    );
    final raw = nested == null
        ? source
        : <String, dynamic>{...source, ...nested};
    final contentMap = _decodeMap(raw['thinkingContent'] ?? raw['content']);
    final values = contentMap == null
        ? raw
        : <String, dynamic>{...raw, ...contentMap};
    return _LegacyThinkingValues(
      taskDescription: _string(
        values['task_description'] ?? values['taskDescription'],
      ),
      subTasks: _stringList(values['sub_tasks'] ?? values['subTasks']),
      preparation: _string(values['preparation']),
      taskTitle: _string(values['task_title'] ?? values['taskTitle']),
      memoryActions: _stringList(
        values['memory_actions'] ?? values['memoryActions'],
      ),
    );
  }

  static String _formatLegacyThinking(_LegacyThinkingValues values) {
    final parts = <String>[];
    if (values.taskDescription.isNotEmpty) parts.add(values.taskDescription);
    if (values.subTasks.isNotEmpty) {
      parts.add(
        values.subTasks
            .asMap()
            .entries
            .map((entry) => '任务${entry.key + 1}: ${entry.value}')
            .join('\n'),
      );
    }
    if (values.preparation.isNotEmpty) parts.add(values.preparation);
    if (values.memoryActions.isNotEmpty) {
      parts.add('记忆：${values.memoryActions.join('、')}');
    }
    return parts.join('\n\n');
  }

  static String _formatPlan(List<Map<String, dynamic>> entries) {
    return entries
        .map((entry) {
          final status = _string(entry['status']).toLowerCase();
          final marker = _isTerminalPlanStatus(status) ? '[x]' : '[ ]';
          return '$marker ${_string(entry['content'] ?? entry['title'])}';
        })
        .where((line) => line.trim().length > 4)
        .join('\n');
  }

  static List<Map<String, dynamic>> _planEntries(Object? value) {
    if (value is List) {
      return value
          .whereType<Map>()
          .map((entry) => Map<String, dynamic>.from(entry))
          .toList(growable: false);
    }
    return const <Map<String, dynamic>>[];
  }

  static List<Map<String, dynamic>> _parsePlanMarkdown(String text) {
    final entries = <Map<String, dynamic>>[];
    final row = RegExp(r'^[-*+]\s+\[([^\]]*)\]\s+(.+)$');
    final numbered = RegExp(r'^\d+[.)]\s+(?:\[([^\]]*)\]\s+)?(.+)$');
    for (final rawLine in text.split('\n')) {
      final line = rawLine.trim();
      if (line.isEmpty || line.startsWith('#')) continue;
      final match = row.firstMatch(line) ?? numbered.firstMatch(line);
      if (match == null) continue;
      final marker = match.group(1)?.trim().toLowerCase() ?? '';
      final content = (match.group(2) ?? '').trim();
      if (content.isEmpty) continue;
      final status = switch (marker) {
        'x' || '✓' || 'done' || 'completed' || 'complete' => 'completed',
        '~' ||
        '-' ||
        'in_progress' ||
        'in-progress' ||
        'running' => 'in_progress',
        _ => 'pending',
      };
      entries.add(<String, dynamic>{'content': content, 'status': status});
    }
    return entries;
  }

  static String _planText(Object? value) {
    if (value is String) return value.trim();
    if (value is Map) {
      return _string(value['content'] ?? value['text'] ?? value['markdown']);
    }
    return '';
  }

  static bool _isTerminalPlanStatus(Object? value) {
    final status = _string(value).toLowerCase();
    return status == 'completed' || status == 'complete' || status == 'done';
  }

  static int _thinkingStage(Map<String, dynamic> card) {
    final phase = _string(card['phase'] ?? card['stageName']).toLowerCase();
    if (phase == 'complete' || phase == 'completed') return 4;
    if (phase == 'cancelled' || phase == 'canceled') return 5;
    if (phase == 'planning' || phase == 'plan') return 2;
    if (phase == 'preparing' || phase == 'preparation') return 3;
    return 1;
  }

  static bool _isThinkingType(String? type) {
    return type == 'deep_thinking' ||
        type == 'thinking' ||
        type == 'reasoning' ||
        type == 'agent_thought_chunk' ||
        type == 'agentThoughtChunk';
  }

  static bool _isPlanType(String? type) {
    return type == 'plan' || type == 'todo_list' || type == 'turn_plan';
  }

  static bool _isRequestType(String? type) {
    return type == 'codex_request' ||
        type == 'requestApproval' ||
        type == 'requestUserInput' ||
        type == 'request_approval' ||
        type == 'request_user_input';
  }

  static bool _isToolType(String? type) {
    if (type == null) return false;
    return type == 'tool_call' ||
        type == 'tool_call_update' ||
        type == 'commandExecution' ||
        type == 'command_execution' ||
        type == 'mcpToolCall' ||
        type == 'mcp_tool_call' ||
        type == 'webSearch' ||
        type == 'web_search' ||
        type == 'fileChange' ||
        type == 'file_change';
  }

  static String _requestKind(String? type) {
    return type == 'requestUserInput' || type == 'request_user_input'
        ? 'user_input'
        : 'approval';
  }

  static String _toolType(String type) {
    if (type.contains('mcp')) return 'mcp';
    if (type.contains('web')) return 'web';
    if (type.contains('file')) return 'file';
    if (type.contains('command')) return 'terminal';
    return 'builtin';
  }

  static String _contentText(Object? value) {
    if (value is String) return value.trim();
    if (value is Map) {
      return _string(
        value['text'] ?? value['summary'] ?? value['content'],
      ).trim();
    }
    if (value is List)
      return value
          .map(_contentText)
          .where((text) => text.isNotEmpty)
          .join('\n');
    return '';
  }

  static Map<String, dynamic>? _decodeMap(Object? value) {
    if (value is Map) return Map<String, dynamic>.from(value);
    if (value is! String) return null;
    final text = value.trim();
    if (!text.startsWith('{') || !text.endsWith('}')) return null;
    try {
      final decoded = jsonDecode(text);
      return decoded is Map ? Map<String, dynamic>.from(decoded) : null;
    } catch (_) {
      return null;
    }
  }

  static List<String> _stringList(Object? value) {
    if (value is List) {
      return value
          .map(_string)
          .where((item) => item.isNotEmpty)
          .toList(growable: false);
    }
    final text = _string(value);
    return text.isEmpty ? const <String>[] : <String>[text];
  }

  static String _string(Object? value) => value?.toString().trim() ?? '';
}

class _LegacyThinkingValues {
  const _LegacyThinkingValues({
    required this.taskDescription,
    required this.subTasks,
    required this.preparation,
    required this.taskTitle,
    required this.memoryActions,
  });

  final String taskDescription;
  final List<String> subTasks;
  final String preparation;
  final String taskTitle;
  final List<String> memoryActions;
}
