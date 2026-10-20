import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/config/jackery_ces_config.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/special_permission.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, null);
  });

  test('CES denied file access returns an English error without hidden route navigation', () async {
    if (!JackeryCesConfig.enabled) return;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, (_) async => false);
    await expectLater(
      OmnibotResourceService.ensureResourceAccess(
        path: '/storage/Download/demo.txt',
      ),
      throwsA(
        isA<PlatformException>()
            .having(
              (error) => error.code,
              'code',
              'CES_RESOURCE_ACCESS_UNAVAILABLE',
            )
            .having(
              (error) => error.message,
              'message',
              contains('staff member'),
            ),
      ),
    );
  });
}
