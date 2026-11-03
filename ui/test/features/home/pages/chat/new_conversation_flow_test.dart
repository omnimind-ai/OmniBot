import 'dart:async';

import 'package:android_file_picker/android_file_picker.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/chat/chat_page.dart';
import 'package:ui/features/home/widgets/home_drawer.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/features/home/pages/chat/widgets/acp_config_button.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_empty_greeting.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_spotlight_tour.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_widgets.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_pane_resize_handle.dart';
import 'package:ui/features/home/pages/chat/widgets/new_conversation_mode_selector.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/chat_input_area.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/theme/app_theme.dart';

class _DraftFilePicker extends FilePickerPlatform {
  List<PlatformFile> nextFiles = [];

  @override
  Future<List<PlatformFile>> pickFiles({
    String? dialogTitle,
    String? initialDirectory,
    FileType type = FileType.any,
    List<String>? allowedExtensions,
    Function(FilePickerStatus)? onFileLoading,
    int compressionQuality = 0,
    AndroidOptions androidOptions = const AndroidOptions(),
    DarwinOptions darwinOptions = const DarwinOptions(),
    WindowsOptions windowsOptions = const WindowsOptions(),
    LinuxOptions linuxOptions = const LinuxOptions(),
    WebOptions webOptions = const WebOptions(),
  }) async => nextFiles;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const assistChannel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  const agentChannel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  const otherChannels = [
    'cn.com.omnimind.bot/AgentRuntimeEvents',
    'cn.com.omnimind.bot/OmniLinkEvents',
    'cn.com.omnimind.bot/SpecialPermissionEvent',
    'cn.com.omnimind.bot/SpecialPermissionEvents',
    'cn.com.omnimind.bot/VoicePlayback',
    'cn.com.omnimind.bot/VoicePlaybackEvents',
    'cn.com.omnimind.bot/overlay',
    'cn.com.omnimind.bot/app_update',
    'cn.com.omnimind.bot/app_state',
    'cn.com.omnimind.bot/PluginPlatform',
  ];
  final coordinator = ChatConversationRuntimeCoordinator.instance;
  late List<MethodCall> assistCalls;
  late List<MethodCall> agentCalls;
  late List<Map<String, Object?>> conversations;
  late List<Map<String, Object?>> agents;
  late _DraftFilePicker picker;
  late FilePickerPlatform originalPicker;
  Completer<void>? initializeGate;
  Completer<void>? sessionNewGate;
  Completer<void>? sessionLoadGate;
  Completer<void>? historyMetadataGate;
  Completer<void>? historySessionGate;
  Map<String, Object?>? historySessionResponse;
  late Map<String, Map<String, Object?>> naHistoryPages;
  late Set<String> failedNaHistoryCursors;
  Completer<void>? naHistoryStatusGate;
  var delayNaHistoryStatus = false;
  Completer<void>? remoteSessionLoadGate;
  Completer<void>? remoteHistoryReadGate;
  var useRemoteRuntime = false;
  Completer<void>? restoredTargetGate;
  ConversationThreadTarget? restoredTarget;
  late Map<int, Completer<void>> historyPageGates;
  late Map<int, List<Map<String, Object?>>> historyPages;
  late Set<int> failedHistoryPages;
  var allowConversationCreation = false;
  var failAgentStatus = false;
  var failDefaultConnect = false;
  var failSessionLoad = false;
  var reportedStatusAgentId = 'xiaowan-acp';
  var reportGlobalRemote = false;
  var nextSessionNumber = 0;
  var reasoningOptions = <String>[];
  var reasoningValue = 'off';
  late Map<String, Map<String, Object>> sessionConfig;

  Map<String, Object?> sessionResponse(String sessionId) => {
    'sessionId': sessionId,
    'configOptions': [
      if (reasoningOptions.isNotEmpty)
        {
          'id': 'thought_level',
          'name': 'Thinking',
          'category': 'thought_level',
          'type': 'select',
          'currentValue':
              sessionConfig[sessionId]?['thought_level'] ?? reasoningValue,
          'options': [
            for (final value in reasoningOptions)
              {'value': value, 'name': value},
          ],
        },
      {
        'id': 'notifications',
        'name': '通知提示',
        'type': 'boolean',
        'currentValue': sessionConfig[sessionId]?['notifications'] ?? false,
      },
      {
        'id': 'collaboration_mode',
        'name': '协作模式',
        'type': 'select',
        'currentValue':
            sessionConfig[sessionId]?['collaboration_mode'] ?? 'default',
        'options': [
          {'value': 'default', 'name': 'Default'},
          {'value': 'plan', 'name': 'Plan'},
        ],
      },
    ],
  };

  const defaultAgents = [
    {
      'id': 'xiaowan-acp',
      'name': '小万',
      'enabled': true,
      'installed': true,
      'status': 'online',
    },
    {
      'id': 'codex-acp',
      'name': 'Codex',
      'enabled': true,
      'installed': true,
      'status': 'online',
    },
    {
      'id': 'na-cloud',
      'name': 'Na',
      'enabled': true,
      'installed': true,
      'status': 'online',
    },
  ];

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    coordinator.resetForTest();
    originalPicker = FilePickerPlatform.instance;
    picker = _DraftFilePicker();
    FilePickerPlatform.instance = picker;
    initializeGate = null;
    sessionNewGate = null;
    sessionLoadGate = null;
    historyMetadataGate = null;
    historySessionGate = null;
    historySessionResponse = null;
    naHistoryPages = {};
    failedNaHistoryCursors = {};
    naHistoryStatusGate = null;
    delayNaHistoryStatus = false;
    remoteSessionLoadGate = null;
    remoteHistoryReadGate = null;
    useRemoteRuntime = false;
    restoredTargetGate = null;
    restoredTarget = null;
    historyPageGates = {};
    historyPages = {};
    failedHistoryPages = {};
    allowConversationCreation = false;
    failAgentStatus = false;
    failDefaultConnect = false;
    failSessionLoad = false;
    reportedStatusAgentId = 'xiaowan-acp';
    reportGlobalRemote = false;
    nextSessionNumber = 0;
    reasoningOptions = [];
    reasoningValue = 'off';
    sessionConfig = {};
    assistCalls = [];
    agentCalls = [];
    conversations = [];
    agents = [
      for (final agent in defaultAgents) {...agent},
    ];
    for (final channel in otherChannels) {
      messenger.setMockMethodCallHandler(MethodChannel(channel), (_) async {
        return null;
      });
    }
    messenger.setMockMethodCallHandler(assistChannel, (call) async {
      assistCalls.add(call);
      switch (call.method) {
        case 'getConversations':
          await historyMetadataGate?.future;
          return conversations;
        case 'getConversationMessagesPaged':
          final conversationId =
              (call.arguments as Map)['conversationId'] as int;
          await historyPageGates[conversationId]?.future;
          if (failedHistoryPages.contains(conversationId)) {
            throw PlatformException(code: 'HISTORY_UNAVAILABLE');
          }
          return {
            'messages': historyPages[conversationId] ?? <Object?>[],
            'hasMore': false,
          };
        case 'conversationSelection':
          if ((call.arguments as Map)['operation'] == 'getLast') {
            await restoredTargetGate?.future;
            return restoredTarget?.toJson();
          }
          return null;
        case 'listModelProviderProfiles':
          return {'profiles': <Object?>[], 'editingProfileId': ''};
        case 'getSceneCatalog':
        case 'getSceneModelBindings':
        case 'getScheduledTasks':
          return <Object?>[];
        case 'setCurrentConversationId':
          await initializeGate?.future;
          return 'SUCCESS';
        case 'createConversation':
          if (!allowConversationCreation) return null;
          final arguments = call.arguments as Map;
          final conversationId = 901 + conversations.length;
          conversations.add({
            'id': conversationId,
            'title': arguments['title'],
            'mode': arguments['mode'],
            'agentId': arguments['agentId'],
            'status': 0,
            'messageCount': 0,
            'createdAt': 1000,
            'updatedAt': 1000,
          });
          return conversationId;
        case 'updateConversation':
          final arguments = call.arguments as Map;
          final updated = Map<String, Object?>.from(
            arguments['conversation'] as Map,
          );
          final index = conversations.indexWhere(
            (conversation) => conversation['id'] == updated['id'],
          );
          if (index >= 0) conversations[index] = updated;
          return 'SUCCESS';
        case 'getWorkspaceLongMemory':
          return {'content': ''};
      }
      return null;
    });
    messenger.setMockMethodCallHandler(agentChannel, (call) async {
      agentCalls.add(call);
      switch (call.method) {
        case 'config/read':
          if (reasoningOptions.isEmpty) return <String, Object?>{};
          return {'model': 'fixture', 'reasoning_effort': reasoningValue};
        case 'model/list':
          if (reasoningOptions.isEmpty) return <String, Object?>{};
          return {
            ...sessionResponse('catalog'),
            'models': ['fixture'],
          };
        case 'agent/list':
          return {'agents': agents};
        case 'status':
          if (delayNaHistoryStatus) {
            delayNaHistoryStatus = false;
            await naHistoryStatusGate?.future;
          }
          if (failAgentStatus) {
            throw PlatformException(
              code: 'STATUS_UNAVAILABLE',
              message: 'Agent status unavailable',
            );
          }
          return {
            'ready': false,
            'connected': useRemoteRuntime,
            if (useRemoteRuntime || reportGlobalRemote) 'runtime': 'remote',
            'remoteEnabled': useRemoteRuntime || reportGlobalRemote,
            'activeAgentId': useRemoteRuntime || reportGlobalRemote
                ? 'codex-acp'
                : reportedStatusAgentId,
            'activeAgentName': '小万',
          };
        case 'connect':
          if (failDefaultConnect) {
            throw PlatformException(
              code: 'runtime_error',
              message: 'OpenCode runtime needs an upgrade',
              details: {'failureKind': 'harness_upgrade_required'},
            );
          }
          if (useRemoteRuntime) {
            return {
              'ready': false,
              'connected': true,
              'runtime': 'remote',
              'remoteEnabled': true,
              'activeAgentId': 'codex-acp',
            };
          }
          return <String, Object?>{};
        case 'session/new':
          final number = ++nextSessionNumber;
          final sessionId = number == 1
              ? 'new-draft-session'
              : 'new-draft-session-$number';
          sessionConfig[sessionId] = {
            'notifications': false,
            'collaboration_mode': 'default',
          };
          await sessionNewGate?.future;
          return sessionResponse(sessionId);
        case 'session/load':
          final arguments = call.arguments as Map;
          if (failedNaHistoryCursors.contains(arguments['historyCursor'])) {
            throw PlatformException(code: 'TEMPORARY_HISTORY_FAILURE');
          }
          if (arguments['agentId'] == 'na-cloud' &&
              arguments['includeHistory'] == false &&
              naHistoryStatusGate != null) {
            delayNaHistoryStatus = true;
          }
          if (failSessionLoad) {
            throw PlatformException(
              code: 'SESSION_UNAVAILABLE',
              message: 'Session load failed',
            );
          }
          if (useRemoteRuntime &&
              arguments['sessionId'] == 'remote-history-session') {
            if (arguments['includeHistory'] == true) {
              await remoteHistoryReadGate?.future;
              return {
                'threadId': 'remote-history-session',
                'thread': {
                  'id': 'remote-history-session',
                  'turns': [
                    {
                      'id': 'remote-history-turn',
                      'stopReason': 'end_turn',
                      'items': [
                        {
                          'id': 'remote-history-message',
                          'type': 'userMessage',
                          'content': '远程会话恢复的历史消息',
                        },
                      ],
                    },
                  ],
                },
              };
            }
            await remoteSessionLoadGate?.future;
            return {
              'threadId': 'remote-history-session',
              'thread': {'id': 'remote-history-session'},
            };
          }
          if (arguments['refreshConfig'] == true) await sessionLoadGate?.future;
          if (arguments['conversationId'] != null) {
            final snapshot =
                naHistoryPages[arguments['historyCursor']] ??
                historySessionResponse;
            await historySessionGate?.future;
            if (snapshot != null) return snapshot;
          }
          return sessionResponse(
            (arguments['sessionId'] ??
                    'existing-session-${arguments['conversationId']}')
                as String,
          );
        case 'session/set_config_option':
          final arguments = call.arguments as Map;
          final sessionId = arguments['sessionId'] as String;
          sessionConfig[sessionId]![arguments['configId'] as String] =
              arguments['value'] as Object;
          if (arguments['configId'] == 'thought_level') {
            reasoningValue = arguments['value'] as String;
          }
          return sessionResponse(sessionId);
        case 'session/prompt':
          final arguments = call.arguments as Map;
          return {
            'sessionId': arguments['sessionId'] ?? 'new-draft-session',
            'stopReason': 'end_turn',
          };
      }
      return <String, Object?>{};
    });
    // Keep the process-wide event subscription outside a widget test's fake
    // async zone so later tests can deliver native ACP updates to it as well.
    coordinator.ensureInitialized();
    await Future<void>.delayed(Duration.zero);
  });

  tearDown(() {
    coordinator.resetForTest();
    FilePickerPlatform.instance = originalPicker;
    LegacyTextLocalizer.clearResolvedLocale();
    messenger.setMockMethodCallHandler(assistChannel, null);
    messenger.setMockMethodCallHandler(agentChannel, null);
    for (final channel in otherChannels) {
      messenger.setMockMethodCallHandler(MethodChannel(channel), null);
    }
  });

  Future<void> pumpChat(
    WidgetTester tester,
    ConversationThreadTarget? target, {
    bool tour = false,
    bool settle = true,
    Size size = const Size(390, 844),
    GlobalKey<NavigatorState>? navigatorKey,
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          navigatorKey: navigatorKey,
          theme: AppTheme.lightTheme,
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: ChatPage(threadTarget: target, showFirstUseTour: tour),
        ),
      ),
    );
    if (settle) await tester.pumpAndSettle();
  }

  Future<void> pumpHistoryFrames(WidgetTester tester) async {
    // The loading label animates continuously, so a pending read must never
    // use pumpAndSettle. Allow I/O microtasks and several actual frames instead.
    for (var frame = 0; frame < 6; frame++) {
      await tester.pump(const Duration(milliseconds: 16));
    }
  }

  Map<String, Object?> historyConversation(
    int id,
    ConversationMode mode, {
    int messageCount = 1,
  }) => {
    'id': id,
    'title': '历史对话 $id',
    'mode': mode.storageValue,
    'agentId': mode == ConversationMode.agent ? 'xiaowan-acp' : null,
    'status': 1,
    'messageCount': messageCount,
    'createdAt': 1000,
    'updatedAt': 2000,
  };

  List<Map<String, Object?>> historyMessage(int conversationId) => [
    {
      'id': 'history-message-$conversationId',
      'type': 1,
      'user': 1,
      'content': {'text': '已加载历史消息 $conversationId'},
      'createAt': 1000,
    },
  ];

  void expectHistoryLoading() {
    expect(find.text('OmniBot'), findsOneWidget);
    expect(find.byType(ChatEmptyGreeting), findsNothing);
    expect(find.byType(ChatMessageList), findsNothing);
  }

  ChatInputArea composer(WidgetTester tester) =>
      tester.widget<ChatInputArea>(find.byType(ChatInputArea));

  NewConversationModeSelector selector(WidgetTester tester) =>
      tester.widget<NewConversationModeSelector>(
        find.byType(NewConversationModeSelector),
      );

  Future<void> chooseMode(WidgetTester tester, String id) async {
    await tester.tap(
      find.byKey(const ValueKey('new-conversation-mode-selector')),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(ValueKey('new-conversation-mode-option-$id')));
    await tester.pumpAndSettle();
  }

  Future<void> attachFile(
    WidgetTester tester,
    String name, {
    bool settle = true,
  }) async {
    picker.nextFiles = [
      AndroidPlatformFile(
        name: name,
        uri: Uri.parse('content://documents/$name'),
        bytesLength: 42,
      ),
    ];
    await composer(tester).onPickAttachment!();
    if (settle) {
      await tester.pumpAndSettle();
    } else {
      await tester.pump();
    }
  }

  void expectNoSessionAdmission() {
    expect(
      agentCalls.where(
        (call) => const {
          'agent/select',
          'session/new',
          'session/prompt',
        }.contains(call.method),
      ),
      isEmpty,
    );
    expect(
      assistCalls.where((call) => call.method == 'createConversation'),
      isEmpty,
    );
  }

  void expectNoPersistedDraft() {
    expect(conversations, isEmpty);
    expect(
      assistCalls.where(
        (call) => const {
          'createConversation',
          'updateConversation',
          'replaceConversationMessages',
        }.contains(call.method),
      ),
      isEmpty,
    );
    expect(
      agentCalls.where((call) => call.method == 'session/prompt'),
      isEmpty,
    );
  }

  Future<void> dismissConfigPanel(WidgetTester tester) async {
    await tester.tapAt(const Offset(360, 80));
    await tester.pumpAndSettle();
    expect(find.byType(AcpConfigPanel), findsNothing);
  }

  Future<void> disposeChat(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  }

  for (final value in ['off', 'none', 'on', 'High']) {
    testWidgets('ACP reasoning value $value survives settings and prompt admission', (
      tester,
    ) async {
      useRemoteRuntime = true;
      allowConversationCreation = true;
      reasoningOptions = ['off', 'none', 'on', 'High'];
      reasoningValue = value;
      await pumpChat(
        tester,
        const ConversationThreadTarget.newConversation(agentId: 'codex-remote'),
      );
      composer(tester).onTriggerSlashCommand!();
      await tester.pumpAndSettle();
      expect(agentCalls.where((call) => call.method == 'config/read'), isNotEmpty);
      composer(tester).controller.text = '保留原始档位';
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('chat-acp-config-button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('acp-config-thought_level')));
      await tester.pumpAndSettle();
      for (final choice in reasoningOptions) {
        expect(find.text(choice), findsOneWidget);
      }
      await tester.tap(find.byKey(ValueKey('acp-config-thought_level-value-$value')));
      await tester.pumpAndSettle();
      final write = agentCalls.lastWhere((call) => call.method == 'session/set_config_option');
      expect((write.arguments as Map)['configId'], 'thought_level');
      expect((write.arguments as Map)['value'], value);
      await dismissConfigPanel(tester);
      composer(tester).onSendMessage();
      await tester.pumpAndSettle();
      final prompt = agentCalls.singleWhere((call) => call.method == 'session/prompt');
      expect((prompt.arguments as Map)['effort'], value);
      await disposeChat(tester);
    });
  }

  testWidgets(
    'Pad resizing and pane controls preserve the draft without admitting a turn',
    (tester) async {
      addTearDown(tester.view.resetViewInsets);
      await pumpChat(
        tester,
        const ConversationThreadTarget.newConversation(
          mode: ConversationMode.agent,
          requestKey: 'pad-resize-draft',
          agentId: 'xiaowan-acp',
        ),
        size: const Size(768, 1024),
      );
      final controller = composer(tester).controller;
      controller.text = '旋转平板后仍保留的草稿';
      final focusNode = composer(tester).focusNode;
      Future<void> togglePane({required bool conversation}) async {
        final direct = find.byKey(
          ValueKey(
            conversation
                ? 'chat-app-bar-sidebar-button'
                : 'chat-app-bar-workspace-button',
          ),
        );
        if (direct.evaluate().isNotEmpty) {
          await tester.tap(direct);
        } else {
          await tester.tap(
            find.byKey(const ValueKey('chat-app-bar-layout-button')),
          );
          await tester.pumpAndSettle();
          await tester.tap(
            find.byKey(
              ValueKey(
                conversation
                    ? 'chat-app-bar-layout-conversations'
                    : 'chat-app-bar-layout-workspace',
              ),
            ),
          );
        }
        await tester.pumpAndSettle();
      }

      expect(
        tester
            .widget<ChatAppBar>(find.byType(ChatAppBar))
            .conversationPaneVisible,
        isTrue,
      );
      expect(
        tester.widget<ChatAppBar>(find.byType(ChatAppBar)).workspacePaneVisible,
        isFalse,
      );
      await togglePane(conversation: true);
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<ChatAppBar>(find.byType(ChatAppBar))
            .conversationPaneVisible,
        isFalse,
      );
      expect(
        tester.widget<ChatAppBar>(find.byType(ChatAppBar)).workspacePaneVisible,
        isFalse,
      );
      focusNode.requestFocus();
      await tester.pump();
      await tester.sendKeyDownEvent(LogicalKeyboardKey.controlLeft);
      await tester.sendKeyEvent(LogicalKeyboardKey.keyB);
      await tester.sendKeyUpEvent(LogicalKeyboardKey.controlLeft);
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<ChatAppBar>(find.byType(ChatAppBar))
            .conversationPaneVisible,
        isTrue,
      );
      await togglePane(conversation: false);
      await tester.pumpAndSettle();
      expect(
        tester.widget<ChatAppBar>(find.byType(ChatAppBar)).workspacePaneVisible,
        isTrue,
      );
      expect(
        tester
            .widget<ChatAppBar>(find.byType(ChatAppBar))
            .conversationPaneVisible,
        isFalse,
      );
      await togglePane(conversation: true);
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<ChatAppBar>(find.byType(ChatAppBar))
            .conversationPaneVisible,
        isTrue,
      );
      expect(
        tester.widget<ChatAppBar>(find.byType(ChatAppBar)).workspacePaneVisible,
        isFalse,
      );
      for (final size in [
        const Size(1366, 1024),
        const Size(960, 480),
        const Size(844, 390),
        const Size(600, 960),
        const Size(768, 1024),
      ]) {
        tester.view.physicalSize = size;
        await tester.pumpAndSettle();
        expect(composer(tester).controller, same(controller));
        expect(composer(tester).focusNode, same(focusNode));
        expect(controller.text, '旋转平板后仍保留的草稿');
        if (size.height < 500) {
          focusNode.requestFocus();
          await tester.pump();
          tester.view.viewInsets = const FakeViewPadding(bottom: 160);
          await tester.pumpAndSettle();
          expect(
            tester.getRect(find.byType(ChatInputArea)).bottom,
            lessThanOrEqualTo(size.height - 160),
          );
          expect(
            find
                    .byKey(const ValueKey('chat-app-bar-sidebar-button'))
                    .evaluate()
                    .isNotEmpty ||
                find
                    .byKey(const ValueKey('chat-app-bar-layout-button'))
                    .evaluate()
                    .isNotEmpty,
            isTrue,
          );
          tester.view.viewInsets = FakeViewPadding.zero;
          await tester.pumpAndSettle();
        }
        expect(tester.takeException(), isNull);
      }
      // A split-window resize can unmount the divider while the pointer is
      // still down. Returning to Pad layout must accept a fresh transaction.
      final resizeHandle = find.byKey(
        const ValueKey('chat-conversation-resize-handle'),
      );
      final drag = await tester.startGesture(tester.getCenter(resizeHandle));
      await drag.moveBy(const Offset(30, 0));
      await tester.pump();
      await drag.moveBy(const Offset(20, 0));
      await tester.pump();
      tester.view.physicalSize = const Size(600, 960);
      await tester.pumpAndSettle();
      expect(find.byType(ChatPaneResizeHandle), findsNothing);
      await drag.up();
      tester.view.physicalSize = const Size(768, 1024);
      await tester.pumpAndSettle();
      await togglePane(conversation: true);
      await togglePane(conversation: true);
      final dividerAnimation = tester.widget<AnimatedContainer>(
        find
            .ancestor(
              of: resizeHandle,
              matching: find.byType(AnimatedContainer),
            )
            .first,
      );
      expect(dividerAnimation.duration, const Duration(milliseconds: 280));
      expect(controller.text, '旋转平板后仍保留的草稿');
      focusNode.requestFocus();
      await tester.pump();
      tester.view.viewInsets = const FakeViewPadding(bottom: 340);
      await tester.pumpAndSettle();
      expect(
        tester.getRect(find.byType(ChatInputArea)).bottom,
        lessThanOrEqualTo(684),
      );
      expect(
        find
                .byKey(const ValueKey('chat-app-bar-sidebar-button'))
                .evaluate()
                .isNotEmpty ||
            find
                .byKey(const ValueKey('chat-app-bar-layout-button'))
                .evaluate()
                .isNotEmpty,
        isTrue,
      );
      expectNoSessionAdmission();
      expectNoPersistedDraft();
      await disposeChat(tester);
    },
  );

  testWidgets(
    'Pad history keeps transcript and tool details readable across columns',
    (tester) async {
      const conversationId = 806;
      conversations = [
        historyConversation(
          conversationId,
          ConversationMode.agent,
          messageCount: 3,
        ),
        historyConversation(807, ConversationMode.agent),
      ];
      historyPages[807] = historyMessage(807);
      historyPages[conversationId] = [
        ChatMessageModel(
          id: 'pad-answer',
          type: 1,
          user: 2,
          content: const {'text': '检查完成，当前工作区没有待提交改动。'},
          streamMeta: const {
            'parentTaskId': 'pad-turn',
            'kind': 'text_snapshot',
            'seq': 3,
            'isFinal': true,
          },
        ).toJson(),
        ChatMessageModel.cardMessage(
          const {
            'type': 'agent_tool_summary',
            'uiStyle': 'agent_tool',
            'toolType': 'terminal',
            'status': 'success',
            'toolTitle': '运行 git status',
            'summary': '命令执行完成',
            'terminalOutput':
                'On branch main\nnothing to commit, working tree clean',
          },
          id: 'pad-tool',
          streamMeta: const {
            'parentTaskId': 'pad-turn',
            'kind': 'tool_completed',
            'seq': 2,
            'isFinal': false,
          },
        ).toJson(),
        ...historyMessage(conversationId),
      ];
      await pumpChat(
        tester,
        const ConversationThreadTarget.existing(
          conversationId: conversationId,
          mode: ConversationMode.agent,
        ),
        size: const Size(1366, 1024),
      );
      expect(composer(tester).focusNode.hasFocus, isFalse);
      expect(tester.testTextInput.isVisible, isFalse);
      Future<void> shortcut(LogicalKeyboardKey key) async {
        await tester.sendKeyDownEvent(LogicalKeyboardKey.controlLeft);
        await tester.sendKeyEvent(key);
        await tester.sendKeyUpEvent(LogicalKeyboardKey.controlLeft);
        await tester.pumpAndSettle();
      }

      await shortcut(LogicalKeyboardKey.keyB);
      expect(
        tester
            .widget<ChatAppBar>(find.byType(ChatAppBar))
            .conversationPaneVisible,
        isFalse,
      );
      await shortcut(LogicalKeyboardKey.keyB);
      expect(
        tester
            .widget<ChatAppBar>(find.byType(ChatAppBar))
            .conversationPaneVisible,
        isTrue,
      );
      await shortcut(LogicalKeyboardKey.keyE);
      expect(
        tester.widget<ChatAppBar>(find.byType(ChatAppBar)).workspacePaneVisible,
        isFalse,
      );
      await shortcut(LogicalKeyboardKey.keyE);
      expect(
        tester.widget<ChatAppBar>(find.byType(ChatAppBar)).workspacePaneVisible,
        isTrue,
      );
      await shortcut(LogicalKeyboardKey.keyL);
      expect(composer(tester).focusNode.hasFocus, isTrue);
      composer(tester).focusNode.unfocus();
      await tester.pumpAndSettle();
      for (final size in [
        const Size(1366, 1024),
        const Size(768, 1024),
        const Size(960, 480),
      ]) {
        tester.view.physicalSize = size;
        await tester.pumpAndSettle();
        expect(
          find.textContaining('检查完成，当前工作区没有待提交改动。', findRichText: true),
          findsOneWidget,
        );
        expect(
          tester.getSize(find.byType(ChatMessageList)).width,
          lessThanOrEqualTo(840),
        );
        final header = find.byKey(const ValueKey('agent-run-summary-pad-turn'));
        await tester.ensureVisible(header);
        await tester.tap(header);
        await tester.pumpAndSettle();
        expect(find.text('执行命令'), findsOneWidget);
        expect(tester.takeException(), isNull);
        await tester.binding.handlePopRoute();
        await tester.pumpAndSettle();
      }
      await tester.tap(
        find.descendant(
          of: find.byType(HomeDrawer),
          matching: find.text('历史对话 807'),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byType(ChatPage), findsOneWidget);
      expect(find.text('已加载历史消息 807'), findsOneWidget);
      expect(
        tester
            .widget<Semantics>(
              find.byKey(const ValueKey('home-current-thread-agent:807')),
            )
            .properties
            .selected,
        isTrue,
      );
      expect(
        tester
            .widget<Semantics>(
              find.byKey(const ValueKey('home-current-thread-agent:806')),
            )
            .properties
            .selected,
        isFalse,
      );
      await tester.tap(find.byKey(const ValueKey('home-search-button')));
      await tester.pumpAndSettle();
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('home-close-search')), findsNothing);
      expect(find.byType(ChatPage), findsOneWidget);
      expect(
        agentCalls.where((call) => call.method == 'session/prompt'),
        isEmpty,
      );
      await disposeChat(tester);
    },
  );

  for (final keyboardAlreadyOpen in [false, true]) {
    testWidgets('Pad history search keeps the greeting at rest '
        '(keyboardAlreadyOpen=$keyboardAlreadyOpen)', (tester) async {
      addTearDown(tester.view.resetViewInsets);
      await pumpChat(
        tester,
        const ConversationThreadTarget.newConversation(
          mode: ConversationMode.agent,
          requestKey: 'pad-search-keyboard',
          agentId: 'xiaowan-acp',
        ),
        size: const Size(1069, 668),
      );
      final input = composer(tester);
      input.focusNode.unfocus();
      await tester.pumpAndSettle();
      final greeting = find.byType(ChatEmptyGreeting);
      AlignmentGeometry greetingAlignment() => tester
          .widget<AnimatedAlign>(
            find.ancestor(of: greeting, matching: find.byType(AnimatedAlign)),
          )
          .alignment;
      const restingAlignment = Alignment(0, -.18);
      final restingPosition = tester.getTopLeft(greeting);
      final restingComposerBottom = tester
          .getRect(find.byType(ChatInputArea))
          .bottom;
      expect(greetingAlignment(), restingAlignment);

      if (keyboardAlreadyOpen) {
        input.focusNode.requestFocus();
        await tester.pump();
        tester.view.viewInsets = const FakeViewPadding(bottom: 280);
        await tester.pumpAndSettle();
        expect(greetingAlignment(), Alignment.topLeft);
      }

      await tester.tap(find.byKey(const ValueKey('home-search-button')));
      await tester.pumpAndSettle();
      final searchFocus = tester
          .widget<TextField>(
            find.descendant(
              of: find.byType(HomeDrawer),
              matching: find.byType(TextField),
            ),
          )
          .focusNode!;
      expect(searchFocus.hasFocus, isTrue);
      expect(input.focusNode.hasFocus, isFalse);
      expect(greetingAlignment(), restingAlignment);

      for (final inset
          in keyboardAlreadyOpen ? [280.0] : [40.0, 140.0, 280.0]) {
        tester.view.viewInsets = FakeViewPadding(bottom: inset);
        await tester.pumpAndSettle();
        expect(greetingAlignment(), restingAlignment);
        expect(tester.getTopLeft(greeting).dx, closeTo(restingPosition.dx, .5));
        expect(tester.getTopLeft(greeting).dy, closeTo(restingPosition.dy, .5));
        expect(
          tester.getRect(find.byType(ChatInputArea)).bottom,
          closeTo(restingComposerBottom, .5),
        );
      }

      // Focus ownership can change without any new keyboard-metrics event.
      input.focusNode.requestFocus();
      await tester.pumpAndSettle();
      expect(greetingAlignment(), Alignment.topLeft);
      searchFocus.requestFocus();
      await tester.pumpAndSettle();
      expect(greetingAlignment(), restingAlignment);
      await tester.tap(find.byKey(const ValueKey('home-close-search')));
      await tester.pumpAndSettle();
      expect(greetingAlignment(), restingAlignment);

      // Unmounting the focused sidebar must not notify during tree disposal.
      await tester.tap(find.byKey(const ValueKey('home-search-button')));
      await tester.pumpAndSettle();
      tester.view.physicalSize = const Size(668, 1069);
      await tester.pumpAndSettle();
      expect(find.byType(HomeDrawer), findsNothing);
      expect(greetingAlignment(), restingAlignment);
      expect(tester.takeException(), isNull);
      expectNoSessionAdmission();
      expectNoPersistedDraft();
      await disposeChat(tester);
    });
  }

  for (final existing in [false, true]) {
    testWidgets('chat keeps its editor and messages above the keyboard without rebuilding page content '
        '(existing=$existing)', (tester) async {
      addTearDown(tester.view.resetViewInsets);
      const conversationId = 805;
      if (existing) {
        conversations = [
          historyConversation(conversationId, ConversationMode.agent),
        ];
        historyPages[conversationId] = historyMessage(conversationId);
      }
      await pumpChat(
        tester,
        existing
            ? const ConversationThreadTarget.existing(
                conversationId: conversationId,
                mode: ConversationMode.agent,
              )
            : const ConversationThreadTarget.newConversation(
                mode: ConversationMode.agent,
                requestKey: 'keyboard-layout-regression',
                agentId: 'xiaowan-acp',
              ),
      );
      // New drafts autofocus after bootstrap. Close that initial focus so the
      // test exercises a real tap from the resting capsule in both cases.
      composer(tester).focusNode.unfocus();
      await tester.pumpAndSettle();
      final originalComposer = composer(tester);
      expect(originalComposer.focusNode.hasFocus, isFalse);
      final originalAppBar = tester.widget<ChatAppBar>(find.byType(ChatAppBar));
      final originalMessages = tester.widget<ChatMessageList>(
        find.byType(ChatMessageList),
      );
      final editor = tester.state<EditableTextState>(find.byType(EditableText));
      final field = find.byType(TextField);
      agentCalls.clear();
      assistCalls.clear();
      await tester.tapAt(tester.getCenter(field));
      await tester.pump();
      expect(originalComposer.focusNode.hasFocus, isTrue);
      expect(tester.testTextInput.isVisible, isTrue);

      for (final inset in [40.0, 120.0, 220.0, 300.0]) {
        tester.view.viewInsets = FakeViewPadding(bottom: inset);
        await tester.pump(const Duration(milliseconds: 60));
        expect(tester.getRect(field).bottom, lessThanOrEqualTo(844 - inset));
        expect(composer(tester), same(originalComposer));
        expect(
          tester.widget<ChatAppBar>(find.byType(ChatAppBar)),
          same(originalAppBar),
        );
        expect(
          tester.state<EditableTextState>(find.byType(EditableText)),
          same(editor),
        );
        expect(
          tester.widget<ChatMessageList>(find.byType(ChatMessageList)),
          same(originalMessages),
        );
        if (existing) {
          expect(find.text('已加载历史消息 $conversationId'), findsOneWidget);
        }
      }
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expectNoSessionAdmission();
      if (!existing) expectNoPersistedDraft();
      await disposeChat(tester);
    });
  }

  for (final mode in [ConversationMode.chatOnly, ConversationMode.agent]) {
    testWidgets(
      '${mode.storageValue} history shows OmniBot from its first frame through metadata and message reads',
      (tester) async {
        const conversationId = 801;
        historyMetadataGate = Completer<void>();
        historyPageGates[conversationId] = Completer<void>();
        conversations = [historyConversation(conversationId, mode)];
        historyPages[conversationId] = historyMessage(conversationId);

        await pumpChat(
          tester,
          ConversationThreadTarget.existing(
            conversationId: conversationId,
            mode: mode,
          ),
          settle: false,
        );
        expectHistoryLoading();
        await pumpHistoryFrames(tester);
        expectHistoryLoading();
        expect(
          assistCalls.where((call) => call.method == 'getConversations'),
          isNotEmpty,
        );
        expect(
          assistCalls.where(
            (call) => call.method == 'getConversationMessagesPaged',
          ),
          isEmpty,
        );

        historyMetadataGate!.complete();
        await pumpHistoryFrames(tester);
        expectHistoryLoading();
        expect(
          assistCalls.where(
            (call) => call.method == 'getConversationMessagesPaged',
          ),
          isNotEmpty,
        );

        historyPageGates[conversationId]!.complete();
        await tester.pumpAndSettle();
        expect(find.text('已加载历史消息 $conversationId'), findsOneWidget);
        expect(find.text('OmniBot'), findsNothing);
        expect(find.byType(ChatEmptyGreeting), findsNothing);
        expect(tester.takeException(), isNull);
        await disposeChat(tester);
      },
    );
  }

  for (final eventBeforeLoad in [false, true]) {
    testWidgets(
      'remote history awaits its single hydration read with early ACP update: $eventBeforeLoad',
      (tester) async {
        useRemoteRuntime = true;
        remoteSessionLoadGate = Completer<void>();
        remoteHistoryReadGate = Completer<void>();
        await pumpChat(
          tester,
          const ConversationThreadTarget.agentSession(
            sessionId: 'remote-history-session',
            agentId: 'codex-acp',
          ),
          settle: false,
        );
        await pumpHistoryFrames(tester);
        expectHistoryLoading();
        expect(
          agentCalls.where((call) => call.method == 'session/load'),
          hasLength(1),
        );

        if (eventBeforeLoad) {
          // Session-scoped command metadata can arrive during session/load.
          // It selects the already reserved remote runtime without admitting
          // a turn or installing any message, and starts history hydration.
          await tester.runAsync(() async {
            await messenger.handlePlatformMessage(
              'cn.com.omnimind.bot/AgentRuntimeEvents',
              const StandardMethodCodec().encodeSuccessEnvelope({
                'method': 'session/update',
                'threadId': 'remote-history-session',
                'params': {
                  'sessionId': 'remote-history-session',
                  'update': {
                    'sessionUpdate': 'available_commands_update',
                    'availableCommands': <Object?>[],
                  },
                },
              }),
              (_) {},
            );
            await Future<void>.delayed(Duration.zero);
          });
          await pumpHistoryFrames(tester);
          expect(
            agentCalls.where(
              (call) =>
                  call.method == 'session/load' &&
                  (call.arguments as Map)['includeHistory'] == true,
            ),
            hasLength(1),
          );
          expectHistoryLoading();
        }

        // A metadata-only load response must await the same pending read
        // whether hydration started above or starts after this response.
        remoteSessionLoadGate!.complete();
        await pumpHistoryFrames(tester);
        expectHistoryLoading();
        expect(
          agentCalls.where(
            (call) =>
                call.method == 'session/load' &&
                (call.arguments as Map)['includeHistory'] == true,
          ),
          hasLength(1),
        );

        remoteHistoryReadGate!.complete();
        await tester.pumpAndSettle();
        expect(find.text('远程会话恢复的历史消息'), findsOneWidget);
        expect(find.text('OmniBot'), findsNothing);
        expect(find.byType(ChatEmptyGreeting), findsNothing);
        expect(
          agentCalls.where((call) => call.method == 'session/load'),
          hasLength(2),
        );
        await disposeChat(tester);
      },
    );
  }

  testWidgets('empty local history waits for its existing cloud session', (
    tester,
  ) async {
    const conversationId = 806;
    historySessionGate = Completer<void>();
    conversations = [
      {
        ...historyConversation(conversationId, ConversationMode.agent),
        'agentId': 'na-cloud',
      },
    ];
    historySessionResponse = {
      'threadId': 'na-existing-session',
      'agentId': 'na-cloud',
      'thread': {
        'id': 'na-existing-session',
        'agentId': 'na-cloud',
        'turns': [
          {
            'id': 'cloud-history-turn',
            'stopReason': 'end_turn',
            'items': [
              {
                'id': 'cloud-history-message',
                'type': 'userMessage',
                'content': '云端恢复的历史消息',
              },
            ],
          },
        ],
      },
    };
    await pumpChat(
      tester,
      const ConversationThreadTarget.existing(
        conversationId: conversationId,
        mode: ConversationMode.agent,
        agentId: 'na-cloud',
      ),
      settle: false,
    );
    await pumpHistoryFrames(tester);
    expect(
      assistCalls.where(
        (call) => call.method == 'getConversationMessagesPaged',
      ),
      isNotEmpty,
    );
    expect(
      agentCalls.where(
        (call) =>
            call.method == 'session/load' &&
            (call.arguments as Map)['conversationId'] == conversationId,
      ),
      hasLength(1),
    );
    expectHistoryLoading();

    historySessionGate!.complete();
    await tester.pumpAndSettle();
    expect(find.text('云端恢复的历史消息'), findsOneWidget);
    expect(find.text('OmniBot'), findsNothing);
    expect(find.byType(ChatEmptyGreeting), findsNothing);
    await disposeChat(tester);
  });

  Map<String, Object?> naHistory(
    String text, {
    String status = 'completed',
    String turn = 'other-phone-turn',
  }) => {
    'threadId': 'na:fixture:conversation',
    'agentId': 'na-cloud',
    'thread': {
      'id': 'na:fixture:conversation',
      'agentId': 'na-cloud',
      'turns': [
        {
          'id': turn,
          'status': status,
          'stopReason': status == 'completed' ? 'end_turn' : null,
          'items': [
            {'id': '$turn-reply', 'type': 'agentMessage', 'text': text},
          ],
        },
      ],
    },
  };

  Future<void> emitNaChange(
    WidgetTester tester, {
    int? id,
    String? owner,
  }) async {
    await tester.runAsync(() async {
      await messenger.handlePlatformMessage(
        'cn.com.omnimind.bot/AssistCoreEvent',
        const StandardMethodCodec().encodeMethodCall(
          MethodCall('onConversationListChanged', {
            'reason': 'na_cloud_history_changed',
            if (id != null) 'conversation': {'id': id, 'agentId': 'na-cloud'},
            if (owner != null) 'naOwnerPrefix': owner,
          }),
        ),
        (_) {},
      );
      await Future<void>.delayed(Duration.zero);
    });
    await pumpHistoryFrames(tester);
  }

  Future<void> openNaHistory(WidgetTester tester) async {
    AssistsMessageService.hasNaHistoryOwnerSnapshot = false;
    conversations = [
      {
        ...historyConversation(806, ConversationMode.agent),
        'agentId': 'na-cloud',
      },
    ];
    historySessionResponse = naHistory('另一台手机的旧回复');
    await pumpChat(
      tester,
      const ConversationThreadTarget.existing(
        conversationId: 806,
        mode: ConversationMode.agent,
        agentId: 'na-cloud',
      ),
    );
    expect(
      coordinator
          .runtimeFor(conversationId: 806, mode: kChatRuntimeModeAgent)!
          .messages
          .where((row) => row.text == '另一台手机的旧回复'),
      hasLength(1),
    );
    agentCalls.clear();
  }

  testWidgets('Na imports earlier cloud pages after showing the latest page', (
    tester,
  ) async {
    AssistsMessageService.hasNaHistoryOwnerSnapshot = false;
    conversations = [
      {
        ...historyConversation(806, ConversationMode.agent),
        'agentId': 'na-cloud',
      },
    ];
    historySessionResponse = {
      ...naHistory('最新一页'),
      'nextRunCursor': 'before-recent',
    };
    naHistoryPages = {
      'before-recent': {
        ...naHistory('中间一页', turn: 'middle'),
        'nextRunCursor': 'before-middle',
      },
      'before-middle': naHistory('最早一页', turn: 'oldest'),
    };
    await pumpChat(
      tester,
      const ConversationThreadTarget.existing(
        conversationId: 806,
        mode: ConversationMode.agent,
        agentId: 'na-cloud',
      ),
    );
    final runtime = coordinator.runtimeFor(
      conversationId: 806,
      mode: kChatRuntimeModeAgent,
    )!;
    expect(
      runtime.messages.map((row) => row.text),
      containsAll(['最新一页', '中间一页', '最早一页']),
    );
    expect(
      agentCalls
          .where(
            (call) =>
                call.method == 'session/load' &&
                (call.arguments as Map)['historyCursor'] != null,
          )
          .map((call) => (call.arguments as Map)['historyCursor']),
      ['before-recent', 'before-middle'],
    );
    agentCalls.clear();
    await emitNaChange(tester, id: 806);
    expect(
      agentCalls.where((call) => call.method == 'session/load'),
      hasLength(1),
    );
    expect(
      runtime.messages.map((row) => row.text),
      containsAll(['最新一页', '中间一页', '最早一页']),
    );
    await disposeChat(tester);
  });

  testWidgets(
    'Na resume catches messages written while this phone was backgrounded',
    (tester) async {
      await openNaHistory(tester);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      await tester.pump();
      historySessionResponse = naHistory('后台期间另一手机的新消息');
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      final runtime = coordinator.runtimeFor(
        conversationId: 806,
        mode: kChatRuntimeModeAgent,
      )!;
      expect(
        runtime.messages.where((row) => row.text == '后台期间另一手机的新消息'),
        hasLength(1),
      );
      expect(
        agentCalls.where((call) => call.method == 'session/prompt'),
        isEmpty,
      );
      await disposeChat(tester);
    },
  );

  testWidgets(
    'Na invalidation imports another phone output once without a local prompt',
    (tester) async {
      await openNaHistory(tester);
      historySessionResponse = naHistory('另一台手机正在继续输出', status: 'running');
      await emitNaChange(tester, id: 806);
      await emitNaChange(tester, id: 806);
      final runtime = coordinator.runtimeFor(
        conversationId: 806,
        mode: kChatRuntimeModeAgent,
      )!;
      expect(
        runtime.messages.where((row) => row.text == '另一台手机正在继续输出'),
        hasLength(1),
      );
      expect(runtime.hasInFlightTask, false);
      expect(runtime.activeAcpTurnId, isNull);
      expect(
        agentCalls.where((call) => call.method == 'session/prompt'),
        isEmpty,
      );
      historySessionResponse = naHistory('另一台手机已完成');
      await emitNaChange(tester, id: 806);
      expect(
        runtime.messages.where((row) => row.text == '另一台手机已完成'),
        hasLength(1),
      );
      await disposeChat(tester);
    },
  );

  testWidgets('Na reconnect continues an interrupted earlier history page', (
    tester,
  ) async {
    AssistsMessageService.hasNaHistoryOwnerSnapshot = false;
    conversations = [
      {
        ...historyConversation(806, ConversationMode.agent),
        'agentId': 'na-cloud',
      },
    ];
    historySessionResponse = {
      ...naHistory('最近消息'),
      'nextRunCursor': 'before-recent',
    };
    naHistoryPages = {'before-recent': naHistory('断网前尚未加载的旧消息', turn: 'older')};
    failedNaHistoryCursors.add('before-recent');
    await pumpChat(
      tester,
      const ConversationThreadTarget.existing(
        conversationId: 806,
        mode: ConversationMode.agent,
        agentId: 'na-cloud',
      ),
    );
    final runtime = coordinator.runtimeFor(
      conversationId: 806,
      mode: kChatRuntimeModeAgent,
    )!;
    expect(
      runtime.messages.map((row) => row.text),
      isNot(contains('断网前尚未加载的旧消息')),
    );
    failedNaHistoryCursors.clear();
    await emitNaChange(tester, id: 806);
    await tester.pumpAndSettle();
    expect(
      runtime.messages.map((row) => row.text),
      containsAll(['最近消息', '断网前尚未加载的旧消息']),
    );
    await disposeChat(tester);
  });

  testWidgets(
    'Na late initial snapshot cannot roll back newer completed remote output',
    (tester) async {
      AssistsMessageService.hasNaHistoryOwnerSnapshot = false;
      conversations = [
        {
          ...historyConversation(806, ConversationMode.agent),
          'agentId': 'na-cloud',
        },
      ];
      historyPages[806] = historyMessage(806);
      historySessionResponse = {
        ...naHistory('过时的未完成片段', status: 'running'),
        'eventCursor': 10,
      };
      naHistoryStatusGate = Completer<void>();
      await pumpChat(
        tester,
        const ConversationThreadTarget.existing(
          conversationId: 806,
          mode: ConversationMode.agent,
          agentId: 'na-cloud',
        ),
        settle: false,
      );
      await pumpHistoryFrames(tester);
      historySessionResponse = {...naHistory('更新且已完成的回复'), 'eventCursor': 20};
      await emitNaChange(tester, id: 806);
      final runtime = coordinator.runtimeFor(
        conversationId: 806,
        mode: kChatRuntimeModeAgent,
      )!;
      expect(runtime.messages.map((row) => row.text), contains('更新且已完成的回复'));
      naHistoryStatusGate!.complete();
      await tester.pumpAndSettle();
      expect(
        runtime.messages.map((row) => row.text),
        isNot(contains('过时的未完成片段')),
      );
      expect(
        runtime.messages
            .singleWhere((row) => row.text == '更新且已完成的回复')
            .streamMeta?['isFinal'],
        true,
      );
      await disposeChat(tester);
    },
  );

  testWidgets(
    'Na reads preserve server activity time rather than the synchronization time',
    (tester) async {
      await openNaHistory(tester);
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      expect(conversations.single['updatedAt'], 2000);
      historySessionResponse = naHistory('更晚的服务端消息');
      (historySessionResponse!['thread'] as Map)['updatedAt'] =
          '2026-10-01T00:00:00Z';
      await emitNaChange(tester, id: 806);
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      expect(
        conversations.single['updatedAt'],
        DateTime.utc(2026, 10, 1).millisecondsSinceEpoch,
      );
      await disposeChat(tester);
    },
  );

  testWidgets('Na ignores other conversation and old owner invalidations', (
    tester,
  ) async {
    await openNaHistory(tester);
    AssistsMessageService.hasNaHistoryOwnerSnapshot = true;
    AssistsMessageService.visibleNaOwnerPrefix = 'new-owner';
    await emitNaChange(tester, id: 807);
    await emitNaChange(tester, id: 806, owner: 'old-owner');
    expect(agentCalls.where((call) => call.method == 'session/load'), isEmpty);
    expect(
      coordinator
          .runtimeFor(conversationId: 806, mode: kChatRuntimeModeAgent)!
          .messages
          .map((row) => row.text),
      contains('另一台手机的旧回复'),
    );
    AssistsMessageService.hasNaHistoryOwnerSnapshot = false;
    await disposeChat(tester);
  });

  testWidgets(
    'Na coalesces changes during a pending history read and catches the latest',
    (tester) async {
      await openNaHistory(tester);
      historySessionResponse = naHistory('中途快照');
      historySessionGate = Completer<void>();
      await emitNaChange(tester, id: 806);
      historySessionResponse = naHistory('读取期间又有新消息');
      await emitNaChange(tester, id: 806);
      await emitNaChange(tester, id: 806);
      expect(
        agentCalls.where((call) => call.method == 'session/load'),
        hasLength(1),
      );
      historySessionGate!.complete();
      await tester.pumpAndSettle();
      expect(
        agentCalls.where((call) => call.method == 'session/load'),
        hasLength(2),
      );
      expect(
        coordinator
            .runtimeFor(conversationId: 806, mode: kChatRuntimeModeAgent)!
            .messages
            .map((row) => row.text),
        contains('读取期间又有新消息'),
      );
      await disposeChat(tester);
    },
  );

  testWidgets(
    'Na discards a history response after account visibility changes',
    (tester) async {
      await openNaHistory(tester);
      historySessionResponse = naHistory('旧账号的迟到内容');
      historySessionGate = Completer<void>();
      await emitNaChange(tester, id: 806);
      AssistsMessageService.conversationVisibilityRevision++;
      historySessionGate!.complete();
      await tester.pumpAndSettle();
      expect(
        coordinator
            .runtimeFor(conversationId: 806, mode: kChatRuntimeModeAgent)!
            .messages
            .map((row) => row.text),
        isNot(contains('旧账号的迟到内容')),
      );
      expect(
        coordinator
            .runtimeFor(conversationId: 806, mode: kChatRuntimeModeAgent)!
            .messages
            .map((row) => row.text),
        contains('另一台手机的旧回复'),
      );
      await disposeChat(tester);
    },
  );

  testWidgets(
    'restoring an unresolved target keeps the welcome screen hidden',
    (tester) async {
      const conversationId = 802;
      restoredTargetGate = Completer<void>();
      restoredTarget = const ConversationThreadTarget.existing(
        conversationId: conversationId,
        mode: ConversationMode.chatOnly,
      );
      conversations = [
        historyConversation(conversationId, ConversationMode.chatOnly),
      ];
      historyPages[conversationId] = historyMessage(conversationId);

      await pumpChat(tester, null, settle: false);
      expectHistoryLoading();
      await pumpHistoryFrames(tester);
      expectHistoryLoading();
      expect(
        assistCalls.where(
          (call) =>
              call.method == 'conversationSelection' &&
              (call.arguments as Map)['operation'] == 'getLast',
        ),
        isNotEmpty,
      );

      restoredTargetGate!.complete();
      await tester.pumpAndSettle();
      expect(find.text('已加载历史消息 $conversationId'), findsOneWidget);
      expect(find.byType(ChatEmptyGreeting), findsNothing);
      await disposeChat(tester);
    },
  );

  testWidgets('late history completion cannot dismiss the next target loader', (
    tester,
  ) async {
    const firstId = 803;
    const secondId = 804;
    for (final id in [firstId, secondId]) {
      conversations.add(historyConversation(id, ConversationMode.chatOnly));
      historyPages[id] = historyMessage(id);
      historyPageGates[id] = Completer<void>();
    }
    await pumpChat(
      tester,
      const ConversationThreadTarget.existing(
        conversationId: firstId,
        mode: ConversationMode.chatOnly,
      ),
      settle: false,
    );
    await pumpHistoryFrames(tester);
    expectHistoryLoading();
    final originalPageState = tester.state(find.byType(ChatPage));

    await pumpChat(
      tester,
      const ConversationThreadTarget.existing(
        conversationId: secondId,
        mode: ConversationMode.chatOnly,
      ),
      settle: false,
    );
    expect(tester.state(find.byType(ChatPage)), same(originalPageState));
    await pumpHistoryFrames(tester);
    expectHistoryLoading();
    expect(
      assistCalls
          .where((call) => call.method == 'getConversationMessagesPaged')
          .map((call) => (call.arguments as Map)['conversationId']),
      containsAll([firstId, secondId]),
    );

    historyPageGates[firstId]!.complete();
    await pumpHistoryFrames(tester);
    expectHistoryLoading();
    expect(find.text('已加载历史消息 $firstId'), findsNothing);

    historyPageGates[secondId]!.complete();
    await tester.pumpAndSettle();
    expect(find.text('已加载历史消息 $secondId'), findsOneWidget);
    expect(find.text('已加载历史消息 $firstId'), findsNothing);
    expect(find.byType(ChatEmptyGreeting), findsNothing);
    await disposeChat(tester);
  });

  for (final failHistory in [false, true]) {
    testWidgets(
      '${failHistory ? 'failed' : 'empty'} existing history finishes loading without a new-chat greeting',
      (tester) async {
        const conversationId = 805;
        conversations = [
          historyConversation(
            conversationId,
            ConversationMode.chatOnly,
            messageCount: failHistory ? 1 : 0,
          ),
        ];
        historyPageGates[conversationId] = Completer<void>();
        if (failHistory) failedHistoryPages.add(conversationId);
        await pumpChat(
          tester,
          const ConversationThreadTarget.existing(
            conversationId: conversationId,
            mode: ConversationMode.chatOnly,
          ),
          settle: false,
        );
        await pumpHistoryFrames(tester);
        expectHistoryLoading();
        historyPageGates[conversationId]!.complete();
        await tester.pumpAndSettle();
        expect(find.text('OmniBot'), findsNothing);
        expect(find.byType(ChatEmptyGreeting), findsNothing);
        expect(find.byType(ChatMessageList), findsOneWidget);
        await disposeChat(tester);
      },
    );
  }

  testWidgets('a new conversation keeps its greeting and composer', (
    tester,
  ) async {
    await pumpChat(
      tester,
      const ConversationThreadTarget.newConversation(agentId: 'xiaowan-acp'),
    );
    expect(find.byType(ChatEmptyGreeting), findsOneWidget);
    expect(find.text('OmniBot'), findsNothing);
    expect(find.byType(ChatInputArea), findsOneWidget);
    expectNoSessionAdmission();
    await disposeChat(tester);
  });

  testWidgets(
    'existing history has no new-conversation selector or autofocus',
    (tester) async {
      conversations = [
        {
          'id': 701,
          'title': '已有对话',
          'mode': 'chat_only',
          'status': 1,
          'messageCount': 0,
          'createdAt': 1000,
          'updatedAt': 2000,
        },
      ];
      await pumpChat(
        tester,
        const ConversationThreadTarget.existing(
          conversationId: 701,
          mode: ConversationMode.chatOnly,
        ),
      );
      expect(find.byType(ChatInputArea), findsOneWidget);
      expect(
        find.byKey(const ValueKey('new-conversation-mode-selector')),
        findsNothing,
      );
      expect(composer(tester).focusNode.hasFocus, isFalse);
      expect(
        assistCalls.where((call) => call.method == 'createConversation'),
        isEmpty,
      );
      expect(tester.takeException(), isNull);
      await disposeChat(tester);
    },
  );

  for (final agentId in ['xiaowan-acp', 'codex-acp']) {
    testWidgets('new $agentId draft selects its target and focuses once', (
      tester,
    ) async {
      await pumpChat(
        tester,
        ConversationThreadTarget.newConversation(agentId: agentId),
      );

      expect(selector(tester).selectedId, agentId);
      expect(composer(tester).focusNode.hasFocus, isTrue);
      expect(tester.testTextInput.isVisible, isTrue);
      expectNoSessionAdmission();

      composer(tester).focusNode.unfocus();
      composer(tester).controller.text = '仍在编辑草稿';
      await tester.pumpAndSettle();
      expect(composer(tester).focusNode.hasFocus, isFalse);
      expect(selector(tester).selectedId, agentId);
      await disposeChat(tester);
    });
  }

  for (final status in ['unchecked', 'offline']) {
    testWidgets('production Na remains selectable before connection: $status', (
      tester,
    ) async {
      agents.firstWhere((agent) => agent['id'] == 'na-cloud').addAll({
        'status': status,
        'configurationAvailable': false,
      });
      await pumpChat(
        tester,
        const ConversationThreadTarget.newConversation(agentId: 'xiaowan-acp'),
      );
      final na = selector(tester).agentModes
          .firstWhere((agent) => agent.id == 'na-cloud');
      expect(na.installed, isTrue);
      expect(na.status, status);
      expect(na.isAvailable, isTrue);
      await chooseMode(tester, 'na-cloud');
      expect(selector(tester).selectedId, 'na-cloud');
      expect(
        tester
            .widget<ChatAppBar>(find.byType(ChatAppBar))
            .actions
            .any((action) => action.label == '云端连接'),
        isFalse,
      );
      expectNoSessionAdmission();
      await disposeChat(tester);
    });
  }

  testWidgets(
    'existing history config resolves its conversation without an in-memory session',
    (tester) async {
      final now = DateTime.now().millisecondsSinceEpoch;
      conversations = [
        {
          'id': 77,
          'title': 'Existing chat',
          'mode': 'chat_only',
          'status': 0,
          'messageCount': 1,
          'createdAt': now,
          'updatedAt': now,
        },
      ];
      await pumpChat(
        tester,
        const ConversationThreadTarget.existing(
          conversationId: 77,
          mode: ConversationMode.chatOnly,
          agentId: 'opencode-acp',
        ),
      );
      final config = tester.widget<AcpConfigButton>(
        find.byType(AcpConfigButton),
      );
      agentCalls.clear();
      final response = await config.load();
      expect(response['sessionId'], 'existing-session-77');
      final load = agentCalls.singleWhere(
        (call) => call.method == 'session/load',
      );
      expect((load.arguments as Map)['conversationId'], 77);
      expect((load.arguments as Map)['agentId'], 'xiaowan-acp');
      expect(agentCalls.where((call) => call.method == 'session/new'), isEmpty);
      await disposeChat(tester);
      expect(
        agentCalls.where((call) => call.method == 'session/close'),
        isEmpty,
      );
      expect(
        assistCalls.where((call) => call.method == 'createConversation'),
        isEmpty,
      );
    },
  );

  testWidgets(
    'Xiaowan config stays an unpersisted draft and first send reuses its session',
    (tester) async {
      // Permit writes so the test catches an eager empty conversation rather
      // than depending on a rejected create call to keep history empty.
      allowConversationCreation = true;
      await pumpChat(
        tester,
        const ConversationThreadTarget.newConversation(agentId: 'xiaowan-acp'),
      );
      await tester.tap(find.byKey(const ValueKey('chat-acp-config-button')));
      await tester.pumpAndSettle();

      expect(find.byType(AcpConfigPanel), findsOneWidget);
      expect(selector(tester).selectedId, 'xiaowan-acp');
      expect(selector(tester).enabled, isTrue);
      expectNoPersistedDraft();
      final createdSession = agentCalls.singleWhere(
        (call) => call.method == 'session/new',
      );
      expect((createdSession.arguments as Map)['agentId'], 'xiaowan-acp');
      expect(
        (createdSession.arguments as Map).containsKey('conversationId'),
        isFalse,
      );

      final config = tester.widget<AcpConfigButton>(
        find.byType(AcpConfigButton),
      );
      final refreshed = await config.refresh!();
      await tester.pumpAndSettle();
      expect(refreshed['sessionId'], 'new-draft-session');
      final refresh = agentCalls.lastWhere(
        (call) => call.method == 'session/load',
      );
      expect((refresh.arguments as Map)['sessionId'], 'new-draft-session');
      expect((refresh.arguments as Map)['refreshConfig'], isTrue);
      expect((refresh.arguments as Map).containsKey('conversationId'), isFalse);
      await tester.tap(find.byKey(const ValueKey('acp-config-notifications')));
      await tester.pumpAndSettle();
      final write = agentCalls.singleWhere(
        (call) => call.method == 'session/set_config_option',
      );
      expect((write.arguments as Map)['sessionId'], 'new-draft-session');
      expect((write.arguments as Map)['value'], isTrue);
      expect((write.arguments as Map).containsKey('conversationId'), isFalse);
      expectNoPersistedDraft();
      expect(selector(tester).enabled, isTrue);

      await dismissConfigPanel(tester);
      composer(tester).controller.text = '配置后首次发送';
      composer(tester).onSendMessage();
      await tester.pumpAndSettle();

      final creations = assistCalls.where(
        (call) => call.method == 'createConversation',
      );
      final prompts = agentCalls.where(
        (call) => call.method == 'session/prompt',
      );
      expect(creations, hasLength(1));
      expect(conversations, hasLength(1));
      expect(prompts, hasLength(1));
      expect((prompts.single.arguments as Map)['conversationId'], 901);
      expect(
        (prompts.single.arguments as Map)['sessionId'],
        'new-draft-session',
      );
      expect((prompts.single.arguments as Map)['text'], '配置后首次发送');
      expect(
        agentCalls.where((call) => call.method == 'session/new'),
        hasLength(1),
      );
      expect(sessionConfig['new-draft-session']?['notifications'], isTrue);
      expect(find.byType(NewConversationModeSelector), findsNothing);
      await disposeChat(tester);
    },
  );

  testWidgets(
    'command and Plan configuration do not persist an empty Xiaowan draft',
    (tester) async {
      allowConversationCreation = true;
      await pumpChat(
        tester,
        const ConversationThreadTarget.newConversation(agentId: 'xiaowan-acp'),
      );
      composer(tester).onTriggerSlashCommand!();
      await tester.pumpAndSettle();
      expect(composer(tester).controller.text, '/');
      expect(
        agentCalls.where((call) => call.method == 'session/new'),
        hasLength(1),
      );
      expectNoPersistedDraft();

      composer(tester).controller.text = '/plan';
      composer(tester).onSendMessage();
      await tester.pumpAndSettle();
      final planWrite = agentCalls.singleWhere(
        (call) =>
            call.method == 'session/set_config_option' &&
            (call.arguments as Map)['configId'] == 'collaboration_mode',
      );
      expect((planWrite.arguments as Map)['sessionId'], 'new-draft-session');
      expect((planWrite.arguments as Map)['value'], 'plan');
      expectNoPersistedDraft();
      expect(selector(tester).enabled, isTrue);

      await chooseMode(tester, 'codex-acp');
      expect(selector(tester).selectedId, 'codex-acp');
      expectNoPersistedDraft();
      await disposeChat(tester);
    },
  );

  for (final owner in ['xiaowan-acp', 'codex-remote']) {
    for (final leave in ['dispose', 'switch-agent', 'switch-mode']) {
      testWidgets(
        'unused config session closes with its creation owner: $owner/$leave',
        (tester) async {
          useRemoteRuntime = owner == 'codex-remote';
          await pumpChat(
            tester,
            ConversationThreadTarget.newConversation(
              agentId: owner,
              agentRuntime: owner == 'codex-remote' ? 'remote' : 'local',
            ),
          );
          await tester.tap(
            find.byKey(const ValueKey('chat-acp-config-button')),
          );
          await tester.pumpAndSettle();
          final created = agentCalls.singleWhere(
            (call) => call.method == 'session/new',
          );
          expect((created.arguments as Map)['agentId'], owner);
          await dismissConfigPanel(tester);
          if (leave == 'switch-agent') {
            await chooseMode(
              tester,
              owner == 'xiaowan-acp' ? 'codex-acp' : 'xiaowan-acp',
            );
          } else if (leave == 'switch-mode') {
            await chooseMode(tester, kNewConversationPureChatModeId);
          }
          await disposeChat(tester);

          final closes = agentCalls.where(
            (call) => call.method == 'session/close',
          );
          expect(closes, hasLength(1));
          expect(closes.single.arguments, {
            'sessionId': 'new-draft-session',
            'agentId': owner,
          });
          expect(
            agentCalls.where((call) => call.method == 'session/prompt'),
            isEmpty,
          );
          expect(conversations, isEmpty);
        },
      );
    }
  }

  testWidgets(
    'late config session from a previous draft cannot replace the new target session',
    (tester) async {
      allowConversationCreation = true;
      await pumpChat(
        tester,
        const ConversationThreadTarget.newConversation(agentId: 'xiaowan-acp'),
      );
      final oldSessionGate = sessionNewGate = Completer<void>();
      await tester.tap(find.byKey(const ValueKey('chat-acp-config-button')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 350));
      expect(
        agentCalls.where((call) => call.method == 'session/new'),
        hasLength(1),
      );
      await dismissConfigPanel(tester);
      await chooseMode(tester, 'codex-acp');
      expect(selector(tester).selectedId, 'codex-acp');
      sessionNewGate = null;
      oldSessionGate.complete();
      await tester.pumpAndSettle();
      expectNoPersistedDraft();
      expect(selector(tester).selectedId, 'codex-acp');

      await tester.tap(find.byKey(const ValueKey('chat-acp-config-button')));
      await tester.pumpAndSettle();
      final newSessions = agentCalls
          .where((call) => call.method == 'session/new')
          .toList();
      expect(newSessions, hasLength(2));
      expect((newSessions.last.arguments as Map)['agentId'], 'codex-acp');
      expectNoPersistedDraft();
      await dismissConfigPanel(tester);
      composer(tester).controller.text = '只发送给新草稿';
      composer(tester).onSendMessage();
      await tester.pumpAndSettle();
      final prompt = agentCalls.singleWhere(
        (call) => call.method == 'session/prompt',
      );
      expect((prompt.arguments as Map)['agentId'], 'codex-acp');
      expect((prompt.arguments as Map)['sessionId'], 'new-draft-session-2');
      expect(
        agentCalls.where((call) => call.method == 'session/new'),
        hasLength(2),
      );
      expect(
        assistCalls.where((call) => call.method == 'createConversation'),
        hasLength(1),
      );
      await disposeChat(tester);
    },
  );

  testWidgets(
    'concurrent draft config and command reads share session preparation with first send',
    (tester) async {
      allowConversationCreation = true;
      await pumpChat(
        tester,
        const ConversationThreadTarget.newConversation(agentId: 'xiaowan-acp'),
      );
      await attachFile(tester, 'submitted.pdf');
      final submittedAttachmentId = composer(tester).attachments.single.id;
      sessionNewGate = Completer<void>();
      await tester.tap(find.byKey(const ValueKey('chat-acp-config-button')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 350));
      final config = tester.widget<AcpConfigButton>(
        find.byType(AcpConfigButton),
      );
      final repeatedLoad = config.load();
      composer(tester).onTriggerSlashCommand!();
      composer(tester).controller.text = '等待配置完成后发送一次';
      composer(tester).onSendMessage();
      composer(tester).onSendMessage();
      await tester.pump();
      composer(tester).controller.text = '下一条草稿';
      composer(tester).onRemoveAttachment!(submittedAttachmentId);
      await attachFile(tester, 'next-draft.pdf', settle: false);
      final nextDraftAttachment = composer(tester).attachments.single;

      expect(
        agentCalls.where((call) => call.method == 'session/new'),
        hasLength(1),
      );
      expectNoPersistedDraft();
      expect(selector(tester).selectedId, 'xiaowan-acp');

      sessionNewGate!.complete();
      expect((await repeatedLoad)['sessionId'], 'new-draft-session');
      await tester.pumpAndSettle();
      expect(
        agentCalls.where((call) => call.method == 'session/new'),
        hasLength(1),
      );
      expect(
        assistCalls.where((call) => call.method == 'createConversation'),
        hasLength(1),
      );
      expect(conversations, hasLength(1));
      final prompt = agentCalls.singleWhere(
        (call) => call.method == 'session/prompt',
      );
      expect((prompt.arguments as Map)['conversationId'], 901);
      expect((prompt.arguments as Map)['sessionId'], 'new-draft-session');
      expect((prompt.arguments as Map)['text'], '等待配置完成后发送一次');
      final submittedAttachments =
          ((prompt.arguments as Map)['attachments'] as List).cast<Map>();
      expect(submittedAttachments, hasLength(1));
      expect(submittedAttachments.single['id'], submittedAttachmentId);
      expect(submittedAttachments.single['name'], 'submitted.pdf');
      expect(composer(tester).controller.text, '下一条草稿');
      expect(composer(tester).attachments.single.id, nextDraftAttachment.id);
      expect(composer(tester).attachments.single.name, 'next-draft.pdf');
      await disposeChat(tester);
      expect(
        agentCalls.where((call) => call.method == 'session/close'),
        isEmpty,
      );
    },
  );

  testWidgets(
    'unowned Agent history stays readable without selecting a default runtime',
    (tester) async {
      conversations = [
        historyConversation(811, ConversationMode.agent)..remove('agentId'),
      ];
      historyPages[811] = historyMessage(811);
      await pumpChat(
        tester,
        const ConversationThreadTarget.existing(conversationId: 811),
      );
      expect(find.text('已加载历史消息 811'), findsOneWidget);
      expect(find.byKey(const Key('chat-agent-unavailable')), findsOneWidget);
      expect(find.byType(ChatInputArea), findsNothing);
      expect(conversations.single['agentId'], isNull);
      expect(
        agentCalls.where(
          (call) =>
              call.method == 'session/new' || call.method == 'session/prompt',
        ),
        isEmpty,
      );
      expect(agentCalls.where((call) => call.method == 'status'), isEmpty);
      await disposeChat(tester);
    },
  );

  for (final globalRemote in [false, true]) {
    for (final configureFirst in [false, true]) {
      testWidgets(
        'Xiaowan owns first send with unavailable default Agent: remote=$globalRemote configured=$configureFirst',
        (tester) async {
          allowConversationCreation = true;
          reportedStatusAgentId = 'opencode-acp';
          reportGlobalRemote = globalRemote;
          failDefaultConnect = true;
          await pumpChat(
            tester,
            const ConversationThreadTarget.newConversation(
              agentId: 'xiaowan-acp',
            ),
          );
          if (configureFirst) {
            await tester.tap(
              find.byKey(const ValueKey('chat-acp-config-button')),
            );
            await tester.pumpAndSettle();
            await dismissConfigPanel(tester);
          }
          composer(tester).controller.text = '你好';
          composer(tester).onSendMessage();
          composer(tester).onSendMessage();
          await tester.pumpAndSettle();

          expect(agentCalls.where((call) => call.method == 'connect'), isEmpty);
          final sessions = agentCalls.where(
            (call) => call.method == 'session/new',
          );
          expect(sessions, hasLength(1));
          expect((sessions.single.arguments as Map)['agentId'], 'xiaowan-acp');
          final prompts = agentCalls.where(
            (call) => call.method == 'session/prompt',
          );
          expect(prompts, hasLength(1));
          expect((prompts.single.arguments as Map)['agentId'], 'xiaowan-acp');
          expect((prompts.single.arguments as Map)['conversationId'], 901);
          expect(
            (prompts.single.arguments as Map)['sessionId'],
            'new-draft-session',
          );
          expect((prompts.single.arguments as Map)['text'], '你好');
          for (final load in agentCalls.where(
            (call) => call.method == 'session/load',
          )) {
            expect((load.arguments as Map)['agentId'], 'xiaowan-acp');
          }
          expect(conversations.single['agentId'], 'xiaowan-acp');
          await disposeChat(tester);
        },
      );
    }
  }

  testWidgets(
    'failed configured session load never opens a replacement or replays the prompt',
    (tester) async {
      allowConversationCreation = true;
      await pumpChat(
        tester,
        const ConversationThreadTarget.newConversation(agentId: 'xiaowan-acp'),
      );
      await tester.tap(find.byKey(const ValueKey('chat-acp-config-button')));
      await tester.pumpAndSettle();
      await dismissConfigPanel(tester);
      failSessionLoad = true;
      composer(tester).controller.text = '保持原会话';
      composer(tester).onSendMessage();
      await tester.pumpAndSettle();

      expect(
        agentCalls.where((call) => call.method == 'session/new'),
        hasLength(1),
      );
      expect(
        agentCalls.where((call) => call.method == 'session/prompt'),
        isEmpty,
      );
      expect(agentCalls.where((call) => call.method == 'connect'), isEmpty);
      final loads = agentCalls.where((call) => call.method == 'session/load');
      expect(loads, hasLength(1));
      expect((loads.single.arguments as Map)['sessionId'], 'new-draft-session');
      expect((loads.single.arguments as Map)['agentId'], 'xiaowan-acp');
      expect(conversations, hasLength(1));
      expect(conversations.single['agentId'], 'xiaowan-acp');
      await disposeChat(tester);
    },
  );

  testWidgets(
    'local send uses its prepared session even when global status is unavailable',
    (tester) async {
      allowConversationCreation = true;
      await pumpChat(
        tester,
        const ConversationThreadTarget.newConversation(agentId: 'xiaowan-acp'),
      );
      await tester.tap(find.byKey(const ValueKey('chat-acp-config-button')));
      await tester.pumpAndSettle();
      expectNoPersistedDraft();
      await dismissConfigPanel(tester);
      failAgentStatus = true;
      final callsBeforeSend = agentCalls.length;
      composer(tester).controller.text = '连接失败也要保留已经提交的消息';
      composer(tester).onSendMessage();
      await tester.pumpAndSettle();

      expect(
        assistCalls.where((call) => call.method == 'createConversation'),
        hasLength(1),
      );
      expect(conversations, hasLength(1));
      final persistedMessages = assistCalls
          .where((call) => call.method == 'replaceConversationMessages')
          .expand((call) => (call.arguments as Map)['messages'] as List)
          .map((message) => Map<String, dynamic>.from(message as Map));
      final persistedUsers = {
        for (final message in persistedMessages)
          if (message['user'] == 1) message['id']: message,
      }.values.toList();
      expect(persistedUsers, hasLength(1));
      expect(
        (persistedUsers.single['content'] as Map)['text'],
        '连接失败也要保留已经提交的消息',
      );
      expect(
        agentCalls.where((call) => call.method == 'session/prompt'),
        hasLength(1),
      );
      expect(
        agentCalls.where((call) => call.method == 'session/new'),
        hasLength(1),
      );
      expect(
        agentCalls
            .skip(callsBeforeSend)
            .where(
              (call) => call.method == 'status' || call.method == 'connect',
            ),
        isEmpty,
      );
      await disposeChat(tester);
      expect(conversations, hasLength(1));
    },
  );

  testWidgets(
    'a late draft config refresh cannot mark its now committed session as unbound',
    (tester) async {
      allowConversationCreation = true;
      await pumpChat(
        tester,
        const ConversationThreadTarget.newConversation(agentId: 'xiaowan-acp'),
      );
      await tester.tap(find.byKey(const ValueKey('chat-acp-config-button')));
      await tester.pumpAndSettle();
      final config = tester.widget<AcpConfigButton>(
        find.byType(AcpConfigButton),
      );
      sessionLoadGate = Completer<void>();
      final pendingRefresh = config.refresh!();
      await tester.pump();
      expectNoPersistedDraft();
      expect(
        (agentCalls.lastWhere((call) => call.method == 'session/load').arguments
                as Map)
            .containsKey('conversationId'),
        isFalse,
      );

      composer(tester).controller.text = '刷新仍在等待时发送';
      composer(tester).onSendMessage();
      await tester.pumpAndSettle();
      expect(
        assistCalls.where((call) => call.method == 'createConversation'),
        hasLength(1),
      );
      final prompt = agentCalls.singleWhere(
        (call) => call.method == 'session/prompt',
      );
      expect((prompt.arguments as Map)['sessionId'], 'new-draft-session');
      sessionLoadGate!.complete();
      expect((await pendingRefresh)['sessionId'], 'new-draft-session');
      await tester.pumpAndSettle();

      await disposeChat(tester);
      // Leaving the page may release unused configuration sessions only.
      // This session has already been adopted by the committed conversation.
      expect(
        agentCalls.where((call) => call.method == 'session/close'),
        isEmpty,
      );
      expect(conversations, hasLength(1));
      expect(
        agentCalls.where((call) => call.method == 'session/new'),
        hasLength(1),
      );
    },
  );

  testWidgets(
    'Agent switching preserves edits and attachment changes in flight',
    (tester) async {
      await pumpChat(
        tester,
        const ConversationThreadTarget.newConversation(agentId: 'xiaowan-acp'),
      );
      composer(tester).controller.text = '切换前的草稿';
      await attachFile(tester, 'old.pdf');
      final oldAttachmentId = composer(tester).attachments.single.id;
      initializeGate = Completer<void>();
      await chooseMode(tester, 'codex-acp');

      expect(selector(tester).enabled, isFalse);
      expect(composer(tester).controller.text, '切换前的草稿');
      expect(composer(tester).attachments.single.id, oldAttachmentId);
      const editedValue = TextEditingValue(
        text: '等待期间继续编辑的草稿',
        selection: TextSelection(baseOffset: 2, extentOffset: 7),
      );
      composer(tester).controller.value = editedValue;
      composer(tester).onRemoveAttachment!(oldAttachmentId);
      await attachFile(tester, 'latest.pdf');
      final latestAttachment = composer(tester).attachments.single;
      expectNoSessionAdmission();

      initializeGate!.complete();
      await tester.pumpAndSettle();
      expect(selector(tester).selectedId, 'codex-acp');
      expect(selector(tester).enabled, isTrue);
      expect(composer(tester).controller.value, editedValue);
      expect(composer(tester).attachments.single.id, latestAttachment.id);
      expect(composer(tester).attachments.single.name, 'latest.pdf');
      expectNoSessionAdmission();
      await disposeChat(tester);
    },
  );

  testWidgets('pure chat and Agent round trip carries the same unsent draft', (
    tester,
  ) async {
    await pumpChat(
      tester,
      const ConversationThreadTarget.newConversation(agentId: 'codex-acp'),
    );
    const draft = TextEditingValue(
      text: '带附件的草稿',
      selection: TextSelection.collapsed(offset: 3),
    );
    composer(tester).controller.value = draft;
    await attachFile(tester, 'draft.pdf');
    final attachmentId = composer(tester).attachments.single.id;

    await chooseMode(tester, kNewConversationPureChatModeId);
    expect(selector(tester).selectedId, kNewConversationPureChatModeId);
    expect(composer(tester).controller.value, draft);
    expect(composer(tester).attachments.single.id, attachmentId);
    expect(
      (assistCalls
              .lastWhere((call) => call.method == 'setCurrentConversationId')
              .arguments
          as Map)['mode'],
      ConversationMode.chatOnly.storageValue,
    );

    await chooseMode(tester, 'xiaowan-acp');
    expect(selector(tester).selectedId, 'xiaowan-acp');
    expect(composer(tester).controller.value, draft);
    expect(composer(tester).attachments.single.id, attachmentId);
    expect(
      (assistCalls
              .lastWhere((call) => call.method == 'setCurrentConversationId')
              .arguments
          as Map)['mode'],
      ConversationMode.agent.storageValue,
    );
    expectNoSessionAdmission();
    await disposeChat(tester);
  });

  testWidgets('first-use tour follows the current header and composer layout', (
    tester,
  ) async {
    await pumpChat(
      tester,
      const ConversationThreadTarget.newConversation(agentId: 'xiaowan-acp'),
      tour: true,
    );
    expect(find.byType(NewConversationModeSelector), findsNothing);
    expect(composer(tester).focusNode.hasFocus, isFalse);

    Rect spotlight(int step) {
      final painter =
          tester
                  .widget<CustomPaint>(
                    find.byKey(ValueKey('chat-spotlight-hole-$step')),
                  )
                  .painter
              as dynamic;
      return painter.spotlight as Rect;
    }

    expect(
      spotlight(0),
      tester
          .getRect(find.byKey(const ValueKey('chat-app-bar-menu-button')))
          .inflate(6),
    );
    await tester.tapAt(const Offset(8, 400));
    await tester.pumpAndSettle();
    expect(find.text('对话标题与模式'), findsOneWidget);
    expect(
      spotlight(1).contains(
        tester.getCenter(find.byKey(const ValueKey('chat-app-bar-title'))),
      ),
      isTrue,
    );
    await tester.tapAt(const Offset(8, 400));
    await tester.pumpAndSettle();
    expect(find.text('对话选项'), findsOneWidget);
    expect(
      spotlight(2),
      tester
          .getRect(find.byKey(const ValueKey('chat-app-bar-more-button')))
          .inflate(6),
    );
    await tester.tapAt(const Offset(8, 400));
    await tester.pumpAndSettle();
    expect(composer(tester).focusNode.hasFocus, isTrue);
    expect(
      spotlight(ChatSpotlightTour.modelStep),
      tester
          .getRect(find.byKey(const ValueKey('chat-acp-config-button')))
          .inflate(4),
    );
    await tester.tapAt(const Offset(8, 400));
    await tester.pumpAndSettle();
    expect(find.text('输入、附件与发送'), findsOneWidget);
    expect(
      spotlight(4).contains(
        tester.getCenter(find.byKey(const ValueKey('chat-minimal-composer'))),
      ),
      isTrue,
    );
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('模型与参数'), findsOneWidget);
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('对话选项'), findsOneWidget);
    expect(composer(tester).focusNode.hasFocus, isFalse);
    expectNoSessionAdmission();
    await disposeChat(tester);
  });

  testWidgets(
    'bootstrap completion beneath another route does not steal focus',
    (tester) async {
      initializeGate = Completer<void>();
      final navigator = GlobalKey<NavigatorState>();
      await pumpChat(
        tester,
        const ConversationThreadTarget.newConversation(agentId: 'xiaowan-acp'),
        navigatorKey: navigator,
      );
      final inputFocus = composer(tester).focusNode;
      expect(inputFocus.hasFocus, isFalse);
      unawaited(
        navigator.currentState!.push<void>(
          MaterialPageRoute(builder: (_) => const Scaffold(body: Text('覆盖页面'))),
        ),
      );
      await tester.pumpAndSettle();
      initializeGate!.complete();
      await tester.pumpAndSettle();
      expect(inputFocus.hasFocus, isFalse);
      expect(tester.testTextInput.isVisible, isFalse);
      navigator.currentState!.pop();
      await tester.pumpAndSettle();
      expect(inputFocus.hasFocus, isFalse);
      expectNoSessionAdmission();
      await disposeChat(tester);
    },
  );

  testWidgets('disposing during bootstrap does not request composer focus', (
    tester,
  ) async {
    initializeGate = Completer<void>();
    await pumpChat(
      tester,
      const ConversationThreadTarget.newConversation(agentId: 'xiaowan-acp'),
    );
    await disposeChat(tester);
    initializeGate!.complete();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(tester.testTextInput.isVisible, isFalse);
    expectNoSessionAdmission();
  });

  testWidgets('send waits for Agent switch and submits once to the new Agent', (
    tester,
  ) async {
    allowConversationCreation = true;
    await pumpChat(
      tester,
      const ConversationThreadTarget.newConversation(agentId: 'xiaowan-acp'),
    );
    composer(tester).controller.text = '切换后发送给 Codex';
    initializeGate = Completer<void>();
    await chooseMode(tester, 'codex-acp');
    composer(tester).onSendMessage();
    composer(tester).onSendMessage();
    await tester.pumpAndSettle();
    expectNoSessionAdmission();

    composer(tester).controller.text = '发送后继续编辑的下一条草稿';
    initializeGate!.complete();
    await tester.pumpAndSettle();
    final creations = assistCalls
        .where((call) => call.method == 'createConversation')
        .toList();
    final prompts = agentCalls
        .where((call) => call.method == 'session/prompt')
        .toList();
    expect(creations, hasLength(1));
    expect((creations.single.arguments as Map)['agentId'], 'codex-acp');
    expect(prompts, hasLength(1));
    expect((prompts.single.arguments as Map)['agentId'], 'codex-acp');
    expect((prompts.single.arguments as Map)['text'], '切换后发送给 Codex');
    expect(composer(tester).controller.text, '发送后继续编辑的下一条草稿');
    expect(agentCalls.where((call) => call.method == 'agent/select'), isEmpty);
    expect(find.byType(NewConversationModeSelector), findsNothing);
    await disposeChat(tester);
  });

  testWidgets('send during initial bootstrap retains input for its target', (
    tester,
  ) async {
    allowConversationCreation = true;
    initializeGate = Completer<void>();
    await pumpChat(
      tester,
      const ConversationThreadTarget.newConversation(agentId: 'codex-acp'),
    );
    composer(tester).controller.text = '初始化期间输入并发送';
    composer(tester).onSendMessage();
    await tester.pumpAndSettle();
    expectNoSessionAdmission();

    initializeGate!.complete();
    await tester.pumpAndSettle();
    final prompts = agentCalls
        .where((call) => call.method == 'session/prompt')
        .toList();
    expect(prompts, hasLength(1));
    expect((prompts.single.arguments as Map)['agentId'], 'codex-acp');
    expect((prompts.single.arguments as Map)['text'], '初始化期间输入并发送');
    expect(composer(tester).focusNode.hasFocus, isFalse);
    await disposeChat(tester);
  });

  testWidgets(
    'initial-message entry sends once without showing draft controls',
    (tester) async {
      allowConversationCreation = true;
      await pumpChat(
        tester,
        const ConversationThreadTarget.newConversation(
          agentId: 'codex-acp',
          initialMessage: '来自已有入口的任务',
          requestKey: 'initial-task',
        ),
      );
      final prompts = agentCalls
          .where((call) => call.method == 'session/prompt')
          .toList();
      expect(prompts, hasLength(1));
      expect((prompts.single.arguments as Map)['agentId'], 'codex-acp');
      expect((prompts.single.arguments as Map)['text'], '来自已有入口的任务');
      expect(find.byType(NewConversationModeSelector), findsNothing);
      expect(composer(tester).focusNode.hasFocus, isFalse);
      expect(tester.testTextInput.isVisible, isFalse);
      await disposeChat(tester);
    },
  );
}
