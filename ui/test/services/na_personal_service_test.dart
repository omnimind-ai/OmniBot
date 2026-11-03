import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/agent_runtime_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test('Na personal status captures an owner without exposing connection credentials', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return {
        'owner': 'instance-owner',
        'data': {
          'capabilities': ['profile', 'composio', 'memory_search'],
        },
      };
    });
    final result = await AgentRuntimeService.requestNaPersonal(
      operation: 'status',
    );
    expect(result.owner, 'instance-owner');
    expect(result.data['capabilities'], contains('composio'));
    expect(calls.single.method, 'config/na/personal');
    expect(calls.single.arguments, {
      'agentId': 'na-cloud',
      'operation': 'status',
      'payload': {},
    });
  });

  test(
    'Na mutations retain captured owner and never retry ambiguous effects',
    () async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        throw PlatformException(
          code: 'AGENT_RUNTIME_CALL_FAILED',
          message: 'Connection lost',
        );
      });
      await expectLater(
        AgentRuntimeService.requestNaPersonal(
          operation: 'actions.approve',
          owner: 'original-owner',
          id: 'action-1',
        ),
        throwsA(isA<PlatformException>()),
      );
      expect(calls, hasLength(1));
      expect(calls.single.arguments, {
        'agentId': 'na-cloud',
        'operation': 'actions.approve',
        'owner': 'original-owner',
        'id': 'action-1',
        'payload': {},
      });
      await expectLater(
        AgentRuntimeService.requestNaPersonal(
          operation: 'actions.approve',
          id: 'action-1',
        ),
        throwsArgumentError,
      );
      expect(calls, hasLength(1));
    },
  );

  test('Na recall preserves query and rejects a response attributed to a changed owner', () async {
    Map? arguments;
    messenger.setMockMethodCallHandler(channel, (call) async {
      arguments = call.arguments as Map;
      return {
        'owner': 'replacement-owner',
        'data': {'memories': []},
      };
    });
    await expectLater(
      AgentRuntimeService.requestNaPersonal(
        operation: 'memories.search',
        owner: 'original-owner',
        payload: {'query': '论文', 'limit': 20},
      ),
      throwsStateError,
    );
    expect(arguments?['payload'], {'query': '论文', 'limit': 20});
    expect(arguments?['owner'], 'original-owner');
  });
}
