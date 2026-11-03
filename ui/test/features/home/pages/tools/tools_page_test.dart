import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/tools/tools_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/omni_circle_button.dart';

const _local = MethodChannel('cn.com.omnimind.bot/PluginPlatform');
const _remote = MethodChannel('cn.com.omnimind.bot/RemoteMcpConfig');

final _tool = {
  'name': 'file_read',
  'displayName': '读取文件',
  'description': '读取工作区中的文件内容',
  'group': 'builtin',
  'source': null,
  'status': 'registered',
};

Widget _app({bool dark = false}) => MaterialApp(
  locale: const Locale('zh'),
  theme: dark ? AppTheme.darkTheme : AppTheme.lightTheme,
  localizationsDelegates: AppLocalizations.localizationsDelegates,
  supportedLocales: AppLocalizations.supportedLocales,
  home: const ToolsPage(),
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() {
    messenger.setMockMethodCallHandler(_local, (call) async {
      expect(call.method, 'listTools');
      return [_tool];
    });
    messenger.setMockMethodCallHandler(_remote, (call) async {
      expect(call.method, 'listToolCatalog');
      return [];
    });
  });
  tearDown(() {
    messenger.setMockMethodCallHandler(_local, null);
    messenger.setMockMethodCallHandler(_remote, null);
  });

  testWidgets('tablet tool search and toolbar stay aligned through resize', (
    tester,
  ) async {
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);
    for (final dark in [false, true]) {
      tester.view.physicalSize = const Size(1366, 1024);
      await tester.pumpWidget(_app(dark: dark));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '工作区');
      final editorState = tester.state(find.byType(EditableText));
      for (final width in [1366.0, 1024.0, 768.0, 600.0, 390.0]) {
        tester.view.physicalSize = Size(width, 900);
        await tester.pumpAndSettle();
        final columnLeft = (width - width.clamp(0, 840)) / 2;
        final search = tester.getRect(find.byType(TextField));
        expect(search.left, greaterThanOrEqualTo(columnLeft + 22));
        expect(search.right, lessThanOrEqualTo(width - columnLeft - 22));
        expect(tester.state(find.byType(EditableText)), same(editorState));
        expect(find.text('工作区'), findsOneWidget);
        expect(find.text('读取文件'), findsOneWidget);
        final back = find.byType(OmniCircleButton).first;
        expect(tester.getCenter(back).dx, closeTo(columnLeft + 36, 1));
        expect(tester.takeException(), isNull);
      }
      await tester.pumpWidget(const SizedBox.shrink());
    }
  });

  testWidgets('toolbar circles share 16dp visible insets and 40dp diameter', (
    tester,
  ) async {
    for (final dark in [false, true]) {
      await tester.pumpWidget(_app(dark: dark));
      await tester.pumpAndSettle();
      final circles = find.byType(OmniCircleButton);
      Finder pane(Finder button) => find.descendant(
        of: button,
        matching: find.byWidgetPredicate(
          (widget) => widget is Material && widget.shape is CircleBorder,
        ),
      );
      final back = tester.getRect(pane(circles.first));
      final refresh = tester.getRect(pane(circles.last));
      final page = tester.getRect(find.byType(Scaffold));
      expect(back.size, const Size.square(40));
      expect(refresh.size, const Size.square(40));
      expect(back.left - page.left, 16);
      expect(page.right - refresh.right, 16);
      expect(tester.getSize(circles.last), const Size.square(48));
      expect(tester.widget<OmniCircleButton>(circles.last).iconSize, 20);
      await tester.pumpWidget(const SizedBox());
    }
  });

  testWidgets(
    'refresh shares circle control and remains disabled while loading',
    (tester) async {
      final pending = Completer<dynamic>();
      var requests = 0;
      messenger.setMockMethodCallHandler(_remote, (_) {
        requests++;
        return pending.future;
      });
      await tester.pumpWidget(_app());
      await tester.pump();
      final button = find.byWidgetPredicate(
        (widget) => widget is OmniCircleButton && widget.tooltip == '刷新工具',
      );
      expect(tester.widget<OmniCircleButton>(button).onPressed, isNull);
      expect(
        find.descendant(
          of: button,
          matching: find.byType(CircularProgressIndicator),
        ),
        findsOneWidget,
      );
      await tester.tap(button);
      await tester.pump();
      expect(requests, 1);
      pending.complete([]);
      await tester.pumpAndSettle();
      expect(tester.widget<OmniCircleButton>(button).onPressed, isNotNull);
      expect(
        find.descendant(
          of: button,
          matching: find.byType(CircularProgressIndicator),
        ),
        findsNothing,
      );
      await tester.tap(button);
      await tester.pumpAndSettle();
      expect(requests, 2);
    },
  );

  testWidgets('searches descriptions and opens tool details in both themes', (
    tester,
  ) async {
    for (final dark in [false, true]) {
      await tester.pumpWidget(_app(dark: dark));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '工作区');
      await tester.pumpAndSettle();
      expect(find.text('读取文件'), findsOneWidget);
      await tester.tap(find.text('读取文件'));
      await tester.pumpAndSettle();
      expect(find.text('使用条件'), findsOneWidget);
      expect(find.text('file_read'), findsOneWidget);
      expect(tester.takeException(), isNull);
      Navigator.of(tester.element(find.text('使用条件'))).pop();
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), 'missing-tool');
      await tester.pumpAndSettle();
      expect(find.text('没有找到匹配的工具，试试其他关键词。'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
    }
  });

  testWidgets(
    'local tools remain usable while remote discovery is pending or fails',
    (tester) async {
      final pending = Completer<dynamic>();
      messenger.setMockMethodCallHandler(_remote, (_) => pending.future);
      await tester.pumpWidget(_app());
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      expect(find.text('读取文件'), findsOneWidget);
      expect(find.text('发现远程工具…'), findsOneWidget);
      pending.completeError(PlatformException(code: 'offline'));
      await tester.pumpAndSettle();
      expect(find.text('读取文件'), findsOneWidget);
      expect(find.text('远程工具加载失败'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'disabled and failed servers do not masquerade as available tools',
    (tester) async {
      messenger.setMockMethodCallHandler(
        _remote,
        (_) async => [
          {'source': 'Disabled server', 'status': 'disabled', 'tools': []},
          {'source': 'Offline server', 'status': 'unavailable', 'tools': []},
        ],
      );
      await tester.pumpWidget(_app());
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.text('Offline server · 连接失败'),
        200,
        scrollable: find.descendant(
          of: find.byType(ListView),
          matching: find.byType(Scrollable),
        ),
      );
      expect(find.text('Disabled server · 未启用'), findsOneWidget);
      expect(find.text('远程 MCP · 0'), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
}
