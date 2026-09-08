part of 'chat_input_area.dart';

class _ContextUsageRing extends StatelessWidget {
  const _ContextUsageRing({required this.ratio});

  final double ratio;

  @override
  Widget build(BuildContext context) {
    final normalized = ratio.isFinite ? ratio : 0.0;
    final progress = normalized.clamp(0.0, 1.0).toDouble();
    final palette = context.omniPalette;
    final color = context.isDarkTheme
        ? normalized >= 1.0
              ? const Color(0xFFB97862)
              : normalized >= 0.85
              ? const Color(0xFFB39B6B)
              : palette.accentPrimary
        : normalized >= 1.0
        ? const Color(0xFFD65A3A)
        : normalized >= 0.85
        ? const Color(0xFFC69234)
        : const Color(0xFF5A8DDE);
    final trackColor = context.isDarkTheme
        ? Color.lerp(
            palette.surfaceElevated,
            palette.borderStrong,
            0.62,
          )!.withValues(alpha: 0.92)
        : const Color(0x18000000);

    return SizedBox(
      width: 18,
      height: 18,
      child: TweenAnimationBuilder<double>(
        tween: Tween<double>(begin: 0, end: progress),
        duration: const Duration(milliseconds: 220),
        curve: Curves.easeOutCubic,
        builder: (context, value, _) {
          return CustomPaint(
            painter: _ContextUsageRingPainter(
              progress: value,
              color: color,
              trackColor: trackColor,
            ),
          );
        },
      ),
    );
  }
}

class _ContextUsageRingButton extends StatefulWidget {
  const _ContextUsageRingButton({
    required this.ratio,
    this.tooltipMessage,
    this.onLongPress,
  });

  final double? ratio;
  final String? tooltipMessage;
  final VoidCallback? onLongPress;

  @override
  State<_ContextUsageRingButton> createState() =>
      _ContextUsageRingButtonState();
}

class _ContextUsageRingButtonState extends State<_ContextUsageRingButton> {
  // 走 [showOverlayGlassPopup] 而不是 [showGlassPopup] —— 后者 push Navigator
  // route,ModalRoute.didPush 会调 setFirstFocus 把焦点从 TextField 抢到 popup
  // 的 FocusScope,TextField 失焦 → 软键盘塌陷 → 输入栏下沉 → 已经算好的 popup
  // 锚点还停在"键盘弹起时的高位置",视觉上就是 tooltip 飘在原地、输入栏掉到底。
  // 详见 glass_popup.dart 里 [OverlayGlassPopupHandle] 的文档。
  OverlayGlassPopupHandle<void>? _handle;
  Timer? _autoDismissTimer;

  @override
  void dispose() {
    _autoDismissTimer?.cancel();
    unawaited(_handle?.dismiss());
    _handle = null;
    super.dispose();
  }

  void _showTooltip(BuildContext anchorContext, String message) {
    if (_handle != null) {
      // 二次点击当 toggle 关掉,免得反复点击堆叠多个 entry。
      final h = _handle;
      _handle = null;
      _autoDismissTimer?.cancel();
      _autoDismissTimer = null;
      unawaited(h?.dismiss());
      return;
    }
    final anchor = glassPopupAnchorFromContext(anchorContext);
    if (anchor == null) return;

    final handle = showOverlayGlassPopup<void>(
      context: anchorContext,
      anchor: anchor,
      preferBelow: false,
      verticalGap: 8,
      horizontalPlacement: GlassPopupHorizontalPlacement.centerOnAnchor,
      builder: (_) => _ContextUsageGlassTooltipBody(message: message),
    );
    _handle = handle;
    // future resolve 后(任何一条 dismiss 路径触发,含 toggle / 自动 / tap-outside /
    // back / 键盘塌陷)清空状态字段。
    handle.future.whenComplete(() {
      if (!mounted) return;
      if (_handle == handle) {
        _handle = null;
      }
      _autoDismissTimer?.cancel();
      _autoDismissTimer = null;
    });

    _autoDismissTimer = Timer(const Duration(seconds: 3), () {
      if (!mounted) return;
      unawaited(handle.dismiss());
    });
  }

  @override
  Widget build(BuildContext context) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    final hasUsage = widget.ratio?.isFinite == true;
    final ring = SizedBox(
      width: 22,
      height: 22,
      child: Center(
        child: _ContextUsageRing(ratio: hasUsage ? widget.ratio! : 0),
      ),
    );
    final suppliedTooltip = widget.tooltipMessage?.trim() ?? '';
    final tooltip = suppliedTooltip.isNotEmpty
        ? suppliedTooltip
        : !hasUsage
        ? '0%' +
              (widget.onLongPress == null
                  ? ''
                  : english
                  ? '\nLong press to adjust threshold'
                  : '\n长按可调整阈值')
        : '';
    final hasTooltip = tooltip.isEmpty == false;
    if (!hasTooltip && widget.onLongPress == null) {
      return ring;
    }
    return Semantics(
      key: const ValueKey('chat-input-context-usage'),
      button: true,
      label: english ? 'Context usage' : '上下文用量',
      value: tooltip,
      child: Builder(
        builder: (anchorContext) {
          return GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: hasTooltip
                ? () => _showTooltip(anchorContext, tooltip)
                : null,
            onLongPress: widget.onLongPress,
            child: ExcludeSemantics(child: ring),
          );
        },
      ),
    );
  }
}

class _ContextUsageGlassTooltipBody extends StatelessWidget {
  const _ContextUsageGlassTooltipBody({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;
    final textColor = isDark ? palette.textPrimary : const Color(0xFF1F2937);
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 280),
      child: OmniGlassPanel(
        borderRadius: const BorderRadius.all(Radius.circular(14)),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        child: Text(
          message,
          style: TextStyle(
            color: textColor,
            fontSize: 12,
            height: 1.45,
            fontWeight: FontWeight.w500,
          ),
        ),
      ),
    );
  }
}

class _ContextUsageRingPainter extends CustomPainter {
  const _ContextUsageRingPainter({
    required this.progress,
    required this.color,
    required this.trackColor,
  });

  final double progress;
  final Color color;
  final Color trackColor;

  @override
  void paint(Canvas canvas, Size size) {
    if (size.isEmpty) return;
    final strokeWidth = 1.8;
    final radius = (math.min(size.width, size.height) - strokeWidth) / 2;
    final center = Offset(size.width / 2, size.height / 2);
    final rect = Rect.fromCircle(center: center, radius: radius);

    final trackPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth
      ..strokeCap = StrokeCap.round
      ..color = trackColor;
    final progressPaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth
      ..strokeCap = StrokeCap.round
      ..color = color;

    canvas.drawArc(rect, 0, math.pi * 2, false, trackPaint);
    if (progress <= 0) return;
    canvas.drawArc(
      rect,
      -math.pi / 2,
      math.pi * 2 * progress.clamp(0.0, 1.0),
      false,
      progressPaint,
    );
  }

  @override
  bool shouldRepaint(covariant _ContextUsageRingPainter oldDelegate) {
    return oldDelegate.progress != progress ||
        oldDelegate.color != color ||
        oldDelegate.trackColor != trackColor;
  }
}
