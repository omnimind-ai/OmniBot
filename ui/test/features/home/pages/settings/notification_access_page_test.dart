import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/settings/notification_access_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';

void main() {
  testWidgets(
    'notification access requires separate system and per-app consent',
    (tester) async {
      const channel = MethodChannel(
        'cn.com.omnimind.bot/SpecialPermissionEvent',
      );
      final calls = <MethodCall>[];
      var allowed = false;
      var granted = false;
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
        call,
      ) async {
        calls.add(call);
        if (call.method == 'setNotificationAppAllowed' ||
            call.method == 'setAllNotificationAppsAllowed') {
          allowed = call.arguments['allowed'] as bool;
        }
        return {
          'granted': granted,
          'connected': granted,
          'apps': [
            {
              'name': 'Delivery fixture',
              'applicationId': 'test.delivery',
              'allowed': allowed,
            },
          ],
        };
      });
      addTearDown(
        () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          channel,
          null,
        ),
      );
      await tester.pumpWidget(
        const MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: NotificationAccessPage(),
        ),
      );
      await tester.pumpAndSettle();
      expect(
        tester.widget<SwitchListTile>(find.byType(SwitchListTile)).value,
        isFalse,
      );
      await tester.tap(find.byType(SwitchListTile));
      await tester.pumpAndSettle();
      expect(calls.last.method, 'setNotificationAppAllowed');
      expect(calls.last.arguments, {
        'applicationId': 'test.delivery',
        'allowed': true,
      });
      granted = true;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(calls.last.method, 'getNotificationAccessSettings');
      await tester.tap(find.byType(SwitchListTile));
      await tester.pumpAndSettle();
      expect(allowed, isFalse);
      await tester.tap(find.text('Enable all'));
      await tester.pumpAndSettle();
      expect(calls.last.method, 'setAllNotificationAppsAllowed');
      expect(calls.last.arguments, {'allowed': true});
      expect(
        tester.widget<SwitchListTile>(find.byType(SwitchListTile)).value,
        isTrue,
      );
      await tester.tap(find.text('Disable all'));
      await tester.pumpAndSettle();
      expect(calls.last.arguments, {'allowed': false});
      expect(allowed, isFalse);
    },
  );
}
