import 'package:flutter/material.dart';
import 'package:ui/widgets/flclash/elastic_press.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';

/// Glass presentation behind an existing FAB. The builder keeps the original
/// button's Hero, focus, tooltip, dimensions and action ownership intact.
class OmniGlassFloatingActionButton extends StatefulWidget {
  const OmniGlassFloatingActionButton({
    super.key,
    required this.builder,
    this.shape,
  });

  final ShapeBorder? shape;

  /// Use a transparent background while [glass] is true; every other button
  /// property remains owned by the original FloatingActionButton.
  final FloatingActionButton Function(BuildContext context, bool glass) builder;

  @override
  State<OmniGlassFloatingActionButton> createState() =>
      _OmniGlassFloatingActionButtonState();
}

class _OmniGlassFloatingActionButtonState
    extends State<OmniGlassFloatingActionButton> {
  final _pointers = <int>{};

  void _end(PointerEvent event) {
    if (_pointers.remove(event.pointer) && mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final glass = LiquidGlassScope.of(context);
    final theme = Theme.of(context);
    final button = widget.builder(context, glass);
    final shape =
        button.shape ??
        widget.shape ??
        theme.floatingActionButtonTheme.shape ??
        (theme.useMaterial3
            ? const RoundedRectangleBorder(
                borderRadius: BorderRadius.all(Radius.circular(16)),
              )
            : const CircleBorder());
    return FlClashElasticPress(
      enabled: glass && button.onPressed != null,
      child: Listener(
        onPointerDown: (event) {
          if (button.onPressed != null) {
            setState(() => _pointers.add(event.pointer));
          }
        },
        onPointerUp: _end,
        onPointerCancel: _end,
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            Positioned.fill(
              child: IgnorePointer(
                child: glass
                    ? LiquidGlass(
                        style: LiquidGlassStyle.regular(theme.colorScheme),
                        clipper: ShapeBorderClipper(
                          shape: shape,
                          textDirection: Directionality.of(context),
                        ),
                        pressed: _pointers.isNotEmpty,
                      )
                    : const SizedBox.shrink(),
              ),
            ),
            // Keep this child in the same slot when the material is toggled.
            Theme(
              data: glass
                  ? theme.copyWith(
                      splashFactory: NoSplash.splashFactory,
                      highlightColor: Colors.transparent,
                      splashColor: Colors.transparent,
                      tooltipTheme: theme.tooltipTheme.copyWith(
                        triggerMode: TooltipTriggerMode.manual,
                      ),
                    )
                  : theme,
              child: button,
            ),
          ],
        ),
      ),
    );
  }
}
