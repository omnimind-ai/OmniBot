import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/features/home/pages/chat/tool_activity_utils.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_diff_viewer.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/app_background_service.dart';
import 'package:ui/services/agent_diff_parser.dart';
import 'package:ui/services/agent_tool_call_parser.dart';
import 'package:ui/services/agent_message_kinds.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/image_preview_overlay.dart';
import 'acp_audio_card.dart';

class AgentToolSummaryCard extends StatefulWidget {
  const AgentToolSummaryCard({
    super.key,
    required this.cardData,
    this.useAgentToolPresentation = true,
    this.parentScrollController,
    this.visualProfile = AppBackgroundVisualProfile.defaultProfile,
  });

  final Map<String, dynamic> cardData;
  final bool useAgentToolPresentation;
  final ScrollController? parentScrollController;
  final AppBackgroundVisualProfile visualProfile;

  @override
  State<AgentToolSummaryCard> createState() => _AgentToolSummaryCardState();
}

class _AgentToolSummaryCardState extends State<AgentToolSummaryCard> {
  final Set<String> _expandedSubagentKeys = <String>{};

  @override
  Widget build(BuildContext context) {
    final cardData = widget.cardData;
    if (cardData['audioDataUrl'] != null || cardData['audioUrl'] != null) {
      return AcpAudioCard(cardData: cardData);
    }
    if (_isCompletedVlmTask(cardData)) {
      return _VlmTaskResultCard(cardData: cardData);
    }
    if (_usesInlineToolStyle(
      cardData,
      useAgentToolPresentation: widget.useAgentToolPresentation,
    )) {
      return _AgentToolCardWithImagePreview(
        cardData: cardData,
        child: _InlineToolCallCard(
          cardData: cardData,
          visualProfile: widget.visualProfile,
        ),
      );
    }

    final status = (cardData['status'] ?? 'running').toString();
    final title = resolveAgentToolTitle(cardData);
    final statusLabel = resolveAgentToolStatusLabel(cardData);
    final preview = resolveAgentToolPreview(cardData);
    final typeLabel = resolveAgentToolTypeLabel(cardData);
    final isSubagent = _isSubagentTool(cardData);
    final isPlan = (cardData['toolType'] ?? '').toString().trim() == 'plan';
    final subagentEvents = isSubagent
        ? _resolveSubagentTimelineEvents(cardData)
        : const <_SubagentTimelineEvent>[];
    final subagentGroups = isSubagent
        ? _resolveSubagentStatusGroups(cardData, subagentEvents)
        : const <_SubagentStatusGroup>[];
    final statusColor = resolveAgentToolStatusColor(status);
    final palette = context.omniPalette;
    final cardBackgroundColor = context.isDarkTheme
        ? Color.alphaBlend(
            statusColor.withValues(
              alpha: status == 'running' || status == 'pending' ? 0.07 : 0.045,
            ),
            palette.surfacePrimary,
          )
        : Color.alphaBlend(
            statusColor.withValues(
              alpha: status == 'running' || status == 'pending' ? 0.055 : 0.035,
            ),
            palette.surfacePrimary,
          );
    // Keep the capsule presentation, but give it one calm surface and a
    // restrained semantic border so the type/status pills remain cohesive.
    final cardBorder = Border.all(
      color: statusColor.withValues(alpha: context.isDarkTheme ? 0.22 : 0.16),
      width: 1,
    );
    final statusTagBackgroundColor = context.isDarkTheme
        ? statusColor.withValues(alpha: 0.13)
        : statusColor.withValues(alpha: 0.09);
    final statusTagTextColor = context.isDarkTheme
        ? Color.lerp(palette.textSecondary, statusColor, 0.38)!
        : statusColor;
    final titleColor = context.isDarkTheme
        ? palette.textPrimary
        : widget.visualProfile.primaryTextColor;
    final titleStyle = TextStyle(
      color: titleColor,
      fontSize: 12.5,
      fontWeight: FontWeight.w600,
      letterSpacing: 0,
      height: 1.15,
    );

    final tooltipLines = <String>[title];
    if (preview.isNotEmpty && preview != title) {
      tooltipLines.add(preview);
    }
    for (final group in subagentGroups) {
      tooltipLines.add(group.statusText);
    }

    final capsule = _buildCapsule(
      context: context,
      cardData: cardData,
      status: status,
      title: title,
      titleStyle: titleStyle,
      typeLabel: typeLabel,
      statusLabel: statusLabel,
      cardBackgroundColor: cardBackgroundColor,
      cardBorder: cardBorder,
      statusTagBackgroundColor: statusTagBackgroundColor,
      statusTagTextColor: statusTagTextColor,
    );

    return _AgentToolCardWithImagePreview(
      cardData: cardData,
      child: Tooltip(
        message: tooltipLines.join('\n'),
        child: Align(
          alignment: Alignment.centerLeft,
          child: ConstrainedBox(
            constraints: BoxConstraints(
              maxWidth: MediaQuery.of(context).size.width * 0.78,
              minHeight: 36,
            ),
            child: Container(
              margin: const EdgeInsets.only(top: 5, bottom: 3),
              child: isSubagent
                  ? Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        capsule,
                        for (final group in subagentGroups)
                          _SubagentStatusBlock(
                            group: group,
                            shimmer:
                                status == 'running' && !group.isTerminalStatus,
                            expanded: _expandedSubagentKeys.contains(group.key),
                            onTap: () => _toggleSubagentGroup(group.key),
                          ),
                      ],
                    )
                  : isPlan
                  ? Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        capsule,
                        _PlanEntriesBlock(cardData: cardData),
                      ],
                    )
                  : capsule,
            ),
          ),
        ),
      ),
    );
  }

  void _toggleSubagentGroup(String key) {
    setState(() {
      if (!_expandedSubagentKeys.add(key)) {
        _expandedSubagentKeys.remove(key);
      }
    });
  }

  Widget _buildCapsule({
    required BuildContext context,
    required Map<String, dynamic> cardData,
    required String status,
    required String title,
    required TextStyle titleStyle,
    required String typeLabel,
    required String statusLabel,
    required Color cardBackgroundColor,
    required Border? cardBorder,
    required Color statusTagBackgroundColor,
    required Color statusTagTextColor,
  }) {
    final diffStatLabel = _resolveDiffStatLabel(cardData);
    return Material(
      key: ValueKey(
        'agent-tool-summary-capsule-${cardData['cardId'] ?? title}',
      ),
      color: Colors.transparent,
      borderRadius: BorderRadius.circular(999),
      clipBehavior: Clip.antiAlias,
      child: Ink(
        decoration: BoxDecoration(
          color: cardBackgroundColor,
          borderRadius: BorderRadius.circular(999),
          border: cardBorder,
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(
                alpha: context.isDarkTheme ? 0.16 : 0.055,
              ),
              blurRadius: 8,
              offset: const Offset(0, 3),
            ),
          ],
        ),
        child: InkWell(
          onTap: () =>
              unawaited(showAgentToolDetailSheet(context, cardData: cardData)),
          borderRadius: BorderRadius.circular(999),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(10, 6, 9, 6),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                _StatusIcon(status: status, toolType: cardData['toolType']),
                const SizedBox(width: 7),
                Flexible(
                  child:
                      status == 'running' &&
                          !isAgentToolAwaitingConfirmation(cardData)
                      ? _FlowingToolTitleText(text: title, style: titleStyle)
                      : Text(
                          title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: titleStyle,
                        ),
                ),
                if (diffStatLabel != null) ...[
                  const SizedBox(width: 8),
                  _DiffStatBadge(label: diffStatLabel),
                ],
                const SizedBox(width: 7),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 7,
                    vertical: 3,
                  ),
                  decoration: BoxDecoration(
                    color: statusTagBackgroundColor,
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text(
                    status == 'running' &&
                            !isAgentToolAwaitingConfirmation(cardData)
                        ? typeLabel
                        : statusLabel,
                    style: TextStyle(
                      color: statusTagTextColor,
                      fontSize: 10,
                      fontWeight: FontWeight.w600,
                      height: 1,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _PlanEntriesBlock extends StatelessWidget {
  const _PlanEntriesBlock({required this.cardData});

  final Map<String, dynamic> cardData;

  @override
  Widget build(BuildContext context) {
    final entries = _planEntries(cardData);
    if (entries.isEmpty) return const SizedBox.shrink();
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.only(left: 12, top: 4, right: 8),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (var index = 0; index < entries.length; index++)
            Padding(
              padding: EdgeInsets.only(
                bottom: index == entries.length - 1 ? 0 : 4,
              ),
              child: Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 7),
                decoration: BoxDecoration(
                  color: context.isDarkTheme
                      ? palette.surfaceElevated.withValues(alpha: 0.52)
                      : palette.surfaceSecondary.withValues(alpha: 0.72),
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(
                    color: palette.borderSubtle.withValues(alpha: 0.72),
                  ),
                ),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Padding(
                      padding: const EdgeInsets.only(top: 1),
                      child: Icon(
                        _planEntryIcon(entries[index]['status']),
                        size: 14,
                        color: _planEntryColor(
                          entries[index]['status'],
                          palette,
                        ),
                      ),
                    ),
                    const SizedBox(width: 7),
                    Expanded(
                      child: Text(
                        _planEntryText(entries[index], index),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: palette.textSecondary,
                          fontSize: 11,
                          fontWeight: FontWeight.w600,
                          height: 1.25,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
        ],
      ),
    );
  }
}

List<Map<String, dynamic>> _planEntries(Map<String, dynamic> cardData) {
  final raw = cardData['planEntries'] ?? cardData['entries'];
  if (raw is! List) return const <Map<String, dynamic>>[];
  return raw
      .whereType<Map>()
      .map((entry) => Map<String, dynamic>.from(entry))
      .where((entry) => _planEntryText(entry, 0).trim().isNotEmpty)
      .toList(growable: false);
}

String _planEntryText(Map<String, dynamic> entry, int index) {
  final text = (entry['content'] ?? entry['title'] ?? entry['text'] ?? '')
      .toString()
      .trim();
  return text.isEmpty ? '任务 ${index + 1}' : text;
}

IconData _planEntryIcon(Object? rawStatus) {
  final status = rawStatus.toString().trim().toLowerCase();
  if (status == 'completed' || status == 'complete' || status == 'done') {
    return Icons.check_circle_rounded;
  }
  if (status == 'in_progress' ||
      status == 'in-progress' ||
      status == 'inprogress' ||
      status == 'running') {
    return Icons.radio_button_checked_rounded;
  }
  return Icons.radio_button_unchecked_rounded;
}

Color _planEntryColor(Object? rawStatus, dynamic palette) {
  final status = rawStatus.toString().trim().toLowerCase();
  if (status == 'completed' || status == 'complete' || status == 'done') {
    return const Color(0xFF2F8F4E);
  }
  if (status == 'in_progress' ||
      status == 'in-progress' ||
      status == 'inprogress' ||
      status == 'running') {
    return palette.accentPrimary;
  }
  return palette.textTertiary;
}

class _AgentToolCardWithImagePreview extends StatelessWidget {
  const _AgentToolCardWithImagePreview({
    required this.cardData,
    required this.child,
  });

  final Map<String, dynamic> cardData;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final images = _resolveAgentToolPreviewImages(cardData);
    if (images.isEmpty) {
      return child;
    }
    final cardId = (cardData['cardId'] ?? cardData['toolCallId'] ?? 'tool')
        .toString();
    final heroTags = List<String>.generate(
      images.length,
      (index) => 'agent_tool_image_${cardId}_$index',
    );
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        child,
        const SizedBox(height: 6),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (var index = 0; index < images.length; index++)
              GestureDetector(
                key: ValueKey('agent-tool-image-preview-$cardId-$index'),
                onTap: () => ImagePreviewOverlay.showAll(
                  context,
                  sources: images.map((image) => image.source).toList(),
                  initialIndex: index,
                  heroTags: heroTags,
                ),
                child: Hero(
                  tag: heroTags[index],
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(12),
                    child: SizedBox(
                      width: math
                          .min(220, MediaQuery.sizeOf(context).width * 0.62)
                          .toDouble(),
                      height: 136,
                      child: images[index].thumbnail,
                    ),
                  ),
                ),
              ),
          ],
        ),
      ],
    );
  }
}

class _AgentToolPreviewImage {
  const _AgentToolPreviewImage({required this.source, required this.thumbnail});

  final ImagePreviewSource source;
  final Widget thumbnail;
}

List<_AgentToolPreviewImage> _resolveAgentToolPreviewImages(
  Map<String, dynamic> cardData,
) {
  final candidates = <dynamic>[
    cardData['imageDataUrl'],
    cardData['dataUrl'],
    cardData['imageUrl'],
    if (cardData['artifacts'] is List) ...(cardData['artifacts'] as List),
  ];
  final images = <_AgentToolPreviewImage>[];
  final seen = <String>{};

  void addCandidate(dynamic candidate) {
    if (candidate is Map) {
      final normalized = candidate.map(
        (key, value) => MapEntry(key.toString(), value),
      );
      for (final key in const <String>[
        'imageDataUrl',
        'dataUrl',
        'imageUrl',
        'url',
        'path',
      ]) {
        addCandidate(normalized[key]);
      }
      return;
    }
    final value = (candidate ?? '').toString().trim();
    if (value.isEmpty || !seen.add(value)) {
      return;
    }
    if (value.startsWith('data:image/')) {
      final comma = value.indexOf(',');
      if (comma < 0 || comma == value.length - 1) {
        return;
      }
      try {
        final bytes = base64Decode(value.substring(comma + 1));
        images.add(
          _AgentToolPreviewImage(
            source: MemoryImageSource(bytes),
            thumbnail: Image.memory(
              bytes,
              fit: BoxFit.cover,
              gaplessPlayback: true,
              errorBuilder: (_, _, _) => const ColoredBox(
                color: Color(0xFFE9EEF5),
                child: Icon(Icons.broken_image_outlined),
              ),
            ),
          ),
        );
      } catch (_) {
        return;
      }
      return;
    }
    if (value.startsWith('http://') || value.startsWith('https://')) {
      images.add(
        _AgentToolPreviewImage(
          source: NetworkImageSource(value),
          thumbnail: Image.network(value, fit: BoxFit.cover),
        ),
      );
      return;
    }
    final lower = value.toLowerCase();
    if (lower.endsWith('.png') ||
        lower.endsWith('.jpg') ||
        lower.endsWith('.jpeg') ||
        lower.endsWith('.webp') ||
        lower.endsWith('.gif')) {
      images.add(
        _AgentToolPreviewImage(
          source: FileImageSource(value),
          thumbnail: Image.file(File(value), fit: BoxFit.cover),
        ),
      );
    }
  }

  for (final candidate in candidates) {
    addCandidate(candidate);
  }
  return images;
}

bool _isCompletedVlmTask(Map<String, dynamic> cardData) {
  final toolName = canonicalAgentToolName(
    (cardData['toolName'] ?? '').toString(),
  );
  return toolName == 'vlm_task' &&
      (cardData['status'] ?? '').toString() != 'running';
}

class _VlmTaskResultCard extends StatelessWidget {
  const _VlmTaskResultCard({required this.cardData});

  final Map<String, dynamic> cardData;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final status = (cardData['status'] ?? 'success').toString();
    final success = status == 'success';
    final payload = _vlmResultPayload(cardData);
    final runId = _firstNonEmptyVlmValue([
      payload['run_id'],
      payload['runId'],
      cardData['run_id'],
      cardData['runId'],
    ]);
    final summary = (payload['content'] ?? cardData['summary'] ?? '')
        .toString()
        .trim();
    final autoRegistered = payload['auto_registered'] == true;
    final registeredFunctionId = _firstNonEmptyVlmValue([
      payload['registered_function_id'],
      payload['function_id'],
    ]);
    final registrationHint = (payload['registration_hint'] ?? '')
        .toString()
        .trim();
    final title = LegacyTextLocalizer.localize(
      success ? 'GUI 任务已完成' : 'GUI 任务未完成',
    );
    final stateColor = success
        ? const Color(0xFF2F8F4E)
        : resolveAgentToolStatusColor(status);

    return Align(
      alignment: Alignment.centerLeft,
      child: Container(
        key: const ValueKey('vlm-task-result-card'),
        constraints: BoxConstraints(
          maxWidth: MediaQuery.sizeOf(context).width * 0.84,
        ),
        margin: const EdgeInsets.only(top: 7, bottom: 3),
        padding: const EdgeInsets.fromLTRB(13, 12, 10, 8),
        decoration: BoxDecoration(
          color: context.isDarkTheme
              ? palette.surfaceSecondary
              : const Color(0xFFF5F8FC),
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: palette.borderSubtle),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  success
                      ? LucideIcons.circleCheck
                      : resolveAgentToolStatusIcon(status, 'builtin'),
                  size: 17,
                  color: stateColor,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    title,
                    style: TextStyle(
                      color: palette.textPrimary,
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ],
            ),
            if (summary.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(
                summary,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: palette.textSecondary,
                  fontSize: 12,
                  height: 1.4,
                ),
              ),
            ],
            if (success && registrationHint.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(
                registrationHint,
                style: TextStyle(
                  color: palette.textSecondary,
                  fontSize: 11,
                  height: 1.35,
                ),
              ),
            ],
            if (runId.isNotEmpty || success) ...[
              const SizedBox(height: 4),
              Wrap(
                spacing: 2,
                children: [
                  if (runId.isNotEmpty)
                    TextButton.icon(
                      key: const ValueKey('vlm-task-open-run-log'),
                      onPressed: () => context.push('/task/run_log/$runId'),
                      icon: const Icon(LucideIcons.logs, size: 15),
                      label: Text(LegacyTextLocalizer.localize('查看 RunLog')),
                    ),
                  if (success)
                    TextButton.icon(
                      key: const ValueKey('vlm-task-open-functions'),
                      onPressed: () => context.push(
                        autoRegistered || runId.isEmpty
                            ? Uri(
                                path: '/task/omniflow',
                                queryParameters: registeredFunctionId.isEmpty
                                    ? null
                                    : {'functionId': registeredFunctionId},
                              ).toString()
                            : '/task/run_log/$runId',
                      ),
                      icon: const Icon(LucideIcons.workflow, size: 15),
                      label: Text(
                        LegacyTextLocalizer.localize(
                          autoRegistered ? '查看复用指令' : '注册为复用指令',
                        ),
                      ),
                    ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }
}

Map<String, dynamic> _vlmResultPayload(Map<String, dynamic> cardData) {
  for (final rawJson in [
    (cardData['resultPreviewJson'] ?? '').toString(),
    (cardData['rawResultJson'] ?? '').toString(),
  ]) {
    final decoded = _decodeJsonMap(rawJson);
    if (decoded.containsKey('run_id') || decoded.containsKey('content')) {
      return decoded;
    }
  }
  return const <String, dynamic>{};
}

String _firstNonEmptyVlmValue(Iterable<dynamic> values) {
  for (final value in values) {
    final text = value?.toString().trim() ?? '';
    if (text.isNotEmpty) return text;
  }
  return '';
}

String? _resolveDiffStatLabel(Map<String, dynamic> cardData) {
  if ((cardData['toolType'] ?? '').toString() != 'file') {
    return null;
  }
  final additions = _asNonNegativeInt(cardData['additions']);
  final deletions = _asNonNegativeInt(cardData['deletions']);
  final changedFiles = _asNonNegativeInt(cardData['changedFiles']);
  if (changedFiles <= 0 && additions <= 0 && deletions <= 0) {
    return null;
  }
  return formatAgentDiffStat(additions: additions, deletions: deletions);
}

int _asNonNegativeInt(dynamic value) {
  final parsed = value is int
      ? value
      : value is num
      ? value.toInt()
      : int.tryParse(value?.toString() ?? '') ?? 0;
  return parsed < 0 ? 0 : parsed;
}

class _DiffStatBadge extends StatelessWidget {
  const _DiffStatBadge({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 4),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.55),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: context.omniPalette.textSecondary,
          fontSize: 9.5,
          fontWeight: FontWeight.w700,
          height: 1,
        ),
      ),
    );
  }
}

bool _isInlineFileTool(Map<String, dynamic> cardData) {
  return (cardData['toolType'] ?? '').toString().trim() == 'file';
}

bool _isAgentInlineTool(Map<String, dynamic> cardData) {
  if (isAgentToolUiStyle(cardData['uiStyle'])) {
    return true;
  }
  final toolName = (cardData['toolName'] ?? '').toString().trim();
  final canonicalToolName = canonicalAgentToolName(toolName) ?? toolName;
  if (canonicalToolName.startsWith('agent.') ||
      canonicalToolName.startsWith('agent/')) {
    return true;
  }
  for (final rawJson in [
    (cardData['rawResultJson'] ?? '').toString(),
    (cardData['resultPreviewJson'] ?? '').toString(),
  ]) {
    final decoded = _decodeJsonMap(rawJson);
    final itemType = (decoded['type'] ?? '').toString();
    if (isAgentToolItemType(itemType)) {
      return true;
    }
  }
  return false;
}

bool _usesInlineToolStyle(
  Map<String, dynamic> cardData, {
  required bool useAgentToolPresentation,
}) {
  return _isInlineFileTool(cardData) ||
      (useAgentToolPresentation && _isAgentInlineTool(cardData));
}

class _InlineToolCallCard extends StatefulWidget {
  const _InlineToolCallCard({
    required this.cardData,
    required this.visualProfile,
  });

  final Map<String, dynamic> cardData;
  final AppBackgroundVisualProfile visualProfile;

  @override
  State<_InlineToolCallCard> createState() => _InlineToolCallCardState();
}

class _InlineToolCallCardState extends State<_InlineToolCallCard> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    final cardData = widget.cardData;
    final palette = context.omniPalette;
    final title = resolveAgentToolTitle(cardData);
    final status = (cardData['status'] ?? 'running').toString();
    final toolType = (cardData['toolType'] ?? '').toString().trim();
    final isFileTool = _isInlineFileTool(cardData);
    final isAgentTool = _isAgentInlineTool(cardData);
    final diffSummary = isFileTool ? _resolveInlineDiffSummary(cardData) : null;
    final hasDiff =
        isFileTool && diffSummary != null && diffSummary.files.isNotEmpty;
    final diffStatLabel = isFileTool
        ? diffSummary == null
              ? _resolveDiffStatLabel(cardData)
              : formatAgentDiffStat(
                  additions: diffSummary.additions,
                  deletions: diffSummary.deletions,
                )
        : null;
    final tooltipSubtitle = _inlineToolSubtitle(cardData, diffSummary, title);
    final filePath = isFileTool
        ? _resolveInlineFilePath(cardData, diffSummary)
        : '';
    final fileName = isFileTool ? _lastPathSegment(filePath) : '';
    final trailingLabel = isFileTool
        ? ''
        : _inlineToolTrailingLabel(cardData, status: status);
    final useLightProfile =
        !context.isDarkTheme && widget.visualProfile.usesLightText;
    final titleColor = context.isDarkTheme
        ? palette.textSecondary.withValues(alpha: 0.92)
        : useLightProfile
        ? Colors.white.withValues(alpha: 0.72)
        : widget.visualProfile.secondaryTextColor.withValues(alpha: 0.88);
    final mutedColor = context.isDarkTheme
        ? palette.textSecondary.withValues(alpha: 0.64)
        : useLightProfile
        ? Colors.white.withValues(alpha: 0.50)
        : widget.visualProfile.secondaryTextColor.withValues(alpha: 0.58);
    final pressedOverlayColor = context.isDarkTheme
        ? palette.surfaceSecondary.withValues(alpha: 0.38)
        : useLightProfile
        ? Colors.white.withValues(alpha: 0.08)
        : Colors.black.withValues(alpha: 0.035);
    final maxDiffHeight = math.max(
      220.0,
      math.min(460.0, MediaQuery.sizeOf(context).height * 0.58),
    );

    return Tooltip(
      message: [
        title,
        if (tooltipSubtitle.isNotEmpty) tooltipSubtitle,
        if (diffStatLabel != null) diffStatLabel,
      ].join('\n'),
      child: Align(
        alignment: Alignment.centerLeft,
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxWidth: MediaQuery.sizeOf(context).width * 0.90,
          ),
          child: Container(
            margin: const EdgeInsets.only(top: 6, bottom: 4),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Material(
                  color: Colors.transparent,
                  borderRadius: BorderRadius.circular(8),
                  clipBehavior: Clip.antiAlias,
                  child: InkWell(
                    key: const ValueKey('inline-file-diff-title-toggle'),
                    onTap: hasDiff
                        ? () => setState(() => _expanded = !_expanded)
                        : isAgentTool
                        ? () => unawaited(
                            showAgentToolDetailSheet(
                              context,
                              cardData: cardData,
                            ),
                          )
                        : null,
                    splashColor: pressedOverlayColor,
                    highlightColor: pressedOverlayColor,
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(2, 5, 5, 5),
                      child: Row(
                        children: [
                          Icon(
                            resolveAgentToolStatusIcon(status, toolType),
                            size: 16,
                            color: mutedColor,
                          ),
                          const SizedBox(width: 6),
                          Expanded(
                            child: _InlineFileTitleText(
                              title: title,
                              fileName: fileName,
                              fullPath: filePath,
                              baseStyle: TextStyle(
                                color: titleColor,
                                fontSize: 12,
                                fontWeight: FontWeight.w500,
                                letterSpacing: 0,
                                height: 1.18,
                              ),
                              fileNameColor: palette.accentPrimary,
                              shimmer:
                                  isAgentTool &&
                                  status == 'running' &&
                                  !isAgentToolAwaitingConfirmation(cardData),
                            ),
                          ),
                          if (diffStatLabel != null) ...[
                            const SizedBox(width: 8),
                            _InlineDiffStatText(
                              label: diffStatLabel,
                              additions:
                                  diffSummary?.additions ??
                                  _asNonNegativeInt(cardData['additions']),
                              deletions:
                                  diffSummary?.deletions ??
                                  _asNonNegativeInt(cardData['deletions']),
                              mutedColor: mutedColor,
                            ),
                          ],
                          if (trailingLabel.isNotEmpty) ...[
                            const SizedBox(width: 8),
                            Text(
                              trailingLabel,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                color: mutedColor,
                                fontSize: 10.5,
                                fontWeight: FontWeight.w700,
                                height: 1,
                              ),
                            ),
                          ],
                          if (hasDiff) ...[
                            const SizedBox(width: 4),
                            AnimatedRotation(
                              turns: _expanded ? 0.5 : 0,
                              duration: const Duration(milliseconds: 220),
                              curve: Curves.easeOutCubic,
                              child: Icon(
                                LucideIcons.chevronDown,
                                size: 18,
                                color: mutedColor,
                              ),
                            ),
                          ],
                        ],
                      ),
                    ),
                  ),
                ),
                AnimatedSize(
                  duration: const Duration(milliseconds: 260),
                  curve: Curves.easeOutCubic,
                  alignment: Alignment.topLeft,
                  child: _expanded && hasDiff
                      ? Padding(
                          padding: const EdgeInsets.only(top: 8),
                          child: ConstrainedBox(
                            constraints: BoxConstraints(
                              maxHeight: maxDiffHeight,
                            ),
                            child: ClipRRect(
                              borderRadius: BorderRadius.circular(14),
                              child: AgentDiffViewer(
                                summary: diffSummary,
                                padding: const EdgeInsets.fromLTRB(0, 0, 0, 0),
                                showOverview: false,
                                showFileHeaders: false,
                              ),
                            ),
                          ),
                        )
                      : const SizedBox.shrink(),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _InlineFileTitleText extends StatelessWidget {
  const _InlineFileTitleText({
    required this.title,
    required this.fileName,
    required this.fullPath,
    required this.baseStyle,
    required this.fileNameColor,
    this.shimmer = false,
  });

  final String title;
  final String fileName;
  final String fullPath;
  final TextStyle baseStyle;
  final Color fileNameColor;
  final bool shimmer;

  @override
  Widget build(BuildContext context) {
    final parts = _splitTitleAroundFileName(title, fileName);
    if (parts.fileName.isEmpty) {
      if (shimmer) {
        return _FlowingToolTitleText(text: title, style: baseStyle);
      }
      return Text(
        title,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: baseStyle,
      );
    }

    final titleRow = Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (parts.prefix.isNotEmpty)
          Flexible(
            child: Text(
              parts.prefix,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: baseStyle,
            ),
          ),
        Flexible(
          child: Tooltip(
            message: fullPath.isEmpty ? parts.fileName : fullPath,
            triggerMode: TooltipTriggerMode.tap,
            waitDuration: Duration.zero,
            showDuration: const Duration(seconds: 3),
            child: Text(
              parts.fileName,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: baseStyle.copyWith(
                color: fileNameColor,
                decoration: TextDecoration.underline,
                decorationColor: fileNameColor,
              ),
            ),
          ),
        ),
        if (parts.suffix.isNotEmpty)
          Flexible(
            child: Text(
              parts.suffix,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: baseStyle,
            ),
          ),
      ],
    );
    if (!shimmer) {
      return titleRow;
    }
    return _FlowingInlineToolTitle(
      baseColor: baseStyle.color ?? context.omniPalette.textSecondary,
      child: titleRow,
    );
  }
}

class _InlineFileTitleParts {
  const _InlineFileTitleParts({
    required this.prefix,
    required this.fileName,
    required this.suffix,
  });

  final String prefix;
  final String fileName;
  final String suffix;
}

_InlineFileTitleParts _splitTitleAroundFileName(String title, String fileName) {
  final normalizedTitle = title.trim();
  final normalizedFileName = fileName.trim();
  if (normalizedFileName.isEmpty) {
    return _InlineFileTitleParts(
      prefix: normalizedTitle,
      fileName: '',
      suffix: '',
    );
  }
  final index = normalizedTitle.lastIndexOf(normalizedFileName);
  if (index >= 0) {
    return _InlineFileTitleParts(
      prefix: normalizedTitle.substring(0, index),
      fileName: normalizedFileName,
      suffix: normalizedTitle.substring(index + normalizedFileName.length),
    );
  }
  final prefix = normalizedTitle.isEmpty ? '' : '$normalizedTitle · ';
  return _InlineFileTitleParts(
    prefix: prefix,
    fileName: normalizedFileName,
    suffix: '',
  );
}

class _InlineDiffStatText extends StatelessWidget {
  const _InlineDiffStatText({
    required this.label,
    required this.additions,
    required this.deletions,
    required this.mutedColor,
  });

  final String label;
  final int additions;
  final int deletions;
  final Color mutedColor;

  @override
  Widget build(BuildContext context) {
    final addColor = context.isDarkTheme
        ? const Color(0xFF7EE787)
        : const Color(0xFF1A7F37);
    final deleteColor = context.isDarkTheme
        ? const Color(0xFFFF7B72)
        : const Color(0xFFCF222E);
    final parts = label.split(' ');
    if (parts.length == 2 && parts.first.startsWith('+')) {
      return RichText(
        text: TextSpan(
          style: TextStyle(
            color: mutedColor,
            fontSize: 10.5,
            fontWeight: FontWeight.w700,
            height: 1,
          ),
          children: [
            TextSpan(
              text: parts.first,
              style: TextStyle(color: additions > 0 ? addColor : mutedColor),
            ),
            const TextSpan(text: ' '),
            TextSpan(
              text: parts.last,
              style: TextStyle(color: deletions > 0 ? deleteColor : mutedColor),
            ),
          ],
        ),
      );
    }
    return Text(
      label,
      style: TextStyle(
        color: mutedColor,
        fontSize: 10.5,
        fontWeight: FontWeight.w700,
        height: 1,
      ),
    );
  }
}

class _FlowingInlineToolTitle extends StatefulWidget {
  const _FlowingInlineToolTitle({required this.child, required this.baseColor});

  final Widget child;
  final Color baseColor;

  @override
  State<_FlowingInlineToolTitle> createState() =>
      _FlowingInlineToolTitleState();
}

class _FlowingInlineToolTitleState extends State<_FlowingInlineToolTitle>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1800),
  )..repeat();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (MediaQuery.disableAnimationsOf(context)) {
      return widget.child;
    }
    // Keep motion as a live-state cue, but avoid the high-contrast white flash
    // that previously made every running tool look like a neon badge.
    final highlightColor = context.isDarkTheme
        ? Colors.white.withValues(alpha: 0.42)
        : Colors.white.withValues(alpha: 0.62);
    return AnimatedBuilder(
      animation: _controller,
      child: widget.child,
      builder: (context, child) {
        return ShaderMask(
          blendMode: BlendMode.srcIn,
          shaderCallback: (bounds) {
            final textWidth = bounds.width <= 0 ? 1.0 : bounds.width;
            final shimmerWidth = (textWidth * 0.72)
                .clamp(52.0, 180.0)
                .toDouble();
            final travelDistance = textWidth + shimmerWidth;
            final shimmerLeft =
                bounds.left - shimmerWidth + travelDistance * _controller.value;
            return LinearGradient(
              begin: Alignment.centerLeft,
              end: Alignment.centerRight,
              colors: [widget.baseColor, highlightColor, widget.baseColor],
              stops: const [0.08, 0.5, 0.92],
            ).createShader(
              Rect.fromLTWH(
                shimmerLeft,
                bounds.top,
                shimmerWidth,
                bounds.height,
              ),
            );
          },
          child: child,
        );
      },
    );
  }
}

AgentDiffSummary? _resolveInlineDiffSummary(Map<String, dynamic> cardData) {
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

String _resolveInlineFilePath(
  Map<String, dynamic> cardData,
  AgentDiffSummary? diffSummary,
) {
  final filePath = (cardData['filePath'] ?? '').toString().trim();
  if (filePath.isNotEmpty) {
    return filePath;
  }
  final primaryPath = (diffSummary?.primaryPath ?? '').trim();
  if (primaryPath.isNotEmpty) {
    return primaryPath;
  }
  return '';
}

String _lastPathSegment(String path) {
  final trimmed = path.trim();
  if (trimmed.isEmpty) {
    return '';
  }
  final normalized = trimmed.replaceAll('\\', '/');
  final segments = normalized.split('/').where((part) => part.isNotEmpty);
  if (segments.isEmpty) {
    return normalized;
  }
  return segments.last;
}

String _inlineToolSubtitle(
  Map<String, dynamic> cardData,
  AgentDiffSummary? diffSummary,
  String title,
) {
  final filePath = (cardData['filePath'] ?? '').toString().trim();
  if (filePath.isNotEmpty && filePath != title) {
    return filePath;
  }
  final primaryPath = (diffSummary?.primaryPath ?? '').trim();
  if (primaryPath.isNotEmpty && primaryPath != title) {
    return primaryPath;
  }
  final preview = resolveAgentToolPreview(cardData).trim();
  if (preview.isNotEmpty && preview != title) {
    return preview;
  }
  final summary = (cardData['summary'] ?? '').toString().trim();
  if (summary.isNotEmpty && summary != title) {
    return summary;
  }
  return '';
}

String _inlineToolTrailingLabel(
  Map<String, dynamic> cardData, {
  required String status,
}) {
  final label =
      status == 'running' && !isAgentToolAwaitingConfirmation(cardData)
      ? resolveAgentToolTypeLabel(cardData)
      : resolveAgentToolStatusLabel(cardData);
  return label.trim();
}

bool _isSubagentTool(Map<String, dynamic> cardData) {
  return (cardData['toolType'] ?? '').toString().trim() == 'subagent' ||
      (cardData['toolName'] ?? '').toString().trim() == 'subagent_dispatch';
}

List<_SubagentStatusGroup> _resolveSubagentStatusGroups(
  Map<String, dynamic> cardData,
  List<_SubagentTimelineEvent> events,
) {
  final keyedEvents = <String, List<_SubagentTimelineEvent>>{};
  final fallbackEvents = <_SubagentTimelineEvent>[];

  for (final event in events) {
    final key = _subagentGroupKey(event);
    if (key == null) {
      fallbackEvents.add(event);
      continue;
    }
    keyedEvents.putIfAbsent(key, () => <_SubagentTimelineEvent>[]).add(event);
  }

  if (keyedEvents.isEmpty) {
    final statusText = _resolveSubagentStatusText(cardData, events);
    if (statusText.isEmpty) {
      return const <_SubagentStatusGroup>[];
    }
    return <_SubagentStatusGroup>[
      _SubagentStatusGroup(
        key: 'subagent-dispatch',
        statusText: statusText,
        events: events.isEmpty ? fallbackEvents : events,
        isTerminalStatus: (cardData['status'] ?? '').toString() != 'running',
        sortIndex: 0,
      ),
    ];
  }

  final groups = keyedEvents.entries
      .map((entry) {
        final groupEvents = entry.value..sort(_compareSubagentEvents);
        final latest = groupEvents.last;
        return _SubagentStatusGroup(
          key: entry.key,
          statusText: latest.summary,
          events: groupEvents,
          isTerminalStatus: groupEvents.any(_isTerminalSubagentEvent),
          sortIndex: _resolveSubagentSortIndex(groupEvents),
        );
      })
      .toList(growable: false);

  groups.sort((left, right) {
    final indexCompare = left.sortIndex.compareTo(right.sortIndex);
    if (indexCompare != 0) {
      return indexCompare;
    }
    return left.key.compareTo(right.key);
  });
  return groups;
}

String? _subagentGroupKey(_SubagentTimelineEvent event) {
  if (event.subagentId.isNotEmpty) {
    return 'id:${event.subagentId}';
  }
  final taskIndex = event.taskIndex;
  if (taskIndex != null) {
    return 'task:$taskIndex';
  }
  return null;
}

int _resolveSubagentSortIndex(List<_SubagentTimelineEvent> events) {
  for (final event in events) {
    final taskIndex = event.taskIndex;
    if (taskIndex != null) {
      return taskIndex;
    }
  }
  return events.isEmpty ? 0 : events.first.sequence;
}

int _compareSubagentEvents(
  _SubagentTimelineEvent left,
  _SubagentTimelineEvent right,
) {
  final sequenceCompare = left.sequence.compareTo(right.sequence);
  if (sequenceCompare != 0) {
    return sequenceCompare;
  }
  final createdCompare = left.createdAt.compareTo(right.createdAt);
  if (createdCompare != 0) {
    return createdCompare;
  }
  return left.summary.compareTo(right.summary);
}

bool _isTerminalSubagentEvent(_SubagentTimelineEvent event) {
  return event.kind == 'subagent_completed' ||
      event.kind == 'subagent_failed' ||
      event.kind == 'subagent_cancelled';
}

String _resolveSubagentStatusText(
  Map<String, dynamic> cardData,
  List<_SubagentTimelineEvent> events,
) {
  final explicit = (cardData['subagentStatusText'] ?? '').toString().trim();
  if (explicit.isNotEmpty) {
    return explicit;
  }

  final status = (cardData['status'] ?? 'running').toString();
  if (status != 'running') {
    final result = _resolveSubagentResultStatusText(cardData);
    if (result.isNotEmpty) {
      return result;
    }
  }
  if (events.isNotEmpty) {
    return events.last.summary;
  }
  final reasoning = _compactInline(
    (cardData['reasoning_content'] ?? '').toString(),
  );
  if (reasoning.isNotEmpty) {
    return '${_isEnglish ? 'Thinking' : '思考'}：$reasoning';
  }
  final progress = _compactInline((cardData['progress'] ?? '').toString());
  if (progress.isNotEmpty) {
    return progress;
  }
  return _compactInline(resolveAgentToolPreview(cardData));
}

String _resolveSubagentResultStatusText(Map<String, dynamic> cardData) {
  for (final rawJson in [
    (cardData['resultPreviewJson'] ?? '').toString(),
    (cardData['rawResultJson'] ?? '').toString(),
  ]) {
    final result = _decodeSubagentResultStatusText(rawJson);
    if (result.isNotEmpty) {
      return result;
    }
  }
  final summary = _compactInline((cardData['summary'] ?? '').toString());
  return summary;
}

String _decodeSubagentResultStatusText(String rawJson) {
  final decoded = _decodeJsonMap(rawJson);
  final results = decoded['results'];
  if (results is! List || results.isEmpty) {
    return '';
  }
  final resultMaps = results.whereType<Map>().toList(growable: false);
  if (resultMaps.isEmpty) {
    return '';
  }
  final last = resultMaps.last;
  final taskIndex = _asNullableInt(last['taskIndex']);
  final displayIndex = taskIndex == null ? '' : ' #${taskIndex + 1}';
  final status = (last['status'] ?? '').toString().trim();
  final result = _compactInline((last['result'] ?? '').toString());
  final error = _compactInline((last['error'] ?? '').toString());
  if (status == 'completed' && result.isNotEmpty) {
    return 'SubAgent$displayIndex ${_isEnglish ? 'result' : '得到结果'}：$result';
  }
  if (error.isNotEmpty) {
    return 'SubAgent$displayIndex ${_isEnglish ? 'failed' : '失败'}：$error';
  }
  return '';
}

List<_SubagentTimelineEvent> _resolveSubagentTimelineEvents(
  Map<String, dynamic> cardData,
) {
  final events = <_SubagentTimelineEvent>[];
  final rawEvents = cardData['subagentEvents'];
  if (rawEvents is List) {
    for (final rawEvent in rawEvents.whereType<Map>()) {
      final event = _SubagentTimelineEvent.fromMap(rawEvent);
      if (event != null) {
        events.add(event);
      }
    }
  }

  if (events.isEmpty) {
    final reasoning = _compactInline(
      (cardData['reasoning_content'] ?? '').toString(),
    );
    if (reasoning.isNotEmpty) {
      events.add(
        _SubagentTimelineEvent(
          sequence: 0,
          createdAt: 0,
          kind: 'thinking',
          summary: '${_isEnglish ? 'Thinking' : '思考'}：$reasoning',
          status: 'running',
        ),
      );
    }
    final progress = _compactInline((cardData['progress'] ?? '').toString());
    if (progress.isNotEmpty) {
      events.add(
        _SubagentTimelineEvent(
          sequence: 1,
          createdAt: 1,
          kind: 'tool_progress',
          summary: progress,
          status: (cardData['status'] ?? 'running').toString(),
        ),
      );
    }
    events.addAll(_synthesizeSubagentResultEvents(cardData));
  }

  events.sort(_compareSubagentEvents);
  return events;
}

List<_SubagentTimelineEvent> _synthesizeSubagentResultEvents(
  Map<String, dynamic> cardData,
) {
  Map<String, dynamic> decoded = const <String, dynamic>{};
  for (final rawJson in [
    (cardData['resultPreviewJson'] ?? '').toString(),
    (cardData['rawResultJson'] ?? '').toString(),
  ]) {
    decoded = _decodeJsonMap(rawJson);
    if (decoded['results'] is List) {
      break;
    }
  }
  final results = decoded['results'];
  if (results is! List || results.isEmpty) {
    return const <_SubagentTimelineEvent>[];
  }
  final events = <_SubagentTimelineEvent>[];
  var sequence = 10;
  for (final result in results.whereType<Map>()) {
    final taskIndex = _asNullableInt(result['taskIndex']);
    final label = taskIndex == null ? 'SubAgent' : 'SubAgent #${taskIndex + 1}';
    final toolCalls = result['toolCalls'];
    if (toolCalls is List && toolCalls.isNotEmpty) {
      events.add(
        _SubagentTimelineEvent(
          sequence: sequence++,
          createdAt: sequence,
          kind: 'tool_started',
          summary:
              '$label ${_isEnglish ? 'called tools' : '调用工具'}：${toolCalls.join(', ')}',
          status: 'completed',
          taskIndex: taskIndex,
        ),
      );
    }
    final status = (result['status'] ?? '').toString().trim();
    final output = _compactInline((result['result'] ?? '').toString());
    final error = _compactInline((result['error'] ?? '').toString());
    if (status == 'completed' && output.isNotEmpty) {
      events.add(
        _SubagentTimelineEvent(
          sequence: sequence++,
          createdAt: sequence,
          kind: 'subagent_completed',
          summary: '$label ${_isEnglish ? 'result' : '得到结果'}：$output',
          status: 'completed',
          taskIndex: taskIndex,
        ),
      );
    } else if (error.isNotEmpty) {
      events.add(
        _SubagentTimelineEvent(
          sequence: sequence++,
          createdAt: sequence,
          kind: 'subagent_failed',
          summary: '$label ${_isEnglish ? 'failed' : '失败'}：$error',
          status: 'failed',
          taskIndex: taskIndex,
        ),
      );
    }
  }
  return events;
}

Map<String, dynamic> _decodeJsonMap(String rawJson) {
  final text = rawJson.trim();
  if (text.isEmpty) {
    return const <String, dynamic>{};
  }
  try {
    final decoded = jsonDecode(text);
    if (decoded is Map<String, dynamic>) {
      return decoded;
    }
    if (decoded is Map) {
      return decoded.map<String, dynamic>(
        (key, value) => MapEntry(key.toString(), value),
      );
    }
  } catch (_) {
    return const <String, dynamic>{};
  }
  return const <String, dynamic>{};
}

String _compactInline(String text, {int limit = 160}) {
  final normalized = text.replaceAll(RegExp(r'\s+'), ' ').trim();
  if (normalized.length <= limit) {
    return normalized;
  }
  return '${normalized.substring(0, limit).trimRight()}...';
}

int? _asNullableInt(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse((value ?? '').toString());
}

int _asInt(dynamic value) {
  return _asNullableInt(value) ?? 0;
}

bool get _isEnglish => LegacyTextLocalizer.isEnglish;

class _SubagentStatusGroup {
  const _SubagentStatusGroup({
    required this.key,
    required this.statusText,
    required this.events,
    required this.isTerminalStatus,
    required this.sortIndex,
  });

  final String key;
  final String statusText;
  final List<_SubagentTimelineEvent> events;
  final bool isTerminalStatus;
  final int sortIndex;
}

class _SubagentTimelineEvent {
  const _SubagentTimelineEvent({
    required this.sequence,
    required this.createdAt,
    required this.kind,
    required this.summary,
    required this.status,
    this.taskIndex,
    this.subagentId = '',
    this.profileId = '',
    this.toolName = '',
  });

  final int sequence;
  final int createdAt;
  final String kind;
  final String summary;
  final String status;
  final int? taskIndex;
  final String subagentId;
  final String profileId;
  final String toolName;

  static _SubagentTimelineEvent? fromMap(Map<dynamic, dynamic> raw) {
    final summary = (raw['summary'] ?? raw['message'] ?? raw['text'] ?? '')
        .toString()
        .trim();
    if (summary.isEmpty) {
      return null;
    }
    return _SubagentTimelineEvent(
      sequence: _asInt(raw['seq']),
      createdAt: _asInt(raw['createdAt']),
      kind: (raw['kind'] ?? '').toString().trim(),
      summary: summary,
      status: (raw['status'] ?? 'running').toString().trim(),
      taskIndex: _asNullableInt(raw['taskIndex']),
      subagentId: (raw['subagentId'] ?? '').toString().trim(),
      profileId: (raw['profileId'] ?? '').toString().trim(),
      toolName: (raw['toolName'] ?? '').toString().trim(),
    );
  }
}

class _SubagentStatusBlock extends StatelessWidget {
  const _SubagentStatusBlock({
    required this.group,
    required this.shimmer,
    required this.expanded,
    required this.onTap,
  });

  final _SubagentStatusGroup group;
  final bool shimmer;
  final bool expanded;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _SubagentStatusLine(
          text: group.statusText,
          shimmer: shimmer,
          expanded: expanded,
          onTap: onTap,
        ),
        _SubagentTimeline(events: group.events, expanded: expanded),
      ],
    );
  }
}

class _SubagentStatusLine extends StatelessWidget {
  const _SubagentStatusLine({
    required this.text,
    required this.shimmer,
    required this.expanded,
    required this.onTap,
  });

  final String text;
  final bool shimmer;
  final bool expanded;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final color = context.isDarkTheme
        ? Color.lerp(palette.textSecondary, palette.accentPrimary, 0.32)!
        : const Color(0xFF51657E);
    return Padding(
      padding: const EdgeInsets.only(top: 6, right: 4),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(6),
          splashColor: palette.accentPrimary.withValues(alpha: 0.06),
          highlightColor: Colors.transparent,
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 3),
            child: Row(
              children: [
                Expanded(
                  child: shimmer
                      ? _FlowingToolTitleText(
                          text: text,
                          style: TextStyle(
                            color: color,
                            fontSize: 11.2,
                            fontWeight: FontWeight.w500,
                            letterSpacing: 0,
                            height: 1.2,
                          ),
                        )
                      : Text(
                          text,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: color,
                            fontSize: 11.2,
                            fontWeight: FontWeight.w500,
                            letterSpacing: 0,
                            height: 1.2,
                          ),
                        ),
                ),
                const SizedBox(width: 4),
                AnimatedRotation(
                  turns: expanded ? 0.5 : 0,
                  duration: const Duration(milliseconds: 220),
                  curve: Curves.easeOutCubic,
                  child: Icon(
                    LucideIcons.chevronDown,
                    size: 15,
                    color: color.withValues(alpha: 0.78),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _SubagentTimeline extends StatelessWidget {
  const _SubagentTimeline({required this.events, required this.expanded});

  final List<_SubagentTimelineEvent> events;
  final bool expanded;

  @override
  Widget build(BuildContext context) {
    final showTimeline = expanded && events.isNotEmpty;
    return AnimatedSize(
      duration: const Duration(milliseconds: 260),
      curve: Curves.easeOutCubic,
      alignment: Alignment.topLeft,
      child: showTimeline
          ? Padding(
              padding: const EdgeInsets.only(left: 18, top: 4, bottom: 4),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  for (var i = 0; i < events.length; i++)
                    _SubagentTimelineRow(
                      event: events[i],
                      isLast: i == events.length - 1,
                    ),
                ],
              ),
            )
          : const SizedBox.shrink(),
    );
  }
}

class _SubagentTimelineRow extends StatelessWidget {
  const _SubagentTimelineRow({required this.event, required this.isLast});

  final _SubagentTimelineEvent event;
  final bool isLast;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final normalizedStatus = event.status == 'failed'
        ? 'error'
        : event.status == 'completed'
        ? 'success'
        : event.status;
    final statusColor = resolveAgentToolStatusColor(normalizedStatus);
    final lineColor = context.isDarkTheme
        ? palette.borderSubtle.withValues(alpha: 0.58)
        : const Color(0x1F0F2034);
    final textColor = context.isDarkTheme
        ? palette.textSecondary
        : const Color(0xFF5F7188);
    final icon = _iconForSubagentEvent(event.kind);
    final detailText = _subagentTimelineDetailText(event);

    return IntrinsicHeight(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 16,
            child: Column(
              children: [
                Container(
                  width: 14,
                  height: 14,
                  decoration: BoxDecoration(
                    color: statusColor.withValues(alpha: 0.12),
                    shape: BoxShape.circle,
                  ),
                  child: Icon(icon, size: 9, color: statusColor),
                ),
                if (!isLast)
                  Expanded(child: Container(width: 1, color: lineColor)),
              ],
            ),
          ),
          const SizedBox(width: 7),
          Flexible(
            child: Padding(
              padding: EdgeInsets.only(bottom: isLast ? 0 : 9),
              child: Text(
                detailText,
                style: TextStyle(
                  color: textColor,
                  fontSize: 11,
                  height: 1.32,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  String _subagentTimelineDetailText(_SubagentTimelineEvent event) {
    final withoutAgentPrefix = event.summary
        .replaceFirst(RegExp(r'^SubAgent(?: #\d+)?\s*'), '')
        .trim();
    final text = withoutAgentPrefix.isEmpty
        ? event.summary.trim()
        : withoutAgentPrefix;

    String stripLabel(List<String> labels) {
      var result = text;
      for (final label in labels) {
        result = result
            .replaceFirst(
              RegExp('^${RegExp.escape(label)}(?:\\s*[：:]\\s*|\\s+)'),
              '',
            )
            .trim();
      }
      return result.isEmpty ? text : result;
    }

    switch (event.kind) {
      case 'subagent_started':
        return stripLabel(const ['开始', 'started']);
      case 'thinking':
        return stripLabel(const ['思考', 'thinking', 'Thinking']);
      case 'tool_started':
        return stripLabel(const [
          '调用工具',
          '工具调用',
          'called tool',
          'called tools',
        ]);
      case 'tool_progress':
        return stripLabel(const ['工具进度', 'tool progress']);
      case 'tool_completed':
        return stripLabel(const ['工具完成', 'completed tool']);
      case 'message':
        return stripLabel(const ['输出', 'output']);
      case 'subagent_completed':
        return stripLabel(const ['得到结果', 'result']);
      case 'subagent_failed':
        return stripLabel(const ['失败', 'failed']);
      default:
        return text;
    }
  }

  IconData _iconForSubagentEvent(String kind) {
    switch (kind) {
      case 'thinking_started':
      case 'thinking':
        return LucideIcons.brain;
      case 'tool_started':
      case 'tool_progress':
      case 'tool_completed':
        return LucideIcons.wrench;
      case 'subagent_completed':
        return LucideIcons.check;
      case 'subagent_failed':
        return LucideIcons.triangleAlert;
      default:
        return LucideIcons.network;
    }
  }
}

class _FlowingToolTitleText extends StatefulWidget {
  const _FlowingToolTitleText({required this.text, required this.style});

  final String text;
  final TextStyle style;

  @override
  State<_FlowingToolTitleText> createState() => _FlowingToolTitleTextState();
}

class _FlowingToolTitleTextState extends State<_FlowingToolTitleText>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1800),
  )..repeat();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final child = Text(
      widget.text,
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: widget.style,
    );
    if (MediaQuery.disableAnimationsOf(context)) {
      return child;
    }

    final baseColor = widget.style.color ?? context.omniPalette.textPrimary;
    // Motion remains visible for running calls without washing the title out.
    final highlightColor = context.isDarkTheme
        ? Colors.white.withValues(alpha: 0.42)
        : Colors.white.withValues(alpha: 0.62);

    return AnimatedBuilder(
      animation: _controller,
      child: child,
      builder: (context, child) {
        return ShaderMask(
          blendMode: BlendMode.srcIn,
          shaderCallback: (bounds) {
            final textWidth = bounds.width <= 0 ? 1.0 : bounds.width;
            final shimmerWidth = (textWidth * 0.72)
                .clamp(52.0, 180.0)
                .toDouble();
            final travelDistance = textWidth + shimmerWidth;
            final shimmerLeft =
                bounds.left - shimmerWidth + travelDistance * _controller.value;
            return LinearGradient(
              begin: Alignment.centerLeft,
              end: Alignment.centerRight,
              colors: [baseColor, highlightColor, baseColor],
              stops: const [0.08, 0.5, 0.92],
            ).createShader(
              Rect.fromLTWH(
                shimmerLeft,
                bounds.top,
                shimmerWidth,
                bounds.height,
              ),
            );
          },
          child: child,
        );
      },
    );
  }
}

class _StatusIcon extends StatelessWidget {
  const _StatusIcon({required this.status, required this.toolType});

  final String status;
  final dynamic toolType;

  @override
  Widget build(BuildContext context) {
    final color = resolveAgentToolStatusColor(status);
    final iconColor = context.isDarkTheme
        ? Color.lerp(context.omniPalette.textSecondary, color, 0.38)!
        : color;
    return DecoratedBox(
      key: const ValueKey('agent-tool-summary-leading-icon'),
      decoration: BoxDecoration(
        color: color.withValues(alpha: context.isDarkTheme ? 0.16 : 0.10),
        shape: BoxShape.circle,
      ),
      child: SizedBox(
        width: 24,
        height: 24,
        child: Center(
          child: status == 'running'
              ? SizedBox(
                  width: 14,
                  height: 14,
                  child: CircularProgressIndicator(
                    strokeWidth: 1.7,
                    valueColor: AlwaysStoppedAnimation<Color>(iconColor),
                  ),
                )
              : Icon(
                  status == 'pending'
                      ? Icons.hourglass_top_rounded
                      : resolveAgentToolStatusIcon(
                          status,
                          (toolType ?? '').toString(),
                        ),
                  size: 16,
                  color: iconColor,
                ),
        ),
      ),
    );
  }
}
