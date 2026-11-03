import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/widgets/image_preview_overlay.dart';
import 'package:ui/widgets/omnibot_chat_sticker.dart';
import 'package:ui/widgets/omnibot_markdown_body.dart';
import 'package:ui/widgets/omnibot_resource_widgets.dart';

const _png =
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk'
    'YAAAAAYAAjCB0C8AAAAASUVORK5CYII=';
// Two 24×12 frames, green then yellow, at 100 ms per frame.
const _animatedGif =
    'R0lGODlhGAAMAIEAADyaZQAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAA'
    'ACwAAAAAGAAMAAAIHgABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFiwwDAgAh+QQBCgAB'
    'ACwAAAAAGAAMAIH2zlAAAAAAAAAAAAAIHgABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLF'
    'iwwDAgA7';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Directory workspace;

  setUp(() async {
    workspace = await Directory.systemTemp.createTemp('omnibot-sticker-');
    final stickers = Directory(
      '${workspace.path}/.omnibot/attachments/stickers',
    );
    await stickers.create(recursive: true);
    await File('${stickers.path}/happy.png').writeAsBytes(base64Decode(_png));
    await File('${stickers.path}/happy.gif')
        .writeAsBytes(base64Decode(_animatedGif));
    await File('${stickers.path}/broken.png').writeAsString('unreadable image');
    OmnibotResourceService.debugSetWorkspacePaths(
      OmnibotWorkspacePaths(
        rootPath: workspace.path,
        shellRootPath: '/workspace',
        internalRootPath: '${workspace.path}/.omnibot',
      ),
    );
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh', 'CN'));
  });

  tearDown(() async {
    PaintingBinding.instance.imageCache.clear();
    PaintingBinding.instance.imageCache.clearLiveImages();
    OmnibotResourceService.debugResetWorkspacePaths();
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh', 'CN'));
    await workspace.delete(recursive: true);
  });

  Widget wrap(String markdown) => MaterialApp(
    home: Scaffold(
      body: SingleChildScrollView(
        child: OmnibotMarkdownBody(
          data: markdown,
          selectable: true,
          baseStyle: const TextStyle(fontSize: 16),
        ),
      ),
    ),
  );

  Future<void> loadImage(WidgetTester tester) async {
    // Animated codecs schedule frames through the test clock. Allow file I/O
    // to complete, then pump that clock instead of waiting for animation idle.
    for (var attempt = 0; attempt < 50; attempt++) {
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 10)),
      );
      await tester.pump();
      final images = tester.widgetList<RawImage>(find.byType(RawImage));
      if (images.any((image) => image.image != null) ||
          find.text('表情包暂时无法显示').evaluate().isNotEmpty ||
          find.text('Sticker unavailable').evaluate().isNotEmpty) {
        return;
      }
    }
    fail('Image did not finish decoding');
  }

  testWidgets('sticker stays compact, preserves alt text, and opens original', (
    tester,
  ) async {
    final semantics = tester.ensureSemantics();
    await tester.pumpWidget(
      wrap(
        '太棒了！\n\n![开心鼓掌](omnibot://attachments/stickers/happy.png "sticker")',
      ),
    );
    await loadImage(tester);

    expect(find.byType(OmnibotChatSticker), findsOneWidget);
    expect(find.byType(OmnibotInlineResourceEmbed), findsNothing);
    expect(
      tester.getSize(find.byType(OmnibotChatSticker)),
      const Size(168, 168),
    );
    final image = tester.widget<Image>(find.byType(Image));
    expect(image.fit, BoxFit.contain);
    expect(image.image, isA<FileImage>());
    expect(find.bySemanticsLabel('开心鼓掌'), findsOneWidget);
    expect(tester.takeException(), isNull);
    semantics.dispose();

    await tester.tap(find.byType(OmnibotChatSticker));
    await tester.pumpAndSettle();
    expect(find.byType(OmnibotInteractiveImageView), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('GIF sticker retains both animated frames', (tester) async {
    await tester.pumpWidget(
      wrap('![开心](omnibot://attachments/stickers/happy.gif "sticker")'),
    );
    await loadImage(tester);
    final colors = <int>{};
    for (var frame = 0; frame < 5; frame++) {
      final image = tester.widget<RawImage>(find.byType(RawImage)).image;
      expect(image, isNotNull);
      final bytes = await tester.runAsync(
        () => image!.toByteData(format: ui.ImageByteFormat.rawRgba),
      );
      colors.add(bytes!.getUint32(0));
      await tester.pump(const Duration(milliseconds: 100));
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 10)),
      );
    }
    expect(colors, containsAll(<int>[0x3c9a65ff, 0xf6ce50ff]));
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });

  for (final uri in <String>[
    'omnibot://attachments/stickers/missing.png',
    'omnibot://unknown/happy.png',
    'https://example.invalid/sticker.png',
  ]) {
    testWidgets('unavailable sticker does not fall back to network: $uri', (
      tester,
    ) async {
      await tester.pumpWidget(wrap('![开心]($uri "sticker")'));
      await loadImage(tester);
      expect(find.text('表情包暂时无法显示'), findsOneWidget);
      expect(find.byType(Image), findsNothing);
      expect(find.byType(OmnibotResourceLinkCard), findsNothing);
      await tester.tapAt(tester.getCenter(find.byType(OmnibotChatSticker)));
      await tester.pump();
      expect(find.byType(OmnibotInteractiveImageView), findsNothing);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('unreadable sticker shows localized fallback', (tester) async {
    LegacyTextLocalizer.setResolvedLocale(const Locale('en'));
    await tester.pumpWidget(
      wrap('![Happy](omnibot://attachments/stickers/broken.png "sticker")'),
    );
    await loadImage(tester);
    expect(find.text('Sticker unavailable'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('ordinary resource images keep their existing presentation', (
    tester,
  ) async {
    await tester.pumpWidget(
      wrap('![图片](omnibot://attachments/stickers/happy.png)'),
    );
    await loadImage(tester);
    expect(find.byType(OmnibotChatSticker), findsNothing);
    expect(find.byType(OmnibotInlineResourceEmbed), findsOneWidget);
    expect(tester.widget<Image>(find.byType(Image)).fit, BoxFit.cover);
    expect(tester.takeException(), isNull);
  });
}
