import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/workspace_memory_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test('batch sends exact entry snapshots in one request', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return {'deletedCount': 2};
    });
    final items = [
      for (final id in ['a', 'b'])
        WorkspaceShortMemoryItem(
          id: id,
          date: '2026-09-06',
          time: '12:00:00',
          content: 'same text',
          timestampMillis: 0,
        ),
    ];
    expect(await WorkspaceMemoryService.deleteShortMemories(items), 2);
    expect(calls.length, 1);
    expect(calls.single.method, 'deleteWorkspaceShortMemories');
    expect(calls.single.arguments, {
      'items': [
        for (final id in ['a', 'b'])
          {
            'id': id,
            'date': '2026-09-06',
            'time': '12:00:00',
            'content': 'same text',
          },
      ],
    });
  });

  test('native stale-entry failure is not reported as success', () async {
    messenger.setMockMethodCallHandler(channel, (_) async {
      throw PlatformException(code: 'DELETE_SHORT_MEMORY_ERROR');
    });
    await expectLater(
      WorkspaceMemoryService.deleteShortMemories(const [
        WorkspaceShortMemoryItem(
          id: 'a',
          date: '2026-09-06',
          time: '12:00:00',
          content: 'old',
          timestampMillis: 0,
        ),
      ]),
      throwsA(isA<PlatformException>()),
    );
  });
}
