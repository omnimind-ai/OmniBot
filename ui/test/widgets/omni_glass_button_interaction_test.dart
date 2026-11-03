import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/flclash/elastic_press.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/omni_circle_button.dart';
import 'package:ui/widgets/omni_glass_floating_action_button.dart';

Widget _app(Widget button, {bool glass = true, bool reducedMotion = false}) =>
    MaterialApp(
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(disableAnimations: reducedMotion),
        child: LiquidGlassScope(
          enabled: glass,
          child: LiquidGlassHost(enabled: glass, child: child!),
        ),
      ),
      home: Scaffold(body: Center(child: button)),
    );

Rect _painted(WidgetTester tester) => FlClashElasticPress.paintBoundsOf(
  tester.renderObject<RenderBox>(find.byIcon(Icons.add)),
);

void main() {
  for (final kind in ['circle', 'popup circle', 'fab']) {
    testWidgets(
      '$kind stretches without changing layout or activating a drag',
      (tester) async {
        var activations = 0;
        final button = switch (kind) {
          'circle' => OmniCircleButton(
            icon: Icons.add,
            tooltip: 'Add',
            size: 40,
            tapTargetSize: MaterialTapTargetSize.padded,
            onPressed: () => activations++,
          ),
          'popup circle' => OmniCircleButton.popup(
            icon: Icons.add,
            tooltip: 'Add',
            size: 40,
            tapTargetSize: MaterialTapTargetSize.padded,
            onOpen: (_) => activations++,
          ),
          _ => SizedBox.square(
            dimension: 52,
            child: OmniGlassFloatingActionButton(
              builder: (context, glass) => FloatingActionButton(
                tooltip: 'Add',
                backgroundColor: glass ? Colors.transparent : Colors.blue,
                onPressed: () => activations++,
                child: const Icon(Icons.add),
              ),
            ),
          ),
        };
        await tester.pumpWidget(_app(button));
        await tester.pumpAndSettle();
        final icon = find.byIcon(Icons.add);
        final layout = tester.getRect(icon);
        final rest = _painted(tester);
        if (kind != 'fab') {
          final style = tester
              .widget<IconButton>(find.byType(IconButton))
              .style!;
          expect(
            style.overlayColor?.resolve({WidgetState.pressed}),
            Colors.transparent,
            reason: 'Elastic glass must not acquire an opaque ink fill',
          );
        }
        final gesture = await tester.startGesture(layout.center);
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 800));
        expect(_painted(tester).width, greaterThan(rest.width));
        expect(tester.getRect(icon), layout);
        expect(find.text('Add'), findsNothing);
        await gesture.moveBy(const Offset(100, 50));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 250));
        expect(_painted(tester).center.dx, greaterThan(rest.center.dx));
        expect(tester.getRect(icon), layout);
        await gesture.up();
        await tester.pumpAndSettle();
        expect(activations, 0);
        expect(_painted(tester).left, closeTo(rest.left, .01));
        expect(_painted(tester).width, closeTo(rest.width, .01));
        await tester.tap(icon);
        await tester.pumpAndSettle();
        expect(activations, 1);
        expect(tester.takeException(), isNull);
      },
    );
  }

  for (final mode in ['glass off', 'reduced motion', 'disabled']) {
    testWidgets('$mode leaves circle geometry still during a press', (
      tester,
    ) async {
      await tester.pumpWidget(
        _app(
          OmniCircleButton(
            icon: Icons.add,
            tooltip: 'Add',
            onPressed: mode == 'disabled' ? null : () {},
          ),
          glass: mode != 'glass off',
          reducedMotion: mode == 'reduced motion',
        ),
      );
      final rest = _painted(tester);
      final gesture = await tester.startGesture(
        tester.getCenter(find.byIcon(Icons.add)),
      );
      await tester.pump(const Duration(milliseconds: 350));
      await gesture.moveBy(const Offset(80, 40));
      await tester.pump(const Duration(milliseconds: 250));
      expect(_painted(tester), rest);
      await gesture.cancel();
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('circle keeps keyboard activation and cancellation ownership', (
    tester,
  ) async {
    var activations = 0;
    await tester.pumpWidget(
      _app(
        OmniCircleButton(
          icon: Icons.add,
          tooltip: 'Add',
          onPressed: () => activations++,
        ),
      ),
    );
    final gesture = await tester.startGesture(
      tester.getCenter(find.byIcon(Icons.add)),
    );
    await tester.pump(const Duration(milliseconds: 200));
    await gesture.cancel();
    await tester.pumpAndSettle();
    expect(activations, 0);
    await tester.sendKeyEvent(LogicalKeyboardKey.tab);
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pumpAndSettle();
    expect(activations, 1);
  });
}
