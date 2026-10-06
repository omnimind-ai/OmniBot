part of 'chat_conversation_runtime_coordinator.dart';

/// Outcome of one native [ChatPromptDispatcher.launchTurn] (batch 5d-0b).
class ChatTurnLaunchOutcome {
  const ChatTurnLaunchOutcome._({
    required this.status,
    this.rejectedReason,
    this.sessionId,
    this.threadId,
    this.turnId,
    this.responseConversationId,
    this.targetCurrent = false,
  });

  factory ChatTurnLaunchOutcome._fromChannel(dynamic raw) {
    final map = raw is Map ? raw : const <dynamic, dynamic>{};
    String? text(String key) {
      final value = map[key]?.toString().trim();
      return value == null || value.isEmpty ? null : value;
    }

    final rawConversationId = map['responseConversationId'];
    return ChatTurnLaunchOutcome._(
      status: text('status') ?? 'failed',
      rejectedReason: text('rejectedReason'),
      sessionId: text('sessionId'),
      threadId: text('threadId'),
      turnId: text('turnId'),
      responseConversationId: rawConversationId is num
          ? rawConversationId.toInt()
          : int.tryParse(rawConversationId?.toString() ?? ''),
      targetCurrent: map['targetCurrent'] == true,
    );
  }

  /// `completed`, `failed` (already projected as this run's PromptResponse)
  /// or `rejected` (never sent; see [rejectedReason]).
  final String status;
  final String? rejectedReason;

  /// Pointers are null when the submitting target is no longer current.
  final String? sessionId;
  final String? threadId;
  final String? turnId;
  final int? responseConversationId;
  final bool targetCurrent;

  bool get completed => status == 'completed';
}

/// Flutter intents for the native prompt dispatcher.
///
/// Every chat send is one [launchTurn] (batch 5d-0b): the native turn
/// launcher admits the run, persists the admission snapshot, reserves the ACP
/// session, sends `session/prompt` and applies the PromptResponse or error
/// through the native coordinator. Pages freeze their per-turn settings,
/// publish their navigation generation ([setSurfaceGeneration]) and adopt
/// the returned pointers. They never call the ACP transport for prompts.
class ChatPromptDispatcher {
  ChatPromptDispatcher._(this._coordinator);

  static final ChatPromptDispatcher instance = ChatPromptDispatcher._(
    ChatConversationRuntimeCoordinator.instance,
  );

  final ChatConversationRuntimeCoordinator _coordinator;

  /// Publishes the navigation generation a surface shows. A turn launched
  /// for an older generation stops natively between its awaits.
  Future<void> setSurfaceGeneration(String surfaceId, int generation) async {
    await _coordinator._invoke('setSurfaceGeneration', <String, dynamic>{
      'surfaceId': surfaceId,
      'generation': generation,
    });
  }

  /// Sends one admitted submission through the native turn launcher
  /// (batch 5d-0b): user row, admission, persistence, session reservation,
  /// prompt and failure projection happen natively. The caller passes its
  /// frozen per-turn settings and applies the returned pointers.
  Future<ChatTurnLaunchOutcome> launchTurn({
    required String taskId,
    required int conversationId,
    required String mode,
    required String text,
    required String surfaceId,
    required int generation,
    List<Map<String, dynamic>> attachments = const <Map<String, dynamic>>[],
    ChatMessageModel? userMessage,
    String? existingSessionId,
    String? agentId,
    String? permissionMode,
    String? model,
    String? effort,
    String? collaborationMode,
    String? conversationMode,
    Map<String, String>? terminalEnvironment,
    ConversationModel? conversation,
    bool clearThinkingOnFailure = false,
  }) async {
    final result = await _coordinator._invoke('launchTurn', <String, dynamic>{
      ..._coordinator._target(conversationId, mode),
      'taskId': taskId,
      'text': text,
      'surfaceId': surfaceId,
      'generation': generation,
      if (attachments.isNotEmpty) 'attachments': attachments,
      if (userMessage != null) 'userMessage': userMessage.toJson(),
      'existingSessionId': ?existingSessionId,
      'agentId': ?agentId,
      'permissionMode': ?permissionMode,
      'model': ?model,
      'effort': ?effort,
      'collaborationMode': ?collaborationMode,
      'conversationMode': ?conversationMode,
      if (terminalEnvironment != null && terminalEnvironment.isNotEmpty)
        'terminalEnvironment': terminalEnvironment,
      if (conversation != null) 'conversation': conversation.toJson(),
      'clearThinkingOnFailure': clearThinkingOnFailure,
    });
    return ChatTurnLaunchOutcome._fromChannel(result);
  }

}
