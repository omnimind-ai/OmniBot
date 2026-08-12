import 'package:flutter/services.dart';

enum AiAccessMode { platform, byok }

class AccountSessionState {
  const AccountSessionState({required this.configured, required this.signedIn});

  final bool configured;
  final bool signedIn;

  factory AccountSessionState.fromMap(Map<dynamic, dynamic> map) {
    return AccountSessionState(
      configured: map['configured'] == true,
      signedIn: map['signedIn'] == true,
    );
  }
}

class AiRoutingState {
  const AiRoutingState({
    required this.mode,
    required this.ready,
    required this.usesPlatform,
    this.unavailableReason,
  });

  final AiAccessMode? mode;
  final bool ready;
  final bool usesPlatform;
  final String? unavailableReason;

  factory AiRoutingState.fromMap(Map<dynamic, dynamic> map) {
    final rawMode = map['mode']?.toString();
    final reason = map['unavailableReason']?.toString().trim();
    return AiRoutingState(
      mode: switch (rawMode) {
        'platform' => AiAccessMode.platform,
        'byok' => AiAccessMode.byok,
        _ => null,
      },
      ready: map['ready'] == true,
      usesPlatform: map['usesPlatform'] == true,
      unavailableReason: reason == null || reason.isEmpty ? null : reason,
    );
  }
}

class AccountUser {
  const AccountUser({
    required this.id,
    required this.email,
    required this.role,
    required this.status,
  });

  final String id;
  final String email;
  final String role;
  final String status;

  factory AccountUser.fromMap(Map<dynamic, dynamic> map) {
    return AccountUser(
      id: (map['id'] ?? '').toString(),
      email: (map['email'] ?? '').toString(),
      role: (map['role'] ?? '').toString(),
      status: (map['status'] ?? '').toString(),
    );
  }
}

class RegistrationCodeRequest {
  const RegistrationCodeRequest({
    required this.requestId,
    required this.expiresInSeconds,
  });

  final String requestId;
  final int expiresInSeconds;

  factory RegistrationCodeRequest.fromMap(Map<dynamic, dynamic> map) {
    return RegistrationCodeRequest(
      requestId: (map['requestId'] ?? '').toString(),
      expiresInSeconds: (map['expiresInSeconds'] as num?)?.toInt() ?? 0,
    );
  }
}

class AccountDeviceSession {
  const AccountDeviceSession({
    required this.id,
    required this.expiresAt,
    required this.createdAt,
    required this.lastUsedAt,
    required this.current,
  });

  final String id;
  final DateTime? expiresAt;
  final DateTime? createdAt;
  final DateTime? lastUsedAt;
  final bool current;

  factory AccountDeviceSession.fromMap(Map<dynamic, dynamic> map) {
    DateTime? parse(String key) =>
        DateTime.tryParse((map[key] ?? '').toString());
    return AccountDeviceSession(
      id: (map['id'] ?? '').toString(),
      expiresAt: parse('expiresAt'),
      createdAt: parse('createdAt'),
      lastUsedAt: parse('lastUsedAt'),
      current: map['current'] == true,
    );
  }
}

class PlatformUsageEntry {
  const PlatformUsageEntry({
    required this.model,
    required this.promptTokens,
    required this.completionTokens,
    required this.totalTokens,
    required this.quotaUsed,
    required this.createdAt,
  });

  final String model;
  final int promptTokens;
  final int completionTokens;
  final int totalTokens;
  final int quotaUsed;
  final DateTime? createdAt;

  factory PlatformUsageEntry.fromMap(Map<dynamic, dynamic> map) {
    return PlatformUsageEntry(
      model: (map['model'] ?? '').toString(),
      promptTokens: (map['promptTokens'] as num?)?.toInt() ?? 0,
      completionTokens: (map['completionTokens'] as num?)?.toInt() ?? 0,
      totalTokens: (map['totalTokens'] as num?)?.toInt() ?? 0,
      quotaUsed: (map['quotaUsed'] as num?)?.toInt() ?? 0,
      createdAt: DateTime.tryParse((map['createdAt'] ?? '').toString()),
    );
  }
}

class PlatformQuota {
  const PlatformQuota({
    required this.enabled,
    required this.balance,
    required this.weeklyLimit,
    required this.weeklyUsed,
    this.weeklyPeriodStart,
    required this.unit,
  });

  final bool enabled;
  final int balance;
  final int weeklyLimit;
  final int weeklyUsed;
  final DateTime? weeklyPeriodStart;
  final String unit;

  factory PlatformQuota.fromMap(Map<dynamic, dynamic> map) {
    return PlatformQuota(
      enabled: map['platformEnabled'] == true,
      balance: (map['balanceQuota'] as num?)?.toInt() ?? 0,
      weeklyLimit: (map['weeklyLimitQuota'] as num?)?.toInt() ?? 0,
      weeklyUsed: (map['weeklyUsedQuota'] as num?)?.toInt() ?? 0,
      weeklyPeriodStart: DateTime.tryParse(
        (map['weeklyPeriodStart'] ?? '').toString(),
      ),
      unit: (map['unit'] ?? '').toString(),
    );
  }
}

class AiSettings {
  const AiSettings({
    required this.mode,
    required this.keyStorage,
    required this.platform,
    required this.platformAvailable,
    this.platformUnavailableReason,
    required this.officialProviderReady,
    this.officialProviderStatus,
  });

  final AiAccessMode mode;
  final String keyStorage;
  final PlatformQuota platform;
  final bool platformAvailable;
  final String? platformUnavailableReason;
  final bool officialProviderReady;
  final String? officialProviderStatus;

  factory AiSettings.fromMap(Map<dynamic, dynamic> map) {
    final platform = map['platform'];
    final platformAvailable = map['platformAvailable'] == true;
    final unavailableReason = map['platformUnavailableReason']
        ?.toString()
        .trim();
    final officialProviderStatus = map['officialProviderStatus']
        ?.toString()
        .trim();
    return AiSettings(
      mode: platformAvailable && (map['mode'] ?? '').toString() == 'platform'
          ? AiAccessMode.platform
          : AiAccessMode.byok,
      keyStorage: (map['keyStorage'] ?? '').toString(),
      platform: PlatformQuota.fromMap(
        platform is Map ? Map<dynamic, dynamic>.from(platform) : const {},
      ),
      platformAvailable: platformAvailable,
      platformUnavailableReason:
          unavailableReason == null || unavailableReason.isEmpty
          ? null
          : unavailableReason,
      officialProviderReady: map['officialProviderReady'] == true,
      officialProviderStatus:
          officialProviderStatus == null || officialProviderStatus.isEmpty
          ? null
          : officialProviderStatus,
    );
  }
}

class AccountOverview {
  const AccountOverview({required this.user, required this.settings});

  final AccountUser user;
  final AiSettings settings;

  factory AccountOverview.fromMap(Map<dynamic, dynamic> map) {
    final user = map['user'];
    final settings = map['settings'];
    if (user is! Map || settings is! Map) {
      throw const FormatException('Invalid account overview');
    }
    return AccountOverview(
      user: AccountUser.fromMap(Map<dynamic, dynamic>.from(user)),
      settings: AiSettings.fromMap(Map<dynamic, dynamic>.from(settings)),
    );
  }
}

class AccountService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/account',
  );

  static Future<AccountSessionState> getSessionState() async {
    final result = await _requiredMap('getSessionState');
    return AccountSessionState.fromMap(result);
  }

  static Future<AiRoutingState> getAiRoutingState() async {
    final result = await _requiredMap('getAiRoutingState');
    return AiRoutingState.fromMap(result);
  }

  static Future<RegistrationCodeRequest> requestRegistrationCode(
    String email,
  ) async {
    final result = await _requiredMap(
      'requestRegistrationCode',
      <String, Object?>{'email': email},
    );
    return RegistrationCodeRequest.fromMap(result);
  }

  static Future<RegistrationCodeRequest> requestPasswordResetCode(
    String email,
  ) async {
    final result = await _requiredMap(
      'requestPasswordResetCode',
      <String, Object?>{'email': email},
    );
    return RegistrationCodeRequest.fromMap(result);
  }

  static Future<void> resetPassword({
    required String email,
    required String newPassword,
    required String verificationRequestId,
    required String verificationCode,
  }) => _channel.invokeMethod<void>('resetPassword', <String, Object?>{
    'email': email,
    'newPassword': newPassword,
    'verificationRequestId': verificationRequestId,
    'verificationCode': verificationCode,
  });

  static Future<AccountUser> register({
    required String email,
    required String password,
    required String verificationRequestId,
    required String verificationCode,
  }) async {
    final result = await _requiredMap('register', <String, Object?>{
      'email': email,
      'password': password,
      'verificationRequestId': verificationRequestId,
      'verificationCode': verificationCode,
    });
    return AccountUser.fromMap(result);
  }

  static Future<AccountUser> login({
    required String email,
    required String password,
  }) async {
    final result = await _requiredMap('login', <String, Object?>{
      'email': email,
      'password': password,
    });
    return AccountUser.fromMap(result);
  }

  static Future<void> logout() => _channel.invokeMethod<void>('logout');

  static Future<AccountOverview> getOverview() async {
    final result = await _requiredMap('getOverview');
    return AccountOverview.fromMap(result);
  }

  static Future<AiSettings> updateAiMode(AiAccessMode mode) async {
    final result = await _requiredMap('updateAiMode', <String, Object?>{
      'mode': mode == AiAccessMode.byok ? 'byok' : 'platform',
    });
    return AiSettings.fromMap(result);
  }

  static Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) => _channel.invokeMethod<void>('changePassword', <String, Object?>{
    'currentPassword': currentPassword,
    'newPassword': newPassword,
  });

  static Future<List<AccountDeviceSession>> listSessions() async {
    final result = await _requiredList('listSessions');
    return result
        .map((item) {
          if (item is! Map) {
            throw _invalidAccountResult();
          }
          return AccountDeviceSession.fromMap(Map<dynamic, dynamic>.from(item));
        })
        .toList(growable: false);
  }

  static Future<void> revokeSession(String sessionId) =>
      _channel.invokeMethod<void>('revokeSession', <String, Object?>{
        'sessionId': sessionId,
      });

  static Future<int> revokeOtherSessions() async {
    final result = await _requiredMap('revokeOtherSessions');
    final revoked = result['revoked'];
    if (revoked is! num || revoked.toInt() < 0) {
      throw _invalidAccountResult();
    }
    return revoked.toInt();
  }

  static Future<List<PlatformUsageEntry>> listPlatformUsage({
    int limit = 20,
  }) async {
    final result = await _requiredList('listPlatformUsage', <String, Object?>{
      'limit': limit,
    });
    return result
        .map((item) {
          if (item is! Map) {
            throw _invalidAccountResult();
          }
          return PlatformUsageEntry.fromMap(Map<dynamic, dynamic>.from(item));
        })
        .toList(growable: false);
  }

  static Future<void> deleteAccount(String currentPassword) =>
      _channel.invokeMethod<void>('deleteAccount', <String, Object?>{
        'currentPassword': currentPassword,
      });

  static Future<Map<dynamic, dynamic>> _requiredMap(
    String method, [
    Map<String, Object?>? arguments,
  ]) async {
    final result = await _channel.invokeMethod<dynamic>(method, arguments);
    if (result is! Map) {
      throw _invalidAccountResult();
    }
    return Map<dynamic, dynamic>.from(result);
  }

  static Future<List<dynamic>> _requiredList(
    String method, [
    Map<String, Object?>? arguments,
  ]) async {
    final result = await _channel.invokeMethod<dynamic>(method, arguments);
    if (result is! List) {
      throw _invalidAccountResult();
    }
    return List<dynamic>.from(result);
  }

  static PlatformException _invalidAccountResult() => PlatformException(
    code: 'INVALID_ACCOUNT_RESULT',
    message: 'Account result is invalid',
  );
}
