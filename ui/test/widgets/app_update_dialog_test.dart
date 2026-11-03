import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/app_update_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/widgets/app_update_dialog.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/omni_glass.dart';

void main() {
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
    AppUpdateStatus updateStatus = status,
    bool glass = false,
    double textScale = 1,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        themeMode: themeMode,
        locale: const Locale('en'),
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: TextScaler.linear(textScale)),
          child: LiquidGlassScope(
            enabled: glass,
            quality: LiquidGlassQuality.ultra,
            child: LiquidGlassHost(enabled: glass, child: child!),
          ),
        ),
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () => showAppUpdateDialog(context, updateStatus),
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

  for (final themeMode in [ThemeMode.light, ThemeMode.dark]) {
    for (final glass in [false, true]) {
      testWidgets(
        'empty release notes fit content: ${themeMode.name}, glass=$glass',
        (tester) async {
          _viewport(tester);
          await pumpDialog(
            tester,
            themeMode: themeMode,
            glass: glass,
            updateStatus: _statusWithNotes(''),
          );

          final pane = tester.getRect(find.byType(OmniGlassPanel));
          final update = tester.getRect(find.byType(FilledButton));
          expect(pane.bottom, closeTo(844, 1));
          expect(pane.height, lessThan(400));
          // Only the card's bottom padding and gesture safe area should follow
          // the actions; an imposed half-screen height leaves a large gap.
          expect(pane.bottom - update.bottom, inInclusiveRange(24, 56));
          expect(find.text('Release notes'), findsNothing);
          expect(find.text('Update now').hitTestable(), findsOneWidget);
          expect(find.text('Later').hitTestable(), findsOneWidget);
          expect(tester.takeException(), isNull);

          await tester.tap(find.text('Later'));
          await tester.pumpAndSettle();
          expect(find.byType(OmniGlassPanel), findsNothing);
        },
      );
    }
  }

  testWidgets('long release notes scroll with actions still reachable', (
    tester,
  ) async {
    _viewport(tester);
    final notes = List.generate(
      60,
      (index) => 'Improvement ${index + 1}: smoother app interactions.',
    ).join('\n');
    await pumpDialog(
      tester,
      themeMode: ThemeMode.light,
      glass: true,
      updateStatus: _statusWithNotes(notes),
    );

    final notesScroll = find
        .ancestor(
          of: find.text(notes),
          matching: find.byType(SingleChildScrollView),
        )
        .first;
    final scrollState = tester.state<ScrollableState>(
      find.descendant(of: notesScroll, matching: find.byType(Scrollable)).first,
    );
    expect(scrollState.position.maxScrollExtent, greaterThan(0));
    await tester.drag(notesScroll, const Offset(0, -240));
    await tester.pumpAndSettle();
    expect(scrollState.position.pixels, greaterThan(0));
    expect(find.text('Update now').hitTestable(), findsOneWidget);
    expect(find.text('Later').hitTestable(), findsOneWidget);
    expect(
      tester.getRect(find.byType(FilledButton)).bottom,
      lessThanOrEqualTo(820),
    );
    expect(tester.takeException(), isNull);
    await tester.tap(find.text('Later'));
    await tester.pumpAndSettle();
    expect(find.byType(OmniGlassPanel), findsNothing);
  });

  testWidgets('narrow screen and enlarged text keep actions accessible', (
    tester,
  ) async {
    _viewport(tester, size: const Size(320, 568));
    await pumpDialog(
      tester,
      themeMode: ThemeMode.dark,
      textScale: 1.8,
      updateStatus: _statusWithNotes('A short release note.'),
    );

    expect(tester.getRect(find.byType(OmniGlassPanel)).bottom, closeTo(568, 1));
    expect(find.text('Update now').hitTestable(), findsOneWidget);
    expect(find.text('Later').hitTestable(), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.tap(find.text('Later'));
    await tester.pumpAndSettle();
    expect(find.byType(OmniGlassPanel), findsNothing);
  });

  testWidgets('tablet update card stays compact and centered', (tester) async {
    _viewport(tester, size: const Size(1024, 768));
    await pumpDialog(
      tester,
      themeMode: ThemeMode.light,
      glass: true,
      updateStatus: _statusWithNotes(''),
    );

    final pane = tester.getRect(find.byType(OmniGlassPanel));
    expect(pane.width, lessThanOrEqualTo(640));
    expect(pane.center.dx, closeTo(512, 1));
    expect(pane.height, lessThan(400));
    expect(
      pane.bottom - tester.getRect(find.byType(FilledButton)).bottom,
      inInclusiveRange(24, 56),
    );
    expect(tester.takeException(), isNull);
  });
}

AppUpdateStatus _statusWithNotes(String notes) => AppUpdateStatus(
  currentVersion: '0.6.3.7',
  latestVersion: '0.6.3.8',
  hasUpdate: true,
  checkedAt: 1,
  publishedAt: 1793232000000,
  releaseUrl: 'https://example.com/release',
  releaseNotes: notes,
  apkName: 'OpenOmniBot-v0.6.3.8.apk',
  apkDownloadUrl: 'https://example.com/update.apk',
);

void _viewport(WidgetTester tester, {Size size = const Size(390, 844)}) {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  tester.view.viewPadding = const FakeViewPadding(bottom: 24);
  tester.view.padding = const FakeViewPadding(bottom: 24);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(tester.view.resetViewPadding);
  addTearDown(tester.view.resetPadding);
}

Color? _textColor(WidgetTester tester, String text) {
  final widget = tester.widget<Text>(find.text(text));
  return widget.style?.color;
}
