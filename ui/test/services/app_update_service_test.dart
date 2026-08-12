import 'dart:async';

import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/app_update_service.dart';
import 'package:ui/services/storage_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/app_update');

  tearDown(() async {
    AppUpdateService.availabilityNotifier.value = false;
    AppUpdateService.betaOptInNotifier.value = false;
    AppUpdateService.downloadSourceNotifier.value =
        AppUpdateDownloadSource.worker;
    AppUpdateService.statusNotifier.value = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('checkNow updates status notifier from channel response', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'isSelfUpdateAvailable') return true;
          if (call.method == 'checkNow') {
            return <String, dynamic>{
              'currentVersion': '0.0.1',
              'latestVersion': '0.0.2',
              'hasUpdate': true,
              'checkedAt': 1,
              'publishedAt': 2,
              'releaseUrl': 'https://example.com/release',
              'releaseNotes': 'notes',
              'apkName': 'OpenOmniBot-v0.0.2.apk',
              'apkDownloadUrl': 'https://example.com/app.apk',
            };
          }
          return null;
        });

    final status = await AppUpdateService.checkNow();

    expect(status, isNotNull);
    expect(status!.hasUpdate, isTrue);
    expect(AppUpdateService.statusNotifier.value?.latestVersion, '0.0.2');
  });

  test('automatic and manual checks share one in-flight channel call', () async {
    AppUpdateService.availabilityNotifier.value = true;
    final response = Completer<Map<String, dynamic>>();
    var checkCallCount = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'checkNow') {
            checkCallCount += 1;
            return response.future;
          }
          return null;
        });

    final manual = AppUpdateService.checkNow();
    final automatic = AppUpdateService.refreshIfNeeded();
    await Future<void>.delayed(Duration.zero);

    expect(checkCallCount, 1);

    response.complete(<String, dynamic>{
      'currentVersion': '1.0.0',
      'latestVersion': '1.1.0',
      'hasUpdate': true,
      'checkedAt': 10,
      'publishedAt': 11,
      'releaseUrl': 'https://example.com/release',
      'releaseNotes': 'notes',
      'apkName': 'OpenOmniBot-v1.1.0-standard.apk',
      'apkDownloadUrl': 'https://example.com/app.apk',
    });

    expect((await manual)?.latestVersion, '1.1.0');
    expect((await automatic)?.latestVersion, '1.1.0');
    expect(checkCallCount, 1);
  });

  test('manual check supersedes an in-flight passive cache check', () async {
    AppUpdateService.availabilityNotifier.value = true;
    final passiveResponse = Completer<Map<String, dynamic>>();
    final forcedResponse = Completer<Map<String, dynamic>>();
    var checkCallCount = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method != 'checkNow') return null;
          checkCallCount += 1;
          final force = (call.arguments as Map<dynamic, dynamic>)['force'] == true;
          return force ? forcedResponse.future : passiveResponse.future;
        });

    final passive = AppUpdateService.refreshIfNeeded();
    await Future<void>.delayed(Duration.zero);
    final forced = AppUpdateService.checkNow();
    await Future<void>.delayed(Duration.zero);

    expect(checkCallCount, 2);

    forcedResponse.complete(<String, dynamic>{
      'currentVersion': '1.0.0',
      'latestVersion': '1.2.0',
      'hasUpdate': true,
      'checkedAt': 20,
      'publishedAt': 21,
      'releaseUrl': 'https://example.com/new-release',
      'releaseNotes': 'new notes',
      'apkName': 'OpenOmniBot-v1.2.0-standard.apk',
      'apkDownloadUrl': 'https://example.com/new.apk',
    });
    expect((await forced)?.latestVersion, '1.2.0');

    passiveResponse.complete(<String, dynamic>{
      'currentVersion': '1.0.0',
      'latestVersion': '1.1.0',
      'hasUpdate': true,
      'checkedAt': 10,
      'publishedAt': 11,
      'releaseUrl': 'https://example.com/old-release',
      'releaseNotes': 'old notes',
      'apkName': 'OpenOmniBot-v1.1.0-standard.apk',
      'apkDownloadUrl': 'https://example.com/old.apk',
    });

    expect((await passive)?.latestVersion, '1.2.0');
    expect(AppUpdateService.statusNotifier.value?.latestVersion, '1.2.0');
  });

  test('late old check cannot replace state after download source changes', () async {
    AppUpdateService.availabilityNotifier.value = true;
    final oldResponse = Completer<Map<String, dynamic>>();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'checkNow') return oldResponse.future;
          if (call.method == 'setApkDownloadSource') return 'github';
          if (call.method == 'getCachedStatus') {
            return <String, dynamic>{
              'currentVersion': '1.0.0',
              'latestVersion': '1.2.0',
              'hasUpdate': true,
              'checkedAt': 20,
              'publishedAt': 21,
              'releaseUrl': 'https://example.com/new-release',
              'releaseNotes': 'new notes',
              'apkName': 'OpenOmniBot-v1.2.0-standard.apk',
              'apkDownloadUrl': 'https://example.com/new.apk',
            };
          }
          return null;
        });

    final oldCheck = AppUpdateService.checkNow();
    await Future<void>.delayed(Duration.zero);
    await AppUpdateService.setDownloadSource(AppUpdateDownloadSource.github);

    oldResponse.complete(<String, dynamic>{
      'currentVersion': '1.0.0',
      'latestVersion': '1.1.0',
      'hasUpdate': true,
      'checkedAt': 10,
      'publishedAt': 11,
      'releaseUrl': 'https://example.com/old-release',
      'releaseNotes': 'old notes',
      'apkName': 'OpenOmniBot-v1.1.0-standard.apk',
      'apkDownloadUrl': 'https://example.com/old.apk',
    });

    expect((await oldCheck)?.latestVersion, '1.2.0');
    expect(AppUpdateService.statusNotifier.value?.latestVersion, '1.2.0');
    expect(
      AppUpdateService.downloadSourceNotifier.value,
      AppUpdateDownloadSource.github,
    );
  });

  test('late source read cannot roll back a newer source selection', () async {
    AppUpdateService.availabilityNotifier.value = true;
    final oldSource = Completer<String>();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'getApkDownloadSource') return oldSource.future;
          if (call.method == 'setApkDownloadSource') return 'github';
          if (call.method == 'getCachedStatus') return null;
          return null;
        });

    final staleRefresh = AppUpdateService.refreshDownloadSource();
    await Future<void>.delayed(Duration.zero);
    await AppUpdateService.setDownloadSource(AppUpdateDownloadSource.github);
    oldSource.complete('worker');

    expect(await staleRefresh, AppUpdateDownloadSource.github);
    expect(
      AppUpdateService.downloadSourceNotifier.value,
      AppUpdateDownloadSource.github,
    );
  });

  test('unavailable distribution never calls APK update methods', () async {
    final invokedMethods = <String>[];
    AppUpdateService.statusNotifier.value = const AppUpdateStatus(
      currentVersion: '1.0.0',
      latestVersion: '1.0.1',
      hasUpdate: true,
      checkedAt: 1,
      publishedAt: 2,
      releaseUrl: 'https://example.com/release',
      releaseNotes: 'notes',
      apkName: 'app.apk',
      apkDownloadUrl: 'https://example.com/app.apk',
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          invokedMethods.add(call.method);
          if (call.method == 'isSelfUpdateAvailable') return false;
          fail('Play distribution invoked forbidden method ${call.method}');
        });

    await AppUpdateService.initialize();
    expect(AppUpdateService.isSelfUpdateAvailable, isFalse);
    expect(AppUpdateService.statusNotifier.value, isNull);
    expect(await AppUpdateService.checkNow(), isNull);
    await expectLater(
      AppUpdateService.installLatestApk(),
      throwsA(isA<UnsupportedError>()),
    );
    expect(invokedMethods, everyElement(equals('isSelfUpdateAvailable')));
  });

  test('download source defaults legacy cnb to worker', () {
    expect(
      AppUpdateDownloadSource.fromRaw(null),
      AppUpdateDownloadSource.worker,
    );
    expect(
      AppUpdateDownloadSource.fromRaw('cnb'),
      AppUpdateDownloadSource.worker,
    );
    expect(
      AppUpdateDownloadSource.fromRaw('github'),
      AppUpdateDownloadSource.github,
    );
  });

  test('setBetaOptIn updates notifier and refreshes status', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'isSelfUpdateAvailable') return true;
          if (call.method == 'setBetaOptIn') {
            return call.arguments['enabled'] == true;
          }
          if (call.method == 'checkNow') {
            return <String, dynamic>{
              'currentVersion': '1.6.1',
              'latestVersion': '1.6.1.2',
              'hasUpdate': true,
              'checkedAt': 3,
              'publishedAt': 4,
              'releaseUrl': 'https://example.com/release',
              'releaseNotes': 'beta notes',
              'apkName': 'OpenOmniBot-v1.6.1.2.apk',
              'apkDownloadUrl': 'https://example.com/app.apk',
            };
          }
          return null;
        });

    final enabled = await AppUpdateService.setBetaOptIn(true);

    expect(enabled, isTrue);
    expect(AppUpdateService.betaOptInNotifier.value, isTrue);
    expect(AppUpdateService.statusNotifier.value?.latestVersion, '1.6.1.2');
  });

  test(
    'setDownloadSource updates notifier and refreshes cached status',
    () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'isSelfUpdateAvailable') return true;
            if (call.method == 'setApkDownloadSource') {
              return call.arguments['source'] as String?;
            }
            if (call.method == 'getCachedStatus') {
              return <String, dynamic>{
                'currentVersion': '1.6.1',
                'latestVersion': '1.6.2',
                'hasUpdate': true,
                'checkedAt': 5,
                'publishedAt': 6,
                'releaseUrl': 'https://example.com/release',
                'releaseNotes': 'stable notes',
                'apkName': 'OpenOmniBot-v1.6.2.apk',
                'apkDownloadUrl':
                    'https://github.com/omnimind-ai/OpenOmniBot/releases/download/v1.6.2/OpenOmniBot-v1.6.2.apk',
              };
            }
            return null;
          });

      final source = await AppUpdateService.setDownloadSource(
        AppUpdateDownloadSource.github,
      );

      expect(source, AppUpdateDownloadSource.github);
      expect(
        AppUpdateService.downloadSourceNotifier.value,
        AppUpdateDownloadSource.github,
      );
      expect(AppUpdateService.statusNotifier.value?.latestVersion, '1.6.2');
    },
  );

  test('dismissBanner hides the banner for the same version only', () async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    AppUpdateService.availabilityNotifier.value = true;

    const status = AppUpdateStatus(
      currentVersion: '0.0.1',
      latestVersion: '0.0.3',
      hasUpdate: true,
      checkedAt: 1,
      publishedAt: 2,
      releaseUrl: 'https://example.com/release',
      releaseNotes: 'notes',
      apkName: 'OpenOmniBot-v0.0.3.apk',
      apkDownloadUrl: 'https://example.com/app.apk',
    );

    expect(AppUpdateService.shouldShowBanner(status), isTrue);

    await AppUpdateService.dismissBanner(status);

    expect(AppUpdateService.shouldShowBanner(status), isFalse);
    expect(
      AppUpdateService.shouldShowBanner(
        const AppUpdateStatus(
          currentVersion: '0.0.1',
          latestVersion: '0.0.4',
          hasUpdate: true,
          checkedAt: 1,
          publishedAt: 2,
          releaseUrl: 'https://example.com/release',
          releaseNotes: 'notes',
          apkName: 'OpenOmniBot-v0.0.4.apk',
          apkDownloadUrl: 'https://example.com/app.apk',
        ),
      ),
      isTrue,
    );
  });
}
