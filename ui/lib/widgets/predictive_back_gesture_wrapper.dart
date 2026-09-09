import 'package:flutter/cupertino.dart';
import 'package:ui/services/display_geometry_service.dart';
import 'package:ui/widgets/miuix_squircle_clip.dart';
import 'package:ui/widgets/predictive_back_motion.dart';

/// 与 [PageRoute] transitionsBuilder 一致的转场构建函数签名。
typedef PredictiveBackTransitionBuilder = Widget Function(
  BuildContext context,
  Animation<double> animation,
  Animation<double> secondaryAnimation,
  Widget child,
);

/// 绘制由路由控制器统一驱动的 Miuix 页面转场。
///
/// 页面位置始终是路由动画值的纯函数：顶层页面全宽滑出，下一层页面以
/// 四分之一屏宽做视差，并由同一个进度控制背景遮罩。手势移动时
/// 不使用额外缓动；松手后从当前位置和当前速度继续运行临界阻尼弹簧。
class PredictiveBackGestureWrapper extends StatefulWidget {
  const PredictiveBackGestureWrapper({
    super.key,
    required this.animation,
    required this.secondaryAnimation,
    required this.transitionBuilder,
    required this.child,
  });

  final Animation<double> animation;
  final Animation<double> secondaryAnimation;
  final PredictiveBackTransitionBuilder transitionBuilder;
  final Widget child;

  @override
  State<PredictiveBackGestureWrapper> createState() =>
      _PredictiveBackGestureWrapperState();
}

class _PredictiveBackGestureWrapperState
    extends State<PredictiveBackGestureWrapper>
    with WidgetsBindingObserver {
  ScreenCornerRadii _screenCorners = const ScreenCornerRadii.zero();
  int _geometryRequest = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _loadScreenCorners();
    });
  }

  @override
  void didChangeMetrics() {
    _loadScreenCorners(refresh: true);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  bool get _predictiveBackEnabled => PredictiveBackMotion.enabled;

  Future<void> _loadScreenCorners({bool refresh = false}) async {
    if (!_predictiveBackEnabled) return;
    final request = ++_geometryRequest;
    final corners = await DisplayGeometryService.screenCornerRadii(
      refresh: refresh,
    );
    if (!mounted || request != _geometryRequest || corners == _screenCorners) {
      return;
    }
    setState(() {
      _screenCorners = corners;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (!_predictiveBackEnabled) {
      return widget.transitionBuilder(
        context,
        widget.animation,
        widget.secondaryAnimation,
        widget.child,
      );
    }

    return PredictiveBackPageTransition(
      animation: widget.animation,
      secondaryAnimation: widget.secondaryAnimation,
      screenCorners: _screenCorners,
      hasPreviousPage: !(ModalRoute.of(context)?.isFirst ?? true),
      child: RepaintBoundary(child: widget.child),
    );
  }
}

@visibleForTesting
class PredictiveBackPageTransition extends StatelessWidget {
  const PredictiveBackPageTransition({
    super.key,
    required this.animation,
    required this.secondaryAnimation,
    required this.screenCorners,
    this.hasPreviousPage = true,
    required this.child,
  });

  final Animation<double> animation;
  final Animation<double> secondaryAnimation;
  final bool hasPreviousPage;
  final ScreenCornerRadii screenCorners;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final listenable = Listenable.merge([animation, secondaryAnimation]);
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        final devicePixelRatio = MediaQuery.devicePixelRatioOf(context);
        final textDirection = Directionality.of(context);
        final direction = textDirection == TextDirection.rtl ? -1.0 : 1.0;
        final leadingCorners = screenLeadingBorderRadius(
          screenCorners,
          textDirection,
        );

        return AnimatedBuilder(
          animation: listenable,
          child: child,
          builder: (context, child) {
            final primary = animation.value.clamp(0.0, 1.0);
            final covered = secondaryAnimation.value.clamp(0.0, 1.0);
            final primaryOffset = snapToPhysicalPixel(
              direction * (1 - primary) * width,
              devicePixelRatio,
            );
            final coveredOffset =
                -direction *
                covered *
                width *
                PredictiveBackMotion.coveredParallax;
            // Miuix puts one stationary scrim underneath the moving top page.
            // Attach it to the outgoing/entering route so it also covers the
            // area outside the previous page's parallax transform.
            final scrimAlpha =
                hasPreviousPage && covered == 0 && primary > 0 && primary < 1
                ? primary * PredictiveBackMotion.dimAmount
                : 0.0;
            final clipActive =
                animation.value > 0 &&
                animation.value < 1 &&
                leadingCorners != BorderRadius.zero;

            Widget page = ClipPath(
              key: const ValueKey('predictive_back_corner_clip'),
              clipper: MiuixSquircleClipper(
                clipActive ? leadingCorners : BorderRadius.zero,
              ),
              clipBehavior: clipActive ? Clip.antiAlias : Clip.none,
              child: child,
            );
            page = Transform.translate(
              key: const ValueKey('predictive_back_primary_transform'),
              offset: Offset(primaryOffset, 0),
              child: page,
            );

            page = Opacity(
              key: const ValueKey('predictive_back_covered_opacity'),
              opacity: 1 - covered * PredictiveBackMotion.coveredAlphaLoss,
              child: page,
            );
            page = Transform.translate(
              key: const ValueKey('predictive_back_covered_transform'),
              offset: Offset(coveredOffset, 0),
              child: page,
            );
            final layer = Stack(
              fit: StackFit.expand,
              children: [
                if (scrimAlpha > 0)
                  Positioned.fill(
                    child: IgnorePointer(
                      child: ColoredBox(
                        key: const ValueKey('predictive_back_covered_scrim'),
                        color: const Color(0xFF000000)
                            .withValues(alpha: scrimAlpha),
                      ),
                    ),
                  ),
                page,
              ],
            );
            return layer;
          },
        );
      },
    );
  }
}

@visibleForTesting
BorderRadius screenLeadingBorderRadius(
  ScreenCornerRadii corners,
  TextDirection direction,
) {
  // Miuix rememberNavSystemCornerRadius uses the bottom-left screen radius
  // for both leading corners, including RTL (it is a window radius, not an edge).
  final radius = Radius.circular(corners.bottomLeft);
  if (direction == TextDirection.rtl) {
    return BorderRadius.only(topRight: radius, bottomRight: radius);
  }
  return BorderRadius.only(topLeft: radius, bottomLeft: radius);
}

@visibleForTesting
double snapToPhysicalPixel(double logicalOffset, double devicePixelRatio) {
  if (!logicalOffset.isFinite ||
      !devicePixelRatio.isFinite ||
      devicePixelRatio <= 0) {
    return logicalOffset;
  }
  return (logicalOffset * devicePixelRatio).round() / devicePixelRatio;
}
