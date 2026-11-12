import 'package:flutter/widgets.dart';

/// Whether the active locale should show English onboarding copy.
bool onboardingIsEnglish(BuildContext context) =>
    Localizations.localeOf(context).languageCode.toLowerCase() != 'zh';

/// Picks the Chinese or English copy for the current locale.
String onbTr(BuildContext context, String zh, String en) =>
    onboardingIsEnglish(context) ? en : zh;

/// Signature for the localization resolver passed into controllers.
typedef OnboardingTranslator = String Function(String zh, String en);
