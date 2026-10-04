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

/// Attribution facts a mounted chat surface publishes to the native runtime
/// owner, which routes every ACP event to exactly one runtime.
///
/// A [ChatRuntimeRoutingContext.page] surface resolves events by identity and
/// falls back to its visible conversation. A
/// [ChatRuntimeRoutingContext.dispatchScoped] surface (the command overlay
/// sheet) only claims events for its explicit conversation while it has a
/// prompt in flight.
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
  ChatRuntimeEventHost._(this._id, this._coordinator, this._context, this._onOutcome);

  final int _id;
  final ChatConversationRuntimeCoordinator _coordinator;

  /// Returns null while the surface does not claim any events.
  final ChatRuntimeRoutingContext? Function() _context;
  final void Function(ChatRuntimeEventOutcome outcome) _onOutcome;

  void detach() => _coordinator._detachEventHost(this);
}

/// Stable local runtime id of a remote Agent thread. Must match the native
/// `remoteAgentRuntimeIdForThread`.
int remoteAgentRuntimeIdForThread(String seed) {
  var hash = 0x45d9f3b;
  for (final codeUnit in seed.codeUnits) {
    hash = 0x1fffffff & (hash * 31 + codeUnit);
  }
  return -((hash & 0x3fffffff) + 1);
}

Map<String, dynamic> _routingContextToChannel(
  int hostId,
  ChatRuntimeRoutingContext context,
) {
  final remote = context.remote;
  return <String, dynamic>{
    'hostId': hostId,
    'dispatchScoped': context.dispatchScoped,
    'activeMode': context.activeMode,
    'conversationIdsByMode': context.conversationIdsByMode,
    'conversationsByMode': <String, dynamic>{
      for (final entry in context.conversationsByMode.entries)
        entry.key: entry.value?.toJson(),
    },
    if (remote != null)
      'remote': <String, dynamic>{
        'activeThreadId': remote.activeThreadId,
        'activeRemoteRuntimeId': remote.activeRemoteRuntimeId,
        'agentFallbackMessages': remote.agentFallbackMessages
            .map(chatMessageToChannel)
            .toList(growable: false),
        'agentConversation': remote.agentConversation?.toJson(),
      },
    'scopedConversationId': context.scopedConversationId,
    'scopedConversation': context.scopedConversation?.toJson(),
  };
}

/// Message JSON for the native owner; `createAt` as epoch millis.
Map<String, dynamic> chatMessageToChannel(ChatMessageModel message) =>
    <String, dynamic>{
      ...message.toJson(),
      'createAt': message.createAt.millisecondsSinceEpoch,
    };
