part of 'chat_conversation_runtime_coordinator.dart';

extension ChatRuntimeSnapshotSupport on ChatConversationRuntimeCoordinator {
  void replaceConversationSnapshot({
    required int conversationId,
    required String mode,
    required List<ChatMessageModel> messages,
    ConversationModel? conversation,
    bool isAiResponding = false,
    bool isContextCompressing = false,
    bool isCheckingExecutableTask = false,
    Map<String, String>? currentAiMessages,
    Map<String, String>? currentThinkingMessages,
    String deepThinkingContent = '',
    bool isDeepThinking = false,
    String? currentDispatchTurnId,
    int currentThinkingStage = 1,
    bool isInputAreaVisible = true,
    bool isExecutingTask = false,
    String? lastAgentTurnId,
    String? activeToolCardId,
    String? activeThinkingCardId,
    String? activeContextCompactionMarkerId,
    String? pendingAgentTextTaskId,
    bool pendingThinkingRoundSplit = false,
    int toolCardSequence = 0,
    int thinkingRound = 0,
    ChatIslandDisplayLayer chatIslandDisplayLayer = ChatIslandDisplayLayer.mode,
    String? lastAgentToolType,
    ChatBrowserSessionSnapshot? browserSessionSnapshot,
    bool preserveLiveStreamingState = false,
    int expectedHistoryRevision = 0,
  }) {
    final runtime = ensureRuntime(
      conversationId: conversationId,
      mode: mode,
      conversation: conversation,
    );
    if (runtime.historyEditPending || runtime.historyRevision != expectedHistoryRevision) return;
    final hasBoundLiveTask = _taskBindings.entries.any((entry) {
      final binding = entry.value;
      if (binding.conversationId != conversationId || binding.mode != mode) {
        return false;
      }
      final taskId = entry.key;
      return runtime.activeRunId == taskId ||
          runtime.currentDispatchTurnId == taskId ||
          runtime.lastAgentTurnId == taskId;
    });
    // Only the host reservation proves live work. Render flags from history
    // cannot admit a prompt, even when they were captured while it was running.
    if (!hasBoundLiveTask) {
      isAiResponding = false;
      isContextCompressing = false;
      isCheckingExecutableTask = false;
      isExecutingTask = false;
      preserveLiveStreamingState = false;
      currentDispatchTurnId = null;
      lastAgentTurnId = null;
      currentAiMessages = null;
      currentThinkingMessages = null;
      deepThinkingContent = '';
      isDeepThinking = false;
      isInputAreaVisible = true;
      activeToolCardId = null;
      activeThinkingCardId = null;
      activeContextCompactionMarkerId = null;
      pendingAgentTextTaskId = null;
      pendingThinkingRoundSplit = false;
    }
    var normalizedMessages = _normalizeIdleAgentRequestCards(
      _normalizeIdleThinkingCards(
        _dedupeEquivalentAgentUserMessages(messages),
        isAiResponding: hasBoundLiveTask,
        preserveLiveStreamingState: hasBoundLiveTask,
      ),
      isAiResponding: hasBoundLiveTask,
      preserveLiveStreamingState: hasBoundLiveTask,
    );
    // When the caller is polling a remote codex thread while reducer push
    // events are still actively streaming into this runtime, we MUST NOT
    // blow away the push-driven streaming state. Otherwise the chat list
    // collapses for a single frame between each poll tick — the symptom
    // the user calls "codex 输出时自动折叠了一下又展开"。
    //
    // In that mode we only refresh the visible message list and conversation
    // metadata; everything else (isAiResponding, currentAiMessages,
    // currentThinkingMessages, currentDispatchTurnId, …) stays exactly as
    // the reducer left it.
    if (hasBoundLiveTask) {
      // A snapshot's render flags cannot re-admit a live turn or clear its
      // official ACP identity. Keep reducer-owned items and add genuinely new
      // history/user items by identity; stale copies cannot roll back updates.
      final knownIds = runtime.messages.map((message) => message.id).toSet();
      final mergedMessages = <ChatMessageModel>[
        ..._normalizeIdleAgentRequestCards(
          runtime.messages,
          isAiResponding: true,
          preserveLiveStreamingState: true,
        ),
        ...normalizedMessages.where((message) => knownIds.add(message.id)),
      ];
      _replaceRuntimeMessagesIfChanged(runtime, mergedMessages);
      runtime.conversation = conversation ?? runtime.conversation;
      _pruneAgentReplayDeltaOffsets(runtime, mergedMessages);
      notifyListeners();
      return;
    }
    // History reads can finish after PromptResponse. Preserve a committed
    // item when the same item in an older snapshot lacks its official terminal
    // result. Completed history may still enrich it (for example with usage).
    final committedItems = <String, ChatMessageModel>{
      for (final message in _normalizeIdleAgentRequestCards(
        runtime.messages,
        isAiResponding: true,
        preserveLiveStreamingState: true,
      ))
        if (message.streamMeta?['stopReason']?.toString().trim().isNotEmpty == true)
          message.id: message,
    };
    normalizedMessages = normalizedMessages.map((message) {
      if (message.streamMeta?['stopReason']?.toString().trim().isNotEmpty != true) {
        return committedItems[message.id] ?? message;
      }
      return message;
    }).toList();
    // A partial history page is not a deletion request. Keep items committed
    // since that page was read, including the user's query. Explicit deletion
    // belongs to persistConversationMessageSnapshot(allowHistoryRemoval: true).
    final snapshotIds = normalizedMessages.map((message) => message.id).toSet();
    normalizedMessages.addAll(
      _normalizeIdleAgentRequestCards(
        _normalizeIdleThinkingCards(runtime.messages,
          isAiResponding: false, preserveLiveStreamingState: false),
        isAiResponding: false, preserveLiveStreamingState: false,
      ).where((message) => snapshotIds.add(message.id)),
    );
    final hadInFlightTask = runtime.hasInFlightTask;
    final snapshotHasLiveWork =
        isAiResponding || isCheckingExecutableTask || isExecutingTask;
    // Page projection refreshes must not discard the admitted prompt's clock.
    // Only the matching live request may retain it; a restored snapshot cannot
    // manufacture timing for another request.
    final preservedPromptTimingKey =
        hasBoundLiveTask &&
            currentDispatchTurnId != null &&
            currentDispatchTurnId == runtime.currentDispatchTurnId
        ? 'prompt:$currentDispatchTurnId'
        : null;
    // Text maps are projection buffers, not lifecycle evidence. A partial
    // stream can survive a transport failure after ACP has already ended the
    // turn; using it here would resurrect a completed run during polling or
    // history restore.
    if (!snapshotHasLiveWork) {
      final previousRunId =
          runtime.activeRunId?.trim() ??
          runtime.currentDispatchTurnId?.trim() ??
          runtime.lastAgentTurnId?.trim() ??
          '';
      final previousTurnId = runtime.activeAcpTurnId?.trim() ?? '';
      if (previousRunId.isNotEmpty) {
        _rememberCompletedTurn(runtime, previousRunId);
      }
      if (previousTurnId.isNotEmpty) {
        _rememberCompletedTurn(runtime, previousTurnId);
        runtime.rememberCompletedAcpTurn(previousTurnId);
      }
    }
    _flushRuntimeStreamingText(runtime);
    _replaceRuntimeMessagesIfChanged(runtime, normalizedMessages);
    runtime.conversation = conversation ?? runtime.conversation;
    runtime.isAiResponding = isAiResponding;
    runtime.isContextCompressing = isContextCompressing;
    runtime.isCheckingExecutableTask = isCheckingExecutableTask;
    runtime.currentAiMessages
      ..clear()
      ..addAll(currentAiMessages ?? const <String, String>{});
    runtime.currentThinkingMessages
      ..clear()
      ..addAll(currentThinkingMessages ?? const <String, String>{});
    runtime.deepThinkingContent = deepThinkingContent;
    runtime.isDeepThinking = isDeepThinking;
    runtime.currentDispatchTurnId = currentDispatchTurnId;
    final snapshotRunId = normalizedMessages
        .map((message) => message.runId)
        .whereType<String>()
        .map((value) => value.trim())
        .firstWhere((value) => value.isNotEmpty, orElse: () => '');
    runtime.activeRunId = snapshotHasLiveWork
        ? (currentDispatchTurnId?.trim().isNotEmpty == true
              ? currentDispatchTurnId
              : (snapshotRunId.isEmpty ? null : snapshotRunId))
        : null;
    // A snapshot carries only a render hint. The official ACP turn identity
    // must be admitted by `turn/started`/`session/update`, never guessed from
    // a local placeholder id.
    runtime.activeAcpTurnId = null;
    // An idle persisted snapshot is authoritative during restore. Do not
    // carry the previous session into a completed conversation merely because
    // the runtime still had a stale dispatch id before this replacement.
    runtime.activeAcpSessionId = snapshotHasLiveWork && hadInFlightTask
        ? runtime.activeAcpSessionId
        : null;
    runtime.currentThinkingStage = currentThinkingStage;
    runtime.isInputAreaVisible = isInputAreaVisible;
    runtime.isExecutingTask = isExecutingTask;
    runtime.lastAgentTurnId = lastAgentTurnId;
    runtime.activeToolCardId = activeToolCardId;
    runtime.activeThinkingCardId = activeThinkingCardId;
    runtime.activeContextCompactionMarkerId = activeContextCompactionMarkerId;
    runtime.pendingAgentTextTaskId = pendingAgentTextTaskId;
    runtime.waitingThinkingBeforeAgentTextTaskId = null;
    runtime.pendingThinkingRoundSplit = pendingThinkingRoundSplit;
    runtime.toolCardSequence = toolCardSequence;
    runtime.thinkingRound = thinkingRound;
    runtime.chatIslandDisplayLayer = chatIslandDisplayLayer;
    runtime.lastAgentToolType = lastAgentToolType;
    runtime.browserSessionSnapshot = browserSessionSnapshot;
    runtime._streamingTextBatches.clear();
    runtime.agentEntrySequences.clear();
    runtime.agentEntryStartTimes.removeWhere(
      (key, _) => key != preservedPromptTimingKey,
    );
    _pruneAgentReplayDeltaOffsets(runtime, normalizedMessages);
    runtime.agentNextEntrySequence = 0;
    notifyListeners();
  }

  /// Updates the runtime projection and persists the same snapshot through
  /// the coordinator. Page-level link preview, edit, retry, and external
  /// message paths must use this seam instead of writing the destructive
  /// history replacement directly. When a live ACP turn exists, merge by
  /// message id so a stale page snapshot cannot erase streamed items.
  Future<void> persistConversationMessageSnapshot({
    required int conversationId,
    required String mode,
    required List<ChatMessageModel> messages,
    ConversationModel? conversation,
    bool allowHistoryRemoval = false,
    int expectedHistoryRevision = 0,
  }) async {
    final runtime = ensureRuntime(
      conversationId: conversationId,
      mode: mode,
      conversation: conversation,
    );
    if (allowHistoryRemoval && !runtime.hasInFlightTask) {
      final retainedIds = messages.map((message) => message.id).toSet();
      await deleteConversationMessageIds(
        conversationId: conversationId, mode: mode,
        messageIds: runtime.messages.where((m) => !retainedIds.contains(m.id)).map((m) => m.id).toSet(),
      );
      return;
    }
    if (runtime.historyEditPending || runtime.historyRevision != expectedHistoryRevision) return;
    final incoming = List<ChatMessageModel>.from(messages);
    if (runtime.hasInFlightTask || !allowHistoryRemoval) {
      final incomingById = <String, ChatMessageModel>{
        for (final message in incoming) message.id: message,
      };
      final merged = runtime.messages.map((message) {
        final pageMessage = incomingById.remove(message.id);
        if (pageMessage == null) return message;
        // ACP owns item content and lifecycle, including after completion.
        // An asynchronous page save may only enrich its link previews; it
        // must not replace a newer reducer projection with an older snapshot.
        if (message.streamMeta != null) {
          if (pageMessage.content?.containsKey('linkPreviews') != true) {
            return message;
          }
          return message.copyWith(
            content: <String, dynamic>{
              ...?message.content,
              'linkPreviews': pageMessage.content!['linkPreviews'],
            },
          );
        }
        return pageMessage;
      }).toList();
      if (incomingById.isNotEmpty) {
        merged.addAll(incomingById.values);
      }
      _replaceRuntimeMessagesIfChanged(runtime, merged);
    } else {
      _replaceRuntimeMessagesIfChanged(runtime, incoming);
    }
    runtime.conversation = conversation ?? runtime.conversation;
    notifyListeners();
    await persistRuntimeConversation(
      conversationId: conversationId,
      mode: mode,
      persistMessages: true,
      allowEphemeralPersistence: true,
      allowHistoryRemoval: allowHistoryRemoval,
    );
  }

  /// Explicit user edit. Storage commits before the visible list changes.
  Future<void> deleteConversationMessageIds({
    required int conversationId, required String mode, required Set<String> messageIds,
  }) async {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null || messageIds.isEmpty) return;
    if (runtime.hasInFlightTask || runtime.historyEditPending) {
      throw StateError('Wait for the current conversation operation to finish');
    }
    final ids = Set<String>.from(messageIds);
    runtime.historyEditPending = true;
    runtime.historyRevision++;
    _cancelPendingPersistence(conversationId: conversationId, mode: mode);
    final key = _runtimeKey(conversationId: conversationId, mode: mode);
    final previous = _persistenceTails[key] ?? Future<void>.value();
    final operation = previous.catchError((Object _) {}).then((_) async {
      await ConversationHistoryService.deleteMessageIds(conversationId, ids,
        mode: _conversationModeFromRuntimeMode(mode, conversation: runtime.conversation));
      if (!identical(runtimeFor(conversationId: conversationId, mode: mode), runtime)) return;
      _replaceRuntimeMessagesIfChanged(runtime,
        runtime.messages.where((message) => !ids.contains(message.id)).toList());
      runtime.currentAiMessages.removeWhere((id, _) => ids.contains(id));
      runtime.currentThinkingMessages.removeWhere((id, _) => ids.contains(id));
    });
    _persistenceTails[key] = operation;
    try {
      await operation;
    } finally {
      _removePersistenceTail(key, operation);
      runtime.historyRevision++;
      runtime.historyEditPending = false;
      notifyListeners();
    }
  }

  Future<void> updateConversationLinkPreviews({
    required int conversationId, required String mode, required String messageId,
    required int expectedHistoryRevision, required List<dynamic> previews,
  }) async {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null || runtime.historyEditPending ||
        runtime.historyRevision != expectedHistoryRevision) return;
    final index = runtime.messages.indexWhere((message) => message.id == messageId);
    if (index < 0) return;
    final message = runtime.messages[index];
    final updated = List<ChatMessageModel>.from(runtime.messages);
    updated[index] = message.copyWith(content: {
      ...?message.content, 'linkPreviews': previews,
    });
    _replaceRuntimeMessagesIfChanged(runtime, updated);
    notifyListeners();
    await persistRuntimeConversation(conversationId: conversationId, mode: mode,
      persistMessages: true, allowEphemeralPersistence: true);
  }

  /// A persisted snapshot can outlive the terminal ACP event (for example if
  /// the app was backgrounded during the final frame). Never resurrect its
  /// pre-created thinking spinner when the runtime is already idle.
  List<ChatMessageModel> _normalizeIdleThinkingCards(
    List<ChatMessageModel> messages, {
    required bool isAiResponding,
    required bool preserveLiveStreamingState,
  }) {
    if (isAiResponding || preserveLiveStreamingState) {
      return messages;
    }
    final now = DateTime.now().millisecondsSinceEpoch;
    return messages
        .map((message) {
          final existingCardData = message.cardData;
          if (message.type != 2 ||
              existingCardData?['type'] != 'deep_thinking' ||
              existingCardData?['isLoading'] != true) {
            return message;
          }
          final cardData = Map<String, dynamic>.from(existingCardData!);
          cardData['isLoading'] = false;
          cardData['stage'] = ThinkingStage.complete.value;
          cardData['endTime'] ??= now;
          cardData['isCollapsible'] = true;
          return message.copyWith(
            content: <String, dynamic>{'cardData': cardData, 'id': message.id},
          );
        })
        .toList(growable: false);
  }

  /// A server request is a live ACP JSON-RPC request. Its id is not a
  /// resumable conversation item: after the process/session ends there is no
  /// transport request left for the UI to answer. Persisting it as `pending`
  /// makes the composer offer a response that can only fail with "unknown
  /// request". Keep the history item for auditability, but make the lifecycle
  /// terminal when restoring an idle snapshot.
  List<ChatMessageModel> _normalizeIdleAgentRequestCards(
    List<ChatMessageModel> messages, {
    required bool isAiResponding,
    required bool preserveLiveStreamingState,
  }) {
    return messages
        .map((message) {
          final existingCardData = message.cardData;
          if (message.type != 2 ||
              existingCardData?['type'] != 'agent_request' ||
              existingCardData?['status']?.toString().trim().toLowerCase() !=
                  'pending' ||
              existingCardData?['requestId'] == null ||
              existingCardData?['interactionUnavailable'] == true) {
            return message;
          }
          final hasPromptOutcome = message.streamMeta?['stopReason']
                  ?.toString().trim().isNotEmpty == true;
          if (!hasPromptOutcome && (isAiResponding || preserveLiveStreamingState)) {
            return message;
          }
          final cardData = Map<String, dynamic>.from(existingCardData!);
          // A persisted PromptResponse owns this old request even while a
          // different prompt is active. Do not infer the new prompt's state.
          cardData['status'] = hasPromptOutcome ? 'cancelled' : 'expired';
          cardData['interactionUnavailable'] = true;
          cardData['interactionUnavailableReason'] = 'session_ended';
          return message.copyWith(
            content: <String, dynamic>{...?message.content, 'cardData': cardData, 'id': message.id},
          );
        })
        .toList(growable: false);
  }

  void _replaceRuntimeMessagesIfChanged(
    ChatConversationRuntimeState runtime,
    List<ChatMessageModel> messages,
  ) {
    final current = runtime.messages;
    if (current.length == messages.length) {
      var sameInstancesInOrder = true;
      for (var index = 0; index < messages.length; index += 1) {
        if (!identical(current[index], messages[index])) {
          sameInstancesInOrder = false;
          break;
        }
      }
      if (sameInstancesInOrder) {
        return;
      }
    }
    current.replaceAllMessages(messages);
  }

  void _pruneAgentReplayDeltaOffsets(
    ChatConversationRuntimeState runtime,
    List<ChatMessageModel> messages,
  ) {
    if (runtime.agentReplayDeltaOffsets.isEmpty) {
      return;
    }
    final liveEntryIds = <String>{};
    for (final message in messages) {
      liveEntryIds.add(message.id);
      final entryId = message.streamMeta?['entryId']?.toString().trim();
      if (entryId != null && entryId.isNotEmpty) {
        liveEntryIds.add(entryId);
      }
      final cardId = message.cardData?['cardId']?.toString().trim();
      if (cardId != null && cardId.isNotEmpty) {
        liveEntryIds.add(cardId);
      }
    }
    runtime.agentReplayDeltaOffsets.removeWhere(
      (entryId, _) => !liveEntryIds.contains(entryId),
    );
  }
}
