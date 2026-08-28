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
const List<String> _kDateHeaderIconAssetPaths = <String>[
  'assets/home/date_header_icons/amphora.svg',
  'assets/home/date_header_icons/apple.svg',
  'assets/home/date_header_icons/banana.svg',
  'assets/home/date_header_icons/barrel.svg',
  'assets/home/date_header_icons/bean.svg',
  'assets/home/date_header_icons/beef.svg',
  'assets/home/date_header_icons/beer.svg',
  'assets/home/date_header_icons/bird.svg',
  'assets/home/date_header_icons/birdhouse.svg',
  'assets/home/date_header_icons/blender.svg',
  'assets/home/date_header_icons/bone.svg',
  'assets/home/date_header_icons/bottle-wine.svg',
  'assets/home/date_header_icons/broccoli.svg',
  'assets/home/date_header_icons/bug-play.svg',
  'assets/home/date_header_icons/bug.svg',
  'assets/home/date_header_icons/cake-slice.svg',
  'assets/home/date_header_icons/cake.svg',
  'assets/home/date_header_icons/candy-cane.svg',
  'assets/home/date_header_icons/candy.svg',
  'assets/home/date_header_icons/carrot.svg',
  'assets/home/date_header_icons/cat.svg',
  'assets/home/date_header_icons/chef-hat.svg',
  'assets/home/date_header_icons/cherry.svg',
  'assets/home/date_header_icons/citrus.svg',
  'assets/home/date_header_icons/coffee.svg',
  'assets/home/date_header_icons/cookie.svg',
  'assets/home/date_header_icons/cooking-pot.svg',
  'assets/home/date_header_icons/croissant.svg',
  'assets/home/date_header_icons/cuboid.svg',
  'assets/home/date_header_icons/cup-soda.svg',
  'assets/home/date_header_icons/dessert.svg',
  'assets/home/date_header_icons/dog.svg',
  'assets/home/date_header_icons/donut.svg',
  'assets/home/date_header_icons/drumstick.svg',
  'assets/home/date_header_icons/egg-fried.svg',
  'assets/home/date_header_icons/egg.svg',
  'assets/home/date_header_icons/fish-symbol.svg',
  'assets/home/date_header_icons/fish.svg',
  'assets/home/date_header_icons/glass-water.svg',
  'assets/home/date_header_icons/grape.svg',
  'assets/home/date_header_icons/ham.svg',
  'assets/home/date_header_icons/hamburger.svg',
  'assets/home/date_header_icons/hand-platter.svg',
  'assets/home/date_header_icons/hop.svg',
  'assets/home/date_header_icons/ice-cream-bowl.svg',
  'assets/home/date_header_icons/ice-cream-cone.svg',
  'assets/home/date_header_icons/leafy-green.svg',
  'assets/home/date_header_icons/lollipop.svg',
  'assets/home/date_header_icons/martini.svg',
  'assets/home/date_header_icons/microwave.svg',
  'assets/home/date_header_icons/milk.svg',
  'assets/home/date_header_icons/nut.svg',
  'assets/home/date_header_icons/origami.svg',
  'assets/home/date_header_icons/panda.svg',
  'assets/home/date_header_icons/paw-print.svg',
  'assets/home/date_header_icons/pizza.svg',
  'assets/home/date_header_icons/popcorn.svg',
  'assets/home/date_header_icons/popsicle.svg',
  'assets/home/date_header_icons/rabbit.svg',
  'assets/home/date_header_icons/rat.svg',
  'assets/home/date_header_icons/refrigerator.svg',
  'assets/home/date_header_icons/salad.svg',
  'assets/home/date_header_icons/sandwich.svg',
  'assets/home/date_header_icons/shell.svg',
  'assets/home/date_header_icons/shrimp.svg',
  'assets/home/date_header_icons/snail.svg',
  'assets/home/date_header_icons/soup.svg',
  'assets/home/date_header_icons/squirrel.svg',
  'assets/home/date_header_icons/torus.svg',
  'assets/home/date_header_icons/tractor.svg',
  'assets/home/date_header_icons/turtle.svg',
  'assets/home/date_header_icons/utensils-crossed.svg',
  'assets/home/date_header_icons/utensils.svg',
  'assets/home/date_header_icons/vegan.svg',
  'assets/home/date_header_icons/wheat.svg',
  'assets/home/date_header_icons/wine.svg',
  'assets/home/date_header_icons/worm.svg',
];

extension _HomeDrawerConversationList on HomeDrawerState {
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
    return SlidableAutoCloseBehavior(
      child: ListView(
        padding: EdgeInsets.zero,
        children: _buildSearchResultChildren(results),
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
      if (mode == ConversationMode.chatOnly) {
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
        sections.add(const SizedBox(height: 12));
      }
      sections.add(section);
    }

    final scheduledGroups = _scheduledConversationGroups;
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
    return SlidableAutoCloseBehavior(
      child: ListView(padding: EdgeInsets.zero, children: sections),
    );
  }

  Widget _buildEmptyConversation() {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 24),
      child: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              context.l10n.chatHistoryEmpty,
              style: TextStyle(fontSize: 14, color: _drawerSecondaryTextColor),
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
    );
  }

  Widget _buildSearchingConversationState() {
    return Center(
      child: Column(
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
    );
  }

  Widget _buildEmptySearchResult() {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 24),
      child: Center(
        child: Column(
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
    );
  }

  List<Widget> _buildSearchResultChildren(
    List<_ConversationSearchResult> results,
  ) {
    final palette = context.omniPalette;
    final children = <Widget>[
      Padding(
        padding: const EdgeInsets.fromLTRB(4, 0, 4, 8),
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
              '${results.length} ${context.l10n.homeDrawerResultCount}',
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
    ];

    for (int index = 0; index < results.length; index++) {
      children.add(
        _buildSwipeConversationItem(
          results[index],
          showDivider: index != results.length - 1,
        ),
      );
    }
    return children;
  }

  List<Widget> _buildConversationDateSectionChildren(
    List<_ConversationSearchResult> results, {
    required String namespace,
  }) {
    final sections = _buildConversationSections(results, namespace: namespace);
    final children = <Widget>[];
    final usedIconIndexes = <int>{};
    for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
      final section = sections[sectionIndex];
      if (children.isNotEmpty || sectionIndex > 0) {
        children.add(const SizedBox(height: 14));
      }
      children.add(
        _buildConversationDateSection(
          section,
          iconAssetPath: _dateSectionIconAssetPath(
            section.sectionKey,
            usedIconIndexes: usedIconIndexes,
          ),
        ),
      );
    }
    return children;
  }

  String _dateSectionIconAssetPath(
    String sectionKey, {
    required Set<int> usedIconIndexes,
  }) {
    var iconIndex =
        _stablePositiveHash(sectionKey) % _kDateHeaderIconAssetPaths.length;
    if (usedIconIndexes.length < _kDateHeaderIconAssetPaths.length) {
      while (!usedIconIndexes.add(iconIndex)) {
        iconIndex = (iconIndex + 1) % _kDateHeaderIconAssetPaths.length;
      }
    }
    return _kDateHeaderIconAssetPaths[iconIndex];
  }

  int _stablePositiveHash(String value) {
    var hash = 0x811c9dc5;
    for (final codeUnit in value.codeUnits) {
      hash ^= codeUnit;
      hash = (hash * 0x01000193) & 0x7fffffff;
    }
    return hash;
  }

  List<_ConversationSection> _buildConversationSections(
    List<_ConversationSearchResult> results, {
    required String namespace,
  }) {
    final sections = <_ConversationSection>[];
    for (final result in results) {
      final conversation = result.conversation;
      final label = conversation.timeDisplay;
      final sectionKey = _dateConversationSectionKeyForConversation(
        conversation,
        namespace: namespace,
      );
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

  String _dateConversationSectionKeyForConversation(
    ConversationModel conversation, {
    required String namespace,
  }) {
    final date = conversation.updatedDate;
    final year = date.year.toString().padLeft(4, '0');
    final month = date.month.toString().padLeft(2, '0');
    final day = date.day.toString().padLeft(2, '0');
    return '__home_drawer_date__${namespace}__$year-$month-$day';
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
          if (entry.value is bool) entry.key.toString(): entry.value as bool,
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
      childrenLeadingInset: _kPromotedConversationItemTitleInset,
      children: [
        for (int itemIndex = 0; itemIndex < results.length; itemIndex++)
          _buildSwipeConversationItem(
            results[itemIndex],
            showDivider: itemIndex != results.length - 1,
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
    return Padding(
      padding: const EdgeInsets.only(left: _kModeSectionTimelineLeadingInset),
      child: Column(
        children: [
          const SizedBox(height: 2),
          ..._buildConversationDateSectionChildren(
            results,
            namespace: dateSectionNamespace,
          ),
        ],
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
    final items = Padding(
      padding: EdgeInsets.only(left: childrenLeadingInset),
      child: Column(children: [const SizedBox(height: 2), ...children]),
    );
    return Column(
      children: [
        _buildConversationSectionHeader(
          label,
          expanded: expanded,
          itemCount: itemCount,
          onTap: () => _toggleConversationSection(sectionKey),
          iconAssetPath: iconAssetPath,
          iconKey: ValueKey('home-drawer-section-icon-$sectionKey'),
          leadingSlotWidth: _kConversationSectionHeaderLeadingSlotWidth,
        ),
        _buildCollapsibleSectionBody(expanded: expanded, child: items),
      ],
    );
  }

  Widget _buildCollapsibleSectionBody({
    required bool expanded,
    required Widget child,
  }) {
    return TweenAnimationBuilder<double>(
      tween: Tween<double>(begin: expanded ? 1 : 0, end: expanded ? 1 : 0),
      duration: HomeDrawerState._sectionToggleDuration,
      curve: Curves.easeInOutCubicEmphasized,
      builder: (context, value, animatedChild) {
        return ClipRect(
          child: Align(
            alignment: Alignment.topCenter,
            heightFactor: value,
            child: Opacity(
              opacity: value.clamp(0.0, 1.0).toDouble(),
              child: IgnorePointer(
                ignoring: value < 0.99,
                child: animatedChild,
              ),
            ),
          ),
        );
      },
      child: child,
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
    final children = Column(
      children: [
        for (
          int childIndex = 0;
          childIndex < group.children.length;
          childIndex++
        )
          _buildScheduledChildConversationItem(
            group.children[childIndex],
            showDivider: childIndex != group.children.length - 1,
          ),
      ],
    );

    return Column(
      children: [
        _buildScheduledParentConversationRow(
          group,
          expanded: expanded,
          onToggle: () => _toggleConversationSection(parentSectionKey),
        ),
        _buildCollapsibleSectionBody(expanded: expanded, child: children),
        if (showDivider) const SizedBox(height: 6),
      ],
    );
  }

  /// 参考项目侧边栏的紧凑相对时间：今天 / N 天 / N 周 / N 个月 / N 年。
  String _conversationRelativeTimeLabel(ConversationModel conversation) {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final updated = conversation.updatedDate;
    final updatedDay = DateTime(updated.year, updated.month, updated.day);
    final days = today.difference(updatedDay).inDays;
    final isEnglish = LegacyTextLocalizer.isEnglish;
    if (days <= 0) {
      return isEnglish ? 'Today' : '今天';
    }
    if (days < 7) {
      return isEnglish ? '${days}d' : '$days 天';
    }
    if (days < 30) {
      final weeks = days ~/ 7;
      return isEnglish ? '${weeks}w' : '$weeks 周';
    }
    if (days < 365) {
      final months = days ~/ 30;
      return isEnglish ? '${months}mo' : '$months 个月';
    }
    final years = days ~/ 365;
    return isEnglish ? '${years}y' : '$years 年';
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
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: isEditing
            ? null
            : () => _openConversationFromDrawer(group.parent),
        onLongPress: isEditing ? null : () => _startEditingTitle(group.parent),
        borderRadius: BorderRadius.circular(8),
        splashColor: palette.accentPrimary.withValues(alpha: 0.08),
        highlightColor: Colors.transparent,
        child: Stack(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(0, 8, 2, 8),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  SizedBox(
                    width: _kScheduledParentToggleHitWidth,
                    height: 24,
                    child: Align(
                      alignment: Alignment.centerRight,
                      child: SizedBox(
                        width: _kScheduledParentToggleIconSlotWidth,
                        height: 24,
                        child: Center(
                          child: AnimatedRotation(
                            turns: expanded ? 0 : -0.25,
                            duration: HomeDrawerState._sectionToggleDuration,
                            curve: Curves.easeInOutCubicEmphasized,
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
              left: 0,
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
    );
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

  Widget _buildConversationDateSection(
    _ConversationSection section, {
    required String iconAssetPath,
  }) {
    final expanded = _isConversationSectionExpanded(section.sectionKey);
    // 会话标题不再相对日期行缩进：条目与日期分组行共用同一左缘。
    final items = Column(
      children: [
        const SizedBox(height: 4),
        for (int itemIndex = 0; itemIndex < section.results.length; itemIndex++)
          _buildSwipeConversationItem(
            section.results[itemIndex],
            showDivider: itemIndex != section.results.length - 1,
          ),
      ],
    );

    return Column(
      children: [
        _buildConversationSectionHeader(
          section.label,
          expanded: expanded,
          itemCount: section.results.length,
          onTap: () => _toggleConversationSection(section.sectionKey),
          iconAssetPath: iconAssetPath,
          leadingSlotWidth: _kConversationSectionHeaderLeadingSlotWidth,
        ),
        _buildCollapsibleSectionBody(expanded: expanded, child: items),
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
  }) {
    final palette = context.omniPalette;
    final headerContent = Semantics(
      button: true,
      toggled: expanded,
      onTap: onTap,
      child: Container(
        constraints: const BoxConstraints(minHeight: 28),
        alignment: Alignment.centerLeft,
        padding: const EdgeInsets.fromLTRB(4, 5, 4, 5),
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
                fontSize: 11,
                fontWeight: FontWeight.w600,
                letterSpacing: 0,
                color: palette.textTertiary,
                fontFamily: 'PingFang SC',
              ),
            ),
            const SizedBox(width: 8),
            Text(
              '$itemCount',
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w500,
                color: palette.textTertiary.withValues(alpha: 0.82),
                fontFamily: 'PingFang SC',
              ),
            ),
            const Spacer(),
          ],
        ),
      ),
    );
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(12),
          splashColor: palette.accentPrimary.withValues(alpha: 0.06),
          highlightColor: Colors.transparent,
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
    return Tooltip(
      message: tooltip,
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          width: 32,
          height: 32,
          decoration: BoxDecoration(
            color: isPrimary
                ? context.omniPalette.accentPrimary
                : context.isDarkTheme
                ? palette.surfaceSecondary
                : Colors.white,
            shape: BoxShape.circle,
          ),
          padding: const EdgeInsets.all(8),
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
    );
  }

  Widget _buildSwipeConversationItem(
    _ConversationSearchResult result, {
    required bool showDivider,
    bool includePinAction = true,
    String? trailingLabel,
  }) {
    final conversation = result.conversation;
    final isBusy = _busyConversationKeys.contains(conversation.threadKey);
    final title = _resolveConversationTitle(conversation);
    final agentLabel = _conversationAgentLabel(conversation);
    final showArchivedBadge = _isSearchActive && conversation.isArchived;
    final isEditing = _editingThreadKey == conversation.threadKey;

    return ConversationSlidable(
      itemKey: conversation.threadKey,
      groupTag: 'home-drawer-conversations',
      isBusy: isBusy,
      actions: _buildDrawerActions(
        conversation,
        includePinAction: includePinAction,
      ),
      onDismissed: () => _deleteConversation(conversation),
      onFullSwipe: () => conversation.isArchived
          ? _unarchiveConversation(conversation)
          : _archiveConversation(conversation),
      child: Column(
        children: [
          Material(
            color: Colors.transparent,
            child: InkWell(
              onTap: isEditing
                  ? null
                  : () => _openConversationFromDrawer(conversation),
              onLongPress: isEditing
                  ? null
                  : () => _startEditingTitle(conversation),
              borderRadius: BorderRadius.circular(14),
              splashColor: context.omniPalette.accentPrimary.withValues(
                alpha: 0.08,
              ),
              highlightColor: Colors.transparent,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(4, 9, 2, 9),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.center,
                      children: [
                        Expanded(
                          child: _buildEditableConversationTitle(
                            title: title,
                            isEditing: isEditing,
                          ),
                        ),
                        if (showArchivedBadge) ...[
                          const SizedBox(width: 10),
                          _buildArchivedBadge(),
                        ],
                        if (agentLabel != null && !isEditing) ...[
                          const SizedBox(width: 10),
                          Text(
                            agentLabel,
                            style: TextStyle(
                              fontSize: 11,
                              fontWeight: FontWeight.w500,
                              color: context.omniPalette.textTertiary,
                              fontFamily: 'PingFang SC',
                            ),
                          ),
                        ],
                        if (trailingLabel != null && !isEditing) ...[
                          const SizedBox(width: 10),
                          Text(
                            trailingLabel,
                            style: TextStyle(
                              fontSize: 11,
                              fontWeight: FontWeight.w400,
                              color: context.omniPalette.textTertiary,
                              fontFamily: 'PingFang SC',
                            ),
                          ),
                        ],
                      ],
                    ),
                    _buildConversationImagePreviewStrip(conversation),
                    if (_isSearchActive && result.matchedPreview != null) ...[
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
          if (showDivider) const SizedBox(height: 2),
        ],
      ),
    );
  }

  String? _conversationAgentLabel(ConversationModel conversation) {
    if (conversation.mode != ConversationMode.agent) {
      return null;
    }
    final agentId = conversation.agentId?.trim().toLowerCase() ?? '';
    if (agentId.isEmpty || agentId == 'xiaowan-acp') {
      return null;
    }
    return switch (agentId) {
      'codex-acp' || 'codex-remote' => 'Codex',
      'claude-code-acp' => 'Claude Code',
      'opencode-acp' => 'OpenCode',
      'deepseek-harness-acp' => 'DeepSeek',
      _ => _humanizeAgentId(agentId),
    };
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
          fontSize: 13,
          fontWeight: fontWeight,
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
        fontSize: 13,
        fontWeight: fontWeight,
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
