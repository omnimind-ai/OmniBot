import 'dart:ui';

enum AppLanguageMode {
  system('system'),
  zhHans('zhHans'),
  en('en'),
  ptBr('ptBr');

  const AppLanguageMode(this.storageValue);

  final String storageValue;

  static AppLanguageMode fromStorageValue(String? raw) {
    final normalized = raw?.trim();
    return AppLanguageMode.values.firstWhere(
      (mode) => mode.storageValue == normalized,
      orElse: () => AppLanguageMode.system,
    );
  }
}

class ResolvedAppLocale {
  const ResolvedAppLocale({
    required this.mode,
    required this.systemLocale,
    required this.locale,
  });

  final AppLanguageMode mode;
  final Locale systemLocale;
  final Locale locale;

  bool get isEnglish => locale.languageCode == 'en';
  bool get isPortuguese => locale.languageCode == 'pt';
  bool get isChinese => locale.languageCode == 'zh';
  String get brandName => isChinese ? '小万' : 'Omnibot';
}

ResolvedAppLocale resolveAppLocale({
  required AppLanguageMode mode,
  required Locale systemLocale,
}) {
  final normalizedSystemLocale = _normalizeSupportedLocale(systemLocale);
  final resolvedLocale = switch (mode) {
    AppLanguageMode.system => normalizedSystemLocale,
    AppLanguageMode.zhHans => const Locale('zh', 'CN'),
    AppLanguageMode.en => const Locale('en', 'US'),
    AppLanguageMode.ptBr => const Locale('pt', 'BR'),
  };

  return ResolvedAppLocale(
    mode: mode,
    systemLocale: normalizedSystemLocale,
    locale: resolvedLocale,
  );
}

Locale _normalizeSupportedLocale(Locale locale) {
  final language = locale.languageCode.toLowerCase();
  if (language == 'zh') {
    return const Locale('zh', 'CN');
  }
  if (language == 'pt') {
    return const Locale('pt', 'BR');
  }
  return const Locale('en', 'US');
}
