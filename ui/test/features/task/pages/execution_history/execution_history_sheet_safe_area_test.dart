import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/task/pages/execution_history/widgets/function_detail_sheet.dart';
import 'package:ui/features/task/pages/execution_history/widgets/run_log_timeline_components.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/omni_glass.dart';

void main() {
  for (final function in [true, false]) {
    final kind = function ? 'function' : 'run log step';
    for (final dark in [false, true]) {
      for (final glass in [false, true]) {
        testWidgets(
          '$kind sheet paints through gesture inset and still resizes/scrolls '
          '(dark=$dark, glass=$glass)',
          (tester) async {
            var replayed = 0;
            _viewport(tester);
            await _openSheet(
              tester,
              function: function,
              dark: dark,
              glass: glass,
              onReplay: () => replayed++,
            );

            final pane = find.byType(OmniGlassSheet);
            final original = tester.getRect(pane);
            expect(original.bottom, 844);
            _expectSafeContent(tester, bottom: 820);
            expect(
              find.byType(LiquidGlass),
              glass ? findsOneWidget : findsNothing,
            );
            final list = find.descendant(
              of: pane,
              matching: find.byType(ListView),
            );
            await tester.drag(list, const Offset(0, -100));
            await tester.pumpAndSettle();
            expect(
              tester.getRect(pane).height,
              greaterThan(original.height + 40),
            );
            expect(tester.getRect(pane).bottom, 844);
            _expectSafeContent(tester, bottom: 820);

            await tester.drag(list, const Offset(0, -500));
            await tester.pumpAndSettle();
            // A fresh gesture at maximum extent scrolls the attached list.
            await tester.drag(list, const Offset(0, -160));
            await tester.pumpAndSettle();
            final scroll = tester.state<ScrollableState>(
              find
                  .descendant(of: list, matching: find.byType(Scrollable))
                  .first,
            );
            expect(scroll.position.pixels, greaterThan(0));
            expect(tester.getRect(pane).bottom, 844);
            _expectSafeContent(tester, bottom: 820);

            if (function) {
              final run = find.byKey(const ValueKey('function-detail-run'));
              expect(run.hitTestable(), findsOneWidget);
              expect(tester.getRect(run).bottom, lessThanOrEqualTo(820));
              await tester.tap(run);
              await tester.pumpAndSettle();
              expect(replayed, 1);
            } else {
              final rect = tester.getRect(pane);
              await tester.tapAt(Offset(rect.center.dx, rect.top - 10));
              await tester.pumpAndSettle();
            }
            expect(pane, findsNothing);
            expect(tester.takeException(), isNull);
          },
        );
      }
    }

    testWidgets('$kind sheet respects tablet width and navigation inset', (
      tester,
    ) async {
      _viewport(tester, width: 1024, bottom: 48);
      await _openSheet(tester, function: function, dark: false, glass: true);
      final rect = tester.getRect(find.byType(OmniGlassSheet));
      expect(rect.bottom, 844);
      expect(rect.width, 640);
      expect(rect.center.dx, 512);
      _expectSafeContent(tester, bottom: 796);
      expect(tester.takeException(), isNull);
    });
  }
}

void _expectSafeContent(WidgetTester tester, {required double bottom}) {
  final safeArea = tester.widget<SafeArea>(
    find
        .descendant(
          of: find.byType(OmniGlassSheet),
          matching: find.byType(SafeArea),
        )
        .first,
  );
  expect(tester.getRect(find.byWidget(safeArea.child)).bottom, bottom);
}

void _viewport(WidgetTester tester, {double width = 390, double bottom = 24}) {
  tester.view.physicalSize = Size(width, 844);
  tester.view.devicePixelRatio = 1;
  tester.view.viewPadding = FakeViewPadding(bottom: bottom);
  tester.view.padding = FakeViewPadding(bottom: bottom);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(tester.view.resetViewPadding);
  addTearDown(tester.view.resetPadding);
}

Future<void> _openSheet(
  WidgetTester tester, {
  required bool function,
  required bool dark,
  required bool glass,
  VoidCallback? onReplay,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: dark ? AppTheme.darkTheme : AppTheme.lightTheme,
      builder: (context, child) => LiquidGlassScope(
        enabled: glass,
        child: LiquidGlassHost(enabled: glass, child: child!),
      ),
      home: Builder(
        builder: (context) => Scaffold(
          body: TextButton(
            onPressed: () => showOmniGlassBottomSheet<void>(
              context: context,
              isScrollControlled: true,
              backgroundColor: Colors.transparent,
              builder: (_) => function
                  ? FunctionDetailSheet(
                      initialFunction: {
                        'function_id': 'test-function',
                        'name': 'Saved command',
                        'description': 'Run the saved command',
                        'steps': List.generate(
                          40,
                          (i) => {
                            'tool': 'echo',
                            'args': {'message': 'Step $i'},
                          },
                        ),
                      },
                      refreshOnOpen: false,
                      loadFunction: (_) async => {},
                      onReplay: (_) => onReplay?.call(),
                      onEnhance: (_) {},
                      onDelete: (_) {},
                    )
                  : RunLogStepDetailSheet(
                      step: {
                        'action': {
                          'tool': 'echo',
                          'args': {
                            for (var i = 0; i < 80; i++) 'line $i': 'Output $i',
                          },
                        },
                      },
                      fallbackIndex: 0,
                      tokenUsage: null,
                      onShowState: (_, _) async {},
                    ),
            ),
            child: const Text('Open sheet'),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('Open sheet'));
  await tester.pumpAndSettle();
}
