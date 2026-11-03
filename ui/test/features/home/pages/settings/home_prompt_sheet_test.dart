import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/settings/home_setting_page.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/omni_glass.dart';

void main() {
  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
  });

  for (final glass in [false, true]) {
    testWidgets(
      'prompt editor covers safe area and scrolls above IME: $glass',
      (tester) async {
        tester.view.physicalSize = const Size(390, 844);
        tester.view.devicePixelRatio = 1;
        tester.view.viewPadding = const FakeViewPadding(bottom: 24);
        tester.view.padding = const FakeViewPadding(bottom: 24);
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        addTearDown(tester.view.resetViewPadding);
        addTearDown(tester.view.resetPadding);
        addTearDown(tester.view.resetViewInsets);
        await tester.pumpWidget(
          MaterialApp(
            theme: AppTheme.lightTheme,
            builder: (context, child) => LiquidGlassScope(
              enabled: glass,
              quality: LiquidGlassQuality.ultra,
              child: LiquidGlassHost(enabled: glass, child: child!),
            ),
            home: const HomeSettingPage(),
          ),
        );
        await tester.pumpAndSettle();
        await tester.scrollUntilVisible(find.text('新增快捷指令'), 300);
        await tester.tap(find.text('新增快捷指令'));
        await tester.pumpAndSettle();
        final sheet = find.byType(OmniGlassSheet);
        final scroll = find.descendant(
          of: sheet,
          matching: find.byType(SingleChildScrollView),
        );
        expect(tester.getRect(sheet).bottom, 844);
        expect(tester.getRect(scroll).bottom, 820);
        expect(tester.getRect(find.text('保存')).bottom, lessThan(820));

        tester.view.viewInsets = const FakeViewPadding(bottom: 360);
        tester.view.padding = FakeViewPadding.zero;
        await tester.pumpAndSettle();
        expect(tester.getRect(sheet).bottom, 484);
        expect(tester.takeException(), isNull);
        await tester.enterText(find.byType(TextField).first, '测试');
        await tester.enterText(find.byType(TextField).last, '验证底部按钮可滚动到达');
        await tester.ensureVisible(find.text('取消'));
        await tester.pumpAndSettle();
        expect(find.text('取消').hitTestable(), findsOneWidget);
        expect(tester.getRect(find.text('保存')).bottom, lessThan(484));
        await tester.tap(find.text('取消'));
        await tester.pumpAndSettle();
        expect(sheet, findsNothing);
        expect(tester.takeException(), isNull);
      },
    );
  }
}
