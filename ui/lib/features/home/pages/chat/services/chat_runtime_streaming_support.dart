part of 'chat_conversation_runtime_coordinator.dart';

extension _ChatRuntimeStreamingSupport on ChatConversationRuntimeCoordinator {
  String _streamingTextBatchKey(String taskId, _StreamingTextStreamKind kind) =>
      '${kind.name}:$taskId';

  _StreamingTextBatchState? _streamingTextBatchFor(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind,
  ) {
    return runtime._streamingTextBatches[_streamingTextBatchKey(taskId, kind)];
  }

  _StreamingTextBatchState _ensureStreamingTextBatch(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind, {
    required String initialLatestText,
    required String initialFlushedText,
  }) {
    final key = _streamingTextBatchKey(taskId, kind);
    return runtime._streamingTextBatches.putIfAbsent(
      key,
      () => _StreamingTextBatchState(
        taskId: taskId,
        kind: kind,
        latestText: initialLatestText,
        lastFlushedText: initialFlushedText,
      ),
    );
  }

  void _clearStreamingTextBatch(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind,
  ) {
    runtime._streamingTextBatches.remove(_streamingTextBatchKey(taskId, kind));
  }

  void _clearStreamingTextBatchesForTask(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    runtime._streamingTextBatches.removeWhere(
      (_, batch) => batch.taskId == taskId,
    );
  }

  void _flushRuntimeStreamingText(
    ChatConversationRuntimeState runtime, {
    bool schedulePersistence = false,
  }) {
    final taskIds = runtime._streamingTextBatches.values
        .map((batch) => batch.taskId)
        .toSet()
        .toList(growable: false);
    for (final taskId in taskIds) {
      _flushStreamingTextForTask(
        runtime,
        taskId,
        schedulePersistence: schedulePersistence,
      );
    }
  }

  void _flushStreamingTextForTask(
    ChatConversationRuntimeState runtime,
    String taskId, {
    bool schedulePersistence = false,
  }) {
    _flushThinkingBatch(
      runtime,
      taskId,
      _StreamingTextStreamKind.pureChatThinking,
      schedulePersistence: schedulePersistence,
    );
    _flushThinkingBatch(
      runtime,
      taskId,
      _StreamingTextStreamKind.agentThinking,
      schedulePersistence: schedulePersistence,
    );
    _flushPureChatReplyBatch(
      runtime,
      taskId,
      schedulePersistence: schedulePersistence,
    );
    _flushAgentReplyBatch(
      runtime,
      taskId,
      schedulePersistence: schedulePersistence,
    );
  }

  bool _stageStreamingTextBatch(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind, {
    required String nextText,
    required String initialLatestText,
    required String initialFlushedText,
  }) {
    if (nextText.isEmpty) {
      return false;
    }
    final state = _ensureStreamingTextBatch(
      runtime,
      taskId,
      kind,
      initialLatestText: initialLatestText,
      initialFlushedText: initialFlushedText,
    );
    if (nextText == state.latestText) {
      return state.reachedFlushThreshold;
    }
    state.stage(nextText);
    return state.reachedFlushThreshold || state.containsNewlineSinceFlush;
  }

  String _visiblePureChatReplyText(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    final index = runtime.messages.indexWhere(
      (message) => message.id == taskId,
    );
    if (index == -1) {
      return '';
    }
    return (runtime.messages[index].content?['text'] as String? ?? '');
  }

  String? _latestAgentTextMessageId(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    String? result;
    var maxSequence = 0;
    for (final message in runtime.messages) {
      final sequence = _agentTextMessageSequence(message.id, taskId);
      if (sequence <= maxSequence) {
        continue;
      }
      maxSequence = sequence;
      result = message.id;
    }
    return result;
  }

  String _visibleAgentReplyText(
    ChatConversationRuntimeState runtime,
    String taskId, {
    String? messageId,
  }) {
    final resolvedMessageId =
        messageId ??
        _resolvePendingAgentTextMessageId(runtime, taskId) ??
        _latestAgentTextMessageId(runtime, taskId);
    if (resolvedMessageId == null) {
      return '';
    }
    final index = runtime.messages.indexWhere(
      (message) => message.id == resolvedMessageId,
    );
    if (index == -1) {
      return '';
    }
    return (runtime.messages[index].content?['text'] as String? ?? '');
  }

  String _visibleThinkingText(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    final thinkingCardId = _resolveThinkingCardId(runtime, taskId);
    if (thinkingCardId == null) {
      return runtime.deepThinkingContent;
    }
    final index = runtime.messages.indexWhere(
      (message) => message.id == thinkingCardId,
    );
    if (index == -1) {
      return runtime.deepThinkingContent;
    }
    return (runtime.messages[index].cardData?['thinkingContent'] as String? ??
            runtime.deepThinkingContent)
        .toString();
  }

  /// 返回已完成 Markdown 渲染的文本长度。
  ///
  /// - 无待刷新数据时返回 `null`（表示全量 Markdown 渲染）
  /// - 有待刷新数据时返回上次 flush 的文本长度，前端据此分段渲染
  int? _markdownRenderedLengthForBatch(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind,
  ) {
    final batch = _streamingTextBatchFor(runtime, taskId, kind);
    if (batch == null || !batch.hasPendingFlush) {
      return null;
    }
    return batch.lastFlushedText.length;
  }

  bool _applyPureChatReplyUpdate(
    ChatConversationRuntimeState runtime,
    String taskId, {
    required String text,
    required bool isError,
    bool renderMarkdown = true,
    int? markdownRenderedLength,
    bool isStreamingMarkdown = false,
    bool isSummarizing = false,
    List<Map<String, dynamic>> attachments = const <Map<String, dynamic>>[],
    double? prefillTokensPerSecond,
    double? decodeTokensPerSecond,
    bool schedulePersistence = false,
  }) {
    final hasExistingMessage = runtime.messages.any(
      (message) => message.id == taskId,
    );
    final hasPerformanceMetrics =
        prefillTokensPerSecond != null || decodeTokensPerSecond != null;
    final shouldWrite =
        isError ||
        isSummarizing ||
        text.isNotEmpty ||
        attachments.isNotEmpty ||
        (hasPerformanceMetrics && hasExistingMessage);
    if (!shouldWrite) {
      return false;
    }

    _removeLatestLoadingIfExists(runtime);
    _removeOpenClawWaitingCard(runtime, taskId);
    final reasoningContent =
        _normalizeReasoningContent(runtime.currentThinkingMessages[taskId]) ??
        _normalizeReasoningContent(runtime.deepThinkingContent);
    _updateOrAddAiMessage(
      runtime,
      taskId,
      text,
      isError,
      isSummarizing: isSummarizing,
      renderMarkdown: renderMarkdown,
      markdownRenderedLength: markdownRenderedLength,
      isStreamingMarkdown: isStreamingMarkdown,
      attachments: attachments,
      prefillTokensPerSecond: prefillTokensPerSecond,
      decodeTokensPerSecond: decodeTokensPerSecond,
      reasoningContent: reasoningContent,
    );
    if (schedulePersistence) {
      schedulePersistRuntimeConversation(
        conversationId: runtime.conversationId,
        mode: runtime.mode,
      );
    }
    return true;
  }

  bool _flushPureChatReplyBatch(
    ChatConversationRuntimeState runtime,
    String taskId, {
    bool isFinal = false,
    bool schedulePersistence = false,
  }) {
    final batch = _streamingTextBatchFor(
      runtime,
      taskId,
      _StreamingTextStreamKind.pureChatReply,
    );
    if (batch == null || !batch.hasPendingFlush) {
      return false;
    }
    final visibleText = runtime.currentAiMessages[taskId] ?? batch.latestText;
    batch.markFlushed();
    return _applyPureChatReplyUpdate(
      runtime,
      taskId,
      text: visibleText,
      isError: false,
      renderMarkdown: true,
      isStreamingMarkdown: !isFinal,
      schedulePersistence: schedulePersistence,
    );
  }

  void _upsertAgentReplyMessage(
    ChatConversationRuntimeState runtime,
    String messageId,
    String text, {
    bool renderMarkdown = true,
    int? markdownRenderedLength,
    bool isFinal = false,
    bool isError = false,
    Map<String, dynamic>? streamMeta,
    Map<String, dynamic>? turnUsage,
    double? prefillTokensPerSecond,
    double? decodeTokensPerSecond,
    String? reasoningContent,
  }) {
    final index = runtime.messages.indexWhere(
      (message) => message.id == messageId,
    );
    final existingStreamMeta = index == -1
        ? null
        : runtime.messages[index].streamMeta;
    final resolvedStreamMeta = ensureAgentStreamMessageMeta(
      <String, dynamic>{...?existingStreamMeta, ...?streamMeta},
      entryId: messageId,
      isFinal: isFinal,
    );
    if (index == -1) {
      final content = <String, dynamic>{
        'text': text,
        'id': messageId,
        'renderMarkdown': renderMarkdown,
        if (isFinal && prefillTokensPerSecond != null)
          'prefillTokensPerSecond': prefillTokensPerSecond,
        if (isFinal && decodeTokensPerSecond != null)
          'decodeTokensPerSecond': decodeTokensPerSecond,
      };
      if (markdownRenderedLength != null) {
        content['markdownRenderedLength'] = markdownRenderedLength;
      }
      _clearAgentRetryPresentation(content);
      runtime.messages.insert(
        0,
        ChatMessageModel(
          id: messageId,
          type: 1,
          user: 2,
          content: content,
          isError: isError,
          streamMeta: resolvedStreamMeta,
          turnUsage: turnUsage,
          reasoningContent: _normalizeReasoningContent(reasoningContent),
        ),
      );
      return;
    }

    final existing = runtime.messages[index];
    final content = Map<String, dynamic>.from(existing.content ?? const {});
    final currentText = (content['text'] ?? '').toString();
    content['text'] = text.isNotEmpty ? text : currentText;
    content['renderMarkdown'] = renderMarkdown;
    if (markdownRenderedLength != null) {
      content['markdownRenderedLength'] = markdownRenderedLength;
    } else {
      content.remove('markdownRenderedLength');
    }
    if (isFinal && prefillTokensPerSecond != null) {
      content['prefillTokensPerSecond'] = prefillTokensPerSecond;
    }
    if (isFinal && decodeTokensPerSecond != null) {
      content['decodeTokensPerSecond'] = decodeTokensPerSecond;
    }
    _clearAgentRetryPresentation(content);
    runtime.messages[index] = existing.copyWith(
      content: content,
      isError: isError,
      streamMeta: ensureAgentStreamMessageMeta(
        resolvedStreamMeta ?? existing.streamMeta,
        entryId: messageId,
        isFinal: isFinal,
      ),
      turnUsage: turnUsage ?? existing.turnUsage,
      reasoningContent:
          _normalizeReasoningContent(reasoningContent) ??
          existing.reasoningContent,
    );
  }

  bool _flushAgentReplyBatch(
    ChatConversationRuntimeState runtime,
    String taskId, {
    bool isFinal = false,
    bool schedulePersistence = false,
    double? prefillTokensPerSecond,
    double? decodeTokensPerSecond,
  }) {
    final batch = _streamingTextBatchFor(
      runtime,
      taskId,
      _StreamingTextStreamKind.agentReply,
    );
    final messageId =
        _resolvePendingAgentTextMessageId(runtime, taskId) ??
        _latestAgentTextMessageId(runtime, taskId) ??
        _nextAgentTextMessageId(runtime, taskId);
    final text =
        batch?.latestText ??
        _visibleAgentReplyText(runtime, taskId, messageId: messageId);
    final hasPendingFlush = batch?.hasPendingFlush ?? false;
    final hasPerformanceMetrics =
        prefillTokensPerSecond != null || decodeTokensPerSecond != null;
    final hasExistingMessage = runtime.messages.any(
      (message) => message.id == messageId,
    );
    final shouldWrite =
        hasPendingFlush ||
        (text.isNotEmpty && !hasExistingMessage) ||
        (hasPerformanceMetrics && hasExistingMessage);
    if (shouldWrite) {
      _upsertAgentReplyMessage(
        runtime,
        messageId,
        text,
        renderMarkdown: true,
        isFinal: isFinal,
        streamMeta: <String, dynamic>{
          'kind': 'text_snapshot',
          'parentTaskId': taskId,
        },
        prefillTokensPerSecond: prefillTokensPerSecond,
        decodeTokensPerSecond: decodeTokensPerSecond,
        reasoningContent: runtime.currentThinkingMessages[taskId],
      );
    }
    if (batch != null && (hasPendingFlush || isFinal)) {
      batch.markFlushed();
    }
    if (schedulePersistence) {
      schedulePersistRuntimeConversation(
        conversationId: runtime.conversationId,
        mode: runtime.mode,
      );
    }
    if (isFinal) {
      _clearStreamingTextBatch(
        runtime,
        taskId,
        _StreamingTextStreamKind.agentReply,
      );
    }
    return shouldWrite;
  }

  bool _flushThinkingBatch(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind, {
    bool schedulePersistence = false,
  }) {
    final batch = _streamingTextBatchFor(runtime, taskId, kind);
    if (batch == null || !batch.hasPendingFlush) {
      return false;
    }
    final binding =
        _taskBindings[taskId] ??
        _TaskBinding(
          conversationId: runtime.conversationId,
          mode: runtime.mode,
        );
    final thinking =
        runtime.currentThinkingMessages[taskId] ?? batch.latestText;
    if (thinking.isNotEmpty) {
      _applyThinkingUpdate(
        runtime,
        binding,
        taskId,
        thinking,
        notifyAfterUpdate: false,
        schedulePersistence: false,
      );
    }
    batch.markFlushed();
    if (schedulePersistence) {
      schedulePersistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
      );
    }
    return thinking.isNotEmpty;
  }
}
