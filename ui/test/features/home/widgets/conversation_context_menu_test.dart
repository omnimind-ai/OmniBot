import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/widgets/conversation_context_menu.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/flclash/popup.dart';
import 'package:ui/widgets/omni_menu.dart';

// Shared only by the companion glass tests. It uses the production route and
// menu rows, and commits a selected action after the route is fully removed.
class ContextMenuTestHarness {
  final navigator = GlobalKey<NavigatorState>();
  int backgroundTaps = 0;
  int actionTaps = 0;
  int dismissals = 0;
  int completions = 0;
  String? appliedAction;
  late ConversationContextMenuRoute<String> route;
  late BuildContext menuContext;
  Future<void>? completion;

  static const labels = [
    'Pin',
    'Rename',
    'Copy',
    'Select',
    'Archive',
    'Delete',
  ];

  Widget app({
    bool glass = false,
    LiquidGlassQuality quality = LiquidGlassQuality.standard,
    bool dark = false,
    double textScale = 1,
    EdgeInsets padding = EdgeInsets.zero,
    EdgeInsets viewInsets = EdgeInsets.zero,
    bool reducedMotion = false,
    bool pushableUnderlay = false,
  }) {
    final page = Scaffold(
      key: const ValueKey('context-test-page'),
      body: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () => backgroundTaps++,
        child: const SizedBox.expand(
          child: Align(
            alignment: Alignment.topLeft,
            child: Opacity(
              key: ValueKey('context-test-source'),
              opacity: 1,
              child: Text('Original conversation'),
            ),
          ),
        ),
      ),
    );
    return MaterialApp(
      navigatorKey: navigator,
      theme: dark ? ThemeData.dark() : ThemeData.light(),
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(
          textScaler: TextScaler.linear(textScale),
          padding: padding,
          viewInsets: viewInsets,
          disableAnimations: reducedMotion,
        ),
        child: LiquidGlassScope(
          enabled: glass,
          quality: quality,
          child: LiquidGlassHost(enabled: glass, child: child!),
        ),
      ),
      home: pushableUnderlay ? const Scaffold(body: Text('Lower route')) : page,
      initialRoute: pushableUnderlay ? '/conversation' : '/',
      routes: {'/conversation': (_) => page},
    );
  }

  void open({
    Rect anchor = const Rect.fromLTWH(16, 100, 358, 74),
    Offset origin = const Offset(80, 130),
    List<String> actionLabels = labels,
    bool reducedMotion = false,
    Widget? preview,
  }) {
    final openedRoute = route = ConversationContextMenuRoute<String>(
      barrierLabel: 'Dismiss',
      reduceMotion: reducedMotion,
      pageBuilder: (context, animation, _) {
        menuContext = context;
        return ConversationContextMenu(
          anchor: anchor,
          preview: preview,
          origin: origin,
          animation: animation,
          onDismiss: () {
            dismissals++;
            Navigator.of(context).pop();
          },
          actions: [
            for (var index = 0; index < actionLabels.length; index++)
              OmniMenuItem(
                key: ValueKey('context-action-$index'),
                label: actionLabels[index],
                leading: const Icon(Icons.edit_outlined),
                destructive: actionLabels[index] == 'Delete',
                onTap: () {
                  if (ModalRoute.of(context)?.isCurrent != true) return;
                  actionTaps++;
                  Navigator.of(context).pop(actionLabels[index]);
                },
              ),
          ],
        );
      },
    );
    completion = () async {
      final selected = await navigator.currentState!.push(openedRoute);
      await openedRoute.completed;
      appliedAction = selected;
      completions++;
    }();
  }

  void dismiss() => Navigator.of(menuContext).pop();
}

Finder get contextMenuPanel =>
    find.byKey(const ValueKey('conversation-context-actions'));
Finder get contextMenuScroll =>
    find.byKey(const ValueKey('conversation-context-scroll'));
Finder get contextMenuSource =>
    find.byKey(const ValueKey('context-test-source'));

Future<void> setContextMenuScreen(WidgetTester tester, Size size) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
}

void main() {
  const previewContent = SizedBox(
    height: 74,
    child: Center(child: Text('Focused conversation')),
  );
  final focusedPreview = find.byKey(
    const ValueKey('conversation-context-preview'),
  );

  for (final (size, anchor) in const [
    (Size(1069, 668), Rect.fromLTWH(160, 100, 840, 74)),
    (Size(768, 1024), Rect.fromLTWH(24, 100, 720, 74)),
  ]) {
    testWidgets('wide preview retains its row width on $size', (tester) async {
      await setContextMenuScreen(tester, size);
      final harness = ContextMenuTestHarness();
      await tester.pumpWidget(harness.app());
      harness.open(anchor: anchor, preview: previewContent);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 40));
      expect(
        tester.getRect(focusedPreview).width,
        inInclusiveRange(anchor.width * .97, anchor.width),
        reason: 'The lifted row keeps its width throughout the press motion.',
      );
      await tester.pumpAndSettle();
      final preview = tester.getRect(focusedPreview);
      final menu = tester.getRect(contextMenuPanel);
      expect(preview.width, closeTo(anchor.width, .01));
      expect(preview.left, anchor.left);
      expect(preview.height, 74);
      expect(menu.width, inInclusiveRange(160, 280));
      expect(menu.left, anchor.left);
      expect(menu.top, 180);
      harness.dismiss();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      expect(
        tester.getRect(focusedPreview).width,
        inInclusiveRange(anchor.width * .97, anchor.width),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('wide preview clamps separately from the action menu', (
    tester,
  ) async {
    await setContextMenuScreen(tester, const Size(1069, 668));
    final harness = ContextMenuTestHarness();
    await tester.pumpWidget(harness.app());
    // A captured row may extend beyond the window after its bounds change.
    harness.open(
      anchor: const Rect.fromLTWH(800, 100, 600, 74),
      preview: previewContent,
    );
    await tester.pumpAndSettle();
    final preview = tester.getRect(focusedPreview);
    final menu = tester.getRect(contextMenuPanel);
    expect(preview.width, 600);
    expect(preview.left, 453);
    expect(preview.right, 1053);
    // Keep the independent menu's existing alignment and compact width.
    expect(menu.left, 685);
    expect(menu.width, inInclusiveRange(160, 280));
    harness.dismiss();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  testWidgets('an open wide preview adapts to a narrower window', (
    tester,
  ) async {
    await setContextMenuScreen(tester, const Size(1069, 668));
    final harness = ContextMenuTestHarness();
    await tester.pumpWidget(harness.app());
    harness.open(
      anchor: const Rect.fromLTWH(160, 100, 840, 74),
      preview: previewContent,
    );
    await tester.pumpAndSettle();
    final menuOwner = tester.element(find.byType(ConversationContextMenu));
    for (final size in const [Size(668, 1069), Size(540, 900)]) {
      tester.view.physicalSize = size;
      await tester.pumpAndSettle();
      expect(
        tester.element(find.byType(ConversationContextMenu)),
        same(menuOwner),
      );
      final preview = tester.getRect(focusedPreview);
      final menu = tester.getRect(contextMenuPanel);
      expect(preview.width, size.width - 32);
      expect(preview.left, 16);
      expect(preview.right, size.width - 16);
      expect(menu.left, greaterThanOrEqualTo(24));
      expect(menu.right, lessThanOrEqualTo(size.width - 24));
      expect(menu.width, inInclusiveRange(160, 280));
      expect(tester.takeException(), isNull);
    }
    harness.dismiss();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  for (final (anchorTop, menuTop) in [(100.0, 180.0), (700.0, 524.0)]) {
    testWidgets(
      'independent menu retains former action position at y=$anchorTop',
      (tester) async {
        await setContextMenuScreen(tester, const Size(390, 844));
        final harness = ContextMenuTestHarness();
        await tester.pumpWidget(harness.app());
        harness.open(anchor: Rect.fromLTWH(16, anchorTop, 358, 74));
        await tester.pumpAndSettle();
        final rect = tester.getRect(contextMenuPanel);
        expect(rect.left, 24);
        expect(rect.top, menuTop);
        expect(rect.height, 16 + 6 * 44);
        expect(rect.width, inInclusiveRange(160, 280));
        expect(
          find.byKey(const ValueKey('conversation-context-preview')),
          findsNothing,
        );
        expect(find.text('Original conversation'), findsOneWidget);
        expect(tester.widget<Opacity>(contextMenuSource).opacity, 1);
        expect(harness.route.barrierColor, isNull);
        final dismissSurface = find.descendant(
          of: find.byKey(const ValueKey('conversation-context-dismiss')),
          matching: find.byType(ColoredBox),
        );
        expect(
          tester.widget<ColoredBox>(dismissSurface).color,
          Colors.transparent,
        );
        final motion = tester.widget<FlClashPopupTransition>(
          find.byType(FlClashPopupTransition),
        );
        expect(motion.anchor, const Offset(80, 130) & Size.zero);
        expect(motion.source, isNull);
        expect(motion.sourceImage, isNull);
        expect(motion.sourceColor, Colors.transparent);
        harness.dismiss();
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets('menu width follows its labels within upstream 160..280 bounds', (
    tester,
  ) async {
    await setContextMenuScreen(tester, const Size(390, 844));
    final harness = ContextMenuTestHarness();
    await tester.pumpWidget(harness.app());
    for (final (label, width) in [
      ('Pin', 160.0),
      ('A very long conversation action that must be ellipsized', 280.0),
    ]) {
      harness.open(actionLabels: [label]);
      await tester.pumpAndSettle();
      expect(tester.getSize(contextMenuPanel).width, width);
      expect(tester.getSize(find.byType(OmniMenuItem)).height, 44);
      harness.dismiss();
      await tester.pumpAndSettle();
    }
    expect(tester.takeException(), isNull);
  });

  testWidgets('indented child preserves its original alignment lane', (
    tester,
  ) async {
    await setContextMenuScreen(tester, const Size(390, 844));
    final harness = ContextMenuTestHarness();
    await tester.pumpWidget(harness.app());
    harness.open(anchor: const Rect.fromLTWH(54, 100, 320, 74));
    await tester.pumpAndSettle();
    expect(tester.getRect(contextMenuPanel).topLeft, const Offset(46, 180));
    harness.dismiss();
    await tester.pumpAndSettle();
  });

  for (final openMilliseconds in [60, 225, 451]) {
    testWidgets(
      'opening $openMilliseconds ms still closes continuously over 200 ms',
      (tester) async {
        final harness = ContextMenuTestHarness();
        await tester.pumpWidget(harness.app());
        harness.open();
        await tester.pump();
        await tester.pump(Duration(milliseconds: openMilliseconds));
        expect(
          harness.route.transitionDuration,
          const Duration(milliseconds: 450),
        );
        expect(
          harness.route.reverseTransitionDuration,
          const Duration(milliseconds: 200),
        );
        final from = harness.route.animation!.value;
        expect(from, closeTo((openMilliseconds / 450).clamp(0, 1), 0.0001));
        harness.dismiss();
        expect(harness.route.animation!.value, from);
        await tester.pump();
        expect(harness.route.animation!.value, from);
        await tester.pump(const Duration(milliseconds: 100));
        expect(harness.route.animation!.value, closeTo(from * 0.5, 0.0001));
        expect(harness.completions, 0);
        expect(tester.widget<Opacity>(contextMenuSource).opacity, 1);
        await tester.pump(const Duration(milliseconds: 99));
        expect(harness.route.animation!.value, greaterThan(0));
        expect(find.byType(ConversationContextMenu), findsOneWidget);
        await tester.pump(const Duration(milliseconds: 2));
        await tester.pumpAndSettle();
        await harness.completion;
        expect(harness.completions, 1);
        expect(find.byType(ConversationContextMenu), findsNothing);
        expect(tester.takeException(), isNull);
      },
    );
  }

  for (final closeWithAction in [false, true]) {
    testWidgets(
      'repeat close cannot reach a lower route: action=$closeWithAction',
      (tester) async {
        final harness = ContextMenuTestHarness();
        await tester.pumpWidget(harness.app(pushableUnderlay: true));
        await tester.pumpAndSettle();
        final underlay = tester.state(
          find.byKey(const ValueKey('context-test-page')),
        );
        harness.open();
        await tester.pumpAndSettle();
        if (closeWithAction) {
          await tester.tap(find.text('Delete'));
        } else {
          await tester.tapAt(const Offset(4, 200));
        }
        // Deliberately do not pump before the second sequence.
        await tester.tapAt(const Offset(4, 200));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 100));
        await tester.tapAt(const Offset(4, 200));
        expect(harness.appliedAction, isNull);
        expect(harness.completions, 0);
        await tester.pumpAndSettle();
        await harness.completion;
        expect(harness.appliedAction, closeWithAction ? 'Delete' : null);
        expect(harness.actionTaps, closeWithAction ? 1 : 0);
        expect(harness.dismissals, closeWithAction ? 0 : 1);
        expect(harness.completions, 1);
        expect(harness.backgroundTaps, 0);
        expect(
          tester.state(find.byKey(const ValueKey('context-test-page'))),
          same(underlay),
        );
        expect(harness.navigator.currentState!.canPop(), isTrue);
        harness.navigator.currentState!.pop();
        await tester.pumpAndSettle();
        expect(find.text('Lower route'), findsOneWidget);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets('all exterior gaps dismiss without activating the visible row', (
    tester,
  ) async {
    await setContextMenuScreen(tester, const Size(390, 844));
    final harness = ContextMenuTestHarness();
    await tester.pumpWidget(harness.app());
    for (final point in <Offset Function(Rect)>[
      (menu) => menu.centerLeft - const Offset(4, 0),
      (menu) => menu.centerRight + const Offset(4, 0),
      (menu) => menu.topCenter - const Offset(0, 4),
      (menu) => menu.bottomCenter + const Offset(0, 4),
      (_) => const Offset(80, 130),
    ]) {
      harness.open();
      await tester.pumpAndSettle();
      await tester.tapAt(point(tester.getRect(contextMenuPanel)));
      await tester.pumpAndSettle();
      expect(find.byType(ConversationContextMenu), findsNothing);
      expect(harness.backgroundTaps, 0);
    }
    expect(harness.dismissals, 5);
  });

  testWidgets(
    'surface padding consumes taps while menu rows retain their action',
    (tester) async {
      final harness = ContextMenuTestHarness();
      await tester.pumpWidget(harness.app());
      harness.open();
      await tester.pumpAndSettle();
      final menu = tester.getRect(contextMenuPanel);
      await tester.tapAt(menu.topCenter + const Offset(0, 4));
      await tester.pumpAndSettle();
      expect(find.byType(ConversationContextMenu), findsOneWidget);
      expect(harness.dismissals, 0);
      await tester.tap(find.text('Rename'));
      await tester.pumpAndSettle();
      await harness.completion;
      expect(harness.appliedAction, 'Rename');
    },
  );

  testWidgets('keyboard and large text keep every action safely scrollable', (
    tester,
  ) async {
    await setContextMenuScreen(tester, const Size(320, 480));
    final harness = ContextMenuTestHarness();
    await tester.pumpWidget(
      harness.app(
        textScale: 1.8,
        padding: const EdgeInsets.only(top: 24, bottom: 12),
        viewInsets: const EdgeInsets.only(bottom: 180),
      ),
    );
    harness.open(anchor: const Rect.fromLTWH(16, 390, 288, 74));
    await tester.pumpAndSettle();
    final bounds = tester.getRect(contextMenuPanel);
    expect(bounds.top, greaterThanOrEqualTo(36));
    expect(bounds.bottom, lessThanOrEqualTo(288));
    expect(bounds.left, greaterThanOrEqualTo(24));
    expect(bounds.right, lessThanOrEqualTo(296));
    await tester.drag(find.text('Pin'), const Offset(0, -480));
    await tester.pumpAndSettle();
    expect(tester.getRect(find.text('Delete')).bottom, lessThanOrEqualTo(288));
    await tester.tap(find.text('Delete'));
    await tester.pumpAndSettle();
    expect(harness.appliedAction, 'Delete');
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'long menus stop at seven and a half rows and retain the last action',
    (tester) async {
      final harness = ContextMenuTestHarness();
      await tester.pumpWidget(harness.app());
      harness.open(actionLabels: List.generate(20, (index) => 'Action $index'));
      await tester.pumpAndSettle();
      expect(tester.getSize(contextMenuPanel).height, 16 + 7.5 * 44);
      await tester.drag(find.text('Action 0'), const Offset(0, -1200));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Action 19'));
      await tester.pumpAndSettle();
      expect(harness.appliedAction, 'Action 19');
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'zero duration and a pop before the first frame leave the row visible',
    (tester) async {
      final harness = ContextMenuTestHarness();
      await tester.pumpWidget(harness.app(reducedMotion: true));
      harness.open(reducedMotion: true);
      expect(harness.route.transitionDuration, Duration.zero);
      expect(harness.route.reverseTransitionDuration, Duration.zero);
      harness.navigator.currentState!.pop();
      await tester.pumpAndSettle();
      await harness.completion;
      expect(tester.widget<Opacity>(contextMenuSource).opacity, 1);
      expect(find.byType(ConversationContextMenu), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
}
