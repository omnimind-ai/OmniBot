import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/flclash/elastic_press.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/flclash/popup.dart';
import 'package:ui/widgets/glass_popup.dart';
import 'package:ui/widgets/omni_menu.dart';

const _panel = ValueKey('menu panel');
const _content = ValueKey('menu content');

Widget _app(
  Widget child, {
  bool reducedMotion = false,
  bool glass = false,
  ThemeData? theme,
}) => MaterialApp(
  theme: theme,
  builder: (context, child) => MediaQuery(
    data: MediaQuery.of(context).copyWith(disableAnimations: reducedMotion),
    child: LiquidGlassScope(
      enabled: glass,
      child: LiquidGlassHost(enabled: glass, child: child!),
    ),
  ),
  home: Scaffold(body: Center(child: child)),
);

Widget _menu(List<Widget> children) => OmniMenuSurface(
  key: _panel,
  width: 240,
  child: Column(
    key: _content,
    mainAxisSize: MainAxisSize.min,
    children: children,
  ),
);

RenderBox _contentBox(WidgetTester tester) =>
    tester.renderObject(find.byKey(_content));
Rect _painted(WidgetTester tester) =>
    FlClashElasticPress.paintBoundsOf(_contentBox(tester));

void main() {
  for (final dark in [false, true]) {
    testWidgets(
      'AppTheme ${dark ? 'dark' : 'light'} keeps tracked row highlight visible',
      (tester) async {
        await tester.pumpWidget(
          _app(
            _menu([OmniMenuItem(label: 'Action', onTap: () {})]),
            theme: dark ? AppTheme.darkTheme : AppTheme.lightTheme,
          ),
        );
        final gesture = await tester.startGesture(
          tester.getCenter(find.text('Action')),
        );
        await tester.pump();
        final highlight = dark
            ? const Color(0x40CCCCCC)
            : const Color(0x66BCBCBC);
        expect(
          find.descendant(
            of: find.byType(OmniMenuItem),
            matching: find.byWidgetPredicate(
              (widget) =>
                  widget is DecoratedBox &&
                  widget.decoration is ShapeDecoration &&
                  (widget.decoration as ShapeDecoration).color == highlight,
            ),
          ),
          findsOneWidget,
        );
        expect(highlight.a, greaterThan(0));
        await gesture.cancel();
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets('press grows and edge pull stretches without moving layout', (
    tester,
  ) async {
    var actions = 0;
    await tester.pumpWidget(
      _app(
        _menu([
          OmniMenuItem(label: 'First', onTap: () => actions++),
          OmniMenuItem(label: 'Second', onTap: () => actions++),
        ]),
      ),
    );
    final rest = tester.getRect(find.byKey(_content));
    final gesture = await tester.startGesture(
      tester.getCenter(find.text('First')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 250));
    expect(_painted(tester).width, greaterThan(rest.width));
    expect(tester.getRect(find.byKey(_content)), rest);
    final expanded = _painted(tester);
    await gesture.moveTo(Offset(rest.right + 100, rest.center.dy));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 150));
    final stretched = _painted(tester);
    expect(stretched.width, greaterThan(expanded.width));
    expect(stretched.center.dx, greaterThan(expanded.center.dx));
    expect(tester.getRect(find.byKey(_content)), rest);
    await gesture.up();
    await tester.pumpAndSettle();
    expect(_painted(tester), Offset.zero & rest.size);
    expect(actions, 0);
  });

  for (final fromPadding in [true, false]) {
    testWidgets(
      'menu ${fromPadding ? 'padding' : 'title'} pulls without becoming a row selection',
      (tester) async {
        var actions = 0;
        await tester.pumpWidget(
          _app(
            _menu([
              const Padding(
                padding: EdgeInsets.all(12),
                child: Text('Menu heading'),
              ),
              OmniMenuItem(label: 'Action', onTap: () => actions++),
            ]),
          ),
        );
        final panel = tester.getRect(find.byKey(_panel));
        final start = fromPadding
            ? panel.topCenter + const Offset(0, 4)
            : tester.getCenter(find.text('Menu heading'));
        final gesture = await tester.startGesture(start);
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 250));
        expect(
          FlClashElasticPress.liftOf(_contentBox(tester)),
          greaterThan(.5),
        );
        final held = _painted(tester);
        await gesture.moveTo(Offset(panel.right + 100, panel.center.dy));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 150));
        expect(_painted(tester).width, greaterThan(held.width));
        await gesture.moveTo(tester.getCenter(find.text('Action')));
        await tester.pump();
        await gesture.up();
        await tester.pumpAndSettle();
        expect(
          actions,
          0,
          reason: 'A chrome press must never turn into row selection',
        );
        expect(FlClashElasticPress.liftOf(_contentBox(tester)), 0);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets(
    'header controls and nested buttons exclude elastic chrome hit testing',
    (tester) async {
      var headerCalls = 0;
      var buttonCalls = 0;
      var rowCalls = 0;
      final field = TextEditingController(text: 'Editable header');
      addTearDown(field.dispose);
      await tester.pumpWidget(
        _app(
          _menu([
            TextField(controller: field),
            Material(
              child: InkWell(
                onTap: () => headerCalls++,
                child: const Padding(
                  padding: EdgeInsets.all(12),
                  child: Text('Header action'),
                ),
              ),
            ),
            OmniMenuItem(
              onTap: () => rowCalls++,
              child: Row(
                children: [
                  const Expanded(child: Text('Service')),
                  TextButton(
                    onPressed: () => buttonCalls++,
                    child: const Text('Run'),
                  ),
                ],
              ),
            ),
          ]),
        ),
      );
      for (final target in [
        find.byType(TextField),
        find.text('Header action'),
        find.text('Run'),
      ]) {
        final gesture = await tester.startGesture(tester.getCenter(target));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 250));
        expect(
          FlClashElasticPress.liftOf(_contentBox(tester)),
          0,
          reason: target.toString(),
        );
        await gesture.up();
        await tester.pumpAndSettle();
      }
      expect(headerCalls, 1);
      expect(buttonCalls, 1);
      expect(rowCalls, 0);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('drag selects the final row once, including its static check', (
    tester,
  ) async {
    final actions = <String>[];
    await tester.pumpWidget(
      _app(
        _menu([
          OmniMenuItem(label: 'First', onTap: () => actions.add('first')),
          OmniMenuItem(
            label: 'Second',
            trailing: const Icon(Icons.check, key: ValueKey('check')),
            onTap: () => actions.add('second'),
          ),
        ]),
      ),
    );
    final gesture = await tester.startGesture(
      tester.getCenter(find.text('First')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 250));
    await gesture.moveTo(tester.getCenter(find.byKey(const ValueKey('check'))));
    await tester.pump(const Duration(milliseconds: 120));
    await gesture.up();
    await tester.pumpAndSettle();
    expect(actions, ['second']);
    await tester.tap(find.byKey(const ValueKey('check')));
    await tester.pumpAndSettle();
    expect(actions, ['second', 'second']);
  });

  testWidgets(
    'accepted pointer cancellation releases the panel and permits another press',
    (tester) async {
      var actions = 0;
      await tester.pumpWidget(
        _app(_menu([OmniMenuItem(label: 'First', onTap: () => actions++)])),
      );
      final gesture = await tester.startGesture(
        tester.getCenter(find.text('First')),
      );
      await gesture.moveBy(const Offset(32, 0));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 250));
      expect(FlClashElasticPress.liftOf(_contentBox(tester)), greaterThan(0.5));
      await gesture.cancel();
      await tester.pumpAndSettle();
      expect(FlClashElasticPress.liftOf(_contentBox(tester)), 0);
      expect(actions, 0);
      await tester.tap(find.text('First'));
      await tester.pumpAndSettle();
      expect(actions, 1);
    },
  );

  testWidgets(
    'text entry, trailing switch and slider retain independent input',
    (tester) async {
      final field = TextEditingController();
      addTearDown(field.dispose);
      var rowCalls = 0;
      var switchCalls = 0;
      var sliderCalls = 0;
      var switchValue = false;
      var sliderValue = 0.2;
      await tester.pumpWidget(
        _app(
          StatefulBuilder(
            builder: (context, setState) => _menu([
              TextField(controller: field),
              OmniMenuItem(
                label: 'Switch',
                onTap: () => rowCalls++,
                trailing: Switch(
                  value: switchValue,
                  onChanged: (value) => setState(() {
                    switchValue = value;
                    switchCalls++;
                  }),
                ),
              ),
              OmniMenuItem(
                label: 'Slider',
                onTap: () => rowCalls++,
                trailing: SizedBox(
                  width: 100,
                  child: FlClashMenuInteractionIgnore(
                    child: Slider(
                      value: sliderValue,
                      onChanged: (value) => setState(() {
                        sliderValue = value;
                        sliderCalls++;
                      }),
                    ),
                  ),
                ),
              ),
            ]),
          ),
        ),
      );
      await tester.tap(find.byType(TextField));
      await tester.enterText(find.byType(TextField), 'Search text');
      await tester.pumpAndSettle();
      expect(field.text, 'Search text');
      expect(FlClashElasticPress.liftOf(_contentBox(tester)), 0);
      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();
      expect(switchValue, isTrue);
      expect(switchCalls, 1);
      expect(FlClashElasticPress.liftOf(_contentBox(tester)), 0);
      await tester.drag(find.byType(Slider), const Offset(40, 0));
      await tester.pumpAndSettle();
      expect(sliderCalls, greaterThan(0));
      expect(rowCalls, 0);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('nested independent action does not select its surrounding row', (
    tester,
  ) async {
    var rowCalls = 0;
    var stopCalls = 0;
    await tester.pumpWidget(
      _app(
        _menu([
          OmniMenuItem(
            onTap: () => rowCalls++,
            child: Row(
              children: [
                const Expanded(child: Text('Service')),
                FlClashMenuInteractionIgnore(
                  child: TextButton(
                    onPressed: () => stopCalls++,
                    child: const Text('Stop'),
                  ),
                ),
              ],
            ),
          ),
        ]),
      ),
    );
    await tester.tap(find.text('Stop'));
    await tester.pumpAndSettle();
    expect(stopCalls, 1);
    expect(rowCalls, 0);
    expect(FlClashElasticPress.liftOf(_contentBox(tester)), 0);
  });

  testWidgets('scroll wins over row selection and clears the elastic press', (
    tester,
  ) async {
    var actions = 0;
    final scroll = ScrollController();
    addTearDown(scroll.dispose);
    await tester.pumpWidget(
      _app(
        SizedBox(
          height: 180,
          child: OmniMenuSurface(
            width: 240,
            child: SingleChildScrollView(
              controller: scroll,
              child: Column(
                key: _content,
                children: [
                  for (var i = 0; i < 20; i++)
                    OmniMenuItem(label: 'Action $i', onTap: () => actions++),
                ],
              ),
            ),
          ),
        ),
      ),
    );
    await tester.drag(find.text('Action 1'), const Offset(0, -110));
    await tester.pumpAndSettle();
    expect(scroll.offset, greaterThan(50));
    expect(actions, 0);
    expect(FlClashElasticPress.liftOf(_contentBox(tester)), 0);
  });

  testWidgets('reduced motion preserves selection without elasticity', (
    tester,
  ) async {
    var actions = 0;
    await tester.pumpWidget(
      _app(
        _menu([OmniMenuItem(label: 'First', onTap: () => actions++)]),
        reducedMotion: true,
      ),
    );
    final rest = _painted(tester);
    final gesture = await tester.startGesture(
      tester.getCenter(find.text('First')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 250));
    expect(_painted(tester), rest);
    await gesture.up();
    await tester.pumpAndSettle();
    expect(actions, 1);
    expect(tester.binding.hasScheduledFrame, isFalse);
  });

  for (final overlay in [false, true]) {
    testWidgets(
      '${overlay ? 'overlay' : 'route'} without source closes in 200ms and owns one elastic surface',
      (tester) async {
        late BuildContext sourceContext;
        late BuildContext panelContext;
        OverlayGlassPopupHandle<void>? handle;
        await tester.pumpWidget(
          _app(
            Builder(
              builder: (context) {
                sourceContext = context;
                return const SizedBox(width: 40, height: 40);
              },
            ),
            glass: true,
          ),
        );
        final content = Builder(
          builder: (context) {
            panelContext = context;
            return _menu([OmniMenuItem(label: 'Action', onTap: () {})]);
          },
        );
        if (overlay) {
          handle = showOverlayGlassPopup<void>(
            context: sourceContext,
            anchor: const Rect.fromLTWH(100, 100, 0, 0),
            builder: (_) => content,
          );
        } else {
          unawaited(
            showGlassPopup<void>(
              context: sourceContext,
              anchor: const Rect.fromLTWH(100, 100, 0, 0),
              child: content,
            ),
          );
        }
        await tester.pumpAndSettle();
        expect(find.byType(FlClashMenuInteraction), findsOneWidget);
        expect(LiquidGlassHost.debugLastCapture!.panes, hasLength(1));
        final panelRect = tester.getRect(find.byKey(_panel));
        final gesture = await tester.startGesture(
          tester.getCenter(find.text('Action')),
        );
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 250));
        expect(
          _painted(tester).width,
          greaterThan(_contentBox(tester).size.width),
        );
        expect(tester.getRect(find.byKey(_panel)), panelRect);
        expect(LiquidGlassHost.debugLastCapture!.panes, hasLength(1));
        await gesture.cancel();
        await tester.pumpAndSettle();
        if (overlay) {
          unawaited(handle!.dismiss());
        } else {
          expect(
            ModalRoute.of(panelContext)!.reverseTransitionDuration,
            FlClashPopupMotion.shrinkingDuration,
          );
          Navigator.of(panelContext).pop();
        }
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 150));
        expect(find.byKey(_panel), findsOneWidget);
        await tester.pump(const Duration(milliseconds: 60));
        await tester.pump();
        expect(find.byKey(_panel), findsNothing);
        expect(LiquidGlassHost.debugHeldCaptures, 0);
        expect(tester.takeException(), isNull);
      },
    );
  }
}
