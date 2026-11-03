import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/chat/widgets/acp_config_button.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/chat_input_area.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/flclash/liquid_glass.dart';
import 'package:ui/widgets/omni_menu.dart';

void main() {
  final shell = find.byKey(const ValueKey('chat-minimal-composer'));
  final plus = find.byKey(
    const ValueKey('chat-input-add-or-cancel-edit-button'),
  );
  final send = find.byKey(const ValueKey('chat-input-send-or-stop-button'));

  for (final independentSendButton in [true, false]) {
    testWidgets(
      'first tap requests IME before expansion and keeps one input connection '
      '(independentSend=$independentSendButton)',
      (tester) async {
        tester.view.devicePixelRatio = 1;
        tester.view.physicalSize = const Size(390, 858);
        addTearDown(tester.view.resetDevicePixelRatio);
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetViewInsets);
        final fixture = _ComposerFixture(tester);
        var sends = 0;
        await fixture.pump(
          independentSendButton: independentSendButton,
          onSend: () => sends++,
        );
        const draft = TextEditingValue(
          text: 'Unsent draft',
          selection: TextSelection.collapsed(offset: 6),
          composing: TextRange(start: 0, end: 6),
        );
        fixture.controller.value = draft;
        await tester.pump();
        final editor = tester.state<EditableTextState>(
          find.byType(EditableText),
        );
        tester.testTextInput.log.clear();

        // Exercise the pointer path, not WidgetTester.showKeyboard(), which
        // requests focus directly and would miss a delayed first tap.
        // The idle field deliberately routes this point through its enclosing
        // tap target so the first tap retains the restored draft selection.
        await tester.tapAt(tester.getCenter(find.byType(TextField)));
        await tester.pump();
        expect(fixture.focus.hasFocus, isTrue);
        expect(tester.testTextInput.isVisible, isTrue);
        expect(tester.getSize(shell).height, 50);
        expect(fixture.controller.value, draft);
        expect(
          tester.testTextInput.setClientArgs!['inputAction'],
          independentSendButton
              ? 'TextInputAction.newline'
              : 'TextInputAction.send',
        );

        const composing = TextEditingValue(
          text: 'Unsent draft input',
          selection: TextSelection.collapsed(offset: 18),
          composing: TextRange(start: 13, end: 18),
        );
        tester.testTextInput.updateEditingValue(composing);
        for (final inset in [40.0, 120.0, 220.0, 300.0]) {
          tester.view.viewInsets = FakeViewPadding(bottom: inset);
          await tester.pump(const Duration(milliseconds: 60));
        }
        await tester.pumpAndSettle();
        expect(tester.getSize(shell).height, 97);
        expect(fixture.controller.value, composing);
        expect(
          tester.state<EditableTextState>(find.byType(EditableText)),
          same(editor),
        );
        final methods = tester.testTextInput.log.map((call) => call.method);
        expect(
          methods.where((method) => method == 'TextInput.setClient'),
          hasLength(1),
        );
        expect(methods, isNot(contains('TextInput.updateConfig')));
        expect(methods, isNot(contains('TextInput.clearClient')));

        await tester.testTextInput.receiveAction(
          independentSendButton
              ? TextInputAction.newline
              : TextInputAction.send,
        );
        expect(sends, independentSendButton ? 0 : 1);
        expect(tester.takeException(), isNull);
      },
    );
  }

  for (final dark in [false, true]) {
    for (final quality in LiquidGlassQuality.values) {
      testWidgets(
        'glass capsule follows scaled height and expands with the editor '
        '(dark=$dark, quality=$quality)',
        (tester) async {
          final fixture = _ComposerFixture(tester);
          await fixture.pump(
            glass: true,
            quality: quality,
            dark: dark,
            textScale: 2,
          );
          final pane = find.descendant(
            of: shell,
            matching: find.byType(LiquidGlass),
          );
          expect(pane, findsOneWidget);
          final initialSize = tester.getSize(shell);
          expect(initialSize.height, greaterThan(50));
          final collapsedGlass = tester.widget<LiquidGlass>(pane);
          // Wide capsules must use the shader's corner geometry, not its
          // generic radial path profile (which pinches the long edges).
          expect(collapsedGlass.clipper, isNull);
          expect(
            collapsedGlass.borderRadiusResolver!(initialSize),
            BorderRadius.circular(initialSize.height / 2),
          );
          expect(
            collapsedGlass.style,
            LiquidGlassStyle.regular(
              Theme.of(tester.element(pane)).colorScheme,
            ),
          );
          final editor = tester.state<EditableTextState>(
            find.byType(EditableText),
          );
          fixture.controller.value = const TextEditingValue(
            text: 'One\nTwo\nThree\nFour',
            selection: TextSelection.collapsed(offset: 7),
            composing: TextRange(start: 4, end: 7),
          );
          fixture.focus.requestFocus();
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 80));
          final intermediateHeight = tester.getSize(shell).height;
          expect(intermediateHeight, greaterThan(initialSize.height));
          await tester.pumpAndSettle();
          expect(tester.getSize(shell).height, greaterThan(intermediateHeight));
          final expanded = tester.widget<LiquidGlass>(pane);
          expect(
            expanded.borderRadiusResolver!(tester.getSize(shell)),
            BorderRadius.circular(20),
          );
          expect(tester.widget<TextField>(find.byType(TextField)).maxLines, 6);
          expect(
            tester.state<EditableTextState>(find.byType(EditableText)),
            same(editor),
          );
          expect(fixture.controller.selection.baseOffset, 7);
          expect(
            fixture.controller.value.composing,
            const TextRange(start: 4, end: 7),
          );
          expect(tester.takeException(), isNull);
        },
      );
    }
  }

  testWidgets(
    'attachments remain outside the idle capsule and keep send/remove',
    (tester) async {
      final fixture = _ComposerFixture(tester);
      var sends = 0;
      String? removed;
      await fixture.pump(
        glass: true,
        attachments: const [
          ChatInputAttachment(
            id: 'file-a',
            name: 'notes.txt',
            path: '/tmp/notes.txt',
            isImage: false,
          ),
        ],
        onRemoveAttachment: (id) => removed = id,
        onSend: () => sends++,
      );
      expect(tester.getSize(shell).height, 50);
      expect(
        tester.getRect(find.text('notes.txt')).bottom,
        lessThan(tester.getRect(shell).top),
      );
      await tester.tap(send);
      expect(sends, 1);
      await tester.tap(find.byIcon(Icons.close_rounded));
      expect(removed, 'file-a');
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('glass toggle preserves a focused multiline draft and editor', (
    tester,
  ) async {
    final fixture = _ComposerFixture(tester);
    await fixture.pump(glass: true);
    fixture.focus.requestFocus();
    fixture.controller.text = 'One\nTwo\nThree';
    await tester.pumpAndSettle();
    final editor = tester.state<EditableTextState>(find.byType(EditableText));
    final height = tester.getSize(shell).height;
    await fixture.pump(glass: false);
    expect(
      find.descendant(of: shell, matching: find.byType(LiquidGlass)),
      findsNothing,
    );
    expect(tester.getSize(shell).height, height);
    expect(fixture.focus.hasFocus, isTrue);
    expect(fixture.controller.text, 'One\nTwo\nThree');
    expect(
      tester.state<EditableTextState>(find.byType(EditableText)),
      same(editor),
    );
    await fixture.pump(glass: true);
    expect(tester.getSize(shell).height, height);
    expect(
      tester.state<EditableTextState>(find.byType(EditableText)),
      same(editor),
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'command composer uses steady glass and preserves multiline space',
    (tester) async {
      final fixture = _ComposerFixture(tester);
      await fixture.pump(glass: true, minimalComposer: false);
      final area = find.byType(ChatInputArea);
      final emptyHeight = tester.getSize(area).height;
      final flowBorder = find.byWidgetPredicate(
        (widget) =>
            widget is CustomPaint &&
            widget.painter.runtimeType.toString() ==
                '_ComposerFlowBorderPainter',
      );
      expect(flowBorder, findsNothing);
      expect(tester.binding.hasScheduledFrame, isFalse);
      fixture.controller.text = 'One\nTwo\nThree';
      await tester.pumpAndSettle();
      expect(tester.getSize(area).height, greaterThan(emptyHeight));
      expect(tester.widget<TextField>(find.byType(TextField)).maxLines, 3);
      expect(tester.binding.hasScheduledFrame, isFalse);
      await fixture.pump(
        glass: false,
        minimalComposer: false,
        disableAnimations: true,
      );
      expect(flowBorder, findsOneWidget);
      expect(fixture.controller.text, 'One\nTwo\nThree');
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'collapsed model is hidden without disposing its configuration owner',
    (tester) async {
      final fixture = _ComposerFixture(tester);
      final configKey = GlobalKey<AcpConfigButtonState>();
      var loads = 0;
      await fixture.pump(
        runtimeConfigButton: AcpConfigButton(
          key: configKey,
          modelLabel: 'provider/gpt-5.4',
          isRunning: false,
          load: () async {
            loads++;
            return {};
          },
          write: (_, _, _) async => {},
        ),
      );
      final owner = configKey.currentState;
      final modelRegion = find.byKey(
        const ValueKey('chat-input-model-visibility'),
      );
      expect(tester.getSize(modelRegion).width, 0);
      expect(
        find.byKey(const ValueKey('chat-acp-config-button')).hitTestable(),
        findsNothing,
      );
      expect(loads, 0);
      fixture.focus.requestFocus();
      await tester.pumpAndSettle();
      expect(tester.getSize(modelRegion).width, greaterThan(90));
      expect(
        find.byKey(const ValueKey('chat-acp-config-button')).hitTestable(),
        findsOneWidget,
      );
      expect(find.text('gpt-5.4'), findsOneWidget);
      expect(configKey.currentState, same(owner));
      fixture.focus.unfocus();
      await tester.pumpAndSettle();
      expect(tester.getSize(modelRegion).width, 0);
      expect(configKey.currentState, same(owner));
      fixture.focus.requestFocus();
      await tester.pumpAndSettle();
      expect(find.text('gpt-5.4'), findsOneWidget);
      expect(configKey.currentState, same(owner));
      expect(loads, 0);
    },
  );

  testWidgets('focus animates two rows without replacing the draft editor', (
    tester,
  ) async {
    final fixture = _ComposerFixture(tester);
    await fixture.pump();
    final editor = tester.state<EditableTextState>(find.byType(EditableText));
    expect(tester.getSize(shell).height, 50);
    fixture.controller.value = const TextEditingValue(
      text: 'Unsent draft',
      selection: TextSelection.collapsed(offset: 6),
      composing: TextRange(start: 0, end: 6),
    );
    fixture.focus.requestFocus();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 80));
    expect(tester.getSize(shell).height, greaterThan(50));
    expect(tester.getSize(shell).height, lessThan(97));
    expect(
      tester.state<EditableTextState>(find.byType(EditableText)),
      same(editor),
    );
    expect(fixture.controller.selection.baseOffset, 6);
    expect(
      fixture.controller.value.composing,
      const TextRange(start: 0, end: 6),
    );
    await tester.pumpAndSettle();
    expect(tester.getSize(shell).height, 97);
    fixture.controller.text = 'One\nTwo\nThree\nFour';
    await tester.pumpAndSettle();
    expect(tester.getSize(shell).height, greaterThan(140));
    fixture.focus.unfocus();
    await tester.pumpAndSettle();
    expect(tester.getSize(shell).height, 50);
    expect(fixture.controller.text, 'One\nTwo\nThree\nFour');
    expect(
      tester.state<EditableTextState>(find.byType(EditableText)),
      same(editor),
    );
  });

  testWidgets('temporary model override follows the expanded model controls', (
    tester,
  ) async {
    final fixture = _ComposerFixture(tester);
    var clearCount = 0;
    await fixture.pump(
      selectedModelOverrideId: 'provider/temporary-model',
      onClearSelectedModelOverride: () => clearCount++,
    );
    final region = find.byKey(
      const ValueKey('chat-input-model-override-visibility'),
    );
    expect(tester.getSize(region).height, 0);
    expect(tester.getSize(shell).height, 50);
    fixture.focus.requestFocus();
    await tester.pumpAndSettle();
    expect(tester.getSize(region).height, greaterThan(20));
    expect(
      find.text('@provider/temporary-model').hitTestable(),
      findsOneWidget,
    );
    fixture.focus.unfocus();
    await tester.pumpAndSettle();
    expect(tester.getSize(region).height, 0);
    expect(tester.getSize(shell).height, 50);
    fixture.focus.requestFocus();
    await tester.pumpAndSettle();
    expect(
      find.text('@provider/temporary-model').hitTestable(),
      findsOneWidget,
    );
    expect(clearCount, 0);
  });

  testWidgets('focus animation reverses and reduced motion is immediate', (
    tester,
  ) async {
    final fixture = _ComposerFixture(tester);
    await fixture.pump();
    fixture.focus.requestFocus();
    await tester.pump();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 60));
    final intermediate = tester.getSize(shell).height;
    fixture.focus.unfocus();
    await tester.pump();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 40));
    expect(tester.getSize(shell).height, lessThan(intermediate));
    await tester.pumpAndSettle();
    expect(tester.getSize(shell).height, 50);
    final editor = tester.state<EditableTextState>(find.byType(EditableText));
    await fixture.pump(disableAnimations: true);
    expect(
      tester.state<EditableTextState>(find.byType(EditableText)),
      same(editor),
    );
    fixture.focus.requestFocus();
    await tester.pump();
    await tester
        .pump(); // Flush focus notification without advancing animation time.
    expect(tester.getSize(shell).height, 97);
    fixture.focus.unfocus();
    await tester.pump();
    await tester.pump();
    expect(tester.getSize(shell).height, 50);
  });

  testWidgets('multiline draft height interpolates on focus and blur', (
    tester,
  ) async {
    final fixture = _ComposerFixture(tester);
    await fixture.pump();
    fixture.controller.text = 'One\nTwo\nThree\nFour';
    await tester.pumpAndSettle();
    final editor = tester.state<EditableTextState>(find.byType(EditableText));
    expect(tester.getSize(shell).height, 50);
    fixture.focus.requestFocus();
    await tester.pump();
    await tester.pump();
    expect(tester.getSize(shell).height, 50);
    await tester.pump(const Duration(milliseconds: 80));
    final midpoint = tester.getSize(shell).height;
    expect(midpoint, greaterThan(50));
    await tester.pumpAndSettle();
    final expandedHeight = tester.getSize(shell).height;
    expect(midpoint, lessThan(expandedHeight));
    expect(expandedHeight, greaterThan(140));
    fixture.focus.unfocus();
    await tester.pump();
    await tester.pump();
    expect(tester.getSize(shell).height, expandedHeight);
    await tester.pump(const Duration(milliseconds: 80));
    expect(tester.getSize(shell).height, lessThan(expandedHeight));
    expect(tester.getSize(shell).height, greaterThan(50));
    await tester.pumpAndSettle();
    expect(tester.getSize(shell).height, 50);
    expect(
      tester.state<EditableTextState>(find.byType(EditableText)),
      same(editor),
    );
  });

  testWidgets(
    'plus menu preserves focus and delegates photos, files and permissions',
    (tester) async {
      final fixture = _ComposerFixture(tester);
      var photos = 0;
      var files = 0;
      AgentPermissionMode? mode;
      await fixture.pump(
        onPickImages: () async => photos++,
        onPickAttachment: () async => files++,
        onPermission: (value) => mode = value,
      );
      fixture.controller.text = 'Keep this';
      fixture.focus.requestFocus();
      await tester.pumpAndSettle();
      final editor = tester.state<EditableTextState>(find.byType(EditableText));
      await tester.tap(plus);
      await tester.pumpAndSettle();
      expect(fixture.focus.hasFocus, isTrue);
      expect(tester.getSize(shell).height, 97);
      await tester.tap(find.byKey(const ValueKey('chat-input-add-images')));
      await tester.pumpAndSettle();
      expect(photos, 1);
      await tester.tap(plus);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('chat-input-add-files')));
      await tester.pumpAndSettle();
      expect(files, 1);
      await tester.tap(plus);
      await tester.pumpAndSettle();
      await tester.tap(
        find.byKey(const ValueKey('chat-input-add-permissions')),
      );
      await tester.pumpAndSettle();
      expect(fixture.focus.hasFocus, isTrue);
      expect(tester.getSize(shell).height, 97);
      expect(
        find.byKey(
          const ValueKey('chat-input-agent-permission-option-autoReview'),
        ),
        findsNothing,
      );
      await tester.tap(
        find.byKey(
          const ValueKey('chat-input-agent-permission-option-readOnly'),
        ),
      );
      await tester.pumpAndSettle();
      expect(mode, AgentPermissionMode.readOnly);
      expect(fixture.controller.text, 'Keep this');
      expect(
        tester.state<EditableTextState>(find.byType(EditableText)),
        same(editor),
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('native picker keeps expanded layout until the picker returns', (
    tester,
  ) async {
    final fixture = _ComposerFixture(tester);
    final picker = Completer<void>();
    await fixture.pump(onPickImages: () => picker.future);
    fixture.focus.requestFocus();
    await tester.pumpAndSettle();
    await tester.tap(plus);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('chat-input-add-images')));
    await tester.pumpAndSettle();
    fixture.focus.unfocus();
    await tester.pumpAndSettle();
    expect(tester.getSize(shell).height, 97);
    picker.complete();
    await tester.pumpAndSettle();
    expect(tester.getSize(shell).height, 50);
  });

  for (final keyboardOpen in [false, true]) {
    testWidgets(
      'attachment and permission menus align with the composer outline '
      '(keyboard=$keyboardOpen)',
      (tester) async {
        const viewport = Size(390, 858);
        final keyboard = keyboardOpen ? 300.0 : 0.0;
        tester.view.devicePixelRatio = 1;
        tester.view.physicalSize = viewport;
        tester.view.viewInsets = FakeViewPadding(bottom: keyboard);
        addTearDown(tester.view.resetDevicePixelRatio);
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetViewInsets);

        final fixture = _ComposerFixture(tester);
        AgentPermissionMode? selectedMode;
        await fixture.pump(
          keyboard: keyboard,
          bottomPadding: 16,
          onPickImages: () async {},
          onPickAttachment: () async {},
          onPermission: (mode) => selectedMode = mode,
        );
        const draft = TextEditingValue(
          text: 'Keep this unsent draft',
          selection: TextSelection.collapsed(offset: 9),
        );
        fixture.controller.value = draft;
        if (keyboardOpen) fixture.focus.requestFocus();
        await tester.pumpAndSettle();
        final composer = tester.getRect(shell);
        final editor = tester.state<EditableTextState>(
          find.byType(EditableText),
        );
        expect(composer.bottom, viewport.height - keyboard - 16);

        void expectAlignedMenu() {
          final panel = tester.getRect(find.byType(OmniMenuSurface));
          expect(panel.left, closeTo(composer.left, 0.01));
          expect(panel.bottom, closeTo(composer.bottom, 0.01));
          expect(tester.getRect(shell), composer);
          expect(fixture.focus.hasFocus, keyboardOpen);
          expect(fixture.controller.value, draft);
          expect(
            tester.state<EditableTextState>(find.byType(EditableText)),
            same(editor),
          );
        }

        await tester.tap(plus);
        await tester.pumpAndSettle();
        expectAlignedMenu();

        await tester.tap(
          find.byKey(const ValueKey('chat-input-add-permissions')),
        );
        await tester.pumpAndSettle();
        expectAlignedMenu();

        await tester.tap(
          find.byKey(
            const ValueKey('chat-input-agent-permission-option-readOnly'),
          ),
        );
        await tester.pumpAndSettle();
        expect(selectedMode, AgentPermissionMode.readOnly);
        expect(find.byType(OmniMenuSurface), findsNothing);
        expect(tester.getRect(shell), composer);
        expect(fixture.focus.hasFocus, keyboardOpen);
        expect(fixture.controller.value, draft);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets(
    'model popup and shared selector handoff keep the layout stable',
    (tester) async {
      final fixture = _ComposerFixture(tester);
      final selection = Completer<void>();
      final visibility = <bool>[];
      var modelSelections = 0;
      var configLoads = 0;
      await fixture.pump(
        runtimeConfigButton: AcpConfigButton(
          modelLabel: 'provider/gpt-5.4',
          isRunning: false,
          onVisibilityChanged: (value) {
            visibility.add(value);
            fixture.key.currentState!.setAuxiliaryPopupVisible(value);
          },
          load: () async {
            configLoads++;
            return {
              'sessionId': 'session-a',
              'configOptions': [
                {
                  'id': 'model',
                  'category': 'model',
                  'type': 'select',
                  'currentValue': 'gpt-5.4',
                },
              ],
            };
          },
          write: (_, _, _) async => {},
          configureModel: (_) {
            modelSelections++;
            return selection.future;
          },
        ),
      );
      fixture.focus.requestFocus();
      await tester.pumpAndSettle();
      expect(find.text('gpt-5.4'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('chat-acp-config-button')));
      await tester.pumpAndSettle();
      expect(visibility, [true]);
      await tester.tap(find.byKey(const ValueKey('acp-config-model')));
      await tester.pumpAndSettle();
      expect(modelSelections, 1);
      await tester.tap(find.byKey(const ValueKey('chat-acp-config-button')));
      await tester.pumpAndSettle();
      expect(configLoads, 1);
      expect(find.byType(AcpConfigPanel), findsNothing);
      fixture.focus.unfocus();
      await tester.pumpAndSettle();
      expect(visibility, [true]);
      expect(tester.getSize(shell).height, 97);
      selection.complete();
      await tester.pumpAndSettle();
      expect(visibility, [true, false]);
      expect(tester.getSize(shell).height, 50);
    },
  );

  testWidgets(
    'external payload sends, running stops, and edit cancel stays available',
    (tester) async {
      final fixture = _ComposerFixture(tester);
      var sends = 0;
      var stops = 0;
      var exits = 0;
      await fixture.pump(hasExternalPayload: true, onSend: () => sends++);
      await tester.tap(send);
      expect(sends, 1);
      await fixture.pump(isProcessing: true, onCancel: () => stops++);
      await tester.tap(send);
      expect(stops, 1);
      fixture.controller.text = 'Edit draft';
      await fixture.pump(isEditing: true, onCancelEditing: () => exits++);
      await tester.tap(plus);
      expect(exits, 1);
      expect(fixture.controller.text, 'Edit draft');
      expect(find.byKey(const ValueKey('chat-input-add-files')), findsNothing);
    },
  );

  for (final dark in [false, true]) {
    testWidgets('narrow composer supports large text (dark=$dark)', (
      tester,
    ) async {
      final fixture = _ComposerFixture(tester);
      await fixture.pump(
        dark: dark,
        textScale: 2,
        width: 272,
        runtimeConfigButton: AcpConfigButton(
          modelLabel: 'provider/a-very-long-actual-model-name',
          isRunning: false,
          load: () async => {},
          write: (_, _, _) async => {},
        ),
      );
      fixture.focus.requestFocus();
      await tester.pumpAndSettle();
      fixture.controller.text = 'A long draft that wraps across several lines';
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.byType(EditableText), findsOneWidget);
      expect(
        find.byKey(const ValueKey('chat-acp-config-button')),
        findsOneWidget,
      );
    });
  }
}

class _ComposerFixture {
  _ComposerFixture(this.tester) {
    addTearDown(controller.dispose);
    addTearDown(focus.dispose);
  }

  final WidgetTester tester;
  final controller = TextEditingController();
  final focus = FocusNode();
  final key = GlobalKey<ChatInputAreaState>();

  Future<void> pump({
    bool glass = false,
    LiquidGlassQuality quality = LiquidGlassQuality.standard,
    bool minimalComposer = true,
    bool independentSendButton = true,
    bool disableAnimations = false,
    bool dark = false,
    double textScale = 1,
    double width = 342,
    double keyboard = 0,
    double bottomPadding = 0,
    Future<void> Function()? onPickImages,
    Future<void> Function()? onPickAttachment,
    ValueChanged<AgentPermissionMode>? onPermission,
    Widget? runtimeConfigButton,
    String? selectedModelOverrideId,
    VoidCallback? onClearSelectedModelOverride,
    bool hasExternalPayload = false,
    bool isProcessing = false,
    bool isEditing = false,
    VoidCallback? onSend,
    VoidCallback? onCancel,
    VoidCallback? onCancelEditing,
    List<ChatInputAttachment> attachments = const [],
    ValueChanged<String>? onRemoveAttachment,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: dark ? AppTheme.darkTheme : AppTheme.lightTheme,
        builder: (context, child) => LiquidGlassScope(
          enabled: glass,
          quality: quality,
          child: LiquidGlassHost(enabled: glass, child: child!),
        ),
        home: MediaQuery(
          data: MediaQueryData(
            size: const Size(390, 858),
            viewInsets: EdgeInsets.only(bottom: keyboard),
            disableAnimations: disableAnimations,
            textScaler: TextScaler.linear(textScale),
          ),
          child: Scaffold(
            body: Padding(
              padding: EdgeInsets.only(bottom: bottomPadding),
              child: Align(
                alignment: Alignment.bottomCenter,
                child: SizedBox(
                  width: width,
                  child: ChatInputArea(
                    key: key,
                    controller: controller,
                    focusNode: focus,
                    minimalComposer: minimalComposer,
                    useIndependentSendButton: independentSendButton,
                    useLargeComposerStyle: !minimalComposer,
                    isProcessing: isProcessing,
                    hasExternalSendPayload: hasExternalPayload,
                    isEditingUserMessage: isEditing,
                    onCancelUserMessageEditing: onCancelEditing,
                    onSendMessage: onSend ?? () {},
                    onCancelTask: onCancel ?? () {},
                    onPickImages: onPickImages,
                    onPickAttachment: onPickAttachment,
                    attachments: attachments,
                    onRemoveAttachment: onRemoveAttachment,
                    agentPermissionMode: onPermission == null
                        ? null
                        : AgentPermissionMode.defaultMode,
                    agentPermissionModes: const [
                      AgentPermissionMode.readOnly,
                      AgentPermissionMode.defaultMode,
                      AgentPermissionMode.fullAccess,
                    ],
                    onAgentPermissionModeChanged: onPermission,
                    runtimeConfigButton: runtimeConfigButton,
                    selectedModelOverrideId: selectedModelOverrideId,
                    onClearSelectedModelOverride: onClearSelectedModelOverride,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }
}
