import 'dart:async';

import 'package:flutter/material.dart';

/// 横向定位策略。
enum GlassPopupHorizontalPlacement {
  /// 默认：popup 边缘对齐 anchor 边缘。
  /// anchor 在屏幕右半边 → popup 右边对齐 anchor 右边；
  /// anchor 在屏幕左半边 → popup 左边对齐 anchor 左边。
  /// 适合按钮触发的下拉菜单（Codex 模型、Codex 权限、灵动岛 model selector 等）。
  edgeAlign,

  /// popup 横向中心对齐 anchor 中心。
  /// 适合"点哪里弹哪里"的 context menu —— 长按消息气泡。
  /// 这样 popup 的顶边中点正好在触点下方,不会再"拐到触点左边"。
  centerOnAnchor,

  /// popup 横向居中在屏幕。
  /// 适合 popup 宽度远大于 trigger 的场景 —— 终端环境变量卡片 (340 宽,
  /// 但触发按钮只有 24 宽)，按 anchor 对齐会让 popup 几乎横跨整个屏幕、
  /// 视觉上看不出来跟按钮有任何关系。居中后变成"贴着输入栏的居中卡片"。
  centerOnScreen,
}

class GlassPopupAnchorLayout extends StatelessWidget {
  const GlassPopupAnchorLayout({
    super.key,
    required this.anchor,
    required this.child,
    this.preferBelow = true,
    this.verticalGap = 6,
    this.screenPadding = const EdgeInsets.all(8),
    this.unfoldAlignment,
    this.horizontalPlacement = GlassPopupHorizontalPlacement.edgeAlign,
  });

  final Rect anchor;
  final Widget child;
  final bool preferBelow;
  final double verticalGap;
  final EdgeInsets screenPadding;
  final Alignment? unfoldAlignment;
  final GlassPopupHorizontalPlacement horizontalPlacement;

  @override
  Widget build(BuildContext context) {
    final mediaQuery = MediaQuery.of(context);
    return CustomSingleChildLayout(
      delegate: _GlassPopupLayoutDelegate(
        anchor: anchor,
        preferBelow: preferBelow,
        verticalGap: verticalGap,
        screenPadding: screenPadding,
        mediaPadding: mediaQuery.padding,
        textDirection: Directionality.of(context),
        explicitUnfoldAlignment: unfoldAlignment,
        horizontalPlacement: horizontalPlacement,
        onAlignmentResolved: (_) {},
      ),
      child: child,
    );
  }
}

/// 玻璃风格 popup 的统一入口。
///
/// 设计目标：还原 Android 原生 PopupMenu 的"从 anchor 一侧的角先展宽、再展高、
/// 整段列表跟着展开"的 unfold 动画——这是 `widthFactor` / `heightFactor` 的
/// 经典 Material 卷帘。
///
/// 实现要点：clip 直接贴在玻璃面板自己身上（在 [CustomSingleChildLayout] **里面**），
/// 不是包整个页面——否则 clip rect 是相对屏幕的，popup 整体在屏幕里的某个位置，
/// clip 还没扩到 popup 的时候你什么都看不到，clip 一旦覆盖到 popup 就"咔"地全显示了，
/// 没有任何 unfold 感。clip 必须直接作用在面板本身的局部坐标系上才能看到生长动画。
///
/// 阴影处理：[_UnfoldClipper] 把 clip 范围向四周扩了 [_shadowExtent] 用于放阴影；
/// 这样 [OmniGlassPanel] 外溢的阴影也会随 unfold 一起渐渐渗出来，而不是被硬切。
Future<T?> showGlassPopup<T>({
  required BuildContext context,
  required Rect anchor,
  required Widget child,
  bool preferBelow = true,
  double verticalGap = 6,
  EdgeInsets screenPadding = const EdgeInsets.all(8),
  Duration transitionDuration = const Duration(milliseconds: 380),
  Duration reverseTransitionDuration = const Duration(milliseconds: 220),
  Curve curve = Curves.easeOutCubic,
  Curve reverseCurve = Curves.easeInCubic,
  Alignment? unfoldAlignment,
  GlassPopupHorizontalPlacement horizontalPlacement =
      GlassPopupHorizontalPlacement.edgeAlign,
  bool useRootNavigator = false,
  Color? barrierColor,
  String? barrierLabel,
  RouteSettings? routeSettings,
  bool? requestFocus,

  /// 设置为 true 时不播放展开/收起动画——直接瞬间出现 / 瞬间消失。
  /// 用于长按消息气泡这类"系统级 context menu"——动画反而显得拖沓。
  bool instant = false,
}) {
  final navigator = Navigator.of(context, rootNavigator: useRootNavigator);
  final capturedThemes = InheritedTheme.capture(
    from: context,
    to: navigator.context,
  );
  return navigator.push<T>(
    GlassPopupRoute<T>(
      anchor: anchor,
      child: capturedThemes.wrap(child),
      preferBelow: preferBelow,
      verticalGap: verticalGap,
      screenPadding: screenPadding,
      animationDuration: transitionDuration,
      reverseAnimationDuration: reverseTransitionDuration,
      curve: curve,
      reverseCurve: reverseCurve,
      explicitUnfoldAlignment: unfoldAlignment,
      horizontalPlacement: horizontalPlacement,
      instant: instant,
      barrierColor: barrierColor,
      barrierLabel:
          barrierLabel ?? MaterialLocalizations.of(context).modalBarrierDismissLabel,
      settings: routeSettings,
      requestFocus: requestFocus,
    ),
  );
}

/// `showGlassPopup` 内部使用的自定义 [PopupRoute]。
class GlassPopupRoute<T> extends PopupRoute<T> {
  GlassPopupRoute({
    required this.anchor,
    required this.child,
    required this.preferBelow,
    required this.verticalGap,
    required this.screenPadding,
    required this.animationDuration,
    required this.reverseAnimationDuration,
    required this.curve,
    required this.reverseCurve,
    required this.explicitUnfoldAlignment,
    required this.horizontalPlacement,
    required this.barrierLabel,
    required this.instant,
    Color? barrierColor,
    super.requestFocus,
    super.settings,
  }) : _barrierColor = barrierColor;

  final Rect anchor;
  final Widget child;
  final bool preferBelow;
  final double verticalGap;
  final EdgeInsets screenPadding;
  final Duration animationDuration;
  final Duration reverseAnimationDuration;
  final Curve curve;
  final Curve reverseCurve;
  final Alignment? explicitUnfoldAlignment;
  final GlassPopupHorizontalPlacement horizontalPlacement;
  final bool instant;
  final Color? _barrierColor;

  final ValueNotifier<Alignment> _resolvedAlignment =
      ValueNotifier<Alignment>(Alignment.topCenter);

  @override
  Color? get barrierColor => _barrierColor;

  @override
  bool get barrierDismissible => true;

  @override
  final String barrierLabel;

  @override
  Duration get transitionDuration =>
      instant ? Duration.zero : animationDuration;

  @override
  Duration get reverseTransitionDuration =>
      instant ? Duration.zero : reverseAnimationDuration;

  @override
  void dispose() {
    _resolvedAlignment.dispose();
    super.dispose();
  }

  @override
  Widget buildPage(
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
  ) {
    final mediaQuery = MediaQuery.of(context);
    // 关键：这里**绝对不能**再套一层 `SafeArea`。anchor 是用
    // `Overlay.context.findRenderObject()` 取的全屏坐标；SafeArea 会把子节点的
    // 坐标系压成"安全区内的局部坐标"，再让 `CustomSingleChildLayout` 把 popup
    // 按局部坐标摆——结果 popup 在屏幕上的实际 Y 多出一整段 notch / 状态栏的高
    // (iPhone 上 ~47px)。视觉上就是"点哪里 popup 都跑到下面老远"。
    //
    // SafeArea 本来就是多余的：[_GlassPopupLayoutDelegate] 已经在 usableTop /
    // usableBottom 里减掉了 mediaPadding，popup 不会被 notch / 底部 home indicator
    // 盖到。
    return CustomSingleChildLayout(
      delegate: _GlassPopupLayoutDelegate(
        anchor: anchor,
        preferBelow: preferBelow,
        verticalGap: verticalGap,
        screenPadding: screenPadding,
        mediaPadding: mediaQuery.padding,
        textDirection: Directionality.of(context),
        explicitUnfoldAlignment: explicitUnfoldAlignment,
        horizontalPlacement: horizontalPlacement,
        onAlignmentResolved: (alignment) {
          if (_resolvedAlignment.value != alignment) {
            scheduleMicrotask(() {
              if (_resolvedAlignment.value != alignment) {
                _resolvedAlignment.value = alignment;
              }
            });
          }
        },
      ),
      child: instant
          ? child
          : _GlassPopupAnimatedFrame(
              animation: animation,
              alignmentNotifier: _resolvedAlignment,
              curve: curve,
              reverseCurve: reverseCurve,
              child: child,
            ),
    );
  }

  /// 动画已经在 [buildPage] 内部紧贴 popup 实施——这里直接透传即可。
  /// 注意：如果在这里再套一层 transition 会导致 clip 包到整个 page 上,
  /// clip rect 变成相对屏幕计算,popup 又会"咔一下"瞬间冒出来。
  @override
  Widget buildTransitions(
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) {
    return child;
  }
}

/// 真正的 unfold 动画外壳：clip 直接作用在 popup 自己身上，
/// clip 的坐标系 == popup 的局部坐标系，所以 widthT/heightT 从 0 长到 1
/// 在视觉上就是 popup 从一个角"长出来"的过程。
class _GlassPopupAnimatedFrame extends StatefulWidget {
  const _GlassPopupAnimatedFrame({
    required this.animation,
    required this.alignmentNotifier,
    required this.curve,
    required this.reverseCurve,
    required this.child,
  });

  final Animation<double> animation;
  final ValueNotifier<Alignment> alignmentNotifier;
  final Curve curve;
  final Curve reverseCurve;
  final Widget child;

  @override
  State<_GlassPopupAnimatedFrame> createState() =>
      _GlassPopupAnimatedFrameState();
}

class _GlassPopupAnimatedFrameState extends State<_GlassPopupAnimatedFrame> {
  late final CurvedAnimation _widthAnim;
  late final CurvedAnimation _heightAnim;
  late final CurvedAnimation _fadeAnim;

  @override
  void initState() {
    super.initState();
    // unfold 动画核心：两条曲线分别控制宽和高的展开节奏。
    //
    // - widthInterval (0, 0.35)：宽度在前 35% 内"嗖"一下扯到位,模拟原生
    //   PopupMenu 把 width 飞快拉满后再展开 list 的节奏。
    // - heightInterval (0, 1.0)：高度跟整段动画从 0 长到 1,玻璃面板的列表
    //   就像帘子一样从顶边垂下来。
    // - fadeInterval (0, 0.18)：开头一小段轻 fade,把首帧"硬边突现"藏掉。
    _widthAnim = CurvedAnimation(
      parent: widget.animation,
      curve: Interval(0.0, 0.35, curve: widget.curve),
      reverseCurve: Interval(0.0, 0.5, curve: widget.reverseCurve),
    );
    _heightAnim = CurvedAnimation(
      parent: widget.animation,
      curve: Interval(0.0, 1.0, curve: widget.curve),
      reverseCurve: Interval(0.0, 1.0, curve: widget.reverseCurve),
    );
    _fadeAnim = CurvedAnimation(
      parent: widget.animation,
      curve: const Interval(0.0, 0.18, curve: Curves.easeOut),
      reverseCurve: const Interval(0.5, 1.0, curve: Curves.easeIn),
    );
  }

  @override
  void dispose() {
    _widthAnim.dispose();
    _heightAnim.dispose();
    _fadeAnim.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<Alignment>(
      valueListenable: widget.alignmentNotifier,
      child: widget.child,
      builder: (context, alignment, innerChild) {
        return AnimatedBuilder(
          animation: widget.animation,
          child: innerChild,
          builder: (context, c) {
            return FadeTransition(
              opacity: _fadeAnim,
              child: ClipRect(
                clipper: _UnfoldClipper(
                  widthT: _widthAnim.value,
                  heightT: _heightAnim.value,
                  alignment: alignment,
                ),
                clipBehavior: Clip.hardEdge,
                child: c,
              ),
            );
          },
        );
      },
    );
  }
}

typedef _AlignmentCallback = void Function(Alignment alignment);

class _GlassPopupLayoutDelegate extends SingleChildLayoutDelegate {
  _GlassPopupLayoutDelegate({
    required this.anchor,
    required this.preferBelow,
    required this.verticalGap,
    required this.screenPadding,
    required this.mediaPadding,
    required this.textDirection,
    required this.explicitUnfoldAlignment,
    required this.horizontalPlacement,
    required this.onAlignmentResolved,
  });

  final Rect anchor;
  final bool preferBelow;
  final double verticalGap;
  final EdgeInsets screenPadding;
  final EdgeInsets mediaPadding;
  final TextDirection textDirection;
  final Alignment? explicitUnfoldAlignment;
  final GlassPopupHorizontalPlacement horizontalPlacement;
  final _AlignmentCallback onAlignmentResolved;

  @override
  BoxConstraints getConstraintsForChild(BoxConstraints constraints) {
    return BoxConstraints.loose(constraints.biggest)
        .deflate(screenPadding + mediaPadding);
  }

  @override
  Offset getPositionForChild(Size overlaySize, Size childSize) {
    final usableTop = screenPadding.top + mediaPadding.top;
    final usableBottom =
        overlaySize.height - screenPadding.bottom - mediaPadding.bottom;
    final usableLeft = screenPadding.left + mediaPadding.left;
    final usableRight = overlaySize.width - screenPadding.right - mediaPadding.right;

    final spaceBelow = usableBottom - anchor.bottom - verticalGap;
    final spaceAbove = anchor.top - usableTop - verticalGap;

    final placeBelow = preferBelow
        ? (spaceBelow >= childSize.height || spaceBelow >= spaceAbove)
        : !(spaceAbove >= childSize.height || spaceAbove >= spaceBelow);

    double y;
    if (placeBelow) {
      y = anchor.bottom + verticalGap;
    } else {
      y = anchor.top - verticalGap - childSize.height;
    }
    final minY = usableTop;
    final maxY = usableBottom - childSize.height;
    if (maxY < minY) {
      y = minY;
    } else {
      y = y.clamp(minY, maxY);
    }

    double x;
    switch (horizontalPlacement) {
      case GlassPopupHorizontalPlacement.centerOnAnchor:
        // 触点居中 — popup 顶边中点对齐 anchor 中心
        x = anchor.center.dx - childSize.width / 2;
      case GlassPopupHorizontalPlacement.centerOnScreen:
        // 屏幕居中 — 给宽 popup + 窄 trigger 用,看起来像贴着输入栏的浮动卡片
        x = (overlaySize.width - childSize.width) / 2;
      case GlassPopupHorizontalPlacement.edgeAlign:
        // 默认 — anchor 边对齐
        final anchorOnRight = anchor.center.dx > overlaySize.width / 2;
        if (anchorOnRight) {
          x = anchor.right - childSize.width;
        } else {
          x = anchor.left;
        }
    }
    final minX = usableLeft;
    final maxX = usableRight - childSize.width;
    if (maxX < minX) {
      x = minX;
    } else {
      x = x.clamp(minX, maxX);
    }

    if (explicitUnfoldAlignment != null) {
      onAlignmentResolved(explicitUnfoldAlignment!);
    } else {
      final popupLeft = x;
      final popupRight = x + childSize.width;
      final anchorCenterX = anchor.center.dx.clamp(popupLeft, popupRight);
      final relX = childSize.width <= 0
          ? 0.5
          : ((anchorCenterX - popupLeft) / childSize.width).clamp(0.0, 1.0);
      final alignmentX = (relX * 2.0 - 1.0).clamp(-1.0, 1.0);
      final alignmentY = placeBelow ? -1.0 : 1.0;
      onAlignmentResolved(Alignment(alignmentX, alignmentY));
    }

    return Offset(x, y);
  }

  @override
  bool shouldRelayout(_GlassPopupLayoutDelegate oldDelegate) {
    return anchor != oldDelegate.anchor ||
        preferBelow != oldDelegate.preferBelow ||
        verticalGap != oldDelegate.verticalGap ||
        screenPadding != oldDelegate.screenPadding ||
        mediaPadding != oldDelegate.mediaPadding ||
        textDirection != oldDelegate.textDirection ||
        explicitUnfoldAlignment != oldDelegate.explicitUnfoldAlignment ||
        horizontalPlacement != oldDelegate.horizontalPlacement;
  }
}

/// 在 popup **自己的** 局部坐标系内做 clip——clip rect 在 [0, size] 这块矩形里
/// 按 (widthT, heightT) 比例从 [alignment] 对应的角生长。
///
/// 为了让 [OmniGlassPanel] 外溢的阴影也跟着 unfold 渐渐渗出来（而不是在 t=1
/// 时一刀切断），clip 范围向四周扩了 [_shadowExtent]——clip 起点不是 (0,0)
/// 而是 (-extent, -extent)，clip 终点是 (size + extent)。
class _UnfoldClipper extends CustomClipper<Rect> {
  _UnfoldClipper({
    required this.widthT,
    required this.heightT,
    required this.alignment,
  });

  final double widthT;
  final double heightT;
  final Alignment alignment;

  /// OmniGlassPanel 外溢阴影最远约 48px (BoxShadow.blurRadius 42 + offset 18)，
  /// 这里给 64px 留点余量，确保阴影完整露出。
  static const double _shadowExtent = 64.0;

  @override
  Rect getClip(Size size) {
    // 总作用范围 = popup 自身尺寸 + 四周阴影外溢
    final fullW = size.width + _shadowExtent * 2;
    final fullH = size.height + _shadowExtent * 2;

    // 给一个 0.5px 下限避免 0 面积矩形进入 ClipRect 的"零面积 fast-path"
    // (会直接跳过绘制，导致首帧"啥都没有→一瞬间冒一整块"的跳变)。
    final w = (fullW * widthT).clamp(0.5, fullW);
    final h = (fullH * heightT).clamp(0.5, fullH);

    // alignment ∈ [-1, +1]² → [0, 1]² 的锚点位置
    final ax = (alignment.x + 1.0) / 2.0;
    final ay = (alignment.y + 1.0) / 2.0;

    // clip 在 [-extent, size + extent] 这个扩展范围内，按锚点对齐生长
    final x = -_shadowExtent + (fullW - w) * ax;
    final y = -_shadowExtent + (fullH - h) * ay;

    return Rect.fromLTWH(x, y, w, h);
  }

  @override
  bool shouldReclip(_UnfoldClipper old) =>
      widthT != old.widthT ||
      heightT != old.heightT ||
      alignment != old.alignment;
}

/// 从 [BuildContext] 对应的 [RenderBox] 中提取 anchor 矩形（overlay 坐标系）。
/// 调用前请确保 widget 已经完成 layout（`hasSize == true`）。
Rect? glassPopupAnchorFromContext(BuildContext anchorContext) {
  final overlay = Overlay.of(anchorContext).context.findRenderObject() as RenderBox?;
  final anchorBox = anchorContext.findRenderObject() as RenderBox?;
  if (overlay == null || anchorBox == null || !anchorBox.hasSize) {
    return null;
  }
  final topLeft = anchorBox.localToGlobal(Offset.zero, ancestor: overlay);
  final bottomRight = anchorBox.localToGlobal(
    anchorBox.size.bottomRight(Offset.zero),
    ancestor: overlay,
  );
  return Rect.fromPoints(topLeft, bottomRight);
}

/// 从全局坐标点构造一个零宽零高的 anchor 矩形（用于触点弹出，如长按消息气泡）。
Rect? glassPopupAnchorFromGlobalPosition(
  BuildContext context,
  Offset globalPosition,
) {
  final overlay = Overlay.of(context).context.findRenderObject() as RenderBox?;
  if (overlay == null) return null;
  final local = overlay.globalToLocal(globalPosition);
  return Rect.fromLTWH(local.dx, local.dy, 0, 0);
}
