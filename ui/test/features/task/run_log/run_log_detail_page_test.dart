import 'dart:io';

import 'package:ui/features/task/execution/omniflow_execution_backend.dart';
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
                  'source_run_id': 'original-recording',
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
              'function_id': 'function.settings',
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

  for (final official in [true, false]) {
    for (final point in [
      const Offset(500, 500),
      const Offset(348.14814814814815, 274.58333333333337),
      const Offset(
        600,
        1200,
      ), // Out of the canonical range, not pixel fallback.
    ]) {
      testWidgets(
        'screenshot shows ${official ? "official" : "legacy"} action point $point',
        (tester) async {
          final screenshot = File('test/fixtures/run_log_1200x2400.png');
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
              .setMockMethodCallHandler(assistChannel, (call) async {
                final args = Map<Object?, Object?>.from(call.arguments as Map);
                if (args['name'] == 'list_functions') {
                  return {'success': true, 'functions': []};
                }
                if (args['name'] == 'get_run_log_state') {
                  return {
                    'success': true,
                    'state_id': 'point-state',
                    'screenshot_path': screenshot.path,
                    'display': {'width': 1080, 'height': 2400},
                  };
                }
                return {
                  'run_id': 'point-run',
                  'status': 'succeeded',
                  'steps': [
                    {
                      'step_index': 0,
                      'before_state_id': 'point-state',
                      'action': official
                          ? {
                              'action_type': 'click',
                              'x': point.dx * 1080 / 1000,
                              'y': point.dy * 2400 / 1000,
                            }
                          : {
                              'tool': 'click',
                              'args': {'x': point.dx, 'y': point.dy},
                            },
                      'result': {'success': true},
                    },
                  ],
                };
              });
          await tester.pumpWidget(
            const MaterialApp(
              locale: Locale('en'),
              home: RunLogDetailPage(
                runId: 'point-run',
                backend: OmniFlowExecutionBackend(),
              ),
            ),
          );
          await tester.pumpAndSettle();
          await tester.runAsync(() async {
            await precacheImage(
              FileImage(screenshot),
              tester.element(find.byType(RunLogDetailPage)),
            );
          });
          await tester.tap(find.textContaining('Tap · '));
          await tester.pumpAndSettle();
          await tester.tap(find.text('Action screenshot'));
          await tester.pumpAndSettle();
          if (point.dy > 1000) {
            expect(find.byIcon(Icons.my_location_rounded), findsNothing);
            return;
          }
          expect(find.byIcon(Icons.my_location_rounded), findsOneWidget);
          final bounds = tester.getRect(find.byType(Image));
          expect(bounds.width, lessThan(1200));
          final marker = tester.getCenter(
            find.byIcon(Icons.my_location_rounded),
          );
          expect(
            marker.dx,
            closeTo(bounds.left + bounds.width * point.dx / 1000, 0.01),
          );
          expect(
            marker.dy,
            closeTo(bounds.top + bounds.height * point.dy / 1000, 0.01),
          );
        },
      );
    }
  }

  testWidgets('renders official flat RunLog actions and keeps raw evidence', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistChannel, (call) async {
          final arguments = Map<Object?, Object?>.from(call.arguments as Map);
          if (arguments['name'] == 'list_functions') {
            return {'success': true, 'functions': <Object?>[]};
          }
          return {
            'schema_version': 'omniflow.run_log.v1',
            'run_id': 'official-run',
            'goal': 'Search Settings',
            'status': 'succeeded',
            'success': true,
            'steps': [
              {
                'step_index': 0,
                'observation': {
                  'auxiliaries': {'state_id': 'before-1'},
                },
                'action': {
                  'action_type': 'input_text',
                  'text': 'wifi',
                  'x': 400,
                  'y': 80,
                },
                'result': {'success': true},
                'next_observation': {
                  'auxiliaries': {'state_id': 'after-1'},
                },
              },
              {
                'step_index': 1,
                'action': {
                  'action_type': 'keyboard_enter',
                  'keycode': 'KEYCODE_back',
                },
                'result': {'success': true},
              },
            ],
          };
        });
    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('en'),
        home: RunLogDetailPage(
          runId: 'official-run',
          backend: OmniFlowExecutionBackend(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Enter text · wifi'), findsOneWidget);
    expect(find.text('Press key · KEYCODE_back'), findsOneWidget);
    await tester.tap(find.text('Enter text · wifi'));
    await tester.pumpAndSettle();
    expect(find.textContaining('"action_type": "input_text"'), findsOneWidget);
    expect(find.text('Action screenshot'), findsOneWidget);
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
          home: RunLogDetailPage(
            runId: 'run-1',
            backend: OmniFlowExecutionBackend(),
          ),
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
