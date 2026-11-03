import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_keyboard_layout.dart';

void main() {
  testWidgets(
    'IME and composer changes rebuild layout without rebuilding content',
    (tester) async {
      const viewport = Size(390, 844);
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = viewport;
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      final media = ValueNotifier(const MediaQueryData(size: viewport));
      final changes = ValueNotifier(0);
      addTearDown(media.dispose);
      addTearDown(changes.dispose);
      var hasFocus = false;
      var composerHeight = 48.0;
      var layoutBuilds = 0;
      final builds = <String, int>{};
      final editorKey = GlobalKey();
      final stableContent = _BuildCounter(
        name: 'page',
        builds: builds,
        child: Stack(
          children: [
            _BuildCounter(
              name: 'body',
              builds: builds,
              child: const Center(child: Text('Conversation body')),
            ),
            _BuildCounter(
              name: 'toolbar',
              builds: builds,
              child: const Align(
                alignment: Alignment.topCenter,
                child: Text('Conversation toolbar'),
              ),
            ),
            ChatKeyboardLayoutBuilder(
              child: _BuildCounter(
                name: 'editor',
                builds: builds,
                child: SizedBox(key: editorKey, height: 48, width: 200),
              ),
              builder: (context, metrics, child) {
                layoutBuilds++;
                return Positioned(
                  left: 0,
                  bottom: metrics.composerBottomOffset,
                  child: child!,
                );
              },
            ),
            ChatKeyboardLayoutBuilder(
              builder: (context, metrics, child) =>
                  Text('reserved:${metrics.composerReservedInset}'),
            ),
          ],
        ),
      );
      await tester.pumpWidget(
        MaterialApp(
          home: ValueListenableBuilder<MediaQueryData>(
            valueListenable: media,
            child: ChatKeyboardLayout(
              changes: changes,
              resolve: (query) {
                final lift = hasFocus ? query.viewInsets.bottom : 0.0;
                return (
                  bottomInset: query.viewInsets.bottom,
                  composerBottomOffset: lift + 8,
                  composerReservedInset: lift + 8 + composerHeight,
                  commandPanelBottomOffset: lift + 8 + composerHeight + 6,
                  inputBottomPadding: 8,
                  keyboardSpacer: lift,
                  liftEmptyGreeting: lift > 0,
                );
              },
              child: stableContent,
            ),
            builder: (context, value, child) =>
                MediaQuery(data: value, child: child!),
          ),
        ),
      );
      expect(builds, {'page': 1, 'body': 1, 'toolbar': 1, 'editor': 1});
      final initialLayoutBuilds = layoutBuilds;
      expect(tester.getBottomLeft(find.byKey(editorKey)).dy, 836);
      expect(find.text('reserved:56.0'), findsOneWidget);

      // Focus can arrive before Android reports its first inset. A same-geometry
      // notification must not rebuild layout consumers or the cached child.
      hasFocus = true;
      changes.value++;
      await tester.pump();
      expect(layoutBuilds, initialLayoutBuilds);

      for (final inset in [40.0, 120.0, 220.0, 300.0]) {
        media.value = media.value.copyWith(
          viewInsets: EdgeInsets.only(bottom: inset),
        );
        await tester.pump(const Duration(milliseconds: 16));
        expect(tester.getBottomLeft(find.byKey(editorKey)).dy, 836 - inset);
        expect(find.text('reserved:${56 + inset}'), findsOneWidget);
        expect(builds, {'page': 1, 'body': 1, 'toolbar': 1, 'editor': 1});
      }
      expect(layoutBuilds, initialLayoutBuilds + 4);

      // Composer expansion reserves extra message space without moving/rebuilding
      // the persistent editor, toolbar or conversation body.
      composerHeight = 96;
      changes.value++;
      await tester.pump();
      expect(find.text('reserved:404.0'), findsOneWidget);
      expect(tester.getBottomLeft(find.byKey(editorKey)).dy, 536);
      expect(layoutBuilds, initialLayoutBuilds + 5);
      changes.value++;
      await tester.pump();
      expect(layoutBuilds, initialLayoutBuilds + 5);
      expect(builds, {'page': 1, 'body': 1, 'toolbar': 1, 'editor': 1});

      hasFocus = false;
      changes.value++;
      await tester.pump();
      expect(tester.getBottomLeft(find.byKey(editorKey)).dy, 836);
      expect(find.text('reserved:104.0'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'stable child still receives viewport and keyboard MediaQuery changes',
    (tester) async {
      final media = ValueNotifier(const MediaQueryData(size: Size(390, 844)));
      final changes = ValueNotifier(0);
      addTearDown(media.dispose);
      addTearDown(changes.dispose);
      var viewportBuilds = 0;
      var keyboardBuilds = 0;
      await tester.pumpWidget(
        MaterialApp(
          home: ValueListenableBuilder<MediaQueryData>(
            valueListenable: media,
            child: ChatKeyboardLayout(
              changes: changes,
              resolve: (query) => (
                bottomInset: query.viewInsets.bottom,
                composerBottomOffset: query.viewInsets.bottom,
                composerReservedInset: query.viewInsets.bottom + 48,
                commandPanelBottomOffset: query.viewInsets.bottom + 54,
                inputBottomPadding: 0,
                keyboardSpacer: query.viewInsets.bottom,
                liftEmptyGreeting: query.viewInsets.bottom > 0,
              ),
              child: Column(
                children: [
                  Builder(
                    builder: (context) {
                      viewportBuilds++;
                      return Text(
                        '${MediaQuery.sizeOf(context)} / '
                        '${MediaQuery.orientationOf(context).name}',
                      );
                    },
                  ),
                  Builder(
                    builder: (context) {
                      keyboardBuilds++;
                      return Text(
                        'ime:${MediaQuery.viewInsetsOf(context).bottom}',
                      );
                    },
                  ),
                ],
              ),
            ),
            builder: (context, value, child) =>
                MediaQuery(data: value, child: child!),
          ),
        ),
      );
      expect(find.text('Size(390.0, 844.0) / portrait'), findsOneWidget);
      expect(viewportBuilds, 1);
      expect(keyboardBuilds, 1);
      media.value = media.value.copyWith(
        viewInsets: const EdgeInsets.only(bottom: 300),
      );
      await tester.pump();
      expect(find.text('ime:300.0'), findsOneWidget);
      expect(viewportBuilds, 1);
      expect(keyboardBuilds, 2);

      media.value = media.value.copyWith(size: const Size(1200, 800));
      await tester.pump();
      expect(find.text('Size(1200.0, 800.0) / landscape'), findsOneWidget);
      expect(viewportBuilds, 2);
      expect(keyboardBuilds, 2);
      expect(tester.takeException(), isNull);
    },
  );
}

class _BuildCounter extends StatelessWidget {
  const _BuildCounter({
    required this.name,
    required this.builds,
    required this.child,
  });

  final String name;
  final Map<String, int> builds;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    builds[name] = (builds[name] ?? 0) + 1;
    return child;
  }
}
