part of 'chat_page.dart';

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
          runtime?.isAiResponding ?? (_modeState(mode).isAiResponding),
      isContextCompressing:
          runtime?.isContextCompressing ??
          (_modeState(mode).isContextCompressing),
      isCheckingExecutableTask:
          runtime?.isCheckingExecutableTask ??
          (_modeState(mode).isCheckingExecutableTask),
      currentAiMessages: Map<String, String>.from(
        runtime?.currentAiMessages ?? _modeState(mode).currentAiMessages,
      ),
      currentThinkingMessages: Map<String, String>.from(
        runtime?.currentThinkingMessages ?? const <String, String>{},
      ),
      deepThinkingContent:
          runtime?.deepThinkingContent ??
          (_modeState(mode).deepThinkingContent),
      isDeepThinking:
          runtime?.isDeepThinking ?? (_modeState(mode).isDeepThinking),
      currentDispatchTaskId:
          runtime?.currentDispatchTaskId ??
          _modeState(mode).currentDispatchTaskId,
      currentThinkingStage:
          runtime?.currentThinkingStage ??
          (_modeState(mode).currentThinkingStage),
      isInputAreaVisible:
          runtime?.isInputAreaVisible ?? (_modeState(mode).isInputAreaVisible),
      isExecutingTask:
          runtime?.isExecutingTask ?? (_modeState(mode).isExecutingTask),
      lastAgentTaskId: runtime?.lastAgentTaskId,
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
      );
    }
    if (_currentConversationId == null) {
      throw StateError('conversationId is not ready');
    }
    _syncRuntimeSnapshotForMode(_activeMode);
  }

  @override
  void _registerActiveTaskBinding(String taskId) {
    final conversationId = _currentConversationId;
    if (conversationId == null) return;
    _runtimeCoordinator.registerTask(
      taskId: taskId,
      conversationId: conversationId,
      mode: _modeKey(_activeMode),
    );
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
      setState(() => _messages.removeAt(loadingIndex));
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
      _messages.removeWhere((msg) => msg.id == thinkingCardId);
      _messages.insert(
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
      _messages[index] = existing.copyWith(
        content: content,
        streamMeta: ensureAgentStreamMessageMeta(
          streamMeta ?? existing.streamMeta,
          entryId: thinkingCardId,
        ),
      );
    });
  }

  @override
  Future<void> _pickAttachments() async {
    try {
      final result = await FilePicker.platform.pickFiles(
        allowMultiple: true,
        type: FileType.any,
      );
      if (result == null || result.files.isEmpty || !mounted) return;

      setState(() {
        for (final file in result.files) {
          final path = file.path;
          if (path == null || path.isEmpty) continue;
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
              size: file.size > 0 ? file.size : null,
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

    for (final profileId in configuredProfileIds) {
      final models = optionsSource[profileId] ?? const <ProviderModelOption>[];
      if (models.any((model) => model.id.trim().isNotEmpty)) {
        return true;
      }
    }

    for (final scene in catalogSource) {
      final providerProfileId = scene.effectiveProviderProfileId.trim();
      if (!scene.providerConfigured ||
          !configuredProfileIds.contains(providerProfileId)) {
        continue;
      }
      if (scene.effectiveModel.trim().isNotEmpty) {
        return true;
      }
    }

    final override = _activeConversationModelOverrideSelection;
    return override != null &&
        configuredProfileIds.contains(override.providerProfileId) &&
        override.modelId.trim().isNotEmpty;
  }

  @override
  Future<bool> _ensureNormalChatModelConfigurationForSend() async {
    if (_activeMode != ChatPageMode.normal || _isOpenClawSurface) {
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
        ModelProviderConfigService.loadChatModelGroups(),
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
  Future<void> _sendMessage({String? text}) async {
    final messageText = (text ?? _messageController.text).trim();
    final hasAttachments = _pendingAttachments.isNotEmpty;
    if ((messageText.isEmpty && !hasAttachments) || _isAiResponding) return;
    if (!hasAttachments &&
        ManualRecordingFlowController.isCommand(messageText)) {
      await _startManualRecordingCommand(messageText);
      return;
    }
    if (!await _ensureNormalChatModelConfigurationForSend()) return;

    final attachments = _pendingAttachments
        .map((item) => item.toMap())
        .toList();
    if (attachments.isNotEmpty && mounted) {
      setState(() => _pendingAttachments.clear());
    }

    await _dispatchUserMessage(
      messageText,
      attachments: attachments,
      runSlashCommand: true,
    );
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
            ? result['function'] is Map
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
            _messages.insert(0, card);
          } else {
            _messages[index] = card;
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

    if (_isAiResponding) {
      _onCancelTask();
    }

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
      _syncUserMessageLinkPreviews(messageIds.userMessageId);
    }
    if (restoreInputValue != null && mounted) {
      _messageController.value = restoreInputValue;
    }

    if (_isOpenClawSurface) {
      await _sendChatMessage(messageIds.aiMessageId);
      return;
    }

    if (_activeConversationMode == ChatPageMode.agent) {
      await _sendAgentMessage(
        messageIds.aiMessageId,
        messageText,
        attachments: attachments,
      );
      return;
    }

    try {
      await _ensureActiveConversationReadyForStreaming();
    } catch (_) {
      if (mounted) {
        handleAgentError('Conversation setup failed. Please retry.');
      }
      return;
    }

    if (activeConversationModeValue == ConversationMode.chatOnly) {
      await _sendPureChatMessage(messageIds.aiMessageId);
      return;
    }

    final handled = await _handleExecutableTaskFlow(
      messageIds.aiMessageId,
      messageIds.userMessageId,
    );
    if (!handled &&
        mounted &&
        _currentDispatchTaskId == messageIds.aiMessageId) {
      handleAgentError('统一 Agent 启动失败，请检查模型提供商与场景模型配置。');
    }
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
      _messages[index] = message.copyWith(content: content);
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
      _messages[index] = message.copyWith(content: content);
    });

    if (!didUpdate) {
      return;
    }

    final conversationId = _currentConversationId;
    if (conversationId != null &&
        !isEphemeralConversation(conversationId, activeConversationModeValue)) {
      await ConversationHistoryService.saveConversationMessages(
        conversationId,
        List<ChatMessageModel>.from(_messages),
        mode: activeConversationModeValue,
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
  Future<void> _sendChatMessage(String aiMessageId) async {
    if (!_isOpenClawSurface) {
      handleAgentError('统一 Agent 已启用，旧聊天链路已移除，请检查配置后重试。');
      return;
    }
    try {
      await _ensureActiveConversationReadyForStreaming();
    } catch (_) {
      if (mounted) {
        handleAgentError('Conversation setup failed. Please retry.');
      }
      return;
    }
    final conversationId = _currentConversationId;
    if (conversationId == null) {
      if (mounted) {
        handleAgentError('Conversation setup failed. Please retry.');
      }
      return;
    }
    final history = buildConversationHistory();
    final userMessage = latestUserUtterance();
    final userAttachments = await _latestUserAttachments();
    final openClawConfig = {
      'baseUrl': _openClawBaseUrl,
      if (_openClawToken.isNotEmpty) 'token': _openClawToken,
      if (_openClawUserId.isNotEmpty) 'userId': _openClawUserId,
      'sessionKey': _buildOpenClawSessionKey(conversationId),
    };
    _showOpenClawWaitingCard(aiMessageId);
    _syncRuntimeSnapshotForMode(_activeMode);
    _registerActiveTaskBinding(aiMessageId);
    _runtimeCoordinator.primePureChatThinking(
      taskId: aiMessageId,
      conversationId: conversationId,
      mode: _modeKey(_activeMode),
    );
    final success = await AssistsMessageService.createChatTask(
      aiMessageId,
      history,
      provider: 'openclaw',
      openClawConfig: openClawConfig,
      conversationId: conversationId,
      conversationMode: activeConversationModeValue.storageValue,
      userMessage: userMessage,
      userAttachments: userAttachments,
    );
    if (success) return;
    _runtimeCoordinator.unregisterTask(aiMessageId);

    try {
      throw Exception('createChatTask returned false');
    } catch (error) {
      if (!mounted) return;
      final errorId = DateTime.now().millisecondsSinceEpoch.toString();
      _removeOpenClawWaitingCard(aiMessageId);
      setState(() {
        _isAiResponding = false;
        _isContextCompressing = false;
        removeLatestLoadingIfExists();
        _messages.insert(
          0,
          ChatMessageModel(
            id: errorId,
            type: 1,
            user: 2,
            content: {'text': '抱歉，发送消息失败：$error', 'id': errorId},
          ),
        );
      });
    }
  }

  @override
  Future<void> _sendPureChatMessage(String aiMessageId) async {
    try {
      await _ensureActiveConversationReadyForStreaming();
    } catch (_) {
      if (mounted) {
        handleAgentError('Conversation setup failed. Please retry.');
      }
      return;
    }
    final conversationId = _currentConversationId;
    if (conversationId == null) {
      if (mounted) {
        handleAgentError('Conversation setup failed. Please retry.');
      }
      return;
    }

    final history = buildConversationHistory();
    final userMessage = latestUserUtterance();
    final userAttachments = await _latestUserAttachments();

    _syncRuntimeSnapshotForMode(_activeMode);
    _registerActiveTaskBinding(aiMessageId);
    _runtimeCoordinator.primePureChatThinking(
      taskId: aiMessageId,
      conversationId: conversationId,
      mode: _modeKey(_activeMode),
    );
    final success = await AssistsMessageService.createChatTask(
      aiMessageId,
      history,
      conversationId: conversationId,
      conversationMode: activeConversationModeValue.storageValue,
      userMessage: userMessage,
      userAttachments: userAttachments,
      modelOverride: _buildChatModelOverridePayload(),
      reasoningEffort: _activeConversationReasoningEffort,
    );
    if (success) {
      return;
    }
    _runtimeCoordinator.clearPureChatThinking(
      taskId: aiMessageId,
      conversationId: conversationId,
      mode: _modeKey(_activeMode),
    );
    _runtimeCoordinator.unregisterTask(aiMessageId);

    if (!mounted) {
      return;
    }
    final errorId = DateTime.now().millisecondsSinceEpoch.toString();
    setState(() {
      _isAiResponding = false;
      _isContextCompressing = false;
      removeLatestLoadingIfExists();
      _messages.insert(
        0,
        ChatMessageModel(
          id: errorId,
          type: 1,
          user: 2,
          content: {'text': '抱歉，发送消息失败，请稍后重试。', 'id': errorId},
        ),
      );
    });
  }

  @override
  Future<bool> _handleExecutableTaskFlow(
    String aiMessageId,
    String userMessageId,
  ) async {
    _isCheckingExecutableTask = true;
    try {
      return await _tryAgentFlow(aiMessageId, userMessageId);
    } finally {
      _isCheckingExecutableTask = false;
    }
  }

  @override
  Future<bool> _tryAgentFlow(String aiMessageId, String userMessageId) async {
    try {
      _currentDispatchTaskId = aiMessageId;
      _deepThinkingContent = '';
      _isDeepThinking = false;
      _currentThinkingStage = 1;

      createThinkingCard(aiMessageId);
      _syncRuntimeSnapshotForMode(_activeMode);
      _registerActiveTaskBinding(aiMessageId);

      final userMessage = latestUserUtterance();
      final attachments = _latestUserAgentAttachments();

      final success = await AssistsMessageService.createAgentTask(
        taskId: aiMessageId,
        userMessage: userMessage,
        attachments: attachments,
        conversationId: _currentConversationId,
        conversationMode: activeConversationModeValue.storageValue,
        userMessageCreatedAtMillis: userMessageId.endsWith('-user')
            ? int.tryParse(userMessageId.split('-').first)
            : null,
        modelOverride: _buildAgentModelOverridePayload(),
        reasoningEffort: _activeConversationReasoningEffort,
        terminalEnvironment: _buildAgentTerminalEnvironmentPayload(),
      );
      if (!success) {
        _runtimeCoordinator.unregisterTask(aiMessageId);
      }

      return success;
    } catch (e) {
      _runtimeCoordinator.unregisterTask(aiMessageId);
      debugPrint('Agent flow error: $e');
      return false;
    }
  }

  @override
  Future<List<Map<String, dynamic>>> _latestUserAttachments() async {
    for (final message in _messages) {
      if (message.user != 1) continue;
      final raw = message.content?['attachments'];
      if (raw is! List) return const [];
      final normalized = raw
          .whereType<Map>()
          .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
          .where(_attachmentShouldSendToModel)
          .toList();
      for (final item in normalized) {
        if (!_isImageAttachmentMap(item)) continue;
        final dataUrl = await _resolveImageDataUrl(item);
        if (dataUrl.isNotEmpty) {
          item['dataUrl'] = dataUrl;
        }
      }
      return normalized;
    }
    return const [];
  }

  bool _attachmentShouldSendToModel(Map<String, dynamic> attachment) {
    final raw = attachment['sendToModel'];
    if (raw is bool) return raw;
    if (raw is String) return raw.toLowerCase() != 'false';
    return true;
  }

  List<Map<String, dynamic>> _latestUserAgentAttachments() {
    for (final message in _messages) {
      if (message.user != 1) continue;
      return buildAgentRuntimeAttachmentsFromMessageContent(message.content);
    }
    return const <Map<String, dynamic>>[];
  }

  @override
  bool _isImageAttachmentMap(Map<String, dynamic> item) {
    final explicitFlag = item['isImage'];
    if (explicitFlag is bool && explicitFlag) return true;
    final mimeType = (item['mimeType'] as String? ?? '').toLowerCase();
    if (mimeType.startsWith('image/')) return true;
    final path = (item['path'] as String? ?? '').toLowerCase();
    final url = (item['url'] as String? ?? '').toLowerCase();
    return path.endsWith('.png') ||
        path.endsWith('.jpg') ||
        path.endsWith('.jpeg') ||
        path.endsWith('.webp') ||
        path.endsWith('.gif') ||
        path.endsWith('.bmp') ||
        path.endsWith('.heic') ||
        path.endsWith('.heif') ||
        url.endsWith('.png') ||
        url.endsWith('.jpg') ||
        url.endsWith('.jpeg') ||
        url.endsWith('.webp') ||
        url.endsWith('.gif');
  }

  @override
  Future<String> _resolveImageDataUrl(Map<String, dynamic> item) async {
    final existingDataUrl = (item['dataUrl'] as String? ?? '').trim();
    if (existingDataUrl.startsWith('data:')) {
      return existingDataUrl;
    }

    final existingUrl = (item['url'] as String? ?? '').trim();
    if (existingUrl.startsWith('data:')) {
      return existingUrl;
    }
    if (existingUrl.startsWith('http://') ||
        existingUrl.startsWith('https://')) {
      return existingUrl;
    }

    final path = (item['path'] as String? ?? '').trim();
    if (path.isEmpty) return '';
    final file = File(path);
    if (!await file.exists()) return '';
    try {
      final bytes = await file.readAsBytes();
      if (bytes.isEmpty) return '';
      final mimeType = ((item['mimeType'] as String?) ?? '')
          .trim()
          .toLowerCase();
      final resolvedMime = mimeType.startsWith('image/')
          ? mimeType
          : _mimeTypeFromExtension(path) ?? 'image/png';
      return 'data:$resolvedMime;base64,${base64Encode(bytes)}';
    } catch (_) {
      return '';
    }
  }

  @override
  void _onCancelTask() {
    try {
      if (_activeConversationMode == ChatPageMode.agent) {
        unawaited(_interruptAgentTurn());
        final taskId =
            _currentDispatchTaskId ?? _activeRuntime?.lastAgentTaskId;
        if (taskId != null) {
          _runtimeCoordinator.unregisterTask(taskId);
          _upsertCancelledAgentRunMessage(taskId);
          _collapseAgentRunTrace(taskId);
        }
        setState(() {
          _isAiResponding = false;
          _isContextCompressing = false;
          _isCheckingExecutableTask = false;
          _isExecutingTask = false;
          _isInputAreaVisible = true;
          _currentDispatchTaskId = null;
          _messages.removeWhere((msg) => msg.isLoading);
        });
        return;
      }
      if (_currentDispatchTaskId != null ||
          _activeRuntime?.lastAgentTaskId != null ||
          _isCheckingExecutableTask ||
          _isExecutingTask) {
        _cancelDispatchTask();
      } else {
        AssistsMessageService.cancelChatTask(
          taskId: _currentAiMessages.keys.isEmpty
              ? null
              : _currentAiMessages.keys.first,
        );
      }

      setState(() {
        _isAiResponding = false;
        _isContextCompressing = false;
        _isCheckingExecutableTask = false;
        _isExecutingTask = false;
        _isInputAreaVisible = true;
        _messages.removeWhere(
          (msg) => msg.isLoading || _isOpenClawWaitingCardMessage(msg),
        );
      });

      debugPrint('Task cancelled, all states reset');
    } catch (e) {
      debugPrint('onCancelTask error: $e');
    }
  }

  @override
  void _cancelDispatchTask() {
    final taskId = _currentDispatchTaskId ?? _activeRuntime?.lastAgentTaskId;
    interruptActiveToolCard();
    AssistsMessageService.cancelRunningTask(taskId: taskId);
    if (taskId != null) {
      _updateThinkingCardToCancelled(taskId);
      _upsertCancelledAgentRunMessage(taskId);
      _collapseAgentRunTrace(taskId);
      _runtimeCoordinator.unregisterTask(taskId);
    }
    clearAgentStreamSessionState();
    resetDispatchState();
  }

  @override
  void _onCancelTaskFromCard(String taskId) {
    try {
      interruptActiveToolCard();
      AssistsMessageService.cancelRunningTask(taskId: taskId);
      _runtimeCoordinator.unregisterTask(taskId);
      _updateThinkingCardToCancelled(taskId);
      _upsertCancelledAgentRunMessage(taskId);
      _collapseAgentRunTrace(taskId);
      clearAgentStreamSessionState();
      resetDispatchState();
      setState(() {
        _isAiResponding = false;
        _isContextCompressing = false;
        _isExecutingTask = false;
        _isInputAreaVisible = true;
        _messages.removeWhere(
          (msg) => msg.isLoading || _isOpenClawWaitingCardMessage(msg),
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
      _messages[index] = ChatMessageModel(
        id: thinkingCardId,
        type: 2,
        user: 3,
        content: {'cardData': cardData, 'id': thinkingCardId},
        createAt: thinkingCard.createAt,
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
        _messages.insert(
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
        _messages[existingIndex] = _messages[existingIndex].copyWith(
          content: content,
          isLoading: false,
          isError: false,
          streamMeta: streamMeta,
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
  Future<void> onAgentPermissionRequired(List<String> permissionIds) async {
    if (!mounted || _activeConversationMode != ChatPageMode.normal) {
      return;
    }
    await _requestAuthorizeForExecution(permissionIds);
  }

  @override
  Future<void> _requestAuthorizeForExecution(
    List<String> requiredPermissionIds,
  ) async {
    if (_isAwaitingAuthorizeResult) return;
    if (latestUserUtterance().trim().isEmpty) return;

    _isAwaitingAuthorizeResult = true;
    try {
      final result = await GoRouterManager.pushForResult<bool>(
        '/home/authorize',
        extra: AuthorizePageArgs(
          requiredPermissionIds: requiredPermissionIds.isEmpty
              ? kTaskExecutionRequiredPermissionIds
              : requiredPermissionIds,
        ),
      );
      if (result == true && mounted) {
        await _retryLatestInstructionAfterAuth();
      }
    } finally {
      _isAwaitingAuthorizeResult = false;
    }
  }

  @override
  Future<void> _retryLatestInstructionAfterAuth() async {
    if (_isRetryingLatestInstructionAfterAuth ||
        _activeConversationMode == ChatPageMode.openclaw) {
      return;
    }

    // Save user text and attachments before cleanup
    final savedUserText = latestUserUtterance().trim();
    final savedAttachments = await _latestUserAttachments();
    if (savedUserText.isEmpty && savedAttachments.isEmpty) return;

    _isRetryingLatestInstructionAfterAuth = true;
    final timestamp = DateTime.now().millisecondsSinceEpoch.toString();
    final aiMessageId = '$timestamp-ai';
    final userMessageId = '$timestamp-user';

    try {
      // Remove ALL messages from the failed attempt (AI responses + user message)
      if (mounted) {
        setState(() {
          _removeFailedAttemptMessages();
          _isAiResponding = true;
        });
      }

      // Sync cleaned state to Kotlin-side DB so old entries
      // (user message, permission error, thinking cards) are replaced
      final conversationId = _currentConversationId;
      if (conversationId != null) {
        await ConversationHistoryService.saveConversationMessages(
          conversationId,
          _messages,
          mode: activeConversationModeValue,
        );
      }

      // Re-add user message for display and latestUserUtterance()
      if (mounted) {
        setState(() {
          final content = <String, dynamic>{
            'text': savedUserText,
            'id': userMessageId,
          };
          if (savedAttachments.isNotEmpty) {
            content['attachments'] = savedAttachments;
          }
          _messages.insert(
            0,
            ChatMessageModel(
              id: userMessageId,
              type: 1,
              user: 1,
              content: content,
              createAt: DateTime.fromMillisecondsSinceEpoch(
                int.parse(timestamp),
              ),
            ),
          );
        });
      }

      final handled = await _handleExecutableTaskFlow(
        aiMessageId,
        userMessageId,
      );
      if (!handled && mounted && _currentDispatchTaskId == aiMessageId) {
        handleAgentError('统一 Agent 启动失败，请检查模型提供商与场景模型配置。');
      }
    } finally {
      _isRetryingLatestInstructionAfterAuth = false;
    }
  }

  /// Remove all messages from the latest failed attempt,
  /// including AI responses, cards, AND the user message that triggered it.
  @override
  void _removeFailedAttemptMessages() {
    var removeCount = 0;
    for (final message in _messages) {
      removeCount += 1;
      if (message.user == 1) break;
    }
    if (removeCount <= 0) return;
    _messages.removeRange(0, removeCount);
  }
}
