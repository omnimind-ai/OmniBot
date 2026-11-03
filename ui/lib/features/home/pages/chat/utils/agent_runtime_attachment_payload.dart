List<Map<String, dynamic>> buildAgentRuntimeAttachmentsFromMessageContent(
  Map<String, dynamic>? content,
) {
  final raw = content?['attachments'];
  if (raw is! List) {
    return const <Map<String, dynamic>>[];
  }
  return raw
      .whereType<Map>()
      .where((item) => item['historyPayloadOffloaded'] != true)
      .map((item) => item.map((key, value) => MapEntry(key.toString(), value)))
      .toList();
}
