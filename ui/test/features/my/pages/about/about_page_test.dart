import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_switch/flutter_switch.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/my/pages/about/about_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/l10n/generated/app_localizations_en.dart';
import 'package:ui/l10n/generated/app_localizations_zh.dart';
import 'package:ui/services/app_update_service.dart';
import 'package:ui/services/privacy_consent_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const deviceChannel = MethodChannel('device_info');
  const updateChannel = MethodChannel('cn.com.omnimind.bot/app_update');
  const privacyChannel = MethodChannel('cn.com.omnimind.bot/privacy_consent');

  test('privacy copy describes every capability controlled by consent', () {
    final copies = <String>[
      AppLocalizationsZh().aboutPrivacyConsentDescription,
      AppLocalizationsEn().aboutPrivacyConsentDescription,
    ];

    for (final copy in copies) {
      expect(copy.toLowerCase(), contains('mcp'));
      expect(copy.toLowerCase(), contains('ai'));
    }
    expect(copies[0], contains('平台模型'));
    expect(copies[0], contains('检查更新'));
    expect(copies[0], contains('旧定时任务'));
    expect(copies[0], contains('手动发起账号或 AI 请求'));
    expect(copies[0], contains('随机安装标识仅用于更新统计'));
    expect(copies[1], contains('platform models'));
    expect(copies[1], contains('check for updates'));
    expect(copies[1], contains('legacy scheduled tasks'));
    expect(copies[1], contains('requests you start yourself'));
    expect(copies[1], contains('used only for update statistics'));
  });

  tearDown(() async {
    AppUpdateService.availabilityNotifier.value = false;
    AppUpdateService.betaOptInNotifier.value = false;
    AppUpdateService.downloadSourceNotifier.value =
        AppUpdateDownloadSource.worker;
    AppUpdateService.statusNotifier.value = null;
    PrivacyConsentService.decisionNotifier.value =
        PrivacyConsentDecision.pending;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(deviceChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(updateChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(privacyChannel, null);
  });

  testWidgets('shows and persists an explicit privacy decision', (
    tester,
  ) async {
    String? persistedDecision;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(deviceChannel, (call) async {
          if (call.method == 'getAppVersion') {
            return <String, dynamic>{'versionName': '0.0.1'};
          }
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(updateChannel, (call) async {
          if (call.method == 'isSelfUpdateAvailable') return true;
          if (call.method == 'getBetaOptIn') return false;
          if (call.method == 'getApkDownloadSource') return 'worker';
          if (call.method == 'getCachedStatus') return null;
          if (call.method == 'checkNow') return null;
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(privacyChannel, (call) async {
          if (call.method == 'getDecision') return 'declined';
          if (call.method == 'setDecision') {
            persistedDecision =
                (call.arguments as Map<dynamic, dynamic>)['decision']
                    as String?;
            return persistedDecision;
          }
          return null;
        });

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: AboutPage(),
      ),
    );
    await tester.pumpAndSettle();

    final dropdownFinder = find.byKey(
      const ValueKey('about-privacy-consent-dropdown'),
    );
    await tester.ensureVisible(dropdownFinder);
    expect(
      tester
          .widget<DropdownButton<PrivacyConsentDecision>>(dropdownFinder)
          .value,
      PrivacyConsentDecision.declined,
    );

    await tester.tap(dropdownFinder);
    await tester.pumpAndSettle();
    await tester.tap(find.text('已同意').last);
    await tester.pumpAndSettle();

    expect(persistedDecision, 'granted');
    expect(
      PrivacyConsentService.decisionNotifier.value,
      PrivacyConsentDecision.granted,
    );
  });

  testWidgets('renders version and update hint from services', (tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(deviceChannel, (call) async {
          if (call.method == 'getAppVersion') {
            return <String, dynamic>{'versionName': '0.0.1'};
          }
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(updateChannel, (call) async {
          if (call.method == 'isSelfUpdateAvailable') return true;
          if (call.method == 'getBetaOptIn') {
            return false;
          }
          if (call.method == 'getApkDownloadSource') {
            return 'worker';
          }
          if (call.method == 'getCachedStatus') {
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

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: AboutPage(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byType(SingleChildScrollView), findsOneWidget);
    expect(find.text('Version 0.0.1'), findsOneWidget);
    expect(find.text('Omnibot'), findsNothing);
    expect(find.text('加入 beta 测试'), findsOneWidget);
    expect(find.text('安装包下载源'), findsOneWidget);
    expect(find.text('自动联网与后台服务'), findsOneWidget);
    expect(find.textContaining('启动时自动同步平台模型'), findsOneWidget);
    expect(find.textContaining('不会自动恢复后台或局域网服务'), findsOneWidget);
    expect(find.textContaining('随机安装标识仅用于更新统计'), findsOneWidget);
    expect(find.text('Cloudflare R2'), findsWidgets);
    expect(find.textContaining('发现新版本'), findsOneWidget);
    expect(find.text('查看新版本'), findsOneWidget);

    final downloadSourceDropdown = find.byKey(
      const ValueKey('about-download-source-dropdown'),
    );
    await tester.ensureVisible(downloadSourceDropdown);
    await tester.tap(downloadSourceDropdown);
    await tester.pumpAndSettle();

    expect(find.text('通过更新 Worker 分发'), findsOneWidget);
    expect(find.text('官方 Release'), findsOneWidget);
  });

  testWidgets('Play distribution hides every APK update control', (
    tester,
  ) async {
    final updateMethods = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(deviceChannel, (call) async {
          if (call.method == 'getAppVersion') {
            return <String, dynamic>{'versionName': '0.0.1'};
          }
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(updateChannel, (call) async {
          updateMethods.add(call.method);
          if (call.method == 'isSelfUpdateAvailable') return false;
          fail('Play UI invoked forbidden update method ${call.method}');
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(privacyChannel, (call) async {
          if (call.method == 'getDecision') return 'declined';
          return null;
        });

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('en'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: AboutPage(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Version 0.0.1'), findsOneWidget);
    expect(find.byType(FlutterSwitch), findsNothing);
    expect(
      find.byKey(const ValueKey('about-download-source-dropdown')),
      findsNothing,
    );
    expect(updateMethods, <String>['isSelfUpdateAvailable']);
  });

  testWidgets('shows cached beta opt-in value on the first frame', (
    tester,
  ) async {
    AppUpdateService.betaOptInNotifier.value = true;
    final betaRead = Completer<bool>();

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(deviceChannel, (call) async {
          if (call.method == 'getAppVersion') {
            return <String, dynamic>{'versionName': '0.0.1'};
          }
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(updateChannel, (call) async {
          if (call.method == 'isSelfUpdateAvailable') return true;
          if (call.method == 'getBetaOptIn') {
            return betaRead.future;
          }
          if (call.method == 'getApkDownloadSource') {
            return 'worker';
          }
          if (call.method == 'getCachedStatus') {
            return null;
          }
          return null;
        });

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: AboutPage(),
      ),
    );
    await tester.pump();

    final betaSwitch = tester.widget<FlutterSwitch>(find.byType(FlutterSwitch));
    expect(betaSwitch.value, isTrue);
    expect(betaSwitch.duration, Duration.zero);

    betaRead.complete(true);
    await tester.pumpAndSettle();
  });

  testWidgets('does not render always-up-to-date hint on page', (tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(deviceChannel, (call) async {
          if (call.method == 'getAppVersion') {
            return <String, dynamic>{'versionName': '0.0.1'};
          }
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(updateChannel, (call) async {
          if (call.method == 'isSelfUpdateAvailable') return true;
          if (call.method == 'getBetaOptIn') {
            return false;
          }
          if (call.method == 'getApkDownloadSource') {
            return 'worker';
          }
          if (call.method == 'getCachedStatus') {
            return <String, dynamic>{
              'currentVersion': '0.0.1',
              'latestVersion': '0.0.1',
              'hasUpdate': false,
              'checkedAt': 1,
              'publishedAt': 2,
              'releaseUrl': 'https://example.com/release',
              'releaseNotes': 'notes',
              'apkName': 'OpenOmniBot-v0.0.1.apk',
              'apkDownloadUrl': 'https://example.com/app.apk',
            };
          }
          return null;
        });

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: AboutPage(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Version 0.0.1'), findsOneWidget);
    expect(find.text('已是最新版'), findsNothing);
    expect(find.text('检查更新'), findsOneWidget);
    expect(find.text('请求日志'), findsOneWidget);
    expect(find.text('使用手册'), findsOneWidget);
  });
}
