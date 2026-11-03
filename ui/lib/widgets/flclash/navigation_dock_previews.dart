import 'package:flutter/material.dart';
import 'package:flutter/widget_previews.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/widgets/omni_navigation_rail.dart';

import 'navigation_dock.dart';
import 'liquid_glass.dart';
import 'liquid_glass_preview_frame.dart';

@Preview(name: 'FlClash navigation · light', size: Size(390, 240))
Widget flClashNavigationLightPreview() => const _DockPreview();

@Preview(name: 'FlClash navigation · dark', size: Size(390, 240))
Widget flClashNavigationDarkPreview() => const _DockPreview(dark: true);

@Preview(
  name: 'FlClash navigation glass · light · standard',
  size: Size(390, 300),
)
Widget flClashNavigationGlassLightStandardPreview() =>
    const _DockPreview(glass: true, quality: LiquidGlassQuality.standard);

@Preview(name: 'FlClash navigation glass · light · ultra', size: Size(390, 300))
Widget flClashNavigationGlassLightUltraPreview() =>
    const _DockPreview(glass: true);

@Preview(
  name: 'FlClash navigation glass · dark · standard',
  size: Size(390, 300),
)
Widget flClashNavigationGlassDarkStandardPreview() => const _DockPreview(
  dark: true,
  glass: true,
  quality: LiquidGlassQuality.standard,
);

@Preview(name: 'FlClash navigation glass · dark · ultra', size: Size(390, 300))
Widget flClashNavigationGlassDarkUltraPreview() =>
    const _DockPreview(dark: true, glass: true);

@Preview(name: 'FlClash rail glass · light · standard', size: Size(390, 540))
Widget flClashRailGlassLightStandardPreview() => const _DockPreview(
  rail: true,
  glass: true,
  quality: LiquidGlassQuality.standard,
);

@Preview(name: 'FlClash rail glass · light · ultra', size: Size(390, 540))
Widget flClashRailGlassLightUltraPreview() =>
    const _DockPreview(rail: true, glass: true);

@Preview(name: 'FlClash rail glass · dark · standard', size: Size(390, 540))
Widget flClashRailGlassDarkStandardPreview() => const _DockPreview(
  rail: true,
  dark: true,
  glass: true,
  quality: LiquidGlassQuality.standard,
);

@Preview(name: 'FlClash rail glass · dark · ultra', size: Size(390, 540))
Widget flClashRailGlassDarkUltraPreview() =>
    const _DockPreview(rail: true, dark: true, glass: true);

class _DockPreview extends StatefulWidget {
  const _DockPreview({
    this.dark = false,
    this.glass = false,
    this.rail = false,
    this.quality = LiquidGlassQuality.ultra,
  });

  final bool dark;
  final bool glass;
  final bool rail;
  final LiquidGlassQuality quality;

  @override
  State<_DockPreview> createState() => _DockPreviewState();
}

class _DockPreviewState extends State<_DockPreview> {
  int _selectedIndex = 0;
  static const _destinations = [
    FlClashNavigationDestination(icon: LucideIcons.house, label: '首页'),
    FlClashNavigationDestination(icon: LucideIcons.bot, label: 'Agents'),
    FlClashNavigationDestination(icon: LucideIcons.route, label: '轨迹'),
    FlClashNavigationDestination(icon: LucideIcons.settings, label: '设置'),
  ];

  void _select(int value) => setState(() => _selectedIndex = value);

  @override
  Widget build(BuildContext context) {
    return LiquidGlassPreviewFrame(
      dark: widget.dark,
      glass: widget.glass,
      quality: widget.quality,
      builder: (context) => Stack(
        children: [
          Center(child: Text(['首页', 'Agents', '轨迹', '设置'][_selectedIndex])),
          Align(
            alignment: widget.rail
                ? Alignment.centerLeft
                : Alignment.bottomCenter,
            child: widget.rail
                ? SizedBox(
                    width: OmniNavigationRail.width,
                    child: OmniNavigationRail(
                      destinations: _destinations,
                      selectedIndex: _selectedIndex,
                      onDestinationSelected: _select,
                    ),
                  )
                : FlClashNavigationDock(
                    selectedIndex: _selectedIndex,
                    onDestinationSelected: _select,
                    destinations: _destinations,
                  ),
          ),
        ],
      ),
    );
  }
}
