// Adapted from FlClash navigation_dock.dart at
// 7fd417fb9a9a277c6a9339a8ac87f907df7c25f7. See flclash-NOTICE.md.
// SPDX-License-Identifier: GPL-3.0-only
// ignore_for_file: prefer_initializing_formals
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/physics.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/scheduler.dart';

const _pressGrowth = 1 / 8;
const _maxPressGrowth = 16.0;
const _liftSnap = 0.975;
const _hoverSwell = 0.4;
const _pullLimit = 7 / 32;
const _pullStretch = 0.5;
final _trackSpring = SpringDescription.withDurationAndBounce(
  duration: const Duration(milliseconds: 120),
);
final _pressSpring = SpringDescription.withDampingRatio(
  mass: 1,
  stiffness: 311,
  ratio: 0.593,
);
final _settleSpring = SpringDescription.withDurationAndBounce(
  duration: const Duration(milliseconds: 500),
  bounce: 0.32,
);
double _rubberBand(double overshoot, double limit) {
  if (limit <= 0) return 0;
  final pull = 1 - 1 / (overshoot.abs() * 0.55 / limit + 1);
  return limit * pull * overshoot.sign;
}

class _Spring extends ChangeNotifier implements ValueListenable<double> {
  _Spring(TickerProvider vsync, this._value) {
    _ticker = vsync.createTicker(_tick);
  }

  static const rest = 1e-3;

  late final Ticker _ticker;
  double _value;
  double _target = 0;
  SpringSimulation? _simulation;
  SpringDescription? _spring;
  double _now = 0;
  double _start = 0;

  @override
  double get value => _value;

  double get target => _simulation == null ? _value : _target;

  double get velocity => _simulation?.dx(_now - _start) ?? 0;

  double get acceleration {
    final spring = _spring;
    if (_simulation == null || spring == null) {
      return 0;
    }
    return (spring.stiffness * (_target - _value) - spring.damping * velocity) /
        spring.mass;
  }

  void springTo(double target, SpringDescription spring) {
    _simulation = SpringSimulation(
      spring,
      _value,
      target,
      velocity,
      tolerance: Tolerance(
        distance: rest,
        velocity: rest * math.sqrt(spring.stiffness / spring.mass),
      ),
    );
    _spring = spring;
    _target = target;
    if (_ticker.isActive) {
      _start = _now;
      return;
    }
    _now = _start = 0;
    _ticker.start();
  }

  void jumpTo(double value) {
    _simulation = null;
    _ticker.stop();
    _value = value;
    notifyListeners();
  }

  void _tick(Duration elapsed) {
    _now = elapsed.inMicroseconds / Duration.microsecondsPerSecond;
    final simulation = _simulation!;
    final time = _now - _start;
    if (simulation.isDone(time)) {
      _value = _target;
      _simulation = null;
      _ticker.stop();
    } else {
      _value = simulation.x(time);
    }
    notifyListeners();
  }

  @override
  void dispose() {
    _ticker.dispose();
    super.dispose();
  }
}

class FlClashElasticPress extends StatefulWidget {
  const FlClashElasticPress({
    super.key,
    this.enabled = true,
    this.listenToPointers = true,
    this.pullPastEdges = false,
    this.maxGrowth = _maxPressGrowth,
    this.maxPull,
    this.panelOf,
    required this.child,
  });

  /// Leaves a press on the button inside to the swell alone.
  static const buttonStyle = ButtonStyle(
    splashFactory: NoSplash.splashFactory,
    overlayColor: WidgetStatePropertyAll(Colors.transparent),
  );

  final bool enabled;
  final bool listenToPointers;
  final bool pullPastEdges;
  final double maxGrowth;
  final double? maxPull;

  /// Where the pressed panel lies in this box, when the box is larger.
  final Rect Function(RenderBox box)? panelOf;

  final Widget child;

  static FlClashElasticPressHandle? maybeOf(BuildContext context) =>
      context.findAncestorStateOfType<_FlClashElasticPressState>();

  /// How far a full press scales a panel whose longest side is [side].
  static double growthOf(double side, {double maxGrowth = _maxPressGrowth}) =>
      math.min(_pressGrowth * 2, maxGrowth / side);

  /// Where [box] paints under the presses around it, in its own coordinates.
  static Rect paintBoundsOf(RenderBox box) {
    final bounds = Offset.zero & box.size;
    var painted = Matrix4.identity();
    RenderObject from = box;
    for (var node = box.parent; node != null; node = node.parent) {
      if (node is _RenderPressTransform && node.hasSize) {
        painted = node._transform.multiplied(
          from.getTransformTo(node).multiplied(painted),
        );
        from = node;
      }
    }
    final rest = Matrix4.tryInvert(box.getTransformTo(from));
    if (identical(from, box) || rest == null) {
      return bounds;
    }
    return MatrixUtils.transformRect(rest.multiplied(painted), bounds);
  }

  /// Calls [onMove] as presses around [box] move it, until the result is called.
  static VoidCallback followPresses(RenderObject box, VoidCallback onMove) {
    final presses = [
      for (var node = box.parent; node != null; node = node.parent)
        if (node is _RenderPressTransform) node,
    ];
    for (final press in presses) {
      press._followers.add(onMove);
    }
    return () {
      for (final press in presses) {
        press._followers.remove(onMove);
      }
    };
  }

  /// How far a press, never a hover, swells the nearest press around [box].
  static double liftOf(RenderBox box) {
    for (var node = box.parent; node != null; node = node.parent) {
      if (node is _RenderPressTransform) {
        return node._press;
      }
    }
    return 0;
  }

  @override
  State<FlClashElasticPress> createState() => _FlClashElasticPressState();
}

/// Drives an [FlClashElasticPress] with a press it does not hear, at global positions.
abstract interface class FlClashElasticPressHandle {
  void press(Offset position);

  void drag(Offset position);

  void release();

  void hover(bool entered);
}

class _FlClashElasticPressState extends State<FlClashElasticPress>
    with TickerProviderStateMixin
    implements FlClashElasticPressHandle {
  late final _Spring _lift = _Spring(this, 0);
  late final _Spring _hover = _Spring(this, 0);
  late final _Spring _pullX = _Spring(this, 0);
  late final _Spring _pullY = _Spring(this, 0);
  late final Listenable _motion = Listenable.merge([
    _lift,
    _hover,
    _pullX,
    _pullY,
  ]);
  int? _pointer;
  var _hovers = 0;
  Offset _origin = Offset.zero;
  bool _releasing = false;

  @override
  void initState() {
    super.initState();
    _lift.addListener(_settle);
  }

  @override
  void dispose() {
    _lift.dispose();
    _hover.dispose();
    _pullX.dispose();
    _pullY.dispose();
    super.dispose();
  }

  bool get _enabled =>
      widget.enabled && !MediaQuery.disableAnimationsOf(context);

  void _reset() {
    _pointer = null;
    _releasing = false;
    _lift.jumpTo(0);
    _hover.jumpTo(0);
    _pullX.jumpTo(0);
    _pullY.jumpTo(0);
  }

  @override
  void didUpdateWidget(covariant FlClashElasticPress oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!widget.enabled && oldWidget.enabled) _reset();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_enabled) _reset();
  }

  void _swell(Offset origin) {
    _origin = origin;
    _releasing = false;
    _lift.springTo(1, _pressSpring);
  }

  void _handlePointerDown(PointerDownEvent event) {
    if (!_enabled || _pointer != null || event.buttons & kPrimaryButton == 0) {
      return;
    }
    _pointer = event.pointer;
    _swell(event.localPosition);
  }

  // A pointer's events keep reaching the hit-test path recorded at its down,
  // so a press removed mid-gesture still hears its moves and release.
  void _handlePointerMove(PointerMoveEvent event) {
    if (!mounted || event.pointer != _pointer) {
      return;
    }
    _pullToward(event.localPosition);
  }

  void _pullToward(Offset position) {
    if (!_enabled) return;
    final box = context.findRenderObject()! as RenderBox;
    final panel = widget.panelOf?.call(box) ?? Offset.zero & box.size;
    final limit = widget.maxPull ?? panel.shortestSide * _pullLimit;
    final pull = widget.pullPastEdges
        ? position -
              Offset(
                position.dx.clamp(panel.left, panel.right),
                position.dy.clamp(panel.top, panel.bottom),
              )
        : position - _origin;
    _pullX.springTo(_rubberBand(pull.dx, limit), _trackSpring);
    _pullY.springTo(_rubberBand(pull.dy, limit), _trackSpring);
  }

  void _handlePointerEnd(PointerEvent event) {
    if (!mounted || event.pointer != _pointer) {
      return;
    }
    _release();
  }

  void _release() {
    if (!_enabled) return;
    _pointer = null;
    _pullX.springTo(0, _pressSpring);
    _pullY.springTo(0, _pressSpring);
    _releasing = true;
    _settle();
  }

  // A tap lets go before the swell has risen; it rises all the way first, as
  // UIKit lifts the tab bar's lens all the way on a tap.
  void _settle() {
    if (!_releasing || _lift.value < _liftSnap) {
      return;
    }
    _releasing = false;
    _lift.springTo(0, _pressSpring);
  }

  Offset _localOf(Offset position) =>
      (context.findRenderObject()! as RenderBox).globalToLocal(position);

  @override
  void press(Offset position) {
    if (!_enabled) {
      return;
    }
    _swell(_localOf(position));
  }

  @override
  void drag(Offset position) => _pullToward(_localOf(position));

  @override
  void release() => _release();

  @override
  void hover(bool entered) {
    if (!mounted) {
      return;
    }
    _hovers = math.max(0, _hovers + (entered ? 1 : -1));
    _hover.springTo(_enabled && _hovers > 0 ? _hoverSwell : 0, _settleSpring);
  }

  @override
  Widget build(BuildContext context) {
    return Listener(
      onPointerDown: widget.listenToPointers ? _handlePointerDown : null,
      onPointerMove: widget.listenToPointers ? _handlePointerMove : null,
      onPointerUp: widget.listenToPointers ? _handlePointerEnd : null,
      onPointerCancel: widget.listenToPointers ? _handlePointerEnd : null,
      child: AnimatedBuilder(
        animation: _motion,
        builder: (_, child) => _PressTransform(
          lift: _lift.value + (1 - _lift.value) * _hover.value,
          press: _lift.value,
          pull: Offset(_pullX.value, _pullY.value),
          maxGrowth: widget.maxGrowth,
          panelOf: widget.panelOf,
          child: child,
        ),
        child: widget.child,
      ),
    );
  }
}

class _PressTransform extends SingleChildRenderObjectWidget {
  const _PressTransform({
    required this.lift,
    double? press,
    required this.pull,
    this.maxGrowth = _maxPressGrowth,
    this.panelOf,
    super.child,
  }) : press = press ?? lift;

  final double lift;
  final double press;
  final Offset pull;
  final double maxGrowth;
  final Rect Function(RenderBox box)? panelOf;

  @override
  RenderObject createRenderObject(BuildContext context) {
    return _RenderPressTransform(lift: lift, press: press, pull: pull)
      ..maxGrowth = maxGrowth
      ..panelOf = panelOf;
  }

  @override
  void updateRenderObject(
    BuildContext context,
    _RenderPressTransform renderObject,
  ) {
    renderObject
      ..lift = lift
      ..press = press
      ..pull = pull
      ..maxGrowth = maxGrowth
      ..panelOf = panelOf;
  }
}

class _RenderPressTransform extends RenderProxyBox {
  _RenderPressTransform({
    required double lift,
    required double press,
    required Offset pull,
  }) : _lift = lift,
       _press = press,
       _pull = pull;

  double _lift;
  double _press;
  Offset _pull;
  double maxGrowth = _maxPressGrowth;
  Rect Function(RenderBox box)? panelOf;
  final _followers = <VoidCallback>[];

  set lift(double value) {
    if (value == _lift) {
      return;
    }
    _lift = value;
    _moved();
  }

  set press(double value) {
    if (value == _press) {
      return;
    }
    _press = value;
    _moved();
  }

  set pull(Offset value) {
    if (value == _pull) {
      return;
    }
    _pull = value;
    _moved();
  }

  void _moved() {
    markNeedsPaint();
    for (final follower in _followers) {
      follower();
    }
  }

  Matrix4 get _transform {
    final panel = panelOf?.call(this) ?? Offset.zero & size;
    if (panel.isEmpty) return Matrix4.identity();
    final swell =
        1 +
        _lift *
            FlClashElasticPress.growthOf(
              panel.longestSide,
              maxGrowth: maxGrowth,
            );
    final scaleX = swell * (1 + _pull.dx.abs() / panel.width * _pullStretch);
    final scaleY = swell * (1 + _pull.dy.abs() / panel.height * _pullStretch);
    final center = panel.center;
    return Matrix4.diagonal3Values(scaleX, scaleY, 1)..setTranslationRaw(
      center.dx * (1 - scaleX) + _pull.dx,
      center.dy * (1 - scaleY) + _pull.dy,
      0,
    );
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    if (child == null || size.isEmpty || (_lift == 0 && _pull == .zero)) {
      layer = null;
      super.paint(context, offset);
      return;
    }
    layer = context.pushTransform(
      needsCompositing,
      offset,
      _transform,
      super.paint,
      oldLayer: layer is TransformLayer ? layer as TransformLayer? : null,
    );
  }
}
