Map<String, dynamic>? ensureAgentStreamMessageMeta(
  Map<String, dynamic>? streamMeta, {
  int? seq,
  int? roundIndex,
  String? kind,
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
