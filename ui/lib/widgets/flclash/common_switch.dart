// Adapted from FlClash lib/ui/switch.dart at
// 7fd417fb9a9a277c6a9339a8ac87f907df7c25f7. See flclash-NOTICE.md.
// SPDX-License-Identifier: GPL-3.0-only
import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import 'liquid_glass.dart';

const _fullShape = RoundedSuperellipseBorder(
  borderRadius: BorderRadius.all(Radius.circular(1000)),
);

/// The M3 switch's track and thumb, drawn here so no ripple or overlay rings
/// it, and filled solid while off rather than outlined. A press lifts the
/// thumb out past the track, white as iOS's solid thumb is; on Liquid Glass
/// the lifted thumb turns into a clear lens over the track, as iOS's does.
class CommonSwitch extends StatefulWidget {
  const CommonSwitch({
    super.key,
    required this.value,
    required this.onChanged,
    this.semanticLabel,
    this.duration = const Duration(milliseconds: 300),
  });

  final bool value;
  final ValueChanged<bool>? onChanged;
  final String? semanticLabel;

  /// Overrides toggle travel for callers that must synchronize immediately.
  final Duration duration;

  static const trackWidth = 52.0;
  static const trackHeight = 32.0;

  @override
  State<CommonSwitch> createState() => _CommonSwitchState();
}

class _CommonSwitchState extends State<CommonSwitch>
    with TickerProviderStateMixin {
  // M3's layout: 4 dp either side of the track, as tall as a tap target.
  static const _inset = 4.0;
  static const _thumb = 24.0;
  static const _midThumb = Size(34, 22);
  static const _heldThumb = 40.0;
  static const _heldStretch = Offset(10, -2);
  static const _pressDuration = Duration(milliseconds: 150);
  static const _tapLift = Duration(milliseconds: 200);

  late final _toggle = AnimationController(
    vsync: this,
    duration: widget.duration,
    value: widget.value ? 1 : 0,
  );
  late final _position = CurvedAnimation(
    parent: _toggle,
    curve: Curves.easeOutBack,
    reverseCurve: Curves.easeOutBack.flipped,
  );
  late final _pressController = AnimationController(
    vsync: this,
    duration: _pressDuration,
  );
  late final _press = CurvedAnimation(
    parent: _pressController,
    curve: Curves.easeOutCubic,
  );
  late final _motion = Listenable.merge([_position, _press]);

  Timer? _tapLiftTimer;
  var _dragging = false;
  var _pointerCancelled = false;
  var _focused = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _updateDurations();
  }

  @override
  void didUpdateWidget(CommonSwitch oldWidget) {
    super.didUpdateWidget(oldWidget);
    _updateDurations();
    if (oldWidget.onChanged != null && widget.onChanged == null) {
      _tapLiftTimer?.cancel();
      _dragging = false;
      _pressController.value = 0;
      _settle();
    }
    if (oldWidget.value != widget.value) {
      if (_toggle.isDismissed || _toggle.isCompleted) {
        _position
          ..curve = Curves.easeOutBack
          ..reverseCurve = Curves.easeOutBack.flipped;
      }
      // A drag hands over its flip with the thumb already past halfway.
      final across = (_toggle.value - (widget.value ? 1 : 0)).abs() > 0.5;
      if (across && widget.onChanged != null) {
        _liftAcross();
      }
      _settle();
    }
  }

  @override
  void dispose() {
    _tapLiftTimer?.cancel();
    _position.dispose();
    _press.dispose();
    _toggle.dispose();
    _pressController.dispose();
    super.dispose();
  }

  Duration _motionDuration(Duration duration) =>
      MediaQuery.disableAnimationsOf(context) ||
          widget.duration == Duration.zero
      ? Duration.zero
      : duration;

  void _updateDurations() {
    _toggle.duration = _motionDuration(widget.duration);
    _pressController.duration = _motionDuration(_pressDuration);
  }

  void _settle() {
    if (_toggle.duration == Duration.zero) {
      _toggle.value = widget.value ? 1 : 0;
    } else {
      widget.value ? _toggle.forward() : _toggle.reverse();
    }
  }

  void _flip() => widget.onChanged?.call(!widget.value);

  void _handlePress() {
    _tapLiftTimer?.cancel();
    _pressController.forward();
  }

  void _liftAcross() {
    _tapLiftTimer?.cancel();
    final duration = _motionDuration(_tapLift);
    if (duration == Duration.zero) {
      _pressController.value = 0;
      return;
    }
    _handlePress();
    _tapLiftTimer = Timer(duration, _pressController.reverse);
  }

  void _handleDragStart() {
    _handlePress();
    _dragging = true;
    _position
      ..curve = Curves.linear
      ..reverseCurve = Curves.linear;
  }

  void _handleDrag(double dx) {
    final rtl = Directionality.of(context) == TextDirection.rtl;
    _toggle.value +=
        (rtl ? -dx : dx) / (CommonSwitch.trackWidth - CommonSwitch.trackHeight);
  }

  void _handleRelease() {
    if (_pointerCancelled || widget.onChanged == null) {
      _handleCancel();
      return;
    }
    if (!_dragging) {
      _flip();
      _liftAcross();
      return;
    }
    _pressController.reverse();
    _dragging = false;
    if (_toggle.value >= 0.5 == widget.value) {
      _settle();
      return;
    }
    _flip();
    // The flipped value arrives with the next build, if the owner takes it.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _settle();
      }
    });
  }

  void _handleCancel() {
    _tapLiftTimer?.cancel();
    _pressController.reverse();
    if (_dragging) {
      _dragging = false;
      _settle();
    }
  }

  Rect _thumbIn(Rect track, bool rtl) {
    final on = _toggle.value;
    final stretch = math.sin(math.pi * on.clamp(0.0, 1.0));
    final size = Size.lerp(
      Size.lerp(const Size.square(_thumb), _midThumb, stretch),
      const Size.square(_heldThumb) + _heldStretch * stretch,
      _press.value,
    )!;
    final along =
        track.height / 2 + (track.width - track.height) * _position.value;
    return Rect.fromCenter(
      center: Offset(
        rtl ? track.right - along : track.left + along,
        track.center.dy,
      ),
      width: size.width,
      height: size.height,
    );
  }

  Widget _buildParts(BuildContext context, Size size) {
    final colorScheme = Theme.of(context).colorScheme;
    final enabled = widget.onChanged != null;
    final rtl = Directionality.of(context) == TextDirection.rtl;
    final on = _toggle.value.clamp(0.0, 1.0);
    final lift = _press.value;
    final track = Rect.fromCenter(
      center: size.center(Offset.zero),
      width: CommonSwitch.trackWidth,
      height: CommonSwitch.trackHeight,
    );
    final trackColor = enabled
        ? Color.lerp(
            colorScheme.surfaceContainerHighest,
            colorScheme.primary,
            on,
          )
        : Color.lerp(
            colorScheme.surfaceContainerHighest.withValues(alpha: 0.12),
            colorScheme.onSurface.withValues(alpha: 0.12),
            on,
          );
    final thumbColor = enabled
        ? Color.lerp(colorScheme.outline, colorScheme.onPrimary, on)!
        : Color.lerp(
            colorScheme.onSurface.withValues(alpha: 0.38),
            colorScheme.surface,
            on,
          )!;
    final shadows = [
      if (lift > 0)
        BoxShadow(
          color: colorScheme.shadow.withValues(alpha: 0.2 * lift),
          offset: const Offset(0, 2),
          blurRadius: 6,
        ),
    ];
    final lens = LiquidGlassScope.of(context) && lift > 0;
    final thumbFill = lens
        ? thumbColor.withValues(alpha: thumbColor.a * (1 - lift))
        : Color.lerp(thumbColor, Colors.white, lift)!;
    final thumb = DecoratedBox(
      decoration: ShapeDecoration(
        color: thumbFill,
        shape: _fullShape,
        shadows: lens ? null : shadows,
      ),
    );
    return Stack(
      clipBehavior: Clip.none,
      children: [
        Positioned.fromRect(
          rect: track,
          child: DecoratedBox(
            decoration: ShapeDecoration(color: trackColor, shape: _fullShape),
          ),
        ),
        Positioned.fromRect(
          rect: _thumbIn(track, rtl),
          child: lens
              ? LiquidGlass(
                  style: LiquidGlassStyle.lens(colorScheme).scale(lift),
                  shadows: shadows,
                  child: thumb,
                )
              : thumb,
        ),
        if (_focused)
          Positioned.fromRect(
            rect: track,
            child: DecoratedBox(
              decoration: ShapeDecoration(
                shape: _fullShape.copyWith(
                  side: BorderSide(
                    color: colorScheme.primary,
                    width: 2,
                    strokeAlign: 2,
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final enabled = widget.onChanged != null;
    final size = Size(CommonSwitch.trackWidth + _inset * 2, switch (Theme.of(
      context,
    ).materialTapTargetSize) {
      MaterialTapTargetSize.padded => kMinInteractiveDimension,
      MaterialTapTargetSize.shrinkWrap => kMinInteractiveDimension - 8,
    });
    return Semantics(
      label: widget.semanticLabel,
      toggled: widget.value,
      enabled: enabled,
      onTap: enabled ? _flip : null,
      child: FocusableActionDetector(
        enabled: enabled,
        mouseCursor: enabled ? SystemMouseCursors.click : MouseCursor.defer,
        onShowFocusHighlight: (value) => setState(() => _focused = value),
        actions: {
          ActivateIntent: CallbackAction<ActivateIntent>(
            onInvoke: (_) => _flip(),
          ),
        },
        child: Listener(
          onPointerDown: (_) => _pointerCancelled = false,
          onPointerCancel: (_) {
            // Flutter reports cancellation of an accepted drag through onEnd.
            // Remember the raw cancellation before the recognizer dispatches.
            _pointerCancelled = true;
            _handleCancel();
          },
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            excludeFromSemantics: true,
            onHorizontalDragStart: enabled ? (_) => _handleDragStart() : null,
            onHorizontalDragUpdate: enabled
                ? (details) => _handleDrag(details.primaryDelta!)
                : null,
            onHorizontalDragEnd: enabled ? (_) => _handleRelease() : null,
            onHorizontalDragCancel: enabled ? _handleCancel : null,
            child: _buildTapTarget(
              enabled: enabled,
              child: SizedBox.fromSize(
                size: size,
                child: AnimatedBuilder(
                  animation: _motion,
                  builder: (context, _) => _buildParts(context, size),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// Taps go through an invisible [InkResponse] because an InkWell around the
  /// switch skips its own press ink while one inside it is pressed.
  Widget _buildTapTarget({required bool enabled, required Widget child}) {
    if (!enabled) {
      return child;
    }
    if (Material.maybeOf(context) == null) {
      return GestureDetector(
        excludeFromSemantics: true,
        onTapDown: (_) => _handlePress(),
        onTap: _handleRelease,
        onTapCancel: _handleCancel,
        child: child,
      );
    }
    return InkResponse(
      onTapDown: (_) => _handlePress(),
      onTap: _handleRelease,
      onTapCancel: _handleCancel,
      excludeFromSemantics: true,
      canRequestFocus: false,
      enableFeedback: false,
      splashFactory: NoSplash.splashFactory,
      overlayColor: const WidgetStatePropertyAll(Colors.transparent),
      child: child,
    );
  }
}
