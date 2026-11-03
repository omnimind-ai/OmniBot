import 'dart:ui' show PointerDeviceKind, Tristate;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/flclash/navigation_dock.dart';
import 'package:ui/widgets/omni_navigation_rail.dart';

const _destinations = [
  FlClashNavigationDestination(icon: Icons.home, label: '首页'),
  FlClashNavigationDestination(icon: Icons.smart_toy, label: 'Agents'),
  FlClashNavigationDestination(icon: Icons.route, label: '轨迹'),
  FlClashNavigationDestination(icon: Icons.settings, label: '设置'),
];

Finder _destination(int index) =>
    find.byKey(ValueKey('main-navigation-rail-$index'));

Widget _testApp({
  required ValueChanged<int> onSelected,
  double textScale = 1,
  Brightness brightness = Brightness.light,
  TextDirection direction = TextDirection.ltr,
  double bottomInset = 0,
  EdgeInsets safePadding = EdgeInsets.zero,
  bool glass = false,
  LiquidGlassQuality quality = LiquidGlassQuality.ultra,
}) => MaterialApp(
  theme: ThemeData(brightness: brightness),
  home: Builder(
    builder: (context) => MediaQuery(
      data: MediaQuery.of(context).copyWith(
        textScaler: TextScaler.linear(textScale),
        padding: safePadding,
        viewInsets: EdgeInsets.only(bottom: bottomInset),
      ),
      child: Directionality(
        textDirection: direction,
        child: LiquidGlassScope(
          enabled: glass,
          quality: quality,
          child: LiquidGlassHost(
            enabled: glass,
            child: Scaffold(
              resizeToAvoidBottomInset: false,
              body: _RailHarness(onSelected: onSelected),
            ),
          ),
        ),
      ),
    ),
  ),
);

class _RailHarness extends StatefulWidget {
  const _RailHarness({required this.onSelected});

  final ValueChanged<int> onSelected;

  @override
  State<_RailHarness> createState() => _RailHarnessState();
}

class _RailHarnessState extends State<_RailHarness> {
  int selectedIndex = 0;

  @override
  Widget build(BuildContext context) => Align(
    alignment: Alignment.centerLeft,
    child: SizedBox(
      width: OmniNavigationRail.width,
      child: OmniNavigationRail(
        destinations: _destinations,
        selectedIndex: selectedIndex,
        onDestinationSelected: (index) {
          widget.onSelected(index);
          setState(() => selectedIndex = index);
        },
      ),
    ),
  );
}

void _setViewport(WidgetTester tester, Size size) {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

void main() {
  for (final direction in TextDirection.values) {
    testWidgets('$direction rail drags along Y and commits only on release', (
      tester,
    ) async {
      _setViewport(tester, const Size(768, 1024));
      final selected = <int>[];
      await tester.pumpWidget(
        _testApp(onSelected: selected.add, direction: direction),
      );
      await tester.pumpAndSettle();

      final dock = tester.widget<FlClashNavigationDock>(
        find.byType(FlClashNavigationDock),
      );
      expect(dock.axis, Axis.vertical);
      final centers = [
        for (var index = 0; index < _destinations.length; index++)
          tester.getCenter(_destination(index)),
      ];
      for (var index = 1; index < centers.length; index++) {
        expect(centers[index].dx, closeTo(centers.first.dx, 0.1));
        expect(centers[index].dy, greaterThan(centers[index - 1].dy));
      }

      final drag = await tester.startGesture(centers.first);
      await tester.pump(const Duration(milliseconds: 80));
      await drag.moveTo(centers.last);
      await tester.pump(const Duration(milliseconds: 400));
      expect(selected, isEmpty);
      await drag.up();
      await tester.pumpAndSettle();
      expect(selected, [3]);

      final cancel = await tester.startGesture(centers.last);
      await cancel.moveTo(centers[1]);
      await tester.pump(const Duration(milliseconds: 120));
      expect(selected, [3]);
      await cancel.cancel();
      await tester.pumpAndSettle();
      expect(selected, [3]);
      await tester.tap(_destination(3));
      await tester.pumpAndSettle();
      expect(selected, [3], reason: 'Tapping the active tab is idempotent.');

      final sideways = await tester.startGesture(centers[1]);
      await sideways.moveBy(const Offset(180, 0));
      await tester.pump(const Duration(milliseconds: 200));
      expect(selected, [3]);
      await sideways.up();
      await tester.pumpAndSettle();
      expect(selected, [
        3,
        1,
      ], reason: 'Horizontal movement must not change the Y destination.');
      expect(tester.takeException(), isNull);
    });
  }

  for (final quality in LiquidGlassQuality.values) {
    testWidgets('$quality rail lifts the glass lens and releases captures', (
      tester,
    ) async {
      _setViewport(tester, const Size(768, 1024));
      final selected = <int>[];
      await tester.pumpWidget(
        _testApp(onSelected: selected.add, glass: true, quality: quality),
      );
      await tester.pumpAndSettle();
      final dock = tester.element(find.byType(FlClashNavigationDock));
      expect(find.byType(LiquidGlass), findsOneWidget);
      expect(LiquidGlassHost.debugHeldCaptures, 1);

      final drag = await tester.startGesture(tester.getCenter(_destination(0)));
      await tester.pump(const Duration(milliseconds: 80));
      await drag.moveTo(tester.getCenter(_destination(3)));
      await tester.pump(const Duration(milliseconds: 100));
      expect(selected, isEmpty);
      expect(find.byType(LiquidGlass).evaluate().length, greaterThan(1));
      expect(LiquidGlassHost.debugHeldCaptures, greaterThan(1));

      final switched = quality == LiquidGlassQuality.ultra
          ? LiquidGlassQuality.standard
          : LiquidGlassQuality.ultra;
      await tester.pumpWidget(
        _testApp(onSelected: selected.add, glass: true, quality: switched),
      );
      await tester.pump(const Duration(milliseconds: 100));
      expect(tester.element(find.byType(FlClashNavigationDock)), same(dock));
      expect(selected, isEmpty);
      await drag.up();
      await tester.pumpAndSettle();
      expect(selected, [3]);
      expect(find.byType(LiquidGlass), findsOneWidget);
      expect(LiquidGlassHost.debugHeldCaptures, 1);

      final cancel = await tester.startGesture(
        tester.getCenter(_destination(3)),
      );
      await cancel.moveTo(tester.getCenter(_destination(1)));
      await tester.pump(const Duration(milliseconds: 120));
      await tester.pumpWidget(_testApp(onSelected: selected.add));
      await tester.pumpAndSettle();
      expect(LiquidGlassHost.debugHeldCaptures, 0);
      await cancel.cancel();
      await tester.pumpAndSettle();
      expect(selected, [3]);

      await tester.pumpWidget(
        _testApp(onSelected: selected.add, glass: true, quality: quality),
      );
      await tester.pumpAndSettle();
      expect(LiquidGlassHost.debugHeldCaptures, 1);
      await tester.pumpWidget(const SizedBox.shrink());
      expect(LiquidGlassHost.debugHeldCaptures, 0);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('rail supports pointer hover and mouse selection', (
    tester,
  ) async {
    _setViewport(tester, const Size(768, 1024));
    final selected = <int>[];
    await tester.pumpWidget(_testApp(onSelected: selected.add, glass: true));
    await tester.pumpAndSettle();
    final mouse = await tester.createGesture(kind: PointerDeviceKind.mouse);
    await mouse.addPointer(location: const Offset(300, 100));
    await mouse.moveTo(tester.getCenter(_destination(1)));
    await tester.pumpAndSettle();
    expect(selected, isEmpty);
    await mouse.down(tester.getCenter(_destination(1)));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(LiquidGlassHost.debugHeldCaptures, greaterThan(1));
    await mouse.up();
    await tester.pumpAndSettle();
    expect(selected, [1]);
    await mouse.moveTo(const Offset(300, 100));
    await mouse.removePointer();
    await tester.pumpAndSettle();
    expect(LiquidGlassHost.debugHeldCaptures, 1);
    await tester.pumpWidget(const SizedBox.shrink());
    expect(LiquidGlassHost.debugHeldCaptures, 0);
    expect(tester.takeException(), isNull);
  });

  testWidgets('rail hit slop reaches the rounded top and bottom ends', (
    tester,
  ) async {
    _setViewport(tester, const Size(768, 1024));
    final selected = <int>[];
    await tester.pumpWidget(_testApp(onSelected: selected.add, glass: true));
    await tester.pumpAndSettle();
    await tester.tap(_destination(1));
    await tester.pumpAndSettle();
    expect(selected, [1]);
    final bar = tester.getRect(find.byType(LiquidGlass));
    await tester.tapAt(Offset(bar.center.dx, bar.top - 4));
    await tester.pumpAndSettle();
    expect(selected, [1, 0]);
    await tester.tapAt(Offset(bar.center.dx, bar.bottom + 4));
    await tester.pumpAndSettle();
    expect(selected, [1, 0, 3]);
    await tester.pumpWidget(const SizedBox.shrink());
    expect(LiquidGlassHost.debugHeldCaptures, 0);
    expect(tester.takeException(), isNull);
  });

  testWidgets('rail keyboard traversal activates the focused destination', (
    tester,
  ) async {
    _setViewport(tester, const Size(768, 1024));
    final semantics = tester.ensureSemantics();
    final selected = <int>[];
    await tester.pumpWidget(_testApp(onSelected: selected.add));
    await tester.pumpAndSettle();
    await tester.sendKeyEvent(LogicalKeyboardKey.tab);
    await tester.sendKeyEvent(LogicalKeyboardKey.tab);
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pumpAndSettle();
    expect(selected, [1]);
    final agentsSemantics = find.byWidgetPredicate(
      (widget) => widget is Semantics && widget.properties.label == 'Agents',
    );
    expect(
      tester.getSemantics(agentsSemantics).flagsCollection.isSelected,
      Tristate.isTrue,
    );
    await tester.sendKeyEvent(LogicalKeyboardKey.tab);
    await tester.sendKeyEvent(LogicalKeyboardKey.space);
    await tester.pumpAndSettle();
    expect(selected, [1, 2]);
    semantics.dispose();
    expect(tester.takeException(), isNull);
  });

  testWidgets('rail keeps upright labels and safe areas with large RTL text', (
    tester,
  ) async {
    _setViewport(tester, const Size(768, 1024));
    final selected = <int>[];
    await tester.pumpWidget(
      _testApp(
        onSelected: selected.add,
        textScale: 2,
        direction: TextDirection.rtl,
        brightness: Brightness.dark,
        safePadding: const EdgeInsets.only(top: 30, bottom: 34),
        bottomInset: 300,
        glass: true,
      ),
    );
    await tester.pumpAndSettle();
    for (var index = 0; index < _destinations.length; index++) {
      final item = _destination(index);
      expect(item.hitTestable(), findsOneWidget);
      final itemRect = tester.getRect(item);
      expect(itemRect.top, greaterThanOrEqualTo(30));
      expect(itemRect.bottom, lessThanOrEqualTo(1024 - 300 - 34));
      final label = find.text(_destinations[index].label);
      final icon = find.byIcon(_destinations[index].icon);
      expect(
        tester.getCenter(label).dy,
        greaterThan(tester.getCenter(icon).dy),
      );
      expect(
        tester.getCenter(label).dx,
        closeTo(tester.getCenter(icon).dx, 0.1),
      );
    }
    await tester.tap(_destination(3));
    await tester.pumpAndSettle();
    expect(selected, [3]);
    await tester.pumpWidget(const SizedBox.shrink());
    expect(LiquidGlassHost.debugHeldCaptures, 0);
    expect(tester.takeException(), isNull);
  });

  testWidgets('shrinking the rail during a glass drag cancels selection', (
    tester,
  ) async {
    _setViewport(tester, const Size(768, 1024));
    final selected = <int>[];
    await tester.pumpWidget(_testApp(onSelected: selected.add, glass: true));
    await tester.pumpAndSettle();
    final dock = tester.element(find.byType(FlClashNavigationDock));
    final drag = await tester.startGesture(tester.getCenter(_destination(0)));
    await tester.pump(const Duration(milliseconds: 80));
    await drag.moveTo(tester.getCenter(_destination(3)));
    await tester.pump(const Duration(milliseconds: 100));
    expect(selected, isEmpty);
    expect(LiquidGlassHost.debugHeldCaptures, greaterThan(1));

    tester.view.physicalSize = const Size(768, 480);
    await tester.pumpWidget(
      _testApp(onSelected: selected.add, glass: true, bottomInset: 280),
    );
    await tester.pumpAndSettle();
    expect(tester.element(find.byType(FlClashNavigationDock)), same(dock));
    expect(selected, isEmpty);
    expect(LiquidGlassHost.debugHeldCaptures, 1);
    await drag.up();
    await tester.pumpAndSettle();
    expect(selected, isEmpty);
    expect(LiquidGlassHost.debugHeldCaptures, 1);

    final scrollable = find.descendant(
      of: find.byType(OmniNavigationRail),
      matching: find.byType(Scrollable),
    );
    await tester.drag(scrollable, const Offset(0, -220));
    await tester.pumpAndSettle();
    expect(selected, isEmpty);
    await tester.ensureVisible(_destination(3));
    await tester.pumpAndSettle();
    expect(_destination(3).hitTestable(), findsOneWidget);
    await tester.tap(_destination(3));
    await tester.pumpAndSettle();
    expect(selected, [3]);
    expect(LiquidGlassHost.debugHeldCaptures, 1);
    await tester.pumpWidget(const SizedBox.shrink());
    expect(LiquidGlassHost.debugHeldCaptures, 0);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'short rail scrolls without selecting and keeps the last tab reachable',
    (tester) async {
      _setViewport(tester, const Size(768, 480));
      final selected = <int>[];
      await tester.pumpWidget(
        _testApp(
          onSelected: selected.add,
          textScale: 2,
          direction: TextDirection.rtl,
          bottomInset: 280,
        ),
      );
      await tester.pumpAndSettle();
      final scrollable = find.descendant(
        of: find.byType(OmniNavigationRail),
        matching: find.byType(Scrollable),
      );
      expect(scrollable, findsOneWidget);
      await tester.drag(scrollable, const Offset(0, -220));
      await tester.pumpAndSettle();
      expect(selected, isEmpty);
      await tester.ensureVisible(_destination(3));
      await tester.pumpAndSettle();
      expect(_destination(3).hitTestable(), findsOneWidget);
      expect(tester.getRect(_destination(3)).bottom, lessThanOrEqualTo(200));
      await tester.tap(_destination(3));
      await tester.pumpAndSettle();
      expect(selected, [3]);
      expect(tester.takeException(), isNull);
    },
  );
}
