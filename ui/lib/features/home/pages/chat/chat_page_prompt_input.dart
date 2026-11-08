part of 'chat_page.dart';

extension _ChatPagePromptInput on _ChatPageStateBase {
  bool get _canAcceptActivePromptInput =>
      _activeAcpAgentId == _kXiaowanAcpAgentId &&
      _activeMode != ChatPageMode.openclaw &&
      _isAiResponding &&
      _editingUserMessageId == null &&
      !_hasPendingAgentUserInputRequest &&
      _activeRuntime?.activeAcpSessionId != null;

  Future<void> _submitActivePromptInput({
    bool steer = false,
    String? text,
  }) async {
    final runtime = _activeRuntime;
    final sessionId = runtime?.activeAcpSessionId;
    final agentId = _activeAcpAgentId;
    if (runtime == null || sessionId == null || agentId == null) return;
    final inputValue = _messageController.value;
    final submittedText = (text ?? inputValue.text).trim();
    final attachments = List<ChatInputAttachment>.of(_pendingAttachments);
    if (submittedText.isEmpty && attachments.isEmpty) return;
    if (steer && attachments.isNotEmpty) return;
    if (runtime.isSteeringInputPending) return;
    final targetGeneration = _conversationTargetRequestId;
    final draftMode = _modeState(_activeMode);
    final requestId = 'input-${DateTime.now().microsecondsSinceEpoch}';
    final queued = ChatQueuedPrompt(
      id: requestId,
      sessionId: sessionId,
      agentId: agentId,
      text: submittedText,
      attachments: attachments
          .map((item) => item.toMap())
          .toList(growable: false),
      conversationMode: activeConversationModeValue.storageValue,
      model: _activeMode == ChatPageMode.agent
          ? _activeAgentModelId
          : _activeDispatchSceneSelection?.modelId,
      effort: _activeMode == ChatPageMode.agent
          ? _activeAgentReasoningEffort
          : _activeConversationReasoningEffort,
      approvalPolicy: _agentPermissionMode.approvalPolicy,
      approvalsReviewer: _agentPermissionMode.approvalsReviewer,
      sandboxPolicy: _agentPermissionMode.sandboxPolicy,
      terminalEnvironment: _buildAgentTerminalEnvironmentPayload(),
    );
    try {
      final injected =
          steer &&
          await _runtimeCoordinator.steerActivePrompt(
            conversationId: runtime.conversationId,
            mode: runtime.mode,
            agentId: agentId,
            text: submittedText,
            requestId: requestId,
          );
      if (!injected) {
        await _runtimeCoordinator.enqueuePrompt(
          conversationId: runtime.conversationId,
          mode: runtime.mode,
          prompt: queued,
        );
      }
      // Keep edits made during steering admission, and never clear another
      // conversation's composer after the page has switched targets.
      if (!mounted || targetGeneration != _conversationTargetRequestId) return;
      setState(() {
        draftMode.consumeMessageAttachments(queued.attachments);
        if (_messageController.value == inputValue) {
          _messageController.clear();
          draftMode.draftMessage = '';
        }
      });
      if (steer && !injected) {
        showToast(
          LegacyTextLocalizer.isEnglish ? 'Follow-up queued.' : '已加入队列',
        );
      }
    } catch (error) {
      if (mounted && targetGeneration == _conversationTargetRequestId) {
        showToast(formatAgentRuntimeErrorForUser(error), type: ToastType.error);
      }
    }
  }

  Widget? _buildPendingPromptQueue() {
    final runtime = _activeRuntime;
    if (runtime == null) return null;
    final unconfirmed = runtime.isSteeringInputPending
        ? null
        : runtime.unconfirmedSteeringText;
    if (runtime.pendingPrompts.isEmpty && unconfirmed == null) return null;
    final english = Localizations.localeOf(context).languageCode == 'en';
    final palette = context.omniPalette;
    return Padding(
      key: const ValueKey('chat-prompt-queue'),
      padding: const EdgeInsets.fromLTRB(4, 0, 4, 4),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (unconfirmed != null) ...[
            Row(
              children: [
                Expanded(
                  child: Text(
                    english ? 'Follow-up awaiting confirmation' : '补充状态待确认',
                    style: TextStyle(
                      fontSize: 12,
                      color: palette.textSecondary,
                    ),
                  ),
                ),
                TextButton(
                  key: const ValueKey('chat-steering-receipt-dismiss'),
                  onPressed: () async {
                    try {
                      await _runtimeCoordinator.dismissUnconfirmedSteering(
                        conversationId: runtime.conversationId,
                        mode: runtime.mode,
                      );
                    } catch (error) {
                      if (mounted)
                        showToast(
                          formatAgentRuntimeErrorForUser(error),
                          type: ToastType.error,
                        );
                    }
                  },
                  child: Text(english ? 'Reviewed' : '已查看'),
                ),
              ],
            ),
            Align(
              alignment: Alignment.centerLeft,
              child: Text(
                unconfirmed,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 13, color: palette.textPrimary),
              ),
            ),
            Align(
              alignment: Alignment.centerLeft,
              child: Text(
                english
                    ? 'Check the conversation before sending it again.'
                    : '请先查看对话记录，再决定是否重发',
                style: TextStyle(fontSize: 12, color: palette.textSecondary),
              ),
            ),
          ],
          if (runtime.pendingPrompts.isNotEmpty)
            Row(
              children: [
                Icon(
                  LucideIcons.listOrdered,
                  size: 14,
                  color: palette.textSecondary,
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    runtime.promptQueuePaused
                        ? (english ? 'Queue paused' : '队列已暂停')
                        : (english
                              ? '${runtime.pendingPrompts.length} queued'
                              : '${runtime.pendingPrompts.length} 条待发送'),
                    style: TextStyle(
                      fontSize: 12,
                      color: palette.textSecondary,
                    ),
                  ),
                ),
                if (runtime.promptQueuePaused && !runtime.hasInFlightTask)
                  TextButton(
                    key: const ValueKey('chat-prompt-queue-resume'),
                    onPressed: () => _runtimeCoordinator.resumePromptQueue(
                      conversationId: runtime.conversationId,
                      mode: runtime.mode,
                    ),
                    child: Text(english ? 'Resume' : '继续发送'),
                  ),
              ],
            ),
          ConstrainedBox(
            constraints: const BoxConstraints(maxHeight: 100),
            child: ListView.builder(
              padding: EdgeInsets.zero,
              shrinkWrap: true,
              itemCount: runtime.pendingPrompts.length,
              itemBuilder: (context, index) {
                final prompt = runtime.pendingPrompts[index];
                return Row(
                  key: ValueKey('queued-prompt-${prompt.id}'),
                  children: [
                    Expanded(
                      child: Text(
                        prompt.text.isNotEmpty
                            ? prompt.text
                            : (english ? 'Attachment' : '附件'),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 13,
                          color: palette.textPrimary,
                        ),
                      ),
                    ),
                    IconButton(
                      visualDensity: VisualDensity.compact,
                      tooltip: english ? 'Remove from queue' : '移出队列',
                      icon: const Icon(LucideIcons.x, size: 15),
                      onPressed: () => _runtimeCoordinator.removeQueuedPrompt(
                        conversationId: runtime.conversationId,
                        mode: runtime.mode,
                        promptId: prompt.id,
                      ),
                    ),
                  ],
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
