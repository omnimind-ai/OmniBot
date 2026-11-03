// ignore_for_file: invalid_use_of_protected_member

part of 'home_drawer.dart';

const String _kExpandedConversationSectionsStorageKey =
    'home_drawer_expanded_sections_v1';
const String _kPinnedConversationSectionKey = '__home_drawer_pinned__';
const String _kScheduledConversationSectionKey = '__home_drawer_scheduled__';
const String _kChatOnlyConversationSectionKey = '__home_drawer_chat_only__';
const String _kOmniAiDateSectionNamespace = 'omni_ai';
const String _kChatOnlyDateSectionNamespace = 'chat_only';
const String _kChatOnlySectionIconAssetPath = 'assets/home/chat/pure_chat.svg';
const double _kConversationSectionHeaderLeadingSlotWidth = 20;
const double _kPromotedConversationItemTitleInset = 20;
const double _kScheduledConversationLeadingInset = 12;
// 模式区块内日期分组行与会话标题共用一条基准线：缩进 20 + 行内边距 4，
// 正好等于区块标题文字的起点（内边距 4 + 图标槽 20）。
const double _kModeSectionTimelineLeadingInset = 20;
const double _kScheduledParentToggleHitWidth = 40;
const double _kScheduledParentToggleIconSlotWidth = 24;
const double _kScheduledChildConversationItemInset =
    _kScheduledConversationLeadingInset + 26;

extension _HomeDrawerConversationList on HomeDrawerState {
  bool _isCurrentConversationRow(ConversationModel conversation) {
    final target = widget.selectedThreadTarget;
    return !_isSelectingConversations &&
        target != null &&
        !target.isNewConversation &&
        target.conversationId == conversation.id &&
        target.mode == conversation.mode;
  }

  Widget _buildConversationSection() {
    final visibleConversationResults = _visibleConversationResults;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Row(
            children: [
              Expanded(
                child: HomeDrawerSearchField(
                  key: widget.searchFieldKey,
                  controller: _searchController,
                  focusNode: _searchFocusNode,
                  isSearching: _isSearching,
                  textColor: _drawerTextColor,
                ),
              ),
              const SizedBox(width: 10),
              _buildSectionActionButton(
                iconPath: 'assets/home/archive_icon.svg',
                tooltip: context.l10n.homeDrawerArchive,
                onTap: () => _navigateTo('/home/archived_conversations'),
              ),
              const SizedBox(width: 10),
              _buildSectionActionButton(
                iconPath: 'assets/home/chat_add_icon.svg',
                tooltip: context.l10n.homeDrawerNewChat,
                onTap: _openNewConversation,
                isPrimary: true,
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        Expanded(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: isLoadingConversations
                ? _buildConversationLoadingState()
                : _isSearchActive
                ? _buildSearchConversationBody(visibleConversationResults)
                : _buildConversationTimelineBody(visibleConversationResults),
          ),
        ),
      ],
    );
  }

  Widget _buildConversationLoadingState() {
    return Center(
      child: SizedBox(
        width: 20,
        height: 20,
        child: CircularProgressIndicator(
          strokeWidth: 2,
          valueColor: AlwaysStoppedAnimation<Color>(_drawerTextColor),
        ),
      ),
    );
  }

  Widget _buildSearchConversationBody(List<_ConversationSearchResult> results) {
    if (results.isEmpty) {
      return _isSearching
          ? _buildSearchingConversationState()
          : _buildEmptySearchResult();
    }
    return ListView.builder(
      padding: EdgeInsets.only(bottom: widget.homePage ? 112 : 0),
      itemCount: results.length + 1,
      itemBuilder: (context, index) => index == 0
          ? _buildSearchResultHeader(results.length)
          : _buildSwipeConversationItem(
              results[index - 1],
              showDivider: index != results.length,
            ),
    );
  }

  Widget _buildConversationTimelineBody(
    List<_ConversationSearchResult> results,
  ) {
    final chatOnlyResults = <_ConversationSearchResult>[];
    final omniAiResults = <_ConversationSearchResult>[];
    for (final result in results) {
      final mode = result.conversation.mode;
      if (mode == ConversationMode.chatOnly && !widget.homePage) {
        chatOnlyResults.add(result);
      } else {
        // Agent、Xiaowan、SubAgent 和 OpenClaw 共用一个会话入口。
        // Agent 的具体 Harness 通过每条会话旁的身份标签区分，不再引入
        // Agent -> Workspace -> Conversation 的额外层级。
        omniAiResults.add(result);
      }
    }

    final sections = <Widget>[];
    void addSection(Widget section) {
      if (sections.isNotEmpty) {
        sections.add(const SliverToBoxAdapter(child: SizedBox(height: 12)));
      }
      sections.add(section);
    }

    final scheduledGroups = _scheduledConversationGroups
        .where((group) => _matchesHomeFilter(group.parent))
        .toList(growable: false);
    final pinnedResults = _pinnedConversationResults;
    if (scheduledGroups.isNotEmpty) {
      addSection(_buildScheduledConversationSection(scheduledGroups));
    }
    if (pinnedResults.isNotEmpty) {
      addSection(_buildPinnedConversationSection(pinnedResults));
    }
    if (omniAiResults.isNotEmpty) {
      // OmniAI/Xiaowan is the default chat history.  It does not need a
      // second navigation header above the date groups; showing one here
      // duplicates the Xiaowan entry already visible in the chat shell.
      addSection(
        _buildModeTimelineConversationEntries(
          dateSectionNamespace: _kOmniAiDateSectionNamespace,
          results: omniAiResults,
        ),
      );
    }
    if (chatOnlyResults.isNotEmpty) {
      addSection(
        _buildModeTimelineConversationSection(
          sectionKey: _kChatOnlyConversationSectionKey,
          label: context.l10n.homeDrawerChatOnlySection,
          iconAssetPath: _kChatOnlySectionIconAssetPath,
          dateSectionNamespace: _kChatOnlyDateSectionNamespace,
          results: chatOnlyResults,
        ),
      );
    }

    if (sections.isEmpty) {
      return _buildEmptyConversation();
    }
    // 所有区块共用同一个滚动列表：任何区块展开条目过多时整体滚动，不再溢出。
    return CustomScrollView(
      slivers: [
        ...sections,
        if (widget.homePage)
          const SliverToBoxAdapter(child: SizedBox(height: 112)),
      ],
    );
  }

  Widget _buildEmptyConversation() {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 24),
      child: Center(
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(
                context.l10n.chatHistoryEmpty,
                style: TextStyle(
                  fontSize: 14,
                  color: _drawerSecondaryTextColor,
                ),
              ),
              const SizedBox(height: 12),
              GestureDetector(
                onTap: _openNewConversation,
                child: Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 8,
                  ),
                  decoration: BoxDecoration(
                    color: context.omniPalette.accentPrimary,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    context.l10n.chatHistoryStartConversation,
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w500,
                      color: Theme.of(context).colorScheme.onPrimary,
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildSearchingConversationState() {
    return Center(
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            SizedBox(
              width: 22,
              height: 22,
              child: CircularProgressIndicator(
                strokeWidth: 2.2,
                valueColor: AlwaysStoppedAnimation<Color>(
                  context.omniPalette.accentPrimary,
                ),
              ),
            ),
            const SizedBox(height: 14),
            Text(
              context.l10n.homeDrawerSearching,
              style: TextStyle(
                fontSize: 14,
                color: _drawerSecondaryTextColor,
                fontFamily: 'PingFang SC',
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildEmptySearchResult() {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 24),
      child: Center(
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: context.isDarkTheme
                      ? palette.surfaceSecondary
                      : palette.previewFallback,
                  shape: BoxShape.circle,
                ),
                alignment: Alignment.center,
                child: Icon(
                  Icons.search_off_rounded,
                  size: 22,
                  color: palette.textSecondary,
                ),
              ),
              const SizedBox(height: 14),
              Text(
                context.l10n.homeDrawerNoResults,
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w500,
                  color: _drawerTextColor,
                  fontFamily: 'PingFang SC',
                ),
              ),
              const SizedBox(height: 6),
              Text(
                context.l10n.homeDrawerSearchHint2,
                style: TextStyle(
                  fontSize: 12,
                  color: _drawerSecondaryTextColor,
                  fontFamily: 'PingFang SC',
                ),
              ),
              const SizedBox(height: 14),
              GestureDetector(
                onTap: _searchController.clear,
                child: Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 14,
                    vertical: 8,
                  ),
                  decoration: BoxDecoration(
                    color: context.isDarkTheme
                        ? palette.surfaceSecondary
                        : Colors.white,
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(color: palette.borderSubtle),
                  ),
                  child: Text(
                    context.l10n.homeDrawerClearSearch,
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w500,
                      color: _drawerTextColor,
                      fontFamily: 'PingFang SC',
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildSearchResultHeader(int count) {
    final palette = context.omniPalette;
    return Padding(
      padding: EdgeInsets.fromLTRB(
        widget.conversationSidebar ? 16 : 4,
        0,
        widget.conversationSidebar ? 16 : 4,
        8,
      ),
      child: Row(
        children: [
          Icon(
            Icons.manage_search_rounded,
            size: 16,
            color: palette.textSecondary,
          ),
          const SizedBox(width: 6),
          Text(
            context.l10n.homeDrawerSearchResults,
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w600,
              letterSpacing: 0.6,
              color: palette.textTertiary,
              fontFamily: 'PingFang SC',
            ),
          ),
          const Spacer(),
          Text(
            '$count ${context.l10n.homeDrawerResultCount}',
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w500,
              color: palette.textTertiary,
              fontFamily: 'PingFang SC',
            ),
          ),
        ],
      ),
    );
  }

  List<Widget> _buildConversationDateSectionChildren(
    List<_ConversationSearchResult> results, {
    required String namespace,
  }) {
    final sections = _buildConversationSections(results, namespace: namespace);
    final children = <Widget>[];
    for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
      final section = sections[sectionIndex];
      if (children.isNotEmpty || sectionIndex > 0) {
        children.add(
          SliverToBoxAdapter(
            child: SizedBox(height: widget.homePage ? 22 : 14),
          ),
        );
      }
      children.add(_buildConversationDateSection(section));
    }
    return children;
  }

  List<_ConversationSection> _buildConversationSections(
    List<_ConversationSearchResult> results, {
    required String namespace,
  }) {
    final sections = <_ConversationSection>[];
    final now = DateTime.now();
    for (final result in results) {
      final conversation = result.conversation;
      final (group, label) = widget.homePage
          ? _homeConversationDateGroup(conversation.updatedDate, now)
          : (
              '${conversation.updatedDate.year}-${conversation.updatedDate.month}-${conversation.updatedDate.day}',
              conversation.timeDisplay,
            );
      final sectionKey = '__home_drawer_date__${namespace}__$group';
      if (sections.isEmpty || sections.last.sectionKey != sectionKey) {
        sections.add(
          _ConversationSection(
            label: label,
            sectionKey: sectionKey,
            results: <_ConversationSearchResult>[result],
          ),
        );
      } else {
        sections.last.results.add(result);
      }
    }
    return sections;
  }

  bool _isConversationSectionExpanded(String sectionKey) =>
      _expandedConversationSections[sectionKey] ?? true;

  void _toggleConversationSection(String sectionKey) {
    setState(() {
      _expandedConversationSections[sectionKey] =
          !_isConversationSectionExpanded(sectionKey);
    });
    _persistExpandedConversationSections();
  }

  (String, String) _homeConversationDateGroup(DateTime date, DateTime now) {
    final today = DateTime(now.year, now.month, now.day);
    final yesterday = DateTime(now.year, now.month, now.day - 1);
    final weekStart = DateTime(now.year, now.month, now.day - now.weekday + 1);
    final monthStart = DateTime(now.year, now.month);
    final english = Localizations.localeOf(context).languageCode == 'en';
    if (!date.isBefore(today)) return ('today', english ? 'Today' : '今天');
    if (!date.isBefore(yesterday)) {
      return ('yesterday', english ? 'Yesterday' : '昨天');
    }
    if (!date.isBefore(weekStart)) {
      return ('week', english ? 'This week' : '这周');
    }
    if (!date.isBefore(monthStart)) {
      return ('month', english ? 'This month' : '这个月');
    }
    return ('earlier', english ? 'Earlier' : '更久以前');
  }

  String _scheduledParentConversationSectionKey(ConversationModel parent) =>
      '__home_drawer_scheduled_${parent.threadKey}';

  void _restoreExpandedConversationSections() {
    _expandedConversationSections
      ..clear()
      ..addAll(_loadExpandedConversationSectionsFromStorage());
  }

  Map<String, bool> _loadExpandedConversationSectionsFromStorage() {
    final raw = StorageService.getString(
      _kExpandedConversationSectionsStorageKey,
    );
    if (raw == null || raw.trim().isEmpty) {
      return <String, bool>{};
    }
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map) {
        return <String, bool>{};
      }
      return <String, bool>{
        for (final entry in decoded.entries)
          if (entry.value is bool &&
              !entry.key.toString().startsWith('__home_drawer_date__'))
            entry.key.toString(): entry.value as bool,
      };
    } catch (error) {
      debugPrint('[HomeDrawer] Failed to load expanded sections: $error');
      return <String, bool>{};
    }
  }

  void _persistExpandedConversationSections() {
    final snapshot = Map<String, bool>.from(_expandedConversationSections);
    unawaited(
      StorageService.setString(
        _kExpandedConversationSectionsStorageKey,
        jsonEncode(snapshot),
      ),
    );
  }

  Widget _buildPinnedConversationSection(
    List<_ConversationSearchResult> results,
  ) {
    return _buildPromotedConversationSection(
      sectionKey: _kPinnedConversationSectionKey,
      label: context.l10n.homeDrawerPinnedConversations,
      itemCount: results.length,
      iconAssetPath: 'assets/home/pin_icon.svg',
      childrenLeadingInset: widget.homePage
          ? 0
          : _kPromotedConversationItemTitleInset,
      children: [
        SliverList.builder(
          itemCount: results.length,
          itemBuilder: (context, index) => _buildSwipeConversationItem(
            results[index],
            showDivider: index != results.length - 1,
          ),
        ),
      ],
    );
  }

  Widget _buildScheduledConversationSection(
    List<_ScheduledConversationGroup> groups,
  ) {
    final itemCount = groups.fold<int>(
      0,
      (count, group) => count + 1 + group.children.length,
    );
    return _buildPromotedConversationSection(
      sectionKey: _kScheduledConversationSectionKey,
      label: context.l10n.homeDrawerScheduledTasks,
      itemCount: itemCount,
      iconAssetPath: 'assets/common/schedule_icon.svg',
      childrenLeadingInset: 0,
      children: [
        for (int groupIndex = 0; groupIndex < groups.length; groupIndex++)
          _buildScheduledConversationGroup(
            groups[groupIndex],
            showDivider: groupIndex != groups.length - 1,
          ),
      ],
    );
  }

  Widget _buildModeTimelineConversationSection({
    required String sectionKey,
    required String label,
    required String iconAssetPath,
    required String dateSectionNamespace,
    required List<_ConversationSearchResult> results,
  }) {
    return _buildPromotedConversationSection(
      sectionKey: sectionKey,
      label: label,
      itemCount: results.length,
      iconAssetPath: iconAssetPath,
      childrenLeadingInset: _kModeSectionTimelineLeadingInset,
      children: _buildConversationDateSectionChildren(
        results,
        namespace: dateSectionNamespace,
      ),
    );
  }

  Widget _buildModeTimelineConversationEntries({
    required String dateSectionNamespace,
    required List<_ConversationSearchResult> results,
  }) {
    return SliverPadding(
      padding: EdgeInsets.only(
        left: widget.homePage ? 0 : _kModeSectionTimelineLeadingInset,
        top: 2,
      ),
      sliver: SliverMainAxisGroup(
        slivers: _buildConversationDateSectionChildren(
          results,
          namespace: dateSectionNamespace,
        ),
      ),
    );
  }

  Widget _buildPromotedConversationSection({
    required String sectionKey,
    required String label,
    required int itemCount,
    required String iconAssetPath,
    required double childrenLeadingInset,
    required List<Widget> children,
  }) {
    final expanded = _isConversationSectionExpanded(sectionKey);
    return SliverMainAxisGroup(
      slivers: [
        SliverToBoxAdapter(
          child: _buildConversationSectionHeader(
            label,
            expanded: expanded,
            itemCount: itemCount,
            onTap: () => _toggleConversationSection(sectionKey),
            iconAssetPath: iconAssetPath,
            iconKey: ValueKey('home-drawer-section-icon-$sectionKey'),
            leadingSlotWidth: _kConversationSectionHeaderLeadingSlotWidth,
          ),
        ),
        if (expanded)
          SliverPadding(
            padding: EdgeInsets.only(left: childrenLeadingInset, top: 2),
            sliver: SliverMainAxisGroup(slivers: children),
          ),
      ],
    );
  }

  Widget _buildScheduledConversationGroup(
    _ScheduledConversationGroup group, {
    required bool showDivider,
  }) {
    final parentSectionKey = _scheduledParentConversationSectionKey(
      group.parent,
    );
    final expanded = _isConversationSectionExpanded(parentSectionKey);
    return SliverMainAxisGroup(
      slivers: [
        SliverToBoxAdapter(
          child: _buildScheduledParentConversationRow(
            group,
            expanded: expanded,
            onToggle: () => _toggleConversationSection(parentSectionKey),
          ),
        ),
        if (expanded)
          SliverList.builder(
            itemCount: group.children.length,
            itemBuilder: (context, index) =>
                _buildScheduledChildConversationItem(
                  group.children[index],
                  showDivider: index != group.children.length - 1,
                ),
          ),
        if (showDivider) const SliverToBoxAdapter(child: SizedBox(height: 6)),
      ],
    );
  }

  Widget _buildScheduledParentConversationRow(
    _ScheduledConversationGroup group, {
    required bool expanded,
    required VoidCallback onToggle,
  }) {
    final palette = context.omniPalette;
    final title = _resolveConversationTitle(group.parent);
    final childCount = group.children.length;
    final countText = childCount > 0 ? '$childCount' : '${group.taskCount}';
    final isEditing = _editingThreadKey == group.parent.threadKey;
    final row = Builder(
      builder: (rowContext) => Semantics(
        key: ValueKey('home-current-thread-${group.parent.threadKey}'),
        selected: _isSelectingConversations
            ? _selectedConversationKeys.contains(group.parent.threadKey)
            : widget.selectedThreadTarget == null
            ? null
            : _isCurrentConversationRow(group.parent),
        child: Opacity(
          key: ValueKey(
            'home-context-source-visibility-${group.parent.threadKey}',
          ),
          opacity:
              widget.homePage &&
                  _activeContextMenuThreadKey == group.parent.threadKey
              ? 0
              : 1,
          child: GestureDetector(
            key: ValueKey('home-source-row-${group.parent.threadKey}'),
            onLongPressStart: isEditing || _isSelectingConversations
                ? null
                : (details) {
                    if (widget.homePage) {
                      _showConversationContextMenu(
                        rowContext,
                        group.parent,
                        pressPosition: details.globalPosition,
                      );
                    } else {
                      _startEditingTitle(group.parent);
                    }
                  },
            child: Material(
              color: _isCurrentConversationRow(group.parent)
                  ? palette.accentPrimary.withValues(alpha: 0.08)
                  : Colors.transparent,
              borderRadius: BorderRadius.circular(12),
              child: InkWell(
                onTap: isEditing
                    ? null
                    : () => _openConversationFromDrawer(group.parent),
                borderRadius: BorderRadius.circular(8),
                splashColor: palette.accentPrimary.withValues(alpha: 0.08),
                highlightColor: Colors.transparent,
                child: widget.homePage
                    ? Padding(
                        padding: EdgeInsets.symmetric(
                          horizontal: widget.conversationSidebar ? 12 : 0,
                        ),
                        child: Row(
                          children: [
                            Expanded(
                              child: HomeConversationTileContent(
                                conversation: group.parent,
                                avatarColorIndex:
                                    _homeAvatarColorIndices[group
                                        .parent
                                        .threadKey],
                                title: title,
                                titleWidget: isEditing
                                    ? _buildEditableConversationTitle(
                                        title: title,
                                        isEditing: true,
                                      )
                                    : null,
                                leading: _isSelectingConversations
                                    ? _buildConversationSelectionIndicator(
                                        group.parent,
                                      )
                                    : null,
                                isExecuting: _runtimeCoordinator
                                    .isAgentConversationActive(group.parent.id),
                              ),
                            ),
                            IconButton(
                              key: ValueKey(
                                'home-scheduled-toggle-${group.parent.threadKey}',
                              ),
                              tooltip:
                                  '${expanded ? context.trLegacy('收起') : context.trLegacy('展开')} · $countText',
                              onPressed: onToggle,
                              visualDensity: VisualDensity.compact,
                              icon: AnimatedRotation(
                                turns: expanded ? 0 : -0.25,
                                duration:
                                    HomeDrawerState._sectionToggleDuration,
                                child: Icon(
                                  LucideIcons.chevronDown,
                                  size: 16,
                                  color: palette.textTertiary,
                                ),
                              ),
                            ),
                          ],
                        ),
                      )
                    : Stack(
                        children: [
                          Padding(
                            padding: const EdgeInsets.fromLTRB(0, 8, 2, 8),
                            child: Row(
                              crossAxisAlignment: CrossAxisAlignment.center,
                              children: [
                                if (_isSelectingConversations)
                                  _buildConversationSelectionIndicator(
                                    group.parent,
                                  ),
                                SizedBox(
                                  width: _kScheduledParentToggleHitWidth,
                                  height: 24,
                                  child: Align(
                                    alignment: Alignment.centerRight,
                                    child: SizedBox(
                                      width:
                                          _kScheduledParentToggleIconSlotWidth,
                                      height: 24,
                                      child: Center(
                                        child: AnimatedRotation(
                                          turns: expanded ? 0 : -0.25,
                                          duration: HomeDrawerState
                                              ._sectionToggleDuration,
                                          curve:
                                              Curves.easeInOutCubicEmphasized,
                                          child: SvgPicture.asset(
                                            'assets/common/chevron-down.svg',
                                            width: 16,
                                            height: 16,
                                            colorFilter: ColorFilter.mode(
                                              palette.textTertiary,
                                              BlendMode.srcIn,
                                            ),
                                          ),
                                        ),
                                      ),
                                    ),
                                  ),
                                ),
                                const SizedBox(width: 2),
                                Expanded(
                                  child: _buildEditableConversationTitle(
                                    title: title,
                                    isEditing: isEditing,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                                const SizedBox(width: 8),
                                Text(
                                  countText,
                                  style: TextStyle(
                                    fontSize: 11,
                                    fontWeight: FontWeight.w500,
                                    color: palette.textTertiary,
                                    fontFamily: 'PingFang SC',
                                  ),
                                ),
                              ],
                            ),
                          ),
                          Positioned(
                            left: _isSelectingConversations ? 34 : 0,
                            top: 0,
                            bottom: 0,
                            width: _kScheduledParentToggleHitWidth,
                            child: GestureDetector(
                              behavior: HitTestBehavior.opaque,
                              onTap: onToggle,
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
    return _wrapHomeConversationSwipe(group.parent, child: row);
  }

  Widget _buildScheduledChildConversationItem(
    _ConversationSearchResult result, {
    required bool showDivider,
  }) {
    return Padding(
      padding: const EdgeInsets.only(
        left: _kScheduledChildConversationItemInset,
      ),
      child: _buildSwipeConversationItem(
        result,
        showDivider: showDivider,
        includePinAction: false,
      ),
    );
  }

  Widget _buildConversationDateSection(_ConversationSection section) {
    return SliverMainAxisGroup(
      slivers: [
        SliverToBoxAdapter(
          child: Semantics(
            header: true,
            child: Padding(
              key: ValueKey('home-date-heading-${section.sectionKey}'),
              padding: EdgeInsets.fromLTRB(
                widget.conversationSidebar ? 12 : (widget.homePage ? 0 : 4),
                12,
                widget.conversationSidebar ? 16 : 4,
                8,
              ),
              child: Text(
                section.label,
                style: TextStyle(
                  fontSize: widget.homePage ? 15 : 11.5,
                  fontWeight: FontWeight.w600,
                  color: context.omniPalette.textTertiary,
                ),
              ),
            ),
          ),
        ),
        SliverPadding(
          padding: const EdgeInsets.only(top: 4),
          sliver: SliverList.builder(
            itemCount: section.results.length,
            itemBuilder: (context, index) => _buildSwipeConversationItem(
              section.results[index],
              showDivider: index != section.results.length - 1,
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildConversationSectionHeader(
    String label, {
    required bool expanded,
    required int itemCount,
    required VoidCallback onTap,
    String? iconAssetPath,
    Key? iconKey,
    double leadingSlotWidth = 0,
    bool showExpandChevron = false,
  }) {
    final palette = context.omniPalette;
    final headerContent = Semantics(
      button: true,
      toggled: expanded,
      onTap: onTap,
      child: Container(
        constraints: BoxConstraints(minHeight: widget.homePage ? 44 : 28),
        alignment: Alignment.centerLeft,
        padding: EdgeInsets.symmetric(
          horizontal: widget.conversationSidebar ? 16 : 4,
          vertical: 5,
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            if (leadingSlotWidth > 0)
              SizedBox(
                width: leadingSlotWidth,
                height: 14,
                child: iconAssetPath == null
                    ? null
                    : Align(
                        alignment: Alignment.centerLeft,
                        child: SvgPicture.asset(
                          iconAssetPath,
                          key: iconKey,
                          width: 14,
                          height: 14,
                          colorFilter: ColorFilter.mode(
                            palette.textTertiary,
                            BlendMode.srcIn,
                          ),
                        ),
                      ),
              ),
            Text(
              label,
              style: TextStyle(
                fontSize: widget.homePage ? 14 : 11.5,
                fontWeight: widget.homePage ? FontWeight.w400 : FontWeight.w600,
                letterSpacing: 0.2,
                color: palette.textTertiary,
                fontFamily: 'PingFang SC',
              ),
            ),
            const SizedBox(width: 6),
            if (!widget.homePage)
              Text(
                '$itemCount',
                style: TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w500,
                  color: palette.textTertiary.withValues(alpha: 0.8),
                  fontFamily: 'PingFang SC',
                ),
              ),
            if (showExpandChevron) ...[
              const SizedBox(width: 4),
              AnimatedRotation(
                turns: expanded ? 0 : -0.25,
                duration: HomeDrawerState._sectionToggleDuration,
                curve: Curves.easeInOutCubicEmphasized,
                child: Icon(
                  LucideIcons.chevronDown,
                  size: widget.homePage ? 16 : 13,
                  color: palette.textTertiary.withValues(alpha: 0.8),
                ),
              ),
            ],
            const Spacer(),
          ],
        ),
      ),
    );
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: () {
            performOmniSelectionHaptic();
            onTap();
          },
          borderRadius: BorderRadius.circular(10),
          splashFactory: NoSplash.splashFactory,
          splashColor: Colors.transparent,
          highlightColor: Colors.transparent,
          focusColor: Colors.transparent,
          hoverColor: Colors.transparent,
          child: headerContent,
        ),
      ),
    );
  }

  Widget _buildSectionActionButton({
    required String iconPath,
    required String tooltip,
    required VoidCallback onTap,
    bool isPrimary = false,
  }) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    return Tooltip(
      message: tooltip,
      child: Material(
        color: isPrimary
            ? palette.accentPrimary
            : isDark
            ? palette.surfacePrimary
            : Colors.white,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: isPrimary
              ? BorderSide.none
              : BorderSide(
                  color: palette.borderSubtle.withValues(
                    alpha: isDark ? 0.65 : 0.85,
                  ),
                  width: 0.75,
                ),
        ),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: () {
            performOmniSelectionHaptic();
            onTap();
          },
          child: SizedBox(
            width: 38,
            height: 38,
            child: Center(
              child: SvgPicture.asset(
                iconPath,
                width: 16,
                height: 16,
                colorFilter: ColorFilter.mode(
                  isPrimary
                      ? Theme.of(context).colorScheme.onPrimary
                      : _drawerTextColor,
                  BlendMode.srcIn,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildSwipeConversationItem(
    _ConversationSearchResult result, {
    required bool showDivider,
    bool includePinAction = true,
    String? trailingLabel,
  }) {
    final palette = context.omniPalette;
    final conversation = result.conversation;
    final isBusy = _busyConversationKeys.contains(conversation.threadKey);
    final title = _resolveConversationTitle(conversation);
    final agentLabel = _conversationAgentLabel(conversation);
    final isExecuting = _runtimeCoordinator.isAgentConversationActive(
      conversation.id,
    );
    final isCompleted = !isExecuting && conversation.isCompleted;
    final showArchivedBadge = _isSearchActive && conversation.isArchived;
    final isEditing = _editingThreadKey == conversation.threadKey;

    final row = Semantics(
      key: ValueKey('home-current-thread-${conversation.threadKey}'),
      selected: _isSelectingConversations
          ? _selectedConversationKeys.contains(conversation.threadKey)
          : widget.selectedThreadTarget == null
          ? null
          : _isCurrentConversationRow(conversation),
      child: IgnorePointer(
        key: ValueKey(conversation.threadKey),
        ignoring: isBusy,
        child: AnimatedOpacity(
          duration: const Duration(milliseconds: 180),
          opacity: isBusy && !widget.homePage ? 0.72 : 1,
          child: Column(
            children: [
              Builder(
                builder: (rowContext) => Opacity(
                  key: ValueKey(
                    'home-context-source-visibility-${conversation.threadKey}',
                  ),
                  opacity:
                      widget.homePage &&
                          _activeContextMenuThreadKey == conversation.threadKey
                      ? 0
                      : 1,
                  child: GestureDetector(
                    key: ValueKey('home-source-row-${conversation.threadKey}'),
                    onLongPressStart: isEditing || _isSelectingConversations
                        ? null
                        : (details) => _showConversationContextMenu(
                            rowContext,
                            conversation,
                            pressPosition: details.globalPosition,
                            includePinAction: includePinAction,
                            previewOverride: _isSearchActive
                                ? result.matchedPreview
                                : null,
                            showArchivedBadge: showArchivedBadge,
                          ),
                    child: Material(
                      color: _isCurrentConversationRow(conversation)
                          ? palette.accentPrimary.withValues(alpha: 0.08)
                          : Colors.transparent,
                      borderRadius: BorderRadius.circular(12),
                      child: InkWell(
                        onTap: isEditing
                            ? null
                            : () => _openConversationFromDrawer(conversation),
                        borderRadius: BorderRadius.circular(12),
                        splashColor: palette.accentPrimary.withValues(
                          alpha: 0.08,
                        ),
                        highlightColor: palette.accentPrimary.withValues(
                          alpha: 0.04,
                        ),
                        child: widget.homePage
                            ? Padding(
                                padding: EdgeInsets.symmetric(
                                  horizontal: widget.conversationSidebar
                                      ? 12
                                      : 0,
                                ),
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    HomeConversationTileContent(
                                      conversation: conversation,
                                      avatarColorIndex:
                                          _homeAvatarColorIndices[conversation
                                              .threadKey],
                                      title: title,
                                      titleWidget: isEditing
                                          ? _buildEditableConversationTitle(
                                              title: title,
                                              isEditing: true,
                                            )
                                          : null,
                                      previewOverride: _isSearchActive
                                          ? result.matchedPreview
                                          : null,
                                      leading: _isSelectingConversations
                                          ? _buildConversationSelectionIndicator(
                                              conversation,
                                            )
                                          : null,
                                      badge: showArchivedBadge
                                          ? _buildArchivedBadge()
                                          : null,
                                      isExecuting: isExecuting,
                                    ),
                                    Padding(
                                      padding: const EdgeInsets.only(left: 58),
                                      child:
                                          _buildConversationImagePreviewStrip(
                                            conversation,
                                          ),
                                    ),
                                  ],
                                ),
                              )
                            : Padding(
                                padding: const EdgeInsets.fromLTRB(4, 9, 2, 9),
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Row(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.center,
                                      children: [
                                        if (_isSelectingConversations)
                                          _buildConversationSelectionIndicator(
                                            conversation,
                                          ),
                                        Expanded(
                                          child:
                                              _buildEditableConversationTitle(
                                                title: title,
                                                isEditing: isEditing,
                                              ),
                                        ),
                                        if (showArchivedBadge) ...[
                                          const SizedBox(width: 10),
                                          _buildArchivedBadge(),
                                        ],
                                        if (agentLabel != null &&
                                            !isEditing) ...[
                                          const SizedBox(width: 10),
                                          _buildConversationAgentBadge(
                                            conversation: conversation,
                                            agentLabel: agentLabel,
                                            isExecuting: isExecuting,
                                            isCompleted: isCompleted,
                                          ),
                                        ],
                                        if (trailingLabel != null &&
                                            !isEditing) ...[
                                          const SizedBox(width: 10),
                                          Text(
                                            trailingLabel,
                                            style: TextStyle(
                                              fontSize: 11,
                                              fontWeight: FontWeight.w400,
                                              color: palette.textTertiary,
                                              fontFamily: 'PingFang SC',
                                            ),
                                          ),
                                        ],
                                      ],
                                    ),
                                    _buildConversationImagePreviewStrip(
                                      conversation,
                                    ),
                                    if (_isSearchActive &&
                                        result.matchedPreview != null) ...[
                                      const SizedBox(height: 4),
                                      Text(
                                        result.matchedPreview!,
                                        maxLines: 2,
                                        overflow: TextOverflow.ellipsis,
                                        style: TextStyle(
                                          fontSize: 12,
                                          fontWeight: FontWeight.w400,
                                          color: _drawerSecondaryTextColor,
                                          height: 1.4,
                                          fontFamily: 'PingFang SC',
                                        ),
                                      ),
                                    ],
                                  ],
                                ),
                              ),
                      ),
                    ),
                  ),
                ),
              ),
              if (showDivider) const SizedBox(height: 2),
            ],
          ),
        ),
      ),
    );
    return _wrapHomeConversationSwipe(conversation, child: row);
  }

  Widget _wrapHomeConversationSwipe(
    ConversationModel conversation, {
    required Widget child,
  }) {
    if (!widget.homePage ||
        _isSelectingConversations ||
        _editingThreadKey == conversation.threadKey) {
      return child;
    }

    final palette = context.omniPalette;
    void toggleArchive() => conversation.isArchived
        ? _unarchiveConversation(conversation)
        : _archiveConversation(conversation);

    return ConversationSlidable(
      key: ValueKey('home-swipe-${conversation.threadKey}'),
      itemKey: conversation.threadKey,
      groupTag: 'home-conversations',
      isBusy: _busyConversationKeys.contains(conversation.threadKey),
      followHabitualHand: false,
      resetAfterFullSwipe: true,
      onDismissed: toggleArchive,
      onFullSwipe: toggleArchive,
      actions: [
        ConversationSlideAction(
          onPressed: () => _deleteConversation(conversation),
          backgroundColor: AppColors.alertRed,
          child: Tooltip(
            message: context.trLegacy('删除对话'),
            child: Center(
              child: SvgPicture.asset(
                'assets/memory/memory_delete.svg',
                width: 18,
                height: 18,
                colorFilter: const ColorFilter.mode(
                  Colors.white,
                  BlendMode.srcIn,
                ),
              ),
            ),
          ),
        ),
        ConversationSlideAction(
          onPressed: toggleArchive,
          backgroundColor: context.isDarkTheme
              ? Color.lerp(palette.surfaceElevated, palette.accentPrimary, 0.3)!
              : AppColors.buttonPrimary,
          borderRadius: const BorderRadius.only(
            topRight: Radius.circular(4),
            bottomRight: Radius.circular(4),
          ),
          child: Tooltip(
            message: conversation.isArchived
                ? (LegacyTextLocalizer.isEnglish ? 'Unarchive' : '取消归档')
                : (LegacyTextLocalizer.isEnglish ? 'Archive' : '归档'),
            child: Center(
              child: conversation.isArchived
                  ? const Icon(
                      Icons.unarchive_outlined,
                      size: 18,
                      color: Colors.white,
                    )
                  : SvgPicture.asset(
                      'assets/home/archive_icon.svg',
                      width: 18,
                      height: 18,
                      colorFilter: const ColorFilter.mode(
                        Colors.white,
                        BlendMode.srcIn,
                      ),
                    ),
            ),
          ),
        ),
      ],
      child: child,
    );
  }

  String? _conversationAgentLabel(ConversationModel conversation) {
    if (conversation.mode != ConversationMode.agent) {
      return null;
    }
    final agentId = AgentBrandIcon.normalizeAgentId(
      conversation.agentId ?? 'xiaowan-acp',
    );
    if (agentId == 'xiaowan-acp') {
      return LegacyTextLocalizer.localize('小万');
    }
    return switch (agentId) {
      'kimi-code-acp' => 'Kimi Code',
      'codex-acp' || 'codex-remote' => 'Codex',
      'claude-code-acp' => 'Claude Code',
      'opencode-acp' => 'OpenCode',
      'pi-acp' => 'Pi',
      'deepseek-harness-acp' => 'DeepSeek',
      _ => _humanizeAgentId(agentId),
    };
  }

  Widget _buildConversationAgentBadge({
    required ConversationModel conversation,
    required String agentLabel,
    required bool isExecuting,
    required bool isCompleted,
  }) {
    final statusLabel = isExecuting
        ? '$agentLabel，执行中'
        : isCompleted
        ? '$agentLabel，已完成'
        : agentLabel;
    return Semantics(
      label: statusLabel,
      child: Tooltip(
        message: isExecuting
            ? '$agentLabel · 执行中'
            : isCompleted
            ? '$agentLabel · 已完成'
            : agentLabel,
        child: SizedBox(
          key: isExecuting
              ? ValueKey('home-drawer-running-${conversation.threadKey}')
              : isCompleted
              ? ValueKey('home-drawer-completed-${conversation.threadKey}')
              : null,
          width: 18,
          height: 18,
          child: Stack(
            clipBehavior: Clip.none,
            alignment: Alignment.center,
            children: [
              AgentBrandIcon(
                agentId: conversation.agentId ?? 'xiaowan-acp',
                size: 16,
              ),
              if (isExecuting || isCompleted)
                Positioned(
                  right: -1,
                  bottom: -1,
                  child: Container(
                    width: 7,
                    height: 7,
                    decoration: BoxDecoration(
                      color: isExecuting
                          ? context.omniPalette.accentPrimary
                          : Colors.transparent,
                      shape: BoxShape.circle,
                      border: Border.all(
                        color: isExecuting
                            ? context.omniPalette.pageBackground
                            : context.omniPalette.textTertiary,
                        width: isExecuting ? 1 : 1.2,
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  String _humanizeAgentId(String agentId) {
    final normalized = agentId
        .replaceFirst(RegExp(r'-harness-acp$'), '')
        .replaceFirst(RegExp(r'-acp$'), '')
        .replaceFirst(RegExp(r'-remote$'), '')
        .replaceAll(RegExp(r'[-_]+'), ' ')
        .trim();
    if (normalized.isEmpty) {
      return 'Agent';
    }
    return normalized
        .split(RegExp(r'\s+'))
        .map(
          (word) => word.isEmpty
              ? word
              : '${word[0].toUpperCase()}${word.substring(1)}',
        )
        .join(' ');
  }

  Widget _buildEditableConversationTitle({
    required String title,
    required bool isEditing,
    FontWeight fontWeight = FontWeight.w500,
  }) {
    if (isEditing) {
      return TextField(
        controller: _titleEditingController,
        focusNode: _titleEditingFocusNode,
        maxLines: 1,
        cursorColor: _drawerTextColor.withValues(alpha: 0.6),
        cursorWidth: 1.5,
        style: TextStyle(
          fontSize: widget.homePage ? 16 : 13,
          fontWeight: widget.homePage ? FontWeight.w600 : fontWeight,
          color: _drawerTextColor,
          height: 1.35,
          fontFamily: 'PingFang SC',
        ),
        decoration: const InputDecoration(
          isDense: true,
          contentPadding: EdgeInsets.zero,
          border: InputBorder.none,
          focusedBorder: InputBorder.none,
          enabledBorder: InputBorder.none,
          disabledBorder: InputBorder.none,
          errorBorder: InputBorder.none,
          focusedErrorBorder: InputBorder.none,
        ),
        onTapOutside: (_) => _commitTitleEdit(),
        onSubmitted: _commitTitleEdit,
      );
    }

    return Text(
      title,
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: TextStyle(
        fontSize: widget.homePage ? 16 : 13,
        fontWeight: widget.homePage ? FontWeight.w600 : fontWeight,
        color: _drawerTextColor,
        height: 1.35,
        fontFamily: 'PingFang SC',
      ),
    );
  }

  Widget _buildArchivedBadge() {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? palette.surfaceSecondary
            : palette.previewFallback,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.archive_outlined, size: 11, color: palette.textSecondary),
          const SizedBox(width: 4),
          Text(
            context.trLegacy('已归档'),
            style: TextStyle(
              fontSize: 10,
              fontWeight: FontWeight.w600,
              color: palette.textSecondary,
              fontFamily: 'PingFang SC',
            ),
          ),
        ],
      ),
    );
  }
}
