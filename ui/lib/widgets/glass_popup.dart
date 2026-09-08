import 'dart:async';

import 'package:flutter/material.dart';

/// 横向定位策略。
enum GlassPopupHorizontalPlacement {
  /// 默认：popup 边缘对齐 anchor 边缘。
  /// anchor 在屏幕右半边 → popup 右边对齐 anchor 右边；
  /// anchor 在屏幕左半边 → popup 左边对齐 anchor 左边。
  /// 适合按钮触发的下拉菜单（Agent 模型、权限、灵动岛 model selector 等）。
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

/// 给 OverlayEntry 场景用的玻璃 popup 包装：复用 [GlassPopupRoute] 的定位
/// 算法和 unfold 动画，但不走 [Navigator]。
///
/// 为什么不走 Navigator：在输入框聚焦+软键盘弹起时打开菜单，[Navigator.push] →
/// [ModalRoute.didPush] 会调 `setFirstFocus` 把焦点从 TextField 抢到 popup 的
/// FocusScope 上，TextField 失焦 → 软键盘塌陷 → 输入栏下沉 → popup 锚点
/// (popup 弹出瞬间按按钮屏幕坐标算出来的 [Rect]) 就停在"原来高位置"和按钮错开。
/// 这里关键的坑是 `ModalRoute.didPush` 里的判断条件是
/// `navigator.widget.requestFocus`(Navigator 的)，**不是** Route 的——
/// 所以给 [PopupRoute] 传 `requestFocus: false` 没用。只能彻底跳过 Navigator。
///
/// 用法：把这个 widget 放进 [OverlayEntry]，传一个
/// `GlobalKey<GlassPopupOverlayContentState>`。选完之后调
/// [GlassPopupOverlayContentState.playReverse] 播完收起动画再把 entry 从
/// overlay 里 `remove()`。
class GlassPopupOverlayContent extends StatefulWidget {
  const GlassPopupOverlayContent({
    super.key,
    required this.anchor,
    required this.child,
    this.preferBelow = true,
    this.verticalGap = 6,
    this.screenPadding = const EdgeInsets.all(8),
    this.unfoldAlignment,
    this.horizontalPlacement = GlassPopupHorizontalPlacement.edgeAlign,
    this.duration = const Duration(milliseconds: 380),
    this.reverseDuration = const Duration(milliseconds: 220),
    this.curve = Curves.easeOutCubic,
    this.reverseCurve = Curves.easeInCubic,
  });

  final Rect anchor;
  final Widget child;
  final bool preferBelow;
  final double verticalGap;
  final EdgeInsets screenPadding;
  final Alignment? unfoldAlignment;
  final GlassPopupHorizontalPlacement horizontalPlacement;
  final Duration duration;
  final Duration reverseDuration;
  final Curve curve;
  final Curve reverseCurve;

  @override
  State<GlassPopupOverlayContent> createState() =>
      GlassPopupOverlayContentState();
}

class GlassPopupOverlayContentState extends State<GlassPopupOverlayContent>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;
  final ValueNotifier<Alignment> _alignmentNotifier =
      ValueNotifier<Alignment>(Alignment.topCenter);

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: widget.duration,
      reverseDuration: widget.reverseDuration,
    );
    _controller.forward();
  }

  @override
  void dispose() {
    _controller.dispose();
    _alignmentNotifier.dispose();
    super.dispose();
  }

  /// 触发收起动画。返回的 [Future] 在动画完成时 resolve；调用方应该接着把外层
  /// [OverlayEntry] 从 [Overlay] 中 `remove()`。
  Future<void> playReverse() async {
    if (!mounted) return;
    try {
      await _controller.reverse();
    } on TickerCanceled {
      // 收起途中被 dispose,正常退出。
    }
  }

  @override
  Widget build(BuildContext context) {
    final mediaQuery = MediaQuery.of(context);
    return CustomSingleChildLayout(
      delegate: _GlassPopupLayoutDelegate(
        anchor: widget.anchor,
        preferBelow: widget.preferBelow,
        verticalGap: widget.verticalGap,
        screenPadding: widget.screenPadding,
        // Root overlays keep full-screen coordinates when the IME opens.
        mediaPadding: mediaQuery.padding + mediaQuery.viewInsets,
        textDirection: Directionality.of(context),
        explicitUnfoldAlignment: widget.unfoldAlignment,
        horizontalPlacement: widget.horizontalPlacement,
        onAlignmentResolved: (alignment) {
          if (_alignmentNotifier.value != alignment) {
            scheduleMicrotask(() {
              if (mounted && _alignmentNotifier.value != alignment) {
                _alignmentNotifier.value = alignment;
              }
            });
          }
        },
      ),
      child: _GlassPopupAnimatedFrame(
        animation: _controller.view,
        alignmentNotifier: _alignmentNotifier,
        curve: widget.curve,
        reverseCurve: widget.reverseCurve,
        child: widget.child,
      ),
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
        // Root overlays keep full-screen coordinates when the IME opens.
        mediaPadding: mediaQuery.padding + mediaQuery.viewInsets,
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

/// 读取仍处于活动树中的 context 对应的 render object。
///
/// 页面切换时，旧页面的 State 可能还 mounted，但它的 Element 已经进入
/// inactive 状态。此时直接调用 `findRenderObject()` 会在 debug/profile 设备
/// 上触发 Flutter 断言，尤其容易发生在 post-frame 的弹窗锚点测量中。
bool isActiveBuildContext(BuildContext? context) {
  if (context == null || !context.mounted) {
    return false;
  }

  var active = true;
  assert(() {
    if (context is Element) {
      active = context.debugIsActive;
    }
    return true;
  }());
  return active;
}

RenderObject? findActiveRenderObject(BuildContext? context) {
  if (!isActiveBuildContext(context)) {
    return null;
  }
  final activeContext = context!;

  try {
    return activeContext.findRenderObject();
  } on FlutterError {
    // The element can be deactivated between the checks and the lookup while
    // a route transition is being committed. Treat it as not measurable.
    return null;
  }
}

/// 从 [BuildContext] 对应的 [RenderBox] 中提取 anchor 矩形（overlay 坐标系）。
/// 调用前请确保 widget 已经完成 layout（`hasSize == true`）。
Rect? glassPopupAnchorFromContext(BuildContext anchorContext) {
  if (!isActiveBuildContext(anchorContext)) {
    return null;
  }
  final overlayState = Overlay.maybeOf(anchorContext);
  final overlay = findActiveRenderObject(overlayState?.context) as RenderBox?;
  final anchorBox = findActiveRenderObject(anchorContext) as RenderBox?;
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
  if (!isActiveBuildContext(context)) {
    return null;
  }
  final overlayState = Overlay.maybeOf(context);
  final overlay = findActiveRenderObject(overlayState?.context) as RenderBox?;
  if (overlay == null) return null;
  final local = overlay.globalToLocal(globalPosition);
  return Rect.fromLTWH(local.dx, local.dy, 0, 0);
}

/// 把 [GlassPopupOverlayContent] 打包成"开箱即用"的 [OverlayEntry] popup —— 一行
/// 调用就能拿到一份覆盖完整生命周期的 popup:
///   - 透明 [Material] 祖先(避免子树 [Text] 回落到 debug fallback 样式: 黄色
///     下划线 + 红色波浪);
///   - [BackButtonListener] 接管系统返回(可选);
///   - [DismissOverlayOnKeyboardHide] 处理"软键盘弹起时返回手势被 IME 吃一击,
///     Flutter 收不到 back"的特殊路径(可选);
///   - [Positioned.fill] tap-outside 兜底关闭(可选);
///   - [GlassPopupOverlayContent.playReverse] 收起动画 + [OverlayEntry.remove]
///     的清理时序(含 `_dismissing` 防重入)。
///
/// 返回 [OverlayGlassPopupHandle]:
///   - `await handle.future` 拿到 [dismiss] 携带的返回值;
///   - `handle.dismiss([result])` 主动关闭(走 [playReverse]);
///   - `handle.isOpen` 查当前是否还在显示。
///
/// 为什么不能走 [showGlassPopup] / [Navigator.push]: 见 [GlassPopupOverlayContent]
/// 文档 —— 软键盘弹起时 push route 会调 `setFirstFocus` 抢走 TextField 焦点 → IME
/// 塌陷 → 输入栏下沉 → popup 锚点错位。挂 Overlay 是唯一干净的绕道。
class OverlayGlassPopupHandle<T> {
  OverlayGlassPopupHandle._();

  final Completer<T?> _completer = Completer<T?>();
  final GlobalKey<GlassPopupOverlayContentState> _wrapperKey =
      GlobalKey<GlassPopupOverlayContentState>();
  OverlayEntry? _entry;
  bool _dismissing = false;
  bool _keepOpenOnNextKeyboardHide = false;

  /// 等待 dismiss 携带的返回值。被取消(tap-outside / back / keyboard hide /
  /// 调用方主动 dismiss 不传 result) 时 resolve 为 `null`。
  Future<T?> get future => _completer.future;

  /// popup 是否还挂在 overlay 上(尚未走完关闭动画 + remove)。
  bool get isOpen => _entry != null;

  /// 标记"接下来的这一次键盘隐藏不要关 popup"——豁免一次性。典型用法:popup 内部
  /// 有搜索框,用户按软键盘的"确定"提交搜索时主动 unfocus,IME 会塌陷,但我们
  /// 希望 popup 还留着展示搜索结果。调用方先打开这个标志再 unfocus 即可。
  ///
  /// 内部由 [showOverlayGlassPopup] 在挂的 [DismissOverlayOnKeyboardHide] 上
  /// 通过 [_consumeKeepOpenForNextKeyboardHide] 消费,消费完即复位,下一次键盘
  /// 隐藏(如系统返回手势先关 IME) 仍然会正常关 popup。
  void keepOpenOnNextKeyboardHide() {
    _keepOpenOnNextKeyboardHide = true;
  }

  bool _consumeKeepOpenForNextKeyboardHide() {
    if (_keepOpenOnNextKeyboardHide) {
      _keepOpenOnNextKeyboardHide = false;
      return true;
    }
    return false;
  }

  /// 主动关闭 popup。可重复调用(后续调用是 no-op)。
  ///
  /// 时序: complete future(让 [future] 的 await 立刻 resolve,UI 后续逻辑可以并行
  /// 起跑) → await playReverse 走完收起动画 → 从 overlay 摘除 entry。
  Future<void> dismiss([T? result]) async {
    if (_dismissing) return;
    _dismissing = true;
    if (!_completer.isCompleted) {
      _completer.complete(result);
    }
    final wrapper = _wrapperKey.currentState;
    if (wrapper != null) {
      await wrapper.playReverse();
    }
    final entry = _entry;
    _entry = null;
    if (entry != null && entry.mounted) {
      entry.remove();
    }
  }
}

/// 见 [OverlayGlassPopupHandle] 类的文档。
OverlayGlassPopupHandle<T> showOverlayGlassPopup<T>({
  required BuildContext context,
  required Rect anchor,
  required Widget Function(OverlayGlassPopupHandle<T> handle) builder,
  bool preferBelow = true,
  double verticalGap = 6,
  EdgeInsets screenPadding = const EdgeInsets.all(8),
  Alignment? unfoldAlignment,
  GlassPopupHorizontalPlacement horizontalPlacement =
      GlassPopupHorizontalPlacement.edgeAlign,
  Duration transitionDuration = const Duration(milliseconds: 380),
  Duration reverseTransitionDuration = const Duration(milliseconds: 220),
  Curve curve = Curves.easeOutCubic,
  Curve reverseCurve = Curves.easeInCubic,
  bool useRootOverlay = true,
  bool dismissOnTapOutside = true,
  bool dismissOnKeyboardHide = true,
  bool dismissOnBackButton = true,
}) {
  final handle = OverlayGlassPopupHandle<T>._();
  final overlayState = Overlay.of(context, rootOverlay: useRootOverlay);

  final entry = OverlayEntry(
    builder: (overlayContext) {
      Widget tree = Material(
        type: MaterialType.transparency,
        child: Stack(
          children: [
            if (dismissOnTapOutside)
              Positioned.fill(
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: () => unawaited(handle.dismiss()),
                ),
              ),
            GlassPopupOverlayContent(
              key: handle._wrapperKey,
              anchor: anchor,
              preferBelow: preferBelow,
              verticalGap: verticalGap,
              screenPadding: screenPadding,
              unfoldAlignment: unfoldAlignment,
              horizontalPlacement: horizontalPlacement,
              duration: transitionDuration,
              reverseDuration: reverseTransitionDuration,
              curve: curve,
              reverseCurve: reverseCurve,
              child: builder(handle),
            ),
          ],
        ),
      );
      if (dismissOnKeyboardHide) {
        tree = DismissOverlayOnKeyboardHide(
          // popup 内有搜索框时,调用方按下软键盘"确定"会先调
          // [OverlayGlassPopupHandle.keepOpenOnNextKeyboardHide] 然后 unfocus,
          // IME 塌陷的这一次会被这里"消费豁免",popup 保留可见。
          shouldDismissOnKeyboardHide: () =>
              !handle._consumeKeepOpenForNextKeyboardHide(),
          onKeyboardHide: () => unawaited(handle.dismiss()),
          child: tree,
        );
      }
      if (dismissOnBackButton) {
        tree = BackButtonListener(
          onBackButtonPressed: () async {
            unawaited(handle.dismiss());
            return true;
          },
          child: tree,
        );
      }
      return tree;
    },
  );

  handle._entry = entry;
  overlayState.insert(entry);
  return handle;
}

/// 监听 [MediaQuery.viewInsetsOf] 的底部 inset。键盘从可见变成不可见时 (典型场景:
/// Android 系统返回手势在键盘弹起状态下先被平台吃掉一击,只关键盘不传到 Flutter,
/// popup 留在原地;还有 home/外部应用切走) 调一次 [onKeyboardHide]。
///
/// 用法:挂在 OverlayEntry 里包住 popup 内容。这样一次系统返回就能把键盘和
/// popup 一起收掉,符合"输入框聚焦+popup 打开→返回→什么都没了"的直觉。
class DismissOverlayOnKeyboardHide extends StatefulWidget {
  const DismissOverlayOnKeyboardHide({
    super.key,
    required this.onKeyboardHide,
    required this.child,
    this.shouldDismissOnKeyboardHide,
  });

  final VoidCallback onKeyboardHide;
  final Widget child;

  /// 可选谓词。返回 `false` 时本次键盘隐藏不触发 [onKeyboardHide],但 `_firedOnce`
  /// 仍标记为已触发——也就是说"这一次豁免"是把这次键盘隐藏整个跳过去,而不是
  /// 推迟到下次键盘再落时补触发。
  ///
  /// 典型用法:popup 内嵌搜索框,按软键盘"确定"主动 unfocus 时希望保留 popup;
  /// 由调用方提前打开一次性豁免标志位,这里就会读到 `false`、跳过本次 dismiss。
  final bool Function()? shouldDismissOnKeyboardHide;

  @override
  State<DismissOverlayOnKeyboardHide> createState() =>
      _DismissOverlayOnKeyboardHideState();
}

class _DismissOverlayOnKeyboardHideState
    extends State<DismissOverlayOnKeyboardHide> {
  /// 要把 peak 视作"键盘是真的弹起过",最小高度;<50dp 通常是 nav bar / 手势
  /// gutter 之类的固定 inset,不算键盘。
  static const double _kKeyboardPeakMinimum = 50.0;

  /// 已经看到的最大 viewInsets.bottom。键盘高度可能随键盘类型(文本/表情/语音)
  /// 变化,我们只把"曾经达到过的最高值"当作 peak,避免切换布局时误触发收起。
  double _peakInset = 0;
  bool _firedOnce = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final bottomInset = MediaQuery.viewInsetsOf(context).bottom;
    // 键盘从未弹起(或已经完全收起)时把 peak/firedOnce 复位。否则:用户打开 popup
    // → 键盘弹一次塌一次 → _firedOnce 永远停在 true → 此后键盘再弹起再收起,
    // 这个 widget 不会再触发 dismiss,Overlay 看起来"豁免错了次"。
    if (bottomInset <= 0) {
      _peakInset = 0;
      _firedOnce = false;
      return;
    }
    if (bottomInset > _peakInset) {
      _peakInset = bottomInset;
    }
    // 关键时序:Android 的 IME hide 是动画的(~250ms),如果等 viewInsets.bottom
    // 全部跌到 0 再触发收起 popup,popup 的反向动画(220ms)就要排在 IME 动画
    // 之后跑,用户看到的延迟 ≈ 250+220 ms,popup 卡顿明显。改为检测"开始下落"
    // 的瞬间(从 peak 跌掉 ≥ 10%),触发 popup 反向动画并行跑——这样 IME 动画
    // 跑完时 popup 也几乎同时消失。
    //
    // 阈值 10% 而不是固定像素,是因为 PJD110/ColorOS 的 viewPadding 在静稳态
    // 也会有亚像素抖动(见 composer-keyboard-debug-rig 笔记),百分比阈值
    // 对噪声更稳健;peak 必须 ≥ 50dp 进一步保证是真键盘弹起过。
    if (!_firedOnce &&
        _peakInset > _kKeyboardPeakMinimum &&
        bottomInset < _peakInset * 0.9) {
      _firedOnce = true;
      // 豁免本次键盘隐藏:谓词返回 false 就跳过整个 dismiss 路径,_firedOnce
      // 仍保持 true 直到下次键盘从无到有再 reset。
      if (widget.shouldDismissOnKeyboardHide?.call() == false) {
        return;
      }
      // 不能在 didChangeDependencies 同步调 callback——callback 可能 setState,
      // 而本帧正在 build。延到下一帧。
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) widget.onKeyboardHide();
      });
    }
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
