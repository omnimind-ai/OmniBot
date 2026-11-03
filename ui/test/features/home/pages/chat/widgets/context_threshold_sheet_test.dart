import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/chat/widgets/context_threshold_sheet.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/omni_glass.dart';

void main() {
  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
  });
  tearDown(LegacyTextLocalizer.clearResolvedLocale);

  for (final brightness in Brightness.values) {
    for (final glass in [false, true]) {
      testWidgets(
        'threshold pane covers gesture area and avoids IME once, $brightness glass=$glass',
        (tester) async {
          _viewport(tester);
          await _open(tester, brightness: brightness, glass: glass);
          final pane = find.byType(OmniGlassSheet);
          final scroll = find.byType(SingleChildScrollView);
          expect(tester.getRect(pane).bottom, 844);
          expect(tester.getRect(scroll).bottom, 820);
          expect(
            find.byType(LiquidGlass),
            glass ? findsOneWidget : findsNothing,
          );
          expect(tester.takeException(), isNull);

          await tester.tap(find.byType(TextField));
          tester.view.viewInsets = const FakeViewPadding(bottom: 300);
          tester.view.padding = FakeViewPadding.zero;
          await tester.pumpAndSettle();
          expect(tester.getRect(pane).bottom, 544);
          expect(tester.getRect(scroll).bottom, 544);
          await tester.ensureVisible(find.byType(TextField));
          await tester.pumpAndSettle();
          expect(
            tester.getRect(find.byType(TextField)).bottom,
            lessThanOrEqualTo(544),
          );
          expect(
            MediaQuery.of(tester.element(find.byType(TextField)))
                .viewInsets
                .bottom,
            0,
          );

          tester.view.viewInsets = FakeViewPadding.zero;
          tester.view.padding = const FakeViewPadding(bottom: 24);
          await tester.pumpAndSettle();
          expect(tester.getRect(pane).bottom, 844);
          expect(tester.getRect(scroll).bottom, 820);
          expect(tester.takeException(), isNull);
        },
      );
    }
  }

  testWidgets('barrier dismissal flushes the pending threshold auto-save', (
    tester,
  ) async {
    _viewport(tester);
    final saved = <int>[];
    await _open(
      tester,
      onSaved: (value) async {
        saved.add(value);
        return true;
      },
    );
    await tester.enterText(find.byType(TextField), '256000');
    expect(saved, isEmpty);
    final pane = tester.getRect(find.byType(OmniGlassSheet));
    await tester.tapAt(Offset(16, pane.top - 16));
    await tester.pumpAndSettle();
    expect(saved, [256000]);
    expect(find.byType(ContextThresholdSheet), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('small viewport keeps the form scrollable and auto-saves edits', (
    tester,
  ) async {
    _viewport(tester, size: const Size(320, 568));
    final saved = <int>[];
    await _open(
      tester,
      textScale: 1.6,
      onSaved: (value) async {
        saved.add(value);
        return true;
      },
    );
    final pane = find.byType(OmniGlassSheet);
    final scroll = find.byType(SingleChildScrollView);
    expect(tester.getRect(pane).bottom, 568);
    expect(tester.getRect(scroll).bottom, 544);
    await tester.ensureVisible(find.byType(TextField));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '64000');
    await tester.pump(const Duration(milliseconds: 321));
    await tester.pumpAndSettle();
    expect(saved, [64000]);
    expect(find.text('已自动保存'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}

void _viewport(WidgetTester tester, {Size size = const Size(390, 844)}) {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  tester.view.viewPadding = const FakeViewPadding(bottom: 24);
  tester.view.padding = const FakeViewPadding(bottom: 24);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(tester.view.resetViewPadding);
  addTearDown(tester.view.resetPadding);
  addTearDown(tester.view.resetViewInsets);
}

Future<void> _open(
  WidgetTester tester, {
  Brightness brightness = Brightness.light,
  bool glass = true,
  double textScale = 1,
  Future<bool> Function(int)? onSaved,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: brightness == Brightness.light
          ? AppTheme.lightTheme
          : AppTheme.darkTheme,
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
            onPressed: () => showOmniGlassBottomSheet<void>(
              context: context,
              isScrollControlled: true,
              backgroundColor: Colors.transparent,
              builder: (_) => ContextThresholdSheet(
                initialThreshold: 128000,
                currentUsageTokens: 22717,
                onThresholdSaved: onSaved ?? (_) async => true,
              ),
            ),
            child: const Text('Open threshold'),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('Open threshold'));
  await tester.pumpAndSettle();
}
