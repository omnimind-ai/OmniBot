part of 'chat_conversation_runtime_coordinator.dart';

/// Remote Agent (Codex bridge) routing facts published by the chat page.
///
/// The remote runtime is keyed by its thread id; this is plain data so the
/// coordinator can attribute an event without calling back into the page.
class ChatRuntimeRemoteRoutingContext {
  const ChatRuntimeRemoteRoutingContext({
    this.activeThreadId,
    this.activeRemoteRuntimeId,
    this.agentFallbackMessages = const <ChatMessageModel>[],
    this.agentConversation,
  });

  final String? activeThreadId;
  final int? activeRemoteRuntimeId;

  /// The Agent mode's page-local messages, used when a remote thread is
  /// promoted into the visible runtime before its first event is applied.
  final List<ChatMessageModel> agentFallbackMessages;
  final ConversationModel? agentConversation;
}

/// Attribution facts a mounted chat surface publishes to the coordinator.
///
/// A [ChatRuntimeRoutingContext.page] surface resolves events by identity and
/// falls back to its visible conversation exactly like the former page-level
/// handler. A [ChatRuntimeRoutingContext.dispatchScoped] surface (the command
/// overlay sheet) only claims events for its explicit conversation while it
/// has a prompt in flight.
class ChatRuntimeRoutingContext {
  const ChatRuntimeRoutingContext.page({
    required this.activeMode,
    this.conversationIdsByMode = const <String, int?>{},
    this.conversationsByMode = const <String, ConversationModel?>{},
    this.remote,
  }) : dispatchScoped = false,
       scopedConversationId = null,
       scopedConversation = null;

  const ChatRuntimeRoutingContext.dispatchScoped({
    required int conversationId,
    required String mode,
    ConversationModel? conversation,
  }) : dispatchScoped = true,
       activeMode = mode,
       scopedConversationId = conversationId,
       scopedConversation = conversation,
       conversationIdsByMode = const <String, int?>{},
       conversationsByMode = const <String, ConversationModel?>{},
       remote = null;

  final bool dispatchScoped;

  /// Runtime mode key of the surface's visible mode.
  final String activeMode;
  final Map<String, int?> conversationIdsByMode;
  final Map<String, ConversationModel?> conversationsByMode;

  /// Non-null only while the remote Agent runtime is configured.
  final ChatRuntimeRemoteRoutingContext? remote;

  final int? scopedConversationId;
  final ConversationModel? scopedConversation;
}

/// The single projection result of one runtime event, delivered to every
/// attached surface after the coordinator applied it.
class ChatRuntimeEventOutcome {
  const ChatRuntimeEventOutcome({
    required this.event,
    required this.conversationId,
    required this.mode,
    required this.result,
    this.promotedRemoteThreadId,
  });

  final Map<String, dynamic> event;
  final int conversationId;
  final String mode;
  final AgentReduceResult result;

  /// Set when the event promoted a remote thread into the visible Agent
  /// runtime; the page then adopts that thread as its active session.
  final String? promotedRemoteThreadId;
}

/// Registration of one mounted surface. [detach] when the surface disposes.
class ChatRuntimeEventHost {
  ChatRuntimeEventHost._(this._coordinator, this._context, this._onOutcome);

  final ChatConversationRuntimeCoordinator _coordinator;

  /// Returns null while the surface does not claim any events.
  final ChatRuntimeRoutingContext? Function() _context;
  final void Function(ChatRuntimeEventOutcome outcome) _onOutcome;

  void detach() => _coordinator._detachEventHost(this);
}

String? remoteAgentEventThreadId(Map<String, dynamic> event) =>
    _remoteAgentThreadIdFromEnvelope(event);

const List<String> _remoteAgentEnvelopeKeys = <String>[
  'message',
  'payload',
  'data',
  'event',
  'notification',
  'params',
  'result',
];

Map<String, dynamic>? _routingMap(dynamic value) {
  if (value is Map<String, dynamic>) return value;
  if (value is Map) {
    return value.map((key, item) => MapEntry(key.toString(), item));
  }
  return null;
}

String? _routingString(dynamic value) {
  final text = value?.toString().trim() ?? '';
  return text.isEmpty ? null : text;
}

int? _routingInt(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '');
}

String? _remoteAgentThreadIdFromEnvelope(dynamic value, {int depth = 0}) {
  if (depth > 6) {
    return null;
  }
  final map = _routingMap(value);
  if (map == null) {
    return null;
  }
  final direct = _routingString(map['threadId'] ?? map['thread_id']);
  if (direct != null) {
    return direct;
  }
  final threadId = _routingString(_routingMap(map['thread'])?['id']);
  if (threadId != null) {
    return threadId;
  }
  for (final key in _remoteAgentEnvelopeKeys) {
    final nested = map[key];
    if (nested == null) {
      continue;
    }
    final nestedThreadId = _remoteAgentThreadIdFromEnvelope(
      nested,
      depth: depth + 1,
    );
    if (nestedThreadId != null) {
      return nestedThreadId;
    }
  }
  return null;
}

/// Stable local runtime id of a remote Agent thread.
int remoteAgentRuntimeIdForThread(String seed) {
  var hash = 0x45d9f3b;
  for (final codeUnit in seed.codeUnits) {
    hash = 0x1fffffff & (hash * 31 + codeUnit);
  }
  return -((hash & 0x3fffffff) + 1);
}

extension _ChatRuntimeEventRouting on ChatConversationRuntimeCoordinator {
  String? _standaloneProcessIdOf(Map<String, dynamic> event) {
    final params = _routingMap(event['params']);
    for (final value in <dynamic>[
      event['processId'],
      event['process_id'],
      event['processHandle'],
      event['process_handle'],
      params?['processId'],
      params?['process_id'],
      params?['processHandle'],
      params?['process_handle'],
    ]) {
      final normalized = value?.toString().trim() ?? '';
      if (normalized.isNotEmpty) return normalized;
    }
    return null;
  }

  String? _eventMethodOf(Map<String, dynamic> event) =>
      _routingString(event['method']) ??
      _routingString(_routingMap(event['message'])?['method']);

  bool _shouldPromoteRemoteThread({
    required ChatRuntimeRoutingContext context,
    required String threadId,
    required int runtimeId,
  }) {
    final remote = context.remote!;
    final activeThreadId = remote.activeThreadId?.trim();
    if (activeThreadId == threadId) {
      return true;
    }
    final currentConversationId =
        context.conversationIdsByMode[kChatRuntimeModeAgent];
    if (currentConversationId == runtimeId) {
      return true;
    }
    if (activeThreadId != null && activeThreadId.isNotEmpty) {
      return false;
    }
    if (currentConversationId == null ||
        currentConversationId != remote.activeRemoteRuntimeId) {
      return false;
    }
    final runtime = _runtimeStateFor(
      conversationId: currentConversationId,
      mode: kChatRuntimeModeAgent,
    );
    return remote.agentFallbackMessages.isNotEmpty ||
        (runtime?.hasInFlightTask ?? false);
  }

  /// Applies one runtime event to the single runtime that owns it.
  ///
  /// Ownership order: explicit host conversation id, promoted/known remote
  /// thread, admitted session/turn identity, the first owner of a legacy
  /// process, and finally the visible Agent conversation for identity-less
  /// errors/process output. Mode comes from the runtime that admitted the
  /// session/turn, then from a dispatch-scoped surface holding a prompt for
  /// that conversation, then from the page's visible mode facts.
  ChatRuntimeEventOutcome? _routeAgentEvent(Map<String, dynamic> event) {
    final contexts = <ChatRuntimeRoutingContext>[
      for (final host in _eventHosts.reversed) ?host._context(),
    ];
    if (contexts.isEmpty) return null;
    final page = contexts.cast<ChatRuntimeRoutingContext?>().firstWhere(
      (context) => !context!.dispatchScoped,
      orElse: () => null,
    );
    final scoped = contexts.where((context) => context.dispatchScoped);

    final method = _eventMethodOf(event);
    final explicitConversationId = _routingInt(event['conversationId']);
    final scopedClaim = scoped.cast<ChatRuntimeRoutingContext?>().firstWhere(
      (context) =>
          explicitConversationId != null &&
          context!.scopedConversationId == explicitConversationId,
      orElse: () => null,
    );
    if (page == null) {
      // Without a page, only a dispatch-scoped surface listens, and it claims
      // nothing but its own explicit conversation while a prompt is in flight.
      if (scopedClaim == null) return null;
      return _applyRoutedEvent(
        event: event,
        conversationId: explicitConversationId!,
        mode: scopedClaim.activeMode,
        conversation: scopedClaim.scopedConversation,
      );
    }
    final eventSessionId = acpEventSessionId(event);
    final eventTurnId = acpEventTurnId(event);
    final remote = page.remote;
    final rawThreadId = remoteAgentEventThreadId(event);
    final eventThreadId = remote == null ? null : rawThreadId;
    final standaloneProcessId = _standaloneProcessIdOf(event);
    final standaloneProcessOwner = standaloneProcessId == null
        ? null
        : conversationIdForStandaloneProcess(standaloneProcessId);
    final hasProtocolIdentity =
        eventSessionId != null || eventTurnId != null || rawThreadId != null;
    final canUseVisibleFallback =
        method == 'error' || standaloneProcessId != null;
    final identityConversationId = explicitConversationId == null
        ? conversationIdForAcpEvent(
            sessionId: eventSessionId,
            turnId: eventTurnId,
          )
        : null;
    final mappedRemoteConversationId = eventThreadId == null
        ? null
        : remoteAgentRuntimeIdForThread(eventThreadId);
    final shouldPromoteRemoteEvent =
        eventThreadId != null &&
        _shouldPromoteRemoteThread(
          context: page,
          threadId: eventThreadId,
          runtimeId: mappedRemoteConversationId!,
        );
    String? promotedRemoteThreadId;
    final conversationId =
        explicitConversationId ??
        (shouldPromoteRemoteEvent
            ? (() {
                promotedRemoteThreadId = eventThreadId;
                return activateRemoteThreadRuntime(
                  eventThreadId,
                  fallbackMessages: remote!.agentFallbackMessages,
                  conversation: remote.agentConversation,
                );
              })()
            : mappedRemoteConversationId) ??
        identityConversationId ??
        standaloneProcessOwner ??
        (!hasProtocolIdentity && canUseVisibleFallback
            ? page.conversationIdsByMode[kChatRuntimeModeAgent]
            : null);
    if (conversationId == null) {
      debugPrint(
        '[Agent] dropping $method — no safe ACP owner '
        '(remoteCodex=${remote != null}, eventSessionId=$eventSessionId, '
        'eventTurnId=$eventTurnId, eventThreadId=$eventThreadId)',
      );
      return null;
    }
    if (eventThreadId != null && !shouldPromoteRemoteEvent) {
      ensureRemoteThreadRuntime(eventThreadId);
    }

    // A promotion makes the remote runtime the visible Agent conversation;
    // resolve the remaining facts as the page will see them afterwards.
    final conversationIds = <String, int?>{
      ...page.conversationIdsByMode,
      if (promotedRemoteThreadId != null) kChatRuntimeModeAgent: conversationId,
    };
    final ownerMode = modeForAcpEvent(
      conversationId: conversationId,
      sessionId: eventSessionId,
      turnId: eventTurnId,
    );
    final String mode;
    if (ownerMode != null) {
      mode = ownerMode;
    } else if (scopedClaim != null) {
      mode = scopedClaim.activeMode;
    } else {
      mode =
          remote != null ||
              conversationId == conversationIds[kChatRuntimeModeAgent] ||
              event['conversationMode'] == ConversationMode.agent.storageValue
          ? kChatRuntimeModeAgent
          : conversationId == conversationIds[kChatRuntimeModeNormal]
          ? kChatRuntimeModeNormal
          : page.activeMode;
    }

    final ConversationModel? visibleConversation;
    if (scopedClaim != null && scopedClaim.activeMode == mode) {
      visibleConversation = scopedClaim.scopedConversation;
    } else if (page.activeMode == mode &&
        conversationIds[mode] == conversationId) {
      visibleConversation = promotedRemoteThreadId != null
          ? _runtimeStateFor(
              conversationId: conversationId,
              mode: kChatRuntimeModeAgent,
            )?.conversation
          : page.conversationsByMode[mode];
    } else {
      visibleConversation = null;
    }

    return _applyRoutedEvent(
      event: event,
      conversationId: conversationId,
      mode: mode,
      conversation: visibleConversation,
      promotedRemoteThreadId: promotedRemoteThreadId,
    );
  }

  ChatRuntimeEventOutcome _applyRoutedEvent({
    required Map<String, dynamic> event,
    required int conversationId,
    required String mode,
    ConversationModel? conversation,
    String? promotedRemoteThreadId,
  }) {
    final result = applyAgentEvent(
      conversationId: conversationId,
      event: event,
      mode: mode,
      conversation: conversation,
    );
    if (!result.handled &&
        result.method != 'codex/stderr' &&
        result.method != 'codex/parseError') {
      debugPrint('[Agent] unhandled ACP event: ${jsonEncode(event)}');
    }
    return ChatRuntimeEventOutcome(
      event: event,
      conversationId: conversationId,
      mode: mode,
      result: result,
      promotedRemoteThreadId: promotedRemoteThreadId,
    );
  }
}
