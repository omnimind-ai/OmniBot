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

  /// Accumulated reasoning text. Same contract as [currentAiMessages].
  final Map<String, String> currentThinkingMessages = <String, String>{};
  final Map<String, _StreamingTextBatchState> _streamingTextBatches =
      <String, _StreamingTextBatchState>{};
  final Map<String, int> agentEntrySequences = <String, int>{};
  final Map<String, int> agentEntryStartTimes = <String, int>{};
  final Map<String, int> agentReplayDeltaOffsets = <String, int>{};
  final Set<String> completedAgentTurnIds = <String>{};
  int agentNextEntrySequence = 0;
  bool isAiResponding = false;
  bool isContextCompressing = false;
  bool isCheckingExecutableTask = false;
  String deepThinkingContent = '';
  bool isDeepThinking = false;
  String? currentDispatchTurnId;

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

  /// Turns currently believed to be producing output.
  ///
  /// Every member must be a TURN id. The text caches are deliberately excluded:
  /// their keys are per-message, and mixing the two id spaces is what produced
  /// one agent avatar and one "processing" row per streamed message.
  Set<String> get activeAgentTurnIds {
    final ids = <String>{};
    final currentTaskId = currentDispatchTurnId?.trim() ?? '';
    if (currentTaskId.isNotEmpty) {
      ids.add(currentTaskId);
    }
    final lastTaskId = lastAgentTurnId?.trim() ?? '';
    if (isAiResponding && lastTaskId.isNotEmpty) {
      ids.add(lastTaskId);
    }
    final pendingTaskId = pendingAgentTextTaskId?.trim() ?? '';
    if (pendingTaskId.isNotEmpty) {
      ids.add(pendingTaskId);
    }
    return ids;
  }

  void dispose() {
    _streamingTextBatches.clear();
    agentEntrySequences.clear();
    agentEntryStartTimes.clear();
    agentReplayDeltaOffsets.clear();
    completedAgentTurnIds.clear();
    messages.dispose();
  }

  bool acceptsAcpEvent({String? sessionId, String? turnId}) {
    final incomingSessionId = sessionId?.trim() ?? '';
    if (incomingSessionId.isEmpty) {
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
    final incomingTurnId = turnId?.trim() ?? '';
    final currentTurnId =
        activeAcpTurnId?.trim() ?? currentDispatchTurnId?.trim() ?? '';
    // A different session may only replace an idle runtime. A late event
    // from another session must never mutate the current turn's state.
    if (currentTurnId.isNotEmpty) {
      return false;
    }
    // Once the old runtime is idle, the first event from the new session
    // becomes the new lifecycle owner. Without rebinding here, every later
    // event from that session would look like a competing stale session.
    activeAcpSessionId = incomingSessionId;
    return true;
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
    final normalizedMessages = _dedupeEquivalentAgentUserMessages(messages);
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
    // A snapshot carries only a render hint. The official ACP turn identity
    // must be admitted by `turn/started`/`session/update`, never guessed from
    // a local placeholder id.
    runtime.activeAcpTurnId = null;
    if (!hadInFlightTask) {
      runtime.activeAcpSessionId = null;
    }
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
    _pruneAgentReplayDeltaOffsets(runtime, messages);
    runtime.agentNextEntrySequence = 0;
    notifyListeners();
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
    }
    _taskBindings[taskId] = _TaskBinding(
      conversationId: conversationId,
      mode: mode,
    );
  }

  void primePureChatThinking({
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

    runtime.lastAgentTurnId = taskId;
    runtime.currentThinkingStage = ThinkingStage.thinking.value;
    runtime.isDeepThinking = true;

    if (runtime.thinkingRound == 0) {
      runtime.thinkingRound = 1;
      runtime.activeThinkingCardId = _baseThinkingCardId(taskId);
      final exists = runtime.messages.any(
        (msg) => msg.id == runtime.activeThinkingCardId,
      );
      if (exists) {
        _updateThinkingCard(
          runtime,
          taskId,
          cardId: runtime.activeThinkingCardId,
          isLoading: true,
          stage: ThinkingStage.thinking.value,
          lockCompleted: false,
        );
      } else {
        _createThinkingCard(
          runtime,
          taskId,
          cardId: runtime.activeThinkingCardId,
          isLoading: true,
          stage: ThinkingStage.thinking.value,
        );
      }
      notifyListeners();
      schedulePersistRuntimeConversation(
        conversationId: conversationId,
        mode: mode,
      );
      return;
    }

    _flushThinkingBatch(
      runtime,
      taskId,
      _StreamingTextStreamKind.pureChatThinking,
    );
    runtime.pendingThinkingRoundSplit = true;
    notifyListeners();
    schedulePersistRuntimeConversation(
      conversationId: conversationId,
      mode: mode,
    );
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
      }
      _flushStreamingTextForTask(runtime, taskId);
      _clearStreamingTextBatchesForTask(runtime, taskId);
      runtime.currentAiMessages.remove(taskId);
      runtime.currentThinkingMessages.remove(taskId);
      if (runtime.currentDispatchTurnId == taskId) {
        runtime.currentDispatchTurnId = null;
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
      runtime.completedAgentTurnIds.remove(
        runtime.completedAgentTurnIds.first,
      );
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
    final params = event['params'];
    final eventParams = params is Map ? params : const <String, dynamic>{};
    final eventSessionId = _runtimeEventString(
      event['sessionId'] ?? eventParams['sessionId'],
    );
    final eventTurnId = _runtimeEventString(
      event['turnId'] ?? eventParams['turnId'],
    );
    if (!runtime.acceptsAcpEvent(
      sessionId: eventSessionId,
      turnId: eventTurnId,
    )) {
      return const AgentReduceResult(handled: false);
    }
    final result = _agentEventReducer.reduce(runtime: runtime, event: event);
    if (result.handled) {
      _annotateAgentMessages(runtime, event, result);
      notifyListeners();
      if (!isEphemeralRuntime(conversationId: conversationId, mode: mode)) {
        schedulePersistRuntimeConversation(
          conversationId: conversationId,
          mode: kChatRuntimeModeAgent,
          persistMessages: true,
        );
      }
    }
    return result;
  }

  String? _runtimeEventString(dynamic value) {
    final normalized = value?.toString().trim() ?? '';
    return normalized.isEmpty ? null : normalized;
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
    final taskId =
        result.turnId ??
        stringValue(event['turnId']) ??
        stringValue(params?['turnId']);

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
            cardData?['taskId'] ??
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
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null) return;

    _flushThinkingBatch(
      runtime,
      taskId,
      _StreamingTextStreamKind.pureChatThinking,
    );
    runtime.currentThinkingMessages.remove(taskId);
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    runtime.lastAgentTurnId = null;
    runtime.activeThinkingCardId = null;
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
    _flushRuntimeStreamingText(runtime);
    runtime.currentDispatchTurnId = null;
    runtime.activeAcpTurnId = null;
    runtime.activeAcpSessionId = null;
    runtime.isAiResponding = false;
    runtime.isExecutingTask = false;
    runtime.isCheckingExecutableTask = false;
    runtime.isContextCompressing = false;
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    runtime.currentThinkingMessages.clear();
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
    final cardId = runtime.activeToolCardId;
    if (cardId == null) return;

    final index = runtime.messages.indexWhere((msg) => msg.id == cardId);
    if (index == -1) {
      runtime.activeToolCardId = null;
      notifyListeners();
      return;
    }

    final existingCardData = Map<String, dynamic>.from(
      runtime.messages[index].cardData ?? const {},
    );
    existingCardData['status'] = 'interrupted';
    existingCardData['success'] = false;
    if (summary != null && summary.trim().isNotEmpty) {
      existingCardData['summary'] = summary.trim();
    }
    runtime.messages[index] = runtime.messages[index].copyWith(
      content: {'cardData': existingCardData, 'id': cardId},
    );
    runtime.activeToolCardId = null;
    notifyListeners();
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
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null) return;
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
    runtime.conversation = updatedConversation;
    if (markComplete) {
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
