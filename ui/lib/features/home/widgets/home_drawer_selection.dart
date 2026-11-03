// ignore_for_file: invalid_use_of_protected_member

part of 'home_drawer.dart';

extension _HomeDrawerSelection on HomeDrawerState {
  String _selectionText(String zh, String en) =>
      Localizations.localeOf(context).languageCode == 'en' ? en : zh;

  /// Matches the rows exposed by the current search/filter and expanded groups.
  /// Offscreen rows in those groups remain selectable; folded descendants do not.
  Map<String, ConversationModel> get _visibleConversationRows {
    final rows = <String, ConversationModel>{};
    void add(ConversationModel conversation) {
      rows[conversation.threadKey] = conversation;
    }

    if (_isSearchActive) {
      for (final result in _visibleConversationResults) {
        add(result.conversation);
      }
      return rows;
    }
    if (_isConversationSectionExpanded(_kScheduledConversationSectionKey)) {
      for (final group in _scheduledConversationGroups) {
        if (!_matchesHomeFilter(group.parent)) continue;
        add(group.parent);
        if (_isConversationSectionExpanded(
          _scheduledParentConversationSectionKey(group.parent),
        )) {
          for (final child in group.children) {
            add(child.conversation);
          }
        }
      }
    }
    if (_isConversationSectionExpanded(_kPinnedConversationSectionKey)) {
      for (final result in _pinnedConversationResults) {
        add(result.conversation);
      }
    }
    for (final result in _visibleConversationResults) {
      add(result.conversation);
    }
    return rows;
  }

  Map<String, ConversationModel> get _selectableVisibleConversations => {
    for (final entry in _visibleConversationRows.entries)
      if (!_busyConversationKeys.contains(entry.key)) entry.key: entry.value,
  };

  void _startConversationSelection(ConversationModel conversation) {
    if (_isBatchConversationActionRunning ||
        !_allConversations.any(
          (row) => row.threadKey == conversation.threadKey,
        )) {
      return;
    }
    _searchFocusNode.unfocus();
    setState(() {
      _isSelectingConversations = true;
      _selectedConversationKeys.add(conversation.threadKey);
      _selectionFeedback = null;
    });
  }

  void _toggleConversationSelection(ConversationModel conversation) {
    if (_isBatchConversationActionRunning ||
        _busyConversationKeys.contains(conversation.threadKey)) {
      return;
    }
    performOmniSelectionHaptic();
    setState(() {
      if (!_selectedConversationKeys.remove(conversation.threadKey)) {
        _selectedConversationKeys.add(conversation.threadKey);
      }
      _selectionFeedback = null;
    });
  }

  void _exitConversationSelection() {
    if (_isBatchConversationActionRunning) return;
    setState(() {
      _isSelectingConversations = false;
      _selectedConversationKeys.clear();
      _selectionFeedback = null;
    });
  }

  void _selectAllVisibleConversations() {
    if (_isBatchConversationActionRunning) return;
    final visibleKeys = _selectableVisibleConversations.keys.toSet();
    if (visibleKeys.isEmpty) return;
    performOmniSelectionHaptic();
    setState(() {
      if (_selectedConversationKeys.containsAll(visibleKeys)) {
        _selectedConversationKeys.removeAll(visibleKeys);
      } else {
        _selectedConversationKeys.addAll(visibleKeys);
      }
      _selectionFeedback = null;
    });
  }

  void _reconcileConversationSelection() {
    if (_isBatchConversationActionRunning) return;
    final existing = _allConversations.map((row) => row.threadKey).toSet();
    _selectedConversationKeys.retainAll(existing);
  }

  Future<void> _runSelectedConversationAction({required bool delete}) async {
    if (_isBatchConversationActionRunning ||
        _selectedConversationKeys.isEmpty) {
      return;
    }
    final targets = _allConversations
        .where((row) => _selectedConversationKeys.contains(row.threadKey))
        .toList(growable: false);
    if (targets.isEmpty) return;
    final visibilityRevision =
        AssistsMessageService.conversationVisibilityRevision;
    // Lock before confirmation too, so repeated taps cannot stack dialogs.
    setState(() {
      _isBatchConversationActionRunning = true;
      _selectionFeedback = null;
    });
    var succeeded = 0;
    var failed = 0;
    var unavailable = 0;
    var submitted = false;
    try {
      if (delete) {
        final confirmed = await showOmniGlassCardSheet<bool>(
          context: context,
          builder: (dialogContext) => OmniGlassAlertDialog(
            title: Text(
              _selectionText('删除所选对话？', 'Delete selected conversations?'),
            ),
            content: Text(
              _selectionText(
                '将删除 ${targets.length} 个对话，此操作无法撤销。',
                '${targets.length} conversations will be deleted. This cannot be undone.',
              ),
            ),
            actions: [
              TextButton(
                key: const ValueKey('home-selection-delete-cancel'),
                onPressed: () => Navigator.of(dialogContext).pop(false),
                child: Text(
                  MaterialLocalizations.of(context).cancelButtonLabel,
                ),
              ),
              TextButton(
                key: const ValueKey('home-selection-delete-confirm'),
                onPressed: () => Navigator.of(dialogContext).pop(true),
                style: TextButton.styleFrom(
                  foregroundColor: Theme.of(context).colorScheme.error,
                ),
                child: Text(_selectionText('删除', 'Delete')),
              ),
            ],
          ),
        );
        if (!mounted || confirmed != true) return;
      }
      submitted = true;
      // A pending list read must not restore rows committed by this batch.
      _conversationLoadGeneration++;
      _searchGeneration++;
      _searchDebounceTimer?.cancel();
      setState(() {
        isLoadingConversations = false;
        _isSearching = false;
        _busyConversationKeys.addAll(targets.map((row) => row.threadKey));
      });
      for (final conversation in targets) {
        if (!mounted ||
            (conversation.agentId == 'na-cloud' &&
                visibilityRevision !=
                    AssistsMessageService.conversationVisibilityRevision) ||
            !_allConversations.any(
              (row) => row.threadKey == conversation.threadKey,
            )) {
          unavailable++;
          if (mounted) {
            setState(() {
              _selectedConversationKeys.remove(conversation.threadKey);
            });
          }
          continue;
        }
        var success = false;
        try {
          // These are the same canonical native operations as the row menu.
          // Native owns the history policy; UI does not cancel ACP turns.
          success = delete
              ? await ConversationService.deleteConversation(
                  conversation.id,
                  mode: conversation.mode,
                )
              : await ConversationService.archiveConversation(conversation);
        } catch (error) {
          debugPrint(
            '[HomeDrawer] batch conversation operation failed: $error',
          );
        }
        if (success) {
          succeeded++;
        } else {
          failed++;
        }
        if (!mounted) continue;
        setState(() {
          if (success) {
            _selectedConversationKeys.remove(conversation.threadKey);
            if (delete) {
              _removeConversationFromState(conversation);
            } else {
              final latest = _allConversations
                  .where((row) => row.threadKey == conversation.threadKey)
                  .firstOrNull;
              if (latest != null) {
                _replaceConversationInState(latest.copyWith(isArchived: true));
              }
            }
          }
        });
        if (success) _persistCurrentConversationSnapshot();
      }
    } finally {
      if (mounted) {
        if (submitted) {
          // Typing during a batch may have started another history search.
          // Its old snapshot must not reinsert successfully removed rows.
          _searchGeneration++;
          _searchDebounceTimer?.cancel();
        }
        setState(() {
          _isBatchConversationActionRunning = false;
          if (submitted) _isSearching = false;
          _busyConversationKeys.removeAll(targets.map((row) => row.threadKey));
          if (submitted && failed == 0 && unavailable == 0) {
            _isSelectingConversations = false;
            _selectedConversationKeys.clear();
          } else if (submitted) {
            _selectionFeedback = _selectionText(
              '已${delete ? '删除' : '归档'} $succeeded 个，$failed 个失败，请重试',
              '$succeeded ${delete ? 'deleted' : 'archived'}, $failed failed. Try again.',
            );
            if (unavailable > 0) {
              _selectionFeedback = _selectionText(
                '已${delete ? '删除' : '归档'} $succeeded 个，$unavailable 个对话已不可用，未操作'
                    '${failed > 0 ? '；$failed 个失败，请重试' : ''}',
                '$succeeded ${delete ? 'deleted' : 'archived'}, $unavailable unavailable and skipped.'
                    '${failed > 0 ? ' $failed failed. Try again.' : ''}',
              );
            }
          }
        });
        if (submitted && failed == 0 && unavailable == 0) {
          if (delete) unawaited(_triggerDeleteHaptic());
          showToast(
            _selectionText(
              '已${delete ? '删除' : '归档'} $succeeded 个对话',
              '$succeeded conversations ${delete ? 'deleted' : 'archived'}',
            ),
            type: ToastType.success,
          );
        }
        if (_refreshConversationsAfterBatch) {
          _refreshConversationsAfterBatch = false;
          unawaited(_loadConversations());
        } else if (_isSearchActive) {
          _scheduleConversationSearch(immediate: true);
        }
      }
    }
  }

  Widget _buildConversationSelectionHeader() {
    final palette = context.omniPalette;
    final count = Text(
      _selectionText(
        '已选择 ${_selectedConversationKeys.length} 项',
        '${_selectedConversationKeys.length} selected',
      ),
      key: const ValueKey('home-selection-count'),
      style: TextStyle(
        fontSize: _homeSearchVisible ? 17 : 13,
        color: _homeSearchVisible ? palette.textPrimary : palette.textSecondary,
      ),
    );
    return Padding(
      padding: const EdgeInsets.fromLTRB(22, 12, 14, 8),
      child: Row(
        children: [
          Expanded(
            child: _homeSearchVisible
                ? count
                : Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      _buildHomeAgentSwitcher(compact: true),
                      Padding(
                        padding: const EdgeInsets.only(left: 4),
                        child: count,
                      ),
                    ],
                  ),
          ),
          if (!_homeSearchVisible)
            IconButton(
              key: const ValueKey('home-selection-search'),
              onPressed: _isBatchConversationActionRunning
                  ? null
                  : _openHomeSearch,
              tooltip: _selectionText('搜索对话', 'Search conversations'),
              icon: const Icon(LucideIcons.search, size: 20),
            ),
          TextButton(
            key: const ValueKey('home-selection-cancel'),
            onPressed: _isBatchConversationActionRunning
                ? null
                : _exitConversationSelection,
            child: Text(MaterialLocalizations.of(context).cancelButtonLabel),
          ),
        ],
      ),
    );
  }

  Widget _buildConversationSelectionBar() {
    final visibleKeys = _selectableVisibleConversations.keys.toSet();
    final allVisibleSelected =
        visibleKeys.isNotEmpty &&
        _selectedConversationKeys.containsAll(visibleKeys);
    final enabled =
        !_isBatchConversationActionRunning &&
        _selectedConversationKeys.isNotEmpty;
    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 8, 14, 12),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (_selectionFeedback != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(
                  _selectionFeedback!,
                  key: const ValueKey('home-selection-feedback'),
                  style: TextStyle(
                    fontSize: 13,
                    color: Theme.of(context).colorScheme.error,
                  ),
                ),
              ),
            if (_isBatchConversationActionRunning &&
                _busyConversationKeys.isNotEmpty)
              const LinearProgressIndicator(
                key: ValueKey('home-selection-progress'),
                minHeight: 2,
              ),
            Row(
              children: [
                Expanded(
                  flex: 2,
                  child: TextButton(
                    key: const ValueKey('home-selection-all'),
                    onPressed:
                        _isBatchConversationActionRunning || visibleKeys.isEmpty
                        ? null
                        : _selectAllVisibleConversations,
                    child: Text(
                      allVisibleSelected
                          ? _selectionText('取消全选', 'Deselect visible')
                          : _selectionText('全选可见', 'Select visible'),
                    ),
                  ),
                ),
                Expanded(
                  child: TextButton(
                    key: const ValueKey('home-selection-archive'),
                    onPressed: enabled
                        ? () => _runSelectedConversationAction(delete: false)
                        : null,
                    child: Text(_selectionText('归档', 'Archive')),
                  ),
                ),
                Expanded(
                  child: TextButton(
                    key: const ValueKey('home-selection-delete'),
                    onPressed: enabled
                        ? () => _runSelectedConversationAction(delete: true)
                        : null,
                    style: TextButton.styleFrom(
                      foregroundColor: Theme.of(context).colorScheme.error,
                    ),
                    child: Text(_selectionText('删除', 'Delete')),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildConversationSelectionIndicator(ConversationModel conversation) {
    final selected = _selectedConversationKeys.contains(conversation.threadKey);
    return Padding(
      padding: const EdgeInsets.only(right: 12),
      child: Icon(
        selected ? LucideIcons.circleCheck : LucideIcons.circle,
        key: ValueKey('home-selection-check-${conversation.threadKey}'),
        size: 22,
        color: selected
            ? context.omniPalette.textPrimary
            : context.omniPalette.textTertiary,
      ),
    );
  }
}
