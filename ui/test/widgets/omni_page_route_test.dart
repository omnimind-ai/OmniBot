import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/navigation/page_route.dart';

const _pageKey = ValueKey('destination');

Future<void> _back(WidgetTester tester, String method, [double? progress]) =>
    tester.binding.defaultBinaryMessenger.handlePlatformMessage(
      'flutter/backgesture',
      const StandardMethodCodec().encodeMethodCall(
        MethodCall(
          method,
          progress == null
              ? null
              : {
                  'touchOffset': <double>[0, 300],
                  'progress': progress,
                  'swipeEdge': 0,
                },
        ),
      ),
      (_) {},
    );

Future<GlobalKey<NavigatorState>> _app(
  WidgetTester tester, {
  bool reduceMotion = false,
}) async {
  final navigator = GlobalKey<NavigatorState>();
  await tester.pumpWidget(
    MaterialApp(
      navigatorKey: navigator,
      onGenerateRoute: (_) => null,
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(disableAnimations: reduceMotion),
        child: child!,
      ),
      onGenerateInitialRoutes: (_) => [
        OmniPageRoute<void>(builder: (_) => const Scaffold(body: Text('Home'))),
      ],
    ),
  );
  await tester.pumpAndSettle();
  return navigator;
}

void main() {
  testWidgets('push and button return use the same quarter-width transition', (
    tester,
  ) async {
    final navigator = await _app(tester);
    final route = OmniPageRoute<String>(
      builder: (_) => const Scaffold(key: _pageKey, body: Text('Chat')),
    );
    final result = navigator.currentState!.push(route);
    await tester.pump();
    expect(route.transitionDuration, const Duration(milliseconds: 450));
    // The first layout is prepared before the entering route starts moving.
    expect(route.animation!.value, 0);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 225));
    final width = tester.getSize(find.byKey(_pageKey)).width;
    final entering = tester.getTopLeft(find.byKey(_pageKey)).dx;
    expect(entering, greaterThan(0));
    expect(entering, lessThan(width / 4));
    await tester.pumpAndSettle();
    expect(tester.getTopLeft(find.byKey(_pageKey)), Offset.zero);

    navigator.currentState!.pop('saved');
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 225));
    final leaving = tester.getTopLeft(find.byKey(_pageKey)).dx;
    expect(leaving, greaterThan(0));
    expect(leaving, lessThan(width / 4));
    await tester.pumpAndSettle();
    expect(await result, 'saved');
    expect(find.text('Home'), findsOneWidget);
    expect(navigator.currentState!.canPop(), isFalse);
  }, variant: TargetPlatformVariant.only(TargetPlatform.android));

  testWidgets('system gesture cancellation is inert and commit pops once', (
    tester,
  ) async {
    final navigator = await _app(tester);
    final route = OmniPageRoute<void>(
      builder: (_) => const Scaffold(key: _pageKey, body: Text('Chat')),
    );
    navigator.currentState!.push(route);
    await tester.pumpAndSettle();
    await _back(tester, 'startBackGesture', 0);
    await _back(tester, 'updateBackGestureProgress', .6);
    await tester.pump();
    expect(route.animation!.value, 1);
    expect(route.popGestureInProgress, isFalse);
    expect(tester.getTopLeft(find.byKey(_pageKey)), Offset.zero);
    await _back(tester, 'cancelBackGesture');
    await tester.pumpAndSettle();
    expect(route.isCurrent, isTrue);

    await _back(tester, 'startBackGesture', 0);
    await _back(tester, 'commitBackGesture');
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    expect(route.animation!.isAnimating, isTrue);
    expect(navigator.currentState!.userGestureInProgress, isFalse);
    await tester.pumpAndSettle();
    expect(find.text('Home'), findsOneWidget);
    expect(navigator.currentState!.canPop(), isFalse);
  }, variant: TargetPlatformVariant.only(TargetPlatform.android));

  testWidgets('pointer return follows distance, cancels and commits', (
    tester,
  ) async {
    final navigator = await _app(tester);
    final route = OmniPageRoute<void>(
      builder: (_) => const Scaffold(key: _pageKey, body: Text('Chat')),
    );
    navigator.currentState!.push(route);
    await tester.pumpAndSettle();
    final drag = await tester.startGesture(const Offset(50, 300));
    await drag.moveBy(const Offset(50, 0));
    await drag.moveBy(const Offset(100, 0));
    await tester.pump();
    final position = tester.getTopLeft(find.byKey(_pageKey)).dx;
    await drag.moveBy(const Offset(60, 0));
    await tester.pump();
    expect(
      tester.getTopLeft(find.byKey(_pageKey)).dx - position,
      closeTo(60, .01),
    );
    await drag.cancel();
    await tester.pumpAndSettle();
    expect(route.isCurrent, isTrue);
    expect(route.popGestureInProgress, isFalse);
    expect(tester.getTopLeft(find.byKey(_pageKey)), Offset.zero);

    final commit = await tester.startGesture(const Offset(50, 300));
    await commit.moveBy(const Offset(50, 0));
    await commit.moveBy(const Offset(500, 0));
    await tester.pump(const Duration(milliseconds: 600));
    await commit.up();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 349));
    expect(route.animation!.isAnimating, isTrue);
    await tester.pumpAndSettle();
    expect(find.text('Home'), findsOneWidget);
    expect(navigator.currentState!.userGestureInProgress, isFalse);
  }, variant: TargetPlatformVariant.only(TargetPlatform.android));

  testWidgets('PopScope owns vetoes and return values', (tester) async {
    final navigator = await _app(tester);
    var attempts = 0;
    final route = OmniPageRoute<String>(
      builder: (_) => PopScope<String>(
        canPop: false,
        onPopInvokedWithResult: (didPop, result) {
          if (!didPop) attempts++;
        },
        child: const Scaffold(key: _pageKey, body: Text('Unsaved')),
      ),
    );
    final result = navigator.currentState!.push(route);
    await tester.pumpAndSettle();
    await tester.drag(find.byKey(_pageKey), const Offset(500, 0));
    await tester.pumpAndSettle();
    expect(route.animation!.value, 1);
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(attempts, 1);
    expect(route.isCurrent, isTrue);
    navigator.currentState!.pop('confirmed');
    await tester.pumpAndSettle();
    expect(await result, 'confirmed');
  }, variant: TargetPlatformVariant.only(TargetPlatform.android));

  testWidgets('reduced motion skips programmatic transforms', (tester) async {
    final navigator = await _app(tester, reduceMotion: true);
    final route = OmniPageRoute<void>(
      builder: (_) => const Scaffold(key: _pageKey, body: Text('Chat')),
    );
    navigator.currentState!.push(route);
    await tester.pumpAndSettle();
    expect(route.transitionDuration, Duration.zero);
    expect(tester.getTopLeft(find.byKey(_pageKey)), Offset.zero);
    navigator.currentState!.pop();
    await tester.pumpAndSettle();
    expect(find.byKey(_pageKey), findsNothing);
  }, variant: TargetPlatformVariant.only(TargetPlatform.android));
}
