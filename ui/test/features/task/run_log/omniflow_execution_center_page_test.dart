import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/features/task/pages/execution_history/omniflow_execution_center_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const pluginChannel = MethodChannel('cn.com.omnimind.bot/PluginPlatform');
  const assistChannel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  const specialPermissionChannel = MethodChannel(
    'cn.com.omnimind.bot/SpecialPermissionEvent',
  );
  final toolCalls = <Map<Object?, Object?>>[];
  Map<String, Object?>? Function(String name, Map<Object?, Object?> call)?
  toolResponseOverride;

  setUp(() {
    toolCalls.clear();
    toolResponseOverride = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(pluginChannel, (call) async {
          if (call.method != 'list') return null;
          return <Object?>[
            <String, Object?>{
              'id': 'com.omnimind.omni-vlm-lite',
              'name': 'Omni VLM Lite',
              'version': '2.0.0',
              'interfaceVersion': 1,
              'description': 'GUI runtime',
              'publisher': 'OmniMind',
              'kind': 'runtime_bundle',
              'downloadSizeBytes': 0,
              'capabilities': <String>[],
              'settingsSchema': <String, Object?>{},
              'installed': true,
              'enabled': true,
              'compatible': true,
            },
          ];
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistChannel, (call) async {
          if (call.method != 'tools/call') return null;
          final arguments = Map<Object?, Object?>.from(call.arguments as Map);
          toolCalls.add(arguments);
          final override = toolResponseOverride?.call(
            arguments['name'].toString(),
            arguments,
          );
          if (override != null) return override;
          return switch (arguments['name']) {
            'list_functions' => <String, Object?>{
              'success': true,
              'count': 1,
              'functions': <Object?>[
                <String, Object?>{
                  'function_id': 'function.demo',
                  'source_run_id': 'run-1',
                  'name': '演示指令',
                  'description': '复用已成功执行的轨迹',
                  'input_schema': <String, Object?>{
                    'type': 'object',
                    'properties': <String, Object?>{
                      'query': <String, Object?>{
                        'type': 'string',
                        'description': 'Text to search for',
                      },
                    },
                    'required': <String>['query'],
                  },
                  'steps': <Object?>[
                    <String, Object?>{
                      'action': <String, Object?>{
                        'tool': 'click',
                        'args': <String, Object?>{'x': 500, 'y': 500},
                      },
                    },
                  ],
                },
              ],
            },
            'list_run_logs' => <String, Object?>{
              'success': true,
              'count': 1,
              'runs': <Object?>[
                <String, Object?>{
                  'run_id': 'run-1',
                  'goal': '完成演示',
                  'status': 'success',
                  'step_count': 1,
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
                      'resolved_model': 'qwen-vl-max',
                    },
                  },
                },
              ],
            },
            'get_function' => <String, Object?>{
              'success': true,
              'function': <String, Object?>{
                'function_id': 'function.demo',
                'name': '演示指令',
                'description': '复用已成功执行的操作',
                'agent_visible': true,
                'input_schema': <String, Object?>{
                  'type': 'object',
                  'properties': <String, Object?>{
                    'query': <String, Object?>{
                      'type': 'string',
                      'description': '要搜索的文本',
                    },
                  },
                  'required': <String>['query'],
                },
                'steps': <Object?>[
                  <String, Object?>{
                    'action': <String, Object?>{
                      'tool': 'click',
                      'args': <String, Object?>{'x': 500, 'y': 500},
                    },
                  },
                ],
              },
            },
            'function.demo' => <String, Object?>{'success': true},
            'save_function' => <String, Object?>{
              'success': true,
              'function_id': 'function.demo',
            },
            _ => <String, Object?>{'success': true},
          };
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(specialPermissionChannel, (call) async {
          if (call.method == 'isAndroidGuiAccessibilityReady') return true;
          return null;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(pluginChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(specialPermissionChannel, null);
  });

  testWidgets('loads only the active tab with a small first page', (
    tester,
  ) async {
    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(),
      ),
    );
    await tester.pumpAndSettle();

    final functionCalls = toolCalls
        .where((call) => call['name'] == 'list_functions')
        .toList(growable: false);
    expect(functionCalls, hasLength(1));
    expect((functionCalls.single['arguments'] as Map)['limit'], 20);
    expect((functionCalls.single['arguments'] as Map)['offset'], 0);
    expect(toolCalls.where((call) => call['name'] == 'list_run_logs'), isEmpty);

    await tester.tap(find.text('运行记录'));
    await tester.pumpAndSettle();

    expect(
      toolCalls.where((call) => call['name'] == 'list_run_logs'),
      hasLength(1),
    );
  });

  testWidgets('paginates Functions with the backend next offset', (
    tester,
  ) async {
    toolResponseOverride = (name, call) {
      if (name != 'list_functions') return null;
      final arguments = call['arguments'] as Map;
      final offset = arguments['offset'] as int;
      final count = offset == 0 ? 20 : 1;
      return <String, Object?>{
        'success': true,
        'count': count,
        'has_more': offset == 0,
        'next_offset': offset + count,
        'functions': List<Object?>.generate(
          count,
          (index) => <String, Object?>{
            'function_id': 'function.page.${offset + index}',
            'name': '分页指令 ${offset + index}',
            'description': '分页测试',
            'steps': <Object?>[],
            'input_schema': <String, Object?>{
              'properties': <String, Object?>{},
            },
          },
        ),
      };
    };

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(),
      ),
    );
    await tester.pumpAndSettle();
    await tester.fling(find.byType(ListView), const Offset(0, -2400), 1200);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('functions-load-more')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('functions-load-more')));
    await tester.pumpAndSettle();

    expect(find.text('分页指令 20'), findsOneWidget);
    expect(
      toolCalls.any(
        (call) =>
            call['name'] == 'list_functions' &&
            (call['arguments'] as Map)['limit'] == 20 &&
            (call['arguments'] as Map)['offset'] == 20,
      ),
      isTrue,
    );
  });

  testWidgets('paginates Run Logs with the backend next offset', (
    tester,
  ) async {
    toolResponseOverride = (name, call) {
      if (name != 'list_run_logs') return null;
      final arguments = call['arguments'] as Map;
      final offset = arguments['offset'] as int;
      final count = offset == 0 ? 20 : 1;
      return <String, Object?>{
        'success': true,
        'count': count,
        'has_more': offset == 0,
        'next_offset': offset + count,
        'runs': List<Object?>.generate(
          count,
          (index) => <String, Object?>{
            'run_id': 'run-page-${offset + index}',
            'goal': '分页记录 ${offset + index}',
            'status': 'succeeded',
            'step_count': 1,
          },
        ),
      };
    };

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(initialTab: 'run_logs'),
      ),
    );
    await tester.pumpAndSettle();
    await tester.fling(find.byType(ListView), const Offset(0, -2400), 1200);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('run-logs-load-more')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('run-logs-load-more')));
    await tester.pumpAndSettle();

    expect(find.text('分页记录 20'), findsOneWidget);
    expect(
      toolCalls.any(
        (call) =>
            call['name'] == 'list_run_logs' &&
            (call['arguments'] as Map)['limit'] == 20 &&
            (call['arguments'] as Map)['offset'] == 20,
      ),
      isTrue,
    );
  });

  testWidgets('opens Function enhancement in a new Agent conversation', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    ConversationThreadTarget? openedTarget;
    final router = GoRouter(
      routes: [
        GoRoute(
          path: '/',
          builder: (_, __) => const OmniFlowExecutionCenterPage(),
        ),
        GoRoute(
          path: '/home/chat',
          builder: (_, state) {
            final target = state.extra! as ConversationThreadTarget;
            openedTarget = target;
            return const Scaffold(body: Text('agent conversation'));
          },
        ),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(
      MaterialApp.router(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        routerConfig: router,
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('演示指令'));
    await tester.pumpAndSettle();

    expect(find.text('复用指令详情'), findsOneWidget);
    expect(find.text('参数'), findsOneWidget);
    expect(find.text('步骤'), findsOneWidget);
    expect(find.text('要搜索的文本'), findsOneWidget);
    expect(toolCalls.any((call) => call['name'] == 'get_function'), isTrue);

    expect(
      find.byKey(const ValueKey('function-detail-enhance')),
      findsOneWidget,
    );
    await tester.tap(find.byKey(const ValueKey('function-detail-enhance')));
    await tester.pumpAndSettle();
    expect(find.text('agent conversation'), findsOneWidget);
    expect(openedTarget?.mode, ConversationMode.agent);
    expect(openedTarget?.isNewConversation, isTrue);
    expect(openedTarget?.requestKey, isNotEmpty);
    expect(
      openedTarget?.initialMessage,
      allOf(
        contains('function_id: function.demo'),
        contains('source_run_id: run-1'),
        contains('get_function'),
        contains('save_function'),
        contains('enhance=true'),
      ),
    );
    expect(toolCalls.any((call) => call['name'] == 'update_function'), isFalse);
  });

  test('builds a complete Function enhancement Agent request', () {
    final prompt = buildFunctionEnhancementPrompt({
      'function_id': 'function.demo',
      'source_run_id': 'run-1',
      'name': '演示指令',
      'steps': <Object?>[],
    });

    expect(prompt, contains('get_function'));
    expect(prompt, contains('save_function'));
    expect(prompt, contains('enhance=true'));
    expect(prompt, contains('source_run_id='));
    expect(prompt, contains('由 OmniFlow 内置的官方增强流程'));
    expect(prompt, contains('不要调用 list_run_logs/get_run_log/get_function'));
    expect(prompt, isNot(contains('"source_run_id":"run-1"')));
    expect(prompt, contains('function_id: function.demo'));
    expect(prompt, contains('不要执行它'));
  });

  testWidgets('opens the requested Function directly', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(initialFunctionId: 'function.demo'),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('function-detail-sheet')), findsOneWidget);
    expect(find.text('演示指令'), findsWidgets);
    expect(toolCalls.any((call) => call['name'] == 'get_function'), isTrue);
  });

  testWidgets('uses consistent Chinese labels across the execution center', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('复用指令'), findsOneWidget);
    expect(find.byType(Card), findsNothing);
    expect(find.text('轨迹'), findsNothing);
    expect(find.text('运行记录'), findsOneWidget);
    expect(find.text('RunLog'), findsNothing);
    await tester.tap(find.text('执行'));
    await tester.pumpAndSettle();
    expect(find.text('填写执行参数'), findsOneWidget);
    await tester.enterText(find.byType(TextFormField), 'replay acceptance');
    await tester.tap(find.text('开始执行'));
    await tester.pumpAndSettle();
    expect(
      toolCalls.any(
        (call) =>
            call['name'] == 'function.demo' &&
            (call['arguments'] as Map?)?['query'] == 'replay acceptance',
      ),
      isTrue,
    );
    final replayCall = toolCalls.lastWhere(
      (call) => call['name'] == 'function.demo',
    );
    expect(replayCall['goal'], contains('演示指令'));
    expect(replayCall['goal'], contains('复用已成功执行的轨迹'));
    expect(replayCall['goal'], contains('replay acceptance'));

    await tester.tap(find.text('运行记录'));
    await tester.pumpAndSettle();
    expect(find.byType(Card), findsNothing);
    expect(find.byType(Chip), findsNothing);
    expect(find.text('2026-07-31 09:18:00'), findsOneWidget);
    expect(find.text('2.35 s'), findsOneWidget);
    expect(find.text('模型用量 1.23k'), findsOneWidget);
    expect(find.text('qwen-vl-max'), findsOneWidget);
    expect(find.text('2 次 VLM 调用'), findsOneWidget);
    expect(find.text('成功'), findsOneWidget);
    expect(find.byKey(const ValueKey('run-log-open-run-1')), findsOneWidget);
    await tester.tap(find.text('注册为复用指令'));
    await tester.pumpAndSettle();
    expect(toolCalls.any((call) => call['name'] == 'save_function'), isTrue);
    expect(find.byKey(const ValueKey('function-detail-sheet')), findsOneWidget);
    expect(
      find.byKey(const ValueKey('function-detail-enhance')),
      findsOneWidget,
    );
  });

  testWidgets('shows the runtime error without a Dart Bad state prefix', (
    tester,
  ) async {
    toolResponseOverride = (name, call) {
      if (name != 'function.demo') return null;
      return <String, Object?>{
        'success': false,
        'error_code': 'FUNCTION_CALL_FAILED',
        'error_message': 'android_gui_accessibility_not_ready',
      };
    };

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('执行'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField), 'replay failure');
    await tester.tap(find.text('开始执行'));
    await tester.pumpAndSettle();

    expect(find.text('android_gui_accessibility_not_ready'), findsOneWidget);
    expect(find.textContaining('Bad state:'), findsNothing);
  });

  testWidgets('shows the Function linked to a RunLog and opens it', (
    tester,
  ) async {
    toolResponseOverride = (name, call) {
      if (name != 'list_functions') return null;
      return <String, Object?>{
        'success': true,
        'functions': <Object?>[
          <String, Object?>{
            'function_id': 'function.demo',
            'source_run_id': 'run-1',
            'name': '演示指令',
            'description': '复用已成功执行的轨迹',
            'input_schema': <String, Object?>{
              'type': 'object',
              'properties': <String, Object?>{},
            },
            'steps': <Object?>[],
          },
        ],
      };
    };
    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(initialTab: 'run_logs'),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('查看复用指令'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('run-log-function-run-1')));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('function-detail-sheet')), findsOneWidget);
    expect(
      find.byKey(const ValueKey('function-detail-enhance')),
      findsOneWidget,
    );
  });

  testWidgets(
    'registration uses the saved Function immediately when detail reload fails',
    (tester) async {
      toolResponseOverride = (name, call) {
        if (name == 'save_function') {
          return <String, Object?>{
            'success': true,
            'function_id': 'function.registered',
            'function': <String, Object?>{
              'function_id': 'function.registered',
              'source_run_id': 'run-1',
              'name': '刚注册的指令',
              'description': '注册响应已经包含完整 Function',
              'agent_visible': true,
              'input_schema': <String, Object?>{
                'type': 'object',
                'properties': <String, Object?>{},
              },
              'steps': <Object?>[],
            },
          };
        }
        if (name == 'get_function') {
          throw PlatformException(code: 'FUNCTION_NOT_READY');
        }
        return null;
      };

      await tester.pumpWidget(
        const MaterialApp(
          locale: Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: OmniFlowExecutionCenterPage(initialTab: 'run_logs'),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.text('注册为复用指令'));
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey('function-detail-sheet')),
        findsOneWidget,
      );
      expect(find.text('刚注册的指令'), findsWidgets);
      expect(find.text('复用指令'), findsWidgets);
      expect(
        toolCalls.where((call) => call['name'] == 'get_function'),
        isEmpty,
      );
      expect(find.textContaining('StateError: 注册失败'), findsNothing);
    },
  );

  testWidgets('keeps Enhance visible on a narrow phone', (tester) async {
    tester.view.physicalSize = const Size(280, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('演示指令'));
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey('function-detail-enhance')),
      findsOneWidget,
    );
    expect(find.text('增强'), findsOneWidget);
    expect(find.byKey(const ValueKey('function-detail-run')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('opens directly on the Run Logs tab', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(initialTab: 'run_logs'),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('完成演示'), findsOneWidget);
    expect(find.byKey(const ValueKey('run-log-open-run-1')), findsOneWidget);
    expect(find.text('演示指令'), findsNothing);
  });

  testWidgets('uses consistent English labels across the execution center', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('en'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Functions'), findsOneWidget);
    expect(find.text('Run Logs'), findsOneWidget);
    expect(find.text('Run'), findsOneWidget);
    expect(find.text('复用指令'), findsNothing);

    await tester.tap(find.text('Run Logs'));
    await tester.pumpAndSettle();
    expect(find.text('Succeeded'), findsOneWidget);
    expect(find.text('1.23k tokens'), findsOneWidget);
    expect(find.text('2 VLM calls'), findsOneWidget);
    expect(find.byKey(const ValueKey('run-log-open-run-1')), findsOneWidget);
  });
}
