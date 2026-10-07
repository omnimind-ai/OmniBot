import 'dart:async';
import 'dart:collection';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/agent_identity.dart';
import 'package:ui/services/assists_core_service.dart';

part 'chat_runtime_mirror.dart';
part 'chat_runtime_view.dart';
part 'chat_runtime_event_routing.dart';
part 'chat_prompt_dispatcher.dart';

const String kChatRuntimeModeNormal = 'normal';
const String kChatRuntimeModeOpenClaw = 'openclaw';
const String kChatRuntimeModeAgent = 'agent';

/// Result of projecting one ACP event or prompt response natively.
class AgentReduceResult {
  const AgentReduceResult({
    required this.handled,
    this.method,
    this.threadId,
    this.turnId,
    this.requestId,
    this.collaborationMode,
    this.compatibilityWarning,
    this.affectsActiveTurn = true,
  });

  factory AgentReduceResult.fromChannel(dynamic raw) {
    final map = raw is Map ? raw : const <dynamic, dynamic>{};
    String? text(String key) {
      final value = map[key]?.toString();
      return value == null || value.isEmpty ? null : value;
    }

    return AgentReduceResult(
      handled: map['handled'] == true,
      method: text('method'),
      threadId: text('threadId'),
      turnId: text('turnId'),
      requestId: map['requestId'],
      collaborationMode: text('collaborationMode'),
      compatibilityWarning: text('compatibilityWarning'),
      affectsActiveTurn: map['affectsActiveTurn'] != false,
    );
  }

  final bool handled;
  final String? method;
  final String? threadId;
  final String? turnId;
  final Object? requestId;
  final String? collaborationMode;
  final String? compatibilityWarning;

  /// Whether the event was allowed to mutate the currently active local turn.
  final bool affectsActiveTurn;
}

/// Flutter adapter of the native chat runtime owner.
///
/// The ACP projection (one reducer, one coordinator) lives in the app module
/// (`ChatRuntimeHost` / `ChatConversationRuntimeCoordinator`). This class
/// keeps the API pages already use but only mirrors the immutable snapshots
/// the native owner publishes and forwards every command to it. It never
/// reduces an ACP event and holds no lifecycle state of its own. Page write
/// commands are applied to the mirror at once (plain field and list writes)
/// and confirmed by the next native snapshot.
class ChatConversationRuntimeCoordinator extends ChangeNotifier {
  ChatConversationRuntimeCoordinator._();

  static final ChatConversationRuntimeCoordinator instance =
      ChatConversationRuntimeCoordinator._();

  static const MethodChannel _methodChannel = MethodChannel(
    'cn.com.omnimind.bot/ChatRuntime',
  );
  static const EventChannel _eventChannel = EventChannel(
    'cn.com.omnimind.bot/ChatRuntimeEvents',
  );

  final Map<String, _ChatRuntimeMirror> _mirrors =
      <String, _ChatRuntimeMirror>{};

  /// Last applied native revision per runtime key, removals included.
  final Map<String, int> _revisions = <String, int>{};
  final List<ChatRuntimeEventHost> _eventHosts = <ChatRuntimeEventHost>[];
  final Set<String> _resyncRequested = <String>{};
  StreamSubscription<dynamic>? _eventSubscription;
  bool _frameCallbackRegistered = false;
  int _nextHostId = 0;
  String? _lastRoutingSignature;
  bool _initialized = false;

  void ensureInitialized() {
    if (_initialized) return;
    _initialized = true;
    // The chat runtime has always installed the shared AssistCore handler
    // (conversation list/message changes, card pushes) on first use.
    AssistsMessageService.initialize();
    _eventSubscription = _eventChannel.receiveBroadcastStream().listen(
      _handleNativeEvent,
      onError: (Object error) =>
          debugPrint('[ChatRuntime] native event stream error: $error'),
    );
  }

  // ---------------------------------------------------------------- reads

  /// Read-only view of a runtime. Callers change it only through the
  /// coordinator commands below.
  ChatRuntimeView? runtimeFor({
    required int conversationId,
    required String mode,
  }) {
    return _mirrors[_runtimeKey(conversationId: conversationId, mode: mode)]
        ?.view;
  }

  /// Conversation ids with live work in the shared ACP projection.
  Set<int> get activeAgentConversationIds => Set.unmodifiable(
    _mirrors.values
        .where(
          (runtime) =>
              runtime.mode == kChatRuntimeModeAgent && runtime.hasInFlightTask,
        )
        .map((runtime) => runtime.conversationId),
  );

  /// True while any runtime, in any mode, has live work. A shared Provider
  /// model change reconnects the ACP runtime and would end those turns.
  bool get hasAnyInFlightTask =>
      _mirrors.values.any((runtime) => runtime.hasInFlightTask);

  bool isAgentConversationActive(int conversationId) {
    return runtimeFor(
          conversationId: conversationId,
          mode: kChatRuntimeModeAgent,
        )?.hasInFlightTask ??
        false;
  }

  bool isEphemeralRuntime({required int conversationId, required String mode}) {
    return _mirrors[_runtimeKey(conversationId: conversationId, mode: mode)]
            ?.isEphemeral ??
        false;
  }

  /// Whether [taskId] still owns the live local turn of this runtime, as of
  /// the latest native snapshot.
  bool isTaskActive({
    required String taskId,
    required int conversationId,
    required String mode,
  }) {
    final runtime =
        _mirrors[_runtimeKey(conversationId: conversationId, mode: mode)];
    if (runtime == null || !runtime.boundTaskIds.contains(taskId)) {
      return false;
    }
    return runtime.activeRunId == taskId || runtime.lastAgentTurnId == taskId;
  }

  // ------------------------------------------------------------ lifecycle

  ChatRuntimeView ensureRuntime({
    required int conversationId,
    required String mode,
    List<ChatMessageModel>? initialMessages,
    ConversationModel? conversation,
    ChatIslandDisplayLayer? initialChatIslandDisplayLayer,
  }) {
    return _ensure(
      'ensureRuntime',
      conversationId: conversationId,
      mode: mode,
      initialMessages: initialMessages,
      conversation: conversation,
      initialChatIslandDisplayLayer: initialChatIslandDisplayLayer,
    ).view;
  }

  ChatRuntimeView ensureEphemeralRuntime({
    required int conversationId,
    required String mode,
    List<ChatMessageModel>? initialMessages,
    ConversationModel? conversation,
    ChatIslandDisplayLayer? initialChatIslandDisplayLayer,
  }) {
    final mirror = _ensure(
      'ensureEphemeralRuntime',
      conversationId: conversationId,
      mode: mode,
      initialMessages: initialMessages,
      conversation: conversation,
      initialChatIslandDisplayLayer: initialChatIslandDisplayLayer,
    );
    mirror.isEphemeral = true;
    return mirror.view;
  }

  _ChatRuntimeMirror _ensure(
    String method, {
    required int conversationId,
    required String mode,
    List<ChatMessageModel>? initialMessages,
    ConversationModel? conversation,
    ChatIslandDisplayLayer? initialChatIslandDisplayLayer,
  }) {
    final mirror = _mirrorFor(conversationId, mode, create: true)!;
    if (mirror.revision == 0) {
      if (initialChatIslandDisplayLayer != null) {
        mirror.chatIslandDisplayLayer = initialChatIslandDisplayLayer;
      }
      if (mirror.messages.isEmpty && initialMessages != null) {
        initialMessages.forEach(mirror.rememberMessage);
        mirror.messages.addAll(initialMessages);
      }
    }
    if (conversation != null) mirror.conversation = conversation;
    _send(method, <String, dynamic>{
      ..._target(conversationId, mode),
      if (initialMessages != null)
        'initialMessages': initialMessages.map(chatMessageToChannel).toList(),
      if (conversation != null) 'conversation': conversation.toJson(),
      if (initialChatIslandDisplayLayer != null)
        'initialChatIslandDisplayLayer': initialChatIslandDisplayLayer.wireName,
    });
    return mirror;
  }

  void registerTask({
    required String taskId,
    required int conversationId,
    required String mode,
  }) {
    _publishRoutingContexts();
    _send('registerTask', <String, dynamic>{
      ..._target(conversationId, mode),
      'taskId': taskId,
    });
  }

  void beginAcpTurn({
    required String taskId,
    required int conversationId,
    required String mode,
  }) {
    _publishRoutingContexts();
    _send('beginAcpTurn', <String, dynamic>{
      ..._target(conversationId, mode),
      'taskId': taskId,
    });
  }

  /// Records the official ACP session after `session/new` and before
  /// `session/prompt`: an identity reservation, not a second lifecycle.
  Future<bool> bindAcpSession({
    required String taskId,
    required int conversationId,
    required String mode,
    required String sessionId,
  }) async {
    _publishRoutingContexts();
    final result = await _invoke('bindAcpSession', <String, dynamic>{
      ..._target(conversationId, mode),
      'taskId': taskId,
      'sessionId': sessionId,
    });
    return result == true;
  }

  /// Commits a terminal transition proven by an authoritative remote session
  /// snapshot (remote ACP read path only).
  Future<bool> finishTaskFromAuthoritativeSnapshot({
    required String taskId,
    required int conversationId,
    required String mode,
    String? sessionId,
    String? turnId,
  }) async {
    final result = await _invoke(
      'finishTaskFromAuthoritativeSnapshot',
      <String, dynamic>{
        ..._target(conversationId, mode),
        'taskId': taskId,
        'sessionId': ?sessionId,
        'turnId': ?turnId,
      },
    );
    return result == true;
  }

  /// Releases [taskId]'s reservation. Callers that read the runtime right
  /// afterwards await the returned future: it completes once the mirror
  /// includes the release.
  Future<void> unregisterTask(
    String taskId, {
    int? conversationId,
    String? mode,
  }) async {
    try {
      await _invoke('unregisterTask', <String, dynamic>{
        'taskId': taskId,
        'conversationId': ?conversationId,
        'mode': ?mode,
      });
    } catch (error) {
      debugPrint('[ChatRuntime] unregisterTask failed: $error');
    }
  }

  /// Applies the official ACP `session/prompt` result through the native
  /// reducer: the canonical terminal boundary, never a synthesized event.
  Future<AgentReduceResult> applyAcpPromptResponse({
    required String taskId,
    required int conversationId,
    required String? sessionId,
    String? turnId,
    String? stopReason,
    String? error,
    String mode = kChatRuntimeModeAgent,
    ConversationModel? conversation,
  }) async {
    final result = await _invoke('applyAcpPromptResponse', <String, dynamic>{
      ..._target(conversationId, mode),
      'taskId': taskId,
      'sessionId': sessionId,
      'turnId': turnId,
      'stopReason': stopReason,
      'error': error,
      if (conversation != null) 'conversation': conversation.toJson(),
    });
    return AgentReduceResult.fromChannel(result);
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
  /// first official ACP update.
  void clearTaskThinkingPresentation({
    required String taskId,
    required int conversationId,
    required String mode,
    bool removeCard = true,
  }) {
    _send('clearTaskThinkingPresentation', <String, dynamic>{
      ..._target(conversationId, mode),
      'taskId': taskId,
      'removeCard': removeCard,
    });
  }

  void clearConversationRuntimeSession({
    required int conversationId,
    required String mode,
  }) {
    _send('clearConversationRuntimeSession', _target(conversationId, mode));
  }

  void discardConversationRuntime({
    required int conversationId,
    required String mode,
  }) {
    _send('discardConversationRuntime', _target(conversationId, mode));
  }

  void interruptActiveToolCard({
    required int conversationId,
    required String mode,
    String? summary,
  }) {
    _send('interruptActiveToolCard', <String, dynamic>{
      ..._target(conversationId, mode),
      'summary': ?summary,
    });
  }

  void beginContextCompaction({
    required int conversationId,
    required String mode,
    String? taskId,
    String trigger = 'manual',
    int? latestPromptTokens,
    int? promptTokenThreshold,
  }) {
    _send('beginContextCompaction', <String, dynamic>{
      ..._target(conversationId, mode),
      'taskId': ?taskId,
      'trigger': trigger,
      'latestPromptTokens': ?latestPromptTokens,
      'promptTokenThreshold': ?promptTokenThreshold,
    });
  }

  void finishContextCompaction({
    required int conversationId,
    required String mode,
    String status = 'completed',
    int? latestPromptTokens,
    int? promptTokenThreshold,
  }) {
    _send('finishContextCompaction', <String, dynamic>{
      ..._target(conversationId, mode),
      'status': status,
      'latestPromptTokens': ?latestPromptTokens,
      'promptTokenThreshold': ?promptTokenThreshold,
    });
  }

  void updateChatIslandDisplayLayer({
    required int conversationId,
    required String mode,
    required ChatIslandDisplayLayer layer,
  }) {
    _send('updateChatIslandDisplayLayer', <String, dynamic>{
      ..._target(conversationId, mode),
      'layer': layer.wireName,
    });
  }

  // ------------------------------------------------------------- snapshot

  /// Installs a page/history snapshot. The native owner treats it as a live
  /// refresh (merging new items only) when its runtime moved on since the
  /// mirror revision this snapshot was built from. Text caches are native
  /// owned and never echoed back.
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
    final basedOn = _mirrors[_runtimeKey(
      conversationId: conversationId,
      mode: mode,
    )]?.revision;
    _send('replaceConversationSnapshot', <String, dynamic>{
      ..._target(conversationId, mode),
      'messages': messages.map(chatMessageToChannel).toList(),
      if (conversation != null) 'conversation': conversation.toJson(),
      'isAiResponding': isAiResponding,
      'isContextCompressing': isContextCompressing,
      'isCheckingExecutableTask': isCheckingExecutableTask,
      'deepThinkingContent': deepThinkingContent,
      'isDeepThinking': isDeepThinking,
      'currentDispatchTurnId': currentDispatchTurnId,
      'currentThinkingStage': currentThinkingStage,
      'isInputAreaVisible': isInputAreaVisible,
      'isExecutingTask': isExecutingTask,
      'lastAgentTurnId': lastAgentTurnId,
      'activeToolCardId': activeToolCardId,
      'activeThinkingCardId': activeThinkingCardId,
      'activeContextCompactionMarkerId': activeContextCompactionMarkerId,
      'pendingAgentTextTaskId': pendingAgentTextTaskId,
      'pendingThinkingRoundSplit': pendingThinkingRoundSplit,
      'toolCardSequence': toolCardSequence,
      'thinkingRound': thinkingRound,
      'chatIslandDisplayLayer': chatIslandDisplayLayer.wireName,
      'lastAgentToolType': lastAgentToolType,
      'browserSessionSnapshot': browserSessionSnapshot?.toMap(),
      'preserveLiveStreamingState': preserveLiveStreamingState,
      if (basedOn != null && basedOn > 0) 'basedOnRevision': basedOn,
    });
  }

  /// Updates the runtime projection and persists the same snapshot. With a
  /// live turn the native owner merges by id so a stale page snapshot cannot
  /// erase streamed items.
  Future<void> persistConversationMessageSnapshot({
    required int conversationId,
    required String mode,
    required List<ChatMessageModel> messages,
    ConversationModel? conversation,
    bool allowHistoryRemoval = false,
  }) async {
    await _invoke('persistConversationMessageSnapshot', <String, dynamic>{
      ..._target(conversationId, mode),
      'messages': messages.map(chatMessageToChannel).toList(),
      if (conversation != null) 'conversation': conversation.toJson(),
      'allowHistoryRemoval': allowHistoryRemoval,
    });
  }

  Future<void> persistRuntimeConversation({
    required int conversationId,
    required String mode,
    bool generateSummary = false,
    bool markComplete = false,
    bool persistMessages = false,
    bool allowEphemeralPersistence = false,
    bool allowHistoryRemoval = false,
  }) async {
    await _invoke('persistRuntimeConversation', <String, dynamic>{
      ..._target(conversationId, mode),
      'generateSummary': generateSummary,
      'markComplete': markComplete,
      'persistMessages': persistMessages,
      'allowEphemeralPersistence': allowEphemeralPersistence,
      'allowHistoryRemoval': allowHistoryRemoval,
    });
  }

  void schedulePersistRuntimeConversation({
    required int conversationId,
    required String mode,
    bool generateSummary = false,
    bool markComplete = false,
    bool persistMessages = false,
    Duration delay = const Duration(milliseconds: 350),
  }) {
    _send('schedulePersistRuntimeConversation', <String, dynamic>{
      ..._target(conversationId, mode),
      'generateSummary': generateSummary,
      'markComplete': markComplete,
      'persistMessages': persistMessages,
      'delayMillis': delay.inMilliseconds,
    });
  }

  Future<void> flushPendingPersistence({
    required int conversationId,
    required String mode,
  }) async {
    await _methodChannel.invokeMethod<void>(
      'flushPendingPersistence',
      _target(conversationId, mode),
    );
  }

  Future<void> flushAllPendingPersistence() async {
    await _methodChannel.invokeMethod<void>('flushAllPendingPersistence');
  }

  // ------------------------------------------------------- page commands

  // Plain field and list writes. Applied to the mirror at once (the caller
  // rebuilds, as with the direct writes they replace) and forwarded to the
  // native owner, whose next snapshot confirms them.

  void updateRuntimePresentation({
    required int conversationId,
    required String mode,
    bool? isAiResponding,
    bool? isContextCompressing,
    bool? isCheckingExecutableTask,
    bool? isExecutingTask,
    bool? isInputAreaVisible,
    bool? isDeepThinking,
    String? deepThinkingContent,
    int? currentThinkingStage,
    ChatIslandDisplayLayer? chatIslandDisplayLayer,
  }) {
    final runtime = _mirrorFor(conversationId, mode);
    if (runtime == null) return;
    if (isAiResponding != null) runtime.isAiResponding = isAiResponding;
    if (isContextCompressing != null) {
      runtime.isContextCompressing = isContextCompressing;
    }
    if (isCheckingExecutableTask != null) {
      runtime.isCheckingExecutableTask = isCheckingExecutableTask;
    }
    if (isExecutingTask != null) runtime.isExecutingTask = isExecutingTask;
    if (isInputAreaVisible != null) {
      runtime.isInputAreaVisible = isInputAreaVisible;
    }
    if (isDeepThinking != null) runtime.isDeepThinking = isDeepThinking;
    if (deepThinkingContent != null) {
      runtime.deepThinkingContent = deepThinkingContent;
    }
    if (currentThinkingStage != null) {
      runtime.currentThinkingStage = currentThinkingStage;
    }
    if (chatIslandDisplayLayer != null) {
      runtime.chatIslandDisplayLayer = chatIslandDisplayLayer;
    }
    _send('updateRuntimePresentation', <String, dynamic>{
      ..._target(conversationId, mode),
      'isAiResponding': ?isAiResponding,
      'isContextCompressing': ?isContextCompressing,
      'isCheckingExecutableTask': ?isCheckingExecutableTask,
      'isExecutingTask': ?isExecutingTask,
      'isInputAreaVisible': ?isInputAreaVisible,
      'isDeepThinking': ?isDeepThinking,
      'deepThinkingContent': ?deepThinkingContent,
      'currentThinkingStage': ?currentThinkingStage,
      'chatIslandDisplayLayer': ?chatIslandDisplayLayer?.wireName,
    });
  }

  void setRuntimeDispatchTurnId({
    required int conversationId,
    required String mode,
    required String? turnId,
  }) {
    final runtime = _mirrorFor(conversationId, mode);
    if (runtime == null) return;
    runtime.activeRunId = turnId;
    _send('setRuntimeDispatchTurnId', <String, dynamic>{
      ..._target(conversationId, mode),
      'turnId': turnId,
    });
  }

  void setRuntimeLastAgentToolType({
    required int conversationId,
    required String mode,
    required String? toolType,
  }) {
    final runtime = _mirrorFor(conversationId, mode);
    if (runtime == null) return;
    runtime.lastAgentToolType = toolType;
    _send('setRuntimeLastAgentToolType', <String, dynamic>{
      ..._target(conversationId, mode),
      'toolType': toolType,
    });
  }

  void setRuntimeBrowserSessionSnapshot({
    required int conversationId,
    required String mode,
    required ChatBrowserSessionSnapshot? snapshot,
  }) {
    final runtime = _mirrorFor(conversationId, mode);
    if (runtime == null) return;
    runtime.browserSessionSnapshot = snapshot;
    _send('setRuntimeBrowserSessionSnapshot', <String, dynamic>{
      ..._target(conversationId, mode),
      'snapshot': snapshot?.toMap(),
    });
  }

  void setRuntimeConversation({
    required int conversationId,
    required String mode,
    required ConversationModel? conversation,
  }) {
    final runtime = _mirrorFor(conversationId, mode);
    if (runtime == null) return;
    runtime.conversation = conversation;
    _send('setRuntimeConversation', <String, dynamic>{
      ..._target(conversationId, mode),
      'conversation': conversation?.toJson(),
    });
  }

  /// Inserts [message] at [index] (newest first). An existing message with
  /// the same id is replaced in place instead.
  void insertRuntimeMessage({
    required int conversationId,
    required String mode,
    required ChatMessageModel message,
    int index = 0,
  }) {
    final runtime = _mirrorFor(conversationId, mode);
    if (runtime == null) return;
    runtime.rememberMessage(message);
    runtime.messages.insert(index.clamp(0, runtime.messages.length), message);
    _send('insertRuntimeMessage', <String, dynamic>{
      ..._target(conversationId, mode),
      'message': chatMessageToChannel(message),
      'index': index,
    });
  }

  /// Appends older messages after the current ones; existing ids are
  /// replaced in place.
  void appendRuntimeMessages({
    required int conversationId,
    required String mode,
    required Iterable<ChatMessageModel> messages,
  }) {
    final runtime = _mirrorFor(conversationId, mode);
    if (runtime == null) return;
    final items = List<ChatMessageModel>.from(messages);
    items.forEach(runtime.rememberMessage);
    runtime.messages.addAll(items);
    _send('appendRuntimeMessages', <String, dynamic>{
      ..._target(conversationId, mode),
      'messages': items.map(chatMessageToChannel).toList(),
    });
  }

  /// Replaces the message identified by [messageId]. Returns false when the
  /// runtime or message is gone.
  bool replaceRuntimeMessage({
    required int conversationId,
    required String mode,
    required String messageId,
    required ChatMessageModel message,
  }) {
    final runtime = _mirrorFor(conversationId, mode);
    if (runtime == null) return false;
    final index = runtime.messages.indexWhere((item) => item.id == messageId);
    if (index < 0) return false;
    runtime.rememberMessage(message);
    runtime.messages[index] = message;
    _send('replaceRuntimeMessage', <String, dynamic>{
      ..._target(conversationId, mode),
      'messageId': messageId,
      'message': chatMessageToChannel(message),
    });
    return true;
  }

  void removeRuntimeMessages({
    required int conversationId,
    required String mode,
    required Iterable<String> messageIds,
  }) {
    final ids = messageIds.toSet();
    if (ids.isEmpty) return;
    final runtime = _mirrorFor(conversationId, mode);
    if (runtime == null) return;
    runtime.messages.removeWhere((message) => ids.contains(message.id));
    _send('removeRuntimeMessages', <String, dynamic>{
      ..._target(conversationId, mode),
      'messageIds': ids.toList(),
    });
  }

  /// Removes the [count] newest messages.
  void removeLeadingRuntimeMessages({
    required int conversationId,
    required String mode,
    required int count,
  }) {
    final runtime = _mirrorFor(conversationId, mode);
    if (runtime == null || count <= 0) return;
    runtime.messages.removeRange(0, count.clamp(0, runtime.messages.length));
    _send('removeLeadingRuntimeMessages', <String, dynamic>{
      ..._target(conversationId, mode),
      'count': count,
    });
  }

  /// Replaces every message, e.g. after a history reload.
  void replaceRuntimeMessages({
    required int conversationId,
    required String mode,
    required Iterable<ChatMessageModel> messages,
  }) {
    final runtime = _mirrorFor(conversationId, mode);
    if (runtime == null) return;
    final items = List<ChatMessageModel>.from(messages);
    items.forEach(runtime.rememberMessage);
    runtime.messages
      ..clear()
      ..addAll(items);
    _send('replaceRuntimeMessages', <String, dynamic>{
      ..._target(conversationId, mode),
      'messages': items.map(chatMessageToChannel).toList(),
    });
  }

  // ------------------------------------------------------ remote threads

  /// Ensures the ephemeral runtime that mirrors a remote Agent thread.
  int ensureRemoteThreadRuntime(String threadId) {
    final runtimeId = remoteAgentRuntimeIdForThread(threadId.trim());
    _mirrorFor(runtimeId, kChatRuntimeModeAgent, create: true)!.isEphemeral =
        true;
    _send('ensureRemoteThreadRuntime', <String, dynamic>{
      'threadId': threadId,
    });
    return runtimeId;
  }

  /// Makes a remote thread's runtime the visible Agent runtime, carrying
  /// over the page-local messages and conversation shown before it existed.
  int activateRemoteThreadRuntime(
    String threadId, {
    List<ChatMessageModel> fallbackMessages = const <ChatMessageModel>[],
    ConversationModel? conversation,
  }) {
    final runtimeId = remoteAgentRuntimeIdForThread(threadId.trim());
    final mirror = _mirrorFor(runtimeId, kChatRuntimeModeAgent, create: true)!
      ..isEphemeral = true;
    if (conversation != null) {
      mirror.conversation = conversation.copyWith(id: runtimeId);
    }
    _send('activateRemoteThreadRuntime', <String, dynamic>{
      'threadId': threadId,
      'fallbackMessages': fallbackMessages.map(chatMessageToChannel).toList(),
      if (conversation != null) 'conversation': conversation.toJson(),
    });
    return runtimeId;
  }

  // -------------------------------------------------------- event route

  /// Attaches a mounted chat surface to the native runtime event route.
  ///
  /// The native owner projects every ACP event; the surface publishes its
  /// attribution facts ([context]) and receives each applied event's outcome
  /// for presentation-only follow-up.
  ChatRuntimeEventHost attachEventHost({
    required ChatRuntimeRoutingContext? Function() context,
    required void Function(ChatRuntimeEventOutcome outcome) onOutcome,
  }) {
    ensureInitialized();
    final host = ChatRuntimeEventHost._(
      _nextHostId++,
      this,
      context,
      onOutcome,
    );
    _eventHosts.add(host);
    if (!_frameCallbackRegistered) {
      _frameCallbackRegistered = true;
      // Routing facts follow page state: publish them after each frame (only
      // when they changed) so the native router sees current facts.
      SchedulerBinding.instance.addPersistentFrameCallback(
        (_) => _publishRoutingContexts(),
      );
    }
    _publishRoutingContexts();
    return host;
  }

  void _detachEventHost(ChatRuntimeEventHost host) {
    if (_eventHosts.remove(host)) _publishRoutingContexts();
  }

  void _publishRoutingContexts() {
    final contexts = <Map<String, dynamic>>[];
    final signatureParts = <Object?>[];
    for (final host in _eventHosts) {
      final context = host._context();
      if (context == null) continue;
      final encoded = _routingContextToChannel(host._id, context);
      contexts.add(encoded);
      final remote = context.remote;
      signatureParts.add(<String, dynamic>{
        ...encoded,
        if (remote != null)
          'remote': <String, dynamic>{
            ...(encoded['remote'] as Map<String, dynamic>),
            'agentFallbackMessages': remote.agentFallbackMessages
                .map((message) => '${message.id}@${identityHashCode(message)}')
                .toList(),
          },
      });
    }
    final String signature;
    try {
      signature = jsonEncode(signatureParts);
    } catch (_) {
      return;
    }
    if (signature == _lastRoutingSignature) return;
    _lastRoutingSignature = signature;
    _send('setRoutingContexts', <String, dynamic>{'contexts': contexts});
  }

  void _handleNativeEvent(dynamic raw) {
    if (raw is! Map) return;
    switch (raw['type']) {
      case 'sync':
        if (_applySync(raw)) notifyListeners();
      case 'outcome':
        final event = _ChatRuntimeMirror._asMap(raw['event']);
        final conversationId = _ChatRuntimeMirror._asInt(
          raw['conversationId'],
        );
        if (event == null || conversationId == null) return;
        final outcome = ChatRuntimeEventOutcome(
          event: event,
          conversationId: conversationId,
          mode: raw['mode']?.toString() ?? kChatRuntimeModeAgent,
          result: AgentReduceResult.fromChannel(raw['result']),
          promotedRemoteThreadId: raw['promotedRemoteThreadId']?.toString(),
        );
        for (final host in List<ChatRuntimeEventHost>.from(_eventHosts)) {
          if (_eventHosts.contains(host)) host._onOutcome(outcome);
        }
    }
  }

  /// Applies one native batch. Snapshots and removals are ordered by their
  /// coordinator-wide revision, so a late batch never rolls a runtime back.
  bool _applySync(Map<dynamic, dynamic> batch) {
    var changed = false;
    final removed = batch['removed'];
    if (removed is Map) {
      for (final entry in removed.entries) {
        final key = entry.key.toString();
        final revision = _ChatRuntimeMirror._asInt(entry.value) ?? 0;
        if (revision <= (_revisions[key] ?? 0)) continue;
        _revisions[key] = revision;
        _mirrors.remove(key)?.dispose();
        changed = true;
      }
    }
    final snapshots = batch['snapshots'];
    if (snapshots is List) {
      for (final item in snapshots) {
        final snapshot = _ChatRuntimeMirror._asMap(item);
        if (snapshot == null) continue;
        final conversationId = _ChatRuntimeMirror._asInt(
          snapshot['conversationId'],
        );
        final mode = snapshot['mode']?.toString();
        final revision = _ChatRuntimeMirror._asInt(snapshot['revision']) ?? 0;
        if (conversationId == null || mode == null) continue;
        final key = _runtimeKey(conversationId: conversationId, mode: mode);
        if (revision <= (_revisions[key] ?? 0)) continue;
        final mirror = _mirrorFor(conversationId, mode, create: true)!;
        if (!mirror.applySnapshot(snapshot)) {
          if (_resyncRequested.add(key)) {
            _send('resync', _target(conversationId, mode));
          }
          continue;
        }
        _resyncRequested.remove(key);
        _revisions[key] = revision;
        changed = true;
      }
    }
    return changed;
  }

  // ---------------------------------------------------------- transport

  Map<String, dynamic> _target(int conversationId, String mode) =>
      <String, dynamic>{'conversationId': conversationId, 'mode': mode};

  /// Fire-and-forget command. Commands reach the native owner in call order.
  void _send(String method, Map<String, dynamic> args) {
    unawaited(
      _invoke(method, args).catchError((Object error) {
        debugPrint('[ChatRuntime] $method failed: $error');
        return null;
      }),
    );
  }

  /// Runs one native command. The mirror includes the command's own change
  /// before the returned future completes.
  Future<dynamic> _invoke(String method, Map<String, dynamic> args) async {
    ensureInitialized();
    final response = await _methodChannel.invokeMethod<dynamic>(method, args);
    if (response is! Map) return response;
    var changed = false;
    final sync = response['sync'];
    if (sync is List) {
      for (final batch in sync) {
        if (batch is Map && _applySync(batch)) changed = true;
      }
    }
    if (changed) notifyListeners();
    return response['result'];
  }

  _ChatRuntimeMirror? _mirrorFor(
    int conversationId,
    String mode, {
    bool create = false,
  }) {
    final key = _runtimeKey(conversationId: conversationId, mode: mode);
    final existing = _mirrors[key];
    if (existing != null || !create) return existing;
    return _mirrors[key] = _ChatRuntimeMirror(
      conversationId: conversationId,
      mode: mode,
    );
  }

  String _runtimeKey({required int conversationId, required String mode}) {
    return '$mode:$conversationId';
  }

  /// Delivers a native event directly (tests stand in for the channel).
  @visibleForTesting
  void debugHandleNativeEvent(Map<String, dynamic> event) =>
      _handleNativeEvent(event);

  @visibleForTesting
  void resetForTest() {
    for (final mirror in _mirrors.values) {
      mirror.dispose();
    }
    _mirrors.clear();
    _revisions.clear();
    _resyncRequested.clear();
    _eventHosts.clear();
    _lastRoutingSignature = null;
    unawaited(_eventSubscription?.cancel());
    _eventSubscription = null;
    _initialized = false;
  }
}
