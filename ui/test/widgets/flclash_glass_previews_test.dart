import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/flclash/common_switch.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/flclash/navigation_dock_previews.dart';
import 'package:ui/widgets/flclash/search_header_previews.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const fontPath = String.fromEnvironment('FLCLASH_PREVIEW_FONT');
  setUpAll(() async {
    // Optional local review font; normal tests remain portable and use Ahem.
    if (fontPath.isEmpty) return;
    final font = ByteData.sublistView(await File(fontPath).readAsBytes());
    for (final family in ['Roboto', 'Ahem']) {
      await (FontLoader(family)..addFont(Future.value(font))).load();
    }
    await (FontLoader('packages/lucide_icons_flutter/Lucide')..addFont(
          rootBundle.load('packages/lucide_icons_flutter/assets/lucide.ttf'),
        ))
        .load();
    await (FontLoader(
      'MaterialIcons',
    )..addFont(rootBundle.load('fonts/MaterialIcons-Regular.otf'))).load();
  });
  const output = String.fromEnvironment('FLCLASH_PREVIEW_OUTPUT');
  final previews = <String, Widget Function()>{
    'dock-light-standard': flClashNavigationGlassLightStandardPreview,
    'dock-light-ultra': flClashNavigationGlassLightUltraPreview,
    'dock-dark-standard': flClashNavigationGlassDarkStandardPreview,
    'dock-dark-ultra': flClashNavigationGlassDarkUltraPreview,
    'rail-light-standard': flClashRailGlassLightStandardPreview,
    'rail-light-ultra': flClashRailGlassLightUltraPreview,
    'rail-dark-standard': flClashRailGlassDarkStandardPreview,
    'rail-dark-ultra': flClashRailGlassDarkUltraPreview,
    'search-light-standard': flClashSearchGlassLightStandardPreview,
    'search-light-ultra': flClashSearchGlassLightUltraPreview,
    'search-dark-standard': flClashSearchGlassDarkStandardPreview,
    'search-dark-ultra': flClashSearchGlassDarkUltraPreview,
  };
  for (final preview in previews.entries) {
    testWidgets('${preview.key} preview renders and toggles its material', (
      tester,
    ) async {
      final rail = preview.key.startsWith('rail-');
      tester.view.physicalSize = rail
          ? const Size(390, 540)
          : const Size(390, 300);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final key = GlobalKey();
      await tester.pumpWidget(
        RepaintBoundary(key: key, child: preview.value()),
      );
      await tester.pumpAndSettle();
      expect(LiquidGlassHost.debugHeldCaptures, greaterThan(0));
      expect(tester.takeException(), isNull);
      Future<void> capture(String name) async {
        if (output.isEmpty) return;
        final boundary =
            key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
        final image = boundary.toImageSync();
        await tester.runAsync(() async {
          final png = await image.toByteData(format: ui.ImageByteFormat.png);
          await Directory(output).create(recursive: true);
          await File('$output/$name.png')
              .writeAsBytes(png!.buffer.asUint8List());
          image.dispose();
        });
      }

      await capture(preview.key);
      if (rail) {
        final first = find.byKey(const ValueKey('main-navigation-rail-0'));
        final third = find.byKey(const ValueKey('main-navigation-rail-2'));
        final gesture = await tester.startGesture(tester.getCenter(first));
        await tester.pump(const Duration(milliseconds: 100));
        await gesture.moveTo(tester.getCenter(third));
        // Start the retargeted spring's ticker before advancing its time.
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 160));
        expect(find.byType(LiquidGlass).evaluate().length, greaterThan(1));
        await capture('${preview.key}-drag');
        await gesture.up();
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
      }
      await tester.tap(
        find.text(preview.key.endsWith('standard') ? '极致' : '标准'),
      );
      await tester.pumpAndSettle();
      expect(LiquidGlassHost.debugHeldCaptures, greaterThan(0));
      await tester.tap(find.byType(CommonSwitch));
      await tester.pumpAndSettle();
      expect(LiquidGlassHost.debugHeldCaptures, 0);
      await tester.pumpWidget(const SizedBox.shrink());
      expect(LiquidGlassHost.debugHeldCaptures, 0);
      expect(tester.takeException(), isNull);
    });
  }
}
