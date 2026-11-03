import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/widgets/settings_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/my/pages/about/about_page.dart';
import 'package:ui/features/my/pages/feedback/feedback_page.dart';
import 'package:ui/features/my/router_config.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/app_update_service.dart';
import 'package:ui/services/storage_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const deviceChannel = MethodChannel('device_info');
  const updateChannel = MethodChannel('cn.com.omnimind.bot/app_update');
  const accountChannel = MethodChannel('cn.com.omnimind.bot/account');

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
  });

  tearDown(() async {
    AppUpdateService.betaOptInNotifier.value = false;
    AppUpdateService.downloadSourceNotifier.value =
        AppUpdateDownloadSource.worker;
    AppUpdateService.statusNotifier.value = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(deviceChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(updateChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(accountChannel, null);
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
    expect(find.textContaining('同意我们的隐私政策'), findsOneWidget);
    expect(find.text('小万官网'), findsWidgets);
    expect(find.textContaining('发现新版本'), findsOneWidget);
    expect(find.text('查看新版本'), findsOneWidget);

    final downloadSourceDropdown = find.byKey(
      const ValueKey('about-download-source-dropdown'),
    );
    await tester.ensureVisible(downloadSourceDropdown);
    await tester.tap(downloadSourceDropdown);
    await tester.pumpAndSettle();

    expect(find.text('通过小万官网分发'), findsOneWidget);
    expect(find.text('官方 Release'), findsOneWidget);
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

    final betaSwitch = tester.widget<OmniSettingsSwitch>(
      find.byType(OmniSettingsSwitch),
    );
    expect(betaSwitch.value, isTrue);
    expect(betaSwitch.duration, Duration.zero);

    betaRead.complete(true);
    await tester.pumpAndSettle();
  });

  testWidgets('hides up-to-date hint and opens the in-app feedback form', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(deviceChannel, (call) async {
          if (call.method == 'getAppVersion') {
            return <String, dynamic>{'versionName': '0.0.1'};
          }
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(updateChannel, (call) async {
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

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(accountChannel, (call) async => '');
    final router = GoRouter(
      navigatorKey: GoRouterManager.rootNavigatorKey,
      initialLocation: '/my/about',
      routes: myRoutes
          .where(
            (route) =>
                route.path == '/my/about' || route.path == '/my/feedback',
          )
          .toList(),
    );
    addTearDown(router.dispose);

    await tester.pumpWidget(
      MaterialApp.router(
        routerConfig: router,
        locale: const Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Version 0.0.1'), findsOneWidget);
    expect(find.text('已是最新版'), findsNothing);
    expect(find.text('检查更新'), findsOneWidget);
    expect(find.text('请求日志'), findsOneWidget);
    expect(find.text('使用手册'), findsOneWidget);
    expect(find.text('我要反馈'), findsOneWidget);

    final feedbackButton = find.text('我要反馈');
    await tester.ensureVisible(feedbackButton);
    await tester.tap(feedbackButton);
    await tester.pumpAndSettle();
    expect(router.state.uri.path, '/my/feedback');
    expect(find.byType(FeedbackPage), findsOneWidget);
    expect(find.byKey(const ValueKey('feedback-title')), findsOneWidget);
    expect(router.canPop(), isTrue);

    router.pop();
    await tester.pumpAndSettle();
    expect(router.state.uri.path, '/my/about');
    expect(find.byType(AboutPage), findsOneWidget);
  });
}
