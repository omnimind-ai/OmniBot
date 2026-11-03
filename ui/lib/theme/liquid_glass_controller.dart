import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/legacy.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';

@immutable
class LiquidGlassSettings {
  const LiquidGlassSettings({
    this.enabled = true,
    this.quality = LiquidGlassQuality.ultra,
  });

  final bool enabled;
  final LiquidGlassQuality quality;
}

final liquidGlassSettingsProvider =
    StateNotifierProvider<LiquidGlassController, LiquidGlassSettings>(
      (ref) => LiquidGlassController(),
    );

class LiquidGlassController extends StateNotifier<LiquidGlassSettings> {
  static const enabledKey = 'liquid_glass_enabled';
  static const qualityKey = 'liquid_glass_quality';

  LiquidGlassController() : super(_read());

  static LiquidGlassSettings _read() {
    try {
      return LiquidGlassSettings(
        enabled: StorageService.getBool(enabledKey, defaultValue: true)!,
        quality: StorageService.getString(qualityKey) == 'standard'
            ? LiquidGlassQuality.standard
            : LiquidGlassQuality.ultra,
      );
    } catch (_) {
      // Match startup's fail-open storage behavior; the default remains usable.
      return const LiquidGlassSettings();
    }
  }

  Future<void> setEnabled(bool enabled) async {
    if (state.enabled == enabled) return;
    state = LiquidGlassSettings(enabled: enabled, quality: state.quality);
    await StorageService.setBool(enabledKey, enabled);
  }

  Future<void> setQuality(LiquidGlassQuality quality) async {
    if (state.quality == quality) return;
    state = LiquidGlassSettings(enabled: state.enabled, quality: quality);
    await StorageService.setString(qualityKey, quality.name);
  }
}
