import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/account_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/account');

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('reads configured signed-in state', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'getSessionState');
          return <String, Object?>{'configured': true, 'signedIn': true};
        });

    final state = await AccountService.getSessionState();

    expect(state.configured, isTrue);
    expect(state.signedIn, isTrue);
  });

  test(
    'reads safe platform routing state without receiving credentials',
    () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            expect(call.method, 'getAiRoutingState');
            return <String, Object?>{
              'mode': 'platform',
              'ready': true,
              'usesPlatform': true,
              'unavailableReason': null,
            };
          });

      final state = await AccountService.getAiRoutingState();

      expect(state.mode, AiAccessMode.platform);
      expect(state.ready, isTrue);
      expect(state.usesPlatform, isTrue);
      expect(state.unavailableReason, isNull);
    },
  );

  test('parses account overview and platform quota', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'getOverview');
          return _overviewPayload(mode: 'platform', balance: 750);
        });

    final overview = await AccountService.getOverview();

    expect(overview.user.email, 'learner@example.com');
    expect(overview.settings.mode, AiAccessMode.platform);
    expect(overview.settings.platformAvailable, isTrue);
    expect(overview.settings.platform.balance, 750);
    expect(overview.settings.platform.weeklyLimit, 5000);
    expect(overview.settings.platform.weeklyUsed, 1200);
    expect(overview.settings.keyStorage, 'device');
    expect(overview.settings.officialProviderReady, isTrue);
  });

  test('BYOK update sends only mode and never an API key', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'updateAiMode');
          final arguments = Map<dynamic, dynamic>.from(call.arguments as Map);
          expect(arguments, <String, Object?>{'mode': 'byok'});
          expect(arguments.containsKey('apiKey'), isFalse);
          return _settingsPayload(mode: 'byok', balance: 500);
        });

    final settings = await AccountService.updateAiMode(AiAccessMode.byok);

    expect(settings.mode, AiAccessMode.byok);
    expect(settings.keyStorage, 'device');
  });

  test('missing availability flag safely forces BYOK mode', () {
    final settings = AiSettings.fromMap(<String, Object?>{
      'mode': 'platform',
      'keyStorage': 'device',
      'platform': <String, Object?>{
        'platformEnabled': true,
        'balanceQuota': 500,
        'unit': 'new_api_quota',
      },
    });

    expect(settings.platformAvailable, isFalse);
    expect(settings.mode, AiAccessMode.byok);
  });

  test('reads official provider provisioning failure without credentials', () {
    final settings = AiSettings.fromMap(
      _settingsPayload(
        mode: 'platform',
        balance: 500,
        officialProviderReady: false,
        officialProviderStatus: 'Official models are temporarily unavailable',
      ),
    );

    expect(settings.mode, AiAccessMode.platform);
    expect(settings.officialProviderReady, isFalse);
    expect(
      settings.officialProviderStatus,
      'Official models are temporarily unavailable',
    );
  });

  test('password recovery uses the reset-purpose channel methods', () async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          if (call.method == 'requestPasswordResetCode') {
            return <String, Object?>{
              'requestId': 'reset-request-1',
              'expiresInSeconds': 600,
            };
          }
          return null;
        });

    final request = await AccountService.requestPasswordResetCode(
      'learner@example.com',
    );
    await AccountService.resetPassword(
      email: 'learner@example.com',
      newPassword: 'NewPass26!',
      verificationRequestId: request.requestId,
      verificationCode: '123456',
    );

    expect(request.requestId, 'reset-request-1');
    expect(calls.first.method, 'requestPasswordResetCode');
    expect(calls.first.arguments, <String, Object?>{
      'email': 'learner@example.com',
    });
    expect(calls.last.method, 'resetPassword');
    expect(calls.last.arguments, <String, Object?>{
      'email': 'learner@example.com',
      'newPassword': 'NewPass26!',
      'verificationRequestId': 'reset-request-1',
      'verificationCode': '123456',
    });
  });

  test('parses sessions and platform usage records', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'listSessions') {
            return <Map<String, Object?>>[
              <String, Object?>{
                'id': 'session-1',
                'expiresAt': '2026-09-12T08:00:00Z',
                'createdAt': '2026-08-12T08:00:00Z',
                'lastUsedAt': '2026-08-12T09:00:00Z',
                'current': true,
              },
            ];
          }
          if (call.method == 'listPlatformUsage') {
            expect(call.arguments, <String, Object?>{'limit': 10});
            return <Map<String, Object?>>[
              <String, Object?>{
                'model': 'official-text',
                'promptTokens': 5,
                'completionTokens': 7,
                'totalTokens': 12,
                'quotaUsed': 9,
                'createdAt': '2026-08-12T09:00:00Z',
              },
            ];
          }
          return null;
        });

    final sessions = await AccountService.listSessions();
    final usage = await AccountService.listPlatformUsage(limit: 10);

    expect(sessions.single.id, 'session-1');
    expect(sessions.single.current, isTrue);
    expect(sessions.single.lastUsedAt, DateTime.utc(2026, 8, 12, 9));
    expect(usage.single.model, 'official-text');
    expect(usage.single.totalTokens, 12);
    expect(usage.single.quotaUsed, 9);
  });

  test('account security actions send only the required fields', () async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          if (call.method == 'revokeOtherSessions') {
            return <String, Object?>{'revoked': 2};
          }
          return null;
        });

    await AccountService.changePassword(
      currentPassword: 'current password value',
      newPassword: 'NewPass26!',
    );
    await AccountService.revokeSession('session-2');
    final revoked = await AccountService.revokeOtherSessions();
    await AccountService.deleteAccount('current password value');

    expect(revoked, 2);
    expect(calls[0].arguments, <String, Object?>{
      'currentPassword': 'current password value',
      'newPassword': 'NewPass26!',
    });
    expect(calls[1].arguments, <String, Object?>{'sessionId': 'session-2'});
    expect(calls[2].arguments, isNull);
    expect(calls[3].arguments, <String, Object?>{
      'currentPassword': 'current password value',
    });
  });

  test(
    'malformed lifecycle payloads fail with a stable protocol code',
    () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'listSessions') {
              return <Object?>['not-a-session-map'];
            }
            if (call.method == 'revokeOtherSessions') {
              return <String, Object?>{};
            }
            return null;
          });

      await expectLater(
        AccountService.listSessions(),
        throwsA(
          isA<PlatformException>().having(
            (error) => error.code,
            'code',
            'INVALID_ACCOUNT_RESULT',
          ),
        ),
      );
      await expectLater(
        AccountService.revokeOtherSessions(),
        throwsA(
          isA<PlatformException>().having(
            (error) => error.code,
            'code',
            'INVALID_ACCOUNT_RESULT',
          ),
        ),
      );
    },
  );
}

Map<String, Object?> _overviewPayload({
  required String mode,
  required int balance,
}) {
  return <String, Object?>{
    'user': <String, Object?>{
      'id': 'user-1',
      'email': 'learner@example.com',
      'role': 'user',
      'status': 'active',
    },
    'settings': _settingsPayload(mode: mode, balance: balance),
  };
}

Map<String, Object?> _settingsPayload({
  required String mode,
  required int balance,
  bool platformAvailable = true,
  bool officialProviderReady = true,
  String? officialProviderStatus,
}) {
  return <String, Object?>{
    'mode': mode,
    'keyStorage': 'device',
    'platformAvailable': platformAvailable,
    'officialProviderReady': officialProviderReady,
    if (officialProviderStatus != null)
      'officialProviderStatus': officialProviderStatus,
    'platform': <String, Object?>{
      'platformEnabled': true,
      'balanceQuota': balance,
      'weeklyLimitQuota': 5000,
      'weeklyUsedQuota': 1200,
      'unit': 'new_api_quota',
    },
  };
}
