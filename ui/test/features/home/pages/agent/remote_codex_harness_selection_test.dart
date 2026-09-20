import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late List<MethodCall> calls;
  late Map<String, dynamic> saved;
  late Map<String, dynamic> connection;
  Completer<Map<String, dynamic>>? pending;
  bool failWrite = false;

  CodexRemoteBridgeConfig config({bool enabled = false}) =>
      CodexRemoteBridgeConfig(
        remoteEnabled: enabled,
        remoteConfigured: true,
        remoteBridgeUrl: 'ws://192.0.2.10:17321/codex',
        remoteBridgeToken: 'fixture-token',
        remoteCwd: '/fixture/remote-project',
      );

  setUp(() {
    calls = [];
    pending = null;
    failWrite = false;
    saved = {};
    connection = {'ready': true, 'connected': true, 'runtime': 'remote'};
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'config/remote/write':
          if (failWrite) throw PlatformException(code: 'write_failed');
          saved = Map<String, dynamic>.from(call.arguments as Map);
          return {...saved, 'remoteConfigured': true};
        case 'config/remote/read':
          return {...saved, 'remoteConfigured': true};
        case 'connect':
          return pending != null ? pending!.future : connection;
        case 'session/prompt':
          return {'stopReason': 'end_turn'};
        default:
          throw StateError('Unexpected native call: ${call.method}');
      }
    });
  });
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test(
    'selecting remote Harness enables only the configured remote target',
    () async {
      final status = await AgentRuntimeService.activateRemoteCodex(config());
      expect(status.runtime, 'remote');
      expect(status.connected, isTrue);
      expect(calls.map((c) => c.method), ['config/remote/write', 'connect']);
      expect(saved, {
        'remoteEnabled': true,
        'remoteBridgeUrl': 'ws://192.0.2.10:17321/codex',
        'remoteBridgeToken': 'fixture-token',
        'remoteCwd': '/fixture/remote-project',
      });
    },
  );

  test('already enabled remote target never rewrites config or closes active transport', () async {
    for (var i = 0; i < 3; i++) {
      await AgentRuntimeService.activateRemoteCodex(config(enabled: true));
    }
    // Idempotent native connect owns whether the transport is reused.
    expect(calls.map((c) => c.method), ['connect', 'connect', 'connect']);
  });

  test(
    'enabled but disconnected is not accepted as a successful selection',
    () async {
      connection = {'ready': true, 'connected': false, 'runtime': 'remote'};
      await expectLater(
        AgentRuntimeService.activateRemoteCodex(config(enabled: true)),
        throwsStateError,
      );
      expect(calls.map((c) => c.method), ['connect']);
    },
  );

  test('a connected local runtime cannot masquerade as remote; failed selection restores config', () async {
    connection = {'ready': true, 'connected': true, 'runtime': 'local'};
    await expectLater(
      AgentRuntimeService.activateRemoteCodex(config()),
      throwsStateError,
    );
    expect(calls.map((c) => c.method), [
      'config/remote/write',
      'connect',
      'config/remote/write',
    ]);
    expect(saved['remoteEnabled'], false);
    expect(saved['remoteBridgeToken'], 'fixture-token');
    expect(saved['remoteCwd'], '/fixture/remote-project');
  });

  test(
    'missing pairing and failed saves never start a transport or prompt',
    () async {
      await expectLater(
        AgentRuntimeService.activateRemoteCodex(
          const CodexRemoteBridgeConfig(),
        ),
        throwsStateError,
      );
      expect(calls, isEmpty);
      failWrite = true;
      await expectLater(
        AgentRuntimeService.activateRemoteCodex(config()),
        throwsA(isA<PlatformException>()),
      );
      expect(calls.map((c) => c.method), ['config/remote/write']);
    },
  );

  test(
    'transport failure is not retried; next explicit selection can succeed',
    () async {
      pending = Completer<Map<String, dynamic>>();
      final activation = AgentRuntimeService.activateRemoteCodex(config());
      final failure = expectLater(
        activation,
        throwsA(isA<PlatformException>()),
      );
      await Future<void>.delayed(Duration.zero);
      pending!.completeError(PlatformException(code: 'connection_failed'));
      await failure;
      expect(calls.where((c) => c.method == 'connect'), hasLength(1));
      expect(saved['remoteEnabled'], false);
      pending = null;
      final status = await AgentRuntimeService.activateRemoteCodex(config());
      expect(status.connected, isTrue);
      expect(calls.where((c) => c.method == 'connect'), hasLength(2));
    },
  );

  test('existing switch barrier waits for remote connection before a single canonical prompt', () async {
    final barrier = HarnessSwitchSendBarrier();
    final generation = barrier.begin();
    pending = Completer<Map<String, dynamic>>();
    final switchFuture = barrier.runIfCurrent(generation, () async {
      await AgentRuntimeService.activateRemoteCodex(config());
      barrier.finish(generation, succeeded: true);
    });
    final send = barrier.waitUntilIdle().then((allowed) async {
      if (allowed) {
        await AgentRuntimeService.promptSession(
          sessionId: 'remote-session',
          requestId: 'one-turn',
          agentId: 'codex-remote',
          text: 'fixture request',
        );
      }
    });
    await Future<void>.delayed(Duration.zero);
    expect(calls.any((c) => c.method == 'session/prompt'), false);
    pending!.complete(connection);
    await switchFuture;
    await send;
    final prompts = calls.where((c) => c.method == 'session/prompt').toList();
    expect(prompts, hasLength(1));
    expect(
      prompts.single.arguments,
      containsPair('sessionId', 'remote-session'),
    );
    expect(prompts.single.arguments, containsPair('requestId', 'one-turn'));
    expect(prompts.single.arguments, containsPair('agentId', 'codex-remote'));
    expect(saved['remoteEnabled'], true);
  });

  test('failed remote selection releases queued send as refused, without local fallback', () async {
    final barrier = HarnessSwitchSendBarrier();
    final generation = barrier.begin();
    final send = barrier.waitUntilIdle();
    connection = {'connected': false, 'runtime': 'remote'};
    await barrier.runIfCurrent(generation, () async {
      try {
        await AgentRuntimeService.activateRemoteCodex(config());
        fail('selection should fail');
      } on StateError {
        barrier.finish(generation, succeeded: false);
      }
    });
    expect(await send, false);
    expect(
      calls.any(
        (c) => c.method == 'session/prompt' || c.method == 'agent/select',
      ),
      false,
    );
  });

  test(
    'selection after reload uses persisted pairing without rewriting it',
    () async {
      await AgentRuntimeService.activateRemoteCodex(config());
      calls.clear();
      final restored = await AgentRuntimeService.readRemoteBridgeConfig();
      await AgentRuntimeService.activateRemoteCodex(restored);
      expect(calls.map((c) => c.method), ['config/remote/read', 'connect']);
    },
  );
}
