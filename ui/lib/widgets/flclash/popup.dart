// Adapted from FlClash lib/ui/popup.dart, goo.dart, motion.dart and the solid
// menuShadows helper in liquid_glass.dart at
// 7fd417fb9a9a277c6a9339a8ac87f907df7c25f7.
// Keeps the upstream source snapshot, pointer-down activation, 450/500 ms
// spring clocks and platter/content morph, including the shader glass branch.
// Nested menus are caller-owned; all popup owners share one surface renderer.
// See docs/third-party/flclash-NOTICE.md.
// SPDX-License-Identifier: GPL-3.0-only
// ignore_for_file: prefer_initializing_formals

import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' as ui show Image, PictureRecorder;
import 'dart:ui' show ImageFilter, SemanticsRole, lerpDouble;

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';
import 'package:flutter/rendering.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/omni_menu.dart';

import 'liquid_glass.dart';
import 'elastic_press.dart';
import 'menu_interaction.dart';

export 'menu_interaction.dart'
    show
        FlClashMenuController,
        FlClashMenuInteraction,
        FlClashMenuItemRegion,
        FlClashMenuInteractionIgnore,
        FlClashMenuPanelRegion;

part 'popup_geometry.dart';

typedef _PopupAnchorResolver = Rect? Function();

/// Opens over the chosen edge, preserving FlClash's 8 dp anchor overlap.
enum FlClashPopupPlacement { overAnchorEnd, overAnchorStart, belowPoint }

const _screenMargin = 16.0;
const _anchorOverlap = 8.0;
const _cardRadius = 24.0;

final _dropletSpring = SpringDescription.withDampingRatio(
  mass: 1,
  stiffness: 165,
  ratio: 0.8,
);

double _riseAndFall(double time, double peak, double sharpness) {
  if (time <= 0) return 0;
  final x = time / peak;
  return math.pow(x, sharpness) * math.exp(sharpness * (1 - x));
}

List<BoxShadow> _menuShadows(ColorScheme scheme) => [
  BoxShadow(
    color: scheme.shadow.withValues(
      alpha: 0.118 * (scheme.brightness == Brightness.dark ? 1.5 : 1.0),
    ),
    blurRadius: 29,
    offset: const Offset(0, 8),
  ),
];

RoundedSuperellipseBorder _shape(double radius) =>
    RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(radius));

// LiquidGlassPane clamps its corner radii before building the visible pane.
// Use that same contour for a glass source; a button's outer hit/ink shape can
// contain a much larger radius and is not necessarily its painted glass path.
ShapeBorder _visibleSourceShape(ShapeBorder shape, Rect rect, bool glass) {
  if (!glass || shape is! RoundedSuperellipseBorder) return shape;
  final corners = shape.borderRadius.resolve(TextDirection.ltr);
  final limit = Radius.circular(rect.shortestSide / 2);
  return RoundedSuperellipseBorder(
    borderRadius: BorderRadius.only(
      topLeft: corners.topLeft.clamp(maximum: limit),
      topRight: corners.topRight.clamp(maximum: limit),
      bottomLeft: corners.bottomLeft.clamp(maximum: limit),
      bottomRight: corners.bottomRight.clamp(maximum: limit),
    ),
  );
}

const _maxContentBlur = 8.0;
const _overshootPerLongestSide = 0.1;

const _openDuration = Duration(milliseconds: 450);

const _dismissDuration = Duration(milliseconds: 500);

/// How far a popup with no control to return into shrinks toward its anchor
/// as it fades, as an iOS context menu does.
const _dismissScale = 0.8;

double _secondsOf(Duration duration) =>
    duration.inMicroseconds / Duration.microsecondsPerSecond;

final _openSeconds = _secondsOf(_openDuration);

final _dismissSeconds = _secondsOf(_dismissDuration);

// iOS 27 returns a menu into its button as one drop of glass: the platter
// dips away, then shrinks into the button, which re-forms out of a droplet
// and rises past its place. iOS lays a menu over its button; one here opens
// beside it, so it dips half as far, or the two would part.
final _droplet = SpringSimulation(_dropletSpring, 0, 1, 0);
const _dropletBlend = 28.0;
const _dropletContentOut = 0.1;
const _dropletGlyphIn = 0.47;
const _dropletGlyphCurve = Cubic(0.25, 0.1, 0.25, 1);

// Inside the button by then, the platter stops swelling it and the glass eases
// back to the button's, so the last frame is the button itself.
const _dropletAbsorbed = Interval(0.6, 0.9, curve: Curves.easeInOut);

// As measured on iOS 27 (.agents/architecture.md, Popup Motion): one spring
// opens the menu, a later one squares off its capsule corners.
final _open = SpringSimulation(
  SpringDescription.withDampingRatio(mass: 1, stiffness: 400, ratio: 0.754),
  0,
  1,
  0,
);
final _corners = SpringSimulation(
  SpringDescription.withDampingRatio(mass: 1, stiffness: 324, ratio: 0.75),
  1,
  0,
  0,
);
const _cornersDelay = 0.07;
const _cornersReachPerWidth = 0.48;
const _openContentBlur = 4.0;
const _openBlurBump = 1.9;
const _unseenBlur = 0.1;
const _platterInControl = 0.41;
const _dropletShare = 0.25;
const _rimDepth = 8.0;
const _glyphUnderAt = 0.25;

/// Shared clocks for the route and focus-preserving overlay menu owners.
abstract final class FlClashPopupMotion {
  static const openingDuration = _openDuration;
  static const closingDuration = _dismissDuration;
  static const shrinkingDuration = Duration(milliseconds: 200);
  static Duration closingDurationFor({required bool hasSource}) =>
      hasSource ? closingDuration : shrinkingDuration;

  static Future<void> close(
    AnimationController controller, {
    Duration duration = closingDuration,
  }) async {
    // AnimationController.reverse normally shortens an interrupted return.
    // Upstream returns from the captured visible frame over the full 500 ms.
    controller.reverseDuration =
        duration * (1 / math.max(controller.value, 1e-3));
    await controller.reverse().orCancel;
  }
}

/// Captures the complete control, even when only one segment opens its menu.
/// Keep background and all glyphs inside [builder]. A descendant popup box or
/// the shared show* APIs consume this source via their sourceContext argument.
class FlClashPopupSource extends StatefulWidget {
  const FlClashPopupSource({
    super.key,
    required this.builder,
    this.color,
    this.shape,
    this.glass,
    this.captureInsets = EdgeInsets.zero,
    this.captureRect,
    this.captureOverflow,
    this.preserveOutsideCapture = true,
  });

  final WidgetBuilder builder;
  final Color? color;
  final ShapeBorder? shape;
  final bool? glass;
  final EdgeInsets captureInsets;

  /// Keep helper/counter content outside a field's capture visible. Controls
  /// with visual padding or shadows can instead hide their complete subtree.
  final bool preserveOutsideCapture;

  /// Visible source bounds in this wrapper's local coordinates. This can omit
  /// an InputDecorator's helper/counter without changing its hit or focus owner.
  final Rect? Function(BuildContext sourceContext)? captureRect;

  /// Painted adornments that extend outside [captureRect], such as a floating
  /// input label. They travel with the snapshot without enlarging its material
  /// contour or anchor. Rectangles use this wrapper's local coordinates.
  final List<Rect> Function(BuildContext sourceContext)? captureOverflow;

  static FlClashPopupSourceLease? captureOf(BuildContext? context) {
    if (context == null || !context.mounted) return null;
    return context
        .findAncestorStateOfType<_FlClashPopupSourceState>()
        ?._capture();
  }

  @override
  State<FlClashPopupSource> createState() => _FlClashPopupSourceState();
}

/// A presentation lease. The menu owner releases it only after reverse/remove;
/// it never owns route identity, focus, selection or the action's lifecycle.
class FlClashPopupSourceLease {
  FlClashPopupSourceLease._(
    this._source,
    this.color,
    this.shape,
    this.glass,
    this.captureRect,
    this.captureOverflow,
    this._lastGlobalRect,
  );

  _FlClashPopupSourceState? _source;
  final Color color;
  final ShapeBorder shape;
  final bool glass;
  final Rect captureRect;
  final List<Rect> captureOverflow;
  final image = ValueNotifier<ui.Image?>(null);
  Rect? _lastGlobalRect;
  bool _released = false;
  bool _revealed = false;

  Rect? rectIn(RenderBox? overlay) {
    final current = _source?._globalRect();
    if (current != null) _lastGlobalRect = current;
    final rect = _lastGlobalRect;
    if (rect == null) return null;
    return overlay == null
        ? rect
        : rect.shift(-overlay.localToGlobal(Offset.zero));
  }

  void reveal() {
    _revealed = true;
    final source = _source;
    if (source != null && source.mounted && identical(source._lease, this)) {
      source._hidden.value = false;
    }
  }

  void release() {
    if (_released) return;
    _released = true;
    reveal();
    final source = _source;
    if (source != null && source.mounted && identical(source._lease, this)) {
      source._releaseLease(this);
    }
    _source = null;
    image.value?.dispose();
    image.dispose();
  }
}

class _FlClashPopupSourceState extends State<FlClashPopupSource> {
  final _boundary = GlobalKey();
  final _hidden = ValueNotifier(false);
  FlClashPopupSourceLease? _lease;

  Rect? _globalRect() {
    final box = _boundary.currentContext?.findRenderObject();
    if (box is! RenderBox || !box.attached || !box.hasSize) return null;
    return _localCaptureRect(box).shift(box.localToGlobal(Offset.zero));
  }

  Rect _localCaptureRect(RenderBox box) {
    final bounds = Offset.zero & box.size;
    return (widget.captureRect?.call(_boundary.currentContext!) ??
            widget.captureInsets.deflateRect(bounds))
        .intersect(bounds);
  }

  FlClashPopupSourceLease? _capture() {
    if (_lease != null) return null;
    final rect = _globalRect();
    if (rect == null || rect.isEmpty) return null;
    final box = _boundary.currentContext!.findRenderObject()! as RenderBox;
    final glass = widget.glass ?? LiquidGlassScope.of(context);
    final lease = FlClashPopupSourceLease._(
      this,
      widget.color ??
          (glass ? Colors.transparent : context.omniPalette.surfacePrimary),
      _visibleSourceShape(widget.shape ?? _shape(1000), rect, glass),
      glass,
      _localCaptureRect(box),
      List.unmodifiable(
        widget.captureOverflow?.call(_boundary.currentContext!) ??
            const <Rect>[],
      ),
      rect,
    );
    setState(() => _lease = lease);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted ||
          !identical(_lease, lease) ||
          lease._released ||
          lease._revealed) {
        return;
      }
      final boundary = _boundary.currentContext?.findRenderObject();
      if (boundary is! _RenderPopupSourceBoundary || !boundary.hasSize) return;
      lease.image.value = _captureShapedSource(
        boundary,
        lease.captureRect,
        lease.shape,
        MediaQuery.devicePixelRatioOf(context),
        overflow: lease.captureOverflow,
      );
      _hidden.value = true;
    });
    return lease;
  }

  void _releaseLease(FlClashPopupSourceLease lease) {
    if (identical(_lease, lease)) setState(() => _lease = null);
  }

  @override
  void dispose() {
    _lease?._source = null;
    _hidden.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => IgnorePointer(
    ignoring: _lease != null,
    child: ValueListenableBuilder<bool>(
      valueListenable: _hidden,
      builder: (_, hidden, child) {
        final lease = _lease;
        final box = _boundary.currentContext?.findRenderObject();
        final partial =
            widget.preserveOutsideCapture &&
            widget.captureRect != null &&
            lease != null &&
            box is RenderBox &&
            box.hasSize &&
            lease.captureRect != (Offset.zero & box.size);
        return Opacity(
          opacity: hidden && !partial ? 0 : 1,
          child: ClipPath(
            clipBehavior: hidden && partial ? Clip.antiAlias : Clip.none,
            clipper: _SourceExclusionClipper(
              lease?.captureRect ?? Rect.zero,
              lease?.shape ?? const RoundedRectangleBorder(),
              lease?.captureOverflow ?? const [],
            ),
            child: child,
          ),
        );
      },
      child: _PopupSourceBoundary(
        key: _boundary,
        child: Builder(builder: widget.builder),
      ),
    ),
  );
}

class _PopupSourceBoundary extends SingleChildRenderObjectWidget {
  const _PopupSourceBoundary({super.key, required super.child});

  @override
  RenderObject createRenderObject(BuildContext context) =>
      _RenderPopupSourceBoundary();
}

class _RenderPopupSourceBoundary extends RenderRepaintBoundary {
  ui.Image capture(Rect bounds, double pixelRatio) =>
      (layer! as OffsetLayer).toImageSync(bounds, pixelRatio: pixelRatio);
}

Rect _sourceCaptureBounds(Rect rect, List<Rect> overflow) =>
    overflow.fold(rect, (bounds, region) => bounds.expandToInclude(region));

Path _sourceCapturePath(Rect rect, ShapeBorder shape, List<Rect> overflow) {
  var path = shape.getOuterPath(rect);
  for (final region in overflow) {
    path = Path.combine(PathOperation.union, path, Path()..addRect(region));
  }
  return path;
}

ui.Image _captureShapedSource(
  _RenderPopupSourceBoundary boundary,
  Rect rect,
  ShapeBorder shape,
  double pixelRatio, {
  List<Rect> overflow = const [],
}) {
  final bounds = _sourceCaptureBounds(rect, overflow);
  final full = LiquidGlass.withoutPanes(
    // Unlike RenderRepaintBoundary.toImageSync, the layer API can retain a
    // floating label painted above the boundary's own layout bounds.
    () => boundary.capture(bounds, pixelRatio),
  );
  final clippedRecorder = ui.PictureRecorder();
  final clippedCanvas = Canvas(clippedRecorder)..scale(pixelRatio);
  clippedCanvas.clipPath(
    _sourceCapturePath(rect, shape, overflow).shift(-bounds.topLeft),
  );
  clippedCanvas.drawImageRect(
    full,
    Offset.zero & Size(full.width.toDouble(), full.height.toDouble()),
    Offset.zero & bounds.size,
    Paint()..filterQuality = FilterQuality.medium,
  );
  final clippedPicture = clippedRecorder.endRecording();
  try {
    return clippedPicture.toImageSync(
      math.max(1, (bounds.width * pixelRatio).ceil()),
      math.max(1, (bounds.height * pixelRatio).ceil()),
    );
  } finally {
    clippedPicture.dispose();
    full.dispose();
  }
}

class _SourceExclusionClipper extends CustomClipper<Path> {
  const _SourceExclusionClipper(this.rect, this.shape, this.overflow);
  final Rect rect;
  final ShapeBorder shape;
  final List<Rect> overflow;

  @override
  Path getClip(Size size) => Path.combine(
    PathOperation.difference,
    Path()..addRect(_sourceCaptureBounds(Offset.zero & size, overflow)),
    _sourceCapturePath(rect, shape, overflow),
  );

  @override
  bool shouldReclip(_SourceExclusionClipper oldClipper) =>
      rect != oldClipper.rect ||
      shape != oldClipper.shape ||
      !listEquals(overflow, oldClipper.overflow);
}

/// The one FlClash paint-only spring/blur/source-return implementation.
/// [anchor] is in the parent CustomSingleChildLayout's coordinate system.
/// Keeping this directly below that layout preserves the measured offset.
class FlClashPopupTransition extends StatefulWidget {
  const FlClashPopupTransition({
    super.key,
    required this.anchor,
    required this.animation,
    required this.child,
    this.source,
    this.sourceImage,
    this.sourceColor,
    this.sourceShape,
    this.sourceGlass,
    this.sourceOverflow = const [],
    this.menuController,
  });

  final Rect anchor;
  final Animation<double> animation;
  final Widget child;
  final FlClashPopupSourceLease? source;
  final ValueListenable<ui.Image?>? sourceImage;
  final Color? sourceColor;
  final ShapeBorder? sourceShape;
  final bool? sourceGlass;
  final List<Rect> sourceOverflow;
  final FlClashMenuController? menuController;

  @override
  State<FlClashPopupTransition> createState() => _FlClashPopupTransitionState();
}

class _FlClashPopupTransitionState extends State<FlClashPopupTransition> {
  final _platter = ValueNotifier(true);
  final _surface = _PopupSurface();

  @override
  void dispose() {
    _platter.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final glass = LiquidGlassScope.of(context);
    final scheme = Theme.of(context).colorScheme;
    final reducedMotion = MediaQuery.disableAnimationsOf(context);
    final morph = _PopupMorph(
      anchor: widget.anchor,
      animation: reducedMotion
          ? const AlwaysStoppedAnimation(1.0)
          : widget.animation,
      platter: _platter,
      surface: _surface,
      sourceColor:
          widget.source?.color ??
          widget.sourceColor ??
          (glass ? Colors.transparent : context.omniPalette.surfacePrimary),
      sourceImage: widget.source?.image ?? widget.sourceImage,
      sourceShape:
          widget.source?.shape ??
          _visibleSourceShape(
            widget.sourceShape ?? _shape(1000),
            widget.anchor,
            widget.sourceGlass ?? glass,
          ),
      sourceOverflow: widget.source == null
          ? widget.sourceOverflow
          : widget.source!.captureOverflow
                .map((rect) => rect.shift(-widget.source!.captureRect.topLeft))
                .toList(growable: false),
      surfaceColor: context.omniPalette.surfacePrimary,
      shadows: _menuShadows(scheme),
      glass: glass
          ? (
              from: LiquidGlassStyle.regular(scheme).scale(
                (widget.source?.glass ?? widget.sourceGlass ?? glass) ? 1 : 0,
              ),
              to: LiquidGlassStyle.menu(scheme),
              rim: LiquidGlassRim.menu(scheme),
              coarse:
                  LiquidGlassScope.qualityOf(context) ==
                  LiquidGlassQuality.standard,
            )
          : null,
      child: LiquidGlassSurfaceScope(
        enabled: true,
        child: AnimatedBuilder(
          animation: widget.animation,
          child: RepaintBoundary(child: widget.child),
          builder: (_, child) => IgnorePointer(
            ignoring: widget.animation.status == AnimationStatus.reverse,
            child: child,
          ),
        ),
      ),
    );
    return AnimatedBuilder(
      animation: widget.animation,
      child: morph,
      builder: (_, child) => FlClashMenuInteraction(
        controller: widget.menuController,
        enabled:
            reducedMotion ||
            widget.animation.isCompleted ||
            (widget.animation.status == AnimationStatus.reverse &&
                widget.source == null &&
                widget.sourceImage == null),
        child: child!,
      ),
    );
  }
}

typedef _PlatterGlass = ({
  LiquidGlassStyle from,
  LiquidGlassStyle to,
  LiquidGlassRim rim,
  bool coarse,
});

/// Internal route; [FlClashPopupBox] owns its source snapshot and lifecycle.
class _FlClashPopupRoute<T> extends PopupRoute<T> {
  _FlClashPopupRoute({
    required this.builder,
    required this.anchorOf,
    required this.barrierLabel,
    required this.sourceColor,
    required this.sourceImage,
    required this.sourceShape,
    required this.sourceGlass,
    this.sourceOverflow = const [],
    this.placement = FlClashPopupPlacement.overAnchorEnd,
    this.reduceMotion = false,
  });

  final bool reduceMotion;
  final WidgetBuilder builder;
  final _PopupAnchorResolver anchorOf;
  final FlClashPopupPlacement placement;
  final Color sourceColor;
  final ValueListenable<ui.Image?> sourceImage;
  final ShapeBorder sourceShape;
  final bool sourceGlass;
  final List<Rect> sourceOverflow;
  @override
  final String? barrierLabel;
  @override
  Color? get barrierColor => null;
  @override
  bool get barrierDismissible => true;
  @override
  Duration get transitionDuration =>
      reduceMotion ? Duration.zero : _openDuration;
  @override
  Duration get reverseTransitionDuration =>
      reduceMotion ? Duration.zero : _dismissDuration;

  // A reversal must retain its full 500 ms even when opening was interrupted.
  @override
  bool didPop(T? result) {
    controller!.reverseDuration =
        reverseTransitionDuration * (1 / math.max(controller!.value, 1e-3));
    return super.didPop(result);
  }

  final _pin = _PopupPin();
  final _press = FlClashMenuController();

  @override
  Widget buildPage(
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
  ) {
    return FocusScope(
      autofocus: navigator?.widget.requestFocus ?? true,
      child: FocusTraversalGroup(
        policy: WidgetOrderTraversalPolicy(),
        child: Semantics(
          role: SemanticsRole.menu,
          scopesRoute: true,
          namesRoute: true,
          explicitChildNodes: true,
          label: MaterialLocalizations.of(context).popupMenuLabel,
          child: Material(
            type: MaterialType.transparency,
            shape: _shape(_cardRadius),
            clipBehavior: Clip.antiAlias,
            child: FlClashMenuPanelRegion(child: builder(context)),
          ),
        ),
      ),
    );
  }

  @override
  Widget buildTransitions(
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) {
    return Stack(
      children: [
        Positioned.fill(
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            excludeFromSemantics: true,
            onTap: () {
              if (isCurrent) navigator?.pop();
            },
          ),
        ),
        _PopupAnchorTracker(
          anchorOf: anchorOf,
          builder: (anchor, safeInsets, child) => CustomSingleChildLayout(
            delegate: _PopupLayoutDelegate(
              anchor: anchor,
              safeInsets: safeInsets,
              placement: placement,
              pin: _pin,
            ),
            child: FlClashPopupTransition(
              anchor: anchor,
              animation: animation,
              sourceColor: sourceColor,
              sourceImage: sourceImage,
              sourceShape: sourceShape,
              sourceGlass: sourceGlass,
              sourceOverflow: sourceOverflow,
              menuController: _press,
              child: child,
            ),
          ),
          child: child,
        ),
      ],
    );
  }
}

/// Flat entries use the same row tracking, keyboard activation and visuals as
/// menus opened by showOmniMenu or the focus-preserving overlay owner.
class FlClashPopupMenuItem<T> extends OmniPopupMenuItem<T> {
  const FlClashPopupMenuItem({
    super.key,
    super.value,
    super.onTap,
    super.enabled,
    required super.child,
    super.selected,
    super.destructive,
  });
}

/// The part of the popup's box that returns into its control, when not all
/// of it: a menu returns its open sub menu alone, as iOS does.
class _PopupSurface {
  Rect? Function(RenderBox popup)? rectIn;
}

class _PopupMorph extends SingleChildRenderObjectWidget {
  const _PopupMorph({
    required this.anchor,
    required this.animation,
    required this.platter,
    required this.surface,
    required this.sourceColor,
    required this.sourceImage,
    required this.sourceShape,
    required this.sourceOverflow,
    required this.surfaceColor,
    required this.shadows,
    required this.glass,
    super.child,
  });

  final Rect anchor;
  final Animation<double> animation;
  final ValueListenable<bool> platter;
  final _PopupSurface surface;
  final Color sourceColor;
  final ValueListenable<ui.Image?>? sourceImage;
  final ShapeBorder sourceShape;
  final List<Rect> sourceOverflow;
  final Color surfaceColor;
  final List<BoxShadow> shadows;

  /// The platter's glass as it leaves the anchor and as it lands on the card,
  /// or null for a [surfaceColor] fill.
  final _PlatterGlass? glass;

  @override
  _RenderPopupMorph createRenderObject(BuildContext context) {
    return _RenderPopupMorph(
      anchor: anchor,
      animation: animation,
      platter: platter,
      surface: surface,
      sourceColor: sourceColor,
      sourceImage: sourceImage,
      sourceShape: sourceShape,
      sourceOverflow: sourceOverflow,
      surfaceColor: surfaceColor,
      shadows: shadows,
      glass: glass,
    );
  }

  @override
  void updateRenderObject(
    BuildContext context,
    _RenderPopupMorph renderObject,
  ) {
    renderObject
      ..anchor = anchor
      ..animation = animation
      ..platter = platter
      ..surface = surface
      ..sourceColor = sourceColor
      ..sourceImage = sourceImage
      ..sourceShape = sourceShape
      ..sourceOverflow = sourceOverflow
      ..surfaceColor = surfaceColor
      ..shadows = shadows
      ..glass = glass;
  }
}

typedef _MorphFrame = ({double time, bool platter});

typedef _Opening = ({
  Rect platter,
  double radius,
  Rect? droplet,
  double progress,
});

/// Grows the popup out of its anchor the way iOS 27 menus leave their button,
/// and returns it into its control, as one drop of glass with it, the content
/// blurring out first; with no control to return into, the popup shrinks
/// toward its anchor as it fades.
class _RenderPopupMorph extends RenderProxyBox {
  _RenderPopupMorph({
    required Rect anchor,
    required Animation<double> animation,
    required ValueListenable<bool> platter,
    required this.surface,
    required Color sourceColor,
    required ValueListenable<ui.Image?>? sourceImage,
    required ShapeBorder sourceShape,
    required List<Rect> sourceOverflow,
    required Color surfaceColor,
    required List<BoxShadow> shadows,
    required _PlatterGlass? glass,
  }) : _anchor = anchor,
       _animation = animation,
       _platter = platter,
       _sourceColor = sourceColor,
       _sourceImage = sourceImage,
       _sourceShape = sourceShape,
       _sourceOverflow = sourceOverflow,
       _surfaceColor = surfaceColor,
       _shadows = shadows,
       _glass = glass;

  late final _pane = LiquidGlassPane(markNeedsPaint);

  _PopupSurface surface;

  Rect _anchor;

  set anchor(Rect value) {
    if (value == _anchor) {
      return;
    }
    _anchor = value;
    markNeedsPaint();
  }

  Animation<double> _animation;

  set animation(Animation<double> value) {
    if (value == _animation) {
      return;
    }
    if (attached) {
      _animation
        ..removeListener(markNeedsPaint)
        ..removeStatusListener(_handleStatusChanged);
      value
        ..addListener(markNeedsPaint)
        ..addStatusListener(_handleStatusChanged);
    }
    _animation = value;
    markNeedsPaint();
  }

  bool _closing = false;
  _MorphFrame? _closingFrame;
  double _closingFrom = 1;
  Rect? _closingSurface;

  void _handleStatusChanged(AnimationStatus status) {
    if (status == AnimationStatus.forward ||
        status == AnimationStatus.completed) {
      _closing = false;
    }
    if (status != AnimationStatus.reverse) return;
    _closing = true;
    _closingFrom = _animation.value;
    _closingFrame = _frame();
    _closingSurface = hasSize
        ? surface.rectIn?.call(this) ?? FlClashElasticPress.paintBoundsOf(this)
        : null;
  }

  /// Where the opening stands, or null once it has settled.
  _MorphFrame? _frame() {
    if (_animation.value >= 1) {
      return null;
    }
    return (time: _animation.value * _openSeconds, platter: _platter.value);
  }

  ValueListenable<bool> _platter;

  set platter(ValueListenable<bool> value) {
    if (value == _platter) {
      return;
    }
    if (attached) {
      _platter.removeListener(markNeedsPaint);
      value.addListener(markNeedsPaint);
    }
    _platter = value;
    markNeedsPaint();
  }

  Color _sourceColor;

  set sourceColor(Color value) {
    if (value == _sourceColor) {
      return;
    }
    _sourceColor = value;
    markNeedsPaint();
  }

  ValueListenable<ui.Image?>? _sourceImage;

  set sourceImage(ValueListenable<ui.Image?>? value) {
    if (value == _sourceImage) {
      return;
    }
    if (attached) {
      _sourceImage?.removeListener(markNeedsPaint);
      value?.addListener(markNeedsPaint);
    }
    _sourceImage = value;
    markNeedsPaint();
  }

  ShapeBorder _sourceShape;

  set sourceShape(ShapeBorder value) {
    if (value == _sourceShape) return;
    _sourceShape = value;
    markNeedsPaint();
  }

  Path _sourceOutline(Rect rect) => _sourceShape
      .scale(rect.shortestSide / math.max(_localAnchor.shortestSide, 1e-3))
      .getOuterPath(rect);

  List<Rect> _sourceOverflow;

  set sourceOverflow(List<Rect> value) {
    if (listEquals(value, _sourceOverflow)) return;
    _sourceOverflow = value;
    markNeedsPaint();
  }

  List<Rect> _imageOverflow(Rect rect) {
    final scaleX = rect.width / math.max(_localAnchor.width, 1e-3);
    final scaleY = rect.height / math.max(_localAnchor.height, 1e-3);
    return _sourceOverflow
        .map(
          (region) => Rect.fromLTRB(
            rect.left + region.left * scaleX,
            rect.top + region.top * scaleY,
            rect.left + region.right * scaleX,
            rect.top + region.bottom * scaleY,
          ),
        )
        .toList(growable: false);
  }

  double _sourceRadius(Rect rect) {
    final radius = switch (_sourceShape) {
      RoundedRectangleBorder(:final borderRadius) ||
      RoundedSuperellipseBorder(
        :final borderRadius,
      ) => borderRadius.resolve(TextDirection.ltr).topLeft.x,
      OutlineInputBorder(:final borderRadius) => borderRadius.topLeft.x,
      _ => _localAnchor.shortestSide / 2,
    };
    return math.min(
      rect.shortestSide / 2,
      radius * rect.shortestSide / math.max(_localAnchor.shortestSide, 1e-3),
    );
  }

  // The snapshot already contains its original fill. Compensate its underlay
  // with source-over algebra, so a translucent field does not become opaque.
  Color _underSnapshot(Color fill, double opacity) {
    final imageAlpha = fill.a * opacity;
    final alpha = imageAlpha >= 1
        ? 0.0
        : fill.a * (1 - opacity) / (1 - imageAlpha);
    return fill.withValues(alpha: alpha.clamp(0, 1));
  }

  Color _surfaceColor;

  set surfaceColor(Color value) {
    if (value == _surfaceColor) {
      return;
    }
    _surfaceColor = value;
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

  _PlatterGlass? _glass;

  set glass(_PlatterGlass? value) {
    if (value == _glass) {
      return;
    }
    _glass = value;
    markNeedsPaint();
  }

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    _animation
      ..addListener(markNeedsPaint)
      ..addStatusListener(_handleStatusChanged);
    _platter.addListener(markNeedsPaint);
    _sourceImage?.addListener(markNeedsPaint);
  }

  @override
  void detach() {
    _animation
      ..removeListener(markNeedsPaint)
      ..removeStatusListener(_handleStatusChanged);
    _platter.removeListener(markNeedsPaint);
    _sourceImage?.removeListener(markNeedsPaint);
    super.detach();
  }

  @override
  void dispose() {
    _pane.dispose();
    super.dispose();
  }

  @override
  bool get alwaysNeedsCompositing => child != null;

  /// The anchor in this box's coordinates; it comes in those of the layout
  /// box that places this one.
  Rect get _localAnchor =>
      _anchor.shift(-FlClashMenuInteraction.layoutOffsetOf(this));

  @override
  void paint(PaintingContext context, Offset offset) {
    if (child == null) {
      return;
    }
    // Owners reveal the real source on dismissed, before this frame paints.
    // Do not restart the t=0 opening platter or paint a duplicate source pane.
    if (_closing && _animation.status == AnimationStatus.dismissed) {
      _pane.clear();
      return;
    }
    if (_animation.status != AnimationStatus.reverse) {
      _paintFrame(context, offset, _frame());
      return;
    }
    final frame = _closingFrame;
    final from = _closingFrom;
    final dismissal = from <= 0
        ? 1.0
        : (1 - _animation.value / from).clamp(0.0, 1.0);
    if (_sourceImage != null) {
      _paintReturn(context, offset, frame, dismissal * _dismissSeconds);
      return;
    }
    final shrink = Curves.easeOut.transform(dismissal);
    final scale = lerpDouble(1, _dismissScale, shrink)!;
    final anchor = _localAnchor.center;
    final origin = Offset(
      anchor.dx.clamp(0.0, size.width),
      anchor.dy.clamp(0.0, size.height),
    );
    final transform = Matrix4.translationValues(origin.dx, origin.dy, 0)
      ..scaleByDouble(scale, scale, 1, 1)
      ..translateByDouble(-origin.dx, -origin.dy, 0, 1);
    context.pushTransform(
      needsCompositing,
      offset,
      transform,
      (context, offset) => LiquidGlassFade.push(
        context,
        offset,
        Color.getAlphaFromOpacity(1 - shrink),
        (context, offset) => _paintFrame(context, offset, frame),
      ),
    );
  }

  _Opening _openingOf(double time, Rect card) {
    final anchor = _localAnchor;
    final progress = _open.x(time);
    final scale = lerpDouble(
      _platterInControl * anchor.shortestSide / card.width,
      1,
      progress,
    )!;
    final height = lerpDouble(card.width, card.height, progress)!;
    final toCard = card.center.dy - anchor.center.dy;
    final distance = toCard.abs();
    final dip = _sourceImage == null
        ? 0.0
        : toCard.sign *
              (math.min(1.0, distance / 30) * 23 + 0.124 * distance) *
              _riseAndFall(time, 0.098, 3.3);
    final platter = Rect.fromCenter(
      center:
          Offset.lerp(anchor.center, card.center, progress)! + Offset(0, dip),
      width: math.max(0.0, card.width * scale),
      height: math.max(0.0, height * scale),
    );
    final corners =
        _cardRadius +
        card.width *
            _cornersReachPerWidth *
            _corners.x(math.max(0.0, time - _cornersDelay));
    Rect? droplet;
    if (_sourceImage != null) {
      final extent = anchor.size * lerpDouble(1, _dropletShare, progress)!;
      final inner = card.deflate(
        anchor.longestSide * _dropletShare / 2 + _dropletBlend + _rimDepth,
      );
      final sunk = Offset.lerp(anchor.center, card.center, _dropletShare)!;
      final target = inner.isEmpty
          ? card.center
          : Offset(
              sunk.dx.clamp(inner.left, inner.right),
              sunk.dy.clamp(inner.top, inner.bottom),
            );
      droplet = Rect.fromCenter(
        center: Offset.lerp(anchor.center, target, progress)!,
        width: extent.width,
        height: extent.height,
      );
    }
    return (
      platter: platter,
      radius: math.max(0.0, math.min(height / 2, corners) * scale),
      droplet: droplet,
      progress: progress,
    );
  }

  void _paintFrame(PaintingContext context, Offset offset, _MorphFrame? frame) {
    final child = this.child!;
    if (frame == null || size.isEmpty) {
      final glass = _glass;
      if (glass == null || size.isEmpty) {
        _pane.clear();
        if (!size.isEmpty) {
          final rect = offset & size;
          final path = _shape(_cardRadius).getOuterPath(rect);
          _paintShadows(context.canvas, path, rect, _cardRadius, 1);
          context.canvas.drawPath(path, Paint()..color = _surfaceColor);
        }
      } else {
        // A shared popup owner keeps one pane after the morph settles, so its
        // generic menu contents never add a second capture or an opaque fill.
        final rect = offset & size;
        _pane.paint(
          context,
          rect,
          shape: LiquidGlassCorners.circular(_cardRadius),
          style: glass.to,
          shadows: _shadows,
          rim: glass.rim,
        );
        glass.rim.paintLine(context.canvas, rect, _cardRadius);
      }
      context.paintChild(child, offset);
      return;
    }
    final anchor = _localAnchor;
    final bounds = Offset.zero & size;
    final (:platter, :radius, :droplet, :progress) = _openingOf(
      frame.time,
      bounds,
    );
    final sourceImage = _sourceImage;
    final image = sourceImage?.value;
    // The source control stays visible until its snapshot exists, so the
    // platter would only cover it.
    if (sourceImage != null && image == null) {
      return;
    }
    final content = _contentTransform(platter, platter.width / size.width);
    // The card paints the same surface and shadow as the platter, so once a
    // sub menu leaves the root's bounds, the platter steps aside instead of
    // clipping the levels until the springs settle.
    if (!frame.platter) {
      _pane.clear();
      context.pushTransform(
        needsCompositing,
        offset,
        content,
        (context, offset) => context.paintChild(child, offset),
      );
      return;
    }
    final glass = _glass;
    final settled = progress.clamp(0.0, 1.0);
    final clip = _shape(radius).getOuterPath(platter);
    final depth = droplet == null
        ? _rimDepth
        : gooPairDepth(platter, radius, droplet, _dropletBlend);
    final merged = depth >= 0;
    final outline = merged
        ? clip
        : gooPairOutline(
            platter,
            radius,
            droplet!,
            _dropletBlend,
            coarse: glass?.coarse ?? false,
            secondOutline: _sourceOutline(droplet),
            secondRadius: _sourceRadius(droplet),
          );
    final rim = merged
        ? glass?.rim.scale(settled * math.min(1.0, depth / _rimDepth))
        : null;
    final path = outline.shift(offset);
    if (glass != null) {
      final shape = outline.getBounds();
      // Frosted as the card and shadowed after, so one capture serves the morph.
      _pane.paint(
        context,
        (merged ? platter : shape).shift(offset),
        shape: merged
            ? LiquidGlassCorners.circular(radius)
            : LiquidGlassGoo(
                outline.shift(-shape.topLeft),
                [platter.shift(-shape.topLeft), droplet!.shift(-shape.topLeft)],
                const [_dropletBlend],
                firstRadius: math.max(radius, 1e-3),
              ),
        style: LiquidGlassStyle.lerp(
          glass.from,
          glass.to,
          settled,
        ).copyWith(blur: glass.to.blur),
        hold: bounds
            .expandToInclude(anchor)
            .inflate(size.longestSide * _overshootPerLongestSide)
            .shift(offset),
        rim: rim,
      );
    }
    _paintShadows(context.canvas, path, platter.shift(offset), radius, settled);
    final Color fill;
    if (glass == null) {
      fill = Color.lerp(_sourceColor, _surfaceColor, settled)!;
    } else {
      fill = _sourceColor.withValues(alpha: _sourceColor.a * (1 - settled));
    }
    final sourceOpacity = 1.0 - math.min(1.0, settled / _glyphUnderAt);
    context.canvas.drawPath(
      path,
      Paint()
        ..color = image == null ? fill : _underSnapshot(fill, sourceOpacity),
    );
    rim?.paintLine(context.canvas, platter.shift(offset), radius);
    if (image != null && droplet != null) {
      final under = math.min(1.0, settled / _glyphUnderAt);
      _paintSource(
        context.canvas,
        image,
        droplet.shift(offset),
        1 - under,
        blur: _openContentBlur * under,
      );
    }
    if (settled > 0) {
      context.pushClipPath(
        needsCompositing,
        offset,
        bounds,
        clip,
        (context, offset) => _paintContent(
          context,
          offset,
          settled,
          math.sqrt(
            math.pow(_openContentBlur * (1 - settled), 2) +
                math.pow(
                  _openBlurBump * _riseAndFall(frame.time, 0.069, 2.1),
                  2,
                ),
          ),
          content,
        ),
      );
    }
  }

  /// The return into the control [time] seconds on, from [start] or the card.
  void _paintReturn(
    PaintingContext context,
    Offset offset,
    _MorphFrame? start,
    double time,
  ) {
    final image = _sourceImage!.value;
    if (image == null) {
      return;
    }
    final anchor = _localAnchor;
    final bounds = Offset.zero & size;
    final card = _closingSurface ?? bounds;
    if (card.isEmpty) {
      _pane.clear();
      return;
    }
    final opening = start == null ? null : _openingOf(start.time, card);
    final from = opening?.platter ?? card;
    final fromRadius = opening?.radius ?? _cardRadius;
    final fromDroplet =
        opening?.droplet ??
        Rect.fromCenter(
          center: Offset.lerp(anchor.center, from.center, _dropletShare)!,
          width: anchor.width * _dropletShare,
          height: anchor.height * _dropletShare,
        );
    // The springs still ring a little when the route ends; ease that out so
    // the control takes over exactly where the droplet lands.
    final ringing =
        1 - Curves.easeIn.transform(((time - 0.4) / 0.1).clamp(0.0, 1.0));
    final progress = 1 + (_droplet.x(time) - 1) * ringing;
    final toCard = from.center.dy - anchor.center.dy;
    final away = toCard.sign;
    final dip =
        math.min(0.21 * toCard.abs(), 22.0) * _riseAndFall(time, 0.072, 2.4);
    final lift =
        math.min(0.25 * toCard.abs(), 19.0) * _riseAndFall(time, 0.176, 4.9);
    final scale = lerpDouble(
      1,
      _platterInControl *
          anchor.shortestSide /
          math.max(from.width, from.height),
      progress,
    )!;
    final platter = Rect.fromCenter(
      center:
          Offset.lerp(from.center, anchor.center, progress)! +
          Offset(0, away * dip * ringing),
      width: math.max(0.0, from.width * scale),
      height: math.max(
        0.0,
        lerpDouble(from.height, from.width, progress)! * scale,
      ),
    );
    final platterRadius = math.max(
      0.0,
      lerpDouble(fromRadius, from.width / 2, progress)! * scale,
    );
    final droplet = Rect.fromCenter(
      center:
          Offset.lerp(fromDroplet.center, anchor.center, progress)! -
          Offset(0, away * lift * ringing),
      width: lerpDouble(fromDroplet.width, anchor.width, progress)!,
      height: lerpDouble(fromDroplet.height, anchor.height, progress)!,
    );
    final absorbed = _dropletAbsorbed.transform(
      (time / _dismissSeconds).clamp(0.0, 1.0),
    );
    final blend = _dropletBlend * math.min(1.0, time / 0.05) * (1 - absorbed);
    final glass = _glass;
    final outline = gooPairOutline(
      platter,
      platterRadius,
      droplet,
      blend,
      coarse: glass?.coarse ?? false,
      secondOutline: _sourceOutline(droplet),
      secondRadius: _sourceRadius(droplet),
    );
    final shape = outline.getBounds();
    final reveal =
        (opening?.progress.clamp(0.0, 1.0) ?? 1) *
        (1 -
            Curves.easeOut.transform(
              (time / _dropletContentOut).clamp(0.0, 1.0),
            ));
    final remaining = (1 - progress).clamp(0.0, 1.0);
    final path = outline.shift(offset);
    if (glass != null) {
      _pane.paint(
        context,
        shape.shift(offset),
        // Once the platter is inside its source, use the source pane's exact
        // analytic corners. A sampled radial outline is unnecessary here and
        // can become unstable when the goo union changes contour count.
        shape:
            gooPairDepth(droplet, _sourceRadius(droplet), platter, blend) >= 0
            ? LiquidGlassCorners.circular(_sourceRadius(droplet))
            : LiquidGlassGoo(
                outline.shift(-shape.topLeft),
                [platter.shift(-shape.topLeft), droplet.shift(-shape.topLeft)],
                [blend],
                firstRadius: math.max(platterRadius, 1e-3),
              ),
        style: LiquidGlassStyle.lerp(
          glass.from,
          glass.to,
          remaining,
        ).copyWith(blur: lerpDouble(glass.to.blur, glass.from.blur, absorbed)),
        hold: bounds
            .expandToInclude(from)
            .expandToInclude(anchor.inflate(anchor.longestSide))
            .inflate(size.longestSide * _overshootPerLongestSide)
            .shift(offset),
        holdFrost: absorbed > 0 ? glass.from.blur : null,
      );
    }
    // Returning cards keep their own shadow as they fade into the platter's,
    // which fills under them from the start so the page does not show
    // through; on glass they stand bare and the platter holds throughout.
    _paintShadows(
      context.canvas,
      path,
      platter.shift(offset),
      platterRadius,
      remaining * (glass == null ? 1 - reveal : 1.0),
    );
    final Color fill;
    if (glass == null) {
      fill = Color.lerp(_surfaceColor, _sourceColor, progress)!;
    } else {
      fill = _sourceColor.withValues(alpha: _sourceColor.a * (1 - remaining));
    }
    final sourceOpacity = lerpDouble(
      0.2,
      1,
      _dropletGlyphCurve.transform((time / _dropletGlyphIn).clamp(0.0, 1.0)),
    )!;
    context.canvas.drawPath(
      path,
      Paint()..color = _underSnapshot(fill, sourceOpacity),
    );
    _paintSource(context.canvas, image, droplet.shift(offset), sourceOpacity);
    if (reveal > 0 && !size.isEmpty) {
      _paintContent(
        context,
        offset,
        reveal,
        _maxContentBlur * (1 - reveal),
        _contentTransform(
          platter,
          math.min(platter.width / card.width, platter.height / card.height),
          content: card,
        ),
      );
    }
  }

  void _paintShadows(
    Canvas canvas,
    Path path,
    Rect rect,
    double radius,
    double strength,
  ) {
    final reach = _shadows.fold(
      0.0,
      (reach, shadow) =>
          math.max(reach, shadow.blurSigma * 3 + shadow.offset.distance),
    );
    // The blur shades under the platter too; keep that out of fading content.
    canvas
      ..save()
      ..clipPath(
        Path()
          ..fillType = PathFillType.evenOdd
          ..addRect(path.getBounds().inflate(reach))
          ..addPath(path, Offset.zero),
      );
    final corners = BorderRadius.circular(radius);
    for (final shadow in _shadows) {
      canvas.drawRSuperellipse(
        corners.toRSuperellipse(rect.shift(shadow.offset)),
        shadow.toPaint()
          ..color = shadow.color.withValues(alpha: shadow.color.a * strength),
      );
    }
    canvas.restore();
  }

  void _paintSource(
    Canvas canvas,
    ui.Image image,
    Rect rect,
    double opacity, {
    double blur = 0,
  }) {
    if (Color.getAlphaFromOpacity(opacity) == 0) {
      return;
    }
    canvas.save();
    final overflow = _imageOverflow(rect);
    var clip = _sourceOutline(rect);
    for (final region in overflow) {
      clip = Path.combine(PathOperation.union, clip, Path()..addRect(region));
    }
    canvas.clipPath(clip);
    canvas.drawImageRect(
      image,
      Offset.zero & Size(image.width.toDouble(), image.height.toDouble()),
      _sourceCaptureBounds(rect, overflow),
      Paint()
        ..color = Color.fromRGBO(0, 0, 0, opacity)
        ..filterQuality = FilterQuality.medium
        ..imageFilter = blur > 0
            ? ImageFilter.blur(sigmaX: blur, sigmaY: blur)
            : null,
    );
    canvas.restore();
  }

  void _paintContent(
    PaintingContext context,
    Offset offset,
    double reveal,
    double blur,
    Matrix4 transform,
  ) {
    void paintChild(PaintingContext context, Offset offset) =>
        context.pushTransform(
          needsCompositing,
          offset,
          transform,
          (context, offset) => context.paintChild(child!, offset),
        );
    void paintBlurred(PaintingContext context, Offset offset) {
      if (blur < _unseenBlur) {
        paintChild(context, offset);
        return;
      }
      context.pushLayer(
        ImageFilterLayer(
          imageFilter: ImageFilter.blur(sigmaX: blur, sigmaY: blur),
        ),
        paintChild,
        offset,
      );
    }

    final alpha = Color.getAlphaFromOpacity(reveal);
    if (alpha == 255) {
      paintBlurred(context, offset);
      return;
    }
    context.pushOpacity(offset, alpha, paintBlurred);
  }

  Matrix4 _contentTransform(Rect rect, double scale, {Rect? content}) {
    final source = content ?? Offset.zero & size;
    return Matrix4.translationValues(rect.center.dx, rect.center.dy, 0)
      ..scaleByDouble(scale, scale, 1, 1)
      ..translateByDouble(-source.center.dx, -source.center.dy, 0, 1);
  }
}

class _PopupAnchorTracker extends StatefulWidget {
  const _PopupAnchorTracker({
    required this.anchorOf,
    required this.builder,
    required this.child,
  });

  final _PopupAnchorResolver anchorOf;
  final Widget Function(Rect anchor, EdgeInsets safeInsets, Widget child)
  builder;
  final Widget child;

  @override
  State<_PopupAnchorTracker> createState() => _PopupAnchorTrackerState();
}

class _PopupAnchorTrackerState extends State<_PopupAnchorTracker> {
  Rect? _anchor;
  bool _syncScheduled = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _scheduleSync();
  }

  void _scheduleSync() {
    if (_syncScheduled) {
      return;
    }
    _syncScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _syncScheduled = false;
      if (!mounted) {
        return;
      }
      final anchor = widget.anchorOf();
      if (anchor == null || anchor == _anchor) {
        return;
      }
      setState(() {
        _anchor = anchor;
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    final mediaQuery = MediaQuery.of(context);
    final anchor = _anchor ??= widget.anchorOf() ?? Rect.zero;
    // padding already gives up the part of the bottom inset the keyboard
    // covers, so the sum keeps clear of the taller of the two.
    return widget.builder(
      anchor,
      mediaQuery.padding + mediaQuery.viewInsets,
      widget.child,
    );
  }
}

class _PopupLayoutDelegate extends SingleChildLayoutDelegate {
  const _PopupLayoutDelegate({
    required this.anchor,
    required this.safeInsets,
    required this.placement,
    required this.pin,
  });

  final Rect anchor;
  final EdgeInsets safeInsets;
  final FlClashPopupPlacement placement;
  final _PopupPin pin;

  EdgeInsets get _insets => safeInsets + const EdgeInsets.all(_screenMargin);

  @override
  Size getSize(BoxConstraints constraints) => constraints.biggest;

  @override
  BoxConstraints getConstraintsForChild(BoxConstraints constraints) {
    final insets = _insets;
    return BoxConstraints.loose(
      Size(
        math.max(0.0, constraints.maxWidth - insets.horizontal),
        math.max(0.0, constraints.maxHeight - insets.vertical),
      ),
    );
  }

  @override
  Offset getPositionForChild(Size size, Size childSize) {
    final insets = _insets;
    final maxX = size.width - insets.right - childSize.width;
    final maxY = size.height - insets.bottom - childSize.height;
    final pinned = pin.topRightFor(this, size);
    final (x, y) = pinned != null
        ? (pinned.dx - childSize.width, pinned.dy)
        : switch (placement) {
            FlClashPopupPlacement.overAnchorEnd => (
              anchor.right - childSize.width,
              anchor.top - _anchorOverlap,
            ),
            FlClashPopupPlacement.overAnchorStart => (
              anchor.left,
              anchor.top - _anchorOverlap,
            ),
            FlClashPopupPlacement.belowPoint => (
              anchor.left,
              _yBelowPoint(childSize.height, insets.top, maxY),
            ),
          };
    final minY = placement == FlClashPopupPlacement.belowPoint
        ? insets.top
        : math.max(safeInsets.top, math.min(insets.top, y));
    final position = Offset(
      x.clamp(insets.left, math.max(insets.left, maxX)),
      y.clamp(minY, math.max(minY, maxY)),
    );
    if (pinned == null) {
      pin.hold(this, size, position.translate(childSize.width, 0));
    }
    return position;
  }

  double _yBelowPoint(double height, double minY, double maxY) {
    if (anchor.bottom > maxY && anchor.top - height >= minY) {
      return anchor.top - height;
    }
    return anchor.bottom;
  }

  bool _placesLike(_PopupLayoutDelegate other) {
    return other.anchor == anchor &&
        other.safeInsets == safeInsets &&
        other.placement == placement;
  }

  @override
  bool shouldRelayout(_PopupLayoutDelegate oldDelegate) {
    return !_placesLike(oldDelegate) || oldDelegate.pin != pin;
  }
}

/// Keeps the top right corner the menu levels stack from while a sub menu
/// unfolds, so the card neither slides nor flips around what it avoids.
class _PopupPin {
  _PopupLayoutDelegate? _placement;
  Size _size = Size.zero;
  Offset _topRight = Offset.zero;

  Offset? topRightFor(_PopupLayoutDelegate placement, Size size) {
    final held = _placement;
    if (held == null || size != _size || !held._placesLike(placement)) {
      return null;
    }
    return _topRight;
  }

  void hold(_PopupLayoutDelegate placement, Size size, Offset topRight) {
    _placement = placement;
    _size = size;
    _topRight = topRight;
  }
}

/// FlClash's popup opening from a single target (normally 44 dp).
/// [popupBuilder] supplies content only: this route paints the surface/clip.
/// Choose with `Navigator.of(context).pop<T>(value)`; [onSelected] runs once
/// the source has reappeared. Use nonblocking [onOpened] for refreshing data.
class FlClashPopupBox<T> extends StatefulWidget {
  const FlClashPopupBox({
    super.key,
    required this.targetBuilder,
    required this.popupBuilder,
    this.placement = FlClashPopupPlacement.overAnchorEnd,
    this.onOpened,
    this.onSelected,
    this.sourceShape,
  });

  final Widget Function(BuildContext context, VoidCallback open) targetBuilder;

  /// Called synchronously after the route is pushed; data refresh must not block.
  final VoidCallback? onOpened;

  /// Runs after the return animation restores the source control.
  final ValueChanged<T>? onSelected;

  final WidgetBuilder popupBuilder;

  final FlClashPopupPlacement placement;
  final ShapeBorder? sourceShape;

  @override
  State<FlClashPopupBox<T>> createState() => _FlClashPopupBoxState<T>();
}

class _FlClashPopupBoxState<T> extends State<FlClashPopupBox<T>> {
  final _sourceKey = GlobalKey();
  final _sourceHidden = ValueNotifier<bool>(false);
  _FlClashPopupRoute<T>? _route;
  int? _pointer;
  Offset _pressOrigin = Offset.zero;
  bool _pressMoved = false;

  @override
  void dispose() {
    _sourceHidden.dispose();
    super.dispose();
  }

  Rect? _sourceRect() {
    if (!mounted) {
      return null;
    }
    final renderBox = context.findRenderObject() as RenderBox?;
    if (renderBox == null || !renderBox.attached || !renderBox.hasSize) {
      return null;
    }
    final navigatorBox =
        Navigator.maybeOf(context)?.context.findRenderObject() as RenderBox?;
    final origin = renderBox.localToGlobal(Offset.zero, ancestor: navigatorBox);
    return origin & renderBox.size;
  }

  Color _sourceColor() {
    Color? color;
    void visit(Element element) {
      if (color != null) {
        return;
      }
      if (element.widget case final Material material) {
        color = material.type == MaterialType.transparency
            ? Colors.transparent
            : material.color ?? Colors.transparent;
        return;
      }
      element.visitChildren(visit);
    }

    context.visitChildElements(visit);
    final resolved = color;
    if (resolved != null && resolved.a > 0) {
      return resolved;
    }
    return LiquidGlassScope.of(context)
        ? Colors.transparent
        : context.omniPalette.surfacePrimary;
  }

  void _captureSource(
    _FlClashPopupRoute<T> route,
    ValueNotifier<ui.Image?> snapshot,
  ) {
    if (!mounted || !route.isActive) {
      return;
    }
    final boundary = _sourceKey.currentContext?.findRenderObject();
    if (boundary is! _RenderPopupSourceBoundary || !boundary.hasSize) {
      return;
    }
    final pixelRatio = MediaQuery.devicePixelRatioOf(context);
    snapshot.value = _captureShapedSource(
      boundary,
      Offset.zero & boundary.size,
      _visibleSourceShape(
        widget.sourceShape ?? _shape(1000),
        Offset.zero & boundary.size,
        LiquidGlassScope.of(context),
      ),
      pixelRatio,
    );
    _sourceHidden.value = true;
  }

  /// Whether the button takes taps, as the first ink response in it tells.
  bool _targetEnabled() {
    bool? enabled;
    void visit(Element element) {
      if (enabled != null) {
        return;
      }
      if (element.widget case final InkResponse response) {
        enabled = response.onTap != null;
        return;
      }
      element.visitChildren(visit);
    }

    context.visitChildElements(visit);
    return enabled ?? false;
  }

  bool _canOpen() => _pointer == null && _route == null && _targetEnabled();

  void _handlePress(PointerDownEvent event) {
    _pointer = event.pointer;
    _pressOrigin = event.position;
    _pressMoved = false;
    _releaseFromNavigator(event.pointer);
    _open();
  }

  // A navigator cancels the presses it tracks as it navigates (flutter#4770).
  void _releaseFromNavigator(int pointer) {
    final listener = Navigator.maybeOf(context)?.context.findRenderObject();
    if (listener is RenderPointerListener) {
      listener.onPointerCancel?.call(PointerCancelEvent(pointer: pointer));
    }
  }

  // The popup covers the button, so a press let go where it went down leaves
  // the popup open rather than picking what now lies under it.
  void _handlePointerMove(PointerMoveEvent event) {
    if (event.pointer != _pointer) {
      return;
    }
    final slop = computeHitSlop(
      event.kind,
      MediaQuery.maybeGestureSettingsOf(context),
    );
    _pressMoved =
        _pressMoved || (event.position - _pressOrigin).distance > slop;
    if (_pressMoved) {
      _route?._press.drag(event.position);
    }
  }

  void _handlePointerUp(PointerUpEvent event) {
    if (event.pointer != _pointer) {
      return;
    }
    _pointer = null;
    if (_pressMoved) {
      _route?._press.release(event.position);
    }
  }

  void _handlePressCancel() {
    if (_pointer == null) {
      return;
    }
    _pointer = null;
    _route?._press.cancel();
  }

  void _open() {
    if (_route != null) {
      return;
    }
    final sharedSource = FlClashPopupSource.captureOf(context);
    final snapshot = sharedSource?.image ?? ValueNotifier<ui.Image?>(null);
    final navigatorBox =
        Navigator.of(context).context.findRenderObject() as RenderBox?;
    final route = _route = _FlClashPopupRoute<T>(
      barrierLabel: MaterialLocalizations.of(context).modalBarrierDismissLabel,
      builder: (context) => widget.popupBuilder(context),
      anchorOf: sharedSource == null
          ? _sourceRect
          : () => sharedSource.rectIn(navigatorBox),
      placement: widget.placement,
      reduceMotion: MediaQuery.disableAnimationsOf(context),
      sourceColor: sharedSource?.color ?? _sourceColor(),
      sourceImage: snapshot,
      sourceShape: sharedSource?.shape ?? widget.sourceShape ?? _shape(1000),
      sourceGlass: sharedSource?.glass ?? LiquidGlassScope.of(context),
      sourceOverflow:
          sharedSource?.captureOverflow
              .map((rect) => rect.shift(-sharedSource.captureRect.topLeft))
              .toList(growable: false) ??
          const [],
    );
    unawaited(Navigator.of(context).push(route));
    widget.onOpened?.call();
    void reveal() {
      sharedSource?.reveal();
      if (mounted) {
        _sourceHidden.value = false;
      }
    }

    route.animation!.addStatusListener((status) {
      if (status.isDismissed) {
        reveal();
      }
    });
    unawaited(
      route.completed.then((value) {
        if (identical(_route, route)) _route = null;
        reveal();
        if (sharedSource != null) {
          sharedSource.release();
        } else {
          snapshot.value?.dispose();
          snapshot.dispose();
        }
        if (mounted && value != null) widget.onSelected?.call(value);
      }),
    );
    // The control repaints its ink as it is tapped, so it can only be
    // captured once that frame has been painted.
    if (sharedSource == null) {
      WidgetsBinding.instance.addPostFrameCallback(
        (_) => _captureSource(route, snapshot),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return RawGestureDetector(
      excludeFromSemantics: true,
      gestures: {
        _PopupPressRecognizer:
            GestureRecognizerFactoryWithHandlers<_PopupPressRecognizer>(
              () => _PopupPressRecognizer(debugOwner: this),
              (recognizer) => recognizer
                ..delay = Scrollable.maybeOf(context) == null
                    ? Duration.zero
                    : kPressTimeout
                ..canOpen = _canOpen
                ..onPress = _handlePress
                ..onMove = _handlePointerMove
                ..onUp = _handlePointerUp
                ..onCancel = _handlePressCancel,
            ),
      },
      child: ValueListenableBuilder<bool>(
        valueListenable: _sourceHidden,
        builder: (_, hidden, child) =>
            Opacity(opacity: hidden ? 0 : 1, child: child),
        child: _PopupSourceBoundary(
          key: _sourceKey,
          child: widget.targetBuilder(context, _open),
        ),
      ),
    );
  }
}

/// Opens a popup on its button's pointer down and carries the press on into
/// it, claiming the pointer so the button's tap and tooltip stand down. In a
/// scroll view it waits [delay] first, so a drag scrolls instead.
class _PopupPressRecognizer extends OneSequenceGestureRecognizer {
  _PopupPressRecognizer({super.debugOwner});

  Duration delay = Duration.zero;
  bool Function()? canOpen;
  ValueSetter<PointerDownEvent>? onPress;
  ValueSetter<PointerMoveEvent>? onMove;
  ValueSetter<PointerUpEvent>? onUp;
  VoidCallback? onCancel;

  PointerDownEvent? _down;
  Timer? _timer;
  bool _pressed = false;

  @override
  bool isPointerAllowed(PointerDownEvent event) =>
      _down == null &&
      event.buttons == kPrimaryButton &&
      super.isPointerAllowed(event);

  @override
  void addAllowedPointer(PointerDownEvent event) {
    if (!(canOpen?.call() ?? false)) {
      return;
    }
    _down = event;
    _pressed = false;
    startTrackingPointer(event.pointer, event.transform);
    if (delay == Duration.zero) {
      _press();
    } else {
      _timer = Timer(delay, _press);
    }
  }

  void _press() {
    _timer?.cancel();
    _timer = null;
    final down = _down;
    if (down == null || _pressed) {
      return;
    }
    _pressed = true;
    resolve(GestureDisposition.accepted);
    onPress?.call(down);
  }

  @override
  void handleEvent(PointerEvent event) {
    final down = _down;
    if (down == null) {
      return;
    }
    if (event is PointerMoveEvent) {
      if (_pressed) {
        onMove?.call(event);
      } else if ((event.position - down.position).distance >
          computeHitSlop(event.kind, gestureSettings)) {
        resolve(GestureDisposition.rejected);
        _reset(event.pointer);
      }
    } else if (event is PointerUpEvent) {
      _press();
      onUp?.call(event);
      _reset(event.pointer);
    } else if (event is PointerCancelEvent) {
      if (_pressed) {
        onCancel?.call();
      } else {
        resolve(GestureDisposition.rejected);
      }
      _reset(event.pointer);
    }
  }

  @override
  void rejectGesture(int pointer) {
    final pressed = _pressed;
    _reset(pointer);
    if (pressed) {
      onCancel?.call();
    }
  }

  void _reset(int pointer) {
    _timer?.cancel();
    _timer = null;
    _down = null;
    _pressed = false;
    stopTrackingPointer(pointer);
  }

  @override
  void didStopTrackingLastPointer(int pointer) {}

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  String get debugDescription => 'popup press';
}
