import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:ui/widgets/omni_sheet_motion.dart';

const _surfaceKey = ValueKey('moving-surface');

Future<void> _open(
  WidgetTester tester, {
  Widget? body,
  bool glass = true,
  bool reducedMotion = false,
  bool fitsContent = false,
  bool settle = true,
  AnimationStyle? animationStyle,
}) async {
  tester.view.physicalSize = const Size(400, 800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(
    MaterialApp(
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(disableAnimations: reducedMotion),
        child: LiquidGlassScope(
          enabled: glass,
          child: LiquidGlassHost(enabled: glass, child: child!),
        ),
      ),
      home: Builder(
        builder: (context) => Scaffold(
          body: TextButton(
            onPressed: () => showOmniGlassCardSheet<void>(
              context: context,
              fitsContent: fitsContent,
              animationStyle: animationStyle,
              builder: (context) => SizedBox(
                key: _surfaceKey,
                width: double.infinity,
                child: body ?? const Text('Content'),
              ),
            ),
            child: const Text('Open'),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('Open'));
  if (settle) {
    await tester.pumpAndSettle();
  } else {
    await tester.pump();
  }
}

void main() {
  testWidgets('upward elasticity leaves the completed route animation idle', (
    tester,
  ) async {
    await _open(tester);
    final presentation = tester
        .widget<OmniSheetMotion>(find.byType(OmniSheetMotion))
        .presentation;
    final statuses = <AnimationStatus>[];
    var updates = 0;
    presentation.addStatusListener(statuses.add);
    presentation.addListener(() => updates++);
    final drag = await tester.startGesture(const Offset(200, 420));
    await drag.moveBy(const Offset(0, -30));
    await drag.moveBy(const Offset(0, -130));
    await tester.pump();
    await drag.up();
    await tester.pumpAndSettle();
    expect(presentation.value, 1);
    expect(presentation.status, AnimationStatus.completed);
    expect(presentation.isAnimating, false);
    expect(statuses, isEmpty);
    expect(updates, 0);
  });

  testWidgets(
    'opening eases with spring while explicit transition style is kept',
    (tester) async {
      await _open(tester, settle: false);
      await tester.pump(const Duration(milliseconds: 100));
      final first = tester.getRect(find.byKey(_surfaceKey)).top;
      await tester.pump(const Duration(milliseconds: 100));
      final second = tester.getRect(find.byKey(_surfaceKey)).top;
      await tester.pump(const Duration(milliseconds: 100));
      final third = tester.getRect(find.byKey(_surfaceKey)).top;
      expect(first - second, greaterThan(second - third));
      await tester.pumpAndSettle();
      Navigator.of(tester.element(find.byType(BottomSheet))).pop();
      await tester.pumpAndSettle();
      await _open(
        tester,
        settle: false,
        animationStyle: const AnimationStyle(
          duration: Duration(milliseconds: 400),
          curve: Curves.linear,
        ),
      );
      await tester.pump(const Duration(milliseconds: 100));
      final linearFirst = tester.getRect(find.byKey(_surfaceKey)).top;
      await tester.pump(const Duration(milliseconds: 100));
      final linearSecond = tester.getRect(find.byKey(_surfaceKey)).top;
      expect(linearFirst - linearSecond, closeTo(100, 0.1));
      await tester.pumpAndSettle();
    },
  );
  for (final glass in [false, true]) {
    testWidgets(
      'accepted upward drag stretches and springs back, glass=$glass',
      (tester) async {
        await _open(tester, glass: glass);
        final resting = tester.getRect(find.byKey(_surfaceKey));
        final press = await tester.startGesture(const Offset(200, 420));
        await tester.pump(const Duration(milliseconds: 100));
        expect(tester.getRect(find.byKey(_surfaceKey)), resting);
        await press.moveBy(const Offset(0, -30));
        await press.moveBy(const Offset(0, -100));
        await tester.pump();
        final first = tester.getRect(find.byKey(_surfaceKey));
        expect(first.top, glass ? lessThan(resting.top - 20) : resting.top);
        await press.moveBy(const Offset(0, -100));
        await tester.pump();
        final second = tester.getRect(find.byKey(_surfaceKey));
        if (glass) {
          expect(first.top - second.top, lessThan(resting.top - first.top));
        } else {
          expect(second, resting);
        }
        expect(find.byType(LiquidGlass), glass ? findsOneWidget : findsNothing);
        expect(
          tester.widget<BottomSheet>(find.byType(BottomSheet)).enableDrag,
          false,
        );
        await press.up();
        await tester.pumpAndSettle();
        expect(
          tester.getRect(find.byKey(_surfaceKey)).top,
          closeTo(resting.top, 0.2),
        );
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets('downward drag follows finger and cancelled drag restores pane', (
    tester,
  ) async {
    await _open(tester);
    final resting = tester.getRect(find.byKey(_surfaceKey));
    final drag = await tester.startGesture(const Offset(200, 420));
    await drag.moveBy(const Offset(0, 30));
    await drag.moveBy(const Offset(0, 80));
    await tester.pump();
    expect(
      tester.getRect(find.byKey(_surfaceKey)).top - resting.top,
      closeTo(110, 1),
    );
    final veil = tester.widget<LiquidGlassVeil>(find.byType(LiquidGlassVeil));
    expect(veil.color.value!.a, lessThan(0.32));
    await drag.cancel();
    await tester.pumpAndSettle();
    expect(
      tester.getRect(find.byKey(_surfaceKey)).top,
      closeTo(resting.top, 0.5),
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'content scroll owns mid-list drag; edge reverses into scrolling',
    (tester) async {
      await _open(
        tester,
        body: SizedBox(
          height: 352,
          child: ListView.builder(
            itemExtent: 48,
            itemCount: 40,
            itemBuilder: (_, index) => Text('Item $index'),
          ),
        ),
      );
      final scroll = tester
          .state<ScrollableState>(find.byType(Scrollable))
          .position;
      final resting = tester.getRect(find.byKey(_surfaceKey));
      scroll.jumpTo(150);
      await tester.pump();
      final fromMiddle = await tester.startGesture(const Offset(200, 550));
      await fromMiddle.moveBy(const Offset(0, 30));
      await fromMiddle.moveBy(const Offset(0, 240));
      await tester.pump();
      expect(scroll.pixels, 0);
      expect(tester.getRect(find.byKey(_surfaceKey)), resting);
      await fromMiddle.up();
      await tester.pumpAndSettle();

      final fromEdge = await tester.startGesture(const Offset(200, 520));
      await fromEdge.moveBy(const Offset(0, 30));
      await fromEdge.moveBy(const Offset(0, 100));
      await tester.pump();
      expect(
        tester.getRect(find.byKey(_surfaceKey)).top,
        greaterThan(resting.top + 50),
      );
      expect(scroll.pixels, 0);
      final pull = tester.getRect(find.byKey(_surfaceKey)).top - resting.top;
      await fromEdge.moveBy(Offset(0, -pull));
      await tester.pump();
      expect(
        tester.getRect(find.byKey(_surfaceKey)).top,
        closeTo(resting.top, 1),
      );
      await fromEdge.moveBy(const Offset(0, -80));
      await tester.pump();
      expect(
        tester.getRect(find.byKey(_surfaceKey)).top,
        closeTo(resting.top, 1),
      );
      expect(scroll.pixels, greaterThan(20));
      await fromEdge.up();
      await tester.pumpAndSettle();
      expect(find.byType(BottomSheet), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'explicit scroll controller retains scrolling and edge dismissal',
    (tester) async {
      final controller = ScrollController();
      addTearDown(controller.dispose);
      await _open(
        tester,
        body: SizedBox(
          height: 352,
          child: ListView.builder(
            controller: controller,
            itemExtent: 48,
            itemCount: 40,
            itemBuilder: (_, index) => Text('Item $index'),
          ),
        ),
      );
      await tester.drag(find.byType(ListView), const Offset(0, -160));
      await tester.pumpAndSettle();
      expect(controller.offset, greaterThan(0));
      controller.jumpTo(0);
      await tester.pump();
      await tester.drag(find.byType(ListView), const Offset(0, 300));
      await tester.pumpAndSettle();
      expect(find.byType(BottomSheet), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('field press and selection never deform the sheet', (
    tester,
  ) async {
    final field = TextEditingController(text: 'select these words');
    addTearDown(field.dispose);
    await _open(tester, body: TextField(controller: field));
    final resting = tester.getRect(find.byKey(_surfaceKey));
    await tester.longPress(find.byType(TextField));
    await tester.pumpAndSettle();
    expect(tester.getRect(find.byKey(_surfaceKey)), resting);
    expect(field.selection.isCollapsed, false);
    expect(tester.takeException(), isNull);
  });

  for (final explicitController in [false, true]) {
    testWidgets(
      'cancelled edge drag never dismisses, explicit=$explicitController',
      (tester) async {
        final controller = explicitController ? ScrollController() : null;
        addTearDown(() => controller?.dispose());
        await _open(
          tester,
          body: SizedBox(
            height: 352,
            child: ListView.builder(
              controller: controller,
              itemExtent: 48,
              itemCount: 40,
              itemBuilder: (_, index) => Text('Item $index'),
            ),
          ),
        );
        final resting = tester.getRect(find.byKey(_surfaceKey));
        final drag = await tester.startGesture(const Offset(200, 480));
        await drag.moveBy(const Offset(0, 30));
        await drag.moveBy(const Offset(0, 220));
        await tester.pump();
        expect(
          tester.getRect(find.byKey(_surfaceKey)).top,
          greaterThan(resting.top + 180),
        );
        await drag.cancel();
        await tester.pumpAndSettle();
        expect(find.byType(BottomSheet), findsOneWidget);
        expect(tester.getRect(find.byKey(_surfaceKey)).top, resting.top);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets('reduced motion suppresses stretch but keeps swipe dismissal', (
    tester,
  ) async {
    await _open(tester, reducedMotion: true);
    final resting = tester.getRect(find.byKey(_surfaceKey));
    final drag = await tester.startGesture(const Offset(200, 420));
    await drag.moveBy(const Offset(0, -30));
    await drag.moveBy(const Offset(0, -130));
    await tester.pump();
    expect(tester.getRect(find.byKey(_surfaceKey)), resting);
    await drag.up();
    await tester.pumpAndSettle();
    await tester.dragFrom(const Offset(200, 420), const Offset(0, 300));
    await tester.pumpAndSettle();
    expect(find.byType(BottomSheet), findsNothing);
  });

  testWidgets('fit-content card removes the reserved half-screen blank', (
    tester,
  ) async {
    await _open(tester, fitsContent: true);
    expect(tester.getSize(find.byType(BottomSheet)).height, lessThan(150));
    expect(tester.getRect(find.byType(BottomSheet)).bottom, 800);
    expect(find.byType(LiquidGlass), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('manual extent controller returns reversed excess to its owner', (
    tester,
  ) async {
    final controller = OmniSheetElasticController(tester);
    addTearDown(controller.dispose);
    expect(controller.dragBy(100), 100);
    expect(controller.value, inExclusiveRange(0, 100));
    expect(controller.pull, closeTo(100, 0.001));
    expect(controller.dragBy(-160), closeTo(-100, 0.001));
    expect(controller.value, 0);
    controller.dragBy(100);
    controller.release();
    await tester.pumpAndSettle();
    expect(controller.value, 0);
  });
}
