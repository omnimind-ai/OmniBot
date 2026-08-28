import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/omnibot_workspace/omnibot_artifact_preview_page.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/widgets/image_preview_overlay.dart';

class _SvgTestAssetBundle extends CachingAssetBundle {
  static final Uint8List _svgBytes = Uint8List.fromList(
    utf8.encode(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
      '<rect width="24" height="24" fill="#000000"/>'
      '</svg>',
    ),
  );

  @override
  Future<ByteData> load(String key) async {
    return ByteData.view(_svgBytes.buffer);
  }

  @override
  Future<String> loadString(String key, {bool cache = true}) async {
    return utf8.decode(_svgBytes);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('HTML artifacts use a full page while other files keep the sheet', () {
    expect(shouldOpenOmnibotArtifactInFullPage('html'), isTrue);
    expect(shouldOpenOmnibotArtifactInFullPage('pdf'), isFalse);
    expect(shouldOpenOmnibotArtifactInFullPage('code'), isFalse);
  });

  late Directory tempDir;
  late File file;
  late File imageFile;

  setUp(() async {
    tempDir = await Directory.systemTemp.createTemp(
      'omnibot_artifact_preview_test_',
    );
    file = File('${tempDir.path}/note.txt');
    await file.writeAsString('hello workspace');
    imageFile = File('${tempDir.path}/preview.png');
    await imageFile.writeAsBytes(
      base64Decode(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO2CS1cAAAAASUVORK5CYII=',
      ),
    );
  });

  tearDown(() async {
    if (await tempDir.exists()) {
      await tempDir.delete(recursive: true);
    }
  });

  testWidgets('collapses file actions into a single more-actions button', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: OmnibotArtifactPreviewPage(
            path: file.path,
            title: 'note.txt',
            previewKind: 'text',
            mimeType: 'text/plain',
          ),
        ),
      ),
    );

    await tester.pump();
    await tester.runAsync(() async {
      await Future<void>.delayed(const Duration(milliseconds: 50));
    });
    await tester.pump();

    expect(
      find.byKey(const ValueKey('artifact-preview-more-actions')),
      findsOneWidget,
    );
    expect(find.byIcon(Icons.more_horiz_rounded), findsOneWidget);
    expect(find.byIcon(Icons.share_outlined), findsNothing);

    await tester.tap(
      find.byKey(const ValueKey('artifact-preview-more-actions')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 250));

    expect(
      find.byWidgetPredicate(
        (widget) =>
            widget is Text &&
            (widget.data == '系统打开' || widget.data == 'Open with system'),
      ),
      findsOneWidget,
    );
    expect(
      find.byWidgetPredicate(
        (widget) =>
            widget is Text &&
            (widget.data == '分享文件' || widget.data == 'Share file'),
      ),
      findsOneWidget,
    );
  });

  testWidgets('artifact preview keeps path and editor surfaces dark', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        darkTheme: AppTheme.darkTheme,
        themeMode: ThemeMode.dark,
        home: OmnibotArtifactPreviewPage(
          path: file.path,
          title: 'note.txt',
          previewKind: 'text',
          mimeType: 'text/plain',
        ),
      ),
    );
    await tester.pump();
    await tester.runAsync(() async {
      await Future<void>.delayed(const Duration(milliseconds: 50));
    });
    await tester.pump();

    final pathBar = tester.widget<Container>(
      find.byKey(const ValueKey('artifact-preview-path-bar')),
    );
    expect(pathBar.color, OmniThemePalette.dark.surfaceSecondary);

    await tester.tap(find.byTooltip('编辑文件'));
    await tester.pump();
    final editorFinder = find.byKey(
      const ValueKey('artifact-preview-editor-field'),
    );
    for (
      var index = 0;
      index < 10 && editorFinder.evaluate().isEmpty;
      index++
    ) {
      await tester.runAsync(() async {
        await Future<void>.delayed(const Duration(milliseconds: 50));
      });
      await tester.pump();
    }

    expect(editorFinder, findsOneWidget);
    final editor = tester.widget<TextField>(editorFinder);
    expect(editor.decoration?.fillColor, OmniThemePalette.dark.surfacePrimary);
    expect(editor.style?.color, OmniThemePalette.dark.textPrimary);
  });

  testWidgets('image artifact preview uses shared interactive image surface', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: OmnibotArtifactPreviewPage(
          path: imageFile.path,
          title: 'preview.png',
          previewKind: 'image',
          mimeType: 'image/png',
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byType(OmnibotInteractiveImageView), findsOneWidget);
    expect(
      find.byKey(const ValueKey('artifact-preview-image-bounds')),
      findsOneWidget,
    );
  });
}
