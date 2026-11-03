import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/widgets/conversation_context_menu.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/flclash/popup.dart';

import 'conversation_context_menu_test.dart'
    show
        ContextMenuTestHarness,
        contextMenuPanel,
        contextMenuSource,
        setContextMenuScreen;

void main() {
  for (final dark in [false, true]) {
    for (final quality in <LiquidGlassQuality?>[
      null,
      ...LiquidGlassQuality.values,
    ]) {
      testWidgets(
        'one independent surface, no copied source: $quality dark=$dark',
        (tester) async {
          await setContextMenuScreen(tester, const Size(390, 844));
          final harness = ContextMenuTestHarness();
          await tester.pumpWidget(
            harness.app(
              glass: quality != null,
              quality: quality ?? LiquidGlassQuality.standard,
              dark: dark,
            ),
          );
          harness.open();
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 100));
          expect(tester.widget<Opacity>(contextMenuSource).opacity, 1);
          expect(LiquidGlassHost.debugHeldCaptures, quality == null ? 0 : 1);
          await tester.pumpAndSettle();
          expect(LiquidGlassHost.debugHeldCaptures, quality == null ? 0 : 1);
          if (quality != null) {
            expect(LiquidGlassHost.debugLastCapture!.panes, hasLength(1));
            expect(
              find.descendant(
                of: contextMenuPanel,
                matching: find.byType(LiquidGlass),
              ),
              findsNothing,
              reason: 'The shared transition owns the only glass surface',
            );
          }
          expect(
            find.byKey(const ValueKey('conversation-context-preview')),
            findsNothing,
          );
          expect(find.text('Original conversation'), findsOneWidget);
          expect(find.byType(FlClashPopupSource), findsNothing);
          expect(harness.route.barrierColor, isNull);

          await tester.tap(find.text('Rename'));
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 100));
          expect(find.byType(ConversationContextMenu), findsOneWidget);
          expect(harness.appliedAction, isNull);
          expect(harness.completions, 0);
          expect(tester.widget<Opacity>(contextMenuSource).opacity, 1);
          expect(LiquidGlassHost.debugHeldCaptures, quality == null ? 0 : 1);
          // Neither an outside tap nor a second item can commit another action.
          await tester.tapAt(const Offset(4, 4));
          await tester.tap(find.text('Delete'), warnIfMissed: false);
          await tester.pumpAndSettle();
          await harness.completion;
          expect(harness.appliedAction, 'Rename');
          expect(harness.actionTaps, 1);
          expect(harness.backgroundTaps, 0);
          expect(harness.dismissals, 0);
          expect(LiquidGlassHost.debugHeldCaptures, 0);
          expect(find.byType(ConversationContextMenu), findsNothing);
          expect(tester.widget<Opacity>(contextMenuSource).opacity, 1);
          expect(tester.takeException(), isNull);
        },
      );
    }
  }

  for (final quality in LiquidGlassQuality.values) {
    testWidgets(
      'interrupted morph closes continuously and releases its one pane: $quality',
      (tester) async {
        final harness = ContextMenuTestHarness();
        await tester.pumpWidget(harness.app(glass: true, quality: quality));
        for (final milliseconds in [45, 150, 451]) {
          harness.open();
          await tester.pump();
          await tester.pump(Duration(milliseconds: milliseconds));
          expect(LiquidGlassHost.debugHeldCaptures, 1);
          final openingPane =
              LiquidGlassHost.debugLastCapture!.panes.single.pane;
          final from = harness.route.animation!.value;
          harness.dismiss();
          await tester.pump();
          expect(harness.route.animation!.value, from);
          expect(
            LiquidGlassHost.debugLastCapture!.panes.single.pane,
            openingPane,
            reason:
                'The closing morph must begin from the currently painted pane',
          );
          expect(
            tester
                .widgetList<ClipRect>(
                  find.ancestor(
                    of: contextMenuPanel,
                    matching: find.byType(ClipRect),
                  ),
                )
                .where((clip) => clip.clipper != null),
            isEmpty,
            reason: 'A second directional clip must not slice the shared rounded morph',
          );
          await tester.pump(const Duration(milliseconds: 199));
          expect(harness.route.animation!.status, AnimationStatus.reverse);
          expect(harness.route.animation!.value, greaterThan(0));
          expect(tester.widget<Opacity>(contextMenuSource).opacity, 1);
          await tester.pump(const Duration(milliseconds: 2));
          await tester.pumpAndSettle();
          await harness.completion;
          expect(LiquidGlassHost.debugHeldCaptures, 0);
          expect(find.byType(ConversationContextMenu), findsNothing);
        }
        expect(tester.takeException(), isNull);
      },
    );

    testWidgets(
      'glass padding consumes a tap without dismissing the pane: $quality',
      (tester) async {
        final harness = ContextMenuTestHarness();
        await tester.pumpWidget(harness.app(glass: true, quality: quality));
        harness.open();
        await tester.pumpAndSettle();
        final panel = tester.getRect(contextMenuPanel);
        await tester.tapAt(panel.topCenter + const Offset(0, 4));
        await tester.pumpAndSettle();
        expect(find.byType(ConversationContextMenu), findsOneWidget);
        expect(harness.dismissals, 0);
        expect(LiquidGlassHost.debugHeldCaptures, 1);
        harness.dismiss();
        await tester.pumpAndSettle();
        expect(LiquidGlassHost.debugHeldCaptures, 0);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets(
    'pop before first frame and rapid reopen leave no orphaned glass',
    (tester) async {
      final harness = ContextMenuTestHarness();
      await tester.pumpWidget(harness.app(glass: true));
      harness.open();
      harness.navigator.currentState!.pop();
      await tester.pumpAndSettle();
      await harness.completion;
      expect(LiquidGlassHost.debugHeldCaptures, 0);
      harness.open();
      await tester.pumpAndSettle();
      expect(LiquidGlassHost.debugHeldCaptures, 1);
      expect(tester.widget<Opacity>(contextMenuSource).opacity, 1);
      harness.dismiss();
      await tester.pumpAndSettle();
      expect(LiquidGlassHost.debugHeldCaptures, 0);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('reduced motion renders one complete pane and releases it', (
    tester,
  ) async {
    final harness = ContextMenuTestHarness();
    await tester.pumpWidget(harness.app(glass: true, reducedMotion: true));
    harness.open(reducedMotion: true);
    await tester.pumpAndSettle();
    expect(LiquidGlassHost.debugHeldCaptures, 1);
    expect(tester.widget<Opacity>(contextMenuSource).opacity, 1);
    await tester.tap(find.text('Pin'));
    await tester.pumpAndSettle();
    await harness.completion;
    expect(harness.appliedAction, 'Pin');
    expect(LiquidGlassHost.debugHeldCaptures, 0);
    expect(tester.takeException(), isNull);
  });
}
