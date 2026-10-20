import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_runtime_coordinator.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/services/jackery_ces_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final coordinator = ChatConversationRuntimeCoordinator.instance;
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    coordinator.resetForTest();
  });
  tearDown(() {
    coordinator.resetForTest();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(JackeryCesService.channel, null);
  });

  test('native failure propagates without a success notification', () async {
    final before = JackeryCesService.demoReset.value;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          JackeryCesService.channel,
          (_) async => throw PlatformException(
            code: 'DEMO_RESET_FAILED',
            message: 'The active AI request has not stopped.',
          ),
        );
    await expectLater(
      JackeryCesService.resetDemo(),
      throwsA(isA<PlatformException>()),
    );
    expect(JackeryCesService.demoReset.value, before);
    expect(JackeryCesService.isResetting, false);
  });

  test(
    'partial native deletion discards old runtime while reporting failure',
    () async {
      final before = JackeryCesService.demoReset.value;
      coordinator.ensureRuntime(
        conversationId: 91,
        mode: kChatRuntimeModeNormal,
        conversation: ConversationModel(
          id: 91,
          title: 'visitor history',
          status: 0,
          mode: ConversationMode.chatOnly,
          messageCount: 1,
          createdAt: 1,
          updatedAt: 1,
        ),
        initialMessages: [
          ChatMessageModel(
            id: 'user-91',
            type: 1,
            user: 1,
            createAt: DateTime(2026),
            content: {'text': 'A deleted visitor question'},
          ),
        ],
      );
      await coordinator.prepareForDemoReset();
      final oldRuntime = coordinator.runtimeFor(
        conversationId: 91,
        mode: kChatRuntimeModeNormal,
      );
      expect(oldRuntime, isNotNull);
      var writesAfterDeletion = 0;
      const historyChannel = MethodChannel(
        'cn.com.omnimind.bot/AssistCoreEvent',
      );
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(historyChannel, (_) async {
            writesAfterDeletion++;
            return 'SUCCESS';
          });
      addTearDown(
        () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(historyChannel, null),
      );
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(JackeryCesService.channel, (_) async {
            throw PlatformException(
              code: 'DEMO_RESET_FAILED',
              message: 'Some demo data has been cleared.',
              details: {'contentChanged': true},
            );
          });
      await expectLater(
        JackeryCesService.resetDemo(),
        throwsA(isA<PlatformException>()),
      );
      expect(
        coordinator.runtimeFor(
          conversationId: 91,
          mode: kChatRuntimeModeNormal,
        ),
        isNull,
      );
      expect(coordinator.demoResetInProgress, false);
      expect(JackeryCesService.demoReset.value, before + 1);
      expect(JackeryCesService.isResetting, false);
      await coordinator.persistRuntimeConversation(
        conversationId: 91,
        mode: kChatRuntimeModeNormal,
        persistMessages: true,
      );
      expect(
        writesAfterDeletion,
        0,
        reason: 'A deleted runtime cannot recreate rows during page disposal',
      );
    },
  );

  test('concurrent reset is rejected and success is notified after native completion', () async {
    final before = JackeryCesService.demoReset.value;
    final result = Completer<Map<String, dynamic>>();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          JackeryCesService.channel,
          (_) => result.future,
        );
    final operation = JackeryCesService.resetDemo();
    expect(JackeryCesService.isResetting, true);
    expect(JackeryCesService.demoReset.value, before);
    await expectLater(
      JackeryCesService.resetDemo(),
      throwsA(isA<StateError>()),
    );
    result.complete({'success': true});
    await operation;
    expect(JackeryCesService.demoReset.value, before + 1);
    expect(JackeryCesService.isResetting, false);
  });
}
