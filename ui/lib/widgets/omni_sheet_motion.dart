// Rubber-band and spring parameters adapted from chen08209/FlClash
// lib/ui/snap_sheet.dart at 7fd417fb9a9a277c6a9339a8ac87f907df7c25f7.
// See docs/third-party/flclash-NOTICE.md. SPDX-License-Identifier: GPL-3.0-only
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/physics.dart';
import 'package:flutter/rendering.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';

final _sheetSpring = SpringDescription.withDampingRatio(
  mass: 3,
  stiffness: 1000,
  ratio: 1,
);

/// Elastic overdrag for sheets that already own their draggable extent.
/// Feed only the unconsumed upward distance after that extent reaches its
/// maximum. This controller never recognizes gestures or dismisses a route.
class OmniSheetElasticController extends ChangeNotifier
    implements ValueListenable<double> {
  OmniSheetElasticController(TickerProvider vsync)
    : _animation = AnimationController.unbounded(vsync: vsync) {
    _animation.addListener(notifyListeners);
  }

  final AnimationController _animation;
  double _pull = 0;
  double _limit = 100;

  @override
  double get value => math.max(_animation.value, 0);

  double get pull {
    final reach = math.min(value, _limit * 0.99);
    return _limit <= 0 ? 0 : _limit / 0.55 * reach / (_limit - reach);
  }

  /// Returns the distance consumed; a negative remainder belongs to the
  /// existing extent owner when a reversing finger has removed all stretch.
  double dragBy(double upwardDelta, {double limit = 100}) {
    _pull = pull;
    _animation.stop();
    _limit = math.max(limit, 0);
    final before = _pull;
    _pull = math.max(_pull + upwardDelta, 0);
    _animation.value = _limit == 0
        ? 0
        : (1 - 1 / (_pull * 0.55 / _limit + 1)) * _limit;
    return _pull - before;
  }

  void release({double velocity = 0}) {
    _animation.animateWith(
      SpringSimulation(
        _sheetSpring,
        value,
        0,
        math.min(velocity, 0.0).clamp(-400.0, 0.0),
        snapToEnd: true,
        tolerance: const Tolerance(distance: 0.1, velocity: 1),
      ),
    );
  }

  void reset() {
    _animation.stop();
    _pull = 0;
    _animation.value = 0;
  }

  @override
  void dispose() {
    _animation.removeListener(notifyListeners);
    _animation.dispose();
    super.dispose();
  }
}

/// Places deformation around the existing single pane, inside its keyboard
/// padding. Existing extent owners retain scroll, drag and dismissal policy.
class OmniSheetElasticSurface extends StatelessWidget {
  const OmniSheetElasticSurface({
    super.key,
    required this.controller,
    required this.child,
    this.keyboardInset = 0,
  });

  final OmniSheetElasticController controller;
  final Widget child;
  final double keyboardInset;

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    child: child,
    builder: (context, child) => _SheetStretch(
      stretch:
          !LiquidGlassScope.of(context) ||
              MediaQuery.disableAnimationsOf(context)
          ? 0
          : controller.value,
      keyboard: keyboardInset,
      child: child!,
    ),
  );
}

/// One gesture owner for the route's presentation and its elastic top edge.
///
/// The gesture arena leaves taps, selection and scrolling to the content.
/// Scrollable bodies contribute only deliberate overscroll from a gesture
/// that began at their top edge; a fling or a drag that started within a list
/// never becomes a sheet dismissal when it reaches that edge.
class OmniSheetMotion extends StatefulWidget {
  const OmniSheetMotion({
    super.key,
    required this.presentation,
    required this.canDrag,
    required this.onDismiss,
    required this.child,
    this.elasticEnabled = true,
  });

  final AnimationController presentation;
  final bool Function() canDrag;
  final Future<void> Function() onDismiss;
  final Widget child;
  final bool elasticEnabled;

  static SpringSimulation presentationSpring(double from, double to) =>
      SpringSimulation(
        _sheetSpring,
        from,
        to,
        0,
        snapToEnd: true,
        tolerance: const Tolerance(distance: 0.001, velocity: 0.01),
      );

  @override
  State<OmniSheetMotion> createState() => _OmniSheetMotionState();
}

class _OmniSheetMotionState extends State<OmniSheetMotion>
    with SingleTickerProviderStateMixin {
  late final _stretch = AnimationController.unbounded(vsync: this);
  late final _scrollController = _SheetScrollController(this);
  final _paneKey = GlobalKey();
  bool _dragging = false;
  bool _scrollStartedAtTop = false;
  bool _scrollFits = false;
  double _pull = 0;
  double _height = 1;
  double _stretchLimit = 0;
  double _keyboard = 0;

  bool get _reducedMotion => MediaQuery.disableAnimationsOf(context);

  @override
  void dispose() {
    _scrollController.dispose();
    _stretch.dispose();
    super.dispose();
  }

  bool _begin() {
    if (!widget.canDrag() ||
        widget.presentation.status == AnimationStatus.reverse) {
      return false;
    }
    final box = _paneKey.currentContext?.findRenderObject() as RenderBox?;
    if (box == null || !box.hasSize) return false;
    _height = math.max(box.size.height, 1);
    // Content may already own an IME inset. Stretch around the visible foot,
    // rather than magnifying the empty area under the form.
    final top = box.localToGlobal(Offset.zero).dy;
    _stretchLimit = math.min(
      (top - MediaQuery.paddingOf(context).top - 14).clamp(0, 100),
      math.max(_height - _keyboard, 0) * 0.25,
    );
    _stretch.stop();
    widget.presentation.stop();
    _pull = (1 - widget.presentation.value) * _height;
    if (_stretch.value > 0 && _stretchLimit > _stretch.value) {
      _pull =
          -_stretchLimit /
          0.55 *
          _stretch.value /
          (_stretchLimit - _stretch.value);
    }
    _dragging = true;
    return true;
  }

  void _update(double delta) {
    if (!_dragging && !_begin()) return;
    if (!widget.canDrag()) {
      _end(0);
      return;
    }
    _pull += delta;
    final progress = (1 - math.max(_pull, 0) / _height)
        .clamp(0.001, 1)
        .toDouble();
    if (widget.presentation.value != progress) {
      widget.presentation.value = progress;
    }
    final overshoot = math.max(-_pull, 0);
    _stretch.value =
        !widget.elasticEnabled || _reducedMotion || _stretchLimit == 0
        ? 0
        : (1 - 1 / (overshoot * 0.55 / _stretchLimit + 1)) * _stretchLimit;
  }

  void _end(double velocity, {bool cancelled = false}) {
    if (!_dragging) return;
    _dragging = false;
    final dismiss =
        !cancelled &&
        widget.canDrag() &&
        _pull > 0 &&
        (_pull + velocity * 0.099 > _height * 0.45 || velocity > 700);
    _pull = 0;
    if (_reducedMotion) {
      _stretch.value = 0;
      if (widget.presentation.value != 1) widget.presentation.value = 1;
    } else {
      _stretch.animateWith(
        SpringSimulation(
          _sheetSpring,
          _stretch.value,
          0,
          math.min(-velocity, 0.0).clamp(-400.0, 0.0),
          snapToEnd: true,
          tolerance: const Tolerance(distance: 0.1, velocity: 1),
        ),
      );
      // Restore while maybePop consults PopScope, as FlClash does. A permitted
      // pop takes over this same controller; a veto leaves the sheet restored.
      // Upward elastic motion leaves presentation fully open. Do not restart
      // the route transition or notify its barrier for a 1 -> 1 animation.
      if (widget.presentation.value != 1) {
        widget.presentation.animateWith(
          OmniSheetMotion.presentationSpring(widget.presentation.value, 1),
        );
      }
    }
    if (dismiss) widget.onDismiss();
  }

  bool _onScroll(ScrollNotification notification) {
    final scrollContext = notification.context;
    final position = scrollContext == null
        ? null
        : Scrollable.maybeOf(scrollContext)?.position;
    if (position is _SheetScrollPosition ||
        notification.depth != 0 ||
        notification.metrics.axis != Axis.vertical ||
        notification.context?.findAncestorWidgetOfExactType<EditableText>() !=
            null ||
        scrollContext
                ?.findAncestorWidgetOfExactType<DraggableScrollableSheet>() !=
            null) {
      return false;
    }
    final metrics = notification.metrics;
    final reversed = metrics.axisDirection == AxisDirection.up;
    final atTop = reversed
        ? metrics.pixels >= metrics.maxScrollExtent - 0.5
        : metrics.pixels <= metrics.minScrollExtent + 0.5;
    if (notification is ScrollStartNotification) {
      _scrollStartedAtTop = notification.dragDetails != null && atTop;
      _scrollFits = metrics.maxScrollExtent <= metrics.minScrollExtent;
    } else if (notification is OverscrollNotification &&
        notification.dragDetails != null &&
        _scrollStartedAtTop) {
      final delta = reversed
          ? notification.overscroll
          : -notification.overscroll;
      if ((atTop && delta > 0) || (_scrollFits && delta < 0)) {
        _update(delta);
        return _dragging;
      }
    } else if (notification is ScrollEndNotification) {
      if (_scrollStartedAtTop) {
        _end(
          notification.dragDetails?.primaryVelocity ?? 0,
          cancelled: notification.dragDetails == null,
        );
      }
      _scrollStartedAtTop = false;
    }
    return false;
  }

  @override
  Widget build(BuildContext context) {
    _keyboard = MediaQuery.viewInsetsOf(context).bottom;
    return Listener(
      // Flutter's scroll recognizer can report an accepted pointer cancel as
      // a zero-velocity drag end. Observe only cancellation, before the arena
      // dispatch, so neither edge handoff can turn it into a dismissal.
      onPointerCancel: (_) {
        _end(0, cancelled: true);
        _scrollStartedAtTop = false;
      },
      child: NotificationListener<ScrollNotification>(
        onNotification: _onScroll,
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onVerticalDragStart: (_) => _begin(),
          onVerticalDragUpdate: (details) => _update(details.primaryDelta ?? 0),
          onVerticalDragEnd: (details) => _end(details.primaryVelocity ?? 0),
          onVerticalDragCancel: () => _end(0, cancelled: true),
          child: AnimatedBuilder(
            animation: _stretch,
            child: PrimaryScrollController(
              controller: _scrollController,
              automaticallyInheritForPlatforms: TargetPlatform.values.toSet(),
              child: widget.child,
            ),
            builder: (context, child) => _SheetStretch(
              key: _paneKey,
              stretch: !widget.elasticEnabled || _reducedMotion
                  ? 0
                  : math.max(_stretch.value, 0),
              keyboard: _keyboard,
              child: child!,
            ),
          ),
        ),
      ),
    );
  }
}

/// Mirrors FlClash's scroll-position handoff so changing direction can return
/// to content scrolling in the same gesture, without a second recognizer.
class _SheetScrollController extends ScrollController {
  _SheetScrollController(this.motion);

  final _OmniSheetMotionState motion;

  @override
  ScrollPosition createScrollPosition(
    ScrollPhysics physics,
    ScrollContext context,
    ScrollPosition? oldPosition,
  ) => _SheetScrollPosition(
    motion: motion,
    physics: physics,
    context: context,
    oldPosition: oldPosition,
  );
}

class _SheetScrollPosition extends ScrollPositionWithSingleContext {
  _SheetScrollPosition({
    required this.motion,
    required super.physics,
    required super.context,
    super.oldPosition,
  });

  final _OmniSheetMotionState motion;
  bool get _atTop => axisDirection == AxisDirection.up
      ? pixels >= maxScrollExtent - 0.5
      : pixels <= minScrollExtent + 0.5;

  @override
  Drag drag(DragStartDetails details, VoidCallback dragCancelCallback) =>
      _SheetContentDrag(
        this,
        super.drag(details, dragCancelCallback),
        startedAtTop: _atTop,
      );
}

class _SheetContentDrag implements Drag {
  _SheetContentDrag(this.position, this.content, {required this.startedAtTop});

  final _SheetScrollPosition position;
  final Drag content;
  final bool startedAtTop;
  bool _held = false;

  @override
  void update(DragUpdateDetails details) {
    final motion = position.motion;
    final delta = details.primaryDelta ?? 0;
    final fits = position.maxScrollExtent <= position.minScrollExtent;
    if (delta == 0 || !motion.widget.canDrag()) {
      content.update(details);
      return;
    }
    if (_held && !fits && delta < 0 && motion._pull > 0) {
      final consumed = math.min(-delta, motion._pull);
      motion._update(-consumed);
      if (motion._pull <= 0) {
        _held = false;
        motion._end(0, cancelled: true);
        if (consumed < -delta) {
          content.update(
            DragUpdateDetails(
              sourceTimeStamp: details.sourceTimeStamp,
              delta: Offset(0, delta + consumed),
              primaryDelta: delta + consumed,
              globalPosition: details.globalPosition,
              localPosition: details.localPosition,
            ),
          );
        }
      }
      return;
    }
    if (_held || (startedAtTop && position._atTop && (delta > 0 || fits))) {
      motion._update(delta);
      _held = motion._dragging;
      if (_held) return;
    }
    content.update(details);
  }

  @override
  void end(DragEndDetails details) {
    content.end(
      _held
          ? DragEndDetails(
              primaryVelocity: 0,
              globalPosition: details.globalPosition,
            )
          : details,
    );
    position.motion._end(_held ? details.primaryVelocity ?? 0 : 0);
  }

  @override
  void cancel() {
    content.cancel();
    position.motion._end(0, cancelled: true);
  }
}

class _SheetStretch extends SingleChildRenderObjectWidget {
  const _SheetStretch({
    super.key,
    required this.stretch,
    required this.keyboard,
    required super.child,
  });

  final double stretch;
  final double keyboard;

  @override
  RenderObject createRenderObject(BuildContext context) =>
      _RenderSheetStretch(stretch, keyboard);

  @override
  void updateRenderObject(
    BuildContext context,
    _RenderSheetStretch renderObject,
  ) {
    renderObject.update(stretch, keyboard);
  }
}

class _RenderSheetStretch extends RenderProxyBox {
  _RenderSheetStretch(this._stretch, this._keyboard);

  double _stretch;
  double _keyboard;
  final _layer = LayerHandle<TransformLayer>();

  void update(double stretch, double keyboard) {
    if (_stretch == stretch && _keyboard == keyboard) return;
    _stretch = stretch;
    _keyboard = keyboard;
    markNeedsPaint();
    markNeedsSemanticsUpdate();
  }

  Matrix4 get _transform {
    final foot = size.height - _keyboard;
    if (foot <= 0) return Matrix4.identity();
    return Matrix4.identity()
      ..translateByDouble(0, -_stretch, 0, 1)
      ..scaleByDouble(1, 1 + _stretch / foot, 1, 1);
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    if (child == null) return;
    _layer.layer = context.pushTransform(
      needsCompositing,
      offset,
      _transform,
      super.paint,
      oldLayer: _layer.layer,
    );
  }

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) =>
      result.addWithPaintTransform(
        transform: _transform,
        position: position,
        hitTest: (result, position) =>
            super.hitTestChildren(result, position: position),
      );

  @override
  void applyPaintTransform(RenderBox child, Matrix4 transform) {
    transform.multiply(_transform);
  }

  @override
  Rect get paintBounds =>
      MatrixUtils.transformRect(_transform, Offset.zero & size);

  @override
  void dispose() {
    _layer.layer = null;
    super.dispose();
  }
}
