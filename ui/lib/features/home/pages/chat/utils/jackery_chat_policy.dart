import 'package:flutter/widgets.dart';
import 'package:ui/config/jackery_ces_config.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/services/home_greeting_settings_service.dart';

/// Reuse the app's pure-chat Conversation and ACP owner. A legacy developer
/// conversation must not be reopened with a different lifecycle or tool policy.
ConversationThreadTarget cesConversationTarget(
  ConversationThreadTarget target,
) {
  if (target.mode == ConversationMode.chatOnly &&
      target.agentId == null &&
      target.agentSessionId == null) {
    return target;
  }
  return ConversationThreadTarget.newConversation(
    mode: ConversationMode.chatOnly,
    fromNativeRoute: target.fromNativeRoute,
    requestKey: target.requestKey,
    initialMessage: target.initialMessage,
  );
}

/// Translate known app errors and avoid exposing untranslated native diagnostics
/// to visitors. The owning runtime retains the original diagnostic in its logs.
String cesVisibleMessage(String message) {
  if (!JackeryCesConfig.enabled) return message;
  final translated = LegacyTextLocalizer.localize(
    message,
    locale: const Locale('en'),
  );
  if (RegExp(r'[\u3400-\u9fff]').hasMatch(translated)) {
    return 'Something went wrong. Please try again or ask a staff member.';
  }
  return translated;
}

const List<HomeQuickPrompt> jackeryDemoPrompts = <HomeQuickPrompt>[
  HomeQuickPrompt(
    id: 'jackery-ces-highlights',
    title: 'Catch up on CES',
    prompt: 'Search for the latest CES highlights and include sources.',
    iconKey: 'search',
    builtIn: true,
  ),
  HomeQuickPrompt(
    id: 'jackery-weekend',
    title: 'Plan an off-grid weekend',
    prompt: 'Help me plan an off-grid weekend. Ask where I am going, check the weather, and help estimate my power use without assuming device specifications.',
    iconKey: 'plan',
    builtIn: true,
  ),
];

/// Projection only. Search/reasoning activity stays in the one shared runtime;
/// visitors see user/assistant text and canonical terminal failures.
bool isCesConversationMessage(ChatMessageModel message) {
  if (message.type == 1) return message.user == 1 || message.user == 2;
  return message.streamMeta?['kind'] == 'error' &&
      message.cardData?['toolType'] == 'status';
}

ChatMessageModel cesConversationMessage(ChatMessageModel message) {
  if (message.type == 1 && !message.isError) return message;
  final text = message.type == 1
      ? message.text ?? ''
      : (message.cardData?['summary'] ?? message.cardData?['toolTitle'] ?? '')
            .toString();
  return ChatMessageModel(
    id: message.id,
    type: 1,
    user: message.user == 1 ? 1 : 2,
    createAt: message.createAt,
    isError: true,
    streamMeta: message.streamMeta,
    content: {'text': cesVisibleMessage(text)},
  );
}
