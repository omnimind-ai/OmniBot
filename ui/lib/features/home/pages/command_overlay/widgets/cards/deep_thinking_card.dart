import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/command_overlay/services/tool_card_detail_gesture_gate.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/streaming_text.dart';
import './bot_status.dart';

/// 深度思考卡片组件
/// 用于展示流式返回的思考内容（task_description, sub_tasks, preparation）
class DeepThinkingCard extends StatefulWidget {
  /// 思考内容文本（已格式化）
  final String thinkingText;

  /// 是否正在加载中
  final bool isLoading;

  /// 卡片最大高度
  final double maxHeight;

  /// 思考阶段：1-正在识别你的需求类型，2-规划任务中，3-正在帮你规划任务，4-完成思考，5-已取消
  final int stage;

  /// 开始时间（毫秒时间戳）
  final int? startTime;

  /// 结束时间（毫秒时间戳）
  final int? endTime;

  /// 任务 ID
  final String? taskId;

  /// 取消任务回调，参数为 taskId
  final void Function(String taskId)? onCancelTask;

  /// 任务是否可执行（影响是否显示操作行）
  final bool isExecutable;

  /// 是否允许点击折叠/展开思考内容
  final bool isCollapsible;

  /// 是否在思考完成后自动折叠内容
  final bool autoCollapseOnComplete;

  /// 外层消息列表滚动控制器，用于内外滚动联动
  final ScrollController? parentScrollController;
  final VoidCallback? onParentScrollHandoff;
  final VoidCallback? onStreamingTextLayoutChanged;
  final double textScale;
  final Color textColor;
  final bool showStatusAvatar;

  const DeepThinkingCard({
    super.key,
    required this.thinkingText,
    this.isLoading = true,
    this.maxHeight = 210.0,
    this.stage = 1,
    this.startTime,
    this.endTime,
    this.taskId,
    this.onCancelTask,
    this.isExecutable = false,
    this.isCollapsible = false,
    this.autoCollapseOnComplete = true,
    this.parentScrollController,
    this.onParentScrollHandoff,
    this.onStreamingTextLayoutChanged,
    this.textScale = 1,
    this.textColor = const Color(0x80353E53),
    this.showStatusAvatar = true,
  });

  @override
  State<DeepThinkingCard> createState() => _DeepThinkingCardState();
}

class _DeepThinkingCardState extends State<DeepThinkingCard>
    with SingleTickerProviderStateMixin {
  static const Duration _collapseDuration = Duration(milliseconds: 170);
  static const Cubic _collapseCurve = Cubic(0.22, 1.0, 0.36, 1.0);
  static const Cubic _expandCurve = Cubic(0.2, 0.8, 0.2, 1.0);
  Timer? _timer;
  int _elapsedSeconds = 0;
  final ScrollController _scrollController = ScrollController();
  final Set<int> _heldPointerIds = <int>{};
  bool _showGradient = false;
  bool _gradientUpdateScheduled = false;
  bool? _pendingGradientVisibility;
  bool _isCollapsed = false;
  bool _autoScrollToLatest = true;
  bool _hasAutoCollapsedForCurrentCompletion = false;
  bool _followParentDuringCollapseAnimation = false;
  late final AnimationController _collapseController;
  late Animation<double> _collapseSizeFactor;
  late Animation<double> _collapseOpacity;
  static const double _bottomTolerance = 1.0;

  @override
  void initState() {
    super.initState();
    _hasAutoCollapsedForCurrentCompletion = _shouldAutoCollapse(widget);
    _isCollapsed = _hasAutoCollapsedForCurrentCompletion;
    _collapseController = AnimationController(
      vsync: this,
      duration: _collapseDuration,
      reverseDuration: _collapseDuration,
      value: _isCollapsed ? 0.0 : 1.0,
    );
    _rebuildCollapseAnimations();
    _collapseController.addListener(_handleCollapseAnimationTick);
    _collapseController.addStatusListener(
      _handleCollapseAnimationStatusChanged,
    );
    _updateElapsedTime(notify: false);
    if (_isActivelyThinking(widget)) {
      _startTimer();
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _scrollToLatestIfNeeded(force: true);
      _checkOverflow();
    });
  }

  @override
  void didUpdateWidget(DeepThinkingCard oldWidget) {
    super.didUpdateWidget(oldWidget);

    // 更新已用时间
    _updateElapsedTime();

    final becameCompleted =
        _isActivelyThinking(oldWidget) && !_isActivelyThinking(widget);
    final becameThinking =
        !_isActivelyThinking(oldWidget) && _isActivelyThinking(widget);
    final completionSettled =
        _shouldAutoCollapse(widget) &&
        (!_shouldAutoCollapse(oldWidget) ||
            oldWidget.isLoading != widget.isLoading ||
            oldWidget.isCollapsible != widget.isCollapsible ||
            oldWidget.autoCollapseOnComplete != widget.autoCollapseOnComplete);
    if (becameCompleted) {
      _stopTimer();
    }

    if (becameThinking) {
      _startTimer();
      _autoScrollToLatest = true;
      _hasAutoCollapsedForCurrentCompletion = false;
    }

    if (completionSettled && !_hasAutoCollapsedForCurrentCompletion) {
      _setCollapsed(
        true,
        markCompletionHandled: true,
        followParentDuringAnimation: true,
      );
    } else if (becameThinking && _isCollapsed) {
      _setCollapsed(false);
    }

    final textChanged = widget.thinkingText != oldWidget.thinkingText;
    final layoutChanged =
        textChanged ||
        widget.stage != oldWidget.stage ||
        widget.isLoading != oldWidget.isLoading ||
        widget.isCollapsible != oldWidget.isCollapsible;

    // 内容更新后自动跟随到最新文本，并更新渐变遮罩
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _scrollToLatestIfNeeded(force: textChanged);
      _checkOverflow();
      if (layoutChanged) {
        widget.onStreamingTextLayoutChanged?.call();
      }
    });
  }

  /// 计算已用时间
  void _updateElapsedTime({bool notify = true}) {
    final nextElapsedSeconds = widget.startTime == null
        ? 0
        : (widget.endTime != null
                  ? DateTime.fromMillisecondsSinceEpoch(
                      widget.endTime!,
                    ).difference(
                      DateTime.fromMillisecondsSinceEpoch(widget.startTime!),
                    )
                  : DateTime.now().difference(
                      DateTime.fromMillisecondsSinceEpoch(widget.startTime!),
                    ))
              .inSeconds;

    if (nextElapsedSeconds == _elapsedSeconds) return;

    if (!notify || !mounted) {
      _elapsedSeconds = nextElapsedSeconds;
      return;
    }

    setState(() {
      _elapsedSeconds = nextElapsedSeconds;
    });
  }

  void _startTimer() {
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(seconds: 1), (timer) {
      _updateElapsedTime();
    });
  }

  void _stopTimer() {
    _timer?.cancel();
    _timer = null;
  }

  void _checkOverflow() {
    if (!_scrollController.hasClients) return;
    final position = _scrollController.position;
    final maxExtent = position.maxScrollExtent;
    final hasOverflow = maxExtent > 0;
    final distanceToBottom = (maxExtent - position.pixels).clamp(
      0.0,
      maxExtent,
    );
    final isAtBottom = distanceToBottom <= _bottomTolerance;
    final shouldShowGradient = hasOverflow && !isAtBottom;

    if (shouldShowGradient == _showGradient &&
        _pendingGradientVisibility == null) {
      return;
    }

    _pendingGradientVisibility = shouldShowGradient;
    if (_gradientUpdateScheduled) {
      return;
    }

    _gradientUpdateScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _gradientUpdateScheduled = false;
      final nextVisibility = _pendingGradientVisibility;
      _pendingGradientVisibility = null;

      if (!mounted ||
          nextVisibility == null ||
          nextVisibility == _showGradient) {
        return;
      }

      setState(() => _showGradient = nextVisibility);
    });
  }

  bool _isInnerNearLatest([ScrollMetrics? metrics]) {
    final resolvedMetrics = metrics;
    if (resolvedMetrics != null) {
      return (resolvedMetrics.maxScrollExtent - resolvedMetrics.pixels).abs() <=
          _bottomTolerance;
    }
    if (!_scrollController.hasClients) {
      return true;
    }
    final position = _scrollController.position;
    return (position.maxScrollExtent - position.pixels).abs() <=
        _bottomTolerance;
  }

  void _scrollToLatestIfNeeded({bool force = false}) {
    if (!mounted || !_scrollController.hasClients) return;
    if (_isCollapsed || widget.stage == 5) return;
    if (!force && widget.stage == 4) return;
    if (!_autoScrollToLatest) return;

    final position = _scrollController.position;
    final maxExtent = position.maxScrollExtent;
    if (maxExtent <= 0) return;

    final current = position.pixels.clamp(0.0, maxExtent);
    if ((maxExtent - current).abs() <= _bottomTolerance) return;

    _scrollController.jumpTo(maxExtent);
  }

  void _toggleCollapsed() {
    if (!widget.isCollapsible || widget.stage != 4) return;
    widget.onParentScrollHandoff?.call();
    _setCollapsed(
      !_isCollapsed,
      markCompletionHandled: _shouldAutoCollapse(widget),
    );
  }

  void _setCollapsed(
    bool collapsed, {
    bool markCompletionHandled = false,
    bool followParentDuringAnimation = false,
  }) {
    if (_isCollapsed == collapsed) {
      if (markCompletionHandled) {
        _hasAutoCollapsedForCurrentCompletion = true;
      }
      return;
    }

    setState(() {
      _isCollapsed = collapsed;
      if (markCompletionHandled) {
        _hasAutoCollapsedForCurrentCompletion = true;
      }
    });

    _followParentDuringCollapseAnimation =
        collapsed && followParentDuringAnimation;
    _collapseController.stop();
    _rebuildCollapseAnimations();
    if (collapsed) {
      _collapseController.reverse();
    } else {
      _collapseController.forward();
    }

    if (!collapsed && _shouldResetScrollPositionOnExpand()) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted || !_scrollController.hasClients) {
          return;
        }
        final position = _scrollController.position;
        final top = position.minScrollExtent;
        if ((position.pixels - top).abs() > _bottomTolerance) {
          _scrollController.jumpTo(top);
        }
        _checkOverflow();
      });
    }
  }

  void _rebuildCollapseAnimations() {
    _collapseSizeFactor = CurvedAnimation(
      parent: _collapseController,
      curve: _isCollapsed ? _collapseCurve : _expandCurve,
      reverseCurve: _collapseCurve,
    );
    _collapseOpacity = CurvedAnimation(
      parent: _collapseController,
      curve: _isCollapsed
          ? const Interval(0.0, 0.72, curve: Curves.easeOut)
          : const Interval(0.16, 1.0, curve: Curves.easeOut),
      reverseCurve: const Interval(0.16, 1.0, curve: Curves.easeOut),
    );
  }

  void _handleCollapseAnimationTick() {
    if (!mounted || !_followParentDuringCollapseAnimation) {
      return;
    }
    widget.onStreamingTextLayoutChanged?.call();
  }

  void _handleCollapseAnimationStatusChanged(AnimationStatus status) {
    if (status == AnimationStatus.completed ||
        status == AnimationStatus.dismissed) {
      final shouldNotifyParent = _followParentDuringCollapseAnimation;
      _followParentDuringCollapseAnimation = false;
      if (mounted && shouldNotifyParent) {
        widget.onStreamingTextLayoutChanged?.call();
      }
    }
  }

  bool _shouldAutoCollapse(DeepThinkingCard widget) {
    return widget.autoCollapseOnComplete &&
        widget.isCollapsible &&
        widget.stage == 4 &&
        !widget.isLoading;
  }

  bool _shouldResetScrollPositionOnExpand() {
    return widget.stage == 4 && widget.isCollapsible;
  }

  bool _isCompletedStage(int stage) => stage == 4 || stage == 5;

  bool _isActivelyThinking(DeepThinkingCard widget) {
    return widget.isLoading && !_isCompletedStage(widget.stage);
  }

  @override
  void dispose() {
    _timer?.cancel();
    _releaseHeldPointers();
    _collapseController
      ..removeListener(_handleCollapseAnimationTick)
      ..removeStatusListener(_handleCollapseAnimationStatusChanged)
      ..dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _handleContentPointerDown(int pointer) {
    if (_heldPointerIds.add(pointer)) {
      ToolCardDetailGestureGate.holdPointer(pointer);
    }
  }

  void _handleContentPointerEnd(int pointer) {
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

  String _formatTime(int seconds) {
    if (seconds < 60) {
      return LegacyTextLocalizer.localize('$seconds 秒');
    } else {
      final minutes = seconds ~/ 60;
      final remainingSeconds = seconds % 60;
      return LegacyTextLocalizer.localize('$minutes 分 $remainingSeconds 秒');
    }
  }

  /// 构建文本显示（逐字透出动画）
  Widget _buildText(String text, Color textColor) {
    final localizedText = text
        .split('\n')
        .map(LegacyTextLocalizer.localize)
        .join('\n');
    return OmnibotPacedRevealText(
      key: const ValueKey('deep-thinking-paced-reveal'),
      text: localizedText,
      style: TextStyle(
        color: textColor,
        fontSize: 12 * widget.textScale,
        fontFamily: 'PingFang SC',
        fontWeight: FontWeight.w400,
        height: 1.50,
        letterSpacing: 0.33,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final hasContent = widget.thinkingText.trim().isNotEmpty;
    if (!hasContent && !_isActivelyThinking(widget)) {
      return const SizedBox.shrink();
    }

    final palette = context.omniPalette;
    final parentScrollPosition = _resolveParentScrollPosition(context);
    final resolvedTextColor = context.isDarkTheme
        ? palette.textPrimary
        : widget.textColor;
    final secondaryTextColor = context.isDarkTheme
        ? palette.textSecondary
        : resolvedTextColor.withValues(alpha: 0.68);
    final bool canCollapse = widget.isCollapsible && widget.stage == 4;
    // The elapsed value is a completed-thinking measurement, not a live
    // progress counter. Showing it while the model is still emitting thought
    // chunks produced misleading labels such as “思考完成（用时 1 秒）” for a
    // turn whose reasoning had not ended yet.
    final completedThinking =
        widget.stage == 4 && !widget.isLoading && widget.endTime != null;

    // 根据阶段显示不同的文案
    String hintText;
    switch (widget.stage) {
      case 1:
        hintText = LegacyTextLocalizer.localize('正在思考');
        break;
      case 2:
        hintText = LegacyTextLocalizer.localize('正在思考');
        break;
      case 3:
        hintText = LegacyTextLocalizer.localize('正在思考');
        break;
      case 4:
      case 5:
        hintText = LegacyTextLocalizer.localize('完成思考');
        break;
      default:
        hintText = LegacyTextLocalizer.localize('正在思考');
    }

    final header = canCollapse && hasContent
        ? InkWell(
            onTap: _toggleCollapsed,
            borderRadius: BorderRadius.circular(8),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 2),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    BotStatus(
                      status: (widget.stage == 4 || widget.stage == 5)
                          ? BotStatusType.completed
                          : BotStatusType.hint,
                      hintText: hintText,
                      costTime: completedThinking
                          ? _formatTime(_elapsedSeconds)
                          : null,
                      showAvatar: widget.showStatusAvatar,
                      // Keep the existing animation contract for early
                      // stages; stage 4 is the hand-off boundary and must not
                      // create an endless test/runtime animation while its
                      // terminal state is still being committed.
                      shimmerText: widget.stage != 4 && widget.stage != 5,
                      textStyle: TextStyle(
                        color: secondaryTextColor,
                        fontSize: 12 * widget.textScale,
                        fontFamily: 'PingFang SC',
                        fontWeight: FontWeight.w400,
                        height: 1.50,
                        letterSpacing: 0.33,
                      ),
                    ),
                    const SizedBox(width: 2),
                    AnimatedBuilder(
                      animation: _collapseController,
                      builder: (context, child) {
                        return Transform.rotate(
                          angle: (1 - _collapseController.value) * math.pi,
                          child: child,
                        );
                      },
                      child: Icon(
                        Icons.keyboard_arrow_down_rounded,
                        size: 16,
                        color: secondaryTextColor,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          )
        : BotStatus(
            status: (widget.stage == 4 || widget.stage == 5)
                ? BotStatusType.completed
                : BotStatusType.hint,
            hintText: hintText,
            costTime: completedThinking ? _formatTime(_elapsedSeconds) : null,
            showAvatar: widget.showStatusAvatar,
            shimmerText: widget.stage != 4 && widget.stage != 5,
            textStyle: TextStyle(
              color: secondaryTextColor,
              fontSize: 12 * widget.textScale,
              fontFamily: 'PingFang SC',
              fontWeight: FontWeight.w400,
              height: 1.50,
              letterSpacing: 0.33,
            ),
          );
    final contentChild = (hasContent && widget.stage != 5)
        ? Container(
            width: double.infinity,
            constraints: BoxConstraints(maxHeight: widget.maxHeight),
            margin: const EdgeInsets.only(top: 8.0),
            decoration: BoxDecoration(
              border: Border(
                left: BorderSide(
                  color: context.isDarkTheme
                      ? palette.borderSubtle
                      : AppColors.text10,
                  width: 1.0,
                ),
              ),
            ),
            child: Stack(
              children: [
                Listener(
                  behavior: HitTestBehavior.opaque,
                  onPointerDown: (event) =>
                      _handleContentPointerDown(event.pointer),
                  onPointerUp: (event) =>
                      _handleContentPointerEnd(event.pointer),
                  onPointerCancel: (event) =>
                      _handleContentPointerEnd(event.pointer),
                  child: NotificationListener<ScrollNotification>(
                    onNotification: (notification) {
                      return _handleThinkingScrollNotification(
                        notification,
                        parentScrollPosition,
                      );
                    },
                    child: SingleChildScrollView(
                      controller: _scrollController,
                      physics: const ClampingScrollPhysics(),
                      child: Padding(
                        padding: const EdgeInsets.only(left: 12.0),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            _buildText(widget.thinkingText, resolvedTextColor),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
                if (_showGradient)
                  Positioned(
                    left: 0,
                    right: 0,
                    bottom: 0,
                    height: 40,
                    child: IgnorePointer(
                      child: Container(
                        decoration: BoxDecoration(
                          borderRadius: const BorderRadius.only(
                            bottomLeft: Radius.circular(4),
                            bottomRight: Radius.circular(4),
                          ),
                          gradient: LinearGradient(
                            begin: Alignment.topCenter,
                            end: Alignment.bottomCenter,
                            colors: [
                              (context.isDarkTheme
                                      ? palette.surfacePrimary
                                      : const Color(0xCCF1F8FF))
                                  .withValues(alpha: 0.0),
                              (context.isDarkTheme
                                      ? palette.surfacePrimary
                                      : const Color(0xCCF1F8FF))
                                  .withValues(alpha: 0.8),
                              context.isDarkTheme
                                  ? palette.surfacePrimary
                                  : const Color(0xCCF1F8FF),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          )
        : const SizedBox.shrink();
    final content = canCollapse
        ? AnimatedBuilder(
            animation: _collapseController,
            child: RepaintBoundary(child: contentChild),
            builder: (context, child) {
              final sizeFactor = _collapseSizeFactor.value.clamp(0.0, 1.0);
              final opacity = _collapseOpacity.value.clamp(0.0, 1.0);
              if (sizeFactor <= 0.001 && !_collapseController.isAnimating) {
                return const SizedBox.shrink();
              }
              return ClipRect(
                child: Align(
                  alignment: Alignment.topCenter,
                  heightFactor: sizeFactor,
                  child: IgnorePointer(
                    ignoring: sizeFactor <= 0.001,
                    child: Opacity(opacity: opacity, child: child),
                  ),
                ),
              );
            },
          )
        : contentChild;
    final footer = widget.stage == 4 && widget.isExecutable
        ? Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  LegacyTextLocalizer.localize('准备执行任务...'),
                  style: TextStyle(
                    color: secondaryTextColor,
                    fontSize: 12 * widget.textScale,
                    fontFamily: 'PingFang SC',
                    fontWeight: FontWeight.w500,
                    height: 1.67,
                  ),
                ),
                GestureDetector(
                  onTap: widget.taskId != null && widget.onCancelTask != null
                      ? () => widget.onCancelTask!(widget.taskId!)
                      : null,
                  child: Text(
                    LegacyTextLocalizer.localize('取消任务'),
                    style: TextStyle(
                      color: context.isDarkTheme
                          ? palette.accentPrimary
                          : const Color(0xFF576B95),
                      fontSize: 12 * widget.textScale,
                      fontFamily: 'PingFang SC',
                      fontWeight: FontWeight.w500,
                      height: 1.50,
                    ),
                  ),
                ),
              ],
            ),
          )
        : widget.stage == 5
        ? Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(
              LegacyTextLocalizer.localize('任务已取消'),
              style: TextStyle(
                color: secondaryTextColor,
                fontSize: 12 * widget.textScale,
                fontFamily: 'PingFang SC',
                fontWeight: FontWeight.w500,
                height: 1.83,
              ),
            ),
          )
        : null;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [header, content, if (footer != null) footer],
    );
  }

  ScrollPosition? _resolveParentScrollPosition(BuildContext context) {
    final inheritedPosition = Scrollable.maybeOf(context)?.position;
    if (inheritedPosition != null) {
      return inheritedPosition;
    }
    final controller = widget.parentScrollController;
    if (controller == null || !controller.hasClients) {
      return null;
    }
    final positions = controller.positions.toList(growable: false);
    if (positions.isEmpty) {
      return null;
    }
    return positions.first;
  }

  bool _handleThinkingScrollNotification(
    ScrollNotification notification,
    ScrollPosition? parentPosition,
  ) {
    _checkOverflow();
    final isUserDrivenUpdate =
        (notification is ScrollUpdateNotification &&
            notification.dragDetails != null) ||
        (notification is OverscrollNotification &&
            notification.dragDetails != null);
    if (isUserDrivenUpdate) {
      _autoScrollToLatest = _isInnerNearLatest(notification.metrics);
    } else if (notification is ScrollEndNotification &&
        _isInnerNearLatest(notification.metrics)) {
      _autoScrollToLatest = true;
    }
    return _forwardOverscrollToParent(notification, parentPosition);
  }

  bool _forwardOverscrollToParent(
    ScrollNotification notification,
    ScrollPosition? parentPosition,
  ) {
    if (parentPosition == null ||
        !parentPosition.hasPixels ||
        notification is! OverscrollNotification ||
        notification.dragDetails == null) {
      return false;
    }

    final overscroll = notification.overscroll;
    if (overscroll.abs() < 0.5) {
      return false;
    }

    final pointerDelta = _scrollDeltaToPointerDelta(
      overscroll,
      notification.metrics.axisDirection,
    );
    final parentDelta = _pointerDeltaToScrollDelta(
      pointerDelta,
      parentPosition.axisDirection,
    );
    if (parentDelta.abs() < 0.5) {
      return false;
    }

    final current = parentPosition.pixels;
    final min = parentPosition.minScrollExtent;
    final max = parentPosition.maxScrollExtent;
    final next = (current + parentDelta).clamp(min, max).toDouble();
    if ((next - current).abs() < 0.5) {
      return false;
    }

    widget.onParentScrollHandoff?.call();
    parentPosition.jumpTo(next);
    return true;
  }

  double _scrollDeltaToPointerDelta(
    double scrollDelta,
    AxisDirection axisDirection,
  ) {
    return switch (axisDirection) {
      AxisDirection.down || AxisDirection.right => -scrollDelta,
      AxisDirection.up || AxisDirection.left => scrollDelta,
    };
  }

  double _pointerDeltaToScrollDelta(
    double pointerDelta,
    AxisDirection axisDirection,
  ) {
    return switch (axisDirection) {
      AxisDirection.down || AxisDirection.right => -pointerDelta,
      AxisDirection.up || AxisDirection.left => pointerDelta,
    };
  }
}
