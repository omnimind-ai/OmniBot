part of 'home_drawer.dart';

extension _HomeDrawerHeaderFooter on HomeDrawerState {
  Color get _drawerBackgroundColor {
    if (!context.isDarkTheme) {
      return AppColors.background;
    }
    return context.omniPalette.pageBackground;
  }

  Color get _drawerTextColor {
    if (!context.isDarkTheme) {
      return AppColors.text;
    }
    return context.omniPalette.textPrimary;
  }

  Color get _drawerSecondaryTextColor {
    if (!context.isDarkTheme) {
      return AppColors.text.withValues(alpha: 0.4);
    }
    return context.omniPalette.textSecondary;
  }

  Widget _buildWebQuickLaunchBar() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 10),
      child: Row(
        children: [
          for (int index = 0; index < _webQuickActions.length; index++) ...[
            if (index > 0) const SizedBox(width: 8),
            Expanded(
              child: _buildWebQuickLaunchButton(_webQuickActions[index]),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildWebQuickLaunchButton(OmniPluginActionItem action) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    final key = '${action.pluginId}/${action.id}';
    final busy = _busyWebQuickActionKey == key;
    final disabled = _busyWebQuickActionKey != null;
    final agentId = action.presentation['agentId']?.toString().trim() ?? '';
    final label = action.localizedPresentationValue(
      'shortLabel',
      english: english,
      fallback: action.displayName,
    );
    final semanticLabel = action.localizedPresentationValue(
      'label',
      english: english,
      fallback: action.displayName,
    );
    final surface = context.isDarkTheme
        ? context.omniPalette.surfaceSecondary
        : Colors.white;

    return Semantics(
      button: true,
      enabled: !disabled,
      label: semanticLabel,
      child: Tooltip(
        message: semanticLabel,
        excludeFromSemantics: true,
        child: Opacity(
          opacity: disabled && !busy ? 0.5 : 1,
          child: Material(
            color: surface,
            borderRadius: BorderRadius.circular(16),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              key: ValueKey('home-drawer-web-$agentId'),
              onTap: disabled ? null : () => _invokeWebQuickAction(action),
              borderRadius: BorderRadius.circular(16),
              child: SizedBox(
                height: 48,
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    AnimatedSwitcher(
                      duration: const Duration(milliseconds: 160),
                      child: busy
                          ? SizedBox(
                              key: const ValueKey('busy'),
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: context.omniPalette.accentPrimary,
                              ),
                            )
                          : AgentBrandIcon(
                              key: const ValueKey('brand'),
                              agentId: agentId,
                              size: 20,
                            ),
                    ),
                    const SizedBox(width: 8),
                    Flexible(
                      child: Text(
                        label,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: _drawerTextColor,
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildFooterShortcutBar() {
    final items = <_DrawerShortcutAction>[
      _DrawerShortcutAction(
        label: context.l10n.settingsTitle,
        assetPath: 'assets/home/setting_icon.svg',
        onTap: () => _navigateTo('/home/settings'),
      ),
      _DrawerShortcutAction(
        label: context.l10n.memoryCenterTitle,
        svgString: _kDrawerMemoryIconSvg,
        onTap: () => _navigateTo('/memory/memory_center_page'),
      ),
      _DrawerShortcutAction(
        label: context.l10n.pluginMarketTitle,
        svgString: _kDrawerPluginMarketIconSvg,
        onTap: () => _navigateTo('/home/plugin_market'),
      ),
      _DrawerShortcutAction(
        label: context.l10n.skillStoreTitle,
        svgString: _kDrawerSkillStoreIconSvg,
        onTap: () => _navigateTo('/home/skill_store'),
      ),
      _DrawerShortcutAction(
        label: context.trLegacy('轨迹'),
        svgString: _kDrawerUsageStatisticsIconSvg,
        onTap: () => _navigateTo('/task/execution_history'),
      ),
      _DrawerShortcutAction(
        label: context.l10n.homeDrawerScheduled,
        assetPath: 'assets/common/schedule_icon.svg',
        onTap: () => _navigateTo('/task/scheduled_tasks'),
      ),
    ];

    const capsuleHeight = 44.0;
    final capsuleColor = context.isDarkTheme
        ? context.omniPalette.surfaceSecondary
        : Colors.white;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Material(
        color: capsuleColor,
        borderRadius: BorderRadius.circular(capsuleHeight / 2),
        clipBehavior: Clip.antiAlias,
        child: SizedBox(
          height: capsuleHeight,
          child: Row(
            children: items
                .map(
                  (item) => Expanded(
                    child: _buildFooterShortcutButton(
                      item,
                      height: capsuleHeight,
                    ),
                  ),
                )
                .toList(growable: false),
          ),
        ),
      ),
    );
  }

  Widget _buildFooterShortcutButton(
    _DrawerShortcutAction item, {
    required double height,
  }) {
    final palette = context.omniPalette;
    final iconColor = context.isDarkTheme
        ? palette.textPrimary
        : AppColors.text;
    final icon = item.assetPath != null
        ? SvgPicture.asset(
            item.assetPath!,
            width: 17,
            height: 17,
            colorFilter: ColorFilter.mode(iconColor, BlendMode.srcIn),
          )
        : SvgPicture.string(
            item.svgString!,
            width: 17,
            height: 17,
            colorFilter: ColorFilter.mode(iconColor, BlendMode.srcIn),
          );

    return Tooltip(
      message: item.label,
      child: InkWell(
        onTap: item.onTap,
        borderRadius: BorderRadius.circular(height / 2),
        child: SizedBox(
          height: height,
          child: Center(child: icon),
        ),
      ),
    );
  }
}
