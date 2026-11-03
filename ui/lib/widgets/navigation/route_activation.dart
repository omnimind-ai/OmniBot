import 'dart:async';
import 'dart:developer' as developer;

import 'package:flutter/widgets.dart';

/// Read-only activity from a route which keeps the page below stationary.
/// Its secondaryAnimation intentionally cannot report an overlay's return.
abstract interface class RouteLoadActivity {
  Listenable get routeLoadActivity;
  bool get hasDepartingRoute;
}

/// Observes Navigator's existing departing route, without owning navigation or
/// creating a second animation clock. Used by the container route.
mixin RouteLoadActivityMixin<T> on ModalRoute<T> implements RouteLoadActivity {
  final _departingRoutes = <TransitionRoute<dynamic>>{};
  final _routeLoadActivity = ValueNotifier(0);
  bool _activityDisposed = false;

  @override
  Listenable get routeLoadActivity => _routeLoadActivity;

  @override
  bool get hasDepartingRoute => _departingRoutes.isNotEmpty;

  @override
  void didPopNext(Route<dynamic> nextRoute) {
    super.didPopNext(nextRoute);
    if (nextRoute is! TransitionRoute<dynamic> ||
        (nextRoute.animation?.isDismissed ?? true)) {
      return;
    }
    _departingRoutes.add(nextRoute);
    _routeLoadActivity.value++;
    nextRoute.completed.then((_) {
      if (_activityDisposed) return;
      _departingRoutes.remove(nextRoute);
      _routeLoadActivity.value++;
    });
  }

  @override
  void dispose() {
    _activityDisposed = true;
    _departingRoutes.clear();
    _routeLoadActivity.dispose();
    super.dispose();
  }
}

/// A UI-work gate attached by State.didChangeDependencies. ModalRoute remains
/// the owner of route identity, currentness, animation and cancellation.
class RouteActivation extends ChangeNotifier {
  ModalRoute<dynamic>? _route;
  bool _bound = false;
  bool _left = false;
  bool _disposed = false;
  bool _landingPending = true;

  bool get ready {
    if (!_bound || _disposed || _left) return false;
    final route = _route;
    if (route == null) return true;
    return route.isActive &&
        route.isCurrent &&
        (route.animation?.isCompleted ?? true) &&
        (route.secondaryAnimation?.isDismissed ?? true) &&
        !(route.navigator?.userGestureInProgress ?? false) &&
        !(route is RouteLoadActivity &&
            (route as RouteLoadActivity).hasDepartingRoute);
  }

  void bind(ModalRoute<dynamic>? route) {
    if (_disposed) return;
    if (!_bound || !identical(_route, route)) {
      _unlisten();
      _route = route;
      _bound = true;
      _left = false;
      _landingPending = true;
      route?.animation?.addStatusListener(_statusChanged);
      route?.secondaryAnimation?.addStatusListener(_statusChanged);
      route?.navigator?.userGestureInProgressNotifier.addListener(_changed);
      if (route is RouteLoadActivity) {
        (route as RouteLoadActivity).routeLoadActivity.addListener(_changed);
      }
      route?.popped.then((_) {
        if (_disposed || !identical(_route, route)) return;
        _left = true;
        _changed();
      });
    }
    // ModalRoute.of establishes the State dependency that wakes this gate when
    // a popup changes isCurrent, even if neither route joins secondaryAnimation.
    _changed();
  }

  void _statusChanged(AnimationStatus _) => _changed();
  void _changed() {
    if (!_disposed) {
      if (!ready) _landingPending = true;
      notifyListeners();
    }
  }

  Future<bool> waitUntilReady(Future<void> cancelled) async {
    while (!_disposed && !_left) {
      if (!ready) {
        final changed = Completer<void>();
        void wake() {
          if (!changed.isCompleted) changed.complete();
        }

        addListener(wake);
        final wasCancelled = await Future.any([
          changed.future.then((_) => false),
          cancelled.then((_) => true),
        ]);
        removeListener(wake);
        if (wasCancelled) return false;
        continue;
      }
      if (!_landingPending) return true;
      // The landing frame must finish painting before native replies or model
      // catalog parsing can rebuild the full page. This is not a duration guess.
      final wasCancelled = await Future.any([
        WidgetsBinding.instance.endOfFrame.then((_) => false),
        cancelled.then((_) => true),
      ]);
      if (wasCancelled || _disposed || _left) return false;
      if (!ready) continue;
      _landingPending = false;
      developer.Timeline.instantSync(
        'OmniRoute.loadReady',
        arguments: {'route': _route?.settings.name ?? 'unrouted'},
      );
      return true;
    }
    return false;
  }

  void _unlisten() {
    final route = _route;
    route?.animation?.removeStatusListener(_statusChanged);
    route?.secondaryAnimation?.removeStatusListener(_statusChanged);
    route?.navigator?.userGestureInProgressNotifier.removeListener(_changed);
    if (route is RouteLoadActivity) {
      (route as RouteLoadActivity).routeLoadActivity.removeListener(_changed);
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _unlisten();
    notifyListeners();
    super.dispose();
  }
}
