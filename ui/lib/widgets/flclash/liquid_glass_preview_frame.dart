import 'package:flutter/material.dart';
import 'package:ui/widgets/omni_segmented_slider.dart';
import 'package:ui/theme/omni_theme_palette.dart';

import 'common_switch.dart';
import 'liquid_glass.dart';

/// Shared controls for isolated previews; the solid previews keep their layout.
class LiquidGlassPreviewFrame extends StatefulWidget {
  const LiquidGlassPreviewFrame({
    super.key,
    required this.dark,
    required this.glass,
    required this.quality,
    required this.builder,
  });

  final bool dark;
  final bool glass;
  final LiquidGlassQuality quality;
  final WidgetBuilder builder;

  @override
  State<LiquidGlassPreviewFrame> createState() =>
      _LiquidGlassPreviewFrameState();
}

class _LiquidGlassPreviewFrameState extends State<LiquidGlassPreviewFrame> {
  late bool _enabled = widget.glass;
  late LiquidGlassQuality _quality = widget.quality;

  @override
  Widget build(BuildContext context) {
    final palette = widget.dark
        ? OmniThemePalette.dark
        : OmniThemePalette.light;
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: widget.dark ? Brightness.dark : Brightness.light,
        colorSchemeSeed: palette.accentPrimary,
        extensions: [palette],
      ),
      home: LiquidGlassScope(
        enabled: _enabled,
        quality: _quality,
        child: LiquidGlassHost(
          enabled: _enabled,
          child: Scaffold(
            backgroundColor: palette.pageBackground,
            body: Column(
              children: [
                if (widget.glass)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(12, 8, 12, 6),
                    child: Row(
                      children: [
                        Expanded(
                          child: OmniSegmentedSlider<LiquidGlassQuality>(
                            keyPrefix: 'preview-glass-quality',
                            value: _quality,
                            options: const [
                              OmniSegmentedOption(
                                value: LiquidGlassQuality.standard,
                                label: '标准',
                              ),
                              OmniSegmentedOption(
                                value: LiquidGlassQuality.ultra,
                                label: '极致',
                              ),
                            ],
                            onChanged: (value) =>
                                setState(() => _quality = value),
                          ),
                        ),
                        const SizedBox(width: 12),
                        CommonSwitch(
                          value: _enabled,
                          onChanged: (value) =>
                              setState(() => _enabled = value),
                        ),
                      ],
                    ),
                  ),
                Expanded(
                  child: Stack(
                    children: [
                      if (widget.glass)
                        const Positioned.fill(child: _Backdrop()),
                      Positioned.fill(child: Builder(builder: widget.builder)),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Backdrop extends StatelessWidget {
  const _Backdrop();

  @override
  Widget build(BuildContext context) => RepaintBoundary(
    child: DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            Theme.of(context).colorScheme.primaryContainer,
            Theme.of(context).colorScheme.surface,
            Theme.of(context).colorScheme.tertiaryContainer,
          ],
        ),
      ),
      child: CustomPaint(painter: _BackdropLines()),
    ),
  );
}

class _BackdropLines extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = const Color(0x3081878F)
      ..strokeWidth = 2;
    for (var x = -size.height; x < size.width; x += 28) {
      canvas.drawLine(
        Offset(x, 0),
        Offset(x + size.height, size.height),
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(_BackdropLines oldDelegate) => false;
}
