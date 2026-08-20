part of 'chat_page.dart';

const String _kAgentModelPreferenceKey = 'model';
const String _kAgentReasoningEffortPreferenceKey = 'reasoning_effort';
const String _kAgentCollaborationModePreferenceKey = 'collaboration_mode';
const String _kAgentPreferenceStoragePrefix = 'chat_agent_command_preference';
const String _kLegacyAgentPreferenceStoragePrefix =
    'chat_codex_command_preference';
const String _kDeepSeekHarnessAgentId = 'deepseek-harness-acp';
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
  bool _usesSharedProviderModel(String? agentId) {
    final normalizedAgentId = agentId?.trim() ?? '';
    if (normalizedAgentId.isEmpty ||
        normalizedAgentId == _kRemoteCodexModeAgentId) {
      return false;
    }
    // Every local ACP Agent consumes the app's configured Provider catalog.
    // The Harness is an execution runtime, not a model authority. Keeping an
    // allow-list here caused newly installed Harnesses (and legacy DSH IDs)
    // to fall back to their own one-model catalog.
    return true;
  }

  Future<List<String>> _loadSharedProviderModelIds() async {
    var selection = _activeDispatchSceneSelection;
    if (selection == null) {
      try {
        final bindings = await SceneModelConfigService.getSceneModelBindings();
        final binding = bindings
            .where((item) => item.sceneId == 'scene.dispatch.model')
            .firstOrNull;
        if (binding != null) {
          selection = _ChatModelOverrideSelection(
            providerProfileId: binding.providerProfileId,
            modelId: binding.modelId,
          );
        }
      } catch (_) {
        // The normal chat context may still be loading.
      }
    }
    final resolvedSelection = selection;
    if (resolvedSelection == null) return const <String>[];
    var profile = _modelProviderProfiles
        .where((item) => item.id == resolvedSelection.providerProfileId)
        .firstOrNull;
    if (profile == null) {
      final payload = await ModelProviderConfigService.listProfiles();
      profile = payload.profiles
          .where((item) => item.id == resolvedSelection.providerProfileId)
          .firstOrNull;
    }
    if (profile == null || !profile.configured) {
      return const <String>[];
    }

    // The normal chat model context already owns the Provider catalog. Keep
    // this path cache-only: fetching /models here duplicated the normal chat
    // refresh and made entering Agent mode wait on the same Provider again.
    // The active Provider is refreshed by the shared chat model context.
    final providerOptions = <ProviderModelOption>[
      ...?_modelOptionsByProfileId[profile.id],
    ];
    final cachedOptions =
        await ModelProviderConfigService.getCachedFetchedModels(
          profileId: profile.id,
        );
    providerOptions.addAll(cachedOptions);
    final storedOptions =
        await ModelProviderConfigService.getStoredModelOptionsForProfile(
          profile.id,
          profile: profile,
          enrichMetadata: false,
        );
    providerOptions.addAll(storedOptions);
    return providerOptions
        .map((item) => item.id.trim())
        .where((item) => item.isNotEmpty)
        .toSet()
        .toList(growable: false);
  }

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
      unawaited(_loadAgentCatalog(force: true));
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
        unawaited(AgentRuntimeService.listSessions());
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
      conversationId: _modeState(ChatPageMode.agent).currentConversationId,
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
    // Agent selection may take several seconds while the ACP process starts.
    // A user-initiated switch owns the next conversation lifecycle request:
    // invalidate the bootstrap/old navigation request immediately, then use
    // this same id when applying the new target. Previously we only captured
    // the old id. Any still-running bootstrap could therefore make the switch
    // look stale after the ACP process had already started, leaving the page
    // on the old mode while the runtime had moved to the new Agent.
    final switchTargetRequestId = _beginConversationTargetRequest();
    final selectsRemote = normalized == _kRemoteCodexModeAgentId;
    if (!selectsRemote &&
        _usesSharedProviderModel(normalized) &&
        !await _ensureSharedProviderModelReadyForSwitch()) {
      return;
    }
    // A conversation binding describes which Agent owns the visible history;
    // it is not proof that the native ACP runtime is currently selected. On
    // restart the binding can still point at Xiaowan while the persisted ACP
    // profile is Codex (or another Agent). Always reconcile that mismatch
    // instead of treating the shortcut tap as a no-op.
    final runtimeActiveAgentId =
        _agentRuntimeStatus.activeAgentId?.trim() ?? '';
    final sameVisibleAgent =
        _activeMode == ChatPageMode.agent && normalized == _activeAcpAgentId;
    final sameRuntimeAgent =
        runtimeActiveAgentId.isEmpty || runtimeActiveAgentId == normalized;
    if (sameVisibleAgent && sameRuntimeAgent) {
      return;
    }
    final previousTarget = _threadTargetForMode;
    final target = _newAgentThreadTarget(
      agentId: normalized,
      agentRuntime: selectsRemote ? 'remote' : 'local',
      conversationId: _modeState(ChatPageMode.agent).currentConversationId,
    );
    setState(() {
      _optimisticAcpAgentId = normalized;
      _isAcpAgentSwitching = true;
    });
    var selected = false;
    try {
      selected = selectsRemote
          ? await _selectRemoteCodexRuntime()
          : await _selectAgent(normalized);
      // Select/connect the adapter before creating the new conversation. This
      // prevents the first prompt from being routed to the previous agent
      // while the ACP process is still switching.
      if (selected &&
          _isConversationTargetRequestCurrent(switchTargetRequestId)) {
        await _applyConversationThreadTarget(
          target,
          requestId: switchTargetRequestId,
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _optimisticAcpAgentId = null;
          _isAcpAgentSwitching = false;
        });
      }
    }
    if (!selected) {
      if (mounted &&
          _isConversationTargetRequestCurrent(switchTargetRequestId)) {
        await _applyConversationThreadTarget(previousTarget);
      }
      return;
    }
  }

  /// A local ACP adapter is only an execution harness. Its Provider and model
  /// come from the shared Agent scene binding. Check that binding before
  /// stopping the currently visible harness; otherwise a missing Provider
  /// model causes a needless process teardown followed by a rollback to the
  /// previous Agent, which looks like a broken mode switch to the user.
  Future<bool> _ensureSharedProviderModelReadyForSwitch() async {
    if (_activeDispatchSceneSelection != null) {
      return true;
    }

    try {
      final catalog = await SceneModelConfigService.getSceneCatalog();
      final dispatchScene = catalog
          .where((item) => item.sceneId == 'scene.dispatch.model')
          .firstOrNull;
      final providerId = dispatchScene?.effectiveProviderProfileId.trim() ?? '';
      final modelId = dispatchScene?.effectiveModel.trim() ?? '';
      if (providerId.isNotEmpty && modelId.isNotEmpty) {
        return true;
      }
    } catch (error) {
      debugPrint('[Agent] failed to resolve shared Provider model: $error');
    }

    if (mounted) {
      _showSnackBar(
        LegacyTextLocalizer.isEnglish
            ? 'Select a verified Provider model before switching Agent.'
            : '请先选择已验证的 Provider 模型，再切换 Agent。',
      );
      GoRouterManager.push('/home/agent_mode_setting');
    }
    return false;
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
    _modeState(ChatPageMode.agent).currentConversationId = runtimeId;

    try {
      AgentRuntimeStatus status = _agentRuntimeStatus;
      if (!status.connected) {
        status = await AgentRuntimeService.connect();
      }
      final response = await AgentRuntimeService.loadSession(
        sessionId: threadId,
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
    final conversationId = _modeState(ChatPageMode.agent).currentConversationId;
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
      _activeAgentModelId = null;
      _activeAgentReasoningEffort = _normalizeAgentReasoningEffort(effort);
      _activeAgentCollaborationMode = collaborationMode;
    });
    if (effort == null || _agentModelOptions.isEmpty) {
      unawaited(_loadAgentModelOptionsWhenReady());
    }
  }

  @override
  Future<void> _loadAgentModelOptionsWhenReady({bool force = false}) async {
    final currentSourceKey = agentModelSourceKey(_agentRuntimeStatus);
    final sharedProviderAgent =
        _usesSharedProviderModel(_activeAcpAgentId) ||
        _usesSharedProviderModel(_agentRuntimeStatus.activeAgentId);
    final hasResolvedEffort =
        _agentReasoningEffortOptions.isEmpty ||
        (_activeAgentReasoningEffort ?? '').trim().isNotEmpty;
    if (!force &&
        (sharedProviderAgent || _agentRuntimeStatus.connected) &&
        _loadedAgentModelSourceKey == currentSourceKey &&
        _agentModelOptions.isNotEmpty &&
        (!sharedProviderAgent || _agentModelOptions.length > 1) &&
        (_activeAgentModelId ?? '').trim().isNotEmpty &&
        hasResolvedEffort) {
      return;
    }
    if (sharedProviderAgent && !force && _agentModelOptions.length > 1) {
      return;
    }
    if (sharedProviderAgent && !force) {
      await _loadAgentModelOptions(force: true);
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
        unawaited(AgentRuntimeService.listSessions());
      }
      _applyRefreshedAgentRuntimeStatus(status);
    } catch (error) {
      debugPrint('Prepare Agent model options failed: $error');
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
      debugPrint('Load ACP agent catalog failed: $error');
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
      final sharedAgent = _usesSharedProviderModel(_activeAcpAgentId);
      final configSettings = sharedAgent
          ? await _readSharedAgentRunSettings()
          : await _readAgentRunSettingsFromServerConfig();
      final response = sharedAgent
          ? const <String, dynamic>{}
          : await AgentRuntimeService.listModelsForStatus(statusForRequest);
      final models = sharedAgent
          ? await _loadSharedProviderModelIds()
          : extractAcpModelIds(response);
      final sharedModel = _activeDispatchSceneSelection?.modelId.trim();
      final normalizedSharedModel = sharedModel?.toLowerCase();
      final modelConfigSupported = sharedAgent
          ? models.isNotEmpty
          : response['modelConfigSupported'] == true || models.isNotEmpty;
      if (models.isEmpty) {
        debugPrint(
          '[Agent] model catalog returned no parseable models: ${jsonEncode(response)}',
        );
      }
      final sharedPreferredModel =
          sharedModel != null &&
              models.any((item) => item.toLowerCase() == normalizedSharedModel)
          ? models.firstWhere(
              (item) => item.toLowerCase() == normalizedSharedModel,
            )
          : null;
      final reportedPreferredModel = sharedAgent
          ? sharedPreferredModel
          : configSettings.modelId ??
                _extractAgentPreferredOptionId(response) ??
                _extractAgentDefaultModelId(response);
      final preferredModel = sharedAgent
          ? reportedPreferredModel
          : models
                .where(
                  (item) =>
                      reportedPreferredModel != null &&
                      item.toLowerCase() ==
                          reportedPreferredModel.toLowerCase(),
                )
                .firstOrNull;
      final activeModel =
          (_loadedAgentModelSourceKey == sourceKey ? _activeAgentModelId : null)
              ?.trim() ??
          '';
      final effectiveModel =
          activeModel.isNotEmpty &&
              models.any(
                (item) => item.toLowerCase() == activeModel.toLowerCase(),
              )
          ? activeModel
          : preferredModel;
      final modelOptions = modelConfigSupported
          ? _mergeAgentOptionIds(
              current: effectiveModel,
              preferred: preferredModel,
              options: models,
            )
          : const <String>[];
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
        _agentModelConfigSupported = modelConfigSupported;
        _agentModelOptions = modelOptions;
        _activeAgentModelId = modelConfigSupported ? effectiveModel : null;
        if (configSettings.permissionMode != null) {
          _agentPermissionMode = configSettings.permissionMode!;
        }
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
        permissionMode: _extractAgentConfigPermissionMode(response),
      );
    } catch (error) {
      debugPrint('Read Agent config run settings failed: $error');
      return const _AgentRunSettingsSnapshot();
    }
  }

  Future<_AgentRunSettingsSnapshot> _readSharedAgentRunSettings() async {
    final agentId = _activeAcpAgentId?.trim() ?? '';
    if (agentId.isEmpty || agentId != _kDeepSeekHarnessAgentId) {
      return const _AgentRunSettingsSnapshot();
    }
    try {
      final response = await AgentRuntimeService.readAgentConfig(agentId);
      return _AgentRunSettingsSnapshot(
        modelId: _extractAgentConfigModelId(response),
        reasoningEffort: _extractAgentConfigReasoningEffort(response),
        permissionMode: _extractAgentConfigPermissionMode(response),
      );
    } catch (error) {
      debugPrint('Read shared Agent adapter settings failed: $error');
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
    if (!_agentModelConfigSupported &&
        _agentRuntimeStatus.runtime != 'remote' &&
        !_agentRuntimeStatus.remoteEnabled) {
      return;
    }
    final sharedSelection = _activeDispatchSceneSelection;
    final sharedAgent = _usesSharedProviderModel(
      (_agentRuntimeStatus.activeAgentId ?? _activeAcpAgentId)?.trim(),
    );
    var selectedModelId = normalized;
    try {
      final activeAgentId =
          (_agentRuntimeStatus.activeAgentId ?? _activeAcpAgentId)?.trim();
      if (sharedAgent) {
        if (sharedSelection == null) {
          throw StateError('Agent Provider / model has not been selected.');
        }
        selectedModelId = _agentModelOptions.firstWhere(
          (item) => item.toLowerCase() == normalized.toLowerCase(),
          orElse: () => throw StateError(
            'The selected model is not available in the configured Provider.',
          ),
        );
        await SceneModelConfigService.saveSceneModelBinding(
          sceneId: 'scene.dispatch.model',
          providerProfileId: sharedSelection.providerProfileId,
          modelId: selectedModelId,
        );
        await AgentRuntimeService.disconnect();
        unawaited(_loadNormalChatModelContext());
      } else if (activeAgentId == _kDeepSeekHarnessAgentId) {
        await AgentRuntimeService.writeAgentConfig(
          activeAgentId!,
          model: normalized,
        );
      } else {
        await _setAgentConfigOption(configId: 'model', value: normalized);
      }
    } catch (error) {
      if (mounted) {
        showToast(
          LegacyTextLocalizer.isEnglish
              ? 'Failed to change Agent model: $error'
              : '修改 Agent 模型失败：$error',
          type: ToastType.error,
        );
      }
      return;
    }
    if (!mounted) return;
    setState(() {
      _activeAgentModelId = selectedModelId;
    });
    if (!sharedAgent) {
      await _writeAgentPreference(_kAgentModelPreferenceKey, selectedModelId);
    }
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
        _agentModelConfigSupported = false;
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
        _agentModelConfigSupported = false;
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
    try {
      final activeAgentId =
          (_agentRuntimeStatus.activeAgentId ?? _activeAcpAgentId)?.trim();
      if (activeAgentId == _kDeepSeekHarnessAgentId) {
        await AgentRuntimeService.writeAgentConfig(
          activeAgentId!,
          reasoningEffort: normalized,
        );
      } else {
        await _setAgentConfigOption(
          configId: 'reasoning_effort',
          value: normalized,
        );
      }
    } catch (error) {
      if (mounted) {
        showToast(
          LegacyTextLocalizer.isEnglish
              ? 'Failed to change reasoning effort: $error'
              : '修改思考强度失败：$error',
          type: ToastType.error,
        );
      }
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
  Future<void> _selectAgentPermissionMode(AgentPermissionMode mode) async {
    final value = switch (mode) {
      AgentPermissionMode.readOnly => 'read-only',
      AgentPermissionMode.defaultMode ||
      AgentPermissionMode.autoReview => 'agent',
      AgentPermissionMode.fullAccess => 'agent-full-access',
    };
    try {
      final activeAgentId =
          (_agentRuntimeStatus.activeAgentId ?? _activeAcpAgentId)?.trim();
      if (activeAgentId == _kDeepSeekHarnessAgentId) {
        final deepSeekPermissionMode = switch (mode) {
          AgentPermissionMode.readOnly => 'read-only',
          AgentPermissionMode.defaultMode ||
          AgentPermissionMode.autoReview => 'workspace-write',
          AgentPermissionMode.fullAccess => 'danger-full-access',
        };
        await AgentRuntimeService.writeAgentConfig(
          activeAgentId!,
          permissionMode: deepSeekPermissionMode,
        );
      } else {
        await _setAgentConfigOption(configId: 'mode', value: value);
      }
    } catch (error) {
      if (mounted) {
        showToast(
          LegacyTextLocalizer.isEnglish
              ? 'Failed to change Agent permissions: $error'
              : '修改 Agent 权限模式失败：$error',
          type: ToastType.error,
        );
      }
      return;
    }
    if (!mounted) return;
    setState(() {
      _agentPermissionMode = mode;
    });
  }

  Future<void> _setAgentConfigOption({
    required String configId,
    required dynamic value,
  }) async {
    // Remote ACP keeps its own connection configuration path. Its turn
    // request still carries the selected legacy fields, while config/set is
    // reserved for local ACP sessions that expose ACP configOptions.
    if (_agentRuntimeStatus.runtime == 'remote' ||
        _agentRuntimeStatus.remoteEnabled) {
      return;
    }
    final threadId = _activeAgentThreadId?.trim();
    final conversationId = _modeState(ChatPageMode.agent).currentConversationId;
    // Before the first turn there is no durable session to mutate. The local
    // preference is applied once when startThread creates the ACP session.
    if ((threadId == null || threadId.isEmpty) && conversationId == null) {
      return;
    }
    final agentId = _activeAcpAgentId?.trim();
    if (agentId == null || agentId.isEmpty) {
      return;
    }
    await AgentRuntimeService.setSessionConfigOption(
      sessionId: threadId,
      agentId: agentId,
      configId: configId,
      value: value,
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
      } catch (error) {
        if (mounted) {
          _currentDispatchTurnId = messageIds.aiMessageId;
          handleAgentError('Conversation setup failed. Please retry. $error');
        }
        return;
      }
      conversationId = _currentConversationId;
      if (conversationId == null) {
        if (mounted) {
          _currentDispatchTurnId = messageIds.aiMessageId;
          handleAgentError('Conversation setup failed. Please retry.');
        }
        return;
      }
    }

    final resolvedConversationId = conversationId;
    _syncRuntimeSnapshotForMode(_activeMode);
    _currentDispatchTurnId = messageIds.aiMessageId;
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
      final response = await AgentRuntimeService.reviewSession(
        conversationId: remoteCodex ? null : resolvedConversationId,
        sessionId: _activeAgentThreadId,
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
      debugPrint('Read Agent command preference failed: $error');
      return null;
    }
  }

  Future<void> _writeAgentPreference(String kind, String value) async {
    final normalized = value.trim();
    if (normalized.isEmpty) {
      return;
    }
    await StorageService.setString(_agentPreferenceKey(kind), normalized);
    final conversationId = _modeState(ChatPageMode.agent).currentConversationId;
    if (conversationId != null) {
      await StorageService.setString(
        _agentPreferenceKey(kind, conversationId: conversationId),
        normalized,
      );
    }
  }

  Future<void> _clearAgentPreference(String kind) async {
    await StorageService.remove(_agentPreferenceKey(kind));
    final conversationId = _modeState(ChatPageMode.agent).currentConversationId;
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
    // ACP methods are reaching the Flutter side. If lines like
    //   [Agent/E] item/started:commandExecution
    //   [Agent/E] item/completed:commandExecution
    // do not show up while pwd/ls/cat run, the events are being dropped
    // upstream (remote ACP -> codex-bridge -> Kotlin -> EventChannel).
    debugPrint('[Agent/E] $diagnosticMethod');
    final acpUpdate = _asAgentMap(
      (_asAgentMap(event['params']) ?? const <String, dynamic>{})['update'],
    );
    if (acpUpdate?['sessionUpdate'] == 'config_option_update') {
      unawaited(_loadAgentModelOptions(force: true));
    }
    final totalEvents = _agentEventDiagnosticCounter.values.fold<int>(
      0,
      (sum, count) => sum + count,
    );
    if (totalEvents % 32 == 0 || diagnosticMethod == 'turn/completed') {
      debugPrint(
        '[Agent/E] === counters @$totalEvents === '
        '${_agentEventDiagnosticCounter.entries.map((e) => '${e.key}:${e.value}').join(', ')}',
      );
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
        _modeState(ChatPageMode.agent).currentConversationId;
    if (conversationId == null) {
      debugPrint(
        '[Agent] dropping $diagnosticMethod — no conversationId '
        '(remoteCodex=$remoteCodex, eventThreadId=$eventThreadId)',
      );
      return;
    }
    if (remoteCodex && eventThreadId != null && !shouldPromoteRemoteEvent) {
      _ensureRemoteCodexRuntimeForThread(eventThreadId);
    }
    final normalConversationId = _modeState(
      ChatPageMode.normal,
    ).currentConversationId;
    final agentConversationId = _modeState(
      ChatPageMode.agent,
    ).currentConversationId;
    final eventParams = _asAgentMap(event['params']);
    final eventSessionId =
        _asAgentString(event['sessionId']) ??
        _asAgentString(eventParams?['sessionId']);
    final eventTurnId = _asAgentString(event['turnId']);
    final ownerMode = _runtimeCoordinator.modeForAcpEvent(
      conversationId: conversationId,
      sessionId: eventSessionId,
      turnId: eventTurnId,
    );
    final eventMode = switch (ownerMode) {
      kChatRuntimeModeNormal => ChatPageMode.normal,
      kChatRuntimeModeAgent => ChatPageMode.agent,
      kChatRuntimeModeOpenClaw => ChatPageMode.openclaw,
      _ =>
        remoteCodex ||
                conversationId == agentConversationId ||
                event['conversationMode'] == ConversationMode.agent.storageValue
            ? ChatPageMode.agent
            : conversationId == normalConversationId
            ? ChatPageMode.normal
            : _activeMode,
    };
    final isVisibleConversation =
        conversationId == _modeState(eventMode).currentConversationId &&
        _activeMode == eventMode;
    final result = _runtimeCoordinator.applyAgentEvent(
      conversationId: conversationId,
      event: event,
      mode: _modeKey(eventMode),
      conversation: isVisibleConversation
          ? _modeState(eventMode).currentConversation
          : null,
    );
    final threadId = _asAgentString(event['threadId']) ?? result.threadId;
    final turnId = eventTurnId ?? result.turnId;
    if (eventMode == ChatPageMode.agent &&
        isVisibleConversation &&
        (threadId != null || turnId != null)) {
      _activeAgentThreadId = threadId ?? _activeAgentThreadId;
      _activeAgentTurnId = turnId ?? _activeAgentTurnId;
    }
    if (eventMode == ChatPageMode.agent && isVisibleConversation) {
      _syncAgentCollaborationModeFromServer(result.collaborationMode);
    }
    if (eventMode == ChatPageMode.agent &&
        isVisibleConversation &&
        result.method == 'turn/completed') {
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
    if (eventMode == ChatPageMode.agent && isVisibleConversation) {
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
    if (!result.handled &&
        result.method != 'codex/stderr' &&
        result.method != 'codex/parseError') {
      debugPrint('[Agent] unhandled ACP event: ${jsonEncode(event)}');
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
        _currentDispatchTurnId = aiMessageId;
        final runtime = _activeRuntime;
        if (runtime != null) {
          runtime.lastAgentTurnId = aiMessageId;
        }
      });
    }
    late AgentRuntimeStatus status;
    try {
      status = await _refreshConnectedAgentRuntimeStatus();
    } catch (error) {
      if (mounted) {
        _currentDispatchTurnId = aiMessageId;
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
      } catch (error) {
        if (mounted) {
          _currentDispatchTurnId = aiMessageId;
          handleAgentError('Conversation setup failed. Please retry. $error');
        }
        return;
      }
      conversationId = _currentConversationId;
      if (conversationId == null) {
        if (mounted) {
          _currentDispatchTurnId = aiMessageId;
          handleAgentError('Conversation setup failed. Please retry.');
        }
        return;
      }
    }

    final resolvedConversationId = conversationId;
    _syncRuntimeSnapshotForMode(_activeMode);
    _currentDispatchTurnId = aiMessageId;
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
      final response = await AgentRuntimeService.promptSession(
        conversationId: remoteCodex ? null : resolvedConversationId,
        sessionId: _activeAgentThreadId,
        // Keep the request id stable across a retry of this message. The ACP
        // runtime uses it to return the original turn instead of replaying
        // tool calls.
        requestId: aiMessageId,
        agentId: remoteCodex ? null : _activeAcpAgentId,
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
              _modeState(ChatPageMode.agent).currentConversationId) {
        if (_modeState(ChatPageMode.agent).currentConversationId == null) {
          _modeState(ChatPageMode.agent).currentConversationId =
              localConversationId;
          await _prepareConversationModeState(
            ChatPageMode.agent,
            ConversationThreadTarget.existing(
              conversationId: localConversationId,
              mode: ConversationMode.agent,
            ),
          );
        } else {
          debugPrint(
            '[Agent] keeping active conversation ${_modeState(ChatPageMode.agent).currentConversationId} '
            'instead of mismatched native conversation $localConversationId',
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
    final conversationId = _modeState(ChatPageMode.agent).currentConversationId;
    if (conversationId == null && _activeAgentThreadId == null) {
      return;
    }
    try {
      await AgentRuntimeService.cancelPrompt(
        conversationId: _isRemoteCodexConfigured() ? null : conversationId,
        sessionId: _activeAgentThreadId,
        promptId: _activeAgentTurnId,
      );
    } catch (error) {
      debugPrint('Agent interrupt failed: $error');
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
      debugPrint('Remote Agent session sync failed: $error');
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
      return await AgentRuntimeService.readSession(sessionId: threadId);
    } catch (error) {
      debugPrint('Agent thread/read failed, falling back to resume: $error');
      return AgentRuntimeService.loadSession(sessionId: threadId);
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
              runtime?.currentDispatchTurnId ??
              runtime?.lastAgentTurnId ??
              _activeAgentTurnId)
        : null;
    final activeTaskId = isAiResponding
        ? (activeTurnId ??
              runtime?.currentDispatchTurnId ??
              runtime?.lastAgentTurnId ??
              'remote-agent-$resolvedThreadId')
        : null;
    final hasTurns = _remoteCodexThreadResponseHasTurns(response);
    final existingMessages = List<ChatMessageModel>.from(
      resolveVisibleChatMessages(
        runtimeMessages: runtime?.messages,
        fallbackMessages: _modeState(ChatPageMode.agent).messages,
        preserveFallbackDuringHandoff: _modeState(
          ChatPageMode.agent,
        ).isAiResponding,
      ),
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
      _modeState(ChatPageMode.agent).currentConversationId = runtimeId;
      _modeState(ChatPageMode.agent).currentConversation = conversation;
      if (!preserveLiveStreamingState) {
        _modeState(ChatPageMode.agent).isAiResponding = isAiResponding;
        _modeState(ChatPageMode.agent).isExecutingTask = isAiResponding;
        _modeState(ChatPageMode.agent).isDeepThinking = isAiResponding;
        _modeState(ChatPageMode.agent).currentThinkingStage = isAiResponding
            ? ThinkingStage.thinking.value
            : ThinkingStage.complete.value;
        _modeState(ChatPageMode.agent).currentDispatchTurnId = activeTaskId;
      }
      _modeState(ChatPageMode.agent).messages
        ..clear()
        ..addAll(messages);
      _modeState(ChatPageMode.agent).hasMoreMessages = false;
      _modeState(ChatPageMode.agent).messageOffset = messages.length;
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
      currentDispatchTurnId: activeTaskId,
      currentThinkingStage: isAiResponding
          ? ThinkingStage.thinking.value
          : ThinkingStage.complete.value,
      lastAgentTurnId: activeTaskId,
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
    _modeState(ChatPageMode.agent).isAiResponding = runtime.isAiResponding;
    _modeState(ChatPageMode.agent).isContextCompressing =
        runtime.isContextCompressing;
    _modeState(ChatPageMode.agent).isCheckingExecutableTask =
        runtime.isCheckingExecutableTask;
    _modeState(ChatPageMode.agent).currentAiMessages
      ..clear()
      ..addAll(runtime.currentAiMessages);
    _modeState(ChatPageMode.agent).deepThinkingContent =
        runtime.deepThinkingContent;
    _modeState(ChatPageMode.agent).isDeepThinking = runtime.isDeepThinking;
    _modeState(ChatPageMode.agent).currentDispatchTurnId =
        runtime.currentDispatchTurnId;
    _modeState(ChatPageMode.agent).currentThinkingStage =
        runtime.currentThinkingStage;
    _modeState(ChatPageMode.agent).isInputAreaVisible =
        runtime.isInputAreaVisible;
    _modeState(ChatPageMode.agent).isExecutingTask = runtime.isExecutingTask;
    _modeState(ChatPageMode.agent).currentConversation = runtime.conversation;
    _modeState(ChatPageMode.agent).chatIslandDisplayLayer =
        runtime.chatIslandDisplayLayer;
    _modeState(ChatPageMode.agent).lastAgentToolType =
        runtime.lastAgentToolType;
    _modeState(ChatPageMode.agent).browserSessionSnapshot =
        runtime.browserSessionSnapshot;
  }

  bool _isRemoteCodexConfigured() {
    final runtime = _agentRuntimeStatus.runtime?.trim();
    return runtime == 'remote' || _agentRuntimeStatus.remoteEnabled;
  }

  int _ensureRemoteCodexRuntimeForCurrentMessages() {
    final currentId = _modeState(ChatPageMode.agent).currentConversationId;
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
    _modeState(ChatPageMode.agent).currentConversationId = runtimeId;
    _modeState(ChatPageMode.agent).currentConversation ??= ConversationModel(
      id: runtimeId,
      mode: ConversationMode.agent,
      title: 'Agent',
      status: 0,
      lastMessage: _modeState(ChatPageMode.agent).messages.isNotEmpty
          ? _modeState(ChatPageMode.agent).messages.first.text
          : null,
      messageCount: _modeState(ChatPageMode.agent).messages.length,
      createdAt: DateTime.now().millisecondsSinceEpoch,
      updatedAt: DateTime.now().millisecondsSinceEpoch,
    );
    _runtimeCoordinator.ensureEphemeralRuntime(
      conversationId: runtimeId,
      mode: kChatRuntimeModeAgent,
      initialMessages: List<ChatMessageModel>.from(
        _modeState(ChatPageMode.agent).messages,
      ),
      conversation: _modeState(ChatPageMode.agent).currentConversation,
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
      final visibleMessages = _modeState(ChatPageMode.agent).messages;
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
      final currentConversation = _modeState(
        ChatPageMode.agent,
      ).currentConversation;
      if (currentConversation != null) {
        runtime.conversation = currentConversation.copyWith(id: runtimeId);
      }
      _modeState(ChatPageMode.agent).currentConversation = runtime.conversation;
    }
    _activeRemoteCodexRuntimeId = runtimeId;
    _activeAgentThreadId = normalizedThreadId;
    _modeState(ChatPageMode.agent).currentConversationId = runtimeId;
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
    final currentConversationId = _modeState(
      ChatPageMode.agent,
    ).currentConversationId;
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
    return _modeState(ChatPageMode.agent).messages.isNotEmpty ||
        (runtime?.hasInFlightTask ?? false) ||
        (_modeState(ChatPageMode.agent).currentDispatchTurnId?.isNotEmpty ??
            false);
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
      debugPrint('Read Agent account failed: $error');
    }
  }

  Future<void> _startRemoteCodexLogin() async {
    try {
      final response = await AgentRuntimeService.startLogin();
      final authUrl = _asAgentString(response['authUrl']);
      if (authUrl == null) return;
      await launchUrlString(authUrl, mode: LaunchMode.externalApplication);
    } catch (error) {
      debugPrint('Start remote Agent login failed: $error');
    }
  }

  Future<AgentRuntimeStatus> _refreshConnectedAgentRuntimeStatus() async {
    var status = await AgentRuntimeService.status();
    if (!status.connected) {
      status = await AgentRuntimeService.connect();
      unawaited(AgentRuntimeService.listSessions());
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
        _agentModelConfigSupported = false;
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
    return selectAgentRequestModel(
      status: status,
      overrideModel: overrideModel,
      activeModel: _activeAgentModelId,
      activeModelSourceMatches: _loadedAgentModelSourceKey == sourceKey,
    );
  }
}
