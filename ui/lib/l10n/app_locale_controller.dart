import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';
import 'package:ui/l10n/app_language_mode.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/l10n/system_locale_controller.dart';
import 'package:ui/services/storage_service.dart';

final appLanguageModeProvider =
    StateNotifierProvider<AppLocaleController, AppLanguageMode>(
      (ref) => AppLocaleController(ref),
    );

final appResolvedLocaleProvider = Provider<ResolvedAppLocale>((ref) {
  final mode = ref.watch(appLanguageModeProvider);
  final systemLocale = ref.watch(systemLocaleProvider);
  return resolveAppLocale(mode: mode, systemLocale: systemLocale);
});

class AppLocaleController extends StateNotifier<AppLanguageMode> {
  final Ref ref;

  AppLocaleController(this.ref) : super(StorageService.getLanguageMode()) {
    LegacyTextLocalizer.setResolvedLocale(StorageService.getResolvedLocale());
  }

  Future<void> setLanguageMode(AppLanguageMode mode) async {
    final snapshot = await StorageService.setLanguageMode(mode);
    if (!mounted) return;
    ref.read(systemLocaleProvider.notifier).restoreFromSnapshot(snapshot);
    restoreFromStorage();
  }

  void restoreFromStorage() {
    final mode = StorageService.getLanguageMode();
    final resolvedLocale = resolveAppLocale(
      mode: mode,
      systemLocale: ref.read(systemLocaleProvider),
    );
    LegacyTextLocalizer.setResolvedLocale(resolvedLocale.locale);
    state = mode;
  }
}
