// ignore_for_file: invalid_use_of_protected_member

part of 'home_drawer.dart';

extension _HomeDrawerActions on HomeDrawerState {
  bool get _shouldCloseOnNavigate => widget.closeOnNavigate && !widget.embedded;

  void _maybeCloseDrawer() {
    if (!_shouldCloseOnNavigate || !Navigator.of(context).canPop()) {
      return;
    }
    Navigator.pop(context);
  }

  void _openThreadTarget(ConversationThreadTarget target) {
    if (widget.embedded && widget.onThreadTargetSelected != null) {
      widget.onThreadTargetSelected!(target);
      return;
    }
    _maybeCloseDrawer();
    GoRouterManager.push(
      '/home/chat',
      extra: target,
      queryParams: _threadTargetQueryParams(target),
    );
  }

  Map<String, dynamic> _threadTargetQueryParams(
    ConversationThreadTarget target,
  ) {
    return <String, dynamic>{
      'conversationId': target.conversationId?.toString() ?? 'new',
      'mode': target.mode.storageValue,
      if (target.agentId?.trim().isNotEmpty == true)
        'agentId': target.agentId!.trim(),
      'requestKey':
          target.requestKey ?? DateTime.now().microsecondsSinceEpoch.toString(),
    };
  }

  void _navigateTo(String route) {
    _maybeCloseDrawer();
    GoRouterManager.push(route);
  }

  void _openNewConversation() {
    // `subagent` 模式是"计划任务执行体"的内部语义,从 HomeDrawer 加号按钮新建的对话
    // 永远不应继承它(否则 subagent_dispatch / schedule_* / alarm_* / calendar_*
    // 都会被 AgentConversationModePolicy 过滤掉)。chatOnly 保持继承,因为那是用户
    // 主动选择的纯聊天模式。
    final newMode = widget.newConversationMode == ConversationMode.subagent
        ? ConversationMode.agent
        : widget.newConversationMode;
    _openThreadTarget(
      ConversationThreadTarget.newConversation(
        mode: newMode,
        requestKey: DateTime.now().microsecondsSinceEpoch.toString(),
      ),
    );
  }

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
      debugPrint('[HomeDrawer] failed to trigger delete haptic: $error');
    }
  }

  void _replaceConversationInState(ConversationModel updatedConversation) {
    final allConversations = List<ConversationModel>.from(_allConversations);
    final allIndex = allConversations.indexWhere(
      (item) => item.threadKey == updatedConversation.threadKey,
    );
    if (allIndex >= 0) {
      allConversations[allIndex] = updatedConversation;
    }

    final searchResults = List<_ConversationSearchResult>.from(_searchResults);
    final searchIndex = searchResults.indexWhere(
      (item) => item.conversation.threadKey == updatedConversation.threadKey,
    );
    if (searchIndex >= 0) {
      searchResults[searchIndex] = searchResults[searchIndex].copyWith(
        conversation: updatedConversation,
      );
    }

    _conversationSearchCache.remove(updatedConversation.threadKey);
    _allConversations = allConversations;
    _searchResults = searchResults;
    _syncConversationSnapshotCache();
  }

  void _removeConversationFromState(ConversationModel conversation) {
    _allConversations = List<ConversationModel>.from(_allConversations)
      ..removeWhere((item) => item.threadKey == conversation.threadKey);
    _searchResults = List<_ConversationSearchResult>.from(_searchResults)
      ..removeWhere(
        (item) => item.conversation.threadKey == conversation.threadKey,
      );
    _conversationSearchCache.remove(conversation.threadKey);
    _syncConversationSnapshotCache();
  }

  void _startEditingTitle(ConversationModel conversation) {
    if (_busyConversationKeys.contains(conversation.threadKey)) {
      return;
    }
    final title = _resolveConversationTitle(conversation);
    _titleEditingController.text = title;
    _titleEditingController.selection = TextSelection(
      baseOffset: 0,
      extentOffset: title.length,
    );
    setState(() {
      _editingThreadKey = conversation.threadKey;
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _titleEditingFocusNode.requestFocus();
    });
  }

  void _handleTitleEditingFocusChanged() {
    if (!_titleEditingFocusNode.hasFocus && _editingThreadKey != null) {
      _commitTitleEdit();
    }
  }

  Future<void> _commitTitleEdit([String? submittedTitle]) async {
    final threadKey = _editingThreadKey;
    if (threadKey == null) return;

    final newTitle = (submittedTitle ?? _titleEditingController.text).trim();
    final conversation = _allConversations
        .cast<ConversationModel?>()
        .firstWhere((c) => c!.threadKey == threadKey, orElse: () => null);

    setState(() {
      _editingThreadKey = null;
    });

    if (conversation == null) return;

    final oldTitle = _resolveConversationTitle(conversation);
    if (newTitle.isEmpty || newTitle == oldTitle) return;

    final updated = conversation.copyWith(title: newTitle);
    setState(() {
      _replaceConversationInState(updated);
    });
    _persistCurrentConversationSnapshot();

    final success = await ConversationService.updateConversationTitle(
      conversationId: conversation.id,
      newTitle: newTitle,
      mode: conversation.mode,
    );

    if (!mounted) return;
    if (!success) {
      setState(() {
        _replaceConversationInState(conversation);
      });
      _persistCurrentConversationSnapshot();
      showToast(context.trLegacy('重命名失败'), type: ToastType.error);
    }
  }

  void _openConversationFromDrawer(ConversationModel conversation) {
    if (_busyConversationKeys.contains(conversation.threadKey)) {
      return;
    }
    _openThreadTarget(
      ConversationThreadTarget.existing(
        conversationId: conversation.id,
        mode: conversation.mode,
        // Legacy Agent conversations were created before agentId was
        // persisted. Resolve that missing binding at the boundary so a
        // switch cannot temporarily inherit whichever ACP is selected in the
        // global UI state.
        agentId:
            conversation.agentId ??
            (conversation.mode == ConversationMode.agent
                ? 'xiaowan-acp'
                : null),
      ),
    );
  }

  Future<void> _deleteConversation(ConversationModel conversation) async {
    if (_busyConversationKeys.contains(conversation.threadKey)) {
      return;
    }

    final originalIndex = _allConversations.indexWhere(
      (item) => item.id == conversation.id,
    );
    if (originalIndex < 0) {
      return;
    }

    setState(() {
      _busyConversationKeys.add(conversation.threadKey);
      _removeConversationFromState(conversation);
    });
    _persistCurrentConversationSnapshot();

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
      _busyConversationKeys.remove(conversation.threadKey);
      if (!deleted) {
        final restoredIndex = originalIndex <= _allConversations.length
            ? originalIndex
            : _allConversations.length;
        _allConversations = List<ConversationModel>.from(_allConversations)
          ..insert(restoredIndex, conversation);
        _syncConversationSnapshotCache();
        if (_isSearchActive) {
          _scheduleConversationSearch(immediate: true);
        }
      }
    });
    _persistCurrentConversationSnapshot();

    showToast(
      deleted ? context.trLegacy('已删除') : context.trLegacy('删除失败'),
      type: deleted ? ToastType.success : ToastType.error,
    );
  }

  Future<void> _copyConversation(ConversationModel conversation) async {
    if (_busyConversationKeys.contains(conversation.threadKey)) {
      return;
    }
    final copied = await ConversationHistoryService.copyConversation(
      conversation.id,
      mode: conversation.mode,
    );
    if (!mounted) return;
    showToast(
      copied ? context.trLegacy('已复制对话') : context.trLegacy('复制失败'),
      type: copied ? ToastType.success : ToastType.error,
    );
  }

  Future<void> _archiveConversation(ConversationModel conversation) async {
    if (_busyConversationKeys.contains(conversation.threadKey)) {
      return;
    }

    final originalIndex = _allConversations.indexWhere(
      (item) => item.threadKey == conversation.threadKey,
    );
    if (originalIndex < 0) {
      return;
    }

    final originalConversation = _allConversations[originalIndex];
    final archivedConversation = originalConversation.copyWith(
      isArchived: true,
    );

    setState(() {
      _busyConversationKeys.add(conversation.threadKey);
      _replaceConversationInState(archivedConversation);
    });
    _persistCurrentConversationSnapshot();

    final archived = await ConversationService.archiveConversation(
      originalConversation,
    );
    if (!mounted) {
      return;
    }

    setState(() {
      _busyConversationKeys.remove(conversation.threadKey);
      if (!archived) {
        _replaceConversationInState(originalConversation);
      }
    });
    _persistCurrentConversationSnapshot();

    showToast(
      archived ? context.trLegacy('已归档') : context.trLegacy('归档失败'),
      type: archived ? ToastType.success : ToastType.error,
    );
  }

  Future<void> _unarchiveConversation(ConversationModel conversation) async {
    if (_busyConversationKeys.contains(conversation.threadKey)) {
      return;
    }

    final originalIndex = _allConversations.indexWhere(
      (item) => item.threadKey == conversation.threadKey,
    );
    if (originalIndex < 0) {
      return;
    }

    final originalConversation = _allConversations[originalIndex];
    final restoredConversation = originalConversation.copyWith(
      isArchived: false,
    );

    setState(() {
      _busyConversationKeys.add(conversation.threadKey);
      _replaceConversationInState(restoredConversation);
    });
    _persistCurrentConversationSnapshot();

    final restored = await ConversationService.unarchiveConversation(
      originalConversation,
    );
    if (!mounted) {
      return;
    }

    setState(() {
      _busyConversationKeys.remove(conversation.threadKey);
      if (!restored) {
        _replaceConversationInState(originalConversation);
      }
    });
    _persistCurrentConversationSnapshot();

    showToast(
      restored ? context.trLegacy('已取消归档') : context.trLegacy('取消归档失败'),
      type: restored ? ToastType.success : ToastType.error,
    );
  }

  Future<void> _setConversationPinned(
    ConversationModel conversation, {
    required bool pinned,
  }) async {
    if (_busyConversationKeys.contains(conversation.threadKey)) {
      return;
    }

    final originalIndex = _allConversations.indexWhere(
      (item) => item.threadKey == conversation.threadKey,
    );
    if (originalIndex < 0) {
      return;
    }

    final originalConversation = _allConversations[originalIndex];
    final updatedConversation = originalConversation.copyWith(isPinned: pinned);

    setState(() {
      _busyConversationKeys.add(conversation.threadKey);
      _replaceConversationInState(updatedConversation);
    });
    _persistCurrentConversationSnapshot();

    final updated = await ConversationService.updateConversation(
      updatedConversation,
    );
    if (!mounted) {
      return;
    }

    setState(() {
      _busyConversationKeys.remove(conversation.threadKey);
      if (!updated) {
        _replaceConversationInState(originalConversation);
      }
    });
    _persistCurrentConversationSnapshot();

    showToast(
      updated
          ? (pinned ? context.trLegacy('已置顶') : context.trLegacy('已取消置顶'))
          : (pinned ? context.trLegacy('置顶失败') : context.trLegacy('取消置顶失败')),
      type: updated ? ToastType.success : ToastType.error,
    );
  }

  List<ConversationSlideAction> _buildDrawerActions(
    ConversationModel conversation, {
    bool includePinAction = true,
  }) {
    final actions = <ConversationSlideAction>[
      ConversationSlideAction(
        onPressed: () => _deleteConversation(conversation),
        backgroundColor: AppColors.alertRed,
        child: Center(
          child: SvgPicture.asset(
            'assets/memory/memory_delete.svg',
            width: HomeDrawerState._conversationActionIconSize,
            height: HomeDrawerState._conversationActionIconSize,
            colorFilter: const ColorFilter.mode(Colors.white, BlendMode.srcIn),
          ),
        ),
      ),
      if (includePinAction)
        ConversationSlideAction(
          onPressed: () => _setConversationPinned(
            conversation,
            pinned: !conversation.isPinned,
          ),
          backgroundColor: context.isDarkTheme
              ? Color.lerp(
                  context.omniPalette.surfaceElevated,
                  context.omniPalette.accentPrimary,
                  0.18,
                )!
              : AppColors.text.withValues(alpha: 0.72),
          child: Center(
            child: SvgPicture.asset(
              conversation.isPinned
                  ? 'assets/home/pin_off_icon.svg'
                  : 'assets/home/pin_icon.svg',
              width: HomeDrawerState._conversationActionIconSize,
              height: HomeDrawerState._conversationActionIconSize,
              colorFilter: const ColorFilter.mode(
                Colors.white,
                BlendMode.srcIn,
              ),
            ),
          ),
        ),
      ConversationSlideAction(
        onPressed: () => _copyConversation(conversation),
        backgroundColor: context.isDarkTheme
            ? context.omniPalette.surfaceElevated
            : AppColors.text.withValues(alpha: 0.62),
        child: const Center(
          child: Icon(
            Icons.content_copy_rounded,
            size: HomeDrawerState._conversationActionIconSize,
            color: Colors.white,
          ),
        ),
      ),
      ConversationSlideAction(
        onPressed: () => conversation.isArchived
            ? _unarchiveConversation(conversation)
            : _archiveConversation(conversation),
        backgroundColor: context.isDarkTheme
            ? Color.lerp(
                context.omniPalette.surfaceElevated,
                context.omniPalette.accentPrimary,
                0.3,
              )!
            : AppColors.buttonPrimary,
        borderRadius: HomeDrawerState._drawerTrailingActionRadius,
        child: Center(
          child: conversation.isArchived
              ? Icon(
                  Icons.unarchive_outlined,
                  size: HomeDrawerState._conversationActionIconSize,
                  color: Colors.white,
                )
              : SvgPicture.asset(
                  'assets/home/archive_icon.svg',
                  width: HomeDrawerState._conversationActionIconSize,
                  height: HomeDrawerState._conversationActionIconSize,
                  colorFilter: const ColorFilter.mode(
                    Colors.white,
                    BlendMode.srcIn,
                  ),
                ),
        ),
      ),
    ];
    return actions;
  }

  String _resolveConversationTitle(ConversationModel conversation) {
    final title = conversation.title.trim();
    if (title.isNotEmpty) {
      return title;
    }
    final summary = (conversation.summary ?? '').trim();
    return summary.isNotEmpty ? summary : context.trLegacy('未命名对话');
  }
}
