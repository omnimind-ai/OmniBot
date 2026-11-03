import 'package:flutter/material.dart';
import 'package:flutter/widget_previews.dart';

import 'flclash/liquid_glass.dart';
import 'flclash/liquid_glass_preview_frame.dart';
import 'omni_slider.dart';

@Preview(name: 'Omni value slider · light', size: Size(390, 260))
Widget omniSliderLightPreview() => const _SliderPreview();

@Preview(name: 'Omni value slider · dark', size: Size(390, 260))
Widget omniSliderDarkPreview() => const _SliderPreview(dark: true);

@Preview(name: 'Omni value slider · glass · light', size: Size(390, 320))
Widget omniSliderGlassLightPreview() => const _SliderPreview(glass: true);

@Preview(name: 'Omni value slider · glass · dark', size: Size(390, 320))
Widget omniSliderGlassDarkPreview() =>
    const _SliderPreview(dark: true, glass: true);

class _SliderPreview extends StatefulWidget {
  const _SliderPreview({this.dark = false, this.glass = false});

  final bool dark;
  final bool glass;

  @override
  State<_SliderPreview> createState() => _SliderPreviewState();
}

class _SliderPreviewState extends State<_SliderPreview> {
  double _blur = 10;
  double _size = 16;

  @override
  Widget build(BuildContext context) => LiquidGlassPreviewFrame(
    dark: widget.dark,
    glass: widget.glass,
    quality: LiquidGlassQuality.ultra,
    builder: (context) => Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('背景柔化 · ${_blur.toStringAsFixed(2)}'),
          const SizedBox(height: 8),
          OmniSlider(
            value: _blur,
            min: 0,
            max: 24,
            semanticLabel: '背景柔化',
            semanticFormatterCallback: (value) => value.toStringAsFixed(2),
            onChanged: (value) => setState(() => _blur = value),
          ),
          const SizedBox(height: 16),
          Text('聊天字号 · ${_size.toStringAsFixed(1)}sp'),
          const SizedBox(height: 8),
          OmniSlider(
            value: _size,
            min: 12,
            max: 22,
            semanticLabel: '聊天字号',
            semanticFormatterCallback: (value) =>
                '${value.toStringAsFixed(1)}sp',
            onChanged: (value) => setState(() => _size = value),
          ),
        ],
      ),
    ),
  );
}
