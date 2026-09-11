import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/widgets/glass_popup.dart';

void main() {
  testWidgets('system back dismisses only the topmost popup', (tester) async {
    final router = GoRouter(
      routes: [
        GoRoute(
          path: '/',
          builder: (context, state) => Scaffold(
            body: TextButton(
              onPressed: () {
                for (final name in ['first popup', 'second popup']) {
                  showOverlayGlassPopup<void>(
                    context: context,
                    anchor: const Rect.fromLTWH(40, 80, 100, 30),
                    transitionDuration: Duration.zero,
                    reverseTransitionDuration: Duration.zero,
                    builder: (_) =>
                        SizedBox(width: 180, height: 80, child: Text(name)),
                  );
                }
              },
              child: const Text('open pair'),
            ),
          ),
        ),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(MaterialApp.router(routerConfig: router));
    await tester.tap(find.text('open pair'));
    await tester.pumpAndSettle();
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('second popup'), findsNothing);
    expect(find.text('first popup'), findsOneWidget);
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('first popup'), findsNothing);
    expect(find.text('open pair'), findsOneWidget);
  });
  for (final mode in ['system', 'manual', 'navigator']) {
    testWidgets('overlay owns back while open; dismissal=$mode', (
      tester,
    ) async {
      late BuildContext pageContext;
      late OverlayGlassPopupHandle<void> popup;
      final focus = FocusNode();
      addTearDown(focus.dispose);
      final router = GoRouter(
        routes: [
          GoRoute(
            path: '/',
            builder: (context, state) {
              pageContext = context;
              return Scaffold(
                body: Column(
                  children: [
                    TextField(focusNode: focus),
                    TextButton(
                      onPressed: () {
                        popup = showOverlayGlassPopup<void>(
                          context: context,
                          anchor: const Rect.fromLTWH(40, 80, 100, 30),
                          transitionDuration: Duration.zero,
                          reverseTransitionDuration: Duration.zero,
                          builder: (_) => const SizedBox(
                            width: 180,
                            height: 80,
                            child: Text('settings popup'),
                          ),
                        );
                      },
                      child: const Text('open settings'),
                    ),
                  ],
                ),
              );
            },
          ),
        ],
      );
      addTearDown(router.dispose);
      await tester.pumpWidget(MaterialApp.router(routerConfig: router));
      focus.requestFocus();
      await tester.pump();
      await tester.tap(find.text('open settings'));
      await tester.pumpAndSettle();
      expect(
        ModalRoute.of(pageContext)!.popDisposition,
        RoutePopDisposition.doNotPop,
        reason: 'Android must know the route can consume back before dispatching it',
      );
      expect(
        focus.hasFocus,
        isTrue,
        reason: 'Registering back must not push a new focus route',
      );
      if (mode == 'manual') {
        await popup.dismiss();
      } else if (mode == 'navigator') {
        await Navigator.of(pageContext).maybePop();
      } else {
        await tester.binding.handlePopRoute();
      }
      await tester.pumpAndSettle();
      expect(find.text('settings popup'), findsNothing);
      expect(find.text('open settings'), findsOneWidget);
      expect(
        ModalRoute.of(pageContext)!.popDisposition,
        RoutePopDisposition.bubble,
      );
      expect(popup.isOpen, isFalse);
    });
  }
}
