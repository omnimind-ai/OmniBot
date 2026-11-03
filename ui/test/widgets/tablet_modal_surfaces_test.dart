import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/glass_popup.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:ui/widgets/omni_menu.dart';
import 'package:ui/widgets/settings_detail_sheet.dart';

void _viewport(WidgetTester tester, Size size) {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(tester.view.resetViewInsets);
}

void main() {
  for (final size in const [
    Size(600, 960),
    Size(768, 1024),
    Size(1024, 768),
    Size(1366, 768),
  ]) {
    testWidgets('tablet detail actions stay reachable at $size', (
      tester,
    ) async {
      _viewport(tester, size);
      tester.platformDispatcher.textScaleFactorTestValue = 1.8;
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      final controller = TextEditingController(text: 'Draft endpoint');
      addTearDown(controller.dispose);
      String? result;
      EdgeInsets? contentInsets;
      final glass = size.width >= 1024;
      await tester.pumpWidget(
        MaterialApp(
          theme: ThemeData(
            brightness: glass ? Brightness.dark : Brightness.light,
          ),
          builder: (context, child) => LiquidGlassScope(
            enabled: glass,
            quality: LiquidGlassQuality.ultra,
            child: LiquidGlassHost(enabled: glass, child: child!),
          ),
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () async {
                  result = await showSettingsDetailSheet<String>(
                    context: context,
                    isScrollControlled: true,
                    builder: (sheetContext) => SettingsDetailSheet(
                      title: 'Connection settings',
                      avoidKeyboard: true,
                      body: Builder(
                        builder: (context) {
                          contentInsets = MediaQuery.viewInsetsOf(context);
                          return Column(
                            children: [
                              TextField(controller: controller),
                              for (var i = 0; i < 20; i++)
                                Text('Configuration description $i'),
                            ],
                          );
                        },
                      ),
                      actions: [
                        TextButton(
                          style: settingsDetailSheetActionStyle(sheetContext),
                          onPressed: () =>
                              Navigator.of(sheetContext).pop(controller.text),
                          child: const Text('Save configuration'),
                        ),
                      ],
                    ),
                  );
                },
                child: const Text('Open'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();
      final surface = tester.getRect(find.byType(OmniGlassSheet));
      expect(surface.width, lessThanOrEqualTo(640));
      expect(surface.center.dx, closeTo(size.width / 2, 1));
      expect(find.text('Save configuration').hitTestable(), findsOneWidget);
      tester.view.viewInsets = const FakeViewPadding(bottom: 300);
      await tester.pumpAndSettle();
      expect(contentInsets, EdgeInsets.zero);
      expect(find.text('Save configuration').hitTestable(), findsOneWidget);
      expect(
        tester.getRect(find.text('Save configuration')).bottom,
        lessThan(size.height - 300),
      );
      expect(controller.text, 'Draft endpoint');
      expect(find.byType(LiquidGlass), glass ? findsOneWidget : findsNothing);
      expect(tester.takeException(), isNull);
      await tester.tap(find.text('Save configuration'));
      await tester.pumpAndSettle();
      expect(result, 'Draft endpoint');
    });
  }

  testWidgets('open settings form survives resize into a short split window', (
    tester,
  ) async {
    _viewport(tester, const Size(1024, 768));
    final controller = TextEditingController();
    addTearDown(controller.dispose);
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () => showSettingsDetailSheet<void>(
                context: context,
                isScrollControlled: true,
                builder: (context) => SettingsDetailSheet(
                  title: 'Edit profile',
                  avoidKeyboard: true,
                  fillAvailableHeight: true,
                  body: TextField(controller: controller),
                  actions: [
                    TextButton(
                      onPressed: () => Navigator.of(context).pop(),
                      child: const Text('Done'),
                    ),
                  ],
                ),
              ),
              child: const Text('Open'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Open'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'Keep my draft');
    tester.view.physicalSize = const Size(480, 420);
    tester.view.viewInsets = const FakeViewPadding(bottom: 180);
    await tester.pumpAndSettle();
    expect(controller.text, 'Keep my draft');
    expect(find.text('Done').hitTestable(), findsOneWidget);
    expect(tester.getRect(find.byType(OmniGlassSheet)).width, 480);
    expect(tester.getRect(find.text('Done')).bottom, lessThan(240));
    expect(tester.takeException(), isNull);
    await tester.tap(find.text('Done'));
    await tester.pumpAndSettle();
  });

  for (final requestedWidth in <double?>[null, 900]) {
    testWidgets('sheet width respects explicit override $requestedWidth', (
      tester,
    ) async {
      _viewport(tester, const Size(1366, 768));
      await tester.pumpWidget(
        MaterialApp(
          theme: ThemeData(useMaterial3: false),
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () => showOmniGlassBottomSheet<void>(
                  context: context,
                  constraints: requestedWidth == null
                      ? null
                      : BoxConstraints(maxWidth: requestedWidth),
                  builder: (_) => const SizedBox(
                    key: ValueKey('sheet-content'),
                    width: double.infinity,
                    height: 200,
                  ),
                ),
                child: const Text('Open'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();
      final rect = tester.getRect(find.byKey(const ValueKey('sheet-content')));
      expect(rect.width, requestedWidth ?? 640);
      expect(rect.center.dx, 683);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('centered popup uses tablet space beside a docked keyboard', (
    tester,
  ) async {
    _viewport(tester, const Size(1024, 768));
    await tester.pumpWidget(
      MaterialApp(
        home: MediaQuery(
          data: const MediaQueryData(
            size: Size(1024, 768),
            padding: EdgeInsets.only(left: 32, top: 24, bottom: 20),
            viewInsets: EdgeInsets.only(right: 300),
          ),
          child: const Stack(
            children: [
              GlassPopupOverlayContent(
                anchor: Rect.fromLTWH(420, 600, 40, 40),
                preferBelow: false,
                horizontalPlacement:
                    GlassPopupHorizontalPlacement.centerOnScreen,
                child: SizedBox(
                  key: ValueKey('popup'),
                  width: 360,
                  height: 280,
                ),
              ),
            ],
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    final panel = tester.getRect(find.byKey(const ValueKey('popup')));
    expect(panel.center.dx, (32 + 1024 - 300) / 2);
    expect(panel.right, lessThanOrEqualTo(1024 - 300 - 8));
    expect(panel.bottom, lessThanOrEqualTo(768 - 20 - 8));
    expect(tester.takeException(), isNull);
  });

  testWidgets('tablet menus use available width for large text', (
    tester,
  ) async {
    _viewport(tester, const Size(1024, 768));
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    String? result;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () async {
                result = await showOmniMenu<String>(
                  context: context,
                  anchor: const Rect.fromLTWH(800, 80, 48, 48),
                  items: const [
                    OmniPopupMenuItem(
                      value: 'selected',
                      child: Text('Conversation settings and preferences'),
                    ),
                  ],
                );
              },
              child: const Text('Open'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Open'));
    await tester.pumpAndSettle();
    final rect = tester.getRect(find.byType(OmniMenuSurface));
    expect(rect.width, greaterThan(280));
    expect(rect.width, lessThanOrEqualTo(420));
    expect(rect.right, lessThanOrEqualTo(1016));
    await tester.tap(find.text('Conversation settings and preferences'));
    await tester.pumpAndSettle();
    expect(result, 'selected');
    expect(tester.takeException(), isNull);
  });
}
