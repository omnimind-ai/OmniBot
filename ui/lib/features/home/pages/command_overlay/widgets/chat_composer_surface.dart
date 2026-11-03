import 'package:flutter/material.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';

/// The composer's material and focus shape, without owning any editing state.
/// A stadium resolves against the laid-out size, including accessibility text
/// scaling. The same editor stays mounted throughout the shape transition.
class ChatComposerSurface extends StatelessWidget {
  const ChatComposerSurface({
    super.key,
    required this.expanded,
    required this.duration,
    required this.child,
  });

  final bool expanded;
  final Duration duration;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final glass = LiquidGlassScope.of(context);
    return TweenAnimationBuilder<double>(
      tween: Tween(end: expanded ? 1 : 0),
      duration: MediaQuery.disableAnimationsOf(context)
          ? Duration.zero
          : duration,
      curve: Curves.easeOutCubic,
      child: child,
      builder: (context, progress, child) {
        final shape = ShapeBorder.lerp(
          const StadiumBorder(),
          const RoundedRectangleBorder(
            borderRadius: BorderRadius.all(Radius.circular(20)),
          ),
          progress,
        )!;
        if (glass) {
          return LiquidGlass(
            style: LiquidGlassStyle.regular(Theme.of(context).colorScheme),
            // Resolve against layout without profiling a wide stadium path:
            // the glass shader has an exact rounded-rectangle branch.
            borderRadiusResolver: (size) => BorderRadius.circular(
              size.shortestSide / 2 * (1 - progress) + 20 * progress,
            ),
            child: child,
          );
        }
        return DecoratedBox(
          decoration: ShapeDecoration(
            color: palette.surfacePrimary,
            shape: shape,
            shadows: [
              BoxShadow(
                color: palette.shadowColor.withValues(
                  alpha: context.isDarkTheme ? 0.18 : 0.07,
                ),
                blurRadius: 20,
                offset: const Offset(0, 6),
              ),
            ],
          ),
          child: child,
        );
      },
    );
  }
}
