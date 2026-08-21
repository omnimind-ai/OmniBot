import 'dart:convert';

import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_message_kinds.dart';
import 'package:ui/features/task/run_log/omniflow_tool_client.dart';

ChatMessageModel buildManualRecordingResultCard({
  required String messageId,
  required Map<String, dynamic> result,
  required String summary,
}) {
  final succeeded = result['success'] == true;
  final runLog = _map(result['run_log']);
  final runId = (runLog['run_id'] ?? '').toString().trim();
  final function = omniFlowRegisteredFunction(result);
  final functionId = (function['function_id'] ?? '').toString().trim();
  final payload = <String, dynamic>{
    'context_type': 'manual_recording_result',
    'success': succeeded,
    'status': succeeded ? 'succeeded' : 'failed',
    'content': summary,
    'run_id': runId,
    'action_count': _list(runLog['steps']).length,
    if (functionId.isNotEmpty) ...{
      'auto_registered': true,
      'registered_function_id': functionId,
    },
  };
  final cardData = <String, dynamic>{
    'type': kAgentToolSummaryCardType,
    'uiStyle': kAgentToolUiStyle,
    'cardId': messageId,
    'toolName': 'vlm_task',
    'toolTitle': '手动录制',
    'displayName': '手动录制',
    'toolType': 'builtin',
    'status': succeeded ? 'success' : 'error',
    'summary': summary,
    'success': succeeded,
    'resultPreviewJson': jsonEncode(payload),
    'rawResultJson': jsonEncode(result),
  };
  return ChatMessageModel(
    id: messageId,
    type: 2,
    user: 3,
    content: {'cardData': cardData, 'id': messageId},
    isError: !succeeded,
  );
}

Map<String, dynamic> _map(Object? value) => value is Map
    ? value.map((key, item) => MapEntry(key.toString(), item))
    : const {};

List<Map<String, dynamic>> _list(Object? value) =>
    value is List ? value.whereType<Map>().map(_map).toList() : const [];
