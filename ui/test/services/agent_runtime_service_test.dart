import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/agent_runtime_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test('accepts the local ACP cancellation acknowledgement', () {
    expect(
      isAgentCancellationSuccessful(<String, dynamic>{'ok': true}),
      isTrue,
    );
    expect(
      isAgentCancellationSuccessful(<String, dynamic>{'cancelled': true}),
      isTrue,
    );
    expect(
      isAgentCancellationSuccessful(<String, dynamic>{'status': 'cancelled'}),
      isTrue,
    );
    expect(
      isAgentCancellationSuccessful(<String, dynamic>{'ok': false}),
      isFalse,
    );
  });

  test('initialize stays on the shared ACP boundary', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'protocolVersion': 1};
    });

    await AgentRuntimeService.initialize(agentId: 'xiaowan-acp');

    expect(capturedCall?.method, 'initialize');
    expect(capturedCall?.arguments, {'agentId': 'xiaowan-acp'});
  });

  test(
    'listSessions leaves page size to the active ACP harness by default',
    () async {
      MethodCall? capturedCall;
      messenger.setMockMethodCallHandler(channel, (call) async {
        capturedCall = call;
        return <String, dynamic>{'sessions': <Map<String, dynamic>>[]};
      });

      await AgentRuntimeService.listSessions();

      expect(capturedCall?.method, 'session/list');
      expect(capturedCall?.arguments, isEmpty);
    },
  );

  test('ensureSession reserves a session before a new prompt', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return <String, dynamic>{'sessionId': 'session-created'};
    });

    final sessionId = await AgentRuntimeService.ensureSession(
      conversationId: 42,
      model: 'model-1',
      conversationMode: 'agent',
    );

    expect(sessionId, 'session-created');
    expect(calls.map((call) => call.method), ['session/new']);
    expect((calls.single.arguments as Map)['conversationId'], 42);
    expect((calls.single.arguments as Map)['model'], 'model-1');
  });

  test(
    'ensureSession reuses an existing official session without a call',
    () async {
      var callCount = 0;
      messenger.setMockMethodCallHandler(channel, (call) async {
        callCount += 1;
        return <String, dynamic>{'sessionId': 'unexpected'};
      });

      final sessionId = await AgentRuntimeService.ensureSession(
        sessionId: '  session-existing  ',
        conversationId: 42,
      );

      expect(sessionId, 'session-existing');
      expect(callCount, 0);
    },
  );

  test('request cancellation is not encoded as session cancellation', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'ok': true, 'cancelled': true};
    });

    await AgentRuntimeService.cancelRequest(
      requestId: 'request-1',
      sessionId: 'session-1',
      agentId: 'xiaowan-acp',
    );

    expect(capturedCall?.method, '\$/cancel_request');
    expect(capturedCall?.arguments, {
      'requestId': 'request-1',
      'sessionId': 'session-1',
      'agentId': 'xiaowan-acp',
    });
  });

  test('parses the live status bundled with an agent switch response', () {
    final catalog = AcpAgentCatalog.fromMap(<String, dynamic>{
      'selectedAgentId': 'claude-code',
      'connected': true,
      'ready': true,
      'runtime': 'local',
      'activeAgentId': 'claude-code',
      'activeAgentName': 'Claude Code',
      'agents': <Map<String, dynamic>>[
        <String, dynamic>{
          'id': 'claude-code',
          'name': 'Claude Code',
          'command': 'claude-code-acp',
          'enabled': true,
        },
      ],
    });

    expect(catalog.runtimeStatus?.connected, isTrue);
    expect(catalog.runtimeStatus?.ready, isTrue);
    expect(catalog.runtimeStatus?.activeAgentId, 'claude-code');
  });

  test(
    'cancelPrompt forwards the OmniFlow run id when stopping a GUI task',
    () async {
      MethodCall? capturedCall;
      messenger.setMockMethodCallHandler(channel, (call) async {
        capturedCall = call;
        return <String, dynamic>{'ok': true, 'cancelled': true};
      });

      await AgentRuntimeService.cancelPrompt(
        sessionId: 'session-1',
        promptId: 'turn-1',
        runId: 'gui-run-1',
      );

      expect(capturedCall?.method, 'session/cancel');
      expect(capturedCall?.arguments, <String, dynamic>{
        'sessionId': 'session-1',
        'promptId': 'turn-1',
        'runId': 'gui-run-1',
      });
    },
  );

  test('promptSession forwards ACP permission payload', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await AgentRuntimeService.promptSession(
      conversationId: 42,
      sessionId: 'thread-1',
      text: 'hello',
      attachments: const <Map<String, dynamic>>[
        <String, dynamic>{
          'id': 'image-1',
          'name': 'screen.png',
          'path': '/tmp/screen.png',
          'mimeType': 'image/png',
          'isImage': true,
        },
      ],
      approvalPolicy: 'never',
      approvalsReviewer: 'user',
      sandboxPolicy: const <String, dynamic>{'type': 'dangerFullAccess'},
      model: 'gpt-5-codex',
      effort: 'high',
      collaborationMode: 'plan',
      terminalEnvironment: const <String, String>{
        'API_ENDPOINT': 'https://example.test',
      },
    );

    expect(capturedCall?.method, 'session/prompt');
    final args = Map<String, dynamic>.from(
      (capturedCall?.arguments as Map).cast<String, dynamic>(),
    );
    expect(args['conversationId'], 42);
    expect(args['sessionId'], 'thread-1');
    expect(args['text'], 'hello');
    expect(args['attachments'], const <Map<String, dynamic>>[
      <String, dynamic>{
        'id': 'image-1',
        'name': 'screen.png',
        'path': '/tmp/screen.png',
        'mimeType': 'image/png',
        'isImage': true,
      },
    ]);
    expect(args['approvalPolicy'], 'never');
    expect(args['approvalsReviewer'], 'user');
    expect(args['sandboxPolicy'], const <String, dynamic>{
      'type': 'dangerFullAccess',
    });
    expect(args['model'], 'gpt-5-codex');
    expect(args['effort'], 'high');
    expect(args['collaborationMode'], 'plan');
    expect(args['terminalEnvironment'], const <String, String>{
      'API_ENDPOINT': 'https://example.test',
    });
  });

  test('startReview forwards codex review payload', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await AgentRuntimeService.startReview(
      conversationId: 42,
      threadId: 'thread-1',
      approvalPolicy: 'on-request',
      approvalsReviewer: 'auto_review',
      model: 'gpt-5-codex',
      effort: 'xhigh',
      collaborationMode: 'plan',
    );

    expect(capturedCall?.method, 'review/start');
    final args = Map<String, dynamic>.from(
      (capturedCall?.arguments as Map).cast<String, dynamic>(),
    );
    expect(args['conversationId'], 42);
    expect(args['threadId'], 'thread-1');
    expect(args['approvalPolicy'], 'on-request');
    expect(args['approvalsReviewer'], 'auto_review');
    expect(args['target'], const <String, dynamic>{
      'type': 'uncommittedChanges',
    });
    expect(args['model'], 'gpt-5-codex');
    expect(args['effort'], 'xhigh');
    expect(args['collaborationMode'], 'plan');
  });

  test('session prompt forwards the turn idempotency key', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'sessionId': 'session-1', 'promptId': 'turn-1'};
    });

    await AgentRuntimeService.promptSession(
      conversationId: 42,
      requestId: 'prompt-1',
      text: 'hello',
    );

    expect(capturedCall?.method, 'session/prompt');
    expect((capturedCall?.arguments as Map)['requestId'], 'prompt-1');
  });

  test(
    'pure chat remains canonical ACP without selecting a Harness agent',
    () async {
      MethodCall? capturedCall;
      messenger.setMockMethodCallHandler(channel, (call) async {
        capturedCall = call;
        return <String, dynamic>{
          'sessionId': 'xiaowan-chat-session',
          'promptId': 'turn-1',
        };
      });

      await AgentRuntimeService.promptSession(
        conversationId: 42,
        text: 'hello',
        conversationMode: 'chat_only',
        model: 'selected-provider-model',
      );

      expect(capturedCall?.method, 'session/prompt');
      final args = Map<String, dynamic>.from(
        (capturedCall?.arguments as Map).cast<String, dynamic>(),
      );
      expect(args['conversationMode'], 'chat_only');
      expect(args['model'], 'selected-provider-model');
      expect(args.containsKey('agentId'), isFalse);
    },
  );

  test(
    'lists the complete harness model catalog, collaboration modes, and config',
    () async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return <String, dynamic>{'ok': true};
      });

      await AgentRuntimeService.listModels();
      await AgentRuntimeService.listCollaborationModes();
      await AgentRuntimeService.readConfig();
      await AgentRuntimeService.listLoadedSessions();

      expect(calls.map((call) => call.method), [
        'model/list',
        'collaborationMode/list',
        'config/read',
        'session/list',
      ]);
      expect(calls.first.arguments, isEmpty);
    },
  );

  test('sets a Harness-owned ACP config option', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await AgentRuntimeService.setConfigOption(
      threadId: 'thread-1',
      configId: 'mode',
      value: 'agent-full-access',
    );

    expect(capturedCall?.method, 'session/set_config_option');
    expect(capturedCall?.arguments, {
      'threadId': 'thread-1',
      'configId': 'mode',
      'value': 'agent-full-access',
    });
  });

  test(
    'sets an ACP config option for the active Agent without conversation binding',
    () async {
      MethodCall? capturedCall;
      messenger.setMockMethodCallHandler(channel, (call) async {
        capturedCall = call;
        return <String, dynamic>{'ok': true};
      });

      await AgentRuntimeService.setSessionConfigOption(
        agentId: 'custom-agent',
        configId: 'model',
        value: 'provider-model',
      );

      expect(capturedCall?.method, 'session/set_config_option');
      expect(capturedCall?.arguments, {
        'agentId': 'custom-agent',
        'configId': 'model',
        'value': 'provider-model',
      });
    },
  );

  test('ACP model extraction keeps config categories separate', () {
    final response = <String, dynamic>{
      'models': <Map<String, dynamic>>[
        <String, dynamic>{'id': 'gpt-5.2-codex'},
        <String, dynamic>{'id': 'claude-sonnet-4-5'},
      ],
      'configOptions': <Map<String, dynamic>>[
        <String, dynamic>{
          'id': 'model',
          'category': 'model',
          'type': 'select',
          'options': <Map<String, dynamic>>[
            <String, dynamic>{'value': 'gpt-5.2-codex'},
            <String, dynamic>{'value': 'claude-sonnet-4-5'},
          ],
        },
        <String, dynamic>{
          'id': 'mode',
          'category': 'mode',
          'type': 'select',
          'options': <Map<String, dynamic>>[
            <String, dynamic>{'value': 'read-only'},
            <String, dynamic>{'value': 'agent'},
            <String, dynamic>{'value': 'agent-full-access'},
            <String, dynamic>{'value': 'default'},
            <String, dynamic>{'value': 'plan'},
            <String, dynamic>{'value': 'acceptEdits'},
            <String, dynamic>{'value': 'dontAsk'},
          ],
        },
        <String, dynamic>{
          'id': 'reasoning_effort',
          'category': 'thought_level',
          'type': 'select',
          'options': <Map<String, dynamic>>[
            <String, dynamic>{'value': 'low'},
            <String, dynamic>{'value': 'medium'},
            <String, dynamic>{'value': 'high'},
            <String, dynamic>{'value': 'xhigh'},
            <String, dynamic>{'value': 'max'},
          ],
        },
        <String, dynamic>{
          'id': 'interactive',
          'type': 'select',
          'options': <Map<String, dynamic>>[
            <String, dynamic>{'value': 'off'},
            <String, dynamic>{'value': 'on'},
          ],
        },
      ],
    };

    expect(extractAcpModelIds(response), <String>[
      'gpt-5.2-codex',
      'claude-sonnet-4-5',
    ]);
    expect(extractAcpReasoningEffortIds(response), <String>[
      'low',
      'medium',
      'high',
      'xhigh',
      'max',
    ]);
    expect(extractAcpReasoningEffortConfigId(response), 'reasoning_effort');
  });

  test('ACP reasoning config id follows the Agent declaration', () {
    expect(
      extractAcpReasoningEffortConfigId(<String, dynamic>{
        'result': <String, dynamic>{
          'configOptions': <Map<String, dynamic>>[
            <String, dynamic>{
              'id': 'effort',
              'category': 'thought_level',
              'type': 'select',
              'options': <Map<String, dynamic>>[
                <String, dynamic>{'value': 'default'},
                <String, dynamic>{'value': 'high'},
              ],
            },
          ],
        },
      }),
      'effort',
    );
  });

  test('ACP model extraction supports category-only config responses', () {
    final response = <String, dynamic>{
      'result': <String, dynamic>{
        'config_options': <Map<String, dynamic>>[
          <String, dynamic>{
            'id': 'model',
            'category': 'model',
            'option_type': 'select',
            'options': <Map<String, dynamic>>[
              <String, dynamic>{
                'value': 'claude-opus-4-1',
                'name': 'Claude Opus 4.1',
              },
            ],
          },
          <String, dynamic>{
            'id': 'mode',
            'category': 'mode',
            'option_type': 'select',
            'options': <Map<String, dynamic>>[
              <String, dynamic>{'value': 'plan'},
            ],
          },
        ],
      },
    };

    expect(extractAcpModelIds(response), <String>['claude-opus-4-1']);
  });

  test('ACP model extraction rejects generic config option lists', () {
    final response = <String, dynamic>{
      'data': <Map<String, dynamic>>[
        <String, dynamic>{
          'id': 'mode',
          'category': 'mode',
          'type': 'select',
          'options': <Map<String, dynamic>>[
            <String, dynamic>{'value': 'read-only'},
            <String, dynamic>{'value': 'plan'},
          ],
        },
        <String, dynamic>{
          'id': 'interactive',
          'type': 'select',
          'options': <Map<String, dynamic>>[
            <String, dynamic>{'value': 'off'},
            <String, dynamic>{'value': 'on'},
          ],
        },
      ],
    };

    expect(extractAcpModelIds(response), isEmpty);
  });

  test(
    'reads and writes Agent-owned configuration without trimming content',
    () async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return <String, dynamic>{'ok': true};
      });

      await AgentRuntimeService.readAgentConfig('claude-code-acp');
      await AgentRuntimeService.writeAgentConfig(
        'claude-code-acp',
        content: ' {\n  "env": {}\n}\n ',
        reasoningEffort: 'high',
        permissionMode: 'workspace-write',
      );

      expect(calls.map((call) => call.method), [
        'agent/config/read',
        'agent/config/write',
      ]);
      expect(calls.first.arguments, {'agentId': 'claude-code-acp'});
      expect(calls.last.arguments, {
        'agentId': 'claude-code-acp',
        'content': ' {\n  "env": {}\n}\n ',
        'reasoningEffort': 'high',
        'permissionMode': 'workspace-write',
      });
    },
  );

  test('ignoreUserInput responds with empty answers payload', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await AgentRuntimeService.ignoreUserInput(requestId: 'request-1');

    expect(capturedCall?.method, 'respondToServerRequest');
    expect(capturedCall?.arguments, {
      'requestId': 'request-1',
      'response': {'answers': <String, dynamic>{}},
    });
  });

  test('elicitation responses preserve ACP content types', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await AgentRuntimeService.respondToElicitation(
      requestId: 'elicitation-1',
      content: <String, dynamic>{'name': 'demo', 'count': 3, 'enabled': false},
    );

    expect(capturedCall?.method, 'respondToServerRequest');
    expect(capturedCall?.arguments, {
      'requestId': 'elicitation-1',
      'response': {
        'action': 'accept',
        'content': {'name': 'demo', 'count': 3, 'enabled': false},
      },
    });
  });

  test('elicitation cancellation uses the ACP cancel action', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await AgentRuntimeService.cancelElicitation(requestId: 'elicitation-1');

    expect(capturedCall?.arguments, {
      'requestId': 'elicitation-1',
      'response': {'action': 'cancel'},
    });
  });

  test('user input response carries its host routing identity', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await AgentRuntimeService.respondToUserInput(
      requestId: 'request-1',
      questionId: 'answer',
      answers: <String>['继续'],
      sessionId: 'dsh-session-1',
      agentId: 'deepseek-harness-acp',
      conversationId: 42,
    );

    expect(capturedCall?.arguments, {
      'requestId': 'request-1',
      'agentId': 'deepseek-harness-acp',
      'conversationId': 42,
      'sessionId': 'dsh-session-1',
      'response': {
        'answers': {
          'answer': {
            'answers': <String>['继续'],
          },
        },
      },
    });
  });

  test('server response is not considered submitted without an ACP ACK', () {
    messenger.setMockMethodCallHandler(channel, (call) async {
      return <String, dynamic>{};
    });

    expect(
      AgentRuntimeService.respondToUserInput(
        requestId: 'request-1',
        questionId: 'answer',
        answers: <String>['继续'],
      ),
      throwsStateError,
    );
  });

  test('readSession requests history by default', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await AgentRuntimeService.readSession(sessionId: 'thread-1');

    expect(capturedCall?.method, 'session/load');
    expect(capturedCall?.arguments, {
      'sessionId': 'thread-1',
      'includeHistory': true,
    });
  });

  test('reads and writes only remote bridge config', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return <String, dynamic>{
        'remoteEnabled': true,
        'remoteBridgeUrl': 'ws://192.168.1.2:17321/codex',
        'remoteBridgeToken': 'token',
        'remoteCwd': '/Users/name/code/project',
      };
    });

    final read = await AgentRuntimeService.readRemoteBridgeConfig();
    final written = await AgentRuntimeService.writeRemoteBridgeConfig(
      remoteEnabled: true,
      remoteBridgeUrl: ' ws://192.168.1.2:17321/codex ',
      remoteBridgeToken: ' token ',
      remoteCwd: ' /Users/name/code/project ',
    );

    expect(read.remoteEnabled, isTrue);
    expect(read.remoteBridgeUrl, 'ws://192.168.1.2:17321/codex');
    expect(written.remoteCwd, '/Users/name/code/project');
    expect(calls.map((call) => call.method), [
      'config/remote/read',
      'config/remote/write',
    ]);
    expect(calls.last.arguments, <String, dynamic>{
      'remoteEnabled': true,
      'remoteBridgeUrl': 'ws://192.168.1.2:17321/codex',
      'remoteBridgeToken': 'token',
      'remoteCwd': '/Users/name/code/project',
    });
  });

  test('forwards ChatGPT device-code login lifecycle', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return <String, dynamic>{'ok': true};
    });

    await AgentRuntimeService.startLogin(
      type: CodexLoginType.chatgptDeviceCode,
    );
    await AgentRuntimeService.cancelLogin(loginId: 'login-1');

    expect(calls.map((call) => call.method), [
      'account/login/start',
      'account/login/cancel',
    ]);
    expect(calls[0].arguments, {'type': 'chatgptDeviceCode'});
    expect(calls[1].arguments, {'loginId': 'login-1'});
  });

  test('ACP agent model picker uses initialize config options', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return <String, dynamic>{'models': <dynamic>[]};
    });

    await AgentRuntimeService.listModelsForStatus(
      const AgentRuntimeStatus(
        connected: true,
        ready: true,
        runtime: 'local',
        activeAgentId: 'custom-agent',
      ),
    );

    expect(calls.map((call) => call.method), ['model/list']);
  });

  test('keeps Codex model sources separate', () {
    expect(
      agentModelSourceKey(
        const AgentRuntimeStatus(
          connected: true,
          ready: true,
          runtime: 'remote',
        ),
      ),
      'remote',
    );
    expect(
      agentModelSourceKey(
        const AgentRuntimeStatus(
          connected: true,
          ready: true,
          runtime: 'local',
          activeAgentId: 'codex-acp',
        ),
      ),
      'local-codex-acp',
    );
  });

  test('parses ACP identity and capability payload from status', () {
    final status = AgentRuntimeStatus.fromMap(<String, dynamic>{
      'connected': true,
      'ready': true,
      'runtime': 'local',
      'protocol': 'acp',
      'protocolVersion': 1,
      'activeAgentId': 'custom-agent',
      'activeAgentName': 'Custom Agent',
      'capabilities': <String, dynamic>{
        'loadSession': true,
        'prompt': <String, dynamic>{'image': true},
      },
    });

    expect(status.protocol, 'acp');
    expect(status.protocolVersion, 1);
    expect(status.activeAgentId, 'custom-agent');
    expect(status.activeAgentName, 'Custom Agent');
    expect(status.capabilities['loadSession'], true);
    expect(agentModelSourceKey(status), 'local-custom-agent');
  });

  test('parses managed ACP adapter discovery metadata', () {
    final agent = AcpAgentProfile.fromMap(<String, dynamic>{
      'id': 'codex-acp',
      'name': 'Codex',
      'command': 'codex-acp',
      'discoveryCommand': 'codex',
      'managedAdapter': true,
      'status': 'unchecked',
    });

    expect(agent.discoveryCommand, 'codex');
    expect(agent.managedAdapter, isTrue);
  });

  test('deduplicates legacy Xiaowan Bot entries in the ACP catalog', () {
    final catalog = AcpAgentCatalog.fromMap(<String, dynamic>{
      'selectedAgentId': 'xiaowan-acp',
      'agents': <Map<String, dynamic>>[
        <String, dynamic>{
          'id': 'xiaowan-acp',
          'name': '小万',
          'command': 'omnibot-xiaowan-acp',
          'builtIn': true,
        },
        <String, dynamic>{
          'id': 'legacy-xiaowan-bot',
          'name': '小万 Bot',
          'command': 'legacy-xiaowan',
        },
        <String, dynamic>{
          'id': 'codex-acp',
          'name': 'Codex',
          'command': 'codex-acp',
        },
      ],
    });

    expect(catalog.agents.map((agent) => agent.id), [
      'xiaowan-acp',
      'codex-acp',
    ]);
    expect(catalog.selectedAgent?.name, '小万');
  });

  for (final custom in <Map<String, dynamic>>[
    {'id': 'user-adapter', 'name': '小万', 'command': 'my-acp'},
    {'id': 'user-adapter', 'name': '小万 Bot', 'command': 'my-bot-acp'},
    {'id': 'user-adapter', 'name': 'Xiaowan_Bot', 'command': 'my-other-acp'},
    {
      'id': 'user-adapter',
      'name': 'My Agent',
      'command': '/workspace/xiaowan-next/acp',
    },
    {
      'id': 'user-adapter',
      'name': 'My configuration',
      'command': 'omnibot-xiaowan-acp',
    },
  ]) {
    test('custom ACP profile remains selectable: ${custom['command']}', () {
      final catalog = AcpAgentCatalog.fromMap(<String, dynamic>{
        'selectedAgentId': custom['id'],
        'agents': <Map<String, dynamic>>[
          {
            'id': 'xiaowan-acp',
            'name': '小万',
            'command': 'omnibot-xiaowan-acp',
            'builtIn': true,
          },
          custom,
        ],
      });
      expect(catalog.agents.map((agent) => agent.id), [
        'xiaowan-acp',
        'user-adapter',
      ]);
      expect(catalog.selectedAgent?.id, 'user-adapter');
    });
  }

  test('local Agent requests use the selected ACP model', () {
    final model = selectAgentRequestModel(
      status: const AgentRuntimeStatus(
        connected: true,
        ready: true,
        runtime: 'local',
      ),
      overrideModel: null,
      activeModel: 'input-selected',
      activeModelSourceMatches: true,
    );

    expect(model, 'input-selected');
  });

  test('shared Agent requests use the verified active Provider model', () {
    final model = selectAgentRequestModel(
      status: const AgentRuntimeStatus(
        connected: true,
        ready: true,
        runtime: 'local',
      ),
      overrideModel: null,
      activeModel: 'DeepSeek-V4-Pro',
      activeModelSourceMatches: true,
    );

    expect(model, 'DeepSeek-V4-Pro');
  });

  test('Agent switch falls back to an existing persisted Provider binding', () {
    final selection = resolveSharedAgentProviderSelection(
      effectiveProviderProfileId: null,
      effectiveModel: null,
      boundProviderProfileId: 'debug-provider',
      boundModel: 'GLM-5.1',
    );

    expect(selection, const <String, String>{
      'providerProfileId': 'debug-provider',
      'modelId': 'GLM-5.1',
    });
  });

  test('Agent switch rejects a stale or unconfigured Provider binding', () {
    const selection = <String, String>{
      'providerProfileId': 'deleted-provider',
      'modelId': 'GLM-5.1',
    };

    expect(
      isSharedAgentProviderSelectionReady(
        selection: selection,
        configuredProviderIds: const <String>{'active-provider'},
      ),
      isFalse,
    );
    expect(
      isSharedAgentProviderSelectionReady(
        selection: selection,
        configuredProviderIds: const <String>{'deleted-provider'},
      ),
      isTrue,
    );
  });

  test('namespace tool incompatibility has an actionable error', () {
    final message = formatAgentRuntimeErrorForUser(
      '{"error":{"message":"tools[8].type: unknown variant namespace, '
      'expected one of function, web_search_preview, code_interpreter, mcp"}}',
    );

    expect(message, contains('Responses Provider'));
    expect(message, contains('MCP'));
    expect(message, isNot(contains('{"error"')));
  });

  test(
    'provider abort reports an interrupted response without claiming a retry',
    () {
      final message = formatAgentRuntimeErrorForUser(
        PlatformException(
          code: 'AGENT_RUNTIME_CALL_FAILED',
          message: 'Software caused connection abort',
          details: {'failureKind': 'provider_stream_interrupted'},
        ),
      );
      expect(message, contains('连接中断'));
      expect(message, contains('未完成的工具调用不会执行'));
      expect(message, isNot(contains('已自动重试')));
    },
  );

  test('incomplete tool calls are mapped to an actionable user error', () {
    final message = formatAgentRuntimeErrorForUser(
      PlatformException(
        code: 'AGENT_RUNTIME_CALL_FAILED',
        message: 'tool_call[1] missing function.name',
        details: <String, dynamic>{
          'failureKind': 'provider_tool_call_incomplete',
        },
      ),
    );

    expect(message, contains('不完整的工具调用'));
    expect(message, isNot(contains('missing function.name')));
  });

  test(
    'provider stream idle timeout is mapped to an actionable user error',
    () {
      final message = formatAgentRuntimeErrorForUser(
        PlatformException(
          code: 'AGENT_RUNTIME_CALL_FAILED',
          message: 'chat completion stream idle timeout after 90000ms',
          details: <String, dynamic>{
            'failureKind': 'provider_stream_idle_timeout',
          },
        ),
      );

      expect(message, contains('长时间没有返回新的流式更新'));
      expect(message, isNot(contains('90000ms')));
    },
  );

  test('Harness preparation contention is actionable and non-blocking', () {
    final message = formatAgentRuntimeErrorForUser(
      PlatformException(
        code: 'AGENT_RUNTIME_CALL_FAILED',
        message: 'Harness preparation is already running',
        details: <String, dynamic>{
          'failureKind': 'harness_preparation_in_progress',
        },
      ),
    );

    expect(message, contains('当前切换不会等待'));
    expect(message, isNot(contains('Harness preparation is already running')));
  });

  test('local Agent requests do not read a separate Codex API model', () {
    final model = selectAgentRequestModel(
      status: const AgentRuntimeStatus(
        connected: true,
        ready: true,
        runtime: 'local',
      ),
      overrideModel: null,
      activeModel: null,
      activeModelSourceMatches: true,
    );

    expect(model, isNull);
  });

  test('model load results are rejected after source changes', () {
    expect(
      isCurrentAgentModelLoad(
        requestId: 4,
        activeRequestId: 4,
        requestSource: 'remote',
        currentSource: 'local-api',
      ),
      isFalse,
    );
    expect(
      isCurrentAgentModelLoad(
        requestId: 5,
        activeRequestId: 5,
        requestSource: 'local-api',
        currentSource: 'local-api',
      ),
      isTrue,
    );
  });

  test(
    'forwards remote filesystem operations without trimming content',
    () async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        if (call.method == 'config/remote/fs/read') {
          return <String, dynamic>{
            'ok': true,
            'path': '/repo/lib/main.dart',
            'name': 'main.dart',
            'previewKind': 'code',
            'mimeType': 'text/plain',
            'content': 'void main() {}',
          };
        }
        return <String, dynamic>{'ok': true};
      });

      final read = await AgentRuntimeService.readRemoteFile(
        remoteBridgeUrl: ' ws://pc:17321/codex ',
        remoteBridgeToken: ' token ',
        remoteCwd: ' /repo ',
        path: ' /repo/lib/main.dart ',
      );
      await AgentRuntimeService.writeRemoteFile(
        path: '/repo/lib/main.dart',
        content: '  keep whitespace\n',
      );
      await AgentRuntimeService.deleteRemotePath(
        path: '/repo/tmp',
        recursive: true,
      );
      await AgentRuntimeService.moveRemotePath(
        path: '/repo/a.dart',
        destinationPath: '/repo/b.dart',
      );

      expect(read.content, 'void main() {}');
      expect(calls.map((call) => call.method), [
        'config/remote/fs/read',
        'config/remote/fs/write',
        'config/remote/fs/delete',
        'config/remote/fs/move',
      ]);
      expect(calls[0].arguments, <String, dynamic>{
        'remoteBridgeUrl': 'ws://pc:17321/codex',
        'remoteBridgeToken': 'token',
        'remoteCwd': '/repo',
        'path': '/repo/lib/main.dart',
      });
      expect((calls[1].arguments as Map)['content'], '  keep whitespace\n');
      expect((calls[2].arguments as Map)['recursive'], true);
      expect((calls[3].arguments as Map)['destinationPath'], '/repo/b.dart');
    },
  );
}
