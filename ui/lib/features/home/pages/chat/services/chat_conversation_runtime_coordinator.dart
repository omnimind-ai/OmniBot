import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/authorize/authorize_page_args.dart';
import 'package:ui/features/home/pages/chat/utils/stream_text_merge.dart';
import 'package:ui/features/home/pages/command_overlay/constants/messages.dart';
import 'package:ui/features/home/pages/chat/mixins/agent_stream_handler.dart';
import 'package:ui/models/agent_stream_event.dart';
import 'package:ui/models/chat_link_preview.dart';
import 'package:ui/features/home/pages/chat/utils/deep_thinking_persistence.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/agent_stream_reducer.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/codex_event_reducer.dart';
import 'package:ui/services/conversation_history_service.dart';
import 'package:ui/services/conversation_service.dart';
import 'package:ui/services/link_preview_service.dart';
import 'package:ui/services/voice_playback_coordinator.dart';
import 'package:ui/services/agent_stream_meta.dart';
import 'package:ui/utils/data_parser.dart';
import 'package:ui/services/codex_diff_parser.dart';

const String kChatRuntimeModeNormal = 'normal';
const String kChatRuntimeModeOpenClaw = 'openclaw';
const String kChatRuntimeModeCodex = 'codex';
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
  ChatConversationRuntimeState({
    required this.conversationId,
    required this.mode,
  }) : chatIslandDisplayLayer = ChatIslandDisplayLayer.mode;

  final int conversationId;
  final String mode;

  ConversationModel? conversation;
  final ObservableChatMessageList messages = ObservableChatMessageList();
  final Map<String, String> currentAiMessages = <String, String>{};
  final Map<String, String> currentThinkingMessages = <String, String>{};
  final Map<String, AgentStreamTaskState> agentStreamStates =
      <String, AgentStreamTaskState>{};
  final Map<String, _StreamingTextBatchState> _streamingTextBatches =
      <String, _StreamingTextBatchState>{};
  final Map<String, int> codexEntrySequences = <String, int>{};
  final Map<String, int> codexEntryStartTimes = <String, int>{};
  final Map<String, int> codexReplayDeltaOffsets = <String, int>{};
  int codexNextEntrySequence = 0;
  bool isAiResponding = false;
  bool isContextCompressing = false;
  bool isCheckingExecutableTask = false;
  bool isSubmittingVlmReply = false;
  String? vlmInfoQuestion;
  String deepThinkingContent = '';
  bool isDeepThinking = false;
  String? currentDispatchTaskId;
  int currentThinkingStage = 1;
  bool isInputAreaVisible = true;
  bool isExecutingTask = false;

  String? lastAgentTaskId;
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

  bool get hasInFlightTask =>
      isAiResponding ||
      isCheckingExecutableTask ||
      isExecutingTask ||
      currentDispatchTaskId != null ||
      currentAiMessages.isNotEmpty;

  Set<String> get activeAgentTaskIds {
    final ids = <String>{
      ...agentStreamStates.keys,
      ...currentAiMessages.keys,
      ...currentThinkingMessages.keys,
    };
    final currentTaskId = currentDispatchTaskId?.trim() ?? '';
    if (currentTaskId.isNotEmpty) {
      ids.add(currentTaskId);
    }
    final lastTaskId = lastAgentTaskId?.trim() ?? '';
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
    agentStreamStates.clear();
    _streamingTextBatches.clear();
    codexEntrySequences.clear();
    codexEntryStartTimes.clear();
    codexReplayDeltaOffsets.clear();
    messages.dispose();
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

class ChatConversationRuntimeCoordinator extends ChangeNotifier {
  ChatConversationRuntimeCoordinator._();

  static final ChatConversationRuntimeCoordinator instance =
      ChatConversationRuntimeCoordinator._();

  static const int _maxTerminalOutputChars = 64 * 1024;
  static const int _maxTerminalOutputLines = 600;
  static const Map<String, String> _executionPermissionNameToId =
      <String, String>{
        '无障碍权限': kAccessibilityPermissionId,
        'Accessibility': kAccessibilityPermissionId,
        '悬浮窗权限': kOverlayPermissionId,
        'Overlay': kOverlayPermissionId,
        '应用列表读取权限': kInstalledAppsPermissionId,
        'Installed Apps Access': kInstalledAppsPermissionId,
        'Shizuku 权限': kShizukuPermissionId,
        'Shizuku Permission': kShizukuPermissionId,
        '公共文件访问': kPublicStoragePermissionId,
        'Public Storage Access': kPublicStoragePermissionId,
      };

  String _agentTextBaseId(String taskId) => '$taskId-text';

  final AgentStreamReducer _agentStreamReducer = const AgentStreamReducer();
  final CodexEventReducer _codexEventReducer = const CodexEventReducer();
  final Map<String, ChatConversationRuntimeState> _runtimes =
      <String, ChatConversationRuntimeState>{};
  final Map<String, _TaskBinding> _taskBindings = <String, _TaskBinding>{};
  final Map<String, _PendingPersistenceRequest> _pendingPersistence =
      <String, _PendingPersistenceRequest>{};
  final Set<String> _ephemeralRuntimeKeys = <String>{};

  bool _initialized = false;

  bool get _isEnglish => LegacyTextLocalizer.isEnglish;

  void ensureInitialized() {
    if (_initialized) return;
    _initialized = true;
    unawaited(VoicePlaybackCoordinator.instance.ensureInitialized());

    AssistsMessageService.initialize();
    AssistsMessageService.addOnChatTaskMessageCallBack(_handleChatTaskMessage);
    AssistsMessageService.addOnChatTaskMessageEndCallBack(
      _handleChatTaskMessageEnd,
    );
    AssistsMessageService.setOnAgentStreamEventCallback(
      _handleAgentStreamEvent,
    );
    AssistsMessageService.addOnExternalUserMessageAppendedCallback(
      _handleExternalUserMessageAppended,
    );
    AssistsMessageService.setOnAgentPromptTokenUsageCallback(
      _handleAgentPromptTokenUsageChanged,
    );
    AssistsMessageService.setOnAgentContextCompactionStateCallback(
      _handleAgentContextCompactionStateChanged,
    );
    AssistsMessageService.setOnVLMRequestUserInputCallBack(
      _handleVlmRequestUserInput,
    );
    AssistsMessageService.setOnVLMTaskFinishCallBack(_handleVlmTaskFinish);
  }

  ChatConversationRuntimeState? runtimeFor({
    required int conversationId,
    required String mode,
  }) {
    return _runtimes[_runtimeKey(conversationId: conversationId, mode: mode)];
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
      runtime.messages.addAll(initialMessages);
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
    bool isSubmittingVlmReply = false,
    String? vlmInfoQuestion,
    Map<String, String>? currentAiMessages,
    Map<String, String>? currentThinkingMessages,
    String deepThinkingContent = '',
    bool isDeepThinking = false,
    String? currentDispatchTaskId,
    int currentThinkingStage = 1,
    bool isInputAreaVisible = true,
    bool isExecutingTask = false,
    String? lastAgentTaskId,
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
    // currentThinkingMessages, currentDispatchTaskId, …) stays exactly as
    // the reducer left it.
    if (preserveLiveStreamingState) {
      runtime.messages.replaceAllMessages(messages);
      runtime.conversation = conversation ?? runtime.conversation;
      _pruneCodexReplayDeltaOffsets(runtime, messages);
      notifyListeners();
      return;
    }
    _flushRuntimeStreamingText(runtime);
    runtime.messages.replaceAllMessages(messages);
    runtime.conversation = conversation ?? runtime.conversation;
    runtime.isAiResponding = isAiResponding;
    runtime.isContextCompressing = isContextCompressing;
    runtime.isCheckingExecutableTask = isCheckingExecutableTask;
    runtime.isSubmittingVlmReply = isSubmittingVlmReply;
    runtime.vlmInfoQuestion = vlmInfoQuestion;
    runtime.currentAiMessages
      ..clear()
      ..addAll(currentAiMessages ?? const <String, String>{});
    runtime.currentThinkingMessages
      ..clear()
      ..addAll(currentThinkingMessages ?? const <String, String>{});
    runtime.deepThinkingContent = deepThinkingContent;
    runtime.isDeepThinking = isDeepThinking;
    runtime.currentDispatchTaskId = currentDispatchTaskId;
    runtime.currentThinkingStage = currentThinkingStage;
    runtime.isInputAreaVisible = isInputAreaVisible;
    runtime.isExecutingTask = isExecutingTask;
    runtime.lastAgentTaskId = lastAgentTaskId;
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
    runtime.agentStreamStates.clear();
    runtime._streamingTextBatches.clear();
    runtime.codexEntrySequences.clear();
    runtime.codexEntryStartTimes.clear();
    _pruneCodexReplayDeltaOffsets(runtime, messages);
    runtime.codexNextEntrySequence = 0;
    notifyListeners();
  }

  void _pruneCodexReplayDeltaOffsets(
    ChatConversationRuntimeState runtime,
    List<ChatMessageModel> messages,
  ) {
    if (runtime.codexReplayDeltaOffsets.isEmpty) {
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
    runtime.codexReplayDeltaOffsets.removeWhere(
      (entryId, _) => !liveEntryIds.contains(entryId),
    );
  }

  void registerTask({
    required String taskId,
    required int conversationId,
    required String mode,
  }) {
    ensureInitialized();
    ensureRuntime(conversationId: conversationId, mode: mode);
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

    runtime.lastAgentTaskId = taskId;
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
      _flushStreamingTextForTask(runtime, taskId);
      _clearStreamingTextBatchesForTask(runtime, taskId);
      runtime.agentStreamStates.remove(taskId);
      runtime.currentAiMessages.remove(taskId);
      runtime.currentThinkingMessages.remove(taskId);
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
      if (runtime.currentDispatchTaskId == taskId) {
        runtime.currentDispatchTaskId = null;
      }
      if (runtime.lastAgentTaskId == taskId) {
        runtime.lastAgentTaskId = null;
      }
    }
    _taskBindings.remove(taskId);
  }

  CodexReduceResult applyCodexEvent({
    required int conversationId,
    required Map<String, dynamic> event,
    ConversationModel? conversation,
  }) {
    ensureInitialized();
    final runtime = ensureRuntime(
      conversationId: conversationId,
      mode: kChatRuntimeModeCodex,
      conversation: conversation,
      initialChatIslandDisplayLayer: ChatIslandDisplayLayer.mode,
    );
    final result = _codexEventReducer.reduce(runtime: runtime, event: event);
    if (result.handled) {
      notifyListeners();
      if (!isEphemeralRuntime(
        conversationId: conversationId,
        mode: kChatRuntimeModeCodex,
      )) {
        schedulePersistRuntimeConversation(
          conversationId: conversationId,
          mode: kChatRuntimeModeCodex,
          persistMessages: true,
        );
      }
    }
    return result;
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
    runtime.lastAgentTaskId = null;
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
    runtime.currentDispatchTaskId = null;
    runtime.isContextCompressing = false;
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    runtime.currentThinkingMessages.clear();
    runtime.currentThinkingStage = ThinkingStage.thinking.value;
    runtime.lastAgentTaskId = null;
    runtime.pendingAgentTextTaskId = null;
    runtime.waitingThinkingBeforeAgentTextTaskId = null;
    runtime.activeToolCardId = null;
    runtime.activeThinkingCardId = null;
    runtime.activeContextCompactionMarkerId = null;
    runtime.agentStreamStates.clear();
    runtime.pendingThinkingRoundSplit = false;
    runtime.toolCardSequence = 0;
    runtime.thinkingRound = 0;
    runtime._streamingTextBatches.clear();
    runtime.codexEntrySequences.clear();
    runtime.codexEntryStartTimes.clear();
    runtime.codexNextEntrySequence = 0;
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

    final latestConversation = await ConversationService.getConversationById(
      conversationId,
      mode: conversationMode,
      includeArchived: true,
    );
    final snapshotBase = snapshotConversation?.mode == conversationMode
        ? snapshotConversation
        : snapshotConversation?.copyWith(mode: conversationMode);
    final baseConversation =
        latestConversation ??
        snapshotBase ??
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

    await ConversationService.updateConversation(updatedConversation);
    if (persistMessages) {
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

  String _streamingTextBatchKey(String taskId, _StreamingTextStreamKind kind) =>
      '${kind.name}:$taskId';

  _StreamingTextBatchState? _streamingTextBatchFor(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind,
  ) {
    return runtime._streamingTextBatches[_streamingTextBatchKey(taskId, kind)];
  }

  _StreamingTextBatchState _ensureStreamingTextBatch(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind, {
    required String initialLatestText,
    required String initialFlushedText,
  }) {
    final key = _streamingTextBatchKey(taskId, kind);
    return runtime._streamingTextBatches.putIfAbsent(
      key,
      () => _StreamingTextBatchState(
        taskId: taskId,
        kind: kind,
        latestText: initialLatestText,
        lastFlushedText: initialFlushedText,
      ),
    );
  }

  void _clearStreamingTextBatch(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind,
  ) {
    runtime._streamingTextBatches.remove(_streamingTextBatchKey(taskId, kind));
  }

  void _clearStreamingTextBatchesForTask(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    runtime._streamingTextBatches.removeWhere(
      (_, batch) => batch.taskId == taskId,
    );
  }

  void _flushRuntimeStreamingText(
    ChatConversationRuntimeState runtime, {
    bool emitVoiceUpdates = false,
    bool schedulePersistence = false,
  }) {
    final taskIds = runtime._streamingTextBatches.values
        .map((batch) => batch.taskId)
        .toSet()
        .toList(growable: false);
    for (final taskId in taskIds) {
      _flushStreamingTextForTask(
        runtime,
        taskId,
        emitVoiceUpdates: emitVoiceUpdates,
        schedulePersistence: schedulePersistence,
      );
    }
  }

  void _flushStreamingTextForTask(
    ChatConversationRuntimeState runtime,
    String taskId, {
    bool emitVoiceUpdates = false,
    bool schedulePersistence = false,
  }) {
    _flushThinkingBatch(
      runtime,
      taskId,
      _StreamingTextStreamKind.pureChatThinking,
      schedulePersistence: schedulePersistence,
    );
    _flushThinkingBatch(
      runtime,
      taskId,
      _StreamingTextStreamKind.agentThinking,
      schedulePersistence: schedulePersistence,
    );
    _flushPureChatReplyBatch(
      runtime,
      taskId,
      emitVoiceUpdate: emitVoiceUpdates,
      schedulePersistence: schedulePersistence,
    );
    _flushAgentReplyBatch(
      runtime,
      taskId,
      emitVoiceEvent: emitVoiceUpdates,
      schedulePersistence: schedulePersistence,
    );
  }

  bool _stageStreamingTextBatch(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind, {
    required String nextText,
    required String initialLatestText,
    required String initialFlushedText,
  }) {
    if (nextText.isEmpty) {
      return false;
    }
    final state = _ensureStreamingTextBatch(
      runtime,
      taskId,
      kind,
      initialLatestText: initialLatestText,
      initialFlushedText: initialFlushedText,
    );
    if (nextText == state.latestText) {
      return state.reachedFlushThreshold;
    }
    state.stage(nextText);
    return state.reachedFlushThreshold || state.containsNewlineSinceFlush;
  }

  String _visiblePureChatReplyText(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    final index = runtime.messages.indexWhere(
      (message) => message.id == taskId,
    );
    if (index == -1) {
      return '';
    }
    return (runtime.messages[index].content?['text'] as String? ?? '');
  }

  String? _latestAgentTextMessageId(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    String? result;
    var maxSequence = 0;
    for (final message in runtime.messages) {
      final sequence = _agentTextMessageSequence(message.id, taskId);
      if (sequence <= maxSequence) {
        continue;
      }
      maxSequence = sequence;
      result = message.id;
    }
    return result;
  }

  String _visibleAgentReplyText(
    ChatConversationRuntimeState runtime,
    String taskId, {
    String? messageId,
  }) {
    final resolvedMessageId =
        messageId ??
        _resolvePendingAgentTextMessageId(runtime, taskId) ??
        _latestAgentTextMessageId(runtime, taskId);
    if (resolvedMessageId == null) {
      return '';
    }
    final index = runtime.messages.indexWhere(
      (message) => message.id == resolvedMessageId,
    );
    if (index == -1) {
      return '';
    }
    return (runtime.messages[index].content?['text'] as String? ?? '');
  }

  String _visibleThinkingText(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    final thinkingCardId = _resolveThinkingCardId(runtime, taskId);
    if (thinkingCardId == null) {
      return runtime.deepThinkingContent;
    }
    final index = runtime.messages.indexWhere(
      (message) => message.id == thinkingCardId,
    );
    if (index == -1) {
      return runtime.deepThinkingContent;
    }
    return (runtime.messages[index].cardData?['thinkingContent'] as String? ??
            runtime.deepThinkingContent)
        .toString();
  }

  /// 返回已完成 Markdown 渲染的文本长度。
  ///
  /// - 无待刷新数据时返回 `null`（表示全量 Markdown 渲染）
  /// - 有待刷新数据时返回上次 flush 的文本长度，前端据此分段渲染
  int? _markdownRenderedLengthForBatch(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind,
  ) {
    final batch = _streamingTextBatchFor(runtime, taskId, kind);
    if (batch == null || !batch.hasPendingFlush) {
      return null;
    }
    return batch.lastFlushedText.length;
  }

  bool _applyPureChatReplyUpdate(
    ChatConversationRuntimeState runtime,
    String taskId, {
    required String text,
    required bool isError,
    bool renderMarkdown = true,
    int? markdownRenderedLength,
    bool isSummarizing = false,
    List<Map<String, dynamic>> attachments = const <Map<String, dynamic>>[],
    double? prefillTokensPerSecond,
    double? decodeTokensPerSecond,
    bool emitVoiceUpdate = false,
    bool schedulePersistence = false,
  }) {
    final hasExistingMessage = runtime.messages.any(
      (message) => message.id == taskId,
    );
    final hasPerformanceMetrics =
        prefillTokensPerSecond != null || decodeTokensPerSecond != null;
    final shouldWrite =
        isError ||
        isSummarizing ||
        text.isNotEmpty ||
        attachments.isNotEmpty ||
        (hasPerformanceMetrics && hasExistingMessage);
    if (!shouldWrite) {
      return false;
    }

    _removeLatestLoadingIfExists(runtime);
    _removeOpenClawWaitingCard(runtime, taskId);
    final reasoningContent =
        _normalizeReasoningContent(runtime.currentThinkingMessages[taskId]) ??
        _normalizeReasoningContent(runtime.deepThinkingContent);
    _updateOrAddAiMessage(
      runtime,
      taskId,
      text,
      isError,
      isSummarizing: isSummarizing,
      renderMarkdown: renderMarkdown,
      markdownRenderedLength: markdownRenderedLength,
      attachments: attachments,
      prefillTokensPerSecond: prefillTokensPerSecond,
      decodeTokensPerSecond: decodeTokensPerSecond,
      reasoningContent: reasoningContent,
    );
    if (emitVoiceUpdate &&
        !isError &&
        !isSummarizing &&
        text.trim().isNotEmpty) {
      unawaited(
        VoicePlaybackCoordinator.instance.onAssistantMessageUpdated(
          messageId: taskId,
          text: text,
          isFinal: false,
        ),
      );
    }
    if (schedulePersistence) {
      schedulePersistRuntimeConversation(
        conversationId: runtime.conversationId,
        mode: runtime.mode,
      );
    }
    return true;
  }

  bool _flushPureChatReplyBatch(
    ChatConversationRuntimeState runtime,
    String taskId, {
    bool emitVoiceUpdate = false,
    bool schedulePersistence = false,
  }) {
    final batch = _streamingTextBatchFor(
      runtime,
      taskId,
      _StreamingTextStreamKind.pureChatReply,
    );
    if (batch == null || !batch.hasPendingFlush) {
      return false;
    }
    final visibleText = runtime.currentAiMessages[taskId] ?? batch.latestText;
    batch.markFlushed();
    return _applyPureChatReplyUpdate(
      runtime,
      taskId,
      text: visibleText,
      isError: false,
      renderMarkdown: true,
      emitVoiceUpdate: emitVoiceUpdate,
      schedulePersistence: schedulePersistence,
    );
  }

  void _upsertAgentReplyMessage(
    ChatConversationRuntimeState runtime,
    String messageId,
    String text, {
    bool renderMarkdown = true,
    int? markdownRenderedLength,
    bool isFinal = false,
    bool isError = false,
    Map<String, dynamic>? streamMeta,
    double? prefillTokensPerSecond,
    double? decodeTokensPerSecond,
    String? reasoningContent,
  }) {
    final resolvedStreamMeta = ensureAgentStreamMessageMeta(
      streamMeta,
      entryId: messageId,
      isFinal: isFinal,
    );
    final index = runtime.messages.indexWhere(
      (message) => message.id == messageId,
    );
    if (index == -1) {
      final content = <String, dynamic>{
        'text': text,
        'id': messageId,
        'renderMarkdown': renderMarkdown,
        if (isFinal && prefillTokensPerSecond != null)
          'prefillTokensPerSecond': prefillTokensPerSecond,
        if (isFinal && decodeTokensPerSecond != null)
          'decodeTokensPerSecond': decodeTokensPerSecond,
      };
      if (markdownRenderedLength != null) {
        content['markdownRenderedLength'] = markdownRenderedLength;
      }
      _clearAgentRetryPresentation(content);
      runtime.messages.insert(
        0,
        ChatMessageModel(
          id: messageId,
          type: 1,
          user: 2,
          content: content,
          isError: isError,
          streamMeta: resolvedStreamMeta,
          reasoningContent: _normalizeReasoningContent(reasoningContent),
        ),
      );
      return;
    }

    final existing = runtime.messages[index];
    final content = Map<String, dynamic>.from(existing.content ?? const {});
    final currentText = (content['text'] ?? '').toString();
    content['text'] = text.isNotEmpty ? text : currentText;
    content['renderMarkdown'] = renderMarkdown;
    if (markdownRenderedLength != null) {
      content['markdownRenderedLength'] = markdownRenderedLength;
    } else {
      content.remove('markdownRenderedLength');
    }
    if (isFinal && prefillTokensPerSecond != null) {
      content['prefillTokensPerSecond'] = prefillTokensPerSecond;
    }
    if (isFinal && decodeTokensPerSecond != null) {
      content['decodeTokensPerSecond'] = decodeTokensPerSecond;
    }
    _clearAgentRetryPresentation(content);
    runtime.messages[index] = existing.copyWith(
      content: content,
      isError: isError,
      streamMeta: ensureAgentStreamMessageMeta(
        resolvedStreamMeta ?? existing.streamMeta,
        entryId: messageId,
        isFinal: isFinal,
      ),
      reasoningContent:
          _normalizeReasoningContent(reasoningContent) ??
          existing.reasoningContent,
    );
  }

  bool _flushAgentReplyBatch(
    ChatConversationRuntimeState runtime,
    String taskId, {
    bool isFinal = false,
    bool emitVoiceEvent = false,
    bool schedulePersistence = false,
    double? prefillTokensPerSecond,
    double? decodeTokensPerSecond,
  }) {
    final batch = _streamingTextBatchFor(
      runtime,
      taskId,
      _StreamingTextStreamKind.agentReply,
    );
    final messageId =
        _resolvePendingAgentTextMessageId(runtime, taskId) ??
        _latestAgentTextMessageId(runtime, taskId) ??
        _nextAgentTextMessageId(runtime, taskId);
    final text =
        batch?.latestText ??
        _visibleAgentReplyText(runtime, taskId, messageId: messageId);
    final hasPendingFlush = batch?.hasPendingFlush ?? false;
    final hasPerformanceMetrics =
        prefillTokensPerSecond != null || decodeTokensPerSecond != null;
    final hasExistingMessage = runtime.messages.any(
      (message) => message.id == messageId,
    );
    final shouldWrite =
        hasPendingFlush ||
        (text.isNotEmpty && !hasExistingMessage) ||
        (hasPerformanceMetrics && hasExistingMessage);
    if (shouldWrite) {
      _upsertAgentReplyMessage(
        runtime,
        messageId,
        text,
        renderMarkdown: true,
        isFinal: isFinal,
        prefillTokensPerSecond: prefillTokensPerSecond,
        decodeTokensPerSecond: decodeTokensPerSecond,
        reasoningContent: runtime.currentThinkingMessages[taskId],
      );
    }
    if (batch != null && (hasPendingFlush || isFinal)) {
      batch.markFlushed();
    }
    if (emitVoiceEvent &&
        text.trim().isNotEmpty &&
        (hasPendingFlush || isFinal)) {
      unawaited(
        VoicePlaybackCoordinator.instance.onAssistantMessageUpdated(
          messageId: messageId,
          text: text,
          isFinal: isFinal,
        ),
      );
    }
    if (schedulePersistence) {
      schedulePersistRuntimeConversation(
        conversationId: runtime.conversationId,
        mode: runtime.mode,
      );
    }
    if (isFinal) {
      _clearStreamingTextBatch(
        runtime,
        taskId,
        _StreamingTextStreamKind.agentReply,
      );
    }
    return shouldWrite || (emitVoiceEvent && text.trim().isNotEmpty && isFinal);
  }

  bool _flushThinkingBatch(
    ChatConversationRuntimeState runtime,
    String taskId,
    _StreamingTextStreamKind kind, {
    bool schedulePersistence = false,
  }) {
    final batch = _streamingTextBatchFor(runtime, taskId, kind);
    if (batch == null || !batch.hasPendingFlush) {
      return false;
    }
    final binding =
        _taskBindings[taskId] ??
        _TaskBinding(
          conversationId: runtime.conversationId,
          mode: runtime.mode,
        );
    final thinking =
        runtime.currentThinkingMessages[taskId] ?? batch.latestText;
    if (thinking.isNotEmpty) {
      _applyThinkingUpdate(
        runtime,
        binding,
        taskId,
        thinking,
        notifyAfterUpdate: false,
        schedulePersistence: false,
      );
    }
    batch.markFlushed();
    if (schedulePersistence) {
      schedulePersistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
      );
    }
    return thinking.isNotEmpty;
  }

  void _handleChatTaskMessage(String taskId, String content, String? type) {
    final binding = _taskBindings[taskId];
    final runtime = _runtimeForTask(taskId);
    if (binding == null || runtime == null) return;

    final isErrorMessage = type == 'error';
    final isRateLimited = type == 'rate_limited';
    final isSummaryStart = type == 'summary_start';
    final isOpenClawAttachment = type == 'openclaw_attachment';
    final payload = safeDecodeMap(content);
    final payloadAttachments = _parseAttachments(payload['attachments']);
    final prefillTokensPerSecond = extractChatTaskPrefillTokensPerSecond(
      content,
    );
    final decodeTokensPerSecond = extractChatTaskDecodeTokensPerSecond(content);
    final hasPerformanceMetrics =
        prefillTokensPerSecond != null || decodeTokensPerSecond != null;

    String messageText;
    bool isError;
    bool isSummarizing;
    var shouldUpdateAiMessage = false;
    var didSchedulePersistence = false;

    if (isRateLimited) {
      _flushPureChatReplyBatch(runtime, taskId, emitVoiceUpdate: true);
      messageText = kRateLimitErrorMessage;
      isError = true;
      isSummarizing = false;
      runtime.isContextCompressing = false;
      runtime.currentAiMessages.remove(taskId);
      _clearStreamingTextBatch(
        runtime,
        taskId,
        _StreamingTextStreamKind.pureChatReply,
      );
      shouldUpdateAiMessage = true;
    } else if (isErrorMessage) {
      _flushPureChatReplyBatch(runtime, taskId, emitVoiceUpdate: true);
      messageText = kNetworkErrorMessage;
      isError = true;
      isSummarizing = false;
      runtime.isContextCompressing = false;
      runtime.currentAiMessages.remove(taskId);
      _clearStreamingTextBatch(
        runtime,
        taskId,
        _StreamingTextStreamKind.pureChatReply,
      );
      shouldUpdateAiMessage = true;
    } else if (isSummaryStart) {
      _flushPureChatReplyBatch(runtime, taskId, emitVoiceUpdate: true);
      messageText = '';
      isError = false;
      isSummarizing = true;
      runtime.isContextCompressing = true;
      runtime.currentAiMessages[taskId] = '';
      _clearStreamingTextBatch(
        runtime,
        taskId,
        _StreamingTextStreamKind.pureChatReply,
      );
      shouldUpdateAiMessage = true;
    } else if (isOpenClawAttachment) {
      messageText =
          runtime.currentAiMessages[taskId] ??
          _visiblePureChatReplyText(runtime, taskId);
      isError = false;
      isSummarizing = false;
      runtime.isContextCompressing = false;
      shouldUpdateAiMessage = true;
    } else {
      final thinking = extractChatTaskThinking(
        content,
        fallbackToRawText: false,
      );
      if (thinking.isNotEmpty) {
        _upsertPureChatThinking(runtime, taskId, thinking);
      }
      final text = extractChatTaskText(content, fallbackToRawText: false);
      if (text.isNotEmpty) {
        final previousText = runtime.currentAiMessages[taskId] ?? '';
        final mergedText = mergeLegacyStreamingText(previousText, text);
        if (mergedText != previousText && mergedText.isNotEmpty) {
          runtime.currentAiMessages[taskId] = mergedText;
          final visibleText = _visiblePureChatReplyText(runtime, taskId);
          final shouldFlush = _stageStreamingTextBatch(
            runtime,
            taskId,
            _StreamingTextStreamKind.pureChatReply,
            nextText: mergedText,
            initialLatestText: previousText.isNotEmpty
                ? previousText
                : visibleText,
            initialFlushedText: visibleText,
          );
          if (shouldFlush) {
            _flushPureChatReplyBatch(
              runtime,
              taskId,
              emitVoiceUpdate: true,
              schedulePersistence: true,
            );
            didSchedulePersistence = true;
          } else {
            final batch = _streamingTextBatchFor(
              runtime,
              taskId,
              _StreamingTextStreamKind.pureChatReply,
            );
            _applyPureChatReplyUpdate(
              runtime,
              taskId,
              text: mergedText,
              isError: false,
              renderMarkdown: true,
              markdownRenderedLength: batch?.lastFlushedText.length,
            );
          }
        }
      }
      messageText = runtime.currentAiMessages[taskId] ?? '';
      isError = false;
      isSummarizing = false;
      runtime.isContextCompressing = false;
      if (payloadAttachments.isNotEmpty || hasPerformanceMetrics) {
        shouldUpdateAiMessage = true;
      }
    }

    if (shouldUpdateAiMessage &&
        _applyPureChatReplyUpdate(
          runtime,
          taskId,
          text: messageText,
          isError: isError,
          renderMarkdown: true,
          markdownRenderedLength: _markdownRenderedLengthForBatch(
            runtime,
            taskId,
            _StreamingTextStreamKind.pureChatReply,
          ),
          isSummarizing: isSummarizing,
          attachments: payloadAttachments,
          prefillTokensPerSecond: prefillTokensPerSecond,
          decodeTokensPerSecond: decodeTokensPerSecond,
          schedulePersistence: true,
        )) {
      didSchedulePersistence = true;
    }
    runtime.isAiResponding = true;
    notifyListeners();
    if (!didSchedulePersistence &&
        (isRateLimited || isErrorMessage || isSummaryStart)) {
      schedulePersistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
      );
    }
  }

  void _handleChatTaskMessageEnd(String taskId) {
    final binding = _taskBindings[taskId];
    final runtime = _runtimeForTask(taskId);
    if (binding == null || runtime == null) return;

    _flushThinkingBatch(
      runtime,
      taskId,
      _StreamingTextStreamKind.pureChatThinking,
    );
    final thinkingCardId = _resolveThinkingCardId(runtime, taskId);
    if (thinkingCardId != null) {
      runtime.currentThinkingStage = ThinkingStage.complete.value;
      runtime.isDeepThinking = false;
      _finalizeThinkingCardsForTask(runtime, taskId);
      runtime.currentThinkingMessages.remove(taskId);
      runtime.deepThinkingContent = '';
      runtime.lastAgentTaskId = null;
      runtime.activeThinkingCardId = null;
      runtime.pendingThinkingRoundSplit = false;
      runtime.thinkingRound = 0;
    }

    runtime.isAiResponding = false;
    runtime.isContextCompressing = false;
    _flushPureChatReplyBatch(runtime, taskId);
    final index = runtime.messages.indexWhere((msg) => msg.id == taskId);
    final isErrorMessage = index != -1 && runtime.messages[index].isError;
    final messageText = isErrorMessage
        ? (runtime.messages[index].content?['text'] as String? ?? '')
        : (runtime.currentAiMessages[taskId] ??
              _visiblePureChatReplyText(runtime, taskId));

    if (messageText.isNotEmpty && index != -1) {
      final existing = runtime.messages[index];
      runtime.messages[index] = existing.copyWith(content: existing.content);
      _syncMessageLinkPreviews(runtime, taskId);
    }
    if (!isErrorMessage && messageText.trim().isNotEmpty) {
      unawaited(
        VoicePlaybackCoordinator.instance.onAssistantMessageCompleted(
          messageId: taskId,
          text: messageText,
        ),
      );
    }
    runtime.currentAiMessages.remove(taskId);
    _clearStreamingTextBatchesForTask(runtime, taskId);
    _taskBindings.remove(taskId);
    notifyListeners();
    unawaited(
      persistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
        markComplete: true,
      ),
    );
  }

  void _handleAgentStreamEvent(AgentStreamEvent event) {
    var binding = _taskBindings[event.taskId];
    var runtime = _runtimeForTask(event.taskId);
    if (binding == null || runtime == null) {
      final recovered = _recoverExternalAgentStreamBinding(event);
      if (recovered == null) {
        return;
      }
      binding = recovered.binding;
      runtime = recovered.runtime;
    }

    final reduceResult = _agentStreamReducer.reduce(
      runtime.agentStreamStates[event.taskId],
      event,
    );
    if (!reduceResult.accepted) {
      return;
    }
    final thinkingCardToFinalize = _resolveThinkingCardToFinalize(
      reduceResult,
      event,
    );
    runtime.agentStreamStates[event.taskId] = reduceResult.nextState;
    _syncRuntimeAgentState(runtime, event, reduceResult.nextState);

    switch (event.kind) {
      case AgentStreamEventKind.thinkingStarted:
      case AgentStreamEventKind.thinkingSnapshot:
        _applyAgentThinkingStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.retrying:
        _applyAgentRetryingStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.textSnapshot:
        _applyAgentTextStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.toolStarted:
      case AgentStreamEventKind.toolProgress:
      case AgentStreamEventKind.toolCompleted:
        _applyAgentToolStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.clarifyRequired:
        _applyAgentClarifyStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.completed:
        _applyAgentCompletedStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.error:
        _applyAgentErrorStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
      case AgentStreamEventKind.permissionRequired:
        _applyAgentPermissionStreamEvent(
          runtime,
          binding,
          event,
          completedThinkingCardId: thinkingCardToFinalize,
        );
        return;
    }
  }

  ({_TaskBinding binding, ChatConversationRuntimeState runtime})?
  _recoverExternalAgentStreamBinding(AgentStreamEvent event) {
    final conversationId = _asPositiveInt(event.raw['conversationId']);
    if (conversationId == null) {
      return null;
    }
    final runtimeMode = _runtimeModeFromConversationMode(
      (event.raw['conversationMode'] ?? event.raw['mode'] ?? '').toString(),
    );
    final runtime = runtimeFor(
      conversationId: conversationId,
      mode: runtimeMode,
    );
    if (runtime == null) {
      return null;
    }
    final binding = _TaskBinding(
      conversationId: conversationId,
      mode: runtimeMode,
    );
    _taskBindings[event.taskId] = binding;
    runtime.currentDispatchTaskId ??= event.taskId;
    runtime.lastAgentTaskId = event.taskId;
    // 外部任务（IM 等）触发：用户消息已经写入 DB，但可能还没进入 runtime.messages
    // —— Flutter 的 messagesChanged 事件走的是异步 stream listener（微任务），
    // 而 agent 流事件是同步回调，常常先到达。这里把缺失的用户消息从 DB 补回来，
    // 否则聊天页只会看到 agent 的回复，没有用户输入。
    final userEntryId = '${event.taskId}-user';
    final alreadyPresent = runtime.messages.any((m) => m.id == userEntryId);
    if (!alreadyPresent) {
      unawaited(
        _reconcileExternalUserMessage(
          conversationId: conversationId,
          mode: runtimeMode,
          userEntryId: userEntryId,
        ),
      );
    }
    return (binding: binding, runtime: runtime);
  }

  void _handleExternalUserMessageAppended(Map<String, dynamic> data) {
    // 原生侧（IM 等）写完用户消息后直推过来的 payload —— 不依赖 messagesChanged
    // 微任务，也不依赖 agent 流事件，直接把用户气泡插入 runtime.messages。
    final conversationId = _asPositiveInt(data['conversationId']);
    if (conversationId == null) return;
    final runtimeMode = _runtimeModeFromConversationMode(
      (data['mode'] ?? data['conversationMode'] ?? '').toString(),
    );
    final runtimeKey = _runtimeKey(
      conversationId: conversationId,
      mode: runtimeMode,
    );
    final runtime = _runtimes[runtimeKey];
    if (runtime == null) {
      // 聊天页还没为这个会话建立 runtime —— 没什么可注入的，等聊天页加载时
      // 自然会从 DB 读到这条用户消息。
      return;
    }
    final entryId = (data['entryId'] ?? '').toString().trim();
    if (entryId.isEmpty) return;
    if (runtime.messages.any((m) => m.id == entryId)) return;

    final text = (data['text'] ?? '').toString();
    final createdAtMs = _asPositiveInt(data['createdAt']) ??
        DateTime.now().millisecondsSinceEpoch;
    final createdAt = DateTime.fromMillisecondsSinceEpoch(createdAtMs);
    final rawAttachments = data['attachments'];
    final attachments = rawAttachments is List
        ? rawAttachments
              .whereType<Map>()
              .map((item) => Map<String, dynamic>.from(item.cast()))
              .toList()
        : const <Map<String, dynamic>>[];

    final message = ChatMessageModel(
      id: entryId,
      type: 1,
      user: 1,
      content: <String, dynamic>{
        'id': entryId,
        'text': text,
        if (attachments.isNotEmpty) 'attachments': attachments,
      },
      createAt: createdAt,
    );

    final insertAt = _findInsertIndexByCreatedAt(
      runtime.messages,
      message.createAt,
    );
    runtime.messages.insert(insertAt, message);
    notifyListeners();
  }

  Future<void> _reconcileExternalUserMessage({
    required int conversationId,
    required String mode,
    required String userEntryId,
  }) async {
    final runtimeKey = _runtimeKey(conversationId: conversationId, mode: mode);
    final runtime = _runtimes[runtimeKey];
    if (runtime == null) return;
    if (runtime.messages.any((m) => m.id == userEntryId)) return;
    final conversationMode = switch (mode) {
      kChatRuntimeModeOpenClaw => ConversationMode.openclaw,
      kChatRuntimeModeCodex => ConversationMode.codex,
      _ => ConversationMode.normal,
    };
    try {
      final result = await ConversationHistoryService.getConversationMessagesPaged(
        conversationId,
        mode: conversationMode,
        limit: 100,
        offset: 0,
      );
      final stillMissingFromRuntime = _runtimes[runtimeKey];
      if (stillMissingFromRuntime == null) return;
      if (stillMissingFromRuntime.messages.any((m) => m.id == userEntryId)) {
        return;
      }
      ChatMessageModel? userMessage;
      for (final message in result.messages) {
        if (message.id == userEntryId) {
          userMessage = message;
          break;
        }
      }
      if (userMessage == null) return;
      final insertAt = _findInsertIndexByCreatedAt(
        stillMissingFromRuntime.messages,
        userMessage.createAt,
      );
      stillMissingFromRuntime.messages.insert(insertAt, userMessage);
      notifyListeners();
    } catch (_) {
      // 即使 DB 拉取失败也不要崩溃 —— 后续 messagesChanged 事件还有机会补救。
    }
  }

  int _findInsertIndexByCreatedAt(
    List<ChatMessageModel> messages,
    DateTime createdAt,
  ) {
    // runtime.messages 是降序（最新在 index 0），与 Kotlin sortForDisplay 的
    // .asReversed() 以及 ChatMessageList 用 timelineEntries.length-1-index 反向
    // 渲染的约定一致 —— 所有 agent 流事件也都是 runtime.messages.insert(0, ...)。
    // 因此从前往后找第一条 createAt 不晚于新消息的位置，插在它之前即可。
    for (var i = 0; i < messages.length; i++) {
      if (!messages[i].createAt.isAfter(createdAt)) {
        return i;
      }
    }
    return messages.length;
  }

  int? _asPositiveInt(dynamic raw) {
    final value = switch (raw) {
      int value => value,
      num value => value.toInt(),
      String value => int.tryParse(value.trim()),
      _ => null,
    };
    return value != null && value > 0 ? value : null;
  }

  String _runtimeModeFromConversationMode(String rawMode) {
    return switch (ConversationMode.fromStorageValue(rawMode)) {
      ConversationMode.openclaw => kChatRuntimeModeOpenClaw,
      ConversationMode.codex => kChatRuntimeModeCodex,
      _ => kChatRuntimeModeNormal,
    };
  }

  void _syncRuntimeAgentState(
    ChatConversationRuntimeState runtime,
    AgentStreamEvent event,
    AgentStreamTaskState state,
  ) {
    runtime.currentDispatchTaskId ??= event.taskId;
    runtime.lastAgentTaskId = event.taskId;
    runtime.activeThinkingCardId = state.activeThinkingEntryId;
    runtime.currentThinkingStage = state.thinkingStage;
    runtime.isDeepThinking = state.isDeepThinking;
    runtime.thinkingRound = state.thinkingRounds.length;
    runtime.toolCardSequence = state.toolCards.length;
    runtime.pendingThinkingRoundSplit = false;
    runtime.browserSessionSnapshot =
        state.browserSnapshot ?? runtime.browserSessionSnapshot;
    runtime.pendingAgentTextTaskId =
        event.kind == AgentStreamEventKind.textSnapshot && !event.isFinal
        ? event.taskId
        : null;
    if (event.kind == AgentStreamEventKind.toolStarted ||
        event.kind == AgentStreamEventKind.toolProgress) {
      runtime.activeToolCardId = event.entryId?.trim();
    } else if (event.kind == AgentStreamEventKind.toolCompleted) {
      if (runtime.activeToolCardId == event.entryId?.trim()) {
        runtime.activeToolCardId = null;
      }
    }
  }

  void _applyAgentThinkingStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final cardId = (event.entryId ?? '').trim();
    if (cardId.isEmpty) {
      return;
    }

    runtime.isAiResponding = true;
    if (event.thinking.isNotEmpty) {
      runtime.currentThinkingMessages[event.taskId] = event.thinking;
      runtime.deepThinkingContent = event.thinking;
    }
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    final streamMeta = _streamMetaFromEvent(event);
    final exists = runtime.messages.any((message) => message.id == cardId);
    if (exists) {
      _updateThinkingCard(
        runtime,
        event.taskId,
        cardId: cardId,
        thinkingContent: event.thinking.isNotEmpty ? event.thinking : null,
        isLoading: true,
        stage: event.stage <= 0 ? ThinkingStage.thinking.value : event.stage,
        streamMeta: streamMeta,
        lockCompleted: false,
      );
    } else {
      _createThinkingCard(
        runtime,
        event.taskId,
        cardId: cardId,
        thinkingContent: event.thinking,
        isLoading: true,
        stage: event.stage <= 0 ? ThinkingStage.thinking.value : event.stage,
        streamMeta: streamMeta,
      );
    }
    notifyListeners();
    schedulePersistRuntimeConversation(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _applyAgentTextStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final messageId = (event.entryId ?? '').trim();
    final text = event.text.trim();
    if (messageId.isEmpty || text.isEmpty) {
      return;
    }

    runtime.isAiResponding = true;
    runtime.currentAiMessages[event.taskId] = text;
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    _upsertAgentReplyMessage(
      runtime,
      messageId,
      text,
      renderMarkdown: true,
      isFinal: event.isFinal,
      streamMeta: _streamMetaFromEvent(event),
      prefillTokensPerSecond: event.prefillTokensPerSecond,
      decodeTokensPerSecond: event.decodeTokensPerSecond,
      reasoningContent: event.thinking,
    );
    if (event.isFinal) {
      _syncMessageLinkPreviews(runtime, messageId);
    }
    unawaited(
      VoicePlaybackCoordinator.instance.onAssistantMessageUpdated(
        messageId: messageId,
        text: text,
        isFinal: event.isFinal,
      ),
    );
    notifyListeners();
    schedulePersistRuntimeConversation(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _applyAgentToolStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final cardId = (event.entryId ?? '').trim();
    if (cardId.isEmpty) {
      return;
    }

    final toolEvent = AgentToolEventData.fromMap(event.raw);
    runtime.isAiResponding = true;
    _updateToolLayerState(runtime, toolEvent);
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    _upsertToolCard(
      runtime: runtime,
      taskId: event.taskId,
      cardId: cardId,
      event: toolEvent,
      status: event.kind == AgentStreamEventKind.toolCompleted
          ? _resolveToolStatus(toolEvent)
          : 'running',
      summary: toolEvent.summary.isNotEmpty
          ? toolEvent.summary
          : (_isEnglish ? 'Calling tool' : '正在调用工具'),
      progress: toolEvent.progress,
      resultPreviewJson: toolEvent.resultPreviewJson,
      rawResultJson: toolEvent.rawResultJson,
      reasoningContent: event.thinking,
      streamMeta: _streamMetaFromEvent(event),
    );
    if (event.kind == AgentStreamEventKind.toolCompleted) {
      _updateBrowserSessionSnapshot(runtime, toolEvent);
      if (event.browserSnapshot != null) {
        runtime.browserSessionSnapshot = event.browserSnapshot;
      }
    }
    notifyListeners();
    schedulePersistRuntimeConversation(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _applyAgentRetryingStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final entryId = (event.entryId ?? '').trim();
    final retryText = _buildAgentRetryingText(event);
    final streamMeta = ensureAgentStreamMessageMeta(
      _streamMetaFromEvent(event),
      entryId: entryId.isEmpty ? null : entryId,
      isFinal: false,
    );
    final isThinkingCardTarget =
        entryId.isNotEmpty &&
        runtime.messages.any((message) => message.id == entryId && message.type == 2);
    if (!isThinkingCardTarget) {
      _finalizeThinkingCard(
        runtime,
        event.taskId,
        cardId: completedThinkingCardId,
      );
    }
    if (isThinkingCardTarget) {
      _updateThinkingCard(
        runtime,
        event.taskId,
        cardId: entryId,
        thinkingContent: retryText,
        isLoading: true,
        stage: 1,
        streamMeta: streamMeta,
        lockCompleted: false,
      );
    } else {
      final messageId = entryId.isNotEmpty ? entryId : _nextAgentTextMessageId(runtime, event.taskId);
      final index = runtime.messages.indexWhere((message) => message.id == messageId);
      if (index == -1) {
        final content = <String, dynamic>{'text': '', 'id': messageId};
        _applyAgentRetryPresentation(content, event, retryText);
        runtime.messages.insert(
          0,
          ChatMessageModel(
            id: messageId,
            type: 1,
            user: 2,
            content: content,
            streamMeta: streamMeta,
          ),
        );
      } else {
        final existing = runtime.messages[index];
        final content = Map<String, dynamic>.from(existing.content ?? const {});
        content['id'] = messageId;
        _applyAgentRetryPresentation(content, event, retryText);
        runtime.messages[index] = existing.copyWith(
          content: content,
          streamMeta: streamMeta ?? existing.streamMeta,
          isError: false,
        );
      }
    }
    runtime.isAiResponding = true;
    notifyListeners();
    schedulePersistRuntimeConversation(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _applyAgentClarifyStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final text = event.question.trim().isNotEmpty
        ? event.question.trim()
        : event.text.trim();
    final messageId = (event.entryId ?? '').trim();
    if (messageId.isNotEmpty && text.isNotEmpty) {
      _upsertAgentReplyMessage(
        runtime,
        messageId,
        text,
        renderMarkdown: true,
        isFinal: true,
        streamMeta: _streamMetaFromEvent(event),
        reasoningContent: event.thinking,
      );
    }
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    runtime.isAiResponding = false;
    runtime.currentAiMessages.remove(event.taskId);
    runtime.currentThinkingMessages.remove(event.taskId);
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    _finalizeThinkingCardsForTask(runtime, event.taskId);
    runtime.agentStreamStates.remove(event.taskId);
    _taskBindings.remove(event.taskId);
    notifyListeners();
    unawaited(
      persistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
        markComplete: true,
      ),
    );
    clearConversationRuntimeSession(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _applyAgentCompletedStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    runtime.isAiResponding = false;
    runtime.currentAiMessages.remove(event.taskId);
    runtime.currentThinkingMessages.remove(event.taskId);
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    _finalizeThinkingCardsForTask(runtime, event.taskId);
    runtime.agentStreamStates.remove(event.taskId);
    _taskBindings.remove(event.taskId);
    notifyListeners();
    unawaited(
      persistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
        markComplete: true,
      ),
    );
    clearConversationRuntimeSession(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _applyAgentErrorStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final entryId = (event.entryId ?? '').trim();
    final shouldMarkError = event.raw['persistAsError'] == true;
    final errorText = (event.raw['errorText'] ?? event.errorMessage).toString().trim();
    if (entryId.isNotEmpty) {
      final index = runtime.messages.indexWhere(
        (message) => message.id == entryId,
      );
      if (index != -1) {
        final existing = runtime.messages[index];
        final content = Map<String, dynamic>.from(existing.content ?? const {});
        _applyAgentErrorPresentation(content, event, errorText);
        runtime.messages[index] = runtime.messages[index].copyWith(
          content: content,
          isError: shouldMarkError,
          streamMeta: ensureAgentStreamMessageMeta(
            _streamMetaFromEvent(event),
            entryId: entryId,
            isFinal: true,
          ),
        );
      }
    }
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );
    runtime.isAiResponding = false;
    runtime.currentAiMessages.remove(event.taskId);
    runtime.currentThinkingMessages.remove(event.taskId);
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    _finalizeThinkingCardsForTask(runtime, event.taskId);
    runtime.agentStreamStates.remove(event.taskId);
    _taskBindings.remove(event.taskId);
    notifyListeners();
    unawaited(
      persistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
        markComplete: true,
      ),
    );
    clearConversationRuntimeSession(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _applyAgentPermissionStreamEvent(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    AgentStreamEvent event, {
    String? completedThinkingCardId,
  }) {
    final messageId = (event.entryId ?? '').trim();
    final text = event.text.trim();
    if (messageId.isNotEmpty && text.isNotEmpty) {
      _upsertAgentReplyMessage(
        runtime,
        messageId,
        text,
        renderMarkdown: true,
        isFinal: true,
        streamMeta: _streamMetaFromEvent(event),
        reasoningContent: event.thinking,
      );
    }
    _finalizeThinkingCard(
      runtime,
      event.taskId,
      cardId: completedThinkingCardId,
    );

    final executionPermissionIds = event.missingPermissions
        .map((item) => item.trim())
        .map((item) => _executionPermissionNameToId[item])
        .whereType<String>()
        .toSet()
        .toList(growable: false);
    final permissionCardId =
        (event.raw['permissionCardId'] ?? '${event.taskId}-permission')
            .toString();
    if (executionPermissionIds.isNotEmpty) {
      final cardIndex = runtime.messages.indexWhere(
        (message) => message.id == permissionCardId,
      );
      final cardData = <String, dynamic>{
        'type': 'permission_section',
        'requiredPermissionIds': executionPermissionIds,
      };
      final message = ChatMessageModel(
        id: permissionCardId,
        type: 2,
        user: 3,
        content: {'cardData': cardData, 'id': permissionCardId},
        streamMeta: _streamMetaFromEvent(event),
      );
      if (cardIndex == -1) {
        runtime.messages.insert(0, message);
      } else {
        runtime.messages[cardIndex] = runtime.messages[cardIndex].copyWith(
          content: {'cardData': cardData, 'id': permissionCardId},
          streamMeta: _streamMetaFromEvent(event),
        );
      }
    }

    runtime.isAiResponding = false;
    runtime.currentAiMessages.remove(event.taskId);
    runtime.currentThinkingMessages.remove(event.taskId);
    runtime.deepThinkingContent = '';
    runtime.isDeepThinking = false;
    _finalizeThinkingCardsForTask(runtime, event.taskId);
    runtime.agentStreamStates.remove(event.taskId);
    _taskBindings.remove(event.taskId);
    notifyListeners();
    unawaited(
      persistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
        markComplete: true,
      ),
    );
    clearConversationRuntimeSession(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  Map<String, dynamic> _streamMetaFromEvent(AgentStreamEvent event) {
    return buildAgentStreamMetaFromEvent(event);
  }

  String _buildAgentRetryingText(AgentStreamEvent event) {
    if (event.text.trim().isNotEmpty) {
      return event.text.trim();
    }
    final retryCount = event.retryCount <= 0 ? 1 : event.retryCount;
    final maxRetries = event.maxRetries <= 0 ? 3 : event.maxRetries;
    return LegacyTextLocalizer.isEnglish
        ? 'Connection interrupted. Retrying $retryCount/$maxRetries...'
        : '连接中断，正在重试 $retryCount/$maxRetries…';
  }

  void _applyAgentRetryPresentation(
    Map<String, dynamic> content,
    AgentStreamEvent event,
    String retryText,
  ) {
    content['agentTaskId'] = event.taskId;
    content['agentRetrying'] = true;
    content['agentRetryStatusText'] = retryText;
    content['agentRetryCount'] = event.retryCount;
    content['agentMaxRetries'] = event.maxRetries;
    content['agentRetryDelayMs'] = event.retryDelayMs;
    content['agentRetryReason'] = event.retryReason;
    content.remove('agentErrorText');
    content.remove('agentRetryable');
  }

  void _applyAgentErrorPresentation(
    Map<String, dynamic> content,
    AgentStreamEvent event,
    String errorText,
  ) {
    content['agentTaskId'] = event.taskId;
    content['agentRetrying'] = false;
    content['agentRetryStatusText'] = '';
    content['agentRetryCount'] = event.retryCount;
    content['agentMaxRetries'] = event.maxRetries;
    content['agentRetryDelayMs'] = 0;
    content['agentRetryReason'] = event.retryReason;
    content['agentRetryable'] = event.retryable;
    content['agentErrorText'] = errorText;
  }

  void _clearAgentRetryPresentation(Map<String, dynamic> content) {
    content.remove('agentRetrying');
    content.remove('agentRetryStatusText');
    content.remove('agentRetryCount');
    content.remove('agentMaxRetries');
    content.remove('agentRetryDelayMs');
    content.remove('agentRetryReason');
    content.remove('agentRetryable');
    content.remove('agentErrorText');
  }

  void _upsertPureChatThinking(
    ChatConversationRuntimeState runtime,
    String taskId,
    String thinking,
  ) {
    final binding = _taskBindings[taskId];
    if (binding == null) {
      return;
    }
    final previous = runtime.currentThinkingMessages[taskId] ?? '';
    final merged = mergeLegacyStreamingText(previous, thinking);
    if (merged.isEmpty || merged == previous) {
      return;
    }

    runtime.currentThinkingMessages[taskId] = merged;
    if (runtime.thinkingRound == 0) {
      primePureChatThinking(
        taskId: taskId,
        conversationId: binding.conversationId,
        mode: binding.mode,
      );
    }
    final visibleThinking = _visibleThinkingText(runtime, taskId);
    final shouldFlush = _stageStreamingTextBatch(
      runtime,
      taskId,
      _StreamingTextStreamKind.pureChatThinking,
      nextText: merged,
      initialLatestText: previous.isNotEmpty ? previous : visibleThinking,
      initialFlushedText: visibleThinking,
    );
    if (shouldFlush) {
      _flushThinkingBatch(
        runtime,
        taskId,
        _StreamingTextStreamKind.pureChatThinking,
        schedulePersistence: true,
      );
    }
  }

  void _applyThinkingUpdate(
    ChatConversationRuntimeState runtime,
    _TaskBinding binding,
    String taskId,
    String thinking, {
    bool notifyAfterUpdate = true,
    bool schedulePersistence = true,
  }) {
    if (runtime.pendingThinkingRoundSplit) {
      if (thinking.trim().isEmpty) {
        return;
      }

      final previousThinkingCardId = _resolveThinkingCardId(runtime, taskId);
      if (previousThinkingCardId != null) {
        _updateThinkingCard(
          runtime,
          taskId,
          cardId: previousThinkingCardId,
          isLoading: false,
          stage: ThinkingStage.complete.value,
          lockCompleted: false,
        );
      }

      runtime.thinkingRound += 1;
      runtime.activeThinkingCardId =
          '$taskId-thinking-${runtime.thinkingRound}';
      _createThinkingCard(
        runtime,
        taskId,
        cardId: runtime.activeThinkingCardId,
        thinkingContent: thinking,
        isLoading: true,
        stage: ThinkingStage.thinking.value,
      );
      runtime.deepThinkingContent = thinking;
      runtime.pendingThinkingRoundSplit = false;
      if (notifyAfterUpdate) {
        notifyListeners();
      }
      return;
    }

    runtime.deepThinkingContent = thinking;
    runtime.lastAgentTaskId = taskId;
    runtime.currentThinkingStage = ThinkingStage.thinking.value;
    runtime.isDeepThinking = true;
    final thinkingCardId = _resolveThinkingCardId(runtime, taskId);
    if (thinkingCardId == null) {
      runtime.activeThinkingCardId = _baseThinkingCardId(taskId);
      _createThinkingCard(
        runtime,
        taskId,
        cardId: runtime.activeThinkingCardId,
        thinkingContent: thinking,
        isLoading: true,
        stage: runtime.currentThinkingStage,
      );
    } else {
      _updateThinkingCard(
        runtime,
        taskId,
        cardId: thinkingCardId,
        thinkingContent: thinking,
        isLoading: true,
        stage: runtime.currentThinkingStage,
        lockCompleted: false,
      );
    }
    if (notifyAfterUpdate) {
      notifyListeners();
    }
  }

  void _handleAgentContextCompactionStateChanged(
    String taskId,
    bool isCompacting,
    int? latestPromptTokens,
    int? promptTokenThreshold,
  ) {
    final binding = _taskBindings[taskId];
    final runtime = _runtimeForTask(taskId);
    if (binding == null || runtime == null) return;

    _applyPromptTokenUsageUpdate(
      runtime,
      latestPromptTokens: latestPromptTokens,
      promptTokenThreshold: promptTokenThreshold,
    );
    if (isCompacting) {
      beginContextCompaction(
        conversationId: binding.conversationId,
        mode: binding.mode,
        taskId: taskId,
        trigger: 'auto',
        latestPromptTokens: latestPromptTokens,
        promptTokenThreshold: promptTokenThreshold,
      );
    } else {
      finishContextCompaction(
        conversationId: binding.conversationId,
        mode: binding.mode,
        status: 'completed',
        latestPromptTokens: latestPromptTokens,
        promptTokenThreshold: promptTokenThreshold,
      );
    }
  }

  void _handleAgentPromptTokenUsageChanged(
    String taskId,
    int latestPromptTokens,
    int? promptTokenThreshold,
  ) {
    final binding = _taskBindings[taskId];
    final runtime = _runtimeForTask(taskId);
    if (binding == null || runtime == null) return;

    _applyPromptTokenUsageUpdate(
      runtime,
      latestPromptTokens: latestPromptTokens,
      promptTokenThreshold: promptTokenThreshold,
    );
    notifyListeners();
    schedulePersistRuntimeConversation(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
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

  void _applyPromptTokenUsageUpdate(
    ChatConversationRuntimeState runtime, {
    int? latestPromptTokens,
    int? promptTokenThreshold,
  }) {
    final conversation = runtime.conversation;
    if (conversation == null ||
        (latestPromptTokens == null && promptTokenThreshold == null)) {
      return;
    }
    final now = DateTime.now().millisecondsSinceEpoch;
    runtime.conversation = conversation.copyWith(
      latestPromptTokens: latestPromptTokens ?? conversation.latestPromptTokens,
      promptTokenThreshold:
          promptTokenThreshold ?? conversation.promptTokenThreshold,
      latestPromptTokensUpdatedAt: latestPromptTokens != null
          ? now
          : conversation.latestPromptTokensUpdatedAt,
    );
  }

  void _handleVlmTaskFinish(String? taskId) {
    if (taskId == null || taskId.isEmpty) return;
    final binding = _taskBindings[taskId];
    if (binding == null) return;
    final runtime = _runtimeForTask(taskId);
    if (runtime == null) return;
    runtime.isExecutingTask = false;
    runtime.isInputAreaVisible = true;
    runtime.vlmInfoQuestion = null;
    runtime.isSubmittingVlmReply = false;
    _taskBindings.remove(taskId);
    notifyListeners();
    unawaited(
      persistRuntimeConversation(
        conversationId: binding.conversationId,
        mode: binding.mode,
        generateSummary: true,
        markComplete: true,
      ),
    );
  }

  void _handleVlmRequestUserInput(String question, String? taskId) {
    if (taskId == null || taskId.isEmpty) return;
    final binding = _taskBindings[taskId];
    final runtime = _runtimeForTask(taskId);
    if (binding == null || runtime == null) return;
    runtime.vlmInfoQuestion = question;
    runtime.isSubmittingVlmReply = false;
    notifyListeners();
    schedulePersistRuntimeConversation(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  ChatConversationRuntimeState? _runtimeForTask(String taskId) {
    final binding = _taskBindings[taskId];
    if (binding == null) return null;
    return ensureRuntime(
      conversationId: binding.conversationId,
      mode: binding.mode,
    );
  }

  void _removeLatestLoadingIfExists(ChatConversationRuntimeState runtime) {
    if (runtime.messages.isNotEmpty && runtime.messages[0].isLoading) {
      runtime.messages.removeAt(0);
    }
  }

  void _updateOrAddAiMessage(
    ChatConversationRuntimeState runtime,
    String taskId,
    String text,
    bool isError, {
    bool renderMarkdown = true,
    int? markdownRenderedLength,
    bool isSummarizing = false,
    List<Map<String, dynamic>> attachments = const [],
    double? prefillTokensPerSecond,
    double? decodeTokensPerSecond,
    String? reasoningContent,
  }) {
    final index = runtime.messages.indexWhere((msg) => msg.id == taskId);
    if (index == -1) {
      final content = <String, dynamic>{
        'text': text,
        'id': taskId,
        'renderMarkdown': renderMarkdown,
      };
      if (markdownRenderedLength != null) {
        content['markdownRenderedLength'] = markdownRenderedLength;
      } else {
        content.remove('markdownRenderedLength');
      }
      if (prefillTokensPerSecond != null) {
        content['prefillTokensPerSecond'] = prefillTokensPerSecond;
      }
      if (decodeTokensPerSecond != null) {
        content['decodeTokensPerSecond'] = decodeTokensPerSecond;
      }
      if (attachments.isNotEmpty) {
        content['attachments'] = attachments;
      }
      runtime.messages.insert(
        0,
        ChatMessageModel(
          id: taskId,
          type: 1,
          user: 2,
          content: content,
          isLoading: false,
          isError: isError,
          isSummarizing: isSummarizing,
          reasoningContent: _normalizeReasoningContent(reasoningContent),
        ),
      );
      return;
    }

    final existing = runtime.messages[index];
    final content = Map<String, dynamic>.from(existing.content ?? {});
    final existingText = content['text'] as String? ?? '';
    content['text'] = text.isNotEmpty ? text : existingText;
    content['renderMarkdown'] = renderMarkdown;
    if (markdownRenderedLength != null) {
      content['markdownRenderedLength'] = markdownRenderedLength;
    } else {
      content.remove('markdownRenderedLength');
    }
    if (prefillTokensPerSecond != null) {
      content['prefillTokensPerSecond'] = prefillTokensPerSecond;
    }
    if (decodeTokensPerSecond != null) {
      content['decodeTokensPerSecond'] = decodeTokensPerSecond;
    }
    final mergedAttachments = _mergeAttachments(
      _parseAttachments(content['attachments']),
      attachments,
    );
    if (mergedAttachments.isNotEmpty) {
      content['attachments'] = mergedAttachments;
    }
    runtime.messages[index] = existing.copyWith(
      content: content,
      isLoading: false,
      isError: isError,
      isSummarizing: isSummarizing,
      reasoningContent:
          _normalizeReasoningContent(reasoningContent) ??
          existing.reasoningContent,
    );
  }

  // 将 AI 文本消息里的 URL 同步成 content.linkPreviews，UI 只负责展示该字段。
  void _syncMessageLinkPreviews(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    final index = runtime.messages.indexWhere((msg) => msg.id == taskId);
    if (index == -1) {
      return;
    }

    final message = runtime.messages[index];
    if (message.type != 1 ||
        message.user != 2 ||
        message.isLoading ||
        message.isError ||
        message.isSummarizing) {
      return;
    }

    final content = Map<String, dynamic>.from(message.content ?? const {});
    final nextPreviews = LinkPreviewService.instance.reconcilePreviewMaps(
      text: message.text ?? '',
      existing: content['linkPreviews'],
    );
    final currentPreviews = content['linkPreviews'];
    var didUpdate = false;
    if (!_previewMapListsEqual(currentPreviews, nextPreviews)) {
      if (nextPreviews.isEmpty) {
        content.remove('linkPreviews');
      } else {
        content['linkPreviews'] = nextPreviews;
      }
      runtime.messages[index] = message.copyWith(content: content);
      didUpdate = true;
    }
    if (didUpdate &&
        !isEphemeralRuntime(
          conversationId: runtime.conversationId,
          mode: runtime.mode,
        ) &&
        nextPreviews.any(
          (item) =>
              ChatLinkPreview.fromJson(item).status !=
              ChatLinkPreview.statusLoading,
        )) {
      unawaited(
        ConversationHistoryService.saveConversationMessages(
          runtime.conversationId,
          List<ChatMessageModel>.from(runtime.messages),
          mode: _conversationModeFromRuntimeMode(
            runtime.mode,
            conversation: runtime.conversation,
          ),
        ),
      );
    }

    // 先写 loading 占位，真实网页信息抓取完成后再局部回填。
    for (final previewMap in nextPreviews) {
      final preview = ChatLinkPreview.fromJson(previewMap);
      if (preview.status != ChatLinkPreview.statusLoading ||
          preview.url.isEmpty) {
        continue;
      }
      unawaited(
        _resolveMessageLinkPreview(
          conversationId: runtime.conversationId,
          mode: runtime.mode,
          taskId: taskId,
          url: preview.url,
        ),
      );
    }
  }

  Future<void> _resolveMessageLinkPreview({
    required int conversationId,
    required String mode,
    required String taskId,
    required String url,
  }) async {
    final resolved = await LinkPreviewService.instance.loadPreview(url);
    final runtime = runtimeFor(conversationId: conversationId, mode: mode);
    if (runtime == null) {
      return;
    }
    final index = runtime.messages.indexWhere((msg) => msg.id == taskId);
    if (index == -1) {
      return;
    }

    final message = runtime.messages[index];
    final content = Map<String, dynamic>.from(message.content ?? const {});
    final rawPreviews = content['linkPreviews'];
    if (rawPreviews is! List) {
      return;
    }

    // 只替换仍处于 loading 的同一 URL，避免覆盖历史 ready/failed 结果。
    var changed = false;
    final updatedPreviews = rawPreviews
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item.cast<String, dynamic>()))
        .map((previewMap) {
          final preview = ChatLinkPreview.fromJson(previewMap);
          if (preview.url != url ||
              preview.status != ChatLinkPreview.statusLoading) {
            return previewMap;
          }
          changed = true;
          return resolved.toJson();
        })
        .toList();
    if (!changed) {
      return;
    }

    content['linkPreviews'] = updatedPreviews;
    runtime.messages[index] = message.copyWith(content: content);
    notifyListeners();
    schedulePersistRuntimeConversation(
      conversationId: conversationId,
      mode: mode,
    );
    if (isEphemeralRuntime(conversationId: conversationId, mode: mode)) {
      return;
    }
    await ConversationHistoryService.saveConversationMessages(
      conversationId,
      List<ChatMessageModel>.from(runtime.messages),
      mode: _conversationModeFromRuntimeMode(
        mode,
        conversation: runtime.conversation,
      ),
    );
  }

  bool _previewMapListsEqual(dynamic left, List<Map<String, dynamic>> right) {
    if (left is! List) {
      return right.isEmpty;
    }
    final normalizedLeft = left
        .whereType<Map>()
        .map((item) => Map<String, dynamic>.from(item.cast<String, dynamic>()))
        .toList();
    if (normalizedLeft.length != right.length) {
      return false;
    }
    for (var index = 0; index < normalizedLeft.length; index += 1) {
      if (!_previewMapEquals(normalizedLeft[index], right[index])) {
        return false;
      }
    }
    return true;
  }

  bool _previewMapEquals(
    Map<String, dynamic> left,
    Map<String, dynamic> right,
  ) {
    return left['url'] == right['url'] &&
        left['domain'] == right['domain'] &&
        left['siteName'] == right['siteName'] &&
        left['title'] == right['title'] &&
        left['description'] == right['description'] &&
        left['imageUrl'] == right['imageUrl'] &&
        left['status'] == right['status'];
  }

  List<Map<String, dynamic>> _parseAttachments(dynamic raw) {
    if (raw is! List) return const [];
    return raw
        .whereType<Map>()
        .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
        .toList();
  }

  List<Map<String, dynamic>> _mergeAttachments(
    List<Map<String, dynamic>> previous,
    List<Map<String, dynamic>> latest,
  ) {
    if (previous.isEmpty) return latest;
    if (latest.isEmpty) return previous;
    final merged = <Map<String, dynamic>>[];
    final seen = <String>{};

    void addAll(List<Map<String, dynamic>> source) {
      for (final item in source) {
        final key = _attachmentIdentity(item);
        if (!seen.add(key)) continue;
        merged.add(item);
      }
    }

    addAll(previous);
    addAll(latest);
    return merged;
  }

  String _attachmentIdentity(Map<String, dynamic> item) {
    final id = (item['id'] as String? ?? '').trim();
    if (id.isNotEmpty) return id;
    final path = (item['path'] as String? ?? '').trim();
    if (path.isNotEmpty) return path;
    final url = (item['url'] as String? ?? '').trim();
    if (url.isNotEmpty) return url;
    final name = (item['name'] as String? ?? '').trim();
    final fileName = (item['fileName'] as String? ?? '').trim();
    return '$name|$fileName|${item['size']}';
  }

  void _createThinkingCard(
    ChatConversationRuntimeState runtime,
    String taskId, {
    String? cardId,
    String? thinkingContent,
    bool? isLoading,
    int? stage,
    Map<String, dynamic>? streamMeta,
  }) {
    final loadingIndex = runtime.messages.indexWhere((msg) => msg.id == taskId);
    if (loadingIndex != -1) {
      runtime.messages.removeAt(loadingIndex);
    }

    final startTime = DateTime.now().millisecondsSinceEpoch;
    final thinkingCardId = cardId ?? '$taskId-thinking';
    final cardData = {
      'type': 'deep_thinking',
      'isLoading': isLoading ?? runtime.isDeepThinking,
      'thinkingContent': thinkingContent ?? '',
      'stage': stage ?? runtime.currentThinkingStage,
      'taskID': taskId,
      'cardId': thinkingCardId,
      'startTime': startTime,
      'endTime': null,
    };

    runtime.messages.removeWhere((msg) => msg.id == thinkingCardId);
    runtime.messages.insert(
      0,
      ChatMessageModel(
        id: thinkingCardId,
        type: 2,
        user: 3,
        content: {'cardData': cardData, 'id': thinkingCardId},
        createAt: DateTime.fromMillisecondsSinceEpoch(startTime),
        streamMeta: ensureAgentStreamMessageMeta(
          streamMeta,
          entryId: thinkingCardId,
        ),
      ),
    );
  }

  String _buildContextCompactionMarkerId({
    required int conversationId,
    String? taskId,
    required String trigger,
  }) {
    final suffix = DateTime.now().millisecondsSinceEpoch;
    final normalizedTaskId = taskId?.trim();
    if (normalizedTaskId != null && normalizedTaskId.isNotEmpty) {
      return '$normalizedTaskId-context-compaction-$suffix';
    }
    return 'conversation-$conversationId-$trigger-context-compaction-$suffix';
  }

  void _upsertContextCompactionMarker(
    ChatConversationRuntimeState runtime, {
    required String markerId,
    required String status,
    String trigger = 'auto',
    int? latestPromptTokens,
    int? promptTokenThreshold,
  }) {
    final index = runtime.messages.indexWhere((msg) => msg.id == markerId);
    final existing = index == -1 ? null : runtime.messages[index];
    final existingCardData = Map<String, dynamic>.from(
      existing?.cardData ?? const <String, dynamic>{},
    );
    final startTime =
        (existingCardData['startTime'] as int?) ??
        DateTime.now().millisecondsSinceEpoch;
    final endTime = status == 'compressing'
        ? null
        : DateTime.now().millisecondsSinceEpoch;
    final resolvedTriggerRaw = (existingCardData['trigger'] ?? trigger)
        .toString()
        .trim();
    final resolvedTrigger = resolvedTriggerRaw.isEmpty
        ? trigger
        : resolvedTriggerRaw;
    final cardData = <String, dynamic>{
      'type': 'context_compaction_marker',
      'status': status,
      'label': _contextCompactionLabel(status),
      'trigger': resolvedTrigger,
      'startTime': startTime,
      'endTime': endTime,
      'latestPromptTokens':
          latestPromptTokens ?? runtime.conversation?.latestPromptTokens,
      'promptTokenThreshold':
          promptTokenThreshold ?? runtime.conversation?.promptTokenThreshold,
    };
    final message = ChatMessageModel(
      id: markerId,
      type: 2,
      user: 3,
      content: {'cardData': cardData, 'id': markerId},
      createAt: DateTime.fromMillisecondsSinceEpoch(startTime),
    );
    if (index == -1) {
      runtime.messages.insert(0, message);
    } else {
      runtime.messages[index] = existing!.copyWith(
        content: {'cardData': cardData, 'id': markerId},
      );
    }
    _persistContextCompactionMarkerIfNeeded(
      conversationId: runtime.conversationId,
      mode: runtime.mode,
      message: index == -1 ? message : runtime.messages[index],
    );
  }

  String _contextCompactionLabel(String status) {
    return switch (status) {
      'compressing' => _isEnglish ? 'Compressing' : '正在压缩',
      'noop' => _isEnglish ? 'No compaction needed' : '无需压缩',
      'failed' => _isEnglish ? 'Compaction failed' : '压缩失败',
      _ => _isEnglish ? 'Compacted' : '已压缩',
    };
  }

  void _persistContextCompactionMarkerIfNeeded({
    required int conversationId,
    required String mode,
    required ChatMessageModel message,
  }) {
    if (isEphemeralRuntime(conversationId: conversationId, mode: mode)) {
      return;
    }
    final cardData = message.cardData;
    if (message.type != 2 || cardData?['type'] != 'context_compaction_marker') {
      return;
    }
    unawaited(
      ConversationHistoryService.upsertConversationUiCard(
        conversationId,
        entryId: message.id,
        cardData: Map<String, dynamic>.from(cardData!),
        createdAtMillis: message.createAt.millisecondsSinceEpoch,
        mode: _conversationModeFromRuntimeMode(
          mode,
          conversation: runtimeFor(
            conversationId: conversationId,
            mode: mode,
          )?.conversation,
        ),
      ),
    );
  }

  void _updateThinkingCard(
    ChatConversationRuntimeState runtime,
    String taskId, {
    String? cardId,
    String? thinkingContent,
    bool? isLoading,
    int? stage,
    Map<String, dynamic>? streamMeta,
    bool lockCompleted = true,
  }) {
    final thinkingCardId = cardId ?? '$taskId-thinking';
    final index = runtime.messages.indexWhere(
      (msg) => msg.id == thinkingCardId,
    );
    if (index == -1) return;

    final existing = runtime.messages[index];
    final content = Map<String, dynamic>.from(existing.content ?? {});
    final cardData = Map<String, dynamic>.from(content['cardData'] ?? {});

    final currentStage = cardData['stage'] as int? ?? 1;
    final targetStage = stage ?? runtime.currentThinkingStage;
    final newStage = (lockCompleted && currentStage == 4) ? 4 : targetStage;

    final startTime = cardData['startTime'] as int?;
    int? endTime = cardData['endTime'] as int?;
    if (newStage == 4 && endTime == null) {
      endTime = DateTime.now().millisecondsSinceEpoch;
    }

    cardData['thinkingContent'] =
        thinkingContent ?? runtime.deepThinkingContent;
    cardData['isLoading'] = isLoading ?? runtime.isDeepThinking;
    cardData['stage'] = newStage;
    cardData['taskID'] = taskId;
    cardData['cardId'] = thinkingCardId;
    cardData['startTime'] = startTime;
    cardData['endTime'] = endTime;

    content['cardData'] = cardData;
    runtime.messages[index] = existing.copyWith(
      content: content,
      streamMeta: ensureAgentStreamMessageMeta(
        streamMeta ?? existing.streamMeta,
        entryId: thinkingCardId,
      ),
    );
  }

  String? _resolveThinkingCardToFinalize(
    AgentStreamReduceResult reduceResult,
    AgentStreamEvent event,
  ) {
    switch (event.kind) {
      case AgentStreamEventKind.thinkingStarted:
      case AgentStreamEventKind.thinkingSnapshot:
        return reduceResult.isNewThinkingEntry
            ? reduceResult.previousThinkingEntryId
            : null;
      case AgentStreamEventKind.textSnapshot:
      case AgentStreamEventKind.retrying:
      case AgentStreamEventKind.toolStarted:
      case AgentStreamEventKind.toolProgress:
      case AgentStreamEventKind.toolCompleted:
      case AgentStreamEventKind.completed:
      case AgentStreamEventKind.error:
      case AgentStreamEventKind.permissionRequired:
      case AgentStreamEventKind.clarifyRequired:
        return reduceResult.previousThinkingEntryId;
    }
  }

  void _finalizeThinkingCard(
    ChatConversationRuntimeState runtime,
    String taskId, {
    String? cardId,
  }) {
    final thinkingCardId = (cardId ?? '').trim();
    if (taskId.trim().isEmpty || thinkingCardId.isEmpty) {
      return;
    }
    final index = runtime.messages.indexWhere(
      (msg) => msg.id == thinkingCardId,
    );
    if (index == -1) {
      return;
    }

    final existing = runtime.messages[index];
    final content = Map<String, dynamic>.from(existing.content ?? const {});
    final cardData = Map<String, dynamic>.from(content['cardData'] ?? const {});
    final currentStageRaw = cardData['stage'];
    final currentStage = currentStageRaw is num
        ? currentStageRaw.toInt()
        : int.tryParse(currentStageRaw?.toString() ?? '');
    final isLoading = cardData['isLoading'] == true;
    if (!isLoading && currentStage == ThinkingStage.complete.value) {
      return;
    }

    cardData['thinkingContent'] =
        cardData['thinkingContent'] ?? runtime.deepThinkingContent;
    cardData['isLoading'] = false;
    cardData['stage'] = ThinkingStage.complete.value;
    cardData['taskID'] = taskId;
    cardData['cardId'] = thinkingCardId;
    cardData['endTime'] ??= DateTime.now().millisecondsSinceEpoch;
    content['cardData'] = cardData;
    runtime.messages[index] = existing.copyWith(content: content);
  }

  void _persistDeepThinkingCardIfNeeded({
    required int conversationId,
    required String mode,
    required ChatMessageModel message,
  }) {
    if (isEphemeralRuntime(conversationId: conversationId, mode: mode)) {
      return;
    }
    final cardData = message.cardData;
    if (message.type != 2 || cardData?['type'] != 'deep_thinking') {
      return;
    }
    unawaited(
      ConversationHistoryService.upsertConversationUiCard(
        conversationId,
        entryId: message.id,
        cardData: buildPersistentDeepThinkingCardData(
          Map<String, dynamic>.from(cardData!),
        ),
        createdAtMillis: message.createAt.millisecondsSinceEpoch,
        mode: _conversationModeFromRuntimeMode(
          mode,
          conversation: runtimeFor(
            conversationId: conversationId,
            mode: mode,
          )?.conversation,
        ),
      ),
    );
  }

  void _finalizeThinkingCardsForTask(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    final endTime = DateTime.now().millisecondsSinceEpoch;
    var touched = false;
    for (var index = 0; index < runtime.messages.length; index++) {
      final message = runtime.messages[index];
      final cardData = message.cardData;
      if (message.type != 2 || cardData?['type'] != 'deep_thinking') {
        continue;
      }
      if ((cardData?['taskID'] ?? '').toString().trim() != taskId) {
        continue;
      }

      final content = Map<String, dynamic>.from(message.content ?? const {});
      final mutableCardData = Map<String, dynamic>.from(cardData ?? const {});
      final currentStageRaw = mutableCardData['stage'];
      final currentStage = currentStageRaw is num
          ? currentStageRaw.toInt()
          : int.tryParse(currentStageRaw?.toString() ?? '');
      final isLoading = mutableCardData['isLoading'] == true;
      if (!isLoading && currentStage == ThinkingStage.complete.value) {
        continue;
      }

      mutableCardData['isLoading'] = false;
      mutableCardData['stage'] = ThinkingStage.complete.value;
      mutableCardData['endTime'] ??= endTime;
      content['cardData'] = mutableCardData;
      runtime.messages[index] = message.copyWith(content: content);
      _persistDeepThinkingCardIfNeeded(
        conversationId: runtime.conversationId,
        mode: runtime.mode,
        message: runtime.messages[index],
      );
      touched = true;
    }
    if (touched) {
      runtime.activeThinkingCardId = null;
      runtime.pendingThinkingRoundSplit = false;
    }
  }

  String _baseThinkingCardId(String taskId) => '$taskId-thinking';

  String? _resolveThinkingCardId(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    if (runtime.activeThinkingCardId != null) {
      return runtime.activeThinkingCardId;
    }
    final baseId = _baseThinkingCardId(taskId);
    final exists = runtime.messages.any((msg) => msg.id == baseId);
    return exists ? baseId : null;
  }

  String? _resolvePendingAgentTextMessageId(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    if (runtime.pendingAgentTextTaskId != taskId) return null;
    for (final message in runtime.messages) {
      if (_isAgentTextMessageForTask(message, taskId)) {
        return message.id;
      }
    }
    return null;
  }

  String _nextAgentTextMessageId(
    ChatConversationRuntimeState runtime,
    String taskId,
  ) {
    final baseId = _agentTextBaseId(taskId);
    var maxSequence = 0;
    for (final message in runtime.messages) {
      final sequence = _agentTextMessageSequence(message.id, taskId);
      if (sequence > maxSequence) {
        maxSequence = sequence;
      }
    }
    if (maxSequence == 0) {
      return baseId;
    }
    return '$baseId-${maxSequence + 1}';
  }

  bool _isAgentTextMessageForTask(ChatMessageModel message, String taskId) {
    if (message.type != 1 || message.user != 2) {
      return false;
    }
    return _agentTextMessageSequence(message.id, taskId) > 0;
  }

  int _agentTextMessageSequence(String messageId, String taskId) {
    final baseId = _agentTextBaseId(taskId);
    if (messageId == baseId) {
      return 1;
    }
    if (!messageId.startsWith('$baseId-')) {
      return 0;
    }
    return int.tryParse(messageId.substring(baseId.length + 1)) ?? 0;
  }

  void _upsertToolCard({
    required ChatConversationRuntimeState runtime,
    required String taskId,
    required String cardId,
    required AgentToolEventData event,
    required String status,
    required String summary,
    required String progress,
    required String resultPreviewJson,
    required String rawResultJson,
    String? reasoningContent,
    Map<String, dynamic>? streamMeta,
  }) {
    final index = runtime.messages.indexWhere((msg) => msg.id == cardId);
    final existingCardData = index == -1
        ? const <String, dynamic>{}
        : Map<String, dynamic>.from(
            runtime.messages[index].cardData ?? const {},
          );
    final existingTerminalOutput = (existingCardData['terminalOutput'] ?? '')
        .toString();
    final isFileChangeTool = _isCodexFileChangeTool(event, existingCardData);
    final effectiveToolType = isFileChangeTool ? 'file' : event.toolType;
    final terminalOutput = effectiveToolType == 'terminal'
        ? _resolveTerminalOutput(existing: existingTerminalOutput, event: event)
        : '';
    final diffSource = _agentToolDiffSource(
      event,
      resultPreviewJson: resultPreviewJson,
      rawResultJson: rawResultJson,
      summary: summary,
      progress: progress,
    );
    final diffText = isFileChangeTool
        ? _resolveAgentFileDiffText(
            existingCardData: existingCardData,
            source: diffSource,
            outputText: terminalOutput,
            progress: progress,
            summary: summary,
          )
        : '';
    final diffSummary = diffText.isEmpty ? null : parseCodexDiffText(diffText);
    final diffPreview = diffSummary == null
        ? ''
        : summarizeCodexDiff(diffSummary);
    final effectiveSummary = isFileChangeTool && diffPreview.isNotEmpty
        ? diffPreview
        : summary.isNotEmpty
        ? summary
        : (existingCardData['summary'] ?? '').toString();
    final effectiveProgress = isFileChangeTool && diffPreview.isNotEmpty
        ? diffPreview
        : progress.isNotEmpty
        ? progress
        : (existingCardData['progress'] ?? '').toString();
    final filePath = isFileChangeTool
        ? extractCodexDiffPath(diffSource) ??
              (diffSummary?.primaryPath.trim().isNotEmpty == true
                  ? diffSummary!.primaryPath
                  : null) ??
              (existingCardData['filePath'] ?? '').toString()
        : '';
    final cardData = <String, dynamic>{
      'type': 'agent_tool_summary',
      'uiStyle': event.uiStyle.isNotEmpty
          ? event.uiStyle
          : (existingCardData['uiStyle'] ?? '').toString(),
      'taskId': taskId,
      'toolName': event.toolName,
      'displayName': event.displayName,
      'toolTitle': event.toolTitle.isNotEmpty
          ? event.toolTitle
          : (existingCardData['toolTitle'] ?? '').toString(),
      'cardId': event.cardId.isNotEmpty
          ? event.cardId
          : (existingCardData['cardId'] ?? cardId).toString(),
      'toolType': effectiveToolType,
      'serverName': event.serverName,
      'status': status,
      'reasoning_content':
          _normalizeReasoningContent(reasoningContent) ??
          (existingCardData['reasoning_content'] ?? '').toString(),
      'summary': effectiveSummary,
      'progress': effectiveProgress,
      'subagentStatusText': event.subagentStatusText.isNotEmpty
          ? event.subagentStatusText
          : (existingCardData['subagentStatusText'] ?? '').toString(),
      'subagentEvents': _mergeSubagentEvents(
        existingCardData['subagentEvents'],
        event.subagentEvents,
      ),
      'argsJson': event.argsJson.isNotEmpty
          ? event.argsJson
          : (existingCardData['argsJson'] ?? '').toString(),
      'resultPreviewJson': resultPreviewJson.isNotEmpty
          ? resultPreviewJson
          : (existingCardData['resultPreviewJson'] ?? '').toString(),
      'rawResultJson': rawResultJson.isNotEmpty
          ? rawResultJson
          : (existingCardData['rawResultJson'] ?? '').toString(),
      'terminalOutput': terminalOutput,
      'terminalOutputDelta': event.terminalOutputDelta,
      'terminalSessionId':
          event.terminalSessionId ?? existingCardData['terminalSessionId'],
      'terminalStreamState': event.terminalStreamState.isNotEmpty
          ? event.terminalStreamState
          : (existingCardData['terminalStreamState'] ?? '').toString(),
      'workspaceId': event.workspaceId ?? existingCardData['workspaceId'],
      'interruptedBy': event.interruptedBy ?? existingCardData['interruptedBy'],
      'interruptionReason':
          event.interruptionReason ?? existingCardData['interruptionReason'],
      'artifacts': event.artifacts.isNotEmpty
          ? event.artifacts
          : (existingCardData['artifacts'] ?? const []),
      'actions': event.actions.isNotEmpty
          ? event.actions
          : (existingCardData['actions'] ?? const []),
      'success': event.success,
      'showTerminalOutput': effectiveToolType == 'terminal',
      'showRawResult': event.rawResultJson.isNotEmpty,
      'showArtifactAction': event.artifacts.isNotEmpty,
      'showScheduleAction': effectiveToolType == 'schedule',
      'showAlarmAction': effectiveToolType == 'alarm',
    };
    if (isFileChangeTool) {
      cardData.addAll(<String, dynamic>{
        'diffText': diffText,
        'showDiff': diffText.isNotEmpty,
        'filePath': filePath,
        'changedFiles': diffSummary?.changedFileCount ?? 0,
        'additions': diffSummary?.additions ?? 0,
        'deletions': diffSummary?.deletions ?? 0,
      });
    }

    if (index == -1) {
      runtime.messages.insert(
        0,
        ChatMessageModel.cardMessage(
          cardData,
          id: cardId,
          streamMeta: ensureAgentStreamMessageMeta(streamMeta, entryId: cardId),
        ),
      );
    } else {
      runtime.messages[index] = runtime.messages[index].copyWith(
        content: {'cardData': cardData, 'id': cardId},
        streamMeta: ensureAgentStreamMessageMeta(
          streamMeta ?? runtime.messages[index].streamMeta,
          entryId: cardId,
        ),
      );
    }
  }

  List<Map<String, dynamic>> _mergeSubagentEvents(
    dynamic existingRaw,
    List<Map<String, dynamic>> incomingEvents,
  ) {
    final merged = <Map<String, dynamic>>[];
    final seen = <String>{};

    void addEvent(Map<dynamic, dynamic> rawEvent) {
      final event = rawEvent.map<String, dynamic>(
        (key, value) => MapEntry(key.toString(), value),
      );
      final key = _subagentEventIdentity(event);
      if (!seen.add(key)) {
        return;
      }
      merged.add(event);
    }

    if (existingRaw is List) {
      for (final item in existingRaw.whereType<Map>()) {
        addEvent(item);
      }
    }
    for (final event in incomingEvents) {
      addEvent(event);
    }
    // 折叠流式累积事件: 同一个 subagent (按 subagentId 或 taskIndex 分组)
    // 的同种流式 kind (thinking / message) 只保留 seq 最大的一条。
    // Kotlin 端为了实现"实时滚动"效果会每 ~32 字符 emit 一次 thinking 事件,
    // 每条 seq 都不同,id-based 去重保留不了它们。展开后会显示"每行字数递增"。
    final folded = _foldStreamingSubagentEvents(merged);
    folded.sort((left, right) {
      final leftSeq = _asInt(left['seq']);
      final rightSeq = _asInt(right['seq']);
      if (leftSeq != null && rightSeq != null && leftSeq != rightSeq) {
        return leftSeq.compareTo(rightSeq);
      }
      final leftCreatedAt = _asInt(left['createdAt']) ?? 0;
      final rightCreatedAt = _asInt(right['createdAt']) ?? 0;
      if (leftCreatedAt != rightCreatedAt) {
        return leftCreatedAt.compareTo(rightCreatedAt);
      }
      return _subagentEventIdentity(
        left,
      ).compareTo(_subagentEventIdentity(right));
    });
    return folded;
  }

  static const Set<String> _streamingSubagentKinds = <String>{
    'thinking',
    'message',
  };

  List<Map<String, dynamic>> _foldStreamingSubagentEvents(
    List<Map<String, dynamic>> events,
  ) {
    final latestByGroup = <String, Map<String, dynamic>>{};
    final result = <Map<String, dynamic>>[];
    for (final event in events) {
      final kind = (event['kind'] ?? '').toString();
      if (!_streamingSubagentKinds.contains(kind)) {
        result.add(event);
        continue;
      }
      final groupKey = _subagentStreamingGroupKey(event, kind);
      final existing = latestByGroup[groupKey];
      if (existing == null) {
        latestByGroup[groupKey] = event;
        continue;
      }
      final existingSeq = _asInt(existing['seq']) ?? -1;
      final newSeq = _asInt(event['seq']) ?? -1;
      if (newSeq >= existingSeq) {
        latestByGroup[groupKey] = event;
      }
    }
    result.addAll(latestByGroup.values);
    return result;
  }

  String _subagentStreamingGroupKey(Map<String, dynamic> event, String kind) {
    final subagentId = (event['subagentId'] ?? '').toString().trim();
    if (subagentId.isNotEmpty) {
      return 'sub:$subagentId|$kind';
    }
    final taskIndex = event['taskIndex'];
    if (taskIndex != null) {
      return 'task:$taskIndex|$kind';
    }
    return 'global|$kind';
  }

  String _subagentEventIdentity(Map<String, dynamic> event) {
    final id = (event['id'] ?? '').toString().trim();
    if (id.isNotEmpty) {
      return id;
    }
    return [
      event['seq'],
      event['kind'],
      event['taskIndex'],
      event['summary'],
      event['toolName'],
    ].map((item) => item?.toString() ?? '').join('|');
  }

  int? _asInt(dynamic value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse((value ?? '').toString());
  }

  String _resolveTerminalOutput({
    required String existing,
    required AgentToolEventData event,
  }) {
    if (event.terminalOutput.isNotEmpty) {
      return _trimTerminalOutput(event.terminalOutput);
    }
    if (event.terminalOutputDelta.isNotEmpty) {
      return _trimTerminalOutput(existing + event.terminalOutputDelta);
    }
    return existing;
  }

  bool _isCodexFileChangeTool(
    AgentToolEventData event,
    Map<String, dynamic> existingCardData,
  ) {
    final toolType = event.toolType.trim();
    if (toolType == 'file' ||
        (existingCardData['toolType'] ?? '').toString().trim() == 'file') {
      return true;
    }
    final toolName = event.toolName.trim();
    if (toolName == 'codex.file') {
      return true;
    }
    if (_valueHasFileChangeType(event.raw)) {
      return true;
    }
    return _jsonHasFileChangeType(event.argsJson) ||
        _jsonHasFileChangeType(event.rawResultJson) ||
        _jsonHasFileChangeType(event.resultPreviewJson);
  }

  bool _jsonHasFileChangeType(String raw) {
    final trimmed = raw.trim();
    if (trimmed.isEmpty) {
      return false;
    }
    try {
      final decoded = jsonDecode(trimmed);
      return _valueHasFileChangeType(decoded);
    } catch (_) {
      return false;
    }
  }

  bool _valueHasFileChangeType(dynamic value) {
    if (value is Map) {
      final map = value.map((key, nested) => MapEntry(key.toString(), nested));
      final type = (map['type'] ?? '').toString();
      if (type == 'fileChange') {
        return true;
      }
      return map.values.any(_valueHasFileChangeType);
    }
    if (value is Iterable) {
      return value.any(_valueHasFileChangeType);
    }
    return false;
  }

  Map<String, dynamic> _agentToolDiffSource(
    AgentToolEventData event, {
    required String resultPreviewJson,
    required String rawResultJson,
    required String summary,
    required String progress,
  }) {
    return <String, dynamic>{
      ...event.raw,
      'toolName': event.toolName,
      'toolType': event.toolType,
      'argsJson': event.argsJson,
      'resultPreviewJson': resultPreviewJson,
      'rawResultJson': rawResultJson,
      'summary': summary,
      'progress': progress,
    };
  }

  String _resolveAgentFileDiffText({
    required Map<String, dynamic> existingCardData,
    required Map<String, dynamic> source,
    required String outputText,
    required String progress,
    required String summary,
  }) {
    final current = extractCodexDiffText(
      source,
      outputText: outputText,
      progress: progress,
      summary: summary,
    );
    if (current != null && current.trim().isNotEmpty) {
      return current;
    }
    return (existingCardData['diffText'] ?? '').toString().trim();
  }

  String _resolveToolStatus(AgentToolEventData event) {
    final normalized = event.status.trim().toLowerCase();
    if (normalized.isNotEmpty) {
      return normalized;
    }
    return event.success ? 'success' : 'error';
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
        : mode == kChatRuntimeModeCodex
        ? ConversationMode.codex
        : switch (conversation?.mode) {
            ConversationMode.chatOnly => ConversationMode.chatOnly,
            ConversationMode.subagent => ConversationMode.subagent,
            _ => ConversationMode.normal,
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
