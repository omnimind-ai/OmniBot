import 'package:flutter/material.dart';

/// Display artifacts retain the shared Function/RunLog schemas. Adapters own
/// tool names, transport envelopes, registration variants and plugin setup.
enum ExecutionCollectionKind { functions, runLogs }

class ExecutionAvailability {
  const ExecutionAvailability({
    required this.installed,
    required this.enabled,
    required this.settingsRoute,
  });
  final bool installed;
  final bool enabled;
  final String settingsRoute;
  bool get ready => installed && enabled;
}

class ExecutionPage {
  const ExecutionPage(
    this.items, {
    required this.hasMore,
    required this.nextOffset,
  });
  final List<Map<String, dynamic>> items;
  final bool hasMore;
  final int nextOffset;
}

abstract interface class ExecutionBackend {
  Future<ExecutionAvailability> availability();
  Future<void> enable();
  Future<bool> authorize(BuildContext context);
  Future<ExecutionPage> list(
    ExecutionCollectionKind kind, {
    required int limit,
    required int offset,
  });
  Future<Map<String, dynamic>> getFunction(String id);
  Future<Map<String, dynamic>> updateFunctionMetadata(
    String id,
    String name,
    String description,
  );
  Future<Map<String, dynamic>> getRunLog(String id);
  Future<Map<String, dynamic>> getRunLogState(String id);
  Future<void> run(
    Map<String, dynamic> function,
    Map<String, dynamic> arguments,
  );
  Future<void> delete(String id);
  Future<Map<String, dynamic>> register(Map<String, dynamic> runLog);
  String enhancementRequest(Map<String, dynamic> function);
  Future<void> record({
    required BuildContext context,
    required FocusNode focus,
    required bool Function() isMounted,
  });
}

class ExecutionFailure implements Exception {
  const ExecutionFailure(this.message);
  final String message;
  @override
  String toString() => message;
}

/// Resolve the canonical replay identity without treating a replay as its source.
Map<String, dynamic>? linkedExecutionFunction(
  Map<String, dynamic> runLog,
  List<Map<String, dynamic>> functions,
) {
  final diagnostics = runLog['diagnostics'];
  final executedId = (diagnostics is Map ? diagnostics['function_id'] : null)
      ?.toString()
      .trim();
  final functionId = executedId?.isNotEmpty == true
      ? executedId!
      : (runLog['function_id']?.toString().trim() ?? '');
  final runId = runLog['run_id']?.toString().trim() ?? '';
  for (final function in functions) {
    if ((functionId.isNotEmpty && function['function_id'] == functionId) ||
        (runId.isNotEmpty && function['source_run_id'] == runId)) {
      return function;
    }
  }
  return null;
}
