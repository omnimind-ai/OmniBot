part of 'chat_conversation_runtime_coordinator.dart';

extension _ChatRuntimeAgentStreamSupport on ChatConversationRuntimeCoordinator {
  /// Adopts the runtime's notion of the turn in flight.
  ///
  /// The real turn id REPLACES the locally minted `<messageId>-ai` dispatch id
  /// instead of coexisting with it. This used to be `??=`, so the optimistic id
  /// survived alongside the native one and a single turn was described by two
  /// ids — both of which were then rendered with their own agent avatar.
  void _adoptRuntimeTurnId(
    ChatConversationRuntimeState runtime,
    String turnId,
  ) {
    final dispatchTaskId = runtime.currentDispatchTaskId?.trim() ?? '';
    if (dispatchTaskId.isEmpty || dispatchTaskId.endsWith('-ai')) {
      runtime.currentDispatchTaskId = turnId;
    }
    runtime.lastAgentTaskId = turnId;
  }

  void _syncRuntimeAgentState(
    ChatConversationRuntimeState runtime,
    AgentStreamEvent event,
    AgentStreamTaskState state,
  ) {
    _adoptRuntimeTurnId(runtime, event.taskId);
    runtime.activeThinkingCardId = state.activeThinkingEntryId;
    runtime.currentThinkingStage = state.thinkingStage;
    runtime.isDeepThinking = state.isDeepThinking;
    runtime.thinkingRound = state.thinkingRounds.length;
    runtime.toolCardSequence = state.toolCards.length;
    runtime.pendingThinkingRoundSplit = false;
    runtime.browserSessionSnapshot =
        state.browserSnapshot ?? runtime.browserSessionSnapshot;
    runtime.pendingAgentTextTaskId =
        event.kind == AgentStreamEventKind.textSnapshot && !event.isFinal
        ? event.taskId
        : null;
    if (event.kind == AgentStreamEventKind.toolStarted ||
        event.kind == AgentStreamEventKind.toolProgress) {
      runtime.activeToolCardId = event.entryId?.trim();
    } else if (event.kind == AgentStreamEventKind.toolCompleted) {
      if (runtime.activeToolCardId == event.entryId?.trim()) {
        runtime.activeToolCardId = null;
      }
    }
  }

  void _applyAgentThinkingStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final cardId = (event.entryId ?? '').trim();
    if (cardId.isEmpty) {
      return;
    }

    runtime.isAiResponding = true;
    if (event.thinking.isNotEmpty) {
      runtime.currentThinkingMessages[event.taskId] = event.thinking;
      runtime.deepThinkingContent = event.thinking;
    }
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    final streamMeta = _streamMetaFromEvent(event);
    final exists = runtime.messages.any((message) => message.id == cardId);
    if (exists) {
      _updateThinkingCard(
        runtime,
        event.taskId,
        cardId: cardId,
        thinkingContent: event.thinking.isNotEmpty ? event.thinking : null,
        isLoading: true,
        stage: event.stage <= 0 ? ThinkingStage.thinking.value : event.stage,
        streamMeta: streamMeta,
        lockCompleted: false,
      );
    } else {
      _createThinkingCard(
        runtime,
        event.taskId,
        cardId: cardId,
        thinkingContent: event.thinking,
        isLoading: true,
        stage: event.stage <= 0 ? ThinkingStage.thinking.value : event.stage,
        streamMeta: streamMeta,
      );
    }
    _notifyRuntimeListeners();
    schedulePersistRuntimeConversation(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _applyAgentTextStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final messageId = (event.entryId ?? '').trim();
    final text = event.text.trim();
    if (messageId.isEmpty || text.isEmpty) {
      return;
    }

    runtime.isAiResponding = true;
    runtime.currentAiMessages[event.taskId] = text;
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );

    if (event.isFinal) {
      // 最终帧：清空批次并全量渲染 markdown
      _clearStreamingTextBatch(
        runtime,
        event.taskId,
        _StreamingTextStreamKind.agentReply,
      );
      _upsertAgentReplyMessage(
        runtime,
        messageId,
        text,
        renderMarkdown: true,
        isFinal: true,
        streamMeta: _streamMetaFromEvent(event),
        turnUsage: event.turnUsage,
        prefillTokensPerSecond: event.prefillTokensPerSecond,
        decodeTokensPerSecond: event.decodeTokensPerSecond,
        reasoningContent: event.thinking,
      );
      _syncMessageLinkPreviews(runtime, messageId);
    } else {
      // 流式 chunk：通过批次控制 markdown 渲染边界，与 pureChatReply 路径一致。
      // 批次在 flush 之间保持 markdownRenderedLength 稳定，让 StreamingText
      // 以 prefix-growth 模式平滑逐字透出，而非每个 chunk 都把前缀推前。
      final visibleText = _visibleAgentReplyText(
        runtime,
        event.taskId,
        messageId: messageId,
      );
      final shouldFlush = _stageStreamingTextBatch(
        runtime,
        event.taskId,
        _StreamingTextStreamKind.agentReply,
        nextText: text,
        initialLatestText: visibleText,
        initialFlushedText: visibleText,
      );
      if (shouldFlush) {
        _streamingTextBatchFor(
          runtime,
          event.taskId,
          _StreamingTextStreamKind.agentReply,
        )?.markFlushed();
      }
      _upsertAgentReplyMessage(
        runtime,
        messageId,
        text,
        renderMarkdown: true,
        isFinal: false,
        markdownRenderedLength: _markdownRenderedLengthForBatch(
          runtime,
          event.taskId,
          _StreamingTextStreamKind.agentReply,
        ),
        streamMeta: _streamMetaFromEvent(event),
        turnUsage: event.turnUsage,
        reasoningContent: event.thinking,
      );
    }
    _notifyRuntimeListeners();
    schedulePersistRuntimeConversation(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _applyAgentToolStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final cardId = (event.entryId ?? '').trim();
    if (cardId.isEmpty) {
      return;
    }

    final toolEvent = AgentToolEventData.fromMap(event.raw);
    runtime.isAiResponding = true;
    _updateToolLayerState(runtime, toolEvent);
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    _upsertToolCard(
      runtime: runtime,
      taskId: event.taskId,
      cardId: cardId,
      event: toolEvent,
      status: event.kind == AgentStreamEventKind.toolCompleted
          ? _resolveToolStatus(toolEvent)
          : 'running',
      summary: toolEvent.summary.isNotEmpty
          ? toolEvent.summary
          : (_isEnglish ? 'Calling tool' : '正在调用工具'),
      progress: toolEvent.progress,
      resultPreviewJson: toolEvent.resultPreviewJson,
      rawResultJson: toolEvent.rawResultJson,
      reasoningContent: event.thinking,
      streamMeta: _streamMetaFromEvent(event),
    );
    if (event.kind == AgentStreamEventKind.toolCompleted) {
      _updateBrowserSessionSnapshot(runtime, toolEvent);
      if (event.browserSnapshot != null) {
        runtime.browserSessionSnapshot = event.browserSnapshot;
      }
    }
    _notifyRuntimeListeners();
    schedulePersistRuntimeConversation(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _applyAgentRetryingStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final entryId = (event.entryId ?? '').trim();
    final retryText = _buildAgentRetryingText(event);
    final streamMeta = ensureAgentStreamMessageMeta(
      _streamMetaFromEvent(event),
      entryId: entryId.isEmpty ? null : entryId,
      isFinal: false,
    );
    final isThinkingCardTarget =
        entryId.isNotEmpty &&
        runtime.messages.any(
          (message) => message.id == entryId && message.type == 2,
        );
    if (!isThinkingCardTarget) {
      _finalizeThinkingCard(
        runtime,
        event.taskId,
        cardId: completedThinkingCardId,
      );
    }
    if (isThinkingCardTarget) {
      _updateThinkingCard(
        runtime,
        event.taskId,
        cardId: entryId,
        thinkingContent: retryText,
        isLoading: true,
        stage: 1,
        streamMeta: streamMeta,
        lockCompleted: false,
      );
    } else {
      final messageId = entryId.isNotEmpty
          ? entryId
          : _nextAgentTextMessageId(runtime, event.taskId);
      final index = runtime.messages.indexWhere(
        (message) => message.id == messageId,
      );
      if (index == -1) {
        final content = <String, dynamic>{'text': '', 'id': messageId};
        _applyAgentRetryPresentation(content, event, retryText);
        runtime.messages.insert(
          0,
          ChatMessageModel(
            id: messageId,
            type: 1,
            user: 2,
            content: content,
            streamMeta: streamMeta,
          ),
        );
      } else {
        final existing = runtime.messages[index];
        final content = Map<String, dynamic>.from(existing.content ?? const {});
        content['id'] = messageId;
        _applyAgentRetryPresentation(content, event, retryText);
        runtime.messages[index] = existing.copyWith(
          content: content,
          streamMeta: streamMeta ?? existing.streamMeta,
          isError: false,
        );
      }
    }
    runtime.isAiResponding = true;
    _notifyRuntimeListeners();
    schedulePersistRuntimeConversation(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _applyAgentClarifyStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final text = event.question.trim().isNotEmpty
        ? event.question.trim()
        : event.text.trim();
    final messageId = (event.entryId ?? '').trim();
    if (messageId.isNotEmpty && text.isNotEmpty) {
      _upsertAgentReplyMessage(
        runtime,
        messageId,
        text,
        renderMarkdown: true,
        isFinal: true,
        streamMeta: _streamMetaFromEvent(event),
        turnUsage: event.turnUsage,
        reasoningContent: event.thinking,
      );
    }
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    runtime.isAiResponding = false;
    runtime.currentAiMessages.remove(event.taskId);
    runtime.currentThinkingMessages.remove(event.taskId);
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    _finalizeThinkingCardsForTask(runtime, event.taskId);
    runtime.agentStreamStates.remove(event.taskId);
    _taskBindings.remove(event.taskId);
    _notifyRuntimeListeners();
    unawaited(
      persistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
        markComplete: true,
      ),
    );
    clearConversationRuntimeSession(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _applyAgentCompletedStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    _finalizeLatestAgentTextSnapshotForCompletedTask(runtime, event);
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    runtime.isAiResponding = false;
    runtime.currentAiMessages.remove(event.taskId);
    runtime.currentThinkingMessages.remove(event.taskId);
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    _finalizeThinkingCardsForTask(runtime, event.taskId);
    runtime.agentStreamStates.remove(event.taskId);
    _taskBindings.remove(event.taskId);
    _notifyRuntimeListeners();
    unawaited(
      persistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
        markComplete: true,
      ),
    );
    clearConversationRuntimeSession(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _finalizeLatestAgentTextSnapshotForCompletedTask(
    ChatConversationRuntimeState runtime,
    AgentStreamEvent event,
  ) {
    final taskId = event.taskId.trim();
    if (taskId.isEmpty) {
      return;
    }
    var hasFinalText = false;
    var latestTextIndex = -1;
    for (var index = 0; index < runtime.messages.length; index += 1) {
      final message = runtime.messages[index];
      if (!_isAgentTextMessageForTask(message, taskId)) {
        continue;
      }
      final kind = (message.streamMeta?['kind'] ?? '')
          .toString()
          .trim()
          .toLowerCase();
      if (kind.isNotEmpty && kind != 'text_snapshot') {
        continue;
      }
      if (message.streamMeta?['isFinal'] == true) {
        hasFinalText = true;
        break;
      }
      if (latestTextIndex == -1 ||
          _isNewerAgentTextSnapshot(
            message,
            runtime.messages[latestTextIndex],
          )) {
        latestTextIndex = index;
      }
    }
    if (hasFinalText || latestTextIndex == -1) {
      return;
    }
    _clearStreamingTextBatch(
      runtime,
      taskId,
      _StreamingTextStreamKind.agentReply,
    );
    final existing = runtime.messages[latestTextIndex];
    runtime.messages[latestTextIndex] = existing.copyWith(
      content: _contentWithFinalMarkdown(existing),
      streamMeta: ensureAgentStreamMessageMeta(
        existing.streamMeta,
        entryId: existing.id,
        isFinal: true,
      ),
      turnUsage: event.turnUsage ?? existing.turnUsage,
    );
  }

  Map<String, dynamic> _contentWithFinalMarkdown(ChatMessageModel message) {
    final content = Map<String, dynamic>.from(message.content ?? const {});
    content['id'] = message.id;
    content['renderMarkdown'] = true;
    content.remove('markdownRenderedLength');
    return content;
  }

  bool _isNewerAgentTextSnapshot(
    ChatMessageModel left,
    ChatMessageModel right,
  ) {
    final leftRound = _asPositiveInt(left.streamMeta?['roundIndex']) ?? 0;
    final rightRound = _asPositiveInt(right.streamMeta?['roundIndex']) ?? 0;
    if (leftRound != rightRound) {
      return leftRound > rightRound;
    }
    final leftSeq = _asPositiveInt(left.streamMeta?['seq']) ?? 0;
    final rightSeq = _asPositiveInt(right.streamMeta?['seq']) ?? 0;
    if (leftSeq != rightSeq) {
      return leftSeq > rightSeq;
    }
    return left.createAt.isAfter(right.createAt);
  }

  void _applyAgentErrorStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final entryId = (event.entryId ?? '').trim();
    final shouldMarkError = event.raw['persistAsError'] == true;
    final errorText = (event.raw['errorText'] ?? event.errorMessage)
        .toString()
        .trim();
    if (entryId.isNotEmpty) {
      final index = runtime.messages.indexWhere(
        (message) => message.id == entryId,
      );
      if (index != -1) {
        final existing = runtime.messages[index];
        final content = Map<String, dynamic>.from(existing.content ?? const {});
        _applyAgentErrorPresentation(content, event, errorText);
        runtime.messages[index] = runtime.messages[index].copyWith(
          content: content,
          isError: shouldMarkError,
          streamMeta: ensureAgentStreamMessageMeta(
            _streamMetaFromEvent(event),
            entryId: entryId,
            isFinal: true,
          ),
          turnUsage: event.turnUsage ?? existing.turnUsage,
        );
      }
    }
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    runtime.isAiResponding = false;
    runtime.currentAiMessages.remove(event.taskId);
    runtime.currentThinkingMessages.remove(event.taskId);
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    _finalizeThinkingCardsForTask(runtime, event.taskId);
    runtime.agentStreamStates.remove(event.taskId);
    _taskBindings.remove(event.taskId);
    _notifyRuntimeListeners();
    unawaited(
      persistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
        markComplete: true,
      ),
    );
    clearConversationRuntimeSession(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _applyAgentPermissionStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final messageId = (event.entryId ?? '').trim();
    final text = event.text.trim();
    if (messageId.isNotEmpty && text.isNotEmpty) {
      _upsertAgentReplyMessage(
        runtime,
        messageId,
        text,
        renderMarkdown: true,
        isFinal: true,
        streamMeta: _streamMetaFromEvent(event),
        turnUsage: event.turnUsage,
        reasoningContent: event.thinking,
      );
    }
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );

    final executionPermissionIds = event.missingPermissions
        .map((item) => item.trim())
        .map((item) => _executionPermissionNameToId[item])
        .whereType<String>()
        .toSet()
        .toList(growable: false);
    final permissionCardId =
        (event.raw['permissionCardId'] ?? '${event.taskId}-permission')
            .toString();
    if (executionPermissionIds.isNotEmpty) {
      final cardIndex = runtime.messages.indexWhere(
        (message) => message.id == permissionCardId,
      );
      final cardData = <String, dynamic>{
        'type': 'permission_section',
        'requiredPermissionIds': executionPermissionIds,
      };
      final message = ChatMessageModel(
        id: permissionCardId,
        type: 2,
        user: 3,
        content: {'cardData': cardData, 'id': permissionCardId},
        streamMeta: _streamMetaFromEvent(event),
      );
      if (cardIndex == -1) {
        runtime.messages.insert(0, message);
      } else {
        runtime.messages[cardIndex] = runtime.messages[cardIndex].copyWith(
          content: {'cardData': cardData, 'id': permissionCardId},
          streamMeta: _streamMetaFromEvent(event),
        );
      }
    }

    runtime.isAiResponding = false;
    runtime.currentAiMessages.remove(event.taskId);
    runtime.currentThinkingMessages.remove(event.taskId);
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    _finalizeThinkingCardsForTask(runtime, event.taskId);
    runtime.agentStreamStates.remove(event.taskId);
    _taskBindings.remove(event.taskId);
    _notifyRuntimeListeners();
    unawaited(
      persistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
        markComplete: true,
      ),
    );
    clearConversationRuntimeSession(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  Map<String, dynamic> _streamMetaFromEvent(AgentStreamEvent event) {
    return buildAgentStreamMetaFromEvent(event);
  }

  String _buildAgentRetryingText(AgentStreamEvent event) {
    if (event.text.trim().isNotEmpty) {
      return event.text.trim();
    }
    final retryCount = event.retryCount <= 0 ? 1 : event.retryCount;
    final maxRetries = event.maxRetries <= 0 ? 3 : event.maxRetries;
    return LegacyTextLocalizer.isEnglish
        ? 'Connection interrupted. Retrying $retryCount/$maxRetries...'
        : '连接中断，正在重试 $retryCount/$maxRetries…';
  }

  void _applyAgentRetryPresentation(
    Map<String, dynamic> content,
    AgentStreamEvent event,
    String retryText,
  ) {
    content['agentTaskId'] = event.taskId;
    content['agentRetrying'] = true;
    content['agentRetryStatusText'] = retryText;
    content['agentRetryCount'] = event.retryCount;
    content['agentMaxRetries'] = event.maxRetries;
    content['agentRetryDelayMs'] = event.retryDelayMs;
    content['agentRetryReason'] = event.retryReason;
    content['agentContinuing'] = false;
    content.remove('agentContinueStatusText');
    content.remove('agentContinueable');
    content.remove('agentContinueResumeMode');
    content.remove('agentErrorText');
    content.remove('agentRetryable');
  }

  void _applyAgentErrorPresentation(
    Map<String, dynamic> content,
    AgentStreamEvent event,
    String errorText,
  ) {
    content['agentTaskId'] = event.taskId;
    content['agentRetrying'] = false;
    content['agentRetryStatusText'] = '';
    content['agentRetryCount'] = event.retryCount;
    content['agentMaxRetries'] = event.maxRetries;
    content['agentRetryDelayMs'] = 0;
    content['agentRetryReason'] = event.retryReason;
    content['agentContinuing'] = false;
    content['agentContinueStatusText'] = '';
    content['agentRetryable'] = event.retryable;
    content['agentContinueable'] = event.continueable;
    content['agentContinueResumeMode'] = event.continueResumeMode;
    content['agentErrorText'] = errorText;
  }

  void _clearAgentRetryPresentation(Map<String, dynamic> content) {
    content.remove('agentRetrying');
    content.remove('agentRetryStatusText');
    content.remove('agentRetryCount');
    content.remove('agentMaxRetries');
    content.remove('agentRetryDelayMs');
    content.remove('agentRetryReason');
    content.remove('agentRetryable');
    content.remove('agentContinuing');
    content.remove('agentContinueStatusText');
    content.remove('agentContinueable');
    content.remove('agentContinueResumeMode');
    content.remove('agentErrorText');
  }

  void _upsertPureChatThinking(
    ChatConversationRuntimeState runtime,
    String taskId,
    String thinking,
  ) {
    final binding = _taskBindings[taskId];
    if (binding == null) {
      return;
    }
    final previous = runtime.currentThinkingMessages[taskId] ?? '';
    final merged = mergeLegacyStreamingText(previous, thinking);
    if (merged.isEmpty || merged == previous) {
      return;
    }

    runtime.currentThinkingMessages[taskId] = merged;
    if (runtime.thinkingRound == 0) {
      primePureChatThinking(
        taskId: taskId,
        conversationId: binding.conversationId,
        mode: binding.mode,
      );
    }
    final visibleThinking = _visibleThinkingText(runtime, taskId);
    final shouldFlush = _stageStreamingTextBatch(
      runtime,
      taskId,
      _StreamingTextStreamKind.pureChatThinking,
      nextText: merged,
      initialLatestText: previous.isNotEmpty ? previous : visibleThinking,
      initialFlushedText: visibleThinking,
    );
    if (shouldFlush) {
      _flushThinkingBatch(
        runtime,
        taskId,
        _StreamingTextStreamKind.pureChatThinking,
        schedulePersistence: true,
      );
    }
  }

  void _applyThinkingUpdate(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    String taskId,
    String thinking, {
    bool notifyAfterUpdate = true,
    bool schedulePersistence = true,
  }) {
    if (runtime.pendingThinkingRoundSplit) {
      if (thinking.trim().isEmpty) {
        return;
      }

      final previousThinkingCardId = _resolveThinkingCardId(runtime, taskId);
      if (previousThinkingCardId != null) {
        _updateThinkingCard(
          runtime,
          taskId,
          cardId: previousThinkingCardId,
          isLoading: false,
          stage: ThinkingStage.complete.value,
          lockCompleted: false,
        );
      }

      runtime.thinkingRound += 1;
      runtime.activeThinkingCardId =
          '$taskId-thinking-${runtime.thinkingRound}';
      _createThinkingCard(
        runtime,
        taskId,
        cardId: runtime.activeThinkingCardId,
        thinkingContent: thinking,
        isLoading: true,
        stage: ThinkingStage.thinking.value,
      );
      runtime.deepThinkingContent = thinking;
      runtime.pendingThinkingRoundSplit = false;
      if (notifyAfterUpdate) {
        _notifyRuntimeListeners();
      }
      return;
    }

    runtime.deepThinkingContent = thinking;
    runtime.lastAgentTaskId = taskId;
    runtime.currentThinkingStage = ThinkingStage.thinking.value;
    runtime.isDeepThinking = true;
    final thinkingCardId = _resolveThinkingCardId(runtime, taskId);
    if (thinkingCardId == null) {
      runtime.activeThinkingCardId = _baseThinkingCardId(taskId);
      _createThinkingCard(
        runtime,
        taskId,
        cardId: runtime.activeThinkingCardId,
        thinkingContent: thinking,
        isLoading: true,
        stage: runtime.currentThinkingStage,
      );
    } else {
      _updateThinkingCard(
        runtime,
        taskId,
        cardId: thinkingCardId,
        thinkingContent: thinking,
        isLoading: true,
        stage: runtime.currentThinkingStage,
        lockCompleted: false,
      );
    }
    if (notifyAfterUpdate) {
      _notifyRuntimeListeners();
    }
  }

  void _handleAgentContextCompactionStateChanged(
    String taskId,
    bool isCompacting,
    int? latestPromptTokens,
    int? promptTokenThreshold,
  ) {
    final binding = _taskBindings[taskId];
    final runtime = _runtimeForTask(taskId);
    if (binding == null || runtime == null) return;

    _applyPromptTokenUsageUpdate(
      runtime,
      latestPromptTokens: latestPromptTokens,
      promptTokenThreshold: promptTokenThreshold,
    );
    if (isCompacting) {
      beginContextCompaction(
        conversationId: binding.conversationId,
        mode: binding.mode,
        taskId: taskId,
        trigger: 'auto',
        latestPromptTokens: latestPromptTokens,
        promptTokenThreshold: promptTokenThreshold,
      );
    } else {
      finishContextCompaction(
        conversationId: binding.conversationId,
        mode: binding.mode,
        status: 'completed',
        latestPromptTokens: latestPromptTokens,
        promptTokenThreshold: promptTokenThreshold,
      );
    }
  }

  void _handleAgentPromptTokenUsageChanged(
    String taskId,
    int latestPromptTokens,
    int? promptTokenThreshold,
  ) {
    final binding = _taskBindings[taskId];
    final runtime = _runtimeForTask(taskId);
    if (binding == null || runtime == null) return;

    _applyPromptTokenUsageUpdate(
      runtime,
      latestPromptTokens: latestPromptTokens,
      promptTokenThreshold: promptTokenThreshold,
    );
    _notifyRuntimeListeners();
    schedulePersistRuntimeConversation(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }
}
