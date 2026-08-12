import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/utils/cache_util.dart';

/// Owns the app-permission side of onboarding: the status of each
/// authorization item shown in Settings › 应用权限授权, plus the notification
/// preference. Statuses refresh whenever the app resumes, since granting a
/// permission always involves a trip to the system settings.
class OnboardingPermissionController extends ChangeNotifier
    with WidgetsBindingObserver {
  bool _disposed = false;
  bool _checking = false;

  bool _backgroundRunning = false;
  bool _overlay = false;
  bool _installedApps = false;
  bool _publicStorage = false;
  AppEditionCapabilitySnapshot _capabilities =
      AppEditionCapabilitySnapshot.unavailable;
  ShizukuStatusSnapshot _shizukuStatus = ShizukuStatusSnapshot.fallback();
  bool _notificationEnabled = true;

  bool get backgroundRunning => _backgroundRunning;
  bool get overlay => _overlay;
  bool get installedApps => _installedApps;
  bool get publicStorage => _publicStorage;
  bool get installedAppsAvailable => _capabilities.installedAppsQuery;
  bool get publicStorageAvailable => _capabilities.publicStorageAccess;
  ShizukuStatusSnapshot get shizukuStatus => _shizukuStatus;
  bool get notificationEnabled => _notificationEnabled;

  /// Play omits installed-app discovery, so it is not counted as a missing
  /// permission that a user could grant later.
  int get coreTotal => installedAppsAvailable ? 3 : 2;

  int get coreReadyCount => <bool>[
    _backgroundRunning,
    _overlay,
    if (installedAppsAvailable) _installedApps,
  ].where((value) => value).length;

  bool get allCoreReady => coreReadyCount == coreTotal;

  double get coreProgress => coreReadyCount / coreTotal;

  /// Registers the lifecycle observer and performs the initial status read.
  void init() {
    WidgetsBinding.instance.addObserver(this);
    unawaited(refresh());
    unawaited(_loadNotificationPreference());
  }

  @override
  void dispose() {
    _disposed = true;
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(refresh());
    }
  }

  void _emit() {
    if (!_disposed) notifyListeners();
  }

  /// Re-reads every permission status. Each probe is isolated so a missing
  /// native channel (e.g. in widget tests) never blocks the others.
  Future<void> refresh() async {
    if (_checking) return;
    _checking = true;
    try {
      var changed = false;
      Future<void> probe(
        Future<bool> Function() read,
        void Function(bool) apply,
      ) async {
        try {
          final value = await read();
          apply(value);
          changed = true;
        } catch (_) {
          // Keep the last known status when the channel is unavailable.
        }
      }

      _capabilities = await refreshAppEditionCapabilitySnapshot();
      changed = true;
      await probe(
        isBackgroundRunAllowed,
        (value) => _backgroundRunning = value,
      );
      await probe(
        () async =>
            await spePermission.invokeMethod('isOverlayPermission') == true,
        (value) => _overlay = value,
      );
      if (installedAppsAvailable) {
        await probe(
          () async =>
              await spePermission.invokeMethod(
                'isInstalledAppsPermissionGranted',
              ) ==
              true,
          (value) => _installedApps = value,
        );
      } else {
        _installedApps = false;
      }
      if (publicStorageAvailable) {
        await probe(
          isPublicStorageAccessGranted,
          (value) => _publicStorage = value,
        );
      } else {
        _publicStorage = false;
      }
      try {
        _shizukuStatus = await getShizukuStatus();
        changed = true;
      } catch (_) {}
      if (changed) _emit();
    } finally {
      _checking = false;
    }
  }

  Future<void> _loadNotificationPreference() async {
    try {
      final value = await CacheUtil.getBool(
        'notification_enabled',
        defaultValue: true,
      );
      if (_disposed) return;
      _notificationEnabled = value;
      _emit();
    } catch (_) {}
  }

  Future<void> setNotificationEnabled(bool value) async {
    _notificationEnabled = value;
    _emit();
    try {
      await CacheUtil.cacheBool('notification_enabled', value);
    } catch (_) {}
  }

  void openBatterySettings() {
    unawaited(spePermission.invokeMethod('openBatteryOptimizationSettings'));
  }

  void openOverlaySettings() {
    unawaited(spePermission.invokeMethod('openOverlaySettings'));
  }

  void openInstalledAppsSettings() {
    if (!installedAppsAvailable) return;
    unawaited(spePermission.invokeMethod('openInstalledAppsSettings'));
  }

  void openStorageSettings() {
    if (!publicStorageAvailable) return;
    unawaited(openPublicStorageSettings());
  }
}
