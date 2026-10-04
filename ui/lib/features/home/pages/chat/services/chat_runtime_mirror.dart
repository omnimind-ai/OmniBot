part of 'chat_conversation_runtime_coordinator.dart';

/// Read-only Flutter mirror of one native runtime snapshot.
///
/// The native `ChatConversationRuntimeCoordinator` owns every runtime; this
/// mirror only holds the last snapshot it published so widgets can render
/// synchronously. Fields change in exactly two ways: [applySnapshot] from
/// the native owner, and the optimistic page write commands (plain field and
/// list writes) that the native owner then confirms in its next snapshot.
class _ChatRuntimeMirror {
  _ChatRuntimeMirror({required this.conversationId, required this.mode});

  final int conversationId;
  final String mode;

  /// Native snapshot revision (one coordinator-wide sequence).
  int revision = 0;

  ConversationModel? conversation;
  final ObservableChatMessageList messages = ObservableChatMessageList();
  final Map<String, ChatMessageModel> _messagesById =
      <String, ChatMessageModel>{};

  /// Text caches are native-owned; the mirror knows their keys only.
  Map<String, String> currentAiMessages = const <String, String>{};
  Map<String, String> currentThinkingMessages = const <String, String>{};

  bool isAiResponding = false;
  bool isContextCompressing = false;
  bool isCheckingExecutableTask = false;
  bool isExecutingTask = false;
  bool isInputAreaVisible = true;
  bool isDeepThinking = false;
  String deepThinkingContent = '';
  int currentThinkingStage = 1;

  String? activeRunId;
  String? get currentDispatchTurnId => activeRunId;
  String? activeAcpTurnId;
  String? activeAcpSessionId;
  String? lastAgentTurnId;
  Set<String> activeAgentTurnIds = const <String>{};
  Set<String> boundTaskIds = const <String>{};

  String? activeToolCardId;
  String? activeThinkingCardId;
  String? activeContextCompactionMarkerId;
  String? pendingAgentTextTaskId;
  bool pendingThinkingRoundSplit = false;
  int toolCardSequence = 0;
  int thinkingRound = 0;

  ChatIslandDisplayLayer chatIslandDisplayLayer = ChatIslandDisplayLayer.mode;
  String? lastAgentToolType;
  ChatBrowserSessionSnapshot? browserSessionSnapshot;

  List<Map<String, dynamic>> availableAcpCommands =
      const <Map<String, dynamic>>[];
  List<Map<String, dynamic>> acpConfigOptions = const <Map<String, dynamic>>[];
  String? currentAcpModeId;
  Map<String, dynamic> acpSessionInfo = const <String, dynamic>{};
  bool shouldSuppressLocalMessageSnapshotEcho = false;
  bool isEphemeral = false;

  ChatRuntimeView? _view;
  ChatRuntimeView get view => _view ??= ChatRuntimeView._(this);

  /// Same formula as the native runtime, so optimistic page writes stay
  /// consistent with the flags they change until the next snapshot.
  bool get hasInFlightTask =>
      isAiResponding ||
      isCheckingExecutableTask ||
      isExecutingTask ||
      activeRunId != null;

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

  /// Applies one native snapshot. Returns false when the snapshot references
  /// a message the mirror never received (it must then request a resync).
  bool applySnapshot(Map<String, dynamic> raw) {
    final changed = <String, ChatMessageModel>{};
    for (final item in _asList(raw['changedMessages'])) {
      final map = _asMap(item);
      if (map == null) continue;
      final message = ChatMessageModel.fromJson(map);
      changed[message.id] = message;
    }
    final ids = _asList(
      raw['messageIds'],
    ).map((id) => id.toString()).toList(growable: false);
    final next = <ChatMessageModel>[];
    for (final id in ids) {
      final message = changed[id] ?? _messagesById[id];
      if (message == null) return false;
      next.add(message);
    }
    revision = _asInt(raw['revision']) ?? revision;
    _applyMessages(next);
    _messagesById
      ..clear()
      ..addEntries(next.map((message) => MapEntry(message.id, message)));

    final rawConversation = _asMap(raw['conversation']);
    conversation = rawConversation == null
        ? null
        : ConversationModel.fromJson(rawConversation);
    currentAiMessages = _keysOnly(raw['currentAiMessageKeys']);
    currentThinkingMessages = _keysOnly(raw['currentThinkingMessageKeys']);
    isAiResponding = raw['isAiResponding'] == true;
    isContextCompressing = raw['isContextCompressing'] == true;
    isCheckingExecutableTask = raw['isCheckingExecutableTask'] == true;
    isExecutingTask = raw['isExecutingTask'] == true;
    isInputAreaVisible = raw['isInputAreaVisible'] != false;
    isDeepThinking = raw['isDeepThinking'] == true;
    deepThinkingContent = raw['deepThinkingContent']?.toString() ?? '';
    currentThinkingStage = _asInt(raw['currentThinkingStage']) ?? 1;
    activeRunId = _optString(raw['activeRunId']);
    activeAcpTurnId = _optString(raw['activeAcpTurnId']);
    activeAcpSessionId = _optString(raw['activeAcpSessionId']);
    lastAgentTurnId = _optString(raw['lastAgentTurnId']);
    activeAgentTurnIds = _stringSet(raw['activeAgentTurnIds']);
    boundTaskIds = _stringSet(raw['boundTaskIds']);
    activeToolCardId = _optString(raw['activeToolCardId']);
    activeThinkingCardId = _optString(raw['activeThinkingCardId']);
    activeContextCompactionMarkerId = _optString(
      raw['activeContextCompactionMarkerId'],
    );
    pendingAgentTextTaskId = _optString(raw['pendingAgentTextTaskId']);
    pendingThinkingRoundSplit = raw['pendingThinkingRoundSplit'] == true;
    toolCardSequence = _asInt(raw['toolCardSequence']) ?? 0;
    thinkingRound = _asInt(raw['thinkingRound']) ?? 0;
    chatIslandDisplayLayer = ChatIslandDisplayLayer.fromWireName(
      raw['chatIslandDisplayLayer']?.toString(),
    );
    lastAgentToolType = _optString(raw['lastAgentToolType']);
    final browser = _asMap(raw['browserSessionSnapshot']);
    browserSessionSnapshot = browser == null
        ? null
        : ChatBrowserSessionSnapshot.fromMap(browser);
    availableAcpCommands = _mapList(raw['availableAcpCommands']);
    acpConfigOptions = _mapList(raw['acpConfigOptions']);
    currentAcpModeId = _optString(raw['currentAcpModeId']);
    acpSessionInfo = _asMap(raw['acpSessionInfo']) ?? <String, dynamic>{};
    shouldSuppressLocalMessageSnapshotEcho =
        raw['shouldSuppressLocalMessageSnapshotEcho'] == true;
    isEphemeral = raw['isEphemeral'] == true;
    return true;
  }

  /// Keeps row listenables: an unchanged id order updates only the rows whose
  /// message instance changed, which the list reports as content or
  /// structure mutations exactly as before.
  void _applyMessages(List<ChatMessageModel> next) {
    final current = messages;
    var sameOrder = current.length == next.length;
    for (var index = 0; sameOrder && index < next.length; index += 1) {
      if (current[index].id != next[index].id) sameOrder = false;
    }
    if (!sameOrder) {
      current.replaceAllMessages(next);
      return;
    }
    for (var index = 0; index < next.length; index += 1) {
      if (!identical(current[index], next[index])) current[index] = next[index];
    }
  }

  /// Optimistic message write mirrored by the native command it precedes.
  void rememberMessage(ChatMessageModel message) {
    _messagesById[message.id] = message;
  }

  void dispose() {
    _messagesById.clear();
    messages.dispose();
  }

  static List<dynamic> _asList(dynamic value) =>
      value is List ? value : const <dynamic>[];

  static Map<String, dynamic>? _asMap(dynamic value) {
    if (value is Map<String, dynamic>) return value;
    if (value is Map) {
      return value.map((key, item) => MapEntry(key.toString(), _deep(item)));
    }
    return null;
  }

  static dynamic _deep(dynamic value) {
    if (value is Map) return _asMap(value);
    if (value is List) return value.map(_deep).toList();
    return value;
  }

  static List<Map<String, dynamic>> _mapList(dynamic value) => _asList(
    value,
  ).map(_asMap).whereType<Map<String, dynamic>>().toList(growable: false);

  static Map<String, String> _keysOnly(dynamic value) => <String, String>{
    for (final key in _asList(value)) key.toString(): '',
  };

  static Set<String> _stringSet(dynamic value) =>
      _asList(value).map((item) => item.toString()).toSet();

  static int? _asInt(dynamic value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '');
  }

  static String? _optString(dynamic value) => value?.toString();
}
