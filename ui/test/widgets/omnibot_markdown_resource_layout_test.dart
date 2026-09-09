import 'dart:convert';
import 'dart:io';

import 'package:archive/archive.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/widgets/omnibot_markdown_body.dart';
import 'package:ui/widgets/omnibot_resource_widgets.dart';
import 'package:webview_flutter_platform_interface/webview_flutter_platform_interface.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Directory directory;
  setUp(() async {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh', 'CN'));
    directory = await Directory.systemTemp.createTemp('oob-office-layout-');
    final xml =
        '<document><body>${List.generate(35, (i) => '<p><r><t>脱敏测试段落 $i</t></r></p>').join()}</body></document>';
    final bytes = utf8.encode(xml);
    final archive = Archive()
      ..addFile(ArchiveFile('word/document.xml', bytes.length, bytes));
    await File('${directory.path}/report.docx')
        .writeAsBytes(ZipEncoder().encode(archive));
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          spePermission,
          (call) async => {
            'rootPath': directory.path,
            'shellRootPath': '/workspace',
            'internalRootPath': '${directory.path}/.omnibot',
          },
        );
    await OmnibotResourceService.ensureWorkspacePathsLoaded(forceRefresh: true);
  });
  tearDown(() async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, null);
    await directory.delete(recursive: true);
  });
  for (final selectable in [false, true]) {
    for (final imageSyntax in [false, true]) {
      testWidgets(
        'HTML preview before table selectable=$selectable image=$imageSyntax',
        (tester) async {
          final platform = _LayoutWebViewPlatform();
          final previous = WebViewPlatform.instance;
          WebViewPlatform.instance = platform;
          if (previous != null) {
            addTearDown(() => WebViewPlatform.instance = previous);
          }
          await tester.runAsync(
            () =>
                File('${directory.path}/report.html')
                    .writeAsString('<h1>Test report</h1>'),
          );
          tester.view.physicalSize = const Size(432, 1200);
          tester.view.devicePixelRatio = 1;
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);
          for (var attempt = 0; attempt < 2; attempt++) {
            await tester.pumpWidget(
              MaterialApp(
                home: Scaffold(
                  body: SingleChildScrollView(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: OmnibotMarkdownBody(
                        data:
                            '正文之前\n\n${imageSyntax ? "!" : ""}[report](omnibot://workspace/report.html)\n\n报告核心发现\n\n| 板块 | 内容 |\n| --- | --- |\n| 模型 | 脱敏测试 |\n\n正文之后',
                        baseStyle: const TextStyle(fontSize: 16, height: 1.57),
                        selectable: selectable,
                      ),
                    ),
                  ),
                ),
              ),
            );
            void expectLayout() {
              final card = tester.getRect(
                find.byType(OmnibotInlineResourceEmbed),
              );
              final before = tester.getRect(
                find.text('正文之前', findRichText: true),
              );
              final heading = tester.getRect(
                find.text('报告核心发现', findRichText: true),
              );
              final table = tester.getRect(find.byType(Table));
              expect(card.top, greaterThanOrEqualTo(before.bottom));
              expect(card.bottom, lessThanOrEqualTo(heading.top));
              expect(card.bottom, lessThanOrEqualTo(table.top));
            }

            expectLayout();
            final initialHeight = tester
                .getSize(find.byType(OmnibotInlineResourceEmbed))
                .height;
            expect(initialHeight, greaterThanOrEqualTo(320));
            platform.delegate.finished?.call('file:///report.html');
            await tester.pumpAndSettle();
            expect(
              tester.getSize(find.byType(OmnibotInlineResourceEmbed)).height,
              greaterThan(initialHeight),
            );
            expectLayout();
            await tester.drag(
              find.byType(SingleChildScrollView).first,
              const Offset(0, -100),
            );
            await tester.pumpAndSettle();
            expectLayout();
            expect(tester.takeException(), isNull);
            await tester.pumpWidget(const SizedBox.shrink());
          }
        },
      );
    }
  }
  for (final selectable in [false, true]) {
    testWidgets(
      'Word preview reserves its full height on repeated rendering selectable=$selectable',
      (tester) async {
        tester.view.physicalSize = const Size(432, 900);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        for (var attempt = 0; attempt < 2; attempt++) {
          await tester.pumpWidget(
            MaterialApp(
              home: Scaffold(
                body: SingleChildScrollView(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: OmnibotMarkdownBody(
                      data: '正文之前\n\n[report.docx](omnibot://workspace/report.docx)\n\n正文之后',
                      baseStyle: const TextStyle(fontSize: 16, height: 1.57),
                      selectable: selectable,
                    ),
                  ),
                ),
              ),
            ),
          );
          void expectNoOverlap() {
            final card = tester.getRect(
              find.byType(OmnibotInlineResourceEmbed),
            );
            final before = tester.getRect(
              find.text('正文之前', findRichText: true),
            );
            final after = tester.getRect(find.text('正文之后', findRichText: true));
            expect(card.height, greaterThan(220));
            expect(
              card.top,
              greaterThanOrEqualTo(before.bottom),
              reason: 'preview must not paint over previous prose',
            );
            expect(
              card.bottom,
              lessThanOrEqualTo(after.top),
              reason: 'following prose must start after the full preview',
            );
          }

          expectNoOverlap();
          // File.readAsBytes has several asynchronous I/O phases. Alternate
          // real I/O and fake-frame pumps instead of settling an active spinner.
          for (
            var frame = 0;
            frame < 40 && find.text('共提取 35 段正文').evaluate().isEmpty;
            frame++
          ) {
            await tester.runAsync(
              () async =>
                  Future<void>.delayed(const Duration(milliseconds: 25)),
            );
            await tester.pump();
          }
          expect(find.text('共提取 35 段正文'), findsOneWidget);
          expectNoOverlap();
          await tester.drag(
            find.byType(OmnibotInlineResourceEmbed),
            const Offset(0, -80),
          );
          await tester.pumpAndSettle();
          expectNoOverlap();
          expect(tester.takeException(), isNull);
          await tester.pumpWidget(const SizedBox.shrink());
        }
      },
    );
  }
}

// Only the native WebView surface is substituted; Markdown, resource resolution,
// card layout, and its asynchronous measured-height update use production code.
class _LayoutWebViewPlatform extends WebViewPlatform {
  late _LayoutNavigationDelegate delegate;
  @override
  PlatformWebViewController createPlatformWebViewController(
    PlatformWebViewControllerCreationParams params,
  ) => _LayoutWebViewController(params);
  @override
  PlatformNavigationDelegate createPlatformNavigationDelegate(
    PlatformNavigationDelegateCreationParams params,
  ) => delegate = _LayoutNavigationDelegate(params);
  @override
  PlatformWebViewWidget createPlatformWebViewWidget(
    PlatformWebViewWidgetCreationParams params,
  ) => _LayoutWebViewWidget(params);
}

class _LayoutWebViewController extends PlatformWebViewController {
  _LayoutWebViewController(super.params) : super.implementation();
  @override
  Future<void> setJavaScriptMode(JavaScriptMode mode) async {}
  @override
  Future<void> setBackgroundColor(Color color) async {}
  @override
  Future<void> setPlatformNavigationDelegate(
    PlatformNavigationDelegate handler,
  ) async {}
  @override
  Future<void> enableZoom(bool enabled) async {}
  @override
  Future<void> loadFile(String path) async {}
  @override
  Future<Object> runJavaScriptReturningResult(String javaScript) async => 900;
}

class _LayoutNavigationDelegate extends PlatformNavigationDelegate {
  _LayoutNavigationDelegate(super.params) : super.implementation();
  PageEventCallback? finished;
  @override
  Future<void> setOnPageStarted(PageEventCallback callback) async {}
  @override
  Future<void> setOnPageFinished(PageEventCallback callback) async {
    finished = callback;
  }

  @override
  Future<void> setOnWebResourceError(WebResourceErrorCallback callback) async {}
}

class _LayoutWebViewWidget extends PlatformWebViewWidget {
  _LayoutWebViewWidget(super.params) : super.implementation();
  @override
  Widget build(BuildContext context) => const ColoredBox(color: Colors.black);
}
