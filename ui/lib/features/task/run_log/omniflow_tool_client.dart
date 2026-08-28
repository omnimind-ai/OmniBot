import 'package:ui/services/assists_core_service.dart';

/// The official OmniFlow bridge returns `functions`/`function_ids` because a
/// run can compile more than one Function. Older app builds returned a single
/// `function`; keep both shapes at this shared boundary so every caller uses
/// the same registration result contract.
Map<String, dynamic> omniFlowRegisteredFunction(Map<String, dynamic> payload) {
  final nested = payload['function'];
  if (nested is Map) {
    return _normalizeFunctionMap(nested);
  }
  final functions = payload['functions'];
  if (functions is List && functions.isNotEmpty && functions.first is Map) {
    return _normalizeFunctionMap(functions.first as Map);
  }
  final rawIds = payload['function_ids'];
  final firstId = rawIds is List && rawIds.isNotEmpty
      ? rawIds.first?.toString().trim() ?? ''
      : '';
  return firstId.isEmpty
      ? <String, dynamic>{}
      : <String, dynamic>{'function_id': firstId};
}

Map<String, dynamic> _normalizeFunctionMap(Map raw) {
  final normalized = <String, dynamic>{};
  raw.forEach((key, value) {
    normalized[key.toString()] = value;
  });
  return normalized;
}

bool hasOmniFlowRegisteredFunction(Map<String, dynamic> payload) {
  return omniFlowRegisteredFunction(payload).isNotEmpty;
}

class OmniFlowFunctionRegistrationResult {
  const OmniFlowFunctionRegistrationResult({
    required this.function,
    this.errorMessage,
  });

  final Map<String, dynamic>? function;
  final String? errorMessage;

  bool get success => function != null;
  String get functionId => function?['function_id']?.toString().trim() ?? '';

  factory OmniFlowFunctionRegistrationResult.fromPayload(
    Map<String, dynamic> payload, {
    required String runId,
  }) {
    if (payload['success'] == false) {
      return OmniFlowFunctionRegistrationResult(
        function: null,
        errorMessage:
            payload['error_message']?.toString().trim().nullIfEmpty ??
            payload['error_code']?.toString().trim().nullIfEmpty ??
            '注册失败',
      );
    }
    final function = omniFlowRegisteredFunction(payload);
    final rawFunctionIds = payload['function_ids'];
    final firstFunctionId = rawFunctionIds is List && rawFunctionIds.isNotEmpty
        ? rawFunctionIds.first
        : null;
    final functionId =
        (function['function_id'] ??
                payload['function_id'] ??
                payload['registered_function_id'] ??
                firstFunctionId)
            ?.toString()
            .trim() ??
        '';
    if (functionId.isEmpty) {
      return const OmniFlowFunctionRegistrationResult(
        function: null,
        errorMessage: '注册响应缺少 function_id',
      );
    }
    return OmniFlowFunctionRegistrationResult(
      function: <String, dynamic>{
        ...function,
        'function_id': functionId,
        if ((function['source_run_id']?.toString().trim() ?? '').isEmpty)
          'source_run_id': runId,
      },
    );
  }
}

class OmniFlowToolClient {
  const OmniFlowToolClient._();

  static Future<Map<String, dynamic>> listFunctions({
    int limit = 100,
    int offset = 0,
  }) {
    return _call('list_functions', {'limit': limit, 'offset': offset});
  }

  static Future<Map<String, dynamic>> listRunLogs({
    int limit = 100,
    int offset = 0,
  }) {
    return _call('list_run_logs', {'limit': limit, 'offset': offset});
  }

  static Future<Map<String, dynamic>> getFunction(String functionId) {
    return _call('get_function', {'function_id': functionId});
  }

  static Future<Map<String, dynamic>> getRunLog(String runId) {
    return _call('get_run_log', {'run_id': runId});
  }

  static Future<Map<String, dynamic>> getRunLogState(String stateId) {
    return _call('get_run_log_state', {'state_id': stateId});
  }

  static Future<Map<String, dynamic>> saveFunctionFromRunLog(String runId) {
    return _call('save_function', {'run_id': runId});
  }

  static Future<OmniFlowFunctionRegistrationResult> registerFunctionFromRunLog(
    String runId,
  ) async {
    final payload = await saveFunctionFromRunLog(runId);
    return OmniFlowFunctionRegistrationResult.fromPayload(
      payload,
      runId: runId,
    );
  }

  static Future<Map<String, dynamic>> startHumanTrajectoryLearning({
    required String name,
    required String description,
    bool enableDebugScreenshots = false,
  }) async {
    final result = await AssistsMessageService.assistCore.invokeMethod<Object?>(
      'startHumanTrajectoryLearning',
      <String, dynamic>{
        'name': name,
        'description': description,
        'enable_debug_screenshots': enableDebugScreenshots,
      },
    );
    if (result is! Map) {
      throw StateError('Manual recording returned an invalid response');
    }
    return result.map(
      (key, value) => MapEntry(key.toString(), _normalize(value)),
    );
  }

  static Future<Map<String, dynamic>> deleteFunction(String functionId) {
    return _call('delete_function', {'function_id': functionId});
  }

  static Future<Map<String, dynamic>> replayFunction(
    String functionId,
    Map<String, dynamic> arguments, {
    String? goal,
  }) {
    return _call(functionId, arguments, goal: goal);
  }

  static Future<Map<String, dynamic>> _call(
    String name,
    Map<String, dynamic> arguments, {
    String? goal,
  }) async {
    final result = await AssistsMessageService.assistCore
        .invokeMethod<Object?>('tools/call', {
          'name': name,
          'arguments': arguments,
          if (goal?.trim().isNotEmpty == true) 'goal': goal!.trim(),
        });
    if (result is! Map) {
      throw StateError('OmniFlow tool $name returned an invalid response');
    }
    return result.map(
      (key, value) => MapEntry(key.toString(), _normalize(value)),
    );
  }

  static dynamic _normalize(dynamic value) {
    if (value is Map) {
      return value.map(
        (key, nested) => MapEntry(key.toString(), _normalize(nested)),
      );
    }
    if (value is List) {
      return value.map(_normalize).toList(growable: false);
    }
    return value;
  }
}

extension on String {
  String? get nullIfEmpty => isEmpty ? null : this;
}
