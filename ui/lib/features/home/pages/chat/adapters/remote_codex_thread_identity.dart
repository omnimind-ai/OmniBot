part of '../chat_page.dart';

int? _asAgentInt(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '');
}

String? _asAgentString(dynamic value) {
  final text = value?.toString().trim() ?? '';
  return text.isEmpty ? null : text;
}

String? _remoteCodexEventThreadId(Map<String, dynamic> event) {
  return _remoteCodexThreadIdFromEnvelope(event);
}

/// Top-level diagnostic counter that survives navigation. Used purely for
/// `flutter logs` / `adb logcat` introspection — the user reported that
/// exec_command tool cards do not surface in our UI even though the codex
/// session rollout contains 18 of them; this counter shows whether the
/// `rawResponseItem/completed` notifications actually reach the Flutter side.
final Map<String, int> _agentEventDiagnosticCounter = <String, int>{};

String _diagnosticEventMethod(Map<String, dynamic> event) {
  final method = _asAgentString(event['method']);
  if (method != null) {
    if (method == 'rawResponseItem/completed' ||
        method == 'item/started' ||
        method == 'item/completed') {
      final params = _asAgentMap(event['params']) ?? const <String, dynamic>{};
      final item = _asAgentMap(params['item']);
      final itemType = _asAgentString(item?['type']);
      if (itemType != null) {
        final name = _asAgentString(item?['name']);
        if (name != null) {
          return '$method:$itemType:$name';
        }
        return '$method:$itemType';
      }
    }
    return method;
  }
  final message = _asAgentMap(event['message']);
  return _asAgentString(message?['method']) ?? '<unknown>';
}

const List<String> _remoteCodexEnvelopeKeys = <String>[
  'message',
  'payload',
  'data',
  'event',
  'notification',
  'params',
  'result',
];

String? _remoteCodexThreadIdFromEnvelope(dynamic value, {int depth = 0}) {
  if (depth > 6) {
    return null;
  }
  final map = _asAgentMap(value);
  if (map == null) {
    return null;
  }
  final direct = _asAgentString(map['threadId'] ?? map['thread_id']);
  if (direct != null) {
    return direct;
  }
  final thread = _asAgentMap(map['thread']);
  final threadId = _asAgentString(thread?['id']);
  if (threadId != null) {
    return threadId;
  }
  for (final key in _remoteCodexEnvelopeKeys) {
    final nested = map[key];
    if (nested == null) {
      continue;
    }
    final nestedThreadId = _remoteCodexThreadIdFromEnvelope(
      nested,
      depth: depth + 1,
    );
    if (nestedThreadId != null) {
      return nestedThreadId;
    }
  }
  return null;
}

int _remoteCodexRuntimeId(String seed) {
  var hash = 0x45d9f3b;
  for (final codeUnit in seed.codeUnits) {
    hash = 0x1fffffff & (hash * 31 + codeUnit);
  }
  return -((hash & 0x3fffffff) + 1);
}

ConversationModel _remoteCodexConversationFromResponse({
  required int runtimeId,
  required Map<String, dynamic> response,
}) {
  final thread = _asAgentMap(response['thread']) ?? response;
  final now = DateTime.now().millisecondsSinceEpoch;
  final createdAt =
      _remoteCodexTimeValueMs(thread['createdAt'] ?? thread['created_at']) ??
      now;
  final updatedAt =
      _remoteCodexTimeValueMs(
        thread['updatedAt'] ??
            thread['updated_at'] ??
            thread['lastActivityAt'] ??
            thread['last_activity_at'],
      ) ??
      createdAt;
  final title =
      _asAgentString(
        thread['name'] ??
            thread['title'] ??
            thread['preview'] ??
            response['name'] ??
            response['title'] ??
            response['preview'],
      ) ??
      'Agent';
  return ConversationModel(
    id: runtimeId,
    mode: ConversationMode.agent,
    title: _truncateAgentText(title, 40),
    status: 0,
    lastMessage: _asAgentString(thread['preview']),
    messageCount: _remoteCodexMessagesFromThreadResponse(response).length,
    createdAt: createdAt,
    updatedAt: updatedAt,
  );
}

class _AgentThreadActivityState {
  const _AgentThreadActivityState({required this.known, required this.active});

  final bool known;
  final bool active;

  static const unknown = _AgentThreadActivityState(known: false, active: false);
  static const activeState = _AgentThreadActivityState(
    known: true,
    active: true,
  );
  static const inactiveState = _AgentThreadActivityState(
    known: true,
    active: false,
  );
}

_AgentThreadActivityState _remoteCodexThreadActivityFromResponse(
  Map<String, dynamic> response,
) {
  final thread = _asAgentMap(response['thread']) ?? response;
  _AgentThreadActivityState? inactiveCandidate;
  for (final value in <dynamic>[
    response['active'],
    response['isActive'],
    response['is_active'],
    response['status'],
    response['state'],
    response['turnStatus'],
    response['turn_status'],
    thread['active'],
    thread['isActive'],
    thread['is_active'],
    thread['status'],
    thread['state'],
    thread['turnStatus'],
    thread['turn_status'],
  ]) {
    final parsed = _remoteCodexActivityFromValue(value);
    if (parsed == null) {
      continue;
    }
    if (parsed.active) {
      return parsed;
    }
    inactiveCandidate ??= parsed;
  }
  final latestTurnActivity = _remoteCodexLatestTurnActivityFromResponse(
    response,
  );
  if (latestTurnActivity != null) {
    return latestTurnActivity;
  }
  if (inactiveCandidate != null) {
    return inactiveCandidate;
  }
  return _AgentThreadActivityState.unknown;
}

_AgentThreadActivityState? _remoteCodexLatestTurnActivityFromResponse(
  Map<String, dynamic> response,
) {
  final turns = _remoteCodexTurnsFromThreadResponse(response);
  if (turns == null) {
    return null;
  }
  for (var index = turns.length - 1; index >= 0; index -= 1) {
    final turn = _asAgentMap(turns[index]);
    if (turn == null) {
      continue;
    }
    final parsed = _remoteCodexActivityFromValue(
      turn['status'] ?? turn['state'],
    );
    if (parsed != null) {
      return parsed;
    }
  }
  return null;
}

_AgentThreadActivityState? _remoteCodexActivityFromValue(dynamic value) {
  if (value is bool) {
    return value
        ? _AgentThreadActivityState.activeState
        : _AgentThreadActivityState.inactiveState;
  }
  final status = _remoteCodexStatusText(value);
  if (status == null) {
    return null;
  }
  final normalized = _normalizeAgentRuntimeStatus(status);
  if (_remoteCodexStatusIsActive(normalized)) {
    return _AgentThreadActivityState.activeState;
  }
  if (_remoteCodexStatusIsInactive(normalized)) {
    return _AgentThreadActivityState.inactiveState;
  }
  return null;
}

String? _remoteCodexStatusText(dynamic value) {
  if (value == null) {
    return null;
  }
  if (value is String || value is num || value is bool) {
    return _asAgentString(value);
  }
  final map = _asAgentMap(value);
  if (map != null) {
    for (final key in const <String>[
      'type',
      'status',
      'state',
      'value',
      'name',
    ]) {
      final text = _remoteCodexStatusText(map[key]);
      if (text != null) {
        return text;
      }
    }
  }
  return null;
}

String _normalizeAgentRuntimeStatus(String status) =>
    status.trim().toLowerCase().replaceAll(RegExp(r'[^a-z0-9]+'), '');

bool _remoteCodexStatusIsActive(String status) {
  return status == 'running' ||
      status == 'active' ||
      status == 'busy' ||
      status == 'inprogress' ||
      status == 'inflight' ||
      status == 'executing';
}

bool _remoteCodexStatusIsInactive(String status) {
  return status == 'idle' ||
      status == 'closed' ||
      status == 'completed' ||
      status == 'complete' ||
      status == 'notloaded' ||
      status == 'systemerror' ||
      status == 'failed' ||
      status == 'cancelled' ||
      status == 'canceled' ||
      status == 'interrupted';
}

String? _remoteCodexActiveTurnIdFromThreadResponse(
  Map<String, dynamic> response,
) {
  final thread = _asAgentMap(response['thread']) ?? response;
  final status =
      _asAgentMap(response['status']) ?? _asAgentMap(thread['status']);
  final direct = _asAgentString(
    response['turnId'] ??
        response['turn_id'] ??
        response['activeTurnId'] ??
        response['active_turn_id'] ??
        response['currentTurnId'] ??
        response['current_turn_id'] ??
        thread['turnId'] ??
        thread['turn_id'] ??
        thread['activeTurnId'] ??
        thread['active_turn_id'] ??
        thread['currentTurnId'] ??
        thread['current_turn_id'] ??
        status?['turnId'] ??
        status?['turn_id'] ??
        status?['activeTurnId'] ??
        status?['active_turn_id'],
  );
  if (direct != null) {
    return direct;
  }
  final turns = _remoteCodexTurnsFromThreadResponse(response);
  if (turns == null) {
    return null;
  }
  for (var index = turns.length - 1; index >= 0; index -= 1) {
    final turn = _asAgentMap(turns[index]);
    if (turn == null) {
      continue;
    }
    final parsed = _remoteCodexActivityFromValue(
      turn['status'] ?? turn['state'],
    );
    if (parsed?.active == true) {
      return _remoteCodexTurnIdAt(turns, index);
    }
  }
  return null;
}

String? _remoteCodexLatestTurnIdFromThreadResponse(
  Map<String, dynamic> response,
) {
  final turns = _remoteCodexTurnsFromThreadResponse(response);
  if (turns == null || turns.isEmpty) {
    return null;
  }
  for (var index = turns.length - 1; index >= 0; index -= 1) {
    final turnId = _remoteCodexTurnIdAt(turns, index);
    if (turnId != null) {
      return turnId;
    }
  }
  return null;
}

bool _remoteCodexThreadResponseHasTurns(Map<String, dynamic> response) {
  return _remoteCodexTurnsFromThreadResponse(response) != null;
}

List<dynamic>? _remoteCodexTurnsFromThreadResponse(
  Map<String, dynamic> response,
) {
  final thread = _asAgentMap(response['thread']) ?? response;
  final rawTurns = thread['turns'] ?? response['turns'];
  return rawTurns is List ? rawTurns : null;
}

String? _remoteCodexTurnIdAt(List<dynamic> turns, int index) {
  if (index < 0 || index >= turns.length) {
    return null;
  }
  final turn = _asAgentMap(turns[index]);
  if (turn == null) {
    return null;
  }
  return _asAgentString(turn['id']) ?? 'turn-$index';
}
