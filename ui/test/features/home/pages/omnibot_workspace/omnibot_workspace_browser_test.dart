import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/omnibot_workspace/widgets/omnibot_workspace_browser.dart';

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

Future<void> _settleDirectory(WidgetTester tester) async {
  for (var i = 0; i < 100; i++) {
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 10)),
    );
    await tester.pump();
    if (find.text('正在加载工作区…').evaluate().isEmpty &&
        find.text('Loading workspace…').evaluate().isEmpty)
      return;
  }
  fail('Directory loading did not finish');
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory workspaceDir;
  late File noteFile;

  setUp(() async {
    workspaceDir = await Directory.systemTemp.createTemp(
      'omnibot_workspace_browser_test_',
    );
    final docsDir = Directory('${workspaceDir.path}/docs');
    await docsDir.create(recursive: true);
    noteFile = File('${docsDir.path}/note.md');
    await noteFile.writeAsString('# hello pane preview');
    await File('${workspaceDir.path}/root.txt').writeAsString('root file');
  });

  tearDown(() async {
    if (await workspaceDir.exists()) {
      await workspaceDir.delete(recursive: true);
    }
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
            ),
          ),
        ),
      ),
    );
    await tester.pump();
    await _settleDirectory(tester);

    expect(find.text('工作区'), findsNothing);
    expect(find.text('/workspace'), findsOneWidget);
    expect(find.text('docs'), findsOneWidget);

    await tester.tap(find.text('docs'));
    await tester.pump();
    await _settleDirectory(tester);

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
    await _settleDirectory(tester);

    expect(find.text('root.txt'), findsOneWidget);
    expect(
      find.byKey(const ValueKey('workspace-inline-preview-edit')),
      findsNothing,
    );
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
            ),
          ),
        ),
      ),
    );
    await tester.pump();
    await _settleDirectory(tester);

    await tester.tap(find.text('docs'));
    await tester.pump();
    await _settleDirectory(tester);

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
    await _settleDirectory(tester);

    expect(find.byType(TextField), findsOneWidget);

    await tester.enterText(find.byType(TextField), '# updated content');
    await tester.pump();

    final dynamic saveButton = tester.widget(
      find.byKey(const ValueKey('workspace-inline-preview-save')),
    );
    await saveButton.onPressed();
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
  });
}
