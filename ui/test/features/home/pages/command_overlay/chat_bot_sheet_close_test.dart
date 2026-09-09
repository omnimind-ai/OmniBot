import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/features/home/pages/command_overlay/chat_bot_sheet.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/chat_input_area.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/message_bubble.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/services/screen_dialog_service.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const runtimeChannel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  const eventsChannel = MethodChannel('cn.com.omnimind.bot/AgentRuntimeEvents');
  const assistChannel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  const speechChannel = MethodChannel('cn.com.omnimind.bot/SpeechRecognition');
  const screenChannel = MethodChannel('cn.com.omnimind.bot/ScreenDialogEvent');
  const voiceChannel = MethodChannel('cn.com.omnimind.bot/VoicePlayback');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final coordinator = ChatConversationRuntimeCoordinator.instance;

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    coordinator.resetForTest();
    messenger.setMockMethodCallHandler(eventsChannel, (_) async => null);
    messenger.setMockMethodCallHandler(speechChannel, (_) async => true);
    messenger.setMockMethodCallHandler(screenChannel, (_) async => null);
    messenger.setMockMethodCallHandler(voiceChannel, (_) async => true);
    messenger.setMockMethodCallHandler(assistChannel, (call) async {
      switch (call.method) {
        case 'createConversation':
          return 1001;
        case 'getConversations':
        case 'getSceneCatalog':
        case 'getSceneModelBindings':
          return <Map<String, dynamic>>[];
        case 'getSceneVoiceConfig':
          return <String, dynamic>{'autoPlay': false};
        default:
          return null;
      }
    });
  });

  tearDown(() {
    coordinator.resetForTest();
    for (final channel in <MethodChannel>[
      runtimeChannel,
      eventsChannel,
      assistChannel,
      speechChannel,
      screenChannel,
      voiceChannel,
    ]) {
      messenger.setMockMethodCallHandler(channel, null);
    }
  });

  testWidgets('failed close retains the prompt and permits explicit close retry', (tester) async {
    final prompt = Completer<Map<String, dynamic>>();
    final requests = <MethodCall>[];
    messenger.setMockMethodCallHandler(runtimeChannel, (call) async {
      requests.add(call);
      switch (call.method) {
        case 'status':
          return {'connected': true, 'activeAgentId': 'test-agent'};
        case 'session/new':
          return {'sessionId': 'close-session'};
        case 'session/prompt':
          return prompt.future;
        case 'session/close':
          if (requests.where((c) => c.method == 'session/close').length == 1) {
            throw PlatformException(code: 'close_failed', message: 'Close transport failed');
          }
          return {'closed': true};
        default:
          return null;
      }
    });
    try {
      await tester.pumpWidget(MaterialApp(
        theme: AppTheme.lightTheme,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: const Scaffold(body: ChatBotSheet(initialMessage: '检查关闭失败')),
      ));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      final runtime = coordinator.runtimeFor(conversationId: 1001, mode: 'command_overlay')!;
      await ScreenDialogService.closeChatBotDialog();
      await tester.pump();
      expect(runtime.isAiResponding, isTrue);
      expect(requests.where((c) => c.method == 'session/close'), hasLength(1));
      expect(requests.where((c) => c.method == 'session/cancel'), isEmpty,
          reason: 'The native close owner handles cancellation; the presentation must not duplicate it.');
      await ScreenDialogService.closeChatBotDialog();
      await tester.pump();
      expect(requests.where((c) => c.method == 'session/close'), hasLength(2));
      expect(requests.where((c) => c.method == 'session/prompt'), hasLength(1));
      expect(runtime.isAiResponding, isTrue);
    } finally {
      prompt.complete({'stopReason': 'cancelled'});
      await tester.pump();
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    }
  });

}
