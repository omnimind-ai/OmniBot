import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/features/home/pages/chat/tool_activity_utils.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/features/home/pages/chat/widgets/agent_run_header.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/card_widget_factory.dart'
    show OnBeforeTaskExecute, OnRequestAuthorize;
import 'package:ui/features/home/pages/command_overlay/widgets/message_bubble.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_avatar_service.dart';
import 'package:ui/services/agent_message_kinds.dart';
import 'package:ui/services/app_background_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/agent_avatar.dart';

class AgentRunGroupMessage extends StatefulWidget {
  const AgentRunGroupMessage({
    super.key,
    required this.group,
    this.useAcpPresentation = false,
    required this.expanded,
    required this.onToggleExpanded,
    required this.onBeforeTaskExecute,
    this.onCancelTask,
    this.onRetryAgentMessage,
    this.onContinueAgentMessage,
    this.parentScrollController,
    this.onParentScrollHandoff,
    this.onRequestAuthorize,
    this.onStreamingTextLayoutChanged,
    this.visualProfile = AppBackgroundVisualProfile.defaultProfile,
    this.appearanceConfig = AppBackgroundConfig.defaults,
  });

  final AgentRunTimelineGroup group;
  final bool useAcpPresentation;
  final bool expanded;
  final VoidCallback onToggleExpanded;
  final OnBeforeTaskExecute onBeforeTaskExecute;
  final void Function(String taskId)? onCancelTask;
  final ValueChanged<ChatMessageModel>? onRetryAgentMessage;
  final ValueChanged<ChatMessageModel>? onContinueAgentMessage;
  final ScrollController? parentScrollController;
  final VoidCallback? onParentScrollHandoff;
  final OnRequestAuthorize? onRequestAuthorize;
  final VoidCallback? onStreamingTextLayoutChanged;
  final AppBackgroundVisualProfile visualProfile;
  final AppBackgroundConfig appearanceConfig;

  @override
  State<AgentRunGroupMessage> createState() => _AgentRunGroupMessageState();
}

class _AgentRunGroupMessageState extends State<AgentRunGroupMessage>
    with SingleTickerProviderStateMixin {
  static const Duration _kToggleDuration = Duration(milliseconds: 320);

  late final AnimationController _expandController;
  late final Animation<double> _sizeFactor;
  late final Animation<double> _opacity;
  late final Animation<double> _lift;
  bool _isNotifyingParentDuringAnimation = false;
  final Set<String> _expandedToolGroupKeys = <String>{};

  /// A running turn is always open; only a finished one honours the user's
  /// fold state. Auto-collapse-on-completion therefore needs no trigger of its
  /// own — the run simply stops forcing itself open when it ends.
  bool get _effectiveExpanded => widget.group.isRunning || widget.expanded;

  @override
  void initState() {
    super.initState();
    _expandController = AnimationController(
      vsync: this,
      duration: _kToggleDuration,
      reverseDuration: _kToggleDuration,
      value: _effectiveExpanded ? 1.0 : 0.0,
    );
    _sizeFactor = CurvedAnimation(
      parent: _expandController,
      curve: Curves.easeInOutCubic,
    );
    _opacity = CurvedAnimation(
      parent: _expandController,
      curve: const Interval(0.1, 1.0, curve: Curves.easeOutCubic),
      reverseCurve: const Interval(0.0, 0.92, curve: Curves.easeOutCubic),
    );
    _lift = Tween<double>(begin: -4, end: 0).animate(
      CurvedAnimation(
        parent: _expandController,
        curve: Curves.easeOutCubic,
        reverseCurve: Curves.easeInCubic,
      ),
    );
    _expandController.addListener(_handleAnimationTick);
    _expandController.addStatusListener(_handleAnimationStatusChanged);
    AgentAvatarService.ensureLoaded();
  }

  @override
  void didUpdateWidget(covariant AgentRunGroupMessage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.group.taskId != oldWidget.group.taskId) {
      _expandedToolGroupKeys.clear();
    }
    final wasExpanded = oldWidget.group.isRunning || oldWidget.expanded;
    if (_effectiveExpanded == wasExpanded) {
      return;
    }
    _isNotifyingParentDuringAnimation = true;
    if (_effectiveExpanded) {
      _expandController.forward();
    } else {
      _expandController.reverse();
    }
  }

  @override
  void dispose() {
    _expandController
      ..removeListener(_handleAnimationTick)
      ..removeStatusListener(_handleAnimationStatusChanged)
      ..dispose();
    super.dispose();
  }

  void _handleAnimationTick() {
    if (!mounted || !_isNotifyingParentDuringAnimation) {
      return;
    }
    widget.onStreamingTextLayoutChanged?.call();
  }

  void _handleAnimationStatusChanged(AnimationStatus status) {
    if (status != AnimationStatus.completed &&
        status != AnimationStatus.dismissed) {
      return;
    }
    final shouldNotifyParent = _isNotifyingParentDuringAnimation;
    _isNotifyingParentDuringAnimation = false;
    if (!mounted) {
      return;
    }
    setState(() {});
    if (shouldNotifyParent) {
      widget.onStreamingTextLayoutChanged?.call();
    }
  }

  @override
  Widget build(BuildContext context) {
    final primaryVisibleMessageId =
        widget.group.visibleMessagesOldestFirst.lastOrNull?.id;
    final hasFoldableHistory = widget.group.segmentsOldestFirst.any(
      (segment) =>
          segment.isProcess || segment.message.id != primaryVisibleMessageId,
    );
    // Resolved across the whole turn, not per fold. The first thinking card
    // only drops its avatar while a separate run header is actually visible.
    // Xiaowan has no running header, so its thinking card owns the avatar
    // until the finished summary replaces it.
    final firstThinkingMessageId = _firstThinkingMessageId(
      widget.group.processMessagesOldestFirst,
    );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Unconditional. A turn with no tool or thinking cards still gets a
        // header, which is what restores the agent avatar on a plain
        // question-and-answer turn.
        if (widget.useAcpPresentation)
          AgentRunHeader(
            key: ValueKey('agent-run-summary-${widget.group.taskId}'),
            taskId: widget.group.taskId,
            agentId: widget.group.agentId,
            status: widget.group.status,
            startedAt: widget.group.startedAt,
            finishedAt: widget.group.finishedAt,
            expanded: _effectiveExpanded,
            onToggleExpanded: widget.group.isRunning || !hasFoldableHistory
                ? null
                : widget.onToggleExpanded,
          )
        // The built-in assistant's header has no in-flight state, so while its
        // run is streaming there is simply no header — the process section is
        // force-expanded and reads exactly as it did before grouping.
        else if (hasFoldableHistory && !widget.group.isRunning)
          _LegacyAgentRunSummaryHeader(
            key: ValueKey('agent-run-summary-${widget.group.taskId}'),
            group: widget.group,
            taskId: widget.group.taskId,
            expanded: _effectiveExpanded,
            onTap: widget.onToggleExpanded,
          ),
        // Arrival order. Every process segment and every prose segment except
        // the final one shares the outer fold controller. Keeping the segments
        // separate preserves prose between tool batches when the run reopens.
        for (final segment in widget.group.segmentsOldestFirst)
          if (segment.isProcess)
            _buildAnimatedProcessSection(
              segment.messages,
              firstThinkingMessageId,
            )
          else if (isAgentPlanMessage(segment.message))
            // ACP plans are mutable state snapshots. Do not put them behind
            // the completed-run fold; the same card id is refreshed for each
            // plan_update and remains visible as the current plan.
            _buildPersistentPlanSection(segment.message)
          else if (segment.message.id != primaryVisibleMessageId)
            _buildAnimatedHistoricalTextSection(segment.message)
          else
            _buildVisibleMessageBubble(segment.message),
      ],
    );
  }

  Widget _buildVisibleMessageBubble(
    ChatMessageModel message, {
    bool forceTextFinal = false,
  }) {
    return MessageBubble(
      key: ValueKey('agent-run-${widget.group.taskId}-${message.id}'),
      message: message,
      // Only the newest prose entry can still be receiving chunks. Older
      // prose entries are historical even while the outer run continues; if
      // their source snapshot lacks an explicit terminal frame, rebuilding
      // them inside the fold must not replay the typewriter animation.
      forceTextFinal: forceTextFinal || !widget.group.isRunning,
      onBeforeTaskExecute: widget.onBeforeTaskExecute,
      onCancelTask: widget.onCancelTask,
      onRetryAgentMessage: () => widget.onRetryAgentMessage?.call(message),
      onContinueAgentMessage: () =>
          widget.onContinueAgentMessage?.call(message),
      enableThinkingCollapse: false,
      useAgentToolPresentation: widget.useAcpPresentation,
      parentScrollController: widget.parentScrollController,
      onParentScrollHandoff: widget.onParentScrollHandoff,
      onRequestAuthorize: widget.onRequestAuthorize,
      onStreamingTextLayoutChanged: widget.onStreamingTextLayoutChanged,
      visualProfile: widget.visualProfile,
      appearanceConfig: widget.appearanceConfig,
    );
  }

  Widget _buildAnimatedHistoricalTextSection(ChatMessageModel message) {
    return _buildAnimatedFoldSection(
      child: KeyedSubtree(
        key: ValueKey('agent-run-history-${widget.group.taskId}-${message.id}'),
        child: _buildVisibleMessageBubble(message, forceTextFinal: true),
      ),
    );
  }

  Widget _buildPersistentPlanSection(ChatMessageModel message) {
    return Padding(
      key: ValueKey(
        'agent-plan-persistent-${widget.group.taskId}-${message.id}',
      ),
      padding: const EdgeInsets.only(top: 2, bottom: 6),
      child: _buildVisibleMessageBubble(message, forceTextFinal: true),
    );
  }

  Widget _buildAnimatedProcessSection(
    List<ChatMessageModel> processMessages,
    String? firstThinkingMessageId,
  ) {
    if (processMessages.isEmpty) {
      return const SizedBox.shrink();
    }

    return _buildAnimatedFoldSection(
      padding: const EdgeInsets.only(top: 2, bottom: 6),
      child: Column(
        key: ValueKey(
          'agent-run-process-${widget.group.taskId}-'
          '${processMessages.first.id}',
        ),
        crossAxisAlignment: CrossAxisAlignment.start,
        children: _buildProcessWidgets(processMessages, firstThinkingMessageId),
      ),
    );
  }

  Widget _buildAnimatedFoldSection({
    required Widget child,
    EdgeInsets padding = EdgeInsets.zero,
  }) {
    final shouldShow =
        _effectiveExpanded ||
        _expandController.isAnimating ||
        _expandController.value > 0.001;
    if (!shouldShow) {
      return const SizedBox.shrink();
    }

    return AnimatedBuilder(
      animation: _expandController,
      child: child,
      builder: (context, child) {
        final sizeFactor = _sizeFactor.value.clamp(0.0, 1.0);
        final opacity = _opacity.value.clamp(0.0, 1.0);
        return Padding(
          padding: padding,
          child: ClipRect(
            child: Align(
              alignment: Alignment.topCenter,
              heightFactor: sizeFactor,
              child: Transform.translate(
                offset: Offset(0, _lift.value),
                child: IgnorePointer(
                  ignoring:
                      !_effectiveExpanded && !_expandController.isAnimating,
                  child: Opacity(opacity: opacity, child: child),
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  List<Widget> _buildProcessWidgets(
    List<ChatMessageModel> processMessages,
    String? firstThinkingMessageId,
  ) {
    final widgets = <Widget>[];
    var index = 0;
    while (index < processMessages.length) {
      final message = processMessages[index];
      if (isAgentToolSummaryMessage(message) && widget.useAcpPresentation) {
        final toolMessages = <ChatMessageModel>[message];
        var nextIndex = index + 1;
        while (nextIndex < processMessages.length &&
            isAgentToolSummaryMessage(processMessages[nextIndex])) {
          toolMessages.add(processMessages[nextIndex]);
          nextIndex += 1;
        }
        if (toolMessages.length > 1) {
          final groupKey = _toolGroupKey(widget.group.taskId, toolMessages);
          final expanded = _expandedToolGroupKeys.contains(groupKey);
          widgets.add(
            _AgentToolCallGroup(
              key: ValueKey('agent-tool-call-group-$groupKey'),
              groupKey: groupKey,
              messages: toolMessages,
              expanded: expanded,
              onToggle: () => _toggleToolGroup(groupKey),
              buildMessageBubble: _buildMessageBubble,
            ),
          );
        } else {
          widgets.add(
            _buildMessageBubble(
              toolMessages.single,
              firstThinkingMessageId: firstThinkingMessageId,
            ),
          );
        }
        index = nextIndex;
        continue;
      }

      widgets.add(
        _buildMessageBubble(
          message,
          firstThinkingMessageId: firstThinkingMessageId,
        ),
      );
      index += 1;
    }
    return widgets;
  }

  MessageBubble _buildMessageBubble(
    ChatMessageModel message, {
    String? firstThinkingMessageId,
  }) {
    final hideAvatar =
        firstThinkingMessageId != null &&
        message.id == firstThinkingMessageId &&
        (widget.useAcpPresentation || !widget.group.isRunning);
    return MessageBubble(
      key: ValueKey('agent-run-${widget.group.taskId}-${message.id}'),
      message: message,
      forceTextFinal: !widget.group.isRunning,
      onBeforeTaskExecute: widget.onBeforeTaskExecute,
      onCancelTask: widget.onCancelTask,
      onRetryAgentMessage: () => widget.onRetryAgentMessage?.call(message),
      onContinueAgentMessage: () =>
          widget.onContinueAgentMessage?.call(message),
      enableThinkingCollapse: true,
      // While the run itself is finishing, let the outer 320 ms fold own the
      // transition. Running the thinking card's 170 ms height/opacity collapse
      // at the same time multiplies both animations and looks like a flash.
      // A manually re-opened finished run still initializes each completed
      // thinking card in its compact collapsed state. Users can expand only
      // the reasoning segment they want to inspect.
      thinkingAutoCollapseOnComplete: widget.group.isRunning || widget.expanded,
      useAgentToolPresentation: widget.useAcpPresentation,
      showThinkingAvatarOverride: hideAvatar ? false : null,
      parentScrollController: widget.parentScrollController,
      onParentScrollHandoff: widget.onParentScrollHandoff,
      onRequestAuthorize: widget.onRequestAuthorize,
      onStreamingTextLayoutChanged: widget.onStreamingTextLayoutChanged,
      visualProfile: widget.visualProfile,
      appearanceConfig: widget.appearanceConfig,
    );
  }

  void _toggleToolGroup(String groupKey) {
    setState(() {
      if (!_expandedToolGroupKeys.add(groupKey)) {
        _expandedToolGroupKeys.remove(groupKey);
      }
    });
    widget.onStreamingTextLayoutChanged?.call();
  }

  String? _firstThinkingMessageId(List<ChatMessageModel> processMessages) {
    for (final message in processMessages) {
      if ((message.cardData?['type'] ?? '').toString() == 'deep_thinking') {
        return message.id;
      }
    }
    return null;
  }
}

String _toolGroupKey(String taskId, List<ChatMessageModel> messages) {
  return '$taskId-${messages.map((message) => message.id).join('-')}';
}

// NOTE: `_toolCountSummary` was the previous source of the
// "已运行 X 条命令 · 已读取 Y 个文件 …" header label. The user explicitly
// asked for both the collapsed AND expanded agent-run headers (and the
// inner tool-group capsule) to read the generic "已处理" instead, so this
// helper now has no callers and was deleted. The per-message-type
// counters live on individually rendered tool cards if anyone needs them
// later.

class _AgentToolCallGroup extends StatelessWidget {
  const _AgentToolCallGroup({
    super.key,
    required this.groupKey,
    required this.messages,
    required this.expanded,
    required this.onToggle,
    required this.buildMessageBubble,
  });

  final String groupKey;
  final List<ChatMessageModel> messages;
  final bool expanded;
  final VoidCallback onToggle;
  final MessageBubble Function(
    ChatMessageModel message, {
    String? firstThinkingMessageId,
  })
  buildMessageBubble;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final primaryCard = _primaryCardData(messages);
    final status = (primaryCard['status'] ?? 'running').toString();
    final toolType = (primaryCard['toolType'] ?? '').toString();
    final mutedColor = palette.textSecondary.withValues(
      alpha: context.isDarkTheme ? 0.78 : 0.68,
    );
    final titleColor = palette.textSecondary.withValues(
      alpha: context.isDarkTheme ? 0.94 : 0.88,
    );
    final overlayColor = palette.accentPrimary.withValues(
      alpha: context.isDarkTheme ? 0.10 : 0.06,
    );
    final isEnglish =
        Localizations.maybeLocaleOf(context)?.languageCode == 'en';
    final title = _toolGroupTitle(messages, isEnglish: isEnglish);

    return Align(
      alignment: Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.only(top: 6, bottom: 4),
        constraints: BoxConstraints(
          maxWidth: MediaQuery.sizeOf(context).width * 0.90,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            Tooltip(
              message: _toolGroupTooltip(messages),
              child: Material(
                color: Colors.transparent,
                borderRadius: BorderRadius.circular(8),
                clipBehavior: Clip.antiAlias,
                child: InkWell(
                  key: ValueKey('agent-tool-call-group-toggle-$groupKey'),
                  onTap: onToggle,
                  splashColor: overlayColor,
                  highlightColor: overlayColor,
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
                          child: Text(
                            title,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: titleColor,
                              fontSize: 12,
                              fontWeight: FontWeight.w500,
                              letterSpacing: 0,
                              height: 1.18,
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Text(
                          '${messages.length}',
                          style: TextStyle(
                            color: mutedColor,
                            fontSize: 10.5,
                            fontWeight: FontWeight.w700,
                            height: 1,
                          ),
                        ),
                        const SizedBox(width: 4),
                        AnimatedRotation(
                          turns: expanded ? 0.5 : 0,
                          duration: const Duration(milliseconds: 220),
                          curve: Curves.easeOutCubic,
                          child: Icon(
                            LucideIcons.chevronDown,
                            size: 18,
                            color: mutedColor,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
            AnimatedSize(
              duration: const Duration(milliseconds: 260),
              curve: Curves.easeOutCubic,
              alignment: Alignment.topLeft,
              child: expanded
                  ? Padding(
                      padding: const EdgeInsets.only(top: 2),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: messages
                            .map((message) => buildMessageBubble(message))
                            .toList(growable: false),
                      ),
                    )
                  : const SizedBox.shrink(),
            ),
          ],
        ),
      ),
    );
  }

  Map<String, dynamic> _primaryCardData(List<ChatMessageModel> messages) {
    for (final message in messages) {
      final cardData = message.cardData;
      if ((cardData?['status'] ?? '').toString() == 'running') {
        return cardData!;
      }
    }
    return messages.first.cardData ?? const <String, dynamic>{};
  }

  String _toolGroupTitle(
    List<ChatMessageModel> messages, {
    required bool isEnglish,
  }) {
    // The inner tool-group capsule (multiple consecutive tool cards
    // collapsed into one chevron) was previously surfacing the per-tool
    // count summary too ("已运行 1 条命令 · 已读取 1 个文件"). The user
    // explicitly asked for the expanded run UI to match the collapsed
    // header, so this capsule also shows the generic "已处理" — its own
    // count text was the only place left after fixing the outer header.
    return isEnglish ? 'Processed' : '已处理';
  }

  String _toolGroupTooltip(List<ChatMessageModel> messages) {
    return messages
        .map((message) => message.cardData)
        .whereType<Map<String, dynamic>>()
        .map(resolveAgentToolTitle)
        .where((title) => title.trim().isNotEmpty)
        .join('\n');
  }
}

/// Header for the built-in assistant's run groups (non-ACP presentation).
///
/// ACP turns go through [AgentRunHeader] instead, which also covers the
/// in-flight state.
class _LegacyAgentRunSummaryHeader extends StatelessWidget {
  const _LegacyAgentRunSummaryHeader({
    super.key,
    required this.group,
    required this.taskId,
    required this.expanded,
    required this.onTap,
  });

  final AgentRunTimelineGroup group;
  final String taskId;
  final bool expanded;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final isEnglish =
        Localizations.maybeLocaleOf(context)?.languageCode == 'en';
    final palette = context.omniPalette;
    // Both collapsed AND expanded show the same "已处理 <elapsed>" label.
    // The per-tool count summary was deliberately retired — the user wants
    // the header noise-free in both states. The elapsed-time suffix is
    // computed from the message timestamps inside this group (first
    // candidate message → last candidate message). If we can't derive
    // a duration (single instant), we just show "已处理".
    final baseLabel = isEnglish ? 'Processed' : '已处理';
    final elapsedLabel = _agentRunElapsedLabel(group);
    final label = elapsedLabel.isEmpty
        ? baseLabel
        : '$baseLabel  $elapsedLabel';
    final labelColor = expanded ? palette.textSecondary : palette.textTertiary;

    return Padding(
      padding: const EdgeInsets.only(top: 8, bottom: 4),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(10),
          splashFactory: NoSplash.splashFactory,
          overlayColor: const WidgetStatePropertyAll(Colors.transparent),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(2, 4, 2, 4),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                ValueListenableBuilder<AgentAvatarState>(
                  valueListenable: AgentAvatarService.avatarStateNotifier,
                  builder: (context, state, _) {
                    return AgentAvatarCircle(
                      key: ValueKey('agent-run-avatar-$taskId'),
                      state: state,
                      size: 30,
                      showBorder: false,
                    );
                  },
                ),
                const SizedBox(width: 8),
                ConstrainedBox(
                  constraints: BoxConstraints(
                    maxWidth: MediaQuery.sizeOf(context).width * 0.6,
                  ),
                  child: Text(
                    label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w600,
                      letterSpacing: 0,
                      color: labelColor,
                      fontFamily: 'PingFang SC',
                    ),
                  ),
                ),
                const SizedBox(width: 2),
                AnimatedRotation(
                  turns: expanded ? 0 : -0.25,
                  duration: _AgentRunGroupMessageState._kToggleDuration,
                  curve: Curves.easeInOutCubicEmphasized,
                  child: Icon(
                    LucideIcons.chevronDown,
                    key: ValueKey('agent-run-summary-chevron-$taskId'),
                    size: 18,
                    color: labelColor,
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

/// Returns a short human-readable duration string ("47s", "1m 23s",
/// "1h 5m") covering the agent run from its earliest candidate message to
/// its latest. Used by the non-ACP header only; [AgentRunHeader] derives its
/// own elapsed time from the run's start and finish timestamps.
String _agentRunElapsedLabel(AgentRunTimelineGroup group) {
  int? earliestMs;
  int? latestMs;
  int? earliestContentMs;
  void visit(Iterable<ChatMessageModel> messages) {
    for (final message in messages) {
      final ms = message.createAt.millisecondsSinceEpoch;
      if (ms <= 0) {
        continue;
      }
      if (earliestMs == null || ms < earliestMs!) {
        earliestMs = ms;
      }
      if (latestMs == null || ms > latestMs!) {
        latestMs = ms;
      }
      // A persisted tool card can carry an old createdAt when a provider
      // reuses its call id on a later turn. Text and reasoning entries are
      // turn-owned anchors; prefer them as the start boundary so one stale
      // tool timestamp cannot inflate the visible Xiaowan duration.
      if (_cardTypeForElapsed(message) != 'agent_tool_summary' &&
          (earliestContentMs == null || ms < earliestContentMs!)) {
        earliestContentMs = ms;
      }
    }
  }

  visit(group.allMessagesOldestFirst);
  if (earliestMs == null || latestMs == null || latestMs! <= earliestMs!) {
    return '';
  }
  earliestMs = earliestContentMs ?? earliestMs;
  if (latestMs! <= earliestMs!) {
    return '';
  }
  final elapsedSec = ((latestMs! - earliestMs!) / 1000).round();
  if (elapsedSec < 1) {
    return '';
  }
  if (elapsedSec < 60) {
    return '${elapsedSec}s';
  }
  final minutes = elapsedSec ~/ 60;
  final remainingSeconds = elapsedSec % 60;
  if (minutes < 60) {
    if (remainingSeconds == 0) {
      return '${minutes}m';
    }
    return '${minutes}m ${remainingSeconds}s';
  }
  final hours = minutes ~/ 60;
  final remainingMinutes = minutes % 60;
  if (remainingMinutes == 0) {
    return '${hours}h';
  }
  return '${hours}h ${remainingMinutes}m';
}

String _cardTypeForElapsed(ChatMessageModel message) {
  return (message.cardData?['type'] ?? '').toString().trim();
}
