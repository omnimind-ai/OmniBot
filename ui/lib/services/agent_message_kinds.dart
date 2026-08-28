import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_tool_call_parser.dart';

const String kAgentToolUiStyle = 'agent_tool';
const String kAgentToolSummaryCardType = 'agent_tool_summary';
const String kAgentRequestCardType = 'agent_request';

// Read-only compatibility for conversation snapshots created before Agent mode
// used the shared ACP tool card types.
const String _legacyAgentToolUiStyle = 'codex_tool';
const String _legacyAgentRequestCardType = 'codex_request';

bool isAgentToolUiStyle(Object? value) {
  final normalized = value?.toString().trim() ?? '';
  return normalized == kAgentToolUiStyle ||
      normalized == _legacyAgentToolUiStyle;
}

String canonicalAgentToolUiStyle(Object? value) {
  return isAgentToolUiStyle(value)
      ? kAgentToolUiStyle
      : (value?.toString().trim() ?? '');
}

bool isAgentRequestCardType(Object? value) {
  final normalized = value?.toString().trim() ?? '';
  return normalized == kAgentRequestCardType ||
      normalized == _legacyAgentRequestCardType;
}

String canonicalAgentRequestCardType(Object? value) {
  return isAgentRequestCardType(value)
      ? kAgentRequestCardType
      : (value?.toString().trim() ?? '');
}

/// Whether the message is a tool-summary card, from any agent.
///
/// The built-in assistant and the ACP agents share this card type; use
/// [isAcpAgentToolSummaryMessage] when the distinction matters. These two
/// predicates previously existed as three private copies with two different
/// definitions, so the same card counted as a tool call in one file and not in
/// another.
bool isAgentToolSummaryMessage(ChatMessageModel message) {
  final type = (message.cardData?['type'] ?? '').toString().trim();
  return type == kAgentToolSummaryCardType ||
      type == 'tool_call' ||
      type == 'tool_call_update' ||
      type == 'commandExecution' ||
      type == 'command_execution' ||
      type == 'mcpToolCall' ||
      type == 'mcp_tool_call' ||
      type == 'webSearch' ||
      type == 'web_search' ||
      type == 'fileChange' ||
      type == 'file_change' ||
      type == 'plan' ||
      type == 'todo_list';
}

/// Whether a tool-summary message is an ACP plan snapshot.
///
/// Plans are stateful protocol data rather than transient tool activity. Keep
/// this predicate shared so the timeline can leave the plan card visible while
/// its `planEntries` are replaced by subsequent ACP updates.
bool isAgentPlanMessage(ChatMessageModel message) {
  final card = message.cardData;
  if (card == null) return false;
  final type = (card['type'] ?? '').toString().trim().toLowerCase();
  if (type == 'plan' || type == 'todo_list') return true;
  if (type != kAgentToolSummaryCardType) return false;
  return (card['toolType'] ?? '').toString().trim().toLowerCase() == 'plan' ||
      (card['toolName'] ?? '').toString().trim().toLowerCase() == 'plan';
}

/// Whether the message is a tool-summary card produced by an ACP agent.
bool isAcpAgentToolSummaryMessage(ChatMessageModel message) {
  final cardData = message.cardData;
  return isAgentToolSummaryMessage(message) &&
      isAgentToolUiStyle(cardData?['uiStyle']);
}

/// Whether the message is a request card (approval / user input).
bool isAgentRequestMessage(ChatMessageModel message) {
  return isAgentRequestCardType(message.cardData?['type']);
}

/// ACP user-input requests are transport state, not conversation content.
/// They are answered through the single chat composer and therefore must not
/// be rendered as a second input card in the timeline.
bool isAgentUserInputRequestMessage(ChatMessageModel message) {
  if (!isAgentRequestMessage(message)) {
    return false;
  }
  return (message.cardData?['requestKind'] ?? '').toString().trim() ==
      'user_input';
}

/// ACP permission requests are transport state as well. They may still be
/// answered by the runtime when a Harness explicitly asks for confirmation,
/// but they use the shared compact request card in the timeline.
bool isAgentApprovalRequestMessage(ChatMessageModel message) {
  if (!isAgentRequestMessage(message)) {
    return false;
  }
  return (message.cardData?['requestKind'] ?? '').toString().trim() ==
      'approval';
}

/// Whether the text reads as the "task cancelled" marker.
///
/// Three producers mint this body — the native runtime, the chat page, and the
/// ACP reducer — and three separate readers used to match it with three copies
/// of the same literal triple.
bool isCancelledTaskText(ChatMessageModel message) {
  final text = (message.text ?? '').trim().toLowerCase();
  return text == '任务已取消' || text == 'task canceled' || text == 'task cancelled';
}

ChatMessageModel canonicalizeAgentHistoryMessage(ChatMessageModel message) {
  final sourceCardData = message.cardData;
  if (sourceCardData == null) {
    return message;
  }
  final cardData = Map<String, dynamic>.from(sourceCardData);
  var changed = false;
  if (isAgentRequestCardType(cardData['type'])) {
    final type = canonicalAgentRequestCardType(cardData['type']);
    if (type != cardData['type']) {
      cardData['type'] = type;
      changed = true;
    }
  }
  if (isAgentToolUiStyle(cardData['uiStyle'])) {
    final uiStyle = canonicalAgentToolUiStyle(cardData['uiStyle']);
    if (uiStyle != cardData['uiStyle']) {
      cardData['uiStyle'] = uiStyle;
      changed = true;
    }
  }
  final rawToolName = cardData['toolName']?.toString();
  if (rawToolName != null) {
    final toolName = canonicalAgentToolName(rawToolName);
    if (toolName != rawToolName) {
      cardData['toolName'] = toolName;
      changed = true;
    }
  }
  if (!changed) {
    return message;
  }
  return message.copyWith(
    content: <String, dynamic>{
      ...?message.content,
      'cardData': cardData,
      'id': message.contentId ?? message.id,
    },
  );
}
