import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/flclash/popup.dart';

void main() {
  testWidgets('opening press slides between rows and selects only on release', (
    tester,
  ) async {
    final selected = <int>[];
    final extra = ValueNotifier(true);
    addTearDown(extra.dispose);
    await tester.pumpWidget(
      _harness(_RouteObserver(), selected.add, extra: extra),
    );
    final press = await tester.startGesture(
      tester.getCenter(find.byKey(const ValueKey('target'))),
    );
    await tester.pumpAndSettle();
    await press.moveTo(tester.getCenter(find.text('First action')));
    await tester.pump();
    expect(_highlighted(tester, 'First action'), isTrue);
    expect(_highlighted(tester, 'Refreshed action'), isFalse);
    expect(selected, isEmpty);

    await press.moveTo(tester.getCenter(find.text('Refreshed action')));
    await tester.pump();
    expect(_highlighted(tester, 'First action'), isFalse);
    expect(_highlighted(tester, 'Refreshed action'), isTrue);
    await press.up();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 250));
    expect(selected, isEmpty);
    expect(_sourceOpacity(tester), 0);
    await tester.pumpAndSettle();
    expect(selected, [2]);
    expect(find.text('Refreshed action'), findsNothing);
    expect(_sourceOpacity(tester), 1);
    expect(tester.takeException(), isNull);
  });

  testWidgets('cancelling the opening press clears the row without selecting', (
    tester,
  ) async {
    final selected = <int>[];
    await tester.pumpWidget(_harness(_RouteObserver(), selected.add));
    final press = await tester.startGesture(
      tester.getCenter(find.byKey(const ValueKey('target'))),
    );
    await tester.pumpAndSettle();
    await press.moveTo(tester.getCenter(find.text('First action')));
    await tester.pump();
    expect(_highlighted(tester, 'First action'), isTrue);
    await press.cancel();
    await tester.pumpAndSettle();
    expect(_highlighted(tester, 'First action'), isFalse);
    expect(selected, isEmpty);
    expect(find.text('First action'), findsOneWidget);
    await tester.tapAt(const Offset(20, 550));
    await tester.pumpAndSettle();
    expect(_sourceOpacity(tester), 1);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'moving outside the menu clears the row and release does not pick',
    (tester) async {
      final selected = <int>[];
      await tester.pumpWidget(_harness(_RouteObserver(), selected.add));
      final press = await tester.startGesture(
        tester.getCenter(find.byKey(const ValueKey('target'))),
      );
      await tester.pumpAndSettle();
      await press.moveTo(tester.getCenter(find.text('First action')));
      await tester.pump();
      expect(_highlighted(tester, 'First action'), isTrue);
      await press.moveTo(const Offset(20, 550));
      await tester.pump();
      expect(_highlighted(tester, 'First action'), isFalse);
      await press.up();
      await tester.pumpAndSettle();
      expect(selected, isEmpty);
      expect(find.text('First action'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('disabled entries neither highlight nor accept carried release', (
    tester,
  ) async {
    final selected = <int>[];
    final extra = ValueNotifier(true);
    addTearDown(extra.dispose);
    await tester.pumpWidget(
      _harness(
        _RouteObserver(),
        selected.add,
        extra: extra,
        disabledSecond: true,
      ),
    );
    final press = await tester.startGesture(
      tester.getCenter(find.byKey(const ValueKey('target'))),
    );
    await tester.pumpAndSettle();
    await press.moveTo(tester.getCenter(find.text('Refreshed action')));
    await tester.pump();
    expect(_highlighted(tester, 'Refreshed action'), isFalse);
    await press.up();
    await tester.pumpAndSettle();
    expect(selected, isEmpty);
    expect(find.text('First action'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('a new press in the menu selects the row it slides onto', (
    tester,
  ) async {
    final selected = <int>[];
    final extra = ValueNotifier(true);
    addTearDown(extra.dispose);
    await tester.pumpWidget(
      _harness(_RouteObserver(), selected.add, extra: extra),
    );
    await tester.tap(find.byKey(const ValueKey('target')));
    await tester.pumpAndSettle();
    final press = await tester.startGesture(
      tester.getCenter(find.text('First action')),
    );
    await tester.pump();
    expect(_highlighted(tester, 'First action'), isTrue);
    await press.moveTo(tester.getCenter(find.text('Refreshed action')));
    await tester.pump();
    expect(_highlighted(tester, 'Refreshed action'), isTrue);
    await press.up();
    await tester.pumpAndSettle();
    expect(selected, [2]);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'a scrolling menu keeps vertical drag ownership without picking',
    (tester) async {
      final selected = <int>[];
      await tester.pumpWidget(
        _harness(_RouteObserver(), selected.add, longMenu: true),
      );
      await tester.tap(find.byKey(const ValueKey('target')));
      await tester.pumpAndSettle();
      final scrollable = tester.state<ScrollableState>(find.byType(Scrollable));
      await tester.drag(find.text('Menu item 6'), const Offset(0, -220));
      await tester.pumpAndSettle();
      expect(scrollable.position.pixels, greaterThan(0));
      expect(selected, isEmpty);
      expect(find.byKey(const ValueKey('menu')), findsOneWidget);
      expect(_highlighted(tester, 'Menu item 6'), isFalse);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('pointer down opens from a 44 dp source before pointer up', (
    tester,
  ) async {
    var opened = 0;
    final selected = <int>[];
    final observer = _RouteObserver();
    await tester.pumpWidget(
      _harness(observer, selected.add, onOpened: () => opened++),
    );
    final target = find.byKey(const ValueKey('target'));
    final sourceElement = tester.element(target);
    expect(tester.getSize(target), const Size(44, 44));
    final source = tester.getRect(target);

    final press = await tester.startGesture(tester.getCenter(target));
    expect(opened, 1);
    expect(observer.popup, isNotNull);
    expect(
      observer.popup!.transitionDuration,
      const Duration(milliseconds: 450),
    );
    expect(
      observer.popup!.reverseTransitionDuration,
      const Duration(milliseconds: 500),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 30));
    expect(_sourceOpacity(tester), 0);
    expect(tester.element(target), same(sourceElement));
    // Pointer up in the original button must leave the popup open.
    await press.up();
    await tester.pumpAndSettle();
    expect(find.text('First action'), findsOneWidget);
    expect(selected, isEmpty);
    final card = tester.getRect(find.byKey(const ValueKey('menu')));
    expect(card.right, closeTo(source.right, .01));
    expect(card.top, closeTo(source.top - 8, .01));
    expect(card.width, 260);
    expect(tester.takeException(), isNull);
  });

  testWidgets('selection returns into the source before running its action', (
    tester,
  ) async {
    final selected = <int>[];
    await tester.pumpWidget(_harness(_RouteObserver(), selected.add));
    final target = find.byKey(const ValueKey('target'));
    final originalElement = tester.element(target);
    await tester.tap(target);
    await tester.pumpAndSettle();
    await tester.tap(find.text('First action'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 250));
    expect(selected, isEmpty);
    expect(_sourceOpacity(tester), 0);
    expect(find.text('First action'), findsOneWidget);
    await tester.pumpAndSettle();
    expect(selected, [1]);
    expect(find.text('First action'), findsNothing);
    expect(_sourceOpacity(tester), 1);
    expect(tester.element(target), same(originalElement));
    // Reusing the same source can open another route after the return.
    await tester.tap(target);
    await tester.pumpAndSettle();
    expect(find.text('First action'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('outside dismissal restores the source without selection', (
    tester,
  ) async {
    final selected = <int>[];
    await tester.pumpWidget(_harness(_RouteObserver(), selected.add));
    await tester.tap(find.byKey(const ValueKey('target')));
    await tester.pumpAndSettle();
    await tester.tapAt(const Offset(20, 550));
    await tester.pumpAndSettle();
    expect(selected, isEmpty);
    expect(find.text('First action'), findsNothing);
    expect(_sourceOpacity(tester), 1);
    expect(tester.takeException(), isNull);
  });

  testWidgets('interrupting the opening still gives the full return duration', (
    tester,
  ) async {
    final selected = <int>[];
    await tester.pumpWidget(_harness(_RouteObserver(), selected.add));
    await tester.tap(find.byKey(const ValueKey('target')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 60));
    await tester.tapAt(const Offset(20, 550));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.text('First action'), findsOneWidget);
    expect(_sourceOpacity(tester), 0);
    await tester.pumpAndSettle();
    expect(find.text('First action'), findsNothing);
    expect(_sourceOpacity(tester), 1);
    expect(selected, isEmpty);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'menu updates in place and fits safe insets on a narrow display',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final extra = ValueNotifier(false);
      addTearDown(extra.dispose);
      await tester.pumpWidget(_harness(_RouteObserver(), (_) {}, extra: extra));
      await tester.tap(find.byKey(const ValueKey('target')));
      await tester.pumpAndSettle();
      final initial = tester.getRect(find.byKey(const ValueKey('menu')));
      extra.value = true;
      await tester.pumpAndSettle();
      final expanded = tester.getRect(find.byKey(const ValueKey('menu')));
      expect(expanded.topRight, initial.topRight);
      expect(expanded.height, greaterThan(initial.height));
      expect(expanded.left, greaterThanOrEqualTo(16));
      expect(expanded.right, lessThanOrEqualTo(304));
      expect(expanded.bottom, lessThanOrEqualTo(624));
      expect(find.text('Refreshed action'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
}

double _sourceOpacity(WidgetTester tester) => tester
    .widget<Opacity>(
      find
          .ancestor(
            of: find.byKey(const ValueKey('target')),
            matching: find.byType(Opacity),
          )
          .first,
    )
    .opacity;

bool _highlighted(WidgetTester tester, String text) {
  final item = find.ancestor(
    of: find.text(text),
    matching: find.byType(FlClashPopupMenuItem<int>),
  );
  final decorated = tester.widget<DecoratedBox>(
    find.descendant(of: item, matching: find.byType(DecoratedBox)).first,
  );
  return (decorated.decoration as ShapeDecoration).color != null;
}

class _RouteObserver extends NavigatorObserver {
  PopupRoute<dynamic>? popup;

  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) {
    if (route is PopupRoute) popup = route;
  }
}

Widget _harness(
  _RouteObserver observer,
  ValueChanged<int> onSelected, {
  VoidCallback? onOpened,
  ValueNotifier<bool>? extra,
  bool disabledSecond = false,
  bool longMenu = false,
}) => MaterialApp(
  theme: AppTheme.lightTheme,
  navigatorObservers: [observer],
  home: Scaffold(
    body: Padding(
      padding: const EdgeInsets.only(top: 40, right: 16),
      child: Align(
        alignment: Alignment.topRight,
        child: FlClashPopupBox<int>(
          onOpened: onOpened,
          onSelected: onSelected,
          targetBuilder: (context, open) => SizedBox.square(
            dimension: 44,
            child: IconButton(
              key: const ValueKey('target'),
              style: IconButton.styleFrom(
                backgroundColor: Colors.white,
                padding: EdgeInsets.zero,
                minimumSize: const Size.square(44),
                maximumSize: const Size.square(44),
                tapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
              onPressed: open,
              icon: const Icon(Icons.more_horiz),
            ),
          ),
          popupBuilder: (context) {
            Widget content(bool refreshed) => SizedBox(
              key: const ValueKey('menu'),
              width: 260,
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(8),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const FlClashPopupMenuItem<int>(
                      value: 1,
                      child: Text('First action'),
                    ),
                    if (refreshed)
                      FlClashPopupMenuItem<int>(
                        value: 2,
                        enabled: !disabledSecond,
                        child: const Text('Refreshed action'),
                      ),
                    if (longMenu)
                      for (var index = 3; index <= 20; index++)
                        FlClashPopupMenuItem<int>(
                          value: index,
                          child: Text('Menu item $index'),
                        ),
                  ],
                ),
              ),
            );
            return extra == null
                ? content(false)
                : ValueListenableBuilder<bool>(
                    valueListenable: extra,
                    builder: (context, value, child) => content(value),
                  );
          },
        ),
      ),
    ),
  ),
);
