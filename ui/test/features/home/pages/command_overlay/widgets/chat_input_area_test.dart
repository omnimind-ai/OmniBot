import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/chat_input_area.dart';
import 'package:ui/widgets/glass_popup.dart';
import 'package:ui/widgets/provider_vendor_icon.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const speechChannel = MethodChannel('cn.com.omnimind.bot/SpeechRecognition');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() {
    messenger.setMockMethodCallHandler(speechChannel, (call) async {
      if (call.method == 'initialize') {
        return true;
      }
      return null;
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(speechChannel, null);
  });

  testWidgets('does not render context usage ring when ratio is absent', (
    tester,
  ) async {
    await tester.pumpWidget(_buildTestApp(contextUsageRatio: null));
    await tester.pump();

    expect(
      find.byWidgetPredicate(
        (widget) =>
            widget is CustomPaint &&
            widget.painter.runtimeType.toString() == '_ContextUsageRingPainter',
      ),
      findsNothing,
    );
  });

  testWidgets('renders context usage ring when ratio is provided', (
    tester,
  ) async {
    await tester.pumpWidget(_buildTestApp(contextUsageRatio: 0.72));
    await tester.pump();

    expect(
      find.byWidgetPredicate(
        (widget) =>
            widget is CustomPaint &&
            widget.painter.runtimeType.toString() == '_ContextUsageRingPainter',
      ),
      findsOneWidget,
    );
  });

  testWidgets('long pressing context usage ring triggers callback', (
    tester,
  ) async {
    var longPressed = false;
    await tester.pumpWidget(
      _buildTestApp(
        contextUsageRatio: 0.72,
        onLongPressContextUsageRing: () {
          longPressed = true;
        },
      ),
    );
    await tester.pump();

    await tester.longPress(
      find.byWidgetPredicate(
        (widget) =>
            widget is CustomPaint &&
            widget.painter.runtimeType.toString() == '_ContextUsageRingPainter',
      ),
    );
    await tester.pump(const Duration(milliseconds: 300));

    expect(longPressed, isTrue);
  });

  testWidgets('tapping slash trigger button invokes callback', (tester) async {
    var tapped = false;
    await tester.pumpWidget(
      _buildTestApp(
        contextUsageRatio: null,
        onTriggerSlashCommand: () {
          tapped = true;
        },
      ),
    );
    await tester.pump();

    await tester.tap(
      find.byKey(const ValueKey('chat-input-trigger-slash-button')),
    );
    await tester.pump(const Duration(milliseconds: 300));

    expect(tapped, isTrue);
  });

  testWidgets('agent permission selector opens menu and selects mode', (
    tester,
  ) async {
    AgentPermissionMode? selected;
    await tester.pumpWidget(
      _buildTestApp(
        contextUsageRatio: null,
        useLargeComposerStyle: true,
        agentPermissionMode: AgentPermissionMode.fullAccess,
        onAgentPermissionModeChanged: (mode) {
          selected = mode;
        },
      ),
    );
    await tester.pump();

    final permissionButton = find.byKey(
      const ValueKey('chat-input-agent-permission-button'),
    );
    expect(
      find.descendant(of: permissionButton, matching: find.byType(SvgPicture)),
      findsOneWidget,
    );

    await tester.tap(permissionButton);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(
      find.byKey(const ValueKey('chat-input-agent-permission-option-readOnly')),
      findsOneWidget,
    );
    expect(
      find.byKey(
        const ValueKey('chat-input-agent-permission-option-defaultMode'),
      ),
      findsOneWidget,
    );
    expect(
      find.byKey(
        const ValueKey('chat-input-agent-permission-option-autoReview'),
      ),
      findsOneWidget,
    );
    expect(
      find.byKey(
        const ValueKey('chat-input-agent-permission-option-fullAccess'),
      ),
      findsOneWidget,
    );
    for (final mode in AgentPermissionMode.values) {
      expect(
        find.descendant(
          of: find.byKey(
            ValueKey('chat-input-agent-permission-option-${mode.name}'),
          ),
          matching: find.byType(SvgPicture),
        ),
        findsOneWidget,
      );
    }
    final selectedPermissionRow = find.byKey(
      const ValueKey('chat-input-agent-permission-option-fullAccess'),
    );
    final selectedPermissionContainer = tester.widget<AnimatedContainer>(
      find.descendant(
        of: selectedPermissionRow,
        matching: find.byType(AnimatedContainer),
      ),
    );
    final selectedPermissionDecoration =
        selectedPermissionContainer.decoration! as BoxDecoration;
    expect(
      selectedPermissionContainer.padding,
      const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
    );
    expect(
      selectedPermissionDecoration.color,
      const Color(0xFF2C7FEB).withValues(alpha: 0.12),
    );
    expect(selectedPermissionDecoration.border, isNull);
    expect(
      tester
          .widget<Text>(
            find.descendant(
              of: selectedPermissionRow,
              matching: find.byType(Text),
            ),
          )
          .style
          ?.fontWeight,
      FontWeight.w500,
    );
    expect(
      find.descendant(
        of: selectedPermissionRow,
        matching: find.byIcon(Icons.check_rounded),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: find.byKey(
          const ValueKey('chat-input-agent-permission-option-defaultMode'),
        ),
        matching: find.byIcon(Icons.check_rounded),
      ),
      findsNothing,
    );

    await tester.tap(
      find.byKey(
        const ValueKey('chat-input-agent-permission-option-autoReview'),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(selected, AgentPermissionMode.autoReview);
  });

  testWidgets('local ACP permission selector hides unsupported auto review', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildTestApp(
        contextUsageRatio: null,
        useLargeComposerStyle: true,
        agentPermissionMode: AgentPermissionMode.defaultMode,
        agentPermissionModes: const <AgentPermissionMode>[
          AgentPermissionMode.readOnly,
          AgentPermissionMode.defaultMode,
          AgentPermissionMode.fullAccess,
        ],
        onAgentPermissionModeChanged: (_) {},
      ),
    );
    await tester.pump();

    await tester.tap(
      find.byKey(const ValueKey('chat-input-agent-permission-button')),
    );
    await tester.pump();

    expect(
      find.byKey(const ValueKey('chat-input-agent-permission-option-readOnly')),
      findsOneWidget,
    );
    expect(
      find.byKey(
        const ValueKey('chat-input-agent-permission-option-defaultMode'),
      ),
      findsOneWidget,
    );
    expect(
      find.byKey(
        const ValueKey('chat-input-agent-permission-option-fullAccess'),
      ),
      findsOneWidget,
    );
    expect(
      find.byKey(
        const ValueKey('chat-input-agent-permission-option-autoReview'),
      ),
      findsNothing,
    );
  });

  testWidgets('agent run settings selector omits agent switching', (
    tester,
  ) async {
    String? selectedModel;
    String? selectedEffort;
    await tester.pumpWidget(
      _buildTestApp(
        contextUsageRatio: null,
        useLargeComposerStyle: true,
        agentRunSettings: const AgentRunSettings(
          agentName: 'Active Agent',
          modelId: 'gpt-5-agent',
          reasoningEffort: 'high',
          modelOptions: <String>['gpt-5-agent', 'gpt-5.1-agent'],
          reasoningEffortOptions: <String>['low', 'high', 'xhigh'],
        ),
        onAgentRunSettingsChanged: ({modelId, reasoningEffort}) {
          selectedModel = modelId;
          selectedEffort = reasoningEffort;
        },
      ),
    );
    await tester.pump();

    final settingsButton = find.byKey(
      const ValueKey('chat-input-agent-run-settings-button'),
    );
    expect(settingsButton, findsOneWidget);
    expect(
      find.descendant(
        of: settingsButton,
        matching: find.byKey(
          const ValueKey('chat-input-agent-run-settings-package-icon'),
        ),
      ),
      findsOneWidget,
    );
    expect(
      find.descendant(
        of: settingsButton,
        matching: find.byType(RotationTransition),
      ),
      findsNothing,
    );
    expect(
      find.descendant(of: settingsButton, matching: find.text('gpt-5-agent')),
      findsNothing,
    );

    await tester.tap(settingsButton);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    final openIcon = tester.widget<Icon>(
      find.byKey(
        const ValueKey('chat-input-agent-run-settings-package-open-icon'),
      ),
    );
    expect(openIcon.icon, LucideIcons.packageOpen);

    expect(
      find.byKey(const ValueKey('conversation-model-selector-search')),
      findsNothing,
    );
    expect(find.text('Agent 模式'), findsNothing);
    expect(
      find.byKey(
        const ValueKey(
          'chat-input-agent-run-settings-option-agent-custom-agent',
        ),
      ),
      findsNothing,
    );
    expect(
      find.byKey(const ValueKey('chat-input-agent-run-settings-group-model')),
      findsOneWidget,
    );
    expect(find.byIcon(LucideIcons.sparkles), findsOneWidget);
    expect(
      find.byKey(
        const ValueKey('chat-input-agent-run-settings-group-reasoning'),
      ),
      findsOneWidget,
    );
    expect(find.byIcon(LucideIcons.brain), findsOneWidget);
    expect(
      find.byKey(
        const ValueKey(
          'chat-input-agent-run-settings-option-model-gpt-5.1-agent',
        ),
      ),
      findsNothing,
    );

    await tester.tap(
      find.byKey(const ValueKey('chat-input-agent-run-settings-group-model')),
    );
    await tester.pump(const Duration(milliseconds: 200));
    final backRow = find.byKey(
      const ValueKey('chat-input-agent-run-settings-back'),
    );
    final backRowRect = tester.getRect(backRow);
    await tester.tapAt(Offset(backRowRect.right - 8, backRowRect.center.dy));
    await tester.pump(const Duration(milliseconds: 200));
    expect(
      find.byKey(const ValueKey('agent-run-settings-overview')),
      findsOneWidget,
    );

    await tester.tap(
      find.byKey(const ValueKey('chat-input-agent-run-settings-group-model')),
    );
    await tester.pump(const Duration(milliseconds: 200));
    await tester.tap(
      find.byKey(
        const ValueKey(
          'chat-input-agent-run-settings-option-model-gpt-5.1-agent',
        ),
      ),
    );
    await tester.pump(const Duration(milliseconds: 700));
    expect(selectedModel, 'gpt-5.1-agent');

    await tester.tap(settingsButton);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    await tester.tap(
      find.byKey(
        const ValueKey('chat-input-agent-run-settings-group-reasoning'),
      ),
    );
    await tester.pump(const Duration(milliseconds: 200));
    await tester.tap(
      find.byKey(
        const ValueKey('chat-input-agent-run-settings-option-effort-xhigh'),
      ),
    );
    await tester.pump(const Duration(milliseconds: 300));
    expect(selectedEffort, 'xhigh');
  });

  testWidgets('agent run settings menu opens above a focused composer', (
    tester,
  ) async {
    final controller = TextEditingController();
    final focusNode = FocusNode();
    addTearDown(controller.dispose);
    addTearDown(focusNode.dispose);
    addTearDown(tester.view.resetViewInsets);

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Align(
            alignment: Alignment.bottomCenter,
            child: ChatInputArea(
              controller: controller,
              focusNode: focusNode,
              isProcessing: false,
              onSendMessage: () {},
              onCancelTask: () {},
              useLargeComposerStyle: true,
              agentRunSettings: const AgentRunSettings(
                modelId: 'gpt-5-agent',
                reasoningEffort: 'high',
                modelOptions: <String>['gpt-5-agent'],
                reasoningEffortOptions: <String>['low', 'high'],
              ),
              onAgentRunSettingsChanged: ({modelId, reasoningEffort}) {},
            ),
          ),
        ),
      ),
    );
    await tester.pump();

    focusNode.requestFocus();
    tester.view.viewInsets = const FakeViewPadding(bottom: 280);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 260));

    final settingsButton = find.byKey(
      const ValueKey('chat-input-agent-run-settings-button'),
    );
    await tester.tap(settingsButton);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    final menu = find.byKey(
      const ValueKey('chat-input-agent-run-settings-menu'),
    );
    expect(menu, findsOneWidget);
    expect(
      tester.getBottomLeft(menu).dy,
      lessThan(tester.getTopLeft(settingsButton).dy),
    );
    expect(focusNode.hasFocus, isTrue);
  });

  testWidgets('agent run settings hides unsupported reasoning control', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildTestApp(
        contextUsageRatio: null,
        useLargeComposerStyle: true,
        agentRunSettings: const AgentRunSettings(
          modelId: 'model-without-effort',
          reasoningEffort: '',
          modelOptions: <String>['model-without-effort'],
        ),
        onAgentRunSettingsChanged: ({modelId, reasoningEffort}) {},
      ),
    );
    await tester.pump();

    await tester.tap(
      find.byKey(const ValueKey('chat-input-agent-run-settings-button')),
    );
    await tester.pump();

    expect(find.text('推理强度'), findsNothing);
    expect(
      find.byKey(
        const ValueKey('chat-input-agent-run-settings-group-reasoning'),
      ),
      findsNothing,
    );
    expect(
      find.byKey(
        const ValueKey(
          'chat-input-agent-run-settings-option-model-model-without-effort',
        ),
      ),
      findsOneWidget,
    );
    expect(
      find.byKey(
        const ValueKey('chat-input-agent-run-settings-option-effort-xhigh'),
      ),
      findsNothing,
    );
  });

  testWidgets('agent run settings opens immediately while models refresh', (
    tester,
  ) async {
    final controller = TextEditingController();
    final focusNode = FocusNode();
    final refreshCompleter = Completer<void>();
    addTearDown(controller.dispose);
    addTearDown(focusNode.dispose);
    addTearDown(() {
      if (!refreshCompleter.isCompleted) {
        refreshCompleter.complete();
      }
    });
    const settings = AgentRunSettings(
      modelId: 'cached-agent',
      reasoningEffort: 'xhigh',
      modelOptions: <String>['cached-agent'],
      reasoningEffortOptions: <String>['high', 'xhigh'],
    );
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ChatInputArea(
            controller: controller,
            focusNode: focusNode,
            isProcessing: false,
            onSendMessage: () {},
            onCancelTask: () {},
            useLargeComposerStyle: true,
            agentRunSettings: settings,
            onAgentRunSettingsOpened: () => refreshCompleter.future,
            onAgentRunSettingsChanged: ({modelId, reasoningEffort}) {},
          ),
        ),
      ),
    );
    await tester.pump();

    await tester.tap(
      find.byKey(const ValueKey('chat-input-agent-run-settings-button')),
    );
    await tester.pump();
    expect(
      find.byKey(const ValueKey('chat-input-agent-run-settings-group-model')),
      findsOneWidget,
    );

    await tester.pump(const Duration(milliseconds: 500));
    await tester.tap(
      find.byKey(const ValueKey('chat-input-agent-run-settings-group-model')),
    );
    await tester.pump(const Duration(milliseconds: 200));
    expect(
      find.byKey(
        const ValueKey(
          'chat-input-agent-run-settings-option-model-cached-agent',
        ),
      ),
      findsOneWidget,
    );

    refreshCompleter.complete();
  });

  testWidgets('normal chat model picker renders inside input actions', (
    tester,
  ) async {
    var opened = false;
    await tester.pumpWidget(
      _buildTestApp(
        contextUsageRatio: null,
        useLargeComposerStyle: true,
        modelPickerSettings: ChatModelPickerSettings(
          modelId: 'gpt-5.4-chat-preview',
          hasSelectableModels: true,
          onOpen: (_) {
            opened = true;
          },
        ),
      ),
    );
    await tester.pump();

    final modelButton = find.byKey(
      const ValueKey('chat-input-model-picker-button'),
    );
    expect(modelButton, findsOneWidget);
    expect(find.text('gpt-5.4-chat-preview'), findsNothing);
    expect(
      find.descendant(
        of: modelButton,
        matching: find.byType(ProviderVendorIcon),
      ),
      findsOneWidget,
    );

    await tester.tap(modelButton);
    await tester.pump(const Duration(milliseconds: 300));

    expect(opened, isTrue);
  });

  testWidgets('disabled normal chat model picker does not open', (
    tester,
  ) async {
    var opened = false;
    await tester.pumpWidget(
      _buildTestApp(
        contextUsageRatio: null,
        useLargeComposerStyle: true,
        modelPickerSettings: ChatModelPickerSettings(
          modelId: 'gpt-5.4',
          hasSelectableModels: false,
          onOpen: (_) {
            opened = true;
          },
        ),
      ),
    );
    await tester.pump();

    final modelButton = find.byKey(
      const ValueKey('chat-input-model-picker-button'),
    );
    expect(modelButton, findsOneWidget);

    await tester.tap(modelButton);
    await tester.pump(const Duration(milliseconds: 300));

    expect(opened, isFalse);
  });

  testWidgets('normal chat model picker popup can keep input focus', (
    tester,
  ) async {
    final focusNode = FocusNode();
    OverlayEntry? modelPickerOverlay;
    addTearDown(focusNode.dispose);
    addTearDown(() {
      modelPickerOverlay?.remove();
      modelPickerOverlay = null;
    });

    await tester.pumpWidget(
      _buildTestApp(
        contextUsageRatio: null,
        useLargeComposerStyle: true,
        focusNode: focusNode,
        modelPickerSettings: ChatModelPickerSettings(
          modelId: 'gpt-5.4',
          hasSelectableModels: true,
          onOpen: (anchorContext) {
            final anchor = glassPopupAnchorFromContext(anchorContext)!;
            modelPickerOverlay = OverlayEntry(
              builder: (_) => GlassPopupOverlayContent(
                anchor: anchor,
                child: const SizedBox(width: 120, height: 80),
              ),
            );
            Overlay.of(
              anchorContext,
              rootOverlay: true,
            ).insert(modelPickerOverlay!);
          },
        ),
      ),
    );
    await tester.pump();

    focusNode.requestFocus();
    await tester.pump();
    expect(focusNode.hasFocus, isTrue);

    await tester.tap(
      find.byKey(const ValueKey('chat-input-model-picker-button')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(focusNode.hasFocus, isTrue);
  });

  testWidgets('large composer agent controls fit on narrow screens', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(300, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    await tester.pumpWidget(
      _buildTestApp(
        contextUsageRatio: 0.72,
        useLargeComposerStyle: true,
        onTriggerSlashCommand: () {},
        agentRunSettings: const AgentRunSettings(
          modelId: 'gpt-5-agent',
          reasoningEffort: 'xhigh',
          modelOptions: <String>['gpt-5-agent', 'gpt-5.1-agent'],
          reasoningEffortOptions: <String>['low', 'high', 'xhigh'],
        ),
        onAgentRunSettingsChanged: ({modelId, reasoningEffort}) {},
        agentPermissionMode: AgentPermissionMode.fullAccess,
        onAgentPermissionModeChanged: (_) {},
      ),
    );
    await tester.pump();

    expect(tester.takeException(), isNull);
    expect(
      find.byKey(const ValueKey('chat-input-agent-run-settings-button')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('chat-input-agent-permission-button')),
      findsOneWidget,
    );
  });

  testWidgets('large composer starts collapsed for empty unfocused input', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildTestApp(contextUsageRatio: null, useLargeComposerStyle: true),
    );
    await tester.pump();

    final field = tester.widget<TextField>(find.byType(TextField));
    expect(field.keyboardType, TextInputType.multiline);
    expect(field.textInputAction, TextInputAction.newline);
    expect(field.minLines, 1);
    expect(field.maxLines, 3);
  });

  testWidgets('large composer ignores a foreign soft keyboard', (tester) async {
    await tester.pumpWidget(
      _buildTestApp(contextUsageRatio: null, useLargeComposerStyle: true),
    );
    await tester.pump();

    tester.view.viewInsets = const FakeViewPadding(bottom: 320);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 260));

    final field = tester.widget<TextField>(find.byType(TextField));
    expect(field.minLines, 1);
    expect(field.maxLines, 3);
    tester.view.resetViewInsets();
  });

  testWidgets('large composer collapses when keyboard hides while focused', (
    tester,
  ) async {
    final focusNode = FocusNode();
    await tester.pumpWidget(
      _buildTestApp(
        contextUsageRatio: null,
        useLargeComposerStyle: true,
        focusNode: focusNode,
      ),
    );
    await tester.pump();

    focusNode.requestFocus();
    tester.view.viewInsets = const FakeViewPadding(bottom: 320);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 260));
    expect(tester.widget<TextField>(find.byType(TextField)).minLines, 2);

    tester.view.resetViewInsets();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 260));
    expect(focusNode.hasFocus, isTrue);
    expect(tester.widget<TextField>(find.byType(TextField)).minLines, 1);
  });

  testWidgets('large composer starts collapsing while keyboard is closing', (
    tester,
  ) async {
    final focusNode = FocusNode();
    await tester.pumpWidget(
      _buildTestApp(
        contextUsageRatio: null,
        useLargeComposerStyle: true,
        focusNode: focusNode,
      ),
    );
    await tester.pump();

    focusNode.requestFocus();
    tester.view.viewInsets = const FakeViewPadding(bottom: 320);
    await tester.pump();
    expect(tester.widget<TextField>(find.byType(TextField)).minLines, 2);

    tester.view.viewInsets = const FakeViewPadding(bottom: 280);
    await tester.pump();
    expect(tester.widget<TextField>(find.byType(TextField)).minLines, 1);
    tester.view.resetViewInsets();
  });

  testWidgets('large composer resizes from bottom to keep actions anchored', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildTestApp(contextUsageRatio: null, useLargeComposerStyle: true),
    );
    await tester.pump();

    final animatedSize = tester.widget<AnimatedSize>(find.byType(AnimatedSize));
    expect(animatedSize.alignment, Alignment.bottomCenter);
  });

  testWidgets('large composer stays expanded for existing text without focus', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildTestApp(
        contextUsageRatio: null,
        useLargeComposerStyle: true,
        initialText: 'draft',
      ),
    );
    await tester.pump();

    final field = tester.widget<TextField>(find.byType(TextField));
    expect(field.minLines, 2);
    expect(field.maxLines, 3);
  });

  testWidgets('large composer sends an external edit payload with empty text', (
    tester,
  ) async {
    var sendCount = 0;
    await tester.pumpWidget(
      _buildTestApp(
        contextUsageRatio: null,
        useLargeComposerStyle: true,
        hasExternalSendPayload: true,
        onSendMessage: () {
          sendCount += 1;
        },
      ),
    );
    await tester.pump();

    final sendButton = find.byKey(
      const ValueKey('chat-input-send-or-stop-button'),
    );
    expect(sendButton, findsOneWidget);
    expect(tester.widget<IconButton>(sendButton).onPressed, isNotNull);

    await tester.tap(sendButton);
    await tester.pump();

    expect(sendCount, 1);
  });

  testWidgets('large composer enables send after text input', (tester) async {
    var sendCount = 0;
    final controller = TextEditingController();
    final focusNode = FocusNode();
    addTearDown(controller.dispose);
    addTearDown(focusNode.dispose);

    await tester.pumpWidget(
      DefaultAssetBundle(
        bundle: _TestAssetBundle(),
        child: MaterialApp(
          home: Scaffold(
            body: ChatInputArea(
              controller: controller,
              focusNode: focusNode,
              isProcessing: false,
              onSendMessage: () => sendCount += 1,
              onCancelTask: () {},
              useLargeComposerStyle: true,
            ),
          ),
        ),
      ),
    );
    await tester.pump();

    await tester.enterText(find.byType(TextField), 'test command');
    await tester.pump();

    final sendButton = find.byKey(
      const ValueKey('chat-input-send-or-stop-button'),
    );
    expect(tester.widget<IconButton>(sendButton).onPressed, isNotNull);

    await tester.tap(sendButton);
    await tester.pump();
    expect(sendCount, 1);
  });

  testWidgets('large composer enables send after external draft restore', (
    tester,
  ) async {
    final controller = TextEditingController();
    final focusNode = FocusNode();
    addTearDown(controller.dispose);
    addTearDown(focusNode.dispose);

    await tester.pumpWidget(
      DefaultAssetBundle(
        bundle: _TestAssetBundle(),
        child: MaterialApp(
          home: Scaffold(
            body: ChatInputArea(
              controller: controller,
              focusNode: focusNode,
              isProcessing: false,
              onSendMessage: () {},
              onCancelTask: () {},
              useLargeComposerStyle: true,
            ),
          ),
        ),
      ),
    );
    await tester.pump();

    controller.value = const TextEditingValue(text: 'restored draft');
    await tester.pump();

    final sendButton = find.byKey(
      const ValueKey('chat-input-send-or-stop-button'),
    );
    expect(tester.widget<IconButton>(sendButton).onPressed, isNotNull);
  });

  testWidgets(
    'user message editing keeps only cancel and send composer actions',
    (tester) async {
      var isEditing = false;
      StateSetter? setHostState;
      final controller = TextEditingController(text: 'original');
      final focusNode = FocusNode();
      addTearDown(controller.dispose);
      addTearDown(focusNode.dispose);

      await tester.pumpWidget(
        DefaultAssetBundle(
          bundle: _TestAssetBundle(),
          child: MaterialApp(
            home: Scaffold(
              body: StatefulBuilder(
                builder: (context, setState) {
                  setHostState = setState;
                  return ChatInputArea(
                    controller: controller,
                    focusNode: focusNode,
                    isProcessing: false,
                    onSendMessage: () {},
                    onCancelTask: () {},
                    useLargeComposerStyle: true,
                    isEditingUserMessage: isEditing,
                    onCancelUserMessageEditing: () {
                      setState(() => isEditing = false);
                    },
                    contextUsageRatio: 0.5,
                    onTriggerSlashCommand: () {},
                    modelPickerSettings: ChatModelPickerSettings(
                      modelId: 'gpt-5.4-chat-preview',
                      hasSelectableModels: true,
                      onOpen: (_) {},
                    ),
                  );
                },
              ),
            ),
          ),
        ),
      );
      await tester.pump();

      expect(
        find.byKey(const ValueKey('chat-input-trigger-slash-button')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey('chat-input-model-picker-button')),
        findsOneWidget,
      );

      setHostState!(() => isEditing = true);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 220));

      final cancelButton = find.byKey(
        const ValueKey('chat-input-add-or-cancel-edit-button'),
      );
      expect(cancelButton, findsOneWidget);
      expect(
        tester
            .widget<AnimatedRotation>(
              find.byKey(const ValueKey('chat-input-add-or-cancel-edit-icon')),
            )
            .turns,
        0.125,
      );
      expect(
        find.byKey(const ValueKey('chat-input-send-or-stop-button')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey('chat-input-trigger-slash-button')),
        findsNothing,
      );
      expect(
        find.byKey(const ValueKey('chat-input-model-picker-button')),
        findsNothing,
      );
      expect(find.byTooltip('Open terminal'), findsNothing);
      expect(
        find.byWidgetPredicate(
          (widget) =>
              widget is CustomPaint &&
              widget.painter.runtimeType.toString() ==
                  '_ContextUsageRingPainter',
        ),
        findsNothing,
      );

      await tester.tap(cancelButton);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 220));

      expect(isEditing, isFalse);
      expect(
        tester
            .widget<AnimatedRotation>(
              find.byKey(const ValueKey('chat-input-add-or-cancel-edit-icon')),
            )
            .turns,
        0,
      );
      expect(
        find.byKey(const ValueKey('chat-input-trigger-slash-button')),
        findsOneWidget,
      );
      expect(
        find.byKey(const ValueKey('chat-input-model-picker-button')),
        findsOneWidget,
      );
    },
  );

  testWidgets('tapping outside a focused composer exits message editing', (
    tester,
  ) async {
    var isEditing = true;
    final controller = TextEditingController(text: 'original');
    final focusNode = FocusNode();
    addTearDown(controller.dispose);
    addTearDown(focusNode.dispose);

    await tester.pumpWidget(
      DefaultAssetBundle(
        bundle: _TestAssetBundle(),
        child: MaterialApp(
          home: Scaffold(
            body: StatefulBuilder(
              builder: (context, setState) {
                return Column(
                  children: [
                    const Expanded(
                      child: _OpaqueTestTapTarget(
                        key: ValueKey('chat-input-test-outside-target'),
                      ),
                    ),
                    ChatInputArea(
                      controller: controller,
                      focusNode: focusNode,
                      isProcessing: false,
                      onSendMessage: () {},
                      onCancelTask: () {},
                      useLargeComposerStyle: true,
                      isEditingUserMessage: isEditing,
                      onCancelUserMessageEditing: () {
                        setState(() => isEditing = false);
                        controller.clear();
                      },
                      onTriggerSlashCommand: () {},
                    ),
                  ],
                );
              },
            ),
          ),
        ),
      ),
    );
    await tester.pump();

    focusNode.requestFocus();
    await tester.pump();
    expect(focusNode.hasFocus, isTrue);
    expect(isEditing, isTrue);

    await tester.tap(
      find.byKey(const ValueKey('chat-input-test-outside-target')),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 220));

    expect(focusNode.hasFocus, isFalse);
    expect(isEditing, isFalse);
    expect(controller.text, isEmpty);
    expect(
      find.byKey(const ValueKey('chat-input-trigger-slash-button')),
      findsOneWidget,
    );
  });

  testWidgets('compact composer keeps send action', (tester) async {
    await tester.pumpWidget(
      _buildTestApp(contextUsageRatio: null, useLargeComposerStyle: false),
    );
    await tester.pump();

    final field = tester.widget<TextField>(find.byType(TextField));
    expect(field.keyboardType, TextInputType.text);
    expect(field.textInputAction, TextInputAction.send);
    expect(field.maxLines, 1);
  });
}

Widget _buildTestApp({
  required double? contextUsageRatio,
  VoidCallback? onLongPressContextUsageRing,
  VoidCallback? onTriggerSlashCommand,
  bool useLargeComposerStyle = false,
  AgentPermissionMode? agentPermissionMode,
  List<AgentPermissionMode> agentPermissionModes = AgentPermissionMode.values,
  ValueChanged<AgentPermissionMode>? onAgentPermissionModeChanged,
  AgentRunSettings? agentRunSettings,
  AgentRunSettingsChanged? onAgentRunSettingsChanged,
  ChatModelPickerSettings? modelPickerSettings,
  String initialText = '',
  FocusNode? focusNode,
  bool hasExternalSendPayload = false,
  VoidCallback? onSendMessage,
}) {
  return DefaultAssetBundle(
    bundle: _TestAssetBundle(),
    child: MaterialApp(
      home: Scaffold(
        body: ChatInputArea(
          controller: TextEditingController(text: initialText),
          focusNode: focusNode ?? FocusNode(),
          isProcessing: false,
          onSendMessage: onSendMessage ?? () {},
          onCancelTask: () {},
          useLargeComposerStyle: useLargeComposerStyle,
          hasExternalSendPayload: hasExternalSendPayload,
          contextUsageRatio: contextUsageRatio,
          onLongPressContextUsageRing: onLongPressContextUsageRing,
          onTriggerSlashCommand: onTriggerSlashCommand,
          modelPickerSettings: modelPickerSettings,
          agentRunSettings: agentRunSettings,
          onAgentRunSettingsChanged: onAgentRunSettingsChanged,
          agentPermissionMode: agentPermissionMode,
          agentPermissionModes: agentPermissionModes,
          onAgentPermissionModeChanged: onAgentPermissionModeChanged,
        ),
      ),
    ),
  );
}

class _TestAssetBundle extends CachingAssetBundle {
  static const String _svg = '''
<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
  <circle cx="12" cy="12" r="10" stroke="#1930D9" stroke-width="2"/>
</svg>
''';

  @override
  Future<ByteData> load(String key) async {
    final bytes = Uint8List.fromList(utf8.encode(_svg));
    return ByteData.view(bytes.buffer);
  }

  @override
  Future<String> loadString(String key, {bool cache = true}) async {
    return _svg;
  }
}

class _OpaqueTestTapTarget extends StatelessWidget {
  const _OpaqueTestTapTarget({super.key});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () {},
      child: const SizedBox.expand(),
    );
  }
}
