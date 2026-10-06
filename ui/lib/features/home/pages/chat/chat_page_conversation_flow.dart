part of 'chat_page.dart';

/// What the user submitted for one turn. The launcher sends exactly this,
/// never a re-read of "the newest user message" (5d-0 fix).
class _ChatTurnSubmission {
  const _ChatTurnSubmission({required this.text, required this.attachments});

  final String text;
  final List<Map<String, dynamic>> attachments;
}

mixin _ChatPageConversationFlowMixin on _ChatPageStateBase {
  void _persistDeepThinkingCardIfNeeded(ChatMessageModel message) {
    final conversationId = _currentConversationId;
    final cardData = message.cardData;
    if (conversationId == null ||
        isEphemeralConversation(conversationId, activeConversationModeValue) ||
        message.type != 2 ||
        cardData?['type'] != 'deep_thinking') {
      return;
    }
    unawaited(
      ConversationHistoryService.upsertConversationUiCard(
        conversationId,
        entryId: message.id,
        cardData: buildPersistentDeepThinkingCardData(
          Map<String, dynamic>.from(cardData!),
        ),
        createdAtMillis: message.createAt.millisecondsSinceEpoch,
        mode: activeConversationModeValue,
      ),
    );
  }

  @override
  void _syncRuntimeSnapshotForMode(
    ChatPageMode mode, {
    ConversationModel? conversation,
    List<ChatMessageModel>? messages,
    bool preserveLiveStreamingState = false,
  }) {
    final conversationId = _modeState(mode).currentConversationId;
    if (conversationId == null) return;
    final runtime = _runtimeCoordinator.runtimeFor(
      conversationId: conversationId,
      mode: _modeKey(mode),
    );
    _runtimeCoordinator.replaceConversationSnapshot(
      conversationId: conversationId,
      mode: _modeKey(mode),
      messages: List<ChatMessageModel>.from(
        messages ?? runtime?.messages ?? _modeState(mode).messages,
      ),
      conversation:
          conversation ??
          runtime?.conversation ??
          _modeState(mode).currentConversation,
      isAiResponding:
          runtime?.isAiResponding ??
          (mode == ChatPageMode.agent
              ? false
              : _modeState(mode).isAiResponding),
      isContextCompressing:
          runtime?.isContextCompressing ??
          (mode == ChatPageMode.agent
              ? false
              : _modeState(mode).isContextCompressing),
      isCheckingExecutableTask:
          runtime?.isCheckingExecutableTask ??
          (mode == ChatPageMode.agent
              ? false
              : _modeState(mode).isCheckingExecutableTask),
      currentAiMessages: Map<String, String>.from(
        runtime?.currentAiMessages ??
            (mode == ChatPageMode.agent
                ? const <String, String>{}
                : _modeState(mode).currentAiMessages),
      ),
      currentThinkingMessages: Map<String, String>.from(
        runtime?.currentThinkingMessages ?? const <String, String>{},
      ),
      deepThinkingContent:
          runtime?.deepThinkingContent ??
          (mode == ChatPageMode.agent
              ? ''
              : _modeState(mode).deepThinkingContent),
      isDeepThinking:
          runtime?.isDeepThinking ??
          (mode == ChatPageMode.agent
              ? false
              : _modeState(mode).isDeepThinking),
      currentDispatchTurnId:
          runtime?.currentDispatchTurnId ??
          (mode == ChatPageMode.agent
              ? null
              : _modeState(mode).currentDispatchTurnId),
      currentThinkingStage:
          runtime?.currentThinkingStage ??
          (mode == ChatPageMode.agent
              ? ThinkingStage.thinking.value
              : _modeState(mode).currentThinkingStage),
      isInputAreaVisible:
          runtime?.isInputAreaVisible ?? (_modeState(mode).isInputAreaVisible),
      isExecutingTask:
          runtime?.isExecutingTask ??
          (mode == ChatPageMode.agent
              ? false
              : _modeState(mode).isExecutingTask),
      lastAgentTurnId: runtime?.lastAgentTurnId,
      activeToolCardId: runtime?.activeToolCardId,
      activeThinkingCardId: runtime?.activeThinkingCardId,
      activeContextCompactionMarkerId: runtime?.activeContextCompactionMarkerId,
      pendingAgentTextTaskId: runtime?.pendingAgentTextTaskId,
      pendingThinkingRoundSplit: runtime?.pendingThinkingRoundSplit ?? false,
      toolCardSequence: runtime?.toolCardSequence ?? 0,
      thinkingRound: runtime?.thinkingRound ?? 0,
      chatIslandDisplayLayer:
          runtime?.chatIslandDisplayLayer ??
          (_modeState(mode).chatIslandDisplayLayer),
      lastAgentToolType:
          runtime?.lastAgentToolType ?? _modeState(mode).lastAgentToolType,
      browserSessionSnapshot:
          runtime?.browserSessionSnapshot ??
          _modeState(mode).browserSessionSnapshot,
      preserveLiveStreamingState: preserveLiveStreamingState,
    );
    _rememberRuntimeUiSnapshot(mode);
  }

  @override
  Future<void> _ensureActiveConversationReadyForStreaming() async {
    if (_currentConversationId == null) {
      await persistConversationSnapshot(
        generateSummary: false,
        markComplete: false,
        rethrowOnFailure: true,
        allowEmpty: true,
      );
    }
    if (_currentConversationId == null) {
      throw StateError('conversationId is not ready');
    }
    _syncRuntimeSnapshotForMode(_activeMode);
  }

  @override
  void _createThinkingCard(
    String taskID, {
    String? cardId,
    String? thinkingContent,
    bool? isLoading,
    int? stage,
    Map<String, dynamic>? streamMeta,
  }) {
    final loadingIndex = _messages.indexWhere((msg) => msg.id == taskID);
    if (loadingIndex != -1) {
      setState(() => _removeVisibleMessages(<String>[taskID]));
    }

    final startTime = DateTime.now().millisecondsSinceEpoch;
    final thinkingCardId = cardId ?? '$taskID-thinking';
    final cardData = {
      'type': 'deep_thinking',
      'isLoading': isLoading ?? _isDeepThinking,
      'thinkingContent': thinkingContent ?? '',
      'stage': stage ?? _currentThinkingStage,
      'taskID': taskID,
      'cardId': thinkingCardId,
      'startTime': startTime,
      'endTime': null,
    };

    setState(() {
      _removeVisibleMessages(<String>[thinkingCardId]);
      _insertVisibleMessage(
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
    });
  }

  @override
  void _updateThinkingCard(
    String taskID, {
    String? cardId,
    String? thinkingContent,
    bool? isLoading,
    int? stage,
    Map<String, dynamic>? streamMeta,
    bool lockCompleted = true,
  }) {
    final thinkingCardId = cardId ?? '$taskID-thinking';
    final index = _messages.indexWhere((msg) => msg.id == thinkingCardId);
    if (index == -1) return;

    setState(() {
      final existing = _messages[index];
      final content = Map<String, dynamic>.from(existing.content ?? {});
      final cardData = Map<String, dynamic>.from(content['cardData'] ?? {});

      final currentStage = cardData['stage'] as int? ?? 1;
      final targetStage = stage ?? _currentThinkingStage;
      final newStage = (lockCompleted && currentStage == 4) ? 4 : targetStage;

      final startTime = cardData['startTime'] as int?;
      int? endTime = cardData['endTime'] as int?;
      if (newStage == 4 && endTime == null) {
        endTime = DateTime.now().millisecondsSinceEpoch;
      }

      cardData['thinkingContent'] = thinkingContent ?? _deepThinkingContent;
      cardData['isLoading'] = isLoading ?? _isDeepThinking;
      cardData['stage'] = newStage;
      cardData['taskID'] = taskID;
      cardData['cardId'] = thinkingCardId;
      cardData['startTime'] = startTime;
      cardData['endTime'] = endTime;

      content['cardData'] = cardData;
      _replaceVisibleMessage(
        existing.id,
        existing.copyWith(
          content: content,
          streamMeta: ensureAgentStreamMessageMeta(
            streamMeta ?? existing.streamMeta,
            entryId: thinkingCardId,
          ),
        ),
      );
    });
  }

  @override
  Future<void> _pickAttachments() async {
    try {
      final files = await FilePicker.pickFiles(type: FileType.any);
      if (files.isEmpty || !mounted) return;

      setState(() {
        for (final file in files) {
          final metadata = pickedAttachmentMetadata(file);
          if (metadata == null) continue;
          final path = metadata.path;
          final exists = _pendingAttachments.any((item) => item.path == path);
          if (exists) continue;
          final displayName = (file.name.trim().isNotEmpty)
              ? file.name.trim()
              : _fileNameFromPath(path);
          final extension = (file.extension ?? '').toLowerCase();
          final mimeType = _mimeTypeFromExtension(path, extension: extension);
          final isImage = _isImageFilePath(path, mimeType: mimeType);
          _pendingAttachments.add(
            ChatInputAttachment(
              id: '${path}_${DateTime.now().microsecondsSinceEpoch}',
              name: displayName,
              path: path,
              size: metadata.size,
              mimeType: mimeType,
              isImage: isImage,
            ),
          );
        }
      });
    } catch (e) {
      _showSnackBar('添加附件失败：$e');
    }
  }

  @override
  void _removePendingAttachment(String id) {
    if (!mounted) return;
    setState(() {
      _pendingAttachments.removeWhere((item) => item.id == id);
    });
  }

  @override
  String _fileNameFromPath(String path) {
    final normalized = path.replaceAll('\\', '/');
    final segments = normalized.split('/');
    if (segments.isEmpty) return path;
    return segments.last.isEmpty ? path : segments.last;
  }

  @override
  bool _isImageFilePath(String path, {String? mimeType}) {
    final normalizedMime = mimeType?.trim().toLowerCase();
    if (normalizedMime != null && normalizedMime.startsWith('image/')) {
      return true;
    }
    final lowerPath = path.toLowerCase();
    return lowerPath.endsWith('.png') ||
        lowerPath.endsWith('.jpg') ||
        lowerPath.endsWith('.jpeg') ||
        lowerPath.endsWith('.webp') ||
        lowerPath.endsWith('.gif') ||
        lowerPath.endsWith('.bmp') ||
        lowerPath.endsWith('.heic') ||
        lowerPath.endsWith('.heif');
  }

  @override
  String? _mimeTypeFromExtension(String path, {String extension = ''}) {
    final ext = extension.isNotEmpty
        ? extension
        : _fileNameFromPath(path).split('.').last.toLowerCase();
    switch (ext) {
      case 'png':
        return 'image/png';
      case 'jpg':
      case 'jpeg':
        return 'image/jpeg';
      case 'gif':
        return 'image/gif';
      case 'webp':
        return 'image/webp';
      case 'bmp':
        return 'image/bmp';
      case 'heic':
        return 'image/heic';
      case 'heif':
        return 'image/heif';
      case 'pdf':
        return 'application/pdf';
      case 'txt':
        return 'text/plain';
      case 'md':
        return 'text/markdown';
      default:
        return null;
    }
  }

  @override
  void _showSnackBar(String message) {
    if (!mounted) return;
    final messenger = ScaffoldMessenger.maybeOf(context);
    messenger?.hideCurrentSnackBar();
    messenger?.showSnackBar(
      SnackBar(
        content: Text(message),
        duration: const Duration(milliseconds: 1200),
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  bool _hasConfiguredNormalChatProviderModel({
    List<ModelProviderProfileSummary>? profiles,
    Map<String, List<ProviderModelOption>>? modelOptionsByProfileId,
    List<SceneCatalogItem>? sceneCatalog,
  }) {
    final profileSource = profiles ?? _modelProviderProfiles;
    final optionsSource = modelOptionsByProfileId ?? _modelOptionsByProfileId;
    final catalogSource = sceneCatalog ?? _sceneCatalog;
    final configuredProfileIds = profileSource
        .where((profile) => profile.configured)
        .map((profile) => profile.id)
        .toSet();
    if (configuredProfileIds.isEmpty) {
      return false;
    }

    for (final scene in catalogSource) {
      final providerProfileId = scene.effectiveProviderProfileId.trim();
      if (!scene.providerConfigured ||
          !configuredProfileIds.contains(providerProfileId)) {
        continue;
      }
      final models =
          optionsSource[providerProfileId] ?? const <ProviderModelOption>[];
      if (models.any((model) => model.id == scene.effectiveModel)) {
        return true;
      }
    }

    final override = _activeConversationModelOverrideSelection;
    if (override == null ||
        !configuredProfileIds.contains(override.providerProfileId)) {
      return false;
    }
    return (optionsSource[override.providerProfileId] ??
            const <ProviderModelOption>[])
        .any((model) => model.id == override.modelId);
  }

  @override
  Future<bool> _ensureNormalChatModelConfigurationForSend() async {
    if (_activeMode != ChatPageMode.normal || _isOpenClawSurface) {
      return true;
    }
    // The durable scene binding is already sufficient for an ACP turn. Do
    // not block the first visible message on refreshing the whole Provider
    // catalog; the native boundary will report a stale/missing binding as a
    // typed error if it cannot use this selection.
    final persistedSelection =
        _activeConversationModelOverrideSelection ??
        _activeDispatchSceneSelection;
    if (persistedSelection != null &&
        persistedSelection.providerProfileId.trim().isNotEmpty &&
        persistedSelection.modelId.trim().isNotEmpty) {
      return true;
    }
    if (_hasConfiguredNormalChatProviderModel()) {
      return true;
    }
    if (_isCheckingSendModelConfiguration) {
      return false;
    }

    _isCheckingSendModelConfiguration = true;
    try {
      final results = await Future.wait<dynamic>([
        // Sending must validate the already persisted Provider document. It
        // is not a model-catalog refresh action; /models is requested only
        // from Provider configuration or an explicit refresh control.
        ModelProviderConfigService.loadChatModelGroups(refresh: false),
        SceneModelConfigService.getSceneCatalog(),
      ]);
      if (!mounted) {
        return false;
      }

      final groups = results[0] as List<ProviderModelGroup>;
      final catalog = results[1] as List<SceneCatalogItem>;
      final profiles = groups.map((group) => group.profile).toList();
      final source = <String, List<ProviderModelOption>>{
        for (final group in groups)
          group.profile.id: List<ProviderModelOption>.from(group.models),
      };
      final mergedOptions = _mergeChatModelOptions(
        profiles: profiles,
        source: source,
        sceneCatalog: catalog,
        overrideSelection: _activeConversationModelOverrideSelection,
      );
      final hasConfiguredModel = _hasConfiguredNormalChatProviderModel(
        profiles: profiles,
        modelOptionsByProfileId: mergedOptions,
        sceneCatalog: catalog,
      );

      setState(() {
        _modelProviderProfiles = profiles;
        _modelOptionsByProfileId = mergedOptions;
        _sceneCatalog = catalog;
      });
      if (hasConfiguredModel) {
        return true;
      }
    } catch (e) {
      debugPrint('检查聊天模型配置失败: $e');
    } finally {
      _isCheckingSendModelConfiguration = false;
    }

    if (mounted) {
      showToast(
        LegacyTextLocalizer.localize('请先配置ai服务商和模型'),
        type: ToastType.warning,
      );
    }
    return false;
  }

  @override
  Future<void> _sendMessage({
    String? text,
    bool waitForBootstrap = true,
  }) async {
    // A Harness switch changes both the native ACP adapter and the visible
    // conversation runtime. Let a user submit queue behind that atomic
    // transition instead of registering it against the old target.
    final queuedDuringSwitch =
        waitForBootstrap && _harnessSwitchSendBarrier.isActive;
    final submittedText = queuedDuringSwitch
        ? (text ?? _messageController.text)
        : null;
    final submittedAttachments = queuedDuringSwitch
        ? List<ChatInputAttachment>.of(_pendingAttachments)
        : null;
    if (waitForBootstrap) {
      final switched = await _harnessSwitchSendBarrier.waitUntilIdle();
      // A failed switch must not deliver the queued prompt to the old Agent.
      // The preserved composer remains editable for the user to send later.
      if (!mounted || !switched) return;
    }
    // Acquire the per-target submit lock immediately after the transition
    // barrier. Two queued UI submit paths wake in the same microtask turn, so
    // only the first may continue into bootstrap/model loading.
    // The target request id changes whenever the page moves to another
    // conversation, allowing independent ACP sessions to send concurrently.
    final sendTargetId = _conversationTargetRequestId;
    if (!_sendMessageInFlightTargetIds.add(sendTargetId)) return;
    try {
      // The chat surface is rendered before the asynchronous conversation
      // bootstrap finishes. Wait for it before inserting the optimistic user
      // row; otherwise bootstrap can restore/reset the target immediately after
      // this method and make the row flash and disappear.
      final bootstrapFuture = _conversationBootstrapFuture;
      // _sendInitialMessageIfNeeded is called from inside this very bootstrap
      // future. Waiting for it here would await the current Future forever,
      // leaving enhancement/replay prompts with no user message or request.
      if (waitForBootstrap && bootstrapFuture != null) {
        await bootstrapFuture;
      }
      final messageText = (submittedText ?? text ?? _messageController.text)
          .trim();
      final inputAttachments = submittedAttachments ?? _pendingAttachments;
      final hasAttachments = inputAttachments.isNotEmpty;
      if ((messageText.isEmpty && !hasAttachments) || _isAiResponding) return;
      if (!hasAttachments &&
          ManualRecordingFlowController.isCommand(messageText)) {
        await _startManualRecordingCommand(messageText);
        return;
      }
      if (!await _ensureNormalChatModelConfigurationForSend()) return;

      final attachments = inputAttachments.map((item) => item.toMap()).toList();
      await _dispatchUserMessage(
        messageText,
        attachments: attachments,
        runSlashCommand: true,
        restoreInputValue:
            queuedDuringSwitch && _messageController.text != submittedText
            ? _messageController.value
            : null,
      );
    } finally {
      _sendMessageInFlightTargetIds.remove(sendTargetId);
    }
  }

  @override
  Future<void> _startManualRecordingCommand(String messageText) async {
    await ManualRecordingFlowController.start(
      context: context,
      inputFocusNode: _inputFocusNode,
      userMessageText: messageText,
      recordDebugScreenshots: true,
      isMounted: () => mounted,
      addUserMessage: (text) {
        final ids = addUserMessage(text);
        return ManualRecordingFlowMessageIds(
          userMessageId: ids.userMessageId,
          aiMessageId: ids.aiMessageId,
        );
      },
      afterUserMessageAdded: (_) => saveConversation(),
      insertResultMessage: (messageId, result) {
        if (!mounted) return;
        final succeeded = result['success'] == true;
        final text = succeeded
            ? hasOmniFlowRegisteredFunction(result)
                  ? '手动录制完成，复用指令已保存'
                  : '手动录制完成，RunLog 已保存；复用指令生成失败'
            : ((result['error_message'] ?? '').toString().trim().isEmpty
                  ? '手动录制失败'
                  : '手动录制失败：${result['error_message']}');
        final card = buildManualRecordingResultCard(
          messageId: messageId,
          result: result,
          summary: text,
        );
        final index = _messages.indexWhere(
          (message) => message.id == messageId,
        );
        setState(() {
          if (index == -1) {
            _insertVisibleMessage(card);
          } else {
            _replaceVisibleMessage(messageId, card);
          }
        });
        unawaited(saveConversation());
      },
      onFinally: () async {
        if (!mounted) return;
        setState(() => _isAiResponding = false);
        await saveConversation();
      },
    );
  }

  @override
  Future<void> _retryUserMessageText(
    String text, {
    List<Map<String, dynamic>> attachments = const [],
    String? retainedUserMessageId,
  }) async {
    final messageText = text.trim();
    if (messageText.isEmpty && attachments.isEmpty) return;

    // A manual retry is a fresh user send, not a request to replace a live
    // prompt.  ACP cancellation is asynchronous and the active turn remains
    // authoritative until its official PromptResponse arrives.
    if (_isAiResponding) return;

    await _dispatchUserMessage(
      messageText,
      attachments: attachments,
      runSlashCommand: false,
      restoreInputValue: _messageController.value,
      retainedUserMessageId: retainedUserMessageId,
    );
  }

  Future<void> _dispatchUserMessage(
    String messageText, {
    required List<Map<String, dynamic>> attachments,
    required bool runSlashCommand,
    TextEditingValue? restoreInputValue,
    String? retainedUserMessageId,
  }) async {
    if ((messageText.isEmpty && attachments.isEmpty) || _isAiResponding) {
      return;
    }

    if (runSlashCommand) {
      final handledSlash = await _tryHandleSlashCommand(
        messageText,
        attachments: attachments,
      );
      if (handledSlash) return;
    }

    if (_isOpenClawSurface && _openClawBaseUrl.trim().isEmpty) {
      _showSnackBar('请先使用 /openclaw 完成配置');
      _showOpenClawCommandPanel(expand: true);
      return;
    }
    if (!await _ensureNormalChatModelConfigurationForSend()) return;

    _inputFocusNode.unfocus();
    final retainedUserMessageIndex = retainedUserMessageId == null
        ? -1
        : _messages.indexWhere(
            (message) =>
                message.id == retainedUserMessageId && message.user == 1,
          );
    final ({String userMessageId, String aiMessageId, int userCreatedAtMillis})
    messageIds;
    if (retainedUserMessageIndex >= 0) {
      final retainedUserMessage = _messages[retainedUserMessageIndex];
      final dispatchTimestamp = DateTime.now().millisecondsSinceEpoch;
      setState(() {
        _isAiResponding = true;
      });
      messageIds = (
        userMessageId: retainedUserMessage.id,
        aiMessageId: '$dispatchTimestamp-ai',
        userCreatedAtMillis:
            retainedUserMessage.createAt.millisecondsSinceEpoch,
      );
    } else {
      messageIds = addUserMessage(messageText, attachments: attachments);
      setState(() {
        _modeState(_activeMode).consumeMessageAttachments(attachments);
      });
      _syncUserMessageLinkPreviews(messageIds.userMessageId);
    }
    if (restoreInputValue != null && mounted) {
      _messageController.value = restoreInputValue;
    }

    final submission = _ChatTurnSubmission(
      text: messageText,
      attachments: attachments,
    );
    if (_isOpenClawSurface) {
      await _sendChatMessage(messageIds.aiMessageId, submission);
      return;
    }

    if (_activeConversationMode == ChatPageMode.agent) {
      await _sendAgentMessage(
        messageIds.aiMessageId,
        messageText,
        userMessageId: messageIds.userMessageId,
        attachments: attachments,
      );
      return;
    }

    try {
      await _ensureActiveConversationReadyForStreaming();
    } catch (error) {
      if (mounted) {
        handleAgentError('Conversation setup failed. Please retry. $error');
      }
      return;
    }

    if (activeConversationModeValue == ConversationMode.chatOnly) {
      await _sendPureChatMessage(messageIds.aiMessageId, submission);
      return;
    }

    // A failed launch is already this run's PromptResponse; the former
    // "统一 Agent 启动失败" fallback here could never fire (5d-0 cleanup).
    await _handleExecutableTaskFlow(messageIds.aiMessageId, submission);
  }

  void _syncUserMessageLinkPreviews(String messageId) {
    final index = _messages.indexWhere((msg) => msg.id == messageId);
    if (index == -1) {
      return;
    }

    final message = _messages[index];
    if (message.type != 1 || message.user != 1) {
      return;
    }

    final content = Map<String, dynamic>.from(message.content ?? const {});
    final nextPreviews = LinkPreviewService.instance.reconcilePreviewMaps(
      text: message.text ?? '',
      existing: content['linkPreviews'],
    );
    if (_previewMapListsEqual(content['linkPreviews'], nextPreviews)) {
      return;
    }

    setState(() {
      if (nextPreviews.isEmpty) {
        content.remove('linkPreviews');
      } else {
        content['linkPreviews'] = nextPreviews;
      }
      _replaceVisibleMessage(message.id, message.copyWith(content: content));
    });

    // 用户消息也先展示 loading 卡片，抓取完成后再回填真实预览。
    for (final previewMap in nextPreviews) {
      final preview = ChatLinkPreview.fromJson(previewMap);
      if (preview.status != ChatLinkPreview.statusLoading ||
          preview.url.isEmpty) {
        continue;
      }
      unawaited(_resolveUserMessageLinkPreview(messageId, preview.url));
    }
  }

  Future<void> _resolveUserMessageLinkPreview(
    String messageId,
    String url,
  ) async {
    final resolved = await LinkPreviewService.instance.loadPreview(url);
    if (!mounted) {
      return;
    }

    var didUpdate = false;
    setState(() {
      final index = _messages.indexWhere((msg) => msg.id == messageId);
      if (index == -1) {
        return;
      }

      final message = _messages[index];
      final content = Map<String, dynamic>.from(message.content ?? const {});
      final rawPreviews = content['linkPreviews'];
      if (rawPreviews is! List) {
        return;
      }

      final updatedPreviews = rawPreviews
          .whereType<Map>()
          .map(
            (item) => Map<String, dynamic>.from(item.cast<String, dynamic>()),
          )
          .map((previewMap) {
            final preview = ChatLinkPreview.fromJson(previewMap);
            if (preview.url != url ||
                preview.status != ChatLinkPreview.statusLoading) {
              return previewMap;
            }
            didUpdate = true;
            return resolved.toJson();
          })
          .toList();
      if (!didUpdate) {
        return;
      }

      content['linkPreviews'] = updatedPreviews;
      _replaceVisibleMessage(message.id, message.copyWith(content: content));
    });

    if (!didUpdate) {
      return;
    }

    final conversationId = _currentConversationId;
    if (conversationId != null &&
        !isEphemeralConversation(conversationId, activeConversationModeValue)) {
      await _runtimeCoordinator.persistConversationMessageSnapshot(
        conversationId: conversationId,
        mode: _modeKey(_activeMode),
        messages: List<ChatMessageModel>.from(_messages),
        conversation: _currentConversation,
      );
    }
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

  @override
  Future<void> _sendChatMessage(
    String aiMessageId,
    _ChatTurnSubmission submission,
  ) async {
    await _sendPureChatMessage(aiMessageId, submission);
  }

  @override
  Future<void> _sendPureChatMessage(
    String aiMessageId,
    _ChatTurnSubmission submission,
  ) {
    return _launchNormalTurn(
      aiMessageId,
      submission,
      // A conversation override applies to pure chat; the scene binding is
      // the fallback.
      selection:
          _activeConversationModelOverrideSelection ??
          _activeDispatchSceneSelection,
      // Pure chat is an ACP turn with tools disabled, not a provider-only
      // transport. It has no Harness identity: otherwise a previous
      // DSH/Xiaowan switch leaks into the pure-chat session.
      agentId: activeConversationModeValue == ConversationMode.chatOnly
          ? null
          : _kXiaowanAcpAgentId,
      clearThinkingOnFailure: true,
    ).then((_) {});
  }

  @override
  Future<bool> _handleExecutableTaskFlow(
    String aiMessageId,
    _ChatTurnSubmission submission,
  ) async {
    // A page preflight flag has no task identity and can be cleared by an
    // older async flow after a newer ACP turn has started. Keep this
    // presentation hint only for the non-Agent path.
    final isLegacyDispatch = _activeMode != ChatPageMode.agent;
    if (isLegacyDispatch) _isCheckingExecutableTask = true;
    try {
      return await _launchNormalTurn(
        aiMessageId,
        submission,
        selection: _activeDispatchSceneSelection,
        agentId: _kXiaowanAcpAgentId,
      );
    } finally {
      if (isLegacyDispatch) _isCheckingExecutableTask = false;
    }
  }

  /// Normal-page turns (pure chat and the Xiaowan task flow) through the
  /// native launcher (batch 5d-0b). Settings are frozen before the first
  /// await; the launcher stops on its own when this page moves on.
  Future<bool> _launchNormalTurn(
    String aiMessageId,
    _ChatTurnSubmission submission, {
    required _ChatModelOverrideSelection? selection,
    required String? agentId,
    bool clearThinkingOnFailure = false,
  }) async {
    final dispatchTargetGeneration = _conversationTargetRequestId;
    final dispatchModeKey = _modeKey(_activeMode);
    final dispatchConversationMode = activeConversationModeValue;
    final dispatchConversationId =
        _currentConversationId ?? _resolvedThreadTarget?.conversationId;
    final dispatchSessionId =
        _normalAcpSessionConversationId == dispatchConversationId
        ? _normalAcpSessionId
        : null;
    final dispatchPermissionMode = _agentPermissionMode;
    final dispatchReasoningEffort = _activeConversationReasoningEffort;
    final dispatchTerminalEnvironment = _buildAgentTerminalEnvironmentPayload();
    bool isDispatchTargetCurrent() =>
        mounted && dispatchTargetGeneration == _conversationTargetRequestId;

    var conversationId = dispatchConversationId;
    if (conversationId == null) {
      if (!isDispatchTargetCurrent()) return false;
      try {
        await _ensureActiveConversationReadyForStreaming();
      } catch (error) {
        if (isDispatchTargetCurrent()) {
          showToast(
            formatAgentRuntimeErrorForUser(
              'Conversation setup failed. Please retry. $error',
            ),
            type: ToastType.error,
          );
        }
        return false;
      }
      if (!isDispatchTargetCurrent()) return false;
      conversationId = _currentConversationId;
    }
    if (conversationId == null) {
      if (isDispatchTargetCurrent()) {
        showToast(
          formatAgentRuntimeErrorForUser(
            'Conversation setup failed. Please retry.',
          ),
          type: ToastType.error,
        );
      }
      return false;
    }
    final resolvedConversationId = conversationId;
    final ChatTurnLaunchOutcome outcome;
    try {
      outcome = await ChatPromptDispatcher.instance.launchTurn(
        taskId: aiMessageId,
        conversationId: resolvedConversationId,
        mode: dispatchModeKey,
        surfaceId: _chatPageSurfaceId,
        generation: dispatchTargetGeneration,
        text: submission.text,
        attachments: submission.attachments,
        existingSessionId: dispatchSessionId,
        agentId: agentId,
        permissionMode: dispatchPermissionMode.preferenceValue,
        model: selection?.modelId,
        effort: dispatchReasoningEffort,
        conversationMode: dispatchConversationMode.storageValue,
        terminalEnvironment: dispatchTerminalEnvironment,
        clearThinkingOnFailure: clearThinkingOnFailure,
      );
    } catch (error) {
      // A channel failure before the launcher ran: end the run on its own
      // runtime, never on whichever one is visible now.
      await _runtimeCoordinator.applyAcpPromptResponse(
        taskId: aiMessageId,
        conversationId: resolvedConversationId,
        mode: dispatchModeKey,
        sessionId: null,
        stopReason: 'error',
        error: formatAgentRuntimeErrorForUser(error),
      );
      return false;
    }
    // Pointers arrive only while this target is still current.
    if (outcome.targetCurrent) {
      if (outcome.sessionId != null) {
        _normalAcpSessionId = outcome.sessionId;
        _normalAcpSessionConversationId = resolvedConversationId;
      }
      _normalAcpTurnId = outcome.turnId;
    }
    return outcome.completed;
  }

  @override
  void _onCancelTask() {
    try {
      if (_activeConversationMode == ChatPageMode.agent) {
        // ACP owns the terminal transition. Do not unregister the task or
        // manufacture a cancelled message here: doing so makes the event
        // reducer reject the real turn/completed notification and leaves the
        // native turn running behind a reset Flutter projection.
        unawaited(_interruptAgentTurn());
        return;
      }
      if (_activeConversationMode == ChatPageMode.normal &&
          activeConversationModeValue != ConversationMode.chatOnly &&
          (_currentDispatchTurnId != null || _normalAcpTurnId != null)) {
        // Keep the host reservation alive until the official cancel result.
        // The shared reducer then finalizes cards, history, and the spinner
        // exactly once.
        unawaited(
          cancelAcpPromptForMode(
            mode: ChatPageMode.normal,
            sessionId: _normalAcpSessionId,
            turnId: _normalAcpTurnId,
          ),
        );
        return;
      }
      if (_currentDispatchTurnId != null ||
          _activeRuntime?.lastAgentTurnId != null ||
          _isCheckingExecutableTask ||
          _isExecutingTask) {
        _cancelDispatchTask();
      } else {
        unawaited(
          cancelAcpPromptForMode(
            mode: ChatPageMode.normal,
            sessionId: _normalAcpSessionId,
            turnId: _normalAcpTurnId,
          ),
        );
      }

      setState(() {
        _isAiResponding = false;
        _isContextCompressing = false;
        _isCheckingExecutableTask = false;
        _isExecutingTask = false;
        _isInputAreaVisible = true;
        _removeVisibleMessages(
          _messages
              .where(
                (msg) => msg.isLoading || _isOpenClawWaitingCardMessage(msg),
              )
              .map((msg) => msg.id),
        );
      });

      debugPrint('Task cancelled, all states reset');
    } catch (e) {
      debugPrint('onCancelTask error: $e');
    }
  }

  @override
  void _cancelDispatchTask() {
    final taskId = _currentDispatchTurnId ?? _activeRuntime?.lastAgentTurnId;
    final runtimeIdentity = _activeRuntime?.activeRunIdentity;
    final agentSessionId =
        runtimeIdentity?.normalizedSessionId ?? _activeAgentThreadId?.trim();
    final agentTurnId =
        runtimeIdentity?.normalizedTurnId ?? _activeAgentTurnId?.trim();
    final normalSessionId =
        _runtimeForMode(ChatPageMode.normal)
            ?.activeRunIdentity
            ?.normalizedSessionId ??
        _normalAcpSessionId?.trim();
    final normalTurnId =
        _runtimeForMode(ChatPageMode.normal)
            ?.activeRunIdentity
            ?.normalizedTurnId ??
        _normalAcpTurnId?.trim();
    if (_activeConversationMode == ChatPageMode.normal &&
        activeConversationModeValue != ConversationMode.chatOnly) {
      unawaited(
        cancelAcpPromptForMode(
          mode: ChatPageMode.normal,
          sessionId: normalSessionId,
          turnId: normalTurnId,
        ),
      );
      return;
    }
    if (_activeConversationMode == ChatPageMode.agent) {
      // The official ACP cancel result is the only authority allowed to end a
      // new Agent turn. This method is also used by card-level stop actions.
      unawaited(_interruptAgentTurn());
      return;
    }
    interruptActiveToolCard();
    if (!(_activeConversationMode == ChatPageMode.normal &&
        activeConversationModeValue != ConversationMode.chatOnly)) {
      unawaited(
        cancelAcpPromptForMode(
          mode: _activeConversationMode,
          sessionId: agentSessionId,
          turnId: agentTurnId,
        ),
      );
    }
    if (taskId != null) {
      _updateThinkingCardToCancelled(taskId);
      _upsertCancelledAgentRunMessage(taskId);
      _collapseAgentRunTrace(taskId);
      _runtimeCoordinator.unregisterTask(
        taskId,
        conversationId: _currentConversationId,
        mode: _modeKey(_activeConversationMode),
      );
    }
    if (_activeConversationMode != ChatPageMode.agent) {
      clearAgentStreamSessionState();
      resetDispatchState();
    }
  }

  @override
  void _onCancelTaskFromCard(String taskId) {
    try {
      final runtimeIdentity = _activeRuntime?.activeRunIdentity;
      final agentSessionId =
          runtimeIdentity?.normalizedSessionId ?? _activeAgentThreadId?.trim();
      final agentTurnId =
          runtimeIdentity?.normalizedTurnId ?? _activeAgentTurnId?.trim();
      final normalIdentity = _runtimeForMode(ChatPageMode.normal)
          ?.activeRunIdentity;
      final normalSessionId =
          normalIdentity?.normalizedSessionId ?? _normalAcpSessionId?.trim();
      final normalTurnId =
          normalIdentity?.normalizedTurnId ?? _normalAcpTurnId?.trim();
      final isAcpMode =
          _activeConversationMode == ChatPageMode.agent ||
          (_activeConversationMode == ChatPageMode.normal &&
              activeConversationModeValue != ConversationMode.chatOnly);
      final activeConversationId = _currentConversationId;
      if (isAcpMode &&
          (activeConversationId == null ||
              !_runtimeCoordinator.isTaskActive(
                taskId: taskId,
                conversationId: activeConversationId,
                mode: _modeKey(_activeConversationMode),
              ))) {
        // A card from an older turn must not cancel the currently active ACP
        // turn. Its terminal event is already fenced by the shared runtime.
        return;
      }
      if (_activeConversationMode == ChatPageMode.normal &&
          activeConversationModeValue != ConversationMode.chatOnly) {
        unawaited(
          cancelAcpPromptForMode(
            mode: ChatPageMode.normal,
            sessionId: normalSessionId,
            turnId: normalTurnId,
          ),
        );
        return;
      }
      if (_activeConversationMode == ChatPageMode.agent) {
        unawaited(_interruptAgentTurn());
        return;
      }
      interruptActiveToolCard();
      if (!(_activeConversationMode == ChatPageMode.normal &&
          activeConversationModeValue != ConversationMode.chatOnly)) {
        unawaited(
          cancelAcpPromptForMode(
            mode: _activeConversationMode,
            sessionId: agentSessionId,
            turnId: agentTurnId,
          ),
        );
      }
      _runtimeCoordinator.unregisterTask(
        taskId,
        conversationId: _currentConversationId,
        mode: _modeKey(_activeConversationMode),
      );
      _updateThinkingCardToCancelled(taskId);
      _upsertCancelledAgentRunMessage(taskId);
      _collapseAgentRunTrace(taskId);
      if (_activeConversationMode != ChatPageMode.agent) {
        clearAgentStreamSessionState();
        resetDispatchState();
      }
      setState(() {
        _removeVisibleMessages(
          _messages
              .where(
                (msg) => msg.isLoading || _isOpenClawWaitingCardMessage(msg),
              )
              .map((msg) => msg.id),
        );
      });
    } catch (e) {
      debugPrint('onCancelTaskFromCard error: $e');
    }
  }

  @override
  void _updateThinkingCardToCancelled(String taskId) {
    final thinkingCard = resolveAgentThinkingCardForTask(
      _messages,
      taskId: taskId,
      preferredCardId: _activeRuntime?.activeThinkingCardId,
    );
    if (thinkingCard == null) return;
    final thinkingCardId = thinkingCard.id;
    final index = _messages.indexWhere((msg) => msg.id == thinkingCardId);
    if (index == -1) return;

    final cardData = Map<String, dynamic>.from(thinkingCard.cardData ?? {});
    cardData['stage'] = 5;
    cardData['isLoading'] = false;
    cardData['endTime'] = DateTime.now().millisecondsSinceEpoch;

    setState(() {
      _replaceVisibleMessage(
        thinkingCardId,
        ChatMessageModel(
          id: thinkingCardId,
          type: 2,
          user: 3,
          content: {'cardData': cardData, 'id': thinkingCardId},
          createAt: thinkingCard.createAt,
        ),
      );
    });
    _persistDeepThinkingCardIfNeeded(_messages[index]);
  }

  @override
  void _collapseAgentRunTrace(String taskId) {
    final normalizedTaskId = taskId.trim();
    if (normalizedTaskId.isEmpty) {
      return;
    }
    final expandedTaskIds = _expandedAgentRunTaskIdsForMode(_activeMode);
    if (!expandedTaskIds.contains(normalizedTaskId)) {
      return;
    }
    final nextTaskIds = Set<String>.from(expandedTaskIds)
      ..remove(normalizedTaskId);
    _updateExpandedAgentRunTaskIds(_activeMode, nextTaskIds);
  }

  void _upsertCancelledAgentRunMessage(String taskId) {
    final normalizedTaskId = taskId.trim();
    if (normalizedTaskId.isEmpty) {
      return;
    }
    final messageId = '$normalizedTaskId-cancelled';
    final text = LegacyTextLocalizer.localize('任务已取消');
    final streamMeta = ensureAgentStreamMessageMeta(
      null,
      seq: 1000000000,
      roundIndex: 1000000000,
      kind: 'text_snapshot',
      parentTaskId: normalizedTaskId,
      entryId: messageId,
      isFinal: true,
    );
    final content = <String, dynamic>{
      'text': text,
      'id': messageId,
      'renderMarkdown': false,
    };
    final existingIndex = _messages.indexWhere(
      (message) => message.id == messageId,
    );
    setState(() {
      if (existingIndex == -1) {
        _insertVisibleMessage(
          ChatMessageModel(
            id: messageId,
            type: 1,
            user: 2,
            content: content,
            streamMeta: streamMeta,
          ),
        );
      } else {
        _replaceVisibleMessage(
          messageId,
          _messages[existingIndex].copyWith(
            content: content,
            isLoading: false,
            isError: false,
            streamMeta: streamMeta,
          ),
        );
      }
    });
    if (_currentConversationId != null) {
      _syncRuntimeSnapshotForMode(_activeMode);
    }
    unawaited(saveConversation());
  }

  @override
  void _onPopupVisibilityChanged(bool visible) {
    setState(() {
      _isPopupVisible = visible;
    });
  }

  @override
  Future<void> _requestAuthorizeForExecution(
    List<String> requiredPermissionIds,
  ) async {
    if (_isAwaitingAuthorizeResult) return;

    _isAwaitingAuthorizeResult = true;
    try {
      await GoRouterManager.pushForResult<bool>(
        '/home/authorize',
        extra: AuthorizePageArgs(
          requiredPermissionIds: requiredPermissionIds.isEmpty
              ? kTaskExecutionRequiredPermissionIds
              : requiredPermissionIds,
        ),
      );
      // Granting a device permission changes only that permission. It is not
      // a new user prompt, and must not delete/recreate the visible turn or
      // silently replay its actions. The retained user message provides the
      // explicit retry affordance if the user wants to continue.
    } finally {
      _isAwaitingAuthorizeResult = false;
    }
  }
}
