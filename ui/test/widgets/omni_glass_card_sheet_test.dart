import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/omni_glass.dart';

Widget _app({
  required Widget home,
  bool glass = false,
  GlobalKey<NavigatorState>? navigatorKey,
  List<NavigatorObserver> navigatorObservers = const [],
}) => MaterialApp(
  navigatorKey: navigatorKey,
  navigatorObservers: navigatorObservers,
  builder: (context, child) => LiquidGlassScope(
    enabled: glass,
    quality: LiquidGlassQuality.ultra,
    child: LiquidGlassHost(enabled: glass, child: child!),
  ),
  home: home,
);

void _phoneViewport(WidgetTester tester) {
  tester.view.physicalSize = const Size(400, 800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(tester.view.resetViewInsets);
}

class _RouteObserver extends NavigatorObserver {
  final pushed = <Route<dynamic>>[];

  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) {
    pushed.add(route);
  }
}

void main() {
  for (final glass in [false, true]) {
    testWidgets('compact card is a bottom half-screen sheet, glass=$glass', (
      tester,
    ) async {
      _phoneViewport(tester);
      bool? result;
      await tester.pumpWidget(
        _app(
          glass: glass,
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () async {
                  result = await showOmniGlassCardSheet<bool>(
                    context: context,
                    builder: (context) => OmniGlassAlertDialog(
                      title: const Text('Delete conversation?'),
                      content: const Text('This cannot be undone.'),
                      actions: [
                        TextButton(
                          onPressed: () => Navigator.of(context).pop(true),
                          child: const Text('Delete'),
                        ),
                      ],
                    ),
                  );
                },
                child: const Text('Open'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();

      final sheet = tester.getRect(find.byType(BottomSheet));
      expect(sheet.left, 0);
      expect(sheet.right, 400);
      expect(sheet.top, closeTo(400, 1));
      expect(sheet.bottom, 800);
      expect(find.byType(Dialog), findsNothing);
      expect(find.byType(LiquidGlass), glass ? findsOneWidget : findsNothing);
      expect(find.text('Delete').hitTestable(), findsOneWidget);
      expect(tester.takeException(), isNull);

      await tester.tap(find.text('Delete'));
      await tester.pumpAndSettle();
      expect(result, isTrue);
      expect(find.byType(BottomSheet), findsNothing);

      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();
      await tester.dragFrom(const Offset(200, 420), const Offset(0, 350));
      await tester.pumpAndSettle();
      expect(result, isNull);
      expect(find.byType(BottomSheet), findsNothing);
    });

    testWidgets('long form scrolls above one keyboard inset, glass=$glass', (
      tester,
    ) async {
      _phoneViewport(tester);
      final controller = TextEditingController();
      addTearDown(controller.dispose);
      String? result;
      EdgeInsets? contentInsets;
      await tester.pumpWidget(
        _app(
          glass: glass,
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () async {
                  result = await showOmniGlassCardSheet<String>(
                    context: context,
                    builder: (context) {
                      contentInsets = MediaQuery.viewInsetsOf(context);
                      return OmniGlassAlertDialog(
                        title: const Text('Connection settings'),
                        content: SizedBox(
                          width: 460,
                          child: SingleChildScrollView(
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                for (var index = 0; index < 12; index++)
                                  TextField(
                                    key: ValueKey('field-$index'),
                                    controller: index == 11 ? controller : null,
                                    decoration: InputDecoration(
                                      labelText: 'Field $index',
                                    ),
                                  ),
                              ],
                            ),
                          ),
                        ),
                        actions: [
                          TextButton(
                            onPressed: () =>
                                Navigator.of(context).pop(controller.text),
                            child: const Text('Save'),
                          ),
                        ],
                      );
                    },
                  );
                },
                child: const Text('Open'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();
      expect(tester.getSize(find.byType(BottomSheet)).height, lessThan(800));

      tester.view.viewInsets = const FakeViewPadding(bottom: 300);
      await tester.pumpAndSettle();
      expect(contentInsets, EdgeInsets.zero);
      expect(tester.getBottomRight(find.text('Save')).dy, lessThan(500));
      // A second inset would lift the entire form another 300 pixels.
      expect(tester.getBottomRight(find.text('Save')).dy, greaterThan(400));
      expect(find.text('Save').hitTestable(), findsOneWidget);
      expect(find.byType(Dialog), findsNothing);
      expect(find.byType(LiquidGlass), glass ? findsOneWidget : findsNothing);
      await tester.scrollUntilVisible(
        find.byKey(const ValueKey('field-11')),
        160,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.enterText(
        find.byKey(const ValueKey('field-11')),
        'Saved endpoint',
      );
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(result, 'Saved endpoint');
      expect(tester.takeException(), isNull);
    });

    testWidgets('drag resumes when PopScope allows dismissal, glass=$glass', (
      tester,
    ) async {
      _phoneViewport(tester);
      final canPop = ValueNotifier(false);
      addTearDown(canPop.dispose);
      var completed = false;
      await tester.pumpWidget(
        _app(
          glass: glass,
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () async {
                  await showOmniGlassCardSheet<void>(
                    context: context,
                    builder: (context) => ValueListenableBuilder<bool>(
                      valueListenable: canPop,
                      builder: (context, allowed, child) => PopScope(
                        canPop: allowed,
                        child: OmniGlassAlertDialog(
                          title: const Text('Pending operation'),
                          actions: [
                            TextButton(
                              onPressed: () => canPop.value = true,
                              child: const Text('Finish operation'),
                            ),
                          ],
                        ),
                      ),
                    ),
                  );
                  completed = true;
                },
                child: const Text('Open'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();
      await tester.dragFrom(const Offset(200, 420), const Offset(0, 350));
      await tester.pumpAndSettle();
      expect(find.text('Pending operation'), findsOneWidget);
      expect(completed, isFalse);

      await tester.tap(find.text('Finish operation'));
      await tester.pumpAndSettle();
      await tester.dragFrom(const Offset(200, 420), const Offset(0, 350));
      await tester.pumpAndSettle();
      expect(find.byType(BottomSheet), findsNothing);
      expect(completed, isTrue);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('custom card body uses the sheet width without dialog insets', (
    tester,
  ) async {
    _phoneViewport(tester);
    await tester.pumpWidget(
      _app(
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () => showOmniGlassCardSheet<void>(
                context: context,
                builder: (context) => const OmniGlassCardBody(
                  padding: EdgeInsets.all(24),
                  child: SizedBox(
                    key: ValueKey('custom-body'),
                    width: double.infinity,
                    child: Text('Custom confirmation'),
                  ),
                ),
              ),
              child: const Text('Open'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Open'));
    await tester.pumpAndSettle();
    expect(
      tester.getSize(find.byKey(const ValueKey('custom-body'))).width,
      closeTo(352, 1),
    );
    expect(tester.getRect(find.byType(BottomSheet)).top, closeTo(400, 1));
    expect(tester.takeException(), isNull);
  });

  for (final useRootNavigator in [false, true]) {
    testWidgets('nested navigator selection: root=$useRootNavigator', (
      tester,
    ) async {
      final rootKey = GlobalKey<NavigatorState>();
      final nestedKey = GlobalKey<NavigatorState>();
      final rootObserver = _RouteObserver();
      final nestedObserver = _RouteObserver();
      String? result;
      await tester.pumpWidget(
        _app(
          navigatorKey: rootKey,
          navigatorObservers: [rootObserver],
          home: Navigator(
            key: nestedKey,
            observers: [nestedObserver],
            onGenerateRoute: (_) => MaterialPageRoute<void>(
              builder: (context) => Scaffold(
                body: TextButton(
                  onPressed: () async {
                    result = await showOmniGlassCardSheet<String>(
                      context: context,
                      useRootNavigator: useRootNavigator,
                      routeSettings: const RouteSettings(name: '/confirmation'),
                      builder: (context) => OmniGlassAlertDialog(
                        title: const Text('Confirmation'),
                        actions: [
                          TextButton(
                            onPressed: () => Navigator.of(context).pop('done'),
                            child: const Text('Done'),
                          ),
                        ],
                      ),
                    );
                  },
                  child: const Text('Open'),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();
      final selected = useRootNavigator ? rootObserver : nestedObserver;
      final other = useRootNavigator ? nestedObserver : rootObserver;
      expect(selected.pushed.last, isA<ModalBottomSheetRoute<dynamic>>());
      expect(selected.pushed.last.settings.name, '/confirmation');
      expect(other.pushed, hasLength(1));

      await tester.tap(find.text('Done'));
      await tester.pumpAndSettle();
      expect(result, 'done');
      expect(rootKey.currentState!.canPop(), isFalse);
      expect(nestedKey.currentState!.canPop(), isFalse);
      expect(find.text('Open'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  for (final glass in [false, true]) {
    for (final barrierDismissible in [false, true]) {
      testWidgets(
        'protected card rejects dismissal, glass=$glass barrier=$barrierDismissible',
        (tester) async {
          _phoneViewport(tester);
          late BuildContext sheetContext;
          bool? result;
          await tester.pumpWidget(
            _app(
              glass: glass,
              home: Builder(
                builder: (context) => Scaffold(
                  body: TextButton(
                    onPressed: () async {
                      result = await showOmniGlassCardSheet<bool>(
                        context: context,
                        barrierDismissible: barrierDismissible,
                        builder: (context) {
                          sheetContext = context;
                          return PopScope(
                            canPop: false,
                            child: OmniGlassAlertDialog(
                              title: const Text('Required confirmation'),
                              actions: [
                                TextButton(
                                  onPressed: () =>
                                      Navigator.of(context).pop(true),
                                  child: const Text('Confirm'),
                                ),
                              ],
                            ),
                          );
                        },
                      );
                    },
                    child: const Text('Open'),
                  ),
                ),
              ),
            ),
          );
          await tester.tap(find.text('Open'));
          await tester.pumpAndSettle();
          await tester.tapAt(const Offset(200, 100));
          await tester.pumpAndSettle();
          expect(find.text('Required confirmation'), findsOneWidget);

          await tester.dragFrom(const Offset(200, 420), const Offset(0, 350));
          await tester.pumpAndSettle();
          expect(find.text('Required confirmation'), findsOneWidget);
          expect(tester.getRect(find.byType(BottomSheet)).top, closeTo(400, 1));

          await Navigator.of(sheetContext).maybePop();
          await tester.pumpAndSettle();
          expect(find.text('Required confirmation'), findsOneWidget);
          expect(result, isNull);
          await tester.tap(find.text('Confirm'));
          await tester.pumpAndSettle();
          expect(result, isTrue);
          expect(find.byType(BottomSheet), findsNothing);
          expect(tester.takeException(), isNull);
        },
      );
    }
  }
}
