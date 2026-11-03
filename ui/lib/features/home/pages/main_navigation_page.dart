import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/features/home/pages/agent/agent_mode_setting_page.dart';
import 'package:ui/features/home/pages/conversation_home_page.dart';
import 'package:ui/features/home/pages/settings/settings_page.dart';
import 'package:ui/features/task/pages/usage_statistics/usage_statistics_page.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/flclash/navigation_dock.dart';
import 'package:ui/widgets/flclash/tab_view.dart';
import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/widgets/omni_navigation_rail.dart';

enum MainNavigationTab {
  home,
  agents,
  trajectory,
  settings;

  static MainNavigationTab fromName(String? name) =>
      values.where((tab) => tab.name == name).firstOrNull ?? home;
}

/// One home route owns the four kept-alive surfaces. Opening a conversation or
/// configuration still pushes the existing route onto the root Navigator.
class MainNavigationPage extends StatefulWidget {
  const MainNavigationPage({
    super.key,
    this.initialTab = MainNavigationTab.home,
  });

  final MainNavigationTab initialTab;

  @override
  State<MainNavigationPage> createState() => _MainNavigationPageState();
}

class _MainNavigationPageState extends State<MainNavigationPage> {
  late int _index = widget.initialTab.index;

  @override
  void didUpdateWidget(covariant MainNavigationPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.initialTab != widget.initialTab) {
      _index = widget.initialTab.index;
    }
  }

  void _select(int index) {
    if (_index == index) return;
    FocusManager.instance.primaryFocus?.unfocus();
    setState(() => _index = index);
  }

  Widget _page(BuildContext context, int index, bool active) =>
      switch (MainNavigationTab.values[index]) {
        MainNavigationTab.home => ConversationHomePage(active: active),
        MainNavigationTab.agents => AgentModeSettingPage(
          embedded: true,
          active: active,
        ),
        MainNavigationTab.trajectory => UsageStatisticsPage(
          embedded: true,
          active: active,
        ),
        MainNavigationTab.settings => SettingsPage(
          embedded: true,
          active: active,
        ),
      };

  @override
  Widget build(BuildContext context) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    final media = MediaQuery.of(context);
    final keyboardVisible = media.viewInsets.bottom > 0;
    final destinations = [
      FlClashNavigationDestination(
        icon: LucideIcons.house,
        label: english ? 'Home' : '首页',
      ),
      const FlClashNavigationDestination(
        icon: LucideIcons.bot,
        label: 'Agents',
      ),
      FlClashNavigationDestination(
        icon: LucideIcons.chartNoAxesColumnIncreasing,
        label: english ? 'Activity' : '轨迹',
      ),
      FlClashNavigationDestination(
        icon: LucideIcons.settings,
        label: english ? 'Settings' : '设置',
      ),
    ];
    const numberKeys = [
      LogicalKeyboardKey.digit1,
      LogicalKeyboardKey.digit2,
      LogicalKeyboardKey.digit3,
      LogicalKeyboardKey.digit4,
    ];
    return CallbackShortcuts(
      bindings: {
        for (var i = 0; i < numberKeys.length; i++)
          SingleActivator(numberKeys[i], control: true): () => _select(i),
      },
      child: Focus(
        autofocus: true,
        child: Scaffold(
          backgroundColor: context.omniPalette.pageBackground,
          resizeToAvoidBottomInset: false,
          body: LayoutBuilder(
            builder: (context, constraints) {
              final useRail =
                  constraints.maxWidth >= OmniLayoutBreakpoints.medium;
              final leadingInset = useRail
                  ? OmniNavigationRail.width + media.padding.left
                  : 0.0;
              final bottomInset = useRail || keyboardVisible
                  ? media.padding.bottom
                  : FlClashNavigationDock.insetOf(context);
              return Stack(
                fit: StackFit.expand,
                children: [
                  // Keep one tab-view element across window resizing. Only its
                  // allocated rectangle and safe insets change, so drafts,
                  // search, scroll positions and lazy page owners survive.
                  Positioned.fill(
                    left: leadingInset,
                    child: MediaQuery(
                      data: media.copyWith(
                        padding: media.padding.copyWith(
                          left: useRail ? 0 : media.padding.left,
                          bottom: bottomInset,
                        ),
                        viewPadding: media.viewPadding.copyWith(
                          left: useRail ? 0 : media.viewPadding.left,
                          bottom: bottomInset,
                        ),
                      ),
                      child: FlClashTabView(
                        scrollDirection: useRail
                            ? Axis.vertical
                            : Axis.horizontal,
                        selectedIndex: _index,
                        itemCount: MainNavigationTab.values.length,
                        builder: _page,
                      ),
                    ),
                  ),
                  if (useRail)
                    Positioned(
                      left: 0,
                      top: 0,
                      bottom: 0,
                      width: leadingInset,
                      child: OmniNavigationRail(
                        key: const ValueKey('main-navigation-rail'),
                        selectedIndex: _index,
                        onDestinationSelected: _select,
                        destinations: destinations,
                      ),
                    )
                  else if (!keyboardVisible)
                    Positioned(
                      left: 0,
                      right: 0,
                      bottom: 0,
                      child: FlClashNavigationDock(
                        key: const ValueKey('main-navigation-dock'),
                        selectedIndex: _index,
                        onDestinationSelected: _select,
                        destinations: destinations,
                      ),
                    ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}
