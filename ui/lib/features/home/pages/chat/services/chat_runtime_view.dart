part of 'chat_conversation_runtime_coordinator.dart';

/// Read-only projection of one conversation runtime.
///
/// Pages, sheets and the drawer read runtime state only through this view and
/// change it only through coordinator commands. The view reads the latest
/// snapshot published by the native runtime owner; Flutter keeps no second
/// mutable copy of runtime state.
class ChatRuntimeView {
  ChatRuntimeView._(this._state)
    : messages = ChatRuntimeMessageListView._(_state.messages);

  final _ChatRuntimeMirror _state;

  /// Newest-first visible messages. Writes throw; use the coordinator's
  /// message commands instead.
  final ChatRuntimeMessageListView messages;

  int get conversationId => _state.conversationId;
  String get mode => _state.mode;
  ConversationModel? get conversation => _state.conversation;

  Map<String, String> get currentAiMessages =>
      UnmodifiableMapView<String, String>(_state.currentAiMessages);
  Map<String, String> get currentThinkingMessages =>
      UnmodifiableMapView<String, String>(_state.currentThinkingMessages);

  bool get isAiResponding => _state.isAiResponding;
  bool get isContextCompressing => _state.isContextCompressing;
  bool get isCheckingExecutableTask => _state.isCheckingExecutableTask;
  bool get isExecutingTask => _state.isExecutingTask;
  bool get isInputAreaVisible => _state.isInputAreaVisible;
  bool get isDeepThinking => _state.isDeepThinking;
  String get deepThinkingContent => _state.deepThinkingContent;
  int get currentThinkingStage => _state.currentThinkingStage;
  bool get hasInFlightTask => _state.hasInFlightTask;

  String? get activeRunId => _state.activeRunId;
  String? get currentDispatchTurnId => _state.currentDispatchTurnId;
  String? get activeAcpTurnId => _state.activeAcpTurnId;
  String? get activeAcpSessionId => _state.activeAcpSessionId;
  String? get lastAgentTurnId => _state.lastAgentTurnId;
  AgentRunIdentity? get activeRunIdentity => _state.activeRunIdentity;
  Set<String> get activeAgentTurnIds =>
      Set<String>.unmodifiable(_state.activeAgentTurnIds);

  String? get activeToolCardId => _state.activeToolCardId;
  String? get activeThinkingCardId => _state.activeThinkingCardId;
  String? get activeContextCompactionMarkerId =>
      _state.activeContextCompactionMarkerId;
  String? get pendingAgentTextTaskId => _state.pendingAgentTextTaskId;
  bool get pendingThinkingRoundSplit => _state.pendingThinkingRoundSplit;
  int get toolCardSequence => _state.toolCardSequence;
  int get thinkingRound => _state.thinkingRound;

  ChatIslandDisplayLayer get chatIslandDisplayLayer =>
      _state.chatIslandDisplayLayer;
  String? get lastAgentToolType => _state.lastAgentToolType;
  ChatBrowserSessionSnapshot? get browserSessionSnapshot =>
      _state.browserSessionSnapshot;

  List<Map<String, dynamic>> get availableAcpCommands =>
      List<Map<String, dynamic>>.unmodifiable(_state.availableAcpCommands);
  List<Map<String, dynamic>> get acpConfigOptions =>
      List<Map<String, dynamic>>.unmodifiable(_state.acpConfigOptions);
  String? get currentAcpModeId => _state.currentAcpModeId;
  Map<String, dynamic> get acpSessionInfo =>
      Map<String, dynamic>.unmodifiable(_state.acpSessionInfo);

  bool get shouldSuppressLocalMessageSnapshotEcho =>
      _state.shouldSuppressLocalMessageSnapshotEcho;
}

/// Read-only list view over a runtime's [ObservableChatMessageList].
///
/// It keeps the row-level listenables and mutation revisions the message list
/// widgets depend on, while every structural or element write throws.
class ChatRuntimeMessageListView extends ListBase<ChatMessageModel>
    implements ObservableChatMessageSource {
  ChatRuntimeMessageListView._(this._source);

  final ObservableChatMessageList _source;

  @override
  int get length => _source.length;

  @override
  set length(int newLength) => throw _readOnlyError();

  @override
  ChatMessageModel operator [](int index) => _source[index];

  @override
  void operator []=(int index, ChatMessageModel value) =>
      throw _readOnlyError();

  @override
  ValueListenable<ChatMessageModel> listenableAt(int index) =>
      _source.listenableAt(index);

  @override
  int get structureRevision => _source.structureRevision;

  @override
  int get lastMutationRevision => _source.lastMutationRevision;

  @override
  bool get lastMutationAffectsPageChrome =>
      _source.lastMutationAffectsPageChrome;

  @override
  ChatMessageListMutationKind get lastMutationKind => _source.lastMutationKind;

  @override
  void addListener(VoidCallback listener) => _source.addListener(listener);

  @override
  void removeListener(VoidCallback listener) =>
      _source.removeListener(listener);

  static UnsupportedError _readOnlyError() => UnsupportedError(
    'Runtime messages are read-only; use ChatConversationRuntimeCoordinator '
    'message commands.',
  );
}
