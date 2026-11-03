import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/omni_circle_button.dart';

Future<Color> _pixelAt(
  WidgetTester tester,
  GlobalKey snapshot,
  Offset position,
) async => (await tester.runAsync(() async {
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
}))!;

void main() {
  for (final height in [64.0, 48.0]) {
    for (final side in ['leading', 'trailing']) {
      testWidgets(
        '$side elastic control paints below a $height toolbar over opaque body',
        (tester) async {
          tester.view.physicalSize = const Size(400, 320);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);

          final snapshot = GlobalKey();
          const bodyKey = ValueKey('opaque-page-body');
          const glyphKey = ValueKey('elastic-control-paint');
          var activations = 0;
          final buttonSize = height < 56 ? 36.0 : 40.0;
          final button = OmniCircleButton(
            icon: Icons.add,
            tooltip: 'Header action',
            size: buttonSize,
            onPressed: () => activations++,
            // A solid glyph lets the pixel assertion distinguish the control
            // from the opaque body without depending on glass shader output.
            iconWidget: SizedBox.square(
              key: glyphKey,
              dimension: buttonSize,
              child: const DecoratedBox(
                decoration: BoxDecoration(
                  color: Colors.red,
                  shape: BoxShape.circle,
                ),
              ),
            ),
          );
          await tester.pumpWidget(
            MaterialApp(
              builder: (context, child) => MediaQuery(
                data: MediaQuery.of(context).copyWith(
                  padding: const EdgeInsets.only(top: 24),
                  viewPadding: const EdgeInsets.only(top: 24),
                ),
                child: LiquidGlassScope(enabled: true, child: child!),
              ),
              home: RepaintBoundary(
                key: snapshot,
                child: Scaffold(
                  appBar: CommonAppBar(
                    height: height,
                    primary: true,
                    showLeading: false,
                    leading: side == 'leading' ? Center(child: button) : null,
                    actions: side == 'trailing'
                        ? [SizedBox(width: 72, child: Center(child: button))]
                        : null,
                  ),
                  body: const SizedBox.expand(
                    key: bodyKey,
                    child: ColoredBox(color: Colors.blue),
                  ),
                ),
              ),
            ),
          );
          await tester.pumpAndSettle();
          final glyph = find.byKey(glyphKey);
          final restingRect = tester.getRect(glyph);
          final bodyTop = tester.getTopLeft(find.byKey(bodyKey)).dy;
          expect(bodyTop, height + 24);
          final probe = Offset(restingRect.center.dx, bodyTop + 1);
          expect(
            await _pixelAt(tester, snapshot, probe),
            const Color(0xFF2196F3),
          );

          final gesture = await tester.startGesture(restingRect.center);
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 800));
          await gesture.moveBy(const Offset(0, 160));
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 350));
          expect(tester.getRect(glyph), restingRect);
          expect(
            await _pixelAt(tester, snapshot, probe),
            const Color(0xFFF44336),
            reason:
                'The stretched control must remain visible beyond the '
                'toolbar boundary, above the opaque page body.',
          );

          await gesture.up();
          await tester.pumpAndSettle();
          expect(activations, 0);
          expect(tester.getRect(glyph), restingRect);
          expect(
            await _pixelAt(tester, snapshot, probe),
            const Color(0xFF2196F3),
          );
          await tester.tap(glyph);
          await tester.pumpAndSettle();
          expect(activations, 1);
          expect(tester.takeException(), isNull);
        },
      );
    }
  }
}
