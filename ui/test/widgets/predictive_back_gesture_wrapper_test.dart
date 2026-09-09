import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter/rendering.dart';
import 'package:go_router/go_router.dart';

import 'dart:ui' as ui;

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/services/display_geometry_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/widgets/predictive_back_gesture_wrapper.dart';
import 'package:ui/widgets/predictive_back_route.dart';
import 'package:ui/widgets/miuix_squircle_clip.dart';

/// Uses the production route motion, with the GoRouter page's legacy fade.
class _WrapperRoute extends PredictiveBackMaterialPageRoute<String> {
  _WrapperRoute({required ValueChanged<ModalRoute<dynamic>?> onRouteReady})
    : super(
        builder: (context) {
          onRouteReady(ModalRoute.of(context));
          return const Scaffold(body: Text('second'));
        },
      );

  @override
  Widget buildTransitions(
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) {
    return PredictiveBackGestureWrapper(
      animation: animation,
      secondaryAnimation: secondaryAnimation,
      transitionBuilder: (context, primary, secondary, child) =>
          FadeTransition(opacity: primary, child: child),
      child: child,
    );
  }
}

/// 经 flutter/backgesture 平台通道模拟引擎侧手势事件
/// (与框架 SDK 测试 predictive_back_page_transitions_builder_test.dart 同款手法)。
Future<void> _sendBackGesture(
  WidgetTester tester,
  String method, [
  Map<String, dynamic>? arguments,
]) async {
  final ByteData message = const StandardMethodCodec().encodeMethodCall(
    MethodCall(method, arguments),
  );
  await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
    'flutter/backgesture',
    message,
    (ByteData? _) {},
  );
}

Future<void> _startBackGesture(WidgetTester tester, double progress) {
  return _sendBackGesture(tester, 'startBackGesture', <String, dynamic>{
    'touchOffset': <double>[0.0, 300.0],
    'progress': progress,
    'swipeEdge': 0, // left
  });
}

Future<void> _updateBackGesture(WidgetTester tester, double progress) {
  return _sendBackGesture(
    tester,
    'updateBackGestureProgress',
    <String, dynamic>{
      'touchOffset': <double>[100.0, 300.0],
      'progress': progress,
      'swipeEdge': 0, // left
    },
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const displayGeometryChannel = MethodChannel(
    'cn.com.omnimind.bot/DisplayGeometry',
  );

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    DisplayGeometryService.resetForTesting();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(displayGeometryChannel, (call) async {
          return <String, double>{
            'topLeft': 42,
            'topRight': 40,
            'bottomLeft': 36,
            'bottomRight': 34,
          };
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(displayGeometryChannel, null);
    DisplayGeometryService.resetForTesting();
  });

  /// 自举应用并 push 出带 wrapper 的二级页面,返回捕获到的路由。
  Future<ModalRoute<dynamic>?> bootstrap(
    WidgetTester tester, {
    bool settle = true,
  }) async {
    ModalRoute<dynamic>? route;
    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData(
          pageTransitionsTheme: const PageTransitionsTheme(
            builders: {TargetPlatform.android: MiuixPageTransitionsBuilder()},
          ),
        ),
        home: Scaffold(
          body: Builder(
            builder: (context) {
              return TextButton(
                onPressed: () {
                  Navigator.of(context)
                      .push(_WrapperRoute(onRouteReady: (r) => route = r));
                },
                child: const Text('push'),
              );
            },
          ),
        ),
      ),
    );
    await tester.tap(find.text('push'));
    await tester.pump();
    if (settle) await tester.pumpAndSettle();
    if (settle) expect(find.text('second'), findsOneWidget);
    return route;
  }

  Finder clipFinder() => find.ancestor(
    of: find.text('second'),
    matching: find.byKey(const ValueKey('predictive_back_corner_clip')),
  );

  testWidgets(
    'gesture drives route controller, slide transition and corner clip; '
    'cancel restores the page',
    (tester) async {
      final route = await bootstrap(tester);
      expect(route, isNotNull);

      // 手势开始后，路由动画由系统进度线性驱动。
      await _startBackGesture(tester, 0.0);
      await tester.pump();
      expect(route!.popGestureInProgress, isTrue);

      // 进度 0.5：页面移动半屏，只裁剪露出的左侧真机圆角。
      await _updateBackGesture(tester, 0.5);
      await tester.pump();
      expect(route.animation!.value, closeTo(0.5, 0.001));
      final clip = tester.widget<ClipPath>(clipFinder());
      expect(
        (clip.clipper! as MiuixSquircleClipper).borderRadius,
        const BorderRadius.only(
          topLeft: Radius.circular(36),
          bottomLeft: Radius.circular(36),
        ),
      );
      expect(clip.clipBehavior, Clip.antiAlias);

      final primaryTransform = tester.widget<Transform>(
        find.ancestor(
          of: find.text('second'),
          matching: find.byKey(
            const ValueKey('predictive_back_primary_transform'),
          ),
        ),
      );
      expect(primaryTransform.transform.storage[12], closeTo(400, 0.001));

      // 取消:页面弹回,路由保留,动画回到 1,圆角消失。
      await _sendBackGesture(tester, 'cancelBackGesture');
      await tester.pumpAndSettle();
      expect(find.text('second'), findsOneWidget);
      expect(route.popGestureInProgress, isFalse);
      expect(route.animation!.value, closeTo(1.0, 0.001));
      expect(
        (tester.widget<ClipPath>(clipFinder()).clipper! as MiuixSquircleClipper)
            .borderRadius,
        BorderRadius.zero,
      );
      expect(tester.widget<ClipPath>(clipFinder()).clipBehavior, Clip.none);
    },
    // wrapper 仅在 Android 消费手势;variant 的 tearDown 会在测试框架
    // 校验 debug 变量之前复位 debugDefaultTargetPlatformOverride。
    variant: TargetPlatformVariant.only(TargetPlatform.android),
  );

  testWidgets(
    'commit settles forward from the drag position without bouncing back',
    (tester) async {
      final route = await bootstrap(tester);
      final navigator = route!.navigator!;

      await _startBackGesture(tester, 0.0);
      await tester.pump();
      await _updateBackGesture(tester, 0.8);
      await tester.pump();
      expect(route.animation!.value, closeTo(0.2, 0.001));

      await _sendBackGesture(tester, 'commitBackGesture');
      // 收尾期间控制器只能从松手位置(0.2)向 0 前进,不得向 1.0 回跳
      // (TransitionRoute._handleDragEnd 的 reverse(from: 1.0) 重播路径)。
      var previous = 0.2;
      // Pop is committed now, while the outgoing overlay remains animated.
      expect(route.isCurrent, isFalse);
      expect(route.animation!.value, closeTo(0.2, 0.001));
      for (var i = 0; i < 10 && route.animation != null; i++) {
        await tester.pump(const Duration(milliseconds: 30));
        final value = route.animation?.value;
        if (value == null) {
          break;
        }
        expect(value, lessThanOrEqualTo(previous + 0.001));
        previous = value;
      }
      await tester.pumpAndSettle();

      expect(find.text('second'), findsNothing);
      expect(route.isCurrent, isFalse);
      expect(navigator.userGestureInProgress, isFalse);

      // 返回完成后不能残留 IgnorePointer，底层页面应立即恢复交互。
      await tester.tap(find.text('push'));
      await tester.pumpAndSettle();
      expect(find.text('second'), findsOneWidget);
    },
    variant: TargetPlatformVariant.only(TargetPlatform.android),
  );

  testWidgets('programmatic push and pop follow the same 500ms Miuix curve', (
    tester,
  ) async {
    final route = (await bootstrap(tester, settle: false))!;
    expect(route.transitionDuration, const Duration(milliseconds: 500));
    await tester.pump(const Duration(milliseconds: 100));
    // Reference NavSettleEasing(response=.8, damping=.95), at t=.2.
    expect(route.animation!.value, closeTo(0.479383, 0.0001));
    await tester.pumpAndSettle();
    route.navigator!.pop();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    expect(route.animation!.value, closeTo(0.520617, 0.0001));
    await tester.pumpAndSettle();
    expect(find.text('second'), findsNothing);
  }, variant: TargetPlatformVariant.only(TargetPlatform.android));

  testWidgets('grab an entering page without a position or parallax jump', (
    tester,
  ) async {
    final route = (await bootstrap(tester, settle: false))!;
    await tester.pump(const Duration(milliseconds: 100));
    final start = route.animation!.value;
    await _startBackGesture(tester, 0);
    await tester.pump();
    expect(route.animation!.value, closeTo(start, 0.000001));
    await _updateBackGesture(tester, 0.1);
    await tester.pump();
    expect(route.animation!.value, closeTo(start - 0.1, 0.000001));
    final covered = tester
        .widgetList<Transform>(
          find.byKey(const ValueKey('predictive_back_covered_transform')),
        )
        .first;
    expect(covered.transform.storage[12], closeTo(-(start - 0.1) * 200, .001));
    await _sendBackGesture(tester, 'cancelBackGesture');
    await tester.pumpAndSettle();
    expect(route.animation!.value, 1);
    expect(route.navigator!.userGestureInProgress, isFalse);
  }, variant: TargetPlatformVariant.only(TargetPlatform.android));

  testWidgets(
    'cancel settle can be grabbed repeatedly without leaking ownership',
    (tester) async {
      final route = (await bootstrap(tester))!;
      final navigator = route.navigator!;
      await _startBackGesture(tester, 0);
      await _updateBackGesture(tester, 0.55);
      await _sendBackGesture(tester, 'cancelBackGesture');
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 80));
      final beforeGrab = route.animation!.value;
      await _startBackGesture(tester, 0);
      await tester.pump();
      expect(route.animation!.value, closeTo(beforeGrab, .000001));
      expect(navigator.userGestureInProgress, isTrue);
      await _updateBackGesture(tester, 0.1);
      await _sendBackGesture(tester, 'cancelBackGesture');
      await tester.pumpAndSettle();
      expect(navigator.userGestureInProgress, isFalse);
      navigator.pop();
      await tester.pumpAndSettle();
      await tester.tap(find.text('push'));
      await tester.pumpAndSettle();
      expect(find.text('second'), findsOneWidget);
    },
    variant: TargetPlatformVariant.only(TargetPlatform.android),
  );

  testWidgets('pop veto and an open dialog keep ownership over page gestures', (
    tester,
  ) async {
    final route = (await bootstrap(tester))!;
    final context = tester.element(find.text('second'));
    showDialog<void>(
      context: context,
      builder: (_) => const AlertDialog(content: Text('dialog')),
    );
    await tester.pumpAndSettle();
    await _startBackGesture(tester, 0);
    await _updateBackGesture(tester, .5);
    await tester.pump();
    expect(route.animation!.value, 1);
    expect(route.popGestureInProgress, isFalse);
    await _sendBackGesture(tester, 'cancelBackGesture');
    Navigator.of(context).pop();
    await tester.pumpAndSettle();

    final vetoRoute = PredictiveBackMaterialPageRoute<void>(
      builder: (_) =>
          const PopScope(canPop: false, child: Scaffold(body: Text('veto'))),
    );
    route.navigator!.push(vetoRoute);
    await tester.pumpAndSettle();
    await _startBackGesture(tester, 0);
    await _updateBackGesture(tester, .5);
    await tester.pump();
    expect(vetoRoute.animation!.value, 1);
    expect(vetoRoute.popGestureInProgress, isFalse);
    await _sendBackGesture(tester, 'cancelBackGesture');
  }, variant: TargetPlatformVariant.only(TargetPlatform.android));

  testWidgets(
    'GoRouter commits one pop and keeps the exiting page for the spring',
    (tester) async {
      Widget fallback(
        BuildContext context,
        Animation<double> primary,
        Animation<double> secondary,
        Widget child,
      ) => FadeTransition(opacity: primary, child: child);
      final router = GoRouter(
        routes: [
          GoRoute(
            path: '/',
            pageBuilder: (context, state) => PredictiveBackPage<void>(
              key: state.pageKey,
              fallbackBuilder: fallback,
              child: const Scaffold(body: Text('root')),
            ),
            routes: [
              GoRoute(
                path: 'detail',
                pageBuilder: (context, state) => PredictiveBackPage<void>(
                  key: state.pageKey,
                  fallbackBuilder: fallback,
                  child: const Scaffold(body: Text('detail')),
                ),
              ),
            ],
          ),
        ],
      );
      addTearDown(router.dispose);
      await tester.pumpWidget(MaterialApp.router(routerConfig: router));
      await tester.pumpAndSettle();
      var completed = false;
      router.push<void>('/detail').then((_) => completed = true);
      await tester.pumpAndSettle();
      final route = ModalRoute.of(tester.element(find.text('detail')))!;
      await _startBackGesture(tester, 0);
      await _updateBackGesture(tester, .55);
      await _sendBackGesture(tester, 'commitBackGesture');
      await tester.pump();
      expect(router.canPop(), isFalse);
      expect(completed, isTrue);
      expect(route.animation!.value, closeTo(.45, .001));
      expect(find.text('detail'), findsOneWidget);
      await tester.pumpAndSettle();
      expect(find.text('detail'), findsNothing);
      expect(find.text('root'), findsOneWidget);
    },
    variant: TargetPlatformVariant.only(TargetPlatform.android),
  );

  testWidgets(
    'another navigation during a drag restores the interrupted page',
    (tester) async {
      final route = (await bootstrap(tester))!;
      final navigator = route.navigator!;
      await _startBackGesture(tester, 0);
      await _updateBackGesture(tester, .5);
      navigator.push(
        PredictiveBackMaterialPageRoute<void>(
          builder: (_) => const Scaffold(body: Text('third')),
        ),
      );
      await tester.pump();
      await _sendBackGesture(tester, 'cancelBackGesture');
      await tester.pumpAndSettle();
      expect(navigator.userGestureInProgress, isFalse);
      navigator.pop();
      await tester.pumpAndSettle();
      expect(route.animation!.value, 1);
      expect(find.text('second'), findsOneWidget);
    },
    variant: TargetPlatformVariant.only(TargetPlatform.android),
  );

  testWidgets(
    'a second back gesture grabs the still-exiting overlay at its current depth',
    (tester) async {
      final middle = (await bootstrap(tester))!;
      final navigator = middle.navigator!;
      final top = PredictiveBackMaterialPageRoute<void>(
        builder: (_) => const Scaffold(body: Text('third')),
      );
      navigator.push(top);
      await tester.pumpAndSettle();
      await _startBackGesture(tester, 0);
      await _updateBackGesture(tester, .4);
      await _sendBackGesture(tester, 'commitBackGesture');
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 40));
      final exitingPosition = top.animation!.value;
      expect(top.isCurrent, isFalse);
      expect(middle.isCurrent, isTrue);
      expect(exitingPosition, greaterThan(.1));
      await _startBackGesture(tester, 0);
      await tester.pump();
      expect(top.animation!.value, closeTo(exitingPosition, .000001));
      await _updateBackGesture(tester, .1);
      await tester.pump();
      expect(top.animation!.value, closeTo(exitingPosition - .1, .000001));
      expect(middle.animation!.value, 1);
      await _sendBackGesture(tester, 'cancelBackGesture');
      await tester.pumpAndSettle();
      expect(find.text('third'), findsNothing);
      expect(find.text('second'), findsOneWidget);
      expect(middle.animation!.value, 1);
      expect(navigator.userGestureInProgress, isFalse);
    },
    variant: TargetPlatformVariant.only(TargetPlatform.android),
  );

  testWidgets(
    'consecutive commits finish both overlays without replaying either page',
    (tester) async {
      final middle = (await bootstrap(tester))!;
      final navigator = middle.navigator!;
      final top = PredictiveBackMaterialPageRoute<void>(
        builder: (_) => const Scaffold(body: Text('third')),
      );
      navigator.push(top);
      await tester.pumpAndSettle();
      await _startBackGesture(tester, 0);
      await _updateBackGesture(tester, .4);
      await _sendBackGesture(tester, 'commitBackGesture');
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 40));
      await _startBackGesture(tester, 0);
      await _updateBackGesture(tester, .1);
      final startTop = top.animation!.value;
      await _sendBackGesture(tester, 'commitBackGesture');
      await tester.pump();
      expect(top.animation!.value, closeTo(startTop, .000001));
      expect(middle.isCurrent, isFalse);
      await tester.pump(const Duration(milliseconds: 40));
      expect(top.animation!.value, lessThan(startTop));
      await tester.pumpAndSettle();
      expect(find.text('third'), findsNothing);
      expect(find.text('second'), findsNothing);
      expect(find.text('push'), findsOneWidget);
      expect(navigator.userGestureInProgress, isFalse);
    },
    variant: TargetPlatformVariant.only(TargetPlatform.android),
  );

  testWidgets(
    'toggle off: gesture is not consumed, legacy transition, plain pop',
    (tester) async {
      await StorageService.setPredictiveBackEnabled(false);
      final route = await bootstrap(tester);

      await _startBackGesture(tester, 0.0);
      await tester.pump();
      // 未消费：无手势状态；回退 Fade 转场，不挂载新转场的裁剪层。
      expect(route!.popGestureInProgress, isFalse);
      expect(clipFinder(), findsNothing);
      expect(
        find.ancestor(
          of: find.text('second'),
          matching: find.byType(FadeTransition),
        ),
        findsOneWidget,
      );

      await _sendBackGesture(tester, 'commitBackGesture');
      await tester.pumpAndSettle();
      expect(find.text('second'), findsNothing);
    },
    variant: TargetPlatformVariant.only(TargetPlatform.android),
  );

  testWidgets('disposing an active route releases the navigator gesture lock', (
    tester,
  ) async {
    final route = await bootstrap(tester);
    final navigator = route!.navigator!;

    await _startBackGesture(tester, 0.0);
    await tester.pump();
    expect(navigator.userGestureInProgress, isTrue);

    navigator.removeRoute(route);
    await tester.pumpAndSettle();

    expect(navigator.userGestureInProgress, isFalse);
    await tester.tap(find.text('push'));
    await tester.pumpAndSettle();
    expect(find.text('second'), findsOneWidget);
  }, variant: TargetPlatformVariant.only(TargetPlatform.android));

  test(
    'leading corners follow layout direction and keep opposite corners square',
    () {
      const corners = ScreenCornerRadii(
        topLeft: 42,
        topRight: 40,
        bottomLeft: 36,
        bottomRight: 34,
      );

      expect(
        screenLeadingBorderRadius(corners, TextDirection.ltr),
        const BorderRadius.only(
          topLeft: Radius.circular(36),
          bottomLeft: Radius.circular(36),
        ),
      );
      expect(
        screenLeadingBorderRadius(corners, TextDirection.rtl),
        const BorderRadius.only(
          topRight: Radius.circular(36),
          bottomRight: Radius.circular(36),
        ),
      );
    },
  );

  test('page offset is aligned to physical pixels', () {
    expect(snapToPhysicalPixel(10.2, 2.5), 10.4);
    expect(snapToPhysicalPixel(10.2, 0), 10.2);
  });

  testWidgets(
    'covered page uses quarter-width parallax and 90 percent opacity',
    (tester) async {
      await tester.pumpWidget(
        MediaQuery(
          data: const MediaQueryData(devicePixelRatio: 1),
          child: Directionality(
            textDirection: TextDirection.ltr,
            child: SizedBox(
              width: 800,
              height: 600,
              child: PredictiveBackPageTransition(
                animation: const AlwaysStoppedAnimation(1),
                secondaryAnimation: const AlwaysStoppedAnimation(0.5),
                screenCorners: const ScreenCornerRadii.zero(),
                child: const Text('page'),
              ),
            ),
          ),
        ),
      );

      final coveredTransform = tester.widget<Transform>(
        find.byKey(const ValueKey('predictive_back_covered_transform')),
      );
      expect(coveredTransform.transform.storage[12], closeTo(-100, 0.001));
      final opacity = tester.widget<Opacity>(
        find.byKey(const ValueKey('predictive_back_covered_opacity')),
      );
      expect(opacity.opacity, closeTo(0.95, 0.001));
      expect(
        find.byKey(const ValueKey('predictive_back_covered_scrim')),
        findsNothing,
      );
    },
  );
  testWidgets(
    'scrim covers the revealed screen but leaves the moving page bright',
    (tester) async {
      final boundaryKey = GlobalKey();
      await tester.pumpWidget(
        MediaQuery(
          data: const MediaQueryData(devicePixelRatio: 1),
          child: Directionality(
            textDirection: TextDirection.ltr,
            child: RepaintBoundary(
              key: boundaryKey,
              child: Stack(
                children: [
                  const Positioned.fill(child: ColoredBox(color: Colors.white)),
                  PredictiveBackPageTransition(
                    animation: const AlwaysStoppedAnimation(.65),
                    secondaryAnimation: const AlwaysStoppedAnimation(0),
                    screenCorners: const ScreenCornerRadii(
                      topLeft: 42,
                      topRight: 42,
                      bottomLeft: 36,
                      bottomRight: 36,
                    ),
                    child: const ColoredBox(color: Color(0xFFFF0000)),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
      await tester.runAsync(() async {
        final boundary =
            boundaryKey.currentContext!.findRenderObject()!
                as RenderRepaintBoundary;
        final screenshot = await boundary.toImage();
        final bytes = (await screenshot.toByteData(
          format: ui.ImageByteFormat.rawRgba,
        ))!;
        int channel(int x, int y, int channel) =>
            bytes.getUint8((y * screenshot.width + x) * 4 + channel);
        // 35% back progress leaves 65% of the 0.5 black scrim: white -> 172.
        expect(channel(40, 300, 0), closeTo(172, 1));
        expect(channel(40, 300, 1), closeTo(172, 1));
        expect(channel(500, 300, 0), 255);
        expect(channel(500, 300, 1), 0);
        // The leading corner reveals the same full-screen scrim, not a bright seam.
        expect(channel(281, 1, 0), closeTo(172, 1));
        screenshot.dispose();
      });
    },
  );
}
