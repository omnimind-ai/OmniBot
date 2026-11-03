import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/flclash/search_header.dart';
import 'package:ui/widgets/omni_adaptive_layout.dart';

/// Shared title and safe-area layout for the four main navigation pages.
/// Search is optional; pages without it render no search control or field.
class OmniRootToolbar extends StatelessWidget implements PreferredSizeWidget {
  const OmniRootToolbar({
    super.key,
    required this.title,
    this.primary = true,
    this.showBackButton = false,
    this.titleTrailing,
    this.actions = const [],
    this.search,
    this.maxContentWidth = 840,
  });

  final String title;
  final bool primary;
  final bool showBackButton;
  final Widget? titleTrailing;
  final List<Widget> actions;
  final OmniRootToolbarSearch? search;
  final double maxContentWidth;

  static const titleKey = ValueKey('root-toolbar-title');
  static const height = FlClashSearchHeader.toolbarHeight;

  @override
  Size get preferredSize => const Size.fromHeight(height);

  @override
  Widget build(BuildContext context) => OmniContentWidth(
    maxWidth: maxContentWidth,
    child: FlClashSearchHeader(
      primary: primary,
      // Align titles with the pages' 22 dp content gutter while the toolbar
      // controls keep FlClash's 16 dp edge inset.
      title: Padding(
        padding: const EdgeInsetsDirectional.only(start: 6),
        child: Row(
          children: [
            Expanded(
              child: Semantics(
                header: true,
                child: Text(
                  title,
                  key: titleKey,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 28,
                    fontWeight: FontWeight.w600,
                    height: 1.2,
                    color: context.omniPalette.textPrimary,
                  ),
                ),
              ),
            ),
            if (titleTrailing != null && search?.searching != true)
              titleTrailing!,
          ],
        ),
      ),
      leading: showBackButton
          ? IconButton(
              tooltip: MaterialLocalizations.of(context).backButtonTooltip,
              onPressed: GoRouterManager.pop,
              icon: const Icon(LucideIcons.chevronLeft),
            )
          : null,
      actions: actions,
      enableSearch: search != null,
      searching: search?.searching ?? false,
      onSearch: search?.onSearch,
      onClose: search?.onClose,
      controller: search?.controller,
      focusNode: search?.focusNode,
      onChanged: search?.onChanged,
      hintText: search?.hintText,
      searchTooltip: search?.searchTooltip,
      clearTooltip: search?.clearTooltip,
      searchButtonKey: search?.searchButtonKey,
      closeButtonKey: search?.closeButtonKey,
      searchFieldKey: search?.fieldKey,
      clearButtonKey: search?.clearButtonKey,
      trailingSearchWidget: search?.trailing,
    ),
  );
}

/// Query and focus ownership remain with the page that performs the search.
class OmniRootToolbarSearch {
  const OmniRootToolbarSearch({
    required this.searching,
    required this.onSearch,
    required this.onClose,
    required this.controller,
    required this.focusNode,
    this.onChanged,
    this.hintText,
    this.searchTooltip,
    this.clearTooltip,
    this.searchButtonKey,
    this.closeButtonKey,
    this.fieldKey,
    this.clearButtonKey,
    this.trailing,
  });

  final bool searching;
  final VoidCallback onSearch;
  final VoidCallback onClose;
  final TextEditingController controller;
  final FocusNode focusNode;
  final ValueChanged<String>? onChanged;
  final String? hintText;
  final String? searchTooltip;
  final String? clearTooltip;
  final Key? searchButtonKey;
  final Key? closeButtonKey;
  final Key? fieldKey;
  final Key? clearButtonKey;
  final Widget? trailing;
}
