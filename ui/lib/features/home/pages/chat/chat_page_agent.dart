part of 'chat_page.dart';

const String _kAgentModelPreferenceKey = 'model';
const String _kAgentReasoningEffortPreferenceKey = 'reasoning_effort';
const String _kAgentCollaborationModePreferenceKey = 'collaboration_mode';
const String _kAgentPermissionModePreferenceKey = 'permission_mode';
const String _kAgentPreferenceStoragePrefix = 'chat_agent_command_preference';
const String _kLegacyAgentPreferenceStoragePrefix =
    'chat_codex_command_preference';
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
  Map<String, dynamic>? _availableAcpCommandForText(String text) {
    final trimmed = text.trim();
    if (!trimmed.startsWith('/')) return null;
    final name = trimmed.substring(1).split(RegExp(r'\s+')).first.toLowerCase();
    if (name.isEmpty) return null;
    final runtime = _runtimeForMode(ChatPageMode.agent);
    for (final command
        in runtime?.availableAcpCommands ?? const <Map<String, dynamic>>[]) {
      final candidate = (command['name'] ?? '').toString().trim();
      if (candidate.replaceFirst(RegExp(r'^/'), '').toLowerCase() == name) {
        return command;
      }
    }
    return null;
  }

  bool _usesSharedProviderModel(String? agentId) {
    final normalizedAgentId = agentId?.trim() ?? '';
    if (normalizedAgentId.isEmpty ||
        normalizedAgentId == _kRemoteCodexModeAgentId) {
      return false;
    }
    // Every local ACP Agent consumes the app's configured Provider catalog.
    // The Harness is an execution runtime, not a model authority. An allow-list
    // here caused newly installed Harnesses (and legacy IDs)
    // to fall back to their own one-model catalog.
    return true;
  }

  Future<List<String>> _loadSharedProviderModelIds() async {
    var selection = _activeDispatchSceneSelection;
    var profilesPayload = await ModelProviderConfigService.listProfiles();
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
      } catch (_) {}
    }
    var resolvedSelection = selection;
    var profile = resolvedSelection == null
        ? null
        : _modelProviderProfiles
              .where((item) => item.id == resolvedSelection!.providerProfileId)
              .firstOrNull;
    profile ??= resolvedSelection == null
        ? null
        : profilesPayload.profiles
              .where((item) => item.id == resolvedSelection!.providerProfileId)
              .firstOrNull;
    if (profile == null) {
      // Older builds let normal-chat model selection live only in Flutter
      // state. Agent/Harness startup now has one durable binding, so migrate
      // that state on first Agent entry: use the configured editing Provider
      // and its first verified/cached model, then persist the canonical
      // scene.dispatch.model binding before ACP connect.
      profile = profilesPayload.profiles
          .where((item) => item.id == profilesPayload.editingProfileId)
          .firstOrNull;
      profile ??= profilesPayload.profiles
          .where((item) => item.configured)
          .firstOrNull;
    }
    if (profile == null || !profile.configured) {
      return const <String>[];
    }

    // The Provider settings surface owns catalog discovery. Keep Agent entry
    // cache-only: fetching /models here duplicated configuration work and
    // made entering Agent mode wait on the Provider again.
    final providerOptions = <ProviderModelOption>[
      ...?_modelOptionsByProfileId[profile.id],
    ];
    final cachedOptions =
        await ModelProviderConfigService.getCachedFetchedModels(
          profileId: profile.id,
          apiBase: profile.baseUrl,
          profileRevision: profile.revision,
        );
    providerOptions.addAll(cachedOptions);
    final storedOptions =
        await ModelProviderConfigService.getStoredModelOptionsForProfile(
          profile.id,
          profile: profile,
          enrichMetadata: false,
        );
    providerOptions.addAll(storedOptions);
    var modelIds = providerOptions
        .map((item) => item.id.trim())
        .where((item) => item.isNotEmpty)
        .toSet()
        .toList(growable: false);
    final boundModelId = resolvedSelection?.modelId.trim() ?? '';
    if (modelIds.isEmpty && boundModelId.isNotEmpty) {
      // A durable ACP binding is usable even when the catalog document is
      // cold. Keep the selected model visible immediately.
      modelIds = <String>[boundModelId];
    }
    return modelIds;
  }

  @override
  Future<void> _refreshAgentRuntimeStatus() async {
    if (!mounted || _isAgentRuntimeStatusLoading) return;
    final requestEpoch = _agentRuntimeStatusEpoch;
    setState(() {
      _isAgentRuntimeStatusLoading = true;
    });
    try {
      final status = await AgentRuntimeService.status();
      if (!mounted) return;
      if (requestEpoch != _agentRuntimeStatusEpoch) {
        // A Harness switch invalidates an older status request. Do not let
        // that request leave the AppBar's loading flag latched forever: the
        // switch has its own loading state and will keep the spinner visible
        // until its commit completes.
        if (_isAgentRuntimeStatusLoading) {
          setState(() {
            _isAgentRuntimeStatusLoading = false;
          });
        }
        return;
      }
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
      if (requestEpoch != _agentRuntimeStatusEpoch) {
        if (_isAgentRuntimeStatusLoading) {
          setState(() {
            _isAgentRuntimeStatusLoading = false;
          });
        }
        return;
      }
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
            ? (status.error?.trim().isNotEmpty == true
                  ? status.error!.trim()
                  : 'The selected ACP Agent is unavailable')
            : (status.error?.trim().isNotEmpty == true
                  ? status.error!.trim()
                  : '所选 ACP Agent 当前不可用'),
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
    if (normalized.isEmpty) {
      return;
    }
    final selectsRemote = normalized == _kRemoteCodexModeAgentId;
    final observedTargetRequestId = _conversationTargetRequestId;
    final switchGeneration = _harnessSwitchSendBarrier.begin();
    // Invalidate every status/catalog refresh already in flight. Only the
    // ACP response produced by this switch may become the next global
    // runtime snapshot.
    _agentRuntimeStatusEpoch++;
    _agentCatalogEpoch++;
    setState(() {
      _optimisticAcpAgentId = normalized;
      _isAcpAgentSwitching = true;
    });
    try {
      if (!selectsRemote &&
          _usesSharedProviderModel(normalized) &&
          !await _ensureSharedProviderModelReadyForSwitch()) {
        return;
      }
      if (!mounted ||
          !_harnessSwitchSendBarrier.isCurrent(switchGeneration) ||
          observedTargetRequestId != _conversationTargetRequestId) {
        return;
      }

      // A conversation binding is not proof that the native ACP runtime is
      // selected. On restart the binding can still point at Xiaowan while the
      // persisted ACP profile is another Harness.
      final runtimeActiveAgentId =
          _agentRuntimeStatus.activeAgentId?.trim() ?? '';
      final sameVisibleAgent =
          _activeMode == ChatPageMode.agent && normalized == _activeAcpAgentId;
      final sameRuntimeAgent = selectsRemote
          ? _agentRuntimeStatus.connected &&
                (_agentRuntimeStatus.runtime == 'remote' ||
                    _agentRuntimeStatus.remoteEnabled)
          : _agentRuntimeStatus.connected && runtimeActiveAgentId == normalized;
      if (sameVisibleAgent && sameRuntimeAgent) {
        return;
      }

      await _harnessSwitchSendBarrier.runIfCurrent(switchGeneration, () async {
        final previousTarget = _threadTargetForMode;
        final target = buildHarnessSwitchTarget(
          agentId: normalized,
          agentRuntime: selectsRemote ? 'remote' : 'local',
          requestKey: DateTime.now().microsecondsSinceEpoch.toString(),
        );
        // Only a real switch invalidates bootstrap/navigation work. A no-op
        // tap or failed Provider preflight must not strand the page.
        final switchTargetRequestId = _beginConversationTargetRequest();
        final selected = selectsRemote
            ? await _selectRemoteCodexRuntime()
            : await _selectAgent(normalized);
        // A newer tap may have arrived while the native selection was in
        // flight. Never let this older result install a visible target; the
        // serialized latest request will reconcile the native runtime next.
        if (!_harnessSwitchSendBarrier.isCurrent(switchGeneration) ||
            !_isConversationTargetRequestCurrent(switchTargetRequestId)) {
          return;
        }
        if (selected) {
          await _applyConversationThreadTarget(
            target,
            requestId: switchTargetRequestId,
          );
        } else {
          await _applyConversationThreadTarget(previousTarget);
        }
      });
    } finally {
      // Also invalidate refreshes started during the switch. They can carry
      // a pre-handshake/disconnected snapshot and must not overwrite the
      // committed result after the loading state is cleared.
      _agentRuntimeStatusEpoch++;
      _agentCatalogEpoch++;
      if (mounted && _harnessSwitchSendBarrier.isCurrent(switchGeneration)) {
        setState(() {
          _optimisticAcpAgentId = null;
          _isAcpAgentSwitching = false;
        });
      }
      _harnessSwitchSendBarrier.finish(switchGeneration);
    }
  }

  /// A local ACP adapter is only an execution harness. Its Provider and model
  /// come from the shared Agent scene binding. Check that binding before
  /// stopping the currently visible harness; otherwise a missing Provider
  /// model causes a needless process teardown followed by a rollback to the
  /// previous Agent, which looks like a broken mode switch to the user.
  Future<bool> _ensureSharedProviderModelReadyForSwitch() async {
    // A connected ACP runtime already passed this exact Provider/model
    // validation during its last launch. Re-reading three settings channels
    // on every selector tap only adds latency (and can briefly block the
    // popup while another Harness is starting). The native ACP boundary still
    // validates the binding when it prepares a genuinely new process.
    if (_agentRuntimeStatus.ready && _agentRuntimeStatus.connected) {
      return true;
    }
    try {
      final results = await Future.wait<dynamic>([
        SceneModelConfigService.getSceneCatalog(),
        SceneModelConfigService.getSceneModelBindings(),
        ModelProviderConfigService.listProfiles(),
      ]);
      final catalog = results[0] as List<SceneCatalogItem>;
      final bindings = results[1] as List<SceneModelBindingEntry>;
      final profiles = results[2] as ModelProviderProfilesPayload;
      final dispatchScene = catalog
          .where((item) => item.sceneId == 'scene.dispatch.model')
          .firstOrNull;
      final persistedBinding = bindings
          .where((item) => item.sceneId == 'scene.dispatch.model')
          .firstOrNull;
      final selection = resolveSharedAgentProviderSelection(
        effectiveProviderProfileId: dispatchScene?.effectiveProviderProfileId,
        effectiveModel: dispatchScene?.effectiveModel,
        boundProviderProfileId:
            persistedBinding?.providerProfileId ??
            dispatchScene?.boundProviderProfileId,
        boundModel: persistedBinding?.modelId ?? dispatchScene?.overrideModel,
      );
      final configuredProviderIds = profiles.profiles
          .where((profile) => profile.configured)
          .map((profile) => profile.id)
          .toSet();
      if (isSharedAgentProviderSelectionReady(
        selection: selection,
        configuredProviderIds: configuredProviderIds,
      )) {
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
    final targetRequestId = _conversationTargetRequestId;
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
        conversationMode: ConversationMode.agent.storageValue,
      );
      if (!_isConversationTargetRequestCurrent(targetRequestId)) return;
      final resolvedThreadId =
          _asAgentString(response['threadId']) ??
          _asAgentString(_asAgentMap(response['thread'])?['id']) ??
          threadId;
      final conversation = _remoteCodexConversationFromResponse(
        runtimeId: runtimeId,
        response: response,
      );
      this._applyRemoteCodexThreadSnapshot(
        response: response,
        fallbackThreadId: resolvedThreadId,
        fallbackRuntimeId: runtimeId,
        fallbackConversation: conversation,
        status: status,
      );
      this._startRemoteCodexSessionSync(resolvedThreadId);
      _rememberRuntimeUiSnapshot(ChatPageMode.agent);
    } catch (error) {
      if (!_isConversationTargetRequestCurrent(targetRequestId)) return;
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
    final permissionMode = _parseAgentPermissionMode(
      _readAgentPreference(
        _kAgentPermissionModePreferenceKey,
        conversationId: conversationId,
      ),
    );
    if (!mounted) return;
    setState(() {
      _activeAgentModelId = null;
      _activeAgentReasoningEffort = _normalizeAgentReasoningEffort(effort);
      _activeAgentCollaborationMode = collaborationMode;
      // Permission is an app-owned ACP policy. A Harness may report its own
      // default mode (usually `agent`/on-request), but that must not silently
      // replace the app's canonical default or a per-conversation choice.
      // New conversations start in Full access, matching the selector's
      // initial value; an explicit stored choice remains authoritative.
      _agentPermissionMode = permissionMode ?? AgentPermissionMode.fullAccess;
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
    final requestEpoch = _agentCatalogEpoch;
    setState(() {
      _isAgentCatalogLoading = true;
    });
    try {
      final catalog = await AgentRuntimeService.listAgents();
      if (!mounted || requestEpoch != _agentCatalogEpoch) return;
      setState(() {
        _agentCatalog = catalog;
      });
    } catch (error) {
      debugPrint('Load ACP agent catalog failed: $error');
    } finally {
      // A forced catalog refresh can overlap a previous request when the
      // user switches Harness quickly. The older request must not clear the
      // loading state owned by the newer request.
      if (mounted && requestEpoch == _agentCatalogEpoch) {
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
      // Every local ACP Agent exposes the same session/config boundary. Do
      // not branch on a vendor or Harness id here: the visible model,
      // reasoning and permission cards must follow the active ACP session.
      // Shared-provider ACP Agents take model/reasoning identity from the
      // canonical Dispatch binding. Reading the Harness config here is a
      // terminal IPC round-trip and cannot change that result; doing it
      // before the shared-provider branch made the model card wait for the
      // same slow startup path that it was meant to describe.
      final configSettings = sharedAgent
          ? const _AgentRunSettingsSnapshot()
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
      final storedPermissionMode = _parseAgentPermissionMode(
        _readAgentPreference(
          _kAgentPermissionModePreferenceKey,
          conversationId: _modeState(ChatPageMode.agent).currentConversationId,
        ),
      );
      final effortOptions = _mergeAgentReasoningEffortOptions(
        current: serverEffort,
        options: extractAcpReasoningEffortIds(response),
      );
      final effortConfigId =
          extractAcpReasoningEffortConfigId(response) ?? 'reasoning_effort';
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
        // A user-selected local preference is authoritative for the next
        // turn. Some Harnesses expose a read-only/stale mode in config/read;
        // allowing it to overwrite the selection makes the picker appear
        // broken immediately after it is changed.
        if (storedPermissionMode != null) {
          _agentPermissionMode = storedPermissionMode;
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
        _agentReasoningEffortConfigId = effortConfigId;
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
    CodexRemoteBridgeConfig? previousRemoteConfig;
    try {
      if (wasRemote) {
        final remote = await AgentRuntimeService.readRemoteBridgeConfig();
        previousRemoteConfig = remote;
        await AgentRuntimeService.writeRemoteBridgeConfig(
          remoteEnabled: false,
          remoteBridgeUrl: remote.remoteBridgeUrl,
          remoteBridgeToken: remote.remoteBridgeToken,
          remoteCwd: remote.remoteCwd,
        );
      }
      final catalog = await AgentRuntimeService.selectAgent(normalized);
      // Native agent/select initializes the ACP process before returning and
      // includes that live status in the same response. Reuse it so a normal
      // switch does not pay an extra status probe/connect IPC round-trip.
      // Keep compatibility with an older native build during hot reload or
      // an in-place APK update that has not restarted the Flutter engine.
      var status = catalog.runtimeStatus ?? await AgentRuntimeService.status();
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
        _agentReasoningEffortConfigId = null;
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
      final remote = previousRemoteConfig;
      if (wasRemote && remote != null && remote.remoteEnabled) {
        try {
          await AgentRuntimeService.writeRemoteBridgeConfig(
            remoteEnabled: true,
            remoteBridgeUrl: remote.remoteBridgeUrl,
            remoteBridgeToken: remote.remoteBridgeToken,
            remoteCwd: remote.remoteCwd,
          );
        } catch (restoreError) {
          debugPrint('Failed to restore remote Agent config: $restoreError');
        }
      }
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
        _agentReasoningEffortConfigId = null;
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
      await _setAgentConfigOption(
        configId: _agentReasoningEffortConfigId ?? 'reasoning_effort',
        value: normalized,
      );
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
    if (!mounted) return;
    setState(() {
      _agentPermissionMode = mode;
    });
    // The canonical prompt carries approvalPolicy/sandboxPolicy on every
    // turn, so the local selection remains effective even when a Harness
    // does not expose a mutable ACP `mode` config option. Persist it before
    // attempting the optional in-session mutation so a new session also
    // starts with the selected mode.
    await _writeAgentPreference(
      _kAgentPermissionModePreferenceKey,
      _agentPermissionModePreferenceValue(mode),
    );
    try {
      await _setAgentConfigOption(configId: 'mode', value: value);
    } catch (error) {
      // A running turn or a Harness without `mode` is not a failed user
      // selection: the next canonical `session/prompt` applies the policy.
      debugPrint('ACP permission mode will apply on the next turn: $error');
    }
  }

  Future<void> _setAgentConfigOption({
    required String configId,
    required dynamic value,
  }) async {
    // Local and remote Agents expose the same official ACP method. The
    // runtime owns transport selection; this UI boundary must not skip the
    // remote path or a changed effort would only update local preferences.
    final threadId = _activeAgentThreadId?.trim();
    final conversationId = _modeState(ChatPageMode.agent).currentConversationId;
    // Before the first turn there is no durable ACP session to mutate. The
    // preference is applied once when session/new creates that session.
    if (threadId == null || threadId.isEmpty) {
      return;
    }
    final agentId = _activeAcpAgentId?.trim();
    if (agentId == null || agentId.isEmpty) {
      return;
    }
    await AgentRuntimeService.setSessionConfigOption(
      sessionId: threadId,
      conversationId: conversationId,
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
    if (cardData['acpCommand'] == true) {
      final value = command.endsWith(' ') ? command : '$command ';
      _messageController.value = TextEditingValue(
        text: value,
        selection: TextSelection.collapsed(offset: value.length),
      );
      _requestComposerFocus();
      _handleSlashCommandInput();
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
    final advertisedCommand = _availableAcpCommandForText(trimmed);
    if (advertisedCommand != null) {
      _messageController.clear();
      _hideSlashCommandPanel();
      await _startAgentTurnCommand(
        displayText: trimmed,
        actualText: trimmed,
        attachments: attachments,
      );
      return true;
    }
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
    final preflightConversationId = _currentConversationId;
    if (preflightConversationId != null) {
      _runtimeCoordinator.beginAcpTurn(
        taskId: messageIds.aiMessageId,
        conversationId: preflightConversationId,
        mode: _modeKey(_activeMode),
      );
    }
    void releasePreflightReservation() {
      if (preflightConversationId == null) return;
      _runtimeCoordinator.unregisterTask(
        messageIds.aiMessageId,
        conversationId: preflightConversationId,
        mode: _modeKey(_activeMode),
      );
    }

    final remoteCodex = agentModelSourceKey(status) == 'remote';
    int? conversationId = preflightConversationId;
    if (remoteCodex) {
      conversationId = this._ensureRemoteCodexRuntimeForCurrentMessages();
    } else if (conversationId == null) {
      try {
        await _ensureActiveConversationReadyForStreaming();
      } catch (error) {
        if (mounted) {
          handleAgentError(
            'Conversation setup failed. Please retry. $error',
            taskIdOverride: messageIds.aiMessageId,
          );
        }
        releasePreflightReservation();
        return;
      }
      conversationId = _currentConversationId;
      if (conversationId == null) {
        if (mounted) {
          handleAgentError(
            'Conversation setup failed. Please retry.',
            taskIdOverride: messageIds.aiMessageId,
          );
        }
        releasePreflightReservation();
        return;
      }
    }

    final resolvedConversationId = conversationId;
    try {
      // Begin before replacing the page projection. The coordinator's
      // admission is the identity boundary that makes a snapshot a live-turn
      // merge instead of an idle restore.
      _runtimeCoordinator.beginAcpTurn(
        taskId: messageIds.aiMessageId,
        conversationId: resolvedConversationId,
        mode: _modeKey(_activeMode),
      );
      _syncRuntimeSnapshotForMode(_activeMode);
      if (!remoteCodex) {
        await _runtimeCoordinator.persistRuntimeConversation(
          conversationId: resolvedConversationId,
          mode: _modeKey(_activeMode),
          persistMessages: true,
        );
      }
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
      if (mounted) {
        handleAgentError('Agent review 启动失败: $error');
      }
      _runtimeCoordinator.unregisterTask(
        messageIds.aiMessageId,
        conversationId: resolvedConversationId,
        mode: _modeKey(_activeMode),
      );
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
      userMessageId: messageIds.userMessageId,
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
    await _writeAgentPreference(
      _kAgentPermissionModePreferenceKey,
      _agentPermissionModePreferenceValue(_agentPermissionMode),
    );
    final collaborationMode = _activeAgentCollaborationMode?.trim();
    if (collaborationMode != null && collaborationMode.isNotEmpty) {
      await _writeAgentPreference(
        _kAgentCollaborationModePreferenceKey,
        collaborationMode,
      );
    }
  }

  String _agentPermissionModePreferenceValue(AgentPermissionMode mode) {
    return switch (mode) {
      AgentPermissionMode.readOnly => 'read-only',
      AgentPermissionMode.defaultMode => 'workspace-write',
      AgentPermissionMode.autoReview => 'auto-review',
      AgentPermissionMode.fullAccess => 'full-access',
    };
  }

  AgentPermissionMode? _parseAgentPermissionMode(String? raw) {
    switch (raw?.trim().toLowerCase().replaceAll('_', '-')) {
      case 'read-only':
      case 'readonly':
        return AgentPermissionMode.readOnly;
      case 'workspace-write':
      case 'workspacewrite':
      case 'agent':
      case 'default':
        return AgentPermissionMode.defaultMode;
      case 'auto-review':
      case 'autoreview':
        return AgentPermissionMode.autoReview;
      case 'full-access':
      case 'fullaccess':
      case 'agent-full-access':
        return AgentPermissionMode.fullAccess;
      default:
        return null;
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
    final eventSessionId = acpEventSessionId(event);
    final eventTurnId = acpEventTurnId(event);
    final eventParams = _asAgentMap(event['params']);
    final hasStandaloneProcessIdentity = <dynamic>[
      event['processId'],
      event['process_id'],
      event['processHandle'],
      event['process_handle'],
      eventParams?['processId'],
      eventParams?['process_id'],
      eventParams?['processHandle'],
      eventParams?['process_handle'],
    ].any((value) => value?.toString().trim().isNotEmpty == true);
    String? standaloneProcessId;
    for (final value in <dynamic>[
      event['processId'],
      event['process_id'],
      event['processHandle'],
      event['process_handle'],
      eventParams?['processId'],
      eventParams?['process_id'],
      eventParams?['processHandle'],
      eventParams?['process_handle'],
    ]) {
      final normalized = value?.toString().trim() ?? '';
      if (normalized.isNotEmpty) {
        standaloneProcessId = normalized;
        break;
      }
    }
    final standaloneProcessOwner = standaloneProcessId == null
        ? null
        : _runtimeCoordinator.conversationIdForStandaloneProcess(
            standaloneProcessId!,
          );
    final hasProtocolIdentity =
        eventSessionId != null || eventTurnId != null || eventThreadId != null;
    final canUseVisibleFallback =
        diagnosticMethod == 'error' || hasStandaloneProcessIdentity;
    final identityConversationId = explicitConversationId == null
        ? _runtimeCoordinator.conversationIdForAcpEvent(
            sessionId: eventSessionId,
            turnId: eventTurnId,
          )
        : null;
    final mappedRemoteConversationId = remoteCodex && eventThreadId != null
        ? _remoteCodexRuntimeId(eventThreadId)
        : null;
    final shouldPromoteRemoteEvent =
        remoteCodex &&
        eventThreadId != null &&
        this._shouldPromoteRemoteCodexEventToVisibleThread(
          threadId: eventThreadId,
          runtimeId: mappedRemoteConversationId!,
        );
    final conversationId =
        explicitConversationId ??
        (shouldPromoteRemoteEvent
            ? _activateRemoteCodexRuntimeForThread(eventThreadId)
            : mappedRemoteConversationId) ??
        identityConversationId ??
        standaloneProcessOwner ??
        (!hasProtocolIdentity && canUseVisibleFallback
            ? _modeState(ChatPageMode.agent).currentConversationId
            : null);
    if (conversationId == null) {
      debugPrint(
        '[Agent] dropping $diagnosticMethod — no safe ACP owner '
        '(remoteCodex=$remoteCodex, eventSessionId=$eventSessionId, '
        'eventTurnId=$eventTurnId, eventThreadId=$eventThreadId)',
      );
      return;
    }
    if (remoteCodex && eventThreadId != null && !shouldPromoteRemoteEvent) {
      this._ensureRemoteCodexRuntimeForThread(eventThreadId);
    }
    final normalConversationId = _modeState(
      ChatPageMode.normal,
    ).currentConversationId;
    final agentConversationId = _modeState(
      ChatPageMode.agent,
    ).currentConversationId;
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
    if (result.compatibilityWarning != null && isVisibleConversation) {
      showToast(result.compatibilityWarning!, type: ToastType.warning);
    }
    final threadId = _asAgentString(event['threadId']) ?? result.threadId;
    final turnId = eventTurnId ?? result.turnId;
    if (eventMode == ChatPageMode.agent &&
        isVisibleConversation &&
        result.handled &&
        result.affectsActiveTurn &&
        (threadId != null || turnId != null)) {
      _activeAgentThreadId = threadId ?? _activeAgentThreadId;
      _activeAgentTurnId = turnId ?? _activeAgentTurnId;
    }
    if (eventMode == ChatPageMode.agent && isVisibleConversation) {
      _syncAgentCollaborationModeFromServer(result.collaborationMode);
    }
    if (eventMode == ChatPageMode.agent && isVisibleConversation) {
      final runtime = _runtimeCoordinator.runtimeFor(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );
      if (runtime != null) {
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
    required String userMessageId,
    List<Map<String, dynamic>> attachments = const [],
    String? modelOverride,
    String? collaborationModeOverride,
  }) async {
    // Freeze the dispatch target before the first await. The page is allowed
    // to switch conversations while status/config/session calls are in
    // flight, but that must not retarget this prompt to the newly visible
    // ACP. The coordinator owns the old runtime; this request owns this
    // immutable binding.
    final dispatchTargetGeneration = _conversationTargetRequestId;
    final dispatchTarget = _resolvedThreadTarget;
    final dispatchMode = _activeMode;
    final dispatchModeKey = _modeKey(dispatchMode);
    final dispatchConversationId =
        _currentConversationId ?? dispatchTarget?.conversationId;
    final dispatchAgentId =
        (dispatchTarget?.agentId ?? _activeAcpAgentId ?? _kXiaowanAcpAgentId)
            .trim();
    final dispatchSessionId = _activeAgentThreadId?.trim();
    final dispatchPermissionMode = _agentPermissionMode;
    final dispatchReasoningEffort = _activeAgentReasoningEffort;
    final dispatchCollaborationMode =
        collaborationModeOverride ?? _activeAgentCollaborationMode;
    final dispatchTerminalEnvironment = _buildAgentTerminalEnvironmentPayload();
    final dispatchActiveModel = _activeAgentModelId;
    final dispatchLoadedModelSource = _loadedAgentModelSourceKey;
    var dispatchMessages = List<ChatMessageModel>.from(_messages);
    bool isDispatchTargetCurrent() =>
        mounted && dispatchTargetGeneration == _conversationTargetRequestId;

    // Prime the active turn before status probing, ACP connection, adapter
    // preparation, or conversation persistence. The chat list can therefore
    // show the selected Agent and an elapsed processing state immediately,
    // without waiting for the first ACP API event.
    // Admit against the already visible local conversation before asking the
    // native runtime for status. Provider probes may take several seconds,
    // but no second local lifecycle is created while they are in flight.
    final preflightConversationId = dispatchConversationId;
    if (preflightConversationId != null) {
      _runtimeCoordinator.beginAcpTurn(
        taskId: aiMessageId,
        conversationId: preflightConversationId,
        mode: dispatchModeKey,
      );
    }

    late AgentRuntimeStatus status;
    try {
      status = await _refreshConnectedAgentRuntimeStatus();
    } catch (error) {
      if (isDispatchTargetCurrent()) {
        handleAgentError('Agent 连接失败: $error');
      }
      if (preflightConversationId != null) {
        _runtimeCoordinator.unregisterTask(
          aiMessageId,
          conversationId: preflightConversationId,
          mode: dispatchModeKey,
        );
      }
      return;
    }
    // Status probing is asynchronous. If the user switched conversation or
    // Harness while it was in flight, this logical turn was never admitted to
    // the target ACP and must not send a prompt there. Release the preflight
    // reservation so it cannot leave an invisible spinner in the old runtime.
    if (!isDispatchTargetCurrent()) {
      if (preflightConversationId != null) {
        _runtimeCoordinator.unregisterTask(
          aiMessageId,
          conversationId: preflightConversationId,
          mode: dispatchModeKey,
        );
      }
      return;
    }
    // Target metadata is authoritative. Runtime status is a shared/global
    // snapshot and may still describe the previous ACP during a switch.
    final remoteCodex =
        dispatchTarget?.isRemoteCodexSessionTarget == true ||
        dispatchTarget?.agentRuntime?.trim().toLowerCase() == 'remote' ||
        dispatchAgentId == _kRemoteCodexModeAgentId ||
        (dispatchTarget == null && agentModelSourceKey(status) == 'remote');
    int? conversationId = dispatchConversationId;
    if (remoteCodex) {
      conversationId = dispatchSessionId == null || dispatchSessionId.isEmpty
          ? this._ensureRemoteCodexRuntimeForCurrentMessages()
          : this._ensureRemoteCodexRuntimeForThread(dispatchSessionId);
    } else {
      if (conversationId == null) {
        if (!isDispatchTargetCurrent()) {
          return;
        }
        try {
          await _ensureActiveConversationReadyForStreaming();
        } catch (error) {
          if (isDispatchTargetCurrent()) {
            handleAgentError('Conversation setup failed. Please retry. $error');
          }
          _runtimeCoordinator.unregisterTask(
            aiMessageId,
            conversationId: preflightConversationId,
            mode: dispatchModeKey,
          );
          return;
        }
        if (!isDispatchTargetCurrent()) {
          _runtimeCoordinator.unregisterTask(
            aiMessageId,
            conversationId: preflightConversationId,
            mode: dispatchModeKey,
          );
          return;
        }
        conversationId = _currentConversationId;
      }
      if (conversationId == null) {
        if (isDispatchTargetCurrent()) {
          handleAgentError('Conversation setup failed. Please retry.');
        }
        _runtimeCoordinator.unregisterTask(
          aiMessageId,
          conversationId: preflightConversationId,
          mode: dispatchModeKey,
        );
        return;
      }
    }

    final resolvedConversationId = conversationId;
    if (isDispatchTargetCurrent()) {
      // The user bubble is created before this method, but ACP bootstrap can
      // replace the runtime projection while awaiting status/session setup.
      // Reconcile the captured submission into the current page snapshot
      // before ACP emits its live user echo, so both paths converge on one
      // visible conversation message.
      final currentMessages = List<ChatMessageModel>.from(_messages);
      final expectedUserId = userMessageId.trim();
      ChatMessageModel? submittedUser;
      for (final message in dispatchMessages) {
        if (message.user != 1) continue;
        if (message.id == expectedUserId) {
          submittedUser = message;
          break;
        }
      }
      if (submittedUser != null &&
          !currentMessages.any((message) => message.id == submittedUser!.id)) {
        currentMessages.insert(0, submittedUser);
      }
      dispatchMessages = currentMessages;
      _syncRuntimeSnapshotForMode(dispatchMode, messages: dispatchMessages);
    }
    // The preflight admission already owns this logical turn when the
    // resolved conversation is unchanged. Only admit again when asynchronous
    // conversation resolution actually moved the task to another runtime;
    // beginAcpTurn is idempotent for same-identity callers as a second guard.
    if (preflightConversationId != resolvedConversationId) {
      _runtimeCoordinator.beginAcpTurn(
        taskId: aiMessageId,
        conversationId: resolvedConversationId,
        mode: dispatchModeKey,
      );
    }
    if (!isDispatchTargetCurrent()) {
      _runtimeCoordinator.unregisterTask(
        aiMessageId,
        conversationId: resolvedConversationId,
        mode: dispatchModeKey,
      );
      return;
    }
    if (!remoteCodex) {
      // The runtime coordinator is the single Agent snapshot writer. Keeping
      // admission persistence on the same ordered tail as ACP updates avoids
      // a late page/history write rolling the conversation behind the turn.
      try {
        await _runtimeCoordinator.persistRuntimeConversation(
          conversationId: resolvedConversationId,
          mode: dispatchModeKey,
          persistMessages: true,
        );
      } catch (error) {
        if (isDispatchTargetCurrent()) {
          handleAgentError(
            'Conversation persistence failed. Please retry. $error',
          );
        }
        _runtimeCoordinator.unregisterTask(
          aiMessageId,
          conversationId: resolvedConversationId,
          mode: dispatchModeKey,
        );
        return;
      }
    }
    if (!isDispatchTargetCurrent()) {
      _runtimeCoordinator.unregisterTask(
        aiMessageId,
        conversationId: resolvedConversationId,
        mode: dispatchModeKey,
      );
      return;
    }

    final turnUsesPlanMode = _isAgentPlanMode(dispatchCollaborationMode);
    try {
      final turnModel = selectAgentRequestModel(
        status: status,
        overrideModel: modelOverride,
        activeModel: dispatchActiveModel,
        activeModelSourceMatches:
            dispatchLoadedModelSource == agentModelSourceKey(status),
      );
      final acpSessionId = await _prepareAcpSessionForTurn(
        runtimeCoordinator: _runtimeCoordinator,
        taskId: aiMessageId,
        conversationId: resolvedConversationId,
        mode: dispatchModeKey,
        existingSessionId: dispatchSessionId,
        isTargetCurrent: isDispatchTargetCurrent,
        model: turnModel,
        effort: dispatchReasoningEffort,
        collaborationMode: dispatchCollaborationMode,
        conversationMode: ConversationMode.agent.storageValue,
      );
      if (acpSessionId == null) {
        _runtimeCoordinator.unregisterTask(
          aiMessageId,
          conversationId: resolvedConversationId,
          mode: dispatchModeKey,
        );
        return;
      }
      _activeAgentThreadId = acpSessionId;
      final response = await AgentRuntimeService.promptSession(
        conversationId: resolvedConversationId,
        sessionId: acpSessionId,
        // Keep the request id stable across a retry of this message. The ACP
        // runtime uses it to return the original turn instead of replaying
        // tool calls.
        requestId: aiMessageId,
        agentId: remoteCodex ? null : dispatchAgentId,
        text: messageText,
        attachments: attachments,
        approvalPolicy: dispatchPermissionMode.approvalPolicy,
        approvalsReviewer: dispatchPermissionMode.approvalsReviewer,
        sandboxPolicy: dispatchPermissionMode.sandboxPolicy,
        model: turnModel,
        effort: dispatchReasoningEffort,
        collaborationMode: dispatchCollaborationMode,
        // The Agent page owns ConversationMode.agent. Keep the mode on the
        // canonical ACP prompt so built-in agents read the same durable
        // history bucket that this page writes.
        conversationMode: ConversationMode.agent.storageValue,
        terminalEnvironment: dispatchTerminalEnvironment,
      );
      final resolvedThreadId = _asAgentString(response['threadId']);
      if (resolvedThreadId != null &&
          remoteCodex &&
          isDispatchTargetCurrent()) {
        _activateRemoteCodexRuntimeForThread(resolvedThreadId);
      }
      final responseTurnId = _asAgentString(response['turnId']);
      _runtimeCoordinator.applyAcpPromptResponse(
        taskId: aiMessageId,
        conversationId: resolvedConversationId,
        mode: dispatchModeKey,
        sessionId: _asAgentString(response['sessionId']) ?? acpSessionId,
        turnId: responseTurnId,
        stopReason:
            _asAgentString(response['stopReason']) ??
            _asAgentString(response['status']),
        error: _asAgentString(response['error']),
      );
      if (isDispatchTargetCurrent()) {
        _activeAgentThreadId = resolvedThreadId ?? acpSessionId;
        _activeAgentTurnId = null;
        if (turnUsesPlanMode) _autoDeactivateAgentPlanModeAfterTurn();
      }
      final localConversationId = _asAgentInt(response['conversationId']);
      if (isDispatchTargetCurrent() &&
          !remoteCodex &&
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
        if (isDispatchTargetCurrent()) {
          await _persistVisibleThreadTargetIfNeeded();
        }
      }
      if (isDispatchTargetCurrent()) {
        await _writeAgentCommandPreferencesForCurrentConversation();
      }
    } catch (error) {
      final activeRuntime = _runtimeCoordinator.runtimeFor(
        conversationId: resolvedConversationId,
        mode: dispatchModeKey,
      );
      _runtimeCoordinator.applyAcpPromptResponse(
        taskId: aiMessageId,
        conversationId: resolvedConversationId,
        mode: dispatchModeKey,
        sessionId: activeRuntime?.activeAcpSessionId,
        turnId: activeRuntime?.activeAcpTurnId,
        stopReason: 'error',
        error: formatAgentRuntimeErrorForUser(error),
      );
    }
  }

  @override
  Future<void> _interruptAgentTurn() async {
    final conversationId = _modeState(ChatPageMode.agent).currentConversationId;
    final runtimeIdentity = _activeRuntime?.activeRunIdentity;
    final sessionId =
        runtimeIdentity?.normalizedSessionId ?? _activeAgentThreadId?.trim();
    final turnId =
        runtimeIdentity?.normalizedTurnId ?? _activeAgentTurnId?.trim();
    if (conversationId == null && sessionId == null) {
      return;
    }
    try {
      await AgentRuntimeService.cancelPrompt(
        conversationId: _isRemoteCodexConfigured() ? null : conversationId,
        sessionId: sessionId,
        promptId: turnId,
      );
    } catch (error) {
      debugPrint('Agent interrupt failed: $error');
    }
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
    final requestEpoch = _agentRuntimeStatusEpoch;
    var status = await AgentRuntimeService.status();
    if (!status.connected) {
      status = await AgentRuntimeService.connect();
    }
    if (requestEpoch != _agentRuntimeStatusEpoch) {
      return _agentRuntimeStatus;
    }
    _applyRefreshedAgentRuntimeStatus(status);
    return status;
  }

  void _applyRefreshedAgentRuntimeStatus(AgentRuntimeStatus status) {
    if (_isAcpAgentSwitching) {
      final expectedAgentId = _optimisticAcpAgentId?.trim() ?? '';
      final observedAgentId = status.activeAgentId?.trim() ?? '';
      if (expectedAgentId.isNotEmpty &&
          observedAgentId.isNotEmpty &&
          observedAgentId != expectedAgentId) {
        // A late status response from the previous Harness is not allowed to
        // change the right-top identity while the requested switch is still
        // in flight.
        return;
      }
    }
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
}
