// Page activity/keep-alive boundary adapted from FlClash lib/ui/inherited.dart,
// lib/widgets/active_polling.dart and lib/pages/home.dart at
// 7fd417fb9a9a277c6a9339a8ac87f907df7c25f7. The settle-aware UI work gate is an
// OmnibotApp adaptation; it never owns or delays ACP/runtime business events.
// See docs/third-party/flclash-NOTICE.md. SPDX-License-Identifier: GPL-3.0-only
// ignore_for_file: prefer_initializing_formals
import 'dart:async';
import 'dart:developer' as developer;

import 'package:flutter/material.dart';

import 'package:ui/widgets/navigation/route_activation.dart';

/// The tab animation is the single owner of this UI scheduling boundary.
/// No timer estimates its duration, and unrequested tabs are never warmed.
class FlClashPageActivityController extends ChangeNotifier {
  FlClashPageActivityController({required int selectedIndex})
    : _selectedIndex = selectedIndex;

  int _selectedIndex;
  bool _transitioning = false;
  bool _disposed = false;

  int get selectedIndex => _selectedIndex;
  bool get transitioning => _transitioning;

  bool readyFor(int index) =>
      !_disposed && !_transitioning && index == _selectedIndex;

  void update({required int selectedIndex, required bool transitioning}) {
    if (_selectedIndex == selectedIndex && _transitioning == transitioning) {
      return;
    }
    _selectedIndex = selectedIndex;
    _transitioning = transitioning;
    developer.Timeline.instantSync(
      'FlClashTab.activity',
      arguments: {
        'selectedIndex': selectedIndex,
        'transitioning': transitioning,
      },
    );
    notifyListeners();
  }

  Future<bool> waitUntilReady(int index, Future<void> cancelled) async {
    var waited = false;
    while (!_disposed && !readyFor(index)) {
      waited = true;
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
      if (wasCancelled || _disposed) return false;
    }
    if (_disposed) return false;
    if (waited) {
      // Let the landing frame paint before native responses can rebuild a
      // list/heatmap. Recheck: another selection may have started meanwhile.
      await WidgetsBinding.instance.endOfFrame;
      if (!readyFor(index)) return waitUntilReady(index, cancelled);
    }
    return true;
  }

  @override
  void dispose() {
    _disposed = true;
    notifyListeners();
    super.dispose();
  }
}

class FlClashPageActivityScope extends InheritedWidget {
  const FlClashPageActivityScope({
    super.key,
    required this.controller,
    required this.index,
    required this.isActive,
    required super.child,
  });

  final FlClashPageActivityController controller;
  final int index;
  final bool isActive;

  static FlClashPageActivityScope? read(BuildContext context) =>
      context.getInheritedWidgetOfExactType<FlClashPageActivityScope>();

  static bool isActiveOf(BuildContext context) =>
      context
          .dependOnInheritedWidgetOfExactType<FlClashPageActivityScope>()
          ?.isActive ??
      true;

  @override
  bool updateShouldNotify(FlClashPageActivityScope oldWidget) =>
      controller != oldWidget.controller ||
      index != oldWidget.index ||
      isActive != oldWidget.isActive;
}

/// Only UI reads and their setState publication use this mixin. Existing
/// services remain the data owner; existing request generations still decide
/// whether a result is current. Concurrent identical refreshes share one read.
class _PageLoad {
  final done = Completer<void>();
  bool started = false;
  bool dirty = false;
}

mixin FlClashPageLoadMixin<T extends StatefulWidget> on State<T> {
  final _pageLoads = <Object, _PageLoad>{};
  final _pageDisposed = Completer<void>();
  final _routeActivation = RouteActivation();

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _routeActivation.bind(ModalRoute.of(context));
  }

  Future<bool> waitForPageReady() async {
    while (mounted && !_pageDisposed.isCompleted) {
      final scope = FlClashPageActivityScope.read(context);
      if (scope != null &&
          !await scope.controller.waitUntilReady(
            scope.index,
            _pageDisposed.future,
          )) {
        return false;
      }
      if (!await _routeActivation.waitUntilReady(_pageDisposed.future)) {
        return false;
      }
      if (!mounted || _pageDisposed.isCompleted) return false;
      // Tab selection may have changed while a route/popup was covering it.
      final currentScope = FlClashPageActivityScope.read(context);
      if (currentScope == null ||
          currentScope.controller.readyFor(currentScope.index)) {
        return true;
      }
    }
    return false;
  }

  bool pageLoadIsCurrent(Object key) => _pageLoads[key]?.dirty != true;

  Future<void> runPageLoad(
    Object key,
    Future<void> Function() read, {
    bool revalidate = false,
  }) {
    final pending = _pageLoads[key];
    if (pending != null) {
      // A read already sent before leaving may hold an old snapshot. Reentry
      // invalidates its UI publication and merges refreshes into one follow-up.
      // Requests still waiting for the first admission already read fresh data.
      if (revalidate && pending.started) pending.dirty = true;
      return pending.done.future;
    }
    final load = _pageLoads[key] = _PageLoad();
    () async {
      try {
        do {
          if (!await waitForPageReady()) break;
          load.dirty = false;
          load.started = true;
          developer.Timeline.instantSync(
            'FlClashTab.load',
            arguments: {'page': runtimeType.toString(), 'load': key.toString()},
          );
          await read();
        } while (load.dirty && mounted);
        load.done.complete();
      } catch (error, stack) {
        load.done.completeError(error, stack);
      } finally {
        _pageLoads.remove(key);
      }
    }();
    return load.done.future;
  }

  Future<void> publishPageState(VoidCallback update) async {
    if (await waitForPageReady()) setState(update);
  }

  @override
  void dispose() {
    _pageDisposed.complete();
    _routeActivation.dispose();
    super.dispose();
  }
}
