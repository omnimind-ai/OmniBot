import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/theme/theme_context.dart';

import '../onboarding_definitions.dart';
import '../onboarding_l10n.dart';

/// Circular back/next arrow used by the tutorial footer.
class OnboardingArrowButton extends StatelessWidget {
  const OnboardingArrowButton({
    super.key,
    required this.buttonKey,
    required this.icon,
    required this.tooltip,
    required this.onPressed,
  });

  /// Key applied to the inner [IconButton] (kept stable for tests).
  final Key buttonKey;
  final IconData icon;
  final String tooltip;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final enabled = onPressed != null;
    return Material(
      color: enabled ? palette.surfacePrimary : palette.surfaceSecondary,
      shape: CircleBorder(side: BorderSide(color: palette.borderStrong)),
      child: IconButton(
        key: buttonKey,
        onPressed: onPressed,
        tooltip: tooltip,
        icon: Icon(icon, size: 20),
        color: enabled ? palette.textPrimary : palette.textTertiary,
        disabledColor: palette.textTertiary,
        constraints: const BoxConstraints.tightFor(width: 48, height: 48),
        padding: EdgeInsets.zero,
      ),
    );
  }
}

/// Sticky footer for the navigable tutorial pages: optional primary action
/// above the dotted pagination row. Geometry matches the original footer so
/// the bar stays perfectly stable across page transitions.
class OnboardingFooter extends StatelessWidget {
  const OnboardingFooter({
    super.key,
    required this.currentPage,
    required this.isVisited,
    required this.canGoBack,
    required this.onBack,
    required this.nextKey,
    required this.nextTooltip,
    required this.onNext,
    required this.onJumpToPage,
    this.primaryAction,
    this.navigationCenter,
    this.navigationKey = const ValueKey('tutorial-pagination-navigation'),
  });

  final TutorialPage currentPage;
  final bool Function(TutorialPage page) isVisited;
  final bool canGoBack;
  final VoidCallback onBack;
  final Key nextKey;
  final String nextTooltip;
  final VoidCallback? onNext;
  final void Function(TutorialPage page) onJumpToPage;
  final Widget? primaryAction;

  /// Replaces pagination with an action while retaining identical footer geometry.
  final Widget? navigationCenter;
  final Key navigationKey;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 760),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 2, 20, 8),
          child: Column(
            key: const ValueKey('tutorial-fixed-footer'),
            mainAxisSize: MainAxisSize.min,
            children: [
              if (primaryAction != null) ...[
                KeyedSubtree(
                  key: const ValueKey('tutorial-sticky-primary-action'),
                  child: primaryAction!,
                ),
                const SizedBox(height: 6),
              ],
              _buildNavigation(context),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildNavigation(BuildContext context) {
    final palette = context.omniPalette;
    final reduceMotion =
        MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    final currentIndex = tutorialNavigationPages.indexOf(currentPage);

    return SizedBox(
      key: navigationKey,
      height: 58,
      child: Row(
        children: [
          OnboardingArrowButton(
            buttonKey: const ValueKey('tutorial-bottom-back'),
            icon: LucideIcons.arrowLeft,
            tooltip: onbTr(context, '上一步', 'Previous'),
            onPressed: canGoBack ? onBack : null,
          ),
          Expanded(
            child:
                navigationCenter ??
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: List<Widget>.generate(
                    tutorialNavigationPages.length,
                    (index) {
                      final page = tutorialNavigationPages[index];
                      final selected = index == currentIndex;
                      final visited = isVisited(page);
                      return Expanded(
                        child: Semantics(
                          button: true,
                          selected: selected,
                          enabled: visited,
                          label: onbTr(
                            context,
                            '教程第 ${index + 1} 页',
                            'Tutorial page ${index + 1}',
                          ),
                          child: InkResponse(
                            key: ValueKey<String>('tutorial-page-dot-$index'),
                            onTap: visited ? () => onJumpToPage(page) : null,
                            radius: 22,
                            child: SizedBox(
                              height: 48,
                              child: Center(
                                child: AnimatedContainer(
                                  duration: reduceMotion
                                      ? Duration.zero
                                      : const Duration(milliseconds: 180),
                                  width: selected ? 9 : 6,
                                  height: selected ? 9 : 6,
                                  decoration: BoxDecoration(
                                    color: selected
                                        ? palette.accentPrimary
                                        : visited
                                        ? palette.textTertiary
                                        : palette.borderStrong,
                                    shape: BoxShape.circle,
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
          ),
          OnboardingArrowButton(
            buttonKey: nextKey,
            icon: LucideIcons.arrowRight,
            tooltip: nextTooltip,
            onPressed: onNext,
          ),
        ],
      ),
    );
  }
}
