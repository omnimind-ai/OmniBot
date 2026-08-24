import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/services/display_geometry_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/widgets/predictive_back_gesture_wrapper.dart';

/// 使用 PredictiveBackGestureWrapper 作为转场的测试路由,
/// 结构与 go_router_manager 中的 _buildPage(Fade 回退分支)一致。
class _WrapperRoute extends PageRouteBuilder<String> {
  _WrapperRoute({required this.onRouteReady})
    : super(
        transitionDuration: const Duration(milliseconds: 250),
        reverseTransitionDuration: const Duration(milliseconds: 250),
        pageBuilder: (context, animation, secondaryAnimation) {
          return Builder(
            builder: (context) {
              onRouteReady(ModalRoute.of(context));
              return const Scaffold(body: Text('second'));
            },
          );
        },
        transitionsBuilder: (context, animation, secondaryAnimation, child) {
          return PredictiveBackGestureWrapper(
            animation: animation,
            secondaryAnimation: secondaryAnimation,
            transitionBuilder:
                (context, animation, secondaryAnimation, child) =>
                    FadeTransition(opacity: animation, child: child),
            child: child,
          );
        },
      );

  final ValueChanged<ModalRoute<dynamic>?> onRouteReady;
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
  Future<ModalRoute<dynamic>?> bootstrap(WidgetTester tester) async {
    ModalRoute<dynamic>? route;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (context) {
              return TextButton(
                onPressed: () {
                  Navigator.of(
                    context,
                  ).push(_WrapperRoute(onRouteReady: (r) => route = r));
                },
                child: const Text('push'),
              );
            },
          ),
        ),
      ),
    );
    await tester.tap(find.text('push'));
    await tester.pumpAndSettle();
    expect(find.text('second'), findsOneWidget);
    return route;
  }

  Finder clipFinder() => find.ancestor(
    of: find.text('second'),
    matching: find.byType(ClipRSuperellipse),
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
      final clip = tester.widget<ClipRSuperellipse>(clipFinder());
      expect(
        clip.borderRadius,
        const BorderRadius.only(
          topLeft: Radius.circular(42),
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
        tester.widget<ClipRSuperellipse>(clipFinder()).borderRadius,
        BorderRadius.zero,
      );
      expect(
        tester.widget<ClipRSuperellipse>(clipFinder()).clipBehavior,
        Clip.none,
      );
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
      for (var i = 0; i < 10 && route.isCurrent; i++) {
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

  testWidgets(
    'disposing an active route releases the navigator gesture lock',
    (tester) async {
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
    },
    variant: TargetPlatformVariant.only(TargetPlatform.android),
  );

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
          topLeft: Radius.circular(42),
          bottomLeft: Radius.circular(36),
        ),
      );
      expect(
        screenLeadingBorderRadius(corners, TextDirection.rtl),
        const BorderRadius.only(
          topRight: Radius.circular(40),
          bottomRight: Radius.circular(34),
        ),
      );
    },
  );

  test('page offset is aligned to physical pixels', () {
    expect(snapToPhysicalPixel(10.2, 2.5), 10.4);
    expect(snapToPhysicalPixel(10.2, 0), 10.2);
  });

  testWidgets('covered page uses quarter-width parallax and a light scrim', (
    tester,
  ) async {
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
              isGestureDriven: () => true,
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
    expect(find.byType(Opacity), findsNothing);
    final scrim = tester.widget<ColoredBox>(
      find.byKey(const ValueKey('predictive_back_covered_scrim')),
    );
    expect(scrim.color.a, closeTo(0.05, 0.001));
  });
}
