import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/chat_page_models.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_browser_overlay.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';

void main() {
  tearDown(LegacyTextLocalizer.clearResolvedLocale);

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
                recipient: 'example.com',
                capabilities: <String>['location'],
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
    expect(find.text('页面输入'), findsOneWidget);
    expect(find.text('当前平台暂不支持浏览器工具视图'), findsOneWidget);
    expect(find.text('网站请求敏感权限'), findsOneWidget);
    expect(find.text('接收方：example.com'), findsOneWidget);
    expect(find.text('用途：允许该网站使用位置。'), findsOneWidget);
    expect(
      find.text(
        '数据由该网站处理，不是 OmniBot AI。授权仅对本次请求有效，不会保存为“始终允许”。',
      ),
      findsOneWidget,
    );
    expect(find.textContaining('android.permission'), findsNothing);
    expect(find.text('仅此次允许'), findsOneWidget);
    expect(find.byIcon(Icons.star_rounded), findsOneWidget);
    expect(find.byIcon(Icons.arrow_back_ios_new_rounded), findsOneWidget);
  });

  testWidgets(
    'renders an English per-request website disclosure without raw permissions',
    (tester) async {
      LegacyTextLocalizer.setResolvedLocale(const Locale('en'));

      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('en'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: Scaffold(
            body: ChatBrowserOverlay(
              snapshot: const ChatBrowserSessionSnapshot(
                available: true,
                workspaceId: 'conversation_2',
                activeTabId: 4,
                currentUrl: 'https://media.example',
                title: 'Media example',
                permissionPrompt: BrowserPermissionPrompt(
                  requestId: 'permission-2',
                  kind: 'web',
                  origin: 'https://media.example',
                  recipient: 'media.example',
                  capabilities: <String>['camera', 'microphone'],
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

      expect(find.text('Website requests sensitive access'), findsOneWidget);
      expect(find.text('Recipient: media.example'), findsOneWidget);
      expect(
        find.text('Purpose: let this website use camera, microphone.'),
        findsOneWidget,
      );
      expect(
        find.text(
          'The website processes this data, not OmniBot AI. Access applies only to this request and is never saved as “always allow”.',
        ),
        findsOneWidget,
      );
      expect(find.textContaining('android.webkit.resource'), findsNothing);
      expect(find.text('Allow once'), findsOneWidget);
    },
  );
}
