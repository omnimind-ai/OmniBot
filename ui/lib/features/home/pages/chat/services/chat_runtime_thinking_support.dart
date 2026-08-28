part of 'chat_conversation_runtime_coordinator.dart';

extension _ChatRuntimeThinkingSupport on ChatConversationRuntimeCoordinator {
  void _createThinkingCard(
    ChatConversationRuntimeState runtime,
    String taskId, {
    String? cardId,
    String? thinkingContent,
    bool? isLoading,
    int? stage,
    Map<String, dynamic>? streamMeta,
  }) {
    final loadingIndex = runtime.messages.indexWhere((msg) => msg.id == taskId);
    if (loadingIndex != -1) {
      runtime.messages.removeAt(loadingIndex);
    }

    final startTime = DateTime.now().millisecondsSinceEpoch;
    final thinkingCardId = cardId ?? '$taskId-thinking';
    final cardData = {
      'type': 'deep_thinking',
      'isLoading': isLoading ?? runtime.isDeepThinking,
      'thinkingContent': thinkingContent ?? '',
      'stage': stage ?? runtime.currentThinkingStage,
      'taskID': taskId,
      'cardId': thinkingCardId,
      'startTime': startTime,
      'endTime': null,
    };

    runtime.messages.removeWhere((msg) => msg.id == thinkingCardId);
    runtime.messages.insert(
      0,
      ChatMessageModel(
        id: thinkingCardId,
        type: 2,
        user: 3,
        content: {'cardData': cardData, 'id': thinkingCardId},
        createAt: DateTime.fromMillisecondsSinceEpoch(startTime),
        streamMeta: ensureAgentStreamMessageMeta(
          streamMeta,
          entryId: thinkingCardId,
        ),
      ),
    );
  }

  String _buildContextCompactionMarkerId({
    required int conversationId,
    String? taskId,
    required String trigger,
  }) {
    final suffix = DateTime.now().millisecondsSinceEpoch;
    final normalizedTaskId = taskId?.trim();
    if (normalizedTaskId != null && normalizedTaskId.isNotEmpty) {
      return '$normalizedTaskId-context-compaction-$suffix';
    }
    return 'conversation-$conversationId-$trigger-context-compaction-$suffix';
  }

  void _upsertContextCompactionMarker(
    ChatConversationRuntimeState runtime, {
    required String markerId,
    required String status,
    String trigger = 'auto',
    int? latestPromptTokens,
    int? promptTokenThreshold,
  }) {
    final index = runtime.messages.indexWhere((msg) => msg.id == markerId);
    final existing = index == -1 ? null : runtime.messages[index];
    final existingCardData = Map<String, dynamic>.from(
      existing?.cardData ?? const <String, dynamic>{},
    );
    final startTime =
        (existingCardData['startTime'] as int?) ??
        DateTime.now().millisecondsSinceEpoch;
    final endTime = status == 'compressing'
        ? null
        : DateTime.now().millisecondsSinceEpoch;
    final resolvedTriggerRaw = (existingCardData['trigger'] ?? trigger)
        .toString()
        .trim();
    final resolvedTrigger = resolvedTriggerRaw.isEmpty
        ? trigger
        : resolvedTriggerRaw;
    final cardData = <String, dynamic>{
      'type': 'context_compaction_marker',
      'status': status,
      'label': _contextCompactionLabel(status),
      'trigger': resolvedTrigger,
      'startTime': startTime,
      'endTime': endTime,
      'latestPromptTokens':
          latestPromptTokens ?? runtime.conversation?.latestPromptTokens,
      'promptTokenThreshold':
          promptTokenThreshold ?? runtime.conversation?.promptTokenThreshold,
    };
    final message = ChatMessageModel(
      id: markerId,
      type: 2,
      user: 3,
      content: {'cardData': cardData, 'id': markerId},
      createAt: DateTime.fromMillisecondsSinceEpoch(startTime),
    );
    if (index == -1) {
      runtime.messages.insert(0, message);
    } else {
      runtime.messages[index] = existing!.copyWith(
        content: {'cardData': cardData, 'id': markerId},
      );
    }
    _persistContextCompactionMarkerIfNeeded(
      conversationId: runtime.conversationId,
      mode: runtime.mode,
      message: index == -1 ? message : runtime.messages[index],
    );
  }

  String _contextCompactionLabel(String status) {
    return switch (status) {
      'compressing' => _isEnglish ? 'Compressing' : '正在压缩',
      'noop' => _isEnglish ? 'No compaction needed' : '无需压缩',
      'failed' => _isEnglish ? 'Compaction failed' : '压缩失败',
      _ => _isEnglish ? 'Compacted' : '已压缩',
    };
  }

  void _persistContextCompactionMarkerIfNeeded({
    required int conversationId,
    required String mode,
    required ChatMessageModel message,
  }) {
    if (isEphemeralRuntime(conversationId: conversationId, mode: mode)) {
      return;
    }
    final cardData = message.cardData;
    if (message.type != 2 || cardData?['type'] != 'context_compaction_marker') {
      return;
    }
    unawaited(
      ConversationHistoryService.upsertConversationUiCard(
        conversationId,
        entryId: message.id,
        cardData: Map<String, dynamic>.from(cardData!),
        createdAtMillis: message.createAt.millisecondsSinceEpoch,
        mode: _conversationModeFromRuntimeMode(
          mode,
          conversation: runtimeFor(
            conversationId: conversationId,
            mode: mode,
          )?.conversation,
        ),
      ),
    );
  }

  void _updateThinkingCard(
    ChatConversationRuntimeState runtime,
    String taskId, {
    String? cardId,
    String? thinkingContent,
    bool? isLoading,
    int? stage,
    Map<String, dynamic>? streamMeta,
    bool lockCompleted = true,
  }) {
    final thinkingCardId = cardId ?? '$taskId-thinking';
    final index = runtime.messages.indexWhere(
      (msg) => msg.id == thinkingCardId,
    );
    if (index == -1) return;

    final existing = runtime.messages[index];
    final content = Map<String, dynamic>.from(existing.content ?? {});
    final cardData = Map<String, dynamic>.from(content['cardData'] ?? {});

    final currentStage = cardData['stage'] as int? ?? 1;
    final targetStage = stage ?? runtime.currentThinkingStage;
    final newStage = (lockCompleted && currentStage == 4) ? 4 : targetStage;

    final startTime = cardData['startTime'] as int?;
    int? endTime = cardData['endTime'] as int?;
    if (newStage == 4 && endTime == null) {
      endTime = DateTime.now().millisecondsSinceEpoch;
    }

    cardData['thinkingContent'] =
        thinkingContent ?? runtime.deepThinkingContent;
    cardData['isLoading'] = isLoading ?? runtime.isDeepThinking;
    cardData['stage'] = newStage;
    cardData['taskID'] = taskId;
    cardData['cardId'] = thinkingCardId;
    cardData['startTime'] = startTime;
    cardData['endTime'] = endTime;

    content['cardData'] = cardData;
    runtime.messages[index] = existing.copyWith(
      content: content,
      streamMeta: ensureAgentStreamMessageMeta(
        streamMeta ?? existing.streamMeta,
        entryId: thinkingCardId,
      ),
    );
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

  void _applyThinkingUpdate(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    String taskId,
    String thinking, {
    bool notifyAfterUpdate = true,
    bool schedulePersistence = true,
  }) {
    if (runtime.pendingThinkingRoundSplit) {
      if (thinking.trim().isEmpty) return;
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
      runtime.activeThinkingCardId = '$taskId-thinking-${runtime.thinkingRound}';
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
      if (notifyAfterUpdate) _notifyRuntimeListeners();
      return;
    }

    runtime.deepThinkingContent = thinking;
    runtime.lastAgentTurnId = taskId;
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
    if (notifyAfterUpdate) _notifyRuntimeListeners();
    if (schedulePersistence) {
      schedulePersistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
      );
    }
  }

  String _baseThinkingCardId(String taskId) => '$taskId-thinking';

  String? _resolveThinkingCardId(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    if (runtime.activeThinkingCardId != null) {
      return runtime.activeThinkingCardId;
    }
    final baseId = _baseThinkingCardId(taskId);
    final exists = runtime.messages.any((msg) => msg.id == baseId);
    return exists ? baseId : null;
  }

  String? _resolvePendingAgentTextMessageId(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    if (runtime.pendingAgentTextTaskId != taskId) return null;
    for (final message in runtime.messages) {
      if (_isAgentTextMessageForTask(message, taskId)) {
        return message.id;
      }
    }
    return null;
  }

  String _nextAgentTextMessageId(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    final baseId = _agentTextBaseId(taskId);
    var maxSequence = 0;
    for (final message in runtime.messages) {
      final sequence = _agentTextMessageSequence(message.id, taskId);
      if (sequence > maxSequence) {
        maxSequence = sequence;
      }
    }
    if (maxSequence == 0) {
      return baseId;
    }
    return '$baseId-${maxSequence + 1}';
  }

  bool _isAgentTextMessageForTask(ChatMessageModel message, String taskId) {
    if (message.type != 1 || message.user != 2) {
      return false;
    }
    return _agentTextMessageSequence(message.id, taskId) > 0;
  }

  int _agentTextMessageSequence(String messageId, String taskId) {
    final baseId = _agentTextBaseId(taskId);
    if (messageId == baseId) {
      return 1;
    }
    if (!messageId.startsWith('$baseId-')) {
      return 0;
    }
    return int.tryParse(messageId.substring(baseId.length + 1)) ?? 0;
  }
}
