import 'package:ui/models/chat_message_model.dart';

/// Presentation only: the shared runtime retains every tool/reasoning item.
bool isNaConversationMessage(ChatMessageModel message) {
  if (message.type == 1) return message.user == 1 || message.user == 2;
  return message.streamMeta?['kind'] == 'error' &&
      message.cardData?['toolType'] == 'status';
}

/// Keep canonical terminal failures readable even without tool cards.
ChatMessageModel naConversationMessage(ChatMessageModel message) {
  if (message.type == 1) return message;
  final card = message.cardData;
  return ChatMessageModel(
    id: message.id,
    type: 1,
    user: 2,
    createAt: message.createAt,
    isError: true,
    streamMeta: message.streamMeta,
    content: {'text': card?['summary'] ?? card?['toolTitle'] ?? ''},
  );
}

bool showNaConversationTime(DateTime current, DateTime? previous) {
  if (previous == null) return true;
  final local = current.toLocal();
  final prior = previous.toLocal();
  return local.year != prior.year ||
      local.month != prior.month ||
      local.day != prior.day ||
      current.difference(previous) >= const Duration(minutes: 5);
}

String naConversationTimeLabel(
  DateTime value, {
  required bool english,
  DateTime? now,
}) {
  final date = value.toLocal();
  final today = (now ?? DateTime.now()).toLocal();
  final day = DateTime(date.year, date.month, date.day);
  final todayDay = DateTime(today.year, today.month, today.day);
  final clock =
      '${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
  if (day == todayDay) return '${english ? 'Today' : '今天'} $clock';
  if (day == DateTime(today.year, today.month, today.day - 1)) {
    return '${english ? 'Yesterday' : '昨天'} $clock';
  }
  final prefix = date.year == today.year
      ? ''
      : '${date.year}${english ? '/' : '年'}';
  return english
      ? '$prefix${date.month}/${date.day} $clock'
      : '$prefix${date.month}月${date.day}日 $clock';
}
