import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/flclash/tab_view.dart';

void main() {
  for (final axis in Axis.values) {
    group('$axis', () {
      testWidgets(
        'cold destination is laid out before its slide clock starts',
        (tester) async {
          final created = <int>[];
          final selected = ValueNotifier(0);
          addTearDown(selected.dispose);
          await tester.pumpWidget(_harness(selected, created, {}, axis: axis));
          selected.value = 3;
          await tester.pump();
          final prepared = find.byKey(
            const ValueKey('page-3'),
            skipOffstage: false,
          );
          expect(created, [0, 3]);
          expect(_start(tester.getRect(_page(0)), axis), 0);
          expect(tester.getSize(prepared), tester.getSize(_page(0)));
          expect(
            _start(tester.getRect(prepared), axis),
            _extent(tester.getSize(_page(0)), axis),
          );
          // Reversing before the first movement cancels admission and does not visit
          // either intervening page while the prepared owner remains cached.
          selected.value = 0;
          await tester.pumpAndSettle();
          expect(created, [0, 3]);
          expect(_start(tester.getRect(_page(0)), axis), 0);
          expect(tester.takeException(), isNull);
        },
      );

      for (final destination in [1, 3]) {
        testWidgets(
          '${destination == 1 ? 'adjacent' : 'cross-tab'} selection slides both pages together',
          (tester) async {
            final created = <int>[];
            final active = <int, bool>{};
            final selected = ValueNotifier(0);
            addTearDown(selected.dispose);
            await tester.pumpWidget(
              _harness(selected, created, active, axis: axis),
            );
            await tester.enterText(_field(0), 'keep this query');
            final originalState = tester.state(_field(0));
            expect(created, [0]);
            final extent = _extent(tester.getSize(_page(0)), axis);

            selected.value = destination;
            await tester.pump();
            await tester.pump();
            await tester.pump(const Duration(milliseconds: 90));
            expect(created.toSet(), {0, destination});
            expect(active[0], isFalse);
            expect(active[destination], isTrue);
            final leaving = tester.getRect(_page(0));
            final arriving = tester.getRect(_page(destination));
            expect(_start(leaving, axis), lessThan(0));
            expect(_start(arriving, axis), greaterThan(0));
            expect(_end(leaving, axis), closeTo(_start(arriving, axis), .01));
            expect(
              _start(leaving, axis),
              closeTo(-extent * Curves.easeOut.transform(90 / 300), .01),
            );
            await tester.pumpAndSettle();
            expect(
              find.text('Page $destination').hitTestable(),
              findsOneWidget,
            );

            selected.value = 0;
            await tester.pump();
            await tester.pump();
            await tester.pump(const Duration(milliseconds: 90));
            expect(
              _start(tester.getRect(_page(destination)), axis),
              greaterThan(0),
            );
            expect(_start(tester.getRect(_page(0)), axis), lessThan(0));
            expect(
              _end(tester.getRect(_page(0)), axis),
              closeTo(_start(tester.getRect(_page(destination)), axis), .01),
            );
            await tester.pumpAndSettle();
            expect(created, [0, destination]);
            expect(tester.state(_field(0)), same(originalState));
            expect(find.text('keep this query'), findsOneWidget);
            expect(active[destination], isFalse);
            expect(tester.takeException(), isNull);
          },
        );
      }

      testWidgets('RTL mirrors only horizontal page motion', (tester) async {
        final selected = ValueNotifier(0);
        addTearDown(selected.dispose);
        await tester.pumpWidget(
          _harness(selected, [], {}, axis: axis, rtl: true),
        );
        selected.value = 3;
        await tester.pump();
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 90));
        if (axis == Axis.horizontal) {
          expect(_start(tester.getRect(_page(0)), axis), greaterThan(0));
          expect(_start(tester.getRect(_page(3)), axis), lessThan(0));
          expect(
            _end(tester.getRect(_page(3)), axis),
            closeTo(_start(tester.getRect(_page(0)), axis), .01),
          );
        } else {
          expect(_start(tester.getRect(_page(0)), axis), lessThan(0));
          expect(_start(tester.getRect(_page(3)), axis), greaterThan(0));
          expect(
            _end(tester.getRect(_page(0)), axis),
            closeTo(_start(tester.getRect(_page(3)), axis), .01),
          );
        }
        await tester.pumpAndSettle();
        expect(find.text('Page 3').hitTestable(), findsOneWidget);
        expect(tester.takeException(), isNull);
      });

      testWidgets(
        'reversing a warped pair keeps both fractional page positions',
        (tester) async {
          final created = <int>[];
          final active = <int, bool>{};
          final selected = ValueNotifier(0);
          addTearDown(selected.dispose);
          await tester.pumpWidget(
            _harness(selected, created, active, axis: axis),
          );
          selected.value = 3;
          await tester.pump();
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 75));
          final beforeReverse = [
            tester.getRect(_page(0)),
            tester.getRect(_page(3)),
          ];
          selected.value = 0;
          await tester.pump();
          await tester.pump();
          expect(tester.getRect(_page(0)), beforeReverse[0]);
          expect(tester.getRect(_page(3)), beforeReverse[1]);
          await tester.pump(const Duration(milliseconds: 40));
          expect(
            _start(tester.getRect(_page(0)), axis),
            greaterThan(_start(beforeReverse[0], axis)),
          );
          final beforeForward = [
            tester.getRect(_page(0)),
            tester.getRect(_page(3)),
          ];
          selected.value = 3;
          await tester.pump();
          await tester.pump();
          expect(tester.getRect(_page(0)), beforeForward[0]);
          expect(tester.getRect(_page(3)), beforeForward[1]);
          await tester.pump(const Duration(milliseconds: 40));
          expect(
            _start(tester.getRect(_page(0)), axis),
            lessThan(_start(beforeForward[0], axis)),
          );
          await tester.pumpAndSettle();
          expect(created, [0, 3]);
          expect(find.text('Page 3').hitTestable(), findsOneWidget);
          expect(_activePages(active), [3]);
          expect(tester.takeException(), isNull);
        },
      );

      testWidgets(
        'only the latest queued third tab starts after the visible pair',
        (tester) async {
          final created = <int>[];
          final active = <int, bool>{};
          final selected = ValueNotifier(0);
          addTearDown(selected.dispose);
          await tester.pumpWidget(
            _harness(selected, created, active, axis: axis),
          );
          selected.value = 3;
          await tester.pump();
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 75));
          for (final index in [1, 2]) {
            final before = [tester.getRect(_page(0)), tester.getRect(_page(3))];
            selected.value = index;
            await tester.pump();
            await tester.pump();
            expect(tester.getRect(_page(0)), before[0]);
            expect(tester.getRect(_page(3)), before[1]);
            expect(created, [0, 3]);
            expect(_activePages(active), isEmpty);
          }
          await tester.pumpAndSettle();
          expect(created, [0, 3, 2]);
          expect(find.text('Page 2').hitTestable(), findsOneWidget);
          expect(_activePages(active), [2]);
          expect(tester.takeException(), isNull);
        },
      );

      testWidgets(
        'returning to a visible page cancels all queued destinations',
        (tester) async {
          final created = <int>[];
          final active = <int, bool>{};
          final selected = ValueNotifier(0);
          addTearDown(selected.dispose);
          await tester.pumpWidget(
            _harness(selected, created, active, axis: axis),
          );
          for (final index in [3, 1, 2, 0, 3, 1, 0]) {
            selected.value = index;
            await tester.pump();
            await tester.pump();
            await tester.pump(const Duration(milliseconds: 30));
          }
          await tester.pumpAndSettle();
          expect(created, [0, 3]);
          expect(find.text('Page 0').hitTestable(), findsOneWidget);
          expect(_activePages(active), [0]);
          expect(tester.takeException(), isNull);
        },
      );

      testWidgets('reduced motion selects the destination immediately', (
        tester,
      ) async {
        final created = <int>[];
        final selected = ValueNotifier(0);
        addTearDown(selected.dispose);
        await tester.pumpWidget(
          _harness(selected, created, {}, axis: axis, reducedMotion: true),
        );
        selected.value = 3;
        await tester.pump();
        await tester.pump();
        await tester.pump();
        await tester.pump();
        expect(find.text('Page 3').hitTestable(), findsOneWidget);
        expect(created, [0, 3]);
        expect(_start(tester.getRect(_page(3)), axis), 0);
        expect(tester.binding.hasScheduledFrame, isFalse);
      });

      testWidgets(
        'enabling reduced motion cancels the current flight and queue',
        (tester) async {
          final created = <int>[];
          final active = <int, bool>{};
          final selected = ValueNotifier(0);
          addTearDown(selected.dispose);
          await tester.pumpWidget(
            _harness(selected, created, active, axis: axis),
          );
          selected.value = 3;
          await tester.pump();
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 75));
          selected.value = 1;
          await tester.pump();
          await tester.pump();
          await tester.pumpWidget(
            _harness(
              selected,
              created,
              active,
              axis: axis,
              reducedMotion: true,
            ),
          );
          await tester.pumpAndSettle();
          expect(find.text('Page 1').hitTestable(), findsOneWidget);
          expect(_start(tester.getRect(_page(1)), axis), 0);
          expect(created, [0, 3, 1]);
          expect(_activePages(active), [1]);
          expect(tester.takeException(), isNull);
        },
      );

      for (final queuedThirdTab in [false, true]) {
        testWidgets(
          'changing direction during a warped slide settles the latest tab '
          '(queuedThirdTab=$queuedThirdTab)',
          (tester) async {
            tester.view.devicePixelRatio = 1;
            tester.view.physicalSize = axis == Axis.horizontal
                ? const Size(540, 900)
                : const Size(900, 540);
            addTearDown(tester.view.resetDevicePixelRatio);
            addTearDown(tester.view.resetPhysicalSize);
            final created = <int>[];
            final active = <int, bool>{};
            final selected = ValueNotifier(0);
            addTearDown(selected.dispose);
            await tester.pumpWidget(
              _harness(selected, created, active, axis: axis),
            );
            await tester.enterText(_field(0), 'keep across navigation layouts');
            final fieldState = tester.state(_field(0));
            final tabState = tester.state(find.byType(FlClashTabView));

            selected.value = 3;
            await tester.pump();
            await tester.pump();
            await tester.pump(const Duration(milliseconds: 75));
            expect(_start(tester.getRect(_page(0)), axis), lessThan(0));
            if (queuedThirdTab) {
              selected.value = 1;
              await tester.pump();
              expect(created, [0, 3]);
            }

            final newAxis = axis == Axis.horizontal
                ? Axis.vertical
                : Axis.horizontal;
            tester.view.physicalSize = newAxis == Axis.horizontal
                ? const Size(540, 900)
                : const Size(900, 540);
            await tester.pumpWidget(
              _harness(selected, created, active, axis: newAxis),
            );
            await tester.pumpAndSettle();
            expect(tester.state(find.byType(FlClashTabView)), same(tabState));
            expect(tester.getRect(_page(selected.value)).topLeft, Offset.zero);
            expect(_activePages(active), [selected.value]);
            expect(created, queuedThirdTab ? [0, 3, 1] : [0, 3]);
            // The old animation's completion must not restart the old flight.
            await tester.pump(const Duration(milliseconds: 400));
            expect(tester.getRect(_page(selected.value)).topLeft, Offset.zero);

            final leavingIndex = selected.value;
            selected.value = 0;
            await tester.pump();
            await tester.pump();
            await tester.pump(const Duration(milliseconds: 90));
            final leaving = tester.getRect(_page(leavingIndex));
            final arriving = tester.getRect(_page(0));
            expect(_start(leaving, newAxis), greaterThan(0));
            expect(_start(arriving, newAxis), lessThan(0));
            expect(
              _end(arriving, newAxis),
              closeTo(_start(leaving, newAxis), .01),
            );
            await tester.pumpAndSettle();
            expect(tester.state(_field(0)), same(fieldState));
            expect(find.text('keep across navigation layouts'), findsOneWidget);
            expect(_activePages(active), [0]);
            expect(tester.takeException(), isNull);
          },
        );
      }
    });
  }
}

double _start(Rect rect, Axis axis) =>
    axis == Axis.horizontal ? rect.left : rect.top;
double _end(Rect rect, Axis axis) =>
    axis == Axis.horizontal ? rect.right : rect.bottom;
double _extent(Size size, Axis axis) =>
    axis == Axis.horizontal ? size.width : size.height;

Finder _page(int index) => find.byKey(ValueKey('page-$index'));
Finder _field(int index) => find.byKey(ValueKey('field-$index'));
Iterable<int> _activePages(Map<int, bool> active) =>
    active.entries.where((entry) => entry.value).map((entry) => entry.key);

Widget _harness(
  ValueNotifier<int> selected,
  List<int> created,
  Map<int, bool> active, {
  Axis axis = Axis.horizontal,
  bool reducedMotion = false,
  bool rtl = false,
}) {
  return MaterialApp(
    home: MediaQuery(
      data: MediaQueryData(disableAnimations: reducedMotion),
      child: Directionality(
        textDirection: rtl ? TextDirection.rtl : TextDirection.ltr,
        child: ValueListenableBuilder<int>(
          valueListenable: selected,
          builder: (_, index, _) => FlClashTabView(
            selectedIndex: index,
            scrollDirection: axis,
            itemCount: 4,
            builder: (_, index, isActive) {
              active[index] = isActive;
              return _Probe(index: index, created: created);
            },
          ),
        ),
      ),
    ),
  );
}

class _Probe extends StatefulWidget {
  const _Probe({required this.index, required this.created});
  final int index;
  final List<int> created;
  @override
  State<_Probe> createState() => _ProbeState();
}

class _ProbeState extends State<_Probe> {
  final _controller = TextEditingController();
  @override
  void initState() {
    super.initState();
    widget.created.add(widget.index);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Material(
    key: ValueKey('page-${widget.index}'),
    child: Column(
      children: [
        Text('Page ${widget.index}'),
        TextField(
          key: ValueKey('field-${widget.index}'),
          controller: _controller,
        ),
      ],
    ),
  );
}
