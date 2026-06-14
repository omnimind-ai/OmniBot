part of 'chat_page.dart';

mixin _ChatPageModelContextMixin on _ChatPageStateBase {
  @override
  Future<void> _loadNormalChatModelContext() async {
    try {
      final results = await Future.wait<dynamic>([
        ModelProviderConfigService.loadModelGroups(),
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

    final normalConversationId =
        _currentConversationIdByMode[ChatPageMode.normal];
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
    final normalConversationId =
        _currentConversationIdByMode[ChatPageMode.normal];

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
    final normalConversationId =
        _currentConversationIdByMode[ChatPageMode.normal];
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
    final normalConversationId =
        _currentConversationIdByMode[ChatPageMode.normal];
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
    for (final item in _modelOptionsByProfileId[override.providerProfileId] ??
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

  void _removeConversationModelSelectorOverlay() {
    _suppressNextOutsideTapKeyboardHide = false;
    final entry = _conversationModelSelectorOverlayEntry;
    if (entry == null) {
      return;
    }
    _conversationModelSelectorOverlayEntry = null;
    entry.remove();
  }

  void _showConversationModelSelectorOverlay({
    required Rect anchorRect,
    required double popupWidth,
    required double popupMaxHeight,
  }) {
    _removeConversationModelSelectorOverlay();
    final overlay = Overlay.of(context, rootOverlay: true);
    _conversationModelSelectorOverlayEntry = OverlayEntry(
      builder: (overlayContext) {
        return Material(
          color: Colors.transparent,
          child: Stack(
            children: [
              Positioned.fill(
                child: GestureDetector(
                  behavior: HitTestBehavior.translucent,
                  onTap: _removeConversationModelSelectorOverlay,
                  child: const SizedBox.expand(),
                ),
              ),
              GlassPopupAnchorLayout(
                anchor: anchorRect,
                child: _ConversationModelSelectorContent(
                  width: popupWidth,
                  maxHeight: popupMaxHeight,
                  profiles: _modelProviderProfiles,
                  providerModelsByProfileId: _modelOptionsByProfileId,
                  currentSelection: _activeDispatchSceneSelection,
                  onSelect: (selected) {
                    _removeConversationModelSelectorOverlay();
                    unawaited(
                      _applyDispatchSceneModelSelection(
                        providerProfileId: selected.providerProfileId,
                        modelId: selected.modelId,
                      ),
                    );
                  },
                ),
              ),
            ],
          ),
        );
      },
    );
    overlay.insert(_conversationModelSelectorOverlayEntry!);
  }

  @override
  Future<void> _openConversationModelSelector(
    BuildContext anchorContext,
  ) async {
    if (_activeMode != ChatPageMode.normal) {
      return;
    }
    if (_showSlashCommandPanel ||
        _showModelMentionPanel ||
        _openClawPanelExpanded) {
      setState(() {
        _showSlashCommandPanel = false;
        _showModelMentionPanel = false;
        _openClawPanelExpanded = false;
      });
    }
    if (!_hasSelectableNormalChatModels) {
      return;
    }
    _suppressNextOutsideTapKeyboardHide = true;
    // Keep the active input focus so the composer does not drop before the
    // popup is anchored from the current button position.
    final anchorBox = anchorContext.findRenderObject() as RenderBox?;
    final anchorRect = glassPopupAnchorFromContext(anchorContext);
    if (anchorBox == null || !anchorBox.hasSize || anchorRect == null) {
      _suppressNextOutsideTapKeyboardHide = false;
      return;
    }
    final popupWidth = math
        .max(260.0, anchorBox.size.width)
        .clamp(260.0, 320.0)
        .toDouble();
    const popupMaxHeight = 360.0;
    _showConversationModelSelectorOverlay(
      anchorRect: anchorRect,
      popupWidth: popupWidth,
      popupMaxHeight: popupMaxHeight,
    );
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
    final isOmniInferLocalModel = localModelFeature.isBuiltinLocalProvider(
      providerProfileId,
    );
    if (isOmniInferLocalModel &&
        activeConversationModeValue != ConversationMode.chatOnly) {
      _dispatchSceneModelSelectionSerial++;
      showToast(
        LegacyTextLocalizer.localize('本地模型仅支持纯聊天模式，请开启新的纯聊天对话后再使用本地模型'),
        type: ToastType.warning,
      );
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
      if (!isOmniInferLocalModel) {
        showToast(
          LegacyTextLocalizer.localize('Agent 模型已切换到 $modelId'),
          type: ToastType.success,
        );
        return;
      }

      final loadingToast = AppToast.loading(
        LegacyTextLocalizer.localize('模型加载中...'),
      );
      try {
        await _waitForLocalModelLoadingStatusFrame();
        if (!mounted || selectionSerial != _dispatchSceneModelSelectionSerial) {
          return;
        }
        final result = await localModelFeature.preloadModelIfNeeded(
          providerProfileId: providerProfileId,
          modelId: modelId,
        );
        if (!mounted ||
            selectionSerial != _dispatchSceneModelSelectionSerial ||
            result == null) {
          return;
        }
        if (result['cancelled'] == true) return;
        if (result['success'] == true) {
          showToast(
            LegacyTextLocalizer.localize('模型加载完成'),
            type: ToastType.success,
          );
        } else {
          final error = (result['error'] ?? '').toString().trim();
          showToast(
            LegacyTextLocalizer.localize(
              error.isEmpty ? '模型加载失败' : '模型加载失败：$error',
            ),
            type: ToastType.error,
          );
        }
      } catch (e) {
        if (!mounted || selectionSerial != _dispatchSceneModelSelectionSerial) {
          return;
        }
        showToast(
          LegacyTextLocalizer.localize('模型加载失败：$e'),
          type: ToastType.error,
        );
      } finally {
        loadingToast.dismiss();
      }
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

  Future<void> _waitForLocalModelLoadingStatusFrame() async {
    await SchedulerBinding.instance.endOfFrame;
    await Future<void>.delayed(Duration.zero);
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
        conversationId ?? _currentConversationIdByMode[ChatPageMode.normal];
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
        _currentConversationByMode[ChatPageMode.normal];
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
      if (_currentConversationByMode[ChatPageMode.normal]?.id ==
          targetConversationId) {
        _currentConversationByMode[ChatPageMode.normal] = nextConversation;
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

Widget _buildChatModelIdTooltip({
  required String modelId,
  required Widget child,
}) {
  return Tooltip(
    message: modelId,
    triggerMode: TooltipTriggerMode.longPress,
    waitDuration: Duration.zero,
    showDuration: const Duration(seconds: 3),
    preferBelow: false,
    textAlign: TextAlign.start,
    constraints: const BoxConstraints(maxWidth: 320),
    child: child,
  );
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
          color: context.isDarkTheme
              ? palette.surfaceSecondary
              : const Color(0xFFF4F6FA),
          borderRadius: BorderRadius.circular(12),
          border: context.isDarkTheme
              ? Border.all(color: palette.borderSubtle)
              : null,
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
      child: _buildChatModelIdTooltip(
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
                  : (context.isDarkTheme
                        ? palette.surfaceSecondary
                        : const Color(0xFFF8FAFD)),
              borderRadius: BorderRadius.circular(12),
              border: context.isDarkTheme
                  ? Border.all(color: palette.borderSubtle)
                  : null,
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

class _ConversationModelSelectorContent extends StatefulWidget {
  const _ConversationModelSelectorContent({
    required this.width,
    required this.maxHeight,
    required this.profiles,
    required this.providerModelsByProfileId,
    required this.currentSelection,
    this.onSelect,
  });

  final double width;
  final double maxHeight;
  final List<ModelProviderProfileSummary> profiles;
  final Map<String, List<ProviderModelOption>> providerModelsByProfileId;
  final _ChatModelOverrideSelection? currentSelection;
  final ValueChanged<_ChatModelOverrideSelection>? onSelect;

  @override
  State<_ConversationModelSelectorContent> createState() =>
      _ConversationModelSelectorContentState();
}

class _ConversationModelSelectorContentState
    extends State<_ConversationModelSelectorContent> {
  static const Map<String, String> _kBackendDisplayNames = {
    'llama.cpp': 'llama.cpp',
    'omniinfer-mnn': 'MNN',
    'llm': 'NPU',
    'manual': '手动添加',
  };
  static const List<String> _kBackendOrder = [
    'llama.cpp',
    'omniinfer-mnn',
    'manual',
  ];

  final TextEditingController _searchController = TextEditingController();
  final ScrollController _listScrollController = ScrollController();
  final GlobalKey _selectedModelRowKey = GlobalKey();
  late final Set<String> _expandedProfileIds;
  late final Set<String> _expandedBackendKeys;

  // 估算滚动定位用的行高常量（与下方各行的固定 padding/字号对应）。
  static const double _kProfileHeaderExtent = 43.0;
  static const double _kModelRowExtent = 43.0;
  static const double _kBackendHeaderExtent = 28.0;
  static const double _kProfileGapExtent = 6.0;

  bool get _hasSearchQuery => _searchController.text.trim().isNotEmpty;

  @override
  void initState() {
    super.initState();
    _expandedProfileIds = <String>{
      if (widget.currentSelection != null)
        widget.currentSelection!.providerProfileId,
    };
    if (_expandedProfileIds.isEmpty && widget.profiles.isNotEmpty) {
      _expandedProfileIds.add(widget.profiles.first.id);
    }
    _expandedBackendKeys = <String>{};
    if (widget.currentSelection != null) {
      final pid = widget.currentSelection!.providerProfileId;
      if (localModelFeature.isBuiltinLocalProvider(pid)) {
        final models =
            widget.providerModelsByProfileId[pid] ??
            const <ProviderModelOption>[];
        for (final m in models) {
          if (m.id == widget.currentSelection!.modelId &&
              m.ownedBy != null &&
              m.ownedBy!.isNotEmpty) {
            _expandedBackendKeys.add('$pid::${m.ownedBy}');
            break;
          }
        }
      }
    }
    _searchController.addListener(() {
      setState(() {});
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _autoScrollToSelectedModel();
      }
    });
  }

  @override
  void dispose() {
    _searchController.dispose();
    _listScrollController.dispose();
    super.dispose();
  }

  /// 打开弹窗后自动滚动到当前选中的模型行。
  ///
  /// 列表为懒加载（ListView.builder），选中行可能尚未构建，因此先按固定行高
  /// 估算偏移跳转到目标附近，待目标行构建后再用 ensureVisible 精确对齐。
  void _autoScrollToSelectedModel() {
    final selection = widget.currentSelection;
    if (selection == null || !_listScrollController.hasClients) {
      return;
    }
    final profiles = _visibleProfiles;
    var offset = 0.0;
    var found = false;
    for (final profile in profiles) {
      offset += _kProfileHeaderExtent;
      final isTargetProfile = profile.id == selection.providerProfileId;
      final expanded = _isExpanded(profile.id);
      if (expanded) {
        if (_needsBackendGrouping(profile.id)) {
          final groups = _groupByBackend(profile.id);
          for (final backend in _sortedBackendKeys(groups.keys)) {
            offset += _kBackendHeaderExtent;
            final models = groups[backend]!;
            if (!_isBackendExpanded(profile.id, backend)) {
              continue;
            }
            final index = isTargetProfile
                ? models.indexWhere((m) => m.id == selection.modelId)
                : -1;
            if (index >= 0) {
              offset += index * _kModelRowExtent;
              found = true;
              break;
            }
            offset += models.length * _kModelRowExtent;
          }
        } else {
          final models = _filteredModels(profile.id);
          final index = isTargetProfile
              ? models.indexWhere((m) => m.id == selection.modelId)
              : -1;
          if (index >= 0) {
            offset += index * _kModelRowExtent;
            found = true;
          } else {
            offset += models.length * _kModelRowExtent;
          }
        }
      }
      if (found || isTargetProfile) {
        found = found || isTargetProfile;
        break;
      }
      offset += _kProfileGapExtent;
    }
    if (!found) {
      return;
    }
    final position = _listScrollController.position;
    if (position.maxScrollExtent <= 0) {
      return;
    }
    // 让目标行落在视口上方约 1/3 处。
    final target = (offset - position.viewportDimension * 0.35).clamp(
      0.0,
      position.maxScrollExtent,
    );
    _listScrollController.jumpTo(target);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      final rowContext = _selectedModelRowKey.currentContext;
      if (rowContext != null) {
        Scrollable.ensureVisible(
          rowContext,
          alignment: 0.35,
          duration: const Duration(milliseconds: 160),
          curve: Curves.easeOutCubic,
        );
      }
    });
  }

  List<ProviderModelOption> _filteredModels(String profileId) {
    final query = _searchController.text.trim().toLowerCase();
    final models = widget.providerModelsByProfileId[profileId] ?? const [];
    if (query.isEmpty) {
      return models;
    }
    return models.where((item) {
      final modelId = item.id.toLowerCase();
      final displayName = item.displayName.toLowerCase();
      return modelId.contains(query) || displayName.contains(query);
    }).toList();
  }

  List<ModelProviderProfileSummary> get _visibleProfiles {
    final configuredProfiles = widget.profiles
        .where((profile) => profile.configured)
        .toList();
    if (!_hasSearchQuery) {
      return configuredProfiles;
    }
    return configuredProfiles.where((profile) {
      return _filteredModels(profile.id).isNotEmpty;
    }).toList();
  }

  bool _isExpanded(String profileId) {
    if (_hasSearchQuery) {
      return true;
    }
    return _expandedProfileIds.contains(profileId);
  }

  bool _needsBackendGrouping(String profileId) {
    return localModelFeature.isBuiltinLocalProvider(profileId);
  }

  Map<String, List<ProviderModelOption>> _groupByBackend(String profileId) {
    final models = _filteredModels(profileId);
    final groups = <String, List<ProviderModelOption>>{};
    for (final model in models) {
      final key = (model.ownedBy != null && model.ownedBy!.isNotEmpty)
          ? model.ownedBy!
          : 'other';
      groups.putIfAbsent(key, () => []).add(model);
    }
    return groups;
  }

  List<String> _sortedBackendKeys(Iterable<String> keys) {
    final list = keys.toList();
    list.sort((a, b) {
      final ia = _kBackendOrder.indexOf(a);
      final ib = _kBackendOrder.indexOf(b);
      return (ia < 0 ? 999 : ia).compareTo(ib < 0 ? 999 : ib);
    });
    return list;
  }

  bool _isBackendExpanded(String profileId, String backend) {
    if (_hasSearchQuery) return true;
    return _expandedBackendKeys.contains('$profileId::$backend');
  }

  Widget _buildSearchRow() {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 8),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        decoration: BoxDecoration(
          color: isDark
              ? palette.surfaceSecondary.withValues(alpha: 0.58)
              : Colors.white.withValues(alpha: 0.38),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: isDark
                ? palette.borderSubtle.withValues(alpha: 0.62)
                : Colors.white.withValues(alpha: 0.58),
          ),
        ),
        child: Row(
          children: [
            Icon(
              Icons.search,
              size: 18,
              color: isDark ? palette.textTertiary : const Color(0xFF9AA4B6),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: TextField(
                controller: _searchController,
                autofocus: false,
                scrollPadding: EdgeInsets.zero,
                cursorColor: isDark ? palette.accentPrimary : null,
                style: TextStyle(
                  fontSize: 13,
                  color: isDark ? palette.textPrimary : const Color(0xFF1F2937),
                  fontWeight: FontWeight.w500,
                ),
                decoration: InputDecoration(
                  isDense: true,
                  filled: false,
                  fillColor: Colors.transparent,
                  hintText: LegacyTextLocalizer.localize('搜索模型 ID'),
                  hintStyle: TextStyle(
                    fontSize: 13,
                    color: isDark
                        ? palette.textTertiary
                        : const Color(0xFF9AA4B6),
                    fontWeight: FontWeight.w500,
                  ),
                  border: InputBorder.none,
                  focusedBorder: InputBorder.none,
                  enabledBorder: InputBorder.none,
                  contentPadding: EdgeInsets.zero,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildProfileHeader(ModelProviderProfileSummary profile) {
    final expanded = _isExpanded(profile.id);
    final models = _filteredModels(profile.id);
    final isSelectedProvider =
        widget.currentSelection?.providerProfileId == profile.id;
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;

    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 2, 10, 2),
      child: InkWell(
        onTap: () {
          if (_hasSearchQuery) {
            return;
          }
          setState(() {
            if (expanded) {
              _expandedProfileIds.remove(profile.id);
            } else {
              _expandedProfileIds.add(profile.id);
            }
          });
        },
        borderRadius: BorderRadius.circular(12),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          decoration: BoxDecoration(
            color: isSelectedProvider
                ? (isDark
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
                    color: isDark
                        ? palette.textSecondary
                        : const Color(0xFF64748B),
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
              Text(
                '${models.length}',
                style: TextStyle(
                  fontSize: 11,
                  color: isDark
                      ? palette.textTertiary
                      : const Color(0xFF9AA4B6),
                  fontWeight: FontWeight.w600,
                ),
              ),
              if (isSelectedProvider) ...[
                const SizedBox(width: 6),
                Icon(
                  Icons.check_circle_rounded,
                  size: 13,
                  color: isDark
                      ? palette.accentPrimary
                      : const Color(0xFF2C7FEB),
                ),
              ],
              const SizedBox(width: 6),
              Icon(
                _hasSearchQuery
                    ? Icons.unfold_more_rounded
                    : expanded
                    ? Icons.expand_less_rounded
                    : Icons.expand_more_rounded,
                size: 16,
                color: isDark ? palette.textTertiary : const Color(0xFF94A3B8),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildModelRow({
    required ModelProviderProfileSummary profile,
    required ProviderModelOption model,
  }) {
    final selected =
        widget.currentSelection?.providerProfileId == profile.id &&
        widget.currentSelection?.modelId == model.id;
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    return Padding(
      key: selected ? _selectedModelRowKey : null,
      padding: const EdgeInsets.fromLTRB(10, 2, 10, 2),
      child: _buildChatModelIdTooltip(
        modelId: model.id,
        child: InkWell(
          onTap: () {
            final selection = _ChatModelOverrideSelection(
              providerProfileId: profile.id,
              modelId: model.id,
            );
            final onSelect = widget.onSelect;
            if (onSelect != null) {
              onSelect(selection);
              return;
            }
            Navigator.of(context).pop(selection);
          },
          borderRadius: BorderRadius.circular(12),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            decoration: BoxDecoration(
              color: selected
                  ? (isDark
                        ? Color.lerp(
                            palette.surfaceSecondary.withValues(alpha: 0.48),
                            palette.accentPrimary,
                            0.18,
                          )!
                        : const Color(0xFF2C7FEB).withValues(alpha: 0.12))
                  : Colors.transparent,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(
              children: [
                ProviderVendorIcon(
                  vendor: ModelVendorCatalog.resolve(
                    model.id,
                    ownedBy: model.ownedBy,
                    providerId: model.modelsDevProviderId,
                  ),
                  size: 14,
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    model.id,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13,
                      color: isDark
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
                    color: isDark
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

  Widget _buildBackendSubHeader(
    String profileId,
    String backend,
    int modelCount,
  ) {
    final expanded = _isBackendExpanded(profileId, backend);
    final displayName = _kBackendDisplayNames[backend] ?? backend;
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 1, 10, 1),
      child: InkWell(
        onTap: _hasSearchQuery
            ? null
            : () {
                final key = '$profileId::$backend';
                setState(() {
                  if (_expandedBackendKeys.contains(key)) {
                    _expandedBackendKeys.remove(key);
                  } else {
                    _expandedBackendKeys.add(key);
                  }
                });
              },
        borderRadius: BorderRadius.circular(8),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
          decoration: BoxDecoration(borderRadius: BorderRadius.circular(8)),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  displayName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 11,
                    color: isDark
                        ? palette.textTertiary
                        : const Color(0xFF94A3B8),
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
              Text(
                '$modelCount',
                style: TextStyle(
                  fontSize: 10,
                  color: isDark
                      ? palette.textTertiary
                      : const Color(0xFFB0BAC9),
                  fontWeight: FontWeight.w500,
                ),
              ),
              const SizedBox(width: 4),
              Icon(
                _hasSearchQuery
                    ? Icons.unfold_more_rounded
                    : expanded
                    ? Icons.expand_less_rounded
                    : Icons.expand_more_rounded,
                size: 14,
                color: isDark ? palette.textTertiary : const Color(0xFFB0BAC9),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildBackendGroupedModels(ModelProviderProfileSummary profile) {
    final groups = _groupByBackend(profile.id);
    if (groups.isEmpty) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(12, 4, 12, 8),
        child: Text(
          LegacyTextLocalizer.localize('该 Provider 暂无可选模型'),
          style: TextStyle(
            fontSize: 12,
            color: context.isDarkTheme
                ? context.omniPalette.textTertiary
                : const Color(0xFF94A3B8),
          ),
        ),
      );
    }
    final sortedKeys = _sortedBackendKeys(groups.keys);
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (final backend in sortedKeys) ...[
          _buildBackendSubHeader(profile.id, backend, groups[backend]!.length),
          if (_isBackendExpanded(profile.id, backend))
            ...groups[backend]!.map(
              (model) => _buildModelRow(profile: profile, model: model),
            ),
        ],
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final mediaQuery = MediaQuery.of(context);
    final dynamicMaxHeight =
        (mediaQuery.size.height - mediaQuery.viewInsets.bottom - 96)
            .clamp(220.0, widget.maxHeight)
            .toDouble();
    final configuredProfiles = widget.profiles
        .where((profile) => profile.configured)
        .toList();
    final visibleProfiles = _visibleProfiles;
    return SizedBox(
      width: widget.width,
      child: OmniGlassPanel(
        width: widget.width,
        borderRadius: BorderRadius.circular(18),
        child: Material(
          color: Colors.transparent,
          child: ConstrainedBox(
            constraints: BoxConstraints(maxHeight: dynamicMaxHeight),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                _buildSearchRow(),
                if (configuredProfiles.isEmpty)
                  Padding(
                    padding: EdgeInsets.all(16),
                    child: Text(
                      LegacyTextLocalizer.localize('请先在模型提供商页配置 Provider'),
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 12,
                        color: context.isDarkTheme
                            ? palette.textTertiary
                            : const Color(0xFF94A3B8),
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  )
                else if (visibleProfiles.isEmpty)
                  Padding(
                    padding: EdgeInsets.all(16),
                    child: Text(
                      LegacyTextLocalizer.localize('没有匹配的模型'),
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        fontSize: 12,
                        color: context.isDarkTheme
                            ? palette.textTertiary
                            : const Color(0xFF94A3B8),
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  )
                else
                  Flexible(
                    child: Scrollbar(
                      controller: _listScrollController,
                      child: ListView.builder(
                        controller: _listScrollController,
                        padding: const EdgeInsets.only(bottom: 8),
                        itemCount: visibleProfiles.length,
                        itemBuilder: (context, index) {
                          final profile = visibleProfiles[index];
                          final expanded = _isExpanded(profile.id);
                          final models = _filteredModels(profile.id);
                          return Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              _buildProfileHeader(profile),
                              if (expanded)
                                if (_needsBackendGrouping(profile.id))
                                  _buildBackendGroupedModels(profile)
                                else if (models.isEmpty)
                                  Padding(
                                    padding: EdgeInsets.fromLTRB(12, 4, 12, 8),
                                    child: Text(
                                      LegacyTextLocalizer.localize(
                                        '该 Provider 暂无可选模型',
                                      ),
                                      style: TextStyle(
                                        fontSize: 12,
                                        color: context.isDarkTheme
                                            ? palette.textTertiary
                                            : const Color(0xFF94A3B8),
                                      ),
                                    ),
                                  )
                                else
                                  Column(
                                    children: models
                                        .map(
                                          (item) => _buildModelRow(
                                            profile: profile,
                                            model: item,
                                          ),
                                        )
                                        .toList(),
                                  ),
                              if (index != visibleProfiles.length - 1)
                                const SizedBox(height: 6),
                            ],
                          );
                        },
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
