import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/bot_status.dart'
    show ShimmeringStatusText;
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/agent_brand_icon.dart';

/// The single header rendered above every agent turn.
///
/// There used to be two of these — one for a run in flight ("正在处理 Ns") and a
/// different one for a run restored from history (`已处理 <elapsed>` with a fold
/// chevron). Because they were separate widgets fed by separate state, a live
/// turn could sprout one header per streamed message while a restored turn
/// could end up with none. One widget driven by one status makes both failures
/// unrepresentable: a turn has exactly one header, and it always has an avatar.
class AgentRunHeader extends StatefulWidget {
  const AgentRunHeader({
    super.key,
    required this.taskId,
    required this.agentId,
    required this.status,
    required this.startedAt,
    this.finishedAt,
    this.expanded = false,
    this.onToggleExpanded,
  });

  final String taskId;
  final String agentId;
  final AgentRunStatus status;
  final DateTime startedAt;

  /// When the turn ended. Null while it is running.
  final DateTime? finishedAt;

  final bool expanded;

  /// Null while running — an in-flight run is always expanded and cannot be
  /// folded, which is what makes "collapse when it finishes" automatic.
  final VoidCallback? onToggleExpanded;

  bool get isRunning => status == AgentRunStatus.running;

  @override
  State<AgentRunHeader> createState() => _AgentRunHeaderState();
}

class _AgentRunHeaderState extends State<AgentRunHeader> {
  Timer? _elapsedTimer;
  int _elapsedSeconds = 0;

  @override
  void initState() {
    super.initState();
    _elapsedSeconds = _resolveElapsedSeconds();
    _syncTimer();
  }

  @override
  void didUpdateWidget(covariant AgentRunHeader oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.startedAt != widget.startedAt ||
        oldWidget.status != widget.status ||
        oldWidget.finishedAt != widget.finishedAt) {
      _elapsedSeconds = _resolveElapsedSeconds();
      _syncTimer();
    }
  }

  @override
  void dispose() {
    _elapsedTimer?.cancel();
    super.dispose();
  }

  void _syncTimer() {
    _elapsedTimer?.cancel();
    if (!widget.isRunning) {
      _elapsedTimer = null;
      return;
    }
    _elapsedTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      setState(() {
        _elapsedSeconds = math.max(
          _elapsedSeconds + 1,
          _resolveElapsedSeconds(),
        );
      });
    });
  }

  int _resolveElapsedSeconds() {
    final end = widget.isRunning ? DateTime.now() : widget.finishedAt;
    if (end == null) return 0;
    return math.max(0, end.difference(widget.startedAt).inSeconds);
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isEnglish =
        Localizations.maybeLocaleOf(context)?.languageCode == 'en';
    final running = widget.isRunning;
    final canToggleExpanded = !running && widget.onToggleExpanded != null;
    final label = running
        ? (isEnglish
              ? 'Processing ${_elapsedSeconds}s'
              : '正在处理 ${_elapsedSeconds}s')
        : _finishedLabel(isEnglish);
    final labelColor = running
        ? palette.textTertiary
        : (widget.expanded ? palette.textSecondary : palette.textTertiary);
    final textStyle = TextStyle(
      fontSize: 11,
      fontWeight: FontWeight.w600,
      letterSpacing: 0,
      height: 1.2,
      color: labelColor,
      fontFamily: 'PingFang SC',
    );

    final Widget labelWidget = running
        ? ShimmeringStatusText(
            baseColor: labelColor,
            child: Text(
              label,
              key: const ValueKey('acp-processing-label'),
              style: textStyle,
            ),
          )
        : Text(
            label,
            key: const ValueKey('acp-processed-label'),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: textStyle,
          );

    final row = Row(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        AgentRunAvatar(
          key: ValueKey('agent-run-acp-avatar-${widget.taskId}'),
          agentId: widget.agentId,
        ),
        const SizedBox(width: 8),
        ConstrainedBox(
          constraints: BoxConstraints(
            maxWidth: MediaQuery.sizeOf(context).width * 0.6,
          ),
          child: AnimatedSwitcher(
            duration: const Duration(milliseconds: 180),
            switchInCurve: Curves.easeOutCubic,
            switchOutCurve: Curves.easeOutCubic,
            transitionBuilder: (child, animation) =>
                FadeTransition(opacity: animation, child: child),
            child: labelWidget,
          ),
        ),
        const SizedBox(width: 2),
        SizedBox(
          width: 18,
          height: 18,
          child: AnimatedSwitcher(
            duration: const Duration(milliseconds: 180),
            switchInCurve: Curves.easeOutCubic,
            switchOutCurve: Curves.easeOutCubic,
            child: running || !canToggleExpanded
                ? const SizedBox(key: ValueKey('agent-run-chevron-running'))
                : AnimatedRotation(
                    key: const ValueKey('agent-run-chevron-finished'),
                    turns: widget.expanded ? 0 : -0.25,
                    duration: const Duration(milliseconds: 320),
                    curve: Curves.easeInOutCubic,
                    child: Icon(
                      LucideIcons.chevronDown,
                      key: ValueKey(
                        'agent-run-summary-chevron-${widget.taskId}',
                      ),
                      size: 18,
                      color: labelColor,
                    ),
                  ),
          ),
        ),
      ],
    );

    final content = Semantics(liveRegion: running, label: label, child: row);

    // Keep the same element tree across running -> finished. Swapping the
    // whole subtree from plain content to Material/InkWell recreates the
    // AnimatedSwitchers on the completion frame, which makes the header flash
    // instead of cross-fading while the process section folds.
    return Padding(
      padding: const EdgeInsets.only(top: 8, bottom: 4),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: running ? null : widget.onToggleExpanded,
          borderRadius: BorderRadius.circular(10),
          splashFactory: NoSplash.splashFactory,
          overlayColor: const WidgetStatePropertyAll(Colors.transparent),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(2, 4, 2, 4),
            child: content,
          ),
        ),
      ),
    );
  }

  String _finishedLabel(bool isEnglish) {
    final base = isEnglish ? 'Processed' : '已处理';
    final elapsed = _formatElapsed(_elapsedSeconds);
    return elapsed.isEmpty ? base : '$base  $elapsed';
  }
}

/// Round agent-brand avatar shown by [AgentRunHeader].
class AgentRunAvatar extends StatelessWidget {
  const AgentRunAvatar({super.key, required this.agentId});

  final String agentId;

  @override
  Widget build(BuildContext context) {
    if (AgentBrandIcon.hasKnownBrand(agentId)) {
      return SizedBox.square(
        dimension: 30,
        child: AgentBrandIcon(agentId: agentId, size: 30),
      );
    }

    final palette = context.omniPalette;
    final backgroundColor = context.isDarkTheme
        ? palette.surfaceSecondary.withValues(alpha: 0.66)
        : palette.surfaceElevated.withValues(alpha: 0.92);
    final borderColor = palette.borderSubtle.withValues(
      alpha: context.isDarkTheme ? 0.48 : 0.72,
    );
    return Container(
      width: 30,
      height: 30,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: backgroundColor,
        shape: BoxShape.circle,
        border: Border.all(color: borderColor, width: 0.5),
      ),
      child: AgentBrandIcon(
        agentId: agentId,
        size: 18,
        tint: palette.textTertiary,
      ),
    );
  }
}

/// "47s" / "1m 23s" / "1h 5m". Empty for sub-second runs.
String _formatElapsed(int totalSeconds) {
  if (totalSeconds < 1) return '';
  if (totalSeconds < 60) return '${totalSeconds}s';
  final minutes = totalSeconds ~/ 60;
  if (minutes < 60) {
    final seconds = totalSeconds % 60;
    return seconds == 0 ? '${minutes}m' : '${minutes}m ${seconds}s';
  }
  final hours = minutes ~/ 60;
  final remainingMinutes = minutes % 60;
  return remainingMinutes == 0 ? '${hours}h' : '${hours}h ${remainingMinutes}m';
}
