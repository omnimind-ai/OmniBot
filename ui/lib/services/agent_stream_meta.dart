Map<String, dynamic>? ensureAgentStreamMessageMeta(
  Map<String, dynamic>? streamMeta, {
  int? seq,
  int? roundIndex,
  String? kind,
  String? runId,
  String? sessionId,
  String? turnId,
  String? itemId,
  String? toolCallId,
  String? cardId,
  String? parentTaskId,
  String? entryId,
  bool isFinal = false,
}) {
  final normalized = Map<String, dynamic>.from(streamMeta ?? const {});
  final hasInput =
      normalized.isNotEmpty ||
      seq != null ||
      roundIndex != null ||
      (kind?.trim().isNotEmpty ?? false) ||
      (runId?.trim().isNotEmpty ?? false) ||
      (sessionId?.trim().isNotEmpty ?? false) ||
      (turnId?.trim().isNotEmpty ?? false) ||
      (itemId?.trim().isNotEmpty ?? false) ||
      (toolCallId?.trim().isNotEmpty ?? false) ||
      (cardId?.trim().isNotEmpty ?? false) ||
      (parentTaskId?.trim().isNotEmpty ?? false) ||
      (entryId?.trim().isNotEmpty ?? false) ||
      isFinal;
  if (!hasInput) {
    return null;
  }

  if (seq != null) {
    normalized['seq'] = seq;
  }
  if (roundIndex != null) {
    normalized['roundIndex'] = roundIndex;
  }
  final normalizedKind = kind?.trim() ?? '';
  if (normalizedKind.isNotEmpty) {
    normalized['kind'] = normalizedKind;
  }
  void putString(String key, String? value) {
    final normalizedValue = value?.trim() ?? '';
    if (normalizedValue.isNotEmpty) {
      normalized[key] = normalizedValue;
    }
  }

  putString('runId', runId);
  putString('sessionId', sessionId);
  putString('turnId', turnId);
  putString('itemId', itemId);
  putString('toolCallId', toolCallId);
  putString('cardId', cardId);
  final normalizedTaskId = parentTaskId?.trim() ?? '';
  if (normalizedTaskId.isNotEmpty) {
    normalized['parentTaskId'] = normalizedTaskId;
  }
  final normalizedEntryId = entryId?.trim() ?? '';
  if (normalizedEntryId.isNotEmpty) {
    normalized['entryId'] = normalizedEntryId;
  }

  normalized['isFinal'] = isFinal || normalized['isFinal'] == true;
  return normalized;
}
