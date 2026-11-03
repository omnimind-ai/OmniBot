import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/omni_root_toolbar.dart';

void main() {
  for (final title in ['首页', 'Agents', '轨迹', '设置']) {
    testWidgets('$title shares the root title metrics and safe area', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(390, 800);
      tester.view.devicePixelRatio = 1;
      tester.view.padding = const FakeViewPadding(top: 24, left: 12, right: 8);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPadding);
      final controller = TextEditingController();
      final focus = FocusNode();
      addTearDown(controller.dispose);
      addTearDown(focus.dispose);
      final searchable = title == '首页' || title == 'Agents';
      final toolbar = OmniRootToolbar(
        title: title,
        search: searchable
            ? OmniRootToolbarSearch(
                searching: false,
                onSearch: () {},
                onClose: () {},
                controller: controller,
                focusNode: focus,
              )
            : null,
        actions: searchable
            ? [IconButton(onPressed: () {}, icon: const Icon(LucideIcons.plus))]
            : const [],
      );
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(appBar: toolbar),
        ),
      );
      await tester.pumpAndSettle();
      final label = find.byKey(OmniRootToolbar.titleKey);
      final style = tester.widget<Text>(label).style!;
      expect(style.fontSize, 28);
      expect(style.fontWeight, FontWeight.w600);
      expect(style.height, 1.2);
      expect(tester.getRect(label).left, 34);
      expect(tester.getCenter(label).dy, closeTo(60, .01));
      expect(toolbar.preferredSize.height, 72);
      expect(tester.getSize(find.byType(OmniRootToolbar)).height, 96);
      expect(find.byIcon(LucideIcons.chevronLeft), findsNothing);
      expect(find.byType(TextField), findsNothing);
      expect(
        find.byIcon(LucideIcons.search),
        searchable ? findsOneWidget : findsNothing,
      );
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('inline root toolbar consumes an existing SafeArea only once', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(390, 800);
    tester.view.padding = const FakeViewPadding(top: 24, left: 12, right: 8);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetPadding);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SafeArea(
            child: Column(
              children: const [OmniRootToolbar(title: '首页', primary: false)],
            ),
          ),
        ),
      ),
    );
    expect(tester.getRect(find.byKey(OmniRootToolbar.titleKey)).left, 34);
    expect(
      tester.getCenter(find.byKey(OmniRootToolbar.titleKey)).dy,
      closeTo(60, .01),
    );
    expect(tester.getSize(find.byType(OmniRootToolbar)).height, 72);
  });

  testWidgets('root search retains the FlClash group and query callbacks', (
    tester,
  ) async {
    final open = ValueNotifier(false);
    final controller = TextEditingController();
    final focus = FocusNode();
    addTearDown(open.dispose);
    addTearDown(controller.dispose);
    addTearDown(focus.dispose);
    var menuCalls = 0;
    String? query;
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.darkTheme,
        home: ValueListenableBuilder<bool>(
          valueListenable: open,
          builder: (context, searching, _) => Scaffold(
            appBar: OmniRootToolbar(
              title: 'Agents',
              actions: [
                IconButton(
                  key: const Key('menu'),
                  onPressed: () => menuCalls++,
                  icon: const Icon(LucideIcons.plus),
                ),
              ],
              search: OmniRootToolbarSearch(
                searching: searching,
                onSearch: () => open.value = true,
                onClose: () {
                  controller.clear();
                  focus.unfocus();
                  open.value = false;
                },
                controller: controller,
                focusNode: focus,
                onChanged: (value) => query = value,
                searchButtonKey: const Key('search'),
                closeButtonKey: const Key('close'),
              ),
            ),
          ),
        ),
      ),
    );
    final search = tester.getRect(find.byKey(const Key('search')));
    final menu = tester.getRect(find.byKey(const Key('menu')));
    expect(search.size, const Size(44, 44));
    expect(menu.size, search.size);
    expect((search.center.dx - menu.center.dx).abs(), 44);
    await tester.tap(find.byKey(const Key('search')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'Codex');
    expect(query, 'Codex');
    expect(focus.hasFocus, isTrue);
    expect(find.byKey(const Key('menu')).hitTestable(), findsNothing);
    await tester.tap(find.byKey(const Key('close')));
    await tester.pumpAndSettle();
    expect(controller.text, isEmpty);
    expect(focus.hasFocus, isFalse);
    await tester.tap(find.byKey(const Key('menu')));
    expect(menuCalls, 1);
    expect(tester.takeException(), isNull);
  });
}
