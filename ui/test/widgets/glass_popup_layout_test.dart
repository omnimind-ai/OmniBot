import 'dart:ui' as ui;

import 'package:flutter/rendering.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/glass_popup.dart';
import 'package:ui/widgets/flclash/popup.dart';

const _panel = ValueKey('panel');
final _snapshot = GlobalKey();

Widget _app({
  required Rect anchor,
  bool preferBelow = true,
  bool overlapAnchor = true,
  double keyboard = 0,
  bool reduceMotion = false,
  TextDirection direction = TextDirection.ltr,
  GlobalKey<GlassPopupOverlayContentState>? popupKey,
}) => RepaintBoundary(
  key: _snapshot,
  child: MaterialApp(
    debugShowCheckedModeBanner: false,
    home: MediaQuery(
      data: MediaQueryData(
        size: const Size(400, 800),
        padding: const EdgeInsets.only(top: 24, bottom: 20),
        viewInsets: EdgeInsets.only(bottom: keyboard),
        disableAnimations: reduceMotion,
      ),
      child: Directionality(
        textDirection: direction,
        child: Stack(
          children: [
            GlassPopupOverlayContent(
              key: popupKey,
              anchor: anchor,
              preferBelow: preferBelow,
              overlapAnchor: overlapAnchor,
              child: const SizedBox(
                key: _panel,
                width: 220,
                height: 240,
                child: ColoredBox(color: Color(0xFFFF0000)),
              ),
            ),
          ],
        ),
      ),
    ),
  ),
);

Future<Rect?> _paintedContent(WidgetTester tester) async {
  return tester.runAsync<Rect?>(() async {
    final boundary =
        _snapshot.currentContext!.findRenderObject()! as RenderRepaintBoundary;
    final image = await boundary.toImage(pixelRatio: 1);
    final bytes = (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!;
    var left = image.width, top = image.height, right = -1, bottom = -1;
    for (var y = 0; y < image.height; y++) {
      for (var x = 0; x < image.width; x++) {
        final i = (y * image.width + x) * 4;
        if (bytes.getUint8(i) > bytes.getUint8(i + 1) + 30 &&
            bytes.getUint8(i) > bytes.getUint8(i + 2) + 30 &&
            bytes.getUint8(i + 3) > 20) {
          left = x < left ? x : left;
          right = x > right ? x : right;
          top = y < top ? y : top;
          bottom = y > bottom ? y : bottom;
        }
      }
    }
    image.dispose();
    return right < 0
        ? null
        : Rect.fromLTRB(
            left.toDouble(),
            top.toDouble(),
            right + 1.0,
            bottom + 1.0,
          );
  });
}

void main() {
  for (final direction in TextDirection.values) {
    testWidgets('covers corner triggers within safe bounds in $direction', (
      tester,
    ) async {
      await tester.binding.setSurfaceSize(const Size(400, 800));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      for (final anchor in [
        const Rect.fromLTWH(16, 40, 44, 44),
        const Rect.fromLTWH(340, 40, 44, 44),
        const Rect.fromLTWH(16, 700, 44, 44),
        const Rect.fromLTWH(340, 700, 44, 44),
      ]) {
        await tester.pumpWidget(_app(anchor: anchor, direction: direction));
        await tester.pumpAndSettle();
        final panel = tester.getRect(find.byKey(_panel));
        expect(panel.contains(anchor.topLeft), isTrue);
        expect(
          panel.contains(anchor.bottomRight - const Offset(0.1, 0.1)),
          isTrue,
        );
        expect(panel.left, greaterThanOrEqualTo(8));
        expect(panel.right, lessThanOrEqualTo(392));
        expect(panel.top, greaterThanOrEqualTo(32));
        expect(panel.bottom, lessThanOrEqualTo(772));
      }
    });
  }

  testWidgets(
    'keyboard keeps the visible trigger covered and panel reachable',
    (tester) async {
      await tester.binding.setSurfaceSize(const Size(400, 800));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      const anchor = Rect.fromLTWH(20, 400, 100, 44);
      await tester.pumpWidget(
        _app(anchor: anchor, preferBelow: false, keyboard: 300),
      );
      await tester.pumpAndSettle();
      final panel = tester.getRect(find.byKey(_panel));
      expect(panel.contains(anchor.center), isTrue);
      expect(panel.bottom, lessThanOrEqualTo(472));
    },
  );

  testWidgets('adjacent cards retain the gap above their trigger', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(400, 800));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    const anchor = Rect.fromLTWH(20, 400, 44, 44);
    await tester.pumpWidget(
      _app(anchor: anchor, preferBelow: false, overlapAnchor: false),
    );
    await tester.pumpAndSettle();
    expect(tester.getRect(find.byKey(_panel)).bottom, anchor.top - 6);
  });

  testWidgets('FlClash paint grows from the trigger without relayout', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(400, 800));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    final key = GlobalKey<GlassPopupOverlayContentState>();
    await tester.pumpWidget(
      _app(anchor: const Rect.fromLTWH(20, 40, 44, 44), popupKey: key),
    );
    await tester.pump();
    final panel = tester.getRect(find.byKey(_panel));
    expect(find.byType(FlClashPopupTransition), findsOneWidget);
    expect(await _paintedContent(tester), isNull);
    await tester.pump(const Duration(milliseconds: 60));
    final growing = (await _paintedContent(tester))!;
    expect(growing.width, greaterThan(20));
    expect(growing.width, lessThan(panel.width));
    expect(tester.getRect(find.byKey(_panel)), panel);
    await tester.pumpAndSettle();
    expect(await _paintedContent(tester), panel);
    final closed = key.currentState!.playReverse();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(tester.getRect(find.byKey(_panel)), panel);
    expect((await _paintedContent(tester))!.width, lessThan(panel.width));
    await tester.pumpAndSettle();
    await closed;
  });

  testWidgets(
    'interrupted reveal keeps its frame and full 200ms no-source close',
    (tester) async {
      await tester.binding.setSurfaceSize(const Size(400, 800));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      final key = GlobalKey<GlassPopupOverlayContentState>();
      await tester.pumpWidget(
        _app(anchor: const Rect.fromLTWH(20, 700, 40, 40), popupKey: key),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 80));
      final panel = tester.getRect(find.byKey(_panel));
      final before = await _paintedContent(tester);
      var finished = false;
      final closed = key.currentState!.playReverse().then(
        (_) => finished = true,
      );
      await tester.pump();
      expect(await _paintedContent(tester), before);
      await tester.pump(const Duration(milliseconds: 150));
      expect(finished, isFalse);
      expect(tester.getRect(find.byKey(_panel)), panel);
      await tester.pumpAndSettle();
      await closed;
      expect(finished, isTrue);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('reduced motion shows the complete panel immediately', (
    tester,
  ) async {
    await tester.pumpWidget(
      _app(anchor: const Rect.fromLTWH(20, 40, 44, 44), reduceMotion: true),
    );
    expect(find.byKey(_panel), findsOneWidget);
    expect(find.byType(ClipRRect), findsNothing);
  });
}
