import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/flclash/elastic_press.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';

@immutable
class OmniSegmentedOption<T> {
  const OmniSegmentedOption({
    required this.value,
    required this.label,
    this.icon,
    this.id,
  });

  final T value;
  final String label;
  final IconData? icon;
  final String? id;
}

/// A controlled discrete selector. Dragging previews a value; only release
/// commits it, so cancelled gestures never change settings or launch work.
class OmniSegmentedSlider<T> extends StatefulWidget {
  const OmniSegmentedSlider({
    super.key,
    required this.value,
    required this.options,
    required this.onChanged,
    this.position,
    this.keyPrefix = 'omni-segment-option',
    this.height = 38,
    this.fontSize = 12.5,
  }) : assert(options.length > 0),
       assert(height > 6);

  final T? value;
  final List<OmniSegmentedOption<T>> options;
  final ValueChanged<T>? onChanged;

  /// Optional page-controller position; it follows the owner without easing.
  final double? position;
  final String keyPrefix;
  final double height;
  final double fontSize;

  @override
  State<OmniSegmentedSlider<T>> createState() => _OmniSegmentedSliderState<T>();
}

class _OmniSegmentedSliderState<T> extends State<OmniSegmentedSlider<T>>
    with SingleTickerProviderStateMixin {
  static const _padding = 3.0;
  static const _trackRadius = 12.0;
  static const _thumbRadius = 9.5;
  static const _settleDuration = Duration(milliseconds: 220);

  late final AnimationController _thumb = AnimationController.unbounded(
    vsync: this,
    value: _ownedPosition,
  );
  late List<FocusNode> _focusNodes = _createFocusNodes();
  FlClashElasticPressHandle? _press;
  bool _dragging = false;
  double _dragDownPosition = 0;
  double _dragOffset = 0;
  int? _pointer;
  Offset _pointerDown = Offset.zero;
  bool _verticalGesture = false;
  int? _previewIndex;

  bool get _enabled => widget.onChanged != null;
  bool get _reducedMotion => MediaQuery.disableAnimationsOf(context);
  int get _selectedIndex {
    final index = widget.options.indexWhere(
      (item) => item.value == widget.value,
    );
    return index;
  }

  double get _ownedPosition => (widget.position ?? _selectedIndex.toDouble())
      .clamp(0.0, widget.options.length - 1.0);

  List<FocusNode> _createFocusNodes() => [
    for (final option in widget.options)
      FocusNode(debugLabel: 'segment-${option.id ?? option.value}'),
  ];

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_reducedMotion && !_dragging) _thumb.value = _ownedPosition;
  }

  @override
  void didUpdateWidget(covariant OmniSegmentedSlider<T> oldWidget) {
    super.didUpdateWidget(oldWidget);
    final optionsChanged = !listEquals(
      oldWidget.options.map((option) => option.value).toList(),
      widget.options.map((option) => option.value).toList(),
    );
    if (optionsChanged) {
      for (final node in _focusNodes) {
        node.dispose();
      }
      _focusNodes = _createFocusNodes();
    }
    if (optionsChanged ||
        oldWidget.value != widget.value ||
        oldWidget.position != widget.position ||
        !_enabled) {
      _dragging = false;
      _previewIndex = null;
      _pointer = null;
      _press?.release();
      _settle();
    }
  }

  void _settle() {
    if (_reducedMotion || widget.position != null) {
      _thumb.value = _ownedPosition;
    } else {
      _thumb.animateTo(
        _ownedPosition,
        duration: _settleDuration,
        curve: Curves.easeOutCubic,
      );
    }
  }

  void _select(int index, {bool haptic = true}) {
    if (!_enabled) return;
    if (haptic && widget.options[index].value != widget.value) {
      HapticFeedback.selectionClick();
    }
    widget.onChanged!(widget.options[index].value);
  }

  double _positionAt(double x, double width) {
    final logicalX = Directionality.of(context) == TextDirection.rtl
        ? width - x
        : x;
    final segmentWidth = (width - _padding * 2) / widget.options.length;
    return (logicalX - _padding) / segmentWidth - 0.5;
  }

  void _startDrag(DragStartDetails details, double width) {
    if (_verticalGesture || !_enabled) return;
    _thumb.stop();
    final distance = _dragDownPosition - _thumb.value;
    // Preserve the finger's offset on the thumb; a drag begun elsewhere on
    // the track picks up the lens at the finger instead.
    _dragOffset = distance.abs() <= 0.5 ? distance : 0;
    setState(() {
      _dragging = true;
      _previewIndex = _selectedIndex;
    });
    _updateDrag(details.localPosition.dx, width);
  }

  void _updateDrag(double x, double width) {
    final position = (_positionAt(x, width) - _dragOffset).clamp(
      0.0,
      widget.options.length - 1.0,
    );
    final index = position.round();
    if (index != _previewIndex) HapticFeedback.selectionClick();
    _previewIndex = index;
    _thumb.value = position;
  }

  void _endDrag(DragEndDetails details, double width) {
    if (!_dragging) return;
    final direction = Directionality.of(context) == TextDirection.rtl ? -1 : 1;
    final velocity = (details.primaryVelocity ?? 0) * direction;
    final segmentWidth = (width - _padding * 2) / widget.options.length;
    final projected =
        _thumb.value +
        (velocity.abs() > 350 ? velocity * 0.06 / segmentWidth : 0);
    final index = projected.round().clamp(0, widget.options.length - 1);
    final haptic = index != _previewIndex;
    setState(() {
      _dragging = false;
      _previewIndex = null;
    });
    _select(index, haptic: haptic);
    _settle();
  }

  void _cancelDrag() {
    if (!_dragging) return;
    setState(() {
      _dragging = false;
      _previewIndex = null;
    });
    _settle();
  }

  KeyEventResult _handleKey(KeyEvent event, int index) {
    if (!_enabled || event is! KeyDownEvent) return KeyEventResult.ignored;
    final rtl = Directionality.of(context) == TextDirection.rtl;
    final key = event.logicalKey;
    final next = switch (key) {
      LogicalKeyboardKey.arrowLeft => index + (rtl ? 1 : -1),
      LogicalKeyboardKey.arrowRight => index + (rtl ? -1 : 1),
      LogicalKeyboardKey.home => 0,
      LogicalKeyboardKey.end => widget.options.length - 1,
      _ => null,
    };
    if (next == null) return KeyEventResult.ignored;
    final target = next.clamp(0, widget.options.length - 1);
    _focusNodes[target].requestFocus();
    _select(target);
    return KeyEventResult.handled;
  }

  @override
  void dispose() {
    _thumb.dispose();
    for (final node in _focusNodes) {
      node.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final glass = LiquidGlassScope.of(context);
    if (_selectedIndex < 0 && !_dragging && widget.position == null) {
      _press = null;
    }
    final borderRadius = BorderRadius.circular(_thumbRadius);
    final shadows = [
      BoxShadow(
        color: palette.shadowColor.withValues(alpha: isDark ? 0.16 : 0.04),
        blurRadius: 3,
        offset: const Offset(0, 1),
      ),
    ];
    final thumb = FlClashElasticPress(
      enabled: glass && _enabled,
      listenToPointers: false,
      maxGrowth: 2,
      maxPull: 0,
      child: Builder(
        builder: (context) {
          _press = FlClashElasticPress.maybeOf(context);
          return glass
              ? Stack(
                  fit: StackFit.expand,
                  children: [
                    // As in the dock, a quiet platter keeps the selected value
                    // legible while the clear upstream lens bends its backdrop.
                    DecoratedBox(
                      decoration: BoxDecoration(
                        color: palette.segmentThumb.withValues(alpha: 0.68),
                        borderRadius: borderRadius,
                      ),
                    ),
                    LiquidGlass(
                      style: LiquidGlassStyle.lens(
                        Theme.of(context).colorScheme,
                      ),
                      borderRadius: borderRadius,
                      shadows: shadows,
                    ),
                  ],
                )
              : DecoratedBox(
                  decoration: BoxDecoration(
                    color: palette.segmentThumb,
                    borderRadius: borderRadius,
                    border: Border.all(
                      color: palette.borderSubtle.withValues(
                        alpha: isDark ? 0.45 : 0.55,
                      ),
                      width: 0.5,
                    ),
                    boxShadow: shadows,
                  ),
                );
        },
      ),
    );

    return LiquidGlassFade(
      opacity: AlwaysStoppedAnimation(_enabled ? 1 : 0.45),
      child: Container(
        height: widget.height,
        decoration: BoxDecoration(
          color: palette.segmentTrack,
          borderRadius: BorderRadius.circular(_trackRadius),
          border: Border.all(
            color: palette.borderSubtle.withValues(alpha: isDark ? 0.55 : 0.7),
            width: 0.65,
          ),
        ),
        child: LayoutBuilder(
          builder: (context, constraints) {
            final width = constraints.maxWidth;
            final segmentWidth = (width - _padding * 2) / widget.options.length;
            return Listener(
              onPointerDown: (event) {
                if (!_enabled || _pointer != null || event.buttons != 1) return;
                _pointer = event.pointer;
                _pointerDown = event.localPosition;
                _verticalGesture = false;
                _press?.press(event.position);
              },
              onPointerMove: (event) {
                if (_pointer != event.pointer || _dragging) return;
                final delta = event.localPosition - _pointerDown;
                if (delta.dy.abs() > kTouchSlop &&
                    delta.dy.abs() > delta.dx.abs()) {
                  _verticalGesture = true;
                  _press?.release();
                }
              },
              onPointerUp: (event) {
                if (_pointer != event.pointer) return;
                _pointer = null;
                _press?.release();
              },
              onPointerCancel: (event) {
                if (_pointer != event.pointer) return;
                _pointer = null;
                _press?.release();
                // Flutter reports an accepted drag's pointer cancellation as
                // drag-end, so discard the preview before that callback runs.
                _cancelDrag();
              },
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onHorizontalDragDown: _enabled
                    ? (details) => _dragDownPosition = _positionAt(
                        details.localPosition.dx,
                        width,
                      )
                    : null,
                onHorizontalDragStart: _enabled
                    ? (details) => _startDrag(details, width)
                    : null,
                onHorizontalDragUpdate: _enabled
                    ? (details) {
                        if (_dragging) {
                          _updateDrag(details.localPosition.dx, width);
                        }
                      }
                    : null,
                onHorizontalDragEnd: _enabled
                    ? (details) => _endDrag(details, width)
                    : null,
                onHorizontalDragCancel: _enabled ? _cancelDrag : null,
                child: AnimatedBuilder(
                  animation: _thumb,
                  builder: (context, child) {
                    final position = _thumb.value.clamp(
                      0.0,
                      widget.options.length - 1.0,
                    );
                    final showSelection =
                        _selectedIndex >= 0 ||
                        _dragging ||
                        widget.position != null;
                    return Stack(
                      clipBehavior: Clip.none,
                      children: [
                        if (showSelection)
                          PositionedDirectional(
                            start: _padding + segmentWidth * position,
                            top: _padding,
                            bottom: _padding,
                            width: segmentWidth,
                            child: IgnorePointer(
                              child: SizedBox(
                                key: ValueKey('${widget.keyPrefix}-thumb'),
                                child: child,
                              ),
                            ),
                          ),
                        Padding(
                          padding: const EdgeInsets.symmetric(
                            horizontal: _padding,
                          ),
                          child: Row(
                            children: [
                              for (var i = 0; i < widget.options.length; i++)
                                _buildOption(
                                  i,
                                  showSelection ? position.round() : -1,
                                ),
                            ],
                          ),
                        ),
                      ],
                    );
                  },
                  child: thumb,
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _buildOption(int index, int highlightedIndex) {
    final option = widget.options[index];
    final palette = context.omniPalette;
    final selected = index == highlightedIndex;
    final label = option.icon == null
        ? Text(option.label, maxLines: 1, overflow: TextOverflow.ellipsis)
        : Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                option.icon,
                size: 14,
                color: selected ? palette.accentPrimary : palette.textTertiary,
              ),
              const SizedBox(width: 6),
              Flexible(
                child: Text(
                  option.label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          );
    return Expanded(
      child: Semantics(
        button: true,
        selected: index == _selectedIndex,
        inMutuallyExclusiveGroup: true,
        enabled: _enabled,
        child: Focus(
          canRequestFocus: false,
          onKeyEvent: (_, event) => _handleKey(event, index),
          child: Material(
            color: Colors.transparent,
            child: InkWell(
              key: ValueKey('${widget.keyPrefix}-${option.id ?? option.value}'),
              focusNode: _focusNodes[index],
              borderRadius: BorderRadius.circular(_thumbRadius),
              splashFactory: NoSplash.splashFactory,
              highlightColor: Colors.transparent,
              focusColor: palette.accentPrimary.withValues(alpha: 0.12),
              onTap: _enabled ? () => _select(index) : null,
              child: Center(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  child: AnimatedDefaultTextStyle(
                    duration: _reducedMotion
                        ? Duration.zero
                        : const Duration(milliseconds: 180),
                    curve: Curves.easeOutCubic,
                    style: TextStyle(
                      fontSize: widget.fontSize,
                      fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                      color: selected
                          ? palette.textPrimary
                          : palette.textSecondary,
                    ),
                    child: label,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
