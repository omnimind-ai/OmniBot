enum AppIconVariant {
  sparkle,
  dot,
  cursor,
  wave,
  open,
  classic;

  String get storageValue => name;
  String get assetPath => 'assets/common/app_icon_$storageValue.png';
  bool get isDefault => this == sparkle;

  static AppIconVariant fromStorageValue(String? value) {
    final normalized = value?.trim().toLowerCase();
    return values.firstWhere(
      (variant) => variant.storageValue == normalized,
      orElse: () => sparkle,
    );
  }
}
