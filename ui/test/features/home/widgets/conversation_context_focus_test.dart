import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/widgets/conversation_context_menu.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/flclash/popup.dart';

import 'conversation_context_menu_test.dart'
    show ContextMenuTestHarness, contextMenuPanel, setContextMenuScreen;

const _contentKey = ValueKey('focus-preview-content');

Finder get _previewContent => find.byKey(_contentKey);

Widget _preview() => Padding(
  key: _contentKey,
  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
  child: ConstrainedBox(
    constraints: const BoxConstraints(minHeight: 50),
    child: const Column(
      mainAxisSize: MainAxisSize.min,
      mainAxisAlignment: MainAxisAlignment.center,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Selected conversation',
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(fontSize: 16, height: 1.25),
        ),
        SizedBox(height: 4),
        Text(
          'Latest message',
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(fontSize: 14, height: 1.3),
        ),
      ],
    ),
  ),
);

// WidgetTester.getRect preserves the untransformed size. Measure the painted
// bounds so these assertions catch a preview snapping when its route reverses.
Rect _paintedPreview(WidgetTester tester) {
  final box = tester.renderObject<RenderBox>(_previewContent);
  return MatrixUtils.transformRect(
    box.getTransformTo(null),
    Offset.zero & box.size,
  );
}

double _scrimAlpha(WidgetTester tester) {
  final veil = find.byType(LiquidGlassVeil);
  if (veil.evaluate().isNotEmpty) {
    return tester.widget<LiquidGlassVeil>(veil).color.value!.a;
  }
  return tester
      .widget<ColoredBox>(
        find.descendant(
          of: find.byKey(const ValueKey('conversation-context-dismiss')),
          matching: find.byType(ColoredBox),
        ),
      )
      .color
      .a;
}

void main() {
  for (final (description, anchor, previewBounds, actionOrigin) in [
    (
      'normal row',
      const Rect.fromLTWH(16, 100, 358, 74),
      const Rect.fromLTWH(16, 92, 358, 74),
      const Offset(24, 180),
    ),
    (
      'bottom row',
      const Rect.fromLTWH(16, 700, 358, 74),
      const Rect.fromLTWH(16, 436, 358, 74),
      const Offset(24, 524),
    ),
    (
      'indented row',
      const Rect.fromLTWH(54, 100, 320, 74),
      const Rect.fromLTWH(46, 92, 320, 74),
      const Offset(46, 180),
    ),
  ]) {
    testWidgets('$description lifts above the unchanged action position', (
      tester,
    ) async {
      await setContextMenuScreen(tester, const Size(390, 844));
      final harness = ContextMenuTestHarness();
      await tester.pumpWidget(harness.app());
      harness.open(anchor: anchor, preview: _preview());
      await tester.pumpAndSettle();

      expect(_paintedPreview(tester), previewBounds);
      expect(tester.getRect(contextMenuPanel).topLeft, actionOrigin);
      expect(tester.getSize(contextMenuPanel).height, 280);
      expect(find.text('Selected conversation'), findsOneWidget);
      final transition = tester.widget<FlClashPopupTransition>(
        find.byType(FlClashPopupTransition),
      );
      expect(transition.anchor, const Offset(80, 130) & Size.zero);
      expect(transition.source, isNull);
      expect(transition.sourceImage, isNull);

      harness.dismiss();
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  }

  for (final dark in [false, true]) {
    testWidgets('focus veil stays mild in ${dark ? 'dark' : 'light'} mode', (
      tester,
    ) async {
      final harness = ContextMenuTestHarness();
      await tester.pumpWidget(harness.app(dark: dark));
      harness.open(preview: _preview());
      await tester.pump();
      expect(_scrimAlpha(tester), 0);
      await tester.pumpAndSettle();
      expect(_scrimAlpha(tester), closeTo(dark ? 0.24 : 0.12, 0.001));

      harness.dismiss();
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('preview inputs cannot receive focus or reach the original row', (
    tester,
  ) async {
    await setContextMenuScreen(tester, const Size(390, 844));
    final focus = FocusNode();
    addTearDown(focus.dispose);
    var inputTaps = 0;
    final harness = ContextMenuTestHarness();
    await tester.pumpWidget(harness.app());
    harness.open(
      preview: Material(
        type: MaterialType.transparency,
        child: SizedBox(
          key: _contentKey,
          height: 74,
          child: TextField(
            focusNode: focus,
            onTap: () => inputTaps++,
            decoration: const InputDecoration(hintText: 'Preview input'),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tapAt(tester.getCenter(find.byType(TextField)));
    await tester.pumpAndSettle();

    expect(inputTaps, 0);
    expect(focus.hasFocus, isFalse);
    expect(tester.testTextInput.hasAnyClients, isFalse);
    expect(harness.backgroundTaps, 0);
    expect(harness.actionTaps, 0);
    expect(harness.dismissals, 0);
    expect(harness.route.isCurrent, isTrue);
    harness.dismiss();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  for (final openMilliseconds in [60, 225, 451]) {
    testWidgets(
      'preview reverses without a scale or veil jump after $openMilliseconds ms',
      (tester) async {
        await setContextMenuScreen(tester, const Size(390, 844));
        final harness = ContextMenuTestHarness();
        await tester.pumpWidget(harness.app());
        const anchor = Rect.fromLTWH(16, 100, 358, 74);
        harness.open(anchor: anchor, preview: _preview());
        await tester.pump();
        // The preview keeps its source row width; only the actions are compact.
        expect(
          _paintedPreview(tester).width,
          closeTo(anchor.width * 0.97, 0.001),
        );
        await tester.pump(Duration(milliseconds: openMilliseconds));
        final openingBounds = _paintedPreview(tester);
        final openingVeil = _scrimAlpha(tester);
        expect(openingBounds.width, greaterThan(anchor.width * 0.97));
        expect(openingBounds.width, lessThanOrEqualTo(anchor.width));

        harness.dismiss();
        await tester.pump();
        expect(_paintedPreview(tester), openingBounds);
        expect(_scrimAlpha(tester), openingVeil);
        await tester.pump(const Duration(milliseconds: 100));
        expect(_paintedPreview(tester).width, lessThan(openingBounds.width));
        expect(_scrimAlpha(tester), lessThan(openingVeil));
        expect(harness.completions, 0);
        await tester.pump(const Duration(milliseconds: 99));
        expect(_previewContent, findsOneWidget);
        await tester.pump(const Duration(milliseconds: 2));
        await tester.pumpAndSettle();
        await harness.completion;
        expect(_previewContent, findsNothing);
        expect(harness.completions, 1);
        expect(tester.takeException(), isNull);
      },
    );
  }

  for (final quality in LiquidGlassQuality.values) {
    testWidgets(
      'both glass surfaces survive close until action commit: $quality',
      (tester) async {
        await setContextMenuScreen(tester, const Size(390, 844));
        final harness = ContextMenuTestHarness();
        await tester.pumpWidget(harness.app(glass: true, quality: quality));
        harness.open(preview: _preview());
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 100));
        expect(LiquidGlassHost.debugHeldCaptures, 2);
        await tester.pumpAndSettle();
        expect(LiquidGlassHost.debugLastCapture!.panes, hasLength(2));
        final previewBounds = _paintedPreview(tester);
        expect(
          LiquidGlassHost.debugLastCapture!.panes.where(
            (capture) => capture.pane.overlaps(previewBounds),
          ),
          hasLength(1),
          reason: 'The lifted row owns a separate pane above the action pane',
        );

        await tester.tap(find.text('Rename'));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 100));
        expect(LiquidGlassHost.debugHeldCaptures, 2);
        expect(_previewContent, findsOneWidget);
        expect(harness.appliedAction, isNull);
        expect(harness.completions, 0);
        await tester.tapAt(const Offset(4, 4));
        await tester.tap(find.text('Delete'), warnIfMissed: false);
        await tester.pumpAndSettle();
        await harness.completion;

        expect(harness.appliedAction, 'Rename');
        expect(harness.actionTaps, 1);
        expect(harness.completions, 1);
        expect(harness.backgroundTaps, 0);
        expect(LiquidGlassHost.debugHeldCaptures, 0);
        expect(_previewContent, findsNothing);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets(
    'keyboard and large text keep preview and last action reachable',
    (tester) async {
      await setContextMenuScreen(tester, const Size(320, 480));
      final harness = ContextMenuTestHarness();
      await tester.pumpWidget(
        harness.app(
          textScale: 1.8,
          padding: const EdgeInsets.only(top: 24, bottom: 12),
          viewInsets: const EdgeInsets.only(bottom: 180),
        ),
      );
      harness.open(
        anchor: const Rect.fromLTWH(16, 390, 288, 74),
        preview: _preview(),
      );
      await tester.pumpAndSettle();
      final preview = _paintedPreview(tester);
      final actions = tester.getRect(contextMenuPanel);
      expect(preview.top, greaterThanOrEqualTo(36));
      expect(preview.left, greaterThanOrEqualTo(16));
      expect(preview.right, lessThanOrEqualTo(304));
      expect(preview.bottom, lessThan(actions.top));
      expect(actions.left, greaterThanOrEqualTo(24));
      expect(actions.right, lessThanOrEqualTo(296));
      expect(actions.bottom, lessThanOrEqualTo(288));
      await tester.drag(find.text('Pin'), const Offset(0, -480));
      await tester.pumpAndSettle();
      expect(
        tester.getRect(find.text('Delete')).bottom,
        lessThanOrEqualTo(288),
      );
      await tester.tap(find.text('Delete'));
      await tester.pumpAndSettle();
      expect(harness.appliedAction, 'Delete');
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'reduced motion immediately shows both surfaces and releases them',
    (tester) async {
      await setContextMenuScreen(tester, const Size(390, 844));
      final harness = ContextMenuTestHarness();
      await tester.pumpWidget(harness.app(glass: true, reducedMotion: true));
      harness.open(preview: _preview(), reducedMotion: true);
      await tester.pumpAndSettle();
      expect(harness.route.transitionDuration, Duration.zero);
      expect(harness.route.reverseTransitionDuration, Duration.zero);
      expect(_paintedPreview(tester), const Rect.fromLTWH(16, 92, 358, 74));
      expect(_scrimAlpha(tester), closeTo(0.12, 0.001));
      expect(LiquidGlassHost.debugHeldCaptures, 2);

      await tester.tap(find.text('Pin'));
      await tester.pumpAndSettle();
      await harness.completion;
      expect(harness.appliedAction, 'Pin');
      expect(LiquidGlassHost.debugHeldCaptures, 0);
      expect(find.byType(ConversationContextMenu), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
}
