import 'package:flutter/material.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/widgets/omni_glass.dart';

/// Presents a settings detail card using the app-wide bottom-sheet treatment.
///
/// Keeping the route configuration here ensures settings surfaces share the
/// same background, dismissal behavior, and transition instead of rebuilding
/// those details at every call site.
Future<T?> showSettingsDetailSheet<T>({
  required BuildContext context,
  required WidgetBuilder builder,
  bool isScrollControlled = false,
  bool isDismissible = true,
  bool enableDrag = true,
  bool useRootNavigator = false,
}) {
  return showOmniGlassBottomSheet<T>(
    context: context,
    backgroundColor: Colors.transparent,
    elevation: 0,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
    ),
    clipBehavior: Clip.antiAlias,
    isScrollControlled: isScrollControlled,
    isDismissible: isDismissible,
    enableDrag: enableDrag,
    useRootNavigator: useRootNavigator,
    useSafeArea: true,
    builder: builder,
  );
}

ButtonStyle settingsDetailSheetActionStyle(
  BuildContext context, {
  Color? foregroundColor,
}) {
  return TextButton.styleFrom(
    foregroundColor: foregroundColor ?? context.omniPalette.accentPrimary,
    minimumSize: const Size(0, 48),
    padding: const EdgeInsets.symmetric(horizontal: 10),
    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
  );
}

/// Compact bottom-sheet surface used by settings detail cards.
class SettingsDetailSheet extends StatelessWidget {
  const SettingsDetailSheet({
    super.key,
    required this.title,
    required this.body,
    this.subtitle,
    this.actions = const <Widget>[],
    this.actionsKey,
    this.headerAction,
    this.footer,
    this.fillAvailableHeight = false,
    this.avoidKeyboard = false,
  });

  final String title;
  final String? subtitle;
  final Widget body;
  final List<Widget> actions;
  final Key? actionsKey;
  final Widget? headerAction;
  final Widget? footer;
  final bool fillAvailableHeight;
  final bool avoidKeyboard;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final heading = Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          title,
          style: TextStyle(
            fontSize: 16.5,
            fontWeight: FontWeight.w600,
            color: palette.textPrimary,
            height: 1.35,
          ),
        ),
        if (subtitle != null) ...[
          const SizedBox(height: 4),
          Text(
            subtitle!,
            style: TextStyle(
              fontSize: 12.5,
              height: 1.48,
              color: palette.textSecondary,
            ),
          ),
        ],
      ],
    );
    final sheet = OmniGlassSheet(
      child: SizedBox(
        width: double.infinity,
        child: SafeArea(
          top: false,
          child: LayoutBuilder(
            builder: (context, constraints) {
              final horizontalPadding =
                  constraints.maxWidth >= OmniLayoutBreakpoints.medium
                  ? 24.0
                  : 16.0;
              final actionContent = Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (actions.isNotEmpty)
                    Wrap(
                      key: actionsKey,
                      spacing: 6,
                      runSpacing: 6,
                      alignment: WrapAlignment.start,
                      crossAxisAlignment: WrapCrossAlignment.center,
                      children: actions,
                    ),
                  if (footer != null) ...[
                    if (actions.isNotEmpty) const SizedBox(height: 10),
                    footer!,
                  ],
                ],
              );
              final hasActions = actions.isNotEmpty || footer != null;
              final content = SingleChildScrollView(
                padding: EdgeInsets.fromLTRB(
                  horizontalPadding,
                  16,
                  horizontalPadding,
                  hasActions ? 12 : 16,
                ),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Center(
                      child: Container(
                        width: 36,
                        height: 4,
                        margin: const EdgeInsets.only(bottom: 12),
                        decoration: BoxDecoration(
                          color: palette.borderStrong.withValues(alpha: 0.6),
                          borderRadius: BorderRadius.circular(999),
                        ),
                      ),
                    ),
                    if (headerAction == null)
                      heading
                    else
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Expanded(child: heading),
                          const SizedBox(width: 8),
                          headerAction!,
                        ],
                      ),
                    const SizedBox(height: 14),
                    body,
                    if (!hasActions) const SizedBox(height: 4),
                    if (hasActions && !constraints.hasBoundedHeight) ...[
                      const SizedBox(height: 14),
                      actionContent,
                    ],
                  ],
                ),
              );
              if (!constraints.hasBoundedHeight) return content;
              final layout = Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Flexible(
                    fit: fillAvailableHeight ? FlexFit.tight : FlexFit.loose,
                    child: content,
                  ),
                  if (hasActions)
                    // Keep save/confirm controls reachable while the form
                    // scrolls. A separate scroll viewport also allows large
                    // text to wrap on a short split-screen or above the IME.
                    ConstrainedBox(
                      constraints: BoxConstraints(
                        maxHeight: constraints.maxHeight * 0.45,
                      ),
                      child: SingleChildScrollView(
                        padding: EdgeInsets.fromLTRB(
                          horizontalPadding,
                          0,
                          horizontalPadding,
                          20,
                        ),
                        child: actionContent,
                      ),
                    ),
                ],
              );
              return fillAvailableHeight
                  ? SizedBox(height: constraints.maxHeight, child: layout)
                  : layout;
            },
          ),
        ),
      ),
    );
    if (!avoidKeyboard) return sheet;
    return AnimatedPadding(
      duration: MediaQuery.disableAnimationsOf(context)
          ? Duration.zero
          : const Duration(milliseconds: 180),
      curve: Curves.easeOutCubic,
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: MediaQuery.removeViewInsets(
        context: context,
        removeBottom: true,
        child: sheet,
      ),
    );
  }
}
