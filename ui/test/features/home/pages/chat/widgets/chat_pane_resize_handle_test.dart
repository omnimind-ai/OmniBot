import 'dart:ui' show SemanticsAction, SemanticsActionEvent;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_pane_resize_handle.dart';

void main() {
  Widget app({required List<String> events, bool leadingPane = true}) =>
      MaterialApp(
        home: Scaffold(
          body: Center(
            child: SizedBox(
              height: 400,
              child: ChatPaneResizeHandle(
                label: 'Sidebar width',
                hint: 'Drag to resize; double-tap to collapse',
                width: 24,
                paneWidth: 260,
                leadingPane: leadingPane,
                onDragStart: () => events.add('start'),
                onDragUpdate: (delta) => events.add('move:$delta'),
                onDragEnd: () => events.add('end'),
                onCollapse: () => events.add('collapse'),
              ),
            ),
          ),
        ),
      );

  testWidgets('touch drag and cancellation finish one resize transaction', (
    tester,
  ) async {
    final events = <String>[];
    await tester.pumpWidget(app(events: events));
    final handle = find.byType(ChatPaneResizeHandle);
    final gesture = await tester.startGesture(tester.getCenter(handle));
    await gesture.moveBy(const Offset(32, 0));
    await tester.pump();
    await gesture.moveBy(const Offset(24, 0));
    await tester.pump();
    await gesture.cancel();
    await tester.pumpAndSettle();
    expect(events.first, 'start');
    expect(events, contains('move:24.0'));
    expect(events.last, 'end');
    expect(events.where((event) => event == 'end'), hasLength(1));
    expect(events, isNot(contains('collapse')));
    expect(tester.takeException(), isNull);
  });

  testWidgets('keyboard arrows resize and Enter collapses through the owner', (
    tester,
  ) async {
    final events = <String>[];
    await tester.pumpWidget(app(events: events));
    await tester.sendKeyEvent(LogicalKeyboardKey.tab);
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
    await tester.sendKeyDownEvent(LogicalKeyboardKey.shiftLeft);
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
    await tester.sendKeyUpEvent(LogicalKeyboardKey.shiftLeft);
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    expect(events, [
      'start',
      'move:24.0',
      'end',
      'start',
      'move:-64.0',
      'end',
      'collapse',
    ]);
    expect(tester.takeException(), isNull);
  });

  testWidgets('right pane accessibility increases width toward the left', (
    tester,
  ) async {
    final semantics = tester.ensureSemantics();
    final events = <String>[];
    await tester.pumpWidget(app(events: events, leadingPane: false));
    final node = tester.getSemantics(find.bySemanticsLabel('Sidebar width'));
    tester.binding.performSemanticsAction(
      SemanticsActionEvent(
        nodeId: node.id,
        type: SemanticsAction.increase,
        viewId: tester.view.viewId,
      ),
    );
    await tester.pump();
    expect(events, ['start', 'move:-24.0', 'end']);
    events.clear();
    tester.binding.performSemanticsAction(
      SemanticsActionEvent(
        nodeId: node.id,
        type: SemanticsAction.decrease,
        viewId: tester.view.viewId,
      ),
    );
    await tester.pump();
    expect(events, ['start', 'move:24.0', 'end']);
    semantics.dispose();
  });

  testWidgets('double tap collapses without starting a resize', (tester) async {
    final events = <String>[];
    await tester.pumpWidget(app(events: events));
    final handle = find.byType(ChatPaneResizeHandle);
    await tester.tap(handle);
    await tester.pump(const Duration(milliseconds: 80));
    await tester.tap(handle);
    await tester.pumpAndSettle();
    expect(events, ['collapse']);
  });
}
