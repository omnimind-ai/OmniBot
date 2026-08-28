import 'dart:convert';

import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/terminal_output_utils.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_diff_parser.dart';
import 'package:ui/services/agent_message_kinds.dart';

export 'package:ui/services/agent_message_kinds.dart'
    show kAgentToolSummaryCardType;

const String kAgentToolTitleField = 'toolTitle';

class AgentToolActivitySnapshot {
  const AgentToolActivitySnapshot({
    required this.messages,
    required this.isActiveRun,
    this.taskId,
  });

  final List<ChatMessageModel> messages;
  final bool isActiveRun;
  final String? taskId;
}

bool shouldShowAgentToolActivitySnapshot(
  AgentToolActivitySnapshot snapshot, {
  Set<String> expandedTaskIds = const <String>{},
}) {
  if (snapshot.messages.isEmpty) {
    return false;
  }
  if (snapshot.isActiveRun) {
    return true;
  }
  final taskId = snapshot.taskId?.trim() ?? '';
  if (taskId.isEmpty) {
    return false;
  }
  final normalizedExpandedTaskIds = expandedTaskIds
      .map((item) => item.trim())
      .where((item) => item.isNotEmpty)
      .toSet();
  return normalizedExpandedTaskIds.contains(taskId);
}

List<Map<String, dynamic>> extractAgentToolCards(
  List<ChatMessageModel> messages,
) {
  return messages
      .map((message) => message.cardData)
      .whereType<Map<String, dynamic>>()
      .where(
        (cardData) =>
            (cardData['type'] ?? '').toString() == kAgentToolSummaryCardType,
      )
      .toList(growable: false);
}

List<Map<String, dynamic>> extractRunningAgentToolCards(
  List<ChatMessageModel> messages,
) {
  return extractAgentToolCards(messages)
      .where((cardData) => (cardData['status'] ?? '').toString() == 'running')
      .toList(growable: false);
}

List<ChatMessageModel> filterAgentToolMessagesByTaskIds(
  List<ChatMessageModel> messages,
  Set<String> taskIds,
) {
  final normalizedTaskIds = taskIds
      .map((item) => item.trim())
      .where((item) => item.isNotEmpty)
      .toSet();
  if (normalizedTaskIds.isEmpty) {
    return const <ChatMessageModel>[];
  }
  return messages
      .where((message) {
        if ((message.cardData?['type'] ?? '').toString() !=
            kAgentToolSummaryCardType) {
          return false;
        }
        final taskId = resolveAgentToolTaskId(message);
        return taskId != null && normalizedTaskIds.contains(taskId);
      })
      .toList(growable: false);
}

AgentToolActivitySnapshot resolveAgentToolActivitySnapshot(
  List<ChatMessageModel> messages, {
  Set<String> activeTaskIds = const <String>{},
  String? preferredCompletedTaskId,
}) {
  final normalizedActiveTaskIds = activeTaskIds
      .map((item) => item.trim())
      .where((item) => item.isNotEmpty)
      .toSet();
  final normalizedPreferredCompletedTaskId =
      preferredCompletedTaskId?.trim() ?? '';
  final activeMessages = filterAgentToolMessagesByTaskIds(
    messages,
    normalizedActiveTaskIds,
  );
  if (activeMessages.isNotEmpty) {
    return AgentToolActivitySnapshot(
      messages: activeMessages,
      isActiveRun: true,
      taskId:
          _resolveSnapshotTaskId(activeMessages) ??
          (normalizedActiveTaskIds.length == 1
              ? normalizedActiveTaskIds.first
              : null),
    );
  }
  if (normalizedActiveTaskIds.isNotEmpty) {
    return AgentToolActivitySnapshot(
      messages: <ChatMessageModel>[],
      isActiveRun: true,
      taskId: normalizedActiveTaskIds.length == 1
          ? normalizedActiveTaskIds.first
          : null,
    );
  }
  if (normalizedPreferredCompletedTaskId.isNotEmpty) {
    return AgentToolActivitySnapshot(
      messages: resolveAgentToolMessagesForTask(
        messages,
        normalizedPreferredCompletedTaskId,
      ),
      isActiveRun: false,
      taskId: normalizedPreferredCompletedTaskId,
    );
  }
  final latestCompletedRun = _resolveLatestCompletedAgentToolRun(messages);
  return AgentToolActivitySnapshot(
    messages: latestCompletedRun?.messages ?? const <ChatMessageModel>[],
    isActiveRun: false,
    taskId: latestCompletedRun?.taskId,
  );
}

List<ChatMessageModel> resolveLatestCompletedAgentToolMessages(
  List<ChatMessageModel> messages,
) {
  return _resolveLatestCompletedAgentToolRun(messages)?.messages ??
      const <ChatMessageModel>[];
}

List<ChatMessageModel> resolveAgentToolMessagesForTask(
  List<ChatMessageModel> messages,
  String taskId,
) {
  final normalizedTaskId = taskId.trim();
  if (normalizedTaskId.isEmpty || messages.isEmpty) {
    return const <ChatMessageModel>[];
  }
  final timelineEntries = buildAgentRunTimelineEntries(messages);
  for (final entry in timelineEntries) {
    final group = entry.group;
    if (group == null ||
        group.taskId != normalizedTaskId ||
        group.toolCount == 0) {
      continue;
    }
    return group.processMessagesNewestFirst
        .where(isAgentToolSummaryMessage)
        .toList(growable: false);
  }
  return const <ChatMessageModel>[];
}

String? resolveAgentToolTaskId(ChatMessageModel message) {
  return message.runId;
}

Map<String, dynamic>? resolveActiveAgentToolCard(
  List<Map<String, dynamic>> cards,
) {
  for (final card in cards) {
    final status = (card['status'] ?? '').toString().trim().toLowerCase();
    if (status == 'running' || status == 'pending') {
      return card;
    }
  }
  if (cards.isEmpty) {
    return null;
  }
  return cards.first;
}

String? _resolveSnapshotTaskId(List<ChatMessageModel> messages) {
  for (final message in messages) {
    final taskId = resolveAgentToolTaskId(message);
    if (taskId != null) {
      return taskId;
    }
  }
  return null;
}

_CompletedAgentToolRun? _resolveLatestCompletedAgentToolRun(
  List<ChatMessageModel> messages,
) {
  if (messages.isEmpty) {
    return null;
  }
  final timelineEntries = buildAgentRunTimelineEntries(messages);
  if (timelineEntries.isEmpty) {
    return null;
  }
  final latestEntry = timelineEntries.first;
  final group = latestEntry.group;
  if (group == null || group.toolCount == 0) {
    return null;
  }
  return _CompletedAgentToolRun(
    taskId: group.taskId,
    messages: group.processMessagesNewestFirst
        .where(isAgentToolSummaryMessage)
        .toList(growable: false),
  );
}

class _CompletedAgentToolRun {
  const _CompletedAgentToolRun({required this.taskId, required this.messages});

  final String taskId;
  final List<ChatMessageModel> messages;
}

String resolveAgentToolTitle(Map<String, dynamic> cardData) {
  final explicit = (cardData[kAgentToolTitleField] ?? '').toString().trim();
  if (explicit.isNotEmpty) {
    return LegacyTextLocalizer.localize(explicit);
  }

  final fromArgs = _extractToolTitleFromArgs(
    (cardData['argsJson'] ?? '').toString(),
  );
  if (fromArgs.isNotEmpty) {
    return LegacyTextLocalizer.localize(fromArgs);
  }

  final summary = (cardData['summary'] ?? '').toString().trim();
  if (summary.isNotEmpty) {
    return LegacyTextLocalizer.localize(summary);
  }

  final displayName = (cardData['displayName'] ?? '工具调用').toString().trim();
  final serverName = (cardData['serverName'] ?? '').toString().trim();
  if ((cardData['toolType'] ?? '').toString() == 'mcp' &&
      serverName.isNotEmpty) {
    return '${LegacyTextLocalizer.localize(displayName)} · $serverName';
  }
  return LegacyTextLocalizer.localize(
    displayName.isEmpty ? '工具调用' : displayName,
  );
}

String resolveAgentToolTerminalOutput(Map<String, dynamic> cardData) {
  return TerminalOutputUtils.buildDisplayOutput(
    terminalOutput: (cardData['terminalOutput'] ?? '').toString(),
    rawResultJson: (cardData['rawResultJson'] ?? '').toString(),
    resultPreviewJson: (cardData['resultPreviewJson'] ?? '').toString(),
  );
}

String resolveAgentToolPreview(Map<String, dynamic> cardData) {
  if (isAgentToolAwaitingConfirmation(cardData)) {
    final question = (cardData['question'] ?? '').toString().trim();
    if (question.isNotEmpty) {
      return LegacyTextLocalizer.localize(question);
    }
  }
  final toolType = (cardData['toolType'] ?? '').toString();
  if (toolType == 'terminal') {
    final output = resolveAgentToolTerminalOutput(cardData).trim();
    if (output.isNotEmpty) {
      final nonEmptyLines = output
          .split('\n')
          .map((line) => line.trimRight())
          .where((line) => line.trim().isNotEmpty)
          .toList(growable: false);
      if (nonEmptyLines.isNotEmpty) {
        return nonEmptyLines.last;
      }
      return output;
    }
  }
  if (toolType == 'file') {
    final additions = _asNonNegativeInt(cardData['additions']);
    final deletions = _asNonNegativeInt(cardData['deletions']);
    final changedFiles = _asNonNegativeInt(cardData['changedFiles']);
    if (changedFiles > 0 || additions > 0 || deletions > 0) {
      final fileLabel = changedFiles <= 1
          ? LegacyTextLocalizer.localize('1 个文件')
          : LegacyTextLocalizer.localize('$changedFiles 个文件');
      return '$fileLabel · ${formatAgentDiffStat(additions: additions, deletions: deletions)}';
    }
    final filePath = (cardData['filePath'] ?? '').toString().trim();
    if (filePath.isNotEmpty) {
      return filePath;
    }
  }

  final progress = (cardData['progress'] ?? '').toString().trim();
  final summary = (cardData['summary'] ?? '').toString().trim();
  final title = resolveAgentToolTitle(cardData);
  if (progress.isNotEmpty && progress != title) {
    return LegacyTextLocalizer.localize(progress);
  }
  if (summary.isNotEmpty && summary != title) {
    return LegacyTextLocalizer.localize(summary);
  }
  return resolveAgentToolStatusLabel(cardData);
}

String resolveAgentToolStatusLabel(Map<String, dynamic> cardData) {
  final explicitStatusLabel = (cardData['statusLabel'] ?? '').toString().trim();
  if (explicitStatusLabel.isNotEmpty) {
    return LegacyTextLocalizer.localize(explicitStatusLabel);
  }
  final status = (cardData['status'] ?? 'running').toString();
  final toolType = (cardData['toolType'] ?? 'builtin').toString();
  if (status == 'timeout') {
    return LegacyTextLocalizer.localize('超时');
  }
  if (status == 'interrupted') {
    return LegacyTextLocalizer.localize('中断');
  }
  if (status == 'pending') {
    return LegacyTextLocalizer.localize('等待确认');
  }
  switch (status) {
    case 'success':
      return LegacyTextLocalizer.localize('成功');
    case 'error':
      return LegacyTextLocalizer.localize('失败');
    default:
      if (isAgentToolAwaitingConfirmation(cardData)) {
        return LegacyTextLocalizer.localize('等待确认');
      }
      if (toolType == 'terminal') return LegacyTextLocalizer.localize('运行中');
      if (toolType == 'browser') return LegacyTextLocalizer.localize('浏览中');
      if (toolType == 'search') return LegacyTextLocalizer.localize('搜索中');
      if (toolType == 'image') return LegacyTextLocalizer.localize('查看中');
      if (toolType == 'mcp') return LegacyTextLocalizer.localize('响应中');
      if (toolType == 'memory') return LegacyTextLocalizer.localize('处理中');
      if (toolType == 'review') return LegacyTextLocalizer.localize('审阅中');
      return LegacyTextLocalizer.localize('执行中');
  }
}

/// A permission request is represented by the standard ACP `pending` lifecycle
/// state. Legacy clarification cards remain recognized for restored history,
/// but new ACP permission requests do not need a local business state.
bool isAgentToolAwaitingConfirmation(Map<String, dynamic> cardData) {
  final toolType = (cardData['toolType'] ?? '').toString().trim().toLowerCase();
  final status = (cardData['status'] ?? '').toString().trim().toLowerCase();
  if (status == 'pending') return true;
  if (toolType != 'clarify' || status != 'running') return false;
  final question = (cardData['question'] ?? '').toString().trim();
  if (question.isEmpty) return false;
  final fields = <String>[
    if (cardData['missingFields'] is Iterable)
      ...(cardData['missingFields'] as Iterable).map(
        (value) => value.toString(),
      ),
    if (cardData['missing_fields'] is Iterable)
      ...(cardData['missing_fields'] as Iterable).map(
        (value) => value.toString(),
      ),
  ];
  return fields.any(
    (field) => field.trim().toLowerCase().endsWith('confirmed'),
  );
}

String resolveAgentToolTypeLabel(Map<String, dynamic> cardData) {
  final explicitTypeLabel = (cardData['toolTypeLabel'] ?? '').toString().trim();
  if (explicitTypeLabel.isNotEmpty) {
    return LegacyTextLocalizer.localize(explicitTypeLabel);
  }
  switch ((cardData['toolType'] ?? '').toString()) {
    case 'terminal':
      return LegacyTextLocalizer.localize('终端');
    case 'browser':
      return LegacyTextLocalizer.localize('浏览器');
    case 'search':
      return LegacyTextLocalizer.localize('搜索');
    case 'image':
      return LegacyTextLocalizer.localize('图像');
    case 'workspace':
      return LegacyTextLocalizer.localize('工作区');
    case 'file':
      return LegacyTextLocalizer.localize('文件');
    case 'plan':
      return LegacyTextLocalizer.localize('计划');
    case 'account':
      return LegacyTextLocalizer.localize('账户');
    case 'status':
      return LegacyTextLocalizer.localize('状态');
    case 'schedule':
      return LegacyTextLocalizer.localize('定时');
    case 'alarm':
      return LegacyTextLocalizer.localize('提醒');
    case 'calendar':
      return LegacyTextLocalizer.localize('日历');
    case 'memory':
      return LegacyTextLocalizer.localize('记忆');
    case 'skill':
      return 'Skill';
    case 'subagent':
      return LegacyTextLocalizer.localize('子任务');
    case 'review':
      return LegacyTextLocalizer.localize('审阅');
    case 'mcp':
      return 'MCP';
    default:
      return LegacyTextLocalizer.localize('工具');
  }
}

String buildAgentToolTranscript(
  List<Map<String, dynamic>> cards, {
  int maxTotalLines = 40,
  int maxTerminalLinesPerTool = 10,
}) {
  if (cards.isEmpty) {
    return '';
  }

  final transcriptLines = <String>[];
  for (final card in cards.reversed) {
    final title = resolveAgentToolTitle(card);
    transcriptLines.add('\$ $title');

    if ((card['toolType'] ?? '').toString() == 'terminal') {
      final output = resolveAgentToolTerminalOutput(card).trimRight();
      if (output.isNotEmpty) {
        final lines = output.split('\n');
        final start = lines.length > maxTerminalLinesPerTool
            ? lines.length - maxTerminalLinesPerTool
            : 0;
        transcriptLines.addAll(lines.sublist(start));
      } else {
        transcriptLines.add('> ${resolveAgentToolPreview(card)}');
      }
    } else {
      transcriptLines.add(
        '> ${resolveAgentToolTypeLabel(card)} · ${resolveAgentToolPreview(card)}',
      );
    }
    transcriptLines.add('');
  }

  if (transcriptLines.isEmpty) {
    return '';
  }

  var normalized = transcriptLines.join('\n').trimRight();
  if (maxTotalLines > 0) {
    final lines = normalized.split('\n');
    if (lines.length > maxTotalLines) {
      normalized = [
        LegacyTextLocalizer.localize('[更早记录已省略]'),
        ...lines.sublist(lines.length - maxTotalLines),
      ].join('\n');
    }
  }
  return normalized;
}

String _extractToolTitleFromArgs(String argsJson) {
  final text = argsJson.trim();
  if (text.isEmpty) {
    return '';
  }
  try {
    final decoded = jsonDecode(text);
    if (decoded is! Map) {
      return '';
    }
    final map = decoded.map((key, value) => MapEntry(key.toString(), value));
    final explicit = (map['tool_title'] ?? map['toolTitle'] ?? '')
        .toString()
        .trim();
    if (explicit.isNotEmpty) {
      return explicit;
    }
    for (final key in const <String>[
      'command',
      'cmd',
      'query',
      'q',
      'url',
      'path',
      'filePath',
      'file_path',
    ]) {
      final value = map[key]?.toString().trim() ?? '';
      if (value.isNotEmpty) {
        return _compactToolTitle(value);
      }
    }
  } catch (_) {
    return '';
  }
  return '';
}

String _compactToolTitle(String value) {
  final normalized = value
      .trim()
      .split('\n')
      .first
      .trim()
      .replaceAll(RegExp(r'\s+'), ' ');
  if (normalized.length <= 48) {
    return normalized;
  }
  return '${normalized.substring(0, 48)}...';
}

int _asNonNegativeInt(dynamic value) {
  final parsed = value is int
      ? value
      : value is num
      ? value.toInt()
      : int.tryParse(value?.toString() ?? '') ?? 0;
  return parsed < 0 ? 0 : parsed;
}
