import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/permission_guide/permission_guide_detail_page.dart';
import 'package:ui/features/home/pages/permission_guide/permission_guide_page.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  testWidgets('permission guide keeps its brand when the window narrows', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1366, 1024);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        home: const PermissionGuidePage(initialBrand: 'xiaomi'),
      ),
    );
    await tester.pumpAndSettle();
    expect(tester.getSize(find.byType(ListView)).width, 840);
    await tester.tap(find.widgetWithText(ChoiceChip, '华为'));
    await tester.pumpAndSettle();
    tester.view.physicalSize = const Size(600, 900);
    await tester.pumpAndSettle();
    expect(
      tester.widget<ChoiceChip>(find.widgetWithText(ChoiceChip, '华为')).selected,
      isTrue,
    );
    expect(tester.getSize(find.byType(ListView)).width, 600);
    expect(tester.takeException(), isNull);
  });

  testWidgets('permission steps and bottom action share the tablet column', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1366, 1024);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        home: const PermissionGuideDetailPage(
          type: 'overlay',
          initialBrand: 'xiaomi',
        ),
      ),
    );
    await tester.pumpAndSettle();
    final list = tester.getRect(find.byType(ListView));
    final action = tester.getRect(find.byType(FilledButton));
    expect(list.width, 840);
    expect(action.width, 808);
    expect(action.center.dx, list.center.dx);
    expect(action.bottom, 1008);
    expect(action.top, greaterThanOrEqualTo(list.bottom));
    expect(tester.takeException(), isNull);
  });
}
