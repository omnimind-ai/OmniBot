part of 'home_drawer.dart';

enum _WebQuickMenuAction { open, stop }

extension _HomeDrawerHeaderFooter on HomeDrawerState {
  Color get _drawerBackgroundColor {
    if (JackeryCesConfig.enabled) return JackeryCesConfig.background;
    if (!context.isDarkTheme) {
      return AppColors.background;
    }
    return context.omniPalette.pageBackground;
  }

  Color get _drawerTextColor {
    if (JackeryCesConfig.enabled) return const Color(0xFF303030);
    if (!context.isDarkTheme) {
      return AppColors.text;
    }
    return context.omniPalette.textPrimary;
  }

  Color get _drawerSecondaryTextColor {
    if (JackeryCesConfig.enabled) return const Color(0xFF77736D);
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
    final status = _webQuickActionStatuses[key] ?? AgentWebStatus.unknown;
    final stoppable =
        status.active && AgentWebStatusService.supportsLifecycle(action);
    final label = action.localizedPresentationValue(
      'shortLabel',
      english: english,
      fallback: action.displayName,
    );
    final baseSemanticLabel = action.localizedPresentationValue(
      'label',
      english: english,
      fallback: action.displayName,
    );
    final semanticLabel = status.active
        ? '$baseSemanticLabel · ${english ? 'Running' : '运行中'}'
        : baseSemanticLabel;
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
            child: Builder(
              builder: (chipContext) => InkWell(
                key: ValueKey('home-drawer-web-$agentId'),
                onTap: disabled ? null : () => _invokeWebQuickAction(action),
                onLongPress: disabled || !stoppable
                    ? null
                    : () => _showWebQuickActionMenu(
                        chipContext,
                        action,
                        english: english,
                      ),
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
                            : _buildWebQuickBrandIcon(
                                agentId,
                                status: status,
                                surface: surface,
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
      ),
    );
  }

  /// The vendor brand icon with a small status dot while the managed Web
  /// process is starting or running; the ring uses the chip surface color so
  /// the dot reads cleanly on both light and dark chips.
  Widget _buildWebQuickBrandIcon(
    String agentId, {
    required AgentWebStatus status,
    required Color surface,
  }) {
    final dotColor = switch (status) {
      AgentWebStatus.running => const Color(0xFF2EAF67),
      AgentWebStatus.starting => const Color(0xFFE3A52B),
      _ => null,
    };
    if (dotColor == null) {
      return AgentBrandIcon(
        key: const ValueKey('brand'),
        agentId: agentId,
        size: 20,
      );
    }
    return SizedBox(
      key: const ValueKey('brand'),
      width: 23,
      height: 23,
      child: Stack(
        children: [
          Center(child: AgentBrandIcon(agentId: agentId, size: 20)),
          Positioned(
            right: 0.5,
            top: 0.5,
            child: Container(
              key: ValueKey('home-drawer-web-status-$agentId'),
              width: 8,
              height: 8,
              decoration: BoxDecoration(
                color: dotColor,
                shape: BoxShape.circle,
                border: Border.all(color: surface, width: 1.5),
              ),
            ),
          ),
        ],
      ),
    );
  }

  /// Long-press management menu for a running Web process: reopen the
  /// browser UI or stop the process, anchored to the pressed chip.
  Future<void> _showWebQuickActionMenu(
    BuildContext chipContext,
    OmniPluginActionItem action, {
    required bool english,
  }) async {
    final palette = context.omniPalette;
    final label = action.localizedPresentationValue(
      'label',
      english: english,
      fallback: action.displayName,
    );
    String text(String zh, String en) => english ? en : zh;
    final box = chipContext.findRenderObject() as RenderBox?;
    final anchor = box == null
        ? Offset.zero
        : box.localToGlobal(Offset(box.size.width / 2, 0));
    final selection = await showMenu<_WebQuickMenuAction>(
      context: context,
      position: PopupMenuAnchorPosition.fromGlobalOffset(
        context: context,
        globalOffset: anchor,
        estimatedMenuHeight: 100,
      ),
      color: palette.surfaceElevated,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      elevation: 8,
      shadowColor: palette.shadowColor,
      menuPadding: EdgeInsets.zero,
      items: [
        _buildWebQuickMenuItem(
          value: _WebQuickMenuAction.open,
          icon: LucideIcons.arrowUpRight,
          iconColor: palette.textPrimary,
          label: text('打开 $label', 'Open $label'),
          labelColor: palette.textPrimary,
        ),
        PopupMenuDivider(height: 1, color: palette.borderSubtle),
        _buildWebQuickMenuItem(
          value: _WebQuickMenuAction.stop,
          icon: LucideIcons.circleStop,
          iconColor: const Color(0xFFE05252),
          label: text('停止', 'Stop'),
          labelColor: const Color(0xFFE05252),
        ),
      ],
    );
    if (!mounted || selection == null) return;
    switch (selection) {
      case _WebQuickMenuAction.open:
        await _invokeWebQuickAction(action);
      case _WebQuickMenuAction.stop:
        await _stopWebQuickAction(action);
    }
  }

  PopupMenuItem<_WebQuickMenuAction> _buildWebQuickMenuItem({
    required _WebQuickMenuAction value,
    required IconData icon,
    required Color iconColor,
    required String label,
    required Color labelColor,
  }) {
    return PopupMenuItem<_WebQuickMenuAction>(
      value: value,
      padding: EdgeInsets.zero,
      height: 44,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        child: Row(
          children: [
            Icon(icon, size: 16, color: iconColor),
            const SizedBox(width: 10),
            Flexible(
              child: Text(
                label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: labelColor,
                  fontSize: 13,
                  fontWeight: FontWeight.w500,
                  fontFamily: 'PingFang SC',
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildFooterShortcutBar() {
    if (JackeryCesConfig.enabled) {
      return Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        child: TextButton.icon(
          key: const ValueKey('jackery-drawer-settings'),
          onPressed: () => _navigateTo('/home/settings'),
          icon: const Icon(LucideIcons.settings, size: 19),
          label: const Text('Settings'),
          style: TextButton.styleFrom(foregroundColor: _drawerTextColor),
        ),
      );
    }
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
