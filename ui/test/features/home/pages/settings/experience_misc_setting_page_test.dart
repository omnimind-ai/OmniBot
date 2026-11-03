import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/widgets/settings_ui.dart';
import 'package:ui/features/home/pages/settings/experience_misc_setting_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const assistChannel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistChannel, (call) async {
          if (call.method == 'getConversations') {
            return <dynamic>[];
          }
          return 'SUCCESS';
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistChannel, null);
  });

  testWidgets(
    'misc settings retain home entry and remove the retired back toggle',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = const Size(800, 1600);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);

      await tester.pumpWidget(
        ProviderScope(
          child: MaterialApp(
            locale: const Locale('zh'),
            theme: AppTheme.lightTheme,
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
            home: const ExperienceMiscSettingPage(),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('首页设置'), findsOneWidget);
      expect(find.byIcon(LucideIcons.house), findsOneWidget);

      await tester.scrollUntilVisible(
        find.text('振动反馈'),
        400,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.text('预测性返回手势'), findsNothing);
      expect(find.byIcon(Icons.swipe_left_rounded), findsNothing);
    },
  );

  testWidgets('recent seven-day conversations switch defaults to disabled', (
    tester,
  ) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(800, 1600);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);

    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          locale: const Locale('zh'),
          theme: AppTheme.lightTheme,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: const ExperienceMiscSettingPage(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final title = find.text('仅显示近7天会话');
    expect(title, findsOneWidget);
    final tile = find.ancestor(of: title, matching: find.byType(InkWell));
    final toggle = find.descendant(
      of: tile,
      matching: find.byType(OmniSettingsSwitch),
    );
    expect(tester.widget<OmniSettingsSwitch>(toggle).value, isFalse);

    await tester.tap(toggle);
    await tester.pumpAndSettle();

    expect(
      StorageService.getBool(StorageService.kRecentConversationsOnlyEnabledKey),
      isTrue,
    );
    expect(tester.widget<OmniSettingsSwitch>(toggle).value, isTrue);
  });
}
