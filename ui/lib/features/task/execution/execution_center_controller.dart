import 'package:flutter/foundation.dart';

import 'execution_backend.dart';

class ExecutionCollection {
  ExecutionCollection(this.kind, this.idKey);
  final ExecutionCollectionKind kind;
  final String idKey;
  List<Map<String, dynamic>> items = const [];
  bool loaded = false;
  bool loading = false;
  bool hasMore = false;
  int nextOffset = 0;
  String? error;

  void merge(List<Map<String, dynamic>> next, {bool reset = false}) {
    final ids = <String>{};
    items = List.unmodifiable(
      (reset ? next : [...items, ...next]).where((item) {
        final id = item[idKey]?.toString() ?? '';
        return id.isEmpty || ids.add(id);
      }),
    );
  }
}

/// Only library-view state; execution/recording ownership remains in the backend.
class ExecutionCenterController extends ChangeNotifier {
  ExecutionCenterController(this.backend);
  final ExecutionBackend backend;
  final functions = ExecutionCollection(
    ExecutionCollectionKind.functions,
    'function_id',
  );
  final runLogs = ExecutionCollection(
    ExecutionCollectionKind.runLogs,
    'run_id',
  );
  ExecutionAvailability? availability;
  bool loading = true;
  String? error;
  bool _disposed = false;
  bool get ready => availability?.ready == true;
  void _changed() {
    if (!_disposed) notifyListeners();
  }

  Future<void> initialize({bool logs = false}) async {
    loading = true;
    error = null;
    _changed();
    try {
      availability = await backend.availability();
      if (_disposed) return;
      if (!ready) {
        functions.merge([], reset: true);
        runLogs.merge([], reset: true);
        invalidate();
      }
      loading = false;
      _changed();
      if (ready) await loadActive(logs: logs, reset: true);
    } catch (e) {
      if (_disposed) return;
      loading = false;
      error = e.toString();
      _changed();
    }
  }

  Future<void> enable({bool logs = false}) async {
    loading = true;
    _changed();
    try {
      await backend.enable();
      await initialize(logs: logs);
    } catch (e) {
      error = e.toString();
      loading = false;
      _changed();
    }
  }

  Future<void> loadActive({required bool logs, bool reset = false}) async {
    if (!ready || _disposed) return;
    await Future.wait([
      if (reset || !functions.loaded) load(functions, reset: true),
      if (logs && (reset || !runLogs.loaded)) load(runLogs, reset: true),
    ]);
  }

  Future<void> load(
    ExecutionCollection collection, {
    required bool reset,
  }) async {
    if (collection.loading || _disposed) return;
    final offset = reset ? 0 : collection.nextOffset;
    collection.loading = true;
    collection.error = null;
    _changed();
    try {
      final page = await backend.list(
        collection.kind,
        limit: 20,
        offset: offset,
      );
      if (_disposed) return;
      collection.merge(page.items, reset: reset);
      collection.hasMore = page.hasMore;
      collection.nextOffset = page.nextOffset;
      collection.loaded = true;
    } catch (e) {
      if (_disposed) return;
      collection.error = e.toString();
      collection.loaded = true;
    } finally {
      collection.loading = false;
      _changed();
    }
  }

  Future<Map<String, dynamic>> register(Map<String, dynamic> runLog) async {
    final function = await backend.register(runLog);
    if (!_disposed) {
      functions.merge([function, ...functions.items], reset: true);
      functions.loaded = true;
      _changed();
    }
    return function;
  }

  void invalidate() {
    functions.loaded = false;
    runLogs.loaded = false;
  }

  Future<Map<String, dynamic>> updateFunctionMetadata(
    String id,
    String name,
    String description,
  ) async {
    final result = await backend.updateFunctionMetadata(id, name, description);
    final function = Map<String, dynamic>.from(
      result['function'] is Map ? result['function'] as Map : result,
    );
    if (!_disposed) {
      functions.items = List.unmodifiable(
        functions.items.map(
          (item) => item['function_id'] == id ? function : item,
        ),
      );
      _changed();
    }
    return function;
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
