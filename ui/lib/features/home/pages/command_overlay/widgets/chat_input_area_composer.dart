part of 'chat_input_area.dart';

const List<Color> _kLightComposerFlowGradientColors = <Color>[
  Color(0xFFFF6A01),
  Color(0xFFF8C91C),
  Color(0xFF8A2BE2),
  Color(0xFF00BFFF),
  Color(0xFFFF0055),
  Color(0xFFFF6A01),
];

const List<Color> _kDarkComposerFlowGradientColors = <Color>[
  Color(0xFF8C775D),
  Color(0xFFB5A27D),
  Color(0xFF99AD91),
  Color(0xFFD5C6AB),
  Color(0xFF889B80),
  Color(0xFF8C775D),
];

enum _AgentRunSettingsMenuKind { model, effort }

class _AgentRunSettingsMenuAction {
  const _AgentRunSettingsMenuAction._(this.kind, this.value);

  const _AgentRunSettingsMenuAction.model(String value)
    : this._(_AgentRunSettingsMenuKind.model, value);

  const _AgentRunSettingsMenuAction.effort(String value)
    : this._(_AgentRunSettingsMenuKind.effort, value);

  final _AgentRunSettingsMenuKind kind;
  final String value;
}

mixin _ChatInputAreaComposerMixin on _ChatInputAreaStateBase {
  final GlobalKey _agentRunSettingsButtonKey = GlobalKey(
    debugLabel: 'agent-run-settings-button',
  );
  final GlobalKey _modelPickerButtonKey = GlobalKey(
    debugLabel: 'chat-model-picker-button',
  );
  final GlobalKey _agentPermissionButtonKey = GlobalKey(
    debugLabel: 'agent-permission-button',
  );
  OverlayGlassPopupHandle<_AgentRunSettingsMenuAction>?
  _agentRunSettingsMenuHandle;
  bool _isOpeningAgentRunSettingsMenu = false;
  bool _isAgentRunSettingsMenuOpen = false;
  OverlayGlassPopupHandle<AgentPermissionMode>? _agentPermissionMenuHandle;

  @override
  void dispose() {
    unawaited(_agentRunSettingsMenuHandle?.dismiss());
    _agentRunSettingsMenuHandle = null;
    unawaited(_agentPermissionMenuHandle?.dismiss());
    _agentPermissionMenuHandle = null;
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final composer = switch ((
      widget.useLargeComposerStyle,
      widget.useFrostedGlass,
    )) {
      (true, _) => SafeArea(child: _buildLargeComposerShell()),
      (false, true) => SafeArea(
        child: ClipRRect(
          borderRadius: BorderRadius.circular(8),
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 2, sigmaY: 2),
            child: Container(
              height: 44,
              padding: const EdgeInsets.fromLTRB(16, 0, 12, 0),
              decoration: BoxDecoration(
                color: context.isDarkTheme
                    ? palette.surfacePrimary.withValues(alpha: 0.86)
                    : const Color(0xE6F1F8FF),
                borderRadius: BorderRadius.circular(8),
                border: context.isDarkTheme
                    ? Border.all(
                        color: palette.borderSubtle.withValues(alpha: 0.72),
                      )
                    : null,
              ),
              child: _buildInputContent(),
            ),
          ),
        ),
      ),
      (false, false) => SafeArea(
        child: Container(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(8),
            boxShadow: context.isDarkTheme
                ? [
                    BoxShadow(
                      color: palette.shadowColor.withValues(alpha: 0.22),
                      blurRadius: 16,
                      offset: const Offset(0, 6),
                    ),
                  ]
                : [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.1),
                      blurRadius: 8,
                      offset: const Offset(0, 2),
                    ),
                  ],
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(8),
            child: Container(
              height: 44,
              padding: const EdgeInsets.fromLTRB(16, 0, 12, 0),
              decoration: BoxDecoration(
                color: context.isDarkTheme
                    ? palette.surfacePrimary
                    : Colors.white,
                borderRadius: BorderRadius.circular(8),
                border: context.isDarkTheme
                    ? Border.all(color: palette.borderSubtle)
                    : null,
              ),
              child: _buildInputContent(),
            ),
          ),
        ),
      ),
    };
    return NotificationListener<SizeChangedLayoutNotification>(
      onNotification: (_) {
        _reportInputHeightAfterBuild();
        return false;
      },
      child: SizeChangedLayoutNotifier(child: composer),
    );
  }

  /// 构建输入框内容区域（按钮、文本框等）
  Widget _buildInputContent() {
    return ValueListenableBuilder<_ComposerInteractionState>(
      valueListenable: _composerStateNotifier,
      builder: (context, composerState, _) {
        final openClawButton = _buildOpenClawButton();
        final hasPayload =
            composerState.hasText ||
            widget.attachments.isNotEmpty ||
            widget.hasExternalSendPayload;
        return Row(
          children: [
            Expanded(child: _buildTextField()),
            const SizedBox(width: 9),
            _buildAnimatedButtonRow(
              hasText: hasPayload,
              openClawButton: openClawButton,
            ),
          ],
        );
      },
    );
  }

  Widget _buildLargeComposer() {
    return ValueListenableBuilder<_ComposerInteractionState>(
      valueListenable: _composerStateNotifier,
      builder: (context, composerState, _) {
        final hasPayload =
            composerState.hasText ||
            widget.attachments.isNotEmpty ||
            widget.hasExternalSendPayload;

        return Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (widget.attachments.isNotEmpty) ...[
              _buildAttachmentPreview(),
              const SizedBox(height: 8),
            ],
            if ((widget.selectedModelOverrideId ?? '').trim().isNotEmpty) ...[
              _buildSelectedModelOverrideChip(),
              const SizedBox(height: 8),
            ],
            _buildTextField(
              multiline: true,
              expanded: composerState.expandsTextField,
            ),
            const SizedBox(height: 6),
            _buildLargeActionRow(hasPayload: hasPayload),
          ],
        );
      },
    );
  }

  Widget _buildLargeActionRow({required bool hasPayload}) {
    final contextUsageRatio = widget.contextUsageRatio;
    final rightActions = <Widget>[
      if (contextUsageRatio != null) ...[
        _ContextUsageRingButton(
          ratio: contextUsageRatio,
          tooltipMessage: widget.contextUsageTooltipMessage,
          onLongPress: widget.onLongPressContextUsageRing,
        ),
        const SizedBox(width: 4),
      ],
      if (_shouldShowAgentRunSettingsSelector) ...[
        _buildAgentRunSettingsButton(compact: false),
        const SizedBox(width: 4),
      ],
      if (_shouldShowModelPicker) ...[
        _buildModelPickerButton(compact: false),
        const SizedBox(width: 4),
      ],
      if (_shouldShowAgentPermissionSelector) ...[
        SizedBox(
          width: 28,
          height: 28,
          child: _buildAgentPermissionButton(iconSize: 20),
        ),
        const SizedBox(width: 4),
      ],
      SizedBox(
        width: 28,
        height: 28,
        child: _buildSpeechInputButton(compact: false),
      ),
      const SizedBox(width: 4),
      SizedBox(
        width: 28,
        height: 28,
        child: _buildTerminalButton(iconSize: 22),
      ),
      const SizedBox(width: 6),
      SizedBox(
        width: 28,
        height: 28,
        child: _buildLargeSendOrStopButton(hasPayload: hasPayload),
      ),
    ];

    return Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        SizedBox(width: 28, height: 28, child: _buildLargeAddButton()),
        if (widget.onTriggerSlashCommand != null) ...[
          const SizedBox(width: 4),
          SizedBox(
            width: 28,
            height: 28,
            child: _buildSlashTriggerButton(iconSize: 20),
          ),
        ],
        const SizedBox(width: 4),
        Expanded(
          child: Align(
            alignment: Alignment.centerRight,
            child: FittedBox(
              fit: BoxFit.scaleDown,
              alignment: Alignment.centerRight,
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: rightActions,
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildSelectedModelOverrideChip() {
    final modelId = (widget.selectedModelOverrideId ?? '').trim();
    final palette = context.omniPalette;
    final chipColor = context.isDarkTheme
        ? palette.surfaceSecondary
        : const Color(0xFFF4F7FD);
    final textColor = context.isDarkTheme
        ? palette.textSecondary
        : const Color(0xFF54627A);
    return Align(
      alignment: Alignment.centerLeft,
      child: Container(
        constraints: const BoxConstraints(maxWidth: 230),
        padding: const EdgeInsets.fromLTRB(10, 5, 6, 5),
        decoration: BoxDecoration(
          color: chipColor,
          borderRadius: BorderRadius.circular(999),
          border: context.isDarkTheme
              ? Border.all(color: palette.borderSubtle)
              : null,
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Flexible(
              child: Text(
                '@$modelId',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 11,
                  color: textColor,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
            if (widget.onClearSelectedModelOverride != null) ...[
              const SizedBox(width: 4),
              GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: widget.onClearSelectedModelOverride,
                child: Container(
                  width: 14,
                  height: 14,
                  decoration: BoxDecoration(
                    color: textColor.withValues(alpha: 0.16),
                    shape: BoxShape.circle,
                  ),
                  child: Icon(Icons.close_rounded, size: 10, color: textColor),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildLargeAddButton() {
    return IconButton(
      padding: EdgeInsets.zero,
      iconSize: 20,
      icon: _addSvg,
      onPressed: () {
        if (widget.useAttachmentPickerForPlus &&
            widget.onPickAttachment != null) {
          if (_isPopupVisible) {
            setState(() => _isPopupVisible = false);
            widget.onPopupVisibilityChanged?.call(false);
          }
          widget.onPickAttachment?.call();
          return;
        }

        setState(() {
          _isPopupVisible = false;
        });
        widget.onPopupVisibilityChanged?.call(false);
      },
    );
  }

  Widget _buildSlashTriggerButton({required double iconSize}) {
    return IconButton(
      key: const ValueKey('chat-input-trigger-slash-button'),
      padding: EdgeInsets.zero,
      iconSize: iconSize,
      icon: _commandSvg,
      tooltip: '命令',
      onPressed: widget.onTriggerSlashCommand == null
          ? null
          : () {
              if (_isPopupVisible) {
                setState(() => _isPopupVisible = false);
                widget.onPopupVisibilityChanged?.call(false);
              }
              widget.onTriggerSlashCommand?.call();
            },
    );
  }

  Widget _buildLargeSendOrStopButton({required bool hasPayload}) {
    final isProcessing = widget.isProcessing;
    final canSend = hasPayload;
    final canTap = isProcessing || canSend;
    final icon = isProcessing ? _pauseSvg : _sendSvg;

    return AnimatedOpacity(
      duration: _buttonAnimationDuration,
      curve: _buttonAnimationCurve,
      opacity: canTap ? 1 : 0.38,
      child: IconButton(
        key: const ValueKey('chat-input-send-or-stop-button'),
        padding: EdgeInsets.zero,
        iconSize: 20,
        icon: AnimatedSwitcher(
          duration: _buttonAnimationDuration,
          switchInCurve: _buttonAnimationCurve,
          switchOutCurve: _buttonAnimationCurve,
          transitionBuilder: (child, animation) {
            return FadeTransition(
              opacity: animation,
              child: ScaleTransition(scale: animation, child: child),
            );
          },
          child: SizedBox(key: ValueKey<bool>(isProcessing), child: icon),
        ),
        onPressed: !canTap
            ? null
            : () {
                if (isProcessing) {
                  widget.onCancelTask();
                } else {
                  widget.onSendMessage();
                }
              },
      ),
    );
  }

  Widget _buildLargeComposerShell() {
    final content = RepaintBoundary(child: _buildLargeComposer());
    final useFrostedGlass = widget.useFrostedGlass;
    final palette = context.omniPalette;
    return MouseRegion(
      onEnter: (_) {
        if (_isComposerHovered) return;
        setState(() => _isComposerHovered = true);
      },
      onExit: (_) {
        if (!_isComposerHovered) return;
        setState(() => _isComposerHovered = false);
      },
      child: ValueListenableBuilder<_ComposerInteractionState>(
        valueListenable: _composerStateNotifier,
        child: content,
        builder: (context, composerState, child) {
          final focused = composerState.hasFocus;
          final inputSurfaceColor = context.isDarkTheme
              ? palette.surfacePrimary
              : const Color(0xFFF9FCFF);
          final shellSurfaceColor = useFrostedGlass
              ? (context.isDarkTheme
                    ? palette.surfacePrimary.withValues(alpha: 0.82)
                    : Colors.white.withValues(alpha: 0.76))
              : inputSurfaceColor;
          final hovered = _isComposerHovered;
          const minShellHeight = 72.0;
          const shellRadius = 20.0;
          const borderInset = 1.5;
          final innerRadius = math.max(0.0, shellRadius - borderInset);
          const contentPadding = EdgeInsets.fromLTRB(14, 8, 12, 8);
          final shouldGlowStrong = focused || hovered;
          final innerBorderColor =
              (context.isDarkTheme ? palette.borderStrong : Colors.white)
                  .withValues(alpha: context.isDarkTheme ? 0.42 : 0.1);

          return AnimatedContainer(
            duration: const Duration(milliseconds: 220),
            curve: Curves.easeOutCubic,
            constraints: BoxConstraints(minHeight: minShellHeight),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(shellRadius),
              boxShadow: [
                BoxShadow(
                  color:
                      (context.isDarkTheme
                              ? palette.accentPrimary
                              : const Color(0xFF2F7BFF))
                          .withValues(
                            alpha: focused
                                ? (context.isDarkTheme ? 0.18 : 0.2)
                                : hovered
                                ? (context.isDarkTheme ? 0.12 : 0.15)
                                : (context.isDarkTheme ? 0.08 : 0.1),
                          ),
                  blurRadius: focused ? 18 : 12,
                  offset: const Offset(0, 6),
                ),
              ],
            ),
            child: Stack(
              children: [
                AnimatedPadding(
                  duration: const Duration(milliseconds: 240),
                  curve: Curves.easeOutCubic,
                  padding: EdgeInsets.all(borderInset),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(innerRadius),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(
                        sigmaX: useFrostedGlass ? 8 : 0,
                        sigmaY: useFrostedGlass ? 8 : 0,
                      ),
                      child: AnimatedContainer(
                        duration: const Duration(milliseconds: 220),
                        curve: Curves.easeOutCubic,
                        padding: contentPadding,
                        decoration: BoxDecoration(
                          color: shellSurfaceColor,
                          borderRadius: BorderRadius.circular(innerRadius),
                          border: Border.all(color: innerBorderColor, width: 1),
                        ),
                        child: AnimatedSize(
                          duration: const Duration(milliseconds: 220),
                          curve: Curves.easeOutCubic,
                          alignment: Alignment.bottomCenter,
                          child: child ?? const SizedBox.shrink(),
                        ),
                      ),
                    ),
                  ),
                ),
                Positioned.fill(
                  child: IgnorePointer(
                    child: CustomPaint(
                      painter: _ComposerFlowBorderPainter(
                        progress: _composerFlowController,
                        interactive: shouldGlowStrong,
                        focused: focused,
                        forceStrong: false,
                        radius: shellRadius,
                        strokeWidth: 1.5,
                        gradientColors: context.isDarkTheme
                            ? _kDarkComposerFlowGradientColors
                            : _kLightComposerFlowGradientColors,
                      ),
                    ),
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _buildAttachmentPreview() {
    // Collect all image sources for multi-image preview
    final imageItems = widget.attachments.where((a) => a.isImage).toList();
    final imageSources = imageItems
        .map((a) => FileImageSource(a.path) as ImagePreviewSource)
        .toList();
    final heroTags = List.generate(
      imageItems.length,
      (i) => 'img_preview_input_${imageItems[i].id}',
    );

    return SizedBox(
      height: 72,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: widget.attachments.length,
        separatorBuilder: (_, _) => const SizedBox(width: 8),
        itemBuilder: (context, index) {
          final item = widget.attachments[index];
          if (item.isImage) {
            final imageIndex = imageItems.indexOf(item);
            return _buildImageAttachmentTile(
              item,
              imageSources,
              imageIndex,
              heroTags,
            );
          }
          return _buildFileAttachmentTile(item);
        },
      ),
    );
  }

  Widget _buildImageAttachmentTile(
    ChatInputAttachment item,
    List<ImagePreviewSource> allSources,
    int tappedIndex,
    List<String> heroTags,
  ) {
    final heroTag = heroTags[tappedIndex];
    final palette = context.omniPalette;
    return GestureDetector(
      onTap: () => ImagePreviewOverlay.showAll(
        context,
        sources: allSources,
        initialIndex: tappedIndex.clamp(0, allSources.length - 1),
        heroTags: heroTags,
      ),
      child: Stack(
        children: [
          Container(
            width: 72,
            height: 72,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(12),
              border: Border.all(
                color: context.isDarkTheme
                    ? palette.borderSubtle
                    : const Color(0xFFD3E3FB),
                width: 1,
              ),
              color: context.isDarkTheme
                  ? palette.surfaceSecondary
                  : const Color(0xFFF1F6FF),
            ),
            clipBehavior: Clip.antiAlias,
            child: Hero(
              tag: heroTag,
              child: Image.file(
                File(item.path),
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) => const Center(
                  child: Icon(
                    Icons.image_not_supported_outlined,
                    size: 20,
                    color: Color(0xFF6A83AA),
                  ),
                ),
              ),
            ),
          ),
          _buildAttachmentRemoveButton(item.id),
        ],
      ),
    );
  }

  Widget _buildFileAttachmentTile(ChatInputAttachment item) {
    final sizeText = _formatAttachmentSize(item.size);
    final palette = context.omniPalette;
    final tileColor = context.isDarkTheme
        ? palette.surfaceSecondary
        : const Color(0xFFF1F6FF);
    final tileBorderColor = context.isDarkTheme
        ? palette.borderSubtle
        : const Color(0xFFD3E3FB);
    final textColor = context.isDarkTheme
        ? palette.textSecondary
        : const Color(0xFF35517A);
    final iconColor = context.isDarkTheme
        ? palette.accentPrimary
        : const Color(0xFF3B6FD6);
    return Stack(
      children: [
        Container(
          width: 160,
          height: 72,
          padding: const EdgeInsets.fromLTRB(10, 8, 28, 8),
          decoration: BoxDecoration(
            color: tileColor,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(color: tileBorderColor, width: 1),
          ),
          child: Row(
            children: [
              Icon(
                Icons.insert_drive_file_outlined,
                size: 18,
                color: iconColor,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  sizeText.isEmpty ? item.name : '${item.name}\n$sizeText',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 12,
                    color: textColor,
                    fontWeight: FontWeight.w500,
                    height: 1.3,
                  ),
                ),
              ),
            ],
          ),
        ),
        _buildAttachmentRemoveButton(item.id),
      ],
    );
  }

  Widget _buildAttachmentRemoveButton(String attachmentId) {
    if (widget.onRemoveAttachment == null) {
      return const SizedBox.shrink();
    }
    return Positioned(
      right: 4,
      top: 4,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () => widget.onRemoveAttachment?.call(attachmentId),
        child: Container(
          width: 18,
          height: 18,
          decoration: BoxDecoration(
            color: Colors.black.withValues(alpha: 0.62),
            shape: BoxShape.circle,
          ),
          child: const Icon(Icons.close_rounded, size: 12, color: Colors.white),
        ),
      ),
    );
  }

  String _formatAttachmentSize(int? size) {
    if (size == null || size <= 0) return '';
    if (size < 1024) return '${size}B';
    if (size < 1024 * 1024) return '${(size / 1024).toStringAsFixed(1)}KB';
    return '${(size / (1024 * 1024)).toStringAsFixed(1)}MB';
  }

  /// 构建带动画的按钮行
  Widget _buildAnimatedButtonRow({
    required bool hasText,
    required Widget? openClawButton,
  }) {
    final contextUsageRatio = widget.contextUsageRatio;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        // OpenClaw 按钮 - 始终显示在固定位置
        if (openClawButton != null) ...[
          openClawButton,
          const SizedBox(width: 2),
        ],
        if (widget.onTriggerSlashCommand != null) ...[
          SizedBox(
            width: 24,
            height: 24,
            child: _buildSlashTriggerButton(iconSize: 18),
          ),
          const SizedBox(width: 2),
        ],
        if (contextUsageRatio != null) ...[
          _ContextUsageRingButton(
            ratio: contextUsageRatio,
            tooltipMessage: widget.contextUsageTooltipMessage,
            onLongPress: widget.onLongPressContextUsageRing,
          ),
          const SizedBox(width: 4),
        ],
        if (_shouldShowAgentRunSettingsSelector) ...[
          _buildAgentRunSettingsButton(compact: true),
          const SizedBox(width: 2),
        ],
        if (_shouldShowModelPicker) ...[
          _buildModelPickerButton(compact: true),
          const SizedBox(width: 2),
        ],
        if (_shouldShowAgentPermissionSelector) ...[
          SizedBox(
            width: 24,
            height: 24,
            child: _buildAgentPermissionButton(iconSize: 18),
          ),
          const SizedBox(width: 2),
        ],
        SizedBox(
          width: 24,
          height: 24,
          child: _buildSpeechInputButton(compact: true),
        ),
        const SizedBox(width: 2),
        SizedBox(
          width: 24,
          height: 24,
          child: _buildTerminalButton(iconSize: 20),
        ),
        const SizedBox(width: 2),
        // 发送/添加按钮
        _buildSendButton(hasText: hasText),
      ],
    );
  }

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
                ? (english ? 'Select model' : '选择模型')
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
          _isOpeningAgentRunSettingsMenu) {
        return;
      }
      _isOpeningAgentRunSettingsMenu = true;
      if (buttonKey.currentContext == null) {
        _isOpeningAgentRunSettingsMenu = false;
        return;
      }
      final opened = widget.onAgentRunSettingsOpened;
      if (opened != null) {
        unawaited(
          Future<void>.sync(opened).catchError((
            Object error,
            StackTrace stackTrace,
          ) {
          }),
        );
      }
      _isOpeningAgentRunSettingsMenu = false;
      if (!mounted) {
        return;
      }
      final anchorContext = buttonKey.currentContext;
      if (anchorContext == null || !anchorContext.mounted) {
        return;
      }
      final anchor = glassPopupAnchorFromContext(anchorContext);
      if (anchor == null) {
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
      setState(() {
        _isAgentRunSettingsMenuOpen = true;
      });
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
            setState(() {
              _isAgentRunSettingsMenuOpen = false;
            });
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
                    _isAgentRunSettingsMenuOpen
                        ? LucideIcons.packageOpen
                        : LucideIcons.package,
                    key: ValueKey(
                      _isAgentRunSettingsMenuOpen
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
      if (_agentPermissionMenuHandle != null) {
        return;
      }
      final anchorContext = buttonKey.currentContext;
      if (anchorContext == null) {
        return;
      }
      final anchor = glassPopupAnchorFromContext(anchorContext);
      if (anchor == null) {
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
      try {
        final mode = await handle.future;
        if (mode == null) return;
        widget.onAgentPermissionModeChanged?.call(mode);
      } finally {
        if (_agentPermissionMenuHandle == handle) {
          _agentPermissionMenuHandle = null;
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
      AgentPermissionMode.defaultMode =>
        english ? 'Default permissions' : '默认权限',
      AgentPermissionMode.autoReview => english ? 'Auto review' : '自动审查',
      AgentPermissionMode.fullAccess => english ? 'Full access' : '完全访问权限',
    };
  }

  String _agentPermissionIconAsset(AgentPermissionMode mode) {
    return switch (mode) {
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

  Widget _buildTerminalButton({required double iconSize}) {
    return IconButton(
      padding: EdgeInsets.zero,
      tooltip: Localizations.localeOf(context).languageCode == 'en'
          ? 'Open terminal'
          : '打开终端',
      iconSize: iconSize,
      icon: SizedBox(
        width: 24,
        height: 24,
        child: Center(
          child: SizedBox(
            width: iconSize,
            height: iconSize,
            child: _terminalSvg,
          ),
        ),
      ),
      onPressed: () {
        unawaited(openTerminalFromInput());
      },
    );
  }

  Widget _buildSpeechInputButton({required bool compact}) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    final recording = _speechInputPhase == _SpeechInputPhase.recording;
    final waiting =
        _speechInputPhase == _SpeechInputPhase.starting ||
        _speechInputPhase == _SpeechInputPhase.transcribing;
    final tooltip = switch (_speechInputPhase) {
      _SpeechInputPhase.idle =>
        english
            ? 'Voice input (long-press to transcribe a file)'
            : '语音输入（长按转写音频文件）',
      _SpeechInputPhase.starting =>
        english ? 'Starting microphone…' : '正在启动麦克风…',
      _SpeechInputPhase.recording =>
        english
            ? 'Stop and transcribe (${_speechRecordingSeconds}s)'
            : '停止并转写（$_speechRecordingSeconds 秒）',
      _SpeechInputPhase.transcribing =>
        english ? 'Transcribing… Tap to cancel' : '正在转写…点击取消',
    };
    final iconSize = compact ? 18.0 : 20.0;
    final color = recording
        ? const Color(0xFFE64646)
        : context.omniPalette.textSecondary;

    return Semantics(
      button: true,
      label: tooltip,
      child: Tooltip(
        message: tooltip,
        waitDuration: const Duration(milliseconds: 400),
        child: InkWell(
          key: const ValueKey('chat-input-speech-button'),
          borderRadius: BorderRadius.circular(8),
          onTap: () => unawaited(toggleSpeechInput()),
          onLongPress: _speechInputPhase == _SpeechInputPhase.idle
              ? () => unawaited(transcribeAudioFile())
              : null,
          child: Center(
            child: waiting
                ? SizedBox(
                    width: iconSize - 3,
                    height: iconSize - 3,
                    child: CircularProgressIndicator(
                      strokeWidth: 1.8,
                      color: color,
                    ),
                  )
                : Icon(
                    recording ? Icons.stop_rounded : LucideIcons.mic2,
                    size: iconSize,
                    color: color,
                  ),
          ),
        ),
      ),
    );
  }

  bool _isIndependentSendButtonEnabledForKeyboard() {
    if (!widget.useIndependentSendButton) {
      return false;
    }
    try {
      return StorageService.isIndependentChatSendButtonEnabled();
    } catch (_) {
      return true;
    }
  }

  /// 统一的输入框组件
  Widget _buildTextField({bool multiline = false, bool expanded = false}) {
    final palette = context.omniPalette;
    final useKeyboardNewline =
        multiline && _isIndependentSendButtonEnabledForKeyboard();
    final keyboardType = useKeyboardNewline
        ? TextInputType.multiline
        : TextInputType.text;
    final textInputAction = useKeyboardNewline
        ? TextInputAction.newline
        : TextInputAction.send;
    final textColor = context.isDarkTheme
        ? palette.textPrimary
        : const Color(0xFF353E53);
    final hintColor = context.isDarkTheme
        ? palette.textTertiary
        : const Color(0x80353E53);
    final textStyle = TextStyle(
      fontSize: multiline ? 15.0 : 14.0,
      height: multiline ? 1.45 : 1.43,
      color: textColor,
      letterSpacing: 0.333,
    );
    final minLines = multiline ? (expanded ? 2 : 1) : 1;
    final maxLines = multiline ? 3 : 1;
    return GestureDetector(
      onTap: () {
        widget.onRequestFocus?.call();
        widget.focusNode.requestFocus();
      },
      child: AbsorbPointer(
        absorbing: !widget.focusNode.hasFocus,
        child: TextField(
          controller: widget.controller,
          focusNode: widget.focusNode,
          scrollController: _textFieldScrollController,
          keyboardType: keyboardType,
          textInputAction: textInputAction,
          minLines: minLines,
          maxLines: maxLines,
          scrollPhysics: const ClampingScrollPhysics(),
          onTap: () => widget.onRequestFocus?.call(),
          onSubmitted: useKeyboardNewline
              ? null
              : (_) {
                  if (widget.controller.text.trim().isNotEmpty) {
                    widget.onSendMessage();
                  } else {
                    widget.onRequestFocus?.call();
                    widget.focusNode.requestFocus();
                  }
                },
          textAlignVertical: multiline
              ? TextAlignVertical.top
              : TextAlignVertical.center,
          textCapitalization: TextCapitalization.sentences,
          style: textStyle,
          contextMenuBuilder: (context, editableTextState) =>
              TextInputContextMenu(editableTextState: editableTextState),
          decoration: InputDecoration(
            hintText: Localizations.localeOf(context).languageCode == 'en'
                ? 'Type your message'
                : '请输入内容',
            hintStyle: TextStyle(
              fontSize: multiline ? 15.0 : 14.0,
              color: hintColor,
              height: multiline ? 1.45 : 1.43,
              letterSpacing: 0.333,
            ),
            filled: false,
            fillColor: Colors.transparent,
            border: InputBorder.none,
            enabledBorder: InputBorder.none,
            focusedBorder: InputBorder.none,
            disabledBorder: InputBorder.none,
            errorBorder: InputBorder.none,
            focusedErrorBorder: InputBorder.none,
            contentPadding: EdgeInsets.symmetric(vertical: multiline ? 2 : 12),
            isDense: true,
          ),
        ),
      ),
    );
  }

  /// OpenClaw 开关按钮（位于语音按钮左侧）
  /// 点击切换开关，长按唤出配置面板
  Widget? _buildOpenClawButton() {
    if (widget.openClawEnabled == null || widget.onToggleOpenClaw == null) {
      return null;
    }

    final isEnabled = widget.openClawEnabled == true;

    return GestureDetector(
      onLongPress: widget.onLongPressOpenClaw,
      child: SizedBox(
        width: 24,
        height: 24,
        child: IconButton(
          padding: EdgeInsets.zero,
          iconSize: 20,
          icon: AnimatedSwitcher(
            duration: _buttonAnimationDuration,
            transitionBuilder: (child, animation) {
              return FadeTransition(
                opacity: animation,
                child: ScaleTransition(scale: animation, child: child),
              );
            },
            child: SvgPicture.asset(
              isEnabled
                  ? 'assets/home/openclaw.svg'
                  : 'assets/home/openclaw_gray.svg',
              key: ValueKey<bool>(isEnabled),
              width: 20,
              height: 20,
            ),
          ),
          onPressed: () => widget.onToggleOpenClaw?.call(!isEnabled),
        ),
      ),
    );
  }

  /// 右侧发送/添加按钮
  Widget _buildSendButton({required bool hasText}) {
    Widget icon;
    VoidCallback? onPressed;
    String iconKey;

    if (widget.isProcessing) {
      icon = _pauseSvg;
      iconKey = 'pause';
      onPressed = () {
        widget.onCancelTask();
      };
    } else if (hasText) {
      icon = _sendSvg;
      iconKey = 'send';
      onPressed = () {
        widget.onSendMessage();
      };
    } else {
      icon = _addSvg;
      iconKey = 'add';
      if (widget.useAttachmentPickerForPlus &&
          widget.onPickAttachment != null) {
        onPressed = () {
          if (_isPopupVisible) {
            setState(() => _isPopupVisible = false);
            widget.onPopupVisibilityChanged?.call(false);
          }
          widget.onPickAttachment?.call();
        };
      } else {
        if (_isPopupVisible) {
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (!mounted) return;
            setState(() => _isPopupVisible = false);
            widget.onPopupVisibilityChanged?.call(false);
          });
        }
        onPressed = null;
      }
    }

    return SizedBox(
      width: 24,
      height: 24,
      child: IconButton(
        padding: EdgeInsets.zero,
        iconSize: 20,
        icon: AnimatedSwitcher(
          duration: _buttonAnimationDuration,
          switchInCurve: _buttonAnimationCurve,
          switchOutCurve: _buttonAnimationCurve,
          transitionBuilder: (child, animation) {
            return FadeTransition(
              opacity: animation,
              child: ScaleTransition(scale: animation, child: child),
            );
          },
          child: SizedBox(key: ValueKey<String>(iconKey), child: icon),
        ),
        onPressed: onPressed,
      ),
    );
  }
}

class _AgentPermissionOptionData {
  const _AgentPermissionOptionData({
    required this.mode,
    required this.label,
    required this.iconAsset,
  });

  final AgentPermissionMode mode;
  final String label;
  final String iconAsset;
}

class _AgentPermissionGlassMenuContent extends StatefulWidget {
  const _AgentPermissionGlassMenuContent({
    required this.width,
    required this.options,
    required this.selected,
    required this.selectedColor,
    required this.inactiveColor,
    required this.textColor,
    required this.onSelect,
  });

  final double width;
  final List<_AgentPermissionOptionData> options;
  final AgentPermissionMode selected;
  final Color selectedColor;
  final Color inactiveColor;
  final Color textColor;
  final ValueChanged<AgentPermissionMode> onSelect;

  @override
  State<_AgentPermissionGlassMenuContent> createState() =>
      _AgentPermissionGlassMenuContentState();
}

class _AgentPermissionGlassMenuContentState
    extends State<_AgentPermissionGlassMenuContent> {
  static const Duration _selectionDuration = Duration(milliseconds: 160);

  void _select(AgentPermissionMode mode) {
    widget.onSelect(mode);
  }

  Widget _buildIcon(_AgentPermissionOptionData option, bool selected) {
    return SvgPicture.asset(
      option.iconAsset,
      width: 18,
      height: 18,
      colorFilter: ColorFilter.mode(
        selected ? widget.selectedColor : widget.inactiveColor,
        BlendMode.srcIn,
      ),
    );
  }

  Widget _buildRow(_AgentPermissionOptionData option) {
    final isSelected = option.mode == widget.selected;
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final selectedBackground = isDark
        ? Color.lerp(
            palette.surfaceSecondary.withValues(alpha: 0.48),
            palette.accentPrimary,
            0.18,
          )!
        : const Color(0xFF2C7FEB).withValues(alpha: 0.12);
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 2, 10, 2),
      child: InkWell(
        key: ValueKey('chat-input-agent-permission-option-${option.mode.name}'),
        onTap: () => _select(option.mode),
        borderRadius: BorderRadius.circular(12),
        child: AnimatedContainer(
          duration: _selectionDuration,
          curve: Curves.easeOutCubic,
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          decoration: BoxDecoration(
            color: isSelected ? selectedBackground : Colors.transparent,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              _buildIcon(option, isSelected),
              const SizedBox(width: 9),
              Expanded(
                child: Text(
                  option.label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 13,
                    height: 1.15,
                    color: widget.textColor,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              if (isSelected)
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
    );
  }

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: widget.width,
      child: OmniGlassPanel(
        width: widget.width,
        borderRadius: BorderRadius.circular(18),
        child: Material(
          color: Colors.transparent,
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (final option in widget.options) _buildRow(option),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

enum _AgentRunSettingsMenuPage { overview, models, reasoning }

class _AgentRunSettingsMenuContent extends StatefulWidget {
  const _AgentRunSettingsMenuContent({
    required this.width,
    required this.maxHeight,
    required this.modelHeader,
    required this.reasoningHeader,
    required this.searchHint,
    required this.noMatchesLabel,
    required this.emptyModelsLabel,
    required this.modelOptions,
    required this.currentModelId,
    required this.reasoningOptions,
    required this.currentReasoningEffort,
    required this.effortLabelBuilder,
    required this.selectedColor,
    required this.textColor,
    required this.onSelectModel,
    required this.onSelectReasoning,
  });

  final double width;
  final double maxHeight;
  final String modelHeader;
  final String reasoningHeader;
  final String searchHint;
  final String noMatchesLabel;
  final String emptyModelsLabel;
  final List<String> modelOptions;
  final String currentModelId;
  final List<String> reasoningOptions;
  final String currentReasoningEffort;
  final String Function(String) effortLabelBuilder;
  final Color selectedColor;
  final Color textColor;
  final ValueChanged<String> onSelectModel;
  final ValueChanged<String> onSelectReasoning;

  @override
  State<_AgentRunSettingsMenuContent> createState() =>
      _AgentRunSettingsMenuContentState();
}

class _AgentRunSettingsMenuContentState
    extends State<_AgentRunSettingsMenuContent> {
  static const int _searchThreshold = 5;
  static const Duration _pageAnimationDuration = Duration(milliseconds: 150);

  final TextEditingController _searchController = TextEditingController();
  late _AgentRunSettingsMenuPage _page;

  @override
  void initState() {
    super.initState();
    _page = widget.reasoningOptions.isEmpty
        ? _AgentRunSettingsMenuPage.models
        : _AgentRunSettingsMenuPage.overview;
    _searchController.addListener(_handleSearchChanged);
  }

  @override
  void dispose() {
    _searchController
      ..removeListener(_handleSearchChanged)
      ..dispose();
    super.dispose();
  }

  void _handleSearchChanged() {
    if (mounted) {
      setState(() {});
    }
  }

  List<String> get _filteredModels {
    final query = _searchController.text.trim().toLowerCase();
    if (query.isEmpty) {
      return widget.modelOptions;
    }
    return widget.modelOptions
        .where((model) => model.toLowerCase().contains(query))
        .toList(growable: false);
  }

  void _showPage(_AgentRunSettingsMenuPage page) {
    if (_page == page) {
      return;
    }
    setState(() {
      _page = page;
      if (page != _AgentRunSettingsMenuPage.models) {
        _searchController.clear();
      }
    });
  }

  Widget _buildOverviewRow({
    required Key key,
    required IconData icon,
    required String label,
    required String value,
    required VoidCallback onTap,
  }) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 2, 8, 2),
      child: InkWell(
        key: key,
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Container(
          constraints: const BoxConstraints(minHeight: 46),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
          child: Row(
            children: [
              Icon(
                icon,
                size: 16,
                color: isDark ? palette.textSecondary : const Color(0xFF66758E),
              ),
              const SizedBox(width: 9),
              Text(
                label,
                style: TextStyle(
                  fontSize: 12,
                  color: widget.textColor,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  value.isEmpty ? '—' : value,
                  textAlign: TextAlign.end,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 12,
                    color: isDark
                        ? palette.textTertiary
                        : const Color(0xFF8490A3),
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
              const SizedBox(width: 4),
              Icon(
                Icons.chevron_right_rounded,
                size: 18,
                color: isDark ? palette.textTertiary : const Color(0xFF9AA4B6),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildOverview() {
    return Padding(
      key: const ValueKey('agent-run-settings-overview'),
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildOverviewRow(
            key: const ValueKey('chat-input-agent-run-settings-group-model'),
            icon: LucideIcons.sparkles,
            label: widget.modelHeader,
            value: widget.currentModelId,
            onTap: () => _showPage(_AgentRunSettingsMenuPage.models),
          ),
          _buildOverviewRow(
            key: const ValueKey(
              'chat-input-agent-run-settings-group-reasoning',
            ),
            icon: LucideIcons.brain,
            label: widget.reasoningHeader,
            value: widget.effortLabelBuilder(widget.currentReasoningEffort),
            onTap: () => _showPage(_AgentRunSettingsMenuPage.reasoning),
          ),
        ],
      ),
    );
  }

  Widget _buildSubmenuHeader(String title) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 7, 10, 3),
      child: InkWell(
        key: const ValueKey('chat-input-agent-run-settings-back'),
        onTap: () => _showPage(_AgentRunSettingsMenuPage.overview),
        borderRadius: BorderRadius.circular(10),
        child: SizedBox(
          height: 34,
          child: Row(
            children: [
              const SizedBox(
                width: 34,
                height: 34,
                child: Icon(Icons.chevron_left_rounded, size: 20),
              ),
              const SizedBox(width: 4),
              Expanded(
                child: Text(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 13,
                    color: widget.textColor,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildSearch() {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    return Padding(
      key: const ValueKey('chat-input-agent-run-settings-model-search'),
      padding: const EdgeInsets.fromLTRB(10, 5, 10, 6),
      child: Container(
        height: 36,
        padding: const EdgeInsets.symmetric(horizontal: 10),
        decoration: BoxDecoration(
          color: isDark
              ? palette.surfaceSecondary.withValues(alpha: 0.58)
              : Colors.white.withValues(alpha: 0.42),
          borderRadius: BorderRadius.circular(11),
          border: Border.all(
            color: isDark
                ? palette.borderSubtle.withValues(alpha: 0.60)
                : Colors.white.withValues(alpha: 0.66),
          ),
        ),
        child: Row(
          children: [
            Icon(
              Icons.search_rounded,
              size: 17,
              color: isDark ? palette.textTertiary : const Color(0xFF929EB0),
            ),
            const SizedBox(width: 7),
            Expanded(
              child: TextField(
                controller: _searchController,
                autofocus: false,
                scrollPadding: EdgeInsets.zero,
                cursorColor: widget.selectedColor,
                style: TextStyle(
                  fontSize: 12,
                  color: widget.textColor,
                  fontWeight: FontWeight.w500,
                ),
                decoration: InputDecoration(
                  isDense: true,
                  hintText: widget.searchHint,
                  hintStyle: TextStyle(
                    fontSize: 12,
                    color: isDark
                        ? palette.textTertiary
                        : const Color(0xFF929EB0),
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

  Widget _buildChoiceRow({
    required String keySuffix,
    required String value,
    required String label,
    required bool selected,
    required VoidCallback onTap,
    bool showVendorIcon = false,
  }) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final selectedBackground = isDark
        ? Color.alphaBlend(
            widget.selectedColor.withValues(alpha: 0.18),
            palette.surfaceSecondary.withValues(alpha: 0.52),
          )
        : widget.selectedColor.withValues(alpha: 0.10);
    final row = Padding(
      padding: const EdgeInsets.fromLTRB(8, 2, 8, 2),
      child: InkWell(
        key: ValueKey('chat-input-agent-run-settings-option-$keySuffix'),
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: AnimatedContainer(
          duration: _pageAnimationDuration,
          curve: Curves.easeOutCubic,
          constraints: const BoxConstraints(minHeight: 42),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
          decoration: BoxDecoration(
            color: selected ? selectedBackground : Colors.transparent,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Row(
            children: [
              if (showVendorIcon) ...[
                ProviderVendorIcon(
                  vendor: ModelVendorCatalog.resolve(value),
                  size: 14,
                ),
                const SizedBox(width: 7),
              ],
              Expanded(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 12,
                    height: 1.1,
                    color: widget.textColor,
                    fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              SizedBox(
                width: 16,
                child: selected
                    ? Icon(
                        Icons.check_rounded,
                        size: 16,
                        color: widget.selectedColor,
                      )
                    : null,
              ),
            ],
          ),
        ),
      ),
    );
    if (!showVendorIcon) {
      return row;
    }
    return Tooltip(
      message: value,
      triggerMode: TooltipTriggerMode.longPress,
      waitDuration: Duration.zero,
      preferBelow: false,
      child: row,
    );
  }

  Widget _buildModelList() {
    final models = _filteredModels;
    final showBack = widget.reasoningOptions.isNotEmpty;
    final showSearch = widget.modelOptions.length > _searchThreshold;
    return Column(
      key: const ValueKey('agent-run-settings-models'),
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (showBack) _buildSubmenuHeader(widget.modelHeader),
        if (showSearch) _buildSearch(),
        if (widget.modelOptions.isEmpty)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 14, 16, 18),
            child: Text(
              widget.emptyModelsLabel,
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 12,
                color: context.isDarkTheme
                    ? context.omniPalette.textTertiary
                    : const Color(0xFF929EB0),
                fontWeight: FontWeight.w500,
              ),
            ),
          )
        else if (models.isEmpty)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 14, 16, 18),
            child: Text(
              widget.noMatchesLabel,
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 12,
                color: context.isDarkTheme
                    ? context.omniPalette.textTertiary
                    : const Color(0xFF929EB0),
                fontWeight: FontWeight.w500,
              ),
            ),
          )
        else
          Flexible(
            child: ListView.builder(
              shrinkWrap: true,
              padding: const EdgeInsets.only(top: 3, bottom: 8),
              itemCount: models.length,
              itemBuilder: (context, index) {
                final model = models[index];
                return _buildChoiceRow(
                  keySuffix: 'model-$model',
                  value: model,
                  label: model,
                  selected: model == widget.currentModelId,
                  showVendorIcon: true,
                  onTap: () => widget.onSelectModel(model),
                );
              },
            ),
          ),
      ],
    );
  }

  Widget _buildReasoningList() {
    return Column(
      key: const ValueKey('agent-run-settings-reasoning'),
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _buildSubmenuHeader(widget.reasoningHeader),
        Flexible(
          child: ListView.builder(
            shrinkWrap: true,
            padding: const EdgeInsets.only(top: 3, bottom: 8),
            itemCount: widget.reasoningOptions.length,
            itemBuilder: (context, index) {
              final effort = widget.reasoningOptions[index];
              return _buildChoiceRow(
                keySuffix: 'effort-$effort',
                value: effort,
                label: widget.effortLabelBuilder(effort),
                selected: effort == widget.currentReasoningEffort,
                onTap: () => widget.onSelectReasoning(effort),
              );
            },
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final mediaQuery = MediaQuery.of(context);
    final dynamicMaxHeight =
        (mediaQuery.size.height - mediaQuery.viewInsets.bottom - 96)
            .clamp(180.0, widget.maxHeight)
            .toDouble();
    final body = switch (_page) {
      _AgentRunSettingsMenuPage.overview => _buildOverview(),
      _AgentRunSettingsMenuPage.models => _buildModelList(),
      _AgentRunSettingsMenuPage.reasoning => _buildReasoningList(),
    };
    return SizedBox(
      key: const ValueKey('chat-input-agent-run-settings-menu'),
      width: widget.width,
      child: OmniGlassPanel(
        width: widget.width,
        borderRadius: BorderRadius.circular(18),
        child: Material(
          color: Colors.transparent,
          child: ConstrainedBox(
            constraints: BoxConstraints(maxHeight: dynamicMaxHeight),
            child: AnimatedSwitcher(
              duration: _pageAnimationDuration,
              switchInCurve: Curves.easeOutCubic,
              switchOutCurve: Curves.easeInCubic,
              child: body,
            ),
          ),
        ),
      ),
    );
  }
}

class _ComposerFlowBorderPainter extends CustomPainter {
  final Animation<double> progress;
  final bool interactive;
  final bool focused;
  final bool forceStrong;
  final double radius;
  final double strokeWidth;
  final List<Color> gradientColors;

  _ComposerFlowBorderPainter({
    required this.progress,
    required this.interactive,
    required this.focused,
    required this.forceStrong,
    required this.radius,
    required this.strokeWidth,
    required this.gradientColors,
  }) : super(repaint: progress);

  @override
  void paint(Canvas canvas, Size size) {
    final flow = progress.value;
    final breath = (math.sin(flow * 2 * math.pi) + 1) / 2;
    final speed = focused ? 1.6 : 1.0;
    final shift = ((flow * speed) % 1.0) * 2 - 1;
    final rawOpacity = forceStrong
        ? 0.9
        : (interactive ? (focused ? 1.0 : 0.82) : (0.3 + breath * 0.4));
    final clampedOpacity = rawOpacity.clamp(0.0, 1.0);
    if (clampedOpacity <= 0 || size.isEmpty) return;

    final rect = Offset.zero & size;
    final rrect = RRect.fromRectAndRadius(
      rect.deflate(strokeWidth / 2),
      Radius.circular(radius - strokeWidth / 2),
    );
    final gradient = LinearGradient(
      begin: Alignment(-1 + shift, 0),
      end: Alignment(1 + shift, 0),
      colors: gradientColors
          .map((color) => color.withValues(alpha: clampedOpacity))
          .toList(growable: false),
      stops: const [0.0, 0.2, 0.4, 0.62, 0.82, 1.0],
    );

    final paint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth
      ..isAntiAlias = true
      ..shader = gradient.createShader(rect);

    canvas.drawRRect(rrect, paint);
  }

  @override
  bool shouldRepaint(covariant _ComposerFlowBorderPainter oldDelegate) {
    return oldDelegate.progress != progress ||
        oldDelegate.interactive != interactive ||
        oldDelegate.focused != focused ||
        oldDelegate.forceStrong != forceStrong ||
        oldDelegate.radius != radius ||
        oldDelegate.strokeWidth != strokeWidth ||
        oldDelegate.gradientColors != gradientColors;
  }
}
