import 'package:flutter/material.dart';
import 'package:ui/widgets/flclash/navigation_dock.dart';

/// The phone dock's original liquid-glass engine, laid out along the tablet's
/// leading edge. Selection remains owned by the host's persistent page tree.
class OmniNavigationRail extends StatelessWidget {
  const OmniNavigationRail({
    super.key,
    required this.destinations,
    required this.selectedIndex,
    required this.onDestinationSelected,
  });

  static const width = 104.0;

  final List<FlClashNavigationDestination> destinations;
  final int selectedIndex;
  final ValueChanged<int> onDestinationSelected;

  @override
  Widget build(BuildContext context) => Padding(
    padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
    child: SafeArea(
      right: false,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 16),
        child: Align(
          alignment: Alignment.center,
          child: FocusTraversalGroup(
            child: FlClashNavigationDock(
              axis: Axis.vertical,
              selectedIndex: selectedIndex,
              onDestinationSelected: onDestinationSelected,
              destinations: [
                for (final (index, destination) in destinations.indexed)
                  FlClashNavigationDestination(
                    key: ValueKey('main-navigation-rail-$index'),
                    icon: destination.icon,
                    label: destination.label,
                    tooltip: '${destination.label} (Ctrl+${index + 1})',
                  ),
              ],
            ),
          ),
        ),
      ),
    ),
  );
}
