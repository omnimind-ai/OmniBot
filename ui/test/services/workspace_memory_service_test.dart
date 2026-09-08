import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/workspace_memory_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test(
    'short memory requests the complete persisted collection by default',
    () async {
      MethodCall? capturedCall;
      messenger.setMockMethodCallHandler(channel, (call) async {
        capturedCall = call;
        return <String, dynamic>{'items': <Map<String, dynamic>>[]};
      });

      await WorkspaceMemoryService.getShortMemories();

      expect(capturedCall?.method, 'getWorkspaceShortMemories');
      expect(capturedCall?.arguments, isEmpty);
    },
  );

  test(
    'short memory paging is sent only when a caller explicitly asks for it',
    () async {
      MethodCall? capturedCall;
      messenger.setMockMethodCallHandler(channel, (call) async {
        capturedCall = call;
        return <String, dynamic>{'items': <Map<String, dynamic>>[]};
      });

      await WorkspaceMemoryService.getShortMemories(days: 365, limit: 2_000);

      expect(capturedCall?.arguments, <String, int>{
        'days': 365,
        'limit': 2000,
      });
    },
  );
}
