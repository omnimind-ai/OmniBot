import 'package:flutter/material.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/flclash/elastic_press.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/flclash/popup.dart';

/// Shared round navigation control for the home, chat, and settings headers.
class OmniCircleButton extends StatelessWidget {
  /// Shared with the chat header, including animated replacement icons.
  static const defaultIconSize = 20.0;

  const OmniCircleButton({
    super.key,
    required this.icon,
    required this.tooltip,
    required this.onPressed,
    this.size = 44,
    this.iconSize = defaultIconSize,
    this.foregroundColor,
    this.tapTargetSize = MaterialTapTargetSize.shrinkWrap,
    this.iconWidget,
    this.surfaceOpacity = 1,
  }) : _popupSource = false,
       onOpen = null,
       assert(surfaceOpacity >= 0 && surfaceOpacity <= 1);

  /// A menu trigger whose animation uses the visible circle while retaining
  /// IconButton's original padded touch target and keyboard semantics.
  const OmniCircleButton.popup({
    super.key,
    required this.icon,
    required this.tooltip,
    required this.onOpen,
    this.size = 44,
    this.iconSize = defaultIconSize,
    this.foregroundColor,
    this.tapTargetSize = MaterialTapTargetSize.shrinkWrap,
    this.iconWidget,
    this.surfaceOpacity = 1,
  }) : onPressed = null,
       _popupSource = true,
       assert(surfaceOpacity >= 0 && surfaceOpacity <= 1);

  final IconData icon;
  final String tooltip;
  final VoidCallback? onPressed;
  final double size;
  final double iconSize;
  final Color? foregroundColor;
  final MaterialTapTargetSize tapTargetSize;
  final bool _popupSource;
  final ValueChanged<BuildContext>? onOpen;

  /// Allows an icon to morph without replacing the shared button or hit target.
  final Widget? iconWidget;

  /// Cross-fades the standard surface with a temporary transition shape.
  /// Glass buttons use the shared elastic press without a separate ink fill.
  final double surfaceOpacity;

  @override
  Widget build(BuildContext context) {
    final glass = LiquidGlassScope.of(context);
    final enabled = _popupSource ? onOpen != null : onPressed != null;
    return FlClashElasticPress(
      enabled: glass && enabled,
      // Keep the 48 dp hit target while the visible circle owns the spring.
      panelOf: (box) => Rect.fromCenter(
        center: box.size.center(Offset.zero),
        width: size,
        height: size,
      ),
      child: TooltipTheme(
        // Long presses belong to the glass spring; hover tooltips and the
        // button's semantic label remain available.
        data: glass
            ? TooltipTheme.of(context)
                  .copyWith(triggerMode: TooltipTriggerMode.manual)
            : TooltipTheme.of(context),
        child: _buildSource(context),
      ),
    );
  }

  Widget _buildSource(BuildContext context) {
    if (_popupSource) {
      final open = onOpen;
      return FlClashPopupSource(
        shape: const CircleBorder(),
        preserveOutsideCapture: false,
        captureRect: (sourceContext) {
          final box = sourceContext.findRenderObject();
          if (box is! RenderBox || !box.hasSize) return null;
          return Rect.fromCenter(
            center: box.size.center(Offset.zero),
            width: size,
            height: size,
          );
        },
        builder: (sourceContext) => _buildButton(
          context,
          open == null ? null : () => open(sourceContext),
        ),
      );
    }
    return _buildButton(context, onPressed);
  }

  Widget _buildButton(BuildContext context, VoidCallback? onActivate) {
    final palette = context.omniPalette;
    final glass = LiquidGlassScope.of(context);
    final surface = palette.surfacePrimary.withValues(
      alpha: palette.surfacePrimary.a * surfaceOpacity,
    );
    final button = IconButton(
      onPressed: onActivate,
      tooltip: tooltip,
      iconSize: iconSize,
      icon: iconWidget ?? Icon(icon),
      style:
          (glass
                  ? LiquidGlass.buttonStyle(
                      (_) => LiquidGlassStyle.regular(
                        Theme.of(context).colorScheme,
                      ).scale(surfaceOpacity),
                    )
                  : const ButtonStyle())
              .merge(glass ? FlClashElasticPress.buttonStyle : null)
              .merge(
                IconButton.styleFrom(
                  foregroundColor: foregroundColor ?? palette.textPrimary,
                  backgroundColor: surface,
                  disabledBackgroundColor: surface,
                  disabledForegroundColor: palette.textTertiary,
                  fixedSize: Size.square(size),
                  minimumSize: Size.square(size),
                  maximumSize: Size.square(size),
                  padding: EdgeInsets.zero,
                  shape: const CircleBorder(),
                  tapTargetSize: tapTargetSize,
                  splashFactory: surfaceOpacity < 1
                      ? NoSplash.splashFactory
                      : null,
                ),
              ),
    );
    if (glass) return button;
    return Stack(
      alignment: Alignment.center,
      clipBehavior: Clip.none,
      children: [
        IgnorePointer(
          child: SizedBox.square(
            dimension: size,
            child: DecoratedBox(
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                boxShadow: [
                  BoxShadow(
                    color: palette.shadowColor.withValues(
                      alpha: palette.shadowColor.a * surfaceOpacity,
                    ),
                    blurRadius: 18,
                    offset: const Offset(0, 5),
                  ),
                ],
              ),
            ),
          ),
        ),
        button,
      ],
    );
  }
}
