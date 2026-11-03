import 'package:flutter/material.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/flclash/search_header.dart';

/// The FlClash proxy toolbar presentation with the existing conversation
/// search owner. Query, debounce, filtering and menu actions stay in HomeDrawer.
class HomeSearchHeader extends StatelessWidget {
  static void releaseMenuPressOf(BuildContext context) =>
      FlClashSearchHeader.releasePressOf(context);

  const HomeSearchHeader({
    super.key,
    required this.searching,
    required this.agentSwitcher,
    required this.updateIndicator,
    required this.controller,
    required this.focusNode,
    required this.isSearching,
    this.searchFieldKey,
    required this.onSearch,
    required this.onClose,
    required this.menuButton,
  });

  final bool searching;
  final Widget agentSwitcher;
  final Widget updateIndicator;
  final TextEditingController controller;
  final FocusNode focusNode;
  final bool isSearching;
  final Key? searchFieldKey;
  final VoidCallback onSearch;
  final VoidCallback onClose;
  final Widget menuButton;

  @override
  Widget build(BuildContext context) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    return FlClashSearchHeader(
      title: Row(
        children: [
          Expanded(child: agentSwitcher),
          if (!searching) updateIndicator,
        ],
      ),
      primary: false,
      actions: [menuButton],
      searching: searching,
      controller: controller,
      focusNode: focusNode,
      hintText: context.l10n.homeDrawerSearchHint,
      searchTooltip: english ? 'Search conversations' : '搜索对话',
      searchButtonKey: const ValueKey('home-search-button'),
      closeButtonKey: const ValueKey('home-close-search'),
      searchFieldKey: searchFieldKey ?? const ValueKey('home-search-input'),
      clearButtonKey: const ValueKey('home-search-clear'),
      clearTooltip: context.l10n.homeDrawerClearSearch,
      trailingSearchWidget: isSearching
          ? SizedBox.square(
              dimension: 16,
              child: CircularProgressIndicator(
                strokeWidth: 2,
                color: context.omniPalette.accentPrimary,
              ),
            )
          : null,
      onSearch: onSearch,
      onClose: onClose,
    );
  }
}
