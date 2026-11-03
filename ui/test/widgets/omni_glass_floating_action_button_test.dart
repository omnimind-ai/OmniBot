import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/omni_glass_floating_action_button.dart';

Widget _app({
  required bool glass,
  required LiquidGlassQuality quality,
  required VoidCallback onPressed,
}) => MaterialApp(
  home: LiquidGlassScope(
    enabled: glass,
    quality: quality,
    child: LiquidGlassHost(
      enabled: glass,
      child: Scaffold(
        floatingActionButton: SizedBox.square(
          dimension: 52,
          child: OmniGlassFloatingActionButton(
            builder: (context, enabled) => FloatingActionButton(
              key: const ValueKey('existing-fab'),
              heroTag: 'original-hero',
              tooltip: 'New conversation',
              shape: const CircleBorder(),
              backgroundColor: enabled ? Colors.transparent : Colors.blue,
              onPressed: onPressed,
              child: const Icon(Icons.add, size: 26),
            ),
          ),
        ),
      ),
    ),
  ),
);

void main() {
  for (final quality in LiquidGlassQuality.values) {
    testWidgets('$quality keeps original FAB ownership across glass toggles', (
      tester,
    ) async {
      var actions = 0;
      Widget app(bool enabled) =>
          _app(glass: enabled, quality: quality, onPressed: () => actions++);
      await tester.pumpWidget(app(false));
      final fab = find.byKey(const ValueKey('existing-fab'));
      final element = tester.element(fab);
      final heroElement = tester.element(find.byType(Hero));
      expect(tester.getSize(fab), const Size.square(52));
      expect(
        tester.widget<FloatingActionButton>(fab).backgroundColor,
        Colors.blue,
      );
      await tester.pumpWidget(app(true));
      await tester.pumpAndSettle();
      expect(tester.element(fab), same(element));
      expect(tester.element(find.byType(Hero)), same(heroElement));
      expect(tester.widget<FloatingActionButton>(fab).heroTag, 'original-hero');
      expect(
        tester.widget<FloatingActionButton>(fab).tooltip,
        'New conversation',
      );
      expect(tester.getSize(fab), const Size.square(52));
      expect(LiquidGlassHost.debugHeldCaptures, 1);
      final surface = tester.widget<LiquidGlass>(find.byType(LiquidGlass));
      expect(
        (surface.clipper as ShapeBorderClipper).shape,
        const CircleBorder(),
      );
      final touch = await tester.startGesture(tester.getCenter(fab));
      await tester.pump(const Duration(milliseconds: 60));
      expect(
        tester.widget<LiquidGlass>(find.byType(LiquidGlass)).pressed,
        isTrue,
      );
      expect(actions, 0);
      await touch.up();
      await tester.pumpAndSettle();
      expect(actions, 1);
      expect(
        tester.widget<LiquidGlass>(find.byType(LiquidGlass)).pressed,
        isFalse,
      );
      final cancelled = await tester.startGesture(tester.getCenter(fab));
      await tester.pump();
      await cancelled.cancel();
      await tester.pumpAndSettle();
      expect(actions, 1);
      expect(
        tester.widget<LiquidGlass>(find.byType(LiquidGlass)).pressed,
        isFalse,
      );
      await tester.pumpWidget(app(false));
      await tester.pumpAndSettle();
      expect(tester.element(fab), same(element));
      expect(tester.element(find.byType(Hero)), same(heroElement));
      expect(
        tester.widget<FloatingActionButton>(fab).backgroundColor,
        Colors.blue,
      );
      expect(LiquidGlassHost.debugHeldCaptures, 0);
      expect(actions, 1);
      expect(tester.takeException(), isNull);
    });
  }
}
