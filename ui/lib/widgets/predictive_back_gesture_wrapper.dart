import 'dart:math' as math;

import 'package:flutter/cupertino.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/physics.dart';
import 'package:flutter/services.dart' show PredictiveBackEvent;
import 'package:ui/services/display_geometry_service.dart';
import 'package:ui/services/storage_service.dart';

/// 与 [PageRoute] transitionsBuilder 一致的转场构建函数签名。
typedef PredictiveBackTransitionBuilder =
    Widget Function(
      BuildContext context,
      Animation<double> animation,
      Animation<double> secondaryAnimation,
      Widget child,
    );

/// 将 Android 预测性返回进度接入应用页面转场。
///
/// 页面位置始终是路由动画值的纯函数：顶层页面全宽滑出，下一层页面以
/// 四分之一屏宽做视差，并由同一个进度控制背景遮罩。手势移动时
/// 不使用额外缓动；松手后从当前位置和当前速度继续运行临界阻尼弹簧。
class PredictiveBackGestureWrapper extends StatefulWidget {
  const PredictiveBackGestureWrapper({
    super.key,
    required this.animation,
    required this.secondaryAnimation,
    required this.transitionBuilder,
    required this.child,
  });

  final Animation<double> animation;
  final Animation<double> secondaryAnimation;
  final PredictiveBackTransitionBuilder transitionBuilder;
  final Widget child;

  @override
  State<PredictiveBackGestureWrapper> createState() =>
      _PredictiveBackGestureWrapperState();
}

enum _SettleOutcome { commit, cancel }

class _PredictiveBackGestureWrapperState
    extends State<PredictiveBackGestureWrapper>
    with WidgetsBindingObserver, SingleTickerProviderStateMixin {
  static const double _springStiffness = 146;
  static const double _springDampingRatio = 1;
  static const double _maxFingerProgress = 0.999;
  static const double _returnVelocityThreshold = -1;

  ModalRoute<dynamic>? _route;
  bool _handlingGesture = false;
  double _gestureAnchor = 0;
  Stopwatch? _gestureClock;
  double _lastProgress = 0;
  int _lastSampleMicros = 0;
  double _progressVelocity = 0;
  NavigatorState? _gestureNavigator;
  bool _routeGestureActive = false;

  ScreenCornerRadii _screenCorners = const ScreenCornerRadii.zero();
  int _geometryRequest = 0;

  late final AnimationController _settleController;
  _SettleOutcome? _settleOutcome;

  @override
  void initState() {
    super.initState();
    _settleController = AnimationController.unbounded(vsync: this)
      ..addListener(_onSettleTick)
      ..addStatusListener(_onSettleStatus);
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _loadScreenCorners();
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _route = ModalRoute.of(context);
  }

  @override
  void didChangeMetrics() {
    _loadScreenCorners(refresh: true);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _settleOutcome = null;
    _settleController.dispose();
    _releaseRouteGestureAfterDispose();
    super.dispose();
  }

  bool get _predictiveBackEnabled {
    return defaultTargetPlatform == TargetPlatform.android &&
        StorageService.isPredictiveBackEnabled();
  }

  Future<void> _loadScreenCorners({bool refresh = false}) async {
    if (!_predictiveBackEnabled) return;
    final request = ++_geometryRequest;
    final corners = await DisplayGeometryService.screenCornerRadii(
      refresh: refresh,
    );
    if (!mounted || request != _geometryRequest || corners == _screenCorners) {
      return;
    }
    setState(() {
      _screenCorners = corners;
    });
  }

  @override
  bool handleStartBackGesture(PredictiveBackEvent event) {
    final route = _route;
    if (event.isButtonEvent ||
        !_predictiveBackEnabled ||
        route == null ||
        !route.isCurrent ||
        !route.popGestureEnabled) {
      return false;
    }

    _settleOutcome = null;
    _settleController.stop();

    final routeValue = (route.animation?.value ?? 1).clamp(0.0, 1.0);
    _gestureAnchor = (1 - routeValue).clamp(0.0, _maxFingerProgress);
    _handlingGesture = true;
    _resetVelocityTracking(event.progress);

    // 从控制器的当前值接管，避免在尚未完全静止的转场上产生首帧跳变。
    route.handleStartBackGesture(progress: routeValue);
    _gestureNavigator = route.navigator;
    _routeGestureActive = true;
    _applyGestureProgress(event.progress);
    setState(() {});
    return true;
  }

  @override
  void handleUpdateBackGestureProgress(PredictiveBackEvent event) {
    if (!_handlingGesture) return;
    _recordVelocitySample(event.progress);
    _applyGestureProgress(event.progress);
  }

  @override
  void handleCommitBackGesture() {
    if (!_handlingGesture) return;
    _handlingGesture = false;

    // 极少数系统会在手指明显向回运动时仍发送完成事件；这种情况按取消处理。
    if (_progressVelocity <= _returnVelocityThreshold) {
      _startSettle(_SettleOutcome.cancel);
      return;
    }
    _startSettle(_SettleOutcome.commit);
  }

  @override
  void handleCancelBackGesture() {
    if (!_handlingGesture) return;
    _handlingGesture = false;
    _startSettle(_SettleOutcome.cancel);
  }

  void _resetVelocityTracking(double progress) {
    _gestureClock = Stopwatch()..start();
    _lastProgress = progress;
    _lastSampleMicros = 0;
    _progressVelocity = 0;
  }

  void _recordVelocitySample(double progress) {
    final clock = _gestureClock;
    if (clock == null) return;
    final now = clock.elapsedMicroseconds;
    final elapsed = now - _lastSampleMicros;
    if (elapsed > 0) {
      _progressVelocity =
          (progress - _lastProgress) * Duration.microsecondsPerSecond / elapsed;
    }
    _lastProgress = progress;
    _lastSampleMicros = now;
  }

  void _applyGestureProgress(double fingerProgress) {
    final totalProgress = (_gestureAnchor + fingerProgress)
        .clamp(math.min(_gestureAnchor, 0), _maxFingerProgress)
        .toDouble();
    _route?.handleUpdateBackGestureProgress(progress: 1 - totalProgress);
  }

  void _startSettle(_SettleOutcome outcome) {
    final route = _route;
    if (route == null) return;

    final start = (route.animation?.value ?? 0).clamp(0.0, 1.0);
    final target = outcome == _SettleOutcome.commit ? 0.0 : 1.0;
    if ((start - target).abs() <= 0.001) {
      route.handleUpdateBackGestureProgress(progress: target);
      _finishSettle(outcome);
      return;
    }

    var initialVelocity = outcome == _SettleOutcome.commit
        ? -_progressVelocity
        : 0.0;
    if (outcome == _SettleOutcome.commit) {
      // 临界阻尼下，速度低于这个边界必然越过终点；钳制后可保留动量又不回弹。
      final velocityFloor = -math.sqrt(_springStiffness) * start;
      initialVelocity = math.max(initialVelocity, velocityFloor);
    }

    final criticalDamping =
        2 * _springDampingRatio * math.sqrt(_springStiffness);
    final simulation = SpringSimulation(
      SpringDescription(
        mass: 1,
        stiffness: _springStiffness,
        damping: criticalDamping,
      ),
      start,
      target,
      initialVelocity,
      tolerance: const Tolerance(distance: 0.0025, velocity: 0.0025),
    );

    _settleOutcome = outcome;
    _settleController
      ..stop()
      ..value = start
      ..animateWith(simulation);
  }

  void _onSettleTick() {
    final route = _route;
    if (!mounted || route == null || _settleOutcome == null) return;
    route.handleUpdateBackGestureProgress(
      progress: _settleController.value.clamp(0.0, 1.0),
    );
  }

  void _onSettleStatus(AnimationStatus status) {
    if (status != AnimationStatus.completed) return;
    final outcome = _settleOutcome;
    if (outcome == null) return;
    _settleOutcome = null;
    _finishSettle(outcome);
  }

  void _finishSettle(_SettleOutcome outcome) {
    final route = _route;
    _gestureClock?.stop();
    _gestureClock = null;
    try {
      if (route == null) return;
      if (outcome == _SettleOutcome.commit) {
        route.handleUpdateBackGestureProgress(progress: 0);
        route.handleCommitBackGesture();
      } else {
        route.handleUpdateBackGestureProgress(progress: 1);
        route.handleCancelBackGesture();
        if (mounted) setState(() {});
      }
    } finally {
      // 路由位于终点时可能被同步销毁，届时 route.navigator 已经为空，
      // 框架无法自行结束手势。保留开始时的 Navigator，幂等补齐生命周期。
      _releaseRouteGesture();
    }
  }

  void _releaseRouteGesture() {
    final navigator = _takeGestureNavigator();
    if (navigator?.userGestureInProgress ?? false) {
      navigator!.didStopUserGesture();
    }
  }

  void _releaseRouteGestureAfterDispose() {
    final navigator = _takeGestureNavigator();
    if (navigator == null) return;

    // dispose 发生在 Flutter 锁定组件树期间，直接通知 Navigator 会触发
    // markNeedsBuild 异常。当前帧结束后再做幂等兜底，避免残留 IgnorePointer。
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (navigator.mounted && navigator.userGestureInProgress) {
        navigator.didStopUserGesture();
      }
    });
  }

  NavigatorState? _takeGestureNavigator() {
    if (!_routeGestureActive) return null;
    final navigator = _gestureNavigator;
    _routeGestureActive = false;
    _gestureNavigator = null;
    return navigator;
  }

  @override
  Widget build(BuildContext context) {
    if (!_predictiveBackEnabled) {
      return widget.transitionBuilder(
        context,
        widget.animation,
        widget.secondaryAnimation,
        widget.child,
      );
    }

    final route = _route;
    return PredictiveBackPageTransition(
      animation: widget.animation,
      secondaryAnimation: widget.secondaryAnimation,
      isGestureDriven: () => route?.popGestureInProgress ?? false,
      screenCorners: _screenCorners,
      child: RepaintBoundary(child: widget.child),
    );
  }
}

@visibleForTesting
class PredictiveBackPageTransition extends StatelessWidget {
  const PredictiveBackPageTransition({
    super.key,
    required this.animation,
    required this.secondaryAnimation,
    required this.isGestureDriven,
    required this.screenCorners,
    required this.child,
  });

  static const double _coveredParallax = 0.25;
  static const double _maximumCoveredScrimAlpha = 0.1;
  static const Curve _programmaticCurve = _DampedSettleCurve();

  final Animation<double> animation;
  final Animation<double> secondaryAnimation;
  final ValueGetter<bool> isGestureDriven;
  final ScreenCornerRadii screenCorners;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final listenable = Listenable.merge([animation, secondaryAnimation]);
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        final devicePixelRatio = MediaQuery.devicePixelRatioOf(context);
        final textDirection = Directionality.of(context);
        final direction = textDirection == TextDirection.rtl ? -1.0 : 1.0;
        final leadingCorners = screenLeadingBorderRadius(
          screenCorners,
          textDirection,
        );

        return AnimatedBuilder(
          animation: listenable,
          child: child,
          builder: (context, child) {
            final primary = _visualProgress(animation.value);
            final covered = _visualProgress(secondaryAnimation.value);
            final primaryOffset = snapToPhysicalPixel(
              direction * (1 - primary) * width,
              devicePixelRatio,
            );
            final coveredOffset =
                -direction * covered * width * _coveredParallax;
            final coveredScrimAlpha = covered * _maximumCoveredScrimAlpha;
            final clipActive =
                animation.value > 0 &&
                animation.value < 1 &&
                leadingCorners != BorderRadius.zero;

            Widget page = ClipRSuperellipse(
              borderRadius: clipActive ? leadingCorners : BorderRadius.zero,
              clipBehavior: clipActive ? Clip.antiAlias : Clip.none,
              child: child,
            );
            page = Transform.translate(
              key: const ValueKey('predictive_back_primary_transform'),
              offset: Offset(primaryOffset, 0),
              child: page,
            );

            Widget layer = page;
            if (coveredScrimAlpha > 0) {
              layer = Stack(
                fit: StackFit.expand,
                children: [
                  page,
                  IgnorePointer(
                    child: ColoredBox(
                      key: const ValueKey('predictive_back_covered_scrim'),
                      color: const Color(
                        0xFF000000,
                      ).withValues(alpha: coveredScrimAlpha),
                    ),
                  ),
                ],
              );
            }
            layer = Transform.translate(
              key: const ValueKey('predictive_back_covered_transform'),
              offset: Offset(coveredOffset, 0),
              child: layer,
            );
            return layer;
          },
        );
      },
    );
  }

  double _visualProgress(double value) {
    final progress = value.clamp(0.0, 1.0);
    return isGestureDriven()
        ? progress
        : _programmaticCurve.transform(progress);
  }
}

@visibleForTesting
BorderRadius screenLeadingBorderRadius(
  ScreenCornerRadii corners,
  TextDirection direction,
) {
  if (direction == TextDirection.rtl) {
    return BorderRadius.only(
      topRight: Radius.circular(corners.topRight),
      bottomRight: Radius.circular(corners.bottomRight),
    );
  }
  return BorderRadius.only(
    topLeft: Radius.circular(corners.topLeft),
    bottomLeft: Radius.circular(corners.bottomLeft),
  );
}

@visibleForTesting
double snapToPhysicalPixel(double logicalOffset, double devicePixelRatio) {
  if (!logicalOffset.isFinite ||
      !devicePixelRatio.isFinite ||
      devicePixelRatio <= 0) {
    return logicalOffset;
  }
  return (logicalOffset * devicePixelRatio).round() / devicePixelRatio;
}

class _DampedSettleCurve extends Curve {
  const _DampedSettleCurve();

  static const double _response = 0.8;
  static const double _dampingRatio = 0.95;

  @override
  double transformInternal(double t) {
    if (t == 0 || t == 1) return t;
    final angularFrequency = 2 * math.pi / _response;
    final decay = -_dampingRatio * angularFrequency;
    final dampedFrequency =
        angularFrequency * math.sqrt(1 - _dampingRatio * _dampingRatio);
    final value =
        math.exp(decay * t) *
            (-math.cos(dampedFrequency * t) +
                decay / dampedFrequency * math.sin(dampedFrequency * t)) +
        1;
    return value.clamp(0.0, 1.0);
  }
}
