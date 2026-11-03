import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:ui/widgets/settings_detail_sheet.dart';

Widget _app({
  required bool enabled,
  required LiquidGlassQuality quality,
  required Widget home,
}) => MaterialApp(
  builder: (context, child) => LiquidGlassScope(
    enabled: enabled,
    quality: quality,
    child: LiquidGlassHost(enabled: enabled, child: child!),
  ),
  home: home,
);

void main() {
  for (final dismissible in [false, true]) {
    testWidgets('sheet handle preserves dismiss semantics: $dismissible', (
      tester,
    ) async {
      final semantics = tester.ensureSemantics();
      await tester.pumpWidget(
        _app(
          enabled: true,
          quality: LiquidGlassQuality.ultra,
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () => showOmniGlassModalBottomSheet<void>(
                  context: context,
                  isDismissible: dismissible,
                  showDragHandle: true,
                  builder: (context) => ListView(
                    children: [
                      for (var index = 0; index < 30; index++)
                        ListTile(title: Text('Entry $index')),
                      TextButton(
                        onPressed: () => Navigator.of(context).pop(),
                        child: const Text('Finish'),
                      ),
                    ],
                  ),
                ),
                child: const Text('Open'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();
      final handle = find.byWidgetPredicate(
        (widget) =>
            widget is Semantics &&
            widget.properties.label == 'Dismiss' &&
            widget.properties.button == true,
      );
      expect(handle, findsOneWidget);
      expect(
        tester.getSemantics(handle),
        matchesSemantics(
          label: 'Dismiss',
          isButton: true,
          hasTapAction: dismissible,
        ),
      );
      expect(find.byType(LiquidGlass), findsOneWidget);
      final veil = tester.widget<LiquidGlassVeil>(find.byType(LiquidGlassVeil));
      final scheme = Theme.of(tester.element(find.byType(LiquidGlass)))
          .colorScheme;
      expect(veil.color.value, scheme.scrim.withValues(alpha: 0.32));
      if (dismissible) {
        tester.widget<Semantics>(handle).properties.onTap!();
      } else {
        await tester.scrollUntilVisible(find.text('Finish'), 300);
        await tester.pumpAndSettle();
        await tester.tap(find.text('Finish'));
      }
      await tester.pumpAndSettle();
      expect(find.byType(OmniGlassSheet), findsNothing);
      expect(tester.takeException(), isNull);
      semantics.dispose();
    });
  }
  for (final quality in LiquidGlassQuality.values) {
    for (final enabled in [false, true]) {
      testWidgets('$quality glass=$enabled alert keeps keyboard and actions', (
        tester,
      ) async {
        tester.view.physicalSize = const Size(360, 720);
        tester.view.devicePixelRatio = 1;
        tester.view.viewInsets = const FakeViewPadding(bottom: 300);
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        addTearDown(tester.view.resetViewInsets);
        String? result;
        await tester.pumpWidget(
          _app(
            enabled: enabled,
            quality: quality,
            home: Builder(
              builder: (context) => Scaffold(
                body: TextButton(
                  onPressed: () async {
                    result = await showOmniGlassCardSheet<String>(
                      context: context,
                      builder: (context) => OmniGlassAlertDialog(
                        title: const Text('Custom Agent'),
                        content: SizedBox(
                          width: 460,
                          child: SingleChildScrollView(
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: List.generate(
                                6,
                                (index) => TextField(
                                  decoration: InputDecoration(
                                    labelText: 'Field $index',
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ),
                        actions: [
                          TextButton(
                            onPressed: () => Navigator.of(context).pop('saved'),
                            child: const Text('Save'),
                          ),
                        ],
                      ),
                    );
                  },
                  child: const Text('Open'),
                ),
              ),
            ),
          ),
        );
        await tester.tap(find.text('Open'));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        expect(
          find.byType(LiquidGlass),
          enabled ? findsOneWidget : findsNothing,
        );
        expect(
          find.byType(LiquidGlassVeil),
          enabled ? findsOneWidget : findsNothing,
        );
        if (enabled) {
          final pane = tester.widget<LiquidGlass>(find.byType(LiquidGlass));
          final scheme = Theme.of(tester.element(find.byType(LiquidGlass)))
              .colorScheme;
          expect(pane.rim, LiquidGlassRim.alert(scheme));
          expect(pane.shadows, menuShadows(scheme));
          final veil = tester.widget<LiquidGlassVeil>(
            find.byType(LiquidGlassVeil),
          );
          expect(veil.color.value, scheme.scrim.withValues(alpha: 0.32));
          final barrier = tester.widget<ModalBarrier>(
            find.descendant(
              of: find.byType(LiquidGlassVeil),
              matching: find.byType(ModalBarrier),
            ),
          );
          expect(barrier.color, isNull);
          expect(barrier.dismissible, isTrue);
        }
        expect(tester.getBottomRight(find.text('Save')).dy, lessThan(420));
        await tester.enterText(find.byType(TextField).first, 'My Agent');
        await tester.tap(find.text('Save'));
        await tester.pumpAndSettle();
        expect(result, 'saved');
        expect(find.byType(OmniGlassAlertDialog), findsNothing);
      });

      testWidgets('$quality glass=$enabled sheet paints one pane and returns', (
        tester,
      ) async {
        bool? result;
        await tester.pumpWidget(
          _app(
            enabled: enabled,
            quality: quality,
            home: Builder(
              builder: (context) => Scaffold(
                body: TextButton(
                  onPressed: () async {
                    result = await showSettingsDetailSheet<bool>(
                      context: context,
                      builder: (context) => SettingsDetailSheet(
                        title: 'Details',
                        body: const OmniGlassPanel(
                          child: Text('Nested content'),
                        ),
                        actions: [
                          TextButton(
                            onPressed: () => Navigator.of(context).pop(true),
                            child: const Text('Done'),
                          ),
                        ],
                      ),
                    );
                  },
                  child: const Text('Open'),
                ),
              ),
            ),
          ),
        );
        await tester.tap(find.text('Open'));
        await tester.pumpAndSettle();
        final bottomSheet = tester.widget<BottomSheet>(
          find.byType(BottomSheet),
        );
        expect(bottomSheet.backgroundColor, Colors.transparent);
        expect(
          find.byType(LiquidGlass),
          enabled ? findsOneWidget : findsNothing,
        );
        expect(find.text('Nested content'), findsOneWidget);
        expect(
          find.byType(LiquidGlassVeil),
          enabled ? findsOneWidget : findsNothing,
        );
        await tester.tap(find.text('Done'));
        await tester.pumpAndSettle();
        expect(result, isTrue);
        expect(tester.takeException(), isNull);
      });
    }
  }

  testWidgets('veil keeps the native barrier callback and accessibility data', (
    tester,
  ) async {
    var dismissed = 0;
    final clip = ValueNotifier(EdgeInsets.zero);
    addTearDown(clip.dispose);
    const color = AlwaysStoppedAnimation<Color?>(Color(0x59445566));
    final barrier = AnimatedModalBarrier(
      color: color,
      dismissible: true,
      onDismiss: () => dismissed++,
      semanticsLabel: 'Close details',
      semanticsOnTapHint: 'return to conversation',
      barrierSemanticsDismissible: true,
      clipDetailsNotifier: clip,
    );
    await tester.pumpWidget(
      MaterialApp(home: wrapOmniGlassModalBarrier(barrier)),
    );
    final native = tester.widget<ModalBarrier>(
      find.descendant(
        of: find.byType(LiquidGlassVeil),
        matching: find.byType(ModalBarrier),
      ),
    );
    expect(native.color, isNull);
    expect(native.semanticsLabel, 'Close details');
    expect(native.semanticsOnTapHint, 'return to conversation');
    expect(native.clipDetailsNotifier, same(clip));
    expect(
      tester.widget<LiquidGlassVeil>(find.byType(LiquidGlassVeil)).color,
      same(color),
    );
    await tester.tapAt(const Offset(20, 20));
    expect(dismissed, 1);
  });
}
