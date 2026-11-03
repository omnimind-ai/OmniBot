// Adapted from FlClash ui/navigation_dock.dart and ui/goo.dart at
// 7fd417fb9a9a277c6a9339a8ac87f907df7c25f7. Shared solid and liquid-glass surface paths.
// SPDX-License-Identifier: GPL-3.0-only
// Preserve upstream render-object constructor layout.
// ignore_for_file: prefer_initializing_formals
part of 'search_header.dart';

const double _pressGrowth = 1 / 8;
const double _maxPressGrowth = 16;
const double _hoverSwell = 0.4;
const double _pullLimit = 7 / 32;
const double _pullStretch = 0.5;
const double _liftSnap = 0.975;
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
  final pull = 1 - 1 / (overshoot.abs() * 0.55 / limit + 1);
  return limit * pull * overshoot.sign;
}

/// A value that springs toward a target the finger may move every frame.
///
/// [AnimationController.animateWith] restarts its ticker, whose first frame
/// reads no elapsed time, so retargeting it on every pointer move holds the
/// value still for as long as the finger keeps moving.
class _Spring extends ChangeNotifier implements ValueListenable<double> {
  _Spring(TickerProvider vsync, this._value) {
    _ticker = vsync.createTicker(_tick);
  }

  static const double rest = 1e-3;

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

/// Grows under a press and stretches after a dragging finger, springing back
/// on release, as the bar's lens does.
///
/// The press only paints: layout, hit testing, and anything anchored to the
/// child, such as a popup menu or a tooltip, see it at rest.
class _ElasticPress extends StatefulWidget {
  const _ElasticPress({super.key, required this.child});

  final Widget child;

  /// How far a full press scales a panel whose longest side is [side].
  static double growthOf(double side) =>
      math.min(_pressGrowth * 2, _maxPressGrowth / side);

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
  State<_ElasticPress> createState() => _ElasticPressState();
}

/// Drives an [_ElasticPress] with a press it does not hear, at global positions.
abstract interface class _ElasticPressHandle {
  void press(Offset position);

  void drag(Offset position);

  void release();

  void hover(bool entered);
}

class _ElasticPressState extends State<_ElasticPress>
    with TickerProviderStateMixin
    implements _ElasticPressHandle {
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

  void _swell(Offset origin) {
    _origin = origin;
    _releasing = false;
    _lift.springTo(1, _pressSpring);
  }

  void _handlePointerDown(PointerDownEvent event) {
    if (_pointer != null || event.buttons & kPrimaryButton == 0) {
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
    final box = context.findRenderObject()! as RenderBox;
    final panel = Offset.zero & box.size;
    final limit = panel.shortestSide * _pullLimit;
    final pull = position - _origin;
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
    _hover.springTo(_hovers > 0 ? _hoverSwell : 0, _settleSpring);
  }

  @override
  Widget build(BuildContext context) {
    return Listener(
      onPointerDown: _handlePointerDown,
      onPointerMove: _handlePointerMove,
      onPointerUp: _handlePointerEnd,
      onPointerCancel: _handlePointerEnd,
      child: AnimatedBuilder(
        animation: _motion,
        builder: (_, child) => _PressTransform(
          lift: _lift.value + (1 - _lift.value) * _hover.value,
          press: _lift.value,
          pull: Offset(_pullX.value, _pullY.value),
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
    super.child,
  }) : press = press ?? lift;

  final double lift;
  final double press;
  final Offset pull;

  @override
  RenderObject createRenderObject(BuildContext context) {
    return _RenderPressTransform(lift: lift, press: press, pull: pull);
  }

  @override
  void updateRenderObject(
    BuildContext context,
    _RenderPressTransform renderObject,
  ) {
    renderObject
      ..lift = lift
      ..press = press
      ..pull = pull;
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
    final panel = Offset.zero & size;
    final swell = 1 + _lift * _ElasticPress.growthOf(panel.longestSide);
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

// At rest no closer than buttons stand in a bar, so none bulges toward its
// neighbor until a press swells it, as iOS 27 merges a search field it swells
// with its cancel button.
const double _restBlend = 8;
const double _pressBlend = 8;
const double _neckStep = 0.5;

/// Draws the [_GooPane]s below, pills in rows, as one surface that flows
/// together where two neighbors in a row come close, as iOS 27 draws controls
/// whose glass shares a container. `TonalButtonTheme` and `TonalButtonGroup`
/// below put their buttons on panes.
class _GooSurface extends StatelessWidget {
  const _GooSurface({required this.color, required this.child});

  final Color color;
  final Widget child;

  static bool of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<_GooScope>() != null;

  static const buttonStyle = ButtonStyle(
    backgroundColor: WidgetStatePropertyAll(Colors.transparent),
    backgroundBuilder: _paneBehind,
  );

  static Widget _paneBehind(
    BuildContext context,
    Set<WidgetState> states,
    Widget? child,
  ) => _GooPane(child: child);

  @override
  Widget build(BuildContext context) {
    return _GooScope(
      child: _GooSurfaceBox(
        glass: LiquidGlassScope.of(context)
            ? LiquidGlassStyle.regular(Theme.of(context).colorScheme)
            : null,
        color: color,
        shadows: const [],
        child: child,
      ),
    );
  }
}

class _GooScope extends InheritedWidget {
  const _GooScope({required super.child});

  @override
  bool updateShouldNotify(_GooScope oldWidget) => false;
}

/// A pill of the [_GooSurface] above, swelling with the presses around it.
class _GooPane extends SingleChildRenderObjectWidget {
  const _GooPane({super.child});

  @override
  RenderObject createRenderObject(BuildContext context) => _RenderGooPane();
}

class _RenderGooPane extends RenderProxyBox {
  _RenderGooSurface? _surface;
  VoidCallback? _unfollow;

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    for (var node = parent; node != null; node = node.parent) {
      if (node is _RenderGooSurface) {
        _surface = node.._panes.add(this);
        _unfollow = _ElasticPress.followPresses(this, node.markNeedsPaint);
        node.markNeedsPaint();
        return;
      }
    }
  }

  @override
  void detach() {
    _unfollow?.call();
    _unfollow = null;
    _surface
      ?.._panes.remove(this)
      ..markNeedsPaint();
    _surface = null;
    super.detach();
  }

  @override
  void performLayout() {
    super.performLayout();
    _surface?.markNeedsPaint();
  }
}

class _GooSurfaceBox extends SingleChildRenderObjectWidget {
  const _GooSurfaceBox({
    required this.glass,
    required this.color,
    required this.shadows,
    super.child,
  });

  final LiquidGlassStyle? glass;
  final Color color;
  final List<BoxShadow> shadows;

  @override
  _RenderGooSurface createRenderObject(BuildContext context) =>
      _RenderGooSurface(glass: glass, color: color, shadows: shadows);

  @override
  void updateRenderObject(
    BuildContext context,
    _RenderGooSurface renderObject,
  ) {
    renderObject
      ..glass = glass
      ..color = color
      ..shadows = shadows;
  }
}

typedef _Placed = ({Rect rect, double lift});

class _RenderGooSurface extends RenderProxyBox {
  _RenderGooSurface({
    required LiquidGlassStyle? glass,
    required Color color,
    required List<BoxShadow> shadows,
  }) : _glass = glass,
       _color = color,
       _shadows = shadows;

  final _panes = <_RenderGooPane>{};
  late final _pane = LiquidGlassPane(markNeedsPaint);
  Offset? _touch;
  ({List<Rect> pills, List<double> blends, Path path})? _outline;

  LiquidGlassStyle? _glass;
  set glass(LiquidGlassStyle? value) {
    if (value == _glass) {
      return;
    }
    if ((value == null) != (_glass == null)) {
      markNeedsCompositingBitsUpdate();
    }
    _glass = value;
    markNeedsPaint();
  }

  Color _color;
  set color(Color value) {
    if (value == _color) {
      return;
    }
    _color = value;
    markNeedsPaint();
  }

  List<BoxShadow> _shadows;
  set shadows(List<BoxShadow> value) {
    if (listEquals(value, _shadows)) {
      return;
    }
    _shadows = value;
    markNeedsPaint();
  }

  @override
  bool get alwaysNeedsCompositing => _glass != null;

  @override
  void handleEvent(PointerEvent event, BoxHitTestEntry entry) {
    if (event is PointerDownEvent || event is PointerMoveEvent) {
      _touch = event.localPosition;
    }
  }

  @override
  void dispose() {
    _pane.dispose();
    super.dispose();
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    final panes = _rows([
      for (final pane in _panes)
        if (pane.attached && pane.hasSize && !pane.size.isEmpty)
          (
            rect: MatrixUtils.transformRect(
              pane.getTransformTo(this),
              _ElasticPress.paintBoundsOf(pane),
            ),
            lift: _ElasticPress.liftOf(pane),
          ),
    ]);
    final pills = [for (final pane in panes) pane.rect];
    if (pills.isEmpty) {
      _pane.clear();
      super.paint(context, offset);
      return;
    }
    final blends = [
      for (var index = 1; index < panes.length; index++)
        _inRow(panes[index - 1].rect, panes[index].rect)
            ? _restBlend +
                  _pressBlend *
                      math.max(panes[index - 1].lift, panes[index].lift)
            : 0.0,
    ];
    final bounds = pills.reduce((a, b) => a.expandToInclude(b));
    final path = _outlineOf(pills, blends);
    final canvas = context.canvas;
    _paintShadows(canvas, path.shift(offset), bounds.shift(offset));
    final glass = _glass;
    if (glass == null) {
      _pane.clear();
      canvas.drawPath(path.shift(offset), Paint()..color = _color);
    } else {
      final pressed = panes.reduce((a, b) => b.lift > a.lift ? b : a);
      final touch = _touch;
      _pane.paint(
        context,
        bounds.shift(offset),
        shape: LiquidGlassGoo(path.shift(-bounds.topLeft), [
          for (final pill in pills) pill.shift(-bounds.topLeft),
        ], blends),
        style: glass,
        glow: LiquidGlassGlow(
          center:
              (touch != null && pressed.rect.contains(touch)
                  ? touch
                  : pressed.rect.center) -
              bounds.topLeft,
          reach: pressed.rect.height / math.sqrt2,
          strength: pressed.lift * 0.16,
        ),
      );
    }
    super.paint(context, offset);
  }

  static bool _inRow(Rect a, Rect b) =>
      (a.center.dy - b.center.dy).abs() < math.min(a.height, b.height) / 2;

  /// [panes] in rows from the top, each from the left.
  static List<_Placed> _rows(List<_Placed> panes) {
    final rows = <List<_Placed>>[];
    for (final pane
        in panes
          ..removeWhere((pane) => pane.rect.isEmpty)
          ..sort((a, b) => a.rect.center.dy.compareTo(b.rect.center.dy))) {
      if (rows.isNotEmpty && _inRow(rows.last.first.rect, pane.rect)) {
        rows.last.add(pane);
      } else {
        rows.add([pane]);
      }
    }
    return [
      for (final row in rows)
        ...row..sort((a, b) => a.rect.center.dx.compareTo(b.rect.center.dx)),
    ];
  }

  Path _outlineOf(List<Rect> pills, List<double> blends) {
    final outline = _outline;
    if (outline != null &&
        listEquals(outline.pills, pills) &&
        listEquals(outline.blends, blends)) {
      return outline.path;
    }
    final path = gooOutline(pills, blends);
    _outline = (pills: pills, blends: blends, path: path);
    return path;
  }

  void _paintShadows(Canvas canvas, Path path, Rect bounds) {
    if (_shadows.isEmpty) {
      return;
    }
    final reach = _shadows.fold(
      0.0,
      (reach, shadow) =>
          math.max(reach, shadow.offset.distance + shadow.blurRadius * 2),
    );
    canvas
      ..save()
      ..clipPath(
        Path()
          ..fillType = PathFillType.evenOdd
          ..addRect(bounds.inflate(reach))
          ..addPath(path, Offset.zero),
      );
    for (final shadow in _shadows) {
      canvas.drawPath(path.shift(shadow.offset), shadow.toPaint());
    }
    canvas.restore();
  }
}

/// The outline of [pills], as gooGeometry in shaders/liquid_glass.frag blends
/// their distances.
@visibleForTesting
Path gooOutline(List<Rect> pills, List<double> blends) {
  var outline = Path();
  for (final pill in pills) {
    outline.addRSuperellipse(
      RSuperellipse.fromRectAndRadius(
        pill,
        Radius.circular(pill.shortestSide / 2),
      ),
    );
  }
  for (var index = 1; index < pills.length; index++) {
    final neck = _neck(pills[index - 1], pills[index], blends[index - 1]);
    if (neck != null) {
      outline = Path.combine(PathOperation.union, outline, neck);
    }
  }
  return outline;
}

double _pillDistance(Rect pill, Offset point) =>
    _roundedDistance(pill, pill.shortestSide / 2, point);

double _roundedDistance(Rect rect, double radius, Offset point) {
  final round = math.min(radius, rect.shortestSide / 2);
  final qx = (point.dx - rect.center.dx).abs() - rect.width / 2 + round;
  final qy = (point.dy - rect.center.dy).abs() - rect.height / 2 + round;
  return Offset(math.max(qx, 0), math.max(qy, 0)).distance +
      math.min(math.max(qx, qy), 0) -
      round;
}

double _smoothMin(double a, double b, double blend) {
  final h = (0.5 + 0.5 * (b - a) / blend).clamp(0.0, 1.0);
  return lerpDouble(b, a, h)! - blend * h * (1 - h);
}

/// What [a] and [b] add to their own outlines between the centers of their
/// facing ends: a neck, or a bulge from each toward the other. Only where they
/// flow together, so each keeps its superellipse elsewhere, as the shader's
/// line does.
Path? _neck(Rect a, Rect b, double blend) {
  final from = a.right - a.shortestSide / 2;
  final to = b.left + b.shortestSide / 2;
  if (blend <= 0 || to <= from || b.left - a.right >= blend) {
    return null;
  }
  double field(Offset point) =>
      _smoothMin(_pillDistance(a, point), _pillDistance(b, point), blend);
  double axisAt(double x) =>
      lerpDouble(a.center.dy, b.center.dy, (x - from) / (to - from))!;
  final reach = math.max(a.height, b.height) / 2 + blend;
  double edge(double x, double toward) {
    final axis = axisAt(x);
    var inside = 0.0;
    var outside = reach;
    for (var step = 0; step < 16; step++) {
      final middle = (inside + outside) / 2;
      if (field(Offset(x, axis + toward * middle)) < 0) {
        inside = middle;
      } else {
        outside = middle;
      }
    }
    return axis + toward * (inside + outside) / 2;
  }

  double tip(double inside, double outside) {
    for (var step = 0; step < 16; step++) {
      final middle = (inside + outside) / 2;
      if (field(Offset(middle, axisAt(middle))) < 0) {
        inside = middle;
      } else {
        outside = middle;
      }
    }
    return (inside + outside) / 2;
  }

  final path = Path();
  final steps = math.max(2, ((to - from) / _neckStep).ceil());
  var top = <Offset>[];
  var bottom = <Offset>[];
  void close() {
    if (top.isEmpty) {
      return;
    }
    path.addPolygon([...top, ...bottom.reversed], true);
    top = [];
    bottom = [];
  }

  bool flows(Offset point) =>
      (_pillDistance(a, point) - _pillDistance(b, point)).abs() < blend;

  double? previous;
  var wasInside = false;
  for (var index = 0; index <= steps; index++) {
    final x = from + (to - from) * index / steps;
    final inside = field(Offset(x, axisAt(x))) < 0;
    final upper = inside ? Offset(x, edge(x, -1)) : null;
    final lower = inside ? Offset(x, edge(x, 1)) : null;
    if (upper != null && lower != null && (flows(upper) || flows(lower))) {
      if (top.isEmpty && previous != null && !wasInside) {
        final start = tip(x, previous);
        top.add(Offset(start, axisAt(start)));
      }
      top.add(upper);
      bottom.add(lower);
    } else if (top.isNotEmpty) {
      if (!inside) {
        final end = tip(previous!, x);
        top.add(Offset(end, axisAt(end)));
      }
      close();
    }
    previous = x;
    wasInside = inside;
  }
  close();
  return path;
}
