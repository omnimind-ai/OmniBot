part of 'chat_conversation_runtime_coordinator.dart';

/// A frozen composer submission. This is not an ACP turn until dispatched.
@immutable
class ChatQueuedPrompt {
  ChatQueuedPrompt({
    required this.id,
    required this.sessionId,
    required this.agentId,
    required this.text,
    required this.conversationMode,
    List<Map<String, dynamic>> attachments = const [],
    this.model,
    this.effort,
    this.approvalPolicy,
    this.approvalsReviewer,
    Map<String, dynamic>? sandboxPolicy,
    Map<String, String>? terminalEnvironment,
  }) : attachments = List.unmodifiable(
         attachments.map(
           (item) => Map<String, dynamic>.unmodifiable(
             jsonDecode(jsonEncode(item)) as Map<String, dynamic>,
           ),
         ),
       ),
       sandboxPolicy = sandboxPolicy == null
           ? null
           : Map<String, dynamic>.unmodifiable(
               jsonDecode(jsonEncode(sandboxPolicy)) as Map<String, dynamic>,
             ),
       terminalEnvironment = terminalEnvironment == null
           ? null
           : Map.unmodifiable(terminalEnvironment);

  final String id;
  final String sessionId;
  final String agentId;
  final String text;
  final String conversationMode;
  final List<Map<String, dynamic>> attachments;
  final String? model;
  final String? effort;
  final String? approvalPolicy;
  final String? approvalsReviewer;
  final Map<String, dynamic>? sandboxPolicy;
  final Map<String, String>? terminalEnvironment;

  Map<String, dynamic> toMap() => {
    'id': id,
    'sessionId': sessionId,
    'agentId': agentId,
    'text': text,
    'conversationMode': conversationMode,
    'attachments': attachments,
    'model': model,
    'effort': effort,
    'approvalPolicy': approvalPolicy,
    'approvalsReviewer': approvalsReviewer,
    'sandboxPolicy': sandboxPolicy,
    'terminalEnvironment': terminalEnvironment,
  };

  factory ChatQueuedPrompt.fromMap(Map<String, dynamic> value) =>
      ChatQueuedPrompt(
        id: value['id'] as String,
        sessionId: value['sessionId'] as String,
        agentId: value['agentId'] as String,
        text: value['text'] as String,
        conversationMode: value['conversationMode'] as String,
        attachments: (value['attachments'] as List? ?? [])
            .map((item) => Map<String, dynamic>.from(item as Map))
            .toList(growable: false),
        model: value['model'] as String?,
        effort: value['effort'] as String?,
        approvalPolicy: value['approvalPolicy'] as String?,
        approvalsReviewer: value['approvalsReviewer'] as String?,
        sandboxPolicy: (value['sandboxPolicy'] as Map?)
            ?.cast<String, dynamic>(),
        terminalEnvironment: (value['terminalEnvironment'] as Map?)
            ?.cast<String, String>(),
      );
}

extension ChatRuntimePromptInputSupport on ChatConversationRuntimeCoordinator {
  String _promptQueueStorageKey(ChatConversationRuntimeState runtime) =>
      'chat.pending_prompts.v1.${runtime.conversationId}.${runtime.mode}';

  void _restorePromptQueue(ChatConversationRuntimeState runtime) {
    try {
      final raw = StorageService.getString(_promptQueueStorageKey(runtime));
      final stored = raw == null
          ? <dynamic>[]
          : jsonDecode(raw) as List<dynamic>;
      for (final item in stored) {
        try {
          final prompt = ChatQueuedPrompt.fromMap(
            Map<String, dynamic>.from(item as Map),
          );
          if (prompt.agentId == 'xiaowan-acp' &&
              prompt.id.isNotEmpty &&
              prompt.sessionId.isNotEmpty) {
            runtime.pendingPrompts.add(prompt);
          }
        } catch (_) {
          /* Malformed drafts are never executable. */
        }
      }
      final receiptRaw = StorageService.getString(
        '${_promptQueueStorageKey(runtime)}.steering',
      );
      if (receiptRaw != null) {
        final receipt = jsonDecode(receiptRaw) as Map;
        runtime._unconfirmedSteeringInput = (
          id: receipt['id'] as String,
          text: receipt['text'] as String,
          sessionId: receipt['sessionId'] as String,
          turnId: receipt['turnId'] as String,
        );
      }
    } catch (_) {
      /* Startup without initialized storage has no executable drafts. */
    }
    runtime.promptQueuePaused =
        runtime.pendingPrompts.isNotEmpty ||
        runtime._unconfirmedSteeringInput != null;
  }

  Future<void> _writePromptInputStorage(String key, Object? value) {
    final previous = _promptQueuePersistenceTails[key] ?? Future<void>.value();
    final next = previous.catchError((Object _) {}).then((_) async {
      final saved = value == null
          ? await StorageService.remove(key)
          : await StorageService.setJson(key, value);
      if (!saved) throw StateError('Unable to save pending input.');
    });
    _promptQueuePersistenceTails[key] = next;
    return next;
  }

  Future<void> _persistPromptQueue(ChatConversationRuntimeState runtime) =>
      _writePromptInputStorage(
        _promptQueueStorageKey(runtime),
        runtime.pendingPrompts
            .map((item) => item.toMap())
            .toList(growable: false),
      );

  Future<void> _persistSteeringReceipt(ChatConversationRuntimeState runtime) {
    final receipt = runtime._unconfirmedSteeringInput;
    return _writePromptInputStorage(
      '${_promptQueueStorageKey(runtime)}.steering',
      receipt == null
          ? null
          : {
              'id': receipt.id,
              'text': receipt.text,
              'sessionId': receipt.sessionId,
              'turnId': receipt.turnId,
            },
    );
  }

  Future<void> dismissUnconfirmedSteering({
    required int conversationId,
    required String mode,
  }) async {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null || runtime.isSteeringInputPending) return;
    await _writePromptInputStorage(
      '${_promptQueueStorageKey(runtime)}.steering',
      null,
    );
    runtime._unconfirmedSteeringInput = null;
    _notifyRuntimeListeners();
  }

  Future<void> clearPromptInputsForConversation(int conversationId) async {
    for (final runtime in _runtimes.values.where(
      (item) => item.conversationId == conversationId,
    )) {
      runtime._promptInputGeneration += 1;
      runtime.pendingPrompts.clear();
      runtime.promptQueuePaused = true;
      runtime._unconfirmedSteeringInput = null;
    }
    _notifyRuntimeListeners();
    for (final mode in [
      kChatRuntimeModeNormal,
      kChatRuntimeModeAgent,
      kChatRuntimeModeOpenClaw,
    ]) {
      final key = 'chat.pending_prompts.v1.$conversationId.$mode';
      await _writePromptInputStorage(key, null);
      await _writePromptInputStorage('$key.steering', null);
    }
  }

  void pausePromptQueue({required int conversationId, required String mode}) {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null) return;
    runtime.promptQueuePaused = true;
    _notifyRuntimeListeners();
  }

  bool _hasSteeringEcho(
    ChatConversationRuntimeState runtime,
    String requestId,
  ) => runtime.messages.any(
    (message) => message.streamMeta?['steeringRequestId'] == requestId,
  );

  Future<void> _commitSteeringEcho(
    ChatConversationRuntimeState runtime,
    String requestId,
  ) async {
    if (!_hasSteeringEcho(runtime, requestId)) {
      final echoed = Completer<void>();
      void observe() {
        if (!echoed.isCompleted && _hasSteeringEcho(runtime, requestId))
          echoed.complete();
      }

      addListener(observe);
      try {
        observe();
        await echoed.future.timeout(const Duration(seconds: 10));
      } finally {
        removeListener(observe);
      }
    }
    await persistRuntimeConversation(
      conversationId: runtime.conversationId,
      mode: runtime.mode,
      persistMessages: true,
    );
    // History must be durable before the draft receipt or composer is cleared.
    await _writePromptInputStorage(
      '${_promptQueueStorageKey(runtime)}.steering',
      null,
    );
    if (runtime._unconfirmedSteeringInput?.id == requestId)
      runtime._unconfirmedSteeringInput = null;
  }

  Future<void> enqueuePrompt({
    required int conversationId,
    required String mode,
    required ChatQueuedPrompt prompt,
  }) {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null ||
        !runtime.knownAcpSessionIds.contains(prompt.sessionId)) {
      return Future<void>.error(
        StateError('The conversation session is not ready.'),
      );
    }
    final generation = runtime._promptInputGeneration;
    final previous = runtime._promptAdmissionTail ?? Future<void>.value();
    final operation = previous.catchError((Object _) {}).then((_) async {
      if (runtime._promptInputGeneration != generation ||
          !identical(
            runtimeFor(conversationId: conversationId, mode: mode),
            runtime,
          )) {
        throw StateError(
          'The conversation has changed. Your draft is preserved.',
        );
      }
      if (runtime.pendingPrompts.any((item) => item.id == prompt.id)) return;
      // The visible draft has not been admitted until its durable write
      // succeeds. A simultaneous PromptResponse must not drain it early.
      runtime._pendingPromptAdmissions.add(prompt.id);
      runtime.pendingPrompts.add(prompt);
      _notifyRuntimeListeners();
      try {
        await _persistPromptQueue(runtime);
      } catch (_) {
        runtime.pendingPrompts.removeWhere((item) => item.id == prompt.id);
        // Ordered cleanup also removes this rejected draft from snapshots
        // that another already-admitted prompt may have captured meanwhile.
        try {
          await _persistPromptQueue(runtime);
        } catch (_) {}
        rethrow;
      } finally {
        runtime._pendingPromptAdmissions.remove(prompt.id);
        _notifyRuntimeListeners();
      }
      unawaited(_drainQueuedPrompts(runtime));
    });
    runtime._promptAdmissionTail = operation;
    return operation;
  }

  void removeQueuedPrompt({
    required int conversationId,
    required String mode,
    required String promptId,
  }) {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null) return;
    runtime.pendingPrompts.removeWhere((item) => item.id == promptId);
    unawaited(
      _persistPromptQueue(runtime).catchError((Object error) {
        debugPrint('Unable to persist prompt queue removal: $error');
      }),
    );
    _notifyRuntimeListeners();
  }

  void resumePromptQueue({required int conversationId, required String mode}) {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null) return;
    runtime.promptQueuePaused = false;
    _notifyRuntimeListeners();
    unawaited(_drainQueuedPrompts(runtime));
  }

  Future<bool> steerActivePrompt({
    required int conversationId,
    required String mode,
    required String agentId,
    required String text,
    required String requestId,
  }) async {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null || runtime.isSteeringInputPending) return false;
    final sessionId = runtime.activeAcpSessionId;
    final turnId = runtime.activeAcpTurnId;
    final prior = runtime._unconfirmedSteeringInput;
    final retrying = prior != null && prior.text == text;
    if (prior != null && !retrying) {
      throw StateError(
        _isEnglish
            ? 'Review the unconfirmed follow-up before adding another.'
            : '请先查看待确认的补充，再添加新要求。',
      );
    }
    if (retrying && _hasSteeringEcho(runtime, prior.id)) {
      await _commitSteeringEcho(runtime, prior.id);
      return true;
    }
    if (retrying && (sessionId != prior.sessionId || turnId != prior.turnId)) {
      throw StateError(
        'The previous input has not been confirmed. Check the conversation before sending it again.',
      );
    }
    if (!runtime.hasInFlightTask || sessionId == null || turnId == null)
      return false;
    runtime.isSteeringInputPending = true;
    final effectiveRequestId = retrying ? prior.id : requestId;
    runtime._unconfirmedSteeringInput = (
      id: effectiveRequestId,
      text: text,
      sessionId: sessionId,
      turnId: turnId,
    );
    _notifyRuntimeListeners();
    try {
      // Preserve the exact input before the RPC can consume it. Recovery only
      // offers this as an unconfirmed draft, never as an automatic new prompt.
      await _persistSteeringReceipt(runtime);
      final result = await AgentRuntimeService.steerSession(
        conversationId: conversationId,
        sessionId: sessionId,
        expectedTurnId: turnId,
        requestId: effectiveRequestId,
        agentId: agentId,
        text: text,
      );
      if (result['outcome'] == 'injected') {
        await _commitSteeringEcho(runtime, effectiveRequestId);
        return true;
      }
      if (result['outcome'] == 'promptRequired' && !retrying) {
        await _writePromptInputStorage(
          '${_promptQueueStorageKey(runtime)}.steering',
          null,
        );
        runtime._unconfirmedSteeringInput = null;
        return false;
      }
      throw StateError(
        'The active input was not acknowledged. Your draft is preserved.',
      );
    } catch (_) {
      if (_hasSteeringEcho(runtime, effectiveRequestId)) {
        await _commitSteeringEcho(runtime, effectiveRequestId);
        return true;
      }
      rethrow;
    } finally {
      runtime.isSteeringInputPending = false;
      _notifyRuntimeListeners();
    }
  }

  void _settleQueuedPrompts(
    ChatConversationRuntimeState runtime, {
    String? sessionId,
    String? stopReason,
    String? error,
  }) {
    final successful =
        (runtime.pendingPrompts.isEmpty ||
            runtime.pendingPrompts.first.sessionId == sessionId) &&
        (error == null || error.trim().isEmpty) &&
        const {
          'end_turn',
          'completed',
          'complete',
          'success',
        }.contains(stopReason?.trim().toLowerCase());
    if (!successful) {
      runtime.promptQueuePaused = true;
      return;
    }
    // Do not revive a queue that the user already stopped or that previously
    // failed. Explicit Resume is the only way out of its paused state.
    unawaited(_drainQueuedPrompts(runtime));
  }

  Future<void> _drainQueuedPrompts(ChatConversationRuntimeState runtime) async {
    bool hasPromptReservation() => _taskBindings.values.any(
      (binding) =>
          binding.conversationId == runtime.conversationId &&
          binding.mode == runtime.mode,
    );
    if (runtime._drainingPromptQueue ||
        runtime.promptQueuePaused ||
        runtime.hasInFlightTask ||
        hasPromptReservation() ||
        runtime.pendingPrompts.isEmpty ||
        runtime._pendingPromptAdmissions.contains(
          runtime.pendingPrompts.first.id,
        ))
      return;
    runtime._drainingPromptQueue = true;
    try {
      while (!runtime.promptQueuePaused &&
          !runtime.hasInFlightTask &&
          !hasPromptReservation() &&
          runtime.pendingPrompts.isNotEmpty &&
          !runtime._pendingPromptAdmissions.contains(
            runtime.pendingPrompts.first.id,
          ) &&
          identical(
            runtimeFor(
              conversationId: runtime.conversationId,
              mode: runtime.mode,
            ),
            runtime,
          )) {
        final prompt = runtime.pendingPrompts.removeAt(0);
        final inputGeneration = runtime._promptInputGeneration;
        final taskId = '${prompt.id}-ai';
        final userId = '${prompt.id}-user';
        // Reserve synchronously, before persistence, so a second drain or
        // composer send cannot claim this session during an await.
        beginAcpTurn(
          taskId: taskId,
          conversationId: runtime.conversationId,
          mode: runtime.mode,
        );
        bindAcpSession(
          taskId: taskId,
          conversationId: runtime.conversationId,
          mode: runtime.mode,
          sessionId: prompt.sessionId,
        );
        if (!runtime.messages.any((message) => message.id == userId)) {
          runtime.messages.insert(
            0,
            ChatMessageModel(
              id: userId,
              type: 1,
              user: 1,
              content: {
                'id': userId,
                'text': prompt.text,
                if (prompt.attachments.isNotEmpty)
                  'attachments': prompt.attachments,
              },
            ),
          );
        }
        _notifyRuntimeListeners();
        try {
          await persistRuntimeConversation(
            conversationId: runtime.conversationId,
            mode: runtime.mode,
            persistMessages: true,
          );
          // Commit the host user item before removing the draft from durable
          // storage; a crash then leaves recoverable history, never an
          // automatically replayed request.
          await _persistPromptQueue(runtime);
          if (!isTaskActive(
            taskId: taskId,
            conversationId: runtime.conversationId,
            mode: runtime.mode,
          ))
            return;
          if (runtime._promptInputGeneration != inputGeneration ||
              !identical(
                runtimeFor(
                  conversationId: runtime.conversationId,
                  mode: runtime.mode,
                ),
                runtime,
              )) {
            unregisterTask(
              taskId,
              conversationId: runtime.conversationId,
              mode: runtime.mode,
            );
            return;
          }
          if (runtime.promptQueuePaused) {
            // Stop won while the host was persisting preparation. No ACP
            // request exists yet: release only that local reservation and
            // preserve the stable draft/user id for an explicit resume.
            runtime.pendingPrompts.insert(0, prompt);
            unregisterTask(
              taskId,
              conversationId: runtime.conversationId,
              mode: runtime.mode,
            );
            await _persistPromptQueue(runtime);
            return;
          }
          final result = await AgentRuntimeService.promptSession(
            conversationId: runtime.conversationId,
            sessionId: prompt.sessionId,
            requestId: taskId,
            userMessageId: userId,
            agentId: prompt.agentId,
            text: prompt.text,
            attachments: prompt.attachments,
            conversationMode: prompt.conversationMode,
            model: prompt.model,
            effort: prompt.effort,
            approvalPolicy: prompt.approvalPolicy,
            approvalsReviewer: prompt.approvalsReviewer,
            sandboxPolicy: prompt.sandboxPolicy,
            terminalEnvironment: prompt.terminalEnvironment,
          );
          applyAcpPromptResponse(
            taskId: taskId,
            conversationId: runtime.conversationId,
            mode: runtime.mode,
            sessionId: result['sessionId']?.toString() ?? prompt.sessionId,
            turnId: (result['turnId'] ?? result['promptId'])?.toString(),
            stopReason: (result['stopReason'] ?? result['status'])?.toString(),
            error: result['error']?.toString(),
          );
        } catch (error) {
          applyAcpPromptResponse(
            taskId: taskId,
            conversationId: runtime.conversationId,
            mode: runtime.mode,
            sessionId: prompt.sessionId,
            stopReason: 'error',
            error: formatAgentRuntimeErrorForUser(error),
          );
        }
      }
    } finally {
      runtime._drainingPromptQueue = false;
      _notifyRuntimeListeners();
    }
  }
}
