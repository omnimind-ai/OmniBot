import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/features/home/pages/command_overlay/chat_bot_sheet.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/storage_service.dart';
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

  testWidgets(
    'native link preview can complete after the new conversation receives its id',
    (tester) async {
      final release = Completer<void>();
      var created = false;
      messenger.setMockMethodCallHandler(assistChannel, (call) async {
        switch (call.method) {
          case 'createConversation':
            created = true;
            return 1001;
          case 'getConversations':
          case 'getSceneCatalog':
          case 'getModelProviderProfiles':
            return <dynamic>[];
          case 'reconcileChatLinkPreviews':
            await release.future;
            return [
              {'url': 'https://example.com/docs', 'status': 'loading'},
            ];
          case 'loadChatLinkPreview':
            return {
              'url': 'https://example.com/docs',
              'title': 'Native preview title',
              'status': 'ready',
            };
          default:
            return null;
        }
      });
      messenger.setMockMethodCallHandler(runtimeChannel, (call) async {
        if (call.method == 'status')
          return {'connected': true, 'activeAgentId': 'test-agent'};
        if (call.method == 'session/new')
          return {'sessionId': 'preview-session'};
        if (call.method == 'session/prompt')
          return {'sessionId': 'preview-session', 'stopReason': 'end_turn'};
        return null;
      });
      tester.view.physicalSize = const Size(1080, 2200);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      try {
        await tester.pumpWidget(
          MaterialApp(
            theme: AppTheme.lightTheme,
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
            locale: const Locale('zh'),
            home: const Scaffold(
              body: ChatBotSheet(initialMessage: 'https://example.com/docs'),
            ),
          ),
        );
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 100));
        expect(created, isTrue);
        release.complete();
        for (var i = 0; i < 10; i++) {
          await tester.pump(const Duration(milliseconds: 50));
        }
        expect(find.text('Native preview title'), findsOneWidget);
        expect(tester.takeException(), isNull);
      } finally {
        if (!release.isCompleted) release.complete();
        await tester.pumpWidget(const SizedBox.shrink());
        await tester.pump();
      }
    },
  );
}
