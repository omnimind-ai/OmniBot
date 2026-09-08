import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_request_card.dart';
import 'package:ui/services/storage_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const requestIdentity = 'request-1.request-1-card.mode.1000';
  const requestStorageKey = 'agent_request_response.$requestIdentity';
  const agentRuntimeChannel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  const assistCoreChannel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(assistCoreChannel, (call) async => null);
  });

  tearDown(() {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(agentRuntimeChannel, null);
    messenger.setMockMethodCallHandler(assistCoreChannel, null);
  });

  testWidgets('compact historical requests cannot offer expired actions', (
    tester,
  ) async {
    for (final extra in <Map<String, dynamic>>[
      {'status': 'expired'},
      {'status': 'pending', 'interactionUnavailable': true},
    ]) {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: AgentRequestNotice(
              cardData: {
                'type': 'agent_request',
                'requestKind': 'approval',
                'requestId': 'old-request',
                'title': 'Implement this plan?',
                ...extra,
              },
            ),
          ),
        ),
      );
      expect(find.text('允许'), findsNothing);
      expect(find.text('拒绝'), findsNothing);
    }
  });

  testWidgets(
    'restored compact request shows its outcome without wire diagnostics',
    (tester) async {
      for (final status in ['accepted', 'declined', 'submitted', 'expired']) {
        await tester.pumpWidget(
          MaterialApp(
            home: Scaffold(
              body: AgentRequestNotice(
                key: ValueKey(status),
                cardData: {
                  'requestKind': 'approval',
                  'title': 'Implement this plan?',
                  'status': status,
                  'interactionUnavailable': true,
                },
              ),
            ),
          ),
        );
        expect(find.textContaining('requestId'), findsNothing);
        expect(find.byType(TextButton), findsNothing);
        expect(
          find.text(switch (status) {
            'accepted' => '已允许',
            'declined' => '已拒绝',
            'submitted' => '已提交',
            _ => '已过期',
          }),
          findsOneWidget,
        );
      }
    },
  );

  testWidgets(
    'unavailable compact input does not invite a reply or invent an outcome',
    (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: AgentRequestNotice(
              cardData: {
                'requestKind': 'user_input',
                'status': 'pending',
                'title': 'Implement this plan?',
                'interactionUnavailable': true,
                'interactionUnavailableReason': 'session_ended',
              },
            ),
          ),
        ),
      );
      expect(find.textContaining('requestId'), findsNothing);
      expect(find.text('请直接在下方输入回复'), findsNothing);
      expect(find.text('历史请求，无法继续操作'), findsOneWidget);
      expect(find.text('已允许'), findsNothing);
    },
  );

  testWidgets(
    'missing request identity is unavailable, not an assumed historical outcome',
    (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: AgentRequestNotice(
              cardData: {
                'requestKind': 'approval',
                'status': 'pending',
                'title': 'Permission',
              },
            ),
          ),
        ),
      );
      expect(find.text('该请求当前无法操作'), findsOneWidget);
      expect(find.byType(TextButton), findsNothing);
      expect(find.text('已允许'), findsNothing);
      expect(find.textContaining('历史'), findsNothing);
    },
  );

  testWidgets('compact acknowledged approval persists beyond widget disposal', (
    tester,
  ) async {
    final calls = <MethodCall>[];
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(
      agentRuntimeChannel,
      (call) async => {'ok': true},
    );
    messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
      calls.add(call);
      return null;
    });
    final card = <String, dynamic>{
      ..._requestCardData(),
      'requestKind': 'approval',
      'status': 'pending',
    };
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: AgentRequestNotice(cardData: card)),
      ),
    );
    await tester.tap(find.text('拒绝'));
    await tester.pumpAndSettle();
    expect(card['status'], 'declined');
    expect(
      calls.where((c) => c.method == 'upsertConversationUiCard'),
      isNotEmpty,
    );
    await tester.pumpWidget(const SizedBox());
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: AgentRequestNotice(cardData: Map.of(card))),
      ),
    );
    expect(find.text('允许'), findsNothing);
    expect(find.text('拒绝'), findsNothing);
  });

  testWidgets('renders requestUserInput options and submits selection', (
    tester,
  ) async {
    MethodCall? submittedCall;
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(agentRuntimeChannel, (call) async {
      submittedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: AgentRequestCard(cardData: _requestCardData())),
      ),
    );

    expect(find.text('Plan'), findsOneWidget);
    expect(find.text('Chat'), findsOneWidget);
    expect(find.byType(TextField), findsNothing);
    expect(find.text('No, tell Claude Code how to adjust'), findsNothing);
    expect(find.text('ESC'), findsNothing);

    await tester.tap(find.text('Chat'));
    await tester.pump();
    await tester.tap(find.text('Submit ↵'));
    await tester.pumpAndSettle();

    expect(submittedCall?.method, 'respondToServerRequest');
    expect(submittedCall?.arguments, containsPair('requestId', 'request-1'));
    final arguments = Map<String, dynamic>.from(
      submittedCall!.arguments as Map,
    );
    expect(arguments['sessionId'], 'session-1');
    expect(arguments['agentId'], _requestCardData()['agentId']);
    expect(arguments['conversationId'], 42);
    final response = Map<String, dynamic>.from(arguments['response'] as Map);
    final answers = Map<String, dynamic>.from(response['answers'] as Map);
    final mode = Map<String, dynamic>.from(answers['mode'] as Map);
    expect(mode['answers'], <String>['Chat']);
    expect(find.text('submitted: Chat'), findsOneWidget);

    final stored = jsonDecode(StorageService.getString(requestStorageKey)!);
    expect(stored, containsPair('identity', requestIdentity));
  });

  testWidgets('ignore submits empty request user input answers', (
    tester,
  ) async {
    MethodCall? submittedCall;
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(agentRuntimeChannel, (call) async {
      submittedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: AgentRequestCard(cardData: _requestCardData())),
      ),
    );

    await tester.tap(find.text('Ignore'));
    await tester.pumpAndSettle();

    final arguments = Map<String, dynamic>.from(
      submittedCall!.arguments as Map,
    );
    expect(arguments['requestId'], 'request-1');
    expect(arguments['sessionId'], 'session-1');
    expect(arguments['agentId'], _requestCardData()['agentId']);
    expect(arguments['conversationId'], 42);
    expect(arguments['response'], {'answers': <String, dynamic>{}});
    expect(find.text('ignored'), findsOneWidget);
  });

  testWidgets('pending request ignores legacy submitted cache', (tester) async {
    await StorageService.setString(
      requestStorageKey,
      jsonEncode(<String, dynamic>{
        'status': 'submitted',
        'answers': <String>['Chat'],
      }),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: AgentRequestCard(cardData: _requestCardData())),
      ),
    );

    expect(find.text('submitted: Chat'), findsNothing);
    expect(find.text('Plan'), findsOneWidget);
    expect(find.text('Chat'), findsOneWidget);
    expect(find.text('No, tell Claude Code how to adjust'), findsNothing);
  });

  testWidgets('pending request restores exact submitted cache after refresh', (
    tester,
  ) async {
    await StorageService.setString(
      requestStorageKey,
      jsonEncode(<String, dynamic>{
        'identity': requestIdentity,
        'status': 'submitted',
        'answers': <String>['Chat'],
      }),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: AgentRequestCard(cardData: _requestCardData())),
      ),
    );

    expect(find.text('submitted: Chat'), findsOneWidget);
    expect(find.text('Plan'), findsNothing);
    expect(find.byType(TextField), findsNothing);
  });

  testWidgets('does not render duplicate title and detail question text', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AgentRequestCard(
            cardData: _requestCardData(detail: 'Choose mode'),
          ),
        ),
      ),
    );

    expect(find.text('Choose mode'), findsOneWidget);
  });

  testWidgets('request card does not render a second free-text input', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SizedBox(
            width: 420,
            child: AgentRequestCard(cardData: _requestCardData()),
          ),
        ),
      ),
    );

    expect(
      find.byKey(const ValueKey('agent-request-option-row-1')),
      findsOneWidget,
    );
    expect(find.byType(TextField), findsNothing);
    expect(find.text('Describe the adjustment'), findsNothing);
  });

  testWidgets('free-text requests also leave input to the main composer', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AgentRequestCard(
            cardData: _requestCardData(rawParams: <String, dynamic>{}),
          ),
        ),
      ),
    );

    expect(find.byType(TextField), findsNothing);
    expect(find.text('Ignore'), findsOneWidget);
  });

  testWidgets('compact notice resolves the live ACP schema question', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AgentRequestNotice(
            cardData: <String, dynamic>{
              'type': 'agent_request',
              'requestKind': 'user_input',
              'requestId': 'elicitation-live',
              'title': 'The agent needs your input.',
              'detail': 'The agent needs your input.',
              'rawParamsJson': jsonEncode(<String, dynamic>{
                'message': 'The agent needs your input.',
                'requestedSchema': <String, dynamic>{
                  'type': 'object',
                  'properties': <String, dynamic>{
                    'details': <String, dynamic>{
                      'type': 'string',
                      'title': '插件详情',
                      'description': '请提供插件名称和用途',
                      'oneOf': <Map<String, dynamic>>[
                        <String, dynamic>{
                          'const': 'android',
                          'title': 'Android 插件',
                        },
                      ],
                    },
                  },
                },
              }),
            },
          ),
        ),
      ),
    );

    expect(find.text('插件详情'), findsOneWidget);
    expect(find.textContaining('请提供插件名称和用途'), findsOneWidget);
    expect(find.textContaining('可选：Android 插件'), findsOneWidget);
    expect(find.text('The agent needs your input.'), findsNothing);
    expect(find.byType(TextField), findsNothing);
  });

  testWidgets('ACP elicitation renders a form and submits typed primitives', (
    tester,
  ) async {
    MethodCall? submittedCall;
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(agentRuntimeChannel, (call) async {
      submittedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AgentRequestCard(
            cardData: <String, dynamic>{
              'type': 'agent_request',
              'requestId': 'elicitation-1',
              'cardId': 'elicitation-1-card',
              'requestKind': 'user_input',
              'structuredElicitation': true,
              'title': 'Project details',
              'detail': 'Provide the project information',
              'status': 'pending',
              'rawParamsJson': jsonEncode(<String, dynamic>{
                'requestedSchema': <String, dynamic>{
                  'type': 'object',
                  'required': <String>['name', 'count'],
                  'properties': <String, dynamic>{
                    'name': <String, dynamic>{
                      'type': 'string',
                      'title': 'Name',
                    },
                    'count': <String, dynamic>{
                      'type': 'integer',
                      'title': 'Count',
                    },
                  },
                },
              }),
            },
          ),
        ),
      ),
    );

    expect(find.byType(TextField), findsNWidgets(2));
    await tester.enterText(find.byType(TextField).at(0), 'demo');
    await tester.enterText(find.byType(TextField).at(1), '3');
    // TextField.onChanged updates the form validity in a stateful card. Give
    // that state transition a frame before tapping the submit action.
    await tester.pump();
    await tester.tap(find.text('Submit ↵'));
    await tester.pumpAndSettle();

    expect(submittedCall?.method, 'respondToServerRequest');
    final arguments = Map<String, dynamic>.from(
      submittedCall!.arguments as Map,
    );
    expect(arguments['requestId'], 'elicitation-1');
    expect(arguments['response'], {
      'action': 'accept',
      'content': {'name': 'demo', 'count': 3},
    });
  });

  testWidgets('fills the available message width', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Center(
            child: SizedBox(
              width: 360,
              child: AgentRequestCard(cardData: _requestCardData()),
            ),
          ),
        ),
      ),
    );

    final surface = find.byKey(const ValueKey('agent-request-card-surface'));
    expect(surface, findsOneWidget);
    expect(tester.getSize(surface).width, closeTo(360, 0.1));
  });
}

Map<String, dynamic> _requestCardData({
  String detail = 'Pick one',
  Map<String, dynamic>? rawParams,
}) {
  return <String, dynamic>{
    'type': 'agent_request',
    'agentId': 'claude-code-acp',
    'agentName': 'Claude Code',
    'sessionId': 'session-1',
    'conversationId': 42,
    'requestId': 'request-1',
    'cardId': 'request-1-card',
    'requestKind': 'user_input',
    'title': 'Choose mode',
    'detail': detail,
    'questionId': 'mode',
    'status': 'pending',
    'startTime': 1000,
    'rawParamsJson': jsonEncode(
      rawParams ??
          {
            'questions': [
              {
                'id': 'mode',
                'question': 'Choose mode',
                'options': [
                  {'label': 'Plan', 'description': 'Plan first'},
                  {'label': 'Chat', 'description': 'Answer directly'},
                ],
              },
            ],
          },
    ),
  };
}
