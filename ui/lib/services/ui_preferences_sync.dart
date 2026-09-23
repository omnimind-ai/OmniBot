import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:ui/l10n/app_locale_controller.dart';
import 'package:ui/l10n/system_locale_controller.dart';
import 'package:ui/theme/app_theme_controller.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/services/home_greeting_settings_service.dart';
import 'package:ui/features/home/state/habitual_hand_controller.dart';
import 'package:ui/features/home/state/predictive_back_controller.dart';
import 'package:ui/services/conversation_service.dart';

/// Refresh existing projections when a cached Flutter host becomes visible again.
/// This reads shared storage; it does not persist settings or repeat side effects.
abstract final class UiPreferencesSync {
  static Future<void> refresh(ProviderContainer container) async {
    final previousRecentOnly =
        StorageService.isRecentConversationsOnlyEnabled();
    await StorageService.reload();
    container.read(habitualHandProvider.notifier).restoreFromStorage();
    container.read(predictiveBackEnabledProvider.notifier).restoreFromStorage();
    if (previousRecentOnly !=
        StorageService.isRecentConversationsOnlyEnabled()) {
      ConversationService.notifySidebarPolicyChangedFromStorage();
    }
    await container.read(systemLocaleProvider.notifier).refreshFromNative();
    container.read(appThemeModeProvider.notifier).restoreFromStorage();
    container.read(appLanguageModeProvider.notifier).restoreFromStorage();
    await HomeGreetingSettingsService.load(force: true);
  }
}
