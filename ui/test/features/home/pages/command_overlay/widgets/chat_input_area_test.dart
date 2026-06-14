import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_svg/flutter_svg.dart';
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

  testWidgets('codex permission selector opens menu and selects mode', (
    tester,
  ) async {
    CodexPermissionMode? selected;
    await tester.pumpWidget(
      _buildTestApp(
        contextUsageRatio: null,
        useLargeComposerStyle: true,
        codexPermissionMode: CodexPermissionMode.fullAccess,
        onCodexPermissionModeChanged: (mode) {
          selected = mode;
        },
      ),
    );
    await tester.pump();

    final permissionButton = find.byKey(
      const ValueKey('chat-input-codex-permission-button'),
    );
    expect(
      find.descendant(of: permissionButton, matching: find.byType(SvgPicture)),
      findsOneWidget,
    );

    await tester.tap(permissionButton);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(
      find.byKey(
        const ValueKey('chat-input-codex-permission-option-defaultMode'),
      ),
      findsOneWidget,
    );
    expect(
      find.byKey(
        const ValueKey('chat-input-codex-permission-option-autoReview'),
      ),
      findsOneWidget,
    );
    expect(
      find.byKey(
        const ValueKey('chat-input-codex-permission-option-fullAccess'),
      ),
      findsOneWidget,
    );
    for (final mode in CodexPermissionMode.values) {
      expect(
        find.descendant(
          of: find.byKey(
            ValueKey('chat-input-codex-permission-option-${mode.name}'),
          ),
          matching: find.byType(SvgPicture),
        ),
        findsOneWidget,
      );
    }

    await tester.tap(
      find.byKey(
        const ValueKey('chat-input-codex-permission-option-autoReview'),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(selected, CodexPermissionMode.autoReview);
  });

  testWidgets('codex run settings selector selects model and effort', (
    tester,
  ) async {
    String? selectedModel;
    String? selectedEffort;
    await tester.pumpWidget(
      _buildTestApp(
        contextUsageRatio: null,
        useLargeComposerStyle: true,
        codexRunSettings: const CodexRunSettings(
          modelId: 'gpt-5-codex',
          reasoningEffort: 'high',
          modelOptions: <String>['gpt-5-codex', 'gpt-5.1-codex'],
          reasoningEffortOptions: <String>['low', 'high', 'xhigh'],
        ),
        onCodexRunSettingsChanged: ({modelId, reasoningEffort}) {
          selectedModel = modelId;
          selectedEffort = reasoningEffort;
        },
      ),
    );
    await tester.pump();

    final settingsButton = find.byKey(
      const ValueKey('chat-input-codex-run-settings-button'),
    );
    expect(settingsButton, findsOneWidget);

    await tester.tap(settingsButton);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    await tester.tap(
      find.byKey(
        const ValueKey(
          'chat-input-codex-run-settings-option-model-gpt-5.1-codex',
        ),
      ),
    );
    await tester.pump(const Duration(milliseconds: 300));
    expect(selectedModel, 'gpt-5.1-codex');

    await tester.tap(settingsButton);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    await tester.tap(
      find.byKey(
        const ValueKey('chat-input-codex-run-settings-option-effort-xhigh'),
      ),
    );
    await tester.pump(const Duration(milliseconds: 300));
    expect(selectedEffort, 'xhigh');
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
    addTearDown(focusNode.dispose);

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
            return showGlassPopup<void>(
              context: anchorContext,
              anchor: anchor,
              requestFocus: false,
              child: const SizedBox(width: 120, height: 80),
            );
          },
        ),
      ),
    );
    await tester.pump();

    focusNode.requestFocus();
    await tester.pump();
    expect(focusNode.hasFocus, isTrue);

    await tester.tap(find.byKey(const ValueKey('chat-input-model-picker-button')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(focusNode.hasFocus, isTrue);
  });

  testWidgets('large composer codex controls fit on narrow screens', (
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
        codexRunSettings: const CodexRunSettings(
          modelId: 'gpt-5-codex',
          reasoningEffort: 'xhigh',
          modelOptions: <String>['gpt-5-codex', 'gpt-5.1-codex'],
          reasoningEffortOptions: <String>['low', 'high', 'xhigh'],
        ),
        onCodexRunSettingsChanged: ({modelId, reasoningEffort}) {},
        codexPermissionMode: CodexPermissionMode.fullAccess,
        onCodexPermissionModeChanged: (_) {},
      ),
    );
    await tester.pump();

    expect(tester.takeException(), isNull);
    expect(
      find.byKey(const ValueKey('chat-input-codex-run-settings-button')),
      findsOneWidget,
    );
    expect(
      find.byKey(const ValueKey('chat-input-codex-permission-button')),
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

  testWidgets('large composer expands when soft keyboard is visible', (
    tester,
  ) async {
    await tester.pumpWidget(
      _buildTestApp(contextUsageRatio: null, useLargeComposerStyle: true),
    );
    await tester.pump();

    tester.view.viewInsets = const FakeViewPadding(bottom: 320);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 260));

    final field = tester.widget<TextField>(find.byType(TextField));
    expect(field.minLines, 2);
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
    await tester.pumpWidget(
      _buildTestApp(contextUsageRatio: null, useLargeComposerStyle: true),
    );
    await tester.pump();

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
  CodexPermissionMode? codexPermissionMode,
  ValueChanged<CodexPermissionMode>? onCodexPermissionModeChanged,
  CodexRunSettings? codexRunSettings,
  CodexRunSettingsChanged? onCodexRunSettingsChanged,
  ChatModelPickerSettings? modelPickerSettings,
  String initialText = '',
  FocusNode? focusNode,
}) {
  return DefaultAssetBundle(
    bundle: _TestAssetBundle(),
    child: MaterialApp(
      home: Scaffold(
        body: ChatInputArea(
          controller: TextEditingController(text: initialText),
          focusNode: focusNode ?? FocusNode(),
          isProcessing: false,
          onSendMessage: () {},
          onCancelTask: () {},
          useLargeComposerStyle: useLargeComposerStyle,
          contextUsageRatio: contextUsageRatio,
          onLongPressContextUsageRing: onLongPressContextUsageRing,
          onTriggerSlashCommand: onTriggerSlashCommand,
          modelPickerSettings: modelPickerSettings,
          codexRunSettings: codexRunSettings,
          onCodexRunSettingsChanged: onCodexRunSettingsChanged,
          codexPermissionMode: codexPermissionMode,
          onCodexPermissionModeChanged: onCodexPermissionModeChanged,
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
