import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/omni_segmented_slider.dart';

const _options = [
  OmniSegmentedOption(value: 0, label: '全部', icon: Icons.apps),
  OmniSegmentedOption(value: 1, label: '可用'),
  OmniSegmentedOption(value: 2, label: '不可用'),
];

Widget _scene({
  required ValueNotifier<int?> value,
  required List<int> changes,
  bool glass = false,
  bool enabled = true,
  bool reducedMotion = false,
  TextDirection direction = TextDirection.ltr,
  double? position,
}) => MaterialApp(
  home: MediaQuery(
    data: MediaQueryData(disableAnimations: reducedMotion),
    child: Directionality(
      textDirection: direction,
      child: LiquidGlassScope(
        enabled: glass,
        child: LiquidGlassHost(
          enabled: glass,
          child: Scaffold(
            body: Center(
              child: SizedBox(
                width: 306,
                child: ValueListenableBuilder<int?>(
                  valueListenable: value,
                  builder: (context, selected, child) =>
                      OmniSegmentedSlider<int>(
                        value: selected,
                        position: position,
                        keyPrefix: 'segment',
                        options: _options,
                        onChanged: enabled
                            ? (next) {
                                changes.add(next);
                                value.value = next;
                              }
                            : null,
                      ),
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  ),
);

Finder _option(int index) => find.byKey(ValueKey('segment-$index'));
Finder get _thumb => find.byKey(const ValueKey('segment-thumb'));

void main() {
  testWidgets('drag follows the finger and commits only its final value', (
    tester,
  ) async {
    final value = ValueNotifier(0);
    final changes = <int>[];
    await tester.pumpWidget(_scene(value: value, changes: changes));
    final initialLeft = tester.getTopLeft(_thumb).dx;
    final gesture = await tester.startGesture(tester.getCenter(_option(0)));
    await gesture.moveBy(const Offset(50, 0));
    await tester.pump();
    expect(tester.getTopLeft(_thumb).dx, closeTo(initialLeft + 50, 0.1));
    expect(changes, isEmpty);
    await gesture.moveBy(const Offset(150, 0));
    await tester.pump();
    expect(changes, isEmpty);
    await gesture.up();
    await tester.pumpAndSettle();
    expect(changes, [2]);
    expect(value.value, 2);
    expect(
      tester.getCenter(_thumb).dx,
      closeTo(tester.getCenter(_option(2)).dx, 0.1),
    );
  });

  testWidgets('cancel restores the owned value without a callback', (
    tester,
  ) async {
    final value = ValueNotifier(0);
    final changes = <int>[];
    await tester.pumpWidget(
      _scene(value: value, changes: changes, glass: true),
    );
    await tester.pumpAndSettle();
    final initialLeft = tester.getTopLeft(_thumb).dx;
    final gesture = await tester.startGesture(tester.getCenter(_option(0)));
    await gesture.moveBy(const Offset(170, 0));
    await tester.pump(const Duration(milliseconds: 50));
    expect(tester.getTopLeft(_thumb).dx, greaterThan(initialLeft));
    await gesture.cancel();
    await tester.pumpAndSettle();
    expect(changes, isEmpty);
    expect(value.value, 0);
    expect(tester.getTopLeft(_thumb).dx, closeTo(initialLeft, 0.1));
    expect(tester.takeException(), isNull);
  });

  testWidgets('a value update during drag cancels its obsolete preview', (
    tester,
  ) async {
    final value = ValueNotifier(0);
    final changes = <int>[];
    await tester.pumpWidget(_scene(value: value, changes: changes));
    final gesture = await tester.startGesture(tester.getCenter(_option(0)));
    await gesture.moveBy(const Offset(170, 0));
    await tester.pump();
    value.value = 1;
    await tester.pump();
    await gesture.moveBy(const Offset(40, 0));
    await gesture.up();
    await tester.pumpAndSettle();
    expect(changes, isEmpty);
    expect(
      tester.getCenter(_thumb).dx,
      closeTo(tester.getCenter(_option(1)).dx, 0.1),
    );
  });

  testWidgets('vertical scroll does not select a segment', (tester) async {
    final value = ValueNotifier(0);
    final changes = <int>[];
    await tester.pumpWidget(_scene(value: value, changes: changes));
    await tester.drag(_option(1), const Offset(0, 100));
    await tester.pumpAndSettle();
    expect(changes, isEmpty);
    expect(value.value, 0);
  });

  testWidgets('tap retains callbacks, haptics, and no pressed ink', (
    tester,
  ) async {
    final value = ValueNotifier(0);
    final changes = <int>[];
    final haptics = <Object?>[];
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'HapticFeedback.vibrate') {
          haptics.add(call.arguments);
        }
        return null;
      },
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      ),
    );
    await tester.pumpWidget(_scene(value: value, changes: changes));
    final ink = tester.widget<InkWell>(_option(1));
    expect(ink.splashFactory, same(NoSplash.splashFactory));
    expect(ink.highlightColor, Colors.transparent);
    await tester.tap(_option(1));
    await tester.pumpAndSettle();
    await tester.tap(_option(1));
    await tester.pumpAndSettle();
    expect(changes, [1, 1]);
    expect(haptics, ['HapticFeedbackType.selectionClick']);
  });

  testWidgets(
    'keyboard arrows move selection and retain focus; enter activates',
    (tester) async {
      final value = ValueNotifier(0);
      final changes = <int>[];
      await tester.pumpWidget(_scene(value: value, changes: changes));
      tester.widget<InkWell>(_option(0)).focusNode!.requestFocus();
      await tester.pump();
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
      await tester.pumpAndSettle();
      expect(value.value, 1);
      expect(
        tester.widget<InkWell>(_option(1)).focusNode!.hasPrimaryFocus,
        isTrue,
      );
      await tester.sendKeyEvent(LogicalKeyboardKey.end);
      await tester.pumpAndSettle();
      expect(value.value, 2);
      await tester.sendKeyEvent(LogicalKeyboardKey.enter);
      await tester.pumpAndSettle();
      expect(changes, [1, 2, 2]);
      final ink = tester.widget<InkWell>(_option(2));
      expect(ink.focusColor, isNot(Colors.transparent));
    },
  );

  testWidgets(
    'right-to-left thumb and arrow direction match the option order',
    (tester) async {
      final value = ValueNotifier(0);
      final changes = <int>[];
      await tester.pumpWidget(
        _scene(value: value, changes: changes, direction: TextDirection.rtl),
      );
      expect(
        tester.getCenter(_thumb).dx,
        closeTo(tester.getCenter(_option(0)).dx, 0.1),
      );
      tester.widget<InkWell>(_option(0)).focusNode!.requestFocus();
      await tester.pump();
      await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
      await tester.pumpAndSettle();
      expect(value.value, 1);
      final gesture = await tester.startGesture(tester.getCenter(_option(1)));
      await gesture.moveBy(const Offset(-100, 0));
      await tester.pump();
      await gesture.up();
      await tester.pumpAndSettle();
      expect(changes, [1, 2]);
      expect(
        tester.getCenter(_thumb).dx,
        closeTo(tester.getCenter(_option(2)).dx, 0.1),
      );
    },
  );

  testWidgets(
    'disabled selection ignores tap and drag and exposes disabled state',
    (tester) async {
      final value = ValueNotifier(0);
      final changes = <int>[];
      await tester.pumpWidget(
        _scene(value: value, changes: changes, enabled: false),
      );
      await tester.tap(_option(1));
      await tester.drag(_option(0), const Offset(200, 0));
      await tester.pumpAndSettle();
      expect(changes, isEmpty);
      expect(tester.widget<InkWell>(_option(1)).onTap, isNull);
      final semantics = tester.widgetList<Semantics>(find.byType(Semantics));
      expect(
        semantics
            .where((item) => item.properties.inMutuallyExclusiveGroup == true)
            .every((item) => item.properties.enabled == false),
        isTrue,
      );
    },
  );

  testWidgets('disabling mid-drag cancels the preview and a later drag works', (
    tester,
  ) async {
    final value = ValueNotifier(0);
    final changes = <int>[];
    await tester.pumpWidget(
      _scene(value: value, changes: changes, glass: true),
    );
    await tester.pumpAndSettle();
    final gesture = await tester.startGesture(tester.getCenter(_option(0)));
    await gesture.moveBy(const Offset(170, 0));
    await tester.pump();
    await tester.pumpWidget(
      _scene(value: value, changes: changes, enabled: false, glass: true),
    );
    await gesture.up();
    await tester.pumpAndSettle();
    expect(changes, isEmpty);
    expect(
      tester.getCenter(_thumb).dx,
      closeTo(tester.getCenter(_option(0)).dx, 0.1),
    );
    await tester.pumpWidget(
      _scene(value: value, changes: changes, glass: true),
    );
    await tester.drag(_option(0), const Offset(200, 0));
    await tester.pumpAndSettle();
    expect(changes, [2]);
    expect(tester.takeException(), isNull);
  });

  testWidgets('unselected optional value has no thumb or selected semantics', (
    tester,
  ) async {
    final value = ValueNotifier<int?>(null);
    final changes = <int>[];
    await tester.pumpWidget(
      _scene(value: value, changes: changes, glass: true),
    );
    expect(_thumb, findsNothing);
    var segments = tester
        .widgetList<Semantics>(find.byType(Semantics))
        .where((item) => item.properties.inMutuallyExclusiveGroup == true);
    expect(segments, hasLength(3));
    expect(segments.every((item) => item.properties.button == true), isTrue);
    expect(segments.every((item) => item.properties.selected == false), isTrue);
    await tester.tap(_option(1));
    await tester.pumpAndSettle();
    expect(value.value, 1);
    expect(_thumb, findsOneWidget);
    value.value = null;
    await tester.pumpAndSettle();
    expect(_thumb, findsNothing);
    final cancelled = await tester.startGesture(tester.getCenter(_option(0)));
    await cancelled.moveBy(const Offset(100, 0));
    await tester.pump();
    await cancelled.cancel();
    await tester.pumpAndSettle();
    expect(_thumb, findsNothing);
    await tester.tap(_option(2));
    await tester.pumpAndSettle();
    expect(changes, [1, 2]);
    expect(tester.takeException(), isNull);
  });

  testWidgets('glass setting releases capture and preserves selection', (
    tester,
  ) async {
    final value = ValueNotifier(1);
    final changes = <int>[];
    await tester.pumpWidget(
      _scene(value: value, changes: changes, glass: true),
    );
    await tester.pumpAndSettle();
    expect(find.byType(LiquidGlass), findsOneWidget);
    expect(LiquidGlassHost.debugHeldCaptures, 1);
    final left = tester.getTopLeft(_thumb).dx;
    await tester.pumpWidget(_scene(value: value, changes: changes));
    await tester.pumpAndSettle();
    expect(find.byType(LiquidGlass), findsNothing);
    expect(LiquidGlassHost.debugHeldCaptures, 0);
    expect(tester.getTopLeft(_thumb).dx, left);
    await tester.tap(_option(2));
    await tester.pumpAndSettle();
    expect(value.value, 2);
  });

  testWidgets('reduced motion settles selection immediately', (tester) async {
    final value = ValueNotifier(0);
    final changes = <int>[];
    await tester.pumpWidget(
      _scene(value: value, changes: changes, glass: true, reducedMotion: true),
    );
    await tester.pumpAndSettle();
    await tester.tap(_option(2));
    await tester.pump();
    expect(
      tester.getCenter(_thumb).dx,
      closeTo(tester.getCenter(_option(2)).dx, 0.1),
    );
    await tester.pump();
    expect(tester.binding.hasScheduledFrame, isFalse);
  });

  testWidgets(
    'external fractional position follows immediately without committing',
    (tester) async {
      final value = ValueNotifier(0);
      final changes = <int>[];
      await tester.pumpWidget(
        _scene(value: value, changes: changes, position: 0.5),
      );
      final midpoint =
          (tester.getCenter(_option(0)).dx + tester.getCenter(_option(1)).dx) /
          2;
      expect(tester.getCenter(_thumb).dx, closeTo(midpoint, 0.1));
      await tester.pumpWidget(
        _scene(value: value, changes: changes, position: 1.5),
      );
      expect(
        tester.getCenter(_thumb).dx,
        closeTo(
          (tester.getCenter(_option(1)).dx + tester.getCenter(_option(2)).dx) /
              2,
          0.1,
        ),
      );
      expect(changes, isEmpty);
    },
  );
}
