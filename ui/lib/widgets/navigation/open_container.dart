// Third-party attribution and license details are in docs/third-party/.
// SPDX-License-Identifier: GPL-3.0-only

import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/scheduler.dart';
import 'package:ui/theme/theme_context.dart';

import 'deferred_push.dart';
import 'drag_back.dart';
import 'keyboard_inset_hold.dart';
import 'route_activation.dart';

const omniContainerExtraKey = '_omniContainer';

typedef CloseContainerActionCallback<S> = void Function({S? returnValue});
typedef OpenContainerBuilder<S> = Widget Function(
  BuildContext context,
  CloseContainerActionCallback<S> action,
);
typedef CloseContainerBuilder = Widget Function(
  BuildContext context,
  VoidCallback action,
);

/// A list-row origin carried through GoRouter's existing route admission.
/// It owns only transition geometry; Navigator owns route identity and results.
class OmniContainerSource {
  OmniContainerSource._(
    this._hideableKey,
    this._closedBuilderKey, {
    required this.closedColor,
    required this.closedShape,
    required this.closedBuilder,
  });

  final Color? closedColor;
  final ShapeBorder? closedShape;
  final CloseContainerBuilder closedBuilder;
  final GlobalKey<_HideableState> _hideableKey;
  final GlobalKey _closedBuilderKey;

  Page<T> createPage<T>({
    required Widget child,
    LocalKey? key,
    String? name,
    Object? arguments,
  }) => _OmniContainerPage<T>(
    source: this,
    child: child,
    key: key,
    name: name,
    arguments: arguments,
  );
}

class _OmniContainerPage<T> extends Page<T> {
  const _OmniContainerPage({
    required this.source,
    required this.child,
    super.key,
    super.name,
    super.arguments,
  });

  final OmniContainerSource source;
  final Widget child;

  @override
  Route<T> createRoute(BuildContext context) => _OpenContainerRoute<T>(
    settings: this,
    reduceMotion: MediaQuery.maybeDisableAnimationsOf(context) ?? false,
    closedColor: source.closedColor,
    closedShape: source.closedShape,
    closedBuilder: source.closedBuilder,
    openBuilder: (_, _) => child,
    hideableKey: source._hideableKey,
    closedBuilderKey: source._closedBuilderKey,
  );
}

/// Expands a source row into a page while the application owns navigation.
class OmniOpenContainer extends StatefulWidget {
  const OmniOpenContainer({
    super.key,
    this.closedColor,
    this.closedShape,
    this.onClosed,
    required this.closedBuilder,
    required this.onOpen,
    this.clipBehavior = Clip.none,
  });

  final Color? closedColor;
  final ShapeBorder? closedShape;
  final ValueChanged<Object?>? onClosed;
  final CloseContainerBuilder closedBuilder;
  final Future<Object?> Function(OmniContainerSource source) onOpen;
  final Clip clipBehavior;

  @override
  State<OmniOpenContainer> createState() => _OmniOpenContainerState();
}

class _OmniOpenContainerState extends State<OmniOpenContainer> {
  final _hideableKey = GlobalKey<_HideableState>();
  final _closedBuilderKey = GlobalKey();
  bool _opening = false;

  Future<void> _openContainer() async {
    if (_opening) return;
    _opening = true;
    try {
      final data = await widget.onOpen(
        OmniContainerSource._(
          _hideableKey,
          _closedBuilderKey,
          closedColor: widget.closedColor,
          closedShape: widget.closedShape,
          closedBuilder: widget.closedBuilder,
        ),
      );
      if (mounted) widget.onClosed?.call(data);
    } finally {
      _opening = false;
    }
  }

  @override
  Widget build(BuildContext context) => _Hideable(
    key: _hideableKey,
    child: Material(
      color: Colors.transparent,
      clipBehavior: widget.clipBehavior,
      shape: widget.closedShape,
      child: Builder(
        key: _closedBuilderKey,
        builder: (context) => widget.closedBuilder(context, _openContainer),
      ),
    ),
  );
}

class _Hideable extends StatefulWidget {
  const _Hideable({super.key, required this.child});

  final Widget child;

  @override
  State<_Hideable> createState() => _HideableState();
}

class _HideableState extends State<_Hideable> {
  Size? get placeholderSize => _placeholderSize;
  Size? _placeholderSize;

  set placeholderSize(Size? value) {
    if (_placeholderSize == value) {
      return;
    }
    setState(() {
      _placeholderSize = value;
    });
  }

  bool get isVisible => _visible;
  bool _visible = true;

  set isVisible(bool value) {
    if (_visible == value) {
      return;
    }
    setState(() {
      _visible = value;
    });
  }

  bool get isInTree => _placeholderSize == null;

  @override
  Widget build(BuildContext context) {
    if (_placeholderSize != null) {
      return SizedBox.fromSize(size: _placeholderSize);
    }
    return Visibility(
      visible: _visible,
      maintainSize: true,
      maintainState: true,
      maintainAnimation: true,
      child: widget.child,
    );
  }
}

class _OpenContainerRoute<T> extends PageRoute<T>
    with
        DragBackRouteMixin<T>,
        DeferredPushRouteMixin<T>,
        RouteLoadActivityMixin<T> {
  _OpenContainerRoute({
    required this.closedColor,
    required ShapeBorder? closedShape,
    required this.closedBuilder,
    required this.openBuilder,
    required this.hideableKey,
    required this.closedBuilderKey,
    required super.settings,
    required this.reduceMotion,
  }) : _shapeTween = ShapeBorderTween(begin: closedShape);

  final Color? closedColor;
  final CloseContainerBuilder closedBuilder;
  final OpenContainerBuilder<T> openBuilder;
  final GlobalKey<_HideableState> hideableKey;
  final GlobalKey closedBuilderKey;
  final bool reduceMotion;
  bool _isDisposed = false;

  @override
  Duration get transitionDuration =>
      reduceMotion ? Duration.zero : const Duration(milliseconds: 300);

  @override
  Duration get reverseTransitionDuration => transitionDuration;

  static const _curve = Curves.fastOutSlowIn;
  static final _reverseCurve = _curve.flipped;

  final ShapeBorderTween _shapeTween;

  // The closed child stays in this route from the push until the route is
  // dismissed or dragged back, so it moves between its tile and the route
  // twice per cycle rather than at every status change.
  final _closedRect = ValueNotifier<Rect?>(null);
  final _hostsClosed = ValueNotifier<bool>(false);
  late final Listenable _closedChanges = Listenable.merge([
    _closedRect,
    _hostsClosed,
  ]);
  late final _openOpacity = _OpenOpacity(this);

  AnimationStatus? _lastAnimationStatus;
  AnimationStatus? _currentAnimationStatus;

  @override
  bool get reduceDragBackMotion => reduceMotion;

  @override
  bool canTransitionTo(TransitionRoute<dynamic> nextRoute) => false;

  @override
  bool canTransitionFrom(TransitionRoute<dynamic> previousRoute) => false;

  bool get _settled => animation!.isCompleted || isDragBackActive;

  bool get _closing =>
      animation!.status == AnimationStatus.reverse &&
      !_transitionWasInterrupted;

  double get _progress {
    final t = animation!.value;
    if (t == 0 || t == 1) {
      return t;
    }
    return (_closing ? _reverseCurve : _curve).transform(t);
  }

  /// How far the open page and the container color have faded in. Both
  /// change over a fifth of the transition: the second fifth on the way in,
  /// the fourth on the way out.
  double get _fade {
    if (_settled) {
      return 1;
    }
    final start = _closing ? 0.6 : 0.2;
    return clampDouble((animation!.value - start) / 0.2, 0, 1);
  }

  @override
  TickerFuture didPush() {
    // With reduced motion the origin stays in place, so there is no global-key
    // transfer or deferred measurement for a zero-duration route to unwind.
    if (!reduceMotion && hideableKey.currentContext != null) {
      _takeMeasurements();
    }

    animation!.addStatusListener((AnimationStatus status) {
      _lastAnimationStatus = _currentAnimationStatus;
      _currentAnimationStatus = status;
      switch (status) {
        case AnimationStatus.dismissed:
          _returnClosedChild(visible: true);
          break;
        case AnimationStatus.completed:
          if (!reduceMotion && !_hostsClosed.value) {
            hideableKey.currentState?.isVisible = false;
          }
          break;
        case AnimationStatus.forward:
        case AnimationStatus.reverse:
          break;
      }
    });

    return super.didPush();
  }

  @override
  bool didPop(T? result) {
    if (reduceMotion || isDragBackActive) {
      return super.didPop(result);
    }
    _takeMeasurements(delayForSourceRoute: true);
    return super.didPop(result);
  }

  @override
  void dispose() {
    _isDisposed = true;
    final hideable = hideableKey.currentState;
    if (hideable != null && (!hideable.isInTree || !hideable.isVisible)) {
      SchedulerBinding.instance.addPostFrameCallback(
        (Duration d) => hideableKey.currentState
          ?..placeholderSize = null
          ..isVisible = true,
      );
    }
    super.dispose();
    _closedRect.dispose();
    _hostsClosed.dispose();
  }

  void _returnClosedChild({required bool visible}) {
    final hideable = hideableKey.currentState;
    if (hideable == null) {
      return;
    }
    hideable
      ..placeholderSize = null
      ..isVisible = visible;
    _hostsClosed.value = false;
  }

  void _takeMeasurements({bool delayForSourceRoute = false}) {
    // GoRouter may place the destination on an ancestor navigator. Both push
    // and pop must measure in this route owner, even for a nested source row.
    final navigatorBox = navigator?.context.findRenderObject();
    if (navigatorBox is! RenderBox) return;

    void takeMeasurementsInSourceRoute([Duration? _]) {
      if (_isDisposed || !navigatorBox.attached) {
        return;
      }
      final rect = _getRect(hideableKey, navigatorBox);
      if (rect == null) return;
      _closedRect.value = rect;
      hideableKey.currentState!.placeholderSize = rect.size;
      _hostsClosed.value = true;
    }

    if (delayForSourceRoute) {
      SchedulerBinding.instance.addPostFrameCallback(
        takeMeasurementsInSourceRoute,
      );
    } else {
      takeMeasurementsInSourceRoute();
    }
  }

  Rect? _getRect(GlobalKey key, RenderBox ancestor) {
    final render = key.currentContext?.findRenderObject();
    if (!ancestor.hasSize ||
        render is! RenderBox ||
        !render.attached ||
        !render.hasSize) {
      return null;
    }
    return MatrixUtils.transformRect(
      render.getTransformTo(ancestor),
      Offset.zero & render.size,
    );
  }

  bool get _transitionWasInterrupted =>
      (_lastAnimationStatus?.isAnimating ?? false) &&
      (_currentAnimationStatus?.isAnimating ?? false);

  void closeContainer({T? returnValue}) {
    Navigator.of(subtreeContext!).pop(returnValue);
  }

  @override
  Widget buildPage(
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
  ) {
    final theme = Theme.of(context);
    final surface = context.omniPalette.pageBackground;
    final open = FadeTransition(
      opacity: _openOpacity,
      child: RepaintBoundary(
        child: Material(
          type: MaterialType.transparency,
          child: Builder(
            builder: (BuildContext context) {
              return KeyboardInsetHold(
                child: openBuilder(context, closeContainer),
              );
            },
          ),
        ),
      ),
    );
    return ListenableBuilder(
      listenable: _closedChanges,
      builder: (BuildContext context, Widget? open) {
        return _ContainerTransition(
          route: this,
          closedColor: closedColor ?? surface,
          openColor: surface,
          settledColor: theme.canvasColor,
          closed: _hostsClosed.value
              ? IgnorePointer(
                  child: ExcludeFocus(
                    child: ExcludeSemantics(
                      child: Material(
                        type: MaterialType.transparency,
                        child: Builder(
                          key: closedBuilderKey,
                          builder: (BuildContext context) {
                            // Use dummy "open container" callback
                            // since we are in the process of opening.
                            return closedBuilder(context, () {});
                          },
                        ),
                      ),
                    ),
                  ),
                )
              : null,
          open: open!,
        );
      },
      child: open,
    );
  }

  @override
  void didStartDragBack() => _returnClosedChild(visible: true);

  @override
  Widget buildTransitions(
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) {
    return dragBackDetector(dragBackSlide(context, animation, child));
  }

  @override
  bool get maintainState => true;

  @override
  Color? get barrierColor => null;

  @override
  bool get opaque => true;

  @override
  bool get barrierDismissible => false;

  @override
  String? get barrierLabel => null;
}

class _OpenOpacity extends Animation<double>
    with AnimationWithParentMixin<double> {
  _OpenOpacity(this.route);

  final _OpenContainerRoute<dynamic> route;

  @override
  Animation<double> get parent => route.animation!;

  @override
  double get value => route._fade;
}

enum _Slot { closed, open }

/// Grows the container from the closed child's rect to the whole route by
/// painting alone: the open page is laid out once at its final size and
/// moved under a clip, and the closed child is scaled to the rect's width.
class _ContainerTransition
    extends SlottedMultiChildRenderObjectWidget<_Slot, RenderBox> {
  const _ContainerTransition({
    required this.route,
    required this.closedColor,
    required this.openColor,
    required this.settledColor,
    required this.closed,
    required this.open,
  });

  final _OpenContainerRoute<dynamic> route;
  final Color closedColor;
  final Color openColor;
  final Color settledColor;
  final Widget? closed;
  final Widget open;

  @override
  Iterable<_Slot> get slots => _Slot.values;

  @override
  Widget? childForSlot(_Slot slot) => switch (slot) {
    _Slot.closed => closed,
    _Slot.open => open,
  };

  @override
  _RenderContainerTransition createRenderObject(BuildContext context) {
    return _RenderContainerTransition(
      route,
      closedColor,
      openColor,
      settledColor,
    );
  }

  @override
  void updateRenderObject(
    BuildContext context,
    _RenderContainerTransition renderObject,
  ) {
    renderObject
      ..route = route
      ..closedColor = closedColor
      ..openColor = openColor
      ..settledColor = settledColor
      ..markNeedsLayout();
  }
}

class _RenderContainerTransition extends RenderBox
    with SlottedContainerRenderObjectMixin<_Slot, RenderBox> {
  _RenderContainerTransition(
    this._route,
    this._closedColor,
    this._openColor,
    this._settledColor,
  );

  _OpenContainerRoute<dynamic> get route => _route;
  _OpenContainerRoute<dynamic> _route;
  set route(_OpenContainerRoute<dynamic> value) {
    if (identical(value, _route)) {
      return;
    }
    if (attached) {
      _unlisten();
    }
    _route = value;
    if (attached) {
      _listen();
    }
    markNeedsPaint();
  }

  Color _closedColor;
  set closedColor(Color value) {
    if (value == _closedColor) {
      return;
    }
    _closedColor = value;
    markNeedsPaint();
  }

  Color _openColor;
  set openColor(Color value) {
    if (value == _openColor) {
      return;
    }
    _openColor = value;
    markNeedsPaint();
  }

  Color _settledColor;
  set settledColor(Color value) {
    if (value == _settledColor) {
      return;
    }
    _settledColor = value;
    markNeedsPaint();
  }

  RenderBox? get _closed => childForSlot(_Slot.closed);
  RenderBox? get _open => childForSlot(_Slot.open);

  final _clipLayer = LayerHandle<ContainerLayer>();
  final _closedTransformLayer = LayerHandle<TransformLayer>();

  void _handleStatus(AnimationStatus _) => markNeedsPaint();

  void _listen() {
    _route.animation!
      ..addListener(markNeedsPaint)
      ..addStatusListener(_handleStatus);
  }

  void _unlisten() {
    _route.animation!
      ..removeListener(markNeedsPaint)
      ..removeStatusListener(_handleStatus);
  }

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    _listen();
  }

  @override
  void detach() {
    _unlisten();
    super.detach();
  }

  @override
  void dispose() {
    _clipLayer.layer = null;
    _closedTransformLayer.layer = null;
    super.dispose();
  }

  Rect? get _closedRect => _route._closedRect.value;

  /// The container's rect, or null once the route has settled.
  Rect? get _rect {
    if (_route._settled) {
      return null;
    }
    final full = Offset.zero & size;
    return Rect.lerp(_closedRect ?? full, full, _route._progress)!;
  }

  Matrix4? _closedTransform(Rect rect) {
    final closedRect = _closedRect;
    if (closedRect == null || closedRect.width == 0) {
      return null;
    }
    final scale = rect.width / closedRect.width;
    return Matrix4.translationValues(rect.left, rect.top, 0)
      ..scaleByDouble(scale, scale, 1, 1);
  }

  @override
  Size computeDryLayout(covariant BoxConstraints constraints) {
    return constraints.biggest;
  }

  @override
  void performLayout() {
    size = constraints.biggest;
    _closed?.layout(BoxConstraints.tight(_closedRect?.size ?? Size.zero));
    _open?.layout(BoxConstraints.tight(size));
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    final rect = _rect;
    if (rect == null) {
      _clipLayer.layer = null;
      _closedTransformLayer.layer = null;
      context.canvas.drawRect(offset & size, Paint()..color = _settledColor);
      context.paintChild(_open!, offset);
      return;
    }
    final path = _route._shapeTween.lerp(_route._progress)?.getOuterPath(rect);
    final color = Paint()
      ..color = Color.lerp(_closedColor, _openColor, _route._fade)!;
    if (path == null) {
      context.canvas.drawRect(rect.shift(offset), color);
    } else {
      context.canvas.drawPath(path.shift(offset), color);
    }
    void paintContents(PaintingContext context, Offset offset) {
      final closed = _closed;
      final transform = _closedTransform(rect);
      if (closed != null && transform != null) {
        _closedTransformLayer.layer = context.pushTransform(
          closed.needsCompositing,
          offset,
          transform,
          (context, offset) => context.paintChild(closed, offset),
          oldLayer: _closedTransformLayer.layer,
        );
      } else {
        _closedTransformLayer.layer = null;
      }
      context.paintChild(_open!, offset + rect.topLeft);
    }

    final oldLayer = _clipLayer.layer;
    _clipLayer.layer = path == null
        ? context.pushClipRect(
            needsCompositing,
            offset,
            rect,
            paintContents,
            clipBehavior: Clip.antiAlias,
            oldLayer: oldLayer is ClipRectLayer ? oldLayer : null,
          )
        : context.pushClipPath(
            needsCompositing,
            offset,
            rect,
            path,
            paintContents,
            clipBehavior: Clip.antiAlias,
            oldLayer: oldLayer is ClipPathLayer ? oldLayer : null,
          );
  }

  @override
  void applyPaintTransform(RenderBox child, Matrix4 transform) {
    final rect = _rect;
    if (rect == null) {
      return;
    }
    if (identical(child, _closed)) {
      final closedTransform = _closedTransform(rect);
      if (closedTransform != null) {
        transform.multiply(closedTransform);
      }
      return;
    }
    transform.translateByDouble(rect.left, rect.top, 0, 1);
  }

  @override
  bool hitTestSelf(Offset position) => true;

  @override
  bool hitTest(BoxHitTestResult result, {required Offset position}) {
    final rect = _rect;
    if (rect != null) {
      final shape = _route._shapeTween.lerp(_route._progress);
      final inside = shape == null
          ? rect.contains(position)
          : shape.getOuterPath(rect).contains(position);
      if (!inside) {
        return false;
      }
    }
    return super.hitTest(result, position: position);
  }

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) {
    if (_route._fade == 0) {
      return false;
    }
    final origin = _rect?.topLeft ?? Offset.zero;
    return result.addWithPaintOffset(
      offset: origin,
      position: position,
      hitTest: (BoxHitTestResult result, Offset transformed) {
        return _open!.hitTest(result, position: transformed);
      },
    );
  }
}
