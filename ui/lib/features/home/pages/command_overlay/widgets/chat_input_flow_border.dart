part of 'chat_input_area.dart';

extension _ChatInputVisualMotion on _ChatInputAreaStateBase {
  Duration get _compactAnimationDuration =>
      MediaQuery.disableAnimationsOf(context)
      ? Duration.zero
      : const Duration(milliseconds: 220);

  void _syncComposerFlowAnimation() {
    final animate =
        !widget.minimalComposer &&
        widget.useLargeComposerStyle &&
        !LiquidGlassScope.of(context) &&
        !MediaQuery.disableAnimationsOf(context);
    if (animate && !_composerFlowController.isAnimating) {
      _composerFlowController.repeat();
    } else if (!animate) {
      _composerFlowController.stop();
    }
  }
}

class _ComposerFlowBorderPainter extends CustomPainter {
  final Animation<double> progress;
  final bool interactive;
  final bool focused;
  final bool forceStrong;
  final double radius;
  final double strokeWidth;
  final List<Color> gradientColors;

  _ComposerFlowBorderPainter({
    required this.progress,
    required this.interactive,
    required this.focused,
    required this.forceStrong,
    required this.radius,
    required this.strokeWidth,
    required this.gradientColors,
  }) : super(repaint: progress);

  @override
  void paint(Canvas canvas, Size size) {
    final flow = progress.value;
    final breath = (math.sin(flow * 2 * math.pi) + 1) / 2;
    final speed = focused ? 1.6 : 1.0;
    final shift = ((flow * speed) % 1.0) * 2 - 1;
    final rawOpacity = forceStrong
        ? 0.9
        : (interactive ? (focused ? 1.0 : 0.82) : (0.3 + breath * 0.4));
    final clampedOpacity = rawOpacity.clamp(0.0, 1.0);
    if (clampedOpacity <= 0 || size.isEmpty) return;

    final rect = Offset.zero & size;
    final rrect = RRect.fromRectAndRadius(
      rect.deflate(strokeWidth / 2),
      Radius.circular(radius - strokeWidth / 2),
    );
    final gradient = LinearGradient(
      begin: Alignment(-1 + shift, 0),
      end: Alignment(1 + shift, 0),
      colors: gradientColors
          .map((color) => color.withValues(alpha: clampedOpacity))
          .toList(growable: false),
      stops: const [0.0, 0.2, 0.4, 0.62, 0.82, 1.0],
    );

    final paint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth
      ..isAntiAlias = true
      ..shader = gradient.createShader(rect);

    canvas.drawRRect(rrect, paint);
  }

  @override
  bool shouldRepaint(covariant _ComposerFlowBorderPainter oldDelegate) {
    return oldDelegate.progress != progress ||
        oldDelegate.interactive != interactive ||
        oldDelegate.focused != focused ||
        oldDelegate.forceStrong != forceStrong ||
        oldDelegate.radius != radius ||
        oldDelegate.strokeWidth != strokeWidth ||
        oldDelegate.gradientColors != gradientColors;
  }
}
