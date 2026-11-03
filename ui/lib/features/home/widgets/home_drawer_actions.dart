// ignore_for_file: invalid_use_of_protected_member

part of 'home_drawer.dart';

enum _ConversationContextAction { pin, rename, copy, select, archive, delete }

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
    final queryParams = _threadTargetQueryParams(target);
    void open() => GoRouterManager.push(
      '/home/chat',
      extra: target,
      queryParams: queryParams,
    );
    if (!_closeDrawerForNavigation()) {
      open();
      return;
    }
    // Switching the chat target rebuilds the whole chat page (history load,
    // message list, composer). Doing it in the frames of the close slide made
    // every close stutter, so it runs once the drawer has settled. The drawer
    // state is gone by then; the router does not need it.
    Future<void>.delayed(HomeDrawerState.drawerSettleDelay, open);
  }

  /// Pops a modal drawer. Returns false when there was nothing to close.
  bool _closeDrawerForNavigation() {
    if (!_shouldCloseOnNavigate || !Navigator.of(context).canPop()) {
      return false;
    }
    Navigator.pop(context);
    return true;
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
    final requestedMode = widget.homePage
        ? _homeFilter.mode
        : widget.newConversationMode;
    final newMode = requestedMode == ConversationMode.subagent
        ? ConversationMode.agent
        : requestedMode;
    _openThreadTarget(
      ConversationThreadTarget.newConversation(
        mode: newMode,
        agentId: widget.homePage && newMode == ConversationMode.agent
            ? _homeFilter.agentId ?? 'xiaowan-acp'
            : null,
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
    if (_isSelectingConversations) {
      _toggleConversationSelection(conversation);
      return;
    }
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
        agentId: conversation.agentId?.trim().isNotEmpty == true
            ? conversation.agentId!.trim()
            : (conversation.mode == ConversationMode.agent
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
      (item) => item.threadKey == conversation.threadKey,
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

  Future<void> _showConversationContextMenu(
    BuildContext rowContext,
    ConversationModel conversation, {
    required Offset pressPosition,
    bool includePinAction = true,
    String? previewOverride,
    bool showArchivedBadge = false,
  }) async {
    if (_busyConversationKeys.contains(conversation.threadKey) ||
        _activeContextMenuThreadKey != null) {
      return;
    }
    unawaited(Feedback.forLongPress(rowContext));

    final box = rowContext.findRenderObject() as RenderBox?;
    final navigator = Navigator.of(rowContext, rootNavigator: true);
    final overlay = navigator.overlay?.context.findRenderObject() as RenderBox?;
    if (box == null || !box.hasSize || overlay == null || !overlay.hasSize) {
      return;
    }

    final rowTopLeft = box.localToGlobal(Offset.zero, ancestor: overlay);
    final rowBottomRight = box.localToGlobal(
      box.size.bottomRight(Offset.zero),
      ancestor: overlay,
    );
    if (widget.homePage) {
      setState(() => _activeContextMenuThreadKey = conversation.threadKey);
    }
    final route = ConversationContextMenuRoute<_ConversationContextAction>(
      reduceMotion: MediaQuery.disableAnimationsOf(rowContext),
      barrierLabel: MaterialLocalizations.of(rowContext)
          .modalBarrierDismissLabel,
      pageBuilder: (dialogContext, animation, secondaryAnimation) {
        final isEnglish =
            Localizations.localeOf(dialogContext).languageCode == 'en';
        String tr(String zh, String en) => isEnglish ? en : zh;
        return ConversationContextMenu(
          animation: animation,
          onDismiss: () => Navigator.of(dialogContext).pop(),
          anchor: Rect.fromPoints(rowTopLeft, rowBottomRight),
          origin: overlay.globalToLocal(pressPosition),
          preview: widget.homePage
              ? Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  child: HomeConversationTileContent(
                    conversation: conversation,
                    avatarColorIndex:
                        _homeAvatarColorIndices[conversation.threadKey],
                    title: _resolveConversationTitle(conversation),
                    previewOverride: previewOverride,
                    badge: showArchivedBadge ? _buildArchivedBadge() : null,
                    isExecuting: _runtimeCoordinator.isAgentConversationActive(
                      conversation.id,
                    ),
                  ),
                )
              : null,
          actions: [
            if (includePinAction)
              _buildConversationMenuOption(
                dialogContext,
                action: _ConversationContextAction.pin,
                icon: conversation.isPinned
                    ? LucideIcons.pinOff
                    : LucideIcons.pin,
                label: conversation.isPinned
                    ? tr('取消置顶', 'Unpin')
                    : tr('置顶对话', 'Pin'),
              ),
            _buildConversationMenuOption(
              dialogContext,
              action: _ConversationContextAction.rename,
              icon: LucideIcons.pencil,
              label: tr('重命名', 'Rename'),
            ),
            _buildConversationMenuOption(
              dialogContext,
              action: _ConversationContextAction.copy,
              icon: LucideIcons.copy,
              label: tr('复制对话', 'Copy'),
            ),
            if (widget.homePage)
              _buildConversationMenuOption(
                dialogContext,
                action: _ConversationContextAction.select,
                icon: LucideIcons.listChecks,
                label: tr('多选', 'Select'),
              ),
            _buildConversationMenuOption(
              dialogContext,
              action: _ConversationContextAction.archive,
              icon: conversation.isArchived
                  ? LucideIcons.archiveRestore
                  : LucideIcons.archive,
              label: conversation.isArchived
                  ? tr('取消归档', 'Unarchive')
                  : tr('归档对话', 'Archive'),
            ),
            _buildConversationMenuOption(
              dialogContext,
              action: _ConversationContextAction.delete,
              icon: LucideIcons.trash2,
              label: tr('删除对话', 'Delete'),
              isDestructive: true,
            ),
          ],
        );
      },
    );
    var sourceRestored = false;
    void restoreSource() {
      if (sourceRestored) return;
      sourceRestored = true;
      if (mounted && _activeContextMenuThreadKey == conversation.threadKey) {
        setState(() => _activeContextMenuThreadKey = null);
      }
    }

    final selection = navigator.push(route);
    final animation = route.animation!;
    void restoreWhenReturned() {
      if (animation.value == 0 && animation.status != AnimationStatus.forward) {
        restoreSource();
      }
    }

    // Restore before the closing animation's final paint so the row never
    // disappears between its focused preview and the original list position.
    animation.addListener(restoreWhenReturned);
    _ConversationContextAction? selectedAction;
    try {
      selectedAction = await selection;
      // A menu action starts only after both focused row and menu are removed.
      await route.completed;
    } finally {
      animation.removeListener(restoreWhenReturned);
      restoreSource();
    }

    if (!mounted || selectedAction == null) return;
    switch (selectedAction) {
      case _ConversationContextAction.pin:
        await _setConversationPinned(
          conversation,
          pinned: !conversation.isPinned,
        );
      case _ConversationContextAction.rename:
        _startEditingTitle(conversation);
      case _ConversationContextAction.copy:
        await _copyConversation(conversation);
      case _ConversationContextAction.select:
        _startConversationSelection(conversation);
      case _ConversationContextAction.archive:
        if (conversation.isArchived) {
          await _unarchiveConversation(conversation);
        } else {
          await _archiveConversation(conversation);
        }
      case _ConversationContextAction.delete:
        await _deleteConversation(conversation);
    }
  }

  Widget _buildConversationMenuOption(
    BuildContext dialogContext, {
    required _ConversationContextAction action,
    required IconData icon,
    required String label,
    bool isDestructive = false,
  }) {
    return OmniMenuItem(
      key: ValueKey('home-drawer-context-${action.name}'),
      label: label,
      leading: Icon(icon),
      destructive: isDestructive,
      onTap: () {
        if (ModalRoute.of(dialogContext)?.isCurrent != true) return;
        performOmniSelectionHaptic();
        Navigator.of(dialogContext).pop(action);
      },
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
}
