import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/feedback_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/account');
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });
  test(
    'anonymous feedback includes app context without device identifiers',
    () {
      final uri = FeedbackService.buildUri(
        languageCode: 'en',
        versionInfo: {
          'versionName': '0.6.3.3 beta',
          'versionCode': 18,
          'androidId': 'must-not-be-sent',
          'accountId': 'must-not-be-sent',
        },
      );
      expect(uri.host, 'omnibot.omnimind.com.cn');
      expect(uri.path, '/feedback/');
      expect(uri.queryParameters, {
        'source': 'android',
        'platform': 'android',
        'lang': 'en',
        'versionName': '0.6.3.3 beta',
        'versionCode': '18',
      });
    },
  );

  test(
    'feedback still opens the Chinese form when version info is unavailable',
    () {
      final uri = FeedbackService.buildUri(languageCode: 'zh');
      expect(uri.queryParameters['lang'], 'zh');
      expect(uri.queryParameters.containsKey('versionName'), isFalse);
      expect(uri.queryParameters.containsKey('versionCode'), isFalse);
    },
  );
  test('signed-in email is encoded only in the URL fragment', () {
    final uri = FeedbackService.buildUri(
      languageCode: 'zh',
      accountEmail: ' ocean+feedback@example.com ',
    );
    expect(uri.queryParameters.containsKey('accountEmail'), isFalse);
    expect(
      Uri.splitQueryString(uri.fragment)['accountEmail'],
      'ocean+feedback@example.com',
    );
  });

  test(
    'reads email through the account-owned channel without AI setup',
    () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            expect(call.method, 'getFeedbackEmail');
            return ' user@example.com ';
          });
      expect(await FeedbackService.loadAccountEmail(), 'user@example.com');
    },
  );

  test(
    'signed-out accounts and account errors keep feedback available',
    () async {
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(channel, (call) async => '');
      expect(await FeedbackService.loadAccountEmail(), '');
      messenger.setMockMethodCallHandler(channel, (call) async {
        throw PlatformException(code: 'account_not_authenticated');
      });
      expect(await FeedbackService.loadAccountEmail(), '');
      expect(FeedbackService.buildUri(languageCode: 'en').fragment, isEmpty);
    },
  );
}
