import 'dart:math' as math;
import 'dart:ui' show SemanticsRole;

import 'package:flutter/material.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/glass_popup.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/flclash/menu_interaction.dart';

/// Opens a button's menu on the shared anchored popup surface.
///
/// Entries keep their own selection and dismissal behavior. The viewport caps
/// long menus without fixing row heights, so text can grow with accessibility
/// settings and custom entries can retain their own scrolling content.
Future<T?> showOmniMenu<T>({
  required BuildContext context,
  BuildContext? sourceContext,
  required Rect anchor,
  required List<PopupMenuEntry<T>> items,
  double? width,
  bool preferBelow = true,
  bool? requestFocus,
  EdgeInsetsGeometry padding = OmniMenuStyle.panelPadding,
}) {
  final focusMenu = requestFocus ?? Navigator.of(context).widget.requestFocus;
  // Large text can use the extra space on a tablet instead of keeping the
  // phone's narrow menu column. The popup still clamps to its actual viewport.
  final menuWidth =
      width ??
      (280 * MediaQuery.textScalerOf(context).scale(15) / 15).clamp(
        280.0,
        420.0,
      );
  return showGlassPopup<T>(
    context: context,
    sourceContext: sourceContext,
    anchor: anchor,
    preferBelow: preferBelow,
    requestFocus: requestFocus,
    child: FocusScope(
      autofocus: focusMenu,
      child: FocusTraversalGroup(
        policy: WidgetOrderTraversalPolicy(),
        child: Semantics(
          role: SemanticsRole.menu,
          scopesRoute: true,
          namesRoute: true,
          explicitChildNodes: true,
          label: MaterialLocalizations.of(context).popupMenuLabel,
          child: Material(
            color: Colors.transparent,
            child: ConstrainedBox(
              constraints: BoxConstraints(
                minWidth: math.min(160, menuWidth),
                maxWidth: menuWidth,
                maxHeight:
                    padding.resolve(Directionality.of(context)).vertical +
                    7.5 * (24 + MediaQuery.textScalerOf(context).scale(20)),
              ),
              child: OmniMenuSurface(
                width: width,
                padding: padding,
                child: LayoutBuilder(
                  builder: (context, constraints) => SingleChildScrollView(
                    child: IntrinsicWidth(
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          for (final item in items)
                            ConstrainedBox(
                              constraints: BoxConstraints(
                                maxHeight: constraints.maxHeight,
                              ),
                              child: item,
                            ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  );
}

/// FlClash CommonPopupMenu metrics shared by button and context menus.
abstract final class OmniMenuStyle {
  static const rowRadius = BorderRadius.all(Radius.circular(16));
  static const panelRadius = BorderRadius.all(Radius.circular(24));
  static const contentPadding = EdgeInsets.all(12);
  static const panelPadding = EdgeInsets.all(8);
  static const minHeight = 44.0;
  static const iconSize = 20.0;
  static const iconGap = 12.0;
  static const labelStyle = TextStyle(
    fontSize: 15,
    fontWeight: FontWeight.w400,
    height: 20 / 15,
    letterSpacing: 0,
  );
}

/// A rounded menu row, independent of route, selection, and dismissal ownership.
/// Use [child] for rich rows, or label/leading/trailing for ordinary actions.
class OmniMenuItem extends StatelessWidget {
  const OmniMenuItem({
    super.key,
    required this.onTap,
    this.child,
    this.label,
    this.leading,
    this.trailing,
    this.subtitle,
    this.selected = false,
    this.enabled = true,
    this.destructive = false,
  }) : assert(child != null || label != null);

  final VoidCallback? onTap;
  final Widget? child;
  final String? label;
  final Widget? leading;
  final Widget? trailing;
  final String? subtitle;
  final bool selected;
  final bool enabled;
  final bool destructive;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final theme = Theme.of(context);
    // AppTheme removes global Material press ink. FlClash's menu owns its
    // tracked-row highlight and uses the platform ThemeData defaults instead.
    final highlight = theme.highlightColor.a > 0
        ? theme.highlightColor
        : theme.brightness == Brightness.dark
        ? const Color(0x40CCCCCC)
        : const Color(0x66BCBCBC);
    final color = !enabled
        ? palette.textTertiary
        : destructive
        ? Theme.of(context).colorScheme.error
        : palette.textPrimary;
    final trailingControl =
        trailing is Switch ||
        trailing is Slider ||
        trailing is Checkbox ||
        trailing is IconButton ||
        trailing is ButtonStyleButton;
    return FlClashMenuItemRegion(
      onTap: enabled ? onTap : null,
      builder: (context, pressed) => Semantics(
        selected: selected,
        enabled: enabled,
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            onTap: enabled ? onTap : null,
            customBorder: const RoundedSuperellipseBorder(
              borderRadius: OmniMenuStyle.rowRadius,
            ),
            splashFactory: NoSplash.splashFactory,
            highlightColor: Colors.transparent,
            hoverColor: color.withValues(alpha: 0.04),
            focusColor: color.withValues(alpha: 0.04),
            child: DecoratedBox(
              decoration: ShapeDecoration(
                color: pressed ? highlight : null,
                shape: const RoundedSuperellipseBorder(
                  borderRadius: OmniMenuStyle.rowRadius,
                ),
              ),
              child: ConstrainedBox(
                constraints: const BoxConstraints(
                  minHeight: OmniMenuStyle.minHeight,
                ),
                child: Padding(
                  padding: OmniMenuStyle.contentPadding,
                  child: IconTheme.merge(
                    data: IconThemeData(
                      color: color,
                      size: OmniMenuStyle.iconSize,
                    ),
                    child: DefaultTextStyle.merge(
                      style: OmniMenuStyle.labelStyle.copyWith(color: color),
                      child:
                          child ??
                          Row(
                            children: [
                              if (leading != null) ...[
                                leading!,
                                const SizedBox(width: OmniMenuStyle.iconGap),
                              ],
                              Expanded(
                                child: Column(
                                  mainAxisSize: MainAxisSize.min,
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      label!,
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                    ),
                                    if (subtitle != null) ...[
                                      Text(
                                        subtitle!,
                                        maxLines: 2,
                                        overflow: TextOverflow.ellipsis,
                                        style: TextStyle(
                                          fontSize: 13,
                                          height: 18 / 13,
                                          letterSpacing: 0,
                                          color: palette.textSecondary,
                                        ),
                                      ),
                                    ],
                                  ],
                                ),
                              ),
                              if (trailing != null) ...[
                                const SizedBox(width: 8),
                                trailingControl
                                    ? FlClashMenuInteractionIgnore(
                                        child: trailing!,
                                      )
                                    : trailing!,
                              ],
                            ],
                          ),
                    ),
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

/// Native popup-menu entry with an individually rounded interaction surface.
/// Retains PopupMenuItem's keyboard navigation and pop-before-callback behavior.
class OmniPopupMenuItem<T> extends PopupMenuItem<T> {
  const OmniPopupMenuItem({
    super.key,
    super.value,
    super.onTap,
    super.enabled = true,
    required super.child,
    this.selected = false,
    this.destructive = false,
  }) : super(height: OmniMenuStyle.minHeight, padding: EdgeInsets.zero);

  final bool selected;
  final bool destructive;

  @override
  PopupMenuItemState<T, OmniPopupMenuItem<T>> createState() =>
      _OmniPopupMenuItemState<T>();
}

class _OmniPopupMenuItemState<T>
    extends PopupMenuItemState<T, OmniPopupMenuItem<T>> {
  @override
  void handleTap() {
    if (ModalRoute.of(context)?.isCurrent != true) return;
    super.handleTap();
  }

  @override
  Widget build(BuildContext context) {
    return Semantics(
      role: SemanticsRole.menuItem,
      child: OmniMenuItem(
        enabled: widget.enabled,
        selected: widget.selected,
        destructive: widget.destructive,
        onTap: handleTap,
        child: widget.child ?? const SizedBox.shrink(),
      ),
    );
  }
}

/// Popup content respects its owning surface to avoid duplicate fills or captures.
/// Its owner still controls positioning and animation.
class OmniMenuSurface extends StatelessWidget {
  const OmniMenuSurface({
    super.key,
    required this.child,
    this.padding = OmniMenuStyle.panelPadding,
    this.width,
  });

  final Widget child;
  final EdgeInsetsGeometry padding;
  final double? width;

  @override
  Widget build(BuildContext context) {
    final inheritedSurface = LiquidGlassSurfaceScope.of(context);
    final glass = LiquidGlassScope.of(context);
    const shape = RoundedSuperellipseBorder(
      borderRadius: OmniMenuStyle.panelRadius,
    );
    Widget content = ClipPath(
      clipper: const ShapeBorderClipper(shape: shape),
      child: Padding(padding: padding, child: child),
    );
    if (!inheritedSurface) {
      final shadows = menuShadows(Theme.of(context).colorScheme);
      content = glass
          ? LiquidGlass(
              style: LiquidGlassStyle.menu(Theme.of(context).colorScheme),
              borderRadius: OmniMenuStyle.panelRadius,
              shadows: shadows,
              child: content,
            )
          : DecoratedBox(
              decoration: ShapeDecoration(
                color: context.omniPalette.surfacePrimary,
                shape: shape,
                shadows: shadows,
              ),
              child: content,
            );
    }
    final surface = FlClashMenuPanelRegion(
      child: SizedBox(width: width, child: content),
    );
    return FlClashMenuInteraction.hasAncestor(context)
        ? surface
        : FlClashMenuInteraction(child: surface);
  }
}
