import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/models/chat_link_preview.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/agent_event_reducer.dart';
import 'package:ui/services/agent_identity.dart';
import 'package:ui/services/agent_message_kinds.dart';
import 'package:ui/services/agent_tool_call_parser.dart';
import 'package:ui/services/conversation_history_service.dart';
import 'package:ui/services/conversation_service.dart';
import 'package:ui/services/link_preview_service.dart';
import 'package:ui/services/voice_playback_coordinator.dart';
import 'package:ui/services/agent_stream_meta.dart';
import 'package:ui/services/agent_diff_parser.dart';

part 'chat_runtime_internal_support.dart';
part 'chat_runtime_state.dart';
part 'chat_runtime_snapshot_support.dart';
part 'chat_runtime_persistence_support.dart';
part 'chat_runtime_external_message_support.dart';
part 'chat_runtime_message_support.dart';
part 'chat_runtime_streaming_support.dart';
part 'chat_runtime_thinking_support.dart';
part 'chat_runtime_tool_support.dart';

const String kChatRuntimeModeNormal = 'normal';
const String kChatRuntimeModeOpenClaw = 'openclaw';
const String kChatRuntimeModeAgent = 'agent';
const int _kStreamingTextChunkFlushThreshold = 5;

class _TaskBinding {
  const _TaskBinding({required this.conversationId, required this.mode});

  final int conversationId;
  final String mode;
}

class _PendingPersistenceRequest {
  _PendingPersistenceRequest({
    required this.conversationId,
    required this.mode,
    required this.timer,
    this.generateSummary = false,
    this.markComplete = false,
    this.persistMessages = false,
  });

  final int conversationId;
  final String mode;
  final Timer timer;
  final bool generateSummary;
  final bool markComplete;
  final bool persistMessages;
}

class ChatConversationRuntimeCoordinator extends ChangeNotifier {
  ChatConversationRuntimeCoordinator._();

  static final ChatConversationRuntimeCoordinator instance =
      ChatConversationRuntimeCoordinator._();

  String _agentTextBaseId(String taskId) => '$taskId-text';

  final AgentEventReducer _agentEventReducer = const AgentEventReducer();
  final Map<String, ChatConversationRuntimeState> _runtimes =
      <String, ChatConversationRuntimeState>{};
  final Map<String, _TaskBinding> _taskBindings = <String, _TaskBinding>{};
  final Map<String, _PendingPersistenceRequest> _pendingPersistence =
      <String, _PendingPersistenceRequest>{};
  // Conversation snapshots are produced by several independent triggers:
  // streamed ACP updates, turn completion, app backgrounding, and page
  // disposal. Keep one ordered tail per runtime so an older snapshot can
  // never finish after a newer one and move durable history backwards.
  final Map<String, Future<void>> _persistenceTails = <String, Future<void>>{};
  final Set<String> _ephemeralRuntimeKeys = <String>{};

  bool _initialized = false;

  bool get _isEnglish => LegacyTextLocalizer.isEnglish;

  void _notifyRuntimeListeners() => notifyListeners();

  void ensureInitialized() {
    if (_initialized) return;
    _initialized = true;
    unawaited(VoicePlaybackCoordinator.instance.ensureInitialized());

    AssistsMessageService.initialize();
    AssistsMessageService.addOnExternalUserMessageAppendedCallback(
      _handleExternalUserMessageAppended,
    );
  }

  ChatConversationRuntimeState? runtimeFor({
    required int conversationId,
    required String mode,
  }) {
    return _runtimes[_runtimeKey(conversationId: conversationId, mode: mode)];
  }

  /// Conversation ids with live work in the shared ACP projection.
  ///
  /// The drawer must read this from the same runtime/reducer that renders the
  /// chat. A second event subscription in the drawer would create another
  /// lifecycle interpretation and can disagree during a session switch.
  Set<int> get activeAgentConversationIds => Set.unmodifiable(
    _runtimes.values
        .where(
          (runtime) =>
              runtime.mode == kChatRuntimeModeAgent && runtime.hasInFlightTask,
        )
        .map((runtime) => runtime.conversationId),
  );

  bool isAgentConversationActive(int conversationId) {
    final runtime = runtimeFor(
      conversationId: conversationId,
      mode: kChatRuntimeModeAgent,
    );
    return runtime?.hasInFlightTask ?? false;
  }

  /// Resolves an incoming ACP event to the runtime that admitted its official
  /// turn. Conversation mode is UI metadata and can be stale during a mode
  /// handoff; the `(conversationId, turnId)` binding is the authoritative
  /// ownership check for streaming and terminal events.
  String? modeForAcpEvent({
    required int conversationId,
    String? sessionId,
    String? turnId,
  }) {
    final normalizedSessionId = sessionId?.trim() ?? '';
    final normalizedTurnId = turnId?.trim() ?? '';
    if (normalizedTurnId.isEmpty && normalizedSessionId.isEmpty) return null;
    for (final mode in <String>[
      kChatRuntimeModeNormal,
      kChatRuntimeModeAgent,
      kChatRuntimeModeOpenClaw,
    ]) {
      final runtime = runtimeFor(conversationId: conversationId, mode: mode);
      if (runtime == null) continue;
      if ((normalizedSessionId.isNotEmpty &&
              runtime.activeAcpSessionId == normalizedSessionId) ||
          runtime.activeAcpTurnId == normalizedTurnId ||
          runtime.currentDispatchTurnId == normalizedTurnId ||
          runtime.lastAgentTurnId == normalizedTurnId ||
          runtime.activeAcpTurnId == normalizedTurnId) {
        return mode;
      }
    }
    return null;
  }

  /// Returns the conversation that first claimed a legacy process identity.
  /// Process-only events have no ACP session/turn boundary; callers can use a
  /// known owner when available and apply their explicit compatibility policy
  /// for an unknown first event.
  int? conversationIdForStandaloneProcess(String processId) {
    final normalized = processId.trim();
    if (normalized.isEmpty) return null;
    for (final entry in _runtimes.entries) {
      if (entry.value.standaloneProcessRunIds.containsKey(normalized)) {
        return entry.value.conversationId;
      }
    }
    return null;
  }

  /// Finds the conversation that owns a session/turn when an ACP event does
  /// not include the optional host conversation id. This is important for
  /// background Sub Agent runs: the visible page must not become the implicit
  /// owner of an event from another conversation.
  int? conversationIdForAcpEvent({String? sessionId, String? turnId}) {
    final normalizedSessionId = sessionId?.trim() ?? '';
    final normalizedTurnId = turnId?.trim() ?? '';
    if (normalizedSessionId.isEmpty && normalizedTurnId.isEmpty) {
      return null;
    }
    for (final runtime in _runtimes.values) {
      if (normalizedSessionId.isNotEmpty &&
          (runtime.activeAcpSessionId == normalizedSessionId ||
              runtime.knownAcpSessionIds.contains(normalizedSessionId))) {
        return runtime.conversationId;
      }
      if (normalizedTurnId.isEmpty) continue;
      if (runtime.activeAcpTurnId == normalizedTurnId ||
          runtime.currentDispatchTurnId == normalizedTurnId ||
          runtime.lastAgentTurnId == normalizedTurnId ||
          runtime.completedAgentTurnIds.contains(normalizedTurnId) ||
          runtime.completedAcpTurnIds.contains(normalizedTurnId) ||
          runtime.acpTurnToRunIds.keys.any((key) {
            return key == normalizedTurnId ||
                key.endsWith(':$normalizedTurnId');
          })) {
        return runtime.conversationId;
      }
    }
    return null;
  }

  ChatConversationRuntimeState ensureRuntime({
    required int conversationId,
    required String mode,
    List<ChatMessageModel>? initialMessages,
    ConversationModel? conversation,
    ChatIslandDisplayLayer? initialChatIslandDisplayLayer,
  }) {
    final key = _runtimeKey(conversationId: conversationId, mode: mode);
    final existing = _runtimes[key];
    final runtime =
        existing ??
        ChatConversationRuntimeState(
          conversationId: conversationId,
          mode: mode,
        );
    if (existing == null) {
      if (initialChatIslandDisplayLayer != null) {
        runtime.chatIslandDisplayLayer = initialChatIslandDisplayLayer;
      }
      _runtimes[key] = runtime;
    }
    if (runtime.messages.isEmpty && initialMessages != null) {
      runtime.messages.addAll(
        _dedupeEquivalentAgentUserMessages(initialMessages),
      );
    }
    if (conversation != null) {
      runtime.conversation = conversation;
    }
    return runtime;
  }

  ChatConversationRuntimeState ensureEphemeralRuntime({
    required int conversationId,
    required String mode,
    List<ChatMessageModel>? initialMessages,
    ConversationModel? conversation,
    ChatIslandDisplayLayer? initialChatIslandDisplayLayer,
  }) {
    final runtime = ensureRuntime(
      conversationId: conversationId,
      mode: mode,
      initialMessages: initialMessages,
      conversation: conversation,
      initialChatIslandDisplayLayer: initialChatIslandDisplayLayer,
    );
    _ephemeralRuntimeKeys.add(
      _runtimeKey(conversationId: conversationId, mode: mode),
    );
    return runtime;
  }

  bool isEphemeralRuntime({required int conversationId, required String mode}) {
    return _ephemeralRuntimeKeys.contains(
      _runtimeKey(conversationId: conversationId, mode: mode),
    );
  }

  void registerTask({
    required String taskId,
    required int conversationId,
    required String mode,
  }) {
    ensureInitialized();
    final existingBinding = _taskBindings[taskId];
    if (existingBinding != null &&
        (existingBinding.conversationId != conversationId ||
            existingBinding.mode != mode)) {
      // A remote/local handoff can resolve the same logical submission to a
      // different runtime after an await. Do not overwrite the binding and
      // strand the old runtime as an invisible active turn.
      unregisterTask(
        taskId,
        conversationId: existingBinding.conversationId,
        mode: existingBinding.mode,
      );
    }
    final runtime = ensureRuntime(conversationId: conversationId, mode: mode);
    // A new prompt starts with a local render key. The official ACP turn is
    // admitted by the first session/update; never let a previous turn's
    // official id claim the new prompt's terminal event.
    if (runtime.currentDispatchTurnId != taskId) {
      runtime.activeAcpTurnId = null;
      runtime.activeRunId = taskId;
    }
    runtime.activeRunId ??= taskId;
    _taskBindings[taskId] = _TaskBinding(
      conversationId: conversationId,
      mode: mode,
    );
  }

  void beginAcpTurn({
    required String taskId,
    required int conversationId,
    required String mode,
  }) {
    final existingBinding = _taskBindings[taskId];
    final existingRuntime = runtimeFor(
      conversationId: conversationId,
      mode: mode,
    );
    final alreadyStarted =
        existingBinding?.conversationId == conversationId &&
        existingBinding?.mode == mode &&
        existingRuntime?.isAiResponding == true &&
        existingRuntime?.currentDispatchTurnId == taskId &&
        existingRuntime?.lastAgentTurnId == taskId;
    registerTask(taskId: taskId, conversationId: conversationId, mode: mode);
    if (alreadyStarted) {
      return;
    }
    final runtime = ensureRuntime(conversationId: conversationId, mode: mode);
    runtime.persistenceGeneration += 1;
    runtime.isAiResponding = true;
    runtime.currentDispatchTurnId = taskId;
    runtime.agentEntryStartTimes['prompt:$taskId'] =
        DateTime.now().millisecondsSinceEpoch;
    runtime.activeRunId = taskId;
    runtime.lastAgentTurnId = taskId;
    runtime.currentThinkingStage = ThinkingStage.thinking.value;
    runtime.allowRetiredAcpSessionReactivation = true;
    notifyListeners();
  }

  /// Records the official ACP session after `session/new` and before
  /// `session/prompt`. This is an identity reservation, not a second local
  /// lifecycle: event admission and cancellation can now use the same
  /// session key the Agent uses on the wire.
  bool bindAcpSession({
    required String taskId,
    required int conversationId,
    required String mode,
    required String sessionId,
  }) {
    final normalizedSessionId = sessionId.trim();
    if (normalizedSessionId.isEmpty ||
        !isTaskActive(
          taskId: taskId,
          conversationId: conversationId,
          mode: mode,
        )) {
      return false;
    }
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null) return false;
    final currentSessionId = runtime.activeAcpSessionId?.trim() ?? '';
    final currentTurnId = runtime.activeAcpTurnId?.trim() ?? '';
    if (currentSessionId.isNotEmpty &&
        currentSessionId != normalizedSessionId &&
        currentTurnId.isNotEmpty) {
      return false;
    }
    runtime.activeAcpSessionId = normalizedSessionId;
    runtime.knownAcpSessionIds.add(normalizedSessionId);
    runtime.retiredAcpSessionIds.remove(normalizedSessionId);
    notifyListeners();
    return true;
  }

  /// Returns whether [taskId] still owns the live local turn in this runtime.
  ///
  /// A task binding can outlive its visible turn while an async preflight or
  /// transport callback is unwinding. Callers that want to mutate shared
  /// presentation state must use this identity check instead of merely
  /// checking that a binding exists.
  bool isTaskActive({
    required String taskId,
    required int conversationId,
    required String mode,
  }) {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    final binding = _taskBindings[taskId];
    if (runtime == null ||
        binding == null ||
        binding.conversationId != conversationId ||
        binding.mode != mode) {
      return false;
    }
    return runtime.activeRunId == taskId ||
        runtime.currentDispatchTurnId == taskId ||
        runtime.lastAgentTurnId == taskId;
  }

  /// Commits a terminal transition proven by an authoritative remote session
  /// snapshot. A normal history snapshot has no lifecycle authority and is
  /// therefore still blocked by [replaceConversationSnapshot]; this seam is
  /// reserved for the remote ACP read path after its session bookkeeping and
  /// payload both prove that no turn is active.
  bool finishTaskFromAuthoritativeSnapshot({
    required String taskId,
    required int conversationId,
    required String mode,
    String? sessionId,
    String? turnId,
  }) {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null ||
        !isTaskActive(
          taskId: taskId,
          conversationId: conversationId,
          mode: mode,
        )) {
      return false;
    }
    final incomingSessionId = sessionId?.trim() ?? '';
    final activeSessionId = runtime.activeAcpSessionId?.trim() ?? '';
    if (incomingSessionId.isNotEmpty &&
        activeSessionId.isNotEmpty &&
        incomingSessionId != activeSessionId) {
      return false;
    }
    final incomingTurnId = turnId?.trim() ?? '';
    final activeTurnId = runtime.activeAcpTurnId?.trim() ?? '';
    if (incomingTurnId.isNotEmpty &&
        activeTurnId.isNotEmpty &&
        incomingTurnId != activeTurnId) {
      return false;
    }
    // `unregisterTask` already owns the terminal cleanup, but it can only
    // clear an official ACP turn when the reducer has previously recorded the
    // protocol-to-local mapping. A remote `thread/read` snapshot may be the
    // first lifecycle signal after a missed push terminal event, so establish
    // that mapping from the identities we just validated before delegating to
    // the same cleanup path.
    if (activeTurnId.isNotEmpty) {
      runtime.resolveRunId(
        sessionId: activeSessionId.isEmpty
            ? incomingSessionId
            : activeSessionId,
        turnId: activeTurnId,
        fallback: taskId,
      );
    }
    unregisterTask(taskId, conversationId: conversationId, mode: mode);
    return true;
  }

  /// Compatibility name for older callers. Starting a turn must stay
  /// presentation-free; real ACP events are the only source of thinking
  /// cards.
  void primeAcpThinking({
    required String taskId,
    required int conversationId,
    required String mode,
  }) {
    beginAcpTurn(taskId: taskId, conversationId: conversationId, mode: mode);
  }

  /// Compatibility name for older pure-chat call sites. Pure chat is still
  /// an ACP turn; it only has an empty tool catalog.
  void primePureChatThinking({
    required String taskId,
    required int conversationId,
    required String mode,
  }) {
    beginAcpTurn(taskId: taskId, conversationId: conversationId, mode: mode);
  }

  void unregisterTask(String taskId, {int? conversationId, String? mode}) {
    final binding = _taskBindings[taskId];
    // New lifecycle callers pass the conversation/mode that admitted the
    // task. If a delayed callback belongs to an older binding, it is a no-op;
    // resolving by the bare task id would otherwise clean the newer turn.
    if (conversationId != null &&
        (binding == null || binding.conversationId != conversationId)) {
      return;
    }
    if (mode != null && (binding == null || binding.mode != mode.trim())) {
      return;
    }
    final runtime = _runtimeForTask(taskId);
    if (runtime != null) {
      // The UI can unregister optimistically when the user presses Stop,
      // before the native ACP terminal event arrives. Fence both identity
      // spaces here: taskId is the local render key, while activeAcpTurnId is
      // the official wire turn. A late session/update for either id must not
      // become the first event of the next prompt.
      final officialTurnId = runtime.activeAcpTurnId?.trim();
      _rememberCompletedTurn(runtime, taskId);
      if (officialTurnId != null && officialTurnId.isNotEmpty) {
        _rememberCompletedTurn(runtime, officialTurnId);
        runtime.rememberCompletedAcpTurn(officialTurnId);
      }
      _flushStreamingTextForTask(runtime, taskId);
      _clearStreamingTextBatchesForTask(runtime, taskId);
      runtime.currentAiMessages.remove(taskId);
      runtime.currentThinkingMessages.remove(taskId);
      if (runtime.currentDispatchTurnId == taskId) {
        runtime.currentDispatchTurnId = null;
      }
      if (runtime.activeRunId == taskId) {
        runtime.activeRunId = null;
      }
      if (runtime.activeAcpTurnId == taskId ||
          _acpTurnBelongsToTask(runtime, runtime.activeAcpTurnId, taskId)) {
        runtime.activeAcpTurnId = null;
      }
      if (runtime.lastAgentTurnId == taskId) {
        runtime.lastAgentTurnId = null;
      }
      // A late cleanup from an older turn must not tear down the newer turn
      // that is already running in the same conversation.
      final hasAnotherTurn =
          runtime.currentDispatchTurnId != null ||
          runtime.lastAgentTurnId != null ||
          runtime.activeAcpTurnId != null;
      if (!hasAnotherTurn) {
        runtime.activeAcpSessionId = null;
        runtime.isAiResponding = false;
        runtime.isExecutingTask = false;
        runtime.isCheckingExecutableTask = false;
        runtime.isContextCompressing = false;
        runtime.deepThinkingContent = '';
        runtime.isDeepThinking = false;
        runtime.isInputAreaVisible = true;
        runtime.currentThinkingStage = ThinkingStage.thinking.value;
        runtime.activeToolCardId = null;
        runtime.activeThinkingCardId = null;
        runtime.pendingAgentTextTaskId = null;
        runtime.waitingThinkingBeforeAgentTextTaskId = null;
        runtime.pendingThinkingRoundSplit = false;
      }
      notifyListeners();
    }
    _taskBindings.remove(taskId);
  }

  bool _acpTurnBelongsToTask(
    ChatConversationRuntimeState runtime,
    String? turnId,
    String taskId,
  ) {
    final normalizedTurnId = turnId?.trim() ?? '';
    if (normalizedTurnId.isEmpty) return false;
    final turnOnlyKey = acpTurnKey(turnId: normalizedTurnId);
    if (runtime.acpTurnToRunIds[turnOnlyKey] == taskId) return true;
    return runtime.acpTurnToRunIds.entries.any(
      (entry) =>
          entry.value == taskId &&
          (entry.key == normalizedTurnId ||
              entry.key.endsWith(':$normalizedTurnId')),
    );
  }

  void _rememberCompletedTurn(
    ChatConversationRuntimeState runtime,
    String turnId,
  ) {
    final normalized = turnId.trim();
    if (normalized.isEmpty) return;
    runtime.completedAgentTurnIds.add(normalized);
  }

  AgentReduceResult applyAgentEvent({
    required int conversationId,
    required Map<String, dynamic> event,
    String mode = kChatRuntimeModeAgent,
    ConversationModel? conversation,
  }) {
    ensureInitialized();
    final runtime = ensureRuntime(
      conversationId: conversationId,
      mode: mode,
      conversation: conversation,
      initialChatIslandDisplayLayer: ChatIslandDisplayLayer.mode,
    );
    final eventSessionId = acpEventSessionId(event);
    final eventTurnId = acpEventTurnId(event);
    final presentation = acpEventPresentation(event);
    final carriesFinalTurnUsage = acpEventCarriesFinalTurnUsage(event);
    final allowsHostSessionAdmission = acpEventAllowsImplicitTurnAdmission(
      event,
    );
    final activeAcpTurn = runtime.activeAcpTurnId?.trim() ?? '';
    final currentDispatchTurn = runtime.currentDispatchTurnId?.trim() ?? '';
    final incomingTurn = eventTurnId?.trim() ?? '';
    final isKnownCurrentTurn =
        incomingTurn.isNotEmpty &&
        (incomingTurn == activeAcpTurn ||
            incomingTurn == currentDispatchTurn ||
            incomingTurn == (runtime.lastAgentTurnId?.trim() ?? ''));
    // New ACP traffic must carry its session identity. The only exception is
    // an already-known current turn, or an explicitly marked host reservation.
    // The old task/kind payloads remain supported below as a named
    // compatibility shape; they must not silently become the admission rule
    // for new protocol events.
    if (eventSessionId == null &&
        incomingTurn.isNotEmpty &&
        !isKnownCurrentTurn &&
        !allowsHostSessionAdmission &&
        !acpEventIsLegacyCompatibilityShape(event)) {
      return const AgentReduceResult(handled: false, affectsActiveTurn: false);
    }
    if (!runtime.acceptsAcpEvent(
      sessionId: eventSessionId,
      turnId: eventTurnId,
      allowCompletedTurnMetadata: carriesFinalTurnUsage,
      allowSessionAdmission: allowsHostSessionAdmission,
    )) {
      return const AgentReduceResult(handled: false, affectsActiveTurn: false);
    }
    // The reducer intentionally consumes stale ACP events so they do not
    // produce a second error path. That does not make them owners of the
    // visible turn, though. Capture ownership before reduction because a
    // terminal event may clear the active ACP fields as part of its normal
    // projection.
    final activeAcpTurnBefore = runtime.activeAcpTurnId?.trim() ?? '';
    final dispatchTurnBefore = runtime.currentDispatchTurnId?.trim() ?? '';
    final affectsActiveTurn = eventTurnId == null
        ? runtime.isAiResponding &&
              dispatchTurnBefore.isNotEmpty &&
              activeAcpTurnBefore.isEmpty
        : activeAcpTurnBefore.isEmpty
        ? runtime.isAiResponding && dispatchTurnBefore.isNotEmpty
        : activeAcpTurnBefore == eventTurnId;
    final result = _agentEventReducer
        .reduce(runtime: runtime, event: event)
        .copyWith(affectsActiveTurn: affectsActiveTurn);
    if (result.handled) {
      _annotateAgentMessages(runtime, event, result);
      _notifyAcpVoicePlayback(runtime, event, result);
      if (presentation?['compaction'] is Map) {
        final markerIndex = runtime.messages.indexWhere(
          (message) =>
              message.type == 2 &&
              message.cardData?['type'] == 'context_compaction_marker',
        );
        if (markerIndex != -1) {
          _persistContextCompactionMarkerIfNeeded(
            conversationId: conversationId,
            mode: mode,
            message: runtime.messages[markerIndex],
          );
        }
      }
      notifyListeners();
      if (!isEphemeralRuntime(conversationId: conversationId, mode: mode)) {
        schedulePersistRuntimeConversation(
          conversationId: conversationId,
          // ACP execution can be hosted by the normal chat runtime (for
          // example Xiaowan) as well as the dedicated Agent page. Persist
          // into the runtime that admitted the event; using the Agent mode
          // here strands normal-chat history in a different storage bucket,
          // so the next Xiaowan prompt cannot reconstruct its context.
          mode: mode,
          persistMessages: true,
          // Exact usage can legally trail turn/completed. Persist it now so
          // leaving the page cannot strand the footer in memory only.
          delay: carriesFinalTurnUsage
              ? Duration.zero
              : const Duration(milliseconds: 350),
        );
      }
    }
    return result;
  }

  /// Applies the terminal result returned by the official ACP
  /// `session/prompt` request through the same reducer and persistence path as
  /// streamed `session/update` notifications. This is the canonical terminal
  /// boundary; host code must not synthesize a private `turn/*` event merely
  /// because a MethodChannel result is not an EventChannel notification.
  AgentReduceResult applyAcpPromptResponse({
    required String taskId,
    required int conversationId,
    required String? sessionId,
    String? turnId,
    String? stopReason,
    String? error,
    String mode = kChatRuntimeModeAgent,
    ConversationModel? conversation,
  }) {
    ensureInitialized();
    // The awaited prompt result belongs to the request that sent it, even
    // when ACP supplies no wire turnId. Never lend a later request's active
    // identity to an old response or transport failure.
    if (!isTaskActive(
      taskId: taskId,
      conversationId: conversationId,
      mode: mode,
    )) {
      return const AgentReduceResult(handled: false, affectsActiveTurn: false);
    }
    final runtime = ensureRuntime(
      conversationId: conversationId,
      mode: mode,
      conversation: conversation,
      initialChatIslandDisplayLayer: ChatIslandDisplayLayer.mode,
    );
    final result = _agentEventReducer.reducePromptResponse(
      runtime: runtime,
      sessionId: sessionId,
      turnId: turnId,
      stopReason: stopReason,
      error: error,
    );
    _taskBindings.remove(taskId);
    if (result.handled) {
      notifyListeners();
      if (!isEphemeralRuntime(conversationId: conversationId, mode: mode)) {
        schedulePersistRuntimeConversation(
          conversationId: conversationId,
          mode: mode,
          persistMessages: true,
          delay: Duration.zero,
        );
      }
    }
    return result;
  }

  /// Keeps ACP assistant text on the same shared voice path that the former
  /// Xiaowan stream handler used. Voice is a presentation side effect, not an
  /// ACP event, so it belongs at the coordinator boundary rather than in a
  /// Harness adapter or a second reducer.
  void _notifyAcpVoicePlayback(
    ChatConversationRuntimeState runtime,
    Map<String, dynamic> event,
    AgentReduceResult result,
  ) {
    final method = result.method;
    if (method != 'item/agentMessage/delta' &&
        method != 'turn/completed' &&
        method != 'thread/closed' &&
        method != 'turn/failed') {
      return;
    }

    Map<String, dynamic>? asStringMap(dynamic value) {
      if (value is Map<String, dynamic>) return value;
      if (value is Map) {
        return value.map((key, item) => MapEntry(key.toString(), item));
      }
      return null;
    }

    String? firstString(Iterable<dynamic> values) {
      for (final value in values) {
        final text = value?.toString().trim() ?? '';
        if (text.isNotEmpty) return text;
      }
      return null;
    }

    final message = asStringMap(event['message']) ?? event;
    final params =
        asStringMap(event['params']) ??
        asStringMap(message['params']) ??
        const <String, dynamic>{};
    final update = asStringMap(params['update']);
    final taskId = runtime.resolveAcpEventRunId(
      sessionId: firstString([
        event['sessionId'],
        event['session_id'],
        params['sessionId'],
        params['session_id'],
        update?['sessionId'],
      ]),
      turnId:
          result.turnId ??
          firstString([
            event['turnId'],
            event['turn_id'],
            params['turnId'],
            params['turn_id'],
            update?['turnId'],
          ]),
      fallback: runtime.lastAgentTurnId ?? runtime.currentDispatchTurnId,
    );

    ChatMessageModel? assistantMessage;
    if (method == 'item/agentMessage/delta') {
      final itemId = firstString([
        params['entryId'],
        params['itemId'],
        params['item_id'],
        update?['entryId'],
        update?['messageId'],
      ]);
      final candidates = runtime.messages.where(
        (message) =>
            message.type == 1 &&
            message.user == 2 &&
            (taskId == null ||
                message.streamMeta?['parentTaskId']?.toString() == taskId),
      );
      if (itemId != null) {
        assistantMessage = candidates.cast<ChatMessageModel?>().firstWhere(
          (message) =>
              message!.id == itemId ||
              message.id.contains(itemId) ||
              message.streamMeta?['entryId']?.toString() == itemId,
          orElse: () => null,
        );
      }
      assistantMessage ??= candidates.isEmpty ? null : candidates.first;
      final assistantText = assistantMessage?.text?.trim() ?? '';
      if (assistantMessage == null || assistantText.isEmpty) {
        return;
      }
      unawaited(
        VoicePlaybackCoordinator.instance.onAssistantMessageUpdated(
          messageId: assistantMessage.id,
          text: assistantText,
          isFinal: false,
        ),
      );
      return;
    }

    final officialTurnId = result.turnId?.trim() ?? '';
    assistantMessage = runtime.messages.cast<ChatMessageModel?>().firstWhere((
      message,
    ) {
      if (message == null || message.type != 1 || message.user != 2) {
        return false;
      }
      final streamTurnId = message.streamMeta?['turnId']?.toString().trim();
      final parentTaskId = message.streamMeta?['parentTaskId']
          ?.toString()
          .trim();
      return (officialTurnId.isNotEmpty && streamTurnId == officialTurnId) ||
          (taskId != null && parentTaskId == taskId);
    }, orElse: () => null);
    final assistantText = assistantMessage?.text?.trim() ?? '';
    if (assistantMessage == null || assistantText.isEmpty) {
      return;
    }
    unawaited(
      VoicePlaybackCoordinator.instance.onAssistantMessageCompleted(
        messageId: assistantMessage.id,
        text: assistantText,
      ),
    );
  }

  void _annotateAgentMessages(
    ChatConversationRuntimeState runtime,
    Map<String, dynamic> event,
    AgentReduceResult result,
  ) {
    String? stringValue(dynamic value) {
      final normalized = value?.toString().trim() ?? '';
      return normalized.isEmpty ? null : normalized;
    }

    Map<String, dynamic>? stringMap(dynamic value) {
      if (value is Map<String, dynamic>) {
        return value;
      }
      if (value is Map) {
        return value.map((key, entry) => MapEntry(key.toString(), entry));
      }
      return null;
    }

    final envelope = stringMap(event['message']);
    final params = stringMap(event['params']) ?? stringMap(envelope?['params']);
    final agentId =
        stringValue(event['agentId']) ??
        stringValue(params?['agentId']) ??
        stringValue(envelope?['agentId']);
    if (agentId == null) {
      return;
    }
    final agentName =
        stringValue(event['agentName']) ??
        stringValue(params?['agentName']) ??
        stringValue(envelope?['agentName']);
    final protocolTurnId =
        result.turnId ??
        stringValue(event['turnId']) ??
        stringValue(params?['turnId']);
    final protocolSessionId =
        stringValue(event['sessionId']) ??
        stringValue(params?['sessionId']) ??
        stringValue(envelope?['sessionId']);
    final taskId = runtime.resolveAcpEventRunId(
      sessionId: protocolSessionId,
      turnId: protocolTurnId,
      fallback: runtime.activeRunId ?? runtime.currentDispatchTurnId,
    );

    for (var index = 0; index < runtime.messages.length; index += 1) {
      final message = runtime.messages[index];
      if (message.agentId != null) {
        continue;
      }
      final cardData = message.cardData;
      final isAcpMessage =
          message.id.contains('-agent-') ||
          message.id.contains('-codex-') ||
          isAgentToolUiStyle(cardData?['uiStyle']) ||
          isAgentRequestCardType(cardData?['type']);
      if (!isAcpMessage) {
        continue;
      }
      final parentTaskId = stringValue(
        message.streamMeta?['parentTaskId'] ??
            message.streamMeta?['runId'] ??
            cardData?['taskId'] ??
            cardData?['runId'] ??
            cardData?['taskID'],
      );
      if (taskId != null && parentTaskId != null && parentTaskId != taskId) {
        continue;
      }
      final content = Map<String, dynamic>.from(
        message.content ?? const <String, dynamic>{},
      );
      content['agentId'] = agentId;
      if (agentName != null) {
        content['agentName'] = agentName;
      }
      if (cardData != null) {
        content['cardData'] = <String, dynamic>{
          ...cardData,
          'agentId': agentId,
          if (agentName != null) 'agentName': agentName,
          if (protocolSessionId != null) 'sessionId': protocolSessionId,
        };
      }
      runtime.messages[index] = message.copyWith(content: content);
    }
  }

  void clearPureChatThinking({
    required String taskId,
    required int conversationId,
    required String mode,
    bool removeCard = true,
  }) {
    clearTaskThinkingPresentation(
      taskId: taskId,
      conversationId: conversationId,
      mode: mode,
      removeCard: removeCard,
    );
  }

  /// Removes the optimistic thinking surface when a turn fails before the
  /// first official ACP update. Without this, a Provider/connect error leaves
  /// the chat showing an infinite "正在思考" card even though the turn ended.
  void clearTaskThinkingPresentation({
    required String taskId,
    required int conversationId,
    required String mode,
    bool removeCard = true,
  }) {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    final binding = _taskBindings[taskId];
    if (runtime == null ||
        binding == null ||
        binding.conversationId != conversationId ||
        binding.mode != mode) {
      return;
    }

    final ownsActiveThinkingState =
        runtime.activeRunId == taskId ||
        runtime.currentDispatchTurnId == taskId ||
        runtime.lastAgentTurnId == taskId;

    _flushThinkingBatch(
      runtime,
      taskId,
      _StreamingTextStreamKind.pureChatThinking,
    );
    _flushThinkingBatch(
      runtime,
      taskId,
      _StreamingTextStreamKind.agentThinking,
    );
    runtime.currentThinkingMessages.remove(taskId);
    if (ownsActiveThinkingState) {
      runtime.deepThinkingContent = '';
      runtime.isDeepThinking = false;
    }
    if (runtime.lastAgentTurnId == taskId) {
      runtime.lastAgentTurnId = null;
    }
    if (ownsActiveThinkingState &&
        runtime.activeThinkingCardId != null &&
        (runtime.activeThinkingCardId == taskId ||
            runtime.activeThinkingCardId!.startsWith('$taskId-thinking'))) {
      runtime.activeThinkingCardId = null;
    }
    if (ownsActiveThinkingState) {
      runtime.pendingThinkingRoundSplit = false;
      runtime.thinkingRound = 0;
    }
    if (removeCard) {
      runtime.messages.removeWhere((message) {
        final cardData = message.cardData;
        return message.type == 2 &&
            cardData?['type'] == 'deep_thinking' &&
            (cardData?['taskID'] ?? '').toString() == taskId;
      });
    }
    _clearStreamingTextBatchesForTask(runtime, taskId);
    notifyListeners();
  }

  @visibleForTesting
  void resetForTest() {
    for (final request in _pendingPersistence.values) {
      request.timer.cancel();
    }
    _pendingPersistence.clear();
    for (final runtime in _runtimes.values) {
      _flushRuntimeStreamingText(runtime);
      runtime.dispose();
    }
    _runtimes.clear();
    _taskBindings.clear();
    _ephemeralRuntimeKeys.clear();
  }

  void clearConversationRuntimeSession({
    required int conversationId,
    required String mode,
  }) {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null) return;
    runtime.persistenceGeneration += 1;
    _flushRuntimeStreamingText(runtime);
    // Clearing a runtime is a lifecycle boundary, not just a UI reset. Fence
    // both local and official identities before releasing them, and remove
    // the local binding so late preflight/error callbacks cannot resolve back
    // into a new session that reuses this conversation.
    final localRunId =
        runtime.activeRunId?.trim() ??
        runtime.currentDispatchTurnId?.trim() ??
        runtime.lastAgentTurnId?.trim() ??
        '';
    final officialTurnId = runtime.activeAcpTurnId?.trim() ?? '';
    if (localRunId.isNotEmpty) {
      _rememberCompletedTurn(runtime, localRunId);
    }
    if (officialTurnId.isNotEmpty) {
      _rememberCompletedTurn(runtime, officialTurnId);
      runtime.rememberCompletedAcpTurn(officialTurnId);
    }
    _taskBindings.removeWhere(
      (_, binding) =>
          binding.conversationId == conversationId && binding.mode == mode,
    );
    final sessionsToRetire = <String>{
      ...runtime.knownAcpSessionIds,
      if (runtime.activeAcpSessionId?.trim().isNotEmpty == true)
        runtime.activeAcpSessionId!.trim(),
    };
    runtime.retiredAcpSessionIds.addAll(sessionsToRetire);
    runtime.allowRetiredAcpSessionReactivation = false;
    runtime.currentDispatchTurnId = null;
    runtime.activeRunId = null;
    runtime.activeAcpTurnId = null;
    runtime.activeAcpSessionId = null;
    runtime.isAiResponding = false;
    runtime.isExecutingTask = false;
    runtime.isCheckingExecutableTask = false;
    runtime.isContextCompressing = false;
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    runtime.currentThinkingMessages.clear();
    runtime.currentAcpUserMessages.clear();
    runtime.currentAiMessages.clear();
    runtime.standaloneProcessRunIds.clear();
    runtime.agentReplayDeltaOffsets.clear();
    runtime.pendingAcpPerformanceMetrics.clear();
    runtime.pendingAcpReasoningCardData.clear();
    runtime.pendingAcpAssistantPresentation.clear();
    runtime.processedAcpEventIds.clear();
    runtime.acpCompatibilityWarningShown = false;
    runtime.availableAcpCommands = <Map<String, dynamic>>[];
    runtime.acpConfigOptions = <Map<String, dynamic>>[];
    runtime.currentAcpModeId = null;
    runtime.acpSessionInfo = <String, dynamic>{};
    runtime.acpExtensionUpdates.clear();
    runtime.currentThinkingStage = ThinkingStage.thinking.value;
    runtime.lastAgentTurnId = null;
    runtime.pendingAgentTextTaskId = null;
    runtime.waitingThinkingBeforeAgentTextTaskId = null;
    runtime.activeToolCardId = null;
    runtime.activeThinkingCardId = null;
    runtime.activeContextCompactionMarkerId = null;
    runtime.pendingThinkingRoundSplit = false;
    runtime.toolCardSequence = 0;
    runtime.thinkingRound = 0;
    runtime._streamingTextBatches.clear();
    runtime.agentEntrySequences.clear();
    runtime.agentEntryStartTimes.clear();
    runtime.agentNextEntrySequence = 0;
    notifyListeners();
  }

  void discardConversationRuntime({
    required int conversationId,
    required String mode,
  }) {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime != null) {
      _flushRuntimeStreamingText(runtime);
    }
    _cancelPendingPersistence(conversationId: conversationId, mode: mode);
    _ephemeralRuntimeKeys.remove(
      _runtimeKey(conversationId: conversationId, mode: mode),
    );
    _taskBindings.removeWhere(
      (_, binding) =>
          binding.conversationId == conversationId && binding.mode == mode,
    );
    final removed = _runtimes.remove(
      _runtimeKey(conversationId: conversationId, mode: mode),
    );
    if (removed != null) {
      removed.dispose();
      notifyListeners();
    }
  }

  void interruptActiveToolCard({
    required int conversationId,
    required String mode,
    String? summary,
  }) {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null) return;
    final activeCard = runtime.activeToolCardId == null
        ? null
        : runtime.messages.cast<ChatMessageModel?>().firstWhere(
            (message) => message?.id == runtime.activeToolCardId,
            orElse: () => null,
          );
    final taskId =
        (runtime.activeRunId ??
                runtime.currentDispatchTurnId ??
                activeCard?.cardData?['taskId'] ??
                activeCard?.cardData?['taskID'])
            ?.toString()
            .trim() ??
        '';
    if (taskId.isEmpty) return;

    var changed = false;
    for (var index = 0; index < runtime.messages.length; index++) {
      final message = runtime.messages[index];
      final cardData = message.cardData;
      if (cardData == null ||
          (cardData['type'] != 'agent_tool_summary' &&
              cardData['type'] != kAgentRequestCardType)) {
        continue;
      }
      final cardTaskId = (cardData['taskId'] ?? cardData['taskID'] ?? '')
          .toString()
          .trim();
      if (cardTaskId != taskId) continue;
      final currentStatus = (cardData['status'] ?? '').toString().toLowerCase();
      final isActive = cardData['type'] == 'agent_tool_summary'
          ? const <String>{
              'running',
              'pending',
              'progress',
              'in_progress',
            }.contains(currentStatus)
          : const <String>{
              'pending',
              'running',
              'waiting',
            }.contains(currentStatus);
      if (!isActive) continue;
      final nextCardData = Map<String, dynamic>.from(cardData)
        ..['status'] = 'interrupted'
        ..['success'] = false;
      if (summary != null && summary.trim().isNotEmpty) {
        nextCardData['summary'] = summary.trim();
      }
      runtime.messages[index] = message.copyWith(
        content: {'cardData': nextCardData, 'id': message.id},
      );
      changed = true;
    }
    runtime.activeToolCardId = null;
    if (changed) {
      notifyListeners();
    }
  }

  void beginContextCompaction({
    required int conversationId,
    required String mode,
    String? taskId,
    String trigger = 'manual',
    int? latestPromptTokens,
    int? promptTokenThreshold,
  }) {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null) return;

    _applyPromptTokenUsageUpdate(
      runtime,
      latestPromptTokens: latestPromptTokens,
      promptTokenThreshold: promptTokenThreshold,
    );
    runtime.isContextCompressing = true;
    final activeMarkerId = runtime.activeContextCompactionMarkerId;
    final markerId =
        activeMarkerId != null &&
            runtime.messages.any((message) => message.id == activeMarkerId)
        ? activeMarkerId
        : _buildContextCompactionMarkerId(
            conversationId: conversationId,
            taskId: taskId,
            trigger: trigger,
          );
    runtime.activeContextCompactionMarkerId = markerId;
    _upsertContextCompactionMarker(
      runtime,
      markerId: markerId,
      status: 'compressing',
      trigger: trigger,
      latestPromptTokens: latestPromptTokens,
      promptTokenThreshold: promptTokenThreshold,
    );
    notifyListeners();
    schedulePersistRuntimeConversation(
      conversationId: conversationId,
      mode: mode,
    );
  }

  void finishContextCompaction({
    required int conversationId,
    required String mode,
    String status = 'completed',
    int? latestPromptTokens,
    int? promptTokenThreshold,
  }) {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null) return;

    _applyPromptTokenUsageUpdate(
      runtime,
      latestPromptTokens: latestPromptTokens,
      promptTokenThreshold: promptTokenThreshold,
    );
    runtime.isContextCompressing = false;
    final markerId = runtime.activeContextCompactionMarkerId;
    if (markerId != null) {
      _upsertContextCompactionMarker(
        runtime,
        markerId: markerId,
        status: status,
        latestPromptTokens: latestPromptTokens,
        promptTokenThreshold: promptTokenThreshold,
      );
    }
    runtime.activeContextCompactionMarkerId = null;
    notifyListeners();
    schedulePersistRuntimeConversation(
      conversationId: conversationId,
      mode: mode,
    );
  }

  void updateChatIslandDisplayLayer({
    required int conversationId,
    required String mode,
    required ChatIslandDisplayLayer layer,
  }) {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null || runtime.chatIslandDisplayLayer == layer) {
      return;
    }
    runtime.chatIslandDisplayLayer = layer;
    notifyListeners();
  }
}
