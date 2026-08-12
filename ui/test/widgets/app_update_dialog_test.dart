import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/app_update_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/widgets/app_update_dialog.dart';

void main() {
  setUp(() {
    AppUpdateService.availabilityNotifier.value = true;
  });

  tearDown(() {
    AppUpdateService.availabilityNotifier.value = false;
  });

  const status = AppUpdateStatus(
    currentVersion: '1.0.0',
    latestVersion: '1.0.1',
    hasUpdate: true,
    checkedAt: 1,
    publishedAt: 1717200000000,
    releaseUrl: 'https://example.com/release',
    releaseNotes: 'Bug fixes and improvements.',
    apkName: 'OpenOmniBot-v1.0.1.apk',
    apkDownloadUrl: '',
  );

  Future<void> pumpDialog(
    WidgetTester tester, {
    required ThemeMode themeMode,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        themeMode: themeMode,
        locale: const Locale('en'),
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () => showAppUpdateDialog(context, status),
              child: const Text('Open dialog'),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.text('Open dialog'));
    await tester.pumpAndSettle();
  }

  testWidgets('uses higher contrast labels in light mode', (tester) async {
    await pumpDialog(tester, themeMode: ThemeMode.light);

    expect(_textColor(tester, 'Current version'), const Color(0xFF5F6F89));
    expect(_textColor(tester, 'Latest version'), const Color(0xFF5F6F89));
    expect(_textColor(tester, 'Published at'), const Color(0xFF5F6F89));
  });

  testWidgets('keeps update labels unchanged in dark mode', (tester) async {
    await pumpDialog(tester, themeMode: ThemeMode.dark);

    expect(
      _textColor(tester, 'Current version'),
      OmniThemePalette.dark.textTertiary,
    );
    expect(
      _textColor(tester, 'Latest version'),
      OmniThemePalette.dark.textTertiary,
    );
    expect(
      _textColor(tester, 'Published at'),
      OmniThemePalette.dark.textTertiary,
    );
  });

  testWidgets('does not open when APK self-update is unavailable', (
    tester,
  ) async {
    AppUpdateService.availabilityNotifier.value = false;

    await pumpDialog(tester, themeMode: ThemeMode.light);

    expect(find.text('New version available'), findsNothing);
    expect(find.text('Current version'), findsNothing);
  });
}

Color? _textColor(WidgetTester tester, String text) {
  final widget = tester.widget<Text>(find.text(text));
  return widget.style?.color;
}
