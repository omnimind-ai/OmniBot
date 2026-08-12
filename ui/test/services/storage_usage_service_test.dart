import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/storage_usage_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/StorageUsage');

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test(
    'sends exact local deletion confirmation and parses started state',
    () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            expect(call.method, 'deleteAllLocalData');
            expect(call.arguments, <String, Object?>{'confirmation': '删除本机数据'});
            return <String, Object?>{
              'status': 'STARTED',
              'reason': '',
              'requiresRestart': true,
            };
          });

      final result = await StorageUsageService.deleteAllLocalData('删除本机数据');

      expect(result.started, isTrue);
      expect(result.requiresRestart, isTrue);
    },
  );

  test('rejects unknown native deletion states', () {
    expect(
      () => LocalDataDeletionResult.fromMap(<String, Object?>{
        'status': 'COMPLETE_WITH_SECRET_PATH',
        'reason': r'C:\Users\owner\private',
        'requiresRestart': false,
      }),
      throwsFormatException,
    );
  });
}
