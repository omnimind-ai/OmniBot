import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/run_log/omniflow_tool_client.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final calls = <MethodCall>[];

  setUp(() {
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          final arguments = Map<Object?, Object?>.from(call.arguments as Map);
          return switch (arguments['name']) {
            'list_functions' => <String, Object?>{
              'success': true,
              'functions': <Object?>[],
            },
            'list_run_logs' => <String, Object?>{
              'success': true,
              'runs': <Object?>[],
            },
            _ => <String, Object?>{'success': true},
          };
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test(
    'uses the shared tools call seam for Function and RunLog operations',
    () async {
      await OmniFlowToolClient.listFunctions();
      await OmniFlowToolClient.listRunLogs();
      await OmniFlowToolClient.saveFunctionFromRunLog('run-1');
      await OmniFlowToolClient.replayFunction(
        'function.demo',
        <String, dynamic>{'query': 'ice'},
        goal: '演示指令\n参数: {"query":"ice"}',
      );

      expect(calls.map((call) => call.method), everyElement('tools/call'));
      expect(calls.map((call) => (call.arguments as Map)['name']), <String>[
        'list_functions',
        'list_run_logs',
        'save_function',
        'function.demo',
      ]);
      expect(
        ((calls[2].arguments as Map)['arguments'] as Map)['run_id'],
        'run-1',
      );
      expect(
        (calls.last.arguments as Map)['goal'],
        '演示指令\n参数: {"query":"ice"}',
      );
    },
  );

  test('parses the saved Function without a second backend read', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          return <String, Object?>{
            'success': true,
            'function_id': 'function.saved',
            'function': <String, Object?>{
              'function_id': 'function.saved',
              'name': '已保存指令',
              'steps': <Object?>[],
            },
          };
        });

    final result = await OmniFlowToolClient.registerFunctionFromRunLog('run-1');

    expect(result.success, isTrue);
    expect(result.functionId, 'function.saved');
    expect(result.function?['source_run_id'], 'run-1');
    expect(calls.map((call) => (call.arguments as Map)['name']), <String>[
      'save_function',
    ]);
  });

  test('rejects a successful registration response without function id', () {
    final result = OmniFlowFunctionRegistrationResult.fromPayload(
      <String, dynamic>{'success': true},
      runId: 'run-1',
    );

    expect(result.success, isFalse);
    expect(result.errorMessage, contains('function_id'));
  });

  test('parses the official plural save_function response', () {
    final result = OmniFlowFunctionRegistrationResult.fromPayload(
      <String, dynamic>{
        'success': true,
        'function_ids': <dynamic>['function.saved'],
        'functions': <dynamic>[
          <String, dynamic>{'function_id': 'function.saved', 'name': '已保存指令'},
        ],
      },
      runId: 'run-2',
    );

    expect(result.success, isTrue);
    expect(result.functionId, 'function.saved');
    expect(result.function?['source_run_id'], 'run-2');
  });
}
