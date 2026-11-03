// Barrier paint ownership and scrim values follow chen08209/FlClash
// lib/ui/dialog.dart and lib/ui/color.dart at
// 7fd417fb9a9a277c6a9339a8ac87f907df7c25f7. Flutter Material route adapters.
// See docs/third-party/flclash-NOTICE.md. SPDX-License-Identifier: GPL-3.0-only
import 'package:flutter/material.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/omni_sheet_motion.dart';

// FlClash's pinned dialog/sheet adapters paint their dimming in LiquidGlassVeil.
// Keep the framework's barrier semantics and gestures while letting the shared
// atlas separate a uniform scrim from the image it captures behind each pane.
Widget wrapOmniGlassModalBarrier(Widget barrier) => switch (barrier) {
  AnimatedModalBarrier() => LiquidGlassVeil(
    color: barrier.color,
    child: ModalBarrier(
      key: barrier.key,
      dismissible: barrier.dismissible,
      onDismiss: barrier.onDismiss,
      semanticsLabel: barrier.semanticsLabel,
      barrierSemanticsDismissible: barrier.barrierSemanticsDismissible,
      clipDetailsNotifier: barrier.clipDetailsNotifier,
      semanticsOnTapHint: barrier.semanticsOnTapHint,
    ),
  ),
  ModalBarrier() when barrier.color != null => LiquidGlassVeil(
    color: AlwaysStoppedAnimation(barrier.color),
    child: ModalBarrier(
      key: barrier.key,
      dismissible: barrier.dismissible,
      onDismiss: barrier.onDismiss,
      semanticsLabel: barrier.semanticsLabel,
      barrierSemanticsDismissible: barrier.barrierSemanticsDismissible,
      clipDetailsNotifier: barrier.clipDetailsNotifier,
      semanticsOnTapHint: barrier.semanticsOnTapHint,
    ),
  ),
  _ => barrier,
};

/// The standard sheet route for a widget that already owns its glass surface.
/// Keeps focus, safe areas, keyboard layout and barrier semantics native, with
/// one shared elastic drag owner above the route's single surface.
Future<T?> showOmniGlassBottomSheet<T>({
  required BuildContext context,
  required WidgetBuilder builder,
  Color? backgroundColor,
  String? barrierLabel,
  double? elevation,
  ShapeBorder? shape,
  Clip? clipBehavior,
  BoxConstraints? constraints,
  Color? barrierColor,
  bool isScrollControlled = false,
  double scrollControlDisabledMaxHeightRatio = 9 / 16,
  bool useRootNavigator = false,
  bool isDismissible = true,
  bool enableDrag = true,
  bool? showDragHandle,
  bool useSafeArea = false,
  RouteSettings? routeSettings,
  AnimationController? transitionAnimationController,
  Offset? anchorPoint,
  AnimationStyle? sheetAnimationStyle,
  bool? requestFocus,
  bool respectPopScope = false,
}) {
  final glassEnabled = LiquidGlassScope.of(context);
  final navigator = Navigator.of(context, rootNavigator: useRootNavigator);
  final localizations = MaterialLocalizations.of(context);
  return navigator.push<T>(
    _OmniGlassBottomSheetRoute<T>(
      glassEnabled: glassEnabled,
      requestedAnimationStyle: sheetAnimationStyle,
      reducedMotion: MediaQuery.disableAnimationsOf(context),
      builder: builder,
      capturedThemes: InheritedTheme.capture(
        from: context,
        to: navigator.context,
      ),
      isScrollControlled: isScrollControlled,
      scrollControlDisabledMaxHeightRatio: scrollControlDisabledMaxHeightRatio,
      barrierLabel: barrierLabel ?? localizations.scrimLabel,
      barrierOnTapHint: localizations.scrimOnTapHint(
        localizations.bottomSheetLabel,
      ),
      backgroundColor: backgroundColor,
      elevation: elevation,
      shape: shape,
      clipBehavior: clipBehavior,
      // Keep modal content readable on large windows regardless of whether a
      // host uses Material 2 or 3. Native route alignment remains responsible
      // for centering, display features and caller-supplied size overrides.
      constraints:
          constraints ??
          Theme.of(context).bottomSheetTheme.constraints ??
          const BoxConstraints(maxWidth: 640),
      isDismissible: isDismissible,
      modalBarrierColor:
          barrierColor ??
          (glassEnabled
              ? Theme.of(context).colorScheme.scrim.withValues(alpha: 0.32)
              : Theme.of(context).bottomSheetTheme.modalBarrierColor),
      enableDrag: enableDrag,
      showDragHandle: showDragHandle,
      settings: routeSettings,
      transitionAnimationController: transitionAnimationController,
      anchorPoint: anchorPoint,
      useSafeArea: useSafeArea,
      sheetAnimationStyle: MediaQuery.disableAnimationsOf(context)
          ? AnimationStyle.noAnimation
          : AnimationStyle(
              duration: sheetAnimationStyle?.duration,
              reverseDuration: sheetAnimationStyle?.reverseDuration,
              // Finger travel and route travel have the same distance.
              curve: Curves.linear,
              reverseCurve: Curves.linear,
            ),
      requestFocus: requestFocus,
    ),
  );
}

class _OmniGlassBottomSheetRoute<T> extends ModalBottomSheetRoute<T> {
  _OmniGlassBottomSheetRoute({
    required this.glassEnabled,
    required this.requestedAnimationStyle,
    required this.reducedMotion,
    required super.builder,
    super.capturedThemes,
    required super.isScrollControlled,
    super.scrollControlDisabledMaxHeightRatio,
    super.barrierLabel,
    super.barrierOnTapHint,
    Color? backgroundColor,
    double? elevation,
    ShapeBorder? shape,
    Clip? clipBehavior,
    super.constraints,
    super.isDismissible,
    super.modalBarrierColor,
    bool enableDrag = true,
    bool? showDragHandle,
    super.settings,
    super.transitionAnimationController,
    super.anchorPoint,
    super.useSafeArea,
    super.sheetAnimationStyle,
    super.requestFocus,
  }) : _contentBuilder = builder,
       _surfaceColor = backgroundColor,
       _surfaceElevation = elevation,
       _surfaceShape = shape,
       _surfaceClip = clipBehavior,
       _dragEnabled = enableDrag,
       _showHandle = showDragHandle,
       super(
         backgroundColor: Colors.transparent,
         elevation: 0,
         shape: const RoundedRectangleBorder(),
         clipBehavior: Clip.none,
         enableDrag: false,
         showDragHandle: false,
       );

  final bool glassEnabled;
  final AnimationStyle? requestedAnimationStyle;
  final bool reducedMotion;
  final WidgetBuilder _contentBuilder;
  final Color? _surfaceColor;
  final double? _surfaceElevation;
  final ShapeBorder? _surfaceShape;
  final Clip? _surfaceClip;
  final bool _dragEnabled;
  final bool? _showHandle;
  AnimationController? _presentation;

  @override
  AnimationController createAnimationController() =>
      _presentation = super.createAnimationController();

  @override
  Simulation? createSimulation({required bool forward}) {
    final duration = forward ? transitionDuration : reverseTransitionDuration;
    if (reducedMotion || duration == Duration.zero) return null;
    if (requestedAnimationStyle == null &&
        transitionAnimationController == null) {
      return OmniSheetMotion.presentationSpring(
        _presentation!.value,
        forward ? 1 : 0,
      );
    }
    return _SheetCurveSimulation(
      from: _presentation!.value,
      to: forward ? 1 : 0,
      duration: duration,
      curve:
          (forward
              ? requestedAnimationStyle?.curve
              : requestedAnimationStyle?.reverseCurve) ??
          Curves.easeOutCubic,
    );
  }

  bool get _canDrag =>
      _dragEnabled &&
      isCurrent &&
      popDisposition != RoutePopDisposition.doNotPop;

  Future<void> _dismiss() async {
    if (isCurrent) await navigator?.maybePop();
  }

  @override
  WidgetBuilder get builder => (context) {
    final theme = Theme.of(context);
    final sheetTheme = theme.bottomSheetTheme;
    Widget content = NotificationListener<DraggableScrollableNotification>(
      onNotification: (notification) {
        if (notification.extent <= notification.minExtent &&
            notification.shouldCloseOnMinExtent) {
          _dismiss();
          return true;
        }
        return false;
      },
      child: Builder(builder: _contentBuilder),
    );
    if (_showHandle ?? (_dragEnabled && (sheetTheme.showDragHandle ?? false))) {
      content = Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Semantics(
            label: MaterialLocalizations.of(context).modalBarrierDismissLabel,
            button: isDismissible,
            onTap: isDismissible ? _dismiss : null,
            child: SizedBox(
              height: kMinInteractiveDimension,
              width: double.infinity,
              child: Center(
                child: Container(
                  width: sheetTheme.dragHandleSize?.width ?? 32,
                  height: sheetTheme.dragHandleSize?.height ?? 4,
                  decoration: BoxDecoration(
                    color:
                        sheetTheme.dragHandleColor ??
                        theme.colorScheme.onSurfaceVariant,
                    borderRadius: BorderRadius.circular(99),
                  ),
                ),
              ),
            ),
          ),
          Flexible(child: content),
        ],
      );
    }
    return OmniSheetMotion(
      presentation: _presentation!,
      elasticEnabled: glassEnabled,
      canDrag: () => _canDrag,
      onDismiss: _dismiss,
      child: Material(
        color:
            _surfaceColor ??
            sheetTheme.modalBackgroundColor ??
            sheetTheme.backgroundColor ??
            theme.colorScheme.surfaceContainerLow,
        elevation:
            _surfaceElevation ??
            sheetTheme.modalElevation ??
            sheetTheme.elevation ??
            0,
        shape: _surfaceShape ?? sheetTheme.shape,
        clipBehavior: _surfaceClip ?? sheetTheme.clipBehavior ?? Clip.none,
        child: content,
      ),
    );
  };

  @override
  Widget buildModalBarrier() => glassEnabled
      ? wrapOmniGlassModalBarrier(super.buildModalBarrier())
      : super.buildModalBarrier();
}

/// Applies a caller's transition curve to the controller itself so the route
/// can remain linear while a finger owns the same presentation controller.
class _SheetCurveSimulation extends Simulation {
  _SheetCurveSimulation({
    required this.from,
    required this.to,
    required Duration duration,
    required this.curve,
  }) : seconds = duration.inMicroseconds / Duration.microsecondsPerSecond;

  final double from;
  final double to;
  final double seconds;
  final Curve curve;

  @override
  double x(double time) =>
      from + (to - from) * curve.transform((time / seconds).clamp(0, 1));

  @override
  double dx(double time) => (x(time + 0.0001) - x(time)) / 0.0001;

  @override
  bool isDone(double time) => time >= seconds;
}
