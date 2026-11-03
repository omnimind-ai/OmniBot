import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/omni_adaptive_layout.dart';

void main() {
  testWidgets('content fits its pane while retaining real window metrics', (
    tester,
  ) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(1366, 1024);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);
    Size? window;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Row(
            children: [
              const SizedBox(width: 104),
              Expanded(
                child: OmniContentWidth(
                  child: Builder(
                    builder: (context) {
                      window = MediaQuery.sizeOf(context);
                      return const SizedBox.expand(key: Key('content'));
                    },
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
    final rect = tester.getRect(find.byKey(const Key('content')));
    expect(rect.width, 840);
    expect(rect.height, 1024);
    expect(rect.center.dx, 104 + (1366 - 104) / 2);
    expect(window, const Size(1366, 1024));
  });

  testWidgets('resizing retains editor focus, selection and list position', (
    tester,
  ) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(1366, 1024);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);
    final scroll = ScrollController();
    final controller = TextEditingController();
    final focus = FocusNode();
    addTearDown(scroll.dispose);
    addTearDown(controller.dispose);
    addTearDown(focus.dispose);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: OmniContentWidth(
            child: Column(
              children: [
                TextField(controller: controller, focusNode: focus),
                Expanded(
                  child: ListView.builder(
                    controller: scroll,
                    itemExtent: 64,
                    itemCount: 100,
                    itemBuilder: (_, index) => Text('Item $index'),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
    await tester.enterText(find.byType(TextField), 'Pad draft');
    controller.selection = const TextSelection(baseOffset: 0, extentOffset: 3);
    scroll.jumpTo(640);
    final editorState = tester.state(find.byType(EditableText));
    for (final width in [768.0, 600.0, 390.0, 1024.0, 1366.0]) {
      tester.view.physicalSize = Size(width, 1024);
      await tester.pump();
      expect(tester.state(find.byType(EditableText)), same(editorState));
      expect(controller.text, 'Pad draft');
      expect(
        controller.selection,
        const TextSelection(baseOffset: 0, extentOffset: 3),
      );
      expect(focus.hasFocus, isTrue);
      expect(scroll.offset, 640);
      expect(tester.getSize(find.byType(TextField)).width, width.clamp(0, 840));
      expect(tester.takeException(), isNull);
    }
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
