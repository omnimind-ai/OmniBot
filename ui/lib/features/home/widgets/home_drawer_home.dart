// ignore_for_file: invalid_use_of_protected_member

part of 'home_drawer.dart';

class _HomeConversationFilter {
  const _HomeConversationFilter({
    required this.id,
    this.agentId,
    this.name,
    this.mode = ConversationMode.agent,
  });

  static const all = _HomeConversationFilter(id: 'all');
  static const chat = _HomeConversationFilter(
    id: 'chat',
    mode: ConversationMode.chatOnly,
  );
  static const xiaowan = _HomeConversationFilter(
    id: 'xiaowan-acp',
    agentId: 'xiaowan-acp',
    name: '小万',
  );

  final String id;
  final String? agentId;
  final String? name;
  final ConversationMode mode;
}

extension _HomeDrawerHome on HomeDrawerState {
  Future<void> _loadHomeAgentCatalog() =>
      runPageLoad(#homeAgentCatalog, _readHomeAgentCatalog, revalidate: true);

  Future<void> _readHomeAgentCatalog() async {
    final request = ++_homeCatalogRequest;
    try {
      final catalog = await AgentRuntimeService.listAgents();
      if (!await waitForPageReady() ||
          request != _homeCatalogRequest ||
          !pageLoadIsCurrent(#homeAgentCatalog)) {
        return;
      }
      setState(() {
        _homeAgentCatalog = catalog;
        final filters = _homeFilters;
        // Refresh the name as well as availability without resetting a valid
        // selection. An unavailable Agent falls back to the first usable one.
        _homeFilter =
            filters
                .where((filter) => filter.id == _homeFilter.id)
                .firstOrNull ??
            filters.firstWhere(
              (filter) => filter.agentId != null,
              orElse: () => _HomeConversationFilter.chat,
            );
      });
    } catch (error) {
      // Retain the last catalog during a transient native refresh failure.
      // Xiaowan remains available through the same built-in fallback as chat.
      debugPrint('Load home Agent catalog failed: $error');
    }
  }

  List<_HomeConversationFilter> get _homeFilters {
    final profiles = _homeAgentCatalog?.agents ?? const <AcpAgentProfile>[];
    final available = profiles
        .where(
          (profile) =>
              profile.enabled &&
              profile.installed == true &&
              profile.status.trim().toLowerCase() != 'missing',
        )
        .toList(growable: false);
    return [
      _HomeConversationFilter.all,
      if (!profiles.any((profile) => profile.id == 'xiaowan-acp'))
        _HomeConversationFilter.xiaowan,
      // Filtering availability preserves the shared native catalog order.
      for (final profile in available)
        _HomeConversationFilter(
          id: profile.id,
          agentId: profile.id,
          name: profile.name.trim().isEmpty ? profile.id : profile.name.trim(),
        ),
      _HomeConversationFilter.chat,
    ];
  }

  bool _matchesHomeFilter(ConversationModel conversation) {
    if (!widget.homePage) return true;
    if (_homeFilter.id == 'all') return true;
    if (_homeFilter.mode == ConversationMode.chatOnly) {
      return conversation.mode == ConversationMode.chatOnly;
    }
    if (conversation.mode == ConversationMode.chatOnly ||
        conversation.mode == ConversationMode.openclaw) {
      return false;
    }
    // Old Xiaowan and scheduled child conversations predate agentId.
    final savedAgentId = conversation.agentId?.trim() ?? '';
    final agentId = savedAgentId.isEmpty ? 'xiaowan-acp' : savedAgentId;
    return AgentBrandIcon.normalizeAgentId(agentId) ==
        AgentBrandIcon.normalizeAgentId(_homeFilter.agentId ?? '');
  }

  Widget _buildHomePage() {
    final palette = context.omniPalette;
    // Assign before lazy rows build, across date/pinned/scheduled boundaries.
    // Busy rows still occupy space and must participate in neighbor avoidance.
    _homeAvatarColorIndices = HomeConversationAvatarColors.assign(
      _visibleConversationRows.keys,
    );
    return PopScope(
      canPop:
          !widget.active || (!_homeSearchVisible && !_isSelectingConversations),
      onPopInvokedWithResult: (didPop, _) {
        if (didPop || widget.conversationSidebar) return;
        handleBackIfNeeded();
      },
      child: Scaffold(
        backgroundColor: widget.conversationSidebar
            ? Colors.transparent
            : palette.pageBackground,
        resizeToAvoidBottomInset: true,
        body: SafeArea(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (_isSelectingConversations)
                _buildConversationSelectionHeader(),
              if (!_isSelectingConversations || _homeSearchVisible)
                LayoutBuilder(
                  builder: (context, constraints) {
                    final updateInMenu = _homeUpdateBelongsInMenu(
                      constraints.maxWidth,
                    );
                    return HomeSearchHeader(
                      searching: _homeSearchVisible,
                      agentSwitcher: _buildHomeAgentSwitcher(
                        compact: widget.conversationSidebar,
                      ),
                      updateIndicator: updateInMenu
                          ? const SizedBox.shrink()
                          : _buildHomeAppUpdateIndicator(),
                      searchFieldKey: widget.searchFieldKey,
                      controller: _searchController,
                      focusNode: _searchFocusNode,
                      isSearching: _isSearching,
                      onSearch: _openHomeSearch,
                      onClose: _closeHomeSearch,
                      menuButton: _buildHomeMenuButton(
                        updateInMenu: updateInMenu,
                      ),
                    );
                  },
                ),
              const SizedBox(height: 10),
              Expanded(
                child: Padding(
                  // Move 12dp of the sidebar gutter inside its rows so the
                  // selection has breathing room without narrowing the text.
                  padding: EdgeInsets.symmetric(
                    horizontal: widget.conversationSidebar ? 4 : 16,
                  ),
                  child: SlidableAutoCloseBehavior(
                    child: isLoadingConversations
                        ? _buildConversationLoadingState()
                        : _isSearchActive
                        ? _buildSearchConversationBody(
                            _visibleConversationResults,
                          )
                        : _buildConversationTimelineBody(
                            _visibleConversationResults,
                          ),
                  ),
                ),
              ),
            ],
          ),
        ),
        bottomNavigationBar: _isSelectingConversations
            ? _buildConversationSelectionBar()
            : null,
        floatingActionButton: _isSelectingConversations
            ? null
            : Padding(
                padding: const EdgeInsets.only(right: 6, bottom: 24),
                child: SizedBox(
                  width: 52,
                  height: 52,
                  child: OmniGlassFloatingActionButton(
                    builder: (context, glass) => FloatingActionButton(
                      key: const ValueKey('home-new-conversation'),
                      heroTag: 'home-new-conversation',
                      tooltip: context.l10n.homeDrawerNewChat,
                      onPressed: _openNewConversation,
                      backgroundColor: glass
                          ? Colors.transparent
                          : palette.accentPrimary,
                      foregroundColor: glass
                          ? palette.textPrimary
                          : Theme.of(context).colorScheme.onPrimary,
                      shape: const CircleBorder(),
                      elevation: glass ? 0 : 3,
                      focusElevation: glass ? 0 : null,
                      hoverElevation: glass ? 0 : null,
                      highlightElevation: glass ? 0 : null,
                      disabledElevation: glass ? 0 : null,
                      child: const Icon(LucideIcons.plus, size: 26),
                    ),
                  ),
                ),
              ),
      ),
    );
  }

  void _openHomeSearch() {
    if (_homeSearchVisible) return;
    setState(() => _homeSearchVisible = true);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && _homeSearchVisible) _searchFocusNode.requestFocus();
    });
  }

  bool _homeUpdateBelongsInMenu(double toolbarWidth) {
    final textScaler = MediaQuery.textScalerOf(context);
    final english = Localizations.localeOf(context).languageCode == 'en';
    final switchLabel = TextPainter(
      text: TextSpan(
        text: english ? 'Switch' : '切换',
        style: Theme.of(context).textTheme.labelLarge
            ?.copyWith(fontSize: 11, fontWeight: FontWeight.w600),
      ),
      textDirection: Directionality.of(context),
      textScaler: textScaler,
    )..layout();
    // Capsule padding, icon, gaps and badge padding, followed by two name
    // glyphs and an ellipsis. Reserve these before a separate update action.
    final minimumAgentWidth =
        24 + 24 + 16 + 8 + switchLabel.width + textScaler.scale(16) * 3;
    switchLabel.dispose();
    // FlClash's 16dp bar edges, 88dp action group and 8dp group gap, plus
    // the existing update button's 48dp hit area and 4dp trailing inset.
    final availableAgentWidth =
        toolbarWidth -
        MediaQuery.paddingOf(context).horizontal -
        32 -
        88 -
        8 -
        52;
    return availableAgentWidth < minimumAgentWidth;
  }

  Widget _buildHomeAppUpdateIndicator() {
    return ValueListenableBuilder<AppUpdateStatus?>(
      valueListenable: AppUpdateService.statusNotifier,
      builder: (context, status, _) {
        if (!AppUpdateService.shouldShowBanner(status)) {
          return const SizedBox.shrink();
        }
        final updateStatus = status!;
        final english = Localizations.localeOf(context).languageCode == 'en';
        return Padding(
          padding: const EdgeInsets.only(right: 4),
          child: IconButton(
            key: const ValueKey('home-app-update-button'),
            tooltip: english
                ? 'New version ${updateStatus.latestVersionLabel} available'
                : '发现新版本 ${updateStatus.latestVersionLabel}',
            onPressed: () => showAppUpdateDialog(context, updateStatus),
            padding: const EdgeInsets.all(15),
            constraints: const BoxConstraints.tightFor(width: 48, height: 48),
            icon: SvgPicture.asset(
              'assets/home/chat/update_sparkles.svg',
              width: 18,
              height: 18,
              colorFilter: const ColorFilter.mode(
                Color(0xFFD4A017),
                BlendMode.srcIn,
              ),
            ),
          ),
        );
      },
    );
  }

  void _closeHomeSearch() {
    _searchController.clear();
    _searchFocusNode.unfocus();
    setState(() => _homeSearchVisible = false);
  }

  String _homeFilterLabel(_HomeConversationFilter filter) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    return switch (filter.id) {
      'all' => english ? 'All' : '全部',
      'chat' => english ? 'Chat' : '纯聊天',
      'xiaowan-acp' => english ? 'Xiaowan' : '小万',
      _ => filter.name ?? filter.id,
    };
  }

  Widget _buildHomeFilterIcon(_HomeConversationFilter filter, double size) {
    return SizedBox.square(
      dimension: size,
      child: Center(
        child: filter.agentId != null
            ? AgentBrandIcon(agentId: filter.agentId!, size: size)
            : Icon(
                filter.id == 'all'
                    ? LucideIcons.layers
                    : LucideIcons.messageCircle,
                size: size,
                color: context.omniPalette.textPrimary,
              ),
      ),
    );
  }

  Widget _buildHomeAgentSwitcher({bool compact = false}) {
    final palette = context.omniPalette;
    final english = Localizations.localeOf(context).languageCode == 'en';
    final menuWidth = (MediaQuery.sizeOf(context).width - 32).clamp(0.0, 340.0);
    return Align(
      alignment: AlignmentDirectional.centerStart,
      widthFactor: 1,
      child: FlClashPopupBox<String>(
        sourceShape: const RoundedSuperellipseBorder(
          borderRadius: BorderRadius.all(Radius.circular(999)),
        ),
        placement: FlClashPopupPlacement.overAnchorStart,
        onOpened: performOmniSelectionHaptic,
        onSelected: (id) {
          // Catalog changes do not invalidate the established filter owner.
          if (!mounted || _isBatchConversationActionRunning) return;
          final filter = _homeFilters
              .where((item) => item.id == id)
              .firstOrNull;
          if (filter != null) _selectHomeFilter(filter);
        },
        targetBuilder: (buttonContext, open) => FlClashToolbarCapsule(
          key: const ValueKey('home-agent-switcher'),
          tooltip: english ? 'Switch Agent' : '切换 Agent',
          onPressed: _isBatchConversationActionRunning ? null : open,
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              ExcludeSemantics(child: _buildHomeFilterIcon(_homeFilter, 24)),
              const SizedBox(width: 8),
              Flexible(
                child: Text(
                  _homeFilterLabel(_homeFilter),
                  key: const ValueKey('home-agent-name'),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: compact ? 15 : 16,
                    fontWeight: FontWeight.w600,
                    color: palette.textPrimary,
                    height: 1.2,
                  ),
                ),
              ),
              if (!compact || !widget.conversationSidebar) ...[
                const SizedBox(width: 8),
                DecoratedBox(
                  decoration: BoxDecoration(
                    color: palette.brandSurface,
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 4,
                      vertical: 2,
                    ),
                    child: Text(
                      english ? 'Switch' : '切换',
                      key: const ValueKey('home-agent-switch-action'),
                      maxLines: 1,
                      softWrap: false,
                      style: TextStyle(
                        fontSize: compact ? 10 : 11,
                        fontWeight: FontWeight.w600,
                        color: palette.brandPrimary,
                        height: 1.2,
                      ),
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
        popupBuilder: (popupContext) => SizedBox(
          width: menuWidth,
          child: SingleChildScrollView(
            padding: OmniMenuStyle.panelPadding,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (final filter in _homeFilters)
                  FlClashPopupMenuItem<String>(
                    key: ValueKey('home-agent-option-${filter.id}'),
                    value: filter.id,
                    selected: _homeFilter.id == filter.id,
                    child: Row(
                      children: [
                        ExcludeSemantics(
                          child: _buildHomeFilterIcon(
                            filter,
                            OmniMenuStyle.iconSize,
                          ),
                        ),
                        const SizedBox(width: OmniMenuStyle.iconGap),
                        Expanded(
                          child: Text(
                            _homeFilterLabel(filter),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        const SizedBox(width: 12),
                        SizedBox.square(
                          dimension: 18,
                          child: _homeFilter.id == filter.id
                              ? const Icon(LucideIcons.check, size: 18)
                              : null,
                        ),
                      ],
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  void _selectHomeFilter(_HomeConversationFilter filter) {
    if (_homeFilter.id == filter.id) return;
    performOmniSelectionHaptic();
    setState(() => _homeFilter = filter);
  }

  List<_DrawerShortcutAction> _homeMenuActions(BuildContext sourceContext) {
    final webQuickActions = _homeMenuWebQuickActions;
    return [
      _DrawerShortcutAction(
        label: context.l10n.homeDrawerArchive,
        assetPath: 'assets/home/archive_icon.svg',
        onTap: () => _navigateTo('/home/archived_conversations'),
      ),
      _DrawerShortcutAction(
        label: _isPetOverlayShowing
            ? LegacyTextLocalizer.localize('收起宠物')
            : LegacyTextLocalizer.localize('唤起宠物'),
        icon: LucideIcons.pawPrint,
        onTap: () => unawaited(_handlePetOverlayTap()),
      ),
      ..._shortcutActions,
      if (webQuickActions.isNotEmpty)
        _DrawerShortcutAction(
          label: 'Agent WebUI',
          icon: LucideIcons.globe,
          onTap: () =>
              _showHomeWebActions(sourceContext, actions: webQuickActions),
        ),
    ];
  }

  Widget _buildHomeMenuButton({required bool updateInMenu}) => Builder(
    builder: (buttonContext) => FlClashPopupBox<VoidCallback>(
      onOpened: () {
        scheduleMicrotask(() {
          if (buttonContext.mounted) {
            HomeSearchHeader.releaseMenuPressOf(buttonContext);
          }
        });
        // Show the cached actions on pointer down, then refresh the already-open
        // menu. Plugin I/O must not hold the source button's opening animation.
        unawaited(_refreshHomeMenuWebQuickActions());
        unawaited(_syncPetOverlayState());
      },
      onSelected: (action) {
        if (mounted) action();
      },
      targetBuilder: (context, open) =>
          ValueListenableBuilder<AppUpdateStatus?>(
            valueListenable: AppUpdateService.statusNotifier,
            builder: (context, status, _) => IconButton(
              key: const ValueKey('home-menu-button'),
              tooltip: MaterialLocalizations.of(context).showMenuTooltip,
              onPressed: open,
              icon: Stack(
                clipBehavior: Clip.none,
                children: [
                  const Icon(LucideIcons.ellipsis),
                  if (updateInMenu && AppUpdateService.shouldShowBanner(status))
                    Positioned(
                      right: -1,
                      top: -1,
                      child: DecoratedBox(
                        key: const ValueKey('home-menu-update-badge'),
                        decoration: const BoxDecoration(
                          color: Color(0xFFD4A017),
                          shape: BoxShape.circle,
                        ),
                        child: const SizedBox.square(dimension: 6),
                      ),
                    ),
                ],
              ),
            ),
          ),
      popupBuilder: (popupContext) => ListenableBuilder(
        listenable: Listenable.merge([
          _homeMenuRevision,
          AppUpdateService.statusNotifier,
        ]),
        builder: (context, _) {
          if (!mounted) return const SizedBox.shrink();
          final actions = _homeMenuActions(buttonContext);
          final updateStatus = AppUpdateService.statusNotifier.value;
          final showUpdate =
              updateInMenu && AppUpdateService.shouldShowBanner(updateStatus);
          final english = Localizations.localeOf(context).languageCode == 'en';
          return SizedBox(
            width: 260,
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(8),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (showUpdate)
                    FlClashPopupMenuItem<VoidCallback>(
                      key: const ValueKey('home-menu-app-update'),
                      value: () =>
                          showAppUpdateDialog(this.context, updateStatus),
                      child: Row(
                        children: [
                          SvgPicture.asset(
                            'assets/home/chat/update_sparkles.svg',
                            width: 20,
                            height: 20,
                            colorFilter: const ColorFilter.mode(
                              Color(0xFFD4A017),
                              BlendMode.srcIn,
                            ),
                          ),
                          const SizedBox(width: OmniMenuStyle.iconGap),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(english ? 'Update available' : '发现新版本'),
                                Text(
                                  updateStatus!.latestVersionLabel,
                                  style: const TextStyle(
                                    color: Color(0xFFD4A017),
                                    fontSize: 12,
                                    height: 1.4,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  for (var i = 0; i < actions.length; i++)
                    FlClashPopupMenuItem<VoidCallback>(
                      key: ValueKey('home-menu-item-$i'),
                      value: actions[i].onTap,
                      child: Row(
                        children: [
                          _shortcutIcon(actions[i], size: 20),
                          const SizedBox(width: OmniMenuStyle.iconGap),
                          Expanded(child: Text(actions[i].label)),
                        ],
                      ),
                    ),
                ],
              ),
            ),
          );
        },
      ),
    ),
  );

  Future<void> _showHomeWebActions(
    BuildContext sourceContext, {
    required List<OmniPluginActionItem> actions,
  }) async {
    // This submenu uses the selected popup snapshot, independent of when the
    // underlying page is allowed to publish its refreshed cache.
    final statuses = await AgentWebStatusService.queryAll(
      actions.where(AgentWebStatusService.supportsLifecycle).toList(),
    );
    if (!await waitForPageReady() || !mounted || !sourceContext.mounted) return;
    final anchor = glassPopupAnchorFromContext(sourceContext);
    if (anchor == null) return;
    await showGlassPopup<void>(
      context: sourceContext,
      sourceContext: sourceContext,
      anchor: anchor,
      child: Builder(
        builder: (popupContext) => OmniMenuSurface(
          width: 340,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (final action in actions)
                  _buildWebPopoverOptionTile(
                    popupContext,
                    action,
                    statusOverride:
                        statuses['${action.pluginId}/${action.id}'] ??
                        AgentWebStatus.unknown,
                    surface: popupContext.omniPalette.surfacePrimary,
                    english:
                        Localizations.localeOf(context).languageCode == 'en',
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
