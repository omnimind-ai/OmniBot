import 'package:flutter/material.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/omni_glass_routes.dart';

export 'package:ui/widgets/omni_glass_routes.dart';

/// Standalone confirmations, forms and detail cards share one bottom sheet.
/// Small cards occupy half the view; longer forms can use up to 92% of the
/// available height. The host alone owns keyboard insets and the glass pane.
Future<T?> showOmniGlassCardSheet<T>({
  required BuildContext context,
  required WidgetBuilder builder,
  bool barrierDismissible = true,
  Color? barrierColor,
  String? barrierLabel,
  bool useSafeArea = true,
  bool useRootNavigator = true,
  RouteSettings? routeSettings,
  Offset? anchorPoint,
  bool? requestFocus,
  AnimationStyle? animationStyle,
  bool forceDark = false,
  bool fitsContent = false,
  bool showDragHandle = true,
}) => showOmniGlassBottomSheet<T>(
  context: context,
  backgroundColor: Colors.transparent,
  elevation: 0,
  constraints: const BoxConstraints(maxWidth: 640),
  isScrollControlled: true,
  isDismissible: barrierDismissible,
  enableDrag: barrierDismissible,
  respectPopScope: true,
  showDragHandle: false,
  useSafeArea: useSafeArea,
  useRootNavigator: useRootNavigator,
  barrierColor: barrierColor,
  barrierLabel: barrierLabel,
  routeSettings: routeSettings,
  anchorPoint: anchorPoint,
  requestFocus: requestFocus,
  sheetAnimationStyle: MediaQuery.disableAnimationsOf(context)
      ? AnimationStyle.noAnimation
      : animationStyle,
  builder: (context) => _OmniGlassCardSheet(
    dismissible: barrierDismissible,
    forceDark: forceDark,
    fitsContent: fitsContent,
    showDragHandle: showDragHandle,
    child: Builder(builder: builder),
  ),
);

class _OmniGlassCardScope extends InheritedWidget {
  const _OmniGlassCardScope({required super.child});

  static bool of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<_OmniGlassCardScope>() != null;

  @override
  bool updateShouldNotify(_OmniGlassCardScope oldWidget) => false;
}

class _OmniGlassCardSheet extends StatelessWidget {
  const _OmniGlassCardSheet({
    required this.dismissible,
    required this.forceDark,
    required this.fitsContent,
    required this.showDragHandle,
    required this.child,
  });

  final bool dismissible;
  final bool forceDark;
  final bool fitsContent;
  final bool showDragHandle;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final media = MediaQuery.of(context);
    final palette = forceDark ? OmniThemePalette.dark : context.omniPalette;
    return AnimatedPadding(
      padding: EdgeInsets.only(bottom: media.viewInsets.bottom),
      duration: media.disableAnimations
          ? Duration.zero
          : const Duration(milliseconds: 200),
      curve: Curves.easeOutCubic,
      child: MediaQuery.removeViewInsets(
        context: context,
        removeBottom: true,
        child: LayoutBuilder(
          builder: (context, constraints) {
            final maxHeight = constraints.maxHeight * 0.92;
            final minHeight = fitsContent
                ? 0.0
                : (media.size.height * 0.5).clamp(0.0, maxHeight);
            return OmniGlassPanel(
              forceDark: forceDark,
              borderRadius: const BorderRadius.vertical(
                top: Radius.circular(24),
              ),
              child: Material(
                type: MaterialType.transparency,
                child: _OmniGlassCardScope(
                  child: FocusTraversalGroup(
                    child: SafeArea(
                      top: false,
                      child: ConstrainedBox(
                        constraints: BoxConstraints(
                          minHeight: minHeight,
                          maxHeight: maxHeight,
                        ),
                        child: SizedBox(
                          width: double.infinity,
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              if (showDragHandle)
                                Semantics(
                                  label: MaterialLocalizations.of(context)
                                      .modalBarrierDismissLabel,
                                  button: dismissible,
                                  onTap: dismissible
                                      ? () => Navigator.of(context).maybePop()
                                      : null,
                                  child: SizedBox(
                                    height: kMinInteractiveDimension,
                                    width: double.infinity,
                                    child: Center(
                                      child: Container(
                                        width: 32,
                                        height: 4,
                                        decoration: BoxDecoration(
                                          color: palette.borderStrong,
                                          borderRadius: BorderRadius.circular(
                                            99,
                                          ),
                                        ),
                                      ),
                                    ),
                                  ),
                                ),
                              Flexible(child: child),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}

/// Content-only adapter: the card route owns the surface, width and insets.
/// Keep scrolling and pinned actions in the body so form state survives IME
/// resizing and long detail content remains reachable.
class OmniGlassCardBody extends StatelessWidget {
  const OmniGlassCardBody({
    super.key,
    required this.child,
    this.padding = EdgeInsets.zero,
    this.constraints,
  });

  final Widget child;
  final EdgeInsetsGeometry padding;
  final BoxConstraints? constraints;

  @override
  Widget build(BuildContext context) => Container(
    width: double.infinity,
    padding: padding,
    constraints: constraints,
    child: child,
  );
}

/// Presents content whose surface used to be painted by the Material route.
/// The handle belongs inside the same pane so it never floats above the glass.
Future<T?> showOmniGlassModalBottomSheet<T>({
  required BuildContext context,
  required WidgetBuilder builder,
  Color? backgroundColor,
  Color? barrierColor,
  BorderRadius borderRadius = const BorderRadius.vertical(
    top: Radius.circular(24),
  ),
  bool isScrollControlled = false,
  bool isDismissible = true,
  bool enableDrag = true,
  bool showDragHandle = false,
  bool useRootNavigator = false,
  bool useSafeArea = false,
  bool? requestFocus,
}) => showOmniGlassBottomSheet<T>(
  context: context,
  backgroundColor: Colors.transparent,
  barrierColor: barrierColor,
  elevation: 0,
  showDragHandle: false,
  isScrollControlled: isScrollControlled,
  isDismissible: isDismissible,
  enableDrag: enableDrag,
  useRootNavigator: useRootNavigator,
  useSafeArea: useSafeArea,
  requestFocus: requestFocus,
  builder: (context) => OmniGlassSheet(
    backgroundColor: backgroundColor,
    borderRadius: borderRadius,
    child: showDragHandle
        ? Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Semantics(
                label: MaterialLocalizations.of(context)
                    .modalBarrierDismissLabel,
                container: true,
                button: true,
                onTap: isDismissible
                    ? () => Navigator.of(context).maybePop()
                    : null,
                child: SizedBox(
                  height: kMinInteractiveDimension,
                  child: Center(
                    child: Container(
                      width: 32,
                      height: 4,
                      decoration: BoxDecoration(
                        color: context.omniPalette.borderStrong,
                        borderRadius: BorderRadius.circular(99),
                      ),
                    ),
                  ),
                ),
              ),
              Flexible(child: Builder(builder: builder)),
            ],
          )
        : Builder(builder: builder),
  ),
);

/// Shared dialog surface using the app's selected glass quality.
class OmniGlassPanel extends StatelessWidget {
  const OmniGlassPanel({
    super.key,
    required this.child,
    this.borderRadius = const BorderRadius.all(Radius.circular(20)),
    this.padding = EdgeInsets.zero,
    this.width,
    this.height,
    this.forceDark = false,
  });

  final Widget child;
  final BorderRadius borderRadius;
  final EdgeInsetsGeometry padding;
  final double? width;
  final double? height;
  final bool forceDark;

  @override
  Widget build(BuildContext context) {
    final content = LiquidGlassSurfaceScope(
      enabled: true,
      child: ClipRRect(
        borderRadius: borderRadius,
        child: Padding(padding: padding, child: child),
      ),
    );
    if (LiquidGlassSurfaceScope.of(context)) {
      return SizedBox(width: width, height: height, child: content);
    }
    final palette = forceDark ? OmniThemePalette.dark : context.omniPalette;
    final isDark = forceDark || context.isDarkTheme;
    if (LiquidGlassScope.of(context)) {
      final scheme = Theme.of(context).colorScheme;
      final resolvedScheme = forceDark
          ? scheme.copyWith(brightness: Brightness.dark)
          : scheme;
      return SizedBox(
        width: width,
        height: height,
        child: LiquidGlass(
          style: LiquidGlassStyle.alert(resolvedScheme),
          borderRadius: borderRadius,
          rim: LiquidGlassRim.alert(resolvedScheme),
          shadows: menuShadows(resolvedScheme),
          child: content,
        ),
      );
    }
    return Container(
      width: width,
      height: height,
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: borderRadius,
        border: Border.all(color: palette.borderSubtle, width: 0.5),
        boxShadow: [
          BoxShadow(
            color: palette.shadowColor.withValues(alpha: isDark ? 0.22 : 0.08),
            blurRadius: 20,
            offset: const Offset(0, 6),
          ),
        ],
      ),
      child: content,
    );
  }
}

/// A bottom sheet's single surface. Route backgrounds must be transparent so
/// the pane can sample the page beneath it. Insets and scrolling stay with the
/// caller, outside or inside this surface as appropriate for its layout.
class OmniGlassSheet extends StatelessWidget {
  const OmniGlassSheet({
    super.key,
    required this.child,
    this.borderRadius = const BorderRadius.vertical(top: Radius.circular(24)),
    this.backgroundColor,
  });

  final Widget child;
  final BorderRadius borderRadius;
  final Color? backgroundColor;

  @override
  Widget build(BuildContext context) {
    if (LiquidGlassScope.of(context) || LiquidGlassSurfaceScope.of(context)) {
      return OmniGlassPanel(
        borderRadius: borderRadius,
        child: Material(type: MaterialType.transparency, child: child),
      );
    }
    return Material(
      color: backgroundColor ?? context.omniPalette.surfacePrimary,
      borderRadius: borderRadius,
      clipBehavior: Clip.antiAlias,
      child: LiquidGlassSurfaceScope(enabled: true, child: child),
    );
  }
}

/// Material's alert content and actions inside the shared card sheet.
///
/// AlertDialog remains the owner of intrinsic sizing, content scrolling,
/// action overflow, localization and semantics. Only its final public Dialog
/// surface is removed in a card sheet; its host handles keyboard insets once.
class OmniGlassAlertDialog extends AlertDialog {
  const OmniGlassAlertDialog({
    super.key,
    super.icon,
    super.iconPadding,
    super.iconColor,
    super.title,
    super.titlePadding,
    super.titleTextStyle,
    super.content,
    super.contentPadding,
    super.contentTextStyle,
    super.actions,
    super.actionsPadding,
    super.actionsAlignment,
    super.actionsOverflowAlignment,
    super.actionsOverflowDirection,
    super.actionsOverflowButtonSpacing,
    super.buttonPadding,
    super.backgroundColor,
    super.elevation,
    super.shadowColor,
    super.surfaceTintColor,
    super.semanticLabel,
    super.insetPadding,
    super.clipBehavior,
    super.shape,
    super.alignment,
    super.constraints,
    super.scrollable,
  });

  @override
  Widget build(BuildContext context) {
    final dialog = super.build(context);
    if (_OmniGlassCardScope.of(context) && dialog is Dialog) {
      return Semantics(
        role: dialog.semanticsRole,
        child: SizedBox(
          width: double.infinity,
          child: dialog.child ?? const SizedBox.shrink(),
        ),
      );
    }
    // A future Flutter layout can still render normally if it stops returning
    // a Dialog; never cast or duplicate the framework's private alert layout.
    if (!LiquidGlassScope.of(context) || dialog is! Dialog) return dialog;
    final resolvedShape = shape ?? DialogTheme.of(context).shape;
    final radius = resolvedShape is RoundedRectangleBorder
        ? resolvedShape.borderRadius.resolve(Directionality.of(context))
        : BorderRadius.circular(20);
    return Dialog(
      backgroundColor: Colors.transparent,
      elevation: 0,
      shadowColor: Colors.transparent,
      surfaceTintColor: Colors.transparent,
      insetAnimationDuration: dialog.insetAnimationDuration,
      insetAnimationCurve: dialog.insetAnimationCurve,
      insetPadding: dialog.insetPadding,
      clipBehavior: Clip.none,
      shape: RoundedRectangleBorder(borderRadius: radius),
      alignment: dialog.alignment,
      constraints: dialog.constraints,
      semanticsRole: dialog.semanticsRole,
      child: OmniGlassPanel(
        borderRadius: radius,
        child: dialog.child ?? const SizedBox.shrink(),
      ),
    );
  }
}
