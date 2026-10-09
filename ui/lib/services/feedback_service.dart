import 'package:ui/services/account_service.dart';
import 'package:ui/services/device_service.dart';
import 'package:url_launcher/url_launcher.dart';

/// Opens the shared feedback form in a browser with the platform file picker.
/// App version and the signed-in account email accompany feedback.
/// Email uses the fragment so it is not included in HTTP URL/access logs.
class FeedbackService {
  static const String feedbackUrl = String.fromEnvironment(
    'OMNIBOT_FEEDBACK_URL',
    defaultValue: 'https://omnibot.omnimind.com.cn/feedback/',
  );

  static Uri buildUri({
    required String languageCode,
    Map<String, dynamic>? versionInfo,
    String? accountEmail,
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
    final email = accountEmail?.trim() ?? '';
    final validEmail =
        email.length <= 254 &&
        RegExp(r'^[^\s@]+@[^\s@]+\.[^\s@]+$').hasMatch(email);
    return uri.replace(
      queryParameters: parameters,
      fragment: validEmail
          ? Uri(queryParameters: {'accountEmail': email}).query
          : '',
    );
  }

  static Future<String> loadAccountEmail() async {
    try {
      return await AccountService.getFeedbackEmail().timeout(
        const Duration(seconds: 5),
      );
    } catch (_) {
      // Signed-out/offline users can always supply contact details themselves.
      return '';
    }
  }

  static Future<bool> open({required String languageCode}) async {
    try {
      final email = loadAccountEmail();
      final versionInfo = await DeviceService.getAppVersion();
      final accountEmail = await email;
      return await launchUrl(
        buildUri(
          languageCode: languageCode,
          versionInfo: versionInfo,
          accountEmail: accountEmail,
        ),
        mode: LaunchMode.externalApplication,
      );
    } catch (_) {
      return false;
    }
  }
}
