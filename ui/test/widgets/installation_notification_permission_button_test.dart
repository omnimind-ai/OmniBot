import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/embedded_terminal_preparation_service.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/widgets/installation_notification_permission_button.dart';

void main() {
  for (final allow in [true, false]) {
    testWidgets('notification access is user initiated: allow=$allow', (
      tester,
    ) async {
      var snapshot = EmbeddedTerminalInitSnapshot.fromMap({'running': true});
      var granted = false;
      var requests = 0;
      var installs = 0;
      var foregroundActions = 0;
      var settingsOpens = 0;
      final preparation = EmbeddedTerminalPreparationService(
        loadSnapshot: () async => snapshot,
        progressStream: const Stream.empty(),
        prepare: () async {
          installs++;
          return {'success': true};
        },
      );
      addTearDown(preparation.dispose);
      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('zh'),
          supportedLocales: const [Locale('zh')],
          localizationsDelegates: const [
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          home: Scaffold(
            body: Column(
              children: [
                InstallationNotificationPermissionButton(
                  preparation: preparation,
                  checkPermission: () async => granted,
                  requestPermission: () async {
                    requests++;
                    return granted = allow;
                  },
                  openSettings: () async => settingsOpens++,
                ),
                TextButton(
                  onPressed: () => foregroundActions++,
                  child: const Text('继续使用'),
                ),
              ],
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('显示安装通知'), findsOneWidget);
      expect(requests, 0);
      expect(installs, 0);
      await tester.tap(find.text('继续使用'));
      expect(foregroundActions, 1);

      await tester.tap(find.text('显示安装通知'));
      await tester.pumpAndSettle();
      expect(requests, 1);
      expect(installs, 0);
      expect(preparation.snapshot?.running, isTrue);
      expect(find.text('显示安装通知'), findsNothing);
      if (!allow) {
        expect(find.text('通知设置'), findsOneWidget);
        expect(settingsOpens, 0);
        await tester.tap(find.text('通知设置'));
        await tester.pumpAndSettle();
        expect(settingsOpens, 1);
        expect(requests, 1);
      }
      await tester.tap(find.text('继续使用'));
      expect(foregroundActions, 2);

      snapshot = EmbeddedTerminalInitSnapshot.fromMap({
        'completed': true,
        'success': true,
      });
      await preparation.refresh();
      await tester.pumpAndSettle();
      expect(find.text('显示安装通知'), findsNothing);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    });
  }
}
