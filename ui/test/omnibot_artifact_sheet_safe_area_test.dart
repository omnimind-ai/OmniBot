import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/omnibot_workspace/omnibot_artifact_preview_page.dart';
import 'package:ui/services/chat_detail_sheet_preferences.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/omni_glass.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late Directory directory;
  late File file;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    await ChatDetailSheetPreferences.saveHeightFactor(
      0.62,
      min: 0.36,
      max: 0.94,
    );
    directory = await Directory.systemTemp.createTemp('artifact_sheet_insets_');
    file = await File('${directory.path}/run.log').writeAsString(
      List.generate(80, (index) => 'Output line $index').join('\n'),
    );
    OmnibotResourceService.debugSetWorkspacePaths(
      OmnibotWorkspacePaths(
        rootPath: directory.path,
        shellRootPath: '/workspace',
        internalRootPath: '${directory.path}/.omnibot',
      ),
    );
    messenger.setMockMethodCallHandler(spePermission, (call) async {
      if (call.method == 'isWorkspaceStorageAccessGranted') return true;
      return null;
    });
  });

  tearDown(() async {
    messenger.setMockMethodCallHandler(spePermission, null);
    OmnibotResourceService.debugResetWorkspacePaths();
    await directory.delete(recursive: true);
  });

  for (final glass in [false, true]) {
    testWidgets('file sheet paints behind the gesture bar, glass=$glass', (
      tester,
    ) async {
      _viewport(tester);
      await _openFile(tester, file, glass: glass);

      final pane = find.byType(OmniGlassPanel);
      final original = tester.getRect(pane);
      expect(original.bottom, 844);
      // Retain the old content height and sheet top while extending its paint.
      expect(original.height, closeTo(844 * 0.62 + 24, 0.1));
      expect(
        tester.getRect(find.byType(OmnibotArtifactPreviewPage)).bottom,
        closeTo(820, 1),
      );
      expect(find.byTooltip('编辑文件').hitTestable(), findsOneWidget);
      expect(find.byType(LiquidGlass), glass ? findsOneWidget : findsNothing);

      final scroll = tester
          .state<ScrollableState>(
            find.descendant(of: pane, matching: find.byType(Scrollable)).first,
          )
          .position;
      await tester.drag(
        find.byType(SingleChildScrollView),
        const Offset(0, -160),
      );
      await tester.pumpAndSettle();
      expect(scroll.pixels, greaterThan(0));
      expect(tester.getRect(pane).bottom, 844);

      await tester.dragFrom(
        Offset(original.center.dx, original.top + 10),
        const Offset(0, -90),
      );
      await tester.pumpAndSettle();
      expect(tester.getRect(pane).height, greaterThan(original.height + 40));
      expect(tester.getRect(pane).bottom, 844);
      expect(
        tester.getRect(find.byType(OmnibotArtifactPreviewPage)).bottom,
        closeTo(820, 1),
      );
      expect(tester.takeException(), isNull);
    });

    testWidgets('file editor avoids the keyboard once, glass=$glass', (
      tester,
    ) async {
      _viewport(tester);
      await _openFile(tester, file, glass: glass);
      await tester.tap(find.byTooltip('编辑文件'));
      await tester.pumpAndSettle();
      final editor = find.byKey(
        const ValueKey('artifact-preview-editor-field'),
      );
      expect(editor, findsOneWidget);

      tester.view.viewInsets = const FakeViewPadding(bottom: 300);
      tester.view.padding = FakeViewPadding.zero;
      await tester.pumpAndSettle();
      final pane = tester.getRect(find.byType(OmniGlassPanel));
      expect(pane.bottom, 544);
      expect(pane.height, closeTo(544 * 0.82, 0.1));
      expect(
        tester.getRect(find.byType(OmnibotArtifactPreviewPage)).bottom,
        closeTo(544, 1),
      );
      expect(tester.getRect(editor).bottom, lessThanOrEqualTo(544));
      expect(tester.getRect(editor).height, greaterThan(200));
      expect(find.byTooltip('保存文件').hitTestable(), findsOneWidget);

      tester.view.viewInsets = FakeViewPadding.zero;
      tester.view.padding = const FakeViewPadding(bottom: 24);
      await tester.pumpAndSettle();
      expect(tester.getRect(find.byType(OmniGlassPanel)).bottom, 844);
      expect(
        tester.getRect(find.byType(OmnibotArtifactPreviewPage)).bottom,
        closeTo(820, 1),
      );
      expect(tester.takeException(), isNull);
    });
  }
}

void _viewport(WidgetTester tester) {
  tester.view.physicalSize = const Size(390, 844);
  tester.view.devicePixelRatio = 1;
  tester.view.viewPadding = const FakeViewPadding(bottom: 24);
  tester.view.padding = const FakeViewPadding(bottom: 24);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(tester.view.resetViewPadding);
  addTearDown(tester.view.resetPadding);
  addTearDown(tester.view.resetViewInsets);
}

Future<void> _openFile(
  WidgetTester tester,
  File file, {
  required bool glass,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: AppTheme.lightTheme,
      builder: (context, child) => LiquidGlassScope(
        enabled: glass,
        quality: LiquidGlassQuality.ultra,
        child: LiquidGlassHost(enabled: glass, child: child!),
      ),
      home: Builder(
        builder: (context) => Scaffold(
          body: TextButton(
            onPressed: () => showOmnibotArtifactPreviewSheet(
              context,
              OmnibotResourceService.describePath(file.path),
            ),
            child: const Text('Open file'),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('Open file'));
  // Route admission awaits access checks, then the page starts a real file
  // read. Give both stages a frame before waiting for settled animations.
  for (
    var attempt = 0;
    attempt < 20 && find.byType(SelectableText).evaluate().isEmpty;
    attempt++
  ) {
    await tester.pump(const Duration(milliseconds: 100));
    await tester.runAsync(() async {
      await Future<void>.delayed(const Duration(milliseconds: 20));
    });
  }
  expect(find.byType(SelectableText), findsOneWidget);
  await tester.pumpAndSettle();
}
