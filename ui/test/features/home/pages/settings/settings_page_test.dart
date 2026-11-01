import 'dart:convert';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/pages/settings/experience_misc_setting_page.dart';
import 'package:ui/widgets/omni_root_toolbar.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/settings/settings_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/navigation/open_container.dart';

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

  const mcpChannel = MethodChannel('cn.com.omnimind.bot/McpServer');
  const assistChannel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(mcpChannel, (call) async {
          if (call.method == 'state') {
            return <String, Object?>{
              'enabled': false,
              'running': false,
              'port': 0,
              'token': '',
            };
          }
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistChannel, (call) async {
          switch (call.method) {
            case 'getWorkspaceMemoryEmbeddingConfig':
              return <String, Object?>{'enabled': false, 'configured': false};
            case 'getWorkspaceMemoryRollupStatus':
              return <String, Object?>{'enabled': false};
            default:
              return null;
          }
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(mcpChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistChannel, null);
  });

  testWidgets('settings section titles render without trailing divider lines', (
    tester,
  ) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(800, 1600);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        theme: AppTheme.lightTheme,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: const SettingsPage(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    Future<void> expectEntryIcon(String title, IconData icon) async {
      final titleFinder = find.text(title);
      await tester.scrollUntilVisible(
        titleFinder,
        400,
        scrollable: find.byType(Scrollable).first,
      );
      final tileFinder = find.ancestor(
        of: titleFinder,
        matching: find.byType(InkWell),
      );
      expect(
        find.descendant(of: tileFinder, matching: find.byIcon(icon)),
        findsOneWidget,
      );
    }

    expect(find.text('账号与 AI 服务'), findsOneWidget);

    await expectEntryIcon('模型提供商', LucideIcons.box);
    await expectEntryIcon('场景模型配置', LucideIcons.fileBox);
    await expectEntryIcon('Workspace 记忆配置', LucideIcons.database);
    expect(find.text('Agent 模式'), findsNothing);
    await expectEntryIcon('本机服务', LucideIcons.monitorSmartphone);
    await expectEntryIcon('MCP 工具', LucideIcons.hammer);
    await expectEntryIcon('外观设置', LucideIcons.palette);

    for (final title in <String>['模型与记忆', '服务与环境', '体验与外观', '权限与信息']) {
      final titleFinder = find.text(title);
      await tester.scrollUntilVisible(
        titleFinder,
        400,
        scrollable: find.byType(Scrollable).first,
      );
      expect(titleFinder, findsOneWidget);
      expect(
        find.ancestor(of: titleFinder, matching: find.byType(Row)),
        findsNothing,
      );
    }
  });

  testWidgets('local service actions start at the left edge of the sheet', (
    tester,
  ) async {
    var allowLanAccess = false;
    final enableCalls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(mcpChannel, (call) async {
          if (call.method == 'state') {
            return <String, Object?>{
              'enabled': true,
              'running': true,
              'host': '127.0.0.1',
              'port': 8765,
              'token': 'test-token',
            };
          }
          if (call.method == 'setEnabled') {
            enableCalls.add(call);
            if (!allowLanAccess) {
              throw PlatformException(code: 'MCP_ERROR', message: '局域网权限未授权');
            }
            return <String, Object?>{
              'enabled': true,
              'running': true,
              'host': '192.168.1.2',
              'port': 8765,
              'token': 'test-token',
            };
          }
          return null;
        });
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        theme: AppTheme.lightTheme,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: DefaultAssetBundle(
          bundle: _SvgTestAssetBundle(),
          child: const SettingsPage(),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.text('本机服务'),
      400,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(find.text('本机服务'));
    await tester.pumpAndSettle();

    final sheet = find.byKey(const ValueKey('local-service-sheet'));
    expect(sheet, findsOneWidget);
    expect(
      tester
          .widget<Wrap>(find.byKey(const ValueKey('local-service-actions')))
          .alignment,
      WrapAlignment.start,
    );
    expect(find.text('复制地址'), findsOneWidget);
    expect(find.text('复制 Token'), findsOneWidget);
    expect(find.text('刷新 Token'), findsOneWidget);
    expect(find.textContaining('此地址仅能在当前设备访问'), findsOneWidget);
    final enableLan = find.byKey(const ValueKey('local-service-enable-lan'));
    await tester.tap(enableLan);
    await tester.pumpAndSettle();
    expect(enableCalls.single.arguments, {'enable': true});

    // Denial preserves the existing loopback service and its details entry.
    await tester.tap(find.text('本机服务'));
    await tester.pumpAndSettle();
    expect(sheet, findsOneWidget);
    allowLanAccess = true;
    await tester.tap(enableLan);
    await tester.pumpAndSettle();
    expect(enableCalls.length, 2);
    expect(find.textContaining('当前仅本机可用'), findsNothing);
  });

  testWidgets(
    'embedded settings has no back button or duplicate Agents entry',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: const SettingsPage(embedded: true),
        ),
      );
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<OmniRootToolbar>(find.byType(OmniRootToolbar))
            .showBackButton,
        isFalse,
      );
      expect(find.text('Agent 模式'), findsNothing);
    },
  );

  testWidgets('real router opens a settings row with the container route', (
    tester,
  ) async {
    await StorageService.setBool(StorageKeys.welcomeCompleted, true);
    GoRouterManager.setInitialRoute('/home/settings');
    GoRouter? router;
    addTearDown(() {
      router?.dispose();
      GoRouterManager.setInitialRoute(null);
    });
    await tester.pumpWidget(
      ProviderScope(
        child: Consumer(
          builder: (context, ref, child) {
            router ??= GoRouterManager.createRouter(ref);
            return MaterialApp.router(
              routerConfig: router,
              theme: AppTheme.lightTheme,
              locale: const Locale('zh'),
              localizationsDelegates: AppLocalizations.localizationsDelegates,
              supportedLocales: AppLocalizations.supportedLocales,
            );
          },
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.text('杂项'),
      300,
      scrollable: find.byType(Scrollable).first,
    );
    final sourceRect = tester.getRect(
      find.ancestor(
        of: find.text('杂项'),
        matching: find.byType(OmniOpenContainer),
      ),
    );
    await tester.tap(find.text('杂项'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    final detail = find.byType(ExperienceMiscSettingPage);
    expect(detail, findsOneWidget);
    final route = ModalRoute.of(tester.element(detail))!;
    expect(route.transitionDuration, const Duration(milliseconds: 300));
    expect(route.animation!.isAnimating, isTrue);
    final full = Offset.zero & tester.getSize(find.byType(MaterialApp));
    final opening = Rect.lerp(
      sourceRect,
      full,
      Curves.fastOutSlowIn.transform(route.animation!.value),
    )!;
    expect(tester.getTopLeft(detail).dy, closeTo(opening.top, 0.01));
    await tester.pumpAndSettle();
    expect(router!.canPop(), isTrue);
    Future<void> back(String method, [Map<String, Object?>? event]) =>
        tester.binding.defaultBinaryMessenger.handlePlatformMessage(
          'flutter/backgesture',
          const StandardMethodCodec().encodeMethodCall(
            MethodCall(method, event),
          ),
          (_) {},
        );
    // The ordinary page beneath a container must not claim system progress
    // or replace the container's return to its source row.
    await back('startBackGesture', {
      'touchOffset': <double>[799, 300],
      'progress': 0.0,
      'swipeEdge': 1,
    });
    await back('updateBackGestureProgress', {
      'touchOffset': <double>[799, 300],
      'progress': 0.4,
      'swipeEdge': 1,
    });
    await tester.pump();
    expect(route.animation!.value, 1);
    await back('commitBackGesture');
    await tester.pump();
    // FlClash deliberately remeasures after the source route lays out again;
    // a lazy scrolling section can now be at a different offset from entry.
    final returnSource = tester.getRect(
      find.byKey(const ValueKey('settings-open-/home/experience_misc_setting')),
    );
    await tester.pump(const Duration(milliseconds: 150));
    final returning = Rect.lerp(
      returnSource,
      full,
      Curves.fastOutSlowIn.flipped.transform(0.5),
    )!;
    expect(tester.getTopLeft(detail).dx, closeTo(returning.left, 0.01));
    expect(tester.getTopLeft(detail).dy, closeTo(returning.top, 0.01));
    await tester.pumpAndSettle();
    expect(detail, findsNothing);
    expect(find.text('杂项'), findsOneWidget);
    expect(router!.canPop(), isFalse);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets(
    'reparented first middle and last rows preserve their group geometry and paint',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = const Size(390, 1000);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      const paths = [
        '/home/model_provider_setting',
        '/home/scene_model_setting',
        '/home/workspace_memory_setting',
      ];
      final boundaryKey = GlobalKey();
      final router = GoRouter(
        navigatorKey: GoRouterManager.rootNavigatorKey,
        routes: [
          GoRoute(
            path: '/',
            builder: (_, _) => const SettingsPage(embedded: true),
          ),
          for (final path in paths)
            GoRoute(
              path: path,
              pageBuilder: (_, state) =>
                  (state.extra! as Map)[omniContainerExtraKey]
                      .createPage<Object?>(
                        key: state.pageKey,
                        child: const Scaffold(body: Text('Row destination')),
                      ),
            ),
        ],
      );
      addTearDown(router.dispose);
      await tester.pumpWidget(
        MaterialApp.router(
          routerConfig: router,
          theme: AppTheme.lightTheme,
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          builder: (_, child) =>
              RepaintBoundary(key: boundaryKey, child: child),
        ),
      );
      await tester.pumpAndSettle();
      Finder row(String path) => find.byKey(ValueKey('settings-open-$path'));
      Future<List<int>> pixels(Rect rect) async {
        final sampled = await tester.runAsync(() async {
          final boundary =
              boundaryKey.currentContext!.findRenderObject()!
                  as RenderRepaintBoundary;
          final image = await boundary.toImage();
          final bytes = (await image.toByteData(
            format: ui.ImageByteFormat.rawRgba,
          ))!;
          // Corner pixels expose a missing per-row surface/shape after it leaves
          // the parent card. Side-middle pixels expose the missing card fill.
          final points = [
            rect.topLeft + const Offset(2, 2),
            rect.bottomLeft + const Offset(2, -3),
            Offset(rect.left + 6, rect.center.dy),
          ];
          final values = <int>[
            for (final point in points)
              for (var channel = 0; channel < 3; channel++)
                bytes.getUint8(
                  (point.dy.floor() * image.width + point.dx.floor()) * 4 +
                      channel,
                ),
          ];
          image.dispose();
          return values;
        });
        return sampled!;
      }

      for (final path in paths) {
        final before = {
          for (final source in paths) source: tester.getRect(row(source)),
        };
        final originalPixels = await pixels(before[path]!);
        final label = find
            .descendant(of: row(path), matching: find.byType(Text))
            .first;
        final title = tester.widget<Text>(label).data!;
        final labelRect = tester.getRect(label);
        await tester.tap(label);
        await tester.pump();
        await tester.pump(); // Finish Navigator's offstage preparation at t=0.
        // The first prepared frame must paint the same source, not a square,
        // transparent tile or an origin shifted by its siblings' layout.
        expect(tester.getRect(find.text(title)), labelRect);
        for (final source in paths) {
          expect(tester.getRect(row(source)), before[source]);
        }
        final movedPixels = await pixels(before[path]!);
        for (var index = 0; index < originalPixels.length; index++) {
          expect(movedPixels[index], closeTo(originalPixels[index], 1));
        }
        await tester.pumpAndSettle();
        router.pop();
        await tester.pumpAndSettle();
        expect(tester.getRect(row(path)), before[path]);
        expect(tester.takeException(), isNull);
      }
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
}
