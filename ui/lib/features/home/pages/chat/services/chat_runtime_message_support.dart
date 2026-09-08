part of 'chat_conversation_runtime_coordinator.dart';

extension _ChatRuntimeMessageSupport on ChatConversationRuntimeCoordinator {
  void _applyPromptTokenUsageUpdate(
    ChatConversationRuntimeState runtime, {
    int? latestPromptTokens,
    int? promptTokenThreshold,
  }) {
    final conversation = runtime.conversation;
    if (conversation == null ||
        (latestPromptTokens == null && promptTokenThreshold == null)) {
      return;
    }
    final now = DateTime.now().millisecondsSinceEpoch;
    runtime.conversation = conversation.copyWith(
      latestPromptTokens: latestPromptTokens ?? conversation.latestPromptTokens,
      promptTokenThreshold:
          promptTokenThreshold ?? conversation.promptTokenThreshold,
      latestPromptTokensUpdatedAt: latestPromptTokens != null
          ? now
          : conversation.latestPromptTokensUpdatedAt,
    );
  }

  ChatConversationRuntimeState? _runtimeForTask(String taskId) {
    final binding = _taskBindings[taskId];
    if (binding == null) return null;
    return ensureRuntime(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _removeLatestLoadingIfExists(ChatConversationRuntimeState runtime) {
    if (runtime.messages.isNotEmpty && runtime.messages[0].isLoading) {
      runtime.messages.removeAt(0);
    }
  }

  void _updateOrAddAiMessage(
    ChatConversationRuntimeState runtime,
    String taskId,
    String text,
    bool isError, {
    bool renderMarkdown = true,
    int? markdownRenderedLength,
    bool isStreamingMarkdown = false,
    bool isSummarizing = false,
    List<Map<String, dynamic>> attachments = const [],
    double? prefillTokensPerSecond,
    double? decodeTokensPerSecond,
    String? reasoningContent,
  }) {
    final index = runtime.messages.indexWhere((msg) => msg.id == taskId);
    if (index == -1) {
      final content = <String, dynamic>{
        'text': text,
        'id': taskId,
        'renderMarkdown': renderMarkdown,
      };
      if (markdownRenderedLength != null) {
        content['markdownRenderedLength'] = markdownRenderedLength;
      } else {
        content.remove('markdownRenderedLength');
      }
      if (isStreamingMarkdown) {
        content['isStreamingMarkdown'] = true;
      }
      if (prefillTokensPerSecond != null) {
        content['prefillTokensPerSecond'] = prefillTokensPerSecond;
      }
      if (decodeTokensPerSecond != null) {
        content['decodeTokensPerSecond'] = decodeTokensPerSecond;
      }
      if (attachments.isNotEmpty) {
        content['attachments'] = attachments;
      }
      runtime.messages.insert(
        0,
        ChatMessageModel(
          id: taskId,
          type: 1,
          user: 2,
          content: content,
          isLoading: false,
          isError: isError,
          isSummarizing: isSummarizing,
          reasoningContent: _normalizeReasoningContent(reasoningContent),
        ),
      );
      return;
    }

    final existing = runtime.messages[index];
    final content = Map<String, dynamic>.from(existing.content ?? {});
    final existingText = content['text'] as String? ?? '';
    content['text'] = text.isNotEmpty ? text : existingText;
    content['renderMarkdown'] = renderMarkdown;
    if (markdownRenderedLength != null) {
      content['markdownRenderedLength'] = markdownRenderedLength;
    } else {
      content.remove('markdownRenderedLength');
    }
    if (isStreamingMarkdown) {
      content['isStreamingMarkdown'] = true;
    } else {
      content.remove('isStreamingMarkdown');
    }
    if (prefillTokensPerSecond != null) {
      content['prefillTokensPerSecond'] = prefillTokensPerSecond;
    }
    if (decodeTokensPerSecond != null) {
      content['decodeTokensPerSecond'] = decodeTokensPerSecond;
    }
    final mergedAttachments = _mergeAttachments(
      _parseAttachments(content['attachments']),
      attachments,
    );
    if (mergedAttachments.isNotEmpty) {
      content['attachments'] = mergedAttachments;
    }
    runtime.messages[index] = existing.copyWith(
      content: content,
      isLoading: false,
      isError: isError,
      isSummarizing: isSummarizing,
      reasoningContent:
          _normalizeReasoningContent(reasoningContent) ??
          existing.reasoningContent,
    );
  }

  // 将 AI 文本消息里的 URL 同步成 content.linkPreviews，UI 只负责展示该字段。
  void _syncMessageLinkPreviews(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    final index = runtime.messages.indexWhere((msg) => msg.id == taskId);
    if (index == -1) {
      return;
    }

    final message = runtime.messages[index];
    if (message.type != 1 ||
        message.user != 2 ||
        message.isLoading ||
        message.isError ||
        message.isSummarizing) {
      return;
    }

    final content = Map<String, dynamic>.from(message.content ?? const {});
    final nextPreviews = LinkPreviewService.instance.reconcilePreviewMaps(
      text: message.text ?? '',
      existing: content['linkPreviews'],
    );
    final currentPreviews = content['linkPreviews'];
    var didUpdate = false;
    if (!_previewMapListsEqual(currentPreviews, nextPreviews)) {
      if (nextPreviews.isEmpty) {
        content.remove('linkPreviews');
      } else {
        content['linkPreviews'] = nextPreviews;
      }
      runtime.messages[index] = message.copyWith(content: content);
      didUpdate = true;
    }
    if (didUpdate &&
        !isEphemeralRuntime(
          conversationId: runtime.conversationId,
          mode: runtime.mode,
        ) &&
        nextPreviews.any(
          (item) =>
              ChatLinkPreview.fromJson(item).status !=
              ChatLinkPreview.statusLoading,
        )) {
      schedulePersistRuntimeConversation(
        conversationId: runtime.conversationId,
        mode: runtime.mode,
        persistMessages: true,
      );
    }

    // 先写 loading 占位，真实网页信息抓取完成后再局部回填。
    for (final previewMap in nextPreviews) {
      final preview = ChatLinkPreview.fromJson(previewMap);
      if (preview.status != ChatLinkPreview.statusLoading ||
          preview.url.isEmpty) {
        continue;
      }
      unawaited(
        _resolveMessageLinkPreview(
          conversationId: runtime.conversationId,
          mode: runtime.mode,
          taskId: taskId,
          url: preview.url,
        ),
      );
    }
  }

  Future<void> _resolveMessageLinkPreview({
    required int conversationId,
    required String mode,
    required String taskId,
    required String url,
  }) async {
    final resolved = await LinkPreviewService.instance.loadPreview(url);
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null) {
      return;
    }
    final index = runtime.messages.indexWhere((msg) => msg.id == taskId);
    if (index == -1) {
      return;
    }

    final message = runtime.messages[index];
    final content = Map<String, dynamic>.from(message.content ?? const {});
    final rawPreviews = content['linkPreviews'];
    if (rawPreviews is! List) {
      return;
    }

    // 只替换仍处于 loading 的同一 URL，避免覆盖历史 ready/failed 结果。
    var changed = false;
    final updatedPreviews = rawPreviews
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item.cast<String, dynamic>()))
        .map((previewMap) {
          final preview = ChatLinkPreview.fromJson(previewMap);
          if (preview.url != url ||
              preview.status != ChatLinkPreview.statusLoading) {
            return previewMap;
          }
          changed = true;
          return resolved.toJson();
        })
        .toList();
    if (!changed) {
      return;
    }

    content['linkPreviews'] = updatedPreviews;
    runtime.messages[index] = message.copyWith(content: content);
    _notifyRuntimeListeners();
    if (isEphemeralRuntime(conversationId: conversationId, mode: mode)) {
      return;
    }
    await persistRuntimeConversation(
      conversationId: conversationId,
      mode: mode,
      persistMessages: true,
    );
  }

  bool _previewMapListsEqual(dynamic left, List<Map<String, dynamic>> right) {
    if (left is! List) {
      return right.isEmpty;
    }
    final normalizedLeft = left
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item.cast<String, dynamic>()))
        .toList();
    if (normalizedLeft.length != right.length) {
      return false;
    }
    for (var index = 0; index < normalizedLeft.length; index += 1) {
      if (!_previewMapEquals(normalizedLeft[index], right[index])) {
        return false;
      }
    }
    return true;
  }

  bool _previewMapEquals(
    Map<String, dynamic> left,
    Map<String, dynamic> right,
  ) {
    return left['url'] == right['url'] &&
        left['domain'] == right['domain'] &&
        left['siteName'] == right['siteName'] &&
        left['title'] == right['title'] &&
        left['description'] == right['description'] &&
        left['imageUrl'] == right['imageUrl'] &&
        left['status'] == right['status'];
  }

  List<Map<String, dynamic>> _parseAttachments(dynamic raw) {
    if (raw is! List) return const [];
    return raw
        .whereType<Map>()
        .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
        .toList();
  }

  List<Map<String, dynamic>> _mergeAttachments(
    List<Map<String, dynamic>> previous,
    List<Map<String, dynamic>> latest,
  ) {
    if (previous.isEmpty) return latest;
    if (latest.isEmpty) return previous;
    final merged = <Map<String, dynamic>>[];
    final seen = <String>{};

    void addAll(List<Map<String, dynamic>> source) {
      for (final item in source) {
        final key = _attachmentIdentity(item);
        if (!seen.add(key)) continue;
        merged.add(item);
      }
    }

    addAll(previous);
    addAll(latest);
    return merged;
  }

  String _attachmentIdentity(Map<String, dynamic> item) {
    final id = (item['id'] as String? ?? '').trim();
    if (id.isNotEmpty) return id;
    final path = (item['path'] as String? ?? '').trim();
    if (path.isNotEmpty) return path;
    final url = (item['url'] as String? ?? '').trim();
    if (url.isNotEmpty) return url;
    final name = (item['name'] as String? ?? '').trim();
    final fileName = (item['fileName'] as String? ?? '').trim();
    return '$name|$fileName|${item['size']}';
  }
}
