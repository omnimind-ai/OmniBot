import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/pages/chat/tool_activity_utils.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_diff_viewer.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/terminal_output_utils.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/chat_detail_sheet_preferences.dart';
import 'package:ui/services/agent_diff_parser.dart';
import 'package:ui/services/agent_tool_call_parser.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/omni_glass.dart';

const Color _kTimeoutStatusColor = Color(0xFFFF8A3D);
const Color _kInterruptedStatusColor = Color(0xFFFFC04D);
const BorderRadius _kTranscriptSurfaceRadius = BorderRadius.all(
  Radius.circular(20),
);
const ValueKey<String> kAgentToolDetailSheetKey = ValueKey<String>(
  'agent-tool-detail-sheet',
);

class AgentToolTranscript {
  const AgentToolTranscript({
    required this.promptLine,
    required this.outputText,
    required this.previewText,
    required this.isTerminal,
  });

  final String promptLine;
  final String outputText;
  final String previewText;
  final bool isTerminal;
}

AgentToolTranscript buildAgentToolTranscript(
  Map<String, dynamic> cardData, {
  int? maxOutputLines,
  int maxPreviewLines = 2,
  int maxPreviewChars = 220,
}) {
  final toolType = (cardData['toolType'] ?? '').toString().trim();
  final isTerminal = toolType == 'terminal';
  final promptLine = isTerminal
      ? _buildTerminalPromptLine(cardData)
      : _buildToolPromptLine(cardData);
  final outputText = isTerminal
      ? _buildTerminalOutputText(cardData)
      : _buildStructuredOutputText(cardData, maxOutputLines: maxOutputLines);
  final previewText = _buildPreviewText(
    outputText,
    isTerminal: isTerminal,
    maxLines: maxPreviewLines,
    maxChars: maxPreviewChars,
  );

  return AgentToolTranscript(
    promptLine: promptLine,
    outputText: outputText,
    previewText: previewText,
    isTerminal: isTerminal,
  );
}

Future<void> showAgentToolDetailDialog(
  BuildContext context, {
  required Map<String, dynamic> cardData,
}) {
  return showDialog<void>(
    context: context,
    useRootNavigator: false,
    builder: (dialogContext) {
      return Dialog(
        elevation: 0,
        backgroundColor: Colors.transparent,
        insetPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 30),
        child: Container(
          constraints: BoxConstraints(
            maxHeight: MediaQuery.of(dialogContext).size.height * 0.76,
            maxWidth: 520,
          ),
          decoration: BoxDecoration(
            color: kTerminalSurfaceBlack,
            borderRadius: BorderRadius.circular(24),
            boxShadow: const [
              BoxShadow(
                color: kTerminalSurfaceShadow,
                blurRadius: 32,
                offset: Offset(0, 20),
              ),
            ],
          ),
          child: _AgentToolDetailContent(
            cardData: cardData,
            headerPadding: const EdgeInsets.fromLTRB(18, 14, 18, 10),
            scrollPadding: const EdgeInsets.fromLTRB(18, 0, 18, 16),
          ),
        ),
      );
    },
  );
}

Future<void> showAgentToolDetailSheet(
  BuildContext context, {
  required Map<String, dynamic> cardData,
}) {
  return showModalBottomSheet<void>(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    isDismissible: true,
    enableDrag: false,
    backgroundColor: Colors.transparent,
    barrierColor: Colors.black.withValues(alpha: 0.28),
    builder: (sheetContext) {
      return _AgentToolDetailSheetFrame(cardData: cardData);
    },
  );
}

Color resolveAgentToolStatusColor(String status) {
  switch (status) {
    case 'success':
      return const Color(0xFF2F8F4E);
    case 'error':
      return AppColors.alertRed;
    case 'timeout':
      return _kTimeoutStatusColor;
    case 'interrupted':
      return _kInterruptedStatusColor;
    default:
      return const Color(0xFF2C7FEB);
  }
}

IconData resolveAgentToolStatusIcon(String status, String toolType) {
  if (status == 'timeout') {
    return LucideIcons.hourglass;
  }
  if (status == 'interrupted') {
    return LucideIcons.stopCircle;
  }
  if (status == 'error') {
    return LucideIcons.triangleAlert;
  }
  if (toolType == 'terminal') {
    return LucideIcons.squareTerminal;
  }
  if (toolType == 'browser') {
    return LucideIcons.globe;
  }
  if (toolType == 'search') {
    return LucideIcons.search;
  }
  if (toolType == 'image') {
    return LucideIcons.image;
  }
  if (toolType == 'file') {
    return LucideIcons.filePenLine;
  }
  if (toolType == 'calendar') {
    return LucideIcons.calendarDays;
  }
  if (toolType == 'alarm' || toolType == 'schedule') {
    return LucideIcons.alarmClock;
  }
  if (toolType == 'memory') {
    return LucideIcons.brain;
  }
  if (toolType == 'workspace') {
    return LucideIcons.folder;
  }
  if (toolType == 'subagent') {
    return LucideIcons.network;
  }
  if (toolType == 'review') {
    return LucideIcons.messageSquare;
  }
  if (toolType == 'mcp') {
    return LucideIcons.puzzle;
  }
  return LucideIcons.circleCheck;
}

TextSpan _buildDetailTextSpan(AgentToolTranscript transcript) {
  final promptStyle = const TextStyle(
    color: Color(0xFFF4F7FB),
    fontSize: 12,
    fontFamily: 'monospace',
    fontWeight: FontWeight.w600,
    height: 1.45,
  );
  final outputStyle = const TextStyle(
    color: Color(0xFFB9F7C9),
    fontSize: 12,
    fontFamily: 'monospace',
    height: 1.45,
  );
  final children = <InlineSpan>[
    TextSpan(text: transcript.promptLine, style: promptStyle),
  ];
  if (transcript.outputText.trim().isNotEmpty) {
    children.add(const TextSpan(text: '\n'));
    children.add(AnsiTextSpanBuilder.build(transcript.outputText, outputStyle));
  }
  return TextSpan(children: children);
}

String _buildTerminalPromptLine(Map<String, dynamic> cardData) {
  final args = _decodeJsonMap((cardData['argsJson'] ?? '').toString());
  final toolName = (cardData['toolName'] ?? '').toString().trim();
  final workingDirectory = (args['workingDirectory'] ?? args['cwd'] ?? '')
      .toString()
      .trim();
  final command = (args['command'] ?? '').toString().trim();

  if (command.isNotEmpty) {
    if (workingDirectory.isEmpty) {
      return '\$ $command';
    }
    return '\$ cd ${_quoteShellValue(workingDirectory)} && $command';
  }

  if (toolName == 'terminal_session_start') {
    if (workingDirectory.isNotEmpty) {
      return '\$ cd ${_quoteShellValue(workingDirectory)}';
    }
    return '\$ sh';
  }
  if (toolName == 'terminal_session_stop') {
    return '\$ exit';
  }
  if (toolName == 'terminal_session_read') {
    final sessionId = (args['sessionId'] ?? cardData['terminalSessionId'] ?? '')
        .toString()
        .trim();
    return sessionId.isEmpty
        ? '\$ tail -f session.log'
        : '\$ tail -f $sessionId';
  }

  return _buildToolPromptLine(cardData);
}

String _buildToolPromptLine(Map<String, dynamic> cardData) {
  final toolName = (cardData['toolName'] ?? '').toString().trim().isEmpty
      ? (cardData['displayName'] ?? 'tool').toString().trim()
      : (cardData['toolName'] ?? '').toString().trim();
  final agentName = _resolveAgentToolPromptAgentName(cardData);
  if (_isInternalAgentToolName(toolName) && agentName.isNotEmpty) {
    final title = _resolveAgentToolPromptTitle(cardData, toolName);
    return '$agentName · $title';
  }
  final args = _decodeJsonMap((cardData['argsJson'] ?? '').toString());
  final segments = <String>[toolName];

  for (final entry in args.entries) {
    final key = entry.key.trim();
    if (key.isEmpty || key == 'tool_title' || key == 'toolTitle') {
      continue;
    }
    segments.addAll(_formatCliArguments(key, entry.value));
  }

  return '\$ ${segments.join(' ').trim()}';
}

String _resolveAgentToolPromptAgentName(Map<String, dynamic> cardData) {
  final explicit = (cardData['agentName'] ?? '').toString().trim();
  if (explicit.isNotEmpty) {
    return explicit;
  }
  // The native ACP event carries the resolved display name when available.
  // Keep an id fallback for custom/ newly-installed Harnesses instead of
  // maintaining a vendor allow-list in the card renderer.
  return (cardData['agentId'] ?? '').toString().trim();
}

String _resolveAgentToolPromptTitle(
  Map<String, dynamic> cardData,
  String toolName,
) {
  for (final value in <dynamic>[
    cardData['toolTitle'],
    cardData['displayName'],
  ]) {
    final title = (value ?? '').toString().trim();
    if (title.isNotEmpty &&
        title != toolName &&
        !_isInternalAgentToolName(title)) {
      return title;
    }
  }
  final separator = toolName.indexOf('.');
  final fallback = separator >= 0
      ? toolName.substring(separator + 1).trim()
      : toolName;
  return fallback.isEmpty ? 'tool' : fallback;
}

bool _isInternalAgentToolName(String value) {
  final normalized = canonicalAgentToolName(value) ?? value.trim();
  return normalized.startsWith('agent.') || normalized.startsWith('agent/');
}

String _buildTerminalOutputText(Map<String, dynamic> cardData) {
  final output = resolveAgentToolTerminalOutput(cardData).trimRight();
  if (output.isNotEmpty) {
    return output;
  }

  final status = (cardData['status'] ?? '').toString().trim();
  final summary = (cardData['summary'] ?? '').toString().trim();
  final progress = (cardData['progress'] ?? '').toString().trim();
  final fallback = progress.isNotEmpty ? progress : summary;
  if (status == 'running' && _isGenericTerminalProgressMessage(fallback)) {
    return '';
  }
  return fallback;
}

String _buildStructuredOutputText(
  Map<String, dynamic> cardData, {
  required int? maxOutputLines,
}) {
  final status = (cardData['status'] ?? '').toString().trim();
  final summary = (cardData['summary'] ?? '').toString().trim();
  final progress = (cardData['progress'] ?? '').toString().trim();
  final previewMap = _decodeJsonMap(
    (cardData['resultPreviewJson'] ?? '').toString(),
  );
  final previewResult = (cardData['resultPreviewJson'] ?? '').toString();
  final rawResult = (cardData['rawResultJson'] ?? '').toString();
  final rawMap = _decodeJsonMap(rawResult);
  final lines = <String>[];

  if (status == 'running') {
    _appendUniqueLine(lines, progress.isNotEmpty ? progress : summary);
  } else if (status == 'timeout' ||
      status == 'error' ||
      status == 'interrupted') {
    _appendUniqueLine(lines, summary);
  }

  // A compact preview may legitimately be a presentation-oriented subset, but
  // the detail sheet and copy action must expose the complete persisted result.
  final detailMap = maxOutputLines == null && rawMap.isNotEmpty
      ? rawMap
      : previewMap;
  final structuredResult = _buildStructuredLines(
    detailMap,
    maxLines: maxOutputLines,
  );
  if (structuredResult.isNotEmpty) {
    lines.addAll(structuredResult.where((line) => !lines.contains(line)));
  } else {
    final structuredRaw = _buildStructuredLines(
      rawMap,
      maxLines: maxOutputLines,
    );
    lines.addAll(structuredRaw.where((line) => !lines.contains(line)));
  }

  // A valid tool result can be a JSON array or scalar rather than an object.
  // The detail/copy path must still show the complete canonical payload.
  if (lines.isEmpty) {
    final detailResult = maxOutputLines == null && rawResult.trim().isNotEmpty
        ? rawResult
        : previewResult;
    _appendUniqueLine(lines, _formatCompleteResultPayload(detailResult));
  }

  if (lines.isEmpty) {
    _appendUniqueLine(lines, progress);
    _appendUniqueLine(lines, summary);
    if (lines.isEmpty) {
      lines.add(resolveAgentToolStatusLabel(cardData));
    }
  }

  final normalized = lines.join('\n').trim();
  return _trimStructuredOutput(normalized, maxLines: maxOutputLines);
}

String _formatCompleteResultPayload(String value) {
  final normalized = value.trimRight();
  if (normalized.isEmpty) {
    return '';
  }
  try {
    return const JsonEncoder.withIndent('  ').convert(jsonDecode(normalized));
  } catch (_) {
    return normalized;
  }
}

String _buildPreviewText(
  String outputText, {
  required bool isTerminal,
  required int maxLines,
  required int maxChars,
}) {
  final lines = outputText
      .split('\n')
      .map((line) => line.trimRight())
      .where((line) => line.trim().isNotEmpty)
      .toList(growable: false);
  if (lines.isEmpty) {
    return '';
  }
  final selected = isTerminal
      ? lines.sublist(math.max(0, lines.length - maxLines))
      : lines.take(maxLines).toList(growable: false);
  final preview = selected.join('\n');
  if (preview.length <= maxChars) {
    return preview;
  }
  return '${preview.substring(0, maxChars - 1).trimRight()}…';
}

List<String> _formatCliArguments(String key, dynamic value) {
  final flag = '--$key';
  if (value == null) {
    return const <String>[];
  }
  if (value is bool) {
    return value ? <String>[flag] : <String>['$flag=false'];
  }
  if (value is num) {
    return <String>[flag, value.toString()];
  }
  if (value is String) {
    final trimmed = value.trim();
    if (trimmed.isEmpty) {
      return const <String>[];
    }
    return <String>[flag, _quoteShellValue(trimmed)];
  }
  if (value is List) {
    final segments = <String>[];
    for (final item in value) {
      if (item == null) {
        continue;
      }
      if (item is Map || item is List) {
        segments.addAll(<String>[flag, _quoteShellValue(jsonEncode(item))]);
        continue;
      }
      final itemText = item.toString().trim();
      if (itemText.isEmpty) {
        continue;
      }
      segments.addAll(<String>[flag, _quoteShellValue(itemText)]);
    }
    return segments;
  }
  return <String>[flag, _quoteShellValue(jsonEncode(value))];
}

List<String> _buildStructuredLines(
  Map<String, dynamic> source, {
  required int? maxLines,
}) {
  if (source.isEmpty) {
    return const <String>[];
  }

  final lines = <String>[];
  final compactValues = maxLines != null;

  bool canAdd() => maxLines == null || lines.length < maxLines;

  void addLine(String line) {
    final normalized = line.trimRight();
    if (normalized.isEmpty || lines.contains(normalized) || !canAdd()) {
      return;
    }
    lines.add(normalized);
  }

  void appendValue(String label, dynamic value, int depth) {
    if (!canAdd() || value == null) {
      return;
    }

    if (value is Map) {
      final normalizedMap = value.map(
        (key, nested) => MapEntry(key.toString(), nested),
      );
      final summary = compactValues ? _summarizeMap(normalizedMap) : null;
      if (summary != null && summary.isNotEmpty) {
        addLine(label.isEmpty ? summary : '$label: $summary');
        return;
      }
      for (final entry in _prioritizeEntries(normalizedMap.entries)) {
        final key = entry.key.trim();
        if (_shouldSkipStructuredKey(key)) {
          continue;
        }
        final nextLabel = label.isEmpty ? key : '$label.$key';
        appendValue(nextLabel, entry.value, depth + 1);
        if (!canAdd()) {
          return;
        }
      }
      return;
    }

    if (value is List) {
      if (value.isEmpty) {
        return;
      }
      if (_canInlineScalarList(value) || !compactValues) {
        addLine(
          '$label: ${value.map((item) => _formatInlineValue(item, compact: compactValues)).join(', ')}',
        );
        return;
      }
      final itemLimit = math.min(value.length, depth <= 1 ? 5 : 3);
      for (var index = 0; index < itemLimit; index++) {
        final item = value[index];
        if (item is Map) {
          final normalizedMap = item.map(
            (key, nested) => MapEntry(key.toString(), nested),
          );
          final summary = compactValues ? _summarizeMap(normalizedMap) : null;
          if (summary != null && summary.isNotEmpty) {
            addLine('$label[$index]: $summary');
          } else {
            appendValue('$label[$index]', normalizedMap, depth + 1);
          }
        } else {
          final formatted = _formatScalarLine(item, compact: compactValues);
          if (formatted != null) {
            addLine('$label[$index]: $formatted');
          }
        }
        if (!canAdd()) {
          return;
        }
      }
      if (value.length > itemLimit && canAdd()) {
        addLine('$label: ... +${value.length - itemLimit} more');
      }
      return;
    }

    final formatted = _formatScalarLine(value, compact: compactValues);
    if (formatted != null) {
      addLine(label.isEmpty ? formatted : '$label: $formatted');
    }
  }

  for (final entry in _prioritizeEntries(source.entries)) {
    final key = entry.key.trim();
    if (_shouldSkipStructuredKey(key)) {
      continue;
    }
    appendValue(key, entry.value, 0);
    if (!canAdd()) {
      break;
    }
  }

  return lines;
}

List<MapEntry<String, dynamic>> _prioritizeEntries(
  Iterable<MapEntry<String, dynamic>> entries,
) {
  const priority = <String, int>{
    'message': 0,
    'question': 1,
    'errorMessage': 2,
    'path': 3,
    'targetPath': 4,
    'query': 5,
    'url': 6,
    'currentUrl': 7,
    'count': 8,
    'name': 9,
    'title': 10,
    'taskId': 11,
    'goal': 12,
    'content': 13,
    'snippet': 14,
    'items': 15,
  };
  final sorted = entries.toList(growable: false);
  sorted.sort((left, right) {
    final leftRank = priority[left.key] ?? 99;
    final rightRank = priority[right.key] ?? 99;
    if (leftRank != rightRank) {
      return leftRank.compareTo(rightRank);
    }
    return left.key.compareTo(right.key);
  });
  return sorted;
}

String? _summarizeMap(Map<String, dynamic> value) {
  final parts = <String>[];
  final path = _firstNonBlank(value, const [
    'path',
    'targetPath',
    'sourcePath',
  ]);
  final name = _firstNonBlank(value, const ['name', 'title', 'label', 'id']);
  final url = _firstNonBlank(value, const ['currentUrl', 'url']);
  final matchType = (value['matchType'] ?? '').toString().trim();
  final snippet = _firstNonBlank(value, const [
    'snippet',
    'content',
    'message',
  ]);

  if (name.isNotEmpty) {
    parts.add(name);
  }
  if (path.isNotEmpty && !parts.contains(path)) {
    parts.add(path);
  }
  if (url.isNotEmpty && !parts.contains(url)) {
    parts.add(url);
  }
  if (matchType.isNotEmpty) {
    parts.add(matchType);
  }
  if (value['isDirectory'] == true && !parts.contains('dir')) {
    parts.add('dir');
  }
  final sizeValue = value['size'];
  if (sizeValue is num && sizeValue > 0) {
    parts.add(_formatBytes(sizeValue.toInt()));
  }
  if (snippet.isNotEmpty) {
    parts.add(_truncateInline(snippet));
  }

  if (parts.isEmpty) {
    return null;
  }
  return parts.join(' | ');
}

String _trimStructuredOutput(
  String value, {
  required int? maxLines,
  int? maxChars,
}) {
  if (value.isEmpty) {
    return value;
  }
  var candidate = value;
  if (maxChars != null && candidate.length > maxChars) {
    candidate = candidate.substring(0, maxChars).trimRight();
    candidate = '$candidate\n...[truncated]';
  }
  final lines = candidate.split('\n');
  if (maxLines != null && lines.length > maxLines) {
    candidate = [...lines.take(maxLines), '...[truncated]'].join('\n');
  }
  return candidate.trimRight();
}

bool _canInlineScalarList(List<dynamic> value) {
  if (value.isEmpty || value.any((item) => item is Map || item is List)) {
    return false;
  }
  final rendered = value.map(_formatInlineValue).join(', ');
  return rendered.length <= 120;
}

String _formatInlineValue(dynamic value, {bool compact = true}) {
  if (value == null) {
    return 'null';
  }
  return compact ? _truncateInline(value.toString()) : value.toString();
}

String? _formatScalarLine(dynamic value, {bool compact = true}) {
  if (value == null) {
    return null;
  }
  if (value is bool || value is num) {
    return value.toString();
  }
  final normalized = value.toString().trim();
  if (normalized.isEmpty) {
    return null;
  }
  return compact ? _truncateInline(normalized) : normalized;
}

String _truncateInline(String value, {int maxLength = 140}) {
  final collapsed = value.replaceAll(RegExp(r'\s+'), ' ').trim();
  if (collapsed.length <= maxLength) {
    return collapsed;
  }
  return '${collapsed.substring(0, maxLength - 1).trimRight()}…';
}

bool _isGenericTerminalProgressMessage(String value) {
  final normalized = value.trim();
  if (normalized.isEmpty) {
    return true;
  }
  final distributionProgress = RegExp(
    r'^(正在调用内嵌 (Alpine|Ubuntu) 环境执行命令|'
    r'正在执行内嵌 (Alpine|Ubuntu) 环境命令|'
    r'(Alpine|Ubuntu) 输出更新中|'
    r'Running a command in the embedded (Alpine|Ubuntu) environment|'
    r'Executing a command in the embedded (Alpine|Ubuntu) environment|'
    r'(Alpine|Ubuntu) output is updating|'
    r'Updating (Alpine|Ubuntu) output)$',
  );
  return distributionProgress.hasMatch(normalized) ||
      normalized == '正在调用内嵌 Alpine 终端执行命令' ||
      normalized == '正在执行内嵌 Alpine 终端命令' ||
      normalized == '正在调用内嵌终端环境执行命令' ||
      normalized == '正在执行内嵌终端环境命令' ||
      normalized == '终端输出更新中' ||
      normalized == 'Running a command in the embedded Alpine terminal' ||
      normalized == 'Executing a command in the embedded Alpine terminal' ||
      normalized == 'Running a command in the embedded terminal environment' ||
      normalized ==
          'Executing a command in the embedded terminal environment' ||
      normalized == 'Updating terminal output';
}

String _firstNonBlank(Map<String, dynamic> value, List<String> keys) {
  for (final key in keys) {
    final candidate = (value[key] ?? '').toString().trim();
    if (candidate.isNotEmpty) {
      return candidate;
    }
  }
  return '';
}

bool _shouldSkipStructuredKey(String key) {
  final normalized = key.trim();
  if (normalized.isEmpty) {
    return true;
  }
  const exactNoise = <String>{
    'success',
    'summary',
    'toolTitle',
    'tool_title',
    'terminalOutput',
    'terminalOutputLength',
    'stdout',
    'stdoutLength',
    'stderr',
    'stderrLength',
    'rawExtras',
    'artifacts',
    'actions',
    'uri',
    'logUri',
    'androidPath',
    'androidRootPath',
    'androidSkillFilePath',
    'androidSourcePath',
    'androidTargetPath',
    'androidLogPath',
    'liveSessionId',
    'liveStreamState',
    'liveFallbackReason',
    'timedOut',
  };
  if (exactNoise.contains(normalized)) {
    return true;
  }
  final lower = normalized.toLowerCase();
  return lower.contains('html') ||
      lower.contains('trace') ||
      lower.contains('bodymarkdown') ||
      lower.contains('raw');
}

Map<String, dynamic> _decodeJsonMap(String raw) {
  final trimmed = raw.trim();
  if (trimmed.isEmpty) {
    return const <String, dynamic>{};
  }
  try {
    final decoded = jsonDecode(trimmed);
    if (decoded is Map) {
      return decoded.map((key, value) => MapEntry(key.toString(), value));
    }
  } catch (_) {
    return const <String, dynamic>{};
  }
  return const <String, dynamic>{};
}

String _quoteShellValue(String value) {
  const safePattern = r'^[A-Za-z0-9_./:@%+=,-]+$';
  if (RegExp(safePattern).hasMatch(value)) {
    return value;
  }
  return "'${value.replaceAll("'", "'\"'\"'")}'";
}

void _appendUniqueLine(List<String> lines, String value) {
  final normalized = value.trim();
  if (normalized.isEmpty || lines.contains(normalized)) {
    return;
  }
  lines.add(normalized);
}

String _formatBytes(int value) {
  if (value >= 1024 * 1024) {
    return '${(value / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
  if (value >= 1024) {
    return '${(value / 1024).toStringAsFixed(1)} KB';
  }
  return '$value B';
}

class _TerminalTrafficLights extends StatelessWidget {
  const _TerminalTrafficLights();

  @override
  Widget build(BuildContext context) {
    Widget dot(Color color) {
      return Container(
        width: 8,
        height: 8,
        decoration: BoxDecoration(color: color, shape: BoxShape.circle),
      );
    }

    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        dot(const Color(0xFFFF5F57)),
        const SizedBox(width: 5),
        dot(const Color(0xFFFEBB2E)),
        const SizedBox(width: 5),
        dot(const Color(0xFF28C840)),
      ],
    );
  }
}

class _AgentToolDetailSheetFrame extends StatefulWidget {
  const _AgentToolDetailSheetFrame({required this.cardData});

  final Map<String, dynamic> cardData;

  @override
  State<_AgentToolDetailSheetFrame> createState() =>
      _AgentToolDetailSheetFrameState();
}

class _AgentToolDetailSheetFrameState
    extends State<_AgentToolDetailSheetFrame> {
  static const double _minHeightFactor = 0.36;
  static const double _maxHeightFactor = 0.94;

  double? _heightFactor;

  double _initialHeightFactor(double viewportHeight) {
    return viewportHeight < 720 ? 0.72 : 0.62;
  }

  void _handleDragUpdate(DragUpdateDetails details, double availableHeight) {
    if (availableHeight <= 0) {
      return;
    }
    final delta = details.primaryDelta ?? details.delta.dy;
    setState(() {
      final current =
          _heightFactor ??
          ChatDetailSheetPreferences.resolveHeightFactor(
            fallback: _initialHeightFactor(MediaQuery.sizeOf(context).height),
            min: _minHeightFactor,
            max: _maxHeightFactor,
          );
      _heightFactor = (current - delta / availableHeight).clamp(
        _minHeightFactor,
        _maxHeightFactor,
      );
    });
  }

  void _persistHeightFactor() {
    final heightFactor = _heightFactor;
    if (heightFactor == null) {
      return;
    }
    try {
      unawaited(
        ChatDetailSheetPreferences.saveHeightFactor(
          heightFactor,
          min: _minHeightFactor,
          max: _maxHeightFactor,
        ).catchError((_) {}),
      );
    } catch (_) {
      // Storage may not be initialized in tests or cold-start edge cases.
    }
  }

  @override
  Widget build(BuildContext context) {
    final mediaQuery = MediaQuery.of(context);
    final availableHeight = math.max(
      320.0,
      mediaQuery.size.height -
          mediaQuery.padding.top -
          mediaQuery.viewInsets.bottom,
    );
    final heightFactor =
        _heightFactor ?? _resolveStoredHeightFactor(mediaQuery.size.height);
    const borderRadius = BorderRadius.vertical(top: Radius.circular(24));

    return SafeArea(
      top: false,
      child: AnimatedPadding(
        duration: const Duration(milliseconds: 180),
        curve: Curves.easeOutCubic,
        padding: EdgeInsets.only(bottom: mediaQuery.viewInsets.bottom),
        child: OmniGlassPanel(
          key: kAgentToolDetailSheetKey,
          height: availableHeight * heightFactor,
          width: double.infinity,
          borderRadius: borderRadius,
          forceDark: true,
          child: Material(
            color: Colors.transparent,
            child: Column(
              children: [
                GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onVerticalDragUpdate: (details) =>
                      _handleDragUpdate(details, availableHeight),
                  onVerticalDragEnd: (_) => _persistHeightFactor(),
                  child: SizedBox(
                    height: 22,
                    width: double.infinity,
                    child: Center(
                      child: Container(
                        width: 42,
                        height: 4,
                        decoration: BoxDecoration(
                          color: Colors.white.withValues(alpha: 0.34),
                          borderRadius: BorderRadius.circular(999),
                        ),
                      ),
                    ),
                  ),
                ),
                Expanded(
                  child: _AgentToolDetailContent(
                    cardData: widget.cardData,
                    headerPadding: const EdgeInsets.fromLTRB(18, 0, 18, 10),
                    scrollPadding: const EdgeInsets.fromLTRB(18, 0, 18, 16),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  double _resolveStoredHeightFactor(double viewportHeight) {
    try {
      return ChatDetailSheetPreferences.resolveHeightFactor(
        fallback: _initialHeightFactor(viewportHeight),
        min: _minHeightFactor,
        max: _maxHeightFactor,
      );
    } catch (_) {
      return _initialHeightFactor(viewportHeight);
    }
  }
}

class _AgentToolDetailContent extends StatelessWidget {
  const _AgentToolDetailContent({
    required this.cardData,
    required this.headerPadding,
    required this.scrollPadding,
  });

  final Map<String, dynamic> cardData;
  final EdgeInsetsGeometry headerPadding;
  final EdgeInsetsGeometry scrollPadding;

  @override
  Widget build(BuildContext context) {
    final transcript = buildAgentToolTranscript(
      cardData,
      maxPreviewLines: 4,
      maxPreviewChars: 420,
    );
    final title = resolveAgentToolTitle(cardData);
    final typeLabel = resolveAgentToolTypeLabel(cardData);
    final status = (cardData['status'] ?? 'running').toString();
    final statusLabel = resolveAgentToolStatusLabel(cardData);
    final diffSummary = _resolveDiffSummary(cardData);
    final isDiffView = diffSummary?.files.isNotEmpty == true;
    final detailSpan = isDiffView ? null : _buildDetailTextSpan(transcript);
    final actions = _resolveAgentToolActions(cardData);
    final copyText = _agentToolCopyText(
      cardData,
      transcript,
      isDiffView: isDiffView,
    );

    return Column(
      children: [
        Padding(
          padding: headerPadding,
          child: Row(
            children: [
              const _TerminalTrafficLights(),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: Color(0xFFF2F7FF),
                    fontSize: 12.5,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
              const SizedBox(width: 10),
              _DialogMetaTag(label: typeLabel),
              const SizedBox(width: 6),
              _DialogStatusTag(status: status, label: statusLabel),
              const SizedBox(width: 2),
              IconButton(
                visualDensity: VisualDensity.compact,
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints.tightFor(
                  width: 28,
                  height: 28,
                ),
                splashRadius: 14,
                tooltip: LegacyTextLocalizer.isEnglish
                    ? 'Copy details'
                    : '复制详情',
                onPressed: copyText.isEmpty
                    ? null
                    : () async {
                        final copied =
                            await AssistsMessageService.copyToClipboard(
                              copyText,
                            );
                        showToast(
                          copied
                              ? (LegacyTextLocalizer.isEnglish
                                    ? 'Copied'
                                    : '已复制')
                              : (LegacyTextLocalizer.isEnglish
                                    ? 'Copy failed'
                                    : '复制失败'),
                          type: copied ? ToastType.success : ToastType.error,
                        );
                      },
                icon: const Icon(LucideIcons.copy, size: 15),
              ),
            ],
          ),
        ),
        Expanded(
          child: isDiffView
              ? AgentDiffViewer(summary: diffSummary!, padding: scrollPadding)
              : SingleChildScrollView(
                  padding: scrollPadding,
                  child: SelectableText.rich(detailSpan!),
                ),
        ),
        if (actions.isNotEmpty)
          Padding(
            padding: const EdgeInsets.fromLTRB(18, 0, 18, 14),
            child: _AgentToolActionBar(actions: actions),
          ),
      ],
    );
  }
}

String _agentToolCopyText(
  Map<String, dynamic> cardData,
  AgentToolTranscript transcript, {
  required bool isDiffView,
}) {
  final body = isDiffView
      ? (cardData['diffText'] ?? '').toString()
      : transcript.outputText;
  final prompt = transcript.promptLine.trimRight();
  final output = body.trimRight();
  if (prompt.isEmpty) return output;
  if (output.isEmpty) return prompt;
  return '$prompt\n$output';
}

List<Map<String, dynamic>> _resolveAgentToolActions(
  Map<String, dynamic> cardData,
) {
  final actions = <Map<String, dynamic>>[];
  final rawActions = cardData['actions'];
  if (rawActions is List) {
    actions.addAll(
      rawActions.whereType<Map>().map(
        (item) => item.map((key, value) => MapEntry(key.toString(), value)),
      ),
    );
  }
  final workspaceId = (cardData['workspaceId'] ?? '').toString().trim();
  final hasWorkspaceAction = actions.any(
    (action) => (action['type'] ?? '').toString().trim() == 'workspace',
  );
  if (workspaceId.isNotEmpty && !hasWorkspaceAction) {
    actions.add(<String, dynamic>{
      'type': 'workspace',
      'label': LegacyTextLocalizer.isEnglish ? 'Open workspace' : '打开工作区',
      'payload': <String, dynamic>{'workspaceId': workspaceId},
    });
  }
  final toolType = (cardData['toolType'] ?? '').toString().trim();
  if (cardData['showScheduleAction'] == true || toolType == 'schedule') {
    actions.add(<String, dynamic>{
      'type': 'route',
      'label': LegacyTextLocalizer.isEnglish
          ? 'View scheduled tasks'
          : '查看定时任务',
      'target': '/task/scheduled_tasks',
    });
  }
  if (cardData['showAlarmAction'] == true || toolType == 'alarm') {
    actions.add(<String, dynamic>{
      'type': 'route',
      'label': LegacyTextLocalizer.isEnglish ? 'View alarms' : '查看闹钟列表',
      'target': '/task/scheduled_tasks?tab=alarm',
    });
  }
  return actions
      .where((action) => (action['label'] ?? '').toString().trim().isNotEmpty)
      .toList(growable: false);
}

class _AgentToolActionBar extends StatelessWidget {
  const _AgentToolActionBar({required this.actions});

  final List<Map<String, dynamic>> actions;

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: Alignment.centerLeft,
      child: Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          for (var index = 0; index < actions.length; index++)
            OutlinedButton(
              key: ValueKey('agent-tool-action-$index'),
              onPressed: () => unawaited(_runAgentToolAction(actions[index])),
              style: OutlinedButton.styleFrom(
                foregroundColor: const Color(0xFFDDE8F7),
                side: const BorderSide(color: Color(0xFF34445E)),
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 8,
                ),
                minimumSize: const Size(0, 34),
                tapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
              child: Text(
                actions[index]['label'].toString(),
                style: const TextStyle(fontSize: 11.5),
              ),
            ),
        ],
      ),
    );
  }
}

Future<void> _runAgentToolAction(Map<String, dynamic> action) async {
  final type = (action['type'] ?? '').toString().trim().toLowerCase();
  final target = (action['target'] ?? '').toString().trim();
  final rawPayload = action['payload'];
  final payload = rawPayload is Map
      ? rawPayload.map((key, value) => MapEntry(key.toString(), value))
      : const <String, dynamic>{};
  final path = (payload['path'] ?? payload['workspacePath'] ?? '')
      .toString()
      .trim();
  final shellPath =
      (payload['shellPath'] ?? payload['workspaceShellPath'] ?? '')
          .toString()
          .trim();

  if (type == 'route' && target.isNotEmpty) {
    GoRouterManager.push(target);
    return;
  }
  if (type == 'workspace') {
    await OmnibotResourceService.openWorkspace(
      workspaceId: payload['workspaceId']?.toString(),
      absolutePath: path.isEmpty ? null : path,
      shellPath: shellPath.isEmpty ? null : shellPath,
      uri: target.isEmpty ? null : target,
    );
    return;
  }
  if (type == 'save' && path.isNotEmpty) {
    await OmnibotResourceService.saveToLocal(
      sourcePath: path,
      fileName: (payload['fileName'] ?? payload['title'] ?? 'artifact')
          .toString(),
      mimeType: (payload['mimeType'] ?? 'application/octet-stream').toString(),
    );
    return;
  }
  if (path.isNotEmpty && (type == 'preview' || type == 'open')) {
    await OmnibotResourceService.openFilePath(
      path,
      uri: target.isEmpty ? null : target,
      title: payload['title']?.toString(),
      previewKind: payload['previewKind']?.toString(),
      mimeType: payload['mimeType']?.toString(),
      shellPath: shellPath.isEmpty ? null : shellPath,
    );
    return;
  }
  if (target.isNotEmpty) {
    if (!await OmnibotResourceService.handleLinkTap(target)) {
      await OmnibotResourceService.openUri(target);
    }
  }
}

AgentDiffSummary? _resolveDiffSummary(Map<String, dynamic> cardData) {
  final diffText = (cardData['diffText'] ?? '').toString();
  final extracted = extractAgentDiffText(
    <String, dynamic>{
      ...cardData,
      if (diffText.isNotEmpty) 'diffText': diffText,
    },
    outputText: diffText.isNotEmpty
        ? diffText
        : resolveAgentToolTerminalOutput(cardData),
    progress: (cardData['progress'] ?? '').toString(),
    summary: (cardData['summary'] ?? '').toString(),
  );
  if (extracted == null || extracted.trim().isEmpty) {
    return null;
  }
  final summary = parseAgentDiffText(extracted);
  return summary.files.isEmpty ? null : summary;
}

class _DialogMetaTag extends StatelessWidget {
  const _DialogMetaTag({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
      decoration: BoxDecoration(
        color: const Color(0xFF152133),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: const Color(0xFF273752)),
      ),
      child: Text(
        label,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(
          color: Color(0xFF9FB1C8),
          fontSize: 9.2,
          fontWeight: FontWeight.w700,
          height: 1,
        ),
      ),
    );
  }
}

class _DialogStatusTag extends StatelessWidget {
  const _DialogStatusTag({required this.status, required this.label});

  final String status;
  final String label;

  @override
  Widget build(BuildContext context) {
    final color = resolveAgentToolStatusColor(status);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: color.withValues(alpha: 0.32)),
      ),
      child: Text(
        label,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          color: color.withValues(alpha: 0.96),
          fontSize: 9.2,
          fontWeight: FontWeight.w700,
          height: 1,
        ),
      ),
    );
  }
}

BoxDecoration buildAgentToolTranscriptDecoration() {
  return BoxDecoration(
    color: kTerminalSurfaceBlackElevated,
    borderRadius: _kTranscriptSurfaceRadius,
    boxShadow: const [
      BoxShadow(
        color: kTerminalSurfaceShadow,
        blurRadius: 18,
        offset: Offset(0, 8),
      ),
    ],
  );
}
