import 'dart:async';
import 'dart:collection';
import 'dart:convert';
import 'dart:math' as math;
import 'dart:ui';

import 'package:flutter/foundation.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/features/home/pages/chat/utils/chat_message_identity.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/services/agent_message_kinds.dart';

enum ThinkingStage {
  thinking(1),
  toolCall(2),
  executing(3),
  complete(4),
  cancelled(5);

  const ThinkingStage(this.value);

  final int value;

  static ThinkingStage fromValue(int value) {
    return ThinkingStage.values.firstWhere(
      (stage) => stage.value == value,
      orElse: () => ThinkingStage.thinking,
    );
  }
}

enum ChatIslandDisplayLayer {
  mode('mode'),
  tools('tools');

  const ChatIslandDisplayLayer(this.wireName);

  final String wireName;

  static ChatIslandDisplayLayer fromWireName(String? value) {
    final normalized = value?.trim().toLowerCase() ?? '';
    if (normalized == 'model') {
      return ChatIslandDisplayLayer.tools;
    }
    for (final layer in ChatIslandDisplayLayer.values) {
      if (layer.wireName == normalized) {
        return layer;
      }
    }
    return ChatIslandDisplayLayer.mode;
  }
}

enum ChatMessageListMutationKind { none, content, structure }

/// Prevents a second selector open from entering while the first is awaiting
/// its catalog refresh and has not created an overlay handle yet.
class ConversationModelSelectorOpeningGuard {
  bool _opening = false;

  bool get isOpening => _opening;

  bool tryBegin() {
    if (_opening) return false;
    _opening = true;
    return true;
  }

  void finish() {
    _opening = false;
  }
}

/// Keeps user submits behind an in-progress Harness lifecycle transition.
///
/// A submit may arrive while the native ACP adapter is selected but before
/// the new conversation target has been installed. Waiting here prevents the
/// prompt from being registered against the previous conversation runtime.
class HarnessSwitchSendBarrier {
  Completer<void>? _pending;
  Future<void> _serializedTail = Future<void>.value();
  int _generation = 0;

  bool get isActive => _pending != null;

  int begin() {
    _generation += 1;
    _pending ??= Completer<void>();
    return _generation;
  }

  bool isCurrent(int generation) => generation == _generation;

  /// Serializes native Harness selection while keeping only the latest tap.
  ///
  /// Native ACP process selection is not cancellable. If a second tap arrives
  /// while the first selection is running, it waits for the first mutation to
  /// finish and then applies the latest selection. Stale queued work exits
  /// without touching the runtime.
  Future<T?> runIfCurrent<T>(
    int generation,
    Future<T> Function() operation,
  ) async {
    final predecessor = _serializedTail;
    final release = Completer<void>();
    _serializedTail = release.future;
    try {
      await predecessor;
      if (!isCurrent(generation)) {
        return null;
      }
      return await operation();
    } finally {
      if (!release.isCompleted) {
        release.complete();
      }
    }
  }

  Future<void> waitUntilIdle() async {
    final pending = _pending;
    if (pending != null) {
      await pending.future;
    }
  }

  void finish(int generation) {
    if (!isCurrent(generation)) {
      return;
    }
    final pending = _pending;
    _pending = null;
    if (pending != null && !pending.isCompleted) {
      pending.complete();
    }
  }
}

ConversationThreadTarget buildHarnessSwitchTarget({
  required String agentId,
  required String agentRuntime,
  required String requestKey,
}) {
  return ConversationThreadTarget.newConversation(
    // `normal` remains readable as a legacy storage alias, but every live
    // Harness uses the single Agent runtime so ACP events and cards cannot be
    // projected into a hidden page-mode runtime during a switch.
    mode: ConversationMode.agent,
    requestKey: requestKey,
    agentId: agentId,
    agentRuntime: agentRuntime,
  );
}

bool shouldReloadConversationMessagesChanged({
  required String? reason,
  required bool hasInFlightTask,
  bool hasRuntimeMessages = false,
  bool suppressLocalSnapshotEcho = false,
}) {
  final isRuntimeStreamSnapshot =
      reason == 'agent_stream_snapshot' ||
      reason == 'chat_task_stream_snapshot';
  if (isRuntimeStreamSnapshot && (hasInFlightTask || hasRuntimeMessages)) {
    return false;
  }
  // A local send persists its optimistic user row through the native history
  // channel, which echoes a generic messages_replaced event. While the turn is
  // active that database snapshot can still be older than the live runtime;
  // reloading it would make the just-sent user message flash away. External
  // device messages use the explicit external_user_message reason and remain
  // reloadable below.
  if (reason == 'messages_replaced' && hasInFlightTask) {
    return false;
  }
  if (reason == 'messages_replaced' && suppressLocalSnapshotEcho) {
    return false;
  }
  return true;
}

bool shouldPreferInMemoryForConversationListChanged({
  required bool hasInFlightTask,
  required bool hasRuntimeMessages,
}) => hasInFlightTask || hasRuntimeMessages;

/// Resolves the message source used by the visible chat surface.
///
/// A newly persisted conversation can briefly acquire an empty runtime before
/// the already-visible draft messages are copied into it. Prefer the populated
/// fallback during that hand-off so the chat never flashes back to its empty
/// greeting after the user presses send. Once the runtime has any messages it
/// remains the authoritative source, including when the fallback is stale.
List<ChatMessageModel> resolveVisibleChatMessages({
  required List<ChatMessageModel>? runtimeMessages,
  required List<ChatMessageModel> fallbackMessages,
  bool preserveFallbackDuringHandoff = false,
}) {
  if (runtimeMessages == null ||
      (preserveFallbackDuringHandoff &&
          runtimeMessages.isEmpty &&
          fallbackMessages.isNotEmpty)) {
    return fallbackMessages;
  }
  return runtimeMessages;
}

/// Number of newest-first entries to remove before retrying a user turn.
///
/// A plain retry keeps the original user entry visible and only clears the
/// assistant/process entries that followed it. An edited resend replaces the
/// user entry as well because its content may have changed.
int retriedMessageRoundRemovalCount(
  List<ChatMessageModel> messages, {
  required String userMessageId,
  required bool preserveUserMessage,
}) {
  final targetIndex = messages.indexWhere(
    (message) => message.id == userMessageId && message.user == 1,
  );
  if (targetIndex < 0) return 0;
  return targetIndex + (preserveUserMessage ? 0 : 1);
}

class ChatMessageListItemNotifier extends ValueNotifier<ChatMessageModel> {
  ChatMessageListItemNotifier(super.value);

  void update(ChatMessageModel message) {
    value = message;
  }
}

class ObservableChatMessageList extends ChangeNotifier
    with ListMixin<ChatMessageModel> {
  static const String _kAgentToolSummaryCardType = 'agent_tool_summary';

  final List<ChatMessageModel> _messages = <ChatMessageModel>[];
  final List<ChatMessageListItemNotifier> _messageNotifiers =
      <ChatMessageListItemNotifier>[];

  bool _isDisposed = false;
  int _structureRevision = 0;
  int _lastMutationRevision = 0;
  bool _lastMutationAffectsPageChrome = false;
  ChatMessageListMutationKind _lastMutationKind =
      ChatMessageListMutationKind.none;

  int get structureRevision => _structureRevision;
  int get lastMutationRevision => _lastMutationRevision;
  bool get lastMutationAffectsPageChrome => _lastMutationAffectsPageChrome;
  ChatMessageListMutationKind get lastMutationKind => _lastMutationKind;

  ValueListenable<ChatMessageModel> listenableAt(int index) {
    return _messageNotifiers[index];
  }

  @override
  void addListener(VoidCallback listener) {
    if (_isDisposed) {
      return;
    }
    super.addListener(listener);
  }

  @override
  void removeListener(VoidCallback listener) {
    if (_isDisposed) {
      return;
    }
    super.removeListener(listener);
  }

  void replaceAllMessages(Iterable<ChatMessageModel> messages) {
    final nextMessages = canonicalizeChatMessagesById(messages);
    final affectsPageChrome =
        _batchAffectsPageChrome(_messages) ||
        _batchAffectsPageChrome(nextMessages);
    _disposeMessageNotifiers(_messageNotifiers);
    _messages
      ..clear()
      ..addAll(nextMessages);
    _messageNotifiers
      ..clear()
      ..addAll(nextMessages.map(ChatMessageListItemNotifier.new));
    _recordStructureMutation(affectsPageChrome: affectsPageChrome);
    notifyListeners();
  }

  @override
  int get length => _messages.length;

  @override
  set length(int newLength) {
    RangeError.checkNotNegative(newLength, 'newLength');
    if (newLength == _messages.length) {
      return;
    }
    if (newLength > _messages.length) {
      throw UnsupportedError(
        'Expanding the message list length is unsupported',
      );
    }
    final removedMessages = _messages.sublist(newLength);
    final removedNotifiers = _messageNotifiers.sublist(newLength);
    _messages.removeRange(newLength, _messages.length);
    _messageNotifiers.removeRange(newLength, _messageNotifiers.length);
    _disposeMessageNotifiers(removedNotifiers);
    _recordStructureMutation(
      affectsPageChrome: _batchAffectsPageChrome(removedMessages),
    );
    notifyListeners();
  }

  @override
  ChatMessageModel operator [](int index) => _messages[index];

  @override
  void operator []=(int index, ChatMessageModel value) {
    final previous = _messages[index];
    _messages[index] = value;
    _messageNotifiers[index].update(value);
    final affectsPageChrome =
        _messageAffectsPageChrome(previous) || _messageAffectsPageChrome(value);
    if (_timelineStructureChanged(previous, value)) {
      _recordStructureMutation(affectsPageChrome: affectsPageChrome);
    } else {
      _recordContentMutation(affectsPageChrome: affectsPageChrome);
    }
    notifyListeners();
  }

  /// 判断一次原位替换是否会影响 agent_run_timeline 的分组结果。流式追加
  /// 文本时这些字段都不变，可以走 content 通道；任何会改变分组归属/可见
  /// 消息判定的字段变化都必须按 structure 通知，让列表重建时间线。
  static bool _timelineStructureChanged(
    ChatMessageModel previous,
    ChatMessageModel next,
  ) {
    if (previous.id != next.id ||
        previous.user != next.user ||
        previous.type != next.type ||
        previous.isError != next.isError ||
        previous.createAt != next.createAt) {
      return true;
    }
    final previousTaskId = agentRunParentTaskId(previous);
    if (previousTaskId != agentRunParentTaskId(next)) {
      return true;
    }
    final previousMeta = previous.streamMeta;
    final nextMeta = next.streamMeta;
    if ((previousMeta?.containsKey('isFinal') ?? false) !=
            (nextMeta?.containsKey('isFinal') ?? false) ||
        previousMeta?['isFinal'] != nextMeta?['isFinal']) {
      return true;
    }
    // 注意：不比较 seq/roundIndex——agent 流式每个 chunk 都会携带递增的
    // seq，但同一条消息原位更新时 seq 变化不影响它的分组归属；分组内的
    // 展示刷新由行级 listener 负责。
    if (agentRunKind(previous) != agentRunKind(next)) {
      return true;
    }
    if (_timelineCardType(previous) != _timelineCardType(next)) {
      return true;
    }
    if (previousTaskId != null &&
        isCancelledTaskText(previous) != isCancelledTaskText(next)) {
      return true;
    }
    return false;
  }

  static String _timelineCardType(ChatMessageModel message) {
    return (message.cardData?['type'] ?? '').toString().trim();
  }

  @override
  void add(ChatMessageModel element) {
    insert(length, element);
  }

  @override
  void addAll(Iterable<ChatMessageModel> iterable) {
    insertAll(length, iterable);
  }

  @override
  void clear() {
    if (_messages.isEmpty) {
      return;
    }
    final removedMessages = List<ChatMessageModel>.from(_messages);
    _messages.clear();
    _disposeMessageNotifiers(_messageNotifiers);
    _messageNotifiers.clear();
    _recordStructureMutation(
      affectsPageChrome: _batchAffectsPageChrome(removedMessages),
    );
    notifyListeners();
  }

  @override
  void insert(int index, ChatMessageModel element) {
    final existingIndex = _messages.indexWhere(
      (message) => message.id == element.id && element.id.trim().isNotEmpty,
    );
    if (existingIndex >= 0) {
      this[existingIndex] = element;
      return;
    }
    _messages.insert(index, element);
    _messageNotifiers.insert(index, ChatMessageListItemNotifier(element));
    _recordStructureMutation(
      affectsPageChrome: _messageAffectsPageChrome(element),
    );
    notifyListeners();
  }

  @override
  void insertAll(int index, Iterable<ChatMessageModel> iterable) {
    final nextMessages = canonicalizeChatMessagesById(iterable);
    if (nextMessages.isEmpty) {
      return;
    }
    var insertionIndex = index;
    var structureChanged = false;
    var affectsPageChrome = false;
    for (final message in nextMessages) {
      final existingIndex = _messages.indexWhere(
        (current) => current.id == message.id && message.id.trim().isNotEmpty,
      );
      if (existingIndex >= 0) {
        final previous = _messages[existingIndex];
        _messages[existingIndex] = message;
        _messageNotifiers[existingIndex].update(message);
        structureChanged =
            structureChanged || _timelineStructureChanged(previous, message);
        affectsPageChrome =
            affectsPageChrome ||
            _messageAffectsPageChrome(previous) ||
            _messageAffectsPageChrome(message);
        continue;
      }
      _messages.insert(insertionIndex, message);
      _messageNotifiers.insert(
        insertionIndex,
        ChatMessageListItemNotifier(message),
      );
      insertionIndex += 1;
      structureChanged = true;
      affectsPageChrome =
          affectsPageChrome || _messageAffectsPageChrome(message);
    }
    if (structureChanged) {
      _recordStructureMutation(affectsPageChrome: affectsPageChrome);
    } else {
      _recordContentMutation(affectsPageChrome: affectsPageChrome);
    }
    notifyListeners();
  }

  @override
  ChatMessageModel removeAt(int index) {
    final removedMessage = _messages.removeAt(index);
    final removedNotifier = _messageNotifiers.removeAt(index);
    removedNotifier.dispose();
    _recordStructureMutation(
      affectsPageChrome: _messageAffectsPageChrome(removedMessage),
    );
    notifyListeners();
    return removedMessage;
  }

  @override
  void removeRange(int start, int end) {
    if (start == end) {
      return;
    }
    final removedMessages = _messages.sublist(start, end);
    final removedNotifiers = _messageNotifiers.sublist(start, end);
    _messages.removeRange(start, end);
    _messageNotifiers.removeRange(start, end);
    _disposeMessageNotifiers(removedNotifiers);
    _recordStructureMutation(
      affectsPageChrome: _batchAffectsPageChrome(removedMessages),
    );
    notifyListeners();
  }

  @override
  void removeWhere(bool Function(ChatMessageModel element) test) {
    final removedMessages = <ChatMessageModel>[];
    final removedNotifiers = <ChatMessageListItemNotifier>[];
    for (var index = _messages.length - 1; index >= 0; index--) {
      final message = _messages[index];
      if (!test(message)) {
        continue;
      }
      removedMessages.add(message);
      removedNotifiers.add(_messageNotifiers[index]);
      _messages.removeAt(index);
      _messageNotifiers.removeAt(index);
    }
    if (removedMessages.isEmpty) {
      return;
    }
    _disposeMessageNotifiers(removedNotifiers);
    _recordStructureMutation(
      affectsPageChrome: _batchAffectsPageChrome(removedMessages),
    );
    notifyListeners();
  }

  @override
  void dispose() {
    if (_isDisposed) {
      return;
    }
    _isDisposed = true;
    _disposeMessageNotifiers(_messageNotifiers);
    _messageNotifiers.clear();
    _messages.clear();
    super.dispose();
  }

  void _recordContentMutation({required bool affectsPageChrome}) {
    _lastMutationRevision += 1;
    _lastMutationAffectsPageChrome = affectsPageChrome;
    _lastMutationKind = ChatMessageListMutationKind.content;
  }

  void _recordStructureMutation({required bool affectsPageChrome}) {
    _structureRevision += 1;
    _lastMutationRevision += 1;
    _lastMutationAffectsPageChrome = affectsPageChrome;
    _lastMutationKind = ChatMessageListMutationKind.structure;
  }

  bool _batchAffectsPageChrome(Iterable<ChatMessageModel> messages) {
    for (final message in messages) {
      if (_messageAffectsPageChrome(message)) {
        return true;
      }
    }
    return false;
  }

  bool _messageAffectsPageChrome(ChatMessageModel message) {
    if (message.type != 2) {
      return false;
    }
    return (message.cardData?['type'] ?? '').toString() ==
        _kAgentToolSummaryCardType;
  }

  void _disposeMessageNotifiers(
    Iterable<ChatMessageListItemNotifier> notifiers,
  ) {
    for (final notifier in notifiers) {
      notifier.dispose();
    }
  }
}

String _browserString(dynamic value) => (value ?? '').toString();

int? _browserInt(dynamic value) {
  if (value is num) return value.toInt();
  return int.tryParse(_browserString(value));
}

double? _browserDouble(dynamic value) {
  if (value is num) return value.toDouble();
  return double.tryParse(_browserString(value));
}

bool _browserBool(dynamic value, {bool fallback = false}) {
  if (value is bool) return value;
  final text = _browserString(value).trim().toLowerCase();
  if (text == 'true' || text == '1') return true;
  if (text == 'false' || text == '0') return false;
  return fallback;
}

Map<String, dynamic> _browserMap(dynamic raw) {
  if (raw is! Map) return const <String, dynamic>{};
  return raw.map((key, value) => MapEntry(key.toString(), value));
}

List<Map<String, dynamic>> _browserMapList(dynamic raw) {
  if (raw is! Iterable) return const <Map<String, dynamic>>[];
  return raw.map(_browserMap).toList(growable: false);
}

List<String> _browserStringList(dynamic raw) {
  if (raw is! Iterable) return const <String>[];
  return raw.map((item) => _browserString(item)).toList(growable: false);
}

class AgentBrowserTab {
  const AgentBrowserTab({
    required this.tabId,
    required this.url,
    required this.title,
    this.userAgentProfile,
    this.isActive = false,
    this.isLoading = false,
    this.hasSslError = false,
    this.riskChallengeDetected = false,
    this.riskChallengeKind,
    this.recommendedNextAction,
  });

  final int tabId;
  final String url;
  final String title;
  final String? userAgentProfile;
  final bool isActive;
  final bool isLoading;
  final bool hasSslError;
  final bool riskChallengeDetected;
  final String? riskChallengeKind;
  final String? recommendedNextAction;

  factory AgentBrowserTab.fromMap(Map<dynamic, dynamic> raw) {
    return AgentBrowserTab(
      tabId: _browserInt(raw['tabId']) ?? 0,
      url: _browserString(raw['url']),
      title: _browserString(raw['title']),
      userAgentProfile: raw['userAgentProfile']?.toString(),
      isActive: _browserBool(raw['isActive']),
      isLoading: _browserBool(raw['isLoading']),
      hasSslError: _browserBool(raw['hasSslError']),
      riskChallengeDetected: _browserBool(raw['riskChallengeDetected']),
      riskChallengeKind: raw['riskChallengeKind']?.toString(),
      recommendedNextAction: raw['recommendedNextAction']?.toString(),
    );
  }

  Map<String, dynamic> toMap() => <String, dynamic>{
    'tabId': tabId,
    'url': url,
    'title': title,
    'userAgentProfile': userAgentProfile,
    'isActive': isActive,
    'isLoading': isLoading,
    'hasSslError': hasSslError,
    'riskChallengeDetected': riskChallengeDetected,
    'riskChallengeKind': riskChallengeKind,
    'recommendedNextAction': recommendedNextAction,
  };
}

class AgentBrowserHistoryEntry {
  const AgentBrowserHistoryEntry({
    required this.url,
    required this.title,
    this.index,
    this.isCurrent = false,
    this.createdAt,
    this.updatedAt,
    this.visitedAt,
  });

  final String url;
  final String title;
  final int? index;
  final bool isCurrent;
  final int? createdAt;
  final int? updatedAt;
  final int? visitedAt;

  factory AgentBrowserHistoryEntry.fromMap(Map<dynamic, dynamic> raw) {
    return AgentBrowserHistoryEntry(
      url: _browserString(raw['url']),
      title: _browserString(raw['title']),
      index: _browserInt(raw['index']),
      isCurrent: _browserBool(raw['isCurrent']),
      createdAt: _browserInt(raw['createdAt']),
      updatedAt: _browserInt(raw['updatedAt']),
      visitedAt: _browserInt(raw['visitedAt']),
    );
  }

  Map<String, dynamic> toMap() => <String, dynamic>{
    'url': url,
    'title': title,
    'index': index,
    'isCurrent': isCurrent,
    'createdAt': createdAt,
    'updatedAt': updatedAt,
    'visitedAt': visitedAt,
  };
}

class BrowserDownloadSummary {
  const BrowserDownloadSummary({
    this.activeCount = 0,
    this.failedCount = 0,
    this.overallProgress,
    this.latestCompletedFileName,
  });

  final int activeCount;
  final int failedCount;
  final double? overallProgress;
  final String? latestCompletedFileName;

  factory BrowserDownloadSummary.fromMap(Map<dynamic, dynamic> raw) {
    return BrowserDownloadSummary(
      activeCount: _browserInt(raw['activeCount']) ?? 0,
      failedCount: _browserInt(raw['failedCount']) ?? 0,
      overallProgress: _browserDouble(raw['overallProgress']),
      latestCompletedFileName: raw['latestCompletedFileName']?.toString(),
    );
  }

  Map<String, dynamic> toMap() => <String, dynamic>{
    'activeCount': activeCount,
    'failedCount': failedCount,
    'overallProgress': overallProgress,
    'latestCompletedFileName': latestCompletedFileName,
  };
}

class BrowserDownloadItem {
  const BrowserDownloadItem({
    required this.id,
    required this.fileName,
    required this.url,
    required this.destinationPath,
    required this.status,
    this.mimeType,
    this.progress,
    this.downloadedBytes = 0,
    this.totalBytes = 0,
    this.errorMessage,
    this.canPause = false,
    this.canResume = false,
    this.canCancel = false,
    this.canRetry = false,
    this.canDelete = false,
    this.canDeleteFile = false,
    this.canOpenFile = false,
    this.canOpenLocation = false,
    this.supportsResume = false,
  });

  final String id;
  final String fileName;
  final String url;
  final String destinationPath;
  final String status;
  final String? mimeType;
  final double? progress;
  final int downloadedBytes;
  final int totalBytes;
  final String? errorMessage;
  final bool canPause;
  final bool canResume;
  final bool canCancel;
  final bool canRetry;
  final bool canDelete;
  final bool canDeleteFile;
  final bool canOpenFile;
  final bool canOpenLocation;
  final bool supportsResume;

  factory BrowserDownloadItem.fromMap(Map<dynamic, dynamic> raw) {
    return BrowserDownloadItem(
      id: _browserString(raw['id']),
      fileName: _browserString(raw['fileName']),
      url: _browserString(raw['url']),
      destinationPath: _browserString(raw['destinationPath']),
      status: _browserString(raw['status']),
      mimeType: raw['mimeType']?.toString(),
      progress: _browserDouble(raw['progress']),
      downloadedBytes: _browserInt(raw['downloadedBytes']) ?? 0,
      totalBytes: _browserInt(raw['totalBytes']) ?? 0,
      errorMessage: raw['errorMessage']?.toString(),
      canPause: _browserBool(raw['canPause']),
      canResume: _browserBool(raw['canResume']),
      canCancel: _browserBool(raw['canCancel']),
      canRetry: _browserBool(raw['canRetry']),
      canDelete: _browserBool(raw['canDelete']),
      canDeleteFile: _browserBool(raw['canDeleteFile']),
      canOpenFile: _browserBool(raw['canOpenFile']),
      canOpenLocation: _browserBool(raw['canOpenLocation']),
      supportsResume: _browserBool(raw['supportsResume']),
    );
  }

  Map<String, dynamic> toMap() => <String, dynamic>{
    'id': id,
    'fileName': fileName,
    'url': url,
    'destinationPath': destinationPath,
    'status': status,
    'mimeType': mimeType,
    'progress': progress,
    'downloadedBytes': downloadedBytes,
    'totalBytes': totalBytes,
    'errorMessage': errorMessage,
    'canPause': canPause,
    'canResume': canResume,
    'canCancel': canCancel,
    'canRetry': canRetry,
    'canDelete': canDelete,
    'canDeleteFile': canDeleteFile,
    'canOpenFile': canOpenFile,
    'canOpenLocation': canOpenLocation,
    'supportsResume': supportsResume,
  };
}

class BrowserExternalOpenPrompt {
  const BrowserExternalOpenPrompt({
    required this.requestId,
    required this.title,
    required this.target,
  });

  final String requestId;
  final String title;
  final String target;

  factory BrowserExternalOpenPrompt.fromMap(Map<dynamic, dynamic> raw) {
    return BrowserExternalOpenPrompt(
      requestId: _browserString(raw['requestId']),
      title: _browserString(raw['title']),
      target: _browserString(raw['target']),
    );
  }

  Map<String, dynamic> toMap() => <String, dynamic>{
    'requestId': requestId,
    'title': title,
    'target': target,
  };
}

class BrowserDialogPrompt {
  const BrowserDialogPrompt({
    required this.requestId,
    required this.type,
    required this.message,
    this.url,
    this.defaultValue,
  });

  final String requestId;
  final String type;
  final String message;
  final String? url;
  final String? defaultValue;

  factory BrowserDialogPrompt.fromMap(Map<dynamic, dynamic> raw) {
    return BrowserDialogPrompt(
      requestId: _browserString(raw['requestId']),
      type: _browserString(raw['type']),
      message: _browserString(raw['message']),
      url: raw['url']?.toString(),
      defaultValue: raw['defaultValue']?.toString(),
    );
  }

  Map<String, dynamic> toMap() => <String, dynamic>{
    'requestId': requestId,
    'type': type,
    'message': message,
    'url': url,
    'defaultValue': defaultValue,
  };
}

class BrowserPermissionPrompt {
  const BrowserPermissionPrompt({
    required this.requestId,
    required this.kind,
    required this.origin,
    this.resources = const <String>[],
  });

  final String requestId;
  final String kind;
  final String origin;
  final List<String> resources;

  factory BrowserPermissionPrompt.fromMap(Map<dynamic, dynamic> raw) {
    return BrowserPermissionPrompt(
      requestId: _browserString(raw['requestId']),
      kind: _browserString(raw['kind']),
      origin: _browserString(raw['origin']),
      resources: _browserStringList(raw['resources']),
    );
  }

  Map<String, dynamic> toMap() => <String, dynamic>{
    'requestId': requestId,
    'kind': kind,
    'origin': origin,
    'resources': resources,
  };
}

class BrowserUserscriptItem {
  const BrowserUserscriptItem({
    required this.id,
    required this.name,
    required this.description,
    required this.version,
    required this.enabled,
    this.blockedGrants = const <String>[],
    this.grants = const <String>[],
    this.matches = const <String>[],
    this.includes = const <String>[],
    this.excludes = const <String>[],
    this.runAt,
    this.sourceUrl,
  });

  final int id;
  final String name;
  final String description;
  final String version;
  final bool enabled;
  final List<String> blockedGrants;
  final List<String> grants;
  final List<String> matches;
  final List<String> includes;
  final List<String> excludes;
  final String? runAt;
  final String? sourceUrl;

  factory BrowserUserscriptItem.fromMap(Map<dynamic, dynamic> raw) {
    return BrowserUserscriptItem(
      id: _browserInt(raw['id']) ?? 0,
      name: _browserString(raw['name']),
      description: _browserString(raw['description']),
      version: _browserString(raw['version']),
      enabled: _browserBool(raw['enabled'], fallback: true),
      blockedGrants: _browserStringList(raw['blockedGrants']),
      grants: _browserStringList(raw['grants']),
      matches: _browserStringList(raw['matches']),
      includes: _browserStringList(raw['includes']),
      excludes: _browserStringList(raw['excludes']),
      runAt: raw['runAt']?.toString(),
      sourceUrl: raw['sourceUrl']?.toString(),
    );
  }

  Map<String, dynamic> toMap() => <String, dynamic>{
    'id': id,
    'name': name,
    'description': description,
    'version': version,
    'enabled': enabled,
    'blockedGrants': blockedGrants,
    'grants': grants,
    'matches': matches,
    'includes': includes,
    'excludes': excludes,
    'runAt': runAt,
    'sourceUrl': sourceUrl,
  };
}

class BrowserUserscriptMenuCommand {
  const BrowserUserscriptMenuCommand({
    required this.commandId,
    required this.scriptId,
    required this.title,
  });

  final String commandId;
  final int scriptId;
  final String title;

  factory BrowserUserscriptMenuCommand.fromMap(Map<dynamic, dynamic> raw) {
    return BrowserUserscriptMenuCommand(
      commandId: _browserString(raw['commandId']),
      scriptId: _browserInt(raw['scriptId']) ?? 0,
      title: _browserString(raw['title']),
    );
  }

  Map<String, dynamic> toMap() => <String, dynamic>{
    'commandId': commandId,
    'scriptId': scriptId,
    'title': title,
  };
}

class BrowserUserscriptInstallPreview {
  const BrowserUserscriptInstallPreview({
    required this.id,
    required this.name,
    required this.description,
    required this.version,
    required this.isUpdate,
    this.blockedGrants = const <String>[],
    this.grants = const <String>[],
    this.matches = const <String>[],
    this.includes = const <String>[],
    this.excludes = const <String>[],
    this.runAt,
    this.sourceUrl,
  });

  final int id;
  final String name;
  final String description;
  final String version;
  final bool isUpdate;
  final List<String> blockedGrants;
  final List<String> grants;
  final List<String> matches;
  final List<String> includes;
  final List<String> excludes;
  final String? runAt;
  final String? sourceUrl;

  factory BrowserUserscriptInstallPreview.fromMap(Map<dynamic, dynamic> raw) {
    return BrowserUserscriptInstallPreview(
      id: _browserInt(raw['id']) ?? 0,
      name: _browserString(raw['name']),
      description: _browserString(raw['description']),
      version: _browserString(raw['version']),
      isUpdate: _browserBool(raw['isUpdate']),
      blockedGrants: _browserStringList(raw['blockedGrants']),
      grants: _browserStringList(raw['grants']),
      matches: _browserStringList(raw['matches']),
      includes: _browserStringList(raw['includes']),
      excludes: _browserStringList(raw['excludes']),
      runAt: raw['runAt']?.toString(),
      sourceUrl: raw['sourceUrl']?.toString(),
    );
  }

  Map<String, dynamic> toMap() => <String, dynamic>{
    'id': id,
    'name': name,
    'description': description,
    'version': version,
    'isUpdate': isUpdate,
    'blockedGrants': blockedGrants,
    'grants': grants,
    'matches': matches,
    'includes': includes,
    'excludes': excludes,
    'runAt': runAt,
    'sourceUrl': sourceUrl,
  };
}

class BrowserUserscriptSummary {
  const BrowserUserscriptSummary({
    this.installedScripts = const <BrowserUserscriptItem>[],
    this.currentPageMenuCommands = const <BrowserUserscriptMenuCommand>[],
    this.pendingInstall,
  });

  final List<BrowserUserscriptItem> installedScripts;
  final List<BrowserUserscriptMenuCommand> currentPageMenuCommands;
  final BrowserUserscriptInstallPreview? pendingInstall;

  factory BrowserUserscriptSummary.fromMap(Map<dynamic, dynamic> raw) {
    return BrowserUserscriptSummary(
      installedScripts: _browserMapList(
        raw['installedScripts'],
      ).map(BrowserUserscriptItem.fromMap).toList(growable: false),
      currentPageMenuCommands: _browserMapList(
        raw['currentPageMenuCommands'],
      ).map(BrowserUserscriptMenuCommand.fromMap).toList(growable: false),
      pendingInstall: raw['pendingInstall'] == null
          ? null
          : BrowserUserscriptInstallPreview.fromMap(
              _browserMap(raw['pendingInstall']),
            ),
    );
  }

  Map<String, dynamic> toMap() => <String, dynamic>{
    'installedScripts': installedScripts.map((item) => item.toMap()).toList(),
    'currentPageMenuCommands': currentPageMenuCommands
        .map((item) => item.toMap())
        .toList(),
    'pendingInstall': pendingInstall?.toMap(),
  };
}

class ChatBrowserSessionSnapshot {
  const ChatBrowserSessionSnapshot({
    required this.available,
    required this.workspaceId,
    required this.activeTabId,
    required this.currentUrl,
    required this.title,
    this.userAgentProfile,
    this.isBookmarked = false,
    this.canGoBack = false,
    this.canGoForward = false,
    this.isLoading = false,
    this.hasSslError = false,
    this.isDesktopMode = false,
    this.riskChallengeDetected = false,
    this.riskChallengeKind,
    this.recommendedNextAction,
    this.throttleDelayMs,
    this.activeDownloadCount = 0,
    this.tabs = const <AgentBrowserTab>[],
    this.bookmarks = const <AgentBrowserHistoryEntry>[],
    this.history = const <AgentBrowserHistoryEntry>[],
    this.sessionHistory = const <AgentBrowserHistoryEntry>[],
    this.downloads = const <BrowserDownloadItem>[],
    this.downloadSummary = const BrowserDownloadSummary(),
    this.externalOpenPrompt,
    this.pendingDialog,
    this.permissionPrompt,
    this.userscriptSummary = const BrowserUserscriptSummary(),
  });

  final bool available;
  final String workspaceId;
  final int? activeTabId;
  final String currentUrl;
  final String title;
  final String? userAgentProfile;
  final bool isBookmarked;
  final bool canGoBack;
  final bool canGoForward;
  final bool isLoading;
  final bool hasSslError;
  final bool isDesktopMode;
  final bool riskChallengeDetected;
  final String? riskChallengeKind;
  final String? recommendedNextAction;
  final int? throttleDelayMs;
  final int activeDownloadCount;
  final List<AgentBrowserTab> tabs;
  final List<AgentBrowserHistoryEntry> bookmarks;
  final List<AgentBrowserHistoryEntry> history;
  final List<AgentBrowserHistoryEntry> sessionHistory;
  final List<BrowserDownloadItem> downloads;
  final BrowserDownloadSummary downloadSummary;
  final BrowserExternalOpenPrompt? externalOpenPrompt;
  final BrowserDialogPrompt? pendingDialog;
  final BrowserPermissionPrompt? permissionPrompt;
  final BrowserUserscriptSummary userscriptSummary;

  bool matchesWorkspace(String? candidateWorkspaceId) {
    final normalizedCandidate = candidateWorkspaceId?.trim() ?? '';
    if (normalizedCandidate.isEmpty) {
      return false;
    }
    return workspaceId.trim() == normalizedCandidate;
  }

  ChatBrowserSessionSnapshot copyWith({
    bool? available,
    String? workspaceId,
    int? activeTabId,
    String? currentUrl,
    String? title,
    String? userAgentProfile,
    bool? isBookmarked,
    bool? canGoBack,
    bool? canGoForward,
    bool? isLoading,
    bool? hasSslError,
    bool? isDesktopMode,
    bool? riskChallengeDetected,
    String? riskChallengeKind,
    String? recommendedNextAction,
    int? throttleDelayMs,
    int? activeDownloadCount,
    List<AgentBrowserTab>? tabs,
    List<AgentBrowserHistoryEntry>? bookmarks,
    List<AgentBrowserHistoryEntry>? history,
    List<AgentBrowserHistoryEntry>? sessionHistory,
    List<BrowserDownloadItem>? downloads,
    BrowserDownloadSummary? downloadSummary,
    BrowserExternalOpenPrompt? externalOpenPrompt,
    BrowserDialogPrompt? pendingDialog,
    BrowserPermissionPrompt? permissionPrompt,
    BrowserUserscriptSummary? userscriptSummary,
  }) {
    return ChatBrowserSessionSnapshot(
      available: available ?? this.available,
      workspaceId: workspaceId ?? this.workspaceId,
      activeTabId: activeTabId ?? this.activeTabId,
      currentUrl: currentUrl ?? this.currentUrl,
      title: title ?? this.title,
      userAgentProfile: userAgentProfile ?? this.userAgentProfile,
      isBookmarked: isBookmarked ?? this.isBookmarked,
      canGoBack: canGoBack ?? this.canGoBack,
      canGoForward: canGoForward ?? this.canGoForward,
      isLoading: isLoading ?? this.isLoading,
      hasSslError: hasSslError ?? this.hasSslError,
      isDesktopMode: isDesktopMode ?? this.isDesktopMode,
      riskChallengeDetected:
          riskChallengeDetected ?? this.riskChallengeDetected,
      riskChallengeKind: riskChallengeKind ?? this.riskChallengeKind,
      recommendedNextAction:
          recommendedNextAction ?? this.recommendedNextAction,
      throttleDelayMs: throttleDelayMs ?? this.throttleDelayMs,
      activeDownloadCount: activeDownloadCount ?? this.activeDownloadCount,
      tabs: tabs ?? this.tabs,
      bookmarks: bookmarks ?? this.bookmarks,
      history: history ?? this.history,
      sessionHistory: sessionHistory ?? this.sessionHistory,
      downloads: downloads ?? this.downloads,
      downloadSummary: downloadSummary ?? this.downloadSummary,
      externalOpenPrompt: externalOpenPrompt ?? this.externalOpenPrompt,
      pendingDialog: pendingDialog ?? this.pendingDialog,
      permissionPrompt: permissionPrompt ?? this.permissionPrompt,
      userscriptSummary: userscriptSummary ?? this.userscriptSummary,
    );
  }

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'available': available,
      'workspaceId': workspaceId,
      'activeTabId': activeTabId,
      'currentUrl': currentUrl,
      'title': title,
      'userAgentProfile': userAgentProfile,
      'isBookmarked': isBookmarked,
      'canGoBack': canGoBack,
      'canGoForward': canGoForward,
      'isLoading': isLoading,
      'hasSslError': hasSslError,
      'isDesktopMode': isDesktopMode,
      'riskChallengeDetected': riskChallengeDetected,
      'riskChallengeKind': riskChallengeKind,
      'recommendedNextAction': recommendedNextAction,
      'throttleDelayMs': throttleDelayMs,
      'activeDownloadCount': activeDownloadCount,
      'tabs': tabs.map((item) => item.toMap()).toList(),
      'bookmarks': bookmarks.map((item) => item.toMap()).toList(),
      'history': history.map((item) => item.toMap()).toList(),
      'sessionHistory': sessionHistory.map((item) => item.toMap()).toList(),
      'downloads': downloads.map((item) => item.toMap()).toList(),
      'downloadSummary': downloadSummary.toMap(),
      'externalOpenPrompt': externalOpenPrompt?.toMap(),
      'pendingDialog': pendingDialog?.toMap(),
      'permissionPrompt': permissionPrompt?.toMap(),
      'userscriptSummary': userscriptSummary.toMap(),
    };
  }

  factory ChatBrowserSessionSnapshot.fromMap(Map<dynamic, dynamic> raw) {
    final normalized = _browserMap(raw);
    return ChatBrowserSessionSnapshot(
      available: _browserBool(normalized['available']),
      workspaceId: _browserString(normalized['workspaceId']),
      activeTabId: _browserInt(normalized['activeTabId']),
      currentUrl: _browserString(normalized['currentUrl']),
      title: _browserString(normalized['title']),
      userAgentProfile: normalized['userAgentProfile']?.toString(),
      isBookmarked: _browserBool(normalized['isBookmarked']),
      canGoBack: _browserBool(normalized['canGoBack']),
      canGoForward: _browserBool(normalized['canGoForward']),
      isLoading: _browserBool(normalized['isLoading']),
      hasSslError: _browserBool(normalized['hasSslError']),
      isDesktopMode: _browserBool(normalized['isDesktopMode']),
      riskChallengeDetected: _browserBool(normalized['riskChallengeDetected']),
      riskChallengeKind: normalized['riskChallengeKind']?.toString(),
      recommendedNextAction: normalized['recommendedNextAction']?.toString(),
      throttleDelayMs: _browserInt(normalized['throttleDelayMs']),
      activeDownloadCount: _browserInt(normalized['activeDownloadCount']) ?? 0,
      tabs: _browserMapList(
        normalized['tabs'],
      ).map(AgentBrowserTab.fromMap).toList(growable: false),
      bookmarks: _browserMapList(
        normalized['bookmarks'],
      ).map(AgentBrowserHistoryEntry.fromMap).toList(growable: false),
      history: _browserMapList(
        normalized['history'],
      ).map(AgentBrowserHistoryEntry.fromMap).toList(growable: false),
      sessionHistory: _browserMapList(
        normalized['sessionHistory'],
      ).map(AgentBrowserHistoryEntry.fromMap).toList(growable: false),
      downloads: _browserMapList(
        normalized['downloads'],
      ).map(BrowserDownloadItem.fromMap).toList(growable: false),
      downloadSummary: BrowserDownloadSummary.fromMap(
        _browserMap(normalized['downloadSummary']),
      ),
      externalOpenPrompt: normalized['externalOpenPrompt'] == null
          ? null
          : BrowserExternalOpenPrompt.fromMap(
              _browserMap(normalized['externalOpenPrompt']),
            ),
      pendingDialog: normalized['pendingDialog'] == null
          ? null
          : BrowserDialogPrompt.fromMap(
              _browserMap(normalized['pendingDialog']),
            ),
      permissionPrompt: normalized['permissionPrompt'] == null
          ? null
          : BrowserPermissionPrompt.fromMap(
              _browserMap(normalized['permissionPrompt']),
            ),
      userscriptSummary: BrowserUserscriptSummary.fromMap(
        _browserMap(normalized['userscriptSummary']),
      ),
    );
  }

  factory ChatBrowserSessionSnapshot.fromBrowserToolPayload({
    required Map<String, dynamic> payload,
    required String workspaceId,
  }) {
    final activeTabId = _browserInt(payload['activeTabId'] ?? payload['tabId']);
    final currentUrl = _browserString(
      payload['currentUrl'] ?? payload['finalUrl'] ?? payload['url'],
    );
    final title = _browserString(payload['pageTitle'] ?? payload['title']);
    return ChatBrowserSessionSnapshot(
      available: true,
      workspaceId: workspaceId,
      activeTabId: activeTabId,
      currentUrl: currentUrl,
      title: title,
      userAgentProfile: payload['userAgentProfile']?.toString(),
      riskChallengeDetected: _browserBool(payload['riskChallengeDetected']),
      riskChallengeKind: payload['riskChallengeKind']?.toString(),
      recommendedNextAction: payload['recommendedNextAction']?.toString(),
      throttleDelayMs: _browserInt(payload['throttleDelayMs']),
    );
  }

  static ChatBrowserSessionSnapshot? tryParseBrowserToolJson({
    required String rawJson,
    required String workspaceId,
  }) {
    final text = rawJson.trim();
    if (text.isEmpty) {
      return null;
    }
    try {
      final decoded = jsonDecode(text);
      if (decoded is! Map) {
        return null;
      }
      final root = decoded.map((key, value) => MapEntry(key.toString(), value));
      final payload = _findBrowserToolPayload(root);
      if (payload == null) {
        return null;
      }
      return ChatBrowserSessionSnapshot.fromBrowserToolPayload(
        payload: payload,
        workspaceId: workspaceId,
      );
    } catch (_) {
      return null;
    }
  }
}

Map<String, dynamic>? _findBrowserToolPayload(
  Map<String, dynamic> root, {
  int depth = 0,
}) {
  if (depth > 4) {
    return null;
  }
  const browserFields = <String>{
    'activeTabId',
    'tabId',
    'currentUrl',
    'finalUrl',
    'url',
    'pageTitle',
    'riskChallengeDetected',
  };
  if (root.keys.any(browserFields.contains)) {
    return root;
  }
  for (final key in const <String>[
    'result',
    'rawResult',
    'rawOutput',
    'output',
    'data',
    'payload',
  ]) {
    final value = root[key];
    Map<String, dynamic>? child;
    if (value is Map) {
      child = value.map((key, value) => MapEntry(key.toString(), value));
    } else if (value is String && value.trimLeft().startsWith('{')) {
      try {
        final decoded = jsonDecode(value);
        if (decoded is Map) {
          child = decoded.map((key, value) => MapEntry(key.toString(), value));
        }
      } catch (_) {
        // The next candidate can still contain the structured browser result.
      }
    }
    if (child == null) {
      continue;
    }
    final resolved = _findBrowserToolPayload(child, depth: depth + 1);
    if (resolved != null) {
      return resolved;
    }
  }
  return null;
}

String chatConversationWorkspaceId(int? conversationId) {
  return conversationId == null
      ? 'conversation_default'
      : 'conversation_$conversationId';
}

class HdPadPaneLayout {
  const HdPadPaneLayout({
    required this.leftWidth,
    required this.centerWidth,
    required this.rightWidth,
  });

  final double leftWidth;
  final double centerWidth;
  final double rightWidth;
}

const double kHdPadMinShortestSide = 600;
const double kHdPadMinLandscapeWidth = 960;
const double kChatKeyboardComposerClearance = 10.0;
const double kChatComposerEdgeInset = 24.0;

/// Resolves the composer's bottom padding inside the page-level [SafeArea].
///
/// While the soft keyboard still covers the system navigation area, the
/// ancestor SafeArea's bottom padding (`max(0, viewPadding - viewInsets)`)
/// has not been restored yet. When the composer is not lifted for the
/// keyboard (e.g. focus was cleared before the IME finished hiding), the
/// missing SafeArea padding is compensated here so the composer rests at its
/// final position immediately, instead of settling too low and visibly
/// popping up once the IME hide animation ends.
double resolveChatComposerInputBottomPadding({
  required bool shouldLiftComposerForKeyboard,
  required double bottomInset,
  required double viewPaddingBottom,
  required double safeAreaBottomPadding,
  double edgeInset = kChatComposerEdgeInset,
}) {
  final normalizedBottomInset = bottomInset.isFinite
      ? math.max(0.0, bottomInset)
      : 0.0;
  final normalizedViewPadding = viewPaddingBottom.isFinite
      ? math.max(0.0, viewPaddingBottom)
      : 0.0;
  final normalizedEdgeInset = edgeInset.isFinite
      ? math.max(0.0, edgeInset)
      : 0.0;
  if (shouldLiftComposerForKeyboard) {
    return (normalizedViewPadding + normalizedEdgeInset - normalizedBottomInset)
        .clamp(0.0, normalizedEdgeInset)
        .toDouble();
  }
  final normalizedSafeAreaPadding = safeAreaBottomPadding.isFinite
      ? safeAreaBottomPadding.clamp(0.0, normalizedViewPadding).toDouble()
      : 0.0;
  final safeAreaShortfall = normalizedViewPadding - normalizedSafeAreaPadding;
  return normalizedEdgeInset + safeAreaShortfall;
}

double resolveChatComposerKeyboardSpacer({
  required bool shouldLiftComposerForKeyboard,
  required double bottomInset,
  double keyboardClearance = kChatKeyboardComposerClearance,
}) {
  if (!shouldLiftComposerForKeyboard) {
    return 0.0;
  }
  final normalizedBottomInset = bottomInset.isFinite
      ? math.max(0.0, bottomInset)
      : 0.0;
  if (normalizedBottomInset <= 0) {
    return 0.0;
  }
  final normalizedClearance = keyboardClearance.isFinite
      ? math.max(0.0, keyboardClearance)
      : 0.0;
  final closingClearance = math.min(normalizedClearance, normalizedBottomInset);
  return normalizedBottomInset + closingClearance;
}

bool isHdPadLandscapeViewport(Size size) {
  return size.width > size.height &&
      size.shortestSide >= kHdPadMinShortestSide &&
      size.width >= kHdPadMinLandscapeWidth;
}

class HdPadPaneLayoutResolver {
  const HdPadPaneLayoutResolver();

  static const double dividerHitWidth = 12;
  static const double defaultLeftWidth = 260;
  static const double minLeftWidth = 220;
  static const double maxLeftWidth = 360;
  static const double defaultRightWidth = 300;
  static const double minRightWidth = 240;
  static const double maxRightWidth = 420;
  static const double minCenterWidth = 320;

  HdPadPaneLayout resolve(
    double totalWidth, {
    double? preferredLeftWidth,
    double? preferredRightWidth,
    bool collapseLeftPane = false,
    bool collapseRightPane = false,
  }) {
    final showLeftPane = !collapseLeftPane;
    final showRightPane = !collapseRightPane;
    final dividerCount = (showLeftPane ? 1 : 0) + (showRightPane ? 1 : 0);
    final availableWidth = math.max(
      0,
      totalWidth - dividerHitWidth * dividerCount,
    );

    var leftWidth = showLeftPane
        ? (preferredLeftWidth ?? defaultLeftWidth)
              .clamp(minLeftWidth, maxLeftWidth)
              .toDouble()
        : 0.0;
    var rightWidth = showRightPane
        ? (preferredRightWidth ?? defaultRightWidth)
              .clamp(minRightWidth, maxRightWidth)
              .toDouble()
        : 0.0;

    if (showLeftPane) {
      final maxLeftBySpace = math.max(
        minLeftWidth,
        availableWidth - rightWidth - minCenterWidth,
      );
      leftWidth = leftWidth.clamp(minLeftWidth, maxLeftBySpace).toDouble();
    }

    if (showRightPane) {
      final maxRightBySpace = math.max(
        minRightWidth,
        availableWidth - leftWidth - minCenterWidth,
      );
      rightWidth = rightWidth.clamp(minRightWidth, maxRightBySpace).toDouble();
    }

    var centerWidth = availableWidth - leftWidth - rightWidth;
    if (showRightPane && centerWidth < minCenterWidth) {
      final rightFlexible = rightWidth - minRightWidth;
      if (rightFlexible > 0) {
        final delta = math.min(minCenterWidth - centerWidth, rightFlexible);
        rightWidth -= delta;
        centerWidth += delta;
      }
    }
    if (showLeftPane && centerWidth < minCenterWidth) {
      final leftFlexible = leftWidth - minLeftWidth;
      if (leftFlexible > 0) {
        final delta = math.min(minCenterWidth - centerWidth, leftFlexible);
        leftWidth -= delta;
        centerWidth += delta;
      }
    }

    return HdPadPaneLayout(
      leftWidth: leftWidth,
      centerWidth: math.max(0, centerWidth),
      rightWidth: centerWidth.isNegative ? 0 : rightWidth,
    );
  }
}

class ChatPaneOverlayAnchorGeometry {
  const ChatPaneOverlayAnchorGeometry({
    required this.rect,
    required this.bottom,
  });

  final Rect rect;
  final double bottom;
}

ChatPaneOverlayAnchorGeometry resolveChatPaneOverlayAnchorGeometry({
  required Size viewportSize,
  double horizontalInset = 24,
  required double bottomSpacing,
  required double anchorHeight,
}) {
  final resolvedWidth = math.max(0.0, viewportSize.width - horizontalInset * 2);
  final resolvedBottom = bottomSpacing
      .clamp(0.0, viewportSize.height)
      .toDouble();
  final resolvedHeight = anchorHeight.isFinite
      ? math.max(0.0, anchorHeight)
      : 0.0;
  final top = (viewportSize.height - resolvedBottom)
      .clamp(0.0, viewportSize.height)
      .toDouble();
  return ChatPaneOverlayAnchorGeometry(
    rect: Rect.fromLTWH(horizontalInset, top, resolvedWidth, resolvedHeight),
    bottom: resolvedBottom,
  );
}
