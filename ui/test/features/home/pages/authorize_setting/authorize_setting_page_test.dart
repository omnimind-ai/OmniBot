import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/authorize_setting/authorize_setting_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/cache.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/settings_ui.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(cacheEvent, null);
  });

  testWidgets('renders all files access entry and opens native settings', (
    tester,
  ) async {
    final permissionCalls = <MethodCall>[];

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(cacheEvent, (call) async {
          if (call.method == 'doMMKVDecodeBoole') {
            return true;
          }
          return null;
        });

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, (call) async {
          permissionCalls.add(call);
          switch (call.method) {
            case 'isBackgroundRunAllowed':
            case 'isOverlayPermission':
            case 'isInstalledAppsPermissionGranted':
              return true;
            case 'isAndroidGuiAccessibilityReady':
              return false;
            case 'isPublicStorageAccessGranted':
              return false;
            case 'getShizukuStatus':
              return <String, dynamic>{
                'status': 'NOT_INSTALLED',
                'backend': 'NONE',
                'installed': false,
                'running': false,
                'permissionGranted': false,
                'binderReady': false,
                'serviceBound': false,
                'availableActions': <String>[],
                'message': '',
              };
            case 'openPublicStorageSettings':
              return true;
          }
          return null;
        });

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        theme: AppTheme.lightTheme,
        home: const AuthorizeSettingPage(),
      ),
    );
    await tester.pumpAndSettle();

    final entry = find.text('所有文件访问权限');
    await tester.scrollUntilVisible(entry, 120);
    await tester.pumpAndSettle();

    expect(entry, findsOneWidget);
    expect(find.textContaining('公共存储'), findsOneWidget);

    await tester.tap(entry);
    await tester.pump();

    expect(
      permissionCalls.map((call) => call.method),
      contains('openPublicStorageSettings'),
    );
  });

  testWidgets(
    'shows where to enable accessibility when permission is missing',
    (tester) async {
      final permissionCalls = <MethodCall>[];

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(cacheEvent, (call) async => true);
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, (call) async {
            permissionCalls.add(call);
            switch (call.method) {
              case 'isBackgroundRunAllowed':
              case 'isOverlayPermission':
              case 'isInstalledAppsPermissionGranted':
                return true;
              case 'isAndroidGuiAccessibilityReady':
              case 'isPublicStorageAccessGranted':
                return false;
              case 'getShizukuStatus':
                return <String, dynamic>{
                  'status': 'NOT_INSTALLED',
                  'backend': 'NONE',
                  'installed': false,
                  'running': false,
                  'permissionGranted': false,
                  'binderReady': false,
                  'serviceBound': false,
                  'availableActions': <String>[],
                  'message': '',
                };
              case 'openAndroidGuiAccessibilitySettings':
                return null;
            }
            return null;
          });

      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          theme: AppTheme.lightTheme,
          home: const AuthorizeSettingPage(),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('3 / 4 项核心授权已就绪'), findsOneWidget);
      expect(find.text('无障碍权限'), findsOneWidget);

      await tester.tap(find.text('无障碍权限'));
      await tester.pumpAndSettle();

      expect(find.text('开启位置'), findsOneWidget);
      expect(find.textContaining('已下载的应用'), findsOneWidget);

      await tester.tap(find.text('打开系统无障碍设置'));
      await tester.pump();
      expect(
        permissionCalls.map((call) => call.method),
        contains('openAndroidGuiAccessibilitySettings'),
      );
    },
  );

  testWidgets(
    'requests local network access and refreshes denied and granted states',
    (tester) async {
      var granted = false;
      var allowRequest = false;
      final calls = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(cacheEvent, (call) async => true);
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, (call) async {
            calls.add(call.method);
            if (call.method == 'getLocalNetworkPermissionStatus') {
              return {'required': true, 'granted': granted};
            }
            if (call.method == 'requestLocalNetworkPermission') {
              granted = allowRequest;
              return granted;
            }
            return null;
          });
      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          theme: AppTheme.lightTheme,
          home: const AuthorizeSettingPage(),
        ),
      );
      await tester.pumpAndSettle();
      final entry = find.text('局域网访问');
      await tester.scrollUntilVisible(entry, 120);
      await tester.pumpAndSettle();
      final tile = find.ancestor(
        of: entry,
        matching: find.byType(OmniSettingsTile),
      );
      expect(
        find.descendant(of: tile, matching: find.text('去开启')),
        findsOneWidget,
      );

      await tester.tap(entry);
      await tester.pumpAndSettle();
      expect(calls, contains('requestLocalNetworkPermission'));
      expect(find.textContaining('局域网访问未授权'), findsOneWidget);
      expect(
        find.descendant(of: tile, matching: find.text('去开启')),
        findsOneWidget,
      );

      allowRequest = true;
      await tester.tap(entry);
      await tester.pumpAndSettle();
      expect(
        find.descendant(of: tile, matching: find.text('已开启')),
        findsOneWidget,
      );
    },
  );
}
