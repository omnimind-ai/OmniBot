part of 'chat_conversation_runtime_coordinator.dart';

/// Outcome of reserving the ACP session for an admitted local run.
class ChatTurnSession {
  const ChatTurnSession._(this.status, this.sessionId, this.created);

  factory ChatTurnSession._fromChannel(dynamic raw) {
    final map = raw is Map ? raw : const <dynamic, dynamic>{};
    final sessionId = map['sessionId']?.toString().trim();
    return ChatTurnSession._(
      map['status']?.toString() ?? 'failed',
      sessionId == null || sessionId.isEmpty ? null : sessionId,
      map['created'] == true,
    );
  }

  /// `ready`, `abandoned` (the run lost ownership) or `failed` (the error is
  /// already applied to the runtime as this run's PromptResponse).
  final String status;
  final String? sessionId;

  /// Whether `session/new` created [sessionId] for this run.
  final bool created;

  bool get isReady => status == 'ready' && sessionId != null;
}

/// Outcome of one `session/prompt` handled by the native dispatcher.
class ChatTurnPromptOutcome {
  const ChatTurnPromptOutcome._(this.completed, this.response, this.result);

  factory ChatTurnPromptOutcome._fromChannel(dynamic raw) {
    final map = raw is Map ? raw : const <dynamic, dynamic>{};
    final response = _ChatRuntimeMirror._asMap(map['response']);
    return ChatTurnPromptOutcome._(
      map['status'] == 'completed',
      response ?? const <String, dynamic>{},
      AgentReduceResult.fromChannel(map['result']),
    );
  }

  /// False when the transport failed; the failure is already projected.
  final bool completed;

  /// The official PromptResponse payload (thread/session/turn pointers).
  final Map<String, dynamic> response;
  final AgentReduceResult result;
}

/// Flutter intents for the native prompt dispatcher (batch 5b).
///
/// Prompt admission has one native entry: the dispatcher reserves the ACP
/// session, sends `session/prompt` and applies the official PromptResponse
/// or transport error through the native coordinator. Pages build the
/// arguments, check their own navigation target between the two steps, and
/// read the result from the runtime snapshot. They never call the ACP
/// transport for prompts.
class ChatPromptDispatcher {
  ChatPromptDispatcher._(this._coordinator);

  static final ChatPromptDispatcher instance = ChatPromptDispatcher._(
    ChatConversationRuntimeCoordinator.instance,
  );

  final ChatConversationRuntimeCoordinator _coordinator;

  Future<ChatTurnSession> prepareTurnSession({
    required String taskId,
    required int conversationId,
    required String mode,
    required String? existingSessionId,
    required Map<String, dynamic> sessionArgs,
    bool clearThinkingOnFailure = false,
  }) async {
    final result = await _coordinator._invoke(
      'prepareTurnSession',
      <String, dynamic>{
        ..._coordinator._target(conversationId, mode),
        'taskId': taskId,
        'existingSessionId': existingSessionId,
        'sessionArgs': sessionArgs,
        'clearThinkingOnFailure': clearThinkingOnFailure,
      },
    );
    return ChatTurnSession._fromChannel(result);
  }

  /// Releases a reservation whose page target moved on before its prompt;
  /// closes [closeSessionId] when the reservation created it.
  Future<void> releaseTurnSession({
    required String taskId,
    required int conversationId,
    required String mode,
    String? closeSessionId,
  }) async {
    await _coordinator._invoke('releaseTurnSession', <String, dynamic>{
      ..._coordinator._target(conversationId, mode),
      'taskId': taskId,
      'closeSessionId': ?closeSessionId,
    });
  }

  Future<ChatTurnPromptOutcome> submitTurnPrompt({
    required String taskId,
    required int conversationId,
    required String mode,
    required Map<String, dynamic> promptArgs,
    String? fallbackSessionId,
    ConversationModel? conversation,
    bool clearThinkingOnFailure = false,
  }) async {
    final result = await _coordinator._invoke(
      'submitTurnPrompt',
      <String, dynamic>{
        ..._coordinator._target(conversationId, mode),
        'taskId': taskId,
        'promptArgs': promptArgs,
        'fallbackSessionId': fallbackSessionId,
        if (conversation != null) 'conversation': conversation.toJson(),
        'clearThinkingOnFailure': clearThinkingOnFailure,
      },
    );
    return ChatTurnPromptOutcome._fromChannel(result);
  }
}
