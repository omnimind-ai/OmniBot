import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/memory/services/mem0_memory_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  late String longMemory;

  setUp(() {
    longMemory = List<String>.generate(
      25,
      (index) => '- persisted memory ${index + 1}',
    ).join('\n');
    messenger.setMockMethodCallHandler(channel, (call) async {
      switch (call.method) {
        case 'getWorkspaceLongMemory':
          return <String, Object?>{'content': longMemory};
        case 'saveWorkspaceLongMemory':
          longMemory = (call.arguments as Map)['content'].toString();
          return <String, Object?>{'content': longMemory};
        default:
          return null;
      }
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test(
    'long-term memory view preserves every persisted entry by default',
    () async {
      final snapshot = await Mem0MemoryService.getMemories();

      expect(snapshot.items, hasLength(25));
      expect(snapshot.items.last.memory, 'persisted memory 25');
    },
  );

  test(
    'long-term memory paging is opt-in for a caller that requests it',
    () async {
      final snapshot = await Mem0MemoryService.getMemories(limit: 3);

      expect(snapshot.items, hasLength(3));
      expect(snapshot.items.last.memory, 'persisted memory 3');
    },
  );

  test(
    'an entry beyond the old default ceiling can be edited and deleted',
    () async {
      final snapshot = await Mem0MemoryService.getMemories();
      final last = snapshot.items.last;

      await Mem0MemoryService.updateMemory(
        memoryId: last.id,
        memory: 'edited persisted memory 25',
      );
      expect(longMemory, contains('- edited persisted memory 25'));

      final updated = await Mem0MemoryService.getMemories();
      await Mem0MemoryService.deleteMemory(memoryId: updated.items.last.id);
      expect(longMemory, isNot(contains('edited persisted memory 25')));
      expect((await Mem0MemoryService.getMemories()).items, hasLength(24));
    },
  );
}
