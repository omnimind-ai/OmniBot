import 'dart:async';
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
  late List<MethodCall> permissionChannelCalls;
  late Uint8List largePngBytes;
  late Uint8List smallPngBytes;
  late Uint8List widePngBytes;

  setUpAll(() async {
    tempDir = await Directory.systemTemp.createTemp(
      'omnibot_preview_overlay_test_',
    );
    imageFile = File('${tempDir.path}/preview.png');
    final fileBytes = await _createPngBytes(width: 800, height: 600);
    await imageFile.writeAsBytes(fileBytes);
  });

  setUp(() async {
    largePngBytes = await _createPngBytes(width: 800, height: 600);
    smallPngBytes = await _createPngBytes(width: 200, height: 100);
    widePngBytes = await _createPngBytes(width: 800, height: 200);
    await imageFile.writeAsBytes(largePngBytes, flush: true);
    fileChannelCalls = <MethodCall>[];
    permissionChannelCalls = <MethodCall>[];

    OmnibotResourceService.debugSetWorkspacePaths(_workspacePaths);
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(spePermission, (call) async {
      permissionChannelCalls.add(call);
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
  });

  tearDownAll(() async {
    PaintingBinding.instance.imageCache
      ..clear()
      ..clearLiveImages();
    await _deleteDirectoryWithRetry(tempDir);
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
      await _waitForWidget(
        tester,
        find.descendant(
          of: find.byKey(boundsKey),
          matching: find.byType(Image),
        ),
      );

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
    await _waitForWidget(
      tester,
      find.descendant(of: find.byKey(boundsKey), matching: find.byType(Image)),
    );

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
    await _waitForWidget(
      tester,
      find.descendant(of: find.byKey(boundsKey), matching: find.byType(Image)),
    );

    final boundsSize = tester.getSize(find.byKey(boundsKey));
    expect(boundsSize.width, 400);
    expect(boundsSize.height, 100);
  });

  testWidgets('long press on file-backed image triggers system share', (
    tester,
  ) async {
    const boundsKey = ValueKey('image-preview-share-bounds');

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
    await tester.pumpAndSettle();
    await _waitForWidget(
      tester,
      find.descendant(of: find.byKey(boundsKey), matching: find.byType(Image)),
    );

    try {
      await tester.runAsync(
        () => imageFile.writeAsBytes(smallPngBytes, flush: true),
      );
      await _longPressPreview(tester, find.byKey(boundsKey));

      expect(fileChannelCalls, hasLength(1));
      expect(fileChannelCalls.single.method, 'shareFile');
      final shareArguments = Map<dynamic, dynamic>.from(
        fileChannelCalls.single.arguments as Map<dynamic, dynamic>,
      );
      final sharedSnapshotPath = shareArguments['sourcePath'] as String;
      expect(sharedSnapshotPath, isNot(imageFile.path));
      expect(
        File(sharedSnapshotPath).parent.path.replaceAll('\\', '/'),
        contains('/omnibot_image_preview_'),
      );
      expect(shareArguments, containsPair('fileName', 'preview.png'));
      expect(shareArguments, containsPair('mimeType', 'image/png'));
      final sharedBytes = await tester.runAsync(
        () => File(sharedSnapshotPath).readAsBytes(),
      );
      expect(sharedBytes, orderedEquals(largePngBytes));
      expect(tester.getSize(find.byKey(boundsKey)), const Size(360, 270));
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pumpAndSettle();
      PaintingBinding.instance.imageCache
        ..clear()
        ..clearLiveImages();
      await tester.pump();
    }
  });

  testWidgets('dependency changes reuse the same immutable file snapshot', (
    tester,
  ) async {
    const boundsKey = ValueKey('image-preview-repeated-dependencies');

    Future<void> pumpWithTextScale(double scale) {
      return tester.pumpWidget(
        MaterialApp(
          home: MediaQuery(
            data: MediaQueryData(textScaler: TextScaler.linear(scale)),
            child: SizedBox(
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
    }

    await pumpWithTextScale(1);
    await _waitForWidget(
      tester,
      find.descendant(of: find.byKey(boundsKey), matching: find.byType(Image)),
    );
    await _longPressPreview(tester, find.byKey(boundsKey));
    final firstPath = _sharedSourcePath(fileChannelCalls.single);

    await pumpWithTextScale(1.1);
    await pumpWithTextScale(1.2);
    await _longPressPreview(tester, find.byKey(boundsKey));

    expect(fileChannelCalls, hasLength(2));
    expect(_sharedSourcePath(fileChannelCalls.last), firstPath);
  });

  testWidgets('changing sources removes the superseded snapshot', (
    tester,
  ) async {
    const boundsKey = ValueKey('image-preview-source-replacement');
    final replacement = File('${tempDir.path}/replacement.png');
    await tester.runAsync(
      () => replacement.writeAsBytes(widePngBytes, flush: true),
    );

    Future<void> pumpSource(String path) {
      return tester.pumpWidget(
        MaterialApp(
          home: SizedBox(
            width: 360,
            height: 280,
            child: OmnibotInteractiveImageView(
              source: FileImageSource(path),
              enableFileShareOnLongPress: true,
              previewBoundsKey: boundsKey,
            ),
          ),
        ),
      );
    }

    await pumpSource(imageFile.path);
    final initialImageFinder = find.descendant(
      of: find.byKey(boundsKey),
      matching: find.byType(Image),
    );
    await _waitForWidget(tester, initialImageFinder);
    final initialImage = tester.widget<Image>(initialImageFinder);
    final oldSnapshot =
        ((initialImage.image as ResizeImage).imageProvider as FileImage).file;

    await pumpSource(replacement.path);
    await _waitForWidget(
      tester,
      find.descendant(of: find.byKey(boundsKey), matching: find.byType(Image)),
    );
    await _waitForFileSystemCondition(
      tester,
      () async => !await oldSnapshot.parent.exists(),
    );
  });

  testWidgets('invalid file data fails closed and cannot be shared', (
    tester,
  ) async {
    const boundsKey = ValueKey('image-preview-invalid-file');
    final invalidFile = File('${tempDir.path}/invalid.png');
    await tester.runAsync(
      () => invalidFile.writeAsBytes(const <int>[1, 2, 3, 4], flush: true),
    );

    await tester.pumpWidget(
      MaterialApp(
        home: SizedBox(
          width: 360,
          height: 280,
          child: OmnibotInteractiveImageView(
            source: FileImageSource(invalidFile.path),
            enableFileShareOnLongPress: true,
            previewBoundsKey: boundsKey,
          ),
        ),
      ),
    );
    await _waitForWidget(tester, find.byIcon(Icons.broken_image_outlined));
    await _longPressPreview(tester, find.byKey(boundsKey));

    expect(fileChannelCalls, isEmpty);
  });

  testWidgets('resource service shares approved files through native channel', (
    tester,
  ) async {
    final shared = await tester.runAsync(
      () => OmnibotResourceService.shareFile(
        sourcePath: imageFile.path,
        fileName: 'preview.png',
        mimeType: 'image/png',
      ),
    );

    expect(shared, isTrue);
    expect(
      permissionChannelCalls.map((call) => call.method),
      contains('isWorkspaceStorageAccessGranted'),
    );
    expect(fileChannelCalls, hasLength(1));
    expect(fileChannelCalls.single.method, 'shareFile');
  });

  testWidgets('network images are downloaded into a bounded file snapshot', (
    tester,
  ) async {
    const boundsKey = ValueKey('image-preview-network-snapshot');
    final previousHttpOverrides = HttpOverrides.current;
    HttpOverrides.global = null;
    addTearDown(() => HttpOverrides.global = previousHttpOverrides);
    final server = (await tester.runAsync(
      () => HttpServer.bind(InternetAddress.loopbackIPv4, 0),
    ))!;
    addTearDown(() async {
      await server.close(force: true);
    });
    server.listen((request) async {
      request.response
        ..statusCode = HttpStatus.ok
        ..headers.contentType = ContentType('image', 'png')
        ..add(largePngBytes);
      await request.response.close();
    });

    await tester.pumpWidget(
      MaterialApp(
        home: SizedBox(
          width: 400,
          height: 300,
          child: OmnibotInteractiveImageView(
            source: NetworkImageSource(
              'http://${server.address.address}:${server.port}/preview.png',
            ),
            previewBoundsKey: boundsKey,
          ),
        ),
      ),
    );
    final imageFinder = find.descendant(
      of: find.byKey(boundsKey),
      matching: find.byType(Image),
    );
    await _waitForWidget(tester, imageFinder);

    final image = tester.widget<Image>(imageFinder);
    expect(image.image, isA<ResizeImage>());
    expect((image.image as ResizeImage).imageProvider, isA<FileImage>());
  });

  testWidgets('failed network decode removes its temporary snapshot', (
    tester,
  ) async {
    final previousHttpOverrides = HttpOverrides.current;
    HttpOverrides.global = null;
    addTearDown(() => HttpOverrides.global = previousHttpOverrides);
    final before = (await tester.runAsync(_managedPreviewDirectoryPaths))!;
    final server = (await tester.runAsync(
      () => HttpServer.bind(InternetAddress.loopbackIPv4, 0),
    ))!;
    addTearDown(() async {
      await server.close(force: true);
    });
    server.listen((request) async {
      request.response
        ..statusCode = HttpStatus.ok
        ..add(const <int>[1, 2, 3, 4]);
      await request.response.close();
    });

    await tester.pumpWidget(
      MaterialApp(
        home: OmnibotInteractiveImageView(
          source: NetworkImageSource(
            'http://${server.address.address}:${server.port}/invalid.png',
          ),
        ),
      ),
    );
    await _waitForWidget(tester, find.byIcon(Icons.broken_image_outlined));
    await _waitForFileSystemCondition(tester, () async {
      final after = await _managedPreviewDirectoryPaths();
      return after.difference(before).isEmpty;
    });
  });

  testWidgets('chunked network data is rejected at the streaming hard limit', (
    tester,
  ) async {
    final previousHttpOverrides = HttpOverrides.current;
    HttpOverrides.global = null;
    addTearDown(() => HttpOverrides.global = previousHttpOverrides);
    final before = (await tester.runAsync(_managedPreviewDirectoryPaths))!;
    final server = (await tester.runAsync(
      () => HttpServer.bind(InternetAddress.loopbackIPv4, 0),
    ))!;
    addTearDown(() async {
      await server.close(force: true);
    });
    server.listen((request) async {
      request.response
        ..statusCode = HttpStatus.ok
        ..headers.chunkedTransferEncoding = true;
      try {
        request.response.add(Uint8List(25 * 1024 * 1024 + 1));
        await request.response.close();
      } on HttpException {
        // The client closes the connection as soon as byte 25 MiB + 1 arrives.
      } on SocketException {
        // The client closes the connection as soon as byte 25 MiB + 1 arrives.
      } on StateError {
        // The response can already be closed after client cancellation.
      }
    });

    await tester.pumpWidget(
      MaterialApp(
        home: OmnibotInteractiveImageView(
          source: NetworkImageSource(
            'http://${server.address.address}:${server.port}/oversized.png',
          ),
        ),
      ),
    );
    await _waitForWidget(tester, find.byIcon(Icons.broken_image_outlined));
    await _waitForFileSystemCondition(tester, () async {
      final after = await _managedPreviewDirectoryPaths();
      return after.difference(before).isEmpty;
    });
  });

  testWidgets('a cancelled network generation cannot replace a newer source', (
    tester,
  ) async {
    const boundsKey = ValueKey('image-preview-generation-race');
    final previousHttpOverrides = HttpOverrides.current;
    HttpOverrides.global = null;
    addTearDown(() => HttpOverrides.global = previousHttpOverrides);
    final requestStarted = Completer<void>();
    final releaseResponse = Completer<void>();
    final server = (await tester.runAsync(
      () => HttpServer.bind(InternetAddress.loopbackIPv4, 0),
    ))!;
    addTearDown(() async {
      if (!releaseResponse.isCompleted) {
        releaseResponse.complete();
      }
      await server.close(force: true);
    });
    server.listen((request) async {
      if (!requestStarted.isCompleted) {
        requestStarted.complete();
      }
      request.response
        ..statusCode = HttpStatus.ok
        ..headers.chunkedTransferEncoding = true;
      await releaseResponse.future;
      try {
        request.response.add(largePngBytes);
        await request.response.close();
      } on HttpException {
        // The client is expected to cancel this superseded request.
      } on SocketException {
        // The client is expected to cancel this superseded request.
      } on StateError {
        // The response can already be closed after client cancellation.
      }
    });

    await tester.pumpWidget(
      MaterialApp(
        home: SizedBox(
          width: 400,
          height: 300,
          child: OmnibotInteractiveImageView(
            source: NetworkImageSource(
              'http://${server.address.address}:${server.port}/slow.png',
            ),
            previewBoundsKey: boundsKey,
          ),
        ),
      ),
    );
    await _waitForCondition(tester, () => requestStarted.isCompleted);

    await tester.pumpWidget(
      MaterialApp(
        home: SizedBox(
          width: 400,
          height: 300,
          child: OmnibotInteractiveImageView(
            source: MemoryImageSource(smallPngBytes),
            previewBoundsKey: boundsKey,
          ),
        ),
      ),
    );
    await _waitForWidget(
      tester,
      find.descendant(of: find.byKey(boundsKey), matching: find.byType(Image)),
    );
    releaseResponse.complete();
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 100)),
    );
    await tester.pump();

    expect(tester.getSize(find.byKey(boundsKey)), const Size(200, 100));
  });

  testWidgets('rejects oversized memory previews before native decoding', (
    tester,
  ) async {
    final oversizedBytes = Uint8List(25 * 1024 * 1024 + 1);

    await tester.pumpWidget(
      MaterialApp(
        home: OmnibotInteractiveImageView(
          source: MemoryImageSource(oversizedBytes),
        ),
      ),
    );
    await _waitForWidget(tester, find.byIcon(Icons.broken_image_outlined));

    expect(find.byType(Image), findsNothing);
  });

  testWidgets('removes expired managed preview directories', (tester) async {
    final staleDirectory = Directory(
      '${Directory.systemTemp.path}${Platform.pathSeparator}'
      'omnibot_image_preview_stale_${DateTime.now().microsecondsSinceEpoch}',
    );
    final unrelatedDirectory = Directory(
      '${Directory.systemTemp.path}${Platform.pathSeparator}'
      'not_omnibot_image_preview_${DateTime.now().microsecondsSinceEpoch}',
    );
    await tester.runAsync(() async {
      await staleDirectory.create();
      await unrelatedDirectory.create();
      await File(
        '${staleDirectory.path}${Platform.pathSeparator}image.bin',
      ).writeAsBytes(const <int>[1]);
      await OmnibotInteractiveImageView.debugCleanupExpiredPreviewDirectories(
        DateTime.now().add(const Duration(days: 1)),
      );
    });
    addTearDown(() async {
      if (await unrelatedDirectory.exists()) {
        await unrelatedDirectory.delete();
      }
    });

    await _waitForFileSystemCondition(
      tester,
      () async => !await staleDirectory.exists(),
    );
    expect(await tester.runAsync(unrelatedDirectory.exists), isTrue);
  });

  testWidgets('rejects oversized file previews before image decoding', (
    tester,
  ) async {
    final oversizedFile = File('${tempDir.path}/oversized.png');
    await tester.runAsync(() async {
      final handle = await oversizedFile.open(mode: FileMode.write);
      try {
        await handle.truncate(25 * 1024 * 1024 + 1);
      } finally {
        await handle.close();
      }
    });

    await tester.pumpWidget(
      MaterialApp(
        home: OmnibotInteractiveImageView(
          source: FileImageSource(oversizedFile.path),
          enableFileShareOnLongPress: true,
          previewBoundsKey: const ValueKey('oversized-file-preview'),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await _waitForWidget(tester, find.byIcon(Icons.broken_image_outlined));
    await _longPressPreview(
      tester,
      find.byKey(const ValueKey('oversized-file-preview')),
    );
    expect(fileChannelCalls, isEmpty);
  });
}

Future<void> _longPressPreview(WidgetTester tester, Finder finder) async {
  final gesture = await tester.startGesture(tester.getCenter(finder));
  await tester.pump(const Duration(milliseconds: 600));
  await gesture.up();
  await tester.pumpAndSettle();
}

String _sharedSourcePath(MethodCall call) {
  final arguments = Map<dynamic, dynamic>.from(
    call.arguments as Map<dynamic, dynamic>,
  );
  return arguments['sourcePath'] as String;
}

Future<void> _waitForFileSystemCondition(
  WidgetTester tester,
  Future<bool> Function() condition,
) async {
  for (var attempt = 0; attempt < 100; attempt += 1) {
    final complete = await tester.runAsync(condition);
    if (complete == true) {
      return;
    }
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 10)),
    );
    await tester.pump(const Duration(milliseconds: 10));
  }
  fail('Timed out waiting for the expected file-system state.');
}

Future<Set<String>> _managedPreviewDirectoryPaths() async {
  final result = <String>{};
  await for (final entity in Directory.systemTemp.list(followLinks: false)) {
    final normalized = entity.path.replaceAll('\\', '/');
    final name = normalized.split('/').last;
    if (entity is Directory && name.startsWith('omnibot_image_preview_')) {
      result.add(normalized);
    }
  }
  return result;
}

Future<void> _waitForWidget(WidgetTester tester, Finder finder) async {
  for (var attempt = 0; attempt < 800; attempt += 1) {
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 10)),
    );
    await tester.pump();
    if (finder.evaluate().isNotEmpty) {
      return;
    }
  }
  fail('Timed out waiting for the expected image preview state.');
}

Future<void> _waitForCondition(
  WidgetTester tester,
  bool Function() condition,
) async {
  for (var attempt = 0; attempt < 300; attempt += 1) {
    if (condition()) {
      return;
    }
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 10)),
    );
    await tester.pump(const Duration(milliseconds: 10));
  }
  fail('Timed out waiting for the expected asynchronous condition.');
}

Future<void> _deleteDirectoryWithRetry(Directory directory) async {
  for (var attempt = 0; attempt < 20; attempt += 1) {
    try {
      if (await directory.exists()) {
        await directory.delete(recursive: true);
      }
      return;
    } on PathAccessException {
      if (attempt == 19) rethrow;
      await Future<void>.delayed(const Duration(milliseconds: 50));
    }
  }
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
