import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/authorize_setting/authorize_setting_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/widgets/omni_menu.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets(
    'documents menu delegates one grant only after returning to the settings row',
    (tester) async {
      final requests = <MethodCall>[];
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        spePermission,
        (call) async {
          if (call.method == 'getPersonalContextCapabilities') {
            return [
              {'id': 'documents', 'status': 'denied'},
            ];
          }
          if (call.method == 'requestPersonalContextAccess') {
            requests.add(call);
            return null;
          }
          if (call.method == 'getShizukuStatus') return <String, dynamic>{};
          return false;
        },
      );
      addTearDown(
        () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          spePermission,
          null,
        ),
      );
      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: const AuthorizeSettingPage(),
        ),
      );
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(find.text('选择文件与文件夹'), 400);
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('选择文件与文件夹'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('选择文件与文件夹'));
      await tester.pumpAndSettle();
      expect(find.byType(OmniMenuSurface), findsOneWidget);
      expect(find.byType(BottomSheet), findsNothing);
      expect(requests, isEmpty);
      await tester.tap(find.text('选择文件夹'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 250));
      expect(requests, isEmpty);
      await tester.pumpAndSettle();
      expect(requests, hasLength(1));
      expect(requests.single.arguments, {
        'capabilityId': 'documents',
        'directory': true,
      });
      expect(find.byType(OmniMenuSurface), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'personal context is optional and only tapped source is requested',
    (tester) async {
      final requests = <MethodCall>[];
      var calendarStatus = 'denied';
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        spePermission,
        (call) async {
          if (call.method == 'getPersonalContextCapabilities') {
            return [
              {'id': 'calendar_read', 'status': calendarStatus},
              {'id': 'calendar_write', 'status': 'denied'},
              {
                'id': 'location',
                'status': 'partial',
                'scope': ['approximate'],
              },
              {'id': 'background_location', 'status': 'denied'},
              {'id': 'sms', 'status': 'restricted'},
              {'id': 'environment', 'status': 'granted'},
            ];
          }
          if (call.method == 'requestPersonalContextAccess') {
            requests.add(call);
            return null;
          }
          if (call.method == 'getShizukuStatus') return <String, dynamic>{};
          return false;
        },
      );
      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: const AuthorizeSettingPage(),
        ),
      );
      await tester.pumpAndSettle();
      expect(requests, isEmpty);
      await tester.scrollUntilVisible(find.text('读取日历'), 400);
      await tester.tap(find.text('读取日历'));
      await tester.pumpAndSettle();
      expect(requests, hasLength(1));
      expect(requests.single.arguments['capabilityId'], 'calendar_read');
      calendarStatus = 'granted';
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(requests, hasLength(1));
      await tester.scrollUntilVisible(find.text('读取短信'), 400);
      expect(find.text('系统受限'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        spePermission,
        null,
      );
    },
  );
}
