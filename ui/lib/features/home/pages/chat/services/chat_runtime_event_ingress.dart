part of 'chat_conversation_runtime_coordinator.dart';

extension _ChatRuntimeEventIngress on ChatConversationRuntimeCoordinator {
  void _handleChatTaskMessage(String taskId, String content, String? type) {
    final binding = _taskBindings[taskId];
    final runtime = _runtimeForTask(taskId);
    if (binding == null || runtime == null) return;

    final isErrorMessage = type == 'error';
    final isRateLimited = type == 'rate_limited';
    final isOpenClawAttachment = type == 'openclaw_attachment';
    final payload = safeDecodeMap(content);
    final payloadAttachments = _parseAttachments(payload['attachments']);
    final prefillTokensPerSecond = extractChatTaskPrefillTokensPerSecond(
      content,
    );
    final decodeTokensPerSecond = extractChatTaskDecodeTokensPerSecond(content);
    final hasPerformanceMetrics =
        prefillTokensPerSecond != null || decodeTokensPerSecond != null;

    String messageText;
    bool isError;
    bool isSummarizing;
    var shouldUpdateAiMessage = false;
    var didSchedulePersistence = false;
    var hadPartialText = false;

    if (isRateLimited) {
      _flushPureChatReplyBatch(runtime, taskId);
      messageText = kRateLimitErrorMessage;
      isError = true;
      isSummarizing = false;
      runtime.isContextCompressing = false;
      runtime.currentAiMessages.remove(taskId);
      _clearStreamingTextBatch(
        runtime,
        taskId,
        _StreamingTextStreamKind.pureChatReply,
      );
      shouldUpdateAiMessage = true;
    } else if (isErrorMessage) {
      hadPartialText =
          (runtime.currentAiMessages[taskId]?.isNotEmpty ?? false) ||
          _visiblePureChatReplyText(runtime, taskId).isNotEmpty;
      _flushPureChatReplyBatch(runtime, taskId);
      messageText = kNetworkErrorMessage;
      isError = true;
      isSummarizing = false;
      runtime.isContextCompressing = false;
      runtime.currentAiMessages.remove(taskId);
      _clearStreamingTextBatch(
        runtime,
        taskId,
        _StreamingTextStreamKind.pureChatReply,
      );
      shouldUpdateAiMessage = true;
    } else if (isOpenClawAttachment) {
      messageText =
          runtime.currentAiMessages[taskId] ??
          _visiblePureChatReplyText(runtime, taskId);
      isError = false;
      isSummarizing = false;
      runtime.isContextCompressing = false;
      shouldUpdateAiMessage = true;
    } else {
      final thinking = extractChatTaskThinking(
        content,
        fallbackToRawText: false,
      );
      if (thinking.isNotEmpty) {
        _upsertPureChatThinking(runtime, taskId, thinking);
      }
      final text = extractChatTaskText(content, fallbackToRawText: false);
      if (text.isNotEmpty) {
        final previousText = runtime.currentAiMessages[taskId] ?? '';
        final mergedText = mergeLegacyStreamingText(previousText, text);
        if (mergedText != previousText && mergedText.isNotEmpty) {
          runtime.currentAiMessages[taskId] = mergedText;
          final visibleText = _visiblePureChatReplyText(runtime, taskId);
          final shouldFlush = _stageStreamingTextBatch(
            runtime,
            taskId,
            _StreamingTextStreamKind.pureChatReply,
            nextText: mergedText,
            initialLatestText: previousText.isNotEmpty
                ? previousText
                : visibleText,
            initialFlushedText: visibleText,
          );
          if (shouldFlush) {
            _flushPureChatReplyBatch(
              runtime,
              taskId,
              schedulePersistence: true,
            );
            didSchedulePersistence = true;
          } else {
            final batch = _streamingTextBatchFor(
              runtime,
              taskId,
              _StreamingTextStreamKind.pureChatReply,
            );
            _applyPureChatReplyUpdate(
              runtime,
              taskId,
              text: mergedText,
              isError: false,
              renderMarkdown: true,
              markdownRenderedLength: batch?.lastFlushedText.length,
              isStreamingMarkdown: true,
            );
          }
        }
      }
      messageText = runtime.currentAiMessages[taskId] ?? '';
      isError = false;
      isSummarizing = false;
      runtime.isContextCompressing = false;
      if (payloadAttachments.isNotEmpty || hasPerformanceMetrics) {
        shouldUpdateAiMessage = true;
      }
    }

    if (shouldUpdateAiMessage &&
        _applyPureChatReplyUpdate(
          runtime,
          taskId,
          text: messageText,
          isError: isError,
          renderMarkdown: true,
          markdownRenderedLength: _markdownRenderedLengthForBatch(
            runtime,
            taskId,
            _StreamingTextStreamKind.pureChatReply,
          ),
          isStreamingMarkdown:
              !isError &&
              !isSummarizing &&
              runtime.currentAiMessages.containsKey(taskId),
          isSummarizing: isSummarizing,
          attachments: payloadAttachments,
          prefillTokensPerSecond: prefillTokensPerSecond,
          decodeTokensPerSecond: decodeTokensPerSecond,
          schedulePersistence: true,
        )) {
      didSchedulePersistence = true;
    }
    if (isError && isErrorMessage) {
      final errIdx = runtime.messages.indexWhere((m) => m.id == taskId);
      if (errIdx != -1) {
        final errMsg = runtime.messages[errIdx];
        final errContent = Map<String, dynamic>.from(errMsg.content ?? {});
        errContent['agentRetryable'] = true;
        if (hadPartialText) errContent['agentContinueable'] = true;
        runtime.messages[errIdx] = errMsg.copyWith(content: errContent);
      }
    }
    runtime.isAiResponding = true;
    _notifyRuntimeListeners();
    if (!didSchedulePersistence && (isRateLimited || isErrorMessage)) {
      schedulePersistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
      );
    }
  }

  void _handleChatTaskMessageEnd(
    String taskId, {
    Map<String, dynamic>? turnUsage,
  }) {
    final binding = _taskBindings[taskId];
    final runtime = _runtimeForTask(taskId);
    if (binding == null || runtime == null) return;

    _flushThinkingBatch(
      runtime,
      taskId,
      _StreamingTextStreamKind.pureChatThinking,
    );
    final thinkingCardId = _resolveThinkingCardId(runtime, taskId);
    if (thinkingCardId != null) {
      runtime.currentThinkingStage = ThinkingStage.complete.value;
      runtime.isDeepThinking = false;
      _finalizeThinkingCardsForTask(runtime, taskId);
      runtime.currentThinkingMessages.remove(taskId);
      runtime.deepThinkingContent = '';
      runtime.lastAgentTaskId = null;
      runtime.activeThinkingCardId = null;
      runtime.pendingThinkingRoundSplit = false;
      runtime.thinkingRound = 0;
    }

    runtime.isAiResponding = false;
    runtime.isContextCompressing = false;
    _flushPureChatReplyBatch(runtime, taskId, isFinal: true);
    final index = runtime.messages.indexWhere((msg) => msg.id == taskId);
    final isErrorMessage = index != -1 && runtime.messages[index].isError;
    final messageText = isErrorMessage
        ? (runtime.messages[index].content?['text'] as String? ?? '')
        : (runtime.currentAiMessages[taskId] ??
              _visiblePureChatReplyText(runtime, taskId));

    if (messageText.isNotEmpty && index != -1) {
      final existing = runtime.messages[index];
      final content = Map<String, dynamic>.from(existing.content ?? const {});
      content.remove('isStreamingMarkdown');
      content.remove('markdownRenderedLength');
      runtime.messages[index] = existing.copyWith(
        content: content,
        turnUsage: turnUsage ?? existing.turnUsage,
      );
      _syncMessageLinkPreviews(runtime, taskId);
    }
    runtime.currentAiMessages.remove(taskId);
    _clearStreamingTextBatchesForTask(runtime, taskId);
    _taskBindings.remove(taskId);
    _notifyRuntimeListeners();
    unawaited(
      persistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
        markComplete: true,
      ),
    );
  }

  void _handleAgentStreamEvent(AgentStreamEvent event) {
    var binding = _taskBindings[event.taskId];
    var runtime = _runtimeForTask(event.taskId);
    if (binding == null || runtime == null) {
      final recovered = _recoverExternalAgentStreamBinding(event);
      if (recovered == null) {
        return;
      }
      binding = recovered.binding;
      runtime = recovered.runtime;
    }

    final reduceResult = _agentStreamReducer.reduce(
      runtime.agentStreamStates[event.taskId],
      event,
    );
    if (!reduceResult.accepted) {
      return;
    }
    final thinkingCardToFinalize = _resolveThinkingCardToFinalize(
      reduceResult,
      event,
    );
    runtime.agentStreamStates[event.taskId] = reduceResult.nextState;
    _syncRuntimeAgentState(runtime, event, reduceResult.nextState);

    switch (event.kind) {
      case AgentStreamEventKind.thinkingStarted:
      case AgentStreamEventKind.thinkingSnapshot:
        _applyAgentThinkingStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.retrying:
        _applyAgentRetryingStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.textSnapshot:
        _applyAgentTextStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.toolStarted:
      case AgentStreamEventKind.toolProgress:
      case AgentStreamEventKind.toolCompleted:
        _applyAgentToolStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.clarifyRequired:
        _applyAgentClarifyStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.completed:
        _applyAgentCompletedStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.error:
        _applyAgentErrorStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.permissionRequired:
        _applyAgentPermissionStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
    }
  }

  ({_TaskBinding binding, ChatConversationRuntimeState runtime})?
  _recoverExternalAgentStreamBinding(AgentStreamEvent event) {
    final conversationId = _asPositiveInt(event.raw['conversationId']);
    if (conversationId == null) {
      return null;
    }
    final runtimeMode = _runtimeModeFromConversationMode(
      (event.raw['conversationMode'] ?? event.raw['mode'] ?? '').toString(),
    );
    final runtime = runtimeFor(
      conversationId: conversationId,
      mode: runtimeMode,
    );
    if (runtime == null) {
      return null;
    }
    final binding = _TaskBinding(
      conversationId: conversationId,
      mode: runtimeMode,
    );
    _taskBindings[event.taskId] = binding;
    _adoptRuntimeTurnId(runtime, event.taskId);
    // 外部任务（IM 等）触发：用户消息已经写入 DB，但可能还没进入 runtime.messages
    // —— Flutter 的 messagesChanged 事件走的是异步 stream listener（微任务），
    // 而 agent 流事件是同步回调，常常先到达。这里把缺失的用户消息从 DB 补回来，
    // 否则聊天页只会看到 agent 的回复，没有用户输入。
    final userEntryId = '${event.taskId}-user';
    final alreadyPresent = _hasEquivalentAgentUserMessage(
      runtime.messages,
      entryId: userEntryId,
    );
    if (!alreadyPresent) {
      unawaited(
        _reconcileExternalUserMessage(
          conversationId: conversationId,
          mode: runtimeMode,
          userEntryId: userEntryId,
        ),
      );
    }
    return (binding: binding, runtime: runtime);
  }

  void _handleExternalUserMessageAppended(Map<String, dynamic> data) {
    // 原生侧（IM 等）写完用户消息后直推过来的 payload —— 不依赖 messagesChanged
    // 微任务，也不依赖 agent 流事件，直接把用户气泡插入 runtime.messages。
    final conversationId = _asPositiveInt(data['conversationId']);
    if (conversationId == null) return;
    final runtimeMode = _runtimeModeFromConversationMode(
      (data['mode'] ?? data['conversationMode'] ?? '').toString(),
    );
    final runtimeKey = _runtimeKey(
      conversationId: conversationId,
      mode: runtimeMode,
    );
    final runtime = _runtimes[runtimeKey];
    if (runtime == null) {
      // 聊天页还没为这个会话建立 runtime —— 没什么可注入的，等聊天页加载时
      // 自然会从 DB 读到这条用户消息。
      return;
    }
    final entryId = (data['entryId'] ?? '').toString().trim();
    if (entryId.isEmpty) return;

    final text = (data['text'] ?? '').toString();
    final createdAtMs =
        _asPositiveInt(data['createdAt']) ??
        DateTime.now().millisecondsSinceEpoch;
    final createdAt = DateTime.fromMillisecondsSinceEpoch(createdAtMs);
    if (_hasEquivalentAgentUserMessage(
      runtime.messages,
      entryId: entryId,
      text: text,
      createdAt: createdAt,
    )) {
      return;
    }
    final rawAttachments = data['attachments'];
    final attachments = rawAttachments is List
        ? rawAttachments
              .whereType<Map>()
              .map((item) => Map<String, dynamic>.from(item.cast()))
              .toList()
        : const <Map<String, dynamic>>[];

    final message = ChatMessageModel(
      id: entryId,
      type: 1,
      user: 1,
      content: <String, dynamic>{
        'id': entryId,
        'text': text,
        if (attachments.isNotEmpty) 'attachments': attachments,
      },
      createAt: createdAt,
    );

    final insertAt = _findInsertIndexByCreatedAt(
      runtime.messages,
      message.createAt,
    );
    runtime.messages.insert(insertAt, message);
    _notifyRuntimeListeners();
  }

  Future<void> _reconcileExternalUserMessage({
    required int conversationId,
    required String mode,
    required String userEntryId,
  }) async {
    final runtimeKey = _runtimeKey(conversationId: conversationId, mode: mode);
    final runtime = _runtimes[runtimeKey];
    if (runtime == null) return;
    if (_hasEquivalentAgentUserMessage(
      runtime.messages,
      entryId: userEntryId,
    )) {
      return;
    }
    final conversationMode = switch (mode) {
      kChatRuntimeModeOpenClaw => ConversationMode.openclaw,
      kChatRuntimeModeAgent => ConversationMode.agent,
      _ => ConversationMode.normal,
    };
    try {
      final result =
          await ConversationHistoryService.getConversationMessagesPaged(
            conversationId,
            mode: conversationMode,
            limit: 100,
            offset: 0,
          );
      final stillMissingFromRuntime = _runtimes[runtimeKey];
      if (stillMissingFromRuntime == null) return;
      if (_hasEquivalentAgentUserMessage(
        stillMissingFromRuntime.messages,
        entryId: userEntryId,
      )) {
        return;
      }
      ChatMessageModel? userMessage;
      for (final message in result.messages) {
        if (message.id == userEntryId) {
          userMessage = message;
          break;
        }
      }
      if (userMessage == null) return;
      if (_hasEquivalentAgentUserMessage(
        stillMissingFromRuntime.messages,
        entryId: userMessage.id,
        text: userMessage.text,
        createdAt: userMessage.createAt,
      )) {
        return;
      }
      final insertAt = _findInsertIndexByCreatedAt(
        stillMissingFromRuntime.messages,
        userMessage.createAt,
      );
      stillMissingFromRuntime.messages.insert(insertAt, userMessage);
      _notifyRuntimeListeners();
    } catch (_) {
      // 即使 DB 拉取失败也不要崩溃 —— 后续 messagesChanged 事件还有机会补救。
    }
  }

  List<ChatMessageModel> _dedupeEquivalentAgentUserMessages(
    Iterable<ChatMessageModel> messages,
  ) {
    final source = List<ChatMessageModel>.from(messages);
    final preferredByCanonicalId = <String, ChatMessageModel>{};
    for (final message in source) {
      if (message.user != 1) {
        continue;
      }
      final canonicalId = _canonicalAgentUserMessageId(message.id);
      if (canonicalId == null) {
        continue;
      }
      final existing = preferredByCanonicalId[canonicalId];
      if (existing == null || _preferAgentUserMessage(message, existing)) {
        preferredByCanonicalId[canonicalId] = message;
      }
    }
    if (preferredByCanonicalId.isEmpty) {
      return source;
    }
    return source
        .where((message) {
          if (message.user != 1) {
            return true;
          }
          final canonicalId = _canonicalAgentUserMessageId(message.id);
          if (canonicalId == null) {
            return true;
          }
          return identical(preferredByCanonicalId[canonicalId], message);
        })
        .toList(growable: false);
  }

  bool _hasEquivalentAgentUserMessage(
    Iterable<ChatMessageModel> messages, {
    required String entryId,
    String? text,
    DateTime? createdAt,
  }) {
    final canonicalId = _canonicalAgentUserMessageId(entryId);
    if (canonicalId == null) {
      return messages.any((message) => message.id == entryId);
    }
    final normalizedText = text?.trim();
    for (final message in messages) {
      if (message.user != 1) {
        continue;
      }
      if (message.id == entryId) {
        return true;
      }
      if (_canonicalAgentUserMessageId(message.id) != canonicalId) {
        continue;
      }
      if (normalizedText != null && normalizedText.isNotEmpty) {
        final existingText = (message.text ?? '').trim();
        if (existingText.isNotEmpty && existingText != normalizedText) {
          continue;
        }
      }
      if (createdAt != null) {
        final deltaMs = message.createAt
            .difference(createdAt)
            .inMilliseconds
            .abs();
        if (deltaMs > 1000) {
          continue;
        }
      }
      return true;
    }
    return false;
  }

  bool _preferAgentUserMessage(
    ChatMessageModel candidate,
    ChatMessageModel existing,
  ) {
    final candidateIsLocal = !candidate.id.endsWith('-ai-user');
    final existingIsLocal = !existing.id.endsWith('-ai-user');
    if (candidateIsLocal != existingIsLocal) {
      return candidateIsLocal;
    }
    return candidate.createAt.isAfter(existing.createAt);
  }

  String? _canonicalAgentUserMessageId(String rawId) {
    final id = rawId.trim();
    if (id.isEmpty) {
      return null;
    }
    if (id.endsWith('-ai-user')) {
      return '${id.substring(0, id.length - '-ai-user'.length)}-user';
    }
    if (id.endsWith('-user')) {
      return id;
    }
    return null;
  }

  int _findInsertIndexByCreatedAt(
    List<ChatMessageModel> messages,
    DateTime createdAt,
  ) {
    // runtime.messages 是降序（最新在 index 0），与 Kotlin sortForDisplay 的
    // .asReversed() 以及 ChatMessageList 用 timelineEntries.length-1-index 反向
    // 渲染的约定一致 —— 所有 agent 流事件也都是 runtime.messages.insert(0, ...)。
    // 因此从前往后找第一条 createAt 不晚于新消息的位置，插在它之前即可。
    for (var i = 0; i < messages.length; i++) {
      if (!messages[i].createAt.isAfter(createdAt)) {
        return i;
      }
    }
    return messages.length;
  }

  int? _asPositiveInt(dynamic raw) {
    final value = switch (raw) {
      int value => value,
      num value => value.toInt(),
      String value => int.tryParse(value.trim()),
      _ => null,
    };
    return value != null && value > 0 ? value : null;
  }

  String _runtimeModeFromConversationMode(String rawMode) {
    return switch (ConversationMode.fromStorageValue(rawMode)) {
      ConversationMode.openclaw => kChatRuntimeModeOpenClaw,
      ConversationMode.agent => kChatRuntimeModeAgent,
      _ => kChatRuntimeModeNormal,
    };
  }
}
