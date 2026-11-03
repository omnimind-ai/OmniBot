// Adapted from FlClash lib/pages/home.dart (_HomePageView, slide branch)
// at 7fd417fb9a9a277c6a9339a8ac87f907df7c25f7.
// GPL-3.0; see docs/third-party/flclash-NOTICE.md.
import 'dart:developer' as developer;

import 'package:flutter/material.dart';
import 'package:ui/widgets/flclash/page_activity.dart';

typedef FlClashTabBuilder = Widget Function(
  BuildContext context,
  int index,
  bool active,
);

/// FlClash's optional slide tab motion, selected here by the application's UI.
/// Nonadjacent destinations are temporarily adjacent along [scrollDirection],
/// so intervening pages are never opened. Retargeting keeps the visible pair in
/// place: it can reverse immediately, or continue to the latest third destination
/// on landing.
class FlClashTabView extends StatefulWidget {
  const FlClashTabView({
    super.key,
    required this.selectedIndex,
    required this.itemCount,
    required this.builder,
    this.scrollDirection = Axis.horizontal,
  }) : assert(selectedIndex >= 0 && selectedIndex < itemCount);

  static const duration = kTabScrollDuration;
  static const curve = Curves.easeOut;

  final int selectedIndex;
  final int itemCount;
  final FlClashTabBuilder builder;
  final Axis scrollDirection;

  @override
  State<FlClashTabView> createState() => _FlClashTabViewState();
}

class _FlClashTabViewState extends State<FlClashTabView> {
  late final _activity = FlClashPageActivityController(
    selectedIndex: widget.selectedIndex,
  );
  late final PageController _controller = PageController(
    initialPage: widget.selectedIndex,
  );
  late final _builtTabs = <int>{widget.selectedIndex};
  List<int>? _order;
  ({int from, int to})? _flight;
  int? _toward;
  int _slide = 0;
  bool _reducedMotion = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final reducedMotion = MediaQuery.disableAnimationsOf(context);
    if (reducedMotion != _reducedMotion) {
      _reducedMotion = reducedMotion;
      if (reducedMotion && _controller.hasClients) {
        _jumpTo(widget.selectedIndex);
      }
    }
  }

  @override
  void didUpdateWidget(covariant FlClashTabView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.scrollDirection != widget.scrollDirection &&
        _controller.hasClients) {
      // A driven slide targets pixels on the previous axis. Settle the latest
      // selection before changing axes, retaining the same kept-alive owners.
      _jumpTo(widget.selectedIndex);
    } else if (oldWidget.selectedIndex != widget.selectedIndex) {
      _toPage(widget.selectedIndex);
    }
  }

  void _toPage(int index) {
    if (!_controller.hasClients) return;
    if (_reducedMotion) {
      _jumpTo(index);
      return;
    }
    final flight = _flight;
    if (flight != null) {
      _activity.update(selectedIndex: index, transitioning: true);
      // Never round and jump a moving page: both currently visible owners must
      // retain their fractional offset. A third destination is read from
      // selectedIndex on landing, so later taps replace or cancel that request.
      if ((index == flight.from || index == flight.to) && index != _toward) {
        _animateTo(index);
      }
      return;
    }
    final current = _controller.page?.round() ?? index;
    _beginSlide(current, index);
  }

  void _jumpTo(int index) {
    ++_slide;
    _flight = null;
    _toward = null;
    _order = null;
    _builtTabs.add(index);
    _controller.jumpToPage(index);
    _activity.update(selectedIndex: index, transitioning: false);
  }

  void _beginSlide(int current, int index) {
    if (current == index) return;
    _flight = (from: current, to: index);
    _toward = index;
    _activity.update(selectedIndex: widget.selectedIndex, transitioning: true);
    // Same warp as FlClash / TabBarView: keep the existing child identities.
    if ((index - current).abs() > 1) {
      final adjacent = index > current ? index - 1 : index + 1;
      setState(() {
        _order = List.generate(widget.itemCount, (item) => item)
          ..[adjacent] = current
          ..[current] = adjacent;
      });
      _controller.jumpToPage(adjacent);
    }
    if (_builtTabs.add(index)) {
      final preparation = ++_slide;
      developer.Timeline.instantSync(
        'FlClashTab.prepare',
        arguments: {'index': index},
      );
      // As DeferredPushRouteMixin prepares a pushed route, give only this
      // requested page one layout frame before starting its slide clock.
      // The adjacent cache slots of other tabs remain lightweight placeholders.
      setState(() {});
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && preparation == _slide && _flight != null) {
          _animateTo(index);
        }
      });
    } else {
      _animateTo(index);
    }
  }

  Future<void> _animateTo(int index) async {
    final slide = ++_slide;
    _toward = index;
    developer.Timeline.instantSync(
      'FlClashTab.slide',
      arguments: {'index': index},
    );
    await _controller.animateToPage(
      _order?.indexOf(index) ?? index,
      duration: FlClashTabView.duration,
      curve: FlClashTabView.curve,
    );
    if (!mounted || slide != _slide) return;
    final warpedPosition = _order?.indexOf(index) ?? index;
    setState(() {
      _order = null;
      _flight = null;
      _toward = null;
    });
    if (warpedPosition != index) {
      _controller.jumpToPage(index);
    }
    if (widget.selectedIndex != index) {
      _beginSlide(index, widget.selectedIndex);
    } else {
      _activity.update(selectedIndex: index, transitioning: false);
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    _activity.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return _TabSelection(
      selectedIndex: widget.selectedIndex,
      activity: _activity,
      child: PageView.builder(
        controller: _controller,
        scrollDirection: widget.scrollDirection,
        physics: const NeverScrollableScrollPhysics(),
        allowImplicitScrolling: true,
        itemCount: widget.itemCount,
        findChildIndexCallback: (key) {
          if (key is! ValueKey<int>) return null;
          return _order?.indexOf(key.value) ?? key.value;
        },
        itemBuilder: (context, position) {
          final index = _order?[position] ?? position;
          return KeyedSubtree(
            key: ValueKey(index),
            child: _builtTabs.contains(index)
                ? _KeptTab(index: index, builder: widget.builder)
                : const SizedBox.expand(),
          );
        },
      ),
    );
  }
}

class _TabSelection extends InheritedWidget {
  const _TabSelection({
    required this.selectedIndex,
    required this.activity,
    required super.child,
  });
  final int selectedIndex;
  final FlClashPageActivityController activity;

  @override
  bool updateShouldNotify(_TabSelection oldWidget) =>
      selectedIndex != oldWidget.selectedIndex;
}

class _KeptTab extends StatefulWidget {
  const _KeptTab({required this.index, required this.builder});
  final int index;
  final FlClashTabBuilder builder;

  @override
  State<_KeptTab> createState() => _KeptTabState();
}

class _KeptTabState extends State<_KeptTab> with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;

  @override
  Widget build(BuildContext context) {
    super.build(context);
    // Kept-alive children also depend on this scope, so leaving a tab really
    // disables its focus, tickers and PopScopes even when it is offscreen.
    final selection = context
        .dependOnInheritedWidgetOfExactType<_TabSelection>()!;
    final active = selection.selectedIndex == widget.index;
    return FlClashPageActivityScope(
      controller: selection.activity,
      index: widget.index,
      isActive: active,
      child: TickerMode(
        enabled: active,
        child: HeroMode(
          enabled: active,
          child: ExcludeFocus(
            excluding: !active,
            child: ExcludeSemantics(
              excluding: !active,
              child: IgnorePointer(
                ignoring: !active,
                child: RepaintBoundary(
                  child: widget.builder(context, widget.index, active),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
