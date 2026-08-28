part of 'chat_input_area.dart';

extension _ChatInputActionSupport on _ChatInputAreaStateBase {
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
  Widget _buildSendButton() {
    Widget icon;
    VoidCallback? onPressed;
    String iconKey;

    final action = _composerStateMachine.value.primaryAction(
      isProcessing: widget.isProcessing,
      hasAttachments: widget.attachments.isNotEmpty,
      hasExternalPayload: widget.hasExternalSendPayload,
      hasTextOverride: widget.controller.text.trim().isNotEmpty,
      supportsAttachmentFallback:
          widget.useAttachmentPickerForPlus && widget.onPickAttachment != null,
    );
    switch (action) {
      case ChatComposerPrimaryAction.cancel:
        icon = _pauseSvg;
        iconKey = 'pause';
        onPressed = widget.onCancelTask;
        break;
      case ChatComposerPrimaryAction.send:
        icon = _sendSvg;
        iconKey = 'send';
        onPressed = widget.onSendMessage;
        break;
      case ChatComposerPrimaryAction.addAttachment:
        icon = _addSvg;
        iconKey = 'add';
        onPressed = () {
          _hideLegacyPopup();
          widget.onPickAttachment?.call();
        };
        break;
      case ChatComposerPrimaryAction.disabled:
        icon = _addSvg;
        iconKey = 'add';
        onPressed = null;
        if (isPopupVisible) {
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (!mounted) return;
            _hideLegacyPopup();
          });
        }
        break;
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
