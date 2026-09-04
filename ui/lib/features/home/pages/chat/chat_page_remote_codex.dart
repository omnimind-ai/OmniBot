part of 'chat_page.dart';

extension _ChatPageRemoteCodexSupport on _ChatPageStateBase {
  void _startRemoteCodexSessionSync(String threadId) {
    final normalizedThreadId = threadId.trim();
    if (normalizedThreadId.isEmpty) {
      return;
    }
    if (_remoteCodexSessionSyncThreadId == normalizedThreadId &&
        _remoteCodexSessionSyncTimer != null) {
      return;
    }
    _remoteCodexSessionSyncThreadId = normalizedThreadId;
    _remoteCodexSessionSyncSignature = '';
    _remoteCodexSessionSyncTimer?.cancel();
    _remoteCodexSessionSyncTimer = Timer.periodic(
      const Duration(seconds: 2),
      (_) => unawaited(_syncRemoteCodexSessionSnapshot()),
    );
    unawaited(_syncRemoteCodexSessionSnapshot());
  }

  void _stopRemoteCodexSessionSync() {
    _remoteCodexSessionSyncTimer?.cancel();
    _remoteCodexSessionSyncTimer = null;
    _remoteCodexSessionSyncInFlight = false;
    _remoteCodexSessionSyncThreadId = null;
    _remoteCodexSessionSyncSignature = '';
    _remoteCodexActivityThreadId = null;
    _remoteCodexActivityContentSignature = '';
    _remoteCodexLastContentChangeAtMs = null;
  }

  bool _inferRemoteCodexSnapshotActive({
    required String threadId,
    required Map<String, dynamic> response,
    required _AgentThreadActivityState activity,
    required bool previousActive,
    required bool assumeActive,
    required String? directActiveTurnId,
  }) {
    if (!_isRemoteCodexConfigured()) {
      return false;
    }

    final nowMs = DateTime.now().millisecondsSinceEpoch;
    if (_remoteCodexActivityThreadId != threadId) {
      _remoteCodexActivityThreadId = threadId;
      _remoteCodexActivityContentSignature = '';
      _remoteCodexLastContentChangeAtMs = null;
    }

    final contentSignature = _remoteCodexThreadContentSignature(response);
    final firstObservation = _remoteCodexActivityContentSignature.isEmpty;
    final contentChanged =
        contentSignature.isNotEmpty &&
        contentSignature != _remoteCodexActivityContentSignature;
    if (contentSignature.isNotEmpty && contentChanged) {
      _remoteCodexActivityContentSignature = contentSignature;
      _remoteCodexLastContentChangeAtMs = nowMs;
    }

    if (directActiveTurnId != null || activity.active) {
      _remoteCodexLastContentChangeAtMs = nowMs;
      return true;
    }

    final looksExternallyActive = _remoteCodexLatestTurnLooksExternallyActive(
      response,
    );
    if (activity.known && !activity.active) {
      // Caller hint wins over Kotlin's authoritative-but-stale active=false:
      // when the user opens a session that the remote codex had already been
      // working on before this client connected, Kotlin's activeTurnsByThreadId
      // is empty so it injects active=false even though codex is in fact still
      // streaming. Trust assumeActive (sourced from the sessions list's
      // session.active flag) for this initial observation.
      if (assumeActive) {
        _remoteCodexLastContentChangeAtMs ??= nowMs;
        return true;
      }
      if (!firstObservation && contentChanged && looksExternallyActive) {
        _remoteCodexLastContentChangeAtMs = nowMs;
        return true;
      }
      final lastChangeAt = _remoteCodexLastContentChangeAtMs;
      if (previousActive && looksExternallyActive && lastChangeAt != null) {
        final ageMs = nowMs - lastChangeAt;
        if (ageMs <= _remoteCodexExternalActiveGrace.inMilliseconds) {
          return true;
        }
      }
      _remoteCodexLastContentChangeAtMs = null;
      return false;
    }

    if (assumeActive) {
      _remoteCodexLastContentChangeAtMs ??= nowMs;
      return true;
    }

    if (!firstObservation && contentChanged && looksExternallyActive) {
      _remoteCodexLastContentChangeAtMs = nowMs;
      return true;
    }

    final lastChangeAt = _remoteCodexLastContentChangeAtMs;
    if (previousActive && lastChangeAt != null) {
      final ageMs = nowMs - lastChangeAt;
      if (ageMs <= _remoteCodexExternalActiveGrace.inMilliseconds) {
        return true;
      }
    }

    return false;
  }

  Future<void> _syncRemoteCodexSessionSnapshot() async {
    if (_remoteCodexSessionSyncInFlight) {
      return;
    }
    final threadId = _remoteCodexSessionSyncThreadId?.trim() ?? '';
    if (threadId.isEmpty ||
        !mounted ||
        _activeConversationMode != ChatPageMode.agent ||
        !_isRemoteCodexConfigured() ||
        _activeAgentThreadId?.trim() != threadId) {
      return;
    }
    _remoteCodexSessionSyncInFlight = true;
    try {
      final response = await _readRemoteCodexThreadSnapshot(threadId);
      if (!mounted ||
          _remoteCodexSessionSyncThreadId != threadId ||
          _activeAgentThreadId?.trim() != threadId) {
        return;
      }
      _applyRemoteCodexThreadSnapshot(
        response: response,
        fallbackThreadId: threadId,
        fromPoll: true,
      );
    } catch (error) {
      debugPrint('Remote Agent session sync failed: $error');
    } finally {
      if (_remoteCodexSessionSyncThreadId == threadId) {
        _remoteCodexSessionSyncInFlight = false;
      }
    }
  }

  Future<Map<String, dynamic>> _readRemoteCodexThreadSnapshot(
    String threadId,
  ) async {
    try {
      return await AgentRuntimeService.readSession(
        sessionId: threadId,
        conversationMode: ConversationMode.agent.storageValue,
      );
    } catch (error) {
      debugPrint('Agent thread/read failed, falling back to resume: $error');
      return AgentRuntimeService.loadSession(
        sessionId: threadId,
        conversationMode: ConversationMode.agent.storageValue,
      );
    }
  }

  void _applyRemoteCodexThreadSnapshot({
    required Map<String, dynamic> response,
    required String fallbackThreadId,
    int? fallbackRuntimeId,
    List<ChatMessageModel>? fallbackMessages,
    ConversationModel? fallbackConversation,
    AgentRuntimeStatus? status,
    bool fromPoll = false,
    bool assumeActive = false,
  }) {
    final resolvedThreadId =
        _asAgentString(response['threadId']) ??
        _asAgentString(_asAgentMap(response['thread'])?['id']) ??
        fallbackThreadId;
    if (resolvedThreadId.isEmpty) {
      return;
    }
    final runtimeId =
        fallbackRuntimeId ?? _remoteCodexRuntimeId(resolvedThreadId);
    final runtime = _runtimeCoordinator.runtimeFor(
      conversationId: runtimeId,
      mode: kChatRuntimeModeAgent,
    );
    final activity = _remoteCodexThreadActivityFromResponse(response);
    final previousActive = runtime?.isAiResponding ?? false;
    final directActiveTurnId = _remoteCodexActiveTurnIdFromThreadResponse(
      response,
    );
    final inferredRemoteActive = _inferRemoteCodexSnapshotActive(
      threadId: resolvedThreadId,
      response: response,
      activity: activity,
      previousActive: previousActive,
      assumeActive: assumeActive,
      directActiveTurnId: directActiveTurnId,
    );
    final snapshotIsAiResponding =
        directActiveTurnId != null || activity.active || inferredRemoteActive;
    // The snapshot makes a definitive "no active turn" statement only when
    // BOTH Kotlin's bookkeeping AND the response payload agree: Kotlin
    // injects active=false (activeTurnsByThreadId dropped this thread after
    // turn/completed, thread/closed, status/changed inactive, or a terminal
    // error), AND no turn in the response still looks externally active.
    //
    // The looksExternallyActive guard matters for the cold-open path: if a
    // user opens a session that the remote codex was already working on,
    // Kotlin never saw turn/started so it injects active=false — yet the
    // response itself can still surface an in-progress latest turn. Without
    // this guard, the snapshot would wrongfully cancel out the assumeActive
    // hint (and later, the reducer's runtime active set by push events).
    final snapshotKnowsInactive =
        directActiveTurnId == null &&
        activity.known &&
        !activity.active &&
        !_remoteCodexLatestTurnLooksExternallyActive(response);
    if (snapshotKnowsInactive && runtime != null) {
      // `thread/read` is allowed to close a missed push lifecycle only after
      // Kotlin's active-turn registry and the remote payload agree that the
      // thread is idle. The coordinator still requires session/turn identity
      // when either side provides it, so an older poll cannot close a newer
      // prompt.
      final currentTaskId =
          runtime.activeRunId ??
          runtime.currentDispatchTurnId ??
          runtime.lastAgentTurnId;
      if (currentTaskId != null && currentTaskId.trim().isNotEmpty) {
        _runtimeCoordinator.finishTaskFromAuthoritativeSnapshot(
          taskId: currentTaskId.trim(),
          conversationId: runtimeId,
          mode: kChatRuntimeModeAgent,
          sessionId: resolvedThreadId,
          turnId: _remoteCodexLatestTurnIdFromThreadResponse(response),
        );
      }
    }
    // Otherwise floor against the reducer's runtime state. Snapshot polling
    // runs every 2s and would otherwise downgrade isAiResponding between
    // reasoning deltas when codex doesn't surface a "running" status in
    // thread/read.
    final isAiResponding =
        snapshotIsAiResponding || (previousActive && !snapshotKnowsInactive);
    final activeTurnId = isAiResponding
        ? (directActiveTurnId ??
              _remoteCodexLatestTurnIdFromThreadResponse(response) ??
              runtime?.currentDispatchTurnId ??
              runtime?.lastAgentTurnId ??
              _activeAgentTurnId)
        : null;
    final activeTaskId = isAiResponding
        ? (activeTurnId ??
              runtime?.currentDispatchTurnId ??
              runtime?.lastAgentTurnId ??
              'remote-agent-$resolvedThreadId')
        : null;
    // `activeTaskId` is the provider's ACP turn identity used by the
    // snapshot adapter to decide which historical turn is still active. It
    // is not the local render/ownership identity. When a remote session is
    // restored without an existing local runtime, create a deterministic
    // local namespace for this turn; when push streaming already owns the
    // runtime, preserve its existing local run id. Passing the ACP turn id
    // into replaceConversationSnapshot would make polling a second lifecycle
    // owner and would let a later provider id rename the visible run.
    final runtimeTaskId = isAiResponding
        ? (previousActive && runtime?.activeRunId?.trim().isNotEmpty == true
              ? runtime!.activeRunId!.trim()
              : 'remote-run:$resolvedThreadId:${activeTurnId ?? 'active'}')
        : null;
    final hasTurns = _remoteCodexThreadResponseHasTurns(response);
    final existingMessages = List<ChatMessageModel>.from(
      resolveVisibleChatMessages(
        runtimeMessages: runtime?.messages,
        fallbackMessages: _modeState(ChatPageMode.agent).messages,
        preserveFallbackDuringHandoff: _modeState(
          ChatPageMode.agent,
        ).isAiResponding,
      ),
    );
    final snapshotMessages = hasTurns
        ? _remoteCodexMessagesFromThreadResponse(
            response,
            active: isAiResponding,
            activeTurnId: activeTurnId,
          )
        : (fallbackMessages ?? existingMessages);
    final messages = hasTurns
        ? _mergeRemoteCodexSnapshotMessages(
            snapshotMessages: snapshotMessages,
            existingMessages: existingMessages,
            activeTaskId: activeTaskId,
            isAiResponding: isAiResponding,
          )
        : snapshotMessages;
    final conversation =
        (fallbackConversation ??
                _remoteCodexConversationFromResponse(
                  runtimeId: runtimeId,
                  response: response,
                ))
            .copyWith(messageCount: messages.length);
    final signature = _remoteCodexSnapshotSignature(
      threadId: resolvedThreadId,
      messages: messages,
      conversation: conversation,
      isAiResponding: isAiResponding,
      activeTaskId: activeTaskId,
    );
    if (fromPoll && signature == _remoteCodexSessionSyncSignature) {
      return;
    }
    _remoteCodexSessionSyncSignature = signature;

    if (!mounted) {
      return;
    }
    // Detect reducer push-driven streaming. When push events have populated
    // currentAiMessages / currentThinkingMessages on the runtime, the 2s poll
    // must not overwrite isAiResponding / dispatch ids / streaming buffers —
    // otherwise the timeline flips to isActive=false for one frame between
    // each tick and the codex run group visibly collapses-then-expands while
    // codex is still outputting (the symptom the user reported).
    final hasLivePushStreaming =
        runtime != null &&
        (runtime.currentAiMessages.isNotEmpty ||
            runtime.currentThinkingMessages.isNotEmpty ||
            runtime.messages.any(_isPendingAgentRequestMessage));
    final preserveLiveStreamingState = fromPoll && hasLivePushStreaming;
    setState(() {
      _activeRemoteCodexRuntimeId = runtimeId;
      _activeAgentThreadId = resolvedThreadId;
      if (!preserveLiveStreamingState) {
        _activeAgentTurnId = activeTurnId;
      }
      if (status != null) {
        _agentRuntimeStatus = status;
      }
      _modeState(ChatPageMode.agent).currentConversationId = runtimeId;
      _modeState(ChatPageMode.agent).currentConversation = conversation;
      _modeState(ChatPageMode.agent).messages
        ..clear()
        ..addAll(messages);
      _modeState(ChatPageMode.agent).hasMoreMessages = false;
      _modeState(ChatPageMode.agent).messageOffset = messages.length;
    });
    _runtimeCoordinator.ensureEphemeralRuntime(
      conversationId: runtimeId,
      mode: kChatRuntimeModeAgent,
      initialMessages: messages,
      conversation: conversation,
      initialChatIslandDisplayLayer: ChatIslandDisplayLayer.mode,
    );
    _runtimeCoordinator.replaceConversationSnapshot(
      conversationId: runtimeId,
      mode: kChatRuntimeModeAgent,
      messages: messages,
      conversation: conversation,
      isAiResponding: isAiResponding,
      isExecutingTask: isAiResponding,
      isDeepThinking: isAiResponding,
      deepThinkingContent: runtime?.deepThinkingContent ?? '',
      currentDispatchTurnId: runtimeTaskId,
      currentThinkingStage: isAiResponding
          ? ThinkingStage.thinking.value
          : ThinkingStage.complete.value,
      lastAgentTurnId: runtimeTaskId,
      chatIslandDisplayLayer: ChatIslandDisplayLayer.mode,
      preserveLiveStreamingState: preserveLiveStreamingState,
    );
    if (runtimeTaskId != null) {
      _runtimeCoordinator.registerTask(
        taskId: runtimeTaskId,
        conversationId: runtimeId,
        mode: kChatRuntimeModeAgent,
      );
    }
  }

  bool _isRemoteCodexConfigured() {
    final runtime = _agentRuntimeStatus.runtime?.trim();
    return runtime == 'remote' || _agentRuntimeStatus.remoteEnabled;
  }

  int _ensureRemoteCodexRuntimeForCurrentMessages() {
    final currentId = _modeState(ChatPageMode.agent).currentConversationId;
    if (currentId != null &&
        _runtimeCoordinator.isEphemeralRuntime(
          conversationId: currentId,
          mode: kChatRuntimeModeAgent,
        )) {
      return currentId;
    }
    final runtimeId = _activeAgentThreadId?.trim().isNotEmpty == true
        ? _remoteCodexRuntimeId(_activeAgentThreadId!)
        : (_activeRemoteCodexRuntimeId ??
              _remoteCodexRuntimeId(
                'pending-${DateTime.now().microsecondsSinceEpoch}',
              ));
    _activeRemoteCodexRuntimeId = runtimeId;
    _modeState(ChatPageMode.agent).currentConversationId = runtimeId;
    _modeState(ChatPageMode.agent).currentConversation ??= ConversationModel(
      id: runtimeId,
      mode: ConversationMode.agent,
      title: 'Agent',
      status: 0,
      lastMessage: _modeState(ChatPageMode.agent).messages.isNotEmpty
          ? _modeState(ChatPageMode.agent).messages.first.text
          : null,
      messageCount: _modeState(ChatPageMode.agent).messages.length,
      createdAt: DateTime.now().millisecondsSinceEpoch,
      updatedAt: DateTime.now().millisecondsSinceEpoch,
    );
    _runtimeCoordinator.ensureEphemeralRuntime(
      conversationId: runtimeId,
      mode: kChatRuntimeModeAgent,
      initialMessages: List<ChatMessageModel>.from(
        _modeState(ChatPageMode.agent).messages,
      ),
      conversation: _modeState(ChatPageMode.agent).currentConversation,
      initialChatIslandDisplayLayer: ChatIslandDisplayLayer.mode,
    );
    return runtimeId;
  }

  int _ensureRemoteCodexRuntimeForThread(String threadId) {
    final normalizedThreadId = threadId.trim();
    final runtimeId = _remoteCodexRuntimeId(normalizedThreadId);
    final now = DateTime.now().millisecondsSinceEpoch;
    _runtimeCoordinator.ensureEphemeralRuntime(
      conversationId: runtimeId,
      mode: kChatRuntimeModeAgent,
      conversation:
          _runtimeCoordinator
              .runtimeFor(
                conversationId: runtimeId,
                mode: kChatRuntimeModeAgent,
              )
              ?.conversation ??
          ConversationModel(
            id: runtimeId,
            mode: ConversationMode.agent,
            title:
                'Agent ${normalizedThreadId.length > 6 ? normalizedThreadId.substring(normalizedThreadId.length - 6) : normalizedThreadId}',
            status: 0,
            messageCount: 0,
            createdAt: now,
            updatedAt: now,
          ),
      initialChatIslandDisplayLayer: ChatIslandDisplayLayer.mode,
    );
    return runtimeId;
  }

  int _activateRemoteCodexRuntimeForThread(String threadId) {
    final normalizedThreadId = threadId.trim();
    final runtimeId = _ensureRemoteCodexRuntimeForThread(normalizedThreadId);
    final runtime = _runtimeCoordinator.runtimeFor(
      conversationId: runtimeId,
      mode: kChatRuntimeModeAgent,
    );
    if (runtime != null) {
      final visibleMessages = _modeState(ChatPageMode.agent).messages;
      if (visibleMessages.isNotEmpty) {
        final existingIds = runtime.messages
            .map((message) => message.id)
            .toSet();
        for (final message in visibleMessages.reversed) {
          if (existingIds.add(message.id)) {
            runtime.messages.add(message);
          }
        }
      }
      final currentConversation = _modeState(
        ChatPageMode.agent,
      ).currentConversation;
      if (currentConversation != null) {
        runtime.conversation = currentConversation.copyWith(id: runtimeId);
      }
      _modeState(ChatPageMode.agent).currentConversation = runtime.conversation;
    }
    _activeRemoteCodexRuntimeId = runtimeId;
    _activeAgentThreadId = normalizedThreadId;
    _modeState(ChatPageMode.agent).currentConversationId = runtimeId;
    _startRemoteCodexSessionSync(normalizedThreadId);
    return runtimeId;
  }

  bool _shouldPromoteRemoteCodexEventToVisibleThread({
    required String threadId,
    required int runtimeId,
  }) {
    final activeThreadId = _activeAgentThreadId?.trim();
    if (activeThreadId == threadId) {
      return true;
    }
    final currentConversationId = _modeState(
      ChatPageMode.agent,
    ).currentConversationId;
    if (currentConversationId == runtimeId) {
      return true;
    }
    if (activeThreadId != null && activeThreadId.isNotEmpty) {
      return false;
    }
    if (currentConversationId == null ||
        currentConversationId != _activeRemoteCodexRuntimeId) {
      return false;
    }
    final runtime = _runtimeCoordinator.runtimeFor(
      conversationId: currentConversationId,
      mode: kChatRuntimeModeAgent,
    );
    return _modeState(ChatPageMode.agent).messages.isNotEmpty ||
        (runtime?.hasInFlightTask ?? false);
  }
}
