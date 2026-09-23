import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/l10n/app_language_mode.dart';
import 'package:ui/l10n/app_locale_controller.dart';
import 'package:ui/services/home_greeting_settings_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/services/ui_preferences_sync.dart';
import 'package:ui/theme/app_theme_controller.dart';
import 'package:ui/theme/app_theme_mode.dart';

import '../support/ui_preferences_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  tearDown(clearUiPreferencesChannelFixture);

  test(
    'returning host refreshes cached theme, language and home projections',
    () async {
      SharedPreferences.setMockInitialValues({});
      await StorageService.init();
      installUiPreferencesChannelFixture();
      final container = ProviderContainer();
      addTearDown(container.dispose);
      expect(container.read(appThemeModeProvider), AppThemeMode.system);
      expect(container.read(appLanguageModeProvider), AppLanguageMode.system);
      await HomeGreetingSettingsService.load(force: true);

      // Replace the platform backing data while keeping StorageService's old instance.
      SharedPreferences.setMockInitialValues({
        'theme_option': 'dark',
        'language_option': 'zhHans',
        'home_greeting_settings': jsonEncode({
          'greetingEnabled': false,
          'quickPrompts': [],
          'pinnedQuickPromptIds': [],
        }),
      });
      await UiPreferencesSync.refresh(container);

      expect(container.read(appThemeModeProvider), AppThemeMode.dark);
      expect(container.read(appLanguageModeProvider), AppLanguageMode.zhHans);
      expect(
        HomeGreetingSettingsService.notifier.value.greetingEnabled,
        isFalse,
      );
      expect(HomeGreetingSettingsService.notifier.value.quickPrompts, isEmpty);

      await container
          .read(appLanguageModeProvider.notifier)
          .setLanguageMode(AppLanguageMode.system);
      expect(
        container.read(appResolvedLocaleProvider).locale.languageCode,
        'en',
      );
      expect(StorageService.getLanguageMode(), AppLanguageMode.system);
    },
  );
}
