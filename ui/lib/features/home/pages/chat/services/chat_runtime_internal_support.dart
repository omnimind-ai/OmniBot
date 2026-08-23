part of 'chat_conversation_runtime_coordinator.dart';

extension _ChatRuntimeInternalSupport on ChatConversationRuntimeCoordinator {
  void _updateToolLayerState(
    ChatConversationRuntimeState runtime,
    AgentToolEventData event,
  ) {
    final toolType = event.toolType.trim();
    if (toolType != 'terminal' && toolType != 'browser') {
      return;
    }
    runtime.lastAgentToolType = toolType;
    runtime.chatIslandDisplayLayer = ChatIslandDisplayLayer.tools;
  }

  void _updateBrowserSessionSnapshot(
    ChatConversationRuntimeState runtime,
    AgentToolEventData event,
  ) {
    if (event.toolType.trim() != 'browser') {
      return;
    }
    final workspaceId = (event.workspaceId ?? '').trim();
    if (!event.success || workspaceId.isEmpty) {
      return;
    }
    final snapshot =
        ChatBrowserSessionSnapshot.tryParseBrowserToolJson(
          rawJson: event.rawResultJson,
          workspaceId: workspaceId,
        ) ??
        ChatBrowserSessionSnapshot.tryParseBrowserToolJson(
          rawJson: event.resultPreviewJson,
          workspaceId: workspaceId,
        );
    if (snapshot == null) {
      return;
    }
    runtime.browserSessionSnapshot = snapshot;
  }

  String _trimTerminalOutput(String value) {
    if (value.isEmpty) return value;

    var candidate = value;
    if (candidate.length > _maxTerminalOutputChars) {
      candidate = candidate.substring(
        candidate.length - _maxTerminalOutputChars,
      );
    }

    final lines = candidate.split('\n');
    if (lines.length > _maxTerminalOutputLines) {
      candidate = lines
          .sublist(lines.length - _maxTerminalOutputLines)
          .join('\n');
    }

    final wasTrimmed =
        candidate.length < value.length ||
        lines.length > _maxTerminalOutputLines;
    if (!wasTrimmed) {
      return candidate;
    }

    final notice = _isEnglish
        ? '[Only the most recent terminal output is shown]\n'
        : '[只显示最近的部分终端输出]\n';
    final body = candidate.startsWith(notice)
        ? candidate.substring(notice.length)
        : candidate;
    final remaining = _maxTerminalOutputChars - notice.length;
    return '$notice${body.substring(body.length > remaining ? body.length - remaining : 0)}';
  }

  String? _normalizeReasoningContent(String? value) {
    final normalized = value?.trim() ?? '';
    return normalized.isEmpty ? null : normalized;
  }

  void _removeOpenClawWaitingCard(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    final waitingCardId = '$taskId-openclaw-waiting';
    runtime.messages.removeWhere((msg) => msg.id == waitingCardId);
  }

  String _buildConversationHistoryText(List<ChatMessageModel> messages) {
    final buffer = StringBuffer();
    for (final message in messages) {
      if (message.user != 1) continue;
      final text = message.content?['text'] as String? ?? '';
      if (text.isEmpty) continue;
      buffer.write(_isEnglish ? 'User: $text\n' : '用户: $text\n');
    }
    return buffer.toString().trim();
  }

  ConversationMode _conversationModeFromRuntimeMode(
    String mode, {
    ConversationModel? conversation,
  }) {
    return mode == kChatRuntimeModeOpenClaw
        ? ConversationMode.openclaw
        : mode == kChatRuntimeModeAgent
        ? ConversationMode.agent
        : switch (conversation?.mode) {
            ConversationMode.chatOnly => ConversationMode.chatOnly,
            ConversationMode.subagent => ConversationMode.subagent,
            // `normal` is the legacy Xiaowan page/runtime label. Durable
            // Agent conversations now use the canonical ACP mode.
            _ => ConversationMode.agent,
          };
  }

  void _cancelPendingPersistence({
    required int conversationId,
    required String mode,
  }) {
    final key = _runtimeKey(conversationId: conversationId, mode: mode);
    final request = _pendingPersistence.remove(key);
    request?.timer.cancel();
  }

  String _runtimeKey({required int conversationId, required String mode}) {
    return '$mode:$conversationId';
  }
}
