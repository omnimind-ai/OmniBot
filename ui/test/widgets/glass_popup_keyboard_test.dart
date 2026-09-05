import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/glass_popup.dart';

void main() {
  testWidgets('anchored card remains above a newly opened keyboard', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(400, 800));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    Future<void> show(double keyboard) => tester.pumpWidget(
      MaterialApp(
        home: MediaQuery(
          data: MediaQueryData(
            size: const Size(400, 800),
            viewInsets: EdgeInsets.only(bottom: keyboard),
          ),
          child: const Stack(
            children: [
              GlassPopupOverlayContent(
                anchor: Rect.fromLTWH(300, 700, 28, 28),
                preferBelow: false,
                child: SizedBox(key: ValueKey('card'), width: 280, height: 350),
              ),
            ],
          ),
        ),
      ),
    );
    await show(0);
    await tester.pumpAndSettle();
    expect(
      tester.getRect(find.byKey(const ValueKey('card'))).bottom,
      lessThan(700),
    );
    await show(300);
    await tester.pumpAndSettle();
    expect(
      tester.getRect(find.byKey(const ValueKey('card'))).bottom,
      lessThanOrEqualTo(492),
    );
  });
}
