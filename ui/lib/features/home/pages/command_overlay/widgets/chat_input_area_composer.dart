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

const List<String> _kDefaultCodexReasoningEfforts = <String>[
  'low',
  'medium',
  'high',
  'xhigh',
];

enum _CodexRunSettingsMenuKind { model, effort }

class _CodexRunSettingsMenuAction {
  const _CodexRunSettingsMenuAction._(this.kind, this.value);

  const _CodexRunSettingsMenuAction.model(String value)
    : this._(_CodexRunSettingsMenuKind.model, value);

  const _CodexRunSettingsMenuAction.effort(String value)
    : this._(_CodexRunSettingsMenuKind.effort, value);

  final _CodexRunSettingsMenuKind kind;
  final String value;
}

mixin _ChatInputAreaComposerMixin on _ChatInputAreaStateBase {
  final GlobalKey _codexRunSettingsButtonKey = GlobalKey(
    debugLabel: 'codex-run-settings-button',
  );
  final GlobalKey _modelPickerButtonKey = GlobalKey(
    debugLabel: 'chat-model-picker-button',
  );
  final GlobalKey _codexPermissionButtonKey = GlobalKey(
    debugLabel: 'codex-permission-button',
  );

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
            composerState.hasText || widget.attachments.isNotEmpty;
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
            composerState.hasText || widget.attachments.isNotEmpty;

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
      if (_shouldShowCodexRunSettingsSelector) ...[
        _buildCodexRunSettingsButton(compact: false),
        const SizedBox(width: 4),
      ],
      if (_shouldShowModelPicker) ...[
        _buildModelPickerButton(compact: false),
        const SizedBox(width: 4),
      ],
      if (_shouldShowCodexPermissionSelector) ...[
        SizedBox(
          width: 28,
          height: 28,
          child: _buildCodexPermissionButton(iconSize: 20),
        ),
        const SizedBox(width: 4),
      ],
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
        if (_shouldShowCodexRunSettingsSelector) ...[
          _buildCodexRunSettingsButton(compact: true),
          const SizedBox(width: 2),
        ],
        if (_shouldShowModelPicker) ...[
          _buildModelPickerButton(compact: true),
          const SizedBox(width: 2),
        ],
        if (_shouldShowCodexPermissionSelector) ...[
          SizedBox(
            width: 24,
            height: 24,
            child: _buildCodexPermissionButton(iconSize: 18),
          ),
          const SizedBox(width: 2),
        ],
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

  bool get _shouldShowCodexPermissionSelector =>
      widget.codexPermissionMode != null &&
      widget.onCodexPermissionModeChanged != null;

  bool get _shouldShowCodexRunSettingsSelector =>
      widget.codexRunSettings != null &&
      widget.onCodexRunSettingsChanged != null;

  bool get _shouldShowModelPicker => widget.modelPickerSettings != null;

  Widget _buildModelPickerButton({required bool compact}) {
    final settings = widget.modelPickerSettings!;
    final palette = context.omniPalette;
    final modelId = settings.modelId.trim();
    final english = Localizations.localeOf(context).languageCode == 'en';
    final selectedColor = palette.accentPrimary;
    final enabled = settings.hasSelectableModels;
    final vendor = modelId.isEmpty ? null : ModelVendorCatalog.resolve(modelId);

    Future<void> openPicker() async {
      final anchorContext = _modelPickerButtonKey.currentContext;
      if (anchorContext == null || !enabled) {
        return;
      }
      _modelPickerSpinController.forward(from: 0);
      await Future<void>.sync(() => settings.onOpen(anchorContext));
    }

    return TextFieldTapRegion(
      child: SizedBox(
      key: _modelPickerButtonKey,
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

  Widget _buildCodexRunSettingsButton({required bool compact}) {
    final settings = widget.codexRunSettings!;
    final palette = context.omniPalette;
    final modelId = settings.modelId.trim();
    final effort = settings.reasoningEffort.trim();
    final english = Localizations.localeOf(context).languageCode == 'en';
    final displayModel = modelId.isEmpty
        ? (settings.isLoadingModels
              ? (english ? 'Loading' : '加载中')
              : (english ? 'Model' : '模型'))
        : _shortModelLabel(modelId);
    final displayEffort = effort.isEmpty
        ? ''
        : _codexReasoningEffortLabel(effort, compact: true);
    final displayText = displayEffort.isEmpty
        ? displayModel
        : '$displayModel · $displayEffort';
    final selectedColor = palette.accentPrimary;
    final menuTextColor = context.isDarkTheme
        ? palette.textPrimary
        : const Color(0xFF26364D);

    final buttonKey = _codexRunSettingsButtonKey;

    Future<void> openMenu() async {
      final anchor = glassPopupAnchorFromContext(buttonKey.currentContext!);
      if (anchor == null) {
        return;
      }
      final opened = widget.onCodexRunSettingsOpened;
      if (opened != null) {
        unawaited(Future<void>.sync(opened));
      }
      final modelOptions = _codexRunSettingsOptions(
        current: modelId,
        options: settings.modelOptions,
      );
      final effortOptions = _codexRunSettingsOptions(
        current: effort,
        options: settings.reasoningEffortOptions.isEmpty
            ? _kDefaultCodexReasoningEfforts
            : settings.reasoningEffortOptions,
      );
      final disabledModelLabel = settings.isLoadingModels
          ? (english ? 'Loading...' : '正在获取模型...')
          : (settings.modelListError?.trim().isNotEmpty ?? false)
          ? (english ? 'Load failed' : '模型获取失败')
          : (english ? 'No models available' : '未获取到可用模型');
      final action = await showGlassPopup<_CodexRunSettingsMenuAction>(
        context: context,
        anchor: anchor,
        child: _CodexRunSettingsGlassMenuContent(
          width: 220,
          modelHeader: english ? 'Model' : '模型',
          reasoningHeader: english ? 'Reasoning' : '推理强度',
          modelOptions: modelOptions,
          disabledModelLabel: disabledModelLabel,
          effortOptions: [
            for (final option in effortOptions)
              _CodexRunSettingsOptionData(
                value: option,
                label: _codexReasoningEffortLabel(option),
              ),
          ],
          selectedModelId: modelId,
          selectedEffort: effort,
          selectedColor: selectedColor,
          textColor: menuTextColor,
        ),
      );
      if (action == null) return;
      final changed = widget.onCodexRunSettingsChanged;
      if (changed == null) return;
      unawaited(
        Future<void>.sync(() {
          if (action.kind == _CodexRunSettingsMenuKind.model) {
            return changed(modelId: action.value);
          }
          return changed(reasoningEffort: action.value);
        }),
      );
    }

    return SizedBox(
      key: buttonKey,
      width: compact ? 92 : 118,
      height: compact ? 24 : 28,
      child: Tooltip(
        message: [
          if (modelId.isNotEmpty) modelId,
          if (effort.isNotEmpty) _codexReasoningEffortLabel(effort),
        ].join(' · '),
        waitDuration: const Duration(milliseconds: 400),
        child: InkWell(
          key: const ValueKey('chat-input-codex-run-settings-button'),
          borderRadius: BorderRadius.circular(8),
          onTap: openMenu,
          child: AnimatedContainer(
            duration: _buttonAnimationDuration,
            curve: _buttonAnimationCurve,
            height: compact ? 24 : 28,
            padding: EdgeInsets.only(
              left: compact ? 4 : 6,
              right: compact ? 2 : 4,
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Expanded(
                  child: Text(
                    displayText,
                    textAlign: TextAlign.right,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: selectedColor,
                      fontSize: compact ? 11 : 12,
                      height: 1.1,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                const SizedBox(width: 2),
                Icon(
                  Icons.expand_more_rounded,
                  size: compact ? 14 : 16,
                  color: selectedColor,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  String _codexReasoningEffortLabel(String effort, {bool compact = false}) {
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

  String _shortModelLabel(String modelId, {int maxLength = 22}) {
    final normalized = modelId.trim();
    if (normalized.length <= maxLength) {
      return normalized;
    }
    final parts = normalized.split(RegExp(r'[-_/]'));
    if (parts.length >= 3) {
      final compact = parts.take(4).join('-');
      if (compact.length <= maxLength) {
        return compact;
      }
    }
    final prefix = normalized
        .substring(0, math.max(1, maxLength - 3))
        .replaceFirst(RegExp(r'[-_/]+$'), '');
    return '$prefix...';
  }

  List<String> _codexRunSettingsOptions({
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

  Widget _buildCodexPermissionButton({required double iconSize}) {
    final selected =
        widget.codexPermissionMode ?? CodexPermissionMode.fullAccess;
    final palette = context.omniPalette;
    final selectedColor = context.isDarkTheme
        ? palette.accentPrimary
        : const Color(0xFF2F65D9);
    final inactiveColor = context.isDarkTheme
        ? palette.textSecondary
        : const Color(0xFF5E6C84);

    final buttonKey = _codexPermissionButtonKey;

    Future<void> openMenu() async {
      final anchor = glassPopupAnchorFromContext(buttonKey.currentContext!);
      if (anchor == null) {
        return;
      }
      final mode = await showGlassPopup<CodexPermissionMode>(
        context: context,
        anchor: anchor,
        child: _CodexPermissionGlassMenuContent(
          width: 196,
          selected: selected,
          selectedColor: selectedColor,
          inactiveColor: inactiveColor,
          textColor: context.isDarkTheme
              ? palette.textPrimary
              : const Color(0xFF232D3D),
          options: [
            for (final mode in CodexPermissionMode.values)
              _CodexPermissionOptionData(
                mode: mode,
                label: _codexPermissionLabel(mode),
                iconAsset: _codexPermissionIconAsset(mode),
              ),
          ],
        ),
      );
      if (mode == null) return;
      widget.onCodexPermissionModeChanged?.call(mode);
    }

    return Tooltip(
      message: _codexPermissionTooltip(),
      waitDuration: const Duration(milliseconds: 400),
      child: InkWell(
        key: const ValueKey('chat-input-codex-permission-button'),
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
            child: _buildCodexPermissionIcon(
              selected,
              size: iconSize,
              color: selectedColor,
            ),
          ),
        ),
      ),
    );
  }

  String _codexPermissionTooltip() {
    return Localizations.localeOf(context).languageCode == 'en'
        ? 'Codex permissions'
        : 'Codex 权限';
  }

  String _codexPermissionLabel(CodexPermissionMode mode) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    return switch (mode) {
      CodexPermissionMode.defaultMode =>
        english ? 'Default permissions' : '默认权限',
      CodexPermissionMode.autoReview => english ? 'Auto review' : '自动审查',
      CodexPermissionMode.fullAccess => english ? 'Full access' : '完全访问权限',
    };
  }

  String _codexPermissionIconAsset(CodexPermissionMode mode) {
    return switch (mode) {
      CodexPermissionMode.defaultMode => _kCodexPermissionDefaultIconAsset,
      CodexPermissionMode.autoReview => _kCodexPermissionAutoReviewIconAsset,
      CodexPermissionMode.fullAccess => _kCodexPermissionFullAccessIconAsset,
    };
  }

  Widget _buildCodexPermissionIcon(
    CodexPermissionMode mode, {
    required double size,
    required Color color,
  }) {
    return SvgPicture.asset(
      _codexPermissionIconAsset(mode),
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
          onSubmitted: useKeyboardNewline
              ? null
              : (_) {
                  if (widget.controller.text.trim().isNotEmpty) {
                    widget.onSendMessage();
                  } else {
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

class _CodexPermissionOptionData {
  const _CodexPermissionOptionData({
    required this.mode,
    required this.label,
    required this.iconAsset,
  });

  final CodexPermissionMode mode;
  final String label;
  final String iconAsset;
}

class _CodexPermissionGlassMenuContent extends StatefulWidget {
  const _CodexPermissionGlassMenuContent({
    required this.width,
    required this.options,
    required this.selected,
    required this.selectedColor,
    required this.inactiveColor,
    required this.textColor,
  });

  static const double _rowHeight = 42;

  final double width;
  final List<_CodexPermissionOptionData> options;
  final CodexPermissionMode selected;
  final Color selectedColor;
  final Color inactiveColor;
  final Color textColor;

  @override
  State<_CodexPermissionGlassMenuContent> createState() =>
      _CodexPermissionGlassMenuContentState();
}

class _CodexPermissionGlassMenuContentState
    extends State<_CodexPermissionGlassMenuContent> {
  static const Duration _selectionDuration = Duration(milliseconds: 160);

  void _select(CodexPermissionMode mode) {
    Navigator.of(context).pop(mode);
  }

  Widget _buildIcon(_CodexPermissionOptionData option, bool selected) {
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

  Widget _buildRow(_CodexPermissionOptionData option) {
    final isSelected = option.mode == widget.selected;
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final selectedBackground = isDark
        ? Color.alphaBlend(
            widget.selectedColor.withValues(alpha: 0.18),
            palette.surfaceSecondary.withValues(alpha: 0.52),
          )
        : widget.selectedColor.withValues(alpha: 0.10);
    final idleBackground = isDark
        ? palette.surfaceSecondary.withValues(alpha: 0.34)
        : Colors.white.withValues(alpha: 0.26);
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 2, 8, 2),
      child: InkWell(
        key: ValueKey('chat-input-codex-permission-option-${option.mode.name}'),
        onTap: () => _select(option.mode),
        borderRadius: BorderRadius.circular(12),
        child: AnimatedContainer(
          duration: _selectionDuration,
          curve: Curves.easeOutCubic,
          constraints: const BoxConstraints(
            minHeight: _CodexPermissionGlassMenuContent._rowHeight,
          ),
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 9),
          decoration: BoxDecoration(
            color: isSelected ? selectedBackground : idleBackground,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(
              color: isSelected
                  ? widget.selectedColor.withValues(alpha: isDark ? 0.30 : 0.20)
                  : (isDark
                        ? palette.borderSubtle.withValues(alpha: 0.48)
                        : Colors.white.withValues(alpha: 0.42)),
            ),
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
                    fontWeight: isSelected ? FontWeight.w700 : FontWeight.w500,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              AnimatedOpacity(
                duration: _selectionDuration,
                opacity: isSelected ? 1 : 0,
                child: Icon(
                  Icons.check_rounded,
                  size: 16,
                  color: widget.selectedColor,
                ),
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

class _CodexRunSettingsOptionData {
  const _CodexRunSettingsOptionData({required this.value, required this.label});

  final String value;
  final String label;
}

class _CodexRunSettingsGlassMenuContent extends StatefulWidget {
  const _CodexRunSettingsGlassMenuContent({
    required this.width,
    required this.modelHeader,
    required this.reasoningHeader,
    required this.modelOptions,
    required this.disabledModelLabel,
    required this.effortOptions,
    required this.selectedModelId,
    required this.selectedEffort,
    required this.selectedColor,
    required this.textColor,
  });

  static const double _maxHeight = 380;
  static const double _rowHeight = 34;

  final double width;
  final String modelHeader;
  final String reasoningHeader;
  final List<String> modelOptions;
  final String disabledModelLabel;
  final List<_CodexRunSettingsOptionData> effortOptions;
  final String selectedModelId;
  final String selectedEffort;
  final Color selectedColor;
  final Color textColor;

  @override
  State<_CodexRunSettingsGlassMenuContent> createState() =>
      _CodexRunSettingsGlassMenuContentState();
}

class _CodexRunSettingsGlassMenuContentState
    extends State<_CodexRunSettingsGlassMenuContent> {
  static const Duration _checkAnimationDuration = Duration(milliseconds: 160);

  void _select(_CodexRunSettingsMenuAction action) {
    Navigator.of(context).pop(action);
  }

  Widget _buildHeader(String label) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 9, 14, 5),
      child: Text(
        label,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          color: context.isDarkTheme
              ? palette.textSecondary
              : const Color(0xFF66758E),
          fontSize: 11,
          fontWeight: FontWeight.w700,
          height: 1.1,
        ),
      ),
    );
  }

  Widget _buildDisabledItem(String label) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 2, 8, 2),
      child: Container(
        constraints: const BoxConstraints(
          minHeight: _CodexRunSettingsGlassMenuContent._rowHeight,
        ),
        alignment: Alignment.centerLeft,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: context.isDarkTheme
              ? palette.surfaceSecondary.withValues(alpha: 0.34)
              : Colors.white.withValues(alpha: 0.26),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Text(
          label,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            color: palette.textTertiary,
            fontSize: 12,
            height: 1.1,
            fontWeight: FontWeight.w500,
          ),
        ),
      ),
    );
  }

  Widget _buildOption({
    required String keySuffix,
    required String label,
    required bool selected,
    required _CodexRunSettingsMenuAction action,
  }) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final selectedBackground = isDark
        ? Color.alphaBlend(
            widget.selectedColor.withValues(alpha: 0.18),
            palette.surfaceSecondary.withValues(alpha: 0.52),
          )
        : widget.selectedColor.withValues(alpha: 0.10);
    final idleBackground = isDark
        ? palette.surfaceSecondary.withValues(alpha: 0.34)
        : Colors.white.withValues(alpha: 0.26);
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 2, 8, 2),
      child: InkWell(
        key: ValueKey('chat-input-codex-run-settings-option-$keySuffix'),
        onTap: () => _select(action),
        borderRadius: BorderRadius.circular(12),
        child: AnimatedContainer(
          duration: _checkAnimationDuration,
          curve: Curves.easeOutCubic,
          constraints: const BoxConstraints(
            minHeight: _CodexRunSettingsGlassMenuContent._rowHeight,
          ),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          decoration: BoxDecoration(
            color: selected ? selectedBackground : idleBackground,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(
              color: selected
                  ? widget.selectedColor.withValues(alpha: isDark ? 0.30 : 0.20)
                  : (isDark
                        ? palette.borderSubtle.withValues(alpha: 0.48)
                        : Colors.white.withValues(alpha: 0.42)),
            ),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Expanded(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 12,
                    height: 1.1,
                    color: selected
                        ? (isDark ? palette.textPrimary : widget.textColor)
                        : widget.textColor,
                    fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              AnimatedOpacity(
                duration: _checkAnimationDuration,
                opacity: selected ? 1 : 0,
                child: Icon(
                  Icons.check_rounded,
                  size: 15,
                  color: widget.selectedColor,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildDivider() {
    final palette = context.omniPalette;
    return Container(
      height: 1,
      margin: const EdgeInsets.fromLTRB(14, 6, 14, 2),
      color: context.isDarkTheme
          ? palette.borderSubtle.withValues(alpha: 0.56)
          : Colors.white.withValues(alpha: 0.64),
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
          child: ConstrainedBox(
            constraints: const BoxConstraints(
              maxHeight: _CodexRunSettingsGlassMenuContent._maxHeight,
            ),
            child: Scrollbar(
              child: SingleChildScrollView(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    _buildHeader(widget.modelHeader),
                    if (widget.modelOptions.isEmpty)
                      _buildDisabledItem(widget.disabledModelLabel)
                    else
                      for (final option in widget.modelOptions)
                        _buildOption(
                          keySuffix: 'model-$option',
                          label: option,
                          selected: option == widget.selectedModelId,
                          action: _CodexRunSettingsMenuAction.model(option),
                        ),
                    _buildDivider(),
                    _buildHeader(widget.reasoningHeader),
                    for (final option in widget.effortOptions)
                      _buildOption(
                        keySuffix: 'effort-${option.value}',
                        label: option.label,
                        selected: option.value == widget.selectedEffort,
                        action: _CodexRunSettingsMenuAction.effort(
                          option.value,
                        ),
                      ),
                  ],
                ),
              ),
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
