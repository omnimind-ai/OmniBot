part of 'chat_conversation_runtime_coordinator.dart';

extension _ChatRuntimeInternalSupport on ChatConversationRuntimeCoordinator {
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
