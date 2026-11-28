import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/legacy.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_icon_variant.dart';

final appIconVariantProvider =
    StateNotifierProvider<AppIconController, AppIconVariant>(
      (ref) => AppIconController(),
    );

class AppIconController extends StateNotifier<AppIconVariant> {
  AppIconController() : super(currentNotifier.value) {
    currentNotifier.addListener(_syncState);
  }

  static final currentNotifier = ValueNotifier(AppIconVariant.sparkle);
  static const _channel = MethodChannel('cn.com.omnimind.bot/app_state');
  static Future<bool>? _pendingSelection;

  static void syncFromStorage() {
    currentNotifier.value = StorageService.getAppIconVariant();
  }

  /// Reconcile an interrupted change with Android before the first frame.
  static Future<void> syncFromNative() async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return;
    try {
      final value = await _channel
          .invokeMethod<String>('getAppIconVariant')
          .timeout(const Duration(milliseconds: 150));
      if (value == null) return;
      final variant = AppIconVariant.fromStorageValue(value);
      if (variant == currentNotifier.value) return;
      currentNotifier.value = variant;
      await _persist(variant);
    } on MissingPluginException {
      // The Flutter module can run without its Android host.
    } on PlatformException catch (error) {
      debugPrint('[AppIcon] Could not read launcher icon: ${error.code}');
    } on TimeoutException {
      // The saved setting is already available for the first frame.
    }
  }

  Future<bool> setVariant(AppIconVariant variant) async {
    final previous = _pendingSelection;
    final selection = previous == null
        ? _applyVariant(variant)
        : previous.then((_) => _applyVariant(variant));
    _pendingSelection = selection;
    try {
      return await selection;
    } finally {
      if (identical(_pendingSelection, selection)) _pendingSelection = null;
    }
  }

  static Future<bool> _applyVariant(AppIconVariant variant) async {
    if (variant == currentNotifier.value) return true;

    var appliedNatively = false;
    try {
      await _channel.invokeMethod<void>('setAppIconVariant', {
        'variant': variant.storageValue,
      });
      appliedNatively = true;
    } on MissingPluginException {
      // Flutter-only runtimes still support changing their in-app icon.
    } on PlatformException catch (error) {
      debugPrint('[AppIcon] Could not change launcher icon: ${error.code}');
      return false;
    }

    final saved = await _persist(variant);
    // Android persists the confirmed launcher choice in the same preference.
    if (!saved && !appliedNatively) return false;
    currentNotifier.value = variant;
    return true;
  }

  static Future<bool> _persist(AppIconVariant variant) async {
    try {
      return await StorageService.setAppIconVariant(variant);
    } catch (error) {
      debugPrint('[AppIcon] Could not save app icon: $error');
      return false;
    }
  }

  void _syncState() {
    state = currentNotifier.value;
  }

  @override
  void dispose() {
    currentNotifier.removeListener(_syncState);
    super.dispose();
  }
}
