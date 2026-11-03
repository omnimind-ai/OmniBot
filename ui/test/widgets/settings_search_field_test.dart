import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/flclash/elastic_press.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/settings_ui.dart';

Widget _app(Widget field, {bool glass = false, bool dark = false}) =>
    MaterialApp(
      locale: const Locale('en'),
      theme: dark ? AppTheme.darkTheme : AppTheme.lightTheme,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      builder: (context, child) => LiquidGlassScope(
        enabled: glass,
        child: LiquidGlassHost(enabled: glass, child: child!),
      ),
      home: Scaffold(
        body: Center(child: SizedBox(width: 320, child: field)),
      ),
    );

void main() {
  testWidgets('bar search keeps FlClash geometry in light and dark glass', (
    tester,
  ) async {
    final controller = TextEditingController(text: 'query');
    addTearDown(controller.dispose);
    for (final dark in [false, true]) {
      await tester.pumpWidget(
        _app(
          OmniSettingsSearchField(controller: controller, hintText: 'Search'),
          glass: true,
          dark: dark,
        ),
      );
      await tester.pumpAndSettle();
      final field = tester.getRect(find.byType(TextField));
      final glyph = tester.getRect(find.byIcon(LucideIcons.search));
      final clear = tester.getRect(find.byIcon(LucideIcons.circleX));
      expect(field.height, 44);
      expect(glyph.width, 20);
      expect(glyph.left - field.left, 12);
      expect(clear.width, 24);
      expect(field.right - clear.center.dx, 24.5);
      final pane = tester.widget<LiquidGlass>(find.byType(LiquidGlass));
      expect(pane.clipper, isNull);
      expect(pane.borderRadius, isNull);
      expect(
        find.descendant(
          of: find.byType(LiquidGlass),
          matching: find.byType(ClipRRect),
        ),
        findsOneWidget,
      );
      expect(find.byType(FlClashElasticPress), findsNothing);
      expect(tester.takeException(), isNull);
    }
  });

  testWidgets('touch focus stays clean while keyboard focus gets a ring', (
    tester,
  ) async {
    final manager = FocusManager.instance;
    final strategy = manager.highlightStrategy;
    manager.highlightStrategy = FocusHighlightStrategy.automatic;
    addTearDown(() => manager.highlightStrategy = strategy);
    final controller = TextEditingController(text: 'query');
    addTearDown(controller.dispose);
    await tester.pumpWidget(
      _app(
        OmniSettingsSearchField(controller: controller, hintText: 'Search'),
        glass: true,
      ),
    );
    final field = find.byType(TextField);
    BorderSide focusRing() =>
        tester.widget<TextField>(field).decoration!.focusedBorder!.borderSide;
    await tester.tap(field);
    await tester.pump();
    expect(tester.widget<TextField>(field).focusNode!.hasFocus, isTrue);
    expect(focusRing(), BorderSide.none);
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
    await tester.pump();
    expect(focusRing().style, BorderStyle.solid);
    expect(focusRing().width, 1);
    await tester.tap(field);
    await tester.pump();
    expect(focusRing(), BorderSide.none);
    expect(controller.text, 'query');
  });

  testWidgets('owned focus clears whitespace and keeps the input ready', (
    tester,
  ) async {
    final controller = TextEditingController(text: '   ');
    final changes = <String>[];
    addTearDown(controller.dispose);
    await tester.pumpWidget(
      _app(
        OmniSettingsSearchField(
          controller: controller,
          hintText: 'Search',
          onChanged: changes.add,
        ),
      ),
    );
    final field = find.byType(TextField);
    await tester.tap(field);
    await tester.pump();
    final focus = tester.widget<TextField>(field).focusNode!;
    expect(focus.hasFocus, isTrue);
    await tester.tap(find.byTooltip('Clear search'));
    await tester.pump();
    expect(controller.text, isEmpty);
    expect(changes, ['']);
    expect(focus.hasFocus, isTrue);
    expect(tester.testTextInput.isVisible, isTrue);
    expect(find.byTooltip('Clear search'), findsNothing);
  });

  testWidgets(
    'external focus and controller survive replacement and disposal',
    (tester) async {
      final first = TextEditingController(text: 'first');
      final second = TextEditingController();
      final focus = FocusNode();
      addTearDown(first.dispose);
      addTearDown(second.dispose);
      addTearDown(focus.dispose);
      Widget field(TextEditingController controller, FocusNode? node) => _app(
        OmniSettingsSearchField(
          controller: controller,
          focusNode: node,
          hintText: 'Search',
        ),
      );
      await tester.pumpWidget(field(first, focus));
      focus.requestFocus();
      await tester.pump();
      expect(focus.hasFocus, isTrue);
      await tester.pumpWidget(field(second, null));
      first.text = 'detached';
      second.text = 'replacement';
      await tester.pump();
      expect(find.text('replacement'), findsOneWidget);
      await tester.tap(find.byType(TextField));
      await tester.pump();
      expect(
        tester.widget<TextField>(find.byType(TextField)).focusNode!.hasFocus,
        isTrue,
      );
      await tester.pumpWidget(const SizedBox());
      first.clear();
      second.clear();
      focus.requestFocus();
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('search action submits exact input and keyboard clear works', (
    tester,
  ) async {
    final controller = TextEditingController();
    final submitted = <String>[];
    addTearDown(controller.dispose);
    await tester.pumpWidget(
      _app(
        OmniSettingsSearchField(
          controller: controller,
          hintText: 'Search',
          onSubmitted: submitted.add,
        ),
      ),
    );
    await tester.enterText(find.byType(TextField), '  skill name  ');
    await tester.testTextInput.receiveAction(TextInputAction.search);
    await tester.pump();
    expect(submitted, ['  skill name  ']);
    await tester.tap(find.byType(TextField));
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.tab);
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pump();
    expect(controller.text, isEmpty);
    expect(
      tester.widget<TextField>(find.byType(TextField)).focusNode!.hasFocus,
      isTrue,
    );
  });

  testWidgets('long press keeps text selection and field geometry stable', (
    tester,
  ) async {
    final controller = TextEditingController(text: 'select this query');
    addTearDown(controller.dispose);
    await tester.pumpWidget(
      _app(
        OmniSettingsSearchField(controller: controller, hintText: 'Search'),
        glass: true,
      ),
    );
    final bounds = tester.getRect(find.byType(TextField));
    await tester.longPress(find.text('select this query'));
    await tester.pumpAndSettle();
    expect(controller.selection.isCollapsed, isFalse);
    expect(tester.getRect(find.byType(TextField)), bounds);
    expect(tester.takeException(), isNull);
  });
}
