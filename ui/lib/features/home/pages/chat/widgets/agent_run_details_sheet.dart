import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:ui/widgets/omni_sheet_motion.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/features/home/pages/chat/tool_activity_utils.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/history_payload_attachments.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_message_kinds.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/omni_circle_button.dart';
import 'package:ui/widgets/streaming_text.dart';

/// The existing turn's historical trace, in its original arrival order.
/// Actionable requests, the current plan, failures and the answer stay in chat.
List<ChatMessageModel> agentRunDetailMessages(AgentRunTimelineGroup group) {
  final answerId = group.visibleMessagesOldestFirst
      .where((message) => !isAgentTurnFailureMessage(message))
      .lastOrNull
      ?.id;
  return <ChatMessageModel>[
    for (final segment in group.segmentsOldestFirst)
      for (final message in segment.messages)
        if (!isAgentTurnFailureMessage(message) &&
            !isAgentPlanMessage(message) &&
            !isAgentRequestMessage(message) &&
            message.cardData?['type'] != 'permission_section' &&
            (segment.isProcess || message.id != answerId))
          message,
  ];
}

Future<void> showAgentRunDetailsSheet({
  required BuildContext context,
  required ValueListenable<AgentRunTimelineGroup> group,
  ValueChanged<ModalRoute<void>>? onRouteCreated,
}) {
  final navigator = Navigator.of(context, rootNavigator: true);
  final localizations = MaterialLocalizations.of(context);
  return navigator.push<void>(
    _AgentRunDetailsRoute(
      glass: LiquidGlassScope.of(context),
      capturedThemes: InheritedTheme.capture(
        from: context,
        to: navigator.context,
      ),
      barrierLabel: localizations.scrimLabel,
      barrierOnTapHint: localizations.scrimOnTapHint(
        localizations.bottomSheetLabel,
      ),
      builder: (sheetContext) {
        final route = ModalRoute.of<void>(sheetContext);
        if (route != null) onRouteCreated?.call(route);
        return AgentRunDetailsSheet(group: group);
      },
    ),
  );
}

/// Keep Flutter's sheet transition, drag layout and barrier semantics. Only
/// an outside tap bypasses the detail page's back veto; system back still
/// follows detail -> timeline -> conversation through the existing PopScope.
class _AgentRunDetailsRoute extends ModalBottomSheetRoute<void> {
  _AgentRunDetailsRoute({
    required this.glass,
    required super.builder,
    required super.capturedThemes,
    required super.barrierLabel,
    required super.barrierOnTapHint,
  }) : super(
         isScrollControlled: true,
         useSafeArea: true,
         requestFocus: false,
         enableDrag: false,
         backgroundColor: Colors.transparent,
         modalBarrierColor: Colors.black.withValues(alpha: 0.10),
       );

  final bool glass;

  void _dismissFromBarrier() {
    if (isCurrent) navigator?.pop();
  }

  @override
  Widget buildModalBarrier() {
    final barrier = super.buildModalBarrier();
    final dismissibleBarrier = switch (barrier) {
      AnimatedModalBarrier() => AnimatedModalBarrier(
        key: const ValueKey('agent-run-details-scrim'),
        color: barrier.color,
        dismissible: barrier.dismissible,
        onDismiss: _dismissFromBarrier,
        semanticsLabel: barrier.semanticsLabel,
        barrierSemanticsDismissible: barrier.barrierSemanticsDismissible,
        clipDetailsNotifier: barrier.clipDetailsNotifier,
        semanticsOnTapHint: barrier.semanticsOnTapHint,
      ),
      ModalBarrier() => ModalBarrier(
        key: const ValueKey('agent-run-details-scrim'),
        color: barrier.color,
        dismissible: barrier.dismissible,
        onDismiss: _dismissFromBarrier,
        semanticsLabel: barrier.semanticsLabel,
        barrierSemanticsDismissible: barrier.barrierSemanticsDismissible,
        clipDetailsNotifier: barrier.clipDetailsNotifier,
        semanticsOnTapHint: barrier.semanticsOnTapHint,
      ),
      _ => barrier,
    };
    return glass
        ? wrapOmniGlassModalBarrier(dismissibleBarrier)
        : dismissibleBarrier;
  }
}

/// Sheet navigation is presentation state only. Selecting an item never
/// changes the conversation, expands its rows or issues an ACP request.
class AgentRunDetailsSheet extends StatefulWidget {
  const AgentRunDetailsSheet({super.key, required this.group});

  final ValueListenable<AgentRunTimelineGroup> group;

  @override
  State<AgentRunDetailsSheet> createState() => _AgentRunDetailsSheetState();
}

class _AgentRunDetailsSheetState extends State<AgentRunDetailsSheet>
    with SingleTickerProviderStateMixin {
  static const _initialExtent = 0.53;
  static const _minimumExtent = 0.32;
  static const _maximumExtent = 0.92;
  final _extentController = DraggableScrollableController();
  late final _elastic = OmniSheetElasticController(this);
  ScrollController? _scrollController;
  String? _selectedMessageId;
  double _timelineOffset = 0;

  @override
  void dispose() {
    _elastic.dispose();
    _extentController.dispose();
    super.dispose();
  }

  void _selectMessage(ChatMessageModel message) {
    _timelineOffset = _scrollController?.hasClients == true
        ? _scrollController!.offset
        : 0;
    setState(() => _selectedMessageId = message.id);
    _restoreScrollOffset(0);
  }

  void _showTimeline() {
    setState(() => _selectedMessageId = null);
    _restoreScrollOffset(_timelineOffset);
  }

  void _restoreScrollOffset(double offset) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final controller = _scrollController;
      if (!mounted || controller == null || !controller.hasClients) return;
      controller.jumpTo(offset.clamp(0, controller.position.maxScrollExtent));
    });
  }

  void _resize(DragUpdateDetails details) {
    if (!_extentController.isAttached) return;
    final availableHeight = MediaQuery.sizeOf(context).height;
    final elasticEnabled =
        LiquidGlassScope.of(context) &&
        !MediaQuery.disableAnimationsOf(context);
    final limit =
        (availableHeight * (1 - _maximumExtent) -
                MediaQuery.paddingOf(context).top -
                14)
            .clamp(0.0, 100.0);
    var upward = -details.delta.dy;
    if (elasticEnabled && _elastic.pull > 0) {
      upward -= _elastic.dragBy(upward, limit: limit);
    }
    final before = _extentController.size;
    final after = (before + upward / availableHeight).clamp(
      _minimumExtent,
      _maximumExtent,
    );
    _extentController.jumpTo(after);
    final excess = upward - (after - before) * availableHeight;
    if (elasticEnabled && excess > 0) {
      _elastic.dragBy(excess, limit: limit);
    }
  }

  void _finishResize(DragEndDetails details) {
    _elastic.release(velocity: -(details.primaryVelocity ?? 0));
    if (!_extentController.isAttached) return;
    final extent = _extentController.size;
    final velocity = details.primaryVelocity ?? 0;
    if (extent < 0.40 || (velocity > 700 && extent <= _initialExtent)) {
      Navigator.of(context).pop();
      return;
    }
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<AgentRunTimelineGroup>(
      valueListenable: widget.group,
      builder: (context, group, _) {
        final messages = agentRunDetailMessages(group);
        final selected = messages
            .where((message) => message.id == _selectedMessageId)
            .firstOrNull;
        final isEnglish =
            Localizations.maybeLocaleOf(context)?.languageCode == 'en';
        final palette = context.omniPalette;
        return PopScope<void>(
          canPop: selected == null,
          onPopInvokedWithResult: (didPop, _) {
            if (!didPop && selected != null) _showTimeline();
          },
          child: DraggableScrollableSheet(
            controller: _extentController,
            initialChildSize: _initialExtent,
            minChildSize: _minimumExtent,
            maxChildSize: _maximumExtent,
            snap: false,
            expand: false,
            builder: (context, scrollController) {
              _scrollController = scrollController;
              final pane = OmniGlassSheet(
                key: ValueKey('agent-run-details-sheet-${group.taskId}'),
                backgroundColor: palette.pageBackground,
                borderRadius: const BorderRadius.vertical(
                  top: Radius.circular(20),
                ),
                child: SafeArea(
                  top: false,
                  child: Column(
                    children: [
                      GestureDetector(
                        key: const ValueKey('agent-run-details-drag-handle'),
                        behavior: HitTestBehavior.opaque,
                        onVerticalDragUpdate: _resize,
                        onVerticalDragEnd: _finishResize,
                        onVerticalDragCancel: _elastic.release,
                        child: Column(
                          children: [
                            SizedBox(
                              height: 28,
                              child: Center(
                                child: Container(
                                  width: 32,
                                  height: 4,
                                  decoration: BoxDecoration(
                                    color: palette.borderStrong,
                                    borderRadius: BorderRadius.circular(99),
                                  ),
                                ),
                              ),
                            ),
                            if (selected != null)
                              Padding(
                                padding: const EdgeInsets.fromLTRB(
                                  16,
                                  0,
                                  16,
                                  8,
                                ),
                                child: Row(
                                  children: [
                                    OmniCircleButton(
                                      key: const ValueKey(
                                        'agent-run-details-back',
                                      ),
                                      icon: LucideIcons.chevronLeft,
                                      size: 40,
                                      tapTargetSize:
                                          MaterialTapTargetSize.padded,
                                      tooltip: isEnglish
                                          ? 'Back to steps'
                                          : '返回过程列表',
                                      foregroundColor: palette.textSecondary,
                                      onPressed: _showTimeline,
                                    ),
                                    const SizedBox(width: 8),
                                    Expanded(
                                      child: Text(
                                        _messageTitle(selected, isEnglish),
                                        key: const ValueKey(
                                          'agent-run-details-title',
                                        ),
                                        maxLines: 2,
                                        overflow: TextOverflow.ellipsis,
                                        style: TextStyle(
                                          color: palette.textPrimary,
                                          fontSize: 17,
                                          height: 1.3,
                                          fontWeight: FontWeight.w600,
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                          ],
                        ),
                      ),
                      Expanded(
                        child: selected == null
                            ? ListView.builder(
                                key: ValueKey(
                                  'agent-run-details-timeline-${group.taskId}',
                                ),
                                controller: scrollController,
                                padding: const EdgeInsets.fromLTRB(
                                  22,
                                  8,
                                  22,
                                  24,
                                ),
                                itemCount: messages.length,
                                itemBuilder: (context, index) =>
                                    _AgentRunTimelineRow(
                                      message: messages[index],
                                      title: _messageTitle(
                                        messages[index],
                                        isEnglish,
                                      ),
                                      hasNext: index < messages.length - 1,
                                      onTap: () =>
                                          _selectMessage(messages[index]),
                                    ),
                              )
                            : isAgentToolSummaryMessage(selected)
                            ? AgentToolDetailBody(
                                key: ValueKey(
                                  'agent-run-details-item-${selected.id}',
                                ),
                                cardData: selected.cardData!,
                                scrollController: scrollController,
                              )
                            : SingleChildScrollView(
                                key: ValueKey(
                                  'agent-run-details-item-${selected.id}',
                                ),
                                controller: scrollController,
                                padding: const EdgeInsets.fromLTRB(
                                  24,
                                  8,
                                  24,
                                  24,
                                ),
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    StreamingText(
                                      fullText:
                                          selected.cardData?['thinkingContent']
                                              ?.toString() ??
                                          selected.text ??
                                          '',
                                      style: TextStyle(
                                        color: palette.textSecondary,
                                        fontSize: 15,
                                        height: 1.55,
                                      ),
                                      isFinal: true,
                                      selectable: true,
                                      enableMarkdown: true,
                                    ),
                                    HistoryPayloadAttachments(
                                      message: selected,
                                    ),
                                  ],
                                ),
                              ),
                      ),
                    ],
                  ),
                ),
              );
              return OmniSheetElasticSurface(controller: _elastic, child: pane);
            },
          ),
        );
      },
    );
  }
}

String _messageTitle(ChatMessageModel message, bool isEnglish) {
  final card = message.cardData;
  if (card?['type'] == 'deep_thinking') {
    return isEnglish ? 'Thinking' : '深度思考';
  }
  if (isAgentToolSummaryMessage(message)) {
    if (card?['toolType'] == 'terminal') {
      return isEnglish ? 'Run command' : '执行命令';
    }
    return resolveAgentToolTitle(card!);
  }
  return isEnglish ? 'Progress update' : '过程说明';
}

class _AgentRunTimelineRow extends StatelessWidget {
  const _AgentRunTimelineRow({
    required this.message,
    required this.title,
    required this.hasNext,
    required this.onTap,
  });

  final ChatMessageModel message;
  final String title;
  final bool hasNext;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final card = message.cardData;
    final status = card?['status']?.toString().toLowerCase();
    final isTool = isAgentToolSummaryMessage(message);
    final isThinking = card?['type'] == 'deep_thinking';
    final icon = !isTool
        ? (isThinking ? LucideIcons.brain : LucideIcons.messageSquare)
        : switch (status) {
            'success' || 'completed' => LucideIcons.circleCheck,
            'error' || 'failed' => LucideIcons.circleX,
            'cancelled' ||
            'canceled' ||
            'interrupted' => LucideIcons.circleMinus,
            _ => LucideIcons.clock,
          };
    // Item status comes from its owning tool call, never the outer turn.
    final color = !isTool
        ? palette.textPrimary
        : switch (status) {
            'success' || 'completed' =>
              context.isDarkTheme
                  ? const Color(0xFF7ACFA4)
                  : const Color(0xFF39996A),
            'error' || 'failed' => Theme.of(context).colorScheme.error,
            _ => palette.textSecondary,
          };
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Semantics(
          button: true,
          label: title,
          value: isTool ? resolveAgentToolStatusLabel(card!) : null,
          child: InkWell(
            key: ValueKey('agent-run-details-row-${message.id}'),
            borderRadius: BorderRadius.circular(10),
            onTap: onTap,
            child: ConstrainedBox(
              constraints: const BoxConstraints(minHeight: 48),
              child: Row(
                children: [
                  Icon(
                    icon,
                    key: ValueKey('agent-run-details-status-${message.id}'),
                    color: color,
                    size: 18,
                  ),
                  const SizedBox(width: 12),
                  Flexible(
                    child: Text(
                      title,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: palette.textPrimary,
                        fontSize: 15,
                        height: 1.4,
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Icon(
                    LucideIcons.chevronRight,
                    size: 18,
                    color: palette.textTertiary,
                  ),
                ],
              ),
            ),
          ),
        ),
        if (hasNext)
          Padding(
            padding: const EdgeInsets.only(left: 8),
            child: CustomPaint(
              size: const Size(2, 14),
              painter: _TimelineConnectorPainter(palette.borderStrong),
            ),
          ),
      ],
    );
  }
}

class _TimelineConnectorPainter extends CustomPainter {
  const _TimelineConnectorPainter(this.color);
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..strokeWidth = 1;
    for (double y = 0; y < size.height; y += 6) {
      canvas.drawLine(Offset(1, y), Offset(1, y + 3), paint);
    }
  }

  @override
  bool shouldRepaint(_TimelineConnectorPainter oldDelegate) =>
      oldDelegate.color != color;
}
