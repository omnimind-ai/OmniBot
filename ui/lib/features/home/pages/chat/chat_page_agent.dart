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
        normalizedAgentId == _kRemoteCodexModeAgentId ||
        normalizedAgentId == 'na-cloud') {
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
      // and its first user-configured model, then persist the canonical
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
    // configuration-only: fetching /models here duplicated configuration work and
    // made entering Agent mode wait on the Provider again.
    final providerOptions = <ProviderModelOption>[
      ...?_modelOptionsByProfileId[profile.id],
    ];
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
    final agentId = _activeAcpAgentId;
    if (!mounted ||
        agentId == null ||
        agentId.isEmpty ||
        _isAgentRuntimeStatusLoading)
      return;
    final generation = _conversationTargetRequestId;
    setState(() => _isAgentRuntimeStatusLoading = true);
    try {
      final status = await AgentRuntimeService.status(agentId: agentId);
      if (!mounted ||
          generation != _conversationTargetRequestId ||
          _activeAcpAgentId != agentId)
        return;
      setState(() => _agentRuntimeStatus = status);
      unawaited(_loadAgentCatalog(force: true));
      if (_activeMode == ChatPageMode.agent)
        unawaited(_loadAgentModelOptionsWhenReady());
    } catch (_) {
      if (!mounted || generation != _conversationTargetRequestId) return;
      setState(() => _agentRuntimeStatus = AgentRuntimeStatus.disconnected);
    } finally {
      if (mounted) setState(() => _isAgentRuntimeStatusLoading = false);
    }
  }

  @override
  String? _remoteCodexWorkspaceNameForGreeting() {
    if (!_activeAgentTargetIsRemote) {
      return null;
    }
    return _remoteCodexLastPathSegment(
      _agentRuntimeStatus.remoteCwd ?? _agentRuntimeStatus.cwd ?? '',
    );
  }

  @override
  Future<void> _openRemoteCodexWorkspacePicker() async {
    if (!_activeAgentTargetIsRemote) {
      return;
    }
    CodexRemoteBridgeConfig config;
    try {
      config = await AgentRuntimeService.readRemoteBridgeConfig();
    } catch (error) {
      showToast(
        formatAgentRuntimeErrorForUser(
          error,
          english: LegacyTextLocalizer.isEnglish,
        ),
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
      final status = await AgentRuntimeService.status(
        agentId: _kRemoteCodexModeAgentId,
      );
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
        formatAgentRuntimeErrorForUser(
          error,
          english: LegacyTextLocalizer.isEnglish,
        ),
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
    // Reserve the explicit remote session identity before its load can emit
    // updates; projection remains owned by the shared coordinator.
    final runtimeId = _ensureRemoteCodexRuntimeForThread(threadId);
    _activeRemoteCodexRuntimeId = runtimeId;
    _activeAgentThreadId = threadId;
    _activeAgentTurnId = null;
    _modeState(ChatPageMode.agent).currentConversationId = runtimeId;

    try {
      final response = await AgentRuntimeService.loadSession(
        sessionId: threadId,
        agentId: _kRemoteCodexModeAgentId,
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
      );
      final historySync = _startRemoteCodexSessionSync(resolvedThreadId);
      if (_messages.isEmpty) {
        await historySync;
        if (!_isConversationTargetRequestCurrent(targetRequestId)) return;
      } else {
        unawaited(historySync);
      }
      _rememberRuntimeUiSnapshot(ChatPageMode.agent);
    } catch (error) {
      if (!_isConversationTargetRequestCurrent(targetRequestId)) return;
      showToast(
        formatAgentRuntimeErrorForUser(
          error,
          english: LegacyTextLocalizer.isEnglish,
        ),
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
      _activeAgentCollaborationMode = null;
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
    if (_activeAcpAgentId == null) return;
    final currentSourceKey = _activeAgentModelSourceKey;
    final sharedProviderAgent = _usesSharedProviderModel(_activeAcpAgentId);
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
    final sourceKey = _activeAgentModelSourceKey;
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
    final agentId = _activeAcpAgentId;
    if (agentId == null) return;
    final remoteTarget = _activeAgentTargetIsRemote;
    if (_activeAcpAgentId == 'na-cloud') {
      if (!mounted) return;
      setState(() {
        _agentModelOptions = const <String>[];
        _agentReasoningEffortOptions = const <String>[];
        _agentCollaborationModes = const <String>[];
        _agentModelConfigSupported = false;
        _activeAgentModelId = null;
        _activeAgentReasoningEffort = null;
        _activeAgentCollaborationMode = null;
        _loadedAgentModelSourceKey = _activeAgentModelSourceKey;
      });
      return;
    }
    final sourceKey = _activeAgentModelSourceKey;
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
      final configSettings = sharedAgent || !remoteTarget
          ? const _AgentRunSettingsSnapshot()
          : await _readAgentRunSettingsFromServerConfig(agentId);
      final response = sharedAgent
          ? const <String, dynamic>{}
          : remoteTarget
          ? await AgentRuntimeService.listModels(agentId: agentId)
          : await _readActiveAcpSessionConfig();
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
            currentSource: _activeAgentModelSourceKey,
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
            currentSource: _activeAgentModelSourceKey,
          )) {
        return;
      }
      setState(() {
        _agentModelListError = formatAgentRuntimeErrorForUser(
          error,
          english: LegacyTextLocalizer.isEnglish,
        );
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

  Future<_AgentRunSettingsSnapshot> _readAgentRunSettingsFromServerConfig(
    String agentId,
  ) async {
    try {
      final response = await AgentRuntimeService.readConfig(agentId: agentId);
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
    final generation = _conversationTargetRequestId;
    try {
      final response = await _readActiveAcpSessionConfig();
      if (!mounted || generation != _conversationTargetRequestId) return;
      final option = acpConfigOptions(response)
          .where((option) => option['id'] == 'collaboration_mode')
          .firstOrNull;
      final choices = <Map>[];
      for (final entry
          in (option?['options'] as List? ?? const []).whereType<Map>()) {
        if (entry['options'] is List) {
          choices.addAll((entry['options'] as List).whereType<Map>());
        } else {
          choices.add(entry);
        }
      }
      final modes = choices
          .map((entry) => entry['value']?.toString() ?? '')
          .where((value) => value.isNotEmpty)
          .toList();
      setState(() {
        _activeAgentThreadId = (response['sessionId'] ?? response['threadId'])
            ?.toString();
        _agentCollaborationModes = modes;
        _activeAgentCollaborationMode = option?['currentValue']?.toString();
        _agentCollaborationModeListError = null;
      });
    } catch (error) {
      if (!mounted || generation != _conversationTargetRequestId) return;
      setState(() {
        _agentCollaborationModeListError = formatAgentRuntimeErrorForUser(
          error,
        );
      });
    } finally {
      if (mounted) {
        setState(() => _isAgentCollaborationModeListLoading = false);
      }
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
    if (!_agentModelConfigSupported && !_activeAgentTargetIsRemote) {
      return;
    }
    final sharedSelection = _activeDispatchSceneSelection;
    final sharedAgent = _usesSharedProviderModel(_activeAcpAgentId?.trim());
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
        unawaited(_loadNormalChatModelContext());
      } else {
        await _setAgentConfigOption(configId: 'model', value: normalized);
      }
    } catch (error) {
      if (mounted) {
        showToast(
          formatAgentRuntimeErrorForUser(
            error,
            english: LegacyTextLocalizer.isEnglish,
          ),
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
          formatAgentRuntimeErrorForUser(
            error,
            english: LegacyTextLocalizer.isEnglish,
          ),
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
      conversationId:
          _unboundConfigurationSessions.values.any(
            (session) =>
                session.sessionId == threadId && session.agentId == agentId,
          )
          ? null
          : conversationId,
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
    if (_isAgentCollaborationModeListLoading) return;
    final generation = _conversationTargetRequestId;
    await _loadAgentCollaborationModes(force: true);
    final planMode = _resolveAgentPlanMode(_agentCollaborationModes);
    if (!mounted || generation != _conversationTargetRequestId) return;
    if (planMode == null) {
      _showSnackBar(
        LegacyTextLocalizer.isEnglish
            ? 'This Agent does not advertise Plan mode'
            : '当前 Agent 未提供 Plan 模式',
      );
      return;
    }
    await _setAgentConfigOption(
      configId: 'collaboration_mode',
      value: planMode,
    );
    if (!mounted || generation != _conversationTargetRequestId) return;
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
    if (_isAgentCollaborationModeListLoading) return;
    final generation = _conversationTargetRequestId;
    await _loadAgentCollaborationModes(force: true);
    final defaultMode = _agentCollaborationModes
        .where((mode) => mode.toLowerCase() == 'default')
        .firstOrNull;
    if (defaultMode == null ||
        !mounted ||
        generation != _conversationTargetRequestId)
      return;
    await _setAgentConfigOption(
      configId: 'collaboration_mode',
      value: defaultMode,
    );
    if (!mounted || generation != _conversationTargetRequestId) return;
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
      await _sendMessage(text: '/init');
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
        await _executeAgentInitCommand(attachments: attachments);
        return true;
      case AgentSlashSubmitKind.togglePlan:
        await _toggleAgentPlanMode();
        return true;
      case AgentSlashSubmitKind.startPlan:
        await _activateAgentPlanMode(persistOnly: true);
        if (!_isAgentPlanMode(_activeAgentCollaborationMode)) return true;
        _messageController.clear();
        _hideSlashCommandPanel();
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
  Future<void> _executeAgentInitCommand({
    List<Map<String, dynamic>> attachments = const [],
  }) async {
    await _startAgentTurnCommand(
      displayText: '/init',
      actualText: _kAgentInitPrompt,
      attachments: attachments,
    );
  }

  @override
  Future<void> _startAgentReviewCommand() async {
    if (_availableAcpCommandForText('/review') == null) {
      _showSnackBar(
        LegacyTextLocalizer.isEnglish
            ? 'This Agent does not advertise /review'
            : '当前 Agent 未提供 /review 命令',
      );
      return;
    }
    if (_isAiResponding) {
      return;
    }
    final generation = _conversationTargetRequestId;
    _inputFocusNode.unfocus();
    _messageController.clear();
    _hideSlashCommandPanel();
    final agentId = _activeAcpAgentId;
    if (agentId == null) {
      handleAgentError(
        formatAgentRuntimeErrorForUser('agent_identity_required: review'),
      );
      return;
    }
    final remoteCodex = agentId == _kRemoteCodexModeAgentId;
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
      final reviewModel = await _resolveAgentRequestModel();
      final response = await AgentRuntimeService.reviewSession(
        agentId: agentId,
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
    setState(() {
      _modeState(_activeMode).consumeMessageAttachments(attachments);
    });
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
        ? '.$_activeAgentModelSourceKey'
        : '';
    if (conversationId == null) {
      return '$_kAgentPreferenceStoragePrefix.$kind$source.global';
    }
    return '$_kAgentPreferenceStoragePrefix.$kind$source.conversation.$conversationId';
  }

  String _legacyAgentPreferenceKey(String kind, {int? conversationId}) {
    final source = kind == _kAgentModelPreferenceKey
        ? '.$_activeAgentModelSourceKey'
        : '';
    if (conversationId == null) {
      return '$_kLegacyAgentPreferenceStoragePrefix.$kind$source.global';
    }
    return '$_kLegacyAgentPreferenceStoragePrefix.$kind$source.conversation.$conversationId';
  }

  @override
  void _handleAgentRuntimeProjection(ChatAgentEventProjection projection) {
    final event = projection.event;
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
    final conversationId = projection.conversationId;
    final runtimeMode = projection.mode;
    if (conversationId == null || runtimeMode == null) return;
    final eventThreadId = _remoteCodexEventThreadId(event);
    if (_isRemoteCodexConfigured() &&
        eventThreadId != null &&
        conversationId == _remoteCodexRuntimeId(eventThreadId) &&
        this._shouldPromoteRemoteCodexEventToVisibleThread(
          threadId: eventThreadId,
          runtimeId: conversationId,
        )) {
      _activateRemoteCodexRuntimeForThread(eventThreadId);
    }
    final eventMode = switch (runtimeMode) {
      kChatRuntimeModeNormal => ChatPageMode.normal,
      kChatRuntimeModeOpenClaw => ChatPageMode.openclaw,
      _ => ChatPageMode.agent,
    };
    final isVisibleConversation =
        conversationId == _modeState(eventMode).currentConversationId &&
        _activeMode == eventMode;
    final result = projection.result;
    if (result.compatibilityWarning != null && isVisibleConversation) {
      showToast(result.compatibilityWarning!, type: ToastType.warning);
    }
    final threadId = _asAgentString(event['threadId']) ?? result.threadId;
    final turnId = acpEventTurnId(event) ?? result.turnId;
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
      final runtime = _runtimeCoordinator.runtimeFor(
        conversationId: conversationId,
        mode: kChatRuntimeModeAgent,
      );
      if (runtime != null && !runtime.isAiResponding) {
        _activeAgentTurnId = null;
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
    final dispatchAgentId = (dispatchTarget?.agentId ?? _activeAcpAgentId ?? '')
        .trim();
    final dispatchSessionId = _activeAgentThreadId?.trim();
    final dispatchPermissionMode = _agentPermissionMode;
    final dispatchReasoningEffort = _activeAgentReasoningEffort;
    final dispatchCollaborationMode =
        collaborationModeOverride ?? _activeAgentCollaborationMode;
    final dispatchTerminalEnvironment = _buildAgentTerminalEnvironmentPayload();
    final dispatchActiveModel = _activeAgentModelId;
    final dispatchLoadedModelSource = _loadedAgentModelSourceKey;
    if (dispatchAgentId.isEmpty) {
      handleAgentError(
        formatAgentRuntimeErrorForUser('agent_identity_required: prompt'),
      );
      return;
    }
    final remoteCodex = dispatchAgentId == _kRemoteCodexModeAgentId;
    var dispatchMessages = List<ChatMessageModel>.from(_messages);
    bool isDispatchTargetCurrent() =>
        mounted && dispatchTargetGeneration == _conversationTargetRequestId;

    // Prime the active turn before status probing, ACP connection, adapter
    // preparation, or conversation persistence. The chat list can therefore
    // show the selected Agent and an elapsed processing state immediately,
    // without waiting for the first ACP API event.
    // Admit against the already visible conversation before its canonical
    // session request. Slow runtime startup does not create another run.
    final preflightConversationId = dispatchConversationId;
    if (preflightConversationId != null) {
      _runtimeCoordinator.beginAcpTurn(
        taskId: aiMessageId,
        conversationId: preflightConversationId,
        mode: dispatchModeKey,
      );
    }

    // The canonical session request resolves its explicit Conversation owner.
    // No selected/global runtime preflight exists on this path.
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

    try {
      final turnModel = selectAgentRequestModel(
        overrideModel: modelOverride,
        activeModel: dispatchActiveModel,
        activeModelSourceMatches:
            dispatchLoadedModelSource ==
            (remoteCodex ? 'remote' : 'local-$dispatchAgentId'),
      );
      final acpSessionId = await _prepareAcpSessionForTurn(
        runtimeCoordinator: _runtimeCoordinator,
        taskId: aiMessageId,
        conversationId: resolvedConversationId,
        mode: dispatchModeKey,
        agentId: dispatchAgentId,
        existingSessionId: dispatchSessionId,
        preparedForFirstPrompt:
            _unboundConfigurationSessions[dispatchMode]?.sessionId ==
                dispatchSessionId &&
            _unboundConfigurationSessions[dispatchMode]?.agentId ==
                dispatchAgentId &&
            dispatchSessionId != null,
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
      _unboundConfigurationSessions.remove(dispatchMode);
      _activeAgentThreadId = acpSessionId;
      final response = await AgentRuntimeService.promptSession(
        conversationId: resolvedConversationId,
        sessionId: acpSessionId,
        // Keep the request id stable across a retry of this message. The ACP
        // runtime uses it to return the original turn instead of replaying
        // tool calls.
        requestId: aiMessageId,
        // The cloud adapter preserves the host's actual user item on reload.
        // Retries may retain a user item while reserving a new prompt id.
        userMessageId: dispatchAgentId == 'na-cloud' ? userMessageId : null,
        agentId: dispatchAgentId,
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
        agentId: _activeAcpAgentId,
        conversationId: _isRemoteCodexConfigured() ? null : conversationId,
        sessionId: sessionId,
        promptId: turnId,
      );
    } catch (error) {
      debugPrint('Agent interrupt failed: $error');
      if (mounted) {
        showToast(formatAgentRuntimeErrorForUser(error), type: ToastType.error);
      }
    }
  }

  Future<String?> _resolveAgentRequestModel({String? overrideModel}) async {
    final sourceKey = _activeAgentModelSourceKey;
    return selectAgentRequestModel(
      overrideModel: overrideModel,
      activeModel: _activeAgentModelId,
      activeModelSourceMatches: _loadedAgentModelSourceKey == sourceKey,
    );
  }
}
