import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/theme/theme_context.dart';

/// A pane divider that shares the host's resize transaction for touch, pointer,
/// keyboard and accessibility actions. It never owns or stores pane widths.
class ChatPaneResizeHandle extends StatefulWidget {
  const ChatPaneResizeHandle({
    super.key,
    required this.label,
    required this.hint,
    required this.width,
    required this.paneWidth,
    required this.onDragUpdate,
    required this.onCollapse,
    this.onDragStart,
    this.onDragEnd,
    this.leadingPane = true,
    this.viewportWidth,
  });

  final String label;
  final String hint;
  final double width;
  final double paneWidth;
  final ValueChanged<double> onDragUpdate;
  final VoidCallback onCollapse;
  final VoidCallback? onDragStart;
  final VoidCallback? onDragEnd;
  final bool leadingPane;
  final double? viewportWidth;

  @override
  State<ChatPaneResizeHandle> createState() => _ChatPaneResizeHandleState();
}

class _ChatPaneResizeHandleState extends State<ChatPaneResizeHandle> {
  bool _hovered = false;
  bool _focused = false;
  bool _dragging = false;

  @override
  void didUpdateWidget(ChatPaneResizeHandle oldWidget) {
    super.didUpdateWidget(oldWidget);
    // The host ends the resize transaction when the window changes. Ignore
    // the old pointer stream until a fresh drag starts in the new geometry.
    if (oldWidget.viewportWidth != widget.viewportWidth) _dragging = false;
  }

  void _step(double delta) {
    widget.onDragStart?.call();
    widget.onDragUpdate(delta);
    widget.onDragEnd?.call();
  }

  void _endDrag() {
    if (!_dragging) return;
    setState(() => _dragging = false);
    widget.onDragEnd?.call();
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final active = _hovered || _focused || _dragging;
    final duration = MediaQuery.disableAnimationsOf(context)
        ? Duration.zero
        : const Duration(milliseconds: 160);
    return Semantics(
      label: widget.label,
      hint: widget.hint,
      value: '${widget.paneWidth.round()}',
      increasedValue: '${(widget.paneWidth + 24).round()}',
      decreasedValue: '${(widget.paneWidth - 24).round()}',
      onIncrease: () => _step(widget.leadingPane ? 24 : -24),
      onDecrease: () => _step(widget.leadingPane ? -24 : 24),
      onTap: widget.onCollapse,
      child: FocusableActionDetector(
        mouseCursor: SystemMouseCursors.resizeColumn,
        onShowHoverHighlight: (value) => setState(() => _hovered = value),
        onShowFocusHighlight: (value) => setState(() => _focused = value),
        shortcuts: const {
          SingleActivator(LogicalKeyboardKey.arrowLeft): _ResizePaneIntent(-24),
          SingleActivator(LogicalKeyboardKey.arrowRight): _ResizePaneIntent(24),
          SingleActivator(LogicalKeyboardKey.arrowLeft, shift: true):
              _ResizePaneIntent(-64),
          SingleActivator(LogicalKeyboardKey.arrowRight, shift: true):
              _ResizePaneIntent(64),
          SingleActivator(LogicalKeyboardKey.enter): ActivateIntent(),
          SingleActivator(LogicalKeyboardKey.space): ActivateIntent(),
        },
        actions: {
          _ResizePaneIntent: CallbackAction<_ResizePaneIntent>(
            onInvoke: (intent) => _step(intent.delta),
          ),
          ActivateIntent: CallbackAction<ActivateIntent>(
            onInvoke: (_) => widget.onCollapse(),
          ),
        },
        child: Tooltip(
          message: '${widget.label}\n${widget.hint}',
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            excludeFromSemantics: true,
            onDoubleTap: widget.onCollapse,
            onHorizontalDragStart: (_) {
              setState(() => _dragging = true);
              widget.onDragStart?.call();
            },
            onHorizontalDragUpdate: (details) {
              if (_dragging) widget.onDragUpdate(details.delta.dx);
            },
            onHorizontalDragEnd: (_) => _endDrag(),
            onHorizontalDragCancel: _endDrag,
            child: SizedBox(
              width: widget.width,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  VerticalDivider(
                    width: widget.width,
                    thickness: 0.5,
                    indent: 8,
                    endIndent: 8,
                    color: palette.borderSubtle.withValues(alpha: .7),
                  ),
                  Center(
                    child: AnimatedContainer(
                      duration: duration,
                      curve: Curves.easeOutCubic,
                      width: active ? 18 : 12,
                      height: active ? 80 : 64,
                      alignment: Alignment.center,
                      decoration: BoxDecoration(
                        color: active
                            ? palette.accentPrimary.withValues(alpha: .09)
                            : Colors.transparent,
                        borderRadius: BorderRadius.circular(99),
                      ),
                      child: AnimatedContainer(
                        duration: duration,
                        width: active ? 3 : 2,
                        height: active ? 36 : 28,
                        decoration: BoxDecoration(
                          color: active
                              ? palette.accentPrimary
                              : palette.borderStrong,
                          borderRadius: BorderRadius.circular(99),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _ResizePaneIntent extends Intent {
  const _ResizePaneIntent(this.delta);
  final double delta;
}
