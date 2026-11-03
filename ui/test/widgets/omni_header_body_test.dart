import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/omni_circle_button.dart';
import 'package:ui/widgets/omni_header_body.dart';

const _red = Color(0xFFFF0000);
const _blue = Color(0xFF0000FF);

Future<Color?> _pixel(
  GlobalKey snapshot,
  Offset position,
  WidgetTester tester,
) => tester.runAsync(() async {
  final boundary =
      snapshot.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  final image = await boundary.toImage(pixelRatio: 1);
  try {
    final bytes = (await image.toByteData(format: ui.ImageByteFormat.rawRgba))!;
    final index = (position.dy.floor() * image.width + position.dx.floor()) * 4;
    return Color.fromARGB(
      bytes.getUint8(index + 3),
      bytes.getUint8(index),
      bytes.getUint8(index + 1),
      bytes.getUint8(index + 2),
    );
  } finally {
    image.dispose();
  }
});

void main() {
  for (final edge in ['leading', 'trailing']) {
    testWidgets('$edge drag paints over an opaque body without moving layout', (
      tester,
    ) async {
      final snapshot = GlobalKey();
      final buttonKey = ValueKey(edge);
      const bodyKey = ValueKey('body');
      var activations = 0;
      await tester.pumpWidget(
        MaterialApp(
          builder: (context, child) => LiquidGlassScope(
            enabled: true,
            child: LiquidGlassHost(enabled: true, child: child!),
          ),
          home: RepaintBoundary(
            key: snapshot,
            child: Scaffold(
              body: OmniHeaderBody(
                header: SizedBox(
                  height: 58,
                  width: double.infinity,
                  child: Align(
                    alignment: edge == 'leading'
                        ? Alignment.centerLeft
                        : Alignment.centerRight,
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 16),
                      child: OmniCircleButton(
                        key: buttonKey,
                        icon: Icons.add,
                        iconSize: 40,
                        iconWidget: const SizedBox.square(
                          dimension: 40,
                          child: ColoredBox(color: _red),
                        ),
                        size: 40,
                        tapTargetSize: MaterialTapTargetSize.padded,
                        tooltip: 'Header action',
                        onPressed: () => activations++,
                      ),
                    ),
                  ),
                ),
                body: const SizedBox.expand(
                  child: ColoredBox(key: bodyKey, color: _blue),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final buttonRect = tester.getRect(find.byKey(buttonKey));
      final bodyRect = tester.getRect(find.byKey(bodyKey));
      final overlap = Offset(buttonRect.center.dx, bodyRect.top + 2);
      expect(await _pixel(snapshot, overlap, tester), _blue);

      final gesture = await tester.startGesture(buttonRect.center);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 800));
      await gesture.moveBy(const Offset(0, 160));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 250));

      expect(await _pixel(snapshot, overlap, tester), _red);
      expect(tester.getRect(find.byKey(buttonKey)), buttonRect);
      expect(tester.getRect(find.byKey(bodyKey)), bodyRect);
      await gesture.cancel();
      await tester.pumpAndSettle();
      expect(await _pixel(snapshot, overlap, tester), _blue);
      expect(activations, 0);
      await tester.tap(find.byKey(buttonKey));
      await tester.pumpAndSettle();
      expect(activations, 1);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('header rebuild retains body focus, draft and layout ownership', (
    tester,
  ) async {
    Widget app(double height) => MaterialApp(
      home: Scaffold(
        body: OmniHeaderBody(
          header: SizedBox(
            height: height,
            child: TextButton(onPressed: () {}, child: const Text('Header')),
          ),
          body: const TextField(key: ValueKey('draft')),
        ),
      ),
    );

    await tester.pumpWidget(app(64));
    await tester.enterText(find.byType(TextField), 'Keep this draft');
    final state = tester.state(find.byType(TextField));
    final editable = tester.widget<EditableText>(find.byType(EditableText));
    expect(editable.focusNode.hasFocus, isTrue);

    await tester.pumpWidget(app(80));
    expect(tester.getTopLeft(find.byType(TextField)).dy, 80);
    expect(tester.state(find.byType(TextField)), same(state));
    expect(find.text('Keep this draft'), findsOneWidget);
    expect(editable.focusNode.hasFocus, isTrue);
    expect(tester.takeException(), isNull);
  });
}
