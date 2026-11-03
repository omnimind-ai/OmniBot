import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_slidable/flutter_slidable.dart';
import 'package:ui/features/home/state/habitual_hand_controller.dart';

class ConversationSlideAction {
  const ConversationSlideAction({
    required this.onPressed,
    required this.backgroundColor,
    required this.child,
    this.borderRadius = BorderRadius.zero,
  });

  final VoidCallback onPressed;
  final Color backgroundColor;
  final Widget child;
  final BorderRadius borderRadius;
}

class ConversationSlidable extends ConsumerStatefulWidget {
  const ConversationSlidable({
    super.key,
    required this.itemKey,
    required this.groupTag,
    required this.actions,
    required this.onDismissed,
    required this.child,
    this.isBusy = false,
    this.margin = EdgeInsets.zero,
    this.actionExtentRatioPerAction = 0.24,
    this.dismissThreshold,
    this.onFullSwipe,
    this.followHabitualHand = true,
    this.resetAfterFullSwipe = false,
  }) : assert(actions.length > 0, 'actions must not be empty');

  final String itemKey;
  final Object groupTag;
  final List<ConversationSlideAction> actions;
  final VoidCallback onDismissed;
  final Widget child;
  final bool isBusy;
  final EdgeInsetsGeometry margin;
  final double actionExtentRatioPerAction;
  final double? dismissThreshold;
  final VoidCallback? onFullSwipe;
  final bool followHabitualHand;
  final bool resetAfterFullSwipe;

  @override
  ConsumerState<ConversationSlidable> createState() =>
      _ConversationSlidableState();
}

class _ConversationSlidableState extends ConsumerState<ConversationSlidable> {
  int _dismissalGeneration = 0;

  void _handleDismissed() {
    // Search results may remain visible after archiving, and a failed action
    // may restore the row before the next frame. Neither can reuse a Slidable
    // controller whose dismissal has already collapsed the row.
    if (widget.resetAfterFullSwipe) {
      setState(() => _dismissalGeneration++);
    }
    (widget.onFullSwipe ?? widget.onDismissed)();
  }

  @override
  Widget build(BuildContext context) {
    final isLeftHanded =
        widget.followHabitualHand && ref.watch(habitualHandProvider).isLeft;
    final totalExtentRatio =
        (widget.actionExtentRatioPerAction * widget.actions.length)
            .clamp(0.0, 1.0)
            .toDouble();
    final resolvedDismissThreshold =
        widget.dismissThreshold ??
        (widget.actions.length > 1
            ? (totalExtentRatio + 0.16).clamp(0.0, 0.95).toDouble()
            : 0.4);
    final effectiveActions = isLeftHanded
        ? widget.actions.reversed.toList(growable: false)
        : widget.actions;
    final actionPane = ActionPane(
      motion: const BehindMotion(),
      extentRatio: totalExtentRatio,
      dismissible: DismissiblePane(
        dismissThreshold: resolvedDismissThreshold,
        closeOnCancel: true,
        motion: const InversedDrawerMotion(),
        onDismissed: _handleDismissed,
      ),
      children: effectiveActions
          .map(
            (action) => CustomSlidableAction(
              onPressed: (_) => action.onPressed(),
              backgroundColor: action.backgroundColor,
              borderRadius: isLeftHanded
                  ? _mirrorBorderRadius(action.borderRadius)
                  : action.borderRadius,
              padding: EdgeInsets.zero,
              child: action.child,
            ),
          )
          .toList(),
    );

    return Container(
      margin: widget.margin,
      child: IgnorePointer(
        ignoring: widget.isBusy,
        child: AnimatedOpacity(
          duration: const Duration(milliseconds: 180),
          opacity: widget.isBusy ? 0.72 : 1,
          child: Slidable(
            key: widget.resetAfterFullSwipe
                ? ValueKey((widget.itemKey, _dismissalGeneration))
                : ValueKey<String>(widget.itemKey),
            groupTag: widget.groupTag,
            closeOnScroll: true,
            startActionPane: isLeftHanded ? actionPane : null,
            endActionPane: isLeftHanded ? null : actionPane,
            child: widget.child,
          ),
        ),
      ),
    );
  }

  BorderRadius _mirrorBorderRadius(BorderRadius radius) {
    return BorderRadius.only(
      topLeft: radius.topRight,
      topRight: radius.topLeft,
      bottomLeft: radius.bottomRight,
      bottomRight: radius.bottomLeft,
    );
  }
}
