part of 'chat_conversation_runtime_coordinator.dart';

extension ChatRuntimePersistenceSupport on ChatConversationRuntimeCoordinator {
  Future<void> persistRuntimeConversation({
    required int conversationId,
    required String mode,
    bool generateSummary = false,
    bool markComplete = false,
    bool persistMessages = false,
    bool allowEphemeralPersistence = false,
  }) async {
    _cancelPendingPersistence(conversationId: conversationId, mode: mode);
    if (isEphemeralRuntime(conversationId: conversationId, mode: mode) &&
        !allowEphemeralPersistence) {
      return;
    }
    final key = _runtimeKey(conversationId: conversationId, mode: mode);
    final expectedRuntime = runtimeFor(
      conversationId: conversationId,
      mode: mode,
    );
    if (expectedRuntime == null) return;
    final previous = _persistenceTails[key] ?? Future<void>.value();
    final operation = previous
        .catchError((Object _) {})
        .then(
          (_) => _persistRuntimeConversationNow(
            conversationId: conversationId,
            mode: mode,
            expectedRuntime: expectedRuntime,
            generateSummary: generateSummary,
            markComplete: markComplete,
            persistMessages: persistMessages,
          ),
        );
    _persistenceTails[key] = operation;
    unawaited(
      operation.then<void>(
        (_) => _removePersistenceTail(key, operation),
        onError: (Object error, StackTrace stack) {
          _removePersistenceTail(key, operation);
        },
      ),
    );
    await operation;
  }

  void _removePersistenceTail(String key, Future<void> operation) {
    if (identical(_persistenceTails[key], operation)) {
      _persistenceTails.remove(key);
    }
  }

  Future<void> _persistRuntimeConversationNow({
    required int conversationId,
    required String mode,
    ChatConversationRuntimeState? expectedRuntime,
    bool generateSummary = false,
    bool markComplete = false,
    bool persistMessages = false,
  }) async {
    final runtime =
        expectedRuntime ??
        runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null) return;
    if (!identical(
      runtimeFor(conversationId: conversationId, mode: mode),
      runtime,
    )) {
      return;
    }
    final persistenceGeneration = runtime.persistenceGeneration;
    _flushRuntimeStreamingText(runtime);
    // `persistMessages: true` means this caller owns the complete durable
    // snapshot. An empty list is therefore a valid clear operation; dropping
    // it resurrects deleted history on the next reload. Callers that merely
    // update conversation metadata keep the old guard by leaving the flag
    // false.
    if (runtime.messages.isEmpty && !persistMessages) return;

    final snapshotMessages = List<ChatMessageModel>.from(runtime.messages);
    final snapshotConversation = runtime.conversation;
    final conversationMode = _conversationModeFromRuntimeMode(
      mode,
      conversation: snapshotConversation,
    );
    final now = DateTime.now().millisecondsSinceEpoch;
    final lastMessage = snapshotMessages.isNotEmpty
        ? (snapshotMessages[0].text ?? '')
        : '';
    final messageCount = snapshotMessages.length;
    final firstUserMessage = snapshotMessages.firstWhere(
      (m) => m.user == 1,
      orElse: () => ChatMessageModel.userMessage("default"),
    );
    final userText = firstUserMessage.text ?? 'conversation';
    final title = userText.length > 20
        ? '${userText.substring(0, 20)}...'
        : userText;

    String? summary = snapshotConversation?.summary;
    if (generateSummary) {
      final history = _buildConversationHistoryText(snapshotMessages);
      summary = history.isEmpty
          ? null
          : await ConversationService.generateConversationSummary(
              conversationHistory: history,
            );
    }

    final baseConversation =
        (snapshotConversation?.mode == conversationMode
            ? snapshotConversation
            : snapshotConversation?.copyWith(mode: conversationMode)) ??
        ConversationModel(
          id: conversationId,
          mode: conversationMode,
          title: title,
          summary: summary,
          status: 0,
          lastMessage: lastMessage,
          messageCount: messageCount,
          createdAt: now,
          updatedAt: now,
        );

    final updatedConversation = baseConversation.copyWith(
      title: baseConversation.title.isEmpty ? title : baseConversation.title,
      summary: summary ?? baseConversation.summary,
      lastMessage: lastMessage,
      messageCount: messageCount,
      updatedAt: now,
    );

    await ConversationService.updateConversation(
      updatedConversation,
      preserveLatestMetadata: true,
    );
    if (persistMessages) {
      // replaceConversationMessages is echoed back to Flutter as
      // messages_replaced. The runtime already owns this exact snapshot; if
      // the page reloads it while the completed run is folding, every row is
      // recreated and the chat visibly flashes through its empty state.
      runtime.expectLocalMessageSnapshotEcho();
      await ConversationHistoryService.saveConversationMessages(
        conversationId,
        snapshotMessages,
        mode: conversationMode,
      );
    }
    // A page switch may dispose this runtime while the durable write is
    // awaiting the database. Do not mutate a replaced runtime when the old
    // operation returns; the ordered write still preserves the user's data.
    final isCurrentRuntime =
        identical(
          runtimeFor(conversationId: conversationId, mode: mode),
          runtime,
        ) &&
        runtime.persistenceGeneration == persistenceGeneration;
    if (isCurrentRuntime) {
      runtime.conversation = updatedConversation;
    }
    if (markComplete && isCurrentRuntime) {
      await ConversationService.completeConversation(
        conversationId,
        mode: conversationMode,
      );
    }
  }

  void schedulePersistRuntimeConversation({
    required int conversationId,
    required String mode,
    bool generateSummary = false,
    bool markComplete = false,
    bool persistMessages = false,
    Duration delay = const Duration(milliseconds: 350),
  }) {
    final key = _runtimeKey(conversationId: conversationId, mode: mode);
    if (_ephemeralRuntimeKeys.contains(key)) {
      return;
    }
    final previous = _pendingPersistence[key];
    previous?.timer.cancel();
    final nextGenerateSummary =
        generateSummary || (previous?.generateSummary ?? false);
    final nextMarkComplete = markComplete || (previous?.markComplete ?? false);
    final nextPersistMessages =
        persistMessages || (previous?.persistMessages ?? false);
    final timer = Timer(delay, () {
      _pendingPersistence.remove(key);
      unawaited(
        persistRuntimeConversation(
          conversationId: conversationId,
          mode: mode,
          generateSummary: nextGenerateSummary,
          markComplete: nextMarkComplete,
          persistMessages: nextPersistMessages,
        ),
      );
    });
    _pendingPersistence[key] = _PendingPersistenceRequest(
      conversationId: conversationId,
      mode: mode,
      timer: timer,
      generateSummary: nextGenerateSummary,
      markComplete: nextMarkComplete,
      persistMessages: nextPersistMessages,
    );
  }

  Future<void> flushPendingPersistence({
    required int conversationId,
    required String mode,
  }) async {
    final key = _runtimeKey(conversationId: conversationId, mode: mode);
    final request = _pendingPersistence.remove(key);
    if (request == null) {
      return;
    }
    request.timer.cancel();
    if (_ephemeralRuntimeKeys.contains(key)) {
      return;
    }
    await persistRuntimeConversation(
      conversationId: request.conversationId,
      mode: request.mode,
      generateSummary: request.generateSummary,
      markComplete: request.markComplete,
      persistMessages: request.persistMessages,
    );
  }

  Future<void> flushAllPendingPersistence() async {
    final requests = _pendingPersistence.values.toList(growable: false);
    _pendingPersistence.clear();
    for (final request in requests) {
      request.timer.cancel();
      await persistRuntimeConversation(
        conversationId: request.conversationId,
        mode: request.mode,
        generateSummary: request.generateSummary,
        markComplete: request.markComplete,
        persistMessages: request.persistMessages,
      );
    }
    // A timer is not the only source of persistence. ACP deltas and lifecycle
    // callbacks may already have queued a database write; disposal/background
    // flush must wait for that tail as well.
    final inFlight = _persistenceTails.values.toList(growable: false);
    if (inFlight.isNotEmpty) {
      await Future.wait(inFlight);
    }
  }
}
