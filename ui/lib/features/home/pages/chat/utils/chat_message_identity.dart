import 'package:ui/models/chat_message_model.dart';

/// A conversation entry ID identifies one logical row for its entire lifetime.
/// Keep the first slot (stable chronology) and the newest value (latest stream
/// snapshot) when a native snapshot or reducer replay contains the same ID.
List<ChatMessageModel> canonicalizeChatMessagesById(
  Iterable<ChatMessageModel> messages,
) {
  final canonical = <ChatMessageModel>[];
  final indexById = <String, int>{};
  for (final message in messages) {
    final id = message.id.trim();
    if (id.isEmpty) {
      canonical.add(message);
      continue;
    }
    final existingIndex = indexById[id];
    if (existingIndex == null) {
      indexById[id] = canonical.length;
      canonical.add(message);
    } else {
      canonical[existingIndex] = message;
    }
  }
  return canonical;
}
