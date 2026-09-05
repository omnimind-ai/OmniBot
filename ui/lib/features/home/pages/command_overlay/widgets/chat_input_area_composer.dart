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
    return TextFieldTapRegion(
      onTapOutside: widget.isEditingUserMessage
          ? (_) {
              widget.focusNode.unfocus();
              widget.onCancelUserMessageEditing?.call();
            }
          : null,
      child: NotificationListener<SizeChangedLayoutNotification>(
        onNotification: (_) {
          _reportInputHeightAfterBuild();
          return false;
        },
        child: SizeChangedLayoutNotifier(child: composer),
      ),
    );
  }

  /// 构建输入框内容区域（按钮、文本框等）
  Widget _buildInputContent() {
    return AnimatedBuilder(
      // Draft restoration and platform IME updates can change the controller
      // without producing the expected composer-state transition. The
      // controller is the source of truth for whether send is available.
      animation: widget.controller,
      builder: (context, _) {
        return ValueListenableBuilder<ChatComposerState>(
          valueListenable: _composerStateMachine,
          builder: (context, composerState, _) {
            final openClawButton = _buildOpenClawButton();
            return Row(
              children: [
                Expanded(child: _buildTextField()),
                const SizedBox(width: 9),
                _buildAnimatedButtonRow(openClawButton: openClawButton),
              ],
            );
          },
        );
      },
    );
  }

  Widget _buildLargeComposer() {
    return ValueListenableBuilder<ChatComposerState>(
      valueListenable: _composerStateMachine,
      builder: (context, composerState, _) {
        return Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (widget.attachments.isNotEmpty) ...[
              _buildAttachmentPreview(),
              const SizedBox(height: 8),
            ],
            if (!widget.isEditingUserMessage &&
                (widget.selectedModelOverrideId ?? '').trim().isNotEmpty) ...[
              _buildSelectedModelOverrideChip(),
              const SizedBox(height: 8),
            ],
            _buildTextField(
              multiline: true,
              expanded: composerState.expandsTextField,
            ),
            const SizedBox(height: 6),
            _buildLargeActionRow(),
          ],
        );
      },
    );
  }

  Widget _buildLargeActionRow() {
    if (widget.isEditingUserMessage) {
      return Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          SizedBox(width: 28, height: 28, child: _buildLargeAddButton()),
          const Spacer(),
          SizedBox(width: 28, height: 28, child: _buildLargeSendOrStopButton()),
        ],
      );
    }

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
      if (widget.runtimeConfigButton != null) ...[
        widget.runtimeConfigButton!,
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
        child: _buildTerminalButton(iconSize: 22),
      ),
      const SizedBox(width: 6),
      SizedBox(width: 28, height: 28, child: _buildLargeSendOrStopButton()),
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
    final isEnglish = Localizations.localeOf(context).languageCode == 'en';
    return IconButton(
      key: const ValueKey('chat-input-add-or-cancel-edit-button'),
      padding: EdgeInsets.zero,
      iconSize: 20,
      tooltip: widget.isEditingUserMessage
          ? (isEnglish ? 'Exit editing' : '退出编辑')
          : (isEnglish ? 'Add attachment' : '添加附件'),
      icon: AnimatedRotation(
        key: const ValueKey('chat-input-add-or-cancel-edit-icon'),
        turns: widget.isEditingUserMessage ? 0.125 : 0,
        duration: _buttonAnimationDuration,
        curve: _buttonAnimationCurve,
        child: _addSvg,
      ),
      onPressed: () {
        if (widget.isEditingUserMessage) {
          widget.onCancelUserMessageEditing?.call();
          return;
        }

        if (widget.useAttachmentPickerForPlus &&
            widget.onPickAttachment != null) {
          _hideLegacyPopup();
          widget.onPickAttachment?.call();
          return;
        }

        _hideLegacyPopup(alwaysNotify: true);
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
              _hideLegacyPopup();
              widget.onTriggerSlashCommand?.call();
            },
    );
  }

  Widget _buildLargeSendOrStopButton() {
    final action = _composerStateMachine.value.primaryAction(
      isProcessing: widget.isProcessing,
      hasAttachments: widget.attachments.isNotEmpty,
      hasExternalPayload: widget.hasExternalSendPayload,
      hasTextOverride: widget.controller.text.trim().isNotEmpty,
    );
    final canTap = action != ChatComposerPrimaryAction.disabled;
    final icon = action == ChatComposerPrimaryAction.cancel
        ? _pauseSvg
        : _sendSvg;

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
          child: SizedBox(
            key: ValueKey<ChatComposerPrimaryAction>(action),
            child: icon,
          ),
        ),
        onPressed: !canTap
            ? null
            : () {
                switch (action) {
                  case ChatComposerPrimaryAction.cancel:
                    widget.onCancelTask();
                    break;
                  case ChatComposerPrimaryAction.send:
                    widget.onSendMessage();
                    break;
                  case ChatComposerPrimaryAction.addAttachment:
                  case ChatComposerPrimaryAction.disabled:
                    break;
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
        _composerStateMachine.hoverChanged(true);
      },
      onExit: (_) {
        _composerStateMachine.hoverChanged(false);
      },
      child: ValueListenableBuilder<ChatComposerState>(
        valueListenable: _composerStateMachine,
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
          final hovered = composerState.isHovered;
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

  Widget _buildAnimatedButtonRow({required Widget? openClawButton}) {
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
        if (widget.runtimeConfigButton != null) ...[
          widget.runtimeConfigButton!,
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
          child: _buildTerminalButton(iconSize: 20),
        ),
        const SizedBox(width: 2),
        // 发送/添加按钮
        _buildSendButton(),
      ],
    );
  }
}
