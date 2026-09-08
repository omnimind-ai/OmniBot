import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/widgets/image_preview_overlay.dart';

const _fileChannel = MethodChannel('cn.com.omnimind.bot/file_save');
const _workspacePaths = OmnibotWorkspacePaths(
  rootPath: '/data/user/0/cn.com.omnimind.bot/workspace',
  shellRootPath: '/workspace',
  internalRootPath: '/data/user/0/cn.com.omnimind.bot/workspace/.omnibot',
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;
  late File imageFile;
  late List<MethodCall> fileChannelCalls;
  late Uint8List largePngBytes;
  late Uint8List smallPngBytes;
  late Uint8List widePngBytes;

  setUp(() async {
    largePngBytes = await _createPngBytes(width: 800, height: 600);
    smallPngBytes = await _createPngBytes(width: 200, height: 100);
    widePngBytes = await _createPngBytes(width: 800, height: 200);
    tempDir = await Directory.systemTemp.createTemp(
      'omnibot_image_preview_overlay_test_',
    );
    imageFile = File('${tempDir.path}/preview.png');
    await imageFile.writeAsBytes(largePngBytes);
    fileChannelCalls = <MethodCall>[];

    OmnibotResourceService.debugSetWorkspacePaths(_workspacePaths);
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(spePermission, (call) async {
      switch (call.method) {
        case 'isWorkspaceStorageAccessGranted':
          return true;
        default:
          return null;
      }
    });
    messenger.setMockMethodCallHandler(_fileChannel, (call) async {
      fileChannelCalls.add(call);
      if (call.method == 'shareFile') {
        return true;
      }
      return null;
    });
  });

  tearDown(() async {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(spePermission, null);
    messenger.setMockMethodCallHandler(_fileChannel, null);
    OmnibotResourceService.debugResetWorkspacePaths();
    if (await tempDir.exists()) {
      await tempDir.delete(recursive: true);
    }
  });

  testWidgets(
    'shrinks images only when their preview height fills the viewport',
    (tester) async {
      const boundsKey = ValueKey('image-preview-bounds');

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SizedBox(
              width: 400,
              height: 300,
              child: OmnibotInteractiveImageView(
                source: MemoryImageSource(largePngBytes),
                previewBoundsKey: boundsKey,
              ),
            ),
          ),
        ),
      );
      await _waitForImage(tester);

      final boundsSize = tester.getSize(find.byKey(boundsKey));
      expect(boundsSize.width, 320);
      expect(boundsSize.height, 240);
    },
  );

  testWidgets('keeps smaller images at their natural preview size', (
    tester,
  ) async {
    const boundsKey = ValueKey('image-preview-natural-bounds');

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SizedBox(
            width: 400,
            height: 300,
            child: OmnibotInteractiveImageView(
              source: MemoryImageSource(smallPngBytes),
              previewBoundsKey: boundsKey,
            ),
          ),
        ),
      ),
    );
    await _waitForImage(tester);

    final boundsSize = tester.getSize(find.byKey(boundsKey));
    expect(boundsSize.width, 200);
    expect(boundsSize.height, 100);
  });

  testWidgets('keeps wide images that only fill width at full preview size', (
    tester,
  ) async {
    const boundsKey = ValueKey('image-preview-wide-bounds');

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SizedBox(
            width: 400,
            height: 300,
            child: OmnibotInteractiveImageView(
              source: MemoryImageSource(widePngBytes),
              previewBoundsKey: boundsKey,
            ),
          ),
        ),
      ),
    );
    await _waitForImage(tester);

    final boundsSize = tester.getSize(find.byKey(boundsKey));
    expect(boundsSize.width, 400);
    expect(boundsSize.height, 100);
  });

  testWidgets('long press on file-backed image triggers system share', (
    tester,
  ) async {
    const boundsKey = ValueKey('image-preview-share-bounds');
    await tester.pumpWidget(const MaterialApp(home: SizedBox.shrink()));
    final context = tester.element(find.byType(SizedBox).first);
    await tester.runAsync(() => precacheImage(FileImage(imageFile), context));

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SizedBox(
            width: 360,
            height: 280,
            child: OmnibotInteractiveImageView(
              source: FileImageSource(imageFile.path),
              enableFileShareOnLongPress: true,
              previewBoundsKey: boundsKey,
            ),
          ),
        ),
      ),
    );
    await _waitForImage(tester);

    await tester.longPress(find.byKey(boundsKey));
    await tester.runAsync(() async {
      for (var i = 0; i < 100 && fileChannelCalls.isEmpty; i++) {
        await Future<void>.delayed(const Duration(milliseconds: 10));
      }
    });
    await tester.pumpAndSettle();

    expect(fileChannelCalls, hasLength(1));
    expect(fileChannelCalls.single.method, 'shareFile');
    expect(
      fileChannelCalls.single.arguments,
      containsPair('sourcePath', imageFile.path),
    );
    expect(
      fileChannelCalls.single.arguments,
      containsPair('fileName', 'preview.png'),
    );
    expect(
      fileChannelCalls.single.arguments,
      containsPair('mimeType', 'image/png'),
    );
  });
}

Future<Uint8List> _createPngBytes({
  required int width,
  required int height,
}) async {
  final recorder = ui.PictureRecorder();
  final canvas = Canvas(recorder);
  canvas.drawRect(
    Rect.fromLTWH(0, 0, width.toDouble(), height.toDouble()),
    Paint()..color = const Color(0xFF1F4ED8),
  );
  final picture = recorder.endRecording();
  final image = await picture.toImage(width, height);
  final byteData = await image.toByteData(format: ui.ImageByteFormat.png);
  image.dispose();
  return byteData!.buffer.asUint8List();
}

Future<void> _waitForImage(WidgetTester tester) async {
  final context = tester.element(find.byType(OmnibotInteractiveImageView));
  final source = tester
      .widget<OmnibotInteractiveImageView>(
        find.byType(OmnibotInteractiveImageView),
      )
      .source;
  final ImageProvider provider = switch (source) {
    MemoryImageSource(:final bytes) => MemoryImage(bytes),
    FileImageSource(:final path) => FileImage(File(path)),
    NetworkImageSource(:final url) => NetworkImage(url),
  };
  await tester.runAsync(() => precacheImage(provider, context));
  await tester.pumpAndSettle();
}
