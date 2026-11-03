import 'dart:ui' show SemanticsAction, SemanticsActionEvent;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/omni_slider.dart';

const _sliderKey = ValueKey('value-slider');

class _Harness extends StatefulWidget {
  const _Harness({
    super.key,
    this.enabled = true,
    this.acceptChanges = true,
    this.rtl = false,
    this.reducedMotion = false,
    this.scrollable = false,
    this.shape,
    this.onChanged,
    this.onChangeEnd,
  });

  final bool enabled;
  final bool acceptChanges;
  final bool rtl;
  final bool reducedMotion;
  final bool scrollable;
  final OmniSliderShape? shape;
  final ValueChanged<double>? onChanged;
  final ValueChanged<double>? onChangeEnd;

  @override
  State<_Harness> createState() => _HarnessState();
}

class _HarnessState extends State<_Harness> {
  double value = 10;

  @override
  Widget build(BuildContext context) {
    final slider = SizedBox(
      width: 300,
      child: OmniSlider(
        key: _sliderKey,
        value: value,
        min: 0,
        max: 24,
        shape: widget.shape,
        semanticLabel: 'Background blur',
        semanticFormatterCallback: (value) => value.toStringAsFixed(2),
        onChanged: widget.enabled
            ? (next) {
                widget.onChanged?.call(next);
                if (widget.acceptChanges) setState(() => value = next);
              }
            : null,
        onChangeEnd: widget.onChangeEnd,
      ),
    );
    return MaterialApp(
      home: MediaQuery(
        data: MediaQueryData(disableAnimations: widget.reducedMotion),
        child: Directionality(
          textDirection: widget.rtl ? TextDirection.rtl : TextDirection.ltr,
          child: Scaffold(
            body: widget.scrollable
                ? ListView(
                    children: [
                      const SizedBox(height: 150),
                      Center(child: slider),
                      const SizedBox(height: 1200),
                    ],
                  )
                : Center(child: slider),
          ),
        ),
      ),
    );
  }
}

double _value(WidgetTester tester) =>
    tester.widget<OmniSlider>(find.byKey(_sliderKey)).value;

class _RecordingShape extends OmniSliderShape {
  double activation = 0;

  @override
  double get height => 44;

  @override
  double get thumbInset => 2;

  @override
  void paint(
    Canvas canvas,
    Size size, {
    required double value,
    required double activation,
    required bool enabled,
    required int? divisions,
    required TextDirection textDirection,
  }) {
    this.activation = activation;
  }
}

void main() {
  testWidgets('taps use the original range and mirror with reading direction', (
    tester,
  ) async {
    for (final rtl in [false, true]) {
      await tester.pumpWidget(_Harness(key: ValueKey(rtl), rtl: rtl));
      final rect = tester.getRect(find.byKey(_sliderKey));
      expect(rect.height, 44);
      await tester.tapAt(Offset(rect.left + 2, rect.center.dy));
      await tester.pumpAndSettle();
      expect(_value(tester), rtl ? 24 : 0);
      await tester.tapAt(Offset(rect.right - 2, rect.center.dy));
      await tester.pumpAndSettle();
      expect(_value(tester), rtl ? 0 : 24);
    }
  });

  testWidgets('drag updates live, finishes once, and stays controlled', (
    tester,
  ) async {
    final changes = <double>[];
    final ends = <double>[];
    await tester.pumpWidget(
      _Harness(onChanged: changes.add, onChangeEnd: ends.add),
    );
    final rect = tester.getRect(find.byKey(_sliderKey));
    final gesture = await tester.startGesture(rect.center);
    await gesture.moveBy(const Offset(60, 0));
    await tester.pump();
    expect(_value(tester), greaterThan(10));
    expect(changes, isNotEmpty);
    expect(ends, isEmpty);
    await gesture.up();
    await tester.pumpAndSettle();
    expect(ends, hasLength(1));
    expect(ends.single, _value(tester));

    await tester.pumpWidget(
      const _Harness(key: ValueKey('rejected'), acceptChanges: false),
    );
    await tester.tapAt(Offset(rect.right - 2, rect.center.dy));
    await tester.pumpAndSettle();
    expect(_value(tester), 10);
  });

  testWidgets(
    'vertical scroll and pointer cancellation do not finish a change',
    (tester) async {
      final changes = <double>[];
      final ends = <double>[];
      await tester.pumpWidget(
        _Harness(
          scrollable: true,
          onChanged: changes.add,
          onChangeEnd: ends.add,
        ),
      );
      final center = tester.getCenter(find.byKey(_sliderKey));
      final scroll = await tester.startGesture(center);
      await tester.pump(const Duration(milliseconds: 150));
      await scroll.moveBy(const Offset(0, -100));
      await scroll.up();
      await tester.pumpAndSettle();
      expect(changes, isEmpty);
      expect(ends, isEmpty);

      final drag = await tester.startGesture(
        tester.getCenter(find.byKey(_sliderKey)),
      );
      await drag.moveBy(const Offset(60, 0));
      await tester.pump();
      expect(changes, isNotEmpty);
      await drag.cancel();
      await tester.pumpAndSettle();
      expect(ends, isEmpty);
    },
  );

  testWidgets('keyboard and accessibility adjust the same actual setting', (
    tester,
  ) async {
    final semantics = tester.ensureSemantics();
    await tester.pumpWidget(const _Harness(rtl: true));
    await tester.sendKeyEvent(LogicalKeyboardKey.tab);
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
    await tester.pumpAndSettle();
    expect(_value(tester), closeTo(12.4, 0.00001));
    final node = tester.getSemantics(find.byKey(_sliderKey));
    expect(node.getSemanticsData().label, 'Background blur');
    expect(node.getSemanticsData().value, '12.40');
    tester.binding.platformDispatcher.onSemanticsActionEvent!(
      SemanticsActionEvent(
        nodeId: node.id,
        viewId: tester.view.viewId,
        type: SemanticsAction.decrease,
      ),
    );
    await tester.pumpAndSettle();
    expect(_value(tester), closeTo(10, 0.00001));
    semantics.dispose();
  });

  testWidgets('disabled slider ignores pointer and accessibility adjustments', (
    tester,
  ) async {
    final semantics = tester.ensureSemantics();
    final changes = <double>[];
    await tester.pumpWidget(_Harness(enabled: false, onChanged: changes.add));
    await tester.tap(find.byKey(_sliderKey));
    await tester.drag(find.byKey(_sliderKey), const Offset(100, 0));
    await tester.pumpAndSettle();
    expect(changes, isEmpty);
    expect(_value(tester), 10);
    final data = tester.getSemantics(find.byKey(_sliderKey)).getSemanticsData();
    expect(data.hasAction(SemanticsAction.increase), isFalse);
    expect(data.hasAction(SemanticsAction.decrease), isFalse);
    semantics.dispose();
  });

  testWidgets('reduced motion settles press feedback immediately', (
    tester,
  ) async {
    final shape = _RecordingShape();
    await tester.pumpWidget(_Harness(reducedMotion: true, shape: shape));
    final gesture = await tester.startGesture(
      tester.getCenter(find.byKey(_sliderKey)),
    );
    await tester.pump(const Duration(milliseconds: 110));
    expect(shape.activation, 1);
    await gesture.cancel();
    await tester.pump();
    expect(shape.activation, 0);
  });
}
