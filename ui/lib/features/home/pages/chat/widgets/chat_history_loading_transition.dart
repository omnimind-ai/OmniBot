import 'package:flutter/material.dart';
import 'package:ui/theme/theme_context.dart';

import '../../command_overlay/widgets/cards/bot_status.dart';
import 'chat_message_viewport.dart';

/// Presents history hydration without treating an empty snapshot as a new chat.
/// The caller owns loading; this widget only coordinates its visual handoff.
class ChatHistoryLoadingTransition extends StatefulWidget {
  const ChatHistoryLoadingTransition({
    super.key,
    required this.isLoading,
    required this.child,
    this.bottomInset = 0,
    this.textColor,
  });

  final bool isLoading;
  final Widget child;
  final double bottomInset;
  final Color? textColor;

  @override
  State<ChatHistoryLoadingTransition> createState() =>
      _ChatHistoryLoadingTransitionState();
}

class _ChatHistoryLoadingTransitionState
    extends State<ChatHistoryLoadingTransition>
    with SingleTickerProviderStateMixin {
  late final AnimationController _revealController = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 240),
    value: widget.isLoading ? 0 : 1,
  );
  late final CurvedAnimation _reveal = CurvedAnimation(
    parent: _revealController,
    curve: Curves.easeOutCubic,
  );
  Animation<double>? _routeAnimation;
  bool _reduceMotion = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _reduceMotion = MediaQuery.disableAnimationsOf(context);
    final routeAnimation = ModalRoute.of(context)?.animation;
    if (routeAnimation != _routeAnimation) {
      _routeAnimation?.removeStatusListener(_handleRouteStatus);
      _routeAnimation = routeAnimation;
      _routeAnimation?.addStatusListener(_handleRouteStatus);
    }
    _revealWhenReady();
  }

  @override
  void didUpdateWidget(covariant ChatHistoryLoadingTransition oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.isLoading) {
      // Remove the previous conversation immediately, even if its reveal is
      // still running. Never crossfade history from different targets.
      _revealController.value = 0;
    } else if (oldWidget.isLoading) {
      _revealWhenReady();
    }
  }

  void _handleRouteStatus(AnimationStatus status) {
    if (status == AnimationStatus.completed ||
        status == AnimationStatus.reverse) {
      _revealWhenReady();
    }
  }

  void _revealWhenReady() {
    if (widget.isLoading) return;
    if (_reduceMotion) {
      _revealController.value = 1;
      return;
    }
    if (_revealController.isCompleted || _revealController.isAnimating) return;
    final routeStatus = _routeAnimation?.status;
    if (routeStatus == AnimationStatus.forward ||
        routeStatus == AnimationStatus.dismissed) {
      // Let the home-to-chat entrance finish before revealing the timeline.
      // History is already available; no timer delays its loading or commits.
      return;
    }
    _revealController.forward();
  }

  @override
  void dispose() {
    _routeAnimation?.removeStatusListener(_handleRouteStatus);
    _reveal.dispose();
    _revealController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final color = widget.textColor ?? context.omniPalette.textSecondary;
    final loadingLabel = Localizations.localeOf(context).languageCode == 'en'
        ? 'Loading conversation'
        : '正在加载聊天记录';
    final label = Text(
      'OmniBot',
      style: TextStyle(
        color: color,
        fontSize: 28,
        fontWeight: FontWeight.w500,
        letterSpacing: -0.5,
      ),
    );
    final loadingContent = Align(
      alignment: const Alignment(0, -0.18),
      child: Semantics(
        label: loadingLabel,
        liveRegion: true,
        excludeSemantics: true,
        child: RepaintBoundary(
          child: TickerMode(
            enabled: !_reduceMotion,
            child: _reduceMotion
                ? label
                : ShimmeringStatusText(baseColor: color, child: label),
          ),
        ),
      ),
    );
    final loading = IgnorePointer(
      child: Builder(
        builder: (context) => Padding(
          padding: EdgeInsets.only(
            bottom:
                (ChatMessageViewport.maybeOf(context)?.bottomOverlayInset ??
                        widget.bottomInset)
                    .clamp(0.0, double.infinity)
                    .toDouble(),
          ),
          child: loadingContent,
        ),
      ),
    );

    return AnimatedBuilder(
      animation: _reveal,
      // Do not even mount the timeline while the selected history is pending.
      child: widget.isLoading ? null : widget.child,
      builder: (context, child) => Stack(
        fit: StackFit.expand,
        children: [
          if (child != null)
            FadeTransition(
              key: const ValueKey('chat-history-content'),
              opacity: _reveal,
              child: IgnorePointer(
                ignoring: _reveal.value == 0,
                child: ExcludeSemantics(
                  excluding: _reveal.value == 0,
                  child: child,
                ),
              ),
            ),
          if (!_revealController.isCompleted)
            FadeTransition(
              key: const ValueKey('chat-history-loading'),
              opacity: ReverseAnimation(_reveal),
              child: loading,
            ),
        ],
      ),
    );
  }
}
