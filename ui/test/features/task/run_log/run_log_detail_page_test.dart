import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/pages/execution_history/run_log_detail_page.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const assistChannel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistChannel, (call) async {
          if (call.method != 'tools/call') return null;
          final arguments = Map<Object?, Object?>.from(call.arguments as Map);
          if (arguments['name'] == 'list_functions') {
            return <String, Object?>{
              'success': true,
              'functions': <Object?>[
                <String, Object?>{
                  'function_id': 'function.settings',
                  'source_run_id': 'run-1',
                  'name': '打开设置',
                },
              ],
            };
          }
          if (arguments['name'] != 'get_run_log') {
            return <String, Object?>{'success': true};
          }
          return <String, Object?>{
            'run_id': 'run-1',
            'goal': 'Open Settings',
            'status': 'succeeded',
            'started_at_ms': DateTime(
              2026,
              7,
              31,
              9,
              18,
            ).millisecondsSinceEpoch,
            'finished_at_ms': DateTime(
              2026,
              7,
              31,
              9,
              18,
              2,
              345,
            ).millisecondsSinceEpoch,
            'diagnostics': <String, Object?>{
              'duration_ms': 2345,
              'token_usage': <String, Object?>{
                'prompt_tokens': 1000,
                'completion_tokens': 234,
                'total_tokens': 1234,
                'call_count': 2,
                'cached_tokens': 100,
                'resolved_model': 'qwen-vl-max-online-production-2026-07-31',
              },
              'token_usage_by_step': <Object?>[
                <String, Object?>{
                  'step_index': 0,
                  'tool': 'click',
                  'token_usage': <String, Object?>{
                    'prompt_tokens': 1000,
                    'completion_tokens': 234,
                    'total_tokens': 1234,
                    'resolved_model':
                        'qwen-vl-max-online-production-2026-07-31',
                  },
                },
              ],
            },
            'steps': <Object?>[
              <String, Object?>{
                'step_index': 0,
                'before_state_id': 'before-1',
                'action': <String, Object?>{
                  'tool': 'click',
                  'args': <String, Object?>{'x': 100, 'y': 200},
                },
                'result': <String, Object?>{'success': true},
                'metadata': <String, Object?>{
                  'summary': 'Tap the visible Settings result',
                  'thinking': 'The Settings result is visible and enabled.',
                },
                'after_state_id': 'after-1',
              },
            ],
          };
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistChannel, null);
  });

  testWidgets(
    'uses compact vlm-core timeline components for canonical RunLog',
    (tester) async {
      tester.view.physicalSize = const Size(360, 800);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(
        const MaterialApp(
          locale: Locale('en'),
          home: RunLogDetailPage(runId: 'run-1'),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Execution completed'), findsOneWidget);
      expect(find.text('Open Settings'), findsOneWidget);
      expect(find.text('Steps 1'), findsOneWidget);
      expect(find.text('Started 2026-07-31 09:18:00'), findsOneWidget);
      expect(find.text('Duration 2.35 s'), findsOneWidget);
      expect(
        find.text('Model qwen-vl-max-online-production-2026-07-31'),
        findsOneWidget,
      );
      expect(find.text('Calls 2'), findsOneWidget);
      expect(find.text('Tokens 1.23k'), findsOneWidget);
      expect(find.text('Prompt 1.00k'), findsOneWidget);
      expect(find.text('Completion 234'), findsOneWidget);
      expect(find.text('Cached 100'), findsOneWidget);
      expect(find.text('Step 1'), findsOneWidget);
      expect(find.text('Tap · 100, 200'), findsOneWidget);
      expect(find.text('1.23k tokens'), findsOneWidget);
      expect(find.textContaining('"tool": "click"'), findsNothing);
      expect(find.text('Linked Function'), findsOneWidget);
      expect(find.text('打开设置'), findsOneWidget);
      expect(
        find.byKey(const ValueKey('run-log-view-function')),
        findsOneWidget,
      );

      await tester.tap(find.text('Tap · 100, 200'));
      await tester.pumpAndSettle();

      expect(find.text('Action evidence'), findsOneWidget);
      expect(find.text('Action screenshot'), findsOneWidget);
      expect(find.text('Before action'), findsNothing);
      expect(find.text('After action'), findsNothing);
      expect(find.text('Decision'), findsOneWidget);
      expect(find.text('Tap the visible Settings result'), findsNWidgets(2));
      expect(find.text('Raw reasoning (optional)'), findsOneWidget);
      expect(
        find.text('The Settings result is visible and enabled.'),
        findsNothing,
      );
      expect(find.text('Action details'), findsOneWidget);
      expect(find.text('Total 1.23k'), findsOneWidget);
      expect(find.textContaining('"tool": "click"'), findsOneWidget);

      await tester.tap(find.text('Raw reasoning (optional)'));
      await tester.pumpAndSettle();
      expect(
        find.text('The Settings result is visible and enabled.'),
        findsOneWidget,
      );
    },
  );
}
