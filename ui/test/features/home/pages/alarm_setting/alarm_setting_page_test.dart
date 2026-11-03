import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/alarm_setting/alarm_setting_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  for (final size in const [Size(1024, 600), Size(932, 430)]) {
    testWidgets('alarm form scrolls and saves above keyboard at $size', (
      tester,
    ) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = size;
      tester.view.padding = const FakeViewPadding(top: 24, bottom: 16);
      tester.platformDispatcher.textScaleFactorTestValue = 1.6;
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetPadding);
      addTearDown(tester.view.resetViewInsets);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      MethodCall? save;
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(AssistsMessageService.assistCore, (
        call,
      ) async {
        if (call.method == 'getAlarmSettings') {
          return {
            'source': 'remote_mp3_url',
            'remoteUrl': 'https://example.com/old.mp3',
          };
        }
        if (call.method == 'saveAlarmSettings') {
          save = call;
          return {'success': true};
        }
        return null;
      });
      addTearDown(
        () => messenger.setMockMethodCallHandler(
          AssistsMessageService.assistCore,
          null,
        ),
      );
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: const AlarmSettingPage(),
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      await tester.scrollUntilVisible(
        find.byType(TextField),
        100,
        scrollable: find
            .descendant(
              of: find.byType(ListView),
              matching: find.byType(Scrollable),
            )
            .first,
      );
      await tester.enterText(
        find.byType(TextField),
        'https://example.com/new.mp3',
      );
      tester.view.viewInsets = FakeViewPadding(
        bottom: size.height >= 600 ? 300 : 160,
      );
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byType(TextField));
      await tester.pumpAndSettle();
      expect(find.byType(TextField).hitTestable(), findsOneWidget);
      expect(find.byType(FilledButton).hitTestable(), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.tap(find.byType(FilledButton));
      await tester.pumpAndSettle();
      expect(save?.arguments, {
        'source': 'remote_mp3_url',
        'localPath': null,
        'remoteUrl': 'https://example.com/new.mp3',
      });
      expect(tester.takeException(), isNull);
    });
  }
}
