import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_avatar_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/agent_avatar.dart';
import 'package:ui/widgets/agent_brand_icon.dart';

import '../chat_page_models.dart';
import '../utils/agent_run_timeline.dart';

/// 消息锚点：一条可跳转的时间线入口（用户消息 / AI 消息 / Agent 运行组）。
class ChatMessageAnchor {
  const ChatMessageAnchor({
    required this.entryKey,
    required this.isUser,
    required this.preview,
    this.agentId,
  });

  /// 对应 [AgentRunTimelineEntry.key]，用于消息列表跳转。
  final String entryKey;
  final bool isUser;

  /// ACP Agent identity for this turn. Null keeps the built-in Xiaowan avatar.
  final String? agentId;

  /// 锚点头像下方展示的消息首行文字。
  final String preview;
}

/// 从消息列表构建锚点（按时间正序，旧→新），与 [buildAgentRunTimelineEntries]
/// 的分组一致：普通用户/AI 文本消息各一个锚点，一次 Agent 运行折叠为一个锚点。
List<ChatMessageAnchor> buildChatMessageAnchors(
  List<ChatMessageModel> messages, {
  Set<String> activeTaskIds = const <String>{},
  String? conversationAgentId,
}) {
  if (messages.isEmpty) {
    return const <ChatMessageAnchor>[];
  }
  final entries = buildAgentRunTimelineEntries(
    List<ChatMessageModel>.from(messages),
    activeTaskIds: activeTaskIds,
    conversationAgentId: conversationAgentId,
  );
  final anchors = <ChatMessageAnchor>[];
  for (final entry in entries) {
    final message = entry.message;
    if (message != null) {
      if (message.type != 1 || (message.user != 1 && message.user != 2)) {
        continue;
      }
      final preview = _firstPreviewLine(message.text);
      if (preview.isEmpty) {
        continue;
      }
      anchors.add(
        ChatMessageAnchor(
          entryKey: entry.key,
          isUser: message.user == 1,
          preview: preview,
          agentId: message.user == 2
              ? _resolvedAnchorAgentId(
                  turnMessages: <ChatMessageModel>[message],
                  conversationAgentId: conversationAgentId,
                )
              : null,
        ),
      );
      continue;
    }
    final group = entry.group;
    // A dispatched turn that has not produced anything yet has nothing to
    // anchor to.
    if (group == null || group.isEmpty) {
      continue;
    }
    var preview = '';
    for (final visible in group.visibleMessagesNewestFirst) {
      preview = _firstPreviewLine(visible.text);
      if (preview.isNotEmpty) {
        break;
      }
    }
    if (preview.isEmpty) {
      preview = LegacyTextLocalizer.isEnglish ? 'Working…' : '思考中…';
    }
    anchors.add(
      ChatMessageAnchor(
        entryKey: entry.key,
        isUser: false,
        preview: preview,
        agentId: group.agentId == kGenericAgentId ? null : group.agentId,
      ),
    );
  }
  // entries 为新→旧，锚点按时间正序（旧→新）返回。
  return anchors.reversed.toList(growable: false);
}

String? _resolvedAnchorAgentId({
  required Iterable<ChatMessageModel> turnMessages,
  String? conversationAgentId,
}) {
  final agentId = resolveAgentRunAgentId(
    turnMessages: turnMessages,
    conversationAgentId: conversationAgentId,
  );
  return agentId == kGenericAgentId ? null : agentId;
}

String _firstPreviewLine(String? text) {
  final normalized = text?.trim() ?? '';
  if (normalized.isEmpty) {
    return '';
  }
  for (final line in normalized.split('\n')) {
    final candidate = line.trim();
    if (candidate.isEmpty) {
      continue;
    }
    // Cherry Studio 的锚点正文取前 50 字符后省略。
    return candidate.length > 50 ? candidate.substring(0, 50) : candidate;
  }
  return '';
}

const double _kAnchorButtonSize = 34.0;

/// Stable test/accessibility hook for the modal spotlight behind the expanded
/// message-anchor fan.
const ValueKey<String> chatMessageAnchorScrimKey = ValueKey<String>(
  'chat-message-anchor-scrim',
);
const ValueKey<String> chatMessageAnchorSystemBarsScrimKey = ValueKey<String>(
  'chat-message-anchor-system-bars-scrim',
);
const ValueKey<String> chatMessageAnchorStatusBarScrimKey = ValueKey<String>(
  'chat-message-anchor-status-bar-scrim',
);
const ValueKey<String> chatMessageAnchorNavigationBarScrimKey =
    ValueKey<String>('chat-message-anchor-navigation-bar-scrim');

Color chatMessageAnchorScrimColor(BuildContext context) {
  return Colors.black.withValues(alpha: context.isDarkTheme ? 0.64 : 0.46);
}

/// Extends the anchor spotlight into the status-bar and gesture-navigation
/// insets that sit outside the chat page's [SafeArea]. The annotation also
/// switches system foregrounds to light while the dark scrim is visible.
class ChatMessageAnchorSystemBarsScrim extends StatelessWidget {
  const ChatMessageAnchorSystemBarsScrim({super.key, required this.expanded});

  final bool expanded;

  @override
  Widget build(BuildContext context) {
    final viewPadding = MediaQuery.viewPaddingOf(context);
    final overlayStyle = AppTheme.overlayStyleForBrightness(
      expanded ? Brightness.dark : Theme.of(context).brightness,
    );
    final duration = Duration(milliseconds: expanded ? 360 : 240);
    // AnimatedOpacity's curve is evaluated on forward progress even while its
    // target falls from 1 to 0. easeOut therefore matches the center scrim's
    // reverse controller value transformed by reverseCurve=easeIn.
    const curve = Curves.easeOutCubic;
    final color = chatMessageAnchorScrimColor(context);

    return AnnotatedRegion<SystemUiOverlayStyle>(
      key: chatMessageAnchorSystemBarsScrimKey,
      value: overlayStyle,
      child: IgnorePointer(
        child: Stack(
          fit: StackFit.expand,
          children: [
            if (viewPadding.top > 0)
              Positioned(
                left: 0,
                top: 0,
                right: 0,
                height: viewPadding.top,
                child: AnimatedOpacity(
                  key: chatMessageAnchorStatusBarScrimKey,
                  opacity: expanded ? 1 : 0,
                  duration: duration,
                  curve: curve,
                  child: ColoredBox(color: color),
                ),
              ),
            if (viewPadding.bottom > 0)
              Positioned(
                left: 0,
                right: 0,
                bottom: 0,
                height: viewPadding.bottom,
                child: AnimatedOpacity(
                  key: chatMessageAnchorNavigationBarScrimKey,
                  opacity: expanded ? 1 : 0,
                  duration: duration,
                  curve: curve,
                  child: ColoredBox(color: color),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// 圆环扇形布局：以按钮为圆心，锚点落在从正上方（-90°）到正左方（-180°）
/// 的四分之一圆弧上，一屏 5 个槽位；更早的锚点通过旋转圆环露出。
const double _kFanRadius = 132.0;
const int _kFanVisibleSlots = 5;
const double _kFanSlotAngle = (math.pi / 2) / (_kFanVisibleSlots - 1);
const double _kFanTopAngle = -math.pi / 2;

/// 锚点命中环带（手指与圆心距离在该范围内才算落在圆环上）。
const double _kFanHitBandInner = _kFanRadius - 58.0;
const double _kFanHitBandOuter = _kFanRadius + 62.0;

/// 悬浮层画布尺寸：容纳半径 + 头像放大 + 文字标签。
const double _kFanCanvasExtent = 208.0;

const double _kAnchorAvatarSize = 26.0;

/// Cherry Studio dock 放大：按角向距离（槽位单位）线性衰减。
const double _kAnchorMagnifyScale = 0.32;
const double _kAnchorMagnifySpanSlots = 1.15;

/// Cherry Studio MessageAnchorLine 的过渡曲线 cubic-bezier(0.25, 1, 0.5, 1)。
const Cubic _kAnchorEaseOutQuart = Cubic(0.25, 1, 0.5, 1);

/// 发散动画：相邻槽位的错峰步长与单个锚点的动画区间跨度。
const double _kFanStaggerStep = 0.07;
const double _kFanStaggerMaxStart = 0.42;
const double _kFanRevealSpan = 0.58;

/// 静态透明度：沿弧离最新锚点越远（越早的消息）越淡（Cherry 的距离梯度）。
const double _kFanIdleOpacityStep = 0.11;
const double _kFanIdleOpacityFloor = 0.5;

/// 悬浮在聊天输入框右上角的消息锚点导航：
/// 点击 gallery-vertical-end 按钮后，锚点以按钮为圆心向左上方散开成一段圆环
/// （每个锚点为消息头像 + 首行文字），时序沿弧从上往下、从右往左：
/// 弧顶（按钮正上方）是可见窗口中最早的消息，越往左下越新。
/// 点按锚点平滑跳转到对应消息；锚点多于一屏时可沿圆环拖动旋转翻阅更早的
/// 历史。也支持长按按钮后直接滑到锚点上，就近锚点 dock 式放大，松手即跳转。
class ChatMessageAnchorBar extends StatefulWidget {
  const ChatMessageAnchorBar({
    super.key,
    required this.messages,
    required this.activeAgentTaskIds,
    required this.conversationSignature,
    required this.bottomInset,
    required this.visible,
    required this.onJumpToEntry,
    this.conversationAgentId,
    this.onExpandedChanged,
  });

  final List<ChatMessageModel> messages;
  final Set<String> activeAgentTaskIds;

  /// ACP Agent bound to this conversation. Individual message identities win;
  /// this fills older ACP history that predates per-message Agent metadata.
  final String? conversationAgentId;

  /// 会话/模式标识，变化时立即收起面板。
  final String conversationSignature;

  /// 按钮底边距 Stack 底部的距离（含 composer 与其上方条带的高度，
  /// 由父层逐帧传入，随键盘升降保持贴合）。
  final double bottomInset;
  final bool visible;
  final Future<bool> Function(String entryKey) onJumpToEntry;

  /// 面板展开态变化回调（展开为 true）。父层据此在展开期间关闭 Scaffold 抽屉的
  /// 边缘侧滑手势，避免展开锚点后从左往右轻滑误触发 home_drawer。
  final ValueChanged<bool>? onExpandedChanged;

  @override
  State<ChatMessageAnchorBar> createState() => _ChatMessageAnchorBarState();
}

class _ChatMessageAnchorBarState extends State<ChatMessageAnchorBar>
    with SingleTickerProviderStateMixin {
  late final AnimationController _expandController = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 360),
    reverseDuration: const Duration(milliseconds: 240),
  );
  late final Animation<double> _scrimOpacity = CurvedAnimation(
    parent: _expandController,
    curve: Curves.easeOutCubic,
    reverseCurve: Curves.easeInCubic,
  );
  final GlobalKey _fanBoxKey = GlobalKey();

  bool _expanded = false;
  bool _buttonPressed = false;
  bool _dragSelecting = false;

  /// 手指在圆环上的槽位坐标（含旋转量），null 表示未在选择。
  double? _dragSlotPosition;
  int? _activeDragIndex;

  /// 圆环旋转量（槽位单位）。锚点按时间正序排列（index 0 最早），
  /// item i 落在槽位 i - _ringScroll：取最大值时最新的一屏锚点可见（默认），
  /// 减小旋转量则把更早的锚点转到弧上。
  double _ringScroll = 0;
  double? _panLastSlotPosition;

  ObservableChatMessageList? _observableMessages;
  List<ChatMessageAnchor> _anchorsCache = const <ChatMessageAnchor>[];
  List<ChatMessageModel>? _anchorsCacheSource;
  int _anchorsCacheRevision = -1;
  int _anchorsCacheLength = -1;
  Set<String>? _anchorsCacheTaskIds;
  String? _anchorsCacheConversationAgentId;

  @override
  void initState() {
    super.initState();
    _bindObservableMessages(widget.messages);
    AgentAvatarService.ensureLoaded();
  }

  @override
  void didUpdateWidget(covariant ChatMessageAnchorBar oldWidget) {
    super.didUpdateWidget(oldWidget);
    _bindObservableMessages(widget.messages);
    if (oldWidget.conversationSignature != widget.conversationSignature) {
      _collapseImmediately();
    } else if (!widget.visible && _expanded) {
      _setExpanded(false);
    }
  }

  @override
  void dispose() {
    // 展开态被拆除时补发一次收起，避免父层的抽屉侧滑一直被关闭。
    if (_expanded) {
      final callback = widget.onExpandedChanged;
      if (callback != null) {
        SchedulerBinding.instance.addPostFrameCallback((_) => callback(false));
      }
    }
    _observableMessages?.removeListener(_handleObservableMessagesChanged);
    _expandController.dispose();
    super.dispose();
  }

  /// 通知父层展开态变化。build 阶段（如 didUpdateWidget）触发时延后到帧末，
  /// 避免在父层 build 期间同步调用其 setState。
  void _notifyExpandedChanged(bool value) {
    final callback = widget.onExpandedChanged;
    if (callback == null) {
      return;
    }
    if (SchedulerBinding.instance.schedulerPhase ==
        SchedulerPhase.persistentCallbacks) {
      SchedulerBinding.instance.addPostFrameCallback((_) {
        if (mounted) {
          callback(value);
        }
      });
    } else {
      callback(value);
    }
  }

  void _bindObservableMessages(List<ChatMessageModel> messages) {
    final nextObservable = messages is ObservableChatMessageList
        ? messages
        : null;
    if (identical(_observableMessages, nextObservable)) {
      return;
    }
    _observableMessages?.removeListener(_handleObservableMessagesChanged);
    _observableMessages = nextObservable;
    _observableMessages?.addListener(_handleObservableMessagesChanged);
  }

  void _handleObservableMessagesChanged() {
    if (!mounted) {
      return;
    }
    // 流式追加正文属于 content 级变更，不影响锚点结构，跳过重建。
    if (_observableMessages?.lastMutationKind ==
        ChatMessageListMutationKind.content) {
      return;
    }
    setState(() {});
    if (_expanded && _resolveAnchors().isEmpty) {
      _setExpanded(false);
    }
  }

  List<ChatMessageAnchor> _resolveAnchors() {
    final source = widget.messages;
    final observable = source is ObservableChatMessageList ? source : null;
    final revision = observable?.structureRevision ?? -1;
    // observable 源按 structureRevision 判缓存；普通 List 源按实例 + 长度
    // 兜底（该路径只出现在会话 runtime 建立前，通常为空列表）。
    if (identical(_anchorsCacheSource, source) &&
        _anchorsCacheRevision == revision &&
        _anchorsCacheLength == source.length &&
        setEquals(_anchorsCacheTaskIds, widget.activeAgentTaskIds) &&
        _anchorsCacheConversationAgentId == widget.conversationAgentId) {
      return _anchorsCache;
    }
    final anchors = buildChatMessageAnchors(
      source,
      activeTaskIds: widget.activeAgentTaskIds,
      conversationAgentId: widget.conversationAgentId,
    );
    _anchorsCache = anchors;
    _anchorsCacheSource = source;
    _anchorsCacheRevision = revision;
    _anchorsCacheLength = source.length;
    _anchorsCacheTaskIds = Set<String>.from(widget.activeAgentTaskIds);
    _anchorsCacheConversationAgentId = widget.conversationAgentId;
    return anchors;
  }

  double get _maxRingScroll {
    final anchorCount = _resolveAnchors().length;
    return math.max(0, anchorCount - _kFanVisibleSlots).toDouble();
  }

  void _setExpanded(bool next) {
    if (_expanded == next) {
      return;
    }
    setState(() {
      _expanded = next;
      if (next) {
        // 默认露出最新的一屏锚点（时间正序，最新在弧的左下端）。
        _ringScroll = _maxRingScroll;
      } else {
        _clearDragState(notify: false);
      }
    });
    if (next) {
      _expandController.forward();
    } else {
      _expandController.reverse();
    }
    _notifyExpandedChanged(next);
  }

  void _collapseImmediately() {
    final wasExpanded = _expanded;
    _clearDragState(notify: false);
    _expanded = false;
    _ringScroll = 0;
    _expandController.value = 0;
    if (wasExpanded) {
      _notifyExpandedChanged(false);
    }
  }

  void _clearDragState({bool notify = true}) {
    if (!_dragSelecting &&
        _dragSlotPosition == null &&
        _activeDragIndex == null) {
      return;
    }
    void reset() {
      _dragSelecting = false;
      _dragSlotPosition = null;
      _activeDragIndex = null;
    }

    if (notify && mounted) {
      setState(reset);
    } else {
      reset();
    }
  }

  Future<void> _jumpToAnchor(ChatMessageAnchor anchor) async {
    HapticFeedback.lightImpact();
    _setExpanded(false);
    await widget.onJumpToEntry(anchor.entryKey);
  }

  // ==================== 极坐标换算 ====================

  /// 画布内圆心 = 按钮中心（画布右下角）。
  static const Offset _fanCenter = Offset(_kFanCanvasExtent, _kFanCanvasExtent);

  /// 槽位 s 的极角：s=0 正上方，s=4 正左方；随 s 增大逆时针（屏幕上向左下）。
  double _slotAngle(double slotPosition) {
    return _kFanTopAngle - slotPosition * _kFanSlotAngle;
  }

  /// 锚点 index 当前所在的槽位坐标（受圆环旋转影响）。
  double _slotPositionOf(int index) => index - _ringScroll;

  /// 手指画布坐标 → 圆环槽位坐标与到圆心的距离。
  ({double slotPosition, double distance}) _fingerRingPosition(
    Offset canvasLocal,
  ) {
    final v = canvasLocal - _fanCenter;
    final distance = v.distance;
    var angle = math.atan2(v.dy, v.dx);
    // 圆弧位于 (-π, -π/2)（左上象限）；下半平面向最近的弧端收敛。
    if (angle > 0) {
      angle = angle > math.pi / 2 ? -math.pi : _kFanTopAngle;
    }
    final slotPosition = (_kFanTopAngle - angle) / _kFanSlotAngle;
    return (slotPosition: slotPosition, distance: distance);
  }

  bool _isWithinRingBand(double distance) {
    return distance >= _kFanHitBandInner && distance <= _kFanHitBandOuter;
  }

  // ==================== 长按 + 滑动选择 ====================

  void _handleButtonLongPressStart(LongPressStartDetails details) {
    HapticFeedback.mediumImpact();
    setState(() {
      _buttonPressed = false;
      _dragSelecting = true;
    });
    _setExpanded(true);
    _updateDragSelection(details.globalPosition);
  }

  void _handleButtonLongPressMove(LongPressMoveUpdateDetails details) {
    if (!_dragSelecting || !_expanded) {
      return;
    }
    _updateDragSelection(details.globalPosition);
  }

  void _handleButtonLongPressEnd(LongPressEndDetails details) {
    if (!_dragSelecting) {
      return;
    }
    final anchors = _resolveAnchors();
    final activeIndex = _activeDragIndex;
    if (activeIndex != null &&
        activeIndex >= 0 &&
        activeIndex < anchors.length) {
      unawaited(_jumpToAnchor(anchors[activeIndex]));
    }
    _clearDragState();
  }

  void _handleButtonLongPressCancel() {
    _clearDragState();
  }

  void _updateDragSelection(Offset globalPosition) {
    final fanBox = _fanBoxKey.currentContext?.findRenderObject() as RenderBox?;
    if (fanBox == null || !fanBox.hasSize) {
      return;
    }
    final ring = _fingerRingPosition(fanBox.globalToLocal(globalPosition));
    if (!_isWithinRingBand(ring.distance)) {
      if (_dragSlotPosition != null || _activeDragIndex != null) {
        setState(() {
          _dragSlotPosition = null;
          _activeDragIndex = null;
        });
      }
      return;
    }
    var slotPosition = ring.slotPosition;
    var nextRingScroll = _ringScroll;
    // 手指滑过弧的两端时带动圆环旋转，长按一次即可翻阅整段历史。
    const lowerEdge = 0.3;
    final upperEdge = _kFanVisibleSlots - 1 - 0.3;
    if (slotPosition < lowerEdge) {
      nextRingScroll += (slotPosition - lowerEdge) * 0.38;
    } else if (slotPosition > upperEdge) {
      nextRingScroll += (slotPosition - upperEdge) * 0.38;
    }
    nextRingScroll = nextRingScroll.clamp(0.0, _maxRingScroll);
    slotPosition = slotPosition.clamp(-0.6, _kFanVisibleSlots - 1 + 0.6);
    final anchors = _resolveAnchors();
    final nearestIndex = (slotPosition + nextRingScroll).round();
    final nextIndex =
        (nearestIndex >= 0 &&
            nearestIndex < anchors.length &&
            ((slotPosition + nextRingScroll) - nearestIndex).abs() <= 0.55)
        ? nearestIndex
        : null;
    if (_dragSlotPosition == slotPosition &&
        _activeDragIndex == nextIndex &&
        nextRingScroll == _ringScroll) {
      return;
    }
    if (nextIndex != _activeDragIndex && nextIndex != null) {
      HapticFeedback.selectionClick();
    }
    setState(() {
      _dragSlotPosition = slotPosition;
      _activeDragIndex = nextIndex;
      _ringScroll = nextRingScroll;
    });
  }

  // ==================== 圆环旋转（普通拖动） ====================

  void _handleFanPanStart(DragStartDetails details) {
    final fanBox = _fanBoxKey.currentContext?.findRenderObject() as RenderBox?;
    if (fanBox == null || !fanBox.hasSize) {
      return;
    }
    final ring = _fingerRingPosition(
      fanBox.globalToLocal(details.globalPosition),
    );
    // 只有从环带上起手才旋转圆环，避免误吞面板附近的普通滑动。
    _panLastSlotPosition = _isWithinRingBand(ring.distance)
        ? ring.slotPosition
        : null;
  }

  void _handleFanPanUpdate(DragUpdateDetails details) {
    final fanBox = _fanBoxKey.currentContext?.findRenderObject() as RenderBox?;
    if (fanBox == null || !fanBox.hasSize) {
      return;
    }
    final ring = _fingerRingPosition(
      fanBox.globalToLocal(details.globalPosition),
    );
    final last = _panLastSlotPosition;
    if (last == null || _maxRingScroll <= 0) {
      return;
    }
    _panLastSlotPosition = ring.slotPosition;
    // 转盘跟手：被抓住的锚点跟着手指沿弧走。
    final next = (_ringScroll + (last - ring.slotPosition)).clamp(
      0.0,
      _maxRingScroll,
    );
    if (next != _ringScroll) {
      setState(() => _ringScroll = next);
    }
  }

  void _handleFanPanEnd(DragEndDetails details) {
    _panLastSlotPosition = null;
    // 松手后对齐到整数槽位，避免圆环停在半个槽位上。
    final snapped = _ringScroll.roundToDouble().clamp(0.0, _maxRingScroll);
    if (snapped != _ringScroll) {
      setState(() => _ringScroll = snapped);
    }
  }

  // ==================== 动画取值 ====================

  /// 单个槽位在发散动画中的进度：离按钮正上方越近越先出场
  /// （收起时反向先吸回），曲线用 Cherry 的 easeOutQuart。
  double _slotRevealProgress(double slotPosition, double controllerValue) {
    final clamped = slotPosition.clamp(0.0, _kFanVisibleSlots + 1.0);
    final start = math.min(clamped * _kFanStaggerStep, _kFanStaggerMaxStart);
    final end = math.min(start + _kFanRevealSpan, 1.0);
    final local = ((controllerValue - start) / (end - start)).clamp(0.0, 1.0);
    return _kAnchorEaseOutQuart.transform(local);
  }

  /// 静止透明度：沿弧离最新锚点越远（越早）越淡；
  /// 滑动选择时改用 Cherry 的 0.5 + p。
  double _restingOpacity(int index, double slotPosition) {
    final drag = _dragSlotPosition;
    if (drag != null) {
      final p = _magnifyFactor(index);
      return (0.5 + p).clamp(0.5, 1.0);
    }
    final newestSlot = (_resolveAnchors().length - 1) - _ringScroll;
    final stepsFromNewest = math.max(0, newestSlot - slotPosition);
    return math.max(
      _kFanIdleOpacityFloor,
      1.0 - stepsFromNewest * _kFanIdleOpacityStep,
    );
  }

  /// 越过弧两端的锚点渐隐（圆环旋转时露出/收起的过渡）。
  double _edgeFade(double slotPosition) {
    const fadeSpan = 0.55;
    if (slotPosition < 0) {
      return (1 + slotPosition / fadeSpan).clamp(0.0, 1.0);
    }
    final overshoot = slotPosition - (_kFanVisibleSlots - 1);
    if (overshoot > 0) {
      return (1 - overshoot / fadeSpan).clamp(0.0, 1.0);
    }
    return 1;
  }

  double _magnifyFactor(int index) {
    final drag = _dragSlotPosition;
    if (drag == null) {
      return 0;
    }
    final distance = ((drag + _ringScroll) - index).abs();
    return math.max(0, 1 - distance / _kAnchorMagnifySpanSlots);
  }

  // ==================== 构建 ====================

  @override
  Widget build(BuildContext context) {
    final anchors = _resolveAnchors();
    final showButton = widget.visible && anchors.isNotEmpty;
    return Stack(
      children: [
        // 始终挂载，避免长按展开时插入前置 child 打断按钮的手势流。
        // 遮罩位于扇形锚点和入口按钮之后，因此只有这两部分保持前景亮度。
        Positioned.fill(
          child: IgnorePointer(
            ignoring: !_expanded,
            child: FadeTransition(
              key: chatMessageAnchorScrimKey,
              opacity: _scrimOpacity,
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: () => _setExpanded(false),
                child: ColoredBox(color: chatMessageAnchorScrimColor(context)),
              ),
            ),
          ),
        ),
        // 圆环画布：右下角对齐按钮中心，覆盖按钮左上方的扇形区域。
        Positioned(
          right: 24 + _kAnchorButtonSize / 2 - _kFanCanvasExtent,
          bottom:
              widget.bottomInset + _kAnchorButtonSize / 2 - _kFanCanvasExtent,
          width: _kFanCanvasExtent * 2,
          height: _kFanCanvasExtent * 2,
          child: IgnorePointer(
            ignoring: !_expanded,
            child: _buildFanCanvas(anchors),
          ),
        ),
        Positioned(
          right: 24,
          bottom: widget.bottomInset,
          child: IgnorePointer(
            ignoring: !showButton,
            child: AnimatedOpacity(
              opacity: showButton ? 1 : 0,
              duration: const Duration(milliseconds: 180),
              curve: Curves.easeOutCubic,
              child: _buildAnchorButton(),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildFanCanvas(List<ChatMessageAnchor> anchors) {
    return AnimatedBuilder(
      animation: _expandController,
      builder: (context, _) {
        final children = <Widget>[];
        Widget? activeChild;
        if (_expandController.value > 0.001) {
          for (var index = 0; index < anchors.length; index++) {
            final slotPosition = _slotPositionOf(index);
            // 只构建可见弧段附近的锚点。
            if (slotPosition < -1.2 || slotPosition > _kFanVisibleSlots + 0.2) {
              continue;
            }
            final item = _buildFanItem(index, anchors[index], slotPosition);
            if (_activeDragIndex == index && _dragSlotPosition != null) {
              activeChild = item;
            } else {
              children.add(item);
            }
          }
          if (activeChild != null) {
            // 选中锚点置于最上层，放大与展开的标签不被相邻锚点遮挡。
            children.add(activeChild);
          }
        }
        return GestureDetector(
          behavior: HitTestBehavior.translucent,
          onPanStart: _handleFanPanStart,
          onPanUpdate: _handleFanPanUpdate,
          onPanEnd: _handleFanPanEnd,
          child: SizedBox.expand(
            child: Stack(
              key: _fanBoxKey,
              clipBehavior: Clip.none,
              children: children,
            ),
          ),
        );
      },
    );
  }

  Widget _buildFanItem(
    int index,
    ChatMessageAnchor anchor,
    double slotPosition,
  ) {
    final reveal = _slotRevealProgress(slotPosition, _expandController.value);
    if (reveal <= 0.001) {
      return const SizedBox.shrink();
    }
    // 展开：从按钮中心沿半径向外、同时从弧顶向自己的槽位扇形展开。
    final angle = _lerpDouble(
      _slotAngle(math.min(slotPosition, 0.0)),
      _slotAngle(slotPosition),
      reveal,
    );
    final radius = _kFanRadius * (0.30 + 0.70 * reveal);
    final centerX = _fanCenter.dx + radius * math.cos(angle);
    final centerY = _fanCenter.dy + radius * math.sin(angle);
    final opacity =
        (reveal *
                _restingOpacity(index, slotPosition) *
                _edgeFade(slotPosition))
            .clamp(0.0, 1.0);
    if (opacity <= 0.004) {
      return const SizedBox.shrink();
    }
    final magnify = _magnifyFactor(index);
    final active = _dragSlotPosition != null && _activeDragIndex == index;
    // 放大量直接跟随手指的角向距离（macOS Dock 式直出），不做 active 突变。
    final scale = (0.35 + 0.65 * reveal) * (1 + _kAnchorMagnifyScale * magnify);
    const itemExtent = 96.0;
    return Positioned(
      left: centerX - itemExtent / 2,
      top: centerY - _kAnchorAvatarSize / 2 - 4,
      width: itemExtent,
      child: Opacity(
        opacity: opacity,
        child: Transform.scale(
          scale: scale,
          alignment: Alignment.topCenter,
          child: _buildAnchorItemContent(anchor, index: index, active: active),
        ),
      ),
    );
  }

  Widget _buildAnchorItemContent(
    ChatMessageAnchor anchor, {
    required int index,
    required bool active,
  }) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final labelBackground = active
        ? palette.accentPrimary.withValues(alpha: isDark ? 0.96 : 0.94)
        : palette.surfacePrimary.withValues(alpha: isDark ? 0.94 : 0.96);
    final inactiveLabelBorder = isDark
        ? Colors.white.withValues(alpha: 0.14)
        : palette.borderStrong.withValues(alpha: 0.78);
    final labelBorder = active
        ? palette.accentPrimary.withValues(alpha: isDark ? 0.54 : 0.42)
        : inactiveLabelBorder;
    final labelShadow = Colors.black.withValues(alpha: isDark ? 0.36 : 0.16);
    final labelColor = active
        ? _foregroundForFill(palette.accentPrimary)
        : palette.textPrimary;
    // 相邻锚点的标签高低错落（按锚点奇偶固定，随圆环旋转保持稳定），
    // 避免弧顶附近几乎同排的标签互相碰撞。
    final labelDrop = index.isOdd ? 13.0 : 0.0;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () => unawaited(_jumpToAnchor(anchor)),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          _buildAnchorAvatar(anchor),
          SizedBox(height: 3 + labelDrop),
          // 锚点会压在聊天内容上方，标签需要足够实的底色来和正文分层。
          Container(
            constraints: BoxConstraints(maxWidth: active ? 92 : 78),
            padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2.5),
            decoration: BoxDecoration(
              color: labelBackground,
              borderRadius: BorderRadius.circular(9),
              border: Border.all(color: labelBorder, width: 0.6),
              boxShadow: [
                BoxShadow(
                  color: labelShadow,
                  blurRadius: active ? 10 : 8,
                  offset: const Offset(0, 3),
                ),
              ],
            ),
            child: Text(
              anchor.preview,
              maxLines: 1,
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 9.5,
                height: 1.25,
                fontWeight: active ? FontWeight.w600 : FontWeight.w500,
                color: labelColor,
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildAnchorAvatar(ChatMessageAnchor anchor) {
    if (anchor.isUser) {
      final palette = context.omniPalette;
      final isDark = context.isDarkTheme;
      return Container(
        width: _kAnchorAvatarSize,
        height: _kAnchorAvatarSize,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: isDark
              ? palette.surfacePrimary.withValues(alpha: 0.58)
              : Colors.white.withValues(alpha: 0.96),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: isDark ? 0.30 : 0.14),
              blurRadius: 8,
              offset: const Offset(0, 3),
            ),
          ],
        ),
        child: Icon(LucideIcons.user, size: 15, color: palette.textSecondary),
      );
    }
    final agentId = anchor.agentId;
    if (agentId != null) {
      final palette = context.omniPalette;
      return Container(
        width: _kAnchorAvatarSize,
        height: _kAnchorAvatarSize,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: context.isDarkTheme
              ? palette.surfaceSecondary.withValues(alpha: 0.66)
              : palette.surfaceElevated.withValues(alpha: 0.92),
          shape: BoxShape.circle,
          border: Border.all(
            color: palette.borderSubtle.withValues(
              alpha: context.isDarkTheme ? 0.48 : 0.72,
            ),
            width: 0.5,
          ),
        ),
        child: AgentBrandIcon(agentId: agentId, size: 16),
      );
    }
    return ValueListenableBuilder<AgentAvatarState>(
      valueListenable: AgentAvatarService.avatarStateNotifier,
      builder: (context, state, _) {
        return AgentAvatarCircle(
          state: state,
          size: _kAnchorAvatarSize,
          showBorder: false,
        );
      },
    );
  }

  Widget _buildAnchorButton() {
    final palette = context.omniPalette;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () => _setExpanded(!_expanded),
      onTapDown: (_) => setState(() => _buttonPressed = true),
      onTapUp: (_) => setState(() => _buttonPressed = false),
      onTapCancel: () => setState(() => _buttonPressed = false),
      onLongPressStart: _handleButtonLongPressStart,
      onLongPressMoveUpdate: _handleButtonLongPressMove,
      onLongPressEnd: _handleButtonLongPressEnd,
      onLongPressCancel: _handleButtonLongPressCancel,
      child: AnimatedScale(
        scale: _buttonPressed ? 0.90 : 1,
        duration: const Duration(milliseconds: 140),
        curve: Curves.easeOutCubic,
        child: AnimatedBuilder(
          animation: _expandController,
          builder: (context, _) {
            final t = _expandController.value.clamp(0.0, 1.0);
            return _buildButtonGlassSurface(
              expansion: t,
              child: Center(
                child: Icon(
                  LucideIcons.galleryVerticalEnd,
                  size: 16,
                  color: Color.lerp(
                    palette.textTertiary,
                    palette.accentPrimary,
                    t,
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }

  /// 小尺寸定制玻璃圆面。不用 OmniGlassPanel：它的顶部 1px 高光线在
  /// 圆形裁剪后只剩顶部中央一小段亮弧、向上的 accent 泛光也会在按钮
  /// 顶缘透出一点颜色，在 34px 的圆钮上都读作杂色。
  Widget _buildButtonGlassSurface({
    required Widget child,
    required double expansion,
  }) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final collapsedTopTint = isDark
        ? palette.surfacePrimary.withValues(alpha: 0.26)
        : Colors.white.withValues(alpha: 0.40);
    final collapsedBottomTint = isDark
        ? palette.surfaceSecondary.withValues(alpha: 0.12)
        : Colors.white.withValues(alpha: 0.18);
    final expandedTopTint = Color.alphaBlend(
      palette.accentPrimary.withValues(alpha: isDark ? 0.24 : 0.16),
      palette.surfacePrimary.withValues(alpha: isDark ? 0.96 : 0.92),
    );
    final expandedBottomTint = Color.alphaBlend(
      palette.accentPrimary.withValues(alpha: isDark ? 0.15 : 0.11),
      palette.surfaceSecondary.withValues(alpha: isDark ? 0.88 : 0.82),
    );
    final topTint = Color.lerp(collapsedTopTint, expandedTopTint, expansion)!;
    final bottomTint = Color.lerp(
      collapsedBottomTint,
      expandedBottomTint,
      expansion,
    )!;
    final borderColor = Color.lerp(
      palette.borderSubtle.withValues(alpha: isDark ? 0.18 : 0.28),
      palette.accentPrimary.withValues(alpha: isDark ? 0.72 : 0.58),
      expansion,
    )!;
    return Container(
      width: _kAnchorButtonSize,
      height: _kAnchorButtonSize,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: isDark ? 0.30 : 0.10),
            blurRadius: 16,
            offset: const Offset(0, 6),
          ),
          BoxShadow(
            color: palette.accentPrimary.withValues(
              alpha: expansion * (isDark ? 0.38 : 0.24),
            ),
            blurRadius: 18,
            spreadRadius: expansion,
          ),
        ],
      ),
      foregroundDecoration: BoxDecoration(
        shape: BoxShape.circle,
        border: Border.all(color: borderColor, width: 0.8),
      ),
      child: ClipOval(
        child: BackdropFilter(
          filter: ui.ImageFilter.blur(sigmaX: 10, sigmaY: 10),
          child: DecoratedBox(
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              gradient: LinearGradient(
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
                colors: [topTint, bottomTint],
              ),
            ),
            child: child,
          ),
        ),
      ),
    );
  }

  static double _lerpDouble(double a, double b, double t) => a + (b - a) * t;

  static Color _foregroundForFill(Color fill) {
    final whiteContrast = _contrastRatio(fill, Colors.white);
    final darkContrast = _contrastRatio(fill, const Color(0xFF101418));
    return whiteContrast >= darkContrast
        ? Colors.white
        : const Color(0xFF101418);
  }

  static double _contrastRatio(Color a, Color b) {
    final aLuminance = a.computeLuminance();
    final bLuminance = b.computeLuminance();
    final lighter = math.max(aLuminance, bLuminance);
    final darker = math.min(aLuminance, bLuminance);
    return (lighter + 0.05) / (darker + 0.05);
  }
}
