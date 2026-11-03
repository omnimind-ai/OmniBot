import 'package:flutter/material.dart';
import 'package:flutter/widget_previews.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

import 'search_header.dart';
import 'liquid_glass.dart';
import 'liquid_glass_preview_frame.dart';

@Preview(name: 'FlClash proxy search · light', size: Size(390, 220))
Widget flClashSearchLightPreview() => const _SearchPreview();

@Preview(name: 'FlClash proxy search · dark', size: Size(390, 220))
Widget flClashSearchDarkPreview() => const _SearchPreview(dark: true);

@Preview(name: 'FlClash search glass · light · standard', size: Size(390, 300))
Widget flClashSearchGlassLightStandardPreview() =>
    const _SearchPreview(glass: true, quality: LiquidGlassQuality.standard);

@Preview(name: 'FlClash search glass · light · ultra', size: Size(390, 300))
Widget flClashSearchGlassLightUltraPreview() =>
    const _SearchPreview(glass: true);

@Preview(name: 'FlClash search glass · dark · standard', size: Size(390, 300))
Widget flClashSearchGlassDarkStandardPreview() => const _SearchPreview(
  dark: true,
  glass: true,
  quality: LiquidGlassQuality.standard,
);

@Preview(name: 'FlClash search glass · dark · ultra', size: Size(390, 300))
Widget flClashSearchGlassDarkUltraPreview() =>
    const _SearchPreview(dark: true, glass: true);

class _SearchPreview extends StatefulWidget {
  const _SearchPreview({
    this.dark = false,
    this.glass = false,
    this.quality = LiquidGlassQuality.ultra,
  });
  final bool dark;
  final bool glass;
  final LiquidGlassQuality quality;
  @override
  State<_SearchPreview> createState() => _SearchPreviewState();
}

class _SearchPreviewState extends State<_SearchPreview> {
  bool _searching = false;
  final _controller = TextEditingController();
  final _focus = FocusNode();
  @override
  void dispose() {
    _controller.dispose();
    _focus.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return LiquidGlassPreviewFrame(
      dark: widget.dark,
      glass: widget.glass,
      quality: widget.quality,
      builder: (context) => Column(
        children: [
          FlClashSearchHeader(
            title: const Text('Agents'),
            searching: _searching,
            controller: _controller,
            focusNode: _focus,
            onSearch: () => setState(() => _searching = true),
            onClose: () {
              _controller.clear();
              _focus.unfocus();
              setState(() => _searching = false);
            },
            actions: [
              IconButton(onPressed: () {}, icon: const Icon(LucideIcons.plus)),
              IconButton(
                onPressed: () {},
                icon: const Icon(LucideIcons.ellipsis),
              ),
            ],
            searchActions: [
              IconButton(onPressed: () {}, icon: const Icon(LucideIcons.plus)),
            ],
          ),
          const Expanded(
            child: Center(child: Text('点击搜索，查看 FlClash 的展开与收起动画')),
          ),
        ],
      ),
    );
  }
}
