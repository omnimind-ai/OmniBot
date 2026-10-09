import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/feedback_service.dart';

void main() {
  test('feedback includes app context without identifiers or account data', () {
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
  });

  test(
    'feedback still opens the Chinese form when version info is unavailable',
    () {
      final uri = FeedbackService.buildUri(languageCode: 'zh');
      expect(uri.queryParameters['lang'], 'zh');
      expect(uri.queryParameters.containsKey('versionName'), isFalse);
      expect(uri.queryParameters.containsKey('versionCode'), isFalse);
    },
  );
}
