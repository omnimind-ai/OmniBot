// ignore_for_file: invalid_use_of_protected_member

part of 'home_drawer.dart';

extension _HomeDrawerSearch on HomeDrawerState {
  bool get _isSearchActive => _searchQuery.isNotEmpty;

  String get _searchQuery => _searchController.text.trim();

  List<_ConversationSearchResult> get _visibleConversationResults {
    if (_isSearchActive) {
      return _searchResults;
    }
    final reservedThreadKeys = _promotedConversationThreadKeys;
    return _allConversations
        .where(
          (conversation) =>
              !conversation.isArchived &&
              !conversation.isPinned &&
              !reservedThreadKeys.contains(conversation.threadKey),
        )
        .map(
          (conversation) =>
              _ConversationSearchResult(conversation: conversation),
        )
        .toList(growable: false);
  }

  Set<String> get _promotedConversationThreadKeys {
    final groups = _scheduledConversationGroups;
    final cached = _promotedThreadKeysCache;
    if (cached != null && identical(_promotedThreadKeysCacheSource, groups)) {
      return cached;
    }
    final keys = <String>{};
    for (final group in groups) {
      keys.add(group.parent.threadKey);
      for (final child in group.children) {
        keys.add(child.conversation.threadKey);
      }
    }
    _promotedThreadKeysCacheSource = groups;
    _promotedThreadKeysCache = keys;
    return keys;
  }

  List<_ConversationSearchResult> get _pinnedConversationResults {
    final reservedThreadKeys = _promotedConversationThreadKeys;
    return _allConversations
        .where(
          (conversation) =>
              !conversation.isArchived &&
              conversation.isPinned &&
              !reservedThreadKeys.contains(conversation.threadKey),
        )
        .map(
          (conversation) =>
              _ConversationSearchResult(conversation: conversation),
        )
        .toList(growable: false);
  }

  List<_ScheduledConversationGroup> get _scheduledConversationGroups {
    final cached = _scheduledGroupsCache;
    if (cached != null &&
        identical(_scheduledGroupsCacheConversations, _allConversations) &&
        identical(_scheduledGroupsCacheTasks, _scheduledTasks)) {
      return cached;
    }
    final groups = _computeScheduledConversationGroups();
    _scheduledGroupsCacheConversations = _allConversations;
    _scheduledGroupsCacheTasks = _scheduledTasks;
    _scheduledGroupsCache = groups;
    return groups;
  }

  List<_ScheduledConversationGroup> _computeScheduledConversationGroups() {
    final visibleConversations = _allConversations
        .where((conversation) => !conversation.isArchived)
        .toList(growable: false);
    if (visibleConversations.isEmpty) {
      return const <_ScheduledConversationGroup>[];
    }

    final conversationsByKey = <String, ConversationModel>{
      for (final conversation in visibleConversations)
        conversation.threadKey: conversation,
    };
    final parentKeys = <String>{};
    final activeScheduledTaskIds = <String>{};
    final activeParentKeys = <String>{};
    final taskCountByParentKey = <String, int>{};

    for (final task in _scheduledTasks) {
      if (task.targetKind != 'subagent') {
        continue;
      }
      activeScheduledTaskIds.add(task.id);
      final parentKey = _scheduledTaskParentThreadKey(task, conversationsByKey);
      if (parentKey == null || !conversationsByKey.containsKey(parentKey)) {
        continue;
      }
      activeParentKeys.add(parentKey);
      parentKeys.add(parentKey);
      taskCountByParentKey[parentKey] =
          (taskCountByParentKey[parentKey] ?? 0) + 1;
    }

    final childrenByParentKey = <String, List<_ConversationSearchResult>>{};
    for (final conversation in visibleConversations) {
      final parentKey = _conversationParentThreadKey(
        conversation,
        conversationsByKey,
      );
      if (parentKey == null || !conversationsByKey.containsKey(parentKey)) {
        continue;
      }
      final scheduledTaskId = (conversation.scheduledTaskId ?? '').trim();
      final belongsToActiveScheduledTask = scheduledTaskId.isNotEmpty
          ? activeScheduledTaskIds.contains(scheduledTaskId)
          : activeParentKeys.contains(parentKey);
      if (!belongsToActiveScheduledTask) {
        continue;
      }
      parentKeys.add(parentKey);
      childrenByParentKey
          .putIfAbsent(parentKey, () => <_ConversationSearchResult>[])
          .add(_ConversationSearchResult(conversation: conversation));
    }

    final groups = <_ScheduledConversationGroup>[];
    for (final parentKey in parentKeys) {
      final parent = conversationsByKey[parentKey];
      if (parent == null) {
        continue;
      }
      final children =
          childrenByParentKey[parentKey] ?? <_ConversationSearchResult>[];
      children.sort((a, b) {
        final byUpdatedAt = b.conversation.updatedAt.compareTo(
          a.conversation.updatedAt,
        );
        if (byUpdatedAt != 0) return byUpdatedAt;
        return b.conversation.createdAt.compareTo(a.conversation.createdAt);
      });
      groups.add(
        _ScheduledConversationGroup(
          parent: parent,
          children: children,
          taskCount: taskCountByParentKey[parentKey] ?? 0,
        ),
      );
    }

    groups.sort((a, b) {
      final aLatest = _scheduledGroupLatestTimestamp(a);
      final bLatest = _scheduledGroupLatestTimestamp(b);
      final byLatest = bLatest.compareTo(aLatest);
      if (byLatest != 0) return byLatest;
      return b.parent.createdAt.compareTo(a.parent.createdAt);
    });
    return groups;
  }

  String? _scheduledTaskParentThreadKey(
    ScheduledTask task,
    Map<String, ConversationModel> conversationsByKey,
  ) {
    final rawParentId =
        (task.parentConversationId ?? task.subagentConversationId ?? '').trim();
    final parentId = int.tryParse(rawParentId);
    if (parentId == null || parentId <= 0) {
      return null;
    }
    if (task.parentConversationMode == null ||
        task.parentConversationMode!.trim().isEmpty) {
      return _threadKeyForConversationId(parentId, conversationsByKey);
    }
    final parentMode = ConversationMode.fromStorageValue(
      task.parentConversationMode,
    );
    return '${parentMode.storageValue}:$parentId';
  }

  String? _conversationParentThreadKey(
    ConversationModel conversation,
    Map<String, ConversationModel> conversationsByKey,
  ) {
    final parentId = conversation.parentConversationId;
    if (parentId == null || parentId <= 0) {
      return null;
    }
    final parentMode = conversation.parentConversationMode;
    if (parentMode == null) {
      return _threadKeyForConversationId(parentId, conversationsByKey);
    }
    return '${parentMode.storageValue}:$parentId';
  }

  String? _threadKeyForConversationId(
    int conversationId,
    Map<String, ConversationModel> conversationsByKey,
  ) {
    for (final conversation in conversationsByKey.values) {
      if (conversation.id == conversationId) {
        return conversation.threadKey;
      }
    }
    return null;
  }

  int _scheduledGroupLatestTimestamp(_ScheduledConversationGroup group) {
    var latest = group.parent.updatedAt;
    for (final child in group.children) {
      if (child.conversation.updatedAt > latest) {
        latest = child.conversation.updatedAt;
      }
    }
    return latest;
  }

  Future<void> _loadConversations() async {
    final generation = ++_conversationLoadGeneration;
    setState(() {
      isLoadingConversations =
          _allConversations.isEmpty &&
          !HomeDrawerState._hasConversationSnapshotCache;
    });

    try {
      final loadedConversations = await ConversationService.getAllConversations(
        includeArchived: true,
      );
      final loadedScheduledTasks =
          await ScheduledTaskStorageService.loadScheduledTasks();
      if (!mounted || generation != _conversationLoadGeneration) return;
      final visibleThreadKeys = loadedConversations
          .map((conversation) => conversation.threadKey)
          .toSet();
      _conversationSearchCache.removeWhere(
        (threadKey, _) => !visibleThreadKeys.contains(threadKey),
      );
      _pruneConversationImagePreviewState(visibleThreadKeys);
      if (!mounted || generation != _conversationLoadGeneration) return;
      setState(() {
        _allConversations = loadedConversations;
        _scheduledTasks = loadedScheduledTasks;
        isLoadingConversations = false;
      });
      _rememberConversationSnapshotCache(loadedConversations);
      _scheduleDrawerSnapshotPersist(loadedConversations);
      _refreshConversationImagePreviewsInBackground(
        loadedConversations
            .where((conversation) => !conversation.isArchived)
            .toList(growable: false),
        generation: generation,
      );
      if (_isSearchActive) {
        _scheduleConversationSearch(immediate: true);
      }
    } catch (e) {
      if (!mounted || generation != _conversationLoadGeneration) return;
      setState(() {
        isLoadingConversations = false;
      });
    }
  }

  void _handleSearchFocusChanged() {
    widget.onSearchFocusChanged?.call(_searchFocusNode.hasFocus);
  }

  void _handleSearchQueryChanged() {
    if (!mounted) {
      return;
    }
    _searchGeneration += 1;
    _searchDebounceTimer?.cancel();

    if (_searchQuery.isEmpty) {
      setState(() {
        _searchResults = <_ConversationSearchResult>[];
        _isSearching = false;
      });
      return;
    }

    setState(() {});
    _scheduleConversationSearch();
  }

  void _scheduleConversationSearch({bool immediate = false}) {
    final query = _searchQuery;
    if (query.isEmpty) {
      return;
    }

    final generation = _searchGeneration;
    _searchDebounceTimer?.cancel();
    void callback() {
      unawaited(_performConversationSearch(query, generation: generation));
    }

    if (immediate) {
      callback();
      return;
    }
    _searchDebounceTimer = Timer(
      HomeDrawerState._searchDebounceDuration,
      callback,
    );
  }

  Future<void> _performConversationSearch(
    String query, {
    required int generation,
  }) async {
    final trimmedQuery = query.trim();
    if (trimmedQuery.isEmpty || generation != _searchGeneration) {
      return;
    }

    final queryTokens = _tokenizeSearchQuery(trimmedQuery);
    if (queryTokens.isEmpty) {
      if (!mounted || generation != _searchGeneration) {
        return;
      }
      setState(() {
        _searchResults = <_ConversationSearchResult>[];
        _isSearching = false;
      });
      return;
    }

    if (mounted) {
      setState(() {
        _isSearching = true;
      });
    }

    final snapshot = List<ConversationModel>.from(_allConversations);
    final results = <_ConversationSearchResult>[];

    for (final conversation in snapshot) {
      if (!mounted || generation != _searchGeneration) {
        return;
      }
      final result = await _matchConversationAgainstQuery(
        conversation,
        queryTokens,
      );
      if (!mounted || generation != _searchGeneration) {
        return;
      }
      if (result != null) {
        results.add(result);
      }
    }

    if (!mounted || generation != _searchGeneration) {
      return;
    }
    setState(() {
      _searchResults = results;
      _isSearching = false;
    });
  }

  Future<_ConversationSearchResult?> _matchConversationAgainstQuery(
    ConversationModel conversation,
    List<String> queryTokens,
  ) async {
    final metadataCandidates = _buildConversationMetadataCandidates(
      conversation,
    );
    if (_matchesSearchTokens(
      _normalizeSearchText(metadataCandidates.join('\n')),
      queryTokens,
    )) {
      return _ConversationSearchResult(
        conversation: conversation,
        matchedPreview: _resolveMatchedPreview(
          candidates: metadataCandidates,
          conversation: conversation,
          queryTokens: queryTokens,
        ),
      );
    }

    final searchIndex = await _ensureConversationSearchIndex(conversation);
    if (!_matchesSearchTokens(searchIndex.searchableText, queryTokens)) {
      return null;
    }

    return _ConversationSearchResult(
      conversation: conversation,
      matchedPreview: _resolveMatchedPreview(
        candidates: searchIndex.candidates,
        conversation: conversation,
        queryTokens: queryTokens,
      ),
    );
  }

  Future<_ConversationSearchIndex> _ensureConversationSearchIndex(
    ConversationModel conversation,
  ) async {
    final signature = _conversationSearchSignature(conversation);
    final cacheKey = conversation.threadKey;
    final cached = _conversationSearchCache[cacheKey];
    if (cached != null && cached.signature == signature) {
      return cached;
    }

    final candidates = _buildConversationMetadataCandidates(conversation);
    final seenCandidates = candidates
        .map(_normalizeSearchText)
        .where((value) => value.isNotEmpty)
        .toSet();
    final messages = await ConversationHistoryService.getConversationMessages(
      conversation.id,
      mode: conversation.mode,
      expectedMessageCount: conversation.messageCount,
    );

    for (final message in messages) {
      for (final fragment in _collectSearchableText(message)) {
        _addUniqueCandidate(candidates, seenCandidates, fragment);
      }
    }

    final searchIndex = _ConversationSearchIndex(
      signature: signature,
      candidates: List<String>.unmodifiable(candidates),
      searchableText: _normalizeSearchText(candidates.join('\n')),
    );
    _conversationSearchCache[cacheKey] = searchIndex;
    return searchIndex;
  }

  List<String> _buildConversationMetadataCandidates(
    ConversationModel conversation,
  ) {
    final candidates = <String>[];
    final seenCandidates = <String>{};
    _addUniqueCandidate(
      candidates,
      seenCandidates,
      _resolveConversationTitle(conversation),
    );
    _addUniqueCandidate(candidates, seenCandidates, conversation.summary);
    _addUniqueCandidate(
      candidates,
      seenCandidates,
      conversation.contextSummary,
    );
    _addUniqueCandidate(candidates, seenCandidates, conversation.lastMessage);
    return candidates;
  }

  List<String> _collectSearchableText(ChatMessageModel message) {
    final fragments = <String>[];
    final seenCandidates = <String>{};
    _collectSearchableTextFromValue(
      message.content,
      sink: fragments,
      seenNormalized: seenCandidates,
    );
    return fragments;
  }

  void _collectSearchableTextFromValue(
    dynamic value, {
    required List<String> sink,
    required Set<String> seenNormalized,
  }) {
    if (value == null) {
      return;
    }
    if (value is String) {
      _addUniqueCandidate(sink, seenNormalized, value);
      return;
    }
    if (value is List) {
      for (final item in value) {
        _collectSearchableTextFromValue(
          item,
          sink: sink,
          seenNormalized: seenNormalized,
        );
      }
      return;
    }
    if (value is Map) {
      for (final item in value.values) {
        _collectSearchableTextFromValue(
          item,
          sink: sink,
          seenNormalized: seenNormalized,
        );
      }
    }
  }

  void _addUniqueCandidate(
    List<String> candidates,
    Set<String> seenNormalized,
    String? rawValue,
  ) {
    final normalized = _normalizeSearchText(rawValue ?? '');
    if (normalized.isEmpty || !seenNormalized.add(normalized)) {
      return;
    }
    candidates.add((rawValue ?? '').replaceAll(RegExp(r'\s+'), ' ').trim());
  }

  List<String> _tokenizeSearchQuery(String value) {
    return _normalizeSearchText(value)
        .split(RegExp(r'\s+'))
        .where((token) => token.isNotEmpty)
        .toList(growable: false);
  }

  String _normalizeSearchText(String value) {
    return value.replaceAll(RegExp(r'\s+'), ' ').trim().toLowerCase();
  }

  bool _matchesSearchTokens(String searchableText, List<String> queryTokens) {
    if (searchableText.isEmpty || queryTokens.isEmpty) {
      return false;
    }
    return queryTokens.every(searchableText.contains);
  }

  String? _resolveMatchedPreview({
    required List<String> candidates,
    required ConversationModel conversation,
    required List<String> queryTokens,
  }) {
    final title = _resolveConversationTitle(conversation);
    for (final candidate in candidates) {
      final normalized = _normalizeSearchText(candidate);
      if (_matchesSearchTokens(normalized, queryTokens) &&
          candidate.trim() != title) {
        return candidate.trim();
      }
    }
    for (final candidate in candidates) {
      final normalized = _normalizeSearchText(candidate);
      if (queryTokens.any(normalized.contains) && candidate.trim() != title) {
        return candidate.trim();
      }
    }
    return null;
  }

  String _conversationSearchSignature(ConversationModel conversation) {
    return [
      conversation.threadKey,
      conversation.updatedAt,
      conversation.messageCount,
      conversation.isArchived ? 1 : 0,
      conversation.isPinned ? 1 : 0,
      conversation.parentConversationId ?? '',
      conversation.parentConversationMode?.storageValue ?? '',
      conversation.scheduledTaskId ?? '',
      conversation.title,
      conversation.summary ?? '',
      conversation.contextSummary ?? '',
      conversation.lastMessage ?? '',
    ].join('|');
  }
}
