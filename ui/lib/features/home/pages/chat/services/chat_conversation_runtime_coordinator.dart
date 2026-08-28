import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/authorize/authorize_page_args.dart';
import 'package:ui/features/home/pages/chat/utils/stream_text_merge.dart';
import 'package:ui/features/home/pages/command_overlay/constants/messages.dart';
import 'package:ui/models/chat_link_preview.dart';
import 'package:ui/features/home/pages/chat/utils/deep_thinking_persistence.dart';
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
import 'package:ui/utils/data_parser.dart';
import 'package:ui/services/agent_diff_parser.dart';

part 'chat_runtime_internal_support.dart';
part 'chat_runtime_external_message_support.dart';
part 'chat_runtime_message_support.dart';
part 'chat_runtime_streaming_support.dart';
part 'chat_runtime_thinking_support.dart';
part 'chat_runtime_tool_support.dart';

const String kChatRuntimeModeNormal = 'normal';
const String kChatRuntimeModeOpenClaw = 'openclaw';
const String kChatRuntimeModeAgent = 'agent';
const int _kStreamingTextChunkFlushThreshold = 5;

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
  String? currentDispatchTurnId;

  /// Stable UI ownership key. Unlike [activeAcpTurnId], this never changes
  /// when the provider admits its official turn id.
  String? activeRunId;

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
    final runId = (activeRunId ?? currentDispatchTurnId)?.trim() ?? '';
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
      currentDispatchTurnId != null ||
      currentAiMessages.isNotEmpty;

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
    final hasLiveWork =
        isAiResponding ||
        isCheckingExecutableTask ||
        isExecutingTask ||
        currentAiMessages.isNotEmpty ||
        currentThinkingMessages.isNotEmpty;
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
    activeRunId = normalizedFallback;
    if (key.isNotEmpty) _rememberAcpTurnRun(key, normalizedFallback);
    return normalizedFallback;
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
    if (activeRunId == normalized) return activeAcpTurnId;
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
  }) {
    final incomingSessionId = sessionId?.trim() ?? '';
    if (incomingSessionId.isEmpty) {
      return true;
    }
    final incomingTurnId = turnId?.trim() ?? '';
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
    knownAcpSessionIds.add(incomingSessionId);
    while (knownAcpSessionIds.length > 32) {
      knownAcpSessionIds.remove(knownAcpSessionIds.first);
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
    final currentSessionId = activeAcpSessionId?.trim() ?? '';
    if (currentSessionId.isEmpty) {
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
    // Once the old runtime is idle, the first event from the new session
    // becomes the new lifecycle owner. Without rebinding here, every later
    // event from that session would look like a competing stale session.
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

const int _maxTerminalOutputChars = 64 * 1024;
const int _maxTerminalOutputLines = 600;
const Map<String, String> _executionPermissionNameToId = <String, String>{
  '悬浮窗权限': kOverlayPermissionId,
  'Overlay': kOverlayPermissionId,
  '应用列表读取权限': kInstalledAppsPermissionId,
  'Installed Apps Access': kInstalledAppsPermissionId,
  'Shizuku 权限': kShizukuPermissionId,
  'Shizuku Permission': kShizukuPermissionId,
  '公共文件访问': kPublicStoragePermissionId,
  'Public Storage Access': kPublicStoragePermissionId,
};

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

  void replaceConversationSnapshot({
    required int conversationId,
    required String mode,
    required List<ChatMessageModel> messages,
    ConversationModel? conversation,
    bool isAiResponding = false,
    bool isContextCompressing = false,
    bool isCheckingExecutableTask = false,
    Map<String, String>? currentAiMessages,
    Map<String, String>? currentThinkingMessages,
    String deepThinkingContent = '',
    bool isDeepThinking = false,
    String? currentDispatchTurnId,
    int currentThinkingStage = 1,
    bool isInputAreaVisible = true,
    bool isExecutingTask = false,
    String? lastAgentTurnId,
    String? activeToolCardId,
    String? activeThinkingCardId,
    String? activeContextCompactionMarkerId,
    String? pendingAgentTextTaskId,
    bool pendingThinkingRoundSplit = false,
    int toolCardSequence = 0,
    int thinkingRound = 0,
    ChatIslandDisplayLayer chatIslandDisplayLayer = ChatIslandDisplayLayer.mode,
    String? lastAgentToolType,
    ChatBrowserSessionSnapshot? browserSessionSnapshot,
    bool preserveLiveStreamingState = false,
  }) {
    final normalizedMessages = _normalizeIdleThinkingCards(
      _dedupeEquivalentAgentUserMessages(messages),
      isAiResponding: isAiResponding,
      preserveLiveStreamingState: preserveLiveStreamingState,
    );
    final runtime = ensureRuntime(
      conversationId: conversationId,
      mode: mode,
      conversation: conversation,
    );
    // When the caller is polling a remote codex thread while reducer push
    // events are still actively streaming into this runtime, we MUST NOT
    // blow away the push-driven streaming state. Otherwise the chat list
    // collapses for a single frame between each poll tick — the symptom
    // the user calls "codex 输出时自动折叠了一下又展开"。
    //
    // In that mode we only refresh the visible message list and conversation
    // metadata; everything else (isAiResponding, currentAiMessages,
    // currentThinkingMessages, currentDispatchTurnId, …) stays exactly as
    // the reducer left it.
    if (preserveLiveStreamingState) {
      _replaceRuntimeMessagesIfChanged(runtime, normalizedMessages);
      runtime.conversation = conversation ?? runtime.conversation;
      _pruneAgentReplayDeltaOffsets(runtime, normalizedMessages);
      notifyListeners();
      return;
    }
    final hadInFlightTask = runtime.hasInFlightTask;
    _flushRuntimeStreamingText(runtime);
    _replaceRuntimeMessagesIfChanged(runtime, normalizedMessages);
    runtime.conversation = conversation ?? runtime.conversation;
    runtime.isAiResponding = isAiResponding;
    runtime.isContextCompressing = isContextCompressing;
    runtime.isCheckingExecutableTask = isCheckingExecutableTask;
    runtime.currentAiMessages
      ..clear()
      ..addAll(currentAiMessages ?? const <String, String>{});
    runtime.currentThinkingMessages
      ..clear()
      ..addAll(currentThinkingMessages ?? const <String, String>{});
    runtime.deepThinkingContent = deepThinkingContent;
    runtime.isDeepThinking = isDeepThinking;
    runtime.currentDispatchTurnId = currentDispatchTurnId;
    final snapshotRunId = normalizedMessages
        .map((message) => message.runId)
        .whereType<String>()
        .map((value) => value.trim())
        .firstWhere((value) => value.isNotEmpty, orElse: () => '');
    final snapshotHasLiveWork =
        isAiResponding ||
        isCheckingExecutableTask ||
        isExecutingTask ||
        (currentAiMessages?.isNotEmpty ?? false) ||
        (currentThinkingMessages?.isNotEmpty ?? false);
    runtime.activeRunId = snapshotHasLiveWork
        ? (currentDispatchTurnId?.trim().isNotEmpty == true
              ? currentDispatchTurnId
              : (snapshotRunId.isEmpty ? null : snapshotRunId))
        : null;
    // A snapshot carries only a render hint. The official ACP turn identity
    // must be admitted by `turn/started`/`session/update`, never guessed from
    // a local placeholder id.
    runtime.activeAcpTurnId = null;
    // An idle persisted snapshot is authoritative during restore. Do not
    // carry the previous session into a completed conversation merely because
    // the runtime still had a stale dispatch id before this replacement.
    runtime.activeAcpSessionId = snapshotHasLiveWork && hadInFlightTask
        ? runtime.activeAcpSessionId
        : null;
    runtime.currentThinkingStage = currentThinkingStage;
    runtime.isInputAreaVisible = isInputAreaVisible;
    runtime.isExecutingTask = isExecutingTask;
    runtime.lastAgentTurnId = lastAgentTurnId;
    runtime.activeToolCardId = activeToolCardId;
    runtime.activeThinkingCardId = activeThinkingCardId;
    runtime.activeContextCompactionMarkerId = activeContextCompactionMarkerId;
    runtime.pendingAgentTextTaskId = pendingAgentTextTaskId;
    runtime.waitingThinkingBeforeAgentTextTaskId = null;
    runtime.pendingThinkingRoundSplit = pendingThinkingRoundSplit;
    runtime.toolCardSequence = toolCardSequence;
    runtime.thinkingRound = thinkingRound;
    runtime.chatIslandDisplayLayer = chatIslandDisplayLayer;
    runtime.lastAgentToolType = lastAgentToolType;
    runtime.browserSessionSnapshot = browserSessionSnapshot;
    runtime._streamingTextBatches.clear();
    runtime.agentEntrySequences.clear();
    runtime.agentEntryStartTimes.clear();
    _pruneAgentReplayDeltaOffsets(runtime, normalizedMessages);
    runtime.agentNextEntrySequence = 0;
    notifyListeners();
  }

  /// A persisted snapshot can outlive the terminal ACP event (for example if
  /// the app was backgrounded during the final frame). Never resurrect its
  /// pre-created thinking spinner when the runtime is already idle.
  List<ChatMessageModel> _normalizeIdleThinkingCards(
    List<ChatMessageModel> messages, {
    required bool isAiResponding,
    required bool preserveLiveStreamingState,
  }) {
    if (isAiResponding || preserveLiveStreamingState) {
      return messages;
    }
    final now = DateTime.now().millisecondsSinceEpoch;
    return messages
        .map((message) {
          final existingCardData = message.cardData;
          if (message.type != 2 ||
              existingCardData?['type'] != 'deep_thinking' ||
              existingCardData?['isLoading'] != true) {
            return message;
          }
          final cardData = Map<String, dynamic>.from(existingCardData!);
          cardData['isLoading'] = false;
          cardData['stage'] = ThinkingStage.complete.value;
          cardData['endTime'] ??= now;
          cardData['isCollapsible'] = true;
          return message.copyWith(
            content: <String, dynamic>{'cardData': cardData, 'id': message.id},
          );
        })
        .toList(growable: false);
  }

  void _replaceRuntimeMessagesIfChanged(
    ChatConversationRuntimeState runtime,
    List<ChatMessageModel> messages,
  ) {
    final current = runtime.messages;
    if (current.length == messages.length) {
      var sameInstancesInOrder = true;
      for (var index = 0; index < messages.length; index += 1) {
        if (!identical(current[index], messages[index])) {
          sameInstancesInOrder = false;
          break;
        }
      }
      if (sameInstancesInOrder) {
        return;
      }
    }
    current.replaceAllMessages(messages);
  }

  void _pruneAgentReplayDeltaOffsets(
    ChatConversationRuntimeState runtime,
    List<ChatMessageModel> messages,
  ) {
    if (runtime.agentReplayDeltaOffsets.isEmpty) {
      return;
    }
    final liveEntryIds = <String>{};
    for (final message in messages) {
      liveEntryIds.add(message.id);
      final entryId = message.streamMeta?['entryId']?.toString().trim();
      if (entryId != null && entryId.isNotEmpty) {
        liveEntryIds.add(entryId);
      }
      final cardId = message.cardData?['cardId']?.toString().trim();
      if (cardId != null && cardId.isNotEmpty) {
        liveEntryIds.add(cardId);
      }
    }
    runtime.agentReplayDeltaOffsets.removeWhere(
      (entryId, _) => !liveEntryIds.contains(entryId),
    );
  }

  void registerTask({
    required String taskId,
    required int conversationId,
    required String mode,
  }) {
    ensureInitialized();
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

  /// Marks an ACP turn as locally active without creating a visible thinking
  /// card. The old implementation eagerly inserted an empty
  /// `deep_thinking` card here, which made every execution start with a
  /// misleading "正在思考" popup before ACP had emitted any reasoning.
  ///
  /// Actual reasoning/item/tool/text events still create their cards through
  /// the reducer. Keeping the active-turn bookkeeping here is important for
  /// adapters that do not emit a synthetic `turn/started` notification: their
  /// first turn-scoped event still needs to be admitted to this local turn.
  void beginAcpTurn({
    required String taskId,
    required int conversationId,
    required String mode,
  }) {
    ensureInitialized();
    final runtime = ensureRuntime(conversationId: conversationId, mode: mode);
    _taskBindings[taskId] = _TaskBinding(
      conversationId: conversationId,
      mode: mode,
    );

    runtime.persistenceGeneration += 1;
    runtime.isAiResponding = true;
    runtime.currentDispatchTurnId = taskId;
    runtime.activeRunId = taskId;
    runtime.lastAgentTurnId = taskId;
    runtime.currentThinkingStage = ThinkingStage.thinking.value;
    runtime.allowRetiredAcpSessionReactivation = true;
    notifyListeners();
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

  void unregisterTask(String taskId) {
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
      if (runtime.activeAcpTurnId == taskId) {
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
        runtime.deepThinkingContent = '';
        runtime.isDeepThinking = false;
        runtime.activeToolCardId = null;
        runtime.activeThinkingCardId = null;
        runtime.pendingAgentTextTaskId = null;
        runtime.waitingThinkingBeforeAgentTextTaskId = null;
        runtime.pendingThinkingRoundSplit = false;
      }
    }
    _taskBindings.remove(taskId);
  }

  void _rememberCompletedTurn(
    ChatConversationRuntimeState runtime,
    String turnId,
  ) {
    final normalized = turnId.trim();
    if (normalized.isEmpty) return;
    runtime.completedAgentTurnIds.add(normalized);
    while (runtime.completedAgentTurnIds.length > 128) {
      runtime.completedAgentTurnIds.remove(runtime.completedAgentTurnIds.first);
    }
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
    if (!runtime.acceptsAcpEvent(
      sessionId: eventSessionId,
      turnId: eventTurnId,
      allowCompletedTurnMetadata: carriesFinalTurnUsage,
    )) {
      return const AgentReduceResult(handled: false);
    }
    final result = _agentEventReducer.reduce(runtime: runtime, event: event);
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
    final taskId = runtime.resolveRunId(
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
    final taskId = runtime.resolveRunId(
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
    if (runtime == null) return;

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
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    if (runtime.lastAgentTurnId == taskId) {
      runtime.lastAgentTurnId = null;
    }
    if (runtime.activeThinkingCardId != null &&
        (runtime.activeThinkingCardId == taskId ||
            runtime.activeThinkingCardId!.startsWith('$taskId-thinking'))) {
      runtime.activeThinkingCardId = null;
    }
    runtime.pendingThinkingRoundSplit = false;
    runtime.thinkingRound = 0;
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
    final sessionsToRetire = <String>{
      ...runtime.knownAcpSessionIds,
      if (runtime.activeAcpSessionId?.trim().isNotEmpty == true)
        runtime.activeAcpSessionId!.trim(),
    };
    runtime.retiredAcpSessionIds.addAll(sessionsToRetire);
    while (runtime.retiredAcpSessionIds.length > 64) {
      runtime.retiredAcpSessionIds.remove(runtime.retiredAcpSessionIds.first);
    }
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

  Future<void> persistRuntimeConversation({
    required int conversationId,
    required String mode,
    bool generateSummary = false,
    bool markComplete = false,
    bool persistMessages = false,
  }) async {
    _cancelPendingPersistence(conversationId: conversationId, mode: mode);
    if (isEphemeralRuntime(conversationId: conversationId, mode: mode)) {
      return;
    }
    final key = _runtimeKey(conversationId: conversationId, mode: mode);
    final previous = _persistenceTails[key] ?? Future<void>.value();
    final operation = previous
        .catchError((Object _) {})
        .then(
          (_) => _persistRuntimeConversationNow(
            conversationId: conversationId,
            mode: mode,
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
    bool generateSummary = false,
    bool markComplete = false,
    bool persistMessages = false,
  }) async {
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null) return;
    final persistenceGeneration = runtime.persistenceGeneration;
    _flushRuntimeStreamingText(runtime);
    if (runtime.messages.isEmpty) return;

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

  void beginContextCompaction({
    required int conversationId,
    required String mode,
    String? taskId,
    String trigger = 'auto',
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
