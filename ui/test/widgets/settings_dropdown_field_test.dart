import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/omni_menu.dart';
import 'package:ui/widgets/settings_ui.dart';
import 'package:ui/widgets/flclash/popup.dart';
import 'package:ui/theme/theme_context.dart';

void main() {
  testWidgets('selection keeps the source value until its return finishes', (
    tester,
  ) async {
    var selected = 'first';
    var changes = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: StatefulBuilder(
            builder: (context, setState) => Center(
              child: OmniSettingsDropdown<String>(
                dropdownKey: const ValueKey('return-source'),
                value: selected,
                options: const [
                  OmniSettingsOption(value: 'first', label: 'First'),
                  OmniSettingsOption(value: 'second', label: 'Second'),
                ],
                onChanged: (value) => setState(() {
                  selected = value!;
                  changes++;
                }),
              ),
            ),
          ),
        ),
      ),
    );
    final control = find.byKey(const ValueKey('return-source'));
    final bounds = tester.getRect(control);
    await tester.tap(control);
    await tester.pumpAndSettle();
    final transition = tester.widget<FlClashPopupTransition>(
      find.byType(FlClashPopupTransition),
    );
    expect(transition.anchor, bounds);
    expect(
      transition.source!.color,
      tester
          .element(control)
          .omniPalette
          .surfaceSecondary
          .withValues(alpha: .8),
    );
    await tester.tap(find.text('Second'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(selected, 'first');
    expect(changes, 0);
    expect(find.byType(FlClashPopupTransition), findsOneWidget);
    expect(tester.getRect(control), bounds);
    await tester.pumpAndSettle();
    expect(selected, 'second');
    expect(changes, 1);
    expect(find.byType(FlClashPopupTransition), findsNothing);
  });

  testWidgets('field source excludes its helper and keeps the actual outline', (
    tester,
  ) async {
    const fill = Color(0xFFBBDDEE);
    const outline = OutlineInputBorder(
      borderRadius: BorderRadius.all(Radius.circular(12)),
    );
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Padding(
            padding: const EdgeInsets.all(24),
            child: OmniSettingsDropdown<String>(
              value: 'first',
              decoration: const InputDecoration(
                labelText: 'Model',
                filled: true,
                fillColor: fill,
                border: outline,
                enabledBorder: outline,
                focusedBorder: outline,
                helperText: 'Explanation stays outside the source',
              ),
              options: const [
                OmniSettingsOption(value: 'first', label: 'First'),
                OmniSettingsOption(value: 'second', label: 'Second'),
              ],
              onChanged: (_) {},
            ),
          ),
        ),
      ),
    );
    final label = find.text('First');
    final box = InputDecorator.containerOf(tester.element(label))!;
    final fieldBounds = box.localToGlobal(Offset.zero) & box.size;
    final labelBox = tester.renderObject<RenderBox>(find.text('Model'));
    final labelBounds = MatrixUtils.transformRect(
      labelBox.getTransformTo(null),
      labelBox.paintBounds,
    );
    expect(labelBounds.top, lessThan(fieldBounds.top));
    await tester.tap(label);
    await tester.pumpAndSettle();
    final transition = tester.widget<FlClashPopupTransition>(
      find.byType(FlClashPopupTransition),
    );
    expect(transition.anchor, fieldBounds);
    expect(transition.source!.color, fill);
    final pixelRatio = tester.view.devicePixelRatio;
    final snapshot = transition.source!.image.value!;
    expect(snapshot.height / pixelRatio, greaterThan(fieldBounds.height));
    final bytes = await tester.runAsync(
      () => snapshot.toByteData(format: ui.ImageByteFormat.rawRgba),
    );
    final upperRows = ((fieldBounds.top - labelBounds.top) * pixelRatio)
        .floor();
    var paintedLabelPixels = 0;
    for (var y = 0; y < upperRows; y++) {
      for (var x = 0; x < snapshot.width; x++) {
        if (bytes!.getUint8((y * snapshot.width + x) * 4 + 3) != 0) {
          paintedLabelPixels++;
        }
      }
    }
    expect(
      paintedLabelPixels,
      greaterThan(0),
      reason: 'the label above the outline must remain in the source snapshot',
    );
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('Explanation stays outside the source'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('a covered page cannot open its prepared selector late', (
    tester,
  ) async {
    final gate = Completer<void>();
    final navigator = GlobalKey<NavigatorState>();
    await tester.pumpWidget(
      MaterialApp(
        navigatorKey: navigator,
        home: Scaffold(
          body: OmniSettingsDropdown<String>(
            value: 'first',
            options: const [
              OmniSettingsOption(value: 'first', label: 'First'),
              OmniSettingsOption(value: 'second', label: 'Second'),
            ],
            onBeforeOpen: () => gate.future,
            onChanged: (_) => fail('Covered page changed selection'),
          ),
        ),
      ),
    );
    await tester.tap(find.text('First'));
    unawaited(
      navigator.currentState!.push(
        MaterialPageRoute<void>(
          builder: (_) => const Scaffold(body: Text('Another page')),
        ),
      ),
    );
    await tester.pumpAndSettle();
    gate.complete();
    await tester.pumpAndSettle();
    expect(find.text('Another page'), findsOneWidget);
    expect(find.byType(FlClashPopupTransition), findsNothing);
    expect(tester.takeException(), isNull);
  });

  for (final alignment in [
    Alignment.topLeft,
    Alignment.topRight,
    Alignment.bottomLeft,
    Alignment.bottomRight,
  ]) {
    testWidgets('selector covers its trigger near $alignment', (tester) async {
      tester.view.physicalSize = const Size(320, 568);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      var changeCount = 0;
      const triggerKey = ValueKey('selector-trigger');
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Padding(
              padding: const EdgeInsets.all(24),
              child: Align(
                alignment: alignment,
                child: OmniSettingsDropdown<String>(
                  dropdownKey: triggerKey,
                  value: 'first',
                  options: const [
                    OmniSettingsOption(value: 'first', label: 'First choice'),
                    OmniSettingsOption(value: 'second', label: 'Second choice'),
                    OmniSettingsOption(value: 'third', label: 'Third choice'),
                  ],
                  onChanged: (_) => changeCount += 1,
                ),
              ),
            ),
          ),
        ),
      );

      final trigger = tester.getRect(find.byKey(triggerKey));
      await tester.tap(find.byKey(triggerKey));
      await tester.pumpAndSettle();
      final panel = tester.getRect(find.byType(OmniMenuSurface));
      expect(panel.contains(trigger.center), isTrue);
      expect(panel.left, greaterThanOrEqualTo(16));
      expect(panel.right, lessThanOrEqualTo(304));
      expect(panel.top, greaterThanOrEqualTo(16));
      expect(panel.bottom, lessThanOrEqualTo(552));
      expect(changeCount, 0);

      await tester.tap(find.text('First choice').last);
      await tester.pumpAndSettle();
      expect(find.byType(OmniMenuSurface), findsNothing);
      expect(changeCount, 0);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets(
    'refresh waits for rebuilt options and suppresses repeated opens',
    (tester) async {
      final gate = Completer<void>();
      var refreshCount = 0;
      var loading = false;
      String? selected;
      List<OmniSettingsOption<String?>> options = const [];
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: StatefulBuilder(
              builder: (context, setState) => Align(
                alignment: Alignment.centerRight,
                child: OmniSettingsDropdown<String?>(
                  value: selected,
                  selectedLabel: 'Current choice',
                  options: options,
                  onBeforeOpen: () async {
                    refreshCount += 1;
                    setState(() => loading = true);
                    await gate.future;
                    setState(() {
                      loading = false;
                      options = const [
                        OmniSettingsOption(
                          value: 'refreshed',
                          label: 'New choice',
                        ),
                      ];
                    });
                  },
                  onChanged: loading
                      ? null
                      : (value) => setState(() => selected = value),
                ),
              ),
            ),
          ),
        ),
      );

      await tester.tap(find.text('Current choice'));
      await tester.tap(find.text('Current choice'));
      await tester.pump();
      expect(refreshCount, 1);
      expect(find.text('New choice'), findsNothing);
      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('New choice'), findsOneWidget);
      expect(find.byType(TextField), findsNothing);
      await tester.tap(find.text('New choice'));
      await tester.pumpAndSettle();
      expect(selected, 'refreshed');
      expect(find.text('Current choice'), findsOneWidget);
      expect(find.text('New choice'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('disposing a selector during preparation never opens a route', (
    tester,
  ) async {
    final gate = Completer<void>();
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OmniSettingsDropdown<String>(
            value: 'first',
            options: const [
              OmniSettingsOption(value: 'first', label: 'First'),
              OmniSettingsOption(value: 'second', label: 'Second'),
            ],
            onBeforeOpen: () => gate.future,
            onChanged: (_) => fail('Disposed selector changed value'),
          ),
        ),
      ),
    );
    await tester.tap(find.text('First'));
    await tester.pump();
    await tester.pumpWidget(const MaterialApp(home: SizedBox.shrink()));
    gate.complete();
    await tester.pumpAndSettle();
    expect(find.text('Second'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'search matches labels, providers and IDs above the live keyboard',
    (tester) async {
      tester.view.physicalSize = const Size(320, 568);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetViewInsets);
      String? selected;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Align(
              alignment: Alignment.bottomLeft,
              child: OmniSettingsDropdown<String?>(
                value: null,
                selectedLabel: 'Choose',
                searchHint: 'Find a model',
                options: [
                  for (var index = 1; index <= 12; index += 1)
                    OmniSettingsOption(
                      value: 'engine-$index',
                      label: 'Friendly ${index.toString().padLeft(2, '0')}',
                      subtitle: index.isEven ? 'Provider A' : 'Provider B',
                      searchText: 'engine-$index',
                    ),
                ],
                onChanged: (value) => selected = value,
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Choose'));
      await tester.pumpAndSettle();
      await tester.tap(find.byType(TextField));
      tester.view.viewInsets = const FakeViewPadding(bottom: 220);
      await tester.pumpAndSettle();
      final panel = tester.getRect(find.byType(OmniMenuSurface));
      expect(panel.left, greaterThanOrEqualTo(16));
      expect(panel.right, lessThanOrEqualTo(304));
      expect(panel.bottom, lessThanOrEqualTo(348));

      await tester.enterText(find.byType(TextField), 'Friendly 02');
      await tester.pump();
      expect(find.widgetWithText(OmniMenuItem, 'Friendly 02'), findsOneWidget);
      expect(find.text('Friendly 01'), findsNothing);
      await tester.enterText(find.byType(TextField), 'Provider B');
      await tester.pump();
      expect(find.text('Friendly 01'), findsOneWidget);
      expect(find.text('Friendly 02'), findsNothing);
      await tester.enterText(find.byType(TextField), 'engine-11');
      await tester.pump();
      expect(find.text('Friendly 11'), findsOneWidget);
      expect(find.text('Friendly 01'), findsNothing);
      expect(selected, isNull);
      await tester.tap(find.text('Friendly 11'));
      await tester.pumpAndSettle();
      expect(selected, 'engine-11');
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('nullable field opens by keyboard and only commits a choice', (
    tester,
  ) async {
    String? selected;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: StatefulBuilder(
            builder: (context, setState) => Padding(
              padding: const EdgeInsets.all(24),
              child: OmniSettingsDropdown<String?>(
                value: selected,
                decoration: const InputDecoration(
                  labelText: 'Model',
                  hintText: 'Choose a model',
                  helperText: 'Used for this scene',
                ),
                options: const [
                  OmniSettingsOption(value: 'first', label: 'First model'),
                  OmniSettingsOption(value: 'second', label: 'Second model'),
                ],
                onChanged: (value) => setState(() => selected = value),
              ),
            ),
          ),
        ),
      ),
    );

    expect(selected, isNull);
    expect(find.text('Choose a model'), findsOneWidget);
    expect(find.text('First model'), findsNothing);
    expect(find.text('Used for this scene'), findsOneWidget);
    await tester.sendKeyEvent(LogicalKeyboardKey.tab);
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    await tester.pumpAndSettle();
    expect(find.text('First model'), findsOneWidget);
    expect(selected, isNull);

    await tester.tap(find.text('Second model'));
    await tester.pumpAndSettle();
    expect(selected, 'second');
    expect(find.text('Second model'), findsOneWidget);
    expect(find.text('Choose a model'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('a field disabled while its picker is open cannot change value', (
    tester,
  ) async {
    var enabled = true;
    var changeCount = 0;
    late StateSetter update;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: StatefulBuilder(
            builder: (context, setState) {
              update = setState;
              return Padding(
                padding: const EdgeInsets.all(24),
                child: OmniSettingsDropdown<String?>(
                  value: null,
                  decoration: const InputDecoration(
                    labelText: 'Model',
                    hintText: 'Choose a model',
                  ),
                  options: const [
                    OmniSettingsOption(value: 'first', label: 'First model'),
                  ],
                  onChanged: enabled ? (_) => changeCount += 1 : null,
                ),
              );
            },
          ),
        ),
      ),
    );

    await tester.tap(find.text('Choose a model'));
    await tester.pumpAndSettle();
    update(() => enabled = false);
    await tester.pump();
    await tester.tap(find.text('First model'));
    await tester.pumpAndSettle();
    expect(changeCount, 0);
    expect(find.text('Choose a model'), findsOneWidget);
    await tester.tap(find.text('Choose a model'));
    await tester.pumpAndSettle();
    expect(find.text('First model'), findsNothing);
    expect(changeCount, 0);
    expect(tester.takeException(), isNull);
  });
}
