import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/agent_runtime_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  test('failed cancellation reaches caller without retrying the logical turn', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (calls.length == 1) {
        throw PlatformException(code: 'AGENT_RUNTIME_CALL_FAILED', message: 'Cancel transport failed');
      }
      return {'ok': true};
    });
    await expectLater(
      AgentRuntimeService.cancelPrompt(conversationId: 42, sessionId: 'session', promptId: 'turn'),
      throwsA(isA<PlatformException>().having((error) => error.message, 'message', 'Cancel transport failed')),
    );
    expect(calls, hasLength(1));
    await AgentRuntimeService.cancelPrompt(conversationId: 42, sessionId: 'session', promptId: 'turn');
    expect(calls.map((call) => call.method), ['session/cancel', 'session/cancel']);
    expect(calls[0].arguments, calls[1].arguments);
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test('automatic Na health opts out of workspace claims, manual checks retain defaults', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return {'ok': true};
    });
    await AgentRuntimeService.testAgent('na-cloud', readOnly: true);
    await AgentRuntimeService.testAgent('na-cloud');
    expect(calls.map((call) => call.method), ['agent/test', 'agent/test']);
    expect(calls.first.arguments, {'agentId': 'na-cloud', 'readOnly': true});
    expect(calls.last.arguments, {'agentId': 'na-cloud'});
  });

  test(
    'update check is read only and retains native version results',
    () async {
      final calls = <String>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call.method);
        return {
          'agents': [
            {
              'id': 'opencode-acp',
              'name': 'OpenCode',
              'command': 'opencode',
              'packageVersion': {
                'installedVersion': '1.18.29',
                'latestVersion': '2.0.32',
                'targetVersion': '2.0.32',
                'checkedAt': 42,
                'updateAvailable': true,
              },
            },
          ],
        };
      });
      final catalog = await AgentRuntimeService.checkAgentUpdates();
      expect(calls, ['agent/checkUpdates']);
      expect(catalog.agents.single.packageVersion?.installedVersion, '1.18.29');
      expect(catalog.agents.single.packageVersion?.updateAvailable, isTrue);
    },
  );

  test(
    'background upgrades are serialized and a failure does not stop the queue',
    () async {
      final first = Completer<Map<String, dynamic>>();
      final second = Completer<Map<String, dynamic>>();
      final calls = <String>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        expect(call.method, 'agent/prepare');
        final id = (call.arguments as Map)['agentId'] as String;
        calls.add(id);
        return id == 'first' ? first.future : second.future;
      });
      final firstTask = AgentRuntimeService.prepareAgentInBackground(
        'first',
        force: true,
      );
      final failed = expectLater(firstTask, throwsA(isA<PlatformException>()));
      final secondTask = AgentRuntimeService.prepareAgentInBackground(
        'second',
        force: true,
      );
      expect(
        identical(
          secondTask,
          AgentRuntimeService.prepareAgentInBackground('second', force: true),
        ),
        isTrue,
      );
      await Future<void>.delayed(Duration.zero);
      expect(calls, ['first']);
      expect(AgentRuntimeService.queuedAgentIds, {'second'});
      first.completeError(PlatformException(code: 'INSTALL_FAILED'));
      await failed;
      await Future<void>.delayed(Duration.zero);
      expect(calls, ['first', 'second']);
      second.complete({'ok': true});
      await secondTask;
      await Future<void>.delayed(Duration.zero);
      expect(AgentRuntimeService.preparingAgentIds, isEmpty);
      expect(AgentRuntimeService.queuedAgentIds, isEmpty);
    },
  );

  test(
    'explicit reinstall reaches the installer with force, ordinary preparation does not',
    () async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return <String, dynamic>{'ok': true};
      });
      await AgentRuntimeService.prepareAgent('codex-acp');
      await AgentRuntimeService.prepareAgentInBackground(
        'codex-acp',
        force: true,
      );
      expect(calls.map((call) => call.method), [
        'agent/prepare',
        'agent/prepare',
      ]);
      expect(calls.first.arguments, {'agentId': 'codex-acp'});
      expect(calls.last.arguments, {'agentId': 'codex-acp', 'force': true});
    },
  );

  test('uninstall sends only the agent and captured distribution', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return {
        'ok': true,
        'agent': {'id': 'pi-acp', 'installed': false},
      };
    });
    final result = await AgentRuntimeService.uninstallAgent(
      ' pi-acp ',
      distributionId: ' alpine ',
    );
    expect(result['ok'], isTrue);
    expect(calls.single.method, 'agent/uninstall');
    expect(calls.single.arguments, {
      'agentId': 'pi-acp',
      'distributionId': 'alpine',
    });
    await expectLater(
      AgentRuntimeService.uninstallAgent('pi-acp', distributionId: ' '),
      throwsArgumentError,
    );
    await expectLater(
      AgentRuntimeService.uninstallAgentInBackground(
        '',
        distributionId: 'alpine',
      ),
      throwsArgumentError,
    );
    expect(calls, hasLength(1));
    expect(AgentRuntimeService.agentPackageOperations, isEmpty);
  });

  test(
    'install and uninstall share a queue and publish their operation types',
    () async {
      final install = Completer<Map<String, dynamic>>();
      final uninstall = Completer<Map<String, dynamic>>();
      addTearDown(() {
        if (!install.isCompleted) install.complete({'ok': false});
        if (!uninstall.isCompleted) uninstall.complete({'ok': false});
      });
      final calls = <MethodCall>[];
      final states = <Map<String, AgentPackageOperation>>[];
      final installStates = <Set<String>>[];
      final subscription = AgentRuntimeService.agentPackageOperationChanges
          .listen(states.add);
      final installationSubscription = AgentRuntimeService
          .agentPreparationChanges
          .listen(installStates.add);
      addTearDown(subscription.cancel);
      addTearDown(installationSubscription.cancel);
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return call.method == 'agent/prepare'
            ? install.future
            : uninstall.future;
      });

      final installTask = AgentRuntimeService.prepareAgentInBackground(
        'codex-acp',
        force: true,
        distributionId: 'ubuntu',
      );
      final uninstallTask = AgentRuntimeService.uninstallAgentInBackground(
        'pi-acp',
        distributionId: 'ubuntu',
      );
      expect(AgentRuntimeService.agentPackageOperations, {
        'codex-acp': AgentPackageOperation.install,
        'pi-acp': AgentPackageOperation.uninstall,
      });
      expect(AgentRuntimeService.preparingAgentIds, {'codex-acp'});
      expect(
        identical(
          uninstallTask,
          AgentRuntimeService.uninstallAgentInBackground(
            ' pi-acp ',
            distributionId: 'ubuntu',
          ),
        ),
        isTrue,
      );
      final snapshot = AgentRuntimeService.agentPackageOperations;
      expect(() => snapshot.clear(), throwsUnsupportedError);

      await Future<void>.delayed(Duration.zero);
      expect(calls.map((call) => call.method), ['agent/prepare']);
      expect(AgentRuntimeService.queuedAgentIds, {'pi-acp'});
      install.complete({'ok': true});
      await installTask;
      await Future<void>.delayed(Duration.zero);
      expect(calls.map((call) => call.method), [
        'agent/prepare',
        'agent/uninstall',
      ]);
      expect(calls.last.arguments, {
        'agentId': 'pi-acp',
        'distributionId': 'ubuntu',
      });
      expect(AgentRuntimeService.preparingAgentIds, isEmpty);
      expect(AgentRuntimeService.queuedAgentIds, isEmpty);
      expect(AgentRuntimeService.agentPackageOperations, {
        'pi-acp': AgentPackageOperation.uninstall,
      });
      expect(snapshot, hasLength(2));
      uninstall.complete({'ok': true});
      await uninstallTask;
      await Future<void>.delayed(Duration.zero);
      expect(AgentRuntimeService.agentPackageOperations, isEmpty);
      expect(
        states.any(
          (state) => state['pi-acp'] == AgentPackageOperation.uninstall,
        ),
        isTrue,
      );
      expect(states.last, isEmpty);
      expect(installStates.expand((state) => state), isNot(contains('pi-acp')));
    },
  );

  test(
    'a conflicting operation or distribution never reuses the active task',
    () async {
      final gate = Completer<Map<String, dynamic>>();
      addTearDown(() {
        if (!gate.isCompleted) gate.complete({'ok': false});
      });
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return gate.future;
      });
      final task = AgentRuntimeService.prepareAgentInBackground(
        'pi-acp',
        force: true,
        distributionId: 'alpine',
      );
      for (final conflict in [
        () => AgentRuntimeService.uninstallAgentInBackground(
          'pi-acp',
          distributionId: 'alpine',
        ),
        () => AgentRuntimeService.prepareAgentInBackground(
          'pi-acp',
          force: true,
          distributionId: 'ubuntu',
        ),
        () => AgentRuntimeService.prepareAgentInBackground(
          'pi-acp',
          distributionId: 'alpine',
        ),
      ]) {
        await expectLater(conflict(), throwsStateError);
      }
      await Future<void>.delayed(Duration.zero);
      expect(calls, hasLength(1));
      expect(AgentRuntimeService.agentPackageOperations, {
        'pi-acp': AgentPackageOperation.install,
      });
      gate.complete({'ok': true});
      await task;
      await Future<void>.delayed(Duration.zero);
      expect(AgentRuntimeService.agentPackageOperations, isEmpty);
    },
  );

  test(
    'failed uninstall releases its task and allows queued work and retry',
    () async {
      final gate = Completer<Map<String, dynamic>>();
      addTearDown(() {
        if (!gate.isCompleted) gate.complete({'ok': false});
      });
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return calls.length == 1 ? gate.future : {'ok': true};
      });
      final uninstallTask = AgentRuntimeService.uninstallAgentInBackground(
        'pi-acp',
        distributionId: 'alpine',
      );
      final failed = expectLater(
        uninstallTask,
        throwsA(isA<PlatformException>()),
      );
      final queuedInstall = AgentRuntimeService.prepareAgentInBackground(
        'kimi-code-acp',
        distributionId: 'alpine',
      );
      await expectLater(
        AgentRuntimeService.uninstallAgentInBackground(
          'pi-acp',
          distributionId: 'ubuntu',
        ),
        throwsStateError,
      );
      await Future<void>.delayed(Duration.zero);
      gate.completeError(PlatformException(code: 'UNINSTALL_FAILED'));
      await failed;
      await queuedInstall;
      await Future<void>.delayed(Duration.zero);
      final retried = await AgentRuntimeService.uninstallAgentInBackground(
        'pi-acp',
        distributionId: 'alpine',
      );
      await Future<void>.delayed(Duration.zero);
      expect(retried['ok'], isTrue);
      expect(calls.map((call) => call.method), [
        'agent/uninstall',
        'agent/prepare',
        'agent/uninstall',
      ]);
      expect(AgentRuntimeService.agentPackageOperations, isEmpty);
      expect(AgentRuntimeService.queuedAgentIds, isEmpty);
    },
  );

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

      await AgentRuntimeService.listSessions(agentId: 'codex-remote');

      expect(capturedCall?.method, 'session/list');
      expect(capturedCall?.arguments, {'agentId': 'codex-remote'});
    },
  );

  test('runtime control requests always carry their explicit owner', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return {'ready': true, 'connected': false};
    });
    await AgentRuntimeService.status(agentId: 'xiaowan-acp');
    await AgentRuntimeService.status(agentId: 'codex-remote');
    await AgentRuntimeService.initialize(agentId: 'na-cloud');
    await AgentRuntimeService.disconnect(agentId: 'custom-agent');
    expect(calls.map((call) => (call.arguments as Map)['agentId']), [
      'xiaowan-acp', 'codex-remote', 'na-cloud', 'custom-agent',
    ]);
    expect(calls.map((call) => call.method), ['status', 'status', 'initialize', 'disconnect']);
    await expectLater(AgentRuntimeService.status(agentId: '  '), throwsArgumentError);
    expect(() => AgentRuntimeService.initialize(agentId: ''), throwsArgumentError);
    expect(calls, hasLength(4));
  });

  test('extensions preserve vendor params inside one explicit owner envelope', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return <String, dynamic>{};
    });
    const vendorMap = <String, dynamic>{
      'agentId': 'vendor-agent',
      'nested': {'agentId': 'nested-vendor-agent', 'value': 1},
    };
    await AgentRuntimeService.callAcpExtension(
      agentId: 'custom-agent',
      method: '_custom/read',
      params: vendorMap,
    );
    expect(calls.single.arguments, {
      'agentId': 'custom-agent',
      'params': vendorMap,
    });
    expect(calls.single.method, '_custom/read');

    for (final params in <Object?>[
      vendorMap,
      [vendorMap, 7, null],
      'vendor text',
      42,
      true,
      null,
    ]) {
      await AgentRuntimeService.callAcpExtensionValue(
        agentId: 'custom-agent',
        method: '_custom/value',
        params: params,
      );
      expect(calls.last.method, '_custom/value');
      expect(calls.last.arguments, {'agentId': 'custom-agent', 'params': params});
      expect((calls.last.arguments as Map).containsKey('params'), isTrue);

      await AgentRuntimeService.notifyAcpExtension(
        agentId: 'custom-agent',
        method: '_custom/update',
        params: params,
      );
      expect(calls.last.method, 'notifyAcpExtension');
      expect(calls.last.arguments, {
        'agentId': 'custom-agent',
        'method': '_custom/update',
        'params': params,
      });
      expect((calls.last.arguments as Map).containsKey('params'), isTrue);
    }
  });

  test('ensureSession reserves a session before a new prompt', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return <String, dynamic>{'sessionId': 'session-created'};
    });

    final sessionId = await AgentRuntimeService.ensureSession(
      conversationId: 42,
      agentId: 'xiaowan-acp',
      model: 'model-1',
      conversationMode: 'agent',
    );

    expect(sessionId, 'session-created');
    expect(calls.map((call) => call.method), ['session/new']);
    expect((calls.single.arguments as Map)['conversationId'], 42);
    expect((calls.single.arguments as Map)['model'], 'model-1');
    expect((calls.single.arguments as Map)['agentId'], 'xiaowan-acp');
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

  test('Harness compatibility failures point to Agent maintenance', () {
    const expected = '当前助手运行环境需要升级，请在助手设置中升级或重新安装。';
    final errors = [
      PlatformException(
        code: 'runtime_error',
        message: 'Internal compatibility check failed',
        details: {'failureKind': 'harness_upgrade_required'},
      ),
      'OpenCode runtime needs an upgrade before continuing.',
      PlatformException(code: 'runtime_error', message: 'OpenCode runtime needs an upgrade'),
    ];
    for (final error in errors) {
      expect(formatAgentRuntimeErrorForUser(error), expected);
      expect(formatAgentRuntimeErrorForUser(error, english: true), contains('Agent settings'));
    }
    expect(formatAgentRuntimeErrorForUser(expected), expected);
  });

  test('retired catalog identities cannot become selectable custom agents', () {
    final catalog = AcpAgentCatalog.fromMap({
      'retiredAgentIds': ['deepseek-harness-acp'],
      'agents': [
        {'id': 'deepseek-harness-acp', 'name': 'Old Agent'},
        {'id': 'codex-acp', 'name': 'Codex'},
        {'id': 'custom-agent', 'name': 'Custom Agent'},
      ],
    });
    expect(catalog.agents.map((agent) => agent.id), [
      'codex-acp',
      'custom-agent',
    ]);
    expect(catalog.isRetired('deepseek-harness-acp'), isTrue);
    expect(catalog.isRetired('custom-agent'), isFalse);
    expect(catalog.isRetired(null), isFalse);
    expect(catalog.agents.first.id, 'codex-acp');
  });

  test(
    'retired agent errors preserve history guidance without model retries',
    () {
      const message = '此助手已停止支持。历史记录仍可查看，请新建其他助手的对话。';
      for (final error in [
        'agent_retired: deepseek-harness-acp',
        PlatformException(
          code: 'runtime_error',
          message: 'agent_retired: old-agent',
        ),
        PlatformException(
          code: 'runtime_error',
          details: {'failureKind': 'agent_retired'},
        ),
      ]) {
        expect(formatAgentRuntimeErrorForUser(error), message);
      }
      expect(formatAgentRuntimeErrorForUser(message), message);
      expect(
        formatAgentRuntimeErrorForUser(message, english: true),
        contains('view its history'),
      );
    },
  );

  test('catalog ignores obsolete selected runtime fields and preserves directory order', () {
    final catalog = AcpAgentCatalog.fromMap(<String, dynamic>{
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

    expect(catalog.agents.single.id, 'claude-code');
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

  test('prompt failures retain structured classification across the shared service', () async {
    messenger.setMockMethodCallHandler(channel, (call) async => <String, dynamic>{
      'status': 'error', 'stopReason': 'error', 'completed': true,
      'error': '测试失败', 'failureKind': 'provider_authentication_failed',
      'sessionId': 'session-1', 'turnId': 'turn-1',
    });
    final response = await AgentRuntimeService.promptSession(sessionId: 'session-1', text: 'test');
    expect(response['error'], '模型连接验证失败，请在模型设置中检查接口地址和密钥。');
    expect(response['status'], 'error');
    expect(response['turnId'], 'turn-1');
    expect(response['completed'], true);
  });

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

  test('reviewSession forwards the owned review payload', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await AgentRuntimeService.reviewSession(
      conversationId: 42,
      sessionId: 'thread-1',
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
    expect(args['sessionId'], 'thread-1');
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
      userMessageId: 'original-user',
      text: 'hello',
    );

    expect(capturedCall?.method, 'session/prompt');
    expect((capturedCall?.arguments as Map)['requestId'], 'prompt-1');
    expect((capturedCall?.arguments as Map)['userMessageId'], 'original-user');
  });

  test(
    'pure chat declares its in-process owner on canonical ACP',
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
        agentId: 'xiaowan-acp',
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
      expect(args['agentId'], 'xiaowan-acp');
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

      await AgentRuntimeService.listModels(agentId: 'codex-remote');
      await AgentRuntimeService.listCollaborationModes(agentId: 'codex-remote');
      await AgentRuntimeService.readConfig(agentId: 'codex-remote');
      await AgentRuntimeService.listLoadedSessions(agentId: 'codex-remote');

      expect(calls.map((call) => call.method), [
        'model/list',
        'collaborationMode/list',
        'config/read',
        'session/list',
      ]);
      for (final call in calls) {
        expect(call.arguments, {'agentId': 'codex-remote'});
      }
    },
  );

  test('sets a Harness-owned ACP config option', () async {
    MethodCall? capturedCall;
    messenger.setMockMethodCallHandler(channel, (call) async {
      capturedCall = call;
      return <String, dynamic>{'ok': true};
    });

    await AgentRuntimeService.setSessionConfigOption(
      sessionId: 'thread-1',
      configId: 'mode',
      value: 'agent-full-access',
    );

    expect(capturedCall?.method, 'session/set_config_option');
    expect(capturedCall?.arguments, {
      'sessionId': 'thread-1',
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
      );

      expect(calls.map((call) => call.method), [
        'agent/config/read',
        'agent/config/write',
      ]);
      expect(calls.first.arguments, {'agentId': 'claude-code-acp'});
      expect(calls.last.arguments, {
        'agentId': 'claude-code-acp',
        'content': ' {\n  "env": {}\n}\n ',
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
      sessionId: 'kimi-session-1',
      agentId: 'kimi-code-acp',
      conversationId: 42,
    );

    expect(capturedCall?.arguments, {
      'requestId': 'request-1',
      'agentId': 'kimi-code-acp',
      'conversationId': 42,
      'sessionId': 'kimi-session-1',
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
      agentId: 'codex-remote',
      type: CodexLoginType.chatgptDeviceCode,
    );
    await AgentRuntimeService.cancelLogin(agentId: 'codex-remote', loginId: 'login-1');

    expect(calls.map((call) => call.method), [
      'account/login/start',
      'account/login/cancel',
    ]);
    expect(calls[0].arguments, {'agentId': 'codex-remote', 'type': 'chatgptDeviceCode'});
    expect(calls[1].arguments, {'agentId': 'codex-remote', 'loginId': 'login-1'});
  });

  test('ACP agent model picker uses initialize config options', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return <String, dynamic>{'models': <dynamic>[]};
    });

    await AgentRuntimeService.listModels(agentId: 'custom-agent');

    expect(calls.map((call) => call.method), ['model/list']);
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
    expect(status.activeAgentId, 'custom-agent');
  });

  test('parses managed ACP adapter discovery metadata', () {
    final agent = AcpAgentProfile.fromMap(<String, dynamic>{
      'id': 'codex-acp',
      'name': 'Codex',
      'command': 'codex-acp',
      'discoveryCommand': 'codex',
      'managedAdapter': true,
      'uninstallable': true,
      'status': 'unchecked',
    });

    expect(agent.discoveryCommand, 'codex');
    expect(agent.managedAdapter, isTrue);
    expect(agent.uninstallable, isTrue);
    expect(agent.toMap(), isNot(contains('uninstallable')));
  });

  test(
    'uninstall capability defaults to false and requires a native boolean',
    () {
      for (final value in [null, false, 'true', 1]) {
        final agent = AcpAgentProfile.fromMap({
          'id': 'custom-agent',
          'name': 'Custom',
          'command': 'custom',
          'managedAdapter': true,
          'uninstallable': ?value,
        });
        expect(agent.uninstallable, isFalse);
      }
      expect(
        const AcpAgentProfile(
          id: 'pi-acp',
          name: 'Pi',
          command: 'pi',
        ).uninstallable,
        isFalse,
      );
    },
  );

  test('Na configuration requires explicit native development capability', () {
    final managed = AcpAgentProfile.fromMap({
      'id': 'na-cloud',
      'name': 'Na',
      'command': 'na-cloud',
    });
    final development = AcpAgentProfile.fromMap({
      'id': 'na-cloud',
      'name': 'Na',
      'command': 'na-cloud',
      'configurationAvailable': true,
    });
    final otherAgent = AcpAgentProfile.fromMap({
      'id': 'codex-acp',
      'name': 'Codex',
      'command': 'codex-acp',
    });
    expect(managed.configurationAvailable, isFalse);
    expect(development.configurationAvailable, isTrue);
    expect(otherAgent.configurationAvailable, isTrue);
  });

  test('deduplicates legacy Xiaowan Bot entries in the ACP catalog', () {
    final catalog = AcpAgentCatalog.fromMap(<String, dynamic>{
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
    expect(catalog.agents.first.name, '小万');
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
      expect(catalog.agents.last.id, 'user-adapter');
    });
  }

  test('local Agent requests use the selected ACP model', () {
    final model = selectAgentRequestModel(
      overrideModel: null,
      activeModel: 'input-selected',
      activeModelSourceMatches: true,
    );

    expect(model, 'input-selected');
  });

  test('shared Agent requests use the verified active Provider model', () {
    final model = selectAgentRequestModel(
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

  test('structured provider limit kinds remain distinct without raw payloads', () {
    const cases = {
      'provider_quota_exceeded': '额度不足',
      'provider_rate_limited': '请求频率',
      'provider_request_limited': '检查额度和请求频率',
    };
    for (final entry in cases.entries) {
      final error = PlatformException(code: 'agent_error', message: 'opaque diagnostic', details: {'failureKind': entry.key});
      final message = formatAgentRuntimeErrorForUser(error);
      expect(message, contains(entry.value));
      expect(message, isNot(contains('opaque')));
      expect(formatAgentRuntimeErrorForUser(message), message);
      expect(formatAgentRuntimeErrorForUser(error, english: true), isNot(message));
    }
  });

  test('ACP provider HTTP failures retain actionable categories without payloads', () {
    const cases = {
      '401': '验证失败',
      '429): insufficient_quota': '额度不足',
      '429): 平台额度不足，请充值或切换 BYOK': '额度不足',
      '429': '检查额度和请求频率',
      '429): rate_limit_exceeded': '限制了请求频率',
      '503': '暂时不可用',
      '400': '拒绝了本次请求',
    };
    for (final entry in cases.entries) {
      final raw = 'chat completion stream request failed(${entry.key}): secret';
      final message = formatAgentRuntimeErrorForUser(raw);
      expect(message, contains(entry.value));
      expect(message, isNot(contains('secret')));
      expect(formatAgentRuntimeErrorForUser(message), message);
    }
    expect(formatAgentRuntimeErrorForUser('command exit 401'),
        '助手暂时无法完成操作，请重试。');
  });

  test('error projection is idempotent and can change language', () {
    final raw = PlatformException(code: 'FAILED', message: 'Request timed out');
    final once = formatAgentRuntimeErrorForUser(raw);
    expect(formatAgentRuntimeErrorForUser(once), once);
    final english = formatAgentRuntimeErrorForUser(once, english: true);
    expect(english, contains('timed out'));
    expect(formatAgentRuntimeErrorForUser(english), once);
  });

  test('installer certificate failure gives a network sign-in action', () {
    final message = formatAgentRuntimeErrorForUser(
      'Failed to prepare adapter: npm error DEPTH_ZERO_SELF_SIGNED_CERT',
    );
    expect(message, contains('网络是否需要登录'));
    expect(message, isNot(contains('SELF_SIGNED_CERT')));
    expect(message, isNot(contains('关闭')));
  });

  test('structured Codex transport failure hides the endpoint', () {
    final message = formatAgentRuntimeErrorForUser(
      'stream disconnected before completion: error sending request for url (https://private.invalid/v1/responses)',
    );
    expect(message, contains('连接已中断'));
    expect(message, isNot(contains('private.invalid')));
    expect(message, isNot(contains('responses')));
  });

  test('unknown native payloads never reach user-facing text', () {
    for (final error in <Object?>[
      null,
      'PlatformException(ACP_ERROR, stack /root/secret https://private.invalid?token=secret)',
      '{"error":{"message":"internal token=secret"}}',
      PlatformException(
        code: 'INTERNAL',
        message: 'secret',
        details: {'token': 'secret'},
      ),
    ]) {
      expect(formatAgentRuntimeErrorForUser(error), '助手暂时无法完成操作，请重试。');
      expect(
        formatAgentRuntimeErrorForUser(error, english: true),
        'The assistant could not complete this action. Please try again.',
      );
    }
  });

  test('real assistant timeout is actionable without raw exception', () {
    final error = PlatformException(
      code: 'AGENT_RUNTIME_CALL_FAILED',
      message: 'Internal error: Request timed out',
    );
    expect(formatAgentRuntimeErrorForUser(error), contains('等待回复超时'));
    expect(formatAgentRuntimeErrorForUser(error), isNot(contains('Internal')));
  });

  test(
    'Claude protocol mismatch explains configuration without guessing an endpoint',
    () {
      final message = formatAgentRuntimeErrorForUser(
        'Failed to initialize ACP agent Claude Code: Claude Code requires an Anthropic-compatible Provider endpoint.',
      );
      expect(message, contains('请更换模型连接或助手'));
      expect(message, isNot(contains('OpenAI')));
      expect(message, isNot(contains('/anthropic')));
      expect(message, isNot(contains('断联')));
    },
  );

  test('namespace tool incompatibility has an actionable error', () {
    final message = formatAgentRuntimeErrorForUser(
      '{"error":{"message":"tools[8].type: unknown variant namespace, '
      'expected one of function, web_search_preview, code_interpreter, mcp"}}',
    );

    expect(message, contains('不支持这个助手的工具'));
    expect(message, isNot(contains('MCP')));
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
      expect(message, contains('连接已中断'));
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

      expect(message, contains('等待回复超时'));
      expect(message, isNot(contains('90000ms')));
    },
  );

  test('model fetch timeout is shown instead of generic configuration failure', () {
    final message = formatAgentRuntimeErrorForUser(
      PlatformException(
        code: 'FETCH_PROVIDER_MODELS_ERROR',
        message: '服务商请求超时',
        details: <String, dynamic>{'failureKind': 'provider_request_timeout'},
      ),
      fallback: '模型列表刷新失败，请检查配置后重试',
    );
    expect(message, contains('等待回复超时'));
    expect(message, isNot(contains('检查配置')));
  });

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

    expect(message, contains('请等待完成后再试'));
    expect(message, isNot(contains('Harness preparation is already running')));
  });

  test('uninstall failure kinds override model diagnostics without exposing them', () {
    const cases = <String, ({String zh, String en})>{
      'agent_uninstall_failed': (
        zh: '卸载未完成，请重试。',
        en: 'Uninstall did not finish. Try again.',
      ),
      'agent_uninstall_external_installation': (
        zh: '检测到其他安装或无法安全移除的文件，请在终端中检查该助手的安装位置。',
        en: 'Another installation or files that could not be safely removed remain. Check the assistant\'s installation in the terminal.',
      ),
      'agent_uninstall_invalid_request': (
        zh: '卸载请求无效，请刷新助手列表后重试。',
        en: 'The uninstall request is invalid. Refresh the assistant list and try again.',
      ),
      'agent_uninstall_distribution_changed': (
        zh: '终端环境已切换，请刷新助手列表后重试。',
        en: 'The terminal environment changed. Refresh the assistant list and try again.',
      ),
      'agent_uninstall_terminal_unavailable': (
        zh: '终端环境不可用，请恢复终端环境后重试卸载。',
        en: 'The terminal environment is unavailable. Restore it before trying to uninstall again.',
      ),
      'agent_package_operation_in_progress': (
        zh: '助手正在安装或卸载，请等待完成后再试。',
        en: 'An assistant is being installed or uninstalled. Try again when it finishes.',
      ),
    };
    for (final entry in cases.entries) {
      final error = PlatformException(
        code: 'agent_uninstall_failed',
        message: 'Timeout, authentication failed, /private/secret-path',
        details: {'failureKind': entry.key},
      );
      final message = formatAgentRuntimeErrorForUser(error);
      expect(message, entry.value.zh);
      expect(
        formatAgentRuntimeErrorForUser(error, english: true),
        entry.value.en,
      );
      expect(formatAgentRuntimeErrorForUser(message), message);
      expect(
        formatAgentRuntimeErrorForUser(entry.value.en),
        entry.value.zh,
      );
      expect(message, isNot(contains('secret-path')));
      expect(message, isNot(contains('更换模型')));
    }
  });

  test('local Agent requests do not read a separate Codex API model', () {
    final model = selectAgentRequestModel(
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
