import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/config/jackery_ces_config.dart';
import 'package:ui/features/home/pages/chat/utils/jackery_chat_policy.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_empty_greeting.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_widgets.dart';
import 'package:ui/features/jackery/jackery_device_widgets.dart';
import 'package:ui/features/home/pages/chat/widgets/jackery_model_picker.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/chat_input_area.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final ces = JackeryCesConfig.enabled;

  test('legacy developer sessions never become a retyped CES conversation', () {
    const legacy = ConversationThreadTarget.existing(
      conversationId: 42,
      mode: ConversationMode.agent,
      agentId: 'codex-remote',
      agentSessionId: 'remote-session',
    );
    final target = cesConversationTarget(legacy);
    expect(target.mode, ConversationMode.chatOnly);
    expect(target.isNewConversation, isTrue);
    expect(target.conversationId, isNull);
    expect(target.agentSessionId, isNull);
    expect(target.agentId, isNull);
    const existing = ConversationThreadTarget.existing(
      conversationId: 77,
      mode: ConversationMode.chatOnly,
      requestKey: 'open-history',
    );
    expect(identical(cesConversationTarget(existing), existing), isTrue);
  });

  test(
    'audience projection keeps terminal error identity and excludes tools',
    () {
      final failure = ChatMessageModel(
        id: 'failure-item',
        type: 2,
        user: 3,
        createAt: DateTime(2026),
        streamMeta: {'kind': 'error', 'turnId': 'one-turn'},
        content: {
          'cardData': {'toolType': 'status', 'summary': '设备连接失败'},
        },
      );
      expect(isCesConversationMessage(failure), isTrue);
      final rendered = cesConversationMessage(failure);
      expect(rendered.id, failure.id);
      expect(rendered.streamMeta, failure.streamMeta);
      expect(rendered.isError, isTrue);
      if (ces)
        expect(RegExp(r'[\u3400-\u9fff]').hasMatch(rendered.text!), isFalse);
      expect(
        isCesConversationMessage(
          failure.copyWith(
            streamMeta: {'kind': 'tool'},
            content: {
              'cardData': {'toolType': 'search'},
            },
          ),
        ),
        isFalse,
      );
    },
  );

  testWidgets(
    'CES greeting overrides Harness identity and tasks retain prompts',
    (tester) async {
      String? selected;
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(
            body: ChatEmptyGreeting(
              agentName: 'Codex',
              agentWorkspaceName: 'developer-workspace',
              quickPrompts: jackeryDemoPrompts,
              onQuickPromptSelected: (prompt) => selected = prompt.prompt,
            ),
          ),
        ),
      );
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.text("Hi, I'm Jackery Power AI"), findsOneWidget);
      expect(find.textContaining('Codex'), findsNothing);
      expect(find.textContaining('developer-workspace'), findsNothing);
      await tester.tap(find.text('Catch up on CES'));
      expect(selected, contains('include sources'));
      await tester.pumpWidget(const SizedBox.shrink());
    },
    skip: !ces,
  );

  testWidgets('CES chrome omits developer controls even when callbacks exist', (
    tester,
  ) async {
    var tools = 0;
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        home: Scaffold(
          body: ChatAppBar(
            onMenuTap: () {},
            onPetTap: () => tools++,
            onAgentTap: () => tools++,
            activeMode: ChatSurfaceMode.workspace,
            onModeChanged: (_) => tools++,
            onDisplayLayerChanged: (_) => tools++,
            onTerminalEnvironmentTap: (_) => tools++,
            onTerminalTap: () => tools++,
            onBrowserTap: () => tools++,
            showSurfaceSwitcher: true,
          ),
        ),
      ),
    );
    expect(find.text(JackeryCesConfig.productName), findsOneWidget);
    expect(find.byTooltip('Open menu'), findsOneWidget);
    expect(find.textContaining('Workspace'), findsNothing);
    expect(find.textContaining('Terminal'), findsNothing);
    expect(find.textContaining('Codex'), findsNothing);
    expect(tools, 0);
  }, skip: !ces);

  testWidgets(
    'device card appears only on empty home and messages omit tool chrome',
    (tester) async {
      final scroll = ScrollController();
      const deviceChannel = MethodChannel('cn.com.omnimind.bot/jackery_ces');
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            deviceChannel,
            (call) async => <String, dynamic>{'connected': false},
          );
      Widget app(List<ChatMessageModel> messages) => DefaultAssetBundle(
        bundle: _SvgBundle(),
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(
            body: ChatMessageList(
              messages: messages,
              scrollController: scroll,
              onBeforeTaskExecute: () async {},
              emptyGreetingQuickPrompts: jackeryDemoPrompts,
            ),
          ),
        ),
      );
      await tester.pumpWidget(app([]));
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.byType(JackeryDeviceCard), findsOneWidget);
      expect(find.text('Not connected'), findsOneWidget);
      expect(find.text('76%'), findsNothing);
      final user = ChatMessageModel(
        id: 'user',
        type: 1,
        user: 1,
        createAt: DateTime(2026),
        content: {'text': 'Who are you?'},
      );
      final assistant = ChatMessageModel(
        id: 'reply',
        type: 1,
        user: 2,
        createAt: DateTime(2026),
        content: {'text': 'I am Jackery Power AI.'},
      );
      final tool = ChatMessageModel(
        id: 'tool',
        type: 2,
        user: 3,
        createAt: DateTime(2026),
        content: {
          'cardData': {'toolTitle': 'private-tool', 'toolType': 'search'},
        },
      );
      await tester.pumpWidget(app([assistant, tool, user]));
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.byType(JackeryDeviceCard), findsNothing);
      expect(find.text('View device'), findsNothing);
      expect(find.text('private-tool'), findsNothing);
      expect(find.text('Who are you?'), findsOneWidget);
      expect(find.textContaining('I am Jackery Power AI.'), findsOneWidget);
      await tester.pumpWidget(const SizedBox.shrink());
      scroll.dispose();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(deviceChannel, null);
    },
    skip: !ces,
  );

  testWidgets(
    'unavailable model has no configuration or cloud selection path',
    (tester) async {
      var selected = false;
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(
            body: JackeryModelPicker(
              available: false,
              onSelect: () => selected = true,
            ),
          ),
        ),
      );
      expect(find.text(JackeryCesConfig.modelDisplayName), findsOneWidget);
      expect(find.text('AI unavailable'), findsOneWidget);
      expect(find.textContaining('Configure'), findsNothing);
      await tester.tap(find.byKey(const ValueKey('jackery-on-device-model')));
      expect(selected, isFalse);
    },
    skip: !ces,
  );

  testWidgets(
    'audience composer retains send attachment model circle and shield',
    (tester) async {
      final controller = TextEditingController(text: 'Who are you?');
      final focus = FocusNode();
      var sent = 0;
      var attached = 0;
      var models = 0;
      var developerActions = 0;
      await tester.pumpWidget(
        DefaultAssetBundle(
          bundle: _SvgBundle(),
          child: MaterialApp(
            theme: AppTheme.lightTheme,
            home: Scaffold(
              body: Align(
                alignment: Alignment.bottomCenter,
                child: ChatInputArea(
                  controller: controller,
                  focusNode: focus,
                  isProcessing: false,
                  onSendMessage: () => sent++,
                  onCancelTask: () {},
                  onTerminalTap: () => developerActions++,
                  onTriggerSlashCommand: () => developerActions++,
                  useLargeComposerStyle: true,
                  useAttachmentPickerForPlus: true,
                  onPickAttachment: () async {
                    attached++;
                  },
                  runtimeConfigButton: IconButton(
                    tooltip: 'Select model',
                    onPressed: () => models++,
                    icon: const Icon(Icons.tune),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pump(const Duration(milliseconds: 500));
      expect(find.byTooltip('Open terminal'), findsNothing);
      expect(
        find.byKey(const ValueKey('chat-input-trigger-slash-button')),
        findsNothing,
      );
      expect(find.byTooltip('Protected demo settings'), findsOneWidget);
      expect(
        find.byWidgetPredicate(
          (widget) =>
              widget is CustomPaint &&
              widget.painter.runtimeType.toString() ==
                  '_ContextUsageRingPainter',
        ),
        findsOneWidget,
      );
      expect(
        find.byWidgetPredicate(
          (widget) =>
              widget is CustomPaint &&
              widget.painter.runtimeType.toString() ==
                  '_ComposerFlowBorderPainter',
        ),
        findsNothing,
      );
      await tester.tap(find.byTooltip('Add attachment'));
      await tester.tap(find.byTooltip('Select model'));
      await tester.tap(find.byTooltip('Send'));
      expect(attached, 1);
      expect(models, 1);
      expect(sent, 1);
      expect(developerActions, 0);
      await tester.pumpWidget(const SizedBox.shrink());
      controller.dispose();
      focus.dispose();
    },
    skip: !ces,
  );
}

class _SvgBundle extends CachingAssetBundle {
  @override
  Future<ByteData> load(String key) async {
    final data = Uint8List.fromList(utf8.encode(await loadString(key)));
    return ByteData.sublistView(data);
  }

  @override
  Future<String> loadString(String key, {bool cache = true}) async =>
      '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path d="M2 2L22 22" stroke="black"/></svg>';
}
