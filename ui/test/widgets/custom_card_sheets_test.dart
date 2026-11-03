import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart';
import 'package:ui/services/agent_avatar_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/agent_avatar.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/omni_glass.dart';

Future<BuildContext> _pumpHost(WidgetTester tester, Size size) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  late BuildContext host;
  await tester.pumpWidget(
    MaterialApp(
      theme: AppTheme.lightTheme,
      builder: (context, child) => LiquidGlassScope(
        enabled: true,
        quality: LiquidGlassQuality.ultra,
        child: LiquidGlassHost(enabled: true, child: child!),
      ),
      home: Builder(
        builder: (context) {
          host = context;
          return const Scaffold(body: SizedBox.expand());
        },
      ),
    ),
  );
  return host;
}

void _expectBottomPane(WidgetTester tester, double bottom) {
  expect(find.byType(Dialog), findsNothing);
  // The sheet is one surface; its copy/close controls can use glass too.
  expect(find.byType(OmniGlassPanel), findsOneWidget);
  final pane = tester.getRect(find.byType(OmniGlassPanel));
  expect(tester.getRect(find.byType(LiquidGlass).first), pane);
  expect(pane.bottom, closeTo(bottom, 1));
  expect(pane.height, greaterThanOrEqualTo(bottom * 0.5));
  expect(tester.takeException(), isNull);
}

void main() {
  testWidgets('card keyboard traversal stays within its modal route', (
    tester,
  ) async {
    final first = FocusNode(debugLabel: 'card-first');
    final last = FocusNode(debugLabel: 'card-last');
    final background = FocusNode(debugLabel: 'background');
    addTearDown(first.dispose);
    addTearDown(last.dispose);
    addTearDown(background.dispose);
    late BuildContext host;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) {
            host = context;
            return Scaffold(
              body: TextButton(
                focusNode: background,
                onPressed: () {},
                child: const Text('Background'),
              ),
            );
          },
        ),
      ),
    );
    showOmniGlassCardSheet<void>(
      context: host,
      builder: (context) => OmniGlassAlertDialog(
        title: const Text('Focus test'),
        actions: [
          TextButton(
            focusNode: first,
            onPressed: () {},
            child: const Text('First'),
          ),
          TextButton(
            focusNode: last,
            onPressed: () {},
            child: const Text('Last'),
          ),
        ],
      ),
    );
    await tester.pumpAndSettle();
    last.requestFocus();
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.tab);
    await tester.pump();
    expect(first.hasFocus, isTrue);
    expect(background.hasFocus, isFalse);
    Navigator.of(host).pop();
    await tester.pumpAndSettle();
  });

  testWidgets('input sheet keeps its action visible above the keyboard', (
    tester,
  ) async {
    final host = await _pumpHost(tester, const Size(360, 720));
    tester.view.viewInsets = const FakeViewPadding(bottom: 300);
    addTearDown(tester.view.resetViewInsets);
    final result = AppDialog.input(
      host,
      title: 'Rename item',
      content: List.filled(20, 'Description for the item.').join('\n'),
      confirmText: 'Save',
    );
    await tester.pumpAndSettle();

    _expectBottomPane(tester, 420);
    expect(tester.getRect(find.text('Save')).bottom, lessThan(420));
    await tester.ensureVisible(find.byType(TextField));
    await tester.enterText(find.byType(TextField), 'Updated item');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(await result, 'Updated item');
    expect(tester.takeException(), isNull);
  });

  testWidgets('long option sheet scrolls while preserving the selection', (
    tester,
  ) async {
    final host = await _pumpHost(tester, const Size(360, 460));
    final result = AppDialog.select(
      host,
      title: 'Choose an item',
      options: List.generate(40, (index) => 'Choice $index'),
    );
    await tester.pumpAndSettle();

    _expectBottomPane(tester, 460);
    await tester.ensureVisible(find.text('Choice 39'));
    await tester.tap(find.text('Choice 39'));
    await tester.tap(find.byType(FilledButton));
    await tester.pumpAndSettle();
    expect(await result, 39);
    expect(tester.takeException(), isNull);
  });

  testWidgets('loading sheet ignores barrier taps and downward drags', (
    tester,
  ) async {
    final host = await _pumpHost(tester, const Size(390, 844));
    AppDialog.loading(host, title: 'Working');
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));

    _expectBottomPane(tester, 844);
    await tester.tapAt(const Offset(8, 8));
    await tester.dragFrom(
      tester.getTopLeft(find.byType(OmniGlassPanel)) + const Offset(100, 24),
      const Offset(0, 400),
    );
    await tester.pump(const Duration(seconds: 1));
    expect(find.text('Working'), findsOneWidget);
    AppDialog.dismissLoading(host);
    await tester.pumpAndSettle();
    expect(find.text('Working'), findsNothing);
  });

  testWidgets(
    'avatar sheet scrolls to and returns a preset on a short screen',
    (tester) async {
      SharedPreferences.setMockInitialValues(<String, Object>{});
      AgentAvatarService.resetForTesting();
      final host = await _pumpHost(tester, const Size(390, 420));
      final result = showAgentAvatarPicker(host);
      await tester.pumpAndSettle();

      _expectBottomPane(tester, 420);
      final lastAvatar = find.byWidgetPredicate(
        (widget) => widget is AgentAvatarCircle && widget.avatarIndex == 5,
      );
      await tester.ensureVisible(lastAvatar);
      await tester.tap(lastAvatar);
      await tester.pumpAndSettle();
      expect((await result)?.presetIndex, 5);
      expect(AgentAvatarService.avatarStateNotifier.value.presetIndex, 5);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('tool details use a single bottom pane and retain full output', (
    tester,
  ) async {
    final host = await _pumpHost(tester, const Size(390, 844));
    final result = showAgentToolDetailDialog(
      host,
      cardData: <String, dynamic>{
        'toolName': 'terminal_execute',
        'toolType': 'terminal',
        'status': 'success',
        'terminalOutput': List.generate(
          60,
          (index) => 'line-$index',
        ).join('\n'),
      },
    );
    await tester.pumpAndSettle();

    _expectBottomPane(tester, 844);
    final blocks = tester.widgetList<SelectableText>(
      find.byType(SelectableText),
    );
    expect(blocks, hasLength(2));
    expect(blocks.first.data, contains('terminal_execute'));
    expect(blocks.last.data, contains('line-59'));
    await tester.tapAt(const Offset(8, 8));
    await tester.pumpAndSettle();
    await result;
    expect(find.byType(SelectableText), findsNothing);
  });
}
