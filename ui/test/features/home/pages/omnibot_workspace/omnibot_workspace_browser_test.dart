import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/omnibot_workspace/widgets/omnibot_workspace_browser.dart';
import 'package:ui/services/workspace_mount_service.dart';

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

  late Directory workspaceDir;
  late File noteFile;
  late List<String> linksToDelete;
  late List<Directory> externalDirectories;

  setUp(() async {
    workspaceDir = await Directory.systemTemp.createTemp(
      'omnibot_workspace_browser_test_',
    );
    linksToDelete = <String>[];
    externalDirectories = <Directory>[];
    final docsDir = Directory('${workspaceDir.path}/docs');
    await docsDir.create(recursive: true);
    noteFile = File('${docsDir.path}/note.md');
    await noteFile.writeAsString('# hello pane preview');
    await File('${workspaceDir.path}/root.txt').writeAsString('root file');
  });

  tearDown(() async {
    for (final path in linksToDelete.reversed) {
      if (FileSystemEntity.typeSync(path, followLinks: false) ==
          FileSystemEntityType.link) {
        await Link(path).delete();
      }
    }
    if (await workspaceDir.exists()) {
      await workspaceDir.delete(recursive: true);
    }
    for (final directory in externalDirectories) {
      if (await directory.exists()) {
        await directory.delete(recursive: true);
      }
    }
  });

  Widget buildBrowser({
    GlobalKey<OmnibotWorkspaceBrowserState>? browserKey,
    WorkspaceTextFileReader? textFileReader,
    bool enableInlineDirectoryExpansion = false,
  }) {
    return MaterialApp(
      home: DefaultAssetBundle(
        bundle: _SvgTestAssetBundle(),
        child: SizedBox(
          width: 360,
          height: 720,
          child: OmnibotWorkspaceBrowser(
            key: browserKey,
            workspacePath: workspaceDir.path,
            workspaceShellPath: '/workspace',
            enableSystemBackHandler: false,
            showBreadcrumbHeader: true,
            showHeaderTitle: false,
            enableInlineDirectoryExpansion: enableInlineDirectoryExpansion,
            inlineFilePreview: true,
            textFileReader: textFileReader,
          ),
        ),
      ),
    );
  }

  Future<void> pumpFileIo(WidgetTester tester) async {
    await tester.runAsync(() async {
      await Future<void>.delayed(const Duration(milliseconds: 50));
    });
    await tester.pump();
  }

  bool createLinkWhenSupported(String linkPath, String targetPath) {
    try {
      Link(linkPath).createSync(targetPath);
      return true;
    } on FileSystemException catch (error) {
      if (Platform.isWindows && error.osError?.errorCode == 1314) {
        return false;
      }
      rethrow;
    }
  }

  test('canonical boundary rejects an unapproved resolved target', () {
    expect(
      isWorkspaceCanonicalPathWithinBoundary(
        resolvedPath: r'C:\outside\secret.txt',
        resolvedBoundaryPath: r'C:\workspace',
        caseInsensitive: true,
      ),
      isFalse,
    );
  });

  test('canonical mount boundary allows only its own resolved descendants', () {
    expect(
      isWorkspaceCanonicalPathWithinBoundary(
        resolvedPath: r'C:\mounted-source\folder\note.txt',
        resolvedBoundaryPath: r'C:\mounted-source',
        caseInsensitive: true,
      ),
      isTrue,
    );
    expect(
      isWorkspaceCanonicalPathWithinBoundary(
        resolvedPath: r'C:\beyond-mount\secret.txt',
        resolvedBoundaryPath: r'C:\mounted-source',
        caseInsensitive: true,
      ),
      isFalse,
    );
  });

  testWidgets('supports breadcrumb navigation and inline file preview', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: SizedBox(
            width: 360,
            height: 720,
            child: OmnibotWorkspaceBrowser(
              workspacePath: workspaceDir.path,
              workspaceShellPath: '/workspace',
              enableSystemBackHandler: false,
              showBreadcrumbHeader: true,
              showHeaderTitle: false,
              enableInlineDirectoryExpansion: false,
              inlineFilePreview: true,
              textFileReader: (path) => File(path).readAsStringSync(),
            ),
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    expect(find.text('工作区'), findsNothing);
    expect(find.text('/workspace'), findsOneWidget);
    expect(find.text('docs'), findsOneWidget);

    await tester.tap(find.text('docs'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    expect(find.text('/workspace'), findsOneWidget);
    expect(find.text('note.md'), findsOneWidget);

    await tester.tap(find.text('note.md'));
    await tester.pump();
    await tester.runAsync(() async {
      await Future<void>.delayed(const Duration(milliseconds: 50));
    });
    await tester.pump();

    expect(
      find.byKey(const ValueKey('workspace-inline-preview-edit')),
      findsOneWidget,
    );

    await tester.tap(find.text('/workspace'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    expect(find.text('root.txt'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('workspace-inline-preview-edit')),
      findsNothing,
    );

    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
  });

  testWidgets('supports editing and saving inline preview files', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: SizedBox(
            width: 360,
            height: 720,
            child: OmnibotWorkspaceBrowser(
              workspacePath: workspaceDir.path,
              workspaceShellPath: '/workspace',
              enableSystemBackHandler: false,
              showBreadcrumbHeader: true,
              showHeaderTitle: false,
              enableInlineDirectoryExpansion: false,
              inlineFilePreview: true,
              textFileReader: (path) => File(path).readAsStringSync(),
            ),
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    await tester.tap(find.text('docs'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    await tester.tap(find.text('note.md'));
    await tester.pump();
    await tester.runAsync(() async {
      await Future<void>.delayed(const Duration(milliseconds: 50));
    });
    await tester.pump();

    expect(
      find.byKey(const ValueKey('workspace-inline-preview-edit')),
      findsOneWidget,
    );

    final dynamic editButton = tester.widget(
      find.byKey(const ValueKey('workspace-inline-preview-edit')),
    );
    await editButton.onPressed();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    expect(find.byType(TextField), findsOneWidget);

    await tester.enterText(find.byType(TextField), '# updated content');
    await tester.pump();

    final dynamic saveButton = tester.widget(
      find.byKey(const ValueKey('workspace-inline-preview-save')),
    );
    await tester.runAsync(() async {
      await saveButton.onPressed();
    });
    await tester.pump();
    await tester.runAsync(() async {
      await Future<void>.delayed(const Duration(milliseconds: 50));
      expect(await noteFile.readAsString(), '# updated content');
    });
    await tester.pump();

    expect(find.byType(TextField), findsNothing);
    expect(
      find.byKey(const ValueKey('workspace-inline-preview-edit')),
      findsOneWidget,
    );

    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
  });

  testWidgets('rejects an unapproved symlink that escapes workspace', (
    tester,
  ) async {
    final outside = Directory.systemTemp.createTempSync(
      'omnibot_workspace_browser_outside_',
    );
    externalDirectories.add(outside);
    File('${outside.path}/secret.txt').writeAsStringSync('outside secret');
    final aliases = Directory('${workspaceDir.path}/aliases');
    aliases.createSync();
    final escapePath = '${aliases.path}/escape';
    if (!createLinkWhenSupported(escapePath, outside.path)) {
      return;
    }
    linksToDelete.add(escapePath);

    await tester.pumpWidget(
      buildBrowser(textFileReader: (path) => File(path).readAsStringSync()),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    await tester.tap(find.text('aliases'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.text('escape'), findsOneWidget);

    await tester.tap(find.text('escape'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    expect(find.text('secret.txt'), findsNothing);
    expect(
      find.byKey(const ValueKey('workspace-inline-preview-edit')),
      findsNothing,
    );
  });

  testWidgets('keeps an authorized root mount inside its source boundary', (
    tester,
  ) async {
    final mountedSource = Directory.systemTemp.createTempSync(
      'omnibot_workspace_browser_mount_',
    );
    externalDirectories.add(mountedSource);
    File(
      '${mountedSource.path}/mounted.txt',
    ).writeAsStringSync('authorized mount content');
    final beyondMount = Directory.systemTemp.createTempSync(
      'omnibot_workspace_browser_beyond_mount_',
    );
    externalDirectories.add(beyondMount);
    File('${beyondMount.path}/beyond.txt').writeAsStringSync('must stay out');
    late final WorkspaceMountEntry mount;
    try {
      mount = WorkspaceMountService.mountDirectorySync(
        sourcePath: mountedSource.path,
        alias: 'authorized',
        rootPath: workspaceDir.path,
      );
    } on FileSystemException catch (error) {
      if (Platform.isWindows && error.osError?.errorCode == 1314) {
        return;
      }
      rethrow;
    }
    linksToDelete.add(mount.linkPath);
    final nestedEscapePath = '${mountedSource.path}/escape';
    if (!createLinkWhenSupported(nestedEscapePath, beyondMount.path)) {
      return;
    }
    linksToDelete.add(nestedEscapePath);

    await tester.pumpWidget(
      buildBrowser(textFileReader: (path) => File(path).readAsStringSync()),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    await tester.tap(find.text('authorized'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.text('mounted.txt'), findsOneWidget);
    expect(find.text('escape'), findsOneWidget);

    await tester.tap(find.text('escape'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.text('beyond.txt'), findsNothing);

    await tester.tap(find.text('mounted.txt'));
    await tester.pump();
    await pumpFileIo(tester);

    expect(find.text('authorized mount content'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('workspace-inline-preview-edit')),
      findsOneWidget,
    );
  });

  testWidgets('ignores stale text read after switching from file A to B', (
    tester,
  ) async {
    final fileA = File('${workspaceDir.path}/a.txt');
    final fileB = File('${workspaceDir.path}/b.txt');
    fileA.writeAsStringSync('A on disk');
    fileB.writeAsStringSync('B on disk');
    final readA = Completer<String>();
    final readB = Completer<String>();
    final browserKey = GlobalKey<OmnibotWorkspaceBrowserState>();

    await tester.pumpWidget(
      buildBrowser(
        browserKey: browserKey,
        textFileReader: (path) {
          final name = File(path).uri.pathSegments.last;
          return name == 'a.txt' ? readA.future : readB.future;
        },
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    await tester.tap(find.text('a.txt'));
    await tester.pump();
    await pumpFileIo(tester);

    browserKey.currentState!.debugOpenInlineFileForTesting(fileB.path);
    await tester.pump();
    await pumpFileIo(tester);

    readB.complete('B from reader');
    await tester.pump();
    expect(find.text('B from reader'), findsOneWidget);

    readA.complete('A stale result');
    await tester.pump();
    expect(find.text('A stale result'), findsNothing);
    expect(find.text('B from reader'), findsOneWidget);

    final dynamic editButton = tester.widget(
      find.byKey(const ValueKey('workspace-inline-preview-edit')),
    );
    await editButton.onPressed();
    await tester.pump();
    await tester.enterText(find.byType(TextField), 'B updated');
    final dynamic saveButton = tester.widget(
      find.byKey(const ValueKey('workspace-inline-preview-save')),
    );
    await tester.runAsync(() async {
      await saveButton.onPressed();
    });
    await tester.pump();

    expect(fileA.readAsStringSync(), 'A on disk');
    expect(fileB.readAsStringSync(), 'B updated');
  });

  testWidgets('rejects a text file above the five MiB stat limit', (
    tester,
  ) async {
    final largeFile = File('${workspaceDir.path}/large.txt');
    final writer = largeFile.openSync(mode: FileMode.write);
    writer.setPositionSync(5 * 1024 * 1024);
    writer.writeByteSync(0x61);
    writer.closeSync();
    var readerCalled = false;

    await tester.pumpWidget(
      buildBrowser(
        textFileReader: (path) {
          readerCalled = true;
          return 'must not be read';
        },
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    await tester.tap(find.text('large.txt'));
    await tester.pump();
    await pumpFileIo(tester);

    expect(find.text('文件过大，无法安全预览或编辑'), findsOneWidget);
    expect(readerCalled, isFalse);
    expect(
      find.byKey(const ValueKey('workspace-inline-preview-edit')),
      findsNothing,
    );
  });

  testWidgets('injected text reader cannot return more than five MiB', (
    tester,
  ) async {
    File('${workspaceDir.path}/reader.txt').writeAsStringSync('small');
    final oversizedText = String.fromCharCodes(
      Uint8List(5 * 1024 * 1024 + 1),
    );

    await tester.pumpWidget(
      buildBrowser(textFileReader: (_) => oversizedText),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    await tester.tap(find.text('reader.txt'));
    await tester.pump();
    await pumpFileIo(tester);

    expect(find.text('文件过大，无法安全预览或编辑'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('workspace-inline-preview-edit')),
      findsNothing,
    );
  });
}
