part of 'chat_page.dart';

const String _kAgentModelPreferenceKey = 'model';
const String _kAgentReasoningEffortPreferenceKey = 'reasoning_effort';
const String _kAgentCollaborationModePreferenceKey = 'collaboration_mode';
const String _kAgentPreferenceStoragePrefix = 'chat_agent_command_preference';
const String _kLegacyAgentPreferenceStoragePrefix =
    'chat_codex_command_preference';
const Duration _remoteCodexExternalActiveGrace = Duration(seconds: 6);
const List<String> _kAgentModelListResponseKeys = <String>[
  'models',
  'modelOptions',
  'model_options',
  'availableModels',
  'available_models',
  'modelIds',
  'model_ids',
];
const String _kAgentInitPrompt = '''
Please analyze this repository and create or update an AGENTS.md file that acts as a contributor guide for future coding agents.

Include concise, repository-specific guidance for:
- project structure and where important code lives
- build, test, lint, and development commands
- coding conventions and architectural patterns visible in the repo
- testing expectations and any important setup notes

Keep the file practical and avoid generic advice. If AGENTS.md already exists, preserve useful existing guidance and update it with what you learn from the current repository.
''';

mixin _ChatPageAgentMixin on _ChatPageStateBase {
  @override
  Future<void> _refreshAgentRuntimeStatus() async {
    if (!mounted || _isAgentRuntimeStatusLoading) return;
    setState(() {
      _isAgentRuntimeStatusLoading = true;
    });
    try {
      final status = await AgentRuntimeService.status();
      if (!mounted) return;
      setState(() {
        _agentRuntimeStatus = status;
        _isAgentRuntimeStatusLoading = false;
      });
      unawaited(_loadAgentCatalog());
      if (_activeMode == ChatPageMode.agent) {
        unawaited(_loadAgentModelOptionsWhenReady());
      }
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _agentRuntimeStatus = AgentRuntimeStatus.disconnected;
        _isAgentRuntimeStatusLoading = false;
      });
    }
  }

  @override
  Future<void> _handleAgentTap() async {
    if (_isAgentRuntimeStatusLoading) return;
    if (_activeMode == ChatPageMode.agent) {
      await _leaveAgentMode();
      return;
    }
    setState(() {
      _isAgentRuntimeStatusLoading = true;
    });
    AgentRuntimeStatus status;
    try {
      status = await AgentRuntimeService.status();
      if (status.ready && !status.connected) {
        status = await AgentRuntimeService.connect();
        unawaited(AgentRuntimeService.listThreads());
      }
    } catch (error) {
      status = AgentRuntimeStatus(
        connected: false,
        ready: false,
        error: error.toString(),
      );
    }
    if (!mounted) return;
    setState(() {
      _agentRuntimeStatus = status;
      _isAgentRuntimeStatusLoading = false;
    });
    if (!status.ready) {
      if (status.remoteEnabled) {
        _showSnackBar(
          LegacyTextLocalizer.isEnglish
              ? 'Remote Agent Bridge is unavailable'
              : '远程 Agent Bridge 不可用',
        );
        GoRouterManager.push('/home/remote_codex_setting');
        return;
      }
      _showSnackBar(
        LegacyTextLocalizer.isEnglish
            ? 'The selected ACP Agent is unavailable'
            : '所选 ACP Agent 当前不可用',
      );
      GoRouterManager.push('/home/agent_mode_setting');
      return;
    }

    await _showAgentAccountStatus();

    final target = _newAgentThreadTarget(
      agentId: _activeAcpAgentId,
      agentRuntime: status.runtime == 'remote' || status.remoteEnabled
          ? 'remote'
          : 'local',
    );
    if (!mounted) return;
    await _applyConversationThreadTarget(target);
  }

  @override
  Future<void> _handleAcpAgentModeShortcutTap(String agentId) async {
    final normalized = agentId.trim();
    if (normalized.isEmpty || _isAcpAgentSwitching) {
      return;
    }
    final selectsRemote = normalized == _kRemoteCodexModeAgentId;
    if (_activeMode == ChatPageMode.agent && normalized == _activeAcpAgentId) {
      return;
    }
    final previousTarget = _threadTargetForMode;
    final target = _newAgentThreadTarget(
      agentId: normalized,
      agentRuntime: selectsRemote ? 'remote' : 'local',
    );
    setState(() {
      _optimisticAcpAgentId = normalized;
      _isAcpAgentSwitching = true;
    });
    // Enter the selected Agent's blank conversation immediately. Adapter
    // installation, process launch, and connection continue in parallel.
    final navigationFuture = _applyConversationThreadTarget(target);
    var selected = false;
    try {
      selected = selectsRemote
          ? await _selectRemoteCodexRuntime()
          : await _selectAgent(normalized);
      await navigationFuture;
    } finally {
      if (mounted) {
        setState(() {
          _optimisticAcpAgentId = null;
          _isAcpAgentSwitching = false;
        });
      }
    }
    if (!selected) {
      if (mounted) {
        await _applyConversationThreadTarget(previousTarget);
      }
      return;
    }
  }

  Future<void> _leaveAgentMode() async {
    _storeDraftForActiveConversationMode();
    await _persistVisibleThreadTargetIfNeeded();
    if (!mounted) return;

    final target = _resolveAgentExitTarget();
    if (!mounted) return;
    await _applyConversationThreadTarget(target);
  }

  ConversationThreadTarget _resolveAgentExitTarget() {
    return _newThreadTargetForConversationMode(ConversationMode.normal);
  }

  @override
  String? _remoteCodexWorkspaceNameForGreeting() {
    if (!_agentRuntimeStatus.remoteEnabled) {
      return null;
    }
    return _remoteCodexLastPathSegment(
      _agentRuntimeStatus.remoteCwd ?? _agentRuntimeStatus.cwd ?? '',
    );
  }

  @override
  Future<void> _openRemoteCodexWorkspacePicker() async {
    if (!_agentRuntimeStatus.remoteEnabled) {
      return;
    }
    CodexRemoteBridgeConfig config;
    try {
      config = await AgentRuntimeService.readRemoteBridgeConfig();
    } catch (error) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Failed to read Agent config: $error'
            : '读取 Agent 配置失败：$error',
        type: ToastType.error,
      );
      return;
    }
    if (!mounted) return;
    if (!config.remoteEnabled || config.remoteBridgeUrl.trim().isEmpty) {
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Remote Agent Bridge is not configured'
            : '远程 Agent Bridge 尚未配置',
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
      await AgentRuntimeService.writeRemoteBridgeConfig(
        remoteEnabled: true,
        remoteBridgeUrl: config.remoteBridgeUrl,
        remoteBridgeToken: config.remoteBridgeToken,
        remoteCwd: nextCwd,
      );
      final status = await AgentRuntimeService.status();
      if (!mounted) return;
      setState(() {
        _agentRuntimeStatus = status;
        _activeAgentThreadId = null;
        _activeAgentTurnId = null;
      });
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Switched Agent workspace to ${_remoteCodexLastPathSegment(nextCwd) ?? nextCwd}'
            : '已切换到 ${_remoteCodexLastPathSegment(nextCwd) ?? nextCwd}',
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
    final threadId = target.agentSessionId?.trim() ?? '';
    if (threadId.isEmpty) {
      return;
    }
    final runtimeId = _remoteCodexRuntimeId(threadId);
    _activeRemoteCodexRuntimeId = runtimeId;
    _activeAgentThreadId = threadId;
    _activeAgentTurnId = null;
    _currentConversationIdByMode[ChatPageMode.agent] = runtimeId;

    try {
      AgentRuntimeStatus status = _agentRuntimeStatus;
      if (!status.connected) {
        status = await AgentRuntimeService.connect();
      }
      final response = await AgentRuntimeService.resumeThread(
        threadId: threadId,
      );
      if (!mounted) return;
      final resolvedThreadId =
          _asAgentString(response['threadId']) ??
          _asAgentString(_asAgentMap(response['thread'])?['id']) ??
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
        assumeActive: target.agentSessionActive == true,
      );
      _startRemoteCodexSessionSync(resolvedThreadId);
      _rememberRuntimeUiSnapshot(ChatPageMode.agent);
    } catch (error) {
      if (!mounted) return;
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Failed to load Agent session: $error'
            : '加载 Agent session 失败：$error',
        type: ToastType.error,
      );
    }
  }

  @override
  Future<void> _refreshAgentCommandPreferences() async {
    final conversationId = _currentConversationIdByMode[ChatPageMode.agent];
    final model = _readAgentPreference(
      _kAgentModelPreferenceKey,
      conversationId: conversationId,
    );
    final effort = _readAgentPreference(
      _kAgentReasoningEffortPreferenceKey,
      conversationId: conversationId,
    );
    final collaborationMode = _readAgentPreference(
      _kAgentCollaborationModePreferenceKey,
      conversationId: conversationId,
    );
    if (!mounted) return;
    setState(() {
      _activeAgentModelId = model;
      _activeAgentReasoningEffort = _normalizeAgentReasoningEffort(effort);
      _activeAgentCollaborationMode = collaborationMode;
    });
    if (model == null || effort == null || _agentModelOptions.isEmpty) {
      unawaited(_loadAgentModelOptionsWhenReady());
    }
  }

  @override
  Future<void> _loadAgentModelOptionsWhenReady({bool force = false}) async {
    final currentSourceKey = agentModelSourceKey(_agentRuntimeStatus);
    final hasResolvedEffort =
        _agentReasoningEffortOptions.isEmpty ||
        (_activeAgentReasoningEffort ?? '').trim().isNotEmpty;
    if (!force &&
        _agentRuntimeStatus.connected &&
        _loadedAgentModelSourceKey == currentSourceKey &&
        _agentModelOptions.isNotEmpty &&
        (_activeAgentModelId ?? '').trim().isNotEmpty &&
        hasResolvedEffort) {
      return;
    }
    late AgentRuntimeStatus status;
    try {
      status = await AgentRuntimeService.status();
      if (!status.ready) {
        return;
      }
      if (!status.connected) {
        status = await AgentRuntimeService.connect();
        unawaited(AgentRuntimeService.listThreads());
      }
      _applyRefreshedAgentRuntimeStatus(status);
    } catch (error) {
      return;
    }
    if (!mounted || !status.connected) {
      return;
    }
    if (status.runtime != 'remote' && !status.remoteEnabled) {
      await _loadAgentCatalog();
    }
    final sourceKey = agentModelSourceKey(status);
    if ((!force &&
            _loadedAgentModelSourceKey == sourceKey &&
            _agentModelOptions.isNotEmpty &&
            (_activeAgentModelId ?? '').trim().isNotEmpty &&
            (_agentReasoningEffortOptions.isEmpty ||
                (_activeAgentReasoningEffort ?? '').trim().isNotEmpty)) ||
        (_isAgentModelListLoading &&
            _loadingAgentModelSourceKey == sourceKey)) {
      return;
    }
    await _loadAgentModelOptions(force: true);
  }

  @override
  Future<void> _loadAgentCatalog({bool force = false}) async {
    if (_isAgentCatalogLoading ||
        (!force && _agentCatalog?.agents.isNotEmpty == true)) {
      return;
    }
    if (!mounted) return;
    setState(() {
      _isAgentCatalogLoading = true;
    });
    try {
      final catalog = await AgentRuntimeService.listAgents();
      if (!mounted) return;
      setState(() {
        _agentCatalog = catalog;
      });
    } catch (error) {
    } finally {
      if (mounted) {
        setState(() {
          _isAgentCatalogLoading = false;
        });
      }
    }
  }

  @override
  Future<void> _loadAgentModelOptions({bool force = false}) async {
    final statusForRequest = _agentRuntimeStatus;
    final sourceKey = agentModelSourceKey(statusForRequest);
    if (_isAgentModelListLoading && _loadingAgentModelSourceKey == sourceKey) {
      return;
    }
    if (!force &&
        _loadedAgentModelSourceKey == sourceKey &&
        _agentModelOptions.isNotEmpty &&
        (_activeAgentModelId ?? '').trim().isNotEmpty) {
      return;
    }
    if (!mounted) return;
    final requestId = ++_agentModelListRequestId;
    setState(() {
      _isAgentModelListLoading = true;
      _loadingAgentModelSourceKey = sourceKey;
      _agentModelListError = null;
    });
    try {
      final configSettings = await _readAgentRunSettingsFromServerConfig();
      final response = await AgentRuntimeService.listModelsForStatus(
        statusForRequest,
      );
      final models = extractAcpModelIds(response);
      final reportedPreferredModel =
          configSettings.modelId ??
          _extractAgentPreferredOptionId(response) ??
          _extractAgentDefaultModelId(response) ??
          (models.isNotEmpty ? models.first : null);
      final preferredModel =
          models.isNotEmpty &&
              (reportedPreferredModel == null ||
                  !models.contains(reportedPreferredModel))
          ? models.first
          : reportedPreferredModel;
      final conversationId = _currentConversationIdByMode[ChatPageMode.agent];
      final scopedModel = _readAgentPreference(
        _kAgentModelPreferenceKey,
        conversationId: conversationId,
      );
      final activeModel =
          (_loadedAgentModelSourceKey == sourceKey
                  ? _activeAgentModelId
                  : scopedModel)
              ?.trim() ??
          '';
      final effectiveModel =
          activeModel.isNotEmpty &&
              (models.isEmpty || models.contains(activeModel))
          ? activeModel
          : preferredModel;
      final modelOptions = _mergeAgentOptionIds(
        current: effectiveModel,
        preferred: preferredModel,
        options: models,
      );
      final modelDefaultEffort = _extractAgentModelDefaultReasoningEffort(
        response,
        effectiveModel,
      );
      final serverEffort = configSettings.reasoningEffort ?? modelDefaultEffort;
      final effortOptions = _mergeAgentReasoningEffortOptions(
        current: serverEffort,
        options: extractAcpReasoningEffortIds(response),
      );
      if (!mounted ||
          !isCurrentAgentModelLoad(
            requestId: requestId,
            activeRequestId: _agentModelListRequestId,
            requestSource: sourceKey,
            currentSource: agentModelSourceKey(_agentRuntimeStatus),
          )) {
        return;
      }
      setState(() {
        _loadedAgentModelSourceKey = sourceKey;
        _agentModelOptions = modelOptions;
        _activeAgentModelId = effectiveModel;
        final selectedEffort = _normalizeAgentReasoningEffort(
          _activeAgentReasoningEffort,
        );
        final normalizedServerEffort = _normalizeAgentReasoningEffort(
          serverEffort,
        );
        _activeAgentReasoningEffort =
            selectedEffort != null && effortOptions.contains(selectedEffort)
            ? selectedEffort
            : normalizedServerEffort != null &&
                  effortOptions.contains(normalizedServerEffort)
            ? normalizedServerEffort
            : effortOptions.firstOrNull;
        _agentReasoningEffortOptions = effortOptions;
        _agentModelListError = null;
      });
    } catch (error) {
      if (!mounted ||
          !isCurrentAgentModelLoad(
            requestId: requestId,
            activeRequestId: _agentModelListRequestId,
            requestSource: sourceKey,
            currentSource: agentModelSourceKey(_agentRuntimeStatus),
          )) {
        return;
      }
      setState(() {
        _agentModelListError = error.toString();
      });
    } finally {
      if (mounted && requestId == _agentModelListRequestId) {
        setState(() {
          _isAgentModelListLoading = false;
          _loadingAgentModelSourceKey = null;
        });
      }
    }
  }

  Future<_AgentRunSettingsSnapshot>
  _readAgentRunSettingsFromServerConfig() async {
    try {
      final response = await AgentRuntimeService.readConfig();
      return _AgentRunSettingsSnapshot(
        modelId: _extractAgentConfigModelId(response),
        reasoningEffort: _extractAgentConfigReasoningEffort(response),
      );
    } catch (error) {
      return const _AgentRunSettingsSnapshot();
    }
  }

  @override
  Future<void> _loadAgentCollaborationModes({bool force = false}) async {
    if (_isAgentCollaborationModeListLoading) {
      return;
    }
    if (!force && _agentCollaborationModes.isNotEmpty) {
      return;
    }
    if (!mounted) return;
    setState(() {
      _isAgentCollaborationModeListLoading = true;
      _agentCollaborationModeListError = null;
    });
    try {
      final response = await AgentRuntimeService.listCollaborationModes();
      final modes = _extractAgentOptionIds(response, const <String>[
        'collaborationModes',
        'modes',
        'items',
        'data',
      ]);
      if (!mounted) return;
      setState(() {
        _agentCollaborationModes = modes;
        _isAgentCollaborationModeListLoading = false;
        _agentCollaborationModeListError = null;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _isAgentCollaborationModeListLoading = false;
        _agentCollaborationModeListError = error.toString();
      });
    }
  }

  @override
  Future<void> _selectAgentModel(
    String modelId, {
    bool clearComposer = true,
  }) async {
    final normalized = modelId.trim();
    if (normalized.isEmpty || normalized.startsWith('/')) {
      return;
    }
    if (!mounted) return;
    setState(() {
      _activeAgentModelId = normalized;
    });
    await _writeAgentPreference(_kAgentModelPreferenceKey, normalized);
    if (clearComposer) {
      _messageController.clear();
      _hideSlashCommandPanel();
    }
  }

  @override
  Future<bool> _selectAgent(String agentId) async {
    final normalized = agentId.trim();
    if (normalized.isEmpty) {
      return false;
    }
    if (normalized == _kRemoteCodexModeAgentId) {
      return _selectRemoteCodexRuntime();
    }
    final wasRemote =
        _agentRuntimeStatus.runtime == 'remote' ||
        _agentRuntimeStatus.remoteEnabled;
    if (!wasRemote &&
        normalized ==
            (_agentRuntimeStatus.activeAgentId ??
                _agentCatalog?.selectedAgentId)) {
      return true;
    }
    try {
      if (wasRemote) {
        final remote = await AgentRuntimeService.readRemoteBridgeConfig();
        await AgentRuntimeService.writeRemoteBridgeConfig(
          remoteEnabled: false,
          remoteBridgeUrl: remote.remoteBridgeUrl,
          remoteBridgeToken: remote.remoteBridgeToken,
          remoteCwd: remote.remoteCwd,
        );
      }
      final catalog = await AgentRuntimeService.selectAgent(normalized);
      var status = await AgentRuntimeService.status();
      if (status.ready && !status.connected) {
        status = await AgentRuntimeService.connect();
      }
      if (!mounted) return false;
      setState(() {
        _agentCatalog = catalog;
        _agentRuntimeStatus = status;
        _activeAgentThreadId = null;
        _activeAgentTurnId = null;
        _activeAgentModelId = null;
        _agentModelOptions = const <String>[];
        _loadedAgentModelSourceKey = null;
        _loadingAgentModelSourceKey = null;
        _agentModelListError = null;
        _agentModelListRequestId++;
      });
      unawaited(_loadAgentModelOptions(force: true));
      return true;
    } catch (error) {
      if (!mounted) return false;
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Failed to switch ACP agent: $error'
            : '切换 ACP Agent 失败：$error',
        type: ToastType.error,
      );
      return false;
    }
  }

  Future<bool> _selectRemoteCodexRuntime() async {
    final isRemote =
        _agentRuntimeStatus.runtime == 'remote' ||
        _agentRuntimeStatus.remoteEnabled;
    if (isRemote) {
      return true;
    }
    try {
      final remote = await AgentRuntimeService.readRemoteBridgeConfig();
      if (!remote.remoteConfigured) {
        if (mounted) {
          _showSnackBar(
            LegacyTextLocalizer.isEnglish
                ? 'Remote Agent Bridge is not configured'
                : '远程 Agent Bridge 尚未配置',
          );
          GoRouterManager.push('/home/remote_codex_setting');
        }
        return false;
      }
      await AgentRuntimeService.writeRemoteBridgeConfig(
        remoteEnabled: true,
        remoteBridgeUrl: remote.remoteBridgeUrl,
        remoteBridgeToken: remote.remoteBridgeToken,
        remoteCwd: remote.remoteCwd,
      );
      var status = await AgentRuntimeService.status();
      if (status.ready && !status.connected) {
        status = await AgentRuntimeService.connect();
      }
      if (!mounted) return false;
      setState(() {
        _agentRuntimeStatus = status;
        _activeAgentThreadId = null;
        _activeAgentTurnId = null;
        _activeAgentModelId = null;
        _activeAgentReasoningEffort = null;
        _activeAgentCollaborationMode = null;
        _agentModelOptions = const <String>[];
        _agentReasoningEffortOptions = const <String>[];
        _agentCollaborationModes = const <String>[];
        _agentModelListError = null;
        _agentCollaborationModeListError = null;
        _loadedAgentModelSourceKey = null;
        _loadingAgentModelSourceKey = null;
        _agentModelListRequestId++;
      });
      unawaited(_loadAgentModelOptions(force: true));
      return true;
    } catch (error) {
      if (!mounted) return false;
      showToast(
        LegacyTextLocalizer.isEnglish
            ? 'Failed to switch to Remote Agent: $error'
            : '切换到远程 Agent 失败：$error',
        type: ToastType.error,
      );
      return false;
    }
  }

  @override
  Future<void> _selectAgentReasoningEffort(String effort) async {
    final normalized = _normalizeAgentReasoningEffort(effort);
    if (normalized == null ||
        !_agentReasoningEffortOptions.contains(normalized)) {
      return;
    }
    if (!mounted) return;
    setState(() {
      _activeAgentReasoningEffort = normalized;
      _agentReasoningEffortOptions = _mergeAgentReasoningEffortOptions(
        current: normalized,
        options: _agentReasoningEffortOptions,
      );
    });
    await _writeAgentPreference(
      _kAgentReasoningEffortPreferenceKey,
      normalized,
    );
  }

  @override
  Future<void> _activateAgentPlanMode({
    bool persistOnly = false,
    bool dismissPanel = true,
  }) async {
    await _loadAgentCollaborationModes();
    final planMode = _resolveAgentPlanMode(_agentCollaborationModes);
    if (!mounted) return;
    setState(() {
      _activeAgentCollaborationMode = planMode;
    });
    await _writeAgentPreference(
      _kAgentCollaborationModePreferenceKey,
      planMode,
    );
    if (!persistOnly && dismissPanel) {
      _messageController.clear();
      _hideSlashCommandPanel();
    }
  }

  @override
  Future<void> _deactivateAgentPlanMode({bool dismissPanel = true}) async {
    if (!mounted) return;
    setState(() {
      _activeAgentCollaborationMode = null;
    });
    await _clearAgentPreference(_kAgentCollaborationModePreferenceKey);
    if (dismissPanel) {
      _messageController.clear();
      _hideSlashCommandPanel();
    }
  }

  Future<void> _toggleAgentPlanMode({bool dismissPanel = true}) {
    return _isAgentPlanMode(_activeAgentCollaborationMode)
        ? _deactivateAgentPlanMode(dismissPanel: dismissPanel)
        : _activateAgentPlanMode(dismissPanel: dismissPanel);
  }

  void _syncAgentCollaborationModeFromServer(String? mode) {
    final normalized = mode?.trim();
    if (normalized == null || normalized.isEmpty) {
      return;
    }
    if (_isAgentPlanMode(normalized)) {
      if (_activeAgentCollaborationMode == normalized) {
        return;
      }
      _activeAgentCollaborationMode = normalized;
      unawaited(
        _writeAgentPreference(
          _kAgentCollaborationModePreferenceKey,
          normalized,
        ),
      );
      return;
    }
    if (_activeAgentCollaborationMode == null) {
      return;
    }
    _activeAgentCollaborationMode = null;
    unawaited(_clearAgentPreference(_kAgentCollaborationModePreferenceKey));
  }

  void _autoDeactivateAgentPlanModeAfterTurn() {
    if (!_isAgentPlanMode(_activeAgentCollaborationMode)) {
      return;
    }
    _activeAgentCollaborationMode = null;
    unawaited(_clearAgentPreference(_kAgentCollaborationModePreferenceKey));
  }

  @override
  Future<void> _handleAgentSlashCommandCardSelected(
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
      await _loadAgentModelOptionsWhenReady();
      return;
    }
    if (command == '/review') {
      await _startAgentReviewCommand();
      return;
    }
    if (command == '/init') {
      await _executeAgentInitCommand();
      return;
    }
    if (command == '/plan') {
      await _toggleAgentPlanMode(dismissPanel: false);
      return;
    }
    if (_resolveSlashCommandPanelRoute(_messageController.text) ==
        _SlashCommandPanelRoute.agentModel) {
      await _selectAgentModel(command);
    }
  }

  @override
  Future<bool> _tryHandleAgentSlashCommand(
    String messageText, {
    List<Map<String, dynamic>> attachments = const [],
  }) async {
    final trimmed = messageText.trim();
    final intent = resolveAgentSlashSubmitIntent(trimmed);
    switch (intent.kind) {
      case AgentSlashSubmitKind.none:
        return false;
      case AgentSlashSubmitKind.openModelPicker:
        _triggerSlashCommandPanel();
        await _loadAgentModelOptionsWhenReady();
        return true;
      case AgentSlashSubmitKind.selectModel:
        await _selectAgentModel(intent.value ?? '');
        return true;
      case AgentSlashSubmitKind.startReview:
        _messageController.clear();
        _hideSlashCommandPanel();
        await _startAgentReviewCommand();
        return true;
      case AgentSlashSubmitKind.startInit:
        _messageController.clear();
        _hideSlashCommandPanel();
        await _executeAgentInitCommand();
        return true;
      case AgentSlashSubmitKind.togglePlan:
        await _toggleAgentPlanMode();
        return true;
      case AgentSlashSubmitKind.startPlan:
        _messageController.clear();
        _hideSlashCommandPanel();
        await _activateAgentPlanMode(persistOnly: true);
        await _startAgentTurnCommand(
          displayText: trimmed,
          actualText: intent.value ?? '',
          attachments: attachments,
          collaborationModeOverride:
              _activeAgentCollaborationMode ?? _resolveAgentPlanMode(const []),
        );
        return true;
      case AgentSlashSubmitKind.unsupported:
        _messageController.clear();
        _hideSlashCommandPanel();
        _showSnackBar(
          LegacyTextLocalizer.isEnglish
              ? 'Unsupported Agent command'
              : '不支持的 Agent 命令',
        );
        return true;
    }
  }

  @override
  Future<void> _executeAgentInitCommand() async {
    await _startAgentTurnCommand(
      displayText: '/init',
      actualText: _kAgentInitPrompt,
    );
  }

  @override
  Future<void> _startAgentReviewCommand() async {
    if (_isAiResponding) {
      return;
    }
    _inputFocusNode.unfocus();
    _messageController.clear();
    _hideSlashCommandPanel();
    late AgentRuntimeStatus status;
    try {
      status = await _refreshConnectedAgentRuntimeStatus();
    } catch (error) {
      if (mounted) {
        handleAgentError('Agent review 启动失败: $error');
      }
      return;
    }
    final messageIds = addUserMessage('/review');
    final remoteCodex = agentModelSourceKey(status) == 'remote';
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
        mode: ConversationMode.agent,
      );
    }

    try {
      final reviewModel = await _resolveAgentRequestModel(status);
      final response = await AgentRuntimeService.startReview(
        conversationId: remoteCodex ? null : resolvedConversationId,
        threadId: _activeAgentThreadId,
        approvalPolicy: _agentPermissionMode.approvalPolicy,
        approvalsReviewer: _agentPermissionMode.approvalsReviewer,
        sandboxPolicy: _agentPermissionMode.sandboxPolicy,
        model: reviewModel,
        effort: _activeAgentReasoningEffort,
        collaborationMode: _activeAgentCollaborationMode,
      );
      final resolvedThreadId = _asAgentString(response['threadId']);
      if (resolvedThreadId != null && remoteCodex) {
        _activateRemoteCodexRuntimeForThread(resolvedThreadId);
        _startRemoteCodexSessionSync(resolvedThreadId);
      }
      _activeAgentThreadId = resolvedThreadId ?? _activeAgentThreadId;
      _activeAgentTurnId =
          _asAgentString(response['turnId']) ?? _activeAgentTurnId;
      if (!remoteCodex) {
        await _persistVisibleThreadTargetIfNeeded();
      }
      await _writeAgentCommandPreferencesForCurrentConversation();
    } catch (error) {
      if (!mounted) return;
      handleAgentError('Agent review 启动失败: $error');
    }
  }

  Future<void> _startAgentTurnCommand({
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
    await _sendAgentMessage(
      messageIds.aiMessageId,
      actualText,
      attachments: attachments,
      collaborationModeOverride: collaborationModeOverride,
    );
  }

  String? _readAgentPreference(String kind, {int? conversationId}) {
    try {
      if (conversationId != null) {
        final scoped =
            StorageService.getString(
              _agentPreferenceKey(kind, conversationId: conversationId),
              defaultValue: '',
            ) ??
            StorageService.getString(
              _legacyAgentPreferenceKey(kind, conversationId: conversationId),
              defaultValue: '',
            );
        final normalizedScoped = scoped?.trim() ?? '';
        if (normalizedScoped.isNotEmpty) {
          return normalizedScoped;
        }
      }
      final global =
          StorageService.getString(
            _agentPreferenceKey(kind),
            defaultValue: '',
          ) ??
          StorageService.getString(
            _legacyAgentPreferenceKey(kind),
            defaultValue: '',
          );
      final normalizedGlobal = global?.trim() ?? '';
      return normalizedGlobal.isEmpty ? null : normalizedGlobal;
    } catch (error) {
      return null;
    }
  }

  Future<void> _writeAgentPreference(String kind, String value) async {
    final normalized = value.trim();
    if (normalized.isEmpty) {
      return;
    }
    await StorageService.setString(_agentPreferenceKey(kind), normalized);
    final conversationId = _currentConversationIdByMode[ChatPageMode.agent];
    if (conversationId != null) {
      await StorageService.setString(
        _agentPreferenceKey(kind, conversationId: conversationId),
        normalized,
      );
    }
  }

  Future<void> _clearAgentPreference(String kind) async {
    await StorageService.remove(_agentPreferenceKey(kind));
    final conversationId = _currentConversationIdByMode[ChatPageMode.agent];
    if (conversationId != null) {
      await StorageService.remove(
        _agentPreferenceKey(kind, conversationId: conversationId),
      );
    }
  }

  Future<void> _writeAgentCommandPreferencesForCurrentConversation() async {
    final modelId = _activeAgentModelId?.trim();
    if (modelId != null && modelId.isNotEmpty) {
      await _writeAgentPreference(_kAgentModelPreferenceKey, modelId);
    }
    final effort = _activeAgentReasoningEffort?.trim();
    if (effort != null && effort.isNotEmpty) {
      await _writeAgentPreference(_kAgentReasoningEffortPreferenceKey, effort);
    }
    final collaborationMode = _activeAgentCollaborationMode?.trim();
    if (collaborationMode != null && collaborationMode.isNotEmpty) {
      await _writeAgentPreference(
        _kAgentCollaborationModePreferenceKey,
        collaborationMode,
      );
    }
  }

  String _agentPreferenceKey(String kind, {int? conversationId}) {
    final source = kind == _kAgentModelPreferenceKey
        ? '.${agentModelSourceKey(_agentRuntimeStatus)}'
        : '';
    if (conversationId == null) {
      return '$_kAgentPreferenceStoragePrefix.$kind$source.global';
    }
    return '$_kAgentPreferenceStoragePrefix.$kind$source.conversation.$conversationId';
  }

  String _legacyAgentPreferenceKey(String kind, {int? conversationId}) {
    final source = kind == _kAgentModelPreferenceKey
        ? '.${agentModelSourceKey(_agentRuntimeStatus)}'
        : '';
    if (conversationId == null) {
      return '$_kLegacyAgentPreferenceStoragePrefix.$kind$source.global';
    }
    return '$_kLegacyAgentPreferenceStoragePrefix.$kind$source.conversation.$conversationId';
  }

  @override
  void _handleAgentRuntimeEvent(Map<String, dynamic> event) {
    final diagnosticMethod = _diagnosticEventMethod(event);
    _agentEventDiagnosticCounter.update(
      diagnosticMethod,
      (count) => count + 1,
      ifAbsent: () => 1,
    );
    // Log every event individually so the user can `adb logcat -s flutter:V`
    // (or `flutter logs`) during a Agent turn and verify exactly which
    // app-server methods are reaching the Flutter side. If lines like
    //   [Agent/E] item/started:commandExecution
    //   [Agent/E] item/completed:commandExecution
    // do not show up while pwd/ls/cat run, the events are being dropped
    // upstream (codex app-server -> codex-bridge -> Kotlin -> EventChannel).
    if (diagnosticMethod == 'acp/configOptions/updated') {
      unawaited(_loadAgentModelOptions(force: true));
    }
    final remoteCodex = _isRemoteCodexConfigured();
    final eventThreadId = _remoteCodexEventThreadId(event);
    final explicitConversationId = _asAgentInt(event['conversationId']);
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
        _currentConversationIdByMode[ChatPageMode.agent];
    if (conversationId == null) {
      return;
    }
    if (remoteCodex && eventThreadId != null && !shouldPromoteRemoteEvent) {
      _ensureRemoteCodexRuntimeForThread(eventThreadId);
    }
    final isVisibleConversation =
        conversationId == _currentConversationIdByMode[ChatPageMode.agent];
    final result = _runtimeCoordinator.applyAgentEvent(
      conversationId: conversationId,
      event: event,
      conversation: isVisibleConversation
          ? _currentConversationByMode[ChatPageMode.agent]
          : null,
    );
    final threadId = _asAgentString(event['threadId']) ?? result.threadId;
    final turnId = _asAgentString(event['turnId']) ?? result.turnId;
    if (isVisibleConversation && (threadId != null || turnId != null)) {
      _activeAgentThreadId = threadId ?? _activeAgentThreadId;
      _activeAgentTurnId = turnId ?? _activeAgentTurnId;
    }
    if (isVisibleConversation) {
      _syncAgentCollaborationModeFromServer(result.collaborationMode);
    }
    if (isVisibleConversation && result.method == 'turn/completed') {
      final completedTurnId = result.turnId;
      final completedPlanTurn =
          completedTurnId != null && _agentPlanTurnIds.remove(completedTurnId);
      if (completedPlanTurn ||
          (completedTurnId == null &&
              _isAgentPlanMode(_activeAgentCollaborationMode))) {
        _autoDeactivateAgentPlanModeAfterTurn();
      }
      _activeAgentTurnId = null;
    }
    if (isVisibleConversation) {
      final runtime = _runtimeCoordinator.runtimeFor(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );
      if (runtime != null) {
        _syncAgentModeStateFromRuntime(runtime);
        if (!runtime.isAiResponding) {
          _activeAgentTurnId = null;
        }
      }
    }
    if (_activeMode == ChatPageMode.agent && mounted && isVisibleConversation) {
      setState(() {});
    }
  }

  @override
  Future<void> _sendAgentMessage(
    String aiMessageId,
    String messageText, {
    List<Map<String, dynamic>> attachments = const [],
    String? modelOverride,
    String? collaborationModeOverride,
  }) async {
    // Prime the active turn before status probing, ACP connection, adapter
    // preparation, or conversation persistence. The chat list can therefore
    // show the selected Agent and an elapsed processing state immediately,
    // without waiting for the first ACP API event.
    if (mounted) {
      setState(() {
        _currentDispatchTaskId = aiMessageId;
        final runtime = _activeRuntime;
        if (runtime != null) {
          runtime.lastAgentTaskId = aiMessageId;
        }
      });
    }
    late AgentRuntimeStatus status;
    try {
      status = await _refreshConnectedAgentRuntimeStatus();
    } catch (error) {
      if (mounted) {
        _currentDispatchTaskId = aiMessageId;
        handleAgentError('Agent 连接失败: $error');
      }
      return;
    }
    final remoteCodex = agentModelSourceKey(status) == 'remote';
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
        mode: ConversationMode.agent,
      );
    }

    final collaborationModeForTurn =
        collaborationModeOverride ?? _activeAgentCollaborationMode;
    final turnUsesPlanMode = _isAgentPlanMode(collaborationModeForTurn);
    try {
      final turnModel = await _resolveAgentRequestModel(
        status,
        overrideModel: modelOverride,
      );
      final response = await AgentRuntimeService.startTurn(
        conversationId: remoteCodex ? null : resolvedConversationId,
        threadId: _activeAgentThreadId,
        text: messageText,
        attachments: attachments,
        approvalPolicy: _agentPermissionMode.approvalPolicy,
        approvalsReviewer: _agentPermissionMode.approvalsReviewer,
        sandboxPolicy: _agentPermissionMode.sandboxPolicy,
        model: turnModel,
        effort: _activeAgentReasoningEffort,
        collaborationMode: collaborationModeForTurn,
      );
      final resolvedThreadId = _asAgentString(response['threadId']);
      if (resolvedThreadId != null && remoteCodex) {
        _activateRemoteCodexRuntimeForThread(resolvedThreadId);
        _startRemoteCodexSessionSync(resolvedThreadId);
      }
      _activeAgentThreadId = resolvedThreadId ?? _activeAgentThreadId;
      _activeAgentTurnId =
          _asAgentString(response['turnId']) ?? _activeAgentTurnId;
      if (turnUsesPlanMode && _activeAgentTurnId != null) {
        _agentPlanTurnIds.add(_activeAgentTurnId!);
      }
      final localConversationId = _asAgentInt(response['conversationId']);
      if (!remoteCodex &&
          localConversationId != null &&
          localConversationId !=
              _currentConversationIdByMode[ChatPageMode.agent]) {
        if (_currentConversationIdByMode[ChatPageMode.agent] == null) {
          _currentConversationIdByMode[ChatPageMode.agent] =
              localConversationId;
          await _prepareConversationModeState(
            ChatPageMode.agent,
            ConversationThreadTarget.existing(
              conversationId: localConversationId,
              mode: ConversationMode.agent,
            ),
          );
        }
      }
      if (!remoteCodex) {
        await _persistVisibleThreadTargetIfNeeded();
      }
      await _writeAgentCommandPreferencesForCurrentConversation();
    } catch (error) {
      if (!mounted) return;
      handleAgentError('$_activeAcpAgentDisplayName 启动失败: $error');
    }
  }

  @override
  Future<void> _interruptAgentTurn() async {
    final conversationId = _currentConversationIdByMode[ChatPageMode.agent];
    if (conversationId == null && _activeAgentThreadId == null) {
      return;
    }
    try {
      await AgentRuntimeService.interruptTurn(
        conversationId: _isRemoteCodexConfigured() ? null : conversationId,
        threadId: _activeAgentThreadId,
        turnId: _activeAgentTurnId,
      );
    } catch (error) {
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
    required _AgentThreadActivityState activity,
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

    final contentSignature = _remoteCodexThreadContentSignature(response);
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

    final looksExternallyActive = _remoteCodexLatestTurnLooksExternallyActive(
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
        _activeConversationMode != ChatPageMode.agent ||
        !_isRemoteCodexConfigured() ||
        _activeAgentThreadId?.trim() != threadId) {
      return;
    }
    _remoteCodexSessionSyncInFlight = true;
    try {
      final response = await _readRemoteCodexThreadSnapshot(threadId);
      if (!mounted ||
          _remoteCodexSessionSyncThreadId != threadId ||
          _activeAgentThreadId?.trim() != threadId) {
        return;
      }
      _applyRemoteCodexThreadSnapshot(
        response: response,
        fallbackThreadId: threadId,
        fromPoll: true,
      );
    } catch (error) {
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
      return await AgentRuntimeService.readThread(threadId: threadId);
    } catch (error) {
      return AgentRuntimeService.resumeThread(threadId: threadId);
    }
  }

  void _applyRemoteCodexThreadSnapshot({
    required Map<String, dynamic> response,
    required String fallbackThreadId,
    int? fallbackRuntimeId,
    List<ChatMessageModel>? fallbackMessages,
    ConversationModel? fallbackConversation,
    AgentRuntimeStatus? status,
    bool fromPoll = false,
    bool assumeActive = false,
  }) {
    final resolvedThreadId =
        _asAgentString(response['threadId']) ??
        _asAgentString(_asAgentMap(response['thread'])?['id']) ??
        fallbackThreadId;
    if (resolvedThreadId.isEmpty) {
      return;
    }
    final runtimeId =
        fallbackRuntimeId ?? _remoteCodexRuntimeId(resolvedThreadId);
    final runtime = _runtimeCoordinator.runtimeFor(
      conversationId: runtimeId,
      mode: kChatRuntimeModeAgent,
    );
    final activity = _remoteCodexThreadActivityFromResponse(response);
    final previousActive = runtime?.isAiResponding ?? false;
    final directActiveTurnId = _remoteCodexActiveTurnIdFromThreadResponse(
      response,
    );
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
        !_remoteCodexLatestTurnLooksExternallyActive(response);
    // Otherwise floor against the reducer's runtime state. Snapshot polling
    // runs every 2s and would otherwise downgrade isAiResponding between
    // reasoning deltas when codex doesn't surface a "running" status in
    // thread/read.
    final isAiResponding =
        snapshotIsAiResponding || (previousActive && !snapshotKnowsInactive);
    final activeTurnId = isAiResponding
        ? (directActiveTurnId ??
              _remoteCodexLatestTurnIdFromThreadResponse(response) ??
              runtime?.currentDispatchTaskId ??
              runtime?.lastAgentTaskId ??
              _activeAgentTurnId)
        : null;
    final activeTaskId = isAiResponding
        ? (activeTurnId ??
              runtime?.currentDispatchTaskId ??
              runtime?.lastAgentTaskId ??
              'remote-agent-$resolvedThreadId')
        : null;
    final hasTurns = _remoteCodexThreadResponseHasTurns(response);
    final existingMessages = List<ChatMessageModel>.from(
      runtime?.messages ??
          _messagesByMode[ChatPageMode.agent] ??
          const <ChatMessageModel>[],
    );
    final snapshotMessages = hasTurns
        ? _remoteCodexMessagesFromThreadResponse(
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
            runtime.messages.any(_isPendingAgentRequestMessage));
    final preserveLiveStreamingState = fromPoll && hasLivePushStreaming;
    setState(() {
      _activeRemoteCodexRuntimeId = runtimeId;
      _activeAgentThreadId = resolvedThreadId;
      if (!preserveLiveStreamingState) {
        _activeAgentTurnId = activeTurnId;
      }
      if (status != null) {
        _agentRuntimeStatus = status;
      }
      _currentConversationIdByMode[ChatPageMode.agent] = runtimeId;
      _currentConversationByMode[ChatPageMode.agent] = conversation;
      if (!preserveLiveStreamingState) {
        _isAiRespondingByMode[ChatPageMode.agent] = isAiResponding;
        _isExecutingTaskByMode[ChatPageMode.agent] = isAiResponding;
        _isDeepThinkingByMode[ChatPageMode.agent] = isAiResponding;
        _currentThinkingStageByMode[ChatPageMode.agent] = isAiResponding
            ? ThinkingStage.thinking.value
            : ThinkingStage.complete.value;
        _currentDispatchTaskIdByMode[ChatPageMode.agent] = activeTaskId;
      }
      _messagesByMode[ChatPageMode.agent]!
        ..clear()
        ..addAll(messages);
      _hasMoreMessagesByMode[ChatPageMode.agent] = false;
      _messageOffsetByMode[ChatPageMode.agent] = messages.length;
    });
    _runtimeCoordinator.ensureEphemeralRuntime(
      conversationId: runtimeId,
      mode: kChatRuntimeModeAgent,
      initialMessages: messages,
      conversation: conversation,
      initialChatIslandDisplayLayer: ChatIslandDisplayLayer.mode,
    );
    _runtimeCoordinator.replaceConversationSnapshot(
      conversationId: runtimeId,
      mode: kChatRuntimeModeAgent,
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
        mode: kChatRuntimeModeAgent,
      );
    }
    final updatedRuntime = _runtimeCoordinator.runtimeFor(
      conversationId: runtimeId,
      mode: kChatRuntimeModeAgent,
    );
    if (updatedRuntime != null) {
      _syncAgentModeStateFromRuntime(updatedRuntime);
    }
  }

  void _syncAgentModeStateFromRuntime(ChatConversationRuntimeState runtime) {
    _isAiRespondingByMode[ChatPageMode.agent] = runtime.isAiResponding;
    _isContextCompressingByMode[ChatPageMode.agent] =
        runtime.isContextCompressing;
    _isCheckingExecutableTaskByMode[ChatPageMode.agent] =
        runtime.isCheckingExecutableTask;
    _currentAiMessagesByMode[ChatPageMode.agent]!
      ..clear()
      ..addAll(runtime.currentAiMessages);
    _deepThinkingContentByMode[ChatPageMode.agent] =
        runtime.deepThinkingContent;
    _isDeepThinkingByMode[ChatPageMode.agent] = runtime.isDeepThinking;
    _currentDispatchTaskIdByMode[ChatPageMode.agent] =
        runtime.currentDispatchTaskId;
    _currentThinkingStageByMode[ChatPageMode.agent] =
        runtime.currentThinkingStage;
    _isInputAreaVisibleByMode[ChatPageMode.agent] = runtime.isInputAreaVisible;
    _isExecutingTaskByMode[ChatPageMode.agent] = runtime.isExecutingTask;
    _currentConversationByMode[ChatPageMode.agent] = runtime.conversation;
    _chatIslandDisplayLayerByMode[ChatPageMode.agent] =
        runtime.chatIslandDisplayLayer;
    _lastAgentToolTypeByMode[ChatPageMode.agent] = runtime.lastAgentToolType;
    _browserSessionSnapshotByMode[ChatPageMode.agent] =
        runtime.browserSessionSnapshot;
  }

  bool _isRemoteCodexConfigured() {
    final runtime = _agentRuntimeStatus.runtime?.trim();
    return runtime == 'remote' || _agentRuntimeStatus.remoteEnabled;
  }

  int _ensureRemoteCodexRuntimeForCurrentMessages() {
    final currentId = _currentConversationIdByMode[ChatPageMode.agent];
    if (currentId != null &&
        _runtimeCoordinator.isEphemeralRuntime(
          conversationId: currentId,
          mode: kChatRuntimeModeAgent,
        )) {
      return currentId;
    }
    final runtimeId = _activeAgentThreadId?.trim().isNotEmpty == true
        ? _remoteCodexRuntimeId(_activeAgentThreadId!)
        : (_activeRemoteCodexRuntimeId ??
              _remoteCodexRuntimeId(
                'pending-${DateTime.now().microsecondsSinceEpoch}',
              ));
    _activeRemoteCodexRuntimeId = runtimeId;
    _currentConversationIdByMode[ChatPageMode.agent] = runtimeId;
    _currentConversationByMode[ChatPageMode.agent] ??= ConversationModel(
      id: runtimeId,
      mode: ConversationMode.agent,
      title: 'Agent',
      status: 0,
      lastMessage: _messagesByMode[ChatPageMode.agent]!.isNotEmpty
          ? _messagesByMode[ChatPageMode.agent]!.first.text
          : null,
      messageCount: _messagesByMode[ChatPageMode.agent]!.length,
      createdAt: DateTime.now().millisecondsSinceEpoch,
      updatedAt: DateTime.now().millisecondsSinceEpoch,
    );
    _runtimeCoordinator.ensureEphemeralRuntime(
      conversationId: runtimeId,
      mode: kChatRuntimeModeAgent,
      initialMessages: List<ChatMessageModel>.from(
        _messagesByMode[ChatPageMode.agent]!,
      ),
      conversation: _currentConversationByMode[ChatPageMode.agent],
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
      mode: kChatRuntimeModeAgent,
      conversation:
          _runtimeCoordinator
              .runtimeFor(
                conversationId: runtimeId,
                mode: kChatRuntimeModeAgent,
              )
              ?.conversation ??
          ConversationModel(
            id: runtimeId,
            mode: ConversationMode.agent,
            title:
                'Agent ${normalizedThreadId.length > 6 ? normalizedThreadId.substring(normalizedThreadId.length - 6) : normalizedThreadId}',
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
      mode: kChatRuntimeModeAgent,
    );
    if (runtime != null) {
      final visibleMessages = _messagesByMode[ChatPageMode.agent]!;
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
          _currentConversationByMode[ChatPageMode.agent];
      if (currentConversation != null) {
        runtime.conversation = currentConversation.copyWith(id: runtimeId);
      }
      _currentConversationByMode[ChatPageMode.agent] = runtime.conversation;
    }
    _activeRemoteCodexRuntimeId = runtimeId;
    _activeAgentThreadId = normalizedThreadId;
    _currentConversationIdByMode[ChatPageMode.agent] = runtimeId;
    return runtimeId;
  }

  bool _shouldPromoteRemoteCodexEventToVisibleThread({
    required String threadId,
    required int runtimeId,
  }) {
    final activeThreadId = _activeAgentThreadId?.trim();
    if (activeThreadId == threadId) {
      return true;
    }
    final currentConversationId =
        _currentConversationIdByMode[ChatPageMode.agent];
    if (currentConversationId == runtimeId) {
      return true;
    }
    if (activeThreadId != null && activeThreadId.isNotEmpty) {
      return false;
    }
    if (currentConversationId == null ||
        currentConversationId != _activeRemoteCodexRuntimeId) {
      return false;
    }
    final runtime = _runtimeCoordinator.runtimeFor(
      conversationId: currentConversationId,
      mode: kChatRuntimeModeAgent,
    );
    return (_messagesByMode[ChatPageMode.agent]?.isNotEmpty ?? false) ||
        (runtime?.hasInFlightTask ?? false) ||
        (_currentDispatchTaskIdByMode[ChatPageMode.agent]?.isNotEmpty ?? false);
  }

  Future<void> _showAgentAccountStatus() async {
    if (_agentRuntimeStatus.runtime != 'remote' &&
        !_agentRuntimeStatus.remoteEnabled) {
      return;
    }
    try {
      final account = await AgentRuntimeService.readAccount();
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
                ? 'Agent login required'
                : '需要登录 Agent',
          ),
          action: SnackBarAction(
            label: Localizations.localeOf(context).languageCode == 'en'
                ? 'Login'
                : '登录',
            onPressed: () {
              if (_agentRuntimeStatus.runtime == 'remote' ||
                  _agentRuntimeStatus.remoteEnabled) {
                unawaited(_startRemoteCodexLogin());
              } else {
                GoRouterManager.push('/home/remote_codex_setting');
              }
            },
          ),
        ),
      );
    } catch (error) {
    }
  }

  Future<void> _startRemoteCodexLogin() async {
    try {
      final response = await AgentRuntimeService.startLogin();
      final authUrl = _asAgentString(response['authUrl']);
      if (authUrl == null) return;
      await launchUrlString(authUrl, mode: LaunchMode.externalApplication);
    } catch (error) {
    }
  }

  Future<AgentRuntimeStatus> _refreshConnectedAgentRuntimeStatus() async {
    var status = await AgentRuntimeService.status();
    if (!status.connected) {
      status = await AgentRuntimeService.connect();
      unawaited(AgentRuntimeService.listThreads());
    }
    _applyRefreshedAgentRuntimeStatus(status);
    return status;
  }

  void _applyRefreshedAgentRuntimeStatus(AgentRuntimeStatus status) {
    final sourceChanged =
        agentModelSourceKey(_agentRuntimeStatus) != agentModelSourceKey(status);
    if (!mounted) return;
    setState(() {
      _agentRuntimeStatus = status;
      if (status.runtime != 'remote' &&
          !status.remoteEnabled &&
          _agentPermissionMode == AgentPermissionMode.autoReview) {
        _agentPermissionMode = AgentPermissionMode.defaultMode;
      }
      if (sourceChanged) {
        _activeAgentThreadId = null;
        _activeAgentTurnId = null;
        _agentModelOptions = const <String>[];
        _agentModelListError = null;
      }
    });
  }

  Future<String?> _resolveAgentRequestModel(
    AgentRuntimeStatus status, {
    String? overrideModel,
  }) async {
    final sourceKey = agentModelSourceKey(status);
    final scopedModel = _readAgentPreference(
      _kAgentModelPreferenceKey,
      conversationId: _currentConversationIdByMode[ChatPageMode.agent],
    );
    return selectAgentRequestModel(
      status: status,
      overrideModel: overrideModel,
      activeModel: _activeAgentModelId,
      scopedModel: scopedModel,
      activeModelSourceMatches: _loadedAgentModelSourceKey == sourceKey,
    );
  }
}

int? _asAgentInt(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '');
}

String? _asAgentString(dynamic value) {
  final text = value?.toString().trim() ?? '';
  return text.isEmpty ? null : text;
}

String? _remoteCodexEventThreadId(Map<String, dynamic> event) {
  return _remoteCodexThreadIdFromEnvelope(event);
}

/// Top-level diagnostic counter that survives navigation. Used purely for
/// `flutter logs` / `adb logcat` introspection — the user reported that
/// exec_command tool cards do not surface in our UI even though the codex
/// session rollout contains 18 of them; this counter shows whether the
/// `rawResponseItem/completed` notifications actually reach the Flutter side.
final Map<String, int> _agentEventDiagnosticCounter = <String, int>{};

String _diagnosticEventMethod(Map<String, dynamic> event) {
  final method = _asAgentString(event['method']);
  if (method != null) {
    if (method == 'codex/event') {
      final params = _asAgentMap(event['params']) ?? const <String, dynamic>{};
      final msg = _asAgentMap(params['msg']);
      final msgType = _asAgentString(msg?['type']);
      if (msgType != null) {
        return 'codex/event:$msgType';
      }
    }
    if (method == 'rawResponseItem/completed' ||
        method == 'item/started' ||
        method == 'item/completed') {
      final params = _asAgentMap(event['params']) ?? const <String, dynamic>{};
      final item = _asAgentMap(params['item']);
      final itemType = _asAgentString(item?['type']);
      if (itemType != null) {
        final name = _asAgentString(item?['name']);
        if (name != null) {
          return '$method:$itemType:$name';
        }
        return '$method:$itemType';
      }
    }
    return method;
  }
  final message = _asAgentMap(event['message']);
  return _asAgentString(message?['method']) ?? '<unknown>';
}

const List<String> _remoteCodexEnvelopeKeys = <String>[
  'message',
  'payload',
  'data',
  'event',
  'notification',
  'params',
  'result',
];

String? _remoteCodexThreadIdFromEnvelope(dynamic value, {int depth = 0}) {
  if (depth > 6) {
    return null;
  }
  final map = _asAgentMap(value);
  if (map == null) {
    return null;
  }
  final direct = _asAgentString(map['threadId'] ?? map['thread_id']);
  if (direct != null) {
    return direct;
  }
  final thread = _asAgentMap(map['thread']);
  final threadId = _asAgentString(thread?['id']);
  if (threadId != null) {
    return threadId;
  }
  for (final key in _remoteCodexEnvelopeKeys) {
    final nested = map[key];
    if (nested == null) {
      continue;
    }
    final nestedThreadId = _remoteCodexThreadIdFromEnvelope(
      nested,
      depth: depth + 1,
    );
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
  final thread = _asAgentMap(response['thread']) ?? response;
  final now = DateTime.now().millisecondsSinceEpoch;
  final createdAt =
      _remoteCodexTimeValueMs(thread['createdAt'] ?? thread['created_at']) ??
      now;
  final updatedAt =
      _remoteCodexTimeValueMs(
        thread['updatedAt'] ??
            thread['updated_at'] ??
            thread['lastActivityAt'] ??
            thread['last_activity_at'],
      ) ??
      createdAt;
  final title =
      _asAgentString(
        thread['name'] ??
            thread['title'] ??
            thread['preview'] ??
            response['name'] ??
            response['title'] ??
            response['preview'],
      ) ??
      'Agent';
  return ConversationModel(
    id: runtimeId,
    mode: ConversationMode.agent,
    title: _truncateAgentText(title, 40),
    status: 0,
    lastMessage: _asAgentString(thread['preview']),
    messageCount: _remoteCodexMessagesFromThreadResponse(response).length,
    createdAt: createdAt,
    updatedAt: updatedAt,
  );
}

class _AgentThreadActivityState {
  const _AgentThreadActivityState({required this.known, required this.active});

  final bool known;
  final bool active;

  static const unknown = _AgentThreadActivityState(known: false, active: false);
  static const activeState = _AgentThreadActivityState(
    known: true,
    active: true,
  );
  static const inactiveState = _AgentThreadActivityState(
    known: true,
    active: false,
  );
}

_AgentThreadActivityState _remoteCodexThreadActivityFromResponse(
  Map<String, dynamic> response,
) {
  final thread = _asAgentMap(response['thread']) ?? response;
  _AgentThreadActivityState? inactiveCandidate;
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
    final parsed = _remoteCodexActivityFromValue(value);
    if (parsed == null) {
      continue;
    }
    if (parsed.active) {
      return parsed;
    }
    inactiveCandidate ??= parsed;
  }
  final latestTurnActivity = _remoteCodexLatestTurnActivityFromResponse(
    response,
  );
  if (latestTurnActivity != null) {
    return latestTurnActivity;
  }
  if (inactiveCandidate != null) {
    return inactiveCandidate;
  }
  return _AgentThreadActivityState.unknown;
}

_AgentThreadActivityState? _remoteCodexLatestTurnActivityFromResponse(
  Map<String, dynamic> response,
) {
  final turns = _remoteCodexTurnsFromThreadResponse(response);
  if (turns == null) {
    return null;
  }
  for (var index = turns.length - 1; index >= 0; index -= 1) {
    final turn = _asAgentMap(turns[index]);
    if (turn == null) {
      continue;
    }
    final parsed = _remoteCodexActivityFromValue(
      turn['status'] ?? turn['state'],
    );
    if (parsed != null) {
      return parsed;
    }
  }
  return null;
}

_AgentThreadActivityState? _remoteCodexActivityFromValue(dynamic value) {
  if (value is bool) {
    return value
        ? _AgentThreadActivityState.activeState
        : _AgentThreadActivityState.inactiveState;
  }
  final status = _remoteCodexStatusText(value);
  if (status == null) {
    return null;
  }
  final normalized = _normalizeAgentRuntimeStatus(status);
  if (_remoteCodexStatusIsActive(normalized)) {
    return _AgentThreadActivityState.activeState;
  }
  if (_remoteCodexStatusIsInactive(normalized)) {
    return _AgentThreadActivityState.inactiveState;
  }
  return null;
}

String? _remoteCodexStatusText(dynamic value) {
  if (value == null) {
    return null;
  }
  if (value is String || value is num || value is bool) {
    return _asAgentString(value);
  }
  final map = _asAgentMap(value);
  if (map != null) {
    for (final key in const <String>[
      'type',
      'status',
      'state',
      'value',
      'name',
    ]) {
      final text = _remoteCodexStatusText(map[key]);
      if (text != null) {
        return text;
      }
    }
  }
  return null;
}

String _normalizeAgentRuntimeStatus(String status) =>
    status.trim().toLowerCase().replaceAll(RegExp(r'[^a-z0-9]+'), '');

bool _remoteCodexStatusIsActive(String status) {
  return status == 'running' ||
      status == 'active' ||
      status == 'busy' ||
      status == 'inprogress' ||
      status == 'inflight' ||
      status == 'executing';
}

bool _remoteCodexStatusIsInactive(String status) {
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

String? _remoteCodexActiveTurnIdFromThreadResponse(
  Map<String, dynamic> response,
) {
  final thread = _asAgentMap(response['thread']) ?? response;
  final status =
      _asAgentMap(response['status']) ?? _asAgentMap(thread['status']);
  final direct = _asAgentString(
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
  final turns = _remoteCodexTurnsFromThreadResponse(response);
  if (turns == null) {
    return null;
  }
  for (var index = turns.length - 1; index >= 0; index -= 1) {
    final turn = _asAgentMap(turns[index]);
    if (turn == null) {
      continue;
    }
    final parsed = _remoteCodexActivityFromValue(
      turn['status'] ?? turn['state'],
    );
    if (parsed?.active == true) {
      return _remoteCodexTurnIdAt(turns, index);
    }
  }
  return null;
}

String? _remoteCodexLatestTurnIdFromThreadResponse(
  Map<String, dynamic> response,
) {
  final turns = _remoteCodexTurnsFromThreadResponse(response);
  if (turns == null || turns.isEmpty) {
    return null;
  }
  for (var index = turns.length - 1; index >= 0; index -= 1) {
    final turnId = _remoteCodexTurnIdAt(turns, index);
    if (turnId != null) {
      return turnId;
    }
  }
  return null;
}

bool _remoteCodexThreadResponseHasTurns(Map<String, dynamic> response) {
  return _remoteCodexTurnsFromThreadResponse(response) != null;
}

List<dynamic>? _remoteCodexTurnsFromThreadResponse(
  Map<String, dynamic> response,
) {
  final thread = _asAgentMap(response['thread']) ?? response;
  final rawTurns = thread['turns'] ?? response['turns'];
  return rawTurns is List ? rawTurns : null;
}

String? _remoteCodexTurnIdAt(List<dynamic> turns, int index) {
  if (index < 0 || index >= turns.length) {
    return null;
  }
  final turn = _asAgentMap(turns[index]);
  if (turn == null) {
    return null;
  }
  return _asAgentString(turn['id']) ?? 'turn-$index';
}

List<Map<String, dynamic>> _remoteCodexHistoricalItemsFromTurn(
  Map<String, dynamic> turn,
) {
  final items = <Map<String, dynamic>>[];
  final seen = <String, int>{};

  void addItem(Map<String, dynamic> item) {
    final normalized = _remoteCodexNormalizeHistoricalItem(item);
    if (normalized == null) {
      return;
    }
    final key = _remoteCodexHistoricalItemDedupeKey(normalized);
    final existingIndex = seen[key];
    if (existingIndex != null) {
      items[existingIndex] = _remoteCodexMergeHistoricalItemSnapshot(
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
    final item = _remoteCodexHistoricalItemFromValue(value);
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

  final worklog = _asAgentMap(turn['worklog']);
  addFromValue(worklog?['messages']);
  return items;
}

Map<String, dynamic> _remoteCodexMergeHistoricalItemSnapshot(
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

Map<String, dynamic>? _remoteCodexHistoricalItemFromValue(dynamic value) {
  final map = _asAgentMap(value);
  if (map == null) {
    return null;
  }
  final direct = _remoteCodexNormalizeHistoricalItem(map);
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
    final nested = _remoteCodexHistoricalItemFromValue(map[key]);
    if (nested != null) {
      return _remoteCodexMergeEnvelopeIds(map, nested);
    }
  }
  final params = _asAgentMap(map['params']);
  if (params != null) {
    final nested = _remoteCodexHistoricalItemFromValue(params);
    if (nested != null) {
      return _remoteCodexMergeEnvelopeIds(map, nested);
    }
  }
  final protocolItem = _remoteCodexHistoricalItemFromProtocolEvent(
    params ?? map,
  );
  if (protocolItem != null) {
    return _remoteCodexMergeEnvelopeIds(map, protocolItem);
  }
  final methodItem = _remoteCodexHistoricalItemFromEventMethod(
    _asAgentString(map['method'] ?? map['type']),
    params ?? map,
  );
  if (methodItem != null) {
    return _remoteCodexMergeEnvelopeIds(map, methodItem);
  }
  for (final key in _remoteCodexEnvelopeKeys) {
    if (key == 'params') {
      continue;
    }
    final nested = _remoteCodexHistoricalItemFromValue(map[key]);
    if (nested != null) {
      return _remoteCodexMergeEnvelopeIds(map, nested);
    }
  }
  return null;
}

Map<String, dynamic>? _remoteCodexHistoricalItemFromEventMethod(
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
    return _remoteCodexNormalizeHistoricalItem(<String, dynamic>{
      ...params,
      'id':
          _asAgentString(params['id']) ??
          _asAgentString(params['requestId']) ??
          _asAgentString(params['request_id']),
      'type': 'requestUserInput',
    });
  }
  if (method.endsWith('requestApproval') ||
      lowerMethod.endsWith('request_approval')) {
    return _remoteCodexNormalizeHistoricalItem(<String, dynamic>{
      ...params,
      'id':
          _asAgentString(params['id']) ??
          _asAgentString(params['requestId']) ??
          _asAgentString(params['request_id']),
      'type': 'requestApproval',
    });
  }
  if (method.contains('commandExecution') ||
      method == 'command/exec/outputDelta' ||
      method == 'command/exec/completed' ||
      method == 'process/outputDelta' ||
      method == 'process/exited') {
    return _remoteCodexNormalizeHistoricalItem(<String, dynamic>{
      ...params,
      'id':
          _asAgentString(
            params['itemId'] ??
                params['item_id'] ??
                params['processId'] ??
                params['process_id'] ??
                params['processHandle'] ??
                params['process_handle'],
          ) ??
          _asAgentString(params['id']),
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
    return _remoteCodexNormalizeHistoricalItem(<String, dynamic>{
      ...params,
      'id':
          _asAgentString(params['itemId'] ?? params['item_id']) ??
          _asAgentString(params['id']),
      'type': 'fileChange',
      'status': params['status'] ?? 'completed',
    });
  }
  if (method.contains('mcpToolCall')) {
    return _remoteCodexNormalizeHistoricalItem(<String, dynamic>{
      ...params,
      'id':
          _asAgentString(params['itemId'] ?? params['item_id']) ??
          _asAgentString(params['id']),
      'type': 'mcpToolCall',
      'status': params['status'] ?? 'completed',
    });
  }
  return null;
}

Map<String, dynamic>? _remoteCodexHistoricalItemFromProtocolEvent(
  Map<String, dynamic> value,
) {
  final msg = _remoteCodexHistoricalProtocolMsg(value);
  if (msg == null) {
    return null;
  }
  final msgType = _remoteCodexNormalizeProtocolMsgType(
    _asAgentString(msg['type']),
  );
  if (msgType.isEmpty) {
    return null;
  }
  final eventId = _asAgentString(value['id']);
  final callId = _asAgentString(
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
      ..._remoteCodexTopLevelIds(value),
      ..._remoteCodexTopLevelIds(msg),
      if (callId != null) 'id': callId,
      ...item,
    };
  }

  switch (msgType) {
    case 'item_started':
    case 'item_completed':
      final item = _asAgentMap(msg['item']);
      return item == null ? null : _remoteCodexNormalizeHistoricalItem(item);
    case 'raw_response_item':
      final item = _asAgentMap(msg['item']);
      return item == null ? null : _remoteCodexNormalizeHistoricalItem(item);
    case 'agent_message':
      final text = _remoteCodexExtractText(msg['message'] ?? msg['text']);
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
      final text = _remoteCodexExtractText(msg['delta'] ?? msg['text']);
      if (text.trim().isEmpty) {
        return null;
      }
      return withIds(<String, dynamic>{'type': 'reasoning', 'summary': text});
    case 'exec_command_begin':
    case 'exec_command_output_delta':
    case 'terminal_interaction':
    case 'exec_command_end':
      return _remoteCodexNormalizeHistoricalItem(
        withIds(_remoteCodexHistoricalCommandItem(msg, msgType: msgType)),
      );
    case 'mcp_tool_call_begin':
    case 'mcp_tool_call_end':
      return _remoteCodexNormalizeHistoricalItem(
        withIds(
          _remoteCodexHistoricalMcpToolItem(
            msg,
            completed: msgType.endsWith('_end'),
          ),
        ),
      );
    case 'web_search_begin':
    case 'web_search_end':
      return _remoteCodexNormalizeHistoricalItem(
        withIds(
          _remoteCodexHistoricalWebSearchItem(
            msg,
            completed: msgType.endsWith('_end'),
          ),
        ),
      );
    case 'view_image_tool_call':
      return _remoteCodexNormalizeHistoricalItem(
        withIds(<String, dynamic>{
          ...msg,
          'type': 'imageView',
          'status': 'completed',
        }),
      );
    case 'patch_apply_begin':
    case 'patch_apply_updated':
    case 'patch_apply_end':
      return _remoteCodexNormalizeHistoricalItem(
        withIds(
          _remoteCodexHistoricalPatchItem(
            msg,
            completed: msgType.endsWith('_end'),
          ),
        ),
      );
  }
  return null;
}

Map<String, dynamic>? _remoteCodexHistoricalProtocolMsg(
  Map<String, dynamic> root, {
  int depth = 0,
}) {
  if (depth > 6) {
    return null;
  }
  final direct = _asAgentMap(root['msg']);
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
    final nested = _asAgentMap(root[key]);
    if (nested == null) {
      continue;
    }
    final msg = _remoteCodexHistoricalProtocolMsg(nested, depth: depth + 1);
    if (msg != null) {
      return msg;
    }
  }
  return null;
}

String _remoteCodexNormalizeProtocolMsgType(String? rawType) {
  final value = rawType?.trim().toLowerCase() ?? '';
  if (value.isEmpty) {
    return '';
  }
  return value.replaceAll(RegExp(r'[^a-z0-9]+'), '_');
}

Map<String, dynamic> _remoteCodexTopLevelIds(Map<String, dynamic> value) {
  final ids = <String, dynamic>{};
  final meta = _asAgentMap(value['_meta']);
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

Map<String, dynamic> _remoteCodexHistoricalCommandItem(
  Map<String, dynamic> msg, {
  required String msgType,
}) {
  final command = _remoteCodexCommandTextFromValue(msg['command']);
  final exitCode = _asAgentInt(msg['exitCode'] ?? msg['exit_code']);
  final output = msgType == 'exec_command_output_delta'
      ? _remoteCodexHistoricalOutputDelta(msg)
      : _remoteCodexExtractText(
          msg['aggregatedOutput'] ??
              msg['aggregated_output'] ??
              msg['output'] ??
              msg['stdout'] ??
              msg['formattedOutput'] ??
              msg['formatted_output'],
        );
  final status =
      _asAgentString(msg['status']) ??
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

Map<String, dynamic> _remoteCodexHistoricalMcpToolItem(
  Map<String, dynamic> msg, {
  required bool completed,
}) {
  final invocation =
      _asAgentMap(msg['invocation']) ?? const <String, dynamic>{};
  final resultFields = _remoteCodexHistoricalMcpResultFields(msg['result']);
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

Map<String, dynamic> _remoteCodexHistoricalMcpResultFields(dynamic value) {
  if (value == null) {
    return const <String, dynamic>{};
  }
  final map = _asAgentMap(value);
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

Map<String, dynamic> _remoteCodexHistoricalWebSearchItem(
  Map<String, dynamic> msg, {
  required bool completed,
}) {
  final action = _asAgentMap(msg['action']);
  return <String, dynamic>{
    ...msg,
    'type': 'webSearch',
    'query': msg['query'] ?? action?['query'],
    'status': completed ? 'completed' : 'in_progress',
  };
}

Map<String, dynamic> _remoteCodexHistoricalPatchItem(
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
        _asAgentString(msg['status']) ??
        (completed
            ? success == false
                  ? 'failed'
                  : 'completed'
            : 'in_progress'),
  };
}

String? _remoteCodexCommandTextFromValue(dynamic value) {
  if (value == null) {
    return null;
  }
  if (value is String) {
    final text = value.trim();
    return text.isEmpty ? null : text;
  }
  if (value is List) {
    final parts = value
        .map(_remoteCodexExtractText)
        .map((part) => part.trim())
        .where((part) => part.isNotEmpty)
        .toList(growable: false);
    return parts.isEmpty ? null : parts.join(' ');
  }
  final text = _remoteCodexExtractText(value).trim();
  return text.isEmpty ? null : text;
}

String _remoteCodexHistoricalOutputDelta(Map<String, dynamic> msg) {
  final decoded =
      _decodeAgentBase64(msg['chunk']) ??
      _decodeAgentByteList(msg['chunk']) ??
      _decodeAgentBase64(msg['deltaBase64']) ??
      _decodeAgentBase64(msg['delta_base64']) ??
      _remoteCodexExtractText(msg['delta'] ?? msg['output'] ?? msg['text']);
  final stream = _asAgentString(msg['stream'])?.toLowerCase();
  if (decoded.isEmpty || stream == null || stream == 'stdout') {
    return decoded;
  }
  return '\n[$stream]\n$decoded${decoded.endsWith('\n') ? '' : '\n'}';
}

String? _decodeAgentBase64(dynamic value) {
  final encoded = _asAgentString(value);
  if (encoded == null) {
    return null;
  }
  try {
    return utf8.decode(base64Decode(encoded), allowMalformed: true);
  } catch (_) {
    return null;
  }
}

String? _decodeAgentByteList(dynamic value) {
  if (value is! List) {
    return null;
  }
  final bytes = <int>[];
  for (final item in value) {
    final byte = _asAgentInt(item);
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

Map<String, dynamic> _remoteCodexMergeEnvelopeIds(
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

Map<String, dynamic>? _remoteCodexNormalizeHistoricalItem(
  Map<String, dynamic> item,
) {
  final normalized = Map<String, dynamic>.from(item);
  var itemType = canonicalAgentItemType(_asAgentString(normalized['type']));
  final role = _asAgentString(
    normalized['role'] ?? _asAgentMap(normalized['author'])?['role'],
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
  if (!_remoteCodexLooksLikeHistoricalItemType(itemType)) {
    return null;
  }
  normalized['type'] = itemType;
  return normalized;
}

bool _remoteCodexLooksLikeHistoricalItemType(String itemType) {
  final canonical = canonicalAgentItemType(itemType);
  return canonical == 'userMessage' ||
      canonical == 'agentMessage' ||
      canonical == 'reasoning' ||
      _remoteCodexHistoricalRequestItemTypes.contains(canonical) ||
      _remoteCodexHistoricalToolItemTypes.contains(canonical) ||
      _remoteCodexHistoricalToolOutputItemTypes.contains(canonical);
}

String _remoteCodexHistoricalItemDedupeKey(Map<String, dynamic> item) {
  final type = canonicalAgentItemType(_asAgentString(item['type']));
  final id =
      _asAgentString(
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
      _remoteCodexStableItemKey(item);
  return '$type:$id';
}

bool _remoteCodexLatestTurnLooksExternallyActive(
  Map<String, dynamic> response,
) {
  final turns = _remoteCodexTurnsFromThreadResponse(response);
  if (turns == null || turns.isEmpty) {
    return false;
  }
  for (var index = turns.length - 1; index >= 0; index -= 1) {
    final turn = _asAgentMap(turns[index]);
    if (turn == null) {
      continue;
    }
    final activity = _remoteCodexActivityFromValue(
      turn['status'] ?? turn['state'],
    );
    if (activity?.active == true) {
      return true;
    }
    final statusText = _remoteCodexStatusText(turn['status'] ?? turn['state']);
    final normalizedStatus = statusText == null
        ? null
        : _normalizeAgentRuntimeStatus(statusText);
    final completedAt =
        _remoteCodexTimeValueMs(turn['completedAt'] ?? turn['completed_at']) ??
        _remoteCodexTimeValueMs(turn['finishedAt'] ?? turn['finished_at']);
    final hasError = turn['error'] != null;
    final hasItems = _remoteCodexHistoricalItemsFromTurn(turn).isNotEmpty;
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

String _remoteCodexThreadContentSignature(Map<String, dynamic> response) {
  final thread = _asAgentMap(response['thread']) ?? response;
  final turns = _remoteCodexTurnsFromThreadResponse(response);
  final buffer = StringBuffer()
    ..write(_asAgentString(thread['id'] ?? response['threadId']) ?? '')
    ..write('|');
  if (turns == null) {
    buffer
      ..write(
        _remoteCodexTimeValueMs(thread['updatedAt'] ?? thread['updated_at']) ??
            '',
      )
      ..write('|')
      ..write(_asAgentString(thread['preview'] ?? response['preview']) ?? '');
    return buffer.toString();
  }
  for (var turnIndex = 0; turnIndex < turns.length; turnIndex += 1) {
    final turn = _asAgentMap(turns[turnIndex]);
    if (turn == null) {
      continue;
    }
    buffer
      ..write(_remoteCodexTurnIdAt(turns, turnIndex) ?? '')
      ..write(':')
      ..write(_remoteCodexStatusText(turn['status'] ?? turn['state']) ?? '')
      ..write(':')
      ..write(
        _remoteCodexTimeValueMs(turn['startedAt'] ?? turn['started_at']) ?? '',
      )
      ..write(':')
      ..write(
        _remoteCodexTimeValueMs(turn['completedAt'] ?? turn['completed_at']) ??
            '',
      )
      ..write('|');
    final rawItems = _remoteCodexHistoricalItemsFromTurn(turn);
    for (var itemIndex = 0; itemIndex < rawItems.length; itemIndex += 1) {
      final item = rawItems[itemIndex];
      buffer
        ..write(_asAgentString(item['id']) ?? '$turnIndex-$itemIndex')
        ..write(',')
        ..write(_asAgentString(item['type']) ?? '')
        ..write(',')
        ..write(_remoteCodexStatusText(item['status'] ?? item['state']) ?? '')
        ..write(',')
        ..write(
          _remoteCodexExtractText(
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
      ..write(attachments == null ? 0 : _safeAgentJson(attachments).hashCode);
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
    return snapshotMessages
        .map(canonicalizeAgentHistoryMessage)
        .toList(growable: false);
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
    mergedById[snapshot.id] = canonicalizeAgentHistoryMessage(
      existing != null &&
              _shouldPreferExistingRemoteMessage(
                existing: existing,
                snapshot: snapshot,
                activeTaskId: activeTaskId,
                isAiResponding: isAiResponding,
              )
          ? existing
          : snapshot,
    );
  }
  for (final existing in existingMessages) {
    if (snapshotById.containsKey(existing.id)) {
      continue;
    }
    if (existing.type == 1 && existing.user == 1) {
      if (userMessageIdsToPreserve.contains(existing.id)) {
        mergedById[existing.id] = canonicalizeAgentHistoryMessage(existing);
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
    mergedById[existing.id] = canonicalizeAgentHistoryMessage(existing);
  }
  final merged = mergedById.values.toList(growable: false)
    ..sort((a, b) => b.createAt.compareTo(a.createAt));
  return _normalizeAgentLoadingThinkingCards(
    merged,
    activeTaskId: activeTaskId,
    isAiResponding: isAiResponding,
  );
}

List<ChatMessageModel> _normalizeAgentLoadingThinkingCards(
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
          ? _completeAgentThinkingSnapshotMessage(message, taskId: taskId)
          : message,
    );
  }
  return normalized;
}

ChatMessageModel _completeAgentThinkingSnapshotMessage(
  ChatMessageModel message, {
  required String? taskId,
}) {
  final cardData = Map<String, dynamic>.from(
    message.cardData ?? const <String, dynamic>{},
  );
  final resolvedTaskId =
      taskId ??
      _asAgentString(cardData['taskID']) ??
      _asAgentString(message.streamMeta?['parentTaskId']);
  final startTime =
      _asAgentInt(cardData['startTime']) ??
      message.createAt.millisecondsSinceEpoch;
  cardData['isLoading'] = false;
  cardData['stage'] = ThinkingStage.complete.value;
  if (resolvedTaskId != null) {
    cardData['taskID'] = resolvedTaskId;
  }
  cardData['cardId'] = _asAgentString(cardData['cardId']) ?? message.id;
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
  if (_isInFlightAgentMessage(existing)) {
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
  if (_isAgentRequestMessage(message)) {
    if (isAiResponding) {
      return true;
    }
    final taskId = _messageTaskId(message);
    return taskId != null && snapshotTaskIds.contains(taskId);
  }
  final isAgentTool = isAcpAgentToolSummaryMessage(message);
  if (isAiResponding &&
      activeTaskId != null &&
      _messageBelongsToTask(message, activeTaskId)) {
    return isAgentTool || _isInFlightAgentMessage(message);
  }
  if (isAgentTool) {
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
      _asAgentString(message.streamMeta?['parentTaskId']) ??
      _asAgentString(cardData?['taskId']) ??
      _asAgentString(cardData?['taskID']);
  return parentTaskId;
}

bool _isAgentRequestMessage(ChatMessageModel message) {
  final cardData = message.cardData;
  return isAgentRequestCardType(cardData?['type']);
}

bool _isPendingAgentRequestMessage(ChatMessageModel message) {
  if (!_isAgentRequestMessage(message)) return false;
  final cardData = message.cardData;
  final status = _asAgentString(cardData?['status'])?.toLowerCase();
  return status == null ||
      status == 'pending' ||
      status == 'running' ||
      status == 'requested' ||
      status == 'open' ||
      status == 'progress';
}

bool _isInFlightAgentMessage(ChatMessageModel message) {
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
  final status = _asAgentString(cardData['status'])?.toLowerCase();
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

List<ChatMessageModel> _remoteCodexMessagesFromThreadResponse(
  Map<String, dynamic> response, {
  bool active = false,
  String? activeTurnId,
}) {
  final thread = _asAgentMap(response['thread']) ?? response;
  final agentId =
      _asAgentString(thread['agentId'] ?? response['agentId']) ?? 'codex-acp';
  final agentName = _asAgentString(
    thread['agentName'] ?? response['agentName'],
  );
  final rawTurns = thread['turns'] ?? response['turns'];
  if (rawTurns is! List) {
    return const <ChatMessageModel>[];
  }
  final chronological = <ChatMessageModel>[];
  final effectiveActiveTurnId =
      activeTurnId ??
      (active ? _remoteCodexLatestTurnIdFromThreadResponse(response) : null);
  var seq = 0;
  for (var turnIndex = 0; turnIndex < rawTurns.length; turnIndex += 1) {
    final turn = _asAgentMap(rawTurns[turnIndex]);
    if (turn == null) {
      continue;
    }
    final turnId =
        _remoteCodexTurnIdAt(rawTurns, turnIndex) ?? 'turn-$turnIndex';
    final isActiveTurn =
        active &&
        ((effectiveActiveTurnId != null && turnId == effectiveActiveTurnId) ||
            (effectiveActiveTurnId == null &&
                turnIndex == rawTurns.length - 1));
    final turnStartedAt =
        _remoteCodexTimeValueMs(turn['startedAt'] ?? turn['started_at']) ??
        DateTime.now().millisecondsSinceEpoch;
    final rawItems = _remoteCodexHistoricalItemsFromTurn(turn);
    if (rawItems.isEmpty) {
      continue;
    }
    for (var itemIndex = 0; itemIndex < rawItems.length; itemIndex += 1) {
      final item = rawItems[itemIndex];
      final itemType = canonicalAgentItemType(_asAgentString(item['type']));
      final itemId =
          _asAgentString(item['id']) ??
          _asAgentString(item['callId']) ??
          _asAgentString(item['call_id']) ??
          '$turnId-${_remoteCodexStableItemKey(item)}';
      final createdAt = DateTime.fromMillisecondsSinceEpoch(
        (_remoteCodexTimeValueMs(
                  item['createdAt'] ??
                      item['created_at'] ??
                      item['startedAt'] ??
                      item['started_at'],
                ) ??
                turnStartedAt) +
            itemIndex,
      );
      if (itemType == 'userMessage') {
        final userContent = _remoteCodexExtractUserMessageContent(
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
          'id': '$itemId-agent-user',
        };
        if (userContent.attachments.isNotEmpty) {
          content['attachments'] = userContent.attachments;
        }
        chronological.add(
          ChatMessageModel(
            id: '$itemId-agent-user',
            type: 1,
            user: 1,
            content: content,
            createAt: createdAt,
          ),
        );
        continue;
      }
      if (itemType == 'agentMessage') {
        final text = _remoteCodexExtractText(
          item['text'] ?? item['message'] ?? item['content'],
        );
        if (text.trim().isEmpty) {
          continue;
        }
        seq += 1;
        final messageId = '$itemId-agent-message';
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
        final text = _remoteCodexExtractText(
          item['summary'] ?? item['text'] ?? item['content'],
        );
        if (text.trim().isEmpty && !isActiveTurn) {
          continue;
        }
        seq += 1;
        final cardId = '$itemId-agent-thinking';
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
      if (_remoteCodexHistoricalRequestItemTypes.contains(itemType)) {
        seq += 1;
        final requestKind = itemType == 'requestApproval'
            ? 'approval'
            : 'user_input';
        final question = _remoteCodexHistoricalFirstQuestion(item);
        final cardSuffix = requestKind == 'approval'
            ? 'approval'
            : 'user-input';
        final cardId = '$itemId-agent-$cardSuffix';
        final title = requestKind == 'approval'
            ? _remoteCodexHistoricalApprovalTitle(item)
            : question.title;
        final detail = requestKind == 'approval'
            ? _remoteCodexHistoricalApprovalDetail(item)
            : question.detail;
        final status = _remoteCodexHistoricalRequestStatus(
          item,
          requestKind: requestKind,
        );
        chronological.add(
          ChatMessageModel.cardMessage(
            <String, dynamic>{
              'type': kAgentRequestCardType,
              'taskId': turnId,
              'requestId':
                  _asAgentString(item['requestId']) ??
                  _asAgentString(item['request_id']) ??
                  _asAgentString(item['id']) ??
                  itemId,
              'requestKind': requestKind,
              'title': title,
              'detail': detail,
              if (requestKind == 'user_input') 'questionId': question.id,
              'rawParamsJson': _safeAgentJson(item),
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
      if (_remoteCodexHistoricalToolOutputItemTypes.contains(itemType)) {
        final outputText = _remoteCodexRawOutputText(item).trimRight();
        final callId =
            _asAgentString(item['callId']) ?? _asAgentString(item['call_id']);
        final existingIndex = callId == null
            ? -1
            : _remoteCodexFindToolMessageIndexForCallId(chronological, callId);
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
              ? _truncateAgentText(outputText, 96)
              : (existingCardData['summary'] ?? '').toString();
          existingCardData.addAll(<String, dynamic>{
            'status': 'success',
            'summary': summary,
            'progress': summary,
            'resultPreviewJson': _safeAgentJson(item['output'] ?? item),
            'rawResultJson': _safeAgentJson(item),
            'terminalOutput': terminalOutput,
            'terminalOutputDelta': '',
            'showTerminalOutput':
                terminalOutput.isNotEmpty || existingToolType == 'terminal',
          });
          final existingSeq = _asAgentInt(existing.streamMeta?['seq']) ?? seq;
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
            ? '$turnId-${_remoteCodexStableItemKey(item)}'
            : itemId;
        final toolInfo = normalizeAgentToolCall(
          item,
          itemType: itemType,
          fallbackToolType: itemType == 'tool_search_output'
              ? 'search'
              : 'tool',
          fallbackStatus: 'success',
        );
        final toolKind = agentToolCardSuffix(
          toolInfo.toolType,
          itemType: itemType,
        );
        final cardId = '$outputItemId-agent-$toolKind';
        final summary = outputText.isNotEmpty
            ? _truncateAgentText(outputText, 96)
            : toolInfo.summary;
        chronological.add(
          ChatMessageModel.cardMessage(
            <String, dynamic>{
              'type': 'agent_tool_summary',
              'uiStyle': kAgentToolUiStyle,
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
      if (_remoteCodexHistoricalToolItemTypes.contains(itemType)) {
        seq += 1;
        final toolInfo = normalizeAgentToolCall(
          item,
          itemType: itemType,
          fallbackStatus: 'success',
        );
        final toolKind = agentToolCardSuffix(
          toolInfo.toolType,
          itemType: itemType,
        );
        final cardId = '$itemId-agent-$toolKind';
        final itemActivity = _remoteCodexActivityFromValue(
          item['status'] ?? item['state'],
        );
        final isRunning = isActiveTurn && itemActivity?.active != false;
        final normalizedStatus = toolInfo.status == 'running' && !isRunning
            ? 'success'
            : toolInfo.status;
        final status = isRunning ? 'running' : normalizedStatus;
        final toolTitle = toolInfo.toolTitle;
        final summary = _remoteCodexExtractText(
          item['summary'] ??
              item['status'] ??
              item['output'] ??
              item['text'] ??
              item['content'],
        );
        final rawJson = toolInfo.rawResultJson.isNotEmpty
            ? toolInfo.rawResultJson
            : _safeAgentJson(item);
        final terminalOutput = toolInfo.terminalOutput.isNotEmpty
            ? toolInfo.terminalOutput
            : _remoteCodexExtractText(item['output']);
        final diffText = toolInfo.toolType == 'file'
            ? extractAgentDiffText(
                    item,
                    outputText: terminalOutput,
                    progress: summary,
                    summary: summary,
                  ) ??
                  ''
            : '';
        final diffSummary = diffText.isEmpty
            ? null
            : parseAgentDiffText(diffText);
        final diffPreview = diffSummary == null
            ? ''
            : summarizeAgentDiff(diffSummary);
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
            ? extractAgentDiffPath(item) ??
                  (diffSummary?.primaryPath.trim().isNotEmpty == true
                      ? diffSummary!.primaryPath
                      : null)
            : null;
        final cardData = <String, dynamic>{
          'type': 'agent_tool_summary',
          'uiStyle': kAgentToolUiStyle,
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
  return _normalizeAgentLoadingThinkingCards(
        messages,
        activeTaskId: effectiveActiveTurnId,
        isAiResponding: active,
      )
      .map(
        (message) => _withAcpAgentIdentity(
          message,
          agentId: agentId,
          agentName: agentName,
        ),
      )
      .toList(growable: false);
}

ChatMessageModel _withAcpAgentIdentity(
  ChatMessageModel message, {
  required String agentId,
  String? agentName,
}) {
  if (message.user == 1 || message.agentId != null) {
    return message;
  }
  final content = Map<String, dynamic>.from(
    message.content ?? const <String, dynamic>{},
  );
  content['agentId'] = agentId;
  if (agentName != null) {
    content['agentName'] = agentName;
  }
  final cardData = message.cardData;
  if (cardData != null) {
    content['cardData'] = <String, dynamic>{
      ...cardData,
      'agentId': agentId,
      if (agentName != null) 'agentName': agentName,
    };
  }
  return message.copyWith(content: content);
}

@visibleForTesting
List<ChatMessageModel> remoteCodexMessagesFromThreadResponseForTesting(
  Map<String, dynamic> response, {
  bool active = false,
  String? activeTurnId,
}) {
  return _remoteCodexMessagesFromThreadResponse(
    response,
    active: active,
    activeTurnId: activeTurnId,
  );
}

@visibleForTesting
String? remoteCodexActiveTurnIdFromThreadResponseForTesting(
  Map<String, dynamic> response,
) {
  return _remoteCodexActiveTurnIdFromThreadResponse(response);
}

@visibleForTesting
bool remoteCodexLatestTurnLooksExternallyActiveForTesting(
  Map<String, dynamic> response,
) {
  return _remoteCodexLatestTurnLooksExternallyActive(response);
}

Map<String, dynamic>? _asAgentMap(dynamic value) {
  if (value is! Map) {
    return null;
  }
  return value.map((key, nestedValue) {
    return MapEntry(key.toString(), nestedValue);
  });
}

class _AgentUserMessageContent {
  const _AgentUserMessageContent({
    required this.text,
    required this.attachments,
  });

  final String text;
  final List<Map<String, dynamic>> attachments;
}

_AgentUserMessageContent _remoteCodexExtractUserMessageContent(dynamic value) {
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

    final map = _asAgentMap(node);
    if (map == null) {
      final fallback = node.toString();
      if (fallback.isNotEmpty) {
        text.write(fallback);
      }
      return;
    }

    final type = _asAgentString(map['type'])?.toLowerCase();
    if (_remoteCodexBlockTypeLooksText(type)) {
      final blockText = _remoteCodexExtractText(
        map['text'] ?? map['content'] ?? map['value'] ?? map['input'],
      );
      if (blockText.isNotEmpty) {
        text.write(blockText);
      }
      return;
    }

    if (_remoteCodexMapLooksLikeImageBlock(map)) {
      final attachment = _remoteCodexImageAttachmentFromBlock(
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

    final fallback = _remoteCodexExtractText(map);
    if (fallback.isNotEmpty) {
      text.write(fallback);
    }
  }

  visit(value);
  return _AgentUserMessageContent(
    text: text.toString(),
    attachments: List<Map<String, dynamic>>.unmodifiable(attachments),
  );
}

bool _remoteCodexBlockTypeLooksText(String? type) {
  if (type == null) return false;
  final normalized = type.replaceAll('-', '_');
  return normalized == 'text' ||
      normalized == 'input_text' ||
      normalized == 'message_text';
}

bool _remoteCodexBlockTypeLooksImage(String? type) {
  if (type == null) return false;
  final normalized = type.replaceAll('-', '_');
  return normalized == 'image' ||
      normalized == 'input_image' ||
      normalized == 'image_url' ||
      normalized == 'screenshot' ||
      normalized.endsWith('_image');
}

bool _remoteCodexMapLooksLikeImageBlock(Map<String, dynamic> map) {
  final type = _asAgentString(map['type'])?.toLowerCase();
  if (_remoteCodexBlockTypeLooksImage(type)) {
    return true;
  }
  final mimeType = _asAgentString(
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

Map<String, dynamic>? _remoteCodexImageAttachmentFromBlock(
  Map<String, dynamic> map,
  int index,
) {
  final source =
      _remoteCodexImageStringFromValue(map['dataUrl']) ??
      _remoteCodexImageStringFromValue(map['data_url']) ??
      _remoteCodexImageStringFromValue(map['url']) ??
      _remoteCodexImageStringFromValue(map['imageUrl']) ??
      _remoteCodexImageStringFromValue(map['image_url']) ??
      _remoteCodexImageStringFromValue(map['image']) ??
      _remoteCodexImageStringFromValue(map['src']) ??
      _remoteCodexImageStringFromValue(map['source']);
  final path =
      _asAgentString(
        map['path'] ??
            map['filePath'] ??
            map['file_path'] ??
            map['filename'] ??
            map['fileName'],
      ) ??
      (source != null && !_remoteCodexImageSourceIsUrl(source) ? source : null);
  final url = source != null && _remoteCodexImageSourceIsUrl(source)
      ? source
      : null;
  final rawBase64 = _remoteCodexImageBase64FromBlock(map);
  final mimeType = _remoteCodexImageMimeType(
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
    'name': _remoteCodexImageAttachmentName(
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

String? _remoteCodexImageStringFromValue(dynamic value) {
  if (value == null) return null;
  if (value is String) {
    final text = value.trim();
    return text.isEmpty ? null : text;
  }
  final map = _asAgentMap(value);
  if (map != null) {
    for (final key in const <String>[
      'url',
      'dataUrl',
      'data_url',
      'src',
      'source',
      'path',
    ]) {
      final nested = _remoteCodexImageStringFromValue(map[key]);
      if (nested != null) {
        return nested;
      }
    }
  }
  return null;
}

String? _remoteCodexImageBase64FromBlock(Map<String, dynamic> map) {
  final raw = _asAgentString(map['base64'] ?? map['b64_json']);
  if (raw == null || raw.startsWith('data:')) {
    return null;
  }
  return raw;
}

bool _remoteCodexImageSourceIsUrl(String value) {
  final normalized = value.trim().toLowerCase();
  return normalized.startsWith('data:') ||
      normalized.startsWith('http://') ||
      normalized.startsWith('https://');
}

String? _remoteCodexImageMimeType({
  required dynamic explicit,
  required String? source,
  required String? path,
}) {
  final explicitText = _asAgentString(explicit)?.toLowerCase();
  if (explicitText != null) {
    return explicitText.startsWith('image/')
        ? explicitText
        : 'image/$explicitText';
  }
  final dataMime = _remoteCodexMimeTypeFromDataUrl(source);
  if (dataMime != null) {
    return dataMime;
  }
  return _remoteCodexImageMimeTypeFromPath(path ?? source ?? '');
}

String? _remoteCodexMimeTypeFromDataUrl(String? value) {
  final source = value?.trim() ?? '';
  if (!source.toLowerCase().startsWith('data:')) {
    return null;
  }
  final comma = source.indexOf(',');
  final meta = comma == -1 ? source.substring(5) : source.substring(5, comma);
  final mime = meta.split(';').first.trim().toLowerCase();
  return mime.startsWith('image/') ? mime : null;
}

String? _remoteCodexImageMimeTypeFromPath(String value) {
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

String _remoteCodexImageAttachmentName({
  required Map<String, dynamic> map,
  required String? source,
  required String? path,
  required String? mimeType,
  required int index,
}) {
  final explicitName = _asAgentString(
    map['name'] ?? map['fileName'] ?? map['filename'],
  );
  if (explicitName != null) {
    return explicitName;
  }
  final pathName = _remoteCodexPathNameWithoutQuery(path);
  if (pathName != null) {
    return pathName;
  }
  final sourceName = _remoteCodexPathNameWithoutQuery(source);
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

String? _remoteCodexPathNameWithoutQuery(String? value) {
  final raw = value?.trim() ?? '';
  if (raw.isEmpty || raw.toLowerCase().startsWith('data:')) {
    return null;
  }
  final withoutQuery = raw.split('?').first.split('#').first;
  return _remoteCodexLastPathSegment(withoutQuery);
}

String _remoteCodexExtractText(dynamic value) {
  if (value == null) return '';
  if (value is String) return value;
  if (value is num || value is bool) return value.toString();
  if (value is List) {
    return value
        .map(_remoteCodexExtractText)
        .where((text) => text.isNotEmpty)
        .join();
  }
  final map = _asAgentMap(value);
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
      final text = _remoteCodexExtractText(map[key]);
      if (text.isNotEmpty) {
        return text;
      }
    }
  }
  return value.toString();
}

int? _remoteCodexTimeValueMs(dynamic value) {
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

String _truncateAgentText(String text, int maxLength) {
  final normalized = text.trim().replaceAll(RegExp(r'\s+'), ' ');
  if (normalized.length <= maxLength) {
    return normalized;
  }
  return '${normalized.substring(0, maxLength)}...';
}

String _safeAgentJson(dynamic value) {
  try {
    return const JsonEncoder.withIndent('  ').convert(value);
  } catch (_) {
    return value?.toString() ?? '';
  }
}

class _AgentHistoricalQuestion {
  const _AgentHistoricalQuestion({
    required this.id,
    required this.title,
    required this.detail,
  });

  final String id;
  final String title;
  final String detail;
}

_AgentHistoricalQuestion _remoteCodexHistoricalFirstQuestion(
  Map<String, dynamic> item,
) {
  final params = _asAgentMap(item['params']);
  final questions = item['questions'] ?? params?['questions'];
  if (questions is List && questions.isNotEmpty) {
    final first = _asAgentMap(questions.first);
    if (first != null) {
      final id =
          _asAgentString(first['id']) ??
          _asAgentString(first['questionId']) ??
          'answer';
      final title =
          _remoteCodexFirstText([
            first['label'],
            first['title'],
            first['question'],
          ]) ??
          'Agent needs input';
      final detail =
          _remoteCodexFirstText([first['description'], first['placeholder']]) ??
          title;
      return _AgentHistoricalQuestion(id: id, title: title, detail: detail);
    }
  }
  final id =
      _asAgentString(item['questionId']) ??
      _asAgentString(item['question_id']) ??
      _asAgentString(item['id']) ??
      'answer';
  final title =
      _remoteCodexFirstText([
        item['question'],
        item['title'],
        params?['question'],
        params?['title'],
      ]) ??
      'Agent needs input';
  final detail =
      _remoteCodexFirstText([
        item['description'],
        item['placeholder'],
        params?['description'],
        params?['placeholder'],
      ]) ??
      title;
  return _AgentHistoricalQuestion(id: id, title: title, detail: detail);
}

String _remoteCodexHistoricalApprovalTitle(Map<String, dynamic> item) {
  final command = _remoteCodexFirstText([
    item['command'],
    _asAgentMap(item['action'])?['command'],
    _asAgentMap(item['params'])?['command'],
  ]);
  if (command != null) {
    return _truncateAgentText(command, 48);
  }
  return 'Agent approval';
}

String _remoteCodexHistoricalApprovalDetail(Map<String, dynamic> item) {
  return _remoteCodexFirstText([
        item['reason'],
        item['description'],
        item['command'],
        _asAgentMap(item['params'])?['reason'],
        _asAgentMap(item['params'])?['description'],
        _asAgentMap(item['params'])?['command'],
      ]) ??
      _safeAgentJson(item);
}

String _remoteCodexHistoricalRequestStatus(
  Map<String, dynamic> item, {
  required String requestKind,
}) {
  final explicit = _remoteCodexNormalizeHistoricalRequestStatus(
    _remoteCodexFirstText([
      item['status'],
      item['state'],
      item['requestStatus'],
      item['request_status'],
      _asAgentMap(item['request'])?['status'],
      _asAgentMap(item['request'])?['state'],
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
  if (_remoteCodexHasRequestResponse(response)) {
    if (requestKind == 'approval') {
      final decision = _remoteCodexNormalizeHistoricalRequestStatus(
        _remoteCodexFirstText([
          item['decision'],
          _asAgentMap(response)?['decision'],
          _asAgentMap(response)?['status'],
          _asAgentMap(response)?['state'],
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

String? _remoteCodexNormalizeHistoricalRequestStatus(
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

bool _remoteCodexHasRequestResponse(dynamic value) {
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

String? _remoteCodexFirstText(Iterable<dynamic> values) {
  for (final value in values) {
    final text = _remoteCodexExtractText(value).trim();
    if (text.isNotEmpty) {
      return text;
    }
  }
  return null;
}

const Set<String> _remoteCodexHistoricalToolItemTypes = <String>{
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

const Set<String> _remoteCodexHistoricalRequestItemTypes = <String>{
  'requestUserInput',
  'requestApproval',
};

const Set<String> _remoteCodexHistoricalToolOutputItemTypes = <String>{
  'function_call_output',
  'custom_tool_call_output',
  'tool_search_output',
};

int _remoteCodexFindToolMessageIndexForCallId(
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
    if (_remoteCodexToolCardContainsCallId(cardData!, normalizedCallId)) {
      return index;
    }
  }
  return -1;
}

bool _remoteCodexToolCardContainsCallId(
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
      if (_remoteCodexValueContainsCallId(jsonDecode(text), callId)) {
        return true;
      }
    } catch (_) {
      continue;
    }
  }
  return false;
}

bool _remoteCodexValueContainsCallId(dynamic value, String callId) {
  if (value == null) {
    return false;
  }
  if (value is String || value is num || value is bool) {
    return value.toString() == callId;
  }
  final map = _asAgentMap(value);
  if (map != null) {
    final direct =
        _asAgentString(map['callId']) ??
        _asAgentString(map['call_id']) ??
        _asAgentString(map['id']);
    if (direct == callId) {
      return true;
    }
    return map.values.any(
      (nested) => _remoteCodexValueContainsCallId(nested, callId),
    );
  }
  if (value is List) {
    return value.any(
      (nested) => _remoteCodexValueContainsCallId(nested, callId),
    );
  }
  return false;
}

String _remoteCodexRawOutputText(Map<String, dynamic> item) {
  final output = item['output'];
  final text = _remoteCodexExtractText(
    output ?? item['tools'] ?? item['result'] ?? item['content'],
  );
  if (text.trim().isNotEmpty) {
    return text;
  }
  if (output != null) {
    return _safeAgentJson(output);
  }
  return '';
}

String _remoteCodexStableItemKey(Map<String, dynamic> item) {
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
  for (final codeUnit in _safeAgentJson(stablePayload).codeUnits) {
    hash ^= codeUnit;
    hash = (hash * 0x01000193) & 0xffffffff;
  }
  return 'raw-${hash.toRadixString(16).padLeft(8, '0')}';
}

String? _remoteCodexLastPathSegment(String path) {
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

List<String> _extractAgentOptionIds(
  Map<String, dynamic> response,
  List<String> listKeys,
) {
  final rawItems = _collectAgentListItems(response, listKeys);
  final seen = <String>{};
  final result = <String>[];
  for (final item in rawItems) {
    final id = _remoteCodexOptionId(item);
    if (id == null || !seen.add(id)) {
      continue;
    }
    result.add(id);
  }
  return result;
}

List<String> _mergeAgentOptionIds({
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

List<dynamic> _collectAgentListItems(
  Map<String, dynamic> response,
  List<String> listKeys, {
  bool allowUnkeyedFallback = true,
}) {
  final normalizedKeys = listKeys.map(_normalizeAgentResponseKey).toSet();
  final rawItems = <dynamic>[];

  void visitMap(Map<dynamic, dynamic> map) {
    for (final entry in map.entries) {
      final key = _normalizeAgentResponseKey(entry.key.toString());
      final value = entry.value;
      if (value is List) {
        if (normalizedKeys.contains(key)) {
          rawItems.addAll(value);
        }
        for (final item in value) {
          final nested = _asAgentMap(item);
          if (nested != null) {
            visitMap(nested);
          }
        }
      } else {
        final nested = _asAgentMap(value);
        if (nested != null) {
          visitMap(nested);
        }
      }
    }
  }

  visitMap(response);
  if (allowUnkeyedFallback && rawItems.isEmpty) {
    for (final value in response.values) {
      if (value is List) {
        rawItems.addAll(value);
      }
    }
  }
  return rawItems;
}

String _normalizeAgentResponseKey(String key) {
  return key.toLowerCase().replaceAll(RegExp(r'[_-]'), '');
}

String? _extractAgentPreferredOptionId(Map<String, dynamic> response) {
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
    final id = _remoteCodexOptionId(response[key]);
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
      final id = _remoteCodexOptionId(value);
      if (id != null) {
        return id;
      }
    }
  }
  return null;
}

String? _extractAgentDefaultModelId(Map<String, dynamic> response) {
  for (final item in _collectAgentListItems(
    response,
    _kAgentModelListResponseKeys,
    allowUnkeyedFallback: false,
  )) {
    final map = _asAgentMap(item);
    if (map == null) {
      continue;
    }
    final isDefault = map['isDefault'] == true || map['default'] == true;
    if (!isDefault) {
      continue;
    }
    final id = _remoteCodexOptionId(map);
    if (id != null) {
      return id;
    }
  }
  return null;
}

String? _extractAgentModelDefaultReasoningEffort(
  Map<String, dynamic> response,
  String? modelId,
) {
  final normalizedModelId = modelId?.trim();
  for (final item in _collectAgentListItems(
    response,
    _kAgentModelListResponseKeys,
    allowUnkeyedFallback: false,
  )) {
    final map = _asAgentMap(item);
    if (map == null) {
      continue;
    }
    if (normalizedModelId != null &&
        normalizedModelId.isNotEmpty &&
        !_agentModelItemMatches(map, normalizedModelId)) {
      continue;
    }
    final effort = _normalizeAgentReasoningEffort(
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

bool _agentModelItemMatches(
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

String? _extractAgentConfigModelId(Map<String, dynamic> response) {
  final direct = _remoteCodexOptionId(response['model'] ?? response['modelId']);
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
      final id = _remoteCodexOptionId(value['model'] ?? value['modelId']);
      if (id != null) {
        return id;
      }
      final nested = _extractAgentConfigModelId(
        value.map((key, nestedValue) => MapEntry(key.toString(), nestedValue)),
      );
      if (nested != null) {
        return nested;
      }
    }
  }
  return null;
}

String? _extractAgentConfigReasoningEffort(Map<String, dynamic> response) {
  final direct = _normalizeAgentReasoningEffort(
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
      final nested = _extractAgentConfigReasoningEffort(
        value.map((key, nestedValue) => MapEntry(key.toString(), nestedValue)),
      );
      if (nested != null) {
        return nested;
      }
    }
  }
  return null;
}

List<String> _mergeAgentReasoningEffortOptions({
  String? current,
  required List<String> options,
}) {
  final seen = <String>{};
  final result = <String>[];
  void add(String? value) {
    final normalized = _normalizeAgentReasoningEffort(value);
    if (normalized == null || !seen.add(normalized)) {
      return;
    }
    result.add(normalized);
  }

  add(current);
  for (final option in options) {
    add(option);
  }
  return result;
}

String? _normalizeAgentReasoningEffort(dynamic value) {
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

String? _remoteCodexOptionId(dynamic item) {
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

String _resolveAgentPlanMode(List<String> modes) {
  for (final mode in modes) {
    if (mode.toLowerCase() == 'plan') {
      return mode;
    }
  }
  for (final mode in modes) {
    if (_isAgentPlanMode(mode)) {
      return mode;
    }
  }
  return 'plan';
}

bool _isAgentPlanMode(String? mode) {
  final normalized = mode?.trim().toLowerCase() ?? '';
  return normalized == 'plan' || normalized.contains('plan');
}

class _AgentRunSettingsSnapshot {
  const _AgentRunSettingsSnapshot({this.modelId, this.reasoningEffort});

  final String? modelId;
  final String? reasoningEffort;
}

extension _AgentPermissionModePayload on AgentPermissionMode {
  String get approvalPolicy {
    return switch (this) {
      AgentPermissionMode.fullAccess => 'never',
      AgentPermissionMode.defaultMode ||
      AgentPermissionMode.autoReview => 'on-request',
    };
  }

  String get approvalsReviewer {
    return switch (this) {
      AgentPermissionMode.autoReview => 'auto_review',
      AgentPermissionMode.defaultMode ||
      AgentPermissionMode.fullAccess => 'user',
    };
  }

  Map<String, dynamic>? get sandboxPolicy {
    return switch (this) {
      AgentPermissionMode.fullAccess => const <String, dynamic>{
        'type': 'dangerFullAccess',
      },
      AgentPermissionMode.defaultMode || AgentPermissionMode.autoReview => null,
    };
  }
}
