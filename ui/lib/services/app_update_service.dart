import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:ui/services/storage_service.dart';

enum AppUpdateDownloadSource {
  worker('worker'),
  github('github');

  const AppUpdateDownloadSource(this.value);

  final String value;

  static AppUpdateDownloadSource fromRaw(String? raw) {
    return AppUpdateDownloadSource.values.firstWhere(
      (source) => source.value == raw?.trim().toLowerCase(),
      orElse: () => AppUpdateDownloadSource.worker,
    );
  }
}

class AppUpdateStatus {
  final String currentVersion;
  final String latestVersion;
  final bool hasUpdate;
  final int checkedAt;
  final int publishedAt;
  final String releaseUrl;
  final String releaseNotes;
  final String apkName;
  final String apkDownloadUrl;

  const AppUpdateStatus({
    required this.currentVersion,
    required this.latestVersion,
    required this.hasUpdate,
    required this.checkedAt,
    required this.publishedAt,
    required this.releaseUrl,
    required this.releaseNotes,
    required this.apkName,
    required this.apkDownloadUrl,
  });

  bool get canInstall => apkDownloadUrl.trim().isNotEmpty;

  String get currentVersionLabel =>
      currentVersion.isEmpty ? '-' : 'v$currentVersion';

  String get latestVersionLabel =>
      latestVersion.isEmpty ? '-' : 'v$latestVersion';

  factory AppUpdateStatus.fromMap(Map<dynamic, dynamic> map) {
    return AppUpdateStatus(
      currentVersion: (map['currentVersion'] as String? ?? '').trim(),
      latestVersion: (map['latestVersion'] as String? ?? '').trim(),
      hasUpdate: map['hasUpdate'] == true,
      checkedAt: _readInt(map['checkedAt']),
      publishedAt: _readInt(map['publishedAt']),
      releaseUrl: (map['releaseUrl'] as String? ?? '').trim(),
      releaseNotes: (map['releaseNotes'] as String? ?? '').trim(),
      apkName: (map['apkName'] as String? ?? '').trim(),
      apkDownloadUrl: (map['apkDownloadUrl'] as String? ?? '').trim(),
    );
  }

  static int _readInt(dynamic raw) {
    if (raw is int) return raw;
    if (raw is double) return raw.toInt();
    if (raw is String) return int.tryParse(raw) ?? 0;
    return 0;
  }
}

class AppUpdateInstallResult {
  final bool success;
  final String status;
  final String message;
  final String? filePath;

  const AppUpdateInstallResult({
    required this.success,
    required this.status,
    required this.message,
    this.filePath,
  });

  factory AppUpdateInstallResult.fromMap(Map<dynamic, dynamic> map) {
    return AppUpdateInstallResult(
      success: map['success'] == true,
      status: (map['status'] as String? ?? '').trim(),
      message: (map['message'] as String? ?? '').trim(),
      filePath: (map['filePath'] as String?)?.trim(),
    );
  }
}

class _InFlightUpdateCheck {
  const _InFlightUpdateCheck({
    required this.configurationGeneration,
    required this.force,
    required this.future,
  });

  final int configurationGeneration;
  final bool force;
  final Future<AppUpdateStatus?> future;
}

class AppUpdateService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/app_update',
  );
  static const String _dismissedBannerVersionKey =
      'dismissed_app_update_banner_version';

  static final ValueNotifier<bool> availabilityNotifier = ValueNotifier<bool>(
    false,
  );
  static final ValueNotifier<bool> betaOptInNotifier = ValueNotifier<bool>(
    false,
  );
  static final ValueNotifier<AppUpdateDownloadSource> downloadSourceNotifier =
      ValueNotifier<AppUpdateDownloadSource>(AppUpdateDownloadSource.worker);
  static final ValueNotifier<AppUpdateStatus?> statusNotifier =
      ValueNotifier<AppUpdateStatus?>(null);
  static int _configurationGeneration = 0;
  static int _statusRequestGeneration = 0;
  static _InFlightUpdateCheck? _inFlightUpdateCheck;

  static Future<void> initialize() => _initialize();

  static Future<void> _initialize() async {
    final available = await refreshAvailability();
    if (!available) {
      _clearUnavailableState();
      return;
    }
    await Future.wait<void>([
      refreshBetaOptIn(),
      refreshCachedStatus(),
      refreshDownloadSource(),
    ]);
    unawaited(_safeRefreshIfNeeded());
  }

  static bool get isSelfUpdateAvailable => availabilityNotifier.value;

  static Future<bool> refreshAvailability() async {
    try {
      final available =
          await _channel.invokeMethod<bool>('isSelfUpdateAvailable') ?? false;
      availabilityNotifier.value = available;
      if (!available) {
        _clearUnavailableState();
      }
      return available;
    } catch (_) {
      availabilityNotifier.value = false;
      _clearUnavailableState();
      return false;
    }
  }

  static Future<bool> refreshBetaOptIn() async {
    if (!await _ensureAvailable()) return false;
    final configurationGeneration = _configurationGeneration;
    try {
      final enabled =
          await _channel.invokeMethod<bool>('getBetaOptIn') ?? false;
      if (configurationGeneration == _configurationGeneration) {
        betaOptInNotifier.value = enabled;
      }
      return betaOptInNotifier.value;
    } catch (_) {
      return betaOptInNotifier.value;
    }
  }

  static Future<AppUpdateStatus?> refreshCachedStatus() async {
    if (!await _ensureAvailable()) return null;
    final requestGeneration = ++_statusRequestGeneration;
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'getCachedStatus',
      );
      final status = result == null ? null : AppUpdateStatus.fromMap(result);
      if (requestGeneration == _statusRequestGeneration) {
        statusNotifier.value = status;
      }
      return requestGeneration == _statusRequestGeneration
          ? status
          : statusNotifier.value;
    } catch (_) {
      return statusNotifier.value;
    }
  }

  static Future<AppUpdateDownloadSource> refreshDownloadSource() async {
    if (!await _ensureAvailable()) return AppUpdateDownloadSource.worker;
    final configurationGeneration = _configurationGeneration;
    try {
      final rawSource = await _channel.invokeMethod<String>(
        'getApkDownloadSource',
      );
      final source = AppUpdateDownloadSource.fromRaw(rawSource);
      if (configurationGeneration == _configurationGeneration) {
        downloadSourceNotifier.value = source;
      }
      return downloadSourceNotifier.value;
    } catch (_) {
      return downloadSourceNotifier.value;
    }
  }

  static Future<AppUpdateStatus?> refreshIfNeeded() {
    return _safeRefreshIfNeeded();
  }

  static Future<AppUpdateStatus?> checkNow() {
    return _check(force: true);
  }

  static Future<bool> setBetaOptIn(bool enabled) async {
    await _requireAvailable();
    _invalidateUpdateConfiguration();
    final updated =
        await _channel.invokeMethod<bool>('setBetaOptIn', {
          'enabled': enabled,
        }) ??
        enabled;
    betaOptInNotifier.value = updated;
    try {
      await _check(force: true);
    } catch (_) {
      await refreshCachedStatus();
    }
    return updated;
  }

  static Future<AppUpdateDownloadSource> setDownloadSource(
    AppUpdateDownloadSource source,
  ) async {
    await _requireAvailable();
    _invalidateUpdateConfiguration();
    final rawSource = await _channel.invokeMethod<String>(
      'setApkDownloadSource',
      {'source': source.value},
    );
    final updatedSource = AppUpdateDownloadSource.fromRaw(rawSource);
    downloadSourceNotifier.value = updatedSource;
    await refreshCachedStatus();
    return updatedSource;
  }

  static Future<AppUpdateInstallResult> installLatestApk() async {
    await _requireAvailable();
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'installLatestApk',
    );
    return AppUpdateInstallResult.fromMap(result ?? const {});
  }

  static bool shouldShowBanner(AppUpdateStatus? status) {
    if (!isSelfUpdateAvailable || status == null || !status.hasUpdate) {
      return false;
    }
    final dismissedVersion = StorageService.getString(
      _dismissedBannerVersionKey,
      defaultValue: '',
    );
    return dismissedVersion != status.latestVersion;
  }

  static Future<void> dismissBanner(AppUpdateStatus status) async {
    if (!isSelfUpdateAvailable || status.latestVersion.isEmpty) {
      return;
    }
    await StorageService.setString(
      _dismissedBannerVersionKey,
      status.latestVersion,
    );
  }

  static Future<AppUpdateStatus?> _check({required bool force}) {
    final configurationGeneration = _configurationGeneration;
    final active = _inFlightUpdateCheck;
    if (active != null &&
        active.configurationGeneration == configurationGeneration &&
        (active.force || !force)) {
      return active.future;
    }

    final requestGeneration = ++_statusRequestGeneration;
    late final Future<AppUpdateStatus?> future;
    future = _performCheck(
      force: force,
      configurationGeneration: configurationGeneration,
      requestGeneration: requestGeneration,
    ).whenComplete(() {
      if (identical(_inFlightUpdateCheck?.future, future)) {
        _inFlightUpdateCheck = null;
      }
    });
    _inFlightUpdateCheck = _InFlightUpdateCheck(
      configurationGeneration: configurationGeneration,
      force: force,
      future: future,
    );
    return future;
  }

  static Future<AppUpdateStatus?> _performCheck({
    required bool force,
    required int configurationGeneration,
    required int requestGeneration,
  }) async {
    if (!await _ensureAvailable()) return null;
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'checkNow',
      {'force': force},
    );
    final status = result == null ? null : AppUpdateStatus.fromMap(result);
    final isCurrent =
        configurationGeneration == _configurationGeneration &&
        requestGeneration == _statusRequestGeneration;
    if (isCurrent) {
      statusNotifier.value = status;
      return status;
    }
    return statusNotifier.value;
  }

  static Future<AppUpdateStatus?> _safeRefreshIfNeeded() async {
    try {
      return await _check(force: false);
    } catch (_) {
      return statusNotifier.value;
    }
  }

  static Future<bool> _ensureAvailable() async {
    if (isSelfUpdateAvailable) return true;
    return refreshAvailability();
  }

  static Future<void> _requireAvailable() async {
    if (await _ensureAvailable()) return;
    throw UnsupportedError(
      'APK self-update is unavailable in this distribution.',
    );
  }

  static void _clearUnavailableState() {
    _invalidateUpdateConfiguration();
    betaOptInNotifier.value = false;
    downloadSourceNotifier.value = AppUpdateDownloadSource.worker;
    statusNotifier.value = null;
  }

  static void _invalidateUpdateConfiguration() {
    _configurationGeneration += 1;
    _statusRequestGeneration += 1;
    _inFlightUpdateCheck = null;
  }
}
