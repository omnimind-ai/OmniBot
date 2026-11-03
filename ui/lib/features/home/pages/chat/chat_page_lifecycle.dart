part of 'chat_page.dart';

ConversationThreadTarget _newThreadTargetForConversationMode(
  ConversationMode mode,
) {
  return ConversationThreadTarget.newConversation(
    mode: mode,
    agentId: _kXiaowanAcpAgentId,
    requestKey: DateTime.now().microsecondsSinceEpoch.toString(),
  );
}

ConversationThreadTarget _newAgentThreadTarget({
  String? agentId,
  String? agentRuntime,
  int? conversationId,
}) {
  if (conversationId != null) {
    return ConversationThreadTarget.existing(
      conversationId: conversationId,
      mode: ConversationMode.agent,
      agentId: agentId,
      agentRuntime: agentRuntime,
    );
  }
  return ConversationThreadTarget.newConversation(
    mode: ConversationMode.agent,
    requestKey: DateTime.now().microsecondsSinceEpoch.toString(),
    agentId:
        agentId ??
        (agentRuntime == 'remote'
            ? _kRemoteCodexModeAgentId
            : _kXiaowanAcpAgentId),
    agentRuntime: agentRuntime,
  );
}

mixin _ChatPageLifecycleMixin on _ChatPageStateBase {
  // UI-only preservation while the existing conversation initializer resets
  // an unsubmitted target. The request id prevents an older initialization
  // from restoring its composer into a newer conversation.
  int? _composerPreservationRequestId;

  @override
  void onConversationReset(ConversationMode mode) {
    final preserveComposer =
        _composerPreservationRequestId == _conversationTargetRequestId &&
        _pageModeForConversationMode(mode) == _activeConversationMode;
    if (!preserveComposer) {
      super.onConversationReset(mode);
      return;
    }
    // Read immediately before the synchronous reset, not before its async
    // initialization: the user may have edited text or attachments meanwhile.
    final composerValue = _messageController.value;
    final composerAttachments = List<ChatInputAttachment>.of(
      _pendingAttachments,
    );
    super.onConversationReset(mode);
    final state = _modeState(_activeConversationMode);
    state.draftMessage = composerValue.text;
    state.pendingAttachments.addAll(composerAttachments);
    _messageController.value = composerValue;
  }

  @override
  void initState() {
    super.initState();

    WidgetsBinding.instance.addObserver(this);
    _loadHdPadPanePreferences();

    SchedulerBinding.instance.addPostFrameCallback((_) {
      checkConversationExists();
    });

    _runtimeCoordinator.ensureInitialized();
    _lastAgentEventProjection = _runtimeCoordinator.latestAgentEventProjection;
    _runtimeCoordinator.addListener(_handleRuntimeCoordinatorChanged);
    HomeGreetingSettingsService.notifier.addListener(
      _handleHomeGreetingSettingsChanged,
    );
    unawaited(HomeGreetingSettingsService.load());
    AppUpdateService.statusNotifier.addListener(_handleAppUpdateStatusChanged);
    _appUpdateStatus = AppUpdateService.statusNotifier.value;
    unawaited(AppUpdateService.initialize());
    _conversationListChangedSubscription = AssistsMessageService
        .conversationListChangedStream
        .listen((event) {
          if (event['reason'] == 'na_history_owner_changed') {
            final activeNa =
                _activeMode == ChatPageMode.agent &&
                (_activeAcpAgentId == 'na-cloud' ||
                    _currentConversation?.agentId == 'na-cloud');
            final cachedNa =
                _modeState(ChatPageMode.agent).currentConversation?.agentId ==
                    'na-cloud' ||
                (_activeAgentThreadId?.startsWith('na:') ?? false);
            // Invalidate history/snapshot awaits before any later callback can
            // restore the account that just left this device.
            _beginConversationTargetRequest();
            invalidateConversationLifecycle();
            _runtimeCoordinator.discardNaHistoryViews();
            if (activeNa || cachedNa)
              _resetLocalConversationState(ChatPageMode.agent);
            if (activeNa) {
              _messageController.clear();
              _pendingAttachments.clear();
              unawaited(
                _applyConversationThreadTarget(
                  _newAgentThreadTarget(
                    agentId: 'na-cloud',
                    agentRuntime: 'na',
                  ),
                ),
              );
            }
          }
          unawaited(_handleExternalConversationListChanged());
        });
    _conversationMessagesChangedSubscription = AssistsMessageService
        .conversationMessagesChangedStream
        .listen((event) {
          unawaited(_handleExternalConversationMessagesChanged(event));
        });
    _browserSessionSnapshotChangedSubscription = AssistsMessageService
        .browserSessionSnapshotChangedStream
        .listen(_handleBrowserSessionSnapshotChanged);
    _omniLinkEventSubscription = OmniLinkPluginService.events.listen(
      _handleOmniLinkEvent,
    );
    unawaited(_refreshAgentRuntimeStatus());

    _inputFocusNode.addListener(_onFocusChange);
    _messageController.addListener(_handleSlashCommandInput);
    final bootstrapFuture = _bootstrapConversationThread();
    _conversationBootstrapFuture = bootstrapFuture;
    unawaited(bootstrapFuture);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final viewportSize = MediaQuery.maybeSizeOf(context);
    if (viewportSize != null) {
      final safeWidth =
          viewportSize.width -
          (MediaQuery.maybeViewPaddingOf(context)?.horizontal ?? 0);
      final isChatWorkspace = isChatWorkspaceViewport(
        Size(safeWidth, viewportSize.height),
      );
      if (_wasChatWorkspace == true && !isChatWorkspace) {
        _embeddedDrawerKey.currentState?.unfocusSearch();
        if (_isHdPadPaneDragging) {
          _resetHdPadPaneDragState();
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted) _persistHdPadPanePreferences();
          });
        }
      }
      _wasChatWorkspace = isChatWorkspace;
      if (isChatWorkspace && _activeSurfaceMode == ChatSurfaceMode.workspace) {
        _activeSurfaceMode = ChatSurfaceMode.normal;
      }
    }
    final route = ModalRoute.of(context);
    if (route is PageRoute && route != _subscribedRoute) {
      if (_subscribedRoute != null) {
        GoRouterManager.routeObserver.unsubscribe(this);
      }
      _subscribedRoute = route;
      GoRouterManager.routeObserver.subscribe(this, route);
    }
  }

  @override
  void didUpdateWidget(covariant ChatPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (_threadTargetChanged(oldWidget.threadTarget, widget.threadTarget)) {
      debugPrint(
        '[ChatPage] thread target changed: '
        '${oldWidget.threadTarget} -> ${widget.threadTarget}',
      );
      unawaited(_reloadConversationForCurrentTarget());
    }
  }

  @override
  bool _threadTargetChanged(
    ConversationThreadTarget? oldTarget,
    ConversationThreadTarget? newTarget,
  ) {
    return oldTarget != newTarget;
  }

  @override
  Future<ConversationThreadTarget> _resolveConversationThreadTarget(
    ConversationThreadTarget? incomingTarget, {
    ConversationMode? preferredMode,
  }) async {
    final normalizedPreferredMode = preferredMode == ConversationMode.openclaw
        ? ConversationMode.normal
        : preferredMode;
    final normalizedIncomingTarget = _normalizeVisibleThreadTarget(
      incomingTarget,
    );
    if (normalizedIncomingTarget != null) {
      return normalizedIncomingTarget;
    }

    if (normalizedPreferredMode == null &&
        StorageService.getChatStartupBehavior() ==
            ChatStartupBehavior.newConversation) {
      return _newThreadTargetForConversationMode(ConversationMode.agent);
    }

    if (normalizedPreferredMode == null) {
      final lastVisible =
          await ConversationHistoryService.getLastVisibleThreadTarget();
      final normalizedLastVisible = _normalizeVisibleThreadTarget(lastVisible);
      if (normalizedLastVisible != null) {
        return normalizedLastVisible;
      }
    }

    final resolvedMode = normalizedPreferredMode ?? ConversationMode.agent;
    final savedTarget =
        await ConversationHistoryService.getCurrentConversationTarget(
          mode: resolvedMode,
        );
    final normalizedSavedTarget = _normalizeVisibleThreadTarget(savedTarget);
    if (normalizedSavedTarget != null) {
      return normalizedSavedTarget;
    }

    final latestTarget = await ConversationService.getLatestConversationTarget(
      mode: resolvedMode,
    );
    final normalizedLatestTarget = _normalizeVisibleThreadTarget(latestTarget);
    if (normalizedLatestTarget != null) {
      return normalizedLatestTarget;
    }

    return _newThreadTargetForConversationMode(resolvedMode);
  }

  ConversationThreadTarget? _normalizeVisibleThreadTarget(
    ConversationThreadTarget? target,
  ) {
    if (target == null) {
      return null;
    }
    if (target.mode == ConversationMode.openclaw) {
      return null;
    }
    if (target.isRemoteCodexSessionTarget || target.agentRuntime == 'remote') {
      return target.copyWith(agentId: _kRemoteCodexModeAgentId);
    }
    if (target.isNewConversation) {
      final agentId = target.agentRuntime == 'remote'
          ? _kRemoteCodexModeAgentId
          : target.mode == ConversationMode.normal ||
                target.mode == ConversationMode.chatOnly
          ? _kXiaowanAcpAgentId
          : (target.agentId?.trim().isNotEmpty == true
                ? target.agentId!
                : _kXiaowanAcpAgentId);
      return target.copyWith(agentId: agentId);
    }
    return target;
  }

  @override
  Future<void> _bootstrapConversationThread() async {
    final requestId = _beginConversationTargetRequest(
      isHistoryLoading: widget.threadTarget?.isNewConversation != true,
    );
    try {
      await _loadOpenClawConfig();
      if (!_isConversationTargetRequestCurrent(requestId)) return;
      await _loadTerminalEnvironmentVariables();
      if (!_isConversationTargetRequestCurrent(requestId)) return;
      final target = await _resolveConversationThreadTarget(
        widget.threadTarget,
      );
      if (!_isConversationTargetRequestCurrent(requestId)) return;
      await _applyConversationThreadTarget(
        target,
        syncPage: false,
        requestId: requestId,
      );
      if (!_isConversationTargetRequestCurrent(requestId)) return;
      unawaited(_loadNormalChatModelContext());
      unawaited(_refreshLiveBrowserSessionSnapshot(syncRuntime: true));
    } finally {
      _finishConversationHistoryLoad(requestId);
    }
  }

  @override
  Future<void> _reloadConversationForCurrentTarget() async {
    final requestId = _beginConversationTargetRequest(
      isHistoryLoading: widget.threadTarget?.isNewConversation != true,
    );
    try {
      final target = await _resolveConversationThreadTarget(
        widget.threadTarget,
      );
      if (!_isConversationTargetRequestCurrent(requestId)) return;
      await _applyConversationThreadTarget(target, requestId: requestId);
      if (!_isConversationTargetRequestCurrent(requestId)) return;
    } finally {
      _finishConversationHistoryLoad(requestId);
    }
  }

  @override
  Future<void> _applyConversationThreadTarget(
    ConversationThreadTarget target, {
    bool syncPage = true,
    bool preserveComposer = false,
    int? requestId,
  }) async {
    final activeRequestId =
        requestId ??
        _beginConversationTargetRequest(
          isHistoryLoading: !target.isNewConversation,
        );
    bool isStaleRequest() =>
        !_isConversationTargetRequestCurrent(activeRequestId);
    try {
      invalidateConversationLifecycle();
      final lifecycleToken = captureConversationLifecycleToken();
      final effectiveTarget = _normalizeVisibleThreadTarget(
        await _overrideTargetWithSharedDraftIfNeeded(target),
      )!;
      if (isStaleRequest()) return;
      final targetMode = _pageModeForConversationMode(effectiveTarget.mode);
      // Carry the current composer across the first synchronous target reset.
      // onConversationReset preserves newer edits made during initialization.
      final composerValue = preserveComposer ? _messageController.value : null;
      final composerAttachments = preserveComposer
          ? List<ChatInputAttachment>.of(_pendingAttachments)
          : null;
      _storeDraftForActiveConversationMode();
      if (effectiveTarget.isNewConversation && !preserveComposer) {
        _modeState(targetMode).draftMessage = '';
        _modeState(targetMode).pendingAttachments.clear();
      }
      if (isStaleRequest()) return;
      setState(() {
        _resolvedThreadTarget = effectiveTarget;
        _activeConversationMode = targetMode;
        _activeSurfaceMode = _surfaceForConversationMode(effectiveTarget.mode);
        _showSlashCommandPanel = false;
        _showModelMentionPanel = false;
        _activeModelMentionToken = null;
        _openClawPanelExpanded = false;
        _isBrowserOverlayVisible = false;
        _isSurfacePageScrolling = false;
      });
      // A new Harness target without a conversationId is a new conversation.
      // The previous conversation remains persisted in history and is not
      // implicitly inherited by the new Harness.
      _resetLocalConversationState(targetMode);
      if (composerValue != null) {
        _modeState(targetMode).draftMessage = composerValue.text;
        _modeState(targetMode).pendingAttachments.addAll(composerAttachments!);
      }
      _restoreLocalAgentThreadIdFromTarget(effectiveTarget);
      final agentHistorySync =
          _shouldSyncExistingLocalAgentTarget(effectiveTarget)
          ? _syncExistingLocalAgentTarget(effectiveTarget, activeRequestId)
          : null;
      if (!preserveComposer) {
        _applyDraftForConversationMode(targetMode);
      }
      if (preserveComposer) {
        _composerPreservationRequestId = activeRequestId;
      }
      try {
        if (effectiveTarget.isRemoteCodexSessionTarget) {
          await _prepareRemoteCodexSessionTarget(effectiveTarget);
        } else {
          await initializeConversation(lifecycleToken: lifecycleToken);
        }
      } finally {
        if (_composerPreservationRequestId == activeRequestId) {
          _composerPreservationRequestId = null;
        }
      }
      if (isStaleRequest()) return;
      // Local history can be empty while an existing cloud session is hydrating.
      // Already available messages can be shown without waiting for metadata sync.
      if (agentHistorySync != null && _messages.isEmpty) {
        await agentHistorySync;
        if (isStaleRequest()) return;
      }
      _finishConversationHistoryLoad(activeRequestId);
      if (_activeConversationMode == ChatPageMode.agent) {
        await _refreshAgentCommandPreferences();
        if (isStaleRequest()) return;
      }
      unawaited(_refreshAgentRuntimeStatus());
      await _applyStagedSharedDraftIfNeeded(effectiveTarget);
      if (isStaleRequest()) return;
      await _sendInitialMessageIfNeeded(effectiveTarget);
      if (isStaleRequest()) return;
      await _persistVisibleThreadTargetIfNeeded();
      unawaited(_syncVisibleChatConversation());
      if (isStaleRequest()) return;
      if (syncPage) {
        _jumpToCurrentModePage(animate: false);
      }
      if (effectiveTarget.isNewConversation &&
          (effectiveTarget.initialMessage?.trim().isEmpty ?? true) &&
          !widget.showFirstUseTour) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (!_isConversationTargetRequestCurrent(activeRequestId) ||
              ModalRoute.of(context)?.isCurrent != true ||
              widget.showFirstUseTour ||
              _resolvedThreadTarget?.isNewConversation != true ||
              _currentConversationId != null ||
              _messages.isNotEmpty ||
              _isAiResponding ||
              _sendMessageInFlightTargetIds.contains(activeRequestId) ||
              _isWorkspaceSurface ||
              _isBrowserOverlayVisible ||
              _isPopupVisible) {
            return;
          }
          _requestComposerFocus(showKeyboard: true);
        });
        WidgetsBinding.instance.ensureVisualUpdate();
      }
    } finally {
      _finishConversationHistoryLoad(activeRequestId);
    }
  }

  Future<void> _sendInitialMessageIfNeeded(
    ConversationThreadTarget target,
  ) async {
    final message = target.initialMessage?.trim() ?? '';
    if (!target.isNewConversation ||
        target.mode != ConversationMode.agent ||
        message.isEmpty) {
      return;
    }
    final requestKey = target.requestKey ?? message;
    if (!_consumedInitialMessageRequests.add(requestKey)) return;
    // This method is invoked by _applyConversationThreadTarget while the
    // bootstrap Future is still running. The conversation has already been
    // initialized above, so waiting for that same Future inside _sendMessage
    // would deadlock the initial prompt (notably OmniFlow enhancement).
    await _sendMessage(text: message, waitForBootstrap: false);
  }

  void _restoreLocalAgentThreadIdFromTarget(ConversationThreadTarget target) {
    if (target.mode != ConversationMode.agent ||
        target.isRemoteCodexSessionTarget) {
      return;
    }
    final threadId = target.agentSessionId?.trim();
    if (threadId == null || threadId.isEmpty) {
      return;
    }
    _activeAgentThreadId = threadId;
  }

  bool _shouldSyncExistingLocalAgentTarget(ConversationThreadTarget target) {
    return target.mode == ConversationMode.agent &&
        !target.isNewConversation &&
        !target.isRemoteCodexSessionTarget &&
        target.conversationId != null;
  }

  Future<void> _syncExistingLocalAgentTarget(
    ConversationThreadTarget target,
    int requestId,
  ) async {
    final conversationId = target.conversationId;
    if (conversationId == null) {
      return;
    }
    try {
      AgentRuntimeStatus? status;
      final requestedAgentId = target.agentId?.trim() ?? '';
      final response = await AgentRuntimeService.readSession(
        conversationId: conversationId,
        agentId: requestedAgentId.isEmpty ? null : requestedAgentId,
        includeHistory: false,
        conversationMode: target.mode.storageValue,
      );
      try {
        final responseAgentId = (response['agentId'] ?? requestedAgentId)
            .toString()
            .trim();
        if (responseAgentId.isNotEmpty) {
          status = await AgentRuntimeService.status(agentId: responseAgentId);
        }
      } catch (_) {
        // Session ownership does not depend on optional status metadata.
      }
      if (!mounted || !_isConversationTargetRequestCurrent(requestId)) {
        return;
      }
      final currentTarget = _resolvedThreadTarget;
      if (currentTarget?.mode != ConversationMode.agent ||
          currentTarget?.conversationId != conversationId) {
        return;
      }
      final thread = response['thread'];
      final threadMap = thread is Map
          ? Map<String, dynamic>.from(thread.cast<dynamic, dynamic>())
          : const <String, dynamic>{};
      final threadId =
          (response['threadId'] ?? threadMap['id'])?.toString().trim() ?? '';
      final resolvedAgentId =
          (response['agentId'] ?? threadMap['agentId'])?.toString().trim() ??
          '';
      setState(() {
        if (threadId.isNotEmpty) {
          _activeAgentThreadId = threadId;
        }
        if (resolvedAgentId.isNotEmpty) {
          _agentIdByConversationId[conversationId] = resolvedAgentId;
          _resolvedThreadTarget = currentTarget?.copyWith(
            agentId: resolvedAgentId,
            agentSessionId: threadId.isEmpty ? null : threadId,
            agentRuntime: resolvedAgentId == 'na-cloud' ? 'na' : 'local',
          );
          final conversation = _modeState(ChatPageMode.agent)
              .currentConversation;
          if (conversation?.id == conversationId) {
            final updatedConversation = conversation!.copyWith(
              agentId: resolvedAgentId,
            );
            _modeState(ChatPageMode.agent).currentConversation =
                updatedConversation;
            final runtime = _runtimeForMode(ChatPageMode.agent);
            if (runtime?.conversation?.id == conversationId) {
              runtime!.conversation = updatedConversation;
            }
          }
        }
        if (status != null) {
          _agentRuntimeStatus = status;
        }
      });
      if (resolvedAgentId == 'na-cloud' &&
          _remoteCodexThreadResponseHasTurns(response)) {
        final runtime = _runtimeForMode(ChatPageMode.agent);
        final responding = runtime?.isAiResponding ?? false;
        final merged = _mergeRemoteCodexSnapshotMessages(
          snapshotMessages: _remoteCodexMessagesFromThreadResponse(response),
          existingMessages: List<ChatMessageModel>.from(
            runtime?.messages ?? _modeState(ChatPageMode.agent).messages,
          ),
          activeTaskId: runtime?.activeRunId,
          isAiResponding: responding,
        );
        _syncRuntimeSnapshotForMode(
          ChatPageMode.agent,
          messages: merged,
          preserveLiveStreamingState: responding,
        );
        if (!responding) {
          await _runtimeCoordinator.persistRuntimeConversation(
            conversationId: conversationId,
            mode: kChatRuntimeModeAgent,
            persistMessages: true,
          );
        }
      }
      unawaited(_loadAgentCatalog());
      unawaited(_loadAgentModelOptionsWhenReady(force: true));
      unawaited(_persistVisibleThreadTargetIfNeeded());
    } catch (error) {
      debugPrint(
        '[ChatPage] Failed to restore ACP agent for conversation '
        '$conversationId: $error',
      );
    }
  }

  @override
  Future<void> _ensureConversationModeReady(ChatPageMode mode) async {
    if (_hasPreparedConversationState(mode)) {
      return;
    }
    final target = await _resolveConversationThreadTarget(
      null,
      preferredMode: _conversationModeForPageMode(mode),
    );
    if (!mounted) return;
    await _prepareConversationModeState(mode, target);
  }

  bool _hasPreparedConversationState(ChatPageMode mode) {
    final runtime = _runtimeForMode(mode);
    final draft = _modeState(mode).draftMessage;
    return _modeState(mode).currentConversationId != null ||
        _modeState(mode).currentConversation != null ||
        _modeState(mode).messages.isNotEmpty ||
        (runtime?.messages.isNotEmpty ?? false) ||
        draft.isNotEmpty ||
        _modeState(mode).pendingAttachments.isNotEmpty;
  }

  @override
  Future<void> _prepareConversationModeState(
    ChatPageMode mode,
    ConversationThreadTarget target,
  ) async {
    final lifecycleToken = captureConversationLifecycleToken();
    if (target.isNewConversation) {
      return;
    }

    final conversationId = target.conversationId;
    if (conversationId == null) {
      return;
    }

    final conversations = await ConversationService.getAllConversations(
      includeArchived: true,
    );
    if (!mounted || !isConversationLifecycleTokenCurrent(lifecycleToken)) {
      return;
    }

    ConversationModel? conversation;
    try {
      conversation = conversations.firstWhere(
        (item) =>
            item.id == conversationId &&
            item.mode == _conversationModeForPageMode(mode),
      );
    } catch (_) {
      conversation = null;
    }

    // The metadata read may overlap the current ACP turn. Select the current
    // runtime only after I/O, rather than projecting an earlier message list.
    var runtime = _runtimeCoordinator.runtimeFor(
      conversationId: conversationId,
      mode: _modeKey(mode),
    );
    var resolvedConversation = runtime?.conversation ?? conversation;
    var historyPage = runtime?.historyPage;
    final historyRevision = runtime?.historyPageRevision ?? 0;
    List<ChatMessageModel> resolvedMessages;
    if (runtime?.messages.isNotEmpty == true) {
      resolvedMessages = List<ChatMessageModel>.from(runtime!.messages);
    } else {
      final page =
          await ConversationHistoryService.getConversationMessagesPaged(
            conversationId,
            mode: _conversationModeForPageMode(mode),
            limit: 50,
            offset: 0,
            expectedMessageCount: resolvedConversation?.messageCount,
          );
      resolvedMessages = page.messages;
      historyPage = (hasMore: page.hasMore, nextOffset: page.nextOffset);
    }
    if (!mounted || !isConversationLifecycleTokenCurrent(lifecycleToken)) {
      return;
    }

    runtime = _runtimeCoordinator.runtimeFor(
      conversationId: conversationId,
      mode: _modeKey(mode),
    );
    if ((runtime?.historyPageRevision ?? 0) != historyRevision) return;
    if (runtime?.messages.isNotEmpty == true) {
      final knownIds = runtime!.messages.map((message) => message.id).toSet();
      resolvedMessages = <ChatMessageModel>[
        ...runtime.messages,
        ...resolvedMessages.where((message) => knownIds.add(message.id)),
      ];
      resolvedConversation = runtime.conversation ?? resolvedConversation;
      historyPage = runtime.historyPage ?? historyPage;
    }

    _modeState(mode).currentConversationId = conversationId;
    _modeState(mode).currentConversation = resolvedConversation;
    _modeState(mode).messages
      ..clear()
      ..addAll(resolvedMessages);
    final selectedPage =
        historyPage ??
        (
          hasMore: !isEphemeralConversation(
            conversationId,
            _conversationModeForPageMode(mode),
          ),
          nextOffset: 0,
        );
    _modeState(mode).hasMoreMessages = selectedPage.hasMore;
    _modeState(mode).messageOffset = selectedPage.nextOffset;
    _modeState(mode).isLoadingMore = false;

    if (runtime == null) {
      _runtimeCoordinator.ensureRuntime(
        conversationId: conversationId,
        mode: _modeKey(mode),
        initialMessages: resolvedMessages,
        conversation: resolvedConversation,
        initialChatIslandDisplayLayer: _chatIslandDisplayLayerForMode(mode),
      );
    } else if (resolvedConversation != null) {
      runtime.conversation = resolvedConversation;
    }
    _syncRuntimeSnapshotForMode(
      mode,
      conversation: resolvedConversation,
      messages: resolvedMessages,
    );
    onConversationHistoryPageLoaded(
      _conversationModeForPageMode(mode),
      conversationId,
      hasMore: selectedPage.hasMore,
      nextOffset: selectedPage.nextOffset,
    );
  }

  @override
  Future<void> _persistVisibleThreadTargetIfNeeded() async {
    final visibleTarget = _visibleThreadTarget;
    if (visibleTarget == null) {
      return;
    }
    _resolvedThreadTarget = visibleTarget;
    if (visibleTarget.isRemoteCodexSessionTarget ||
        (_activeConversationMode == ChatPageMode.agent &&
            _isRemoteCodexRuntimeActiveForMode(ChatPageMode.agent))) {
      return;
    }
    await ConversationHistoryService.saveLastVisibleThreadTarget(visibleTarget);
    await ConversationHistoryService.saveCurrentConversationTarget(
      visibleTarget,
      mode: visibleTarget.mode,
    );
    await ConversationService.setCurrentConversationTarget(visibleTarget);
  }

  @override
  void dispose() {
    for (final mode in _unboundConfigurationSessions.keys.toList()) {
      _releaseUnboundConfigurationSession(mode);
    }
    unawaited(_clearVisibleChatConversation());
    unawaited(_conversationModelSelectorHandle?.dismiss());
    _conversationModelSelectorHandle = null;
    WidgetsBinding.instance.removeObserver(this);
    unawaited(_runtimeCoordinator.flushAllPendingPersistence());
    _conversationListChangedSubscription?.cancel();
    _conversationMessagesChangedSubscription?.cancel();
    _browserSessionSnapshotChangedSubscription?.cancel();
    if (_subscribedRoute != null) {
      GoRouterManager.routeObserver.unsubscribe(this);
      _subscribedRoute = null;
    }
    _runtimeCoordinator.removeListener(_handleRuntimeCoordinatorChanged);
    HomeGreetingSettingsService.notifier.removeListener(
      _handleHomeGreetingSettingsChanged,
    );
    AppUpdateService.statusNotifier.removeListener(
      _handleAppUpdateStatusChanged,
    );
    _messageController.removeListener(_handleSlashCommandInput);
    _messageController.dispose();
    _normalMessageScrollController.dispose();
    _openClawMessageScrollController.dispose();
    _agentMessageScrollController.dispose();
    _modePageController.dispose();
    _inputFocusNode.dispose();
    _pageShortcutsFocusNode.dispose();
    _chatKeyboardLayoutChanges.dispose();
    _openClawBaseUrlController.dispose();
    _openClawTokenController.dispose();
    _openClawUserIdController.dispose();
    this._stopRemoteCodexSessionSync();
    _omniLinkEventSubscription?.cancel();
    super.dispose();
  }

  void _handleOmniLinkEvent(Map<String, dynamic> event) {
    if (!mounted) {
      return;
    }
    final kind = event['kind']?.toString();
    final eventId = kind == 'omnilink_agent_message'
        ? event['messageId']?.toString().trim() ?? ''
        : event['eventId']?.toString().trim() ?? '';
    if (eventId.isEmpty) {
      return;
    }
    final chatMessageId = 'omnilink:$eventId';
    if (_messages.any((item) => item.id == chatMessageId)) {
      return;
    }
    final sourceDeviceId = event['sourceDeviceId']?.toString().trim() ?? '';
    String visibleText;
    if (kind == 'omnilink_agent_message') {
      final message = event['message']?.toString().trim() ?? '';
      visibleText = message.isEmpty ? '' : '来自协作设备上的小万：\n$message';
    } else if (kind == 'omnilink_device_notification') {
      final applicationId = event['applicationId']?.toString().trim() ?? '';
      final sourceLabel = applicationId.isEmpty ? '协作设备' : applicationId;
      final postedAt = formatOmniLinkNotificationTime(event['postedAt']);
      final timeLabel = postedAt.isEmpty ? '' : ' · $postedAt';
      final removed = event['removed'] == true;
      final sensitive = event['sensitive'] == true;
      if (removed) {
        visibleText = '协作设备通知已撤回：$sourceLabel$timeLabel';
      } else if (sensitive) {
        visibleText = '协作设备收到来自 $sourceLabel$timeLabel 的通知（内容已按隐私策略隐藏）。';
      } else {
        visibleText = '协作设备收到来自 $sourceLabel$timeLabel 的通知（仅显示安全摘要，正文未传输）。';
      }
    } else {
      visibleText = '';
    }
    if (visibleText.isEmpty) {
      return;
    }
    final chatMessage = ChatMessageModel(
      id: chatMessageId,
      type: 1,
      user: 2,
      content: {
        'text': visibleText,
        'id': chatMessageId,
        'agentName': 'OmniLink 协作小万',
        'source': 'omnilink',
        'sourceDeviceId': sourceDeviceId,
        'messageId': eventId,
      },
    );
    setState(() {
      _messages.insert(0, chatMessage);
    });
    final conversationId = _currentConversationId;
    if (conversationId != null &&
        !isEphemeralConversation(conversationId, activeConversationModeValue)) {
      unawaited(
        _runtimeCoordinator.persistConversationMessageSnapshot(
          conversationId: conversationId,
          mode: _modeKey(_activeMode),
          messages: List<ChatMessageModel>.from(_messages),
          conversation: _currentConversation,
        ),
      );
    }
  }

  @override
  void didPopNext() {
    unawaited(_handleDidPopNext());
    unawaited(_syncVisibleChatConversation());
    unawaited(_refreshAgentRuntimeStatus());
  }

  @override
  void didPush() {
    unawaited(_syncVisibleChatConversation());
  }

  @override
  void didPop() {
    unawaited(_clearVisibleChatConversation());
  }

  @override
  void didPushNext() {
    _dismissChatInputFocus();
    unawaited(_clearVisibleChatConversation());
  }

  Future<void> _handleDidPopNext() async {
    final lifecycleToken = captureConversationLifecycleToken();
    await checkConversationExists(lifecycleToken: lifecycleToken);
    if (!mounted || !isConversationLifecycleTokenCurrent(lifecycleToken)) {
      return;
    }
    await _loadNormalChatModelContext();
    if (!mounted || !isConversationLifecycleTokenCurrent(lifecycleToken)) {
      return;
    }
    await _refreshLiveBrowserSessionSnapshot(syncRuntime: true);
  }

  Future<void> _handleExternalConversationListChanged() async {
    final lifecycleToken = captureConversationLifecycleToken();
    final conversationId = _currentConversationId;
    final runtime = _runtimeForMode(_activeMode);
    final hasLiveTurn = runtime?.hasInFlightTask == true || _isAiResponding;
    // Conversation creation/update notifications are metadata-only from the
    // chat page's perspective while a turn is running. Loading the database
    // snapshot here can race the optimistic user-message persistence and
    // replace the visible live timeline with an older, empty one.
    if (hasLiveTurn) return;
    await checkConversationExists(lifecycleToken: lifecycleToken);
    if (!mounted ||
        !isConversationLifecycleTokenCurrent(lifecycleToken) ||
        conversationId == null ||
        conversationId != _currentConversationId) {
      return;
    }
    // The task may have started while checkConversationExists was awaiting
    // the native conversation list. Re-check before installing any snapshot;
    // otherwise that late list event can still win the race.
    if (_runtimeForMode(_activeMode)?.hasInFlightTask == true ||
        _isAiResponding) {
      return;
    }
    await loadConversation(
      conversationId,
      // A list-change event updates conversation metadata. If this page
      // already owns a populated runtime, keep that same observable timeline
      // instead of rebuilding all rows from the database on agent completion.
      preferInMemory: shouldPreferInMemoryForConversationListChanged(
        hasInFlightTask: runtime?.hasInFlightTask == true,
        hasRuntimeMessages: runtime?.messages.isNotEmpty == true,
      ),
      lifecycleToken: lifecycleToken,
    );
    if (!mounted ||
        !isConversationLifecycleTokenCurrent(lifecycleToken) ||
        conversationId != _currentConversationId) {
      return;
    }
    setState(() {});
    unawaited(_syncVisibleChatConversation());
  }

  Future<void> _handleExternalConversationMessagesChanged(
    Map<String, dynamic> event,
  ) async {
    final lifecycleToken = captureConversationLifecycleToken();
    final conversationId = _currentConversationId;
    if (conversationId == null) {
      return;
    }
    final changedConversationId = (event['conversationId'] as num?)?.toInt();
    final changedMode = ConversationMode.fromStorageValue(
      event['mode'] as String?,
    );
    if (changedConversationId != conversationId ||
        changedMode != activeConversationModeValue) {
      return;
    }
    final runtime = _runtimeForMode(_activeMode);
    final hasLiveTurn = runtime?.hasInFlightTask == true || _isAiResponding;
    // IM 等外部入口写入用户消息时，原生侧用 reason=external_user_message 通知前端：
    // 这条消息只在 DB 里、还没进入 runtime.messages，必须强制从 DB 重载，
    // 否则 agent 流事件先到时 hasInFlightTask=true 会让 in-memory 分支吞掉它。
    final reason = event['reason']?.toString();
    final isExternalUserMessage = reason == 'external_user_message';
    // 流事件已经由 runtime reducer 直接维护。原生侧每次把流式快照落库后
    // 还会发送 messages_replaced；若在这里重新安装同一份 in-memory 列表，
    // 会重建消息 notifier 并清空 reducer 的排序状态，造成思考卡闪烁和
    // 文本/思考时序跳动。
    if (!shouldReloadConversationMessagesChanged(
      reason: reason,
      hasInFlightTask: hasLiveTurn,
      hasRuntimeMessages: runtime?.messages.isNotEmpty == true,
      suppressLocalSnapshotEcho:
          runtime?.shouldSuppressLocalMessageSnapshotEcho == true,
    )) {
      return;
    }
    await loadConversation(
      conversationId,
      preferInMemory: !isExternalUserMessage && hasLiveTurn,
      lifecycleToken: lifecycleToken,
    );
    if (!mounted ||
        !isConversationLifecycleTokenCurrent(lifecycleToken) ||
        conversationId != _currentConversationId) {
      return;
    }
    setState(() {});
    unawaited(_syncVisibleChatConversation());
  }

  @override
  void _onFocusChange() {
    if (!mounted) return;
    if (_inputFocusNode.hasFocus) {
      _armComposerLiftIntent();
    }
    _chatKeyboardLayoutChanges.value++;
  }

  void _handleHomeGreetingSettingsChanged() {
    if (!mounted) return;
    setState(() {});
  }

  @override
  void _handleAppUpdateStatusChanged() {
    if (!mounted) return;
    setState(() {
      _appUpdateStatus = AppUpdateService.statusNotifier.value;
    });
  }

  @override
  double _popupMenuBottomOffset() {
    final renderObject = findActiveRenderObject(_inputAreaKey.currentContext);
    if (renderObject is! RenderBox || !renderObject.hasSize) {
      return 72;
    }
    final offset = renderObject.size.height - 8;
    return offset < 72 ? 72 : offset;
  }

  @override
  Future<void> _handleAppUpdateBannerTap() async {
    final status = _appUpdateStatus;
    if (status == null || !status.hasUpdate || !mounted) return;
    await showAppUpdateDialog(context, status);
  }

  @override
  int _pageIndexForSurface(ChatSurfaceMode mode) => switch (mode) {
    ChatSurfaceMode.normal => 0,
    ChatSurfaceMode.workspace => 1,
    ChatSurfaceMode.openclaw => 0,
  };

  @override
  ChatSurfaceMode _surfaceForPageIndex(int pageIndex) => switch (pageIndex) {
    1 => ChatSurfaceMode.workspace,
    _ => ChatSurfaceMode.normal,
  };

  @override
  ScrollController _scrollControllerForMode(ChatPageMode mode) {
    return mode == ChatPageMode.openclaw
        ? _openClawMessageScrollController
        : mode == ChatPageMode.agent
        ? _agentMessageScrollController
        : _normalMessageScrollController;
  }

  @override
  void _jumpToCurrentModePage({bool animate = true}) {
    final targetPage = _pageIndexForSurface(_activeSurfaceMode);
    if (!_modePageController.hasClients) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        _jumpToCurrentModePage(animate: animate);
      });
      return;
    }
    // PageController.page/position and animateToPage require exactly one
    // attached PageView.  During a layout branch swap the old PageView can
    // still be attached for one frame; wait for the next lifecycle event
    // instead of throwing from the chat build.
    if (_modePageController.positions.length != 1) {
      return;
    }
    final currentPage = _modePageController.page?.round();
    if (currentPage == targetPage) return;
    if (animate) {
      _modePageController.animateToPage(
        targetPage,
        duration: const Duration(milliseconds: 240),
        curve: Curves.easeOutCubic,
      );
    } else {
      _modePageController.jumpToPage(targetPage);
    }
  }

  @override
  Future<void> _switchChatMode(
    ChatSurfaceMode targetMode, {
    bool syncPage = true,
  }) async {
    final resolvedTargetMode = targetMode == ChatSurfaceMode.openclaw
        ? ChatSurfaceMode.normal
        : targetMode;
    final requestId = ++_surfaceSwitchRequestId;
    bool isStaleRequest() => !mounted || requestId != _surfaceSwitchRequestId;
    if (!mounted) return;
    if (_activeSurfaceMode == resolvedTargetMode) {
      if (syncPage) _jumpToCurrentModePage();
      return;
    }

    _storeDraftForActiveConversationMode();
    await _persistVisibleThreadTargetIfNeeded();
    if (isStaleRequest()) return;

    if (resolvedTargetMode == ChatSurfaceMode.workspace) {
      _inputFocusNode.unfocus();
      final workspacePathsFuture =
          _workspacePathsLoadFuture ??
          OmnibotResourceService.ensureWorkspacePathsLoaded();
      setState(() {
        _activeSurfaceMode = ChatSurfaceMode.workspace;
        _workspacePathsLoadFuture = workspacePathsFuture;
        _messageController.clear();
        _setChatIslandDisplayLayerForMode(
          ChatPageMode.normal,
          ChatIslandDisplayLayer.mode,
        );
        _isBrowserOverlayVisible = false;
      });
      _hideSlashCommandPanel();
      if (syncPage) _jumpToCurrentModePage();
      return;
    }

    final targetConversationMode = _activeConversationMode == ChatPageMode.agent
        ? ChatPageMode.agent
        : ChatPageMode.normal;
    await _ensureConversationModeReady(targetConversationMode);
    if (isStaleRequest()) return;
    setState(() {
      _activeSurfaceMode = ChatSurfaceMode.normal;
      _activeConversationMode = targetConversationMode;
    });
    _applyDraftForConversationMode(targetConversationMode);
    await _persistVisibleThreadTargetIfNeeded();
    unawaited(_syncVisibleChatConversation());
    if (isStaleRequest()) return;
    _hideSlashCommandPanel();
    if (targetConversationMode == ChatPageMode.normal) {
      unawaited(_loadNormalChatModelContext());
    }
    if (syncPage) _jumpToCurrentModePage();
  }

  @override
  void _handleModePageChanged(int pageIndex) {
    final targetMode = _surfaceForPageIndex(pageIndex);
    unawaited(_switchChatMode(targetMode, syncPage: false));
  }

  @override
  void _storeDraftForActiveConversationMode() {
    _modeState(_activeConversationMode).draftMessage = _messageController.text;
  }

  @override
  void _applyDraftForConversationMode(ChatPageMode mode) {
    final draft = _modeState(mode).draftMessage;
    _messageController.value = TextEditingValue(
      text: draft,
      selection: TextSelection.collapsed(offset: draft.length),
    );
  }

  Future<ConversationThreadTarget> _overrideTargetWithSharedDraftIfNeeded(
    ConversationThreadTarget target,
  ) async {
    if (target.mode != ConversationMode.normal) {
      return target;
    }
    final staged = _activeStagedSharedOpenDraft();
    if (staged != null && staged.hasContent) {
      return ConversationThreadTarget.newConversation(
        mode: ConversationMode.agent,
        agentId: _kXiaowanAcpAgentId,
        fromNativeRoute: true,
        requestKey: staged.requestKey,
      );
    }

    final payload = await SharedOpenDraftService.getPendingDraft();
    if (payload == null || !payload.hasContent) {
      return target;
    }
    _stagedSharedOpenDraft = payload;
    _stagedSharedOpenDraftExpiresAt =
        DateTime.now().millisecondsSinceEpoch + 5000;
    return ConversationThreadTarget.newConversation(
      mode: ConversationMode.agent,
      agentId: _kXiaowanAcpAgentId,
      fromNativeRoute: true,
      requestKey: payload.requestKey,
    );
  }

  Future<void> _applyStagedSharedDraftIfNeeded(
    ConversationThreadTarget target,
  ) async {
    final payload = _activeStagedSharedOpenDraft();
    if (payload == null ||
        !payload.hasContent ||
        !target.isNewConversation ||
        (target.mode != ConversationMode.agent &&
            target.mode != ConversationMode.normal)) {
      return;
    }

    final targetPageMode = _pageModeForConversationMode(target.mode);

    final attachments = payload.attachments
        .map(
          (item) => ChatInputAttachment(
            id: item.id.isNotEmpty ? item.id : item.path,
            name: item.name.isNotEmpty ? item.name : item.path.split('/').last,
            path: item.path,
            size: item.size,
            mimeType: item.mimeType,
            isImage: item.isImage,
            promptPath: item.promptPath,
            sendToModel: item.sendToModel,
          ),
        )
        .toList();

    if (!mounted) {
      return;
    }
    setState(() {
      _modeState(targetPageMode).draftMessage = payload.text ?? '';
      _modeState(targetPageMode).pendingAttachments
        ..clear()
        ..addAll(attachments);
    });
    if (_activeConversationMode == targetPageMode) {
      _applyDraftForConversationMode(targetPageMode);
    }
    await SharedOpenDraftService.clearPendingDraft();
  }

  SharedOpenDraftPayload? _activeStagedSharedOpenDraft() {
    final payload = _stagedSharedOpenDraft;
    final expiresAt = _stagedSharedOpenDraftExpiresAt;
    if (payload == null) {
      return null;
    }
    if (expiresAt != null &&
        DateTime.now().millisecondsSinceEpoch > expiresAt) {
      _stagedSharedOpenDraft = null;
      _stagedSharedOpenDraftExpiresAt = null;
      return null;
    }
    return payload;
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);
    if ((state == AppLifecycleState.resumed ||
            state == AppLifecycleState.inactive) &&
        _currentConversationId != null) {
      Future.delayed(const Duration(milliseconds: 100), () async {
        if (!mounted) return;
        await checkConversationExists();
      });
    }
    if (state == AppLifecycleState.resumed) {
      unawaited(_syncVisibleChatConversation());
      unawaited(AppUpdateService.refreshIfNeeded());
      unawaited(_loadNormalChatModelContext());
      unawaited(_refreshLiveBrowserSessionSnapshot(syncRuntime: true));
    }
    if (state == AppLifecycleState.inactive ||
        state == AppLifecycleState.paused ||
        state == AppLifecycleState.detached) {
      unawaited(_clearVisibleChatConversation());
      unawaited(_runtimeCoordinator.flushAllPendingPersistence());
      unawaited(_persistVisibleThreadTargetIfNeeded());
    }
    if (state == AppLifecycleState.paused) {
      // 根页面的系统返回由 Android 直接处理，不会再经过 Dart 的 pop 回调。
      // 在页面离开前台后异步完成会话，既保留原有语义，也不阻塞返回桌面动画。
      unawaited(saveConversationWithSummary());
    }
  }

  Future<void> _syncVisibleChatConversation() async {
    if (!mounted) return;
    final route = ModalRoute.of(context);
    if (route is PageRoute && !route.isCurrent) {
      await _clearVisibleChatConversation();
      return;
    }
    final target = _visibleThreadTarget;
    if (target == null || target.isNewConversation) {
      await AssistsMessageService.setVisibleChatConversation(
        conversationMode: activeConversationModeValue.storageValue,
      );
      return;
    }
    await AssistsMessageService.setVisibleChatConversation(
      conversationId: target.conversationId,
      conversationMode: target.mode.storageValue,
    );
  }

  Future<void> _clearVisibleChatConversation() {
    return AssistsMessageService.setVisibleChatConversation(visible: false);
  }
}
