// A gapped value track with a narrow bar thumb and press feedback.
// See docs/third-party/flclash-NOTICE.md for source and license.
// SPDX-License-Identifier: GPL-3.0-only
import 'package:flutter/gestures.dart';
import 'package:flutter/services.dart';
import 'package:flutter/material.dart';

/// How an [OmniSlider] looks. It gets the box the slider laid out at, how far along
/// the thumb is, from 0 to 1 in reading direction, and how active it is.
abstract class OmniSliderShape {
  const OmniSliderShape();

  double get height;

  /// How far from either end of the box the thumb's center stops.
  double get thumbInset;

  void paint(
    Canvas canvas,
    Size size, {
    required double value,
    required double activation,
    required bool enabled,
    required int? divisions,
    required TextDirection textDirection,
  });
}

/// A value along a track, dragged or tapped to, or moved with the arrow keys.
class OmniSlider extends StatefulWidget {
  const OmniSlider({
    super.key,
    required this.value,
    required this.onChanged,
    this.onChangeEnd,
    this.min = 0,
    this.max = 1,
    this.divisions,
    this.label,
    this.shape,
    this.semanticLabel,
    this.semanticFormatterCallback,
  }) : assert(max >= min),
       assert(value >= min && value <= max),
       assert(divisions == null || divisions > 0);

  final double value;
  final ValueChanged<double>? onChanged;
  final ValueChanged<double>? onChangeEnd;
  final double min;
  final double max;
  final int? divisions;

  /// Shown over the thumb while it moves.
  final String? label;

  /// Defaults to a gapped track with a bar for a thumb.
  final OmniSliderShape? shape;
  final String? semanticLabel;
  final String Function(double value)? semanticFormatterCallback;

  @override
  State<OmniSlider> createState() => _SliderState();
}

class _SliderState extends State<OmniSlider>
    with SingleTickerProviderStateMixin {
  static const _activationDuration = Duration(milliseconds: 100);

  late final _activation = AnimationController(
    vsync: this,
    duration: _activationDuration,
  );
  late final _actions = <Type, Action<Intent>>{
    _AdjustIntent: CallbackAction<_AdjustIntent>(
      onInvoke: (intent) => _adjust(intent.direction),
    ),
  };
  double? _dragValue;
  bool _pointerCancelled = false;
  bool _focused = false;

  bool get _enabled => widget.onChanged != null && _range > 0;

  double get _range => widget.max - widget.min;

  double get _normalized =>
      _range == 0 ? 0 : ((widget.value - widget.min) / _range).clamp(0.0, 1.0);

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _activation.duration = MediaQuery.disableAnimationsOf(context)
        ? Duration.zero
        : _activationDuration;
  }

  @override
  void didUpdateWidget(OmniSlider oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!_enabled) _cancel();
  }

  @override
  void dispose() {
    _activation.dispose();
    super.dispose();
  }

  double _snapped(double normalized) {
    final divisions = widget.divisions;
    final clamped = normalized.clamp(0.0, 1.0);
    final fraction = divisions == null
        ? clamped
        : (clamped * divisions).round() / divisions;
    return widget.min + fraction * _range;
  }

  double _normalizedAt(Offset local, Size size, double inset) {
    final extent = size.width - inset * 2;
    if (extent <= 0) {
      return 0;
    }
    final t = (local.dx - inset) / extent;
    return Directionality.of(context) == TextDirection.rtl ? 1 - t : t;
  }

  void _set(double normalized) {
    if (!_enabled || _pointerCancelled) return;
    final value = _snapped(normalized);
    _dragValue = value;
    if (value != widget.value) {
      widget.onChanged?.call(value);
    }
  }

  void _start(Offset local, Size size, double inset) {
    if (!_enabled || _pointerCancelled) return;
    _activation.forward();
    _set(_normalizedAt(local, size, inset));
  }

  void _end() {
    _activation.reverse();
    final value = _dragValue;
    _dragValue = null;
    if (_enabled && !_pointerCancelled && value != null) {
      widget.onChangeEnd?.call(value);
    }
  }

  void _cancel() {
    _dragValue = null;
    _activation.reverse();
  }

  void _adjust(int direction) {
    if (!_enabled) {
      return;
    }
    final step = 1 / (widget.divisions ?? 10);
    final value = _snapped(_normalized + step * direction);
    if (value != widget.value) {
      widget.onChanged?.call(value);
      widget.onChangeEnd?.call(value);
    }
  }

  @override
  Widget build(BuildContext context) {
    final shape = widget.shape ?? _GappedSliderShape(context);
    final textDirection = Directionality.of(context);
    final towardEnd = textDirection == TextDirection.ltr ? 1 : -1;
    final step = 1 / (widget.divisions ?? 10);
    String describe(double normalized) =>
        widget.semanticFormatterCallback?.call(_snapped(normalized)) ??
        '${(normalized.clamp(0.0, 1.0) * 100).round()}%';
    return Semantics(
      label: widget.semanticLabel,
      slider: true,
      enabled: _enabled,
      value: describe(_normalized),
      increasedValue: _enabled ? describe(_normalized + step) : null,
      decreasedValue: _enabled ? describe(_normalized - step) : null,
      onIncrease: _enabled ? () => _adjust(1) : null,
      onDecrease: _enabled ? () => _adjust(-1) : null,
      child: FocusableActionDetector(
        enabled: _enabled,
        onShowFocusHighlight: (focused) => setState(() => _focused = focused),
        actions: _actions,
        shortcuts: {
          const SingleActivator(LogicalKeyboardKey.arrowRight): _AdjustIntent(
            towardEnd,
          ),
          const SingleActivator(LogicalKeyboardKey.arrowLeft): _AdjustIntent(
            -towardEnd,
          ),
        },
        mouseCursor: _enabled ? SystemMouseCursors.click : MouseCursor.defer,
        child: SizedBox(
          height: shape.height,
          child: LayoutBuilder(
            builder: (context, constraints) {
              final size = constraints.biggest;
              final inset = shape.thumbInset;
              return Listener(
                onPointerDown: (_) => _pointerCancelled = false,
                onPointerCancel: (_) {
                  _pointerCancelled = true;
                  _cancel();
                },
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  excludeFromSemantics: true,
                  dragStartBehavior: DragStartBehavior.down,
                  onHorizontalDragStart: _enabled
                      ? (details) => _start(details.localPosition, size, inset)
                      : null,
                  onHorizontalDragUpdate: _enabled
                      ? (details) => _set(
                          _normalizedAt(details.localPosition, size, inset),
                        )
                      : null,
                  onHorizontalDragEnd: _enabled ? (_) => _end() : null,
                  onHorizontalDragCancel: _enabled ? _cancel : null,
                  onTapDown: _enabled ? (_) => _activation.forward() : null,
                  onTapUp: _enabled
                      ? (details) {
                          _set(
                            _normalizedAt(details.localPosition, size, inset),
                          );
                          _end();
                        }
                      : null,
                  onTapCancel: _enabled ? _cancel : null,
                  child: RepaintBoundary(
                    child: CustomPaint(
                      size: size,
                      painter: _SliderPainter(
                        shape: shape,
                        value: _normalized,
                        enabled: _enabled,
                        divisions: widget.divisions,
                        textDirection: textDirection,
                        label: widget.divisions == null ? null : widget.label,
                        labelStyle: Theme.of(context).textTheme.bodyMedium!
                            .copyWith(
                              fontWeight: FontWeight.w600,
                              color: Theme.of(context)
                                  .colorScheme
                                  .onInverseSurface,
                            ),
                        labelColor: Theme.of(context)
                            .colorScheme
                            .inverseSurface,
                        textScaler: MediaQuery.textScalerOf(context),
                        activation: _activation,
                        focused: _focused,
                        focusColor: Theme.of(context).colorScheme.primary,
                      ),
                    ),
                  ),
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}

class _AdjustIntent extends Intent {
  const _AdjustIntent(this.direction);

  final int direction;
}

class _SliderPainter extends CustomPainter {
  _SliderPainter({
    required this.shape,
    required this.value,
    required this.enabled,
    required this.divisions,
    required this.textDirection,
    required this.label,
    required this.labelStyle,
    required this.labelColor,
    required this.textScaler,
    required this.activation,
    required this.focused,
    required this.focusColor,
  }) : super(repaint: activation);

  final OmniSliderShape shape;
  final double value;
  final bool enabled;
  final int? divisions;
  final TextDirection textDirection;
  final String? label;
  final TextStyle labelStyle;
  final Color labelColor;
  final TextScaler textScaler;
  final Animation<double> activation;
  final bool focused;
  final Color focusColor;

  static const _labelPadding = 8.0;
  static const _labelHeight = 32.0;
  static const _labelGap = 10.0;

  @override
  void paint(Canvas canvas, Size size) {
    shape.paint(
      canvas,
      size,
      value: value,
      activation: activation.value,
      enabled: enabled,
      divisions: divisions,
      textDirection: textDirection,
    );
    if (focused) {
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          (Offset.zero & size).inflate(3),
          const Radius.circular(8),
        ),
        Paint()
          ..color = focusColor
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2,
      );
    }
    final label = this.label;
    if (label == null || activation.value == 0) {
      return;
    }
    final painter = TextPainter(
      text: TextSpan(text: label, style: labelStyle),
      textDirection: textDirection,
      textScaler: textScaler,
    )..layout();
    final inset = shape.thumbInset;
    final t = textDirection == TextDirection.ltr ? value : 1 - value;
    final x = inset + (size.width - inset * 2) * t;
    final width = painter.width + _labelPadding * 2;
    final rect = Rect.fromLTWH(
      x - width / 2,
      -_labelGap - _labelHeight,
      width,
      _labelHeight,
    );
    canvas.save();
    canvas.translate(rect.center.dx, rect.bottom);
    canvas.scale(activation.value);
    canvas.translate(-rect.center.dx, -rect.bottom);
    canvas.drawRSuperellipse(
      RSuperellipse.fromRectAndRadius(rect, const Radius.circular(12)),
      Paint()..color = labelColor,
    );
    painter.paint(
      canvas,
      Offset(rect.left + _labelPadding, rect.center.dy - painter.height / 2),
    );
    canvas.restore();
    painter.dispose();
  }

  @override
  bool shouldRepaint(_SliderPainter oldDelegate) =>
      oldDelegate.shape != shape ||
      oldDelegate.value != value ||
      oldDelegate.enabled != enabled ||
      oldDelegate.divisions != divisions ||
      oldDelegate.textDirection != textDirection ||
      oldDelegate.label != label ||
      oldDelegate.labelStyle != labelStyle ||
      oldDelegate.labelColor != labelColor ||
      oldDelegate.textScaler != textScaler ||
      oldDelegate.focused != focused ||
      oldDelegate.focusColor != focusColor;
}

class _GappedSliderShape extends OmniSliderShape {
  _GappedSliderShape(BuildContext context)
    : _colors = Theme.of(context).colorScheme;

  final ColorScheme _colors;

  static const _trackHeight = 16.0;
  static const _thumbHeight = 44.0;
  static const _restWidth = 4.0;
  static const _activeWidth = 2.0;
  static const _gap = 6.0;
  static const _outerRadius = Radius.circular(_trackHeight / 2);
  static const _innerRadius = Radius.circular(2);
  static const _tickRadius = 2.0;

  @override
  double get height => _thumbHeight;

  @override
  double get thumbInset => _restWidth / 2;

  void _paintTrack(
    Canvas canvas,
    Rect rect,
    Color color, {
    required bool roundsLeft,
    required bool roundsRight,
  }) {
    if (rect.width <= 0) {
      return;
    }
    canvas.drawRSuperellipse(
      RSuperellipse.fromRectAndCorners(
        rect,
        topLeft: roundsLeft ? _outerRadius : _innerRadius,
        bottomLeft: roundsLeft ? _outerRadius : _innerRadius,
        topRight: roundsRight ? _outerRadius : _innerRadius,
        bottomRight: roundsRight ? _outerRadius : _innerRadius,
      ),
      Paint()..color = color,
    );
  }

  @override
  void paint(
    Canvas canvas,
    Size size, {
    required double value,
    required double activation,
    required bool enabled,
    required int? divisions,
    required TextDirection textDirection,
  }) {
    final ltr = textDirection == TextDirection.ltr;
    final top = (size.height - _trackHeight) / 2;
    final bottom = top + _trackHeight;
    final travel = size.width - thumbInset * 2;
    final x = thumbInset + travel * (ltr ? value : 1 - value);
    final active = enabled
        ? _colors.primary
        : _colors.onSurface.withValues(alpha: 0.38);
    final inactive = enabled
        ? _colors.secondaryContainer
        : _colors.onSurface.withValues(alpha: 0.12);
    final (leftColor, rightColor) = ltr
        ? (active, inactive)
        : (inactive, active);
    final thumbWidth = _restWidth + (_activeWidth - _restWidth) * activation;
    final leftEnd = x - thumbWidth / 2 - _gap;
    final rightStart = x + thumbWidth / 2 + _gap;
    _paintTrack(
      canvas,
      Rect.fromLTRB(0, top, leftEnd, bottom),
      leftColor,
      roundsLeft: true,
      roundsRight: false,
    );
    _paintTrack(
      canvas,
      Rect.fromLTRB(rightStart, top, size.width, bottom),
      rightColor,
      roundsLeft: false,
      roundsRight: true,
    );
    if (divisions != null && divisions > 0) {
      final activeTick = enabled ? _colors.onPrimary : _colors.onInverseSurface;
      final inactiveTick = enabled
          ? _colors.onSecondaryContainer
          : _colors.onSurface;
      for (var i = 0; i <= divisions; i++) {
        final tickX = thumbInset + travel * (i / divisions);
        if (tickX > leftEnd - _tickRadius && tickX < rightStart + _tickRadius) {
          continue;
        }
        final isLeft = tickX < x;
        canvas.drawCircle(
          Offset(tickX, size.height / 2),
          _tickRadius,
          Paint()..color = isLeft == ltr ? activeTick : inactiveTick,
        );
      }
    }
    canvas.drawRSuperellipse(
      RSuperellipse.fromRectAndRadius(
        Rect.fromCenter(
          center: Offset(x, size.height / 2),
          width: thumbWidth,
          height: _thumbHeight,
        ),
        const Radius.circular(1000),
      ),
      Paint()
        ..color = enabled
            ? _colors.primary
            : _colors.onSurface.withValues(alpha: 0.38),
    );
  }
}
