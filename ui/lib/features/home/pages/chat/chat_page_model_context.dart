part of 'chat_page.dart';

mixin _ChatPageModelContextMixin on _ChatPageStateBase {
  @override
  Future<void> _loadNormalChatModelContext() =>
      _loadNormalChatModelContextInternal();

  Future<void> _loadNormalChatModelContextInternal() async {
    try {
      final results = await Future.wait<dynamic>([
        ModelProviderConfigService.loadChatModelGroups(),
        SceneModelConfigService.getSceneCatalog(),
      ]);
      if (!mounted) return;

      final groups = results[0] as List<ProviderModelGroup>;
      final catalog = results[1] as List<SceneCatalogItem>;
      final profiles = groups.map((group) => group.profile).toList();
      final modelOptionsByProfileId = <String, List<ProviderModelOption>>{
        for (final group in groups)
          group.profile.id: List<ProviderModelOption>.from(group.models),
      };

      setState(() {
        _sceneCatalog = catalog;
        _modelProviderProfiles = profiles;
        _modelOptionsByProfileId = _mergeChatModelOptions(
          profiles: profiles,
          source: modelOptionsByProfileId,
          sceneCatalog: catalog,
          overrideSelection: _activeConversationModelOverrideSelection,
        );
      });
      await _syncInvalidNormalConversationOverrideIfNeeded();
      await _syncActiveNormalConversationPromptTokenThreshold();
    } catch (e) {
      debugPrint('加载聊天模型上下文失败: $e');
    }
  }

  Future<void> _refreshOfficialChatProviderModelsForSelector() async {
    try {
      final group =
          await ModelProviderConfigService.refreshOfficialChatModelGroup();
      if (!mounted || group == null) {
        return;
      }
      final profiles = List<ModelProviderProfileSummary>.from(
        _modelProviderProfiles,
      );
      final profileIndex = profiles.indexWhere(
        (profile) => profile.id == group.profile.id,
      );
      if (profileIndex >= 0) {
        profiles[profileIndex] = group.profile;
      } else {
        profiles.add(group.profile);
      }
      final next = <String, List<ProviderModelOption>>{
        for (final entry in _modelOptionsByProfileId.entries)
          entry.key: List<ProviderModelOption>.from(entry.value),
        group.profile.id: List<ProviderModelOption>.from(group.models),
      };
      setState(() {
        _modelProviderProfiles = profiles;
        _modelOptionsByProfileId = _mergeChatModelOptions(
          profiles: profiles,
          source: next,
          sceneCatalog: _sceneCatalog,
          overrideSelection: _activeConversationModelOverrideSelection,
        );
      });
    } catch (_) {
      // Keep the already-loaded cache available while offline. The next
      // explicit selector opening will retry the official catalog refresh.
    }
  }

  @override
  Future<void> _syncInvalidNormalConversationOverrideIfNeeded() async {
    if (_modelProviderProfiles.isEmpty) {
      return;
    }
    final configuredProfileIds = _modelProviderProfiles
        .where((item) => item.configured)
        .map((item) => item.id)
        .toSet();
    final persisted = _conversationModelOverride;
    final pending = _pendingConversationModelOverride;
    final shouldClearPersisted =
        persisted != null &&
        !configuredProfileIds.contains(persisted.providerProfileId);
    final shouldClearPending =
        pending != null &&
        !configuredProfileIds.contains(pending.providerProfileId);

    if (!shouldClearPersisted && !shouldClearPending) {
      return;
    }

    final normalConversationId = _modeState(
      ChatPageMode.normal,
    ).currentConversationId;
    if (shouldClearPersisted && normalConversationId != null) {
      await ConversationModelOverrideService.clearOverride(
        normalConversationId,
      );
    }
    if (!mounted) {
      return;
    }
    setState(() {
      if (shouldClearPersisted) {
        _conversationModelOverride = null;
      }
      if (shouldClearPending) {
        _pendingConversationModelOverride = null;
      }
      if (_conversationModelOverride == null &&
          _pendingConversationModelOverride == null) {
        _showConversationModelMentionChip = false;
      }
      _modelOptionsByProfileId = _mergeChatModelOptions(
        profiles: _modelProviderProfiles,
        source: _modelOptionsByProfileId,
        sceneCatalog: _sceneCatalog,
        overrideSelection: _activeConversationModelOverrideSelection,
      );
    });
  }

  @override
  Future<void> _loadConversationModelOverrideForNormalConversation(
    int? conversationId,
  ) async {
    if (conversationId == null) {
      if (!mounted) return;
      setState(() {
        _conversationModelOverride = null;
        _conversationReasoningEffort = null;
        if (_pendingConversationModelOverride == null) {
          _showConversationModelMentionChip = false;
        }
      });
      return;
    }
    final results = await Future.wait<dynamic>([
      ConversationModelOverrideService.getOverride(conversationId),
      ConversationReasoningEffortService.getEffort(conversationId),
    ]);
    final override = results[0] as ConversationModelOverride?;
    final reasoningEffort = results[1] as String?;
    if (!mounted) return;
    final nextSelection = override == null
        ? _pendingConversationModelOverride
        : _ChatModelOverrideSelection(
            providerProfileId: override.providerProfileId,
            modelId: override.modelId,
          );
    setState(() {
      _conversationModelOverride = override;
      _conversationReasoningEffort = reasoningEffort;
      _pendingConversationModelOverride = null;
      _pendingConversationReasoningEffort = null;
      _showConversationModelMentionChip = override != null;
      _modelOptionsByProfileId = _mergeChatModelOptions(
        profiles: _modelProviderProfiles,
        source: _modelOptionsByProfileId,
        sceneCatalog: _sceneCatalog,
        overrideSelection: nextSelection,
      );
    });
    await _syncInvalidNormalConversationOverrideIfNeeded();
    await _syncActiveNormalConversationPromptTokenThreshold(
      selection: nextSelection,
      conversationId: conversationId,
    );
  }

  @override
  Future<void> _persistPendingConversationModelOverrideIfNeeded(
    int conversationId,
  ) async {
    final pending = _pendingConversationModelOverride;
    final pendingReasoningEffort = _pendingConversationReasoningEffort;
    if (pending == null && pendingReasoningEffort == null) {
      return;
    }

    ConversationModelOverride? value;
    if (pending != null) {
      value = ConversationModelOverride(
        conversationId: conversationId,
        providerProfileId: pending.providerProfileId,
        modelId: pending.modelId,
      );
      await ConversationModelOverrideService.saveOverride(value);
    }
    final normalizedEffort = _normalizeReasoningEffort(pendingReasoningEffort);
    if (normalizedEffort != null) {
      await ConversationReasoningEffortService.saveEffort(
        conversationId,
        normalizedEffort,
      );
    }
    if (!mounted) return;
    final nextSelection = value == null
        ? _activeConversationModelOverrideSelection
        : _ChatModelOverrideSelection(
            providerProfileId: value.providerProfileId,
            modelId: value.modelId,
          );
    setState(() {
      if (value != null) {
        _conversationModelOverride = value;
      }
      _conversationReasoningEffort =
          normalizedEffort ?? _conversationReasoningEffort;
      _pendingConversationModelOverride = null;
      _pendingConversationReasoningEffort = null;
      _modelOptionsByProfileId = _mergeChatModelOptions(
        profiles: _modelProviderProfiles,
        source: _modelOptionsByProfileId,
        sceneCatalog: _sceneCatalog,
        overrideSelection: nextSelection,
      );
    });
    await _syncActiveNormalConversationPromptTokenThreshold(
      selection: nextSelection,
      conversationId: conversationId,
    );
  }

  @override
  void _removeActiveModelMentionTokenFromInput() {
    final token = _activeModelMentionToken;
    if (token == null) {
      return;
    }
    final value = _messageController.value;
    final text = value.text;
    final start = token.start.clamp(0, text.length);
    final end = token.end.clamp(start, text.length);
    final before = text.substring(0, start);
    final after = text.substring(end);
    var nextText = '$before$after';
    if (before.endsWith(' ') && after.startsWith(' ')) {
      nextText = '$before${after.substring(1)}';
    }
    if (nextText.startsWith(' ')) {
      nextText = nextText.substring(1);
    }
    final nextOffset = start > nextText.length ? nextText.length : start;
    _messageController.value = TextEditingValue(
      text: nextText,
      selection: TextSelection.collapsed(offset: nextOffset),
    );
  }

  @override
  Future<void> _applyConversationModelOverride({
    required String providerProfileId,
    required String modelId,
    bool displayAsMentionChip = false,
  }) async {
    _removeActiveModelMentionTokenFromInput();
    final selection = _ChatModelOverrideSelection(
      providerProfileId: providerProfileId,
      modelId: modelId,
    );
    final normalConversationId = _modeState(
      ChatPageMode.normal,
    ).currentConversationId;

    if (normalConversationId == null) {
      if (!mounted) return;
      setState(() {
        _pendingConversationModelOverride = selection;
        _conversationModelOverride = null;
        _showConversationModelMentionChip = displayAsMentionChip;
        _showModelMentionPanel = false;
        _activeModelMentionToken = null;
        _modelOptionsByProfileId = _mergeChatModelOptions(
          profiles: _modelProviderProfiles,
          source: _modelOptionsByProfileId,
          sceneCatalog: _sceneCatalog,
          overrideSelection: selection,
        );
      });
    } else {
      final value = ConversationModelOverride(
        conversationId: normalConversationId,
        providerProfileId: providerProfileId,
        modelId: modelId,
      );
      await ConversationModelOverrideService.saveOverride(value);
      if (!mounted) return;
      setState(() {
        _conversationModelOverride = value;
        _pendingConversationModelOverride = null;
        _showConversationModelMentionChip = displayAsMentionChip;
        _showModelMentionPanel = false;
        _activeModelMentionToken = null;
        _modelOptionsByProfileId = _mergeChatModelOptions(
          profiles: _modelProviderProfiles,
          source: _modelOptionsByProfileId,
          sceneCatalog: _sceneCatalog,
          overrideSelection: selection,
        );
      });
    }
    await _syncActiveNormalConversationPromptTokenThreshold(
      selection: selection,
      conversationId: normalConversationId,
    );

    final switchedLabel = displayAsMentionChip ? '@$modelId' : modelId;
    showToast(
      LegacyTextLocalizer.localize('已切换到 $switchedLabel'),
      type: ToastType.success,
    );
  }

  @override
  Future<void> _applyConversationReasoningEffort(String reasoningEffort) async {
    final normalizedEffort = _normalizeReasoningEffort(reasoningEffort);
    if (normalizedEffort == null || _activeMode != ChatPageMode.normal) {
      return;
    }
    final normalConversationId = _modeState(
      ChatPageMode.normal,
    ).currentConversationId;
    if (normalConversationId == null) {
      if (!mounted) return;
      setState(() {
        _pendingConversationReasoningEffort = normalizedEffort;
        _conversationReasoningEffort = null;
      });
    } else {
      await ConversationReasoningEffortService.saveEffort(
        normalConversationId,
        normalizedEffort,
      );
      if (!mounted) return;
      setState(() {
        _conversationReasoningEffort = normalizedEffort;
        _pendingConversationReasoningEffort = null;
      });
    }
    showToast(
      LegacyTextLocalizer.localize(
        normalizedEffort == 'no' ? '已关闭思考' : '已设置思考强度为 $normalizedEffort',
      ),
      type: ToastType.success,
    );
  }

  @override
  Future<void> _clearConversationModelOverride() async {
    final hasOverride = _activeConversationModelOverrideSelection != null;
    if (!hasOverride) {
      return;
    }
    final normalConversationId = _modeState(
      ChatPageMode.normal,
    ).currentConversationId;
    if (normalConversationId != null) {
      await ConversationModelOverrideService.clearOverride(
        normalConversationId,
      );
    }
    if (!mounted) return;
    setState(() {
      _conversationModelOverride = null;
      _pendingConversationModelOverride = null;
      _showConversationModelMentionChip = false;
      _modelOptionsByProfileId = _mergeChatModelOptions(
        profiles: _modelProviderProfiles,
        source: _modelOptionsByProfileId,
        sceneCatalog: _sceneCatalog,
        overrideSelection: null,
      );
    });
    showToast(
      LegacyTextLocalizer.localize('已恢复场景默认模型'),
      type: ToastType.success,
    );
    await _syncActiveNormalConversationPromptTokenThreshold();
  }

  @override
  Map<String, dynamic>? _buildAgentModelOverridePayload() {
    return _buildChatModelOverridePayload();
  }

  @override
  Map<String, dynamic>? _buildChatModelOverridePayload() {
    if (_activeConversationMode != ChatPageMode.normal ||
        !_showConversationModelMentionChip) {
      return null;
    }
    final override = _activeConversationModelOverrideSelection;
    if (override == null) {
      return null;
    }
    ModelProviderProfileSummary? profile;
    for (final item in _modelProviderProfiles) {
      if (item.id == override.providerProfileId) {
        profile = item;
        break;
      }
    }
    ProviderModelOption? selectedModel;
    for (final item
        in _modelOptionsByProfileId[override.providerProfileId] ??
            const <ProviderModelOption>[]) {
      if (item.id == override.modelId) {
        selectedModel = item;
        break;
      }
    }
    return {
      'providerProfileId': override.providerProfileId,
      'modelId': override.modelId,
      if ((selectedModel?.contextLimit ?? 0) > 0)
        'contextLimit': selectedModel!.contextLimit,
      if (profile != null && profile.baseUrl.trim().isNotEmpty)
        'apiBase': profile.baseUrl.trim(),
      if (profile != null && profile.protocolType.trim().isNotEmpty)
        'protocolType': profile.protocolType.trim(),
    };
  }

  @override
  _ActiveModelMentionToken? _parseActiveModelMentionToken(
    TextEditingValue value,
  ) {
    if (_activeConversationMode != ChatPageMode.normal || _isOpenClawSurface) {
      return null;
    }
    final selectionEnd = value.selection.baseOffset;
    final text = value.text;
    if (selectionEnd < 0 || selectionEnd > text.length) {
      return null;
    }

    var tokenStart = selectionEnd;
    while (tokenStart > 0) {
      final char = text.substring(tokenStart - 1, tokenStart);
      if (RegExp(r'\s').hasMatch(char)) {
        break;
      }
      tokenStart -= 1;
    }

    if (tokenStart >= text.length ||
        text.substring(tokenStart, tokenStart + 1) != '@') {
      return null;
    }
    if (tokenStart > 0) {
      final previousChar = text.substring(tokenStart - 1, tokenStart);
      if (!RegExp(r'\s').hasMatch(previousChar)) {
        return null;
      }
    }

    final query = text.substring(tokenStart + 1, selectionEnd);
    if (query.contains(RegExp(r'\s'))) {
      return null;
    }
    return _ActiveModelMentionToken(
      query: query,
      start: tokenStart,
      end: selectionEnd,
    );
  }

  @override
  Future<void> _openConversationModelSelector(
    BuildContext anchorContext,
  ) async {
    if (_activeMode != ChatPageMode.normal) {
      return;
    }
    if (_conversationModelSelectorHandle != null ||
        !_conversationModelSelectorOpeningGuard.tryBegin()) {
      // 已经打开或正在等待目录刷新，不重开。
      return;
    }
    try {
      if (_showSlashCommandPanel ||
          _showModelMentionPanel ||
          _openClawPanelExpanded) {
        setState(() {
          _showSlashCommandPanel = false;
          _showModelMentionPanel = false;
          _openClawPanelExpanded = false;
        });
      }
      // Refresh the scene binding at the point of use, then independently
      // force-refresh the configured official profile. The active Provider may
      // be BYOK, while the selector still shows the official group.
      await _loadNormalChatModelContextInternal();
      await _refreshOfficialChatProviderModelsForSelector();
      final selectorProfiles = _modelProviderProfiles;
      final selectorOptions = _modelOptionsByProfileId;
      final hasSelectorModels = selectorProfiles.any((profile) {
        return profile.configured &&
            (selectorOptions[profile.id] ?? const <ProviderModelOption>[])
                .isNotEmpty;
      });
      if (!hasSelectorModels) {
        return;
      }
      if (!mounted || !anchorContext.mounted) {
        return;
      }
      // 关键：不能调 `_inputFocusNode.unfocus()`，也不能用 `showGlassPopup`
      // (push Navigator route)。两条路径都会让 TextField 失焦 → 软键盘塌陷 →
      // 输入栏下沉 → popup 锚点错位(锚点是 popup 弹出瞬间按按钮在屏幕上的位置算的，
      // 键盘塌陷后按钮位置已经变了)。
      //
      // Flutter 框架细节(重要)：`Route.requestFocus = false` **不能** 阻止 push
      // 时的焦点迁移——`ModalRoute.didPush` 里检查的是 `navigator.widget.requestFocus`
      // (Navigator 的 requestFocus),不是 Route 的。详见 routes.dart:1668。要彻底
      // 跳过这条焦点迁移路径，唯一干净的办法是不走 Navigator 路由——直接挂到 Overlay。
      // 这里走 [showOverlayGlassPopup],它把 OverlayEntry + Material + tap-outside +
      // BackButtonListener + DismissOverlayOnKeyboardHide + playReverse 清理时序
      // 都封装好了。
      final anchorBox = anchorContext.findRenderObject() as RenderBox?;
      final anchorRect = glassPopupAnchorFromContext(anchorContext);
      if (anchorBox == null || !anchorBox.hasSize || anchorRect == null) {
        return;
      }
      final popupWidth = math
          .max(260.0, anchorBox.size.width)
          .clamp(260.0, 320.0)
          .toDouble();
      const popupMaxHeight = 360.0;

      final currentSelection = _activeDispatchSceneSelection == null
          ? null
          : ConversationModelSelection(
              providerProfileId:
                  _activeDispatchSceneSelection!.providerProfileId,
              modelId: _activeDispatchSceneSelection!.modelId,
            );
      final handle = showOverlayGlassPopup<ConversationModelSelection>(
        context: context,
        anchor: anchorRect,
        builder: (handle) => ConversationModelSelectorContent(
          width: popupWidth,
          maxHeight: popupMaxHeight,
          profiles: selectorProfiles,
          providerModelsByProfileId: selectorOptions,
          currentSelection: currentSelection,
          // 软键盘"确定"提交搜索时:先打开 popup 的"一次性键盘隐藏豁免",再 unfocus
          // —— 这样 IME 塌陷不会被 DismissOverlayOnKeyboardHide 当作"用户想关 popup"
          // 误关掉,搜索结果列表得以保留。
          onSearchSubmitted: () {
            handle.keepOpenOnNextKeyboardHide();
            FocusManager.instance.primaryFocus?.unfocus();
          },
          // dismiss 内部会立刻 complete future,让下面 await 的逻辑并行起跑;
          // 收起动画在后台跑完,UI 更响应。
          onSelect: (selection) => unawaited(handle.dismiss(selection)),
        ),
      );
      setState(() {
        _conversationModelSelectorHandle = handle;
      });

      try {
        final selected = await handle.future;
        if (selected == null) {
          return;
        }
        await _applyDispatchSceneModelSelection(
          providerProfileId: selected.providerProfileId,
          modelId: selected.modelId,
        );
      } finally {
        if (_conversationModelSelectorHandle == handle) {
          if (mounted) {
            setState(() {
              _conversationModelSelectorHandle = null;
            });
          } else {
            _conversationModelSelectorHandle = null;
          }
        }
      }
    } finally {
      _conversationModelSelectorOpeningGuard.finish();
    }
  }

  @override
  Future<void> _applyDispatchSceneModelSelection({
    required String providerProfileId,
    required String modelId,
  }) async {
    const sceneId = 'scene.dispatch.model';
    final currentSelection = _activeDispatchSceneSelection;
    if (currentSelection != null &&
        currentSelection.providerProfileId == providerProfileId &&
        currentSelection.modelId == modelId) {
      return;
    }
    final selectionSerial = ++_dispatchSceneModelSelectionSerial;
    try {
      await SceneModelConfigService.saveSceneModelBinding(
        sceneId: sceneId,
        providerProfileId: providerProfileId,
        modelId: modelId,
      );
      await _loadNormalChatModelContext();
      if (!mounted || selectionSerial != _dispatchSceneModelSelectionSerial) {
        return;
      }
      showToast(
        LegacyTextLocalizer.localize('Agent 模型已切换到 $modelId'),
        type: ToastType.success,
      );
    } catch (e) {
      if (!mounted || selectionSerial != _dispatchSceneModelSelectionSerial) {
        return;
      }
      showToast(
        LegacyTextLocalizer.localize('更新 Agent 模型失败：$e'),
        type: ToastType.error,
      );
    }
  }

  @override
  Widget _buildModelMentionPanel() {
    return _ChatModelMentionPanel(
      profiles: _modelProviderProfiles,
      providerModelsByProfileId: _modelOptionsByProfileId,
      query: _activeModelMentionToken?.query ?? '',
      currentSelection: _activeConversationModelOverrideSelection,
      onSelect: (selection) {
        unawaited(
          _applyConversationModelOverride(
            providerProfileId: selection.providerProfileId,
            modelId: selection.modelId,
            displayAsMentionChip: true,
          ),
        );
      },
    );
  }

  _ChatModelOverrideSelection? _effectiveNormalModelSelection(
    _ChatModelOverrideSelection? explicitSelection,
  ) {
    if (explicitSelection != null) {
      return explicitSelection;
    }
    if (_showConversationModelMentionChip) {
      final override = _activeConversationModelOverrideSelection;
      if (override != null) {
        return override;
      }
    }
    return _activeDispatchSceneSelection;
  }

  ProviderModelOption? _findProviderModelOption(
    _ChatModelOverrideSelection selection,
  ) {
    final models =
        _modelOptionsByProfileId[selection.providerProfileId] ??
        const <ProviderModelOption>[];
    for (final model in models) {
      if (model.id == selection.modelId) {
        return model;
      }
    }
    return null;
  }

  ModelProviderProfileSummary? _findProviderProfile(String profileId) {
    for (final profile in _modelProviderProfiles) {
      if (profile.id == profileId) {
        return profile;
      }
    }
    return null;
  }

  Future<ProviderModelOption?> _resolveProviderModelOption(
    _ChatModelOverrideSelection selection,
  ) async {
    final existing = _findProviderModelOption(selection);
    if ((existing?.contextLimit ?? 0) > 0) {
      final manualThreshold = StorageService.getManualModelContextThreshold(
        selection.modelId,
      );
      if (manualThreshold != null &&
          manualThreshold > 0 &&
          manualThreshold != existing!.contextLimit) {
        return existing.copyWith(contextLimit: manualThreshold);
      }
      return existing;
    }
    final profile = _findProviderProfile(selection.providerProfileId);
    if (profile == null) {
      return existing;
    }
    final seed =
        existing ??
        ProviderModelOption(
          id: selection.modelId,
          displayName: selection.modelId,
          ownedBy: 'selection',
        );
    final enriched = await ModelProviderConfigService.enrichModelsForProfile(
      profileId: profile.id,
      providerName: profile.name,
      apiBase: profile.baseUrl,
      models: [seed],
    );
    if (enriched.isEmpty) {
      return existing;
    }
    var resolved = enriched.first;
    final manualThreshold = StorageService.getManualModelContextThreshold(
      selection.modelId,
    );
    if (manualThreshold != null && manualThreshold > 0) {
      resolved = resolved.copyWith(contextLimit: manualThreshold);
    }
    if (!mounted || (resolved.contextLimit ?? 0) <= 0) {
      return resolved;
    }
    setState(() {
      final next = <String, List<ProviderModelOption>>{
        for (final entry in _modelOptionsByProfileId.entries)
          entry.key: List<ProviderModelOption>.from(entry.value),
      };
      final bucket = next.putIfAbsent(
        selection.providerProfileId,
        () => <ProviderModelOption>[],
      );
      final index = bucket.indexWhere((item) => item.id == selection.modelId);
      if (index >= 0) {
        bucket[index] = resolved;
      } else {
        bucket.insert(0, resolved);
      }
      _modelOptionsByProfileId = next;
    });
    return resolved;
  }

  Future<void> _syncActiveNormalConversationPromptTokenThreshold({
    _ChatModelOverrideSelection? selection,
    int? conversationId,
  }) async {
    final targetConversationId =
        conversationId ?? _modeState(ChatPageMode.normal).currentConversationId;
    if (targetConversationId == null || targetConversationId <= 0) {
      return;
    }
    final effectiveSelection = _effectiveNormalModelSelection(selection);
    if (effectiveSelection == null) {
      return;
    }
    final model = await _resolveProviderModelOption(effectiveSelection);
    final contextLimit = model?.contextLimit;
    if (contextLimit == null || contextLimit <= 0) {
      return;
    }
    final currentConversation =
        _runtimeForMode(ChatPageMode.normal)?.conversation ??
        _modeState(ChatPageMode.normal).currentConversation;
    if (currentConversation?.promptTokenThreshold == contextLimit) {
      return;
    }
    final updated =
        await ConversationService.updateConversationPromptTokenThreshold(
          conversationId: targetConversationId,
          promptTokenThreshold: contextLimit,
        );
    if (!updated || !mounted) {
      return;
    }
    final baseConversation = currentConversation;
    if (baseConversation == null) {
      return;
    }
    final nextConversation = baseConversation.copyWith(
      promptTokenThreshold: contextLimit,
    );
    setState(() {
      if (_modeState(ChatPageMode.normal).currentConversation?.id ==
          targetConversationId) {
        _modeState(ChatPageMode.normal).currentConversation = nextConversation;
      }
      final runtime = _runtimeForMode(ChatPageMode.normal);
      if (runtime?.conversation?.id == targetConversationId) {
        runtime!.conversation = nextConversation;
      }
    });
    _syncRuntimeSnapshotForMode(
      ChatPageMode.normal,
      conversation: nextConversation,
    );
  }
}

class _ActiveModelMentionToken {
  final String query;
  final int start;
  final int end;

  const _ActiveModelMentionToken({
    required this.query,
    required this.start,
    required this.end,
  });

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is _ActiveModelMentionToken &&
        other.query == query &&
        other.start == start &&
        other.end == end;
  }

  @override
  int get hashCode => Object.hash(query, start, end);
}

class _ChatModelOverrideSelection {
  final String providerProfileId;
  final String modelId;

  const _ChatModelOverrideSelection({
    required this.providerProfileId,
    required this.modelId,
  });
}

class _ChatModelMentionPanel extends StatefulWidget {
  final List<ModelProviderProfileSummary> profiles;
  final Map<String, List<ProviderModelOption>> providerModelsByProfileId;
  final String query;
  final _ChatModelOverrideSelection? currentSelection;
  final ValueChanged<_ChatModelOverrideSelection> onSelect;

  const _ChatModelMentionPanel({
    required this.profiles,
    required this.providerModelsByProfileId,
    required this.query,
    required this.currentSelection,
    required this.onSelect,
  });

  @override
  State<_ChatModelMentionPanel> createState() => _ChatModelMentionPanelState();
}

class _ChatModelMentionPanelState extends State<_ChatModelMentionPanel> {
  List<ProviderModelOption> _filteredModels(String profileId) {
    final normalizedQuery = widget.query.trim().toLowerCase();
    final models =
        widget.providerModelsByProfileId[profileId] ??
        const <ProviderModelOption>[];
    if (normalizedQuery.isEmpty) {
      return models;
    }
    return models.where((item) {
      final modelId = item.id.toLowerCase();
      final displayName = item.displayName.toLowerCase();
      return modelId.contains(normalizedQuery) ||
          displayName.contains(normalizedQuery);
    }).toList();
  }

  Widget _buildProviderHeader(
    ModelProviderProfileSummary profile,
    int modelCount,
  ) {
    final isCurrentProvider =
        widget.currentSelection?.providerProfileId == profile.id;
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 2, 10, 2),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: isCurrentProvider
              ? (context.isDarkTheme
                    ? Color.lerp(
                        palette.surfaceSecondary.withValues(alpha: 0.46),
                        palette.accentPrimary,
                        0.14,
                      )!
                    : const Color(0xFF2C7FEB).withValues(alpha: 0.10))
              : Colors.transparent,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          children: [
            Expanded(
              child: Text(
                profile.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 12,
                  color: context.isDarkTheme
                      ? palette.textSecondary
                      : const Color(0xFF64748B),
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
            Text(
              '$modelCount',
              style: TextStyle(
                fontSize: 11,
                color: context.isDarkTheme
                    ? palette.textTertiary
                    : const Color(0xFF9AA4B6),
                fontWeight: FontWeight.w600,
              ),
            ),
            if (isCurrentProvider) ...[
              const SizedBox(width: 6),
              Icon(
                Icons.check_circle_rounded,
                size: 13,
                color: context.isDarkTheme
                    ? palette.accentPrimary
                    : const Color(0xFF2C7FEB),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildModelRow({
    required ModelProviderProfileSummary profile,
    required ProviderModelOption item,
  }) {
    final selected =
        widget.currentSelection?.providerProfileId == profile.id &&
        widget.currentSelection?.modelId == item.id;
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 2, 10, 2),
      child: buildConversationModelIdTooltip(
        modelId: item.id,
        child: InkWell(
          onTap: () {
            widget.onSelect(
              _ChatModelOverrideSelection(
                providerProfileId: profile.id,
                modelId: item.id,
              ),
            );
          },
          borderRadius: BorderRadius.circular(12),
          child: Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            decoration: BoxDecoration(
              color: selected
                  ? (context.isDarkTheme
                        ? palette.segmentThumb
                        : const Color(0xFFEAF3FF))
                  : Colors.transparent,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    item.id,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13,
                      color: context.isDarkTheme
                          ? palette.textPrimary
                          : const Color(0xFF1F2937),
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ),
                if (selected)
                  Icon(
                    Icons.check_rounded,
                    size: 15,
                    color: context.isDarkTheme
                        ? palette.accentPrimary
                        : const Color(0xFF2C7FEB),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final visibleProfiles = widget.profiles.where((profile) {
      if (!profile.configured) {
        return false;
      }
      return _filteredModels(profile.id).isNotEmpty;
    }).toList();

    if (visibleProfiles.isEmpty) {
      return const SizedBox.shrink();
    }

    final mediaQuery = MediaQuery.of(context);
    final dynamicMaxHeight =
        (mediaQuery.size.height - mediaQuery.viewInsets.bottom - 180)
            .clamp(150.0, 240.0)
            .toDouble();

    return ConstrainedBox(
      constraints: BoxConstraints(maxHeight: dynamicMaxHeight),
      child: Scrollbar(
        child: ListView.builder(
          padding: const EdgeInsets.only(bottom: 6),
          itemCount: visibleProfiles.length,
          itemBuilder: (context, index) {
            final profile = visibleProfiles[index];
            final models = _filteredModels(profile.id);
            return Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                _buildProviderHeader(profile, models.length),
                if (models.isEmpty)
                  Padding(
                    padding: EdgeInsets.fromLTRB(12, 4, 12, 8),
                    child: Text(
                      LegacyTextLocalizer.localize('没有匹配的模型'),
                      style: TextStyle(
                        fontSize: 12,
                        color: context.isDarkTheme
                            ? context.omniPalette.textTertiary
                            : const Color(0xFF94A3B8),
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  )
                else
                  Column(
                    mainAxisSize: MainAxisSize.min,
                    children: models
                        .map(
                          (item) =>
                              _buildModelRow(profile: profile, item: item),
                        )
                        .toList(),
                  ),
                if (index != visibleProfiles.length - 1)
                  const SizedBox(height: 4),
              ],
            );
          },
        ),
      ),
    );
  }
}

// DismissOverlayOnKeyboardHide 已提取到 lib/widgets/glass_popup.dart,
// 给 chat_input_area.dart 里的 context-usage tooltip 一起复用。
// PR #410 引入的 shouldDismissOnKeyboardHide 一次性豁免 + bottomInset<=0 复位
// 修复也都搬到了那里;调用方通过 [OverlayGlassPopupHandle.keepOpenOnNextKeyboardHide]
// 触发豁免(本文件 _openConversationModelSelector 的 onSearchSubmitted 即用法)。
