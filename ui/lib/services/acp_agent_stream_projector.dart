import 'package:ui/models/agent_stream_event.dart';

class AcpAgentStreamProjector {
  String _text = '';
  String _thinking = '';
  int _sequence = 0;

  AgentStreamEvent? project(
    Map<String, dynamic> event, {
    required String taskId,
    required int conversationId,
  }) {
    if (_asInt(event['conversationId']) != conversationId) return null;
    final presentation = _asMap(event['presentation']);
    final presentationKind = presentation?['kind']?.toString().trim();
    if (presentationKind == 'turn_completed') {
      return _event(taskId, AgentStreamEventKind.completed, isFinal: true);
    }
    if (presentationKind == 'turn_failed') {
      return _event(
        taskId,
        AgentStreamEventKind.error,
        error: presentation?['error']?.toString() ?? 'Agent 任务执行失败',
        isFinal: true,
      );
    }

    final method = event['method']?.toString().trim();
    final params = _asMap(event['params']);
    final update = _asMap(params?['update']);
    if (method == 'session/update' && update != null) {
      final kind = update['sessionUpdate']?.toString().trim();
      if (kind == 'agent_message_chunk') {
        final text = _extractText(update['content']);
        if (text.isEmpty) return null;
        _text = _mergeSnapshot(_text, text);
        return _event(
          taskId,
          AgentStreamEventKind.textSnapshot,
          entryId: update['messageId']?.toString() ?? '$taskId-message',
          text: _text,
        );
      }
      if (kind == 'agent_thought_chunk') {
        final thinking = _extractText(update['content']);
        if (thinking.isEmpty) return null;
        _thinking = _mergeSnapshot(_thinking, thinking);
        return _event(
          taskId,
          AgentStreamEventKind.thinkingSnapshot,
          entryId: update['messageId']?.toString() ?? '$taskId-thinking',
          thinking: _thinking,
        );
      }
      if (kind == 'tool_call' || kind == 'tool_call_update') {
        final status = update['status']?.toString().trim().toLowerCase();
        final toolKind = kind == 'tool_call'
            ? AgentStreamEventKind.toolStarted
            : status == 'completed' ||
                  status == 'failed' ||
                  status == 'cancelled'
            ? AgentStreamEventKind.toolCompleted
            : AgentStreamEventKind.toolProgress;
        return _event(
          taskId,
          toolKind,
          entryId: update['toolCallId']?.toString(),
          raw: update,
          isFinal: toolKind == AgentStreamEventKind.toolCompleted,
        );
      }
      return null;
    }
    if (method == 'error' || method == 'turn/failed') {
      return _event(
        taskId,
        AgentStreamEventKind.error,
        error: _extractText(params?['error']).isEmpty
            ? 'Agent 任务执行失败'
            : _extractText(params?['error']),
        isFinal: true,
      );
    }
    return null;
  }

  AgentStreamEvent _event(
    String taskId,
    AgentStreamEventKind kind, {
    String? entryId,
    String text = '',
    String thinking = '',
    String error = '',
    Map<String, dynamic>? raw,
    bool isFinal = false,
  }) {
    _sequence += 1;
    return AgentStreamEvent(
      taskId: taskId,
      seq: _sequence,
      kind: kind,
      createdAtMs: DateTime.now().millisecondsSinceEpoch,
      entryId: entryId,
      text: text,
      thinking: thinking,
      errorMessage: error,
      isFinal: isFinal,
      raw: <String, dynamic>{
        ...?raw,
        'taskId': taskId,
        'seq': _sequence,
        'kind': kind.value,
        if (entryId != null) 'entryId': entryId,
        if (text.isNotEmpty) 'text': text,
        if (thinking.isNotEmpty) 'thinking': thinking,
        if (error.isNotEmpty) 'error': error,
        if (isFinal) 'isFinal': true,
      },
    );
  }

  static String _mergeSnapshot(String previous, String incoming) {
    if (incoming.startsWith(previous)) return incoming;
    if (previous.startsWith(incoming)) return previous;
    return '$previous$incoming';
  }

  static Map<String, dynamic>? _asMap(dynamic value) {
    if (value is! Map) return null;
    return value.map((key, item) => MapEntry(key.toString(), item));
  }

  static int? _asInt(dynamic value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '');
  }

  static String _extractText(dynamic value) {
    if (value is String) return value;
    final map = _asMap(value);
    if (map == null) return '';
    return (map['text'] ?? map['content'] ?? '').toString();
  }
}
