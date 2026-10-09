import 'package:ui/services/device_service.dart';
import 'package:url_launcher/url_launcher.dart';

/// Opens the shared feedback form in a browser with the platform file picker.
/// Only app version metadata is supplied; no account or device identifier is sent.
class FeedbackService {
  static const String feedbackUrl = String.fromEnvironment(
    'OMNIBOT_FEEDBACK_URL',
    defaultValue: 'https://omnibot.omnimind.com.cn/feedback/',
  );

  static Uri buildUri({
    required String languageCode,
    Map<String, dynamic>? versionInfo,
  }) {
    final uri = Uri.parse(feedbackUrl);
    final parameters = <String, String>{
      ...uri.queryParameters,
      'source': 'android',
      'platform': 'android',
      'lang': languageCode == 'en' ? 'en' : 'zh',
    };
    for (final key in const ['versionName', 'versionCode']) {
      final value = versionInfo?[key]?.toString().trim() ?? '';
      if (value.isNotEmpty) parameters[key] = value;
    }
    return uri.replace(queryParameters: parameters);
  }

  static Future<bool> open({required String languageCode}) async {
    try {
      final versionInfo = await DeviceService.getAppVersion();
      return await launchUrl(
        buildUri(languageCode: languageCode, versionInfo: versionInfo),
        mode: LaunchMode.externalApplication,
      );
    } catch (_) {
      return false;
    }
  }
}
