import 'dart:ui';

import 'package:ui/services/app_state_service.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';

final systemLocaleProvider =
    StateNotifierProvider<SystemLocaleController, Locale>(
      (ref) => SystemLocaleController(),
    );

class SystemLocaleController extends StateNotifier<Locale>
    with WidgetsBindingObserver {
  SystemLocaleController() : super(_currentLocale()) {
    WidgetsBinding.instance.addObserver(this);
  }

  static Locale _currentLocale() {
    final locales = WidgetsBinding.instance.platformDispatcher.locales;
    if (locales.isNotEmpty) {
      return locales.first;
    }
    return WidgetsBinding.instance.platformDispatcher.locale;
  }

  /// Android application resources may contain an app-language override. Read
  /// the device locale through the shared native preference boundary instead.
  Future<void> refreshFromNative() async {
    final snapshot = await AppStateService.getUiPreferences();
    restoreFromSnapshot(snapshot);
  }

  void restoreFromSnapshot(Map<dynamic, dynamic> snapshot) {
    final tag = snapshot['systemLocaleTag'] as String?;
    if (tag == null || tag.isEmpty) return;
    final language = tag.split(RegExp('[-_]')).first;
    if (mounted) state = Locale(language);
  }

  @override
  void didChangeLocales(List<Locale>? locales) {
    state = (locales != null && locales.isNotEmpty)
        ? locales.first
        : _currentLocale();
    refreshFromNative().catchError((Object error) {
      debugPrint('Unable to refresh device language: $error');
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }
}
