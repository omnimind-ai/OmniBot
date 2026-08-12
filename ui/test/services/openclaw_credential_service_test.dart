import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/constants/openclaw/openclaw_keys.dart';
import 'package:ui/services/openclaw_credential_service.dart';
import 'package:ui/services/storage_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/openclaw_credential');

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{
      kOpenClawTokenKey: 'legacy-secret',
      kOpenClawBaseUrlKey: 'https://legacy.example',
      kOpenClawUserIdKey: 'legacy-user',
      kOpenClawEnabledKey: true,
    });
    await StorageService.init();
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('legacy values migrate inactive and all Dart copies are erased', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'migrateLegacyInactive');
      final args = call.arguments as Map;
      expect(args['gatewayToken'], 'legacy-secret');
      expect(args['baseUrl'], 'https://legacy.example');
      expect(args['userId'], 'legacy-user');
      return <String, Object>{'success': true, 'status': 'success'};
    });

    expect(await OpenClawCredentialService.migrateLegacyPlaintext(), isTrue);
    expect(StorageService.containsKey(kOpenClawTokenKey), isFalse);
    expect(StorageService.containsKey(kOpenClawBaseUrlKey), isFalse);
    expect(StorageService.containsKey(kOpenClawUserIdKey), isFalse);
    expect(StorageService.containsKey(kOpenClawEnabledKey), isFalse);
  });

  test('migration failure still erases every legacy Dart copy', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (_) async {
      throw PlatformException(code: 'storage_unavailable');
    });

    await expectLater(
      OpenClawCredentialService.migrateLegacyPlaintext(),
      throwsA(isA<PlatformException>()),
    );
    expect(StorageService.containsKey(kOpenClawTokenKey), isFalse);
    expect(StorageService.containsKey(kOpenClawBaseUrlKey), isFalse);
    expect(StorageService.containsKey(kOpenClawUserIdKey), isFalse);
    expect(StorageService.containsKey(kOpenClawEnabledKey), isFalse);
  });

  test('native snapshot exposes status but never a credential', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'getConfiguration');
      return <String, Object>{
        'configured': true,
        'enabled': true,
        'baseUrl': 'https://gateway.example',
        'userId': 'user-1',
        'generation': 7,
        'canonicalOrigin': 'https://gateway.example:443',
        'consentVersion': 1,
        'hasGatewayToken': true,
      };
    });

    final snapshot = await OpenClawCredentialService.loadConfiguration();
    expect(snapshot.enabled, isTrue);
    expect(snapshot.generation, 7);
    expect(snapshot.hasGatewayToken, isTrue);
    expect(
      snapshot.taskPayload(sessionKey: 'session'),
      isNot(contains('token')),
    );
  });

  test('confirmed save sends one CAS transaction and no secret returns', () async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'prepareDestination') {
        return <String, Object>{
          'success': true,
          'status': 'success',
          'requestId': 'one-time',
          'baseUrl': 'https://gateway.example',
          'canonicalOrigin': 'https://gateway.example:443',
          'expectedGeneration': 4,
        };
      }
      return <String, Object>{
        'success': true,
        'status': 'success',
        'configuration': <String, Object>{
          'configured': true,
          'enabled': true,
          'baseUrl': 'https://gateway.example',
          'userId': 'user-1',
          'generation': 5,
          'canonicalOrigin': 'https://gateway.example:443',
          'consentVersion': 1,
          'hasGatewayToken': true,
        },
      };
    });

    final plan = await OpenClawCredentialService.prepareDestination(
      'https://gateway.example',
    );
    final result = await OpenClawCredentialService.saveConfirmed(
      plan: plan,
      baseUrl: plan.baseUrl,
      userId: 'user-1',
      enable: true,
      replacementToken: 'new-secret',
    );

    expect(result.success, isTrue);
    expect(result.configuration?.generation, 5);
    final args = calls.last.arguments as Map;
    expect(args['requestId'], 'one-time');
    expect(args['expectedGeneration'], 4);
    expect(args['credentialAction'], 'replace');
    expect(args['replacementToken'], 'new-secret');
    expect((result.configuration?.taskPayload(sessionKey: 's')), isNot(contains('token')));
  });

  test('standalone secret APIs are rejected', () async {
    await expectLater(
      OpenClawCredentialService.replaceToken('secret'),
      throwsA(isA<OpenClawConfigurationException>()),
    );
    expect(await OpenClawCredentialService.clearToken(), isFalse);
  });

  test('identity state and reset expose only boolean and status', () async {
    final methods = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      methods.add(call.method);
      if (call.method == 'hasExistingIdentity') return false;
      return <String, Object>{'success': true, 'status': 'success'};
    });

    expect(await OpenClawCredentialService.hasExistingIdentity(), isFalse);
    final result = await OpenClawCredentialService.resetDeviceIdentity();
    expect(result.success, isTrue);
    expect(result.status, 'success');
    expect(methods, ['hasExistingIdentity', 'resetDeviceIdentity']);
  });
}
