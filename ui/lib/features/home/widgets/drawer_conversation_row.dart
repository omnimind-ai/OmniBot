import 'package:flutter/material.dart';
import 'package:ui/theme/theme_context.dart';

/// Shared presentation and full-row hit target for phone and computer history.
class DrawerConversationRow extends StatelessWidget {
  const DrawerConversationRow({
    super.key,
    required this.child,
    this.onTap,
    this.onLongPress,
  });
  final Widget child;
  final VoidCallback? onTap;
  final VoidCallback? onLongPress;

  @override
  Widget build(BuildContext context) => Material(
    color: Colors.transparent,
    child: InkWell(
      onTap: onTap,
      onLongPress: onLongPress,
      borderRadius: BorderRadius.circular(14),
      splashColor: context.omniPalette.accentPrimary.withValues(alpha: 0.08),
      highlightColor: Colors.transparent,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(4, 9, 2, 9),
        child: child,
      ),
    ),
  );
}

class DrawerConversationTitle extends StatelessWidget {
  const DrawerConversationTitle(
    this.title, {
    super.key,
    this.color,
    this.fontWeight = FontWeight.w500,
  });
  final String title;
  final Color? color;
  final FontWeight fontWeight;

  @override
  Widget build(BuildContext context) => Text(
    title,
    maxLines: 1,
    overflow: TextOverflow.ellipsis,
    style: TextStyle(
      fontSize: 13,
      fontWeight: fontWeight,
      color: color ?? context.omniPalette.textPrimary,
      height: 1.35,
      fontFamily: 'PingFang SC',
    ),
  );
}
