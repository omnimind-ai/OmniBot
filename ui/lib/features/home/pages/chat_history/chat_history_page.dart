import 'dart:async';

import 'package:flutter/material.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:flutter/services.dart';
import 'package:flutter_slidable/flutter_slidable.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/pages/chat_history/widgets/chat_history_conversation_item.dart';
import 'package:ui/features/home/widgets/conversation_slidable.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/conversation_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/cache_util.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';

class ChatHistoryPage extends StatefulWidget {
  const ChatHistoryPage({super.key, this.archivedOnly = false});

  final bool archivedOnly;

  @override
  State<ChatHistoryPage> createState() => _ChatHistoryPageState();
}

class _ChatHistoryPageState extends State<ChatHistoryPage> {
  static const String _dateToggleClosedIconAsset =
      'assets/home/chat/mode_menu_closed.svg';
  static const String _dateToggleOpenIconAsset =
      'assets/home/chat/mode_menu_open.svg';
  static const String _archivedConversationGroupTag =
      'chat-history-archived-conversations';
  static const Duration _sectionToggleDuration = Duration(milliseconds: 260);
  static const double _archivedConversationActionIconSize = 18;
  static const BorderRadius _cardTrailingActionRadius = BorderRadius.only(
    topRight: Radius.circular(8),
    bottomRight: Radius.circular(8),
  );
  static const BorderRadius _flatTrailingActionRadius = BorderRadius.only(
    topRight: Radius.circular(4),
    bottomRight: Radius.circular(4),
  );

  List<ConversationModel> _conversations = const [];
  final Set<String> _busyKeys = <String>{};
  final Map<String, bool> _expandedDateSections = <String, bool>{};
  bool _isLoading = true;
  StreamSubscription<Map<String, dynamic>>?
  _conversationListChangedSubscription;

  Future<void> _triggerDeleteHaptic() async {
    try {
      final enabled = await CacheUtil.getBool(
        'app_vibrate',
        defaultValue: true,
      );
      if (!enabled) {
        return;
      }
      await HapticFeedback.mediumImpact();
    } catch (error) {
      debugPrint('[ChatHistoryPage] failed to trigger delete haptic: $error');
    }
  }

  @override
  void initState() {
    super.initState();
    _conversationListChangedSubscription = AssistsMessageService
        .conversationListChangedStream
        .listen((_) {
          unawaited(_loadConversations());
        });
    _loadConversations();
  }

  @override
  void dispose() {
    _conversationListChangedSubscription?.cancel();
    super.dispose();
  }

  Future<void> _loadConversations() async {
    if (mounted) {
      setState(() {
        _isLoading = true;
      });
    }

    try {
      final loadedConversations = await ConversationService.getAllConversations(
        archivedOnly: widget.archivedOnly,
      );
      if (!mounted) {
        return;
      }
      final sectionLabels = _buildConversationSections(
        loadedConversations,
      ).map((section) => section.label).toSet();
      setState(() {
        _conversations = loadedConversations;
        _expandedDateSections.removeWhere(
          (label, _) => !sectionLabels.contains(label),
        );
        _isLoading = false;
      });
    } catch (error) {
      debugPrint('[ChatHistoryPage] failed to load conversations: $error');
      if (!mounted) {
        return;
      }
      setState(() {
        _isLoading = false;
      });
    }
  }

  Future<void> _deleteConversation(ConversationModel conversation) async {
    if (_busyKeys.contains(conversation.threadKey)) {
      return;
    }

    final originalIndex = _conversations.indexWhere(
      (item) => item.id == conversation.id,
    );
    if (originalIndex < 0) {
      return;
    }

    setState(() {
      _busyKeys.add(conversation.threadKey);
      _conversations = List<ConversationModel>.from(_conversations)
        ..removeAt(originalIndex);
    });

    final deleted = await ConversationService.deleteConversation(
      conversation.id,
      mode: conversation.mode,
    );
    if (!mounted) {
      return;
    }
    if (deleted) {
      unawaited(_triggerDeleteHaptic());
    }

    setState(() {
      _busyKeys.remove(conversation.threadKey);
      if (!deleted) {
        final restoredIndex = originalIndex <= _conversations.length
            ? originalIndex
            : _conversations.length;
        _conversations = List<ConversationModel>.from(_conversations)
          ..insert(restoredIndex, conversation);
      }
    });

    showToast(
      deleted ? '\u5df2\u5220\u9664' : '\u5220\u9664\u5931\u8d25',
      type: deleted ? ToastType.success : ToastType.error,
    );
  }

  Future<void> _setConversationArchived(
    ConversationModel conversation, {
    required bool archived,
  }) async {
    if (_busyKeys.contains(conversation.threadKey)) {
      return;
    }

    final originalIndex = _conversations.indexWhere(
      (item) => item.id == conversation.id,
    );
    if (originalIndex < 0) {
      return;
    }

    setState(() {
      _busyKeys.add(conversation.threadKey);
      _conversations = List<ConversationModel>.from(_conversations)
        ..removeAt(originalIndex);
    });

    final success = archived
        ? await ConversationService.archiveConversation(conversation)
        : await ConversationService.unarchiveConversation(conversation);
    if (!mounted) {
      return;
    }

    setState(() {
      _busyKeys.remove(conversation.threadKey);
      if (!success) {
        final restoredIndex = originalIndex <= _conversations.length
            ? originalIndex
            : _conversations.length;
        _conversations = List<ConversationModel>.from(_conversations)
          ..insert(restoredIndex, conversation);
      }
    });

    showToast(
      success
          ? (archived
                ? context.l10n.chatHistoryArchivedToast
                : context.l10n.chatHistoryUnarchivedToast)
          : (archived
                ? context.l10n.chatHistoryArchiveFailed
                : context.l10n.chatHistoryUnarchiveFailed),
      type: success ? ToastType.success : ToastType.error,
    );
  }

  void _openConversation(ConversationModel conversation) {
    if (_busyKeys.contains(conversation.threadKey)) {
      return;
    }
    GoRouterManager.push(
      '/home/chat',
      extra: ConversationThreadTarget.existing(
        conversationId: conversation.id,
        mode: conversation.mode,
      ),
    );
  }

  void _createConversation() {
    GoRouterManager.push(
      '/home/chat',
      extra: ConversationThreadTarget.newConversation(
        mode: ConversationMode.agent,
        requestKey: DateTime.now().microsecondsSinceEpoch.toString(),
      ),
    );
  }

  String get _pageTitle => widget.archivedOnly
      ? context.l10n.chatHistoryArchivedTitle
      : context.l10n.chatHistoryTitle;

  String get _emptyTitle => widget.archivedOnly
      ? context.l10n.chatHistoryNoArchived
      : context.l10n.chatHistoryEmpty;

  List<ConversationSlideAction> _buildActions(ConversationModel conversation) {
    final palette = context.omniPalette;
    final primaryAction = ConversationSlideAction(
      onPressed: () => _setConversationArchived(
        conversation,
        archived: !widget.archivedOnly,
      ),
      backgroundColor: context.isDarkTheme
          ? Color.lerp(palette.surfaceElevated, palette.accentPrimary, 0.3)!
          : AppColors.buttonPrimary,
      borderRadius: widget.archivedOnly
          ? _flatTrailingActionRadius
          : BorderRadius.zero,
      child: Center(
        child: widget.archivedOnly
            ? Icon(
                Icons.unarchive_outlined,
                color: Colors.white,
                size: _archivedConversationActionIconSize,
              )
            : SvgPicture.asset(
                'assets/home/archive_icon.svg',
                width: 20,
                height: 20,
                colorFilter: const ColorFilter.mode(
                  Colors.white,
                  BlendMode.srcIn,
                ),
              ),
      ),
    );

    final deleteAction = ConversationSlideAction(
      onPressed: () => _deleteConversation(conversation),
      backgroundColor: AppColors.alertRed,
      borderRadius: widget.archivedOnly
          ? BorderRadius.zero
          : _cardTrailingActionRadius,
      child: Center(
        child: SvgPicture.asset(
          'assets/memory/memory_delete.svg',
          width: widget.archivedOnly ? _archivedConversationActionIconSize : 20,
          height: widget.archivedOnly
              ? _archivedConversationActionIconSize
              : 20,
          colorFilter: const ColorFilter.mode(Colors.white, BlendMode.srcIn),
        ),
      ),
    );

    return widget.archivedOnly
        ? [deleteAction, primaryAction]
        : [primaryAction, deleteAction];
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: _pageTitle,
        primary: true,
        trailing: widget.archivedOnly
            ? _buildArchiveDateToggleButton()
            : IconButton(
                icon: Icon(
                  Icons.add,
                  color: context.isDarkTheme
                      ? palette.textSecondary
                      : Colors.grey[600],
                  size: 24,
                ),
                onPressed: _createConversation,
                tooltip: '\u65b0\u5efa\u5bf9\u8bdd',
              ),
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    final palette = context.omniPalette;
    if (_isLoading) {
      return Center(
        child: CircularProgressIndicator(
          valueColor: AlwaysStoppedAnimation<Color>(
            context.isDarkTheme
                ? palette.accentPrimary
                : const Color(0xFF1930D9),
          ),
        ),
      );
    }

    if (_conversations.isEmpty) {
      return _buildEmptyState();
    }

    if (widget.archivedOnly) {
      return _buildArchivedConversationTimeline();
    }

    return ListView.builder(
      padding: edgeToEdgeScrollPadding(context, const EdgeInsets.all(16)),
      itemCount: _conversations.length,
      itemBuilder: (context, index) {
        final conversation = _conversations[index];
        return ChatHistoryConversationItem(
          conversation: conversation,
          actions: _buildActions(conversation),
          isBusy: _busyKeys.contains(conversation.threadKey),
          compact: widget.archivedOnly,
          showLeadingIcon: !widget.archivedOnly,
          onTap: () => _openConversation(conversation),
          onDelete: () => _deleteConversation(conversation),
        );
      },
    );
  }

  List<_ConversationDateSection> _buildConversationSections(
    List<ConversationModel> conversations,
  ) {
    final sections = <_ConversationDateSection>[];
    for (final conversation in conversations) {
      final label = conversation.timeDisplay;
      if (sections.isEmpty || sections.last.label != label) {
        sections.add(
          _ConversationDateSection(
            label: label,
            conversations: <ConversationModel>[conversation],
          ),
        );
      } else {
        sections.last.conversations.add(conversation);
      }
    }
    return sections;
  }

  bool _isDateSectionExpanded(String label) =>
      _expandedDateSections[label] ?? true;

  bool get _hasExpandableDateSections =>
      !_isLoading && _conversations.isNotEmpty;

  bool get _hasAnyExpandedDateSection {
    final sections = _buildConversationSections(_conversations);
    return sections.any((section) => _isDateSectionExpanded(section.label));
  }

  bool get _areAllDateSectionsCollapsed {
    final sections = _buildConversationSections(_conversations);
    return sections.isNotEmpty &&
        sections.every((section) => !_isDateSectionExpanded(section.label));
  }

  void _toggleDateSection(String label) {
    setState(() {
      _expandedDateSections[label] = !_isDateSectionExpanded(label);
    });
  }

  void _toggleAllDateSections() {
    final sections = _buildConversationSections(_conversations);
    if (sections.isEmpty) {
      return;
    }

    final shouldExpand = _areAllDateSectionsCollapsed;
    setState(() {
      for (final section in sections) {
        _expandedDateSections[section.label] = shouldExpand;
      }
    });
  }

  Widget _buildArchiveDateToggleButton() {
    final palette = context.omniPalette;
    final enabled = _hasExpandableDateSections;
    final isOpen = enabled && _hasAnyExpandedDateSection;
    final iconColor = !enabled
        ? palette.textTertiary
        : isOpen
        ? palette.accentPrimary
        : palette.textSecondary;
    final tooltip = !enabled
        ? context.trLegacy('暂无归档对话')
        : _areAllDateSectionsCollapsed
        ? context.trLegacy('展开全部日期')
        : context.trLegacy('折叠全部日期');

    return Tooltip(
      message: tooltip,
      child: GestureDetector(
        key: const ValueKey('chat-history-date-toggle-button'),
        onTap: enabled ? _toggleAllDateSections : null,
        behavior: HitTestBehavior.opaque,
        child: AnimatedOpacity(
          duration: const Duration(milliseconds: 180),
          opacity: enabled ? 1 : 0.36,
          child: SizedBox(
            width: 48,
            height: 44,
            child: Center(
              child: SvgPicture.asset(
                isOpen ? _dateToggleOpenIconAsset : _dateToggleClosedIconAsset,
                width: 20,
                height: 20,
                colorFilter: ColorFilter.mode(iconColor, BlendMode.srcIn),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildArchivedConversationTimeline() {
    final sections = _buildConversationSections(_conversations);
    return SlidableAutoCloseBehavior(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(16, 10, 16, 20),
        children: [
          for (
            int sectionIndex = 0;
            sectionIndex < sections.length;
            sectionIndex++
          ) ...[
            if (sectionIndex > 0) const SizedBox(height: 14),
            _buildConversationDateSection(sections[sectionIndex]),
          ],
        ],
      ),
    );
  }

  Widget _buildConversationDateSection(_ConversationDateSection section) {
    final expanded = _isDateSectionExpanded(section.label);
    final items = Column(
      children: [
        const SizedBox(height: 4),
        for (
          int itemIndex = 0;
          itemIndex < section.conversations.length;
          itemIndex++
        )
          _buildArchivedConversationItem(
            section.conversations[itemIndex],
            showDivider: itemIndex != section.conversations.length - 1,
          ),
      ],
    );

    return Column(
      children: [
        _buildConversationSectionHeader(
          section.label,
          expanded: expanded,
          itemCount: section.conversations.length,
          onTap: () => _toggleDateSection(section.label),
        ),
        TweenAnimationBuilder<double>(
          tween: Tween<double>(begin: expanded ? 1 : 0, end: expanded ? 1 : 0),
          duration: _sectionToggleDuration,
          curve: Curves.easeInOutCubicEmphasized,
          builder: (context, value, child) {
            return ClipRect(
              key: ValueKey<String>(
                'chat-history-date-section-body-${section.label}',
              ),
              child: Align(
                alignment: Alignment.topCenter,
                heightFactor: value,
                child: Opacity(
                  opacity: value.clamp(0.0, 1.0).toDouble(),
                  child: IgnorePointer(ignoring: value < 0.99, child: child),
                ),
              ),
            );
          },
          child: items,
        ),
      ],
    );
  }

  Widget _buildConversationSectionHeader(
    String label, {
    required bool expanded,
    required int itemCount,
    required VoidCallback onTap,
  }) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(12),
          splashColor: palette.accentPrimary.withValues(alpha: 0.06),
          highlightColor: Colors.transparent,
          child: Container(
            constraints: const BoxConstraints(minHeight: 28),
            alignment: Alignment.centerLeft,
            padding: const EdgeInsets.fromLTRB(4, 5, 4, 5),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Text(
                  label,
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                    letterSpacing: 0.6,
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
                const SizedBox(width: 10),
                Expanded(
                  child: Container(
                    height: 1,
                    color: palette.borderSubtle.withValues(
                      alpha: context.isDarkTheme ? 0.56 : 0.8,
                    ),
                  ),
                ),
                const SizedBox(width: 6),
                AnimatedRotation(
                  turns: expanded ? 0 : -0.25,
                  duration: _sectionToggleDuration,
                  curve: Curves.easeInOutCubicEmphasized,
                  child: Icon(
                    Icons.keyboard_arrow_down_rounded,
                    size: 18,
                    color: palette.textTertiary,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildArchivedConversationItem(
    ConversationModel conversation, {
    required bool showDivider,
  }) {
    final palette = context.omniPalette;
    final isBusy = _busyKeys.contains(conversation.threadKey);
    final title = _resolveConversationTitle(conversation);
    final messageCount = conversation.messageCount;

    return ConversationSlidable(
      itemKey: conversation.threadKey,
      groupTag: _archivedConversationGroupTag,
      isBusy: isBusy,
      actions: _buildActions(conversation),
      onDismissed: () => _deleteConversation(conversation),
      onFullSwipe: () => _setConversationArchived(
        conversation,
        archived: !widget.archivedOnly,
      ),
      child: Column(
        children: [
          Material(
            color: Colors.transparent,
            child: InkWell(
              onTap: () => _openConversation(conversation),
              borderRadius: BorderRadius.circular(14),
              splashColor: palette.accentPrimary.withValues(alpha: 0.08),
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
                          child: Text(
                            title,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 13,
                              fontWeight: FontWeight.w500,
                              color: context.isDarkTheme
                                  ? palette.textPrimary
                                  : AppColors.text,
                              height: 1.35,
                              fontFamily: 'PingFang SC',
                            ),
                          ),
                        ),
                        if (messageCount > 0) ...[
                          const SizedBox(width: 10),
                          _buildMessageCountBadge(messageCount),
                        ],
                      ],
                    ),
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

  Widget _buildMessageCountBadge(int messageCount) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: context.isDarkTheme
            ? palette.surfaceSecondary
            : palette.previewFallback,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        context.trLegacy('$messageCount 条消息'),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.w600,
          color: palette.textSecondary,
          fontFamily: 'PingFang SC',
        ),
      ),
    );
  }

  String _resolveConversationTitle(ConversationModel conversation) {
    final title = conversation.title.trim();
    if (title.isNotEmpty) {
      return title;
    }
    final summary = (conversation.summary ?? '').trim();
    return summary.isNotEmpty ? summary : context.trLegacy('未命名对话');
  }

  Widget _buildEmptyHint() {
    final palette = context.omniPalette;
    if (!widget.archivedOnly) {
      return GestureDetector(
        onTap: _createConversation,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
          decoration: BoxDecoration(
            gradient: LinearGradient(
              colors: context.isDarkTheme
                  ? <Color>[
                      Color.lerp(
                        palette.surfaceElevated,
                        palette.accentPrimary,
                        0.24,
                      )!,
                      Color.lerp(
                        palette.surfaceSecondary,
                        palette.accentPrimary,
                        0.36,
                      )!,
                    ]
                  : const <Color>[Color(0xFF1930D9), Color(0xFF2CA5F0)],
            ),
            borderRadius: BorderRadius.all(Radius.circular(24)),
            border: context.isDarkTheme
                ? Border.all(color: palette.borderSubtle)
                : null,
          ),
          child: Text(
            '\u5f00\u59cb\u5bf9\u8bdd',
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w500,
              color: context.isDarkTheme ? palette.textPrimary : Colors.white,
            ),
          ),
        ),
      );
    }

    return Text(
      context.l10n.chatHistoryArchiveHint,
      style: TextStyle(
        fontSize: 13,
        color: context.isDarkTheme ? palette.textSecondary : Colors.grey[500],
      ),
    );
  }

  Widget _buildEmptyState() {
    final palette = context.omniPalette;
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            widget.archivedOnly
                ? Icons.archive_outlined
                : Icons.chat_bubble_outline,
            size: 64,
            color: context.isDarkTheme
                ? palette.borderStrong
                : Colors.grey[300],
          ),
          const SizedBox(height: 16),
          Text(
            _emptyTitle,
            style: TextStyle(
              fontSize: 16,
              color: context.isDarkTheme
                  ? palette.textSecondary
                  : Colors.grey[500],
            ),
          ),
          const SizedBox(height: 24),
          _buildEmptyHint(),
        ],
      ),
    );
  }
}

class _ConversationDateSection {
  _ConversationDateSection({required this.label, required this.conversations});

  final String label;
  final List<ConversationModel> conversations;
}
