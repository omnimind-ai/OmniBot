import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/widgets/predictive_back_motion.dart';
import 'package:ui/widgets/predictive_back_gesture_wrapper.dart';

/// Extends Flutter's route lifecycle; there is no second animation controller.
/// Navigator still owns admission, pop results, history and exiting overlays.
mixin PredictiveBackRouteMotion<T> on PageRoute<T> {
  _RouteBackGestureObserver? _backObserver;
  NavigatorState? _gestureNavigator;
  double? _releaseVelocity;
  bool _disposed = false;
  int _gestureGeneration = 0;
  PredictiveBackRouteMotion<dynamic>? _leavingNext;
  Simulation? _joinedSimulation;

  // A popped route remains a Navigator overlay while it exits. If another
  // gesture grabs the page underneath, carry that already-travelled depth too
  // (Miuix's signed anchor). This reference comes from Navigator, not a second
  // back stack. All joined overlays are driven by this route's one controller.
  double get _physicalPosition =>
      (animation?.value ?? 0) +
      ((_leavingNext?._disposed ?? true) ? 0 : _leavingNext!._physicalPosition);

  double get _physicalVelocity =>
      (controller?.velocity ?? 0) +
      ((_leavingNext?._disposed ?? true) ? 0 : _leavingNext!._physicalVelocity);

  void _stopJoinedMotion() {
    if (!(_leavingNext?._disposed ?? true)) _leavingNext!._stopJoinedMotion();
    _joinedSimulation = null;
    controller?.stop();
  }

  void _setPhysicalPosition(double position) {
    if (_disposed) return;
    if (!(_leavingNext?._disposed ?? true)) {
      _leavingNext!._setPhysicalPosition(math.max(0, position - 1));
    }
    controller!.value = position.clamp(0.0, 1.0);
  }

  void _syncLeavingOverlay() {
    final simulation = _joinedSimulation;
    if (simulation == null || (_leavingNext?._disposed ?? true)) return;
    final elapsed = controller!.lastElapsedDuration;
    if (elapsed == null) {
      // AnimationController clears elapsed time before its terminal tick.
      // Complete the departing overlay as well, including a cancel whose
      // current route was clamped at 1 for the whole joined settle.
      if (controller!.isCompleted || controller!.isDismissed) {
        _leavingNext!._setPhysicalPosition(0);
      }
      return;
    }
    final position = simulation.x(
      elapsed.inMicroseconds / Duration.microsecondsPerSecond,
    );
    _leavingNext!._setPhysicalPosition(math.max(0, position - 1));
  }

  @override
  void didPopNext(Route<dynamic> nextRoute) {
    super.didPopNext(nextRoute);
    if (PredictiveBackMotion.enabled &&
        nextRoute is PredictiveBackRouteMotion &&
        !nextRoute._disposed) {
      _leavingNext = nextRoute;
      nextRoute.completed.then((_) {
        if (identical(_leavingNext, nextRoute)) _leavingNext = null;
      });
    }
  }

  @override
  void install() {
    super.install();
    controller!.addListener(_syncLeavingOverlay);
    _backObserver = _RouteBackGestureObserver(this);
    WidgetsBinding.instance.addObserver(_backObserver!);
  }

  @override
  bool get popGestureEnabled {
    if (!PredictiveBackMotion.enabled) return super.popGestureEnabled;
    // Keep Flutter's navigation vetoes, but allow grabbing an in-flight push
    // or cancel settle. Its controller already holds the visible position.
    return !isFirst &&
        !willHandlePopInternally &&
        // ignore: deprecated_member_use
        !hasScopedWillPopCallback &&
        popDisposition != RoutePopDisposition.doNotPop &&
        !fullscreenDialog;
  }

  @override
  Simulation? createSimulation({required bool forward}) {
    if (!PredictiveBackMotion.enabled) {
      return super.createSimulation(forward: forward);
    }
    final start = _physicalPosition;
    final target = forward ? 1.0 : 0.0;
    final velocity = _releaseVelocity ?? _physicalVelocity;
    final fromGesture = _releaseVelocity != null;
    _releaseVelocity = null;
    _stopJoinedMotion();
    final simulation =
        !fromGesture && velocity == 0 && (target - start).abs() >= 0.999
        ? PredictiveBackMotion.programmatic(start, target)
        : PredictiveBackMotion.spring(start, target, velocity);
    _joinedSimulation = simulation;
    return simulation;
  }

  @override
  void handleStartBackGesture({double progress = 0}) {
    _gestureGeneration++;
    _stopJoinedMotion();
    if (_gestureNavigator == null) {
      _gestureNavigator = navigator;
      super.handleStartBackGesture(progress: progress.clamp(0.0, 1.0));
    } else {
      // Re-grabbing a cancel settle must not increment Navigator's gesture
      // counter twice. The same route retains its one gesture reservation.
      controller!.value = progress.clamp(0.0, 1.0);
    }
  }

  @override
  void handleUpdateBackGestureProgress({required double progress}) {
    if (isCurrent) _setPhysicalPosition(progress);
  }

  void _commitGesture(double velocity) {
    if (!isCurrent) {
      handleCancelBackGesture();
      return;
    }
    _releaseVelocity = velocity;
    // Official pop immediately commits the result and retains the overlay
    // until createSimulation's velocity-seeded exit finishes.
    // Avoid TransitionRoute._handleDragEnd's reverse(from: upperBound).
    navigator!.pop();
    _releaseGesture();
  }

  @override
  void handleCommitBackGesture() => _commitGesture(0);

  @override
  void handleCancelBackGesture() {
    if (_disposed) return;
    final animation = controller!;
    final generation = _gestureGeneration;
    final simulation = PredictiveBackMotion.spring(_physicalPosition, 1, 0);
    _stopJoinedMotion();
    _joinedSimulation = simulation;
    animation.animateWith(simulation).whenCompleteOrCancel(() {
      // stop() also completes this future. A new gesture keeps ownership.
      if (!_disposed &&
          generation == _gestureGeneration &&
          !animation.isAnimating &&
          animation.isCompleted) {
        _releaseGesture();
      }
    });
  }

  void _releaseGesture() {
    final owner = _gestureNavigator;
    _gestureNavigator = null;
    if (owner != null && owner.mounted) owner.didStopUserGesture();
  }

  @override
  void dispose() {
    _disposed = true;
    WidgetsBinding.instance.removeObserver(_backObserver!);
    final owner = _gestureNavigator;
    _gestureNavigator = null;
    if (owner != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (owner.mounted) owner.didStopUserGesture();
      });
    }
    super.dispose();
  }
}

class _RouteBackGestureObserver with WidgetsBindingObserver {
  _RouteBackGestureObserver(this.route);

  final PredictiveBackRouteMotion<dynamic> route;
  bool _tracking = false;
  double _anchor = 0;
  final Stopwatch _clock = Stopwatch();
  double _lastProgress = 0;
  int _lastMicros = 0;
  double _velocity = 0;

  @override
  bool handleStartBackGesture(PredictiveBackEvent event) {
    if (event.isButtonEvent ||
        !PredictiveBackMotion.enabled ||
        !route.isCurrent ||
        !route.popGestureEnabled) {
      return false;
    }
    final position = route._physicalPosition;
    route.handleStartBackGesture(progress: position);
    _anchor = 1 - position;
    _tracking = true;
    _clock
      ..reset()
      ..start();
    _lastMicros = 0;
    _lastProgress = event.progress;
    _velocity = 0;
    _apply(event.progress);
    return true;
  }

  void _apply(double progress) {
    if (!progress.isFinite) return;
    final travel = (_anchor + progress).clamp(
      math.min(_anchor, 0),
      PredictiveBackMotion.maxFingerProgress,
    );
    route.handleUpdateBackGestureProgress(progress: 1 - travel.toDouble());
  }

  @override
  void handleUpdateBackGestureProgress(PredictiveBackEvent event) {
    if (!_tracking) return;
    // Flutter's public PredictiveBackEvent has no platform timestamp. Keep
    // timing at this adapter boundary, without introducing another channel.
    final now = _clock.elapsedMicroseconds;
    if (now > _lastMicros) {
      _velocity =
          (event.progress - _lastProgress) *
          Duration.microsecondsPerSecond /
          (now - _lastMicros);
    }
    _lastProgress = event.progress;
    _lastMicros = now;
    _apply(event.progress);
  }

  @override
  void handleCommitBackGesture() {
    if (!_tracking) return;
    _tracking = false;
    _clock.stop();
    if (_velocity <= -1) {
      route.handleCancelBackGesture();
    } else {
      route._commitGesture(-_velocity);
    }
  }

  @override
  void handleCancelBackGesture() {
    if (!_tracking) return;
    _tracking = false;
    _clock.stop();
    route.handleCancelBackGesture();
  }
}

/// Page-based entry used by GoRouter, retaining the original fallback per page.
class PredictiveBackPage<T> extends Page<T> {
  const PredictiveBackPage({
    required this.child,
    required this.fallbackBuilder,
    this.fallbackDuration = const Duration(milliseconds: 250),
    super.key,
    super.name,
    super.arguments,
    super.restorationId,
  });

  final Widget child;
  final PredictiveBackTransitionBuilder fallbackBuilder;
  final Duration fallbackDuration;

  @override
  PageRoute<T> createRoute(BuildContext context) =>
      _PredictivePageRoute<T>(this);
}

class _PredictivePageRoute<T> extends PageRoute<T>
    with MaterialRouteTransitionMixin<T>, PredictiveBackRouteMotion<T> {
  _PredictivePageRoute(PredictiveBackPage<T> page) : super(settings: page);

  PredictiveBackPage<T> get _page => settings as PredictiveBackPage<T>;

  @override
  bool get maintainState => true;

  @override
  Duration get transitionDuration => PredictiveBackMotion.enabled
      ? PredictiveBackMotion.duration
      : _page.fallbackDuration;

  @override
  Duration get reverseTransitionDuration => transitionDuration;

  @override
  Widget buildContent(BuildContext context) => _page.child;

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
      transitionBuilder: _page.fallbackBuilder,
      child: child,
    );
  }
}

/// Imperative routes use the same controller and theme as GoRouter pages.
class PredictiveBackMaterialPageRoute<T> extends MaterialPageRoute<T>
    with PredictiveBackRouteMotion<T> {
  PredictiveBackMaterialPageRoute({
    required super.builder,
    super.settings,
    super.maintainState,
    super.fullscreenDialog,
  });

  @override
  Duration get transitionDuration => PredictiveBackMotion.enabled
      ? PredictiveBackMotion.duration
      : super.transitionDuration;

  @override
  Duration get reverseTransitionDuration => PredictiveBackMotion.enabled
      ? PredictiveBackMotion.duration
      : super.reverseTransitionDuration;
}

class MiuixPageTransitionsBuilder extends PageTransitionsBuilder {
  const MiuixPageTransitionsBuilder();

  @override
  Duration get transitionDuration => PredictiveBackMotion.duration;

  @override
  Duration get reverseTransitionDuration => PredictiveBackMotion.duration;

  @override
  Widget buildTransitions<T>(
    PageRoute<T> route,
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) {
    return PredictiveBackGestureWrapper(
      animation: animation,
      secondaryAnimation: secondaryAnimation,
      transitionBuilder: (context, primary, secondary, child) =>
          const FadeForwardsPageTransitionsBuilder().buildTransitions(
            route,
            context,
            primary,
            secondary,
            child,
          ),
      child: child,
    );
  }
}
