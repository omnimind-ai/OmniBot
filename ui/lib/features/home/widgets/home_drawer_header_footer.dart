part of 'home_drawer.dart';

extension _HomeDrawerHeaderFooter on HomeDrawerState {
  Color get _drawerBackgroundColor {
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

  AgentWebStatus get _aggregateWebQuickStatus {
    var hasStarting = false;
    for (final action in _webQuickActions) {
      final key = '${action.pluginId}/${action.id}';
      final status = _webQuickActionStatuses[key] ?? AgentWebStatus.unknown;
      if (status == AgentWebStatus.running) {
        return AgentWebStatus.running;
      }
      if (status == AgentWebStatus.starting) {
        hasStarting = true;
      }
    }
    return hasStarting ? AgentWebStatus.starting : AgentWebStatus.unknown;
  }

  /// The vendor brand icon with a small status dot while the managed Web
  /// process is starting or running.
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

  Future<void> _showWebQuickLaunchPopover(BuildContext anchorContext) async {
    if (_webQuickActions.isEmpty) return;
    performOmniSelectionHaptic();

    final anchor = glassPopupAnchorFromContext(anchorContext);
    if (anchor == null) return;

    await showGlassPopup<void>(
      context: anchorContext,
      sourceContext: anchorContext,
      anchor: anchor,
      preferBelow: false,
      child: Builder(
        builder: (dialogContext) {
          final palette = dialogContext.omniPalette;
          final english =
              Localizations.localeOf(dialogContext).languageCode == 'en';
          final surface = palette.surfacePrimary;

          return Material(
            color: Colors.transparent,
            child: OmniMenuSurface(
              width: 320,
              child: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(10, 4, 10, 6),
                      child: Text(
                        'Agent WebUI',
                        style: TextStyle(
                          fontSize: 11,
                          fontWeight: FontWeight.w600,
                          letterSpacing: 0.3,
                          color: palette.textTertiary,
                        ),
                      ),
                    ),
                    for (var i = 0; i < _webQuickActions.length; i++) ...[
                      if (i > 0) const SizedBox(height: 2),
                      _buildWebPopoverOptionTile(
                        dialogContext,
                        _webQuickActions[i],
                        surface: surface,
                        english: english,
                      ),
                    ],
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _buildWebPopoverOptionTile(
    BuildContext dialogContext,
    OmniPluginActionItem action, {
    required Color surface,
    required bool english,
    AgentWebStatus? statusOverride,
  }) {
    final palette = dialogContext.omniPalette;
    final key = '${action.pluginId}/${action.id}';
    final busy = _busyWebQuickActionKey == key;
    final disabled = _busyWebQuickActionKey != null;
    final agentId = action.presentation['agentId']?.toString().trim() ?? '';
    final status =
        statusOverride ??
        _webQuickActionStatuses[key] ??
        AgentWebStatus.unknown;
    final stoppable =
        status.active && AgentWebStatusService.supportsLifecycle(action);
    final shortLabel = action.localizedPresentationValue(
      'shortLabel',
      english: english,
      fallback: action.displayName,
    );
    final fullLabel = action.localizedPresentationValue(
      'label',
      english: english,
      fallback: action.displayName,
    );

    return OmniMenuItem(
      key: ValueKey('home-drawer-web-$agentId'),
      enabled: !disabled,
      onTap: disabled
          ? null
          : () {
              Navigator.of(dialogContext).pop();
              unawaited(_invokeWebQuickAction(action));
            },
      child: Row(
        children: [
          if (busy)
            SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(
                strokeWidth: 2,
                color: palette.accentPrimary,
              ),
            )
          else
            _buildWebQuickBrandIcon(agentId, status: status, surface: surface),
          const SizedBox(width: OmniMenuStyle.iconGap),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  shortLabel,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: palette.textPrimary,
                    fontSize: 16,
                    fontWeight: FontWeight.w400,
                    height: 1.25,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  status.active
                      ? '$fullLabel · ${english ? 'Running' : '运行中'}'
                      : fullLabel,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: status.active
                        ? const Color(0xFF2EAF67)
                        : palette.textSecondary,
                    fontSize: 13,
                    height: 1.25,
                  ),
                ),
              ],
            ),
          ),
          if (stoppable) ...[
            const SizedBox(width: 8),
            FlClashMenuInteractionIgnore(
              child: TextButton.icon(
                key: ValueKey('home-drawer-web-stop-$agentId'),
                style: TextButton.styleFrom(
                  foregroundColor: const Color(0xFFE05252),
                  backgroundColor: const Color(0xFFE05252)
                      .withValues(alpha: 0.08),
                  minimumSize: const Size(0, 30),
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 4,
                  ),
                  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(8),
                  ),
                ),
                onPressed: disabled
                    ? null
                    : () {
                        Navigator.of(dialogContext).pop();
                        unawaited(_stopWebQuickAction(action));
                      },
                icon: const Icon(LucideIcons.circleStop, size: 13),
                label: Text(
                  english ? 'Stop' : '停止',
                  style: const TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
            ),
          ] else ...[
            const SizedBox(width: 8),
            Icon(
              LucideIcons.arrowUpRight,
              size: 15,
              color: palette.textTertiary,
            ),
          ],
        ],
      ),
    );
  }

  List<_DrawerShortcutAction> get _shortcutActions => <_DrawerShortcutAction>[
    if (!widget.homePage)
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
    if (!widget.homePage)
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

  Widget _buildFooterShortcutBar() {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final items = _shortcutActions;
    const barHeight = 44.0;
    final surfaceColor = isDark ? palette.surfacePrimary : Colors.white;
    final borderColor = palette.borderSubtle.withValues(
      alpha: isDark ? 0.65 : 0.85,
    );

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Material(
        color: surfaceColor,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(14),
          side: BorderSide(color: borderColor, width: 0.75),
        ),
        clipBehavior: Clip.antiAlias,
        child: SizedBox(
          height: barHeight,
          child: Row(
            children: [
              for (final item in items)
                Expanded(
                  child: _buildFooterShortcutButton(item, height: barHeight),
                ),
              if (_webQuickActions.isNotEmpty)
                Expanded(
                  child: _buildFooterWebShortcutButton(
                    height: barHeight,
                    surfaceColor: surfaceColor,
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildFooterWebShortcutButton({
    required double height,
    required Color surfaceColor,
  }) {
    final palette = context.omniPalette;
    final iconColor = context.isDarkTheme
        ? palette.textPrimary
        : AppColors.text;
    final status = _aggregateWebQuickStatus;
    final dotColor = switch (status) {
      AgentWebStatus.running => const Color(0xFF2EAF67),
      AgentWebStatus.starting => const Color(0xFFE3A52B),
      _ => null,
    };
    return FlClashPopupSource(
      glass: false,
      color: Colors.transparent,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
      builder: (buttonContext) => Tooltip(
        message: 'Agent WebUI',
        child: InkWell(
          key: const ValueKey('home-drawer-web-menu-button'),
          onTap: () => _showWebQuickLaunchPopover(buttonContext),
          borderRadius: BorderRadius.circular(10),
          child: SizedBox(
            height: height,
            child: Center(
              child: SizedBox(
                width: 22,
                height: 22,
                child: Stack(
                  alignment: Alignment.center,
                  children: [
                    Icon(LucideIcons.globe, size: 17, color: iconColor),
                    if (dotColor != null)
                      Positioned(
                        right: 0,
                        top: 0,
                        child: Container(
                          key: const ValueKey(
                            'home-drawer-web-menu-status-dot',
                          ),
                          width: 7,
                          height: 7,
                          decoration: BoxDecoration(
                            color: dotColor,
                            shape: BoxShape.circle,
                            border: Border.all(color: surfaceColor, width: 1.3),
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

  Widget _buildFooterShortcutButton(
    _DrawerShortcutAction item, {
    required double height,
  }) {
    return Tooltip(
      message: item.label,
      child: InkWell(
        onTap: () {
          performOmniSelectionHaptic();
          item.onTap();
        },
        borderRadius: BorderRadius.circular(10),
        child: SizedBox(
          height: height,
          child: Center(child: _shortcutIcon(item)),
        ),
      ),
    );
  }

  Widget _shortcutIcon(_DrawerShortcutAction item, {double size = 17}) {
    final color = context.omniPalette.textPrimary;
    if (item.icon != null) return Icon(item.icon, size: size, color: color);
    return item.assetPath != null
        ? SvgPicture.asset(
            item.assetPath!,
            width: size,
            height: size,
            colorFilter: ColorFilter.mode(color, BlendMode.srcIn),
          )
        : SvgPicture.string(
            item.svgString!,
            width: size,
            height: size,
            colorFilter: ColorFilter.mode(color, BlendMode.srcIn),
          );
  }
}
