import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/authorize_setting/authorize_setting_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/cache.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(debugResetAppEditionCapabilitySnapshot);

  tearDown(() {
    debugResetAppEditionCapabilitySnapshot();
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
            case 'getAppEditionCapabilitySnapshot':
              return <String, dynamic>{
                'schemaVersion': 1,
                'edition': 'standard',
                'installedAppsQuery': true,
                'publicStorageAccess': true,
              };
            case 'isBackgroundRunAllowed':
            case 'isOverlayPermission':
            case 'isInstalledAppsPermissionGranted':
              return true;
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

  testWidgets('Play edition hides unavailable app-list and all-files actions', (
    tester,
  ) async {
    final permissionCalls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(cacheEvent, (call) async => true);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, (call) async {
          permissionCalls.add(call);
          switch (call.method) {
            case 'getAppEditionCapabilitySnapshot':
              return <String, dynamic>{
                'schemaVersion': 1,
                'edition': 'play',
                'installedAppsQuery': false,
                'publicStorageAccess': false,
              };
            case 'isBackgroundRunAllowed':
            case 'isOverlayPermission':
              return true;
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

    expect(find.text('应用列表读取'), findsNothing);
    expect(find.text('所有文件访问权限'), findsNothing);
    expect(find.textContaining('2 / 2'), findsOneWidget);
    expect(
      permissionCalls.map((call) => call.method),
      isNot(contains('isInstalledAppsPermissionGranted')),
    );
    expect(
      permissionCalls.map((call) => call.method),
      isNot(contains('isPublicStorageAccessGranted')),
    );
  });
}
