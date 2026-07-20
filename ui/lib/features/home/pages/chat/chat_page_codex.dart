part of 'chat_page.dart';

const String _kCodexModelPreferenceKey = 'model';
const String _kCodexReasoningEffortPreferenceKey = 'reasoning_effort';
const String _kCodexCollaborationModePreferenceKey = 'collaboration_mode';
const String _kCodexPreferenceStoragePrefix = 'chat_codex_command_preference';
const String _kDefaultCodexReasoningEffort = 'xhigh';
const Duration _remoteCodexExternalActiveGrace = Duration(seconds: 6);
const List<String> _kCodexModelListResponseKeys = <String>[
  'models',
  'items',
  'data',
  'modelOptions',
  'model_options',
  'availableModels',
  'available_models',
  'modelIds',
  'model_ids',
  'options',
];
const String _kCodexInitPrompt = '''
Please analyze this repository and create or update an AGENTS.md file that acts as a contributor guide for future coding agents.

Include concise, repository-specific guidance for:
- project structure and where important code lives
- build, test, lint, and development commands
- coding conventions and architectural patterns visible in the repo
- testing expectations and any important setup notes

Keep the file practical and avoid generic advice. If AGENTS.md already exists, preserve useful existing guidance and update it with what you learn from the current repository.
''';

mixin _ChatPageCodexMixin on _ChatPageStateBase {
  @override
  Future<void> _refreshCodexStatus() async {
    if (!mounted || _isCodexStatusLoading) return;
    setState(() {
      _isCodexStatusLoading = true;
    });
    try {
      final status = await CodexAppServerService.status();
      if (!mounted) return;
      setState(() {
        _codexStatus = status;
        _isCodexStatusLoading = false;
      });
      if (_activeMode == ChatPageMode.codex) {
        unawaited(_loadCodexModelOptionsWhenReady());
      }
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _codexStatus = CodexStatus.disconnected;
        _isCodexStatusLoading = false;
      });
    }
  }

  @override
  Future<void> _handleCodexTap() async {
    if (_isCodexStatusLoading) return;
    if (_activeMode == ChatPageMode.codex) {
      await _leaveCodexMode();
      return;
    }
    setState(() {
      _isCodexStatusLoading = true;
    });
    CodexStatus status;
    try {
      status = await CodexAppServerService.status();
      if (status.ready && !status.connected) {
        status = await CodexAppServerService.connect();
        unawaited(CodexAppServerService.listThreads());
      }
    } catch (error) {
      status = CodexStatus(
        connected: false,
        ready: false,
        error: error.toString(),
      );
    }
    if (!mounted) return;
    setState(() {
      _codexStatus = status;
      _isCodexStatusLoading = false;
    });
    if (!status.ready) {
      if (status.remoteEnabled) {
        _showSnackBar(
          LegacyTextLocalizer.isEnglish
              ? 'Remote Codex Bridge is unavailable'
              : '远程 Codex Bridge 不可用',
        );
        GoRouterManager.push('/home/codex_setting');
        return;
      }
      GoRouterManager.push('/home/termux_setting?focus=codex');
      return;
    }

    await _showCodexAccountStatus();

    final target = _newCodexThreadTarget();
    if (!mounted) return;
    await _applyConversationThreadTarget(target);
  }

  Future<void> _leaveCodexMode() async {
    _storeDraftForActiveConversationMode();
    await _persistVisibleThreadTargetIfNeeded();
    if (!mounted) return;

    final target = _resolveCodexExitTarget();
    if (!mounted) return;
    await _applyConversationThreadTarget(target);
  }

  ConversationThreadTarget _resolveCodexExitTarget() {
    return _newThreadTargetForConversationMode(ConversationMode.normal);
  }

  @override
  String? _codexRemoteWorkspaceNameForGreeting() {
    if (!_codexStatus.remoteEnabled) {
      return null;
    }
    return _codexLastPathSegment(
      _codexStatus.remoteCwd ?? _codexStatus.cwd ?? '',
    );
  }

  @override
  Future<void> _openCodexRemoteWorkspacePicker() async {
    if (!_codexStatus.remoteEnabled) {
      return;
    }
    CodexLocalConfig config;
    try {
      config = await CodexAppServerService.readLocalConfig();
    } catch (error) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Failed to read Codex config: $error'
            : '读取 Codex 配置失败：$error',
        type: ToastType.error,
      );
      return;
    }
    if (!mounted) return;
    if (!config.remoteEnabled || config.remoteBridgeUrl.trim().isEmpty) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Remote Codex Bridge is not configured'
            : '远程 Codex Bridge 尚未配置',
        type: ToastType.warning,
      );
      return;
    }
    final selected = await showCodexRemoteDirectoryPicker(
      context: context,
      remoteBridgeUrl: config.remoteBridgeUrl,
      remoteBridgeToken: config.remoteBridgeToken,
      initialPath: config.remoteCwd,
    );
    if (!mounted || selected == null || selected.trim().isEmpty) {
      return;
    }
    final nextCwd = selected.trim();
    if (nextCwd == config.remoteCwd.trim()) {
      return;
    }
    try {
      await CodexAppServerService.writeLocalConfig(
        baseUrl: config.baseUrl,
        model: config.model,
        apiKey: config.apiKey,
        remoteEnabled: true,
        remoteBridgeUrl: config.remoteBridgeUrl,
        remoteBridgeToken: config.remoteBridgeToken,
        remoteCwd: nextCwd,
      );
      final status = await CodexAppServerService.status();
      if (!mounted) return;
      setState(() {
        _codexStatus = status;
        _activeCodexThreadId = null;
        _activeCodexTurnId = null;
      });
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Switched Codex workspace to ${_codexLastPathSegment(nextCwd) ?? nextCwd}'
            : '已切换到 ${_codexLastPathSegment(nextCwd) ?? nextCwd}',
        type: ToastType.success,
      );
    } catch (error) {
      if (!mounted) return;
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Failed to switch workspace: $error'
            : '切换工作目录失败：$error',
        type: ToastType.error,
      );
    }
  }

  @override
  Future<void> _prepareRemoteCodexSessionTarget(
    ConversationThreadTarget target,
  ) async {
    final threadId = target.codexThreadId?.trim() ?? '';
    if (threadId.isEmpty) {
      return;
    }
    final runtimeId = _remoteCodexRuntimeId(threadId);
    _activeCodexRemoteRuntimeId = runtimeId;
    _activeCodexThreadId = threadId;
    _activeCodexTurnId = null;
    _currentConversationIdByMode[ChatPageMode.codex] = runtimeId;

    try {
      CodexStatus status = _codexStatus;
      if (!status.connected) {
        status = await CodexAppServerService.connect();
      }
      final response = await CodexAppServerService.resumeThread(
        threadId: threadId,
      );
      if (!mounted) return;
      final resolvedThreadId =
          _asCodexString(response['threadId']) ??
          _asCodexString(_asCodexMap(response['thread'])?['id']) ??
          threadId;
      final conversation = _remoteCodexConversationFromResponse(
        runtimeId: runtimeId,
        response: response,
      );
      _applyRemoteCodexThreadSnapshot(
        response: response,
        fallbackThreadId: resolvedThreadId,
        fallbackRuntimeId: runtimeId,
        fallbackConversation: conversation,
        status: status,
        assumeActive: target.codexThreadActive == true,
      );
      _startRemoteCodexSessionSync(resolvedThreadId);
      _rememberRuntimeUiSnapshot(ChatPageMode.codex);
    } catch (error) {
      if (!mounted) return;
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Failed to load Codex session: $error'
            : '加载 Codex session 失败：$error',
        type: ToastType.error,
      );
    }
  }

  @override
  Future<void> _refreshCodexCommandPreferences() async {
    final conversationId = _currentConversationIdByMode[ChatPageMode.codex];
    final model = _readCodexPreference(
      _kCodexModelPreferenceKey,
      conversationId: conversationId,
    );
    final effort = _readCodexPreference(
      _kCodexReasoningEffortPreferenceKey,
      conversationId: conversationId,
    );
    final collaborationMode = _readCodexPreference(
      _kCodexCollaborationModePreferenceKey,
      conversationId: conversationId,
    );
    if (!mounted) return;
    setState(() {
      _activeCodexModelId = model;
      _activeCodexReasoningEffort = _normalizeCodexReasoningEffort(effort);
      _activeCodexCollaborationMode = collaborationMode;
    });
    if (model == null || effort == null || _codexModelOptions.isEmpty) {
      unawaited(_loadCodexModelOptionsWhenReady());
    }
  }

  @override
  Future<void> _loadCodexModelOptionsWhenReady() async {
    late CodexStatus status;
    try {
      status = await CodexAppServerService.status();
      if (!status.ready) {
        return;
      }
      if (!status.connected) {
        status = await CodexAppServerService.connect();
        unawaited(CodexAppServerService.listThreads());
      }
      _applyRefreshedCodexStatus(status);
    } catch (error) {
      debugPrint('Prepare Codex model options failed: $error');
      return;
    }
    if (!mounted || !status.connected) {
      return;
    }
    final sourceKey = codexModelSourceKey(status);
    if ((_loadedCodexModelSourceKey == sourceKey &&
            _codexModelOptions.isNotEmpty &&
            (_activeCodexModelId ?? '').trim().isNotEmpty &&
            (_activeCodexReasoningEffort ?? '').trim().isNotEmpty) ||
        (_isCodexModelListLoading &&
            _loadingCodexModelSourceKey == sourceKey)) {
      return;
    }
    await _loadCodexModelOptions(force: true);
  }

  @override
  Future<void> _loadCodexModelOptions({bool force = false}) async {
    final statusForRequest = _codexStatus;
    final sourceKey = codexModelSourceKey(statusForRequest);
    if (_isCodexModelListLoading && _loadingCodexModelSourceKey == sourceKey) {
      return;
    }
    if (!force &&
        _loadedCodexModelSourceKey == sourceKey &&
        _codexModelOptions.isNotEmpty &&
        (_activeCodexModelId ?? '').trim().isNotEmpty) {
      return;
    }
    if (!mounted) return;
    final requestId = ++_codexModelListRequestId;
    setState(() {
      _isCodexModelListLoading = true;
      _loadingCodexModelSourceKey = sourceKey;
      _codexModelListError = null;
    });
    try {
      final configSettings = await _readCodexRunSettingsFromServerConfig();
      final useConfiguredApiModelOnly =
          statusForRequest.runtime != 'remote' &&
          !statusForRequest.remoteEnabled &&
          statusForRequest.localAuthMode == CodexLocalAuthMode.api;
      final response = useConfiguredApiModelOnly
          ? const <String, dynamic>{}
          : await CodexAppServerService.listModels();
      final models = useConfiguredApiModelOnly
          ? <String>[
              if ((configSettings.modelId ?? '').trim().isNotEmpty)
                configSettings.modelId!.trim(),
            ]
          : _extractCodexOptionIds(response, _kCodexModelListResponseKeys);
      if (models.isEmpty) {
        debugPrint(
          '[Codex] model/list returned no parseable models: ${jsonEncode(response)}',
        );
      }
      final preferredModel =
          configSettings.modelId ??
          _extractCodexPreferredOptionId(response) ??
          _extractCodexDefaultModelId(response) ??
          (models.isNotEmpty ? models.first : null);
      final conversationId = _currentConversationIdByMode[ChatPageMode.codex];
      final scopedModel = _readCodexPreference(
        _kCodexModelPreferenceKey,
        conversationId: conversationId,
      );
      final activeModel =
          (_loadedCodexModelSourceKey == sourceKey
                  ? _activeCodexModelId
                  : scopedModel)
              ?.trim() ??
          '';
      final effectiveModel = useConfiguredApiModelOnly
          ? preferredModel
          : (activeModel.isNotEmpty ? activeModel : preferredModel);
      final modelOptions = useConfiguredApiModelOnly
          ? models
          : _mergeCodexOptionIds(
              current: effectiveModel,
              preferred: preferredModel,
              options: models,
            );
      final modelDefaultEffort = _extractCodexModelDefaultReasoningEffort(
        response,
        effectiveModel,
      );
      final effortOptions = _mergeCodexReasoningEffortOptions(
        current: configSettings.reasoningEffort ?? modelDefaultEffort,
        options: _extractCodexReasoningEffortOptions(response),
      );
      if (!mounted ||
          !isCurrentCodexModelLoad(
            requestId: requestId,
            activeRequestId: _codexModelListRequestId,
            requestSource: sourceKey,
            currentSource: codexModelSourceKey(_codexStatus),
          )) {
        return;
      }
      setState(() {
        _loadedCodexModelSourceKey = sourceKey;
        _codexModelOptions = modelOptions;
        _activeCodexModelId = effectiveModel;
        if ((_activeCodexReasoningEffort ?? '').trim().isEmpty) {
          _activeCodexReasoningEffort =
              configSettings.reasoningEffort ??
              modelDefaultEffort ??
              (effortOptions.isNotEmpty
                  ? effortOptions.last
                  : _kDefaultCodexReasoningEffort);
        }
        _codexReasoningEffortOptions = effortOptions;
        _codexModelListError = null;
      });
    } catch (error) {
      if (!mounted ||
          !isCurrentCodexModelLoad(
            requestId: requestId,
            activeRequestId: _codexModelListRequestId,
            requestSource: sourceKey,
            currentSource: codexModelSourceKey(_codexStatus),
          )) {
        return;
      }
      setState(() {
        _codexModelListError = error.toString();
      });
    } finally {
      if (mounted && requestId == _codexModelListRequestId) {
        setState(() {
          _isCodexModelListLoading = false;
          _loadingCodexModelSourceKey = null;
        });
      }
    }
  }

  Future<_CodexRunSettingsSnapshot>
  _readCodexRunSettingsFromServerConfig() async {
    try {
      final response = await CodexAppServerService.readConfig();
      return _CodexRunSettingsSnapshot(
        modelId: _extractCodexConfigModelId(response),
        reasoningEffort: _extractCodexConfigReasoningEffort(response),
      );
    } catch (error) {
      debugPrint('Read Codex config run settings failed: $error');
      return const _CodexRunSettingsSnapshot();
    }
  }

  @override
  Future<void> _loadCodexCollaborationModes({bool force = false}) async {
    if (_isCodexCollaborationModeListLoading) {
      return;
    }
    if (!force && _codexCollaborationModes.isNotEmpty) {
      return;
    }
    if (!mounted) return;
    setState(() {
      _isCodexCollaborationModeListLoading = true;
      _codexCollaborationModeListError = null;
    });
    try {
      final response = await CodexAppServerService.listCollaborationModes();
      final modes = _extractCodexOptionIds(response, const <String>[
        'collaborationModes',
        'modes',
        'items',
        'data',
      ]);
      if (!mounted) return;
      setState(() {
        _codexCollaborationModes = modes;
        _isCodexCollaborationModeListLoading = false;
        _codexCollaborationModeListError = null;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _isCodexCollaborationModeListLoading = false;
        _codexCollaborationModeListError = error.toString();
      });
    }
  }

  @override
  Future<void> _selectCodexModel(
    String modelId, {
    bool clearComposer = true,
  }) async {
    final normalized = modelId.trim();
    if (normalized.isEmpty || normalized.startsWith('/')) {
      return;
    }
    if (!mounted) return;
    setState(() {
      _activeCodexModelId = normalized;
    });
    await _writeCodexPreference(_kCodexModelPreferenceKey, normalized);
    if (clearComposer) {
      _messageController.clear();
      _hideSlashCommandPanel();
    }
  }

  @override
  Future<void> _selectCodexReasoningEffort(String effort) async {
    final normalized = _normalizeCodexReasoningEffort(effort);
    if (normalized == null) {
      return;
    }
    if (!mounted) return;
    setState(() {
      _activeCodexReasoningEffort = normalized;
      _codexReasoningEffortOptions = _mergeCodexReasoningEffortOptions(
        current: normalized,
        options: _codexReasoningEffortOptions,
      );
    });
    await _writeCodexPreference(
      _kCodexReasoningEffortPreferenceKey,
      normalized,
    );
  }

  @override
  Future<void> _activateCodexPlanMode({
    bool persistOnly = false,
    bool dismissPanel = true,
  }) async {
    await _loadCodexCollaborationModes();
    final planMode = _resolveCodexPlanMode(_codexCollaborationModes);
    if (!mounted) return;
    setState(() {
      _activeCodexCollaborationMode = planMode;
    });
    await _writeCodexPreference(
      _kCodexCollaborationModePreferenceKey,
      planMode,
    );
    if (!persistOnly && dismissPanel) {
      _messageController.clear();
      _hideSlashCommandPanel();
    }
  }

  @override
  Future<void> _deactivateCodexPlanMode({bool dismissPanel = true}) async {
    if (!mounted) return;
    setState(() {
      _activeCodexCollaborationMode = null;
    });
    await _clearCodexPreference(_kCodexCollaborationModePreferenceKey);
    if (dismissPanel) {
      _messageController.clear();
      _hideSlashCommandPanel();
    }
  }

  Future<void> _toggleCodexPlanMode({bool dismissPanel = true}) {
    return _isCodexPlanMode(_activeCodexCollaborationMode)
        ? _deactivateCodexPlanMode(dismissPanel: dismissPanel)
        : _activateCodexPlanMode(dismissPanel: dismissPanel);
  }

  void exitPlanMode() {
    _deactivateCodexPlanMode();
  }

  void resetToNormalMode() {
    if (_isCodexPlanMode(_activeCodexCollaborationMode)) {
      exitPlanMode();
    }
    _messageController.clear();
  }

  void _syncCodexCollaborationModeFromServer(String? mode) {
    final normalized = mode?.trim();
    if (normalized == null || normalized.isEmpty) {
      return;
    }
    if (_isCodexPlanMode(normalized)) {
      if (_activeCodexCollaborationMode == normalized) {
        return;
      }
      _activeCodexCollaborationMode = normalized;
      unawaited(
        _writeCodexPreference(
          _kCodexCollaborationModePreferenceKey,
          normalized,
        ),
      );
      return;
    }
    if (_activeCodexCollaborationMode == null) {
      return;
    }
    _activeCodexCollaborationMode = null;
    unawaited(_clearCodexPreference(_kCodexCollaborationModePreferenceKey));
  }

  void _autoDeactivateCodexPlanModeAfterTurn() {
    if (!_isCodexPlanMode(_activeCodexCollaborationMode)) {
      return;
    }
    _activeCodexCollaborationMode = null;
    unawaited(_clearCodexPreference(_kCodexCollaborationModePreferenceKey));
  }

  @override
  Future<void> _handleCodexSlashCommandCardSelected(
    Map<String, dynamic> cardData,
  ) async {
    final command = (cardData['toolTitle'] ?? cardData['displayName'] ?? '')
        .toString()
        .trim();
    if (command.isEmpty) {
      return;
    }
    if (command == '/model') {
      _messageController.value = const TextEditingValue(
        text: '/model ',
        selection: TextSelection.collapsed(offset: 7),
      );
      _requestComposerFocus();
      _handleSlashCommandInput();
      await _loadCodexModelOptionsWhenReady();
      return;
    }
    if (command == '/review') {
      await _startCodexReviewCommand();
      return;
    }
    if (command == '/init') {
      await _executeCodexInitCommand();
      return;
    }
    if (command == '/plan') {
      await _toggleCodexPlanMode(dismissPanel: false);
      return;
    }
    if (_resolveSlashCommandPanelRoute(_messageController.text) ==
        _SlashCommandPanelRoute.codexModel) {
      await _selectCodexModel(command);
    }
  }

  @override
  Future<bool> _tryHandleCodexSlashCommand(
    String messageText, {
    List<Map<String, dynamic>> attachments = const [],
  }) async {
    final trimmed = messageText.trim();
    final intent = resolveCodexSlashSubmitIntent(trimmed);
    switch (intent.kind) {
      case CodexSlashSubmitKind.none:
        return false;
      case CodexSlashSubmitKind.openModelPicker:
        _triggerSlashCommandPanel();
        await _loadCodexModelOptionsWhenReady();
        return true;
      case CodexSlashSubmitKind.selectModel:
        await _selectCodexModel(intent.value ?? '');
        return true;
      case CodexSlashSubmitKind.startReview:
        _messageController.clear();
        _hideSlashCommandPanel();
        await _startCodexReviewCommand();
        return true;
      case CodexSlashSubmitKind.startInit:
        _messageController.clear();
        _hideSlashCommandPanel();
        await _executeCodexInitCommand();
        return true;
      case CodexSlashSubmitKind.togglePlan:
        await _toggleCodexPlanMode();
        return true;
      case CodexSlashSubmitKind.startPlan:
        _messageController.clear();
        _hideSlashCommandPanel();
        await _activateCodexPlanMode(persistOnly: true);
        await _startCodexTurnCommand(
          displayText: trimmed,
          actualText: intent.value ?? '',
          attachments: attachments,
          collaborationModeOverride:
              _activeCodexCollaborationMode ?? _resolveCodexPlanMode(const []),
        );
        return true;
      case CodexSlashSubmitKind.unsupported:
        _messageController.clear();
        _hideSlashCommandPanel();
        _showSnackBar(
          LegacyTextLocalizer.isEnglish
              ? 'Unsupported Codex command'
              : '不支持的 Codex 命令',
        );
        return true;
    }
  }

  @override
  Future<void> _executeCodexInitCommand() async {
    await _startCodexTurnCommand(
      displayText: '/init',
      actualText: _kCodexInitPrompt,
    );
  }

  @override
  Future<void> _startCodexReviewCommand() async {
    if (_isAiResponding) {
      return;
    }
    _inputFocusNode.unfocus();
    _messageController.clear();
    _hideSlashCommandPanel();
    late CodexStatus status;
    try {
      status = await _refreshConnectedCodexStatus();
    } catch (error) {
      if (mounted) {
        handleAgentError('Codex review 启动失败: $error');
      }
      return;
    }
    final messageIds = addUserMessage('/review');
    final remoteCodex = codexModelSourceKey(status) == 'remote';
    int? conversationId;
    if (remoteCodex) {
      conversationId = _ensureRemoteCodexRuntimeForCurrentMessages();
    } else {
      try {
        await _ensureActiveConversationReadyForStreaming();
      } catch (_) {
        if (mounted) {
          _currentDispatchTaskId = messageIds.aiMessageId;
          handleAgentError('Conversation setup failed. Please retry.');
        }
        return;
      }
      conversationId = _currentConversationId;
      if (conversationId == null) {
        if (mounted) {
          _currentDispatchTaskId = messageIds.aiMessageId;
          handleAgentError('Conversation setup failed. Please retry.');
        }
        return;
      }
    }

    final resolvedConversationId = conversationId;
    _syncRuntimeSnapshotForMode(_activeMode);
    _currentDispatchTaskId = messageIds.aiMessageId;
    _runtimeCoordinator.registerTask(
      taskId: messageIds.aiMessageId,
      conversationId: resolvedConversationId,
      mode: _modeKey(_activeMode),
    );
    if (!remoteCodex) {
      await ConversationHistoryService.saveConversationMessages(
        resolvedConversationId,
        List<ChatMessageModel>.from(_messages),
        mode: ConversationMode.codex,
      );
    }

    try {
      final reviewModel = await _resolveCodexRequestModel(status);
      final response = await CodexAppServerService.startReview(
        conversationId: remoteCodex ? null : resolvedConversationId,
        threadId: _activeCodexThreadId,
        approvalPolicy: _codexPermissionMode.approvalPolicy,
        approvalsReviewer: _codexPermissionMode.approvalsReviewer,
        sandboxPolicy: _codexPermissionMode.sandboxPolicy,
        model: reviewModel,
        effort: _activeCodexReasoningEffort,
        collaborationMode: _activeCodexCollaborationMode,
      );
      final resolvedThreadId = _asCodexString(response['threadId']);
      if (resolvedThreadId != null && remoteCodex) {
        _activateRemoteCodexRuntimeForThread(resolvedThreadId);
        _startRemoteCodexSessionSync(resolvedThreadId);
      }
      _activeCodexThreadId = resolvedThreadId ?? _activeCodexThreadId;
      _activeCodexTurnId =
          _asCodexString(response['turnId']) ?? _activeCodexTurnId;
      if (!remoteCodex) {
        await _persistVisibleThreadTargetIfNeeded();
      }
      await _writeCodexCommandPreferencesForCurrentConversation();
    } catch (error) {
      if (!mounted) return;
      handleAgentError('Codex review 启动失败: $error');
    }
  }

  Future<void> _startCodexTurnCommand({
    required String displayText,
    required String actualText,
    List<Map<String, dynamic>> attachments = const [],
    String? collaborationModeOverride,
  }) async {
    if (_isAiResponding) {
      return;
    }
    _inputFocusNode.unfocus();
    _messageController.clear();
    _hideSlashCommandPanel();
    final messageIds = addUserMessage(displayText, attachments: attachments);
    await _sendCodexMessage(
      messageIds.aiMessageId,
      actualText,
      attachments: attachments,
      collaborationModeOverride: collaborationModeOverride,
    );
  }

  String? _readCodexPreference(String kind, {int? conversationId}) {
    try {
      if (conversationId != null) {
        final scoped = StorageService.getString(
          _codexPreferenceKey(kind, conversationId: conversationId),
          defaultValue: '',
        );
        final normalizedScoped = scoped?.trim() ?? '';
        if (normalizedScoped.isNotEmpty) {
          return normalizedScoped;
        }
      }
      final global = StorageService.getString(
        _codexPreferenceKey(kind),
        defaultValue: '',
      );
      final normalizedGlobal = global?.trim() ?? '';
      return normalizedGlobal.isEmpty ? null : normalizedGlobal;
    } catch (error) {
      debugPrint('Read Codex command preference failed: $error');
      return null;
    }
  }

  Future<void> _writeCodexPreference(String kind, String value) async {
    final normalized = value.trim();
    if (normalized.isEmpty) {
      return;
    }
    await StorageService.setString(_codexPreferenceKey(kind), normalized);
    final conversationId = _currentConversationIdByMode[ChatPageMode.codex];
    if (conversationId != null) {
      await StorageService.setString(
        _codexPreferenceKey(kind, conversationId: conversationId),
        normalized,
      );
    }
  }

  Future<void> _clearCodexPreference(String kind) async {
    await StorageService.remove(_codexPreferenceKey(kind));
    final conversationId = _currentConversationIdByMode[ChatPageMode.codex];
    if (conversationId != null) {
      await StorageService.remove(
        _codexPreferenceKey(kind, conversationId: conversationId),
      );
    }
  }

  Future<void> _writeCodexCommandPreferencesForCurrentConversation() async {
    final modelId = _activeCodexModelId?.trim();
    if (modelId != null && modelId.isNotEmpty) {
      await _writeCodexPreference(_kCodexModelPreferenceKey, modelId);
    }
    final effort = _activeCodexReasoningEffort?.trim();
    if (effort != null && effort.isNotEmpty) {
      await _writeCodexPreference(_kCodexReasoningEffortPreferenceKey, effort);
    }
    final collaborationMode = _activeCodexCollaborationMode?.trim();
    if (collaborationMode != null && collaborationMode.isNotEmpty) {
      await _writeCodexPreference(
        _kCodexCollaborationModePreferenceKey,
        collaborationMode,
      );
    }
  }

  String _codexPreferenceKey(String kind, {int? conversationId}) {
    final source = kind == _kCodexModelPreferenceKey
        ? '.${codexModelSourceKey(_codexStatus)}'
        : '';
    if (conversationId == null) {
      return '$_kCodexPreferenceStoragePrefix.$kind$source.global';
    }
    return '$_kCodexPreferenceStoragePrefix.$kind$source.conversation.$conversationId';
  }

  @override
  void _handleCodexAppServerEvent(Map<String, dynamic> event) {
    final diagnosticMethod = _diagnosticEventMethod(event);
    _codexEventDiagnosticCounter.update(
      diagnosticMethod,
      (count) => count + 1,
      ifAbsent: () => 1,
    );
    // Log every event individually so the user can `adb logcat -s flutter:V`
    // (or `flutter logs`) during a Codex turn and verify exactly which
    // app-server methods are reaching the Flutter side. If lines like
    //   [Codex/E] item/started:commandExecution
    //   [Codex/E] item/completed:commandExecution
    // do not show up while pwd/ls/cat run, the events are being dropped
    // upstream (codex app-server -> codex-bridge -> Kotlin -> EventChannel).
    debugPrint('[Codex/E] $diagnosticMethod');
    final totalEvents = _codexEventDiagnosticCounter.values.fold<int>(
      0,
      (sum, count) => sum + count,
    );
    if (totalEvents % 32 == 0 || diagnosticMethod == 'turn/completed') {
      debugPrint(
        '[Codex/E] === counters @$totalEvents === '
        '${_codexEventDiagnosticCounter.entries.map((e) => '${e.key}:${e.value}').join(', ')}',
      );
    }
    final remoteCodex = _isRemoteCodexConfigured();
    final eventThreadId = _codexEventThreadId(event);
    final explicitConversationId = _asCodexInt(event['conversationId']);
    final mappedRemoteConversationId = remoteCodex && eventThreadId != null
        ? _remoteCodexRuntimeId(eventThreadId)
        : null;
    final shouldPromoteRemoteEvent =
        remoteCodex &&
        eventThreadId != null &&
        _shouldPromoteRemoteCodexEventToVisibleThread(
          threadId: eventThreadId,
          runtimeId: mappedRemoteConversationId!,
        );
    final conversationId =
        explicitConversationId ??
        (shouldPromoteRemoteEvent
            ? _activateRemoteCodexRuntimeForThread(eventThreadId)
            : mappedRemoteConversationId) ??
        _currentConversationIdByMode[ChatPageMode.codex];
    if (conversationId == null) {
      debugPrint(
        '[Codex] dropping $diagnosticMethod — no conversationId '
        '(remoteCodex=$remoteCodex, eventThreadId=$eventThreadId)',
      );
      return;
    }
    if (remoteCodex && eventThreadId != null && !shouldPromoteRemoteEvent) {
      _ensureRemoteCodexRuntimeForThread(eventThreadId);
    }
    final isVisibleConversation =
        conversationId == _currentConversationIdByMode[ChatPageMode.codex];
    final result = _runtimeCoordinator.applyCodexEvent(
      conversationId: conversationId,
      event: event,
      conversation: isVisibleConversation
          ? _currentConversationByMode[ChatPageMode.codex]
          : null,
    );
    final threadId = _asCodexString(event['threadId']) ?? result.threadId;
    final turnId = _asCodexString(event['turnId']) ?? result.turnId;
    if (isVisibleConversation && (threadId != null || turnId != null)) {
      _activeCodexThreadId = threadId ?? _activeCodexThreadId;
      _activeCodexTurnId = turnId ?? _activeCodexTurnId;
    }
    if (isVisibleConversation) {
      _syncCodexCollaborationModeFromServer(result.collaborationMode);
    }
    if (isVisibleConversation && result.method == 'turn/completed') {
      final completedTurnId = result.turnId;
      final completedPlanTurn =
          completedTurnId != null && _codexPlanTurnIds.remove(completedTurnId);
      if (completedPlanTurn ||
          (completedTurnId == null &&
              _isCodexPlanMode(_activeCodexCollaborationMode))) {
        _autoDeactivateCodexPlanModeAfterTurn();
      }
      _activeCodexTurnId = null;
    }
    if (isVisibleConversation) {
      final runtime = _runtimeCoordinator.runtimeFor(
        conversationId: conversationId,
        mode: kChatRuntimeModeCodex,
      );
      if (runtime != null) {
        _syncCodexModeStateFromRuntime(runtime);
        if (!runtime.isAiResponding) {
          _activeCodexTurnId = null;
        }
      }
    }
    if (!result.handled &&
        result.method != 'codex/stderr' &&
        result.method != 'codex/parseError') {
      debugPrint('[Codex] unhandled app-server event: ${jsonEncode(event)}');
    }
    if (_activeMode == ChatPageMode.codex && mounted && isVisibleConversation) {
      setState(() {});
    }
  }

  @override
  Future<void> _sendCodexMessage(
    String aiMessageId,
    String messageText, {
    List<Map<String, dynamic>> attachments = const [],
    String? modelOverride,
    String? collaborationModeOverride,
  }) async {
    late CodexStatus status;
    try {
      status = await _refreshConnectedCodexStatus();
    } catch (error) {
      if (mounted) {
        _currentDispatchTaskId = aiMessageId;
        handleAgentError('Codex 连接失败: $error');
      }
      return;
    }
    final remoteCodex = codexModelSourceKey(status) == 'remote';
    int? conversationId;
    if (remoteCodex) {
      conversationId = _ensureRemoteCodexRuntimeForCurrentMessages();
    } else {
      try {
        await _ensureActiveConversationReadyForStreaming();
      } catch (_) {
        if (mounted) {
          _currentDispatchTaskId = aiMessageId;
          handleAgentError('Conversation setup failed. Please retry.');
        }
        return;
      }
      conversationId = _currentConversationId;
      if (conversationId == null) {
        if (mounted) {
          _currentDispatchTaskId = aiMessageId;
          handleAgentError('Conversation setup failed. Please retry.');
        }
        return;
      }
    }

    final resolvedConversationId = conversationId;
    _syncRuntimeSnapshotForMode(_activeMode);
    _currentDispatchTaskId = aiMessageId;
    _runtimeCoordinator.registerTask(
      taskId: aiMessageId,
      conversationId: resolvedConversationId,
      mode: _modeKey(_activeMode),
    );
    if (!remoteCodex) {
      await ConversationHistoryService.saveConversationMessages(
        resolvedConversationId,
        List<ChatMessageModel>.from(_messages),
        mode: ConversationMode.codex,
      );
    }

    final collaborationModeForTurn =
        collaborationModeOverride ?? _activeCodexCollaborationMode;
    final turnUsesPlanMode = _isCodexPlanMode(collaborationModeForTurn);
    try {
      final turnModel = await _resolveCodexRequestModel(
        status,
        overrideModel: modelOverride,
      );
      final response = await CodexAppServerService.startTurn(
        conversationId: remoteCodex ? null : resolvedConversationId,
        threadId: _activeCodexThreadId,
        text: messageText,
        attachments: attachments,
        approvalPolicy: _codexPermissionMode.approvalPolicy,
        approvalsReviewer: _codexPermissionMode.approvalsReviewer,
        sandboxPolicy: _codexPermissionMode.sandboxPolicy,
        model: turnModel,
        effort: _activeCodexReasoningEffort,
        collaborationMode: collaborationModeForTurn,
      );
      final resolvedThreadId = _asCodexString(response['threadId']);
      if (resolvedThreadId != null && remoteCodex) {
        _activateRemoteCodexRuntimeForThread(resolvedThreadId);
        _startRemoteCodexSessionSync(resolvedThreadId);
      }
      _activeCodexThreadId = resolvedThreadId ?? _activeCodexThreadId;
      _activeCodexTurnId =
          _asCodexString(response['turnId']) ?? _activeCodexTurnId;
      if (turnUsesPlanMode && _activeCodexTurnId != null) {
        _codexPlanTurnIds.add(_activeCodexTurnId!);
      }
      final localConversationId = _asCodexInt(response['conversationId']);
      if (!remoteCodex &&
          localConversationId != null &&
          localConversationId !=
              _currentConversationIdByMode[ChatPageMode.codex]) {
        if (_currentConversationIdByMode[ChatPageMode.codex] == null) {
          _currentConversationIdByMode[ChatPageMode.codex] =
              localConversationId;
          await _prepareConversationModeState(
            ChatPageMode.codex,
            ConversationThreadTarget.existing(
              conversationId: localConversationId,
              mode: ConversationMode.codex,
            ),
          );
        } else {
          debugPrint(
            '[Codex] keeping active conversation ${_currentConversationIdByMode[ChatPageMode.codex]} '
            'instead of mismatched native conversation $localConversationId',
          );
        }
      }
      if (!remoteCodex) {
        await _persistVisibleThreadTargetIfNeeded();
      }
      await _writeCodexCommandPreferencesForCurrentConversation();
    } catch (error) {
      if (!mounted) return;
      handleAgentError('Codex 启动失败: $error');
    }
  }

  @override
  Future<void> _interruptCodexTurn() async {
    final conversationId = _currentConversationIdByMode[ChatPageMode.codex];
    if (conversationId == null && _activeCodexThreadId == null) {
      return;
    }
    try {
      await CodexAppServerService.interruptTurn(
        conversationId: _isRemoteCodexConfigured() ? null : conversationId,
        threadId: _activeCodexThreadId,
        turnId: _activeCodexTurnId,
      );
    } catch (error) {
      debugPrint('Codex interrupt failed: $error');
    }
  }

  void _startRemoteCodexSessionSync(String threadId) {
    final normalizedThreadId = threadId.trim();
    if (normalizedThreadId.isEmpty) {
      return;
    }
    if (_remoteCodexSessionSyncThreadId == normalizedThreadId &&
        _remoteCodexSessionSyncTimer != null) {
      return;
    }
    _remoteCodexSessionSyncThreadId = normalizedThreadId;
    _remoteCodexSessionSyncSignature = '';
    _remoteCodexSessionSyncTimer?.cancel();
    _remoteCodexSessionSyncTimer = Timer.periodic(
      const Duration(seconds: 2),
      (_) => unawaited(_syncRemoteCodexSessionSnapshot()),
    );
    unawaited(_syncRemoteCodexSessionSnapshot());
  }

  @override
  void _stopRemoteCodexSessionSync() {
    _remoteCodexSessionSyncTimer?.cancel();
    _remoteCodexSessionSyncTimer = null;
    _remoteCodexSessionSyncInFlight = false;
    _remoteCodexSessionSyncThreadId = null;
    _remoteCodexSessionSyncSignature = '';
    _remoteCodexActivityThreadId = null;
    _remoteCodexActivityContentSignature = '';
    _remoteCodexLastContentChangeAtMs = null;
  }

  bool _inferRemoteCodexSnapshotActive({
    required String threadId,
    required Map<String, dynamic> response,
    required _CodexThreadActivityState activity,
    required bool previousActive,
    required bool assumeActive,
    required String? directActiveTurnId,
  }) {
    if (!_isRemoteCodexConfigured()) {
      return false;
    }

    final nowMs = DateTime.now().millisecondsSinceEpoch;
    if (_remoteCodexActivityThreadId != threadId) {
      _remoteCodexActivityThreadId = threadId;
      _remoteCodexActivityContentSignature = '';
      _remoteCodexLastContentChangeAtMs = null;
    }

    final contentSignature = _codexThreadContentSignature(response);
    final firstObservation = _remoteCodexActivityContentSignature.isEmpty;
    final contentChanged =
        contentSignature.isNotEmpty &&
        contentSignature != _remoteCodexActivityContentSignature;
    if (contentSignature.isNotEmpty && contentChanged) {
      _remoteCodexActivityContentSignature = contentSignature;
      _remoteCodexLastContentChangeAtMs = nowMs;
    }

    if (directActiveTurnId != null || activity.active) {
      _remoteCodexLastContentChangeAtMs = nowMs;
      return true;
    }

    final looksExternallyActive = _codexLatestTurnLooksExternallyActive(
      response,
    );
    if (activity.known && !activity.active) {
      // Caller hint wins over Kotlin's authoritative-but-stale active=false:
      // when the user opens a session that the remote codex had already been
      // working on before this client connected, Kotlin's activeTurnsByThreadId
      // is empty so it injects active=false even though codex is in fact still
      // streaming. Trust assumeActive (sourced from the sessions list's
      // session.active flag) for this initial observation.
      if (assumeActive) {
        _remoteCodexLastContentChangeAtMs ??= nowMs;
        return true;
      }
      if (!firstObservation && contentChanged && looksExternallyActive) {
        _remoteCodexLastContentChangeAtMs = nowMs;
        return true;
      }
      final lastChangeAt = _remoteCodexLastContentChangeAtMs;
      if (previousActive && looksExternallyActive && lastChangeAt != null) {
        final ageMs = nowMs - lastChangeAt;
        if (ageMs <= _remoteCodexExternalActiveGrace.inMilliseconds) {
          return true;
        }
      }
      _remoteCodexLastContentChangeAtMs = null;
      return false;
    }

    if (assumeActive) {
      _remoteCodexLastContentChangeAtMs ??= nowMs;
      return true;
    }

    if (!firstObservation && contentChanged && looksExternallyActive) {
      _remoteCodexLastContentChangeAtMs = nowMs;
      return true;
    }

    final lastChangeAt = _remoteCodexLastContentChangeAtMs;
    if (previousActive && lastChangeAt != null) {
      final ageMs = nowMs - lastChangeAt;
      if (ageMs <= _remoteCodexExternalActiveGrace.inMilliseconds) {
        return true;
      }
    }

    return false;
  }

  Future<void> _syncRemoteCodexSessionSnapshot() async {
    if (_remoteCodexSessionSyncInFlight) {
      return;
    }
    final threadId = _remoteCodexSessionSyncThreadId?.trim() ?? '';
    if (threadId.isEmpty ||
        !mounted ||
        _activeConversationMode != ChatPageMode.codex ||
        !_isRemoteCodexConfigured() ||
        _activeCodexThreadId?.trim() != threadId) {
      return;
    }
    _remoteCodexSessionSyncInFlight = true;
    try {
      final response = await _readRemoteCodexThreadSnapshot(threadId);
      if (!mounted ||
          _remoteCodexSessionSyncThreadId != threadId ||
          _activeCodexThreadId?.trim() != threadId) {
        return;
      }
      _applyRemoteCodexThreadSnapshot(
        response: response,
        fallbackThreadId: threadId,
        fromPoll: true,
      );
    } catch (error) {
      debugPrint('Remote Codex session sync failed: $error');
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
      return await CodexAppServerService.readThread(threadId: threadId);
    } catch (error) {
      debugPrint('Codex thread/read failed, falling back to resume: $error');
      return CodexAppServerService.resumeThread(threadId: threadId);
    }
  }

  void _applyRemoteCodexThreadSnapshot({
    required Map<String, dynamic> response,
    required String fallbackThreadId,
    int? fallbackRuntimeId,
    List<ChatMessageModel>? fallbackMessages,
    ConversationModel? fallbackConversation,
    CodexStatus? status,
    bool fromPoll = false,
    bool assumeActive = false,
  }) {
    final resolvedThreadId =
        _asCodexString(response['threadId']) ??
        _asCodexString(_asCodexMap(response['thread'])?['id']) ??
        fallbackThreadId;
    if (resolvedThreadId.isEmpty) {
      return;
    }
    final runtimeId =
        fallbackRuntimeId ?? _remoteCodexRuntimeId(resolvedThreadId);
    final runtime = _runtimeCoordinator.runtimeFor(
      conversationId: runtimeId,
      mode: kChatRuntimeModeCodex,
    );
    final activity = _codexThreadActivityFromResponse(response);
    final previousActive = runtime?.isAiResponding ?? false;
    final directActiveTurnId = _codexActiveTurnIdFromThreadResponse(response);
    final inferredRemoteActive = _inferRemoteCodexSnapshotActive(
      threadId: resolvedThreadId,
      response: response,
      activity: activity,
      previousActive: previousActive,
      assumeActive: assumeActive,
      directActiveTurnId: directActiveTurnId,
    );
    final snapshotIsAiResponding =
        directActiveTurnId != null || activity.active || inferredRemoteActive;
    // The snapshot makes a definitive "no active turn" statement only when
    // BOTH Kotlin's bookkeeping AND the response payload agree: Kotlin
    // injects active=false (activeTurnsByThreadId dropped this thread after
    // turn/completed, thread/closed, status/changed inactive, or a terminal
    // error), AND no turn in the response still looks externally active.
    //
    // The looksExternallyActive guard matters for the cold-open path: if a
    // user opens a session that the remote codex was already working on,
    // Kotlin never saw turn/started so it injects active=false — yet the
    // response itself can still surface an in-progress latest turn. Without
    // this guard, the snapshot would wrongfully cancel out the assumeActive
    // hint (and later, the reducer's runtime active set by push events).
    final snapshotKnowsInactive =
        directActiveTurnId == null &&
        activity.known &&
        !activity.active &&
        !_codexLatestTurnLooksExternallyActive(response);
    // Otherwise floor against the reducer's runtime state. Snapshot polling
    // runs every 2s and would otherwise downgrade isAiResponding between
    // reasoning deltas when codex doesn't surface a "running" status in
    // thread/read.
    final isAiResponding =
        snapshotIsAiResponding || (previousActive && !snapshotKnowsInactive);
    final activeTurnId = isAiResponding
        ? (directActiveTurnId ??
              _codexLatestTurnIdFromThreadResponse(response) ??
              runtime?.currentDispatchTaskId ??
              runtime?.lastAgentTaskId ??
              _activeCodexTurnId)
        : null;
    final activeTaskId = isAiResponding
        ? (activeTurnId ??
              runtime?.currentDispatchTaskId ??
              runtime?.lastAgentTaskId ??
              'remote-codex-$resolvedThreadId')
        : null;
    final hasTurns = _codexThreadResponseHasTurns(response);
    final existingMessages = List<ChatMessageModel>.from(
      runtime?.messages ??
          _messagesByMode[ChatPageMode.codex] ??
          const <ChatMessageModel>[],
    );
    final snapshotMessages = hasTurns
        ? _codexMessagesFromThreadResponse(
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
    final signature = _remoteCodexSnapshotSignature(
      threadId: resolvedThreadId,
      messages: messages,
      conversation: conversation,
      isAiResponding: isAiResponding,
      activeTaskId: activeTaskId,
    );
    if (fromPoll && signature == _remoteCodexSessionSyncSignature) {
      return;
    }
    _remoteCodexSessionSyncSignature = signature;

    if (!mounted) {
      return;
    }
    // Detect reducer push-driven streaming. When push events have populated
    // currentAiMessages / currentThinkingMessages on the runtime, the 2s poll
    // must not overwrite isAiResponding / dispatch ids / streaming buffers —
    // otherwise the timeline flips to isActive=false for one frame between
    // each tick and the codex run group visibly collapses-then-expands while
    // codex is still outputting (the symptom the user reported).
    final hasLivePushStreaming =
        runtime != null &&
        (runtime.currentAiMessages.isNotEmpty ||
            runtime.currentThinkingMessages.isNotEmpty ||
            runtime.messages.any(_isPendingCodexRequestMessage));
    final preserveLiveStreamingState = fromPoll && hasLivePushStreaming;
    setState(() {
      _activeCodexRemoteRuntimeId = runtimeId;
      _activeCodexThreadId = resolvedThreadId;
      if (!preserveLiveStreamingState) {
        _activeCodexTurnId = activeTurnId;
      }
      if (status != null) {
        _codexStatus = status;
      }
      _currentConversationIdByMode[ChatPageMode.codex] = runtimeId;
      _currentConversationByMode[ChatPageMode.codex] = conversation;
      if (!preserveLiveStreamingState) {
        _isAiRespondingByMode[ChatPageMode.codex] = isAiResponding;
        _isExecutingTaskByMode[ChatPageMode.codex] = isAiResponding;
        _isDeepThinkingByMode[ChatPageMode.codex] = isAiResponding;
        _currentThinkingStageByMode[ChatPageMode.codex] = isAiResponding
            ? ThinkingStage.thinking.value
            : ThinkingStage.complete.value;
        _currentDispatchTaskIdByMode[ChatPageMode.codex] = activeTaskId;
      }
      _messagesByMode[ChatPageMode.codex]!
        ..clear()
        ..addAll(messages);
      _hasMoreMessagesByMode[ChatPageMode.codex] = false;
      _messageOffsetByMode[ChatPageMode.codex] = messages.length;
    });
    _runtimeCoordinator.ensureEphemeralRuntime(
      conversationId: runtimeId,
      mode: kChatRuntimeModeCodex,
      initialMessages: messages,
      conversation: conversation,
      initialChatIslandDisplayLayer: ChatIslandDisplayLayer.mode,
    );
    _runtimeCoordinator.replaceConversationSnapshot(
      conversationId: runtimeId,
      mode: kChatRuntimeModeCodex,
      messages: messages,
      conversation: conversation,
      isAiResponding: isAiResponding,
      isExecutingTask: isAiResponding,
      isDeepThinking: isAiResponding,
      deepThinkingContent: runtime?.deepThinkingContent ?? '',
      currentDispatchTaskId: activeTaskId,
      currentThinkingStage: isAiResponding
          ? ThinkingStage.thinking.value
          : ThinkingStage.complete.value,
      lastAgentTaskId: activeTaskId,
      chatIslandDisplayLayer: ChatIslandDisplayLayer.mode,
      preserveLiveStreamingState: preserveLiveStreamingState,
    );
    if (activeTaskId != null) {
      _runtimeCoordinator.registerTask(
        taskId: activeTaskId,
        conversationId: runtimeId,
        mode: kChatRuntimeModeCodex,
      );
    }
    final updatedRuntime = _runtimeCoordinator.runtimeFor(
      conversationId: runtimeId,
      mode: kChatRuntimeModeCodex,
    );
    if (updatedRuntime != null) {
      _syncCodexModeStateFromRuntime(updatedRuntime);
    }
  }

  void _syncCodexModeStateFromRuntime(ChatConversationRuntimeState runtime) {
    _isAiRespondingByMode[ChatPageMode.codex] = runtime.isAiResponding;
    _isContextCompressingByMode[ChatPageMode.codex] =
        runtime.isContextCompressing;
    _isCheckingExecutableTaskByMode[ChatPageMode.codex] =
        runtime.isCheckingExecutableTask;
    _currentAiMessagesByMode[ChatPageMode.codex]!
      ..clear()
      ..addAll(runtime.currentAiMessages);
    _deepThinkingContentByMode[ChatPageMode.codex] =
        runtime.deepThinkingContent;
    _isDeepThinkingByMode[ChatPageMode.codex] = runtime.isDeepThinking;
    _currentDispatchTaskIdByMode[ChatPageMode.codex] =
        runtime.currentDispatchTaskId;
    _currentThinkingStageByMode[ChatPageMode.codex] =
        runtime.currentThinkingStage;
    _isInputAreaVisibleByMode[ChatPageMode.codex] = runtime.isInputAreaVisible;
    _isExecutingTaskByMode[ChatPageMode.codex] = runtime.isExecutingTask;
    _currentConversationByMode[ChatPageMode.codex] = runtime.conversation;
    _chatIslandDisplayLayerByMode[ChatPageMode.codex] =
        runtime.chatIslandDisplayLayer;
    _lastAgentToolTypeByMode[ChatPageMode.codex] = runtime.lastAgentToolType;
    _browserSessionSnapshotByMode[ChatPageMode.codex] =
        runtime.browserSessionSnapshot;
  }

  bool _isRemoteCodexConfigured() {
    final runtime = _codexStatus.runtime?.trim();
    return runtime == 'remote' || _codexStatus.remoteEnabled;
  }

  int _ensureRemoteCodexRuntimeForCurrentMessages() {
    final currentId = _currentConversationIdByMode[ChatPageMode.codex];
    if (currentId != null &&
        _runtimeCoordinator.isEphemeralRuntime(
          conversationId: currentId,
          mode: kChatRuntimeModeCodex,
        )) {
      return currentId;
    }
    final runtimeId = _activeCodexThreadId?.trim().isNotEmpty == true
        ? _remoteCodexRuntimeId(_activeCodexThreadId!)
        : (_activeCodexRemoteRuntimeId ??
              _remoteCodexRuntimeId(
                'pending-${DateTime.now().microsecondsSinceEpoch}',
              ));
    _activeCodexRemoteRuntimeId = runtimeId;
    _currentConversationIdByMode[ChatPageMode.codex] = runtimeId;
    _currentConversationByMode[ChatPageMode.codex] ??= ConversationModel(
      id: runtimeId,
      mode: ConversationMode.codex,
      title: 'Codex',
      status: 0,
      lastMessage: _messagesByMode[ChatPageMode.codex]!.isNotEmpty
          ? _messagesByMode[ChatPageMode.codex]!.first.text
          : null,
      messageCount: _messagesByMode[ChatPageMode.codex]!.length,
      createdAt: DateTime.now().millisecondsSinceEpoch,
      updatedAt: DateTime.now().millisecondsSinceEpoch,
    );
    _runtimeCoordinator.ensureEphemeralRuntime(
      conversationId: runtimeId,
      mode: kChatRuntimeModeCodex,
      initialMessages: List<ChatMessageModel>.from(
        _messagesByMode[ChatPageMode.codex]!,
      ),
      conversation: _currentConversationByMode[ChatPageMode.codex],
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
      mode: kChatRuntimeModeCodex,
      conversation:
          _runtimeCoordinator
              .runtimeFor(
                conversationId: runtimeId,
                mode: kChatRuntimeModeCodex,
              )
              ?.conversation ??
          ConversationModel(
            id: runtimeId,
            mode: ConversationMode.codex,
            title:
                'Codex ${normalizedThreadId.length > 6 ? normalizedThreadId.substring(normalizedThreadId.length - 6) : normalizedThreadId}',
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
      mode: kChatRuntimeModeCodex,
    );
    if (runtime != null) {
      final visibleMessages = _messagesByMode[ChatPageMode.codex]!;
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
      final currentConversation =
          _currentConversationByMode[ChatPageMode.codex];
      if (currentConversation != null) {
        runtime.conversation = currentConversation.copyWith(id: runtimeId);
      }
      _currentConversationByMode[ChatPageMode.codex] = runtime.conversation;
    }
    _activeCodexRemoteRuntimeId = runtimeId;
    _activeCodexThreadId = normalizedThreadId;
    _currentConversationIdByMode[ChatPageMode.codex] = runtimeId;
    return runtimeId;
  }

  bool _shouldPromoteRemoteCodexEventToVisibleThread({
    required String threadId,
    required int runtimeId,
  }) {
    final activeThreadId = _activeCodexThreadId?.trim();
    if (activeThreadId == threadId) {
      return true;
    }
    final currentConversationId =
        _currentConversationIdByMode[ChatPageMode.codex];
    if (currentConversationId == runtimeId) {
      return true;
    }
    if (activeThreadId != null && activeThreadId.isNotEmpty) {
      return false;
    }
    if (currentConversationId == null ||
        currentConversationId != _activeCodexRemoteRuntimeId) {
      return false;
    }
    final runtime = _runtimeCoordinator.runtimeFor(
      conversationId: currentConversationId,
      mode: kChatRuntimeModeCodex,
    );
    return (_messagesByMode[ChatPageMode.codex]?.isNotEmpty ?? false) ||
        (runtime?.hasInFlightTask ?? false) ||
        (_currentDispatchTaskIdByMode[ChatPageMode.codex]?.isNotEmpty ?? false);
  }

  Future<void> _showCodexAccountStatus() async {
    try {
      final account = await CodexAppServerService.readAccount();
      final accountMap = account['account'];
      final requiresOpenaiAuth = account['requiresOpenaiAuth'] == true;
      final accountType = accountMap is Map
          ? accountMap['type']?.toString().trim()
          : null;
      final isLoggedInWithChatGpt = accountType == 'chatgpt';
      if (isLoggedInWithChatGpt || !requiresOpenaiAuth) {
        return;
      }
      if (!mounted) return;
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(
        SnackBar(
          content: Text(
            Localizations.localeOf(context).languageCode == 'en'
                ? 'Codex login required'
                : '需要登录 Codex',
          ),
          action: SnackBarAction(
            label: Localizations.localeOf(context).languageCode == 'en'
                ? 'Login'
                : '登录',
            onPressed: () {
              if (_codexStatus.runtime == 'remote' ||
                  _codexStatus.remoteEnabled) {
                unawaited(_startRemoteCodexLogin());
              } else {
                GoRouterManager.push('/home/codex_setting');
              }
            },
          ),
        ),
      );
    } catch (error) {
      debugPrint('Read Codex account failed: $error');
    }
  }

  Future<void> _startRemoteCodexLogin() async {
    try {
      final response = await CodexAppServerService.startLogin();
      final authUrl = _asCodexString(response['authUrl']);
      if (authUrl == null) return;
      await launchUrlString(authUrl, mode: LaunchMode.externalApplication);
    } catch (error) {
      debugPrint('Start remote Codex login failed: $error');
    }
  }

  Future<CodexStatus> _refreshConnectedCodexStatus() async {
    var status = await CodexAppServerService.status();
    if (!status.connected) {
      status = await CodexAppServerService.connect();
      unawaited(CodexAppServerService.listThreads());
    }
    _applyRefreshedCodexStatus(status);
    return status;
  }

  void _applyRefreshedCodexStatus(CodexStatus status) {
    final sourceChanged =
        codexModelSourceKey(_codexStatus) != codexModelSourceKey(status);
    if (!mounted) return;
    setState(() {
      _codexStatus = status;
      if (sourceChanged) {
        _activeCodexThreadId = null;
        _activeCodexTurnId = null;
        _codexModelOptions = const <String>[];
        _codexModelListError = null;
      }
    });
  }

  Future<String?> _resolveCodexRequestModel(
    CodexStatus status, {
    String? overrideModel,
  }) async {
    final sourceKey = codexModelSourceKey(status);
    final scopedModel = _readCodexPreference(
      _kCodexModelPreferenceKey,
      conversationId: _currentConversationIdByMode[ChatPageMode.codex],
    );
    final localApiMode =
        status.runtime != 'remote' &&
        !status.remoteEnabled &&
        status.localAuthMode == CodexLocalAuthMode.api;
    final configuredApiModel = localApiMode
        ? (await _readCodexRunSettingsFromServerConfig()).modelId
        : null;
    return selectCodexRequestModel(
      status: status,
      overrideModel: overrideModel,
      activeModel: _activeCodexModelId,
      scopedModel: scopedModel,
      configuredApiModel: configuredApiModel,
      activeModelSourceMatches: _loadedCodexModelSourceKey == sourceKey,
    );
  }
}

int? _asCodexInt(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '');
}

String? _asCodexString(dynamic value) {
  final text = value?.toString().trim() ?? '';
  return text.isEmpty ? null : text;
}

String? _codexEventThreadId(Map<String, dynamic> event) {
  return _codexThreadIdFromEnvelope(event);
}

/// Top-level diagnostic counter that survives navigation. Used purely for
/// `flutter logs` / `adb logcat` introspection — the user reported that
/// exec_command tool cards do not surface in our UI even though the codex
/// session rollout contains 18 of them; this counter shows whether the
/// `rawResponseItem/completed` notifications actually reach the Flutter side.
final Map<String, int> _codexEventDiagnosticCounter = <String, int>{};

String _diagnosticEventMethod(Map<String, dynamic> event) {
  final method = _asCodexString(event['method']);
  if (method != null) {
    if (method == 'codex/event') {
      final params = _asCodexMap(event['params']) ?? const <String, dynamic>{};
      final msg = _asCodexMap(params['msg']);
      final msgType = _asCodexString(msg?['type']);
      if (msgType != null) {
        return 'codex/event:$msgType';
      }
    }
    if (method == 'rawResponseItem/completed' ||
        method == 'item/started' ||
        method == 'item/completed') {
      final params = _asCodexMap(event['params']) ?? const <String, dynamic>{};
      final item = _asCodexMap(params['item']);
      final itemType = _asCodexString(item?['type']);
      if (itemType != null) {
        final name = _asCodexString(item?['name']);
        if (name != null) {
          return '$method:$itemType:$name';
        }
        return '$method:$itemType';
      }
    }
    return method;
  }
  final message = _asCodexMap(event['message']);
  return _asCodexString(message?['method']) ?? '<unknown>';
}

const List<String> _codexEnvelopeKeys = <String>[
  'message',
  'payload',
  'data',
  'event',
  'notification',
  'params',
  'result',
];

String? _codexThreadIdFromEnvelope(dynamic value, {int depth = 0}) {
  if (depth > 6) {
    return null;
  }
  final map = _asCodexMap(value);
  if (map == null) {
    return null;
  }
  final direct = _asCodexString(map['threadId'] ?? map['thread_id']);
  if (direct != null) {
    return direct;
  }
  final thread = _asCodexMap(map['thread']);
  final threadId = _asCodexString(thread?['id']);
  if (threadId != null) {
    return threadId;
  }
  for (final key in _codexEnvelopeKeys) {
    final nested = map[key];
    if (nested == null) {
      continue;
    }
    final nestedThreadId = _codexThreadIdFromEnvelope(nested, depth: depth + 1);
    if (nestedThreadId != null) {
      return nestedThreadId;
    }
  }
  return null;
}

int _remoteCodexRuntimeId(String seed) {
  var hash = 0x45d9f3b;
  for (final codeUnit in seed.codeUnits) {
    hash = 0x1fffffff & (hash * 31 + codeUnit);
  }
  return -((hash & 0x3fffffff) + 1);
}

ConversationModel _remoteCodexConversationFromResponse({
  required int runtimeId,
  required Map<String, dynamic> response,
}) {
  final thread = _asCodexMap(response['thread']) ?? response;
  final now = DateTime.now().millisecondsSinceEpoch;
  final createdAt =
      _codexTimeValueMs(thread['createdAt'] ?? thread['created_at']) ?? now;
  final updatedAt =
      _codexTimeValueMs(
        thread['updatedAt'] ??
            thread['updated_at'] ??
            thread['lastActivityAt'] ??
            thread['last_activity_at'],
      ) ??
      createdAt;
  final title =
      _asCodexString(
        thread['name'] ??
            thread['title'] ??
            thread['preview'] ??
            response['name'] ??
            response['title'] ??
            response['preview'],
      ) ??
      'Codex';
  return ConversationModel(
    id: runtimeId,
    mode: ConversationMode.codex,
    title: _truncateCodexText(title, 40),
    status: 0,
    lastMessage: _asCodexString(thread['preview']),
    messageCount: _codexMessagesFromThreadResponse(response).length,
    createdAt: createdAt,
    updatedAt: updatedAt,
  );
}

class _CodexThreadActivityState {
  const _CodexThreadActivityState({required this.known, required this.active});

  final bool known;
  final bool active;

  static const unknown = _CodexThreadActivityState(known: false, active: false);
  static const activeState = _CodexThreadActivityState(
    known: true,
    active: true,
  );
  static const inactiveState = _CodexThreadActivityState(
    known: true,
    active: false,
  );
}

_CodexThreadActivityState _codexThreadActivityFromResponse(
  Map<String, dynamic> response,
) {
  final thread = _asCodexMap(response['thread']) ?? response;
  _CodexThreadActivityState? inactiveCandidate;
  for (final value in <dynamic>[
    response['active'],
    response['isActive'],
    response['is_active'],
    response['status'],
    response['state'],
    response['turnStatus'],
    response['turn_status'],
    thread['active'],
    thread['isActive'],
    thread['is_active'],
    thread['status'],
    thread['state'],
    thread['turnStatus'],
    thread['turn_status'],
  ]) {
    final parsed = _codexActivityFromValue(value);
    if (parsed == null) {
      continue;
    }
    if (parsed.active) {
      return parsed;
    }
    inactiveCandidate ??= parsed;
  }
  final latestTurnActivity = _codexLatestTurnActivityFromResponse(response);
  if (latestTurnActivity != null) {
    return latestTurnActivity;
  }
  if (inactiveCandidate != null) {
    return inactiveCandidate;
  }
  return _CodexThreadActivityState.unknown;
}

_CodexThreadActivityState? _codexLatestTurnActivityFromResponse(
  Map<String, dynamic> response,
) {
  final turns = _codexTurnsFromThreadResponse(response);
  if (turns == null) {
    return null;
  }
  for (var index = turns.length - 1; index >= 0; index -= 1) {
    final turn = _asCodexMap(turns[index]);
    if (turn == null) {
      continue;
    }
    final parsed = _codexActivityFromValue(turn['status'] ?? turn['state']);
    if (parsed != null) {
      return parsed;
    }
  }
  return null;
}

_CodexThreadActivityState? _codexActivityFromValue(dynamic value) {
  if (value is bool) {
    return value
        ? _CodexThreadActivityState.activeState
        : _CodexThreadActivityState.inactiveState;
  }
  final status = _codexStatusText(value);
  if (status == null) {
    return null;
  }
  final normalized = _normalizeCodexStatus(status);
  if (_codexStatusIsActive(normalized)) {
    return _CodexThreadActivityState.activeState;
  }
  if (_codexStatusIsInactive(normalized)) {
    return _CodexThreadActivityState.inactiveState;
  }
  return null;
}

String? _codexStatusText(dynamic value) {
  if (value == null) {
    return null;
  }
  if (value is String || value is num || value is bool) {
    return _asCodexString(value);
  }
  final map = _asCodexMap(value);
  if (map != null) {
    for (final key in const <String>[
      'type',
      'status',
      'state',
      'value',
      'name',
    ]) {
      final text = _codexStatusText(map[key]);
      if (text != null) {
        return text;
      }
    }
  }
  return null;
}

String _normalizeCodexStatus(String status) =>
    status.trim().toLowerCase().replaceAll(RegExp(r'[^a-z0-9]+'), '');

bool _codexStatusIsActive(String status) {
  return status == 'running' ||
      status == 'active' ||
      status == 'busy' ||
      status == 'inprogress' ||
      status == 'inflight' ||
      status == 'executing';
}

bool _codexStatusIsInactive(String status) {
  return status == 'idle' ||
      status == 'closed' ||
      status == 'completed' ||
      status == 'complete' ||
      status == 'notloaded' ||
      status == 'systemerror' ||
      status == 'failed' ||
      status == 'cancelled' ||
      status == 'canceled' ||
      status == 'interrupted';
}

String? _codexActiveTurnIdFromThreadResponse(Map<String, dynamic> response) {
  final thread = _asCodexMap(response['thread']) ?? response;
  final status =
      _asCodexMap(response['status']) ?? _asCodexMap(thread['status']);
  final direct = _asCodexString(
    response['turnId'] ??
        response['turn_id'] ??
        response['activeTurnId'] ??
        response['active_turn_id'] ??
        response['currentTurnId'] ??
        response['current_turn_id'] ??
        thread['turnId'] ??
        thread['turn_id'] ??
        thread['activeTurnId'] ??
        thread['active_turn_id'] ??
        thread['currentTurnId'] ??
        thread['current_turn_id'] ??
        status?['turnId'] ??
        status?['turn_id'] ??
        status?['activeTurnId'] ??
        status?['active_turn_id'],
  );
  if (direct != null) {
    return direct;
  }
  final turns = _codexTurnsFromThreadResponse(response);
  if (turns == null) {
    return null;
  }
  for (var index = turns.length - 1; index >= 0; index -= 1) {
    final turn = _asCodexMap(turns[index]);
    if (turn == null) {
      continue;
    }
    final parsed = _codexActivityFromValue(turn['status'] ?? turn['state']);
    if (parsed?.active == true) {
      return _codexTurnIdAt(turns, index);
    }
  }
  return null;
}

String? _codexLatestTurnIdFromThreadResponse(Map<String, dynamic> response) {
  final turns = _codexTurnsFromThreadResponse(response);
  if (turns == null || turns.isEmpty) {
    return null;
  }
  for (var index = turns.length - 1; index >= 0; index -= 1) {
    final turnId = _codexTurnIdAt(turns, index);
    if (turnId != null) {
      return turnId;
    }
  }
  return null;
}

bool _codexThreadResponseHasTurns(Map<String, dynamic> response) {
  return _codexTurnsFromThreadResponse(response) != null;
}

List<dynamic>? _codexTurnsFromThreadResponse(Map<String, dynamic> response) {
  final thread = _asCodexMap(response['thread']) ?? response;
  final rawTurns = thread['turns'] ?? response['turns'];
  return rawTurns is List ? rawTurns : null;
}

String? _codexTurnIdAt(List<dynamic> turns, int index) {
  if (index < 0 || index >= turns.length) {
    return null;
  }
  final turn = _asCodexMap(turns[index]);
  if (turn == null) {
    return null;
  }
  return _asCodexString(turn['id']) ?? 'turn-$index';
}

List<Map<String, dynamic>> _codexHistoricalItemsFromTurn(
  Map<String, dynamic> turn,
) {
  final items = <Map<String, dynamic>>[];
  final seen = <String, int>{};

  void addItem(Map<String, dynamic> item) {
    final normalized = _codexNormalizeHistoricalItem(item);
    if (normalized == null) {
      return;
    }
    final key = _codexHistoricalItemDedupeKey(normalized);
    final existingIndex = seen[key];
    if (existingIndex != null) {
      items[existingIndex] = _codexMergeHistoricalItemSnapshot(
        items[existingIndex],
        normalized,
      );
      return;
    }
    seen[key] = items.length;
    items.add(normalized);
  }

  void addFromValue(dynamic value) {
    if (value is List) {
      for (final entry in value) {
        addFromValue(entry);
      }
      return;
    }
    final item = _codexHistoricalItemFromValue(value);
    if (item != null) {
      addItem(item);
    }
  }

  for (final key in const <String>[
    'items',
    'outputItems',
    'output_items',
    'responseItems',
    'response_items',
    'rawItems',
    'raw_items',
    'messages',
    'events',
    'inputItems',
    'input_items',
  ]) {
    addFromValue(turn[key]);
  }

  final worklog = _asCodexMap(turn['worklog']);
  addFromValue(worklog?['messages']);
  return items;
}

Map<String, dynamic> _codexMergeHistoricalItemSnapshot(
  Map<String, dynamic> existing,
  Map<String, dynamic> incoming,
) {
  final merged = Map<String, dynamic>.from(existing);
  for (final entry in incoming.entries) {
    final value = entry.value;
    if (value == null) {
      continue;
    }
    if (value is String && value.trim().isEmpty) {
      continue;
    }
    merged[entry.key] = value;
  }
  return merged;
}

Map<String, dynamic>? _codexHistoricalItemFromValue(dynamic value) {
  final map = _asCodexMap(value);
  if (map == null) {
    return null;
  }
  final direct = _codexNormalizeHistoricalItem(map);
  if (direct != null) {
    return direct;
  }
  for (final key in const <String>[
    'item',
    'rawItem',
    'raw_item',
    'responseItem',
    'response_item',
  ]) {
    final nested = _codexHistoricalItemFromValue(map[key]);
    if (nested != null) {
      return _codexMergeEnvelopeIds(map, nested);
    }
  }
  final params = _asCodexMap(map['params']);
  if (params != null) {
    final nested = _codexHistoricalItemFromValue(params);
    if (nested != null) {
      return _codexMergeEnvelopeIds(map, nested);
    }
  }
  final protocolItem = _codexHistoricalItemFromProtocolEvent(params ?? map);
  if (protocolItem != null) {
    return _codexMergeEnvelopeIds(map, protocolItem);
  }
  final methodItem = _codexHistoricalItemFromEventMethod(
    _asCodexString(map['method'] ?? map['type']),
    params ?? map,
  );
  if (methodItem != null) {
    return _codexMergeEnvelopeIds(map, methodItem);
  }
  for (final key in _codexEnvelopeKeys) {
    if (key == 'params') {
      continue;
    }
    final nested = _codexHistoricalItemFromValue(map[key]);
    if (nested != null) {
      return _codexMergeEnvelopeIds(map, nested);
    }
  }
  return null;
}

Map<String, dynamic>? _codexHistoricalItemFromEventMethod(
  String? rawMethod,
  Map<String, dynamic> params,
) {
  final method = (rawMethod ?? '')
      .trim()
      .replaceAll('.', '/')
      .replaceAll('/command_execution/', '/commandExecution/')
      .replaceAll('/file_change/', '/fileChange/')
      .replaceAll('/mcp_tool_call/', '/mcpToolCall/');
  if (method.isEmpty) {
    return null;
  }
  final lowerMethod = method.toLowerCase();
  if (method.endsWith('requestUserInput') ||
      lowerMethod.endsWith('request_user_input')) {
    return _codexNormalizeHistoricalItem(<String, dynamic>{
      ...params,
      'id':
          _asCodexString(params['id']) ??
          _asCodexString(params['requestId']) ??
          _asCodexString(params['request_id']),
      'type': 'requestUserInput',
    });
  }
  if (method.endsWith('requestApproval') ||
      lowerMethod.endsWith('request_approval')) {
    return _codexNormalizeHistoricalItem(<String, dynamic>{
      ...params,
      'id':
          _asCodexString(params['id']) ??
          _asCodexString(params['requestId']) ??
          _asCodexString(params['request_id']),
      'type': 'requestApproval',
    });
  }
  if (method.contains('commandExecution') ||
      method == 'command/exec/outputDelta' ||
      method == 'command/exec/completed' ||
      method == 'process/outputDelta' ||
      method == 'process/exited') {
    return _codexNormalizeHistoricalItem(<String, dynamic>{
      ...params,
      'id':
          _asCodexString(
            params['itemId'] ??
                params['item_id'] ??
                params['processId'] ??
                params['process_id'] ??
                params['processHandle'] ??
                params['process_handle'],
          ) ??
          _asCodexString(params['id']),
      'type': method.contains('process')
          ? 'processExecution'
          : method.contains('command/exec')
          ? 'commandExec'
          : 'commandExecution',
      'aggregatedOutput':
          params['aggregatedOutput'] ??
          params['aggregated_output'] ??
          params['output'] ??
          params['delta'] ??
          params['text'],
      'status': params['status'] ?? 'completed',
    });
  }
  if (method.contains('fileChange') || method == 'turn/diff/updated') {
    return _codexNormalizeHistoricalItem(<String, dynamic>{
      ...params,
      'id':
          _asCodexString(params['itemId'] ?? params['item_id']) ??
          _asCodexString(params['id']),
      'type': 'fileChange',
      'status': params['status'] ?? 'completed',
    });
  }
  if (method.contains('mcpToolCall')) {
    return _codexNormalizeHistoricalItem(<String, dynamic>{
      ...params,
      'id':
          _asCodexString(params['itemId'] ?? params['item_id']) ??
          _asCodexString(params['id']),
      'type': 'mcpToolCall',
      'status': params['status'] ?? 'completed',
    });
  }
  return null;
}

Map<String, dynamic>? _codexHistoricalItemFromProtocolEvent(
  Map<String, dynamic> value,
) {
  final msg = _codexHistoricalProtocolMsg(value);
  if (msg == null) {
    return null;
  }
  final msgType = _codexNormalizeProtocolMsgType(_asCodexString(msg['type']));
  if (msgType.isEmpty) {
    return null;
  }
  final eventId = _asCodexString(value['id']);
  final callId = _asCodexString(
    msg['callId'] ??
        msg['call_id'] ??
        msg['itemId'] ??
        msg['item_id'] ??
        msg['processId'] ??
        msg['process_id'] ??
        eventId,
  );
  Map<String, dynamic> withIds(Map<String, dynamic> item) {
    return <String, dynamic>{
      ..._codexTopLevelIds(value),
      ..._codexTopLevelIds(msg),
      if (callId != null) 'id': callId,
      ...item,
    };
  }

  switch (msgType) {
    case 'item_started':
    case 'item_completed':
      final item = _asCodexMap(msg['item']);
      return item == null ? null : _codexNormalizeHistoricalItem(item);
    case 'raw_response_item':
      final item = _asCodexMap(msg['item']);
      return item == null ? null : _codexNormalizeHistoricalItem(item);
    case 'agent_message':
      final text = _codexExtractText(msg['message'] ?? msg['text']);
      if (text.trim().isEmpty) {
        return null;
      }
      return withIds(<String, dynamic>{
        'type': 'agentMessage',
        'message': text,
      });
    case 'agent_reasoning':
    case 'agent_reasoning_raw_content':
    case 'reasoning_content_delta':
    case 'reasoning_raw_content_delta':
      final text = _codexExtractText(msg['delta'] ?? msg['text']);
      if (text.trim().isEmpty) {
        return null;
      }
      return withIds(<String, dynamic>{'type': 'reasoning', 'summary': text});
    case 'exec_command_begin':
    case 'exec_command_output_delta':
    case 'terminal_interaction':
    case 'exec_command_end':
      return _codexNormalizeHistoricalItem(
        withIds(_codexHistoricalCommandItem(msg, msgType: msgType)),
      );
    case 'mcp_tool_call_begin':
    case 'mcp_tool_call_end':
      return _codexNormalizeHistoricalItem(
        withIds(
          _codexHistoricalMcpToolItem(msg, completed: msgType.endsWith('_end')),
        ),
      );
    case 'web_search_begin':
    case 'web_search_end':
      return _codexNormalizeHistoricalItem(
        withIds(
          _codexHistoricalWebSearchItem(
            msg,
            completed: msgType.endsWith('_end'),
          ),
        ),
      );
    case 'view_image_tool_call':
      return _codexNormalizeHistoricalItem(
        withIds(<String, dynamic>{
          ...msg,
          'type': 'imageView',
          'status': 'completed',
        }),
      );
    case 'patch_apply_begin':
    case 'patch_apply_updated':
    case 'patch_apply_end':
      return _codexNormalizeHistoricalItem(
        withIds(
          _codexHistoricalPatchItem(msg, completed: msgType.endsWith('_end')),
        ),
      );
  }
  return null;
}

Map<String, dynamic>? _codexHistoricalProtocolMsg(
  Map<String, dynamic> root, {
  int depth = 0,
}) {
  if (depth > 6) {
    return null;
  }
  final direct = _asCodexMap(root['msg']);
  if (direct != null) {
    return direct;
  }
  for (final key in const <String>[
    'params',
    'message',
    'payload',
    'data',
    'event',
    'notification',
    'result',
  ]) {
    final nested = _asCodexMap(root[key]);
    if (nested == null) {
      continue;
    }
    final msg = _codexHistoricalProtocolMsg(nested, depth: depth + 1);
    if (msg != null) {
      return msg;
    }
  }
  return null;
}

String _codexNormalizeProtocolMsgType(String? rawType) {
  final value = rawType?.trim().toLowerCase() ?? '';
  if (value.isEmpty) {
    return '';
  }
  return value.replaceAll(RegExp(r'[^a-z0-9]+'), '_');
}

Map<String, dynamic> _codexTopLevelIds(Map<String, dynamic> value) {
  final ids = <String, dynamic>{};
  final meta = _asCodexMap(value['_meta']);
  if (meta != null) {
    for (final key in const <String>['threadId', 'thread_id']) {
      if (meta.containsKey(key)) {
        ids[key] = meta[key];
      }
    }
  }
  for (final key in const <String>[
    'threadId',
    'thread_id',
    'turnId',
    'turn_id',
    'itemId',
    'item_id',
  ]) {
    if (value.containsKey(key)) {
      ids[key] = value[key];
    }
  }
  return ids;
}

Map<String, dynamic> _codexHistoricalCommandItem(
  Map<String, dynamic> msg, {
  required String msgType,
}) {
  final command = _codexCommandTextFromValue(msg['command']);
  final exitCode = _asCodexInt(msg['exitCode'] ?? msg['exit_code']);
  final output = msgType == 'exec_command_output_delta'
      ? _codexHistoricalOutputDelta(msg)
      : _codexExtractText(
          msg['aggregatedOutput'] ??
              msg['aggregated_output'] ??
              msg['output'] ??
              msg['stdout'] ??
              msg['formattedOutput'] ??
              msg['formatted_output'],
        );
  final status =
      _asCodexString(msg['status']) ??
      (msgType == 'exec_command_begin'
          ? 'in_progress'
          : exitCode == null
          ? 'completed'
          : exitCode == 0
          ? 'completed'
          : 'failed');
  return <String, dynamic>{
    ...msg,
    'type': 'commandExecution',
    if (command != null) 'command': command,
    'cwd': msg['cwd'],
    'processId': msg['processId'] ?? msg['process_id'],
    'process_id': msg['process_id'] ?? msg['processId'],
    'aggregatedOutput': output,
    'aggregated_output': output,
    'stdout': msg['stdout'],
    'stderr': msg['stderr'],
    'exitCode': exitCode,
    'exit_code': exitCode,
    'status': status,
  };
}

Map<String, dynamic> _codexHistoricalMcpToolItem(
  Map<String, dynamic> msg, {
  required bool completed,
}) {
  final invocation =
      _asCodexMap(msg['invocation']) ?? const <String, dynamic>{};
  final resultFields = _codexHistoricalMcpResultFields(msg['result']);
  return <String, dynamic>{
    ...msg,
    'type': 'mcpToolCall',
    'server': invocation['server'] ?? msg['server'],
    'tool': invocation['tool'] ?? msg['tool'],
    'arguments': invocation['arguments'] ?? msg['arguments'],
    'status': completed
        ? (resultFields['status'] ?? msg['status'] ?? 'completed')
        : 'in_progress',
    ...resultFields,
  };
}

Map<String, dynamic> _codexHistoricalMcpResultFields(dynamic value) {
  if (value == null) {
    return const <String, dynamic>{};
  }
  final map = _asCodexMap(value);
  if (map != null) {
    if (map.containsKey('Ok') || map.containsKey('ok')) {
      return <String, dynamic>{
        'status': 'completed',
        'result': map['Ok'] ?? map['ok'],
      };
    }
    if (map.containsKey('Err') || map.containsKey('err')) {
      final error = map['Err'] ?? map['err'];
      return <String, dynamic>{
        'status': 'failed',
        'error': error is Map ? error : <String, dynamic>{'message': error},
      };
    }
  }
  return <String, dynamic>{'status': 'completed', 'result': value};
}

Map<String, dynamic> _codexHistoricalWebSearchItem(
  Map<String, dynamic> msg, {
  required bool completed,
}) {
  final action = _asCodexMap(msg['action']);
  return <String, dynamic>{
    ...msg,
    'type': 'webSearch',
    'query': msg['query'] ?? action?['query'],
    'status': completed ? 'completed' : 'in_progress',
  };
}

Map<String, dynamic> _codexHistoricalPatchItem(
  Map<String, dynamic> msg, {
  required bool completed,
}) {
  final success = msg['success'];
  return <String, dynamic>{
    ...msg,
    'type': 'fileChange',
    'changes': msg['changes'],
    'stdout': msg['stdout'],
    'stderr': msg['stderr'],
    'success': success,
    'status':
        _asCodexString(msg['status']) ??
        (completed
            ? success == false
                  ? 'failed'
                  : 'completed'
            : 'in_progress'),
  };
}

String? _codexCommandTextFromValue(dynamic value) {
  if (value == null) {
    return null;
  }
  if (value is String) {
    final text = value.trim();
    return text.isEmpty ? null : text;
  }
  if (value is List) {
    final parts = value
        .map(_codexExtractText)
        .map((part) => part.trim())
        .where((part) => part.isNotEmpty)
        .toList(growable: false);
    return parts.isEmpty ? null : parts.join(' ');
  }
  final text = _codexExtractText(value).trim();
  return text.isEmpty ? null : text;
}

String _codexHistoricalOutputDelta(Map<String, dynamic> msg) {
  final decoded =
      _decodeCodexBase64(msg['chunk']) ??
      _decodeCodexByteList(msg['chunk']) ??
      _decodeCodexBase64(msg['deltaBase64']) ??
      _decodeCodexBase64(msg['delta_base64']) ??
      _codexExtractText(msg['delta'] ?? msg['output'] ?? msg['text']);
  final stream = _asCodexString(msg['stream'])?.toLowerCase();
  if (decoded.isEmpty || stream == null || stream == 'stdout') {
    return decoded;
  }
  return '\n[$stream]\n$decoded${decoded.endsWith('\n') ? '' : '\n'}';
}

String? _decodeCodexBase64(dynamic value) {
  final encoded = _asCodexString(value);
  if (encoded == null) {
    return null;
  }
  try {
    return utf8.decode(base64Decode(encoded), allowMalformed: true);
  } catch (_) {
    return null;
  }
}

String? _decodeCodexByteList(dynamic value) {
  if (value is! List) {
    return null;
  }
  final bytes = <int>[];
  for (final item in value) {
    final byte = _asCodexInt(item);
    if (byte == null || byte < 0 || byte > 255) {
      return null;
    }
    bytes.add(byte);
  }
  try {
    return utf8.decode(bytes, allowMalformed: true);
  } catch (_) {
    return null;
  }
}

Map<String, dynamic> _codexMergeEnvelopeIds(
  Map<String, dynamic> envelope,
  Map<String, dynamic> item,
) {
  final merged = Map<String, dynamic>.from(item);
  for (final key in const <String>[
    'threadId',
    'thread_id',
    'turnId',
    'turn_id',
    'itemId',
    'item_id',
  ]) {
    if (!merged.containsKey(key) && envelope.containsKey(key)) {
      merged[key] = envelope[key];
    }
  }
  return merged;
}

Map<String, dynamic>? _codexNormalizeHistoricalItem(Map<String, dynamic> item) {
  final normalized = Map<String, dynamic>.from(item);
  var itemType = canonicalCodexItemType(_asCodexString(normalized['type']));
  final role = _asCodexString(
    normalized['role'] ?? _asCodexMap(normalized['author'])?['role'],
  )?.toLowerCase();
  if (itemType == 'message' || itemType.isEmpty) {
    if (role == 'user') {
      itemType = 'userMessage';
    } else if (role == 'assistant') {
      itemType = 'agentMessage';
    }
  }
  if ((itemType == 'output_diff' || itemType == 'pr') &&
      (normalized['diff'] != null || normalized['output_diff'] != null)) {
    itemType = 'fileChange';
    normalized['changes'] ??= normalized['diff'] ?? normalized['output_diff'];
  }
  if (itemType.isEmpty) {
    if (normalized['command'] != null || normalized['cmd'] != null) {
      itemType = 'commandExecution';
    } else if (normalized['name'] != null && normalized['arguments'] != null) {
      itemType = 'function_call';
    } else if ((normalized['callId'] != null ||
            normalized['call_id'] != null) &&
        normalized['output'] != null) {
      itemType = 'function_call_output';
    }
  }
  if (!_codexLooksLikeHistoricalItemType(itemType)) {
    return null;
  }
  normalized['type'] = itemType;
  return normalized;
}

bool _codexLooksLikeHistoricalItemType(String itemType) {
  final canonical = canonicalCodexItemType(itemType);
  return canonical == 'userMessage' ||
      canonical == 'agentMessage' ||
      canonical == 'reasoning' ||
      _codexHistoricalRequestItemTypes.contains(canonical) ||
      _codexHistoricalToolItemTypes.contains(canonical) ||
      _codexHistoricalToolOutputItemTypes.contains(canonical);
}

String _codexHistoricalItemDedupeKey(Map<String, dynamic> item) {
  final type = canonicalCodexItemType(_asCodexString(item['type']));
  final id =
      _asCodexString(
        item['id'] ??
            item['itemId'] ??
            item['item_id'] ??
            item['callId'] ??
            item['call_id'] ??
            item['processId'] ??
            item['process_id'] ??
            item['processHandle'] ??
            item['process_handle'],
      ) ??
      _codexStableItemKey(item);
  return '$type:$id';
}

bool _codexLatestTurnLooksExternallyActive(Map<String, dynamic> response) {
  final turns = _codexTurnsFromThreadResponse(response);
  if (turns == null || turns.isEmpty) {
    return false;
  }
  for (var index = turns.length - 1; index >= 0; index -= 1) {
    final turn = _asCodexMap(turns[index]);
    if (turn == null) {
      continue;
    }
    final activity = _codexActivityFromValue(turn['status'] ?? turn['state']);
    if (activity?.active == true) {
      return true;
    }
    final statusText = _codexStatusText(turn['status'] ?? turn['state']);
    final normalizedStatus = statusText == null
        ? null
        : _normalizeCodexStatus(statusText);
    final completedAt =
        _codexTimeValueMs(turn['completedAt'] ?? turn['completed_at']) ??
        _codexTimeValueMs(turn['finishedAt'] ?? turn['finished_at']);
    final hasError = turn['error'] != null;
    final hasItems = _codexHistoricalItemsFromTurn(turn).isNotEmpty;
    if (completedAt == null &&
        !hasError &&
        hasItems &&
        (normalizedStatus == null || normalizedStatus == 'interrupted')) {
      return true;
    }
    return false;
  }
  return false;
}

String _codexThreadContentSignature(Map<String, dynamic> response) {
  final thread = _asCodexMap(response['thread']) ?? response;
  final turns = _codexTurnsFromThreadResponse(response);
  final buffer = StringBuffer()
    ..write(_asCodexString(thread['id'] ?? response['threadId']) ?? '')
    ..write('|');
  if (turns == null) {
    buffer
      ..write(
        _codexTimeValueMs(thread['updatedAt'] ?? thread['updated_at']) ?? '',
      )
      ..write('|')
      ..write(_asCodexString(thread['preview'] ?? response['preview']) ?? '');
    return buffer.toString();
  }
  for (var turnIndex = 0; turnIndex < turns.length; turnIndex += 1) {
    final turn = _asCodexMap(turns[turnIndex]);
    if (turn == null) {
      continue;
    }
    buffer
      ..write(_codexTurnIdAt(turns, turnIndex) ?? '')
      ..write(':')
      ..write(_codexStatusText(turn['status'] ?? turn['state']) ?? '')
      ..write(':')
      ..write(_codexTimeValueMs(turn['startedAt'] ?? turn['started_at']) ?? '')
      ..write(':')
      ..write(
        _codexTimeValueMs(turn['completedAt'] ?? turn['completed_at']) ?? '',
      )
      ..write('|');
    final rawItems = _codexHistoricalItemsFromTurn(turn);
    for (var itemIndex = 0; itemIndex < rawItems.length; itemIndex += 1) {
      final item = rawItems[itemIndex];
      buffer
        ..write(_asCodexString(item['id']) ?? '$turnIndex-$itemIndex')
        ..write(',')
        ..write(_asCodexString(item['type']) ?? '')
        ..write(',')
        ..write(_codexStatusText(item['status'] ?? item['state']) ?? '')
        ..write(',')
        ..write(
          _codexExtractText(
            item['summary'] ??
                item['text'] ??
                item['message'] ??
                item['content'] ??
                item['output'] ??
                item['command'] ??
                item['cmd'] ??
                item['path'],
          ).hashCode,
        )
        ..write(';');
    }
  }
  return buffer.toString();
}

String _remoteCodexSnapshotSignature({
  required String threadId,
  required List<ChatMessageModel> messages,
  required ConversationModel conversation,
  required bool isAiResponding,
  required String? activeTaskId,
}) {
  final buffer = StringBuffer()
    ..write(threadId)
    ..write('|')
    ..write(conversation.updatedAt)
    ..write('|')
    ..write(isAiResponding ? '1' : '0')
    ..write('|')
    ..write(activeTaskId ?? '')
    ..write('|')
    ..write(messages.length);
  for (final message in messages) {
    final attachments = message.content?['attachments'];
    buffer
      ..write('|')
      ..write(message.id)
      ..write(':')
      ..write(message.text?.hashCode ?? message.cardData?.hashCode ?? 0)
      ..write(':')
      ..write(attachments == null ? 0 : _safeCodexJson(attachments).hashCode);
  }
  return buffer.toString();
}

List<ChatMessageModel> _mergeRemoteCodexSnapshotMessages({
  required List<ChatMessageModel> snapshotMessages,
  required List<ChatMessageModel> existingMessages,
  required String? activeTaskId,
  required bool isAiResponding,
}) {
  if (existingMessages.isEmpty) {
    return snapshotMessages;
  }
  final snapshotById = <String, ChatMessageModel>{
    for (final message in snapshotMessages) message.id: message,
  };
  final existingById = <String, ChatMessageModel>{
    for (final message in existingMessages) message.id: message,
  };
  final userMessageIdsToPreserve = _remoteRuntimeUserMessageIdsToPreserve(
    existingMessages: existingMessages,
    snapshotMessageIds: snapshotById.keys.toSet(),
    snapshotUserTextCounts: _remoteUserMessageTextCounts(snapshotMessages),
  );
  final snapshotTaskIds = _remoteSnapshotTaskIds(snapshotMessages);
  final mergedById = <String, ChatMessageModel>{};
  for (final snapshot in snapshotMessages) {
    final existing = existingById[snapshot.id];
    mergedById[snapshot.id] =
        existing != null &&
            _shouldPreferExistingRemoteMessage(
              existing: existing,
              snapshot: snapshot,
              activeTaskId: activeTaskId,
              isAiResponding: isAiResponding,
            )
        ? existing
        : snapshot;
  }
  for (final existing in existingMessages) {
    if (snapshotById.containsKey(existing.id)) {
      continue;
    }
    if (existing.type == 1 && existing.user == 1) {
      if (userMessageIdsToPreserve.contains(existing.id)) {
        mergedById[existing.id] = existing;
      }
      continue;
    }
    if (!_shouldPreserveRemoteRuntimeMessage(
      existing,
      activeTaskId: activeTaskId,
      isAiResponding: isAiResponding,
      snapshotTaskIds: snapshotTaskIds,
    )) {
      continue;
    }
    mergedById[existing.id] = existing;
  }
  final merged = mergedById.values.toList(growable: false)
    ..sort((a, b) => b.createAt.compareTo(a.createAt));
  return _normalizeCodexLoadingThinkingCards(
    merged,
    activeTaskId: activeTaskId,
    isAiResponding: isAiResponding,
  );
}

List<ChatMessageModel> _normalizeCodexLoadingThinkingCards(
  List<ChatMessageModel> messages, {
  required String? activeTaskId,
  required bool isAiResponding,
}) {
  final activeTask = activeTaskId?.trim() ?? '';
  final keptLoadingTaskIds = <String>{};
  final normalized = <ChatMessageModel>[];
  for (final message in messages) {
    final cardData = message.cardData;
    if (cardData?['type'] != 'deep_thinking') {
      normalized.add(message);
      continue;
    }
    final taskId = _messageTaskId(message);
    final normalizedTaskId = taskId?.trim() ?? '';
    final isLoading = cardData?['isLoading'] == true;
    final keepLoading =
        isLoading &&
        isAiResponding &&
        activeTask.isNotEmpty &&
        normalizedTaskId == activeTask &&
        !keptLoadingTaskIds.contains(normalizedTaskId);
    if (keepLoading) {
      keptLoadingTaskIds.add(normalizedTaskId);
      normalized.add(message);
      continue;
    }
    final shouldFinalize =
        isLoading ||
        cardData?['stage'] == ThinkingStage.thinking.value ||
        cardData?['isCollapsible'] == false;
    normalized.add(
      shouldFinalize
          ? _completeCodexThinkingSnapshotMessage(message, taskId: taskId)
          : message,
    );
  }
  return normalized;
}

ChatMessageModel _completeCodexThinkingSnapshotMessage(
  ChatMessageModel message, {
  required String? taskId,
}) {
  final cardData = Map<String, dynamic>.from(
    message.cardData ?? const <String, dynamic>{},
  );
  final resolvedTaskId =
      taskId ??
      _asCodexString(cardData['taskID']) ??
      _asCodexString(message.streamMeta?['parentTaskId']);
  final startTime =
      _asCodexInt(cardData['startTime']) ??
      message.createAt.millisecondsSinceEpoch;
  cardData['isLoading'] = false;
  cardData['stage'] = ThinkingStage.complete.value;
  if (resolvedTaskId != null) {
    cardData['taskID'] = resolvedTaskId;
  }
  cardData['cardId'] = _asCodexString(cardData['cardId']) ?? message.id;
  cardData['startTime'] = startTime;
  cardData['endTime'] ??= DateTime.now().millisecondsSinceEpoch;
  cardData['isCollapsible'] = true;
  cardData['thinkingContent'] = (cardData['thinkingContent'] ?? '').toString();
  return message.copyWith(
    content: {'cardData': cardData, 'id': message.id},
    streamMeta: ensureAgentStreamMessageMeta(
      message.streamMeta,
      kind: 'thinking_snapshot',
      parentTaskId: resolvedTaskId,
      entryId: message.id,
      isFinal: true,
    ),
  );
}

bool _shouldPreferExistingRemoteMessage({
  required ChatMessageModel existing,
  required ChatMessageModel snapshot,
  required String? activeTaskId,
  required bool isAiResponding,
}) {
  if (!isAiResponding) {
    return false;
  }
  if (!_messageBelongsToTask(existing, activeTaskId)) {
    return false;
  }
  if (_isInFlightCodexMessage(existing)) {
    return true;
  }
  final existingText = existing.text ?? '';
  final snapshotText = snapshot.text ?? '';
  return existingText.length > snapshotText.length &&
      existingText.startsWith(snapshotText);
}

bool _shouldPreserveRemoteRuntimeMessage(
  ChatMessageModel message, {
  required String? activeTaskId,
  required bool isAiResponding,
  required Set<String> snapshotTaskIds,
}) {
  if (_isCodexRequestMessage(message)) {
    if (isAiResponding) {
      return true;
    }
    final taskId = _messageTaskId(message);
    return taskId != null && snapshotTaskIds.contains(taskId);
  }
  final isCodexTool = _isCodexToolSummaryMessage(message);
  if (isAiResponding &&
      activeTaskId != null &&
      _messageBelongsToTask(message, activeTaskId)) {
    return isCodexTool || _isInFlightCodexMessage(message);
  }
  if (isCodexTool) {
    final taskId = _messageTaskId(message);
    return taskId != null && snapshotTaskIds.contains(taskId);
  }
  return false;
}

Set<String> _remoteSnapshotTaskIds(List<ChatMessageModel> messages) {
  final ids = <String>{};
  for (final message in messages) {
    final taskId = _messageTaskId(message);
    if (taskId != null) {
      ids.add(taskId);
    }
  }
  return ids;
}

Map<String, int> _remoteUserMessageTextCounts(List<ChatMessageModel> messages) {
  final counts = <String, int>{};
  for (final message in messages) {
    if (message.type != 1 || message.user != 1) {
      continue;
    }
    final text = message.text?.trim();
    if (text == null || text.isEmpty) {
      continue;
    }
    counts[text] = (counts[text] ?? 0) + 1;
  }
  return counts;
}

Set<String> _remoteRuntimeUserMessageIdsToPreserve({
  required List<ChatMessageModel> existingMessages,
  required Set<String> snapshotMessageIds,
  required Map<String, int> snapshotUserTextCounts,
}) {
  final existingByText = <String, List<ChatMessageModel>>{};
  for (final message in existingMessages) {
    if (snapshotMessageIds.contains(message.id) ||
        message.type != 1 ||
        message.user != 1) {
      continue;
    }
    final text = message.text?.trim();
    if (text == null || text.isEmpty) {
      continue;
    }
    (existingByText[text] ??= <ChatMessageModel>[]).add(message);
  }
  final preserveIds = <String>{};
  existingByText.forEach((text, messages) {
    messages.sort((a, b) => b.createAt.compareTo(a.createAt));
    final preserveCount = messages.length - (snapshotUserTextCounts[text] ?? 0);
    if (preserveCount <= 0) {
      return;
    }
    for (
      var index = 0;
      index < preserveCount && index < messages.length;
      index += 1
    ) {
      preserveIds.add(messages[index].id);
    }
  });
  return preserveIds;
}

bool _messageBelongsToTask(ChatMessageModel message, String? taskId) {
  final normalizedTaskId = taskId?.trim() ?? '';
  if (normalizedTaskId.isEmpty) {
    return false;
  }
  return _messageTaskId(message) == normalizedTaskId;
}

String? _messageTaskId(ChatMessageModel message) {
  final cardData = message.cardData;
  final parentTaskId =
      _asCodexString(message.streamMeta?['parentTaskId']) ??
      _asCodexString(cardData?['taskId']) ??
      _asCodexString(cardData?['taskID']);
  return parentTaskId;
}

bool _isCodexToolSummaryMessage(ChatMessageModel message) {
  final cardData = message.cardData;
  return cardData?['type'] == 'agent_tool_summary' &&
      (cardData?['uiStyle'] ?? '').toString().trim() == 'codex_tool';
}

bool _isCodexRequestMessage(ChatMessageModel message) {
  final cardData = message.cardData;
  return cardData?['type'] == 'codex_request';
}

bool _isPendingCodexRequestMessage(ChatMessageModel message) {
  if (!_isCodexRequestMessage(message)) return false;
  final cardData = message.cardData;
  final status = _asCodexString(cardData?['status'])?.toLowerCase();
  return status == null ||
      status == 'pending' ||
      status == 'running' ||
      status == 'requested' ||
      status == 'open' ||
      status == 'progress';
}

bool _isInFlightCodexMessage(ChatMessageModel message) {
  final streamFinal = message.streamMeta?['isFinal'];
  if (streamFinal == false) {
    return true;
  }
  final cardData = message.cardData;
  if (cardData == null) {
    return message.isLoading;
  }
  if (cardData['type'] == 'deep_thinking' && cardData['isLoading'] == true) {
    return true;
  }
  final status = _asCodexString(cardData['status'])?.toLowerCase();
  return status == 'running' || status == 'pending' || status == 'progress';
}

@visibleForTesting
List<ChatMessageModel> mergeRemoteCodexSnapshotMessagesForTesting({
  required List<ChatMessageModel> snapshotMessages,
  required List<ChatMessageModel> existingMessages,
  required String? activeTaskId,
  required bool isAiResponding,
}) {
  return _mergeRemoteCodexSnapshotMessages(
    snapshotMessages: snapshotMessages,
    existingMessages: existingMessages,
    activeTaskId: activeTaskId,
    isAiResponding: isAiResponding,
  );
}

List<ChatMessageModel> _codexMessagesFromThreadResponse(
  Map<String, dynamic> response, {
  bool active = false,
  String? activeTurnId,
}) {
  final thread = _asCodexMap(response['thread']) ?? response;
  final rawTurns = thread['turns'] ?? response['turns'];
  if (rawTurns is! List) {
    return const <ChatMessageModel>[];
  }
  final chronological = <ChatMessageModel>[];
  final effectiveActiveTurnId =
      activeTurnId ??
      (active ? _codexLatestTurnIdFromThreadResponse(response) : null);
  var seq = 0;
  for (var turnIndex = 0; turnIndex < rawTurns.length; turnIndex += 1) {
    final turn = _asCodexMap(rawTurns[turnIndex]);
    if (turn == null) {
      continue;
    }
    final turnId = _codexTurnIdAt(rawTurns, turnIndex) ?? 'turn-$turnIndex';
    final isActiveTurn =
        active &&
        ((effectiveActiveTurnId != null && turnId == effectiveActiveTurnId) ||
            (effectiveActiveTurnId == null &&
                turnIndex == rawTurns.length - 1));
    final turnStartedAt =
        _codexTimeValueMs(turn['startedAt'] ?? turn['started_at']) ??
        DateTime.now().millisecondsSinceEpoch;
    final rawItems = _codexHistoricalItemsFromTurn(turn);
    if (rawItems.isEmpty) {
      continue;
    }
    for (var itemIndex = 0; itemIndex < rawItems.length; itemIndex += 1) {
      final item = rawItems[itemIndex];
      final itemType = canonicalCodexItemType(_asCodexString(item['type']));
      final itemId =
          _asCodexString(item['id']) ??
          _asCodexString(item['callId']) ??
          _asCodexString(item['call_id']) ??
          '$turnId-${_codexStableItemKey(item)}';
      final createdAt = DateTime.fromMillisecondsSinceEpoch(
        (_codexTimeValueMs(
                  item['createdAt'] ??
                      item['created_at'] ??
                      item['startedAt'] ??
                      item['started_at'],
                ) ??
                turnStartedAt) +
            itemIndex,
      );
      if (itemType == 'userMessage') {
        final userContent = _codexExtractUserMessageContent(
          item['content'] ??
              item['text'] ??
              item['message'] ??
              item['input'] ??
              item['text_elements'] ??
              item['parts'],
        );
        if (userContent.text.trim().isEmpty &&
            userContent.attachments.isEmpty) {
          continue;
        }
        final content = <String, dynamic>{
          'text': userContent.text,
          'id': '$itemId-codex-user',
        };
        if (userContent.attachments.isNotEmpty) {
          content['attachments'] = userContent.attachments;
        }
        chronological.add(
          ChatMessageModel(
            id: '$itemId-codex-user',
            type: 1,
            user: 1,
            content: content,
            createAt: createdAt,
          ),
        );
        continue;
      }
      if (itemType == 'agentMessage') {
        final text = _codexExtractText(
          item['text'] ?? item['message'] ?? item['content'],
        );
        if (text.trim().isEmpty) {
          continue;
        }
        seq += 1;
        final messageId = '$itemId-codex-agent';
        final isFinal = !isActiveTurn;
        chronological.add(
          ChatMessageModel(
            id: messageId,
            type: 1,
            user: 2,
            content: {'text': text, 'id': messageId},
            createAt: createdAt,
            streamMeta: ensureAgentStreamMessageMeta(
              null,
              seq: seq,
              roundIndex: seq,
              kind: 'text_snapshot',
              parentTaskId: turnId,
              entryId: messageId,
              isFinal: isFinal,
            ),
          ),
        );
        continue;
      }
      if (itemType == 'reasoning') {
        final text = _codexExtractText(
          item['summary'] ?? item['text'] ?? item['content'],
        );
        if (text.trim().isEmpty && !isActiveTurn) {
          continue;
        }
        seq += 1;
        final cardId = '$itemId-codex-thinking';
        // Reasoning items only collapse once the entire turn ends. While the
        // turn is active, all reasoning cards stay in "正在思考" + expanded —
        // even if a per-item status flips to "completed" mid-turn.
        final isLoading = isActiveTurn;
        final stage = isLoading
            ? ThinkingStage.thinking.value
            : ThinkingStage.complete.value;
        chronological.add(
          ChatMessageModel.cardMessage(
            {
              'type': 'deep_thinking',
              'isLoading': isLoading,
              'thinkingContent': text,
              'stage': stage,
              'taskID': turnId,
              'cardId': cardId,
              'startTime': createdAt.millisecondsSinceEpoch,
              'endTime': isLoading ? null : createdAt.millisecondsSinceEpoch,
              'isCollapsible': !isLoading,
            },
            id: cardId,
            streamMeta: ensureAgentStreamMessageMeta(
              null,
              seq: seq,
              roundIndex: seq,
              kind: 'thinking_snapshot',
              parentTaskId: turnId,
              entryId: cardId,
              isFinal: !isLoading,
            ),
          ).copyWith(createAt: createdAt),
        );
        continue;
      }
      if (_codexHistoricalRequestItemTypes.contains(itemType)) {
        seq += 1;
        final requestKind = itemType == 'requestApproval'
            ? 'approval'
            : 'user_input';
        final question = _codexHistoricalFirstQuestion(item);
        final cardSuffix = requestKind == 'approval'
            ? 'approval'
            : 'user-input';
        final cardId = '$itemId-codex-$cardSuffix';
        final title = requestKind == 'approval'
            ? _codexHistoricalApprovalTitle(item)
            : question.title;
        final detail = requestKind == 'approval'
            ? _codexHistoricalApprovalDetail(item)
            : question.detail;
        final status = _codexHistoricalRequestStatus(
          item,
          requestKind: requestKind,
        );
        chronological.add(
          ChatMessageModel.cardMessage(
            <String, dynamic>{
              'type': 'codex_request',
              'taskId': turnId,
              'requestId':
                  _asCodexString(item['requestId']) ??
                  _asCodexString(item['request_id']) ??
                  _asCodexString(item['id']) ??
                  itemId,
              'requestKind': requestKind,
              'title': title,
              'detail': detail,
              if (requestKind == 'user_input') 'questionId': question.id,
              'rawParamsJson': _safeCodexJson(item),
              'status': status,
              'cardId': cardId,
              'startTime': createdAt.millisecondsSinceEpoch,
            },
            id: cardId,
            streamMeta: ensureAgentStreamMessageMeta(
              null,
              seq: seq,
              roundIndex: seq,
              kind: requestKind == 'approval'
                  ? 'permission_required'
                  : 'clarify_required',
              parentTaskId: turnId,
              entryId: cardId,
              isFinal: status != 'pending',
            ),
          ).copyWith(createAt: createdAt),
        );
        continue;
      }
      if (_codexHistoricalToolOutputItemTypes.contains(itemType)) {
        final outputText = _codexRawOutputText(item).trimRight();
        final callId =
            _asCodexString(item['callId']) ?? _asCodexString(item['call_id']);
        final existingIndex = callId == null
            ? -1
            : _codexFindToolMessageIndexForCallId(chronological, callId);
        if (existingIndex != -1) {
          final existing = chronological[existingIndex];
          final existingCardData = Map<String, dynamic>.from(
            existing.cardData ?? const <String, dynamic>{},
          );
          final existingToolType = (existingCardData['toolType'] ?? '')
              .toString();
          final terminalOutput = existingToolType == 'terminal'
              ? [
                  (existingCardData['terminalOutput'] ?? '')
                      .toString()
                      .trimRight(),
                  outputText,
                ].where((part) => part.isNotEmpty).join('\n')
              : (existingCardData['terminalOutput'] ?? '').toString();
          final summary = outputText.isNotEmpty
              ? _truncateCodexText(outputText, 96)
              : (existingCardData['summary'] ?? '').toString();
          existingCardData.addAll(<String, dynamic>{
            'status': 'success',
            'summary': summary,
            'progress': summary,
            'resultPreviewJson': _safeCodexJson(item['output'] ?? item),
            'rawResultJson': _safeCodexJson(item),
            'terminalOutput': terminalOutput,
            'terminalOutputDelta': '',
            'showTerminalOutput':
                terminalOutput.isNotEmpty || existingToolType == 'terminal',
          });
          final existingSeq = _asCodexInt(existing.streamMeta?['seq']) ?? seq;
          chronological[existingIndex] = existing.copyWith(
            content: {'cardData': existingCardData, 'id': existing.id},
            streamMeta: ensureAgentStreamMessageMeta(
              existing.streamMeta,
              seq: existingSeq,
              roundIndex: existingSeq,
              kind: 'tool_completed',
              parentTaskId: turnId,
              entryId: existing.id,
              isFinal: true,
            ),
          );
          continue;
        }
        seq += 1;
        final outputItemId = itemId.startsWith('$turnId-item-')
            ? '$turnId-${_codexStableItemKey(item)}'
            : itemId;
        final toolInfo = normalizeCodexToolCall(
          item,
          itemType: itemType,
          fallbackToolType: itemType == 'tool_search_output'
              ? 'search'
              : 'tool',
          fallbackStatus: 'success',
        );
        final toolKind = codexToolCardSuffix(
          toolInfo.toolType,
          itemType: itemType,
        );
        final cardId = '$outputItemId-codex-$toolKind';
        final summary = outputText.isNotEmpty
            ? _truncateCodexText(outputText, 96)
            : toolInfo.summary;
        chronological.add(
          ChatMessageModel.cardMessage(
            <String, dynamic>{
              'type': 'agent_tool_summary',
              'uiStyle': 'codex_tool',
              'taskId': turnId,
              'toolName': toolInfo.toolName,
              'displayName': toolInfo.displayName,
              'toolTitle': toolInfo.toolTitle,
              'cardId': cardId,
              'toolType': toolInfo.toolType,
              if (toolInfo.serverName != null)
                'serverName': toolInfo.serverName,
              'status': 'success',
              'summary': summary,
              'progress': summary,
              'argsJson': toolInfo.argsJson,
              'resultPreviewJson': toolInfo.resultPreviewJson,
              'rawResultJson': toolInfo.rawResultJson,
              'terminalOutput': toolInfo.toolType == 'terminal'
                  ? outputText
                  : '',
              'terminalOutputDelta': '',
              'showTerminalOutput': toolInfo.toolType == 'terminal',
              'showRawResult': true,
            },
            id: cardId,
            streamMeta: ensureAgentStreamMessageMeta(
              null,
              seq: seq,
              roundIndex: seq,
              kind: 'tool_completed',
              parentTaskId: turnId,
              entryId: cardId,
              isFinal: true,
            ),
          ).copyWith(createAt: createdAt),
        );
        continue;
      }
      if (_codexHistoricalToolItemTypes.contains(itemType)) {
        seq += 1;
        final toolInfo = normalizeCodexToolCall(
          item,
          itemType: itemType,
          fallbackStatus: 'success',
        );
        final toolKind = codexToolCardSuffix(
          toolInfo.toolType,
          itemType: itemType,
        );
        final cardId = '$itemId-codex-$toolKind';
        final itemActivity = _codexActivityFromValue(
          item['status'] ?? item['state'],
        );
        final isRunning = isActiveTurn && itemActivity?.active != false;
        final normalizedStatus = toolInfo.status == 'running' && !isRunning
            ? 'success'
            : toolInfo.status;
        final status = isRunning ? 'running' : normalizedStatus;
        final toolTitle = toolInfo.toolTitle;
        final summary = _codexExtractText(
          item['summary'] ??
              item['status'] ??
              item['output'] ??
              item['text'] ??
              item['content'],
        );
        final rawJson = toolInfo.rawResultJson.isNotEmpty
            ? toolInfo.rawResultJson
            : _safeCodexJson(item);
        final terminalOutput = toolInfo.terminalOutput.isNotEmpty
            ? toolInfo.terminalOutput
            : _codexExtractText(item['output']);
        final diffText = toolInfo.toolType == 'file'
            ? extractCodexDiffText(
                    item,
                    outputText: terminalOutput,
                    progress: summary,
                    summary: summary,
                  ) ??
                  ''
            : '';
        final diffSummary = diffText.isEmpty
            ? null
            : parseCodexDiffText(diffText);
        final diffPreview = diffSummary == null
            ? ''
            : summarizeCodexDiff(diffSummary);
        final effectiveSummary = toolKind == 'file' && diffPreview.isNotEmpty
            ? diffPreview
            : summary.isNotEmpty
            ? summary
            : toolInfo.summary;
        final effectiveProgress = toolKind == 'file' && diffPreview.isNotEmpty
            ? diffPreview
            : toolInfo.progress.isNotEmpty
            ? toolInfo.progress
            : summary;
        final filePath = toolInfo.toolType == 'file'
            ? extractCodexDiffPath(item) ??
                  (diffSummary?.primaryPath.trim().isNotEmpty == true
                      ? diffSummary!.primaryPath
                      : null)
            : null;
        final cardData = <String, dynamic>{
          'type': 'agent_tool_summary',
          'uiStyle': 'codex_tool',
          'taskId': turnId,
          'toolName': toolInfo.toolName,
          'displayName': toolInfo.displayName,
          'toolTitle': toolTitle,
          'cardId': cardId,
          'toolType': toolInfo.toolType,
          if (toolInfo.serverName != null) 'serverName': toolInfo.serverName,
          'status': status,
          'summary': effectiveSummary,
          'progress': effectiveProgress,
          'argsJson': toolInfo.argsJson,
          'resultPreviewJson': toolInfo.resultPreviewJson,
          'rawResultJson': rawJson,
          'terminalOutput': terminalOutput,
          'terminalOutputDelta': '',
          'showTerminalOutput': toolInfo.toolType == 'terminal',
          'showRawResult': true,
        };
        if (toolInfo.toolType == 'file') {
          cardData.addAll(<String, dynamic>{
            'diffText': diffText,
            'showDiff': diffText.isNotEmpty,
            'filePath': filePath ?? '',
            'changedFiles': diffSummary?.changedFileCount ?? 0,
            'additions': diffSummary?.additions ?? 0,
            'deletions': diffSummary?.deletions ?? 0,
          });
        }
        chronological.add(
          ChatMessageModel.cardMessage(
            cardData,
            id: cardId,
            streamMeta: ensureAgentStreamMessageMeta(
              null,
              seq: seq,
              roundIndex: seq,
              kind: isRunning ? 'tool_progress' : 'tool_completed',
              parentTaskId: turnId,
              entryId: cardId,
              isFinal: !isRunning,
            ),
          ).copyWith(createAt: createdAt),
        );
      }
    }
  }
  final messages = chronological.reversed.toList(growable: false);
  return _normalizeCodexLoadingThinkingCards(
    messages,
    activeTaskId: effectiveActiveTurnId,
    isAiResponding: active,
  );
}

@visibleForTesting
List<ChatMessageModel> codexMessagesFromThreadResponseForTesting(
  Map<String, dynamic> response, {
  bool active = false,
  String? activeTurnId,
}) {
  return _codexMessagesFromThreadResponse(
    response,
    active: active,
    activeTurnId: activeTurnId,
  );
}

@visibleForTesting
String? codexActiveTurnIdFromThreadResponseForTesting(
  Map<String, dynamic> response,
) {
  return _codexActiveTurnIdFromThreadResponse(response);
}

@visibleForTesting
bool codexLatestTurnLooksExternallyActiveForTesting(
  Map<String, dynamic> response,
) {
  return _codexLatestTurnLooksExternallyActive(response);
}

Map<String, dynamic>? _asCodexMap(dynamic value) {
  if (value is! Map) {
    return null;
  }
  return value.map((key, nestedValue) {
    return MapEntry(key.toString(), nestedValue);
  });
}

class _CodexUserMessageContent {
  const _CodexUserMessageContent({
    required this.text,
    required this.attachments,
  });

  final String text;
  final List<Map<String, dynamic>> attachments;
}

_CodexUserMessageContent _codexExtractUserMessageContent(dynamic value) {
  final text = StringBuffer();
  final attachments = <Map<String, dynamic>>[];

  void visit(dynamic node) {
    if (node == null) return;
    if (node is String) {
      text.write(node);
      return;
    }
    if (node is num || node is bool) {
      text.write(node);
      return;
    }
    if (node is List) {
      for (final child in node) {
        visit(child);
      }
      return;
    }

    final map = _asCodexMap(node);
    if (map == null) {
      final fallback = node.toString();
      if (fallback.isNotEmpty) {
        text.write(fallback);
      }
      return;
    }

    final type = _asCodexString(map['type'])?.toLowerCase();
    if (_codexBlockTypeLooksText(type)) {
      final blockText = _codexExtractText(
        map['text'] ?? map['content'] ?? map['value'] ?? map['input'],
      );
      if (blockText.isNotEmpty) {
        text.write(blockText);
      }
      return;
    }

    if (_codexMapLooksLikeImageBlock(map)) {
      final attachment = _codexImageAttachmentFromBlock(
        map,
        attachments.length,
      );
      if (attachment != null) {
        attachments.add(attachment);
      }
      return;
    }

    for (final key in const <String>[
      'text',
      'content',
      'message',
      'input',
      'value',
      'delta',
      'summary',
      'text_elements',
      'parts',
      'attachments',
      'images',
    ]) {
      if (!map.containsKey(key)) continue;
      final beforeTextLength = text.length;
      final beforeAttachmentLength = attachments.length;
      visit(map[key]);
      if (text.length != beforeTextLength ||
          attachments.length != beforeAttachmentLength) {
        return;
      }
    }

    final fallback = _codexExtractText(map);
    if (fallback.isNotEmpty) {
      text.write(fallback);
    }
  }

  visit(value);
  return _CodexUserMessageContent(
    text: text.toString(),
    attachments: List<Map<String, dynamic>>.unmodifiable(attachments),
  );
}

bool _codexBlockTypeLooksText(String? type) {
  if (type == null) return false;
  final normalized = type.replaceAll('-', '_');
  return normalized == 'text' ||
      normalized == 'input_text' ||
      normalized == 'message_text';
}

bool _codexBlockTypeLooksImage(String? type) {
  if (type == null) return false;
  final normalized = type.replaceAll('-', '_');
  return normalized == 'image' ||
      normalized == 'input_image' ||
      normalized == 'image_url' ||
      normalized == 'screenshot' ||
      normalized.endsWith('_image');
}

bool _codexMapLooksLikeImageBlock(Map<String, dynamic> map) {
  final type = _asCodexString(map['type'])?.toLowerCase();
  if (_codexBlockTypeLooksImage(type)) {
    return true;
  }
  final mimeType = _asCodexString(
    map['mimeType'] ??
        map['mime_type'] ??
        map['mediaType'] ??
        map['media_type'],
  )?.toLowerCase();
  if (mimeType?.startsWith('image/') == true) {
    return true;
  }
  for (final key in const <String>[
    'image',
    'imageUrl',
    'image_url',
    'dataUrl',
    'data_url',
  ]) {
    if (map.containsKey(key)) {
      return true;
    }
  }
  return false;
}

Map<String, dynamic>? _codexImageAttachmentFromBlock(
  Map<String, dynamic> map,
  int index,
) {
  final source =
      _codexImageStringFromValue(map['dataUrl']) ??
      _codexImageStringFromValue(map['data_url']) ??
      _codexImageStringFromValue(map['url']) ??
      _codexImageStringFromValue(map['imageUrl']) ??
      _codexImageStringFromValue(map['image_url']) ??
      _codexImageStringFromValue(map['image']) ??
      _codexImageStringFromValue(map['src']) ??
      _codexImageStringFromValue(map['source']);
  final path =
      _asCodexString(
        map['path'] ??
            map['filePath'] ??
            map['file_path'] ??
            map['filename'] ??
            map['fileName'],
      ) ??
      (source != null && !_codexImageSourceIsUrl(source) ? source : null);
  final url = source != null && _codexImageSourceIsUrl(source) ? source : null;
  final rawBase64 = _codexImageBase64FromBlock(map);
  final mimeType = _codexImageMimeType(
    explicit:
        map['mimeType'] ??
        map['mime_type'] ??
        map['mediaType'] ??
        map['media_type'],
    source: url,
    path: path,
  );
  final dataUrl = url?.startsWith('data:') == true
      ? url
      : (rawBase64 == null
            ? null
            : 'data:${mimeType ?? 'image/png'};base64,$rawBase64');
  final effectiveUrl = dataUrl ?? url;
  final effectivePath = dataUrl == null && url == null ? path : null;

  if ((effectiveUrl ?? '').isEmpty && (effectivePath ?? '').isEmpty) {
    return null;
  }

  final attachment = <String, dynamic>{
    'id': 'codex-image-$index',
    'name': _codexImageAttachmentName(
      map: map,
      source: effectiveUrl,
      path: effectivePath,
      mimeType: mimeType,
      index: index,
    ),
    'isImage': true,
    'sendToModel': true,
  };
  if (mimeType != null) {
    attachment['mimeType'] = mimeType;
  }
  if (dataUrl != null) {
    attachment['dataUrl'] = dataUrl;
  } else if (effectiveUrl != null) {
    attachment['url'] = effectiveUrl;
  }
  if (effectivePath != null) {
    attachment['path'] = effectivePath;
  }
  return attachment;
}

String? _codexImageStringFromValue(dynamic value) {
  if (value == null) return null;
  if (value is String) {
    final text = value.trim();
    return text.isEmpty ? null : text;
  }
  final map = _asCodexMap(value);
  if (map != null) {
    for (final key in const <String>[
      'url',
      'dataUrl',
      'data_url',
      'src',
      'source',
      'path',
    ]) {
      final nested = _codexImageStringFromValue(map[key]);
      if (nested != null) {
        return nested;
      }
    }
  }
  return null;
}

String? _codexImageBase64FromBlock(Map<String, dynamic> map) {
  final raw = _asCodexString(map['base64'] ?? map['b64_json']);
  if (raw == null || raw.startsWith('data:')) {
    return null;
  }
  return raw;
}

bool _codexImageSourceIsUrl(String value) {
  final normalized = value.trim().toLowerCase();
  return normalized.startsWith('data:') ||
      normalized.startsWith('http://') ||
      normalized.startsWith('https://');
}

String? _codexImageMimeType({
  required dynamic explicit,
  required String? source,
  required String? path,
}) {
  final explicitText = _asCodexString(explicit)?.toLowerCase();
  if (explicitText != null) {
    return explicitText.startsWith('image/')
        ? explicitText
        : 'image/$explicitText';
  }
  final dataMime = _codexMimeTypeFromDataUrl(source);
  if (dataMime != null) {
    return dataMime;
  }
  return _codexImageMimeTypeFromPath(path ?? source ?? '');
}

String? _codexMimeTypeFromDataUrl(String? value) {
  final source = value?.trim() ?? '';
  if (!source.toLowerCase().startsWith('data:')) {
    return null;
  }
  final comma = source.indexOf(',');
  final meta = comma == -1 ? source.substring(5) : source.substring(5, comma);
  final mime = meta.split(';').first.trim().toLowerCase();
  return mime.startsWith('image/') ? mime : null;
}

String? _codexImageMimeTypeFromPath(String value) {
  final path = value.split('?').first.split('#').first.toLowerCase();
  if (path.endsWith('.png')) return 'image/png';
  if (path.endsWith('.jpg') || path.endsWith('.jpeg')) return 'image/jpeg';
  if (path.endsWith('.gif')) return 'image/gif';
  if (path.endsWith('.webp')) return 'image/webp';
  if (path.endsWith('.bmp')) return 'image/bmp';
  if (path.endsWith('.heic')) return 'image/heic';
  if (path.endsWith('.heif')) return 'image/heif';
  return null;
}

String _codexImageAttachmentName({
  required Map<String, dynamic> map,
  required String? source,
  required String? path,
  required String? mimeType,
  required int index,
}) {
  final explicitName = _asCodexString(
    map['name'] ?? map['fileName'] ?? map['filename'],
  );
  if (explicitName != null) {
    return explicitName;
  }
  final pathName = _codexPathNameWithoutQuery(path);
  if (pathName != null) {
    return pathName;
  }
  final sourceName = _codexPathNameWithoutQuery(source);
  if (sourceName != null) {
    return sourceName;
  }
  final extension = switch (mimeType) {
    'image/jpeg' => 'jpg',
    'image/gif' => 'gif',
    'image/webp' => 'webp',
    'image/bmp' => 'bmp',
    'image/heic' => 'heic',
    'image/heif' => 'heif',
    _ => 'png',
  };
  return index == 0 ? 'image.$extension' : 'image-${index + 1}.$extension';
}

String? _codexPathNameWithoutQuery(String? value) {
  final raw = value?.trim() ?? '';
  if (raw.isEmpty || raw.toLowerCase().startsWith('data:')) {
    return null;
  }
  final withoutQuery = raw.split('?').first.split('#').first;
  return _codexLastPathSegment(withoutQuery);
}

String _codexExtractText(dynamic value) {
  if (value == null) return '';
  if (value is String) return value;
  if (value is num || value is bool) return value.toString();
  if (value is List) {
    return value.map(_codexExtractText).where((text) => text.isNotEmpty).join();
  }
  final map = _asCodexMap(value);
  if (map != null) {
    for (final key in const <String>[
      'text',
      'content',
      'message',
      'input',
      'value',
      'delta',
      'summary',
      'text_elements',
      'parts',
    ]) {
      final text = _codexExtractText(map[key]);
      if (text.isNotEmpty) {
        return text;
      }
    }
  }
  return value.toString();
}

int? _codexTimeValueMs(dynamic value) {
  if (value == null) return null;
  if (value is num) {
    final raw = value.toInt();
    return raw < 100000000000 ? raw * 1000 : raw;
  }
  final text = value.toString().trim();
  if (text.isEmpty) return null;
  final rawInt = int.tryParse(text);
  if (rawInt != null) {
    return rawInt < 100000000000 ? rawInt * 1000 : rawInt;
  }
  return DateTime.tryParse(text)?.millisecondsSinceEpoch;
}

String _truncateCodexText(String text, int maxLength) {
  final normalized = text.trim().replaceAll(RegExp(r'\s+'), ' ');
  if (normalized.length <= maxLength) {
    return normalized;
  }
  return '${normalized.substring(0, maxLength)}...';
}

String _safeCodexJson(dynamic value) {
  try {
    return const JsonEncoder.withIndent('  ').convert(value);
  } catch (_) {
    return value?.toString() ?? '';
  }
}

class _CodexHistoricalQuestion {
  const _CodexHistoricalQuestion({
    required this.id,
    required this.title,
    required this.detail,
  });

  final String id;
  final String title;
  final String detail;
}

_CodexHistoricalQuestion _codexHistoricalFirstQuestion(
  Map<String, dynamic> item,
) {
  final params = _asCodexMap(item['params']);
  final questions = item['questions'] ?? params?['questions'];
  if (questions is List && questions.isNotEmpty) {
    final first = _asCodexMap(questions.first);
    if (first != null) {
      final id =
          _asCodexString(first['id']) ??
          _asCodexString(first['questionId']) ??
          'answer';
      final title =
          _codexFirstText([
            first['label'],
            first['title'],
            first['question'],
          ]) ??
          'Codex needs input';
      final detail =
          _codexFirstText([first['description'], first['placeholder']]) ??
          title;
      return _CodexHistoricalQuestion(id: id, title: title, detail: detail);
    }
  }
  final id =
      _asCodexString(item['questionId']) ??
      _asCodexString(item['question_id']) ??
      _asCodexString(item['id']) ??
      'answer';
  final title =
      _codexFirstText([
        item['question'],
        item['title'],
        params?['question'],
        params?['title'],
      ]) ??
      'Codex needs input';
  final detail =
      _codexFirstText([
        item['description'],
        item['placeholder'],
        params?['description'],
        params?['placeholder'],
      ]) ??
      title;
  return _CodexHistoricalQuestion(id: id, title: title, detail: detail);
}

String _codexHistoricalApprovalTitle(Map<String, dynamic> item) {
  final command = _codexFirstText([
    item['command'],
    _asCodexMap(item['action'])?['command'],
    _asCodexMap(item['params'])?['command'],
  ]);
  if (command != null) {
    return _truncateCodexText(command, 48);
  }
  return 'Codex approval';
}

String _codexHistoricalApprovalDetail(Map<String, dynamic> item) {
  return _codexFirstText([
        item['reason'],
        item['description'],
        item['command'],
        _asCodexMap(item['params'])?['reason'],
        _asCodexMap(item['params'])?['description'],
        _asCodexMap(item['params'])?['command'],
      ]) ??
      _safeCodexJson(item);
}

String _codexHistoricalRequestStatus(
  Map<String, dynamic> item, {
  required String requestKind,
}) {
  final explicit = _codexNormalizeHistoricalRequestStatus(
    _codexFirstText([
      item['status'],
      item['state'],
      item['requestStatus'],
      item['request_status'],
      _asCodexMap(item['request'])?['status'],
      _asCodexMap(item['request'])?['state'],
    ]),
    requestKind: requestKind,
  );
  if (explicit != null && explicit != 'pending') {
    return explicit;
  }
  final response =
      item['response'] ??
      item['answer'] ??
      item['answers'] ??
      item['result'] ??
      item['decision'];
  if (_codexHasRequestResponse(response)) {
    if (requestKind == 'approval') {
      final decision = _codexNormalizeHistoricalRequestStatus(
        _codexFirstText([
          item['decision'],
          _asCodexMap(response)?['decision'],
          _asCodexMap(response)?['status'],
          _asCodexMap(response)?['state'],
        ]),
        requestKind: requestKind,
      );
      if (decision == 'accepted' || decision == 'declined') {
        return decision!;
      }
      return 'accepted';
    }
    return 'submitted';
  }
  return explicit ?? 'pending';
}

String? _codexNormalizeHistoricalRequestStatus(
  String? value, {
  required String requestKind,
}) {
  final normalized = value?.trim().toLowerCase() ?? '';
  if (normalized.isEmpty) {
    return null;
  }
  return switch (normalized) {
    'accept' || 'accepted' || 'approve' || 'approved' => 'accepted',
    'decline' || 'declined' || 'reject' || 'rejected' => 'declined',
    'submit' || 'submitted' || 'answered' => 'submitted',
    'complete' ||
    'completed' => requestKind == 'approval' ? 'accepted' : 'submitted',
    'fail' || 'failed' || 'error' => 'failed',
    'pending' || 'running' || 'requested' || 'open' => 'pending',
    _ => normalized,
  };
}

bool _codexHasRequestResponse(dynamic value) {
  if (value == null) {
    return false;
  }
  if (value is String) {
    return value.trim().isNotEmpty;
  }
  if (value is Iterable) {
    return value.isNotEmpty;
  }
  if (value is Map) {
    return value.isNotEmpty;
  }
  return true;
}

String? _codexFirstText(Iterable<dynamic> values) {
  for (final value in values) {
    final text = _codexExtractText(value).trim();
    if (text.isNotEmpty) {
      return text;
    }
  }
  return null;
}

const Set<String> _codexHistoricalToolItemTypes = <String>{
  'commandExecution',
  'local_shell_call',
  'commandExec',
  'processExecution',
  'fileChange',
  'tool',
  'mcpToolCall',
  'dynamicToolCall',
  'function_call',
  'custom_tool_call',
  'tool_search_call',
  'webSearch',
  'web_search_call',
  'imageView',
  'imageGeneration',
  'image_generation_call',
  'collabAgentToolCall',
  'collabToolCall',
  'plan',
};

const Set<String> _codexHistoricalRequestItemTypes = <String>{
  'requestUserInput',
  'requestApproval',
};

const Set<String> _codexHistoricalToolOutputItemTypes = <String>{
  'function_call_output',
  'custom_tool_call_output',
  'tool_search_output',
};

int _codexFindToolMessageIndexForCallId(
  List<ChatMessageModel> messages,
  String callId,
) {
  final normalizedCallId = callId.trim();
  if (normalizedCallId.isEmpty) {
    return -1;
  }
  for (var index = messages.length - 1; index >= 0; index -= 1) {
    final cardData = messages[index].cardData;
    if ((cardData?['type'] ?? '').toString() != 'agent_tool_summary') {
      continue;
    }
    if (_codexToolCardContainsCallId(cardData!, normalizedCallId)) {
      return index;
    }
  }
  return -1;
}

bool _codexToolCardContainsCallId(
  Map<String, dynamic> cardData,
  String callId,
) {
  for (final key in const <String>[
    'rawResultJson',
    'resultPreviewJson',
    'argsJson',
  ]) {
    final text = (cardData[key] ?? '').toString().trim();
    if (text.isEmpty) {
      continue;
    }
    try {
      if (_codexValueContainsCallId(jsonDecode(text), callId)) {
        return true;
      }
    } catch (_) {
      continue;
    }
  }
  return false;
}

bool _codexValueContainsCallId(dynamic value, String callId) {
  if (value == null) {
    return false;
  }
  if (value is String || value is num || value is bool) {
    return value.toString() == callId;
  }
  final map = _asCodexMap(value);
  if (map != null) {
    final direct =
        _asCodexString(map['callId']) ??
        _asCodexString(map['call_id']) ??
        _asCodexString(map['id']);
    if (direct == callId) {
      return true;
    }
    return map.values.any(
      (nested) => _codexValueContainsCallId(nested, callId),
    );
  }
  if (value is List) {
    return value.any((nested) => _codexValueContainsCallId(nested, callId));
  }
  return false;
}

String _codexRawOutputText(Map<String, dynamic> item) {
  final output = item['output'];
  final text = _codexExtractText(
    output ?? item['tools'] ?? item['result'] ?? item['content'],
  );
  if (text.trim().isNotEmpty) {
    return text;
  }
  if (output != null) {
    return _safeCodexJson(output);
  }
  return '';
}

String _codexStableItemKey(Map<String, dynamic> item) {
  final stablePayload = <String, dynamic>{
    'type': item['type'],
    'name': item['name'],
    'namespace': item['namespace'],
    'arguments': item['arguments'],
    'action': item['action'],
    'execution': item['execution'],
    'query': item['query'],
    'output': item['output'],
    'status': item['status'],
  };
  var hash = 0x811c9dc5;
  for (final codeUnit in _safeCodexJson(stablePayload).codeUnits) {
    hash ^= codeUnit;
    hash = (hash * 0x01000193) & 0xffffffff;
  }
  return 'raw-${hash.toRadixString(16).padLeft(8, '0')}';
}

String? _codexLastPathSegment(String path) {
  final normalized = path.trim().replaceAll(RegExp(r'/+$'), '');
  if (normalized.isEmpty) {
    return null;
  }
  final parts = normalized.split('/').where((part) => part.isNotEmpty).toList();
  if (parts.isEmpty) {
    return normalized == '/' ? '/' : null;
  }
  return parts.last;
}

List<String> _extractCodexOptionIds(
  Map<String, dynamic> response,
  List<String> listKeys,
) {
  final rawItems = _collectCodexListItems(response, listKeys);
  final seen = <String>{};
  final result = <String>[];
  for (final item in rawItems) {
    final id = _codexOptionId(item);
    if (id == null || !seen.add(id)) {
      continue;
    }
    result.add(id);
  }
  return result;
}

List<String> _mergeCodexOptionIds({
  String? current,
  String? preferred,
  required List<String> options,
}) {
  final seen = <String>{};
  final result = <String>[];
  void add(String? value) {
    final text = value?.trim() ?? '';
    if (text.isEmpty || !seen.add(text)) {
      return;
    }
    result.add(text);
  }

  add(current);
  add(preferred);
  for (final option in options) {
    add(option);
  }
  return result;
}

List<dynamic> _collectCodexListItems(
  Map<String, dynamic> response,
  List<String> listKeys,
) {
  final normalizedKeys = listKeys.map(_normalizeCodexResponseKey).toSet();
  final rawItems = <dynamic>[];

  void visitMap(Map<dynamic, dynamic> map) {
    for (final entry in map.entries) {
      final key = _normalizeCodexResponseKey(entry.key.toString());
      final value = entry.value;
      if (value is List) {
        if (normalizedKeys.contains(key)) {
          rawItems.addAll(value);
        }
        for (final item in value) {
          final nested = _asCodexMap(item);
          if (nested != null) {
            visitMap(nested);
          }
        }
      } else {
        final nested = _asCodexMap(value);
        if (nested != null) {
          visitMap(nested);
        }
      }
    }
  }

  visitMap(response);
  if (rawItems.isEmpty) {
    for (final value in response.values) {
      if (value is List) {
        rawItems.addAll(value);
      }
    }
  }
  return rawItems;
}

String _normalizeCodexResponseKey(String key) {
  return key.toLowerCase().replaceAll(RegExp(r'[_-]'), '');
}

String? _extractCodexPreferredOptionId(Map<String, dynamic> response) {
  for (final key in const <String>[
    'currentModel',
    'currentModelId',
    'selectedModel',
    'selectedModelId',
    'activeModel',
    'activeModelId',
    'defaultModel',
    'defaultModelId',
    'model',
    'modelId',
  ]) {
    final id = _codexOptionId(response[key]);
    if (id != null) {
      return id;
    }
  }
  for (final key in const <String>[
    'current',
    'selected',
    'active',
    'default',
  ]) {
    final value = response[key];
    if (value is Map) {
      final id = _codexOptionId(value);
      if (id != null) {
        return id;
      }
    }
  }
  return null;
}

String? _extractCodexDefaultModelId(Map<String, dynamic> response) {
  for (final item in _collectCodexListItems(
    response,
    _kCodexModelListResponseKeys,
  )) {
    final map = _asCodexMap(item);
    if (map == null) {
      continue;
    }
    final isDefault = map['isDefault'] == true || map['default'] == true;
    if (!isDefault) {
      continue;
    }
    final id = _codexOptionId(map);
    if (id != null) {
      return id;
    }
  }
  return null;
}

String? _extractCodexModelDefaultReasoningEffort(
  Map<String, dynamic> response,
  String? modelId,
) {
  final normalizedModelId = modelId?.trim();
  for (final item in _collectCodexListItems(
    response,
    _kCodexModelListResponseKeys,
  )) {
    final map = _asCodexMap(item);
    if (map == null) {
      continue;
    }
    if (normalizedModelId != null &&
        normalizedModelId.isNotEmpty &&
        !_codexModelItemMatches(map, normalizedModelId)) {
      continue;
    }
    final effort = _normalizeCodexReasoningEffort(
      map['defaultReasoningEffort'] ??
          map['default_reasoning_effort'] ??
          map['defaultReasoningLevel'] ??
          map['default_reasoning_level'] ??
          map['reasoningEffort'] ??
          map['reasoning_effort'],
    );
    if (effort != null) {
      return effort;
    }
  }
  return null;
}

bool _codexModelItemMatches(
  Map<String, dynamic> item,
  String normalizedModelId,
) {
  for (final key in const <String>[
    'id',
    'model',
    'modelId',
    'model_id',
    'slug',
    'value',
    'name',
  ]) {
    final text = item[key]?.toString().trim();
    if (text == normalizedModelId) {
      return true;
    }
  }
  return false;
}

String? _extractCodexConfigModelId(Map<String, dynamic> response) {
  final direct = _codexOptionId(response['model'] ?? response['modelId']);
  if (direct != null) {
    return direct;
  }
  for (final key in const <String>[
    'config',
    'effectiveConfig',
    'effective',
    'settings',
    'data',
    'result',
  ]) {
    final value = response[key];
    if (value is Map) {
      final id = _codexOptionId(value['model'] ?? value['modelId']);
      if (id != null) {
        return id;
      }
      final nested = _extractCodexConfigModelId(
        value.map((key, nestedValue) => MapEntry(key.toString(), nestedValue)),
      );
      if (nested != null) {
        return nested;
      }
    }
  }
  return null;
}

String? _extractCodexConfigReasoningEffort(Map<String, dynamic> response) {
  final direct = _normalizeCodexReasoningEffort(
    response['model_reasoning_effort'] ??
        response['reasoning_effort'] ??
        response['reasoningEffort'] ??
        response['effort'],
  );
  if (direct != null) {
    return direct;
  }
  for (final key in const <String>[
    'config',
    'effectiveConfig',
    'effective',
    'settings',
    'modelSettings',
    'model_settings',
    'data',
    'result',
  ]) {
    final value = response[key];
    if (value is Map) {
      final nested = _extractCodexConfigReasoningEffort(
        value.map((key, nestedValue) => MapEntry(key.toString(), nestedValue)),
      );
      if (nested != null) {
        return nested;
      }
    }
  }
  return null;
}

List<String> _extractCodexReasoningEffortOptions(
  Map<String, dynamic> response,
) {
  final rawItems = <dynamic>[];
  for (final key in const <String>[
    'reasoningEfforts',
    'reasoning_efforts',
    'efforts',
    'modelReasoningEfforts',
    'model_reasoning_efforts',
  ]) {
    final value = response[key];
    if (value is List) {
      rawItems.addAll(value);
    }
  }
  for (final value in response.values) {
    if (value is Map) {
      rawItems.addAll(
        _extractCodexReasoningEffortOptions(
          value.map(
            (key, nestedValue) => MapEntry(key.toString(), nestedValue),
          ),
        ),
      );
    } else if (value is List) {
      for (final item in value) {
        if (item is! Map) {
          continue;
        }
        for (final key in const <String>[
          'reasoningEfforts',
          'reasoning_efforts',
          'supportedReasoningEfforts',
          'supported_reasoning_efforts',
          'efforts',
        ]) {
          final nested = item[key];
          if (nested is List) {
            rawItems.addAll(nested);
          }
        }
      }
    }
  }
  final seen = <String>{};
  final result = <String>[];
  for (final item in rawItems) {
    final normalized = _normalizeCodexReasoningEffort(
      item is Map
          ? (item['id'] ??
                item['value'] ??
                item['name'] ??
                item['effort'] ??
                item['reasoningEffort'] ??
                item['reasoning_effort'])
          : item,
    );
    if (normalized == null || !seen.add(normalized)) {
      continue;
    }
    result.add(normalized);
  }
  return result;
}

List<String> _mergeCodexReasoningEffortOptions({
  String? current,
  required List<String> options,
}) {
  final seen = <String>{};
  final result = <String>[];
  void add(String? value) {
    final normalized = _normalizeCodexReasoningEffort(value);
    if (normalized == null || !seen.add(normalized)) {
      return;
    }
    result.add(normalized);
  }

  add(current);
  for (final option in options) {
    add(option);
  }
  for (final option in const <String>[
    'low',
    'medium',
    'high',
    _kDefaultCodexReasoningEffort,
  ]) {
    add(option);
  }
  return result;
}

String? _normalizeCodexReasoningEffort(dynamic value) {
  final text = value?.toString().trim().toLowerCase() ?? '';
  if (text.isEmpty) {
    return null;
  }
  return switch (text) {
    'no' || 'none' || 'off' => 'none',
    'min' || 'minimal' || 'minimum' => 'minimal',
    'med' || 'medium' => 'medium',
    'extra_high' ||
    'extra-high' ||
    'very_high' ||
    'very-high' ||
    'x-high' ||
    'x high' ||
    'xhigh' => 'xhigh',
    'low' || 'high' => text,
    _ => text,
  };
}

String? _codexOptionId(dynamic item) {
  if (item is String) {
    final text = item.trim();
    return text.isEmpty ? null : text;
  }
  if (item is Map) {
    for (final key in const <String>[
      'id',
      'modelId',
      'model_id',
      'slug',
      'value',
      'model',
      'name',
      'displayName',
      'display_name',
      'mode',
    ]) {
      final text = item[key]?.toString().trim() ?? '';
      if (text.isNotEmpty) {
        return text;
      }
    }
    return null;
  }
  if (item is Iterable) {
    return null;
  }
  final text = item?.toString().trim() ?? '';
  return text.isEmpty ? null : text;
}

String _resolveCodexPlanMode(List<String> modes) {
  for (final mode in modes) {
    if (mode.toLowerCase() == 'plan') {
      return mode;
    }
  }
  for (final mode in modes) {
    if (_isCodexPlanMode(mode)) {
      return mode;
    }
  }
  return 'plan';
}

bool _isCodexPlanMode(String? mode) {
  final normalized = mode?.trim().toLowerCase() ?? '';
  return normalized == 'plan' || normalized.contains('plan');
}

class _CodexRunSettingsSnapshot {
  const _CodexRunSettingsSnapshot({this.modelId, this.reasoningEffort});

  final String? modelId;
  final String? reasoningEffort;
}

extension _CodexPermissionModePayload on CodexPermissionMode {
  String get approvalPolicy {
    return switch (this) {
      CodexPermissionMode.fullAccess => 'never',
      CodexPermissionMode.defaultMode ||
      CodexPermissionMode.autoReview => 'on-request',
    };
  }

  String get approvalsReviewer {
    return switch (this) {
      CodexPermissionMode.autoReview => 'guardian_subagent',
      CodexPermissionMode.defaultMode ||
      CodexPermissionMode.fullAccess => 'user',
    };
  }

  Map<String, dynamic>? get sandboxPolicy {
    return switch (this) {
      CodexPermissionMode.fullAccess => const <String, dynamic>{
        'type': 'dangerFullAccess',
      },
      CodexPermissionMode.defaultMode || CodexPermissionMode.autoReview => null,
    };
  }
}
