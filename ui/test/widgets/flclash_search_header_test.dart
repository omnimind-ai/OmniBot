import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/flclash/search_header.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';

void main() {
  for (final quality in LiquidGlassQuality.values) {
    testWidgets(
      '$quality search reverses and changes quality without a new input',
      (tester) async {
        final open = ValueNotifier(false);
        final controller = TextEditingController();
        final focus = FocusNode();
        addTearDown(open.dispose);
        addTearDown(controller.dispose);
        addTearDown(focus.dispose);
        var menuCount = 0;
        Widget app({bool enabled = true, LiquidGlassQuality? mode}) => _harness(
          open,
          controller,
          focus,
          glass: enabled,
          quality: mode ?? quality,
          onMenu: () => menuCount++,
        );
        await tester.pumpWidget(app());
        await tester.pumpAndSettle();
        final restCaptures = LiquidGlassHost.debugHeldCaptures;
        expect(restCaptures, 1);
        await tester.tap(find.byKey(const ValueKey('search')));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 100));
        final input = tester.element(find.byType(TextField));
        await tester.enterText(find.byType(TextField), 'Codex');
        await tester.pumpWidget(
          app(
            mode: quality == LiquidGlassQuality.ultra
                ? LiquidGlassQuality.standard
                : LiquidGlassQuality.ultra,
          ),
        );
        await tester.pumpAndSettle();
        expect(tester.element(find.byType(TextField)), same(input));
        expect(focus.hasFocus, isTrue);
        expect(controller.text, 'Codex');
        open.value = false;
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 70));
        open.value = true;
        await tester.pumpAndSettle();
        expect(tester.element(find.byType(TextField)), same(input));
        expect(controller.text, 'Codex');
        await tester.tap(find.byKey(const ValueKey('cancel')));
        await tester.pumpAndSettle();
        expect(find.byType(TextField), findsNothing);
        expect(focus.hasFocus, isFalse);
        expect(LiquidGlassHost.debugHeldCaptures, restCaptures);
        await tester.pumpWidget(app(enabled: false));
        await tester.pumpAndSettle();
        expect(LiquidGlassHost.debugHeldCaptures, 0);
        await tester.pumpWidget(app());
        await tester.pumpAndSettle();
        expect(LiquidGlassHost.debugHeldCaptures, restCaptures);
        expect(menuCount, 0);
        await tester.tap(find.byKey(const ValueKey('menu')));
        expect(menuCount, 1);
        await tester.pumpWidget(const SizedBox.shrink());
        expect(LiquidGlassHost.debugHeldCaptures, 0);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets(
    'app theme keeps input contents centered in the 44 dp search pill',
    (tester) async {
      final open = ValueNotifier(false);
      final controller = TextEditingController();
      final focus = FocusNode();
      addTearDown(open.dispose);
      addTearDown(controller.dispose);
      addTearDown(focus.dispose);
      await tester.pumpWidget(
        _harness(open, controller, focus, theme: AppTheme.lightTheme),
      );
      await tester.tap(find.byKey(const ValueKey('search')));
      await tester.pumpAndSettle();
      final field = find.byType(TextField);
      final prefix = find.descendant(
        of: field,
        matching: find.byIcon(LucideIcons.search),
      );
      final cancelCenter = tester
          .getCenter(find.byKey(const ValueKey('cancel')))
          .dy;
      expect(tester.getSize(field).height, 44);
      expect(tester.getCenter(field).dy, closeTo(cancelCenter, .1));
      expect(tester.getCenter(prefix).dy, closeTo(cancelCenter, .1));
      expect(
        tester.getCenter(find.byType(EditableText)).dy,
        closeTo(cancelCenter, .1),
      );
      await tester.enterText(field, 'Codex');
      await tester.pump();
      expect(tester.getCenter(prefix).dy, closeTo(cancelCenter, .1));
      expect(
        tester.getCenter(find.byType(EditableText)).dy,
        closeTo(cancelCenter, .1),
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'group search expands from its action and keeps one input through reversal',
    (tester) async {
      final open = ValueNotifier(false);
      final controller = TextEditingController();
      final focus = FocusNode();
      addTearDown(open.dispose);
      addTearDown(controller.dispose);
      addTearDown(focus.dispose);
      await tester.pumpWidget(_harness(open, controller, focus));
      final source = tester.getRect(find.byKey(const ValueKey('menu')));
      final height = tester.getSize(find.byType(FlClashSearchHeader)).height;

      await tester.tap(find.byKey(const ValueKey('search')));
      await tester.pump();
      expect(find.byType(TextField), findsOneWidget);
      final element = tester.element(find.byType(TextField));
      final earlyRect = tester.getRect(find.byType(TextField));
      expect(earlyRect.left, greaterThan(source.left - 50));
      await tester.pump(const Duration(milliseconds: 100));
      final growingRect = tester.getRect(find.byType(TextField));
      expect(growingRect.left, lessThan(earlyRect.left));
      expect(tester.getSize(find.byType(FlClashSearchHeader)).height, height);
      expect(focus.hasFocus, isTrue);
      await tester.enterText(find.byType(TextField), 'Codex');

      open.value = false;
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 70));
      open.value = true;
      await tester.pump();
      await tester.pumpAndSettle();
      expect(tester.element(find.byType(TextField)), same(element));
      expect(controller.text, 'Codex');
      expect(find.byKey(const ValueKey('menu')).hitTestable(), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'clear retains input focus; cancel returns the original group and action',
    (tester) async {
      final open = ValueNotifier(false);
      final controller = TextEditingController();
      final focus = FocusNode();
      addTearDown(open.dispose);
      addTearDown(controller.dispose);
      addTearDown(focus.dispose);
      await tester.pumpWidget(_harness(open, controller, focus));
      await tester.tap(find.byKey(const ValueKey('search')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), 'term');
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('clear')));
      await tester.pumpAndSettle();
      expect(controller.text, isEmpty);
      expect(focus.hasFocus, isTrue);
      await tester.enterText(find.byType(TextField), 'another');
      await tester.tap(find.byKey(const ValueKey('cancel')));
      await tester.pumpAndSettle();
      expect(controller.text, isEmpty);
      expect(focus.hasFocus, isFalse);
      expect(find.byType(TextField), findsNothing);
      expect(find.byKey(const ValueKey('menu')).hitTestable(), findsOneWidget);
      expect(
        find.byKey(const ValueKey('search')).hitTestable(),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'narrow RTL toolbar with large text and persistent search action fits',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final open = ValueNotifier(false);
      final controller = TextEditingController();
      final focus = FocusNode();
      addTearDown(open.dispose);
      addTearDown(controller.dispose);
      addTearDown(focus.dispose);
      await tester.pumpWidget(
        _harness(open, controller, focus, rtl: true, searchAction: true),
      );
      await tester.tap(find.byKey(const ValueKey('search')));
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('persistent')).hitTestable(),
        findsOneWidget,
      );
      expect(tester.getRect(find.byType(TextField)).width, greaterThan(100));
      expect(tester.takeException(), isNull);
    },
  );
}

Widget _harness(
  ValueNotifier<bool> open,
  TextEditingController controller,
  FocusNode focus, {
  bool rtl = false,
  bool searchAction = false,
  ThemeData? theme,
  bool glass = false,
  LiquidGlassQuality quality = LiquidGlassQuality.ultra,
  VoidCallback? onMenu,
}) => MaterialApp(
  theme: theme,
  home: MediaQuery(
    data: MediaQueryData(textScaler: TextScaler.linear(rtl ? 1.5 : 1)),
    child: Directionality(
      textDirection: rtl ? TextDirection.rtl : TextDirection.ltr,
      child: LiquidGlassScope(
        enabled: glass,
        quality: quality,
        child: LiquidGlassHost(
          enabled: glass,
          child: Scaffold(
            body: Column(
              children: [
                ValueListenableBuilder<bool>(
                  valueListenable: open,
                  builder: (_, value, _) => FlClashSearchHeader(
                    title: const Text(
                      'Agents',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    searching: value,
                    controller: controller,
                    focusNode: focus,
                    onSearch: () => open.value = true,
                    onClose: () {
                      controller.clear();
                      focus.unfocus();
                      open.value = false;
                    },
                    searchButtonKey: const ValueKey('search'),
                    closeButtonKey: const ValueKey('cancel'),
                    fieldKey: const ValueKey('field'),
                    clearButtonKey: const ValueKey('clear'),
                    actions: [
                      IconButton(
                        key: const ValueKey('menu'),
                        onPressed: onMenu ?? () {},
                        icon: const Icon(Icons.more_horiz),
                      ),
                    ],
                    searchActions: [
                      if (searchAction)
                        IconButton(
                          key: const ValueKey('persistent'),
                          onPressed: () {},
                          icon: const Icon(Icons.refresh),
                        ),
                    ],
                  ),
                ),
                const Expanded(child: SizedBox()),
              ],
            ),
          ),
        ),
      ),
    ),
  ),
);
