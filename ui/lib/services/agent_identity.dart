/// Stable identities shared by the ACP adapter and the chat presentation.
///
/// These are deliberately data-only values. ACP owns the protocol fields;
/// the UI owns [cardId] and [runId]. No Harness-specific name belongs here.
class AgentRunIdentity {
  const AgentRunIdentity({
    required this.runId,
    required this.conversationId,
    this.sessionId,
    this.turnId,
    this.rpcRequestId,
  });

  final String runId;
  final int conversationId;
  final String? sessionId;
  final String? turnId;
  final String? rpcRequestId;

  /// The stable host identity for one UI run. This is intentionally separate
  /// from ACP's session/turn identifiers: a provider may admit the official
  /// turn after the local prompt has already been rendered.
  String get normalizedRunId => runId.trim();

  String? get normalizedSessionId => _normalizedOrNull(sessionId);

  String? get normalizedTurnId => _normalizedOrNull(turnId);
}

/// The identity carried by one projected ACP item/card.
///
/// ACP identifiers remain protocol identifiers. The UI only uses [runId] for
/// turn grouping and [cardId] for widget replacement. Keeping these scopes
/// explicit prevents a provider tool id from being used as a global card key.
class AgentEventIdentity {
  const AgentEventIdentity({
    required this.runId,
    this.conversationId,
    this.sessionId,
    this.turnId,
    this.itemId,
    this.toolCallId,
    this.cardId,
  });

  final String runId;
  final int? conversationId;
  final String? sessionId;
  final String? turnId;
  final String? itemId;
  final String? toolCallId;
  final String? cardId;

  String get scopedToolKey {
    final session = _normalizedOrNull(sessionId) ?? 'session-unknown';
    final turn = _normalizedOrNull(turnId) ?? 'turn-unknown';
    final tool = _normalizedOrNull(toolCallId) ?? 'tool-unknown';
    return '$session:$turn:$tool';
  }
}

class AgentToolIdentity {
  const AgentToolIdentity({
    this.sessionId,
    this.turnId,
    this.toolCallId,
    this.rawProviderToolCallId,
  });

  final String? sessionId;
  final String? turnId;

  /// The ACP identity. This is the value used to match tool_call_update.
  final String? toolCallId;

  /// The provider's original call id, when the adapter had to normalize it.
  final String? rawProviderToolCallId;

  bool get hasAcpIdentity => _nonEmpty(sessionId) && _nonEmpty(toolCallId);

  String? get toolKey {
    if (!hasAcpIdentity) return null;
    return '${sessionId!.trim()}:${toolCallId!.trim()}';
  }

  /// Derive a new card id without changing the ACP toolCallId itself.
  String cardId({required String suffix, required String fallback}) {
    final key = toolKey;
    if (key == null) return fallback;
    return 'tool:${_safeSegment(sessionId!)}:${_safeSegment(toolCallId!)}:$suffix';
  }

  static AgentToolIdentity fromMaps({
    Map<String, dynamic>? raw,
    Map<String, dynamic>? existing,
    String? sessionId,
    String? turnId,
  }) {
    final source = raw ?? const <String, dynamic>{};
    final old = existing ?? const <String, dynamic>{};
    final canonical = _firstString(<dynamic>[
      source['toolCallId'],
      source['tool_call_id'],
      old['toolCallId'],
      old['tool_call_id'],
      source['callId'],
      source['call_id'],
      source['id'],
      old['callId'],
      old['call_id'],
    ]);
    final rawProvider = _firstString(<dynamic>[
      source['rawProviderToolCallId'],
      source['raw_provider_tool_call_id'],
      source['providerCallId'],
      source['provider_call_id'],
      source['callId'],
      source['call_id'],
      old['rawProviderToolCallId'],
      old['callId'],
      old['call_id'],
    ]);
    return AgentToolIdentity(
      sessionId: _firstString(<dynamic>[
        sessionId,
        source['sessionId'],
        source['session_id'],
        old['sessionId'],
        old['session_id'],
      ]),
      turnId: _firstString(<dynamic>[
        turnId,
        source['turnId'],
        source['turn_id'],
        old['turnId'],
        old['turn_id'],
      ]),
      toolCallId: canonical,
      rawProviderToolCallId: rawProvider,
    );
  }
}

/// Read the official ACP lifecycle identity from an event envelope.
///
/// ACP notifications can arrive directly, wrapped in `message`, or through
/// the bridge's `payload`/`data`/`notification` envelope. The reducer already
/// accepts all of these shapes; the page-level routing must use the same
/// identity rules or a valid reasoning update can be reduced into a runtime
/// that is not currently visible.
String? acpEventSessionId(Map<String, dynamic> event) {
  return _acpEnvelopeIdentity(event, const <String>[
    'sessionId',
    'session_id',
    // `threadId` was the compatibility name used by app-server and the
    // pre-ACP Harness bridge. Accept it only at this boundary; all projected
    // state remains keyed by the canonical ACP session identity.
    'threadId',
    'thread_id',
  ]);
}

String? acpEventTurnId(Map<String, dynamic> event) {
  return _acpEnvelopeIdentity(event, const <String>[
    'turnId',
    'turn_id',
    // Legacy AgentStreamEvent called the ACP turn a task.  This is a read
    // compatibility alias, never a second runtime identity.
    'taskId',
    'task_id',
    'runId',
    'run_id',
  ]);
}

String? acpEventMessageId(Map<String, dynamic> event) {
  return _acpEnvelopeIdentity(event, const <String>[
    'messageId',
    'message_id',
    'entryId',
    'entry_id',
    'itemId',
    'item_id',
  ]);
}

String? acpEventItemId(Map<String, dynamic> event) {
  return _acpEnvelopeIdentity(event, const <String>[
    'itemId',
    'item_id',
    'toolCallId',
    'tool_call_id',
    'callId',
    'call_id',
  ]);
}

/// Returns whether the host explicitly reserved the first event for the
/// currently active local prompt. This is delivery metadata, not an ACP
/// protocol field. Keeping the envelope walk here gives routing, admission,
/// and reduction one interpretation of the reservation marker.
bool acpEventAllowsImplicitTurnAdmission(Map<String, dynamic> event) {
  return _acpEnvelopeContainsTrue(event, 'allowImplicitTurnAdmission');
}

/// Identifies the removed pre-ACP event shape at the compatibility boundary.
/// New ACP traffic must not inherit its unscoped admission semantics.
bool acpEventIsLegacyCompatibilityShape(Map<String, dynamic> event) {
  return event['legacyCompatibility'] == true ||
      event.containsKey('kind') ||
      event.containsKey('streamKind') ||
      event.containsKey('eventKind') ||
      event.containsKey('taskId') ||
      event.containsKey('task_id');
}

String? _acpEnvelopeIdentity(
  Map<String, dynamic> root,
  List<String> keys, {
  int depth = 0,
}) {
  if (depth > 6) return null;

  for (final key in keys) {
    final value = _firstString(<dynamic>[root[key]]);
    if (value != null) return value;
  }

  // `params` is included explicitly because ACP notifications normally place
  // session/update identity there. The remaining keys cover the bridge
  // envelopes without treating arbitrary payload fields as identities.
  for (final key in const <String>[
    'params',
    'message',
    'payload',
    'data',
    'event',
    'notification',
    'result',
  ]) {
    final nested = root[key];
    if (nested is! Map) continue;
    final nestedMap = nested.map(
      (key, value) => MapEntry(key.toString(), value),
    );
    final value = _acpEnvelopeIdentity(nestedMap, keys, depth: depth + 1);
    if (value != null) return value;
  }
  return null;
}

bool _acpEnvelopeContainsTrue(
  Map<String, dynamic> root,
  String key, {
  int depth = 0,
}) {
  if (depth > 6) return false;
  if (root[key] == true) return true;
  for (final envelopeKey in const <String>[
    'params',
    'message',
    'payload',
    'data',
    'event',
    'notification',
    'result',
  ]) {
    final nested = root[envelopeKey];
    if (nested is! Map) continue;
    final nestedMap = nested.map(
      (key, value) => MapEntry(key.toString(), value),
    );
    if (_acpEnvelopeContainsTrue(nestedMap, key, depth: depth + 1)) {
      return true;
    }
  }
  return false;
}

bool _nonEmpty(String? value) => value?.trim().isNotEmpty == true;

String? _normalizedOrNull(String? value) {
  final normalized = value?.trim() ?? '';
  return normalized.isEmpty ? null : normalized;
}

/// The key used for a protocol turn lookup. A turn id is normally unique only
/// inside an ACP session, so the session must be part of the lookup whenever
/// it is available. The turn-only fallback keeps legacy providers working.
String acpTurnKey({String? sessionId, String? turnId}) {
  final normalizedTurnId = _normalizedOrNull(turnId);
  if (normalizedTurnId == null) return '';
  final normalizedSessionId = _normalizedOrNull(sessionId);
  return normalizedSessionId == null
      ? normalizedTurnId
      : '$normalizedSessionId:$normalizedTurnId';
}

String? _firstString(Iterable<dynamic> values) {
  for (final value in values) {
    if (value == null) continue;
    final text = value.toString().trim();
    if (text.isNotEmpty) return text;
  }
  return null;
}

String _safeSegment(String value) {
  return value.trim().replaceAll(RegExp(r'[^a-zA-Z0-9._:-]'), '_');
}
