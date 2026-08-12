import 'package:flutter/services.dart';
import 'package:ui/constants/openclaw/openclaw_keys.dart';
import 'package:ui/services/storage_service.dart';

class OpenClawConfigurationSnapshot {
  const OpenClawConfigurationSnapshot({
    required this.configured,
    required this.enabled,
    required this.baseUrl,
    required this.userId,
    required this.generation,
    required this.canonicalOrigin,
    required this.consentVersion,
    required this.hasGatewayToken,
  });

  final bool configured;
  final bool enabled;
  final String baseUrl;
  final String userId;
  final int generation;
  final String canonicalOrigin;
  final int consentVersion;
  final bool hasGatewayToken;

  Map<String, dynamic> taskPayload({required String sessionKey}) => {
    'baseUrl': baseUrl,
    'userId': userId,
    'sessionKey': sessionKey,
    'generation': generation,
    'canonicalOrigin': canonicalOrigin,
  };

  static OpenClawConfigurationSnapshot fromMap(Map<dynamic, dynamic>? raw) {
    return OpenClawConfigurationSnapshot(
      configured: raw?['configured'] == true,
      enabled: raw?['enabled'] == true,
      baseUrl: (raw?['baseUrl'] ?? '').toString(),
      userId: (raw?['userId'] ?? '').toString(),
      generation: (raw?['generation'] as num?)?.toInt() ?? 0,
      canonicalOrigin: (raw?['canonicalOrigin'] ?? '').toString(),
      consentVersion: (raw?['consentVersion'] as num?)?.toInt() ?? 0,
      hasGatewayToken: raw?['hasGatewayToken'] == true,
    );
  }
}

class OpenClawDestinationPlan {
  const OpenClawDestinationPlan({
    required this.requestId,
    required this.baseUrl,
    required this.canonicalOrigin,
    required this.expectedGeneration,
  });

  final String requestId;
  final String baseUrl;
  final String canonicalOrigin;
  final int expectedGeneration;
}

class OpenClawConfigurationMutationResult {
  const OpenClawConfigurationMutationResult({
    required this.success,
    required this.status,
    this.configuration,
  });

  final bool success;
  final String status;
  final OpenClawConfigurationSnapshot? configuration;
}

/// Authoritative native OpenClaw configuration and credential access.
///
/// Flutter can submit an explicit credential replacement or clear operation, but native state
/// never returns the credential. Legacy SharedPreferences values are imported only as inactive
/// configuration and then erased, so an app upgrade always needs a new destination confirmation.
class OpenClawCredentialService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/openclaw_credential',
  );

  static Future<OpenClawConfigurationSnapshot> initializeAndLoad() async {
    await migrateLegacyPlaintext();
    return loadConfiguration();
  }

  static Future<bool> migrateLegacyPlaintext() async {
    final legacyToken =
        StorageService.getString(kOpenClawTokenKey, defaultValue: '') ?? '';
    final legacyBaseUrl =
        StorageService.getString(kOpenClawBaseUrlKey, defaultValue: '') ?? '';
    final legacyUserId =
        StorageService.getString(kOpenClawUserIdKey, defaultValue: '') ?? '';
    final hasLegacy =
        StorageService.containsKey(kOpenClawTokenKey) ||
        StorageService.containsKey(kOpenClawBaseUrlKey) ||
        StorageService.containsKey(kOpenClawUserIdKey) ||
        StorageService.containsKey(kOpenClawEnabledKey);
    if (!hasLegacy) return true;
    try {
      final raw = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'migrateLegacyInactive',
        {
          'baseUrl': legacyBaseUrl,
          if (legacyToken.isNotEmpty) 'gatewayToken': legacyToken,
          'userId': legacyUserId,
        },
      );
      return raw?['success'] == true;
    } finally {
      await StorageService.remove(kOpenClawTokenKey);
      await StorageService.remove(kOpenClawBaseUrlKey);
      await StorageService.remove(kOpenClawUserIdKey);
      await StorageService.remove(kOpenClawEnabledKey);
    }
  }

  static Future<OpenClawConfigurationSnapshot> loadConfiguration() async {
    final raw = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'getConfiguration',
    );
    return OpenClawConfigurationSnapshot.fromMap(raw);
  }

  static Future<OpenClawDestinationPlan> prepareDestination(
    String baseUrl,
  ) async {
    final raw = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'prepareDestination',
      {'baseUrl': baseUrl.trim()},
    );
    if (raw?['success'] != true) {
      throw OpenClawConfigurationException(
        (raw?['status'] ?? 'invalid_endpoint').toString(),
      );
    }
    return OpenClawDestinationPlan(
      requestId: (raw?['requestId'] ?? '').toString(),
      baseUrl: (raw?['baseUrl'] ?? '').toString(),
      canonicalOrigin: (raw?['canonicalOrigin'] ?? '').toString(),
      expectedGeneration:
          (raw?['expectedGeneration'] as num?)?.toInt() ?? -1,
    );
  }

  static Future<OpenClawConfigurationMutationResult> saveConfirmed({
    required OpenClawDestinationPlan plan,
    required String baseUrl,
    required String userId,
    required bool enable,
    String replacementToken = '',
    bool clearToken = false,
  }) async {
    final normalizedToken = replacementToken.trim();
    final credentialAction = clearToken
        ? 'clear'
        : (normalizedToken.isNotEmpty ? 'replace' : 'keep');
    final raw = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'saveConfirmedConfiguration',
      {
        'requestId': plan.requestId,
        'expectedGeneration': plan.expectedGeneration,
        'confirmedOrigin': plan.canonicalOrigin,
        'baseUrl': baseUrl.trim(),
        'userId': userId.trim(),
        'credentialAction': credentialAction,
        if (credentialAction == 'replace')
          'replacementToken': normalizedToken,
        'enable': enable,
      },
    );
    return _mutationFromMap(raw);
  }

  static Future<OpenClawConfigurationMutationResult> disable() async {
    final raw = await _channel.invokeMethod<Map<dynamic, dynamic>>('disable');
    return _mutationFromMap(raw);
  }

  static Future<bool> isAuthorized(
    OpenClawConfigurationSnapshot configuration,
  ) async {
    if (!configuration.enabled) return false;
    return await _channel.invokeMethod<bool>('isAuthorized', {
          'baseUrl': configuration.baseUrl,
          'userId': configuration.userId,
          'generation': configuration.generation,
          'canonicalOrigin': configuration.canonicalOrigin,
        }) ??
        false;
  }

  static Future<bool> hasToken() async =>
      (await loadConfiguration()).hasGatewayToken;

  /// Compatibility validation facade. It does not grant consent or mutate native state.
  static Future<void> validateEndpoint(
    String endpoint, {
    bool hasCredential = false,
  }) async {
    await prepareDestination(endpoint);
  }

  /// Compatibility read facade backed only by the authoritative native record.
  static Future<String> loadValidatedBaseUrl() async =>
      (await loadConfiguration()).baseUrl;

  /// Standalone secret writes are forbidden; callers must use [saveConfirmed].
  static Future<void> replaceToken(String token) async {
    throw const OpenClawConfigurationException('transaction_required');
  }

  /// Standalone secret clears are forbidden; callers must use [saveConfirmed].
  static Future<bool> clearToken() async => false;

  static Future<bool> hasExistingIdentity() async {
    return await _channel.invokeMethod<bool>('hasExistingIdentity') ?? false;
  }

  static Future<OpenClawIdentityResetResult> resetDeviceIdentity() async {
    final raw = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'resetDeviceIdentity',
    );
    return OpenClawIdentityResetResult(
      success: raw?['success'] == true,
      status: (raw?['status'] ?? 'core_unavailable').toString(),
    );
  }

  static OpenClawConfigurationMutationResult _mutationFromMap(
    Map<dynamic, dynamic>? raw,
  ) {
    final configurationRaw = raw?['configuration'];
    return OpenClawConfigurationMutationResult(
      success: raw?['success'] == true,
      status: (raw?['status'] ?? 'storage_unavailable').toString(),
      configuration: configurationRaw is Map
          ? OpenClawConfigurationSnapshot.fromMap(configurationRaw)
          : null,
    );
  }
}

class OpenClawConfigurationException implements Exception {
  const OpenClawConfigurationException(this.code);

  final String code;

  @override
  String toString() => 'OpenClawConfigurationException($code)';
}

class OpenClawIdentityResetResult {
  const OpenClawIdentityResetResult({
    required this.success,
    required this.status,
  });

  final bool success;
  final String status;
}
