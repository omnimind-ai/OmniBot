import 'package:flutter/widgets.dart';

/// Window breakpoints shared by navigation and content surfaces. Individual
/// panes must still use their own LayoutBuilder constraints.
abstract final class OmniLayoutBreakpoints {
  static const double medium = 600;
  static const double expanded = 960;
}

/// Keeps forms, lists and conversation content within a readable column.
///
/// The surrounding page retains the real window metrics, including safe areas,
/// keyboard insets and overlay coordinates. The widget tree stays identical
/// across resize so editors, scroll positions and focus retain their owners.
class OmniContentWidth extends StatelessWidget {
  const OmniContentWidth({
    super.key,
    this.maxWidth = 840,
    this.alignment = Alignment.topCenter,
    required this.child,
  }) : assert(maxWidth > 0);

  final double maxWidth;
  final AlignmentGeometry alignment;
  final Widget child;

  @override
  Widget build(BuildContext context) => Align(
    alignment: alignment,
    child: ConstrainedBox(
      constraints: BoxConstraints(maxWidth: maxWidth),
      child: SizedBox(width: double.infinity, child: child),
    ),
  );
}
