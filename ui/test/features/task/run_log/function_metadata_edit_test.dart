import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/execution/omniflow_execution_backend.dart';
import 'package:ui/features/task/pages/execution_history/widgets/function_metadata_dialog.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final artifact = <String, dynamic>{
    'schema_version': 'omniflow.function.v2',
    'function_id': 'recorded-function',
    'source_run_id': 'original-recording',
    'name': 'Before',
    'description': 'Original description',
    'steps': [
      {'source_state_id': 'frozen-source', 'step_index': 0},
    ],
    'bindings': [],
    'input_schema': {'type': 'object'},
    'agent_visible': true,
  };
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );

  test('manual edit saves current artifact without a run or model and retains provenance', () async {
    var current = Map<String, dynamic>.from(artifact);
    final names = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          final envelope = Map.from(call.arguments as Map);
          final args = Map.from(envelope['arguments'] as Map);
          names.add(envelope['name'] as String);
          if (envelope['name'] == 'get_function') return current;
          expect(envelope['name'], 'save_function');
          expect(args.keys, unorderedEquals(['function', 'enhance']));
          expect(args['enhance'], false);
          final saved = Map<String, dynamic>.from(args['function'] as Map);
          final expected = {...artifact, 'name': 'New', 'description': 'Edited'}
            ..remove('source_run_id');
          expect(saved, expected);
          current = {...saved, 'source_run_id': 'original-recording'};
          return {'success': true};
        });
    final result = await const OmniFlowExecutionBackend()
        .updateFunctionMetadata('recorded-function', ' New ', ' Edited ');
    expect(result['source_run_id'], 'original-recording');
    expect(result['steps'], artifact['steps']);
    expect(names, ['get_function', 'save_function', 'get_function']);
  });

  testWidgets(
    'validation and failed saves retain edits; repeated click does not duplicate saving',
    (tester) async {
      final pending = Completer<Map<String, dynamic>>();
      var saves = 0;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Builder(
              builder: (context) => TextButton(
                onPressed: () => showDialog<Map<String, dynamic>>(
                  context: context,
                  builder: (_) => FunctionMetadataDialog(
                    function: artifact,
                    onSave: (id, name, description) {
                      saves++;
                      expect(name, 'User name');
                      expect(description, 'User description');
                      return pending.future;
                    },
                  ),
                ),
                child: const Text('Open'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();
      final name = find.byKey(const ValueKey('function-edit-name'));
      final desc = find.byKey(const ValueKey('function-edit-description'));
      final save = find.byKey(const ValueKey('function-edit-save'));
      await tester.enterText(name, ' ');
      await tester.tap(save);
      await tester.pumpAndSettle();
      expect(saves, 0);
      expect(find.text('Required'), findsOneWidget);
      await tester.enterText(name, 'User name');
      await tester.enterText(desc, 'User description');
      await tester.tap(save);
      await tester.pump();
      await tester.tap(save);
      await tester.pump();
      expect(saves, 1);
      pending.completeError(StateError('Save unavailable'));
      await tester.pumpAndSettle();
      expect(find.textContaining('Save unavailable'), findsOneWidget);
      expect(find.text('User name'), findsOneWidget);
      expect(find.text('User description'), findsOneWidget);
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(find.byType(FunctionMetadataDialog), findsNothing);
    },
  );
}
