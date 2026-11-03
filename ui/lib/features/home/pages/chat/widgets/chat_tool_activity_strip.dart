import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:ui/widgets/omni_segmented_slider.dart';
import 'package:ui/features/home/pages/chat/tool_activity_utils.dart'
    hide buildAgentToolTranscript;
import 'package:ui/features/home/pages/command_overlay/services/tool_card_detail_gesture_gate.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/terminal_output_utils.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_browser_session_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';

const ValueKey<String> kChatToolActivityBarKey = ValueKey<String>(
  'chat-tool-activity-bar',
);
const ValueKey<String> kChatToolActivityPanelKey = ValueKey<String>(
  'chat-tool-activity-panel',
);
const ValueKey<String> kChatToolActivityPreviewKey = ValueKey<String>(
  'chat-tool-activity-preview',
);
const ValueKey<String> kChatToolActivityToggleKey = ValueKey<String>(
  'chat-tool-activity-toggle',
);
const ValueKey<String> kChatToolActivityStopKey = ValueKey<String>(
  'chat-tool-activity-stop',
);
const String kChatCommandToggleKeyPrefix = 'chat-command-toggle';
const String kChatCommandEffortSliderKeyPrefix = 'chat-command-effort-slider';

const double _kToolActivityRowHeight = 32;
const double _kToolActivitySurfaceRadius = 18;
const double _kToolActivityPreviewWidth = 94;
const double _kToolActivityPreviewHeight = 54;
const double _kToolActivityPreviewOverlap = 30;
const int _kBrowserActivityPreviewMaxWidth = 420;
const double _kToolActivitySurfaceHorizontalInset = 20;
const double _kToolActivityDrawerMaxHeight = 264;
const double _kToolActivityTypeSlotWidth = 34;
const double _kToolActivityStatusSlotWidth = 42;
const double _kToolActivityTrailingSlotWidth = 24;
const double _kCommandEffortSliderWidth = 156;
const double _kToolActivityAttachedBorderReveal = 1.5;
const BorderRadius _kToolActivitySurfaceBorderRadius = BorderRadius.only(
  topLeft: Radius.circular(_kToolActivitySurfaceRadius),
  topRight: Radius.circular(_kToolActivitySurfaceRadius),
);
const BorderRadius _kToolActivityPreviewBorderRadius = BorderRadius.all(
  Radius.circular(18),
);

class ChatToolActivityStrip extends StatefulWidget {
  const ChatToolActivityStrip({
    super.key,
    required this.messages,
    this.anchorRect,
    this.onOccupiedHeightChanged,
    this.expanded,
    this.onExpandedChanged,
    this.suppressSurfaceShadow = false,
    this.onStopToolCall,
    this.runningOnly = false,
    this.showPreviewThumbnail = true,
    this.openActiveCardOnTap = false,
  });

  final List<ChatMessageModel> messages;
  final Rect? anchorRect;
  final ValueChanged<double>? onOccupiedHeightChanged;
  final bool? expanded;
  final ValueChanged<bool>? onExpandedChanged;
  final bool suppressSurfaceShadow;
  final Future<bool> Function(String taskId, String cardId)? onStopToolCall;
  final bool runningOnly;
  final bool showPreviewThumbnail;
  final bool openActiveCardOnTap;

  @override
  State<ChatToolActivityStrip> createState() => _ChatToolActivityStripState();
}

class _ChatToolActivityStripState extends State<ChatToolActivityStrip> {
  bool _expanded = false;
  double? _lastReportedOccupiedHeight;
  final Set<int> _heldPointerIds = <int>{};
  String? _pendingStopCardId;

  bool get _resolvedExpanded => widget.expanded ?? _expanded;

  List<Map<String, dynamic>> _resolvedCards() {
    return widget.runningOnly
        ? extractRunningAgentToolCards(widget.messages)
        : extractAgentToolCards(widget.messages);
  }

  @override
  Widget build(BuildContext context) {
    final cards = _resolvedCards();
    final activeCard = resolveActiveAgentToolCard(cards);
    if (activeCard == null) {
      _scheduleExpandedResetIfNeeded();
      _reportOccupiedHeight(0);
      return const SizedBox.shrink();
    }

    final activeCardId = _cardIdentity(activeCard);
    _schedulePendingStopResetIfNeeded(activeCardId: activeCardId);
    final historyCards = cards
        .where((card) => _cardIdentity(card) != activeCardId)
        .toList(growable: false);
    final canExpand = historyCards.isNotEmpty;
    final isExpanded = _resolvedExpanded && canExpand;
    final previewVisible = widget.showPreviewThumbnail && !isExpanded;
    final showBrowserPreview =
        previewVisible && _isBrowserActivityCard(activeCard);
    final activeTranscript = previewVisible && !showBrowserPreview
        ? buildAgentToolTranscript(activeCard)
        : null;
    if (!canExpand && _resolvedExpanded) {
      _scheduleExpandedResetIfNeeded();
    }
    final historyHeight = isExpanded
        ? _resolveHistoryHeight(historyCards)
        : 0.0;
    final dividerHeight = isExpanded ? 1.0 : 0.0;
    final surfaceHeight =
        _kToolActivityRowHeight + historyHeight + dividerHeight;
    final collapsedOccupiedHeight =
        _kToolActivityRowHeight +
        (widget.showPreviewThumbnail
            ? _kToolActivityPreviewHeight - _kToolActivityPreviewOverlap
            : 0.0);
    final totalHeight =
        surfaceHeight +
        (previewVisible
            ? _kToolActivityPreviewHeight - _kToolActivityPreviewOverlap
            : 0.0);
    final collapsedLeadingInset = math.max(
      0.0,
      _kToolActivityPreviewWidth - _kToolActivitySurfaceHorizontalInset + 2,
    );
    _reportOccupiedHeight(collapsedOccupiedHeight);

    return AnimatedSize(
      duration: const Duration(milliseconds: 280),
      curve: Curves.easeOutQuart,
      alignment: Alignment.bottomLeft,
      child: SizedBox(
        width: widget.anchorRect?.width ?? double.infinity,
        height: totalHeight,
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            Positioned(
              left: _kToolActivitySurfaceHorizontalInset,
              right: _kToolActivitySurfaceHorizontalInset,
              bottom: 0,
              child: _ActivityDrawerSurface(
                activeCard: activeCard,
                historyCards: historyCards,
                historyHeight: historyHeight,
                expanded: isExpanded,
                canExpand: canExpand,
                suppressShadow: widget.suppressSurfaceShadow,
                leadingInset: previewVisible ? collapsedLeadingInset : 0,
                showPreviewCutout: previewVisible,
                openActiveCardOnTap: widget.openActiveCardOnTap,
                onToggle: () => _handleExpandedChanged(!isExpanded),
                onStopToolCall: widget.onStopToolCall == null
                    ? null
                    : () => _handleStopToolCall(activeCard),
                isStopPending: _pendingStopCardId == activeCardId,
                onOpenCard: (cardData) =>
                    _openCardDetailSheet(context, cardData: cardData),
                onHistoryPointerDown: _handleHistoryPointerDown,
                onHistoryPointerEnd: _handleHistoryPointerEnd,
              ),
            ),
            if (widget.showPreviewThumbnail)
              Positioned(
                left: 0,
                top: 0,
                child: AnimatedSwitcher(
                  duration: const Duration(milliseconds: 220),
                  switchInCurve: Curves.easeOutCubic,
                  switchOutCurve: Curves.easeInCubic,
                  transitionBuilder: (child, animation) {
                    final offset = Tween<Offset>(
                      begin: const Offset(-0.05, 0.12),
                      end: Offset.zero,
                    ).animate(animation);
                    return FadeTransition(
                      opacity: animation,
                      child: SlideTransition(position: offset, child: child),
                    );
                  },
                  child: previewVisible
                      ? showBrowserPreview
                            ? _BrowserThumbnail(
                                key: kChatToolActivityPreviewKey,
                                cardData: activeCard,
                                onTap: () => _openCardDetailSheet(
                                  context,
                                  cardData: activeCard,
                                ),
                              )
                            : activeTranscript != null
                            ? _TerminalThumbnail(
                                key: kChatToolActivityPreviewKey,
                                transcript: activeTranscript,
                                onTap: () => _openCardDetailSheet(
                                  context,
                                  cardData: activeCard,
                                ),
                              )
                            : const SizedBox.shrink(
                                key: ValueKey('hidden-preview'),
                              )
                      : const SizedBox.shrink(key: ValueKey('hidden-preview')),
                ),
              ),
          ],
        ),
      ),
    );
  }

  @override
  void didUpdateWidget(covariant ChatToolActivityStrip oldWidget) {
    super.didUpdateWidget(oldWidget);
    _schedulePendingStopResetIfNeeded();
    if (widget.messages.isEmpty && _lastReportedOccupiedHeight != 0) {
      _reportOccupiedHeight(0);
    }
    if (oldWidget.expanded == true && widget.expanded != true) {
      _releaseHeldPointers();
    }
  }

  @override
  void dispose() {
    _releaseHeldPointers();
    super.dispose();
  }

  String _cardIdentity(Map<String, dynamic> cardData) {
    final explicit = (cardData['cardId'] ?? '').toString().trim();
    if (explicit.isNotEmpty) {
      return explicit;
    }
    return [
      (cardData['taskId'] ?? '').toString(),
      (cardData['toolName'] ?? '').toString(),
      (cardData['toolTitle'] ?? '').toString(),
      (cardData['status'] ?? '').toString(),
    ].join('|');
  }

  double _resolveHistoryHeight(List<Map<String, dynamic>> cards) {
    final visibleCount = cards.length.clamp(1, 5);
    final estimated = visibleCount * _kToolActivityRowHeight;
    return math.min(_kToolActivityDrawerMaxHeight, estimated.toDouble());
  }

  void _handleExpandedChanged(bool expanded) {
    if (_resolvedExpanded == expanded && widget.expanded != null) {
      return;
    }
    if (widget.expanded == null) {
      if (_expanded == expanded) {
        return;
      }
      setState(() {
        _expanded = expanded;
      });
    }
    if (!expanded) {
      _releaseHeldPointers();
    }
    widget.onExpandedChanged?.call(expanded);
  }

  void _scheduleExpandedResetIfNeeded() {
    if (!_resolvedExpanded) {
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_resolvedExpanded) {
        return;
      }
      _handleExpandedChanged(false);
    });
  }

  void _schedulePendingStopResetIfNeeded({String? activeCardId}) {
    final pendingCardId = _pendingStopCardId;
    if (pendingCardId == null) {
      return;
    }
    final cards = _resolvedCards();
    Map<String, dynamic>? pendingCard;
    for (final card in cards) {
      if (_cardIdentity(card) == pendingCardId) {
        pendingCard = card;
        break;
      }
    }
    final resolvedActiveCard = resolveActiveAgentToolCard(cards);
    final normalizedActiveCardId =
        activeCardId ??
        (resolvedActiveCard == null ? null : _cardIdentity(resolvedActiveCard));
    final stillPending =
        pendingCard != null &&
        (pendingCard['status'] ?? '').toString() == 'running' &&
        normalizedActiveCardId == pendingCardId;
    if (stillPending) {
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || _pendingStopCardId != pendingCardId) {
        return;
      }
      setState(() {
        _pendingStopCardId = null;
      });
    });
  }

  Future<void> _handleStopToolCall(Map<String, dynamic> cardData) async {
    final onStopToolCall = widget.onStopToolCall;
    if (onStopToolCall == null) {
      return;
    }
    final taskId = (cardData['taskId'] ?? '').toString().trim();
    final cardId = _cardIdentity(cardData);
    if (taskId.isEmpty || cardId.isEmpty || _pendingStopCardId == cardId) {
      return;
    }
    setState(() {
      _pendingStopCardId = cardId;
    });

    var success = false;
    try {
      success = await onStopToolCall(taskId, cardId);
    } catch (_) {
      success = false;
    }
    if (!mounted) {
      return;
    }
    if (success) {
      return;
    }
    setState(() {
      if (_pendingStopCardId == cardId) {
        _pendingStopCardId = null;
      }
    });
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(
      SnackBar(content: Text(LegacyTextLocalizer.localize('停止工具调用失败，请稍后重试'))),
    );
  }

  void _handleHistoryPointerDown(int pointer) {
    if (_heldPointerIds.add(pointer)) {
      ToolCardDetailGestureGate.holdPointer(pointer);
    }
  }

  void _handleHistoryPointerEnd(int pointer) {
    if (_heldPointerIds.remove(pointer)) {
      ToolCardDetailGestureGate.releasePointer(pointer);
    }
  }

  void _releaseHeldPointers() {
    if (_heldPointerIds.isEmpty) {
      return;
    }
    for (final pointer in _heldPointerIds.toList(growable: false)) {
      ToolCardDetailGestureGate.releasePointer(pointer);
    }
    _heldPointerIds.clear();
  }

  void _reportOccupiedHeight(double height) {
    if (widget.onOccupiedHeightChanged == null) {
      return;
    }
    final normalized = height.isFinite ? height : 0.0;
    if (_lastReportedOccupiedHeight != null &&
        (_lastReportedOccupiedHeight! - normalized).abs() < 0.5) {
      return;
    }
    _lastReportedOccupiedHeight = normalized;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      widget.onOccupiedHeightChanged?.call(normalized);
    });
  }

  Future<void> _openCardDetailSheet(
    BuildContext context, {
    required Map<String, dynamic> cardData,
  }) {
    return showAgentToolDetailSheet(context, cardData: cardData);
  }
}

class ChatCommandActivityStrip extends StatefulWidget {
  const ChatCommandActivityStrip({
    super.key,
    required this.commands,
    required this.onSelectCommand,
    this.anchorRect,
    this.onOccupiedHeightChanged,
    this.suppressSurfaceShadow = false,
  });

  final List<Map<String, dynamic>> commands;
  final ValueChanged<Map<String, dynamic>> onSelectCommand;
  final Rect? anchorRect;
  final ValueChanged<double>? onOccupiedHeightChanged;
  final bool suppressSurfaceShadow;

  @override
  State<ChatCommandActivityStrip> createState() =>
      _ChatCommandActivityStripState();
}

class _ChatCommandActivityStripState extends State<ChatCommandActivityStrip> {
  double? _lastReportedOccupiedHeight;
  final Set<int> _heldPointerIds = <int>{};

  @override
  Widget build(BuildContext context) {
    final commands = widget.commands;
    if (commands.isEmpty) {
      _reportOccupiedHeight(0);
      return const SizedBox.shrink();
    }

    final activeCard = commands.first;
    final historyCards = commands.length > 1
        ? commands.sublist(1)
        : const <Map<String, dynamic>>[];
    final showHistory = historyCards.isNotEmpty;
    final historyHeight = showHistory
        ? _resolveHistoryHeight(historyCards)
        : 0.0;
    final dividerHeight = showHistory ? 1.0 : 0.0;
    final surfaceHeight =
        _kToolActivityRowHeight + historyHeight + dividerHeight;
    _reportOccupiedHeight(surfaceHeight);

    return AnimatedSize(
      duration: const Duration(milliseconds: 280),
      curve: Curves.easeOutQuart,
      alignment: Alignment.bottomLeft,
      child: SizedBox(
        width: widget.anchorRect?.width ?? double.infinity,
        height: surfaceHeight,
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            Positioned(
              left: _kToolActivitySurfaceHorizontalInset,
              right: _kToolActivitySurfaceHorizontalInset,
              bottom: 0,
              child: _CommandDrawerSurface(
                activeCard: activeCard,
                historyCards: historyCards,
                historyHeight: historyHeight,
                expanded: showHistory,
                canExpand: false,
                suppressShadow: widget.suppressSurfaceShadow,
                leadingInset: 0,
                onToggle: () {},
                onSelectCommand: widget.onSelectCommand,
                onHistoryPointerDown: _handleHistoryPointerDown,
                onHistoryPointerEnd: _handleHistoryPointerEnd,
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  void didUpdateWidget(covariant ChatCommandActivityStrip oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.commands.isEmpty && _lastReportedOccupiedHeight != 0) {
      _reportOccupiedHeight(0);
    }
  }

  @override
  void dispose() {
    _releaseHeldPointers();
    super.dispose();
  }

  double _resolveHistoryHeight(List<Map<String, dynamic>> cards) {
    final visibleCount = cards.length.clamp(1, 5);
    final estimated = visibleCount * _kToolActivityRowHeight;
    return math.min(_kToolActivityDrawerMaxHeight, estimated.toDouble());
  }

  void _handleHistoryPointerDown(int pointer) {
    if (_heldPointerIds.add(pointer)) {
      ToolCardDetailGestureGate.holdPointer(pointer);
    }
  }

  void _handleHistoryPointerEnd(int pointer) {
    if (_heldPointerIds.remove(pointer)) {
      ToolCardDetailGestureGate.releasePointer(pointer);
    }
  }

  void _releaseHeldPointers() {
    if (_heldPointerIds.isEmpty) {
      return;
    }
    for (final pointer in _heldPointerIds.toList(growable: false)) {
      ToolCardDetailGestureGate.releasePointer(pointer);
    }
    _heldPointerIds.clear();
  }

  void _reportOccupiedHeight(double height) {
    if (widget.onOccupiedHeightChanged == null) {
      return;
    }
    final normalized = height.isFinite ? height : 0.0;
    if (_lastReportedOccupiedHeight != null &&
        (_lastReportedOccupiedHeight! - normalized).abs() < 0.5) {
      return;
    }
    _lastReportedOccupiedHeight = normalized;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      widget.onOccupiedHeightChanged?.call(normalized);
    });
  }
}

class _CommandDrawerSurface extends StatelessWidget {
  const _CommandDrawerSurface({
    required this.activeCard,
    required this.historyCards,
    required this.historyHeight,
    required this.expanded,
    required this.canExpand,
    required this.suppressShadow,
    required this.leadingInset,
    required this.onToggle,
    required this.onSelectCommand,
    required this.onHistoryPointerDown,
    required this.onHistoryPointerEnd,
  });

  final Map<String, dynamic> activeCard;
  final List<Map<String, dynamic>> historyCards;
  final double historyHeight;
  final bool expanded;
  final bool canExpand;
  final bool suppressShadow;
  final double leadingInset;
  final VoidCallback onToggle;
  final ValueChanged<Map<String, dynamic>> onSelectCommand;
  final ValueChanged<int> onHistoryPointerDown;
  final ValueChanged<int> onHistoryPointerEnd;

  @override
  Widget build(BuildContext context) {
    final dividerColor = context.isDarkTheme
        ? context.omniPalette.borderSubtle.withValues(alpha: 0.52)
        : const Color(0x140F2034);
    return _ActivitySurface(
      key: const ValueKey('chat-command-activity-bar'),
      expanded: expanded,
      suppressShadow: suppressShadow,
      showPreviewCutout: false,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          AnimatedCrossFade(
            duration: const Duration(milliseconds: 240),
            firstCurve: Curves.easeInCubic,
            secondCurve: Curves.easeOutCubic,
            sizeCurve: Curves.easeOutQuart,
            alignment: Alignment.bottomCenter,
            crossFadeState: expanded
                ? CrossFadeState.showSecond
                : CrossFadeState.showFirst,
            firstChild: const SizedBox.shrink(
              key: ValueKey('collapsed-command-panel'),
            ),
            secondChild: SizedBox(
              height: historyHeight,
              child: _HistoryDrawer(
                cards: historyCards,
                onOpenCard: onSelectCommand,
                onPointerDown: onHistoryPointerDown,
                onPointerEnd: onHistoryPointerEnd,
              ),
            ),
          ),
          AnimatedContainer(
            duration: const Duration(milliseconds: 220),
            curve: Curves.easeOutCubic,
            height: expanded ? 1 : 0,
            margin: const EdgeInsets.only(left: 18, right: 10),
            color: dividerColor,
          ),
          ToolActivityRow(
            card: activeCard,
            leadingInset: leadingInset,
            onTap: () => onSelectCommand(activeCard),
            trailing:
                _buildCommandTrailingControl(
                  activeCard,
                  onTap: () => onSelectCommand(activeCard),
                  onSelectCommand: onSelectCommand,
                ) ??
                (canExpand
                    ? _ActivityBarTrailing(
                        expanded: expanded,
                        onToggle: onToggle,
                      )
                    : null),
          ),
        ],
      ),
    );
  }
}

class _ActivityDrawerSurface extends StatelessWidget {
  const _ActivityDrawerSurface({
    required this.activeCard,
    required this.historyCards,
    required this.historyHeight,
    required this.expanded,
    required this.canExpand,
    required this.suppressShadow,
    required this.leadingInset,
    required this.showPreviewCutout,
    required this.openActiveCardOnTap,
    required this.onToggle,
    required this.isStopPending,
    required this.onStopToolCall,
    required this.onOpenCard,
    required this.onHistoryPointerDown,
    required this.onHistoryPointerEnd,
  });

  final Map<String, dynamic> activeCard;
  final List<Map<String, dynamic>> historyCards;
  final double historyHeight;
  final bool expanded;
  final bool canExpand;
  final bool suppressShadow;
  final double leadingInset;
  final bool showPreviewCutout;
  final bool openActiveCardOnTap;
  final VoidCallback onToggle;
  final bool isStopPending;
  final VoidCallback? onStopToolCall;
  final ValueChanged<Map<String, dynamic>> onOpenCard;
  final ValueChanged<int> onHistoryPointerDown;
  final ValueChanged<int> onHistoryPointerEnd;

  @override
  Widget build(BuildContext context) {
    final dividerColor = context.isDarkTheme
        ? context.omniPalette.borderSubtle.withValues(alpha: 0.52)
        : const Color(0x140F2034);
    return _ActivitySurface(
      key: kChatToolActivityBarKey,
      expanded: expanded,
      suppressShadow: suppressShadow,
      showPreviewCutout: showPreviewCutout,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          AnimatedCrossFade(
            duration: const Duration(milliseconds: 240),
            firstCurve: Curves.easeInCubic,
            secondCurve: Curves.easeOutCubic,
            sizeCurve: Curves.easeOutQuart,
            alignment: Alignment.bottomCenter,
            crossFadeState: expanded
                ? CrossFadeState.showSecond
                : CrossFadeState.showFirst,
            firstChild: const SizedBox.shrink(key: ValueKey('collapsed-panel')),
            secondChild: SizedBox(
              height: historyHeight,
              child: _HistoryDrawer(
                cards: historyCards,
                onOpenCard: onOpenCard,
                onPointerDown: onHistoryPointerDown,
                onPointerEnd: onHistoryPointerEnd,
              ),
            ),
          ),
          AnimatedContainer(
            duration: const Duration(milliseconds: 220),
            curve: Curves.easeOutCubic,
            height: expanded ? 1 : 0,
            margin: const EdgeInsets.only(left: 18, right: 10),
            color: dividerColor,
          ),
          ToolActivityRow(
            card: activeCard,
            leadingInset: leadingInset,
            onTap: openActiveCardOnTap
                ? () => onOpenCard(activeCard)
                : (canExpand ? onToggle : null),
            trailing: _supportsToolStop(activeCard) && onStopToolCall != null
                ? _ToolStopButton(
                    enabled: !isStopPending,
                    onTap: onStopToolCall,
                  )
                : canExpand
                ? _ActivityBarTrailing(expanded: expanded, onToggle: onToggle)
                : null,
          ),
        ],
      ),
    );
  }

  bool _supportsToolStop(Map<String, dynamic> cardData) {
    return (cardData['status'] ?? '').toString() == 'running';
  }
}

/// Opaque neutral chrome, retaining the strip's existing clipping geometry.
class _ActivitySurface extends StatelessWidget {
  const _ActivitySurface({
    super.key,
    required this.expanded,
    required this.suppressShadow,
    required this.showPreviewCutout,
    required this.child,
  });

  final bool expanded;
  final bool suppressShadow;
  final bool showPreviewCutout;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final bottomReveal = suppressShadow
        ? _kToolActivityAttachedBorderReveal
        : 0.0;
    return DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: _kToolActivitySurfaceBorderRadius,
        boxShadow: suppressShadow
            ? const <BoxShadow>[]
            : <BoxShadow>[
                BoxShadow(
                  color: palette.shadowColor,
                  blurRadius: expanded ? 20 : 16,
                  offset: const Offset(0, 6),
                ),
              ],
      ),
      child: ClipPath(
        clipper: _ActivityDrawerClipper(
          showPreviewCutout: showPreviewCutout,
          bottomReveal: bottomReveal,
        ),
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: palette.surfacePrimary,
            border: Border(
              top: BorderSide(color: palette.borderSubtle, width: 0.5),
              left: BorderSide(color: palette.borderSubtle, width: 0.5),
              right: BorderSide(color: palette.borderSubtle, width: 0.5),
            ),
            borderRadius: _kToolActivitySurfaceBorderRadius,
          ),
          child: child,
        ),
      ),
    );
  }
}

class _ActivityBarTrailing extends StatelessWidget {
  const _ActivityBarTrailing({required this.expanded, required this.onToggle});

  final bool expanded;
  final VoidCallback onToggle;

  @override
  Widget build(BuildContext context) {
    final color = context.isDarkTheme
        ? context.omniPalette.textSecondary
        : const Color(0xFF657891);
    return GestureDetector(
      key: kChatToolActivityToggleKey,
      behavior: HitTestBehavior.opaque,
      onTap: onToggle,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 1),
        child: AnimatedRotation(
          turns: expanded ? 0 : 0.5,
          duration: const Duration(milliseconds: 220),
          curve: Curves.easeOutCubic,
          child: Icon(Icons.keyboard_arrow_up_rounded, size: 14, color: color),
        ),
      ),
    );
  }
}

class _ToolStopButton extends StatelessWidget {
  const _ToolStopButton({required this.enabled, required this.onTap});

  final bool enabled;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final baseColor = context.isDarkTheme
        ? palette.textSecondary
        : const Color(0xFF657891);
    final foregroundColor = enabled
        ? baseColor
        : baseColor.withValues(alpha: 0.42);
    final borderColor = enabled
        ? foregroundColor.withValues(alpha: 0.48)
        : foregroundColor.withValues(alpha: 0.3);
    final backgroundColor = context.isDarkTheme
        ? palette.surfaceElevated.withValues(alpha: enabled ? 0.88 : 0.72)
        : Colors.white.withValues(alpha: enabled ? 0.9 : 0.72);

    return Tooltip(
      message: LegacyTextLocalizer.localize(enabled ? '停止工具' : '正在停止工具'),
      child: GestureDetector(
        key: kChatToolActivityStopKey,
        behavior: HitTestBehavior.opaque,
        onTap: enabled ? onTap : null,
        child: SizedBox(
          width: _kToolActivityTrailingSlotWidth,
          height: _kToolActivityRowHeight,
          child: Center(
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 180),
              width: 18,
              height: 18,
              decoration: BoxDecoration(
                color: backgroundColor,
                shape: BoxShape.circle,
                border: Border.all(color: borderColor, width: 1),
              ),
              alignment: Alignment.center,
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 180),
                width: 6.5,
                height: 6.5,
                decoration: BoxDecoration(
                  color: foregroundColor,
                  borderRadius: BorderRadius.circular(1.8),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _HistoryDrawer extends StatelessWidget {
  const _HistoryDrawer({
    required this.cards,
    required this.onOpenCard,
    required this.onPointerDown,
    required this.onPointerEnd,
  });

  final List<Map<String, dynamic>> cards;
  final ValueChanged<Map<String, dynamic>> onOpenCard;
  final ValueChanged<int> onPointerDown;
  final ValueChanged<int> onPointerEnd;

  @override
  Widget build(BuildContext context) {
    final dividerColor = context.isDarkTheme
        ? context.omniPalette.borderSubtle.withValues(alpha: 0.52)
        : const Color(0x140F2034);
    final scrollable = cards.length > 4;
    return Container(
      key: kChatToolActivityPanelKey,
      padding: EdgeInsets.zero,
      child: Listener(
        behavior: HitTestBehavior.translucent,
        onPointerDown: (event) => onPointerDown(event.pointer),
        onPointerUp: (event) => onPointerEnd(event.pointer),
        onPointerCancel: (event) => onPointerEnd(event.pointer),
        child: ListView.separated(
          reverse: true,
          padding: EdgeInsets.zero,
          shrinkWrap: true,
          physics: scrollable
              ? const BouncingScrollPhysics(parent: ClampingScrollPhysics())
              : const NeverScrollableScrollPhysics(),
          itemBuilder: (context, index) {
            final card = cards[index];
            final isBottomMost = index == 0;
            return DecoratedBox(
              decoration: BoxDecoration(
                border: isBottomMost
                    ? null
                    : Border(bottom: BorderSide(color: dividerColor, width: 1)),
              ),
              child: Material(
                color: Colors.transparent,
                child: InkWell(
                  onTap: () => onOpenCard(card),
                  child: ToolActivityRow(
                    card: card,
                    trailing: _buildCommandTrailingControl(
                      card,
                      onTap: () => onOpenCard(card),
                      onSelectCommand: onOpenCard,
                    ),
                  ),
                ),
              ),
            );
          },
          separatorBuilder: (_, __) => const SizedBox.shrink(),
          itemCount: cards.length,
        ),
      ),
    );
  }
}

class ToolActivityRow extends StatelessWidget {
  const ToolActivityRow({
    super.key,
    required this.card,
    this.leadingInset = 0,
    this.onTap,
    this.trailing,
  });

  final Map<String, dynamic> card;
  final double leadingInset;
  final VoidCallback? onTap;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final primaryTextColor = context.isDarkTheme
        ? palette.textPrimary
        : AppColors.text;
    final secondaryTextColor = context.isDarkTheme
        ? palette.textSecondary
        : const Color(0xFF7C8DA5);
    final status = (card['status'] ?? 'running').toString();
    final isCommandCard = _isCommandActivityCard(card);
    final toolTypeLabel = resolveAgentToolTypeLabel(card);
    final statusLabel = resolveAgentToolStatusLabel(card);
    final titleText = _resolveActivityRowTitle(card);
    final descriptionText = isCommandCard
        ? _resolveCommandActivityDescription(card)
        : '';
    final titleStyle = TextStyle(
      color: primaryTextColor,
      fontSize: 11,
      fontWeight: FontWeight.w600,
      letterSpacing: 0,
      height: 1.05,
    );
    final descriptionStyle = TextStyle(
      color: secondaryTextColor.withValues(alpha: 0.76),
      fontSize: 9,
      fontWeight: FontWeight.w400,
      letterSpacing: 0,
      height: 1.05,
    );

    return SizedBox(
      height: _kToolActivityRowHeight,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 220),
        curve: Curves.easeOutCubic,
        padding: EdgeInsets.fromLTRB(10 + leadingInset, 0, 8, 0),
        child: LayoutBuilder(
          builder: (context, constraints) {
            final trailingSlotWidth = math
                .min(
                  _resolveActivityTrailingSlotWidth(card),
                  math.max(
                    _kToolActivityTrailingSlotWidth,
                    constraints.maxWidth * 0.52,
                  ),
                )
                .toDouble();
            final commandDescriptionMaxWidth = math
                .min(constraints.maxWidth * 0.42, 180.0)
                .toDouble();
            final showTypeLabel =
                !isCommandCard &&
                constraints.maxWidth >=
                    _kToolActivityTypeSlotWidth +
                        _kToolActivityStatusSlotWidth +
                        trailingSlotWidth +
                        28;
            final showStatusLabel = !isCommandCard;
            return Row(
              children: [
                Expanded(
                  child: GestureDetector(
                    behavior: HitTestBehavior.opaque,
                    onTap: onTap,
                    child: Row(
                      children: [
                        if (!isCommandCard) ...[
                          _StatusDot(status: status),
                          const SizedBox(width: 6),
                        ],
                        Expanded(
                          child: descriptionText.isEmpty
                              ? Text(
                                  titleText,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: titleStyle,
                                )
                              : Row(
                                  children: [
                                    Expanded(
                                      child: Text(
                                        titleText,
                                        maxLines: 1,
                                        overflow: TextOverflow.ellipsis,
                                        style: titleStyle,
                                      ),
                                    ),
                                    const SizedBox(width: 8),
                                    SizedBox(
                                      width: commandDescriptionMaxWidth,
                                      child: Align(
                                        alignment: Alignment.centerRight,
                                        child: Text(
                                          descriptionText,
                                          maxLines: 1,
                                          overflow: TextOverflow.ellipsis,
                                          textAlign: TextAlign.right,
                                          style: descriptionStyle,
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                        ),
                        SizedBox(width: showTypeLabel ? 6 : 0),
                        SizedBox(
                          width: showTypeLabel
                              ? _kToolActivityTypeSlotWidth
                              : 0,
                          child: showTypeLabel
                              ? Align(
                                  alignment: Alignment.centerRight,
                                  child: Text(
                                    toolTypeLabel,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    textAlign: TextAlign.right,
                                    style: TextStyle(
                                      color: secondaryTextColor,
                                      fontSize: 9,
                                      fontWeight: FontWeight.w600,
                                      letterSpacing: 0,
                                      height: 1.05,
                                    ),
                                  ),
                                )
                              : null,
                        ),
                        SizedBox(width: showStatusLabel ? 4 : 0),
                        SizedBox(
                          width: showStatusLabel
                              ? _kToolActivityStatusSlotWidth
                              : 0,
                          child: showStatusLabel
                              ? Align(
                                  alignment: Alignment.centerRight,
                                  child: _StatusTag(
                                    status: status,
                                    label: statusLabel,
                                  ),
                                )
                              : null,
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(width: 4),
                SizedBox(
                  width: trailingSlotWidth,
                  child: Align(
                    alignment: Alignment.centerRight,
                    child: trailing,
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}

bool _isCommandActivityCard(Map<String, dynamic> cardData) {
  return (cardData['toolType'] ?? '').toString() == 'command';
}

bool _isCommandToggleCard(Map<String, dynamic> cardData) {
  return _isCommandActivityCard(cardData) && cardData['isToggle'] == true;
}

bool _isCommandEffortSliderCard(Map<String, dynamic> cardData) {
  return _isCommandActivityCard(cardData) &&
      (cardData['controlType'] ?? '').toString() == 'effortSlider';
}

double _resolveActivityTrailingSlotWidth(Map<String, dynamic> cardData) {
  if (_isCommandEffortSliderCard(cardData)) {
    return _kCommandEffortSliderWidth;
  }
  return _kToolActivityTrailingSlotWidth;
}

List<String> _resolveCommandEffortOptions(Map<String, dynamic> cardData) {
  final rawOptions = cardData['effortOptions'];
  final options = rawOptions is List
      ? rawOptions
            .map((item) => item.toString().trim())
            .where((item) => item.isNotEmpty)
            .toList(growable: false)
      : const <String>[];
  return options.isEmpty
      ? const <String>['no', 'low', 'high', 'xhigh', 'max']
      : options;
}

String? _resolveSelectedCommandEffort(Map<String, dynamic> cardData) {
  final selected = (cardData['selectedEffort'] ?? cardData['statusLabel'])
      .toString()
      .trim();
  if (selected.isEmpty) {
    return null;
  }
  final options = _resolveCommandEffortOptions(cardData);
  return options.contains(selected) ? selected : null;
}

Widget? _buildCommandTrailingControl(
  Map<String, dynamic> cardData, {
  VoidCallback? onTap,
  ValueChanged<Map<String, dynamic>>? onSelectCommand,
}) {
  final cardId = (cardData['cardId'] ?? cardData['toolTitle'] ?? '')
      .toString()
      .trim();
  if (_isCommandToggleCard(cardData)) {
    return _CommandToggleSwitch(
      value: cardData['toggleValue'] == true,
      semanticLabel: resolveAgentToolTitle(cardData),
      onTap: onTap,
      key: ValueKey<String>('$kChatCommandToggleKeyPrefix-$cardId'),
    );
  }
  if (_isCommandEffortSliderCard(cardData)) {
    return _CommandEffortSlider(
      key: ValueKey<String>('$kChatCommandEffortSliderKeyPrefix-$cardId'),
      options: _resolveCommandEffortOptions(cardData),
      selectedValue: _resolveSelectedCommandEffort(cardData),
      semanticLabel: resolveAgentToolTitle(cardData),
      onChanged: onSelectCommand == null
          ? null
          : (effort) {
              onSelectCommand(<String, dynamic>{
                ...cardData,
                'toolName': effort,
                'toolTitle': effort,
                'displayName': effort,
                'selectedEffort': effort,
              });
            },
    );
  }
  return null;
}

bool _isBrowserActivityCard(Map<String, dynamic> cardData) {
  return (cardData['toolType'] ?? '').toString() == 'browser';
}

String _resolveActivityRowTitle(Map<String, dynamic> cardData) {
  final title = resolveAgentToolTitle(cardData).trim();
  if (!_isCommandActivityCard(cardData)) {
    return title;
  }
  return title.replaceFirst(RegExp(r'^/+'), '').trim();
}

String _resolveCommandActivityDescription(Map<String, dynamic> cardData) {
  if (_isCommandEffortSliderCard(cardData)) {
    return '';
  }
  final title = resolveAgentToolTitle(cardData).trim();
  final summary = (cardData['summary'] ?? '').toString().trim();
  if (summary.isNotEmpty && summary != title) {
    return summary;
  }
  final progress = (cardData['progress'] ?? '').toString().trim();
  if (progress.isNotEmpty && progress != title) {
    return progress;
  }
  final preview = resolveAgentToolPreview(cardData).trim();
  if (preview.isNotEmpty && preview != title) {
    return preview;
  }
  return '';
}

class _StatusDot extends StatelessWidget {
  const _StatusDot({required this.status});

  final String status;

  @override
  Widget build(BuildContext context) {
    final color = resolveAgentToolStatusColor(status);
    final palette = context.omniPalette;
    final outerColor = context.isDarkTheme
        ? Color.alphaBlend(
            color.withValues(alpha: 0.14),
            palette.surfaceElevated,
          )
        : color.withValues(alpha: 0.16);
    return Container(
      width: 8,
      height: 8,
      decoration: BoxDecoration(color: outerColor, shape: BoxShape.circle),
      alignment: Alignment.center,
      child: Container(
        width: 3,
        height: 3,
        decoration: BoxDecoration(color: color, shape: BoxShape.circle),
      ),
    );
  }
}

class _StatusTag extends StatelessWidget {
  const _StatusTag({required this.status, required this.label});

  final String status;
  final String label;

  @override
  Widget build(BuildContext context) {
    final color = resolveAgentToolStatusColor(status);
    final palette = context.omniPalette;
    final backgroundColor = context.isDarkTheme
        ? Color.alphaBlend(
            color.withValues(alpha: 0.14),
            palette.surfaceElevated,
          )
        : color.withValues(alpha: 0.11);
    final textColor = context.isDarkTheme
        ? Color.lerp(palette.textSecondary, color, 0.38)!
        : color.withValues(alpha: 0.9);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 2),
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          color: textColor,
          fontSize: 8.4,
          fontWeight: FontWeight.w700,
          height: 1,
        ),
      ),
    );
  }
}

class _CommandEffortSlider extends StatelessWidget {
  const _CommandEffortSlider({
    super.key,
    required this.options,
    required this.selectedValue,
    required this.semanticLabel,
    this.onChanged,
  });

  final List<String> options;
  final String? selectedValue;
  final String semanticLabel;
  final ValueChanged<String>? onChanged;

  @override
  Widget build(BuildContext context) => Semantics(
    label: semanticLabel,
    value: selectedValue ?? '',
    child: OmniSegmentedSlider<String>(
      keyPrefix: 'command-effort',
      value: selectedValue,
      height: 22,
      fontSize: 8.2,
      options: [
        for (final option in options)
          OmniSegmentedOption(value: option, label: option),
      ],
      onChanged: onChanged,
    ),
  );
}

class _CommandToggleSwitch extends StatelessWidget {
  const _CommandToggleSwitch({
    super.key,
    required this.value,
    required this.semanticLabel,
    this.onTap,
  });

  final bool value;
  final String semanticLabel;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final activeColor = context.isDarkTheme
        ? palette.accentPrimary
        : AppColors.primaryBlue;
    final inactiveColor = context.isDarkTheme
        ? palette.borderSubtle.withValues(alpha: 0.72)
        : const Color(0xFFD4DEEC);
    final thumbColor = context.isDarkTheme
        ? palette.surfaceElevated
        : Colors.white;
    return Semantics(
      label: semanticLabel,
      toggled: value,
      button: true,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          curve: Curves.easeOutCubic,
          width: 24,
          height: 14,
          padding: const EdgeInsets.all(2),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(999),
            color: value ? activeColor : inactiveColor,
          ),
          child: Align(
            alignment: value ? Alignment.centerRight : Alignment.centerLeft,
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 180),
              curve: Curves.easeOutCubic,
              width: 10,
              height: 10,
              decoration: BoxDecoration(
                color: thumbColor,
                shape: BoxShape.circle,
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: value ? 0.16 : 0.10),
                    blurRadius: 3,
                    offset: const Offset(0, 1),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _BrowserThumbnail extends StatefulWidget {
  const _BrowserThumbnail({
    super.key,
    required this.cardData,
    required this.onTap,
  });

  final Map<String, dynamic> cardData;
  final VoidCallback onTap;

  @override
  State<_BrowserThumbnail> createState() => _BrowserThumbnailState();
}

class _BrowserThumbnailState extends State<_BrowserThumbnail> {
  static const Duration _refreshInterval = Duration(milliseconds: 1200);

  Timer? _refreshTimer;
  Uint8List? _previewBytes;
  String _previewTitle = '';
  String _previewUrl = '';
  bool _isLoading = false;
  bool _refreshInFlight = false;

  @override
  void initState() {
    super.initState();
    _primeFromCard();
    _startRefreshing();
  }

  @override
  void didUpdateWidget(covariant _BrowserThumbnail oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (_cardSignature(oldWidget.cardData) != _cardSignature(widget.cardData)) {
      _primeFromCard();
      _refreshPreview();
    }
  }

  @override
  void dispose() {
    _refreshTimer?.cancel();
    super.dispose();
  }

  void _startRefreshing() {
    if (!Platform.isAndroid) {
      return;
    }
    _refreshPreview();
    _refreshTimer = Timer.periodic(_refreshInterval, (_) => _refreshPreview());
  }

  void _primeFromCard() {
    _previewTitle = resolveAgentToolTitle(widget.cardData);
    _previewUrl = _resolveBrowserUrl(widget.cardData);
    _isLoading = (widget.cardData['status'] ?? '').toString() == 'running';
  }

  Future<void> _refreshPreview() async {
    if (!Platform.isAndroid || _refreshInFlight) {
      return;
    }
    _refreshInFlight = true;
    try {
      final frame = await AgentBrowserSessionService.capturePreviewFrame(
        workspaceId: _workspaceId,
        maxWidth: _kBrowserActivityPreviewMaxWidth,
      );
      final bytes = frame == null
          ? null
          : _decodeImageDataUrl(frame.imageDataUrl);
      if (!mounted || frame == null || bytes == null) {
        return;
      }
      setState(() {
        _previewBytes = bytes;
        _previewTitle = frame.title.trim().isNotEmpty
            ? frame.title.trim()
            : _previewTitle;
        _previewUrl = frame.currentUrl.trim().isNotEmpty
            ? frame.currentUrl.trim()
            : _previewUrl;
        _isLoading = frame.isLoading;
      });
    } catch (_) {
      // Keep the last good browser frame; the next timer tick can recover.
    } finally {
      _refreshInFlight = false;
    }
  }

  String get _workspaceId {
    return (widget.cardData['workspaceId'] ?? '').toString().trim();
  }

  String _cardSignature(Map<String, dynamic> cardData) {
    return [
      (cardData['taskId'] ?? '').toString(),
      (cardData['cardId'] ?? '').toString(),
      (cardData['status'] ?? '').toString(),
      (cardData['toolTitle'] ?? '').toString(),
      (cardData['argsJson'] ?? '').toString(),
    ].join('|');
  }

  Uint8List? _decodeImageDataUrl(String value) {
    final commaIndex = value.indexOf(',');
    if (commaIndex < 0 || commaIndex == value.length - 1) {
      return null;
    }
    try {
      return base64Decode(value.substring(commaIndex + 1));
    } catch (_) {
      return null;
    }
  }

  String _resolveBrowserUrl(Map<String, dynamic> cardData) {
    for (final source in <String>[
      (cardData['resultPreviewJson'] ?? '').toString(),
      (cardData['rawResultJson'] ?? '').toString(),
      (cardData['argsJson'] ?? '').toString(),
    ]) {
      final url = _extractUrlFromJson(source);
      if (url.isNotEmpty) {
        return url;
      }
    }
    return '';
  }

  String _extractUrlFromJson(String source) {
    final text = source.trim();
    if (text.isEmpty) {
      return '';
    }
    try {
      final decoded = jsonDecode(text);
      if (decoded is! Map) {
        return '';
      }
      final map = decoded.map((key, value) => MapEntry(key.toString(), value));
      for (final key in const <String>[
        'currentUrl',
        'finalUrl',
        'url',
        'href',
        'target',
      ]) {
        final value = (map[key] ?? '').toString().trim();
        if (value.isNotEmpty) {
          return value;
        }
      }
    } catch (_) {
      return '';
    }
    return '';
  }

  @override
  Widget build(BuildContext context) {
    final title = _previewTitle.trim().isNotEmpty
        ? _previewTitle.trim()
        : LegacyTextLocalizer.localize('浏览器');
    final host = _resolveHostLabel(_previewUrl);
    return PhysicalModel(
      color: Colors.white,
      borderRadius: _kToolActivityPreviewBorderRadius,
      clipBehavior: Clip.antiAlias,
      elevation: 6,
      shadowColor: const Color(0x3A1E2C45),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: widget.onTap,
          child: Ink(
            width: _kToolActivityPreviewWidth,
            height: _kToolActivityPreviewHeight,
            decoration: const BoxDecoration(
              color: Color(0xFFEFF4FA),
              borderRadius: _kToolActivityPreviewBorderRadius,
            ),
            child: Stack(
              fit: StackFit.expand,
              children: [
                if (_previewBytes != null)
                  Image.memory(
                    _previewBytes!,
                    fit: BoxFit.cover,
                    alignment: Alignment.topCenter,
                    gaplessPlayback: true,
                    filterQuality: FilterQuality.medium,
                  )
                else
                  _BrowserThumbnailFallback(title: title, host: host),
                const Positioned.fill(
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.topCenter,
                        end: Alignment.bottomCenter,
                        colors: <Color>[
                          Color(0x4DFFFFFF),
                          Color(0x00FFFFFF),
                          Color(0x260A1628),
                        ],
                        stops: <double>[0, 0.46, 1],
                      ),
                    ),
                  ),
                ),
                Positioned(
                  left: 5,
                  right: 5,
                  top: 5,
                  child: _BrowserThumbnailChrome(
                    title: title,
                    host: host,
                    isLoading: _isLoading,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  String _resolveHostLabel(String url) {
    final trimmed = url.trim();
    if (trimmed.isEmpty) {
      return LegacyTextLocalizer.localize('浏览器');
    }
    final uri = Uri.tryParse(trimmed);
    final host = uri?.host.trim() ?? '';
    if (host.isNotEmpty) {
      return host;
    }
    return trimmed.replaceFirst(RegExp(r'^https?://'), '');
  }
}

class _BrowserThumbnailChrome extends StatelessWidget {
  const _BrowserThumbnailChrome({
    required this.title,
    required this.host,
    required this.isLoading,
  });

  final String title;
  final String host;
  final bool isLoading;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.88),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: const Color(0x66D5E1EF), width: 0.6),
      ),
      child: SizedBox(
        height: 10,
        child: Row(
          children: [
            const SizedBox(width: 4),
            AnimatedContainer(
              duration: const Duration(milliseconds: 180),
              width: 3.5,
              height: 3.5,
              decoration: BoxDecoration(
                color: isLoading
                    ? const Color(0xFF2F80ED)
                    : const Color(0xFF34A853),
                shape: BoxShape.circle,
              ),
            ),
            const SizedBox(width: 3),
            Expanded(
              child: Text(
                host.isNotEmpty ? host : title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  color: Color(0xFF38516D),
                  fontSize: 5.8,
                  height: 1,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 0,
                ),
              ),
            ),
            const SizedBox(width: 4),
          ],
        ),
      ),
    );
  }
}

class _BrowserThumbnailFallback extends StatelessWidget {
  const _BrowserThumbnailFallback({required this.title, required this.host});

  final String title;
  final String host;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: <Color>[Color(0xFFF9FBFF), Color(0xFFE2ECF8)],
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(8, 18, 8, 6),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Icon(
              Icons.travel_explore_rounded,
              size: 12,
              color: Color(0xFF5D7FA8),
            ),
            const Spacer(),
            Text(
              host.isNotEmpty ? host : title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                color: Color(0xFF31516F),
                fontSize: 6.2,
                height: 1,
                fontWeight: FontWeight.w700,
                letterSpacing: 0,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _TerminalThumbnail extends StatelessWidget {
  const _TerminalThumbnail({
    super.key,
    required this.transcript,
    required this.onTap,
  });

  final AgentToolTranscript transcript;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return PhysicalModel(
      color: kTerminalSurfaceBlack,
      borderRadius: _kToolActivityPreviewBorderRadius,
      clipBehavior: Clip.antiAlias,
      elevation: 6,
      shadowColor: kTerminalSurfaceShadow,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          child: Ink(
            width: _kToolActivityPreviewWidth,
            height: _kToolActivityPreviewHeight,
            padding: const EdgeInsets.fromLTRB(8, 7, 8, 7),
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                colors: [kTerminalSurfaceBlackElevated, kTerminalSurfaceBlack],
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
              ),
              borderRadius: _kToolActivityPreviewBorderRadius,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  transcript.promptLine,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: Color(0xFFF4F7FB),
                    fontSize: 6.9,
                    height: 1.05,
                    fontWeight: FontWeight.w600,
                    fontFamily: 'monospace',
                  ),
                ),
                if (transcript.previewText.trim().isNotEmpty) ...[
                  const SizedBox(height: 2),
                  Expanded(
                    child: Text.rich(
                      AnsiTextSpanBuilder.build(
                        transcript.previewText,
                        const TextStyle(
                          color: Color(0xFF88EEA6),
                          fontSize: 5.7,
                          height: 1.08,
                          fontFamily: 'monospace',
                        ),
                      ),
                      maxLines: 3,
                      overflow: TextOverflow.clip,
                    ),
                  ),
                ] else
                  const Spacer(),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _ActivityDrawerClipper extends CustomClipper<Path> {
  const _ActivityDrawerClipper({
    required this.showPreviewCutout,
    this.bottomReveal = 0,
  });

  final bool showPreviewCutout;
  final double bottomReveal;

  @override
  Path getClip(Size size) {
    final resolvedBottomReveal = bottomReveal.clamp(0.0, size.height);
    final surfaceHeight = math.max(0.0, size.height - resolvedBottomReveal);
    final surfacePath = Path()
      ..addRRect(
        _kToolActivitySurfaceBorderRadius.toRRect(
          Rect.fromLTWH(0, 0, size.width, surfaceHeight),
        ),
      );
    if (!showPreviewCutout) {
      return surfacePath;
    }
    final previewTop =
        -(_kToolActivityPreviewHeight - _kToolActivityPreviewOverlap);
    final previewRect = Rect.fromLTWH(
      -_kToolActivitySurfaceHorizontalInset,
      previewTop,
      _kToolActivityPreviewWidth,
      _kToolActivityPreviewHeight,
    );
    final previewPath = Path()
      ..addRRect(_kToolActivityPreviewBorderRadius.toRRect(previewRect));
    return Path.combine(PathOperation.difference, surfacePath, previewPath);
  }

  @override
  bool shouldReclip(covariant _ActivityDrawerClipper oldClipper) {
    return oldClipper.showPreviewCutout != showPreviewCutout ||
        oldClipper.bottomReveal != bottomReveal;
  }
}
