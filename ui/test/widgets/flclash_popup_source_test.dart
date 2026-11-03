import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/flclash/popup.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/omni_menu.dart';
import 'package:ui/widgets/glass_popup.dart';

const _group = ValueKey('complete source group');
const _menu = ValueKey('menu trigger');
const _panel = ValueKey('popup panel');

enum _Owner { route, overlay }

class _Harness {
  _Harness(this.owner);

  final _Owner owner;
  final focus = FocusNode();
  final showSource = ValueNotifier(true);
  late BuildContext sourceContext;
  late BuildContext popupContext;
  OverlayGlassPopupHandle<int>? handle;
  Future<int?>? result;
  var searchCount = 0;

  void dispose() {
    focus.dispose();
    showSource.dispose();
  }

  Widget build({
    bool reducedMotion = false,
    bool nested = false,
    bool glass = false,
  }) {
    Widget page() => Scaffold(
      body: Column(
        children: [
          TextField(focusNode: focus),
          ValueListenableBuilder<bool>(
            valueListenable: showSource,
            builder: (_, visible, _) => visible
                ? Align(
                    alignment: Alignment.topRight,
                    child: Padding(
                      padding: const EdgeInsets.only(right: 20, top: 20),
                      child: FlClashPopupSource(
                        builder: (context) {
                          sourceContext = context;
                          return Material(
                            key: _group,
                            color: Colors.blue,
                            borderRadius: BorderRadius.circular(22),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                GestureDetector(
                                  behavior: HitTestBehavior.opaque,
                                  onTap: () => searchCount++,
                                  child: const SizedBox(
                                    width: 44,
                                    height: 44,
                                    child: Icon(Icons.search),
                                  ),
                                ),
                                GestureDetector(
                                  key: _menu,
                                  behavior: HitTestBehavior.opaque,
                                  onTap: open,
                                  child: const SizedBox(
                                    width: 44,
                                    height: 44,
                                    child: Icon(Icons.more_horiz),
                                  ),
                                ),
                              ],
                            ),
                          );
                        },
                      ),
                    ),
                  )
                : const SizedBox.shrink(),
          ),
        ],
      ),
    );
    return MaterialApp(
      theme: AppTheme.lightTheme,
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(disableAnimations: reducedMotion),
        child: LiquidGlassScope(
          enabled: glass,
          child: LiquidGlassHost(enabled: glass, child: child!),
        ),
      ),
      home: nested
          ? Center(
              child: SizedBox(
                width: 500,
                height: 400,
                child: Navigator(
                  onGenerateRoute: (_) =>
                      MaterialPageRoute<void>(builder: (_) => page()),
                ),
              ),
            )
          : page(),
    );
  }

  void open({bool root = false, Rect? placementAnchor}) {
    final anchor = glassPopupAnchorFromContext(sourceContext)!;
    Widget panel() => Builder(
      builder: (context) {
        popupContext = context;
        return SizedBox(
          key: _panel,
          width: 220,
          height: 160,
          child: Material(
            type: MaterialType.transparency,
            child: OmniMenuSurface(
              padding: EdgeInsets.zero,
              child: TextButton(
                onPressed: () => select(7),
                child: const Text('Select value'),
              ),
            ),
          ),
        );
      },
    );
    if (owner == _Owner.route) {
      result = showGlassPopup<int>(
        context: sourceContext,
        sourceContext: sourceContext,
        placementAnchor: placementAnchor,
        anchor: anchor,
        useRootNavigator: root,
        child: panel(),
      );
    } else {
      handle = showOverlayGlassPopup<int>(
        context: sourceContext,
        sourceContext: sourceContext,
        placementAnchor: placementAnchor,
        anchor: anchor,
        useRootOverlay: root,
        builder: (_) => panel(),
      );
      result = handle!.future;
    }
  }

  void select([int? value]) {
    if (owner == _Owner.route) {
      Navigator.of(popupContext).pop(value);
    } else {
      unawaited(handle!.dismiss(value));
    }
  }
}

double _sourceOpacity(WidgetTester tester) => tester
    .widget<Opacity>(
      find
          .descendant(
            of: find.byType(FlClashPopupSource),
            matching: find.byType(Opacity),
          )
          .first,
    )
    .opacity;

void main() {
  for (final owner in _Owner.values) {
    testWidgets('$owner captures and returns the entire grouped source', (
      tester,
    ) async {
      final harness = _Harness(owner);
      addTearDown(harness.dispose);
      await tester.pumpWidget(harness.build());
      final source = tester.getRect(find.byKey(_group));
      await tester.tap(find.byKey(_menu));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 80));
      final transition = tester.widget<FlClashPopupTransition>(
        find.byType(FlClashPopupTransition),
      );
      expect(transition.anchor, source);
      final pixelRatio = MediaQuery.devicePixelRatioOf(harness.sourceContext);
      expect(transition.source!.image.value!.width / pixelRatio, 88);
      expect(transition.source!.image.value!.height / pixelRatio, 44);
      expect(_sourceOpacity(tester), 0);
      final sourceBlock = tester.widget<IgnorePointer>(
        find
            .descendant(
              of: find.byType(FlClashPopupSource),
              matching: find.byType(IgnorePointer),
            )
            .first,
      );
      expect(sourceBlock.ignoring, isTrue);
      await tester.pumpAndSettle();
      harness.select(7);
      var closed = false;
      final completion = owner == _Owner.route
          ? harness.result!.then((_) => closed = true)
          : harness.handle!.closed.then((_) => closed = true);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      expect(closed, isFalse);
      expect(_sourceOpacity(tester), 0);
      await tester.pumpAndSettle();
      await completion;
      expect(await harness.result, 7);
      expect(_sourceOpacity(tester), 1);
      await tester.tapAt(source.centerLeft + const Offset(22, 0));
      expect(harness.searchCount, 1);
      expect(tester.takeException(), isNull);
    });

    testWidgets('$owner glass morph owns one pane through open and return', (
      tester,
    ) async {
      final harness = _Harness(owner);
      addTearDown(harness.dispose);
      await tester.pumpWidget(harness.build(glass: true));
      harness.open();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 80));
      expect(LiquidGlassHost.debugHeldCaptures, 1);
      expect(LiquidGlassHost.debugLastCapture!.panes, hasLength(1));
      expect(
        LiquidGlassSurfaceScope.of(
          tester.element(find.byType(OmniMenuSurface)),
        ),
        isTrue,
      );
      expect(
        find.descendant(
          of: find.byType(OmniMenuSurface),
          matching: find.byType(DecoratedBox),
        ),
        findsNothing,
        reason: 'The content must not cover the renderer with an opaque card',
      );
      await tester.pumpAndSettle();
      expect(LiquidGlassHost.debugHeldCaptures, 1);
      harness.select();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 150));
      expect(LiquidGlassHost.debugHeldCaptures, 1);
      await tester.pumpAndSettle();
      expect(_sourceOpacity(tester), 1);
      expect(LiquidGlassHost.debugHeldCaptures, 0);
      expect(tester.takeException(), isNull);
    });

    testWidgets('$owner uses destination overlay coordinates when nested', (
      tester,
    ) async {
      final harness = _Harness(owner);
      addTearDown(harness.dispose);
      await tester.pumpWidget(harness.build(nested: true));
      final source = tester.getRect(find.byKey(_group));
      harness.open(root: true);
      await tester.pumpAndSettle();
      final transition = tester.widget<FlClashPopupTransition>(
        find.byType(FlClashPopupTransition),
      );
      expect(transition.anchor, source);
      final panel = tester.getRect(find.byKey(_panel));
      expect(panel.right, source.right);
      expect(panel.top, source.top);
      harness.select();
      await tester.pumpAndSettle();
      expect(await harness.result, isNull);
      expect(tester.takeException(), isNull);
    });

    testWidgets('$owner can align a panel independently of its source', (
      tester,
    ) async {
      final harness = _Harness(owner);
      addTearDown(harness.dispose);
      await tester.pumpWidget(harness.build());
      final source = tester.getRect(find.byKey(_group));
      final alignment = source.shift(const Offset(-12, 0));
      harness.open(placementAnchor: alignment);
      await tester.pumpAndSettle();
      final transition = tester.widget<FlClashPopupTransition>(
        find.byType(FlClashPopupTransition),
      );
      expect(transition.anchor, source);
      expect(tester.getRect(find.byKey(_panel)).right, alignment.right);
      harness.select();
      await tester.pumpAndSettle();
      expect(_sourceOpacity(tester), 1);
      expect(tester.takeException(), isNull);
    });

    testWidgets('$owner safely returns after its source is removed', (
      tester,
    ) async {
      final harness = _Harness(owner);
      addTearDown(harness.dispose);
      await tester.pumpWidget(harness.build());
      harness.open();
      await tester.pumpAndSettle();
      harness.showSource.value = false;
      await tester.pump();
      harness.select();
      await tester.pumpAndSettle();
      expect(find.byKey(_panel), findsNothing);
      expect(await harness.result, isNull);
      expect(tester.takeException(), isNull);
    });

    testWidgets('$owner can reopen after repeated interrupted returns', (
      tester,
    ) async {
      final harness = _Harness(owner);
      addTearDown(harness.dispose);
      await tester.pumpWidget(harness.build(glass: true));
      for (var cycle = 0; cycle < 3; cycle++) {
        harness.open();
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 30));
        final transition = tester.widget<FlClashPopupTransition>(
          find.byType(FlClashPopupTransition),
        );
        expect(transition.source!.image.value, isNotNull);
        expect(_sourceOpacity(tester), 0);
        harness.select();
        await tester.pumpAndSettle();
        expect(find.byKey(_panel), findsNothing);
        expect(_sourceOpacity(tester), 1);
        expect(LiquidGlassHost.debugHeldCaptures, 0);
        expect(await harness.result, isNull);
      }
      expect(tester.takeException(), isNull);
    });

    testWidgets('$owner honors reduced motion and restores its source', (
      tester,
    ) async {
      final harness = _Harness(owner);
      addTearDown(harness.dispose);
      await tester.pumpWidget(harness.build(reducedMotion: true));
      harness.open();
      await tester.pumpAndSettle();
      expect(find.byKey(_panel), findsOneWidget);
      harness.select(7);
      await tester.pump();
      await tester.pump();
      expect(find.byKey(_panel), findsNothing);
      expect(_sourceOpacity(tester), 1);
      expect(await harness.result, 7);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets(
    'overlay retains input focus and dismissal waits for full return',
    (tester) async {
      final harness = _Harness(_Owner.overlay);
      addTearDown(harness.dispose);
      await tester.pumpWidget(harness.build());
      await tester.showKeyboard(find.byType(TextField));
      expect(tester.testTextInput.isVisible, isTrue);
      harness.open();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 80));
      expect(harness.focus.hasFocus, isTrue);
      expect(tester.testTextInput.isVisible, isTrue);
      final first = harness.handle!.dismiss(7);
      final again = harness.handle!.dismiss(9);
      expect(again, same(first));
      expect(await harness.handle!.future, 7);
      var closed = false;
      unawaited(first.then((_) => closed = true));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      expect(closed, isFalse);
      expect(harness.handle!.isOpen, isTrue);
      await tester.pumpAndSettle();
      await first;
      await harness.handle!.closed;
      expect(closed, isTrue);
      expect(harness.handle!.isOpen, isFalse);
      expect(harness.focus.hasFocus, isTrue);
      expect(tester.testTextInput.isVisible, isTrue);
      harness.open();
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<FlClashPopupTransition>(find.byType(FlClashPopupTransition))
            .source,
        isNotNull,
        reason: 'A completed menu releases its source for the next submenu',
      );
      harness.select();
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
  );
}
