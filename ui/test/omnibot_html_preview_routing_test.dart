import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/special_permission.dart';

const _fileChannel = MethodChannel('cn.com.omnimind.bot/file_save');
const _workspace = '/data/user/0/cn.com.omnimind.bot/workspace';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late List<MethodCall> nativeCalls;
  late List<String> operations;

  setUp(() {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh', 'CN'));
    OmnibotResourceService.debugSetWorkspacePaths(
      const OmnibotWorkspacePaths(
        rootPath: _workspace,
        shellRootPath: '/workspace',
        internalRootPath: '$_workspace/.omnibot',
      ),
    );
    nativeCalls = <MethodCall>[];
    operations = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, (call) async {
          operations.add(call.method);
          return true;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_fileChannel, (call) async {
          operations.add(call.method);
          nativeCalls.add(call);
          return true;
        });
  });

  tearDown(() {
    OmnibotResourceService.debugResetWorkspacePaths();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_fileChannel, null);
  });

  for (final path in <String>[
    '$_workspace/modern.html',
    '/storage/emulated/0/Download/报告.HTM',
  ]) {
    testWidgets('Android HTML opens native preview after permission: $path', (
      tester,
    ) async {
      final router = await _pumpRouter(tester);

      expect(await _openFile(tester, path, title: '测试预览'), isTrue);
      await tester.pump();

      expect(nativeCalls, hasLength(1));
      expect(nativeCalls.single.method, 'openHtmlPreview');
      expect(nativeCalls.single.arguments, <String, String>{
        'sourcePath': path,
        'title': '测试预览',
      });
      expect(operations, <String>[
        path.startsWith('/storage')
            ? 'isPublicStorageAccessGranted'
            : 'isWorkspaceStorageAccessGranted',
        'openHtmlPreview',
      ]);
      expect(router.routeInformationProvider.value.uri.path, '/workspace');
      expect(find.text('Flutter file preview'), findsNothing);
    });
  }

  testWidgets('Android non-HTML keeps the existing Flutter preview route', (
    tester,
  ) async {
    final router = await _pumpRouter(tester);
    expect(
      await _openFile(tester, '$_workspace/note.txt', startInEditMode: true),
      isTrue,
    );
    await tester.pumpAndSettle();

    expect(nativeCalls, isEmpty);
    expect(find.text('Flutter file preview'), findsOneWidget);
    final extra = router.state.extra! as Map<String, dynamic>;
    expect(extra['path'], '$_workspace/note.txt');
    expect(extra['previewKind'], 'text');
    expect(extra['startInEditMode'], isTrue);
  });

  testWidgets('an explicit code preview of HTML stays editable in Flutter', (
    tester,
  ) async {
    final router = await _pumpRouter(tester);
    await _openFile(
      tester,
      '$_workspace/modern.html',
      previewKind: 'code',
      startInEditMode: true,
    );
    await tester.pumpAndSettle();

    expect(nativeCalls, isEmpty);
    expect(find.text('Flutter file preview'), findsOneWidget);
    expect((router.state.extra! as Map)['previewKind'], 'code');
  });

  testWidgets('non-Android HTML retains the Flutter preview', (tester) async {
    await _pumpRouter(tester);

    await _openFile(tester, '$_workspace/modern.html');
    await tester.pumpAndSettle();

    expect(nativeCalls, isEmpty);
    expect(find.text('Flutter file preview'), findsOneWidget);
  }, variant: TargetPlatformVariant.only(TargetPlatform.iOS));

  testWidgets('declined resource access never launches native preview', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, (_) async => false);
    final router = await _pumpRouter(tester);
    late Future<bool> opening;
    await tester.runAsync(() async {
      opening = OmnibotResourceService.openFilePath('$_workspace/modern.html');
      // Deliver the mocked platform permission result before pumping the
      // authorization route. The open future remains pending until it closes.
      await Future<void>.delayed(Duration.zero);
    });
    await tester.pumpAndSettle();
    expect(find.text('Storage permission'), findsOneWidget);

    router.pop(false);
    await tester.pumpAndSettle();

    expect(await tester.runAsync(() => opening), isFalse);
    expect(nativeCalls, isEmpty);
    expect(router.routeInformationProvider.value.uri.path, '/workspace');
  });

  for (final result in <Object?>[
    false,
    null,
    PlatformException(code: 'file_not_found', message: '文件已不存在'),
  ]) {
    testWidgets(
      'native launch failure is visible without Flutter fallback: $result',
      (tester) async {
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(_fileChannel, (call) async {
              nativeCalls.add(call);
              if (result is PlatformException) throw result;
              return result;
            });
        final router = await _pumpRouter(tester);

        expect(await _openFile(tester, '$_workspace/modern.html'), isFalse);
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 250));

        expect(nativeCalls, hasLength(1));
        expect(find.textContaining('HTML 预览打开失败'), findsOneWidget);
        expect(router.routeInformationProvider.value.uri.path, '/workspace');
        expect(find.text('Flutter file preview'), findsNothing);
        await tester.pump(const Duration(seconds: 3));
        await tester.pumpAndSettle();
      },
    );
  }
}

Future<GoRouter> _pumpRouter(WidgetTester tester) async {
  final router = GoRouter(
    navigatorKey: GoRouterManager.rootNavigatorKey,
    initialLocation: '/workspace',
    routes: <GoRoute>[
      GoRoute(
        path: '/workspace',
        builder: (_, _) => const Scaffold(body: Text('Workspace')),
      ),
      GoRoute(
        path: '/home/omnibot_artifact_preview',
        builder: (_, _) => const Scaffold(body: Text('Flutter file preview')),
      ),
      GoRoute(
        path: '/home/authorize',
        builder: (_, _) => const Scaffold(body: Text('Storage permission')),
      ),
    ],
  );
  addTearDown(router.dispose);
  await tester.pumpWidget(MaterialApp.router(routerConfig: router));
  await tester.pumpAndSettle();
  return router;
}

Future<bool?> _openFile(
  WidgetTester tester,
  String path, {
  String? title,
  String? previewKind,
  bool startInEditMode = false,
}) => tester.runAsync(
  () => OmnibotResourceService.openFilePath(
    path,
    title: title,
    previewKind: previewKind,
    startInEditMode: startInEditMode,
  ),
);
