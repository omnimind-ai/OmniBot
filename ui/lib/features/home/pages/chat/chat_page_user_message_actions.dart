part of 'chat_page.dart';

extension _ChatPageUserMessageActions on _ChatPageStateBase {
  Future<void> _handleContextUsageRingLongPress() async {
    final conversation = _currentConversation;
    if (conversation == null || conversation.id <= 0) {
      _showSnackBar(
        LegacyTextLocalizer.isEnglish
            ? 'No adjustable context threshold for this conversation'
            : '当前对话还没有可调整的上下文阈值',
      );
      return;
    }

    final conversationMode = _activeMode;
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useRootNavigator: false,
      backgroundColor: Colors.transparent,
      builder: (_) => _ContextThresholdSheet(
        initialThreshold: conversation.promptTokenThreshold,
        currentUsageTokens: conversation.latestPromptTokens,
        onThresholdSaved: (nextThreshold) async {
          final trackedConversation = _modeState(
            conversationMode,
          ).currentConversation;
          final activeConversation = _currentConversation;
          final ConversationModel latestConversation;
          if (trackedConversation?.id == conversation.id) {
            latestConversation = trackedConversation!;
          } else if (activeConversation?.id == conversation.id) {
            latestConversation = activeConversation!;
          } else {
            latestConversation = conversation;
          }
          if (nextThreshold == latestConversation.promptTokenThreshold) {
            return true;
          }

          final success =
              await ConversationService.updateConversationPromptTokenThreshold(
                conversationId: conversation.id,
                promptTokenThreshold: nextThreshold,
              );
          if (!mounted || !success) {
            return success;
          }

          final modelId =
              _activeConversationModelOverrideSelection?.modelId ??
              _activeDispatchSceneSelection?.modelId;
          if (modelId != null && modelId.isNotEmpty) {
            await StorageService.setManualModelContextThreshold(
              modelId,
              nextThreshold,
            );
          }

          final updatedConversation = latestConversation.copyWith(
            promptTokenThreshold: nextThreshold,
          );
          setState(() {
            if ((_modeState(conversationMode).currentConversation?.id ?? 0) ==
                conversation.id) {
              _modeState(conversationMode).currentConversation =
                  updatedConversation;
            }
            if ((_currentConversation?.id ?? 0) == conversation.id) {
              _currentConversation = updatedConversation;
            }
          });
          if ((_modeState(conversationMode).currentConversationId ?? 0) ==
              conversation.id) {
            _syncRuntimeSnapshotForMode(
              conversationMode,
              conversation: updatedConversation,
            );
          }
          return true;
        },
      ),
    );
  }

  Future<void> _handleUserMessageLongPressStart(
    ChatMessageModel message,
    LongPressStartDetails details, {
    bool allowConversationActions = true,
  }) async {
    final text = (message.text ?? '').trim();
    final hasAttachments = _extractRetryAttachments(message).isNotEmpty;
    if (text.isEmpty && !hasAttachments) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'No actionable text in this user message'
            : '这条用户消息没有可操作的文本',
        type: ToastType.warning,
      );
      return;
    }

    final action = await _showUserMessageQuickMenu(
      details.globalPosition,
      showEditAction: allowConversationActions && _canEditUserMessage(message),
      showRetryAction:
          allowConversationActions && _canRetryUserMessage(message),
    );
    if (!mounted || action == null) return;

    switch (action) {
      case _UserMessageQuickAction.edit:
        _startEditingLatestUserMessage(message);
        return;
      case _UserMessageQuickAction.copy:
        if (text.isEmpty) {
          showToast(
            LegacyTextLocalizer.isEnglish
                ? 'No text to copy in this user message'
                : '这条用户消息没有可复制的文本',
            type: ToastType.warning,
          );
          return;
        }
        await _copyUserMessageText(text);
        return;
      case _UserMessageQuickAction.retry:
        await _retryUserMessage(message);
        return;
    }
  }

  Future<_UserMessageQuickAction?> _showUserMessageQuickMenu(
    Offset globalPosition, {
    required bool showEditAction,
    required bool showRetryAction,
  }) {
    final anchor = glassPopupAnchorFromGlobalPosition(context, globalPosition);
    if (anchor == null) {
      return Future<_UserMessageQuickAction?>.value();
    }
    return showGlassPopup<_UserMessageQuickAction>(
      context: context,
      anchor: anchor,
      verticalGap: 10,
      instant: true,
      horizontalPlacement: GlassPopupHorizontalPlacement.centerOnAnchor,
      child: _UserMessageQuickMenuContent(
        width: 188,
        showEditAction: showEditAction,
        showRetryAction: showRetryAction,
      ),
    );
  }

  bool _canRetryUserMessage(ChatMessageModel message) {
    // A retry creates a new ACP prompt.  While the current prompt is still
    // active, session/cancel is only a notification: its acknowledgement is
    // not the current turn's terminal response.  Do not remove or replace
    // visible items before that official terminal transition arrives.
    return !_isAiResponding && _isLatestUserMessage(message);
  }

  bool _canEditUserMessage(ChatMessageModel message) {
    return !_isAiResponding && _isLatestUserMessage(message);
  }

  bool _isLatestUserMessage(ChatMessageModel message) {
    if (message.user != 1) return false;
    for (final item in _messages) {
      if (item.user != 1) continue;
      return item.id == message.id;
    }
    return false;
  }

  ChatMessageModel? _currentEditingUserMessage() {
    final editingMessageId = _editingUserMessageId;
    if (editingMessageId == null) return null;
    for (final message in _messages) {
      if (message.id == editingMessageId && message.user == 1) {
        return message;
      }
    }
    return null;
  }

  bool get _editingUserMessageHasAttachments {
    final message = _currentEditingUserMessage();
    return message != null && _extractRetryAttachments(message).isNotEmpty;
  }

  Future<void> _handleComposerSendMessage({String? text}) async {
    if (_editingUserMessageId != null) {
      final message = _currentEditingUserMessage();
      if (message == null) {
        _stopUserMessageEditing();
        return;
      }
      await _saveAndResendEditedUserMessage(message);
      return;
    }
    final messageText = (text ?? _messageController.text).trim();
    if (messageText.isNotEmpty &&
        await _respondToPendingAgentUserInput(messageText)) {
      return;
    }
    await _sendMessage(text: text);
  }

  Future<bool> _respondToPendingAgentUserInput(String text) async {
    final card = _pendingAgentUserInputCard;
    if (card == null) {
      return false;
    }
    final requestId = card['requestId'];
    if (requestId == null) {
      return false;
    }
    if (_pendingAgentInputResponseInFlight) {
      return true;
    }
    final questionId = (card['questionId'] ?? 'answer').toString();
    final agentId = card['agentId']?.toString().trim();
    final rawConversationId = card['conversationId'];
    final conversationId = rawConversationId is num
        ? rawConversationId.toInt()
        : int.tryParse(rawConversationId?.toString() ?? '');
    _pendingAgentInputResponseInFlight = true;
    try {
      if (card['structuredElicitation'] == true) {
        await AgentRuntimeService.respondToElicitation(
          requestId: requestId,
          content: _singleComposerElicitationContent(card, text),
          sessionId: card['sessionId']?.toString(),
          agentId: agentId,
          conversationId: conversationId,
        );
      } else {
        await AgentRuntimeService.respondToUserInput(
          requestId: requestId,
          questionId: questionId,
          answers: <String>[text],
          sessionId: card['sessionId']?.toString(),
          agentId: agentId,
          conversationId: conversationId,
        );
      }
      _markPendingAgentUserInputAnswered(card, text);
      if (mounted) {
        _messageController.clear();
        _modeState(_activeMode).draftMessage = '';
      }
      return true;
    } catch (_) {
      if (mounted) {
        showToast(
          LegacyTextLocalizer.isEnglish
              ? 'Unable to submit the Agent response'
              : '无法提交 Agent 的输入回复',
          type: ToastType.warning,
        );
      }
      return true;
    } finally {
      _pendingAgentInputResponseInFlight = false;
    }
  }

  Map<String, dynamic> _singleComposerElicitationContent(
    Map<String, dynamic> card,
    String text,
  ) {
    Map<String, dynamic> raw = const <String, dynamic>{};
    final rawJson = card['rawParamsJson']?.toString().trim() ?? '';
    if (rawJson.isNotEmpty) {
      try {
        final decoded = jsonDecode(rawJson);
        if (decoded is Map) {
          raw = decoded.map((key, value) => MapEntry(key.toString(), value));
        }
      } catch (_) {
        raw = const <String, dynamic>{};
      }
    }
    dynamic schema =
        raw['requestedSchema'] ??
        raw['requested_schema'] ??
        raw['schema'] ??
        raw['inputSchema'] ??
        raw['input_schema'];
    if (schema is String) {
      try {
        schema = jsonDecode(schema);
      } catch (_) {
        schema = null;
      }
    }
    if (schema is! Map) {
      for (final key in const <String>['request', 'elicitation', 'params']) {
        var nested = raw[key];
        if (nested is String) {
          try {
            nested = jsonDecode(nested);
          } catch (_) {
            nested = null;
          }
        }
        if (nested is! Map) continue;
        schema =
            nested['requestedSchema'] ??
            nested['requested_schema'] ??
            nested['schema'] ??
            nested['inputSchema'] ??
            nested['input_schema'];
        if (schema is String) {
          try {
            schema = jsonDecode(schema);
          } catch (_) {
            schema = null;
          }
        }
        if (schema is Map) break;
      }
    }
    final properties = schema is Map && schema['properties'] is Map
        ? (schema['properties'] as Map).map(
            (key, value) => MapEntry(key.toString(), value),
          )
        : const <String, dynamic>{};
    final required = schema is Map && schema['required'] is List
        ? (schema['required'] as List).map((value) => value.toString()).toList()
        : const <String>[];
    final fieldName = required.length == 1
        ? required.first
        : (properties.length == 1 ? properties.keys.first : null);
    if (fieldName == null || fieldName.isEmpty) {
      return <String, dynamic>{'answer': text};
    }
    final field = properties[fieldName];
    final type = field is Map
        ? (field['type'] ?? 'string').toString().toLowerCase()
        : 'string';
    final value = switch (type) {
      'integer' => int.tryParse(text) ?? text,
      'number' => double.tryParse(text) ?? text,
      'boolean' => text.toLowerCase() == 'true',
      'array' =>
        text
            .split(',')
            .map((item) => item.trim())
            .where((item) => item.isNotEmpty)
            .toList(growable: false),
      _ => text,
    };
    return <String, dynamic>{fieldName: value};
  }

  void _markPendingAgentUserInputAnswered(
    Map<String, dynamic> card,
    String answer,
  ) {
    final runtime = _activeRuntime;
    if (runtime == null) return;
    final requestId = card['requestId']?.toString();
    final sessionId = card['sessionId']?.toString().trim();
    final agentId = card['agentId']?.toString().trim();
    for (var index = 0; index < runtime.messages.length; index++) {
      final message = runtime.messages[index];
      final cardData = message.cardData;
      if (cardData == null || cardData['requestId']?.toString() != requestId) {
        continue;
      }
      if (sessionId != null &&
          sessionId.isNotEmpty &&
          cardData['sessionId'] != null &&
          cardData['sessionId']?.toString().trim() != sessionId) {
        continue;
      }
      if (agentId != null &&
          agentId.isNotEmpty &&
          cardData['agentId'] != null &&
          cardData['agentId']?.toString().trim() != agentId) {
        continue;
      }
      final nextCard = Map<String, dynamic>.from(cardData)
        ..['status'] = 'submitted'
        ..['submittedAnswers'] = <String>[answer];
      runtime.messages[index] = message.copyWith(
        content: <String, dynamic>{'cardData': nextCard, 'id': message.id},
      );
      return;
    }
  }

  void _startEditingLatestUserMessage(ChatMessageModel message) {
    if (!_isLatestUserMessage(message)) {
      showToast(
        'Only the latest user message can be edited',
        type: ToastType.warning,
      );
      return;
    }
    final originalText = message.text ?? '';
    _suppressNextOutsideTapKeyboardHide = true;
    _armComposerLiftIntent();
    setState(() {
      _editingUserMessageId = message.id;
      _modeState(_activeMode).draftMessage = originalText;
      _pendingAttachments.clear();
    });
    _messageController.value = TextEditingValue(
      text: originalText,
      selection: TextSelection.collapsed(offset: originalText.length),
    );
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _editingUserMessageId != message.id) return;
      _requestComposerFocus(showKeyboard: true);
    });
  }

  void _stopUserMessageEditing() {
    if (_editingUserMessageId == null) return;
    final mode = _activeMode;
    setState(() {
      _editingUserMessageId = null;
    });
    _modeState(mode).draftMessage = '';
    _messageController.clear();
  }

  Future<void> _saveAndResendEditedUserMessage(ChatMessageModel message) async {
    if (_editingUserMessageId != message.id) return;
    if (!_canEditUserMessage(message)) {
      _stopUserMessageEditing();
      showToast(
        _isAiResponding
            ? 'Wait for the current response to finish before editing'
            : 'Only the latest user message can be edited',
        type: ToastType.warning,
      );
      return;
    }

    final editedText = _messageController.text.trim();
    final attachments = _extractRetryAttachments(message);
    if (editedText.isEmpty && attachments.isEmpty) {
      showToast('No content to send after editing', type: ToastType.warning);
      return;
    }
    if (!await _ensureNormalChatModelConfigurationForSend()) {
      return;
    }

    if (!await _clearRetriedMessageRound(message)) return;
    if (!mounted) return;

    await _retryUserMessageText(editedText, attachments: attachments);
  }

  int _retryMessageRoundLength(
    ChatMessageModel message, {
    bool preserveUserMessage = false,
  }) {
    if (!_canRetryUserMessage(message)) return 0;
    return retriedMessageRoundRemovalCount(
      _messages,
      userMessageId: message.id,
      preserveUserMessage: preserveUserMessage,
    );
  }

  Future<bool> _clearRetriedMessageRound(
    ChatMessageModel message, {
    bool preserveUserMessage = false,
  }) async {
    if (_isAiResponding) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Wait for the current response to finish first'
            : '请先等待当前回复结束',
        type: ToastType.warning,
      );
      return false;
    }

    final removeCount = _retryMessageRoundLength(
      message,
      preserveUserMessage: preserveUserMessage,
    );
    if (removeCount <= 0) return false;

    final shouldClearEditState = _editingUserMessageId == message.id;
    setState(() {
      if (shouldClearEditState) {
        _editingUserMessageId = null;
      }
      _messages.removeRange(0, removeCount);
    });
    if (shouldClearEditState) {
      _modeState(_activeMode).draftMessage = '';
      _messageController.clear();
    }

    final conversationId = _currentConversationId;
    if (conversationId == null) return true;
    if (isEphemeralConversation(conversationId, activeConversationModeValue)) {
      return true;
    }

    await _runtimeCoordinator.persistConversationMessageSnapshot(
      conversationId: conversationId,
      mode: _modeKey(_activeMode),
      messages: List<ChatMessageModel>.from(_messages),
      conversation: _currentConversation,
    );
    return true;
  }

  Future<void> _copyUserMessageText(String text) async {
    final success = await AssistsMessageService.copyToClipboard(text);
    if (!mounted) return;
    showToast(
      success
          ? (LegacyTextLocalizer.isEnglish ? 'Message copied' : '已复制消息内容')
          : (LegacyTextLocalizer.isEnglish ? 'Copy failed' : '复制失败'),
      type: success ? ToastType.success : ToastType.error,
    );
  }

  Future<void> _retryUserMessage(ChatMessageModel message) async {
    final text = (message.text ?? '').trim();
    final attachments = _extractRetryAttachments(message);
    if (text.isEmpty && attachments.isEmpty) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'No content to retry in this user message'
            : '这条用户消息没有可重试的内容',
        type: ToastType.warning,
      );
      return;
    }
    if (!_canRetryUserMessage(message)) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? (_isAiResponding
                  ? 'Wait for the current response to finish first'
                  : 'Only the latest user message can be retried')
            : (_isAiResponding ? '请先等待当前回复结束' : '只有最新一条用户消息支持重试'),
        type: ToastType.warning,
      );
      return;
    }
    if (!await _ensureNormalChatModelConfigurationForSend()) {
      return;
    }

    if (text.isNotEmpty) {
      await AssistsMessageService.copyToClipboard(text);
      if (!mounted) return;
    }

    if (_editingUserMessageId == message.id) {
      _stopUserMessageEditing();
      if (!mounted) return;
    }

    if (!await _clearRetriedMessageRound(message, preserveUserMessage: true)) {
      return;
    }
    if (!mounted) return;

    await _retryUserMessageText(
      text,
      attachments: attachments,
      retainedUserMessageId: message.id,
    );
    if (!mounted) return;
  }

  Future<void> _retryFailedAgentTurn(ChatMessageModel message) async {
    final taskId = _resolveRetryableAgentTaskId(message);
    if (taskId == null) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'This reply can no longer be retried'
            : '这条回复当前无法继续重试',
        type: ToastType.warning,
      );
      return;
    }
    if (_pendingManualAgentRetryTaskIds.contains(taskId) ||
        message.content?['agentRetrying'] == true) {
      return;
    }
    if (_isAiResponding) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Wait for the current response to finish first'
            : '请先等待当前回复结束',
        type: ToastType.warning,
      );
      return;
    }

    final userMessage = _agentPromptForFailedTurn(message);
    if (userMessage == null) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'The original user message is unavailable for retry'
            : '找不到原始用户消息，无法重试',
        type: ToastType.warning,
      );
      return;
    }
    _pendingManualAgentRetryTaskIds.add(taskId);
    try {
      // Retrying is an explicit user action, so it follows the ordinary send
      // path and receives a fresh ACP turn id. Keep the original user and
      // failed-assistant items intact rather than replacing either with a
      // local "retrying" presentation.
      await _retryUserMessageText(
        userMessage.text ?? '',
        attachments: _extractRetryAttachments(userMessage),
        retainedUserMessageId: userMessage.id,
      );
    } finally {
      _pendingManualAgentRetryTaskIds.remove(taskId);
    }
  }

  List<Map<String, dynamic>> _extractRetryAttachments(
    ChatMessageModel message,
  ) {
    final raw = message.content?['attachments'];
    if (raw is! List) return const [];
    return raw
        .whereType<Map>()
        .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
        .toList();
  }

  ChatMessageModel? _agentPromptForFailedTurn(ChatMessageModel message) {
    final messageIndex = _messages.indexWhere((item) => item.id == message.id);
    if (messageIndex < 0) return null;
    for (var index = messageIndex - 1; index >= 0; index--) {
      final candidate = _messages[index];
      if (candidate.user == 1) return candidate;
    }
    return null;
  }

  String? _resolveRetryableAgentTaskId(ChatMessageModel message) {
    if (message.content?['agentRetryable'] != true) {
      return null;
    }
    return _resolveAgentTaskId(message);
  }

  String? _resolveAgentTaskId(ChatMessageModel message) {
    final contentTaskId = (message.content?['agentTaskId'] ?? '')
        .toString()
        .trim();
    if (contentTaskId.isNotEmpty) {
      return contentTaskId;
    }
    final streamTaskId = (message.streamMeta?['parentTaskId'] ?? '')
        .toString()
        .trim();
    if (streamTaskId.isNotEmpty) {
      return streamTaskId;
    }
    return null;
  }
}
