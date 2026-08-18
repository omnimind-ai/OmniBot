part of 'chat_input_area.dart';

extension _ChatInputAgentControls on _ChatInputAreaStateBase {
  bool get _shouldShowAgentPermissionSelector =>
      widget.agentPermissionMode != null &&
      widget.onAgentPermissionModeChanged != null;

  bool get _shouldShowAgentRunSettingsSelector =>
      widget.agentRunSettings != null &&
      widget.onAgentRunSettingsChanged != null;

  bool get _shouldShowModelPicker => widget.modelPickerSettings != null;

  Widget _buildModelPickerButton({required bool compact}) {
    final settings = widget.modelPickerSettings!;
    final palette = context.omniPalette;
    final modelId = settings.modelId.trim();
    final english = Localizations.localeOf(context).languageCode == 'en';
    final selectedColor = palette.accentPrimary;
    final enabled = settings.hasSelectableModels;
    final vendor = modelId.isEmpty ? null : ModelVendorCatalog.resolve(modelId);
    final buttonKey = settings.anchorKey ?? _modelPickerButtonKey;

    Future<void> openPicker() async {
      final anchorContext = buttonKey.currentContext;
      if (anchorContext == null || !enabled) {
        return;
      }
      _modelPickerSpinController.forward(from: 0);
      await Future<void>.sync(() => settings.onOpen(anchorContext));
    }

    return TextFieldTapRegion(
      child: SizedBox(
        key: buttonKey,
        width: compact ? 24 : 28,
        height: compact ? 24 : 28,
        child: Listener(
          behavior: HitTestBehavior.opaque,
          onPointerDown: (_) => settings.onPointerDown?.call(),
      child: Tooltip(
        message: modelId.isEmpty
                ? (english ? 'Select Provider / model' : '选择 Provider / 模型')
                : modelId,
            waitDuration: const Duration(milliseconds: 400),
            child: InkWell(
              key: const ValueKey('chat-input-model-picker-button'),
              borderRadius: BorderRadius.circular(8),
              onTap: enabled ? openPicker : null,
              child: Center(
                child: RotationTransition(
                  turns: CurvedAnimation(
                    parent: _modelPickerSpinController,
                    curve: Curves.easeOutCubic,
                  ),
                  child: ProviderVendorIcon(
                    vendor: vendor,
                    size: compact ? 20 : 22,
                    disabled: !enabled,
                    forceMonochrome: true,
                    monochromeColor: enabled
                        ? selectedColor
                        : palette.textTertiary.withValues(alpha: 0.82),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildAgentRunSettingsButton({required bool compact}) {
    final settings = widget.agentRunSettings!;
    final palette = context.omniPalette;
    final modelId = settings.modelId.trim();
    final effort = settings.reasoningEffort.trim();
    final agentName = settings.agentName.trim();
    final english = Localizations.localeOf(context).languageCode == 'en';
    final selectedColor = palette.accentPrimary;
    final menuTextColor = context.isDarkTheme
        ? palette.textPrimary
        : const Color(0xFF26364D);

    final buttonKey = _agentRunSettingsButtonKey;

    Future<void> openMenu() async {
      if (_agentRunSettingsMenuHandle != null ||
          !_composerStateMachine.beginPopupOpening(
            ChatComposerPopup.agentRunSettings,
          )) {
        return;
      }
      if (buttonKey.currentContext == null) {
        _composerStateMachine.popupClosed(ChatComposerPopup.agentRunSettings);
        return;
      }
      final opened = widget.onAgentRunSettingsOpened;
      if (opened != null) {
        unawaited(
          Future<void>.sync(
            opened,
          ).catchError((Object error, StackTrace stackTrace) {}),
        );
      }
      if (!mounted) {
        _composerStateMachine.popupClosed(ChatComposerPopup.agentRunSettings);
        return;
      }
      final anchorContext = buttonKey.currentContext;
      if (anchorContext == null || !anchorContext.mounted) {
        _composerStateMachine.popupClosed(ChatComposerPopup.agentRunSettings);
        return;
      }
      final anchor = glassPopupAnchorFromContext(anchorContext);
      if (anchor == null) {
        _composerStateMachine.popupClosed(ChatComposerPopup.agentRunSettings);
        return;
      }
      final refreshedSettings = widget.agentRunSettings ?? settings;
      final refreshedModelId = refreshedSettings.modelId.trim();
      final refreshedEffort = refreshedSettings.reasoningEffort.trim();
      final modelOptions = _agentRunSettingsOptions(
        current: refreshedModelId,
        options: refreshedSettings.modelOptions,
      );
      final effortOptions = refreshedSettings.reasoningEffortOptions;
      final disabledModelLabel = refreshedSettings.isLoadingModels
          ? (english ? 'Loading...' : '正在获取模型...')
          : (refreshedSettings.modelListError?.trim().isNotEmpty ?? false)
          ? (english ? 'Load failed' : '模型获取失败')
          : (english ? 'No models available' : '未获取到可用模型');
      final handle = showOverlayGlassPopup<_AgentRunSettingsMenuAction>(
        context: anchorContext,
        anchor: anchor,
        preferBelow: false,
        reverseTransitionDuration: Duration.zero,
        dismissOnBackButton: false,
        builder: (handle) => _AgentRunSettingsMenuContent(
          width: 280,
          maxHeight: 420,
          modelHeader: english ? 'Model' : '模型',
          reasoningHeader: english ? 'Reasoning' : '推理强度',
          searchHint: english ? 'Search models' : '搜索模型',
          noMatchesLabel: english ? 'No matching models' : '没有匹配的模型',
          emptyModelsLabel: disabledModelLabel,
          modelOptions: modelOptions,
          currentModelId: refreshedModelId,
          reasoningOptions: effortOptions,
          currentReasoningEffort: refreshedEffort,
          effortLabelBuilder: _agentReasoningEffortLabel,
          selectedColor: selectedColor,
          textColor: menuTextColor,
          onSelectModel: (modelId) {
            unawaited(
              handle.dismiss(_AgentRunSettingsMenuAction.model(modelId)),
            );
          },
          onSelectReasoning: (effort) {
            unawaited(
              handle.dismiss(_AgentRunSettingsMenuAction.effort(effort)),
            );
          },
        ),
      );
      _agentRunSettingsMenuHandle = handle;
      _composerStateMachine.popupOpened(ChatComposerPopup.agentRunSettings);
      try {
        final action = await handle.future;
        if (action == null) return;
        final changed = widget.onAgentRunSettingsChanged;
        if (changed == null) return;
        unawaited(
          Future<void>.sync(() {
            if (action.kind == _AgentRunSettingsMenuKind.model) {
              return changed(modelId: action.value);
            }
            return changed(reasoningEffort: action.value);
          }),
        );
      } finally {
        if (_agentRunSettingsMenuHandle == handle) {
          _agentRunSettingsMenuHandle = null;
          if (mounted) {
            _composerStateMachine.popupClosed(
              ChatComposerPopup.agentRunSettings,
            );
          }
        }
      }
    }

    return TextFieldTapRegion(
      child: SizedBox(
        key: buttonKey,
        width: compact ? 24 : 28,
        height: compact ? 24 : 28,
        child: Tooltip(
          message: [
            if (modelId.isNotEmpty) modelId,
            if (agentName.isNotEmpty) agentName,
            if (effort.isNotEmpty) _agentReasoningEffortLabel(effort),
          ].join(' · '),
          waitDuration: const Duration(milliseconds: 400),
          child: InkWell(
            key: const ValueKey('chat-input-agent-run-settings-button'),
            borderRadius: BorderRadius.circular(8),
            onTap: openMenu,
            child: AnimatedContainer(
              duration: _buttonAnimationDuration,
              curve: _buttonAnimationCurve,
              width: compact ? 24 : 28,
              height: compact ? 24 : 28,
              alignment: Alignment.center,
              child: RepaintBoundary(
                child: AnimatedSwitcher(
                  duration: const Duration(milliseconds: 240),
                  reverseDuration: const Duration(milliseconds: 190),
                  switchInCurve: Curves.easeOutCubic,
                  switchOutCurve: Curves.easeInCubic,
                  transitionBuilder: (child, animation) {
                    return FadeTransition(
                      opacity: animation,
                      child: ScaleTransition(
                        scale: Tween<double>(
                          begin: 0.84,
                          end: 1,
                        ).animate(animation),
                        child: child,
                      ),
                    );
                  },
                  child: Icon(
                    _composerStateMachine.value.isPopupOpen(
                          ChatComposerPopup.agentRunSettings,
                        )
                        ? LucideIcons.packageOpen
                        : LucideIcons.package,
                    key: ValueKey(
                      _composerStateMachine.value.isPopupOpen(
                            ChatComposerPopup.agentRunSettings,
                          )
                          ? 'chat-input-agent-run-settings-package-open-icon'
                          : 'chat-input-agent-run-settings-package-icon',
                    ),
                    size: compact ? 20 : 22,
                    color: selectedColor,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  String _agentReasoningEffortLabel(String effort, {bool compact = false}) {
    final normalized = effort.trim().toLowerCase();
    final english = Localizations.localeOf(context).languageCode == 'en';
    return switch (normalized) {
      'none' || 'no' => english ? 'No reasoning' : (compact ? '无' : '无推理'),
      'minimal' || 'min' => english ? 'Minimal' : '极低',
      'low' => english ? 'Low' : '低',
      'medium' || 'med' => english ? 'Medium' : '中',
      'high' => english ? 'High' : '高',
      'xhigh' ||
      'extra_high' ||
      'extra-high' ||
      'very_high' ||
      'very-high' => english ? 'XHigh' : '超高',
      _ => effort.trim().isEmpty ? (english ? 'Reasoning' : '推理') : effort,
    };
  }

  List<String> _agentRunSettingsOptions({
    required String current,
    required List<String> options,
  }) {
    final seen = <String>{};
    final result = <String>[];
    void add(String value) {
      final normalized = value.trim();
      if (normalized.isEmpty || !seen.add(normalized)) {
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

  Widget _buildAgentPermissionButton({required double iconSize}) {
    final selected =
        widget.agentPermissionMode ?? AgentPermissionMode.fullAccess;
    final palette = context.omniPalette;
    final selectedColor = context.isDarkTheme
        ? palette.accentPrimary
        : const Color(0xFF2F65D9);
    final inactiveColor = context.isDarkTheme
        ? palette.textSecondary
        : const Color(0xFF5E6C84);

    final buttonKey = _agentPermissionButtonKey;

    Future<void> openMenu() async {
      if (_agentPermissionMenuHandle != null ||
          !_composerStateMachine.beginPopupOpening(
            ChatComposerPopup.agentPermission,
          )) {
        return;
      }
      final anchorContext = buttonKey.currentContext;
      if (anchorContext == null) {
        _composerStateMachine.popupClosed(ChatComposerPopup.agentPermission);
        return;
      }
      final anchor = glassPopupAnchorFromContext(anchorContext);
      if (anchor == null) {
        _composerStateMachine.popupClosed(ChatComposerPopup.agentPermission);
        return;
      }
      final handle = showOverlayGlassPopup<AgentPermissionMode>(
        context: anchorContext,
        anchor: anchor,
        preferBelow: false,
        reverseTransitionDuration: Duration.zero,
        dismissOnBackButton: false,
        builder: (handle) => _AgentPermissionGlassMenuContent(
          width: 196,
          selected: selected,
          selectedColor: selectedColor,
          inactiveColor: inactiveColor,
          textColor: context.isDarkTheme
              ? palette.textPrimary
              : const Color(0xFF232D3D),
          options: [
            for (final mode in widget.agentPermissionModes)
              _AgentPermissionOptionData(
                mode: mode,
                label: _agentPermissionLabel(mode),
                iconAsset: _agentPermissionIconAsset(mode),
              ),
          ],
          onSelect: (mode) => unawaited(handle.dismiss(mode)),
        ),
      );
      _agentPermissionMenuHandle = handle;
      _composerStateMachine.popupOpened(ChatComposerPopup.agentPermission);
      try {
        final mode = await handle.future;
        if (mode == null) return;
        await widget.onAgentPermissionModeChanged?.call(mode);
      } finally {
        if (_agentPermissionMenuHandle == handle) {
          _agentPermissionMenuHandle = null;
          if (mounted) {
            _composerStateMachine.popupClosed(
              ChatComposerPopup.agentPermission,
            );
          }
        }
      }
    }

    return TextFieldTapRegion(
      child: Tooltip(
        message: _agentPermissionTooltip(),
        waitDuration: const Duration(milliseconds: 400),
        child: InkWell(
          key: const ValueKey('chat-input-agent-permission-button'),
          borderRadius: BorderRadius.circular(999),
          onTap: openMenu,
          child: AnimatedContainer(
            key: buttonKey,
            duration: _buttonAnimationDuration,
            curve: _buttonAnimationCurve,
            width: 24,
            height: 24,
            decoration: BoxDecoration(
              color: context.isDarkTheme
                  ? palette.surfaceSecondary.withValues(alpha: 0.72)
                  : const Color(0xFFEAF1FF),
              shape: BoxShape.circle,
            ),
            child: Center(
              child: _buildAgentPermissionIcon(
                selected,
                size: iconSize,
                color: selectedColor,
              ),
            ),
          ),
        ),
      ),
    );
  }

  String _agentPermissionTooltip() {
    final agentName = widget.agentRunSettings?.agentName.trim() ?? '';
    final displayName = agentName.isNotEmpty ? agentName : 'Agent';
    return Localizations.localeOf(context).languageCode == 'en'
        ? '$displayName permissions'
        : '$displayName 权限';
  }

  String _agentPermissionLabel(AgentPermissionMode mode) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    return switch (mode) {
      AgentPermissionMode.readOnly => english ? 'Read only' : '只读',
      AgentPermissionMode.defaultMode => english ? 'Workspace write' : '工作区读写',
      AgentPermissionMode.autoReview => english ? 'Auto review' : '自动审查',
      AgentPermissionMode.fullAccess => english ? 'Full access' : '完全访问权限',
    };
  }

  String _agentPermissionIconAsset(AgentPermissionMode mode) {
    return switch (mode) {
      AgentPermissionMode.readOnly => _kAgentPermissionReadOnlyIconAsset,
      AgentPermissionMode.defaultMode => _kAgentPermissionDefaultIconAsset,
      AgentPermissionMode.autoReview => _kAgentPermissionAutoReviewIconAsset,
      AgentPermissionMode.fullAccess => _kAgentPermissionFullAccessIconAsset,
    };
  }

  Widget _buildAgentPermissionIcon(
    AgentPermissionMode mode, {
    required double size,
    required Color color,
  }) {
    return SvgPicture.asset(
      _agentPermissionIconAsset(mode),
      width: size,
      height: size,
      colorFilter: ColorFilter.mode(color, BlendMode.srcIn),
    );
  }
}
