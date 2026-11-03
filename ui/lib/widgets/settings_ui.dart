import 'package:ui/widgets/flclash/popup.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/widgets/flclash/common_switch.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/glass_popup.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/omni_menu.dart';

/// Triggers a crisp toggle haptic feedback.
void performOmniSwitchHaptic(bool nextValue) {
  if (nextValue) {
    HapticFeedback.lightImpact();
  } else {
    HapticFeedback.selectionClick();
  }
}

/// Triggers a subtle selection tick haptic feedback for menus and segments.
void performOmniSelectionHaptic() {
  HapticFeedback.selectionClick();
}

/// Unified surface decoration used across settings groups and cards.
///
/// Uses a plain rounded surface over the page background. Borders are opt-in;
/// fine inset dividers provide separation inside a group.
BoxDecoration omniSettingsSurfaceDecoration(
  BuildContext context, {
  Color? color,
  Color? borderColor,
  double borderRadius = 22,
}) {
  final palette = context.omniPalette;
  return BoxDecoration(
    color: color ?? palette.surfacePrimary,
    borderRadius: BorderRadius.circular(borderRadius),
    border: borderColor == null
        ? null
        : Border.all(color: borderColor, width: 0.75),
  );
}

/// Unified surface shape used across settings groups and cards.
ShapeBorder omniSettingsSurfaceShape(
  BuildContext context, {
  Color? borderColor,
  double borderRadius = 22,
}) {
  return RoundedRectangleBorder(
    borderRadius: BorderRadius.circular(borderRadius),
    side: borderColor == null
        ? BorderSide.none
        : BorderSide(color: borderColor, width: 0.75),
  );
}

/// A rounded, shadowless surface card for composite settings content
/// (forms, sliders, status banners, etc.).
class OmniSettingsCard extends StatelessWidget {
  const OmniSettingsCard({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.all(16),
    this.margin,
    this.color,
    this.borderColor,
    this.borderRadius = 22,
    this.clipBehavior = Clip.antiAlias,
  });

  final Widget child;
  final EdgeInsetsGeometry padding;
  final EdgeInsetsGeometry? margin;
  final Color? color;
  final Color? borderColor;
  final double borderRadius;
  final Clip clipBehavior;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    Widget card = Material(
      color: color ?? palette.surfacePrimary,
      shape: omniSettingsSurfaceShape(
        context,
        borderColor: borderColor,
        borderRadius: borderRadius,
      ),
      clipBehavior: clipBehavior,
      child: SizedBox(
        width: double.infinity,
        child: Padding(padding: padding, child: child),
      ),
    );
    if (margin != null) {
      card = Padding(padding: margin!, child: card);
    }
    return card;
  }
}

/// An inset grouped container that renders a vertical list of setting rows
/// inside a single rounded surface with subtle hairline dividers.
class OmniSettingsGroup extends StatelessWidget {
  const OmniSettingsGroup({
    super.key,
    required this.children,
    this.title,
    this.subtitle,
    this.headerTrailing,
    this.footer,
    this.dividerIndent = 54,
    this.dividerEndIndent = 0,
    this.borderRadius = 22,
    this.translateTitle = true,
  });

  final List<Widget> children;
  final String? title;
  final String? subtitle;
  final Widget? headerTrailing;
  final Widget? footer;
  final double dividerIndent;
  final double dividerEndIndent;
  final double borderRadius;
  final bool translateTitle;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final dividerColor = palette.borderSubtle.withValues(
      alpha: isDark ? 0.55 : 0.75,
    );

    final surface = Material(
      color: palette.surfacePrimary,
      shape: omniSettingsSurfaceShape(context, borderRadius: borderRadius),
      clipBehavior: Clip.antiAlias,
      child: SizedBox(
        width: double.infinity,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (var i = 0; i < children.length; i++) ...[
              children[i],
              if (i < children.length - 1)
                Divider(
                  height: 1,
                  thickness: 0.65,
                  indent: dividerIndent,
                  endIndent: dividerEndIndent,
                  color: dividerColor,
                ),
            ],
          ],
        ),
      ),
    );

    if (title == null && subtitle == null && footer == null) {
      return surface;
    }

    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (title != null || subtitle != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(18, 0, 18, 16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (title != null)
                  headerTrailing == null
                      ? Text(
                          translateTitle ? context.trLegacy(title!) : title!,
                          style: TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.w400,
                            color: palette.textTertiary,
                            height: 1.35,
                          ),
                        )
                      : Row(
                          children: [
                            Expanded(
                              child: Text(
                                translateTitle
                                    ? context.trLegacy(title!)
                                    : title!,
                                style: TextStyle(
                                  fontSize: 14,
                                  fontWeight: FontWeight.w400,
                                  color: palette.textTertiary,
                                  height: 1.35,
                                ),
                              ),
                            ),
                            headerTrailing!,
                          ],
                        ),
                if (subtitle != null) ...[
                  const SizedBox(height: 4),
                  Text(
                    translateTitle ? context.trLegacy(subtitle!) : subtitle!,
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w400,
                      color: palette.textSecondary,
                      height: 1.45,
                    ),
                  ),
                ],
              ],
            ),
          ),
        surface,
        if (footer != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(6, 8, 6, 0),
            child: DefaultTextStyle(
              style: TextStyle(
                fontSize: 12,
                color: palette.textTertiary,
                height: 1.45,
              ),
              child: footer!,
            ),
          ),
      ],
    );
  }
}

/// A single row inside an [OmniSettingsGroup] or standalone settings list.
class OmniSettingsTile extends StatelessWidget {
  const OmniSettingsTile({
    super.key,
    this.tileKey,
    this.icon,
    this.iconColor,
    this.leading,
    required this.title,
    this.subtitle,
    this.trailingText,
    this.trailingTextColor,
    this.trailingStatusDotColor,
    this.trailing,
    this.onTap,
    this.showChevron,
    this.bottomNote,
    this.translateText = true,
    this.isDestructive = false,
    this.padding = const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
  });

  final Key? tileKey;
  final IconData? icon;
  final Color? iconColor;
  final Widget? leading;
  final String title;
  final String? subtitle;
  final String? trailingText;
  final Color? trailingTextColor;
  final Color? trailingStatusDotColor;
  final Widget? trailing;
  final VoidCallback? onTap;
  final bool? showChevron;
  final String? bottomNote;
  final bool translateText;
  final bool isDestructive;
  final EdgeInsetsGeometry padding;

  void _triggerDescendantSwitch(BuildContext context) {
    _OmniSwitchTapTarget? target;
    void visit(Element element) {
      if (target != null) return;
      if (element is StatefulElement && element.state is _OmniSwitchTapTarget) {
        target = element.state as _OmniSwitchTapTarget;
        return;
      }
      element.visitChildren(visit);
    }

    context.visitChildElements(visit);
    if (target?.canToggle ?? false) target!.triggerToggle();
  }

  void _triggerDescendantDropdown(BuildContext context) {
    _OmniDropdownTapTarget? target;
    void visit(Element element) {
      if (target != null) return;
      if (element is StatefulElement &&
          element.state is _OmniDropdownTapTarget) {
        target = element.state as _OmniDropdownTapTarget;
        return;
      }
      element.visitChildren(visit);
    }

    context.visitChildElements(visit);
    if (target != null && target!.canOpenPopover) {
      target!.openPopover();
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final effectiveTitleColor = isDestructive
        ? const Color(0xFFE05252)
        : palette.textPrimary;
    final effectiveIconColor =
        iconColor ??
        (isDestructive ? const Color(0xFFE05252) : palette.textPrimary);
    final hasLeading = leading != null || icon != null;
    final effectiveShowChevron =
        showChevron ?? (onTap != null && trailing == null);
    final effectiveOnTap = onTap != null
        ? () {
            if (trailing is OmniSettingsSwitch) {
              performOmniSelectionHaptic();
            }
            onTap!();
          }
        : (trailing is OmniSettingsSwitch
              ? (trailing as OmniSettingsSwitch).enabled
                    ? () => _triggerDescendantSwitch(context)
                    : null
              : trailing is OmniSettingsDropdown
              ? () => _triggerDescendantDropdown(context)
              : null);

    return Material(
      color: Colors.transparent,
      child: InkWell(
        key: tileKey,
        onTap: effectiveOnTap,
        splashColor: palette.accentPrimary.withValues(alpha: 0.06),
        highlightColor: palette.accentPrimary.withValues(alpha: 0.03),
        child: Padding(
          padding: padding,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  if (hasLeading) ...[
                    SizedBox(
                      width: 22,
                      height: 24,
                      child: Center(
                        child:
                            leading ??
                            Icon(icon, size: 20, color: effectiveIconColor),
                      ),
                    ),
                    const SizedBox(width: 14),
                  ],
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          translateText ? context.trLegacy(title) : title,
                          style: TextStyle(
                            fontSize: 15,
                            fontWeight: FontWeight.w400,
                            color: effectiveTitleColor,
                            height: 1.38,
                          ),
                        ),
                        if (subtitle != null &&
                            subtitle!.trim().isNotEmpty) ...[
                          const SizedBox(height: 2.5),
                          Text(
                            translateText
                                ? context.trLegacy(subtitle!)
                                : subtitle!,
                            style: TextStyle(
                              color: palette.textSecondary,
                              fontSize: 12,
                              fontWeight: FontWeight.w400,
                              height: 1.45,
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                  if (trailing != null) ...[
                    const SizedBox(width: 12),
                    trailing!,
                  ] else if (trailingText != null || effectiveShowChevron) ...[
                    const SizedBox(width: 10),
                    Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        if (trailingStatusDotColor != null) ...[
                          Container(
                            width: 6,
                            height: 6,
                            decoration: BoxDecoration(
                              color: trailingStatusDotColor,
                              shape: BoxShape.circle,
                            ),
                          ),
                          const SizedBox(width: 6),
                        ],
                        if (trailingText != null)
                          ConstrainedBox(
                            constraints: const BoxConstraints(maxWidth: 150),
                            child: Text(
                              translateText
                                  ? context.trLegacy(trailingText!)
                                  : trailingText!,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                fontSize: 14,
                                fontWeight: FontWeight.w400,
                                color:
                                    trailingTextColor ?? palette.textSecondary,
                              ),
                            ),
                          ),
                        if (trailingText != null && effectiveShowChevron)
                          const SizedBox(width: 4),
                        if (effectiveShowChevron)
                          Icon(
                            LucideIcons.chevronRight,
                            size: 18,
                            color: palette.textTertiary,
                          ),
                      ],
                    ),
                  ],
                ],
              ),
              if (bottomNote != null && bottomNote!.trim().isNotEmpty) ...[
                Padding(
                  padding: EdgeInsets.only(top: 6, left: hasLeading ? 36 : 0),
                  child: Text(
                    translateText ? context.trLegacy(bottomNote!) : bottomNote!,
                    style: TextStyle(
                      fontSize: 11.5,
                      color: palette.textTertiary,
                      height: 1.4,
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

abstract interface class _OmniSwitchTapTarget {
  bool get canToggle;
  void triggerToggle();
}

/// Settings adapter for the shared FlClash solid and liquid-glass switch.
class OmniSettingsSwitch extends StatefulWidget {
  const OmniSettingsSwitch({
    super.key,
    required this.value,
    required this.onToggle,
    this.enabled = true,
    this.duration = const Duration(milliseconds: 300),
  });

  final bool value;
  final ValueChanged<bool> onToggle;
  final bool enabled;
  final Duration duration;

  @override
  State<OmniSettingsSwitch> createState() => _OmniSettingsSwitchState();
}

class _OmniSettingsSwitchState extends State<OmniSettingsSwitch>
    implements _OmniSwitchTapTarget {
  @override
  bool get canToggle => widget.enabled;

  void _toggle(bool next) {
    if (!canToggle) return;
    performOmniSwitchHaptic(next);
    widget.onToggle(next);
  }

  @override
  void triggerToggle() => _toggle(!widget.value);

  @override
  Widget build(BuildContext context) => CommonSwitch(
    value: widget.value,
    onChanged: widget.enabled ? _toggle : null,
    duration: widget.duration,
  );
}

/// Minimalist status indicator: a 6px colored dot followed by a 12sp label.
class OmniSettingsStatusBadge extends StatelessWidget {
  const OmniSettingsStatusBadge({
    super.key,
    required this.label,
    required this.color,
    this.fontSize = 12,
  });

  final String label;
  final Color color;
  final double fontSize;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 6,
          height: 6,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
        const SizedBox(width: 6),
        Text(
          label,
          style: TextStyle(
            fontSize: fontSize,
            fontWeight: FontWeight.w500,
            color: color,
            height: 1.2,
          ),
        ),
      ],
    );
  }
}

/// Option model for [OmniSettingsDropdown].
@immutable
class OmniSettingsOption<T> {
  const OmniSettingsOption({
    required this.value,
    required this.label,
    this.leading,
    this.trailingLabel,
    this.subtitle,
    this.searchText,
  });

  final T value;
  final String label;
  final Widget? leading;
  final String? trailingLabel;
  final String? subtitle;
  final String? searchText;
}

abstract interface class _OmniDropdownTapTarget {
  bool get canOpenPopover;
  void openPopover();
}

/// Refined, pill-styled selector trigger that opens an anchored floating
/// popover menu with inset rounded highlight pills and a checkmark indicator.
class OmniSettingsDropdown<T> extends StatefulWidget {
  const OmniSettingsDropdown({
    super.key,
    this.dropdownKey,
    this.hint,
    this.selectedLabel,
    this.onBeforeOpen,
    this.searchHint,
    this.decoration,
    this.style,
    required this.value,
    required this.options,
    required this.onChanged,
    this.maxWidth = 156,
    this.menuMinWidth = 176,
    this.menuMaxWidth = 264,
  });

  final Key? dropdownKey;
  final String? hint;
  final String? selectedLabel;

  /// Lets the owning page refresh its options before opening the selector.
  final Future<void> Function()? onBeforeOpen;

  /// Enables an optional search field without changing ordinary selectors.
  final String? searchHint;

  /// Optional form-field presentation; the popup and selection owner stay the
  /// same. Omit this to retain the compact settings trigger.
  final InputDecoration? decoration;
  final TextStyle? style;
  final T value;
  final List<OmniSettingsOption<T>> options;
  final ValueChanged<T?>? onChanged;
  final double maxWidth;
  final double menuMinWidth;
  final double menuMaxWidth;

  @override
  State<OmniSettingsDropdown<T>> createState() =>
      _OmniSettingsDropdownState<T>();
}

class _OmniSettingsDropdownState<T> extends State<OmniSettingsDropdown<T>>
    implements _OmniDropdownTapTarget {
  final _sourceKey = GlobalKey();
  final _fieldContentKey = GlobalKey();
  bool _fieldFocused = false;
  bool _opening = false;

  @override
  bool get canOpenPopover =>
      !_opening &&
      widget.onChanged != null &&
      (widget.options.isNotEmpty ||
          widget.onBeforeOpen != null ||
          widget.searchHint != null);

  @override
  void openPopover() {
    final anchorContext = _sourceKey.currentContext;
    if (anchorContext != null) _openPopover(anchorContext);
  }

  Future<void> _openPopover(BuildContext anchorContext) async {
    if (!canOpenPopover) return;
    final sourceRoute = ModalRoute.of(anchorContext);
    bool sourceIsCurrent() =>
        mounted &&
        anchorContext.mounted &&
        (sourceRoute == null ||
            (sourceRoute.isActive && sourceRoute.isCurrent));
    _opening = true;
    try {
      final beforeOpen = widget.onBeforeOpen;
      if (beforeOpen != null) {
        await beforeOpen();
        if (!sourceIsCurrent()) return;
        // A caller may have setState in the async callback. Wait for that
        // rebuild so this route receives the refreshed options and callback.
        await WidgetsBinding.instance.endOfFrame;
      }
      if (!mounted ||
          !anchorContext.mounted ||
          !sourceIsCurrent() ||
          widget.onChanged == null ||
          (widget.options.isEmpty && widget.searchHint == null)) {
        return;
      }
      await _showPopover(anchorContext);
    } finally {
      _opening = false;
    }
  }

  Future<void> _showPopover(BuildContext anchorContext) async {
    performOmniSelectionHaptic();

    final anchor = glassPopupAnchorFromContext(anchorContext);
    if (anchor == null) return;

    final selected = await showGlassPopup<T>(
      context: anchorContext,
      sourceContext: anchorContext,
      anchor: anchor,
      screenPadding: const EdgeInsets.all(16),
      child: LayoutBuilder(
        builder: (popupContext, constraints) {
          // The shared popup owns live safe-area/keyboard positioning. Keep
          // the menu as wide as its trigger where possible, without exceeding
          // either the configured limit or the space currently available.
          final maxWidth = widget.menuMaxWidth.clamp(0.0, constraints.maxWidth);
          final minWidth = anchor.width.clamp(
            widget.menuMinWidth.clamp(0.0, maxWidth),
            maxWidth,
          );
          return _OmniSettingsPopoverCard<T>(
            options: widget.options,
            selectedValue: widget.value,
            searchHint: widget.searchHint,
            minWidth: minWidth,
            maxWidth: maxWidth,
            maxHeight: constraints.maxHeight,
            onSelected: (picked) => Navigator.of(popupContext).pop(picked),
          );
        },
      ),
    );

    if (!mounted ||
        widget.onChanged == null ||
        selected == null ||
        !widget.options.any((option) => option.value == selected)) {
      return;
    }
    if (selected != widget.value) {
      performOmniSwitchHaptic(true);
      widget.onChanged!(selected);
    }
  }

  Rect? _fieldCaptureRect(BuildContext sourceContext) =>
      glassPopupInputContainerRect(
        sourceContext,
        _fieldContentKey.currentContext,
      );

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final decoration = widget.decoration?.applyDefaults(
      Theme.of(context).inputDecorationTheme,
    );
    final border = decoration == null
        ? null
        : widget.onChanged == null
        ? decoration.disabledBorder ?? decoration.border
        : _fieldFocused
        ? decoration.focusedBorder ?? decoration.border
        : decoration.enabledBorder ?? decoration.border;
    return FlClashPopupSource(
      glass: false,
      shape: decoration == null
          ? const RoundedRectangleBorder(
              borderRadius: OmniMenuStyle.panelRadius,
            )
          : border ??
                RoundedRectangleBorder(borderRadius: BorderRadius.circular(4)),
      color: decoration == null
          ? palette.surfaceSecondary.withValues(
              alpha: context.isDarkTheme ? 0.72 : 0.8,
            )
          : decoration.filled == true
          ? decoration.fillColor ?? Colors.transparent
          : Colors.transparent,
      captureRect: decoration == null ? null : _fieldCaptureRect,
      captureOverflow: decoration == null
          ? null
          : (sourceContext) => glassPopupInputLabelRects(
              sourceContext,
              _fieldContentKey.currentContext,
            ),
      builder: (_) => Builder(key: _sourceKey, builder: _buildControl),
    );
  }

  Widget _buildControl(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final enabled = widget.onChanged != null;
    final selectedOption = widget.options
        .where((item) => item.value == widget.value)
        .firstOrNull;
    final selectedLabel =
        widget.selectedLabel ??
        selectedOption?.label ??
        widget.hint ??
        widget.options.firstOrNull?.label ??
        '';

    final decoration = widget.decoration;
    if (decoration != null) {
      final fieldEnabled =
          enabled &&
          (widget.options.isNotEmpty ||
              widget.onBeforeOpen != null ||
              widget.searchHint != null);
      final label =
          widget.selectedLabel ??
          selectedOption?.label ??
          widget.hint ??
          decoration.hintText ??
          '';
      final border = decoration.border;
      return Shortcuts(
        shortcuts: const {
          SingleActivator(LogicalKeyboardKey.arrowDown): ActivateIntent(),
        },
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            key: widget.dropdownKey,
            onTap: fieldEnabled ? () => _openPopover(context) : null,
            onFocusChange: (focused) {
              if (_fieldFocused != focused) {
                setState(() => _fieldFocused = focused);
              }
            },
            borderRadius: border is OutlineInputBorder
                ? border.borderRadius
                : BorderRadius.circular(4),
            child: InputDecorator(
              decoration: decoration.copyWith(
                enabled: fieldEnabled,
                hintText: '',
              ),
              isEmpty: label.isEmpty,
              isFocused: _fieldFocused,
              child: Builder(
                key: _fieldContentKey,
                builder: (context) => Row(
                  children: [
                    Expanded(
                      child: Text(
                        label,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style:
                            (widget.style ??
                                    Theme.of(context).textTheme.titleMedium)
                                ?.copyWith(
                                  color: fieldEnabled
                                      ? selectedOption == null
                                            ? palette.textTertiary
                                            : widget.style?.color ??
                                                  palette.textPrimary
                                      : Theme.of(context).disabledColor,
                                ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Icon(
                      Icons.arrow_drop_down,
                      color: fieldEnabled
                          ? palette.textSecondary
                          : Theme.of(context).disabledColor,
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      );
    }

    return Opacity(
      opacity: enabled ? 1.0 : 0.5,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          key: widget.dropdownKey,
          borderRadius: OmniMenuStyle.panelRadius,
          onTap: enabled ? () => _openPopover(context) : null,
          child: Container(
            constraints: BoxConstraints(
              minHeight: OmniMenuStyle.panelRadius.topLeft.x * 2,
              maxWidth: widget.maxWidth,
            ),
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            decoration: BoxDecoration(
              color: palette.surfaceSecondary.withValues(
                alpha: isDark ? 0.72 : 0.8,
              ),
              borderRadius: OmniMenuStyle.panelRadius,
              border: Border.all(
                color: palette.borderSubtle.withValues(
                  alpha: isDark ? 0.55 : 0.75,
                ),
                width: 0.65,
              ),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Flexible(
                  child: Text(
                    selectedLabel,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: palette.textPrimary,
                      fontSize: 12.5,
                      fontWeight: FontWeight.w500,
                      height: 1.25,
                    ),
                  ),
                ),
                const SizedBox(width: 4),
                Icon(
                  LucideIcons.chevronsUpDown,
                  size: 13.5,
                  color: palette.textTertiary,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _OmniSettingsPopoverCard<T> extends StatefulWidget {
  const _OmniSettingsPopoverCard({
    required this.options,
    required this.selectedValue,
    required this.minWidth,
    required this.maxWidth,
    required this.maxHeight,
    required this.onSelected,
    this.searchHint,
  });

  final List<OmniSettingsOption<T>> options;
  final T selectedValue;
  final double minWidth;
  final double maxWidth;
  final double maxHeight;
  final ValueChanged<T> onSelected;
  final String? searchHint;

  @override
  State<_OmniSettingsPopoverCard<T>> createState() =>
      _OmniSettingsPopoverCardState<T>();
}

class _OmniSettingsPopoverCardState<T>
    extends State<_OmniSettingsPopoverCard<T>> {
  final TextEditingController _searchController = TextEditingController();
  final FocusNode _searchFocusNode = FocusNode();

  @override
  void dispose() {
    _searchController.dispose();
    _searchFocusNode.dispose();
    super.dispose();
  }

  Widget _buildOption(OmniSettingsOption<T> option) {
    return OmniMenuItem(
      onTap: () => widget.onSelected(option.value),
      label: option.label,
      leading: option.leading,
      subtitle: option.subtitle?.trim().isNotEmpty == true
          ? option.subtitle
          : null,
      selected: option.value == widget.selectedValue,
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (option.trailingLabel?.trim().isNotEmpty == true) ...[
            ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 88),
              child: Text(
                option.trailingLabel!,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 12,
                  color: context.omniPalette.textTertiary,
                ),
              ),
            ),
            const SizedBox(width: 8),
          ],
          SizedBox.square(
            dimension: OmniMenuStyle.iconSize,
            child: option.value == widget.selectedValue
                ? const Icon(LucideIcons.check)
                : null,
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final query = _searchController.text.trim().toLowerCase();
    final options = widget.options.where(
      (option) =>
          query.isEmpty ||
          option.label.toLowerCase().contains(query) ||
          (option.trailingLabel?.toLowerCase().contains(query) ?? false) ||
          (option.subtitle?.toLowerCase().contains(query) ?? false) ||
          (option.searchText?.toLowerCase().contains(query) ?? false),
    );
    return Material(
      color: Colors.transparent,
      child: ConstrainedBox(
        constraints: BoxConstraints(
          minWidth: widget.minWidth,
          maxWidth: widget.maxWidth,
          maxHeight: widget.maxHeight,
        ),
        child: OmniMenuSurface(
          padding: EdgeInsets.zero,
          child: widget.searchHint != null
              ? SizedBox(
                  width: widget.maxWidth,
                  child: SingleChildScrollView(
                    padding: OmniMenuStyle.panelPadding,
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        OmniSettingsSearchField(
                          controller: _searchController,
                          focusNode: _searchFocusNode,
                          hintText: widget.searchHint!,
                          onChanged: (_) => setState(() {}),
                        ),
                        const SizedBox(height: 8),
                        if (options.isEmpty)
                          Padding(
                            padding: OmniMenuStyle.contentPadding,
                            child: Text(
                              context.trLegacy('没有匹配的选项'),
                              style: TextStyle(
                                color: context.omniPalette.textTertiary,
                              ),
                            ),
                          ),
                        for (final option in options) _buildOption(option),
                      ],
                    ),
                  ),
                )
              : SingleChildScrollView(
                  padding: OmniMenuStyle.panelPadding,
                  child: IntrinsicWidth(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        for (final option in options) _buildOption(option),
                      ],
                    ),
                  ),
                ),
        ),
      ),
    );
  }
}

/// FlClash's bar search geometry on the shared settings glass surface.
/// Text selection keeps the TextField's native gesture and focus ownership.
class OmniSettingsSearchField extends StatefulWidget {
  const OmniSettingsSearchField({
    super.key,
    required this.controller,
    this.focusNode,
    required this.hintText,
    this.onChanged,
    this.onSubmitted,
    this.autofocus = false,
    this.height = 44,
  });

  final TextEditingController controller;
  final FocusNode? focusNode;
  final String hintText;
  final ValueChanged<String>? onChanged;
  final ValueChanged<String>? onSubmitted;
  final bool autofocus;
  final double height;

  @override
  State<OmniSettingsSearchField> createState() =>
      _OmniSettingsSearchFieldState();
}

class _OmniSettingsSearchFieldState extends State<OmniSettingsSearchField> {
  FocusNode? _ownedFocusNode;

  FocusNode get _focusNode =>
      widget.focusNode ?? (_ownedFocusNode ??= FocusNode());

  @override
  void initState() {
    super.initState();
    FocusManager.instance.addHighlightModeListener(_highlightModeChanged);
  }

  void _highlightModeChanged(FocusHighlightMode _) {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    FocusManager.instance.removeHighlightModeListener(_highlightModeChanged);
    _ownedFocusNode?.dispose();
    super.dispose();
  }

  void _clear() {
    widget.controller.clear();
    widget.onChanged?.call('');
    _focusNode.requestFocus();
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Listenable.merge([widget.controller, _focusNode]),
      builder: (context, _) {
        final palette = context.omniPalette;
        final glass = LiquidGlassScope.of(context);
        final showFocusRing =
            FocusManager.instance.highlightMode ==
            FocusHighlightMode.traditional;
        final hasQuery = widget.controller.text.isNotEmpty;
        const border = OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(1000)),
          borderSide: BorderSide.none,
        );

        final field = TextField(
          controller: widget.controller,
          focusNode: _focusNode,
          autofocus: widget.autofocus,
          maxLines: 1,
          onChanged: widget.onChanged,
          onSubmitted: widget.onSubmitted,
          onTapOutside: (_) => _focusNode.unfocus(),
          textInputAction: TextInputAction.search,
          textAlignVertical: TextAlignVertical.center,
          style: TextStyle(
            fontSize: 16,
            color: palette.textPrimary,
            fontWeight: FontWeight.w400,
            height: 1.25,
          ),
          cursorColor: palette.accentPrimary,
          decoration: InputDecoration(
            constraints: BoxConstraints.tightFor(height: widget.height),
            floatingLabelBehavior: FloatingLabelBehavior.never,
            hintText: widget.hintText,
            hintStyle: TextStyle(
              fontSize: 16,
              color: palette.textTertiary,
              fontWeight: FontWeight.w400,
            ),
            border: border,
            enabledBorder: border,
            focusedBorder: border.copyWith(
              borderSide: showFocusRing
                  ? BorderSide(
                      color: palette.accentPrimary.withValues(alpha: 0.65),
                    )
                  : BorderSide.none,
            ),
            filled: !glass,
            fillColor: palette.surfacePrimary,
            isDense: false,
            contentPadding: EdgeInsetsDirectional.only(end: hasQuery ? 0 : 12),
            prefixIconConstraints: const BoxConstraints.tightFor(width: 41),
            suffixIconConstraints: const BoxConstraints(),
            prefixIcon: Padding(
              padding: const EdgeInsetsDirectional.only(start: 12),
              child: Align(
                alignment: AlignmentDirectional.centerStart,
                child: Icon(
                  LucideIcons.search,
                  size: 20,
                  color: palette.textSecondary,
                ),
              ),
            ),
            suffixIcon: hasQuery
                ? SizedBox(
                    width: 49,
                    child: IconButton(
                      tooltip: context.l10n.homeDrawerClearSearch,
                      onPressed: _clear,
                      style: IconButton.styleFrom(
                        foregroundColor: palette.textSecondary,
                        overlayColor: Colors.transparent,
                        splashFactory: NoSplash.splashFactory,
                      ),
                      icon: const Icon(LucideIcons.circleX, size: 24),
                    ),
                  )
                : null,
          ),
        );
        if (!glass) return field;
        return LiquidGlass(
          style: LiquidGlassStyle.regular(Theme.of(context).colorScheme),
          // A null radius uses the analytical capsule shader path.
          child: ClipRRect(
            borderRadius: const BorderRadius.all(Radius.circular(1000)),
            child: field,
          ),
        );
      },
    );
  }
}

/// Consistent input decoration for text fields inside settings cards.
InputDecoration omniSettingsInputDecoration(
  BuildContext context, {
  String? labelText,
  String? hintText,
  String? helperText,
  String? errorText,
  Widget? prefixIcon,
  Widget? suffixIcon,
  bool isDense = true,
  EdgeInsetsGeometry? contentPadding,
}) {
  final palette = context.omniPalette;
  final isDark = context.isDarkTheme;
  final borderRadius = BorderRadius.circular(12);
  final borderColor = palette.borderSubtle.withValues(
    alpha: isDark ? 0.7 : 0.9,
  );

  return InputDecoration(
    labelText: labelText,
    hintText: hintText,
    helperText: helperText,
    helperMaxLines: 2,
    errorText: errorText,
    errorMaxLines: 2,
    isDense: isDense,
    filled: true,
    fillColor: palette.surfaceSecondary.withValues(alpha: isDark ? 0.65 : 0.7),
    prefixIcon: prefixIcon,
    suffixIcon: suffixIcon,
    labelStyle: TextStyle(
      color: palette.textSecondary,
      fontSize: 13,
      fontWeight: FontWeight.w500,
    ),
    hintStyle: TextStyle(color: palette.textTertiary, fontSize: 13),
    helperStyle: TextStyle(color: palette.textTertiary, fontSize: 11.5),
    contentPadding:
        contentPadding ??
        const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
    border: OutlineInputBorder(
      borderRadius: borderRadius,
      borderSide: BorderSide(color: borderColor, width: 0.75),
    ),
    enabledBorder: OutlineInputBorder(
      borderRadius: borderRadius,
      borderSide: BorderSide(color: borderColor, width: 0.75),
    ),
    disabledBorder: OutlineInputBorder(
      borderRadius: borderRadius,
      borderSide: BorderSide(
        color: borderColor.withValues(alpha: 0.4),
        width: 0.75,
      ),
    ),
    focusedBorder: OutlineInputBorder(
      borderRadius: borderRadius,
      borderSide: BorderSide(
        color: palette.accentPrimary.withValues(alpha: 0.75),
        width: 1.2,
      ),
    ),
    errorBorder: OutlineInputBorder(
      borderRadius: borderRadius,
      borderSide: const BorderSide(color: Color(0xFFE05252), width: 0.9),
    ),
    focusedErrorBorder: OutlineInputBorder(
      borderRadius: borderRadius,
      borderSide: const BorderSide(color: Color(0xFFE05252), width: 1.2),
    ),
  );
}
