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
  }) {
    final normalizedMessages = _normalizeIdleAgentRequestCards(
      _normalizeIdleThinkingCards(
        _dedupeEquivalentAgentUserMessages(messages),
        isAiResponding: isAiResponding,
        preserveLiveStreamingState: preserveLiveStreamingState,
      ),
      isAiResponding: isAiResponding,
      preserveLiveStreamingState: preserveLiveStreamingState,
    );
    final runtime = ensureRuntime(
      conversationId: conversationId,
      mode: mode,
      conversation: conversation,
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
    if (preserveLiveStreamingState) {
      _replaceRuntimeMessagesIfChanged(runtime, normalizedMessages);
      runtime.conversation = conversation ?? runtime.conversation;
      _pruneAgentReplayDeltaOffsets(runtime, normalizedMessages);
      notifyListeners();
      return;
    }
    final hadInFlightTask = runtime.hasInFlightTask;
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
    final snapshotHasLiveWork =
        isAiResponding || isCheckingExecutableTask || isExecutingTask;
    if (hasBoundLiveTask && !snapshotHasLiveWork) {
      // A history/poll snapshot has no turn identity. Once this runtime has
      // admitted a new logical turn, an idle snapshot is necessarily older or
      // incomplete and must not demote the live ACP lifecycle. Merge only
      // messages that are not already present; terminal ACP events (or the
      // explicit unregister path) are the sole owners allowed to end the
      // active turn.
      final incomingById = <String, ChatMessageModel>{
        for (final message in normalizedMessages) message.id: message,
      };
      final mergedMessages = runtime.messages
          .map((message) => incomingById.remove(message.id) ?? message)
          .toList();
      if (incomingById.isNotEmpty) {
        mergedMessages.addAll(incomingById.values);
      }
      _replaceRuntimeMessagesIfChanged(runtime, mergedMessages);
      runtime.conversation = conversation ?? runtime.conversation;
      _pruneAgentReplayDeltaOffsets(runtime, mergedMessages);
      notifyListeners();
      return;
    }
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
    runtime.agentEntryStartTimes.clear();
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
  }) async {
    final runtime = ensureRuntime(
      conversationId: conversationId,
      mode: mode,
      conversation: conversation,
    );
    final incoming = List<ChatMessageModel>.from(messages);
    if (runtime.hasInFlightTask) {
      final incomingById = <String, ChatMessageModel>{
        for (final message in incoming) message.id: message,
      };
      final merged = runtime.messages
          .map((message) => incomingById.remove(message.id) ?? message)
          .toList();
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
    );
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
    if (isAiResponding || preserveLiveStreamingState) {
      return messages;
    }
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
          final cardData = Map<String, dynamic>.from(existingCardData!);
          cardData['status'] = 'expired';
          cardData['interactionUnavailable'] = true;
          cardData['interactionUnavailableReason'] = 'session_ended';
          return message.copyWith(
            content: <String, dynamic>{'cardData': cardData, 'id': message.id},
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
