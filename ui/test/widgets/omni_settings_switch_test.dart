import 'dart:ui' show SemanticsAction, SemanticsActionEvent;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/flclash/common_switch.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/settings_ui.dart';

const _switchKey = ValueKey('settings-switch');

class _SwitchHarness extends StatefulWidget {
  const _SwitchHarness({
    this.enabled = true,
    this.acceptChanges = true,
    this.glass = false,
    this.reducedMotion = false,
    this.direction = TextDirection.ltr,
    this.duration = const Duration(milliseconds: 300),
    this.row = false,
    this.onRowTap,
    this.onToggle,
  });

  final bool enabled;
  final bool acceptChanges;
  final bool glass;
  final bool reducedMotion;
  final TextDirection direction;
  final Duration duration;
  final bool row;
  final VoidCallback? onRowTap;
  final ValueChanged<bool>? onToggle;

  @override
  State<_SwitchHarness> createState() => _SwitchHarnessState();
}

class _SwitchHarnessState extends State<_SwitchHarness> {
  bool value = false;

  @override
  Widget build(BuildContext context) {
    final toggle = OmniSettingsSwitch(
      key: _switchKey,
      value: value,
      enabled: widget.enabled,
      duration: widget.duration,
      onToggle: (next) {
        widget.onToggle?.call(next);
        if (widget.acceptChanges) setState(() => value = next);
      },
    );
    return MaterialApp(
      home: MediaQuery(
        data: MediaQueryData(disableAnimations: widget.reducedMotion),
        child: Directionality(
          textDirection: widget.direction,
          child: LiquidGlassScope(
            enabled: widget.glass,
            child: LiquidGlassHost(
              enabled: widget.glass,
              child: Scaffold(
                body: Center(
                  child: widget.row
                      ? OmniSettingsTile(
                          tileKey: const ValueKey('settings-row'),
                          title: 'Enable setting',
                          translateText: false,
                          trailing: toggle,
                          onTap: widget.onRowTap,
                        )
                      : toggle,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

List<Positioned> _parts(WidgetTester tester) => tester
    .widget<Stack>(
      find
          .descendant(
            of: find.byType(CommonSwitch),
            matching: find.byType(Stack),
          )
          .first,
    )
    .children
    .whereType<Positioned>()
    .toList();

Rect _thumb(WidgetTester tester) {
  final thumb = _parts(tester)[1];
  return Rect.fromLTWH(thumb.left!, thumb.top!, thumb.width!, thumb.height!);
}

bool _value(WidgetTester tester) =>
    tester.widget<OmniSettingsSwitch>(find.byKey(_switchKey)).value;

Future<TestGesture> _dragTowardsOn(
  WidgetTester tester, {
  bool rtl = false,
}) async {
  final gesture = await tester.startGesture(
    tester.getCenter(find.byKey(_switchKey)),
  );
  final direction = rtl ? -1.0 : 1.0;
  await gesture.moveBy(Offset(24 * direction, 0));
  await tester.pump();
  await gesture.moveBy(Offset(20 * direction, 0));
  await tester.pump(const Duration(milliseconds: 150));
  return gesture;
}

void main() {
  final haptics = <Object?>[];

  setUp(() {
    haptics.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (call) async {
          if (call.method == 'HapticFeedback.vibrate') {
            haptics.add(call.arguments);
          }
          return null;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null);
  });

  testWidgets('uses upstream track, thumb, target, and held lens geometry', (
    tester,
  ) async {
    await tester.pumpWidget(const _SwitchHarness(glass: true));
    await tester.pumpAndSettle();
    expect(tester.getSize(find.byKey(_switchKey)), const Size(60, 48));
    final track = _parts(tester).first;
    expect(Size(track.width!, track.height!), const Size(52, 32));
    expect(_thumb(tester).size, const Size.square(24));
    expect(find.byType(LiquidGlass), findsNothing);
    final gesture = await tester.startGesture(
      tester.getCenter(find.byKey(_switchKey)),
    );
    // Tap-down is admitted after the tap recognizer deadline.
    await tester.pump(const Duration(milliseconds: 110));
    await tester.pump(const Duration(milliseconds: 150));
    expect(_thumb(tester).size, const Size.square(40));
    expect(find.byType(LiquidGlass), findsOneWidget);
    expect(
      tester.widget<LiquidGlass>(find.byType(LiquidGlass)).style,
      isA<LiquidGlassStyle>(),
    );
    await gesture.cancel();
    await tester.pumpAndSettle();
    expect(_thumb(tester).size, const Size.square(24));
    expect(find.byType(LiquidGlass), findsNothing);
    expect(haptics, isEmpty);
  });

  testWidgets('tap toggles once and emits one directional haptic', (
    tester,
  ) async {
    final changes = <bool>[];
    await tester.pumpWidget(_SwitchHarness(onToggle: changes.add));
    await tester.tap(find.byKey(_switchKey));
    await tester.pumpAndSettle();
    expect(changes, [true]);
    expect(_value(tester), isTrue);
    expect(haptics, ['HapticFeedbackType.lightImpact']);
    await tester.tap(find.byKey(_switchKey));
    await tester.pumpAndSettle();
    expect(changes, [true, false]);
    expect(haptics, [
      'HapticFeedbackType.lightImpact',
      'HapticFeedbackType.selectionClick',
    ]);
  });

  testWidgets('drag cancellation restores the owner value without haptics', (
    tester,
  ) async {
    final changes = <bool>[];
    await tester.pumpWidget(_SwitchHarness(onToggle: changes.add));
    final rest = _thumb(tester);
    final gesture = await _dragTowardsOn(tester);
    expect(_thumb(tester).center.dx, greaterThan(rest.center.dx));
    await gesture.cancel();
    await tester.pumpAndSettle();
    expect(_thumb(tester), rest);
    expect(changes, isEmpty);
    expect(haptics, isEmpty);
  });

  testWidgets('a rejected drag settles back to the owner value', (
    tester,
  ) async {
    final changes = <bool>[];
    await tester.pumpWidget(
      _SwitchHarness(acceptChanges: false, onToggle: changes.add),
    );
    final rest = _thumb(tester);
    final gesture = await _dragTowardsOn(tester);
    await gesture.up();
    await tester.pumpAndSettle();
    expect(changes, [true]);
    expect(_value(tester), isFalse);
    expect(_thumb(tester), rest);
    expect(haptics, ['HapticFeedbackType.lightImpact']);
  });

  testWidgets('RTL mirrors resting geometry and accepts a leftward on drag', (
    tester,
  ) async {
    await tester.pumpWidget(const _SwitchHarness(direction: TextDirection.rtl));
    expect(_thumb(tester).center.dx, 40);
    final gesture = await _dragTowardsOn(tester, rtl: true);
    await gesture.up();
    await tester.pumpAndSettle();
    expect(_value(tester), isTrue);
    expect(_thumb(tester).center.dx, 20);
    expect(haptics, ['HapticFeedbackType.lightImpact']);
  });

  testWidgets('disabled switch and row ignore taps, drags, and keyboard', (
    tester,
  ) async {
    final changes = <bool>[];
    await tester.pumpWidget(
      _SwitchHarness(enabled: false, row: true, onToggle: changes.add),
    );
    final rest = _thumb(tester);
    await tester.tap(find.byKey(_switchKey));
    await tester.tap(find.text('Enable setting'));
    final gesture = await _dragTowardsOn(tester);
    await gesture.up();
    await tester.sendKeyEvent(LogicalKeyboardKey.tab);
    await tester.sendKeyEvent(LogicalKeyboardKey.space);
    await tester.pumpAndSettle();
    expect(_thumb(tester), rest);
    expect(changes, isEmpty);
    expect(haptics, isEmpty);
  });

  testWidgets(
    'disabling during a drag clears the held thumb without a toggle',
    (tester) async {
      final changes = <bool>[];
      await tester.pumpWidget(_SwitchHarness(onToggle: changes.add));
      final rest = _thumb(tester);
      final gesture = await _dragTowardsOn(tester);
      await tester.pumpWidget(
        _SwitchHarness(enabled: false, onToggle: changes.add),
      );
      await gesture.up();
      await tester.pumpAndSettle();
      expect(_thumb(tester), rest);
      expect(changes, isEmpty);
      expect(haptics, isEmpty);
    },
  );

  for (final reducedMotion in [false, true]) {
    testWidgets(
      '${reducedMotion ? 'reduced motion' : 'zero duration'} settles without animated travel',
      (tester) async {
        await tester.pumpWidget(
          _SwitchHarness(
            reducedMotion: reducedMotion,
            duration: reducedMotion
                ? const Duration(milliseconds: 300)
                : Duration.zero,
          ),
        );
        await tester.tap(find.byKey(_switchKey));
        await tester.pump();
        await tester.pump();
        expect(_value(tester), isTrue);
        expect(_thumb(tester), const Rect.fromLTWH(28, 12, 24, 24));
        expect(tester.binding.transientCallbackCount, 0);
      },
    );
  }

  testWidgets(
    'row taps use the switch owner and direct taps never double toggle',
    (tester) async {
      final changes = <bool>[];
      await tester.pumpWidget(_SwitchHarness(row: true, onToggle: changes.add));
      await tester.tap(find.text('Enable setting'));
      await tester.pumpAndSettle();
      expect(changes, [true]);
      await tester.tap(find.byKey(_switchKey));
      await tester.pumpAndSettle();
      expect(changes, [true, false]);
      expect(haptics, [
        'HapticFeedbackType.lightImpact',
        'HapticFeedbackType.selectionClick',
      ]);
    },
  );

  testWidgets('explicit row actions remain independent from the switch', (
    tester,
  ) async {
    var rowTaps = 0;
    final changes = <bool>[];
    await tester.pumpWidget(
      _SwitchHarness(
        row: true,
        onRowTap: () => rowTaps++,
        onToggle: changes.add,
      ),
    );
    await tester.tap(find.byKey(_switchKey));
    await tester.pumpAndSettle();
    expect(changes, [true]);
    expect(rowTaps, 0);
    await tester.tap(find.text('Enable setting'));
    await tester.pumpAndSettle();
    expect(rowTaps, 1);
    expect(changes, [true]);
  });

  testWidgets('keyboard row and switch activation each toggle once', (
    tester,
  ) async {
    final changes = <bool>[];
    await tester.pumpWidget(_SwitchHarness(row: true, onToggle: changes.add));
    await tester.sendKeyEvent(LogicalKeyboardKey.tab);
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pumpAndSettle();
    expect(changes, [true]);
    await tester.sendKeyEvent(LogicalKeyboardKey.tab);
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.space);
    await tester.pumpAndSettle();
    expect(changes, [true, false]);
    expect(haptics, [
      'HapticFeedbackType.lightImpact',
      'HapticFeedbackType.selectionClick',
    ]);
  });

  testWidgets('exposes one enabled toggle semantic action', (tester) async {
    final semantics = tester.ensureSemantics();
    await tester.pumpWidget(const _SwitchHarness());
    expect(
      tester.getSemantics(find.byKey(_switchKey)),
      matchesSemantics(
        hasEnabledState: true,
        isEnabled: true,
        hasToggledState: true,
        isToggled: false,
        isFocusable: true,
        hasTapAction: true,
        hasFocusAction: true,
      ),
    );
    tester.binding.performSemanticsAction(
      SemanticsActionEvent(
        nodeId: tester.getSemantics(find.byKey(_switchKey)).id,
        viewId: tester.view.viewId,
        type: SemanticsAction.tap,
      ),
    );
    await tester.pumpAndSettle();
    expect(_value(tester), isTrue);
    expect(haptics, ['HapticFeedbackType.lightImpact']);
    semantics.dispose();
  });
}
