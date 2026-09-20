import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/settings/settings_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';

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
    await expectEntryIcon('Agent 模式', LucideIcons.bot);
    expect(find.text('执行中心'), findsNothing);
    expect(find.text('手动录制、执行已保存的操作、查看结果'), findsNothing);
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
  });
}
