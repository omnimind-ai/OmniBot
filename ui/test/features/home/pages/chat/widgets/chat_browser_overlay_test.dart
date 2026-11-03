import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/widgets/omni_menu.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_browser_overlay.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';

void main() {
  tearDown(LegacyTextLocalizer.clearResolvedLocale);

  const channel = MethodChannel('cn.com.omnimind.bot/AgentBrowserSession');
  Widget browser({ValueChanged<Offset>? onResizeRightDelta}) => MaterialApp(
    locale: const Locale('zh'),
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    home: Scaffold(
      body: ChatBrowserOverlay(
        snapshot: const ChatBrowserSessionSnapshot(
          available: true,
          workspaceId: 'conversation_1',
          activeTabId: 2,
          currentUrl: 'https://example.com',
          title: '示例页面',
          tabs: [
            AgentBrowserTab(
              tabId: 2,
              url: 'https://example.com',
              title: '示例页面',
              isActive: true,
            ),
            AgentBrowserTab(
              tabId: 7,
              url: 'https://flutter.dev',
              title: 'Flutter',
              isActive: false,
            ),
          ],
        ),
        onSnapshotChanged: (_) {},
        onClose: () {},
        onDragDelta: (_) {},
        onResizeLeftDelta: (_) {},
        onResizeRightDelta: onResizeRightDelta ?? (_) {},
      ),
    ),
  );
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );

  testWidgets('browser action menu finishes returning before opening history', (
    tester,
  ) async {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    await tester.pumpWidget(browser());
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('菜单'));
    await tester.pumpAndSettle();
    expect(find.byType(OmniMenuSurface), findsOneWidget);
    expect(find.byType(BottomSheet), findsNothing);
    await tester.tap(find.text('历史记录'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 250));
    expect(find.byType(BottomSheet), findsNothing);
    await tester.pumpAndSettle();
    expect(find.byType(OmniMenuSurface), findsNothing);
    expect(find.byType(BottomSheet), findsOneWidget);
    expect(find.text('暂无历史记录'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('browser corner resize keeps its drag gesture and menu taps', (
    tester,
  ) async {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    final deltas = <Offset>[];
    await tester.pumpWidget(browser(onResizeRightDelta: deltas.add));
    await tester.pumpAndSettle();
    await tester.drag(find.byTooltip('菜单'), const Offset(24, 0));
    await tester.pumpAndSettle();
    expect(deltas, isNotEmpty);
    expect(find.byType(OmniMenuSurface), findsNothing);
    await tester.tap(find.byTooltip('菜单'));
    await tester.pumpAndSettle();
    expect(find.byType(OmniMenuSurface), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('tab menu preserves selected IDs and invokes actions once', (
    tester,
  ) async {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    final calls = <MethodCall>[];
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      calls.add(call);
      return null;
    });
    await tester.pumpWidget(browser());
    await tester.pumpAndSettle();
    await tester.tap(find.byIcon(Icons.layers_outlined));
    await tester.pumpAndSettle();
    expect(find.byType(BottomSheet), findsNothing);
    await tester.tap(find.text('Flutter'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 250));
    expect(calls, isEmpty);
    await tester.pumpAndSettle();
    expect(calls.single.method, 'selectTab');
    expect(calls.single.arguments, {'tabId': 7});
    await tester.tap(find.byIcon(Icons.layers_outlined));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('关闭标签页').last);
    await tester.pumpAndSettle();
    expect(calls, hasLength(2));
    expect(calls.last.method, 'closeTab');
    expect(calls.last.arguments, {'tabId': 7});
    expect(tester.takeException(), isNull);
  });

  testWidgets('renders unsupported fallback and rich prompts', (tester) async {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: ChatBrowserOverlay(
            snapshot: const ChatBrowserSessionSnapshot(
              available: true,
              workspaceId: 'conversation_1',
              activeTabId: 2,
              currentUrl: 'https://example.com',
              title: '示例页面',
              isBookmarked: true,
              canGoBack: true,
              riskChallengeDetected: true,
              recommendedNextAction:
                  'ask_user_to_complete_verification_manually',
              tabs: <AgentBrowserTab>[
                AgentBrowserTab(
                  tabId: 2,
                  url: 'https://example.com',
                  title: '示例页面',
                  isActive: true,
                ),
              ],
              externalOpenPrompt: BrowserExternalOpenPrompt(
                requestId: 'external-1',
                title: '打开应用',
                target: 'intent://demo',
              ),
              pendingDialog: BrowserDialogPrompt(
                requestId: 'dialog-1',
                type: 'prompt',
                message: '请输入内容',
                defaultValue: '默认值',
              ),
              permissionPrompt: BrowserPermissionPrompt(
                requestId: 'permission-1',
                kind: 'geolocation',
                origin: 'https://example.com',
                resources: <String>['android.permission.ACCESS_FINE_LOCATION'],
              ),
              userscriptSummary: BrowserUserscriptSummary(
                pendingInstall: BrowserUserscriptInstallPreview(
                  id: 4,
                  name: 'Demo Script',
                  description: '',
                  version: '1.0.0',
                  isUpdate: false,
                ),
              ),
            ),
            onSnapshotChanged: (_) {},
            onClose: () {},
            onDragDelta: (_) {},
            onResizeLeftDelta: (_) {},
            onResizeRightDelta: (_) {},
          ),
        ),
      ),
    );

    await tester.pumpAndSettle();

    expect(find.text('example.com'), findsOneWidget);
    expect(find.text('检测到验证码或风控验证，已暂停自动操作，请手动处理后继续'), findsOneWidget);
    expect(find.text('是否打开外部链接？'), findsOneWidget);
    expect(find.text('页面请求权限'), findsOneWidget);
    expect(find.text('页面输入'), findsOneWidget);
    expect(find.text('当前平台暂不支持浏览器工具视图'), findsOneWidget);
    expect(find.byIcon(Icons.star_rounded), findsOneWidget);
    expect(find.byIcon(Icons.arrow_back_ios_new_rounded), findsOneWidget);
  });
}
