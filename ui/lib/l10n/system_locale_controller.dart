import 'dart:ui';

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

  @override
  void didChangeLocales(List<Locale>? locales) {
    state =
        (locales != null && locales.isNotEmpty) ? locales.first : _currentLocale();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }
}
