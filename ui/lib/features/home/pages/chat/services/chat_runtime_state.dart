part of 'chat_conversation_runtime_coordinator.dart';

enum _StreamingTextStreamKind {
  pureChatReply,
  agentReply,
  pureChatThinking,
  agentThinking,
}

class _StreamingTextBatchState {
  _StreamingTextBatchState({
    required this.taskId,
    required this.kind,
    required this.latestText,
    required this.lastFlushedText,
  });

  final String taskId;
  final _StreamingTextStreamKind kind;
  String latestText;
  String lastFlushedText;
  int pendingChunkCount = 0;

  bool get hasPendingFlush => latestText != lastFlushedText;

  bool get reachedFlushThreshold =>
      pendingChunkCount >= _kStreamingTextChunkFlushThreshold;

  /// 自上次 flush 以来的新增文本中是否包含换行符。
  /// 遇到换行时立即 flush，确保 markdown 块级元素（段落、列表等）及时渲染。
  bool get containsNewlineSinceFlush {
    if (latestText.length <= lastFlushedText.length) return false;
    return latestText.indexOf('\n', lastFlushedText.length) >= 0;
  }

  void stage(String nextText) {
    if (nextText == latestText) {
      return;
    }
    latestText = nextText;
    pendingChunkCount += 1;
  }

  void markFlushed() {
    lastFlushedText = latestText;
    pendingChunkCount = 0;
  }
}

class ChatConversationRuntimeState {
  static const Duration _localSnapshotEchoSuppressionDuration = Duration(
    seconds: 2,
  );

  ChatConversationRuntimeState({
    required this.conversationId,
    required this.mode,
  }) : chatIslandDisplayLayer = ChatIslandDisplayLayer.mode;

  final int conversationId;
  final String mode;

  ConversationModel? conversation;
  final ObservableChatMessageList messages = ObservableChatMessageList();

  /// Accumulated assistant text, used to continue a stream across events.
  ///
  /// This is a TEXT CACHE, not a record of what is running. Its key shape
  /// differs by producer — the built-in agent keys it by task id, the ACP
  /// reducer by message entry id (`<acpMessageId>-agent-message`) — so it must
  /// never feed [activeAgentTurnIds]. It used to, and because ACP mints a new
  /// message id per `agent_message_chunk`, every streamed message registered a
  /// phantom "task" that rendered its own agent avatar and processing row.
  final Map<String, String> currentAiMessages = <String, String>{};

  /// Legacy command/process notifications may omit ACP turnId. Keep their
  /// explicit process identity bound to the run that first observed it so a
  /// delayed stdout/stderr chunk cannot create a card in the next turn.
  final Map<String, String> standaloneProcessRunIds = <String, String>{};

  /// Accumulated reasoning text. Same contract as [currentAiMessages].
  final Map<String, String> currentThinkingMessages = <String, String>{};

  /// User-message chunks are only admitted for an explicit ACP history
  /// replay. Live prompts are already persisted by the host and must not be
  /// echoed into the conversation a second time.
  final Map<String, String> currentAcpUserMessages = <String, String>{};
  final Map<String, _StreamingTextBatchState> _streamingTextBatches =
      <String, _StreamingTextBatchState>{};
  final Map<String, int> agentEntrySequences = <String, int>{};
  final Map<String, int> agentEntryStartTimes = <String, int>{};
  final Map<String, int> agentReplayDeltaOffsets = <String, int>{};

  /// ACP performance metadata may arrive before the assistant message that
  /// owns it. Keep it at the runtime boundary until that message is created.
  final Map<String, Map<String, dynamic>> pendingAcpPerformanceMetrics =
      <String, Map<String, dynamic>>{};
  final Map<String, Map<String, dynamic>> pendingAcpReasoningCardData =
      <String, Map<String, dynamic>>{};

  /// ACP presentation metadata may arrive in an empty assistant chunk before
  /// the text entry it describes. Keep recovery/clarification facts at the
  /// runtime boundary instead of dropping them when no message exists yet.
  final Map<String, Map<String, dynamic>> pendingAcpAssistantPresentation =
      <String, Map<String, dynamic>>{};

  /// Host-generated ACP notification ids already reduced by this runtime.
  /// A reconnecting bridge may deliver the same official session/update more
  /// than once; dedupe only the explicit host id, never message text.
  final Set<String> processedAcpEventIds = <String>{};
  final Set<String> completedAgentTurnIds = <String>{};
  static const int _maxProcessedAcpEventIds = 512;
  static const int _maxAcpTurnHistory = 256;

  /// Events that reached the ACP projection without enough identity to be
  /// safely attached to a turn.  Keep a bounded, metadata-only trail instead
  /// of guessing the current run (which can merge a late Harness event into a
  /// newer Xiaowan turn).  The payload deliberately excludes text/tool args.
  final List<Map<String, dynamic>> acpCompatibilityDiagnostics =
      <Map<String, dynamic>>[];
  bool acpCompatibilityWarningShown = false;

  /// Session ids observed by this runtime. ACP session-scoped notifications
  /// can arrive after a turn has become idle and therefore after
  /// [activeAcpSessionId] has been cleared. Retaining the bounded identity
  /// lets the host route a background conversation's event without falling
  /// back to whichever conversation happens to be visible.
  final Set<String> knownAcpSessionIds = <String>{};

  /// Sessions explicitly invalidated by a cancel/reset. Keep their identity
  /// for routing so a late event can be rejected by the owning runtime instead
  /// of falling through to the visible conversation. A new turn may reactivate
  /// the same ACP session after its first official turn id is admitted.
  final Set<String> retiredAcpSessionIds = <String>{};
  bool allowRetiredAcpSessionReactivation = false;

  /// Official ACP turn -> stable local run. The map is retained after a run
  /// completes so a delayed terminal/update event can still finalize the
  /// correct historical cards without stealing the next run.
  final Map<String, String> acpTurnToRunIds = <String, String>{};
  final Set<String> completedAcpTurnIds = <String>{};

  /// Monotonically advances whenever a new prompt/session lifecycle starts.
  /// Async persistence captures this value and must not apply terminal state
  /// from an older snapshot after a newer prompt has already started.
  int persistenceGeneration = 0;

  /// ACP advertises commands at session scope. Keep the last declaration on
  /// the shared runtime so every Harness gets the same slash-command surface.
  /// The protocol only advertises a command; execution still goes through the
  /// ordinary ACP prompt path in ChatPage.
  List<Map<String, dynamic>> availableAcpCommands = <Map<String, dynamic>>[];

  /// Dynamic ACP session configuration. Keep the full option payload instead
  /// of reducing it to only model/mode so future Harness-specific options can
  /// be consumed by a shared adapter without changing the chat page.
  List<Map<String, dynamic>> acpConfigOptions = <Map<String, dynamic>>[];
  String? currentAcpModeId;
  Map<String, dynamic> acpSessionInfo = <String, dynamic>{};

  /// Preserve extension updates that the current UI does not understand yet.
  /// This is intentionally bounded and session-scoped: an extension must not
  /// disappear at the Kotlin/Flutter seam, but an arbitrary provider payload
  /// must not create an unbounded chat history entry either.
  final List<Map<String, dynamic>> acpExtensionUpdates =
      <Map<String, dynamic>>[];
  int agentNextEntrySequence = 0;
  bool isAiResponding = false;
  bool isContextCompressing = false;
  bool isCheckingExecutableTask = false;
  String deepThinkingContent = '';
  bool isDeepThinking = false;

  /// Stable local ownership key. Unlike [activeAcpTurnId], this never changes
  /// when the provider admits its official turn id.
  String? activeRunId;

  /// Compatibility name retained for page and persisted-history callers.
  /// There is only one local run value; this is not a second identity and it
  /// must never be assigned an ACP turn id.
  String? get currentDispatchTurnId => activeRunId;

  set currentDispatchTurnId(String? value) {
    activeRunId = value;
  }

  /// Official ACP identity of the turn owning this runtime. The dispatch id
  /// remains a local request/render key only until ACP admits the prompt.
  String? activeAcpTurnId;
  String? activeAcpSessionId;
  int currentThinkingStage = 1;
  bool isInputAreaVisible = true;
  bool isExecutingTask = false;

  String? lastAgentTurnId;
  String? activeToolCardId;
  String? activeThinkingCardId;
  String? activeContextCompactionMarkerId;
  String? pendingAgentTextTaskId;
  String? waitingThinkingBeforeAgentTextTaskId;
  bool pendingThinkingRoundSplit = false;
  int toolCardSequence = 0;
  int thinkingRound = 0;
  ChatIslandDisplayLayer chatIslandDisplayLayer;
  String? lastAgentToolType;
  ChatBrowserSessionSnapshot? browserSessionSnapshot;
  int _localSnapshotEchoSuppressionUntilMillis = 0;

  /// The single host-facing run identity. Protocol consumers should use the
  /// ACP fields inside this value instead of treating taskId, turnId, or
  /// sessionId as interchangeable.
  AgentRunIdentity? get activeRunIdentity {
    final runId = activeRunId?.trim() ?? '';
    if (runId.isEmpty) return null;
    return AgentRunIdentity(
      runId: runId,
      conversationId: conversationId,
      sessionId: activeAcpSessionId,
      turnId: activeAcpTurnId,
    );
  }

  bool get hasInFlightTask =>
      isAiResponding ||
      isCheckingExecutableTask ||
      isExecutingTask ||
      activeRunId != null;

  bool get shouldSuppressLocalMessageSnapshotEcho =>
      DateTime.now().millisecondsSinceEpoch <
      _localSnapshotEchoSuppressionUntilMillis;

  void expectLocalMessageSnapshotEcho() {
    _localSnapshotEchoSuppressionUntilMillis = DateTime.now()
        .add(_localSnapshotEchoSuppressionDuration)
        .millisecondsSinceEpoch;
  }

  /// Runs currently believed to be producing output.
  ///
  /// Every member is a stable UI run id. ACP turn ids and per-message text
  /// cache keys are deliberately excluded: mixing those scopes is what
  /// produced one agent avatar and one "processing" row per streamed message.
  Set<String> get activeAgentTurnIds {
    final ids = <String>{};
    // A run id is an ownership key, not proof that a run is still alive.
    // Restored conversations retain message runIds, and an older snapshot can
    // also leave activeRunId behind after the terminal event. Only expose the
    // id to the timeline while the runtime has live work to render.
    // Text caches are projection buffers, not proof that a prompt is still
    // alive. A broken stream can leave a partial buffer behind after the
    // official ACP prompt has ended; treating that buffer as lifecycle state
    // resurrects a completed run during history merge or polling.
    final hasLiveWork =
        isAiResponding ||
        isCheckingExecutableTask ||
        isExecutingTask ||
        activeRunId != null;
    if (hasLiveWork) {
      final currentTaskId =
          (activeRunId ?? currentDispatchTurnId)?.trim() ?? '';
      if (currentTaskId.isNotEmpty) {
        ids.add(currentTaskId);
      }
    }
    final lastTaskId = lastAgentTurnId?.trim() ?? '';
    if (isAiResponding && lastTaskId.isNotEmpty) {
      ids.add(lastTaskId);
    }
    final pendingTaskId = pendingAgentTextTaskId?.trim() ?? '';
    if (pendingTaskId.isNotEmpty) {
      ids.add((activeRunId ?? pendingTaskId).trim());
    }
    return ids;
  }

  void dispose() {
    _streamingTextBatches.clear();
    agentEntrySequences.clear();
    agentEntryStartTimes.clear();
    agentReplayDeltaOffsets.clear();
    standaloneProcessRunIds.clear();
    pendingAcpPerformanceMetrics.clear();
    pendingAcpReasoningCardData.clear();
    pendingAcpAssistantPresentation.clear();
    processedAcpEventIds.clear();
    completedAgentTurnIds.clear();
    acpCompatibilityDiagnostics.clear();
    acpCompatibilityWarningShown = false;
    knownAcpSessionIds.clear();
    retiredAcpSessionIds.clear();
    allowRetiredAcpSessionReactivation = false;
    acpTurnToRunIds.clear();
    completedAcpTurnIds.clear();
    messages.dispose();
  }

  String? resolveRunId({String? sessionId, String? turnId, String? fallback}) {
    final key = acpTurnKey(sessionId: sessionId, turnId: turnId);
    if (key.isNotEmpty) {
      final existing = acpTurnToRunIds[key];
      if (existing != null) return existing;
      final turnOnlyKey = acpTurnKey(turnId: turnId);
      final turnOnly = acpTurnToRunIds[turnOnlyKey];
      if (turnOnly != null) return turnOnly;
    }
    final active = activeRunId?.trim() ?? '';
    if (active.isNotEmpty) {
      if (key.isNotEmpty) _rememberAcpTurnRun(key, active);
      final turnOnlyKey = acpTurnKey(turnId: turnId);
      if (turnOnlyKey.isNotEmpty) _rememberAcpTurnRun(turnOnlyKey, active);
      return active;
    }
    final normalizedFallback = fallback?.trim() ?? '';
    if (normalizedFallback.isEmpty) return null;
    if (key.isNotEmpty) _rememberAcpTurnRun(key, normalizedFallback);
    return normalizedFallback;
  }

  /// Resolves an ACP event without allowing an unknown protocol identity to
  /// borrow the currently active render id.
  ///
  /// [resolveRunId] is intentionally permissive for the admission boundary:
  /// the first update may be the only signal that binds a provider turn to a
  /// locally reserved run. Once an official turn is active, that fallback is
  /// unsafe for delayed events because it turns an old terminal/update into a
  /// mutation of the new turn. Keep this policy here so the reducer and the
  /// coordinator use the same identity rule.
  String? resolveAcpEventRunId({
    String? sessionId,
    String? turnId,
    String? fallback,
  }) {
    final incomingSessionId = sessionId?.trim() ?? '';
    final incomingTurnId = turnId?.trim() ?? '';
    final activeSessionId = activeAcpSessionId?.trim() ?? '';
    final activeTurnId = activeAcpTurnId?.trim() ?? '';
    final belongsToDifferentActiveIdentity =
        (incomingSessionId.isNotEmpty &&
            activeSessionId.isNotEmpty &&
            incomingSessionId != activeSessionId) ||
        (incomingTurnId.isNotEmpty &&
            activeTurnId.isNotEmpty &&
            incomingTurnId != activeTurnId);
    if (belongsToDifferentActiveIdentity) {
      return resolveKnownRunId(
        sessionId: incomingSessionId,
        turnId: incomingTurnId,
      );
    }
    return resolveRunId(
      sessionId: incomingSessionId,
      turnId: incomingTurnId,
      fallback: fallback,
    );
  }

  /// Looks up only an already admitted ACP identity. It never creates a
  /// binding, which is important when quarantining a late event.
  String? resolveKnownRunId({String? sessionId, String? turnId}) {
    final key = acpTurnKey(sessionId: sessionId, turnId: turnId);
    if (key.isNotEmpty) {
      final existing = acpTurnToRunIds[key];
      if (existing != null) return existing;
    }
    final turnOnlyKey = acpTurnKey(turnId: turnId);
    if (turnOnlyKey.isNotEmpty) {
      return acpTurnToRunIds[turnOnlyKey];
    }
    return null;
  }

  bool rememberProcessedAcpEventId(String eventId) {
    final normalized = eventId.trim();
    if (normalized.isEmpty) return true;
    final added = processedAcpEventIds.add(normalized);
    while (processedAcpEventIds.length > _maxProcessedAcpEventIds) {
      processedAcpEventIds.remove(processedAcpEventIds.first);
    }
    return added;
  }

  bool hasProcessedAcpEventId(String eventId) {
    final normalized = eventId.trim();
    return normalized.isNotEmpty && processedAcpEventIds.contains(normalized);
  }

  void rememberCompletedAcpTurn(String turnId) {
    final normalized = turnId.trim();
    if (normalized.isEmpty) return;
    completedAcpTurnIds.add(normalized);
    while (completedAcpTurnIds.length > _maxAcpTurnHistory) {
      completedAcpTurnIds.remove(completedAcpTurnIds.first);
    }
  }

  void _rememberAcpTurnRun(String key, String runId) {
    acpTurnToRunIds[key] = runId;
    while (acpTurnToRunIds.length > _maxAcpTurnHistory * 2) {
      acpTurnToRunIds.remove(acpTurnToRunIds.keys.first);
    }
  }

  bool rememberAcpCompatibilityDiagnostic({
    required String reason,
    required String method,
    String? sessionId,
    String? turnId,
    String? itemId,
    String? messageId,
    bool legacy = false,
  }) {
    final entry = <String, dynamic>{
      'reason': reason,
      'method': method,
      if (sessionId?.trim().isNotEmpty == true) 'sessionId': sessionId!.trim(),
      if (turnId?.trim().isNotEmpty == true) 'turnId': turnId!.trim(),
      if (itemId?.trim().isNotEmpty == true) 'itemId': itemId!.trim(),
      if (messageId?.trim().isNotEmpty == true) 'messageId': messageId!.trim(),
      if (legacy) 'legacy': true,
      'at': DateTime.now().millisecondsSinceEpoch,
    };
    final shouldWarnUser =
        reason == 'turn_id_missing' && !acpCompatibilityWarningShown;
    acpCompatibilityWarningShown =
        acpCompatibilityWarningShown || shouldWarnUser;
    acpCompatibilityDiagnostics.add(entry);
    while (acpCompatibilityDiagnostics.length > 64) {
      acpCompatibilityDiagnostics.removeAt(0);
    }
    debugPrint(
      '[ACP compatibility] quarantined $method: $reason'
      '${sessionId == null ? '' : ' session=$sessionId'}'
      '${turnId == null ? '' : ' turn=$turnId'}'
      '${itemId == null ? '' : ' item=$itemId'}'
      '${messageId == null ? '' : ' message=$messageId'}'
      '${legacy ? ' legacy=true' : ''}',
    );
    return shouldWarnUser;
  }

  String standaloneProcessOwner(String processId, String fallbackRunId) {
    final normalizedProcessId = processId.trim();
    final normalizedFallback = fallbackRunId.trim();
    if (normalizedProcessId.isEmpty || normalizedFallback.isEmpty) {
      return normalizedFallback;
    }
    final existing = standaloneProcessRunIds[normalizedProcessId];
    if (existing != null && existing.isNotEmpty) {
      return existing;
    }
    standaloneProcessRunIds[normalizedProcessId] = normalizedFallback;
    while (standaloneProcessRunIds.length > 128) {
      standaloneProcessRunIds.remove(standaloneProcessRunIds.keys.first);
    }
    return normalizedFallback;
  }

  String? acpTurnIdForRun(String runId) {
    final normalized = runId.trim();
    if (normalized.isEmpty) return null;
    // An active local run may still be waiting for ACP admission. Do not
    // return early with null: a direct/legacy event can already have a
    // protocol-to-run mapping even though it did not own the current prompt.
    if (activeRunId == normalized && activeAcpTurnId != null) {
      return activeAcpTurnId;
    }
    for (final entry in acpTurnToRunIds.entries) {
      if (entry.value != normalized) continue;
      final separator = entry.key.lastIndexOf(':');
      return separator == -1 ? entry.key : entry.key.substring(separator + 1);
    }
    return null;
  }

  bool acceptsAcpEvent({
    String? sessionId,
    String? turnId,
    bool allowCompletedTurnMetadata = false,
    bool allowSessionAdmission = false,
  }) {
    final incomingSessionId = sessionId?.trim() ?? '';
    final incomingTurnId = turnId?.trim() ?? '';
    // A sessionless event is a supported legacy shape, but it is not allowed
    // to bypass the turn tombstone. After a reset/cancel, late legacy chunks
    // still carry the old turn id and must not be admitted to the next prompt.
    // A sessionless event with a new turn remains compatible with the legacy
    // wire shape; only an already-fenced turn is rejected below.
    if (incomingSessionId.isEmpty && incomingTurnId.isEmpty) {
      return true;
    }
    if (retiredAcpSessionIds.contains(incomingSessionId)) {
      final canReactivate =
          allowRetiredAcpSessionReactivation &&
          incomingTurnIdIsNewForRuntime(incomingTurnId);
      if (!canReactivate) {
        return false;
      }
      retiredAcpSessionIds.remove(incomingSessionId);
      allowRetiredAcpSessionReactivation = false;
    }
    if (incomingSessionId.isNotEmpty) {
      knownAcpSessionIds.add(incomingSessionId);
      while (knownAcpSessionIds.length > 32) {
        knownAcpSessionIds.remove(knownAcpSessionIds.first);
      }
    }
    // A completed turn remains fenced even after its session becomes idle.
    // Without this check, a delayed event from the previous Harness can be
    // the first event seen after a Xiaowan/DSH switch and silently rebind the
    // runtime to the old session.
    if (incomingTurnId.isNotEmpty &&
        (completedAgentTurnIds.contains(incomingTurnId) ||
            completedAcpTurnIds.contains(incomingTurnId))) {
      final activeTurnId =
          activeAcpTurnId?.trim() ?? currentDispatchTurnId?.trim() ?? '';
      final currentSessionId = activeAcpSessionId?.trim() ?? '';
      if (!allowCompletedTurnMetadata ||
          activeTurnId.isNotEmpty ||
          (currentSessionId.isNotEmpty &&
              currentSessionId != incomingSessionId)) {
        return false;
      }
      return true;
    }
    // Sessionless events have no session admission to perform. Once their
    // turn id passed the completed-turn fence, preserve the existing legacy
    // compatibility and let the reducer handle the event.
    if (incomingSessionId.isEmpty) {
      return true;
    }

    final currentSessionId = activeAcpSessionId?.trim() ?? '';
    if (currentSessionId.isEmpty) {
      if (!allowSessionAdmission) return false;
      activeAcpSessionId = incomingSessionId;
      return true;
    }
    if (currentSessionId == incomingSessionId) {
      return true;
    }
    final currentTurnId =
        activeAcpTurnId?.trim() ?? currentDispatchTurnId?.trim() ?? '';
    // A different session may replace the previous session only while the
    // current local task is waiting for its first official ACP turn. Once an
    // official turn has been admitted, a late event from another session must
    // never mutate the current turn's state. The completed-turn fence above
    // covers delayed events from the previous turn during that admission gap.
    if (currentTurnId.isNotEmpty &&
        activeAcpTurnId?.trim().isNotEmpty == true) {
      return false;
    }
    // A session cannot become the owner merely because its event arrived
    // first. The host prompt reservation must explicitly authorize this
    // admission through host metadata; otherwise a delayed event from a
    // previous Harness can steal the admission window.
    if (!allowSessionAdmission) {
      return false;
    }
    activeAcpSessionId = incomingSessionId;
    return true;
  }

  bool incomingTurnIdIsNewForRuntime(String turnId) {
    return turnId.isNotEmpty &&
        !completedAgentTurnIds.contains(turnId) &&
        !completedAcpTurnIds.contains(turnId) &&
        (currentDispatchTurnId?.trim().isNotEmpty == true ||
            activeRunId?.trim().isNotEmpty == true);
  }
}
