part of 'chat_page.dart';

extension _ChatPageRemoteCodexSupport on _ChatPageStateBase {
  void _startRemoteCodexSessionSync(String threadId) {
    final normalizedThreadId = threadId.trim();
    if (normalizedThreadId.isEmpty) {
      return;
    }
    // A session snapshot is for initial history hydration only. Live state is
    // owned by the ACP event subscription; polling thread/read here used to
    // race those updates and repeatedly rewrite the visible turn.
    if (_remoteCodexSessionSyncThreadId == normalizedThreadId) {
      return;
    }
    _remoteCodexSessionSyncThreadId = normalizedThreadId;
    unawaited(_syncRemoteCodexSessionSnapshot());
  }

  void _stopRemoteCodexSessionSync() {
    _remoteCodexSessionSyncInFlight = false;
    _remoteCodexSessionSyncThreadId = null;
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
      );
    } catch (error) {
      if (_remoteCodexSessionSyncThreadId == threadId) {
        // Allow the next explicit session open/reconnect to retry hydration;
        // there is intentionally no background polling fallback.
        _remoteCodexSessionSyncThreadId = null;
      }
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
    final previousActive = runtime?.isAiResponding ?? false;
    // Floor the one-time hydration result against the reducer's runtime state.
    // The event reducer is the live lifecycle owner; a history snapshot must
    // not demote an already admitted ACP turn.
    final isAiResponding = previousActive;
    final activeTurnId = isAiResponding
        ? (runtime?.currentDispatchTurnId ??
              runtime?.lastAgentTurnId ??
              _activeAgentTurnId)
        : null;
    final runtimeTaskId = isAiResponding
        ? (runtime?.activeRunId?.trim().isNotEmpty == true
              ? runtime!.activeRunId!.trim()
              : runtime?.currentDispatchTurnId ?? runtime?.lastAgentTurnId)
        : null;
    // A snapshot may carry no local task identity. Do not invent one: the
    // runtime coordinator already owns the admitted turn identity.
    final activeTaskId = runtimeTaskId;
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
    if (!mounted) {
      return;
    }
    // Preserve reducer-driven streaming state when the one-time hydration
    // races an ACP event that arrived while session/load was in flight.
    final hasLivePushStreaming =
        runtime != null &&
        (runtime.currentAiMessages.isNotEmpty ||
            runtime.currentThinkingMessages.isNotEmpty ||
            runtime.messages.any(_isPendingAgentRequestMessage));
    final preserveLiveStreamingState = hasLivePushStreaming;
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
