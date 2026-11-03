part of 'chat_page.dart';

/// Presentation for an unbound draft. Conversation/session admission remains
/// with the existing target lifecycle and send barrier.
mixin _ChatPageNewConversationMixin on _ChatPageStateBase {
  @override
  bool get _showNewConversationModeSelector =>
      !_isWorkspaceSurface &&
      !_isConversationHistoryLoading &&
      !_isFirstUseTourActive &&
      _resolvedThreadTarget?.isNewConversation == true &&
      (_resolvedThreadTarget?.initialMessage?.trim().isEmpty ?? true) &&
      _currentConversationId == null &&
      _messages.isEmpty &&
      !_isAiResponding;

  @override
  bool get _canSwitchNewConversationMode =>
      _showNewConversationModeSelector &&
      !_harnessSwitchSendBarrier.isActive &&
      !_sendMessageInFlightTargetIds.contains(_conversationTargetRequestId);

  @override
  String get _newConversationModeId =>
      _activeMode == ChatPageMode.normal && _isPureChatSelected
      ? kNewConversationPureChatModeId
      : (_activeAcpAgentId ?? _kXiaowanAcpAgentId);

  @override
  Future<void> _selectNewConversationMode(String modeId) async {
    final id = modeId.trim();
    if (!_canSwitchNewConversationMode || id == _newConversationModeId) {
      return;
    }
    final pureChat = id == kNewConversationPureChatModeId;
    bool isAvailable() =>
        pureChat ||
        _chatAcpAgentModeOptions.any(
          (option) => option.id == id && option.isAvailable,
        );
    if (!isAvailable()) return;

    final originRequestId = _conversationTargetRequestId;
    final generation = _harnessSwitchSendBarrier.begin();
    setState(() {});
    var applied = false;
    try {
      // The first frame can be interactive while the initial target is still
      // loading. Finish that owner before applying the user's next draft.
      await _conversationBootstrapFuture;
      if (!mounted ||
          !_isConversationTargetRequestCurrent(originRequestId) ||
          !_showNewConversationModeSelector) {
        return;
      }
      await _harnessSwitchSendBarrier.runIfCurrent(generation, () async {
        if (!_isConversationTargetRequestCurrent(originRequestId) ||
            !_showNewConversationModeSelector ||
            !isAvailable()) {
          return;
        }
        final target = ConversationThreadTarget.newConversation(
          mode: pureChat ? ConversationMode.chatOnly : ConversationMode.agent,
          agentId: pureChat ? null : id,
          requestKey: DateTime.now().microsecondsSinceEpoch.toString(),
        );
        // An explicit draft target owns the choice, just like the home entry.
        // Do not mutate the globally selected native Agent or an old session.
        await _applyConversationThreadTarget(target, preserveComposer: true);
        applied =
            mounted &&
            _resolvedThreadTarget?.requestKey == target.requestKey &&
            _newConversationModeId == id;
      });
    } catch (error) {
      if (mounted &&
          _harnessSwitchSendBarrier.isCurrent(generation) &&
          _showNewConversationModeSelector) {
        showToast(
          LegacyTextLocalizer.isEnglish
              ? 'Unable to switch conversation mode: $error'
              : '切换会话模式失败：$error',
          type: ToastType.error,
        );
      }
    } finally {
      _harnessSwitchSendBarrier.finish(generation, succeeded: applied);
      if (mounted) setState(() {});
    }
  }
}
