import 'package:flutter/material.dart';
import 'package:ui/services/app_background_service.dart';
import 'artifact_card.dart';
import 'agent_tool_summary_card.dart';
import 'context_compaction_marker_card.dart';
import 'agent_request_card.dart';
import 'package:ui/services/agent_message_kinds.dart';
import 'deep_thinking_card.dart';
import 'permission_section_card.dart';
import 'stage_hint_card.dart';
import 'openclaw_attachment_card.dart';
import 'acp_audio_card.dart';
import 'package:ui/services/agent_acp_card_normalizer.dart';

/// 任务执行前的回调类型
typedef OnBeforeTaskExecute = Future<void> Function();
typedef OnRequestAuthorize = void Function(List<String> requiredPermissionIds);

/// 卡片组件工厂
///
/// 根据卡片类型返回对应的Widget
/// 支持扩展新的卡片类型
class CardWidgetFactory {
  static Widget createCard(
    Map<String, dynamic> cardData, {
    OnBeforeTaskExecute? onBeforeTaskExecute,
    OnRequestAuthorize? onRequestAuthorize,
    void Function(String taskId)? onCancelTask,
    bool enableThinkingCollapse = false,
    bool thinkingAutoCollapseOnComplete = true,
    bool useAgentToolPresentation = true,
    bool? showThinkingAvatarOverride,
    ScrollController? parentScrollController,
    VoidCallback? onParentScrollHandoff,
    VoidCallback? onStreamingTextLayoutChanged,
    AppBackgroundConfig appearanceConfig = AppBackgroundConfig.defaults,
    AppBackgroundVisualProfile visualProfile =
        AppBackgroundVisualProfile.defaultProfile,
  }) {
    cardData = AgentAcpCardNormalizer.normalize(cardData);
    final type = cardData['type'] as String? ?? 'unknown';
    if (isAgentRequestCardType(type)) {
      // ACP requests are transport state, not large nested forms.  The chat
      // timeline projects them into a normal Agent bubble; this lightweight
      // fallback is only used by legacy/raw card surfaces.
      return AgentRequestNotice(cardData: cardData);
    }

    switch (type) {
      case 'deep_thinking':
        final stage = _asInt(cardData['stage']) ?? 1;
        final isLoading = _asBool(
          cardData['isLoading'],
          fallback: stage != 4 && stage != 5,
        );
        final thinkingText = _asString(cardData['thinkingContent']);
        final taskID = _asNullableString(cardData['taskID']);
        final cardId = _asNullableString(cardData['cardId']);
        final startTime = _asInt(cardData['startTime']);
        final endTime = _asInt(cardData['endTime']);
        final isExecutable = _asBool(cardData['isExecutable']);
        final isCollapsible = _asBool(
          cardData['isCollapsible'],
          fallback: enableThinkingCollapse,
        );
        final keyIdentity = cardId ?? taskID;
        // cardId is the replacement identity. Including a mutable timestamp
        // remounts the typewriter whenever a restored card receives a new
        // boundary and makes a single reasoning stream look duplicated.
        final key = keyIdentity != null
            ? ValueKey('deep_thinking_$keyIdentity')
            : null;
        return DeepThinkingCard(
          key: key,
          isLoading: isLoading,
          thinkingText: thinkingText,
          stage: stage,
          startTime: startTime,
          endTime: endTime,
          taskId: taskID,
          onCancelTask: onCancelTask,
          isExecutable: isExecutable,
          isCollapsible: isCollapsible,
          autoCollapseOnComplete: thinkingAutoCollapseOnComplete,
          parentScrollController: parentScrollController,
          onParentScrollHandoff: onParentScrollHandoff,
          onStreamingTextLayoutChanged: onStreamingTextLayoutChanged,
          textScale: resolvedChatTextScale(appearanceConfig),
          textColor: visualProfile.primaryTextColor,
          showStatusAvatar:
              showThinkingAvatarOverride ??
              _shouldShowDeepThinkingAvatar(taskID: taskID, cardId: cardId),
        );
      case 'stage_hint':
        final hint = cardData['hint'] as String? ?? '';
        final startTime = cardData['startTime'] as int?;
        return StageHintCard(
          hint: hint,
          startTime: startTime != null
              ? DateTime.fromMillisecondsSinceEpoch(startTime)
              : null,
        );
      case 'openclaw_attachment':
        final attachment =
            cardData['attachment'] as Map<String, dynamic>? ?? {};
        return OpenClawAttachmentCard(attachment: attachment);
      case 'permission_section':
        return PermissionSectionCard(
          cardData: cardData,
          onRequestAuthorize: onRequestAuthorize,
        );
      case 'agent_tool_summary':
        return AgentToolSummaryCard(
          cardData: cardData,
          useAgentToolPresentation: useAgentToolPresentation,
          parentScrollController: parentScrollController,
          visualProfile: visualProfile,
        );
      case 'context_compaction_marker':
        return ContextCompactionMarkerCard(cardData: cardData);
      case 'history_omitted_card':
        return _HistoryOmittedCard(cardData: cardData);
      case 'artifact_card':
        final artifact = cardData['artifact'] as Map<String, dynamic>? ?? {};
        return ArtifactCard(artifact: artifact);
      case 'acp_audio':
        return AcpAudioCard(cardData: cardData);
      default:
        return _UnknownCard(type: type);
    }
  }

  static String _asString(dynamic value) => value?.toString() ?? '';

  static String? _asNullableString(dynamic value) {
    final normalized = value?.toString().trim() ?? '';
    return normalized.isEmpty ? null : normalized;
  }

  static int? _asInt(dynamic value) {
    if (value is int) return value;
    if (value is num) {
      final number = value.toDouble();
      if (number.isFinite) {
        return number.round();
      }
      return null;
    }
    final text = value?.toString().trim() ?? '';
    if (text.isEmpty) return null;
    final parsedInt = int.tryParse(text);
    if (parsedInt != null) return parsedInt;
    final parsedDouble = double.tryParse(text);
    if (parsedDouble != null && parsedDouble.isFinite) {
      return parsedDouble.round();
    }
    return null;
  }

  static bool _asBool(dynamic value, {bool fallback = false}) {
    if (value is bool) return value;
    final text = value?.toString().trim().toLowerCase() ?? '';
    if (text == 'true') return true;
    if (text == 'false') return false;
    return fallback;
  }

  static bool _shouldShowDeepThinkingAvatar({
    required String? taskID,
    required String? cardId,
  }) {
    if (taskID == null || cardId == null) {
      return true;
    }
    return cardId == '$taskID-thinking';
  }
}

class _HistoryOmittedCard extends StatelessWidget {
  const _HistoryOmittedCard({required this.cardData});

  final Map<String, dynamic> cardData;

  @override
  Widget build(BuildContext context) {
    final summary = (cardData['summary'] ?? '').toString().trim();
    final originalType = (cardData['originalType'] ?? '').toString().trim();
    final title = summary.isEmpty ? '历史过程卡片已折叠' : summary;
    final subtitle = originalType.isEmpty ? '历史过程' : originalType;
    final colorScheme = Theme.of(context).colorScheme;

    return Align(
      alignment: Alignment.centerLeft,
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxWidth: MediaQuery.of(context).size.width * 0.78,
        ),
        child: Container(
          margin: const EdgeInsets.only(top: 6, bottom: 2),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
          decoration: BoxDecoration(
            color: colorScheme.surfaceContainerHighest.withValues(alpha: 0.55),
            borderRadius: BorderRadius.circular(10),
            border: Border.all(
              color: colorScheme.outlineVariant.withValues(alpha: 0.6),
            ),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.history_toggle_off_rounded,
                size: 16,
                color: colorScheme.onSurfaceVariant,
              ),
              const SizedBox(width: 8),
              Flexible(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: colorScheme.onSurface,
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        height: 1.2,
                      ),
                    ),
                    Text(
                      subtitle,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: colorScheme.onSurfaceVariant,
                        fontSize: 10,
                        height: 1.2,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 未知类型卡片
///
/// 当卡片类型不被识别时显示的默认组件
class _UnknownCard extends StatelessWidget {
  final String type;

  const _UnknownCard({required this.type});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        // color: Colors.grey[100],
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.grey[300]!),
      ),
      child: Column(
        children: [
          Icon(Icons.help_outline, size: 40, color: Colors.grey[400]),
          const SizedBox(height: 8),
          Text('未知卡片类型：$type', style: TextStyle(color: Colors.grey[600])),
        ],
      ),
    );
  }
}
