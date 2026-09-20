import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/execution/execution_backend.dart';
import 'package:ui/features/task/execution/execution_center_controller.dart';
import 'package:ui/features/task/pages/execution_history/execution_center_page.dart';

class MemoryExecutionBackend implements ExecutionBackend {
  final requests = <(ExecutionCollectionKind, int)>[];
  Future<ExecutionPage> Function(ExecutionCollectionKind, int)? respond;
  @override
  Future<ExecutionAvailability> availability() async =>
      const ExecutionAvailability(
        installed: true,
        enabled: true,
        settingsRoute: '/memory',
      );
  @override
  Future<ExecutionPage> list(
    ExecutionCollectionKind kind, {
    required int limit,
    required int offset,
  }) async {
    requests.add((kind, offset));
    return respond?.call(kind, offset) ??
        ExecutionPage(
          [
            if (kind == ExecutionCollectionKind.functions)
              {
                'function_id': 'memory-task',
                'name': 'Independent backend task',
                'input_schema': {},
              },
          ],
          hasMore: false,
          nextOffset: 1,
        );
  }

  @override
  Future<void> enable() async {}
  @override
  Future<bool> authorize(BuildContext context) async => true;
  @override
  Future<Map<String, dynamic>> getFunction(String id) async => {
    'function_id': id,
  };
  @override
  Future<Map<String, dynamic>> updateFunctionMetadata(
    String id,
    String name,
    String description,
  ) async => {'function_id': id, 'name': name, 'description': description};
  @override
  Future<Map<String, dynamic>> getRunLog(String id) async => {'run_id': id};
  @override
  Future<Map<String, dynamic>> getRunLogState(String id) async => {};
  int runs = 0;
  @override
  Future<void> run(
    Map<String, dynamic> function,
    Map<String, dynamic> arguments,
  ) async {
    runs++;
  }

  @override
  Future<void> delete(String id) async {}
  @override
  Future<Map<String, dynamic>> register(Map<String, dynamic> runLog) async => {
    'function_id': 'registered',
    'source_run_id': runLog['run_id'],
  };
  @override
  String enhancementRequest(Map<String, dynamic> function) => 'Enhance';
  @override
  Future<void> record({
    required BuildContext context,
    required FocusNode focus,
    required bool Function() isMounted,
  }) async {}
}

void main() {
  testWidgets(
    'the page lists and runs with an independent backend and no native channels',
    (tester) async {
      final backend = MemoryExecutionBackend();
      await tester.pumpWidget(
        MaterialApp(home: ExecutionCenterPage(backend: backend)),
      );
      await tester.pumpAndSettle();
      expect(find.text('Independent backend task'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('function-run-memory-task')));
      await tester.pumpAndSettle();
      expect(backend.runs, 1);
      expect(tester.takeException(), isNull);
    },
  );

  test('one paging owner deduplicates IDs, uses backend offsets, preserves data after error', () async {
    final backend = MemoryExecutionBackend();
    final controller = ExecutionCenterController(backend);
    await controller.initialize();
    backend.respond = (_, offset) async => const ExecutionPage(
      [
        {'function_id': 'memory-task'},
        {'function_id': 'second'},
      ],
      hasMore: true,
      nextOffset: 37,
    );
    await controller.load(controller.functions, reset: false);
    expect(controller.functions.items.map((f) => f['function_id']), [
      'memory-task',
      'second',
    ]);
    backend.respond = (_, offset) async =>
        throw const ExecutionFailure('offline');
    await controller.load(controller.functions, reset: false);
    expect(backend.requests.last.$2, 37);
    expect(controller.functions.items.length, 2);
    expect(controller.functions.error, 'offline');
    expect(controller.functions.loading, false);
    controller.dispose();
  });

  test(
    'concurrent refresh joins no second request and disposal ignores late data',
    () async {
      final backend = MemoryExecutionBackend();
      final controller = ExecutionCenterController(backend);
      await controller.initialize();
      final pending = Completer<ExecutionPage>();
      backend.respond = (_, offset) => pending.future;
      final loading = controller.load(controller.functions, reset: true);
      await controller.load(controller.functions, reset: true);
      expect(backend.requests.length, 2);
      controller.dispose();
      pending.complete(
        const ExecutionPage(
          [
            {'function_id': 'late'},
          ],
          hasMore: false,
          nextOffset: 1,
        ),
      );
      await loading;
      expect(controller.functions.items.single['function_id'], 'memory-task');
    },
  );

  test('execution UI does not import provider or tool transport details', () {
    final page = File(
      'lib/features/task/pages/execution_history/execution_center_page.dart',
    ).readAsStringSync();
    for (final forbidden in [
      'OmniFlow',
      'OmniPluginService',
      'tools/call',
      'run_function',
      'save_function',
      'MethodChannel',
    ]) {
      expect(page, isNot(contains(forbidden)));
    }
  });

  test('canonical replay identity preserves the original source identity', () {
    final function = <String, dynamic>{
      'function_id': 'saved',
      'source_run_id': 'recorded',
    };
    final run = <String, dynamic>{
      'run_id': 'replay',
      'diagnostics': {'function_id': 'saved'},
    };
    expect(linkedExecutionFunction(run, [function]), same(function));
    expect(function['source_run_id'], 'recorded');
    final imported = <String, dynamic>{'function_id': 'saved'};
    expect(linkedExecutionFunction(run, [imported]), same(imported));
    expect(imported.containsKey('source_run_id'), false);
  });
}
