import 'dart:convert';

import 'package:ui/models/conversation_model.dart';

class ConversationThreadTarget {
  const ConversationThreadTarget({
    required this.mode,
    this.conversationId,
    this.agentId,
    this.agentSessionId,
    this.agentRuntime,
    this.agentSessionActive,
    this.isNewConversation = false,
    this.fromNativeRoute = false,
    this.requestKey,
    this.initialMessage,
  });

  final int? conversationId;
  final String? agentId;
  final String? agentSessionId;
  final String? agentRuntime;
  final bool? agentSessionActive;
  final ConversationMode mode;
  final bool isNewConversation;
  final bool fromNativeRoute;
  final String? requestKey;
  final String? initialMessage;

  const ConversationThreadTarget.newConversation({
    this.mode = ConversationMode.agent,
    this.fromNativeRoute = false,
    this.requestKey,
    this.agentRuntime,
    this.agentId,
    this.initialMessage,
  }) : conversationId = null,
       agentSessionId = null,
       agentSessionActive = null,
       isNewConversation = true;

  const ConversationThreadTarget.existing({
    required this.conversationId,
    this.mode = ConversationMode.agent,
    this.fromNativeRoute = false,
    this.requestKey,
    this.agentId,
    this.agentSessionId,
    this.agentRuntime,
    this.agentSessionActive,
  }) : initialMessage = null,
       isNewConversation = false;

  const ConversationThreadTarget.agentSession({
    required String sessionId,
    String runtime = 'remote',
    this.agentId,
    this.agentSessionActive,
    this.fromNativeRoute = false,
    this.requestKey,
  }) : conversationId = null,
       agentSessionId = sessionId,
       agentRuntime = runtime,
       mode = ConversationMode.agent,
       initialMessage = null,
       isNewConversation = false;

  bool get hasConversationId => conversationId != null;
  bool get isAgentSessionTarget =>
      mode == ConversationMode.agent &&
      !isNewConversation &&
      (agentSessionId?.trim().isNotEmpty ?? false);
  bool get isRemoteCodexSessionTarget =>
      isAgentSessionTarget && (agentRuntime ?? '').trim() == 'remote';

  String get threadKey {
    final type = isNewConversation ? 'new' : 'existing';
    final idPart = agentSessionId?.trim().isNotEmpty == true
        ? 'agent-session:${agentSessionId!.trim()}'
        : conversationId?.toString() ?? 'none';
    return '${mode.canonicalStorageValue}:$type:$idPart';
  }

  ConversationThreadTarget copyWith({
    int? conversationId,
    String? agentId,
    String? agentSessionId,
    String? agentRuntime,
    bool? agentSessionActive,
    ConversationMode? mode,
    bool? isNewConversation,
    bool? fromNativeRoute,
    String? requestKey,
    String? initialMessage,
    bool clearRequestKey = false,
  }) {
    return ConversationThreadTarget(
      conversationId: conversationId ?? this.conversationId,
      agentId: agentId ?? this.agentId,
      agentSessionId: agentSessionId ?? this.agentSessionId,
      agentRuntime: agentRuntime ?? this.agentRuntime,
      agentSessionActive: agentSessionActive ?? this.agentSessionActive,
      mode: mode ?? this.mode,
      isNewConversation: isNewConversation ?? this.isNewConversation,
      fromNativeRoute: fromNativeRoute ?? this.fromNativeRoute,
      requestKey: clearRequestKey ? null : (requestKey ?? this.requestKey),
      initialMessage: initialMessage ?? this.initialMessage,
    );
  }

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'conversationId': conversationId,
      if (agentId != null && agentId!.isNotEmpty) 'agentId': agentId,
      if (agentSessionId != null && agentSessionId!.isNotEmpty)
        'agentSessionId': agentSessionId,
      if (agentRuntime != null && agentRuntime!.isNotEmpty)
        'agentRuntime': agentRuntime,
      if (agentSessionActive != null) 'agentSessionActive': agentSessionActive,
      'mode': mode.storageValue,
      'isNewConversation': isNewConversation,
      'fromNativeRoute': fromNativeRoute,
      if (requestKey != null && requestKey!.isNotEmpty)
        'requestKey': requestKey,
    };
  }

  factory ConversationThreadTarget.fromJson(Map<String, dynamic> json) {
    final conversationIdRaw = json['conversationId'];
    final conversationId = conversationIdRaw is int
        ? conversationIdRaw
        : int.tryParse(conversationIdRaw?.toString() ?? '');
    final isNewConversation = json['isNewConversation'] == true;
    return ConversationThreadTarget(
      conversationId: conversationId,
      agentId: json['agentId']?.toString(),
      mode: ConversationMode.fromStorageValue(json['mode'] as String?),
      isNewConversation: isNewConversation,
      fromNativeRoute: json['fromNativeRoute'] == true,
      requestKey: json['requestKey']?.toString(),
      agentSessionId: (json['agentSessionId'] ?? json['codexThreadId'])
          ?.toString(),
      agentRuntime: (json['agentRuntime'] ?? json['codexRuntime'])?.toString(),
      agentSessionActive: _boolFromJson(
        json['agentSessionActive'] ?? json['codexThreadActive'],
      ),
    );
  }

  String toEncodedJson() => jsonEncode(toJson());

  factory ConversationThreadTarget.fromEncodedJson(String raw) {
    final decoded = jsonDecode(raw);
    if (decoded is! Map) {
      throw FormatException('Invalid thread target json');
    }
    return ConversationThreadTarget.fromJson(
      decoded.map((key, value) => MapEntry(key.toString(), value)),
    );
  }

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) return true;
    return other is ConversationThreadTarget &&
        other.conversationId == conversationId &&
        other.agentId == agentId &&
        other.agentSessionId == agentSessionId &&
        other.agentRuntime == agentRuntime &&
        other.agentSessionActive == agentSessionActive &&
        other.mode == mode &&
        other.isNewConversation == isNewConversation &&
        other.fromNativeRoute == fromNativeRoute &&
        other.requestKey == requestKey &&
        other.initialMessage == initialMessage;
  }

  @override
  int get hashCode => Object.hash(
    conversationId,
    agentId,
    agentSessionId,
    agentRuntime,
    agentSessionActive,
    mode,
    isNewConversation,
    fromNativeRoute,
    requestKey,
    initialMessage,
  );

  @override
  String toString() {
    return 'ConversationThreadTarget('
        'conversationId: $conversationId, '
        'agentId: $agentId, '
        'agentSessionId: $agentSessionId, '
        'agentRuntime: $agentRuntime, '
        'agentSessionActive: $agentSessionActive, '
        'mode: ${mode.storageValue}, '
        'isNewConversation: $isNewConversation, '
        'fromNativeRoute: $fromNativeRoute, '
        'requestKey: $requestKey'
        ')';
  }
}

bool? _boolFromJson(dynamic value) {
  if (value is bool) {
    return value;
  }
  final normalized = value?.toString().trim().toLowerCase();
  if (normalized == null || normalized.isEmpty) {
    return null;
  }
  if (normalized == 'true' || normalized == '1') {
    return true;
  }
  if (normalized == 'false' || normalized == '0') {
    return false;
  }
  return null;
}
